const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function element(tag,text){return {tag,textContent:text||'',children:[],hidden:false,append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=nodes;},setAttribute(){}};}
const dialogs=[],elements={'detail':element('dialog'),'topic-tasks':element('section'),'topic-task-list':element('div')};
let closed=false,delay=false,release,listStatus='CANDIDATE';
const api=async path=>{
 if(delay&&path.startsWith('/api/jobs/'))await new Promise(resolve=>release=resolve);
 if(path==='/api/jobs/answer')return {id:'answer',kind:'ANSWER',status:'SUCCEEDED',result:{answer:'该任务独立回答',version_id:'fixed-version',citations:['claim-1']}};
 if(path==='/api/jobs/distill')return {id:'distill',profile_id:'topic',kind:'DISTILL',status:'SUCCEEDED',result:{version_id:'candidate'},progress:{completed:1,total:1}};
 if(path==='/api/profiles/topic/versions')return [{id:'candidate',base_id:null,status:'CANDIDATE',content:{claims:[{id:'claim-1'}]}}];
 if(path==='/api/projects/workspace/profiles')return [{id:'topic',published_id:null}];
 if(path.startsWith('/api/profiles/topic/jobs/page'))return {items:[{id:'distill',creator:'editor',created_at:0,status:'SUCCEEDED',version_status:listStatus}],total:1};
 throw Error(path);
};
const context=vm.createContext({project:'workspace',profile:'topic',role:'OWNER',URLSearchParams,Set,node:element,table:()=>{const t=element('table'),body=element('tbody');t.append(body);return {t,body};},cell:(row,content)=>{const td=element('td',typeof content==='string'?content:'');if(typeof content!=='string')td.append(content);row.append(td);return td;},$:id=>elements[id],api,statusLabels:{SUCCEEDED:'已完成'},message:()=>{},versionCard:(v,versions,current,after,opts)=>{assert.equal(v.id,'candidate');assert.equal(opts.hideRollback,true);return element('article','候选内容');},ExperienceUI:{action:(label,callback)=>Object.assign(element('button',label),{callback}),modal:()=>{const body=element('div');dialogs.push(body);return {body,active:()=>!closed};}}});
context.window=context.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),context);context.UiReadScope=context.window.UiReadScope;
vm.runInContext("const workspaceReads=UiReadScope.create(()=>[typeof token==='undefined'?'':token,typeof project==='undefined'?'':project,typeof role==='undefined'?'':role]);",context);
const appSource=fs.readFileSync('server/src/main/resources/static/app.js','utf8');vm.runInContext(appSource.slice(appSource.indexOf('function topicDetailVisible('),appSource.indexOf('async function loadVersions(')),context);
vm.runInContext(fs.readFileSync('server/src/main/resources/static/experience-tasks.js','utf8'),context);
const text=e=>[e.textContent,...e.children.map(text)].join(' ');
(async()=>{
 await vm.runInContext('openExperienceTask({id:"answer"})',context);assert.match(text(dialogs.at(-1)),/该任务独立回答/);assert.match(text(dialogs.at(-1)),/fixed-version/);assert.doesNotMatch(text(dialogs.at(-1)),/候选内容/);
 await vm.runInContext('openExperienceTask({id:"distill"})',context);assert.match(text(dialogs.at(-1)),/本次新增 1 条/);assert.match(text(dialogs.at(-1)),/候选内容/);
 await vm.runInContext('loadTopicTasks()',context);assert.match(text(elements['topic-task-list']),/待审核/);assert.match(text(elements['topic-task-list']),/审核发布/);
 listStatus='APPROVED';await vm.runInContext('loadTopicTasks()',context);assert.match(text(elements['topic-task-list']),/删除对应知识/);context.role='EDITOR';await vm.runInContext('loadTopicTasks()',context);assert.doesNotMatch(text(elements['topic-task-list']),/删除对应知识/);context.role='VIEWER';await vm.runInContext('loadTopicTasks()',context);assert.equal(elements['topic-tasks'].hidden,true);
 delay=true;const pending=vm.runInContext('openExperienceTask({id:"distill"})',context);closed=true;release();await pending;assert.equal(text(dialogs.at(-1)),' 正在加载任务详情…');
 console.log('PASS: task-specific answer/version, candidate isolation, review entry, viewer boundary, closed-dialog stale response');
})().catch(error=>{console.error(error);process.exitCode=1;});
