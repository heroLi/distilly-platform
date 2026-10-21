'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),{webcrypto}=require('node:crypto');
function setup(stored=new Map()){
 const c=vm.createContext({window:{},TextEncoder,Uint8Array});vm.runInContext(fs.readFileSync('server/src/main/resources/static/material-upload.js','utf8'),c);
 const state={project:'p1',role:'OWNER',userId:'owner',revision:1};let saved=0,returns=0,knowledge=0,opens=0,refreshError=false,returnError=false;
 const e={modal:{open:false},file:{value:'',files:[]},error:{textContent:''},only:{disabled:false},more:{disabled:false,hidden:false},recheck:{hidden:true},retry:{hidden:true}};
 const material='11111111-1111-4111-8111-111111111111';let handler=async(path,options)=>({id:material,submission:{request_id:options.body.fields.request_id,material_id:material,status:'ACCEPTED'}});const calls=[],notices=[];
 const ui=c.window.MaterialUploadFlow.create({context:()=>state,capture:()=>state.revision,current:scope=>scope===state.revision,api:async(path,options)=>{calls.push({path,options});return handler(path,options);},openModal(){opens++;e.modal.open=true;},closeModal(){ui.cancel();e.modal.open=false;},refresh:async()=>{saved++;if(refreshError)throw Error('list offline');},continueKnowledge:async()=>knowledge++,notify:(text,error)=>notices.push({text,error}),elements:e,formData:()=>({fields:{},append(k,v){this.fields[k]=v;}}),crypto:webcrypto,storage:{getItem:k=>stored.get(k)||null,setItem:(k,v)=>stored.set(k,v),removeItem:k=>stored.delete(k)}});
 const completed=async id=>{assert.equal(id,material);returns++;if(returnError)throw Error('return failed');};
 return {c,state,e,ui,calls,notices,stored,material,record:()=>JSON.parse(stored.get('platform-material-submissions-v1')||'{}')[JSON.stringify([state.userId,state.project])],open:(guided=true)=>ui.open(guided?{completed}:{}),file:(size=12,text='合成输入',name='synthetic.txt')=>{e.file.value=name;e.file.files=[{name,size,arrayBuffer:async()=>new TextEncoder().encode(text).buffer}];},set:fn=>handler=fn,saved:()=>saved,returns:()=>returns,knowledge:()=>knowledge,opens:()=>opens,refreshFail:()=>refreshError=true,returnFail:()=>returnError=true};
}
async function until(fn){const end=Date.now()+2000;while(Date.now()<end){if(fn())return;await new Promise(r=>setTimeout(r,1));}throw Error('fixture did not reach API');}
if(require.main===module)(async()=>{
 let count=0,h=setup();h.open();assert.match(h.e.only.textContent,/返回人格/);assert.equal(h.e.more.hidden,true);h.file();await h.ui.submit({submitter:h.e.only});assert.equal(h.calls.length,1);assert.equal(h.saved(),1);assert.equal(h.returns(),1);assert.equal(h.e.modal.open,false);count++;
 h=setup();h.open(false);h.file();await h.ui.submit({submitter:h.e.more});assert.equal(h.knowledge(),1);assert.equal(h.returns(),0);count++;
 h=setup();h.open(false);h.file();await h.ui.submit({submitter:h.e.only});assert.equal(h.knowledge(),0);count++;
 for(const role of ['VIEWER','']){h=setup();h.state.role=role;h.open();assert.equal(h.opens(),0);assert.equal(h.calls.length,0);count++;}
 h=setup();h.open();await h.ui.submit({});assert.equal(h.calls.length,0);assert.match(h.e.error.textContent,/请选择/);h.file(1048577);await h.ui.submit({});assert.equal(h.calls.length,0);assert.match(h.e.error.textContent,/1 MiB/);assert.equal(h.e.only.disabled,false);count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('UTF-8 required'),{status:422});});await h.ui.submit({});assert.equal(h.e.modal.open,true);assert.equal(h.e.file.value,'synthetic.txt');assert.equal(h.e.only.disabled,false);h.set(async(path,options)=>({id:'11111111-1111-4111-8111-111111111111',submission:{request_id:options.body.fields.request_id,material_id:'11111111-1111-4111-8111-111111111111',status:'ACCEPTED'}}));await h.ui.submit({});assert.equal(h.returns(),1);count++;
 h=setup();h.open();h.file();let release;h.set(()=>new Promise(resolve=>release=resolve));const first=h.ui.submit({});await until(()=>release);await h.ui.submit({});h.ui.cancel();h.e.modal.open=false;h.open();assert.equal(h.opens(),1);assert.equal(h.calls.length,1);release({id:'m1'});await first;assert.equal(h.returns(),0);assert.equal(h.saved(),0);count++;
 for(const ending of ['close','scope'])for(const outcome of ['resolve','reject']){
  h=setup();h.open();h.file();let yes,no;h.set(()=>new Promise((resolve,reject)=>{yes=resolve;no=reject;}));const pending=h.ui.submit({});await until(()=>yes);
  if(ending==='close'){h.ui.cancel();h.e.modal.open=false;}else h.state.revision++;
  outcome==='resolve'?yes({id:'m1'}):no(Error('old error'));await pending;assert.equal(h.returns(),0);assert.equal(h.saved(),0);assert.equal(h.e.error.textContent,'');count++;
 }
 for(const status of [401,403,404]){h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('denied'),{status});});await h.ui.submit({});assert.match(h.e.error.textContent,/权限/);assert.equal(h.e.only.disabled,true);await h.ui.submit({});assert.equal(h.calls.length,1);assert.equal(h.returns(),0);count++;}
 for(const status of [undefined,500]){h=setup();h.open();h.file();h.set(async()=>{throw Object.assign(Error('unconfirmed'),{status});});await h.ui.submit({});assert.match(h.e.error.textContent,/核对.*上次上传/);assert.equal(h.e.only.disabled,true);await h.ui.submit({});assert.equal(h.calls.length,1);count++;}
 h=setup();h.open();h.file();h.refreshFail();await h.ui.submit({});assert.equal(h.returns(),1);assert.equal(h.calls.length,1);assert.ok(h.notices.some(x=>/材料已保存.*列表刷新失败/.test(x.text)));count++;
 h=setup();h.open();h.file();h.returnFail();await h.ui.submit({});assert.equal(h.calls.length,1);assert.ok(h.notices.some(x=>/材料已保存.*后续页面/.test(x.text)));count++;
 // Actual Studio empty-state handler reuses the existing workspace action.
 let projectClicks=0,editors=0;const fields={'create-persona':{},'empty-create':{},'edit-persona':{},'close-dialog':{},'new-project':{click(){projectClicks++;}}};
 const x=vm.createContext({state:{project:null},$:id=>fields[id],openEditor:()=>editors++});
 const source=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');vm.runInContext(source.split('\n').find(line=>line.startsWith("$('create-persona').onclick")),x);
 fields['empty-create'].onclick();assert.equal(projectClicks,1);assert.equal(editors,0);x.state.project={id:'p1'};fields['empty-create'].onclick();assert.equal(editors,1);count++;
 console.log('PASS:',count,'actual upload/first-entry UI scenarios; reused dialog, explicit continuation, validation, saved-vs-refresh errors, duplicate guards and stale/denied outcomes');
})().catch(error=>{console.error(error);process.exitCode=1;});

module.exports={setup,until};
