import test from 'node:test';
import assert from 'node:assert/strict';

// Exercise the actual entry point and delegated navigation without a live account.
// This checks JavaScript wiring; it does not replace visual browser QA.
test('entry point manages data through dashboard actions, navigates every section and ends the session',async()=>{
  const originals=Object.fromEntries(['document','window','location','CSS','setInterval','FormData','setTimeout','fetch'].map(key=>[key,globalThis[key]]));
  const documentHandlers={},windowHandlers={};
  const node=()=>({innerHTML:'',textContent:'',isConnected:true,disabled:false,handlers:{},attributes:{},style:{},addEventListener(event,callback){this.handlers[event]=callback;},querySelector(){return node();},querySelectorAll(){return[];},getAttribute(name){return this.attributes[name];},setAttribute(name,value){this.attributes[name]=value;},removeAttribute(name){delete this.attributes[name];},classList:{remove(){},toggle(){}},append(){},focus(){},remove(){}});
  let dialogForm,dialogSubmit,dialogCancel,dialogTools=[];
  const app=node(),dialogError=node(),modal={...node(),open:false,close(){this.open=false;},showModal(){this.open=true;},getBoundingClientRect(){return {left:100,top:100,right:500,bottom:500};},querySelector(selector){return selector==='form'?dialogForm:dialogError;}};
  const appNodes=new Map();let appHTML='';
  Object.defineProperty(app,'innerHTML',{get(){return appHTML;},set(html){appHTML=html;appNodes.clear();}});
  app.querySelector=selector=>{if(!appNodes.has(selector))appNodes.set(selector,node());return appNodes.get(selector);};
  let dialogHTML='';
  Object.defineProperty(modal,'innerHTML',{get(){return dialogHTML;},set(html){
    dialogHTML=html;dialogSubmit=node();dialogCancel=node();dialogSubmit.textContent=html.match(/type="submit"[^>]*>([^<]*)/)?.[1]||'';
    const initialValues=Object.fromEntries([...html.matchAll(/<input\b[^>]*name="([^"]+)"[^>]*value="([^"]*)"/g)].map(([,name,value])=>[name,value]));
    dialogTools=[...html.matchAll(/<button\b([^>]*data-action="(?:org-account-create|org-fingerprint-enroll|org-fingerprint-delete|face-enroll|face-delete)"[^>]*)>/g)].map(([,attributes])=>({...node(),disabled:/\bdisabled\b/.test(attributes)}));
    const draftNote=node();
    dialogForm={...node(),dataset:{},values:initialValues,elements:{namedItem(name){return{value:dialogForm.values[name]??''};}},reportValidity(){return true;},querySelector(selector){return selector==='[data-employee-draft-note]'?draftNote:dialogSubmit;},querySelectorAll(selector){return selector==='[data-employee-tools] button[data-action]'?dialogTools:[dialogSubmit,dialogCancel,...dialogTools];}};
  }});
  const form=node(),error=node();
  globalThis.document={activeElement:null,querySelector(selector){return selector==='#app'?app:selector==='#modal'?modal:selector==='#login-form'?form:selector==='#login-error'?error:node();},addEventListener(event,callback){documentHandlers[event]=callback;},createElement:node};
  globalThis.window={scrollY:0,scrollTo(x,y){this.scrollY=y;},addEventListener(event,callback){windowHandlers[event]=callback;}};
  globalThis.location={hash:'#payroll'};globalThis.CSS={escape:value=>value};globalThis.setInterval=()=>0;
  globalThis.FormData=class{constructor(form){return new Map(Object.entries(form.values));}};
  globalThis.setTimeout=(callback,delay,...args)=>originals.setTimeout(callback,Math.min(delay,100),...args);
  const previewRequests=[];
  globalThis.fetch=async(url,options)=>{previewRequests.push({url,signal:options.signal});return url.endsWith('/health')?new Response(JSON.stringify({cameraReady:true,wifiConnected:true,sensor:'OV5640'}),{headers:{'Content-Type':'application/json'}}):new Response(new Uint8Array([0xff,0xd8,0xff,0xd9]),{headers:{'Content-Type':'image/jpeg'}});};
  try{
    await import('../src/app.js');assert.match(app.innerHTML,/Đăng nhập bằng tài khoản admin/);
    const click=(action,extra={})=>documentHandlers.click({target:{closest:()=>({...node(),dataset:{action,...extra}})}});
    await click('demo');assert.match(app.innerHTML,/DỮ LIỆU MẪU/);
    assert.match(app.innerHTML,/href="#payroll" class="nav-item active"/);
    assert.doesNotMatch(app.innerHTML,/data-action="payroll-create"[^>]*disabled/);
    location.hash='#dashboard';windowHandlers.hashchange();assert.match(app.innerHTML,/<h1>Tổng quan<\/h1>/);
    assert.match(app.innerHTML,/Thao tác quản lý/);
    for(const action of ['org-employee-add','ops-work-new','ops-schedule-new','attendance-adjust'])assert.match(app.innerHTML,new RegExp(`data-action="${action}"`));
    await click('org-employee-add');assert.equal(modal.open,true);assert.match(modal.innerHTML,/Thêm nhân viên/);
    dialogForm.values={fullName:'Nhân viên thao tác web',email:'web-actions@example.com',phone:'',address:'',position:'',hireDate:'',departmentId:''};
    const saving=dialogForm.handlers.submit({preventDefault(){},currentTarget:dialogForm});
    assert.equal(dialogSubmit.disabled,true);assert.equal(dialogCancel.disabled,true);assert.equal(dialogSubmit.textContent,'Đang lưu…');
    let cancelPrevented=false;modal.handlers.cancel({preventDefault(){cancelPrevented=true;}});assert.equal(cancelPrevented,true);
    await click('dialog-close');assert.equal(modal.open,true);
    await saving;assert.equal(modal.open,true);assert.equal(dialogSubmit.disabled,false);
    assert.match(modal.innerHTML,/Nhân viên · Nhân viên thao tác web/);
    for(const title of ['Hồ sơ','Tài khoản','Vân tay','Khuôn mặt'])assert.match(modal.innerHTML,new RegExp(`>${title}</h3>`));
    const fingerprintId=modal.innerHTML.match(/data-action="org-fingerprint-enroll"\s+data-id="([^"]+)"/)?.[1];
    assert.ok(fingerprintId);assert.match(modal.innerHTML,new RegExp(`data-action="face-enroll"\\s+data-id="${fingerprintId}"[^>]*disabled`));
    await click('dialog-close');assert.equal(modal.open,false);
    window.scrollY=600;location.hash='#employees';windowHandlers.hashchange();assert.equal(window.scrollY,0);assert.match(app.innerHTML,/Nhân viên thao tác web/);
    assert.match(app.innerHTML,/Khuôn mặt/);assert.doesNotMatch(app.innerHTML,/data-action="face-enroll"/);
    assert.match(app.innerHTML,/data-action="org-employee-detail"/);
    const row=app.innerHTML.match(/data-action="org-employee-edit"\s+data-id="([^"]+)"[^>]*>Sửa hồ sơ/);
    assert.ok(row);
    // Find the newly created row's identifier, then edit via the actual delegated route.
    const newRow=app.innerHTML.match(/<tr>[^]*?Nhân viên thao tác web[^]*?data-action="org-employee-edit"\s+data-id="([^"]+)"/);
    assert.ok(newRow);await click('org-employee-edit',{id:newRow[1]});
    // A failed child action leaves this editor open; its subsequent Save still
    // belongs to the active form, even though the action version has changed.
    const editForm=dialogForm;await click('org-account-create',{id:'missing-employee'});
    assert.equal(dialogForm,editForm);assert.equal(modal.open,true);
    dialogForm.values={fullName:'Nhân viên đã sửa trên web',email:'web-actions@example.com',phone:'',address:'',position:'',hireDate:'',departmentId:''};
    dialogForm.handlers.input({target:{name:'fullName'},currentTarget:dialogForm});
    assert.equal(dialogForm.dataset.employeeDraft,'dirty');assert.ok(dialogTools.every(tool=>tool.disabled));
    await dialogForm.handlers.submit({preventDefault(){},currentTarget:dialogForm});assert.match(app.innerHTML,/Nhân viên đã sửa trên web/);
    assert.equal(modal.open,true);assert.match(modal.innerHTML,/Nhân viên · Nhân viên đã sửa trên web/);
    await click('dialog-close');
    // Native Escape and a backdrop click use the same guarded user dismissal
    // route as the X/footer buttons; ordinary dialogs still close immediately.
    await click('account');let escapePrevented=false;
    modal.handlers.cancel({preventDefault(){escapePrevented=true;}});assert.equal(escapePrevented,true);assert.equal(modal.open,false);
    await click('account');let backdropPrevented=false,backdropStopped=false;
    modal.handlers.click({target:modal,clientX:10,clientY:10,preventDefault(){backdropPrevented=true;},stopPropagation(){backdropStopped=true;}});
    assert.equal(backdropPrevented,true);assert.equal(backdropStopped,true);assert.equal(modal.open,false);
    location.hash='#camera';windowHandlers.hashchange();assert.match(app.innerHTML,/<h1>Camera & khuôn mặt<\/h1>/);
    assert.equal(previewRequests.length,0);await click('camera-start');await new Promise(resolve=>originals.setTimeout(resolve,10));
    assert.deepEqual(previewRequests.map(row=>row.url),['/api/camera/health','/api/camera/frame']);
    const currentFrame=app.querySelector('#camera-image').getAttribute('src');assert.match(currentFrame,/^blob:/);assert.equal(app.querySelector('#camera-stop').disabled,false);
    // Re-rendering on this page preserves the latest image and live controls.
    windowHandlers.hashchange();assert.equal(app.querySelector('#camera-image').getAttribute('src'),currentFrame);assert.equal(app.querySelector('#camera-stop').disabled,false);
    location.hash='#devices';windowHandlers.hashchange();assert.equal(previewRequests.at(-1).signal.aborted,true);
    location.hash='#camera';windowHandlers.hashchange();await click('camera-start');await new Promise(resolve=>originals.setTimeout(resolve,10));
    document.hidden=true;documentHandlers.visibilitychange();assert.equal(previewRequests.at(-1).signal.aborted,true);document.hidden=false;
    for(const page of ['workItems','employees','attendance','presence','schedules','shifts','requests','payroll','reports','performance','camera','devices','departments','notifications','resources','audit']){
      location.hash=`#${page}`;windowHandlers.hashchange();assert.match(app.innerHTML,new RegExp(`href="#${page}" class="nav-item active"`));assert.doesNotMatch(app.innerHTML,/\bNaN\b|\bundefined\b/);
    }
    location.hash='#schedules';windowHandlers.hashchange();
    documentHandlers.change({target:{dataset:{filter:'scheduleView'},value:'month'}});
    await new Promise(resolve=>originals.setTimeout(resolve,120));assert.match(app.innerHTML,/schedule-month-grid/);
    documentHandlers.change({target:{dataset:{filter:'scheduleView'},value:'week'}});
    await new Promise(resolve=>originals.setTimeout(resolve,120));assert.match(app.innerHTML,/schedule-grid/);
    location.hash='#payroll';windowHandlers.hashchange();
    documentHandlers.change({target:{dataset:{filter:'month'},value:'2026-10'}});
    await new Promise(resolve=>originals.setTimeout(resolve,120));
    assert.doesNotMatch(app.innerHTML,/data-action="payroll-create"[^>]*disabled/);
    location.hash='#camera';windowHandlers.hashchange();await click('camera-start');await new Promise(resolve=>originals.setTimeout(resolve,10));
    await click('logout');assert.equal(previewRequests.at(-1).signal.aborted,true);assert.match(app.innerHTML,/Đăng nhập bằng tài khoản admin/);assert.doesNotMatch(app.innerHTML,/DỮ LIỆU MẪU/);
    // Allow the optional Auth SDK initialization rejection to finish in Node.
    await new Promise(resolve=>setTimeout(resolve,30));
  }finally{for(const[key,value]of Object.entries(originals)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
});
