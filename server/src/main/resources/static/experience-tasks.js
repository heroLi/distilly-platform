'use strict';
const experienceVersionLabels={CANDIDATE:'待审核',APPROVED:'已审核',REJECTED:'已拒绝',DELETED:'已删除'};
let topicTaskState={page:1,size:10,q:'',status:''},topicTaskSelected=null,topicTaskRevision=0;
async function loadTopicTasks(){
 $('topic-tasks').hidden=role==='VIEWER';if(role==='VIEWER'||!profile)return;
 const scope=workspaceReads.capture(),f=profile,p=project,revision=++topicTaskRevision;
 if(topicTaskSelected!==f){topicTaskSelected=f;topicTaskState={page:1,size:10,q:'',status:''};}
 const read=await workspaceReads.read('topic-tasks',()=>api(`/api/profiles/${f}/jobs/page?${new URLSearchParams(topicTaskState)}`),()=>profile===f&&topicTaskRevision===revision&&topicDetailVisible());if(!read)return;const data=read.value;
 if(!workspaceReads.current(scope)||profile!==f||project!==p||revision!==topicTaskRevision||!topicDetailVisible())return;
 if(!data.items.length&&data.total&&topicTaskState.page>1){topicTaskState.page=Math.ceil(data.total/topicTaskState.size);return loadTopicTasks();}
 const host=$('topic-task-list'),form=node('form',undefined,'search-bar'),q=node('input'),filter=node('select');
 q.placeholder='搜索操作人或任务编号';q.setAttribute('aria-label','搜索专题蒸馏任务');q.value=topicTaskState.q;
 filter.setAttribute('aria-label','专题任务状态');for(const [value,label]of [['','全部状态'],...Object.entries(statusLabels)]){const o=node('option',label);o.value=value;filter.append(o);}filter.value=topicTaskState.status;
 form.append(q,filter,node('button','搜索'),ExperienceUI.action('重置',()=>{topicTaskState.q='';topicTaskState.status='';topicTaskState.page=1;return loadTopicTasks();}));
 form.onsubmit=e=>{e.preventDefault();topicTaskState.q=q.value.trim();topicTaskState.status=filter.value;topicTaskState.page=1;loadTopicTasks().catch(e=>message(e.message,true));};filter.onchange=()=>form.requestSubmit();
 const {t,body}=table(['任务编号','操作人','创建时间','任务状态','经验状态','操作']);
 for(const j of data.items){const tr=node('tr');cell(tr,j.id.slice(0,8));cell(tr,j.creator);cell(tr,new Date(j.created_at).toLocaleString());cell(tr,statusLabels[j.status]||j.status);cell(tr,experienceVersionLabels[j.version_status]||'未生成');const actions=node('div',undefined,'actions');actions.append(ExperienceUI.action(role==='OWNER'&&j.version_status==='CANDIDATE'?'审核发布':'查看详情',()=>openExperienceTask(j)));if(role==='OWNER'&&j.version_status==='APPROVED')actions.append(ExperienceUI.action('删除对应知识',()=>deleteExperienceKnowledge(j),'danger'));cell(tr,actions,'row-actions');body.append(tr);}
 if(!data.items.length){const tr=node('tr');cell(tr,'暂无符合条件的蒸馏任务','empty-cell').colSpan=6;body.append(tr);}
 const pages=Math.max(1,Math.ceil(data.total/topicTaskState.size)),pager=node('div',undefined,'pager');pager.append(node('span',`共 ${data.total} 条 · 第 ${topicTaskState.page}/${pages} 页`));
 for(const [delta,label]of [[-1,'上一页'],[1,'下一页']]){const b=ExperienceUI.action(label,()=>{topicTaskState.page+=delta;return loadTopicTasks();});b.disabled=delta<0?topicTaskState.page===1:topicTaskState.page>=pages;pager.append(b);}
 host.replaceChildren(form,t,pager);
}
async function openExperienceTask(j){
 const scope=workspaceReads.capture(),dialog=ExperienceUI.modal(j.kind==='DISTILL'?'蒸馏任务详情':'问答任务详情'),p=project;
 dialog.body.append(node('p','正在加载任务详情…','muted'));
 const refresh=async()=>{
  const task=await api(`/api/jobs/${j.id}`);if(!workspaceReads.current(scope)||!dialog.active()||project!==p)return;
  dialog.body.replaceChildren(node('p',`${j.profile_title||'知识专题'} · ${statusLabels[task.status]||task.status} · ${task.progress?.completed||0}/${task.progress?.total||1} 分块`,'muted'),node('small','任务编号：'+task.id));
  if(task.kind==='DISTILL')dialog.body.append(node('p',`已保存 ${task.checkpoint_count||0} 个分块；暂停、失败重试或服务重启后，继续生成尚未保存的分块。`,'muted'));
  dialog.body.append(ExperienceUI.action('刷新任务详情',refresh));
  if(task.kind==='ANSWER'){dialog.body.append(node('div',task.result?.answer||(task.status==='FAILED'?'回答失败：'+taskFailureReason(task.error_code):'回答尚未生成。'),'answer'));if(task.result)dialog.body.append(node('p',`使用版本：${task.result.version_id} · 引用：${task.result.citations?.join('、')||'无'}`,'muted'));return;}
  if(!task.result?.version_id){dialog.body.append(node('p',task.status==='FAILED'?'生成失败：'+taskFailureReason(task.error_code):'候选尚未生成，请完成任务后查看。','muted'));return;}
  const versions=await api(`/api/profiles/${task.profile_id}/versions`),topics=await api(`/api/projects/${p}/profiles`);
  if(!workspaceReads.current(scope)||!dialog.active()||project!==p)return;
  const v=versions.find(v=>v.id===task.result.version_id),current=topics.find(t=>t.id===task.profile_id)?.published_id;
  if(!v){dialog.body.append(node('p',task.version_status==='DELETED'?'该任务知识已删除，恢复历史任务不会恢复知识。':'该候选版本当前不可查看。','muted'));return;}
  if(role==='OWNER'&&v.status==='APPROVED')dialog.body.append(ExperienceUI.action('删除对应知识',async()=>{if(await deleteExperienceKnowledge({...j,...task}))dialog.close();},'danger'));
  const base=versions.find(x=>x.id===v.base_id),baseIds=new Set((base?.content.claims||[]).map(c=>c.id));
  dialog.body.append(node('p',`基于版本：${v.base_id?.slice(0,8)||'无（首次生成）'} · 共 ${v.content.claims.length} 条结论`,'muted'));
  if(base||!v.base_id)dialog.body.append(node('p',`本次新增 ${v.content.claims.filter(c=>!baseIds.has(c.id)).length} 条结论`,'muted'));
  dialog.body.append(versionCard(v,versions,current,async()=>{await refresh();await loadProfiles();if(profile&&project===p)await refreshCurrent();await loadTasks();await loadAudit();},{hideRollback:true}));
 };
 try{await refresh();}catch(e){if(workspaceReads.current(scope)&&dialog.active())dialog.body.replaceChildren(node('p','加载失败：'+e.message,'form-error'),ExperienceUI.action('重试',refresh));}
}

async function deleteExperienceKnowledge(j){
 const p=project;
 const confirmed=await new Promise(resolve=>{
  const dialog=ExperienceUI.modal('删除对应知识');
  dialog.body.append(node('p','将撤下本任务生成的知识版本，以及继承它的后续版本。若当前发布版本受影响，专题会变为待发布，问答和下载将停止使用这些知识。原材料保留。此操作不能通过恢复历史任务撤销。'));
  let answer=false;
  const actions=node('div',undefined,'actions');
  actions.append(ExperienceUI.action('取消',()=>dialog.close()),ExperienceUI.action('确认删除对应知识',()=>{answer=true;dialog.close();},'danger'));
  dialog.body.append(actions);dialog.dialog.addEventListener('close',()=>resolve(answer),{once:true});
 });
 if(!confirmed||project!==p)return false;
 await api(`/api/profiles/${j.profile_id}/jobs/${j.id}/experience`,{method:'DELETE'});
 message('对应知识及继承版本已撤下，原材料保留');
 await loadProfiles();if(profile)await refreshCurrent();await loadTasks();await loadAudit();
 return true;
}
