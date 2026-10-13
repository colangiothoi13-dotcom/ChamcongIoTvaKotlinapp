import test from 'node:test';
import assert from 'node:assert/strict';
import {SIX_POSE_PLAN} from '../src/face-capture-plan.js';
import {faceFramingGuide,updateFaceFraming} from '../src/face-framing.js';

const command=(extra={})=>({action:'ENROLL',captureMode:'MANUAL',sampleCount:6,capturePlan:SIX_POSE_PLAN,status:'CAPTURING',cancelRequested:false,samplesCaptured:0,captureRequestSeq:0,captureConsumedSeq:0,...extra});
const live=(extra={})=>({phase:'live',frameUrl:'blob:live-camera',rotation:0,sourceRotation:180,...extra});
function fixture(){
  const nodes=new Map();
  for(const name of ['overlay','caption','title','instruction']){
    const properties=new Map();
    nodes.set(`#face-frame-${name}`,{hidden:true,textContent:'',dataset:{},style:{setProperty(key,value){properties.set(key,value);},getPropertyValue:key=>properties.get(key)||''}});
  }
  return {root:{querySelector:selector=>nodes.get(selector)||null},node:name=>nodes.get(`#face-frame-${name}`)};
}

test('each angle guides one whole-face shot then one closer shot, advancing only after accepted photos',()=>{
  const expected=[['FRONT','FAR',1],['FRONT','NEAR',2],['LEFT','FAR',1],['LEFT','NEAR',2],['RIGHT','FAR',1],['RIGHT','NEAR',2]];
  for(let saved=0;saved<6;saved++){
    const input=command({samplesCaptured:saved}),before=structuredClone(input),guide=faceFramingGuide(input);
    assert.deepEqual([guide.pose,guide.distance,guide.photo],expected[saved]);
    assert.match(guide.instruction,/trán và cằm/);
    assert.match(guide.instruction,/bấm Chụp ảnh/);
    assert.deepEqual(input,before,'guidance cannot write enrollment state');
    if(saved%2){assert.ok(guide.scale>faceFramingGuide(command({samplesCaptured:saved-1})).scale);assert.match(guide.title,/Tiến lại gần/);}
    else assert.match(guide.title,/Đứng xa hơn/);
  }
});

test('rejected, duplicate and in-flight clicks keep the same distance and pose until the saved count changes',()=>{
  for(let saved=0;saved<6;saved++){
    const expected=faceFramingGuide(command({samplesCaptured:saved}));
    for(const attempt of [{captureRequestSeq:20,captureConsumedSeq:20,captureLastReason:'low_quality'},{captureRequestSeq:21,captureConsumedSeq:20},{captureRequestSeq:21,captureConsumedSeq:21,captureLastReason:'no_face'}]){
      assert.deepEqual(faceFramingGuide(command({samplesCaptured:saved,...attempt})),expected);
    }
  }
});

test('no distance prompt remains for completed, cancelled, legacy or invalid enrollment state',()=>{
  for(const extra of [{samplesCaptured:6},{samplesCaptured:-1},{samplesCaptured:1.5},{samplesCaptured:'1'},{samplesCaptured:undefined},{status:'FINALIZING'},{status:'COMPLETED'},{status:'FAILED'},{status:'CANCELLED'},{cancelRequested:true},{sampleCount:10,capturePlan:undefined},{captureMode:'AUTO'},{capturePlan:'other_plan'},{action:'DELETE'}]){
    assert.equal(faceFramingGuide(command(extra)),null,JSON.stringify(extra));
  }
  assert.equal(faceFramingGuide(command({status:'PENDING'})).distance,'FAR');
  assert.equal(faceFramingGuide(null),null);
});

test('oval and its instruction appear only over an available live frame and hide when capture stops',()=>{
  const f=fixture();
  updateFaceFraming(f.root,command(),live());
  assert.equal(f.node('overlay').hidden,false);assert.equal(f.node('caption').hidden,false);
  assert.equal(f.node('overlay').dataset.distance,'FAR');
  assert.match(f.node('title').textContent,/Nhìn thẳng.*Ảnh 1\/2/);
  for(const preview of [null,live({phase:'connecting'}),live({phase:'error'}),live({frameUrl:null}),live({phase:'idle'})]){
    updateFaceFraming(f.root,command(),preview);assert.equal(f.node('overlay').hidden,true);assert.equal(f.node('caption').hidden,true);
  }
  updateFaceFraming(f.root,command({samplesCaptured:1}),live());
  assert.equal(f.node('overlay').hidden,false);assert.equal(f.node('overlay').dataset.distance,'NEAR');
  assert.match(f.node('instruction').textContent,/khung oval lớn/);
  for(const extra of [{samplesCaptured:6},{cancelRequested:true},{status:'FINALIZING'},{sampleCount:10,capturePlan:undefined}]){
    updateFaceFraming(f.root,command(extra),live());assert.equal(f.node('overlay').hidden,true);assert.equal(f.node('caption').hidden,true);
  }
});

test('source correction and user view rotation never rotate the guidance or change its scale',()=>{
  const f=fixture();
  updateFaceFraming(f.root,command({samplesCaptured:1}),live());
  const title=f.node('title').textContent,scale=f.node('overlay').style.getPropertyValue('--face-frame-scale');
  for(const sourceRotation of [0,90,180,270])for(const rotation of [0,90,180,270]){
    updateFaceFraming(f.root,command({samplesCaptured:1}),live({sourceRotation,rotation}));
    assert.equal(f.node('overlay').style.transform,undefined);assert.equal(f.node('overlay').style.getPropertyValue('--face-frame-scale'),scale);assert.equal(f.node('title').textContent,title);assert.equal(f.node('overlay').hidden,false);
  }
});
