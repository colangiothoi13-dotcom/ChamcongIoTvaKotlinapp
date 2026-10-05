// Run only with the local Firestore emulator. The demo project cannot reach production.
const test = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-attendance-final-fix";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085";
const database = `projects/${project}/databases/(default)`;
const base = `http://${host}/v1/${database}/documents`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function adminToken(id) {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project, sub: id, user_id: id,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: "password", identities: {} }
  })}.`;
}
const string = value => ({ stringValue: value });
const strings = values => ({ arrayValue: { values: values.map(string) } });
async function request(url, auth, body, method = "POST") {
  return fetch(url, {
    method, headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(15000)
  });
}
async function success(response) {
  const body = await response.text();
  assert.equal(response.status, 200, body);
  return JSON.parse(body);
}
async function seed(path, fields) {
  await success(await request(`${base}/${path}`, "owner", { fields }, "PATCH"));
}
async function begin(auth) {
  return (await success(await request(`${base}:beginTransaction`, auth, {}))).transaction;
}
async function readSchedule(path, auth, transaction) {
  const result = await success(await request(`${base}:batchGet`, auth, {
    documents: [`${database}/documents/${path}`], transaction
  }));
  return result.find(document => document.found)?.found.fields;
}
function assignmentFields(existing, selectedId, selectedCategory, shifts, actor) {
  const previousIds = existing.shiftIds.arrayValue.values.map(value => value.stringValue);
  const ids = [...previousIds.filter(id => shifts[id].category !== selectedCategory), selectedId]
    .filter((id, index, all) => all.indexOf(id) === index)
    .sort((first, second) => shifts[first].start.localeCompare(shifts[second].start));
  return {
    employeeId: existing.employeeId, employeeName: existing.employeeName, department: existing.department,
    date: existing.date, shiftId: string(ids[0]), shiftIds: strings(ids),
    shiftName: string(ids.map(id => shifts[id].name).join(" + ")),
    overtimeHours: existing.overtimeHours, assignedBy: string(actor), source: string("DEPARTMENT")
  };
}
async function commit(path, fields, auth, transaction) {
  return request(`${base}:commit`, auth, {
    transaction, writes: [{
      update: { name: `${database}/documents/${path}`, fields },
      // Matches the repository's SetOptions.merge: no adjustment or note fields are written.
      updateMask: { fieldPaths: Object.keys(fields) }
    }]
  });
}

test("two admins retry department transactions without losing the other shift or adjustments", { timeout: 60000 }, async () => {
  const suffix = `${Date.now()}-${process.pid}`;
  const adminA = `department-admin-a-${suffix}`;
  const adminB = `department-admin-b-${suffix}`;
  const tokenA = adminToken(adminA);
  const tokenB = adminToken(adminB);
  await seed(`users/${adminA}`, { role: string("ADMIN"), active: { booleanValue: true } });
  await seed(`users/${adminB}`, { role: string("ADMIN"), active: { booleanValue: true } });
  const morning = `department-morning-${suffix}`;
  const afternoon = `department-afternoon-${suffix}`;
  const previousMorning = `department-old-morning-${suffix}`;
  const shifts = {
    [morning]: { name: "Ca sáng", category: "MORNING", start: "08:00", end: "12:00" },
    [afternoon]: { name: "Ca chiều", category: "EVENING", start: "13:00", end: "17:00" },
    [previousMorning]: { name: "Ca sáng cũ", category: "MORNING", start: "07:00", end: "11:00" }
  };
  for (const [id, shift] of Object.entries(shifts)) {
    await seed(`shifts/${id}`, {
      name: string(shift.name), category: string(shift.category), startTime: string(shift.start), endTime: string(shift.end),
      active: { booleanValue: true }, countsOvertime: { booleanValue: false }, effectiveFrom: string("2026-01-01"),
      allowEarlyMinutes: { integerValue: "0" }, lateGraceMinutes: { integerValue: "0" },
      earlyLeaveAllowedMinutes: { integerValue: "0" }, missingCheckOutGraceMinutes: { integerValue: "60" }
    });
  }
  const path = `workSchedules/department-employee-${suffix}_2026-09-21`;
  await seed(path, {
    employeeId: string(`department-employee-${suffix}`), employeeName: string("An"), department: string("IT"),
    date: string("2026-09-21"), shiftId: string(previousMorning), shiftIds: strings([previousMorning]),
    shiftName: string("Ca sáng cũ"), overtimeHours: { integerValue: "2" }, source: string("ADMIN"),
    workedHoursOverride: { doubleValue: 7.5 }, adjustmentNote: string("Điều chỉnh đã duyệt"), note: string("Ghi chú cũ")
  });
  const transactionA = await begin(tokenA);
  const transactionB = await begin(tokenB);
  const readA = await readSchedule(path, tokenA, transactionA);
  const readB = await readSchedule(path, tokenB, transactionB);
  // REST transactions hold shared read locks. Submit both lock upgrades together;
  // either admin may win, or both may abort and retry after the lock conflict.
  const attempts = [
    { auth: tokenA, actor: adminA, selected: morning, category: "MORNING", transaction: transactionA, read: readA },
    { auth: tokenB, actor: adminB, selected: afternoon, category: "EVENING", transaction: transactionB, read: readB }
  ];
  const outcomes = await Promise.all(attempts.map(async attempt => {
    const response = await commit(path, assignmentFields(attempt.read, attempt.selected, attempt.category, shifts,
      attempt.actor), attempt.auth, attempt.transaction);
    return { attempt, status: response.status, body: await response.text() };
  }));
  assert.ok(outcomes.filter(outcome => outcome.status === 200).length <= 1,
    "Conflicting stale transactions must not both commit");
  const aborted = outcomes.filter(outcome => outcome.status !== 200);
  assert.ok(aborted.length > 0, "At least one conflicting transaction must retry");
  for (const stale of aborted) {
    assert.equal(stale.status, 409, stale.body);
    assert.equal(JSON.parse(stale.body).error.status, "ABORTED");
    const loser = stale.attempt;
    const retry = await begin(loser.auth);
    const newest = await readSchedule(path, loser.auth, retry);
    await success(await commit(path, assignmentFields(newest, loser.selected, loser.category, shifts, loser.actor), loser.auth, retry));
  }
  // Repeat assignment in a new transaction to check idempotence at the persistence boundary.
  const repeated = await begin(tokenB);
  const current = await readSchedule(path, tokenB, repeated);
  await success(await commit(path, assignmentFields(current, afternoon, "EVENING", shifts, adminB), tokenB, repeated));
  const final = (await success(await request(`${base}/${path}`, tokenA, undefined, "GET"))).fields;
  assert.deepEqual(final.shiftIds.arrayValue.values.map(value => value.stringValue), [morning, afternoon]);
  assert.equal(final.shiftId.stringValue, morning);
  assert.equal(final.workedHoursOverride.doubleValue, 7.5);
  assert.equal(final.adjustmentNote.stringValue, "Điều chỉnh đã duyệt");
  assert.equal(final.note.stringValue, "Ghi chú cũ");
  assert.equal(final.overtimeHours.integerValue, "2");
});
