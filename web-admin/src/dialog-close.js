// User dismissals may need an asynchronous action before closing. Replacing a
// dialog or ending a session only resets this guard and never runs that action.
export function createDialogCloseGuard(modal,{isSaving=()=>false,onClose=()=>modal.close(),onError=()=>{}}={}){
  let generation=0,pending=null,beforeClose=null;
  function reset(){generation++;pending=null;beforeClose=null;}
  function setBeforeClose(callback){reset();beforeClose=callback||null;}
  async function request(){
    if(isSaving()||pending||!modal.open)return false;
    const form=modal.querySelector('form'),version=generation,callback=beforeClose;
    if(!form)return false;
    const controls=[...form.querySelectorAll('button,input,select,textarea')].map(control=>[control,control.disabled]);
    const task={form,version};pending=task;
    const current=()=>modal.open&&modal.querySelector('form')===form&&generation===version;
    controls.forEach(([control])=>{control.disabled=true;});form.setAttribute('aria-busy','true');
    try{
      const allowed=callback?await callback():true;
      if(allowed!==false&&current()){onClose();return true;}
      return false;
    }catch(error){if(current())onError(error);return false;}
    finally{
      if(pending===task)pending=null;
      if(modal.querySelector('form')===form&&generation===version){form.removeAttribute('aria-busy');controls.forEach(([control,disabled])=>{if(control.isConnected)control.disabled=disabled;});}
    }
  }
  return {request,reset,setBeforeClose,isClosing:()=>pending!==null};
}
