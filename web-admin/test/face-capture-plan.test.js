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

test('six-picture guidance distinguishes saving, unconfirmed completion and a confirmed saved profile',()=>{
  const finalizing=renderCapturePoseGuide(guided({status:'FINALIZING',samplesCaptured:6}));
  assert.match(finalizing,/Laptop đang lưu mẫu/);assert.doesNotMatch(finalizing,/Đã lưu mẫu khuôn mặt|aria-current|giữ yên khi bấm chụp/);
  const completed=guided({status:'COMPLETED',samplesCaptured:6});
  const waiting=renderCapturePoseGuide(completed);
  assert.match(waiting,/đang chờ xác nhận hồ sơ/);assert.doesNotMatch(waiting,/Đã lưu mẫu khuôn mặt|[Đđ]ang lưu mẫu|aria-current/);
  const saved=renderCapturePoseGuide(completed,{completionConfirmed:true});
  assert.match(saved,/Đã lưu mẫu khuôn mặt/);assert.match(saved,/có thể chấm công bằng khuôn mặt/);
  assert.doesNotMatch(saved,/đang chờ|[Đđ]ang lưu|bấm chụp|aria-current/);
  assert.equal((saved.match(/2\/2 ảnh đạt/g)||[]).length,3);
  assert.doesNotMatch(renderCapturePoseGuide(guided({status:'FINALIZING',samplesCaptured:6}),{completionConfirmed:true}),/Đã lưu mẫu khuôn mặt/,'a profile alone cannot complete a still-finalizing command');
});

test('cancelled, failed and cancel-pending guidance keeps collected counts without prompting another picture',()=>{
  for(const [extra,message]of [[{status:'CANCELLED'},/Đã hủy đăng ký/],[{status:'FAILED'},/Đăng ký chưa hoàn tất/],[{status:'CAPTURING',cancelRequested:true},/Đang hủy đăng ký/]]){
    const html=renderCapturePoseGuide(guided({samplesCaptured:3,...extra}));
    assert.match(html,message);assert.match(html,/1\/2 ảnh đạt/);
    assert.doesNotMatch(html,/Bước 2\/3|Ảnh 2\/2|aria-current|giữ yên khi bấm chụp|Đã lưu mẫu/);
  }
});
