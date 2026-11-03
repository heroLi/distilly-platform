/* Explicit, ephemeral reference-only reads. All record text is rendered as text nodes. */
(function(root){
 'use strict';
 function create({context,api,elements:e,createElement=tag=>document.createElement(tag)}){
  let generation=0,key='',busy=false,lastQuery='',records=[],suspended=false;
  const scope=()=>{const c=context();return !suspended&&c.active&&c.persona?JSON.stringify([c.epoch,c.project,c.persona]):'';};
  const active=()=>!!scope()&&e.dialog.open;
  function buttons(){e.open.disabled=!scope();e.submit.disabled=busy||!active()||!e.query.value.trim();e.refresh.disabled=busy||!active()||!lastQuery;}
  function clear(){records=[];e.results.replaceChildren();e.applied.textContent='';e.error.textContent='';e.error.hidden=true;}
  function reset(){generation++;busy=false;lastQuery='';key=scope();e.query.value='';clear();e.status.textContent='输入关键词后，主动查阅当前记录。';if(e.dialog.open)e.dialog.close();buttons();}
  function update(){if(key!==scope())reset();else buttons();}
  function open(){update();if(!scope()||e.dialog.open)return;reset();e.dialog.showModal();buttons();e.query.focus();}
  function failure(message){clear();e.status.textContent='';e.error.textContent=message;e.error.hidden=false;buttons();}
  const exact=(value,keys)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(k=>Object.hasOwn(value,k));
  const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
  function valid(data){
   if(!exact(data,['contract','mode','support_status','notice','references'])||data.contract!=='MEMORY_REFERENCE_V1'||data.mode!=='SOURCE_REFERENCE'||data.support_status!=='NOT_ASSESSED'||typeof data.notice!=='string'||!Array.isArray(data.references)||data.references.length>12)return false;
   const seen=new Set();return data.references.every(r=>{
    if(!exact(r,['memory_id','source_message_id','record_text','record_fingerprint','quote_kind'])||!uuid(r.memory_id)||!uuid(r.source_message_id)||typeof r.record_text!=='string'||!r.record_text.length||typeof r.record_fingerprint!=='string'||!/^[0-9a-f]{64}$/.test(r.record_fingerprint)||r.quote_kind!=='CONFIRMED_MEMORY_RECORD'||seen.has(r.memory_id))return false;
    seen.add(r.memory_id);return true;
   });
  }
  function node(tag,text,className){const n=createElement(tag);if(text!==undefined)n.textContent=text;if(className)n.className=className;return n;}
  function render(data,query){
   records=data.references.map(r=>({...r}));e.applied.textContent='本次查询：'+query;
   e.results.replaceChildren(...records.map(r=>{
    const card=node('article',undefined,'record-card memory-reference-card');
    card.append(node('h3','已确认记忆记录'),node('p','支持关系未判定 · 当前记忆正文','record-label'),
     node('p','记录 '+r.memory_id+' · 内容指纹 '+r.record_fingerprint.slice(0,12),'muted small'),node('pre',r.record_text,'memory-reference-text'));
    return card;
   }));
   e.status.textContent=records.length?'查阅到 '+records.length+' 条记录。这是一次读取快照，记录变更后请重新核对。':'没有匹配的已确认记忆记录；未匹配不表示从未发生。可调整关键词或先确认记忆。';
  }
  async function load(query){
   update();if(busy||!active())return;
   const selected=context().persona,identity=key,read=++generation;
   const current=()=>read===generation&&identity===scope()&&e.dialog.open;
   busy=true;lastQuery=query;clear();e.status.textContent='正在核对当前已确认记忆记录…';buttons();
   try{
    const data=await api('/api/personas/'+encodeURIComponent(selected)+'/memory-references',{mode:'SOURCE_REFERENCE',query});
    if(!current()){update();return;}
    if(!valid(data)){failure('参考响应未通过核对，未展示任何记录。请重新核对。');return;}
    render(data,query);
   }catch(error){
    if(!current()){update();return;}
    if([401,403,404].includes(error.status)){lastQuery='';e.query.value='';failure('当前人物或记忆不可访问，已清空参考和查询。请核对登录、人物与权限。');}
    else failure(error.status===409?'来源记录已变化或不可用，旧参考已清除。请重新核对。':'本次查阅未完成，旧参考已清除。请检查连接后重新核对。');
   }finally{if(current()){busy=false;buttons();}}
  }
  async function submit(event){event?.preventDefault();update();if(busy||!active())return;const q=e.query.value.trim();if(!q||q.length>4000){lastQuery='';failure('请输入 1 至 4000 字的查询关键词。');return;}await load(q);}
  async function refresh(){update();if(!busy&&lastQuery&&e.query.value.trim()===lastQuery)await load(lastQuery);}
  function invalidate(message='已确认记忆记录已变化，旧参考已清除。请点击“重新核对”读取当前记录。'){update();if(!active())return;generation++;busy=false;clear();e.status.textContent=message;buttons();}
  function observe(list){update();if(!active())return;if(busy){invalidate('记忆列表已重新加载，进行中的参考快照已清除。请点击“重新核对”读取当前记录。');return;}if(records.length&&(!Array.isArray(list)||records.some(r=>!list.some(m=>m.id===r.memory_id&&m.status==='CONFIRMED'&&m.body===r.record_text&&m.source_id===r.source_message_id))))invalidate();}
  e.open.onclick=open;e.close.onclick=reset;e.form.onsubmit=submit;e.refresh.onclick=refresh;
  e.query.oninput=()=>{generation++;busy=false;lastQuery='';clear();e.status.textContent='修改查询后，请点击“查阅原文”。';buttons();};
  e.query.oninvalid=()=>{lastQuery='';failure('请输入 1 至 4000 字的查询关键词。');};
  e.dialog.addEventListener('cancel',event=>{event.preventDefault();reset();});e.dialog.addEventListener('close',()=>{if(!e.dialog.open)reset();});
  reset();return {open,submit,refresh,reset,update,invalidate,observe,suspend(){suspended=true;reset();},ready(){suspended=false;update();}};
 }
 root.PersonaMemoryReferenceFactory={create};
 if(typeof document!=='undefined'){
  const el=id=>document.getElementById('memory-reference-'+id);
  root.PersonaMemoryReferences=create({context:()=>({active:!!state.token&&!state.pageHidden&&state.view==='chat',epoch:state.epoch,project:state.project?.id,persona:state.persona?.id}),api,
   elements:Object.fromEntries(['open','dialog','close','form','query','submit','refresh','status','error','applied','results'].map(id=>[id,el(id)]))});
  window.addEventListener('pagehide',()=>root.PersonaMemoryReferences.reset());
  window.addEventListener('pageshow',()=>root.PersonaMemoryReferences.update());
  document.getElementById('logout').addEventListener('click',()=>root.PersonaMemoryReferences.reset());
 }
})(globalThis);
