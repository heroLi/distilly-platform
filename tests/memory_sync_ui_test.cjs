const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const code=fs.readFileSync('server/src/main/resources/static/persona-memory-sync.js','utf8');
function setup(){
 const ctx=vm.createContext({});vm.runInContext(code,ctx);
 const c={active:true,epoch:1,persona:'person'},elements={items:{innerHTML:''},status:{textContent:''},refresh:{disabled:false}};
 let handler=async()=>({mode:'DISABLED',real_provider_enabled:false,items:[]}),calls=[];
 const module=ctx.PersonaMemorySyncFactory.create({context:()=>c,api:async(...args)=>{calls.push(args);return handler(...args);},elements,escape:s=>String(s).replace(/</g,'&lt;').replace(/"/g,'&quot;')});
 return {module,c,elements,calls,handle:fn=>handler=fn};
}
const row=(state='PENDING')=>({memory_id:'<memory>',revision:1,kind:'RETAIN',state,receipt_mode:state==='ACKED'?'OFFLINE_SIMULATION':null,receipt_outcome:state==='FAILED'?'NOT_APPLIED':null});
const offline=items=>({mode:'OFFLINE_SIMULATION',real_provider_enabled:false,items});
const click=action=>({target:{closest:()=>({disabled:false,dataset:{id:'<memory>',memorySync:action}})}});
(async()=>{
 let s=setup();await s.module.update();assert.match(s.elements.status.textContent,/消费者关闭/);assert.equal(s.calls.length,1);
 await s.elements.items.onclick(click('advance'));assert.equal(s.calls.length,1,'disabled mode never posts');
 s=setup();s.handle(async()=>offline([row()]));await s.module.update();assert.match(s.elements.items.innerHTML,/&lt;memory>/);assert.match(s.elements.items.innerHTML,/离线/);
 let resolve;s.handle((path,body)=>body?new Promise(r=>resolve=r):Promise.resolve(offline([row('ACKED')])));
 const first=s.elements.items.onclick(click('advance'));await s.elements.items.onclick(click('advance'));assert.equal(s.calls.filter(c=>c[1]).length,1);
 resolve(offline([row('ACKED')]));await first;assert.match(s.elements.items.innerHTML,/非真实同步/);assert.doesNotMatch(s.elements.items.innerHTML,/data-memory-sync=/);
 s=setup();s.handle(async()=>offline([{...row('FAILED'),receipt_mode:'OFFLINE_SIMULATION'}]));await s.module.update();assert.match(s.elements.items.innerHTML,/data-memory-sync="retry"/);assert.equal(s.calls.filter(c=>c[1]).length,0);
 s=setup();s.handle(()=>new Promise(r=>resolve=r));const old=s.module.update();s.c.epoch++;s.c.persona='other';s.module.reset();resolve(offline([row()]));await old;assert.equal(s.elements.items.innerHTML,'');
 s=setup();s.handle(async()=>offline([row('UNKNOWN')]));await s.module.update();assert.match(s.elements.items.innerHTML,/结果未知/);assert.equal(s.calls.filter(c=>c[1]).length,0,'read cannot recover or resend');
 s.handle(async()=>{throw new Error('denied');});await s.module.update();assert.equal(s.elements.items.innerHTML,'');assert.match(s.elements.status.textContent,/权限/);
 s=setup();s.c.active=false;await s.module.update();assert.equal(s.calls.length,0);
 console.log('PASS: memory sync status is private/read-only, default closed, explicit bounded actions, duplicate/late responses and lifecycle guards; mock never labeled real');
})().catch(e=>{console.error(e);process.exitCode=1;});
