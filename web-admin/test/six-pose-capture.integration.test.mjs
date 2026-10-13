// Actual web transactions against loopback demo Firestore Rules. No production
// credentials, camera images, or employee face profiles are used by this suite.
import test from 'node:test';
import assert from 'node:assert/strict';
import {assertEmulatorHost,createHarness} from './firestore-rest-helper.mjs';
import {requestFaceEnrollment,requestFaceCapture} from '../src/face-enrollment.js';
import {SIX_POSE_PLAN,capturePoseStep} from '../src/face-capture-plan.js';

const host=process.env.WEB_FIRESTORE_EMULATOR_HOST;
if(host)assertEmulatorHost(host);
const harness=host?createHarness(host):null,options={skip:!host,timeout:120000};
const suffix=`${Date.now()}-${process.pid}`;let sequence=0;
const permissionDenied=error=>error.status===403;
const withoutId=row=>{const{id,...data}=row;return data;};
const commandKeys=['requestId','employeeId','stationId','deviceId','sessionId','action','status','requestedBy','requestedAt','sampleCount','samplesCaptured','cancelRequested','message','captureMode','captureRequestSeq','captureConsumedSeq','capturePlan'].sort();

async function fixture({capturing=true}={}){
  const prefix=`pose-${suffix}-${++sequence}`,ctx=await harness.context(`${prefix}-admin`);
  const employeeId=`${prefix}-employee`,stationId=`${prefix}-station`;
  await harness.seed('employees',employeeId,{fullName:'Nhân viên kiểm thử góc chụp',active:true});
  await harness.seed('faceStations',stationId,{stationId,faceDeviceId:`${prefix}-cam`,sessionId:`${prefix}-boot`,mode:'firebase',lastSeenAt:new Date(),cameraReady:true,pendingCommandId:null});
  const requestId=await requestFaceEnrollment(ctx,{employeeId,stationId,captureMode:'MANUAL',capturePlan:SIX_POSE_PLAN});
  if(capturing)await harness.owner.update('faceEnrollmentCommands',requestId,{status:'CAPTURING'});
  return{ctx,employeeId,stationId,requestId,prefix};
}

test('six pose integration: guided request creates exactly six-sample manual schema and both reservations',options,async()=>{
  const{ctx,employeeId,stationId,requestId,prefix}=await fixture({capturing:false});
  const command=withoutId(await ctx.store.get('faceEnrollmentCommands',requestId));
  assert.deepEqual(Object.keys(command).sort(),commandKeys);
  assert.deepEqual({...command,requestedAt:'SERVER_TIME'},{requestId,employeeId,stationId,deviceId:`${prefix}-cam`,sessionId:`${prefix}-boot`,action:'ENROLL',status:'PENDING',requestedBy:ctx.user.uid,requestedAt:'SERVER_TIME',sampleCount:6,samplesCaptured:0,cancelRequested:false,message:'',captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,capturePlan:SIX_POSE_PLAN});
  assert.ok(Math.abs(Date.now()-command.requestedAt.toMillis())<30000);
  assert.equal((await ctx.store.get('faceStations',stationId)).pendingCommandId,requestId);
  const lock=await ctx.store.get('faceEnrollmentLocks',employeeId);
  assert.equal(lock.commandId,requestId);assert.equal(lock.stationId,stationId);assert.equal(lock.employeeId,employeeId);
  assert.equal(lock.updatedAt.toMillis(),command.requestedAt.toMillis());
  assert.equal(await harness.owner.get('faceProfiles',employeeId),null);
  await assert.rejects(requestFaceCapture(ctx,requestId),/chưa sẵn sàng/);
});

test('six pose integration: two accepted photos per direction, rejection keeps current pose, and six is the final accepted sample',options,async()=>{
  const{ctx,employeeId,stationId,requestId}=await fixture();
  const expected=[['FRONT',1,true],['FRONT',2,true],['LEFT',1,false],['LEFT',1,true],['LEFT',2,true],['RIGHT',1,true],['RIGHT',2,true]];
  const acceptedCounts=[1,2,2,3,4,5,6];
  let saved=0;
  for(let index=0;index<expected.length;index++){
    const commandBefore=await ctx.store.get('faceEnrollmentCommands',requestId),poseBefore=capturePoseStep(commandBefore.samplesCaptured);
    const[key,next,accepted]=expected[index];
    assert.deepEqual([poseBefore.key,poseBefore.next],[key,next]);assert.equal(poseBefore.complete,false);
    const requestSeq=await requestFaceCapture(ctx,requestId);assert.equal(requestSeq,index+1);
    const pending=await ctx.store.get('faceEnrollmentCommands',requestId);
    assert.equal(pending.samplesCaptured,saved);assert.equal(pending.captureConsumedSeq,index);
    assert.deepEqual(capturePoseStep(pending.samplesCaptured),poseBefore);
    await assert.rejects(requestFaceCapture(ctx,requestId),/ảnh trước/);
    // Admin cannot acknowledge a click or turn a rejected photo into progress.
    await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureConsumedSeq:requestSeq,samplesCaptured:saved+1}),permissionDenied);
    saved+=accepted?1:0;
    await harness.owner.update('faceEnrollmentCommands',requestId,{captureConsumedSeq:requestSeq,samplesCaptured:saved,message:accepted?'Đã lưu ảnh đạt':'Ảnh chưa rõ, chụp lại cùng hướng',captureLastReason:accepted?'quality_ok':'low_quality'});
    const acknowledged=await ctx.store.get('faceEnrollmentCommands',requestId);
    assert.equal(acknowledged.samplesCaptured,acceptedCounts[index]);assert.equal(acknowledged.captureRequestSeq,requestSeq);assert.equal(acknowledged.captureConsumedSeq,requestSeq);
    if(!accepted)assert.deepEqual(capturePoseStep(acknowledged.samplesCaptured),poseBefore);
  }
  const completedSamples=await ctx.store.get('faceEnrollmentCommands',requestId);
  assert.equal(completedSamples.samplesCaptured,6);assert.equal(completedSamples.captureRequestSeq,7);
  assert.equal(capturePoseStep(completedSamples.samplesCaptured).complete,true);
  await assert.rejects(requestFaceCapture(ctx,requestId),/Đã lấy đủ ảnh/);
  await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:8,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
  assert.equal((await ctx.store.get('faceStations',stationId)).pendingCommandId,requestId);
  assert.equal((await ctx.store.get('faceEnrollmentLocks',employeeId)).commandId,requestId);
  assert.equal(await harness.owner.get('faceProfiles',employeeId),null);
  await harness.owner.update('faceEnrollmentCommands',requestId,{status:'FINALIZING'});
  await assert.rejects(requestFaceCapture(ctx,requestId),/chưa sẵn sàng/);
  await harness.owner.update('faceEnrollmentCommands',requestId,{status:'COMPLETED'});
  await assert.rejects(requestFaceCapture(ctx,requestId),/chưa sẵn sàng/);
});

test('six pose integration: Rules deny client total/plan changes and invalid guided contracts cannot send a capture',options,async()=>{
  const{ctx,requestId}=await fixture();
  const original=withoutId(await ctx.store.get('faceEnrollmentCommands',requestId));
  for(const patch of [{sampleCount:2},{sampleCount:10},{capturePlan:'OTHER_PLAN'},{capturePlan:null},{sampleCount:10,capturePlan:null},{samplesCaptured:6},{captureRequestSeq:1,captureRequestedAt:ctx.store.stamp(),sampleCount:2},{captureRequestSeq:1,captureRequestedAt:ctx.store.stamp(),capturePlan:'OTHER_PLAN'}]){
    await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,patch),permissionDenied);
    assert.deepEqual(withoutId(await ctx.store.get('faceEnrollmentCommands',requestId)),original);
  }
  // Even if a trusted worker leaves malformed data, neither the web validation
  // nor a bypassed client write may treat it as a valid six-photo request.
  for(const patch of [{sampleCount:2},{sampleCount:10},{capturePlan:'OTHER_PLAN'},{capturePlan:null}]){
    await harness.owner.set('faceEnrollmentCommands',requestId,{...original,...patch});
    await assert.rejects(requestFaceCapture(ctx,requestId),/Thông tin yêu cầu/);
    await assert.rejects(ctx.store.update('faceEnrollmentCommands',requestId,{captureRequestSeq:1,captureRequestedAt:ctx.store.stamp()}),permissionDenied);
    assert.equal((await ctx.store.get('faceEnrollmentCommands',requestId)).captureRequestSeq,0);
  }
  await harness.owner.set('faceEnrollmentCommands',requestId,original);
  assert.equal(await requestFaceCapture(ctx,requestId),1);
});
