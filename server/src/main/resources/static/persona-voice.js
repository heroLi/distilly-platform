/* Mock clone lifecycle. Shares the existing studio scope; never reads microphone/files. */
(function(root){
 'use strict';
 function create({context,api,elements,escape}){
  let generation=0,scope='',busy=false,loading=false,profiles=[],pendingCreate=null,previews=new Map(),selected=null;
  const labels={QUEUED:'排队中',RUNNING:'模拟处理中',SUCCEEDED:'已完成',FAILED:'失败',CANCELLED:'已停止',ACTIVE:'授权有效',REVOKED:'授权已撤销',DELETED:'已删除'};
  function stop(){elements.audio.pause();elements.audio.removeAttribute('src');elements.audio.load();elements.audio.hidden=true;selected=null;}
  function reset(){generation++;stop();scope='';busy=false;loading=false;profiles=[];pendingCreate=null;previews.clear();elements.profiles.innerHTML='';elements.name.value='';elements.consent.checked=false;elements.name.disabled=false;elements.status.textContent='';}
  function valid(read,key){const c=context();return read===generation&&c.active&&c.edit&&`${c.epoch}:${c.persona}`===key;}
  function render(){
   elements.profiles.innerHTML=profiles.map(p=>{
    const clone=p.jobs.find(j=>j.kind==='VOICE_CLONE'),active=p.authorization_status==='ACTIVE',ready=clone?.status==='SUCCEEDED';
    return `<div class="memory"><strong>${escape(p.name||'已删除档案')}</strong><p>${labels[p.authorization_status]||'未知状态'} · 模拟创建：${labels[clone?.status]||'待核对'}</p><p class="muted small">仅合成模拟 · 授权 ${escape(p.authorization_id)} · 不代表真人同意或音色质量</p><div class="actions"><button data-clone="preview" data-id="${p.id}" ${!active||!ready||busy?'disabled':''}>生成测试音试听</button><button data-clone="revoke" data-id="${p.id}" ${!active||busy?'disabled':''}>撤销授权</button><button data-clone="delete" data-id="${p.id}" ${busy?'disabled':''}>删除档案</button></div></div>`;
   }).join('')||'<p class="muted">还没有你的模拟克隆档案。先填写合成名称并确认模拟用途。</p>';
   elements.create.disabled=busy;elements.refresh.disabled=busy;
  }
  async function update(){
   const c=context(),key=`${c.epoch}:${c.persona}`;
   elements.panel.hidden=!c.edit;
   if(!c.active||!c.edit){if(scope)reset();return;}
   if(scope!==key){reset();scope=key;}
   if(loading||busy)return;loading=true;const read=generation;
   try{
    const list=await api(`/api/personas/${c.persona}/voice-profiles`);
    if(!valid(read,key))return;
    profiles=list;render();
    if(selected){
     const p=list.find(p=>p.id===selected.profile),j=p?.jobs.find(j=>j.id===selected.job);
     if(!p||p.authorization_status!=='ACTIVE'||!j||['FAILED','CANCELLED'].includes(j.status)){stop();elements.status.textContent='试听已停止，请核对授权或任务状态。';}
     else if(j.status==='SUCCEEDED'&&j.result?.synthetic===true&&j.result.provider==='mock'){
      if(elements.audio.hidden){elements.audio.src=j.result.audio;elements.audio.hidden=false;elements.status.textContent='模拟测试音已就绪，请手动播放；这不是克隆语音。';}
     }
    }
   }catch(error){if(valid(read,key)){stop();profiles=[];render();elements.status.textContent='无法核对档案或权限已变化，已停止试听。请主动刷新。';}}
   finally{if(read===generation)loading=false;}
  }
  async function action(operation){
   const c=context(),key=scope,read=++generation;
   if(!c.active||!c.edit||!key)return;
   busy=true;loading=false;render();
   try{await operation(c,read,key);}
   catch(error){if(valid(read,key)){stop();profiles=[];elements.status.textContent=error.status===403?'当前权限已变化，请刷新核对。':'操作结果未确认，请核对状态；再次点击将复用本次操作，不自动创建新任务。';}}
   finally{if(valid(read,key)){busy=false;render();await update();}}
  }
  elements.form.onsubmit=event=>{event.preventDefault();if(busy)return;
   if(!pendingCreate){if(!elements.consent.checked||!elements.name.value.trim())return;pendingCreate={name:elements.name.value.trim(),synthetic_consent:true,request_id:root.crypto.randomUUID()};}
   elements.name.disabled=true;
   return action(async(c,read,key)=>{
    await api(`/api/personas/${c.persona}/voice-profiles`,pendingCreate);
    if(!valid(read,key))return;
    pendingCreate=null;elements.name.disabled=false;elements.name.value='';elements.consent.checked=false;elements.status.textContent='模拟档案已提交。等待后台创建；刷新不会重复提交。';
   });
  };
  elements.profiles.onclick=event=>{
   const b=event.target.closest('[data-clone]');if(!b||b.disabled||busy)return;
   const id=b.dataset.id,op=b.dataset.clone;stop();
   return action(async(c,read,key)=>{
    const path=`/api/personas/${c.persona}/voice-profiles/${id}`;
    if(op==='preview'){
     if(!previews.has(id))previews.set(id,{request_id:root.crypto.randomUUID()});
     const job=await api(path+'/preview',previews.get(id));
     if(valid(read,key)){previews.delete(id);selected={profile:id,job:job.id};elements.status.textContent='正在生成合成测试音，不会自动播放。';}
    }else{
     await api(path+(op==='revoke'?'/revoke':''),op==='revoke'?{}:undefined,op==='revoke'?'POST':'DELETE');
     if(valid(read,key)){previews.delete(id);profiles=op==='delete'?profiles.filter(p=>p.id!==id):profiles.map(p=>p.id===id?{...p,authorization_status:'REVOKED',jobs:[]}:p);elements.status.textContent=op==='revoke'?'授权已撤销，相关任务与缓存试听已清除。':'档案已删除，名称与缓存试听已清除。';}
    }
   });
  };
  elements.refresh.onclick=()=>{stop();return update();};
  return {update,reset};
 }
 root.PersonaVoiceFactory={create};
 if(typeof document!=='undefined'){
  const el=id=>document.getElementById(id);
  root.PersonaVoice=create({context:()=>({active:!!state.token&&!state.pageHidden&&!!state.persona&&state.view==='media',edit:canEdit(),persona:state.persona?.id,epoch:state.epoch}),api,escape:escapeHtml,
   elements:{panel:el('clone-panel'),form:el('clone-form'),name:el('clone-name'),consent:el('clone-consent'),create:el('clone-create'),refresh:el('clone-refresh'),profiles:el('clone-profiles'),status:el('clone-status'),audio:el('clone-audio')}});
  setInterval(()=>root.PersonaVoice.update(),2000);
  window.addEventListener('pagehide',()=>root.PersonaVoice.reset());
  el('logout').addEventListener('click',()=>root.PersonaVoice.reset());
 }
})(globalThis);
