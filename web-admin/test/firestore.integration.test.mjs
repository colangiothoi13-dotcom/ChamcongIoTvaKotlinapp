// Real rules verification with a local demo project. The ordinary npm test
// suite has no network dependency; opt in only with a loopback emulator host.
import test from 'node:test';
import assert from 'node:assert/strict';
import { assertEmulatorHost, createHarness } from './firestore-rest-helper.mjs';
import { saveWorkItem, reviewWorkItem, reviewRequest, assignSchedules, onAction as operationAction } from '../src/operations.js';
import { saveDepartment, retireOrDeleteFingerprint, onAction as organizationAction } from '../src/organization.js';
import { onAction as attendanceAction } from '../src/attendance.js';
import { dateAdd } from '../src/ui.js';

const host = process.env.WEB_FIRESTORE_EMULATOR_HOST;
if (host) assertEmulatorHost(host);
const harness = host ? createHarness(host) : null;
const options = { skip: !host, timeout: 120000 };
const suffix = `${Date.now()}-${process.pid}`;
let serial = 0;
const plain = value => { const { id, ...data } = value; return data; };
const DAY = '2026-10-12';

test('integration adapter refuses production, remote hosts, credentials and URL paths', () => {
  for (const remote of ['firestore.googleapis.com:443', '192.168.1.2:8088', 'https://127.0.0.1:8088', '127.0.0.1:8088/anything', 'owner@127.0.0.1:8088', 'localhost:99999']) assert.throws(() => assertEmulatorHost(remote), /loopback/);
  assert.equal(assertEmulatorHost('127.0.0.1:8088'), '127.0.0.1:8088');
});

async function fixture() {
  const prefix = `i-${suffix}-${++serial}`;
  const ctx = await harness.context(`${prefix}-admin`);
  const employeeId = `${prefix}-employee`, departmentId = `${prefix}-department`;
  const employee = { fullName: `Nhân viên ${prefix}`, code: 'NV9000', email: 'employee@example.test', phone: '', address: '',
    departmentId, department: `Bộ phận ${prefix}`, position: 'Kỹ sư', hireDate: '2026-01-01', terminationDate: '',
    baseSalary: 45000, fingerprintTemplateId: null, pendingTemplateId: null, fingerprintDeviceId: 'GATE-01', active: true, accountVersion: 0 };
  await harness.seed('employees', employeeId, employee);
  await harness.seed('departments', departmentId, { name: employee.department, active: true, createdAt: new Date(0), updatedAt: new Date(0) });
  const shift = { name: 'Ca sáng', category: 'MORNING', startTime: '08:00', endTime: '12:00', active: true,
    effectiveFrom: '1970-01-01', effectiveTo: null, allowEarlyMinutes: 120, lateGraceMinutes: 10, earlyLeaveAllowedMinutes: 0,
    missingCheckOutGraceMinutes: 30, breakStartTime: null, breakEndTime: null, countsOvertime: false };
  const morning = `${prefix}-morning`, evening = `${prefix}-evening`;
  await harness.seed('shifts', morning, shift);
  await harness.seed('shifts', evening, { ...shift, name: 'Ca chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00', allowEarlyMinutes: 0 });
  ctx.data.employees = [{ ...employee, id: employeeId }];
  ctx.loaded.employees = true;
  ctx.data.departments = [{ id: departmentId, name: employee.department, active: true }];
  ctx.data.shifts = [await ctx.store.get('shifts', morning), await ctx.store.get('shifts', evening)];
  ctx.filters = { from: DAY, to: DAY, month: '2026-10', week: DAY };
  ctx.loadRange = async (from, to) => {
    for (const collection of ['attendance', 'workSchedules', 'attendanceAdjustments', 'attendanceClassificationOverrides', 'offScheduleReviews', 'leaveRequests', 'overtimeRequests']) {
      ctx.data[collection] = (await ctx.store.list(collection, { where: [['employeeId', '==', employeeId]] })).rows;
    }
    ctx.range = { from, to, complete: true, loading: false, error: '' };
  };
  await ctx.loadRange('2026-10-01', '2026-10-31');
  return { ctx, employeeId, departmentId, morning, evening, prefix };
}

test('real rules accept employee code transaction and canonical department propagation', options, async () => {
  const { ctx, employeeId, departmentId, prefix } = await fixture();
  const oldCounter = await ctx.store.get('system', 'employeeCounter');
  await organizationAction('org-employee-add', { dataset: {} }, ctx);
  await ctx.dialog.onSubmit({ fullName: `Nội bộ ${prefix}`, email: '', phone: '0901234567', address: '', position: 'Nhân viên', hireDate: DAY, departmentId });
  const counter = await ctx.store.get('system', 'employeeCounter');
  assert.ok(counter.lastNumber > (oldCounter?.lastNumber || 0));
  const created = (await ctx.store.list('employees', { where: [['fullName', '==', `Nội bộ ${prefix}`]] })).rows[0];
  assert.match(created.code, /^NV\d{4,}$/);
  assert.equal(created.active, true);
  const query = { where: [['departmentId', '==', departmentId]], orderBy: [['fullName', 'asc']], limit: 1 };
  const firstPage = await ctx.store.list('employees', query);
  const secondPage = await ctx.store.list('employees', { ...query, startAfter: firstPage.cursor });
  const emptyPage = await ctx.store.list('employees', { ...query, startAfter: secondPage.cursor });
  assert.deepEqual(new Set([...firstPage.rows, ...secondPage.rows].map(row => row.id)), new Set([employeeId, created.id]));
  assert.equal(emptyPage.rows.length, 0);
  const newName = `Đổi tên ${prefix}`;
  await saveDepartment(ctx, departmentId, newName);
  assert.equal((await ctx.store.get('employees', employeeId)).department, newName);
  assert.equal((await ctx.store.get('employees', created.id)).department, newName);
  await organizationAction('org-department-toggle', { dataset: { id: departmentId } }, ctx);
  await ctx.confirm();
  assert.equal((await ctx.store.get('departments', departmentId)).active, false);
  await organizationAction('org-department-toggle', { dataset: { id: departmentId } }, ctx);
  await ctx.confirm();
  assert.equal((await ctx.store.get('departments', departmentId)).active, true);
  await organizationAction('org-employee-salary', { dataset: { id: employeeId } }, ctx);
  await ctx.dialog.onSubmit({ salary: '50000' });
  assert.equal((await ctx.store.get('employees', employeeId)).baseSalary, 50000);
});

test('real rules accept device configuration, command requests and reserved fingerprint enrollment', options, async () => {
  const { ctx, employeeId, prefix } = await fixture();
  const deviceId = `${prefix}-gate`;
  await harness.seed('devices', deviceId, { deviceId, name: 'Cổng thử nghiệm', location: 'Phòng lab', status: 'ONLINE', wifiStatus: 'ONLINE',
    lastHeartbeat: new Date(), capabilities: ['door', 'fingerprint'] });
  ctx.data.devices = [await ctx.store.get('devices', deviceId)];
  await organizationAction('org-device-edit', { dataset: { id: deviceId } }, ctx);
  await ctx.dialog.onSubmit({ name: 'Cổng lab', location: 'Tầng 1' });
  assert.equal((await ctx.store.get('devices', deviceId)).name, 'Cổng lab');
  for (const type of ['TEST_LED_GREEN', 'TEST_LED_RED', 'TEST_BUZZER', 'OPEN_DOOR', 'CLOSE_DOOR', 'SYNC_ATTENDANCE', 'RESTART_DEVICE']) {
    await organizationAction('org-device-command', { dataset: { id: deviceId, type } }, ctx);
    if (['OPEN_DOOR', 'RESTART_DEVICE'].includes(type)) await ctx.confirm();
    const command = await ctx.store.get('deviceCommands', deviceId);
    assert.equal(command.type, type);
    assert.equal(command.status, 'REQUESTED');
    await harness.owner.update('deviceCommands', deviceId, { status: 'COMPLETED', applied: true });
  }
  await organizationAction('org-fingerprint-enroll', { dataset: { id: employeeId } }, ctx);
  await ctx.dialog.onSubmit({ deviceId });
  const employee = await ctx.store.get('employees', employeeId);
  assert.ok(employee.pendingTemplateId >= 1 && employee.pendingTemplateId <= 127);
  assert.equal(employee.fingerprintDeviceId, deviceId);
  assert.equal((await ctx.store.get('fingerprintMappings', String(employee.pendingTemplateId))).enabled, false);
  assert.equal((await ctx.store.get('deviceCommands', deviceId)).type, 'ENROLL_FINGERPRINT');
});

test('real rules accept work creation, edit and approval with exact immutable history snapshots', options, async () => {
  const { ctx, employeeId } = await fixture();
  const draft = { title: 'Kiểm tra thiết bị', description: 'Kiểm tra toàn bộ tín hiệu cảm biến.', requiredResult: 'Báo cáo các kết quả đo.',
    assigneeId: employeeId, priority: 'NORMAL', startAt: new Date(`${DAY}T08:00:00+07:00`), deadline: new Date(`${DAY}T17:00:00+07:00`),
    relatedScheduleId: null, relatedShiftId: null, relatedScheduleDate: null };
  const id = await saveWorkItem(ctx, null, null, draft);
  const first = await ctx.store.get('workItems', id);
  assert.equal(first.version, 1);
  assert.deepEqual((await ctx.store.get(`workItems/${id}/history`, '1')).after, plain(first));
  await saveWorkItem(ctx, id, 1, { ...draft, title: 'Kiểm tra thiết bị cổng' });
  const edited = await ctx.store.get('workItems', id);
  const history = await ctx.store.get(`workItems/${id}/history`, '2');
  assert.deepEqual(history.before, plain(first));
  assert.deepEqual(history.after, plain(edited));
  await harness.owner.update('workItems', id, { status: 'PENDING_REVIEW', resultReport: 'Thiết bị hoạt động ổn định.', version: 3 });
  await reviewWorkItem(ctx, id, 3, true, 'Đã kiểm tra báo cáo.');
  assert.equal((await ctx.store.get('workItems', id)).status, 'COMPLETED');
  assert.equal((await ctx.store.get(`workItems/${id}/history`, '4')).action, 'APPROVED');
});

const leave = (employee, type = 'LEAVE') => ({ employeeId: employee.id, employeeName: employee.fullName, department: employee.department,
  type, startDate: DAY, endDate: DAY, reason: 'Cần xử lý theo kế hoạch.', status: 'PENDING', createdAt: new Date(0),
  proposedCheckIn: null, proposedCheckOut: null, requestedShiftId: null, requestedShiftName: null, appliedAdjustmentId: null,
  attachmentUrl: null, reviewerId: null, reviewerName: null, reviewedAt: null, reviewNote: null,
});

test('real rules accept scheduling, weekly approval, leave and shift-change decisions', options, async () => {
  const { ctx, employeeId, morning, evening, prefix } = await fixture();
  await assignSchedules(ctx, [employeeId], [DAY], [morning, evening]);
  assert.deepEqual((await ctx.store.get('workSchedules', `${employeeId}_${DAY}`)).shiftIds, [morning, evening]);
  const employee = ctx.data.employees[0];
  const weeklyId = `${employeeId}_${DAY}`;
  await harness.seed('weeklyScheduleRequests', weeklyId, { id: weeklyId, employeeId, employeeName: employee.fullName, department: employee.department,
    weekStart: DAY, shiftsByDate: Object.fromEntries(Array.from({ length: 6 }, (_, i) => [dateAdd(DAY, i), i === 0 ? [morning] : []])),
    status: 'PENDING', reason: '', reviewNote: null, reviewerId: null, reviewerName: null, createdAt: new Date(0), reviewedAt: null });
  await reviewRequest(ctx, 'weekly', weeklyId, true, 'Lịch đã được kiểm tra.');
  assert.equal((await ctx.store.get('weeklyScheduleRequests', weeklyId)).status, 'APPROVED');
  assert.deepEqual((await ctx.store.get('workSchedules', weeklyId)).shiftIds, [morning]);
  const leaveId = `${prefix}-leave`;
  await harness.seed('leaveRequests', leaveId, leave(employee));
  await reviewRequest(ctx, 'leave', leaveId, true, 'Đồng ý nghỉ.');
  assert.equal((await ctx.store.get('audit_logs', `${leaveId}_REVIEW`)).action, 'LEAVE_REVIEW');
  const changeId = `${prefix}-change`;
  await harness.seed('leaveRequests', changeId, { ...leave(employee, 'SHIFT_CHANGE'), requestedShiftId: evening, requestedShiftName: 'Ca chiều' });
  await reviewRequest(ctx, 'leave', changeId, true, 'Đồng ý đổi ca.');
  assert.equal((await ctx.store.get('workSchedules', weeklyId)).shiftId, evening);
  assert.equal((await ctx.store.get('audit_logs', `${changeId}_REVIEW`)).targetType, 'workSchedule');
});

test('real rules accept adjustment requests and overtime decisions with deterministic audits', options, async () => {
  const { ctx, employeeId, prefix } = await fixture();
  const adjustmentRequest = `${prefix}-adjust`;
  await harness.seed('leaveRequests', adjustmentRequest, { ...leave(ctx.data.employees[0], 'ATTENDANCE_ADJUSTMENT'), proposedCheckIn: '08:00', proposedCheckOut: '12:00' });
  await reviewRequest(ctx, 'leave', adjustmentRequest, true, 'Đã xác minh.');
  const applied = (await ctx.store.get('leaveRequests', adjustmentRequest)).appliedAdjustmentId;
  assert.ok(applied);
  assert.equal((await ctx.store.get('audit_logs', applied)).action, 'ATTENDANCE_ADJUST');
  const overtimeId = `${employeeId}_${DAY}`;
  const employee = ctx.data.employees[0];
  await harness.seed('overtimeRequests', overtimeId, { employeeId, employeeName: employee.fullName, department: employee.department, workDate: DAY,
    startTime: '18:00', endTime: '22:00', reason: 'Hỗ trợ triển khai.', status: 'PENDING', createdAt: new Date(0), reviewerId: null, reviewerName: null, reviewedAt: null, rejectionReason: null });
  await reviewRequest(ctx, 'overtime', overtimeId, false, 'Đổi ngày triển khai.');
  assert.equal((await ctx.store.get('audit_logs', `${overtimeId}_OVERTIME_REVIEW`)).status, 'REJECTED');
  await operationAction('ops-overtime-new', { dataset: {} }, ctx);
  const nextDay = dateAdd(DAY, 1);
  await ctx.dialog.onSubmit({ employeeId, date: nextDay, reason: 'Phân ca bổ sung cho triển khai.' });
  assert.equal((await ctx.store.get('overtimeRequests', `${employeeId}_${nextDay}`)).status, 'APPROVED');
  assert.equal((await ctx.store.get('audit_logs', `${employeeId}_${nextDay}_OVERTIME_ASSIGN`)).action, 'OVERTIME_ASSIGN');
});

test('real rules accept attendance correction dialogs and immutable payroll revision archive', options, async () => {
  const { ctx, employeeId, morning, prefix } = await fixture();
  const scanId = `${prefix}-scan`;
  const employee = ctx.data.employees[0];
  await harness.seed('attendance', scanId, { employeeId, employeeName: employee.fullName, deviceId: 'GATE-01', type: 'CHECK_IN', status: 'ON_TIME',
    resolutionStatus: 'ACCEPTED', verified: true, timestamp: new Date(`${DAY}T08:00:00+07:00`), scheduleDate: DAY, shiftId: morning, syncStatus: 'SYNCED' });
  await ctx.loadRange('2026-10-01', '2026-10-31');
  await attendanceAction('attendance-adjust', { dataset: {} }, ctx);
  await ctx.dialog.onSubmit({ employeeId, scheduleDate: DAY, checkInAt: `${DAY}T08:00`, checkOutAt: `${DAY}T12:00`, workedHoursOverride: '', reason: 'Bổ sung giờ ra đã xác minh.' });
  assert.equal((await ctx.store.list('attendanceAdjustments', { where: [['employeeId', '==', employeeId]] })).rows.length, 1);
  await attendanceAction('attendance-classify', { dataset: { id: scanId } }, ctx);
  await ctx.dialog.onSubmit({ scheduleDate: DAY, correctedType: 'CHECK_IN', correctedStatus: 'LATE', reason: 'Đã đối chiếu thời gian thực tế.' });
  assert.equal((await ctx.store.list('attendanceClassificationOverrides', { where: [['attendanceId', '==', scanId]] })).rows.length, 1);
  await attendanceAction('attendance-offschedule', { dataset: { id: scanId } }, ctx);
  await ctx.dialog.onSubmit({ scheduleDate: DAY, shiftId: morning, decision: 'REJECT', reason: 'Không ghi nhận ngoài lịch.' });
  assert.equal((await ctx.store.list('offScheduleReviews', { where: [['employeeId', '==', employeeId]] })).rows.length, 1);
  await ctx.loadRange('2026-10-01', '2026-10-31');
  await attendanceAction('payroll-create', { dataset: { id: employeeId } }, ctx);
  await ctx.dialog.onSubmit({ hoursWorked: '4', bonus: '100000', deduction: '0' });
  const payrollId = `${employeeId}_2026-10`;
  ctx.data.payroll = [await ctx.store.get('payroll', payrollId)];
  assert.equal(ctx.data.payroll[0].baseSalary, 180000);
  await attendanceAction('payroll-recalculate', { dataset: { id: payrollId } }, ctx);
  await ctx.dialog.onSubmit({ hoursWorked: '5', bonus: '100000', deduction: '0', reason: 'Cập nhật công đã đối chiếu.' });
  assert.equal((await ctx.store.get('payroll', payrollId)).revision, 1);
  assert.equal((await ctx.store.get(`payroll/${payrollId}/revisions`, '1')).previous.hoursWorked, 4);
  // Even admins append corrections: raw device records and prior revisions stay immutable.
  await assert.rejects(ctx.store.update('attendance', scanId, { status: 'NORMAL' }), error => error.status === 403);
  await assert.rejects(ctx.store.update(`payroll/${payrollId}/revisions`, '1', { reason: 'rewrite' }), error => error.status === 403);
  assert.equal((await ctx.store.get('attendance', scanId)).status, 'ON_TIME');
});

test('real rules accept retirement and reserved deletion; employee writes remain denied', options, async () => {
  const { ctx, employeeId, prefix } = await fixture();
  const employeeAccount = await harness.context(`${prefix}-account`, 'EMPLOYEE', employeeId);
  await harness.owner.update('employees', employeeId, { fingerprintTemplateId: 107 });
  await harness.seed('fingerprintMappings', '107', { employeeId, employeeName: ctx.data.employees[0].fullName, templateId: 107, enabled: true });
  await harness.seed('deviceCommands', 'GATE-01', { deviceId: 'GATE-01', status: 'COMPLETED', applied: true });
  await assert.rejects(employeeAccount.store.update('employees', employeeId, { baseSalary: 999999 }), error => error.status === 403);
  await assert.rejects(employeeAccount.store.update('users', employeeAccount.user.uid, { role: 'ADMIN' }), error => error.status === 403);
  await assert.rejects(employeeAccount.store.set('deviceCommands', 'GATE-01', { deviceId: 'GATE-01', requestId: 'forbidden', type: 'OPEN_DOOR', status: 'REQUESTED', applied: true }), error => error.status === 403);
  await retireOrDeleteFingerprint(ctx, employeeId, true);
  assert.equal((await ctx.store.get('employees', employeeId)).active, false);
  assert.equal((await ctx.store.get('users', employeeAccount.user.uid)).active, false);
  assert.equal((await ctx.store.get('fingerprintMappings', '107')).enabled, false);
  assert.equal((await ctx.store.get('deviceCommands', 'GATE-01')).type, 'DELETE_FINGERPRINT');
});
