// Calculation rules mirror the Android app. Raw device scans are never mutated.
export const ZONE = 'Asia/Ho_Chi_Minh';
export const SUPPLEMENTARY = 'SUPPLEMENTARY_1800_2200';
const modelShift = shift => ({ name: '', category: 'MORNING', startTime: '08:00', endTime: '12:00', allowEarlyMinutes: 0, lateGraceMinutes: 0, earlyLeaveAllowedMinutes: 0, missingCheckOutGraceMinutes: 60, breakStartTime: null, breakEndTime: null, countsOvertime: false, active: true, ...shift });
const modelReady = Symbol('calculationModels');
const cmp = (a, b) => String(a ?? '') < String(b ?? '') ? -1 : String(a ?? '') > String(b ?? '') ? 1 : 0;
const dateFormatter = new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' });
const models = data => data[modelReady] ? data : ({ ...data, [modelReady]: true, shifts: (data.shifts || []).map(modelShift), workSchedules: data.workSchedules || data.schedules || [], leaveRequests: (data.leaveRequests || []).map(row => ({ type: 'LEAVE', status: 'PENDING', leaveShiftsByDate: null, ...row })), overtimeRequests: (data.overtimeRequests || []).map(row => ({ startTime: '18:00', endTime: '22:00', status: 'PENDING', rejectionReason: null, ...row })), offScheduleReviews: (data.offScheduleReviews || []).map(row => ({ status: 'PENDING', ...row })) });
export const ms = value => value == null ? null : value instanceof Date ? value.getTime() : typeof value === 'number' ? value : typeof value.toMillis === 'function' ? value.toMillis() : typeof value.toDate === 'function' ? value.toDate().getTime() : typeof value.seconds === 'number' ? value.seconds * 1000 + (value.nanoseconds || 0) / 1e6 : typeof value._seconds === 'number' ? value._seconds * 1000 + (value._nanoseconds || 0) / 1e6 : Date.parse(value);
export const roundHours = seconds => Math.round(seconds / 36) / 100;
export const dateOf = value => {
  const n = ms(value);
  if (!Number.isFinite(n)) return '';
  const p = Object.fromEntries(dateFormatter.formatToParts(new Date(n)).filter(v => v.type !== 'literal').map(v => [v.type, v.value]));
  return `${p.year}-${p.month}-${p.day}`;
};
export const addDays = (date, amount) => new Date(Date.parse(`${date}T00:00:00Z`) + amount * 864e5).toISOString().slice(0, 10);
export const validDate = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
export function datesBetween(from, to) {
  if (!validDate(from) || !validDate(to) || from > to) throw new Error('Khoảng ngày không hợp lệ.');
  const result = [];
  for (let date = from; date <= to; date = addDays(date, 1)) result.push(date);
  return result;
}
export function monthDates(month) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new Error('Tháng phải có dạng yyyy-MM.');
  const from = `${month}-01`;
  const end = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).toISOString().slice(0, 10);
  return datesBetween(from, end);
}
export const at = (date, time) => Date.parse(`${date}T${time.length === 5 ? `${time}:00` : time}+07:00`);
const morning = s => s.category === 'MORNING' && s.startTime === '08:00' && s.endTime === '12:00';
const afternoon = s => s.category === 'EVENING' && s.startTime === '13:00' && s.endTime === '17:00';
const strict = s => s.id === SUPPLEMENTARY || morning(s) || afternoon(s);
export const earlyMinutes = s => morning(s) ? Math.max(120, Number(s.allowEarlyMinutes || 0)) : Number(s.allowEarlyMinutes || 0);
export const checkoutGrace = s => s.id === SUPPLEMENTARY ? 120 : morning(s) || afternoon(s) ? 30 : Number(s.missingCheckOutGraceMinutes ?? 60);
export function shiftWindow(date, shift) {
  shift = modelShift(shift);
  const start = at(date, shift.startTime);
  let end = at(date, shift.endTime);
  if (end <= start) end = at(addDays(date, 1), shift.endTime);
  if (!Number.isFinite(start) || !Number.isFinite(end)) throw new Error(`Giờ ca ${shift.name || shift.id} không hợp lệ.`);
  return { start, end };
}
export function scheduledIds(schedule) {
  const ids = [...new Set((schedule?.shiftIds || []).map(v => v.trim()).filter(Boolean))];
  const legacy = schedule?.shiftId?.trim();
  return legacy ? [legacy, ...ids.filter(v => v !== legacy)] : ids;
}
export function scheduledShifts(schedule, shifts) {
  const selected = scheduledIds(schedule).map(id => shifts.find(s => s.id === id)).filter(Boolean).map(modelShift).sort((a, b) => cmp(a.startTime, b.startTime) || cmp(a.id, b.id));
  const seen = new Set();
  return selected.filter(s => seen.has(s.category) ? false : (seen.add(s.category), true));
}
const defaulted = (row, key, fallback) => Object.hasOwn(row, key) ? row[key] : fallback;
export const accepted = row => defaulted(row, 'verified', true) === true && defaulted(row, 'resolutionStatus', 'ACCEPTED') === 'ACCEPTED' && ['CHECK_IN', 'CHECK_OUT'].includes(defaulted(row, 'type', 'CHECK_IN')) && ['NORMAL', 'ON_TIME', 'LATE', 'EARLY_LEAVE'].includes(defaulted(row, 'status', 'NORMAL'));
export const abnormal = row => defaulted(row, 'verified', true) !== true || !(row.resolutionStatus === 'DUPLICATE' && ['DUPLICATE', 'CHECK_IN', 'CHECK_OUT'].includes(row.type)) && !accepted(row);
export function belongs(row, date, shift) {
  if (shift && row.shiftId != null && row.shiftId !== shift.id) return false;
  if (row.scheduleDate != null) return row.scheduleDate === date;
  if (!shift) return dateOf(row.timestamp) === date;
  const { start, end } = shiftWindow(date, shift), n = ms(row.timestamp);
  return n >= start - earlyMinutes(shift) * 6e4 && n <= end + checkoutGrace(shift) * 6e4;
}
const distance = (n, type, window) => Math.abs(n - (type === 'CHECK_OUT' ? window.end : window.start));
function candidates(schedules, shifts) {
  return schedules.filter(s => validDate(s.date)).flatMap(schedule => scheduledShifts(schedule, shifts).map(shift => ({ schedule, shift, window: shiftWindow(schedule.date, shift) })));
}
function byEmployee(choices) {
  const grouped = new Map();
  for (const c of choices) { const id = c.schedule.employeeId; if (!grouped.has(id)) grouped.set(id, []); grouped.get(id).push(c); }
  return grouped;
}
export function assignScheduleDates(rows, schedules, shifts) {
  const choices = byEmployee(candidates(schedules, shifts));
  return rows.map(row => {
    const selected = (choices.get(row.employeeId) || []).filter(c => (row.scheduleDate == null || c.schedule.date === row.scheduleDate) && (row.shiftId == null || c.shift.id === row.shiftId) && (row.scheduleDate != null || belongs(row, c.schedule.date, c.shift))).sort((a, b) => distance(ms(row.timestamp), row.type, a.window) - distance(ms(row.timestamp), row.type, b.window) || a.window.start - b.window.start || cmp(a.schedule.date, b.schedule.date) || cmp(a.shift.id, b.shift.id) || cmp(a.schedule.id, b.schedule.id))[0];
    if (!selected) return { ...row };
    const multi = scheduledShifts(selected.schedule, shifts).length > 1;
    return row.scheduleDate != null && !multi ? { ...row } : { ...row, scheduleDate: row.scheduleDate ?? selected.schedule.date, shiftId: row.shiftId ?? (multi ? selected.shift.id : null) };
  });
}
export function approvedSchedules(schedules, reviews, shifts) {
  shifts = shifts.map(modelShift);
  const map = new Map(schedules.map(s => [`${s.employeeId}_${s.date}`, { ...s }]));
  for (const r of reviews.filter(r => r.decision === 'APPROVE' && ['PENDING', 'APPROVED'].includes(r.status)).sort((a, b) => (ms(a.createdAt) || 0) - (ms(b.createdAt) || 0))) {
    const shift = shifts.find(s => s.id === r.shiftId);
    if (!shift || !['MORNING', 'EVENING'].includes(shift.category) || !validDate(r.scheduleDate)) continue;
    const key = `${r.employeeId}_${r.scheduleDate}`, old = map.get(key);
    const ids = [...new Set([...scheduledIds(old), shift.id])];
    if (ids.length > 2 || new Set(ids.map(id => shifts.find(s => s.id === id)?.category).filter(Boolean)).size !== ids.length) continue;
    ids.sort((a, b) => cmp(shifts.find(s => s.id === a)?.startTime, shifts.find(s => s.id === b)?.startTime));
    map.set(key, { ...(old || { employeeId: r.employeeId, employeeName: r.employeeName, date: r.scheduleDate, source: 'OFF_SCHEDULE_REVIEW' }), shiftId: ids[0], shiftIds: ids, shiftName: ids.map(id => shifts.find(s => s.id === id)?.name || '').join(' + ') });
  }
  return [...map.values()];
}
export const validOvertime = r => !!r.employeeId && validDate(r.workDate) && r.startTime === '18:00' && r.endTime === '22:00' && ['PENDING', 'APPROVED', 'REJECTED'].includes(r.status) && (r.status !== 'REJECTED' || !!r.rejectionReason?.trim());
export const supplementaryShift = () => ({ id: SUPPLEMENTARY, name: 'Tăng ca', category: 'SUPPLEMENTARY', startTime: '18:00', endTime: '22:00', countsOvertime: true, allowEarlyMinutes: 0, lateGraceMinutes: 0, earlyLeaveAllowedMinutes: 0, missingCheckOutGraceMinutes: 120 });
export const lateMinutes = (n, date, shift) => n == null || !shift || n <= shiftWindow(date, shift).start + Number(shift.lateGraceMinutes || 0) * 6e4 ? 0 : Math.max(1, Math.floor((n - shiftWindow(date, shift).start) / 6e4));
export const earlyLeaveMinutes = (n, date, shift) => n == null || !shift || n >= shiftWindow(date, shift).end - Number(shift.earlyLeaveAllowedMinutes || 0) * 6e4 ? 0 : Math.max(1, Math.floor((shiftWindow(date, shift).end - n) / 6e4));
export function resolveSpark(rows, schedules, shifts, overtime = [], reviews = []) {
  shifts = shifts.map(modelShift);
  const effectiveSchedules = approvedSchedules(schedules, reviews, shifts);
  const choices = [...candidates(effectiveSchedules, shifts), ...overtime.filter(validOvertime).map(r => { const shift = supplementaryShift(); return { schedule: { employeeId: r.employeeId, date: r.workDate }, shift, window: shiftWindow(r.workDate, shift) }; })];
  if (!choices.length) return rows.map(r => ({ ...r }));
  const grouped = byEmployee(choices);
  const sessions = new Map(), result = rows.map(r => ({ ...r }));
  rows.map((row, i) => ({ row, i })).sort((a, b) => ms(a.row.timestamp) - ms(b.row.timestamp) || cmp(a.row.id, b.row.id)).forEach(({ row, i }) => {
    const n = ms(row.timestamp), approvals = reviews.filter(r => r.decision === 'APPROVE' && ['PENDING', 'APPROVED'].includes(r.status) && r.employeeId === row.employeeId && r.scheduleDate === dateOf(n));
    const unscheduled = row.type === 'UNSCHEDULED' && row.resolutionStatus === 'UNSCHEDULED' && row.offScheduleReviewStatus == null && approvals.length > 0;
    const pending = row.type === 'SCAN' && row.resolutionStatus === 'PENDING', needs = pending || unscheduled;
    const selected = (grouped.get(row.employeeId) || []).filter(c => (row.scheduleDate == null || c.schedule.date === row.scheduleDate) && (row.shiftId == null || c.shift.id === row.shiftId) && (!needs || (!unscheduled || approvals.some(r => r.shiftId === c.shift.id)) && n >= c.window.start - earlyMinutes(c.shift) * 6e4 && n <= c.window.end + checkoutGrace(c.shift) * 6e4)).sort((a, b) => (needs ? Math.min(Math.abs(n - a.window.start), Math.abs(n - a.window.end)) - Math.min(Math.abs(n - b.window.start), Math.abs(n - b.window.end)) : distance(n, row.type, a.window) - distance(n, row.type, b.window)) || a.window.start - b.window.start || cmp(a.shift.id, b.shift.id))[0];
    if (!selected) return;
    const { shift, schedule, window } = selected, key = `${row.employeeId}_${schedule.date}_${shift.id}`;
    const session = sessions.get(key) || { last: null, open: null, closed: false };
    sessions.set(key, session);
    if (!needs) {
      if (accepted(row)) { session.last = n; session.open = row.type === 'CHECK_IN' ? n : null; session.closed = row.type === 'CHECK_OUT'; }
      return;
    }
    const extra = { scheduleDate: schedule.date, shiftId: shift.id, syncStatus: 'SYNCED' };
    const duplicate = session.last != null && Math.abs(Math.floor(n / 1000) - Math.floor(session.last / 1000)) <= 180;
    const rejected = duplicate ? 'DUPLICATE' : session.last != null && n < session.last ? 'OUT_OF_ORDER' : session.closed ? 'UNSCHEDULED' : null;
    if (rejected) { result[i] = { ...row, ...extra, type: rejected, status: 'ABNORMAL', resolutionStatus: rejected }; return; }
    const type = session.open != null || n >= window.end ? 'CHECK_OUT' : 'CHECK_IN';
    const request = shift.id === SUPPLEMENTARY ? overtime.find(r => r.employeeId === row.employeeId && r.workDate === schedule.date) : null;
    const resolutionStatus = request?.status === 'PENDING' ? 'OVERTIME_PENDING' : request?.status === 'REJECTED' ? 'OVERTIME_REJECTED' : 'ACCEPTED';
    const status = type === 'CHECK_IN' && lateMinutes(n, schedule.date, shift) > 0 ? 'LATE' : type === 'CHECK_OUT' && earlyLeaveMinutes(n, schedule.date, shift) > 0 ? 'EARLY_LEAVE' : 'NORMAL';
    const review = approvals.filter(r => r.shiftId === shift.id).sort((a, b) => (ms(b.createdAt) || 0) - (ms(a.createdAt) || 0))[0];
    result[i] = { ...row, ...extra, type, status: resolutionStatus === 'ACCEPTED' ? status : 'ABNORMAL', resolutionStatus, overtimeRequestId: request?.id ?? null, offScheduleReviewStatus: unscheduled || review ? 'APPROVED' : row.offScheduleReviewStatus, offScheduleReviewerId: review?.reviewerId ?? row.offScheduleReviewerId, offScheduleReviewerName: review?.reviewerName ?? row.offScheduleReviewerName };
    session.last = n; session.open = type === 'CHECK_IN' ? n : null; session.closed = type === 'CHECK_OUT';
  });
  return result;
}
export function validateAdjustment(v) {
  if (!v.employeeId || !v.employeeName || !validDate(v.scheduleDate) || !v.reason?.trim() || !v.actorId || !v.actorName) throw new Error('Điều chỉnh cần nhân viên, ngày, lý do và người thực hiện.');
  const ci = ms(v.checkInAt), co = ms(v.checkOutAt), h = v.workedHoursOverride;
  if (ci == null && co == null && h == null) throw new Error('Nhập ít nhất giờ vào, giờ ra hoặc số giờ điều chỉnh.');
  if ((ci != null && !Number.isFinite(ci)) || (co != null && !Number.isFinite(co)) || (ci != null && co != null && co <= ci)) throw new Error('Giờ ra phải sau giờ vào.');
  if (h != null && (!Number.isFinite(h) || h < 0 || h > 24)) throw new Error('Giờ điều chỉnh phải từ 0 đến 24.');
  return v;
}
export function validateClassification(v) {
  if (!v.attendanceId || !v.employeeId || !v.employeeName || !validDate(v.scheduleDate) || !Number.isFinite(ms(v.sourceTimestamp)) || ms(v.sourceTimestamp) === 0 || !v.sourceType || !v.sourceStatus || !v.sourceResolutionStatus || !v.actorId || !v.actorName || !v.reason?.trim() || v.reason.length > 500) throw new Error('Thiếu thông tin hoặc lý do sửa lượt chấm (tối đa 500 ký tự).');
  if (!['CHECK_IN', 'CHECK_OUT', 'DISCARDED'].includes(v.correctedType) || !['ON_TIME', 'LATE', 'EARLY_LEAVE', 'NORMAL', 'DISCARDED'].includes(v.correctedStatus) || (v.correctedType === 'DISCARDED') !== (v.correctedStatus === 'DISCARDED')) throw new Error('Phân loại sửa không hợp lệ.');
  if (v.previousType === v.correctedType && v.previousStatus === v.correctedStatus) throw new Error('Phân loại mới phải khác phân loại hiện tại.');
  return v;
}
const isValid = (fn, v) => { try { fn(v); return true; } catch { return false; } };
export const latestAdjustment = (adjustments, employeeId, date) => adjustments.filter(v => v.employeeId === employeeId && v.scheduleDate === date && ms(v.createdAt) != null && isValid(validateAdjustment, v)).sort((a, b) => ms(b.createdAt) - ms(a.createdAt))[0] || null;
export function effectiveAttendance(data) {
  data = models(data);
  const schedules = data.workSchedules || data.schedules || [], shifts = data.shifts || [], reviews = data.offScheduleReviews || [];
  const latest = new Map();
  for (const v of (data.attendanceClassificationOverrides || []).filter(v => ms(v.createdAt) != null && isValid(validateClassification, v)).sort((a, b) => ms(a.createdAt) - ms(b.createdAt))) latest.set(v.attendanceId, v);
  const corrected = row => {
    const override = latest.get(row.id);
    return override && ms(row.timestamp) === ms(override.sourceTimestamp) ? { ...row, type: override.correctedType, status: override.correctedStatus, resolutionStatus: override.sourceType === 'SCAN' ? override.correctedType === 'DISCARDED' ? 'DISCARDED' : 'ACCEPTED' : row.resolutionStatus } : row;
  };
  // Corrections seed the resolver sessions, then are reapplied to its effective copy.
  const resolved = resolveSpark((data.attendance || []).map(row => corrected({ type: 'CHECK_IN', status: 'NORMAL', verified: true, resolutionStatus: 'ACCEPTED', syncStatus: 'SYNCED', ...row })), schedules, shifts, data.overtimeRequests || [], reviews);
  return resolved.map(row => {
    row = corrected(row);
    const rawPending = row.type === 'SCAN' && row.resolutionStatus === 'PENDING';
    if ((rawPending || row.type === 'UNSCHEDULED' || row.resolutionStatus === 'UNSCHEDULED') && !['APPROVED', 'REJECTED'].includes(row.offScheduleReviewStatus)) {
      const matches = reviews.filter(r => r.decision === 'REJECT' && ['PENDING', 'REJECTED'].includes(r.status) && r.employeeId === row.employeeId && r.scheduleDate === (row.scheduleDate || dateOf(row.timestamp)) && (() => { const shift = shifts.find(s => s.id === r.shiftId); return !shift || belongs({ ...row, shiftId: null, scheduleDate: null }, r.scheduleDate, shift); })());
      const review = matches.find(r => !row.shiftId || row.shiftId === r.shiftId) || matches[0];
      if (review) row = { ...row, type: rawPending ? 'UNSCHEDULED' : row.type, status: rawPending ? 'ABNORMAL' : row.status, resolutionStatus: rawPending ? 'UNSCHEDULED' : row.resolutionStatus, offScheduleReviewStatus: 'REJECTED', offScheduleReviewerId: review.reviewerId, offScheduleReviewerName: review.reviewerName };
    }
    return row;
  });
}
export function pair(rows, date, shift, adjustment = null) {
  const valid = rows.filter(r => accepted(r) && belongs(r, date, shift)).sort((a, b) => ms(a.timestamp) - ms(b.timestamp));
  let checkIn = ms(adjustment?.checkInAt), checkOut = null;
  for (const row of valid) {
    const n = ms(row.timestamp);
    if (defaulted(row, 'type', 'CHECK_IN') === 'CHECK_IN' && checkIn == null) checkIn = n;
    if (row.type === 'CHECK_OUT' && checkOut == null && checkIn != null && n > checkIn) checkOut = n;
  }
  return { checkIn: ms(adjustment?.checkInAt) ?? checkIn, checkOut: ms(adjustment?.checkOutAt) ?? checkOut };
}
export function missingCheckout(p, date, shift, now = Date.now()) {
  if (p.checkIn == null || p.checkOut != null) return false;
  if (!shift) return dateOf(now) > date;
  const deadline = shiftWindow(date, shift).end + checkoutGrace(shift) * 6e4;
  return strict(shift) ? now >= deadline : now > deadline;
}
export function workTime(ci, co, date, shift, overtimeHours = 0) {
  const zero = { workedSeconds: 0, overtimeSeconds: 0, workedHours: 0, overtimeHours: 0, lateMinutes: 0, earlyLeaveMinutes: 0, paidCheckInAt: null, paidCheckOutAt: null };
  if (ci == null || co == null || co <= ci) return zero;
  if (!shift) { const seconds = Math.floor((co - ci) / 1000); return { ...zero, workedSeconds: seconds, workedHours: roundHours(seconds), paidCheckInAt: ci, paidCheckOutAt: co }; }
  if (!['MORNING', 'EVENING', 'SUPPLEMENTARY'].includes(shift.category)) throw new Error('Loại ca không hợp lệ.');
  const window = shiftWindow(date, shift), paidIn = Math.max(ci, window.start), paidOut = Math.min(co, window.end);
  let seconds = Math.max(0, Math.floor((paidOut - paidIn) / 1000));
  if ((shift.breakStartTime == null) !== (shift.breakEndTime == null)) throw new Error('Ca có cấu hình nghỉ không đầy đủ.');
  if (shift.breakStartTime && shift.breakEndTime) {
    const breakDate = dateOf(window.end) > date && shift.breakStartTime < shift.startTime ? addDays(date, 1) : date;
    const bs = at(breakDate, shift.breakStartTime), be = at(shift.breakEndTime <= shift.breakStartTime ? addDays(breakDate, 1) : breakDate, shift.breakEndTime);
    seconds -= Math.max(0, Math.floor((Math.min(paidOut, be, window.end) - Math.max(paidIn, bs, window.start)) / 1000));
    seconds = Math.max(0, seconds);
  }
  const extra = Math.max(0, Math.floor((co - window.end) / 1000));
  const overtimeSeconds = morning(shift) || afternoon(shift) || shift.countsOvertime ? extra : shift.category === 'SUPPLEMENTARY' ? seconds : Math.min(extra, overtimeHours * 3600);
  const regular = shift.category === 'SUPPLEMENTARY' ? seconds : shift.countsOvertime ? seconds + extra : seconds;
  // Shared consumer path normalizes custom countsOvertime into disjoint portions.
  const disjointRegular = shift.countsOvertime && !morning(shift) && !afternoon(shift) && shift.category !== 'SUPPLEMENTARY' ? regular - overtimeSeconds : regular;
  return { workedSeconds: disjointRegular, overtimeSeconds: shift.category === 'SUPPLEMENTARY' ? seconds : overtimeSeconds, workedHours: roundHours(disjointRegular), overtimeHours: roundHours(shift.category === 'SUPPLEMENTARY' ? seconds : overtimeSeconds), paidCheckInAt: paidIn, paidCheckOutAt: paidOut, lateMinutes: lateMinutes(ci, date, shift), earlyLeaveMinutes: earlyLeaveMinutes(co, date, shift) };
}
function leaveFor(employeeId, date, schedule, shifts, requests) {
  const leave = requests.filter(r => r.employeeId === employeeId && r.type === 'LEAVE' && r.status === 'APPROVED' && validDate(r.startDate) && validDate(r.endDate) && date >= r.startDate && date <= r.endDate);
  const legacy = leave.some(r => r.leaveShiftsByDate == null), ids = new Set();
  const assigned = scheduledShifts(schedule, shifts).map(s => s.id);
  for (const r of leave) for (const id of r.leaveShiftsByDate == null ? assigned : r.leaveShiftsByDate[date] || []) if (assigned.includes(id)) ids.add(id);
  return { legacy, ids };
}
function summarizeOne(employeeId, date, rows, schedule, shift, adjustment, leave, now) {
  const day = rows.filter(r => r.employeeId === employeeId && belongs(r, date, shift)), raw = pair(day, date, shift), p = pair(day, date, shift, adjustment);
  p.checkIn = ms(adjustment?.checkInAt) ?? p.checkIn; p.checkOut = ms(adjustment?.checkOutAt) ?? p.checkOut;
  const calculated = workTime(p.checkIn, p.checkOut, date, shift, schedule?.overtimeHours || 0);
  const late = p.checkIn != null && p.checkOut == null ? lateMinutes(p.checkIn, date, shift) : calculated.lateMinutes;
  const status = leave ? 'LEAVE' : day.some(abnormal) || p.checkIn != null && p.checkOut != null && p.checkOut <= p.checkIn ? 'ABNORMAL' : p.checkIn == null ? 'MISSING_CHECK_IN' : missingCheckout(p, date, shift, now) ? 'MISSING_CHECK_OUT' : p.checkOut == null ? 'PRESENT' : late > 0 && calculated.earlyLeaveMinutes > 0 ? 'ABNORMAL' : late > 0 ? 'LATE' : calculated.earlyLeaveMinutes > 0 ? 'EARLY_LEAVE' : 'ON_TIME';
  const override = adjustment?.workedHoursOverride ?? schedule?.workedHoursOverride;
  const workedSeconds = leave ? 0 : override != null ? Math.round(override * 3600) : calculated.workedSeconds;
  const result = { date, shiftName: schedule?.shiftName || shift?.name || '', checkIn: p.checkIn, checkOut: p.checkOut, status, workedSeconds, overtimeSeconds: leave ? 0 : calculated.overtimeSeconds, workedHours: leave ? 0 : override ?? calculated.workedHours, overtimeHours: leave ? 0 : calculated.overtimeHours, lateMinutes: leave ? 0 : late, earlyLeaveMinutes: leave ? 0 : calculated.earlyLeaveMinutes, shiftSummaries: [] };
  if (shift) result.shiftSummaries.push({ ...result, shiftSummaries: undefined, shiftId: shift.id, shiftName: shift.name, rawCheckInAt: raw.checkIn, rawCheckOutAt: raw.checkOut, paidCheckInAt: calculated.paidCheckInAt, paidCheckOutAt: calculated.paidCheckOutAt });
  return result;
}
export function daySummary(employeeId, date, data, rows = null, now = Date.now(), prepared = null) {
  data = models(data);
  const shifts = data.shifts || [], schedules = prepared?.schedules || approvedSchedules(data.workSchedules || data.schedules || [], data.offScheduleReviews || [], shifts);
  rows = prepared ? rows || prepared.rows : assignScheduleDates(rows || effectiveAttendance(data), schedules, shifts);
  const schedule = schedules.find(s => s.employeeId === employeeId && s.date === date);
  const ids = scheduledIds(schedule).filter(id => id !== SUPPLEMENTARY), mainSchedule = ids.length ? { ...schedule, shiftId: ids[0], shiftIds: ids } : null;
  const selected = scheduledShifts(mainSchedule, shifts), leave = leaveFor(employeeId, date, mainSchedule, shifts, data.leaveRequests || []), adjustment = latestAdjustment(data.attendanceAdjustments || [], employeeId, date);
  const mainRows = rows.filter(r => r.shiftId !== SUPPLEMENTARY);
  let daily;
  if (selected.length <= 1) daily = summarizeOne(employeeId, date, mainRows, mainSchedule, selected[0], adjustment, leave.legacy || leave.ids.has(selected[0]?.id), now);
  else {
    const adjustedTime = ms(adjustment?.checkInAt) ?? ms(adjustment?.checkOutAt);
    const adjustmentShift = adjustedTime == null ? null : [...selected].sort((a, b) => distance(adjustedTime, adjustment.checkInAt != null ? 'CHECK_IN' : 'CHECK_OUT', shiftWindow(date, a)) - distance(adjustedTime, adjustment.checkInAt != null ? 'CHECK_IN' : 'CHECK_OUT', shiftWindow(date, b)))[0]?.id;
    const days = selected.map((shift, i) => summarizeOne(employeeId, date, mainRows, { ...mainSchedule, shiftId: shift.id, shiftIds: [shift.id], shiftName: shift.name, workedHoursOverride: null, overtimeHours: i === 0 ? mainSchedule.overtimeHours : 0 }, shift, shift.id === adjustmentShift ? adjustment : null, leave.legacy || leave.ids.has(shift.id), now));
    const working = days.filter(d => d.status !== 'LEAVE'), precedence = ['ABNORMAL', 'MISSING_CHECK_OUT', 'MISSING_CHECK_IN', 'PRESENT'];
    const late = days.reduce((a, d) => a + d.lateMinutes, 0), early = days.reduce((a, d) => a + d.earlyLeaveMinutes, 0);
    const override = leave.legacy || leave.ids.size ? null : adjustment?.workedHoursOverride ?? mainSchedule.workedHoursOverride;
    const ci = days.map(d => d.checkIn).filter(v => v != null), co = days.map(d => d.checkOut).filter(v => v != null);
    daily = { date, shiftName: selected.map(s => s.name).join(' / '), checkIn: ms(adjustment?.checkInAt) ?? (ci.length ? Math.min(...ci) : null), checkOut: ms(adjustment?.checkOutAt) ?? (co.length ? Math.max(...co) : null), status: !working.length ? 'LEAVE' : precedence.find(s => working.some(d => d.status === s)) || (late > 0 && early > 0 ? 'ABNORMAL' : late > 0 ? 'LATE' : early > 0 ? 'EARLY_LEAVE' : 'ON_TIME'), workedSeconds: override != null ? Math.round(override * 3600) : days.reduce((a, d) => a + d.workedSeconds, 0), workedHours: override ?? days.reduce((a, d) => a + d.workedHours, 0), overtimeSeconds: days.reduce((a, d) => a + d.overtimeSeconds, 0), overtimeHours: days.reduce((a, d) => a + d.overtimeHours, 0), lateMinutes: late, earlyLeaveMinutes: early, shiftSummaries: days.flatMap(d => d.shiftSummaries) };
  }
  daily.approvedLeaveShiftCount = leave.ids.size;
  for (const request of (data.overtimeRequests || []).filter(r => r.employeeId === employeeId && r.workDate === date && r.status === 'APPROVED' && validOvertime(r))) {
    if (daily.shiftSummaries.some(s => s.shiftId === SUPPLEMENTARY)) break;
    const shift = supplementaryShift(), p = pair(rows.filter(r => r.employeeId === employeeId && r.shiftId === SUPPLEMENTARY && (r.scheduleDate == null || r.scheduleDate === date)), date, shift), window = shiftWindow(date, shift);
    if (p.checkIn == null || p.checkOut == null || p.checkIn < window.start || p.checkOut > window.end) continue;
    const hours = workTime(p.checkIn, p.checkOut, date, shift, 4);
    if (!hours.overtimeSeconds) continue;
    daily.shiftSummaries.push({ shiftId: SUPPLEMENTARY, shiftName: 'Tăng ca', rawCheckInAt: p.checkIn, rawCheckOutAt: p.checkOut, workedSeconds: 0, workedHours: 0, overtimeSeconds: hours.overtimeSeconds, overtimeHours: hours.overtimeHours, status: 'ON_TIME' });
    daily.overtimeSeconds += hours.overtimeSeconds; daily.overtimeHours += hours.overtimeHours;
    if (!mainSchedule && daily.checkIn == null) { daily.checkIn = p.checkIn; daily.checkOut = p.checkOut; daily.shiftName = 'Tăng ca'; if (!leave.legacy) daily.status = 'ON_TIME'; }
  }
  return daily;
}
export function reportRows(data, from, to, employees = data.employees || [], now = Date.now()) {
  data = models(data);
  const rows = effectiveAttendance(data), schedules = approvedSchedules(data.workSchedules || data.schedules || [], data.offScheduleReviews || [], data.shifts || []);
  const assigned = assignScheduleDates(rows, schedules, data.shifts || []);
  const knownDays = new Set([...schedules.map(s => `${s.employeeId}_${s.date}`), ...assigned.map(r => `${r.employeeId}_${r.scheduleDate || dateOf(r.timestamp)}`), ...(data.overtimeRequests || []).map(r => `${r.employeeId}_${r.workDate}`)]);
  const groups = new Map();
  for (const row of assigned) { if (!groups.has(row.employeeId)) groups.set(row.employeeId, []); groups.get(row.employeeId).push(row); }
  const dates = datesBetween(from, to);
  return employees.flatMap(employee => {
    const ownRows = groups.get(employee.id) || [], ownSchedules = schedules.filter(s => s.employeeId === employee.id);
    const scoped = { ...data, workSchedules: ownSchedules, attendanceAdjustments: (data.attendanceAdjustments || []).filter(r => r.employeeId === employee.id), leaveRequests: (data.leaveRequests || []).filter(r => r.employeeId === employee.id), overtimeRequests: (data.overtimeRequests || []).filter(r => r.employeeId === employee.id) };
    return dates.flatMap(date => {
      const hasSource = knownDays.has(`${employee.id}_${date}`) || latestAdjustment(scoped.attendanceAdjustments, employee.id, date) || scoped.leaveRequests.some(r => r.type === 'LEAVE' && r.status === 'APPROVED' && date >= r.startDate && date <= r.endDate);
      return hasSource ? [{ ...daySummary(employee.id, date, scoped, ownRows, now, { schedules: ownSchedules, rows: ownRows }), employeeId: employee.id, employeeName: employee.fullName, department: employee.department, code: employee.code }] : [];
    });
  }).sort((a, b) => cmp(a.date, b.date) || cmp(a.employeeName, b.employeeName));
}
// The monthly timesheet view ends at the employee's recorded departure date.
// Other reports and saved payroll still use their existing calculation scope.
export function monthlyTimesheetRows(data, month, employees = data.employees || [], now = Date.now()) {
  const days = monthDates(month), departures = new Map(employees.map(employee => [employee.id, employee.terminationDate]));
  return reportRows(data, days[0], days.at(-1), employees, now).filter(row => {
    const departure = departures.get(row.employeeId);
    return !validDate(departure) || row.date <= departure;
  });
}
// Match the Android monthly view's eligibility, including legacy retired profiles
// without a usable departure date. Prepared rows keep the UI on one calculation.
export function monthlyTimesheetEmployees(data, month, employees = data.employees || [], now = Date.now(), summaries = monthlyTimesheetRows(data, month, employees, now)) {
  monthDates(month);
  const currentMonth = dateOf(now).slice(0, 7);
  const evidenceIds = new Set(summaries.filter(row => row.shiftSummaries?.length || row.checkIn != null || row.checkOut != null ||
    row.workedSeconds > 0 || row.overtimeSeconds > 0 || row.workedHours > 0 || row.overtimeHours > 0).map(row => row.employeeId));
  return employees.filter(employee => {
    const departureValid = validDate(employee.terminationDate);
    const lastMonth = departureValid ? employee.terminationDate.slice(0, 7) : employee.active === false ? currentMonth : null;
    if (lastMonth != null && lastMonth < month) return false;
    return employee.active !== false || departureValid || evidenceIds.has(employee.id);
  });
}
export function monthlyTotals(data, month, employees = data.employees || [], now = Date.now()) {
  const dates = monthDates(month), rows = reportRows(data, dates[0], dates.at(-1), employees, now);
  const values = new Map(employees.map(employee => {
    const own = rows.filter(r => r.employeeId === employee.id);
    const supplements = own.flatMap(r => r.shiftSummaries).filter(s => s.shiftId === SUPPLEMENTARY);
    return [employee.id, { employee, rows: own, regularHours: roundHours(own.reduce((a, r) => a + r.workedSeconds, 0)), overtimeHours: roundHours(own.reduce((a, r) => a + r.overtimeSeconds, 0)), hoursWorked: Math.round(own.reduce((a, r) => a + r.workedHours + r.overtimeHours, 0) * 100) / 100, lateCount: own.filter(r => r.lateMinutes > 0).length, earlyCount: own.filter(r => r.earlyLeaveMinutes > 0).length, overtimeShiftCount: supplements.length, supplementaryHours: supplements.reduce((a, r) => a + r.overtimeHours, 0), workdays: own.filter(r => r.workedSeconds > 0).length }];
  }));
  const ranked = [...values.values()].filter(v => v.lateCount === 0).sort((a, b) => b.overtimeShiftCount - a.overtimeShiftCount || b.supplementaryHours - a.supplementaryHours || cmp(a.employee.code || a.employee.id, b.employee.code || b.employee.id) || cmp(a.employee.id, b.employee.id)).slice(0, 3);
  for (const v of values.values()) { v.top3Rank = ranked.indexOf(v) >= 0 ? ranked.indexOf(v) + 1 : null; v.top3Bonus = v.top3Rank ? 500000 : 0; v.overtimeBonus = v.overtimeShiftCount * 50000; v.latePenalty = v.lateCount * 100000; v.totalBonus = Math.max(0, v.top3Bonus + v.overtimeBonus - v.latePenalty); }
  return values;
}
export function basePay(rate, hours) {
  if (!Number.isSafeInteger(rate) || rate < 0 || rate > 1e12 || !Number.isFinite(hours) || hours < 0 || hours > 744) throw new Error('Đơn giá từ 0 đến 1.000 tỷ; giờ làm từ 0 đến 744.');
  return Math.round(rate * hours);
}
export function payrollPreview(previous, employee, hoursWorked, bonus, deduction) {
  if (!employee?.id || !/^\d{4}-(0[1-9]|1[0-2])$/.test(previous.month) || ![bonus, deduction].every(v => Number.isSafeInteger(v) && v >= 0 && v <= 1e12)) throw new Error('Thông tin phiếu lương không hợp lệ.');
  const rate = Number(employee.baseSalary || 0);
  return { ...previous, hoursWorked, hourlyRate: rate, baseSalary: basePay(rate, hoursWorked), bonus, deduction, revision: Number(previous.revision || 0) + 1 };
}
export function workMetrics(items, now = Date.now()) {
  const completed = items.filter(i => i.status === 'COMPLETED' && ms(i.completedAt) != null), late = completed.filter(i => ms(i.completedAt) > ms(i.deadline)).length;
  return { assigned: items.length, completed: completed.length, inProgress: items.filter(i => i.status === 'IN_PROGRESS').length, pendingReview: items.filter(i => i.status === 'PENDING_REVIEW').length, completedOnTime: completed.length - late, completedLate: late, reworked: items.filter(i => i.reworkCount > 0).length, reworkCount: items.reduce((a, i) => a + Number(i.reworkCount || 0), 0), overdue: items.filter(i => i.status !== 'COMPLETED' && ms(i.deadline) < now).length, onTimeCompletionRate: completed.length ? (completed.length - late) / completed.length : null };
}
export function presenceRows(data, date, now = Date.now()) {
  data = models(data);
  const schedules = approvedSchedules(data.workSchedules || data.schedules || [], data.offScheduleReviews || [], data.shifts || []), rows = assignScheduleDates(effectiveAttendance(data), schedules, data.shifts || []);
  return (data.employees || []).filter(e => e.active !== false).map(employee => {
    const schedule = schedules.find(s => s.employeeId === employee.id && s.date === date), selected = scheduledShifts(schedule, data.shifts || []), shift = selected.length <= 1 ? selected[0] : selected.find(s => shiftWindow(date, s).end > now) || selected.at(-1), leave = leaveFor(employee.id, date, schedule, data.shifts || [], data.leaveRequests || []);
    let adjustment = latestAdjustment(data.attendanceAdjustments || [], employee.id, date);
    const n = ms(adjustment?.checkInAt) ?? ms(adjustment?.checkOutAt);
    if (selected.length > 1 && n != null && [...selected].sort((a, b) => distance(n, adjustment.checkInAt != null ? 'CHECK_IN' : 'CHECK_OUT', shiftWindow(date, a)) - distance(n, adjustment.checkInAt != null ? 'CHECK_IN' : 'CHECK_OUT', shiftWindow(date, b)))[0]?.id !== shift?.id) adjustment = null;
    const own = rows.filter(r => r.employeeId === employee.id && belongs(r, date, shift)), p = pair(own, date, shift, adjustment);
    const status = leave.legacy || leave.ids.has(shift?.id) ? 'ON_LEAVE' : own.some(abnormal) || p.checkIn != null && p.checkOut != null && p.checkOut <= p.checkIn ? 'ABNORMAL' : p.checkOut != null ? 'LEFT' : p.checkIn == null ? 'NOT_CHECKED_IN' : missingCheckout(p, date, shift, now) ? 'MISSING_CHECK_OUT' : 'PRESENT';
    return { employee, shift, status, ...p, latest: own.filter(accepted).sort((a, b) => ms(b.timestamp) - ms(a.timestamp))[0] || null };
  });
}
