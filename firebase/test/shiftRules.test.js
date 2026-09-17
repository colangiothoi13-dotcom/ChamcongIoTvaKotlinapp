// Run separately with the local Firestore emulator; never contacts a configured Firebase project.
const test = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-attendance-final-fix";
const base = `http://127.0.0.1:8085/v1/projects/${project}/databases/(default)/documents`;
const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
const now = Math.floor(Date.now() / 1000);
const token = `${encode({ alg: "none", typ: "JWT" })}.${encode({
  iss: `https://securetoken.google.com/${project}`, aud: project, sub: "rules-admin", user_id: "rules-admin",
  iat: now, exp: now + 3600, auth_time: now, firebase: { sign_in_provider: "password", identities: {} }
})}.`;
const fields = {
  name: { stringValue: "Test shift" }, category: { stringValue: "MORNING" },
  startTime: { stringValue: "08:00" }, endTime: { stringValue: "17:00" },
  allowEarlyMinutes: { integerValue: "15" }, lateGraceMinutes: { integerValue: "5" },
  earlyLeaveAllowedMinutes: { integerValue: "0" }, countsOvertime: { booleanValue: true },
  effectiveFrom: { stringValue: "2026-09-17" }, active: { booleanValue: true }
};
async function write(path, data, auth = token) {
  return fetch(`${base}/${path}`, { method: "PATCH", headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" }, body: JSON.stringify({ fields: data }) });
}

test("optional checkout grace validates admin creates and updates", async t => {
  const seed = await write("users/rules-admin", { role: { stringValue: "ADMIN" }, active: { booleanValue: true } }, "owner");
  assert.equal(seed.status, 200, await seed.text());
  for (const [name, grace, expected] of [
    ["omitted", undefined, 200], ["zero", { integerValue: "0" }, 200], ["positive", { integerValue: "60" }, 200],
    ["negative", { integerValue: "-1" }, 403], ["fraction", { doubleValue: 1.5 }, 403],
    ["string", { stringValue: "60" }, 403], ["null", { nullValue: null }, 403]
  ]) {
    await t.test(name, async () => {
      const data = grace === undefined ? fields : { ...fields, missingCheckOutGraceMinutes: grace };
      const path = `shifts/${name}-${Date.now()}`;
      const created = await write(path, data);
      assert.equal(created.status, expected, `create ${name}: ${await created.text()}`);
      const initial = await write(`${path}-update`, fields);
      assert.equal(initial.status, 200, await initial.text());
      const updated = await write(`${path}-update`, data);
      assert.equal(updated.status, expected, `update ${name}: ${await updated.text()}`);
    });
  }
});
