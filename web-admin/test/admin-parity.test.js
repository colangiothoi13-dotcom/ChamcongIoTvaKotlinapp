import test from 'node:test';
import assert from 'node:assert/strict';
import { onAction as workAction, render as renderOperations } from '../src/operations.js';
import { onAction as attendanceAction, render as renderAttendance } from '../src/attendance.js';
import { at } from '../src/domain.js';
import { memoryStore } from '../src/demo.js';

function fixture() {
  const date = '2026-10-10';
  const employee = { id: 'e1', code: 'NV1', fullName: 'An <img src=x>', active: true, department: 'Kỹ thuật', baseSalary: 25000 };
  const morning = { id: 'am', name: 'Ca sáng', category: 'MORNING', startTime: '08:00', endTime: '12:00', active: true };
  const afternoon = { ...morning, id: 'pm', name: 'Ca chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00' };
  const scan = (id, time, type) => ({ id, employeeId: 'e1', timestamp: new Date(at(date, time)), type, status: 'NORMAL', resolutionStatus: 'ACCEPTED', verified: true, deviceId: 'GATE-01' });
  const data = {
    employees: [employee], shifts: [morning, afternoon],
    workSchedules: [{ id: `e1_${date}`, employeeId: 'e1', date, shiftId: 'am', shiftIds: ['am', 'pm'] }],
    attendance: [scan('in-am', '07:45', 'CHECK_IN'), scan('out-am', '12:15', 'CHECK_OUT'), scan('in-pm', '13:00', 'CHECK_IN'), scan('out-pm', '17:00', 'CHECK_OUT')],
    workItems: [{ id: 'work1', title: 'Kiểm tra', assigneeId: 'e1', assigneeName: 'An', status: 'IN_PROGRESS', version: 2, relatedScheduleId: `e1_${date}`, relatedScheduleDate: date, relatedShiftId: 'am', resultAttachments: [] }],
    leaveRequests: [], overtimeRequests: [], attendanceAdjustments: [], attendanceClassificationOverrides: [], offScheduleReviews: [],
    payroll: [{ id: 'e1_2026-10', employeeId: 'e1', employeeName: employee.fullName, employeeCode: 'NV1', month: '2026-10', hourlyRate: 25000, hoursWorked: 1, baseSalary: 25000, bonus: 0, deduction: 0 }],
    'workItems/work1/history': [], weeklyScheduleRequests: [], departments: []
  };
  let dialog;
  const store = memoryStore(data);
  const ctx = { data, store, user: { uid: 'admin', displayName: 'Admin' }, filters: { month: '2026-10', reportType: 'MONTHLY', from: date, to: date, week: '2026-10-12', requestGroup: 'weekly' }, loaded: { employees: true, payroll: true }, errors: {}, range: { from: '2026-10-01', to: '2026-10-31', complete: true, loading: false }, ui: { dialog: value => { dialog = value; }, toast: () => {} }, render: () => {}, loadRange: async (from, to) => { ctx.range = { from, to, complete: true, loading: false }; } };
  return { ctx, data, employee, date, dialog: () => dialog };
}

test('monthly report and read-only detail expose two distinct shifts with raw and paid times', async () => {
  const f = fixture(), before = structuredClone(f.data);
  assert.match(renderAttendance('reports', f.ctx), /data-action="timesheet-detail" data-id="e1"/);
  await attendanceAction('timesheet-detail', { dataset: { id: 'e1' } }, f.ctx);
  const body = f.dialog().body;
  assert.match(body, /Ca sáng/); assert.match(body, /Ca chiều/);
  assert.match(body, /07:45:00 → 12:15:00/); assert.match(body, /08:00:00 → 12:00:00/);
  assert.match(body, /Giờ thường 8 h/); assert.match(body, /Quét vào → ra/);
  assert.match(body, /An &lt;img src=x&gt;/); assert.doesNotMatch(body, /<img src=x>/);
  assert.deepEqual(f.data, before); assert.equal(f.dialog().onSubmit, undefined);
});

test('monthly detail refuses incomplete ranges without pretending hours are zero', async () => {
  const f = fixture(); f.ctx.range.complete = false; f.ctx.loadRange = async () => {};
  await assert.rejects(attendanceAction('timesheet-detail', { dataset: { id: 'e1' } }, f.ctx), /chưa đầy đủ/);
  assert.equal(f.dialog(), undefined);
});

test('monthly report detail uses employment cutoff while payroll keeps its own current totals', async () => {
  const f = fixture(); f.employee.active = false; f.employee.terminationDate = '2026-10-09';
  await attendanceAction('timesheet-detail', { dataset: { id: 'e1' } }, f.ctx);
  assert.match(f.dialog().body, /Giờ thường 0 h/); assert.doesNotMatch(f.dialog().body, /07:45:00/);
  await attendanceAction('payroll-recalculate', { dataset: { id: 'e1_2026-10' } }, f.ctx);
  assert.match(f.dialog().body, /Chi tiết giờ công theo ngày và ca/);
  assert.match(f.dialog().body, /07:45:00 → 12:15:00/);
});

test('new payroll and recalculation offer day evidence before any confirmation write', async () => {
  const f = fixture(), before = structuredClone(f.data);
  await attendanceAction('payroll-recalculate', { dataset: { id: 'e1_2026-10' } }, f.ctx);
  assert.match(f.dialog().body, /Chi tiết giờ công theo ngày và ca/);
  assert.match(f.dialog().body, /Ca sáng/); assert.deepEqual(f.data, before);
  f.data.payroll = [];
  await attendanceAction('payroll-create', { dataset: { id: 'e1' } }, f.ctx);
  assert.match(f.dialog().body, /Chi tiết giờ công theo ngày và ca/);
  assert.match(f.dialog().body, /Ca chiều/); assert.equal(f.data.payroll.length, 0);
});

test('linked work evidence assigns legacy scans once and excludes the other shift', async () => {
  const f = fixture();
  await workAction('ops-work-detail', { dataset: { id: 'work1' } }, f.ctx);
  assert.match(f.dialog().body, /07:45:00/); assert.match(f.dialog().body, /12:15:00/);
  assert.doesNotMatch(f.dialog().body, /13:00:00|17:00:00/);
  assert.match(f.dialog().body, /Lượt hợp lệ của ca/);
});

test('linked work evidence uses effective corrections and does not display failed scans', async () => {
  const f = fixture();
  f.data.attendance[0].type = 'SCAN'; f.data.attendance[0].resolutionStatus = 'PENDING';
  f.data.attendanceClassificationOverrides.push({ id: 'correction', attendanceId: 'in-am', employeeId: 'e1', scheduleDate: f.date, correctedType: 'CHECK_IN', correctedStatus: 'ON_TIME', createdAt: new Date() });
  f.data.attendance[1].verified = false;
  await workAction('ops-work-detail', { dataset: { id: 'work1' } }, f.ctx);
  assert.match(f.dialog().body, /07:45:00/); assert.doesNotMatch(f.dialog().body, /12:15:00/);
});

test('work detail remains usable when linked attendance cannot load', async () => {
  const f = fixture(); f.ctx.range.complete = false;
  f.ctx.loadRange = async () => { throw new Error('offline'); };
  await workAction('ops-work-detail', { dataset: { id: 'work1' } }, f.ctx);
  assert.match(f.dialog().body, /chưa tải được/); assert.match(f.dialog().body, /Mô tả công việc/);
});

test('weekly headcounts include pending and approved, exclude needs revision and deduplicate shift choices', () => {
  const f = fixture(), date = '2026-10-12';
  f.data.weeklyScheduleRequests = [
    { id: 'a', employeeId: 'e1', employeeName: 'An', weekStart: date, status: 'PENDING', shiftsByDate: { [date]: ['am', 'am'] } },
    { id: 'b', employeeId: 'e2', employeeName: 'Bình', weekStart: date, status: 'APPROVED', shiftsByDate: { [date]: ['am', 'pm'] } },
    { id: 'c', employeeId: 'e3', employeeName: 'Chi', weekStart: date, status: 'NEEDS_REVISION', shiftsByDate: { [date]: ['am'] } }
  ];
  f.ctx.filters.search = 'not found';
  const body = renderOperations('requests', f.ctx);
  assert.match(body, /Số người theo ca đã gửi \/ duyệt/);
  assert.match(body, /Ca sáng \(08:00–12:00\)<\/td><td>2<\/td>/);
  assert.match(body, /Ca chiều \(13:00–17:00\)<\/td><td>1<\/td>/);
});

async function oldAttachmentFixture() {
  const f = fixture(), bytes = new TextEncoder().encode('old result');
  const sha256 = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(b => b.toString(16).padStart(2, '0')).join('');
  const attachment = { id: 'old-file', fileName: '<img src=x>.txt', sha256 };
  f.data['workItems/work1/history'] = [{ id: '1', version: 1, action: 'RESULT_SUBMITTED', after: { title: 'Kiểm tra', status: 'PENDING_REVIEW', resultAttachments: [attachment] } }];
  f.data['workItems/work1/attachments'] = [{ id: 'old-file', fileName: attachment.fileName, sha256, sizeBytes: bytes.length, chunkCount: 1, mimeType: 'text/plain' }];
  f.data['workItems/work1/attachments/old-file/chunks'] = [{ id: '0', index: 0, data: bytes }];
  return f;
}

test('work history exposes escaped historical attachments with explicit version', async () => {
  const f = await oldAttachmentFixture();
  await workAction('ops-work-detail', { dataset: { id: 'work1' } }, f.ctx);
  assert.match(f.dialog().body, /data-attachment="old-file" data-version="1"/);
  assert.match(f.dialog().body, /&lt;img src=x&gt;.txt/); assert.doesNotMatch(f.dialog().body, /<img src=x>/);
});

test('historical attachment download works after reassignment removes it from current result', async t => {
  const f = await oldAttachmentFixture(), before = structuredClone(f.data), link = { click() { this.clicked = true; } };
  t.mock.method(globalThis, 'setTimeout', fn => { fn(); return 0; });
  const previousDocument = globalThis.document;
  globalThis.document = { createElement: () => link };
  t.after(() => { if (previousDocument === undefined) delete globalThis.document; else globalThis.document = previousDocument; });
  await workAction('ops-attachment-download', { dataset: { id: 'work1', attachment: 'old-file', version: '1' } }, f.ctx);
  assert.equal(link.clicked, true); assert.equal(link.download, '<img src=x>.txt'); assert.match(link.href, /^blob:/);
  assert.deepEqual(f.data, before);
});

test('historical download rejects foreign attachment, missing version and tampered bytes', async () => {
  const f = await oldAttachmentFixture();
  await assert.rejects(workAction('ops-attachment-download', { dataset: { id: 'work1', attachment: 'other-file', version: '1' } }, f.ctx), /không thuộc phiên bản/);
  await assert.rejects(workAction('ops-attachment-download', { dataset: { id: 'work1', attachment: 'old-file', version: '2' } }, f.ctx), /Không tìm thấy phiên bản/);
  await assert.rejects(workAction('ops-attachment-download', { dataset: { id: 'work1', attachment: 'old-file', version: '../1' } }, f.ctx), /không hợp lệ/);
  f.data['workItems/work1/attachments/old-file/chunks'][0].data[0] = 1;
  await assert.rejects(workAction('ops-attachment-download', { dataset: { id: 'work1', attachment: 'old-file', version: '1' } }, f.ctx), /Mã kiểm tra/);
});
