'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=fs.readFileSync('server/src/main/resources/static/app.js','utf8');
const studio=fs.readFileSync('server/src/main/resources/static/persona.js','utf8');
const context=vm.createContext({});
vm.runInContext(app.split('\n').find(line=>line.startsWith('function taskFailureReason(')),context);
vm.runInContext(studio.split('\n').find(line=>line.startsWith('const errorLabels = '))+'\nglobalThis.labels=errorLabels;',context);
for(const message of [context.taskFailureReason('MODEL_BUDGET_NOT_VERIFIED'),context.labels.MODEL_BUDGET_NOT_VERIFIED]){
 assert.match(message,/费用/);assert.match(message,/模拟/);assert.doesNotMatch(message,/API_KEY|Bearer|https?:/);
}
console.log('PASS: both actual UI adapters explain unverified text cost and retain the mock alternative without credential details');
