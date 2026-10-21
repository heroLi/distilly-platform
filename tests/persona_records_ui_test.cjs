const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const base='server/src/main/resources/static/';
function setup(){
 const elements=Object.fromEntries(['form','question','submit','recheck','clear','status','answer','last-question','cards'].map(id=>['record-'+id,{value:'',textContent:'',innerHTML:'',disabled:false}]));
 const state={token:'synthetic-token',epoch:1,project:{id:'A'},persona:{id:'person-A'}};
 const requests=[],timers=new Map();let nextTimer=0,shown=true;
 const c=vm.createContext({window:{},setInterval(){throw Error('uncontrolled interval');},clearInterval(){}});
 vm.runInContext(fs.readFileSync(base+'ui-read-scope.js','utf8'),c);
 vm.runInContext(fs.readFileSync(base+'persona-records.js','utf8'),c);
 const reads=c.window.UiReadScope.create(()=>[state.token,state.epoch,state.project?.id,state.persona?.id]);
 const ui=c.window.PersonaRecordsUI.create({getState:()=>state,reads,request:(path,body)=>new Promise((resolve,reject)=>requests.push({path,body,resolve,reject})),element:id=>elements[id],escape:value=>String(value??'').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch])),setTimer:fn=>{timers.set(++nextTimer,fn);return nextTimer;},clearTimer:id=>timers.delete(id),visible:()=>shown});
 const el=id=>elements['record-'+id];
 const submit=question=>{el('question').value=question;return ui.submit({preventDefault(){}});};
 const response=(question='first',text='old record',extra={})=>({contract:'RECORD_CARDS_V1',persona_id:state.persona?.id,persona_version_id:'persona-v1',question,answer:'请按资料记录核对。',record_cards:[{id:'record-1',profile_id:'profile-1',title:'合成资料',version_id:'version-1',section:'安排',text,origin:'published_workspace_record',label:'已审核工作资料，不证明人物亲历'}],model_calls:0,...extra});
 return {c,elements,state,ui,reads,el,requests,timers,submit,response,show:value=>shown=value};
}
(async()=>{
 let checks=0;
 // No automatic initial request, and no chat/job side effects are supplied to the module.
 let h=setup();assert.equal(h.requests.length,0);assert.equal(h.timers.size,0);assert.equal(h.el('submit').disabled,false);checks++;
 // Actual module submission and rendering, including text/attribute escaping.
 let p=h.submit('first');assert.equal(h.requests.length,1);assert.equal(h.requests[0].path,'/api/personas/person-A/record-questions');assert.deepEqual(Object.keys(h.requests[0].body),['question']);
 await h.ui.submit();assert.equal(h.requests.length,1,'duplicate submission blocked');
 const payload=h.response('first','<img src=x onerror=alert(1)>');payload.record_cards[0].id='" onmouseover="bad';payload.record_cards[0].title='<script>bad</script>';payload.answer='<b>plain text answer</b>';
 h.requests[0].resolve(payload);await p;
 assert.match(h.el('cards').innerHTML,/&lt;img/);assert.match(h.el('cards').innerHTML,/&lt;script&gt;/);assert.match(h.el('cards').innerHTML,/data-record-id="&quot; onmouseover=&quot;bad"/);assert.doesNotMatch(h.el('cards').innerHTML,/<(?:img|script)/);assert.equal(h.el('answer').textContent,payload.answer);assert.equal(h.timers.size,1);checks++;
 // Recheck last submitted question, keep a newly edited draft, and replace corrected records.
 h.el('question').value='new unsent draft';p=h.ui.recheck();assert.equal(h.requests[1].body.question,'first');assert.equal(h.el('cards').innerHTML,'');h.requests[1].resolve(h.response('first','corrected record'));await p;
 assert.match(h.el('cards').innerHTML,/corrected record/);assert.doesNotMatch(h.el('cards').innerHTML,/old record/);assert.equal(h.el('question').value,'new unsent draft');assert.equal(h.el('last-question').textContent,'已核对问题：first');checks++;
 // Polling never overlaps and respects visibility; withdrawal empties the record snapshot.
 h.show(false);[...h.timers.values()][0]();assert.equal(h.requests.length,2);h.show(true);[...h.timers.values()][0]();[...h.timers.values()][0]();assert.equal(h.requests.length,3);h.requests[2].resolve(h.response('first','',{record_cards:[]}));await new Promise(setImmediate);assert.equal(h.el('cards').innerHTML,'');assert.match(h.el('status').textContent,/没有可用/);assert.equal(h.el('question').value,'new unsent draft');checks++;
 // Current access loss clears all evidence, stops refresh and preserves the draft.
 for(const status of [401,403,404]){h=setup();p=h.submit('first');h.requests[0].resolve(h.response());await p;h.el('question').value='draft after first';p=h.ui.recheck();h.requests[1].reject(Object.assign(Error('denied'),{status}));await p;assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('answer').textContent,'');assert.match(h.el('status').textContent,/已清除记录卡/);assert.equal(h.timers.size,0);assert.equal(h.el('question').value,'draft after first');assert.equal(h.el('recheck').disabled,true);checks++;}
 h=setup();p=h.submit('first');h.requests[0].reject(Error('current failure'));await p;assert.equal(h.el('status').textContent,'current failure');assert.equal(h.el('submit').disabled,false);checks++;
 // Same-context clear/new question: neither an obsolete success nor an error wins.
 for(const outcome of ['resolve','reject']){h=setup();const old=h.submit('first');h.ui.reset();p=h.submit('second');h.requests[1].resolve(h.response('second','newest record'));await p;const snapshot=h.el('cards').innerHTML;h.requests[0][outcome](outcome==='resolve'?h.response('first','obsolete'):Error('obsolete error'));await old;assert.equal(h.el('cards').innerHTML,snapshot);assert.match(h.el('status').textContent,/已核对/);checks++;}
 // Workspace/persona switches, including A-B-A, invalidate success and failure.
 for(const outcome of ['resolve','reject']){h=setup();const old=h.submit('first');h.state.epoch++;h.state.project={id:'B'};h.state.persona={id:'person-B'};h.ui.update();assert.equal(h.el('question').value,'');h.state.epoch++;h.state.project={id:'A'};h.state.persona={id:'person-A'};h.ui.update();p=h.submit('second');h.requests[1].resolve(h.response('second','A after B'));await p;h.requests[0][outcome](outcome==='resolve'?h.response('first','before switch'):Error('old scope failure'));await old;assert.match(h.el('cards').innerHTML,/A after B/);assert.doesNotMatch(h.el('cards').innerHTML,/before switch/);checks++;}
 // Logout, login identity reset, page exit all use reset(true): pending results cannot revive UI.
 for(const ending of ['logout','identity-reset','pagehide'])for(const outcome of ['resolve','reject']){h=setup();p=h.submit('first');h.ui.reset(true);if(ending==='logout'){h.state.token='';h.state.epoch++;}if(ending==='identity-reset'){h.state.token='another-synthetic-token';h.state.epoch++;}h.requests[0][outcome](outcome==='resolve'?h.response('first','late after exit'):Error('late error'));await p;assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('question').value,'');assert.equal(h.timers.size,0);assert.equal(h.el('submit').disabled,true);checks++;}
 // Latest studio read slot can supersede a pending module read.
 h=setup();p=h.submit('first');await h.reads.read('record-questions',async()=>({newer:true}));h.requests[0].resolve(h.response());await p;assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('submit').disabled,false);checks++;
 // Reject a mismatched/untrusted response; never render it as reviewed evidence.
 for(const extra of [{persona_id:'other-person'},{model_calls:1},{contract:'OTHER'},{record_cards:[{origin:'unreviewed',text:'unsafe'}]}]){h=setup();p=h.submit('first');h.requests[0].resolve(h.response('first','',extra));await p;assert.equal(h.el('cards').innerHTML,'');assert.match(h.el('status').textContent,/未通过核对/);checks++;}
 // Explicit clear forgets in-memory question and stops periodic refresh; validation sends nothing.
 h=setup();p=h.submit('first');h.requests[0].resolve(h.response());await p;h.el('clear').onclick();await h.ui.recheck();assert.equal(h.requests.length,1);assert.equal(h.timers.size,0);assert.equal(h.el('question').value,'');await h.submit('   ');await h.submit('x'.repeat(4001));assert.equal(h.requests.length,1);checks++;
 // Run actual studio integration handlers with the real record UI, not source assertions.
 const studio=fs.readFileSync(base+'persona.js','utf8');
 function integrate(h){
  const events={};h.c.window.PersonaRecords=h.ui;h.c.window.addEventListener=(name,fn)=>events[name]=fn;
  Object.assign(h.c,{state:h.state,studioReads:h.reads,$:id=>h.elements[id]||(h.elements[id]={innerHTML:'',hidden:false}),stopSpeech(){},recognition:null,document:{querySelectorAll:()=>[]},renderPersona:()=>h.ui.update(),clearInterval(){}});
  for(const name of ['loadMaterials','loadConversations','loadMemories','loadJobs','loadKnowledge','loadPersonalityBindings'])h.c[name]=async()=>{};
  vm.runInContext(studio.split('\n').find(line=>line.startsWith('async function selectPersona(')),h.c);
  for(const name of ['pagehide','pageshow'])vm.runInContext(studio.split('\n').find(line=>line.startsWith("window.addEventListener('"+name+"'")),h.c);
  return events;
 }
 // Re-selecting the same persona must resume after selection; old responses remain isolated.
 for(const outcome of ['resolve','reject']){
  h=setup();integrate(h);const old=h.submit('first');let resolvePersona;
  h.c.api=()=>new Promise(resolve=>resolvePersona=resolve);
  const selecting=h.c.selectPersona('person-A');assert.equal(h.el('submit').disabled,true);
  resolvePersona({id:'person-A'});await selecting;assert.equal(h.el('submit').disabled,false,'same-person reload resumes records');
  p=h.submit('second');h.requests[1].resolve(h.response('second','after same-person reload'));await p;
  h.requests[0][outcome](outcome==='resolve'?h.response('first','obsolete selection'):Error('obsolete selection failure'));await old;
  assert.match(h.el('cards').innerHTML,/after same-person reload/);assert.equal(h.el('submit').disabled,false);checks++;
 }
 // Real page lifecycle: normal pageshow preserves the draft; persisted pageshow resumes a cleared view.
 for(const outcome of ['resolve','reject']){
  h=setup();const events=integrate(h);h.el('question').value='initial draft';events.pageshow({persisted:false});assert.equal(h.el('question').value,'initial draft');
  const old=h.submit('first');events.pagehide();assert.equal(h.el('submit').disabled,true);assert.equal(h.el('question').value,'');
  events.pageshow({persisted:false});assert.equal(h.el('submit').disabled,true);
  events.pageshow({persisted:true});assert.equal(h.el('submit').disabled,false);assert.equal(h.requests.length,1,'restoration does not auto-submit');
  p=h.submit('second');h.requests[1].resolve(h.response('second','after cached-page restoration'));await p;
  h.requests[0][outcome](outcome==='resolve'?h.response('first','pre-exit record'):Error('pre-exit failure'));await old;
  assert.match(h.el('cards').innerHTML,/after cached-page restoration/);assert.equal(h.el('submit').disabled,false);checks++;
 }
 console.log(`PASS: ${checks} actual record-card UI scenarios (safe rendering, current/stale errors, ABA, exit, polling, correction/withdrawal, draft retention and contract validation)`);
})().catch(error=>{console.error(error);process.exitCode=1;});
