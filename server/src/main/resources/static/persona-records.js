'use strict';
window.PersonaRecordsUI={create({getState,reads,request,element,escape,setTimer=setInterval,clearTimer=clearInterval,visible=()=>true}){
 const el=id=>element('record-'+id);
 let identity='',generation=0,busy=false,lastQuestion='',lastFilters={},timer=null,suspended=false;
 const context=()=>{const s=getState();return JSON.stringify([s.token,s.epoch,s.project?.id,s.persona?.id]);};
 const ready=()=>{const s=getState();return !!(!suspended&&s.token&&s.project&&s.persona);};
 const questionError='请输入 1 至 4000 字的问题。',dateError='日期需同时填写有效的起止日期（0001 至 9999 年），且起日不能晚于止日。',identifierError='编号每行一个，最多 5 个且不能重复；每个为 1 至 80 位英文字母、数字、下划线或短横线，首位须为英文字母或数字，不含空格，区分大小写。';
 const copyFilters=filters=>({...filters,...(filters.identifiers?{identifiers:[...filters.identifiers]}:{})});
 const hasDraft=()=>['question','date-from','date-to','identifiers'].some(id=>el(id).value);
 function buttons(){el('submit').disabled=busy||!ready();el('recheck').disabled=busy||!ready()||!lastQuestion;el('clear').disabled=!lastQuestion&&!hasDraft()&&!busy;}
 function stop(){if(timer!==null)clearTimer(timer);timer=null;}
 function clearView(){el('answer').textContent='';el('last-question').textContent='';el('applied-filters').textContent='';el('cards').innerHTML='';}
 function rejectDraft(message){if(busy)return;clearView();stop();el('status').textContent=message+' 本次未提交，已清除此前结果；修改后请点击“核对资料”。'+(lastQuestion?'也可点击“重新核对来源”，沿用上次已提交的问题和条件。':'');buttons();}
 function reset(disable=false){suspended=disable===true;generation++;busy=false;lastQuestion='';lastFilters={};stop();identity=context();for(const id of ['question','date-from','date-to','identifiers'])el(id).value='';clearView();el('status').textContent=ready()?'输入问题，核对当前已发布工作资料。':'请先选择人物。';buttons();}
 function update(){if(identity!==context())reset();else buttons();}
 function schedule(){if(timer===null)timer=setTimer(()=>{if(visible()&&lastQuestion&&!busy)recheck();},5000);}
 function validDate(value){
  if(!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value))return false;
  const [year,month,day]=value.split('-').map(Number),leap=year%4===0&&(year%100!==0||year%400===0);
  return year>=1&&month>=1&&month<=12&&day>=1&&day<=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31][month-1];
 }
 function draftFilters(){
  const from=el('date-from').value,to=el('date-to').value;
  if((from||to)&&(!validDate(from)||!validDate(to)||from>to))throw new Error(dateError);
  const raw=el('identifiers').value,identifiers=raw.split(/\r?\n/).filter(line=>line!=='');
  if(raw.length>404||identifiers.length>5||identifiers.some(id=>!/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(id))||new Set(identifiers).size!==identifiers.length)throw new Error(identifierError);
  return {...(from?{date_from:from,date_to:to}:{}),...(identifiers.length?{identifiers}:{})};
 }
 function sameFilters(actual,expected){
  if(!actual||typeof actual!=='object'||Array.isArray(actual))return false;
  const keys=Object.keys(expected);if(Object.keys(actual).length!==keys.length)return false;
  return keys.every(key=>key==='identifiers'?Array.isArray(actual[key])&&actual[key].length===expected[key].length&&actual[key].every((id,i)=>id===expected[key][i]):actual[key]===expected[key]);
 }
 function valid(data,persona,question,filters){
  const hasFilters=Object.keys(filters).length>0,hasMeta=data?.filter_contract!==undefined||data?.applied_filters!==undefined;
  return data?.contract==='RECORD_CARDS_V1'&&data.persona_id===persona&&data.question===question&&data.model_calls===0&&typeof data.answer==='string'&&Array.isArray(data.record_cards)&&data.record_cards.every(c=>c&&c.origin==='published_workspace_record'&&['id','profile_id','title','version_id','section','text','label'].every(k=>typeof c[k]==='string'))&&(!(hasFilters||hasMeta)||(data.filter_contract==='RECORD_LITERAL_FILTERS_V1'&&sameFilters(data.applied_filters,filters)));
 }
 function describeFilters(filters){return '本次已应用条件：'+(Object.keys(filters).length?[filters.date_from?'正文日期 '+filters.date_from+' 至 '+filters.date_to+'（含两端）':'',filters.identifiers?'正文精确编号 '+filters.identifiers.join('、'):''].filter(Boolean).join('；'):'未启用正文日期／编号筛选')+'。';}
 async function load(question,submittedFilters){
  update();if(busy||!ready())return;
  const filters=copyFilters(submittedFilters),s=getState(),persona=s.persona.id,scopeIdentity=identity,run=++generation;
  const current=()=>run===generation&&scopeIdentity===context();
  lastQuestion=question;lastFilters=copyFilters(filters);busy=true;el('status').textContent='正在核对当前已发布资料…';buttons();clearView();
  try{
   const body={question,...(Object.keys(filters).length?{filters:copyFilters(filters)}:{})};
   const result=await reads.read('record-questions',()=>request('/api/personas/'+encodeURIComponent(persona)+'/record-questions',body),current);
   if(!result||!current())return;
   const data=result.value;if(!valid(data,persona,question,filters))throw new Error('资料响应未通过核对，请重试。');
   el('last-question').textContent='已核对问题：'+question;el('applied-filters').textContent=describeFilters(filters);el('answer').textContent=data.answer;
   el('cards').innerHTML=data.record_cards.map(c=>`<article class="record-card" data-record-id="${escape(c.id)}"><h3>${escape(c.title)}</h3><p class="record-label">${escape(c.label)}</p><p class="muted small">${escape(c.section)} · 资料版本 ${escape(c.version_id)}</p><p class="record-text">${escape(c.text)}</p></article>`).join('');
   el('status').textContent=data.record_cards.length?'已核对 '+data.record_cards.length+' 条当前资料记录 · 模型调用 0 次':(Object.keys(filters).length?'当前没有同时符合问题和正文筛选条件的可用资料记录；未回退到其他资料。':'当前没有可用的已发布资料记录，请到虚拟经历台核对或纠错。');schedule();
  }catch(error){
   if(!current())return;
   clearView();
   const denied=error.status===401||error.status===403||error.status===404;
   if(denied){lastQuestion='';lastFilters={};stop();}
   const filterErrors={invalid_record_filters:'筛选条件无效，请核对正文日期范围和精确编号后重新提交。',invalid_record_date:'正文日期无效：请同时填写有效起止日期，起日不能晚于止日。',invalid_record_identifier:'正文编号无效：每行一个，最多 5 个且不可重复，请核对英文大小写和格式。'};
   el('status').textContent=denied?'当前资料不可访问，已清除记录卡。请核对登录、人物和资料授权。':(error.status===422&&filterErrors[error.code]||error.message||'资料核对失败，请重试。');
  }finally{if(current()){busy=false;buttons();}}
 }
 async function submit(event){
  event?.preventDefault();update();if(busy)return;
  const question=el('question').value.trim();if(!question||question.length>4000){rejectDraft(questionError);return;}
  let filters;try{filters=draftFilters();}catch(error){rejectDraft(error.message);return;}
  await load(question,filters);
 }
 async function recheck(){update();if(lastQuestion&&!busy)await load(lastQuestion,lastFilters);}
 el('form').onsubmit=submit;el('recheck').onclick=recheck;el('clear').onclick=()=>reset();for(const id of ['question','date-from','date-to','identifiers'])el(id).oninput=buttons;
 for(const [id,message] of [['question',questionError],['date-from',dateError],['date-to',dateError],['identifiers',identifierError]])el(id).oninvalid=()=>rejectDraft(message);
 reset();return {reset,update,submit,recheck};
}};
if(typeof state!=='undefined'&&typeof studioReads!=='undefined'){
 window.PersonaRecords=window.PersonaRecordsUI.create({getState:()=>state,reads:studioReads,request:api,element:$,escape:escapeHtml,visible:()=>!document.hidden&&state.view==='chat'});
}
