'use strict';
// An in-memory view of authorized published records. This never creates a chat or model job.
window.PersonaRecordsUI={create({getState,reads,request,element,escape,setTimer=setInterval,clearTimer=clearInterval,visible=()=>true}){
 const el=id=>element('record-'+id);
 let identity='',generation=0,busy=false,lastQuestion='',timer=null,suspended=false;
 const context=()=>{const s=getState();return JSON.stringify([s.token,s.epoch,s.project?.id,s.persona?.id]);};
 const ready=()=>{const s=getState();return !!(!suspended&&s.token&&s.project&&s.persona);};
 function buttons(){el('submit').disabled=busy||!ready();el('recheck').disabled=busy||!ready()||!lastQuestion;el('clear').disabled=!lastQuestion&&!el('question').value&&!busy;}
 function stop(){if(timer!==null)clearTimer(timer);timer=null;}
 function reset(disable=false){suspended=disable===true;generation++;busy=false;lastQuestion='';stop();identity=context();el('question').value='';el('answer').textContent='';el('last-question').textContent='';el('cards').innerHTML='';el('status').textContent=ready()?'输入问题，核对当前已发布工作资料。':'请先选择人物。';buttons();}
 function update(){if(identity!==context())reset();else buttons();}
 function schedule(){if(timer===null)timer=setTimer(()=>{if(visible()&&lastQuestion&&!busy)recheck();},5000);}
 function valid(data,persona,question){return data?.contract==='RECORD_CARDS_V1'&&data.persona_id===persona&&data.question===question&&data.model_calls===0&&typeof data.answer==='string'&&Array.isArray(data.record_cards)&&data.record_cards.every(c=>c&&c.origin==='published_workspace_record'&&['id','profile_id','title','version_id','section','text','label'].every(k=>typeof c[k]==='string'));}
 async function load(question){
  update();if(busy||!ready())return;
  const s=getState(),persona=s.persona.id,scopeIdentity=identity,run=++generation;
  const current=()=>run===generation&&scopeIdentity===context();
  busy=true;el('status').textContent='正在核对当前已发布资料…';buttons();
  // Clear previous evidence while a new question or source snapshot is unresolved.
  el('cards').innerHTML='';el('answer').textContent='';el('last-question').textContent='';
  try{
   const result=await reads.read('record-questions',()=>request('/api/personas/'+encodeURIComponent(persona)+'/record-questions',{question}),current);
   if(!result||!current())return;
   const data=result.value;if(!valid(data,persona,question))throw new Error('资料响应未通过核对，请重试。');
   lastQuestion=question;el('last-question').textContent='已核对问题：'+question;el('answer').textContent=data.answer;
   el('cards').innerHTML=data.record_cards.map(c=>`<article class="record-card" data-record-id="${escape(c.id)}"><h3>${escape(c.title)}</h3><p class="record-label">${escape(c.label)}</p><p class="muted small">${escape(c.section)} · 资料版本 ${escape(c.version_id)}</p><p class="record-text">${escape(c.text)}</p></article>`).join('');
   el('status').textContent=data.record_cards.length?'已核对 '+data.record_cards.length+' 条当前资料记录 · 模型调用 0 次':'当前没有可用的已发布资料记录，请到虚拟经历台核对或纠错。';schedule();
  }catch(error){
   if(!current())return;
   el('cards').innerHTML='';el('answer').textContent='';el('last-question').textContent='';
   const denied=error.status===401||error.status===403||error.status===404;
   if(denied){lastQuestion='';stop();}
   el('status').textContent=denied?'当前资料不可访问，已清除记录卡。请核对登录、人物和资料授权。':(error.message||'资料核对失败，请重试。');
  }finally{if(current()){busy=false;buttons();}}
 }
 async function submit(event){event?.preventDefault();update();if(busy)return;const question=el('question').value.trim();if(!question||question.length>4000){el('status').textContent='请输入 1 至 4000 字的问题。';return;}await load(question);}
 async function recheck(){update();if(lastQuestion&&!busy)await load(lastQuestion);}
 el('form').onsubmit=submit;el('recheck').onclick=recheck;el('clear').onclick=()=>reset();el('question').oninput=buttons;
 reset();return {reset,update,submit,recheck};
}};
if(typeof state!=='undefined'&&typeof studioReads!=='undefined'){
 window.PersonaRecords=window.PersonaRecordsUI.create({getState:()=>state,reads:studioReads,request:api,element:$,escape:escapeHtml,visible:()=>!document.hidden&&state.view==='chat'});
}
