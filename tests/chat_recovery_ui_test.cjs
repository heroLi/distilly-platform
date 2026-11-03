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
  if(status==='QUEUED'){assert.match(h.e.status.textContent,/尚未确认后台领取/);assert.doesNotMatch(h.e.status.textContent,/后台不可用|后台已离线/);}
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
 let intervalStarts=0,clears=0,reads=0;const events={},elements={messages:{innerHTML:'old'},memories:{innerHTML:'old'},workspace:{hidden:false}};
 let sharedToken='fixture',loginResets=0;
 const l=vm.createContext({window:{addEventListener:(name,fn)=>events[name]=fn},PlatformSession:{token:()=>sharedToken},clearPersonaLogin:shared=>{assert.equal(shared,false);loginResets++;l.state.token='';l.state.session=null;},state:{token:'fixture',epoch:1,session:{id:'c1'}},chatRecovery:{reset(){}},chatSubmit:null,$:id=>elements[id],studioReads:{invalidate(){}},stopSpeech(){},recognition:null,clearInterval:()=>clears++,setInterval:()=>{intervalStarts++;return 1;},poll(){},loadSession:async()=>{reads++;},loadMemories:async()=>{},denyChat:()=>false,notice(){}});
 for(const line of source.split('\n').filter(line=>line.startsWith("window.addEventListener('page")))vm.runInContext(line,l);
 events.pagehide();assert.equal(l.state.epoch,2);assert.equal(l.state.pageHidden,true);assert.equal(elements.messages.innerHTML,'');assert.equal(elements.workspace.hidden,true,'private title and drafts must stay hidden before current access is verified');events.pageshow({persisted:true});await Promise.resolve();assert.equal(intervalStarts,1);assert.equal(reads,1);assert.equal(clears,2);assert.equal(l.state.pageHidden,false);count++;
 await new Promise(setImmediate);assert.equal(elements.workspace.hidden,false,'authorized restore becomes visible after successful reads');
 for(const token of ['', 'different-account']){
  l.state.token='fixture';l.state.session={id:'c1'};sharedToken=token;const prior=reads,started=intervalStarts;
  events.pagehide();events.pageshow({persisted:true});await Promise.resolve();
  assert.equal(reads,prior,'BFCache must not fetch with an identity that was logged out or replaced');assert.equal(intervalStarts,started);assert.equal(l.state.session,null);assert.equal(sharedToken,token,'another page login must survive');count++;
 }
 assert.equal(loginResets,2);
 l.state.token='fixture';l.state.session={id:'c1'};l.PlatformSession.token=()=>{throw Error('synthetic storage unavailable');};const beforeFailure=reads;
 events.pagehide();events.pageshow({persisted:true});await Promise.resolve();assert.equal(reads,beforeFailure);assert.equal(l.state.session,null);assert.equal(loginResets,3);count++;
 l.PlatformSession.token=()=> 'fixture';l.state.token='fixture';l.state.session={id:'c1'};let deniedMemoryReads=0;
 l.loadSession=async()=>{throw Object.assign(Error('synthetic revoked'),{status:403});};l.loadMemories=async()=>deniedMemoryReads++;
 events.pagehide();events.pageshow({persisted:true});await new Promise(setImmediate);
 assert.equal(elements.workspace.hidden,true);assert.equal(l.state.session,null);assert.equal(loginResets,4);assert.equal(deniedMemoryReads,0);count++;
 // Execute the actual login cleanup with the real submission controller.
 // A restored old page must not erase a new identity's uncertain-send receipt.
 for(const explicitLogout of [false,true]){
  const key='platform-chat-submissions-v1',receipt=JSON.stringify({'["new-user","p2","person2","c2"]':{request_id:'11111111-1111-4111-8111-111111111111',digest:'a'.repeat(64)}});
  const store=new Map([[key,receipt]]),els={};let shared='new-login';
  const ctx=vm.createContext({window:{},state:{token:'old-login',me:{id:'old-user'},project:{id:'p1'},persona:{id:'person1'},session:turn(),epoch:1,sessionNonce:1},
   studioReads:{invalidate(){}},stopSpeech(){},clearInterval(){},chatRecovery:{reset(){}},PlatformSession:{clear:()=>shared=''},$:id=>els[id]||(els[id]={innerHTML:'private',value:'private',textContent:'private'})});
  vm.runInContext(fs.readFileSync(base+'persona-chat-submit.js','utf8'),ctx);
  ctx.chatSubmit=ctx.window.PersonaChatSubmit.create({context:()=>({userId:ctx.state.me?.id,projectId:ctx.state.project?.id,personaId:ctx.state.persona?.id,session:ctx.state.session,epoch:ctx.state.epoch,nonce:ctx.state.sessionNonce,active:!!ctx.state.token}),api:async()=>{},prepare(){},accepted(){},denied(){},
   storage:{getItem:k=>store.get(k),setItem:(k,v)=>store.set(k,v),removeItem:k=>store.delete(k)},crypto:{},elements:Object.fromEntries(['send','status','recheck','retry'].map(k=>[k,{}]))});
  vm.runInContext(source.split('\n').find(line=>line.startsWith('function clearPersonaLogin(')),ctx);
  ctx.clearPersonaLogin(explicitLogout);
  assert.equal(store.get(key),explicitLogout?undefined:receipt,'passive old-page cleanup must preserve other identity receipts');
  assert.equal(shared,explicitLogout?'':'new-login');assert.equal(els.messages.innerHTML,'');assert.equal(els.memories.innerHTML,'');assert.equal(ctx.state.session,null);count++;
 }
 console.log('PASS:',count,'actual current-chat recovery/lifecycle scenarios; explicit retry, cancel, lost ack, no auto writes, scoped late responses and BFCache restart');
})().catch(error=>{console.error(error);process.exitCode=1;});
