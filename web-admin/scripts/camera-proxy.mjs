import {readFile} from 'node:fs/promises';
import {createEnrollmentPreviewProxy} from './enrollment-preview-proxy.mjs';

const MAX_FRAME_BYTES=5*1024*1024;
const MAX_HEALTH_BYTES=16*1024;
const REQUEST_TIMEOUT_MS=6000;
const stationIdPattern=/^[A-Za-z0-9_-]{1,128}$/;
const validStationId=value=>typeof value==='string'&&stationIdPattern.test(value);
const responseHeaders={'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff','Cross-Origin-Resource-Policy':'same-origin','Referrer-Policy':'no-referrer'};
const errors={
  CAMERA_CONFIG:'Chưa có cấu hình camera hợp lệ trên laptop.',
  CAMERA_AUTH:'Token camera chưa khớp. Kiểm tra cấu hình trên laptop và firmware.',
  CAMERA_OFFLINE:'Chưa liên lạc được camera. Kiểm tra nguồn, IP và Wi-Fi rồi thử lại.',
  CAMERA_TIMEOUT:'Camera trả lời quá chậm. Kiểm tra Wi-Fi rồi thử lại.',
  CAMERA_INVALID:'Camera chưa trả về ảnh JPEG hoặc trạng thái hợp lệ.',
  CAMERA_BUSY:'Camera đang phục vụ một yêu cầu khác. Hãy thử lại sau vài giây.',
  CAMERA_STATION:'Camera trên laptop này không thuộc trạm đăng ký đã chọn.'
};

function json(res,status,value){
  if(res.destroyed||res.writableEnded)return;
  res.writeHead(status,{...responseHeaders,'Content-Type':'application/json; charset=utf-8'});
  res.end(JSON.stringify(value));
}

// A foreign site cannot read frames through an <img>, a form or a CORS request.
export function isLocalPreviewRequest(req){
  const port=req.socket.localPort;
  const hosts=new Set([`localhost:${port}`,`127.0.0.1:${port}`]);
  const host=req.headers.host?.toLowerCase();
  if(!hosts.has(host)||req.headers['x-camera-preview']!=='1')return false;
  if(req.headers.origin&&req.headers.origin!==`http://${host}`)return false;
  if(req.headers['sec-fetch-site']&&!['same-origin','none'].includes(req.headers['sec-fetch-site']))return false;
  return true;
}

async function limitedBody(response,limit){
  const length=Number(response.headers.get('content-length'));
  if(Number.isFinite(length)&&length>limit)throw new Error('CAMERA_INVALID');
  const reader=response.body?.getReader();if(!reader)throw new Error('CAMERA_INVALID');
  const chunks=[];let size=0;
  try{
    while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit)throw new Error('CAMERA_INVALID');chunks.push(Buffer.from(value));}
    return Buffer.concat(chunks,size);
  }catch(error){await reader.cancel().catch(()=>{});throw error;}
  finally{reader.releaseLock();}
}

async function cameraConfig(configPath,readConfig){
  try{
    const config=JSON.parse(await readConfig(configPath,'utf8'));
    const camera=config.camera;
    const url=new URL(camera?.url);
    if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.search||url.hash||url.pathname!=='/capture'||typeof camera.token!=='string'||!camera.token.trim()||camera.token.length>512||/[\r\n]/.test(camera.token))throw new Error();
    const rotation=camera.rotation===undefined?0:camera.rotation;
    if(!Number.isInteger(rotation)||![0,90,180,270].includes(rotation))throw new Error();
    return {url,token:camera.token,stationId:validStationId(config.stationId)?config.stationId:undefined,rotation};
  }catch{throw new Error('CAMERA_CONFIG');}
}

function safeHealth(value){
  if(!value||typeof value!=='object'||Array.isArray(value)||typeof value.cameraReady!=='boolean'||typeof value.wifiConnected!=='boolean')throw new Error('CAMERA_INVALID');
  const result={cameraReady:value.cameraReady,wifiConnected:value.wifiConnected};
  for(const key of ['board','sensor','deviceId'])if(typeof value[key]==='string')result[key]=value[key].slice(0,100);
  for(const key of ['sensorPid','psramBytes','flashBytes','cameraError'])if(Number.isSafeInteger(value[key])&&value[key]>=0)result[key]=value[key];
  if(typeof value.psram==='boolean')result.psram=value.psram;
  return result;
}

export function createCameraProxy({configPath,fetchImpl=fetch,readConfig=readFile,timeoutMs=REQUEST_TIMEOUT_MS,authorizeRequest=isLocalPreviewRequest}={}){
  let busy=false;
  const enrollmentPreview=createEnrollmentPreviewProxy({configPath,fetchImpl,readConfig,timeoutMs,authorizeRequest});
  return async(req,res,url)=>{
    if(url.pathname.startsWith('/api/camera/enrollment-preview/'))return enrollmentPreview(req,res,url);
    if(!['/api/camera/frame','/api/camera/health'].includes(url.pathname))return json(res,404,{error:'NOT_FOUND'});
    if(req.method!=='GET')return json(res,405,{error:'METHOD_NOT_ALLOWED'});
    if(!authorizeRequest(req))return json(res,403,{error:'PREVIEW_FORBIDDEN',message:'Mở camera từ web trên localhost của laptop.'});
    if(url.search)return json(res,400,{error:'INVALID_REQUEST'});
    if(busy)return json(res,429,{error:'CAMERA_BUSY',message:errors.CAMERA_BUSY});
    busy=true;
    const controller=new AbortController();
    const abort=()=>controller.abort();
    const closed=()=>{if(!res.writableEnded)abort();};
    req.once('aborted',abort);res.once('close',closed);
    let timer,timedOut=false;
    try{
      const config=await cameraConfig(configPath,readConfig);
      if(controller.signal.aborted)return;
      const expectedStationId=req.headers['x-camera-station'];
      // Check each snapshot against current local configuration, so changing
      // stations after health was read cannot show a different camera.
      if(expectedStationId!==undefined&&(!validStationId(expectedStationId)||expectedStationId!==config.stationId))throw new Error('CAMERA_STATION');
      const health=url.pathname.endsWith('/health');
      const target=new URL(config.url);if(health)target.pathname='/health';
      timer=setTimeout(()=>{timedOut=true;controller.abort();},timeoutMs);
      const upstream=await fetchImpl(target,{method:'GET',headers:{'X-Camera-Token':config.token,'Accept':health?'application/json':'image/jpeg'},redirect:'error',signal:controller.signal});
      if([401,403].includes(upstream.status)){await upstream.body?.cancel();throw new Error('CAMERA_AUTH');}
      if(!upstream.ok){await upstream.body?.cancel();throw new Error('CAMERA_OFFLINE');}
      const type=upstream.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
      if(type!==(health?'application/json':'image/jpeg')){await upstream.body?.cancel();throw new Error('CAMERA_INVALID');}
      const body=await limitedBody(upstream,health?MAX_HEALTH_BYTES:MAX_FRAME_BYTES);
      if(controller.signal.aborted||res.destroyed)return;
      if(health){let value;try{value=JSON.parse(body.toString('utf8'));}catch{throw new Error('CAMERA_INVALID');}const result=safeHealth(value);if(config.stationId)result.stationId=config.stationId;result.rotation=config.rotation;json(res,200,result);}
      else{
        if(body.length<4||body[0]!==0xff||body[1]!==0xd8||body.at(-2)!==0xff||body.at(-1)!==0xd9)throw new Error('CAMERA_INVALID');
        res.writeHead(200,{...responseHeaders,'Content-Type':'image/jpeg','Content-Length':body.length,'X-Camera-Rotation':String(config.rotation)});res.end(body);
      }
    }catch(error){
      if(res.destroyed||req.aborted)return;
      const code=timedOut?'CAMERA_TIMEOUT':Object.hasOwn(errors,error.message)?error.message:'CAMERA_OFFLINE';
      json(res,code==='CAMERA_CONFIG'?503:code==='CAMERA_TIMEOUT'?504:code==='CAMERA_STATION'?409:502,{error:code,message:errors[code]});
    }finally{clearTimeout(timer);req.removeListener('aborted',abort);res.removeListener('close',closed);busy=false;}
  };
}
