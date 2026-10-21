'use strict';
window.PersonalityBindingFlow={open({workspace,personalityId,versionId,people,sourcePersona,current,api,node,modal,onBound}){
 const m=modal('绑定人格版本'),select=node('select'),status=node('p','','muted');
 select.setAttribute('aria-label','选择虚拟人');status.setAttribute('role','status');
 const placeholder=node('option','请选择虚拟人');placeholder.value='';select.append(placeholder);
 for(const person of people){const option=node('option',person.name);option.value=person.id;select.append(option);}
 select.value=people.some(person=>person.id===sourcePersona)?sourcePersona:'';
 const hint=node('p',select.value?'已选中进入人格库时的人物，请核对名称后确认。':'请选择要绑定的人物，不会自动绑定列表中的第一个人物。','muted');
 const confirm=node('button','确认绑定','primary'),recheck=node('button','核对绑定状态');
 confirm.type=recheck.type='button';let busy=false,denied=false,finished=false;
 const valid=()=>m.active()&&current();
 function buttons(){select.disabled=busy;confirm.disabled=busy||denied;recheck.disabled=busy||denied;}
 function matches(person,target){const v=person?.versions?.find(version=>version.id===person.published_id);return person?.id===target&&person.project_id===workspace&&person.library===false&&v?.status==='APPROVED'&&v.content?.binding?.personality_id===personalityId&&v.content.binding.version_id===versionId;}
 async function success(person,target){
  if(!valid())return;finished=true;
  const link=node('a','返回 '+person.name+' 开始交流','primary');link.href=PersonalityPrefill.returnLink(workspace,target);
  m.body.replaceChildren(node('p','绑定已保存。返回人物后会重新读取当前版本；已有交流保持原版本。','muted'),link);
  try{await onBound?.();}catch(error){if(valid())m.body.append(node('p','绑定已保存，列表刷新失败。可返回人物重新读取。','muted'));}
 }
 async function perform(write){
  if(busy||denied||finished||!valid())return;
  const target=select.value;if(!people.some(person=>person.id===target)){status.textContent='请先选择要绑定的虚拟人。';return;}
  busy=true;buttons();status.textContent=write?'正在保存绑定…':'正在读取当前绑定…';
  try{
   const person=await api('/api/personas/'+encodeURIComponent(target)+(write?'/personality':''),write?{method:'PUT',body:{personality_id:personalityId,version_id:versionId}}:undefined);
   if(!valid())return;
   if(matches(person,target))await success(person,target);
   else status.textContent='尚未确认这个绑定。请核对所选人物，可重新读取状态或显式提交相同绑定。';
  }catch(error){
   if(!valid())return;
   denied=[401,403,404].includes(error.status);
   status.textContent=denied?'人物或当前权限已变化，请关闭此窗口并刷新人物列表。':(error.message||'绑定暂未确认。')+' 请先“核对绑定状态”；不会自动重发。';
  }finally{if(valid()){busy=false;buttons();}}
 }
 confirm.onclick=()=>perform(true);recheck.onclick=()=>perform(false);
 m.body.append(node('p','绑定版本：'+versionId+'。新交流使用本次绑定。','muted'),hint,select,confirm,recheck,status);
 return m;
}};
