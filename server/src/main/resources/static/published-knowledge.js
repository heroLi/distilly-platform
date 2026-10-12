'use strict';
let publishedKnowledgeState={page:1,size:10,q:'',profile_id:''},publishedKnowledgeRevision=0;
function resetPublishedKnowledge(){publishedKnowledgeRevision++;publishedKnowledgeState={page:1,size:10,q:'',profile_id:''};$('published-knowledge-list').replaceChildren();$('published-knowledge-pager').replaceChildren();$('published-knowledge-query').value='';$('published-knowledge-topic').replaceChildren(node('option','全部专题'));}
async function loadPublishedKnowledge(){
 if(!project||page!=='published-knowledge')return;
 const scope=workspaceReads.capture(),p=project,revision=++publishedKnowledgeRevision,host=$('published-knowledge-list');
 host.setAttribute('aria-busy','true');
 try{
  const topics=await api(`/api/projects/${p}/profiles`);
  if(!workspaceReads.current(scope)||project!==p||revision!==publishedKnowledgeRevision||page!=='published-knowledge')return;
  const options=topics.filter(t=>t.published_id);
  if(publishedKnowledgeState.profile_id&&!options.some(t=>t.id===publishedKnowledgeState.profile_id))publishedKnowledgeState.profile_id='';
  const filter=$('published-knowledge-topic'),all=node('option','全部专题');all.value='';filter.replaceChildren(all,...options.map(t=>{const o=node('option',t.title);o.value=t.id;return o;}));filter.value=publishedKnowledgeState.profile_id;
  const data=await api(`/api/projects/${p}/published-knowledge/page?${new URLSearchParams(publishedKnowledgeState)}`);
  if(!workspaceReads.current(scope)||project!==p||revision!==publishedKnowledgeRevision||page!=='published-knowledge')return;
  if(!data.items.length&&data.total&&publishedKnowledgeState.page>1){publishedKnowledgeState.page=Math.ceil(data.total/publishedKnowledgeState.size);return loadPublishedKnowledge();}
  renderPublishedKnowledge(data.items);
  const pages=Math.max(1,Math.ceil(data.total/publishedKnowledgeState.size)),pager=$('published-knowledge-pager');pager.replaceChildren(node('span',`共 ${data.total} 条 · 第 ${publishedKnowledgeState.page}/${pages} 页`));
  for(const [delta,label]of [[-1,'上一页'],[1,'下一页']]){const b=ExperienceUI.action(label,()=>{publishedKnowledgeState.page+=delta;return loadPublishedKnowledge();});b.disabled=delta<0?publishedKnowledgeState.page===1:publishedKnowledgeState.page>=pages;pager.append(b);}
  const size=node('select');size.setAttribute('aria-label','已审核知识每页条数');for(const n of [5,10,20,50]){const o=node('option',n+' 条 / 页');o.value=n;size.append(o);}size.value=publishedKnowledgeState.size;size.onchange=()=>{publishedKnowledgeState.size=Number(size.value);publishedKnowledgeState.page=1;loadPublishedKnowledge().catch(e=>message(e.message,true));};pager.append(size);
 }catch(e){if(workspaceReads.current(scope)&&project===p&&revision===publishedKnowledgeRevision&&page==='published-knowledge'){host.replaceChildren(node('p','知识列表加载失败：'+e.message,'form-error'),ExperienceUI.action('重试',loadPublishedKnowledge));$('published-knowledge-pager').replaceChildren();}}finally{if(workspaceReads.current(scope)&&revision===publishedKnowledgeRevision)host.setAttribute('aria-busy','false');}
}
function renderPublishedKnowledge(rows){
 const {t,body}=table(['知识专题','章节','知识内容','材料依据','更新时间','操作']);
 for(const item of rows){const tr=node('tr');cell(tr,item.profile_title);cell(tr,item.section);const content=node('div',item.text,'knowledge-preview');cell(tr,content);cell(tr,`${item.evidence?.length||0} 处依据`);cell(tr,new Date(item.updated_at).toLocaleString());const actions=node('div',undefined,'actions');actions.append(ExperienceUI.action('查看详情',()=>openPublishedKnowledge(item)));if(role==='OWNER')actions.append(ExperienceUI.action('编辑知识',()=>editPublishedKnowledge(item)));cell(tr,actions,'row-actions');body.append(tr);}
 if(!rows.length){const row=node('tr');cell(row,'暂无符合条件的已审核知识，可重置搜索或先审核发布蒸馏候选。','empty-cell').colSpan=6;body.append(row);}
 $('published-knowledge-list').replaceChildren(t);
}
function publishedSources(item){
 const host=node('div');host.append(node('h3','材料依据'));
 for(const source of item.evidence||[]){const block=node('div',undefined,'source-card');block.append(node('blockquote',source.quote),node('small',`材料 ${source.material_id} · 分块 ${source.chunk_id}`));
  if(role!=='VIEWER')block.append(ExperienceUI.action('查看原材料',async()=>{const p=project,m=await api(`/api/materials/${source.material_id}`);if(project!==p)return;const dialog=ExperienceUI.modal('原材料 · '+m.filename);dialog.body.append(node('pre',m.body));}));host.append(block);
 }return host;
}
function openPublishedKnowledge(item){
 const dialog=ExperienceUI.modal('已审核知识详情');dialog.body.append(node('p',`${item.profile_title} · 当前发布版本 ${item.version_id.slice(0,8)}`,'muted'),node('h3',item.section),node('p',item.text,'knowledge-full-text'),publishedSources(item));
 if(role==='OWNER')dialog.body.append(ExperienceUI.action('编辑知识',()=>{dialog.close();editPublishedKnowledge(item);}));
}
function editPublishedKnowledge(item){
 if(role!=='OWNER')return;
 const p=project,dialog=ExperienceUI.modal('编辑已审核知识'),form=node('form');
 dialog.body.append(node('p',`${item.profile_title} · 基于版本 ${item.version_id.slice(0,8)}`,'muted'),node('p','保存后立即发布为新的人工修订版本，原版本保留。材料依据保持不变，请核对修改后的结论仍被原材料支持。','muted'));
 const section=node('input');section.value=item.section;section.required=true;section.maxLength=80;const sectionLabel=node('label','章节');sectionLabel.append(section);
 const content=node('textarea');content.value=item.text;content.required=true;content.maxLength=2000;content.rows=8;const contentLabel=node('label','知识内容');contentLabel.append(content);
 const note=node('textarea');note.maxLength=1000;note.rows=2;const noteLabel=node('label','修订说明（可选）');noteLabel.append(note);
 const error=node('p',undefined,'form-error');error.setAttribute('role','alert');const save=node('button','保存并发布修订','primary');save.type='submit';const actions=node('div',undefined,'actions');actions.append(ExperienceUI.action('取消',()=>dialog.close()),save);
 form.append(sectionLabel,contentLabel,noteLabel,publishedSources(item),error,actions);dialog.body.append(form);
 let submitting=false;
 form.onsubmit=async e=>{e.preventDefault();if(submitting)return;error.textContent='';if(!section.value.trim()||!content.value.trim()){error.textContent='章节和知识内容不能为空。';return;}if(project!==p||!dialog.active())return;
  submitting=true;save.disabled=true;save.textContent='正在保存…';
  try{const result=await api(`/api/profiles/${item.profile_id}/claims/${item.claim_id}`,{method:'PUT',body:{version_id:item.version_id,section:section.value.trim(),text:content.value.trim(),note:note.value.trim()}});if(project!==p)return;dialog.close();message(result.changed?'知识已修订并发布，原版本已保留':'内容未变化');await loadPublishedKnowledge();await loadProfiles();await loadAudit();}
  catch(e){if(dialog.active())error.textContent=e.message;}
  finally{submitting=false;save.disabled=false;save.textContent='保存并发布修订';}
 };
}
function openPublishedKnowledgeForTopic(topic){publishedKnowledgeState.profile_id=topic;publishedKnowledgeState.page=1;showPage('published-knowledge');}
bind('published-knowledge-search','submit',()=>{publishedKnowledgeState.q=$('published-knowledge-query').value.trim();publishedKnowledgeState.profile_id=$('published-knowledge-topic').value;publishedKnowledgeState.page=1;return loadPublishedKnowledge();});
bind('published-knowledge-topic','change',()=>{publishedKnowledgeState.profile_id=$('published-knowledge-topic').value;publishedKnowledgeState.page=1;return loadPublishedKnowledge();});
bind('published-knowledge-reset','click',()=>{publishedKnowledgeState.q='';publishedKnowledgeState.profile_id='';publishedKnowledgeState.page=1;$('published-knowledge-query').value='';return loadPublishedKnowledge();});
bind('published-knowledge-refresh','click',loadPublishedKnowledge);
if(location.hash==='#published-knowledge')page='published-knowledge';
