const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const base='server/src/main/resources/static/',workspace='11111111-1111-4111-8111-111111111111',personaId='22222222-2222-4222-8222-222222222222';
class Element{
 constructor(tag,text='',cls=''){Object.assign(this,{tag,textContent:text,children:[],className:cls,dataset:{},attributes:{},value:'',disabled:false,isConnected:true});}
 append(...nodes){for(const n of nodes){n.parentNode=this;this.children.push(n);}}
 replaceChildren(...nodes){for(const n of this.children)n.parentNode=null;this.children=[];this.textContent='';this.append(...nodes);}
 setAttribute(k,v){this.attributes[k]=v;} remove(){this.parentNode.children=this.parentNode.children.filter(x=>x!==this);this.parentNode=null;this.isConnected=false;}
 find(predicate){for(const n of this.children){if(predicate(n))return n;const hit=n.find(predicate);if(hit)return hit;}return null;}
 closest(tag){return this.tag===tag?this:this.parentNode?.closest(tag)||null;}
 querySelector(selector){return this.find(n=>selector==='[data-request-error]'?n.dataset.requestError==='true':n.tag===selector);}
}
const node=(tag,text,cls)=>new Element(tag,text,cls),modals=[],host=node('div');let actions=[],mode='valid',pendingResolve,pendingReject,posts=[],apiCalls=[];
const person={id:personaId,project_id:workspace,library:false,name:'青禾',family:'relationship',definition:{background:'原背景',personality:'尊重隐私',expression:'简洁',relationship:'朋友',boundaries:'不冒充真人',appearance:'绿色',origin:'authorized',consent:true},versions:[{private:'never copy'}],memories:['never copy']};
const c=vm.createContext({window:{},location:{search:'?workspace='+workspace+'&from_persona='+personaId},project:workspace,role:'OWNER',node,$:()=>host,URLSearchParams,AbortController,TypeError,setTimeout,clearTimeout,clearInterval(){},setInterval(){},message:(text,error)=>{c.lastMessage={text,error};},document:{createElement:node},FormData:class{constructor(form){const values=[];function read(n){if(n.name)values.push([n.name,n.value]);n.children.forEach(read);}read(form);this.values=values;}[Symbol.iterator](){return this.values[Symbol.iterator]();}},cell(){},table(){},statusLabels:{}});
c.window=c.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),c);c.UiReadScope=c.window.UiReadScope;
vm.runInContext("const workspaceReads=UiReadScope.create(()=>[typeof token==='undefined'?'':token,typeof project==='undefined'?'':project,typeof role==='undefined'?'':role]);",c);
vm.runInContext(fs.readFileSync(base+'platform-request.js','utf8'),c);c.PlatformRequest=c.window.PlatformRequest;
vm.runInContext(fs.readFileSync(base+'personality-prefill.js','utf8'),c);c.PersonalityPrefill=c.window.PersonalityPrefill;
vm.runInContext(fs.readFileSync(base+'experience-ui.js','utf8'),c);c.ExperienceUI=vm.runInContext('ExperienceUI',c);
c.ExperienceUI.modal=title=>{const dialog=node('dialog'),body=node('div');dialog.append(body);const m={title,dialog,body,open:true,active(){return this.open;},close(){this.open=false;}};modals.push(m);return m;};
c.ExperienceUI.list=(host,key,rows,headers,render,options)=>{if(key==='人格')actions=options.actions;};
async function offline(){c.fetch=async()=>{throw new TypeError('Failed to fetch');};return c.PlatformRequest.request('/api/fixture');}
c.api=async(path,options)=>{apiCalls.push(path);if(path.endsWith('/personalities')&&!options)return [];if(path==='/api/personas/'+personaId){if(mode==='deferred')return new Promise((resolve,reject)=>{pendingResolve=resolve;pendingReject=reject;});if(mode==='offline')return offline();if(mode==='denied'){const e=Error('当前角色无权执行此操作');e.code='role_denied';throw e;}return person;}
 if(options?.method==='POST'){posts.push(options.body);if(mode==='save-offline')return offline();if(mode==='save-deferred')return new Promise((resolve,reject)=>{pendingResolve=resolve;pendingReject=reject;});return {id:'library'};}
 if(path.endsWith('/jobs')||path.endsWith('/personas'))return [];
 if(path==='/api/personas/library')return {id:'library',name:'新人格',family:'relationship',created_at:'2026-10-12',definition:{},versions:[]};throw Error('unexpected path '+path);};
const source=fs.readFileSync(process.env.PREFILL_LIBRARY_SOURCE||base+'personality-library.js','utf8');vm.runInContext(source,c);
const load=()=>c.window.loadPersonalityLibrary(),button=label=>actions.find(a=>a.textContent===label),last=()=>modals.at(-1),field=(m,name)=>m.body.find(n=>n.name===name),save=m=>m.body.find(n=>n.textContent==='保存人格设定'),submit=m=>m.body.querySelector('form').onsubmit({preventDefault(){}});
(async()=>{
 await load();assert.ok(button('沿用人物设定新增人格'),'explicit prefill action offered only with matching source');assert.equal(posts.length,0,'navigation never writes');
 await button('沿用人物设定新增人格').onclick();let m=last();assert.equal(field(m,'name').value,'青禾人格');assert.equal(field(m,'background').value,'原背景');assert.equal(field(m,'origin').value,'authorized');assert.equal(m.body.find(n=>n.type==='checkbox').checked,false,'authorization never inherited');
 field(m,'name').value='用户改的名字';field(m,'background').value='用户改的背景';mode='save-offline';await submit(m);assert.equal(field(m,'name').value,'用户改的名字');assert.equal(field(m,'background').value,'用户改的背景');assert.equal(save(m).disabled,false);assert.match(m.body.querySelector('[data-request-error]').textContent,/无法连接服务/);assert.equal(m.body.querySelector('[data-request-error]').attributes.role,'alert');assert.equal(m.open,true);
 mode='save-deferred';const first=submit(m),before=posts.length;await submit(m);assert.equal(posts.length,before,'repeat submit disabled while pending');pendingReject(await offline().catch(e=>e));await first;
 mode='valid';await submit(m);assert.equal(m.open,false);const body=posts.at(-1);assert.equal(body.name,'用户改的名字');assert.equal(body.background,'用户改的背景');assert.equal(body.appearance,'绿色');assert.equal(body.consent,false);assert.equal(body.versions,undefined);assert.equal(body.memories,undefined);
 mode='deferred';let opening=button('沿用人物设定新增人格').onclick();m=last();m.close();pendingResolve(person);await opening;assert.equal(m.body.querySelector('form'),null,'closed modal cannot receive prefill');
 opening=button('沿用人物设定新增人格').onclick();m=last();c.project='other';pendingResolve(person);await opening;assert.equal(m.body.querySelector('form'),null,'space change cannot receive old prefill');c.project=workspace;
 mode='offline';await button('沿用人物设定新增人格').onclick();m=last();assert.match(m.body.querySelector('[data-request-error]').textContent,/无法连接服务/);const retry=m.body.find(n=>n.textContent==='重新读取人物设定');assert.ok(retry);mode='valid';await retry.onclick();assert.equal(field(last(),'background').value,'原背景');
 mode='denied';await button('沿用人物设定新增人格').onclick();assert.equal(last().body.querySelector('form'),null);assert.equal(last().body.querySelector('[data-request-error]'),null);assert.match(c.lastMessage.text,/当前角色/);
 c.role='VIEWER';await load();assert.equal(button('沿用人物设定新增人格'),undefined);assert.equal(button('新增人格'),undefined);
 c.role='OWNER';c.project='33333333-3333-4333-8333-333333333333';await load();assert.equal(button('沿用人物设定新增人格'),undefined);c.project=workspace;c.location.search='?workspace='+workspace+'&from_persona=../../private';await load();assert.equal(button('沿用人物设定新增人格'),undefined);
 c.location.search='';mode='valid';await load();await button('新增人格').onclick();assert.equal(field(last(),'background').value,'','ordinary create stays blank');
 assert.throws(()=>c.PersonalityPrefill.definition({...person,project_id:'other'},workspace),/当前工作空间/);assert.throws(()=>c.PersonalityPrefill.definition({...person,library:true},workspace),/当前工作空间/);
 // Drive the actual generated submit action through a transport rejection.
 const material={id:'m',filename:'fixture.md',creator:'owner',created_at:'2026-10-12'};let generateAction,selected;
 c.ExperienceUI.list=(host,key,rows,headers,render,opts)=>{if(key==='人格'){actions=opts.actions;const row=node('tr');c.cell=(r,item)=>{if(item?.children?.some(n=>n.textContent==='生成'))generateAction=item.children.find(n=>n.textContent==='生成');};render(row,{id:'library',name:'人格',family:'relationship',created_at:'2026-10-12'});}if(key==='人格生成材料'){const row=node('tr');c.cell=(r,item)=>{if(item?.type==='checkbox')selected=item;};render(row,material);}};
 c.api=async(path,options)=>{if(path.endsWith('/personalities'))return [];if(path.endsWith('/materials'))return [material];if(path.endsWith('/generate')){assert.deepEqual(Array.from(options.body.material_ids),['m']);return offline();}throw Error(path);};
 await load();await generateAction.onclick();m=last();selected.checked=true;selected.onchange();const start=m.body.find(n=>n.textContent==='提交生成');await start.onclick();assert.equal(selected.checked,true);assert.equal(start.disabled,false);assert.match(m.dialog.querySelector('[data-request-error]').textContent,/无法连接服务/);assert.equal(m.open,true);
 console.log('PASS: actual create/generate handlers retain input and choices with persistent alerts; explicit scoped prefill, edited values win, consent reset, stale reads and role/mismatch guards');
})().catch(e=>{console.error(e);process.exitCode=1;});
