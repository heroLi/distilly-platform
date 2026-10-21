'use strict';
// One existing upload dialog, optionally returning to its current caller.
window.MaterialUploadFlow={create({context,capture,current,api,openModal,closeModal,refresh,continueKnowledge,notify,elements,formData}){
 const {modal,file,error,only,more}=elements;let intent=null,busy=false;
 const active=op=>intent===op&&modal.open&&current(op.scope);
 function buttons(){only.disabled=more.disabled=busy||!!intent?.denied||!!intent?.uncertain;}
 function cancel(){intent=null;only.textContent='上传材料';more.hidden=false;}
 function open({completed}={}){
  const c=context();if(!c.project||!['OWNER','EDITOR'].includes(c.role)){notify('当前权限不能上传材料，请刷新工作空间列表。',true);return;}
  if(busy){notify('已有材料上传待确认，请先等待并核对材料列表。',true);return;}
  intent={project:c.project,scope:capture(),completed,denied:false};file.value='';error.textContent='';
  only.textContent=completed?'上传并选中，返回人格生成':'上传材料';more.hidden=!!completed;buttons();openModal();
 }
 async function submit(event){
  const op=intent;if(busy||!op||op.denied||op.uncertain||!active(op))return;
  const goKnowledge=!op.completed&&event?.submitter===more;busy=true;buttons();error.textContent='';let saved=false;
  try{
   op.started=false;
   const selected=file.files[0];if(!selected)throw Error('请选择材料文件');if(selected.size>1048576)throw Error('文件超过 1 MiB');
   const body=formData();body.append('file',selected);
   op.started=true;const uploaded=await api('/api/projects/'+encodeURIComponent(op.project)+'/materials',{method:'POST',body});saved=true;
   if(!active(op))return;
   file.value='';closeModal();
   let refreshFailed=false;try{await refresh(uploaded);}catch(failure){refreshFailed=true;}
   if(!current(op.scope))return;
   if(op.completed){notify(refreshFailed?'材料已保存，列表刷新失败。返回的人格窗口会重新读取材料，请核对后继续，不要重复上传。':'材料已保存，请在返回的人格窗口核对所选材料后提交生成。',refreshFailed);await op.completed(uploaded.id,{refreshFailed});}
   else{notify(refreshFailed?'材料已保存，列表刷新失败。请刷新列表核对，不要重复上传。':'材料已上传并选中，可继续生成知识',refreshFailed);if(goKnowledge)await continueKnowledge();}
  }catch(failure){
   if(saved){if(current(op.scope))notify('材料已保存，后续页面未能打开。请从现有材料或人格列表继续，不要重复上传。',true);}
   else if(active(op)){
    op.denied=[401,403,404].includes(failure.status);
    op.uncertain=op.started&&(!failure.status||failure.status>=500);
    error.textContent=op.denied?'当前空间或权限已变化，请关闭窗口并刷新列表。':op.uncertain?'上传结果待核对，请关闭窗口先检查材料列表，避免重复上传。':failure.message;
   }
  }finally{busy=false;buttons();}
 }
 return {open,submit,cancel};
}};
