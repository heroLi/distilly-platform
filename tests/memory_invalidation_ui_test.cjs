const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');
const start=source.indexOf('async function poll(){'),end=source.indexOf('\nconst templates=',start);
assert.ok(start>=0&&end>start);
function setup(oldStatus='RUNNING',newStatus='CANCELLED'){
 const old={id:'conversation',messages:[{id:'user',role:'user'}],active_job:oldStatus==='CANCELLED'?[]:[{id:'job',status:oldStatus}],latest_chat_job:{id:'job',status:oldStatus}};
 const next={...old,active_job:newStatus==='CANCELLED'?[]:[{id:'job',status:newStatus}],latest_chat_job:{id:'job',status:newStatus}};
 const elements=new Proxy({}, {get:(o,k)=>o[k]||(o[k]={textContent:'',checked:false,contains:()=>false})});
 let reads=0,renders=0,calls=0;
 const c=vm.createContext({state:{token:'fixture',persona:{id:'person',versions:[]},session:old,epoch:1,sessionNonce:1,pollBusy:false},document:{activeElement:{}},$:id=>elements[id],api:async path=>{calls++;return path.endsWith('/jobs')?[]:next;},loadMemories:async()=>{reads++;},loadJobs:async()=>{},renderSession:()=>renders++,renderVersions(){},denyChat:()=>false,speak(){}});
 vm.runInContext(source.slice(start,end),c);
 return {c,elements,next,reads:()=>reads,renders:()=>renders,calls:()=>calls};
}
(async()=>{
 for(const status of ['RUNNING','QUEUED']){
  const s=setup(status);await s.c.poll();assert.equal(s.reads(),1,status+' cancellation must recheck memory cards without a new message');
  await s.c.poll();assert.equal(s.reads(),1,'stable cancellation must not keep fetching');
 }
 let s=setup();s.elements.memories.contains=()=>true;await s.c.poll();assert.equal(s.reads(),0,'retain focused memory draft');assert.equal(s.c.poll.memoriesPending,true);
 s.elements.memories.contains=()=>false;await s.c.poll();assert.equal(s.reads(),1,'recheck after focus leaves even with no further task change');
 s=setup('CANCELLED');await s.c.poll();assert.equal(s.reads(),0);
 s=setup('RUNNING','RUNNING');s.next.active_job[0].partial_reply='synthetic delta';await s.c.poll();assert.equal(s.reads(),0,'partial text alone does not invalidate memories');
 s=setup('RUNNING','RUNNING');s.next.messages=[...s.next.messages,{id:'reply',role:'assistant'}];await s.c.poll();assert.equal(s.reads(),1,'new reply keeps existing refresh behavior');
 s=setup();let resolve;s.c.api=path=>path.endsWith('/jobs')?Promise.resolve([]):new Promise(r=>resolve=r);
 const pending=s.c.poll();await new Promise(setImmediate);assert.equal(typeof resolve,'function');s.c.state.epoch++;s.c.state.persona={id:'other',versions:[]};resolve(s.next);await pending;assert.equal(s.reads(),0,'late prior-person cancellation cannot fetch/render their memories');assert.equal(s.renders(),0);
 for(const ending of ['pageHidden','token']){s=setup();s.c.state[ending]=ending==='token'?'':true;await s.c.poll();assert.equal(s.calls(),0);}
 console.log('PASS: cancellation refreshes memory cards once; focused drafts defer until blur, stable/partial states do not refetch, new replies and scope/lifecycle guards preserved');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
