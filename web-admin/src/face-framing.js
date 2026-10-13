import {capturePoseStep,hasSixPosePlan} from './face-capture-plan.js';

// This overlay guides positioning on the display. It never changes or crops
// the camera JPEG, and its ring does not certify quality or liveness.
export function faceFramingGuide(command){
  if(!hasSixPosePlan(command)||!['PENDING','CAPTURING'].includes(command.status)||command.cancelRequested===true||!Number.isSafeInteger(command.samplesCaptured)||command.samplesCaptured<0||command.samplesCaptured>=6)return null;
  const pose=capturePoseStep(command.samplesCaptured),near=pose.next===2;
  return {pose:pose.key,poseLabel:pose.label,photo:pose.next,distance:near?'NEAR':'FAR',scale:near?1.35:1,
    title:`${pose.label} · Ảnh ${pose.next}/2 · ${near?'Tiến lại gần':'Giữ mặt trọn khung'}`,
    instruction:near?'Tiến lại gần vừa đủ để mặt nằm trong khung oval lớn. Giữ cả trán và cằm trong ảnh, giữ yên rồi bấm Chụp ảnh.':'Giữ cả trán và cằm trong khung oval nhỏ. Nếu đã vừa khung thì không cần lùi thêm. Giữ yên rồi bấm Chụp ảnh.'};
}

export function renderFaceFraming(){
  return `<div id="face-frame-overlay" class="face-framing-overlay" hidden aria-hidden="true"><svg viewBox="0 0 640 480" preserveAspectRatio="none" focusable="false"><defs><mask id="face-frame-cutout" maskUnits="userSpaceOnUse" x="0" y="0" width="640" height="480"><rect width="640" height="480" fill="white"/><ellipse class="face-framing-oval" cx="320" cy="240" rx="116" ry="164" fill="black"/></mask></defs><rect width="640" height="480" class="face-framing-shade" mask="url(#face-frame-cutout)"/><ellipse class="face-framing-oval face-framing-border" cx="320" cy="240" rx="116" ry="164" fill="none"/><ellipse class="face-framing-oval face-framing-track" cx="320" cy="240" rx="108" ry="156" pathLength="100" fill="none" stroke-dasharray="18 32" stroke-dashoffset="-9"/></svg></div>`;
}

export function updateFaceFraming(root,command,preview){
  const overlay=root.querySelector('#face-frame-overlay'),caption=root.querySelector('#face-frame-caption');
  if(!overlay||!caption)return;
  const guide=faceFramingGuide(command),visible=!!guide&&preview?.phase==='live'&&!!preview.frameUrl;
  overlay.hidden=!visible;caption.hidden=!visible;
  if(!guide)return;
  overlay.dataset.distance=guide.distance;
  overlay.style.setProperty('--face-frame-scale',String(guide.scale));
  const title=root.querySelector('#face-frame-title'),instruction=root.querySelector('#face-frame-instruction');
  if(title&&title.textContent!==guide.title)title.textContent=guide.title;
  if(instruction&&instruction.textContent!==guide.instruction)instruction.textContent=guide.instruction;
}
