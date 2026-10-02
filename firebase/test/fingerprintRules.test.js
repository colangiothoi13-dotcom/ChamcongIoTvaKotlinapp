// Run with the local Firestore emulator; uses a demo project only.
const test = require("node:test");
const assert = require("node:assert/strict");

const project = "demo-fingerprint-commit";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
const base = `http://${host}/v1/projects/${project}/databases/(default)`;
const documents = `${base}/documents`;
const name = path => `projects/${project}/databases/(default)/documents/${path}`;
const field = value => value === null ? { nullValue: null }
  : typeof value === "boolean" ? { booleanValue: value }
  : typeof value === "number" ? { integerValue: String(value) }
  : { stringValue: value };
const fields = value => Object.fromEntries(Object.entries(value).map(([key, item]) => [key, field(item)]));
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
const token = (uid, provider) => {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project,
    sub: uid, user_id: uid, iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.`;
};
const device = token("device-1", "anonymous");

async function request(path, method, auth, body) {
  return fetch(`${documents}${path}`, {
    method,
    headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
}
async function expectStatus(result, status) {
  if (result.status !== status) {
    assert.fail(`Expected HTTP ${status}, got ${result.status}: ${await result.text()}`);
  }
}
async function seed(path, data) {
  const result = await request(`/${path}`, "PATCH", "owner", { fields: fields(data) });
  await expectStatus(result, 200);
}
async function readDocument(path) {
  const result = await request(`/${path}`, "GET", "owner");
  await expectStatus(result, 200);
  return result.json();
}
async function read(path) { return (await readDocument(path)).fields; }
function completion(type, templateId, commandVersion) {
  const command = { update: { name: name("deviceCommands/GATE-01"),
    fields: { applied: field(true) } }, updateMask: { fieldPaths: ["applied"] },
    currentDocument: { updateTime: commandVersion } };
  const employee = { update: { name: name("employees/e1"), fields: {
    fingerprintTemplateId: field(type === "ENROLL_FINGERPRINT" ? templateId : null),
    pendingTemplateId: field(null)
  } }, updateMask: { fieldPaths: ["fingerprintTemplateId", "pendingTemplateId"] },
    currentDocument: { exists: true } };
  const mapping = type === "ENROLL_FINGERPRINT"
    ? { update: { name: name(`fingerprintMappings/${templateId}`), fields: { enabled: field(true) } },
      updateMask: { fieldPaths: ["enabled"] }, currentDocument: { exists: true } }
    : { delete: name(`fingerprintMappings/${templateId}`) };
  return { writes: [command, employee, mapping] };
}

test("Spark device can atomically finish its reserved enrollment and deletion", async () => {
  await seed("employees/e1", { fullName: "An", active: true, fingerprintDeviceId: "GATE-01",
    fingerprintTemplateId: null, pendingTemplateId: 7 });
  await seed("fingerprintMappings/7", { employeeId: "e1", employeeName: "An",
    templateId: 7, enabled: false });
  await seed("deviceCommands/GATE-01", { requestId: "req-1", type: "ENROLL_FINGERPRINT",
    deviceId: "GATE-01", employeeId: "e1", employeeName: "An", templateId: 7,
    status: "PROCESSING", applied: false });

  const status = await request("/deviceCommands/GATE-01?updateMask.fieldPaths=status", "PATCH", device,
    { fields: { status: field("COMPLETED") } });
  await expectStatus(status, 200);
  const enrollmentVersion = (await readDocument("deviceCommands/GATE-01")).updateTime;
  const mappingAlone = await request("/fingerprintMappings/7?updateMask.fieldPaths=enabled", "PATCH", device,
    { fields: { enabled: field(true) } });
  await expectStatus(mappingAlone, 403);
  const incomplete = await request(":commit", "POST", device,
    { writes: completion("ENROLL_FINGERPRINT", 7, enrollmentVersion).writes.slice(0, 2) });
  await expectStatus(incomplete, 403);
  const enrolled = await request(":commit", "POST", device, completion("ENROLL_FINGERPRINT", 7, enrollmentVersion));
  await expectStatus(enrolled, 200);
  assert.equal((await read("employees/e1")).fingerprintTemplateId.integerValue, "7");
  assert.equal((await read("fingerprintMappings/7")).enabled.booleanValue, true);
  assert.equal((await read("deviceCommands/GATE-01")).applied.booleanValue, true);

  await seed("fingerprintMappings/7", { employeeId: "e1", employeeName: "An",
    templateId: 7, enabled: false });
  await seed("employees/e1", { fullName: "An", active: true, fingerprintDeviceId: "GATE-01",
    fingerprintTemplateId: 7, pendingTemplateId: 7 });
  await seed("deviceCommands/GATE-01", { requestId: "req-2", type: "DELETE_FINGERPRINT",
    deviceId: "GATE-01", employeeId: "e1", employeeName: "An", templateId: 7,
    status: "COMPLETED", applied: false });
  const deletionVersion = (await readDocument("deviceCommands/GATE-01")).updateTime;
  const deleted = await request(":commit", "POST", device, completion("DELETE_FINGERPRINT", 7, deletionVersion));
  await expectStatus(deleted, 200);
  assert.equal((await read("employees/e1")).fingerprintTemplateId.nullValue, null);
  assert.equal((await read("deviceCommands/GATE-01")).applied.booleanValue, true);
  const missingMapping = await request("/fingerprintMappings/7", "GET", "owner");
  assert.equal(missingMapping.status, 404);

  await seed("employees/e1", { fullName: "An", active: true, fingerprintDeviceId: "GATE-01",
    fingerprintTemplateId: null, pendingTemplateId: 8 });
  await seed("fingerprintMappings/8", { employeeId: "e1", templateId: 9, enabled: false });
  await seed("deviceCommands/GATE-01", { requestId: "req-3", type: "ENROLL_FINGERPRINT",
    deviceId: "GATE-01", employeeId: "e1", templateId: 8,
    status: "COMPLETED", applied: false });
  const mismatchVersion = (await readDocument("deviceCommands/GATE-01")).updateTime;
  const mismatchedMapping = await request(":commit", "POST", device,
    completion("ENROLL_FINGERPRINT", 8, mismatchVersion));
  await expectStatus(mismatchedMapping, 403);
});
