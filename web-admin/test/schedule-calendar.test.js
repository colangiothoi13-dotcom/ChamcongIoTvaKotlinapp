import test from 'node:test';
import assert from 'node:assert/strict';
import { renderScheduleCalendar, scheduleCalendarRange, selectedScheduleDate, scheduleShiftStatus } from '../src/schedule-calendar.js';

const DAY = '2026-10-12';
const at = (date, time) => new Date(`${date}T${time}:00+07:00`).getTime();
const morning = { id: 'legacy-morning', name: 'Ca sáng cũ', category: 'MORNING', startTime: '08:00', endTime: '12:00', effectiveFrom: '2026-09-14', allowEarlyMinutes: 120, lateGraceMinutes: 10, missingCheckOutGraceMinutes: 60, active: true };
const afternoon = { ...morning, id: 'legacy-afternoon', name: 'Ca chiều cũ', category: 'EVENING', startTime: '13:00', endTime: '17:00', allowEarlyMinutes: 0 };
const scan = (id, type, time, shiftId = morning.id, extra = {}) => ({ id, employeeId: 'employee', timestamp: at(DAY, time), scheduleDate: DAY, shiftId, type, status: 'NORMAL', resolutionStatus: 'ACCEPTED', verified: true, ...extra });

function fixture({ filters = {}, attendance = [], schedule = {}, extra = {} } = {}) {
  return {
    filters: { week: DAY, month: '2026-10', ...filters },
    data: {
      employees: [{ id: 'employee', code: 'NV1', fullName: 'An <img src=x>', departmentId: 'department', department: 'Kỹ thuật', active: true }, { id: 'retired', fullName: 'Đã nghỉ', active: false }],
      departments: [{ id: 'department', name: 'Kỹ thuật' }], shifts: [morning, afternoon],
      workSchedules: [{ id: `employee_${DAY}`, employeeId: 'employee', date: DAY, shiftId: morning.id, shiftIds: [morning.id, afternoon.id], ...schedule }],
      attendance, leaveRequests: [], overtimeRequests: [], attendanceAdjustments: [], attendanceClassificationOverrides: [], offScheduleReviews: [], ...extra
    },
    loaded: { employees: true, shifts: true, workSchedules: true }, errors: {},
    range: { from: '2026-10-01', to: '2026-10-31', complete: true, loading: false }
  };
}

test('calendar derives Monday to Saturday and a bounded leap-year month', () => {
  assert.deepEqual(scheduleCalendarRange({ week: '2026-10-14' }, at(DAY, '10:00')), { view: 'week', week: DAY, month: '2026-10', from: DAY, to: '2026-10-17' });
  const leap = scheduleCalendarRange({ scheduleView: 'month', month: '2028-02', week: DAY }, at(DAY, '10:00'));
  assert.equal(leap.from, '2028-02-01'); assert.equal(leap.to, '2028-02-29');
  assert.equal(scheduleCalendarRange({ scheduleView: 'unexpected', week: 'bad', month: '2026-13' }, at(DAY, '10:00')).view, 'week');
  assert.equal(scheduleCalendarRange({ scheduleView: 'month', month: '2026-13', week: DAY }, at(DAY, '10:00')).month, '2026-10');
});

test('per-shift statuses distinguish upcoming, open, completed and missing scans', () => {
  assert.equal(scheduleShiftStatus(DAY, morning, {}, at(DAY, '07:59')).label, 'Sắp vào ca');
  assert.equal(scheduleShiftStatus(DAY, morning, {}, at(DAY, '08:00')).label, 'Chưa chấm vào');
  assert.equal(scheduleShiftStatus(DAY, morning, {}, at(DAY, '12:30')).label, 'Chưa chấm vào');
  assert.equal(scheduleShiftStatus(DAY, morning, {}, at(DAY, '12:31')).label, 'Thiếu chấm vào');
  const open = scheduleShiftStatus(DAY, morning, { checkIn: at(DAY, '10:00'), lateMinutes: 120 }, at(DAY, '10:30'));
  assert.equal(open.tone, 'active'); assert.match(open.label, /Đi trễ 2 giờ • Đã làm 30 phút • Chờ chấm ra/);
  assert.equal(scheduleShiftStatus(DAY, morning, { checkIn: at(DAY, '08:00') }, at(DAY, '12:31')).label, 'Thiếu chấm ra • Chưa tính giờ');
  const closed = scheduleShiftStatus(DAY, morning, { checkIn: at(DAY, '08:15'), checkOut: at(DAY, '11:45'), lateMinutes: 15, earlyLeaveMinutes: 15, status: 'ABNORMAL' }, at(DAY, '13:00'));
  assert.equal(closed.tone, 'error'); assert.match(closed.label, /Đã kết thúc ca • Đã làm 3 giờ 30 phút • Đi trễ 15 phút • Về sớm 15 phút/);
});

test('week cells retain legacy shift definitions and pair scans within each shift once', () => {
  const ctx = fixture({ attendance: [scan('am-in', 'CHECK_IN', '08:00'), scan('am-out', 'CHECK_OUT', '12:00'), scan('pm-in', 'CHECK_IN', '13:00', afternoon.id), scan('pm-out', 'CHECK_OUT', '17:00', afternoon.id)] });
  ctx.data.shifts.push({ ...morning, id: 'weekly_v1_morning_0800_1200', name: 'Ca sáng mặc định' });
  const before = structuredClone(ctx.data), html = renderScheduleCalendar(ctx, at(DAY, '18:00'));
  assert.equal((html.match(/Đã kết thúc ca • Đã làm 4 giờ/g) || []).length, 2);
  assert.match(html, /Ca sáng cũ \(08:00–12:00\)/); assert.match(html, /Ca chiều cũ \(13:00–17:00\)/);
  assert.doesNotMatch(html, /Ca sáng mặc định|Đã nghỉ|<img src=x>/); assert.match(html, /An &lt;img src=x&gt;/);
  assert.match(html, /data-action="ops-schedule-cell" data-employee="employee" data-date="2026-10-12"/);
  assert.match(html, /data-filter="scheduleView"/); assert.deepEqual(ctx.data, before);
});

test('unlabelled legacy scans are assigned to the correct morning or afternoon', () => {
  const ctx = fixture({ attendance: [scan('am-in', 'CHECK_IN', '08:00', null, { scheduleDate: null }), scan('am-out', 'CHECK_OUT', '12:00', null, { scheduleDate: null }), scan('pm-in', 'CHECK_IN', '13:00', null, { scheduleDate: null })] });
  const html = renderScheduleCalendar(ctx, at(DAY, '14:00'));
  assert.equal((html.match(/Đã kết thúc ca • Đã làm 4 giờ/g) || []).length, 1);
  assert.match(html, /Đang làm ca • Đã làm 1 giờ • Chờ chấm ra/);
});

test('calendar uses effective classifications and excludes failed or duplicate scans', () => {
  const ctx = fixture({ attendance: [scan('in', 'CHECK_IN', '08:00'), scan('out', 'CHECK_OUT', '12:00', morning.id, { verified: false }), scan('duplicate', 'CHECK_OUT', '12:01', morning.id, { resolutionStatus: 'DUPLICATE' })], schedule: { shiftIds: [morning.id] } });
  ctx.data.attendanceClassificationOverrides.push({ id: 'correction', attendanceId: 'in', employeeId: 'employee', employeeName: 'An', scheduleDate: DAY, sourceTimestamp: at(DAY, '08:00'), sourceType: 'CHECK_IN', sourceStatus: 'NORMAL', sourceResolutionStatus: 'ACCEPTED', previousType: 'CHECK_IN', previousStatus: 'NORMAL', correctedType: 'DISCARDED', correctedStatus: 'DISCARDED', reason: 'Kiểm tra mẫu', actorId: 'admin', actorName: 'Admin', createdAt: at(DAY, '13:00') });
  const html = renderScheduleCalendar(ctx, at(DAY, '14:00'));
  assert.doesNotMatch(html, /Đã kết thúc ca|Đang làm ca/); assert.match(html, /Thiếu chấm vào/);
});

test('an incomplete or loading range never asserts a missing check-in or completed shift', () => {
  const ctx = fixture({ attendance: [scan('in', 'CHECK_IN', '08:00'), scan('out', 'CHECK_OUT', '12:00')] });
  for (const range of [{ ...ctx.range, complete: false }, { ...ctx.range, loading: true }, { ...ctx.range, from: '2026-10-13' }, { ...ctx.range, error: 'Mất kết nối' }]) {
    ctx.range = range;
    const html = renderScheduleCalendar(ctx, at(DAY, '18:00'));
    assert.doesNotMatch(html, /Đã kết thúc ca|Thiếu chấm vào|Thiếu chấm ra/); assert.match(html, /role="status"/);
  }
});

test('month calendar uses day selection, disables Sundays and presents the selected day details', () => {
  const ctx = fixture({ filters: { scheduleView: 'month', scheduleDate: DAY }, attendance: [scan('in', 'CHECK_IN', '08:00')] });
  const html = renderScheduleCalendar(ctx, at(DAY, '10:30'));
  assert.equal((html.match(/data-action="ops-schedule-day"/g) || []).length, 31);
  assert.match(html, /data-date="2026-10-11"[^>]*disabled/);
  assert.match(html, /data-date="2026-10-12" aria-pressed="true"/);
  assert.match(html, /id="schedule-day-detail"/); assert.match(html, /1 nhân viên/);
  assert.match(html, /Đang làm ca • Đã làm 2 giờ 30 phút • Chờ chấm ra/);
  ctx.filters.scheduleDate = '2026-10-11';
  assert.doesNotMatch(renderScheduleCalendar(ctx, at(DAY, '10:30')), /data-date="2026-10-11" aria-pressed="true"/);
});

test('month statuses wait for the whole month and respect employee department/search filters', () => {
  const ctx = fixture({ filters: { scheduleView: 'month', scheduleDate: DAY, department: 'department', search: 'NV1' } });
  ctx.range.to = '2026-10-17';
  const html = renderScheduleCalendar(ctx, at(DAY, '18:00'));
  assert.match(html, /An &lt;img src=x&gt;/); assert.doesNotMatch(html, /Thiếu chấm vào/);
  assert.match(html, /data-from="2026-10-01" data-to="2026-10-31"/);
  ctx.filters.search = 'không tồn tại';
  assert.match(renderScheduleCalendar(ctx, at(DAY, '18:00')), /Không có nhân viên phù hợp/);
});

test('approved leave and approved off-schedule reviews use shared schedule summaries', () => {
  const ctx = fixture({ schedule: { shiftIds: [morning.id] } });
  ctx.data.leaveRequests.push({ id: 'leave', employeeId: 'employee', type: 'LEAVE', startDate: DAY, endDate: DAY, status: 'APPROVED', leaveShiftsByDate: { [DAY]: [morning.id] } });
  ctx.data.offScheduleReviews.push({ id: 'review', employeeId: 'employee', employeeName: 'An', scheduleDate: DAY, shiftId: afternoon.id, decision: 'APPROVE', status: 'APPROVED', createdAt: at(DAY, '14:00') });
  const html = renderScheduleCalendar(ctx, at(DAY, '18:00'));
  assert.match(html, /Đã duyệt nghỉ phép/); assert.match(html, /Ca chiều cũ/); assert.match(html, /Thiếu chấm vào/);
});

test('bad or absent shift configuration preserves the schedule view without asserting attendance', () => {
  const ctx = fixture({ attendance: [scan('in', 'CHECK_IN', '08:00')] });
  ctx.data.shifts = [{ ...morning, startTime: 'bad' }, afternoon];
  const html = renderScheduleCalendar(ctx, at(DAY, '18:00'));
  assert.match(html, /Cần kiểm tra cấu hình ca/); assert.match(html, /data-action="ops-schedule-cell"/);
  assert.doesNotMatch(html, /Thiếu chấm vào|Đã kết thúc ca/);
  ctx.data.shifts = [afternoon];
  assert.match(renderScheduleCalendar(ctx, at(DAY, '18:00')), /Thiếu cấu hình ca/);
});

test('month assignment default matches the visible day in another month without mutating filters', () => {
  const filters = Object.freeze({ scheduleView: 'month', month: '2026-11', week: DAY });
  const before = { ...filters }, now = at(DAY, '10:00');
  assert.equal(selectedScheduleDate(filters, now), '2026-11-02');
  assert.deepEqual(filters, before);
  const ctx = fixture({ filters });
  const html = renderScheduleCalendar(ctx, now);
  assert.match(html, /data-date="2026-11-02" aria-pressed="true"/);
  assert.doesNotMatch(html, /data-action="ops-week-copy"/);
  assert.match(renderScheduleCalendar(fixture(), now), /data-action="ops-week-copy"/);
  assert.equal(selectedScheduleDate({ ...filters, scheduleDate: '2026-11-04' }, now), '2026-11-04');
  assert.equal(selectedScheduleDate({ ...filters, scheduleDate: '2026-11-01' }, now), '2026-11-02');
  assert.equal(selectedScheduleDate({ ...filters, scheduleDate: DAY }, now), '2026-11-02');
  assert.equal(selectedScheduleDate({ scheduleView: 'month', month: '2026-10', week: DAY }, now), DAY);
});
