'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('server/src/main/resources/static/persona.js','utf8'),c=vm.createContext({});
vm.runInContext(source.slice(source.indexOf('function contextNotice('),source.indexOf('function renderSession(')),c);
const scope={history_window_limit:24,history_outside_window:8,omitted_history:3,omitted_memories:2,omitted_experiences:1,omitted_knowledge:4};
let text=c.contextNotice({context_scope:scope});assert.match(text,/省略了部分上下文/);assert.match(text,/之外有 8 条/);assert.match(text,/历史 3 条/);assert.match(text,/已确认记忆 2 条/);assert.match(text,/背景摘录 1 条/);assert.match(text,/工作资料 4 条/);assert.match(text,/生成时范围/);assert.match(text,/未命中或检索阶段未选入/);
for(const bad of [undefined,null,{}, {...scope,omitted_history:-1},{...scope,omitted_memories:1.5},{...scope,history_outside_window:'<script>'},{...scope,omitted_knowledge:Number.MAX_SAFE_INTEGER+1}]){
 text=c.contextNotice({context_scope:bad});assert.match(text,/范围未记录/);assert.doesNotMatch(text,/历史 0 条|<script>/);
}
text=c.contextNotice({context_scope:{...scope,history_outside_window:undefined}});assert.match(text,/更早历史消息未计数/);assert.doesNotMatch(text,/之外有 0 条/);
const zero=Object.fromEntries(Object.keys(scope).map(k=>[k,k==='history_window_limit'?24:0]));text=c.contextNotice({context_scope:zero});assert.doesNotMatch(text,/省略了部分/);assert.match(text,/最多选取最近24条/);
text=c.contextNotice({context_scope:{...scope,body:'private marker',lease_token:'private marker'}});assert.doesNotMatch(text,/private marker|lease_token/);
console.log('PASS: 11 per-reply context notice cases; exact counts, bounded meaning, unknown legacy, invalid metadata and no extra-field disclosure');
