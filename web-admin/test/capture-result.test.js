import test from 'node:test';
import assert from 'node:assert/strict';
import {createCaptureResultController,updateCaptureResult} from '../src/capture-result.js';
import {SIX_POSE_PLAN} from '../src/face-capture-plan.js';

const flush=async()=>{for(let i=0;i<24;i++)await Promise.resolve();};
const command=(overrides={})=>({requestId:'request_1',action:'ENROLL',sampleCount:10,captureMode:'MANUAL',status:'CAPTURING',captureRequestSeq:1,captureConsumedSeq:1,captureLastReason:'quality_ok',savedCount:1,...overrides});
const jpeg=(overrides={})=>new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg','X-Enrollment-Request':'request_1','X-Capture-Sequence':'1','X-Capture-Reason':'quality_ok','X-Capture-Saved':'1',...overrides}});
function fixture(fetchImpl=async()=>jpeg()){
  let urlId=0,timerId=0,hidden=false;const calls=[],revoked=[],states=[],timers=new Map();
  const viewer=createCaptureResultController({expectedStationId:'GATE-01',fetchImpl:(...args)=>{calls.push(args);return fetchImpl(...args);},createUrl:()=>`blob:capture-${++urlId}`,revokeUrl:url=>revoked.push(url),onChange:state=>states.push(state),isHidden:()=>hidden,setTimer:(callback,delay)=>{timers.set(++timerId,{callback,delay});return timerId;},clearTimer:id=>timers.delete(id)});
  return {viewer,calls,revoked,states,timers,hide(){hidden=true;viewer.visibilityChanged();},show(){hidden=false;viewer.visibilityChanged();},tick(){const [id,timer]=timers.entries().next().value;timers.delete(id);timer.callback();return timer;}};
}

test('six-picture results use the worker total and refuse mismatched or missing new-plan totals',async()=>{
  const guided=command({sampleCount:6,capturePlan:SIX_POSE_PLAN});
  const f=fixture(async()=>jpeg({'X-Capture-Total':'6','X-Capture-Saved':'2'}));f.viewer.observe(guided,'request_1');await flush();assert.equal(f.viewer.getState().phase,'ready');assert.match(f.viewer.getState().message,/2\/6/);f.viewer.dispose();
  for(const headers of [{},{'X-Capture-Total':'10'},{'X-Capture-Total':'6','X-Capture-Saved':'7'},{'X-Capture-Total':'5'}]){const bad=fixture(async()=>jpeg(headers));bad.viewer.observe(guided,'request_1');await flush();assert.equal(bad.viewer.getState().phase,'error');assert.equal(bad.viewer.getState().frameUrl,null);bad.viewer.dispose();}
});

test('a capture preview is fetched only after the worker acknowledges this click',async()=>{
  const f=fixture();f.viewer.observe(command({captureRequestSeq:0,captureConsumedSeq:0,savedCount:0}),'request_1');assert.equal(f.calls.length,0);assert.equal(f.viewer.getState().phase,'idle');
  f.viewer.observe(command({captureConsumedSeq:0,savedCount:0}),'request_1');assert.equal(f.calls.length,0);assert.equal(f.viewer.getState().phase,'loading');assert.equal(f.viewer.getState().frameUrl,null);
  f.viewer.observe(command(),'request_1');await flush();assert.equal(f.calls.length,1);assert.equal(f.viewer.getState().phase,'ready');
  assert.equal(f.calls[0][0],'/api/camera/enrollment-preview/request_1/1');assert.deepEqual(f.calls[0][1].headers,{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01'});assert.equal(f.calls[0][1].cache,'no-store');assert.equal(f.calls[0][1].redirect,'error');assert.equal(f.calls[0][1].credentials,'same-origin');
  f.viewer.observe(command(),'request_1');await flush();assert.equal(f.calls.length,1,'duplicate command snapshots must not reload the same capture');f.viewer.dispose();
});

test('a rejected photo still appears and its metadata shows zero saved samples',async()=>{
  const f=fixture(async()=>jpeg({'X-Capture-Reason':'low_quality','X-Capture-Saved':'0'}));f.viewer.observe(command({captureLastReason:'low_quality',savedCount:0}),'request_1');await flush();
  const state=f.viewer.getState();assert.equal(state.phase,'ready');assert.equal(state.reason,'low_quality');assert.equal(state.saved,0);assert.equal(state.sequence,1);assert.equal(state.frameUrl,'blob:capture-1');assert.match(state.message,/chưa đạt/);assert.match(state.message,/0\/10/);f.viewer.dispose();
});

test('the displayed accepted count comes from the inspected capture metadata',async()=>{
  const f=fixture(async()=>jpeg({'X-Capture-Saved':'3'}));f.viewer.observe(command({savedCount:4}),'request_1');await flush();assert.equal(f.viewer.getState().saved,3);assert.match(f.viewer.getState().message,/Tổng 3\/10/);f.viewer.dispose();
});

test('another request or sequence and invalid JPEG metadata cannot replace the capture',async()=>{
  for(const headers of [{'X-Enrollment-Request':'other_request'},{'X-Capture-Sequence':'2'},{'X-Capture-Reason':'unexpected'},{'X-Capture-Saved':'11'},{'X-Capture-Saved':'01'},{'Content-Type':'text/html'}]){
    const f=fixture(async()=>jpeg(headers));f.viewer.observe(command(),'request_1');await flush();assert.equal(f.viewer.getState().phase,'error',JSON.stringify(headers));assert.equal(f.viewer.getState().frameUrl,null);assert.match(f.viewer.getState().message,/không khớp/);assert.deepEqual(f.revoked,[]);f.viewer.dispose();
  }
});

test('only valid manual enrollment commands can fetch a capture preview',async()=>{
  for(const update of [{requestId:'other_request'},{captureMode:'AUTO'},{action:'DELETE'},{status:'PENDING'},{status:'CANCELLED'},{cancelRequested:true},{captureRequestSeq:101,captureConsumedSeq:101},{captureRequestSeq:1,captureConsumedSeq:2},{captureRequestSeq:1.5},{captureConsumedSeq:-1}]){
    const f=fixture();f.viewer.observe(command(update),'request_1');await flush();assert.equal(f.calls.length,0,JSON.stringify(update));f.viewer.dispose();
  }
  for(const requestId of ['../request','request/1','','a'.repeat(129)]){const f=fixture();f.viewer.observe(command(),requestId);assert.equal(f.calls.length,0);f.viewer.dispose();}
});

test('a newer click revokes the previous image and ignores the delayed old response',async()=>{
  let resolveOld;const f=fixture(url=>url.endsWith('/1')?new Promise(resolve=>{resolveOld=resolve;}):jpeg({'X-Capture-Sequence':'2','X-Capture-Saved':'2'}));
  f.viewer.observe(command(),'request_1');await flush();const oldSignal=f.calls[0][1].signal;
  f.viewer.observe(command({captureRequestSeq:2,captureConsumedSeq:2,savedCount:2}),'request_1');await flush();assert.equal(oldSignal.aborted,true);assert.equal(f.viewer.getState().sequence,2);assert.equal(f.viewer.getState().frameUrl,'blob:capture-1');
  resolveOld(jpeg());await flush();assert.equal(f.viewer.getState().sequence,2);assert.equal(f.viewer.getState().frameUrl,'blob:capture-1');
  f.viewer.observe(command({captureRequestSeq:3,captureConsumedSeq:2}),'request_1');assert.deepEqual(f.revoked,['blob:capture-1']);assert.equal(f.viewer.getState().frameUrl,null);assert.equal(f.calls.length,2);f.viewer.dispose();
});

test('switching requests cancels a delayed response rather than showing another employee photo',async()=>{
  let resolveOld;const f=fixture(url=>url.includes('request_1')?new Promise(resolve=>{resolveOld=resolve;}):jpeg({'X-Enrollment-Request':'request_2'}));
  f.viewer.observe(command(),'request_1');await flush();const oldSignal=f.calls[0][1].signal;f.viewer.observe(command({requestId:'request_2'}),'request_2');await flush();assert.equal(oldSignal.aborted,true);assert.equal(f.viewer.getState().frameUrl,'blob:capture-1');
  resolveOld(jpeg());await flush();assert.equal(f.viewer.getState().frameUrl,'blob:capture-1');assert.equal(f.calls.at(-1)[0],'/api/camera/enrollment-preview/request_2/1');f.viewer.dispose();assert.deepEqual(f.revoked,['blob:capture-1']);
});

test('missing preview retries twice with a bound and a user retry can recover',async()=>{
  let missing=true;const f=fixture(async()=>missing?new Response(JSON.stringify({message:'Ảnh chưa có'}),{status:404,headers:{'Content-Type':'application/json'}}):jpeg());
  f.viewer.observe(command(),'request_1');await flush();assert.equal(f.calls.length,1);assert.equal(f.tick().delay,200);await flush();assert.equal(f.calls.length,2);assert.equal(f.tick().delay,200);await flush();
  assert.equal(f.calls.length,3);assert.equal(f.timers.size,0);assert.equal(f.viewer.getState().phase,'error');assert.equal(f.viewer.getState().frameUrl,null);missing=false;f.viewer.retry();await flush();assert.equal(f.calls.length,4);assert.equal(f.viewer.getState().phase,'ready');f.viewer.dispose();
});

test('a replacement command cancels the 404 retry timer',async()=>{
  const f=fixture(async()=>new Response('missing',{status:404}));f.viewer.observe(command(),'request_1');await flush();assert.equal(f.timers.size,1);const oldSignal=f.calls[0][1].signal;
  f.viewer.observe(command({captureRequestSeq:2,captureConsumedSeq:1}),'request_1');assert.equal(f.timers.size,0);assert.equal(oldSignal.aborted,true);assert.equal(f.calls.length,1);f.viewer.dispose();
});

test('hiding and disposal abort pending work, revoke photos and leave no retry timer',async()=>{
  const f=fixture();f.viewer.observe(command(),'request_1');await flush();f.hide();assert.equal(f.viewer.getState().frameUrl,null);assert.deepEqual(f.revoked,['blob:capture-1']);assert.equal(f.timers.size,0);
  f.viewer.observe(command(),'request_1');assert.equal(f.calls.length,1);f.show();await flush();assert.equal(f.calls.length,2);assert.equal(f.viewer.getState().frameUrl,'blob:capture-2');f.viewer.dispose();f.viewer.dispose();assert.deepEqual(f.revoked,['blob:capture-1','blob:capture-2']);f.viewer.retry();f.viewer.observe(command(),'request_1');assert.equal(f.calls.length,2);
  let resolve;const delayed=fixture(()=>new Promise(done=>{resolve=done;}));delayed.viewer.observe(command(),'request_1');await flush();const signal=delayed.calls[0][1].signal;delayed.viewer.dispose();assert.equal(signal.aborted,true);resolve(jpeg());await flush();assert.equal(delayed.viewer.getState().frameUrl,null);assert.deepEqual(delayed.revoked,[]);
});

test('a camera error clears an older preview and never fetches stale cached pixels',async()=>{
  const f=fixture();f.viewer.observe(command(),'request_1');await flush();f.viewer.observe(command({captureRequestSeq:2,captureConsumedSeq:2,captureLastReason:'camera_error'}),'request_1');await flush();assert.equal(f.calls.length,1);assert.equal(f.viewer.getState().phase,'error');assert.equal(f.viewer.getState().frameUrl,null);assert.equal(f.viewer.getState().sequence,2);assert.deepEqual(f.revoked,['blob:capture-1']);assert.match(f.viewer.getState().message,/chưa lấy được ảnh camera/);f.viewer.dispose();
});

test('page pause revokes the image and permits restore; unreadable images offer an explicit retry',async()=>{
  const f=fixture();f.viewer.observe(command(),'request_1');await flush();f.viewer.pause();assert.equal(f.viewer.getState().frameUrl,null);assert.deepEqual(f.revoked,['blob:capture-1']);f.viewer.visibilityChanged();await flush();assert.equal(f.calls.length,2);assert.equal(f.viewer.getState().frameUrl,'blob:capture-2');
  f.viewer.imageFailed();assert.equal(f.viewer.getState().phase,'error');assert.equal(f.viewer.getState().frameUrl,null);assert.deepEqual(f.revoked,['blob:capture-1','blob:capture-2']);assert.match(f.viewer.getState().message,/Thử tải ảnh/);f.viewer.retry();await flush();assert.equal(f.calls.length,3);assert.equal(f.viewer.getState().phase,'ready');f.viewer.dispose();
});

test('preview DOM shows the captured photo separately from the live image and uses safe text',()=>{
  const nodes=new Map();const node=id=>{if(!nodes.has(id))nodes.set(id,{hidden:false,textContent:'',className:'',attributes:{},getAttribute(name){return this.attributes[name];},setAttribute(name,value){this.attributes[name]=value;},removeAttribute(name){delete this.attributes[name];}});return nodes.get(id);};const root={querySelector:selector=>node(selector.slice(1))};
  updateCaptureResult(root,{phase:'ready',frameUrl:'blob:exact-capture',message:'<img src=x onerror=bad()>',reason:'low_quality',saved:0});assert.equal(node('face-capture-image').attributes.src,'blob:exact-capture');assert.equal(node('face-capture-image').hidden,false);assert.equal(node('face-capture-placeholder').hidden,true);assert.equal(node('face-capture-result').textContent,'<img src=x onerror=bad()>');assert.equal(node('face-capture-preview-retry').hidden,true);
  updateCaptureResult(root,{phase:'error',frameUrl:null,message:'Không đọc được ảnh'});assert.equal(node('face-capture-image').hidden,true);assert.equal(node('face-capture-image').attributes.src,undefined);assert.equal(node('face-capture-placeholder').hidden,false);assert.equal(node('face-capture-preview-retry').hidden,false);assert.match(node('face-capture-result').className,/text-warning/);
});
