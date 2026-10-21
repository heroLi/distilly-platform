'use strict';
const $=id=>document.getElementById(id);
let token=PlatformSession.token()||'', project=PlatformSession.workspace()||null, profile=null, role='', poll=null, page='experiences', meId='', meDemo=false;
const selectedMaterials=new Set();
const lists={material:{page:1,size:10,q:''},task:{page:1,size:10,q:'',status:''},archive:{page:1,size:10,q:''}};
let materialInventory=[],listRevision={},taskPreflightRevision=0;
const workspaceReads=UiReadScope.create(()=>[token,project,role]);
const errors={material_authorization_required:'使用材料生成人格前，请编辑设定并确认建模授权',personality_not_published:'只能绑定已批准的人格版本',personality_workspace_mismatch:'只能绑定同一工作空间的人格',persona_generation_busy:'已有正在进行的人格生成任务，请等待或停止',user_active_task_limit:'未结束任务已达到上限，请等待或停止部分任务；暂停任务也计入上限',user_task_submission_rate_limit:'提交过于频繁，请稍后再试',platform_task_submission_rate_limit:'系统任务提交较多，请稍后再试',authentication_required:'令牌无效或已失效',project_access_denied:'无权访问这个工作空间',role_denied:'当前角色无权执行此操作',not_published:'请先审核并发布经验',published_version_changed:'当前发布版本已变化，请刷新列表并重新打开编辑或重新生成候选',duplicate_claim:'修改后的知识与已有条目重复，请调整内容',claim_not_found:'该知识条目已不存在，请刷新列表',unverified_evidence:'存在无法核验的证据，请重新生成',file_too_large:'文件超过 1 MiB',utf8_required:'请上传 UTF-8 文本',unsupported_filename:'只支持 Markdown 和 TXT',unknown_user:'账号未配置，请联系管理员',invalid_role:'角色无效',job_not_failed:'只有失败任务可以重试',job_is_active:'未结束的任务不能删除，请先停止',job_control_conflict:'任务状态已变化，请刷新后重试',job_access_denied:'只能管理自己的历史任务，负责人可管理全部任务',cannot_change_own_role:'不能修改自己的角色',profile_has_active_jobs:'专题还有排队、执行或暂停的任务，请先完成或停止后再删除',experience_not_approved:'只能删除已审核的蒸馏任务知识，请刷新后重试',task_material_limit:'所选材料合计超过200000字符，请拆成多次蒸馏',invalid_material_selection:'每次请选择1至50份材料，不能重复',invalid_claim_count:'专题累积知识为空或超过300条，请调整专题范围后重新生成',invalid_text:'内容为空或超过长度限制'};
function node(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function message(text,error=false){$('message').textContent=text;$('message').className=error?'error':'';$('message').style.display='block';setTimeout(()=>$('message').style.display='none',6500);}
async function api(path,options={}){
 const headers={Authorization:'Bearer '+token,...options.headers};
 let body=options.body;if(body&&!(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body);}
 return PlatformRequest.request(path,{...options,body,headers},{errors,raw:options.raw});
}
function bind(id,event,fn){$(id).addEventListener(event,async e=>{e.preventDefault();try{await fn(e);}catch(error){message(error.message,true);}});}
async function connect(){window.SyntheticCosts?.reset(true);meDemo=false;workspaceReads.invalidate();const result=await workspaceReads.read('login',()=>api('/auth/login',{method:'POST',body:{}}));if(!result)return;const me=result.value,connectedToken=token;if(me.error)throw Error('访问令牌无效，请重新填写；本地演示可用 demo-owner。');PlatformSession.saveToken(token);meId=me.id;meDemo=me.demo===true;$('identity').textContent=me.id;$('login').hidden=true;$('workspace').hidden=false;$('logout').hidden=false;$('mode').textContent=me.model_mode==='mock'?'演示账号 · 模拟模型，仅验证流程':me.demo?'演示账号 · 真实模型出口关闭，费用未核验':'已登录虚拟经历台 · 真实文字出口关闭';await loadProjects();if(token!==connectedToken)return;setInterval(()=>{if(project&&page==='tasks'&&!document.hidden&&$('task-modal').hidden)loadTasks().catch(e=>message(e.message,true));},3000);}
async function loadProjects(){const result=await workspaceReads.read('projects',()=>api('/api/projects'));if(!result)return;const data=result.value;$('projects').replaceChildren();for(const p of data){const option=node('option',p.title);option.value=p.id;option.dataset.role=p.role;$('projects').append(option);}if(project&&data.some(p=>p.id===project))$('projects').value=project;await chooseProject();}

async function chooseProject(){window.SyntheticCosts?.reset(true);workspaceReads.invalidate();ExperienceUI.closeAll();if(typeof resetPublishedKnowledge==='function')resetPublishedKnowledge();selectedMaterials.clear();materialInventory=[];for(const state of Object.values(lists)){state.page=1;state.q='';state.status='';}for(const id of ['material-query','task-query','task-filter'])$(id).value='';closeModal('upload-modal');closeModal('task-modal');clearInterval(poll);poll=null;trackedJob=null;profile=null;project=$('projects').value;PlatformSession.selectWorkspace(project);const option=$('projects').selectedOptions[0];role=option?.dataset.role||'';const labels={OWNER:'负责人',EDITOR:'编辑者',VIEWER:'阅读者'};$('current-role').textContent=role?labels[role]+' · '+role:'暂无工作空间角色';$('role-description').textContent=role?'你在当前工作空间的角色是'+labels[role]+'（'+role+'）。同一个账号在不同工作空间可以有不同角色。':'选择工作空间后查看当前权限。';$('project-heading').textContent=option?.textContent||'创建第一个工作空间';$('edit-panel').hidden=!project||role==='VIEWER';$('member-panel').hidden=!project;$('audit-panel').hidden=role!=='OWNER';$('new-profile').hidden=!project||role==='VIEWER';$('task-create').hidden=!project||role==='VIEWER';$('trash-panel').hidden=role!=='OWNER';document.querySelectorAll('[data-owner]').forEach(n=>n.hidden=role!=='OWNER');document.querySelectorAll('[data-edit]').forEach(n=>n.hidden=role==='VIEWER');$('detail').hidden=true;for(const id of ['profiles','materials','members','audit','archived','task-list','archived-tasks','personality-library','material-pager','task-pager','archive-pager'])$(id).replaceChildren();renderTaskTopics([]);if(!project){showPage('settings');return;}const scope=workspaceReads.capture();showPage(page);if(role!=='VIEWER')await loadMaterials();if(!workspaceReads.current(scope))return;await loadMembers();if(!workspaceReads.current(scope))return;await loadProfiles();if(!workspaceReads.current(scope))return;await loadAudit();if(!workspaceReads.current(scope))return;await loadTasks();}
function table(headers){const t=node('table');const head=node('thead'),tr=node('tr');for(const h of headers){const th=node('th',h);th.scope='col';tr.append(th);}head.append(tr);const body=node('tbody');t.append(head,body);return {t,body};}
function cell(row,content,cls){const c=node('td',undefined,cls);if(typeof content==='string')c.textContent=content;else c.append(content);row.append(c);return c;}
function selectionCount(){clearTaskPreflight();$('to-tasks').disabled=!selectedMaterials.size;$('material-selection').textContent=$('selected-materials').textContent='已选择 '+selectedMaterials.size+' 份材料（翻页和搜索保留选择）';}
function materialCheck(m){const c=node('input');c.type='checkbox';c.value=m.id;c.checked=selectedMaterials.has(m.id);c.setAttribute('aria-label','选择材料 '+m.filename);c.addEventListener('change',()=>{if(c.checked)selectedMaterials.add(m.id);else selectedMaterials.delete(m.id);document.querySelectorAll('input[type=checkbox]').forEach(n=>{if(n.value===m.id)n.checked=c.checked;});selectionCount();});return c;}
function pager(key,data,load){const state=lists[key],host=$(key==='material'?'material-pager':key==='task'?'task-pager':'archive-pager');host.replaceChildren();const pages=Math.max(1,Math.ceil(data.total/state.size));host.append(node('span',`共 ${data.total} 条 · 第 ${state.page} / ${pages} 页`));for(const [delta,label]of [[-1,'上一页'],[1,'下一页']]){const b=node('button',label);b.disabled=delta<0?state.page<=1:state.page>=pages;b.addEventListener('click',()=>{state.page+=delta;load().catch(e=>message(e.message,true));});host.append(b);}const size=node('select');size.setAttribute('aria-label',key+'每页条数');for(const n of [5,10,20,50]){const o=node('option',n+' 条 / 页');o.value=n;size.append(o);}size.value=state.size;size.addEventListener('change',()=>{state.size=Number(size.value);state.page=1;load().catch(e=>message(e.message,true));});host.append(size);}
async function listData(key,type){const state=lists[key],params=new URLSearchParams(state),p=project,revision=(listRevision[key]||0)+1;listRevision[key]=revision;const result=await workspaceReads.read('page:'+key,()=>api(`/api/projects/${p}/${type}/page?${params}`));if(!result||listRevision[key]!==revision)return null;const data=result.value;if(!data.items.length&&data.total>0&&state.page>1){state.page=Math.max(1,Math.ceil(data.total/state.size));return listData(key,type);}return data;}

async function loadMaterials(){const data=await listData('material','materials');if(!data)return;const scope=workspaceReads.capture();const {t,body}=table(['选择','材料名称','操作人','上传时间','状态','操作']);for(const m of data.items){const row=node('tr');cell(row,materialCheck(m));const name=cell(row,m.filename);name.append(node('small',m.id.slice(0,8)));cell(row,m.creator);cell(row,new Date(m.created_at).toLocaleString());cell(row,node('span','可用','badge'));const b=node('button','查看材料');b.addEventListener('click',async()=>{try{const view=page,read=await workspaceReads.read('material-detail',()=>api(`/api/materials/${m.id}`),()=>workspaceReads.current(scope)&&page===view);if(!read)return;const item=read.value,modal=ExperienceUI.modal(item.filename);modal.body.append(node('pre',item.body),ExperienceUI.action('关闭',modal.close));}catch(e){if(workspaceReads.current(scope))message(e.message,true);}});cell(row,b,'row-actions');body.append(row);}if(!data.items.length){const r=node('tr'),c=cell(r,'暂无符合条件的材料','empty-cell');c.colSpan=6;body.append(r);}$('materials').replaceChildren(t);pager('material',data,loadMaterials);selectionCount();}
function closeModal(id){if(id==='upload-modal')window.MaterialUploader?.cancel();const modal=$(id);if(!modal)return;if(modal.open)modal.close();modal.hidden=true;document.body.classList.remove('modal-open');if(modal._returnFocus)modal._returnFocus.focus();}
function openModal(id){const modal=$(id);modal._returnFocus=document.activeElement;modal.hidden=false;modal.oncancel=e=>{e.preventDefault();closeModal(id);};modal.showModal();document.body.classList.add('modal-open');modal.querySelector('input,select,button').focus();}
function renderTaskTopics(topics,selected=$('task-profile').value){
 const options=topics.map(p=>{const o=node('option',p.title);o.value=p.id;return o;});
 const create=node('option','＋ 创建新专题');create.value='__new__';options.push(create);
 $('task-profile').replaceChildren(...options);
 if(topics.some(p=>p.id===selected)||selected==='__new__')$('task-profile').value=selected;
 taskTopicMode();
}
function taskTopicMode(){clearTaskPreflight();const creating=$('task-profile').value==='__new__';$('task-new-topic-field').hidden=!creating;$('task-new-topic-title').required=creating;}
function clearTaskPreflight(){taskPreflightRevision++;$('task-preflight-result').replaceChildren();}
async function checkTaskPreflight(){
 const p=project,topic=$('task-profile').value,ids=[...selectedMaterials],revision=++taskPreflightRevision,button=$('task-preflight');
 button.disabled=true;$('task-error').textContent='';$('task-preflight-result').textContent='正在核对材料与任务规模…';
 try{
  if(!ids.length)throw Error('请先选择生成材料');
  const result=await workspaceReads.read('task-preflight',()=>api(`/api/projects/${p}/distill/preflight`,{method:'POST',body:{profile_id:topic==='__new__'?null:topic,material_ids:ids}}),()=>taskPreflightRevision===revision&&$('task-modal').open);if(!result)return;const plan=result.value;
  if(project!==p||taskPreflightRevision!==revision||!$('task-modal').open)return;
  $('task-preflight-result').replaceChildren(node('p',`${plan.material_count} 份材料 · ${plan.character_count} 字符 · ${plan.chunk_count} 个分块 · 继承 ${plan.base_claim_count} 条知识`));
  if(plan.estimated_claim_ceiling>plan.claim_limit)$('task-preflight-result').append(node('p',`按每块最多20条估算，累积上限可能达到 ${plan.estimated_claim_ceiling} 条，超过专题 ${plan.claim_limit} 条限制。建议缩小专题范围或减少本批材料；实际结果以生成和去重后为准。`,'form-error'));
  $('task-preflight-result').append(node('small','这是当前选材的规模检查，不代表模型效果或费用；提交时会重新校验并冻结任务。'));
 }catch(e){if(project===p&&taskPreflightRevision===revision){$('task-preflight-result').replaceChildren();$('task-error').textContent=e.message;}}
 finally{if(taskPreflightRevision===revision)button.disabled=false;}
}
async function openTaskModal(){
 const p=project,selected=$('task-profile').value,view=page;const result=await workspaceReads.read('task-open',()=>Promise.all([api(`/api/projects/${p}/materials`),api(`/api/projects/${p}/profiles`)]),()=>page===view);if(!result)return;const [materials,topics]=result.value;materialInventory=materials;
 const available=new Set(materials.map(m=>m.id));for(const id of selectedMaterials)if(!available.has(id))selectedMaterials.delete(id);
 renderTaskTopics(topics,selected);
 ExperienceUI.list($('task-picker'),'蒸馏材料选择',materialInventory,['选择','材料名称','操作人','上传时间'],(row,m)=>{cell(row,materialCheck(m));cell(row,m.filename);cell(row,m.creator);cell(row,new Date(m.created_at).toLocaleString());},{placeholder:'搜索材料名称、操作人或编号',text:m=>[m.filename,m.creator,m.id].join(' '),empty:'暂无可选材料，请先上传材料。'});
 selectionCount();$('task-error').textContent='';openModal('task-modal');
}
async function loadMembers(){const p=project,result=await workspaceReads.read('members',()=>role==='OWNER'?api(`/api/projects/${p}/members`):Promise.resolve([{user_id:meId,role}]));if(!result)return;const data=result.value,scope=workspaceReads.capture();const labels={OWNER:'负责人',EDITOR:'编辑者',VIEWER:'阅读者'};ExperienceUI.list($('members'),'成员',data,['账号','角色','操作'],(row,m)=>{cell(row,m.user_id+(m.user_id===meId?'（当前账号）':''));cell(row,labels[m.role]+' · '+m.role);const b=ExperienceUI.action('修改角色',()=>{if(!workspaceReads.current(scope))return;const dialog=ExperienceUI.modal('修改成员角色'),form=node('form'),select=node('select');select.setAttribute('aria-label','成员角色');for(const [value,label]of Object.entries(labels)){const o=node('option',label);o.value=value;select.append(o);}select.value=m.role;const save=node('button','保存角色','primary');form.append(node('p',m.user_id),select,save);dialog.body.append(form);form.onsubmit=async e=>{e.preventDefault();if(!dialog.active()||!workspaceReads.current(scope)||save.disabled)return;save.disabled=true;try{await api(`/api/projects/${p}/members`,{method:'PUT',body:{user_id:m.user_id,role:select.value}});if(!dialog.active()||!workspaceReads.current(scope))return;dialog.close();await loadMembers();message('成员角色已更新');}catch(e){if(dialog.active()&&workspaceReads.current(scope))message(e.message,true);save.disabled=false;}};});b.disabled=role!=='OWNER'||m.user_id===meId;cell(row,b);},{placeholder:'搜索成员账号',text:m=>m.user_id,filters:[['','全部角色'],...Object.entries(labels)],filter:(m,f)=>m.role===f});}
async function loadProfiles(){const p=project,result=await workspaceReads.read('profiles',()=>api(`/api/projects/${p}/profiles`));if(!result)return;const data=result.value,scope=workspaceReads.capture();$('empty').hidden=true;renderTaskTopics(data);
 ExperienceUI.list($('profiles'),'知识专题',data,['专题名称','状态','当前发布版本','操作'],(row,p)=>{cell(row,p.title);cell(row,p.published_id?'已发布':'待发布');cell(row,p.published_id?p.published_id.slice(0,8):'尚未发布');const actions=node('div',undefined,'actions');actions.append(ExperienceUI.action('查看详情',()=>workspaceReads.current(scope)&&chooseProfile(p)));if(p.published_id)actions.append(ExperienceUI.action('查看知识',()=>workspaceReads.current(scope)&&openPublishedKnowledgeForTopic(p.id)));if(role==='OWNER')actions.append(ExperienceUI.action('删除',async()=>{if(!workspaceReads.current(scope)||!await confirmDelete(p.title)||!workspaceReads.current(scope))return;await api(`/api/profiles/${p.id}`,{method:'DELETE'});if(!workspaceReads.current(scope))return;if(profile===p.id){profile=null;ExperienceUI.closeAll();}await loadProfiles();await loadAudit();message('专题已删除，可恢复');},'danger'));cell(row,actions);},{placeholder:'搜索专题名称或编号',text:p=>p.title+' '+p.id,filters:[['','全部状态'],['published','已发布'],['draft','待发布']],filter:(p,f)=>f==='published'?!!p.published_id:!p.published_id});if(role==='OWNER')await loadArchived();}
async function chooseProfile(p){clearInterval(poll);poll=null;profile=p.id;showPage('experiences');ExperienceUI.existing('detail','知识专题详情');$('profile-heading').textContent=p.title;$('detail').hidden=false;$('distill-panel').hidden=role==='VIEWER';$('question-panel').hidden=!p.published_id;$('download').disabled=!p.published_id;$('job').textContent='';$('answer').textContent='';questionState('',false);await loadProfiles();await loadVersions();}
function candidateDelta(version,versions){
 const base=versions.find(v=>v.id===version.base_id),known=!version.base_id||!!base;
 const ids=new Set((base?.content.claims||[]).map(c=>c.id));
 return {known,added:version.content.claims.filter(c=>!ids.has(c.id)),inherited:version.content.claims.filter(c=>ids.has(c.id))};
}
function versionCard(v,versions,current,afterReview,opts={}){
 const block=node('article',undefined,'version'),candidate=v.status==='CANDIDATE',conflict=candidate&&(v.base_id||null)!==(current||null);
 block.append(node('span',({CANDIDATE:'待审核',APPROVED:'已审核',REJECTED:'已拒绝'})[v.status],'badge'),node('small',(v.id===current?'当前发布 · ':'')+v.id.slice(0,8)+' · '+new Date(v.created_at).toLocaleString()));
 const renderClaim=c=>{
  const claim=node('div',undefined,'claim');claim.append(node('strong',c.section),node('p',c.text));
  for(const ev of c.evidence||[]){
   claim.append(node('div',`依据：${ev.quote} · ${ev.material_id.slice(0,8)}`,'source'));
   if(role!=='VIEWER'){
    const b=node('button','查看来源');b.type='button';b.addEventListener('click',async()=>{
     b.disabled=true;try{const m=await api(`/api/materials/${ev.material_id}`);if(!claim.isConnected)return;const d=node('details');d.open=true;d.append(node('summary',m.filename),node('pre',m.body));claim.append(d);b.remove();}catch(e){message(e.message,true);}finally{b.disabled=false;}
    });claim.append(b);
   }
  }
  return claim;
 };
 if(candidate){
  const delta=candidateDelta(v,versions);
  block.append(node('p','批准后发布整个累积快照；下方先展示本次新增，继承知识保持原样。','muted'));
  if(delta.known){
   block.append(node('h3',`本次新增知识（${delta.added.length} 条）`));
   if(!delta.added.length)block.append(node('p','本次未新增知识，请核对是否重复选材；可拒绝候选后补充材料。','muted'));
   for(const claim of delta.added)block.append(renderClaim(claim));
   if(delta.inherited.length){const inherited=node('details');inherited.append(node('summary',`继承的已审核知识（${delta.inherited.length} 条，展开核对）`));for(const claim of delta.inherited)inherited.append(renderClaim(claim));block.append(inherited);}
  }else{
   block.append(node('p','基础版本当前不可查看，无法可靠区分增量；下面展示完整快照，请逐条核对。','form-error'));
   for(const claim of v.content.claims)block.append(renderClaim(claim));
  }
  if(conflict)block.append(node('p','当前发布版本已变化，此候选不能直接发布。请拒绝后基于最新版本重新生成。','form-error'));
 }else{block.append(node('p','此版本为专题的累积知识快照。','muted'));for(const claim of v.content.claims)block.append(renderClaim(claim));}
 if(candidate&&role==='OWNER'){
  const note=node('textarea');note.placeholder='审核说明（可选）';note.maxLength=1000;note.setAttribute('aria-label','审核说明');
  const actions=node('div',undefined,'actions');let submitting=false;
  for(const [approve,label]of [[true,'批准并发布'],[false,'拒绝候选']]){
   const b=node('button',label,approve?'primary':'');b.type='button';b.disabled=approve&&conflict;b.dataset.approve=String(approve);
   b.addEventListener('click',async()=>{
    if(submitting)return;submitting=true;actions.querySelectorAll('button').forEach(n=>n.disabled=true);
    try{await api(`/api/versions/${v.id}/review`,{method:'POST',body:{approve,note:note.value}});message(approve?'已发布':'已拒绝');await afterReview();}
    catch(e){message(e.message,true);}
    finally{submitting=false;actions.querySelectorAll('button').forEach(n=>n.disabled=conflict&&n.dataset.approve==='true');}
   });actions.append(b);
  }
  block.append(note,actions);
 }
 if(!opts.hideRollback&&v.status==='APPROVED'&&role==='OWNER'&&v.id!==current){const b=node('button','设为当前发布版本');b.type='button';b.addEventListener('click',async()=>{try{await api(`/api/profiles/${v.profile_id}/rollback`,{method:'POST',body:{version_id:v.id}});message('当前发布版本已切换');await afterReview();}catch(e){message(e.message,true);}});block.append(b);}
 return block;
}
function topicDetailVisible(){const detail=$('detail');return !detail.hidden&&(detail.closest?.('dialog')?.open??true);}
async function loadVersions(){
 const selected=profile,p=project;if(!selected||!topicDetailVisible())return;
 const result=await workspaceReads.read('versions',async scope=>{const versions=await api(`/api/profiles/${selected}/versions`);if(!workspaceReads.current(scope))return null;const topics=await api(`/api/projects/${p}/profiles`);return {versions,current:topics.find(x=>x.id===selected)?.published_id};},()=>profile===selected&&topicDetailVisible());
 if(!result)return;const {versions,current}=result.value;
 $('versions').replaceChildren();const approved=versions.filter(v=>v.status==='APPROVED');if(!approved.length)$('versions').append(node('p','暂无已审核知识，请在下方蒸馏任务中查看并审核候选。','muted'));for(const v of approved)$('versions').append(versionCard(v,versions,current,refreshCurrent));await loadTopicTasks();
}

const auditLabels={KNOWLEDGE_MANUALLY_REVISED:'人工修订知识',DISTILL_EXPERIENCE_DELETED:'删除蒸馏知识',PROJECT_CREATED:'创建工作空间',MEMBER_CHANGED:'更新成员',MATERIAL_UPLOADED:'上传材料',PROFILE_CREATED:'创建专题',VERSION_PUBLISHED:'发布知识',VERSION_REJECTED:'拒绝候选',VERSION_ROLLED_BACK:'回滚版本',PROFILE_DELETED:'删除专题',PROFILE_RESTORED:'恢复专题',PERSONA_CREATED:'创建人物或人格',PERSONA_EDITED:'编辑设定',PERSONA_PUBLISHED:'发布人格',PERSONA_REJECTED:'拒绝人格',PERSONALITY_BOUND:'绑定人格',PERSONA_KNOWLEDGE_CHANGED:'更新知识配置',PERSONA_CORRECTED:'修订人格',JOB_CREATED:'创建任务',JOB_COMPLETED:'完成任务',JOB_RETRIED:'重试任务'};
async function loadAudit(){if(role!=='OWNER'||!project)return;const p=project,result=await workspaceReads.read('audit',()=>api(`/api/projects/${p}/audit`));if(!result)return;const data=result.value;ExperienceUI.list($('audit'),'操作记录',data,['时间','操作人','操作','对象编号'],(row,a)=>{cell(row,new Date(a.created_at).toLocaleString());cell(row,a.actor);cell(row,auditLabels[a.action]||a.action);cell(row,a.resource||'—');},{placeholder:'搜索操作人、操作或对象编号',text:a=>[a.actor,a.action,auditLabels[a.action],a.resource].join(' ')});}
async function loadWorkspaceList(){const result=await workspaceReads.read('workspace-list',()=>api('/api/projects'));if(!result)return;const data=result.value,scope=workspaceReads.capture();ExperienceUI.list($('workspace-list'),'工作空间',data,['工作空间','当前角色','操作'],(row,p)=>{cell(row,p.title);cell(row,{OWNER:'负责人',EDITOR:'编辑者',VIEWER:'阅读者'}[p.role]);cell(row,ExperienceUI.action(p.id===project?'当前空间':'切换到此空间',async()=>{if(!workspaceReads.current(scope))return;$('projects').value=p.id;await chooseProject();}));},{placeholder:'搜索工作空间名称或编号',text:p=>p.title+' '+p.id});}
async function refreshCurrent(){const scope=workspaceReads.capture(),selected=profile,p=project;const live=()=>workspaceReads.current(scope)&&profile===selected&&topicDetailVisible();await loadProfiles();if(!live())return;await loadVersions();if(!live())return;await loadAudit();if(!live())return;const result=await workspaceReads.read('refresh-current',()=>api(`/api/projects/${p}/profiles`),live);if(!result)return;const topic=result.value.find(x=>x.id===selected);$('question-panel').hidden=!topic?.published_id;$('download').disabled=!topic?.published_id;}

function questionState(text,busy){$('question-status').textContent=text;$('question-submit').disabled=busy;$('question-submit').textContent=busy?'正在回答…':'提问';$('question-panel').setAttribute('aria-busy',String(busy));}
let trackedJob=null;
async function track(job,isQuestion=false){
  clearInterval(poll);trackedJob=job.id;
  const selected=profile,scope=workspaceReads.capture();let checking=false;
  $('job').textContent='任务已提交，等待处理…';
  if(isQuestion)questionState('问题已提交，等待模型回答…',true);
  const check=async()=>{
    if(checking||!workspaceReads.current(scope)||trackedJob!==job.id||profile!==selected)return;
    checking=true;
    try{
      const current=await api(`/api/jobs/${job.id}`);
      if(!workspaceReads.current(scope)||trackedJob!==job.id||profile!==selected)return;
      const status=({QUEUED:'等待处理',RUNNING:'正在处理',SUCCEEDED:'处理完成',FAILED:'处理失败',PAUSED:'已暂停，请到任务列表继续',CANCELLED:'已停止'})[current.status];
      $('job').textContent=status+` · 第 ${current.attempt} 次执行 · ${current.progress?.completed||0}/${current.progress?.total||1} 分块`;
      if(isQuestion)questionState(current.status==='RUNNING'?'DeepSeek 正在回答，请稍候…':status,current.status==='QUEUED'||current.status==='RUNNING');
      if(['SUCCEEDED','FAILED','PAUSED','CANCELLED'].includes(current.status)){
        clearInterval(poll);poll=null;trackedJob=null;
        if(current.result?.answer){$('answer').textContent=current.result.answer+`\n\n使用版本：${current.result.version_id}\n引用：${current.result.citations.join('、')||'无支持引用'}`;if(isQuestion)questionState('回答已完成',false);}
        if(current.status==='FAILED'){
          const failure='处理失败：'+taskFailureReason(current.error_code)+'。请检查模型连接后重试。';
          if(isQuestion)questionState(failure,false);
          $('job').append(node('p',failure));
          if(role!=='VIEWER'){const b=node('button','重试');b.addEventListener('click',async()=>{try{await api(`/api/jobs/${job.id}/retry`,{method:'POST'});await track(job,isQuestion);}catch(e){message(e.message,true);}});$('job').append(b);}
        }
        // Answer delivery must not depend on auxiliary task/audit refreshes.
        refreshCurrent().catch(e=>message('回答状态已更新，页面刷新失败：'+e.message,true));
      }
      loadTasks().catch(()=>{});
    }catch(error){
      if(!workspaceReads.current(scope)||trackedJob!==job.id||profile!==selected)return;
      if(isQuestion)questionState('暂时无法获取回答状态，正在重试：'+error.message,true);
      else $('job').textContent='暂时无法获取任务状态，正在重试：'+error.message;
    }finally{checking=false;}
  };
  poll=setInterval(check,2000);
  await check();
}
bind('login-form','submit',async()=>{const b=$('login-form').querySelector('button');if(b.disabled)return;b.disabled=true;try{token=$('token').value.trim();await connect();}finally{b.disabled=false;}});
bind('logout','click',async()=>{window.MaterialUploader?.clear();window.SyntheticCosts?.reset(true);meDemo=false;workspaceReads.invalidate();token='';project=null;profile=null;role='';meId='';trackedJob=null;clearInterval(poll);ExperienceUI.closeAll();if(typeof resetPublishedKnowledge==='function')resetPublishedKnowledge();PlatformSession.clear();location.reload();});
window.addEventListener('pagehide',()=>{window.SyntheticCosts?.reset(true);workspaceReads.invalidate();});
window.addEventListener('pageshow',event=>{if(event.persisted)window.SyntheticCosts?.reset();});
bind('projects','change',chooseProject);
bind('project-form','submit',async()=>{const p=await api('/api/projects',{method:'POST',body:{title:$('project-title').value}});project=p.id;$('project-title').value='';ExperienceUI.closeAll();await loadProjects();});
bind('member-form','submit',async()=>{await api(`/api/projects/${project}/members`,{method:'PUT',body:{user_id:$('member-user').value,role:$('member-role').value}});ExperienceUI.closeAll();await loadMembers();message('成员权限已更新');});
window.MaterialUploader=window.MaterialUploadFlow?.create({context:()=>({project,role,userId:meId}),capture:()=>workspaceReads.capture(),current:scope=>workspaceReads.current(scope),api,openModal:()=>openModal('upload-modal'),closeModal:()=>closeModal('upload-modal'),refresh:async uploaded=>{selectedMaterials.add(uploaded.id);lists.material.page=1;lists.material.q='';$('material-query').value='';await loadMaterials();},continueKnowledge:openTaskModal,notify:message,elements:{modal:$('upload-modal'),file:$('file'),error:$('upload-error'),only:$('upload-only'),more:$('upload-continue'),recheck:$('upload-recheck'),retry:$('upload-retry')},formData:()=>new FormData(),storage:{getItem:key=>sessionStorage.getItem(key),setItem:(key,value)=>sessionStorage.setItem(key,value),removeItem:key=>sessionStorage.removeItem(key)},crypto:window.crypto});
bind('upload-form','submit',e=>window.MaterialUploader?.submit(e));
bind('profile-form','submit',async()=>{const p=await api(`/api/projects/${project}/profiles`,{method:'POST',body:{title:$('profile-title').value}});$('profile-title').value='';ExperienceUI.closeAll();const topics=await api(`/api/projects/${project}/profiles`);await chooseProfile(topics.find(t=>t.id===p.id)||{...p,title:'新知识专题'});});
bind('distill','click',async()=>{$('task-profile').value=profile;ExperienceUI.closeAll();await openTaskModal();});
bind('question-form','submit',async()=>{
  if($('question-submit').disabled)return;
  const question=$('question').value.trim();
  if(!question){questionState('请先输入要提问的问题。',false);$('question').focus();return;}
  if(question.length>4000){questionState('问题不能超过4000个字符。',false);return;}
  questionState('正在提交问题…',true);$('answer').textContent='';
  try{const job=await api(`/api/profiles/${profile}/questions`,{method:'POST',body:{question}});await track(job,true);}
  catch(error){questionState('提问失败：'+error.message,false);throw error;}
});
bind('download','click',async()=>{const r=await api(`/api/profiles/${profile}/download`,{raw:true});const url=URL.createObjectURL(await r.blob());const a=node('a');a.href=url;a.download='SKILL.md';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
bind('refresh','click',async()=>{if(profile)await refreshCurrent();else if(project)await loadProfiles();});
if(token)connect().catch(error=>{message(error.message,true);PlatformSession.clear();});

function showPage(name){window.SyntheticCosts?.show(false);ExperienceUI.closeAll();if(role==='VIEWER'&&name==='materials')name='experiences';if(role!=='OWNER'&&name==='audit')name='experiences';page=name;history.replaceState(null,'','#'+name);document.querySelectorAll('[data-view]').forEach(n=>n.hidden=n.dataset.view!==name);document.querySelectorAll('[data-page]').forEach(n=>{n.classList.toggle('active',n.dataset.page===name);n.setAttribute('aria-current',n.dataset.page===name?'page':'false');});selectionCount();if(name==='published-knowledge'&&project)loadPublishedKnowledge().catch(e=>message(e.message,true));if(name==='personalities'&&project&&window.loadPersonalityLibrary)window.loadPersonalityLibrary().catch(e=>message(e.message,true));if(name==='audit'&&project)loadAudit().catch(e=>message(e.message,true));if(name==='roles'&&project)loadMembers().catch(e=>message(e.message,true));if(name==='materials'&&project&&role!=='VIEWER')loadMaterials().catch(e=>message(e.message,true));if(name==='settings'){loadWorkspaceList().catch(e=>message(e.message,true));window.SyntheticCosts?.show(true);}if(name==='tasks'&&project)loadTasks().catch(e=>message(e.message,true));}
document.querySelectorAll('[data-page]').forEach(n=>n.addEventListener('click',()=>showPage(n.dataset.page)));
bind('task-preflight','click',checkTaskPreflight);bind('to-tasks','click',openTaskModal);bind('task-profile','change',taskTopicMode);bind('task-filter','change',()=>{lists.task.page=1;lists.task.status=$('task-filter').value;return loadTasks();});
bind('task-form','submit',async()=>{
 const button=$('task-form').querySelector('button.primary'),p=project;button.disabled=true;$('task-error').textContent='';
 try{
  const ids=[...selectedMaterials];if(!ids.length)throw Error('请勾选本次生成使用的材料');if(ids.length>50)throw Error('每次最多选择50份材料');
  let id=$('task-profile').value;
  if(id==='__new__'){
   const title=$('task-new-topic-title').value.trim();if(!title)throw Error('请输入新专题名称');
   const topic=await api(`/api/projects/${p}/profiles`,{method:'POST',body:{title}});
   if(project!==p)return;
   // Retain the created topic if task submission fails, so retry cannot create it twice.
   renderTaskTopics(await api(`/api/projects/${p}/profiles`),topic.id);id=topic.id;$('task-new-topic-title').value='';
  }
  if(!id)throw Error('请选择知识专题');
  const all=await api(`/api/projects/${p}/profiles`);if(project!==p)return;if(!all.some(t=>t.id===id))throw Error('专题已删除，请重新选择');
  await api(`/api/profiles/${id}/distill`,{method:'POST',body:{material_ids:ids}});if(project!==p)return;
  closeModal('task-modal');selectedMaterials.clear();selectionCount();lists.task.page=1;lists.task.status='';$('task-filter').value='';
  showPage('tasks');await loadTasks();await loadProfiles();message('候选生成任务已提交，完成后请审核发布');
 }catch(e){if(project===p)$('task-error').textContent=e.message;}finally{button.disabled=false;}
});
bind('new-material','click',()=>window.MaterialUploader?.open());bind('task-create','click',openTaskModal);
document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>closeModal(b.dataset.close)));

for(const [key,prefix,load]of [['material','material',loadMaterials],['task','task',loadTasks]]){bind(prefix+'-search','submit',()=>{lists[key].q=$(prefix+'-query').value.trim();lists[key].page=1;return load();});bind(prefix+'-reset','click',()=>{$(prefix+'-query').value='';lists[key].q='';lists[key].page=1;if(key==='task'){lists.task.status='';$('task-filter').value='';}return load();});}
async function loadArchived(){const p=project,result=await workspaceReads.read('archived-profiles',()=>api(`/api/projects/${p}/archived-profiles`));if(!result)return;const rows=result.value,scope=workspaceReads.capture();$('archived').replaceChildren();if(!rows.length)$('archived').append(node('p','暂无已删除专题','muted'));for(const p of rows){const row=node('div',undefined,'archive-row');row.append(node('span',p.title));const b=node('button','恢复');b.addEventListener('click',async()=>{try{if(!workspaceReads.current(scope))return;await api(`/api/profiles/${p.id}/restore`,{method:'POST'});if(!workspaceReads.current(scope))return;await loadProfiles();await loadAudit();message('专题已恢复');}catch(e){if(workspaceReads.current(scope))message(e.message,true);}});row.append(b);$('archived').append(row);}}
function taskActions(j){const actions=node('div',undefined,'actions');if(role==='OWNER'&&j.kind==='DISTILL'&&j.version_status==='APPROVED')actions.append(ExperienceUI.action('删除对应知识',()=>deleteExperienceKnowledge(j),'danger'));if(role==='OWNER'||j.creator===meId){for(const [action,label]of j.status==='PAUSED'?[['resume','继续任务'],['stop','停止任务']]:['QUEUED','RUNNING'].includes(j.status)?[['pause','暂停任务'],['stop','停止任务']]:[]){const b=node('button',label,action==='stop'?'danger':'');b.addEventListener('click',async()=>{if(action==='stop'&&!await confirmDelete(j.profile_title+' · '+j.id.slice(0,8),'停止任务'))return;try{await api(`/api/jobs/${j.id}/${action}`,{method:'POST'});await loadTasks();message(label+'成功');}catch(e){message(e.message,true);}});actions.append(b);}}if(j.status==='FAILED' &&role!=='VIEWER'){const b=node('button','重试任务');b.addEventListener('click',async()=>{try{await api(`/api/jobs/${j.id}/retry`,{method:'POST'});await loadTasks();}catch(e){message(e.message,true);}});actions.append(b);}const open=ExperienceUI.action(j.kind==='DISTILL'?(role==='OWNER'&&j.version_status==='CANDIDATE'?'审核发布':'查看任务详情'):'查看回答',()=>openExperienceTask(j));actions.append(open);if(['SUCCEEDED','FAILED','CANCELLED'].includes(j.status)&&(role==='OWNER'||j.creator===meId)){const del=node('button','删除历史任务','danger');del.addEventListener('click',async()=>{if(!await confirmDelete(j.profile_title+' · '+j.id.slice(0,8),'历史任务'))return;try{await api(`/api/jobs/${j.id}`,{method:'DELETE'});await loadTasks();message('历史任务已删除，可从已删除列表恢复');}catch(e){message(e.message,true);}});actions.append(del);}return actions;}
function taskFailureReason(code){return ({MODEL_EXECUTION_BUDGET_UNVERIFIED:'合成执行预算不可用或占款尚待核对。请先核对记录，不要连续重试；真实调用仍关闭。',MODEL_BUDGET_NOT_VERIFIED:'真实文字调用已关闭：尚不能核验累计费用。模拟流程仍可使用；不要连续重试。',MODEL_OUTPUT_LIMIT:'输出达到模型上限，结果未保存。请缩小输入或调整额度后重新生成；已有分块保留。',MODEL_CONFIGURATION_ERROR:'模型拒绝了请求，请检查模型、凭证和请求配置后重试；已保存分块保留。',EXECUTION_CHANGED:'模型或提示词配置已变化。恢复原配置后可重试，使用新配置请重新生成任务。',MODEL_UNAVAILABLE:'模型暂时不可用，已保存分块会保留。',INVALID_MODEL_OUTPUT:'模型输出未通过校验，已保存分块会保留。',LEASE_EXHAUSTED:'任务执行多次中断，已保存分块会保留。'})[code]||code||'未知错误';}
const statusLabels={QUEUED:'排队中',RUNNING:'执行中',SUCCEEDED:'已完成',FAILED:'失败',PAUSED:'已暂停',CANCELLED:'已停止'};
async function loadTasks(){if(!project)return;const data=await listData('task','jobs');if(!data)return;renderTasks('task-list',data.items);pager('task',data,loadTasks);await loadArchivedTasks();}
function renderTasks(id,rows,archived=false){const {t,body}=table(['任务 / 专题','类型','操作人','创建时间','状态 / 进度','操作']);for(const j of rows){const row=node('tr');const title=cell(row,j.profile_title);title.append(node('small',j.id.slice(0,8)));cell(row,j.kind==='DISTILL'?'候选生成':'问答');cell(row,j.creator);cell(row,new Date(j.created_at).toLocaleString());const state=cell(row,node('span',statusLabels[j.status]||j.status,'badge status-'+j.status));const completed=j.progress?.completed||0,total=j.progress?.total||1;const progress=node('progress');progress.max=100;progress.value=j.status==='SUCCEEDED'?100:Math.min(95,Math.round(completed/total*90));progress.setAttribute('aria-label',j.profile_title+'任务进度');state.append(progress,node('small',`${completed}/${total} 分块 · 尝试 ${j.attempt}`));if(j.kind==='DISTILL')state.append(node('small','经验状态：'+({CANDIDATE:'待审核',APPROVED:'已审核',REJECTED:'已拒绝',DELETED:'已删除'}[j.version_status]||'未生成')));if(j.kind==='DISTILL')state.append(node('small',`已保存 ${j.checkpoint_count||0} 个分块，可用于续跑`));if(j.error_code)state.append(node('small',taskFailureReason(j.error_code),'form-error'));if(archived){const b=node('button','恢复任务');b.addEventListener('click',async()=>{try{await api(`/api/jobs/${j.id}/restore`,{method:'POST'});await loadTasks();message('历史任务已恢复');}catch(e){message(e.message,true);}});cell(row,b,'row-actions');}else cell(row,taskActions(j),'row-actions');body.append(row);}if(!rows.length){const row=node('tr'),c=cell(row,'暂无符合条件的任务','empty-cell');c.colSpan=6;body.append(row);}$(id).replaceChildren(t);}

function confirmDelete(title,kind='知识专题'){
 return new Promise(resolve=>{
  const stopping=kind==='停止任务',modal=ExperienceUI.modal(stopping?kind:'删除'+kind);
  modal.body.append(node('p',stopping?`确定停止“${title}”？停止后不能继续；本次结果不会写入。`:kind==='历史任务'?`确定删除“${title}”？任务记录可恢复，已生成的候选和发布版本会保留。`:`确定删除“${title}”？删除后停止共享，历史版本保留，可在已删除专题中恢复。`));
  let approved=false;const actions=node('div',undefined,'actions');
  actions.append(ExperienceUI.action('取消',modal.close),ExperienceUI.action(stopping?'确认停止':'确认删除',()=>{approved=true;modal.close();},'danger'));
  modal.body.append(actions);modal.dialog.addEventListener('close',()=>resolve(approved),{once:true});
 });
}

async function loadArchivedTasks(){const data=await listData('archive','archived-jobs');if(!data)return;renderTasks('archived-tasks',data.items,true);pager('archive',data,loadArchivedTasks);}

if(['experiences','personalities','materials','tasks','roles','settings','audit'].includes(location.hash.slice(1)))page=location.hash.slice(1);

bind('new-profile','click',()=>ExperienceUI.existing('create-profile','新增知识专题'));
bind('new-workspace','click',()=>ExperienceUI.existing('project-form','新建工作空间'));
bind('new-member','click',()=>ExperienceUI.existing('member-form','添加成员'));
