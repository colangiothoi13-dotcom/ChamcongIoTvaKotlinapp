const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const sketch = path.join(__dirname, '..', 'esp8266_fingerprint');
const helperSource = fs.readFileSync(path.join(sketch, '10_helpers.ino'), 'utf8');
const mainSource = fs.readFileSync(path.join(sketch, 'esp8266_fingerprint.ino'), 'utf8');

function functionSource(name) {
  const start = helperSource.search(new RegExp(`\\b(?:bool|void) ${name}\\([^)]*\\)\\s*\\{`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = helperSource.indexOf('{', start), depth = 1;
  while (depth && ++end < helperSource.length) {
    if (helperSource[end] === '{') depth++;
    if (helperSource[end] === '}') depth--;
  }
  return helperSource.slice(start, end + 1);
}

// Run the production decision branches, replacing only Arduino scalar types,
// clock, logging and ESP heap measurements. No handshake is simulated here.
function adapt(source) {
  return source
    .replace(/^(?:bool|void) (\w+)\(([^)]*)\)\s*\{/, (_, name, parameters) =>
      `function ${name}(${parameters.replace(/\b(?:const\s+)?(?:char\s*\*|bool)\s*/g, '')}) {`)
    .replace(/\bconst\s+(?:uint32_t|uint8_t|bool)\s+/g, 'const ');
}

function fixture() {
  const constants = Object.fromEntries([...mainSource.matchAll(
    /const (?:uint32_t|unsigned long) (\w+) = (\d+);/g,
  )].map(match => [match[1], Number(match[2])]));
  const s = {
    ...constants, now: 1000, online: true, doorBusy: false,
    heap: 40000, block: 30000, fragmentation: 2, logs: [],
    firebaseSyncStatus: 'ONLINE', hasHttpsTransportFailure: false, lastHttpsTransportFailure: 0,
    WL_CONNECTED: 1, F: value => value, PSTR: value => value,
    Serial: {
      printf(...args) { s.logs.push(args); },
      printf_P(...args) { s.logs.push(args); },
    },
    millis: () => s.now >>> 0,
    elapsedAtLeast: (now, started, interval) => ((now - started) >>> 0) >= interval,
    doorNeedsResponsiveLoop: () => s.doorBusy,
    WiFi: { status: () => s.online ? 1 : 0 },
    ESP: {
      getFreeHeap: () => s.heap,
      getMaxFreeBlockSize: () => s.block,
      getHeapFragmentation: () => s.fragmentation,
    },
  };
  const context = vm.createContext(s);
  s.run = code => vm.runInContext(code, context);
  for (const name of ['deferHttpsRequests', 'httpsRetryCooldownActive', 'canStartHttpsRequest']) {
    s.run(adapt(functionSource(name)));
  }
  return s;
}

test('initial HTTPS budget rejects insufficient total RAM and contiguous block independently', () => {
  for (const [heap, block, allowed] of [
    [30000, 20000, true], [29999, 30000, false], [40000, 19999, false],
  ]) {
    const s = fixture(); s.heap = heap; s.block = block;
    assert.equal(s.run("canStartHttpsRequest('initial')"), allowed, `heap=${heap}, block=${block}`);
    assert.equal(s.hasHttpsTransportFailure, !allowed);
    if (!allowed) assert.equal(s.lastHttpsTransportFailure, s.now);
  }
});

test('prepared HTTPS budget independently protects handshake RAM and large contiguous allocation', () => {
  for (const [heap, block, allowed] of [
    [28000, 24000, true], [27999, 30000, false], [40000, 23999, false],
  ]) {
    const s = fixture(); s.heap = heap; s.block = block;
    assert.equal(s.run("canStartHttpsRequest('prepared', true)"), allowed, `heap=${heap}, block=${block}`);
    assert.equal(s.hasHttpsTransportFailure, !allowed);
    assert.ok(s.logs.length > 0, 'log the budget measured immediately before the handshake');
  }
});

test('a preliminary pass cannot mask fragmentation introduced by request preparation', () => {
  const s = fixture(); s.heap = 35000; s.block = 27000;
  assert.equal(s.run("canStartHttpsRequest('heartbeat')"), true);
  // Client construction reserves the TLS secondary stack; HTTP headers and
  // token concatenation may also split the largest remaining heap block.
  s.heap = 28500; s.block = 23000; s.fragmentation = 19;
  assert.equal(s.run("canStartHttpsRequest('heartbeat', true)"), false);
  assert.equal(s.hasHttpsTransportFailure, true);
  assert.equal(s.lastHttpsTransportFailure, 1000);
});

test('door and disconnected Wi-Fi deferrals never create a transport cooldown', () => {
  for (const configure of [s => { s.doorBusy = true; }, s => { s.online = false; }]) {
    const s = fixture(); configure(s);
    assert.equal(s.run("canStartHttpsRequest('deferred', true)"), false);
    assert.equal(s.hasHttpsTransportFailure, false);
    assert.equal(s.lastHttpsTransportFailure, 0);
  }
});

test('memory deferral recovers at cooldown expiry across millis rollover', () => {
  const s = fixture(); s.now = 0xfffffff0; s.heap = 27999;
  assert.equal(s.run("canStartHttpsRequest('heartbeat', true)"), false);
  s.heap = 40000; s.block = 30000;
  s.now = (0xfffffff0 + 29999) >>> 0;
  assert.equal(s.run("canStartHttpsRequest('heartbeat', true)"), false);
  assert.equal(s.lastHttpsTransportFailure, 0xfffffff0, 'cooldown checks never move the failure deadline');
  s.now = (0xfffffff0 + 30000) >>> 0;
  assert.equal(s.run("canStartHttpsRequest('heartbeat', true)"), true);
});
