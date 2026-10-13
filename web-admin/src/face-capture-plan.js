import {e} from './ui.js';

export const SIX_POSE_PLAN='FRONT_LEFT_RIGHT_V1';
export const SIX_POSE_SAMPLE_COUNT=6;
export function faceSampleCount(command){return command?.action==='DELETE'?0:command?.sampleCount===6?6:10;}
export function validManualCapturePlan(command){
  return command?.action==='ENROLL'&&command.captureMode==='MANUAL'&&(
    command.sampleCount===6&&command.capturePlan===SIX_POSE_PLAN||command.sampleCount===10&&command.capturePlan===undefined);
}
export function hasSixPosePlan(command){return validManualCapturePlan(command)&&command.sampleCount===6;}

const STEPS=[
  {key:'FRONT',label:'Nhìn thẳng',instruction:'Nhìn thẳng vào camera, giữ đầu cân bằng và đưa đủ cả cằm vào hình.'},
  {key:'LEFT',label:'Quay sang trái',instruction:'Quay nhẹ đầu sang phía tay trái của bạn. Giữ toàn bộ mặt trong hình và vẫn để camera thấy cả hai mắt.'},
  {key:'RIGHT',label:'Quay sang phải',instruction:'Quay nhẹ đầu sang phía tay phải của bạn. Giữ toàn bộ mặt trong hình và vẫn để camera thấy cả hai mắt.'}
];
export function capturePoseStep(samplesCaptured){
  const saved=Number.isSafeInteger(samplesCaptured)?Math.max(0,Math.min(6,samplesCaptured)):0;
  if(saved===6)return {complete:true,index:2,number:3,captured:2,next:2,...STEPS[2]};
  const index=Math.floor(saved/2),captured=saved%2,near=captured===1;
  const distanceInstruction=near?'Tiến lại gần vừa đủ để mặt nằm trong khung oval lớn.':'Đứng xa hơn một chút để lấy toàn bộ mặt trong khung oval nhỏ.';
  return {complete:false,index,number:index+1,captured,next:captured+1,...STEPS[index],distance:near?'NEAR':'FAR',instruction:`${STEPS[index].instruction} ${distanceInstruction}`};
}
export function renderCapturePoseGuide(command){
  if(!hasSixPosePlan(command))return '';
  const saved=Math.max(0,Math.min(6,Number(command.samplesCaptured)||0)),step=capturePoseStep(saved);
  return `<section class="capture-pose-guide" aria-label="Hướng dẫn chụp sáu ảnh"><ol class="capture-pose-steps">${STEPS.map((item,index)=>{
    const count=Math.max(0,Math.min(2,saved-index*2)),current=!step.complete&&step.index===index;
    return `<li class="capture-pose-step${current?' current':''}${count===2?' complete':''}"${current?' aria-current="step"':''}><span>${index+1}. ${e(item.label)}</span><strong>${count}/2 ảnh đạt</strong></li>`;
  }).join('')}</ol><div class="notice capture-pose-instruction" role="status" aria-live="polite"><strong>${step.complete?'Đã đủ 6 ảnh':`Bước ${step.number}/3 · ${e(step.label)} · Ảnh ${step.next}/2`}</strong><p>${step.complete?'Đang lưu mẫu khuôn mặt của nhân viên.':e(step.instruction)}</p></div><p class="hint">Trái/phải là phía của nhân viên. Quay nhẹ, giữ yên khi bấm chụp; Admin kiểm tra đúng hướng trên hình trực tiếp.</p></section>`;
}
