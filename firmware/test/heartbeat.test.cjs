const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '..', 'esp8266_fingerprint', '40_attendance_service.ino'), 'utf8');

function functionSource(name) {
  const start = source.search(new RegExp(`\\b(?:bool|void) ${name}\\([^)]*\\)\\s*\\{`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

// Execute production branches; replace only C++ types and Arduino APIs with
// fake JSON/HTTP peripherals. No real device or network service is contacted.
function adapt(code) {
  return code
    .replace(/\bEnrollmentStage::/g, 'EnrollmentStage.')
    .replace(/^(?:bool|void) (\w+)\(([^)]*)\)\s*\{/, 'function $1($2) {')
    .replace(/const char\* (\w+)\[\]\s*=\s*\{([^}]+)\};/g, 'const $1 = [$2];')
    .replace(/for \(const char\* (\w+) : (\w+)\)/g, 'for (const $1 of $2)')
    .replace(/DynamicJsonDocument (\w+)\([^;]+\);/g, 'const $1 = jsonDocument();')
    .replace(/BearSSL::WiFiClientSecure (\w+);/g, 'const $1 = new BearSSL.WiFiClientSecure();')
    .replace(/HTTPClient (\w+);/g, 'const $1 = new HTTPClient();')
    .replace(/reinterpret_cast<const uint8_t\*>\((\w+)\.c_str\(\)\)/g, '$1')
    .replace(/\bconst\s+(?:bool|int|size_t|uint32_t|char\s*\*)\s+/g, 'const ')
    .replace(/\bString (\w+);/g, "let $1 = '';")
    .replace(/\b(?:bool|int|uint8_t|size_t|String|JsonObject|JsonArray)\s+(\w+)(?=\s*[=;])/g, 'let $1')
    .replace(/\b(\w+)\.reserve\(([^;\n]*)\)/g, 'reserveString($1, $2)')
    .replace(/serializeJson\((\w+), (\w+)\)/g, 'serializeJson($1, value => { $2 = value; })')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '');
}

function jsonDocument(overflowed) {
  const node = (parent, key) => new Proxy({}, {
    get(_, name) {
      if (name === 'toJSON') return () => parent[key];
      if (name === 'overflowed') return overflowed;
      if (name === 'createNestedObject') return child => {
        if (child === undefined) {
          assert.ok(Array.isArray(parent[key]));
          const index = parent[key].push({}) - 1;
          return node(parent[key], index);
        }
        parent[key] ||= {}; parent[key][child] = {}; return node(parent[key], child);
      };
      if (name === 'createNestedArray') return child => {
        parent[key] ||= {}; parent[key][child] = []; return node(parent[key], child);
      };
      if (name === 'add') return value => parent[key].push(value);
      parent[key] ||= {};
      return node(parent[key], name);
    },
    set(_, name, value) { parent[key] ||= {}; parent[key][name] = value; return true; },
  });
  return node({ root: {} }, 'root');
}

function fixture() {
  const s = {
    DEVICE_ID: 'GATE-01', FIRMWARE_VERSION: 'heartbeat-test',
    FIRESTORE_URL: 'https://firestore.test/v1/projects/test/databases/(default)/documents',
    ATTENDANCE_OUTBOX_MAX_BYTES: 12288, FINGERPRINT_OK: 0, WL_CONNECTED: 1,
    queueBytes: 1680, pendingCount: 3, outboxStatus: 'PENDING', online: true,
    sensorReady: true, sensorStatus: 'READY', doorStatus: 'CLOSED', doorBusy: false,
    lastError: '', capabilitiesNeedSync: true, firebaseSyncStatus: 'PENDING',
    firebaseIdToken: 'test-token', tokenCreatedAt: 123, signedIn: true,
    httpsAllowed: true, preparedAllowed: true, overflow: false, reserveAllowed: true,
    serializedBytes: null, beginAllowed: true, httpCode: 200,
    requests: [], authCalls: 0, guards: 0, results: [], deferrals: [], ended: 0, stopped: 0,
    constructed: 0, begins: 0, headers: [],
    pendingCommandExecution: false, pendingCommandResult: false, pendingCommandType: '', foregroundAttendanceHandled: true,
    EnrollmentStage: { IDLE: 'IDLE', STORE_MODEL: 'STORE_MODEL' }, enrollmentStage: 'IDLE',
    heartbeatWaitingForAttendanceAttempt: false, now: 31000, lastHeartbeat: 1000,
    HEARTBEAT_INTERVAL_MS: 30000, cooldown: false,
    Serial: { printf() {}, printf_P() {}, println() {} }, String, F: value => value, PSTR: value => value,
    strcmp: (a, b) => a === b ? 0 : 1,
    WiFi: { status: () => s.online ? 1 : 0 },
    doorNeedsResponsiveLoop: () => s.doorBusy,
    attendanceOutboxBytes: () => s.queueBytes,
    attendancePendingCount: () => s.pendingCount,
    attendanceOutboxStatus: () => s.outboxStatus,
    firebaseSignIn() { s.authCalls++; if (s.signedIn && !s.firebaseIdToken) s.firebaseIdToken = 'refreshed'; return s.signedIn; },
    templateReads: 0, finger: { templateCount: 21, getTemplateCount() { s.templateReads++; return 0; } },
    recentFailedScanCount: () => 2,
    setSensorError(message) { s.sensorError = message; },
    setLatestError(message) { s.error = message; },
    guardPhases: [],
    canStartHttpsRequest(operation, prepared = false) {
      s.guards++; s.guardPhases.push({ operation, prepared, headers: [...s.headers] });
      return s.httpsAllowed && (!prepared || s.preparedAllowed) && !s.cooldown && s.online && !s.doorBusy;
    },
    recordHttpsResult(operation, code) { s.results.push([operation, code]); },
    deferHttpsRequests(operation) { s.deferrals.push(operation); },
    // A missing or drifting device clock must never supply presence time.
    hasValidClock() { return false; },
    utcTimestamp() { throw new Error('Heartbeat must use server request time'); },
    jsonDocument: () => jsonDocument(() => s.overflow),
    measureJson: doc => JSON.stringify(doc).length,
    reserveString: () => s.reserveAllowed,
    serializeJson(doc, set) {
      const full = JSON.stringify(doc);
      const bytes = s.serializedBytes === null ? full.length : s.serializedBytes;
      set(full.slice(0, bytes)); return bytes;
    },
    millis: () => s.now >>> 0,
    elapsedAtLeast: (now, started, duration) => ((now - started) >>> 0) >= duration,
    httpsRetryCooldownActive: () => s.cooldown,
    BearSSL: { WiFiClientSecure: class {
      constructor() { s.constructed++; }
      setInsecure() {}
      stop() { s.stopped++; }
    } },
    HTTPClient: class {
      begin(client, url) { s.begins++; this.url = url; return s.beginAllowed; }
      setTimeout(timeout) { this.timeout = timeout; }
      addHeader(name) { s.headers.push(name); }
      sendRequest(method, body, length) {
        assert.equal(body.length, length);
        s.requests.push({ method, url: this.url, timeout: this.timeout, body: JSON.parse(body) });
        return s.httpCode;
      }
      end() { s.ended++; }
    },
  };
  const context = vm.createContext(s);
  s.run = code => vm.runInContext(code, context);
  s.run(adapt(functionSource('publishDeviceSnapshot')));
  s.run(adapt(functionSource('maybePublishDeviceSnapshot')));
  return s;
}

test('pending attendance and missing NTP still report live presence using one server-time commit', () => {
  const s = fixture();
  assert.equal(s.run('publishDeviceSnapshot()'), true);
  assert.equal(s.requests.length, 1);
  const request = s.requests[0];
  assert.equal(request.method, 'POST'); assert.equal(request.url, `${s.FIRESTORE_URL}:commit`);
  assert.equal(request.timeout, 5000); assert.equal(request.body.writes.length, 1);
  const write = request.body.writes[0];
  assert.equal(write.update.name, 'projects/test/databases/(default)/documents/devices/GATE-01');
  assert.deepEqual(write.updateTransforms, [{ fieldPath: 'lastHeartbeat', setToServerValue: 'REQUEST_TIME' }]);
  assert.equal(write.update.fields.lastHeartbeat, undefined);
  assert.equal(write.update.fields.status.stringValue, 'ONLINE');
  assert.equal(write.update.fields.firebaseSyncStatus.stringValue, 'PENDING');
  assert.equal(write.update.fields.pendingAttendanceCount.integerValue, 3);
  assert.equal(s.queueBytes, 1680); assert.equal(s.pendingCount, 3);
  assert.equal(s.capabilitiesNeedSync, false);
  assert.equal(write.updateMask.fieldPaths.includes('name'), false);
  assert.equal(write.updateMask.fieldPaths.includes('location'), false);
  assert.equal(s.ended, 1); assert.equal(s.stopped, 1);
});

test('a failed heartbeat retains capabilities for retry and 401 refreshes only the expired token', () => {
  const s = fixture(); s.httpCode = 401;
  assert.equal(s.run('publishDeviceSnapshot()'), false);
  assert.equal(s.firebaseIdToken, ''); assert.equal(s.tokenCreatedAt, 0);
  assert.equal(s.capabilitiesNeedSync, true); assert.equal(s.queueBytes, 1680);
  s.httpCode = 200;
  assert.equal(s.run('publishDeviceSnapshot()'), true);
  assert.equal(s.firebaseIdToken, 'refreshed'); assert.equal(s.capabilitiesNeedSync, false);
  s.httpCode = 403;
  assert.equal(s.run('publishDeviceSnapshot()'), false);
  assert.equal(s.firebaseIdToken, 'refreshed');
});

test('ordinary heartbeats preserve the existing capability and unavailable fingerprint count', () => {
  const s = fixture(); s.capabilitiesNeedSync = false; s.sensorReady = false;
  s.queueBytes = 0; s.pendingCount = 0;
  assert.equal(s.run('publishDeviceSnapshot()'), true);
  const write = s.requests[0].body.writes[0];
  for (const field of ['capabilities', 'fingerprintCount', 'name', 'location']) {
    assert.equal(write.update.fields[field], undefined);
    assert.equal(write.updateMask.fieldPaths.includes(field), false);
  }
  assert.equal(s.firebaseSyncStatus, 'ONLINE');
});

test('door, missing Wi-Fi/auth, low HTTPS budget and JSON overflow perform no heartbeat request', () => {
  for (const [name, configure] of [
    ['door', s => { s.doorBusy = true; }], ['wifi', s => { s.online = false; }],
    ['auth', s => { s.signedIn = false; }], ['heap', s => { s.httpsAllowed = false; }],
    ['json', s => { s.overflow = true; }],
  ]) {
    const s = fixture(); configure(s);
    assert.equal(s.run('publishDeviceSnapshot()'), false, name);
    assert.equal(s.requests.length, 0, name); assert.equal(s.capabilitiesNeedSync, true, name);
    assert.equal(s.queueBytes, 1680, name);
  }
});

test('background SYNC heartbeat is periodic with a nonempty queue and then yields a turn to attendance', () => {
  const s = fixture(); s.pendingCommandExecution = true; s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 1);
  assert.equal(s.heartbeatWaitingForAttendanceAttempt, true);
  s.now = 61000; s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 1);
  // The real main loop clears this only after a non-deferred attendance attempt.
  s.heartbeatWaitingForAttendanceAttempt = false;
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 2);
  assert.equal(s.lastHeartbeat, 61000);
});

test('foreground scan, door, sensor command and cooldown preserve a due heartbeat until safe', () => {
  for (const [name, configure] of [
    ['scan', s => { s.foregroundAttendanceHandled = false; }],
    ['door', s => { s.doorBusy = true; }],
    ['sensor command', s => { s.pendingCommandExecution = true; s.pendingCommandType = 'ENROLL_FINGERPRINT'; }],
    ['cooldown', s => { s.cooldown = true; }],
  ]) {
    const s = fixture(); configure(s);
    s.run('maybePublishDeviceSnapshot()');
    assert.equal(s.requests.length, 0, name); assert.equal(s.lastHeartbeat, 1000, name);
    s.foregroundAttendanceHandled = true; s.doorBusy = false; s.pendingCommandExecution = false; s.cooldown = false;
    s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 1, name);
  }
});

test('heartbeat interval is measured correctly across millis rollover', () => {
  const s = fixture(); s.queueBytes = 0; s.pendingCount = 0;
  s.lastHeartbeat = 0xfffffff0;
  s.now = (s.lastHeartbeat + 29999) >>> 0;
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 0);
  s.now = (s.lastHeartbeat + 30000) >>> 0;
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 1);
});

test('prepared heartbeat budget is checked after TLS client, URL and authorization allocations', () => {
  const s = fixture(); s.preparedAllowed = false;
  assert.equal(s.run('publishDeviceSnapshot()'), false);
  assert.equal(s.requests.length, 0, 'a stale preliminary budget cannot authorize the handshake');
  assert.equal(s.constructed, 1); assert.equal(s.begins, 1);
  const prepared = s.guardPhases.filter(phase => phase.prepared);
  assert.equal(prepared.length, 1);
  assert.deepEqual(prepared[0].headers, ['Content-Type', 'Authorization']);
  assert.equal(s.ended, 1); assert.equal(s.stopped, 1, 'release allocations after deferral');
  assert.equal(s.capabilitiesNeedSync, true); assert.equal(s.queueBytes, 1680);
  assert.equal(s.firebaseSyncStatus, 'PENDING');
  s.preparedAllowed = true;
  assert.equal(s.run('publishDeviceSnapshot()'), true);
  assert.equal(s.requests.length, 1); assert.equal(s.capabilitiesNeedSync, false);
});

test('failed body reservation or incomplete serialization never opens a TLS client', () => {
  for (const [name, configure] of [
    ['reservation', s => { s.reserveAllowed = false; }],
    ['serialization', s => { s.serializedBytes = 19; }],
  ]) {
    const s = fixture(); configure(s);
    assert.equal(s.run('publishDeviceSnapshot()'), false, name);
    assert.equal(s.constructed, 0, name); assert.equal(s.begins, 0, name);
    assert.equal(s.requests.length, 0, name);
    assert.equal(s.capabilitiesNeedSync, true, name);
    assert.equal(s.queueBytes, 1680, name);
    assert.equal(s.firebaseSyncStatus, 'PENDING', name);
    assert.deepEqual(s.deferrals, ['JSON heartbeat'], name);
  }
});

test('enrollment completion, error and timeout keep due heartbeat deferred until status is acknowledged', () => {
  for (const outcome of ['success', 'sensor error', 'timeout']) {
    const s = fixture(); s.pendingCommandType = 'ENROLL_FINGERPRINT';
    s.enrollmentStage = s.EnrollmentStage.STORE_MODEL;
    s.run('maybePublishDeviceSnapshot()');
    assert.equal(s.requests.length, 0, outcome); assert.equal(s.templateReads, 0, outcome);
    assert.equal(s.lastHeartbeat, 1000, outcome);
    // Sensor work has finished, but ownership/status reporting still awaits
    // Firestore; the idle enrollment stage alone does not permit telemetry.
    s.enrollmentStage = s.EnrollmentStage.IDLE;
    s.pendingCommandResult = true; s.pendingCommandSuccess = outcome === 'success';
    s.run('maybePublishDeviceSnapshot()');
    assert.equal(s.requests.length, 0, outcome); assert.equal(s.templateReads, 0, outcome);
    assert.equal(s.lastHeartbeat, 1000, outcome);
    s.pendingCommandResult = false;
    s.run('maybePublishDeviceSnapshot()');
    assert.equal(s.requests.length, 1, outcome); assert.equal(s.templateReads, 1, outcome);
  }
});

test('deletion completion blocks telemetry while a pending SYNC result stays background work', () => {
  const s = fixture(); s.pendingCommandResult = true; s.pendingCommandType = 'DELETE_FINGERPRINT';
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 0);
  assert.equal(s.lastHeartbeat, 1000);
  s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.run('maybePublishDeviceSnapshot()'); assert.equal(s.requests.length, 1);
});
