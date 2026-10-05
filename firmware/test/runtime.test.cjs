const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const sketch = path.join(__dirname, '..', 'esp8266_fingerprint');
const read = name => fs.readFileSync(path.join(sketch, name), 'utf8');

// Execute the actual control functions with fake peripherals/time. This small
// C++-to-JS adapter handles the scalar subset used by those functions; it is not
// a substitute for arduino-cli compile or measuring the physical servo.
function js(source) {
  return source
    .replace(/\b(?:AttendanceDelivery|AttendanceSyncResult|EnrollmentStage)::/g, name => name.replace('::', '.'))
    .replace(/static_cast<uint32_t>\(now - started\)/g, '((now - started) >>> 0)')
    .replace(/\b(?:inline\s+)?(?:bool|void|int|AttendanceSyncResult)\s+(\w+)\(([^)]*)\)\s*\{/g, (_, name, params) =>
      `function ${name}(${params.replace(/\b(?:const\s+)?(?:String\s*&|char\s*\*|bool|uint32_t|int|unsigned long|AttendanceDelivery|AttendanceSyncResult)\s*/g, '')}) {`)
    .replace(/\bconst\s+(?:unsigned long|uint8_t|uint32_t|size_t|bool|int|wl_status_t|AttendanceDelivery|AttendanceSyncResult)\s+/g, 'const ')
    .replace(/\b(?:unsigned long|uint8_t|uint16_t|bool|int|String|File)\s+(\w+)(?=\s*[=;])/g, 'let $1')
    .replace(/DynamicJsonDocument (\w+)\((\d+)\);/g, 'const $1 = {};')
    .replace(/(record\["\w+"\]) \| ""/g, "($1 ?? '')")
    .replace(/line.trim\(\);/g, 'line = line.trim();')
    // Bind the resolver's C++ String& output at this fake network boundary.
    .replace(/preparationCode = resolveOfflineAttendancePayload\(payload, resolvedPayload\);/g,
      '({code: preparationCode, payload: resolvedPayload} = resolveOfflineAttendancePayload(payload));')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '');
}

function functionSource(file, name) {
  const source = read(file);
  const start = source.search(new RegExp(`\\b(?:bool|void|int|AttendanceSyncResult) ${name}\\(`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

function fixture() {
  const state = {
    now: 0, doorOpen: false, doorMoving: false, doorOpenedAt: 0, doorStatus: 'CLOSED',
    fingerprintDoorNoticeActive: false, fingerprintDoorNoticeOffline: false, fingerprintDoorNoticeAttendanceSaved: true,
    pendingCommandExecution: false,
    pendingCommandType: '', pendingCommandResult: false, pendingCommandSuccess: false,
    pendingCommandStartedAt: 0, lastCommandCheck: 0, COMMAND_ACTIVE_POLL_INTERVAL_MS: 3000,
    SYNC_COMMAND_TIMEOUT_MS: 180000, FOREGROUND_ATTENDANCE_VALIDITY_MS: 15000,
    foregroundAttendanceEventId: '', foregroundAttendanceEmployeeName: 'Employee',
    foregroundAttendanceHandled: true, foregroundAttendanceCreatedAt: 0,
    foregroundAttendanceDelivery: 'NOT_STORED',
    OFFLINE_AS608_ACCESS_ENABLED: true, lastAttendanceCreatedOffline: false, lastAttendanceMissingClock: false,
    validClock: true, hasValidClock() { return state.validClock; },
    lastFingerprintAuthorizationDenied: false, sensorReady: true,
    EnrollmentStage: Object.fromEntries(['IDLE', 'FIRST_IMAGE', 'FIRST_CONVERSION', 'REMOVE_FINGER', 'SECOND_GAP', 'SECOND_IMAGE', 'SECOND_CONVERSION', 'CREATE_MODEL', 'STORE_MODEL'].map(x => [x, x])),
    AttendanceDelivery: Object.fromEntries(['NOT_STORED', 'QUEUED', 'CONFIRMED', 'REJECTED', 'LOCAL_ACCEPTED', 'ACCESS_ONLY'].map(x => [x, x])),
    AttendanceSyncResult: Object.fromEntries(['EMPTY', 'DEFERRED', 'RETRY', 'ACKNOWLEDGED', 'REJECTED', 'STORAGE_ERROR'].map(x => [x, x])),
    Serial: { printf() {}, println() {} }, servoWrites: [], displays: [], signals: [],
    millis() { return state.now >>> 0; }, constrain: (value, min, max) => Math.min(max, Math.max(min, value)),
    doorServo: { write(angle) { state.servoWrites.push([state.now, angle]); }, attach() {} },
    showLcd(...lines) { state.displays.push(lines); }, renderLcd() {}, showReadyScreen() {},
    showFingerprintResultNotice(...lines) { state.displays.push(lines); },
    signalResult(ok) { state.signals.push(ok); }, setLatestError(message) { state.error = message; },
    queueBytes: 0, attendanceOutboxBytes() { return state.queueBytes; },
    littleFsReady: true, attendanceOutboxIsEmpty() { return state.littleFsReady && state.queueBytes === 0; },
    WL_CONNECTED: 3, wifiStatus: 3, WiFi: { status() { return state.wifiStatus; } },
    hasHttpsTransportFailure: false, lastHttpsTransportFailure: 0, HTTPS_RETRY_COOLDOWN_MS: 30000,
    FINGERPRINT_OK: 0, FINGERPRINT_NOFINGER: 2, FINGERPRINT_PACKETRECIEVEERR: 1,
    enrollmentStage: 'IDLE', enrollmentTemplateId: 1, enrollmentStageStartedAt: 0,
    enrollmentLastServiceAt: 0, enrollmentLastPollAt: 0,
    sensorCalls: 0, imageResult: 2,
    finger: { getImage() { state.sensorCalls++; return state.imageResult; }, image2Tz() { state.sensorCalls++; return 0; }, createModel() { state.sensorCalls++; return 0; }, storeModel() { state.sensorCalls++; return 0; } },
    playBuzzerTone() {},
  };
  const context = vm.createContext(state);
  const evaluate = source => vm.runInContext(source, context);
  const policies = read('RuntimePolicy.h').split('\n').filter(line => !line.startsWith('#') && !line.startsWith('enum class')).join('\n');
  evaluate(js(policies));
  evaluate(js(read('13_servo.ino').replace('Servo doorServo;', '')));
  for (const [file, names] of [
    ['10_helpers.ino', ['httpsRetryCooldownActive']],
    ['40_attendance_service.ino', ['invalidateForegroundAttendanceAccess', 'handleAttendanceDelivery', 'expireForegroundAttendance']],
    ['60_device_commands.ino', ['serviceDeviceCommandExecution']],
    ['12_as608.ino', ['finishEnrollment', 'serviceEnrollment']],
  ]) for (const name of names) evaluate(js(functionSource(file, name)));
  state.tick = milliseconds => { state.now += milliseconds; evaluate('serviceDoor(); serviceEnrollment(); serviceDeviceCommandExecution();'); };
  state.run = evaluate;
  return state;
}

function fingerprintFixture() {
  const s = fixture();
  Object.assign(s, {
    FINGERPRINT_RESULT_HOLD_MS: Number(read('11_lcd.ino').match(/FINGERPRINT_RESULT_HOLD_MS = (\d+)/)[1]),
    fingerprintResultNoticeActive: false, fingerprintResultNoticeStartedAt: 0,
    lcdIdleMode: false, waitingForFingerRemoval: false, fingerRemovalStarted: 0,
    FINGERPRINT_NOTFOUND: 9, FINGERPRINT_IMAGEMESS: 6,
    FINGERPRINT_IMAGEFAIL: 3, FINGERPRINT_NOMATCH: 8, FINGERPRINT_FEATUREFAIL: 7, FINGERPRINT_INVALIDIMAGE: 21,
    FINGERPRINT_POLL_INTERVAL_MS: Number(read('12_as608.ino').match(/FINGERPRINT_POLL_INTERVAL_MS = (\d+)/)[1]),
    lastFingerprintPollAt: -80,
    imageResult: 0, conversionResult: 0, searchResult: 0,
    lastFingerprintAuthorizationUnavailable: false, outboxFull: false,
    uploadResult: s.AttendanceDelivery.QUEUED,
    renderLcd(...lines) { s.displays.push(lines); },
    showIdleScreen() { s.lcdIdleMode = true; s.renderLcd('READY', 'DAT NGON TAY...'); },
    attendanceOutboxIsFull() { return s.outboxFull; },
    uploadAttendance() {
      s.invalidateForegroundAttendanceAccess();
      if (s.uploadResult === s.AttendanceDelivery.QUEUED) {
        // The fake upload boundary supplies the in-RAM context established by
        // production after the immutable event has been saved successfully.
        s.foregroundAttendanceEventId = 'current';
        s.foregroundAttendanceEmployeeName = s.lastAttendanceCreatedOffline ? 'VAN TAY #1' : 'Employee';
        s.foregroundAttendanceCreatedAt = s.now;
        s.foregroundAttendanceHandled = false;
        s.foregroundAttendanceDelivery = s.AttendanceDelivery.QUEUED;
        s.attendancePendingSync = true;
        s.queueBytes += 560;
      }
      return s.uploadResult;
    },
    recordFailedScan() { s.failedScans = (s.failedScans || 0) + 1; },
  });
  s.finger.image2Tz = () => { s.sensorCalls++; return s.conversionResult; };
  s.finger.fingerFastSearch = () => { s.sensorCalls++; return s.searchResult; };
  s.finger.fingerID = 1; s.finger.confidence = 100;
  for (const [file, names] of [
    ['11_lcd.ino', ['showLcd', 'fingerprintResultHoldActive', 'showFingerprintResultNotice', 'serviceFingerprintResultNotice', 'showReadyScreen', 'showSensorReconnectScreen']],
    ['12_as608.ino', ['fingerprintPollDue', 'setSensorError', 'markSensorReady', 'startWaitingForFingerRemoval', 'handleFingerprintRemoval', 'handleFingerprintScan']],
  ]) for (const name of names) s.run(js(functionSource(file, name)));
  return s;
}

function fingerprintLoopFixture() {
  const s = fingerprintFixture();
  Object.assign(s, {
    physicalCalls: [], ioCalls: [], delays: [],
    outputEffectStartedAt: 0, outputEffectDurationMs: 1300,
    LED_GREEN_PIN: 1, LED_RED_PIN: 2, ledWrites: [],
    setLed(pin, enabled) { s.ledWrites.push([pin, enabled]); },
    handleDoorSwitch() { s.physicalCalls.push('switch'); },
    maintainWifiConnection() { s.ioCalls.push('wifi'); },
    maybeRecoverSensor() { s.ioCalls.push('sensor-recovery'); },
    maybeUpdateIdleClock() { s.ioCalls.push('idle-clock'); },
    checkDeviceCommand() { s.ioCalls.push('command-poll'); return false; },
    maybePublishDeviceSnapshot() { s.ioCalls.push('snapshot'); },
    flushAttendanceOutbox() { s.ioCalls.push('flush'); return s.AttendanceSyncResult.EMPTY; },
    delay(milliseconds) { s.delays.push(milliseconds); },
    lastAttendanceSync: 0, attendanceSyncIntervalMs: 250,
    ATTENDANCE_NEXT_RECORD_INTERVAL_MS: 250, ATTENDANCE_RETRY_INTERVAL_MS: 30000,
    commandPollIntervalMs: 15000,
  });
  s.run(js(functionSource('14_outputs_switch.ino', 'serviceOutputEffects')));
  for (const name of ['serviceDoor', 'serviceOutputEffects']) {
    const actual = s[name];
    s[name] = () => { s.physicalCalls.push(name); return actual(); };
  }
  for (const name of ['expireForegroundAttendance', 'serviceEnrollment', 'serviceDeviceCommandExecution']) {
    const actual = s[name];
    s[name] = () => { s.ioCalls.push(name); return actual(); };
  }
  s.run(js(functionSource('90_main.ino', 'loop')));
  return s;
}

function wifiLoopFixture() {
  const s = fingerprintLoopFixture();
  Object.assign(s, {
    wifiWasConnected: true, lastWifiReconnectAttempt: 0, WIFI_RECONNECT_INTERVAL_MS: 15000,
    MIN_VALID_UNIX_TIME: 1700000000, nullptr: null, time: () => 1791072600,
    configTime() {}, WIFI_STA: 1, WIFI_SSID: 'fixture', WIFI_PASSWORD: 'fixture',
    wifiBegins: 0,
  });
  s.WiFi.mode = () => {};
  s.WiFi.begin = () => { s.wifiBegins++; };
  s.run(js(functionSource('10_helpers.ino', 'maintainWifiConnection')));
  return s;
}

test('wrong fingerprint stays readable for 1s after immediate finger lift and blocks another scan', () => {
  const s = fingerprintFixture(); s.searchResult = s.FINGERPRINT_NOTFOUND;
  s.run('handleFingerprintScan()');
  assert.deepEqual(s.displays.at(-1), ['VAN TAY SAI', 'XIN THU LAI']);
  assert.equal(s.failedScans, 1); assert.deepEqual(s.signals, [false]);
  const sensorCalls = s.sensorCalls;
  s.imageResult = s.FINGERPRINT_NOFINGER;
  for (const now of [0, 999]) {
    s.now = now;
    s.run('showReadyScreen(); serviceFingerprintResultNotice(); handleFingerprintRemoval(); handleFingerprintScan();');
    assert.equal(s.waitingForFingerRemoval, true); assert.equal(s.lcdIdleMode, false);
    assert.deepEqual(s.displays.at(-1), ['VAN TAY SAI', 'XIN THU LAI']);
    assert.equal(s.sensorCalls, sensorCalls);
  }
  s.now = 1000;
  s.run('serviceFingerprintResultNotice(); handleFingerprintRemoval();');
  assert.equal(s.waitingForFingerRemoval, false); assert.equal(s.lcdIdleMode, true);
  assert.deepEqual(s.displays.at(-1), ['READY', 'DAT NGON TAY...']);
  assert.equal(s.doorStatus, 'CLOSED');
});

test('denied employee and other scan failures hold their final message for 1s without opening', () => {
  const cases = [
    { configure: s => { s.lastFingerprintAuthorizationDenied = true; s.uploadResult = s.AttendanceDelivery.REJECTED; }, lines: ['KHONG DUOC PHEP', 'XIN LIEN HE ADMIN'] },
    { configure: s => { s.lastFingerprintAuthorizationUnavailable = true; s.uploadResult = s.AttendanceDelivery.NOT_STORED; }, lines: ['KHONG XAC THUC', 'KHONG MO CUA'] },
    { configure: s => { s.outboxFull = true; s.uploadResult = s.AttendanceDelivery.NOT_STORED; }, lines: ['HANG DOI DAY', 'KHONG LUU DUOC'] },
    { configure: s => { s.uploadResult = s.AttendanceDelivery.NOT_STORED; }, lines: ['KHONG LUU DUOC', 'XIN THU LAI'] },
    { configure: s => { s.searchResult = s.FINGERPRINT_PACKETRECIEVEERR; }, lines: ['VAN TAY SAI', 'XIN THU LAI'] },
  ];
  for (const { configure, lines } of cases) {
    const s = fingerprintFixture(); configure(s);
    s.run('handleFingerprintScan()');
    assert.deepEqual(s.displays.at(-1), lines); assert.deepEqual(s.signals, [false]);
    s.imageResult = s.FINGERPRINT_NOFINGER; s.now = 999;
    s.run('serviceFingerprintResultNotice(); handleFingerprintRemoval();');
    assert.deepEqual(s.displays.at(-1), lines); assert.equal(s.doorStatus, 'CLOSED');
    s.now = 1000;
    s.run('serviceFingerprintResultNotice(); handleFingerprintRemoval();');
    assert.equal(s.fingerprintResultNoticeActive, false);
    assert.equal(s.waitingForFingerRemoval, false);
    assert.notDeepEqual(s.displays.at(-1), lines);
  }
});

test('async attendance rejection after finger lift holds 1s and then restores readiness', () => {
  const s = fingerprintFixture(); s.now = 500;
  s.foregroundAttendanceEventId = 'rejected'; s.foregroundAttendanceHandled = false;
  s.run("handleAttendanceDelivery('rejected', AttendanceDelivery.REJECTED)");
  assert.deepEqual(s.displays.at(-1), ['LUOT BI TU CHOI', 'XIN LIEN HE ADMIN']);
  s.now = 1499; s.run('serviceFingerprintResultNotice(); showReadyScreen();');
  assert.equal(s.lcdIdleMode, false); assert.equal(s.doorStatus, 'CLOSED');
  s.now = 1500; s.run('serviceFingerprintResultNotice();');
  assert.equal(s.fingerprintResultNoticeActive, false); assert.equal(s.lcdIdleMode, true);
  assert.deepEqual(s.displays.at(-1), ['READY', 'DAT NGON TAY...']);
});

test('fingerprint result hold expires correctly across millis rollover', () => {
  const s = fingerprintFixture(); s.now = 0xfffffff0;
  s.run("showFingerprintResultNotice('VAN TAY SAI', 'XIN THU LAI')");
  s.now += 999; s.run('serviceFingerprintResultNotice(); showReadyScreen();');
  assert.equal(s.run('fingerprintResultHoldActive()'), true); assert.equal(s.lcdIdleMode, false);
  s.now++; s.run('serviceFingerprintResultNotice();');
  assert.equal(s.run('fingerprintResultHoldActive()'), false); assert.equal(s.lcdIdleMode, true);
});

test('active result hold keeps physical services running and defers background I/O', () => {
  const s = fingerprintLoopFixture(); s.run('openDoor()'); s.now = 1299;
  s.run("showFingerprintResultNotice('VAN TAY SAI', 'XIN THU LAI')");
  for (const now of [1300, 1310]) { s.now = now; s.run('loop()'); }
  assert.deepEqual(s.physicalCalls, ['switch', 'serviceDoor', 'serviceOutputEffects', 'switch', 'serviceDoor', 'serviceOutputEffects']);
  assert.equal(s.servoWrites.length, 2); assert.equal(s.doorStatus, 'OPENING');
  assert.deepEqual(s.ledWrites, [[1, false], [2, false]]);
  assert.deepEqual(s.ioCalls, []); assert.equal(s.sensorCalls, 0);
  assert.deepEqual(s.delays, [5, 5]);
  assert.deepEqual(s.displays.at(-1), ['VAN TAY SAI', 'XIN THU LAI']);
});

test('rejection during outbox flush holds its message before command polling or finger removal', () => {
  const s = fingerprintLoopFixture(); s.now = 250; s.queueBytes = 1;
  s.foregroundAttendanceEventId = 'rejected'; s.foregroundAttendanceHandled = false;
  s.waitingForFingerRemoval = true; s.imageResult = s.FINGERPRINT_NOFINGER;
  s.pendingCommandResult = true; s.commandPollIntervalMs = 0;
  s.flushAttendanceOutbox = () => {
    s.ioCalls.push('flush'); s.queueBytes = 0;
    s.run("handleAttendanceDelivery('rejected', AttendanceDelivery.REJECTED)");
    return s.AttendanceSyncResult.REJECTED;
  };
  s.run('loop()');
  assert.equal(s.run('fingerprintResultHoldActive()'), true);
  assert.deepEqual(s.displays.at(-1), ['LUOT BI TU CHOI', 'XIN LIEN HE ADMIN']);
  assert.equal(s.waitingForFingerRemoval, true); assert.equal(s.sensorCalls, 0);
  assert.equal(s.ioCalls.includes('command-poll'), false);
  assert.equal(s.ioCalls.at(-1), 'flush');
});

test('queued scan stays pending after finger lift; confirmed current scan shows door opening', () => {
  const s = fingerprintFixture(); s.run('handleFingerprintScan()');
  assert.equal(s.displays.at(-1)[1], 'DANG XU LY'); assert.equal(s.doorStatus, 'CLOSED');
  assert.equal(s.foregroundAttendanceEventId, 'current'); assert.equal(s.foregroundAttendanceHandled, false);
  assert.equal(s.waitingForFingerRemoval, true); assert.deepEqual(s.signals, []);
  s.imageResult = s.FINGERPRINT_NOFINGER; s.now = 100;
  s.run('handleFingerprintRemoval();');
  assert.equal(s.waitingForFingerRemoval, false); assert.equal(s.foregroundAttendanceHandled, false);
  assert.deepEqual(s.displays.at(-1), ['Employee', 'DANG XU LY']);
  assert.equal(s.lcdIdleMode, false); assert.equal(s.doorStatus, 'CLOSED'); assert.equal(s.doorMoving, false);
  s.run("handleAttendanceDelivery('current', AttendanceDelivery.CONFIRMED)");
  assert.deepEqual(s.displays.at(-1), ['Employee', 'DANG MO CUA']);
  assert.equal(s.doorStatus, 'OPENING'); assert.deepEqual(s.signals, [true]);
  assert.equal(s.fingerprintDoorNoticeActive, true);
});

test('one-second scan notice cannot postpone the five-second auto-close deadline', () => {
  const s = fingerprintLoopFixture(); s.now = 4999;
  s.doorOpen = true; s.doorStatus = 'OPEN'; s.doorOpenedAt = 0;
  s.run('doorCurrentAngle = 0; doorTargetAngle = 0;');
  s.run("showFingerprintResultNotice('VAN TAY SAI', 'XIN THU LAI')");
  s.run('loop();'); assert.equal(s.doorStatus, 'OPEN');
  s.now = 5000; s.run('loop();');
  assert.equal(s.doorStatus, 'CLOSING'); assert.equal(s.run('fingerprintResultHoldActive()'), true);
  s.now = 5010; s.run('loop();');
  assert.equal(s.servoWrites.length, 1); assert.deepEqual(s.ioCalls, []); assert.equal(s.sensorCalls, 0);
  assert.deepEqual(s.displays.at(-1), ['VAN TAY SAI', 'XIN THU LAI']);
});

test('background SYNC execution keeps one-head synchronization and normal fingerprint scanning active', () => {
  const s = fingerprintLoopFixture(); s.now = 250; s.queueBytes = 1024;
  s.pendingCommandType = 'SYNC_ATTENDANCE'; s.pendingCommandExecution = true;
  s.flushAttendanceOutbox = () => { s.ioCalls.push('flush'); return s.AttendanceSyncResult.RETRY; };
  s.run('loop();');
  assert.equal(s.sensorCalls, 3); assert.equal(s.waitingForFingerRemoval, true);
  assert.equal(s.foregroundAttendanceDelivery, 'QUEUED'); assert.equal(s.doorStatus, 'CLOSED');
  assert.equal(s.pendingCommandExecution, true); assert.equal(s.pendingCommandResult, false);
  assert.equal(s.ioCalls.filter(value => value === 'flush').length, 1);
  assert.equal(s.ioCalls.includes('command-poll'), false);
});

test('retrying a background SYNC completion report no longer locks the scanner', () => {
  const s = fingerprintLoopFixture(); s.now = 20000;
  s.pendingCommandType = 'SYNC_ATTENDANCE'; s.pendingCommandResult = true;
  s.checkDeviceCommand = () => { s.ioCalls.push('command-poll'); return true; };
  s.run('loop();');
  assert.equal(s.ioCalls.filter(value => value === 'command-poll').length, 1);
  assert.equal(s.sensorCalls, 3); assert.equal(s.pendingCommandResult, true);
  assert.equal(s.foregroundAttendanceHandled, false); assert.equal(s.doorStatus, 'CLOSED');
  assert.equal(s.displays.at(-1)[1], 'DANG XU LY');
});

test('newly queued scan re-arms an empty SYNC before it can report COMPLETED', () => {
  const s = fingerprintLoopFixture(); s.now = 20000; s.queueBytes = 560;
  s.pendingCommandType = 'SYNC_ATTENDANCE'; s.pendingCommandResult = true;
  s.pendingCommandSuccess = true; s.pendingCommandStartedAt = 1234;
  s.flushAttendanceOutbox = () => { s.ioCalls.push('flush'); return s.AttendanceSyncResult.RETRY; };
  s.run('loop();');
  assert.equal(s.pendingCommandExecution, true); assert.equal(s.pendingCommandResult, false);
  assert.equal(s.pendingCommandStartedAt, 1234); assert.equal(s.ioCalls.includes('command-poll'), false);
  assert.equal(s.sensorCalls, 3); assert.equal(s.queueBytes > 0, true);
});

test('background SYNC reporting preserves active scan, finger removal, rejection and door screens', () => {
  for (const mode of ['pending scan', 'finger removal', 'rejection notice', 'opening door']) {
    const s = fingerprintLoopFixture(); s.now = 20000; s.imageResult = s.FINGERPRINT_NOFINGER;
    s.pendingCommandType = 'SYNC_ATTENDANCE'; s.pendingCommandResult = true;
    s.checkDeviceCommand = () => { s.ioCalls.push('command-poll'); s.run("showLcd('SYNC RESULT', 'SHOULD WAIT')"); return true; };
    if (mode === 'pending scan') {
      s.foregroundAttendanceCreatedAt = s.now; s.foregroundAttendanceHandled = false;
      s.run('showReadyScreen();');
    } else if (mode === 'finger removal') {
      s.waitingForFingerRemoval = true;
    } else if (mode === 'rejection notice') {
      s.run("showFingerprintResultNotice('LUOT BI TU CHOI', 'XIN LIEN HE ADMIN')");
    } else {
      s.run("showLcd('Employee', 'DANG MO CUA'); openDoor();");
    }
    const previousDisplay = s.displays.at(-1);
    s.run('loop();');
    assert.equal(s.ioCalls.includes('command-poll'), false, mode);
    if (mode !== 'finger removal') assert.deepEqual(s.displays.at(-1), previousDisplay, mode);
    assert.equal(s.pendingCommandResult, true, mode);
  }
});

test('cooldown deferral preserves an already due FIFO head and resumes at the expiry without an extra 30s', () => {
  const s = fingerprintLoopFixture(); s.imageResult = s.FINGERPRINT_NOFINGER;
  s.queueBytes = 2; s.now = 1000; s.lastAttendanceSync = 123; s.attendanceSyncIntervalMs = 250;
  s.flushAttendanceOutbox = () => {
    s.ioCalls.push('flush'); s.hasHttpsTransportFailure = true; s.lastHttpsTransportFailure = s.now;
    return s.AttendanceSyncResult.DEFERRED;
  };
  s.run('loop();');
  assert.equal(s.lastAttendanceSync, 123); assert.equal(s.attendanceSyncIntervalMs, 250);
  assert.equal(s.ioCalls.filter(x => x === 'flush').length, 1);
  for (const now of [1005, 29999, 30999]) { s.now = now; s.run('loop();'); }
  assert.equal(s.ioCalls.filter(x => x === 'flush').length, 1);
  assert.equal(s.ioCalls.includes('command-poll'), false);
  s.flushAttendanceOutbox = () => { s.ioCalls.push('flush'); s.queueBytes--; return s.AttendanceSyncResult.ACKNOWLEDGED; };
  s.now = 31000; s.run('loop();');
  assert.equal(s.queueBytes, 1); assert.equal(s.lastAttendanceSync, 31000); assert.equal(s.attendanceSyncIntervalMs, 250);
  s.now = 31249; s.run('loop();'); assert.equal(s.queueBytes, 1);
  s.now = 31250; s.run('loop();'); assert.equal(s.queueBytes, 0);
});

test('restored Wi-Fi clears an old cooldown once, immediately drains both FIFO heads and preserves live-link backoff', () => {
  const s = wifiLoopFixture(); s.imageResult = s.FINGERPRINT_NOFINGER;
  s.pendingHeads = [69, 70]; s.queueBytes = 2;
  s.hasHttpsTransportFailure = true; s.lastHttpsTransportFailure = 1000;
  s.lastAttendanceSync = 1000; s.attendanceSyncIntervalMs = 30000;
  s.now = 1500; s.wifiStatus = 6; s.run('loop();');
  assert.equal(s.wifiWasConnected, false); assert.equal(s.hasHttpsTransportFailure, true);
  assert.equal(s.ioCalls.includes('flush'), false);
  const sent = [];
  s.flushAttendanceOutbox = () => {
    sent.push(s.pendingHeads.shift()); s.queueBytes = s.pendingHeads.length;
    return s.AttendanceSyncResult.ACKNOWLEDGED;
  };
  s.now = 2000; s.wifiStatus = s.WL_CONNECTED; s.run('loop();');
  assert.deepEqual(sent, [69]); assert.equal(s.hasHttpsTransportFailure, false);
  assert.equal(s.ioCalls.includes('command-poll'), false);
  s.now = 2249; s.run('loop();'); assert.deepEqual(sent, [69]);
  s.now = 2250; s.run('loop();'); assert.deepEqual(sent, [69, 70]);
  s.queueBytes = 1; s.hasHttpsTransportFailure = true; s.lastHttpsTransportFailure = 2250;
  s.now = 2255; s.run('loop();');
  assert.equal(s.hasHttpsTransportFailure, true); assert.deepEqual(sent, [69, 70]);
});

test('pending attendance wins over a due new command GET when its transport cooldown ends', () => {
  const s = fingerprintLoopFixture(); s.imageResult = s.FINGERPRINT_NOFINGER;
  s.queueBytes = 2; s.hasHttpsTransportFailure = true; s.lastHttpsTransportFailure = 0;
  const operations = [];
  s.flushAttendanceOutbox = () => { operations.push('head'); s.queueBytes--; return s.AttendanceSyncResult.ACKNOWLEDGED; };
  s.checkDeviceCommand = () => { operations.push('command'); return false; };
  s.now = 29999; s.run('loop();'); assert.deepEqual(operations, []);
  s.now = 30000; s.run('loop();'); assert.deepEqual(operations, ['head']);
  s.now = 30250; s.run('loop();'); assert.deepEqual(operations, ['head', 'head', 'command']);
});

test('failed SYNC completion report restores the actual empty queue LCD instead of stale attendance pending', () => {
  const s = fingerprintFixture(); s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.pendingCommandResult = true; s.pendingCommandSuccess = true;
  s.syncCommandHadRejections = false;
  s.COMMAND_RETRY_INTERVAL_MS = 10000; s.lastLcdClock = 0;
  s.attendancePendingCount = () => s.queueBytes;
  s.vietnamTimeText = () => 'CLOCK';
  s.updateDeviceCommandStatus = () => false;
  s.commandMessages = []; s.Serial.println = value => s.commandMessages.push(value);
  s.run(js(functionSource('11_lcd.ino', 'showIdleScreen')));
  s.run(js(functionSource('60_device_commands.ino', 'finishDeviceCommand')));
  s.run("showLcd('CHO DONG BO', 'KIEM TRA MANG'); finishDeviceCommand();");
  assert.equal(s.pendingCommandResult, true); assert.equal(s.commandPollIntervalMs, 10000);
  assert.equal(s.lcdIdleMode, true); assert.deepEqual(s.displays.at(-1), ['CLOCK', 'DAT NGON TAY...']);
  assert.deepEqual(s.commandMessages, ['CHO GUI KET QUA LENH: SYNC_ATTENDANCE']);
  s.queueBytes = 1; s.run('finishDeviceCommand();');
  assert.deepEqual(s.displays.at(-1), ['CLOCK', 'CHO DONG BO: 1']);
  for (const mode of ['pending scan', 'finger removal', 'rejection']) {
    s.queueBytes = 0; s.foregroundAttendanceHandled = mode !== 'pending scan';
    s.waitingForFingerRemoval = mode === 'finger removal';
    s.run("showLcd('CURRENT RESULT', 'KEEP VISIBLE');");
    s.fingerprintResultNoticeActive = mode === 'rejection'; s.fingerprintResultNoticeStartedAt = s.now;
    const count = s.displays.length; s.run('finishDeviceCommand();');
    assert.equal(s.displays.length, count, mode);
  }
});

test('sensor-exclusive commands retain their scan lock while SYNC becomes background work', () => {
  for (const type of ['ENROLL_FINGERPRINT', 'DELETE_FINGERPRINT']) {
    for (const phase of ['execution', 'completion report']) {
      const s = fingerprintLoopFixture(); s.now = 1000; s.pendingCommandType = type;
      s.pendingCommandExecution = phase === 'execution'; s.pendingCommandResult = phase === 'completion report';
      s.run('loop();'); assert.equal(s.sensorCalls, 0, `${type}: ${phase}`);
    }
  }
});

test('the shared 80ms sensor throttle applies to both scanning and finger removal', () => {
  const s = fingerprintFixture(); s.imageResult = s.FINGERPRINT_NOFINGER;
  s.run('handleFingerprintScan();'); assert.equal(s.sensorCalls, 1);
  s.now = 79; s.run('handleFingerprintScan();'); assert.equal(s.sensorCalls, 1);
  s.now = 80; s.run('handleFingerprintScan();'); assert.equal(s.sensorCalls, 2);
  s.waitingForFingerRemoval = true;
  s.now = 159; s.run('handleFingerprintRemoval();'); assert.equal(s.sensorCalls, 2);
  s.now = 160; s.run('handleFingerprintRemoval(); handleFingerprintScan();');
  assert.equal(s.sensorCalls, 3); assert.equal(s.waitingForFingerRemoval, false);
});

test('image capture failure stays retryable and does not disable a responsive AS608', () => {
  const s = fingerprintFixture(); s.imageResult = s.FINGERPRINT_IMAGEFAIL;
  s.run('handleFingerprintScan();');
  assert.equal(s.sensorReady, true); assert.equal(s.waitingForFingerRemoval, true);
  assert.deepEqual(s.displays.at(-1), ['KHONG DOC DUOC', 'DAT LAI NGON TAY']);
  s.now = 1000; s.run('serviceFingerprintResultNotice(); handleFingerprintRemoval();');
  assert.equal(s.sensorReady, true); assert.equal(s.waitingForFingerRemoval, true);
  s.now = 1080; s.imageResult = s.FINGERPRINT_NOFINGER; s.run('handleFingerprintRemoval();');
  assert.equal(s.sensorReady, true); assert.equal(s.waitingForFingerRemoval, false);
});

test('bad fingerprint feature/image and no-match codes do not become hardware disconnections', () => {
  for (const [phase, code] of [['conversion', 6], ['conversion', 7], ['conversion', 21], ['search', 8], ['search', 9]]) {
    const s = fingerprintFixture();
    if (phase === 'conversion') s.conversionResult = code; else s.searchResult = code;
    s.run('handleFingerprintScan();');
    assert.equal(s.sensorReady, true, `${phase}: ${code}`); assert.equal(s.failedScans, 1);
    assert.deepEqual(s.displays.at(-1), ['VAN TAY SAI', 'XIN THU LAI']); assert.equal(s.doorStatus, 'CLOSED');
  }
});

test('UART packet error disables scanning until recovery then permits an immediate poll', () => {
  const s = fingerprintFixture(); s.imageResult = s.FINGERPRINT_PACKETRECIEVEERR;
  s.run('handleFingerprintScan();'); assert.equal(s.sensorReady, false); assert.equal(s.sensorCalls, 1);
  s.now = 100; s.imageResult = s.FINGERPRINT_NOFINGER; s.run('handleFingerprintScan();');
  assert.equal(s.sensorCalls, 1);
  s.run('markSensorReady(); handleFingerprintScan();');
  assert.equal(s.sensorReady, true); assert.equal(s.sensorCalls, 2);
});

test('a failed new scan cancels old door access without losing its saved event', () => {
  const cases = [
    s => { s.imageResult = s.FINGERPRINT_IMAGEFAIL; },
    s => { s.conversionResult = s.FINGERPRINT_FEATUREFAIL; },
    s => { s.searchResult = s.FINGERPRINT_NOTFOUND; },
    s => { s.lastFingerprintAuthorizationDenied = true; s.uploadResult = s.AttendanceDelivery.REJECTED; },
    s => { s.uploadResult = s.AttendanceDelivery.NOT_STORED; },
    s => { s.outboxFull = true; s.uploadResult = s.AttendanceDelivery.NOT_STORED; },
    s => { s.validClock = false; s.uploadResult = s.AttendanceDelivery.NOT_STORED; },
  ];
  for (const configure of cases) {
    const s = fingerprintFixture();
    s.foregroundAttendanceEventId = 'previous'; s.foregroundAttendanceHandled = false;
    s.foregroundAttendanceEmployeeName = 'Previous employee';
    s.foregroundAttendanceDelivery = s.AttendanceDelivery.QUEUED; s.queueBytes = 560;
    configure(s); s.run('handleFingerprintScan();');
    assert.equal(s.foregroundAttendanceEventId, ''); assert.equal(s.foregroundAttendanceEmployeeName, '');
    assert.equal(s.foregroundAttendanceHandled, true); assert.equal(s.foregroundAttendanceDelivery, 'NOT_STORED');
    assert.equal(s.queueBytes, 560); assert.deepEqual(s.signals, [false]);
    const failureNotice = s.displays.at(-1);
    // A previous response can arrive while the new failure notice is held.
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.CONFIRMED);");
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.LOCAL_ACCEPTED);");
    assert.deepEqual(s.displays.at(-1), failureNotice); assert.equal(s.doorStatus, 'CLOSED');
    s.now = 1000; s.imageResult = s.FINGERPRINT_NOFINGER;
    s.run('serviceFingerprintResultNotice(); handleFingerprintRemoval();');
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.CONFIRMED);");
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.LOCAL_ACCEPTED);");
    assert.equal(s.doorStatus, 'CLOSED'); assert.deepEqual(s.signals, [false]);
    assert.equal(s.queueBytes, 560);
  }
});

test('no finger or unreadable UART packet does not cancel a saved current scan', () => {
  for (const imageStatus of [2, 1]) {
    const s = fingerprintFixture(); s.imageResult = imageStatus;
    s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
    s.foregroundAttendanceEmployeeName = 'Current employee';
    s.foregroundAttendanceDelivery = s.AttendanceDelivery.QUEUED; s.queueBytes = 560;
    s.run('handleFingerprintScan();');
    assert.equal(s.foregroundAttendanceEventId, 'current'); assert.equal(s.foregroundAttendanceHandled, false);
    assert.equal(s.foregroundAttendanceEmployeeName, 'Current employee');
    assert.equal(s.foregroundAttendanceDelivery, 'QUEUED'); assert.equal(s.queueBytes, 560);
    assert.equal(s.doorStatus, 'CLOSED'); assert.deepEqual(s.signals, []);
  }
});

test('a saved new scan replaces old door access but preserves both queued events', () => {
  for (const delivery of ['CONFIRMED', 'LOCAL_ACCEPTED']) {
    const s = fingerprintFixture();
    s.foregroundAttendanceEventId = 'previous'; s.foregroundAttendanceHandled = false;
    s.foregroundAttendanceEmployeeName = 'Previous employee'; s.queueBytes = 560;
    s.run('handleFingerprintScan();');
    assert.equal(s.foregroundAttendanceEventId, 'current'); assert.equal(s.queueBytes, 1120);
    assert.equal(s.foregroundAttendanceHandled, false); assert.equal(s.doorStatus, 'CLOSED');
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.CONFIRMED);");
    s.run("handleAttendanceDelivery('previous', AttendanceDelivery.LOCAL_ACCEPTED);");
    assert.equal(s.foregroundAttendanceHandled, false); assert.equal(s.doorStatus, 'CLOSED');
    s.run(`handleAttendanceDelivery('current', AttendanceDelivery.${delivery});`);
    assert.equal(s.doorStatus, 'OPENING'); assert.deepEqual(s.signals, [true]);
    assert.deepEqual(s.displays.at(-1), ['Employee', 'DANG MO CUA']);
    s.run(`handleAttendanceDelivery('current', AttendanceDelivery.${delivery});`);
    assert.deepEqual(s.signals, [true]); assert.equal(s.queueBytes, 1120);
  }
});

test('selected offline policy opens a matched current scan only after it was saved', () => {
  const notStored = fingerprintFixture(); notStored.lastAttendanceCreatedOffline = true;
  notStored.uploadResult = notStored.AttendanceDelivery.NOT_STORED;
  notStored.run('handleFingerprintScan();');
  assert.equal(notStored.doorStatus, 'CLOSED'); assert.equal(notStored.queueBytes, 0);
  assert.deepEqual(notStored.signals, [false]);
  const saved = fingerprintFixture(); saved.lastAttendanceCreatedOffline = true;
  saved.run('handleFingerprintScan();');
  assert.equal(saved.foregroundAttendanceDelivery, 'LOCAL_ACCEPTED'); assert.equal(saved.foregroundAttendanceHandled, true);
  assert.equal(saved.doorStatus, 'OPENING'); assert.equal(saved.queueBytes > 0, true);
  assert.equal(saved.attendancePendingSync, true); assert.equal(saved.fingerprintDoorNoticeOffline, true);
  assert.deepEqual(saved.displays.at(-1), ['VAN TAY #1', 'DANG MO CUA']); assert.deepEqual(saved.signals, [true]);
  for (let i = 0; i < 180; i++) saved.tick(10);
  assert.deepEqual(saved.displays.at(-1), ['SE DONG SAU 5S', 'OFFLINE: DA LUU']);
});

test('matched cold boot without a clock opens access only, creates no pending attendance and closes after 5s', () => {
  const s = fingerprintFixture(); s.validClock = false; s.lastAttendanceMissingClock = true;
  s.uploadResult = s.AttendanceDelivery.ACCESS_ONLY;
  s.run('handleFingerprintScan();');
  assert.deepEqual(s.displays.at(-1), ['DANG MO CUA', 'CHUA LUU CONG']);
  assert.equal(s.doorStatus, 'OPENING'); assert.equal(s.queueBytes, 0); assert.deepEqual(s.signals, [true]);
  assert.equal(s.foregroundAttendanceHandled, true); assert.equal(s.foregroundAttendanceEventId, '');
  assert.equal(s.foregroundAttendanceDelivery, 'NOT_STORED'); assert.notEqual(s.attendancePendingSync, true);
  assert.equal(s.fingerprintDoorNoticeAttendanceSaved, false); assert.match(s.error, /CHUA LUU CONG/);
  for (let i = 0; i < 180; i++) s.tick(10);
  assert.equal(s.doorStatus, 'OPEN'); assert.deepEqual(s.displays.at(-1), ['SE DONG SAU 5S', 'CHUA LUU CONG']);
  s.tick(4999); assert.equal(s.doorStatus, 'OPEN');
  s.tick(1); assert.equal(s.doorStatus, 'CLOSING');
  for (let i = 0; i < 180; i++) s.tick(10);
  assert.equal(s.doorStatus, 'CLOSED'); assert.equal(s.queueBytes, 0);
});

test('denial and unsaved sequence or storage failures cannot use access-only delivery', () => {
  for (const reason of ['denied', 'sequence', 'storage', 'full']) {
    const s = fingerprintFixture(); s.lastAttendanceCreatedOffline = true;
    s.uploadResult = s.AttendanceDelivery.NOT_STORED;
    if (reason === 'denied') {
      s.validClock = false; s.lastFingerprintAuthorizationDenied = true;
      s.uploadResult = s.AttendanceDelivery.REJECTED;
    }
    if (reason === 'full') s.outboxFull = true;
    s.run('handleFingerprintScan();');
    assert.equal(s.doorStatus, 'CLOSED', reason); assert.equal(s.queueBytes, 0, reason);
    assert.deepEqual(s.signals, [false], reason);
    assert.equal(s.foregroundAttendanceHandled, true, reason);
  }
});

test('door holds fully open 5s; closing travel is measured separately during enrollment', () => {
  const s = fixture();
  s.pendingCommandExecution = true; s.pendingCommandType = 'ENROLL_FINGERPRINT';
  s.enrollmentStage = 'FIRST_IMAGE';
  s.run('openDoor()');
  for (let i = 0; i < 180; i++) s.tick(10);
  assert.equal(s.doorStatus, 'OPEN'); assert.equal(s.doorOpenedAt, 1800);
  for (let i = 0; i < 499; i++) s.tick(10);
  assert.equal(s.doorStatus, 'OPEN');
  s.tick(10); assert.equal(s.doorStatus, 'CLOSING'); assert.equal(s.sensorCalls, 0);
  for (let i = 0; i < 180; i++) s.tick(10);
  assert.equal(s.doorStatus, 'CLOSED');
  assert.equal(s.now - 6800, 1800);
  assert.equal(s.enrollmentStage, 'FIRST_IMAGE');
  assert.equal(s.pendingCommandResult, false);
});

test('door timer and recent confirmation remain correct across millis rollover', () => {
  const s = fixture(); s.now = 0xfffffff0;
  s.doorOpen = true; s.doorOpenedAt = s.now;
  s.doorStatus = 'OPEN'; s.run('doorCurrentAngle = 0');
  s.tick(4999); assert.equal(s.doorStatus, 'OPEN');
  assert.equal(s.doorOpen, true);
  s.tick(1); assert.equal(s.doorStatus, 'CLOSING');
  assert.equal(s.run('foregroundConfirmationCanOpen(true, false, 20, 0xfffffff0, 15000)'), true);
});

test('old queued event acknowledgment does not fail or open the current scan', () => {
  const s = fixture(); s.foregroundAttendanceEventId = 'new'; s.foregroundAttendanceHandled = false;
  s.run("handleAttendanceDelivery('old', AttendanceDelivery.CONFIRMED)");
  assert.equal(s.doorMoving, false); assert.equal(s.foregroundAttendanceHandled, false);
  assert.equal(s.signals.length, 0);
  s.run("handleAttendanceDelivery('new', AttendanceDelivery.CONFIRMED)");
  assert.equal(s.doorStatus, 'OPENING'); assert.deepEqual(s.signals, [true]);
  s.run("handleAttendanceDelivery('new', AttendanceDelivery.CONFIRMED)");
  assert.deepEqual(s.signals, [true]);
});

test('late and restored acknowledgments synchronize data without opening', () => {
  const s = fixture(); s.foregroundAttendanceEventId = 'late'; s.foregroundAttendanceHandled = false;
  s.now = 15000;
  s.run("handleAttendanceDelivery('late', AttendanceDelivery.CONFIRMED)");
  assert.equal(s.doorMoving, false); assert.equal(s.foregroundAttendanceHandled, true);
  const restarted = fixture();
  restarted.run("handleAttendanceDelivery('restored', AttendanceDelivery.CONFIRMED)");
  assert.equal(restarted.doorMoving, false);
});

test('permanent rejection is distinct from saved pending scan and never opens', () => {
  const s = fixture(); s.foregroundAttendanceEventId = 'reject'; s.foregroundAttendanceHandled = false;
  s.run("handleAttendanceDelivery('reject', AttendanceDelivery.REJECTED)");
  assert.equal(s.doorMoving, false); assert.equal(s.lastFingerprintAuthorizationDenied, true);
  assert.deepEqual(s.signals, [false]);
  assert.equal(s.foregroundAttendanceDelivery, 'REJECTED');
});

test('SYNC_ATTENDANCE remains processing across multiple heads and retry, completes only empty', () => {
  const s = fixture(); s.pendingCommandExecution = true; s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.queueBytes = 3;
  s.tick(30000); assert.equal(s.pendingCommandResult, false);
  s.queueBytes = 2;
  s.tick(30000); assert.equal(s.pendingCommandResult, false);
  s.queueBytes = 1;
  s.tick(30000); assert.equal(s.pendingCommandResult, false);
  s.queueBytes = 0;
  s.tick(250); assert.equal(s.pendingCommandResult, true); assert.equal(s.pendingCommandSuccess, true);
});

test('SYNC timeout reports failure and retains unsent events', () => {
  const s = fixture(); s.pendingCommandExecution = true; s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.queueBytes = 12; s.tick(180000);
  assert.equal(s.pendingCommandResult, true); assert.equal(s.pendingCommandSuccess, false);
  assert.equal(s.queueBytes, 12);
});

test('enrollment consumes one sensor operation per tick and pauses timeout for door', () => {
  const s = fixture(); s.enrollmentStage = 'FIRST_IMAGE'; s.imageResult = 0;
  s.pendingCommandExecution = true; s.pendingCommandType = 'ENROLL_FINGERPRINT';
  s.tick(80); assert.equal(s.sensorCalls, 1); assert.equal(s.enrollmentStage, 'FIRST_CONVERSION');
  s.tick(80); assert.equal(s.sensorCalls, 2); assert.equal(s.enrollmentStage, 'REMOVE_FINGER');
  s.imageResult = 2;
  s.tick(80); assert.equal(s.enrollmentStage, 'SECOND_GAP');
  s.tick(600); assert.equal(s.enrollmentStage, 'SECOND_IMAGE');
  s.imageResult = 0;
  s.tick(80); s.tick(80); s.tick(80); s.tick(80);
  assert.equal(s.pendingCommandResult, true); assert.equal(s.pendingCommandSuccess, true);
  assert.equal(s.sensorCalls, 7);
});

test('door deferral neither sets HTTPS transport failure nor advances network operations', () => {
  const source = functionSource('10_helpers.ino', 'canStartHttpsRequest');
  const s = fixture(); s.firebaseSyncStatus = 'ONLINE'; s.hasHttpsTransportFailure = false;
  s.run(js(source)); s.run('openDoor()');
  assert.equal(s.run("canStartHttpsRequest('test')"), false);
  assert.equal(s.firebaseSyncStatus, 'PENDING'); assert.equal(s.hasHttpsTransportFailure, false);
  const loop = functionSource('90_main.ino', 'loop');
  assert.ok(loop.indexOf('serviceDoor()') < loop.indexOf('maintainWifiConnection()'));
  assert.ok(loop.indexOf('if (doorNeedsResponsiveLoop())') < loop.indexOf('flushAttendanceOutbox()'));
  assert.equal((loop.match(/flushAttendanceOutbox\(\)/g) || []).length, 1);
  assert.doesNotMatch(functionSource('12_as608.ino', 'serviceEnrollment'), /\bwhile\s*\(/);
});

function outboxFixture(records, responses) {
  const s = fixture();
  s.records = records.map(id => JSON.stringify({ eventId: id, payload: 'immutable-payload' }) + '\n');
  s.responses = [...responses]; s.posted = [];
  s.littleFsReady = true; s.ATTENDANCE_OUTBOX_PATH = '/attendance.outbox';
  s.WL_CONNECTED = 1; s.online = true; s.WiFi = { status: () => s.online ? 1 : 0 };
  s.canStartHttpsRequest = () => s.online && !s.doorMoving && !s.doorOpen;
  s.LittleFS = { exists: () => s.records.length > 0, open() {
    const content = s.records.join(''); let offset = 0;
    return { size: () => content.length, close() {}, position: () => offset,
      readStringUntil() { const end = content.indexOf('\n', offset); const value = content.slice(offset, end); offset = end + 1; return value; } };
  } };
  s.deserializeJson = (record, line) => { try { Object.assign(record, JSON.parse(line)); return false; } catch { return true; } };
  s.postAttendanceEvent = (id, payload) => { s.posted.push([id, payload]); return s.responses.shift() ?? -1; };
  s.preparationCode = 200;
  s.resolveOfflineAttendancePayload = payload => ({ code: s.preparationCode, payload });
  s.httpResponseAcknowledgesEvent = code => code >= 200 && code < 300 || code === 409;
  s.failRemoval = false;
  s.removeAttendanceOutboxHead = () => { if (s.failRemoval) return false; s.records.shift(); return true; };
  s.attendanceOutboxBytes = () => s.records.join('').length;
  s.attendanceOutboxIsEmpty = () => s.littleFsReady && s.records.length === 0;
  s.run(js(functionSource('30_attendance_storage.ino', 'isPermanentAttendanceFailure')));
  s.run(js(functionSource('30_attendance_storage.ino', 'flushAttendanceOutbox')));
  return s;
}

test('outbox sends one immutable head per attempt; disconnect/retry preserves all queued data', () => {
  const s = outboxFixture(['old', 'current'], [-1, 200, 200]);
  s.OFFLINE_AS608_ACCESS_ENABLED = false;
  s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.equal(s.records.length, 2);
  s.online = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'DEFERRED'); assert.equal(s.records.length, 2);
  assert.equal(s.posted.length, 1);
  s.online = true;
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 1);
  assert.equal(s.doorMoving, false); assert.equal(s.foregroundAttendanceHandled, false);
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 0);
  assert.equal(s.doorStatus, 'OPENING');
  assert.deepEqual(s.posted.map(x => x[0]), ['old', 'old', 'current']);
});

test('permanent outbox rejection reports current scan and continues toward next head', () => {
  const s = outboxFixture(['reject', 'next'], [403, 200]);
  s.foregroundAttendanceEventId = 'reject'; s.foregroundAttendanceHandled = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'REJECTED'); assert.equal(s.records.length, 1);
  assert.equal(s.lastRejectedAttendanceEventId, 'reject'); assert.equal(s.doorMoving, false);
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 0);
});

test('failed local removal retains confirmed record; idempotent replay cannot open twice', () => {
  const s = outboxFixture(['current'], [200, 409]);
  s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
  s.failRemoval = true;
  assert.equal(s.run('flushAttendanceOutbox()'), 'STORAGE_ERROR'); assert.equal(s.records.length, 1);
  assert.deepEqual(s.signals, [true]);
  // Finish the actual door cycle before allowing HTTPS replay.
  for (let i = 0; i < 860; i++) s.tick(10);
  s.failRemoval = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 0);
  assert.deepEqual(s.signals, [true]);
});

test('transport retry retains all records and grants the stored current scan once even with an older head', () => {
  const s = outboxFixture(['old', 'current'], [-1, -1, 200, 200]);
  s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.equal(s.records.length, 2);
  assert.deepEqual(s.posted.map(record => record[0]), ['old']);
  assert.equal(s.doorStatus, 'OPENING'); assert.equal(s.foregroundAttendanceDelivery, 'LOCAL_ACCEPTED');
  assert.equal(s.foregroundAttendanceHandled, true); assert.deepEqual(s.signals, [true]);
  for (let i = 0; i < 860; i++) s.tick(10);
  assert.equal(s.doorStatus, 'CLOSED');
  assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.equal(s.records.length, 2);
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 1);
  assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 0);
  assert.equal(s.doorStatus, 'CLOSED'); assert.deepEqual(s.signals, [true]);
});

test('restored and expired records receive neither offline fallback access nor a later second opening', () => {
  for (const mode of ['restored', 'expired']) {
    const s = outboxFixture(['saved'], [-1, 200]);
    if (mode === 'expired') {
      s.foregroundAttendanceEventId = 'saved'; s.foregroundAttendanceHandled = false; s.now = 15000;
    }
    assert.equal(s.run('flushAttendanceOutbox()'), 'RETRY'); assert.equal(s.records.length, 1);
    assert.equal(s.doorStatus, 'CLOSED');
    assert.equal(s.run('flushAttendanceOutbox()'), 'ACKNOWLEDGED'); assert.equal(s.records.length, 0);
    assert.equal(s.doorStatus, 'CLOSED'); assert.deepEqual(s.signals, []);
  }
});

test('explicit server or offline-mapping 403 never falls back to local door access', () => {
  for (const stage of ['POST', 'mapping resolution']) {
    const s = outboxFixture(['current'], [403]);
    s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
    if (stage === 'mapping resolution') s.preparationCode = 403;
    assert.equal(s.run('flushAttendanceOutbox()'), 'REJECTED'); assert.equal(s.records.length, 0);
    assert.equal(s.doorStatus, 'CLOSED'); assert.equal(s.foregroundAttendanceDelivery, 'REJECTED');
    assert.deepEqual(s.signals, [false]);
    if (stage === 'mapping resolution') assert.equal(s.posted.length, 0);
  }
});

test('offline fallback is limited to unavailable transport and can be disabled', () => {
  const s = fixture();
  for (const code of [-1, -11, 408, 425, 429, 500, 503]) assert.equal(s.run(`attendanceTransportUnavailable(${code})`), true);
  for (const code of [200, 400, 401, 403, 404, 409]) assert.equal(s.run(`attendanceTransportUnavailable(${code})`), false);
  s.OFFLINE_AS608_ACCESS_ENABLED = false; s.foregroundAttendanceEventId = 'current'; s.foregroundAttendanceHandled = false;
  s.run("handleAttendanceDelivery('current', AttendanceDelivery.LOCAL_ACCEPTED)");
  assert.equal(s.doorStatus, 'CLOSED'); assert.deepEqual(s.signals, []);
});

test('unavailable LittleFS cannot report an empty queue or complete SYNC', () => {
  const s = outboxFixture(['saved'], [200]); s.littleFsReady = false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'STORAGE_ERROR'); assert.equal(s.records.length, 1);
  s.pendingCommandExecution = true; s.pendingCommandType = 'SYNC_ATTENDANCE';
  s.tick(100); assert.equal(s.pendingCommandResult, false);
  s.tick(180000); assert.equal(s.pendingCommandSuccess, false); assert.equal(s.records.length, 1);
});

test('failed opening an existing queue is a storage error, never EMPTY', () => {
  const s = outboxFixture(['saved'], [200]); s.LittleFS.open = () => false;
  assert.equal(s.run('flushAttendanceOutbox()'), 'STORAGE_ERROR');
  assert.equal(s.posted.length, 0); assert.equal(s.records.length, 1);
});

test('manual reopen at the first closing tick restores OPEN and starts a new hold', () => {
  const s = fixture(); s.run('openDoor()');
  for (let i = 0; i < 680; i++) s.tick(10);
  assert.equal(s.doorStatus, 'CLOSING');
  s.run('openDoor()'); assert.equal(s.doorStatus, 'OPEN'); assert.equal(s.doorOpenedAt, 6800);
  s.tick(4999); assert.equal(s.doorStatus, 'OPEN');
  s.tick(1); assert.equal(s.doorStatus, 'CLOSING');
});

test('enrollment completion/failure never requests a software restart', () => {
  const s = fixture();
  s.COMMAND_IDLE_POLL_INTERVAL_MS = 15000; s.COMMAND_RETRY_INTERVAL_MS = 10000;
  s.pendingCommandRestart = false; s.restartCalls = 0;
  s.ESP = { restart() { s.restartCalls++; } };
  s.strcmp = (left, right) => left === right ? 0 : 1;
  s.updateDeviceCommandStatus = () => true; s.commitFingerprintCompletion = () => true;
  s.startWaitingForFingerRemoval = () => {}; s.showSensorReconnectScreen = () => {};
  s.run(js(functionSource('60_device_commands.ino', 'finishDeviceCommand')));
  for (const success of [true, false]) {
    s.pendingCommandType = 'ENROLL_FINGERPRINT'; s.pendingCommandSuccess = success;
    s.pendingCommandResult = true;
    assert.equal(s.run('finishDeviceCommand()'), true); assert.equal(s.restartCalls, 0);
  }
  assert.match(functionSource('60_device_commands.ino', 'checkDeviceCommand'), /pendingCommandRestart = type == "RESTART_DEVICE"/);
  s.pendingCommandType = 'RESTART_DEVICE'; s.pendingCommandRestart = true; s.pendingCommandSuccess = true;
  assert.equal(s.run('finishDeviceCommand()'), true); assert.equal(s.restartCalls, 1);
});
