const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const base='server/src/main/resources/static/';
function requestContext(kind){
 const c=vm.createContext({window:{},AbortController,TypeError,FormData:class{},setTimeout,clearTimeout,token:'fixture',state:{token:'fixture'},errors:{role_denied:'当前角色无权执行此操作'},errorLabels:{role_denied:'当前角色没有操作权限'}});
 const shared=base+'platform-request.js';if(fs.existsSync(shared)){vm.runInContext(fs.readFileSync(shared,'utf8'),c);c.PlatformRequest=c.window.PlatformRequest;}
 const s=fs.readFileSync(base+(kind==='studio'?'persona.js':'app.js'),'utf8');vm.runInContext(s.slice(s.indexOf('async function api('),s.indexOf(kind==='studio'?'function run(':'function bind(')),c);c.API_TIMEOUT_MS=20;return c;
}
(async()=>{
 for(const kind of ['studio','console']){
  const c=requestContext(kind),call=()=>vm.runInContext("api('/api/fixture')",c);
  c.fetch=async()=>{throw new TypeError('Failed to fetch');};
  await assert.rejects(call(),e=>/无法连接服务/.test(e.message)&&/查看任务记录/.test(e.message));
  c.fetch=async()=>({ok:false,status:403,json:async()=>({error:'role_denied'})});
  await assert.rejects(call(),e=>/当前角色/.test(e.message)&&!/网络|连接服务/.test(e.message)&&e.code==='role_denied'&&e.status===403);
  c.fetch=async()=>({ok:false,status:401,json:async()=>({error:'authentication_required'})});
  await assert.rejects(call(),e=>e.code==='authentication_required'&&e.status===401&&!/连接服务/.test(e.message));
  for(const status of [401,403]){c.fetch=async()=>({ok:false,status,json:async()=>{throw new TypeError('error body reset');}});await assert.rejects(call(),e=>e.status===status&&!/连接服务/.test(e.message)&&!c.PlatformRequest.isTransportError(e));}
  const circular={};circular.self=circular;let serializedCalls=0;c.fetch=async()=>{serializedCalls++;};c.circular=circular;
  await assert.rejects(vm.runInContext(kind==='studio'?"api('/api/fixture',circular)":"api('/api/fixture',{body:circular})",c),e=>e.name==='TypeError'&&!/连接服务/.test(e.message));assert.equal(serializedCalls,0);
  c.fetch=async()=>({ok:false,status:409,json:async()=>({error:'published_version_changed'})});
  await assert.rejects(call(),e=>e.code==='published_version_changed'&&e.status===409&&!/连接服务/.test(e.message));
  c.fetch=async()=>({ok:false,status:500,json:async()=>{throw new SyntaxError('invalid error body');}});
  await assert.rejects(call(),e=>/500/.test(e.message)&&!/连接服务/.test(e.message));
  c.fetch=async()=>({ok:true,text:async()=>'{broken'});
  await assert.rejects(call(),e=>e.name==='SyntaxError'&&!/连接服务/.test(e.message));
  c.fetch=async()=>({ok:true,text:async()=>{throw new TypeError('body connection reset');}});
  await assert.rejects(call(),/无法连接服务/);
  c.setTimeout=fn=>setTimeout(fn,10);
  c.fetch=async(_,o)=>new Promise((_,reject)=>o.signal.addEventListener('abort',()=>reject(new TypeError('aborted'))));
  await assert.rejects(call(),e=>/请求超时/.test(e.message)&&/查看任务记录/.test(e.message));
  c.fetch=async()=>({ok:true,text:async()=>'{"saved":true}'});
  assert.equal((await call()).saved,true,'explicit retry succeeds');
  let sent=0;c.fetch=async()=>{sent++;throw new TypeError('Failed to fetch');};await assert.rejects(call());assert.equal(sent,1,'no automatic POST/GET retry');
 }
 console.log('PASS: both actual API adapters normalize transport only; HTTP/auth/business/parse errors preserved; timeout, body reset and explicit retry');
})().catch(e=>{console.error(e);process.exitCode=1;});
