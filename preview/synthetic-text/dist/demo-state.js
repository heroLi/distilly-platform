(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.DistillyDemo=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const INTRO='我习惯在周六上午去月湾公园散步。';
  const RECALL='你记得我的散步安排吗？';
  const SUNDAY='我习惯在周日上午去月湾公园散步。';
  const clone=v=>JSON.parse(JSON.stringify(v));
  function create(){
    let visitor='A',conversation='first',people={},serial=0,epoch=0,pending=null,last=null;
    const person=()=>people[visitor]||(people[visitor]={memories:[],sources:{},chats:{first:[],again:[]}});
    const invalidate=()=>{epoch++;pending=null;};
    const find=id=>person().memories.find(m=>m.id===id);
    const current=m=>m&&m.status==='CONFIRMED'&&person().sources[m.sourceId]?.version===m.sourceVersion;
    function begin(text,retry=false){
      if(pending||typeof text!=='string'||!text.trim()||text.trim().length>1000)return null;
      text=text.trim();const t={id:++serial,epoch,visitor,conversation,text};
      if(!retry)person().chats[conversation].push({role:'user',text});
      pending=t;last={text,visitor,conversation};return clone(t);
    }
    function finish(t){
      if(!pending||!t||t.id!==pending.id||t.epoch!==epoch||t.visitor!==visitor||t.conversation!==conversation)return null;
      const text=pending.text;pending=null;let result;
      if(text===INTRO){
        if(!person().memories.some(m=>m.status!=='REVOKED'&&m.status!=='INVALIDATED')){
          const sourceId='demo-source-'+(++serial),id='demo-memory-'+(++serial);
          person().sources[sourceId]={id:sourceId,version:1,text:INTRO,conversation};
          person().memories.push({id,body:INTRO,status:'PENDING',version:1,sourceId,sourceVersion:1,edited:false});
        }
        result={kind:'candidate',text:'我把这条合成安排放进了待确认候选。你可以到“记忆”里核对来源，再决定是否确认。未确认时不会在再次交流中使用。'};
      }else if(text===RECALL){
        const records=person().memories.filter(current);
        result=records.length?{kind:'record',text:'当前浏览器中已确认的合成记忆记录写着：'+records[0].body+'\n这是预设规则读取记录的演示，不是自由问答的事实支持判定。'}:{kind:'unknown',text:'当前没有可用的已确认合成记忆。我不会猜测你的散步安排。可以先试一条合成示例，再亲自确认。'};
      }else if(text==='你好，小禾。')result={kind:'greeting',text:'你好，我是小禾，一个 AI 虚拟人物。这里可以试着聊天，也可以亲自决定哪些合成记录留下。点一下示例，走完整个记忆流程吧。'};
      else result={kind:'unknown',text:'这是离线脚本演示，没有连接真实模型。我收到了这段文字，但不会据此提取记忆或编造回答。请用下方的合成示例体验已实现的交互规则。'};
      person().chats[conversation].push({role:'assistant',text:result.text,kind:result.kind});return clone(result);
    }
    return {
      begin,finish,
      retry(){if(!last||last.visitor!==visitor||last.conversation!==conversation)return null;return begin(last.text,true);},
      cancel(){invalidate();},leave(){invalidate();last=null;},
      snapshot(){return clone({visitor,conversation,pending:!!pending,canRetry:!!last&&!pending,memories:person().memories,messages:person().chats[conversation]});},
      conversation(id){if(!['first','again'].includes(id))return false;invalidate();last=null;conversation=id;return true;},
      switchVisitor(id){if(!['A','B'].includes(id))return false;invalidate();last=null;visitor=id;conversation='first';return true;},
      source(id){const m=find(id);if(!m||m.status==='REVOKED'||m.status==='INVALIDATED')return null;return clone(person().sources[m.sourceId]||null);},
      confirm(id){const m=find(id);if(!m||m.status!=='PENDING'||person().sources[m.sourceId]?.version!==m.sourceVersion)return false;invalidate();m.status='CONFIRMED';return true;},
      correct(id){const m=find(id);if(!current(m))return false;invalidate();m.body=m.body===INTRO?SUNDAY:INTRO;m.version++;m.edited=true;return true;},
      forget(id){const m=find(id);if(!m||!['PENDING','CONFIRMED'].includes(m.status))return false;invalidate();m.status='REVOKED';m.body='';m.version++;return true;},
      changeSource(id){const m=find(id);if(!m||!person().sources[m.sourceId])return false;invalidate();const s=person().sources[m.sourceId];s.version++;s.text=SUNDAY;for(const item of person().memories)if(item.sourceId===s.id){item.status='INVALIDATED';item.body='';}return true;},
      reset(){invalidate();people={};visitor='A';conversation='first';last=null;}
    };
  }
  return {create,INTRO,RECALL};
});
