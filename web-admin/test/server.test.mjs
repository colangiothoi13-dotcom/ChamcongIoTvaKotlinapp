import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
test('local server serves the UI while refusing project files and traversal paths',async()=>{
  const server=spawn(process.execPath,[fileURLToPath(new URL('../scripts/server.mjs',import.meta.url))],{env:{...process.env,PORT:'0'},stdio:['ignore','pipe','pipe'],windowsHide:true});
  try{
    const port=await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('Local server did not start.')),5000);server.once('error',reject);server.stdout.on('data',chunk=>{const match=chunk.toString().match(/localhost:(\d+)/);if(match){clearTimeout(timeout);resolve(match[1]);}});server.once('exit',code=>{clearTimeout(timeout);reject(new Error(`Local server exited: ${code}`));});});
    const url=`http://127.0.0.1:${port}`;
    const index=await fetch(url);assert.equal(index.status,200);assert.match(index.headers.get('content-type'),/text\/html.*utf-8/);assert.match(await index.text(),/src\/app.js/);
    const module=await fetch(`${url}/src/app.js`);assert.equal(module.status,200);assert.match(module.headers.get('content-type'),/javascript/);
    for(const route of ['/package.json','/scripts/config.mjs','/scripts/camera-proxy.mjs','/test/interface.test.js','/services/face_attendance/config.json','/src/%2e%2e%5cpackage.json','/%2e%2e%5capp%5cgoogle-services.json','/src/%2e%2e%5c%2e%2e%5capp%5cgoogle-services.json'])assert.equal((await fetch(`${url}${route}`)).status,404,route);
    // No unauthenticated image or cross-site request reaches the hardware.
    assert.equal((await fetch(`${url}/api/camera/frame`)).status,403);
    assert.equal((await fetch(`${url}/api/camera/health`,{headers:{'X-Camera-Preview':'1',Origin:'https://foreign.example'}})).status,403);
  }finally{server.kill();}
});
