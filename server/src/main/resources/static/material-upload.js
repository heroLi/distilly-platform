'use strict';
// One existing upload dialog and explicit recovery of its actor/workspace operation.
window.MaterialUploadFlow={create({context,capture,current,api,openModal,closeModal,refresh,continueKnowledge,notify,elements,formData,storage,crypto}){
 const {modal,file,error,only,more,recheck,retry}=elements,storeKey='platform-material-submissions-v1';
 const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
 let intent=null,busy=false;
 const active=op=>intent===op&&modal.open&&current(op.scope);
 const slot=c=>JSON.stringify([c.userId,c.project]);
 function records(){const value=JSON.parse(storage.getItem(storeKey)||'{}');if(!value||typeof value!=='object'||Array.isArray(value))throw Error('invalid records');return value;}
 function read(op){const value=records()[op.slot];if(value&&(!uuid.test(value.request_id||'')||!/^[0-9a-f]{64}$/.test(value.fingerprint||'')||Object.keys(value).length!==2))throw Error('invalid record');return value;}
 function save(op,value){const all=records();if(value)all[op.slot]=value;else delete all[op.slot];storage.setItem(storeKey,JSON.stringify(all));}
 function buttons(){
  let record=null,broken=false;try{if(intent)record=read(intent);}catch(failure){broken=true;}
  only.disabled=more.disabled=busy||!!intent?.denied||broken||!!record;
  recheck.hidden=!record;retry.hidden=!record||intent?.notFound!==record.request_id;
  recheck.disabled=retry.disabled=busy||!!intent?.denied||broken;
  if(broken)error.textContent='无法读取上传操作记录，已暂停上传。请检查浏览器存储并先核对材料列表。';
 }
 function cancel(){intent=null;only.textContent='上传材料';more.hidden=false;recheck.hidden=retry.hidden=true;}
 function clear(){cancel();file.value='';try{storage.removeItem(storeKey);}catch(failure){}buttons();}
 function open({completed}={}){
  const c=context();if(!c.project||!['OWNER','EDITOR'].includes(c.role)){notify('当前权限不能上传材料，请刷新工作空间列表。',true);return;}
  if(busy){notify('已有材料上传待确认，请先等待并核对材料列表。',true);return;}
  intent={project:c.project,slot:slot(c),scope:capture(),completed,denied:false};file.value='';error.textContent='';
  only.textContent=completed?'上传并选中，返回人格生成':'上传材料';more.hidden=!!completed;
  try{if(read(intent))error.textContent='上次上传结果待核对。请先核对上次上传，不会自动重传。';}catch(failure){}
  buttons();openModal();
 }
 async function fingerprint(selected){
  const bytes=await selected.arrayBuffer();const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify([selected.name,selected.size,hash])))),b=>b.toString(16).padStart(2,'0')).join('');
 }
 function receiptMatches(value,record){return value?.status==='ACCEPTED'&&value.request_id===record.request_id&&uuid.test(value.material_id||'');}
 async function finish(op,record,value){
  if(!active(op))return;
  if(!receiptMatches(value,record))throw Error('unconfirmed upload receipt');
  if(read(op)?.request_id===record.request_id)save(op,null);
  file.value='';closeModal();
  let refreshFailed=false;try{await refresh({id:value.material_id});}catch(failure){refreshFailed=true;}
  if(!current(op.scope))return;
  if(op.completed){notify(refreshFailed?'材料已保存，列表刷新失败。返回的人格窗口会重新读取材料，请核对后继续，不要重复上传。':'材料已保存，请在返回的人格窗口核对所选材料后提交生成。',refreshFailed);await op.completed(value.material_id,{refreshFailed});}
  else{notify(refreshFailed?'材料已保存，列表刷新失败。请刷新列表核对，不要重复上传。':'材料已上传并选中，可继续生成知识',refreshFailed);if(op.goKnowledge)await continueKnowledge();}
 }
 async function perform(mode,event){
  const op=intent;if(busy||!op||op.denied||!active(op))return;
  busy=true;error.textContent='';let accepted=false;buttons();
  try{
   op.started=false;let record=read(op);
   if(mode==='check'){
    if(!record)return;
    try{const value=await api('/api/projects/'+encodeURIComponent(op.project)+'/material-submissions/'+record.request_id);if(!active(op))return;if(!receiptMatches(value,record))throw Error('unconfirmed receipt');accepted=true;await finish(op,record,value);}
    catch(failure){if(accepted)throw failure;if(!active(op))return;if(failure.status===404&&failure.code==='material_submission_not_found'){op.notFound=record.request_id;error.textContent='暂未查到保存记录。请重新选择完全相同的原文件，再显式重试上次上传；仍使用原操作编号。';}else throw failure;}
   }else{
    if(mode==='new'&&record)return;
    if(mode==='retry'&&(!record||op.notFound!==record.request_id))return;
    const selected=file.files[0];if(!selected)throw Error('请选择材料文件');if(selected.size>1048576)throw Error('文件超过 1 MiB');
    const hash=await fingerprint(selected);if(!active(op))return;
    if(record&&record.fingerprint!==hash){error.textContent='当前文件名或内容与上次操作不同，未上传。请先核对或重新选择原文件。';return;}
    if(!record){record={request_id:crypto.randomUUID(),fingerprint:hash};save(op,record);op.goKnowledge=!op.completed&&event?.submitter===more;}
    const body=formData();body.append('file',selected);body.append('request_id',record.request_id);
    if(!active(op))return;
    op.notFound=null;op.started=true;const uploaded=await api('/api/projects/'+encodeURIComponent(op.project)+'/materials',{method:'POST',body});
    if(!active(op))return;if(!receiptMatches(uploaded?.submission,record))throw Error('unconfirmed upload receipt');accepted=true;await finish(op,record,uploaded.submission);
   }
  }catch(failure){
   if(accepted){if(current(op.scope))notify('材料已保存，后续页面未能打开。请从现有材料或人格列表继续，不要重复上传。',true);}
   else if(active(op)){
    op.denied=[401,403,404].includes(failure.status);
    if(op.denied){try{save(op,null);}catch(ignored){}error.textContent='当前空间或权限已变化，请关闭窗口并刷新列表。';}
    else if(op.started&&[400,413,415,422].includes(failure.status)){save(op,null);op.notFound=null;error.textContent=failure.message;}
    else error.textContent=mode==='check'?'暂时无法核对，请再次核对上次上传；不会自动重传。':op.started?'上传结果待核对，请先核对上次上传；不会自动重传。':['请选择材料文件','文件超过 1 MiB'].includes(failure.message)?failure.message:'本次未上传：无法读取文件或保存操作编号，请检查浏览器存储后重试。';
   }
  }finally{busy=false;buttons();}
 }
 recheck.onclick=()=>perform('check');retry.onclick=()=>perform('retry');
 return {open,submit:event=>perform('new',event),cancel,clear};
}};
