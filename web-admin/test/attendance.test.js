import test from 'node:test';
import assert from 'node:assert/strict';
import { onAction, render } from '../src/attendance.js';
import { at, daySummary } from '../src/domain.js';
import { memoryStore } from '../src/demo.js';

function fixture() {
  const employee = { id: 'e1', code: 'NV0001', fullName: 'Nguyễn An', active: true, department: 'Kỹ thuật', baseSalary: 25000 };
  const date = '2026-10-10', stamp = time => new Date(at(date, time));
  const data = {
    employees: [employee], shifts: [{ id: 'am', name: 'Sáng', category: 'MORNING', startTime: '08:00', endTime: '12:00', active: true }],
    workSchedules: [{ id: `e1_${date}`, employeeId: 'e1', date, shiftId: 'am' }],
    attendance: [{ id: 'scan1', employeeId: 'e1', employeeName: employee.fullName, timestamp: stamp('08:00'), type: 'SCAN', status: 'PENDING', resolutionStatus: 'PENDING', verified: true }, { id: 'scan2', employeeId: 'e1', employeeName: employee.fullName, timestamp: stamp('12:00'), type: 'SCAN', status: 'PENDING', resolutionStatus: 'PENDING', verified: true }],
    payroll: [{ id: 'e1_2026-10', employeeId: 'e1', employeeCode: employee.code, employeeName: employee.fullName, month: '2026-10', hoursWorked: 1, hourlyRate: 10000, baseSalary: 10000, bonus: 20000, deduction: 0 }],
    attendanceAdjustments: [], attendanceClassificationOverrides: [], offScheduleReviews: [], leaveRequests: [], overtimeRequests: [], performanceReviews: [], workItems: [], audit_logs: []
  };
  let dialog, batches = [];
  const store = memoryStore(data);
  const batch = store.batch;
  store.batch = async operations => { batches.push(operations); return batch(operations); };
  const ctx = { data, store, user: { uid: 'admin', displayName: 'Quản lý' }, filters: { month: '2026-10', from: date, to: date }, loaded: { employees: true, workItems: true, payroll: true }, errors: {}, range: { from: '2026-10-01', to: '2026-10-31', complete: true, loading: false }, ui: { dialog: value => { dialog = value; }, toast: () => {} }, render: () => {}, loadRange: async (from, to) => { ctx.range = { from, to, complete: true, loading: false }; }, audit: (action, targetType, targetId, details, reason = '') => ({ action, targetType, targetId, details, reason, actorId: 'admin', actorName: 'Quản lý', createdAt: store.stamp() }) };
  return { ctx, employee, data, dialog: () => dialog, batches: () => batches };
}

test('classification appends a correction plus matching same-ID audit without modifying raw scan', async () => {
  const f = fixture(), before = structuredClone(f.data.attendance);
  assert.equal(await onAction('attendance-classify', { dataset: { id: 'scan1' } }, f.ctx), true);
  await f.dialog().onSubmit({ scheduleDate: '2026-10-10', correctedType: 'CHECK_IN', correctedStatus: 'NORMAL', reason: 'Xác minh vào ca' });
  assert.deepEqual(f.data.attendance, before);
  const ops = f.batches()[0];
  assert.equal(ops.length, 2); assert.equal(ops[0].collection, 'attendanceClassificationOverrides');
  assert.equal(ops[1].collection, 'audit_logs'); assert.equal(ops[0].id, ops[1].id);
  assert.equal(ops[1].data.action, 'ATTENDANCE_CLASSIFICATION_CORRECT');
  assert.equal(ops[1].data.targetId, ops[0].id); assert.equal(ops[1].data.reason, ops[0].data.reason);
  assert.equal(ops[0].data.sourceType, 'SCAN'); assert.equal(ops[0].data.previousType, 'SCAN');
});
test('adjustment persists Vietnamese local time and atomically pairs its audit', async () => {
  const f = fixture();
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  await f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-10-10', checkInAt: '2026-10-10T08:00', checkOutAt: '2026-10-10T12:00', workedHoursOverride: '', reason: 'Bổ sung giờ vào và ra' });
  const ops = f.batches()[0];
  assert.equal(ops[0].id, ops[1].id); assert.equal(ops[0].data.checkInAt.getTime(), at('2026-10-10', '08:00'));
  assert.equal(ops[0].data.workedHoursOverride, null); assert.equal(ops[1].data.action, 'ATTENDANCE_ADJUST');
  assert.equal(f.data.attendance[0].type, 'SCAN');
});
test('invalid adjustment is rejected before any write', async () => {
  const f = fixture();
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  await assert.rejects(f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-10-10', checkInAt: '2026-10-10T12:00', checkOutAt: '2026-10-10T08:00', workedHoursOverride: '', reason: 'Sửa giờ' }), /Giờ ra/);
  assert.equal(f.batches().length, 0);
});
test('payroll preview preserves saved slip until explicit recalculation archives exact before and after', async () => {
  const f = fixture(), before = structuredClone(f.data.payroll[0]);
  await onAction('payroll-recalculate', { dataset: { id: before.id } }, f.ctx);
  assert.deepEqual(f.data.payroll[0], before);
  assert.match(f.dialog().body, /Xem|Phiếu đã lưu/);
  await f.dialog().onSubmit({ hoursWorked: '4', bonus: '50000', deduction: '0', reason: 'Bổ sung công đã xác minh' });
  assert.equal(f.data.payroll[0].baseSalary, 100000); assert.equal(f.data.payroll[0].revision, 1);
  const ops = f.batches()[0];
  assert.equal(ops.length, 3);
  const archive = ops.find(o => o.collection === 'payroll/e1_2026-10/revisions').data;
  assert.equal(archive.previous.baseSalary, before.baseSalary); assert.equal(archive.next.baseSalary, 100000);
  assert.equal(archive.revision, 1); assert.equal(archive.reason, 'Bổ sung công đã xác minh');
  assert.equal('id' in archive.previous, false); assert.equal('revision' in archive.previous, false);
  assert.equal('id' in archive.next, false);
  assert.equal(ops.find(o => o.collection === 'audit_logs').data.targetId, before.id);
});
test('payroll salary conflict prevents both new slip and history writes', async () => {
  const f = fixture(), before = structuredClone(f.data.payroll[0]);
  await onAction('payroll-recalculate', { dataset: { id: before.id } }, f.ctx);
  f.employee.baseSalary = 30000;
  await assert.rejects(f.dialog().onSubmit({ hoursWorked: '4', bonus: '50000', deduction: '0', reason: 'Tính lại' }), /đã thay đổi/);
  assert.deepEqual(f.data.payroll[0], before); assert.equal(f.batches().length, 0);
});
test('a reused revision is rejected before committing payroll', async () => {
  const f = fixture(), before = structuredClone(f.data.payroll[0]);
  await onAction('payroll-recalculate', { dataset: { id: before.id } }, f.ctx);
  f.data['payroll/e1_2026-10/revisions'] = [{ id: '1', revision: 1 }];
  await assert.rejects(f.dialog().onSubmit({ hoursWorked: '4', bonus: '50000', deduction: '0', reason: 'Tính lại' }), /đã tồn tại/);
  assert.deepEqual(f.data.payroll[0], before); assert.equal(f.batches().length, 0);
});
test('new payroll creation checks absence and rate in a transaction', async () => {
  const f = fixture(); f.data.payroll = [];
  await onAction('payroll-create', { dataset: { id: 'e1' } }, f.ctx);
  await f.dialog().onSubmit({ hoursWorked: '4', bonus: '0', deduction: '0' });
  const slip = f.data.payroll[0];
  assert.equal(slip.id, 'e1_2026-10'); assert.equal(slip.baseSalary, 100000); assert.equal(slip.revision, 0);
  assert.equal(f.batches()[0].length, 2);
});
test('saved payroll remains visible with incomplete calculation data and no automatic writes', () => {
  const f = fixture(); f.ctx.range.complete = false;
  const html = render('payroll', f.ctx);
  assert.match(html, /NV0001/); assert.match(html, /Phiếu đã lưu|phiếu lương|Phiếu/);
  assert.equal(f.batches().length, 0);
  for (const page of ['attendance', 'presence', 'reports', 'performance']) assert.equal(typeof render(page, f.ctx), 'string');
});

const inputValue = (body, name) => body.match(new RegExp(`<input[^>]*name="${name}"[^>]*value="([^"]*)"`))?.[1];
const contextualAdjustment = (id = 'scan1') => ({ dataset: { id, employee: 'e1', date: '2026-10-10' } });
const adjustmentValues = body => ({ employeeId: inputValue(body, 'employeeId'), scheduleDate: inputValue(body, 'scheduleDate'), checkInAt: inputValue(body, 'checkInAt'), checkOutAt: inputValue(body, 'checkOutAt'), workedHoursOverride: inputValue(body, 'workedHoursOverride'), reason: 'Xác minh giờ công' });

test('attendance rows and detail expose contextual adjustment controls while opening remains read-only', async () => {
  const f = fixture(), before = structuredClone(f.data);
  const html = render('attendance', f.ctx);
  assert.match(html, /class="btn primary" data-action="attendance-adjust"/);
  assert.match(html, /data-action="attendance-adjust"[^>]*data-id="scan1"[^>]*data-employee="e1"[^>]*data-date="2026-10-10"/);
  await onAction('attendance-detail', { dataset: { id: 'scan1' } }, f.ctx);
  assert.match(f.dialog().body, /data-action="attendance-adjust"[^>]*data-employee="e1"[^>]*data-date="2026-10-10"/);
  assert.deepEqual(f.data, before); assert.equal(f.batches().length, 0);
});

test('contextual adjustment prefills current effective pair, keeps timestamp precision and appends audited correction', async () => {
  const f = fixture(), before = structuredClone(f.data.attendance);
  f.data.attendance[0].timestamp = new Date(at('2026-10-10', '08:00') + 12345);
  await onAction('attendance-adjust', contextualAdjustment(), f.ctx);
  const body = f.dialog().body, values = adjustmentValues(body);
  assert.equal(values.employeeId, 'e1'); assert.equal(values.scheduleDate, '2026-10-10');
  assert.equal(values.checkInAt, '2026-10-10T08:00:12.345');
  assert.equal(values.checkOutAt, '2026-10-10T12:00:00.000');
  assert.equal(values.workedHoursOverride, '');
  assert.match(body, /name="scheduleDate"[^>]*readonly/);
  assert.equal(f.batches().length, 0);
  await f.dialog().onSubmit({ ...values, checkOutAt: '2026-10-10T11:30:00.000' });
  const correction = f.data.attendanceAdjustments[0];
  assert.equal(correction.checkInAt.getTime(), at('2026-10-10', '08:00') + 12345);
  assert.equal(correction.checkOutAt.getTime(), at('2026-10-10', '11:30'));
  assert.equal(correction.workedHoursOverride, null);
  assert.equal(f.batches()[0][0].id, f.batches()[0][1].id);
  assert.deepEqual(f.data.attendance[1], before[1]);
  assert.equal(f.data.attendance[0].type, 'SCAN');
  assert.equal(f.data.attendance[0].timestamp.getTime(), at('2026-10-10', '08:00') + 12345);
});

test('contextual adjustment rejects changing employee or day before writes', async () => {
  const f = fixture();
  await onAction('attendance-adjust', contextualAdjustment(), f.ctx);
  const values = adjustmentValues(f.dialog().body);
  await assert.rejects(f.dialog().onSubmit({ ...values, employeeId: 'e2' }), /phải giữ nguyên/);
  await assert.rejects(f.dialog().onSubmit({ ...values, scheduleDate: '2026-10-11' }), /phải giữ nguyên/);
  assert.equal(f.batches().length, 0);
});

test('multishift contextual adjustment uses its resolved shift pair and preserves the other shift', async () => {
  const f = fixture();
  f.data.shifts.push({ id: 'pm', name: 'Chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00', active: true });
  f.data.workSchedules[0].shiftIds = ['am', 'pm'];
  f.data.attendance.push(...[['scan3', '13:00'], ['scan4', '17:00']].map(([id, time]) => ({ ...f.data.attendance[0], id, timestamp: new Date(at('2026-10-10', time)) })));
  const before = structuredClone(f.data.attendance);
  await onAction('attendance-adjust', contextualAdjustment('scan3'), f.ctx);
  const values = adjustmentValues(f.dialog().body);
  assert.equal(values.checkInAt, '2026-10-10T13:00:00.000');
  assert.equal(values.checkOutAt, '2026-10-10T17:00:00.000');
  assert.equal(values.workedHoursOverride, '');
  await f.dialog().onSubmit({ ...values, checkInAt: '2026-10-10T13:30:00.000' });
  const summary = daySummary('e1', '2026-10-10', f.data);
  assert.equal(summary.shiftSummaries.find(shift => shift.shiftId === 'am').workedHours, 4);
  assert.equal(summary.shiftSummaries.find(shift => shift.shiftId === 'pm').workedHours, 3.5);
  assert.deepEqual(f.data.attendance, before);
});

test('ambiguous multishift day adjustment leaves times blank instead of using the full-day aggregate', async () => {
  const f = fixture();
  f.data.shifts.push({ id: 'pm', name: 'Chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00', active: true });
  f.data.workSchedules[0].shiftIds = ['am', 'pm'];
  await onAction('attendance-adjust', contextualAdjustment(''), f.ctx);
  const values = adjustmentValues(f.dialog().body);
  assert.equal(values.checkInAt, ''); assert.equal(values.checkOutAt, '');
  assert.match(f.dialog().body, /nhiều ca/); assert.equal(f.batches().length, 0);
});

test('contextual prefill uses a previous adjustment without clearing its explicit hours override', async () => {
  const f = fixture();
  f.data.attendanceAdjustments.push({ id: 'previous', employeeId: 'e1', employeeName: f.employee.fullName, scheduleDate: '2026-10-10', checkInAt: new Date(at('2026-10-10', '08:15')), checkOutAt: new Date(at('2026-10-10', '11:45')), workedHoursOverride: 3, reason: 'Xác minh', actorId: 'admin', actorName: 'Quản lý', createdAt: new Date() });
  await onAction('attendance-adjust', contextualAdjustment(), f.ctx);
  const values = adjustmentValues(f.dialog().body);
  assert.equal(values.checkInAt, '2026-10-10T08:15:00.000');
  assert.equal(values.checkOutAt, '2026-10-10T11:45:00.000');
  assert.equal(values.workedHoursOverride, '3');
  assert.equal(f.batches().length, 0);
});

test('all-saved payroll chooser offers recalculation and hourly-rate management without mutating slips', async () => {
  const f = fixture(), before = structuredClone(f.data.payroll);
  assert.match(render('payroll', f.ctx), /class="btn primary" data-action="payroll-create"/);
  await onAction('payroll-create', { dataset: {} }, f.ctx);
  assert.equal(f.dialog().submitLabel, null);
  assert.match(f.dialog().body, /data-action="payroll-recalculate"[^>]*data-id="e1_2026-10"/);
  assert.match(f.dialog().body, /data-action="org-employee-salary"[^>]*data-id="e1"/);
  assert.deepEqual(f.data.payroll, before); assert.equal(f.batches().length, 0);
  await onAction('payroll-create', { dataset: { id: 'e1' } }, f.ctx);
  assert.match(f.dialog().title, /Xem trước tính lại/);
  assert.deepEqual(f.data.payroll, before); assert.equal(f.batches().length, 0);
});

test('salary picker is available without calculation data and routes existing audited salary flow', async () => {
  const f = fixture(); f.ctx.range.complete = false;
  assert.match(render('payroll', f.ctx), /data-action="payroll-salary-picker"/);
  await onAction('payroll-salary-picker', { dataset: {} }, f.ctx);
  assert.match(f.dialog().body, /data-action="org-employee-salary"[^>]*data-id="e1"/);
  assert.equal(f.dialog().submitLabel, null); assert.equal(f.batches().length, 0);
});

test('saving a contextual adjustment reloads the full active attendance range', async () => {
  const f = fixture(); f.ctx.filters.from = '2026-10-09'; f.ctx.filters.to = '2026-10-11';
  await onAction('attendance-adjust', contextualAdjustment(), f.ctx);
  await f.dialog().onSubmit(adjustmentValues(f.dialog().body));
  assert.equal(f.ctx.range.from, '2026-10-09'); assert.equal(f.ctx.range.to, '2026-10-11');
  assert.equal(f.ctx.filters.from, '2026-10-09'); assert.equal(f.ctx.filters.to, '2026-10-11');
  assert.match(render('attendance', f.ctx), /NV0001/);
});

test('saving an adjustment outside the current range shows and reloads the corrected day', async () => {
  const f = fixture();
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  await f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-10-11', checkInAt: '2026-10-11T08:00', checkOutAt: '2026-10-11T12:00', workedHoursOverride: '', reason: 'Bổ sung công ngày khác' });
  assert.equal(f.ctx.filters.from, '2026-10-11'); assert.equal(f.ctx.filters.to, '2026-10-11');
  assert.equal(f.ctx.range.from, '2026-10-11'); assert.equal(f.ctx.range.to, '2026-10-11');
});

const employeeOptions = body => body.match(/<select name="employeeId">([\s\S]*?)<\/select>/)?.[1] || '';

test('adjustment picker hides prior-month retirements and invalid retirement dates while labelling departure-month employees', async () => {
  const f = fixture();
  f.data.employees.push(
    { ...f.employee, id: 'retired-september', fullName: 'Nghỉ tháng trước', active: false, terminationDate: '2026-09-30' },
    { ...f.employee, id: 'retired-october', fullName: 'Nghỉ trong tháng', active: false, terminationDate: '2026-10-01' },
    { ...f.employee, id: 'retired-missing-date', active: false },
    { ...f.employee, id: 'retired-invalid-date', active: false, terminationDate: '2026-09-99' },
    { ...f.employee, id: 'retired-empty-date', active: false, terminationDate: '' }
  );
  const before = structuredClone(f.data);
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  const options = employeeOptions(f.dialog().body);
  assert.match(options, /value="e1"/);
  assert.match(options, /value="retired-october"[^>]*>NV0001 · Nghỉ trong tháng · Đã nghỉ<\/option>/);
  for (const id of ['retired-september', 'retired-missing-date', 'retired-invalid-date', 'retired-empty-date']) assert.doesNotMatch(options, new RegExp(`value="${id}"`));
  assert.deepEqual(f.data, before);
  assert.equal(f.batches().length, 0);
});

test('adjustment picker clears a stale employee filter when the selected employee retired before that month', async () => {
  const f = fixture();
  Object.assign(f.employee, { active: false, terminationDate: '2026-09-30' });
  f.ctx.filters.employeeId = 'e1';
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  const options = employeeOptions(f.dialog().body);
  assert.match(options, /<option value="" selected>Chọn nhân viên<\/option>/);
  assert.doesNotMatch(options, /value="e1"/);
  assert.equal(inputValue(f.dialog().body, 'employeeId'), undefined);
  assert.equal(f.batches().length, 0);
});

test('changing adjustment date restores historical employee options and clears ineligible selection without resetting entered fields', async () => {
  const f = fixture();
  f.data.employees.push({ ...f.employee, id: 'retired', fullName: 'Nhân viên cũ', active: false, terminationDate: '2026-09-30' });
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  const dialog = f.dialog(), fields = {
    employeeId: { value: 'e1', innerHTML: employeeOptions(dialog.body) },
    scheduleDate: { name: 'scheduleDate', value: '2026-09-20' },
    reason: { value: 'Đã xác minh với quản lý' },
    workedHoursOverride: { value: '3.5' },
    checkInAt: { value: '2026-09-20T08:15' }
  };
  const form = { elements: { namedItem: name => fields[name] } };
  dialog.onChange({ target: fields.scheduleDate }, form);
  assert.match(fields.employeeId.innerHTML, /value="retired">NV0001 · Nhân viên cũ · Đã nghỉ<\/option>/);
  assert.equal(fields.employeeId.value, 'e1');
  fields.employeeId.value = 'retired'; fields.scheduleDate.value = '2026-10-10';
  dialog.onChange({ target: fields.scheduleDate }, form);
  assert.doesNotMatch(fields.employeeId.innerHTML, /value="retired"/);
  assert.equal(fields.employeeId.value, '');
  assert.equal(fields.reason.value, 'Đã xác minh với quản lý');
  assert.equal(fields.workedHoursOverride.value, '3.5');
  assert.equal(fields.checkInAt.value, '2026-09-20T08:15');
  assert.equal(f.dialog(), dialog); assert.equal(f.batches().length, 0);
});

test('submitting a hidden retired employee rejects both the adjustment and audit before writing', async () => {
  const f = fixture();
  Object.assign(f.employee, { active: false, terminationDate: '2026-09-30' });
  const before = structuredClone(f.data);
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  await assert.rejects(f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-10-10', checkInAt: '', checkOutAt: '', workedHoursOverride: '4', reason: 'Sửa giờ công' }), /đã nghỉ trước tháng/);
  assert.equal(f.batches().length, 0); assert.deepEqual(f.data, before);
});

test('adjustment save checks current server retirement status when the loaded employee list is stale', async () => {
  const f = fixture();
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  const originalGet = f.ctx.store.get;
  f.ctx.store.get = async (collection, id) => collection === 'employees' && id === 'e1'
    ? { ...f.employee, active: false, terminationDate: '2026-09-30' }
    : originalGet(collection, id);
  await assert.rejects(f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-10-10', checkInAt: '', checkOutAt: '', workedHoursOverride: '4', reason: 'Sửa giờ công' }), /đã nghỉ trước tháng/);
  assert.equal(f.employee.active, true);
  assert.equal(f.batches().length, 0);
  assert.deepEqual(f.data.attendanceAdjustments, []); assert.deepEqual(f.data.audit_logs, []);
});

test('retired employee remains eligible for a generic adjustment in the historical departure month', async () => {
  const f = fixture();
  Object.assign(f.employee, { active: false, terminationDate: '2026-09-15' });
  f.ctx.filters.from = '2026-09-20'; f.ctx.filters.to = '2026-09-20';
  const beforeAttendance = structuredClone(f.data.attendance), beforePayroll = structuredClone(f.data.payroll);
  await onAction('attendance-adjust', { dataset: {} }, f.ctx);
  assert.match(employeeOptions(f.dialog().body), /value="e1"[^>]*>NV0001 · Nguyễn An · Đã nghỉ<\/option>/);
  await f.dialog().onSubmit({ employeeId: 'e1', scheduleDate: '2026-09-20', checkInAt: '', checkOutAt: '', workedHoursOverride: '4', reason: 'Chốt lại công tháng nghỉ việc' });
  assert.equal(f.data.attendanceAdjustments[0].scheduleDate, '2026-09-20');
  assert.equal(f.data.attendanceAdjustments[0].workedHoursOverride, 4);
  assert.equal(f.batches()[0][0].id, f.batches()[0][1].id);
  assert.equal(f.data.audit_logs[0].action, 'ATTENDANCE_ADJUST');
  assert.deepEqual(f.data.attendance, beforeAttendance); assert.deepEqual(f.data.payroll, beforePayroll);
});

test('contextual adjustment preserves access to retired employee attendance history', async () => {
  const f = fixture(), historicalDate = '2026-09-10';
  Object.assign(f.employee, { active: false, terminationDate: '2026-09-15' });
  f.data.workSchedules[0].date = historicalDate;
  f.data.attendance[0].timestamp = new Date(at(historicalDate, '08:00'));
  f.data.attendance[1].timestamp = new Date(at(historicalDate, '12:00'));
  const before = structuredClone(f.data.attendance);
  await onAction('attendance-adjust', { dataset: { id: 'scan1', employee: 'e1', date: historicalDate } }, f.ctx);
  assert.equal(employeeOptions(f.dialog().body), '');
  assert.match(f.dialog().body, /name="employeeName"[^>]*readonly/);
  assert.equal(inputValue(f.dialog().body, 'employeeId'), 'e1');
  await f.dialog().onSubmit(adjustmentValues(f.dialog().body));
  assert.equal(f.data.attendanceAdjustments[0].scheduleDate, historicalDate);
  assert.equal(f.batches()[0][0].id, f.batches()[0][1].id);
  assert.equal(f.data.audit_logs[0].action, 'ATTENDANCE_ADJUST');
  assert.deepEqual(f.data.attendance, before);
});
