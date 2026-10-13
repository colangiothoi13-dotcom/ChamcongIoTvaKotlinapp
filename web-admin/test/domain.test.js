import test from 'node:test';
import assert from 'node:assert/strict';
import { at, resolveSpark, effectiveAttendance, reportRows, monthlyTotals, daySummary, presenceRows, payrollPreview, basePay, SUPPLEMENTARY, workMetrics, validDate, accepted } from '../src/domain.js';

const date = '2026-10-10';
const employee = { id: 'e1', code: 'NV0001', fullName: 'Nguyễn An', department: 'Kỹ thuật', active: true, baseSalary: 25000 };
const shifts = [
  { id: 'am', name: 'Sáng', category: 'MORNING', startTime: '08:00', endTime: '12:00', lateGraceMinutes: 5 },
  { id: 'pm', name: 'Chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00', lateGraceMinutes: 0 }
];
const raw = (id, time, extra = {}) => ({ id, employeeId: employee.id, employeeName: employee.fullName, type: 'SCAN', status: 'PENDING', resolutionStatus: 'PENDING', verified: true, timestamp: at(date, time), ...extra });
const data = rows => ({ employees: [employee], shifts, workSchedules: [{ id: `e1_${date}`, employeeId: 'e1', date, shiftId: 'am', shiftIds: ['am', 'pm'] }], attendance: rows });
const now = at('2026-10-11', '09:00');

test('Spark resolves four scans into separate morning and afternoon sessions without altering raw data', () => {
  const input = [raw('4', '17:30'), raw('1', '07:30'), raw('3', '13:00'), raw('2', '12:00')], d = data(input);
  const resolved = effectiveAttendance(d);
  assert.equal(resolved.find(r => r.id === '1').type, 'CHECK_IN');
  assert.equal(resolved.find(r => r.id === '2').type, 'CHECK_OUT');
  assert.equal(resolved.find(r => r.id === '3').shiftId, 'pm');
  assert.equal(resolved.find(r => r.id === '4').type, 'CHECK_OUT');
  assert.ok(input.every(r => r.type === 'SCAN' && r.scheduleDate == null));
  const summary = daySummary('e1', date, d, resolved, now);
  assert.equal(summary.workedHours, 8);
  assert.equal(summary.overtimeHours, .5);
  assert.equal(summary.shiftSummaries.length, 2);
});
test('three-minute duplicate boundary is inclusive and a duplicate does not spoil valid work', () => {
  const d = data([raw('1', '08:00'), raw('2', '08:03'), raw('3', '12:00')]);
  const rows = effectiveAttendance(d);
  assert.equal(rows[1].type, 'DUPLICATE');
  assert.equal(daySummary('e1', date, d, rows, now).shiftSummaries[0].status, 'ON_TIME');
});
test('Spark with no candidate keeps raw pending; accepted legacy data remains readable', () => {
  const input = [raw('1', '08:00')];
  assert.equal(resolveSpark(input, [], []).at(0).resolutionStatus, 'PENDING');
  const d = { employees: [employee], attendance: [raw('1', '08:00', { type: 'CHECK_IN', status: 'NORMAL', resolutionStatus: 'ACCEPTED' }), raw('2', '12:00', { type: 'CHECK_OUT', status: 'NORMAL', resolutionStatus: 'ACCEPTED' })] };
  assert.equal(daySummary('e1', date, d, null, now).workedHours, 4);
});
test('classification override seeds a session before the next raw scan resolves', () => {
  const source = raw('1', '08:00');
  const correction = { attendanceId: '1', employeeId: 'e1', employeeName: employee.fullName, scheduleDate: date, sourceTimestamp: source.timestamp, sourceType: 'SCAN', sourceStatus: 'PENDING', sourceResolutionStatus: 'PENDING', previousType: 'SCAN', previousStatus: 'PENDING', correctedType: 'CHECK_IN', correctedStatus: 'NORMAL', reason: 'Xác minh lượt vào', actorId: 'admin', actorName: 'Admin', createdAt: now };
  const d = data([source, raw('2', '12:00')]); d.attendanceClassificationOverrides = [correction];
  assert.equal(effectiveAttendance(d)[1].type, 'CHECK_OUT');
  assert.equal(daySummary('e1', date, d, null, now).shiftSummaries[0].workedHours, 4);
  d.attendanceClassificationOverrides = [{ ...correction, sourceTimestamp: at(date, '08:01') }];
  assert.equal(effectiveAttendance(d)[0].type, 'CHECK_IN');
});
test('partial leave suppresses only the approved shift; main leave preserves independent overtime', () => {
  const overtime = { id: `e1_${date}`, employeeId: 'e1', workDate: date, startTime: '18:00', endTime: '22:00', status: 'APPROVED' };
  const d = data([raw('1', '08:00'), raw('2', '12:00'), raw('3', '13:00'), raw('4', '17:00'), raw('5', '18:00'), raw('6', '22:00')]);
  d.overtimeRequests = [overtime];
  d.leaveRequests = [{ employeeId: 'e1', type: 'LEAVE', status: 'APPROVED', startDate: date, endDate: date, leaveShiftsByDate: { [date]: ['am'] } }];
  let summary = daySummary('e1', date, d, null, now);
  assert.equal(summary.workedHours, 4); assert.equal(summary.overtimeHours, 4);
  assert.equal(summary.approvedLeaveShiftCount, 1);
  d.leaveRequests[0].leaveShiftsByDate = null;
  summary = daySummary('e1', date, d, null, now);
  assert.equal(summary.workedHours, 0); assert.equal(summary.overtimeHours, 4); assert.equal(summary.status, 'LEAVE');
});
test('supplementary overtime needs approval and both punches inside exact 18–22 window', () => {
  const d = data([raw('1', '18:00'), raw('2', '22:01')]);
  d.overtimeRequests = [{ id: 'ot', employeeId: 'e1', workDate: date, startTime: '18:00', endTime: '22:00', status: 'APPROVED' }];
  assert.equal(effectiveAttendance(d)[0].shiftId, SUPPLEMENTARY);
  assert.equal(daySummary('e1', date, d, null, now).overtimeHours, 0);
  d.attendance[1].timestamp = at(date, '22:00');
  assert.equal(daySummary('e1', date, d, null, now).overtimeHours, 4);
  d.overtimeRequests[0].status = 'PENDING';
  assert.equal(daySummary('e1', date, d, null, now).overtimeHours, 0);
  assert.equal(effectiveAttendance(d)[0].resolutionStatus, 'OVERTIME_PENDING');
});
test('adjustment completes orphan checkout only in its nearest shift', () => {
  const d = data([raw('1', '12:00'), raw('2', '13:00'), raw('3', '17:00')]);
  d.attendanceAdjustments = [{ employeeId: 'e1', employeeName: employee.fullName, scheduleDate: date, checkInAt: at(date, '08:00'), checkOutAt: null, workedHoursOverride: null, reason: 'Bổ sung vào', actorId: 'a', actorName: 'Admin', createdAt: now }];
  const summary = daySummary('e1', date, d, null, now);
  assert.equal(summary.workedHours, 8);
  assert.equal(summary.shiftSummaries[1].rawCheckInAt, at(date, '13:00'));
});
test('missing checkout deadline and late KPI survive missing checkout', () => {
  const d = data([raw('1', '08:06')]);
  const summary = daySummary('e1', date, d, null, at(date, '12:30'));
  assert.equal(summary.shiftSummaries[0].status, 'MISSING_CHECK_OUT');
  assert.equal(summary.lateMinutes, 6);
  assert.equal(monthlyTotals(d, '2026-10', [employee], now).get('e1').lateCount, 1);
  assert.equal(presenceRows(d, date, at(date, '10:00'))[0].status, 'PRESENT');
});
test('off-schedule approvals create effective schedule and rejection overlays raw pending', () => {
  const d = { employees: [employee], shifts, attendance: [raw('1', '08:00'), raw('2', '12:00')], offScheduleReviews: [{ employeeId: 'e1', scheduleDate: date, shiftId: 'am', decision: 'APPROVE', status: 'PENDING', createdAt: now }] };
  assert.equal(reportRows(d, date, date, [employee], now)[0].workedHours, 4);
  d.offScheduleReviews[0].decision = 'REJECT';
  assert.equal(effectiveAttendance(d)[0].offScheduleReviewStatus, 'REJECTED');
  assert.equal(effectiveAttendance(d)[0].type, 'UNSCHEDULED');
});
test('payroll sums regular and overtime once and preview leaves saved slip untouched', () => {
  const d = data([raw('1', '08:00'), raw('2', '12:00'), raw('3', '13:00'), raw('4', '17:30')]);
  const totals = monthlyTotals(d, '2026-10', [employee], now).get('e1');
  assert.equal(totals.hoursWorked, 8.5);
  const previous = { employeeId: 'e1', employeeCode: 'NV0001', employeeName: employee.fullName, month: '2026-10', hourlyRate: 10000, hoursWorked: 1, baseSalary: 10000, bonus: 0, deduction: 0 };
  const next = payrollPreview(previous, employee, totals.hoursWorked, 50000, 0);
  assert.equal(next.baseSalary, 212500); assert.equal(next.revision, 1); assert.equal(previous.baseSalary, 10000);
  assert.throws(() => basePay(25000, 745)); assert.throws(() => basePay(-1, 10));
});
test('custom overtime-capable shifts expose disjoint regular and overtime portions', () => {
  const custom = { id: 'custom', name: 'Ca cũ', category: 'MORNING', startTime: '08:00', endTime: '16:00', countsOvertime: true };
  const d = { employees: [employee], shifts: [custom], workSchedules: [{ employeeId: 'e1', date, shiftId: 'custom' }], attendance: [raw('1', '08:00'), raw('2', '17:00')] };
  const summary = daySummary('e1', date, d, null, now);
  assert.equal(summary.workedHours, 8); assert.equal(summary.overtimeHours, 1);
  assert.equal(monthlyTotals(d, '2026-10', [employee], now).get('e1').hoursWorked, 9);
});
test('KPI top three uses deterministic code ties and late staff are ineligible', () => {
  const employees = ['4', '2', '3', '1'].map(i => ({ ...employee, id: `e${i}`, code: `NV000${i}` }));
  const d = { employees, attendance: [], shifts, workSchedules: [] };
  const totals = monthlyTotals(d, '2026-10', employees, now);
  assert.equal(totals.get('e1').top3Rank, 1); assert.equal(totals.get('e4').top3Rank, null);
});
test('work KPI counts manager-approved completion times and overdue unfinished tasks', () => {
  const items = [{ status: 'COMPLETED', completedAt: 100, deadline: 100, reworkCount: 1 }, { status: 'COMPLETED', completedAt: 101, deadline: 100 }, { status: 'PENDING_REVIEW', deadline: 100 }];
  const metrics = workMetrics(items, 200);
  assert.equal(metrics.completedOnTime, 1); assert.equal(metrics.completedLate, 1); assert.equal(metrics.overdue, 1); assert.equal(metrics.onTimeCompletionRate, .5);
  assert.equal(validDate('2026-02-30'), false);
});
test('only omitted legacy classification fields use defaults; explicit malformed values stay rejected', () => {
  assert.equal(accepted({}), true);
  for (const key of ['verified', 'type', 'status', 'resolutionStatus']) assert.equal(accepted({ [key]: null }), false, key);
  const d = { employees: [employee], attendance: [{ id: '1', employeeId: 'e1', timestamp: at(date, '08:00') }, { id: '2', employeeId: 'e1', timestamp: at(date, '12:00'), type: 'CHECK_OUT' }] };
  assert.equal(daySummary('e1', date, d, null, now).workedHours, 4);
});
test('legacy omitted shift configuration and leave type retain Android model defaults', () => {
  const d = { employees: [employee], shifts: [{ id: 'legacy', name: 'Ca cũ' }], workSchedules: [{ employeeId: 'e1', date, shiftId: 'legacy' }], attendance: [raw('1', '08:00'), raw('2', '12:00')] };
  assert.equal(daySummary('e1', date, d, null, now).workedHours, 4);
  d.leaveRequests = [{ employeeId: 'e1', startDate: date, endDate: date, status: 'APPROVED' }];
  assert.equal(daySummary('e1', date, d, null, now).workedHours, 0);
  assert.equal(daySummary('e1', date, d, null, now).status, 'LEAVE');
});
test('equal scan timestamps use Android ordinal ID ties, independent of browser locale', () => {
  const d = data([raw('a', '08:00'), raw('A', '08:00')]), rows = effectiveAttendance(d);
  assert.equal(rows.find(r => r.id === 'A').type, 'CHECK_IN');
  assert.equal(rows.find(r => r.id === 'a').type, 'DUPLICATE');
});
