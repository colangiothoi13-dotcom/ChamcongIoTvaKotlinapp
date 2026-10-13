import test from 'node:test';
import assert from 'node:assert/strict';
import {SIX_POSE_PLAN,faceSampleCount,validManualCapturePlan,capturePoseStep,renderCapturePoseGuide} from '../src/face-capture-plan.js';

const guided=(extra={})=>({action:'ENROLL',captureMode:'MANUAL',sampleCount:6,capturePlan:SIX_POSE_PLAN,samplesCaptured:0,...extra});
test('six guided pictures require the exact immutable plan, while ten-picture legacy requests remain valid',()=>{
  assert.equal(validManualCapturePlan(guided()),true);assert.equal(faceSampleCount(guided()),6);
  assert.equal(validManualCapturePlan({action:'ENROLL',captureMode:'MANUAL',sampleCount:10}),true);assert.equal(faceSampleCount({sampleCount:10}),10);
  assert.equal(faceSampleCount({action:'DELETE'}),0);
  for(const extra of [{sampleCount:2},{sampleCount:6,capturePlan:undefined},{sampleCount:10},{captureMode:'AUTO'},{capturePlan:'wrong'},{sampleCount:'6'}])assert.equal(validManualCapturePlan(guided(extra)),false);
});
test('pose guidance advances after two accepted pictures and preserves the current direction on rejection',()=>{
  const expected=[['FRONT',1],['FRONT',2],['LEFT',1],['LEFT',2],['RIGHT',1],['RIGHT',2]];
  for(let saved=0;saved<6;saved++){
    const step=capturePoseStep(saved);assert.deepEqual([step.key,step.next],expected[saved]);assert.equal(step.complete,false);
    const html=renderCapturePoseGuide(guided({samplesCaptured:saved}));assert.match(html,new RegExp(`Bước ${step.number}/3`));assert.match(html,new RegExp(`Ảnh ${step.next}/2`));assert.equal((html.match(/aria-current="step"/g)||[]).length,1);
    assert.equal(renderCapturePoseGuide(guided({samplesCaptured:saved,captureLastReason:'low_quality',captureRequestSeq:77,captureConsumedSeq:77})),html,'rejected attempts never change pose');
  }
  const done=renderCapturePoseGuide(guided({samplesCaptured:6}));assert.match(done,/Đã đủ 6 ảnh/);assert.doesNotMatch(done,/aria-current/);assert.equal((done.match(/2\/2 ảnh đạt/g)||[]).length,3);
});
test('left and right instructions refer to the employee, keep both eyes visible and do not claim measured head pose',()=>{
  assert.match(capturePoseStep(2).instruction,/tay trái của bạn/);assert.match(capturePoseStep(4).instruction,/tay phải của bạn/);
  assert.match(renderCapturePoseGuide(guided()),/Trái\/phải là phía của nhân viên/);assert.match(renderCapturePoseGuide(guided()),/Admin kiểm tra đúng hướng/);
  assert.equal(renderCapturePoseGuide({action:'ENROLL',captureMode:'MANUAL',sampleCount:10,samplesCaptured:5}), '');
});
