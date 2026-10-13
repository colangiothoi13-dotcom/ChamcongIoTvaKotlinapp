// Real rules on a loopback demo emulator; no Firebase credentials or production writes.
import test from 'node:test';
import assert from 'node:assert/strict';
import {assertEmulatorHost,createHarness} from './firestore-rest-helper.mjs';
import {requestFaceEnrollment,requestFaceCapture} from '../src/face-enrollment.js';

const host=process.env.WEB_FIRESTORE_EMULATOR_HOST;
if(host)assertEmulatorHost(host);
const harness=host?createHarness(host):null,options={skip:!host,timeout:120000};
const suffix=`${Date.now()}-${process.pid}`;let sequence=0;
const permissionDenied=error=>error.status===403;
const withoutId=row=>{const{id,...data}=row;return data;};
const legacyKeys=['requestId','employeeId','stationId','deviceId','sessionId','action','status','requestedBy','requestedAt','sampleCount','samplesCaptured','cancelRequested','message'];

async function fixture({captureMode='MANUAL',capturing=true}={}){
  const prefix=`shot-${suffix}-${++sequence}`,ctx=await harness.context(`${prefix}-admin`);
  const employeeId=`${prefix}-employee`,stationId=`${prefix}-station`;
  await harness.seed('employees',employeeId,{fullName:'Nhân viên kiểm thử',active:true});
  await harness.seed('faceStations',stationId,{stationId,faceDeviceId:`${prefix}-cam`,sessionId:`${prefix}-boot`,mode:'firebase',lastSeenAt:new Date(),cameraReady:true,pendingCommandId:null});
  const requestId=await requestFaceEnrollment(ctx,{employeeId,stationId,captureMode});
  if(capturing)await harness.owner.update('faceEnrollmentCommands',requestId,{status:'CAPTURING'});
  return{ctx,employeeId,stationId,requestId,prefix};
}

test('manual face integration: new manual schema and legacy automatic clients reserve station and employee atomically',options,async()=>{
  for(const captureMode of ['MANUAL','AUTO']){
    const{ctx,employeeId,stationId,requestId}=await fixture({captureMode,capturing:false});
    const saved=withoutId(await ctx.store.get('faceEnrollmentCommands',requestId));
    assert.deepEqual(Object.keys(saved).sort(),[...legacyKeys,...(captureMode==='MANUAL'?['captureMode','captureRequestSeq','captureConsumedSeq']:[])].sort());
    assert.equal(saved.status,'PENDING');assert.equal(saved.samplesCaptured,0);assert.equal(saved.sampleCount,10);
    if(captureMode==='MANUAL'){assert.equal(saved.captureMode,'MANUAL');assert.equal(saved.captureRequestSeq,0);assert.equal(saved.captureConsumedSeq,0);}
    assert.ok(Math.abs(Date.now()-saved.requestedAt.toMillis())<30000);
    assert.equal((await ctx.store.get('faceStations',stationId)).pendingCommandId,requestId);
    const lock=await ctx.store.get('faceEnrollmentLocks',employeeId);assert.equal(lock.commandId,requestId);assert.equal(lock.stationId,stationId);
    assert.equal(await harness.owner.get('faceProfiles',employeeId),null);
    await harness.owner.update('faceEnrollmentCommands',requestId,{status:'COMPLETED'});
    const deleteId=await requestFaceEnrollment(ctx,{employeeId,stationId,action:'DELETE'});
    const deleted=withoutId(await ctx.store.get('faceEnrollmentCommands',deleteId));assert.deepEqual(Object.keys(deleted).sort(),legacyKeys.slice().sort());assert.equal(deleted.sampleCount,0);
    await harness.owner.update('faceEnrollmentCommands',deleteId,{status:'COMPLETED'});
    const baseline=await ctx.store.get('faceStations',stationId);
    for(const extra of [{captureMode:'MANUAL'},{captureMode:'MANUAL',captureRequestSeq:0},{captureMode:'AUTO',captureRequestSeq:0,captureConsumedSeq:0},{captureMode:'MANUAL',captureRequestSeq:1,captureConsumedSeq:0},{captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,action:'DELETE',sampleCount:0}]){
      const id=ctx.store.id(),data={...saved,requestId:id,requestedAt:ctx.store.stamp(),captureMode:undefined};delete data.captureMode;delete data.captureRequestSeq;delete data.captureConsumedSeq;Object.assign(data,extra);
      await assert.rejects(ctx.store.batch([{type:'set',collection:'faceEnrollmentCommands',id,data},{type:'update',collection:'faceStations',id:stationId,data:{pendingCommandId:id,updatedAt:ctx.store.stamp()}},{type:'set',collection:'faceEnrollmentLocks',id:employeeId,data:{employeeId,stationId,commandId:id,updatedAt:ctx.store.stamp()}}]),permissionDenied);
      assert.equal(await harness.owner.get('faceEnrollmentCommands',id),null);assert.equal((await ctx.store.get('faceStations',stationId)).pendingCommandId,baseline.pendingCommandId);assert.equal((await ctx.store.get('faceEnrollmentLocks',employeeId)).commandId,deleteId);
    }
  }
});

test('manual face integration: one click changes only request sequence and server time, waiting for worker acknowledgement',options,async()=>{
  const{ctx,employeeId,stationId,requestId}=await fixture();
  const before=withoutId(await ctx.store.get('faceEnrollmentCommands',requestId)),station=await ctx.store.get('faceStations',stationId),lock=await ctx.store.get('faceEnrollmentLocks',employeeId);
  assert.equal(await requestFaceCapture(ctx,requestId),1);
  const first=withoutId(await ctx.store.get('faceEnrollmentCommands',requestId)),{captureRequestedAt,...remaining}=first;
  assert.deepEqual(remaining,{...before,captureRequestSeq:1});assert.ok(captureRequestedAt.toMillis()>=before.requestedAt.toMillis());assert.ok(Math.abs(Date.now()-captureRequestedAt.toMillis())<30000);
  assert.deepEqual(await ctx.store.get('faceStations',stationId),station);assert.deepEqual(await ctx.store.get('faceEnrollmentLocks',employeeId),lock);assert.equal(await harness.owner.get('faceProfiles',employeeId),null);
  await assert.rejects(requestFaceCapture(ctx,requestId),/ảnh trước/);
  await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:2,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
  for(const patch of [{samplesCaptured:1},{captureConsumedSeq:1},{status:'COMPLETED'},{captureRequestSeq:2,captureRequestedAt:ctx.store.stamp(),samplesCaptured:1}])await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,patch),permissionDenied);
  await assert.rejects(ctx.store.set('faceProfiles',employeeId,{employeeId,enabled:true,version:1}),permissionDenied);
  assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).samplesCaptured,0);
  // A rejected picture consumes one request without inventing an accepted sample.
  await harness.owner.update('faceEnrollmentCommands',requestId,{captureConsumedSeq:1,message:'Ảnh chưa rõ'});
  assert.equal(await requestFaceCapture(ctx,requestId),2);assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).samplesCaptured,0);
  await harness.owner.update('faceEnrollmentCommands',requestId,{captureConsumedSeq:2,samplesCaptured:1,message:'Đã lưu ảnh'});
  assert.equal(await requestFaceCapture(ctx,requestId),3);assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).samplesCaptured,1);
});

test('manual face integration: rules reject forged clicks for unauthorized users and invalid current worker/reservation state',options,async()=>{
  const{ctx,employeeId,stationId,requestId,prefix}=await fixture();
  const originalCommand=withoutId(await harness.owner.get('faceEnrollmentCommands',requestId)),originalStation=withoutId(await harness.owner.get('faceStations',stationId)),originalLock=withoutId(await harness.owner.get('faceEnrollmentLocks',employeeId));
  const cases=[
    ['command',{status:'PENDING'}],['command',{status:'FINALIZING'}],['command',{status:'COMPLETED'}],['command',{captureMode:'AUTO'}],['command',{action:'DELETE'}],['command',{cancelRequested:true}],['command',{requestedBy:'another-admin'}],['command',{requestId:'wrong-id'}],['command',{sampleCount:9}],['command',{samplesCaptured:10}],['command',{captureRequestSeq:1}],['command',{captureRequestSeq:100,captureConsumedSeq:100}],['command',{captureRequestSeq:-1,captureConsumedSeq:-1}],['command',{captureRequestSeq:0.5,captureConsumedSeq:0.5}],
    ['station',{cameraReady:false}],['station',{mode:'demo'}],['station',{lastSeenAt:new Date(Date.now()-120000)}],['station',{lastSeenAt:new Date(Date.now()+120000)}],['station',{sessionId:'replacement-boot'}],['station',{faceDeviceId:'replacement-camera'}],['station',{pendingCommandId:'replacement-command'}],
    ['lock',{commandId:'replacement-command'}],['lock',{stationId:'replacement-station'}],['lock',{employeeId:'replacement-employee'}],['employee',{active:false}]
  ];
  for(const[kind,patch]of cases){
    await harness.owner.set('faceEnrollmentCommands',requestId,originalCommand);await harness.owner.set('faceStations',stationId,{...originalStation,lastSeenAt:new Date()});await harness.owner.set('faceEnrollmentLocks',employeeId,originalLock);await harness.owner.update('employees',employeeId,{active:true});
    const target=kind==='command'?['faceEnrollmentCommands',requestId]:kind==='station'?['faceStations',stationId]:kind==='lock'?['faceEnrollmentLocks',employeeId]:['employees',employeeId];
    await harness.owner.update(...target,patch);
    await assert.rejects(requestFaceCapture(ctx,requestId),`${kind}: ${JSON.stringify(patch)}`);
    await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:1,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
  }
  await harness.owner.set('faceEnrollmentCommands',requestId,originalCommand);await harness.owner.set('faceStations',stationId,{...originalStation,lastSeenAt:new Date()});await harness.owner.set('faceEnrollmentLocks',employeeId,originalLock);await harness.owner.update('employees',employeeId,{active:true});
  const other=await harness.context(`${prefix}-other-admin`),employee=await harness.context(`${prefix}-employee-account`,'EMPLOYEE',employeeId),inactive=await harness.context(`${prefix}-inactive-admin`);await harness.owner.update('users',inactive.user.uid,{active:false});
  for(const forbidden of [other,employee,inactive]){
    await assert.rejects(requestFaceCapture(forbidden,requestId));await assert.rejects(forbidden.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:1,captureRequestedAt:forbidden.store.stamp()}),permissionDenied);
  }
  await assert.rejects(harness.storeFor(`${prefix}-anonymous`,'anonymous').update('faceEnrollmentCommands',requestId,{captureRequestSeq:1,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
  assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).captureRequestSeq,0);assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).samplesCaptured,0);
});

test('manual face integration: concurrent clicks produce one request and 100 is the final bounded attempt',options,async()=>{
  const{ctx,requestId}=await fixture();
  const results=await Promise.allSettled([requestFaceCapture(ctx,requestId),requestFaceCapture(ctx,requestId)]);
  assert.equal(results.filter(row=>row.status==='fulfilled').length,1);assert.equal(results.filter(row=>row.status==='rejected').length,1);assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).captureRequestSeq,1);
  await harness.owner.update('faceEnrollmentCommands',requestId,{captureRequestSeq:99,captureConsumedSeq:99,samplesCaptured:9});
  assert.equal(await requestFaceCapture(ctx,requestId),100);assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).samplesCaptured,9);
  await harness.owner.update('faceEnrollmentCommands',requestId,{captureConsumedSeq:100});await assert.rejects(requestFaceCapture(ctx,requestId),/ảnh trước/);
  await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:101,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
});
