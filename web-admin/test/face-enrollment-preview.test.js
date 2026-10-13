import test from 'node:test';
import assert from 'node:assert/strict';
import {memoryStore} from '../src/demo.js';
import {createFaceEnrollmentActions} from '../src/face-enrollment.js';

const NOW=1700000000000;
const command=(extra={})=>({id:'request-one',requestId:'request-one',employeeId:'emp-1',stationId:'GATE-01',action:'ENROLL',status:'CAPTURING',sampleCount:10,samplesCaptured:0,cancelRequested:false,...extra});

// These fixtures model the existing dialog lifecycle, not a browser or a camera.
// Only the preview factory is mocked; enrollment transactions use the real store.
function fixture({commands=[],previewError=false}={}){
  const data={
    users:[{id:'admin',role:'ADMIN',active:true}],
    employees:[{id:'emp-1',fullName:'Nhân viên A',active:true}],
    faceProfiles:[],faceEnrollmentCommands:structuredClone(commands),faceEnrollmentLocks:[],
    faceStations:[{id:'GATE-01',stationId:'GATE-01',faceDeviceId:'CAM-01',sessionId:'session-one',mode:'firebase',cameraReady:true,lastSeenAt:new Date(NOW),pendingCommandId:null,sensor:'OV5640'}]
  };
  const store=memoryStore(data),writes=[],watchers=new Map(),instances=[],dialogs=[],queuedClose=[];
  const originalBatch=store.batch;
  store.batch=async operations=>{writes.push(...structuredClone(operations));return originalBatch(operations);};
  let progress=null,camera=null,beforeStarts=0;
  const modal={open:false,handlers:new Set(),addEventListener(type,handler){if(type==='close')this.handlers.add(handler);},removeEventListener(type,handler){if(type==='close')this.handlers.delete(handler);}};
  const ctx={data,store,user:{uid:'admin'},demo:false,loaded:{faceProfiles:true,faceStations:true,faceEnrollmentCommands:true},errors:{},ui:{toast(){},dialog(options){
    const previous=dialogs.at(-1);
    if(modal.open)queuedClose.push(()=>{previous?.onClose?.();[...modal.handlers].forEach(handler=>handler());});
    modal.open=true;dialogs.push(options);
    const progressId=options.body.match(/id="face-enrollment-progress"[^>]*data-request-id="([^"]+)"/)?.[1];
    const cameraId=options.body.match(/id="face-enrollment-camera"[^>]*data-request-id="([^"]+)"/)?.[1];
    progress=progressId?{dataset:{requestId:progressId},innerHTML:''}:null;
    camera=cameraId?{dataset:{requestId:cameraId},innerHTML:'preview-markup'}:null;
  }}};
  store.listenDoc=(collection,id,success,error)=>{
    const entry={success,error,stopped:false};watchers.set(`${collection}/${id}`,entry);
    return()=>{entry.stopped=true;};
  };
  const documentImpl={querySelector(selector){return selector==='#modal'?modal:selector==='#face-enrollment-progress'?progress:selector==='#face-enrollment-camera'?camera:null;}};
  const previewFactory=(root,options)=>{
    const instance={root,options,starts:0,stops:0,disposals:0,start(){
      this.starts++;options.onStart?.();
      if(previewError)root.innerHTML='Không kết nối được camera để xem ảnh.';
      return Promise.resolve();
    },stop(){this.stops++;},dispose(){this.disposals++;}};
    instances.push(instance);return instance;
  };
  const controller=createFaceEnrollmentActions(ctx,{documentImpl,now:()=>NOW,previewFactory,beforePreviewStart(){beforeStarts++;}});
  return {
    ctx,controller,store,writes,watchers,instances,dialogs,modal,
    get progress(){return progress;},get camera(){return camera;},get beforeStarts(){return beforeStarts;},
    get dialog(){return dialogs.at(-1);},
    publish(id,value){watchers.get(`faceEnrollmentCommands/${id}`).success(value);},
    flushClose(){queuedClose.splice(0).forEach(run=>run());},
    close(){modal.open=false;dialogs.at(-1)?.onClose?.();[...modal.handlers].forEach(handler=>handler());},
    replace(){ctx.ui.dialog({title:'Another dialog',body:'<p>Other content</p>'});}
  };
}

async function begin(f){
  await f.controller.onAction('face-enroll',{dataset:{id:'emp-1'}});
  await f.dialog.onSubmit({stationId:'GATE-01',personConfirmed:'yes'});
  f.controller.refresh();return f.progress.dataset.requestId;
}

async function reopen(f,id='request-one'){
  await f.controller.onAction('face-progress',{dataset:{id:'emp-1',requestId:id}});
  f.controller.refresh();
}

test('new enrollment opens the station-bound preview once and keeps its node during progress updates',async()=>{
  const f=fixture(),id=await begin(f),camera=f.camera,instance=f.instances[0];
  assert.equal(f.dialog.title,'Yêu cầu khuôn mặt');assert.equal(f.dialog.wide,true);
  assert.ok(camera,'enrollment needs a camera sibling beside its progress container');
  assert.equal(camera.dataset.requestId,id);assert.notEqual(camera,f.progress);
  assert.equal(instance.root,camera);assert.equal(instance.options.expectedStationId,'GATE-01');
  assert.equal(instance.starts,1);assert.equal(f.beforeStarts,1,'the regular viewer must yield before inline preview starts');
  assert.equal(f.writes.length,3,'only the explicit command and its two reservations are created');
  f.writes.length=0;
  for(const captured of [0,1,4,9]){
    f.publish(id,command({id,requestId:id,status:'CAPTURING',samplesCaptured:captured}));
    f.controller.refresh();
    assert.equal(f.camera,camera);assert.equal(camera.innerHTML,'preview-markup');
    assert.match(f.progress.innerHTML,new RegExp(`Đang thu ảnh: ${captured}/10`));
  }
  assert.equal(f.instances.length,1);assert.equal(instance.starts,1);assert.equal(instance.disposals,0);
  assert.equal(f.beforeStarts,1);assert.deepEqual(f.writes,[]);
  f.flushClose();assert.equal(instance.disposals,0,'the queued picker close belongs to the previous dialog');
  assert.ok([...f.watchers.values()].every(entry=>!entry.stopped));
  f.controller.dispose();
});

test('reopened pending and capturing enrollment requests start preview, while other states never start it',async()=>{
  for(const status of ['PENDING','CAPTURING','FINALIZING','COMPLETED','FAILED','CANCELLED']){
    const f=fixture({commands:[command({status})]});await reopen(f);
    assert.equal(f.instances.length,1);assert.equal(f.instances[0].starts,['PENDING','CAPTURING'].includes(status)?1:0,status);
    assert.equal(f.instances[0].options.expectedStationId,'GATE-01');assert.deepEqual(f.writes,[]);
    f.controller.dispose();
  }
  const f=fixture({commands:[command({cancelRequested:true})]});await reopen(f);
  assert.equal(f.instances[0].starts,0);assert.match(f.progress.innerHTML,/Đang hủy/);f.controller.dispose();
});

test('finalizing and terminal snapshots stop the preview once without rewriting enrollment or saved profiles',async()=>{
  for(const status of ['FINALIZING','COMPLETED','FAILED','CANCELLED']){
    const f=fixture({commands:[command()]});await reopen(f);const instance=f.instances[0];
    f.publish('request-one',command({status,samplesCaptured:10}));f.controller.refresh();
    assert.equal(instance.starts,1);assert.equal(instance.stops,1,status);
    assert.equal(f.instances.length,1);assert.deepEqual(f.writes,[]);assert.deepEqual(f.ctx.data.faceProfiles,[]);
    if(status==='COMPLETED')assert.match(f.progress.innerHTML,/đang chờ xác nhận hồ sơ/,'preview cannot pretend that a saved profile exists');
    f.controller.dispose();
  }
});

test('cancel request stops inline preview but still waits for the worker to cancel enrollment',async()=>{
  const f=fixture(),id=await begin(f),instance=f.instances[0];f.writes.length=0;
  await f.controller.onAction('face-command-cancel',{dataset:{requestId:id}});
  assert.equal(instance.stops,1);assert.match(f.progress.innerHTML,/Đang hủy; chờ laptop xác nhận/);
  assert.doesNotMatch(f.progress.innerHTML,/Đã hủy yêu cầu/);
  assert.deepEqual(f.writes.map(({type,collection})=>[type,collection]),[['update','faceEnrollmentCommands']]);
  const saved=await f.store.get('faceEnrollmentCommands',id);assert.equal(saved.status,'PENDING');assert.equal(saved.cancelRequested,true);
  f.writes.length=0;f.publish(id,command({id,requestId:id,status:'CANCELLED',cancelRequested:true}));
  assert.equal(instance.stops,1);assert.match(f.progress.innerHTML,/Đã hủy yêu cầu/);assert.deepEqual(f.writes,[]);
  f.controller.dispose();
});

test('deletion progress never mounts or starts a camera preview',async()=>{
  for(const status of ['PENDING','CAPTURING','FINALIZING','COMPLETED']){
    const f=fixture({commands:[command({action:'DELETE',status,sampleCount:0})]});await reopen(f);
    assert.equal(f.camera,null);assert.equal(f.instances.length,0);assert.equal(f.beforeStarts,0);
    assert.doesNotMatch(f.dialog.body,/id="face-enrollment-camera"/);assert.deepEqual(f.writes,[]);f.controller.dispose();
  }
});

test('closing or replacing the dialog disposes its preview and ignores late command callbacks',async()=>{
  for(const change of ['close','replace']){
    const f=fixture({commands:[command()]});await reopen(f);
    const instance=f.instances[0],oldProgress=f.progress,html=oldProgress.innerHTML,entry=f.watchers.get('faceEnrollmentCommands/request-one');
    if(change==='close')f.close();else{f.replace();f.flushClose();}
    assert.equal(instance.disposals,1,change);assert.ok([...f.watchers.values()].every(row=>row.stopped));
    entry.success(command({status:'COMPLETED',samplesCaptured:10}));entry.error(new Error('late error'));f.controller.refresh();
    assert.equal(oldProgress.innerHTML,html);assert.equal(instance.starts,1);assert.equal(f.instances.length,1);
    assert.deepEqual(f.writes,[]);f.controller.dispose();assert.equal(instance.disposals,1);
  }
});

test('a replacement request owns its preview even when previous close or subscription callbacks arrive late',async()=>{
  const f=fixture({commands:[command(),command({id:'request-two',requestId:'request-two',status:'PENDING'})]});await reopen(f);
  const old=f.instances[0],oldDialog=f.dialog,oldWatcher=f.watchers.get('faceEnrollmentCommands/request-one');
  await reopen(f,'request-two');const current=f.instances[1],currentProgress=f.progress;
  assert.equal(old.disposals,1);assert.equal(current.starts,1);assert.equal(current.root.dataset.requestId,'request-two');
  oldDialog.onClose();f.flushClose();oldWatcher.success(command({status:'FINALIZING'}));oldWatcher.error(new Error('old listener'));
  assert.equal(current.disposals,0);assert.equal(current.stops,0);assert.equal(f.progress,currentProgress);
  assert.match(f.progress.innerHTML,/Đang chờ laptop/);assert.deepEqual(f.writes,[]);
  f.controller.dispose();assert.equal(current.disposals,1);
});

test('session cleanup disposes the camera and late callbacks cannot touch another signed-in session',async()=>{
  const f=fixture({commands:[command()]});await reopen(f);
  const instance=f.instances[0],html=f.progress.innerHTML,watcher=f.watchers.get('faceEnrollmentCommands/request-one');
  f.controller.dispose();f.ctx.user={uid:'another-admin'};f.ctx.store=memoryStore({});
  watcher.success(command({status:'COMPLETED',samplesCaptured:10}));watcher.error(new Error('late old-session error'));f.controller.refresh();
  assert.equal(instance.disposals,1);assert.equal(instance.starts,1);assert.equal(f.progress.innerHTML,html);
  assert.deepEqual(f.writes,[]);
});

test('preview connection errors and command listener errors do not cancel or change enrollment writes',async()=>{
  const f=fixture({previewError:true}),id=await begin(f),instance=f.instances[0];f.writes.length=0;
  assert.match(f.camera.innerHTML,/Không kết nối được camera/);assert.match(f.progress.innerHTML,/Đang chờ laptop/);
  assert.doesNotMatch(f.progress.innerHTML,/Thất bại:/);
  f.watchers.get(`faceEnrollmentCommands/${id}`).error({code:'permission-denied'});
  assert.match(f.progress.innerHTML,/Không đủ quyền/);assert.equal(f.camera,instance.root);assert.equal(instance.starts,1);
  f.publish(id,command({id,requestId:id,samplesCaptured:3}));
  assert.match(f.progress.innerHTML,/Đang thu ảnh: 3\/10/);assert.match(f.camera.innerHTML,/Không kết nối được camera/);
  const saved=await f.store.get('faceEnrollmentCommands',id);assert.equal(saved.status,'PENDING');assert.equal(saved.cancelRequested,false);
  assert.deepEqual(f.ctx.data.faceProfiles,[]);assert.deepEqual(f.writes,[]);f.controller.dispose();
});
