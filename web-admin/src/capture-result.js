import {faceSampleCount,validManualCapturePlan} from './face-capture-plan.js';

const validId=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(value);
const reasons=new Set(['quality_ok','no_face','multiple','low_quality','invalid_image','camera_error']);
const INITIAL='Bấm Chụp ảnh. Ảnh được laptop kiểm tra sẽ hiện ở đây.';

export function createCaptureResultController({fetchImpl=(...args)=>fetch(...args),createUrl=blob=>URL.createObjectURL(blob),revokeUrl=url=>URL.revokeObjectURL(url),onChange=()=>{},expectedStationId,isHidden=()=>document.hidden,setTimer=setTimeout,clearTimer=clearTimeout}={}){
  let state={phase:'idle',frameUrl:null,sequence:null,saved:null,reason:null,message:INITIAL},generation=0,controller=null,timer=null,key='',last=null,disposed=false;
  const emit=()=>onChange({...state});
  function clear(){if(state.frameUrl)revokeUrl(state.frameUrl);state.frameUrl=null;}
  function abort(){generation++;controller?.abort();controller=null;clearTimer(timer);timer=null;}
  function reset(message=INITIAL){abort();clear();state={phase:'idle',frameUrl:null,sequence:null,saved:null,reason:null,message};key='';emit();}
  async function load(requestId,sequence,version,total,attempt=0){
    if(disposed||version!==generation||isHidden())return;
    const signal=controller.signal;
    try{
      const response=await fetchImpl(`/api/camera/enrollment-preview/${requestId}/${sequence}`,{method:'GET',headers:{'X-Camera-Preview':'1','X-Camera-Station':expectedStationId},credentials:'same-origin',cache:'no-store',redirect:'error',signal});
      if(disposed||version!==generation||signal.aborted)return;
      if(response.status===404&&attempt<2){await response.body?.cancel();timer=setTimer(()=>{timer=null;void load(requestId,sequence,version,total,attempt+1);},200);return;}
      if(!response.ok){let data;try{data=await response.json();}catch{}throw new Error(data?.message||'Chưa xem được ảnh vừa chụp. Giữ dịch vụ laptop chạy rồi thử lại.');}
      const reason=response.headers.get('X-Capture-Reason'),count=response.headers.get('X-Capture-Saved'),responseTotal=response.headers.get('X-Capture-Total')??'10';
      if(response.headers.get('content-type')?.split(';')[0].trim()!=='image/jpeg'||response.headers.get('X-Enrollment-Request')!==requestId||response.headers.get('X-Capture-Sequence')!==String(sequence)||!reasons.has(reason)||responseTotal!==String(total)||!/^(0|[1-9]|10)$/.test(count||'')||Number(count)>total)throw new Error('Ảnh không khớp lần chụp này. Bấm Chụp ảnh để thử lại.');
      const blob=await response.blob();
      if(disposed||version!==generation||signal.aborted||isHidden())return;
      if(!blob.size||blob.size>5*1024*1024)throw new Error('Ảnh vừa chụp không hợp lệ. Hãy chụp lại.');
      clear();state={phase:'ready',frameUrl:createUrl(blob),sequence,saved:Number(count),reason,message:reason==='quality_ok'?`Lần chụp ${sequence}: đã lưu ảnh. Tổng ${count}/${total} ảnh đạt yêu cầu.`:`Lần chụp ${sequence}: ảnh chưa đạt, không thêm vào mẫu. Tổng ${count}/${total} ảnh đã lưu.`};emit();
    }catch(error){if(!disposed&&version===generation&&!signal.aborted){clear();state={...state,phase:'error',message:error.message||'Chưa tải được ảnh vừa chụp.'};emit();}}
  }
  function observe(command,requestId){
    if(disposed)return;
    last={command,requestId};
    if(isHidden()){reset('Ảnh vừa chụp đã ẩn khi bạn chuyển trang.');return;}
    if(!validManualCapturePlan(command)||(command.requestId||command.id)!==requestId||!validId(requestId)||!validId(expectedStationId)||!['CAPTURING','FINALIZING','COMPLETED'].includes(command.status)||command.cancelRequested===true){if(key||state.frameUrl)reset();return;}
    const requested=command.captureRequestSeq,consumed=command.captureConsumedSeq;
    if(!Number.isSafeInteger(requested)||!Number.isSafeInteger(consumed)||requested<0||requested>100||consumed<0||consumed>requested)return;
    const total=faceSampleCount(command),nextKey=`${requestId}/${requested}/${consumed}/${total}`;
    if(nextKey===key)return;
    abort();clear();key=nextKey;
    if(requested>consumed){state={...state,phase:'loading',frameUrl:null,sequence:requested,message:'Laptop đang chụp và kiểm tra ảnh. Vui lòng giữ yên.'};emit();return;}
    if(!consumed){state={...state,phase:'idle',frameUrl:null,message:INITIAL};emit();return;}
    if(command.captureLastReason==='camera_error'){state={...state,phase:'error',frameUrl:null,sequence:consumed,message:'Lần này chưa lấy được ảnh camera. Kiểm tra kết nối rồi bấm Chụp ảnh lại.'};emit();return;}
    state={...state,phase:'loading',frameUrl:null,sequence:consumed,message:'Đang tải đúng ảnh laptop vừa kiểm tra…'};emit();
    controller=new AbortController();void load(requestId,consumed,generation,total);
  }
  return {observe,retry(){key='';if(last)observe(last.command,last.requestId);},pause(){reset('Ảnh vừa chụp đã ẩn khi bạn chuyển trang.');},visibilityChanged(){if(isHidden())reset('Ảnh vừa chụp đã ẩn khi bạn chuyển trang.');else if(last)observe(last.command,last.requestId);},imageFailed(){abort();clear();state={...state,phase:'error',message:'Không đọc được ảnh vừa chụp. Bấm Thử tải ảnh hoặc chụp lại.'};emit();},dispose(){if(disposed)return;disposed=true;abort();clear();last=null;},getState:()=>({...state})};
}

export function updateCaptureResult(root,state){
  const image=root.querySelector('#face-capture-image'),placeholder=root.querySelector('#face-capture-placeholder'),message=root.querySelector('#face-capture-result'),retry=root.querySelector('#face-capture-preview-retry');
  if(!image)return;
  image.hidden=!state.frameUrl;
  if(state.frameUrl){if(image.getAttribute('src')!==state.frameUrl)image.setAttribute('src',state.frameUrl);}else image.removeAttribute('src');
  if(placeholder){placeholder.hidden=!!state.frameUrl;placeholder.textContent=state.phase==='loading'?'Đang xử lý ảnh…':'Chưa có ảnh của lần chụp này';}
  if(message){message.textContent=state.message;message.className=`hint${state.phase==='error'?' text-warning':''}`;}
  if(retry)retry.hidden=state.phase!=='error';
}
