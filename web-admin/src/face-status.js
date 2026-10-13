const STATUSES=new Set(['IDLE','FACE_BLOCKED','FACE_READY','FACE_WAITING','REGISTERING','UNAVAILABLE','STOPPED']);
const ISSUES=new Set(['mask','hat','glasses','accessory_uncertain','accessory_head_out_of_frame']);
const SCAN_STATUSES=new Set(['IDLE','CHECKING','NO_MATCH','WAITING','WRITING','PENDING','ACCEPTED','DUPLICATE','REJECTED']);
const ATTENDANCE_STATUSES=new Set(['NONE','PENDING','ACCEPTED','DUPLICATE','REJECTED']);
const INITIAL='Bấm Bắt đầu xem và mở dịch vụ Trạm khuôn mặt trên laptop để xem kiểm tra phụ kiện.';
const OFFLINE='Chưa nhận được kiểm tra phụ kiện. Giữ dịch vụ Trạm khuôn mặt trên laptop chạy; ảnh camera vẫn có thể xem riêng.';
const STALE='Trạng thái kiểm tra đã cũ. Đang chờ ảnh được dịch vụ laptop xử lý; chưa thể xác nhận mặt đạt.';
const NO_ATTENDANCE={status:'NONE',employeeName:'',message:'Chưa có lượt chấm công khuôn mặt gần đây.',updatedAt:null};
const validStation=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(value);
const validUtc=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(value)&&Number.isFinite(Date.parse(value))&&new Date(Date.parse(value)).toISOString().slice(0,19)===value.slice(0,19);

function checkedSnapshot(value){
  if(!value||typeof value!=='object'||Array.isArray(value)||!STATUSES.has(value.status)||typeof value.accessoryGateEnabled!=='boolean'||typeof value.message!=='string'||!value.message.trim()||value.message.length>300||typeof value.updatedAt!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(value.updatedAt))throw new Error(OFFLINE);
  const stamp=Date.parse(value.updatedAt);
  if(!Number.isFinite(stamp)||new Date(stamp).toISOString().slice(0,19)!==value.updatedAt.slice(0,19)||
     (value.status==='FACE_BLOCKED'?!ISSUES.has(value.qualityIssue):value.qualityIssue!==null))throw new Error(OFFLINE);
  const scan=value.scan??{status:'IDLE',message:'Đưa một người vào khung hình để quét.',updatedAt:value.updatedAt};
  if(!scan||typeof scan!=='object'||Array.isArray(scan)||!SCAN_STATUSES.has(scan.status)||typeof scan.message!=='string'||!scan.message.trim()||scan.message.length>300||!validUtc(scan.updatedAt))throw new Error(OFFLINE);
  const attendance=value.attendance??{...NO_ATTENDANCE,updatedAt:value.updatedAt};
  if(!attendance||typeof attendance!=='object'||Array.isArray(attendance)||!ATTENDANCE_STATUSES.has(attendance.status)||typeof attendance.employeeName!=='string'||attendance.employeeName.length>120||typeof attendance.message!=='string'||!attendance.message.trim()||attendance.message.length>300||!validUtc(attendance.updatedAt))throw new Error(OFFLINE);
  return {status:value.status,qualityIssue:value.qualityIssue,message:value.message,updatedAt:value.updatedAt,accessoryGateEnabled:value.accessoryGateEnabled,
    scan:{status:scan.status,message:scan.message,updatedAt:scan.updatedAt},
    attendance:{status:attendance.status,employeeName:attendance.employeeName,message:attendance.message,updatedAt:attendance.updatedAt}};
}

export function createFaceStatusController({fetchImpl=(...args)=>fetch(...args),setTimer=setTimeout,clearTimer=clearTimeout,now=()=>Date.now(),isHidden=()=>document.hidden,onChange=()=>{},intervalMs=1000,timeoutMs=5000,maxAgeMs=10000}={}){
  const cadence=Number.isFinite(intervalMs)&&intervalMs>=1000?intervalMs:1000;
  let state={running:false,phase:'idle',message:INITIAL,updatedAt:null,qualityIssue:null,scan:{status:'IDLE',message:'Đưa một người vào khung hình để quét.'},attendance:NO_ATTENDANCE};
  let snapshot=null,generation=0,timer=null,timeout=null,controller=null,pending=null,stationId=null,disposed=false;
  function current(){
    if(!state.running||!snapshot)return {...state};
    const attendance=now()-Date.parse(snapshot.attendance.updatedAt)>60000?NO_ATTENDANCE:snapshot.attendance;
    const age=now()-Date.parse(snapshot.updatedAt);
    if(age>maxAgeMs||age<-3000)return {...state,phase:'stale',message:STALE,qualityIssue:null,updatedAt:null,scan:snapshot.scan,attendance};
    if(!snapshot.accessoryGateEnabled)return {...state,phase:'disabled',message:'Chưa bật kiểm tra khẩu trang và kính trên dịch vụ laptop.',qualityIssue:null,updatedAt:snapshot.updatedAt,scan:snapshot.scan,attendance};
    const phase=snapshot.status==='FACE_BLOCKED'?'blocked':snapshot.status==='FACE_READY'?'ready':
      ['UNAVAILABLE','STOPPED'].includes(snapshot.status)?'offline':'waiting';
    return {...state,phase,message:snapshot.message,qualityIssue:snapshot.qualityIssue,updatedAt:snapshot.updatedAt,scan:snapshot.scan,attendance};
  }
  const emit=()=>onChange(current());
  function stop(message=INITIAL){
    generation++;clearTimer(timer);clearTimer(timeout);timer=timeout=null;controller?.abort();controller=null;
    snapshot=null;stationId=null;state={running:false,phase:'idle',message,updatedAt:null,qualityIssue:null,scan:{status:'IDLE',message:'Đưa một người vào khung hình để quét.'},attendance:NO_ATTENDANCE};emit();
  }
  async function poll(version){
    if(version!==generation||!state.running||controller)return;
    const requestController=new AbortController();controller=requestController;
    timeout=setTimer(()=>requestController.abort(),timeoutMs);
    try{
      const response=await fetchImpl('/api/camera/face-status',{method:'GET',headers:{'X-Camera-Preview':'1','X-Camera-Station':stationId},cache:'no-store',credentials:'same-origin',redirect:'error',signal:requestController.signal});
      if(!response.ok||response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='application/json')throw new Error(OFFLINE);
      const value=checkedSnapshot(await response.json());
      if(version!==generation||!state.running||requestController.signal.aborted)return;
      snapshot=value;state={...state,phase:'waiting',message:value.message,scan:value.scan,attendance:value.attendance};emit();
    }catch{
      if(version===generation&&state.running){snapshot=null;state={...state,phase:'offline',message:OFFLINE,updatedAt:null,qualityIssue:null,scan:{status:'WAITING',message:'Chưa nhận được trạng thái quét từ laptop.'},attendance:NO_ATTENDANCE};emit();}
    }finally{
      if(controller===requestController){clearTimer(timeout);timeout=null;controller=null;}
    }
  }
  function tick(version){
    if(version!==generation||!state.running)return;
    if(isHidden()){stop('Đã dừng kiểm tra vì trang bị ẩn. Bấm Bắt đầu xem khi quay lại.');return;}
    emit();if(!controller)pending=poll(version);timer=setTimer(()=>tick(version),cadence);
  }
  async function start(expectedStationId){
    if(disposed||isHidden())return;
    if(state.running&&stationId===expectedStationId)return;
    stop();
    if(!validStation(expectedStationId)){state={...state,phase:'offline',message:'Chưa xác định được trạm của camera. Kiểm tra cấu hình dịch vụ laptop.'};emit();return;}
    const version=++generation;
    stationId=expectedStationId;state={...state,running:true,phase:'checking',message:'Đang kết nối kiểm tra phụ kiện…'};emit();
    await pending?.catch(()=>{});
    if(version!==generation||!state.running)return;
    pending=poll(version);timer=setTimer(()=>tick(version),cadence);await pending;
  }
  return {
    start,stop,getState:current,
    previewChanged(preview){
      if(disposed)return;
      if(!preview.running){if(state.running||state.phase!=='idle')stop();return;}
      if(preview.health)void start(preview.health.stationId);
    },
    visibilityChanged(){if(isHidden())stop('Đã dừng kiểm tra vì trang bị ẩn. Bấm Bắt đầu xem khi quay lại.');},
    dispose(){if(disposed)return;stop();disposed=true;},
  };
}

export function updateFaceStatus(root,state){
  const panel=root.querySelector('#camera-face-status'),badge=root.querySelector('#camera-face-status-badge'),message=root.querySelector('#camera-face-status-message');
  if(!panel||!badge||!message)return;
  const labels={idle:'Chưa mở',checking:'Đang kết nối',blocked:'Chưa đạt',ready:'Đạt kiểm tra phụ kiện',waiting:'Đang kiểm tra',stale:'Đang chờ trạng thái mới',offline:'Dịch vụ chưa sẵn sàng',disabled:'Chưa bật kiểm tra'};
  badge.textContent=labels[state.phase]||'Đang chờ';
  badge.className=`badge ${state.phase==='blocked'?'bad':state.phase==='ready'?'good':'neutral'}`;
  panel.classList.toggle('error',state.phase==='blocked');
  if(message.textContent!==state.message)message.textContent=state.message;
  const scanBadge=root.querySelector('#camera-face-scan-badge'),scanMessage=root.querySelector('#camera-face-scan-message');
  const attendanceBadge=root.querySelector('#camera-face-attendance-badge'),attendanceName=root.querySelector('#camera-face-attendance-name'),attendanceMessage=root.querySelector('#camera-face-attendance-message');
  const scan=state.scan||{status:'IDLE',message:'Đưa một người vào khung hình để quét.'};
  if(scanBadge){const labels={IDLE:'Chờ quét',CHECKING:'Đang nhận diện',NO_MATCH:'Chưa khớp',WAITING:'Cần chỉnh lại',WRITING:'Đang ghi công',PENDING:'Đang đồng bộ',ACCEPTED:'Đã chấm công',DUPLICATE:'Đã gộp lượt',REJECTED:'Không ghi công'};scanBadge.textContent=labels[scan.status]||'Đang chờ';scanBadge.className=`badge ${['ACCEPTED'].includes(scan.status)?'good':['NO_MATCH','REJECTED'].includes(scan.status)?'bad':'neutral'}`;}
  if(scanMessage&&scanMessage.textContent!==scan.message)scanMessage.textContent=scan.message;
  const attendance=state.attendance||NO_ATTENDANCE;
  if(attendanceBadge){const labels={NONE:'Chưa có lượt',PENDING:'Đang đồng bộ',ACCEPTED:'Đã chấm công',DUPLICATE:'Đã gộp lượt',REJECTED:'Không ghi công'};attendanceBadge.textContent=labels[attendance.status]||'Đang chờ';attendanceBadge.className=`badge ${attendance.status==='ACCEPTED'?'good':attendance.status==='REJECTED'?'bad':'neutral'}`;}
  if(attendanceName){attendanceName.textContent=attendance.employeeName||'';attendanceName.hidden=!attendance.employeeName;}
  if(attendanceMessage&&attendanceMessage.textContent!==attendance.message)attendanceMessage.textContent=attendance.message;
}
