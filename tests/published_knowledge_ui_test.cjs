const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function element(tag,text,cls){return {tag,textContent:text||'',className:cls||'',children:[],value:'',attributes:{},append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=nodes;},setAttribute(k,v){this.attributes[k]=v;}};}
const ids=['published-knowledge-list','published-knowledge-pager','published-knowledge-query','published-knowledge-topic'];
const elements=Object.fromEntries(ids.map(id=>[id,element('div')])),dialogs=[],calls=[];
let delayed=false,release,rejected=false;
const entry={profile_id:'topic',profile_title:'接口经验',version_id:'version',claim_id:'claim',section:'规则',text:'知识正文',evidence:[{material_id:'material',chunk_id:'material:0',quote:'原文引文'}],updated_at:0};
const text=e=>[e.textContent,...e.children.map(text)].join(' ');
const find=(root,tag)=>root.tag===tag?root:root.children.map(n=>find(n,tag)).find(Boolean);
const all=(root,tag)=>[...(root.tag===tag?[root]:[]),...root.children.flatMap(n=>all(n,tag))];
const context=vm.createContext({project:'workspace',page:'published-knowledge',role:'OWNER',location:{hash:''},URLSearchParams,node:element,$:id=>elements[id],bind(){},table:()=>{const t=element('table'),body=element('tbody');t.append(body);return {t,body};},cell:(row,c)=>{const n=element('td',typeof c==='string'?c:'');if(typeof c!=='string')n.append(c);row.append(n);return n;},message(){},loadProfiles:async()=>{},loadAudit:async()=>{},showPage(){},api:async(path,options)=>{
 calls.push({path,options});
 if(options?.method==='PUT'){if(delayed)await new Promise(r=>release=r);if(rejected)throw Error('当前发布版本已变化');return {changed:true,version_id:'revision'};}
 if(path==='/api/projects/workspace/profiles')return [{id:'topic',title:'接口经验',published_id:'version'}];
 if(path.startsWith('/api/projects/workspace/published-knowledge/page')){if(delayed)await new Promise(r=>release=r);return {items:[entry],total:1};}
 throw Error(path);
 },ExperienceUI:{action:(label,callback)=>Object.assign(element('button',label),{callback}),modal:()=>{const body=element('div');let open=true;const dialog={body,close:()=>open=false,active:()=>open};dialogs.push(dialog);return dialog;}}});
context.window=context.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),context);context.UiReadScope=context.window.UiReadScope;
vm.runInContext("const workspaceReads=UiReadScope.create(()=>[typeof token==='undefined'?'':token,typeof project==='undefined'?'':project,typeof role==='undefined'?'':role]);",context);
vm.runInContext(fs.readFileSync('server/src/main/resources/static/published-knowledge.js','utf8'),context);
(async()=>{
 await vm.runInContext('loadPublishedKnowledge()',context);assert.match(text(elements['published-knowledge-list']),/编辑知识/);assert.match(text(elements['published-knowledge-pager']),/共 1 条/);
 context.role='VIEWER';await vm.runInContext('loadPublishedKnowledge()',context);assert.doesNotMatch(text(elements['published-knowledge-list']),/编辑知识/);
 context.role='OWNER';context.entry=entry;vm.runInContext('editPublishedKnowledge(entry)',context);let dialog=dialogs.at(-1),form=find(dialog.body,'form');const input=find(form,'input'),areas=all(form,'textarea');input.value='新章节';areas[0].value='手工修订知识';areas[1].value='人工说明';
 rejected=true;await form.onsubmit({preventDefault(){}});assert.equal(dialog.active(),true);assert.match(text(form),/当前发布版本已变化/);assert.equal(all(form,'button').find(b=>b.type==='submit').disabled,false);
 rejected=false;delayed=true;const pending=form.onsubmit({preventDefault(){}});await form.onsubmit({preventDefault(){}});assert.equal(calls.filter(c=>c.options?.method==='PUT').length,2);delayed=false;release();await pending;assert.equal(dialog.active(),false);
 const update=calls.find(c=>c.options?.method==='PUT');assert.equal(update.options.body.version_id,'version');assert.equal(update.options.body.section,'新章节');assert.equal(update.options.body.text,'手工修订知识');assert.equal(update.options.body.evidence,undefined);
 delayed=true;const loading=vm.runInContext('loadPublishedKnowledge()',context);await Promise.resolve();context.project='another';release();await loading;assert.equal(text(elements['published-knowledge-list']).includes('another'),false);
 console.log('PASS: pagination, viewer edit boundary, safe edit payload, duplicate submit prevention, conflict recovery and workspace stale response');
})().catch(error=>{console.error(error);process.exitCode=1;});
