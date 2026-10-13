// Exercise the actual web transactions against a loopback demo Firestore.
// No production credentials or Firebase project writes are used by this suite.
import test from 'node:test';
import assert from 'node:assert/strict';
import { assertEmulatorHost, createHarness } from './firestore-rest-helper.mjs';
import { requestFaceEnrollment, cancelFaceEnrollment } from '../src/face-enrollment.js';

const host = process.env.WEB_FIRESTORE_EMULATOR_HOST;
if (host) assertEmulatorHost(host);
const harness = host ? createHarness(host) : null;
const options = { skip: !host, timeout: 120000 };
const suffix = `${Date.now()}-${process.pid}`;
let sequence = 0;
const commandKeys = ['requestId', 'employeeId', 'stationId', 'deviceId', 'sessionId', 'action', 'status', 'requestedBy', 'requestedAt', 'sampleCount', 'samplesCaptured', 'cancelRequested', 'message'].sort();
const permissionDenied = error => error.status === 403;
const withoutId = row => { const { id, ...data } = row; return data; };

async function fixture() {
  const prefix = `face-${suffix}-${++sequence}`;
  const ctx = await harness.context(`${prefix}-admin`);
  const employeeId = `${prefix}-employee`, otherEmployeeId = `${prefix}-other`;
  const stationId = `${prefix}-station`, otherStationId = `${prefix}-station2`;
  for (const id of [employeeId, otherEmployeeId]) {
    await harness.seed('employees', id, { fullName: `Nhân viên ${id}`, active: true });
  }
  for (const id of [stationId, otherStationId]) {
    await harness.seed('faceStations', id, { stationId: id, faceDeviceId: `${id}-cam`, sessionId: `${id}-boot`,
      mode: 'firebase', lastSeenAt: new Date(), cameraReady: true, pendingCommandId: null });
  }
  return { ctx, employeeId, otherEmployeeId, stationId, otherStationId, prefix };
}

test('face integration: web ENROLL atomically creates the exact command and both reservations; DELETE captures zero samples', options, async () => {
  const { ctx, employeeId, stationId } = await fixture();
  const requestId = await requestFaceEnrollment(ctx, { employeeId, stationId });
  const command = withoutId(await ctx.store.get('faceEnrollmentCommands', requestId));
  assert.deepEqual(Object.keys(command).sort(), commandKeys);
  assert.deepEqual({ ...command, requestedAt: 'SERVER_TIME' }, { requestId, employeeId, stationId,
    deviceId: `${stationId}-cam`, sessionId: `${stationId}-boot`, action: 'ENROLL', status: 'PENDING',
    requestedBy: ctx.user.uid, requestedAt: 'SERVER_TIME', sampleCount: 10, samplesCaptured: 0, cancelRequested: false, message: '' });
  assert.ok(Math.abs(Date.now() - command.requestedAt.toMillis()) < 30000);
  assert.equal((await ctx.store.get('faceStations', stationId)).pendingCommandId, requestId);
  const lock = withoutId(await ctx.store.get('faceEnrollmentLocks', employeeId));
  assert.deepEqual(Object.keys(lock).sort(), ['commandId', 'employeeId', 'stationId', 'updatedAt']);
  assert.equal(lock.commandId, requestId);
  assert.equal(lock.stationId, stationId);
  assert.equal(lock.employeeId, employeeId);
  assert.equal(lock.updatedAt.toMillis(), command.requestedAt.toMillis());
  await assert.rejects(ctx.store.update('faceEnrollmentCommands', requestId, { status: 'COMPLETED' }), permissionDenied);
  assert.equal((await ctx.store.get('faceEnrollmentCommands', requestId)).status, 'PENDING');

  // Only the worker may finish the prior request; the next web transaction
  // reuses terminal reservations without an unsafe client-side unlock.
  await harness.owner.update('faceEnrollmentCommands', requestId, { status: 'COMPLETED' });
  const deleteId = await requestFaceEnrollment(ctx, { employeeId, stationId, action: 'DELETE' });
  const deletion = await ctx.store.get('faceEnrollmentCommands', deleteId);
  assert.equal(deletion.action, 'DELETE');
  assert.equal(deletion.sampleCount, 0);
  assert.equal(deletion.samplesCaptured, 0);
  assert.equal(deletion.status, 'PENDING');
  assert.equal((await ctx.store.get('faceEnrollmentLocks', employeeId)).commandId, deleteId);
  assert.equal((await ctx.store.get('faceStations', stationId)).pendingCommandId, deleteId);
});

test('face integration: cancellation changes only the request flag; locks and worker-owned success remain protected', options, async () => {
  const { ctx, employeeId, stationId } = await fixture();
  const requestId = await requestFaceEnrollment(ctx, { employeeId, stationId });
  const before = withoutId(await ctx.store.get('faceEnrollmentCommands', requestId));
  await cancelFaceEnrollment(ctx, requestId);
  const cancelled = withoutId(await ctx.store.get('faceEnrollmentCommands', requestId));
  const { cancelRequestedAt, ...remaining } = cancelled;
  assert.deepEqual(remaining, { ...before, cancelRequested: true });
  assert.ok(cancelRequestedAt.toMillis() >= before.requestedAt.toMillis());
  assert.equal(cancelled.status, 'PENDING');
  assert.equal((await ctx.store.get('faceStations', stationId)).pendingCommandId, requestId);
  assert.equal((await ctx.store.get('faceEnrollmentLocks', employeeId)).commandId, requestId);
  await assert.rejects(ctx.store.update('faceEnrollmentCommands', requestId, { status: 'CANCELLED' }), permissionDenied);
  await assert.rejects(ctx.store.set('faceProfiles', employeeId, { employeeId, enabled: true, version: 1 }), permissionDenied);
  assert.equal(await harness.owner.get('faceProfiles', employeeId), null);

  await harness.owner.update('faceEnrollmentCommands', requestId, { status: 'CANCELLED' });
  const capturingId = await requestFaceEnrollment(ctx, { employeeId, stationId });
  await harness.owner.update('faceEnrollmentCommands', capturingId, { status: 'CAPTURING', samplesCaptured: 3 });
  await cancelFaceEnrollment(ctx, capturingId);
  const capturing = await ctx.store.get('faceEnrollmentCommands', capturingId);
  assert.equal(capturing.status, 'CAPTURING');
  assert.equal(capturing.samplesCaptured, 3);
  assert.equal(capturing.cancelRequested, true);
  await harness.owner.update('faceEnrollmentCommands', capturingId, { status: 'FINALIZING', cancelRequested: false });
  await assert.rejects(cancelFaceEnrollment(ctx, capturingId), /đã kết thúc|đang lưu/i);
  await assert.rejects(ctx.store.update('faceEnrollmentCommands', capturingId, { cancelRequested: true, cancelRequestedAt: ctx.store.stamp() }), permissionDenied);
});

test('face integration: concurrent web admins cannot reserve one station twice or one employee at two stations', options, async () => {
  const { ctx, employeeId, otherEmployeeId, stationId, otherStationId, prefix } = await fixture();
  const otherCtx = await harness.context(`${prefix}-second-admin`);
  const results = await Promise.allSettled([
    requestFaceEnrollment(ctx, { employeeId, stationId }),
    requestFaceEnrollment(otherCtx, { employeeId: otherEmployeeId, stationId }),
  ]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1,
    results.map(result => result.status === 'rejected' ? result.reason.message : `committed ${result.value}`).join('\n'));
  assert.equal(results.filter(result => result.status === 'rejected').length, 1);
  const winnerId = results.find(result => result.status === 'fulfilled').value;
  const winningCommand = await ctx.store.get('faceEnrollmentCommands', winnerId);
  const losingEmployeeId = winningCommand.employeeId === employeeId ? otherEmployeeId : employeeId;
  assert.equal((await ctx.store.get('faceStations', stationId)).pendingCommandId, winnerId);
  assert.equal((await ctx.store.get('faceEnrollmentLocks', winningCommand.employeeId)).commandId, winnerId);
  assert.equal(await ctx.store.get('faceEnrollmentLocks', losingEmployeeId), null);
  const commands = (await ctx.store.list('faceEnrollmentCommands', { where: [['stationId', '==', stationId]] })).rows;
  assert.deepEqual(commands.map(row => row.id), [winnerId]);
  await assert.rejects(requestFaceEnrollment(otherCtx, { employeeId: winningCommand.employeeId, stationId: otherStationId }), /chưa hoàn tất/i);
  assert.equal((await ctx.store.get('faceStations', otherStationId)).pendingCommandId, null);
});

test('face integration: current worker heartbeat, session and active employee are validated before the transaction writes', options, async () => {
  const { ctx, employeeId, stationId } = await fixture();
  for (const change of [{ cameraReady: false }, { mode: 'demo' }, { lastSeenAt: new Date(Date.now() - 120000) },
    { lastSeenAt: new Date(Date.now() + 120000) }, { sessionId: '' }]) {
    await harness.owner.update('faceStations', stationId, { cameraReady: true, mode: 'firebase', lastSeenAt: new Date(), sessionId: `${stationId}-boot`, ...change });
    await assert.rejects(requestFaceEnrollment(ctx, { employeeId, stationId }), /chưa sẵn sàng/i);
    assert.equal((await harness.owner.get('faceStations', stationId)).pendingCommandId, null);
    assert.equal(await harness.owner.get('faceEnrollmentLocks', employeeId), null);
  }
  await harness.owner.update('faceStations', stationId, { sessionId: `${stationId}-new-boot`, lastSeenAt: new Date() });
  await harness.owner.update('employees', employeeId, { active: false });
  await assert.rejects(requestFaceEnrollment(ctx, { employeeId, stationId }), /ngừng hoạt động/i);
  assert.equal((await harness.owner.get('faceStations', stationId)).pendingCommandId, null);
  await harness.owner.update('employees', employeeId, { active: true });
  const requestId = await requestFaceEnrollment(ctx, { employeeId, stationId });
  assert.equal((await ctx.store.get('faceEnrollmentCommands', requestId)).sessionId, `${stationId}-new-boot`);
});

test('face integration: only active Admins can read face metadata or request and cancel enrollment', options, async () => {
  const { ctx, employeeId, stationId, prefix } = await fixture();
  const requestId = await requestFaceEnrollment(ctx, { employeeId, stationId });
  await harness.seed('faceProfiles', employeeId, { employeeId, enabled: true, version: 1 });
  const employee = await harness.context(`${prefix}-account`, 'EMPLOYEE', employeeId);
  const inactive = await harness.context(`${prefix}-retired-admin`);
  await harness.owner.update('users', inactive.user.uid, { active: false });
  const anonymous = harness.storeFor(`${prefix}-device`, 'anonymous');
  for (const [collection, id] of [['faceEnrollmentCommands', requestId], ['faceStations', stationId], ['faceEnrollmentLocks', employeeId], ['faceProfiles', employeeId]]) {
    assert.ok(await ctx.store.get(collection, id));
    for (const store of [employee.store, inactive.store, anonymous]) await assert.rejects(store.get(collection, id), permissionDenied);
  }
  for (const forbidden of [employee, inactive]) {
    await assert.rejects(requestFaceEnrollment(forbidden, { employeeId, stationId }), /Admin|HTTP 403/i);
    await assert.rejects(cancelFaceEnrollment(forbidden, requestId), /Admin|HTTP 403/i);
  }
  assert.equal((await ctx.store.get('faceEnrollmentCommands', requestId)).cancelRequested, false);
});
