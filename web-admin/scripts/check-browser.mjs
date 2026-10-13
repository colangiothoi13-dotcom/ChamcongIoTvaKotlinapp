// Isolated real-browser regression checks. Firebase is replaced in this NEW
// browser profile with an in-memory fixture; no user profile or live writes.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,mkdir,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';

const executable=process.env.WEB_BROWSER_EXECUTABLE;
if(!executable)throw new Error('Set WEB_BROWSER_EXECUTABLE to a Chrome or Edge executable.');
const url=process.env.WEB_BROWSER_URL||'http://127.0.0.1:4173';
assert.ok(['localhost','127.0.0.1'].includes(new URL(url).hostname),'Browser checks only use the local web server.');
const profile=await mkdtemp(path.join(tmpdir(),'chamcong-browser-check-'));
const browser=spawn(executable,['--headless=new','--disable-gpu','--disable-background-networking','--disable-extensions','--disable-sync','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:'ignore',windowsHide:true});
let socket;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(read,timeout=10000){const started=Date.now();while(Date.now()-started<timeout){const value=await read();if(value)return value;await delay(30);}throw new Error('Browser check timed out.');}
const fixture=`import {demoData,memoryStore} from '/src/demo.js';
import {today,dateAdd} from '/src/ui.js';
export async function connectFirebase(){
  const data=demoData(), listeners=new Set();let store;
  const linkedId='fixture-linked-emp-1',linkedEmail='linked-account@example.com';
  // Keep the global users subscription stale while targeted reads see the link.
  // This reproduces an editor opened before account subscription data arrives.
  const subscriptionRows=(name,rows)=>name==='users'?rows.filter(row=>row.id!==linkedId):rows;
  const publish=()=>{for(const listener of listeners)store.list(listener.name,listener.options).then(result=>listener.onData(subscriptionRows(listener.name,result.rows),{fromCache:false,hasMore:result.hasMore}));};
  data.users.push({id:'demo-admin',uid:'demo-admin',active:true,role:'ADMIN',displayName:'Browser fixture',email:'fixture@example.com'});
  data.users.push({id:linkedId,uid:linkedId,employeeId:'emp-1',active:true,role:'EMPLOYEE',displayName:'Linked fixture employee',email:linkedEmail});
  const priorDate=dateAdd(today().slice(0,7)+'-01',-1),retiredId='fixture-retired';
  data.employees.push({id:retiredId,code:'NV-RETIRED',fullName:'Nhân viên nghỉ tháng trước',active:false,terminationDate:priorDate});
  store=memoryStore(data,publish);
  const originalGet=store.get,originalList=store.list;
  window.__browserFixture={hold:false,fail:false,releases:[],retiredId,priorDate,linkedEmail,holdAccounts:false,failAccounts:false,accountReleases:[],accountQueries:0,
    lockLinkedAccount:()=>{data.users.find(row=>row.id===linkedId).active=false;},
    removeLinkedAccount:()=>{data.users=data.users.filter(row=>row.id!==linkedId);}};
  store.get=async(...args)=>{if(window.__browserFixture.fail)throw Object.assign(new Error('Fixture read failed'),{code:'permission-denied'});if(window.__browserFixture.hold)await new Promise(resolve=>window.__browserFixture.releases.push(resolve));return originalGet(...args);};
  store.list=async(name,options={})=>{if(name==='users'&&options.where?.some(([field])=>field==='employeeId')){window.__browserFixture.accountQueries++;if(window.__browserFixture.failAccounts)throw Object.assign(new Error('Fixture account read failed'),{code:'permission-denied'});if(window.__browserFixture.holdAccounts)await new Promise(resolve=>window.__browserFixture.accountReleases.push(resolve));}return originalList(name,options);};
  store.listen=(name,options,onData)=>{const listener={name,options,onData};listeners.add(listener);store.list(name,options).then(result=>onData(subscriptionRows(name,result.rows),{fromCache:false,hasMore:result.hasMore}));return()=>listeners.delete(listener);};
  store.listenDoc=(name,id,onData)=>{queueMicrotask(()=>onData({id,active:true,role:'ADMIN',displayName:'Browser fixture',email:'fixture@example.com'}));return()=>{};};
  return {store,observe:callback=>{queueMicrotask(()=>callback({uid:'demo-admin',email:'fixture@example.com',isAnonymous:false}));return()=>{};},signOut:async()=>{}};
}`;
try{
  const port=await until(async()=>{try{return Number((await readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);}catch{return null;}});
  const page=await(await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'})).json();
  socket=new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  let nextId=0;const pending=new Map();const exceptions=[];
  const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++nextId;const timer=setTimeout(()=>{pending.delete(id);reject(new Error(`CDP timed out: ${method}`));},10000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
  socket.addEventListener('message',event=>{const message=JSON.parse(String(event.data));if(message.id){const request=pending.get(message.id);if(!request)return;clearTimeout(request.timer);pending.delete(message.id);message.error?request.reject(new Error(message.error.message)):request.resolve(message.result);}else if(message.method==='Fetch.requestPaused'){send('Fetch.fulfillRequest',{requestId:message.params.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'text/javascript; charset=utf-8'}],body:Buffer.from(fixture).toString('base64')}).catch(error=>exceptions.push(error.message));}else if(message.method==='Runtime.exceptionThrown')exceptions.push(message.params.exceptionDetails.text);});
  const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value;};
  const click=async selector=>{const point=await evaluate(`(async()=>{const el=document.querySelector(${JSON.stringify(selector)});if(!el)throw new Error('Missing button');el.scrollIntoView({block:'center'});await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));const r=el.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);await send('Input.dispatchMouseEvent',{type:'mouseMoved',...point});await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point});await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point});};
  const tap=async selector=>{const point=await evaluate(`(async()=>{const el=document.querySelector(${JSON.stringify(selector)});if(!el)throw new Error('Missing touch target');el.scrollIntoView({block:'center'});await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));const r=el.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...point,id:1}]});await delay(50);await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});};
  const reset=async()=>{await send('Page.navigate',{url});await until(()=>evaluate(`!!document.querySelector('[data-action="org-employee-add"]:not(:disabled)') && !document.querySelector('.loading-line')`));await delay(200);};
  const dialog=()=>evaluate(`(()=>{const el=document.querySelector('#modal');const r=el.getBoundingClientRect();return{open:el.open,visible:r.width>0&&r.height>0,title:document.querySelector('#dialog-title')?.textContent,error:document.querySelector('#dialog-error')?.textContent};})()`);
  await send('Runtime.enable');await send('Page.enable');await send('Network.enable');
  await send('Emulation.setDeviceMetricsOverride',{width:1366,height:900,deviceScaleFactor:1,mobile:false});
  await send('Network.setBlockedURLs',{urls:['*googleapis.com/*','*gstatic.com/firebasejs/*']});
  await send('Fetch.enable',{patterns:[{urlPattern:'*/src/firebase.js',requestStage:'Request'}]});
  const failures=[];
  async function check(name,run){try{await reset();await run();console.log(`PASS ${name}`);}catch(error){failures.push(name);console.log(`FAIL ${name}: ${error.message}`);}}
  const employeeEditSelector='[data-action="org-employee-edit"][data-id="emp-1"]';
  const employeesPage=async()=>{await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector(${JSON.stringify(employeeEditSelector)})`));await delay(150);};
  const editLinkedEmployee=async()=>{await employeesPage();await click(employeeEditSelector);await until(()=>evaluate(`!!document.querySelector('#employee-account-title')`));};
  const accountSection=()=>evaluate(`(()=>{const section=document.querySelector('#employee-account-title')?.closest('section'),create=section?.querySelector('[data-action="org-account-create"]');return{text:section?.textContent,create:!!create,disabled:create?.disabled};})()`);
  const accountScreenshot=async name=>{if(!process.env.WEB_BROWSER_SCREENSHOTS)return;const output=path.resolve(process.env.WEB_BROWSER_SCREENSHOTS);await mkdir(output,{recursive:true});const clip=await evaluate(`(async()=>{const section=document.querySelector('#employee-account-title').closest('section');section.scrollIntoView({block:'center',behavior:'instant'});await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));const r=section.getBoundingClientRect();return{x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,scale:1};})()`);await writeFile(path.join(output,name),Buffer.from((await send('Page.captureScreenshot',{format:'png',clip,captureBeyondViewport:false})).data,'base64'));};
  await check('right-side Add button opens a visible native dialog',async()=>{await click('[data-action="org-employee-add"]');const state=await dialog();assert.equal(state.open,true);assert.equal(state.visible,true);assert.equal(state.title,'Thêm nhân viên');});
  await check('a native form saves and enables tools inside the same employee editor',async()=>{
    await click('[data-action="org-employee-add"]');
    await evaluate(`document.querySelector('#modal [name="fullName"]').value='Nhân viên kiểm tra Edge';document.querySelector('#modal [name="email"]').value='edge-check@example.com'`);
    await click('#modal [type="submit"]');await until(async()=>(await dialog()).title==='Nhân viên · Nhân viên kiểm tra Edge');
    const choices=await evaluate(`(()=>{const f=document.querySelector('#modal [data-action="org-fingerprint-enroll"]'),c=document.querySelector('#modal [data-action="face-enroll"]');return{fingerprintId:f?.dataset.id,faceId:c?.dataset.id,faceDisabled:c?.disabled};})()`);
    assert.ok(choices.fingerprintId);assert.equal(choices.faceId,choices.fingerprintId);assert.equal(choices.faceDisabled,false);
    await click('#modal [data-action="dialog-close"]');
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`document.querySelector('#app').textContent.includes('Nhân viên kiểm tra Edge')`));
  });
  await check('linked accounts hide Create despite stale users subscription, dirty email and saving',async()=>{
    await editLinkedEmployee();
    const linkedEmail=await evaluate(`window.__browserFixture.linkedEmail`);
    let account=await accountSection();assert.ok(account.text.includes(linkedEmail));assert.equal(account.create,false);
    await evaluate(`(()=>{const input=document.querySelector('#modal [name="email"]');input.value='changed-contact@example.com';input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
    assert.equal(await evaluate(`document.querySelector('#dialog-form').dataset.employeeDraft`),'dirty');
    account=await accountSection();assert.ok(account.text.includes(linkedEmail));assert.equal(account.create,false);
    await accountScreenshot('linked-account-dirty-email.png');
    const queries=await evaluate(`window.__browserFixture.accountQueries`);
    await click('#modal [type="submit"]');
    await until(()=>evaluate(`window.__browserFixture.accountQueries>${queries} && !!document.querySelector('#modal[open] #employee-account-title') && !document.querySelector('#dialog-form[aria-busy="true"]')`));
    account=await accountSection();assert.ok(account.text.includes(linkedEmail));assert.equal(account.create,false);
    assert.equal(await evaluate(`document.querySelector('#modal [name="email"]').value`),'changed-contact@example.com');
    await accountScreenshot('linked-account-after-save.png');
  });
  await check('locked linked accounts also hide Create',async()=>{
    await evaluate(`window.__browserFixture.lockLinkedAccount()`);await editLinkedEmployee();
    const account=await accountSection();assert.match(account.text,/Đã khóa/);assert.ok(account.text.includes('linked-account@example.com'));assert.equal(account.create,false);
    await accountScreenshot('linked-account-locked.png');
  });
  await check('a verified empty account query allows Create',async()=>{
    await evaluate(`window.__browserFixture.removeLinkedAccount()`);await editLinkedEmployee();
    const account=await accountSection();assert.match(account.text,/Chưa có tài khoản đăng nhập/);assert.equal(account.create,true);assert.equal(account.disabled,false);
  });
  await check('an account query error hides Create and shows a retry message',async()=>{
    await evaluate(`window.__browserFixture.failAccounts=true`);await editLinkedEmployee();
    const account=await accountSection();assert.match(account.text,/Chưa tải được tài khoản liên kết/);assert.equal(account.create,false);
  });
  await check('a pending account query cannot overwrite a newer Add dialog',async()=>{
    await employeesPage();await evaluate(`window.__browserFixture.holdAccounts=true`);await click(employeeEditSelector);
    await until(()=>evaluate(`window.__browserFixture.accountReleases.length>0`));
    assert.equal((await dialog()).open,false);
    await click('[data-action="org-employee-add"]');assert.equal((await dialog()).title,'Thêm nhân viên');
    await evaluate(`window.__browserFixture.holdAccounts=false;window.__browserFixture.accountReleases.forEach(resolve=>resolve())`);await delay(200);
    assert.equal((await dialog()).title,'Thêm nhân viên');assert.equal(await evaluate(`document.querySelector('[data-employee-editor]').dataset.employeeEditor`),'new');
  });
  await check('work, schedule and attendance controls open their actual forms',async()=>{
    for(const [action,title] of [['ops-work-new','Tạo'],['ops-schedule-new','Phân'],['attendance-adjust','Điều chỉnh công']]){
      await click(`[data-action="${action}"]`);const state=await dialog();assert.equal(state.open,true,action);assert.equal(state.visible,true,action);assert.ok(state.title.includes(title),`${action}: ${state.title}`);await click('#modal [data-action="dialog-close"]');
    }
  });
  await check('a pending Edit read does not block Add or reopen stale Edit',async()=>{
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector('[data-action="org-employee-edit"]')`));await delay(150);
    await evaluate(`window.__browserFixture.hold=true`);await click('[data-action="org-employee-edit"]');await until(()=>evaluate(`window.__browserFixture.releases.length>0`));
    await click('[data-action="org-employee-add"]');assert.equal((await dialog()).title,'Thêm nhân viên');
    await evaluate(`window.__browserFixture.hold=false;window.__browserFixture.releases.forEach(resolve=>resolve())`);await delay(200);assert.equal((await dialog()).title,'Thêm nhân viên');
  });
  await check('filter blur does not swallow the following Add click',async()=>{
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector('input[data-filter="search"]')`));await delay(150);
    await evaluate(`document.querySelector('input[data-filter="search"]').focus()`);await send('Input.insertText',{text:'Nguyễn'});await click('[data-action="org-employee-add"]');assert.equal((await dialog()).title,'Thêm nhân viên');
  });
  await check('failed actions inside a dialog show their error in that dialog',async()=>{
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector('[data-action="org-employee-detail"]')`));await delay(150);
    await click('[data-action="org-employee-detail"]');await until(async()=>(await dialog()).open);
    await evaluate(`window.__browserFixture.fail=true`);await click('#modal [data-action="org-employee-salary"]');await delay(150);assert.match((await dialog()).error,/Không đủ quyền thao tác/);
  });
  await check('attendance adjustment hides past retirees and refreshes its picker when the date changes',async()=>{
    const fixture=await evaluate(`({retiredId:window.__browserFixture.retiredId,priorDate:window.__browserFixture.priorDate})`);
    await click('[data-action="attendance-adjust"]');
    const choices=()=>evaluate(`Array.from(document.querySelector('#modal [name="employeeId"]').options,option=>({value:option.value,label:option.textContent}))`);
    assert.equal((await choices()).some(option=>option.value===fixture.retiredId),false);
    const currentDate=await evaluate(`document.querySelector('#modal [name="scheduleDate"]').value`);
    const changeDate=date=>evaluate(`(()=>{const input=document.querySelector('#modal [name="scheduleDate"]');input.value=${JSON.stringify(date)};input.dispatchEvent(new Event('change',{bubbles:true}));})()`);
    await changeDate(fixture.priorDate);
    assert.match((await choices()).find(option=>option.value===fixture.retiredId)?.label||'',/Đã nghỉ/);
    await evaluate(`document.querySelector('#modal [name="employeeId"]').value=${JSON.stringify(fixture.retiredId)};document.querySelector('#modal [name="reason"]').value='Giữ nội dung đã nhập';document.querySelector('#modal [name="workedHoursOverride"]').value='4'`);
    await changeDate(currentDate);
    const values=await evaluate(`Object.fromEntries(new FormData(document.querySelector('#dialog-form')))`);
    assert.equal((await choices()).some(option=>option.value===fixture.retiredId),false);assert.equal(values.employeeId,'');assert.equal(values.reason,'Giữ nội dung đã nhập');assert.equal(values.workedHoursOverride,'4');
  });
  await check('shared design keeps desktop forms readable and controls accessible',async()=>{
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector('table')`));
    const styles=await evaluate(`({body:getComputedStyle(document.body).fontSize,input:getComputedStyle(document.querySelector('input')).fontSize,table:getComputedStyle(document.querySelector('table')).fontSize,button:parseFloat(getComputedStyle(document.querySelector('[data-action="org-employee-add"]')).minHeight)})`);
    assert.equal(styles.body,'16px');assert.equal(styles.input,'16px');assert.equal(styles.table,'14px');assert.ok(styles.button>=44);
  });
  await check('work priority and deadline filters are wired to the actual page',async()=>{
    await evaluate(`location.hash='#workItems'`);await until(()=>evaluate(`!!document.querySelector('[data-filter="workPriority"]')`));
    await evaluate(`(()=>{const select=document.querySelector('[data-filter="workPriority"]');select.value='HIGH';select.dispatchEvent(new Event('change',{bubbles:true}));})()`);
    assert.equal(await evaluate(`document.querySelector('[data-filter="workPriority"]').value`),'HIGH');
    await evaluate(`(()=>{const select=document.querySelector('[data-filter="workDue"]');select.value='OVERDUE';select.dispatchEvent(new Event('change',{bubbles:true}));})()`);
    assert.equal(await evaluate(`document.querySelector('[data-filter="workDue"]').value`),'OVERDUE');
  });
  await check('page titles agree with navigation across desktop and phone layouts',async()=>{
    const routes=await evaluate(`Array.from(document.querySelectorAll('.nav-item'),link=>({href:link.getAttribute('href'),title:link.querySelector('span').textContent.trim()}))`);
    for(const mobile of [false,true]){
      await send('Emulation.setDeviceMetricsOverride',{width:mobile?375:1366,height:mobile?812:900,deviceScaleFactor:1,mobile});
      await send('Emulation.setTouchEmulationEnabled',{enabled:mobile});
      for(const route of routes){
        if(mobile){
          await evaluate(`window.__navigationEvents=[];for(const type of ['pointerdown','pointerup','touchstart','touchend','click'])document.addEventListener(type,event=>window.__navigationEvents.push({type,x:event.clientX,y:event.clientY,target:event.target.closest?.('[data-action]')?.dataset.action,open:document.querySelector('.sidebar')?.classList.contains('open')}),{capture:true,once:true})`);
          await tap('[data-action="menu"]');
          try { await until(()=>evaluate(`document.querySelector('.sidebar.open')?.getBoundingClientRect().x>=-0.5`)); }
          catch(error) {
            if(process.env.WEB_BROWSER_SCREENSHOTS)await writeFile(path.join(path.resolve(process.env.WEB_BROWSER_SCREENSHOTS),'mobile-navigation-failure.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
            throw new Error(`phone menu before ${route.href}: ${error.message}; actual ${JSON.stringify(await evaluate(`(()=>{const menu=document.querySelector('[data-action="menu"]'),r=menu.getBoundingClientRect();return{hash:location.hash,classes:document.querySelector('.sidebar')?.className,x:document.querySelector('.sidebar')?.getBoundingClientRect().x,menu:{x:r.x,y:r.y,w:r.width,h:r.height,display:getComputedStyle(menu).display,disabled:menu.disabled},events:window.__navigationEvents,hit:document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.outerHTML,viewport:{width:innerWidth,scale:visualViewport.scale,offsetX:visualViewport.offsetLeft,offsetY:visualViewport.offsetTop}};})()`))}`);
          }
        }
        await (mobile?tap:click)(`.nav-item[href="${route.href}"]`);
        try { await until(()=>evaluate(`document.querySelector('.nav-item.active')?.getAttribute('href')===${JSON.stringify(route.href)}`)); }
        catch(error) { throw new Error(`${mobile?'phone':'desktop'} ${route.href}: ${error.message}; actual ${await evaluate(`location.hash+' / '+document.querySelector('.nav-item.active')?.getAttribute('href')`)}`); }
        const result=await evaluate(`({title:document.querySelector('.content .page-heading h1,.content .page-heading h2,.content .section-heading h2')?.textContent.trim(),width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth})`);
        assert.equal(result.title,route.title,`${mobile?'phone':'desktop'} ${route.href}`);
        assert.ok(result.scroll<=result.width,`${route.href} must not overflow the page`);
      }
    }
    await send('Emulation.setDeviceMetricsOverride',{width:1366,height:900,deviceScaleFactor:1,mobile:false});
    await send('Emulation.setTouchEmulationEnabled',{enabled:false});
  });
  if(process.env.WEB_BROWSER_SCREENSHOTS){
    const output=path.resolve(process.env.WEB_BROWSER_SCREENSHOTS);await mkdir(output,{recursive:true});
    await reset();await writeFile(path.join(output,'desktop-dashboard.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
    await send('Emulation.setDeviceMetricsOverride',{width:375,height:812,deviceScaleFactor:1,mobile:true});await send('Emulation.setTouchEmulationEnabled',{enabled:true});
    await evaluate(`location.hash='#employees'`);await until(()=>evaluate(`!!document.querySelector('table')`));
    const mobile=await evaluate(`({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth,button:parseFloat(getComputedStyle(document.querySelector('[data-action="org-employee-add"]')).minHeight)})`);
    assert.ok(mobile.scroll<=mobile.width,'Page must not overflow the mobile viewport');assert.ok(mobile.button>=48,'Coarse pointer controls must reach 48px');
    await writeFile(path.join(output,'mobile-employees.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));
    console.log(`Screenshots: ${output}`);
  }
  assert.deepEqual(exceptions,[],'Uncaught browser errors');
  await send('Browser.close');socket.close();socket=null;
  console.log(`Temporary test profile: ${profile}`);
  if(failures.length)process.exitCode=1;
}finally{if(socket){try{socket.send(JSON.stringify({id:999999,method:'Browser.close'}));}catch{}socket.close();}browser.kill();}
