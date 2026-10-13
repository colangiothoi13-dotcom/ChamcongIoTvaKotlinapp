import { e, icon, button, field, select, person, dateLabel, dateAdd, today, normalize } from './ui.js';
import { validDate, monthDates, approvedSchedules, assignScheduleDates, effectiveAttendance, scheduledIds, scheduledShifts, daySummary, shiftWindow, checkoutGrace } from './domain.js';

const DAYS = ['Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy', 'Chủ nhật'];
const list = (ctx, name) => ctx.data?.[name] || [];
const weekday = date => new Date(`${date}T12:00:00Z`).getUTCDay();
const monday = date => dateAdd(date, -((weekday(date) + 6) % 7));
const monthValid = month => /^\d{4}-(0[1-9]|1[0-2])$/.test(month || '') && validDate(`${month}-01`);

export function scheduleCalendarRange(filters = {}, now = Date.now()) {
  const current = today(new Date(now));
  const week = monday(validDate(filters.week) ? filters.week : current);
  const month = monthValid(filters.month) ? filters.month : week.slice(0, 7);
  const view = filters.scheduleView === 'month' ? 'month' : 'week';
  const dates = view === 'month' ? monthDates(month) : Array.from({ length: 6 }, (_, i) => dateAdd(week, i));
  return { view, week, month, from: dates[0], to: dates.at(-1) };
}

export function selectedScheduleDate(filters = {}, now = Date.now()) {
  const range = scheduleCalendarRange(filters, now);
  const dates = range.view === 'month' ? monthDates(range.month) : Array.from({ length: 6 }, (_, i) => dateAdd(range.week, i));
  const operational = dates.filter(date => weekday(date) !== 0), current = today(new Date(now));
  return operational.includes(filters.scheduleDate) ? filters.scheduleDate : operational.includes(current) ? current : operational[0];
}

function duration(from, to) {
  const total = Math.max(0, Math.floor((to - from) / 60000)), hours = Math.floor(total / 60), minutes = total % 60;
  return hours ? `${hours} giờ${minutes ? ` ${minutes} phút` : ''}` : `${minutes} phút`;
}

// The summary comes from the same resolved scans and per-shift pairing used by reports.
export function scheduleShiftStatus(date, shift, summary, now = Date.now()) {
  const window = shiftWindow(date, shift), deadline = window.end + checkoutGrace(shift) * 60000;
  const checkIn = summary?.checkIn, checkOut = summary?.checkOut;
  if (summary?.status === 'LEAVE') return { label: 'Đã duyệt nghỉ phép', tone: 'neutral' };
  if (checkIn != null && checkOut != null) {
    const details = [`Đã làm ${duration(checkIn, checkOut)}`];
    if (summary.lateMinutes > 0) details.push(`Đi trễ ${summary.lateMinutes} phút`);
    if (summary.earlyLeaveMinutes > 0) details.push(`Về sớm ${summary.earlyLeaveMinutes} phút`);
    if (summary.overtimeHours > 0) details.push(`Tăng ca ${Number(summary.overtimeHours).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} giờ`);
    return { label: `Đã kết thúc ca • ${details.join(' • ')}`, tone: summary.status === 'ABNORMAL' ? 'error' : 'success' };
  }
  if (checkIn != null) {
    if (now > deadline) return { label: 'Thiếu chấm ra • Chưa tính giờ', tone: 'error' };
    const details = [];
    if (summary.lateMinutes > 0) details.push(`Đi trễ ${duration(window.start, checkIn)}`);
    const elapsed = Math.min(now, window.end);
    if (elapsed > checkIn) details.push(`Đã làm ${duration(checkIn, elapsed)}`);
    details.push('Chờ chấm ra');
    return { label: `Đang làm ca • ${details.join(' • ')}`, tone: 'active' };
  }
  if (now < window.start) return { label: 'Sắp vào ca', tone: 'neutral' };
  if (now > deadline) return { label: 'Thiếu chấm vào', tone: 'error' };
  return { label: 'Chưa chấm vào', tone: 'warning' };
}

function attendanceReady(ctx, range) {
  return ['employees', 'shifts', 'workSchedules'].every(name => ctx.loaded?.[name] === true && !ctx.errors?.[name])
    && ctx.range?.complete === true && !ctx.range.loading && !ctx.range.error
    && ctx.range.from <= range.from && ctx.range.to >= range.to;
}

function matchesEmployee(ctx, employee) {
  const filters = ctx.filters || {}, department = list(ctx, 'departments').find(row => row.id === filters.department);
  return employee.active === true && (!filters.employeeId || employee.id === filters.employeeId)
    && (!filters.department || employee.departmentId === filters.department || employee.department === filters.department || employee.department === department?.name)
    && (!filters.search || normalize(`${employee.fullName} ${employee.code || ''} ${employee.department || ''} ${employee.id}`).includes(normalize(filters.search)));
}

function controls(ctx, range) {
  const filters = ctx.filters || {};
  const view = select('scheduleView', 'Xem lịch', [{ value: 'week', label: 'Tuần' }, { value: 'month', label: 'Tháng' }], range.view)
    .replace('<select ', '<select data-filter="scheduleView" ');
  const departments = select('department', 'Phòng ban', [{ value: '', label: 'Tất cả phòng ban' }, ...list(ctx, 'departments').map(row => ({ value: row.id, label: row.name }))], filters.department || '')
    .replace('<select ', '<select data-filter="department" ');
  const dates = range.view === 'month'
    ? field('month', 'Tháng đang xem', range.month, 'month', 'data-filter="month"')
    : `${button('‹ Tuần trước', 'ops-week-prev')}${field('week', 'Tuần bắt đầu', range.week, 'date', 'data-filter="week"')}${button('Tuần sau ›', 'ops-week-next')}`;
  return `<div class="toolbar schedule-view-switch">${view}${dates}<label class="search-field">${icon('search')}<input type="search" data-filter="search" aria-label="Tìm kiếm" value="${e(filters.search || '')}" placeholder="Tên, mã nhân viên, phòng ban"></label>${departments}</div>`;
}

function rangeNotice(ctx, range, calculation) {
  if (calculation.ready) return '';
  const issue = calculation.error || ['employees', 'shifts', 'workSchedules'].map(name => ctx.errors?.[name]).find(Boolean) || ctx.range?.error;
  const message = issue || (ctx.range?.loading ? 'Đang tải đủ lịch và lượt chấm trong khoảng đang xem…' : 'Chưa tải đủ dữ liệu. Trạng thái chấm công sẽ hiện sau khi tải đầy đủ khoảng đang xem.');
  return `<div class="notice ${issue ? 'error' : ''}" role="status"><p>${e(message)}</p>${button(ctx.range?.loading ? 'Đang tải…' : 'Tải dữ liệu khoảng này', 'attendance-load-range', `data-from="${e(range.from)}" data-to="${e(range.to)}" ${ctx.range?.loading ? 'disabled' : ''}`)}</div>`;
}

function slot(ctx, employee, date, schedule, calculation) {
  const shifts = list(ctx, 'shifts'), selected = scheduledShifts(schedule, shifts), ids = scheduledIds(schedule);
  let summary, summaryIssue = false;
  if (calculation.ready && selected.length) {
    try { summary = daySummary(employee.id, date, ctx.data, calculation.rows, calculation.now, calculation.prepared); }
    catch { summaryIssue = true; }
  }
  const details = ids.length ? ids.map(id => {
    const shift = selected.find(row => row.id === id) || shifts.find(row => row.id === id);
    const label = shift ? `${shift.name} (${shift.startTime}–${shift.endTime})` : id;
    let status = '';
    if (!shift) status = '<small class="schedule-shift-status tone-error">Thiếu cấu hình ca</small>';
    else if (summaryIssue) status = '<small class="schedule-shift-status tone-error">Cần kiểm tra cấu hình ca</small>';
    else if (calculation.ready) {
      try {
        const shiftSummary = summary?.shiftSummaries.find(row => row.shiftId === id);
        const result = scheduleShiftStatus(date, shift, shiftSummary, calculation.now);
        status = `<small class="schedule-shift-status tone-${e(result.tone)}">${e(result.label)}</small>`;
      } catch { status = '<small class="schedule-shift-status tone-error">Cần kiểm tra cấu hình ca</small>'; }
    }
    return `<span class="schedule-shift"><span class="shift-pill">${e(label)}</span>${status}</span>`;
  }).join('') : '<span class="muted">+ Phân ca</span>';
  return `<button type="button" class="schedule-cell" data-action="ops-schedule-cell" data-employee="${e(employee.id)}" data-date="${e(date)}" aria-label="${e(`Phân ca ${employee.fullName} ngày ${dateLabel(date)}`)}">${details}${schedule?.workedHoursOverride != null ? '<small>Đã điều chỉnh công</small>' : ''}${schedule?.overtimeHours > 0 ? `<small>+${e(schedule.overtimeHours)} giờ tăng ca</small>` : ''}</button>`;
}

function weekGrid(ctx, employees, schedules, range, calculation) {
  const dates = Array.from({ length: 6 }, (_, i) => dateAdd(range.week, i));
  return `<div class="table-wrap"><table class="schedule-grid"><thead><tr><th scope="col">Nhân viên</th>${dates.map((date, i) => `<th scope="col">${DAYS[i]}<small>${dateLabel(date)}</small></th>`).join('')}</tr></thead><tbody>${employees.length ? employees.map(employee => `<tr><th scope="row">${person(employee.fullName, `${employee.code || ''} · ${employee.department || ''}`)}</th>${dates.map(date => `<td>${slot(ctx, employee, date, schedules.get(`${employee.id}_${date}`), calculation)}</td>`).join('')}</tr>`).join('') : '<tr><td colspan="7">Không có nhân viên phù hợp.</td></tr>'}</tbody></table></div>`;
}

function monthGrid(ctx, employees, schedules, range, calculation) {
  const dates = monthDates(range.month), selected = selectedScheduleDate(ctx.filters, calculation.now);
  const blanks = (weekday(dates[0]) + 6) % 7;
  const days = Array.from({ length: blanks }, () => '<span class="schedule-month-empty" aria-hidden="true"></span>');
  for (const date of dates) {
    const sunday = weekday(date) === 0;
    const count = employees.filter(employee => scheduledIds(schedules.get(`${employee.id}_${date}`)).length).length;
    days.push(`<button type="button" class="schedule-month-day ${date === selected ? 'active' : ''}" data-action="ops-schedule-day" data-date="${e(date)}" aria-pressed="${date === selected}" aria-controls="schedule-day-detail" aria-label="${e(`${dateLabel(date)} · ${sunday ? 'Chủ nhật, không phân ca' : `${count} nhân viên đã phân ca`}`)}" ${sunday ? 'disabled' : ''}><strong>${Number(date.slice(-2))}</strong><small>${sunday ? 'CN' : `${count} nhân viên`}</small></button>`);
  }
  return `<div class="schedule-month-grid" role="group" aria-label="Lịch tháng ${e(range.month)}">${DAYS.map(day => `<span class="schedule-month-heading">${e(day)}</span>`).join('')}${days.join('')}</div><section class="schedule-day-detail" id="schedule-day-detail" aria-label="Chi tiết ngày ${e(dateLabel(selected))}"><h3>Lịch ngày ${dateLabel(selected)}</h3><div class="table-wrap"><table><thead><tr><th scope="col">Nhân viên</th><th scope="col">Ca làm và chấm công</th></tr></thead><tbody>${employees.length ? employees.map(employee => `<tr><th scope="row">${person(employee.fullName, `${employee.code || ''} · ${employee.department || ''}`)}</th><td>${slot(ctx, employee, selected, schedules.get(`${employee.id}_${selected}`), calculation)}</td></tr>`).join('') : '<tr><td colspan="2">Không có nhân viên phù hợp.</td></tr>'}</tbody></table></div></section>`;
}

export function renderScheduleCalendar(ctx, now = Date.now()) {
  const range = scheduleCalendarRange(ctx.filters, now), employees = list(ctx, 'employees').filter(employee => matchesEmployee(ctx, employee));
  const effectiveSchedules = approvedSchedules(list(ctx, 'workSchedules'), list(ctx, 'offScheduleReviews'), list(ctx, 'shifts'));
  const schedules = new Map(effectiveSchedules.map(row => [`${row.employeeId}_${row.date}`, row]));
  const ready = attendanceReady(ctx, range);
  const calculation = { ready, rows: [], now, prepared: { schedules: effectiveSchedules, rows: [] }, error: '' };
  if (ready) {
    try {
      calculation.rows = assignScheduleDates(effectiveAttendance(ctx.data), effectiveSchedules, list(ctx, 'shifts'));
      calculation.prepared.rows = calculation.rows;
    } catch {
      calculation.ready = false;
      calculation.error = 'Cần kiểm tra cấu hình ca trước khi hiển thị trạng thái chấm công.';
    }
  }
  const title = range.view === 'month' ? `Tháng ${range.month}` : `Tuần ${dateLabel(range.from)} – ${dateLabel(range.to)}`;
  return `<section class="panel"><div class="panel-head"><div><h2>Lịch làm việc</h2><p class="muted">${e(title)} · Giờ Việt Nam</p></div><div class="actions">${range.view === 'week' ? button('Sao chép tuần trước', 'ops-week-copy') : ''}${button('Phân ca', 'ops-schedule-new', 'class="primary"')}</div></div>${controls(ctx, range)}${rangeNotice(ctx, range, calculation)}${range.view === 'month' ? monthGrid(ctx, employees, schedules, range, calculation) : weekGrid(ctx, employees, schedules, range, calculation)}<p class="muted panel-foot">Chọn ô lịch để sửa phân ca. Trạng thái cập nhật theo lượt chấm của từng ca; thời gian giữa lượt vào và ra có thể khác giờ tính lương. Phân thêm một loại ca giữ ca còn lại và điều chỉnh công đã có. Tăng ca 18:00–22:00 được quản lý tại Ca làm hoặc Đơn từ.</p></section>`;
}
