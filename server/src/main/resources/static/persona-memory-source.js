/* Private source inspection; closing or leaving a scope invalidates late responses. */
(function(root){
 'use strict';
 function create({context,api,elements}){
  let generation=0;
  function reset(){generation++;elements.dialog.close();elements.body.textContent='';elements.meta.textContent='';}
  async function open(id){
   reset();const current=context(),read=generation;
   if(!current.active||!current.persona)return;
   elements.meta.textContent='正在核对来源…';elements.dialog.showModal();
   try{
    const source=await api(`/api/personas/${current.persona}/memories/${id}/source`);
    const now=context();if(read!==generation||!now.active||now.epoch!==current.epoch||now.persona!==current.persona)return;
    elements.meta.textContent=`来源交流：${source.title} · ${new Date(source.created_at).toLocaleString()} · 消息 ${source.message_id}`;
    elements.body.textContent=source.body;
   }catch(error){if(read===generation){elements.body.textContent='';elements.meta.textContent='来源暂不可用或权限已变化，请关闭后重新核对。';}}
  }
  elements.close.onclick=reset;elements.dialog.addEventListener('cancel',reset);
  return {open,reset};
 }
 root.PersonaMemorySourceFactory={create};
 if(typeof document!=='undefined'){
  const el=id=>document.getElementById(id);
  root.PersonaMemorySource=create({context:()=>({active:!!state.token&&!state.pageHidden,persona:state.persona?.id,epoch:state.epoch}),api,
   elements:{dialog:el('memory-source-dialog'),body:el('memory-source-body'),meta:el('memory-source-meta'),close:el('memory-source-close')}});
  window.addEventListener('pagehide',()=>root.PersonaMemorySource.reset());
  el('logout').addEventListener('click',()=>root.PersonaMemorySource.reset());
 }
})(globalThis);
