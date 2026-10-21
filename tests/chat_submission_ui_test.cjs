'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),{webcrypto}=require('node:crypto');
const path='server/src/main/resources/static/persona-chat-submit.js',KEY='platform-chat-submissions-v1';
const id='11111111-1111-4111-8111-111111111111',messageId='22222222-2222-4222-8222-222222222222',jobId='33333333-3333-4333-8333-333333333333';
function setup(shared=new Map()){
 const ctx={userId:'owner',projectId:'w1',personaId:'p1',session:{id,active_job:[]},epoch:1,nonce:1,active:true};
 const input={message:'合成输入',image:null};const e=Object.fromEntries(['send','status','recheck','retry'].map(k=>[k,{disabled:false,hidden:false,textContent:''}]));
 let handler=async(path,body)=>({submission:{request_id:body.request_id,message_id:messageId,job_id:jobId,status:'ACCEPTED'}}),applied=0,denials=0,failStore=false;
 const storage={getItem:k=>{if(failStore)throw Error('storage denied');return shared.get(k)||null;},setItem:(k,v)=>{if(failStore)throw Error('storage denied');shared.set(k,v);},removeItem:k=>shared.delete(k)};
 const c=vm.createContext({window:{},TextEncoder,Uint8Array});vm.runInContext(fs.readFileSync(path,'utf8'),c);const calls=[];
 const ui=c.window.PersonaChatSubmit.create({context:()=>ctx,api:async(path,body)=>{calls.push({path,body});return handler(path,body);},prepare:async()=>{const raw={...input};if(!raw.message.trim())return null;return {body:{message:raw.message.trim(),...(raw.image?{image:raw.image}:{})},clearIfUnchanged(){if(input.message===raw.message&&input.image===raw.image){input.message='';input.image=null;}}};},accepted:async()=>{applied++;},denied:()=>{denials++;ctx.session=null;ctx.nonce++;},storage,crypto:webcrypto,elements:e});ui.render();
 return {ctx,input,e,ui,calls,shared,set:fn=>handler=fn,receipt:request=>({request_id:request,message_id:messageId,job_id:jobId,status:'ACCEPTED'}),stored:()=>JSON.parse(shared.get(KEY)||'{}'),applied:()=>applied,denials:()=>denials,failStorage:()=>failStore=true};
}
async function until(fn){const end=Date.now()+2000;while(Date.now()<end){if(fn())return;await new Promise(resolve=>setTimeout(resolve,1));}throw Error('fixture did not reach API');}
(async()=>{
 let h=setup(),count=0;await h.ui.submit();assert.equal(h.calls.length,1);assert.match(h.calls[0].body.request_id,/^[a-f0-9-]{36}$/);assert.equal(h.input.message,'');assert.equal(Object.keys(h.stored()).length,0);count++;
 h=setup();let resolve;h.set((path,body)=>new Promise(yes=>resolve=()=>yes({submission:h.receipt(body.request_id)})));const pending=h.ui.submit();await until(()=>resolve);await h.ui.submit();assert.equal(h.calls.length,1);assert.equal(h.e.send.disabled,true);resolve();await pending;count++;
 for(const changed of [false,true]){
  h=setup();h.set(async()=>{throw TypeError('lost ack');});await h.ui.submit();const request=h.calls[0].body.request_id;assert.equal(h.e.send.disabled,true);assert.equal(h.calls.length,1);assert.equal(h.input.message,'合成输入');if(changed)h.input.message='新草稿';
  h.set(async()=>h.receipt(request));await h.e.recheck.onclick();assert.equal(h.calls.length,2);assert.equal(h.calls[1].body,undefined);assert.equal(h.input.message,changed?'新草稿':'');assert.equal(h.applied(),1);count++;
 }
 h=setup();h.set(async()=>{throw Error('offline');});await h.ui.submit();const shared=h.shared,request=h.calls[0].body.request_id;
 assert.ok(!JSON.stringify(h.stored()).includes('合成输入'));const restored=setup(shared);assert.equal(restored.e.send.disabled,true);assert.equal(restored.calls.length,0);restored.input.message='';restored.set(async()=>restored.receipt(request));await restored.e.recheck.onclick();assert.equal(restored.calls.length,1);assert.equal(restored.calls[0].body,undefined);count++;
 h=setup();h.set(async()=>{throw Error('before commit disconnect');});await h.ui.submit();const original=h.calls[0].body.request_id;
 h.set(async()=>{throw Object.assign(Error('missing'),{status:404,code:'submission_not_found'});});await h.e.recheck.onclick();assert.equal(h.e.retry.hidden,false);h.input.message='不同的消息';await h.e.retry.onclick();assert.equal(h.calls.length,2);assert.match(h.e.status.textContent,/内容.*不同/);
 h.input.message='合成输入';h.set(async(path,body)=>({submission:h.receipt(body.request_id)}));await h.e.retry.onclick();assert.equal(h.calls[2].body.request_id,original);assert.equal(h.input.message,'');count++;
 for(const status of [401,403,404]){h=setup();h.set(async()=>{throw Object.assign(Error('denied'),{status,code:'private_conversation'});});await h.ui.submit();assert.equal(h.denials(),1);assert.equal(Object.keys(h.stored()).length,0);count++;}
 for(const ending of ['user','person','conversation','nonce','epoch','leave','reset'])for(const outcome of ['resolve','reject']){
  h=setup();let yes,no;h.set((path,body)=>new Promise((resolve,reject)=>{yes=()=>resolve({submission:h.receipt(body.request_id)});no=()=>reject(Object.assign(Error('old denied'),{status:403}));}));const pending=h.ui.submit();await until(()=>yes);
  if(ending==='user')h.ctx.userId='other';if(ending==='person')h.ctx.personaId='other';if(ending==='conversation')h.ctx.session={id:'other',active_job:[]};if(ending==='nonce')h.ctx.nonce++;if(ending==='epoch')h.ctx.epoch++;if(ending==='leave')h.ctx.active=false;if(ending==='reset')h.ui.reset();
  outcome==='resolve'?yes():no();await pending;assert.equal(h.applied(),0);assert.equal(h.denials(),0);assert.equal(h.input.message,'合成输入');assert.equal(Object.keys(h.stored()).length,1);count++;
 }
 h=setup();h.failStorage();await h.ui.submit();assert.equal(h.calls.length,0);assert.equal(h.e.send.disabled,true);count++;
 h=setup(new Map([[KEY,'invalid JSON']]));await h.ui.submit();assert.equal(h.calls.length,0);assert.equal(h.e.send.disabled,true);count++;
 h=setup();h.set(async()=>({submission:{request_id:'wrong',status:'ACCEPTED'}}));await h.ui.submit();assert.equal(h.applied(),0);assert.equal(Object.keys(h.stored()).length,1);assert.equal(h.e.send.disabled,true);count++;
 h=setup();h.set(async()=>{throw Error('lost');});await h.ui.submit();const other=setup(h.shared);other.ctx.userId='other';other.ui.render();assert.equal(other.e.send.disabled,false);assert.equal(other.e.recheck.hidden,true);count++;
 h=setup();h.set(async()=>{throw Error('lost');});await h.ui.submit();h.ui.clear();assert.equal(h.shared.has(KEY),false);count++;
 h=setup();h.ctx.session.active_job=[{id:'active'}];await h.ui.submit();assert.equal(h.calls.length,0);count++;
 const source=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');let prevented=0,submitted=0;const fields={message:{},'chat-form':{requestSubmit(){submitted++;}}};const actual=vm.createContext({$:id=>fields[id]});vm.runInContext(source.split('\n').find(line=>line.startsWith("$('message').onkeydown")),actual);fields.message.onkeydown({key:'Enter',ctrlKey:true,preventDefault(){prevented++;}});assert.equal(prevented,1);assert.equal(submitted,1);count++;
 console.log('PASS:',count,'actual message-submission UI scenarios; persisted metadata only, explicit GET recovery, same-key retry, draft retention, scope isolation and no automatic resend');
})().catch(error=>{console.error(error);process.exitCode=1;});
