/* Read-only polling; advancement is explicit and only available in the offline demo. */
(function(root){
 'use strict';
 function create({context,api,elements,escape}){
  let generation=0,key='',busy=false,loading=false,data=null;
  const current=()=>{const c=context();return c.active?`${c.epoch}:${c.persona}`:'';};
  const valid=(read,scope)=>read===generation&&scope&&current()===scope;
  function reset(){generation++;key='';busy=false;loading=false;data=null;elements.items.innerHTML='';elements.status.textContent='外部同步关闭，SQL 记忆仍可使用。';elements.refresh.disabled=false;}
  function render(){
   const offline=data?.mode==='OFFLINE_SIMULATION'&&data.real_provider_enabled===false;
   elements.status.textContent=offline?'仅离线模拟：明确未执行的失败可创建重试；未知结果先核对，不盲目重发。':'仅本地记录：同步消费者关闭，未连接外部服务。';
   elements.items.innerHTML=(data?.items||[]).map(item=>{
    let text={PENDING:offline?'等待离线模拟处理':'已本地记录，消费者关闭',DISPATCHED:'结果待核对，不会盲目重发',UNKNOWN:'结果未知，请核对',FAILED:'离线模拟确认未执行',ACKED:item.receipt_mode==='OFFLINE_SIMULATION'?'离线模拟完成（非真实同步）':'旧测试结果，未证明同步',SUPERSEDED:'已被新目标替代'}[item.state]||'状态待核对';
    const advance=offline&&['PENDING','DISPATCHED','UNKNOWN'].includes(item.state),retry=offline&&item.state==='FAILED'&&item.receipt_outcome==='NOT_APPLIED'&&item.receipt_mode==='OFFLINE_SIMULATION';
    return `<div class="memory"><strong>${item.kind==='DELETE'?'遗忘 / 撤回的清理记录':'确认 / 纠错记录'} · ${escape(String(item.memory_id).slice(0,8))}</strong><p>${text}</p><p class="muted small">修订 ${escape(item.revision)}${item.blocked_by_unresolved_operation?' · 旧操作仍未核清':''}</p>${advance?`<button data-memory-sync="advance" data-id="${escape(item.memory_id)}" ${busy?'disabled':''}>核对并继续离线模拟</button>`:''}${retry?`<button data-memory-sync="retry" data-id="${escape(item.memory_id)}" ${busy?'disabled':''}>为已确认未执行的失败创建重试</button>`:''}</div>`;
   }).join('')||'<p class="muted small">尚无同步目标。确认、纠错或遗忘后可在这里核对。</p>';
   elements.refresh.disabled=busy;
  }
  async function update(){
   const scope=current();if(!scope){if(key)reset();return;}
   if(key!==scope){reset();key=scope;}if(busy||loading)return;
   loading=true;const read=generation,c=context();
   try{const result=await api(`/api/personas/${c.persona}/memory-sync`);if(valid(read,scope)){data=result;render();}}
   catch(error){if(valid(read,scope)){data=null;elements.items.innerHTML='';elements.status.textContent='同步状态无法核对或权限已变化；未自动重试任何操作。';}}
   finally{if(read===generation)loading=false;}
  }
  elements.refresh.onclick=()=>update();
  elements.items.onclick=async event=>{
   const button=event.target.closest('[data-memory-sync]');if(!button||button.disabled||busy||data?.mode!=='OFFLINE_SIMULATION'||data.real_provider_enabled!==false)return;
   const scope=key,read=++generation,c=context();if(!scope||current()!==scope)return;
   busy=true;loading=false;render();
   try{
    const result=await api(`/api/personas/${c.persona}/memories/${encodeURIComponent(button.dataset.id)}/sync/${button.dataset.memorySync}`,{});
    if(valid(read,scope)){data=result;render();}
   }catch(error){if(valid(read,scope)){data=null;elements.items.innerHTML='';elements.status.textContent='操作结果待核对，请刷新状态；不会自动重发或生成新的重试。';}}
   finally{if(valid(read,scope)){busy=false;if(data)render();else elements.refresh.disabled=false;await update();}}
  };
  return {update,reset};
 }
 root.PersonaMemorySyncFactory={create};
 if(typeof document!=='undefined'){
  const el=id=>document.getElementById(id);
  root.PersonaMemorySync=create({context:()=>({active:!!state.token&&!state.pageHidden&&!!state.persona&&state.view==='chat',epoch:state.epoch,persona:state.persona?.id}),api,escape:escapeHtml,
   elements:{status:el('memory-sync-status'),items:el('memory-sync-items'),refresh:el('memory-sync-refresh')}});
  setInterval(()=>root.PersonaMemorySync.update(),2000);
  window.addEventListener('pagehide',()=>root.PersonaMemorySync.reset());
  el('logout').addEventListener('click',()=>root.PersonaMemorySync.reset());
 }
})(globalThis);
