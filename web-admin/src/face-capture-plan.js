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
  const distanceInstruction=near?'Tiến lại gần vừa đủ để mặt nằm trong khung oval lớn, vẫn giữ trán và cằm trong ảnh.':'Đưa trọn khuôn mặt, cả trán và cằm vào khung oval nhỏ. Nếu đã vừa khung thì giữ vị trí, không cần lùi thêm.';
  return {complete:false,index,number:index+1,captured,next:captured+1,...STEPS[index],distance:near?'NEAR':'FAR',instruction:`${STEPS[index].instruction} ${distanceInstruction}`};
}
export function renderCapturePoseGuide(command,{completionConfirmed=false}={}){
  if(!hasSixPosePlan(command))return '';
  const saved=Math.max(0,Math.min(6,Number(command.samplesCaptured)||0)),step=capturePoseStep(saved);
  const ended=['COMPLETED','CANCELLED','FAILED'].includes(command.status),canceling=command.cancelRequested===true&&!ended;
  const active=!ended&&!canceling&&command.status!=='FINALIZING';
  let title,instruction;
  if(command.status==='COMPLETED'){
    title=completionConfirmed?'Đã lưu mẫu khuôn mặt':'Đang xác nhận mẫu khuôn mặt';
    instruction=completionConfirmed?'Đăng ký đã hoàn tất. Nhân viên có thể chấm công bằng khuôn mặt khi dịch vụ laptop đang chạy.':'Laptop đã hoàn tất; đang chờ xác nhận hồ sơ nhân viên từ Firebase.';
  }else if(command.status==='CANCELLED'){
    title='Đã hủy đăng ký';instruction='Yêu cầu đã dừng. Các ảnh của yêu cầu này không được dùng để tạo mẫu mới.';
  }else if(command.status==='FAILED'){
    title='Đăng ký chưa hoàn tất';instruction='Mẫu mới chưa được lưu thành công. Kiểm tra lý do bên trên rồi mở yêu cầu mới để thử lại.';
  }else if(canceling){
    title='Đang hủy đăng ký';instruction='Đã gửi yêu cầu hủy; chờ laptop xác nhận. Không chụp thêm ảnh.';
  }else if(command.status==='FINALIZING'){
    title='Đã đủ 6 ảnh';instruction='Laptop đang lưu mẫu khuôn mặt. Vui lòng chờ xác nhận hoàn tất.';
  }else if(step.complete){
    title='Đã đủ 6 ảnh';instruction='Đang chờ laptop xử lý và lưu mẫu khuôn mặt.';
  }else{
    title=`Bước ${step.number}/3 · ${step.label} · Ảnh ${step.next}/2`;instruction=step.instruction;
  }
  return `<section class="capture-pose-guide" aria-label="Hướng dẫn chụp sáu ảnh"><ol class="capture-pose-steps">${STEPS.map((item,index)=>{
    const count=Math.max(0,Math.min(2,saved-index*2)),current=active&&!step.complete&&step.index===index;
    return `<li class="capture-pose-step${current?' current':''}${count===2?' complete':''}"${current?' aria-current="step"':''}><span>${index+1}. ${e(item.label)}</span><strong>${count}/2 ảnh đạt</strong></li>`;
  }).join('')}</ol><div class="notice capture-pose-instruction" role="status" aria-live="polite"><strong>${e(title)}</strong><p>${e(instruction)}</p></div>${active&&!step.complete?'<p class="hint">Trái/phải là phía của nhân viên. Quay nhẹ, giữ yên khi bấm chụp; Admin kiểm tra đúng hướng trên hình trực tiếp.</p>':''}</section>`;
}
