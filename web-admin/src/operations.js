import { e, icon, badge, field, select, textarea, table, button, person, dateLabel, dateTimeLabel, today, dateAdd, normalize, stat } from './ui.js';
import { effectiveAttendance, assignScheduleDates, accepted, belongs, validDate } from './domain.js';
import { defaultScheduleShifts, defaultScheduleShift, displayedScheduleShifts, assignableScheduleShifts } from './shift-selection.js';
import { renderScheduleCalendar, scheduleCalendarRange, selectedScheduleDate } from './schedule-calendar.js';

const WORK_STATUS = { ASSIGNED: 'Đã giao', IN_PROGRESS: 'Đang thực hiện', PENDING_REVIEW: 'Chờ duyệt', COMPLETED: 'Hoàn thành' };
const PRIORITY = { LOW: 'Thấp', NORMAL: 'Bình thường', HIGH: 'Cao', URGENT: 'Khẩn cấp' };
const REQUEST_STATUS = { PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối', NEEDS_REVISION: 'Cần sửa', CANCELLED: 'Đã hủy' };
const REQUEST_TYPES = { LEAVE: 'Nghỉ phép', LATE: 'Đi trễ', EARLY_LEAVE: 'Về sớm', REMOTE: 'Làm từ xa', ATTENDANCE_ADJUSTMENT: 'Điều chỉnh công', SHIFT_CHANGE: 'Đổi ca', FINGERPRINT_SUPPORT: 'Hỗ trợ vân tay' };
const CATEGORY = { MORNING: 'Ca sáng', EVENING: 'Ca chiều', SUPPLEMENTARY: 'Ca bổ sung' };
const DAYS = ['Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
const SUPPLEMENTARY = 'SUPPLEMENTARY_1800_2200';
const rows = (ctx, collection) => ctx.data[collection] || [];
const rawData = value => { if (!value) return null; const { id, ...data } = value; return data; };
const docId = value => { const id = String(value || '').trim(); if (!id || id.includes('/')) throw new Error('Mã dữ liệu không hợp lệ.'); return id; };
const required = (value, label, max = Infinity) => { const text = String(value || '').trim(); if (!text || text.length > max) throw new Error(`${label} bắt buộc${Number.isFinite(max) ? `, tối đa ${max} ký tự` : ''}.`); return text; };
const actorName = ctx => required(ctx.user.displayName || ctx.user.email || ctx.user.uid, 'Tên Admin');
const millis = value => value?.toMillis?.() ?? value?.toDate?.()?.getTime() ?? (value?.seconds != null ? value.seconds * 1000 : new Date(value).getTime());
const isoDate = value => { if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '') || new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) !== value) throw new Error('Ngày phải hợp lệ theo yyyy-MM-dd.'); return value; };
const weekday = value => new Date(`${isoDate(value)}T12:00:00Z`).getUTCDay();
const workDate = value => { isoDate(value); if (weekday(value) === 0) throw new Error('Chỉ phân ca từ thứ Hai đến thứ Bảy.'); return value; };
const monday = value => { const day = weekday(value); return dateAdd(value, -(day === 0 ? 6 : day - 1)); };
const weekOf = ctx => monday(ctx.filters.week || today());
const int = (value, label, min = 0, max = 1440) => { const n = Number(value); if (!Number.isInteger(n) || n < min || n > max) throw new Error(`${label} phải từ ${min} đến ${max}.`); return n; };
const minutes = value => { if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value || '')) throw new Error('Giờ phải hợp lệ theo HH:mm.'); const [h, m] = value.split(':').map(Number); return h * 60 + m; };
const localDateTime = value => { if (!/^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):[0-5]\d$/.test(value || '')) throw new Error('Ngày giờ không hợp lệ.'); isoDate(value.slice(0, 10)); return new Date(`${value}:00+07:00`); };
const datetimeInput = value => { if (!Number.isFinite(millis(value))) return ''; return new Date(millis(value) + 7 * 3600000).toISOString().slice(0, 16); };
const itemButtons = (id, ...actions) => `<div class="actions">${actions.map(([label, action, attrs = '']) => button(label, action, `data-id="${e(id)}" ${attrs}`)).join('')}</div>`;
const textBlock = value => `<p class="pre-wrap">${e(value || '—')}</p>`;
const section = (label, html) => `<section class="detail-section"><h3>${e(label)}</h3>${html}</section>`;
const attachmentButtons = (workId, attachments, version = null) => (attachments || []).map(a => button(a.fileName, 'ops-attachment-download', `data-id="${e(workId)}" data-attachment="${e(a.id)}"${version == null ? '' : ` data-version="${e(version)}"`}`)).join(' ');
const options = values => Object.entries(values).map(([value, label]) => ({ value, label }));
const filterSelect = (name, label, items, value = '') => select(name, label, items, value).replace('<select ', `<select data-filter="${name}" `);
const searchBox = (ctx, placeholder = 'Tên, mã nhân viên, phòng ban') => `<label class="search-field">${icon('search')}<input type="search" data-filter="search" aria-label="Tìm kiếm" value="${e(ctx.filters.search || '')}" placeholder="${e(placeholder)}"></label>`;
const departmentFilter = ctx => filterSelect('department', 'Phòng ban', [{ value: '', label: 'Tất cả phòng ban' }, ...rows(ctx, 'departments').map(d => ({ value: d.id, label: d.name }))], ctx.filters.department || '');
const filterEmployee = (ctx, id, name, extra = '') => {
  const employee = rows(ctx, 'employees').find(x => x.id === id);
  const query = normalize(ctx.filters.search || '');
  const department = ctx.filters.department;
  return (!query || normalize(`${name || ''} ${employee?.code || ''} ${employee?.department || ''} ${extra}`).includes(query)) && (!department || employee?.departmentId === department || employee?.department === department || rows(ctx, 'departments').find(d => d.id === department)?.name === employee?.department);
};
const statusFilter = (ctx, values) => filterSelect('status', 'Trạng thái', [{ value: '', label: 'Tất cả trạng thái' }, ...options(values)], ctx.filters.status || '');
const matchesStatus = (ctx, row) => !ctx.filters.status || row.status === ctx.filters.status;
const shiftIds = schedule => [...new Set([schedule?.shiftId, ...(schedule?.shiftIds || [])].filter(Boolean))];
const shiftLabel = (ctx, id) => { const shift = rows(ctx, 'shifts').find(s => s.id === id); return shift ? `${shift.name} (${shift.startTime}–${shift.endTime})` : id; };
const requestCollection = kind => ({ weekly: 'weeklyScheduleRequests', overtime: 'overtimeRequests', leave: 'leaveRequests' }[kind] || 'leaveRequests');
const stampAudit = (ctx, action, targetType, targetId, details, reason = '', actor = null) => ({ ...ctx.audit(action, targetType, targetId, details, reason), ...(actor ? { actorId: actor.uid, actorName: actor.name } : {}), createdAt: ctx.store.stamp() });
const notification = (ctx, type, title, body, referenceId, request) => ({ type, title, body, referenceId, recipientEmployeeId: request.employeeId, audienceLabel: request.employeeName, read: false, createdAt: ctx.store.stamp() });

export function validateShift(shift, mainOnly = false) {
  required(shift.name, 'Tên ca', 120);
  if (!Object.hasOwn(CATEGORY, shift.category) || (mainOnly && (!['MORNING', 'EVENING'].includes(shift.category) || [SUPPLEMENTARY, 'SUPPLEMENTARY_1730_2030'].includes(shift.id)))) throw new Error('Ca chính chỉ gồm ca sáng và ca chiều. Ca tăng ca được quản lý riêng.');
  const length = minutes(shift.endTime) - minutes(shift.startTime);
  if (length <= 0 || length > 240) throw new Error('Ca phải kết thúc trong cùng ngày và có thời lượng tối đa 4 giờ.');
  for (const key of ['allowEarlyMinutes', 'lateGraceMinutes', 'earlyLeaveAllowedMinutes', 'missingCheckOutGraceMinutes']) int(shift[key] ?? 0, 'Số phút cho phép');
  isoDate(shift.effectiveFrom);
  if (shift.effectiveTo && isoDate(shift.effectiveTo) < shift.effectiveFrom) throw new Error('Ngày kết thúc áp dụng phải sau ngày bắt đầu.');
  if (Boolean(shift.breakStartTime) !== Boolean(shift.breakEndTime)) throw new Error('Cần nhập đủ giờ bắt đầu và kết thúc nghỉ.');
  if (shift.breakStartTime && (minutes(shift.breakEndTime) <= minutes(shift.breakStartTime) || shift.breakStartTime < shift.startTime || shift.breakEndTime > shift.endTime)) throw new Error('Giờ nghỉ phải nằm trong ca và kết thúc sau giờ bắt đầu.');
  if (mainOnly && shift.active !== true) throw new Error(`Ca ${shift.name} đã ngừng hoạt động.`);
  return shift;
}

export function mergeAssignment(existing, incoming, selected, shiftsById) {
  selected.forEach(s => validateShift(s, true));
  const categories = new Set(selected.map(s => s.category));
  if (!selected.length || selected.length > 2 || categories.size !== selected.length) throw new Error('Mỗi ngày chọn tối đa một ca sáng và một ca chiều.');
  const retained = shiftIds(existing).map(id => { const shift = shiftsById.get(id); if (!shift) throw new Error(`Ca đã phân không còn tồn tại: ${id}.`); return shift; }).filter(s => !categories.has(s.category));
  retained.forEach(s => validateShift(s, true));
  const combined = [...retained, ...selected].sort((a, b) => a.startTime.localeCompare(b.startTime));
  if (combined.length > 2 || new Set(combined.map(s => s.category)).size !== combined.length) throw new Error('Lịch có ca trùng loại.');
  return { ...(existing || {}), ...incoming, shiftId: combined[0].id, shiftIds: combined.map(s => s.id), shiftName: combined.map(s => s.name).join(' + '), overtimeHours: existing?.overtimeHours ?? incoming.overtimeHours ?? 0, workedHoursOverride: existing?.workedHoursOverride ?? null, adjustmentNote: existing?.adjustmentNote || '', note: existing?.note || '' };
}

export function render(page, ctx) {
  if (page === 'workItems') return renderWork(ctx);
  if (page === 'schedules') return renderSchedule(ctx);
  if (page === 'shifts') return renderShifts(ctx);
  if (page === 'requests') return renderRequests(ctx);
  return '';
}

export function filteredWorkItems(ctx, now = Date.now()) {
  const day = new Date(now + 7 * 3600000).toISOString().slice(0, 10);
  return rows(ctx, 'workItems').filter(item => {
    if (!filterEmployee(ctx, item.assigneeId, item.assigneeName, item.title) || !matchesStatus(ctx, item)
      || ctx.filters.workPriority && ctx.filters.workPriority !== item.priority) return false;
    if (!ctx.filters.workDue) return true;
    const deadline = millis(item.deadline);
    if (item.status === 'COMPLETED' || !Number.isFinite(deadline)) return false;
    if (ctx.filters.workDue === 'TODAY') return new Date(deadline + 7 * 3600000).toISOString().slice(0, 10) === day;
    if (ctx.filters.workDue === 'SOON') return deadline >= now && deadline <= now + 24 * 3600000;
    if (ctx.filters.workDue === 'OVERDUE') return deadline < now;
    return true;
  }).sort((a, b) => Number(a.status === 'COMPLETED') - Number(b.status === 'COMPLETED') || millis(a.deadline) - millis(b.deadline));
}

function renderWork(ctx) {
  const all = rows(ctx, 'workItems');
  const filtered = filteredWorkItems(ctx);
  return `<div class="stats-grid">${stat('Công việc', all.length, 'Tổng số việc đã giao')}${stat('Đang thực hiện', all.filter(x => x.status === 'IN_PROGRESS').length, 'Theo dõi tiến độ')}${stat('Chờ duyệt', all.filter(x => x.status === 'PENDING_REVIEW').length, 'Cần kiểm tra kết quả')}${stat('Quá hạn', all.filter(x => x.status !== 'COMPLETED' && millis(x.deadline) < Date.now()).length, 'Chưa hoàn thành')}</div><section class="panel"><div class="panel-head"><div><h2>Công việc & kết quả</h2><p class="muted">Giao việc, xem báo cáo và duyệt kết quả trên cùng một màn hình.</p></div>${button('Tạo & giao việc', 'ops-work-new', 'class="primary"')}</div><div class="toolbar">${searchBox(ctx, 'Tên công việc, người thực hiện, mã NV')}${departmentFilter(ctx)}${statusFilter(ctx, WORK_STATUS)}${filterSelect('workPriority', 'Ưu tiên', [{ value: '', label: 'Tất cả mức ưu tiên' }, ...options(PRIORITY)], ctx.filters.workPriority || '')}${filterSelect('workDue', 'Hạn hoàn thành', [{ value: '', label: 'Tất cả thời hạn' }, { value: 'TODAY', label: 'Hạn hôm nay' }, { value: 'SOON', label: 'Trong 24 giờ tới' }, { value: 'OVERDUE', label: 'Đang quá hạn' }], ctx.filters.workDue || '')}</div><p class="muted">Hiển thị ${filtered.length}/${all.length} công việc.</p>${table(['Công việc', 'Người thực hiện', 'Hạn hoàn thành', 'Ưu tiên', 'Trạng thái', 'Thao tác'], filtered.map(item => [`<strong>${e(item.title)}</strong><small class="muted">Phiên bản ${e(item.version)}${item.reworkCount ? ` · Làm lại ${e(item.reworkCount)} lần` : ''}</small>`, person(item.assigneeName, rows(ctx, 'employees').find(x => x.id === item.assigneeId)?.code || ''), `<span class="${item.status !== 'COMPLETED' && millis(item.deadline) < Date.now() ? 'text-danger' : ''}">${dateTimeLabel(item.deadline)}</span>`, badge(item.priority), badge(item.status), itemButtons(item.id, ['Chi tiết', 'ops-work-detail'], ...(item.status !== 'COMPLETED' ? [['Sửa', 'ops-work-edit']] : []))]), 'Chưa có công việc phù hợp. Tạo công việc mới hoặc điều chỉnh bộ lọc.')}</section>`;
}

function renderSchedule(ctx) { return renderScheduleCalendar(ctx); }

function renderShifts(ctx) {
  const all = displayedScheduleShifts(rows(ctx, 'shifts')).filter(s => !ctx.filters.search || normalize(`${s.name} ${CATEGORY[s.category]}`).includes(normalize(ctx.filters.search)));
  return `<section class="panel"><div class="panel-head"><div><h2>Ca làm & tăng ca</h2><p class="muted">Ca chính mặc định 08:00–12:00 và 13:00–17:00; tăng ca cố định 18:00–22:00.</p></div><div class="actions">${button('Tạo ca bổ sung', 'ops-shift-new')}${button('Phân tăng ca', 'ops-overtime-new', 'class="primary"')}</div></div><div class="toolbar">${searchBox(ctx, 'Tên ca làm')}${button('Khởi tạo ca mặc định', 'ops-shift-seed')}</div>${table(['Tên ca', 'Loại', 'Khung giờ', 'Cho phép', 'Áp dụng từ', 'Trạng thái', 'Thao tác'], all.map(s => [`<strong>${e(s.name)}</strong>`, e(CATEGORY[s.category] || s.category), `${e(s.startTime)}–${e(s.endTime)}`, `<small>Trễ ${e(s.lateGraceMinutes || 0)} phút · Ra ${e(s.missingCheckOutGraceMinutes ?? 60)} phút</small>`, defaultScheduleShift(s.id) ? 'Ca mặc định' : `${dateLabel(s.effectiveFrom)}${s.effectiveTo ? ` – ${dateLabel(s.effectiveTo)}` : ''}`, badge(s.active ? 'Đang hoạt động' : 'Đã ngừng'), s.category === 'SUPPLEMENTARY' ? itemButtons(s.id, ['Sửa', 'ops-shift-edit'], [s.active ? 'Ngừng' : 'Kích hoạt', 'ops-shift-toggle']) : '<span class="muted">Ca chính mặc định</span>']), 'Chưa có cấu hình ca. Khởi tạo hai ca chính trước khi phân lịch.')}</section><section class="panel"><div class="panel-head"><h2>Lịch tăng ca đã phân</h2></div>${table(['Nhân viên', 'Ngày', 'Khung giờ', 'Trạng thái', 'Lý do'], rows(ctx, 'overtimeRequests').filter(x => x.status === 'APPROVED').sort((a, b) => b.workDate.localeCompare(a.workDate)).map(r => [person(r.employeeName, r.department), dateLabel(r.workDate), '18:00–22:00', badge('Đã duyệt'), e(r.reason || '—')]), 'Chưa có ca tăng ca đã duyệt.')}</section>`;
}

function weeklyShiftTotals(ctx, requests) {
  const counts = new Map();
  for (const request of requests.filter(request => request.status !== 'NEEDS_REVISION')) {
    for (const [date, ids] of Object.entries(request.shiftsByDate || {})) {
      for (const shiftId of new Set(ids || [])) {
        const key = `${date}|${shiftId}`;
        counts.set(key, { date, shiftId, count: (counts.get(key)?.count || 0) + 1 });
      }
    }
  }
  const ordered = [...counts.values()].sort((a, b) => a.date.localeCompare(b.date) || a.shiftId.localeCompare(b.shiftId));
  return ordered.length ? `${section('Số người theo ca đã gửi / duyệt', table(['Ngày', 'Ca', 'Số người'], ordered.map(row => [dateLabel(row.date), e(shiftLabel(ctx, row.shiftId)), e(row.count)])))}<p>Tính trên cả tuần; không gồm đơn đang cần sửa, không đổi theo bộ lọc danh sách.</p>` : '';
}
function renderRequests(ctx) {
  const kind = ctx.filters.requestGroup || 'weekly';
  const week = weekOf(ctx);
  const all = rows(ctx, requestCollection(kind)).filter(r => kind !== 'weekly' || r.weekStart === week);
  const filtered = all.filter(r => matchesStatus(ctx, r) && filterEmployee(ctx, r.employeeId, r.employeeName, r.department)).sort((a, b) => Number(b.status === 'PENDING') - Number(a.status === 'PENDING') || millis(b.createdAt) - millis(a.createdAt));
  const datesText = r => kind === 'weekly' ? `${dateLabel(r.weekStart)} – ${dateLabel(dateAdd(r.weekStart, 5))}` : kind === 'overtime' ? dateLabel(r.workDate) : `${dateLabel(r.startDate)}${r.endDate !== r.startDate ? ` – ${dateLabel(r.endDate)}` : ''}`;
  const active = rows(ctx, 'employees').filter(x => x.active);
  const submitted = new Set(all.map(x => x.employeeId));
  const missing = active.filter(x => !submitted.has(x.id));
  return `<div class="stats-grid">${stat('Đơn đã gửi', all.length, kind === 'weekly' ? 'Trong tuần đang chọn' : 'Trong nhóm đơn')}${stat('Chờ duyệt', all.filter(r => r.status === 'PENDING').length, 'Cần Admin xử lý')}${stat('Đã duyệt', all.filter(r => r.status === 'APPROVED').length, 'Đã có quyết định')}${stat(kind === 'weekly' ? 'Chưa gửi' : 'Từ chối', kind === 'weekly' ? missing.length : all.filter(r => r.status === 'REJECTED').length, kind === 'weekly' ? 'Nhân viên đang hoạt động' : 'Có phản hồi cho nhân viên')}</div><section class="panel"><div class="panel-head"><div><h2>Duyệt đơn từ</h2><p class="muted">Mở chi tiết để đối chiếu nội dung và phản hồi cho nhân viên.</p></div>${kind === 'weekly' ? button('Duyệt toàn bộ đơn chờ', 'ops-weekly-all', 'class="primary"') : ''}</div><div class="tabs">${[['weekly', 'Đăng ký tuần'], ['leave', 'Đơn khác'], ['overtime', 'Tăng ca']].map(([value, label]) => button(label, 'ops-request-group', `data-group="${value}" class="tab-button ${kind === value ? 'active' : ''}"`)).join('')}</div><div class="toolbar">${kind === 'weekly' ? `${button('‹ Tuần trước', 'ops-week-prev')}${field('week', 'Tuần bắt đầu', week, 'date', 'data-filter="week"')}${button('Tuần sau ›', 'ops-week-next')}${button('Tuần kế tiếp', 'ops-week-upcoming')}` : ''}${searchBox(ctx)}${departmentFilter(ctx)}${statusFilter(ctx, kind === 'weekly' ? { PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', NEEDS_REVISION: 'Cần sửa' } : kind === 'overtime' ? { PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối' } : REQUEST_STATUS)}</div>${kind === 'weekly' ? `<details class="notice"><summary>Tổng hợp · ${missing.length} nhân viên chưa gửi</summary><p>${e(missing.map(x => `${x.fullName} (${x.code || x.id})`).join(', ') || 'Tất cả nhân viên đã gửi đăng ký.')}</p><p>Hạn gửi: 12:00 thứ Bảy; hạn Admin duyệt: 17:00 thứ Bảy trước tuần làm. Đơn quá hạn vẫn cần xử lý.</p>${weeklyShiftTotals(ctx, all)}</details>` : ''}<p class="muted">Hiển thị ${filtered.length}/${all.length} đơn.</p>${table(['Nhân viên', 'Loại đơn', 'Ngày / tuần', 'Trạng thái', 'Thao tác'], filtered.map(r => [person(r.employeeName, `${rows(ctx, 'employees').find(x => x.id === r.employeeId)?.code || ''} · ${r.department || ''}`), e(kind === 'weekly' ? 'Đăng ký lịch tuần' : kind === 'overtime' ? 'Tăng ca 18:00–22:00' : REQUEST_TYPES[r.type] || r.type), datesText(r), badge(r.status), button('Chi tiết', 'ops-request-detail', `data-id="${e(r.id)}" data-kind="${kind}"`)]), 'Chưa có đơn phù hợp. Kiểm tra tuần đang chọn hoặc điều chỉnh bộ lọc.')}</section>`;
}

async function verifyAdmin(tx, ctx) {
  const user = await tx.get('users', docId(ctx.user.uid));
  if (!user || user.active !== true || user.role !== 'ADMIN') throw new Error('Tài khoản Admin không còn quyền thao tác.');
  return { uid: ctx.user.uid, name: user.displayName || actorName(ctx) };
}

function workDraft(values, ctx) {
  const start = localDateTime(values.startAt), end = localDateTime(values.deadline);
  if (end <= start) throw new Error('Hạn hoàn thành phải sau ngày bắt đầu.');
  if (!Object.hasOwn(PRIORITY, values.priority)) throw new Error('Mức ưu tiên không hợp lệ.');
  let relatedScheduleId = null, relatedShiftId = null;
  if (values.related) { const index = values.related.lastIndexOf('|'); if (index < 1) throw new Error('Lịch liên quan không hợp lệ.'); relatedScheduleId = docId(values.related.slice(0, index)); relatedShiftId = docId(values.related.slice(index + 1)); }
  return { title: required(values.title, 'Tên công việc', 160), description: required(values.description, 'Mô tả', 5000), requiredResult: required(values.requiredResult, 'Yêu cầu kết quả', 5000), assigneeId: docId(values.assigneeId), priority: values.priority, startAt: ctx.store.timestamp(start), deadline: ctx.store.timestamp(end), relatedScheduleId, relatedShiftId, relatedScheduleDate: null };
}

async function canonicalDraft(tx, draft) {
  const employee = await tx.get('employees', draft.assigneeId);
  if (!employee?.active) throw new Error('Người thực hiện không tồn tại hoặc đã nghỉ.');
  if (draft.relatedScheduleId) {
    const schedule = await tx.get('workSchedules', draft.relatedScheduleId);
    if (!schedule || schedule.employeeId !== draft.assigneeId || !shiftIds(schedule).includes(draft.relatedShiftId)) throw new Error('Ca liên quan không thuộc lịch của người thực hiện.');
    draft = { ...draft, relatedScheduleDate: schedule.date };
  }
  return { ...draft, assigneeName: employee.fullName };
}

export async function saveWorkItem(ctx, id, expectedVersion, draft) {
  const workId = id || ctx.store.id('workItems');
  await ctx.store.transaction(async tx => {
    const actor = await verifyAdmin(tx, ctx);
    const current = id ? rawData(await tx.get('workItems', id)) : null;
    if (id && !current) throw new Error('Công việc không còn tồn tại.');
    if (current && current.version !== expectedVersion) throw new Error('Công việc đã được người khác cập nhật. Tải lại trước khi lưu.');
    if (current?.status === 'COMPLETED') throw new Error('Công việc đã hoàn thành không thể sửa.');
    const canonical = await canonicalDraft(tx, draft);
    const reassigned = current && current.assigneeId !== canonical.assigneeId;
    const next = current ? { ...current, ...canonical, status: reassigned ? 'ASSIGNED' : current.status === 'PENDING_REVIEW' ? 'IN_PROGRESS' : current.status, resultReport: reassigned ? '' : current.resultReport, resultAttachments: reassigned ? [] : current.resultAttachments || [], managerFeedback: reassigned ? '' : current.managerFeedback, reworkCount: reassigned ? 0 : current.reworkCount, version: current.version + 1 } : { ...canonical, assignedById: actor.uid, assignedByName: actor.name, status: 'ASSIGNED', resultReport: '', resultAttachments: [], managerFeedback: '', completedAt: null, reworkCount: 0, version: 1, createdAt: ctx.store.stamp() };
    next.updatedAt = ctx.store.stamp(); next.lastUpdatedById = actor.uid; next.lastUpdatedByName = actor.name;
    tx.set('workItems', workId, next);
    tx.set(`workItems/${workId}/history`, String(next.version), { workItemId: workId, version: next.version, action: current ? 'EDITED' : 'CREATED', actorId: actor.uid, actorName: actor.name, createdAt: ctx.store.stamp(), before: current, after: next });
  });
  return workId;
}

export async function reviewWorkItem(ctx, id, expectedVersion, approve, feedback) {
  feedback = String(feedback || '').trim();
  if (feedback.length > 5000 || (!approve && !feedback)) throw new Error('Yêu cầu làm lại cần phản hồi; phản hồi tối đa 5.000 ký tự.');
  await ctx.store.transaction(async tx => {
    const actor = await verifyAdmin(tx, ctx), current = rawData(await tx.get('workItems', docId(id)));
    if (!current || current.version !== expectedVersion) throw new Error('Công việc đã thay đổi. Tải lại trước khi duyệt.');
    if (current.status !== 'PENDING_REVIEW' || !(current.resultReport?.trim() || current.resultAttachments?.length)) throw new Error('Công việc chưa có kết quả chờ duyệt.');
    const next = { ...current, status: approve ? 'COMPLETED' : 'IN_PROGRESS', managerFeedback: feedback, completedAt: approve ? ctx.store.stamp() : null, reworkCount: current.reworkCount + (approve ? 0 : 1), version: current.version + 1, updatedAt: ctx.store.stamp(), lastUpdatedById: actor.uid, lastUpdatedByName: actor.name };
    tx.set('workItems', id, next);
    tx.set(`workItems/${id}/history`, String(next.version), { workItemId: id, version: next.version, action: approve ? 'APPROVED' : 'REWORK_REQUESTED', actorId: actor.uid, actorName: actor.name, createdAt: ctx.store.stamp(), before: current, after: next });
  });
}

function workEditor(ctx, item = null) {
  const relatedOptions = rows(ctx, 'workSchedules').flatMap(schedule => shiftIds(schedule).map(id => ({ value: `${schedule.id}|${id}`, label: `${schedule.employeeName} · ${dateLabel(schedule.date)} · ${shiftLabel(ctx, id)}` })));
  ctx.ui.dialog({ title: item ? 'Chỉnh sửa công việc' : 'Tạo & giao công việc', wide: true, submitLabel: 'Lưu công việc', body: `<div class="form-grid">${field('title', 'Tên công việc', item?.title || '', 'text', 'required maxlength="160"')}${select('assigneeId', 'Người thực hiện', [{ value: '', label: 'Chọn nhân viên đang làm' }, ...rows(ctx, 'employees').filter(x => x.active).map(x => ({ value: x.id, label: `${x.code || ''} · ${x.fullName}` }))], item?.assigneeId || '')}${field('startAt', 'Bắt đầu (giờ Việt Nam)', datetimeInput(item?.startAt) || `${today()}T08:00`, 'datetime-local', 'required')}${field('deadline', 'Hạn hoàn thành (giờ Việt Nam)', datetimeInput(item?.deadline) || `${today()}T17:00`, 'datetime-local', 'required')}${select('priority', 'Ưu tiên', options(PRIORITY), item?.priority || 'NORMAL')}${select('related', 'Liên kết ca (tùy chọn)', [{ value: '', label: 'Không liên kết ca' }, ...relatedOptions], item?.relatedScheduleId ? `${item.relatedScheduleId}|${item.relatedShiftId}` : '')}</div>${textarea('description', 'Mô tả công việc', item?.description || '', 'required maxlength="5000"')}${textarea('requiredResult', 'Yêu cầu kết quả', item?.requiredResult || '', 'required maxlength="5000"')}${item ? '<p class="notice">Sửa việc đang chờ duyệt đưa việc về đang thực hiện. Đổi người thực hiện tạo lần giao mới và xóa báo cáo trên phiên bản mới; lịch sử trước đó vẫn được giữ.</p>' : ''}`, onSubmit: async values => { await saveWorkItem(ctx, item?.id, item?.version, workDraft(values, ctx)); ctx.ui.toast('Đã lưu công việc và lịch sử phiên bản.'); ctx.render(); } });
}

async function workDetail(ctx, id) {
  const item = await ctx.store.get('workItems', id);
  if (!item) throw new Error('Công việc không còn tồn tại.');
  const history = await ctx.store.list(`workItems/${id}/history`, { orderBy: [['version', 'desc']], limit: 100 });
  const attachments = item.resultAttachments || [];
  const attendanceEvidence = item.relatedScheduleId ? await linkedAttendanceEvidence(ctx, item) : '';
  const historyRows = history.rows.map(v => [e(v.version), e({ CREATED: 'Tạo & giao', EDITED: 'Chỉnh sửa', STARTED: 'Bắt đầu', PROGRESS_UPDATED: 'Cập nhật tiến độ', RESULT_SUBMITTED: 'Gửi kết quả', APPROVED: 'Duyệt hoàn thành', REWORK_REQUESTED: 'Yêu cầu làm lại' }[v.action] || v.action), e(v.actorName), dateTimeLabel(v.createdAt), `<details><summary>Xem thay đổi</summary><p>${e(v.before?.title || 'Tạo mới')} → ${e(v.after?.title || '')}</p><p>Trạng thái: ${e(WORK_STATUS[v.before?.status] || '—')} → ${e(WORK_STATUS[v.after?.status] || '')}</p>${textBlock(v.after?.resultReport)}${textBlock(v.after?.managerFeedback)}${v.after?.resultAttachments?.length ? section('Tệp kết quả của phiên bản này', attachmentButtons(id, v.after.resultAttachments, v.version)) : ''}</details>`]);
  ctx.ui.dialog({ title: item.title, wide: true, submitLabel: null, body: `<div class="detail-grid"><div><span class="muted">Người thực hiện</span>${person(item.assigneeName)}</div><div><span class="muted">Trạng thái</span>${badge(item.status)}</div><div>Bắt đầu: ${dateTimeLabel(item.startAt)}</div><div>Hạn: ${dateTimeLabel(item.deadline)}</div></div>${section('Mô tả công việc', textBlock(item.description))}${section('Yêu cầu kết quả', textBlock(item.requiredResult))}${section('Báo cáo của nhân viên', textBlock(item.resultReport))}${attachments.length ? section('Tệp kết quả', attachments.map(a => button(a.fileName, 'ops-attachment-download', `data-id="${e(id)}" data-attachment="${e(a.id)}"`)).join(' ')) : ''}${section('Phản hồi quản lý', textBlock(item.managerFeedback))}${item.relatedScheduleId ? section('Ca liên quan', `<p>${dateLabel(item.relatedScheduleDate)} · ${e(shiftLabel(ctx, item.relatedShiftId))}</p><p class="muted">Chấm công cung cấp bằng chứng có mặt; kết quả công việc được duyệt riêng.</p>${attendanceEvidence}`) : ''}${item.status === 'PENDING_REVIEW' ? `<div class="actions">${button('Duyệt hoàn thành', 'ops-work-approve', `data-id="${e(id)}" data-version="${item.version}" class="primary"`)}${button('Yêu cầu làm lại', 'ops-work-rework', `data-id="${e(id)}" data-version="${item.version}"`)}</div>` : ''}${section('Lịch sử phiên bản', table(['Phiên bản', 'Thao tác', 'Người thực hiện', 'Thời điểm', 'Chi tiết'], historyRows, 'Chưa có lịch sử.'))}${history.hasMore ? '<p class="muted">Đang hiển thị 100 phiên bản gần nhất.</p>' : ''}` });
}

async function linkedAttendanceEvidence(ctx, item) {
  const schedule = rows(ctx, 'workSchedules').find(row => row.id === item.relatedScheduleId && row.employeeId === item.assigneeId);
  const date = item.relatedScheduleDate || schedule?.date;
  if (!validDate(date) || !item.relatedShiftId) return '<p>Chưa có đủ thông tin ngày và ca để đối chiếu lượt chấm.</p>';
  const rangeReady = () => ctx.range?.complete && !ctx.range.loading && !ctx.range.error && ctx.range.from <= date && ctx.range.to >= date;
  if (!rangeReady() && ctx.loadRange) {
    try { await ctx.loadRange(date, date); }
    catch { return '<p>Dữ liệu có mặt của ca chưa tải được. Kiểm tra kết nối rồi mở lại chi tiết công việc.</p>'; }
  }
  if (!rangeReady()) return '<p>Dữ liệu có mặt của ca chưa đầy đủ. Mở Chấm công và tải ngày ca để kiểm tra.</p>';
  const freshSchedule = rows(ctx, 'workSchedules').find(row => row.id === item.relatedScheduleId && row.employeeId === item.assigneeId);
  const shift = rows(ctx, 'shifts').find(row => row.id === item.relatedShiftId);
  const linkedRegistered = shiftIds(freshSchedule).includes(item.relatedShiftId);
  const seen = new Set();
  const scans = assignScheduleDates(effectiveAttendance(ctx.data), rows(ctx, 'workSchedules'), rows(ctx, 'shifts'))
    .filter(row => row.employeeId === item.assigneeId && accepted(row) &&
      (shift == null ? row.shiftId === item.relatedShiftId && row.scheduleDate === date :
        row.shiftId == null && !linkedRegistered ? false : belongs(row, date, shift)))
    .filter(row => { const key = row.id || `${millis(row.timestamp)}|${row.type}`; if (seen.has(key)) return false; seen.add(key); return true; })
    .sort((a, b) => millis(a.timestamp) - millis(b.timestamp));
  return scans.length ? table(['Lượt hợp lệ của ca', 'Thời gian', 'Thiết bị'], scans.map(row => [badge(row.type), dateTimeLabel(row.timestamp), e(row.deviceId || row.stationId || 'Chưa xác định')])) : '<p>Chưa ghi nhận lượt quét hợp lệ thuộc ca này. Dữ liệu này không xác nhận kết quả công việc.</p>';
}

async function seedShifts(ctx) {
  await ctx.store.transaction(async tx => { await verifyAdmin(tx, ctx); const defaults = defaultScheduleShifts(); const existing = await Promise.all(defaults.map(s => tx.get('shifts', s.id))); defaults.forEach((s, i) => { if (!existing[i]) { const { id, ...data } = s; tx.set('shifts', id, data); } }); });
}

function shiftEditor(ctx, current = null) {
  if (current && current.category !== 'SUPPLEMENTARY') throw new Error('Ca chính dùng mẫu mặc định; chỉ chỉnh cấu hình ca bổ sung.');
  ctx.ui.dialog({ title: current ? 'Chỉnh cấu hình ca bổ sung' : 'Tạo cấu hình ca bổ sung', wide: true, submitLabel: 'Lưu ca', body: `<p class="notice">Ca bổ sung là cấu hình tham chiếu. Phân tăng ca cho nhân viên vẫn dùng khung cố định 18:00–22:00.</p><div class="form-grid">${field('name', 'Tên ca', current?.name || 'Ca bổ sung', 'text', 'required maxlength="120"')}${field('startTime', 'Giờ bắt đầu', current?.startTime || '18:00', 'time', 'required')}${field('endTime', 'Giờ kết thúc', current?.endTime || '22:00', 'time', 'required')}${field('effectiveFrom', 'Áp dụng từ', current?.effectiveFrom || today(), 'date', 'required')}${field('effectiveTo', 'Áp dụng đến (tùy chọn)', current?.effectiveTo || '', 'date')}${field('allowEarlyMinutes', 'Cho phép quét sớm (phút)', current?.allowEarlyMinutes ?? 0, 'number', 'min="0" max="1440" step="1"')}${field('lateGraceMinutes', 'Cho phép trễ (phút)', current?.lateGraceMinutes ?? 0, 'number', 'min="0" max="1440" step="1"')}${field('earlyLeaveAllowedMinutes', 'Cho phép về sớm (phút)', current?.earlyLeaveAllowedMinutes ?? 0, 'number', 'min="0" max="1440" step="1"')}${field('missingCheckOutGraceMinutes', 'Chờ thiếu quét ra (phút)', current?.missingCheckOutGraceMinutes ?? 120, 'number', 'min="0" max="1440" step="1"')}${field('breakStartTime', 'Bắt đầu nghỉ (tùy chọn)', current?.breakStartTime || '', 'time')}${field('breakEndTime', 'Kết thúc nghỉ (tùy chọn)', current?.breakEndTime || '', 'time')}${select('active', 'Trạng thái', [{ value: 'true', label: 'Đang hoạt động' }, { value: 'false', label: 'Ngừng hoạt động' }], String(current?.active ?? true))}</div>`, onSubmit: async values => { const next = { name: required(values.name, 'Tên ca', 120), category: 'SUPPLEMENTARY', startTime: values.startTime, endTime: values.endTime, effectiveFrom: values.effectiveFrom, effectiveTo: values.effectiveTo || null, allowEarlyMinutes: int(values.allowEarlyMinutes, 'Cho phép sớm'), lateGraceMinutes: int(values.lateGraceMinutes, 'Cho phép trễ'), earlyLeaveAllowedMinutes: int(values.earlyLeaveAllowedMinutes, 'Cho phép về sớm'), missingCheckOutGraceMinutes: int(values.missingCheckOutGraceMinutes, 'Chờ quét ra'), breakStartTime: values.breakStartTime || null, breakEndTime: values.breakEndTime || null, countsOvertime: true, active: values.active === 'true' }; validateShift(next); const id = current?.id || ctx.store.id('shifts'); await ctx.store.transaction(async tx => { await verifyAdmin(tx, ctx); if (current) { const latest = await tx.get('shifts', id); if (!latest || latest.category !== 'SUPPLEMENTARY' || Object.keys(next).some(k => JSON.stringify(latest[k] ?? null) !== JSON.stringify(current[k] ?? null))) throw new Error('Cấu hình ca đã thay đổi. Mở lại trước khi lưu.'); } tx.set('shifts', id, next); tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'shift', id, `Lưu cấu hình ca ${next.name}`)); }); ctx.ui.toast('Đã lưu cấu hình ca.'); ctx.render(); } });
}

function assignmentDates(from, to) {
  isoDate(from); isoDate(to);
  if (to < from || (new Date(`${to}T00:00Z`) - new Date(`${from}T00:00Z`)) / 86400000 > 30) throw new Error('Khoảng phân ca phải từ 1 đến 31 ngày.');
  const dates = [];
  for (let day = from; day <= to; day = dateAdd(day, 1)) if (weekday(day) !== 0) dates.push(day);
  return dates;
}

function assignmentEditor(ctx, employeeId = '', date = '') {
  const main = assignableScheduleShifts(rows(ctx, 'shifts'));
  const staff = rows(ctx, 'employees').filter(employee => employee.active);
  const shiftOptions = category => [{ value: '', label: 'Không chọn ca này' }, ...main.filter(shift => shift.category === category).map(shift => ({ value: shift.id, label: `${shift.name} · ${shift.startTime}–${shift.endTime}` }))];
  const current = rows(ctx, 'workSchedules').find(schedule => schedule.employeeId === employeeId && schedule.date === date);
  const existingCategories = new Set(shiftIds(current).map(id => rows(ctx, 'shifts').find(shift => shift.id === id)?.category).filter(Boolean));
  const selectedFor = category => main.find(shift => shift.category === category && (existingCategories.has(category) || !current && category === 'MORNING'))?.id || '';
  const from = date || weekOf(ctx), to = date || dateAdd(weekOf(ctx), 5);
  let selectedDates = new Set(assignmentDates(from, to));
  const dayChoices = dates => dates.map(day => `<label class="checkbox"><input type="checkbox" name="scheduleDates" value="${e(day)}" ${selectedDates.has(day) ? 'checked' : ''}><span>${e(DAYS[weekday(day) - 1])} · ${dateLabel(day)}</span></label>`).join('');
  const checkedValues = (values, form, name, fallback = []) => {
    if (form?.querySelectorAll) return [...form.querySelectorAll(`input[name="${name}"]:checked`)].map(input => input.value);
    if (values[name] !== undefined) return Array.isArray(values[name]) ? values[name] : [values[name]];
    return fallback;
  };
  const onChange = (event, form) => {
    if (event.target.name === 'scope') {
      form.querySelector('[data-assignment-single]').hidden = event.target.value !== 'employee';
      form.querySelector('[data-assignment-many]').hidden = event.target.value !== 'employees';
      form.querySelector('[data-assignment-department]').hidden = event.target.value !== 'department';
    }
    if (event.target.name === 'scheduleDates') selectedDates = new Set(checkedValues({}, form, 'scheduleDates'));
    if (['from', 'to'].includes(event.target.name)) {
      const target = form.querySelector('#assignment-days');
      try {
        const nextDates = assignmentDates(form.elements.namedItem('from').value, form.elements.namedItem('to').value);
        selectedDates = new Set(nextDates);
        target.innerHTML = dayChoices(nextDates);
      } catch (error) { selectedDates = new Set(); target.innerHTML = `<p role="alert">${e(error.message)}</p>`; }
    }
  };
  ctx.ui.dialog({ title: 'Phân ca làm việc', wide: true, submitLabel: 'Lưu phân ca', onChange,
    body: `<div class="form-grid">${select('scope', 'Phân cho', [{ value: 'employee', label: 'Một nhân viên' }, { value: 'employees', label: 'Nhiều nhân viên' }, { value: 'department', label: 'Phòng ban' }], 'employee')}
      <div data-assignment-single>${select('employeeId', 'Nhân viên', [{ value: '', label: 'Chọn nhân viên' }, ...staff.map(employee => ({ value: employee.id, label: `${employee.code || ''} · ${employee.fullName}` }))], employeeId || staff[0]?.id || '')}</div>
      <div data-assignment-department hidden>${select('departmentId', 'Phòng ban', [{ value: '', label: 'Chọn phòng ban' }, ...rows(ctx, 'departments').filter(department => department.active !== false).map(department => ({ value: department.id, label: department.name }))], '')}</div>
      ${field('from', 'Từ ngày', from, 'date', 'required')}${field('to', 'Đến ngày', to, 'date', 'required')}
      ${select('morning', 'Ca sáng', shiftOptions('MORNING'), selectedFor('MORNING'))}${select('afternoon', 'Ca chiều', shiftOptions('EVENING'), selectedFor('EVENING'))}
      ${select('mode', 'Cách áp dụng', [{ value: 'merge', label: 'Thêm / thay ca cùng loại, giữ ca khác' }, { value: 'replace', label: 'Chỉ giữ các ca được chọn' }], employeeId && date ? 'replace' : 'merge')}</div>
      <fieldset data-assignment-many hidden><legend>Chọn nhân viên</legend><div class="assignment-choices">${staff.map(employee => `<label class="checkbox"><input type="checkbox" name="employeeIds" value="${e(employee.id)}"><span>${e(employee.code || '')} · ${e(employee.fullName)}</span></label>`).join('') || '<p>Chưa có nhân viên đang làm.</p>'}</div></fieldset>
      <fieldset><legend>Chọn từng ngày làm việc</legend><div class="assignment-choices" id="assignment-days">${dayChoices(assignmentDates(from, to))}</div></fieldset>
      <p class="notice">${current ? `Lịch hiện tại: ${e(current.shiftName || shiftIds(current).map(id => shiftLabel(ctx, id)).join(' + '))}. ` : ''}Chọn một hoặc nhiều nhân viên, hoặc cả phòng ban. Chọn từng ngày thứ Hai–thứ Bảy trong khoảng tối đa 31 ngày. Điều chỉnh công và ghi chú cũ được giữ. Chọn “Chỉ giữ” để bỏ ca không còn làm.</p>
      ${employeeId && date ? `<div class="actions">${button('Điều chỉnh giờ công', 'attendance-adjust', `data-employee="${e(employeeId)}" data-date="${e(date)}"`)}${current ? button('Bỏ lịch ngày này', 'ops-schedule-remove', `data-id="${e(current.id)}" data-date="${e(date)}"`) : ''}</div><p class="hint">Điều chỉnh giờ công mở bước riêng cho đúng nhân viên và ngày: tối đa 24 giờ, bắt buộc lý do, giữ lượt quét gốc.</p>` : ''}`,
    onSubmit: async (values, form) => {
      const availableDates = assignmentDates(values.from, values.to);
      const dates = [...new Set(checkedValues(values, form, 'scheduleDates', availableDates))];
      if (!dates.length || dates.some(day => !availableDates.includes(day))) throw new Error('Chọn ít nhất một ngày làm việc trong khoảng đang xem.');
      const selected = [values.morning, values.afternoon].filter(Boolean);
      if (!selected.length) throw new Error('Chọn ít nhất một ca chính.');
      let employees;
      if (values.scope === 'department') {
        const department = rows(ctx, 'departments').find(department => department.id === values.departmentId);
        if (!department || department.active === false) throw new Error('Chọn phòng ban đang hoạt động.');
        employees = rows(ctx, 'employees').filter(employee => employee.active && (employee.departmentId === department.id || employee.department === department.name));
      } else if (values.scope === 'employees') {
        const ids = new Set(checkedValues(values, form, 'employeeIds'));
        employees = rows(ctx, 'employees').filter(employee => employee.active && ids.has(employee.id));
        if (!ids.size || employees.length !== ids.size) throw new Error('Chọn nhân viên đang làm. Danh sách đã thay đổi; mở lại trước khi phân.');
      } else if (values.scope === 'employee') employees = rows(ctx, 'employees').filter(employee => employee.active && employee.id === values.employeeId);
      else throw new Error('Cách chọn nhân viên không hợp lệ.');
      if (!employees.length) throw new Error('Chưa có nhân viên để phân ca.');
      const assigned = await assignSchedules(ctx, employees.map(employee => employee.id), dates, selected, values.mode, values.scope === 'department' ? values.departmentId : null);
      ctx.ui.toast(`Đã lưu ${assigned} lịch làm việc.`); ctx.render();
    },
  });
}

export async function assignSchedules(ctx, employeeIds, dates, selectedIds, mode = 'merge', departmentId = null) {
  if (!['merge', 'replace'].includes(mode)) throw new Error('Cách áp dụng phân ca không hợp lệ.');
  if (!employeeIds.length || !dates.length || !selectedIds.length || selectedIds.length > 2 || new Set(selectedIds).size !== selectedIds.length) throw new Error('Chọn nhân viên, ngày và tối đa hai ca khác nhau.');
  employeeIds.forEach(docId); dates.forEach(workDate); selectedIds.forEach(docId);
  const slots = [...new Set(employeeIds)].flatMap(employeeId => [...new Set(dates)].map(date => ({ employeeId, date, id: `${employeeId}_${date}` })));
  let saved = 0;
  try {
    for (let offset = 0; offset < slots.length; offset += 40) {
      const chunk = slots.slice(offset, offset + 40);
      await ctx.store.transaction(async tx => {
        await verifyAdmin(tx, ctx);
        const department = departmentId ? await tx.get('departments', departmentId) : null;
        if (departmentId && (!department || department.active === false)) throw new Error('Phòng ban đã ngừng hoạt động.');
        const employees = new Map(); for (const employeeId of new Set(chunk.map(x => x.employeeId))) { const employee = await tx.get('employees', employeeId); if (!employee?.active || (department && employee.departmentId !== departmentId && employee.department !== department.name)) throw new Error('Nhân viên đã nghỉ hoặc đổi phòng ban. Mở lại danh sách trước khi phân.'); employees.set(employeeId, employee); }
        const previous = new Map(); for (const slot of chunk) previous.set(slot.id, rawData(await tx.get('workSchedules', slot.id)));
        const allShiftIds = [...new Set([...selectedIds, ...[...previous.values()].flatMap(shiftIds)])];
        const shifts = new Map(), missingDefaults = new Map();
        for (const id of allShiftIds) {
          let data = await tx.get('shifts', id);
          if (!data) {
            const standard = selectedIds.includes(id) ? defaultScheduleShift(id) : null;
            if (!standard) throw new Error('Ca làm không còn tồn tại.');
            const { id: ignored, ...fields } = standard;
            data = fields; missingDefaults.set(id, fields);
          }
          shifts.set(id, { ...data, id });
        }
        const selected = selectedIds.map(id => shifts.get(id)).sort((a, b) => a.startTime.localeCompare(b.startTime));
        selected.forEach(s => {
          validateShift(s, true);
          const standard = defaultScheduleShift(s.id);
          if (standard && (s.category !== standard.category || s.startTime !== standard.startTime || s.endTime !== standard.endTime)) throw new Error('Ca mẫu đã đổi giờ hoặc loại. Kiểm tra cấu hình ca trước khi phân.');
        });
        if (new Set(selected.map(x => x.category)).size !== selected.length) throw new Error('Mỗi ngày chỉ chọn một ca sáng và một ca chiều.');
        for (const [id, fields] of missingDefaults) tx.set('shifts', id, fields);
        for (const slot of chunk) {
          const employee = employees.get(slot.employeeId), prior = previous.get(slot.id);
          const incoming = { employeeId: slot.employeeId, employeeName: employee.fullName, department: employee.department || '', date: slot.date, assignedBy: ctx.user.uid, source: departmentId ? 'DEPARTMENT' : 'ADMIN', overtimeHours: 0 };
          const next = mode === 'replace' ? { ...(prior || {}), ...incoming, shiftId: selected[0].id, shiftIds: selected.map(x => x.id), shiftName: selected.map(x => x.name).join(' + '), overtimeHours: prior?.overtimeHours ?? 0, workedHoursOverride: prior?.workedHoursOverride ?? null, adjustmentNote: prior?.adjustmentNote || '', note: prior?.note || '' } : mergeAssignment(prior, incoming, selected, shifts);
          tx.set('workSchedules', slot.id, next);
        }
        tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'workSchedule', chunk[0].id, `Phân ${chunk.length} lịch; ca ${selected.map(x => x.name).join(' + ')}; cách ${mode}`));
      });
      saved += chunk.length;
    }
  } catch (error) { throw new Error(`Đã lưu ${saved}/${slots.length} lịch. Có thể thử lại an toàn. ${error.message}`); }
  return saved;
}

export async function copyPreviousWeek(ctx, targetWeek) {
  const target = monday(isoDate(targetWeek)), source = dateAdd(target, -7);
  const found = await ctx.store.list('workSchedules', { where: [['date', '>=', source], ['date', '<=', dateAdd(source, 5)]], limit: 10000 });
  if (found.hasMore) throw new Error('Tuần nguồn vượt giới hạn tải. Chia nhỏ theo phòng ban trước khi sao chép.');
  let saved = 0;
  try { for (let offset = 0; offset < found.rows.length; offset += 40) {
    const chunk = found.rows.slice(offset, offset + 40);
    const count = await ctx.store.transaction(async tx => {
      await verifyAdmin(tx, ctx);
      const candidates = [];
      for (const row of chunk) {
        if (weekday(row.date) === 0) continue;
        const date = dateAdd(row.date, 7), id = `${row.employeeId}_${date}`;
        const previous = await tx.get('workSchedules', id), employee = await tx.get('employees', row.employeeId);
        if (!previous && employee?.active) candidates.push({ row, employee, date, id });
      }
      const shifts = new Map(); for (const id of new Set(candidates.flatMap(c => shiftIds(c.row)))) { const shift = await tx.get('shifts', id); if (!shift) throw new Error('Ca nguồn không còn tồn tại.'); validateShift(shift, true); shifts.set(id, shift); }
      for (const candidate of candidates) { const { id: ignored, ...data } = candidate.row; tx.set('workSchedules', candidate.id, { ...data, employeeName: candidate.employee.fullName, department: candidate.employee.department || '', date: candidate.date, shiftIds: shiftIds(candidate.row), assignedBy: ctx.user.uid, source: 'ADMIN_COPY' }); }
      if (candidates.length) tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'week', target, `Sao chép ${candidates.length} lịch từ ${source}; giữ lịch đã có, bỏ nhân viên đã nghỉ`));
      return candidates.length;
    });
    saved += count;
  } } catch (error) { throw new Error(`Đã sao chép ${saved} lịch mới. Lịch đã có được giữ; có thể thử lại an toàn. ${error.message}`); }
  return saved;
}

function overtimeEditor(ctx) {
  ctx.ui.dialog({ title: 'Phân ca tăng ca', submitLabel: 'Phân ca 18:00–22:00', body: `<p class="notice">Ca tăng ca có hiệu lực sau khi lưu. Giờ trả lương vẫn dựa trên cặp chấm vào/ra hợp lệ trong khung 18:00–22:00.</p>${select('employeeId', 'Nhân viên', [{ value: '', label: 'Chọn nhân viên' }, ...rows(ctx, 'employees').filter(x => x.active).map(x => ({ value: x.id, label: `${x.code || ''} · ${x.fullName}` }))])}${field('date', 'Ngày tăng ca', today(), 'date', 'required')}${textarea('reason', 'Lý do phân tăng ca', '', 'required maxlength="1000"')}`, onSubmit: async values => { const employeeId = docId(values.employeeId), date = workDate(values.date), reason = required(values.reason, 'Lý do', 1000), id = `${employeeId}_${date}`; await ctx.store.transaction(async tx => { const actor = await verifyAdmin(tx, ctx); const employee = await tx.get('employees', employeeId), existing = await tx.get('overtimeRequests', id); if (!employee?.active) throw new Error('Nhân viên đã nghỉ hoặc không tồn tại.'); if (existing) throw new Error('Nhân viên đã có đơn / ca tăng ca ngày này. Xem tại Đơn từ.'); const data = { employeeId, employeeName: employee.fullName, department: employee.department || '', workDate: date, startTime: '18:00', endTime: '22:00', reason, status: 'APPROVED', createdAt: ctx.store.stamp(), reviewerId: actor.uid, reviewerName: actor.name, reviewedAt: ctx.store.stamp(), rejectionReason: null }; tx.set('overtimeRequests', id, data); tx.set('audit_logs', `${id}_OVERTIME_ASSIGN`, stampAudit(ctx, 'OVERTIME_ASSIGN', 'overtimeRequest', id, `Phân tăng ca 18:00–22:00 ngày ${date}`, reason, actor)); tx.set('notifications', ctx.store.id('notifications'), notification(ctx, 'OVERTIME_RESULT', 'Đã phân ca tăng ca', `Bạn được phân tăng ca 18:00–22:00 ngày ${date}. ${reason}`, id, data)); }); ctx.ui.toast('Đã phân ca tăng ca.'); ctx.render(); } });
}

export async function reviewRequest(ctx, kind, id, approve, note) {
  if (!['weekly', 'overtime', 'leave'].includes(kind)) throw new Error('Nhóm đơn không hợp lệ.');
  note = String(note || '').trim();
  if ((!approve && !note) || note.length > 1000) throw new Error('Từ chối / yêu cầu sửa cần lý do, tối đa 1.000 ký tự.');
  const collection = requestCollection(kind);
  await ctx.store.transaction(async tx => {
    const actor = await verifyAdmin(tx, ctx), current = await tx.get(collection, docId(id));
    if (!current || current.status !== 'PENDING') throw new Error('Đơn đã được Admin khác xử lý. Tải lại danh sách.');
    const employee = await tx.get('employees', current.employeeId);
    if (approve && !employee?.active) throw new Error('Nhân viên đã nghỉ hoặc không tồn tại; không thể duyệt.');
    const status = approve ? 'APPROVED' : kind === 'weekly' ? 'NEEDS_REVISION' : 'REJECTED';
    const update = { status, reviewerId: actor.uid, reviewerName: actor.name, reviewedAt: ctx.store.stamp() };
    if (kind === 'weekly') {
      isoDate(current.weekStart); if (weekday(current.weekStart) !== 1) throw new Error('Tuần đăng ký phải bắt đầu thứ Hai.');
      update.reviewNote = note;
      const dates = DAYS.map((_, i) => dateAdd(current.weekStart, i));
      if (approve) {
        if (!current.shiftsByDate || Object.keys(current.shiftsByDate).some(d => !dates.includes(d))) throw new Error('Đăng ký có ngày không hợp lệ.');
        const shifts = new Map();
        for (const shiftId of new Set(Object.values(current.shiftsByDate).flat())) { const shift = await tx.get('shifts', docId(shiftId)); if (!shift) throw new Error('Ca đã đăng ký không còn tồn tại.'); validateShift(shift, true); shifts.set(shiftId, { ...shift, id: shiftId }); }
        const previous = new Map(); for (const date of dates) previous.set(date, await tx.get('workSchedules', `${current.employeeId}_${date}`));
        const plans = dates.map(date => { const ids = current.shiftsByDate[date] || []; if (!Array.isArray(ids) || ids.length > 2 || new Set(ids).size !== ids.length || new Set(ids.map(x => shifts.get(x)?.category)).size !== ids.length) throw new Error('Lịch đăng ký có ca trùng loại hoặc không hợp lệ.'); const prior = previous.get(date); return { date, ids, prior }; });
        for (const { date, ids, prior } of plans) { const scheduleId = `${current.employeeId}_${date}`; if (!ids.length) { if (prior) tx.remove('workSchedules', scheduleId); } else tx.set('workSchedules', scheduleId, { employeeId: current.employeeId, employeeName: current.employeeName, department: current.department || '', date, shiftId: ids[0], shiftIds: ids, shiftName: shifts.get(ids[0]).name, overtimeHours: prior?.overtimeHours ?? 0, workedHoursOverride: prior?.workedHoursOverride ?? null, adjustmentNote: prior?.adjustmentNote || '', assignedBy: actor.uid, source: 'WEEKLY_EMPLOYEE', note: prior?.note || '' }); }
      }
      tx.update(collection, id, update);
      tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'weeklyScheduleRequest', id, `${approve ? 'Duyệt' : 'Yêu cầu sửa'} đăng ký tuần ${current.weekStart}`, approve ? '' : note, actor));
      tx.set('notifications', ctx.store.id('notifications'), notification(ctx, 'WEEKLY_SCHEDULE_RESULT', approve ? 'Lịch tuần đã được duyệt' : 'Cần chỉnh sửa đăng ký lịch tuần', note || `Lịch tuần ${current.weekStart} đã được duyệt.`, id, current));
      return;
    }
    if (kind === 'overtime') {
      workDate(current.workDate); if (current.startTime !== '18:00' || current.endTime !== '22:00') throw new Error('Đơn tăng ca không đúng khung 18:00–22:00.');
      update.rejectionReason = approve ? null : note;
      tx.update(collection, id, update);
      tx.set('audit_logs', `${id}_OVERTIME_REVIEW`, { ...stampAudit(ctx, 'OVERTIME_REVIEW', 'overtimeRequest', id, `Xử lý tăng ca: ${status}`, approve ? '' : note, actor), status });
      tx.set('notifications', ctx.store.id('notifications'), notification(ctx, 'OVERTIME_RESULT', approve ? 'Tăng ca đã được duyệt' : 'Đơn tăng ca bị từ chối', `${current.workDate} 18:00–22:00. ${note}`, id, current));
      return;
    }
    if (!Object.hasOwn(REQUEST_TYPES, current.type) || isoDate(current.endDate) < isoDate(current.startDate)) throw new Error('Đơn có loại hoặc ngày không hợp lệ.');
    update.reviewNote = note || null;
    let schedule = null, shift = null, scheduleId = null, adjustmentId = null;
    if (approve && current.type === 'SHIFT_CHANGE') { scheduleId = `${current.employeeId}_${current.startDate}`; schedule = await tx.get('workSchedules', scheduleId); shift = await tx.get('shifts', docId(current.requestedShiftId)); if (!schedule || !shift) throw new Error('Lịch hoặc ca đề nghị không còn tồn tại.'); validateShift(shift, true); }
    if (approve && current.type === 'ATTENDANCE_ADJUSTMENT') {
      if (current.startDate !== current.endDate || !(current.proposedCheckIn || current.proposedCheckOut)) throw new Error('Đơn điều chỉnh chưa có thời gian hợp lệ.');
      let checkIn = current.proposedCheckIn ? localDateTime(`${current.startDate}T${current.proposedCheckIn}`) : null;
      let checkOut = current.proposedCheckOut ? localDateTime(`${current.startDate}T${current.proposedCheckOut}`) : null;
      if (checkIn && checkOut && checkOut <= checkIn) checkOut = localDateTime(`${dateAdd(current.startDate, 1)}T${current.proposedCheckOut}`);
      adjustmentId = ctx.store.id('attendanceAdjustments');
      const reason = `Được duyệt từ đơn ${id}: ${current.reason}`;
      const adjustment = { employeeId: current.employeeId, employeeName: current.employeeName, scheduleDate: current.startDate, checkInAt: checkIn ? ctx.store.timestamp(checkIn) : null, checkOutAt: checkOut ? ctx.store.timestamp(checkOut) : null, workedHoursOverride: null, reason, actorId: actor.uid, actorName: actor.name, createdAt: ctx.store.stamp() };
      tx.set('attendanceAdjustments', adjustmentId, adjustment);
      tx.set('audit_logs', adjustmentId, stampAudit(ctx, 'ATTENDANCE_ADJUST', 'attendanceAdjustment', adjustmentId, `Điều chỉnh theo đơn ${id}; employeeId=${current.employeeId}; scheduleDate=${current.startDate}`, reason, actor));
      update.appliedAdjustmentId = adjustmentId;
    }
    if (schedule && shift) tx.set('workSchedules', scheduleId, { ...rawData(schedule), shiftId: current.requestedShiftId, shiftIds: [current.requestedShiftId], shiftName: shift.name, source: 'REQUEST', note: `Đổi ca được duyệt từ đơn ${id}` });
    tx.update(collection, id, update);
    tx.set('audit_logs', `${id}_REVIEW`, stampAudit(ctx, scheduleId ? 'SHIFT_UPDATE' : 'LEAVE_REVIEW', scheduleId ? 'workSchedule' : 'leaveRequest', scheduleId || id, `Xử lý ${current.type}: ${status}; requestId=${id}`, note, actor));
    tx.set('notifications', ctx.store.id('notifications'), notification(ctx, 'REQUEST_RESULT', approve ? 'Đơn từ đã được duyệt' : 'Đơn từ bị từ chối', `${REQUEST_TYPES[current.type]}: ${note || 'Đã xử lý'}`, id, current));
  });
}

async function requestDetail(ctx, kind, id) {
  const request = await ctx.store.get(requestCollection(kind), id);
  if (!request) throw new Error('Đơn không còn tồn tại.');
  let extra = '';
  if (kind === 'weekly') extra = section('Sáu ngày đăng ký', table(['Ngày', 'Ca đã chọn'], DAYS.map((day, i) => { const date = dateAdd(request.weekStart, i); return [`${day} · ${dateLabel(date)}`, e((request.shiftsByDate?.[date] || []).map(id => shiftLabel(ctx, id)).join(' + ') || 'Nghỉ')]; }), ''));
  else if (kind === 'overtime') extra = `<p>Ngày ${dateLabel(request.workDate)} · 18:00–22:00</p><p class="muted">Chỉ tính giờ tăng ca khi có lượt vào/ra hợp lệ sau khi được duyệt.</p>`;
  else { extra = `<p>${e(REQUEST_TYPES[request.type] || request.type)} · ${dateLabel(request.startDate)} – ${dateLabel(request.endDate)}</p>`; if (request.leaveShiftsByDate) extra += section('Ca nghỉ đề nghị', table(['Ngày', 'Ca'], Object.entries(request.leaveShiftsByDate).map(([date, ids]) => [dateLabel(date), e(ids.map(id => shiftLabel(ctx, id)).join(' + '))]), '')); if (request.type === 'ATTENDANCE_ADJUSTMENT') extra += `<p>Giờ vào đề nghị: ${e(request.proposedCheckIn || 'Giữ giờ cũ')} · Giờ ra: ${e(request.proposedCheckOut || 'Giữ giờ cũ')}</p>`; if (request.type === 'SHIFT_CHANGE') extra += `<p>Ca muốn đổi: ${e(shiftLabel(ctx, request.requestedShiftId))}</p>`; if (request.attachmentUrl) { try { const url = new URL(request.attachmentUrl); if (url.protocol === 'https:') extra += `<a href="${e(url.href)}" target="_blank" rel="noopener noreferrer">Mở tệp đính kèm</a>`; } catch {} } }
  ctx.ui.dialog({ title: kind === 'weekly' ? 'Chi tiết đăng ký tuần' : kind === 'overtime' ? 'Chi tiết đăng ký tăng ca' : 'Chi tiết đơn từ', wide: true, submitLabel: null, body: `${person(request.employeeName, request.department)}${badge(request.status)}${extra}${section('Lý do / ghi chú', textBlock(request.reason))}<p class="muted">Gửi lúc ${dateTimeLabel(request.createdAt)}${request.reviewedAt ? ` · Xử lý lúc ${dateTimeLabel(request.reviewedAt)} bởi ${e(request.reviewerName || '')}` : ''}</p>${request.reviewNote || request.rejectionReason ? section('Phản hồi Admin', textBlock(request.reviewNote || request.rejectionReason)) : ''}${request.status === 'PENDING' ? `<div class="actions">${button('Duyệt', 'ops-request-approve', `data-id="${e(id)}" data-kind="${kind}" class="primary"`)}${button(kind === 'weekly' ? 'Yêu cầu sửa' : 'Từ chối', 'ops-request-reject', `data-id="${e(id)}" data-kind="${kind}"`)}</div><p class="notice">${kind === 'weekly' ? 'Duyệt áp dụng toàn bộ sáu ngày: ngày nghỉ bỏ lịch đã có của ngày đó, giữ các điều chỉnh công và ghi chú ở ngày còn làm.' : 'Thao tác ghi quyết định, cập nhật dữ liệu liên quan và gửi thông báo cho nhân viên.'}</p>` : ''}` });
}

async function downloadAttachment(ctx, workId, attachmentId, version = null) {
  const item = await ctx.store.get('workItems', docId(workId));
  if (!item) throw new Error('Công việc không còn tồn tại.');
  let source = item;
  if (version != null) {
    if (!/^\d+$/.test(String(version)) || !Number.isSafeInteger(Number(version))) throw new Error('Phiên bản lịch sử không hợp lệ.');
    const entry = await ctx.store.get(`workItems/${workId}/history`, String(Number(version)));
    if (!entry || entry.version !== Number(version)) throw new Error('Không tìm thấy phiên bản lịch sử này.');
    source = entry.after;
  }
  const reference = source?.resultAttachments?.find(a => a.id === attachmentId);
  if (!reference) throw new Error(version == null ? 'Tệp không thuộc kết quả hiện tại. Xem lịch sử nếu công việc đã được đổi người.' : 'Tệp không thuộc phiên bản lịch sử đã chọn.');
  const metadata = await ctx.store.get(`workItems/${workId}/attachments`, docId(attachmentId));
  if (!metadata || metadata.sizeBytes < 1 || metadata.sizeBytes > 5242880 || metadata.chunkCount < 1 || metadata.chunkCount > 10 || metadata.sha256 !== reference.sha256) throw new Error('Siêu dữ liệu tệp không hợp lệ.');
  const chunks = [];
  for (let i = 0; i < metadata.chunkCount; i++) { const chunk = await ctx.store.get(`workItems/${workId}/attachments/${attachmentId}/chunks`, String(i)); const bytes = chunk?.data?.toUint8Array?.() || (chunk?.data instanceof Uint8Array ? chunk.data : null); if (!bytes || chunk.index !== i || bytes.length !== (i === metadata.chunkCount - 1 ? metadata.sizeBytes - i * 524288 : 524288)) throw new Error('Tệp thiếu hoặc có phần dữ liệu không hợp lệ.'); chunks.push(bytes); }
  const blob = new Blob(chunks, { type: metadata.mimeType });
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))].map(b => b.toString(16).padStart(2, '0')).join('');
  if (hash !== metadata.sha256) throw new Error('Mã kiểm tra tệp không khớp. Không tải tệp bị thay đổi.');
  const link = document.createElement('a'), url = URL.createObjectURL(blob); link.href = url; link.download = metadata.fileName.replace(/[\\/]/g, '_'); link.click(); setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export async function onAction(action, element, ctx) {
  if (!action.startsWith('ops-')) return false;
  const id = element.dataset.id;
  if (action === 'ops-work-new') workEditor(ctx);
  else if (action === 'ops-work-edit') { const item = await ctx.store.get('workItems', docId(id)); if (!item) throw new Error('Công việc không còn tồn tại.'); workEditor(ctx, { ...item, id }); }
  else if (action === 'ops-work-detail') await workDetail(ctx, docId(id));
  else if (action === 'ops-work-approve' || action === 'ops-work-rework') { const approve = action === 'ops-work-approve'; ctx.ui.dialog({ title: approve ? 'Duyệt hoàn thành công việc' : 'Yêu cầu làm lại', submitLabel: approve ? 'Duyệt hoàn thành' : 'Gửi yêu cầu làm lại', body: textarea('feedback', approve ? 'Phản hồi (tùy chọn)' : 'Lý do yêu cầu làm lại', '', `${approve ? '' : 'required'} maxlength="5000"`), onSubmit: async values => { await reviewWorkItem(ctx, docId(id), Number(element.dataset.version), approve, values.feedback); ctx.ui.toast(approve ? 'Đã duyệt hoàn thành.' : 'Đã gửi yêu cầu làm lại.'); ctx.render(); } }); }
  else if (action === 'ops-attachment-download') await downloadAttachment(ctx, id, element.dataset.attachment, element.dataset.version ?? null);
  else if (action === 'ops-shift-new') shiftEditor(ctx);
  else if (action === 'ops-shift-edit') { const current = await ctx.store.get('shifts', docId(id)); if (!current) throw new Error('Ca không còn tồn tại.'); shiftEditor(ctx, { ...current, id }); }
  else if (action === 'ops-shift-seed') { await seedShifts(ctx); ctx.ui.toast('Đã kiểm tra và khởi tạo hai ca chính mặc định.'); ctx.render(); }
  else if (action === 'ops-shift-toggle') { const current = await ctx.store.get('shifts', docId(id)); if (!current || current.category !== 'SUPPLEMENTARY') throw new Error('Chỉ đổi trạng thái ca bổ sung.'); ctx.ui.confirm(current.active ? 'Ngừng ca bổ sung' : 'Kích hoạt ca bổ sung', 'Lịch sử công và lương được giữ. Cấu hình này sẽ đổi trạng thái hoạt động.', async () => { await ctx.store.transaction(async tx => { await verifyAdmin(tx, ctx); const latest = await tx.get('shifts', id); if (!latest || latest.active !== current.active || latest.category !== 'SUPPLEMENTARY') throw new Error('Cấu hình đã thay đổi. Mở lại trước khi lưu.'); tx.update('shifts', id, { active: !latest.active }); tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'shift', id, `${latest.active ? 'Ngừng' : 'Kích hoạt'} ca ${latest.name}`)); }); ctx.ui.toast('Đã cập nhật trạng thái ca.'); ctx.render(); }); }
  else if (action === 'ops-schedule-new') {
    const range = scheduleCalendarRange(ctx.filters);
    assignmentEditor(ctx, '', range.view === 'month' ? selectedScheduleDate(ctx.filters) : '');
  }
  else if (action === 'ops-schedule-day') { workDate(element.dataset.date); ctx.filters.scheduleDate = element.dataset.date; ctx.render(); }
  else if (action === 'ops-schedule-cell') assignmentEditor(ctx, element.dataset.employee, element.dataset.date);
  else if (action === 'ops-schedule-remove') { const current = await ctx.store.get('workSchedules', docId(id)); if (!current) throw new Error('Lịch không còn tồn tại.'); ctx.ui.confirm('Bỏ lịch làm việc ngày này', `Bỏ lịch ${current.employeeName} ngày ${dateLabel(current.date)}. Dữ liệu quét gốc và lịch sử lương được giữ; công đang tính từ lịch có thể thay đổi.`, async () => { await ctx.store.transaction(async tx => { await verifyAdmin(tx, ctx); const latest = await tx.get('workSchedules', id); if (!latest || JSON.stringify(latest) !== JSON.stringify(current)) throw new Error('Lịch đã thay đổi. Mở lại trước khi bỏ.'); tx.remove('workSchedules', id); tx.set('audit_logs', ctx.store.id('audit_logs'), stampAudit(ctx, 'SHIFT_UPDATE', 'workSchedule', id, `Bỏ lịch ngày ${current.date} của ${current.employeeName}`)); }); ctx.ui.toast('Đã bỏ lịch ngày này.'); ctx.render(); }); }
  else if (action === 'ops-week-prev' || action === 'ops-week-next' || action === 'ops-week-upcoming') { ctx.filters.week = action === 'ops-week-upcoming' ? dateAdd(monday(today()), 7) : dateAdd(weekOf(ctx), action === 'ops-week-prev' ? -7 : 7); ctx.render(); if (ctx.loadRange) await ctx.loadRange(ctx.filters.week, dateAdd(ctx.filters.week, 6)); }
  else if (action === 'ops-week-copy') ctx.ui.confirm('Sao chép lịch tuần trước', `Tạo các lịch còn thiếu trong tuần ${dateLabel(weekOf(ctx))}. Lịch đã có và nhân viên đã nghỉ được bỏ qua. Ghi chú và điều chỉnh giờ công trên lịch nguồn cũng được sao chép như trên app.`, async () => { const count = await copyPreviousWeek(ctx, weekOf(ctx)); ctx.ui.toast(`Đã sao chép ${count} lịch mới.`); ctx.render(); });
  else if (action === 'ops-overtime-new') overtimeEditor(ctx);
  else if (action === 'ops-request-group') { ctx.filters.requestGroup = element.dataset.group; ctx.filters.status = ''; ctx.filters.search = ''; if (!ctx.filters.week) ctx.filters.week = dateAdd(monday(today()), 7); ctx.render(); }
  else if (action === 'ops-request-detail') await requestDetail(ctx, element.dataset.kind || ctx.filters.requestGroup || 'weekly', docId(id));
  else if (action === 'ops-request-approve' || action === 'ops-request-reject') { const kind = element.dataset.kind, approve = action === 'ops-request-approve'; ctx.ui.dialog({ title: approve ? 'Duyệt đơn' : kind === 'weekly' ? 'Yêu cầu sửa đăng ký tuần' : 'Từ chối đơn', submitLabel: approve ? 'Xác nhận duyệt' : 'Gửi phản hồi', body: `${approve && kind === 'weekly' ? '<p class="notice">Duyệt áp dụng lịch cả sáu ngày đã đăng ký; ngày nghỉ bỏ lịch đã có trong ngày đó.</p>' : ''}${textarea('note', approve ? 'Ghi chú duyệt (tùy chọn)' : 'Lý do / phản hồi', '', `${approve ? '' : 'required'} maxlength="1000"`)}`, onSubmit: async values => { await reviewRequest(ctx, kind, docId(id), approve, values.note); ctx.ui.toast(approve ? 'Đã duyệt và cập nhật dữ liệu.' : 'Đã gửi phản hồi cho nhân viên.'); ctx.render(); } }); }
  else if (action === 'ops-weekly-all') { const week = weekOf(ctx), requests = rows(ctx, 'weeklyScheduleRequests').filter(r => r.weekStart === week && r.status === 'PENDING'); if (!requests.length) throw new Error('Tuần này không có đơn đang chờ.'); ctx.ui.confirm('Duyệt toàn bộ đăng ký tuần', `Duyệt ${requests.length} đơn chờ của tuần ${dateLabel(week)} – ${dateLabel(dateAdd(week, 5))}, gồm cả đơn bị ẩn bởi bộ lọc. Lịch sáu ngày của mỗi đơn được áp dụng, ngày nghỉ bỏ lịch của ngày đó.`, async () => { let count = 0; try { for (const request of requests) { await reviewRequest(ctx, 'weekly', request.id, true, ''); count++; } } catch (error) { throw new Error(`Đã duyệt ${count}/${requests.length} đơn. ${error.message}`); } ctx.ui.toast(`Đã duyệt ${count} đăng ký tuần.`); ctx.render(); }); }
  else return false;
  return true;
}
