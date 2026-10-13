import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createCameraProxy} from '../scripts/camera-proxy.mjs';

const jpeg=Buffer.from([0xff,0xd8,0x00,0x01,0xff,0xd9]);
const config={camera:{url:'http://192.168.0.110/capture',token:'test-camera-secret'}};
const asJson=value=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
const asJpeg=()=>new Response(jpeg,{headers:{'Content-Type':'image/jpeg'}});
async function fixture(options={}){
  let calls=0;const seen=[];
  const proxy=createCameraProxy({configPath:'fixed-config.json',readConfig:async file=>{assert.equal(file,'fixed-config.json');return JSON.stringify(config);},fetchImpl:async(url,request)=>{calls++;seen.push({url:String(url),request});return String(url).endsWith('/health')?asJson({cameraReady:true,wifiConnected:true,sensor:'OV5640',board:'ESP32-S3',psramBytes:8388608,token:'never expose',extra:{secret:true}}):asJpeg();},...options});
  const server=http.createServer((req,res)=>proxy(req,res,new URL(req.url,'http://localhost')));
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const url=`http://127.0.0.1:${server.address().port}`;
  return {url,server,seen,get calls(){return calls;},request:(route='/api/camera/frame',init={})=>fetch(url+route,{...init,headers:{'X-Camera-Preview':'1',...init.headers}}),close:()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();})};
}

test('camera proxy keeps the fixed token on the server and returns only fresh JPEG and safe health fields',async()=>{
  const f=await fixture();try{
    const health=await f.request('/api/camera/health');assert.equal(health.status,200);
    assert.deepEqual(await health.json(),{cameraReady:true,wifiConnected:true,sensor:'OV5640',board:'ESP32-S3',psramBytes:8388608,rotation:0});
    const response=await f.request();assert.equal(response.status,200);assert.equal(response.headers.get('content-type'),'image/jpeg');assert.match(response.headers.get('cache-control'),/no-store/);assert.equal(response.headers.get('cross-origin-resource-policy'),'same-origin');assert.equal(response.headers.get('access-control-allow-origin'),null);assert.deepEqual(Buffer.from(await response.arrayBuffer()),jpeg);
    assert.deepEqual(f.seen.map(row=>row.url),['http://192.168.0.110/health','http://192.168.0.110/capture']);
    for(const {request}of f.seen){assert.equal(request.headers['X-Camera-Token'],'test-camera-secret');assert.equal(request.redirect,'error');assert.equal(request.signal.aborted,false);}
  }finally{await f.close();}
});

test('proxy rejects foreign Origin, missing custom header, cross-site fetch, rebound Host and URL parameters before contacting camera',async()=>{
  const f=await fixture();try{
    for(const init of [{headers:{Origin:'https://foreign.example'}},{headers:{'X-Camera-Preview':''}},{headers:{'Sec-Fetch-Site':'cross-site'}}])assert.equal((await f.request('/api/camera/frame',init)).status,403,JSON.stringify(init));
    const reboundStatus=await new Promise((resolve,reject)=>{const req=http.get(f.url+'/api/camera/frame',{headers:{Host:'camera-attacker.example','X-Camera-Preview':'1'}},response=>{response.resume();resolve(response.statusCode);});req.once('error',reject);});
    assert.equal(reboundStatus,403);
    assert.equal((await f.request('/api/camera/frame?url=http://foreign.example')).status,400);
    assert.equal((await f.request('/api/camera/frame',{method:'OPTIONS'})).status,405);
    assert.equal((await f.request('/api/camera/frame',{method:'POST'})).status,405);
    assert.equal(f.calls,0);
    assert.equal((await f.request('/api/camera/frame',{headers:{Origin:f.url}})).status,200);
  }finally{await f.close();}
});

test('invalid or missing camera configuration never contacts a network target or echoes secrets',async()=>{
  for(const camera of [{},{url:'file:///config.json',token:'secret'},{url:'http://secret@192.168.0.1/capture',token:'secret'},{url:'http://192.168.0.1/capture?token=secret',token:'secret'},{url:'http://192.168.0.1/health',token:'secret'},{url:'http://192.168.0.1/capture',token:'secret\r\nBad: true'}]){
    const f=await fixture({readConfig:async()=>JSON.stringify({camera})});try{const response=await f.request();assert.equal(response.status,503);const body=await response.text();assert.match(body,/CAMERA_CONFIG/);assert.doesNotMatch(body,/secret|file:|192\.168/);assert.equal(f.calls,0);}finally{await f.close();}
  }
});

test('proxy rejects camera authentication failures, non-JPEG and oversized bodies',async()=>{
  const samples=[new Response('private-token',{status:401}),new Response('not image',{headers:{'Content-Type':'text/plain'}}),new Response('html',{headers:{'Content-Type':'image/jpeg'}}),new Response(jpeg,{headers:{'Content-Type':'image/jpeg','Content-Length':String(6*1024*1024)}}),new Response(Buffer.alloc(5*1024*1024+1),{headers:{'Content-Type':'image/jpeg'}})];
  for(const upstream of samples){const f=await fixture({fetchImpl:async()=>upstream});try{const response=await f.request();assert.equal(response.status,502);assert.doesNotMatch(await response.text(),/private-token|test-camera-secret/);}finally{await f.close();}}
});

test('camera redirects are refused rather than forwarding the secret token to another server',async()=>{
  let captureCalls=0,redirectCalls=0;
  const upstream=http.createServer((req,res)=>{if(req.url==='/capture'){captureCalls++;res.writeHead(302,{Location:'/steal-token'});res.end();}else{redirectCalls++;res.end();}});
  upstream.listen(0,'127.0.0.1');await once(upstream,'listening');
  const f=await fixture({readConfig:async()=>JSON.stringify({camera:{url:`http://127.0.0.1:${upstream.address().port}/capture`,token:'camera-secret'}}),fetchImpl:fetch});
  try{assert.equal((await f.request()).status,502);assert.equal(captureCalls,1);assert.equal(redirectCalls,0);}finally{await f.close();await new Promise(resolve=>{upstream.close(resolve);upstream.closeAllConnections();});}
});

test('timeout cancels a stalled upstream and permits the next request',async()=>{
  let aborted=0;
  const f=await fixture({timeoutMs:25,fetchImpl:(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>{aborted++;reject(new Error('aborted'));},{once:true}))});
  try{for(let i=0;i<2;i++){const response=await f.request();assert.equal(response.status,504);assert.equal((await response.json()).error,'CAMERA_TIMEOUT');}assert.equal(aborted,2);}finally{await f.close();}
});

test('proxy bounds concurrent requests and aborts capture when the local browser disconnects',async()=>{
  let resolveStarted,resolveAborted;const started=new Promise(resolve=>{resolveStarted=resolve;}),aborted=new Promise(resolve=>{resolveAborted=resolve;});
  const f=await fixture({fetchImpl:(url,{signal})=>{resolveStarted();return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>{resolveAborted();reject(new Error('aborted'));},{once:true}));}});
  const req=http.get(f.url+'/api/camera/frame',{headers:{'X-Camera-Preview':'1'}});req.on('error',()=>{});
  try{await started;assert.equal((await f.request()).status,429);req.destroy();await aborted;}finally{req.destroy();await f.close();}
});

test('enrollment preview verifies the local station and reports its identity without trusting upstream metadata',async()=>{
  const f=await fixture({readConfig:async()=>JSON.stringify({...config,stationId:'GATE-01'}),fetchImpl:async(url,request)=>{
    assert.equal(request.headers['X-Camera-Station'],undefined,'station expectations stay on the laptop');
    assert.equal(request.headers['X-Camera-Token'],config.camera.token);
    return String(url).endsWith('/health')?asJson({cameraReady:true,wifiConnected:true,stationId:'WRONG-UPSTREAM',token:'secret-on-camera',privateKey:'never expose'}):asJpeg();
  }});
  try{
    const health=await f.request('/api/camera/health',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(health.status,200);
    assert.deepEqual(await health.json(),{cameraReady:true,wifiConnected:true,stationId:'GATE-01',rotation:0});
    const frame=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(frame.status,200);assert.deepEqual(Buffer.from(await frame.arrayBuffer()),jpeg);
  }finally{await f.close();}
});

test('a missing, invalid or different expected station never captures from another local camera',async()=>{
  for(const stationId of [undefined,'GATE-01','invalid/station',null,17]){
    const f=await fixture({readConfig:async()=>JSON.stringify({...config,...(stationId===undefined?{}:{stationId})})});
    try{
      for(const expectedStationId of ['GATE-02','','invalid/station','a'.repeat(129)]){
        const response=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':expectedStationId}});assert.equal(response.status,409);
        const body=await response.json();assert.equal(body.error,'CAMERA_STATION');assert.equal(body.message,'Camera trên laptop này không thuộc trạm đăng ký đã chọn.');
        assert.doesNotMatch(JSON.stringify(body),/test-camera-secret|192\.168|invalid\/station/);
      }
      if(stationId!=='GATE-01')assert.equal((await f.request('/api/camera/health',{headers:{'X-Camera-Station':'GATE-01'}})).status,409);
      assert.equal(f.calls,0,'station mismatch must be checked before camera network reads');
    }finally{await f.close();}
  }
});

test('local station changes after health was read are rechecked before every frame',async()=>{
  let stationId='GATE-01';const f=await fixture({readConfig:async()=>JSON.stringify({...config,stationId})});
  try{
    const health=await f.request('/api/camera/health',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(health.status,200);assert.equal((await health.json()).stationId,'GATE-01');assert.equal(f.calls,1);
    stationId='GATE-02';const frame=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(frame.status,409);assert.equal((await frame.json()).error,'CAMERA_STATION');assert.equal(f.calls,1);
    const ordinary=await f.request('/api/camera/health');assert.equal(ordinary.status,200);assert.equal((await ordinary.json()).stationId,'GATE-02');assert.equal(f.calls,2);
  }finally{await f.close();}
});

test('ordinary camera page omits invalid local station metadata and never exposes upstream station identity',async()=>{
  for(const stationId of [undefined,'bad/station',null,17,'a'.repeat(129)]){
    const f=await fixture({readConfig:async()=>JSON.stringify({...config,...(stationId===undefined?{}:{stationId})}),fetchImpl:async()=>asJson({cameraReady:true,wifiConnected:true,stationId:'UPSTREAM-STATION',token:'secret-on-camera'})});
    try{
      const response=await f.request('/api/camera/health');assert.equal(response.status,200);assert.deepEqual(await response.json(),{cameraReady:true,wifiConnected:true,rotation:0});
    }finally{await f.close();}
  }
});

test('camera orientation comes only from local configuration and is attached to each unchanged JPEG',async()=>{
  for(const rotation of [undefined,0,90,180,270]){
    const f=await fixture({readConfig:async()=>JSON.stringify({...config,stationId:'GATE-01',camera:{...config.camera,...(rotation===undefined?{}:{rotation})}}),fetchImpl:async url=>String(url).endsWith('/health')?asJson({cameraReady:true,wifiConnected:true,rotation:45,token:'private-upstream'}):asJpeg()});
    try{
      const health=await f.request('/api/camera/health',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(health.status,200);assert.deepEqual(await health.json(),{cameraReady:true,wifiConnected:true,stationId:'GATE-01',rotation:rotation??0});
      const frame=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(frame.status,200);assert.equal(frame.headers.get('x-camera-rotation'),String(rotation??0));assert.deepEqual(Buffer.from(await frame.arrayBuffer()),jpeg);
    }finally{await f.close();}
  }
});

test('invalid camera rotations fail before any upstream request and do not expose configuration',async()=>{
  for(const rotation of [true,false,'180',45,-90,360,null,[],{}]){
    const f=await fixture({readConfig:async()=>JSON.stringify({...config,camera:{...config.camera,rotation}})});
    try{
      for(const endpoint of ['/api/camera/health','/api/camera/frame']){
        const response=await f.request(endpoint);assert.equal(response.status,503);assert.equal(response.headers.get('x-camera-rotation'),null);const body=await response.text();assert.match(body,/CAMERA_CONFIG/);assert.doesNotMatch(body,/test-camera-secret|192\.168/);
      }
      assert.equal(f.calls,0);
    }finally{await f.close();}
  }
});

test('a frame uses orientation from the same configuration snapshot as its camera request',async()=>{
  let rotation=90;const seen=[];
  const f=await fixture({readConfig:async()=>JSON.stringify({...config,stationId:'GATE-01',camera:{...config.camera,rotation}}),fetchImpl:async url=>{
    seen.push(String(url));if(String(url).endsWith('/health'))return asJson({cameraReady:true,wifiConnected:true});rotation=270;return asJpeg();
  }});
  try{
    const health=await f.request('/api/camera/health');assert.equal((await health.json()).rotation,90);
    rotation=180;
    const frame=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(frame.status,200);assert.equal(frame.headers.get('x-camera-rotation'),'180');assert.deepEqual(Buffer.from(await frame.arrayBuffer()),jpeg);
    const next=await f.request('/api/camera/frame',{headers:{'X-Camera-Station':'GATE-01'}});assert.equal(next.status,200);assert.equal(next.headers.get('x-camera-rotation'),'270');
    assert.deepEqual(seen,['http://192.168.0.110/health','http://192.168.0.110/capture','http://192.168.0.110/capture']);
  }finally{await f.close();}
});
