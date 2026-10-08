// Emulator-only regression for the first transaction read before a weekly submission.
const { before, test } = require("node:test");
const assert = require("node:assert/strict");

const project = "demo-weekly-schedule-submission-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085";
assert.match(host, /^(localhost|127\.0\.0\.1|\[::1\]):\d+$/);
const documents = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
const documentName = path => `projects/${project}/databases/(default)/documents/${path}`;
const suffix = `${Date.now()}-${process.pid}`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function actor(label, provider = "password") {
  const uid = `${label}-${suffix}`, now = Math.floor(Date.now() / 1000);
  return { uid, token: `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.` };
}
// The ID deliberately includes underscores and regexp metacharacters.
const employee = { ...actor("employee"), employeeId: `own_employee.+[x]_${suffix}` };
const other = { ...actor("other"), employeeId: `other_${suffix}` };
const admin = actor("admin");
const anonymous = actor("device", "anonymous");
const unlinked = actor("unlinked");
const inactive = { ...actor("inactive"), employeeId: employee.employeeId };
const futureMonday = new Date();
futureMonday.setUTCHours(0, 0, 0, 0);
futureMonday.setUTCDate(futureMonday.getUTCDate() + 14);
while (futureMonday.getUTCDay() !== 1) futureMonday.setUTCDate(futureMonday.getUTCDate() + 1);
const weekStart = futureMonday.toISOString().slice(0, 10);
const ownId = `${employee.employeeId}_${weekStart}`;
const otherId = `${other.employeeId}_${weekStart}`;

function field(value) {
  if (value === null) return { nullValue: null };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(field) } };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "object") return { mapValue: { fields: fields(value) } };
  return { stringValue: value };
}
const fields = value => Object.fromEntries(Object.entries(value).map(([key, value]) => [key, field(value)]));
async function request(path, method, caller, body) {
  return fetch(`${documents}${path}`, {
    method, headers: { Authorization: `Bearer ${typeof caller === "string" ? caller : caller.token}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
}
async function expectStatus(response, status) { assert.equal(response.status, status, await response.text()); }
async function seed(path, data) {
  const encodedPath = path.split('/').map(encodeURIComponent).join('/');
  await expectStatus(await request(`/${encodedPath}`, "PATCH", "owner", { fields: fields(data) }), 200);
}
function shifts(monday = futureMonday) {
  return Object.fromEntries(Array.from({ length: 6 }, (_, offset) => {
    const date = new Date(monday); date.setUTCDate(date.getUTCDate() + offset);
    return [date.toISOString().slice(0, 10), offset === 0 ? ["morning"] : []];
  }));
}
function draft(caller = employee, overrides = {}) {
  return { id: `${caller.employeeId}_${weekStart}`, employeeId: caller.employeeId,
    employeeName: `Employee ${caller.employeeId}`, department: "Engineering", weekStart,
    shiftsByDate: shifts(), status: "PENDING", reason: "Đăng ký lịch tuần", reviewNote: null,
    reviewerId: null, reviewerName: null, reviewedAt: null, ...overrides };
}
function auditWrite(id, caller) {
  return { update: { name: documentName(`audit_logs/submission-${suffix}-${Math.random().toString(36).slice(2)}`),
    fields: fields({ actorId: caller.uid, actorName: caller.uid, action: "SHIFT_UPDATE",
      targetType: "weeklyScheduleRequest", targetId: id, reason: "", details: "Weekly submission" }) },
    currentDocument: { exists: false }, updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] };
}
async function submit(id, data, caller = employee, transaction) {
  const requestWrite = { update: { name: documentName(`weeklyScheduleRequests/${id}`), fields: fields(data) },
    currentDocument: { exists: false }, updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] };
  return request(":commit", "POST", caller, { writes: [requestWrite, auditWrite(id, caller)], ...(transaction ? { transaction } : {}) });
}
async function read(id, caller = employee) { return request(`/weeklyScheduleRequests/${encodeURIComponent(id)}`, "GET", caller); }
async function update(id, changes, caller = employee) {
  return request(":commit", "POST", caller, { writes: [{
    update: { name: documentName(`weeklyScheduleRequests/${id}`), fields: fields(changes) },
    updateMask: { fieldPaths: Object.keys(changes) }, currentDocument: { exists: true }
  }, auditWrite(id, caller)] });
}
async function query(caller, employeeId) {
  const structuredQuery = { from: [{ collectionId: "weeklyScheduleRequests" }] };
  if (employeeId) structuredQuery.where = { fieldFilter: {
    field: { fieldPath: "employeeId" }, op: "EQUAL", value: field(employeeId)
  } };
  return request(":runQuery", "POST", caller, { structuredQuery });
}

before(async () => {
  for (const caller of [employee, other]) {
    await seed(`users/${caller.uid}`, { role: "EMPLOYEE", active: true, employeeId: caller.employeeId });
    await seed(`employees/${caller.employeeId}`, { fullName: `Employee ${caller.employeeId}`, active: true, department: "Engineering" });
  }
  await seed(`users/${inactive.uid}`, { role: "EMPLOYEE", active: false, employeeId: employee.employeeId });
  await seed(`users/${admin.uid}`, { role: "ADMIN", active: true });
});

test("first own GET returns not-found and the read-before-create transaction with audit succeeds", async () => {
  await expectStatus(await read(ownId), 404);
  const begun = await request(":beginTransaction", "POST", employee, { options: { readWrite: {} } });
  assert.equal(begun.status, 200, await begun.clone().text());
  const { transaction } = await begun.json();
  const missingRead = await request(":batchGet", "POST", employee, {
    documents: [documentName(`weeklyScheduleRequests/${ownId}`)], transaction
  });
  assert.equal(missingRead.status, 200, await missingRead.clone().text());
  const missingRows = await missingRead.json();
  assert(missingRows.some(row => row.missing === documentName(`weeklyScheduleRequests/${ownId}`)));
  await expectStatus(await submit(ownId, draft(), employee, transaction), 200);
  const stored = await read(ownId);
  assert.equal(stored.status, 200, await stored.clone().text());
  assert.equal((await stored.json()).fields.status.stringValue, "PENDING");
});

test("pending own requests can update while employee list queries retain their scope", async () => {
  await expectStatus(await update(ownId, { reason: "Cập nhật ghi chú", shiftsByDate: shifts() }), 200);
  await expectStatus(await query(employee, employee.employeeId), 200);
  await expectStatus(await query(employee), 403);
  await expectStatus(await query(employee, other.employeeId), 403);
});

test("other employees, inactive profiles and anonymous devices cannot probe a missing own slot", async () => {
  await expectStatus(await read(otherId), 403);
  await expectStatus(await read(otherId, other), 404);
  const later = new Date(futureMonday); later.setUTCDate(later.getUTCDate() + 21);
  const ownMissing = `${employee.employeeId}_${later.toISOString().slice(0, 10)}`;
  await expectStatus(await read(ownMissing), 404);
  await expectStatus(await read(ownMissing, admin), 404);
  await expectStatus(await read(ownMissing, anonymous), 403);
  await expectStatus(await read(ownMissing, inactive), 403);
  await expectStatus(await read(ownMissing, other), 403);
  await expectStatus(await read(ownMissing, unlinked), 403);
  await expectStatus(await submit(otherId, draft(other), other), 200);
  await expectStatus(await read(otherId), 403);
  await expectStatus(await read(ownId, other), 403);
});

test("missing malformed dates and IDs cannot gain the own-slot exception", async () => {
  const invalidIds = [employee.employeeId, `${employee.employeeId}_not-a-date`,
    `${employee.employeeId}_2026-02-30`, `${employee.employeeId}_2026-13-01`,
    `${employee.employeeId}_2026-10-13`, `${employee.employeeId}_${weekStart}_suffix`,
    `prefix_${ownId}`, `${employee.employeeId.replace('[x]', 'x')}_${weekStart}`];
  for (const id of invalidIds) await expectStatus(await read(id), 403);
});

test("an existing foreign record in a canonical own slot cannot use the missing-slot permission", async () => {
  const later = new Date(futureMonday); later.setUTCDate(later.getUTCDate() + 7);
  const id = `${employee.employeeId}_${later.toISOString().slice(0, 10)}`;
  await seed(`weeklyScheduleRequests/${id}`, { ...draft(other), createdAt: new Date(), id });
  await expectStatus(await read(id), 403);
  await expectStatus(await read(id, admin), 200);
});

test("an approved request stays immutable to employee submissions", async () => {
  await seed(`weeklyScheduleRequests/${ownId}`, {
    ...draft(), createdAt: new Date(), status: "APPROVED", reviewNote: "Đã duyệt",
    reviewerId: admin.uid, reviewerName: admin.uid, reviewedAt: new Date()
  });
  await expectStatus(await read(ownId), 200);
  await expectStatus(await update(ownId, { reason: "Sửa sau duyệt", status: "PENDING",
    reviewNote: null, reviewerId: null, reviewerName: null, reviewedAt: null }), 403);
});

test("the previous Saturday noon Vietnam deadline rejects past weeks and permits future weeks", async () => {
  const past = new Date(futureMonday); past.setUTCDate(past.getUTCDate() - 35);
  const pastWeek = past.toISOString().slice(0, 10), pastId = `${employee.employeeId}_${pastWeek}`;
  await expectStatus(await read(pastId), 404);
  await expectStatus(await submit(pastId, draft(employee, {
    id: pastId, weekStart: pastWeek, shiftsByDate: shifts(past)
  })), 403);
  await seed(`weeklyScheduleRequests/${pastId}`, {
    ...draft(employee, { id: pastId, weekStart: pastWeek, shiftsByDate: shifts(past) }), createdAt: new Date()
  });
  await expectStatus(await read(pastId), 200);
  await expectStatus(await update(pastId, { reason: "Cập nhật sau hạn" }), 403);
  const future = new Date(futureMonday); future.setUTCDate(future.getUTCDate() + 28);
  const futureWeek = future.toISOString().slice(0, 10), futureId = `${employee.employeeId}_${futureWeek}`;
  await expectStatus(await read(futureId), 404);
  await expectStatus(await submit(futureId, draft(employee, {
    id: futureId, weekStart: futureWeek, shiftsByDate: shifts(future)
  })), 200);
});
