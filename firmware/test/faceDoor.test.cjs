const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const root = path.join(__dirname, '..', 'esp8266_fingerprint');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');

function functionSource(file, name) {
  const source = read(file);
  const start = source.search(new RegExp(`\\b(?:inline\\s+)?(?:bool|int|int64_t) ${name}\\(`));
  assert.notEqual(start, -1);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

// Execute actual parser and command control branches, adapting only C++ scalar
// types, pointer arithmetic, Arduino String APIs and fake clock/network I/O.
function adapt(source) {
  return source
    .replace(/^(?:inline\s+)?(?:bool|int|int64_t) (\w+)\(([^)]*)\)\s*\{/, (_, name, parameters) =>
      `function ${name}(${parameters.trim() ? parameters.split(',').map(p => p.match(/(\w+)$/)[1]).join(',') : ''}) {`)
    .replace(/const int (\w+)\[\] = \{([^}]+)\};/g, 'const $1 = [$2];')
    .replace(/struct timeval (\w+);/g, 'let $1 = {};')
    .replace(/gettimeofday\(&(\w+), nullptr\)/g, 'gettimeofday($1, null)')
    .replace(/static_cast<int64_t>\(([^)]+)\)/g, '$1')
    .replace(/\bconst\s+(?:bool|char\s*\*|char|int|size_t|int64_t)\s+/g, 'const ')
    .replace(/\b(?:bool|int|uint16_t|size_t|int64_t|String)\s+(\w+)(?=\s*[=;])/g, 'let $1')
    .replace(/readDeviceCommand\(templateId, type, employeeId, employeeName, wasProcessing, wasCompleted\)/g,
      'readDeviceCommand(value => { templateId = value; }, value => { type = value; }, value => { employeeId = value; }, value => { employeeName = value; }, value => { wasProcessing = value; }, value => { wasCompleted = value; })')
    .replace(/requestId \+ 10/g, 'requestId.slice(10)')
    .replace(/\+ value\[index\] - '0'/g, '+ Number(value[index])')
    .replace(/\b(\d+)LL\b/g, '$1')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '')
    .replace(/\bnullptr\b/g, 'null');
}

function fixture(overrides = {}) {
  const attendanceId = 'a1'.repeat(32);
  const now = Date.parse('2026-10-13T08:00:00.500Z');
  const state = {
    now, clockReady: true, clockCallAllowed: true, elapsedNetworkMs: 0,
    commandRequestId: `face-door-${attendanceId}`, commandSource: 'FACE_ATTENDANCE', commandAttendanceId: attendanceId,
    commandCreatedAt: new Date(now - 500).toISOString(), commandExpiresAt: new Date(now + 14500).toISOString(),
    nextType: 'OPEN_DOOR', processing: false, completed: false,
    pendingCommandExecution: false, pendingCommandResult: false, pendingCommandSuccess: false,
    pendingCommandRestart: false, pendingCommandFailureMessage: '', pendingCommandType: '',
    sensorReady: true, claims: [], finishes: [], opens: 0, error: '',
    COMMAND_RETRY_INTERVAL_MS: 10000, COMMAND_ACTIVE_POLL_INTERVAL_MS: 3000,
    ATTENDANCE_NEXT_RECORD_INTERVAL_MS: 250,
    strlen: s => s.length, strcmp: (a, b) => a === b ? 0 : 1,
    strncmp: (a, b, n) => a.slice(0, n) === b.slice(0, n) ? 0 : 1,
    F: s => s, PSTR: s => s, Serial: { println() {}, printf_P() {} },
    String, millis: () => state.now, hasValidClock: () => state.clockReady,
    gettimeofday(clock) { clock.tv_sec = Math.floor(state.now / 1000); clock.tv_usec = state.now % 1000 * 1000; return state.clockCallAllowed ? 0 : -1; },
    readDeviceCommand(template, type, employee, employeeName, processing, completed) {
      template(0); type(state.nextType); employee(''); employeeName('Nguyen Van A'); processing(state.processing); completed(state.completed); return true;
    },
    updateDeviceCommandStatus(status) { state.claims.push(status); state.now += state.elapsedNetworkMs; return state.claimAllowed !== false; },
    finishDeviceCommand() { state.finishes.push(state.pendingCommandSuccess); state.pendingCommandResult = false; return true; },
    doorNeedsResponsiveLoop: () => false, attendanceOutboxIsEmpty: () => true,
    openDoor() { state.opens++; }, closeDoor() {},
    showAttendanceDoorNotice(name) { state.attendanceNoticeName = name; state.now += state.lcdDelayMs || 0; },
    showLcd(firstLine) { if (firstLine === 'DANG MO CUA') state.now += state.lcdDelayMs || 0; },
    setLatestError(message) { state.error = message; },
    ...overrides,
  };
  const context = vm.createContext(state);
  state.run = source => vm.runInContext(source, context);
  for (const name of ['faceTimestampNumber', 'faceTimestampLeapYear', 'faceTimestampMillis', 'faceDoorCommandMarked', 'faceDoorCommandCanOpen']) {
    state.run(adapt(functionSource('FaceDoorPolicy.h', name)));
  }
  for (const name of ['isSupportedDeviceCommand', 'currentFaceDoorCommandCanOpen', 'rejectFaceDoorCommand', 'checkDeviceCommand']) {
    state.run(adapt(functionSource('60_device_commands.ino', name)));
  }
  return state;
}

test('Firestore UTC parser handles valid calendar dates and fractional precision conservatively', () => {
  const s = fixture();
  for (const timestamp of ['1970-01-01T00:00:00Z', '2024-02-29T23:59:59.1Z', '2026-10-13T08:00:00.12Z',
    '2026-10-13T08:00:00.123Z', '2026-10-13T08:00:00.123456789Z', '2099-12-31T23:59:59Z']) {
    assert.equal(s.faceTimestampMillis(timestamp), Date.parse(timestamp), timestamp);
  }
  for (const value of [null, '', '2025-02-29T00:00:00Z', '2026-04-31T00:00:00Z', '2026-00-01T00:00:00Z',
    '2026-10-13T24:00:00Z', '2026-10-13T08:60:00Z', '2026-10-13T08:00:60Z',
    '2026-10-13T08:00:00.Z', '2026-10-13T08:00:00.XZ', '2026-10-13T08:00:00+07:00',
    '2026-10-13T08:00:00.1234567890Z', '202x-10-13T08:00:00Z']) {
    assert.equal(s.faceTimestampMillis(value), -1, String(value));
  }
});

test('fresh bound face request claims PROCESSING before opening once', () => {
  const s = fixture();
  assert.equal(s.run('checkDeviceCommand()'), true);
  assert.deepEqual(s.claims, ['PROCESSING']);
  assert.equal(s.opens, 1); assert.equal(s.pendingCommandExecution, true);
  assert.equal(s.attendanceNoticeName, 'Nguyen Van A');
  s.run('checkDeviceCommand()'); assert.equal(s.opens, 1);
});

test('expired, malformed, future or mismatched face requests fail without servo movement', () => {
  const base = fixture();
  for (const overrides of [
    { commandExpiresAt: new Date(base.now).toISOString() },
    { commandExpiresAt: 'bad' }, { commandCreatedAt: '' },
    { commandCreatedAt: new Date(base.now + 3001).toISOString(), commandExpiresAt: new Date(base.now + 14000).toISOString() },
    { commandExpiresAt: new Date(base.now + 14501).toISOString() },
    { commandRequestId: `face-door-${'b'.repeat(64)}` },
    { commandAttendanceId: 'A'.repeat(64), commandRequestId: `face-door-${'A'.repeat(64)}` },
    { commandSource: '' }, { nextType: 'CLOSE_DOOR' }, { clockCallAllowed: false },
  ]) {
    const s = fixture(overrides); s.run('checkDeviceCommand()');
    assert.equal(s.opens, 0, JSON.stringify(overrides));
    assert.deepEqual(s.finishes, [false], JSON.stringify(overrides));
    assert.deepEqual(s.claims, [], 'invalid request is not claimed for execution');
  }
});

test('slow processing claim rechecks exact expiry before touching servo', () => {
  for (const elapsedNetworkMs of [14500, 15000, 20000]) {
    const s = fixture({ elapsedNetworkMs }); s.run('checkDeviceCommand()');
    assert.deepEqual(s.claims, ['PROCESSING']); assert.equal(s.opens, 0);
    assert.deepEqual(s.finishes, [false]);
  }
  const s = fixture({ elapsedNetworkMs: 14499 }); s.run('checkDeviceCommand()'); assert.equal(s.opens, 1);
  const slowDisplay = fixture({ elapsedNetworkMs: 14499, lcdDelayMs: 1 });
  slowDisplay.run('checkDeviceCommand()'); assert.equal(slowDisplay.opens, 0, 'expiry checked after display I/O immediately before servo');
});

test('no valid NTP clock and PROCESSING recovery never replay a door request', () => {
  const noClock = fixture({ clockReady: false }); noClock.run('checkDeviceCommand()');
  assert.equal(noClock.opens, 0); assert.deepEqual(noClock.claims, []);
  const restarted = fixture({ processing: true }); restarted.run('checkDeviceCommand()');
  assert.equal(restarted.opens, 0); assert.deepEqual(restarted.claims, []); assert.deepEqual(restarted.finishes, [false]);
  const failedClaim = fixture({ claimAllowed: false }); failedClaim.run('checkDeviceCommand()');
  assert.equal(failedClaim.opens, 0);
});

test('manual admin OPEN_DOOR keeps legacy behavior without face timestamps', () => {
  const s = fixture({ commandSource: '', commandAttendanceId: '', commandRequestId: 'manual-open-1',
    commandCreatedAt: '', commandExpiresAt: '' });
  s.run('checkDeviceCommand()'); assert.equal(s.opens, 1); assert.deepEqual(s.claims, ['PROCESSING']);
});

test('both transport modes advertise TTL support and idle polling fits Spark read budget', () => {
  const source = read('esp8266_fingerprint.ino');
  assert.match(source, /spark-anonymous-v18-face-name-door-ttl/);
  assert.match(source, /spark-gateway-v19-face-name-door-ttl/);
  const interval = Number(source.match(/COMMAND_IDLE_POLL_INTERVAL_MS = (\d+)/)[1]);
  assert.equal(interval, 3000);
  assert.ok(86400000 / interval < 30000);
  assert.match(read('13_servo.ino'), /DOOR_AUTO_CLOSE_DELAY_MS = 5000/);
});
