import test from 'node:test';
import assert from 'node:assert/strict';
import {memoryStore} from '../src/demo.js';
import {requestFaceEnrollment,requestFaceCapture,createFaceEnrollmentActions,faceCommandStatus} from '../src/face-enrollment.js';
import {SIX_POSE_PLAN} from '../src/face-capture-plan.js';

const NOW=1700000000000;
const command=(extra={})=>({id:'request-one',requestId:'request-one',employeeId:'emp-1',stationId:'GATE-01',deviceId:'CAM-01',sessionId:'boot-one',action:'ENROLL',status:'CAPTURING',requestedBy:'admin',requestedAt:new Date(NOW),sampleCount:10,samplesCaptured:0,cancelRequested:false,message:'',captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,...extra});
const station=()=>({id:'GATE-01',stationId:'GATE-01',faceDeviceId:'CAM-01',sessionId:'boot-one',mode:'firebase',cameraReady:true,lastSeenAt:new Date(NOW),pendingCommandId:'request-one'});
const baseKeys=['requestId','employeeId','stationId','deviceId','sessionId','action','status','requestedBy','requestedAt','sampleCount','samplesCaptured','cancelRequested','message'];
const withoutId=row=>{const{id,...fields}=row;return fields;};

function context({empty=false}={}){
  const data={users:[{id:'admin',role:'ADMIN',active:true}],employees:[{id:'emp-1',fullName:'Nhân viên A',active:true}],faceProfiles:[],faceStations:[station()],faceEnrollmentCommands:empty?[]:[command()],faceEnrollmentLocks:empty?[]:[{id:'emp-1',employeeId:'emp-1',stationId:'GATE-01',commandId:'request-one'}]};
  if(empty)data.faceStations[0].pendingCommandId=null;
  const store=memoryStore(data),events=[],originalTransaction=store.transaction;
  store.transaction=callback=>originalTransaction(async tx=>{
    let written=false;
    return callback({get:(collection,id)=>{assert.equal(written,false,'fresh reads precede all writes');events.push(['get',collection,id]);return tx.get(collection,id);},set:(...args)=>{written=true;events.push(['set',...args]);return tx.set(...args);},update:(...args)=>{written=true;events.push(['update',...args]);return tx.update(...args);}});
  });
  const ctx={data,store,events,user:{uid:'admin'},demo:false,loaded:{faceProfiles:true,faceStations:true,faceEnrollmentCommands:true},errors:{},ui:{dialog(options){ctx.dialog=options;},toast(){}}};
  return ctx;
}
const shoot=ctx=>requestFaceCapture(ctx,'request-one',{now:NOW});
const writes=ctx=>ctx.events.filter(row=>row[0]!=='get');

function uiFixture({empty=false}={}){
  const ctx=context({empty}),watchers=new Map(),dialogs=[];
  let target=null;
  const modal={open:false,handlers:new Set(),addEventListener(type,fn){if(type==='close')this.handlers.add(fn);},removeEventListener(type,fn){if(type==='close')this.handlers.delete(fn);}};
  ctx.ui.dialog=options=>{ctx.dialog=options;dialogs.push(options);modal.open=true;const id=options.body.match(/id="face-enrollment-progress"[^>]*data-request-id="([^"]+)"/)?.[1];target=id?{dataset:{requestId:id},innerHTML:''}:null;};
  ctx.store.listenDoc=(collection,id,success,error)=>{const entry={success,error,stopped:false};watchers.set(`${collection}/${id}`,entry);return()=>{entry.stopped=true;};};
  const documentImpl={querySelector(selector){return selector==='#modal'?modal:selector==='#face-enrollment-progress'?target:null;}};
  const controller=createFaceEnrollmentActions(ctx,{documentImpl,now:()=>NOW});
  return{ctx,controller,watchers,dialogs,modal,get target(){return target;},publish(value){watchers.get(`faceEnrollmentCommands/${value.id||value.requestId}`).success(value);},close(){modal.open=false;ctx.dialog?.onClose?.();[...modal.handlers].forEach(fn=>fn());}};
}
const open=f=>f.controller.onAction('face-progress',{dataset:{id:'emp-1',requestId:'request-one'}});
const shotButton=html=>html.match(/<button[^>]*data-action="face-capture"[^>]*>[\s\S]*?<\/button>/)?.[0];

test('guided manual creation stores six and its exact plan, without changing legacy defaults',async()=>{
  const ctx=context({empty:true}),id=await requestFaceEnrollment(ctx,{employeeId:'emp-1',stationId:'GATE-01',captureMode:'MANUAL',capturePlan:SIX_POSE_PLAN,now:NOW});
  const saved=await ctx.store.get('faceEnrollmentCommands',id);assert.equal(saved.sampleCount,6);assert.equal(saved.capturePlan,SIX_POSE_PLAN);assert.equal(saved.captureRequestSeq,0);assert.equal(saved.samplesCaptured,0);
  for(const options of [{capturePlan:SIX_POSE_PLAN},{captureMode:'MANUAL',capturePlan:'wrong'},{captureMode:'MANUAL',capturePlan:null},{captureMode:'MANUAL',capturePlan:SIX_POSE_PLAN,action:'DELETE'}]){
    const invalid=context({empty:true});await assert.rejects(requestFaceEnrollment(invalid,{employeeId:'emp-1',stationId:'GATE-01',now:NOW,...options}),/Kế hoạch|Cách chụp/);assert.deepEqual(invalid.events,[]);
  }
});

test('six-picture captures permit the last image once and reject a seventh or malformed plan',async()=>{
  const ctx=context();Object.assign(ctx.data.faceEnrollmentCommands[0],{sampleCount:6,capturePlan:SIX_POSE_PLAN,samplesCaptured:5,captureRequestSeq:5,captureConsumedSeq:5});
  assert.equal(await shoot(ctx),6);assert.equal(ctx.data.faceEnrollmentCommands[0].samplesCaptured,5);
  await ctx.store.update('faceEnrollmentCommands','request-one',{captureConsumedSeq:6,samplesCaptured:6});ctx.events.length=0;await assert.rejects(shoot(ctx),/Đã lấy đủ/);assert.deepEqual(writes(ctx),[]);
  for(const extra of [{sampleCount:6},{sampleCount:6,capturePlan:'wrong'},{sampleCount:10,capturePlan:SIX_POSE_PLAN}]){const invalid=context();Object.assign(invalid.data.faceEnrollmentCommands[0],extra);await assert.rejects(shoot(invalid),/Thông tin/);assert.deepEqual(writes(invalid),[]);}
});

test('guided progress uses six and advances front, left and right only from accepted samples',async()=>{
  const f=uiFixture();Object.assign(f.ctx.data.faceEnrollmentCommands[0],{sampleCount:6,capturePlan:SIX_POSE_PLAN});await open(f);f.controller.refresh();
  assert.match(f.target.innerHTML,/max="6"/);assert.match(f.target.innerHTML,/Chụp ảnh \(1\/6\)/);assert.match(f.target.innerHTML,/Bước 1\/3.*Nhìn thẳng/);
  for(const[saved,step,label]of [[2,2,'Quay sang trái'],[4,3,'Quay sang phải']]){
    const data=command({sampleCount:6,capturePlan:SIX_POSE_PLAN,samplesCaptured:saved,captureRequestSeq:saved,captureConsumedSeq:saved});f.publish(data);assert.match(f.target.innerHTML,new RegExp(`Bước ${step}/3.*${label}`));
    f.publish({...data,captureRequestSeq:saved+1,captureConsumedSeq:saved+1,captureLastReason:'low_quality',message:'Ảnh mờ; chụp lại.'});assert.match(f.target.innerHTML,new RegExp(`Bước ${step}/3.*${label}`));assert.match(f.target.innerHTML,/Ảnh 1\/2/);
  }
  f.publish(command({sampleCount:6,capturePlan:SIX_POSE_PLAN,samplesCaptured:6,captureRequestSeq:6,captureConsumedSeq:6,status:'FINALIZING'}));assert.match(f.target.innerHTML,/Đã đủ 6 ảnh/);assert.match(shotButton(f.target.innerHTML),/disabled/);f.controller.dispose();
});

test('manual enrollment adds exactly three initialized fields, while omitted/AUTO and DELETE keep the old 13-field schema',async()=>{
  for(const options of [{captureMode:'MANUAL'},{},{captureMode:'AUTO'},{action:'DELETE'}]){
    const ctx=context({empty:true}),id=await requestFaceEnrollment(ctx,{employeeId:'emp-1',stationId:'GATE-01',now:NOW,...options});
    const saved=withoutId(await ctx.store.get('faceEnrollmentCommands',id)),manual=options.captureMode==='MANUAL';
    assert.deepEqual(Object.keys(saved).sort(),[...baseKeys,...(manual?['captureMode','captureRequestSeq','captureConsumedSeq']:[])].sort());
    if(manual){assert.equal(saved.captureMode,'MANUAL');assert.equal(saved.captureRequestSeq,0);assert.equal(saved.captureConsumedSeq,0);}
    assert.equal(saved.status,'PENDING');assert.equal(saved.samplesCaptured,0);assert.equal(saved.sampleCount,options.action==='DELETE'?0:10);
    assert.equal((await ctx.store.get('faceStations','GATE-01')).pendingCommandId,id);
    assert.equal((await ctx.store.get('faceEnrollmentLocks','emp-1')).commandId,id);assert.deepEqual(ctx.data.faceProfiles,[]);
  }
});

test('invalid capture mode and manual deletion are rejected before allocating or writing a command',async()=>{
  for(const options of [{captureMode:'automatic'},{captureMode:null},{captureMode:true},{captureMode:'MANUAL',action:'DELETE'}]){
    const ctx=context({empty:true});let allocations=0;ctx.store.id=()=>{allocations++;return'new-id';};
    await assert.rejects(requestFaceEnrollment(ctx,{employeeId:'emp-1',stationId:'GATE-01',now:NOW,...options}),/Cách chụp ảnh/);
    assert.equal(allocations,0);assert.deepEqual(ctx.events,[]);assert.deepEqual(ctx.data.faceEnrollmentCommands,[]);
  }
});

test('a manual click performs five fresh reads and writes only the next request sequence and server timestamp',async()=>{
  const ctx=context();ctx.data.faceEnrollmentCommands[0].samplesCaptured=4;
  const before=withoutId(await ctx.store.get('faceEnrollmentCommands','request-one'));
  const stale={...ctx.data,faceEnrollmentCommands:[command({samplesCaptured:9,captureRequestSeq:55,captureConsumedSeq:55})]};ctx.data=stale;
  assert.equal(await shoot(ctx),1);
  assert.deepEqual(ctx.events.filter(row=>row[0]==='get').map(row=>row.slice(1)),[['users','admin'],['faceEnrollmentCommands','request-one'],['employees','emp-1'],['faceStations','GATE-01'],['faceEnrollmentLocks','emp-1']]);
  assert.equal(writes(ctx).length,1);assert.deepEqual(writes(ctx)[0].slice(0,3),['update','faceEnrollmentCommands','request-one']);
  assert.deepEqual(Object.keys(writes(ctx)[0][3]).sort(),['captureRequestSeq','captureRequestedAt']);
  const saved=withoutId(await ctx.store.get('faceEnrollmentCommands','request-one')),{captureRequestedAt,...remaining}=saved;
  assert.ok(captureRequestedAt instanceof Date);assert.deepEqual(remaining,{...before,captureRequestSeq:1});
  assert.equal(saved.samplesCaptured,4);assert.equal(saved.status,'CAPTURING');assert.deepEqual(ctx.data.faceProfiles,[]);
  ctx.events.length=0;await assert.rejects(shoot(ctx),/ảnh trước/);assert.deepEqual(writes(ctx),[]);
});

test('fresh authorization, worker state, reservations and bounded counters are checked before a manual write',async()=>{
  const cases={
    demo:ctx=>ctx.demo=true,unauthenticated:ctx=>ctx.user=null,employeeRole:ctx=>ctx.data.users[0].role='EMPLOYEE',inactiveAdmin:ctx=>ctx.data.users[0].active=false,
    otherOwner:ctx=>ctx.data.faceEnrollmentCommands[0].requestedBy='another-admin',wrongRequestId:ctx=>ctx.data.faceEnrollmentCommands[0].requestId='another-request',wrongSampleCount:ctx=>ctx.data.faceEnrollmentCommands[0].sampleCount=9,
    pending:ctx=>ctx.data.faceEnrollmentCommands[0].status='PENDING',finalizing:ctx=>ctx.data.faceEnrollmentCommands[0].status='FINALIZING',completed:ctx=>ctx.data.faceEnrollmentCommands[0].status='COMPLETED',auto:ctx=>ctx.data.faceEnrollmentCommands[0].captureMode='AUTO',delete:ctx=>ctx.data.faceEnrollmentCommands[0].action='DELETE',cancelled:ctx=>ctx.data.faceEnrollmentCommands[0].cancelRequested=true,
    outstanding:ctx=>ctx.data.faceEnrollmentCommands[0].captureRequestSeq=1,negative:ctx=>Object.assign(ctx.data.faceEnrollmentCommands[0],{captureRequestSeq:-1,captureConsumedSeq:-1}),fraction:ctx=>Object.assign(ctx.data.faceEnrollmentCommands[0],{captureRequestSeq:0.5,captureConsumedSeq:0.5}),string:ctx=>Object.assign(ctx.data.faceEnrollmentCommands[0],{captureRequestSeq:'0',captureConsumedSeq:'0'}),limit:ctx=>Object.assign(ctx.data.faceEnrollmentCommands[0],{captureRequestSeq:100,captureConsumedSeq:100}),tenSamples:ctx=>ctx.data.faceEnrollmentCommands[0].samplesCaptured=10,negativeSamples:ctx=>ctx.data.faceEnrollmentCommands[0].samplesCaptured=-1,fractionalSamples:ctx=>ctx.data.faceEnrollmentCommands[0].samplesCaptured=0.5,
    inactiveEmployee:ctx=>ctx.data.employees[0].active=false,offlineCamera:ctx=>ctx.data.faceStations[0].cameraReady=false,staleHeartbeat:ctx=>ctx.data.faceStations[0].lastSeenAt=new Date(NOW-90001),newSession:ctx=>ctx.data.faceStations[0].sessionId='boot-two',newDevice:ctx=>ctx.data.faceStations[0].faceDeviceId='CAM-02',newStationRequest:ctx=>ctx.data.faceStations[0].pendingCommandId='replacement',newEmployeeLock:ctx=>ctx.data.faceEnrollmentLocks[0].commandId='replacement',wrongLockStation:ctx=>ctx.data.faceEnrollmentLocks[0].stationId='GATE-02',wrongLockEmployee:ctx=>ctx.data.faceEnrollmentLocks[0].employeeId='emp-2'
  };
  for(const[label,mutate]of Object.entries(cases)){
    const ctx=context();mutate(ctx);await assert.rejects(shoot(ctx),label);assert.deepEqual(writes(ctx),[],label);
  }
  const ctx=context();await assert.rejects(requestFaceCapture(ctx,'unsafe/id',{now:NOW}));assert.deepEqual(ctx.events,[]);
});

test('the 100th bounded request is permitted once and never increases accepted samples',async()=>{
  const ctx=context();Object.assign(ctx.data.faceEnrollmentCommands[0],{captureRequestSeq:99,captureConsumedSeq:99,samplesCaptured:9});
  assert.equal(await shoot(ctx),100);assert.equal((await ctx.store.get('faceEnrollmentCommands','request-one')).samplesCaptured,9);
  await ctx.store.update('faceEnrollmentCommands','request-one',{captureConsumedSeq:100});ctx.events.length=0;
  await assert.rejects(shoot(ctx),/ảnh trước/);assert.deepEqual(writes(ctx),[]);
});

test('web enrollment picker creates a manual request with no capture before the worker is ready',async()=>{
  const f=uiFixture({empty:true});await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});
  assert.match(f.ctx.dialog.submitLabel,/Mở camera.*6 ảnh/);
  await assert.rejects(f.ctx.dialog.onSubmit({stationId:'GATE-01'}),/xác nhận/);assert.deepEqual(f.ctx.data.faceEnrollmentCommands,[]);
  await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});f.controller.refresh();
  const saved=f.ctx.data.faceEnrollmentCommands[0];assert.equal(saved.captureMode,'MANUAL');assert.equal(saved.captureRequestSeq,0);assert.equal(saved.captureConsumedSeq,0);
  assert.match(f.target.innerHTML,/Đang chờ laptop/);assert.match(shotButton(f.target.innerHTML),/ disabled/);assert.equal(saved.samplesCaptured,0);f.controller.dispose();
});

test('capture button locks immediately, deduplicates clicks and remains locked until worker acknowledgement',async()=>{
  const f=uiFixture();await open(f);f.controller.refresh();assert.doesNotMatch(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Chụp ảnh \(1\/10\)/);
  const original=f.ctx.store.transaction;let release,transactions=0;
  f.ctx.store.transaction=async callback=>{transactions++;await new Promise(resolve=>release=resolve);return original(callback);};
  const pending=f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}});assert.match(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Đang chụp…/);
  assert.equal(await f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}}),true);assert.equal(transactions,1);
  release();await pending;assert.match(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Đang chụp ảnh: 0\/10/);
  assert.equal(f.ctx.data.faceEnrollmentCommands[0].samplesCaptured,0);
  const acknowledged=command({captureRequestSeq:1,captureConsumedSeq:1,message:'Ảnh chưa rõ, hãy giữ mặt trong khung.'});f.publish(acknowledged);
  assert.doesNotMatch(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Chụp ảnh \(1\/10\)/);assert.match(f.target.innerHTML,/Ảnh chưa rõ/);assert.match(f.target.innerHTML,/Chờ bạn bấm Chụp ảnh: 0\/10/);
  f.publish(command({captureRequestSeq:1,captureConsumedSeq:1,samplesCaptured:1,message:'Đã lưu ảnh.'}));assert.match(f.target.innerHTML,/Chụp ảnh \(2\/10\)/);assert.match(f.target.innerHTML,/value="1"/);f.controller.dispose();
});

test('progress never enables capture for wrong states, cancelled requests, ten samples, outstanding requests or listener errors',async()=>{
  const f=uiFixture();await open(f);
  for(const extra of [{status:'PENDING'},{status:'FINALIZING'},{status:'FAILED'},{status:'COMPLETED'},{cancelRequested:true},{samplesCaptured:10},{captureRequestSeq:1},{captureRequestSeq:100,captureConsumedSeq:100}]){
    f.publish(command(extra));assert.match(shotButton(f.target.innerHTML),/ disabled/,JSON.stringify(extra));
  }
  f.publish(command());f.watchers.get('faceEnrollmentCommands/request-one').error({code:'permission-denied'});assert.match(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Không đủ quyền/);
  f.publish(command({message:'<script>bad</script>'}));assert.doesNotMatch(f.target.innerHTML,/<script>/);assert.match(f.target.innerHTML,/&lt;script&gt;/);assert.doesNotMatch(shotButton(f.target.innerHTML),/ disabled/);f.controller.dispose();
});

test('late capture results and listener callbacks cannot reopen or mutate a closed progress window',async()=>{
  const f=uiFixture();await open(f);const original=f.ctx.store.transaction;let release;
  f.ctx.store.transaction=async callback=>{await new Promise(resolve=>release=resolve);return original(callback);};
  const pending=f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}}),target=f.target,html=target.innerHTML,dialogs=f.dialogs.length,watcher=f.watchers.get('faceEnrollmentCommands/request-one');
  f.close();release();await pending;watcher.success(command({captureRequestSeq:1,captureConsumedSeq:1,samplesCaptured:1}));watcher.error(new Error('late failure'));
  assert.equal(target.innerHTML,html);assert.equal(f.dialogs.length,dialogs);assert.ok([...f.watchers.values()].every(entry=>entry.stopped));assert.equal(f.ctx.data.faceEnrollmentCommands[0].samplesCaptured,0);
  await assert.rejects(f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}}),/Cửa sổ/);
});

test('a transient capture request failure leaves retry available without faking a request or accepted sample',async()=>{
  const f=uiFixture();await open(f);f.controller.refresh();
  const original=f.ctx.store.transaction;f.ctx.store.transaction=async()=>{throw new Error('Tạm mất kết nối laptop');};
  await assert.rejects(f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}}),/Tạm mất kết nối/);
  assert.doesNotMatch(shotButton(f.target.innerHTML),/ disabled/);assert.match(f.target.innerHTML,/Tạm mất kết nối laptop/);assert.equal(f.ctx.data.faceEnrollmentCommands[0].captureRequestSeq,0);assert.equal(f.ctx.data.faceEnrollmentCommands[0].samplesCaptured,0);
  f.ctx.store.transaction=original;
  await f.controller.onAction('face-capture',{dataset:{requestId:'request-one'}});assert.doesNotMatch(f.target.innerHTML,/Tạm mất kết nối laptop/);assert.equal(f.ctx.data.faceEnrollmentCommands[0].captureRequestSeq,1);assert.equal(f.ctx.data.faceEnrollmentCommands[0].samplesCaptured,0);f.controller.dispose();
});

test('manual and legacy status text describe requests without pretending an accepted photo exists',()=>{
  assert.equal(faceCommandStatus(command()),'Chờ bạn bấm Chụp ảnh: 0/10');
  assert.equal(faceCommandStatus(command({captureRequestSeq:1,samplesCaptured:4})),'Đang chụp ảnh: 4/10');
  assert.equal(faceCommandStatus(command({captureMode:undefined,samplesCaptured:4})),'Đang thu ảnh: 4/10');
});
