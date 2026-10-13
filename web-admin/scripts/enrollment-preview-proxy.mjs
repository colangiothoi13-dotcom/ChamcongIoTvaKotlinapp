import {readFile} from 'node:fs/promises';

const MAX_BYTES=5*1024*1024;
const routePattern=/^\/api\/camera\/enrollment-preview\/([A-Za-z0-9_-]{1,128})\/([1-9][0-9]?|100)$/;
const stationPattern=/^[A-Za-z0-9_-]{1,128}$/;
const reasons=new Set(['quality_ok','no_face','multiple','low_quality','invalid_image','camera_error']);
const headers={'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff','Cross-Origin-Resource-Policy':'same-origin','Referrer-Policy':'no-referrer'};
function fail(res,status,error,message){if(!res.destroyed&&!res.writableEnded){res.writeHead(status,{...headers,'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({error,message}));}}

// Only the laptop's fixed station endpoint is used. Neither a browser URL nor
// an employee/photo filename can select the upstream or expose the Admin token.
export function createEnrollmentPreviewProxy({configPath,readConfig=readFile,fetchImpl=fetch,authorizeRequest,timeoutMs=6000}={}){
  return async(req,res,url)=>{
    if(req.method!=='GET')return fail(res,405,'METHOD_NOT_ALLOWED','Chỉ hỗ trợ xem ảnh vừa chụp.');
    if(!authorizeRequest?.(req))return fail(res,403,'PREVIEW_FORBIDDEN','Mở camera từ web trên localhost của laptop.');
    const match=routePattern.exec(url.pathname);
    if(!match||url.search)return fail(res,400,'INVALID_REQUEST','Yêu cầu xem ảnh không hợp lệ.');
    const[,requestId,sequence]=match;
    let config;
    try{
      config=JSON.parse(await readConfig(configPath,'utf8'));
      if(!stationPattern.test(config.stationId)||!Number.isInteger(config.port)||config.port<1||config.port>65535||typeof config.adminToken!=='string'||config.adminToken.length<24||config.adminToken.length>512||config.adminToken.includes('REPLACE')||/[\r\n]/.test(config.adminToken))throw new Error();
    }catch{return fail(res,503,'STATION_CONFIG','Chưa có cấu hình trạm laptop hợp lệ.');}
    if(!stationPattern.test(req.headers['x-camera-station']||'')||req.headers['x-camera-station']!==config.stationId)return fail(res,409,'CAMERA_STATION','Trạm trên laptop không thuộc yêu cầu đang đăng ký.');
    const controller=new AbortController();
    const abort=()=>controller.abort(),closed=()=>{if(!res.writableEnded)abort();};
    req.once('aborted',abort);res.once('close',closed);
    const timer=setTimeout(abort,timeoutMs);
    try{
      const response=await fetchImpl(`http://127.0.0.1:${config.port}/v1/enrollment-preview/${requestId}/${sequence}`,{method:'GET',headers:{'X-Admin-Token':config.adminToken,Accept:'image/jpeg'},redirect:'error',signal:controller.signal});
      if(response.status===404){await response.body?.cancel();return fail(res,404,'CAPTURE_PREVIEW_MISSING','Ảnh lần chụp này chưa có hoặc đã hết hạn. Bấm Chụp ảnh để lấy ảnh mới.');}
      if(!response.ok){await response.body?.cancel();throw new Error();}
      const reason=response.headers.get('X-Capture-Reason'),saved=response.headers.get('X-Capture-Saved'),total=response.headers.get('X-Capture-Total')??'10';
      if(response.headers.get('content-type')?.split(';')[0].trim()!=='image/jpeg'||response.headers.get('X-Enrollment-Request')!==requestId||response.headers.get('X-Capture-Sequence')!==sequence||!reasons.has(reason)||!/^(6|10)$/.test(total)||!/^(0|[1-9]|10)$/.test(saved||'')||Number(saved)>Number(total)){
        await response.body?.cancel();throw new Error();
      }
      const length=Number(response.headers.get('content-length'));
      if(Number.isFinite(length)&&length>MAX_BYTES){await response.body?.cancel();throw new Error();}
      const reader=response.body?.getReader();if(!reader)throw new Error();
      const chunks=[];let size=0;
      try{while(true){const{done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>MAX_BYTES)throw new Error();chunks.push(Buffer.from(value));}}
      catch(error){await reader.cancel().catch(()=>{});throw error;}
      finally{reader.releaseLock();}
      const body=Buffer.concat(chunks,size);
      if(body.length<4||body[0]!==0xff||body[1]!==0xd8||body.at(-2)!==0xff||body.at(-1)!==0xd9)throw new Error();
      if(controller.signal.aborted||res.destroyed)return;
      // These pixels were rotated before quality inspection and saving.
      res.writeHead(200,{...headers,'Content-Type':'image/jpeg','Content-Length':body.length,'X-Enrollment-Request':requestId,'X-Capture-Sequence':sequence,'X-Capture-Reason':reason,'X-Capture-Saved':saved,'X-Capture-Total':total,'X-Camera-Rotation':'0'});res.end(body);
    }catch{if(!res.destroyed&&!req.aborted)fail(res,502,'CAPTURE_PREVIEW_UNAVAILABLE','Chưa xem được ảnh vừa chụp. Giữ cửa sổ Trạm khuôn mặt chạy rồi thử chụp lại.');}
    finally{clearTimeout(timer);req.removeListener('aborted',abort);res.removeListener('close',closed);}
  };
}
