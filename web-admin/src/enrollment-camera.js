import {e,icon} from './ui.js';
import {createPreviewController,updateCameraPreview} from './camera-preview.js';
import {createCaptureResultController,updateCaptureResult} from './capture-result.js';
import {renderFaceFraming,updateFaceFraming} from './face-framing.js';

export function renderEnrollmentCameraPreview(){
  return `<section class="enrollment-camera-preview" aria-labelledby="face-camera-title">
    <div class="panel-head"><div><h3 id="face-camera-title">Camera đăng ký khuôn mặt</h3><p>Chỉ một người, mặt rõ và đủ sáng. Tháo khẩu trang và kính (kính cận hoặc kính râm); giữ trọn khuôn mặt, cả trán và cằm trong ảnh.</p></div><span id="face-camera-state" class="badge neutral">Đã tắt</span></div>
    <div class="camera-controls"><button type="button" class="btn primary" data-action="face-camera-start" id="face-camera-start">${icon('camera')} Bắt đầu xem</button><button type="button" class="btn" data-action="face-camera-stop" id="face-camera-stop" disabled>Dừng xem</button><button type="button" class="btn" data-action="face-camera-rotate" aria-label="Xoay ảnh camera thêm 90 độ">${icon('refresh')} Xoay 90°</button><span id="face-camera-rotation" class="muted">0°</span></div>
    <div class="camera-feedback"><p id="face-camera-message" role="status" aria-live="polite">Đang chuẩn bị hình ảnh từ camera…</p><p id="face-camera-updated" class="hint">Chưa nhận ảnh.</p></div>
    <div id="face-camera-stage" class="camera-stage"><img id="face-camera-image" width="640" height="480" alt="Ảnh trực tiếp từ camera của trạm đăng ký khuôn mặt" hidden>${renderFaceFraming()}<div id="face-camera-placeholder" class="camera-placeholder">${icon('camera')}<strong>Chưa nhận hình từ camera</strong><span>Giữ camera được cấp nguồn và laptop cùng Wi-Fi. Bấm Bắt đầu xem nếu hình đang tắt.</span></div></div>
    <div id="face-frame-caption" class="face-framing-caption" hidden role="status" aria-live="polite" aria-atomic="true"><strong id="face-frame-title"></strong><p id="face-frame-instruction"></p></div>
    <p id="face-camera-hardware" class="hint">Thông tin cảm biến sẽ hiện khi kết nối.</p><p class="hint">${e('Xoay ảnh chỉ đổi góc xem; khi lấy mẫu giữ ảnh gốc đứng thẳng. Dừng xem không hủy yêu cầu đăng ký.')}</p>
    <section class="capture-result" aria-labelledby="face-capture-title"><h3 id="face-capture-title">Ảnh vừa chụp</h3><div class="capture-result-stage"><img id="face-capture-image" width="640" height="480" alt="Đúng ảnh của lần bấm Chụp ảnh, đã được laptop kiểm tra" hidden><p id="face-capture-placeholder">Chưa có ảnh của lần chụp này</p></div><p id="face-capture-result" class="hint" role="status" aria-live="polite">Bấm Chụp ảnh. Ảnh được laptop kiểm tra sẽ hiện ở đây.</p><button type="button" class="btn" data-action="face-capture-preview-retry" id="face-capture-preview-retry" hidden>Thử tải ảnh vừa chụp</button></section>
  </section>`;
}

// This viewer only requests local camera snapshots. Enrollment remains controlled
// by the existing command tracker, including when the viewer stops or is closed.
export function createEnrollmentCameraPreview(root,{documentImpl=root.ownerDocument,expectedStationId,controllerFactory=createPreviewController,captureFactory=createCaptureResultController,onStart=()=>{}}={}){
  let disposed=false,viewer,intent=0,command=null,preview=null,captureState=null;
  const detached=()=>root.isConnected===false;
  const image=root.querySelector('#face-camera-image');
  const capturedImage=root.querySelector('#face-capture-image');
  const windowImpl=documentImpl?.defaultView;
  function refreshCaptureResult(){
    if(!captureState||disposed||detached())return;
    let state=captureState;
    if(['COMPLETED','FAILED','CANCELLED'].includes(command?.status)&&!state.frameUrl){
      if(state.phase==='idle')state={...state,message:command.status==='COMPLETED'?'Yêu cầu đã hoàn tất. Ảnh cuối sẽ hiện ở đây nếu còn được lưu tạm trên laptop.':'Yêu cầu đã kết thúc. Không chụp thêm ảnh trong yêu cầu này.'};
      else if(state.phase==='error')state={...state,message:'Chưa xem được ảnh cuối của yêu cầu đã kết thúc. Có thể thử tải lại ảnh.'};
    }
    updateCaptureResult(root,state);
  }
  const capture=captureFactory({expectedStationId,isHidden:()=>Boolean(documentImpl?.hidden),onChange:state=>{captureState=state;refreshCaptureResult();}});
  captureState=capture.getState?.()||{phase:'idle',frameUrl:null,message:'Ảnh của lần chụp sẽ hiện ở đây.'};
  viewer=controllerFactory({intervalMs:1000,expectedStationId:expectedStationId??null,isHidden:()=>Boolean(documentImpl?.hidden),onChange:state=>{
    if(disposed)return;
    if(detached()){if(state.running)viewer?.stop();return;}
    updateCameraPreview(root,state,'face-camera');
    preview=state;updateFaceFraming(root,command,preview);
  }});
  async function start(){
    if(disposed||detached())return;
    if(documentImpl?.hidden){viewer.visibilityChanged();return;}
    const version=++intent;
    try{
      await onStart();
      if(version!==intent||disposed||detached()||documentImpl?.hidden)return;
      await viewer.start();
    }catch(error){if(version===intent&&!disposed&&!detached())viewer.stop(error?.message||'Chưa mở được hình camera. Bấm Thử lại.','error');}
  }
  function stop(){intent++;if(!disposed)viewer.stop();}
  function visibilityChanged(){
    if(disposed)return;
    capture.visibilityChanged();
    if(documentImpl?.hidden)intent++;
    if(detached())viewer.stop();else viewer.visibilityChanged();
  }
  function click(event){
    const button=event.target?.closest?.('[data-action]');
    const action=button?.dataset?.action;
    if(!['face-camera-start','face-camera-stop','face-camera-rotate','face-capture-preview-retry'].includes(action))return;
    if(root.contains&&!root.contains(button))return;
    event.preventDefault();event.stopPropagation();
    if(disposed||button.disabled||detached())return;
    if(action==='face-capture-preview-retry')capture.retry();
    else if(action==='face-camera-start')void start();
    else if(action==='face-camera-stop')stop();
    else viewer.rotate();
  }
  function imageFailed(){if(!disposed&&!detached())viewer.imageFailed();}
  function captureImageFailed(){if(!disposed&&!detached())capture.imageFailed();}
  function pageHidden(){stop();capture.pause();}
  root.addEventListener('click',click);
  image?.addEventListener('error',imageFailed);
  capturedImage?.addEventListener('error',captureImageFailed);
  documentImpl?.addEventListener('visibilitychange',visibilityChanged);
  windowImpl?.addEventListener('pagehide',pageHidden);
  function dispose(){
    if(disposed)return;
    intent++;viewer.stop();capture.dispose();disposed=true;
    root.removeEventListener('click',click);
    image?.removeEventListener('error',imageFailed);
    capturedImage?.removeEventListener('error',captureImageFailed);
    documentImpl?.removeEventListener('visibilitychange',visibilityChanged);
    windowImpl?.removeEventListener('pagehide',pageHidden);
  }
  return {start,stop,dispose,visibilityChanged,observeCapture(value,requestId){if(!disposed&&!detached()){command=value;updateFaceFraming(root,command,preview);capture.observe(value,requestId);refreshCaptureResult();}}};
}
