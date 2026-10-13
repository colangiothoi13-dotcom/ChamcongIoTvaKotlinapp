import test from 'node:test';
import assert from 'node:assert/strict';
import { memoryStore } from '../src/demo.js';
import { nextEmployeeCode, isCommandBusy, deviceConnectionState, saveDepartment, retireOrDeleteFingerprint, onAction, render } from '../src/organization.js';

function context(initial) {
  const data = structuredClone(initial);
  const store = memoryStore(data);
  // Firebase rejects reads after writes within a transaction. Verify that the
  // module follows that ordering, including legacy mapping/account lookups.
  const transaction = store.transaction;
  store.transaction = callback => transaction(async tx => {
    let wrote = false;
    return callback({
      get: (...args) => { assert.equal(wrote, false, 'transaction read after a write'); return tx.get(...args); },
      set: (...args) => { wrote = true; return tx.set(...args); },
      update: (...args) => { wrote = true; return tx.update(...args); },
      remove: (...args) => { wrote = true; return tx.remove(...args); },
    });
  });
  const ctx = { data, store, filters: {}, user: { uid: 'admin', email: 'admin@example.com' },
    audit: (action, targetType, targetId, details, reason = '') => ({ actorId: 'admin', actorName: 'Admin', action, targetType, targetId, details, reason, createdAt: new Date() }),
    ui: { toast() {}, dialog(options) { ctx.dialog = options; }, confirm(title, body, callback) { ctx.confirm = callback; } },
  };
  return ctx;
}

test('new employee codes include manually assigned and retired employee numbers', () => {
  assert.equal(nextEmployeeCode(5, ['NV0003', 'NV0099', 'CUSTOM', 'NV0500']), 'NV0501');
  assert.equal(nextEmployeeCode(1000, ['NV0003']), 'NV1001');
  assert.throws(() => nextEmployeeCode(Number.MAX_SAFE_INTEGER, []));
});

test('completed fingerprint commands keep their reserved slot until applied', () => {
  assert.equal(isCommandBusy({ status: 'COMPLETED', applied: false }), true);
  assert.equal(isCommandBusy({ status: 'COMPLETED' }), true);
  assert.equal(isCommandBusy({ status: 'COMPLETED', applied: true }), false);
  assert.equal(isCommandBusy({ status: 'FAILED', applied: false }), false);
  assert.equal(isCommandBusy({ status: 'REQUESTED' }), true);
});

test('online state requires recent credible heartbeat even if status says ONLINE', () => {
  const now = 1700000000000;
  assert.equal(deviceConnectionState({ status: 'ONLINE' }, now), 'NO_HEARTBEAT');
  assert.equal(deviceConnectionState({ status: 'ONLINE', lastHeartbeat: new Date(now - 120001) }, now), 'STALE_HEARTBEAT');
  assert.equal(deviceConnectionState({ status: 'ONLINE', lastHeartbeat: new Date(now + 30001) }, now), 'CLOCK_SKEW');
  assert.equal(deviceConnectionState({ status: 'ONLINE', wifiStatus: 'OFFLINE', lastHeartbeat: new Date(now) }, now), 'REPORTED_OFFLINE');
  assert.equal(deviceConnectionState({ status: 'ONLINE', wifiStatus: 'ONLINE', lastHeartbeat: new Date(now) }, now), 'ONLINE');
});

test('department rename updates canonical and legacy members but skips transferred profiles', async () => {
  const ctx = context({ departments: [{ id: 'dep', name: 'Cũ', active: false, createdAt: new Date(0) }], employees: [
    { id: 'canonical', departmentId: 'dep', department: 'Tên bị lệch', active: false },
    { id: 'legacy', departmentId: '', department: 'Cũ' },
    { id: 'moved', departmentId: 'dep', department: 'Cũ' },
    { id: 'outsider', departmentId: 'other', department: 'Cũ' },
  ] });
  const transaction = ctx.store.transaction;
  ctx.store.transaction = callback => {
    Object.assign(ctx.data.employees.find(row => row.id === 'moved'), { departmentId: 'other', department: 'Mới khác' });
    return transaction(callback);
  };
  await saveDepartment(ctx, 'dep', 'Mới');
  assert.equal(ctx.data.departments[0].active, false);
  assert.equal(ctx.data.departments[0].createdAt.getTime(), 0);
  assert.equal(ctx.data.employees.find(row => row.id === 'canonical').department, 'Mới');
  assert.equal(ctx.data.employees.find(row => row.id === 'legacy').departmentId, 'dep');
  assert.equal(ctx.data.employees.find(row => row.id === 'moved').department, 'Mới khác');
  assert.equal(ctx.data.employees.find(row => row.id === 'outsider').departmentId, 'other');
  assert.equal(ctx.data.audit_logs[0].action, 'DEPARTMENT_UPDATE');
});

test('duplicate department names are rejected before mutating employee snapshots', async () => {
  const ctx = context({ departments: [{ id: 'a', name: 'Nhân sự' }, { id: 'b', name: 'Kỹ thuật' }], employees: [] });
  await assert.rejects(saveDepartment(ctx, 'b', ' NHÂN SỰ '), /đã tồn tại/);
  assert.equal(ctx.data.departments[1].name, 'Kỹ thuật');
  assert.equal(ctx.data.audit_logs, undefined);
});

test('retirement disables accounts and legacy mapping while leaving sensor deletion pending', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, code: 'NV0001', baseSalary: 50000 }],
    fingerprintMappings: [{ id: '9', employeeId: 'emp', employeeName: 'Nhân viên', templateId: 9, enabled: true }],
    users: [{ id: 'uid', uid: 'uid', employeeId: 'emp', role: 'EMPLOYEE', active: true, email: 'employee@example.com' }], deviceCommands: [] });
  await retireOrDeleteFingerprint(ctx, 'emp', true);
  assert.equal(ctx.data.employees[0].active, false);
  assert.equal(ctx.data.employees[0].baseSalary, 50000);
  assert.equal(ctx.data.employees[0].pendingTemplateId, 9);
  assert.equal(ctx.data.employees[0].fingerprintDeviceId, 'GATE-01');
  assert.equal(ctx.data.users[0].active, false);
  assert.equal(ctx.data.fingerprintMappings[0].enabled, false);
  assert.equal(ctx.data.deviceCommands[0].type, 'DELETE_FINGERPRINT');
  assert.equal(ctx.data.deviceCommands[0].status, 'REQUESTED');
  assert.equal(ctx.data.deviceCommands[0].applied, false);
  assert.match(ctx.data.employees[0].terminationDate, /^\d{4}-\d{2}-\d{2}$/);
});

test('unapplied completed command prevents retirement from partially locking accounts', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, pendingTemplateId: 1 }],
    fingerprintMappings: [{ id: '1', employeeId: 'emp', templateId: 1, enabled: false }],
    users: [{ id: 'uid', employeeId: 'emp', role: 'EMPLOYEE', active: true }],
    deviceCommands: [{ id: 'GATE-01', employeeId: 'emp', status: 'COMPLETED', applied: false }] });
  await assert.rejects(retireOrDeleteFingerprint(ctx, 'emp', true), /chưa xử lý xong/);
  assert.equal(ctx.data.employees[0].active, true);
  assert.equal(ctx.data.users[0].active, true);
  assert.equal(ctx.data.fingerprintMappings[0].enabled, false);
  assert.equal(ctx.data.audit_logs, undefined);
});

test('retirement aborts if account linking changed after preliminary account lookup', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, accountVersion: 1 }], users: [], fingerprintMappings: [], deviceCommands: [] });
  const transaction = ctx.store.transaction;
  ctx.store.transaction = callback => {
    ctx.data.employees[0].accountVersion = 2;
    ctx.data.users.push({ id: 'new', employeeId: 'emp', role: 'EMPLOYEE', active: true });
    return transaction(callback);
  };
  await assert.rejects(retireOrDeleteFingerprint(ctx, 'emp', true), /vừa thay đổi/);
  assert.equal(ctx.data.employees[0].active, true);
  assert.equal(ctx.data.users[0].active, true);
});

test('employee editing preserves salary, fingerprint state and legacy extra fields', async () => {
  const ctx = context({ employees: [{ id: 'emp', code: 'NV0001', fullName: 'Cũ', active: true, baseSalary: 75000,
    fingerprintTemplateId: 7, pendingTemplateId: null, fingerprintDeviceId: 'GATE-02', legacyMetadata: 'keep' }], departments: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  await ctx.dialog.onSubmit({ fullName: 'Mới', email: '', phone: '', address: '', hireDate: '2026-02-28', position: 'Kỹ sư', departmentId: '' });
  assert.equal(ctx.data.employees[0].fullName, 'Mới');
  assert.equal(ctx.data.employees[0].baseSalary, 75000);
  assert.equal(ctx.data.employees[0].fingerprintTemplateId, 7);
  assert.equal(ctx.data.employees[0].fingerprintDeviceId, 'GATE-02');
  assert.equal(ctx.data.employees[0].legacyMetadata, 'keep');
  await assert.rejects(ctx.dialog.onSubmit({ fullName: 'Mới', hireDate: '2026-02-30' }), /không hợp lệ/);
});

test('an unrelated edit retains a legacy department absent from the department registry', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, department: 'Bộ phận cũ', departmentId: '' }], departments: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  assert.match(ctx.dialog.body, /value="__preserve_legacy__" selected/);
  await ctx.dialog.onSubmit({ fullName: 'Nhân viên', phone: '0901111222', departmentId: '__preserve_legacy__' });
  assert.equal(ctx.data.employees[0].department, 'Bộ phận cũ');
  assert.equal(ctx.data.employees[0].departmentId, '');
  // An explicit change to the empty option still removes membership.
  await ctx.dialog.onSubmit({ fullName: 'Nhân viên', departmentId: '' });
  assert.equal(ctx.data.employees[0].department, '');
});

test('editing a legacy member resolves the canonical inactive department without removing membership', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, department: 'Bộ phận cũ' }],
    departments: [{ id: 'old', name: 'Bộ phận cũ', active: false }] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  assert.match(ctx.dialog.body, /value="old" selected/);
  await ctx.dialog.onSubmit({ fullName: 'Nhân viên', departmentId: 'old' });
  assert.equal(ctx.data.employees[0].department, 'Bộ phận cũ');
  assert.equal(ctx.data.employees[0].departmentId, 'old');
});

test('enrollment skips occupied sensor slots and creates disabled mapping with pending command', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true, pendingTemplateId: null, fingerprintTemplateId: null }],
    devices: [{ id: 'GATE-01', capabilities: ['fingerprint', 'enrollment'] }],
    deviceCommands: [], fingerprintMappings: [{ id: '1', employeeId: 'other', templateId: 1, enabled: false }] });
  await onAction('org-fingerprint-enroll', { dataset: { id: 'emp' } }, ctx);
  await ctx.dialog.onSubmit({ deviceId: 'GATE-01' });
  assert.equal(ctx.data.employees[0].pendingTemplateId, 2);
  assert.equal(ctx.data.fingerprintMappings.find(row => row.id === '2').enabled, false);
  assert.equal(ctx.data.deviceCommands[0].type, 'ENROLL_FINGERPRINT');
  assert.equal(ctx.data.deviceCommands[0].applied, false);
});

test('rendered employee data is escaped before entering HTML', () => {
  const ctx = context({ employees: [{ id: 'x" onclick="alert(1)', fullName: '<script>alert(1)</script>', email: '<img onerror=alert(1)>', active: true }] });
  const html = render('employees', ctx);
  assert.ok(!html.includes('<script>alert(1)</script>'));
  assert.ok(!html.includes('<img onerror=alert(1)>'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('data-id="x&quot; onclick=&quot;alert(1)"'));
});

test('employee list exposes compact management controls while preserving retirement restrictions', async () => {
  const ctx = context({ employees: [
    { id: 'active', fullName: 'Đang làm', active: true, baseSalary: 50000 },
    { id: 'retired', fullName: 'Đã nghỉ', active: false, baseSalary: 60000 },
  ], departments: [], users: [], attendance: [] });
  const html = render('employees', ctx);
  assert.match(html, /class="btn primary" data-action="org-employee-add"/);
  assert.match(render('departments', ctx), /class="btn primary" data-action="org-department-add"/);
  const controls = [...html.matchAll(/<button\b[^>]*data-action="([^"]+)"[^>]*data-id="([^"]+)"[^>]*>([^<]*)<\/button>/g)]
    .map(([, action, id, label]) => ({ action, id, label }));
  assert.deepEqual(controls.filter(row => row.id === 'active').map(row => row.label), ['Sửa hồ sơ', 'Đơn giá giờ']);
  assert.deepEqual(controls.filter(row => row.id === 'retired').map(row => row.action), []);
  assert.match(html, /class="employee-name-button"[^>]*data-action="org-employee-detail"[^>]*data-id="active"/);
  assert.match(html, /class="employee-name-button"[^>]*data-action="org-employee-detail"[^>]*data-id="retired"/);
  assert.doesNotMatch(html, /data-action="(?:face-enroll|face-delete|org-account-create|org-employee-biometrics)"/);
  assert.doesNotMatch(html, /Tài khoản \/ vân tay/);

  const salaryControl = controls.find(row => row.id === 'active' && row.label === 'Đơn giá giờ');
  assert.equal(await onAction(salaryControl.action, { dataset: { id: salaryControl.id } }, ctx), true);
  await ctx.dialog.onSubmit({ salary: '75000' });
  assert.equal(ctx.data.employees[0].baseSalary, 75000);
  assert.equal(ctx.data.audit_logs[0].action, 'EMPLOYEE_UPDATE');
  assert.equal(ctx.data.employees[1].baseSalary, 60000);
  await assert.rejects(onAction('org-employee-salary', { dataset: { id: 'retired' } }, ctx), /đã nghỉ/);
  await assert.rejects(onAction('org-employee-edit', { dataset: { id: 'retired' } }, ctx), /đang làm việc/);
});

test('account and fingerprint entry exposes real setup actions only for active employees', async () => {
  const ctx = context({ employees: [
    { id: 'active', fullName: 'Đang làm', email: 'staff@example.com', active: true, fingerprintTemplateId: null, pendingTemplateId: null },
    { id: 'retired', fullName: 'Đã nghỉ', active: false, fingerprintTemplateId: 2 },
  ], departments: [], users: [], attendance: [] });
  await onAction('org-employee-detail', { dataset: { id: 'active' } }, ctx);
  assert.equal(ctx.dialog.submitLabel, null);
  assert.match(ctx.dialog.body, /data-action="org-account-create"/);
  assert.match(ctx.dialog.body, /data-action="org-fingerprint-enroll"/);
  assert.match(ctx.dialog.body, /data-action="org-employee-edit"[^>]*data-id="active"/);
  assert.match(ctx.dialog.body, /<h3 id="employee-face-title">Khuôn mặt<\/h3>/);
  assert.match(ctx.dialog.body, /data-action="face-enroll"[^>]*data-id="active"/);
  await onAction('org-account-create', { dataset: { id: 'active' } }, ctx);
  await ctx.dialog.onSubmit({ displayName: 'Nhân viên mới', email: 'staff@example.com', password: 'initial-password' });
  assert.equal(ctx.data.users[0].employeeId, 'active');
  assert.equal(ctx.data.users[0].role, 'EMPLOYEE');
  assert.equal(ctx.user.uid, 'admin');

  await onAction('org-employee-detail', { dataset: { id: 'active' } }, ctx);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"/);
  await onAction('org-employee-detail', { dataset: { id: 'retired' } }, ctx);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-(?:employee-edit|employee-salary|fingerprint-enroll|employee-retire)"/);
  assert.match(ctx.dialog.body, /data-action="org-account-create"[^>]*data-id="retired"[^>]*disabled/);
  assert.match(ctx.dialog.body, /data-action="org-fingerprint-delete"/);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-employee-biometrics"/);
  assert.match(ctx.dialog.body, /data-action="face-enroll"[^>]*data-id="retired"[^>]*disabled/);
});

test('create editor shows all employee sections and enables its committed identity after save without automatic enrollment', async () => {
  const ctx = context({ employees: [], departments: [{ id: 'dept', name: 'Kỹ thuật', active: true }],
    deviceCommands: [], fingerprintMappings: [], faceEnrollmentCommands: [], faceProfiles: [], faceStations: [] });
  // A real subscription may still be showing the old collection after commit.
  // The panel must use the committed snapshot without inserting a guessed row.
  ctx.data = structuredClone(ctx.data);
  await onAction('org-employee-add', { dataset: {} }, ctx);
  const addDialog = ctx.dialog;
  assert.equal(addDialog.submitLabel, 'Lưu hồ sơ & tiếp tục');
  for (const title of ['Hồ sơ', 'Tài khoản', 'Vân tay', 'Khuôn mặt']) assert.match(addDialog.body, new RegExp(`<h3[^>]*>${title}</h3>`));
  for (const action of ['org-account-create', 'org-fingerprint-enroll', 'face-enroll']) assert.match(addDialog.body, new RegExp(`data-action="${action}"[^>]*disabled`));
  assert.match(addDialog.body, /Lưu hồ sơ trước/);
  const saved = await addDialog.onSubmit({ fullName: 'Trần An', email: '', phone: '0901234567', address: '', position: '', hireDate: '2026-10-12', departmentId: 'dept' });
  const committed = await ctx.store.get('employees', saved.id);
  assert.deepEqual(saved, committed);
  assert.equal(saved.fullName, 'Trần An');assert.equal(saved.code, 'NV0001');assert.equal(saved.department, 'Kỹ thuật');assert.equal(saved.active, true);
  assert.equal((await ctx.store.get('system', 'employeeCounter')).lastNumber, 1);
  assert.equal((await ctx.store.list('audit_logs')).rows[0].action, 'EMPLOYEE_CREATE');
  assert.equal(ctx.data.employees.length, 0, 'do not mutate the loaded subscription collection');
  assert.equal(ctx.dialog.title, 'Nhân viên · Trần An');assert.equal(ctx.dialog.submitLabel, 'Lưu hồ sơ');
  assert.match(ctx.dialog.body, /name="fullName"[^>]*value="Trần An"/);
  assert.match(ctx.dialog.body, new RegExp(`data-employee-editor="${saved.id}"`));
  assert.match(ctx.dialog.body, new RegExp(`data-action="org-fingerprint-enroll"[^>]*data-id="${saved.id}"`));
  assert.match(ctx.dialog.body, new RegExp(`data-action="face-enroll"[^>]*data-id="${saved.id}"`));
  assert.match(ctx.dialog.body, /đăng ký lần lượt/);
  assert.match(ctx.dialog.body, new RegExp(`data-action="org-account-create"[^>]*data-id="${saved.id}"`));
  for (const name of ['deviceCommands', 'fingerprintMappings', 'faceEnrollmentCommands', 'faceProfiles']) assert.equal((await ctx.store.list(name)).rows.length, 0, `${name} must not be created on save`);
});

test('an unsuccessful profile commit retains unsaved editor and disabled registration controls', async () => {
  const ctx = context({ employees: [], departments: [] });
  await onAction('org-employee-add', { dataset: {} }, ctx);const addDialog = ctx.dialog;
  ctx.store.batch = async () => { throw new Error('commit failed'); };
  await assert.rejects(addDialog.onSubmit({ fullName: 'Chưa lưu', departmentId: '' }), /commit failed/);
  assert.equal(ctx.dialog, addDialog);assert.equal(ctx.data.employees.length, 0);
  assert.equal((await ctx.store.list('audit_logs')).rows.length, 0);
  assert.equal(await ctx.store.get('system', 'employeeCounter'), null);
});

test('editing a profile stays in the unified editor after save without creating registration commands', async () => {
  const ctx = context({ employees: [{ id: 'emp', code: 'NV0007', fullName: 'Tên cũ', active: true, fingerprintTemplateId: 7, pendingTemplateId: null, fingerprintDeviceId: 'GATE-01', baseSalary: 45000 }], departments: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);const editDialog = ctx.dialog;
  assert.equal(editDialog.submitLabel, 'Lưu hồ sơ');assert.match(editDialog.body, /employee-editor/);
  const saved = await editDialog.onSubmit({ fullName: 'Tên mới', departmentId: '' });
  assert.notEqual(ctx.dialog, editDialog);assert.equal(ctx.dialog.title, 'Nhân viên · Tên mới');assert.match(ctx.dialog.body, /name="fullName"[^>]*value="Tên mới"/);assert.equal(saved.id, 'emp');assert.equal(saved.fullName, 'Tên mới');assert.equal(saved.fingerprintTemplateId, 7);assert.equal(saved.baseSalary, 45000);
  assert.equal((await ctx.store.list('deviceCommands')).rows.length, 0);assert.equal((await ctx.store.list('faceEnrollmentCommands')).rows.length, 0);
});

test('legacy registration action opens unified editor and protects pending and retired fingerprint state', async () => {
  const ctx = context({ employees: [{ id: 'emp', code: 'NV0001', fullName: 'Đúng người', active: true, fingerprintTemplateId: null, pendingTemplateId: null }],
    deviceCommands: [], faceProfiles: [], faceStations: [] });
  await onAction('org-employee-biometrics', { dataset: { id: 'emp' } }, ctx);
  assert.match(ctx.dialog.body, /Đúng người/);assert.match(ctx.dialog.body, /name="fullName"/);assert.equal(ctx.dialog.submitLabel, 'Lưu hồ sơ');assert.match(ctx.dialog.body, /data-action="org-fingerprint-enroll"[^>]*data-id="emp"/);assert.match(ctx.dialog.body, /data-action="face-enroll"[^>]*data-id="emp"/);
  ctx.data.deviceCommands = [{ id: 'GATE-01', employeeId: 'emp', status: 'COMPLETED', applied: false }];
  await onAction('org-employee-biometrics', { dataset: { id: 'emp' } }, ctx);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-fingerprint-enroll"/);assert.match(ctx.dialog.body, /Đang chờ thiết bị/);
  ctx.data.deviceCommands = [];ctx.data.employees[0].pendingTemplateId = 3;
  await onAction('org-employee-biometrics', { dataset: { id: 'emp' } }, ctx);assert.doesNotMatch(ctx.dialog.body, /data-action="org-fingerprint-enroll"/);
  ctx.data.employees[0].pendingTemplateId = null;ctx.data.employees[0].fingerprintTemplateId = 3;
  await onAction('org-employee-biometrics', { dataset: { id: 'emp' } }, ctx);assert.match(ctx.dialog.body, /data-action="org-fingerprint-delete"/);assert.doesNotMatch(ctx.dialog.body, /data-action="org-fingerprint-enroll"/);
  ctx.data.employees[0].active = false;const previous = ctx.dialog;
  await assert.rejects(onAction('org-employee-biometrics', { dataset: { id: 'emp' } }, ctx), /đã nghỉ/);assert.equal(ctx.dialog, previous);
});

test('unified editor blocks tool actions while profile drafts differ and preserves prior disabled reasons', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Đúng người', email: '', phone: '', departmentId: '', address: '', position: '', hireDate: '2026-10-12', active: true }], departments: [], users: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  const dialog = ctx.dialog, values = { fullName: 'Đúng người', email: '', phone: '', departmentId: '', address: '', position: '', hireDate: '2026-10-12' };
  const tools = [{ disabled: false }, { disabled: true }], note = { textContent: '' };
  const form = { dataset: {}, elements: { namedItem: name => ({ value: values[name] }) }, querySelectorAll: () => tools, querySelector: () => note };
  values.fullName = 'Tên đang sửa';dialog.onInput({ target: { name: 'fullName' } }, form);
  assert.equal(form.dataset.employeeDraft, 'dirty');assert.ok(tools.every(control => control.disabled));assert.match(note.textContent, /chưa lưu/);
  values.fullName = 'Đúng người';dialog.onChange({ target: { name: 'fullName' } }, form);
  assert.equal(form.dataset.employeeDraft, 'clean');assert.deepEqual(tools.map(control => control.disabled), [false, true]);
  dialog.onInput({ target: { name: 'unrelated' } }, form);assert.equal(form.dataset.employeeDraft, 'clean');
  assert.equal((await ctx.store.list('deviceCommands')).rows.length, 0);assert.equal((await ctx.store.list('faceEnrollmentCommands')).rows.length, 0);
});

test('saved editor links account, fingerprint and face controls to one identity and preserves account status', async () => {
  const ctx = context({ employees: [{ id: 'emp', code: 'NV0001', fullName: 'Đúng người', active: true, fingerprintTemplateId: null, pendingTemplateId: null }], departments: [],
    users: [{ id: 'uid', employeeId: 'emp', displayName: 'Tên đăng nhập', email: 'login@example.com', active: true }],
    faceProfiles: [{ id: 'emp', employeeId: 'emp', enabled: true, version: 2 }], faceStations: [], faceEnrollmentCommands: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  for (const title of ['Hồ sơ', 'Tài khoản', 'Vân tay', 'Khuôn mặt']) assert.match(ctx.dialog.body, new RegExp(`<h3[^>]*>${title}</h3>`));
  assert.match(ctx.dialog.body, /login@example.com/);assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"/);
  assert.match(ctx.dialog.body, /data-action="org-fingerprint-enroll"[^>]*data-id="emp"/);
  assert.match(ctx.dialog.body, /data-action="face-enroll"[^>]*data-id="emp"/);assert.match(ctx.dialog.body, /Đăng ký lại khuôn mặt/);
  assert.match(ctx.dialog.body, /data-action="face-delete"[^>]*data-id="emp"/);
});

test('mount captures permanent tool states before pending actions temporarily disable a button', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Đúng người', email: '', phone: '', departmentId: '', address: '', position: '', hireDate: '2026-10-12', active: true }], departments: [], users: [] });
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  const dialog = ctx.dialog, values = { fullName: 'Đúng người', email: '', phone: '', departmentId: '', address: '', position: '', hireDate: '2026-10-12' };
  const tools = [{ disabled: false }, { disabled: true }], note = { textContent: '' };
  const form = { dataset: {}, elements: { namedItem: name => ({ value: values[name] }) }, querySelectorAll: () => tools, querySelector: () => note };
  dialog.onOpen(form);
  tools[0].disabled = true; // An asynchronous tool action marks this pending.
  values.fullName = 'Tên đang sửa';dialog.onInput({ target: { name: 'fullName' } }, form);assert.ok(tools.every(control => control.disabled));
  values.fullName = 'Đúng người';dialog.onChange({ target: { name: 'fullName' } }, form);
  assert.deepEqual(tools.map(control => control.disabled), [false, true]);assert.equal(form.dataset.employeeDraft, 'clean');
});

test('employee editor reads linked accounts from the server despite an empty subscription snapshot', async () => {
  for (const active of [true, false]) {
    const ctx = context({ employees: [{ id: 'emp', code: 'NV0001', fullName: 'Nhân viên', email: 'contact@example.com', active: true }], departments: [],
      users: [{ id: 'login', employeeId: 'emp', displayName: 'Tên đăng nhập', email: 'login@example.com', active }] });
    // Keep the server store separate from the outdated collection used by the page.
    ctx.data = structuredClone(ctx.data);ctx.data.users = [];ctx.loaded = { users: true };
    const list = ctx.store.list, lookups = [];
    ctx.store.list = async (name, options) => { if (name === 'users') lookups.push(options);return list(name, options); };
    for (const action of ['org-employee-edit', 'org-employee-biometrics']) {
      await onAction(action, { dataset: { id: 'emp' } }, ctx);
      assert.match(ctx.dialog.body, /login@example.com/);
      assert.match(ctx.dialog.body, active ? /Đang hoạt động/ : /Đã khóa/);
      assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"/);
      assert.doesNotMatch(ctx.dialog.body, /Chưa có tài khoản đăng nhập/);
    }
    const saved = await ctx.dialog.onSubmit({ fullName: 'Tên đã lưu', email: 'new-contact@example.com', departmentId: '' });
    assert.equal(saved.email, 'new-contact@example.com');
    assert.match(ctx.dialog.body, /name="email"[^>]*value="new-contact@example.com"/);
    assert.match(ctx.dialog.body, /login@example.com/);
    assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"/);
    assert.equal(ctx.data.users.length, 0, 'account lookup must not mutate the subscription snapshot');
    assert.equal(lookups.length, 3, 'lookup is repeated when reopening the committed editor');
    for (const options of lookups) assert.deepEqual(options.where, [['employeeId', '==', 'emp']]);
  }
});

test('verified empty employee account lookup offers creation without matching contact emails or stale page users', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', email: 'shared@example.com', active: true }], departments: [],
    users: [{ id: 'other-login', employeeId: 'other', displayName: 'Nhân viên khác', email: 'shared@example.com', active: true }] });
  ctx.data = structuredClone(ctx.data);
  ctx.data.users.push({ id: 'stale-login', employeeId: 'emp', email: 'old@example.com', active: true });
  ctx.loaded = { users: false };ctx.errors = { users: 'Global subscription failed' };
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  assert.match(ctx.dialog.body, /Chưa có tài khoản đăng nhập/);
  assert.match(ctx.dialog.body, /data-action="org-account-create"[^>]*data-id="emp"/);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"[^>]*disabled/);
  assert.doesNotMatch(ctx.dialog.body, /Nhân viên khác|old@example.com/);
});

test('editor waits for its account lookup before presenting a create account action', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true }], departments: [],
    users: [{ id: 'login', employeeId: 'emp', email: 'linked@example.com', active: true }] });
  ctx.data = structuredClone(ctx.data);ctx.data.users = [];
  let release, requested;
  const pending = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { requested = resolve; });
  const list = ctx.store.list;
  ctx.store.list = async (name, options) => { if (name === 'users') { requested();await pending; }return list(name, options); };
  const opening = onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  await Promise.race([started, opening.then(() => assert.fail('editor opened without checking server accounts'))]);
  assert.equal(ctx.dialog, undefined, 'no guessed empty account section while the lookup is pending');
  release();await opening;
  assert.match(ctx.dialog.body, /linked@example.com/);
  assert.doesNotMatch(ctx.dialog.body, /data-action="org-account-create"/);
});

test('failed account lookup keeps employee editing available without claiming the account is absent', async () => {
  const ctx = context({ employees: [{ id: 'emp', fullName: 'Nhân viên', active: true }], departments: [], users: [] });
  const list = ctx.store.list;
  ctx.store.list = async (name, options) => { if (name === 'users') throw new Error('account query denied');return list(name, options); };
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  assert.equal(ctx.dialog.submitLabel, 'Lưu hồ sơ');
  assert.match(ctx.dialog.body, /name="fullName"[^>]*value="Nhân viên"/);
  assert.match(ctx.dialog.body, /Chưa tải được tài khoản liên kết\. Vui lòng mở lại hồ sơ để thử lại\./);
  assert.doesNotMatch(ctx.dialog.body, /Chưa có tài khoản đăng nhập|data-action="org-account-create"/);
});

test('account lookup failure after a successful save shows the committed profile and blocks account creation', async () => {
  const ctx = context({ employees: [{ id: 'emp', code: 'NV0001', fullName: 'Tên cũ', active: true, baseSalary: 45000 }], departments: [], users: [] });
  ctx.data = structuredClone(ctx.data);
  await onAction('org-employee-edit', { dataset: { id: 'emp' } }, ctx);
  const editing = ctx.dialog, list = ctx.store.list;
  ctx.store.list = async (name, options) => { if (name === 'users') throw new Error('account connection failed');return list(name, options); };
  const saved = await editing.onSubmit({ fullName: 'Tên đã lưu', email: 'saved@example.com', departmentId: '' });
  assert.equal(saved.fullName, 'Tên đã lưu');
  assert.equal((await ctx.store.get('employees', 'emp')).fullName, 'Tên đã lưu');
  assert.equal(ctx.data.employees[0].fullName, 'Tên cũ', 'saving must not fabricate an updated subscription snapshot');
  assert.notEqual(ctx.dialog, editing);
  assert.equal(ctx.dialog.title, 'Nhân viên · Tên đã lưu');
  assert.match(ctx.dialog.body, /name="fullName"[^>]*value="Tên đã lưu"/);
  assert.match(ctx.dialog.body, /Chưa tải được tài khoản liên kết\. Vui lòng mở lại hồ sơ để thử lại\./);
  assert.doesNotMatch(ctx.dialog.body, /Chưa có tài khoản đăng nhập|data-action="org-account-create"/);
  assert.equal((await ctx.store.list('audit_logs')).rows[0].action, 'EMPLOYEE_UPDATE');
  assert.equal((await ctx.store.list('deviceCommands')).rows.length, 0);
});
