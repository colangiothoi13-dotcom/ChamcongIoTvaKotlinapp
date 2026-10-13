import {readFile} from 'node:fs/promises';

const statuses=new Set(['IDLE','FACE_BLOCKED','FACE_READY','FACE_WAITING','REGISTERING','UNAVAILABLE','STOPPED']);
const issues=new Set(['mask','hat','glasses','accessory_uncertain','accessory_head_out_of_frame']);
const scanStatuses=new Set(['IDLE','CHECKING','NO_MATCH','WAITING','WRITING','PENDING','ACCEPTED','DUPLICATE','REJECTED']);
const attendanceStatuses=new Set(['NONE','PENDING','ACCEPTED','DUPLICATE','REJECTED']);
const stationPattern=/^[A-Za-z0-9_-]{1,128}$/;
const MAX_BYTES=16*1024;
const headers={'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff','Cross-Origin-Resource-Policy':'same-origin','Referrer-Policy':'no-referrer'};
const offline='Chưa nhận được kiểm tra phụ kiện. Mở dịch vụ Trạm khuôn mặt trên laptop; ảnh camera vẫn có thể xem riêng.';
function respond(res,status,value){if(!res.destroyed&&!res.writableEnded){res.writeHead(status,{...headers,'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(value));}}
function validUtc(value){
  if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(value))return false;
  const time=Date.parse(value);return Number.isFinite(time)&&new Date(time).toISOString().slice(0,19)===value.slice(0,19);
}
export function safeFaceStatus(value){
  if(!value||typeof value!=='object'||Array.isArray(value)||!statuses.has(value.status)||typeof value.accessoryGateEnabled!=='boolean'||typeof value.message!=='string'||!value.message.trim()||value.message.length>300||!validUtc(value.updatedAt))throw new Error('INVALID_STATUS');
  const issue=value.qualityIssue;
  if(value.status==='FACE_BLOCKED'?!issues.has(issue):issue!==null)throw new Error('INVALID_STATUS');
  const scan=value.scan===undefined?{status:'IDLE',message:'Đưa một người vào khung hình để quét.',updatedAt:value.updatedAt}:value.scan;
  if(!scan||typeof scan!=='object'||Array.isArray(scan)||!scanStatuses.has(scan.status)||typeof scan.message!=='string'||!scan.message.trim()||scan.message.length>300||!validUtc(scan.updatedAt))throw new Error('INVALID_STATUS');
  const attendance=value.attendance===undefined?{status:'NONE',employeeName:'',message:'Chưa có lượt chấm công khuôn mặt gần đây.',updatedAt:value.updatedAt}:value.attendance;
  if(!attendance||typeof attendance!=='object'||Array.isArray(attendance)||!attendanceStatuses.has(attendance.status)||typeof attendance.employeeName!=='string'||attendance.employeeName.length>120||typeof attendance.message!=='string'||!attendance.message.trim()||attendance.message.length>300||!validUtc(attendance.updatedAt))throw new Error('INVALID_STATUS');
  return {status:value.status,qualityIssue:issue,message:value.message,updatedAt:value.updatedAt,accessoryGateEnabled:value.accessoryGateEnabled,
    scan:{status:scan.status,message:scan.message,updatedAt:scan.updatedAt},
    attendance:{status:attendance.status,employeeName:attendance.employeeName,message:attendance.message,updatedAt:attendance.updatedAt}};
}
async function readBody(response){
  const length=Number(response.headers.get('content-length'));
  if(Number.isFinite(length)&&length>MAX_BYTES){await response.body?.cancel();throw new Error();}
  const reader=response.body?.getReader();if(!reader)throw new Error();
  const chunks=[];let size=0;
  try{while(true){const{done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>MAX_BYTES)throw new Error();chunks.push(Buffer.from(value));}}
  catch(error){await reader.cancel().catch(()=>{});throw error;}
  finally{reader.releaseLock();}
  return new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks,size));
}

// The browser can only read this laptop's configured station. Camera images
// and face-status polling have independent upstream requests and lifecycles.
export function createFaceStatusProxy({configPath,readConfig=readFile,fetchImpl=fetch,authorizeRequest,timeoutMs=6000}={}){
  return async(req,res,url)=>{
    if(req.method!=='GET')return respond(res,405,{error:'METHOD_NOT_ALLOWED'});
    if(!authorizeRequest?.(req))return respond(res,403,{error:'PREVIEW_FORBIDDEN',message:'Mở camera từ web trên localhost của laptop.'});
    if(url.pathname!=='/api/camera/face-status'||url.search)return respond(res,400,{error:'INVALID_REQUEST'});
    let config;
    try{
      config=JSON.parse(await readConfig(configPath,'utf8'));
      if(typeof config.stationId!=='string'||!stationPattern.test(config.stationId)||!Number.isInteger(config.port)||config.port<1||config.port>65535||typeof config.adminToken!=='string'||config.adminToken.length<24||config.adminToken.length>512||!config.adminToken.trim()||config.adminToken.includes('REPLACE')||/[\r\n]/.test(config.adminToken))throw new Error();
    }catch{return respond(res,503,{error:'STATION_CONFIG',message:'Chưa có cấu hình kiểm tra phụ kiện hợp lệ trên laptop.'});}
    const expected=req.headers['x-camera-station'];
    if(typeof expected!=='string'||!stationPattern.test(expected)||expected!==config.stationId)return respond(res,409,{error:'CAMERA_STATION',message:'Trạm kiểm tra không khớp với camera đang xem.'});
    const controller=new AbortController(),abort=()=>controller.abort(),closed=()=>{if(!res.writableEnded)abort();};
    req.once('aborted',abort);res.once('close',closed);
    const timer=setTimeout(abort,timeoutMs);
    try{
      const response=await fetchImpl(`http://127.0.0.1:${config.port}/v1/face-status`,{method:'GET',headers:{'X-Admin-Token':config.adminToken,Accept:'application/json'},redirect:'error',signal:controller.signal});
      if(!response.ok||response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='application/json'){await response.body?.cancel();throw new Error();}
      const result=safeFaceStatus(JSON.parse(await readBody(response)));
      if(controller.signal.aborted||res.destroyed)return;
      respond(res,200,result);
    }catch{if(!res.destroyed&&!req.aborted)respond(res,503,{error:'FACE_STATUS_UNAVAILABLE',message:offline});}
    finally{clearTimeout(timer);req.removeListener('aborted',abort);res.removeListener('close',closed);}
  };
}
