import {e,button,select,asDate,errorMessage} from './ui.js';
import {renderEnrollmentCameraPreview,createEnrollmentCameraPreview} from './enrollment-camera.js';
import {SIX_POSE_PLAN,faceSampleCount,validManualCapturePlan,renderCapturePoseGuide} from './face-capture-plan.js';

export const faceSubscriptionQueries={faceProfiles:{},faceStations:{},faceEnrollmentCommands:{orderBy:[['requestedAt','desc']],limit:100}};
const TERMINAL=new Set(['COMPLETED','FAILED','CANCELLED']);
const ACTIVE=new Set(['PENDING','CAPTURING','FINALIZING']);
const idPattern=/^[A-Za-z0-9_-]{1,128}$/;
const validId=value=>typeof value==='string'&&idPattern.test(value);
const requireValue=(condition,message)=>{if(!condition)throw new Error(message);};
const millis=value=>asDate(value)?.getTime();
const registered=profile=>profile?.enabled===true&&Number.isSafeInteger(profile.version)&&profile.version>0;
const canCancel=command=>['PENDING','CAPTURING'].includes(command?.status)&&command.cancelRequested!==true;
const newestCommand=(employeeId,data)=>(data?.faceEnrollmentCommands||[]).filter(row=>row.employeeId===employeeId).sort((a,b)=>(millis(b.requestedAt)||0)-(millis(a.requestedAt)||0))[0];

export function faceStationAvailable(station,now=Date.now()){
  const seen=millis(station?.lastSeenAt),age=now-seen;
  return !!station&&validId(station.stationId)&&(!station.id||station.id===station.stationId)&&validId(station.faceDeviceId)&&validId(station.sessionId)&&station.mode==='firebase'&&station.cameraReady===true&&Number.isFinite(seen)&&age>=-30000&&age<=90000;
}

export function faceCommandStatus(command){
  if(!command)return 'Chưa có yêu cầu đăng ký';
  if(ACTIVE.has(command.status)&&command.cancelRequested)return 'Đang hủy; chờ laptop xác nhận';
  if(command.status==='PENDING')return 'Đang chờ laptop';
  if(command.status==='CAPTURING'){
    const total=faceSampleCount(command),count=Math.max(0,Math.min(total,Number(command.samplesCaptured)||0));
    if(command.action==='DELETE')return 'Đang xóa mẫu';
    if(command.captureMode==='MANUAL')return count>=total?'Đã thu đủ ảnh; chờ laptop lưu mẫu':command.captureRequestSeq>command.captureConsumedSeq?`Đang chụp ảnh: ${count}/${total}`:`Chờ bạn bấm Chụp ảnh: ${count}/${total}`;
    return `Đang thu ảnh: ${count}/${total}`;
  }
  if(command.status==='FINALIZING')return 'Đang lưu mẫu; vui lòng chờ';
  if(command.status==='COMPLETED')return command.action==='DELETE'?'Đã xóa mẫu khuôn mặt':'Đã đăng ký khuôn mặt';
  if(command.status==='CANCELLED')return 'Đã hủy yêu cầu';
  if(command.status==='FAILED')return `Thất bại: ${String(command.message||'Kiểm tra laptop và camera rồi thử lại').slice(0,300)}`;
  return 'Chưa xác định trạng thái';
}

function requireRealSession(ctx){
  requireValue(!ctx.demo,'Đăng ký khuôn mặt cần đăng nhập Admin và dịch vụ laptop kết nối Firebase. Dữ liệu mẫu không tạo yêu cầu thật.');
  requireValue(ctx.user?.uid&&ctx.store,'Chưa đăng nhập Admin');
}

// Read all reservations before writing, so concurrent admins reserve one station
// and one employee in the same Firestore transaction.
export async function requestFaceEnrollment(ctx,{employeeId,stationId,action='ENROLL',captureMode='AUTO',capturePlan,now=Date.now()}){
  requireRealSession(ctx);
  requireValue(validId(employeeId)&&validId(stationId),'Chưa chọn nhân viên hoặc trạm camera hợp lệ');
  requireValue(['ENROLL','DELETE'].includes(action),'Thao tác khuôn mặt không hợp lệ');
  requireValue(['AUTO','MANUAL'].includes(captureMode)&&(captureMode!=='MANUAL'||action==='ENROLL'),'Cách chụp ảnh không hợp lệ');
  requireValue(capturePlan===undefined||(capturePlan===SIX_POSE_PLAN&&captureMode==='MANUAL'&&action==='ENROLL'),'Kế hoạch chụp ảnh không hợp lệ');
  const sampleCount=action==='DELETE'?0:capturePlan===SIX_POSE_PLAN?6:10;
  const store=ctx.store,uid=ctx.user.uid,requestId=store.id('faceEnrollmentCommands');
  requireValue(validId(requestId),'Mã yêu cầu không hợp lệ');
  await store.transaction(async tx=>{
    const user=await tx.get('users',uid);
    requireValue(user?.role==='ADMIN'&&user.active===true,'Chỉ Admin đang hoạt động được quản lý mẫu khuôn mặt');
    const employee=await tx.get('employees',employeeId);
    requireValue(employee?.active===true,'Nhân viên không tồn tại hoặc đã ngừng hoạt động');
    const station=await tx.get('faceStations',stationId);
    requireValue(station?.stationId===stationId&&faceStationAvailable(station,now),'Trạm camera chưa sẵn sàng. Bật dịch vụ laptop ở chế độ Firebase và kiểm tra camera.');
    const lock=await tx.get('faceEnrollmentLocks',employeeId);
    const previousIds=[station.pendingCommandId,lock?.commandId].filter(id=>id!==null&&id!==undefined);
    for(const oldId of new Set(previousIds)){
      requireValue(validId(oldId),'Khóa yêu cầu không hợp lệ; kiểm tra dịch vụ laptop');
      const old=await tx.get('faceEnrollmentCommands',oldId);
      requireValue(old&&TERMINAL.has(old.status),'Nhân viên hoặc trạm đang có yêu cầu chưa hoàn tất. Chờ hoặc hủy yêu cầu trước.');
    }
    tx.set('faceEnrollmentCommands',requestId,{requestId,employeeId,stationId,deviceId:station.faceDeviceId,sessionId:station.sessionId,action,status:'PENDING',requestedBy:uid,requestedAt:store.stamp(),sampleCount,samplesCaptured:0,cancelRequested:false,message:'',...(capturePlan===SIX_POSE_PLAN?{capturePlan}:{}),...(captureMode==='MANUAL'?{captureMode,captureRequestSeq:0,captureConsumedSeq:0}:{})});
    tx.update('faceStations',stationId,{pendingCommandId:requestId,updatedAt:store.stamp()});
    tx.set('faceEnrollmentLocks',employeeId,{employeeId,commandId:requestId,stationId,updatedAt:store.stamp()});
  });
  return requestId;
}

export async function requestFaceCapture(ctx,requestId,{now=Date.now()}={}){
  requireRealSession(ctx);requireValue(validId(requestId),'Yêu cầu không hợp lệ');
  const store=ctx.store,uid=ctx.user.uid;
  return store.transaction(async tx=>{
    const user=await tx.get('users',uid);
    requireValue(user?.role==='ADMIN'&&user.active===true,'Chỉ Admin đang hoạt động được chụp mẫu khuôn mặt');
    const command=await tx.get('faceEnrollmentCommands',requestId);
    requireValue(command?.requestedBy===uid,'Chỉ người mở yêu cầu này được bấm chụp ảnh');
    requireValue(command.requestId===requestId&&validManualCapturePlan(command),'Thông tin yêu cầu chụp ảnh không hợp lệ');
    requireValue(command.action==='ENROLL'&&command.captureMode==='MANUAL'&&command.status==='CAPTURING'&&command.cancelRequested===false,'Yêu cầu chưa sẵn sàng để chụp ảnh');
    const requested=command.captureRequestSeq,consumed=command.captureConsumedSeq;
    requireValue(Number.isSafeInteger(requested)&&requested>=0&&requested<100&&requested===consumed,'Đang xử lý ảnh trước; chờ kết quả rồi chụp tiếp');
    requireValue(Number.isSafeInteger(command.samplesCaptured)&&command.samplesCaptured>=0&&command.samplesCaptured<command.sampleCount,'Đã lấy đủ ảnh hoặc tiến độ không hợp lệ');
    requireValue(validId(command.employeeId)&&validId(command.stationId),'Nhân viên hoặc trạm không hợp lệ');
    const employee=await tx.get('employees',command.employeeId),station=await tx.get('faceStations',command.stationId),lock=await tx.get('faceEnrollmentLocks',command.employeeId);
    requireValue(employee?.active===true,'Nhân viên đã ngừng hoạt động');
    requireValue(faceStationAvailable(station,now)&&station.sessionId===command.sessionId&&station.faceDeviceId===command.deviceId&&station.pendingCommandId===requestId,'Trạm camera đã mất kết nối hoặc đổi phiên');
    requireValue(lock?.commandId===requestId&&lock.stationId===command.stationId&&lock.employeeId===command.employeeId,'Yêu cầu đăng ký đã thay đổi');
    tx.update('faceEnrollmentCommands',requestId,{captureRequestSeq:requested+1,captureRequestedAt:store.stamp()});
    return requested+1;
  });
}

export async function cancelFaceEnrollment(ctx,requestId,{allowFinished=false}={}){
  requireRealSession(ctx);requireValue(validId(requestId),'Yêu cầu không hợp lệ');
  const store=ctx.store,uid=ctx.user.uid;
  const sameSession=()=>ctx.store===store&&ctx.user?.uid===uid&&!ctx.demo;
  return store.transaction(async tx=>{
    requireValue(sameSession(),'Phiên đăng nhập đã thay đổi.');
    const user=await tx.get('users',uid);
    requireValue(user?.role==='ADMIN'&&user.active===true,'Chỉ Admin đang hoạt động được hủy yêu cầu');
    const command=await tx.get('faceEnrollmentCommands',requestId);
    requireValue(sameSession(),'Phiên đăng nhập đã thay đổi.');
    // Closing can race with a worker finishing or accepting an earlier cancel.
    // Return the fresh state without trying to change an irreversible phase.
    if(allowFinished&&command&&(TERMINAL.has(command.status)||command.status==='FINALIZING'||(ACTIVE.has(command.status)&&command.cancelRequested===true)))return command;
    requireValue(canCancel(command),'Yêu cầu đã kết thúc, đang lưu mẫu hoặc đang chờ xác nhận hủy');
    tx.update('faceEnrollmentCommands',requestId,{cancelRequested:true,cancelRequestedAt:store.stamp()});
    return {...command,cancelRequested:true};
  });
}

export function renderFaceEmployeeControls(employee,ctx){
  const profile=(ctx.data.faceProfiles||[]).find(row=>(row.employeeId||row.id)===employee.id);
  const command=newestCommand(employee.id,ctx.data),busy=ACTIVE.has(command?.status);
  const errors=['faceProfiles','faceStations','faceEnrollmentCommands'].filter(name=>ctx.errors?.[name]);
  const loaded=['faceProfiles','faceStations','faceEnrollmentCommands'].every(name=>ctx.loaded?.[name]!==false);
  const allowed=employee.active===true&&!ctx.demo&&loaded&&!errors.length&&!busy;
  const attributes=`data-id="${e(employee.id)}"${allowed?'':' disabled'}`;
  const status=registered(profile)?`Đã đăng ký · v${profile.version}`:'Chưa đăng ký';
  return `<div class="face-employee-controls"><span class="badge ${registered(profile)?'good':'neutral'}">${e(status)}</span><div class="actions">${button(registered(profile)?'Đăng ký lại khuôn mặt':'Đăng ký khuôn mặt','face-enroll',attributes)}${registered(profile)?button('Xóa mẫu mặt','face-delete',attributes+' class="danger"'):''}${command&&!ctx.demo?button('Xem yêu cầu','face-progress',`data-id="${e(employee.id)}" data-request-id="${e(command.id||command.requestId)}"`):''}</div>${ctx.demo?'<small>Đăng nhập Admin để đăng ký với camera thật.</small>':errors.length?`<small>Chưa tải được dữ liệu khuôn mặt. Kiểm tra kết nối hoặc quyền Firebase.</small>${button('Tải lại dữ liệu mặt','face-subscriptions-retry')}`:busy?`<small>${e(faceCommandStatus(command))}</small>`:!loaded?'<small>Đang tải dữ liệu khuôn mặt…</small>':employee.active!==true?'<small>Nhân viên đã ngừng hoạt động.</small>':''}</div>`;
}

function completionConfirmed(command,profile){
  if(command?.status!=='COMPLETED'||!profile||!Number.isSafeInteger(profile.version)||profile.version<=0)return false;
  const version=Number(command.profileVersion);
  if(Number.isFinite(version)&&version>0&&Number(profile.version)<version)return false;
  return command.action==='DELETE'?profile.enabled===false:registered(profile);
}

function progressBody(state){
  const command=state.command;
  const confirmed=completionConfirmed(command,state.profile);
  const status=command?.status==='COMPLETED'&&!confirmed?'Laptop đã hoàn tất yêu cầu; đang chờ xác nhận hồ sơ.':command?faceCommandStatus(command):'Đang tải trạng thái yêu cầu từ Firebase…';
  const total=faceSampleCount(command),captured=Math.max(0,Math.min(total,Number(command?.samplesCaptured)||0));
  const errors=[state.error,...Object.values(state.listenerErrors||{})].filter(Boolean);
  const manual=command?.action==='ENROLL'&&command.captureMode==='MANUAL';
  const takingSamples=['PENDING','CAPTURING'].includes(command?.status)&&command.cancelRequested!==true&&captured<total;
  const shotPending=command?.captureRequestSeq>command?.captureConsumedSeq;
  const canShoot=validManualCapturePlan(command)&&command.status==='CAPTURING'&&command.cancelRequested===false&&captured<total&&Number.isSafeInteger(command.captureRequestSeq)&&command.captureRequestSeq>=0&&command.captureRequestSeq<100&&command.captureRequestSeq===command.captureConsumedSeq&&!state.capturing&&!state.closing&&!state.canceling&&!Object.keys(state.listenerErrors||{}).length;
  const shot=manual&&takingSamples?`${button(state.capturing||shotPending?'Đang chụp…':`Chụp ảnh (${Math.min(total,captured+1)}/${total})`,'face-capture',`data-request-id="${e(state.requestId)}" class="primary"${canShoot?'':' disabled'}`)}<p class="hint">Chỉnh mặt trong hình rồi bấm Chụp ảnh. Xem ảnh của từng lần bấm ở phần Ảnh vừa chụp. Chỉ ảnh đạt yêu cầu mới tăng tiến độ; đủ ${total} ảnh hệ thống sẽ lưu mẫu.</p>${command.message&&command.status==='CAPTURING'?`<p class="notice capture-outcome" role="status">${e(String(command.message).slice(0,300))}</p>`:''}`:'';
  return `<p><strong>${e(state.employeeName)}</strong></p><p role="status" aria-live="polite">${e(status)}</p>${total?`<progress max="${total}" value="${captured}" aria-label="Số ảnh khuôn mặt đã thu">${captured}/${total}</progress><p class="hint">${captured}/${total} ảnh đã thu${takingSamples?' · Chỉ đứng một người trước camera.':'.'}</p>`:''}${renderCapturePoseGuide(command,{completionConfirmed:confirmed})}${shot}${errors.length?`<div class="notice error" role="alert">${errors.map(e).join('<br>')}</div>`:''}${Object.keys(state.listenerErrors||{}).length?button('Thử theo dõi lại','face-progress-retry',`data-request-id="${e(state.requestId)}"`):''}${canCancel(command)?button(state.canceling?'Đang gửi yêu cầu hủy…':'Hủy yêu cầu','face-command-cancel',`data-request-id="${e(state.requestId)}"${state.canceling||state.closing?' disabled':''}`):''}${command?.status==='FINALIZING'?'<p class="hint">Laptop đang lưu mẫu. Không thể hủy ở bước này.</p>':''}${canCancel(command)?'<p class="hint">Bấm X hoặc Đóng sẽ gửi yêu cầu hủy và dừng lấy mẫu.</p>':ACTIVE.has(command?.status)&&command?.cancelRequested?'<p class="hint">Đã gửi yêu cầu hủy; chờ laptop xác nhận.</p>':''}`;
}

export function createFaceEnrollmentActions(ctx,{documentImpl=globalThis.document,now=()=>Date.now(),previewFactory=createEnrollmentCameraPreview,beforePreviewStart=()=>{}}={}){
  let generation=0,state=null,stops=[],closeTarget=null,closeHandler=null,lastHTML='';
  let camera=null,cameraRoot=null,cameraStation=null,cameraActive=false;
  function disposeCamera(){camera?.dispose();camera=null;cameraRoot=null;cameraStation=null;cameraActive=false;}
  function dispose(){generation++;disposeCamera();stops.forEach(stop=>stop());stops=[];if(closeTarget&&closeHandler)closeTarget.removeEventListener('close',closeHandler);closeTarget=null;closeHandler=null;state=null;lastHTML='';}
  function refreshCamera(){
    const command=state?.command,element=documentImpl?.querySelector('#face-enrollment-camera');
    if(!element||element.dataset.requestId!==state?.requestId||command?.action!=='ENROLL'||!validId(command.stationId)){disposeCamera();return;}
    if(element!==cameraRoot||command.stationId!==cameraStation){
      disposeCamera();cameraRoot=element;cameraStation=command.stationId;
      camera=previewFactory(element,{documentImpl,expectedStationId:cameraStation,onStart:beforePreviewStart});
    }
    camera?.observeCapture?.(command,state.requestId);
    const active=['PENDING','CAPTURING'].includes(command.status)&&command.cancelRequested!==true;
    if(active&&!cameraActive){cameraActive=true;void camera.start();}
    else if(!active&&cameraActive){cameraActive=false;camera.stop('Đã dừng xem camera; xem kết quả đăng ký bên cạnh.');}
  }
  function refresh(){
    if(!state)return;
    const target=documentImpl?.querySelector('#face-enrollment-progress');
    if(target?.dataset?.requestId!==state.requestId){dispose();return;}
    const html=progressBody(state);if(html!==lastHTML){target.innerHTML=html;lastHTML=html;}
    refreshCamera();
  }
  function requestCancellation(actionCtx=ctx){
    const tracked=state,current=generation,store=ctx.store,uid=ctx.user?.uid;
    requireValue(tracked,'Cửa sổ đăng ký đã thay đổi. Mở lại đúng yêu cầu.');
    if(tracked.cancelPromise)return tracked.cancelPromise;
    tracked.canceling=true;tracked.error='';refresh();
    const valid=()=>generation===current&&state===tracked&&ctx.store===store&&ctx.user?.uid===uid&&!ctx.demo;
    const task=(async()=>{
      // A click that is already being submitted finishes before cancellation;
      // closing immediately disables any subsequent capture clicks.
      if(tracked.capturePromise)try{await tracked.capturePromise;}catch{}
      requireValue(valid(),'Phiên đăng nhập hoặc cửa sổ đăng ký đã thay đổi.');
      const command=await cancelFaceEnrollment(actionCtx,tracked.requestId,{allowFinished:true});
      if(valid()){
        const ended=TERMINAL.has(tracked.command?.status)||tracked.command?.status==='FINALIZING';
        if(!ended||TERMINAL.has(command.status)||command.status==='FINALIZING')tracked.command=command;
      }
      return valid()?tracked.command:command;
    })();
    tracked.cancelPromise=task;
    void task.catch(error=>{if(valid())tracked.error=errorMessage(error);}).finally(()=>{if(valid()){tracked.canceling=false;tracked.cancelPromise=null;refresh();}});
    return task;
  }
  function track(requestId,employee,actionCtx,initialCommand=null){
    dispose();const current=generation,store=ctx.store,uid=ctx.user?.uid;
    state={requestId,employeeId:employee.id,employeeName:employee.fullName||employee.id,command:initialCommand,profile:null,error:'',listenerErrors:{},canceling:false,capturing:false,closing:false,cancelPromise:null,capturePromise:null};
    const valid=()=>generation===current&&ctx.store===store&&ctx.user?.uid===uid&&!ctx.demo;
    const enrollment=initialCommand?.action==='ENROLL';
    actionCtx.ui.dialog({title:'Yêu cầu khuôn mặt',submitLabel:null,wide:enrollment,body:`<div class="${enrollment?'face-enrollment-layout':''}">${enrollment?`<section id="face-enrollment-camera" data-request-id="${e(requestId)}">${renderEnrollmentCameraPreview()}</section>`:''}<div id="face-enrollment-progress" data-request-id="${e(requestId)}">${progressBody(state)}</div></div>`,onBeforeClose:async()=>{
      if(!valid())return false;
      const tracked=state;
      if(!tracked.cancelPromise&&(TERMINAL.has(tracked.command?.status)||tracked.command?.status==='FINALIZING'||(ACTIVE.has(tracked.command?.status)&&tracked.command?.cancelRequested===true))){
        if(tracked.command.status==='FINALIZING')ctx.ui.toast('Laptop đang lưu mẫu; không thể hủy ở bước này. Việc lưu mẫu sẽ tiếp tục.');
        return true;
      }
      tracked.closing=true;refresh();
      try{
        const command=await requestCancellation(ctx);
        if(!valid()||state!==tracked)return false;
        if(command.status==='FINALIZING')ctx.ui.toast('Laptop đang lưu mẫu; không thể hủy ở bước này. Việc lưu mẫu sẽ tiếp tục.');
        else if(command.cancelRequested&&ACTIVE.has(command.status))ctx.ui.toast('Đã gửi yêu cầu hủy; chờ laptop xác nhận.');
        return true;
      }catch{return false;}
      finally{if(valid()&&state===tracked){tracked.closing=false;refresh();}}
    },onClose:()=>{if(generation===current)dispose();}});
    if(documentImpl&&documentImpl.querySelector('#face-enrollment-progress')?.dataset?.requestId!==requestId){dispose();return;}
    closeTarget=documentImpl?.querySelector('#modal');
    // close() queues an event. The previous picker can emit it after the new
    // progress dialog has opened, so only detach if this dialog is gone.
    closeHandler=()=>{const target=documentImpl?.querySelector('#face-enrollment-progress');if(!closeTarget?.open||target?.dataset?.requestId!==requestId)dispose();};
    closeTarget?.addEventListener('close',closeHandler);
    refreshCamera();
    if(!store?.listenDoc){state.error='Chưa theo dõi được trạng thái. Kiểm tra kết nối Firebase.';refresh();return;}
    for(const [collection,id,key]of [['faceEnrollmentCommands',requestId,'command'],['faceProfiles',employee.id,'profile']]){
      stops.push(store.listenDoc(collection,id,value=>{if(!valid())return;state[key]=value;delete state.listenerErrors[key];refresh();},error=>{if(!valid())return;state.listenerErrors[key]=`Chưa cập nhật được trạng thái: ${errorMessage(error)}`;refresh();}));
    }
  }
  async function onAction(action,element,actionCtx=ctx){
    if(!['face-enroll','face-delete','face-progress','face-progress-retry','face-command-cancel','face-capture'].includes(action))return false;
    requireRealSession(actionCtx);
    if(action==='face-capture'){
      const requestId=element.dataset.requestId,current=generation;
      requireValue(state?.requestId===requestId,'Cửa sổ đăng ký đã thay đổi. Mở lại đúng yêu cầu.');
      if(state.capturing||state.closing||state.canceling)return true;
      state.capturing=true;state.error='';refresh();
      try{
        const pending=requestFaceCapture(actionCtx,requestId,{now:now()});state.capturePromise=pending;
        const next=await pending;
        if(generation===current&&state?.requestId===requestId&&state.command?.status==='CAPTURING'&&state.command.captureRequestSeq<next)state.command={...state.command,captureRequestSeq:next};
      }catch(error){if(generation===current&&state?.requestId===requestId)state.error=errorMessage(error);throw error;}
      finally{if(generation===current&&state?.requestId===requestId){state.capturing=false;state.capturePromise=null;refresh();}}
      return true;
    }
    if(action==='face-progress-retry'){
      requireValue(state?.requestId===element.dataset.requestId,'Cửa sổ theo dõi đã thay đổi. Mở lại yêu cầu từ hồ sơ nhân viên.');
      const current=state;track(current.requestId,{id:current.employeeId,fullName:current.employeeName},actionCtx,current.command);return true;
    }
    if(action==='face-command-cancel'){
      requireValue(state?.requestId===element.dataset.requestId,'Cửa sổ đăng ký đã thay đổi. Mở lại đúng yêu cầu.');
      await requestCancellation(actionCtx);
      return true;
    }
    const employeeId=element.dataset.id,lookupGeneration=generation,lookupStore=ctx.store,lookupUid=ctx.user?.uid;
    requireValue(validId(employeeId),'Mã nhân viên không hợp lệ');
    // A newly committed employee may not have reached the realtime list yet.
    const employee=actionCtx.data.employees.find(row=>row.id===employeeId)||await actionCtx.store.get('employees',employeeId);
    // An older read must not replace a newer dialog or detach its listeners.
    if(actionCtx.isCurrent?.()===false||generation!==lookupGeneration||ctx.store!==lookupStore||ctx.user?.uid!==lookupUid||ctx.demo)return true;
    requireValue(employee?.id===employeeId,'Không tìm thấy hồ sơ nhân viên');
    if(action==='face-progress'){
      const requestId=element.dataset.requestId;requireValue(validId(requestId),'Yêu cầu không hợp lệ');
      const command=(actionCtx.data.faceEnrollmentCommands||[]).find(row=>(row.id||row.requestId)===requestId&&row.employeeId===employee.id);
      requireValue(command,'Chưa tải được yêu cầu của nhân viên này');track(requestId,employee,actionCtx,command);return true;
    }
    requireValue(employee.active===true,'Nhân viên đã ngừng hoạt động');
    const stations=(actionCtx.data.faceStations||[]).filter(station=>faceStationAvailable(station,now()));
    requireValue(stations.length,'Chưa có trạm camera sẵn sàng. Chạy dịch vụ laptop ở chế độ Firebase và kiểm tra camera.');
    dispose();
    const enrollment=action==='face-enroll',current=generation,store=ctx.store,uid=ctx.user?.uid;
    actionCtx.ui.dialog({title:enrollment?'Đăng ký khuôn mặt':'Xóa mẫu khuôn mặt',submitLabel:enrollment?'Mở camera để chụp 6 ảnh':'Gửi yêu cầu xóa',body:`<p>Nhân viên: <strong>${e(employee.fullName||employee.id)}</strong></p>${select('stationId','Trạm camera',stations.map(station=>({value:station.stationId,label:`${station.stationId} · ${station.sensor||'Camera'}`})),stations[0].stationId)}${enrollment?`<div class="notice" style="margin-top:16px">Lấy 6 ảnh: 2 nhìn thẳng, 2 quay nhẹ sang trái, 2 quay nhẹ sang phải. Trái/phải tính theo nhân viên. Đặt camera ngang tầm mặt và đủ sáng. Chỉ để <strong>${e(employee.fullName||employee.id)}</strong> đứng trong khung hình.</div><label class="checkbox"><input type="checkbox" name="personConfirmed" value="yes" required><span>Tôi đã xác nhận người đứng trước camera đúng là nhân viên này.</span></label>`:'<div class="notice" style="margin-top:16px">Laptop sẽ xóa mẫu nhận diện của nhân viên này. Có thể đăng ký lại sau.</div>'}`,onSubmit:async values=>{
      requireValue(!enrollment||values.personConfirmed==='yes','Cần xác nhận đúng người trước camera');
      const requestId=await requestFaceEnrollment(actionCtx,{employeeId:employee.id,stationId:values.stationId,action:enrollment?'ENROLL':'DELETE',captureMode:enrollment?'MANUAL':'AUTO',capturePlan:enrollment?SIX_POSE_PLAN:undefined,now:now()});
      if(generation!==current||ctx.store!==store||ctx.user?.uid!==uid||ctx.demo)return;
      track(requestId,employee,actionCtx,{requestId,employeeId:employee.id,stationId:values.stationId,action:enrollment?'ENROLL':'DELETE',status:'PENDING',sampleCount:enrollment?6:0,samplesCaptured:0,cancelRequested:false,...(enrollment?{captureMode:'MANUAL',capturePlan:SIX_POSE_PLAN,captureRequestSeq:0,captureConsumedSeq:0}:{})});
    }});
    return true;
  }
  return {onAction,refresh,dispose};
}
