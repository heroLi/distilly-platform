// Regression checks against the actual browser question handler and polling code.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('server/src/main/resources/static/app.js', 'utf8');
const elements = {};
for (const id of ['question-status','question-submit','question-panel','question','answer','job']) {
  elements[id] = {textContent:'',value:'',disabled:false,append(){},setAttribute(){},focus(){this.focused=true;}};
}
let handler, interval, calls=0, status='RUNNING', unavailable=false;
const context = vm.createContext({
  $:id=>elements[id],profile:'profile-1',role:'OWNER',poll:null,
  bind:(id,event,fn)=>{handler=fn;},node:()=>({addEventListener(){}}),message(){},
  clearInterval(){interval=null;},setInterval:fn=>{interval=fn;return 1;},
  api:async(path,options)=>{
    calls++;
    if(options?.method==='POST')return {id:'job-1'};
    if(unavailable)throw Error('temporary network failure');
    return {status,attempt:1,progress:{completed:1,total:1},result:status==='SUCCEEDED'?{answer:'已核对发布方案',version_id:'v1',citations:['c1']}:null};
  },
  loadTasks:async()=>{throw Error('task list unavailable');},
  refreshCurrent:async()=>{throw Error('audit unavailable');}
});
context.window=context.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),context);context.UiReadScope=context.window.UiReadScope;
vm.runInContext("const workspaceReads=UiReadScope.create(()=>[typeof token==='undefined'?'':token,typeof project==='undefined'?'':project,typeof role==='undefined'?'':role]);",context);
vm.runInContext(source.slice(source.indexOf('function questionState('),source.indexOf("bind('login-form'")),context);
vm.runInContext(source.slice(source.indexOf("bind('question-form'"),source.indexOf("bind('download'")),context);
(async()=>{
  await handler();
  assert.equal(calls,0);assert.match(elements['question-status'].textContent,/请先输入/);
  elements.question.value='发布前复核什么？';
  await handler();
  assert.equal(elements['question-submit'].disabled,true);
  assert.match(elements['question-status'].textContent,/正在回答/);
  const previous=calls;await handler();assert.equal(calls,previous,'no duplicate submission while waiting');
  unavailable=true;await interval();assert.ok(interval,'transient failure must keep polling');
  assert.match(elements['question-status'].textContent,/正在重试/);
  unavailable=false;status='SUCCEEDED';await interval();
  assert.equal(elements['question-submit'].disabled,false);
  assert.match(elements.answer.textContent,/已核对发布方案/);
  assert.match(elements.answer.textContent,/c1/);
  assert.equal(interval,null);
  for(const terminal of ['PAUSED','CANCELLED']){status=terminal;await handler();assert.equal(elements['question-submit'].disabled,false);assert.equal(interval,null,'paused/stopped questions must stop polling');}
  // Drive real HTTP error decoding through the actual form handler.
  context.token='fixture-token';context.AbortController=AbortController;context.FormData=class {};
  context.setTimeout=()=>1;context.clearTimeout=()=>{};
  vm.runInContext(source.slice(source.indexOf('const errors='),source.indexOf('function node(')),context);
  context.window={};context.TypeError=TypeError;vm.runInContext(fs.readFileSync('server/src/main/resources/static/platform-request.js','utf8'),context);context.PlatformRequest=context.window.PlatformRequest;
  vm.runInContext(source.slice(source.indexOf('async function api('),source.indexOf('function bind(')),context);
  for(const [code,label] of [['user_active_task_limit',/未结束任务已达到上限/],['user_task_submission_rate_limit',/提交过于频繁/],['platform_task_submission_rate_limit',/系统任务提交较多/]]){
    context.fetch=async()=>({ok:false,status:429,json:async()=>({error:code})});
    elements.question.value='额度恢复后再提交的原始问题';
    await assert.rejects(handler,label);
    assert.match(elements['question-status'].textContent,label);
    assert.equal(elements.question.value,'额度恢复后再提交的原始问题');
    assert.equal(elements['question-submit'].disabled,false);
    assert.equal(interval,null,'rejected admission must not start polling');
  }
  console.log('PASS: empty question, submission feedback, duplicate guard, polling recovery, answer survives task/audit failure, admission rejection retains input');
})().catch(error=>{console.error(error);process.exitCode=1;});
