import test from 'node:test';
import assert from 'node:assert/strict';
import { assertEmulatorHost, createHarness } from './firestore-rest-helper.mjs';
import { assignSchedules } from '../src/operations.js';
import { defaultScheduleShifts } from '../src/shift-selection.js';

const host = process.env.WEB_FIRESTORE_EMULATOR_HOST;
if (host) assertEmulatorHost(host);
const harness = host ? createHarness(host) : null;

test('shift integration: explicit assignment seeds stable defaults in the same transaction and preserves existing settings and historical IDs', { skip: !host, timeout: 120000 }, async () => {
  const prefix = `shift-${Date.now()}-${process.pid}`, ctx = await harness.context(`${prefix}-admin`);
  const employeeId = `${prefix}-employee`, legacyId = `${prefix}-legacy`;
  const [morning, afternoon] = defaultScheduleShifts();
  // This suite uses only the loopback demo project enforced by the adapter.
  for (const shift of [morning, afternoon]) await harness.owner.remove('shifts', shift.id);
  await harness.seed('employees', employeeId, { fullName: 'Nhân viên', active: true, department: 'Kỹ thuật' });
  const { id: ignored, ...legacy } = { ...afternoon, id: legacyId };
  await harness.seed('shifts', legacyId, legacy);
  const old = { employeeId, employeeName: 'Nhân viên', department: 'Kỹ thuật', date: '2026-10-12',
    shiftId: legacyId, shiftIds: [legacyId], shiftName: 'Ca chiều', overtimeHours: 0,
    workedHoursOverride: 3, adjustmentNote: 'Đối chiếu', note: 'Giữ' };
  await harness.seed('workSchedules', `${employeeId}_2026-10-12`, old);
  await assignSchedules(ctx, [employeeId], ['2026-10-12'], [morning.id]);
  const first = await ctx.store.get('workSchedules', `${employeeId}_2026-10-12`);
  assert.deepEqual(first.shiftIds, [morning.id, legacyId]);
  assert.equal(first.workedHoursOverride, 3); assert.equal(first.adjustmentNote, 'Đối chiếu');
  assert.equal(first.note, 'Giữ');
  assert.equal((await ctx.store.get('shifts', morning.id)).allowEarlyMinutes, 120);
  assert.equal(await ctx.store.get('shifts', afternoon.id), null);
  assert.deepEqual(await ctx.store.get('shifts', legacyId), { ...legacy, id: legacyId });
  await harness.owner.update('shifts', morning.id, { lateGraceMinutes: 7 });
  await assignSchedules(ctx, [employeeId], ['2026-10-13'], [morning.id, afternoon.id]);
  assert.equal((await ctx.store.get('shifts', morning.id)).lateGraceMinutes, 7);
  assert.equal((await ctx.store.get('shifts', afternoon.id)).startTime, '13:00');
  assert.deepEqual(await ctx.store.get('workSchedules', `${employeeId}_2026-10-12`), first);
});
