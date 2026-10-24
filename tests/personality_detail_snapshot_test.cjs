const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('server/src/main/resources/static/personality-library.js','utf8');
const begin=source.indexOf('async function renderDetail('),end=source.indexOf('m.body.replaceChildren();',begin);
assert.ok(begin>=0&&end>begin);
async function check(initiallyComplete=false){
 let committed=initiallyComplete,snapshot;const calls=[];
 const c=vm.createContext({project:'space',workspaceReads:{read:async(key,load,valid)=>{const value=await load();if(!valid())return; snapshot=value;return {value,scope:{}};}},
  api:async path=>{calls.push(path.endsWith('/jobs')?'jobs':'persona');if(path.endsWith('/jobs')){committed=true;return [{status:'SUCCEEDED',result:{version_id:'version'}}];}return {versions:committed?[{id:'version',status:'CANDIDATE'}]:[]};}});
 vm.runInContext(source.slice(begin,end)+'return read.value;}',c);
 await c.renderDetail('person',{active:()=>true},'space');
 assert.ok(snapshot[0].versions.some(v=>v.id===snapshot[1][0].result.version_id),'completed task must not strand a no-version detail without polling');
 return calls;
}
(async()=>{await check();await check(true);console.log('PASS: actual detail read observes committed version when task completion falls between the two API reads');})().catch(e=>{console.error(e.message);process.exitCode=1;});
