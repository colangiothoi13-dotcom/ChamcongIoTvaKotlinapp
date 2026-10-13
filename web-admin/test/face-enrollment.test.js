import test from 'node:test';
import assert from 'node:assert/strict';
import {memoryStore} from '../src/demo.js';
import {faceStationAvailable,faceCommandStatus,requestFaceEnrollment,cancelFaceEnrollment,renderFaceEmployeeControls,createFaceEnrollmentActions,faceSubscriptionQueries} from '../src/face-enrollment.js';

const NOW=1700000000000;
const station=(extra={})=>({id:'GATE-01',stationId:'GATE-01',faceDeviceId:'CAM-01',sessionId:'session-one',mode:'firebase',cameraReady:true,lastSeenAt:new Date(NOW),pendingCommandId:null,sensor:'OV5640',...extra});
function context(extra={}){
  const data=structuredClone({users:[{id:'admin',role:'ADMIN',active:true}],employees:[{id:'emp-1',fullName:'Nhân viên A',active:true}],faceProfiles:[],faceStations:[station()],faceEnrollmentCommands:[],faceEnrollmentLocks:[],...extra});
  const store=memoryStore(data),events=[],original=store.transaction;
  store.transaction=callback=>original(async tx=>{
    let wrote=false;
    return callback({get:(name,id)=>{assert.equal(wrote,false,'transaction cannot read after writing');events.push(['get',name,id]);return tx.get(name,id);},set:(...args)=>{wrote=true;events.push(['set',...args]);return tx.set(...args);},update:(...args)=>{wrote=true;events.push(['update',...args]);return tx.update(...args);}});
  });
  const ctx={data,store,user:{uid:'admin'},demo:false,loaded:{faceProfiles:true,faceStations:true,faceEnrollmentCommands:true},errors:{},ui:{dialog(options){ctx.dialog=options;},toast(){}},events};
  return ctx;
}
const request=(ctx,extra={})=>requestFaceEnrollment(ctx,{employeeId:'emp-1',stationId:'GATE-01',action:'ENROLL',now:NOW,...extra});

test('station readiness requires current Firebase camera and credible heartbeat boundaries',()=>{
  for(const age of [-30000,0,90000])assert.equal(faceStationAvailable(station({lastSeenAt:new Date(NOW-age)}),NOW),true);
  for(const extra of [{lastSeenAt:new Date(NOW+30001)},{lastSeenAt:new Date(NOW-90001)},{lastSeenAt:null},{mode:'demo'},{cameraReady:false},{sessionId:''},{faceDeviceId:'cam/slash'},{stationId:'OTHER'},{stationId:''}])assert.equal(faceStationAvailable(station(extra),NOW),false,JSON.stringify(extra));
  assert.deepEqual(faceSubscriptionQueries.faceEnrollmentCommands,{orderBy:[['requestedAt','desc']],limit:100});
});

test('enrollment writes exactly the command and two reservations from fresh transaction data',async()=>{
  const ctx=context();ctx.data.faceStations[0].sessionId='new-session';
  const id=await request(ctx),command=await ctx.store.get('faceEnrollmentCommands',id);
  assert.equal(command.sessionId,'new-session');assert.equal(command.requestId,id);assert.equal(command.employeeId,'emp-1');assert.equal(command.requestedBy,'admin');assert.equal(command.status,'PENDING');assert.equal(command.sampleCount,10);assert.equal(command.samplesCaptured,0);assert.equal(command.cancelRequested,false);assert.equal(command.requestedAt instanceof Date,true);
  assert.deepEqual(Object.keys(command).filter(key=>key!=='id').sort(),['requestId','employeeId','stationId','deviceId','sessionId','action','status','requestedBy','requestedAt','sampleCount','samplesCaptured','cancelRequested','message'].sort());
  assert.equal((await ctx.store.get('faceStations','GATE-01')).pendingCommandId,id);
  const lock=await ctx.store.get('faceEnrollmentLocks','emp-1');assert.equal(lock.commandId,id);assert.equal(lock.stationId,'GATE-01');
  assert.deepEqual(ctx.events.filter(row=>row[0]!=='get').map(row=>row.slice(0,2)),[['set','faceEnrollmentCommands'],['update','faceStations'],['set','faceEnrollmentLocks']]);
  assert.deepEqual(ctx.data.faceProfiles,[]);
});

test('delete follows the same active employee reservations with zero samples',async()=>{
  const ctx=context(),id=await request(ctx,{action:'DELETE'}),command=await ctx.store.get('faceEnrollmentCommands',id);
  assert.equal(command.action,'DELETE');assert.equal(command.sampleCount,0);assert.equal(command.status,'PENDING');
  assert.equal((await ctx.store.get('faceEnrollmentLocks','emp-1')).commandId,id);
  assert.deepEqual(ctx.data.faceProfiles,[]);
});

test('request rejects demo, inactive admins/employees, unsafe ids and unavailable stations before writes',async()=>{
  for(const mutate of [ctx=>ctx.demo=true,ctx=>ctx.user=null,ctx=>ctx.data.users[0].role='EMPLOYEE',ctx=>ctx.data.users[0].active=false,ctx=>ctx.data.employees[0].active=false,ctx=>ctx.data.faceStations[0].cameraReady=false,ctx=>ctx.data.faceStations[0].mode='demo',ctx=>ctx.data.faceStations[0].lastSeenAt=new Date(NOW-90001),ctx=>ctx.data.faceStations[0].sessionId='']){
    const ctx=context();mutate(ctx);await assert.rejects(request(ctx));assert.equal(ctx.events.filter(row=>row[0]!=='get').length,0);assert.deepEqual(ctx.data.faceEnrollmentCommands,[]);
  }
  for(const extra of [{employeeId:'bad/id'},{stationId:'../bad'},{action:'WRITE_PROFILE'}])await assert.rejects(request(context(),extra));
  const ctx=context();ctx.data.employees[0].active=false;await assert.rejects(request(ctx,{action:'DELETE'}));
});

test('live station and employee locks reject unfinished requests, including another station',async()=>{
  for(const status of ['PENDING','CAPTURING','FINALIZING']){
    for(const reserved of ['station','employee']){
      const ctx=context({faceEnrollmentCommands:[{id:'old-command',employeeId:'emp-1',stationId:'OTHER',status}]});
      if(reserved==='station')ctx.data.faceStations[0].pendingCommandId='old-command';
      else ctx.data.faceEnrollmentLocks.push({id:'emp-1',commandId:'old-command',stationId:'OTHER'});
      await assert.rejects(request(ctx),/chưa hoàn tất/);assert.equal(ctx.events.filter(row=>row[0]!=='get').length,0);
    }
  }
  for(const oldId of ['', 'unsafe/id','missing']){const ctx=context();ctx.data.faceStations[0].pendingCommandId=oldId;await assert.rejects(request(ctx));}
});

test('terminal reservations can be replaced and matching previous commands are read once',async()=>{
  for(const status of ['COMPLETED','FAILED','CANCELLED']){
    const ctx=context({faceEnrollmentCommands:[{id:'old-command',status}],faceEnrollmentLocks:[{id:'emp-1',commandId:'old-command',stationId:'GATE-01'}]});
    ctx.data.faceStations[0].pendingCommandId='old-command';const id=await request(ctx);
    assert.equal((await ctx.store.get('faceEnrollmentLocks','emp-1')).commandId,id);
    assert.equal(ctx.events.filter(row=>row[0]==='get'&&row[1]==='faceEnrollmentCommands'&&row[2]==='old-command').length,1);
  }
});

test('cancel only flags pending/capturing requests and leaves worker status and reservations intact',async()=>{
  for(const status of ['PENDING','CAPTURING']){
    const ctx=context(),id=await request(ctx);await ctx.store.update('faceEnrollmentCommands',id,{status});ctx.events.length=0;
    await cancelFaceEnrollment(ctx,id);const command=await ctx.store.get('faceEnrollmentCommands',id);
    assert.equal(command.status,status);assert.equal(command.cancelRequested,true);assert.equal(command.cancelRequestedAt instanceof Date,true);
    assert.equal((await ctx.store.get('faceStations','GATE-01')).pendingCommandId,id);assert.equal((await ctx.store.get('faceEnrollmentLocks','emp-1')).commandId,id);
    const write=ctx.events.find(row=>row[0]==='update');assert.deepEqual(Object.keys(write[3]).sort(),['cancelRequested','cancelRequestedAt']);
    await assert.rejects(cancelFaceEnrollment(ctx,id));
  }
});

test('cancel rechecks current status and permission instead of trusting stale UI data',async()=>{
  for(const status of ['FINALIZING','COMPLETED','FAILED','CANCELLED','UNKNOWN']){const ctx=context({faceEnrollmentCommands:[{id:'command',status,cancelRequested:false}]});await assert.rejects(cancelFaceEnrollment(ctx,'command'));assert.equal(ctx.events.filter(row=>row[0]!=='get').length,0);}
  const ctx=context({faceEnrollmentCommands:[{id:'command',status:'PENDING',cancelRequested:false}]});ctx.data.users[0].active=false;await assert.rejects(cancelFaceEnrollment(ctx,'command'));
  await assert.rejects(cancelFaceEnrollment(context(),'invalid/id'));
});

test('employee controls use saved profile metadata; completed command alone cannot mark registration',()=>{
  const ctx=context({faceEnrollmentCommands:[{id:'command',employeeId:'emp-1',action:'ENROLL',status:'COMPLETED'}]}),employee=ctx.data.employees[0];
  assert.match(renderFaceEmployeeControls(employee,ctx),/Chưa đăng ký/);assert.match(renderFaceEmployeeControls(employee,ctx),/>Đăng ký khuôn mặt</);
  ctx.data.faceProfiles=[{id:'emp-1',enabled:true,version:2}];assert.match(renderFaceEmployeeControls(employee,ctx),/Đã đăng ký · v2/);assert.match(renderFaceEmployeeControls(employee,ctx),/Đăng ký lại/);assert.match(renderFaceEmployeeControls(employee,ctx),/Xóa mẫu mặt/);
  ctx.data.faceEnrollmentCommands[0].status='FINALIZING';const html=renderFaceEmployeeControls(employee,ctx);assert.match(html,/data-action="face-enroll"[^>]*disabled/);assert.match(html,/Đang lưu mẫu/);
  ctx.demo=true;assert.match(renderFaceEmployeeControls(employee,ctx),/Đăng nhập Admin/);
  assert.equal(faceCommandStatus({status:'CAPTURING',samplesCaptured:99}), 'Đang thu ảnh: 10/10');assert.match(faceCommandStatus({status:'CAPTURING',cancelRequested:true}),/Đang hủy/);
});

function trackerFixture(){
  const ctx=context(),watchers=new Map();let target=null;const queuedClose=[];
  const modal={open:false,handlers:new Set(),addEventListener(type,handler){if(type==='close')this.handlers.add(handler);},removeEventListener(type,handler){if(type==='close')this.handlers.delete(handler);}};
  ctx.ui.dialog=options=>{if(modal.open)queuedClose.push(()=>[...modal.handlers].forEach(handler=>handler()));modal.open=true;ctx.dialog=options;const requestId=options.body.match(/data-request-id="([^"]+)"/)?.[1];target=requestId?{dataset:{requestId},innerHTML:''}:null;};
  ctx.store.listenDoc=(name,id,success,error)=>{const entry={success,error,stopped:false};watchers.set(`${name}/${id}`,entry);return()=>entry.stopped=true;};
  const documentImpl={querySelector(selector){return selector==='#modal'?modal:selector==='#face-enrollment-progress'?target:null;}};
  const controller=createFaceEnrollmentActions(ctx,{documentImpl,now:()=>NOW});
  return {ctx,controller,watchers,modal,get target(){return target;},flushClose(){queuedClose.splice(0).forEach(run=>run());},publish(name,id,value){watchers.get(`${name}/${id}`).success(value);}};
}

test('picker confirms physical identity, reserves request and opens live progress without fake success',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});
  assert.match(f.ctx.dialog.body,/personConfirmed/);assert.match(f.ctx.dialog.body,/OV5640/);
  await assert.rejects(f.ctx.dialog.onSubmit({stationId:'GATE-01'}),/xác nhận/);
  await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});
  assert.equal(f.ctx.dialog.title,'Yêu cầu khuôn mặt');f.controller.refresh();assert.match(f.target.innerHTML,/Đang chờ laptop/);assert.doesNotMatch(f.target.innerHTML,/Đã đăng ký khuôn mặt/);
  f.flushClose();assert.ok([...f.watchers.values()].every(w=>!w.stopped),'queued picker close must not stop newly opened progress');
  f.controller.dispose();assert.ok([...f.watchers.values()].every(w=>w.stopped));
});

test('progress waits for the matching saved profile and prevents cancellation during finalizing',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});
  const id=f.target.dataset.requestId;
  f.publish('faceEnrollmentCommands',id,{status:'FINALIZING',action:'ENROLL',samplesCaptured:10});assert.match(f.target.innerHTML,/Đang lưu mẫu/);assert.doesNotMatch(f.target.innerHTML,/data-action="face-command-cancel"/);
  f.publish('faceProfiles','emp-1',{enabled:true,version:1});f.publish('faceEnrollmentCommands',id,{status:'COMPLETED',action:'ENROLL',profileVersion:2,samplesCaptured:10});assert.match(f.target.innerHTML,/đang chờ xác nhận hồ sơ/);
  f.publish('faceProfiles','emp-1',{enabled:true,version:2});assert.match(f.target.innerHTML,/Đã đăng ký khuôn mặt/);
  f.controller.dispose();const html=f.target.innerHTML;f.publish('faceEnrollmentCommands',id,{status:'FAILED',message:'late'});assert.equal(f.target.innerHTML,html);
});

test('reopening a request tracks the chosen employee and confirms deletion through profile metadata',async()=>{
  const f=trackerFixture();f.ctx.data.faceEnrollmentCommands=[{id:'existing-command',employeeId:'emp-1',action:'DELETE',status:'CAPTURING'}];
  await f.controller.onAction('face-progress',{dataset:{id:'emp-1',requestId:'existing-command'}});f.controller.refresh();assert.match(f.target.innerHTML,/Đang xóa mẫu/);
  f.publish('faceEnrollmentCommands','existing-command',{employeeId:'emp-1',action:'DELETE',status:'COMPLETED',profileVersion:3});
  f.publish('faceProfiles','emp-1',{enabled:true,version:2});assert.match(f.target.innerHTML,/đang chờ xác nhận hồ sơ/);
  f.publish('faceProfiles','emp-1',{enabled:false,version:3});assert.match(f.target.innerHTML,/Đã xóa mẫu khuôn mặt/);assert.doesNotMatch(f.target.innerHTML,/data-action="face-command-cancel"/);
  f.controller.dispose();await assert.rejects(f.controller.onAction('face-progress',{dataset:{id:'other-employee',requestId:'existing-command'}}));
});

test('cancel action keeps progress pending until the worker confirms cancellation',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});const id=f.target.dataset.requestId;
  await f.controller.onAction('face-command-cancel',{dataset:{requestId:id}});assert.match(f.target.innerHTML,/Đang hủy; chờ laptop xác nhận/);assert.doesNotMatch(f.target.innerHTML,/Đã hủy yêu cầu/);assert.doesNotMatch(f.target.innerHTML,/data-action="face-command-cancel"/);
  f.publish('faceEnrollmentCommands',id,{status:'CANCELLED',action:'ENROLL'});assert.match(f.target.innerHTML,/Đã hủy yêu cầu/);f.controller.dispose();
});

test('a profile snapshot cannot clear a failed command listener, and subscription errors offer retry',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});const id=f.target.dataset.requestId;
  f.watchers.get(`faceEnrollmentCommands/${id}`).error({code:'permission-denied'});f.publish('faceProfiles','emp-1',{enabled:false,version:1});assert.match(f.target.innerHTML,/Không đủ quyền/);
  f.publish('faceEnrollmentCommands',id,{status:'PENDING',action:'ENROLL'});assert.doesNotMatch(f.target.innerHTML,/Không đủ quyền/);f.controller.dispose();
  f.ctx.errors.faceStations='offline';const html=renderFaceEmployeeControls(f.ctx.data.employees[0],f.ctx);assert.match(html,/data-action="face-subscriptions-retry"/);assert.match(html,/data-action="face-enroll"[^>]*disabled/);
});

test('progress retry restores terminated listeners without creating another command or changing locks',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});const id=f.target.dataset.requestId;
  const old=[...f.watchers.values()];f.watchers.get(`faceEnrollmentCommands/${id}`).error({code:'permission-denied'});assert.match(f.target.innerHTML,/data-action="face-progress-retry"/);
  f.ctx.events.length=0;await f.controller.onAction('face-progress-retry',{dataset:{requestId:id}});assert.ok(old.every(w=>w.stopped));assert.equal(f.ctx.events.length,0);assert.equal(f.ctx.data.faceEnrollmentCommands.length,1);
  f.publish('faceEnrollmentCommands',id,{status:'CAPTURING',action:'ENROLL',samplesCaptured:4});assert.match(f.target.innerHTML,/Đang thu ảnh: 4\/10/);assert.doesNotMatch(f.target.innerHTML,/Không đủ quyền/);f.flushClose();assert.ok([...f.watchers.values()].every(w=>!w.stopped));f.controller.dispose();
});

test('closing progress detaches listeners; later callbacks cannot reopen dialogs or report completion',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});f.controller.refresh();
  const id=f.target.dataset.requestId,html=f.target.innerHTML;f.modal.open=false;[...f.modal.handlers].forEach(handler=>handler());
  assert.ok([...f.watchers.values()].every(w=>w.stopped));f.publish('faceEnrollmentCommands',id,{status:'COMPLETED',action:'ENROLL'});assert.equal(f.target.innerHTML,html);
});

test('session changes during an outstanding transaction cannot open a progress dialog in another session',async()=>{
  const f=trackerFixture();await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});const picker=f.ctx.dialog;
  const original=f.ctx.store.transaction;let release;f.ctx.store.transaction=async callback=>{await new Promise(resolve=>release=resolve);return original(callback);};
  const pending=picker.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});f.controller.dispose();f.ctx.user={uid:'another-admin'};release();await pending;
  assert.equal(f.ctx.dialog,picker);assert.equal(f.watchers.size,0);
});

test('demo controls are disabled and delegated actions cannot create fake requests',async()=>{
  const ctx=context();ctx.demo=true;const controller=createFaceEnrollmentActions(ctx);
  assert.match(renderFaceEmployeeControls(ctx.data.employees[0],ctx),/data-action="face-enroll"[^>]*disabled/);
  await assert.rejects(controller.onAction('face-enroll',{dataset:{id:'emp-1'}}),/Dữ liệu mẫu/);assert.equal(ctx.data.faceEnrollmentCommands.length,0);assert.equal(await controller.onAction('unrelated',{dataset:{}}),false);
});

test('newly saved employee can enroll before the realtime employee list arrives',async()=>{
  const f=trackerFixture();f.ctx.data={...f.ctx.data,employees:[]};
  await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});
  assert.equal(f.ctx.dialog.title,'Đăng ký khuôn mặt');assert.match(f.ctx.dialog.body,/Nhân viên A/);
  assert.equal(f.ctx.events.length,0,'opening the picker must not create enrollment commands');
  await f.ctx.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});
  assert.equal(f.ctx.data.faceEnrollmentCommands[0].employeeId,'emp-1');
  assert.equal(f.ctx.dialog.title,'Yêu cầu khuôn mặt');f.controller.dispose();
});

test('employee lookup rejects invalid, missing and retired records without enrollment writes',async()=>{
  for(const [id,extra,message] of [['invalid/id',{},/Mã nhân viên/],['missing',{},/Không tìm thấy/],['emp-1',{employees:[{id:'emp-1',active:false}]},/ngừng hoạt động/]]){
    const ctx=context(extra);ctx.data={...ctx.data,employees:[]};const controller=createFaceEnrollmentActions(ctx,{now:()=>NOW});
    await assert.rejects(controller.onAction('face-enroll',{dataset:{id}}),message);
    assert.equal(ctx.dialog,undefined);assert.equal(ctx.events.length,0);
  }
});

test('a delayed employee lookup cannot detach a newer face progress tracker',async()=>{
  const f=trackerFixture();let release;const originalGet=f.ctx.store.get;
  f.ctx.store.get=(name,id)=>name==='employees'&&id==='new-employee'?new Promise(resolve=>release=resolve):originalGet(name,id);
  const pending=f.controller.onAction('face-enroll',{dataset:{id:'new-employee'}});
  f.ctx.data.faceEnrollmentCommands=[{id:'existing-command',employeeId:'emp-1',action:'ENROLL',status:'CAPTURING'}];
  await f.controller.onAction('face-progress',{dataset:{id:'emp-1',requestId:'existing-command'}});
  const progress=f.ctx.dialog;release({id:'new-employee',fullName:'New employee',active:true});await pending;
  assert.equal(f.ctx.dialog,progress);assert.ok([...f.watchers.values()].every(w=>!w.stopped));
  f.publish('faceEnrollmentCommands','existing-command',{status:'CAPTURING',action:'ENROLL',samplesCaptured:3});assert.match(f.target.innerHTML,/3\/10/);
  f.controller.dispose();
});

test('delayed employee reads are ignored after action, controller or session changes',async()=>{
  for(const change of ['action','controller','session']){
    const f=trackerFixture();f.ctx.data={...f.ctx.data,employees:[]};let release,current=true;
    f.ctx.isCurrent=()=>current;f.ctx.store.get=()=>new Promise(resolve=>release=resolve);
    const pending=f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});
    if(change==='action')current=false;else if(change==='controller')f.controller.dispose();else f.ctx.user={uid:'another-admin'};
    release({id:'emp-1',fullName:'Nhân viên A',active:true});await pending;
    assert.equal(f.ctx.dialog,undefined);assert.equal(f.watchers.size,0);assert.equal(f.ctx.events.length,0);
  }
});
