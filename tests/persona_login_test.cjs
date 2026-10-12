// Exercise the actual login and request handlers under stalled and rejected requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('server/src/main/resources/static/persona.js', 'utf8');
class Button { constructor(){this.disabled=false;this.id='login-submit';} }
const button=new Button(), elements={}, saved=new Map();
for(const id of ['login-status','token','identity','model-mode','login','workspace','logout','login-form'])elements[id]={textContent:'',value:'',hidden:id==='workspace'||id==='logout'};
let mode='stalled', aborted=0, projectFailure=false;
const pending=signal=>new Promise((_,reject)=>signal.addEventListener('abort',()=>{aborted++;reject(Error('aborted'));},{once:true}));
const context=vm.createContext({
  window:{},state:{token:'',me:null,timer:null}, $:id=>elements[id],
  HTMLButtonElement:Button, AbortController, TypeError,
  errorLabels:{authentication_required:'请重新登录'}, notice(){},
  setTimeout:fn=>setTimeout(fn,20),clearTimeout,clearInterval(){},setInterval:()=>1,poll(){},
  PlatformSession:{saveToken:v=>saved.set('persona-token',v),selectWorkspace:()=>{},clear:()=>saved.clear()},
  loadProjects:async()=>{if(projectFailure)throw Error('工作空间暂时不可用');},
  fetch:async(_,options)=>{
    assert.equal(options.cache,'no-store');
    if(mode==='stalled')return pending(options.signal);
    if(mode==='body-stalled')return {ok:true,text:()=>pending(options.signal)};
    if(mode==='offline')throw new TypeError('network failure');
    if(mode==='rejected'||mode==='form-rejected')return mode==='form-rejected'?{ok:true,text:async()=>JSON.stringify({error:'authentication_required'})}:{ok:false,json:async()=>({error:'authentication_required'})};
    return {ok:true,text:async()=>JSON.stringify({id:'owner',demo:true,model_mode:'gateway'})};
  }
});
context.window=context.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),context);context.UiReadScope=context.window.UiReadScope;
vm.runInContext(fs.readFileSync('server/src/main/resources/static/platform-request.js','utf8'),context);context.PlatformRequest=context.window.PlatformRequest;
vm.runInContext(source.slice(source.indexOf('const API_TIMEOUT_MS'),source.indexOf('function ask(')),context);
vm.runInContext(source.slice(source.indexOf('async function login()'),source.indexOf("$('logout').onclick")),context);
const submit=()=>elements['login-form'].onsubmit({type:'submit',submitter:button,preventDefault(){}});
(async()=>{
  elements.token.value='demo-owner';
  const first=submit();assert.equal(button.disabled,true);
  await first;assert.match(elements['login-status'].textContent,/超时/);assert.equal(button.disabled,false);assert.equal(aborted,1);assert.equal(saved.size,0);assert.equal(elements.workspace.hidden,true);
  mode='body-stalled';await submit();assert.equal(aborted,2);assert.equal(button.disabled,false);
  mode='offline';await submit();assert.match(elements['login-status'].textContent,/无法连接/);assert.equal(button.disabled,false);
  saved.set('persona-token','outdated');mode='rejected';await submit();assert.match(elements['login-status'].textContent,/令牌无效/);assert.equal(saved.size,0);assert.equal(button.disabled,false);
  mode='form-rejected';await submit();assert.match(elements['login-status'].textContent,/令牌无效/);assert.equal(button.disabled,false);
  mode='valid';projectFailure=true;await submit();assert.equal(elements.workspace.hidden,true);assert.equal(saved.size,0);assert.match(elements['login-status'].textContent,/工作空间/);
  projectFailure=false;await submit();assert.equal(elements.workspace.hidden,false);assert.equal(elements.login.hidden,true);assert.equal(elements.identity.textContent,'owner');assert.equal(saved.get('persona-token'),'demo-owner');assert.equal(button.disabled,false);
  console.log('PASS: stalled headers/body abort, offline feedback, invalid token cleanup, bootstrap failure, retry without refresh');
})().catch(error=>{console.error(error);process.exitCode=1;});
