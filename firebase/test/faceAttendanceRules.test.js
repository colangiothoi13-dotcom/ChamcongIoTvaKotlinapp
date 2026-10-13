// Local Firestore emulator only. No credentials or production project are used.
const test = require("node:test");
const assert = require("node:assert/strict");

const project = "demo-face-attendance-rules";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";
if (!/^(localhost|127\.0\.0\.1|\[::1\]):\d+$/.test(host)) throw new Error("Local emulator required");
const base = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
function token(uid, provider = "password") {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: `https://securetoken.google.com/${project}`, aud: project,
    sub: uid, user_id: uid, iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} }
  })}.`;
}
const admin = token("face-admin");
const employee = token("face-employee");
const otherEmployee = token("other-employee");
const device = token("face-device", "anonymous");
function field(value) {
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") return Number.isInteger(value)
    ? { integerValue: String(value) } : { doubleValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  return { stringValue: value };
}
async function request(path, method, auth, data) {
  return fetch(`${base}/${path}`, {
    method,
    headers: { ...(auth ? { Authorization: `Bearer ${auth}` } : {}), "Content-Type": "application/json" },
    ...(data ? { body: JSON.stringify({ fields: Object.fromEntries(
      Object.entries(data).map(([key, value]) => [key, field(value)])
    ) }) } : {})
  });
}
async function expectStatus(response, status) {
  assert.equal(response.status, status, await response.text());
}
async function seed(path, data) { await expectStatus(await request(path, "PATCH", "owner", data), 200); }
async function seedUsers() {
  await seed("users/face-admin", { role: "ADMIN", active: true });
  await seed("users/face-employee", { role: "EMPLOYEE", active: true, employeeId: "e1" });
  await seed("users/other-employee", { role: "EMPLOYEE", active: true, employeeId: "e2" });
  await seed("employees/e1", { fullName: "An", active: true });
  await seed("fingerprintMappings/7", { templateId: 7, employeeId: "e1", enabled: true });
}
function rawFingerprint() {
  return { employeeId: "e1", employeeName: "An", deviceId: "GATE-01", templateId: 7,
    confidence: 120, type: "SCAN", status: "PENDING", resolutionStatus: "PENDING",
    syncStatus: "PENDING_SYNC", timestamp: new Date("2026-10-12T01:00:00Z"), verified: true };
}

test("legacy fingerprint writes stay allowed and anonymous clients cannot forge face metadata", async () => {
  await seedUsers();
  await expectStatus(await request("attendance/legacy-fingerprint", "PATCH", device, rawFingerprint()), 200);
  await expectStatus(await request("attendance/forged-face", "PATCH", device,
    { ...rawFingerprint(), method: "FACE", faceSimilarity: 0.62, profileVersion: 1 }), 403);
  await expectStatus(await request("attendance/forged-gateway", "PATCH", device,
    { ...rawFingerprint(), method: "FINGERPRINT", sourceEventId: "event-1", stationId: "GATE-01" }), 403);
});

test("gateway face scans follow the existing attendance read boundary", async () => {
  await seedUsers();
  const scan = rawFingerprint();
  delete scan.templateId;
  delete scan.confidence;
  await seed("attendance/gateway-face", { ...scan, deviceId: "CAM-01", method: "FACE",
    sourceEventId: "camera-session-1", stationId: "GATE-01", capturedAt: scan.timestamp,
    faceSimilarity: 0.62, profileVersion: 1 });
  for (const auth of [admin, employee]) {
    await expectStatus(await request("attendance/gateway-face", "GET", auth), 200);
    await expectStatus(await request("attendance/gateway-face", "PATCH", auth, { method: "FINGERPRINT" }), 403);
  }
  for (const auth of [device, otherEmployee, null]) {
    await expectStatus(await request("attendance/gateway-face", "GET", auth), 403);
  }
});

test("face profiles expose metadata to admin only and cannot be changed by clients", async () => {
  await seedUsers();
  await seed("faceProfiles/e1", { employeeId: "e1", enabled: true, version: 1 });
  await expectStatus(await request("faceProfiles/e1", "GET", admin), 200);
  for (const auth of [admin, employee, device, null]) {
    if (auth !== admin) await expectStatus(await request("faceProfiles/e1", "GET", auth), 403);
    await expectStatus(await request("faceProfiles/new-profile", "PATCH", auth,
      { employeeId: "e2", enabled: true, version: 1 }), 403);
    await expectStatus(await request("faceProfiles/e1", "PATCH", auth, { enabled: false }), 403);
    await expectStatus(await request("faceProfiles/e1", "DELETE", auth), 403);
  }
});

test("gateway receipts, gates and nested event history are private even for client admins", async () => {
  await seedUsers();
  for (const collection of ["attendanceReceipts", "attendanceGates", "attendanceGates/gate/events"]) {
    await seed(`${collection}/private-state`, { employeeId: "e1", status: "ACCEPTED" });
    for (const auth of [admin, employee, device, null]) {
      await expectStatus(await request(`${collection}/private-state`, "GET", auth), 403);
      await expectStatus(await request(`${collection}/new-state`, "PATCH", auth, { employeeId: "e1" }), 403);
      await expectStatus(await request(`${collection}/private-state`, "PATCH", auth, { status: "DUPLICATE" }), 403);
      await expectStatus(await request(`${collection}/private-state`, "DELETE", auth), 403);
    }
  }
});
