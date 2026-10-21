'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const base='server/src/main/resources/static/';
const app=fs.readFileSync(base+'app.js','utf8'),studio=fs.readFileSync(base+'persona.js','utf8');
(async()=>{
 let checks=0;
 // Exercise the existing library login; API mode metadata never proves Worker availability.
 for(const demo of [true,false])for(const model_mode of ['gateway','mock',undefined]){
  const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,{textContent:'',hidden:false});return elements.get(id);};
  const c=vm.createContext({window:{},meDemo:false,token:'synthetic',workspaceReads:{invalidate(){},read:async()=>({value:{id:'owner',demo,model_mode}})},api(){},PlatformSession:{saveToken(){}},$:get,loadProjects:async()=>{},setInterval(){}});
  vm.runInContext(app.split('\n').find(line=>line.startsWith('async function connect(')),c);await c.connect();
  assert.match(get('mode').textContent,/后台执行状态以任务为准/);assert.match(get('mode').textContent,/真实文字出口仍关闭/);assert.doesNotMatch(get('mode').textContent,/真实模型交流|已配置模型/);checks++;
 }
 // The actual poll distinguishes queued generation from a claimed task, with no new writes.
 const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,{textContent:'',checked:false,contains:()=>false});return elements.get(id);};
 let jobs=[],read;
 const state={token:'synthetic',epoch:1,sessionNonce:1,persona:{id:'p',versions:[]},session:null,pollBusy:false};
 const c=vm.createContext({state,$:get,window:{},document:{activeElement:null},api:async()=>read?await read:jobs,loadJobs:async()=>{},denyChat:()=>false});
 vm.runInContext(studio.split('\n').find(line=>line.startsWith('async function poll(')),c);
 jobs=[{kind:'PERSONA',status:'QUEUED'}];await c.poll();assert.match(get('generation-status').textContent,/排队中.*尚未确认后台领取/);checks++;
 jobs=[{kind:'PERSONA',status:'RUNNING'}];await c.poll();assert.match(get('generation-status').textContent,/已被后台领取/);assert.doesNotMatch(get('generation-status').textContent,/真实模型|已完成/);checks++;
 jobs=[{kind:'PERSONA',status:'FAILED',error_code:'MODEL_BUDGET_NOT_VERIFIED'}];await c.poll();assert.equal(get('generation-status').textContent,'');checks++;
 let release;read=new Promise(resolve=>release=resolve);const late=c.poll();state.epoch++;get('generation-status').textContent='current scope';release([{kind:'PERSONA',status:'RUNNING'}]);await late;assert.equal(get('generation-status').textContent,'current scope');checks++;
 state.pageHidden=true;read=null;jobs=[{kind:'PERSONA',status:'RUNNING'}];await c.poll();assert.equal(get('generation-status').textContent,'current scope');checks++;
 console.log('PASS:',checks,'actual login/poll readiness scenarios; API metadata is not availability, queued is not offline, stale scope cannot overwrite current status');
})().catch(error=>{console.error(error);process.exitCode=1;});
