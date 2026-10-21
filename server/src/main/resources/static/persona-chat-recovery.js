'use strict';
// Current-conversation controls reuse the existing task API. No automatic writes.
window.PersonaChatRecovery={create({getContext,api,apply,onDenied,elements,errors={}}){
 const {status,stop,retry,refresh}=elements;
 let revision=0,pending=null,blocked=false,uncertain=null,feedback='',shownKey=null;
 const key=()=>{const c=getContext();return JSON.stringify([c.epoch,c.nonce,c.personaId,c.session?.id,c.active]);};
 const current=op=>op.revision===revision&&op.key===key()&&getContext().active;
 function task(){const c=getContext().session;return c?.active_job?.[0]||c?.latest_chat_job;}
 function render(){
  if(shownKey!==key()){shownKey=key();feedback='';}
  const c=getContext(),j=task(),busy=pending&&current(pending),unknown=uncertain===key();
  stop.hidden=!j||!['QUEUED','RUNNING'].includes(j.status);retry.hidden=j?.status!=='FAILED';
  for(const button of [stop,retry,refresh])button.disabled=!!busy||blocked||!c.active||!c.session;
  if(unknown){stop.disabled=true;retry.disabled=true;}
  status.textContent=blocked?'当前交流已不可访问，请刷新人物列表或重新登录。':busy?'正在核对回应状态…':unknown?'操作结果尚未确认。请先刷新回应状态；不会自动重发。':feedback;
  if(!status.textContent&&c.active&&c.session){
   if(j?.status==='QUEUED')status.textContent='回应排队中，尚未确认后台领取；可以停止本次回应或刷新状态。';
   if(j?.status==='RUNNING')status.textContent='正在回应，可以停止本次回应。';
   if(j?.status==='FAILED')status.textContent='本次回应失败：'+(errors[j.error_code]||'生成未完成。')+' 可显式重试这次回应，原消息不会重复发送。';
   if(j?.status==='CANCELLED')status.textContent='本次回应已停止。可以发送新消息；停止前的临时内容不作为完成回答。';
  }
 }
 function deny(){blocked=true;feedback='';uncertain=null;onDenied();render();}
 async function read(op){
  const c=await api('/api/conversations/'+encodeURIComponent(op.sessionId));
  if(!current(op))return;
  if(c.id!==op.sessionId||c.persona_id!==op.personaId)throw Error('交流状态不匹配，请重新选择交流。');
  uncertain=null;feedback='';apply(c);render();
 }
 async function perform(action){
  const c=getContext(),j=task();
  if(blocked||!c.active||!c.session||(pending&&current(pending)))return;
  if(action&&uncertain===key())return;
  if(action==='cancel'&&!['QUEUED','RUNNING'].includes(j?.status))return;
  if(action==='retry'&&j?.status!=='FAILED')return;
  const op={revision,key:key(),sessionId:c.session.id,personaId:c.personaId};pending=op;feedback='';render();
  try{
   if(action)await api('/api/persona-jobs/'+encodeURIComponent(j.id)+'/'+action,{});
   if(current(op))await read(op);
  }catch(error){
   if(!current(op))return;
   if([401,403,404].includes(error.status))deny();
   else if(action)uncertain=op.key;
   else feedback='回应状态暂时无法读取，请再次点击刷新。';
  }finally{if(pending===op)pending=null;if(current(op)||blocked)render();}
 }
 stop.onclick=()=>perform('cancel');retry.onclick=()=>perform('retry');refresh.onclick=()=>perform();
 return {render,reset(){revision++;pending=null;blocked=false;uncertain=null;feedback='';render();},deny};
}};
