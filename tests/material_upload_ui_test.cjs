'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function setup(){
 const c=vm.createContext({window:{}});vm.runInContext(fs.readFileSync('server/src/main/resources/static/material-upload.js','utf8'),c);
 const state={project:'p1',role:'OWNER',revision:1};let saved=0,returns=0,knowledge=0,opens=0,refreshError=false,returnError=false;
 const e={modal:{open:false},file:{value:'',files:[]},error:{textContent:''},only:{disabled:false},more:{disabled:false,hidden:false}};
 let handler=async()=>({id:'m1'});const calls=[],notices=[];
 const ui=c.window.MaterialUploadFlow.create({context:()=>state,capture:()=>state.revision,current:scope=>scope===state.revision,api:async(path,options)=>{calls.push({path,options});return handler();},openModal(){opens++;e.modal.open=true;},closeModal(){ui.cancel();e.modal.open=false;},refresh:async()=>{saved++;if(refreshError)throw Error('list offline');},continueKnowledge:async()=>knowledge++,notify:(text,error)=>notices.push({text,error}),elements:e,formData:()=>({append(){}})});
 const completed=async id=>{assert.equal(id,'m1');returns++;if(returnError)throw Error('return failed');};
 return {c,state,e,ui,calls,notices,open:(guided=true)=>ui.open(guided?{completed}:{}),file:(size=12)=>{e.file.value='synthetic.txt';e.file.files=[{name:'synthetic.txt',size}];},set:fn=>handler=fn,saved:()=>saved,returns:()=>returns,knowledge:()=>knowledge,opens:()=>opens,refreshFail:()=>refreshError=true,returnFail:()=>returnError=true};
}
(async()=>{
 let count=0,h=setup();h.open();assert.match(h.e.only.textContent,/返回人格/);assert.equal(h.e.more.hidden,true);h.file();await h.ui.submit({submitter:h.e.only});assert.equal(h.calls.length,1);assert.equal(h.saved(),1);assert.equal(h.returns(),1);assert.equal(h.e.modal.open,false);count++;
 h=setup();h.open(false);h.file();await h.ui.submit({submitter:h.e.more});assert.equal(h.knowledge(),1);assert.equal(h.returns(),0);count++;
 h=setup();h.open(false);h.file();await h.ui.submit({submitter:h.e.only});assert.equal(h.knowledge(),0);count++;
 for(const role of ['VIEWER','']){h=setup();h.state.role=role;h.open();assert.equal(h.opens(),0);assert.equal(h.calls.length,0);count++;}
 h=setup();h.open();await h.ui.submit({});assert.equal(h.calls.length,0);assert.match(h.e.error.textContent,/请选择/);h.file(1048577);await h.ui.submit({});assert.equal(h.calls.length,0);assert.match(h.e.error.textContent,/1 MiB/);assert.equal(h.e.only.disabled,false);count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('UTF-8 required'),{status:422});});await h.ui.submit({});assert.equal(h.e.modal.open,true);assert.equal(h.e.file.value,'synthetic.txt');assert.equal(h.e.only.disabled,false);h.set(async()=>({id:'m1'}));await h.ui.submit({});assert.equal(h.returns(),1);count++;
 h=setup();h.open();h.file();let release;h.set(()=>new Promise(resolve=>release=resolve));const first=h.ui.submit({});await h.ui.submit({});h.ui.cancel();h.e.modal.open=false;h.open();assert.equal(h.opens(),1);assert.equal(h.calls.length,1);release({id:'m1'});await first;assert.equal(h.returns(),0);assert.equal(h.saved(),0);count++;
 for(const ending of ['close','scope'])for(const outcome of ['resolve','reject']){
  h=setup();h.open();h.file();let yes,no;h.set(()=>new Promise((resolve,reject)=>{yes=resolve;no=reject;}));const pending=h.ui.submit({});
  if(ending==='close'){h.ui.cancel();h.e.modal.open=false;}else h.state.revision++;
  outcome==='resolve'?yes({id:'m1'}):no(Error('old error'));await pending;assert.equal(h.returns(),0);assert.equal(h.saved(),0);assert.equal(h.e.error.textContent,'');count++;
 }
 for(const status of [401,403,404]){h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('denied'),{status});});await h.ui.submit({});assert.match(h.e.error.textContent,/权限/);assert.equal(h.e.only.disabled,true);await h.ui.submit({});assert.equal(h.calls.length,1);assert.equal(h.returns(),0);count++;}
 for(const status of [undefined,500]){h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('unconfirmed'),{status});});await h.ui.submit({});assert.match(h.e.error.textContent,/核对.*材料列表/);assert.equal(h.e.only.disabled,true);await h.ui.submit({});assert.equal(h.calls.length,1);count++;}
 h=setup();h.open();h.file();h.refreshFail();await h.ui.submit({});assert.equal(h.returns(),1);assert.equal(h.calls.length,1);assert.ok(h.notices.some(x=>/材料已保存.*列表刷新失败/.test(x.text)));count++;
 h=setup();h.open();h.file();h.returnFail();await h.ui.submit({});assert.equal(h.calls.length,1);assert.ok(h.notices.some(x=>/材料已保存.*后续页面/.test(x.text)));count++;
 // Actual Studio empty-state handler reuses the existing workspace action.
 let projectClicks=0,editors=0;const fields={'create-persona':{},'empty-create':{},'edit-persona':{},'close-dialog':{},'new-project':{click(){projectClicks++;}}};
 const x=vm.createContext({state:{project:null},$:id=>fields[id],openEditor:()=>editors++});
 const source=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');vm.runInContext(source.split('\n').find(line=>line.startsWith("$('create-persona').onclick")),x);
 fields['empty-create'].onclick();assert.equal(projectClicks,1);assert.equal(editors,0);x.state.project={id:'p1'};fields['empty-create'].onclick();assert.equal(editors,1);count++;
 console.log('PASS:',count,'actual upload/first-entry UI scenarios; reused dialog, explicit continuation, validation, saved-vs-refresh errors, duplicate guards and stale/denied outcomes');
})().catch(error=>{console.error(error);process.exitCode=1;});
