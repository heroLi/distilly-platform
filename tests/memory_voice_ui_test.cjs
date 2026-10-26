const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function element(){return {value:'',checked:false,hidden:false,disabled:false,textContent:'',innerHTML:'',src:'',pause(){this.paused=true;},load(){},removeAttribute(){this.src='';},showModal(){this.open=true;},close(){this.open=false;},addEventListener(){}};}
function voice(){
 let c={active:true,edit:true,persona:'person',epoch:1},calls=[],handler=async()=>[],ids=0;
 const ctx=vm.createContext({crypto:{randomUUID:()=>`request-${++ids}`}});
 vm.runInContext(fs.readFileSync('server/src/main/resources/static/persona-voice.js','utf8'),ctx);
 const elements=Object.fromEntries(['panel','form','name','consent','create','refresh','profiles','status','audio'].map(n=>[n,element()]));
 const module=ctx.PersonaVoiceFactory.create({context:()=>c,api:async(...args)=>{calls.push(args);return handler(...args);},elements,escape:s=>String(s).replace(/</g,'&lt;').replace(/"/g,'&quot;')});
 return {elements,module,c,calls,handle:fn=>handler=fn};
}
const click=(id,op)=>({target:{closest:()=>({dataset:{id,clone:op},disabled:false})}});
const submit={preventDefault(){}};
const profile=(authorization='ACTIVE',jobs=[])=>({id:'profile',name:'<script>',authorization_status:authorization,authorization_id:'authorization',jobs});
(async()=>{
 let s=voice();s.c.edit=false;await s.module.update();assert.equal(s.calls.length,0);assert.equal(s.elements.panel.hidden,true);
 s=voice();await s.module.update();assert.match(s.elements.profiles.innerHTML,/还没有/);
 let resolve;s.handle(()=>new Promise(r=>resolve=r));s.elements.name.value='合成';s.elements.consent.checked=true;
 const saving=s.elements.form.onsubmit(submit);await s.elements.form.onsubmit(submit);assert.equal(s.calls.length,2,'one list and one create despite double click');
 resolve({id:'profile'});s.handle(async()=>[profile()]);await saving;assert.match(s.elements.profiles.innerHTML,/&lt;script>/);assert.equal(s.elements.name.value,'');
 s=voice();await s.module.update();s.elements.name.value='合成';s.elements.consent.checked=true;
 let bodies=[];s.handle(async(path,body)=>{if(body){bodies.push(body);throw new Error('lost response');}return [];});
 await s.elements.form.onsubmit(submit);await s.elements.form.onsubmit(submit);
 assert.equal(bodies.length,2);assert.equal(bodies[0].request_id,bodies[1].request_id);assert.equal(s.elements.name.disabled,true);
 s=voice();s.handle(()=>new Promise(r=>resolve=r));const old=s.module.update();s.c.epoch++;s.c.persona='other';s.module.reset();resolve([profile()]);await old;assert.equal(s.elements.profiles.innerHTML,'');
 s=voice();s.handle(async()=>[profile('ACTIVE',[{id:'clone',kind:'VOICE_CLONE',status:'SUCCEEDED'}])]);await s.module.update();
 s.handle(async(path,body)=>body?{id:'preview'}:[profile('ACTIVE',[{id:'clone',kind:'VOICE_CLONE',status:'SUCCEEDED'},{id:'preview',kind:'VOICE_PREVIEW',status:'SUCCEEDED',result:{provider:'mock',synthetic:true,audio:'data:audio/wav;base64,synthetic'}}])]);
 await s.elements.profiles.onclick(click('profile','preview'));assert.equal(s.elements.audio.hidden,false);assert.match(s.elements.status.textContent,/不是克隆语音/);
 s.handle(async()=>[profile('REVOKED')]);await s.module.update();assert.equal(s.elements.audio.hidden,true);assert.equal(s.elements.audio.src,'');
 s.elements.audio.src='old';s.elements.audio.hidden=false;s.handle(()=>new Promise(r=>resolve=r));const revoking=s.elements.profiles.onclick(click('profile','revoke'));
 assert.equal(s.elements.audio.src,'','stop immediately before mutation response');s.c.active=false;s.module.reset();resolve({});await revoking;assert.equal(s.elements.audio.hidden,true);
 s=voice();s.elements.audio.src='old';s.handle(async()=>{throw new Error('denied');});await s.module.update();assert.equal(s.elements.audio.src,'');assert.match(s.elements.status.textContent,/权限/);
 // Source inspection uses textContent and invalidates closed/old-scope replies.
 const ctx=vm.createContext({});vm.runInContext(fs.readFileSync('server/src/main/resources/static/persona-memory-source.js','utf8'),ctx);
 const e=Object.fromEntries(['dialog','body','meta','close'].map(n=>[n,element()]));let c={active:true,persona:'person',epoch:1};
 const source=ctx.PersonaMemorySourceFactory.create({context:()=>c,api:()=>new Promise(r=>resolve=r),elements:e});
 const opening=source.open('memory');source.reset();resolve({body:'<script>',title:'source',message_id:'id',created_at:1});await opening;assert.equal(e.body.textContent,'');
 const current=source.open('memory');resolve({body:'<script>',title:'source',message_id:'id',created_at:1});await current;assert.equal(e.body.textContent,'<script>');assert.equal(e.body.innerHTML,'');
 // A background memory read that started before typing must not replace a draft when it arrives late.
 const studio=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');
 const from=studio.indexOf('async function loadMemories('),to=studio.indexOf('async function loadJobs(',from);
 let dirty=false;const memoryElement={innerHTML:'draft-render',querySelector:()=>dirty?{}:null};
 const loader=vm.createContext({state:{persona:{id:'person'},epoch:1},$:()=>memoryElement,escapeHtml:String,api(){},studioReads:{read:()=>new Promise(r=>resolve=r)}});
 vm.runInContext(studio.slice(from,to),loader);
 const loading=loader.loadMemories();dirty=true;resolve({value:[{id:'memory',body:'server state',status:'CONFIRMED'}]});await loading;
 assert.equal(memoryElement.innerHTML,'draft-render','late background memory response must preserve the edited card');
 // Explicit refresh discards only the old draft; typing after its request starts is a new draft.
 memoryElement.querySelectorAll=()=>[{dataset:{set memoryDirty(value){dirty=value==='true';}}}];
 const refresh=loader.loadMemories(true);assert.equal(dirty,false);dirty=true;
 resolve({value:[{id:'memory',body:'server state',status:'CONFIRMED'}]});await refresh;
 assert.equal(memoryElement.innerHTML,'draft-render','refresh cannot discard typing that began after it was requested');
 console.log('PASS: mock clone scope, duplicate/uncertain requests, escaped states, preview/revocation/lifecycle cleanup and private source late-response guards');
})().catch(error=>{console.error(error);process.exitCode=1;});
