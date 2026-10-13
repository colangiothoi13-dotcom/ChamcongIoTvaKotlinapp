import {mkdir,cp,access} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {ensureConfig} from './config.mjs';
const root=fileURLToPath(new URL('..',import.meta.url));await ensureConfig(root);const dist=path.join(root,'dist');await mkdir(dist,{recursive:true});
for(const file of ['index.html','styles.css','favicon.svg','firebase-config.js','src']){await access(path.join(root,file));await cp(path.join(root,file),path.join(dist,file),{recursive:true});}
console.log('Đã tạo web-admin/dist để chạy trên Firebase Hosting hoặc máy chủ static.');
