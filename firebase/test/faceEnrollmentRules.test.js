// Real rules evaluation on a loopback emulator; never use production credentials.
const test = require('node:test');
const assert = require('node:assert/strict');
const project = 'demo-face-enrollment-rules';
const host = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
if (!/^(localhost|127\.0\.0\.1|\[::1\]):\d+$/.test(host)) throw new Error('Local emulator required');
const base = 'http://' + host + '/v1/projects/' + project + '/databases/(default)/documents';
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
function token(uid, provider = 'password') {
  const now = Math.floor(Date.now() / 1000);
  return encode({alg:'none',typ:'JWT'}) + '.' + encode({
    iss:'https://securetoken.google.com/' + project,aud:project,sub:uid,user_id:uid,
    iat:now,exp:now+3600,auth_time:now,
    firebase:{sign_in_provider:provider,identities:{}}
  }) + '.';
}
const admin = token('enrollment-admin');
const employee = token('enrollment-employee');
const device = token('enrollment-device','anonymous');
const retiredAdmin = token('retired-admin');
const otherAdmin = token('other-enrollment-admin');
let sequence = 0;
// Keep repeated focused/full-suite runs isolated on the same emulator instance.
const runId = require('node:crypto').randomUUID();
const freshId = () => 'request_' + runId + '_' + (++sequence);
function field(value) {
  if (value === null) return {nullValue:null};
  if (value instanceof Date) return {timestampValue:value.toISOString()};
  if (typeof value === 'boolean') return {booleanValue:value};
  if (typeof value === 'number') return Number.isInteger(value)
    ? {integerValue:String(value)} : {doubleValue:value};
  return {stringValue:value};
}
const name = path => 'projects/' + project + '/databases/(default)/documents/' + path;
function write(path, data, {merge=false,stamps=[]}={}) {
  const entry = {update:{name:name(path),fields:Object.fromEntries(
    Object.entries(data).filter(([key])=>!stamps.includes(key)).map(([key,value])=>[key,field(value)])
  )}};
  if (merge) entry.updateMask = {fieldPaths:Object.keys(data).filter(key=>!stamps.includes(key))};
  if (stamps.length) entry.updateTransforms = stamps.map(fieldPath=>({
    fieldPath,setToServerValue:'REQUEST_TIME'
  }));
  return entry;
}
async function commit(auth,writes) {
  return fetch(base + ':commit',{
    method:'POST',headers:{Authorization:'Bearer ' + auth,'Content-Type':'application/json'},
    body:JSON.stringify({writes})
  });
}
async function expectStatus(response,status) {
  assert.equal(response.status,status,await response.text());
}
async function seed(path,data) { await expectStatus(await commit('owner',[write(path,data)]),200); }
async function patch(path,data,auth='owner',stamps=[]) {
  return commit(auth,[write(path,data,{merge:true,stamps})]);
}
async function read(path,auth) {
  return fetch(base + '/' + path,{headers:auth?{Authorization:'Bearer ' + auth}:{}});
}
async function setup({cameraReady=true,mode='firebase',lastSeenAt=new Date(),active=true}={}) {
  await seed('users/enrollment-admin',{role:'ADMIN',active:true});
  await seed('users/enrollment-employee',{role:'EMPLOYEE',active:true,employeeId:'e1'});
  await seed('users/retired-admin',{role:'ADMIN',active:false});
  await seed('users/other-enrollment-admin',{role:'ADMIN',active:true});
  await seed('employees/e1',{fullName:'An',active});
  await seed('employees/e2',{fullName:'Binh',active:true});
  for (const stationId of ['GATE-01','GATE-02']) {
    await seed('faceStations/' + stationId,{
      stationId,faceDeviceId:stationId==='GATE-01'?'CAM-01':'CAM-02',
      sessionId:'session_1',mode,lastSeenAt,cameraReady,pendingCommandId:null
    });
  }
  for (const employeeId of ['e1','e2']) {
    await seed('faceEnrollmentLocks/' + employeeId,{
      employeeId,commandId:null,stationId:'GATE-01',updatedAt:new Date()
    });
  }
}
function requestData(requestId,overrides={}) {
  return {requestId,employeeId:'e1',stationId:'GATE-01',deviceId:'CAM-01',
    sessionId:'session_1',action:'ENROLL',status:'PENDING',
    requestedBy:'enrollment-admin',sampleCount:10,samplesCaptured:0,
    cancelRequested:false,message:'',...overrides};
}
function requestWrites(data,{station=true,employeeLock=true}={}) {
  const writes = [write('faceEnrollmentCommands/' + data.requestId,data,{stamps:['requestedAt']})];
  if (station) writes.push(write('faceStations/' + data.stationId,{
    pendingCommandId:data.requestId
  },{merge:true,stamps:['updatedAt']}));
  if (employeeLock) writes.push(write('faceEnrollmentLocks/' + data.employeeId,{
    employeeId:data.employeeId,commandId:data.requestId,stationId:data.stationId
  },{stamps:['updatedAt']}));
  return writes;
}
function manualRequest(overrides={}) {
  return requestData(freshId(),{
    captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,...overrides
  });
}
async function capturingManual(overrides={}) {
  await setup();
  const data = manualRequest();
  await expectStatus(await commit(admin,requestWrites(data)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    status:'CAPTURING',...overrides
  }),200);
  return data;
}
async function capturingPlannedManual() {
  await setup();
  const data = manualRequest({sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1'});
  await expectStatus(await commit(admin,requestWrites(data)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{status:'CAPTURING'}),200);
  return data;
}
async function click(data,{auth=admin,seq=1,extra={},stamps=['captureRequestedAt']}={}) {
  return patch('faceEnrollmentCommands/' + data.requestId,{
    captureRequestSeq:seq,...extra
  },auth,stamps);
}

test('admin requests enrollment atomically with both reservations; DELETE uses zero samples',async()=>{
  await setup();
  const first = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(first)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + first.requestId,{status:'COMPLETED'}),200);
  await expectStatus(await commit(admin,requestWrites(requestData(freshId(),{action:'DELETE',sampleCount:0}))),200);
});

test('employee, anonymous, inactive admin and unauthenticated callers cannot start enrollment',async()=>{
  await setup();
  for (const [auth,requestedBy] of [[employee,'enrollment-employee'],[device,'enrollment-device'],[retiredAdmin,'retired-admin']]) {
    await expectStatus(await commit(auth,requestWrites(requestData(freshId(),{requestedBy}))),403);
  }
  const response = await fetch(base + ':commit',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({writes:requestWrites(requestData(freshId()))})});
  await expectStatus(response,403);
});

test('missing either atomic reservation cannot create a command',async()=>{
  await setup();
  for (const options of [{station:false},{employeeLock:false},{station:false,employeeLock:false}]) {
    await expectStatus(await commit(admin,requestWrites(requestData(freshId()),options)),403);
  }
});

test('offline, demo, stale and future heartbeat stations cannot accept requests',async()=>{
  for (const options of [{cameraReady:false},{mode:'demo'},
    {lastSeenAt:new Date(Date.now()-120000)},{lastSeenAt:new Date(Date.now()+120000)}]) {
    await setup(options);
    await expectStatus(await commit(admin,requestWrites(requestData(freshId()))),403);
  }
});

test('camera/session mismatch, inactive employee and invalid initial success fields are rejected',async()=>{
  await setup();
  for (const changes of [{deviceId:'CAM-99'},{sessionId:'old_session'},{employeeId:'missing'},
    {status:'COMPLETED'},{samplesCaptured:10},{sampleCount:11},{action:'UNKNOWN'},
    {requestedBy:'another-admin'},{cancelRequested:true},{message:'already enrolled'},
    {centroid:'secret vector'}]) {
    await expectStatus(await commit(admin,requestWrites(requestData(freshId(),changes))),403);
  }
  await setup({active:false});
  await expectStatus(await commit(admin,requestWrites(requestData(freshId()))),403);
});

test('active station reservation prevents a second employee from capturing concurrently',async()=>{
  await setup();
  const first = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(first)),200);
  await expectStatus(await commit(admin,requestWrites(requestData(freshId(),{employeeId:'e2'}))),403);
  await expectStatus(await patch('faceEnrollmentCommands/' + first.requestId,{status:'FINALIZING'}),200);
  await expectStatus(await commit(admin,requestWrites(requestData(freshId(),{employeeId:'e2'}))),403);
});

test('employee reservation prevents concurrent enrollment at a different station',async()=>{
  await setup();
  await expectStatus(await commit(admin,requestWrites(requestData(freshId()))),200);
  await expectStatus(await commit(admin,requestWrites(requestData(freshId(),{
    stationId:'GATE-02',deviceId:'CAM-02'
  }))),403);
});

test('client cannot forge progress, completion, profile version or heartbeat metadata',async()=>{
  await setup();
  const data = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(data)),200);
  for (const changes of [{status:'CAPTURING'},{status:'COMPLETED'},{samplesCaptured:1},
    {profileVersion:1},{requestedBy:'other-admin'},{message:'saved'}]) {
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,changes,admin),403);
  }
  for (const changes of [{cameraReady:true},{lastSeenAt:new Date()},{faceDeviceId:'CAM-99'},
    {sessionId:'replacement'},{pendingCommandId:null}]) {
    await expectStatus(await patch('faceStations/GATE-01',changes,admin),403);
  }
  await expectStatus(await commit(admin,[write('faceStations/forged',{stationId:'forged'})]),403);
});

test('admin cancellation is only a flag on pending/capturing requests, never fake terminal success',async()=>{
  await setup();
  const pending = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(pending)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + pending.requestId,{
    cancelRequested:true
  },employee,['cancelRequestedAt']),403);
  await expectStatus(await patch('faceEnrollmentCommands/' + pending.requestId,{
    cancelRequested:true,status:'CANCELLED'
  },admin,['cancelRequestedAt']),403);
  await expectStatus(await patch('faceEnrollmentCommands/' + pending.requestId,{
    cancelRequested:true
  },admin,['cancelRequestedAt']),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + pending.requestId,{
    cancelRequested:false
  },admin),403);
  await expectStatus(await patch('faceEnrollmentCommands/' + pending.requestId,{status:'CANCELLED'}),200);
  const capturing = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(capturing)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + capturing.requestId,{status:'CAPTURING'}),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + capturing.requestId,{
    cancelRequested:true
  },admin,['cancelRequestedAt']),200);
});

test('finalizing and completed requests cannot be cancelled or modified by clients',async()=>{
  await setup();
  const data = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(data)),200);
  for (const status of ['FINALIZING','COMPLETED']) {
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{status}),200);
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
      cancelRequested:true
    },admin,['cancelRequestedAt']),403);
  }
});

test('command, station and lock metadata are readable only to active admins',async()=>{
  await setup();
  const data = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(data)),200);
  for (const path of ['faceEnrollmentCommands/' + data.requestId,'faceStations/GATE-01','faceEnrollmentLocks/e1']) {
    await expectStatus(await read(path,admin),200);
    for (const auth of [employee,device,retiredAdmin,null]) await expectStatus(await read(path,auth),403);
  }
});

test('manual enrollment creation accepts only the full zero-counter contract; legacy Android still works',async()=>{
  await setup();
  const legacy = requestData(freshId());
  // requestedAt is the thirteenth field, supplied by the server transform.
  assert.equal(Object.keys(legacy).length + 1,13);
  await expectStatus(await commit(admin,requestWrites(legacy)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + legacy.requestId,{status:'COMPLETED'}),200);
  const manual = manualRequest();
  await expectStatus(await commit(admin,requestWrites(manual)),200);
  const stored = await read('faceEnrollmentCommands/' + manual.requestId,admin);
  assert.equal(stored.status,200);
  const fields = (await stored.json()).fields;
  assert.equal(fields.captureMode.stringValue,'MANUAL');
  assert.equal(fields.captureRequestSeq.integerValue,'0');
  assert.equal(fields.captureConsumedSeq.integerValue,'0');
});

test('manual creation rejects partial fields, explicit AUTO, noninteger counters and manual DELETE',async()=>{
  await setup();
  for (const fields of [
    {captureMode:'MANUAL'}, {captureRequestSeq:0}, {captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:0},
    {captureMode:'MANUAL',captureConsumedSeq:0},
    {captureRequestSeq:0,captureConsumedSeq:0},
    {captureMode:'AUTO',captureRequestSeq:0,captureConsumedSeq:0},
    {captureMode:true,captureRequestSeq:0,captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:false,captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:false},
    {captureMode:'MANUAL',captureRequestSeq:'0',captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:'0'},
    {captureMode:'MANUAL',captureRequestSeq:0.5,captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0.5},
    {captureMode:'MANUAL',captureRequestSeq:1,captureConsumedSeq:0},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:1},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,action:'DELETE',sampleCount:0},
    {captureMode:'MANUAL',captureRequestSeq:0,captureConsumedSeq:0,captureRequestedAt:new Date()}
  ]) {
    await expectStatus(await commit(admin,requestWrites(requestData(freshId(),fields))),403);
  }
});

test('guided enrollment accepts exactly six manual samples with the front-left-right plan',async()=>{
  const data = await capturingPlannedManual();
  const stored = await read('faceEnrollmentCommands/' + data.requestId,admin);
  assert.equal(stored.status,200);
  const fields = (await stored.json()).fields;
  assert.equal(fields.sampleCount.integerValue,'6');
  assert.equal(fields.capturePlan.stringValue,'FRONT_LEFT_RIGHT_V1');
  assert.equal(fields.captureMode.stringValue,'MANUAL');
});

test('guided creation denies absent or invalid plans, wrong totals and automatic or DELETE commands',async()=>{
  await setup();
  for (const fields of [
    {sampleCount:6},
    {sampleCount:6,capturePlan:null},
    {sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V2'},
    {sampleCount:6,capturePlan:true},
    {sampleCount:6,capturePlan:6},
    {sampleCount:2,capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:10,capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:10,capturePlan:null},
    {sampleCount:6.5,capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:'6',capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1',captureMode:'AUTO'},
    {sampleCount:0,capturePlan:'FRONT_LEFT_RIGHT_V1',action:'DELETE'}
  ]) {
    await expectStatus(await commit(admin,requestWrites(manualRequest(fields))),403);
  }
  // Omitting manual capture fields must not turn the guided plan into legacy AUTO.
  for (const fields of [
    {sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:10,capturePlan:'FRONT_LEFT_RIGHT_V1'},
    {sampleCount:0,capturePlan:'FRONT_LEFT_RIGHT_V1',action:'DELETE'}
  ]) {
    await expectStatus(await commit(admin,requestWrites(requestData(freshId(),fields))),403);
  }
});

test('guided capture permits all six accepted samples and rejects a seventh request',async()=>{
  const data = await capturingPlannedManual();
  for (let index=0; index<6; index++) {
    await expectStatus(await click(data,{seq:index+1}),200);
    await expectStatus(await click(data,{seq:index+2}),403);
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
      captureConsumedSeq:index+1,samplesCaptured:index+1
    }),200);
  }
  await expectStatus(await click(data,{seq:7}),403);
  const fields = (await (await read('faceEnrollmentCommands/' + data.requestId,admin)).json()).fields;
  assert.equal(fields.samplesCaptured.integerValue,'6');
  assert.equal(fields.captureRequestSeq.integerValue,'6');
  assert.equal(fields.captureConsumedSeq.integerValue,'6');
});

test('guided clicks cannot change sample count, plan or capture mode',async()=>{
  const data = await capturingPlannedManual();
  for (const extra of [
    {capturePlan:'FRONT_LEFT_RIGHT_V2'}, {capturePlan:null},
    {sampleCount:10}, {sampleCount:2}, {captureMode:'AUTO'},
    {sampleCount:10,capturePlan:null}, {samplesCaptured:6}
  ]) {
    await expectStatus(await click(data,{extra}),403);
  }
  await expectStatus(await click(data),200);
  const legacy = await capturingManual();
  await expectStatus(await click(legacy,{extra:{sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1'}}),403);
  await expectStatus(await click(legacy,{extra:{capturePlan:'FRONT_LEFT_RIGHT_V1'}}),403);
  await expectStatus(await click(legacy),200);
});

test('guided clicks revalidate the exact stored plan and total before every capture',async()=>{
  const data = await capturingPlannedManual();
  // Removing the plan is distinct from replacing it with null.
  await expectStatus(await commit('owner',[{
    update:{name:name('faceEnrollmentCommands/' + data.requestId),fields:{}},
    updateMask:{fieldPaths:['capturePlan']}
  }]),200);
  await expectStatus(await click(data),403);
  for (const changes of [
    {capturePlan:null}, {capturePlan:'FRONT_LEFT_RIGHT_V2'}, {capturePlan:true},
    {sampleCount:10}, {sampleCount:2}, {sampleCount:'6'},
    {captureMode:'AUTO'}, {samplesCaptured:6}, {samplesCaptured:7},
    {samplesCaptured:-1}, {samplesCaptured:0.5},
    {captureRequestSeq:100,captureConsumedSeq:100}
  ]) {
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
      sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1',captureMode:'MANUAL',
      samplesCaptured:0,captureRequestSeq:0,captureConsumedSeq:0,...changes
    }),200);
    await expectStatus(await click(data),403);
  }
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    sampleCount:6,capturePlan:'FRONT_LEFT_RIGHT_V1',captureMode:'MANUAL',
    samplesCaptured:5,captureRequestSeq:99,captureConsumedSeq:99
  }),200);
  await expectStatus(await click(data,{seq:100}),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{captureConsumedSeq:100}),200);
  await expectStatus(await click(data,{seq:101}),403);
});

test('guided capture preserves requester, station, employee lock and cancellation checks',async()=>{
  const data = await capturingPlannedManual();
  for (const auth of [employee,device,retiredAdmin,otherAdmin]) {
    await expectStatus(await click(data,{auth}),403);
  }
  await expectStatus(await patch('faceStations/GATE-01',{sessionId:'replacement'}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('faceStations/GATE-01',{sessionId:'session_1'}),200);
  await expectStatus(await patch('faceEnrollmentLocks/e1',{commandId:'another_request'}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('faceEnrollmentLocks/e1',{commandId:data.requestId}),200);
  await expectStatus(await patch('employees/e1',{active:false}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('employees/e1',{active:true}),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    cancelRequested:true
  },otherAdmin,['cancelRequestedAt']),200);
  await expectStatus(await click(data),403);
});

test('one manual click is allowed only after the preceding sequence was consumed by the laptop',async()=>{
  const data = await capturingManual();
  await expectStatus(await click(data),200);
  let fields = (await (await read('faceEnrollmentCommands/' + data.requestId,admin)).json()).fields;
  assert.equal(fields.captureRequestSeq.integerValue,'1');
  assert.equal(fields.captureConsumedSeq.integerValue,'0');
  assert.equal(fields.samplesCaptured.integerValue,'0');
  assert.equal(fields.status.stringValue,'CAPTURING');
  assert.ok(fields.captureRequestedAt.timestampValue);
  await expectStatus(await click(data,{seq:1}),403);
  await expectStatus(await click(data,{seq:2}),403);
  // Only the trusted service (emulator owner) acknowledges a rejected/accepted frame.
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{captureConsumedSeq:1}),200);
  await expectStatus(await click(data,{seq:2}),200);
  fields = (await (await read('faceEnrollmentCommands/' + data.requestId,admin)).json()).fields;
  assert.equal(fields.captureRequestSeq.integerValue,'2');
  assert.equal(fields.captureConsumedSeq.integerValue,'1');
  assert.equal(fields.samplesCaptured.integerValue,'0');
});

test('capture clicks require the active admin who originally requested enrollment',async()=>{
  const data = await capturingManual();
  for (const auth of [employee,device,retiredAdmin,otherAdmin]) {
    await expectStatus(await click(data,{auth}),403);
  }
  const response = await fetch(base + ':commit',{
    method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({writes:[write('faceEnrollmentCommands/' + data.requestId,{
      captureRequestSeq:1
    },{merge:true,stamps:['captureRequestedAt']})]})
  });
  await expectStatus(response,403);
  await expectStatus(await click(data),200);
});

test('clients cannot forge click acknowledgement, progress, status, image data or capture timestamps',async()=>{
  const data = await capturingManual();
  for (const seq of [0,2,-1,100,false,'1',1.5]) {
    await expectStatus(await click(data,{seq}),403);
  }
  await expectStatus(await click(data,{stamps:[]}),403);
  await expectStatus(await click(data,{stamps:[],extra:{captureRequestedAt:new Date(Date.now()-60000)}}),403);
  for (const extra of [
    {captureConsumedSeq:1}, {samplesCaptured:1}, {status:'COMPLETED'},
    {captureMode:'AUTO'}, {sampleCount:1}, {cancelRequested:true},
    {requestedBy:'other-enrollment-admin'}, {requestedAt:new Date()},
    {message:'captured'}, {profileVersion:1}, {photo:'image bytes'},
    {centroid:'face vector'}, {updatedAt:new Date()}
  ]) {
    await expectStatus(await click(data,{extra}),403);
  }
  await expectStatus(await click(data),200);
});

test('the requesting admin must remain active and retain the ADMIN role at every click',async()=>{
  const data = await capturingManual();
  await expectStatus(await patch('users/enrollment-admin',{active:false}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('users/enrollment-admin',{active:true,role:'EMPLOYEE',employeeId:'e1'}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('users/enrollment-admin',{role:'ADMIN'}),200);
  await expectStatus(await click(data),200);
});

test('manual capture validates integer counters, sample progress and the bounded request sequence',async()=>{
  const data = await capturingManual();
  for (const overrides of [
    {captureRequestSeq:-1,captureConsumedSeq:-1},
    {captureRequestSeq:101,captureConsumedSeq:101},
    {captureRequestSeq:false,captureConsumedSeq:false},
    {captureRequestSeq:0.5,captureConsumedSeq:0.5},
    {captureRequestSeq:'0',captureConsumedSeq:'0'},
    {captureRequestSeq:0,captureConsumedSeq:false},
    {captureRequestSeq:0,captureConsumedSeq:'0'},
    {captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:-1},
    {captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:10},
    {captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:false},
    {captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:'0'},
    {captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:0.5}
  ]) {
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
      captureRequestSeq:0,captureConsumedSeq:0,samplesCaptured:0,...overrides
    }),200);
    await expectStatus(await click(data),403);
  }
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    captureRequestSeq:99,captureConsumedSeq:99,samplesCaptured:9
  }),200);
  await expectStatus(await click(data,{seq:100}),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{captureConsumedSeq:100}),200);
  await expectStatus(await click(data,{seq:101}),403);
});

test('capture rechecks station session, camera, heartbeat and the matching pending command',async()=>{
  for (const change of [
    {sessionId:'old_session'}, {faceDeviceId:'CAM-99'}, {pendingCommandId:null},
    {pendingCommandId:'another_request'}, {cameraReady:false}, {mode:'demo'},
    {lastSeenAt:new Date(Date.now()-120000)}, {lastSeenAt:new Date(Date.now()+120000)}
  ]) {
    const data = await capturingManual();
    await expectStatus(await patch('faceStations/GATE-01',change),200);
    await expectStatus(await click(data),403);
  }
  const data = await capturingManual();
  await expectStatus(await commit('owner',[{delete:name('faceStations/GATE-01')}]),200);
  await expectStatus(await click(data),403);
});

test('capture rechecks employee activity and the exact employee lock',async()=>{
  for (const change of [
    {commandId:null}, {commandId:'another_request'}, {employeeId:'e2'}, {stationId:'GATE-02'}
  ]) {
    const data = await capturingManual();
    await expectStatus(await patch('faceEnrollmentLocks/e1',change),200);
    await expectStatus(await click(data),403);
  }
  let data = await capturingManual();
  await expectStatus(await commit('owner',[{delete:name('faceEnrollmentLocks/e1')}]),200);
  await expectStatus(await click(data),403);
  data = await capturingManual();
  await expectStatus(await patch('employees/e1',{active:false}),200);
  await expectStatus(await click(data),403);
  await expectStatus(await commit('owner',[{delete:name('employees/e1')}]),200);
  await expectStatus(await click(data),403);
});

test('capture clicks reject pending, terminal, cancelled, DELETE and legacy automatic commands',async()=>{
  const data = await capturingManual();
  for (const status of ['PENDING','FINALIZING','COMPLETED','FAILED','CANCELLED']) {
    await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{status}),200);
    await expectStatus(await click(data),403);
  }
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    status:'CAPTURING',cancelRequested:true
  }),200);
  await expectStatus(await click(data),403);
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    cancelRequested:false,action:'DELETE'
  }),200);
  await expectStatus(await click(data),403);
  await setup();
  const legacy = requestData(freshId());
  await expectStatus(await commit(admin,requestWrites(legacy)),200);
  await expectStatus(await patch('faceEnrollmentCommands/' + legacy.requestId,{status:'CAPTURING'}),200);
  await expectStatus(await click(legacy),403);
});

test('manual capture does not widen cancellation or deletion privileges',async()=>{
  const data = await capturingManual();
  // Existing cancellation semantics still allow another active admin to stop a request.
  await expectStatus(await patch('faceEnrollmentCommands/' + data.requestId,{
    cancelRequested:true
  },otherAdmin,['cancelRequestedAt']),200);
  await expectStatus(await click(data),403);
  await expectStatus(await commit(admin,[{delete:name('faceEnrollmentCommands/' + data.requestId)}]),403);
  await expectStatus(await commit(admin,[{delete:name('faceEnrollmentLocks/e1')}]),403);
  await expectStatus(await commit(admin,[{delete:name('faceStations/GATE-01')}]),403);
});
