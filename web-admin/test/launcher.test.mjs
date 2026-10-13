import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('Windows double-click launcher starts the server from a path containing spaces and parentheses', {skip:process.platform!=='win32'},async()=>{
  const directory=fileURLToPath(new URL('..',import.meta.url));
  // Bound only the test's server lifetime, without Windows process-tree permissions.
  const timeoutImport='data:text/javascript,'+encodeURIComponent('setTimeout(() => process.exit(0), 3000).unref();');
  const nodeOptions=[process.env.NODE_OPTIONS,`--import=${timeoutImport}`].filter(Boolean).join(' ');
  const child=spawn(process.env.ComSpec||'cmd.exe',['/d','/c','mo-web-admin.cmd'],{cwd:directory,env:{...process.env,PORT:'0',NODE_OPTIONS:nodeOptions},stdio:['ignore','pipe','pipe'],windowsHide:true});
  const closed=new Promise(resolve=>child.once('close',resolve));
  let port;
  try{
    port=await new Promise((resolve,reject)=>{let output='';const timeout=setTimeout(()=>reject(new Error('Windows launcher did not start its server.')),5000);child.once('error',reject);child.stdout.on('data',chunk=>{output+=chunk.toString('utf8');const match=output.match(/Web admin: http:\/\/localhost:(\d+)/);if(match){clearTimeout(timeout);resolve(Number(match[1]));}});child.once('exit',code=>{clearTimeout(timeout);reject(new Error(`Windows launcher exited before serving the web (code ${code}).`));});});
    const response=await fetch(`http://127.0.0.1:${port}/`,{headers:{Connection:'close'}});
    await response.arrayBuffer();
    assert.equal(response.status,200);
  }finally{
    await closed;
  }
});
