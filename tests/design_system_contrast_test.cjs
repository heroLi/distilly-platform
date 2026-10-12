const fs=require('node:fs'),assert=require('node:assert/strict');
const css=fs.readFileSync(process.env.DESIGN_CSS_SOURCE||'server/src/main/resources/static/design-system.css','utf8');
const studio=fs.readFileSync('server/src/main/resources/static/index.html','utf8'),experience=fs.readFileSync('server/src/main/resources/static/knowledge.html','utf8');
assert.match(studio,/<textarea\b[^>]*id="message"/);assert.match(experience,/<div\b[^>]*id="message"[^>]*role="status"/);
// Collision guard: a shared #message status selector must exclude the studio's textarea.
const messageRules=[...css.matchAll(/([^{}]+)\{([^{}]+)\}/g)].filter(m=>m[1].includes('#message'));
assert.ok(messageRules.length);for(const rule of messageRules){for(const selector of rule[1].split(',').filter(s=>s.includes('#message')))assert.match(selector,/\[role=["']?status["']?\]/,'status styles must be role-scoped');}
const color=name=>{const m=css.match(new RegExp('--'+name+':\\s*#([0-9a-f]{3,6})','i'));assert.ok(m);let h=m[1];if(h.length===3)h=[...h].map(x=>x+x).join('');return [0,2,4].map(i=>parseInt(h.slice(i,i+2),16)/255);};
function lum(channels){return channels.map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);}
const [dark,light]=[lum(color('ink')),lum(color('white'))].sort((a,b)=>a-b),ratio=(light+.05)/(dark+.05);
assert.ok(ratio>=4.5,'normal text tokens meet 4.5:1 AA minimum');assert.match(css,/textarea\s*\{[^}]*color:\s*var\(--ink\);[^}]*background:\s*var\(--white\)/s);
console.log('PASS: real textarea/status markup selector collision guard and normal text token contrast '+ratio.toFixed(2)+':1; browser cascade still requires actual computed-style verification');
