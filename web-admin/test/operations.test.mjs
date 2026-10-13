import test from 'node:test';
import assert from 'node:assert/strict';
import { saveWorkItem, reviewWorkItem, assignSchedules, copyPreviousWeek, reviewRequest, mergeAssignment, validateShift, filteredWorkItems, render } from '../src/operations.js';

function context(seed = {}) {
  const documents = new Map(Object.entries(seed));
  const writes = [];
  let sequence = 0;
  const read = (collection, id) => documents.has(`${collection}/${id}`) ? { ...documents.get(`${collection}/${id}`), id } : null;
  const ctx = { user: { uid: 'admin', displayName: 'Admin' }, filters: {}, data: {}, audit: (action, targetType, targetId, details, reason = '') => ({ actorId: 'admin', actorName: 'Admin', action, targetType, targetId, details, reason }) };
  ctx.store = {
    id: () => `random-${++sequence}`,
    stamp: () => 'SERVER_TIMESTAMP',
    timestamp: date => ({ seconds: Math.floor(date.getTime() / 1000) }),
    get: async (collection, id) => read(collection, id),
    list: async (collection, query = {}) => ({ rows: [...documents.entries()].filter(([key]) => key.startsWith(`${collection}/`) && key.slice(collection.length + 1).indexOf('/') < 0).map(([key, value]) => ({ ...value, id: key.slice(collection.length + 1) })).filter(row => (query.where || []).every(([field, op, value]) => op === '>=' ? row[field] >= value : op === '<=' ? row[field] <= value : row[field] === value)), hasMore: false }),
    transaction: async callback => {
      const pending = []; let written = false;
      const tx = {
        get: async (collection, id) => { assert.equal(written, false, 'Firestore requires all reads before writes'); return read(collection, id); },
        set: (collection, id, data) => { written = true; pending.push({ type: 'set', collection, id, data }); },
        update: (collection, id, data) => { written = true; pending.push({ type: 'update', collection, id, data }); },
        remove: (collection, id) => { written = true; pending.push({ type: 'delete', collection, id }); }
      };
      const result = await callback(tx);
      for (const write of pending) { const key = `${write.collection}/${write.id}`; if (write.type === 'delete') documents.delete(key); else documents.set(key, write.type === 'update' ? { ...documents.get(key), ...write.data } : write.data); }
      writes.push(...pending);
      return result;
    }
  };
  documents.set('users/admin', { role: 'ADMIN', active: true, displayName: 'Admin' });
  ctx.documents = documents; ctx.writes = writes;
  return ctx;
}

const morning = { id: 'morning', name: 'Ca sáng', category: 'MORNING', startTime: '08:00', endTime: '12:00', effectiveFrom: '1970-01-01', active: true, allowEarlyMinutes: 120, lateGraceMinutes: 10, earlyLeaveAllowedMinutes: 0, missingCheckOutGraceMinutes: 30 };
const afternoon = { ...morning, id: 'afternoon', name: 'Ca chiều', category: 'EVENING', startTime: '13:00', endTime: '17:00' };
const employee = { fullName: 'Nguyễn An', department: 'Kỹ thuật', departmentId: 'dep', active: true };
const draft = { title: 'Kiểm tra', description: 'Kiểm tra cảm biến', requiredResult: 'Nộp kết quả', assigneeId: 'employee', priority: 'NORMAL', startAt: { seconds: 10 }, deadline: { seconds: 20 }, relatedScheduleId: null, relatedShiftId: null, relatedScheduleDate: null };

test('work priority and due filters match Android Vietnamese day and 24-hour boundaries', () => {
  const now = Date.parse('2026-10-11T22:00:00Z'); // Monday 05:00 in Vietnam.
  const ctx = context();
  ctx.data.employees = [{ id: 'employee', ...employee }];
  const item = (id, offset, extra = {}) => ({ id, ...draft, assigneeName: employee.fullName, status: 'ASSIGNED', deadline: new Date(now + offset), ...extra });
  ctx.data.workItems = [
    item('overdue-yesterday', -6 * 3600000, { priority: 'HIGH' }),
    item('today-overdue', -3600000), item('now', 0, { priority: 'HIGH' }),
    item('today-future', 12 * 3600000), item('tomorrow-boundary', 24 * 3600000, { priority: 'HIGH' }),
    item('tomorrow-later', 24 * 3600000 + 1), item('completed', -3600000, { status: 'COMPLETED', priority: 'HIGH' }),
  ];
  ctx.filters.workDue = 'TODAY';
  assert.deepEqual(filteredWorkItems(ctx, now).map(row => row.id), ['today-overdue', 'now', 'today-future']);
  ctx.filters.workDue = 'SOON';
  assert.deepEqual(filteredWorkItems(ctx, now).map(row => row.id), ['now', 'today-future', 'tomorrow-boundary']);
  ctx.filters.workDue = 'OVERDUE';
  assert.deepEqual(filteredWorkItems(ctx, now).map(row => row.id), ['overdue-yesterday', 'today-overdue']);
  ctx.filters.workDue = 'SOON'; ctx.filters.workPriority = 'HIGH';
  assert.deepEqual(filteredWorkItems(ctx, now).map(row => row.id), ['now', 'tomorrow-boundary']);
  ctx.filters.workDue = ''; ctx.filters.workPriority = '';
  assert.equal(filteredWorkItems(ctx, now).at(-1).id, 'completed');
  assert.match(render('workItems', ctx), /data-filter="workPriority"/);
  assert.match(render('workItems', ctx), /data-filter="workDue"/);
  assert.equal(ctx.writes.length, 0);
});

test('work filters combine search, department, status and priority without including invalid due dates', () => {
  const ctx = context();
  ctx.data.employees = [{ id: 'employee', ...employee, code: 'NV0010' }];
  ctx.data.departments = [{ id: 'dep', name: 'Kỹ thuật' }];
  ctx.data.workItems = [{ id: 'matching', ...draft, assigneeName: employee.fullName, status: 'PENDING_REVIEW', priority: 'URGENT', deadline: new Date('2026-10-12T02:00:00Z') },
    { id: 'invalid', ...draft, status: 'ASSIGNED', deadline: 'invalid' }];
  ctx.filters = { search: 'nguyen', department: 'dep', status: 'PENDING_REVIEW', workPriority: 'URGENT', workDue: 'TODAY' };
  assert.deepEqual(filteredWorkItems(ctx, Date.parse('2026-10-12T01:00:00Z')).map(row => row.id), ['matching']);
  ctx.filters.workPriority = 'HIGH';
  assert.deepEqual(filteredWorkItems(ctx, Date.parse('2026-10-12T01:00:00Z')), []);
});

test('create task and matching immutable history in one transaction without injected IDs', async () => {
  const ctx = context({ 'employees/employee': employee });
  const id = await saveWorkItem(ctx, null, null, draft);
  const task = ctx.documents.get(`workItems/${id}`), history = ctx.documents.get(`workItems/${id}/history/1`);
  assert.equal(task.version, 1); assert.equal(task.status, 'ASSIGNED'); assert.equal(task.assigneeName, employee.fullName);
  assert.equal(Object.hasOwn(task, 'id'), false); assert.equal(history.before, null); assert.deepEqual(history.after, task);
});

test('stale task update commits nothing and leaves prior version intact', async () => {
  const ctx = context({ 'employees/employee': employee }); const id = await saveWorkItem(ctx, null, null, draft);
  const before = ctx.writes.length;
  await assert.rejects(saveWorkItem(ctx, id, 0, { ...draft, title: 'Changed' }), /người khác cập nhật/);
  assert.equal(ctx.writes.length, before); assert.equal(ctx.documents.get(`workItems/${id}`).title, draft.title);
});

test('review saves exact old document and increments version only once', async () => {
  const ctx = context({ 'employees/employee': employee }); const id = await saveWorkItem(ctx, null, null, draft);
  const previous = { ...ctx.documents.get(`workItems/${id}`), status: 'PENDING_REVIEW', resultReport: 'Đã kiểm tra' };
  ctx.documents.set(`workItems/${id}`, previous);
  await reviewWorkItem(ctx, id, 1, true, 'Tốt');
  const next = ctx.documents.get(`workItems/${id}`), history = ctx.documents.get(`workItems/${id}/history/2`);
  assert.equal(next.status, 'COMPLETED'); assert.equal(next.completedAt, 'SERVER_TIMESTAMP');
  assert.deepEqual(history.before, previous); assert.deepEqual(history.after, next); assert.equal(Object.hasOwn(history.before, 'id'), false);
  await assert.rejects(reviewWorkItem(ctx, id, 1, true, ''), /đã thay đổi/);
});

test('reassignment resets report and starts a new assigned version while retaining history', async () => {
  const ctx = context({ 'employees/employee': employee, 'employees/second': { ...employee, fullName: 'Bình' } }); const id = await saveWorkItem(ctx, null, null, draft);
  ctx.documents.set(`workItems/${id}`, { ...ctx.documents.get(`workItems/${id}`), status: 'PENDING_REVIEW', resultReport: 'Existing report', managerFeedback: 'Previous', reworkCount: 2 });
  await saveWorkItem(ctx, id, 1, { ...draft, assigneeId: 'second' });
  const next = ctx.documents.get(`workItems/${id}`); assert.equal(next.status, 'ASSIGNED'); assert.equal(next.resultReport, ''); assert.equal(next.reworkCount, 0);
  assert.equal(ctx.documents.get(`workItems/${id}/history/2`).before.resultReport, 'Existing report');
});

test('merging an afternoon assignment preserves morning, adjustments and notes', () => {
  const existing = { shiftId: 'morning', shiftIds: ['morning'], workedHoursOverride: 3.5, adjustmentNote: 'Sửa công', overtimeHours: 1, note: 'Cũ' };
  const merged = mergeAssignment(existing, { date: '2026-10-12', overtimeHours: 0 }, [afternoon], new Map([['morning', morning], ['afternoon', afternoon]]));
  assert.deepEqual(merged.shiftIds, ['morning', 'afternoon']); assert.equal(merged.workedHoursOverride, 3.5); assert.equal(merged.note, 'Cũ'); assert.equal(merged.overtimeHours, 1);
});

test('schedule transaction validates current active employee and does not write injected IDs', async () => {
  const ctx = context({ 'employees/employee': employee, 'shifts/morning': morning, 'shifts/afternoon': afternoon, 'workSchedules/employee_2026-10-12': { employeeId: 'employee', date: '2026-10-12', shiftId: 'morning', shiftIds: ['morning'], overtimeHours: 0, workedHoursOverride: 3 } });
  await assignSchedules(ctx, ['employee'], ['2026-10-12'], ['afternoon']);
  const schedule = ctx.documents.get('workSchedules/employee_2026-10-12'); assert.deepEqual(schedule.shiftIds, ['morning', 'afternoon']); assert.equal(schedule.workedHoursOverride, 3); assert.equal(Object.hasOwn(schedule, 'id'), false);
  ctx.documents.set('employees/employee', { ...employee, active: false });
  await assert.rejects(assignSchedules(ctx, ['employee'], ['2026-10-13'], ['morning']), /đã nghỉ/);
  assert.equal(ctx.documents.has('workSchedules/employee_2026-10-13'), false);
});

test('copy skips an existing target schedule and preserves source fields like Android', async () => {
  const ctx = context({ 'employees/employee': employee, 'shifts/morning': morning, 'workSchedules/source': { employeeId: 'employee', date: '2026-10-05', shiftId: 'morning', shiftIds: ['morning'], overtimeHours: 0, workedHoursOverride: 2, adjustmentNote: 'Source correction' } });
  assert.equal(await copyPreviousWeek(ctx, '2026-10-12'), 1);
  assert.equal(ctx.documents.get('workSchedules/employee_2026-10-12').workedHoursOverride, 2);
  assert.equal(ctx.documents.get('workSchedules/employee_2026-10-12').adjustmentNote, 'Source correction');
  assert.equal(await copyPreviousWeek(ctx, '2026-10-12'), 0);
});

test('weekly approval atomically applies six days, removes registered days off, preserves adjustments and sends result', async () => {
  const ctx = context({ 'employees/employee': employee, 'shifts/morning': morning, 'weeklyScheduleRequests/weekly': { id: 'weekly', employeeId: 'employee', employeeName: employee.fullName, department: employee.department, weekStart: '2026-10-12', status: 'PENDING', shiftsByDate: { '2026-10-12': ['morning'] }, reason: '', createdAt: { seconds: 1 } }, 'workSchedules/employee_2026-10-12': { shiftId: 'morning', overtimeHours: 1, workedHoursOverride: 3.5, adjustmentNote: 'Correction', note: 'Keep' }, 'workSchedules/employee_2026-10-13': { shiftId: 'morning', overtimeHours: 0 } });
  await reviewRequest(ctx, 'weekly', 'weekly', true, '');
  assert.equal(ctx.documents.get('weeklyScheduleRequests/weekly').status, 'APPROVED'); assert.equal(ctx.documents.has('workSchedules/employee_2026-10-13'), false);
  assert.equal(ctx.documents.get('workSchedules/employee_2026-10-12').workedHoursOverride, 3.5);
  assert.ok(ctx.writes.some(w => w.collection === 'notifications' && w.data.type === 'WEEKLY_SCHEDULE_RESULT'));
});

test('overtime review pairs deterministic audit status and rejection reason', async () => {
  const ctx = context({ 'employees/employee': employee, 'overtimeRequests/employee_2026-10-12': { employeeId: 'employee', employeeName: employee.fullName, workDate: '2026-10-12', startTime: '18:00', endTime: '22:00', status: 'PENDING' } });
  await reviewRequest(ctx, 'overtime', 'employee_2026-10-12', false, 'Không cần thêm ca');
  const audit = ctx.documents.get('audit_logs/employee_2026-10-12_OVERTIME_REVIEW'); assert.equal(audit.status, 'REJECTED'); assert.equal(audit.reason, 'Không cần thêm ca');
  await assert.rejects(reviewRequest(ctx, 'overtime', 'employee_2026-10-12', true, ''), /đã được Admin khác/);
});

test('request audit follows the freshly verified Admin name during a concurrent profile rename', async () => {
  const ctx = context({ 'employees/employee': employee, 'overtimeRequests/employee_2026-10-12': { employeeId: 'employee', employeeName: employee.fullName, workDate: '2026-10-12', startTime: '18:00', endTime: '22:00', status: 'PENDING' } });
  ctx.documents.set('users/admin', { role: 'ADMIN', active: true, displayName: 'Tên mới' });
  await reviewRequest(ctx, 'overtime', 'employee_2026-10-12', true, '');
  assert.equal(ctx.documents.get('overtimeRequests/employee_2026-10-12').reviewerName, 'Tên mới');
  assert.equal(ctx.documents.get('audit_logs/employee_2026-10-12_OVERTIME_REVIEW').actorName, 'Tên mới');
});

test('attendance request approval handles overnight checkout and matching adjustment audit', async () => {
  const ctx = context({ 'employees/employee': employee, 'leaveRequests/request': { employeeId: 'employee', employeeName: employee.fullName, type: 'ATTENDANCE_ADJUSTMENT', startDate: '2026-10-12', endDate: '2026-10-12', proposedCheckIn: '22:00', proposedCheckOut: '02:00', reason: 'Quên quét', status: 'PENDING' } });
  await reviewRequest(ctx, 'leave', 'request', true, '');
  const id = ctx.documents.get('leaveRequests/request').appliedAdjustmentId, adjustment = ctx.documents.get(`attendanceAdjustments/${id}`);
  assert.equal(adjustment.checkOutAt.seconds - adjustment.checkInAt.seconds, 4 * 3600);
  assert.equal(ctx.documents.get(`audit_logs/${id}`).reason, adjustment.reason);
  assert.equal(ctx.documents.get('audit_logs/request_REVIEW').targetId, 'request');
});

test('invalid shifts and XSS in rendered task content stay bounded', () => {
  assert.throws(() => validateShift({ ...morning, endTime: '13:00' }), /tối đa 4 giờ/);
  assert.throws(() => validateShift({ ...morning, id: 'SUPPLEMENTARY_1800_2200' }, true), /quản lý riêng/);
  const ctx = context(); ctx.data = { workItems: [{ id: 'task', title: '<script>alert(1)</script>', assigneeName: 'Test', assigneeId: 'employee', status: 'ASSIGNED', priority: 'NORMAL', deadline: { seconds: 20 }, version: 1 }] };
  const html = render('workItems', ctx); assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'));
});
