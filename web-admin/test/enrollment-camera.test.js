import test from 'node:test';
import assert from 'node:assert/strict';
import {createPreviewController} from '../src/camera-preview.js';
import {createEnrollmentCameraPreview,renderEnrollmentCameraPreview} from '../src/enrollment-camera.js';
import {SIX_POSE_PLAN} from '../src/face-capture-plan.js';

class Events{
  listeners=new Map();
  addEventListener(type,listener){if(!this.listeners.has(type))this.listeners.set(type,new Set());this.listeners.get(type).add(listener);}
  removeEventListener(type,listener){this.listeners.get(type)?.delete(listener);}
  emit(type,event={}){for(const listener of this.listeners.get(type)||[])listener(event);}
  count(){return [...this.listeners.values()].reduce((total,list)=>total+list.size,0);}
}
const health=()=>new Response(JSON.stringify({cameraReady:true,wifiConnected:true,stationId:'GATE-01',sensor:'OV5640',board:'ESP32-S3'}),{headers:{'Content-Type':'application/json'}});
const jpeg=()=>new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg'}});
const flush=async()=>{for(let i=0;i<24;i++)await Promise.resolve();};
function fixture(fetchImpl,{captureFactory}={}){
  const documentImpl=new Events();documentImpl.hidden=false;documentImpl.defaultView=new Events();
  const root=new Events();root.isConnected=true;root.ownerDocument=documentImpl;
  const nodes=new Map(),calls=[],revoked=[],timers=new Map(),starts=[];let nextTimer=0,nextUrl=0,controller,options;
  for(const name of ['image','stage','placeholder','start','stop','rotate','state','message','updated','rotation','hardware']){
    const node=new Events();Object.assign(node,{root,hidden:false,disabled:name==='stop',innerHTML:'',textContent:'',style:{},attributes:{},classList:{toggle(){}},dataset:{action:`face-camera-${name}`},closest(){return this;},getAttribute(key){return this.attributes[key];},setAttribute(key,value){this.attributes[key]=value;},removeAttribute(key){delete this.attributes[key];}});nodes.set(`#face-camera-${name}`,node);
  }
  for(const name of ['overlay','caption','title','instruction']){
    const properties=new Map();
    nodes.set(`#face-frame-${name}`,{hidden:true,textContent:'',dataset:{},style:{setProperty(key,value){properties.set(key,value);},getPropertyValue:key=>properties.get(key)||''}});
  }
  for(const name of ['image','placeholder','result','preview-retry']){
    const node=new Events();Object.assign(node,{hidden:true,textContent:'',attributes:{},getAttribute(key){return this.attributes[key];},setAttribute(key,value){this.attributes[key]=value;},removeAttribute(key){delete this.attributes[key];}});nodes.set(`#face-capture-${name}`,node);
  }
  root.querySelector=selector=>nodes.get(selector)||null;root.contains=node=>node.root===root;
  const viewer=createEnrollmentCameraPreview(root,{documentImpl,expectedStationId:'GATE-01',...(captureFactory?{captureFactory}:{}),onStart:()=>starts.push(calls.length),controllerFactory:received=>{
    options=received;
    controller=createPreviewController({...received,fetchImpl:async(...args)=>{calls.push(args);return fetchImpl?fetchImpl(...args):args[0].endsWith('/health')?health():jpeg();},createUrl:()=>`blob:inline-${++nextUrl}`,revokeUrl:url=>revoked.push(url),now:()=>1000+nextUrl,setTimer:(callback,delay)=>{timers.set(++nextTimer,{callback,delay});return nextTimer;},clearTimer:id=>timers.delete(id)});return controller;
  }});
  return {viewer,root,documentImpl,nodes,calls,revoked,timers,starts,get controller(){return controller;},get options(){return options;},node:name=>nodes.get(`#face-camera-${name}`),click(name){let prevented=false,stopped=false;root.emit('click',{target:this.node(name),preventDefault(){prevented=true;},stopPropagation(){stopped=true;}});return {prevented,stopped};},tick(){const [id,timer]=timers.entries().next().value;timers.delete(id);timer.callback();return timer;}};
}

test('inline markup has accessible camera controls and describes independent viewing',()=>{
  const html=renderEnrollmentCameraPreview();
  assert.match(html,/aria-labelledby="face-camera-title"/);assert.match(html,/aria-live="polite"/);assert.match(html,/alt="Ảnh trực tiếp từ camera của trạm đăng ký khuôn mặt"/);
  for(const action of ['start','stop','rotate'])assert.match(html,new RegExp(`type="button"[^>]*data-action="face-camera-${action}"`));
  assert.match(html,/không hủy yêu cầu đăng ký/);assert.match(html,/Xoay ảnh chỉ đổi góc xem/);assert.doesNotMatch(html,/192\.168|X-Camera-Token|firebase-admin/);
  assert.match(html,/Camera đăng ký khuôn mặt/);assert.doesNotMatch(html,/Camera đang lấy mẫu/,'the shared camera card must not claim an already-completed request is still capturing');
});

test('inline viewer starts on demand, pins station, polls slowly and handles local controls without root actions',async()=>{
  const f=fixture();assert.equal(f.calls.length,0);assert.equal(f.starts.length,0);
  assert.deepEqual(f.click('start'),{prevented:true,stopped:true});await flush();
  assert.deepEqual(f.starts,[0]);assert.equal(f.node('image').attributes.src,'blob:inline-1');assert.equal(f.node('start').disabled,true);assert.equal(f.node('stop').disabled,false);assert.equal(f.node('state').textContent,'Đang xem');assert.match(f.node('hardware').textContent,/OV5640/);
  for(const [,request]of f.calls){assert.equal(request.method,'GET');assert.equal(request.headers['X-Camera-Station'],'GATE-01');}
  assert.equal(f.tick().delay,1000);await flush();assert.equal(f.node('image').attributes.src,'blob:inline-2');assert.deepEqual(f.revoked,['blob:inline-1']);
  assert.deepEqual(f.click('rotate'),{prevented:true,stopped:true});assert.equal(f.node('image').style.transform,'rotate(90deg)');
  assert.deepEqual(f.click('stop'),{prevented:true,stopped:true});assert.equal(f.node('image').hidden,true);assert.equal(f.node('image').attributes.src,undefined);assert.equal(f.timers.size,0);
  assert.ok(f.calls.every(([endpoint])=>['/api/camera/health','/api/camera/frame'].includes(endpoint)));f.viewer.dispose();
});

test('disposing aborts delayed frames, clears listeners and ignores later controller callbacks',async()=>{
  let resolveFrame;const f=fixture(endpoint=>endpoint.endsWith('/health')?health():new Promise(resolve=>{resolveFrame=resolve;}));
  const starting=f.viewer.start();await flush();const signal=f.calls.at(-1)[1].signal;
  f.viewer.dispose();assert.equal(signal.aborted,true);assert.equal(f.root.count(),0);assert.equal(f.documentImpl.count(),0);assert.equal(f.documentImpl.defaultView.count(),0);assert.equal(f.node('image').count(),0);
  const message=f.node('message').textContent;resolveFrame(jpeg());await starting;
  f.options.onChange({running:true,phase:'live',frameUrl:'blob:late',rotation:0});await f.viewer.start();f.viewer.visibilityChanged();
  assert.equal(f.calls.length,2);assert.equal(f.node('image').attributes.src,undefined);assert.equal(f.node('message').textContent,message);assert.deepEqual(f.revoked,[]);assert.equal(f.timers.size,0);
});

test('hidden document pauses and revokes images, visibility return does not auto-restart, and pagehide also stops',async()=>{
  const f=fixture();await f.viewer.start();assert.equal(f.node('image').attributes.src,'blob:inline-1');
  f.documentImpl.hidden=true;f.documentImpl.emit('visibilitychange');assert.equal(f.controller.getState().running,false);assert.equal(f.node('start').disabled,false);assert.deepEqual(f.revoked,['blob:inline-1']);assert.equal(f.timers.size,0);
  f.documentImpl.hidden=false;f.documentImpl.emit('visibilitychange');await flush();assert.equal(f.calls.length,2);assert.equal(f.controller.getState().running,false);
  await f.viewer.start();f.documentImpl.defaultView.emit('pagehide');assert.equal(f.controller.getState().running,false);assert.deepEqual(f.revoked,['blob:inline-1','blob:inline-2']);f.viewer.dispose();
});

test('image failure exposes retry and recovering only resumes snapshots',async()=>{
  const f=fixture();await f.viewer.start();f.node('image').emit('error');
  assert.equal(f.controller.getState().phase,'error');assert.equal(f.timers.size,0);assert.match(f.node('start').innerHTML,/Thử lại/);assert.equal(f.node('image').hidden,true);
  f.click('start');await flush();assert.equal(f.controller.getState().phase,'live');assert.equal(f.node('image').attributes.src,'blob:inline-2');assert.equal(f.calls.length,4);assert.ok(f.calls.every(([endpoint])=>endpoint.startsWith('/api/camera/')));f.viewer.dispose();
});

test('detached modal cannot start and delayed responses cannot update its removed content',async()=>{
  const f=fixture();f.root.isConnected=false;await f.viewer.start();assert.equal(f.calls.length,0);assert.equal(f.starts.length,0);f.viewer.dispose();
  let resolveFrame;const delayed=fixture(endpoint=>endpoint.endsWith('/health')?health():new Promise(resolve=>{resolveFrame=resolve;}));
  const starting=delayed.viewer.start();await flush();delayed.root.isConnected=false;const message=delayed.node('message').textContent;resolveFrame(jpeg());await starting;
  assert.equal(delayed.node('image').attributes.src,undefined);assert.equal(delayed.node('message').textContent,message);assert.equal(delayed.controller.getState().running,false);assert.equal(delayed.timers.size,0);delayed.viewer.dispose();
});

test('stop prevents an in-flight startup hook from reopening the camera afterward',async()=>{
  const f=fixture();f.viewer.dispose();let releaseStart,requests=0;
  const viewer=createEnrollmentCameraPreview(f.root,{documentImpl:f.documentImpl,expectedStationId:'GATE-01',onStart:()=>new Promise(resolve=>{releaseStart=resolve;}),controllerFactory:options=>createPreviewController({...options,fetchImpl:async()=>{requests++;return health();},createUrl:()=>{throw new Error('No image should be created');}})});
  const starting=viewer.start();await flush();viewer.stop();releaseStart();await starting;
  assert.equal(requests,0);assert.equal(f.node('start').disabled,false);viewer.dispose();
});

test('removing the modal during health check never starts a frame request',async()=>{
  let resolveHealth;const f=fixture(()=>new Promise(resolve=>{resolveHealth=resolve;}));
  const starting=f.viewer.start();await flush();f.root.isConnected=false;resolveHealth(health());await starting;
  assert.deepEqual(f.calls.map(([endpoint])=>endpoint),['/api/camera/health']);assert.equal(f.controller.getState().running,false);assert.equal(f.timers.size,0);f.viewer.dispose();
});

test('accepted count updates the existing oval without taking photos, reloading live image or rebuilding its node',async()=>{
  const observations=[];
  const f=fixture(undefined,{captureFactory:()=>({observe:(command,requestId)=>observations.push({command,requestId}),dispose(){},visibilityChanged(){},pause(){},retry(){},imageFailed(){}})});
  const command=(extra={})=>({requestId:'request_1',action:'ENROLL',captureMode:'MANUAL',sampleCount:6,capturePlan:SIX_POSE_PLAN,status:'CAPTURING',cancelRequested:false,samplesCaptured:0,captureRequestSeq:0,captureConsumedSeq:0,...extra});
  const overlay=f.nodes.get('#face-frame-overlay'),caption=f.nodes.get('#face-frame-caption'),title=f.nodes.get('#face-frame-title');
  f.viewer.observeCapture(command(),'request_1');assert.equal(overlay.hidden,true);assert.equal(f.calls.length,0);
  await f.viewer.start();const imageUrl=f.node('image').attributes.src,requestCount=f.calls.length;
  assert.equal(overlay.hidden,false);assert.equal(overlay.dataset.distance,'FAR');assert.equal(overlay.style.getPropertyValue('--face-frame-scale'),'1');
  f.viewer.observeCapture(command({captureRequestSeq:1,captureConsumedSeq:1,captureLastReason:'low_quality'}),'request_1');
  assert.equal(overlay.dataset.distance,'FAR');assert.match(title.textContent,/Ảnh 1\/2/);
  f.viewer.observeCapture(command({samplesCaptured:1,captureRequestSeq:2,captureConsumedSeq:2}),'request_1');
  assert.equal(overlay.dataset.distance,'NEAR');assert.equal(overlay.style.getPropertyValue('--face-frame-scale'),'1.35');assert.match(title.textContent,/Ảnh 2\/2/);
  assert.equal(f.nodes.get('#face-frame-overlay'),overlay);assert.equal(f.node('image').attributes.src,imageUrl);assert.equal(f.calls.length,requestCount);
  f.click('rotate');assert.equal(f.node('image').style.transform,'rotate(90deg)');assert.equal(overlay.style.transform,undefined);assert.equal(overlay.dataset.distance,'NEAR');
  f.viewer.observeCapture(command({samplesCaptured:2,captureRequestSeq:3,captureConsumedSeq:3}),'request_1');
  assert.equal(overlay.dataset.distance,'FAR');assert.match(title.textContent,/Quay sang trái.*Ảnh 1\/2/);
  f.viewer.observeCapture(command({samplesCaptured:6}),'request_1');assert.equal(overlay.hidden,true);assert.equal(caption.hidden,true);
  f.viewer.observeCapture(command(),'request_1');assert.equal(overlay.hidden,false);f.viewer.stop();assert.equal(overlay.hidden,true);assert.equal(caption.hidden,true);
  assert.ok(f.calls.every(([endpoint,options])=>options.method==='GET'&&['/api/camera/health','/api/camera/frame'].includes(endpoint)));assert.equal(observations.length,6);
  f.viewer.dispose();f.viewer.observeCapture(command({samplesCaptured:1}),'request_1');assert.equal(observations.length,6,'disposed dialog cannot update capture guidance');
});

test('completed enrollment keeps the exact final picture and removes idle retry prompts to shoot another picture',()=>{
  let publishCapture;
  const f=fixture(undefined,{captureFactory:options=>{publishCapture=options.onChange;return {observe(){},dispose(){},visibilityChanged(){},pause(){},retry(){},imageFailed(){}};}});
  const command={requestId:'request_1',action:'ENROLL',status:'CAPTURING'};
  const image=f.nodes.get('#face-capture-image'),message=f.nodes.get('#face-capture-result');
  publishCapture({phase:'ready',frameUrl:'blob:last-accepted',message:'Lần chụp 6: đã lưu ảnh. Tổng 6/6 ảnh đạt yêu cầu.'});
  f.viewer.observeCapture({...command,status:'COMPLETED'},'request_1');
  assert.equal(image.attributes.src,'blob:last-accepted');assert.equal(image.hidden,false);assert.match(message.textContent,/6\/6 ảnh đạt/);
  publishCapture({phase:'idle',frameUrl:null,message:'Bấm Chụp ảnh. Ảnh được laptop kiểm tra sẽ hiện ở đây.'});
  assert.match(message.textContent,/Yêu cầu đã hoàn tất/);assert.doesNotMatch(message.textContent,/Bấm Chụp ảnh/);
  publishCapture({phase:'error',frameUrl:null,message:'Ảnh không khớp. Bấm Chụp ảnh để thử lại.'});
  assert.match(message.textContent,/Có thể thử tải lại ảnh/);assert.doesNotMatch(message.textContent,/Bấm Chụp ảnh/);
  f.viewer.dispose();
});

test('cancelled and failed requests never leave the capture caption asking for a new shot',()=>{
  for(const status of ['CANCELLED','FAILED']){
    const f=fixture(undefined,{captureFactory:()=>({getState:()=>({phase:'idle',frameUrl:null,message:'Bấm Chụp ảnh.'}),observe(){},dispose(){},visibilityChanged(){},pause(){},retry(){},imageFailed(){}})});
    f.viewer.observeCapture({requestId:'request_1',action:'ENROLL',status},'request_1');
    assert.match(f.nodes.get('#face-capture-result').textContent,/Yêu cầu đã kết thúc/);assert.doesNotMatch(f.nodes.get('#face-capture-result').textContent,/Bấm Chụp ảnh/);assert.equal(f.calls.length,0);
    f.viewer.dispose();
  }
});
