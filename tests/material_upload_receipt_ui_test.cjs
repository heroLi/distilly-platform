'use strict';
const assert=require('node:assert/strict'),{setup,until}=require('./material_upload_ui_test.cjs');
const receipt=(h,id)=>({request_id:id,material_id:h.material,status:'ACCEPTED'});
(async()=>{
 let count=0,h=setup();h.open();h.file();h.set(async()=>{throw Error('lost accepted response');});await h.ui.submit({});const id=h.record().request_id;
 assert.equal(h.e.only.disabled,true);assert.equal(h.e.recheck.hidden,false);assert.equal(h.e.retry.hidden,true);assert.ok(!JSON.stringify(h.record()).includes('synthetic.txt'));assert.ok(!JSON.stringify(h.record()).includes('合成输入'));
 h.set(async()=>receipt(h,id));await h.e.recheck.onclick();assert.equal(h.calls.length,2);assert.equal(h.calls[1].options,undefined);assert.equal(h.saved(),1);assert.equal(h.returns(),1);assert.equal(h.record(),undefined);count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});const shared=h.stored,key=h.record().request_id;h.ui.cancel();h.e.modal.open=false;
 const restored=setup(shared);restored.open();assert.equal(restored.calls.length,0);assert.equal(restored.e.only.disabled,true);restored.set(async()=>receipt(restored,key));await restored.e.recheck.onclick();assert.equal(restored.calls.length,1);assert.equal(restored.calls[0].options,undefined);assert.equal(restored.record(),undefined);count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Error('before commit');});await h.ui.submit({});const original=h.record().request_id;
 h.set(async()=>{throw Object.assign(Error('not found'),{status:404,code:'material_submission_not_found'});});await h.e.recheck.onclick();assert.equal(h.e.retry.hidden,false);
 for(const file of [[12,'不同内容','synthetic.txt'],[12,'合成输入','renamed.txt']]){h.file(...file);await h.e.retry.onclick();assert.equal(h.calls.length,2);assert.match(h.e.error.textContent,/文件名或内容.*不同/);count++;}
 h.file();h.set(async(path,options)=>({id:h.material,submission:receipt(h,options.body.fields.request_id)}));const sending=h.e.retry.onclick();await h.e.retry.onclick();await sending;assert.equal(h.calls.length,3);assert.equal(h.calls[2].options.body.fields.request_id,original);assert.equal(h.record(),undefined);count++;
 for(const ending of ['close','scope'])for(const outcome of ['resolve','reject']){
  h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});let yes,no;h.set(()=>new Promise((resolve,reject)=>{yes=resolve;no=reject;}));const reading=h.e.recheck.onclick();await until(()=>yes);
  if(ending==='close'){h.ui.cancel();h.e.modal.open=false;}else h.state.revision++;
  outcome==='resolve'?yes(receipt(h,h.record().request_id)):no(Object.assign(Error('old denied'),{status:403}));await reading;assert.equal(h.saved(),0);assert.equal(h.returns(),0);assert.ok(h.record());count++;
 }
 for(const mode of ['user','project']){
  h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});const other=setup(h.stored);other.state[mode==='user'?'userId':'project']='different';other.open();assert.equal(other.e.only.disabled,false);assert.equal(other.e.recheck.hidden,true);assert.equal(other.record(),undefined);count++;
 }
 for(const status of [401,403,404]){
  h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});h.set(async()=>{throw Object.assign(Error('denied'),{status,code:'project_access_denied'});});await h.e.recheck.onclick();assert.equal(h.e.only.disabled,true);assert.equal(h.record(),undefined);assert.equal(h.returns(),0);count++;
 }
 h=setup();h.open();h.file();h.set(async()=>({id:h.material,submission:{request_id:'wrong',material_id:h.material,status:'ACCEPTED'}}));await h.ui.submit({});assert.equal(h.saved(),0);assert.ok(h.record());assert.equal(h.e.recheck.hidden,false);count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});h.set(async()=>receipt(h,h.record().request_id));h.returnFail();await h.e.recheck.onclick();assert.equal(h.record(),undefined);assert.ok(h.notices.some(n=>/材料已保存.*后续页面/.test(n.text)));count++;
 h=setup();h.open();h.file();h.set(async()=>{throw Error('lost');});await h.ui.submit({});h.ui.clear();assert.equal(h.stored.has('platform-material-submissions-v1'),false);count++;
 h=setup(new Map([['platform-material-submissions-v1','invalid JSON']]));h.open();h.file();await h.ui.submit({});assert.equal(h.calls.length,0);assert.equal(h.e.only.disabled,true);count++;
 h=setup();h.open();h.file();h.stored.set=()=>{throw Error('quota');};await h.ui.submit({});assert.equal(h.calls.length,0);assert.match(h.e.error.textContent,/本次未上传/);count++;
 console.log('PASS:',count,'actual TXT upload receipt UI scenarios; metadata-only reload, GET recovery, same-file retry, conflict guards, cross-scope keys, late outcomes and logout cleanup');
})().catch(error=>{console.error(error);process.exitCode=1;});
