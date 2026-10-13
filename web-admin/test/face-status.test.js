import test from 'node:test';
import assert from 'node:assert/strict';
import {createFaceStatusController,updateFaceStatus} from '../src/face-status.js';

const baseTime=Date.parse('2026-10-13T00:00:00Z');
const snapshot=(extra={})=>({status:'FACE_BLOCKED',qualityIssue:'mask',message:'Phát hiện khẩu trang. Tháo khẩu trang.',updatedAt:'2026-10-13T00:00:00+00:00',accessoryGateEnabled:true,
  scan:{status:'CHECKING',message:'Đang xác nhận ổn định.',updatedAt:'2026-10-13T00:00:00+00:00'},
  attendance:{status:'ACCEPTED',employeeName:'<b>Nguyễn Văn A</b>',message:'Đã ghi công trên Firebase.',updatedAt:'2026-10-13T00:00:00+00:00'},...extra});
const response=value=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
const flush=async()=>{for(let count=0;count<14;count++)await Promise.resolve();};
function setup(fetchImpl=async()=>response(snapshot())){
  let id=0,hidden=false,time=baseTime;const timers=new Map(),calls=[],states=[];
  const controller=createFaceStatusController({fetchImpl:async(...args)=>{calls.push(args);return fetchImpl(...args);},setTimer:(callback,delay)=>{timers.set(++id,{callback,delay});return id;},clearTimer:key=>timers.delete(key),isHidden:()=>hidden,now:()=>time,onChange:value=>states.push(value)});
  return {controller,calls,timers,states,advance:seconds=>{time+=seconds*1000;},hide(){hidden=true;controller.visibilityChanged();},tick(delay=1000){const entry=[...timers.entries()].find(([,value])=>value.delay===delay);assert.ok(entry,'expected timer');timers.delete(entry[0]);entry[1].callback();}};
}

test('accessory status starts only with camera viewing and uses fixed local route with expected station',async()=>{
  const f=setup();assert.equal(f.calls.length,0);
  f.controller.previewChanged({running:true,phase:'connecting',health:null});assert.equal(f.calls.length,0);
  f.controller.previewChanged({running:true,health:{stationId:'GATE-01'}});await flush();
  assert.equal(f.calls.length,1);assert.equal(f.controller.getState().phase,'blocked');assert.match(f.controller.getState().message,/khẩu trang/);
  const[route,request]=f.calls[0];assert.equal(route,'/api/camera/face-status');assert.deepEqual(request.headers,{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01'});assert.equal(request.cache,'no-store');assert.equal(request.redirect,'error');
  f.controller.previewChanged({running:true,health:{stationId:'GATE-01'}});assert.equal(f.calls.length,1);
  f.controller.previewChanged({running:false});assert.equal(f.timers.size,0);assert.equal(f.controller.getState().phase,'idle');
});

test('old or future status never leaves a blocked or ready claim visible',async()=>{
  for(const status of ['FACE_BLOCKED','FACE_READY']){
    const value=snapshot({status,qualityIssue:status==='FACE_READY'?null:'mask'});
    const f=setup(async()=>response(value));await f.controller.start('GATE-01');
    f.advance(11);f.tick();await flush();assert.equal(f.controller.getState().phase,'stale');assert.equal(f.controller.getState().qualityIssue,null);assert.match(f.controller.getState().message,/Trạng thái kiểm tra đã cũ/);f.controller.stop();
  }
  const f=setup(async()=>response(snapshot({updatedAt:'2026-10-13T00:00:20Z'})));await f.controller.start('GATE-01');assert.equal(f.controller.getState().phase,'stale');f.controller.stop();
});

test('station failure is isolated from preview and next status poll can recover',async()=>{
  let down=true;const f=setup(async()=>down?new Response('station offline',{status:503}):response(snapshot({status:'FACE_READY',qualityIssue:null,message:'Đã qua kiểm tra phụ kiện.'})));
  await f.controller.start('GATE-01');assert.equal(f.controller.getState().running,true);assert.equal(f.controller.getState().phase,'offline');assert.match(f.controller.getState().message,/ảnh camera vẫn có thể xem riêng/);
  down=false;f.tick();await flush();assert.equal(f.controller.getState().phase,'ready');assert.equal(f.calls.length,2);f.controller.stop();
});

test('hidden, route stop and dispose abort polling and ignore a late blocked response',async()=>{
  for(const action of ['hide','stop','dispose']){
    let resolve;const f=setup(()=>new Promise(done=>{resolve=done;}));
    const pending=f.controller.start('GATE-01');await flush();
    const signal=f.calls[0][1].signal;
    if(action==='hide')f.hide();else f.controller[action]();
    assert.equal(signal.aborted,true);assert.equal(f.timers.size,0);
    resolve(response(snapshot()));await pending;
    assert.equal(f.controller.getState().running,false);assert.equal(f.controller.getState().phase,'idle');assert.equal(f.timers.size,0);
  }
});

test('status requests never overlap and a restart waits for the aborted request to settle',async()=>{
  let resolve,count=0;const f=setup(()=>++count===1?new Promise(done=>{resolve=done;}):response(snapshot()));
  const first=f.controller.start('GATE-01');await flush();f.tick();await flush();assert.equal(f.calls.length,1);
  f.controller.stop();const next=f.controller.start('GATE-02');await flush();assert.equal(f.calls.length,1);
  resolve(response(snapshot()));await first;await next;
  assert.equal(f.calls.length,2);assert.equal(f.calls[1][1].headers['X-Camera-Station'],'GATE-02');assert.equal(f.controller.getState().phase,'blocked');f.controller.stop();
});

test('invalid status or a disabled gate cannot be presented as passed',async()=>{
  for(const value of [snapshot({status:'UNKNOWN'}),snapshot({qualityIssue:'foreign'}),snapshot({updatedAt:'2026-02-30T00:00:00Z'}),snapshot({message:'x'.repeat(301)}),snapshot({accessoryGateEnabled:1}),snapshot({status:'FACE_READY',qualityIssue:'mask'})]){
    const f=setup(async()=>response(value));await f.controller.start('GATE-01');assert.equal(f.controller.getState().phase,'offline');f.controller.stop();
  }
  const f=setup(async()=>response(snapshot({status:'FACE_READY',qualityIssue:null,accessoryGateEnabled:false})));await f.controller.start('GATE-01');assert.equal(f.controller.getState().phase,'disabled');assert.match(f.controller.getState().message,/Chưa bật/);f.controller.stop();
});

test('status DOM uses text content and does not change the camera image',()=>{
  const message={textContent:''},badge={textContent:'',className:''},panel={classList:{toggle(name,value){this[name]=value;}}};
  const scanBadge={textContent:'',className:''},scanMessage={textContent:''},attendanceBadge={textContent:'',className:''},attendanceName={textContent:'',hidden:true},attendanceMessage={textContent:''};
  const root={querySelector:selector=>({'#camera-face-status':panel,'#camera-face-status-badge':badge,'#camera-face-status-message':message,'#camera-face-scan-badge':scanBadge,'#camera-face-scan-message':scanMessage,'#camera-face-attendance-badge':attendanceBadge,'#camera-face-attendance-name':attendanceName,'#camera-face-attendance-message':attendanceMessage})[selector]};
  updateFaceStatus(root,{phase:'blocked',message:'<img src=x onerror=alert(1)>'});assert.equal(message.textContent,'<img src=x onerror=alert(1)>');assert.equal(badge.textContent,'Chưa đạt');assert.match(badge.className,/bad/);assert.equal(panel.classList.error,true);
  updateFaceStatus(root,{phase:'ready',message:'Đã qua kiểm tra.'});assert.match(badge.className,/good/);assert.equal(panel.classList.error,false);
  updateFaceStatus(root,{phase:'ready',message:'Đã qua kiểm tra.',scan:{status:'ACCEPTED',message:'Đã ghi công.'},attendance:{status:'ACCEPTED',employeeName:'<b>Nguyễn Văn A</b>',message:'Đã ghi công trên Firebase.'}});
  assert.equal(attendanceName.textContent,'<b>Nguyễn Văn A</b>');assert.equal(attendanceName.hidden,false);assert.equal(attendanceBadge.textContent,'Đã chấm công');
});

test('recognized employee and attendance outcome are visible, then the old name expires',async()=>{
  const f=setup(async()=>response(snapshot({status:'FACE_READY',qualityIssue:null,attendance:{status:'ACCEPTED',employeeName:'Nguyễn Văn A',message:'Đã ghi công trên Firebase.',updatedAt:'2026-10-13T00:00:00+00:00'}})));
  await f.controller.start('GATE-01');assert.equal(f.controller.getState().attendance.employeeName,'Nguyễn Văn A');
  f.advance(61);f.tick();await flush();assert.equal(f.controller.getState().attendance.status,'NONE');f.controller.stop();
});
