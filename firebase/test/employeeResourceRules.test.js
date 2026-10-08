// Run only against the local Firestore emulator, never a live Firebase project.
const { before, test } = require("node:test");
const assert = require("node:assert/strict");

const project = "demo-employee-resource-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
assert.match(host, /^(localhost|127\.0\.0\.1|\[::1\]):\d+$/);
const database = `http://${host}/v1/projects/${project}/databases/(default)`;
const documents = `${database}/documents`;
const name = path => `projects/${project}/databases/(default)/documents/${path}`;
const suffix = `${Date.now()}-${process.pid}`;
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
const employee = { ...actor("employee"), employeeId: `e1-${suffix}` };
const other = { ...actor("other"), employeeId: `e2-${suffix}` };
const inactive = { ...actor("inactive"), employeeId: `e3-${suffix}` };
const device = actor("device", "anonymous");
const department = `d1-${suffix}`;
const otherDepartment = `d2-${suffix}`;
const field = value => value === null ? { nullValue: null }
  : value instanceof Date ? { timestampValue: value.toISOString() }
  : typeof value === "boolean" ? { booleanValue: value } : { stringValue: value };
const fields = value => Object.fromEntries(Object.entries(value).map(([key, item]) => [key, field(item)]));

async function request(path, method, auth, body) {
  return fetch(`${documents}${path}`, {
    method, headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
}
async function expectStatus(response, status) {
  assert.equal(response.status, status, await response.text());
}
async function seed(path, data) {
  await expectStatus(await request(`/${path}`, "PATCH", "owner", { fields: fields(data) }), 200);
}
function resource(overrides = {}) {
  return { type: "MEETING", title: "Họp kế hoạch", body: "Nội dung cuộc họp", audience: "ALL",
    eventDate: "2026-10-12", startTime: "09:00", endTime: "10:00", location: "Phòng họp",
    url: "", createdBy: admin.uid, ...overrides };
}
async function create(id, data = resource(), caller = admin) {
  return request(":commit", "POST", caller.token, { writes: [{
    update: { name: name(`employeeResources/${id}`), fields: fields(data) },
    currentDocument: { exists: false },
    updateTransforms: ["createdAt", "updatedAt"].map(fieldPath => ({ fieldPath, setToServerValue: "REQUEST_TIME" }))
  }] });
}
async function update(id, changes, caller = admin) {
  return request(":commit", "POST", caller.token, { writes: [{
    update: { name: name(`employeeResources/${id}`), fields: fields(changes) },
    updateMask: { fieldPaths: Object.keys(changes) }, currentDocument: { exists: true },
    updateTransforms: [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }]
  }] });
}
async function read(id, caller = employee) {
  return request(`/employeeResources/${id}`, "GET", caller.token);
}
async function query(caller, audiences) {
  const query = {
    from: [{ collectionId: "employeeResources" }], orderBy: [{ field: { fieldPath: "updatedAt" }, direction: "DESCENDING" }], limit: 200
  };
  if (audiences) query.where = { fieldFilter: {
    field: { fieldPath: "audience" }, op: "IN", value: { arrayValue: { values: audiences.map(field) } }
  } };
  return request(":runQuery", "POST", caller.token, { structuredQuery: query });
}

before(async () => {
  await seed(`users/${admin.uid}`, { role: "ADMIN", active: true });
  for (const who of [employee, other, inactive]) {
    await seed(`users/${who.uid}`, { role: "EMPLOYEE", active: true, employeeId: who.employeeId });
    await seed(`employees/${who.employeeId}`, {
      fullName: who.uid, active: who !== inactive, departmentId: who === other ? otherDepartment : department
    });
  }
  await seed(`departments/${department}`, { name: "Kỹ thuật", active: true });
  await seed(`departments/${otherDepartment}`, { name: "Kinh doanh", active: true });
});

test("admin publishes and edits content, preserving original author and creation time", async () => {
  const id = `admin-crud-${suffix}`;
  await expectStatus(await create(id), 200);
  await expectStatus(await update(id, { title: "Lịch họp đã đổi", startTime: "10:00", endTime: "11:00" }), 200);
  await expectStatus(await update(id, { createdBy: employee.uid }), 403);
  await expectStatus(await update(id, { createdAt: new Date("2026-10-08T00:00:00Z") }), 403);
  await expectStatus(await request(`/employeeResources/${id}`, "DELETE", admin.token), 200);
});

test("employees read only all-staff, their own department and their individual content", async () => {
  const fixtures = [
    ["all", resource()],
    ["department", resource({ audience: `DEPARTMENT:${department}` })],
    ["other-department", resource({ audience: `DEPARTMENT:${otherDepartment}` })],
    ["own-reward", resource({ type: "REWARD", audience: `EMPLOYEE:${employee.employeeId}` })],
    ["other-reward", resource({ type: "REWARD", audience: `EMPLOYEE:${other.employeeId}` })]
  ];
  for (const [label, data] of fixtures) await expectStatus(await create(`${label}-${suffix}`, data), 200);
  for (const label of ["all", "department", "own-reward"]) await expectStatus(await read(`${label}-${suffix}`), 200);
  for (const label of ["other-department", "other-reward"]) await expectStatus(await read(`${label}-${suffix}`), 403);
  await expectStatus(await read(`all-${suffix}`, inactive), 403);
  await expectStatus(await read(`all-${suffix}`, device), 403);
  const scoped = await query(employee, ["ALL", `EMPLOYEE:${employee.employeeId}`, `DEPARTMENT:${department}`]);
  assert.equal(scoped.status, 200, await scoped.clone().text());
  const rows = (await scoped.json()).filter(row => row.document).map(row => row.document.name);
  assert(rows.some(row => row.endsWith(`/own-reward-${suffix}`)));
  assert(!rows.some(row => row.endsWith(`/other-reward-${suffix}`) || row.endsWith(`/other-department-${suffix}`)));
  await expectStatus(await query(employee), 403);
  await expectStatus(await query(employee, ["ALL", `DEPARTMENT:${otherDepartment}`]), 403);
});

test("changing an employee department revokes the former audience and permits a rebuilt query", async () => {
  await seed(`employees/${employee.employeeId}`, { fullName: employee.uid, active: true, departmentId: otherDepartment });
  await expectStatus(await read(`department-${suffix}`), 403);
  await expectStatus(await read(`other-department-${suffix}`), 200);
  await expectStatus(await query(employee, ["ALL", `EMPLOYEE:${employee.employeeId}`, `DEPARTMENT:${department}`]), 403);
  await expectStatus(await query(employee, ["ALL", `EMPLOYEE:${employee.employeeId}`, `DEPARTMENT:${otherDepartment}`]), 200);
  await seed(`employees/${employee.employeeId}`, { fullName: employee.uid, active: true, departmentId: department });
});

test("employees cannot create, edit, retarget or delete resources", async () => {
  await expectStatus(await create(`employee-write-${suffix}`, resource({ createdBy: employee.uid }), employee), 403);
  await expectStatus(await update(`all-${suffix}`, { title: "Thay tiêu đề" }, employee), 403);
  await expectStatus(await update(`own-reward-${suffix}`, { audience: "ALL" }, employee), 403);
  await expectStatus(await request(`/employeeResources/all-${suffix}`, "DELETE", employee.token), 403);
});

test("admin rejects invalid meeting dates, time ranges, targets and document URLs", async () => {
  const invalid = [
    { eventDate: "2026-02-29" }, { eventDate: "2026-04-31" }, { eventDate: "2026-13-01" },
    { startTime: "9:00" }, { endTime: "09:00" }, { startTime: "22:00", endTime: "01:00" },
    { location: " " }, { audience: "DEPARTMENT:missing" }, { audience: "EMPLOYEE:missing" },
    { type: "REWARD", audience: "ALL" }, { type: "REWARD", audience: `DEPARTMENT:${department}` },
    { type: "DOCUMENT", url: "" }, { type: "DOCUMENT", url: "http://example.com/file" },
    { type: "DOCUMENT", url: "javascript:alert(1)" }, { type: "DOCUMENT", url: "https://user:secret@example.com/file" },
    { title: " " }, { body: " " }, { createdBy: employee.uid }
  ];
  for (let index = 0; index < invalid.length; index++) {
    await expectStatus(await create(`invalid-${index}-${suffix}`, resource(invalid[index])), 403);
  }
  await expectStatus(await create(`leap-${suffix}`, resource({ eventDate: "2028-02-29" })), 200);
  await expectStatus(await create(`document-${suffix}`, resource({ type: "DOCUMENT", eventDate: "", startTime: "", endTime: "", location: "", url: "https://example.com/policy.pdf" })), 200);
});
