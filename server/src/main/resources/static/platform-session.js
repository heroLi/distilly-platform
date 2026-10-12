 'use strict';
// Both modules share one same-origin, tab-scoped login and workspace selection.
window.PlatformSession={
 token(){const token=sessionStorage.getItem('platform-token')||sessionStorage.getItem('persona-token')||sessionStorage.getItem('distilly-token')||'';if(token)this.saveToken(token);return token;},
 saveToken(token){const previous=sessionStorage.getItem('platform-token');if(previous&&previous!==token)sessionStorage.removeItem('platform-workspace');sessionStorage.setItem('platform-token',token);sessionStorage.removeItem('persona-token');sessionStorage.removeItem('distilly-token');},
 workspace(){return sessionStorage.getItem('platform-workspace')||'';},
 selectWorkspace(id){if(id)sessionStorage.setItem('platform-workspace',id);else sessionStorage.removeItem('platform-workspace');},
 clear(){for(const key of ['platform-token','persona-token','distilly-token','platform-workspace'])sessionStorage.removeItem(key);}
};
