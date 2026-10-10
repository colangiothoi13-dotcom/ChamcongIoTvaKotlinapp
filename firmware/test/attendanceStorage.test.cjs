const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '..', 'esp8266_fingerprint', '30_attendance_storage.ino'), 'utf8');

// Execute the production scalar control flow with a byte-addressable fake
// LittleFS. Peripheral compilation and power-cut testing still require a board.
function functionSource(name, returnType = 'bool') {
  const start = source.indexOf(`${returnType} ${name}(`);
  assert.notEqual(start, -1, `${name} exists`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

function enqueueSource() {
  return functionSource('enqueueAttendanceEvent')
    .replace(/bool enqueueAttendanceEvent\(const String& eventId, const String& payload\)/,
      'function enqueueAttendanceEvent(eventId, payload)')
    .replace(/DynamicJsonDocument record\(2048\);/, 'const record = {};')
    .replace(/String line;/, "let line = '';")
    .replace(/line.reserve\([^;]+;/g, '')
    .replace(/serializeJson\(record, line\);/, 'line = JSON.stringify(record);')
    .replace(/lastByte != '\\n'/, 'lastByte != 10')
    .replace(/\bconst\s+(?:size_t|int)\s+/g, 'const ')
    .replace(/\b(?:size_t|bool|File)\s+(\w+)\s*=/g, 'let $1 =')
    .replace(/\.length\(\)/g, '.length')
    .replace(/\.c_str\(\)/g, '')
    .replace(/static_cast<unsigned>\(([^)]+)\)/g, '($1)');
}

function fixture(content = '') {
  const state = {
    content, exists: content.length > 0, littleFsReady: true,
    ATTENDANCE_OUTBOX_PATH: '/attendance.outbox', ATTENDANCE_OUTBOX_MAX_BYTES: 12288,
    failRead: false, failSeek: false, failTailRead: false, writeBudget: Infinity,
    errors: [], appendCalls: 0, Serial: { printf() {}, printf_P() {} }, F: value => value, PSTR: value => value,
    setLatestError(value) { state.errors.push(value); },
    LittleFS: {
      exists() { return state.exists; },
      open(_path, mode) {
        if (mode === 'r') {
          if (!state.exists || state.failRead) return false;
          let offset = 0;
          return {
            size: () => state.content.length,
            seek(value) { offset = value; return !state.failSeek; },
            read: () => state.failTailRead ? -1 : state.content.charCodeAt(offset),
            close() {},
          };
        }
        assert.equal(mode, 'a');
        state.appendCalls++;
        state.exists = true;
        return {
          print(value) {
            const written = Math.min(value.length, state.writeBudget);
            state.content += value.slice(0, written);
            state.writeBudget -= written;
            return written;
          },
          flush() {}, close() {},
        };
      },
    },
  };
  const context = vm.createContext(state);
  vm.runInContext(enqueueSource(), context);
  state.enqueue = (eventId, payload = 'immutable-payload') => state.enqueueAttendanceEvent(eventId, payload);
  return state;
}

const record = (eventId, payload = 'immutable-payload') => JSON.stringify({ eventId, payload });
const readableRecords = content => content.split('\n').flatMap(line => {
  try { return line ? [JSON.parse(line)] : []; } catch { return []; }
});

function recoveryFixture(files) {
  const state = {
    files: { ...files }, littleFsReady: true, errors: [], F: value => value, PSTR: value => value,
    ATTENDANCE_OUTBOX_PATH: '/main', ATTENDANCE_OUTBOX_TMP_PATH: '/temp', ATTENDANCE_OUTBOX_BACKUP_PATH: '/backup',
    setLatestError(value) { state.errors.push(value); }, yield() {},
    strcmp: (left, right) => left === right ? 0 : 1,
    parseAttendanceOutboxRecord(line) {
      try { const row = JSON.parse(line); return !!row.eventId?.trim() && !!row.payload?.trim(); } catch { return false; }
    },
    LittleFS: {
      exists: file => Object.hasOwn(state.files, file),
      remove(file) { delete state.files[file]; return true; },
      rename(from, to) {
        if (!Object.hasOwn(state.files, from)) return false;
        state.files[to] = state.files[from]; delete state.files[from]; return true;
      },
      open(file) {
        if (!Object.hasOwn(state.files, file)) return false;
        const content = state.files[file]; let offset = 0;
        return {
          available: () => offset < content.length, close() {},
          readStringUntil() {
            const newline = content.indexOf('\n', offset);
            const end = newline < 0 ? content.length : newline;
            const line = content.slice(offset, end); offset = end + 1; return line;
          },
        };
      },
    },
  };
  const context = vm.createContext(state);
  const adapt = input => input
    .replace(/\b(?:bool|void)\s+(\w+)\(([^)]*)\)/g, (_, name, params) =>
      `function ${name}(${params.replace(/\b(?:const\s+)?(?:char\*|size_t&)\s*/g, '')})`)
    .replace(/\b(?:const\s+)?(?:size_t|bool|File|String)\s+(\w+)\s*=/g, 'let $1 =')
    .replace(/const char\* (\w+) =/g, 'let $1 =')
    .replace(/\bnullptr\b/g, 'null')
    .replace(/String (\w+);/g, "let $1 = '';")
    .replace(/line.trim\(\);/g, 'line = line.trim();')
    .replace(/\.length\(\)/g, '.length')
    .replace(/parseAttendanceOutboxRecord\(line, eventId, payload\)/g, 'parseAttendanceOutboxRecord(line)');
  vm.runInContext(adapt(functionSource('inspectAttendanceOutbox')).replace(/\brecordCount\b/g, 'recordCount.value')
    .replace('function inspectAttendanceOutbox(path, recordCount.value)', 'function inspectAttendanceOutbox(path, recordCount)'), context);
  vm.runInContext(adapt(functionSource('promoteAttendanceOutbox')), context);
  vm.runInContext(adapt(functionSource('recoverAttendanceOutbox', 'void'))
    .replace(/inspectAttendanceOutbox\(([^,]+), (\w+)\)/g, (_, file, count) =>
      `inspectAttendanceOutbox(${file}, {get value() {return ${count};}, set value(value) {${count} = value;}})`), context);
  state.recover = () => state.recoverAttendanceOutbox();
  state.inspect = file => { const count = { value: 0 }; return { clean: state.inspectAttendanceOutbox(file, count), count: count.value }; };
  return state;
}

test('a torn append cannot swallow the next successfully stored scan', () => {
  const s = fixture(`${record('first')}\n{"eventId":"torn`);
  assert.equal(s.enqueue('next'), true);
  assert.deepEqual(readableRecords(s.content).map(row => row.eventId), ['first', 'next']);
  assert.equal(s.content.split('\n')[1], '{"eventId":"torn');
});

test('a complete record missing only its newline is retained before the new scan', () => {
  const s = fixture(record('first'));
  assert.equal(s.enqueue('next'), true);
  assert.deepEqual(readableRecords(s.content).map(row => row.eventId), ['first', 'next']);
});

test('retry after an actual short write preserves the next healthy enqueue', () => {
  const s = fixture(`${record('first')}\n`);
  s.writeBudget = 12;
  assert.equal(s.enqueue('failed'), false);
  s.writeBudget = Infinity;
  assert.equal(s.enqueue('next'), true);
  assert.deepEqual(readableRecords(s.content).map(row => row.eventId), ['first', 'next']);
});

test('unreadable queue or tail rejects enqueue without appending another record', () => {
  for (const failure of ['failRead', 'failSeek', 'failTailRead']) {
    const original = `${record('first')}\n`;
    const s = fixture(original); s[failure] = true;
    assert.equal(s.enqueue('next'), false, failure);
    assert.equal(s.content, original, failure);
    assert.equal(s.appendCalls, 0, failure);
  }
});

test('failure to persist the repair separator does not acknowledge the new scan', () => {
  const s = fixture('{"torn":'); s.writeBudget = 0;
  assert.equal(s.enqueue('next'), false);
  assert.equal(s.content, '{"torn":');
});

test('queue capacity accounts for the repair separator and preserves existing bytes', () => {
  const s = fixture('{"torn":');
  s.ATTENDANCE_OUTBOX_MAX_BYTES = s.content.length + record('next').length + 1;
  const original = s.content;
  assert.equal(s.enqueue('next'), false);
  assert.equal(s.content, original);
  assert.equal(s.appendCalls, 0);
});

test('normal appends create exactly one newline per record', () => {
  const s = fixture();
  assert.equal(s.enqueue('first'), true);
  assert.equal(s.enqueue('next'), true);
  assert.equal(s.content, `${record('first')}\n${record('next')}\n`);
});

test('recovery preserves newer live records when a torn append and stale clean backup coexist', () => {
  const main = `${record('pending')}\n${record('newer')}\n{"torn":`;
  const s = recoveryFixture({ '/main': main, '/backup': `${record('acknowledged')}\n${record('pending')}\n` });
  s.recover();
  assert.equal(s.files['/main'], main);
  assert.deepEqual(readableRecords(s.files['/main']).map(row => row.eventId), ['pending', 'newer']);
  assert.equal(Object.hasOwn(s.files, '/backup'), false);
});

test('inspection finds valid records after a damaged line instead of stopping at the first error', () => {
  const main = `{"torn":\n${record('newer')}\n`;
  const s = recoveryFixture({ '/main': main, '/backup': `${record('old')}\n` });
  assert.deepEqual(s.inspect('/main'), { clean: false, count: 1 });
  s.recover();
  assert.equal(s.files['/main'], main);
});

test('interrupted compaction without a live file still recovers the complete backup', () => {
  const backup = `${record('old')}\n${record('pending')}\n`;
  const s = recoveryFixture({ '/temp': `${record('pending')}\n`, '/backup': backup });
  s.recover();
  assert.equal(s.files['/main'], backup);
  assert.equal(Object.hasOwn(s.files, '/backup'), false);
  assert.equal(Object.hasOwn(s.files, '/temp'), false);
});
