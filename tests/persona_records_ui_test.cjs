const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const base='server/src/main/resources/static/';
function setup(){
 const elements=Object.fromEntries(['form','question','submit','recheck','clear','status','answer','last-question','cards','date-from','date-to','identifiers','applied-filters','filter-draft'].map(id=>['record-'+id,{value:'',textContent:'',innerHTML:'',disabled:false}]));
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
  Object.assign(h.c,{chatRecovery:null,chatSubmit:null,poll(){},setInterval:()=>1,loadSession:async()=>{},state:h.state,studioReads:h.reads,$:id=>h.elements[id]||(h.elements[id]={innerHTML:'',hidden:false}),stopSpeech(){},recognition:null,document:{querySelectorAll:()=>[]},renderPersona:()=>h.ui.update(),clearInterval(){}});
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

 // Explicit record-body filters: real UI request and exact echo, without changing card source checks.
 const asPlain=value=>JSON.parse(JSON.stringify(value));
 const filters={date_from:'2028-02-29',date_to:'2028-03-01',identifiers:['REQ-007','case_A']};
 function fillFilters(h,value=filters){h.el('date-from').value=value.date_from||'';h.el('date-to').value=value.date_to||'';h.el('identifiers').value=(value.identifiers||[]).join('\n');}
 function filteredResponse(h,question='filtered',value=filters,extra={}){return h.response(question,'synthetic filtered record',{filter_contract:'RECORD_LITERAL_FILTERS_V1',applied_filters:asPlain(value),...extra});}
 h=setup();fillFilters(h);p=h.submit('filtered');assert.deepEqual(asPlain(h.requests[0].body),{question:'filtered',filters});h.requests[0].resolve(filteredResponse(h));await p;
 assert.match(h.el('applied-filters').textContent,/2028-02-29 至 2028-03-01/);assert.match(h.el('applied-filters').textContent,/REQ-007、case_A/);assert.match(h.el('cards').innerHTML,/synthetic filtered record/);checks++;
 // Changed drafts never enter recheck or polling, even while in-flight input changes again.
 h.el('question').value='unsent question';fillFilters(h,{date_from:'2031-06-01',date_to:'2031-06-30',identifiers:['unsent-9']});
 p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[1].body),{question:'filtered',filters});assert.equal(h.el('applied-filters').textContent,'');assert.equal(h.el('cards').innerHTML,'');
 h.el('identifiers').value='another-draft';h.requests[1].resolve(filteredResponse(h));await p;
 assert.equal(h.el('question').value,'unsent question');assert.equal(h.el('date-from').value,'2031-06-01');assert.equal(h.el('identifiers').value,'another-draft');assert.match(h.el('applied-filters').textContent,/REQ-007/);assert.doesNotMatch(h.el('applied-filters').textContent,/unsent|2031|another/);checks++;
 [...h.timers.values()][0]();assert.deepEqual(asPlain(h.requests[2].body),{question:'filtered',filters});h.requests[2].resolve(filteredResponse(h,'filtered',filters,{record_cards:[]}));await new Promise(setImmediate);assert.match(h.el('status').textContent,/未回退/);assert.equal(h.el('cards').innerHTML,'');checks++;

 // Request/response object mutation cannot change the frozen submitted filter snapshot.
 h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].body.filters.identifiers[0]='mutated-request';
 const external=filteredResponse(h);h.requests[0].resolve(external);await p;external.applied_filters.identifiers[0]='mutated-response';
 p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[1].body),{question:'filtered',filters});h.requests[1].resolve(filteredResponse(h));await p;checks++;
 // After a failed new submission, retry retains that new question/filters rather than old success.
 h=setup();p=h.submit('first');h.requests[0].resolve(h.response());await p;fillFilters(h);p=h.submit('filtered');h.requests[1].reject(Error('temporary failure'));await p;
 h.el('question').value='another unsent draft';fillFilters(h,{identifiers:['unsent']});p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[2].body),{question:'filtered',filters});h.requests[2].resolve(filteredResponse(h));await p;assert.equal(h.el('question').value,'another unsent draft');assert.equal(h.el('identifiers').value,'unsent');checks++;
 // Client validation covers real Gregorian dates (including century exceptions), partial/reversed ranges,
 // strict case-sensitive ASCII identifiers, duplicates, limits and no hidden whitespace normalization.
 const invalidFilters=[
  {date_from:'2028-02-29'}, {date_to:'2028-02-29'},
  {date_from:'0000-01-01',date_to:'0001-01-01'}, {date_from:'10000-01-01',date_to:'10000-01-01'},
  {date_from:'2026-02-29',date_to:'2026-03-01'}, {date_from:'1900-02-29',date_to:'1900-03-01'},
  {date_from:'2026-04-31',date_to:'2026-05-01'}, {date_from:'2026-00-01',date_to:'2026-01-01'},
  {date_from:'2026-01-00',date_to:'2026-01-01'}, {date_from:'2026-1-01',date_to:'2026-01-01'},
  {date_from:'２０２６-01-01',date_to:'2026-01-01'}, {date_from:'2026-10-22',date_to:'2026-10-21'},
  {identifiers:['REQ-007','REQ-007']}, {identifiers:['ＲＥＱ-007']}, {identifiers:['REQ 007']},
  {identifiers:[' REQ-007']}, {identifiers:['REQ-007 ']}, {identifiers:['-REQ']},
  {identifiers:['id.1']}, {identifiers:['a'.repeat(81)]}, {identifiers:['a','b','c','d','e','f']}
 ];
 for(const value of invalidFilters){h=setup();fillFilters(h,value);await h.submit('filtered');assert.equal(h.requests.length,0,JSON.stringify(value));assert.match(h.el('status').textContent,/日期|编号/);assert.equal(h.el('submit').disabled,false);checks++;}
 for(const value of [{date_from:'0001-01-01',date_to:'9999-12-31'},{date_from:'2000-02-29',date_to:'2000-02-29'},{identifiers:['REQ-007','req-007','a','9','x'.repeat(80)]}]){h=setup();fillFilters(h,value);p=h.submit('filtered');assert.deepEqual(asPlain(h.requests[0].body.filters),value);h.requests[0].resolve(filteredResponse(h,'filtered',value));await p;assert.match(h.el('status').textContent,/已核对/);checks++;}
 // Empty fields omit filters; blank identifier lines do not change literal identifiers.
 h=setup();h.el('identifiers').value='\n\n';p=h.submit('first');assert.deepEqual(asPlain(h.requests[0].body),{question:'first'});h.requests[0].resolve(h.response('first','no filter',{filter_contract:'RECORD_LITERAL_FILTERS_V1',applied_filters:{}}));await p;assert.match(h.el('applied-filters').textContent,/未启用/);checks++;
 h=setup();h.el('identifiers').value='\nREQ-007\r\n\n';p=h.submit('filtered');assert.deepEqual(asPlain(h.requests[0].body.filters),{identifiers:['REQ-007']});h.requests[0].resolve(filteredResponse(h,'filtered',{identifiers:['REQ-007']}));await p;checks++;
 // Filtered requests reject legacy, omitted/malformed echo, wrong limits/order/case, extras and contract.
 const invalidEchoes=[
  {filter_contract:undefined,applied_filters:undefined}, {applied_filters:undefined},
  {filter_contract:undefined}, {filter_contract:'OTHER'}, {applied_filters:{}},
  {applied_filters:{...filters,date_to:'2028-03-02'}},
  {applied_filters:{...filters,identifiers:['REQ-007','CASE_A']}},
  {applied_filters:{...filters,identifiers:['case_A','REQ-007']}},
  {applied_filters:{...filters,extra:true}}, {applied_filters:[]},
  {applied_filters:{...filters,identifiers:'REQ-007'}},
  {question:'different question'}, {persona_id:'person-B'}, {model_calls:1}
 ];
 for(const extra of invalidEchoes){h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].resolve(filteredResponse(h,'filtered',filters,extra));await p;assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.match(h.el('status').textContent,/未通过核对/);checks++;}
 // No-filter requests can accept legacy responses, but cannot accept a filtered or partial modern echo.
 for(const extra of [{filter_contract:'RECORD_LITERAL_FILTERS_V1'}, {applied_filters:{}},{filter_contract:'RECORD_LITERAL_FILTERS_V1',applied_filters:{identifiers:['REQ-007']}}]){h=setup();p=h.submit('first');h.requests[0].resolve(h.response('first','bad echo',extra));await p;assert.equal(h.el('cards').innerHTML,'');assert.match(h.el('status').textContent,/未通过核对/);checks++;}
 // Friendly authoritative filter failures clear prior records and applied conditions, preserving drafts.
 for(const code of ['invalid_record_filters','invalid_record_date','invalid_record_identifier']){h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].resolve(filteredResponse(h));await p;fillFilters(h,{identifiers:['changed-draft']});p=h.ui.recheck();h.requests[1].reject(Object.assign(Error('raw server failure'),{status:422,code}));await p;assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.equal(h.el('identifiers').value,'changed-draft');assert.match(h.el('status').textContent,/正文|筛选条件/);assert.doesNotMatch(h.el('status').textContent,/raw server/);checks++;}

 // Rejected local drafts clear old evidence and stop polling; manual recheck retains the last snapshot.
 for(const draft of [{date_from:'2028-02-29'},{identifiers:['REQ-007','REQ-007']}]){
  h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].resolve(filteredResponse(h));await p;assert.equal(h.timers.size,1);
  fillFilters(h,draft);h.el('question').value='unsent invalid draft';await h.ui.submit();
  assert.equal(h.requests.length,1);assert.equal(h.timers.size,0);assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.equal(h.el('answer').textContent,'');assert.equal(h.el('last-question').textContent,'');assert.match(h.el('status').textContent,/本次未提交/);assert.equal(h.el('recheck').disabled,false);
  p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[1].body),{question:'filtered',filters});h.requests[1].resolve(filteredResponse(h));await p;assert.equal(h.timers.size,1);assert.equal(h.el('question').value,'unsent invalid draft');assert.equal(h.el('date-from').value,draft.date_from||'');assert.equal(h.el('identifiers').value,(draft.identifiers||[]).join('\n'));assert.match(h.el('cards').innerHTML,/synthetic filtered record/);checks++;
 }
 // Browser native validation may suppress submit entirely; each real input invalid handler clears/pauses.
 for(const id of ['question','date-from','date-to','identifiers']){
  h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].resolve(filteredResponse(h));await p;
  h.el(id).value=id==='question'?'':'invalid-native-draft';h.el(id).oninvalid();
  assert.equal(h.requests.length,1);assert.equal(h.timers.size,0);assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.match(h.el('status').textContent,/本次未提交/);assert.equal(h.el(id).value,id==='question'?'':'invalid-native-draft');
  p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[1].body),{question:'filtered',filters});h.requests[1].resolve(filteredResponse(h));await p;assert.equal(h.timers.size,1);checks++;
 }
 // Empty/long question drafts receive the same clearing behavior without losing the last submission.
 for(const question of ['','x'.repeat(4001)]){h=setup();fillFilters(h);p=h.submit('filtered');h.requests[0].resolve(filteredResponse(h));await p;await h.submit(question);assert.equal(h.requests.length,1);assert.equal(h.timers.size,0);assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.match(h.el('status').textContent,/本次未提交/);p=h.ui.recheck();assert.deepEqual(asPlain(h.requests[1].body),{question:'filtered',filters});h.requests[1].resolve(filteredResponse(h));await p;checks++;}
 // Old filtered success/failure cannot repopulate a new unfiltered snapshot after clear.
 for(const outcome of ['resolve','reject']){h=setup();fillFilters(h);const old=h.submit('filtered');h.ui.reset();p=h.submit('new first');h.requests[1].resolve(h.response('new first','new unfiltered record'));await p;h.requests[0][outcome](outcome==='resolve'?filteredResponse(h):Error('obsolete filtered error'));await old;assert.match(h.el('cards').innerHTML,/new unfiltered/);assert.match(h.el('applied-filters').textContent,/未启用/);assert.equal(h.el('date-from').value,'');assert.equal(h.el('identifiers').value,'');checks++;}
 // A-B-A, persona/identity switches and real pagehide discard all filter drafts/active views.
 for(const transition of ['ABA','persona','identity','pagehide'])for(const outcome of ['resolve','reject']){
  h=setup();const events=integrate(h);fillFilters(h);const old=h.submit('filtered');
  if(transition==='pagehide')events.pagehide();
  else {h.state.epoch++;if(transition==='ABA'){h.state.project={id:'B'};h.ui.update();h.state.epoch++;h.state.project={id:'A'};}if(transition==='persona')h.state.persona={id:'person-B'};if(transition==='identity')h.state.token='different-token';h.ui.update();}
  h.requests[0][outcome](outcome==='resolve'?filteredResponse(h):Error('old filter scope error'));await old;
  assert.equal(h.el('cards').innerHTML,'');assert.equal(h.el('applied-filters').textContent,'');assert.equal(h.el('date-from').value,'');assert.equal(h.el('date-to').value,'');assert.equal(h.el('identifiers').value,'');assert.equal(h.timers.size,0);assert.equal(h.el('recheck').disabled,true);checks++;
 }
 console.log(`PASS: ${checks} actual record-card UI scenarios (safe rendering, current/stale errors, ABA, exit, polling, correction/withdrawal, draft retention, literal body filters and strict filter echo validation)`);
})().catch(error=>{console.error(error);process.exitCode=1;});
