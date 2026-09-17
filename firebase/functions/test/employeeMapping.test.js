const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

test("recordAttendance uses snapshot identity for Android employee data with blank id", async () => {
  const created = [];
  const transaction = {
    async get(ref) {
      if (ref === "attendance/scan-1") return { exists: false };
      if (ref === "fingerprintMappings/7") return { exists: true, data: () => ({ enabled: true, employeeId: "employee-1" }) };
      assert.equal(ref, "employees/employee-1");
      return { exists: true, id: "employee-1", data: () => ({ id: "", code: "NV0001", fullName: "An", department: "IT", active: true, fingerprintTemplateId: 7 }) };
    },
    create(ref, data) { created.push({ ref, data }); }
  };
  const db = { collection: name => ({ doc: id => `${name}/${id}` }), runTransaction: callback => callback(transaction) };
  const firestore = Object.assign(() => db, { Timestamp: { now: () => new Date() }, FieldValue: { serverTimestamp: () => "server-time" } });
  const exports = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../index.js"), "utf8"), {
    exports, Buffer, console,
    require(name) {
      if (name === "firebase-functions/v2/https") return { onRequest: (_, handler) => handler };
      if (name === "firebase-functions/v2/firestore") return { onDocumentCreated: (_, handler) => handler, onDocumentUpdated: (_, handler) => handler };
      if (name === "firebase-functions/params") return { defineSecret: () => ({ value: () => "local-test-key" }) };
      if (name === "firebase-admin") return { initializeApp() {}, firestore };
      if (name.startsWith("./")) return require(`../${name.slice(2)}`);
      return require(name);
    }
  });
  let status = 200;
  let body;
  const response = { status(value) { status = value; return this; }, json(value) { body = value; return this; } };
  await exports.recordAttendance({ method: "POST", get: () => "local-test-key", body: { deviceId: "GATE-01", templateId: 7, eventId: "scan-1" } }, response);
  assert.equal(status, 200, JSON.stringify(body));
  assert.equal(body.ok, true);
  assert.equal(created.length, 1);
  assert.equal(created[0].data.employeeId, "employee-1");
  assert.equal(created[0].data.type, "SCAN");
  assert.equal(created[0].data.resolutionStatus, "PENDING");
});
