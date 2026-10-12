const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('server/src/main/resources/static/platform-session.js','utf8'),storage=new Map([['persona-token','demo-owner']]);
const sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
function modulePage(){const context=vm.createContext({window:{},sessionStorage});vm.runInContext(source,context);return context.window.PlatformSession;}
const studio=modulePage();assert.equal(studio.token(),'demo-owner');studio.selectWorkspace('workspace-2');
const library=modulePage();assert.equal(library.token(),'demo-owner');assert.equal(library.workspace(),'workspace-2');assert.equal(storage.has('persona-token'),false);
library.selectWorkspace('workspace-3');assert.equal(modulePage().workspace(),'workspace-3');
library.saveToken('demo-viewer');assert.equal(library.workspace(),'');library.clear();assert.equal(modulePage().token(),'');
storage.set('distilly-token','demo-editor');assert.equal(modulePage().token(),'demo-editor');assert.equal(storage.has('distilly-token'),false);
console.log('PASS: legacy login migration, both module directions, workspace preservation, account switch and unified logout');
