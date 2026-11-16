(function(){
  'use strict';
  const $=id=>document.getElementById(id),demo=DistillyDemo.create();
  let timer=null,sourceId=null,retryVisible=false;
  const words={PENDING:'待确认',CONFIRMED:'已确认',REVOKED:'已撤回 / 遗忘',INVALIDATED:'来源已更正 · 已失效'};
  function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
  function stopTimer(){if(timer!==null)clearTimeout(timer);timer=null;}
  function closeSource(){if($('source-dialog').open)$('source-dialog').close();sourceId=null;$('source-body').textContent='';$('source-meta').textContent='';}
  function status(text){$('status').textContent=text;}
  function render(){
    const s=demo.snapshot();$('visitor').value=s.visitor;$('conversation').value=s.conversation;
    $('send').disabled=s.pending;for(const id of ['greeting','intro','recall'])$(id).disabled=s.pending;
    $('stop').hidden=!s.pending;$('retry').hidden=!retryVisible||s.pending||!s.canRetry;
    $('messages').replaceChildren();
    if(!s.messages.length){const empty=el('div',undefined,'empty');empty.append(el('strong','你好，我是小禾。'),el('p','先聊一句，再由你决定留下什么。'));$('messages').append(empty);}
    for(const m of s.messages){const b=el('article',undefined,'bubble '+m.role);b.append(el('small',m.role==='user'?'你 · 合成试用':'小禾 · 预设脚本'),el('div',m.text));$('messages').append(b);}
    $('messages').scrollTop=$('messages').scrollHeight;
    $('memory-count').textContent=String(s.memories.filter(m=>m.status==='PENDING'||m.status==='CONFIRMED').length);
    $('memories').replaceChildren();
    if(!s.memories.length)$('memories').append(el('div','还没有记忆。发送散步示例后，会出现一条待确认候选。','empty'));
    for(const m of s.memories){
      const card=el('article',undefined,'memory-card');card.dataset.memoryId=m.id;card.append(el('span',words[m.status],'badge'),el('span','记录 v'+m.version,'meta'));
      card.append(el('p',m.body||(m.status==='INVALIDATED'?'来源版本已变化，原确认不再可用。':'这条记忆已停用，正文已从记忆卡清除。原聊天仍保留。')));
      if(m.edited&&m.body)card.append(el('p','这条记录由你在演示中纠正，原来源文本未被改写。','meta'));
      if(m.body){card.append(el('p','来源 '+m.sourceId+' · 来源 v'+m.sourceVersion+' · 支持关系未判定','meta'));const actions=el('div',undefined,'actions');
        function button(label,action){const b=el('button',label);b.type='button';b.dataset.action=action;b.onclick=()=>operate(action,m.id);actions.append(b);}
        button('查看来源','source');if(m.status==='PENDING')button('确认留下','confirm');if(m.status==='CONFIRMED')button('纠错：切换周六 / 周日','correct');button(m.status==='PENDING'?'撤回候选':'遗忘这条','forget');card.append(actions);
      }
      $('memories').append(card);
    }
  }
  function operate(action,id){
    if(action==='source'){const s=demo.source(id);if(!s){status('来源已不可用，请重新核对。');return;}closeSource();sourceId=id;$('source-meta').textContent=s.id+' · v'+s.version+' · '+(s.conversation==='first'?'初次交流':'再次交流');$('source-body').textContent=s.text;$('source-dialog').showModal();return;}
    stopTimer();retryVisible=false;const ok=action==='confirm'?demo.confirm(id):action==='correct'?demo.correct(id):demo.forget(id);
    status(ok?({confirm:'已确认。切到“再次交流”，再询问散步安排。',correct:'已纠正记忆记录；原来源保留。下一次脚本回应读取当前版本。',forget:'这条记忆已停用。原聊天仍保留；再次询问会显示没有可用记忆。'}[action]):'操作未生效，记录可能已变化。');render();
  }
  function run(text,retry=false){
    const ticket=retry?demo.retry():demo.begin(text);if(!ticket){status('请填写 1–1000 字，或等待当前回应结束。');return;}
    retryVisible=false;$('message').value='';status('正在生成预设脚本回应…');render();
    timer=setTimeout(()=>{timer=null;const result=demo.finish(ticket);if(!result)return;status('脚本回应完成 · 未调用真实模型。');render();},420);
  }
  $('chat-form').onsubmit=e=>{e.preventDefault();run($('message').value);};
  $('greeting').onclick=()=>run('你好，小禾。');$('intro').onclick=()=>run(DistillyDemo.INTRO);$('recall').onclick=()=>run(DistillyDemo.RECALL);
  $('stop').onclick=()=>{stopTimer();demo.cancel();retryVisible=true;status('已停止。你可以显式重试，原发送不会重复添加。');render();};$('retry').onclick=()=>run('',true);
  function switchScope(fn){stopTimer();closeSource();retryVisible=false;$('message').value='';fn();status('已切换场景，旧在途回应已取消。这里只保留本页内存中的合成记录。');render();}
  $('visitor').onchange=()=>switchScope(()=>demo.switchVisitor($('visitor').value));$('conversation').onchange=()=>switchScope(()=>demo.conversation($('conversation').value));
  function tab(memory){$('experience').classList.toggle('show-memory',memory);$('chat-tab').setAttribute('aria-pressed',String(!memory));$('memory-tab').setAttribute('aria-pressed',String(memory));}
  $('chat-tab').onclick=()=>tab(false);$('memory-tab').onclick=()=>tab(true);
  function reset(){stopTimer();closeSource();demo.reset();retryVisible=false;$('message').value='';tab(false);status('演示已重置，记录和输入已清空。');render();}
  $('reset').onclick=reset;$('exit').onclick=()=>{reset();$('experience').hidden=true;$('left').hidden=false;$('restart').focus();};$('restart').onclick=()=>{$('left').hidden=true;$('experience').hidden=false;reset();$('greeting').focus();};
  $('close-source').onclick=closeSource;$('source-dialog').addEventListener('close',()=>{sourceId=null;$('source-body').textContent='';$('source-meta').textContent='';});
  $('change-source').onclick=()=>{const id=sourceId;stopTimer();demo.changeSource(id);closeSource();retryVisible=false;status('合成来源已更正为新版本；旧确认已失效，不能继续用于脚本回应。');render();};
  addEventListener('pagehide',()=>{stopTimer();demo.leave();closeSource();$('message').value='';render();});
  render();
})();
