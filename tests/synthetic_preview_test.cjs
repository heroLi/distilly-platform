'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {create,INTRO,RECALL}=require('../preview/synthetic-text/dist/demo-state.js');
let count=0;
function test(name,fn){fn();count++;console.log('PASS',name);}
function answer(d,text){const t=d.begin(text);assert.ok(t);return d.finish(t);}
function proposed(d){answer(d,INTRO);return d.snapshot().memories[0].id;}
test('starts empty and no candidate is used without confirmation',()=>{const d=create();assert.equal(d.snapshot().memories.length,0);proposed(d);assert.equal(answer(d,RECALL).kind,'unknown');});
test('exact synthetic scenario creates only one pending candidate',()=>{const d=create();answer(d,INTRO);answer(d,INTRO);assert.equal(d.snapshot().memories.length,1);assert.equal(d.snapshot().memories[0].status,'PENDING');});
test('arbitrary text is not automatically extracted as a fact',()=>{const d=create();answer(d,'我周一去另一座公园');assert.equal(d.snapshot().memories.length,0);assert.equal(answer(d,RECALL).kind,'unknown');});
test('explicit confirmation allows a new conversation to use current record',()=>{const d=create();const id=proposed(d);assert.equal(d.confirm(id),true);d.conversation('again');assert.equal(answer(d,RECALL).kind,'record');assert.match(d.snapshot().messages.at(-1).text,/周六/);});
test('correction preserves original source and increments record version',()=>{const d=create();const id=proposed(d);d.confirm(id);const before=d.source(id);d.correct(id);assert.equal(d.snapshot().memories[0].version,2);assert.equal(d.source(id).text,before.text);assert.match(answer(d,RECALL).text,/周日/);});
test('forget excludes record but explicitly preserved historical chat remains',()=>{const d=create();const id=proposed(d);d.confirm(id);const n=d.snapshot().messages.length;d.forget(id);assert.equal(d.snapshot().messages.length,n);assert.equal(d.snapshot().memories[0].body,'');assert.equal(answer(d,RECALL).kind,'unknown');});
test('withdraw unconfirmed candidate cannot then confirm it',()=>{const d=create();const id=proposed(d);d.forget(id);assert.equal(d.confirm(id),false);assert.equal(answer(d,RECALL).kind,'unknown');});
test('source correction invalidates existing confirmation',()=>{const d=create();const id=proposed(d);d.confirm(id);d.changeSource(id);assert.equal(d.snapshot().memories[0].status,'INVALIDATED');assert.equal(answer(d,RECALL).kind,'unknown');assert.equal(d.confirm(id),false);});
test('source lookup rejects other simulated visitor and unknown ids',()=>{const d=create();const id=proposed(d);d.switchVisitor('B');assert.equal(d.source(id),null);assert.equal(d.confirm(id),false);assert.equal(d.forget(id),false);assert.equal(d.snapshot().memories.length,0);assert.equal(d.source('missing'),null);});
test('invalid simulated visitor and conversation are rejected',()=>{const d=create();assert.equal(d.switchVisitor('../../x'),false);assert.equal(d.conversation('other'),false);assert.equal(d.snapshot().visitor,'A');});
test('duplicate submit while pending adds only one user message',()=>{const d=create();const t=d.begin(INTRO);assert.ok(t);assert.equal(d.begin(INTRO),null);assert.equal(d.snapshot().messages.length,1);d.finish(t);assert.equal(d.finish(t),null);assert.equal(d.snapshot().messages.length,2);});
test('cancelled response and late completion cannot propose memory',()=>{const d=create();const t=d.begin(INTRO);d.cancel();assert.equal(d.finish(t),null);assert.equal(d.snapshot().memories.length,0);assert.equal(d.snapshot().messages.length,1);});
test('explicit retry does not duplicate original user message',()=>{const d=create();const t=d.begin(INTRO);d.cancel();const retry=d.retry();assert.ok(retry);assert.equal(d.finish(t),null);assert.ok(d.finish(retry));assert.equal(d.snapshot().messages.length,2);});
test('visitor switch and leave invalidate delayed response',()=>{for(const action of [d=>d.switchVisitor('B'),d=>d.leave(),d=>d.conversation('again')]){const d=create();const t=d.begin(INTRO);action(d);assert.equal(d.finish(t),null);assert.equal(d.snapshot().memories.length,0);}});
test('forget during response prevents stale record answer',()=>{const d=create();const id=proposed(d);d.confirm(id);const t=d.begin(RECALL);d.forget(id);assert.equal(d.finish(t),null);assert.equal(answer(d,RECALL).kind,'unknown');});
test('reset removes all visitors and invalidates old callbacks',()=>{const d=create();proposed(d);const t=d.begin(RECALL);d.reset();assert.equal(d.finish(t),null);assert.equal(d.snapshot().messages.length,0);assert.equal(d.snapshot().memories.length,0);});
test('snapshots cannot mutate authoritative browser state',()=>{const d=create();proposed(d);const s=d.snapshot();s.memories[0].status='CONFIRMED';assert.equal(answer(d,RECALL).kind,'unknown');});
test('empty or oversized input is rejected without changes',()=>{const d=create();assert.equal(d.begin(' '),null);assert.equal(d.begin('x'.repeat(1001)),null);assert.equal(d.snapshot().messages.length,0);});
console.log('PASS',count,'synthetic preview lifecycle tests');
