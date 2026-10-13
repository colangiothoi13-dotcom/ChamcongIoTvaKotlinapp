import { e, icon, badge, field, select, textarea, table, button, person, money, dateLabel, dateTimeLabel, today, normalize, emptyState, stat } from './ui.js';
import {renderFaceEmployeeControls} from './face-enrollment.js';

const COMMANDS = [
  ['TEST_LED_GREEN', 'Thử LED xanh'], ['TEST_LED_RED', 'Thử LED đỏ'],
  ['TEST_BUZZER', 'Thử còi'], ['OPEN_DOOR', 'Mở cửa'], ['CLOSE_DOOR', 'Đóng cửa'],
  ['SYNC_ATTENDANCE', 'Đồng bộ chấm công'], ['RESTART_DEVICE', 'Khởi động lại'],
];
const valuesOf = (ctx, collection) => ctx.data[collection] || [];
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const clean = value => String(value ?? '').trim();
const attrs = id => `data-id="${e(id)}"`;
const current = (ctx, collection, id) => valuesOf(ctx, collection).find(row => row.id === id);
const auditWrite = (ctx, action, type, id, details, reason = '') => ({
  type: 'set', collection: 'audit_logs', id: ctx.store.id('audit_logs'),
  data: ctx.audit(action, type, id, details, reason),
});
const hasDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00+07:00`))
  && new Date(`${value}T00:00:00+07:00`).toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }) === value;
const timestampMillis = value => {
  if (!value) return null;
  if (typeof value.toMillis === 'function') return value.toMillis();
  if (typeof value.toDate === 'function') return value.toDate().getTime();
  if (typeof value.seconds === 'number') return value.seconds * 1000;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : null;
};

export function nextEmployeeCode(lastNumber, existingCodes) {
  const maximum = existingCodes.reduce((max, code) => {
    const match = /^NV(\d+)$/.exec(String(code));
    return match ? Math.max(max, Number(match[1])) : max;
  }, 0);
  const next = Math.max(Number(lastNumber) || 0, maximum) + 1;
  assert(Number.isSafeInteger(next), 'Bộ đếm mã nhân viên đã vượt giới hạn.');
  return `NV${String(next).padStart(4, '0')}`;
}

export function isCommandBusy(command) {
  return Boolean(command && (['REQUESTED', 'PROCESSING'].includes(command.status)
    || (command.status === 'COMPLETED' && command.applied !== true)));
}

export function deviceConnectionState(device, now = Date.now()) {
  const heartbeat = timestampMillis(device.lastHeartbeat);
  if (heartbeat === null) return 'NO_HEARTBEAT';
  if (heartbeat - now > 30000) return 'CLOCK_SKEW';
  if (now - heartbeat > 120000) return 'STALE_HEARTBEAT';
  if ([device.status, device.wifiStatus].some(value => clean(value).toUpperCase() === 'OFFLINE')) return 'REPORTED_OFFLINE';
  return 'ONLINE';
}

const connectionLabel = state => ({ ONLINE: 'Đang kết nối', NO_HEARTBEAT: 'Chưa có tín hiệu',
  CLOCK_SKEW: 'Sai giờ thiết bị', STALE_HEARTBEAT: 'Mất tín hiệu', REPORTED_OFFLINE: 'Ngoại tuyến' }[state] || state);
const commandLabel = command => {
  if (!command) return 'Chưa có lệnh';
  const type = COMMANDS.find(item => item[0] === command.type)?.[1]
    || ({ ENROLL_FINGERPRINT: 'Đăng ký vân tay', DELETE_FINGERPRINT: 'Xóa vân tay' }[command.type]) || command.type;
  const status = ({ REQUESTED: 'Chờ thiết bị', PROCESSING: 'Đang xử lý', FAILED: 'Thất bại',
    COMPLETED: command.applied === true ? 'Hoàn tất' : 'Đang đồng bộ kết quả' }[command.status]) || command.status;
  return `${type} · ${status}`;
};

function searchFilter(ctx, rows, keys) {
  const search = normalize(ctx.filters.search || '');
  return rows.filter(row => !search || keys.some(key => normalize(row[key] || '').includes(search)));
}

function filterToolbar(ctx, { status = false, department = false, placeholder = 'Tìm kiếm...' } = {}) {
  return `<div class="toolbar"><div class="search-field">${icon('search')}<input type="search" data-filter="search" aria-label="Tìm kiếm" placeholder="${e(placeholder)}" value="${e(ctx.filters.search || '')}"></div>
    ${status ? `<select data-filter="status" aria-label="Trạng thái"><option value="">Tất cả trạng thái</option><option value="active" ${ctx.filters.status === 'active' ? 'selected' : ''}>Đang hoạt động</option><option value="retired" ${ctx.filters.status === 'retired' ? 'selected' : ''}>Đã ngừng hoạt động</option></select>` : ''}
    ${department ? `<select data-filter="department" aria-label="Phòng ban"><option value="">Tất cả phòng ban</option>${valuesOf(ctx, 'departments').map(row => `<option value="${e(row.id)}" ${ctx.filters.department === row.id ? 'selected' : ''}>${e(row.name)}</option>`).join('')}</select>` : ''}</div>`;
}

function employeeFingerprint(ctx, employee) {
  const command = valuesOf(ctx, 'deviceCommands').find(row => row.employeeId === employee.id);
  if (command && isCommandBusy(command)) return `<span class="muted">${e(commandLabel(command))}</span>`;
  if (employee.fingerprintTemplateId != null) return badge(`Mẫu #${employee.fingerprintTemplateId}`);
  if (employee.pendingTemplateId != null) return badge(`Đang chờ #${employee.pendingTemplateId}`);
  return '<span class="muted">Chưa đăng ký</span>';
}

function employeeFaceStatus(ctx, employee) {
  const profile = valuesOf(ctx, 'faceProfiles').find(row => (row.employeeId || row.id) === employee.id);
  if (ctx.errors?.faceProfiles) return '<span class="muted">Chưa tải được trạng thái</span>';
  if (ctx.loaded?.faceProfiles === false) return '<span class="muted">Đang tải…</span>';
  return profile?.enabled === true && Number(profile.version) > 0 ? badge('Đã đăng ký') : '<span class="muted">Chưa đăng ký</span>';
}

function renderEmployees(ctx) {
  const employees = valuesOf(ctx, 'employees');
  const active = employees.filter(row => row.active !== false);
  const visible = searchFilter(ctx, employees, ['fullName', 'code', 'email', 'department', 'position']).filter(row =>
    (!ctx.filters.status || (ctx.filters.status === 'active' ? row.active !== false : row.active === false))
    && (!ctx.filters.department || row.departmentId === ctx.filters.department
      || (!row.departmentId && row.department === current(ctx, 'departments', ctx.filters.department)?.name)));
  return `<div class="page-heading"><div><h1>Nhân viên</h1><p>Hồ sơ, tài khoản, vân tay và khuôn mặt của toàn bộ nhân sự.</p></div>${button('Thêm nhân viên', 'org-employee-add', 'class="primary"')}</div>
    <div class="stats-grid">${stat('Đang làm việc', active.length, 'Nhân viên đang hoạt động', 'users')}${stat('Đã đăng ký vân tay', active.filter(row => row.fingerprintTemplateId != null).length, 'Mẫu đã đồng bộ với thiết bị', 'shield')}${stat('Đã nghỉ', employees.length - active.length, 'Giữ hồ sơ và lịch sử chấm công', 'history')}</div>
    <section class="panel">${filterToolbar(ctx, { status: true, department: true, placeholder: 'Tìm tên, mã, email hoặc phòng ban...' })}
    ${table(['Nhân viên', 'Phòng ban / Chức vụ', 'Liên hệ', 'Đơn giá giờ', 'Vân tay', 'Khuôn mặt', 'Trạng thái', 'Thao tác'], visible.map(row => [
      `<button type="button" class="employee-name-button" data-action="org-employee-detail" ${attrs(row.id)} aria-label="${e(`Xem chi tiết nhân viên ${row.fullName}`)}">${person(row.fullName, row.code)}</button>`, `<strong>${e(row.department || 'Chưa phân phòng')}</strong><div class="muted">${e(row.position || '—')}</div>`,
      `<div>${e(row.email || '—')}</div><div class="muted">${e(row.phone || '—')}</div>`, `${money(row.baseSalary || 0)}<div class="muted">/ giờ</div>`,
      employeeFingerprint(ctx, row), employeeFaceStatus(ctx, row), badge(row.active === false ? 'Đã nghỉ' : 'Đang làm việc'),
      `<div class="row-actions">${row.active !== false ? `${button('Sửa hồ sơ', 'org-employee-edit', attrs(row.id))}${button('Đơn giá giờ', 'org-employee-salary', attrs(row.id))}` : ''}</div>`,
    ]), 'Chưa có nhân viên phù hợp với bộ lọc.')}</section>`;
}

function renderDepartments(ctx) {
  const rows = searchFilter(ctx, valuesOf(ctx, 'departments'), ['name']).filter(row => !ctx.filters.status
    || (ctx.filters.status === 'active' ? row.active !== false : row.active === false));
  return `<div class="page-heading"><div><h1>Phòng ban</h1><p>Sắp xếp nhân sự theo bộ phận và cập nhật tên thống nhất.</p></div>${button('Thêm phòng ban', 'org-department-add', 'class="primary"')}</div>
    <section class="panel">${filterToolbar(ctx, { status: true, placeholder: 'Tìm phòng ban...' })}${table(['Phòng ban', 'Nhân viên đang làm', 'Trạng thái', 'Thao tác'], rows.map(row => [
      person(row.name, 'Phòng ban'), String(valuesOf(ctx, 'employees').filter(employee => employee.active !== false
        && (employee.departmentId === row.id || (!employee.departmentId && employee.department === row.name))).length),
      badge(row.active === false ? 'Đã ngừng hoạt động' : 'Đang hoạt động'),
      `<div class="row-actions">${button('Đổi tên', 'org-department-edit', attrs(row.id))}${button(row.active === false ? 'Kích hoạt' : 'Ngừng hoạt động', 'org-department-toggle', attrs(row.id))}</div>`,
    ]), 'Chưa có phòng ban phù hợp.')}</section>`;
}

function renderDevices(ctx) {
  const devices = searchFilter(ctx, valuesOf(ctx, 'devices'), ['name', 'id', 'location']);
  const online = devices.filter(row => deviceConnectionState(row) === 'ONLINE').length;
  return `<div class="page-heading"><div><h1>Thiết bị IoT</h1><p>Theo dõi kết nối, hàng đợi chấm công và điều khiển thiết bị.</p></div></div>
    <div class="stats-grid">${stat('Thiết bị', devices.length, 'Đã kết nối với hệ thống', 'device')}${stat('Đang kết nối', online, 'Tín hiệu trong vòng 2 phút', 'check')}${stat('Chờ đồng bộ', devices.reduce((sum, row) => sum + (Number(row.pendingAttendanceCount) || 0), 0), 'Lượt chấm công trong hàng đợi', 'clock')}</div>
    ${filterToolbar(ctx, { placeholder: 'Tìm thiết bị hoặc vị trí...' })}
    <div class="device-grid">${devices.map(device => {
      const state = deviceConnectionState(device);
      const command = valuesOf(ctx, 'deviceCommands').find(row => row.id === device.id || row.deviceId === device.id);
      const busy = isCommandBusy(command);
      const canAct = state === 'ONLINE' && !busy;
      const telemetry = [['Wi-Fi', device.wifiStatus], ['Đồng bộ Firebase', device.firebaseSyncStatus], ['Cảm biến', device.sensorStatus], ['Cửa', device.doorStatus]];
      const latest = valuesOf(ctx, 'attendance').filter(row => row.deviceId === device.id).sort((a, b) => (timestampMillis(b.timestamp) || 0) - (timestampMillis(a.timestamp) || 0))[0];
      return `<section class="panel device-card"><div class="card-heading"><div><h3>${e(device.name || device.id)}</h3><p>${e(device.location || 'Chưa cập nhật vị trí')} · ${e(device.id)}</p></div>${badge(connectionLabel(state))}</div>
        <div class="detail-grid">${telemetry.map(([label, value]) => `<div><span class="muted">${e(label)}</span><strong>${e(value || 'UNKNOWN')}</strong></div>`).join('')}
        <div><span class="muted">Vân tay</span><strong>${e(device.fingerprintCount ?? '?')} / ${e(device.capacity ?? '?')}</strong></div><div><span class="muted">Phiên bản</span><strong>${e(device.firmwareVersion || '—')}</strong></div>
        <div><span class="muted">Tín hiệu gần nhất</span><strong>${dateTimeLabel(device.lastHeartbeat)}</strong></div><div><span class="muted">Quét lỗi (báo lần cuối)</span><strong>${e(device.failedScanCount || 0)}</strong></div></div>
        ${device.lastError ? `<p class="form-error">${e(device.lastError)}</p>` : ''}
        <p class="muted">Hàng đợi: ${e(device.pendingAttendanceCount || 0)} lượt · ${e(device.pendingAttendanceBytes || 0)} / ${e(device.pendingAttendanceCapacity || 0)} byte · ${e(device.attendanceOutboxStatus || 'UNKNOWN')}</p>
        <p class="muted">Chấm công gần nhất: ${latest ? `${e(latest.employeeName)} · ${dateTimeLabel(latest.timestamp)}` : 'Chưa có dữ liệu'}</p>
        <div class="row-actions">${button('Sửa tên / vị trí', 'org-device-edit', attrs(device.id))}${COMMANDS.map(([type, label]) => button(label, 'org-device-command', `${attrs(device.id)} data-type="${type}" ${(!canAct || (['OPEN_DOOR', 'CLOSE_DOOR'].includes(type) && !(device.capabilities || []).includes('door'))) ? 'disabled' : ''}`)).join('')}</div>
        <p class="muted">${e(commandLabel(command))}${command?.message ? ` · ${e(command.message)}` : ''}</p>${busy ? '<p class="hint">Thiết bị đang xử lý một lệnh. Các thao tác mới sẽ khả dụng khi hoàn tất.</p>' : ''}</section>`;
    }).join('') || emptyState('Chưa có thiết bị', 'Thiết bị sẽ xuất hiện khi gửi tín hiệu đầu tiên đến hệ thống.')}</div>
    <section class="panel"><div class="card-heading"><h3>Lệnh thiết bị gần đây</h3></div>${table(['Thiết bị', 'Lệnh / Trạng thái', 'Nhân viên', 'Thời gian', 'Phản hồi'], valuesOf(ctx, 'deviceCommands').map(command => [
      e(command.deviceId || command.id), e(commandLabel(command)), e(command.employeeName || '—'), dateTimeLabel(command.createdAt), e(command.message || '—'),
    ]), 'Chưa có lệnh thiết bị.')}</section>`;
}

export function render(page, ctx) {
  if (page === 'employees') return renderEmployees(ctx);
  if (page === 'departments') return renderDepartments(ctx);
  if (page === 'devices') return renderDevices(ctx);
  return '';
}

function validateEmployee(values) {
  const fullName = clean(values.fullName);
  const hireDate = clean(values.hireDate);
  const email = clean(values.email);
  const phone = clean(values.phone);
  assert(fullName && fullName.length <= 160, 'Họ tên phải từ 1–160 ký tự.');
  assert(!hireDate || hasDate(hireDate), 'Ngày vào làm không hợp lệ.');
  assert(!email || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email), 'Email không hợp lệ.');
  assert(phone.length <= 25 && /^[\d+() -]*$/.test(phone), 'Số điện thoại không hợp lệ.');
  assert(clean(values.address).length <= 200, 'Địa chỉ tối đa 200 ký tự.');
  return { fullName, email, phone, address: clean(values.address), position: clean(values.position), hireDate };
}

async function saveEmployee(ctx, id, values) {
  const fields = validateEmployee(values);
  const creating = !id;
  const employeeId = id || ctx.store.id('employees');
  const all = creating ? (await ctx.store.list('employees', {})).rows : [];
  const auditId = ctx.store.id('audit_logs');
  const committed = await ctx.store.transaction(async tx => {
    const old = await tx.get('employees', employeeId);
    assert(creating ? !old : old && old.active !== false, 'Không tìm thấy nhân viên đang làm việc.');
    const counter = creating ? await tx.get('system', 'employeeCounter') : null;
    const preserveLegacy = values.departmentId === '__preserve_legacy__' && old && !old.departmentId;
    const departmentId = preserveLegacy ? '' : clean(values.departmentId);
    const department = departmentId ? await tx.get('departments', departmentId) : null;
    assert(!departmentId || (department && (department.active !== false || old?.departmentId === departmentId
      || (!old?.departmentId && old?.department === department.name))), 'Phòng ban không còn hoạt động.');
    const data = { ...fields, departmentId, department: preserveLegacy ? old.department : (department?.name || '') };
    let saved;
    if (creating) {
      const code = nextEmployeeCode(counter?.lastNumber, all.map(row => row.code));
      saved = { ...data, code, terminationDate: '', baseSalary: 0,
        fingerprintTemplateId: null, fingerprintDeviceId: 'GATE-01', pendingTemplateId: null, active: true };
      tx.set('employees', employeeId, saved);
      tx.set('system', 'employeeCounter', { lastNumber: Number(code.slice(2)) });
    } else {
      tx.update('employees', employeeId, data);
      saved = { ...old, ...data };
    }
    tx.set('audit_logs', auditId, ctx.audit(creating ? 'EMPLOYEE_CREATE' : 'EMPLOYEE_UPDATE', 'employee', employeeId, `Lưu hồ sơ ${fields.fullName}`));
    return { ...saved, id: employeeId };
  });
  ctx.ui.toast(creating ? 'Đã tạo hồ sơ nhân viên.' : 'Đã cập nhật hồ sơ nhân viên.');
  return committed;
}

function employeeTools(ctx, employee, profilesOverride = null, accountError = '') {
  const saved = Boolean(employee?.id), active = saved && employee.active !== false;
  const profiles = profilesOverride || (saved ? valuesOf(ctx, 'users').filter(row => row.employeeId === employee.id) : []);
  const accountFailed = accountError || (profilesOverride === null && ctx.errors?.users);
  const accountReady = !accountFailed && (profilesOverride !== null || ctx.loaded?.users !== false);
  const command = saved && valuesOf(ctx, 'deviceCommands').find(row => row.employeeId === employee.id && isCommandBusy(row));
  const busy = Boolean(command) || employee?.pendingTemplateId != null;
  const registered = employee?.fingerprintTemplateId != null;
  const fingerprintControls = !saved ? button('Đăng ký vân tay', 'org-fingerprint-enroll', 'disabled')
    : busy ? '<p class="hint">Đang chờ thiết bị hoàn tất yêu cầu vân tay. Hãy chờ kết quả trước khi đăng ký tiếp.</p>'
      : registered ? button('Xóa / làm lại vân tay', 'org-fingerprint-delete', attrs(employee.id))
        : active ? button('Đăng ký vân tay', 'org-fingerprint-enroll', attrs(employee.id) + ' class="primary"') : '<p class="hint">Nhân viên đã ngừng hoạt động; không đăng ký mẫu mới.</p>';
  return `<div class="employee-editor-tools" data-employee-tools>
    <section class="employee-editor-section" aria-labelledby="employee-account-title"><h3 id="employee-account-title">Tài khoản</h3><p>Tài khoản đăng nhập được liên kết với hồ sơ nhân viên này.</p>
      ${profiles.length ? table(['Tên hiển thị', 'Email đăng nhập', 'Trạng thái'], profiles.map(profile => [e(profile.displayName), e(profile.email), badge(profile.active === false ? 'Đã khóa' : 'Đang hoạt động')])) : accountFailed ? '<p class="form-error" role="alert">Chưa tải được tài khoản liên kết. Vui lòng mở lại hồ sơ để thử lại.</p>' : `<p class="muted">${saved && !accountReady ? 'Đang tải tài khoản liên kết…' : 'Chưa có tài khoản đăng nhập.'}</p>`}
      ${!profiles.length && (!saved || accountReady) ? button('Tạo tài khoản', 'org-account-create', `${saved ? attrs(employee.id) : ''}${active && accountReady ? '' : ' disabled'}`) : ''}</section>
    <section class="employee-editor-section" aria-labelledby="employee-fingerprint-title"><h3 id="employee-fingerprint-title">Vân tay</h3><p>Nhân viên đặt ngón tay lên cảm biến sau khi gửi yêu cầu đăng ký.</p><p>${saved ? employeeFingerprint(ctx, employee) : '<span class="muted">Chưa đăng ký</span>'}</p><div class="row-actions">${fingerprintControls}</div></section>
    <section class="employee-editor-section" aria-labelledby="employee-face-title"><h3 id="employee-face-title">Khuôn mặt</h3><p>Dùng camera tại trạm và dịch vụ nhận diện đang chạy trên laptop.</p>${saved ? renderFaceEmployeeControls(employee, ctx) : `<span class="badge neutral">Chưa đăng ký</span><div class="row-actions">${button('Đăng ký khuôn mặt', 'face-enroll', 'disabled')}</div>`}</section></div>`;
}

async function employeeDialog(ctx, employee = null) {
  let profiles = null, accountError = '';
  if (employee?.id) {
    try {
      // The open editor does not rerender with the collection subscription.
      // Read this employee's account links from the server before showing tools.
      profiles = (await ctx.store.list('users', { where: [['employeeId', '==', employee.id]] })).rows;
    } catch {
      profiles = [];
      accountError = 'unavailable';
    }
  }
  const departments = valuesOf(ctx, 'departments').filter(row => row.active !== false || row.id === employee?.departmentId || row.name === employee?.department);
  const matchedId = employee?.departmentId || departments.find(row => row.name === employee?.department)?.id || '';
  const legacy = Boolean(employee?.department && !matchedId);
  const departmentOptions = [{ value: '', label: 'Chưa phân phòng' },
    ...(legacy ? [{ value: '__preserve_legacy__', label: employee.department }] : []),
    ...departments.map(row => ({ value: row.id, label: row.name }))];
  const baseline = { fullName: employee?.fullName || '', email: employee?.email || '', phone: employee?.phone || '', departmentId: legacy ? '__preserve_legacy__' : matchedId,
    position: employee?.position || '', hireDate: employee?.hireDate || today(), address: employee?.address || '' };
  const originalDisabled = new Map();
  const rememberToolStates = form => {
    for (const control of form.querySelectorAll('[data-employee-tools] button[data-action]')) {
      if (!originalDisabled.has(control)) originalDisabled.set(control, control.disabled);
    }
  };
  const draftMessage = employee ? 'Tài khoản, vân tay và khuôn mặt dùng cùng hồ sơ này. Lưu thay đổi hồ sơ trước khi thao tác ở các mục bên dưới.'
    : 'Lưu hồ sơ trước để hệ thống cấp mã nhân viên. Sau đó bạn có thể tạo tài khoản, đăng ký vân tay hoặc khuôn mặt ngay trong cửa sổ này.';
  const draftChanged = (event, form) => {
    if (!Object.hasOwn(baseline, event.target?.name)) return;
    const dirty = Object.entries(baseline).some(([name, value]) => String(form.elements?.namedItem(name)?.value ?? value) !== String(value));
    if (form.dataset) form.dataset.employeeDraft = dirty ? 'dirty' : 'clean';
    rememberToolStates(form);
    for (const control of form.querySelectorAll('[data-employee-tools] button[data-action]')) {
      control.disabled = dirty || originalDisabled.get(control);
    }
    const note = form.querySelector('[data-employee-draft-note]');
    if (note) note.textContent = dirty ? 'Bạn có thay đổi hồ sơ chưa lưu. Bấm Lưu hồ sơ để giữ thông tin trước khi tạo tài khoản hoặc đăng ký mẫu.' : draftMessage;
  };
  ctx.ui.dialog({ title: employee ? `Nhân viên · ${employee.fullName}` : 'Thêm nhân viên', wide: true, submitLabel: employee ? 'Lưu hồ sơ' : 'Lưu hồ sơ & tiếp tục',
    body: `<div class="employee-editor" data-employee-editor="${e(employee?.id || 'new')}"><section class="employee-editor-section" aria-labelledby="employee-profile-title"><h3 id="employee-profile-title">Hồ sơ</h3>${employee ? `<p class="hint">${e(employee.code || employee.id)} · ${e(employee.fullName)}</p>` : ''}<div class="form-grid">${field('fullName', 'Họ và tên', employee?.fullName || '', 'text', 'required maxlength="160"')}${field('email', 'Email liên hệ', employee?.email || '', 'email')}
      ${field('phone', 'Số điện thoại', employee?.phone || '', 'tel', 'maxlength="25"')}${select('departmentId', 'Phòng ban', departmentOptions, legacy ? '__preserve_legacy__' : matchedId)}
      ${field('position', 'Chức vụ', employee?.position || '')}${field('hireDate', 'Ngày vào làm', employee?.hireDate || today(), 'date')}${textarea('address', 'Địa chỉ', employee?.address || '', 'maxlength="200"')}</div>
      ${employee ? '<p class="hint">Đổi email liên hệ không đổi email đăng nhập của tài khoản đã tạo.</p>' : ''}</section>
      <p class="notice" data-employee-draft-note role="status" aria-live="polite">${e(draftMessage)}</p>${employeeTools(ctx, employee, profiles, accountError)}<p class="hint">Có thể dùng vân tay hoặc khuôn mặt. Nếu cần cả hai, đăng ký lần lượt cho cùng nhân viên. Lưu hồ sơ chỉ lưu thông tin; chưa gửi lệnh đăng ký mẫu.</p></div>`,
    onOpen: rememberToolStates,
    onInput: draftChanged,
    onChange: draftChanged,
    onSubmit: async values => {
      const saved = await saveEmployee(ctx, employee?.id, values);
      await employeeDialog(ctx, saved);
      return saved;
    },
  });
}

async function employeeDetail(ctx, id) {
  const employee = await ctx.store.get('employees', id);
  assert(employee, 'Không tìm thấy nhân viên.');
  const profiles = (await ctx.store.list('users', { where: [['employeeId', '==', id]] })).rows;
  const attendance = valuesOf(ctx, 'attendance').filter(row => row.employeeId === id).slice(0, 8);
  ctx.ui.dialog({ title: employee.fullName, wide: true, submitLabel: null,
    body: `<div class="employee-editor"><section class="employee-editor-section"><h3>Hồ sơ</h3><div class="detail-grid"><div><span class="muted">Mã nhân viên</span><strong>${e(employee.code)}</strong></div><div><span class="muted">Trạng thái</span><strong>${e(employee.active === false ? 'Đã nghỉ' : 'Đang làm việc')}</strong></div>
      <div><span class="muted">Phòng ban</span><strong>${e(employee.department || '—')}</strong></div><div><span class="muted">Chức vụ</span><strong>${e(employee.position || '—')}</strong></div>
      <div><span class="muted">Email liên hệ</span><strong>${e(employee.email || '—')}</strong></div><div><span class="muted">Điện thoại</span><strong>${e(employee.phone || '—')}</strong></div>
      <div><span class="muted">Ngày vào làm</span><strong>${dateLabel(employee.hireDate)}</strong></div><div><span class="muted">Ngày nghỉ</span><strong>${dateLabel(employee.terminationDate)}</strong></div>
      <div><span class="muted">Đơn giá giờ</span><strong>${money(employee.baseSalary || 0)}</strong></div><div><span class="muted">Vân tay</span><strong>${employeeFingerprint(ctx, employee)}</strong></div></div>
      <p>${e(employee.address || 'Chưa cập nhật địa chỉ.')}</p><div class="row-actions">${employee.active !== false ? `${button('Sửa hồ sơ', 'org-employee-edit', attrs(id) + ' class="primary"')}${button('Đặt đơn giá giờ', 'org-employee-salary', attrs(id))}` : ''}</div></section>
      ${employeeTools(ctx, employee, profiles)}
      <section class="employee-editor-section"><h3>Chấm công gần đây</h3>${table(['Thời gian', 'Loại', 'Trạng thái'], attendance.map(row => [dateTimeLabel(row.timestamp), badge(row.type), badge(row.status)]), 'Chưa có lượt chấm công gần đây.')}</section>
      ${employee.active !== false ? `<div class="row-actions">${button('Chuyển sang đã nghỉ', 'org-employee-retire', attrs(id) + ' class="danger"')}</div>` : ''}</div>`,
  });
}

async function salaryDialog(ctx, id) {
  const employee = await ctx.store.get('employees', id);
  assert(employee && employee.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
  ctx.ui.dialog({ title: `Đơn giá giờ · ${employee.fullName}`, submitLabel: 'Lưu đơn giá',
    body: `${field('salary', 'Đơn giá mỗi giờ (VNĐ)', employee.baseSalary || 0, 'number', 'required min="0" max="1000000000000" step="1"')}<p class="hint">Đơn giá mới dùng cho các lần tính lương tiếp theo. Phiếu lương đã lưu giữ nguyên.</p>`,
    onSubmit: async values => {
      const salary = Number(values.salary);
      assert(Number.isSafeInteger(salary) && salary >= 0 && salary <= 1000000000000, 'Đơn giá phải là số nguyên từ 0 đến 1.000 tỷ đồng.');
      await ctx.store.transaction(async tx => {
        const fresh = await tx.get('employees', id);
        assert(fresh && fresh.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
        tx.update('employees', id, { baseSalary: salary });
        tx.set('audit_logs', ctx.store.id('audit_logs'), ctx.audit('EMPLOYEE_UPDATE', 'employee', id, `Cập nhật đơn giá giờ thành ${salary} VNĐ`));
      });
      ctx.ui.toast('Đã cập nhật đơn giá giờ.');
    },
  });
}

async function accountDialog(ctx, id) {
  const employee = await ctx.store.get('employees', id);
  assert(employee && employee.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
  assert(!(await ctx.store.list('users', { where: [['employeeId', '==', id]] })).rows.length, 'Nhân viên đã có tài khoản đăng nhập.');
  ctx.ui.dialog({ title: `Tạo tài khoản · ${employee.fullName}`, submitLabel: 'Tạo tài khoản',
    body: `${field('displayName', 'Tên hiển thị', employee.fullName, 'text', 'required')}${field('email', 'Email đăng nhập', employee.email || '', 'email', 'required autocomplete="off"')}${field('password', 'Mật khẩu ban đầu', '', 'password', 'required minlength="6" autocomplete="new-password"')}<p class="hint">Tài khoản được liên kết với hồ sơ này và có quyền nhân viên.</p>`,
    onSubmit: async values => {
      const email = clean(values.email);
      assert(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email), 'Email đăng nhập không hợp lệ.');
      assert(clean(values.displayName) && String(values.password).length >= 6, 'Nhập tên hiển thị và mật khẩu ít nhất 6 ký tự.');
      assert(!(await ctx.store.list('users', { where: [['employeeId', '==', id]] })).rows.length, 'Nhân viên đã có tài khoản. Hãy tải lại để kiểm tra.');
      const uid = await ctx.store.createEmployeeAccount(email, values.password, {
        email, displayName: clean(values.displayName), role: 'EMPLOYEE', active: true, employeeId: id, updatedAt: ctx.store.stamp(),
      });
      if (uid) {
        try { await ctx.store.add('audit_logs', ctx.audit('ACCOUNT_CREATE', 'user', typeof uid === 'string' ? uid : uid.uid, `Tạo tài khoản EMPLOYEE cho nhân viên ${id}`)); }
        catch { ctx.ui.toast('Tài khoản đã tạo thành công; chưa lưu được nhật ký tạo tài khoản.'); return; }
      }
      ctx.ui.toast('Đã tạo tài khoản nhân viên.');
    },
  });
}

export async function saveDepartment(ctx, id, rawName) {
  const name = clean(rawName);
  assert(name.length >= 2 && name.length <= 80 && !name.includes('/'), 'Tên phòng ban phải từ 2–80 ký tự và không chứa dấu /.');
  const departments = (await ctx.store.list('departments', {})).rows;
  assert(!departments.some(row => row.id !== id && normalize(clean(row.name)) === normalize(name)), 'Tên phòng ban đã tồn tại.');
  const previous = id ? departments.find(row => row.id === id) : null;
  assert(!id || previous, 'Không tìm thấy phòng ban.');
  const departmentId = id || ctx.store.id('departments');
  let candidates = [];
  if (previous && previous.name !== name) {
    const canonical = await ctx.store.list('employees', { where: [['departmentId', '==', id]] });
    const legacy = await ctx.store.list('employees', { where: [['department', '==', previous.name]] });
    candidates = [...new Map([...canonical.rows, ...legacy.rows].map(row => [row.id, row])).values()];
  }
  assert(candidates.length <= 498, 'Có quá nhiều nhân viên để đổi tên phòng ban trong một lần.');
  const auditId = ctx.store.id('audit_logs');
  await ctx.store.transaction(async tx => {
    const department = await tx.get('departments', departmentId);
    assert(previous ? department && department.name === previous.name : !department, 'Phòng ban đã thay đổi. Hãy tải lại danh sách.');
    const employees = [];
    for (const candidate of candidates) employees.push([candidate.id, await tx.get('employees', candidate.id)]);
    if (!previous) tx.set('departments', departmentId, { name, active: true, createdAt: ctx.store.stamp(), updatedAt: ctx.store.stamp() });
    else tx.update('departments', departmentId, { name, updatedAt: ctx.store.stamp() });
    for (const [employeeId, employee] of employees) {
      if (employee && (employee.departmentId === departmentId || (!employee.departmentId && employee.department === previous?.name))) {
        tx.update('employees', employeeId, { departmentId, department: name });
      }
    }
    tx.set('audit_logs', auditId, ctx.audit('DEPARTMENT_UPDATE', 'department', departmentId,
      previous ? `Đổi tên phòng ban ${previous.name} thành ${name}` : `Tạo phòng ban ${name}`));
  });
  ctx.ui.toast('Đã lưu phòng ban.');
}

function departmentDialog(ctx, department = null) {
  ctx.ui.dialog({ title: department ? 'Đổi tên phòng ban' : 'Thêm phòng ban', submitLabel: 'Lưu phòng ban',
    body: `${field('name', 'Tên phòng ban', department?.name || '', 'text', 'required minlength="2" maxlength="80"')}<p class="hint">Khi đổi tên, hồ sơ nhân viên thuộc phòng ban được cập nhật cùng lúc.</p>`,
    onSubmit: values => saveDepartment(ctx, department?.id, values.name),
  });
}

export async function retireOrDeleteFingerprint(ctx, employeeId, retire) {
  // Account creation bumps this token in the same transaction as linking users.
  // Reject a stale pre-query snapshot rather than leave a newly linked account active.
  const accountSnapshot = retire ? await ctx.store.get('employees', employeeId) : null;
  const mappings = (await ctx.store.list('fingerprintMappings', { where: [['employeeId', '==', employeeId]] })).rows;
  const accounts = retire ? (await ctx.store.list('users', { where: [['employeeId', '==', employeeId]] })).rows : [];
  const requestId = ctx.store.id('deviceCommands');
  const auditId = ctx.store.id('audit_logs');
  await ctx.store.transaction(async tx => {
    const employee = await tx.get('employees', employeeId);
    assert(employee, 'Không tìm thấy nhân viên.');
    if (retire) assert((employee.accountVersion || 0) === (accountSnapshot?.accountVersion || 0), 'Tài khoản liên kết vừa thay đổi. Hãy tải lại rồi chuyển nhân viên sang đã nghỉ.');
    const deviceId = employee.fingerprintDeviceId || 'GATE-01';
    const command = await tx.get('deviceCommands', deviceId);
    const mappedTemplate = mappings.map(row => row.templateId ?? Number(row.id)).find(value => Number.isInteger(value) && value >= 1 && value <= 127);
    const legacyTemplate = command?.employeeId === employeeId && command.type === 'ENROLL_FINGERPRINT' && command.status !== 'COMPLETED' ? command.templateId : null;
    const template = employee.fingerprintTemplateId ?? employee.pendingTemplateId ?? mappedTemplate ?? legacyTemplate;
    assert(template == null || (Number.isInteger(template) && template >= 1 && template <= 127), 'Vị trí vân tay không hợp lệ.');
    const mapping = template != null ? await tx.get('fingerprintMappings', String(template)) : null;
    if (template != null || command?.employeeId === employeeId) assert(!isCommandBusy(command), 'Thiết bị đang có lệnh chưa xử lý xong. Hãy chờ hoàn tất.');
    assert(!mapping || !mapping.employeeId || mapping.employeeId === employeeId, 'Mẫu vân tay đang liên kết với nhân viên khác.');
    const profiles = [];
    for (const account of accounts) profiles.push([account.id, await tx.get('users', account.id)]);
    assert(profiles.every(([, profile]) => !profile || (profile.role === 'EMPLOYEE' && profile.employeeId === employeeId)), 'Liên kết tài khoản đã thay đổi. Hãy tải lại trước khi thao tác.');
    assert(profiles.length + (template != null ? 4 : 2) <= 500, 'Có quá nhiều tài khoản liên kết để xử lý cùng lúc.');
    const employeeUpdate = retire ? { active: false,
      terminationDate: hasDate(employee.terminationDate || '') ? employee.terminationDate : today() } : {};
    if (template != null) {
      tx.set('fingerprintMappings', String(template), { employeeId, employeeName: employee.fullName, templateId: template, enabled: false }, { merge: true });
      Object.assign(employeeUpdate, { pendingTemplateId: template, fingerprintDeviceId: deviceId });
      tx.set('deviceCommands', deviceId, { requestId, type: 'DELETE_FINGERPRINT', deviceId,
        employeeId, employeeName: employee.fullName, templateId: template, status: 'REQUESTED', applied: false, createdAt: ctx.store.stamp() });
    }
    if (Object.keys(employeeUpdate).length) tx.update('employees', employeeId, employeeUpdate);
    for (const [uid, profile] of profiles) if (profile) tx.update('users', uid, { active: false, updatedAt: ctx.store.stamp() });
    tx.set('audit_logs', auditId, ctx.audit(retire ? 'EMPLOYEE_UPDATE' : 'FINGERPRINT_DELETE', 'employee', employeeId,
      'Yêu cầu xử lý qua deviceCommands', retire ? 'Chuyển nhân viên sang đã nghỉ' : 'Xóa mẫu vân tay trên thiết bị'));
  });
  ctx.ui.toast(retire ? 'Đã chuyển nhân viên sang đã nghỉ và khóa tài khoản liên kết.' : 'Đã gửi yêu cầu xóa vân tay. Vị trí chỉ được giải phóng khi thiết bị xác nhận.');
}

async function enrollmentDialog(ctx, employeeId) {
  const employee = await ctx.store.get('employees', employeeId);
  assert(employee && employee.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
  const devices = valuesOf(ctx, 'devices').filter(row => (row.capabilities || []).includes('fingerprint')
    || (row.capabilities || []).includes('enrollment') || (row.id === 'GATE-01' && !(row.capabilities || []).length));
  assert(devices.length, 'Chưa có thiết bị đăng ký vân tay. Hãy bật thiết bị để nhận tín hiệu trước.');
  ctx.ui.dialog({ title: `Đăng ký vân tay · ${employee.fullName}`, submitLabel: 'Gửi yêu cầu đăng ký',
    body: `${select('deviceId', 'Thiết bị', devices.map(row => ({ value: row.id, label: `${row.name || row.id} · ${connectionLabel(deviceConnectionState(row))}` })), employee.fingerprintDeviceId)}<p class="hint">Sau khi gửi, nhân viên đặt ngón tay lên cảm biến theo hướng dẫn trên thiết bị. Vị trí được cấp tự động và giữ đến khi đăng ký hoặc xóa hoàn tất.</p>`,
    onSubmit: async values => {
      const deviceId = clean(values.deviceId);
      assert(devices.some(row => row.id === deviceId) && !deviceId.includes('/'), 'Thiết bị không hợp lệ.');
      assert(!(await ctx.store.list('fingerprintMappings', { where: [['employeeId', '==', employeeId]] })).rows.length, 'Nhân viên còn ánh xạ vân tay cũ. Hãy xóa vân tay trước khi đăng ký lại.');
      const requestId = ctx.store.id('deviceCommands');
      const auditId = ctx.store.id('audit_logs');
      await ctx.store.transaction(async tx => {
        const fresh = await tx.get('employees', employeeId);
        const device = await tx.get('devices', deviceId);
        const command = await tx.get('deviceCommands', deviceId);
        assert(fresh && fresh.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
        assert(fresh.fingerprintTemplateId == null && fresh.pendingTemplateId == null, 'Hãy xóa mẫu vân tay cũ hoặc mẫu đang chờ trước khi đăng ký lại.');
        assert(device, 'Không tìm thấy thiết bị.');
        assert(!isCommandBusy(command), 'Thiết bị đang có lệnh chưa xử lý xong.');
        let template = null;
        for (let slot = 1; slot <= 127; slot++) {
          if (!(await tx.get('fingerprintMappings', String(slot)))) { template = slot; break; }
        }
        assert(template !== null, 'Đã hết 127 vị trí vân tay.');
        tx.update('employees', employeeId, { pendingTemplateId: template, fingerprintDeviceId: deviceId });
        tx.set('fingerprintMappings', String(template), { employeeId, employeeName: fresh.fullName, templateId: template, enabled: false });
        tx.set('deviceCommands', deviceId, { requestId, type: 'ENROLL_FINGERPRINT', deviceId, employeeId,
          employeeName: fresh.fullName, templateId: template, status: 'REQUESTED', createdAt: ctx.store.stamp(), applied: false });
        tx.set('audit_logs', auditId, ctx.audit('EMPLOYEE_UPDATE', 'employee', employeeId, `Yêu cầu đăng ký vân tay cho ${fresh.fullName}`));
      });
      ctx.ui.toast('Đã gửi yêu cầu. Nhân viên hãy thao tác trên cảm biến vân tay.');
    },
  });
}

async function editDeviceDialog(ctx, id) {
  const device = await ctx.store.get('devices', id);
  assert(device, 'Không tìm thấy thiết bị.');
  ctx.ui.dialog({ title: 'Tên và vị trí thiết bị', submitLabel: 'Lưu cấu hình',
    body: `${field('name', 'Tên thiết bị', device.name || device.id, 'text', 'required')}${field('location', 'Vị trí', device.location || '')}`,
    onSubmit: async values => {
      assert(clean(values.name), 'Tên thiết bị không được để trống.');
      await ctx.store.batch([{ type: 'update', collection: 'devices', id,
        data: { name: clean(values.name), location: clean(values.location) } },
      auditWrite(ctx, 'DEVICE_CONFIG_UPDATE', 'device', id, `Đổi tên/vị trí thành ${clean(values.name)} / ${clean(values.location)}`)]);
      ctx.ui.toast('Đã cập nhật cấu hình thiết bị.');
    },
  });
}

async function requestDeviceCommand(ctx, id, type) {
  assert(COMMANDS.some(row => row[0] === type), 'Loại lệnh không hợp lệ.');
  const requestId = ctx.store.id('deviceCommands');
  const auditId = ctx.store.id('audit_logs');
  await ctx.store.transaction(async tx => {
    const device = await tx.get('devices', id);
    const command = await tx.get('deviceCommands', id);
    assert(device && (!device.deviceId || device.deviceId === id), 'Thiết bị không khớp mã đăng ký.');
    assert(deviceConnectionState(device) === 'ONLINE', 'Thiết bị đang mất kết nối. Hãy đợi tín hiệu mới rồi thử lại.');
    assert(!isCommandBusy(command), 'Thiết bị đang xử lý một lệnh khác.');
    if (['OPEN_DOOR', 'CLOSE_DOOR'].includes(type)) assert((device.capabilities || []).includes('door'), 'Thiết bị không hỗ trợ điều khiển cửa.');
    tx.set('deviceCommands', id, { requestId, type, deviceId: id, status: 'REQUESTED', createdAt: ctx.store.stamp(), message: '', applied: true });
    tx.set('audit_logs', auditId, ctx.audit('DEVICE_COMMAND', 'deviceCommand', requestId, `Gửi lệnh ${type} cho thiết bị ${id}`));
  });
  ctx.ui.toast('Đã gửi lệnh đến thiết bị.');
}

export async function onAction(action, element, ctx) {
  if (!action.startsWith('org-')) return false;
  const id = element.dataset.id;
  if (action === 'org-employee-add') await employeeDialog(ctx);
  else if (action === 'org-employee-edit') {
    const employee = await ctx.store.get('employees', id);
    assert(employee && employee.active !== false, 'Không tìm thấy nhân viên đang làm việc.');
    await employeeDialog(ctx, employee);
  } else if (action === 'org-employee-detail') await employeeDetail(ctx, id);
  else if (action === 'org-employee-biometrics') {
    const employee = await ctx.store.get('employees', id);
    assert(employee && employee.active !== false, 'Nhân viên đã nghỉ hoặc không tồn tại.');
    await employeeDialog(ctx, employee);
  }
  else if (action === 'org-employee-salary') await salaryDialog(ctx, id);
  else if (action === 'org-account-create') await accountDialog(ctx, id);
  else if (action === 'org-employee-retire') ctx.ui.confirm('Chuyển nhân viên sang đã nghỉ?', 'Tài khoản liên kết sẽ bị khóa, vân tay được vô hiệu hóa và gửi yêu cầu xóa đến thiết bị. Hồ sơ, chấm công và phiếu lương được giữ để tra cứu.', () => retireOrDeleteFingerprint(ctx, id, true));
  else if (action === 'org-fingerprint-delete') ctx.ui.confirm('Xóa mẫu vân tay?', 'Mẫu được vô hiệu hóa ngay và thiết bị nhận yêu cầu xóa. Khi thiết bị xác nhận hoàn tất, có thể đăng ký lại vân tay.', () => retireOrDeleteFingerprint(ctx, id, false));
  else if (action === 'org-fingerprint-enroll') await enrollmentDialog(ctx, id);
  else if (action === 'org-department-add') departmentDialog(ctx);
  else if (action === 'org-department-edit') {
    const department = await ctx.store.get('departments', id);
    assert(department, 'Không tìm thấy phòng ban.');
    departmentDialog(ctx, department);
  } else if (action === 'org-department-toggle') {
    const department = await ctx.store.get('departments', id);
    assert(department, 'Không tìm thấy phòng ban.');
    const active = department.active === false;
    ctx.ui.confirm(active ? 'Kích hoạt phòng ban?' : 'Ngừng hoạt động phòng ban?', `${department.name}: ${active ? 'cho phép phân công nhân viên trở lại.' : 'giữ hồ sơ hiện có và ngừng dùng khi phân công mới.'}`, async () => {
      await ctx.store.batch([{ type: 'update', collection: 'departments', id, data: { active, updatedAt: ctx.store.stamp() } },
        auditWrite(ctx, 'DEPARTMENT_UPDATE', 'department', id, `${active ? 'Kích hoạt' : 'Ngừng hoạt động'} phòng ban ${department.name}`)]);
      ctx.ui.toast('Đã cập nhật trạng thái phòng ban.');
    });
  } else if (action === 'org-device-edit') await editDeviceDialog(ctx, id);
  else if (action === 'org-device-command') {
    const type = element.dataset.type;
    if (['OPEN_DOOR', 'RESTART_DEVICE'].includes(type)) ctx.ui.confirm(COMMANDS.find(row => row[0] === type)?.[1] || 'Gửi lệnh?', type === 'OPEN_DOOR' ? 'Gửi lệnh mở cửa đến thiết bị này?' : 'Thiết bị sẽ khởi động lại. Hãy thực hiện khi không có người đang quét vân tay.', () => requestDeviceCommand(ctx, id, type));
    else await requestDeviceCommand(ctx, id, type);
  } else return false;
  return true;
}
