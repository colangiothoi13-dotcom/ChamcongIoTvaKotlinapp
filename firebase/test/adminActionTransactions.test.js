// Persistence regression fixtures: local emulator only, never production credentials.
const test = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-attendance-final-fix";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085";
assert.match(host, /^(127\.0\.0\.1|localhost):\d+$/, "A local emulator is required");
const database = `projects/${project}/databases/(default)`;
const base = `http://${host}/v1/${database}/documents`;
const name = path => `${database}/documents/${path}`;
const string = value => ({ stringValue: value });
const integer = value => ({ integerValue: String(value) });
const bool = value => ({ booleanValue: value });
const array = values => ({ arrayValue: { values: values.map(string) } });
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function token(uid, provider = "password") {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.`;
}
async function request(path, auth, body, method = "POST") {
  return fetch(`${base}${path}`, {
    method, headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000)
  });
}
async function success(response) {
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return JSON.parse(text);
}
async function seed(path, fields) { await success(await request(`/${path}`, "owner", { fields }, "PATCH")); }
async function begin(auth) { return (await success(await request(":beginTransaction", auth, {}))).transaction; }
async function read(paths, auth, transaction) {
  const response = await success(await request(":batchGet", auth, {
    documents: paths.map(name), transaction
  }));
  return new Map(response.map(item => [item.found?.name || item.missing, item.found?.fields]));
}
async function commit(auth, transaction, writes) { return request(":commit", auth, { transaction, writes }); }
const update = (path, fields, mask) => ({
  update: { name: name(path), fields }, ...(mask ? { updateMask: { fieldPaths: mask } } : {})
});
async function outcomes(attempts) {
  return Promise.all(attempts.map(async attempt => {
    const response = await commit(attempt.auth, attempt.transaction, attempt.writes);
    return { status: response.status, body: await response.text() };
  }));
}
function assertConflict(results) {
  assert.ok(results.filter(result => result.status === 200).length <= 1,
    "A stale delete/create and competing update must not both commit");
  assert.ok(results.some(result => result.status === 409), "The conflicting read must cause retry");
  results.filter(result => result.status !== 200).forEach(result => {
    assert.equal(result.status, 409, result.body);
    assert.equal(JSON.parse(result.body).error.status, "ABORTED");
  });
}

test("copy transaction retries a stale empty target and preserves a concurrent manual schedule", { timeout: 60000 }, async () => {
  const suffix = `${Date.now()}-${process.pid}`;
  const admin = `copy-admin-${suffix}`;
  const auth = token(admin);
  const employee = `copy-employee-${suffix}`;
  const morning = `copy-morning-${suffix}`;
  const evening = `copy-evening-${suffix}`;
  const path = `workSchedules/${employee}_2026-10-12`;
  await seed(`users/${admin}`, { role: string("ADMIN"), active: bool(true) });
  await seed(`employees/${employee}`, { fullName: string("Fixture"), active: bool(true), department: string("IT") });
  for (const [id, category, start, end] of [
    [morning, "MORNING", "08:00", "12:00"], [evening, "EVENING", "13:00", "17:00"]
  ]) await seed(`shifts/${id}`, {
    name: string(category), category: string(category), startTime: string(start), endTime: string(end), active: bool(true)
  });
  const fields = shift => ({
    employeeId: string(employee), employeeName: string("Fixture"), department: string("IT"),
    date: string("2026-10-12"), shiftId: string(shift), shiftIds: array([shift]), shiftName: string(shift),
    overtimeHours: integer(0), assignedBy: string(admin), source: string("ADMIN_COPY")
  });
  const manual = {
    ...fields(evening), source: string("ADMIN"), overtimeHours: integer(2),
    workedHoursOverride: { doubleValue: 7.5 }, adjustmentNote: string("Previously approved"), note: string("Keep me")
  };
  // Both admins saw the same missing target, just as the preliminary week query may be stale.
  const copyTransaction = await begin(auth);
  const manualTransaction = await begin(auth);
  assert.equal((await read([path, `employees/${employee}`], auth, copyTransaction)).get(name(path)), undefined);
  assert.equal((await read([path], auth, manualTransaction)).get(name(path)), undefined);
  const results = await outcomes([
    { auth, transaction: copyTransaction, writes: [update(path, fields(morning))] },
    { auth, transaction: manualTransaction, writes: [update(path, manual)] }
  ]);
  assertConflict(results);
  if (results[1].status !== 200) {
    // A manual edit may intentionally replace a copy, but a copy must never replace a manual edit.
    const retry = await begin(auth);
    await read([path], auth, retry);
    await success(await commit(auth, retry, [update(path, manual)]));
  }
  const copyRetry = await begin(auth);
  const newest = (await read([path, `employees/${employee}`], auth, copyRetry)).get(name(path));
  assert.ok(newest, "The fresh copy transaction sees the existing target and returns zero writes");
  await success(await request(":rollback", auth, { transaction: copyRetry }));
  const final = (await success(await request(`/${path}`, auth, undefined, "GET"))).fields;
  assert.deepEqual(final, manual, "Every manual field, including adjustments, survives a copy retry");
});

test("device start and employee rollback cannot both commit, so accepted enrollment keeps its reservation", { timeout: 60000 }, async () => {
  const suffix = `${Date.now()}-${process.pid}`;
  const admin = `rollback-admin-${suffix}`;
  const auth = token(admin);
  const deviceAuth = token(`rollback-device-${suffix}`, "anonymous");
  const employee = `rollback-employee-${suffix}`;
  const device = `ROLLBACK-${suffix}`;
  const employeePath = `employees/${employee}`;
  const commandPath = `deviceCommands/${device}`;
  const mappingPath = "fingerprintMappings/121";
  await seed(`users/${admin}`, { role: string("ADMIN"), active: bool(true) });
  await seed(employeePath, {
    fullName: string("Fixture"), active: bool(true), pendingTemplateId: integer(121),
    fingerprintTemplateId: { nullValue: null }, fingerprintDeviceId: string(device)
  });
  await seed(mappingPath, { employeeId: string(employee), templateId: integer(121), enabled: bool(false) });
  await seed(commandPath, {
    requestId: string(`request-${suffix}`), type: string("ENROLL_FINGERPRINT"), deviceId: string(device),
    employeeId: string(employee), employeeName: string("Fixture"), templateId: integer(121),
    status: string("REQUESTED"), applied: bool(false)
  });
  const rollback = await begin(auth);
  const deviceStart = await begin(deviceAuth);
  const captured = await read([employeePath, commandPath, mappingPath], auth, rollback);
  assert.equal(captured.get(name(commandPath)).status.stringValue, "REQUESTED");
  await read([commandPath], deviceAuth, deviceStart);
  const results = await outcomes([
    { auth, transaction: rollback, writes: [commandPath, mappingPath, employeePath].map(path => ({ delete: name(path) })) },
    { auth: deviceAuth, transaction: deviceStart, writes: [update(commandPath, { status: string("PROCESSING") }, ["status"])] }
  ]);
  assertConflict(results);
  if (results[0].status === 200) {
    // Firmware only starts enrollment after PROCESSING was accepted; here that write was rejected.
    assert.equal(results[1].status, 409);
    for (const path of [employeePath, commandPath, mappingPath]) {
      assert.equal((await request(`/${path}`, "owner", undefined, "GET")).status, 404);
    }
  } else {
    if (results[1].status !== 200) {
      const retry = await begin(deviceAuth);
      await read([commandPath], deviceAuth, retry);
      await success(await commit(deviceAuth, retry, [update(commandPath, { status: string("PROCESSING") }, ["status"])]));
    }
    const retry = await begin(auth);
    const current = await read([employeePath, commandPath, mappingPath], auth, retry);
    assert.equal(current.get(name(commandPath)).status.stringValue, "PROCESSING");
    assert.ok(current.get(name(employeePath)));
    assert.equal(current.get(name(mappingPath)).enabled.booleanValue, false);
    // The repository's guard aborts compensation once the device accepted this command.
    await success(await request(":rollback", auth, { transaction: retry }));
    for (const path of [employeePath, commandPath, mappingPath]) {
      assert.equal((await request(`/${path}`, "owner", undefined, "GET")).status, 200);
    }
  }
});

test("department rename and weekly approval re-read changed profiles instead of trusting stale snapshots", { timeout: 60000 }, async () => {
  const suffix = `${Date.now()}-${process.pid}`;
  const admin = `profile-admin-${suffix}`;
  const auth = token(admin);
  const department = `profile-department-${suffix}`;
  const departmentPath = `departments/${department}`;
  const moved = `profile-moved-${suffix}`;
  const legacy = `profile-legacy-${suffix}`;
  const movedPath = `employees/${moved}`;
  const legacyPath = `employees/${legacy}`;
  const createdAt = { timestampValue: "2026-01-01T00:00:00Z" };
  await seed(`users/${admin}`, { role: string("ADMIN"), active: bool(true) });
  await seed(departmentPath, { name: string("Old department"), active: bool(true), createdAt, updatedAt: createdAt });
  await seed(movedPath, { fullName: string("Moved"), active: bool(true), departmentId: string(department), department: string("Old department") });
  await seed(legacyPath, { fullName: string("Legacy"), active: bool(true), department: string("Old department") });
  // These are the documents returned by the initial department/employee queries.
  const staleDepartment = (await success(await request(`/${departmentPath}`, auth, undefined, "GET"))).fields;
  const employeePathsFromInitialQuery = [movedPath, legacyPath];
  await seed(departmentPath, { ...staleDepartment, active: bool(false) });
  await seed(movedPath, { fullName: string("Moved"), active: bool(true), departmentId: string("OTHER"), department: string("Other department") });
  const rename = await begin(auth);
  const current = await read([departmentPath, ...employeePathsFromInitialQuery], auth, rename);
  assert.equal(current.get(name(departmentPath)).name.stringValue, staleDepartment.name.stringValue);
  const departmentWrite = update(departmentPath, { name: string("New department") }, ["name"]);
  departmentWrite.updateTransforms = [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }];
  const writes = [departmentWrite];
  for (const path of employeePathsFromInitialQuery) {
    const employee = current.get(name(path));
    const id = employee?.departmentId?.stringValue || "";
    if (employee && (id === department || (!id && employee.department.stringValue === staleDepartment.name.stringValue))) {
      writes.push(update(path, { departmentId: string(department), department: string("New department") }, ["departmentId", "department"]));
    }
  }
  await success(await commit(auth, rename, writes));
  const renamed = (await success(await request(`/${departmentPath}`, auth, undefined, "GET"))).fields;
  assert.equal(renamed.active.booleanValue, false);
  assert.deepEqual(renamed.createdAt, createdAt);
  assert.notEqual(renamed.updatedAt.timestampValue, createdAt.timestampValue);
  const transferred = (await success(await request(`/${movedPath}`, auth, undefined, "GET"))).fields;
  assert.equal(transferred.departmentId.stringValue, "OTHER");
  assert.equal(transferred.department.stringValue, "Other department");
  const retained = (await success(await request(`/${legacyPath}`, auth, undefined, "GET"))).fields;
  assert.equal(retained.departmentId.stringValue, department);
  assert.equal(retained.department.stringValue, "New department");
  // A second rename based on the old name must abort rather than undo the first rename.
  const staleRename = await begin(auth);
  const newestDepartment = (await read([departmentPath], auth, staleRename)).get(name(departmentPath));
  assert.notEqual(newestDepartment.name.stringValue, staleDepartment.name.stringValue);
  await success(await request(":rollback", auth, { transaction: staleRename }));

  const requestPath = `weeklyScheduleRequests/${moved}_2026-10-12`;
  const schedulePath = `workSchedules/${moved}_2026-10-12`;
  await seed(requestPath, { employeeId: string(moved), weekStart: string("2026-10-12"), status: string("PENDING") });
  assert.equal(transferred.active.booleanValue, true, "The review dialog initially saw an active employee");
  await seed(movedPath, { ...transferred, active: bool(false), terminationDate: string("2026-10-08") });
  const approval = await begin(auth);
  const approvalReads = await read([requestPath, movedPath, schedulePath], auth, approval);
  assert.equal(approvalReads.get(name(requestPath)).status.stringValue, "PENDING");
  assert.equal(approvalReads.get(name(movedPath)).active.booleanValue, false,
    "The approval transaction must use the current employee, not the active dialog snapshot");
  await success(await request(":rollback", auth, { transaction: approval }));
  assert.equal((await success(await request(`/${requestPath}`, auth, undefined, "GET"))).fields.status.stringValue, "PENDING");
  assert.equal((await request(`/${schedulePath}`, auth, undefined, "GET")).status, 404);
});
