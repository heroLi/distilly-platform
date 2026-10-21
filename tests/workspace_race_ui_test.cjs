const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const base='server/src/main/resources/static/',app=fs.readFileSync(process.env.WORKSPACE_APP_SOURCE||base+'app.js','utf8'),studio=fs.readFileSync(process.env.WORKSPACE_STUDIO_SOURCE||base+'persona.js','utf8');
function extract(source,name){const start=source.search(new RegExp('^(?:async )?function '+name+'\\(','m'));assert.ok(start>=0,name);const tail=source.slice(start),next=tail.slice(1).search(/\n(?:(?:async )?function |const |let |bind\(|document\.|if\(|window\.|\$\()/);return next<0?tail:tail.slice(0,next+1);}
class Element{constructor(){this.children=[];this.dataset={};this.attributes={};this.value='';this.hidden=false;this.open=true;this.textContent='';this.innerHTML='';this.disabled=false;this.isConnected=true;this.classList={remove(){},add(){}};}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this.textContent='';}setAttribute(k,v){this.attributes[k]=v;}addEventListener(){}close(){this.open=false;this.hidden=true;}focus(){}}
function setup(){
 const elements=new Proxy({}, {get:(o,id)=>o[id]||(o[id]=new Element())}),deferred=[],calls=[],errors=[],rows={};let delay=()=>false;
 const c=vm.createContext({window:{addEventListener:(n,f)=>{c.events[n]=f;}},events:{},project:'A',token:'fixture',role:'OWNER',profile:null,poll:null,page:'experiences',meId:'owner',selectedMaterials:new Set(),materialInventory:[],lists:{material:{page:1,size:10},task:{page:1,size:10},archive:{page:1,size:10}},listRevision:{},taskPreflightRevision:0,$:id=>elements[id],node:()=>new Element(),clearInterval(){},setInterval(){},PlatformSession:{selectWorkspace(){},clear(){}},location:{reload(){c.reloaded=true;}},document:{querySelectorAll:()=>[],body:{classList:{remove(){}}}},ExperienceUI:{closeAll(){elements.detail.hidden=true;},list:(host,key,data)=>{rows[key]=JSON.parse(JSON.stringify(data));},action:(label,fn)=>({label,fn})},renderTaskTopics:data=>{elements['task-profile'].rows=data.map(p=>p.id);},loadMaterials:async()=>{},loadTasks:async()=>{},showPage:()=>{c.shown=true;},resetPublishedKnowledge(){},closeModal:()=>{},message:(text)=>errors.push(text),table:()=>({t:new Element(),body:new Element()}),cell:()=>new Element(),bind:(id,event,fn)=>{c.bound[id]=fn;},bound:{}});
 const normal=path=>path==='/api/projects'?[{id:'A',title:'A',role:'OWNER'},{id:'B',title:'B',role:'OWNER'}]:path.includes('/profiles')?[{id:c.project+'-topic',title:c.project+' fresh',published_id:null}]:[];
 c.api=path=>{calls.push(path);if(delay(path))return new Promise((resolve,reject)=>deferred.push({path,resolve,reject}));return Promise.resolve(normal(path));};
 if(fs.existsSync(base+'ui-read-scope.js')){vm.runInContext(fs.readFileSync(base+'ui-read-scope.js','utf8'),c);c.UiReadScope=c.window.UiReadScope;vm.runInContext('const workspaceReads=UiReadScope.create(()=>[token,project,role]);',c);}
 for(const name of ['loadMembers','loadProfiles','loadAudit','loadArchived','loadWorkspaceList','chooseProject','topicDetailVisible','loadVersions','refreshCurrent'])vm.runInContext(extract(app,name),c);
 const logout=app.split('\n').find(s=>s.startsWith("bind('logout'"));vm.runInContext(logout,c);
 const pagehide=app.split('\n').find(s=>s.startsWith("window.addEventListener('pagehide'"));if(pagehide)vm.runInContext(pagehide,c);
 const switchTo=async id=>{elements.projects.value=id;elements.projects.selectedOptions=[{textContent:id,dataset:{role:'OWNER'}}];await c.chooseProject();};
 return {c,elements,deferred,calls,errors,rows,switchTo,delay:fn=>delay=fn};
}
const call=(h,name)=>h.c[name]().catch(e=>{h.errors.push(e.message);});
(async()=>{
 // Exact browser report: A profiles is slow; B completes first, then A returns.
 let h=setup();h.delay(p=>p==='/api/projects/A/profiles');const old=call(h,'loadProfiles');await h.switchTo('B');h.elements['task-new-topic-title'].value='B未提交标题';h.deferred[0].resolve([{id:'A-old',title:'A stale'}]);await old;assert.equal(h.rows['知识专题'][0].title,'B fresh');assert.deepEqual(h.elements['task-profile'].rows,['B-topic']);assert.equal(h.elements['task-new-topic-title'].value,'B未提交标题');
 // A->B->A: equality of project ID alone is insufficient.
 h=setup();let first=true;h.delay(p=>p==='/api/projects/A/profiles'&&first?(first=false,true):false);const aba=call(h,'loadProfiles');await h.switchTo('B');await h.switchTo('A');h.deferred[0].resolve([{id:'old',title:'before A-B-A'}]);await aba;assert.equal(h.rows['知识专题'][0].title,'A fresh');
 for(const name of ['loadProfiles','loadMembers','loadAudit','loadArchived','loadWorkspaceList'])for(const outcome of ['resolve','reject']){
  h=setup();h.delay(()=>true);const pending=call(h,name);assert.equal(h.deferred.length,1,name);h.delay(()=>false);await h.switchTo('B');const snapshot=JSON.stringify({rows:h.rows,archived:h.elements.archived.children,topics:h.elements['task-profile'].rows});h.deferred[0][outcome](outcome==='resolve'?[{id:'obsolete',title:'old A',user_id:'old A',role:'OWNER'}]:Error('旧A失败不应显示'));await pending;assert.deepEqual(h.errors,[],name+' stale errors discarded');assert.equal(JSON.stringify({rows:h.rows,archived:h.elements.archived.children,topics:h.elements['task-profile'].rows}),snapshot,name+' stale successes discarded');
 }
 // Same context newest invocation wins, including error arrival order.
 h=setup();h.delay(()=>true);const membersOld=call(h,'loadMembers');h.delay(()=>false);await h.c.loadMembers();h.deferred[0].resolve([{user_id:'obsolete-user',role:'OWNER'}]);await membersOld;assert.deepEqual(h.rows['成员'],[]);
 for(const ending of ['logout','pagehide'])for(const outcome of ['resolve','reject']){
  h=setup();h.delay(()=>true);const pending=call(h,'loadProfiles');if(ending==='logout')await h.c.bound.logout();else{assert.ok(h.c.events.pagehide);h.c.events.pagehide();}
  h.deferred[0][outcome](outcome==='resolve'?[{id:'old',title:'after exit'}]:Error('late after exit'));await pending;assert.equal(h.rows['知识专题'],undefined);assert.deepEqual(h.errors,[]);
 }
 h=setup();h.delay(()=>true);const current=call(h,'loadProfiles');h.deferred[0].reject(Error('当前请求有效错误'));await current;assert.deepEqual(h.errors,['当前请求有效错误']);
 // Detail closes while its first read waits: no further fetch/paint, error also ignored.
 for(const outcome of ['resolve','reject']){
  h=setup();h.c.profile='topic';h.c.loadTopicTasks=async()=>{};h.delay(()=>true);h.elements.detail.hidden=false;const pending=call(h,'loadVersions');h.elements.detail.hidden=true;h.deferred[0][outcome](outcome==='resolve'?[]:Error('closed detail failure'));await pending;assert.equal(h.calls.length,1);assert.deepEqual(h.errors,[]);assert.equal(h.elements.versions.children.length,0);
 }
 h=setup();h.c.profile='topic';h.c.loadTopicTasks=async()=>{};h.delay(()=>true);const nativeDialog={open:true};h.elements.detail.closest=()=>nativeDialog;const nativePending=call(h,'loadVersions');nativeDialog.open=false;h.deferred[0].resolve([]);await nativePending;assert.equal(h.calls.length,1);assert.deepEqual(h.errors,[]);
 // A startup sequence cannot resume in B after A's material read completes.
 h=setup();let release;h.c.loadMaterials=()=>new Promise(r=>release=r);h.elements.projects.value='A';h.elements.projects.selectedOptions=[{textContent:'A',dataset:{role:'OWNER'}}];const choosing=h.c.chooseProject();h.c.loadMaterials=async()=>{};await h.switchTo('B');const count=h.calls.length;release();await choosing;assert.equal(h.calls.length,count,'obsolete startup chain must stop');
 // Existing persona epoch guards plus per-host request order: latest memory wins.
 function persona(){const el=new Proxy({}, {get:(o,id)=>o[id]||(o[id]=new Element())}),queue=[];let slow=true;
  const c=vm.createContext({window:{},chatRecovery:null,chatSubmit:null,state:{token:'fixture',epoch:1,project:{id:'A'},persona:{id:'person-A'},session:null,sessionNonce:0},$:id=>el[id],canEdit:()=>true,escapeHtml:String,date:String,api:path=>slow?new Promise((resolve,reject)=>queue.push({path,resolve,reject})):Promise.resolve([{id:'memory',body:'new confirmed',status:'CONFIRMED'}])});
  vm.runInContext(fs.readFileSync(base+'ui-read-scope.js','utf8'),c);c.UiReadScope=c.window.UiReadScope;vm.runInContext('const studioReads=UiReadScope.create(()=>[state.token,state.epoch,state.project?.id,state.persona?.id]);',c);
  for(const name of ['loadMemories','loadJobs','loadProjects','loadMembers','selectPersona'])vm.runInContext(extract(studio,name),c);
  return {c,el,queue,fast:()=>slow=false};}
 let s=persona(),p=s.c.loadMemories();s.fast();await s.c.loadMemories();s.queue[0].resolve([{id:'memory',body:'old pending',status:'PENDING'}]);await p;assert.match(s.el.memories.innerHTML,/new confirmed/);assert.doesNotMatch(s.el.memories.innerHTML,/old pending/);
 for(const outcome of ['resolve','reject']){s=persona();p=s.c.loadMemories();s.c.state.epoch++;s.c.state.persona={id:'person-B'};s.queue[0][outcome](outcome==='resolve'?[{id:'memory',body:'A private',status:'CONFIRMED'}]:Error('old persona failed'));await p;assert.equal(s.el.memories.innerHTML,'');}
 for(const outcome of ['resolve','reject']){s=persona();p=s.c.loadProjects();s.c.state.token='';s.c.state.epoch++;s.queue[0][outcome](outcome==='resolve'?[]:Error('old workspace after logout'));await p;assert.equal(s.el.projects.innerHTML,'');}
 s=persona();s.el.members.textContent='旧账号成员';s.el['archived-personas'].innerHTML='旧账号归档人物';s.c.state.project=null;await s.c.loadMembers();assert.equal(s.el.members.textContent,'请先选择工作空间');assert.equal(s.el['archived-personas'].innerHTML,'');
 s=persona();s.c.stopSpeech=()=>{};s.c.document={querySelectorAll:()=>[]};s.c.renderPersona=()=>{};for(const name of ['loadMaterials','loadConversations','loadMemories','loadJobs','loadKnowledge','loadPersonalityBindings'])s.c[name]=async()=>{};s.el.memories.innerHTML='A private row';s.el.message.value='未提交的合成草稿';p=s.c.selectPersona('person-B');s.queue[0].resolve({id:'person-B'});await p;assert.equal(s.el.memories.innerHTML,'');assert.equal(s.el.message.value,'未提交的合成草稿');
 console.log('PASS: actual workspace loaders/initialization handle slow A-fast B, ABA, same-host order, stale success/error, logout/pagehide/closed detail; persona memory latest-read and epoch isolation; current errors/drafts preserved');
})().catch(e=>{console.error(e);process.exitCode=1;});
