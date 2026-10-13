import test from 'node:test';
import assert from 'node:assert/strict';
import {createPreviewController,renderCameraPreview,updateCameraPreview} from '../src/camera-preview.js';

const health=(stationId)=>new Response(JSON.stringify({cameraReady:true,wifiConnected:true,sensor:'OV5640',board:'ESP32-S3',...(stationId===undefined?{}:{stationId})}),{headers:{'Content-Type':'application/json'}});
const image=()=>new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg'}});
const flush=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
function setup(fetchImpl,options={}){
  let timerId=0,urlId=0,hidden=false;const timers=new Map(),revoked=[],states=[],calls=[];
  const viewer=createPreviewController({...options,fetchImpl:async(...args)=>{calls.push(args);return fetchImpl?fetchImpl(...args):args[0].endsWith('/health')?health():image();},createUrl:()=>`blob:frame-${++urlId}`,revokeUrl:url=>revoked.push(url),setTimer:(callback,delay)=>{timers.set(++timerId,{callback,delay});return timerId;},clearTimer:id=>timers.delete(id),isHidden:()=>hidden,now:()=>1000+urlId,onChange:state=>states.push(state)});
  return {viewer,timers,revoked,states,calls,hide(){hidden=true;viewer.visibilityChanged();},tick(){const [id,timer]=timers.entries().next().value;timers.delete(id);timer.callback();return timer;}};
}

test('preview starts explicitly, reads health before fresh frames and never stores camera credentials',async()=>{
  const f=setup();assert.equal(f.calls.length,0);await f.viewer.start();assert.equal(f.viewer.getState().phase,'live');assert.equal(f.viewer.getState().frameUrl,'blob:frame-1');assert.deepEqual(f.calls.map(row=>row[0]),['/api/camera/health','/api/camera/frame']);
  for(const[,request]of f.calls){assert.deepEqual(request.headers,{'X-Camera-Preview':'1'});assert.equal(request.cache,'no-store');assert.equal(request.redirect,'error');}
  assert.equal(f.tick().delay,400);await flush();assert.equal(f.calls.length,3);assert.equal(f.viewer.getState().frameUrl,'blob:frame-2');assert.deepEqual(f.revoked,['blob:frame-1']);f.viewer.stop();assert.equal(f.timers.size,0);assert.deepEqual(f.revoked,['blob:frame-1','blob:frame-2']);assert.equal(f.viewer.getState().frameUrl,null);
});

test('starting twice does not overlap requests; hiding cancels capture and ignores late frames',async()=>{
  let resolveFrame;const f=setup(async(endpoint)=>endpoint.endsWith('/health')?health():new Promise(resolve=>{resolveFrame=resolve;}));
  const starting=f.viewer.start();await flush();assert.equal(f.calls.length,2);await f.viewer.start();assert.equal(f.calls.length,2);assert.equal(f.timers.size,0);
  const signal=f.calls.at(-1)[1].signal;f.hide();assert.equal(signal.aborted,true);resolveFrame(image());await starting;assert.equal(f.viewer.getState().running,false);assert.equal(f.viewer.getState().frameUrl,null);assert.equal(f.timers.size,0);assert.match(f.viewer.getState().message,/trang bị ẩn/);
});

test('stop and immediate restart wait for the canceled fetch to settle',async()=>{
  let resolveFrame,frameCalls=0;const f=setup(endpoint=>endpoint.endsWith('/health')?health():++frameCalls===1?new Promise(resolve=>{resolveFrame=resolve;}):image());
  const initial=f.viewer.start();await flush();f.viewer.stop();const restarting=f.viewer.start();await flush();assert.equal(f.calls.length,2);resolveFrame(image());await initial;await restarting;assert.equal(f.calls.length,4);assert.equal(f.viewer.getState().phase,'live');assert.equal(f.viewer.getState().frameUrl,'blob:frame-1');f.viewer.stop();
});

test('error stops polling and explicit retry recovers without enrollment or attendance requests',async()=>{
  let failed=true;const f=setup(endpoint=>endpoint.endsWith('/health')?health():failed?new Response(JSON.stringify({message:'Camera mất nguồn'}),{status:502,headers:{'Content-Type':'application/json'}}):image());
  await f.viewer.start();assert.equal(f.viewer.getState().phase,'error');assert.equal(f.viewer.getState().running,false);assert.match(f.viewer.getState().message,/Camera mất nguồn/);assert.equal(f.timers.size,0);failed=false;await f.viewer.start();assert.equal(f.viewer.getState().phase,'live');assert.ok(f.calls.every(row=>['/api/camera/health','/api/camera/frame'].includes(row[0])));f.viewer.stop();
});

test('unsafe health and static hosting responses produce clear errors; rotation only changes viewing state',async()=>{
  for(const [response,message]of [[new Response('<html>static web</html>',{headers:{'Content-Type':'text/html'}}),/localhost/],[new Response(JSON.stringify({cameraReady:false,wifiConnected:true}),{headers:{'Content-Type':'application/json'}}),/chưa sẵn sàng/]]){
    const f=setup(()=>response);await f.viewer.start();assert.equal(f.viewer.getState().phase,'error');assert.match(f.viewer.getState().message,message);assert.equal(f.timers.size,0);
  }
  const f=setup();for(let i=0;i<4;i++){f.viewer.rotate();assert.equal(f.viewer.getState().rotation,((i+1)*90)%360);}assert.equal(f.calls.length,0);
  const html=renderCameraPreview();assert.match(html,/data-action="camera-start"/);assert.match(html,/data-action="camera-stop"/);assert.match(html,/data-action="camera-rotate"/);assert.match(html,/không ghi lượt chấm công/);assert.match(html,/aria-live="polite"/);assert.match(html,/width="640" height="480"/);assert.doesNotMatch(html,/X-Camera-Token|192\.168|test-camera-secret/);
});

test('DOM updates expose current connection state, rotation, safe sensor details and stop controls',()=>{
  const nodes=new Map();const node=id=>{if(!nodes.has(id))nodes.set(id,{hidden:false,disabled:false,innerHTML:'',textContent:'',className:'',style:{},attributes:{},classList:{toggle(){}},getAttribute(name){return this.attributes[name];},setAttribute(name,value){this.attributes[name]=value;},removeAttribute(name){delete this.attributes[name];}});return nodes.get(id);};
  const root={querySelector:selector=>node(selector.slice(1))};
  updateCameraPreview(root,{running:true,phase:'live',message:'Đang nhận ảnh',rotation:90,frameUrl:'blob:current',updatedAt:1000,health:{sensor:'OV5640',board:'ESP32-S3'}});
  assert.equal(node('camera-image').attributes.src,'blob:current');assert.equal(node('camera-image').style.transform,'rotate(90deg)');assert.equal(node('camera-start').disabled,true);assert.equal(node('camera-stop').disabled,false);assert.equal(node('camera-state').textContent,'Đang xem');assert.match(node('camera-hardware').textContent,/OV5640/);
  updateCameraPreview(root,{running:false,phase:'error',message:'Mất camera',rotation:90,frameUrl:null,updatedAt:null,health:null});assert.equal(node('camera-image').hidden,true);assert.equal(node('camera-image').attributes.src,undefined);assert.match(node('camera-start').innerHTML,/Thử lại/);assert.equal(node('camera-message').textContent,'Mất camera');
});

test('configured source rotation matches frames and remains separate from the view rotation',async()=>{
  let angle=180;
  const f=setup(endpoint=>endpoint.endsWith('/health')?new Response(JSON.stringify({cameraReady:true,wifiConnected:true,rotation:180}),{headers:{'Content-Type':'application/json'}}):new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg','X-Camera-Rotation':String(angle)}}));
  await f.viewer.start();assert.equal(f.viewer.getState().sourceRotation,180);assert.equal(f.viewer.getState().rotation,0);
  f.viewer.rotate();assert.equal(f.viewer.getState().sourceRotation,180);assert.equal(f.viewer.getState().rotation,90);
  angle=0;f.tick();await flush();assert.equal(f.viewer.getState().sourceRotation,0);assert.equal(f.viewer.getState().rotation,90);
  f.viewer.stop();
});

test('invalid orientation metadata stops preview before accepting any frame',async()=>{
  for(const angle of [false,45,'180']){
    const f=setup(()=>new Response(JSON.stringify({cameraReady:true,wifiConnected:true,rotation:angle}),{headers:{'Content-Type':'application/json'}}));
    await f.viewer.start();assert.equal(f.calls.length,1);assert.equal(f.viewer.getState().phase,'error');f.viewer.stop();
  }
  const f=setup(endpoint=>endpoint.endsWith('/health')?health():new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg','X-Camera-Rotation':'180junk'}}));
  await f.viewer.start();assert.equal(f.viewer.getState().frameUrl,null);assert.equal(f.viewer.getState().phase,'error');f.viewer.stop();
});

test('enrollment preview verifies the exact station before frames and pins every request to it',async()=>{
  for(const stationId of [undefined,'OTHER-GATE']){
    const f=setup(()=>health(stationId),{expectedStationId:'GATE-01',intervalMs:1000});
    await f.viewer.start();assert.equal(f.viewer.getState().phase,'error');assert.match(f.viewer.getState().message,/không thuộc trạm đang đăng ký/);assert.equal(f.viewer.getState().health,null);assert.equal(f.viewer.getState().frameUrl,null);assert.equal(f.timers.size,0);assert.deepEqual(f.calls.map(row=>row[0]),['/api/camera/health']);
  }
  const f=setup(endpoint=>endpoint.endsWith('/health')?health('GATE-01'):image(),{expectedStationId:'GATE-01',intervalMs:1000});
  await f.viewer.start();assert.equal(f.viewer.getState().phase,'live');assert.equal(f.tick().delay,1000);await flush();
  for(const [,request]of f.calls)assert.deepEqual(request.headers,{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01'});
  f.viewer.stop();
});
