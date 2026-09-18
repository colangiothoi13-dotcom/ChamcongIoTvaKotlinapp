// Run separately with the local Firestore emulator; never contacts a configured Firebase project.
const test = require("node:test");
const assert = require("node:assert/strict");
const project = "demo-attendance-final-fix";
const host = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085";
const base = `http://${host}/v1/projects/${project}/databases/(default)/documents`;
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

test("schedule writes reject supplementary shifts while legacy schedules remain readable", async t => {
  async function expectStatus(response, status) {
    assert.equal(response.status, status, await response.text());
  }
  await expectStatus(await write("users/rules-admin", {
    role: { stringValue: "ADMIN" }, active: { booleanValue: true }
  }, "owner"), 200);
  const prefix = `schedule-${Date.now()}`;
  const mainId = `${prefix}-main`;
  const supplementaryId = `${prefix}-supplementary`;
  const virtualId = "SUPPLEMENTARY_1730_2030";
  const supplementaryFields = { ...fields, category: { stringValue: "SUPPLEMENTARY" } };
  await expectStatus(await write(`shifts/${mainId}`, fields), 200);
  await expectStatus(await write(`shifts/${supplementaryId}`, supplementaryFields), 200);
  // A caller cannot disguise the reserved virtual ID as a main shift.
  await expectStatus(await write(`shifts/${virtualId}`, fields, "owner"), 200);
  const schedule = shiftId => ({
    employeeId: { stringValue: "e1" }, shiftId: { stringValue: shiftId },
    date: { stringValue: "2026-09-21" }, overtimeHours: { integerValue: "0" },
    source: { stringValue: "EMPLOYEE" }
  });

  for (const [name, shiftId, expected] of [
    ["main", mainId, 200], ["supplementary", supplementaryId, 403],
    ["virtual", virtualId, 403], ["missing-shift", `${prefix}-missing`, 403]
  ]) {
    await t.test(`${name} create and update`, async () => {
      await expectStatus(await write(`workSchedules/${prefix}-${name}`, schedule(shiftId)), expected);
      const target = `workSchedules/${prefix}-${name}-update`;
      await expectStatus(await write(target, schedule(mainId)), 200);
      await expectStatus(await write(target, schedule(shiftId)), expected);
    });
  }

  async function commit(writes) {
    return fetch(`${base}:commit`, {
      method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ writes })
    });
  }
  const update = (path, data) => ({ update: {
    name: `projects/${project}/databases/(default)/documents/${path}`, fields: data
  } });
  for (const [category, expected] of [["MORNING", 200], ["EVENING", 200], ["SUPPLEMENTARY", 403]]) {
    await t.test(`batch creates ${category} template and schedule`, async () => {
      const id = `${prefix}-batch-${category}`;
      await expectStatus(await commit([
        update(`shifts/${id}`, { ...fields, category: { stringValue: category } }),
        update(`workSchedules/${id}`, schedule(id))
      ]), expected);
    });
  }

  await t.test("batch cannot change a main shift to supplementary while assigning it", async () => {
    await expectStatus(await commit([
      update(`shifts/${mainId}`, supplementaryFields),
      update(`workSchedules/${prefix}-changed-category`, schedule(mainId))
    ]), 403);
  });

  await t.test("legacy supplementary schedule is readable by admin and its employee", async () => {
    const path = `workSchedules/${prefix}-legacy`;
    await expectStatus(await write(path, schedule(supplementaryId), "owner"), 200);
    await expectStatus(await fetch(`${base}/${path}`, { headers: { Authorization: `Bearer ${token}` } }), 200);
    const employeeToken = `${encode({ alg: "none", typ: "JWT" })}.${encode({
      iss: `https://securetoken.google.com/${project}`, aud: project, sub: "rules-employee", user_id: "rules-employee",
      iat: now, exp: now + 3600, auth_time: now, firebase: { sign_in_provider: "password", identities: {} }
    })}.`;
    await expectStatus(await write("users/rules-employee", {
      role: { stringValue: "EMPLOYEE" }, active: { booleanValue: true }, employeeId: { stringValue: "e1" }
    }, "owner"), 200);
    await expectStatus(await fetch(`${base}/${path}`, { headers: { Authorization: `Bearer ${employeeToken}` } }), 200);
    await expectStatus(await write(path, schedule(supplementaryId)), 403);
  });
});
