 'use strict';
// Only rejected transport operations are translated; HTTP and payload errors stay distinct.
window.PlatformRequest=(()=>{
 const transportErrors=new WeakSet();
 const messages={network_unavailable:'无法连接服务。请检查网络和服务状态后重试；提交无响应时，请先查看任务记录，避免重复提交。',request_timeout:'请求超时。请稍后重试；提交无响应时，请先查看任务记录，避免重复提交。'};
 function failure(code){const error=new Error(messages[code]);error.code=code;transportErrors.add(error);return error;}
 async function transport(read,controller){try{return await read();}catch(error){if(controller.signal.aborted)throw failure('request_timeout');if(error instanceof TypeError)throw failure('network_unavailable');throw error;}}
 async function request(path,options={},policy={}){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),policy.timeout||12000);
  try{
   const response=await transport(()=>fetch(path,{...options,signal:controller.signal,cache:'no-store'}),controller);
   if(!response.ok){let data={};try{data=await transport(()=>response.json(),controller);}catch(error){/* HTTP status is authoritative even if its error body disconnects. */}
    const error=new Error(policy.errors?.[data?.error]||({401:'访问令牌无效或已失效，请重新登录。',403:'当前账号无权执行此操作。'}[response.status])||(response.status?`请求失败（${response.status}）${data?.error||''}`:'请求失败'));
    error.code=data?.error;error.status=response.status;throw error;
   }
   if(policy.raw)return response;
   const text=await transport(()=>response.text(),controller);return text?JSON.parse(text):null;
  }finally{clearTimeout(timer);}
 }
 return {request,isTransportError:error=>transportErrors.has(error)};
})();
