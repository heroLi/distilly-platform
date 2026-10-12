 'use strict';
// Read results have a context identity and a latest request per rendering slot.
window.UiReadScope={create(snapshot){
 let epoch=0;const revisions=new Map();
 function capture(key=null,live=()=>true){const revision=key?(revisions.get(key)||0)+1:0;if(key)revisions.set(key,revision);return {epoch,context:[...snapshot()],key,revision,live};}
 function current(scope){const now=snapshot();return scope.epoch===epoch&&scope.context.length===now.length&&scope.context.every((value,i)=>Object.is(value,now[i]))&&(!scope.key||revisions.get(scope.key)===scope.revision)&&scope.live();}
 async function read(key,fetchValue,live){const scope=capture(key,live);try{const value=await fetchValue(scope);return current(scope)?{value,scope}:null;}catch(error){if(!current(scope))return null;throw error;}}
 return {capture,current,read,invalidate(){epoch++;revisions.clear();}};
}};
