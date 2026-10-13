import test from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import http from 'node:http';
import {X509Certificate} from 'node:crypto';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createAdminVerifier,generatePinnedCertificate,pinnedCertificate,mobileCameraServer,nativePreviewRequest} from '../scripts/mobile-camera.mjs';
import {createCameraProxy} from '../scripts/camera-proxy.mjs';

const firebaseConfig={apiKey:'fixture-public-api-key',projectId:'demo-mobile-camera'};
const claims=()=>({aud:firebaseConfig.projectId,iss:`https://securetoken.google.com/${firebaseConfig.projectId}`,
  sub:'admin-uid',exp:Math.floor(Date.now()/1000)+3600,firebase:{sign_in_provider:'password'}});
const jwt=payload=>[Buffer.from(JSON.stringify({alg:'RS256',typ:'JWT'})).toString('base64url'),
  Buffer.from(JSON.stringify(payload)).toString('base64url'),'fixture_signature_do_not_trust'].join('.');
const token=jwt(claims());
const authorization=`Bearer ${token}`;
const jpeg=Buffer.from([0xff,0xd8,0,1,0xff,0xd9]);
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const adminAccount=()=>({localId:'admin-uid',disabled:false,providerUserInfo:[{providerId:'password'}]});
const adminProfile=()=>({fields:{role:{stringValue:'ADMIN'},active:{booleanValue:true}}});

function authMock(expectedToken=token){
  const state={account:adminAccount(),profile:adminProfile(),authStatus:200,profileStatus:200,error:null,authBody:null,profileBody:null};
  const calls=[];
  const fetchImpl=async(url,options={})=>{
    calls.push({url:String(url),options});
    if(state.error)throw new Error(state.error);
    assert.equal(options.redirect,'error');
    assert.ok(options.signal instanceof AbortSignal);
    if(String(url).startsWith('https://identitytoolkit.googleapis.com/v1/accounts:lookup?')){
      assert.equal(String(url),`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${firebaseConfig.apiKey}`);
      assert.equal(options.method,'POST');
      assert.equal(JSON.parse(options.body).idToken,expectedToken);
      return state.authBody!=null?new Response(state.authBody,{status:state.authStatus}):json({users:[state.account]},state.authStatus);
    }
    assert.equal(String(url),'https://firestore.googleapis.com/v1/projects/demo-mobile-camera/databases/(default)/documents/users/admin-uid');
    assert.equal(options.headers.Authorization,`Bearer ${expectedToken}`);
    return state.profileBody!=null?new Response(state.profileBody,{status:state.profileStatus}):json(state.profile,state.profileStatus);
  };
  return {state,calls,fetchImpl};
}

async function removeFixture(directory){
  const resolved=path.resolve(directory);
  assert.equal(path.dirname(resolved),path.resolve(tmpdir()));
  assert.ok(path.basename(resolved).startsWith('mobile-camera-test-'));
  await rm(resolved,{recursive:true,force:true});
}

function tlsRequest(port,certificate,{route='/api/camera/frame',method='GET',headers={},ca=certificate,servername='localhost'}={}){
  return new Promise((resolve,reject)=>{
    const req=https.request({hostname:'127.0.0.1',port,path:route,method,ca,...(servername===undefined?{}:{servername}),agent:false,
      rejectUnauthorized:true,headers:{'X-Camera-Preview':'1',Authorization:authorization,...headers}},res=>{
      const tls={authorized:res.socket.authorized,fingerprint:res.socket.getPeerCertificate().fingerprint256};
      const chunks=[];
      res.on('data',chunk=>chunks.push(chunk));
      res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks),tls}));
      res.on('error',reject);
    });
    req.setTimeout(5000,()=>req.destroy(new Error('fixture request timed out')));
    req.once('error',reject);req.end();
  });
}

async function bridgeFixture(options={}){
  const directory=await mkdtemp(path.join(tmpdir(),'mobile-camera-test-'));
  const auth=authMock(),authorizedRequests=new WeakSet(),cameraCalls=[],configReads=[];
  const cameraConfig={camera:{url:'http://192.168.0.110/capture',token:'camera-secret-on-laptop'}};
  const proxy=createCameraProxy({configPath:'fixed-laptop-config.json',authorizeRequest:req=>authorizedRequests.has(req),
    readConfig:async file=>{configReads.push(file);assert.equal(file,'fixed-laptop-config.json');return JSON.stringify(cameraConfig);},
    fetchImpl:async(url,request)=>{cameraCalls.push({url:String(url),request});return String(url).endsWith('/health')
      ?json({cameraReady:true,wifiConnected:true,sensor:'OV5640',token:'must-not-leak'})
      :new Response(jpeg,{headers:{'Content-Type':'image/jpeg'}});}});
  let result;
  try{result=await mobileCameraServer({directory,addresses:['127.0.0.1'],config:firebaseConfig,proxy,authorizedRequests,port:0,fetchImpl:auth.fetchImpl,...options});}
  catch(error){await removeFixture(directory);throw error;}
  const certificate=await readFile(path.join(directory,'cert.pem'),'utf8'),port=result.server.address().port;
  return {...result,auth,cameraCalls,configReads,certificate,directory,port,
    request:options=>tlsRequest(port,certificate,options),
    async close(){await new Promise(resolve=>{result.server.close(resolve);result.server.closeAllConnections();});await removeFixture(directory);}};
}

test('generated certificate has valid dates, signature and laptop SANs; actual TLS trusts only the pinned laptop',async()=>{
  const f=await bridgeFixture();
  try{
    const certificate=new X509Certificate(f.certificate);
    assert.ok(Number.isFinite(Date.parse(certificate.validFrom)));
    assert.ok(Number.isFinite(Date.parse(certificate.validTo)));
    assert.ok(Date.parse(certificate.validFrom)<=Date.now());
    assert.ok(Date.parse(certificate.validTo)>Date.now());
    assert.equal(certificate.verify(certificate.publicKey),true);
    assert.equal(certificate.checkHost('localhost'),'localhost');
    assert.equal(certificate.checkIP('127.0.0.1'),'127.0.0.1');
    assert.equal(certificate.fingerprint256,f.fingerprint);
    const response=await f.request();
    assert.equal(response.status,200);
    assert.equal(response.tls.authorized,true);
    assert.equal(response.tls.fingerprint,f.fingerprint);
    await assert.rejects(f.request({ca:[]}),error=>/SELF_SIGNED|CERT/.test(error.code||''));
    const other=generatePinnedCertificate(['127.0.0.1']);
    await assert.rejects(f.request({ca:other.cert}),error=>/SELF_SIGNED|CERT/.test(error.code||''));
    await assert.rejects(f.request({servername:'wrong-laptop.example'}),error=>error.code==='ERR_TLS_CERT_ALTNAME_INVALID');
    assert.equal(f.cameraCalls.length,1,'TLS failures never reach camera');
  }finally{await f.close();}
});

test('persisted pin remains stable, while future, expired and changed-address certificates are replaced',async()=>{
  const directory=await mkdtemp(path.join(tmpdir(),'mobile-camera-test-'));
  try{
    const first=await pinnedCertificate(directory,['127.0.0.1']);
    assert.equal((await pinnedCertificate(directory,['127.0.0.1'])).fingerprint,first.fingerprint);
    const changed=await pinnedCertificate(directory,['127.0.0.1','192.168.1.25']);
    assert.notEqual(changed.fingerprint,first.fingerprint);
    assert.equal(new X509Certificate(changed.cert).checkIP('192.168.1.25'),'192.168.1.25');
    for(const days of [10,-400]){
      const invalid=generatePinnedCertificate(['127.0.0.1'],new Date(Date.now()+days*86400000));
      await writeFile(path.join(directory,'cert.pem'),invalid.cert);
      await writeFile(path.join(directory,'key.pem'),invalid.key,{mode:0o600});
      const restored=await pinnedCertificate(directory,['127.0.0.1']);
      assert.notEqual(restored.fingerprint,new X509Certificate(invalid.cert).fingerprint256);
      assert.ok(Date.parse(new X509Certificate(restored.cert).validFrom)<=Date.now());
      assert.ok(Date.parse(new X509Certificate(restored.cert).validTo)>Date.now());
    }
    assert.throws(()=>generatePinnedCertificate(['8.8.8.8']));
    assert.throws(()=>generatePinnedCertificate(['192.168.999.1']));
  }finally{await removeFixture(directory);}
});

test('active Admin is verified through fixed Auth/Firestore URLs before proxying fixed laptop camera configuration',async()=>{
  const f=await bridgeFixture();
  try{
    const health=await f.request({route:'/api/camera/health'});
    assert.equal(health.status,200);
    assert.deepEqual(JSON.parse(health.body.toString()),{cameraReady:true,wifiConnected:true,sensor:'OV5640',rotation:0});
    const frame=await f.request();
    assert.equal(frame.status,200);assert.deepEqual(frame.body,jpeg);
    assert.equal(frame.headers['content-type'],'image/jpeg');
    assert.match(frame.headers['cache-control'],/no-store/);
    assert.equal(frame.headers['access-control-allow-origin'],undefined);
    assert.equal(f.auth.calls.length,2,'same token may use bounded verified cache');
    assert.deepEqual(f.configReads,['fixed-laptop-config.json','fixed-laptop-config.json']);
    assert.deepEqual(f.cameraCalls.map(row=>row.url),['http://192.168.0.110/health','http://192.168.0.110/capture']);
    for(const {request}of f.cameraCalls){assert.equal(request.headers['X-Camera-Token'],'camera-secret-on-laptop');assert.equal(request.headers.Authorization,undefined);assert.equal(request.redirect,'error');}
    assert.doesNotMatch(health.body.toString(),/secret|token|fixture-admin/);
  }finally{await f.close();}
});

test('hostname, Origin, fetch metadata, method, endpoint and query violations are denied before Auth or upstream reads',async()=>{
  const f=await bridgeFixture();
  try{
    const invalid=[
      {headers:{Host:'foreign.example'}},{headers:{Host:`127.0.0.2:${f.port}`}},{headers:{Origin:'https://foreign.example'}},
      {headers:{Origin:`https://127.0.0.1:${f.port}`}},{headers:{'Sec-Fetch-Site':'same-origin'}},{headers:{'X-Camera-Preview':''}},
      {method:'POST'},{method:'OPTIONS'},{route:'/index.html'},{route:'/api/camera/anything'},
      {route:'/api/camera/frame?url=http://foreign.example/capture'},{route:'/api/camera/health?config=other.json'},
    ];
    for(const request of invalid)assert.equal((await f.request(request)).status,403,JSON.stringify(request));
    assert.equal(f.auth.calls.length,0);assert.equal(f.cameraCalls.length,0);assert.equal(f.configReads.length,0);
    assert.equal(nativePreviewRequest({socket:{encrypted:false,localPort:f.port},headers:{host:`127.0.0.1:${f.port}`,'x-camera-preview':'1'}},['127.0.0.1']),false);
    await assert.rejects(new Promise((resolve,reject)=>{const req=http.get(`http://127.0.0.1:${f.port}/api/camera/frame`,response=>{response.resume();resolve(response.statusCode);});req.setTimeout(1000,()=>req.destroy(new Error('plain HTTP refused')));req.on('error',reject);}),/socket|reset|refused/i);
    assert.equal(f.cameraCalls.length,0);
  }finally{await f.close();}
});

test('missing and malformed authorization never contacts Auth and inactive or employee profiles never reach camera',async()=>{
  const f=await bridgeFixture();
  try{
    for(const Authorization of ['', 'Basic abc', 'Bearer short', `Bearer ${token} extra`, `Bearer ${'a'.repeat(8193)}`])
      assert.equal((await f.request({headers:{Authorization}})).status,401);
    assert.equal(f.auth.calls.length,0);
    for(const profile of [
      {fields:{role:{stringValue:'EMPLOYEE'},active:{booleanValue:true}}},
      {fields:{role:{stringValue:'ADMIN'},active:{booleanValue:false}}},
      {fields:{role:{stringValue:'ADMIN'},active:{booleanValue:'true'}}},
      {},
    ]){f.auth.state.profile=profile;assert.equal((await f.request()).status,403);}
    assert.equal(f.cameraCalls.length,0);assert.equal(f.configReads.length,0);
  }finally{await f.close();}
});

test('disabled, anonymous and malformed Auth lookup accounts fail closed before profile lookup',async()=>{
  for(const account of [
    {...adminAccount(),disabled:true},{localId:'admin-uid',providerUserInfo:[]},
    {localId:'admin-uid',providerUserInfo:[{providerId:'anonymous'}]},
    {localId:'admin-uid',providerUserInfo:'password'},
    {localId:'admin-uid',providerUserInfo:[null]},
    {localId:'admin-uid',providerUserInfo:[{}]},
    {localId:42,providerUserInfo:[{providerId:'password'}]},
    {localId:'../admin',providerUserInfo:[{providerId:'password'}]},
    null,
  ]){
    const mock=authMock();mock.state.account=account;
    const verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl});
    await assert.rejects(verify(authorization),/ADMIN_REQUIRED/);
    assert.equal(mock.calls.length,1,JSON.stringify(account));
  }
});

test('validated lookup cannot authorize anonymous, malformed, foreign-project or expired token claims',async()=>{
  const valid=claims();
  const invalid=[
    jwt({...valid,firebase:{sign_in_provider:'anonymous'}}),
    jwt({...valid,firebase:null}),jwt({...valid,firebase:{}}),
    jwt({...valid,aud:'other-project'}),jwt({...valid,iss:'https://securetoken.google.com/other-project'}),
    jwt({...valid,sub:'different-uid'}),jwt({...valid,exp:Math.floor(Date.now()/1000)-1}),
    jwt({...valid,exp:'9999999999'}),jwt({...valid,exp:null}),
    jwt(null),jwt([]),jwt('not-an-object'),
    [Buffer.from('{"alg":"RS256"}').toString('base64url'),Buffer.from('not JSON').toString('base64url'),'fixture_signature'].join('.'),
    'a'.repeat(40),
  ];
  for(const badToken of invalid){
    const mock=authMock(badToken),verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl});
    await assert.rejects(verify(`Bearer ${badToken}`),/AUTH_REQUIRED|ADMIN_REQUIRED/);
    assert.equal(mock.calls.length,1,'lookup validates signature before claims; invalid claims never query profile');
  }
});

test('verification failures, malformed JSON and oversized responses do not produce a verified cache entry',async()=>{
  const failures=[{authStatus:401},{authStatus:503},{profileStatus:404},{profileStatus:503},
    {error:'network unreachable'},{authBody:'not JSON'},{profileBody:'not JSON'},
    {authBody:'x'.repeat(65537)},{profileBody:'x'.repeat(65537)}];
  for(const failed of failures){
    const mock=authMock();Object.assign(mock.state,failed);
    const verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl});
    await assert.rejects(verify(authorization),/AUTH_REQUIRED|ADMIN_REQUIRED|AUTH_UNAVAILABLE/);
    const calls=mock.calls.length;
    await assert.rejects(verify(authorization),/AUTH_REQUIRED|ADMIN_REQUIRED|AUTH_UNAVAILABLE/);
    assert.ok(mock.calls.length>calls,'failed verification must be checked again');
    Object.assign(mock.state,{account:adminAccount(),profile:adminProfile(),authStatus:200,profileStatus:200,error:null,authBody:null,profileBody:null});
    assert.equal(await verify(authorization),true,'transient failure does not permanently poison a token');
  }
});

test('verified cache expires at TTL and an unavailable or revoked verifier never extends old authorization',async()=>{
  const mock=authMock();let now=1000;
  const verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl,now:()=>now,ttlMs:100});
  assert.equal(await verify(authorization),true);assert.equal(mock.calls.length,2);
  now=1099;assert.equal(await verify(authorization),true);assert.equal(mock.calls.length,2);
  now=1100;mock.state.authStatus=503;
  await assert.rejects(verify(authorization),/AUTH_UNAVAILABLE/);assert.equal(mock.calls.length,3);
  now=1101;await assert.rejects(verify(authorization),/AUTH_UNAVAILABLE/);assert.equal(mock.calls.length,4);
  mock.state.authStatus=200;mock.state.profile.fields.active.booleanValue=false;
  await assert.rejects(verify(authorization),/ADMIN_REQUIRED/);
  mock.state.profile=adminProfile();assert.equal(await verify(authorization),true);
});

test('positive cache never outlives the validated Firebase token expiry',async()=>{
  const epoch=Date.parse('2026-10-12T10:00:00Z');let now=epoch;
  const shortToken=jwt({...claims(),exp:Math.floor(epoch/1000)+2});
  const mock=authMock(shortToken),verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl,now:()=>now,ttlMs:15000});
  assert.equal(await verify(`Bearer ${shortToken}`),true);assert.equal(mock.calls.length,2);
  now=epoch+1999;assert.equal(await verify(`Bearer ${shortToken}`),true);assert.equal(mock.calls.length,2);
  now=epoch+2000;
  await assert.rejects(verify(`Bearer ${shortToken}`),/AUTH_REQUIRED|ADMIN_REQUIRED/);
  assert.equal(mock.calls.length,3,'expired cached tokens undergo lookup but must never reach the profile/proxy');
});

test('concurrent verification shares one lookup and token/config rejection does not authorize',async()=>{
  const mock=authMock(),verify=createAdminVerifier(firebaseConfig,{fetchImpl:mock.fetchImpl});
  assert.deepEqual(await Promise.all([verify(authorization),verify(authorization),verify(authorization)]),[true,true,true]);
  assert.equal(mock.calls.length,2);
  await assert.rejects(createAdminVerifier({}, {fetchImpl:mock.fetchImpl})(authorization),/AUTH_CONFIG/);
  for(const header of [undefined, null, authorization+'\n', ['Bearer token']])
    await assert.rejects(verify(header),/AUTH_REQUIRED/);
  assert.equal(mock.calls.length,2);
});

test('TLS bridge returns a bounded unavailable error when Admin verification is transiently failing',async()=>{
  const f=await bridgeFixture();
  try{
    f.auth.state.error='sensitive-network-detail';
    for(let attempt=0;attempt<2;attempt++){
      const response=await f.request();assert.equal(response.status,503);
      assert.doesNotMatch(response.body.toString(),/sensitive-network-detail|fixture-admin|camera-secret/);
    }
    assert.equal(f.cameraCalls.length,0);
    f.auth.state.error=null;
    assert.equal((await f.request()).status,200);
    assert.equal(f.cameraCalls.length,1);
  }finally{await f.close();}
});
