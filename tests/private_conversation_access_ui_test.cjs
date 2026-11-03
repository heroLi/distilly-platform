'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root='server/src/main/resources/static/',source=fs.readFileSync(root+'persona.js','utf8');
function setup(){
 const nodes={},resets=[],state={token:'synthetic-local-login',me:{id:'viewer'},project:{id:'project'},persona:{id:'person',versions:[]},session:{id:'old',active_job:[]},epoch:1,sessionNonce:1,pollBusy:false};let hooks;
 const element=id=>nodes[id]||(nodes[id]={innerHTML:'private',value:'private',textContent:'private',replaceChildren(){this.innerHTML='';}});
 const window={PersonaChatRecovery:{create:opts=>{hooks=opts;return {deny:()=>opts.onDenied()};}}};
 for(const name of ['PersonaMemorySource','PersonaMemoryReferences','PersonaMemorySync'])window[name]={reset:()=>resets.push(name)};
 const c=vm.createContext({window,state,errorLabels:{},$:element,api:async()=>{throw Error('cleanup must not send requests');},renderSession:()=>{element('messages').innerHTML='generic';},notice(){},chatSubmit:null});
 vm.runInContext(fs.readFileSync(root+'ui-read-scope.js','utf8'),c);c.studioReads=window.UiReadScope.create(()=>[state.token,state.epoch,state.project?.id,state.persona?.id]);
 const helper=source.split('\n').find(l=>l.startsWith('function clearPrivateConversation('));if(helper)vm.runInContext(helper,c);
 vm.runInContext(source.split('\n').find(l=>l.startsWith('const chatRecovery=')),c);
 return {c,state,nodes,element,resets,deny:()=>hooks.onDenied()};
}
(async()=>{
 let h=setup();h.deny();
 assert.equal(h.element('conversations').innerHTML,'','denial must remove the private conversation title');assert.equal(h.element('message').value,'','denial must remove private unsent text');assert.equal(h.element('chat-image').value,'');assert.equal(h.element('memories').innerHTML,'');assert.equal(h.state.session,null);assert.equal(h.state.sessionNonce,2);
 assert.equal(h.state.token,'synthetic-local-login');assert.equal(h.state.persona.id,'person');assert.equal(h.state.project.id,'project');assert.equal(h.resets.length,3);
 h=setup();let release;const pending=h.c.studioReads.read('memories',()=>new Promise(resolve=>release=resolve));h.deny();release('obsolete-private-memory');assert.equal(await pending,null,'pending private reads must be invalidated');
 h=setup();let reject;h.c.api=()=>new Promise((_,no)=>reject=no);h.c.denyChat=()=>{throw Error('obsolete403 must not deny current conversation');};
 vm.runInContext(source.split('\n').find(l=>l.startsWith('async function poll(')),h.c);const old=h.c.poll();h.state.sessionNonce++;h.state.session={id:'new',active_job:[]};h.element('message').value='new legitimate draft';reject(Object.assign(Error('old'),{status:403}));await old;
 assert.equal(h.state.session.id,'new');assert.equal(h.element('message').value,'new legitimate draft');assert.equal(h.state.token,'synthetic-local-login');
 console.log('PASS: current denial clears private metadata/drafts and pending reads; identity and stale-response isolation preserved (3 groups)');
})().catch(error=>{console.error(error);process.exitCode=1;});
