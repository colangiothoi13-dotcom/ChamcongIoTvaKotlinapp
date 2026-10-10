const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture({ capturedIdentity = true, originalEmployeeExists = true } = {}) {
  const timestamp = milliseconds => ({ toMillis: () => milliseconds });
  const scan = {
    templateId: 7, deviceId: 'GATE-01', verified: true,
    type: 'SCAN', resolutionStatus: 'PENDING', status: 'PENDING',
    timestamp: timestamp(Date.parse('2026-10-08T01:00:00Z')),
    ...(capturedIdentity ? { employeeId: 'employee-original', employeeName: 'Original name at scan' } : {}),
  };
  const documents = new Map([
    ['attendance/scan-1', scan],
    ['fingerprintMappings/7', { enabled: true, employeeId: 'employee-replacement' }],
    ['employees/employee-replacement', { active: true, fullName: 'Replacement employee' }],
    ['shifts/morning', { startTime: '08:00', endTime: '12:00', category: 'MORNING' }],
    ...['employee-original', 'employee-replacement'].map(employeeId => [`workSchedules/${employeeId}_2026-10-08`, {
      employeeId, date: '2026-10-08', shiftId: 'morning', shiftIds: ['morning'],
    }]),
  ]);
  if (originalEmployeeExists) documents.set('employees/employee-original', {
    active: false, fullName: 'Original name changed after scan',
  });
  const reads = [], writes = [];
  const snapshot = path => ({
    id: path.split('/').at(-1), exists: documents.has(path), data: () => documents.get(path),
  });
  const transaction = {
    async get(ref) {
      reads.push(ref.path || ref.collection);
      if (ref.path) return snapshot(ref.path);
      const rows = [...documents.keys()].filter(path => path.startsWith(`${ref.collection}/`))
        .filter(path => ref.filters.every(([field, op, expected]) => {
          const value = documents.get(path)[field];
          return op === 'in' ? expected.includes(value) : value === expected;
        }));
      return { docs: rows.map(snapshot) };
    },
    update(ref, data) {
      writes.push({ path: ref.path, data });
      documents.set(ref.path, { ...documents.get(ref.path), ...data });
    },
    set(ref, data, options) {
      writes.push({ path: ref.path, data });
      documents.set(ref.path, options?.merge ? { ...documents.get(ref.path), ...data } : data);
    },
  };
  const db = {
    collection(collection) {
      return {
        collection, filters: [],
        doc(id) { return { path: `${collection}/${id}` }; },
        where(field, op, expected) { this.filters.push([field, op, expected]); return this; },
      };
    },
    runTransaction: callback => callback(transaction),
  };
  const firestore = Object.assign(() => db, {
    Timestamp: { fromMillis: timestamp }, FieldValue: { serverTimestamp: () => 'server-time' },
  });
  const exported = {};
  vm.runInNewContext(fs.readFileSync(require.resolve('../index.js'), 'utf8'), {
    exports: exported, Buffer, console,
    require(name) {
      if (name === 'firebase-functions/v2/https') return { onRequest: (_, handler) => handler };
      if (name === 'firebase-functions/v2/firestore') return {
        onDocumentCreated: (_, handler) => handler,
        onDocumentUpdated: (_, handler) => handler,
        onDocumentWritten: (_, handler) => handler,
      };
      if (name === 'firebase-functions/params') return { defineSecret: () => ({ value: () => 'test-key' }) };
      if (name === 'firebase-admin') return { initializeApp() {}, firestore };
      if (name.startsWith('./')) return require(`../${name.slice(2)}`);
      return require(name);
    },
  });
  // The create-event snapshot is fixed even when the transaction writes back.
  const event = { data: { ref: { path: 'attendance/scan-1' }, data: () => scan }, params: { eventId: 'scan-1' } };
  return { resolve: () => exported.resolveAttendance(event), reads, writes, documents };
}

test('delayed attendance resolution keeps the scanned employee after template reassignment and retirement', async () => {
  const s = fixture();
  await s.resolve();
  const resolved = s.documents.get('attendance/scan-1');
  assert.equal(resolved.employeeId, 'employee-original');
  assert.equal(resolved.employeeName, 'Original name at scan');
  assert.equal(resolved.type, 'CHECK_IN');
  assert.equal(resolved.resolutionStatus, 'ACCEPTED');
  assert.equal(s.documents.get('attendanceSessions/employee-original_2026-10-08').lastAcceptedEventId, 'scan-1');
  assert.equal(s.documents.has('attendanceSessions/employee-replacement_2026-10-08'), false);
  assert.equal(s.reads.includes('fingerprintMappings/7'), false);
});

test('historical attendance remains attributed to its recorded identity when the employee profile was removed', async () => {
  const s = fixture({ originalEmployeeExists: false });
  await s.resolve();
  assert.equal(s.documents.get('attendance/scan-1').employeeId, 'employee-original');
  assert.equal(s.documents.get('attendance/scan-1').resolutionStatus, 'ACCEPTED');
});

test('legacy scans without a captured employee use the enabled active mapping fallback', async () => {
  const s = fixture({ capturedIdentity: false });
  await s.resolve();
  assert.equal(s.documents.get('attendance/scan-1').employeeId, 'employee-replacement');
  assert.equal(s.documents.get('attendance/scan-1').employeeName, 'Replacement employee');
  assert.equal(s.reads.includes('fingerprintMappings/7'), true);
  assert.equal(s.reads.includes('employees/employee-replacement'), true);
});

test('duplicate trigger delivery does not write the resolved event or advance its session again', async () => {
  const s = fixture();
  await s.resolve();
  const writeCount = s.writes.length;
  await s.resolve();
  assert.equal(s.writes.length, writeCount);
  assert.equal(s.documents.get('attendanceSessions/employee-original_2026-10-08').lastAcceptedEventId, 'scan-1');
});
