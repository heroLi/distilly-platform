'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const base='server/src/main/resources/static/';
const turn=(status='FAILED',id='j1')=>({id:'c1',persona_id:'p1',messages:[{role:'user',body:'合成输入'}],active_job:['QUEUED','RUNNING'].includes(status)?[{id,status}]:[],latest_chat_job:{id,status,error_code:'SYNTHETIC_FAILURE'}});
function setup(status='FAILED'){
 const c=vm.createContext({window:{}});vm.runInContext(fs.readFileSync(base+'persona-chat-recovery.js','utf8'),c);
 const state={epoch:1,nonce:1,personaId:'p1',active:true,session:status?turn(status):null},elements=Object.fromEntries(['status','stop','retry','refresh'].map(id=>[id,{hidden:false,disabled:false,textContent:''}]));
 let handler=async()=>turn('SUCCEEDED'),applied=0,denied=0;const calls=[];
 const controller=c.window.PersonaChatRecovery.create({getContext:()=>state,api:async(path,body)=>{calls.push({path,body});return handler(path,body);},elements,errors:{SYNTHETIC_FAILURE:'合成执行失败。'},apply:value=>{state.session=value;applied++;controller.render();},onDenied:()=>{state.session=null;state.nonce++;denied++;}});
 controller.render();return {c,state,e:elements,calls,controller,set:fn=>handler=fn,applied:()=>applied,denied:()=>denied};
}
(async()=>{
 let count=0,h;
 for(const status of [null,'QUEUED','RUNNING','FAILED','CANCELLED','SUCCEEDED']){
  h=setup(status);assert.equal(h.e.stop.hidden,!['QUEUED','RUNNING'].includes(status));assert.equal(h.e.retry.hidden,status!=='FAILED');assert.equal(h.e.refresh.disabled,!status);
  if(status==='FAILED')assert.match(h.e.status.textContent,/失败.*显式重试/);if(status==='CANCELLED')assert.match(h.e.status.textContent,/已停止/);count++;
 }
 h=setup();let release;h.set((path,body)=>body?new Promise(resolve=>release=resolve):turn('QUEUED'));
 const first=h.e.retry.onclick();await h.e.retry.onclick();await h.e.refresh.onclick();assert.equal(h.calls.length,1);assert.equal(h.e.retry.disabled,true);release();await first;
 assert.equal(h.calls.length,2);assert.equal(h.calls[0].path,'/api/persona-jobs/j1/retry');assert.equal(h.calls[1].body,undefined);assert.equal(h.state.session.messages.length,1);assert.equal(h.e.stop.hidden,false);count++;
 for(const action of ['retry','stop']){
  h=setup(action==='stop'?'RUNNING':'FAILED');h.set(async()=>{throw Error('lost ack');});await h.e[action].onclick();assert.match(h.e.status.textContent,/尚未确认/);
  await h.e[action].onclick();assert.equal(h.calls.length,1);assert.equal(h.e.refresh.disabled,false);
  h.set(async()=>turn(action==='stop'?'CANCELLED':'QUEUED'));await h.e.refresh.onclick();assert.equal(h.calls.length,2);assert.equal(h.calls[1].body,undefined);assert.equal(h.applied(),1);count++;
 }
 for(const status of [401,403,404]){
  h=setup();h.set(async()=>{throw Object.assign(Error('denied'),{status});});await h.e.retry.onclick();assert.equal(h.denied(),1);assert.equal(h.state.session,null);assert.match(h.e.status.textContent,/不可访问/);assert.equal(h.e.refresh.disabled,true);await h.e.retry.onclick();assert.equal(h.calls.length,1);count++;
 }
 for(const ending of ['session','nonce','epoch','pagehide','reset'])for(const outcome of ['resolve','reject']){
  h=setup();let yes,no;h.set(()=>new Promise((resolve,reject)=>{yes=resolve;no=reject;}));const pending=h.e.retry.onclick();
  if(ending==='session')h.state.session={...turn(),id:'c2'};if(ending==='nonce')h.state.nonce++;if(ending==='epoch')h.state.epoch++;if(ending==='pagehide')h.state.active=false;if(ending==='reset')h.controller.reset();
  outcome==='resolve'?yes():no(Object.assign(Error('old denied'),{status:403}));await pending;assert.equal(h.calls.length,1);assert.equal(h.applied(),0);assert.equal(h.denied(),0);count++;
 }
 h=setup();h.set(async()=>({...turn(),persona_id:'other'}));await h.e.refresh.onclick();assert.equal(h.applied(),0);assert.match(h.e.status.textContent,/无法读取/);count++;
 h=setup();h.set(async()=>{throw Error('offline');});await h.e.refresh.onclick();assert.match(h.e.status.textContent,/再次点击刷新/);assert.equal(h.e.retry.disabled,false);h.set(async()=>turn());await h.e.refresh.onclick();assert.match(h.e.status.textContent,/本次回应失败/);count++;
 // Actual poll refreshes a terminal state even when message and active counts do not change.
 const source=fs.readFileSync(base+'persona.js','utf8');let renders=0;
 const state={token:'fixture',epoch:1,sessionNonce:1,persona:{id:'p1',versions:[]},session:turn('FAILED'),pollBusy:false};
 const p=vm.createContext({window:{},state,$:()=>({textContent:'',checked:false,contains:()=>false}),document:{activeElement:null},api:async path=>path.endsWith('/jobs')?[]:turn('CANCELLED'),renderSession:()=>renders++,loadMemories:async()=>{},loadJobs:async()=>{},denyChat:()=>false});
 vm.runInContext(source.split('\n').find(line=>line.startsWith('async function poll(')),p);await p.poll();assert.equal(renders,1);assert.equal(state.session.latest_chat_job.status,'CANCELLED');count++;
 // Actual page lifecycle invalidates late mutations and restarts one timer on BFCache restore.
 let intervalStarts=0,clears=0,reads=0;const events={},elements={messages:{innerHTML:'old'},memories:{innerHTML:'old'}};
 const l=vm.createContext({window:{addEventListener:(name,fn)=>events[name]=fn},state:{token:'fixture',epoch:1,session:{id:'c1'}},chatRecovery:{reset(){}},chatSubmit:null,$:id=>elements[id],studioReads:{invalidate(){}},stopSpeech(){},recognition:null,clearInterval:()=>clears++,setInterval:()=>{intervalStarts++;return 1;},poll(){},loadSession:async()=>{reads++;},loadMemories:async()=>{},denyChat:()=>false,notice(){}});
 for(const line of source.split('\n').filter(line=>line.startsWith("window.addEventListener('page")))vm.runInContext(line,l);
 events.pagehide();assert.equal(l.state.epoch,2);assert.equal(l.state.pageHidden,true);assert.equal(elements.messages.innerHTML,'');events.pageshow({persisted:true});await Promise.resolve();assert.equal(intervalStarts,1);assert.equal(reads,1);assert.equal(clears,2);assert.equal(l.state.pageHidden,false);count++;
 console.log('PASS:',count,'actual current-chat recovery/lifecycle scenarios; explicit retry, cancel, lost ack, no auto writes, scoped late responses and BFCache restart');
})().catch(error=>{console.error(error);process.exitCode=1;});
