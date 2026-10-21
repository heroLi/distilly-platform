'use strict';
// Read-only owner view. Simulated amounts never authorize a model or provider call.
window.SyntheticCostsUI={create({getState,reads,request,element}){
 const el=id=>element('synthetic-cost-'+id),states={RESERVED:'已预留',UNCERTAIN:'结果未知，继续占款',SETTLED:'已结算',RELEASED:'已释放'};
 const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const integer=(value,min=0,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&value>=min&&value<=max;
 const money=value=>`${value} 分（¥${(value/100).toFixed(2)}）`;
 let generation=0,detailGeneration=0,identity='',active=false,suspended=false,busy=false,page=1,total=0,loaded=false,detailLoading=null;
 const context=()=>{const s=getState();return JSON.stringify([s.token,s.user,s.project,s.role,s.demo]);};
 const allowed=()=>{const s=getState();return !!(s.token&&s.project&&s.role==='OWNER'&&s.demo===true);};
 const ready=()=>active&&!suspended&&allowed();
 const base=()=>'/api/projects/'+encodeURIComponent(getState().project)+'/synthetic-costs';
 function buttons(){el('refresh').disabled=busy||!ready();el('filter').disabled=busy||!ready();el('prev').disabled=busy||!ready()||!loaded||page<=1;el('next').disabled=busy||!ready()||!loaded||page>=Math.max(1,Math.ceil(total/20));}
 function closeDetail(){detailLoading=null;detailGeneration++;el('detail').hidden=true;el('detail-body').innerHTML='';}
 function clearSnapshot(){loaded=false;total=0;el('summary').innerHTML='';el('list').innerHTML='';el('page').textContent='';closeDetail();}
 function idle(){const s=getState();return !s.token||!s.project?'请选择已登录的工作空间。':s.role!=='OWNER'?'仅当前工作空间负责人可查看合成账本。':s.demo!==true?'当前不是演示环境，合成账本不可用。真实供应商费用未核验，出口关闭。':'尚未读取合成账本，点击刷新查看当前状态。';}
 function reset(disable=false){generation++;suspended=disable===true;busy=false;page=1;identity=context();el('filter').value='UNRESOLVED';clearSnapshot();el('status').textContent=idle();buttons();}
 function sync(){if(identity!==context())reset();buttons();}
 function contract(data){return data?.contract==='SYNTHETIC_CNY_LEDGER_V1'&&data.synthetic===true&&data.real_egress_authorized===false;}
 function record(row){return row&&typeof row.id==='string'&&row.id.length>0&&row.project_id===getState().project&&typeof row.request_key==='string'&&Object.hasOwn(states,row.status)&&integer(row.reserved_fen,1,1000)&&integer(row.created_at,0,8640000000000000)&&integer(row.updated_at,0,8640000000000000)&&(row.status==='SETTLED'?integer(row.actual_fen,0,row.reserved_fen):row.status==='RELEASED'?row.actual_fen===0:row.actual_fen===null);}
 function summary(data){return contract(data)&&data.currency==='CNY'&&data.unit==='fen'&&data.limit_fen===1000&&['held_fen','spent_fen','available_fen'].every(k=>integer(data[k],0,1000))&&data.held_fen+data.spent_fen+data.available_fen===1000&&integer(data.reservation_count)&&integer(data.unresolved_count,0,data.reservation_count);}
 function render(data){const s=data.summary;el('summary').innerHTML=[['合成额度',s.limit_fen],['模拟已计费',s.spent_fen],['当前占款',s.held_fen],['模拟可用',s.available_fen]].map(([label,value])=>`<div class="synthetic-cost-metric"><span>${label}</span><strong>${money(value)}</strong></div>`).join('')+`<p class="synthetic-cost-count">预留共 ${s.reservation_count} 笔 · 未决 ${s.unresolved_count} 笔。预留与结果未知均占款，未知不会自动释放。</p>`;
  el('list').innerHTML=data.items.length?'<table><thead><tr><th scope="col">合成请求键</th><th scope="col">当前状态</th><th scope="col">预留</th><th scope="col">模拟计费</th><th scope="col">已释放／差额</th><th scope="col">当前占款</th><th scope="col">操作</th></tr></thead><tbody>'+data.items.map(r=>{const held=r.status==='RESERVED'||r.status==='UNCERTAIN',released=r.status==='RELEASED'?r.reserved_fen:r.status==='SETTLED'?r.reserved_fen-r.actual_fen:0;return `<tr><td>${esc(r.request_key)}<small>${esc(r.id)}</small></td><td>${states[r.status]}<small>${r.status}</small><small>更新 ${new Date(r.updated_at).toLocaleString('zh-CN')}</small></td><td>${money(r.reserved_fen)}</td><td>${held?'尚未确定':money(r.actual_fen)}</td><td>${money(released)}</td><td>${money(held?r.reserved_fen:0)}</td><td><button type="button" data-cost-reservation="${esc(r.id)}">查看事件</button></td></tr>`;}).join('')+'</tbody></table>':`<p class="muted">${el('filter').value==='UNRESOLVED'?'当前没有未决预留。':'当前没有符合条件的合成预留。'}可切换状态查看历史；本页不创建真实调用。</p>`;
  el('page').textContent=`共 ${data.total} 笔 · 第 ${page} / ${Math.max(1,Math.ceil(data.total/20))} 页`;
 }
 async function load(){sync();if(busy||!ready())return;const mine=++generation,scope=context(),requested=page,filter=el('filter').value;const current=()=>mine===generation&&scope===context()&&ready();busy=true;clearSnapshot();el('status').textContent='正在读取当前合成账本…';buttons();
  try{const read=await reads.read('synthetic-cost-page',()=>request(base()+`/reservations?page=${requested}&size=20&status=${encodeURIComponent(filter)}`),current);if(!read||!current())return;const data=read.value;if(!contract(data)||!summary(data.summary)||data.page!==requested||data.size!==20||!integer(data.total)||!Array.isArray(data.items)||data.items.length>20||data.items.length>data.total||!data.items.every(record))throw Error('合成账本响应未通过核对。');
   if(!data.items.length&&data.total&&requested>Math.ceil(data.total/20)){page=Math.ceil(data.total/20);busy=false;return load();}
   total=data.total;loaded=true;render(data);el('status').textContent='已读取当前合成快照。金额仅供演示，真实供应商费用不可核验，真实模型出口关闭。';
  }catch(error){if(!current())return;clearSnapshot();el('status').textContent=[401,403,404].includes(error.status)?'合成账本当前不可访问，已清除金额与记录。请核对登录、负责人权限及演示环境。':'合成账本加载失败，已清除旧快照。请点击刷新重试。';}finally{if(current()){busy=false;buttons();}}
 }
 async function detail(id){sync();if(!ready()||busy||!loaded||detailLoading===id)return;closeDetail();detailLoading=id;const mine=++detailGeneration,scope=context(),listGeneration=generation;const current=()=>mine===detailGeneration&&scope===context()&&listGeneration===generation&&ready();el('detail').hidden=false;el('detail-body').textContent='正在读取当前预留事件…';
  try{const read=await reads.read('synthetic-cost-detail',()=>request(base()+'/reservations/'+encodeURIComponent(id)),current);if(!read||!current())return;const r=read.value;if(!contract(r)||!record(r)||r.id!==id||!Array.isArray(r.events)||r.events.length<1||r.events.length>3||!r.events.every((e,i)=>e.event_number===i+1&&e.reservation_id===id&&e.project_id===getState().project&&Object.hasOwn(states,e.outcome)&&typeof e.evidence==='string'&&(e.actual_fen===null||integer(e.actual_fen,0,r.reserved_fen))))throw Error('预留事件响应未通过核对。');
   el('detail-body').innerHTML=`<h3>${esc(r.request_key)}</h3><p>${states[r.status]} · ${r.status} · 预留 ${money(r.reserved_fen)}</p><p class="muted">记录更新时间：${new Date(r.updated_at).toLocaleString('zh-CN')}</p><p class="muted">本次详情重新读取；上方摘要仍是列表快照，可刷新核对。模拟回执不是供应商账单。</p><ol>`+r.events.map(e=>`<li><strong>${states[e.outcome]} · ${e.outcome}</strong><p>模拟回执：${esc(e.evidence)} · ${e.actual_fen===null?'费用未确定':money(e.actual_fen)}</p></li>`).join('')+'</ol>';
  }catch(error){if(!current())return;if([401,403,404].includes(error.status)){generation++;busy=false;clearSnapshot();el('status').textContent='预留当前不可访问，已清除金额与记录。请刷新核对权限或状态。';buttons();}else el('detail-body').textContent='预留详情加载失败，请关闭后重新查看或刷新列表。';}finally{if(current())detailLoading=null;}
 }
 function show(value){active=value===true;if(!active){reset(true);return;}suspended=false;sync();if(!allowed()){reset();return;}return load();}
 el('refresh').onclick=()=>load();el('filter').onchange=()=>{if(busy)return;page=1;return load();};el('prev').onclick=()=>{if(busy||!loaded||page<=1)return;page--;return load();};el('next').onclick=()=>{if(busy||!loaded||page>=Math.ceil(total/20))return;page++;return load();};el('detail-close').onclick=closeDetail;el('list').onclick=event=>{const target=event.target.closest('[data-cost-reservation]');if(target)return detail(target.dataset.costReservation);};
 reset();return {reset,show,load,detail,closeDetail};
}};
if(typeof workspaceReads!=='undefined'){
 window.SyntheticCosts=window.SyntheticCostsUI.create({getState:()=>({token,user:meId,project,role,demo:meDemo}),reads:workspaceReads,request:api,element:$});
 window.SyntheticCosts.show(page==='settings');
}
