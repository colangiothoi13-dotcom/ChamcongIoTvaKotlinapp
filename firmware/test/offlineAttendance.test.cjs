const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const sketch = path.join(__dirname, '..', 'esp8266_fingerprint');
const read = name => fs.readFileSync(path.join(sketch, name), 'utf8');

function functionSource(file, name) {
  const source = read(file);
  const start = source.search(new RegExp(`\\b(?:inline\\s+)?(?:bool|void|int|AttendanceDelivery|AttendanceSyncResult) ${name}\\([^)]*\\)\\s*\\{`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

// Run the real scalar control paths. Only Arduino types, reference parameters,
// clocks and JSON/peripheral APIs are adapted; policy branches remain unchanged.
function adapt(source) {
  const refs = [];
  const signature = /^(?:inline\s+)?(?:bool|void|int|AttendanceDelivery|AttendanceSyncResult) (\w+)\(([^)]*)\)\s*\{/;
  source = source.replace(signature, (_, name, parameters) => {
    const args = (parameters.trim() ? parameters.split(',') : []).map(parameter => {
      parameter = parameter.trim();
      const ref = parameter.match(/^(?:const\s+)?(?:String|uint16_t|bool)\s*&\s*(\w+)$/);
      if (ref && !parameter.trim().startsWith('const ')) {
        refs.push(ref[1]);
        return `_set_${ref[1]}`;
      }
      return parameter.trim().match(/(\w+)$/)[1];
    });
    return `function ${name}(${args.join(',')}) { ${refs.map(name => `let ${name} = '';`).join(' ')} try {`;
  });
  source = source.slice(0, -1) + `} finally { ${refs.map(name => `_set_${name}(${name});`).join(' ')} } }`;
  return source
    .replace(/\b(?:AttendanceDelivery|AttendanceSyncResult)::/g, value => value.replace('::', '.'))
    .replace(/static_cast<uint32_t>\(now - started\)/g, '((now - started) >>> 0)')
    .replace(/DynamicJsonDocument (\w+)\(\d+\);/g, 'const $1 = jsonDocument();')
    .replace(/\bconst\s+(?:unsigned long|uint8_t|uint32_t|uint16_t|time_t|size_t|bool|int|String|char\s*\*|AttendanceSyncResult|AttendanceDelivery)\s+/g, 'const ')
    .replace(/\b(?:unsigned long|uint8_t|uint16_t|uint32_t|time_t|bool|int|String|File|JsonObject)\s+(\w+)(?=\s*[=;])/g, 'let $1')
    .replace(/struct tm (\w+);/g, 'let $1 = {};')
    .replace(/char (\w+)\[\d+\];/g, "let $1 = '';")
    .replace(/gmtime_r\(&(\w+), &(\w+)\);/g, '$2 = new Date($1 * 1000);')
    .replace(/strftime\((\w+), sizeof\(\1\), "%H:%M:%S", &(\w+)\);/g, "$1 = $2.toISOString().slice(11,19);")
    .replace(/getFingerprintMapping\(templateId, employeeId, employeeName\)/g,
      'getFingerprintMapping(templateId, value => { employeeId = value; }, value => { employeeName = value; })')
    .replace(/reserveAttendanceEventId\(eventId\)/g, 'reserveAttendanceEventId(value => { eventId = value; })')
    .replace(/\bserializeJson\((\w+), (\w+)\)/g, 'serializeJson($1, value => { $2 = value; })')
    .replace(/buildAttendanceEvent\(templateId, confidence, eventId, payload, employeeName, attendanceTime, attendanceType\)/g,
      'buildAttendanceEvent(templateId, confidence, value => { eventId = value; }, value => { payload = value; }, value => { employeeName = value; }, value => { attendanceTime = value; }, value => { attendanceType = value; })')
    .replace(/uploadAttendance\(finger\.fingerID, finger\.confidence,\s*employeeName, attendanceTime, attendanceType\)/g,
      'uploadAttendance(finger.fingerID, finger.confidence, value => { employeeName = value; }, value => { attendanceTime = value; }, value => { attendanceType = value; })')
    .replace(/resolveOfflineAttendancePayload\(payload, resolvedPayload\)/g,
      'resolveOfflineAttendancePayload(payload, value => { resolvedPayload = value; })')
    .replace(/readDeviceCommand\(templateId, type, employeeId, wasProcessing, wasCompleted\)/g,
      'readDeviceCommand(value => { templateId = value; }, value => { type = value; }, value => { employeeId = value; }, value => { wasProcessing = value; }, value => { wasCompleted = value; })')
    .replace(/(record\["\w+"\]) \| ""/g, "$1.as('String')")
    .replace(/\.as<(bool|int|String)>\(\)/g, ".as('$1')")
    .replace(/\b\w+\.reserve\([^;\n]*\);/g, '')
    .replace(/(\w+)\.trim\(\);/g, '$1 = $1.trim();')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '')
    .replace(/\bnullptr\b/g, 'null');
}

function jsonDocument() {
  const root = {};
  const node = (parent, key) => new Proxy({}, {
    get(_, name) {
      const value = parent[key];
      if (name === 'toJSON') return () => value;
      if (name === 'as') return type => type === 'bool' ? value === true :
        type === 'int' ? (Number.isFinite(Number(value)) ? Math.trunc(Number(value)) : 0) :
          typeof value === 'string' ? value : '';
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

function fixture() {
  const state = {
    now: 1000, unixTime: 1791072600, MIN_VALID_UNIX_TIME: 1700000000,
    DEVICE_ID: 'GATE-01', OFFLINE_AS608_ACCESS_ENABLED: true,
    FOREGROUND_ATTENDANCE_VALIDITY_MS: 15000, ATTENDANCE_NEXT_RECORD_INTERVAL_MS: 250,
    COMMAND_ACTIVE_POLL_INTERVAL_MS: 3000, COMMAND_IDLE_POLL_INTERVAL_MS: 15000,
    COMMAND_RETRY_INTERVAL_MS: 10000, SYNC_COMMAND_TIMEOUT_MS: 180000,
    mappingMode: 'unavailable', mappingLookups: [], mappingDelaySeconds: 0, sequence: 0, reserveAllowed: true,
    lastFingerprintAuthorizationUnavailable: false, lastFingerprintAuthorizationDenied: false,
    lastAttendanceCreatedOffline: false, lastAttendanceMissingClock: false,
    fingerprintDoorNoticeAttendanceSaved: true, enqueueAllowed: true, records: [], posted: [],
    postCode: 200, online: false, littleFsReady: true, firebaseSyncStatus: 'ONLINE',
    foregroundAttendanceEventId: '', foregroundAttendanceEmployeeName: '',
    foregroundAttendanceCreatedAt: 0, foregroundAttendanceHandled: true,
    foregroundAttendanceDelivery: 'NOT_STORED', attendancePendingSync: false,
    lastAttendanceSync: 0, attendanceSyncIntervalMs: 5000,
    ATTENDANCE_OUTBOX_PATH: '/attendance.outbox', lastAcknowledgedAttendanceEventId: '',
    lastRejectedAttendanceEventId: '', opens: 0, operations: [], displays: [], signals: [],
    sensorReady: true, pendingCommandExecution: false, pendingCommandResult: false,
    pendingCommandType: '', pendingCommandSuccess: false, pendingCommandRestart: false,
    pendingCommandStartedAt: 0, pendingCommandTemplateId: 0, pendingCommandEmployeeId: '',
    commandPollIntervalMs: 15000, lastCommandCheck: 0, commandRequestId: 'requested-id',
    sensorDeletes: 0, sensorEnrollments: 0, statusUpdates: [],
    AttendanceDelivery: Object.fromEntries(['NOT_STORED', 'QUEUED', 'CONFIRMED', 'REJECTED', 'LOCAL_ACCEPTED', 'ACCESS_ONLY'].map(x => [x, x])),
    AttendanceSyncResult: Object.fromEntries(['EMPTY', 'DEFERRED', 'RETRY', 'ACKNOWLEDGED', 'REJECTED', 'STORAGE_ERROR'].map(x => [x, x])),
    FINGERPRINT_OK: 0, FINGERPRINT_NOFINGER: 2, FINGERPRINT_IMAGEFAIL: 3,
    FINGERPRINT_NOTFOUND: 9, FINGERPRINT_NOMATCH: 8, FINGERPRINT_IMAGEMESS: 6,
    FINGERPRINT_FEATUREFAIL: 7, FINGERPRINT_INVALIDIMAGE: 15,
    Serial: { printf() {}, printf_P() {}, println() {} }, F: value => value, PSTR: value => value,
    jsonDocument,
    millis: () => state.now, time: () => state.unixTime,
    utcTimestamp: seconds => new Date(seconds * 1000).toISOString().replace('.000', ''),
    deserializeJson(doc, input) { try { doc.assignJSON(JSON.parse(input)); return false; } catch { return true; } },
    serializeJson: (doc, set) => set(JSON.stringify(doc)),
    getFingerprintMapping(templateId, setId, setName) {
      state.mappingLookups.push(templateId);
      state.unixTime += state.mappingDelaySeconds;
      state.lastFingerprintAuthorizationUnavailable = state.mappingMode === 'unavailable';
      state.lastFingerprintAuthorizationDenied = state.mappingMode === 'denied';
      setId(state.mappingMode === 'enabled' ? 'employee-1' : '');
      setName(state.mappingMode === 'enabled' ? 'Employee One' : '');
      return state.mappingMode === 'enabled';
    },
    reserveAttendanceEventId(set) {
      if (!state.reserveAllowed) return false;
      set(`GATE-01-event-${++state.sequence}`); return true;
    },
    enqueueAttendanceEvent(id, payload) {
      state.operations.push('enqueue');
      if (!state.enqueueAllowed) return false;
      state.records.push(JSON.stringify({ eventId: id, payload }) + '\n'); return true;
    },
    doorNeedsResponsiveLoop: () => false, fingerprintResultHoldActive: () => false,
    fingerprintPollDue: () => true, playBuzzerTone() {}, recordFailedScan() {},
    startWaitingForFingerRemoval() {}, setSensorError(message) { state.error = message; },
    finger: { fingerID: 7, confidence: 77, getImage: () => 0, image2Tz: () => 0, fingerFastSearch: () => 0,
      deleteModel() { state.sensorDeletes++; return 0; } },
    setLatestError(message) { state.error = message; },
    showLcd(...lines) { state.displays.push(lines); },
    showFingerprintResultNotice(...lines) { state.displays.push(lines); },
    signalResult(success) { state.signals.push(success); },
    openDoor() { state.operations.push('open'); state.opens++; },
    canStartHttpsRequest: () => state.online, WL_CONNECTED: 1,
    WiFi: { status: () => state.online ? 1 : 0 },
    attendanceOutboxIsFull: () => !state.enqueueAllowed,
    attendanceOutboxBytes: () => state.records.join('').length,
    attendanceOutboxIsEmpty: () => state.littleFsReady && state.records.length === 0,
    postAttendanceEvent(id, payload) { state.posted.push({ id, payload: JSON.parse(payload) }); return state.postCode; },
    removeAttendanceOutboxHead() { state.records.shift(); return true; },
    LittleFS: { exists: () => state.records.length > 0, open() {
      const content = state.records.join(''); let offset = 0;
      return { size: () => content.length, close() {}, position: () => offset,
        readStringUntil() { const end = content.indexOf('\n', offset); const line = content.slice(offset, end); offset = end + 1; return line; } };
    } },
    hasValidClock: () => state.unixTime >= state.MIN_VALID_UNIX_TIME,
    readDeviceCommand(setTemplate, setType, setEmployee, setProcessing, setCompleted) {
      setTemplate(7); setType(state.nextCommand); setEmployee('employee-1'); setProcessing(false); setCompleted(false); return true;
    },
    updateDeviceCommandStatus(status) { state.statusUpdates.push(status); return true; },
    isSupportedDeviceCommand: () => true,
    startEnrollment() { state.sensorEnrollments++; return true; },
    finishDeviceCommand: () => true,
  };
  const context = vm.createContext(state);
  state.run = source => vm.runInContext(source, context);
  for (const name of ['elapsedAtLeast', 'foregroundConfirmationCanOpen', 'attendanceTransportUnavailable']) {
    state.run(adapt(functionSource('RuntimePolicy.h', name)));
  }
  for (const [file, names] of [
    ['40_attendance_service.ino', ['invalidateForegroundAttendanceAccess', 'buildAttendanceEvent', 'resolveOfflineAttendancePayload', 'uploadAttendance', 'handleAttendanceDelivery']],
    ['12_as608.ino', ['handleFingerprintScan']],
    ['30_attendance_storage.ino', ['httpResponseAcknowledgesEvent', 'isPermanentAttendanceFailure', 'flushAttendanceOutbox']],
    ['60_device_commands.ino', ['checkDeviceCommand']],
  ]) for (const name of names) state.run(adapt(functionSource(file, name)));
  state.build = () => {
    const result = {};
    state.captureId = value => { result.id = value; };
    state.capturePayload = value => { result.payload = value; };
    state.captureName = value => { result.name = value; };
    state.captureTime = value => { result.time = value; };
    state.captureType = value => { result.type = value; };
    result.ok = state.run('buildAttendanceEvent(7, 77, captureId, capturePayload, captureName, captureTime, captureType)');
    return result;
  };
  state.resolve = payload => {
    const result = {};
    state.inputPayload = payload;
    state.captureResolved = value => { result.payload = value; };
    result.code = state.run('resolveOfflineAttendancePayload(inputPayload, captureResolved)');
    return result;
  };
  return state;
}

test('offline AS608 match stores only raw scan and opens after durable enqueue', () => {
  const s = fixture(); s.run('handleFingerprintScan()');
  assert.equal(s.opens, 1); assert.deepEqual(s.operations, ['enqueue', 'open']);
  const event = JSON.parse(s.records[0]); const payload = JSON.parse(event.payload);
  assert.deepEqual(payload, { offlineScan: true, deviceId: 'GATE-01', templateId: 7, confidence: 77,
    timestamp: s.utcTimestamp(s.unixTime) });
  assert.equal('employeeId' in payload, false); assert.equal('employeeName' in payload, false);
  assert.equal(s.foregroundAttendanceDelivery, 'LOCAL_ACCEPTED');
});

test('explicit mapping denial never falls back to local access', () => {
  const s = fixture(); s.mappingMode = 'denied'; s.run('handleFingerprintScan()');
  assert.equal(s.opens, 0); assert.equal(s.records.length, 0); assert.equal(s.sequence, 0);
  assert.deepEqual(s.signals, [false]);
});

test('a failed new upload invalidates old door authorization without deleting the old event', () => {
  for (const [failure, configure, expected] of [
    ['denied mapping', s => { s.mappingMode = 'denied'; }, 'REJECTED'],
    ['invalid clock', s => { s.unixTime = 0; }, 'ACCESS_ONLY'],
    ['sequence failure', s => { s.reserveAllowed = false; }, 'NOT_STORED'],
    ['storage failure', s => { s.enqueueAllowed = false; }, 'NOT_STORED'],
  ]) {
    const s = fixture(); s.online = true; s.mappingMode = 'enabled'; s.run('handleFingerprintScan()');
    const saved = [...s.records];
    assert.equal(s.foregroundAttendanceHandled, false);
    configure(s);
    assert.equal(s.run('uploadAttendance(7, 77, () => {}, () => {}, () => {})'), expected, failure);
    assert.deepEqual(s.records, saved, failure);
    assert.equal(s.foregroundAttendanceHandled, true, failure);
    assert.equal(s.foregroundAttendanceEventId, '', failure);
    assert.equal(s.foregroundAttendanceEmployeeName, '', failure);
    assert.equal(s.foregroundAttendanceDelivery, expected === 'ACCESS_ONLY' ? 'ACCESS_ONLY' : 'NOT_STORED', failure);
    s.mappingMode = 'enabled';
    assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED');
    assert.equal(s.opens, 0, `${failure}: acknowledgment of the superseded event cannot open the door`);
  }
});

test('offline cold boot opens a matched AS608 template without inventing an attendance record', () => {
  const s = fixture(); s.unixTime = 0;
  s.run('handleFingerprintScan()');
  assert.equal(s.opens, 1); assert.deepEqual(s.operations, ['open']);
  assert.equal(s.records.length, 0); assert.equal(s.sequence, 0);
  assert.equal(s.attendancePendingSync, false); assert.equal(s.foregroundAttendanceHandled, true);
  assert.equal(s.foregroundAttendanceDelivery, 'ACCESS_ONLY');
  assert.equal(s.fingerprintDoorNoticeAttendanceSaved, false);
  assert.deepEqual(s.displays.at(-1), ['DANG MO CUA', 'CHUA LUU CONG']);
  assert.match(s.error, /CHUA LUU CONG/);
  // Reconnecting cannot convert the access-only scan to a false current-time event.
  s.unixTime = 1791072600; s.online = true; s.mappingMode = 'enabled';
  assert.equal(s.run('flushAttendanceOutbox()'), 'EMPTY');
  assert.equal(s.posted.length, 0); assert.equal(s.opens, 1);
});

test('cold boot access still rejects unknown or explicitly denied fingerprints and can be disabled', () => {
  for (const configure of [
    s => { s.finger.fingerFastSearch = () => s.FINGERPRINT_NOTFOUND; },
    s => { s.mappingMode = 'denied'; },
    s => { s.OFFLINE_AS608_ACCESS_ENABLED = false; },
  ]) {
    const s = fixture(); s.unixTime = 0; configure(s); s.run('handleFingerprintScan()');
    assert.equal(s.opens, 0); assert.equal(s.records.length, 0); assert.equal(s.sequence, 0);
    assert.deepEqual(s.signals, [false]);
  }
});

test('valid-time scans still require sequence reservation and durable queue storage before opening', () => {
  for (const configure of [s => { s.reserveAllowed = false; }, s => { s.enqueueAllowed = false; }]) {
    const s = fixture(); configure(s); s.run('handleFingerprintScan()');
    assert.equal(s.opens, 0); assert.equal(s.records.length, 0);
    assert.equal(s.foregroundAttendanceHandled, true);
    assert.equal(s.lastFingerprintAuthorizationUnavailable, false, 'accepted local lookup does not mask time/storage failure');
  }
});

test('online scan preserves standard Firestore shape and awaits acknowledgment', () => {
  const s = fixture(); s.online = true; s.mappingMode = 'enabled'; s.run('handleFingerprintScan()');
  assert.equal(s.opens, 0); const payload = JSON.parse(JSON.parse(s.records[0]).payload);
  assert.equal('offlineScan' in payload, false);
  assert.deepEqual(payload.fields, {
    employeeId: { stringValue: 'employee-1' }, employeeName: { stringValue: 'Employee One' },
    deviceId: { stringValue: 'GATE-01' }, templateId: { integerValue: 7 }, confidence: { integerValue: 77 },
    type: { stringValue: 'SCAN' }, resolutionStatus: { stringValue: 'PENDING' }, status: { stringValue: 'PENDING' },
    syncStatus: { stringValue: 'PENDING_SYNC' }, timestamp: { timestampValue: s.utcTimestamp(s.unixTime) },
    verified: { booleanValue: true },
  });
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.opens, 1);
});

test('slow mapping lookup preserves the timestamp captured when the scan started', () => {
  for (const mappingMode of ['enabled', 'unavailable']) {
    const s = fixture(); s.mappingMode = mappingMode; s.mappingDelaySeconds = 12;
    const scanTime = s.utcTimestamp(s.unixTime);
    const event = s.build(); assert.equal(event.ok, true);
    const payload = JSON.parse(event.payload);
    assert.equal(payload.offlineScan ? payload.timestamp : payload.fields.timestamp.timestampValue, scanTime);
    assert.equal(s.unixTime, 1791072612, 'mapping fixture simulated elapsed network time');
  }
});

test('reconnected raw scan resolves identity while preserving its original time, slot and event id', () => {
  const s = fixture(); s.run('handleFingerprintScan()'); const original = JSON.parse(s.records[0]);
  const raw = JSON.parse(original.payload); s.unixTime += 3600; s.now += 100;
  s.online = true; s.mappingMode = 'enabled';
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED');
  assert.equal(s.posted[0].id, original.eventId);
  assert.equal(s.posted[0].payload.fields.timestamp.timestampValue, raw.timestamp);
  assert.equal(s.posted[0].payload.fields.templateId.integerValue, raw.templateId);
  assert.equal(s.posted[0].payload.fields.confidence.integerValue, raw.confidence);
  assert.equal(s.records.length, 0); assert.equal(s.opens, 1, 'data acknowledgment cannot reopen local access');
});

test('transient resolver and POST failures preserve immutable queue heads one at a time', () => {
  const s = fixture(); s.run('handleFingerprintScan()'); s.run('handleFingerprintScan()');
  const saved = [...s.records]; s.online = true;
  assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.deepEqual(s.records, saved);
  assert.equal(s.posted.length, 0);
  s.mappingMode = 'enabled'; s.postCode = 503;
  assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.deepEqual(s.records, saved);
  s.postCode = 200;
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 1);
  assert.equal(s.records[0], saved[1]);
});

test('deactivated mapping rejects a raw scan without posting identity or opening again', () => {
  const s = fixture(); s.run('handleFingerprintScan()'); s.online = true; s.mappingMode = 'denied';
  assert.equal(s.run('flushAttendanceOutbox()'), 'REJECTED'); assert.equal(s.records.length, 0);
  assert.equal(s.posted.length, 0); assert.equal(s.opens, 1);
});

test('restored queued raw scans synchronize data only and invalid records are rejected', () => {
  const before = fixture(); before.run('handleFingerprintScan()');
  const after = fixture(); after.records = [...before.records]; after.online = true; after.mappingMode = 'enabled';
  assert.equal(after.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(after.opens, 0);
  const raw = JSON.parse(JSON.parse(before.records[0]).payload);
  for (const invalid of [ '{invalid', ...[
    { ...raw, templateId: 0 }, { ...raw, templateId: 128 }, { ...raw, confidence: -1 },
    { ...raw, confidence: 65536 }, { ...raw, deviceId: 'OTHER' }, { ...raw, timestamp: '' },
  ].map(JSON.stringify) ]) assert.equal(after.resolve(invalid).code, 400);
});

test('standard queue payload is returned byte for byte without another mapping lookup', () => {
  const s = fixture(); const original = '{ "fields": {"type":{"stringValue":"SCAN"}} }';
  const result = s.resolve(original); assert.equal(result.code, 200); assert.equal(result.payload, original);
  assert.equal(s.mappingLookups.length, 0);
});

test('ENROLL and DELETE wait for queue drain before mutating AS608 template ownership', () => {
  for (const type of ['ENROLL_FINGERPRINT', 'DELETE_FINGERPRINT']) {
    const s = fixture(); s.nextCommand = type; s.records = ['saved scan\n'];
    assert.equal(s.run('checkDeviceCommand()'), false);
    assert.equal(s.sensorDeletes, 0); assert.equal(s.sensorEnrollments, 0); assert.deepEqual(s.statusUpdates, []);
    s.records = []; assert.equal(s.run('checkDeviceCommand()'), true);
    assert.deepEqual(s.statusUpdates, ['PROCESSING']);
    assert.equal(s.sensorDeletes + s.sensorEnrollments, 1);
  }
});

test('firmware does not persist an employee roster/cache for offline access', () => {
  const source = read('40_attendance_service.ino') + read('esp8266_fingerprint.ino');
  assert.doesNotMatch(source, /cacheFingerprintMapping|loadFingerprintCache|saveFingerprintCache/);
  const raw = fixture().build(); assert.equal(raw.ok, true);
  assert.deepEqual(Object.keys(JSON.parse(raw.payload)).sort(), ['confidence', 'deviceId', 'offlineScan', 'templateId', 'timestamp']);
});
