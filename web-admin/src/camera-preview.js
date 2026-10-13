import {e,icon} from './ui.js';

const INTERVAL_MS=400; // At most 2.5 fresh snapshots per second, never overlapping.
const INITIAL_MESSAGE='Camera đang tắt. Bấm Bắt đầu xem để mở ảnh trực tiếp.';
const LOCAL_ONLY='Xem camera cần mở web trên localhost của laptop bằng máy chủ Node.';

export function renderCameraPreview(){
  return `<div class="page-heading"><div><div class="eyebrow">CAMERA & KHUÔN MẶT</div><h1>Camera & khuôn mặt</h1><p>Kiểm tra góc đặt và ánh sáng trước khi đăng ký khuôn mặt.</p></div></div>
  <div class="camera-layout"><section class="panel camera-panel" aria-labelledby="camera-title">
    <div class="panel-head"><div><h2 id="camera-title">Camera tại trạm</h2><p>Ảnh mới được cập nhật liên tục, tối đa 2–3 ảnh/giây.</p></div><span id="camera-state" class="badge neutral">Đã tắt</span></div>
    <div class="camera-controls"><button type="button" class="btn primary" data-action="camera-start" id="camera-start">${icon('camera')} Bắt đầu xem</button><button type="button" class="btn" data-action="camera-stop" id="camera-stop" disabled>Dừng xem</button><button type="button" class="btn" data-action="camera-rotate" aria-label="Xoay ảnh camera thêm 90 độ">${icon('refresh')} Xoay 90°</button><span id="camera-rotation" class="muted">0°</span></div>
    <div class="camera-feedback"><p id="camera-message" role="status" aria-live="polite">${e(INITIAL_MESSAGE)}</p><p id="camera-updated" class="hint">Chưa nhận ảnh.</p></div>
    <div id="camera-stage" class="camera-stage"><img id="camera-image" width="640" height="480" alt="Ảnh hiện tại từ camera chấm công" hidden><div id="camera-placeholder" class="camera-placeholder">${icon('camera')}<strong>Chưa mở camera</strong><span>Bấm Bắt đầu xem ở phía trên. Giữ camera được cấp nguồn và laptop cùng mạng.</span></div></div>
  </section><aside class="panel camera-guide"><h2>Đặt camera trước khi lấy mẫu</h2><ol><li>Đặt camera ngang tầm mặt, đủ sáng.</li><li>Chỉ để một người trong khung hình.</li><li>Giữ toàn bộ mặt trong ảnh, tránh bị cắt hoặc che.</li></ol><div id="camera-hardware" class="camera-hardware">Thông tin cảm biến sẽ hiện khi kết nối.</div><div class="notice"><strong>Đăng ký khuôn mặt cho nhân viên</strong><p>Vào Nhân viên, bấm tên người cần đăng ký rồi chọn Sửa hồ sơ → Khuôn mặt. Laptop cần chạy dịch vụ đăng ký kết nối Firebase.</p><a class="btn" href="#employees">Mở danh sách nhân viên</a></div><p class="hint">Mở trang này chỉ xem ảnh; không đăng ký mẫu và không ghi lượt chấm công. Camera dùng thiết bị thật, kể cả trong phiên dữ liệu mẫu.</p><p class="hint">Xoay ảnh ở đây chỉ đổi góc xem. Khi đăng ký, đặt board sao cho ảnh gốc đứng thẳng.</p></aside></div>`;
}

export function createPreviewController({fetchImpl=(...args)=>fetch(...args),createUrl=blob=>URL.createObjectURL(blob),revokeUrl=url=>URL.revokeObjectURL(url),setTimer=setTimeout,clearTimer=clearTimeout,now=()=>Date.now(),isHidden=()=>document.hidden,onChange=()=>{},intervalMs=INTERVAL_MS,expectedStationId=null}={}){
  const cadence=Number.isFinite(intervalMs)&&intervalMs>0?intervalMs:INTERVAL_MS;
  let state={running:false,phase:'idle',message:INITIAL_MESSAGE,rotation:0,sourceRotation:0,frameUrl:null,updatedAt:null,health:null};
  let generation=0,timer=null,controller=null,pending=null;
  const emit=()=>onChange({...state});
  const clearFrame=()=>{if(state.frameUrl)revokeUrl(state.frameUrl);state.frameUrl=null;state.updatedAt=null;};
  function stop(message='Đã dừng xem camera.',phase='idle'){
    generation++;clearTimer(timer);timer=null;controller?.abort();controller=null;
    clearFrame();state={...state,running:false,phase,message};emit();
  }
  async function request(endpoint,signal){
    let response;
    const headers={'X-Camera-Preview':'1'};
    if(expectedStationId!==null)headers['X-Camera-Station']=expectedStationId;
    try{response=await fetchImpl(endpoint,{method:'GET',headers,cache:'no-store',credentials:'same-origin',redirect:'error',signal});}
    catch(error){if(signal?.aborted)throw error;throw new Error('Không kết nối được máy chủ local. Giữ cửa sổ Node mở rồi thử lại.');}
    if(!response.ok){let value;try{value=await response.json();}catch{}throw new Error(value?.message||([404,405].includes(response.status)?LOCAL_ONLY:'Chưa mở được camera. Kiểm tra máy chủ local, nguồn và Wi-Fi rồi thử lại.'));}
    return response;
  }
  async function frame(version){
    if(version!==generation||!state.running)return;
    const signal=controller?.signal;
    try{
      const response=await request('/api/camera/frame',signal);
      if(response.headers.get('content-type')?.split(';')[0].trim()!=='image/jpeg')throw new Error(LOCAL_ONLY);
      const blob=await response.blob();
      if(version!==generation||!state.running||signal?.aborted)return;
      if(!blob.size||blob.size>5*1024*1024)throw new Error('Ảnh camera không hợp lệ. Hãy thử lại.');
      const rotationHeader=response.headers.get('X-Camera-Rotation');
      const sourceRotation=rotationHeader===null?(state.health?.rotation??0):Number(rotationHeader);
      if(![0,90,180,270].includes(sourceRotation)||rotationHeader!==null&&!/^(0|90|180|270)$/.test(rotationHeader))throw new Error('Chiều ảnh camera không hợp lệ. Kiểm tra cấu hình laptop.');
      state.sourceRotation=sourceRotation;
      const previous=state.frameUrl;state.frameUrl=createUrl(blob);state.updatedAt=now();
      state.phase='live';state.message='Đang nhận ảnh mới từ camera.';emit();
      if(previous)revokeUrl(previous);
      // A modal can be removed by the state callback while a frame settles.
      if(version!==generation||!state.running||signal?.aborted)return;
      timer=setTimer(()=>{timer=null;pending=frame(version);},cadence);
    }catch(error){if(version===generation&&!signal?.aborted)stop(error.message,'error');}
  }
  async function start(){
    if(state.running)return;
    if(isHidden()){stop('Trang đang ẩn. Quay lại trang rồi bấm Bắt đầu xem.');return;}
    const version=++generation;
    state={...state,running:true,phase:'connecting',message:'Đang kết nối camera…',health:null};emit();
    // A restart waits for the aborted operation to settle before requesting again.
    await pending?.catch(()=>{});
    if(version!==generation||!state.running)return;
    controller=new AbortController();const signal=controller.signal;
    pending=(async()=>{
      try{
        const response=await request('/api/camera/health',signal);
        if(response.headers.get('content-type')?.split(';')[0].trim()!=='application/json')throw new Error(LOCAL_ONLY);
        let health;try{health=await response.json();}catch{throw new Error('Trạng thái camera không hợp lệ. Hãy thử lại.');}
        if(version!==generation||signal.aborted)return;
        if(expectedStationId!==null&&health?.stationId!==expectedStationId)throw new Error('Camera trên laptop không thuộc trạm đang đăng ký. Kiểm tra trạm trong cấu hình dịch vụ laptop rồi thử lại.');
        if(!health||health.cameraReady!==true||health.wifiConnected!==true)throw new Error('Camera chưa sẵn sàng. Kiểm tra nguồn hoặc khởi động lại camera rồi thử lại.');
        const sourceRotation=health.rotation??0;
        if(![0,90,180,270].includes(sourceRotation))throw new Error('Chiều ảnh camera không hợp lệ. Kiểm tra cấu hình laptop.');
        state.sourceRotation=sourceRotation;
        state.health=health;emit();await frame(version);
      }catch(error){if(version===generation&&!signal.aborted)stop(error.message,'error');}
    })();
    await pending;
  }
  function rotate(){state.rotation=(state.rotation+90)%360;emit();}
  return {start,stop,rotate,visibilityChanged(){if(isHidden())stop('Đã dừng vì trang bị ẩn. Bấm Bắt đầu xem khi quay lại.');},imageFailed(){if(state.running)stop('Không đọc được ảnh camera. Hãy thử lại.','error');},getState:()=>({...state})};
}

export function updateCameraPreview(root,state,idPrefix='camera'){
  const query=id=>root.querySelector(`#${id.replace(/^camera/,idPrefix)}`);
  const image=query('camera-image');if(!image)return;
  image.hidden=!state.frameUrl;
  if(state.frameUrl){if(image.getAttribute('src')!==state.frameUrl)image.setAttribute('src',state.frameUrl);}else image.removeAttribute('src');
  const displayRotation=((state.sourceRotation||0)+state.rotation)%360;
  image.style.transform=`rotate(${displayRotation}deg)`;
  query('camera-stage')?.classList.toggle('portrait',displayRotation%180!==0);
  query('camera-placeholder').hidden=!!state.frameUrl;
  const start=query('camera-start'),stop=query('camera-stop'),badge=query('camera-state');
  start.disabled=state.running;start.innerHTML=`${icon('camera')} ${state.phase==='error'?'Thử lại':'Bắt đầu xem'}`;
  stop.disabled=!state.running;
  badge.textContent=state.phase==='live'?'Đang xem':state.phase==='connecting'?'Đang kết nối':state.phase==='error'?'Cần kiểm tra':'Đã tắt';
  badge.className=`badge ${state.phase==='live'?'good':state.phase==='error'?'bad':'neutral'}`;
  if(query('camera-message').textContent!==state.message)query('camera-message').textContent=state.message;
  query('camera-message').classList.toggle('text-warning',state.phase==='error');
  query('camera-updated').textContent=state.updatedAt?`Ảnh mới nhất: ${new Date(state.updatedAt).toLocaleTimeString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'})}`:'Chưa nhận ảnh.';
  query('camera-rotation').textContent=`${state.rotation}°`;
  query('camera-hardware').textContent=state.health?`${state.health.sensor||'Camera'} · ${state.health.board||'Trạm camera'}`:'Thông tin cảm biến sẽ hiện khi kết nối.';
}
