const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(process.env.TABS_SOURCE||'server/src/main/resources/static/personality-library.js','utf8');
const block=source.slice(source.indexOf("const tabs=node('div'"),source.indexOf('const [content,definition,sources,bindings,records]'));
let focus=null;class Element{
 constructor(tag,text='',cls=''){this.tag=tag;this.textContent=text;this.children=[];this.attributes={};this.dataset={};this.classes=new Set(cls?cls.split(' '):[]);this.classList={toggle:(name,on)=>on?this.classes.add(name):this.classes.delete(name)};}
 append(...items){this.children.push(...items);}setAttribute(k,v){this.attributes[k]=v;}getAttribute(k){return this.attributes[k];}focus(){focus=this;}
}
const node=(...args)=>new Element(...args),c=vm.createContext({node,m:{body:node('div')},detailSequence:0});
vm.runInContext(block+';globalThis.result={tabs,panels};',c);
const {tabs,panels}=c.result,buttons=tabs.children;
function selected(index){assert.equal(buttons.filter(b=>b.getAttribute('aria-selected')==='true').length,1);assert.equal(buttons.filter(b=>b.tabIndex===0).length,1);buttons.forEach((b,i)=>{assert.equal(b.getAttribute('aria-selected'),String(i===index));assert.equal(b.tabIndex,i===index?0:-1);assert.equal(panels[i].hidden,i!==index);assert.equal(b.classes.has('primary'),i===index);});}
function key(index,name){let prevented=false;assert.equal(typeof buttons[index].onkeydown,'function','tab keyboard handler exists');buttons[index].onkeydown({key:name,preventDefault(){prevented=true;}});return prevented;}
selected(0);assert.ok(tabs.getAttribute('aria-label'));assert.equal(tabs.getAttribute('aria-orientation'),'horizontal');
buttons.forEach((b,i)=>{assert.equal(panels[i].getAttribute('role'),'tabpanel');assert.equal(b.getAttribute('aria-controls'),panels[i].id);assert.equal(panels[i].getAttribute('aria-labelledby'),b.id);assert.equal(panels[i].tabIndex,0);});
// Keep a populated field in a panel: navigation must not recreate or reset it.
const retained=node('textarea');retained.value='保留未提交的合成输入';panels[1].append(retained);
assert.equal(key(0,'ArrowLeft'),true);selected(4);assert.equal(focus,buttons[4]);
assert.equal(key(4,'ArrowRight'),true);selected(0);assert.equal(focus,buttons[0]);
key(0,'ArrowRight');selected(1);assert.equal(focus,buttons[1]);key(1,'End');selected(4);key(4,'Home');selected(0);
for(const k of ['Tab','Enter',' ','Escape','ArrowDown','ArrowUp']){assert.equal(key(0,k),false,k+' keeps native behavior');selected(0);}
buttons[2].onclick();selected(2);assert.equal(retained.value,'保留未提交的合成输入');assert.equal(panels[1].children[0],retained);
const firstIds=panels.map(p=>p.id);vm.runInContext('{'+block+';globalThis.secondPanels=panels;}',c);assert.equal(new Set([...firstIds,...c.secondPanels.map(p=>p.id)]).size,10,'separate modal renders have unique ARIA IDs');
console.log('PASS: actual detail tab handlers wrap arrows, Home/End, native keys, roving focus/ARIA, unique panel IDs and retained DOM/input');
