const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const commandSource = fs.readFileSync(path.join(__dirname, '..', 'esp8266_fingerprint', '60_device_commands.ino'), 'utf8');

function functionSource(name) {
  const start = commandSource.search(new RegExp(`\\bbool ${name}\\([^)]*\\)\\s*\\{`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = commandSource.indexOf('{', start), depth = 1;
  while (depth && ++end < commandSource.length) {
    if (commandSource[end] === '{') depth++;
    if (commandSource[end] === '}') depth--;
  }
  return commandSource.slice(start, end + 1);
}

// Execute the production branches. Adapt only C++ types, String/JSON APIs and
// peripheral construction; HTTP responses and command state remain observable.
function adapt(source) {
  return source
    .replace(/^bool (\w+)\(([^)]*)\)\s*\{/, (_, name, parameters) => {
      const args = parameters.trim() ? parameters.split(',').map(parameter => parameter.match(/(\w+)$/)[1]) : [];
      return `function ${name}(${args.join(',')}) {`;
    })
    .replace(/DynamicJsonDocument (\w+)\(\d+\);/g, 'const $1 = jsonDocument();')
    .replace(/BearSSL::WiFiClientSecure (\w+);/g, 'const $1 = new BearSSL.WiFiClientSecure();')
    .replace(/HTTPClient (\w+);/g, 'const $1 = new HTTPClient();')
    .replace(/reinterpret_cast<const uint8_t\*>\((\w+)\.c_str\(\)\)/g, '$1')
    .replace(/\bconst\s+(?:bool|int|String)\s+/g, 'const ')
    .replace(/\bString (\w+);/g, "let $1 = '';")
    .replace(/\b(?:bool|int|String|JsonObject|JsonArray)\s+(\w+)(?=\s*[=;])/g, 'let $1')
    .replace(/(current(?:\["\w+"\])+) \| ""/g, "$1.as('String')")
    .replace(/(current(?:\["\w+"\])+) \| false/g, "$1.as('bool')")
    .replace(/\.as<String>\(\)/g, ".as('String')")
    .replace(/serializeJson\((\w+), (\w+)\)/g, 'serializeJson($1, value => { $2 = value; })')
    .replace(/\b\w+\.reserve\([^;\n]*\);/g, '')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '')
    .replace(/\bHTTPClient::/g, 'HTTPClient.')
    .replace(/\bnullptr\b/g, 'null');
}

function jsonDocument() {
  const root = {};
  const node = (parent, key) => new Proxy({}, {
    get(_, name) {
      const value = parent[key];
      if (name === 'toJSON') return () => value;
      if (name === 'as') return type => type === 'bool' ? value === true : typeof value === 'string' ? value : '';
      if (name === 'createNestedObject') return child => {
        parent[key] ||= {}; parent[key][child] = {}; return node(parent[key], child);
      };
      if (name === 'assignJSON') return json => {
        for (const field of Object.keys(root)) delete root[field];
        Object.assign(root, json);
      };
      parent[key] ||= {};
      return node(parent[key], name);
    },
    set(_, name, value) { parent[key] ||= {}; parent[key][name] = value; return true; },
  });
  return node({ root }, 'root');
}

function fixture(type = 'SYNC_ATTENDANCE') {
  const s = {
    DEVICE_ID: 'GATE-01', FIRESTORE_URL: 'https://firestore.test/v1/projects/test/databases/(default)/documents',
    WL_CONNECTED: 1, online: true, signedIn: true, httpsAllowed: true, doorBusy: false,
    commandRequestId: 'command-1', commandVersion: 'old-version', commandAlreadyApplied: false,
    documentRequestId: 'command-1', documentVersion: '2026-10-05T12:00:00.123456Z', documentApplied: true,
    documentCode: 200, documentMalformed: false, beginAllowed: true, patchCode: 200,
    firebaseIdToken: 'test-token', completedAt: '2026-10-05T12:01:00Z',
    pendingCommandType: type, pendingCommandSuccess: true, pendingCommandResult: true,
    pendingCommandRestart: type === 'RESTART_DEVICE', pendingCommandEmployeeId: 'employee-1', pendingCommandTemplateId: 7,
    syncCommandHadRejections: false, foregroundAttendanceHandled: true, waitingForFingerRemoval: false,
    sensorReady: true, COMMAND_RETRY_INTERVAL_MS: 10000, COMMAND_IDLE_POLL_INTERVAL_MS: 15000,
    commandPollIntervalMs: 3000, requests: [], signals: [], displays: [], readyCalls: 0,
    restarts: 0, commits: 0, stoppedClients: 0, endedRequests: 0, results: [], deferrals: [],
    jsonDocument, String, strcmp: (a, b) => a === b ? 0 : 1,
    Serial: { printf() {}, println() {} },
    WiFi: { status: () => s.online ? 1 : 0 },
    ESP: { getFreeHeap: () => 34000, restart() { s.restarts++; } },
    BearSSL: { WiFiClientSecure: class {
      setInsecure() {}
      stop() { s.stoppedClients++; }
    } },
    HTTPClient: class {
      static errorToString(code) { return `test HTTP error ${code}`; }
      useHTTP10() {}
      setTimeout() {}
      begin(client, url) { this.url = url; this.headers = {}; return s.beginAllowed; }
      addHeader(name, value) { this.headers[name] = value; }
      GET() { s.requests.push({ method: 'GET', url: this.url, headers: this.headers }); return s.documentCode; }
      getStream() {
        return s.documentMalformed ? '{invalid' : JSON.stringify({ updateTime: s.documentVersion, fields: {
          requestId: { stringValue: s.documentRequestId }, applied: { booleanValue: s.documentApplied },
        } });
      }
      sendRequest(method, body, length) {
        assert.equal(body.length, length);
        s.requests.push({ method, url: this.url, headers: this.headers, body: JSON.parse(body) });
        if (method === 'POST') s.commits++;
        return s.patchCode;
      }
      end() { s.endedRequests++; }
    },
    deserializeJson(doc, input) { try { doc.assignJSON(JSON.parse(input)); return false; } catch { return true; } },
    serializeJson: (doc, set) => set(JSON.stringify(doc)),
    measureJson: doc => JSON.stringify(doc).length,
    utcTimestamp: () => s.completedAt,
    firebaseSignIn: () => s.signedIn,
    canStartHttpsRequest: () => s.httpsAllowed,
    doorNeedsResponsiveLoop: () => s.doorBusy,
    recordHttpsResult(label, code) { s.results.push({ label, code }); },
    deferHttpsRequests(label) { s.deferrals.push(label); },
    setLatestError(message) { s.error = message; },
    fingerprintResultHoldActive: () => false,
    showReadyScreen() { s.readyCalls++; },
    showLcd(...lines) { s.displays.push(lines); },
    signalResult(success) { s.signals.push(success); },
    startWaitingForFingerRemoval() {}, showSensorReconnectScreen() {},
  };
  const context = vm.createContext(s);
  s.run = code => vm.runInContext(code, context);
  for (const name of ['refreshCommandVersion', 'updateDeviceCommandStatus', 'commitFingerprintCompletion', 'finishDeviceCommand']) {
    s.run(adapt(functionSource(name)));
  }
  s.patchRequests = () => s.requests.filter(request => request.method === 'PATCH');
  return s;
}

test('applied generic commands still publish COMPLETED to Firestore', () => {
  for (const type of ['SYNC_ATTENDANCE', 'TEST_LED_GREEN', 'TEST_LED_RED', 'TEST_BUZZER', 'OPEN_DOOR', 'CLOSE_DOOR', 'RESTART_DEVICE']) {
    const s = fixture(type);
    assert.equal(s.run('finishDeviceCommand()'), true, type);
    assert.equal(s.commandAlreadyApplied, true, 'refresh observed the app-owned applied flag');
    assert.equal(s.patchRequests().length, 1, `${type}: completion must be persisted even when applied=true`);
    const patch = s.patchRequests()[0];
    assert.equal(new URL(patch.url).pathname.endsWith('/deviceCommands/GATE-01'), true);
    const query = new URL(patch.url).searchParams;
    assert.deepEqual(query.getAll('updateMask.fieldPaths'), ['status', 'message', 'completedAt']);
    assert.equal(query.get('currentDocument.updateTime'), s.documentVersion, 'PATCH guards the version read from Firestore');
    assert.equal(patch.headers.Authorization, 'Bearer test-token');
    assert.equal(patch.headers['Content-Type'], 'application/json');
    assert.equal(patch.body.fields.status.stringValue, 'COMPLETED');
    assert.equal(patch.body.fields.completedAt.timestampValue, s.completedAt);
    assert.equal('applied' in patch.body.fields, false, 'status reporting preserves app-owned applied semantics');
    assert.equal(s.pendingCommandResult, false, type);
    assert.equal(s.commandPollIntervalMs, s.COMMAND_IDLE_POLL_INTERVAL_MS, type);
    assert.equal(s.restarts, type === 'RESTART_DEVICE' ? 1 : 0, type);
    assert.equal(s.commits, 0, `${type}: generic commands never commit fingerprint ownership`);
  }
});

test('completion success requires a 2xx PATCH response even when applied=true', () => {
  for (const code of [-1, 199, 200, 201, 204, 299, 300, 403, 409, 412, 429, 500, 503]) {
    const s = fixture(); s.patchCode = code;
    assert.equal(s.run("updateDeviceCommandStatus('COMPLETED', 'Attendance synchronized')"), code >= 200 && code < 300, `HTTP ${code}`);
    assert.equal(s.patchRequests().length, 1, `HTTP ${code} must reach PATCH`);
    assert.equal(s.results.at(-1).code, code);
    assert.equal(s.endedRequests, 2, 'both refresh GET and status PATCH release their HTTP request');
    assert.equal(s.stoppedClients, 2, 'both TLS clients are stopped');
  }
});

test('failed completion PATCH retains SYNC result without success feedback, then retry completes', () => {
  for (const code of [-1, 403, 409, 412, 503]) {
    const s = fixture(); s.patchCode = code;
    assert.equal(s.run('finishDeviceCommand()'), false, `HTTP ${code}`);
    assert.equal(s.patchRequests().length, 1);
    assert.equal(s.pendingCommandResult, true, 'retain the completed local result for retry');
    assert.equal(s.pendingCommandSuccess, true, 'network failure does not rerun or fail the completed synchronization');
    assert.equal(s.commandPollIntervalMs, s.COMMAND_RETRY_INTERVAL_MS);
    assert.deepEqual(s.signals, [], 'do not announce a saved completion before Firestore acknowledges it');
    assert.deepEqual(s.displays, [], 'do not show DA DONG BO before Firestore acknowledges it');
    assert.equal(s.readyCalls, 1, 'drained attendance can return to the ready display while result reporting retries');

    s.patchCode = 200; s.documentVersion = '2026-10-05T12:01:30.654321Z';
    assert.equal(s.run('finishDeviceCommand()'), true);
    assert.equal(s.patchRequests().length, 2);
    assert.equal(new URL(s.patchRequests()[1].url).searchParams.get('currentDocument.updateTime'), s.documentVersion);
    assert.equal(s.pendingCommandResult, false);
    assert.equal(s.commandPollIntervalMs, s.COMMAND_IDLE_POLL_INTERVAL_MS);
    assert.deepEqual(s.signals, [true]);
    assert.deepEqual(s.displays, [['DA DONG BO', 'CHAM CONG']]);
  }
});

test('restart waits for acknowledged status and occurs once after a successful retry', () => {
  const s = fixture('RESTART_DEVICE'); s.patchCode = 503;
  assert.equal(s.run('finishDeviceCommand()'), false);
  assert.equal(s.pendingCommandResult, true); assert.equal(s.restarts, 0); assert.deepEqual(s.signals, []);
  s.patchCode = 204;
  assert.equal(s.run('finishDeviceCommand()'), true);
  assert.equal(s.pendingCommandResult, false); assert.equal(s.restarts, 1); assert.deepEqual(s.signals, [true]);
});

test('changed command request cannot receive the previous command completion', () => {
  const s = fixture(); s.documentRequestId = 'replacement-command';
  assert.equal(s.run('finishDeviceCommand()'), false);
  assert.equal(s.patchRequests().length, 0);
  assert.equal(s.pendingCommandResult, false, 'refresh discards the superseded local result');
  assert.deepEqual(s.signals, []);
});

test('FAILED status also requires an acknowledged PATCH and retains pending failure until retry', () => {
  const s = fixture(); s.pendingCommandSuccess = false; s.patchCode = 503;
  assert.equal(s.run('finishDeviceCommand()'), false);
  assert.equal(s.pendingCommandResult, true);
  assert.deepEqual(s.signals, []);
  assert.equal(s.patchRequests()[0].body.fields.status.stringValue, 'FAILED');
  assert.equal(s.patchRequests()[0].body.fields.completedAt.timestampValue, s.completedAt);
  s.patchCode = 200;
  assert.equal(s.run('finishDeviceCommand()'), true);
  assert.equal(s.pendingCommandResult, false); assert.deepEqual(s.signals, [false]);
});

test('already applied fingerprint commit remains idempotent, while its COMPLETED status still gets saved', () => {
  for (const type of ['ENROLL_FINGERPRINT', 'DELETE_FINGERPRINT']) {
    const s = fixture(type);
    assert.equal(s.run('finishDeviceCommand()'), true, type);
    assert.equal(s.patchRequests().length, 1, `${type}: applied ownership does not imply saved COMPLETED status`);
    assert.equal(s.commits, 0, `${type}: already applied ownership must not be committed twice`);
    assert.equal(s.requests.filter(request => request.method === 'GET').length, 2, 'status and fingerprint commit each refresh the command');
    assert.equal(s.pendingCommandResult, false);
    assert.deepEqual(s.signals, [true]);
  }
});
