 'use strict';
window.PersonalityPrefill=(()=>{
 const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
 function link(workspace,persona){return '/knowledge.html?'+new URLSearchParams({workspace,from_persona:persona})+'#personalities';}
 function source(search,workspace){const query=new URLSearchParams(search),id=query.get('from_persona');return uuid.test(id||'')&&query.get('workspace')===workspace&&uuid.test(workspace||'')?id:null;}
 function definition(persona,workspace){if(persona.project_id!==workspace||persona.library!==false)throw Error('只能沿用当前工作空间的虚拟人设定。');const from=persona.definition||{},result={};
  for(const key of ['background','personality','expression','relationship','boundaries','appearance'])result[key]=typeof from[key]==='string'?from[key]:'';
  result.name=(String(persona.name||'')+'人格').slice(0,100);result.family=persona.family;
  result.origin=from.origin==='authorized'?'authorized':'original';result.consent=false;return result;
 }
 return {link,source,definition};
})();
