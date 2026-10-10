// Local demo emulator only: explicit payroll recalculation, stale preview and exact immutable history.
const { before, test } = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-payroll-revision-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
assert.match(host, /^(localhost|127\.0\.0\.1|\[::1\]):\d+$/);
const documents = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
const suffix = `${Date.now()}-${process.pid}`;
const name = path => `projects/${project}/databases/(default)/documents/${path}`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function actor(label, provider = "password") {
  const uid = `${label}-${suffix}`;
  const now = Math.floor(Date.now() / 1000);
  return { uid, token: `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.` };
}
const admin = actor("admin");
const secondAdmin = actor("second-admin");
const employee = { ...actor("employee"), employeeId: `e1-${suffix}` };
const other = { ...actor("other"), employeeId: `e2-${suffix}` };
const device = actor("device", "anonymous");
const field = value => value === null ? { nullValue: null }
  : value instanceof Date ? { timestampValue: value.toISOString() }
  : typeof value === "boolean" ? { booleanValue: value }
  : typeof value === "number" ? Number.isInteger(value) ? { integerValue: String(value) }
    : { doubleValue: Number.isFinite(value) ? value : String(value) }
  : typeof value === "object" ? { mapValue: { fields: fields(value) } }
  : { stringValue: value };
const fields = object => Object.fromEntries(Object.entries(object).map(([key, value]) => [key, field(value)]));
const decode = raw => raw.nullValue !== undefined ? null
  : raw.integerValue !== undefined ? Number(raw.integerValue)
  : raw.doubleValue !== undefined ? Number(raw.doubleValue)
  : raw.booleanValue !== undefined ? raw.booleanValue
  : raw.timestampValue !== undefined ? new Date(raw.timestampValue)
  : raw.mapValue ? Object.fromEntries(Object.entries(raw.mapValue.fields).map(([key, value]) => [key, decode(value)]))
  : raw.stringValue;
async function request(path, method, caller, body) {
  return fetch(`${documents}${path}`, { method,
    headers: { Authorization: `Bearer ${caller.token || caller}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {}) });
}
async function status(response, expected) {
  assert.equal(response.status, expected, await response.text());
}
async function seed(path, value) {
  await status(await request(`/${path}`, "PATCH", "owner", { fields: fields(value) }), 200);
}
async function read(path, caller = admin) {
  const response = await request(`/${path}`, "GET", caller);
  assert.equal(response.status, 200, await response.clone().text());
  return Object.fromEntries(Object.entries((await response.json()).fields).map(([key, value]) => [key, decode(value)]));
}
const idFor = (month, employeeId = employee.employeeId) => `${employeeId}_${month}`;
function payroll(month, overrides = {}) {
  return { employeeId: employee.employeeId, employeeCode: "NV0001", employeeName: "An", month,
    baseSalary: 104000, hourlyRate: 26000, hoursWorked: 4, bonus: 0, deduction: 25000, ...overrides };
}
async function create(month, overrides = {}, caller = admin, id = idFor(month)) {
  return request(":commit", "POST", caller, { writes: [{
    update: { name: name(`payroll/${id}`), fields: fields(payroll(month, overrides)) },
    currentDocument: { exists: false }
  }] });
}
function revisions(id, previous, changes, caller = admin, options = {}) {
  const next = { ...previous, ...changes, revision: options.revision ?? (previous.revision || 0) + 1 };
  if (options.removeField) delete next[options.removeField];
  if (!Object.hasOwn(changes, "baseSalary")) next.baseSalary = Math.round(next.hourlyRate * next.hoursWorked);
  const archive = { payrollId: id, revision: next.revision, previous: options.forgePrevious || previous,
    next: options.forgeNext || next, actorId: options.actorId || caller.uid,
    reason: options.reason ?? "Bổ sung công và cập nhật đơn giá hiện tại" };
  return [{ update: { name: name(`payroll/${id}`), fields: fields(next) }, currentDocument: { exists: true } },
    ...(!options.omitHistory ? [{ update: { name: name(`payroll/${id}/revisions/${next.revision}`), fields: fields(archive) },
      currentDocument: { exists: false },
      updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] }] : []),
    { update: { name: name(`audit_logs/${id}_${next.revision}_${caller.uid}`), fields: fields({ actorId: caller.uid,
      actorName: caller.uid, action: "EMPLOYEE_UPDATE", targetType: "payroll", targetId: id, reason: "",
      details: `Recalculate ${previous.hoursWorked}h/${previous.hourlyRate} to ${next.hoursWorked}h/${next.hourlyRate}; ${archive.reason}` }) },
      currentDocument: { exists: false }, updateTransforms: [{ fieldPath: "createdAt", setToServerValue: "REQUEST_TIME" }] }];
}
async function recalculate(id, previous, changes = {}, caller = admin, options = {}) {
  return request(":commit", "POST", caller, { writes: revisions(id, previous, changes, caller, options) });
}
before(async () => {
  for (const who of [admin, secondAdmin]) await seed(`users/${who.uid}`, { role: "ADMIN", active: true });
  for (const who of [employee, other]) {
    await seed(`users/${who.uid}`, { role: "EMPLOYEE", active: true, employeeId: who.employeeId });
    await seed(`employees/${who.employeeId}`, { code: who === employee ? "NV0001" : "NV0002",
      fullName: who === employee ? "An" : "Other", active: true, baseSalary: 26000 });
  }
});

test("legacy zero-rate snapshot changes only through explicit atomic revision preserving raw old fields", async () => {
  const month = "2026-01";
  const id = idFor(month);
  const previous = payroll(month, { hourlyRate: 0, hoursWorked: 0, baseSalary: 0, legacyNote: "Keep original snapshot" });
  await seed(`payroll/${id}`, previous);
  await status(await recalculate(id, previous, { hourlyRate: 26000, hoursWorked: 4, legacyNote: "Changed" }), 403);
  await status(await recalculate(id, previous, { hourlyRate: 26000, hoursWorked: 4 }, admin, { removeField: "legacyNote" }), 403);
  await status(await recalculate(id, previous, { hourlyRate: 26000, hoursWorked: 4, anotherLegacyField: true }), 403);
  await status(await recalculate(id, previous, { hourlyRate: 26000, hoursWorked: 4 }), 200);
  const current = await read(`payroll/${id}`);
  assert.equal(current.revision, 1);
  assert.equal(current.baseSalary + current.bonus - current.deduction, 79000);
  assert.equal(current.deduction, 25000);
  assert.equal(current.legacyNote, previous.legacyNote);
  const history = await read(`payroll/${id}/revisions/1`);
  assert.deepEqual(history.previous, previous);
  assert(!Object.hasOwn(history.previous, "revision"));
  assert.deepEqual(history.next, current);
  assert.equal(history.actorId, admin.uid);
  assert(history.createdAt instanceof Date);
  assert.equal((await read(`audit_logs/${id}_1_${admin.uid}`)).targetId, id);
});

test("new saves accept initial zero revision only, keep duplicate saves blocked and archive repeated recalculation", async () => {
  const month = "2026-02";
  const id = idFor(month);
  await status(await create(month, { revision: 0 }), 200);
  const saved = await read(`payroll/${id}`);
  await status(await create(month, { hoursWorked: 1, baseSalary: 26000 }), 409);
  await status(await recalculate(id, saved, { hoursWorked: 4.5, bonus: 5000 }), 200);
  const first = await read(`payroll/${id}`);
  await status(await recalculate(id, first, { hoursWorked: 5.25, deduction: 10000 }), 200);
  const current = await read(`payroll/${id}`);
  assert.equal(current.revision, 2);
  assert.equal(current.baseSalary, 136500);
  assert.deepEqual((await read(`payroll/${id}/revisions/1`)).previous, saved);
  assert.deepEqual((await read(`payroll/${id}/revisions/2`)).previous, first);
  assert.deepEqual((await read(`payroll/${id}/revisions/2`)).next, current);
});

test("payroll updates reject missing or forged history, forged actor, nonincrementing revisions and identity changes", async () => {
  const month = "2026-03";
  const id = idFor(month);
  await status(await create(month), 200);
  const previous = await read(`payroll/${id}`);
  for (const [changes, options] of [
    [{ hoursWorked: 5 }, { omitHistory: true }], [{}, { forgePrevious: { ...previous, deduction: 0 } }],
    [{}, { forgeNext: { ...previous, revision: 1, baseSalary: 1 } }], [{}, { actorId: secondAdmin.uid }],
    [{}, { reason: " " }], [{}, { reason: "x".repeat(501) }], [{}, { revision: 0 }], [{}, { revision: 2 }],
    [{ employeeId: other.employeeId }, {}], [{ month: "2026-04" }, {}],
    [{ employeeName: "Forged" }, {}], [{ employeeCode: "NV9999" }, {}], [{ unexpected: true }, {}]
  ]) await status(await recalculate(id, previous, changes, admin, options), 403);
  assert.deepEqual(await read(`payroll/${id}`), previous);
  await status(await request(`/payroll/${id}/revisions/1`, "GET", admin), 404);
});

test("canonical pay math, finite hours, money bounds and current employee rate are enforced", async () => {
  const month = "2026-04";
  const id = idFor(month);
  await status(await create(month), 200);
  const previous = await read(`payroll/${id}`);
  for (const changes of [
    { hoursWorked: -1 }, { hoursWorked: 744.01 }, { hoursWorked: NaN }, { hoursWorked: Infinity },
    { hourlyRate: 0 }, { hourlyRate: 25000 }, { hourlyRate: -1 }, { hourlyRate: 1000000000001 },
    { baseSalary: 104001 }, { baseSalary: 104000.5 }, { bonus: -1 }, { deduction: -1 },
    { bonus: 1000000000001 }, { deduction: 1000000000001 }, { bonus: 0.5 }, { deduction: 0.5 }
  ]) await status(await recalculate(id, previous, changes), 403);
  await status(await recalculate(id, previous, { hoursWorked: 0.00025 }), 200);
  assert.equal((await read(`payroll/${id}`)).baseSalary, 7);
  // A rate changed since the preview is rejected, rather than silently switching to that rate.
  const current = await read(`payroll/${id}`);
  await seed(`employees/${employee.employeeId}`, { code: "NV0001", fullName: "An", active: true, baseSalary: 27000 });
  try { await status(await recalculate(id, current, { hoursWorked: 4 }), 403); }
  finally { await seed(`employees/${employee.employeeId}`, { code: "NV0001", fullName: "An", active: true, baseSalary: 26000 }); }
});

test("employee sees only their own payslip/history and cannot recalculate, erase or forge archived revisions", async () => {
  const month = "2026-05";
  const id = idFor(month);
  await status(await create(month), 200);
  const previous = await read(`payroll/${id}`);
  for (const caller of [employee, other, device]) {
    await status(await recalculate(id, previous, { hoursWorked: 5 }, caller), 403);
  }
  await status(await request(`/payroll/${id}`, "GET", employee), 200);
  for (const caller of [other, device]) await status(await request(`/payroll/${id}`, "GET", caller), 403);
  await status(await recalculate(id, previous, { hoursWorked: 5 }), 200);
  const historyPath = `/payroll/${id}/revisions/1`;
  for (const caller of [admin, employee]) {
    await status(await request(historyPath, "GET", caller), 200);
    await status(await request(historyPath, "PATCH", caller, { fields: fields({ reason: "Rewrite history" }) }), 403);
    await status(await request(historyPath, "DELETE", caller), 403);
    await status(await request(`/payroll/${id}`, "DELETE", caller), 403);
  }
  for (const caller of [other, device]) await status(await request(historyPath, "GET", caller), 403);
  const current = await read(`payroll/${id}`);
  const archiveOnly = revisions(id, current, { hoursWorked: 6 }).slice(1, 2);
  await status(await request(":commit", "POST", admin, { writes: archiveOnly }), 403);
});

test("two admins revising the same preview commit once and retain the unchanged historical snapshot", async () => {
  const month = "2026-06";
  const id = idFor(month);
  await status(await create(month), 200);
  const previous = await read(`payroll/${id}`);
  const responses = await Promise.all([
    recalculate(id, previous, { hoursWorked: 5 }, admin),
    recalculate(id, previous, { hoursWorked: 6 }, secondAdmin)
  ]);
  const statuses = responses.map(response => response.status).sort();
  assert.equal(statuses.filter(code => code === 200).length, 1, await Promise.all(responses.map(response => response.text())));
  assert(statuses.some(code => code === 403 || code === 409));
  const current = await read(`payroll/${id}`);
  assert.equal(current.revision, 1);
  const history = await read(`payroll/${id}/revisions/1`);
  assert.deepEqual(history.previous, previous);
  assert.deepEqual(history.next, current);
  const stale = await recalculate(id, previous, { hoursWorked: 7 });
  assert([403, 409].includes(stale.status), await stale.text());
  assert.deepEqual(await read(`payroll/${id}`), current);
});

test("new payroll creation validates canonical month, employee rate, initial revision and amounts", async () => {
  for (const [month, changes, caller, id] of [
    ["2026-07", {}, employee], ["2026-07", {}, device], ["2026-13", {}],
    ["2026-07", { revision: 1 }], ["2026-07", { hourlyRate: 0, baseSalary: 0 }],
    ["2026-07", { baseSalary: 1 }], ["2026-07", { bonus: -1 }],
    ["2026-07", {}, admin, "wrong-id"], ["2026-07", { extraField: "unrequested" }]
  ]) await status(await create(month, changes, caller || admin, id || idFor(month)), 403);
});

test("legacy employee without rate uses zero default and never accepts a nonzero fabricated preview", async () => {
  const month = "2026-08";
  const id = idFor(month);
  await seed(`employees/${employee.employeeId}`, { code: "NV0001", fullName: "An", active: true });
  try {
    await status(await create(month, { hourlyRate: 0, baseSalary: 0 }), 200);
    const previous = await read(`payroll/${id}`);
    await status(await recalculate(id, previous, { hoursWorked: 5, hourlyRate: 1 }), 403);
    await status(await recalculate(id, previous, { hoursWorked: 5 }), 200);
    assert.equal((await read(`payroll/${id}`)).baseSalary, 0);
  } finally { await seed(`employees/${employee.employeeId}`, { code: "NV0001", fullName: "An", active: true, baseSalary: 26000 }); }
});
