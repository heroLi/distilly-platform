'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const base='server/src/main/resources/static/',workspace='11111111-1111-4111-8111-111111111111',a='22222222-2222-4222-8222-222222222222',b='33333333-3333-4333-8333-333333333333',library='44444444-4444-4444-8444-444444444444',version='55555555-5555-4555-8555-555555555555';
class Element{
 constructor(tag,text='',cls=''){Object.assign(this,{tag,textContent:text,children:[],className:cls,value:'',disabled:false,innerHTML:'',hidden:false,attributes:{}});}
 append(...nodes){this.children.push(...nodes);} replaceChildren(...nodes){this.children=nodes;this.textContent='';}
 setAttribute(k,v){this.attributes[k]=v;} find(fn){for(const child of this.children){if(fn(child))return child;const found=child.find(fn);if(found)return found;}return null;}
}
const node=(...args)=>new Element(...args),bound=(id=b)=>({id,project_id:workspace,library:false,name:'合成目标',published_id:'bound-version',versions:[{id:'bound-version',status:'APPROVED',content:{binding:{personality_id:library,version_id:version}}}]});
function setup(source=b){
 const c=vm.createContext({window:{},URLSearchParams});vm.runInContext(fs.readFileSync(base+'personality-prefill.js','utf8'),c);c.PersonalityPrefill=c.window.PersonalityPrefill;
 vm.runInContext(fs.readFileSync(base+'personality-binding.js','utf8'),c);
 let current=true,open=true,after=0,handler=async()=>bound();const calls=[];
 const m={body:node('div'),active:()=>open,close:()=>open=false};
 c.window.PersonalityBindingFlow.open({workspace,personalityId:library,versionId:version,people:[{id:a,name:'第一个人物'},{id:b,name:'合成目标'}],sourcePersona:source,current:()=>current,
  api:async(path,options)=>{calls.push({path,options});return handler(path,options);},node,modal:()=>m,onBound:async()=>{after++;if(handler.afterError)throw Error('refresh failed');}});
 return {c,m,calls,set:fn=>handler=fn,invalidate:()=>current=false,after:()=>after,select:()=>m.body.find(n=>n.tag==='select'),button:text=>m.body.find(n=>n.tag==='button'&&n.textContent===text),link:()=>m.body.find(n=>n.tag==='a'),status:()=>m.body.find(n=>n.attributes.role==='status')};
}
(async()=>{
 let cases=0,h=setup();assert.equal(h.select().value,b);assert.equal(h.calls.length,0);await h.button('确认绑定').onclick();assert.equal(h.calls[0].path,'/api/personas/'+b+'/personality');assert.equal(h.calls[0].options.method,'PUT');assert.match(h.link().href,/persona=33333333/);assert.match(h.link().href,/#chat$/);assert.equal(h.after(),1);cases++;
 h=setup('unavailable');assert.equal(h.select().value,'');await h.button('确认绑定').onclick();assert.equal(h.calls.length,0);assert.match(h.status().textContent,/请先选择/);h.select().value=a;h.set(async()=>bound(a));await h.button('确认绑定').onclick();assert.match(h.link().href,/persona=22222222/);cases++;
 h=setup();let committed=false;h.set(async(path,options)=>{if(options){committed=true;throw new TypeError('连接中断');}assert.ok(committed);return bound();});await h.button('确认绑定').onclick();assert.equal(h.select().value,b);assert.match(h.status().textContent,/核对绑定状态/);assert.equal(h.calls.length,1);await h.button('核对绑定状态').onclick();assert.ok(h.link());assert.equal(h.calls.filter(x=>x.options).length,1);cases++;
 h=setup();h.set(async()=>{throw Error('暂时离线');});await h.button('确认绑定').onclick();assert.equal(h.button('确认绑定').disabled,false);h.set(async()=>bound());await h.button('确认绑定').onclick();assert.ok(h.link());assert.equal(h.calls.length,2);cases++;
 h=setup();h.set(async()=>({id:b,project_id:workspace,library:false,versions:[]}));await h.button('核对绑定状态').onclick();assert.equal(h.link(),null);assert.equal(h.calls[0].options,undefined);assert.match(h.status().textContent,/尚未确认/);cases++;
 for(const status of [401,403,404]){h=setup();h.set(async()=>{throw Object.assign(Error('denied'),{status});});await h.button('确认绑定').onclick();assert.equal(h.button('确认绑定').disabled,true);assert.equal(h.button('核对绑定状态').disabled,true);assert.equal(h.link(),null);cases++;}
 for(const ending of ['close','scope'])for(const outcome of ['resolve','reject']){h=setup();let resolve,reject;h.set(()=>new Promise((yes,no)=>{resolve=yes;reject=no;}));const pending=h.button('确认绑定').onclick();await h.button('确认绑定').onclick();assert.equal(h.calls.length,1);if(ending==='close')h.m.close();else h.invalidate();outcome==='resolve'?resolve(bound()):reject(Error('late failure'));await pending;assert.equal(h.link(),null);assert.equal(h.after(),0);cases++;}
 for(const wrong of [{...bound(),id:a},{...bound(),project_id:'other'},{...bound(),library:true},{...bound(),published_id:'other-version'},{...bound(),versions:[{id:'bound-version',status:'CANDIDATE',content:{binding:{personality_id:library,version_id:version}}}]}]){h=setup();h.set(async()=>wrong);await h.button('确认绑定').onclick();assert.equal(h.link(),null);cases++;}
 h=setup();const refreshFail=async()=>bound();refreshFail.afterError=true;h.set(refreshFail);await h.button('确认绑定').onclick();assert.ok(h.link());assert.equal(h.calls.length,1);assert.ok(h.m.body.find(n=>/列表刷新失败/.test(n.textContent)));cases++;
 assert.equal(h.c.PersonalityPrefill.target('?workspace='+workspace+'&persona='+b,workspace),b);assert.equal(h.c.PersonalityPrefill.target('?workspace=other&persona='+b,workspace),null);assert.throws(()=>h.c.PersonalityPrefill.returnLink(workspace,'https://outside.invalid'),/无效/);cases++;
 // Execute the actual Studio loader: a return URL never silently opens the first wrong person.
 async function studio({search,people,selected=null}){
  const elements={},s={token:'fixture',epoch:1,project:{id:workspace},persona:selected,session:{id:'old'}};
  const c=vm.createContext({window:{},location:{search},state:s,PersonalityPrefill:h.c.PersonalityPrefill,$:id=>elements[id]||(elements[id]=node('div')),escapeHtml:String,familyLabels:{},notice:message=>c.lastNotice=message,
   studioReads:{read:async()=>({value:people})},api:async()=>people,selectPersona:async id=>{s.epoch++;s.persona={id};c.selected=id;},switchView:view=>c.view=view});
  vm.runInContext(fs.readFileSync(base+'persona.js','utf8').split('\n').find(line=>line.startsWith('async function loadPeople(')),c);await c.loadPeople();return {c,elements,s};
 }
 let s=await studio({search:'?workspace='+workspace+'&persona='+b,people:[{id:a},{id:b}]});assert.equal(s.c.selected,b);assert.equal(s.c.view,'chat');cases++;
 s=await studio({search:'?workspace='+workspace+'&persona='+b,people:[{id:a}]});assert.equal(s.c.selected,undefined);assert.equal(s.s.persona,null);assert.equal(s.s.session,null);assert.equal(s.elements['persona-content'].hidden,true);assert.match(s.c.lastNotice,/不可用/);cases++;
 s=await studio({search:'?workspace=other&persona='+b,people:[{id:a},{id:b}]});assert.equal(s.c.selected,a);assert.equal(s.c.view,undefined);cases++;
 s=await studio({search:'?workspace='+workspace+'&persona='+b,people:[{id:a},{id:b}],selected:{id:a}});assert.equal(s.c.selected,a);assert.equal(s.c.view,undefined);cases++;
 console.log('PASS:',cases,'actual binding/return UI scenarios; explicit source target, lost-ack readback, guarded retry, revocation, late responses and safe refresh');
})().catch(error=>{console.error(error);process.exitCode=1;});
