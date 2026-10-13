import test from 'node:test';
import assert from 'node:assert/strict';
import { defaultScheduleShifts, displayedScheduleShifts, assignableScheduleShifts } from '../src/shift-selection.js';
import { onAction, render, assignSchedules } from '../src/operations.js';
import { memoryStore } from '../src/demo.js';

const [morning, afternoon] = defaultScheduleShifts();
const legacyMorning = { ...morning, id: 'legacy-morning', effectiveFrom: '2026-10-01', lateGraceMinutes: 5 };
const legacyAfternoon = { ...afternoon, id: 'legacy-afternoon' };
const supplementary = { ...afternoon, id: 'extra', name: 'Ca thêm', category: 'SUPPLEMENTARY', startTime: '18:00', endTime: '22:00', countsOvertime: true, effectiveFrom: '2026-10-12' };

function fixture(extra = {}) {
  const data = { users: [{ id: 'admin', role: 'ADMIN', active: true }],
    employees: [{ id: 'e1', fullName: 'Nhân viên', active: true, department: 'Kỹ thuật' }],
    shifts: [], workSchedules: [], departments: [], audit_logs: [], overtimeRequests: [], ...structuredClone(extra) };
  const store = memoryStore(data), transaction = store.transaction;
  store.transaction = callback => transaction(async tx => {
    let wrote = false;
    return callback({
      get: (...args) => { assert.equal(wrote, false, 'transaction read after write'); return tx.get(...args); },
      set: (...args) => { wrote = true; tx.set(...args); },
      update: (...args) => { wrote = true; tx.update(...args); },
      remove: (...args) => { wrote = true; tx.remove(...args); },
    });
  });
  const ctx = { data, store, user: { uid: 'admin', displayName: 'Admin' }, filters: { week: '2026-10-12' },
    audit: (action, targetType, targetId, details) => ({ actorId: 'admin', action, targetType, targetId, details }),
    render() {}, ui: { toast() {}, dialog(value) { ctx.dialog = value; } } };
  return ctx;
}

test('current main choices prefer the saved Android template IDs over legacy duplicates and preserve their settings', () => {
  const saved = { ...morning, lateGraceMinutes: 7 };
  const shifts = [legacyMorning, legacyAfternoon, supplementary, saved, afternoon];
  const before = structuredClone(shifts);
  assert.deepEqual(assignableScheduleShifts(shifts).map(shift => shift.id), [morning.id, afternoon.id]);
  assert.equal(assignableScheduleShifts(shifts)[0].lateGraceMinutes, 7);
  assert.deepEqual(shifts, before);
});

test('shift page collapses old main definitions, labels sentinel defaults and retains supplementary administration', () => {
  const ctx = fixture({ shifts: [legacyMorning, legacyAfternoon, morning, afternoon, supplementary,
    { ...supplementary, id: 'extra-inactive', name: 'Ca thêm cũ', active: false }] });
  const before = structuredClone(ctx.data);
  const html = render('shifts', ctx);
  assert.equal((html.match(/<strong>Ca sáng<\/strong>/g) || []).length, 1);
  assert.equal((html.match(/<strong>Ca chiều<\/strong>/g) || []).length, 1);
  assert.doesNotMatch(html, /01\/01\/1970|1970-01-01/);
  assert.match(html, /Ca mặc định/);
  assert.match(html, /Ca thêm cũ/);
  assert.match(html, /data-action="ops-shift-toggle" data-id="extra-inactive"/);
  assert.deepEqual(ctx.data, before);
});

test('missing defaults appear once in assignment choices without any write until the user submits', async () => {
  const ctx = fixture({ shifts: [legacyMorning, legacyAfternoon] });
  const before = structuredClone(ctx.data);
  await onAction('ops-schedule-new', { dataset: {} }, ctx);
  assert.equal((ctx.dialog.body.match(new RegExp(`value="${morning.id}"`, 'g')) || []).length, 1);
  assert.equal((ctx.dialog.body.match(new RegExp(`value="${afternoon.id}"`, 'g')) || []).length, 1);
  assert.doesNotMatch(ctx.dialog.body, /value="legacy-(?:morning|afternoon)"/);
  assert.deepEqual(ctx.data, before);
});

test('stored inactive default remains visible as stopped and is unavailable for assignment without fake fallback', () => {
  const shifts = [{ ...morning, active: false }, legacyMorning, afternoon];
  assert.equal(displayedScheduleShifts(shifts)[0].active, false);
  assert.deepEqual(assignableScheduleShifts(shifts).map(shift => shift.id), [afternoon.id]);
});

test('contextual assignment preselects its current main shifts and preserves the loaded legacy definitions', async () => {
  const ctx = fixture({ shifts: [morning, afternoon, legacyAfternoon], workSchedules: [
    { id: 'e1_2026-10-12', employeeId: 'e1', date: '2026-10-12', shiftId: morning.id, shiftIds: [morning.id, afternoon.id] },
  ] });
  const before = structuredClone(ctx.data);
  await onAction('ops-schedule-cell', { dataset: { employee: 'e1', date: '2026-10-12' } }, ctx);
  assert.match(ctx.dialog.body, new RegExp(`value="${morning.id}" selected`));
  assert.match(ctx.dialog.body, new RegExp(`value="${afternoon.id}" selected`));
  assert.deepEqual(ctx.data, before);
});

test('explicit assignment atomically creates only missing selected default and keeps existing legacy other-shift IDs and corrections', async () => {
  const ctx = fixture({ shifts: [legacyAfternoon], workSchedules: [
    { id: 'e1_2026-10-12', employeeId: 'e1', date: '2026-10-12', shiftId: legacyAfternoon.id, shiftIds: [legacyAfternoon.id], workedHoursOverride: 3.5, adjustmentNote: 'Công cũ', overtimeHours: 1, note: 'Ghi chú cũ' },
  ] });
  await assignSchedules(ctx, ['e1'], ['2026-10-12'], [morning.id]);
  assert.deepEqual(ctx.data.shifts.map(shift => shift.id).sort(), [legacyAfternoon.id, morning.id].sort());
  assert.equal(ctx.data.shifts.find(shift => shift.id === morning.id).allowEarlyMinutes, 120);
  const schedule = ctx.data.workSchedules[0];
  assert.deepEqual(schedule.shiftIds, [morning.id, legacyAfternoon.id]);
  assert.equal(schedule.workedHoursOverride, 3.5); assert.equal(schedule.adjustmentNote, 'Công cũ');
  assert.equal(schedule.overtimeHours, 1); assert.equal(schedule.note, 'Ghi chú cũ');
  assert.deepEqual(ctx.data.shifts.find(shift => shift.id === legacyAfternoon.id), legacyAfternoon);
});

test('default initialization is idempotent and preserves saved custom grace settings and all legacy definitions', async () => {
  const saved = { ...morning, lateGraceMinutes: 7 };
  const ctx = fixture({ shifts: [saved, legacyMorning] });
  await onAction('ops-shift-seed', { dataset: {} }, ctx);
  await onAction('ops-shift-seed', { dataset: {} }, ctx);
  assert.equal(ctx.data.shifts.length, 3);
  assert.deepEqual(ctx.data.shifts.find(shift => shift.id === morning.id), saved);
  assert.deepEqual(ctx.data.shifts.find(shift => shift.id === legacyMorning.id), legacyMorning);
});

test('assignment rejects inactive or corrupted stored defaults and invalid modes without partial writes', async () => {
  for (const shift of [{ ...morning, active: false }, { ...morning, startTime: '09:00' }, { ...morning, category: 'EVENING' }]) {
    const ctx = fixture({ shifts: [shift] }), before = structuredClone(ctx.data);
    await assert.rejects(assignSchedules(ctx, ['e1'], ['2026-10-12'], [morning.id]), /ngừng hoạt động|đổi giờ hoặc loại/);
    assert.deepEqual(ctx.data, before);
  }
  const ctx = fixture(), before = structuredClone(ctx.data);
  await assert.rejects(assignSchedules(ctx, ['e1'], ['2026-10-12'], [morning.id], 'invalid'), /Cách áp dụng/);
  assert.deepEqual(ctx.data, before);
});

test('bulk editor uses every checked employee and only the individually checked workdays', async () => {
  const ctx = fixture({ employees: [
    { id: 'e1', fullName: 'Một', active: true }, { id: 'e2', fullName: 'Hai', active: true },
    { id: 'e3', fullName: 'Ba', active: true },
  ] });
  await onAction('ops-schedule-new', { dataset: {} }, ctx);
  const form = { querySelectorAll(selector) {
    const values = selector.includes('employeeIds') ? ['e1', 'e2'] : ['2026-10-12', '2026-10-14'];
    return values.map(value => ({ value }));
  } };
  // Generic FormData's single string entry must not discard other checked values.
  await ctx.dialog.onSubmit({ scope: 'employees', employeeIds: 'e2', from: '2026-10-12', to: '2026-10-17',
    scheduleDates: '2026-10-14', morning: morning.id, afternoon: '', mode: 'merge' }, form);
  assert.deepEqual(ctx.data.workSchedules.map(schedule => schedule.id).sort(), [
    'e1_2026-10-12', 'e1_2026-10-14', 'e2_2026-10-12', 'e2_2026-10-14',
  ]);
  assert.equal(ctx.data.workSchedules.some(schedule => schedule.employeeId === 'e3' || schedule.date === '2026-10-13'), false);
});

test('bulk editor rejects omitted, retired, out-of-range and Sunday selections without any write', async () => {
  for (const selection of [
    { employeeIds: [], scheduleDates: ['2026-10-12'] },
    { employeeIds: ['retired'], scheduleDates: ['2026-10-12'] },
    { employeeIds: ['e1'], scheduleDates: [] },
    { employeeIds: ['e1'], scheduleDates: ['2026-10-11'] },
    { employeeIds: ['e1'], scheduleDates: ['2026-10-18'] },
    { employeeIds: ['e1'], scheduleDates: ['2026-11-02'] },
  ]) {
    const ctx = fixture({ employees: [{ id: 'e1', fullName: 'Một', active: true }, { id: 'retired', fullName: 'Đã nghỉ', active: false }] });
    await onAction('ops-schedule-new', { dataset: {} }, ctx);
    const before = structuredClone(ctx.data);
    await assert.rejects(ctx.dialog.onSubmit({ scope: 'employees', from: '2026-10-12', to: '2026-10-17',
      morning: morning.id, afternoon: '', mode: 'merge', ...selection }), /Chọn/);
    assert.deepEqual(ctx.data, before);
  }
});

test('cell editor chooses replace explicitly while preserving prior overrides, notes and unrelated schedules', async () => {
  const ctx = fixture({ shifts: [morning, afternoon], workSchedules: [
    { id: 'e1_2026-10-12', employeeId: 'e1', date: '2026-10-12', shiftId: morning.id, shiftIds: [morning.id, afternoon.id], workedHoursOverride: 3, adjustmentNote: 'Cũ', note: 'Giữ' },
    { id: 'e1_2026-10-13', employeeId: 'e1', date: '2026-10-13', shiftId: afternoon.id, shiftIds: [afternoon.id] },
  ] });
  const otherDay = structuredClone(ctx.data.workSchedules[1]);
  await onAction('ops-schedule-cell', { dataset: { employee: 'e1', date: '2026-10-12' } }, ctx);
  assert.match(ctx.dialog.body, /value="replace" selected/);
  assert.match(ctx.dialog.body, /data-action="attendance-adjust" data-employee="e1" data-date="2026-10-12"/);
  await ctx.dialog.onSubmit({ scope: 'employee', employeeId: 'e1', from: '2026-10-12', to: '2026-10-12',
    scheduleDates: ['2026-10-12'], morning: morning.id, afternoon: '', mode: 'replace' });
  assert.deepEqual(ctx.data.workSchedules[0].shiftIds, [morning.id]);
  assert.equal(ctx.data.workSchedules[0].workedHoursOverride, 3);
  assert.equal(ctx.data.workSchedules[0].adjustmentNote, 'Cũ'); assert.equal(ctx.data.workSchedules[0].note, 'Giữ');
  assert.deepEqual(ctx.data.workSchedules[1], otherDay);
});

test('date and scope changes update only picker controls and keep all data read-only', async () => {
  const ctx = fixture();
  await onAction('ops-schedule-new', { dataset: {} }, ctx);
  const before = structuredClone(ctx.data);
  const controls = { '[data-assignment-single]': {}, '[data-assignment-many]': {}, '[data-assignment-department]': {}, '#assignment-days': {} };
  const dates = { from: '2026-10-19', to: '2026-10-24' };
  const form = { querySelector(selector) { return controls[selector]; }, elements: { namedItem(name) { return { value: dates[name] }; } } };
  ctx.dialog.onChange({ target: { name: 'scope', value: 'employees' } }, form);
  assert.equal(controls['[data-assignment-many]'].hidden, false);
  assert.equal(controls['[data-assignment-single]'].hidden, true);
  ctx.dialog.onChange({ target: { name: 'from' } }, form);
  assert.match(controls['#assignment-days'].innerHTML, /value="2026-10-19" checked/);
  assert.doesNotMatch(controls['#assignment-days'].innerHTML, /2026-10-12/);
  dates.to = '2026-12-01';
  ctx.dialog.onChange({ target: { name: 'to' } }, form);
  assert.match(controls['#assignment-days'].innerHTML, /role="alert"/);
  assert.deepEqual(ctx.data, before);
});

test('month day selection is read-only and opens assignment for the selected working date only', async () => {
  const ctx = fixture(); ctx.filters.scheduleView = 'month'; ctx.filters.month = '2026-10';
  const before = structuredClone(ctx.data);
  await onAction('ops-schedule-day', { dataset: { date: '2026-10-20' } }, ctx);
  assert.equal(ctx.filters.scheduleDate, '2026-10-20');
  await onAction('ops-schedule-new', { dataset: {} }, ctx);
  assert.equal((ctx.dialog.body.match(/value="2026-10-20"/g) || []).length, 3);
  assert.match(render('schedules', ctx), /schedule-month-grid/);
  assert.deepEqual(ctx.data, before);
  await assert.rejects(onAction('ops-schedule-day', { dataset: { date: '2026-10-18' } }, ctx), /Chủ nhật|thứ Hai/);
  assert.equal(ctx.filters.scheduleDate, '2026-10-20');
});

test('month assignment follows the calendar fallback workday without leaking a previous week', async () => {
  const ctx = fixture(); ctx.filters.scheduleView = 'month';
  ctx.filters.month = '2026-11'; ctx.filters.week = '2026-10-12';
  const before = structuredClone(ctx.data);
  const previousNow = Date.now;
  try {
    Date.now = () => Date.parse('2026-10-12T05:00:00Z');
    await onAction('ops-schedule-new', { dataset: {} }, ctx);
  } finally { Date.now = previousNow; }
  assert.equal((ctx.dialog.body.match(/value="2026-11-02"/g) || []).length, 3);
  assert.doesNotMatch(ctx.dialog.body, /value="2026-10-12"/);
  assert.equal(ctx.filters.scheduleDate, undefined);
  assert.deepEqual(ctx.data, before);
});
