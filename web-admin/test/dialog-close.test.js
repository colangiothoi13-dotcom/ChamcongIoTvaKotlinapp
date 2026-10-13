import test from 'node:test';
import assert from 'node:assert/strict';
import {createDialogCloseGuard} from '../src/dialog-close.js';

const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
function fixture(){
  const control=disabled=>({disabled,isConnected:true});
  const makeForm=()=>({controls:[control(false),control(true)],attributes:{},querySelectorAll(){return this.controls;},setAttribute(key,value){this.attributes[key]=value;},removeAttribute(key){delete this.attributes[key];}});
  let form=makeForm(),saving=false,closes=0;const errors=[];
  const modal={open:true,querySelector(){return form;},close(){this.open=false;closes++;}};
  const guard=createDialogCloseGuard(modal,{isSaving:()=>saving,onError:error=>errors.push(error)});
  return {modal,guard,errors,get form(){return form;},get closes(){return closes;},set saving(value){saving=value;},replace(){form.controls.forEach(control=>control.isConnected=false);form=makeForm();guard.reset();modal.open=true;}};
}

test('user dismissal waits for confirmation and coalesces repeated close actions',async()=>{
  const f=fixture(),wait=deferred();let calls=0;
  f.guard.setBeforeClose(()=>{calls++;return wait.promise;});
  const pending=f.guard.request();assert.equal(f.modal.open,true);assert.equal(f.guard.isClosing(),true);assert.ok(f.form.controls.every(control=>control.disabled));assert.equal(f.form.attributes['aria-busy'],'true');
  assert.equal(await f.guard.request(),false);assert.equal(calls,1);wait.resolve(true);
  assert.equal(await pending,true);assert.equal(f.closes,1);assert.equal(f.guard.isClosing(),false);
});

test('failed cancellation keeps the same dialog open and restores original disabled states',async()=>{
  for(const result of [false,new Error('offline')]){
    const f=fixture();f.guard.setBeforeClose(async()=>{if(result instanceof Error)throw result;return result;});
    assert.equal(await f.guard.request(),false);assert.equal(f.modal.open,true);assert.equal(f.closes,0);assert.deepEqual(f.form.controls.map(control=>control.disabled),[false,true]);assert.equal(f.form.attributes['aria-busy'],undefined);assert.equal(f.errors.length,result instanceof Error?1:0);
  }
});

test('an old close resolving after replacement cannot close or unlock the new dialog',async()=>{
  for(const outcome of ['success','failure']){
    const f=fixture(),old=deferred(),next=deferred();f.guard.setBeforeClose(()=>old.promise);
    const pending=f.guard.request();f.replace();f.guard.setBeforeClose(()=>next.promise);const replacement=f.form;
    const newPending=f.guard.request();if(outcome==='success')old.resolve(true);else old.reject(new Error('old failure'));
    assert.equal(await pending,false);assert.equal(f.modal.open,true);assert.equal(f.form,replacement);assert.ok(replacement.controls.every(control=>control.disabled));assert.equal(f.guard.isClosing(),true);assert.deepEqual(f.errors,[]);
    next.resolve(false);assert.equal(await newPending,false);assert.deepEqual(replacement.controls.map(control=>control.disabled),[false,true]);
  }
});

test('programmatic cleanup invalidates user cancellation without running another callback',async()=>{
  const f=fixture(),wait=deferred();let calls=0;f.guard.setBeforeClose(()=>{calls++;return wait.promise;});
  const pending=f.guard.request();f.guard.reset();f.modal.close();wait.resolve(true);
  assert.equal(await pending,false);assert.equal(calls,1);assert.equal(f.closes,1);
  const fresh=fixture();fresh.guard.setBeforeClose(()=>{calls++;return true;});fresh.guard.reset();assert.equal(await fresh.guard.request(),true);assert.equal(calls,1);
});

test('saving blocks all dismissals and ordinary terminal dialogs close without an asynchronous hook',async()=>{
  const f=fixture();let calls=0;f.guard.setBeforeClose(()=>{calls++;return true;});f.saving=true;
  assert.equal(await f.guard.request(),false);assert.equal(calls,0);assert.equal(f.modal.open,true);assert.deepEqual(f.form.controls.map(control=>control.disabled),[false,true]);
  f.saving=false;f.guard.setBeforeClose(null);assert.equal(await f.guard.request(),true);assert.equal(calls,0);assert.equal(f.closes,1);
});
