import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createCameraProxy} from '../scripts/camera-proxy.mjs';

const jpeg=Buffer.from([0xff,0xd8,0x00,0x01,0xff,0xd9]);
const privateToken='private-admin-token-for-local-tests';
const config={stationId:'GATE-01',port:8765,adminToken:privateToken,camera:{url:'http://192.168.0.110/capture',token:'private-camera-test-token',rotation:180}};
const route='/api/camera/enrollment-preview/request_1/1';
const capture=(extraHeaders={},body=jpeg)=>new Response(body,{headers:{'Content-Type':'image/jpeg','X-Enrollment-Request':'request_1','X-Capture-Sequence':'1','X-Capture-Reason':'quality_ok','X-Capture-Saved':'1',...extraHeaders}});

test('six-picture preview totals are forwarded and impossible saved counts are refused',async()=>{
  const ok=await fixture({fetchImpl:async()=>capture({'X-Capture-Total':'6','X-Capture-Saved':'6'})});try{const response=await ok.request();assert.equal(response.status,200);assert.equal(response.headers.get('X-Capture-Total'),'6');assert.equal(response.headers.get('X-Capture-Saved'),'6');}finally{await ok.close();}
  for(const metadata of [{'X-Capture-Total':'6','X-Capture-Saved':'7'},{'X-Capture-Total':'5'},{'X-Capture-Total':'06'},{'X-Capture-Total':'100'}]){const bad=await fixture({fetchImpl:async()=>capture(metadata)});try{const response=await bad.request();assert.equal(response.status,502);assert.doesNotMatch(await response.text(),/private-admin-token/);}finally{await bad.close();}}
});
async function fixture(options={}){
  const seen=[];const proxy=createCameraProxy({configPath:'local-only.json',readConfig:async path=>{assert.equal(path,'local-only.json');return JSON.stringify(config);},fetchImpl:async(url,init)=>{seen.push({url:String(url),init});return capture();},...options});
  const server=http.createServer((req,res)=>proxy(req,res,new URL(req.url,'http://localhost')));server.listen(0,'127.0.0.1');await once(server,'listening');const base=`http://127.0.0.1:${server.address().port}`;
  return {seen,base,request:(path=route,init={})=>fetch(base+path,{...init,headers:{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01',...init.headers}}),close:()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();})};
}

test('exact captured-photo proxy uses only the configured localhost station and keeps credentials server-side',async()=>{
  const f=await fixture();try{
    const response=await f.request();assert.equal(response.status,200);assert.deepEqual(Buffer.from(await response.arrayBuffer()),jpeg);assert.equal(response.headers.get('x-enrollment-request'),'request_1');assert.equal(response.headers.get('x-capture-sequence'),'1');assert.equal(response.headers.get('x-capture-saved'),'1');assert.equal(response.headers.get('x-capture-reason'),'quality_ok');assert.equal(response.headers.get('x-camera-rotation'),'0','these pixels were already rotated by the worker');assert.match(response.headers.get('cache-control'),/no-store/);assert.equal(response.headers.get('cross-origin-resource-policy'),'same-origin');assert.equal(response.headers.get('access-control-allow-origin'),null);
    assert.equal(f.seen.length,1);assert.equal(f.seen[0].url,'http://127.0.0.1:8765/v1/enrollment-preview/request_1/1');assert.deepEqual(f.seen[0].init.headers,{'X-Admin-Token':privateToken,Accept:'image/jpeg'});assert.equal(f.seen[0].init.redirect,'error');assert.equal(response.headers.get('x-admin-token'),null);assert.equal(response.headers.get('x-camera-token'),null);
  }finally{await f.close();}
});

test('rejected-photo preview retains its exact pixels and zero saved-count metadata',async()=>{
  const f=await fixture({fetchImpl:async()=>capture({'X-Capture-Reason':'low_quality','X-Capture-Saved':'0'})});try{const response=await f.request();assert.equal(response.status,200);assert.equal(response.headers.get('x-capture-reason'),'low_quality');assert.equal(response.headers.get('x-capture-saved'),'0');assert.deepEqual(Buffer.from(await response.arrayBuffer()),jpeg);}finally{await f.close();}
});

test('foreign requests, missing or mismatched stations and invalid route values never contact the station',async()=>{
  const f=await fixture();try{
    for(const headers of [{'X-Camera-Preview':''},{Origin:'https://foreign.example'},{'Sec-Fetch-Site':'cross-site'}])assert.equal((await f.request(route,{headers})).status,403);
    for(const station of ['','OTHER-GATE','bad/station','a'.repeat(129)])assert.equal((await f.request(route,{headers:{'X-Camera-Station':station}})).status,409);
    for(const path of ['/api/camera/enrollment-preview/request_1/0','/api/camera/enrollment-preview/request_1/01','/api/camera/enrollment-preview/request_1/101','/api/camera/enrollment-preview/bad%2Frequest/1',`${route}?url=http://attacker.example`,`${route}?token=${privateToken}`])assert.equal((await f.request(path)).status,400,path);
    assert.equal((await f.request(route,{method:'POST'})).status,405);assert.equal(f.seen.length,0);
  }finally{await f.close();}
});

test('invalid station endpoint configuration cannot select another URL or disclose credentials',async()=>{
  for(const patch of [{port:0},{port:65536},{port:'8765'},{stationId:'bad/station'},{adminToken:'short'},{adminToken:'REPLACE_WITH_RANDOM_DEVICE_TOKEN'},{adminToken:`${privateToken}\r\nInjected: yes`}]){
    const f=await fixture({readConfig:async()=>JSON.stringify({...config,...patch})});try{const response=await f.request();assert.equal(response.status,503);const body=await response.text();assert.doesNotMatch(body,/private-admin|private-camera|8765|192\.168|Injected/);assert.equal(f.seen.length,0);}finally{await f.close();}
  }
});

test('mismatched response identities and invalid JPEG, reason, count or size are refused',async()=>{
  const samples=[
    capture({'X-Enrollment-Request':'other_request'}),capture({'X-Capture-Sequence':'2'}),capture({'X-Capture-Reason':'unknown'}),capture({'X-Capture-Saved':'11'}),capture({'X-Capture-Saved':'01'}),capture({'Content-Type':'text/html'}),capture({},Buffer.from('fake JPEG private-body')),capture({'Content-Length':String(5*1024*1024+1)}),capture({},Buffer.alloc(5*1024*1024+1)),
  ];
  for(const upstream of samples){const f=await fixture({fetchImpl:async()=>upstream});try{const response=await f.request();assert.equal(response.status,502);assert.equal(response.headers.get('content-type'),'application/json; charset=utf-8');assert.match(response.headers.get('cache-control'),/no-store/);assert.doesNotMatch(await response.text(),/other_request|fake JPEG|private-body|private-admin|private-camera/);}finally{await f.close();}}
});

test('missing captures have a bounded readable 404 while upstream failures never reveal private bodies',async()=>{
  for(const status of [404,401,403,500]){
    const f=await fixture({fetchImpl:async()=>new Response('private-admin-token private exception details',{status})});try{const response=await f.request();assert.equal(response.status,status===404?404:502);const body=await response.json();assert.equal(body.error,status===404?'CAPTURE_PREVIEW_MISSING':'CAPTURE_PREVIEW_UNAVAILABLE');assert.doesNotMatch(JSON.stringify(body),/private-admin|exception details/);assert.match(response.headers.get('cache-control'),/no-store/);}finally{await f.close();}
  }
});

test('redirects cannot forward the Admin token even to another local station path',async()=>{
  let captureCalls=0,redirectCalls=0;const upstream=http.createServer((req,res)=>{if(req.url==='/v1/enrollment-preview/request_1/1'){captureCalls++;assert.equal(req.headers['x-admin-token'],privateToken);res.writeHead(302,{Location:'/steal-token'});res.end();}else{redirectCalls++;res.end();}});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');
  const f=await fixture({readConfig:async()=>JSON.stringify({...config,port:upstream.address().port}),fetchImpl:fetch});try{assert.equal((await f.request()).status,502);assert.equal(captureCalls,1);assert.equal(redirectCalls,0);}finally{await f.close();await new Promise(resolve=>{upstream.close(resolve);upstream.closeAllConnections();});}
});

test('a live camera snapshot in progress does not block the locally cached capture preview',async()=>{
  let resolveLive,markLive;const started=new Promise(resolve=>{markLive=resolve;});const f=await fixture({fetchImpl:(url)=>String(url).includes('/v1/enrollment-preview/')?capture():new Promise(resolve=>{resolveLive=resolve;markLive();})});
  try{const live=f.request('/api/camera/frame');await started;const response=await f.request();assert.equal(response.status,200);assert.deepEqual(Buffer.from(await response.arrayBuffer()),jpeg);resolveLive(new Response(jpeg,{headers:{'Content-Type':'image/jpeg'}}));assert.equal((await live).status,200);}finally{resolveLive?.(new Response(jpeg,{headers:{'Content-Type':'image/jpeg'}}));await f.close();}
});

test('timed-out capture preview requests abort the station read and can be retried independently',async()=>{
  let aborted=0;const f=await fixture({timeoutMs:25,fetchImpl:(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>{aborted++;reject(new Error(`secret ${privateToken}`));},{once:true}))});try{for(let i=0;i<2;i++){const response=await f.request();assert.equal(response.status,502);assert.doesNotMatch(await response.text(),/secret|private-admin/);}assert.equal(aborted,2);}finally{await f.close();}
});

test('closing the browser aborts its station preview request',async()=>{
  let started,stopped;const startedPromise=new Promise(resolve=>{started=resolve;}),stoppedPromise=new Promise(resolve=>{stopped=resolve;});const f=await fixture({fetchImpl:(url,{signal})=>{started();return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>{stopped();reject(new Error('aborted'));},{once:true}));}});
  const req=http.get(f.base+route,{headers:{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01'}});req.on('error',()=>{});try{await startedPromise;req.destroy();await stoppedPromise;}finally{req.destroy();await f.close();}
});
