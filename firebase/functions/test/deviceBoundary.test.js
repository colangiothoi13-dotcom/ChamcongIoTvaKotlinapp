const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

function loadFunctions(writes) {
  let db;
  const firestore = Object.assign(() => db, {
    FieldValue: { serverTimestamp: () => "server-time" },
    Timestamp: { now: () => new Date() }
  });
  db = {
    collection(name) {
      return {
        doc(id) {
          return {
            async set(data, options) { writes.push({ name, id, data, options }); }
          };
        }
      };
    }
  };
  const exported = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../index.js"), "utf8"), {
    exports: exported,
    Buffer,
    console,
    require(name) {
      if (name === "firebase-functions/v2/https") return { onRequest: (_, handler) => handler };
      if (name === "firebase-functions/v2/firestore") {
        return {
          onDocumentCreated: (_, handler) => handler,
          onDocumentUpdated: (_, handler) => handler,
          onDocumentWritten: (_, handler) => handler
        };
      }
      if (name === "firebase-functions/params") return { defineSecret: () => ({ value: () => "local-test-key" }) };
      if (name === "firebase-admin") return { initializeApp() {}, firestore };
      if (name.startsWith("./")) return require(`../${name.slice(2)}`);
      return require(name);
    }
  });
  return exported;
}

function responseRecorder() {
  return {
    statusCode: 200,
    body: null,
    status(value) { this.statusCode = value; return this; },
    json(value) { this.body = value; return this; }
  };
}

test("device snapshot goes through the keyed Function boundary", async () => {
  const writes = [];
  const exported = loadFunctions(writes);
  const response = responseRecorder();
  await exported.updateDeviceSnapshot({
    method: "POST",
    get: header => header === "x-device-key" ? "local-test-key" : "GATE-01",
    body: {
      deviceId: "GATE-01",
      status: "ONLINE",
      firmwareVersion: "functions-v1-offline",
      fingerprintCount: 2,
      capacity: 127,
      pendingAttendanceCount: 0,
      capabilities: ["fingerprint", "heartbeat"],
      wifiStatus: "ONLINE",
      firebaseSyncStatus: "ONLINE",
      sensorStatus: "OK",
      failedScanCount: 0,
      lastError: ""
    }
  }, response);

  assert.equal(response.statusCode, 200, JSON.stringify(response.body));
  assert.equal(writes.length, 1);
  assert.equal(writes[0].name, "devices");
  assert.equal(writes[0].id, "GATE-01");
  assert.equal(writes[0].options.merge, true);
  assert.equal(JSON.stringify(writes[0].data), JSON.stringify({
    deviceId: "GATE-01",
    status: "ONLINE",
    lastHeartbeat: "server-time",
    firmwareVersion: "functions-v1-offline",
    fingerprintCount: 2,
    capacity: 127,
    pendingAttendanceCount: 0,
    capabilities: ["fingerprint", "heartbeat"],
    wifiStatus: "ONLINE",
    firebaseSyncStatus: "ONLINE",
    sensorStatus: "OK",
    failedScanCount: 0,
    lastError: ""
  }));
});

test("Spark firmware uses Anonymous Auth and Rules-bound Firestore REST", () => {
  const firmware = fs.readFileSync(require.resolve("../../../firmware/esp8266_fingerprint/esp8266_fingerprint.ino"), "utf8");
  const rules = fs.readFileSync(require.resolve("../../firestore.rules"), "utf8");
  assert.match(firmware, /identitytoolkit\.googleapis\.com\/v1\/accounts:signUp\?key=/);
  assert.match(firmware, /FIRESTORE_BASE_URL/);
  assert.match(firmware, /\/attendance\?documentId=/);
  assert.match(firmware, /\/devices\//);
  assert.match(firmware, /\/fingerprintMappings\//);
  assert.match(firmware, /\/deviceCommands\//);
  assert.doesNotMatch(firmware, /DEVICE_FUNCTIONS_BASE_URL|x-device-key|\/recordAttendance|\/updateDeviceSnapshot/);
  assert.match(rules, /function isDevice\(\)/);
  assert.match(rules, /match \/attendance\/{id} \{[\s\S]*?allow create: if isDevice\(\)/);
  assert.match(rules, /match \/devices\/{id} \{[\s\S]*?allow read: if isAdmin\(\) \|\| \(isDevice\(\)/);
  assert.match(rules, /match \/fingerprintMappings\/{id} \{[\s\S]*?allow read: if isAdmin\(\) \|\| isDevice\(\);/);
});
