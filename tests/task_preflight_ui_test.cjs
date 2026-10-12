const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function node(tag,text){return {tag,textContent:text||'',children:[],append(...children){this.children.push(...children);},replaceChildren(...children){this.textContent='';this.children=children;}};}
const elements={'task-profile':{value:'topic'},'task-modal':{open:true},'task-preflight':{},'task-error':node('p'),'task-preflight-result':node('div')};
let release,last;
const context=vm.createContext({project:'workspace',taskPreflightRevision:0,selectedMaterials:new Set(['material']),node,$:id=>elements[id],api:(path,options)=>{last={path,options};return new Promise(resolve=>release=resolve);}});
const source=fs.readFileSync('server/src/main/resources/static/app.js','utf8');
context.window=context.window||{};vm.runInContext(fs.readFileSync('server/src/main/resources/static/ui-read-scope.js','utf8'),context);context.UiReadScope=context.window.UiReadScope;
vm.runInContext("const workspaceReads=UiReadScope.create(()=>[typeof token==='undefined'?'':token,typeof project==='undefined'?'':project,typeof role==='undefined'?'':role]);",context);
vm.runInContext(source.slice(source.indexOf('function clearTaskPreflight('),source.indexOf('async function openTaskModal(')),context);
const plan={material_count:1,character_count:20000,chunk_count:10,base_claim_count:250,estimated_claim_ceiling:450,claim_limit:300};
const text=n=>[n.textContent,...n.children.map(text)].join(' ');
(async()=>{
 let pending=vm.runInContext('checkTaskPreflight()',context);assert.equal(last.path,'/api/projects/workspace/distill/preflight');assert.equal(last.options.body.profile_id,'topic');release(plan);await pending;
 assert.match(text(elements['task-preflight-result']),/10 个分块/);assert.match(text(elements['task-preflight-result']),/超过专题 300 条限制/);
 pending=vm.runInContext('checkTaskPreflight()',context);vm.runInContext('clearTaskPreflight()',context);release(plan);await pending;assert.equal(text(elements['task-preflight-result']),'');
 elements['task-profile'].value='__new__';pending=vm.runInContext('checkTaskPreflight()',context);assert.equal(last.options.body.profile_id,null);context.project='other';vm.runInContext('clearTaskPreflight()',context);release(plan);await pending;assert.equal(text(elements['task-preflight-result']),'');
 context.project='workspace';context.selectedMaterials.clear();await vm.runInContext('checkTaskPreflight()',context);assert.match(elements['task-error'].textContent,/请先选择生成材料/);assert.equal(elements['task-preflight'].disabled,false);
 console.log('PASS: task size preview, budget warning, new-topic selection and stale response protection');
})().catch(error=>{console.error(error);process.exitCode=1;});
