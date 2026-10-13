import { e, icon, badge, field, select, textarea, table, button, person, money, dateLabel, timeLabel, dateTimeLabel, today, normalize, emptyState, stat, csv } from './ui.js';
import { effectiveAttendance, accepted, abnormal, dateOf, ms, at, addDays, datesBetween, monthDates, reportRows, monthlyTimesheetRows, monthlyTimesheetEmployees, monthlyTotals, presenceRows, workMetrics, validDate, validateAdjustment, validateClassification, payrollPreview, basePay, latestAdjustment, daySummary, SUPPLEMENTARY } from './domain.js';

const list = (ctx, collection) => ctx.data[collection] || [];
const monthOf = ctx => ctx.filters.month || today().slice(0, 7);
const rangeOf = (ctx, page) => page === 'payroll' || page === 'performance' || page === 'reports' && ctx.filters.reportType === 'MONTHLY' ? { from: `${monthOf(ctx)}-01`, to: monthDates(monthOf(ctx)).at(-1) } : page === 'presence' ? { from: ctx.filters.from || today(), to: ctx.filters.from || today() } : { from: ctx.filters.from || today(), to: ctx.filters.to || ctx.filters.from || today() };
const ready = (ctx, range) => validDate(range.from) && validDate(range.to) && range.from <= range.to && ctx.loaded?.employees === true && !ctx.errors?.employees && !!ctx.range?.complete && !ctx.range.loading && ctx.range.from <= range.from && ctx.range.to >= range.to && !ctx.range.error;
const actor = ctx => ctx.user.displayName || ctx.user.email || 'Quản lý';
const plain = value => { const { id, ...data } = value; return data; };
const number = (value, max = 1e12) => { const n = Number(value); if (String(value).trim() === '' || !Number.isFinite(n) || n < 0 || n > max) throw new Error(`Giá trị phải từ 0 đến ${max.toLocaleString('vi-VN')}.`); return n; };
const reasonOf = value => { const r = String(value || '').trim(); if (!r || r.length > 500) throw new Error('Vui lòng nhập lý do, tối đa 500 ký tự.'); return r; };
const hh = value => Number(value || 0).toLocaleString('vi-VN', { maximumFractionDigits: 2 });
const method = row => row.method == null || row.method === 'FINGERPRINT' ? 'FINGERPRINT' : row.method === 'FACE' ? 'FACE' : 'UNKNOWN';
const net = p => Number(p.baseSalary || 0) + Number(p.bonus || 0) - Number(p.deduction || 0);
const labels = { PRESENT: 'Đang có mặt', LEFT: 'Đã rời ca', ON_LEAVE: 'Nghỉ phép', NOT_CHECKED_IN: 'Chưa chấm vào', MISSING_CHECK_IN: 'Thiếu chấm vào', MISSING_CHECK_OUT: 'Thiếu chấm ra', ABNORMAL: 'Cần kiểm tra', OVERTIME_PENDING: 'Tăng ca chờ duyệt', OVERTIME_REJECTED: 'Tăng ca bị từ chối', OUT_OF_ORDER: 'Sai thứ tự', PENDING_REVIEW: 'Chờ duyệt kết quả' };
const b = value => labels[value] ? `<span class="badge ${['ABNORMAL', 'MISSING_CHECK_IN', 'MISSING_CHECK_OUT', 'OVERTIME_REJECTED', 'OUT_OF_ORDER'].includes(value) ? 'bad' : value === 'PRESENT' ? 'good' : 'neutral'}">${e(labels[value])}</span>` : badge(value);
const matchesEmployee = (employee, ctx) => (!ctx.filters.employeeId || employee.id === ctx.filters.employeeId) && (!ctx.filters.department || employee.department === ctx.filters.department || employee.departmentId === ctx.filters.department) && (!ctx.filters.search || normalize(`${employee.fullName} ${employee.code} ${employee.department} ${employee.id}`).includes(normalize(ctx.filters.search)));
const visibleInMonth = (employee, month) => employee.active !== false || validDate(employee.terminationDate) && employee.terminationDate.slice(0, 7) >= month;
const historical = (ctx, month = monthOf(ctx)) => list(ctx, 'employees').filter(employee => visibleInMonth(employee, month));
const adjustmentChoices = (ctx, date) => [{ value: '', label: 'Chọn nhân viên' }, ...historical(ctx, validDate(date) ? date.slice(0, 7) : today().slice(0, 7)).map(employee => ({ value: employee.id, label: `${employee.code} · ${employee.fullName}${employee.active === false ? ' · Đã nghỉ' : ''}` }))];
const selectedEmployees = ctx => list(ctx, 'employees').filter(employee => matchesEmployee(employee, ctx) && (!ctx.filters.employeeId || employee.id === ctx.filters.employeeId));
function monthlyTimesheetView(ctx) {
  const month = monthOf(ctx), employees = list(ctx, 'employees'), now = Date.now();
  const summaries = monthlyTimesheetRows(ctx.data, month, employees, now);
  const staff = monthlyTimesheetEmployees(ctx.data, month, employees, now, summaries).filter(employee => matchesEmployee(employee, ctx));
  const ids = new Set(staff.map(employee => employee.id));
  return { staff, rows: summaries.filter(row => ids.has(row.employeeId)) };
}
const header = (title, description, tools = '') => `<div class="section-heading"><div><h2>${e(title)}</h2><p class="muted">${e(description)}</p></div><div class="actions">${tools}</div></div>`;
const adjustmentButton = (row, attrs = '') => {
  const date = row.scheduleDate || dateOf(row.timestamp);
  return row.employeeId && validDate(date) ? button('Điều chỉnh công', 'attendance-adjust', `data-id="${e(row.id)}" data-employee="${e(row.employeeId)}" data-date="${e(date)}" ${attrs}`) : '';
};
const salaryButton = employee => employee && employee.active !== false ? button('Đặt đơn giá giờ', 'org-employee-salary', `data-id="${e(employee.id)}"`) : '';
function dayHoursBreakdown(employee, rows, month) {
  const shifts = rows.flatMap(row => row.shiftSummaries || []);
  const metrics = [
    ['Ngày có công', rows.filter(row => row.workedHours > 0 || row.overtimeHours > 0 || row.checkIn != null || row.checkOut != null).length],
    ['Đi trễ', `${rows.filter(row => row.lateMinutes > 0).length} ngày · ${rows.reduce((sum, row) => sum + (row.lateMinutes || 0), 0)} phút`],
    ['Về sớm', `${rows.filter(row => row.earlyLeaveMinutes > 0).length} ngày · ${rows.reduce((sum, row) => sum + (row.earlyLeaveMinutes || 0), 0)} phút`],
    ['Thiếu chấm công', `${shifts.filter(shift => ['MISSING_CHECK_IN', 'MISSING_CHECK_OUT'].includes(shift.status)).length} ca`],
    ['Bất thường', `${shifts.filter(shift => shift.status === 'ABNORMAL').length} ca`],
    ['Nghỉ phép', `${shifts.filter(shift => shift.status === 'LEAVE').length} ca`]
  ];
  const days = rows.filter(row => row.shiftSummaries?.length || row.checkIn != null || row.checkOut != null || row.workedHours > 0 || row.overtimeHours > 0);
  return `<details class="detail-section"><summary>Chi tiết giờ công theo ngày và ca · ${e(month)}</summary><p class="muted">${e(employee.fullName)}. Giờ quét gốc và giờ tính công theo ca dùng cùng dữ liệu hiệu lực với tổng giờ. Điều chỉnh giờ công thay thế được phản ánh ở tổng ngày.</p>${table(['Chỉ số', 'Giá trị'], metrics.map(([label, value]) => [e(label), e(value)]))}${days.map(day => `<section class="detail-section"><h3>${e(dateLabel(day.date))} · Thường ${hh(day.workedHours)} h · Tăng ca ${hh(day.overtimeHours)} h</h3>${day.shiftSummaries?.length ? table(['Ca', 'Trạng thái', 'Quét vào → ra', 'Tính công vào → ra', 'Giờ thường', 'Tăng ca', 'Trễ / sớm'], day.shiftSummaries.map(shift => [e(shift.shiftName || shift.shiftId), b(shift.status), `${e(timeLabel(shift.rawCheckInAt))} → ${e(timeLabel(shift.rawCheckOutAt))}`, `${e(timeLabel(shift.paidCheckInAt))} → ${e(timeLabel(shift.paidCheckOutAt))}`, `${hh(shift.workedHours)} h`, `${hh(shift.overtimeHours)} h`, `${e(shift.lateMinutes || 0)} / ${e(shift.earlyLeaveMinutes || 0)} phút`])) : `<p>${b(day.status)} · Vào ${e(timeLabel(day.checkIn))} · Ra ${e(timeLabel(day.checkOut))}</p>`}</section>`).join('') || '<p>Chưa có dữ liệu ngày hoặc ca trong tháng.</p>'}<p class="muted">Dấu —: chưa có giờ tương ứng của ca.</p></details>`;
}
async function timesheetDetail(ctx, employeeId) {
  const month = monthOf(ctx), dates = monthDates(month), range = { from: dates[0], to: dates.at(-1) };
  if (!ready(ctx, range)) await ctx.loadRange(range.from, range.to);
  if (!ready(ctx, range)) throw new Error('Dữ liệu tháng chưa đầy đủ; chưa thể xem chi tiết giờ công.');
  const view = monthlyTimesheetView(ctx), employee = view.staff.find(row => row.id === employeeId);
  if (!employee) throw new Error('Nhân viên không thuộc kỳ bảng công này.');
  const rows = view.rows.filter(row => row.employeeId === employeeId);
  ctx.ui.dialog({ title: `Bảng công tháng · ${employee.fullName}`, wide: true, submitLabel: null, body: `<p>${e(month)} · Giờ thường ${hh(rows.reduce((sum, row) => sum + row.workedHours, 0))} h · Tăng ca ${hh(rows.reduce((sum, row) => sum + row.overtimeHours, 0))} h</p>${dayHoursBreakdown(employee, rows, month)}` });
}
function filters(ctx, { month = false, dates = true, single = false, status = [] } = {}) {
  const departments = [...new Set(list(ctx, 'employees').map(e => e.department).filter(Boolean))].sort();
  const employees = list(ctx, 'employees').filter(employee => !ctx.filters.department || employee.department === ctx.filters.department || employee.departmentId === ctx.filters.department);
  return `<div class="filter-bar"><label class="search-field">${icon('search')}<input type="search" placeholder="Tìm nhân viên, mã, phòng ban…" aria-label="Tìm nhân viên" data-filter="search" value="${e(ctx.filters.search || '')}"></label><select aria-label="Phòng ban" data-filter="department"><option value="">Tất cả phòng ban</option>${departments.map(d => `<option value="${e(d)}" ${ctx.filters.department === d ? 'selected' : ''}>${e(d)}</option>`).join('')}</select><select aria-label="Nhân viên" data-filter="employeeId"><option value="">Tất cả nhân viên</option>${employees.map(employee => `<option value="${e(employee.id)}" ${ctx.filters.employeeId === employee.id ? 'selected' : ''}>${e(`${employee.code} · ${employee.fullName}`)}</option>`).join('')}</select>${month ? `<label>Tháng <input type="month" data-filter="month" value="${e(monthOf(ctx))}"></label>` : dates ? `<label>${single ? 'Ngày' : 'Từ ngày'} <input aria-label="Từ ngày" type="date" data-filter="from" value="${e(ctx.filters.from || today())}"></label>${single ? '' : `<label>Đến ngày <input aria-label="Đến ngày" type="date" data-filter="to" value="${e(ctx.filters.to || ctx.filters.from || today())}"></label>`}` : ''}${status.length ? `<select data-filter="status" aria-label="Trạng thái"><option value="">Tất cả trạng thái</option>${status.map(v => `<option value="${e(v)}" ${ctx.filters.status === v ? 'selected' : ''}>${e(labels[v] || ({ CHECK_IN: 'Vào ca', CHECK_OUT: 'Ra ca', LATE: 'Đi trễ', NORMAL: 'Bình thường', DUPLICATE: 'Quét trùng', UNSCHEDULED: 'Ngoài lịch', PENDING: 'Chờ xử lý' })[v] || v)}</option>`).join('')}</select>` : ''}</div>`;
}
function rangeNotice(ctx, range) {
  if (ready(ctx, range)) return '';
  const message = ctx.errors?.employees || (ctx.loaded?.employees !== true ? 'Đang chờ danh sách nhân viên đầy đủ…' : ctx.range?.loading ? 'Đang tải đủ lượt chấm, lịch làm, nghỉ phép, tăng ca và lịch sử điều chỉnh…' : ctx.range?.error || 'Tải đầy đủ dữ liệu trong khoảng đã chọn để xem công và tính lương chính xác.');
  return `<div class="notice ${ctx.range?.error ? 'error' : ''}" role="status"><p>${e(message)}</p>${button(ctx.range?.loading ? 'Đang tải…' : 'Tải dữ liệu khoảng này', 'attendance-load-range', `data-from="${e(range.from)}" data-to="${e(range.to)}" ${ctx.range?.loading ? 'disabled' : ''}`)}</div>`;
}
function scans(ctx, range) {
  const ids = new Set(selectedEmployees(ctx).map(v => v.id));
  return effectiveAttendance(ctx.data).filter(row => ids.has(row.employeeId) && (row.scheduleDate || dateOf(row.timestamp)) >= range.from && (row.scheduleDate || dateOf(row.timestamp)) <= range.to && (!ctx.filters.status || row.type === ctx.filters.status || row.status === ctx.filters.status || row.resolutionStatus === ctx.filters.status)).sort((a, b) => ms(b.timestamp) - ms(a.timestamp));
}
function attendancePage(ctx) {
  const range = rangeOf(ctx, 'attendance'), loaded = ready(ctx, range), rows = loaded ? scans(ctx, range) : [];
  return header('Chấm công', 'Lượt quét từ thiết bị và kết quả xử lý theo từng ca. Chọn Điều chỉnh công để bổ sung giờ vào, giờ ra hoặc giờ công.', button('Điều chỉnh công', 'attendance-adjust', 'class="primary"') + button('Xuất CSV', 'attendance-export', !loaded ? 'disabled' : '')) + filters(ctx, { status: ['CHECK_IN', 'CHECK_OUT', 'LATE', 'DUPLICATE', 'UNSCHEDULED', 'PENDING'] }) + rangeNotice(ctx, range) + (loaded ? `<div class="stats-grid">${stat('Lượt hợp lệ', rows.filter(accepted).length, 'Theo bộ lọc đang chọn', 'check')}${stat('Đi trễ', rows.filter(r => r.status === 'LATE').length, 'Lượt vào ca đi trễ', 'clock')}${stat('Cần kiểm tra', rows.filter(abnormal).length, 'Chờ xử lý hoặc bất thường', 'shield')}</div><section class="card">${table(['Nhân viên', 'Thời gian quét', 'Ngày ca / ca', 'Vào · ra', 'Trạng thái', 'Phương thức', 'Thiết bị', 'Thao tác'], rows.map(row => {
    const source = list(ctx, 'attendance').find(r => r.id === row.id), correctionAllowed = source && source.verified !== false && (accepted(source) || source.type === 'SCAN' && source.resolutionStatus === 'PENDING');
    const employee = list(ctx, 'employees').find(v => v.id === row.employeeId);
    return [person(row.employeeName || employee?.fullName || row.employeeId, employee?.code), e(dateTimeLabel(row.timestamp)), `${e(row.scheduleDate || dateOf(row.timestamp))}<small>${e(list(ctx, 'shifts').find(s => s.id === row.shiftId)?.name || (row.shiftId === 'SUPPLEMENTARY_1800_2200' ? 'Tăng ca' : 'Chưa xác định ca'))}</small>`, b(row.type), `${b(row.status)} ${row.resolutionStatus !== 'ACCEPTED' ? b(row.resolutionStatus) : ''}`, badge(method(row)), e(row.deviceId || row.stationId || '—'), `<div class="row-actions">${adjustmentButton(row)}${button('Chi tiết', 'attendance-detail', `data-id="${e(row.id)}"`)}${correctionAllowed ? button('Sửa phân loại', 'attendance-classify', `data-id="${e(row.id)}"`) : ''}${row.type === 'UNSCHEDULED' || row.resolutionStatus === 'UNSCHEDULED' || source?.type === 'SCAN' && row.resolutionStatus === 'PENDING' ? button('Xử lý ngoài lịch', 'attendance-offschedule', `data-id="${e(row.id)}"`) : ''}</div>`];
  }))}</section>` : '');
}
function presencePage(ctx) {
  const range = rangeOf(ctx, 'presence'), loaded = ready(ctx, range), rows = loaded ? presenceRows(ctx.data, range.from).filter(r => matchesEmployee(r.employee, ctx) && (!ctx.filters.status || r.status === ctx.filters.status)) : [];
  return header('Có mặt', 'Tình trạng nhân viên theo ca đang diễn ra; cập nhật cùng dữ liệu thiết bị.', button('Làm mới', 'attendance-load-range', `data-from="${e(range.from)}" data-to="${e(range.to)}"`)) + filters(ctx, { single: true, status: ['PRESENT', 'LEFT', 'NOT_CHECKED_IN', 'ON_LEAVE', 'MISSING_CHECK_OUT', 'ABNORMAL'] }) + rangeNotice(ctx, range) + (loaded ? `<div class="stats-grid">${stat('Đang có mặt', rows.filter(r => r.status === 'PRESENT').length, 'Đã vào và chưa ra ca', 'users')}${stat('Đã rời ca', rows.filter(r => r.status === 'LEFT').length, 'Có lượt ra hợp lệ', 'check')}${stat('Nghỉ phép', rows.filter(r => r.status === 'ON_LEAVE').length, 'Ca được duyệt nghỉ', 'calendar')}${stat('Cần kiểm tra', rows.filter(r => ['ABNORMAL', 'MISSING_CHECK_OUT'].includes(r.status)).length, 'Kiểm tra lượt chấm và điều chỉnh', 'shield')}</div><section class="card">${table(['Nhân viên', 'Phòng ban', 'Ca đang theo dõi', 'Hiện diện', 'Giờ vào', 'Giờ ra', 'Lượt gần nhất'], rows.map(r => [person(r.employee.fullName, r.employee.code), e(r.employee.department || '—'), e(r.shift?.name || 'Chưa có lịch'), b(r.status), e(timeLabel(r.checkIn)), e(timeLabel(r.checkOut)), e(dateTimeLabel(r.latest?.timestamp))]))}</section>` : '');
}
function payrollPage(ctx) {
  const month = monthOf(ctx), range = rangeOf(ctx, 'payroll'), loaded = ready(ctx, range), employees = historical(ctx), totals = loaded ? monthlyTotals(ctx.data, month, employees) : new Map();
  const slips = list(ctx, 'payroll').filter(p => p.month === month && matchesEmployee(list(ctx, 'employees').find(e => e.id === p.employeeId) || { id: p.employeeId, fullName: p.employeeName, code: p.employeeCode }, ctx));
  const slipActions = p => `<div class="row-actions">${button('Xem / tính lại', 'payroll-recalculate', `data-id="${e(p.id || `${p.employeeId}_${p.month}`)}"`)}${button('Lịch sử', 'payroll-history', `data-id="${e(p.id || `${p.employeeId}_${p.month}`)}"`)}${salaryButton(list(ctx, 'employees').find(employee => employee.id === p.employeeId))}</div>`;
  const currentActions = v => {
    const slip = slips.find(p => p.employeeId === v.employee.id);
    return `<div class="row-actions">${slip ? button('Xem / tính lại', 'payroll-recalculate', `data-id="${e(slip.id || `${slip.employeeId}_${slip.month}`)}"`) : button('Tạo phiếu', 'payroll-create', `data-id="${e(v.employee.id)}" class="primary"`)}${salaryButton(v.employee)}</div>`;
  };
  return header('Bảng lương', 'Đặt đơn giá giờ, lập phiếu và quản lý phiếu đã lưu. Phiếu chỉ thay đổi khi xác nhận lưu hoặc tính lại.', button('Lập / quản lý phiếu', 'payroll-create', `class="primary" ${!loaded ? 'disabled' : ''}`) + button('Đặt đơn giá giờ', 'payroll-salary-picker', ctx.loaded?.employees !== true || ctx.errors?.employees ? 'disabled' : '') + button('Xuất bảng lương', 'payroll-export')) + filters(ctx, { month: true }) + rangeNotice(ctx, range) + `<div class="stats-grid">${stat('Thực nhận đã lưu', money(slips.reduce((a, p) => a + net(p), 0)), `${slips.length} phiếu lương tháng ${month}`, 'wallet')}${stat('Giờ trên phiếu', hh(slips.reduce((a, p) => a + Number(p.hoursWorked || 0), 0)), 'Giờ công đã chốt', 'clock')}${stat('Tổng thưởng', money(slips.reduce((a, p) => a + Number(p.bonus || 0), 0)), 'Theo phiếu đã lưu', 'chart')}</div><section class="card">${table(['Nhân viên', 'Giờ đã chốt', 'Đơn giá giờ', 'Lương cơ bản', 'Thưởng', 'Khấu trừ', 'Thực nhận', 'Phiên bản', 'Thao tác'], slips.map(p => [person(p.employeeName, p.employeeCode), `${hh(p.hoursWorked)} h${totals.has(p.employeeId) && Math.abs(totals.get(p.employeeId).hoursWorked - Number(p.hoursWorked || 0)) > .005 ? `<small class="text-warning">Dữ liệu hiện tại: ${hh(totals.get(p.employeeId).hoursWorked)} h</small>` : ''}`, money(p.hourlyRate), money(p.baseSalary), money(p.bonus), money(p.deduction), `<strong>${money(net(p))}</strong>`, `v${Number(p.revision || 0)}`, slipActions(p)]))}</section>${loaded ? `<section class="card"><div class="card-heading"><h3>Công và thưởng tham khảo trước khi chốt</h3><p class="muted">Giờ thường + tăng ca; nghỉ theo từng ca được loại khỏi giờ thường.</p></div>${table(['Nhân viên', 'Giờ thường', 'Tăng ca', 'Tổng giờ tính lương', 'Đi trễ', 'Thưởng tự động', 'Thao tác'], [...totals.values()].filter(v => matchesEmployee(v.employee, ctx)).map(v => [person(v.employee.fullName, v.employee.code), `${hh(v.regularHours)} h`, `${hh(v.overtimeHours)} h`, `<strong>${hh(v.hoursWorked)} h</strong>`, `${v.lateCount} lần`, money(v.totalBonus), currentActions(v)]))}</section>` : ''}`;
}
const reportTypes = [{ value: 'WORK_ITEMS', label: 'Kết quả công việc' }, { value: 'ATTENDANCE', label: 'Chấm công theo ngày' }, { value: 'WORK_SUMMARY', label: 'Tổng hợp ngày công' }, { value: 'MONTHLY', label: 'Bảng công tháng' }, { value: 'LATE_EARLY', label: 'Đi trễ / về sớm' }, { value: 'LEAVE', label: 'Nghỉ phép' }, { value: 'OVERTIME', label: 'Tăng ca' }, { value: 'PAYROLL', label: 'Bảng lương' }, { value: 'DEVICES', label: 'Hoạt động thiết bị' }];
function reportWork(ctx) {
  const range = rangeOf(ctx, 'reports'), ids = new Set(selectedEmployees(ctx).map(e => e.id));
  return list(ctx, 'workItems').filter(t => dateOf(t.deadline) >= range.from && dateOf(t.deadline) <= range.to && (!ctx.filters.department && !ctx.filters.employeeId && !ctx.filters.search || ids.has(t.assigneeId))).sort((a, b) => ms(a.deadline) - ms(b.deadline) || a.id.localeCompare(b.id));
}
function summaryWork(ctx, rows) {
  return selectedEmployees(ctx).map(employee => { const own = rows.filter(r => r.employeeId === employee.id); return { employee, workdays: own.filter(r => r.workedSeconds > 0).length, regularHours: own.reduce((a, r) => a + r.workedHours, 0), overtimeHours: own.reduce((a, r) => a + r.overtimeHours, 0), lateCount: own.filter(r => r.lateMinutes > 0).length, earlyCount: own.filter(r => r.earlyLeaveMinutes > 0).length, leaveCount: own.filter(r => r.status === 'LEAVE' || r.approvedLeaveShiftCount > 0).length, missingCount: own.filter(r => ['MISSING_CHECK_IN', 'MISSING_CHECK_OUT'].includes(r.status)).length }; });
}
function selectedReportRows(ctx, timesheet = null) {
  const range = rangeOf(ctx, 'reports'), type = ctx.filters.reportType || 'ATTENDANCE';
  let rows = type === 'MONTHLY' ? (timesheet || monthlyTimesheetView(ctx)).rows : reportRows(ctx.data, range.from, range.to, selectedEmployees(ctx));
  if (type === 'LATE_EARLY') rows = rows.filter(r => r.lateMinutes > 0 || r.earlyLeaveMinutes > 0);
  if (type === 'LEAVE') rows = rows.filter(r => r.status === 'LEAVE' || r.approvedLeaveShiftCount > 0);
  if (type === 'OVERTIME') rows = rows.filter(r => r.overtimeHours > 0);
  return rows;
}
function reportsPage(ctx) {
  const type = ctx.filters.reportType || 'ATTENDANCE', range = rangeOf(ctx, 'reports'), loaded = ready(ctx, range), independent = ['PAYROLL', 'DEVICES', 'WORK_ITEMS'].includes(type), workReady = ctx.loaded?.workItems === true && !ctx.errors?.workItems;
  let body = '';
  if (type === 'WORK_ITEMS') {
    const rows = workReady ? reportWork(ctx) : [], m = workMetrics(rows);
    body = workReady ? `<p class="muted">Lọc theo hạn hoàn thành; thời điểm hoàn thành là lúc quản lý duyệt.</p><div class="stats-grid">${stat('Được giao', m.assigned, `${m.completed} việc hoàn thành`, 'tasks')}${stat('Đúng hạn', m.completedOnTime, m.onTimeCompletionRate == null ? 'Chưa có việc hoàn thành' : `${hh(m.onTimeCompletionRate * 100)}% việc hoàn thành`, 'check')}${stat('Đang quá hạn', m.overdue, `${m.completedLate} việc hoàn thành muộn`, 'clock')}</div>${table(['Công việc', 'Nhân viên', 'Bắt đầu', 'Hạn hoàn thành', 'Trạng thái', 'Hoàn thành lúc', 'Kết quả', 'Phản hồi', 'Làm lại'], rows.map(t => [e(t.title), person(t.assigneeName, list(ctx, 'employees').find(e => e.id === t.assigneeId)?.code), e(dateTimeLabel(t.startAt)), e(dateTimeLabel(t.deadline)), b(t.status), e(dateTimeLabel(t.completedAt)), `<span style="white-space:normal">${e(t.resultReport)}</span>`, `<span style="white-space:normal">${e(t.managerFeedback)}</span>`, e(t.reworkCount || 0)]))}` : `<div class="notice error">${e(ctx.errors?.workItems || 'Đang tải đầy đủ công việc…')}</div>`;
  } else if (type === 'DEVICES') body = table(['Thiết bị', 'Kết nối', 'Heartbeat gần nhất', 'Firmware', 'Mẫu vân tay', 'Dung lượng', 'Lệnh lỗi'], list(ctx, 'devices').map(d => [e(d.id), badge(d.status), e(dateTimeLabel(d.lastHeartbeat)), e(d.firmwareVersion || '—'), e(d.fingerprintCount || 0), e(d.capacity || '—'), e(list(ctx, 'deviceCommands').filter(c => c.deviceId === d.id && c.status === 'FAILED').length)]));
  else if (type === 'PAYROLL') body = table(['Nhân viên', 'Tháng', 'Giờ công', 'Đơn giá giờ', 'Lương cơ bản', 'Thưởng', 'Khấu trừ', 'Thực nhận'], list(ctx, 'payroll').filter(p => p.month === monthOf(ctx) && matchesEmployee(list(ctx, 'employees').find(e => e.id === p.employeeId) || { id: p.employeeId, fullName: p.employeeName, code: p.employeeCode }, ctx)).map(p => [person(p.employeeName, p.employeeCode), e(p.month), hh(p.hoursWorked), money(p.hourlyRate), money(p.baseSalary), money(p.bonus), money(p.deduction), money(net(p))]));
  else if (loaded) {
    const timesheet = type === 'MONTHLY' ? monthlyTimesheetView(ctx) : null, rows = selectedReportRows(ctx, timesheet);
    body = `<div class="stats-grid">${stat('Giờ thường', hh(rows.reduce((a, r) => a + r.workedHours, 0)), `${rows.length} dòng báo cáo`, 'clock')}${stat('Giờ tăng ca', hh(rows.reduce((a, r) => a + r.overtimeHours, 0)), 'Theo ca và đơn đã duyệt', 'chart')}${stat('Đi trễ / về sớm', rows.filter(r => r.lateMinutes || r.earlyLeaveMinutes).length, 'Ngày có phát sinh', 'shield')}</div>`;
    if (type === 'WORK_SUMMARY') body += table(['Nhân viên', 'Ngày có công', 'Giờ thường', 'Tăng ca', 'Ngày đi trễ', 'Ngày về sớm', 'Ngày có nghỉ phép', 'Ngày thiếu chấm'], summaryWork(ctx, rows).map(v => [person(v.employee.fullName, v.employee.code), e(v.workdays), hh(v.regularHours), hh(v.overtimeHours), e(v.lateCount), e(v.earlyCount), e(v.leaveCount), e(v.missingCount)]));
    else if (type === 'MONTHLY') {
      const days = datesBetween(range.from, range.to), employees = timesheet.staff, map = new Map(rows.map(r => [`${r.employeeId}_${r.date}`, r]));
      body += table(['Nhân viên', ...days.map(d => d.slice(-2)), 'Giờ thường', 'Tăng ca', 'Thao tác'], employees.map(employee => {
        const own = rows.filter(r => r.employeeId === employee.id);
        return [person(employee.fullName, employee.code), ...days.map(date => { const r = map.get(`${employee.id}_${date}`); return r ? `<span title="${e(`${labels[r.status] || r.status}; tăng ca ${hh(r.overtimeHours)} giờ`)}">${r.status === 'LEAVE' ? 'P' : hh(r.workedHours + r.overtimeHours)}</span>` : '—'; }), `<strong>${hh(own.reduce((a, r) => a + r.workedHours, 0))}</strong>`, hh(own.reduce((a, r) => a + r.overtimeHours, 0)), button('Chi tiết ngày / ca', 'timesheet-detail', `data-id="${e(employee.id)}"`)];
      }));
    } else body += table(['Ngày', 'Nhân viên', 'Phòng ban', 'Vào', 'Ra', 'Trạng thái', 'Giờ thường', 'Tăng ca', 'Trễ (phút)', 'Sớm (phút)', 'Ca nghỉ'], rows.map(r => [e(dateLabel(`${r.date}T12:00:00+07:00`)), person(r.employeeName, r.code), e(r.department), e(timeLabel(r.checkIn)), e(timeLabel(r.checkOut)), b(r.status), hh(r.workedHours), hh(r.overtimeHours), e(r.lateMinutes), e(r.earlyLeaveMinutes), e(r.approvedLeaveShiftCount)]));
  }
  return header('Báo cáo & bảng công', 'Đối chiếu công, nghỉ phép, tăng ca, lương và hoạt động thiết bị.', button('Xuất CSV', 'reports-export', !independent && !loaded || type === 'WORK_ITEMS' && !workReady ? 'disabled' : '')) + `<div class="filter-bar"><label>Loại báo cáo <select data-filter="reportType">${reportTypes.map(r => `<option value="${r.value}" ${r.value === type ? 'selected' : ''}>${e(r.label)}</option>`).join('')}</select></label></div>` + filters(ctx, { month: ['MONTHLY', 'PAYROLL'].includes(type), dates: type !== 'DEVICES' }) + (!independent ? rangeNotice(ctx, range) : '') + `<section class="card">${body}</section>`;
}
function performancePage(ctx) {
  const month = monthOf(ctx), range = rangeOf(ctx, 'performance'), loaded = ready(ctx, range), staff = historical(ctx), totals = loaded ? monthlyTotals(ctx.data, month, staff) : new Map(), workReady = ctx.loaded?.workItems === true && !ctx.errors?.workItems;
  const tasks = list(ctx, 'workItems').filter(t => dateOf(t.deadline).startsWith(`${month}-`));
  const ids = [...new Set([...staff.map(e => e.id), ...tasks.map(t => t.assigneeId)])];
  const rows = ids.map(id => { const employee = list(ctx, 'employees').find(e => e.id === id) || { id, fullName: tasks.find(t => t.assigneeId === id)?.assigneeName || id }; return { employee, metrics: workMetrics(tasks.filter(t => t.assigneeId === id)), kpi: totals.get(id) }; }).filter(v => matchesEmployee(v.employee, ctx));
  return header('Hiệu suất', 'Kết quả công việc tính theo lúc quản lý duyệt hoàn thành; công và tăng ca hỗ trợ tính thưởng.', button('Xuất CSV', 'performance-export', !workReady ? 'disabled' : '')) + filters(ctx, { month: true }) + (!workReady ? `<div class="notice error">${e(ctx.errors?.workItems || 'Đang tải đầy đủ công việc để tính hiệu suất…')}</div>` : `<section class="card"><div class="card-heading"><h3>Kết quả công việc</h3><p class="muted">Công việc có hạn hoàn thành trong tháng ${e(month)}.</p></div>${table(['Nhân viên', 'Được giao', 'Hoàn thành', 'Đang làm', 'Chờ duyệt', 'Đúng hạn', 'Hoàn thành muộn', 'Đang quá hạn', 'Làm lại / lần', 'Tỷ lệ đúng hạn', 'Đánh giá'], rows.map(v => { const m = v.metrics, review = list(ctx, 'performanceReviews').find(r => r.employeeId === v.employee.id && r.period === month); return [person(v.employee.fullName, v.employee.code), e(m.assigned), e(m.completed), e(m.inProgress), e(m.pendingReview), e(m.completedOnTime), e(m.completedLate), `<strong class="${m.overdue ? 'text-warning' : ''}">${m.overdue}</strong>`, `${m.reworked} / ${m.reworkCount}`, m.onTimeCompletionRate == null ? 'Chưa có việc hoàn thành' : `${hh(m.onTimeCompletionRate * 100)}%`, `${review ? `<strong>${hh(review.score)} / 100</strong><small>${e(review.note)}</small>` : ''}${button(review ? 'Sửa đánh giá' : 'Đánh giá', 'performance-review', `data-id="${e(v.employee.id)}"`)}`]; }))}</section>`) + rangeNotice(ctx, range) + (loaded ? `<section class="card"><div class="card-heading"><h3>Chấm công và tăng ca hỗ trợ</h3><p class="muted">Top 3 không đi trễ: 500.000 đ; mỗi ca tăng ca hoàn thành: 50.000 đ; mỗi ngày đi trễ: trừ 100.000 đ, thưởng tối thiểu 0 đ.</p></div>${table(['Nhân viên', 'Hạng Top 3', 'Ca tăng ca', 'Giờ tăng ca', 'Ngày đi trễ', 'Thưởng Top 3', 'Thưởng tăng ca', 'Phạt đi trễ', 'Thưởng tự động'], rows.filter(v => v.kpi).map(v => [person(v.employee.fullName, v.employee.code), v.kpi.top3Rank ? `#${v.kpi.top3Rank}` : v.kpi.lateCount ? 'Có đi trễ' : '—', e(v.kpi.overtimeShiftCount), hh(v.kpi.supplementaryHours), e(v.kpi.lateCount), money(v.kpi.top3Bonus), money(v.kpi.overtimeBonus), money(v.kpi.latePenalty), `<strong>${money(v.kpi.totalBonus)}</strong>`]))}</section>` : '');
}
export function render(page, ctx) {
  return ({ attendance: attendancePage, presence: presencePage, payroll: payrollPage, reports: reportsPage, performance: performancePage })[page]?.(ctx) || emptyState('Chưa có nội dung');
}
async function all(ctx, collection, where = []) {
  const rows = []; let cursor;
  do { const result = await ctx.store.list(collection, { where, limit: 250, startAfter: cursor }); rows.push(...result.rows); if (!result.hasMore) return rows; if (!result.cursor || result.cursor === cursor || rows.length >= 100000) throw new Error('Chưa tải đủ dữ liệu; vui lòng thu hẹp phạm vi rồi thử lại.'); cursor = result.cursor; } while (true);
}
const stamped = ctx => ctx.store.stamp();
const timestamp = (ctx, value) => value == null ? null : ctx.store.timestamp(new Date(value));
async function requireRange(ctx, page) { const range = rangeOf(ctx, page); if (!ready(ctx, range)) { await ctx.loadRange(range.from, range.to); if (!ready(ctx, range)) throw new Error('Chưa có đủ dữ liệu để thực hiện phép tính.'); } return range; }
const localDateTime = value => {
  const time = ms(value);
  return time == null ? '' : new Date(time + 7 * 3600000).toISOString().slice(0, 23);
};
async function adjustmentDialog(ctx, { employeeId = ctx.filters.employeeId || '', date = ctx.filters.from || today(), attendanceId = '', contextual = false } = {}) {
  if (contextual) {
    if (!employeeId || !validDate(date)) throw new Error('Nhân viên hoặc ngày cần điều chỉnh không hợp lệ.');
    if (!ready(ctx, { from: date, to: date })) await ctx.loadRange(date, date);
    if (!ready(ctx, { from: date, to: date })) throw new Error('Chưa tải đủ dữ liệu ngày để xem giờ công hiện tại.');
  }
  const employees = list(ctx, 'employees'), selected = employees.find(employee => employee.id === employeeId);
  let current = null, explanation = '', previous = null;
  if (contextual) {
    if (!selected) throw new Error('Không tìm thấy nhân viên cần điều chỉnh.');
    const row = effectiveAttendance(ctx.data).find(row => row.id === attendanceId && row.employeeId === employeeId);
    const summary = daySummary(employeeId, date, ctx.data), regular = summary.shiftSummaries.filter(shift => shift.shiftId !== SUPPLEMENTARY);
    if (row?.shiftId === SUPPLEMENTARY) explanation = 'Lượt này thuộc tăng ca. Điền giờ đã xác minh; điều chỉnh công không thay quyết định duyệt tăng ca.';
    else if (row?.shiftId) current = regular.find(shift => shift.shiftId === row.shiftId) || null;
    else if (regular.length === 1) current = regular[0];
    else if (!summary.shiftSummaries.length) current = summary;
    if (current) explanation = `Giờ vào và ra hiện tại${current.shiftName ? ` của ca ${current.shiftName}` : ''} đã được điền sẵn. Kiểm tra và nhập lý do trước khi lưu.`;
    else if (!explanation) explanation = 'Ngày này có nhiều ca hoặc chưa xác định được ca. Điền cặp giờ đã xác minh cho ca cần điều chỉnh.';
    previous = latestAdjustment(list(ctx, 'attendanceAdjustments'), employeeId, date);
  }
  const choices = adjustmentChoices(ctx, date), initialEmployee = choices.some(choice => choice.value === employeeId) ? employeeId : '';
  const employeeField = contextual ? `${field('employeeName', 'Nhân viên', `${selected.code || ''} · ${selected.fullName}`, 'text', 'readonly')}<input type="hidden" name="employeeId" value="${e(employeeId)}">` : select('employeeId', 'Nhân viên', choices, initialEmployee);
  const onChange = (event, form) => {
    if (contextual || event.target.name !== 'scheduleDate') return;
    const picker = form.elements.namedItem('employeeId'), previousId = picker.value, choices = adjustmentChoices(ctx, event.target.value);
    picker.innerHTML = choices.map(choice => `<option value="${e(choice.value)}">${e(choice.label)}</option>`).join('');
    picker.value = choices.some(choice => choice.value === previousId) ? previousId : '';
  };
  ctx.ui.dialog({ title: 'Điều chỉnh công', wide: true, submitLabel: 'Lưu điều chỉnh', onChange, body: `<p class="muted">Điều chỉnh bổ sung giờ cho một ngày. Lượt quét gốc và các điều chỉnh trước được giữ nguyên.</p>${!contextual ? '<p class="muted">Danh sách nhân viên theo tháng của ngày ca. Người đã nghỉ vẫn có thể điều chỉnh công đến hết tháng nghỉ việc.</p>' : ''}${explanation ? `<p class="notice">${e(explanation)}</p>` : ''}<div class="form-grid">${employeeField}${field('scheduleDate', 'Ngày ca', date, 'date', `required ${contextual ? 'readonly' : ''}`)}${field('checkInAt', 'Giờ vào bổ sung', localDateTime(current?.checkIn), 'datetime-local', 'step="0.001"')}${field('checkOutAt', 'Giờ ra bổ sung', localDateTime(current?.checkOut), 'datetime-local', 'step="0.001"')}${field('workedHoursOverride', 'Giờ công thay thế (nếu cần)', previous?.workedHoursOverride ?? '', 'number', 'min="0" max="24" step="0.01"')}${textarea('reason', 'Lý do điều chỉnh', '', 'required maxlength="500"')}</div>`, onSubmit: async values => {
    if (contextual && (values.employeeId !== employeeId || values.scheduleDate !== date)) throw new Error('Nhân viên và ngày đã chọn phải giữ nguyên. Mở điều chỉnh khác để đổi nhân viên hoặc ngày.');
    if (!validDate(values.scheduleDate)) throw new Error('Ngày ca không hợp lệ.');
    const selectedEmployee = list(ctx, 'employees').find(v => v.id === values.employeeId); if (!selectedEmployee) throw new Error('Vui lòng chọn nhân viên.');
    const employee = await ctx.store.get('employees', values.employeeId); if (!employee) throw new Error('Nhân viên không còn tồn tại. Hãy tải lại danh sách.');
    if (!contextual && !visibleInMonth(employee, values.scheduleDate.slice(0, 7))) throw new Error('Nhân viên đã nghỉ trước tháng cần điều chỉnh. Chọn ngày trong kỳ làm việc hoặc một nhân viên khác.');
    const parseTime = value => {
      if (!value) return null;
      if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/.test(value)) throw new Error('Giờ vào hoặc ra không hợp lệ.');
      const time = Date.parse(`${value}+07:00`);
      if (!Number.isFinite(time)) throw new Error('Giờ vào hoặc ra không hợp lệ.');
      return time;
    };
    const id = ctx.store.id('attendanceAdjustments'), reason = reasonOf(values.reason);
    const data = { employeeId: employee.id, employeeName: employee.fullName, scheduleDate: values.scheduleDate, checkInAt: timestamp(ctx, parseTime(values.checkInAt)), checkOutAt: timestamp(ctx, parseTime(values.checkOutAt)), workedHoursOverride: values.workedHoursOverride.trim() === '' ? null : number(values.workedHoursOverride, 24), reason, actorId: ctx.user.uid, actorName: actor(ctx), createdAt: stamped(ctx) };
    validateAdjustment(data);
    const latest = latestAdjustment(list(ctx, 'attendanceAdjustments'), employee.id, values.scheduleDate);
    await ctx.store.batch([{ type: 'set', collection: 'attendanceAdjustments', id, data }, { type: 'set', collection: 'audit_logs', id, data: ctx.audit('ATTENDANCE_ADJUST', 'attendanceAdjustment', id, `employeeId=${employee.id}; scheduleDate=${values.scheduleDate}; before=${JSON.stringify(latest)}; after checkIn=${values.checkInAt}, checkOut=${values.checkOutAt}, hours=${values.workedHoursOverride}`, reason) }]);
    const activeRange = rangeOf(ctx, 'attendance');
    if (validDate(activeRange.from) && validDate(activeRange.to) && activeRange.from <= values.scheduleDate && activeRange.to >= values.scheduleDate) await ctx.loadRange(activeRange.from, activeRange.to);
    else { ctx.filters.from = values.scheduleDate; ctx.filters.to = values.scheduleDate; await ctx.loadRange(values.scheduleDate, values.scheduleDate); }
    ctx.ui.toast('Đã lưu điều chỉnh công.');
  } });
}
async function classifyDialog(ctx, id) {
  const source = await ctx.store.get('attendance', id); if (!source) throw new Error('Lượt chấm không còn tồn tại.');
  if (source.verified === false || !(accepted(source) || source.type === 'SCAN' && source.resolutionStatus === 'PENDING')) throw new Error('Chỉ sửa lượt đã chấp nhận hoặc lượt quét đang chờ.');
  const previous = (await all(ctx, 'attendanceClassificationOverrides', [['attendanceId', '==', id]])).sort((a, b) => (ms(b.createdAt) || 0) - (ms(a.createdAt) || 0))[0];
  const effective = effectiveAttendance(ctx.data).find(r => r.id === id) || source;
  ctx.ui.dialog({ title: 'Sửa phân loại lượt chấm', body: `<p>${e(source.employeeName)} · ${e(dateTimeLabel(source.timestamp))}</p><p class="muted">Gốc: ${e(source.type)} / ${e(source.status)}. Kết quả hiện tại: ${e(effective.type)} / ${e(effective.status)}.</p><div class="form-grid">${field('scheduleDate', 'Ngày ca', source.scheduleDate || dateOf(source.timestamp), 'date', 'required')}${select('correctedType', 'Phân loại mới', [{ value: 'CHECK_IN', label: 'Vào ca' }, { value: 'CHECK_OUT', label: 'Ra ca' }, { value: 'DISCARDED', label: 'Loại lượt quét' }], effective.type)}${select('correctedStatus', 'Trạng thái mới', [{ value: 'NORMAL', label: 'Bình thường' }, { value: 'ON_TIME', label: 'Đúng giờ' }, { value: 'LATE', label: 'Đi trễ' }, { value: 'EARLY_LEAVE', label: 'Về sớm' }, { value: 'DISCARDED', label: 'Đã loại' }], effective.status)}${textarea('reason', 'Lý do sửa', '', 'required maxlength="500"')}</div>`, submitLabel: 'Lưu phân loại', onSubmit: async values => {
    const reason = reasonOf(values.reason), overrideId = ctx.store.id('attendanceClassificationOverrides');
    const data = { attendanceId: id, employeeId: source.employeeId, employeeName: source.employeeName, scheduleDate: values.scheduleDate, sourceTimestamp: source.timestamp, sourceType: source.type ?? 'CHECK_IN', sourceStatus: source.status ?? 'NORMAL', sourceResolutionStatus: source.resolutionStatus ?? 'ACCEPTED', previousType: previous?.correctedType || source.type || 'CHECK_IN', previousStatus: previous?.correctedStatus || source.status || 'NORMAL', correctedType: values.correctedType, correctedStatus: values.correctedType === 'DISCARDED' ? 'DISCARDED' : values.correctedStatus, reason, actorId: ctx.user.uid, actorName: actor(ctx), createdAt: stamped(ctx) };
    validateClassification(data);
    await ctx.store.batch([{ type: 'set', collection: 'attendanceClassificationOverrides', id: overrideId, data }, { type: 'set', collection: 'audit_logs', id: overrideId, data: ctx.audit('ATTENDANCE_CLASSIFICATION_CORRECT', 'attendanceClassificationOverride', overrideId, `attendanceId=${id}; raw type=${source.type}, status=${source.status}; effectiveBefore=${data.previousType}/${data.previousStatus}; effectiveAfter=${data.correctedType}/${data.correctedStatus}`, reason) }]);
    const range = rangeOf(ctx, 'attendance'); await ctx.loadRange(range.from, range.to); ctx.ui.toast('Đã lưu phân loại và nhật ký.');
  } });
}
async function offscheduleDialog(ctx, id) {
  const source = await ctx.store.get('attendance', id); if (!source) throw new Error('Không tìm thấy lượt quét.');
  const shifts = list(ctx, 'shifts').filter(s => s.active !== false && ['MORNING', 'EVENING'].includes(s.category));
  if (!shifts.length) throw new Error('Cần tạo ca sáng hoặc chiều đang hoạt động.');
  ctx.ui.dialog({ title: 'Xử lý chấm công ngoài lịch', body: `<p>${e(source.employeeName)} · ${e(dateTimeLabel(source.timestamp))}</p><div class="form-grid">${field('scheduleDate', 'Ngày ca', source.scheduleDate || dateOf(source.timestamp), 'date', 'required')}${select('shiftId', 'Ca xác minh', shifts.map(s => ({ value: s.id, label: `${s.name} · ${s.startTime}–${s.endTime}` })), source.shiftId)}${select('decision', 'Quyết định', [{ value: 'APPROVE', label: 'Duyệt vào ca' }, { value: 'REJECT', label: 'Từ chối ghi nhận' }], 'APPROVE')}${textarea('reason', 'Lý do xử lý', '', 'required maxlength="500"')}</div>`, submitLabel: 'Lưu quyết định', onSubmit: async values => {
    if (!validDate(values.scheduleDate)) throw new Error('Ngày ca không hợp lệ.');
    const shift = shifts.find(s => s.id === values.shiftId); if (!shift) throw new Error('Ca không hợp lệ.');
    const reviewId = ctx.store.id('offScheduleReviews'), reason = reasonOf(values.reason);
    const data = { id: reviewId, employeeId: source.employeeId, employeeName: source.employeeName, scheduleDate: values.scheduleDate, shiftId: shift.id, shiftName: shift.name, decision: values.decision, reason, status: 'PENDING', reviewerId: ctx.user.uid, reviewerName: actor(ctx), createdAt: stamped(ctx) };
    await ctx.store.batch([{ type: 'set', collection: 'offScheduleReviews', id: reviewId, data }, { type: 'set', collection: 'audit_logs', id: reviewId, data: ctx.audit('OFF_SCHEDULE_REVIEW', 'offScheduleReview', reviewId, `${values.decision} ${source.employeeId} ${values.scheduleDate} ${shift.id}`, reason) }]);
    await ctx.loadRange(values.scheduleDate, values.scheduleDate); ctx.ui.toast('Đã lưu quyết định ngoài lịch.');
  } });
}
async function createPayroll(ctx, employeeId) {
  await requireRange(ctx, 'payroll');
  const month = monthOf(ctx), staff = historical(ctx), totals = monthlyTotals(ctx.data, month, staff);
  if (!employeeId) {
    ctx.ui.dialog({ title: 'Lập / quản lý phiếu lương', wide: true, submitLabel: null, body: `<p>Tháng ${e(month)}. Chọn nhân viên để lập phiếu mới hoặc xem và tính lại phiếu đã lưu.</p>${table(['Nhân viên', 'Đơn giá giờ hiện tại', 'Công hiện tại', 'Phiếu lương', 'Thao tác'], staff.filter(employee => matchesEmployee(employee, ctx)).map(employee => {
      const slip = list(ctx, 'payroll').find(p => p.employeeId === employee.id && p.month === month);
      return [person(employee.fullName, employee.code), money(employee.baseSalary), `${hh(totals.get(employee.id)?.hoursWorked)} h`, slip ? `Đã lưu · v${Number(slip.revision || 0)}` : 'Chưa lập phiếu', `<div class="row-actions">${slip ? button('Xem / tính lại', 'payroll-recalculate', `data-id="${e(slip.id || `${employee.id}_${month}`)}" class="primary"`) : button('Tạo phiếu', 'payroll-create', `data-id="${e(employee.id)}" class="primary"`)}${salaryButton(employee)}</div>`];
    }), 'Chưa có nhân viên phù hợp với bộ lọc trong tháng này.')}` });
    return;
  }
  const selected = staff.find(employee => employee.id === employeeId);
  if (!selected) throw new Error('Không tìm thấy nhân viên phù hợp trong tháng này.');
  const saved = list(ctx, 'payroll').find(p => p.employeeId === employeeId && p.month === month);
  if (saved) { await recalculatePayroll(ctx, saved.id || `${employeeId}_${month}`); return; }
  const makeBody = employee => { const t = totals.get(employee.id); return `<p>${e(employee.fullName)} · tháng ${e(month)}</p><p class="muted">Đơn giá: ${money(employee.baseSalary)} / giờ. Công hiện tại: ${hh(t?.regularHours)} giờ thường + ${hh(t?.overtimeHours)} giờ tăng ca. Thưởng tự động: ${money(t?.totalBonus)}.</p>${dayHoursBreakdown(employee, t?.rows || [], month)}<div class="actions">${salaryButton(employee)}</div><div class="form-grid">${field('hoursWorked', 'Giờ công chốt', t?.hoursWorked || 0, 'number', 'required min="0" max="744" step="0.01"')}${field('bonus', 'Thưởng', t?.totalBonus || 0, 'number', 'required min="0" max="1000000000000" step="1"')}${field('deduction', 'Khấu trừ', 0, 'number', 'required min="0" max="1000000000000" step="1"')}</div>`; };
  const submit = employee => async values => {
    const hours = number(values.hoursWorked, 744), bonus = number(values.bonus), deduction = number(values.deduction), id = `${employee.id}_${month}`;
    if (!Number.isSafeInteger(bonus) || !Number.isSafeInteger(deduction)) throw new Error('Thưởng và khấu trừ phải là số đồng nguyên.');
    await ctx.store.transaction(async tx => {
      const currentEmployee = await tx.get('employees', employee.id), existing = await tx.get('payroll', id);
      if (existing) throw new Error('Phiếu lương vừa được tạo. Hãy tải lại bảng lương.');
      if (!currentEmployee || Number(currentEmployee.baseSalary || 0) !== Number(employee.baseSalary || 0)) throw new Error('Đơn giá giờ đã thay đổi. Mở lại phiếu để kiểm tra.');
      const payroll = { employeeId: employee.id, employeeCode: employee.code || '', employeeName: employee.fullName, month, hourlyRate: Number(employee.baseSalary || 0), hoursWorked: hours, baseSalary: basePay(Number(employee.baseSalary || 0), hours), bonus, deduction, revision: 0 };
      tx.set('payroll', id, payroll); tx.set('audit_logs', ctx.store.id('audit_logs'), ctx.audit('EMPLOYEE_UPDATE', 'payroll', id, `Tạo phiếu lương ${month}; giờ=${hours}; thực nhận=${net(payroll)}`));
    }); ctx.ui.toast('Đã tạo phiếu lương.');
  };
  ctx.ui.dialog({ title: 'Tạo phiếu lương', body: makeBody(selected), submitLabel: 'Chốt và lưu phiếu', onSubmit: submit(selected) });
}
function payrollSalaryPicker(ctx) {
  if (ctx.loaded?.employees !== true || ctx.errors?.employees) throw new Error(ctx.errors?.employees || 'Chưa tải đầy đủ danh sách nhân viên.');
  const staff = list(ctx, 'employees').filter(employee => employee.active !== false && matchesEmployee(employee, ctx));
  ctx.ui.dialog({ title: 'Đặt đơn giá giờ', wide: true, submitLabel: null, body: `<p>Đơn giá giờ dùng để lập phiếu mới và xem trước tính lại. Phiếu lương đã lưu giữ nguyên cho đến khi xác nhận tính lại.</p>${table(['Nhân viên', 'Đơn giá giờ hiện tại', 'Thao tác'], staff.map(employee => [person(employee.fullName, employee.code), `${money(employee.baseSalary)} / giờ`, salaryButton(employee)]), 'Chưa có nhân viên đang làm phù hợp với bộ lọc.')}` });
}
const same = (a, b) => { const keys = Object.keys(a).sort(); return JSON.stringify(keys) === JSON.stringify(Object.keys(b).sort()) && keys.every(k => a[k] && typeof a[k] === 'object' ? b[k] && typeof b[k] === 'object' && same(a[k], b[k]) : a[k] === b[k]); };
async function recalculatePayroll(ctx, id) {
  const previousDoc = await ctx.store.get('payroll', id); if (!previousDoc) throw new Error('Phiếu lương không còn tồn tại.');
  const previous = plain(previousDoc), employee = await ctx.store.get('employees', previous.employeeId); if (!employee) throw new Error('Không tìm thấy nhân viên.');
  const dates = monthDates(previous.month); await ctx.loadRange(dates[0], dates.at(-1));
  if (!ready(ctx, { from: dates[0], to: dates.at(-1) })) throw new Error('Dữ liệu tháng chưa đầy đủ; phiếu đã lưu vẫn được giữ nguyên.');
  const totals = monthlyTotals(ctx.data, previous.month, historical(ctx, previous.month)).get(previous.employeeId), hours = totals?.hoursWorked || 0, preview = payrollPreview(previous, employee, hours, Number(previous.bonus || 0), Number(previous.deduction || 0));
  ctx.ui.dialog({ title: `Xem trước tính lại · ${previous.employeeName}`, wide: true, submitLabel: 'Xác nhận tính lại và lưu lịch sử', body: `<p class="muted">Tháng ${e(previous.month)}. Sau khi lưu, cả phiếu trước và phiếu mới được lưu vào lịch sử phiên bản ${preview.revision}.</p>${table(['Giá trị', 'Phiếu đã lưu', 'Theo dữ liệu hiện tại'], [['Giờ công', hh(previous.hoursWorked), hh(hours)], ['Đơn giá giờ', money(previous.hourlyRate), money(employee.baseSalary)], ['Lương cơ bản', money(previous.baseSalary), money(preview.baseSalary)], ['Thực nhận dự kiến', money(net(previous)), money(net(preview))]])}${dayHoursBreakdown(employee, totals?.rows || [], previous.month)}<div class="form-grid">${field('hoursWorked', 'Giờ công chốt mới', hours, 'number', 'required min="0" max="744" step="0.01"')}${field('bonus', 'Thưởng mới', previous.bonus || 0, 'number', 'required min="0" max="1000000000000" step="1"')}${field('deduction', 'Khấu trừ mới', previous.deduction || 0, 'number', 'required min="0" max="1000000000000" step="1"')}${textarea('reason', 'Lý do tính lại', '', 'required maxlength="500"')}</div>`, onSubmit: async values => {
    const reason = reasonOf(values.reason), next = payrollPreview(previous, employee, number(values.hoursWorked, 744), number(values.bonus), number(values.deduction)), revisions = `payroll/${id}/revisions`;
    await ctx.store.transaction(async tx => {
      const currentDoc = await tx.get('payroll', id), currentEmployee = await tx.get('employees', previous.employeeId), archive = await tx.get(revisions, String(next.revision));
      if (!currentDoc || !same(plain(currentDoc), previous) || !currentEmployee || Number(currentEmployee.baseSalary || 0) !== Number(employee.baseSalary || 0)) throw new Error('Phiếu lương hoặc đơn giá đã thay đổi. Hãy mở lại để xem trước.');
      if (archive) throw new Error('Phiên bản lịch sử đã tồn tại. Hãy tải lại phiếu lương.');
      tx.set('payroll', id, next);
      tx.set(revisions, String(next.revision), { payrollId: id, revision: next.revision, previous, next, actorId: ctx.user.uid, reason, createdAt: stamped(ctx) });
      tx.set('audit_logs', ctx.store.id('audit_logs'), ctx.audit('EMPLOYEE_UPDATE', 'payroll', id, `Tính lại ${previous.month}, phiên bản ${next.revision}; giờ ${previous.hoursWorked} → ${next.hoursWorked}; thực nhận ${net(previous)} → ${net(next)}; lý do: ${reason}`));
    }); ctx.ui.toast(`Đã lưu phiếu lương phiên bản ${next.revision}.`);
  } });
}
async function payrollHistory(ctx, id) {
  const history = await all(ctx, `payroll/${id}/revisions`); history.sort((a, b) => Number(b.revision) - Number(a.revision));
  ctx.ui.dialog({ title: 'Lịch sử tính lại phiếu lương', wide: true, submitLabel: null, body: table(['Phiên bản', 'Thời gian', 'Giờ trước → sau', 'Thực nhận trước → sau', 'Người thực hiện', 'Lý do'], history.map(h => [e(h.revision), e(dateTimeLabel(h.createdAt)), `${hh(h.previous?.hoursWorked)} → ${hh(h.next?.hoursWorked)}`, `${money(net(h.previous || {}))} → ${money(net(h.next || {}))}`, e(h.actorId), e(h.reason)]), 'Phiếu chưa được tính lại. Bản lưu ban đầu ở phiên bản 0.') });
}
function exportPayroll(ctx) {
  if (ctx.loaded?.payroll !== true || ctx.errors?.payroll) throw new Error(ctx.errors?.payroll || 'Bảng lương chưa tải đầy đủ; vui lòng thử lại khi dữ liệu sẵn sàng.');
  const rows = list(ctx, 'payroll').filter(p => p.month === monthOf(ctx) && matchesEmployee(list(ctx, 'employees').find(e => e.id === p.employeeId) || { id: p.employeeId, fullName: p.employeeName, code: p.employeeCode }, ctx));
  csv(`bang-luong-${monthOf(ctx)}.csv`, ['Mã', 'Nhân viên', 'Tháng', 'Giờ', 'Đơn giá', 'Lương cơ bản', 'Thưởng', 'Khấu trừ', 'Thực nhận', 'Phiên bản'], rows.map(p => [p.employeeCode, p.employeeName, p.month, p.hoursWorked, p.hourlyRate, p.baseSalary, p.bonus, p.deduction, net(p), p.revision || 0]));
}
async function attendanceDetail(ctx, id) {
  const row = await ctx.store.get('attendance', id); if (!row) throw new Error('Không tìm thấy lượt chấm.');
  const [classifications, reviews, adjustments] = await Promise.all([all(ctx, 'attendanceClassificationOverrides', [['attendanceId', '==', id]]), all(ctx, 'offScheduleReviews', [['employeeId', '==', row.employeeId]]), all(ctx, 'attendanceAdjustments', [['employeeId', '==', row.employeeId]])]);
  const effective = effectiveAttendance(ctx.data).find(r => r.id === id) || row, date = effective.scheduleDate || dateOf(effective.timestamp);
  ctx.ui.dialog({ title: 'Chi tiết và lịch sử chấm công', wide: true, submitLabel: null, body: `<h3>${e(row.employeeName)}</h3><div class="actions">${adjustmentButton(effective, 'class="primary"')}</div>${table(['Thông tin', 'Giá trị'], [['Thời gian gốc', e(dateTimeLabel(row.timestamp))], ['Ngày ca', e(date)], ['Thiết bị / phương thức', `${e(row.deviceId || row.stationId)} / ${e(method(row))}`], ['Phân loại gốc', `${b(row.type)} ${b(row.status)} ${b(row.resolutionStatus)}`], ['Kết quả hiện tại', `${b(effective.type)} ${b(effective.status)} ${b(effective.resolutionStatus)}`], ['Mã lượt / nguồn', `${e(row.id)} / ${e(row.sourceEventId || '—')}`]])}<h3>Lịch sử sửa phân loại</h3>${table(['Thời gian', 'Trước → sau', 'Người thực hiện', 'Lý do'], classifications.sort((a, b) => ms(b.createdAt) - ms(a.createdAt)).map(v => [e(dateTimeLabel(v.createdAt)), `${e(v.previousType)} / ${e(v.previousStatus)} → ${e(v.correctedType)} / ${e(v.correctedStatus)}`, e(v.actorName), e(v.reason)]))}<h3>Điều chỉnh công của ngày</h3>${table(['Thời gian', 'Vào', 'Ra', 'Giờ thay thế', 'Lý do'], adjustments.filter(v => v.scheduleDate === date).sort((a, b) => ms(b.createdAt) - ms(a.createdAt)).map(v => [e(dateTimeLabel(v.createdAt)), e(dateTimeLabel(v.checkInAt)), e(dateTimeLabel(v.checkOutAt)), v.workedHoursOverride == null ? '—' : hh(v.workedHoursOverride), e(v.reason)]))}<h3>Quyết định ngoài lịch</h3>${table(['Ca', 'Quyết định', 'Trạng thái', 'Người xử lý', 'Lý do'], reviews.filter(v => v.scheduleDate === date).map(v => [e(v.shiftName), e(v.decision === 'APPROVE' ? 'Duyệt vào ca' : 'Từ chối'), b(v.status), e(v.reviewerName), e(v.reason)]))}` });
}
export async function onAction(action, element, ctx) {
  const id = element.dataset.id;
  if (action === 'timesheet-detail') { await timesheetDetail(ctx, id); return true; }
  if (action === 'attendance-load-range') { await ctx.loadRange(element.dataset.from, element.dataset.to); return true; }
  if (action === 'attendance-adjust') { await adjustmentDialog(ctx, { employeeId: element.dataset.employee || ctx.filters.employeeId || '', date: element.dataset.date || ctx.filters.from || today(), attendanceId: id || '', contextual: !!element.dataset.employee }); return true; }
  if (action === 'attendance-classify') { await classifyDialog(ctx, id); return true; }
  if (action === 'attendance-offschedule') { await offscheduleDialog(ctx, id); return true; }
  if (action === 'attendance-detail') { await attendanceDetail(ctx, id); return true; }
  if (action === 'attendance-export') { const range = await requireRange(ctx, 'attendance'); csv(`luot-cham-${range.from}-${range.to}.csv`, ['Nhân viên', 'ID nhân viên', 'Thời gian', 'Ngày ca', 'Ca', 'Loại', 'Trạng thái', 'Kết quả xử lý', 'Phương thức', 'Thiết bị'], scans(ctx, range).map(r => [r.employeeName, r.employeeId, dateTimeLabel(r.timestamp), r.scheduleDate || dateOf(r.timestamp), r.shiftId, r.type, r.status, r.resolutionStatus, method(r), r.deviceId || r.stationId])); return true; }
  if (action === 'payroll-create') { await createPayroll(ctx, id); return true; }
  if (action === 'payroll-salary-picker') { payrollSalaryPicker(ctx); return true; }
  if (action === 'payroll-recalculate') { await recalculatePayroll(ctx, id); return true; }
  if (action === 'payroll-history') { await payrollHistory(ctx, id); return true; }
  if (action === 'payroll-export') { exportPayroll(ctx); return true; }
  if (action === 'reports-export') {
    const type = ctx.filters.reportType || 'ATTENDANCE';
    if (type === 'PAYROLL') exportPayroll(ctx);
    else if (type === 'WORK_ITEMS') {
      if (ctx.loaded?.workItems !== true || ctx.errors?.workItems) throw new Error('Chưa tải đủ công việc để xuất báo cáo.');
      const range = rangeOf(ctx, 'reports'); if (!validDate(range.from) || !validDate(range.to) || range.from > range.to) throw new Error('Khoảng ngày không hợp lệ.');
      csv(`bao-cao-cong-viec-${range.from}-${range.to}.csv`, ['Công việc', 'ID', 'Nhân viên', 'Bắt đầu', 'Hạn hoàn thành', 'Trạng thái', 'Hoàn thành lúc', 'Kết quả', 'Phản hồi', 'Số lần làm lại'], reportWork(ctx).map(t => [t.title, t.id, t.assigneeName, dateTimeLabel(t.startAt), dateTimeLabel(t.deadline), t.status, dateTimeLabel(t.completedAt), t.resultReport, t.managerFeedback, t.reworkCount || 0]));
    }
    else if (type === 'DEVICES') {
      if (ctx.loaded?.devices !== true || ctx.loaded?.deviceCommands !== true || ctx.errors?.devices || ctx.errors?.deviceCommands) throw new Error('Chưa tải đủ dữ liệu thiết bị và lệnh để xuất báo cáo.');
      csv('hoat-dong-thiet-bi.csv', ['Thiết bị', 'Trạng thái', 'Heartbeat', 'Firmware', 'Mẫu vân tay', 'Dung lượng', 'Lệnh lỗi'], list(ctx, 'devices').map(d => [d.id, d.status, dateTimeLabel(d.lastHeartbeat), d.firmwareVersion, d.fingerprintCount, d.capacity, list(ctx, 'deviceCommands').filter(c => c.deviceId === d.id && c.status === 'FAILED').length]));
    }
    else {
      const range = await requireRange(ctx, 'reports'), timesheet = type === 'MONTHLY' ? monthlyTimesheetView(ctx) : null, rows = selectedReportRows(ctx, timesheet);
      if (type === 'WORK_SUMMARY') csv(`tong-hop-cong-${range.from}-${range.to}.csv`, ['Mã', 'Nhân viên', 'Ngày có công', 'Giờ thường', 'Tăng ca', 'Ngày đi trễ', 'Ngày về sớm', 'Ngày có nghỉ phép', 'Ngày thiếu chấm'], summaryWork(ctx, rows).map(v => [v.employee.code, v.employee.fullName, v.workdays, v.regularHours, v.overtimeHours, v.lateCount, v.earlyCount, v.leaveCount, v.missingCount]));
      else if (type === 'MONTHLY') {
        const dates = datesBetween(range.from, range.to), map = new Map(rows.map(r => [`${r.employeeId}_${r.date}`, r]));
        csv(`bang-cong-${monthOf(ctx)}.csv`, ['Mã', 'Nhân viên', ...dates, 'Giờ thường', 'Tăng ca'], timesheet.staff.map(employee => { const own = rows.filter(r => r.employeeId === employee.id); return [employee.code, employee.fullName, ...dates.map(date => { const r = map.get(`${employee.id}_${date}`); return r ? r.status === 'LEAVE' ? 'P' : r.workedHours + r.overtimeHours : ''; }), own.reduce((a, r) => a + r.workedHours, 0), own.reduce((a, r) => a + r.overtimeHours, 0)]; }));
      } else csv(`bao-cao-${type.toLowerCase()}-${range.from}-${range.to}.csv`, ['Ngày', 'Mã', 'Nhân viên', 'Phòng ban', 'Vào', 'Ra', 'Trạng thái', 'Giờ thường', 'Tăng ca', 'Trễ (phút)', 'Sớm (phút)', 'Ca nghỉ'], rows.map(r => [r.date, r.code, r.employeeName, r.department, timeLabel(r.checkIn), timeLabel(r.checkOut), r.status, r.workedHours, r.overtimeHours, r.lateMinutes, r.earlyLeaveMinutes, r.approvedLeaveShiftCount]));
    } return true;
  }
  if (action === 'performance-review') {
    const employee = list(ctx, 'employees').find(e => e.id === id); if (!employee) throw new Error('Không tìm thấy nhân viên.');
    const month = monthOf(ctx), existing = list(ctx, 'performanceReviews').find(r => r.employeeId === id && r.period === month);
    ctx.ui.dialog({ title: `Đánh giá · ${employee.fullName}`, body: `<p>Tháng ${e(month)}</p><div class="form-grid">${field('score', 'Điểm đánh giá / 100', existing?.score || 0, 'number', 'required min="0" max="100" step="0.1"')}${textarea('note', 'Nhận xét', existing?.note || '', 'maxlength="2000"')}</div>`, submitLabel: 'Lưu đánh giá', onSubmit: async values => { const reviewId = existing?.id || `${id}_${month}`, data = { employeeId: id, period: month, score: number(values.score, 100), note: values.note.trim() }; await ctx.store.batch([{ type: 'set', collection: 'performanceReviews', id: reviewId, data }, { type: 'set', collection: 'audit_logs', id: ctx.store.id('audit_logs'), data: ctx.audit('EMPLOYEE_UPDATE', 'performanceReview', reviewId, `Đánh giá ${month}: ${data.score}/100; ${data.note}`) }]); ctx.ui.toast('Đã lưu đánh giá hiệu suất.'); } }); return true;
  }
  if (action === 'performance-export') {
    if (ctx.loaded?.workItems !== true || ctx.errors?.workItems) throw new Error('Chưa tải đủ công việc để xuất hiệu suất.');
    const month = monthOf(ctx), tasks = list(ctx, 'workItems').filter(t => dateOf(t.deadline).startsWith(`${month}-`));
    const ids = [...new Set([...historical(ctx).map(e => e.id), ...tasks.map(t => t.assigneeId)])];
    csv(`hieu-suat-${month}.csv`, ['Mã', 'Nhân viên', 'Được giao', 'Hoàn thành', 'Đang làm', 'Chờ duyệt', 'Đúng hạn', 'Hoàn thành muộn', 'Đang quá hạn', 'Việc làm lại', 'Số lần làm lại', 'Tỷ lệ đúng hạn', 'Điểm đánh giá', 'Nhận xét'], ids.map(id => list(ctx, 'employees').find(e => e.id === id) || { id, fullName: tasks.find(t => t.assigneeId === id)?.assigneeName || id }).filter(e => matchesEmployee(e, ctx)).map(employee => { const m = workMetrics(tasks.filter(t => t.assigneeId === employee.id)), r = list(ctx, 'performanceReviews').find(r => r.employeeId === employee.id && r.period === month); return [employee.code, employee.fullName, m.assigned, m.completed, m.inProgress, m.pendingReview, m.completedOnTime, m.completedLate, m.overdue, m.reworked, m.reworkCount, m.onTimeCompletionRate == null ? '' : m.onTimeCompletionRate * 100, r?.score, r?.note]; })); return true;
  }
  return false;
}
