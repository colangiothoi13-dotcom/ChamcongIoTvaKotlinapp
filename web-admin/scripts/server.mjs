import http from 'node:http';
import {readFile,stat} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {ensureConfig} from './config.mjs';
import {createCameraProxy,isLocalPreviewRequest} from './camera-proxy.mjs';
import {mobileCameraServer} from './mobile-camera.mjs';
const root=path.resolve(fileURLToPath(new URL('..',import.meta.url)));await ensureConfig(root);
const port=Number(process.env.PORT||4173);const allowed=['index.html','styles.css','favicon.svg','firebase-config.js'];
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml'};
// The browser cannot choose the configuration path or the upstream URL.
const authorizedRequests=new WeakSet();
const cameraProxy=createCameraProxy({configPath:path.join(root,'..','services','face_attendance','config.json'),authorizeRequest:req=>authorizedRequests.has(req)||isLocalPreviewRequest(req)});
const server=http.createServer(async(req,res)=>{try{const url=new URL(req.url,'http://localhost');const pathname=decodeURIComponent(url.pathname);if(pathname.startsWith('/api/camera/'))return await cameraProxy(req,res,url);const resolved=path.resolve(root,pathname==='/'?'index.html':pathname.slice(1));const relative=path.relative(root,resolved).replaceAll(path.sep,'/');if(!resolved.startsWith(root+path.sep)||(!allowed.includes(relative)&&!relative.startsWith('src/'))){res.writeHead(404);return res.end('Not found');}const info=await stat(resolved);if(!info.isFile())throw new Error('not file');res.writeHead(200,{'Content-Type':mime[path.extname(resolved)]||'application/octet-stream','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'strict-origin-when-cross-origin'});res.end(await readFile(resolved));}catch{if(!res.headersSent){res.writeHead(404);res.end('Not found');}else res.destroy();}});
server.listen(port,'127.0.0.1',()=>console.log(`Web admin: http://localhost:${server.address().port}`));
server.on('error',error=>{console.error(error.code==='EADDRINUSE'?`Cổng ${port} đang được dùng. Đặt PORT để chọn cổng khác.`:error.message);process.exitCode=1;});
if(process.env.WEB_ADMIN_MOBILE_CAMERA==='1'){
  try{
    const {firebaseConfig}=await import(pathToFileURL(path.join(root,'firebase-config.js')).href);
    if(!firebaseConfig.apiKey||!firebaseConfig.projectId)throw new Error('Thiếu cấu hình Firebase client.');
    const mobilePort=Number(process.env.WEB_ADMIN_MOBILE_PORT||4174);
    if(!Number.isInteger(mobilePort)||mobilePort<1||mobilePort>65535)throw new Error('Cổng camera app không hợp lệ.');
    const mobile=await mobileCameraServer({directory:path.join(root,'.camera-mobile'),config:firebaseConfig,proxy:cameraProxy,authorizedRequests,port:mobilePort});
    for(const address of mobile.addresses)console.log(`Camera trong app: https://${address}:${mobilePort}`);
    console.log(`Mã xác minh laptop (SHA-256): ${mobile.fingerprint}`);
  }catch(error){console.error(`Chưa mở được camera cho app: ${error.message}`);process.exitCode=1;}
}
