'use strict';
window.PersonaChatSubmit={create({context,api,prepare,accepted,denied,storage,crypto,elements}){
 const {send,status,recheck,retry}=elements,storeKey='platform-chat-submissions-v1';
 const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
 let revision=0,pending=null,feedback='',feedbackScope='',notFound=null;
 const scope=c=>JSON.stringify([c.userId,c.projectId,c.personaId,c.session?.id]);
 const live=op=>op.revision===revision&&context().active&&op.scope===scope(context())&&op.epoch===context().epoch&&op.nonce===context().nonce;
 function records(){const value=JSON.parse(storage.getItem(storeKey)||'{}');if(!value||typeof value!=='object'||Array.isArray(value))throw Error('invalid records');return value;}
 function read(c){const value=records()[scope(c)];if(value&&(!uuid.test(value.request_id)||!/^[0-9a-f]{64}$/.test(value.digest)||Object.keys(value).length!==2))throw Error('invalid operation');return value;}
 function save(c,value){const all=records();if(value)all[scope(c)]=value;else delete all[scope(c)];storage.setItem(storeKey,JSON.stringify(all));}
 function render(){
  const c=context();let value,broken=false;try{value=read(c);}catch(error){broken=true;}
  const busy=!!pending&&live(pending),active=c.active&&!!c.session;
  send.disabled=!active||busy||broken||!!value||!!c.session?.active_job?.length;
  recheck.hidden=!value&&!broken;retry.hidden=!value||notFound!==value.request_id;
  recheck.disabled=retry.disabled=!active||busy||broken;
  status.textContent=broken?'无法读取本次操作记录，已暂停发送。请重新登录后先核对交流历史。':busy?'正在核对本次发送…':feedbackScope===scope(c)&&feedback?feedback:value?'上次发送结果待核对。请先核对本次发送，不会自动重发。':'';
 }
 async function digest(body){const bytes=new TextEncoder().encode(JSON.stringify([body.message,body.image??null]));return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');}
 function validReceipt(value,operation){return value?.status==='ACCEPTED'&&value.request_id===operation.request_id&&uuid.test(value.message_id||'')&&uuid.test(value.job_id||'');}
 async function finish(op,operation,receipt,draft){
  if(!live(op))return;
  if(!validReceipt(receipt,operation))throw Error('unconfirmed receipt');
  if(!draft){try{const value=await prepare();if(value&&await digest(value.body)===operation.digest)draft=value;}catch(error){}if(!live(op))return;}
  const stored=read(context());if(stored?.request_id===operation.request_id)save(context(),null);
  notFound=null;feedback='消息已保存。';feedbackScope=op.scope;
  if(draft)draft.clearIfUnchanged();
  try{await accepted(op.sessionId);}catch(error){if(scope(context())===op.scope)feedback='消息已保存，显示刷新失败。请刷新回应状态，不要重复发送。';}
 }
 async function perform(mode){
  const c=context();if(!c.active||!c.session||(pending&&live(pending)))return;
  if(mode==='new'&&c.session.active_job?.length)return;
  const op={revision,scope:scope(c),epoch:c.epoch,nonce:c.nonce,sessionId:c.session.id};pending=op;feedback='';feedbackScope=op.scope;render();
  try{
   let operation=read(c);
   if(mode==='check'){
    if(!operation)return;
    try{const receipt=await api('/api/conversations/'+encodeURIComponent(c.session.id)+'/submissions/'+operation.request_id);await finish(op,operation,receipt);}
    catch(error){if(!live(op))return;if(error.status===404&&error.code==='submission_not_found'){notFound=operation.request_id;feedback='暂未查到保存记录。如需重试，请输入完全相同的原消息，再点击“重试本次发送”；仍使用原操作编号。';}else throw error;}
   }else{
    if(mode==='new'&&operation)return;
    if(mode==='retry'&&(!operation||notFound!==operation.request_id))return;
    const draft=await prepare();if(!draft||!live(op))return;
    const hash=await digest(draft.body);if(!live(op))return;
    if(operation&&hash!==operation.digest){feedback='内容与待核对的原消息不同，未发送。请先核对或重新输入原消息。';return;}
    if(!operation){operation={request_id:crypto.randomUUID(),digest:hash};save(c,operation);}
    if(!live(op))return;
    op.writeStarted=true;
    const job=await api('/api/conversations/'+encodeURIComponent(c.session.id)+'/messages',{...draft.body,request_id:operation.request_id});
    await finish(op,operation,job.submission,draft);
   }
  }catch(error){
   if(!live(op))return;
   if([401,403,404].includes(error.status)){try{save(c,null);}catch(ignored){}denied(error);}
   else feedback=mode==='check'?'暂时无法核对本次发送，请再次核对；不会自动重发。':op.writeStarted?'发送结果尚未确认，请先核对本次发送；不会自动重发。':'本次未发送：无法读取输入或保存操作编号，请检查后重试。';
  }finally{if(pending===op)pending=null;render();}
 }
 recheck.onclick=()=>perform('check');retry.onclick=()=>perform('retry');
 return {submit:event=>{event?.preventDefault();return perform('new');},render,reset(){revision++;pending=null;feedback='';notFound=null;render();},clear(){revision++;pending=null;feedback='';notFound=null;try{storage.removeItem(storeKey);}catch(error){}render();}};
}};
