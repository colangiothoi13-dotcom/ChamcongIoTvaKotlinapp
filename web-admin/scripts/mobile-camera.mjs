// Optional HTTPS bridge for the Android admin app. Camera credentials stay here.
import https from 'node:https';
import {createHash,createPrivateKey,generateKeyPairSync,randomBytes,sign,X509Certificate} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {networkInterfaces} from 'node:os';
import path from 'node:path';

export function privateAddress(host){
  if(typeof host!=='string'||!host.split('.').every(part=>/^(?:0|[1-9]\d{0,2})$/.test(part)))return false;
  const parts=host.split('.').map(Number);
  return parts.length===4&&parts.every(part=>part<=255)&&(host==='127.0.0.1'||parts[0]===10||parts[0]===192&&parts[1]===168||parts[0]===172&&parts[1]>=16&&parts[1]<=31);
}
export function laptopAddresses(){return [...new Set(Object.values(networkInterfaces()).flat().filter(row=>row?.family==='IPv4'&&!row.internal&&privateAddress(row.address)).map(row=>row.address))];}
const der=(tag,...parts)=>{const body=Buffer.concat(parts.map(part=>Buffer.from(part)));const length=body.length<128?Buffer.from([body.length]):(()=>{const bytes=[];let n=body.length;while(n){bytes.unshift(n&255);n>>>=8;}return Buffer.from([0x80|bytes.length,...bytes]);})();return Buffer.concat([Buffer.from([tag]),length,body]);};
const seq=(...parts)=>der(0x30,...parts),oid=hex=>der(6,Buffer.from(hex,'hex'));
const algorithm=seq(oid('2a864886f70d01010b'),der(5));
const pem=(label,bytes)=>`-----BEGIN ${label}-----\n${bytes.toString('base64').match(/.{1,64}/g).join('\n')}\n-----END ${label}-----\n`;
const utc=date=>der(0x17,Buffer.from(date.toISOString().replace(/[-:T]/g,'').slice(2,14)+'Z'));
export function generatePinnedCertificate(addresses=['127.0.0.1'],now=new Date()){
  const keys=generateKeyPairSync('rsa',{modulusLength:2048});
  const name=seq(der(0x31,seq(oid('550403'),der(0x0c,Buffer.from('Cham cong IoT laptop')))));
  const names=seq(der(0x82,Buffer.from('localhost')),...[...new Set(['127.0.0.1',...addresses])].map(address=>{if(!privateAddress(address)||address.split('.').some(part=>Number(part)>255))throw new Error('Invalid laptop address');return der(0x87,Buffer.from(address.split('.').map(Number)));}));
  const extensions=der(0xa3,seq(seq(oid('551d13'),der(1,[255]),der(4,seq())),seq(oid('551d11'),der(4,names))));
  const serial=randomBytes(16);serial[0]=(serial[0]&127)||1;
  const tbs=seq(der(0xa0,der(2,[2])),der(2,serial),algorithm,name,seq(utc(new Date(now.getTime()-86400000)),utc(new Date(now.getTime()+365*86400000))),name,keys.publicKey.export({type:'spki',format:'der'}),extensions);
  const certificate=seq(tbs,algorithm,der(3,Buffer.from([0]),sign('RSA-SHA256',tbs,keys.privateKey)));
  return {cert:pem('CERTIFICATE',certificate),key:keys.privateKey.export({type:'pkcs8',format:'pem'})};
}
export async function pinnedCertificate(directory,addresses){
  let credentials;
  try{
    credentials={cert:await readFile(path.join(directory,'cert.pem'),'utf8'),key:await readFile(path.join(directory,'key.pem'),'utf8')};
    const certificate=new X509Certificate(credentials.cert);
    const from=Date.parse(certificate.validFrom),to=Date.parse(certificate.validTo),now=Date.now();
    if(!certificate.checkPrivateKey(createPrivateKey(credentials.key))||!Number.isFinite(from)||!Number.isFinite(to)||from>now||to<now||addresses.some(address=>!certificate.checkIP(address)))credentials=null;
  }catch{credentials=null;}
  if(!credentials){credentials=generatePinnedCertificate(addresses);await mkdir(directory,{recursive:true});await writeFile(path.join(directory,'key.pem'),credentials.key,{mode:0o600});await writeFile(path.join(directory,'cert.pem'),credentials.cert);}
  return {...credentials,fingerprint:new X509Certificate(credentials.cert).fingerprint256};
}
const reply=(res,status,message)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify({message}));};
export function nativePreviewRequest(req,addresses){
  const hosts=new Set(['localhost','127.0.0.1',...addresses].map(address=>`${address}:${req.socket.localPort}`));
  return req.socket.encrypted===true&&hosts.has(req.headers.host?.toLowerCase())&&req.headers['x-camera-preview']==='1'&&!req.headers.origin&&!req.headers['sec-fetch-site'];
}
async function smallJson(response){
  const reader=response.body?.getReader();if(!reader)throw new Error('AUTH_UNAVAILABLE');let size=0;const chunks=[];
  try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>65536)throw new Error('AUTH_UNAVAILABLE');chunks.push(Buffer.from(value));}return JSON.parse(Buffer.concat(chunks).toString('utf8'));}finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
export function createAdminVerifier(config,{fetchImpl=fetch,now=Date.now,ttlMs=15000}={}){
  const verified=new Map(),pending=new Map();
  return async authorization=>{
    if(!config?.apiKey||!config.projectId)throw new Error('AUTH_CONFIG');
    const match=typeof authorization==='string'&&authorization.match(/^Bearer ([A-Za-z0-9_.-]{30,8192})$/);if(!match)throw new Error('AUTH_REQUIRED');
    const token=match[1],key=createHash('sha256').update(token).digest('hex');
    if((verified.get(key)||0)>now())return true;
    if(pending.has(key))return pending.get(key);
    if(pending.size>=10)throw new Error('AUTH_UNAVAILABLE');
    const check=(async()=>{
      const signal=AbortSignal.timeout(5000);
      const auth=await fetchImpl(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(config.apiKey)}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:token}),redirect:'error',signal});
      if(!auth.ok){await auth.body?.cancel();throw new Error(auth.status>=500?'AUTH_UNAVAILABLE':'AUTH_REQUIRED');}
      const account=(await smallJson(auth)).users?.[0];
      if(typeof account?.localId!=='string'||!account.localId.trim()||account.localId.length>128||/[\x00-\x1f/\\]/.test(account.localId)||account.disabled||!Array.isArray(account.providerUserInfo)||!account.providerUserInfo.length||!account.providerUserInfo.every(provider=>typeof provider?.providerId==='string'&&provider.providerId.trim()&&provider.providerId!=='anonymous'))throw new Error('ADMIN_REQUIRED');
      let claims;
      try{if(token.split('.').length!==3)throw new Error();claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString('utf8'));}catch{throw new Error('AUTH_REQUIRED');}
      if(!claims||typeof claims!=='object'||Array.isArray(claims))throw new Error('AUTH_REQUIRED');
      if(claims.aud!==config.projectId||claims.iss!==`https://securetoken.google.com/${config.projectId}`||claims.sub!==account.localId||!Number.isFinite(claims.exp)||claims.exp*1000<=now()||typeof claims.firebase?.sign_in_provider!=='string'||!claims.firebase.sign_in_provider.trim()||claims.firebase.sign_in_provider==='anonymous')throw new Error('ADMIN_REQUIRED');
      const profile=await fetchImpl(`https://firestore.googleapis.com/v1/projects/${encodeURIComponent(config.projectId)}/databases/(default)/documents/users/${encodeURIComponent(account.localId)}`,{headers:{Authorization:`Bearer ${token}`},redirect:'error',signal});
      if(!profile.ok){await profile.body?.cancel();throw new Error(profile.status>=500?'AUTH_UNAVAILABLE':'ADMIN_REQUIRED');}
      const fields=(await smallJson(profile)).fields;
      if(fields?.role?.stringValue!=='ADMIN'||fields?.active?.booleanValue!==true)throw new Error('ADMIN_REQUIRED');
      if(verified.size>=100)verified.delete(verified.keys().next().value);
      verified.set(key,Math.min(now()+ttlMs,claims.exp*1000));return true;
    })().catch(error=>{if(['AUTH_REQUIRED','ADMIN_REQUIRED','AUTH_CONFIG'].includes(error.message))throw error;throw new Error('AUTH_UNAVAILABLE');}).finally(()=>pending.delete(key));
    pending.set(key,check);return check;
  };
}
export async function mobileCameraServer({directory,addresses=laptopAddresses(),config,proxy,authorizedRequests,port=4174,fetchImpl=fetch}){
  const credentials=await pinnedCertificate(directory,addresses),verify=createAdminVerifier(config,{fetchImpl});
  const server=https.createServer({...credentials,minVersion:'TLSv1.2'},async(req,res)=>{
    try{
      const url=new URL(req.url,'https://localhost');
      if(req.method!=='GET'||!['/api/camera/health','/api/camera/frame'].includes(url.pathname)||url.search||!nativePreviewRequest(req,addresses))return reply(res,403,'Kết nối xem camera không hợp lệ.');
      await verify(req.headers.authorization);if(req.aborted||res.destroyed)return;
      authorizedRequests.add(req);await proxy(req,res,url);
    }catch(error){
      if(res.destroyed||res.headersSent)return;
      const status=error.message==='AUTH_REQUIRED'?401:error.message==='ADMIN_REQUIRED'?403:503;
      reply(res,status,status===401?'Đăng nhập lại tài khoản Admin rồi thử lại.':status===403?'Chỉ tài khoản Admin đang hoạt động được xem camera.':'Chưa xác thực được tài khoản. Kiểm tra Internet và cấu hình Firebase trên laptop.');
    }
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'0.0.0.0',resolve);});
  return {server,fingerprint:credentials.fingerprint,addresses};
}
