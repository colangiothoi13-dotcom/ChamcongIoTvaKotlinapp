import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createCameraProxy} from '../scripts/camera-proxy.mjs';

const token='private-admin-token-for-testing-only';
const config={stationId:'GATE-01',port:8765,adminToken:token,camera:{url:'http://192.168.0.110/capture',token:'private-camera-token'}};
const value={status:'FACE_BLOCKED',qualityIssue:'mask',message:'Phát hiện khẩu trang. Tháo khẩu trang.',updatedAt:'2026-10-13T00:00:00.123456+00:00',accessoryGateEnabled:true,
  scan:{status:'NO_MATCH',message:'Chưa khớp khuôn mặt đã đăng ký.',updatedAt:'2026-10-13T00:00:00.123456+00:00'},
  attendance:{status:'ACCEPTED',employeeName:'Nguyễn Văn A',message:'Đã ghi công trên Firebase.',updatedAt:'2026-10-13T00:00:00.123456+00:00'}};
const asJson=data=>new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}});
async function fixture(options={}){
  const seen=[];let reads=0;
  const proxy=createCameraProxy({configPath:'fixed-private-config.json',readConfig:async file=>{assert.equal(file,'fixed-private-config.json');reads++;return JSON.stringify(config);},fetchImpl:async(url,request)=>{seen.push({url:String(url),request});return asJson({...value,employeeId:'do-not-return',gallery:{private:true},adminToken:'do-not-return'});},...options});
  const server=http.createServer((req,res)=>proxy(req,res,new URL(req.url,'http://localhost')));server.listen(0,'127.0.0.1');await once(server,'listening');
  const url=`http://127.0.0.1:${server.address().port}`;
  return {url,seen,get reads(){return reads;},request:(route='/api/camera/face-status',init={})=>fetch(url+route,{...init,headers:{'X-Camera-Preview':'1','X-Camera-Station':'GATE-01',...init.headers}}),close:()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();})};
}

test('face status uses fixed loopback Admin endpoint and strips all unrelated fields',async()=>{
  const f=await fixture();try{
    const response=await f.request();assert.equal(response.status,200);assert.deepEqual(await response.json(),value);assert.match(response.headers.get('cache-control'),/no-store/);assert.equal(response.headers.get('access-control-allow-origin'),null);
    assert.equal(f.seen.length,1);assert.equal(f.seen[0].url,'http://127.0.0.1:8765/v1/face-status');assert.equal(f.seen[0].request.headers['X-Admin-Token'],token);assert.equal(f.seen[0].request.redirect,'error');
  }finally{await f.close();}
});

test('foreign callers, URL parameters and station mismatches never reach the service',async()=>{
  const f=await fixture();try{
    for(const headers of [{Origin:'https://foreign.example'},{'X-Camera-Preview':''},{'Sec-Fetch-Site':'cross-site'}])assert.equal((await f.request('/api/camera/face-status',{headers})).status,403);
    const rebound=await new Promise((resolve,reject)=>{const req=http.get(f.url+'/api/camera/face-status',{headers:{Host:'attacker.example','X-Camera-Preview':'1','X-Camera-Station':'GATE-01'}},res=>{res.resume();resolve(res.statusCode);});req.once('error',reject);});assert.equal(rebound,403);
    for(const expected of ['', 'OTHER', 'bad/station'])assert.equal((await f.request('/api/camera/face-status',{headers:{'X-Camera-Station':expected}})).status,409);
    assert.equal((await f.request('/api/camera/face-status?url=http://foreign.example')).status,400);
    assert.equal((await f.request('/api/camera/face-status',{method:'POST'})).status,405);assert.equal(f.seen.length,0);
  }finally{await f.close();}
});

test('unsafe private station configurations fail without leaking credentials or contacting a target',async()=>{
  for(const change of [{port:0},{port:'8765'},{adminToken:'short'},{adminToken:'a'.repeat(24)+'\nsecret'},{adminToken:'REPLACE_WITH_SECRET_TOKEN'},{stationId:null},{stationId:'bad/station'}]){
    let calls=0;const f=await fixture({readConfig:async()=>JSON.stringify({...config,...change}),fetchImpl:async()=>{calls++;return asJson(value);}});
    try{const response=await f.request();assert.equal(response.status,503);assert.doesNotMatch(await response.text(),/private-admin|private-camera|192\.168/);assert.equal(calls,0);}finally{await f.close();}
  }
});

test('invalid, oversized or non-UTC feedback is rejected with a safe service message',async()=>{
  const responses=[()=>asJson({...value,status:'INVALID'}),()=>asJson({...value,qualityIssue:'other'}),()=>asJson({...value,message:'x'.repeat(301)}),()=>asJson({...value,accessoryGateEnabled:1}),()=>asJson({...value,updatedAt:'2026-02-30T00:00:00Z'}),()=>asJson({...value,updatedAt:'2026-10-13T07:00:00+07:00'}),()=>asJson({...value,scan:{...value.scan,status:'INVALID'}}),()=>asJson({...value,attendance:{...value.attendance,employeeName:'x'.repeat(121)}}),()=>new Response('x'.repeat(17000),{headers:{'Content-Type':'application/json'}}),()=>new Response('private upstream error',{status:401}),()=>new Response('<html>login</html>',{headers:{'Content-Type':'text/html'}})];
  for(const upstream of responses){
    const f=await fixture({fetchImpl:async()=>upstream()});try{const response=await f.request();assert.equal(response.status,503);const body=await response.text();assert.match(body,/FACE_STATUS_UNAVAILABLE/);assert.match(body,/ảnh camera vẫn có thể xem riêng/);assert.doesNotMatch(body,/private upstream|private-admin|192\.168/);}finally{await f.close();}
  }
});

test('timeout aborts status read and camera frame busy state does not block independent status',async()=>{
  let statusSignal;const timed=await fixture({timeoutMs:10,fetchImpl:async(url,request)=>{statusSignal=request.signal;return new Promise((resolve,reject)=>request.signal.addEventListener('abort',()=>reject(new Error('private timeout')),{once:true}));}});
  try{const response=await timed.request();assert.equal(response.status,503);assert.equal(statusSignal.aborted,true);}finally{await timed.close();}
  let release,markStarted;const started=new Promise(resolve=>{markStarted=resolve;});
  const f=await fixture({fetchImpl:async url=>{
    if(String(url).endsWith('/capture')){markStarted();return new Promise(resolve=>{release=()=>resolve(new Response(new Uint8Array([255,216,255,217]),{headers:{'Content-Type':'image/jpeg'}}));});}
    return asJson(value);
  }});
  try{
    const frame=f.request('/api/camera/frame');await started;
    const status=await f.request();assert.equal(status.status,200);assert.deepEqual(await status.json(),value);
    release();assert.equal((await frame).status,200);
  }finally{release?.();await f.close();}
});
