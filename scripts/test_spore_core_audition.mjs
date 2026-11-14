import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const root=process.argv[2],html=fs.readFileSync(`${root}/index.html`,'utf8'),manifest=JSON.parse(fs.readFileSync(`${root}/manifest.json`,'utf8'));
class Element{constructor(){this.children=[];this.dataset={};this.currentTime=0;this.value='matched';this.textContent=''}append(...items){this.children.push(...items)}pause(){this.paused=true}play(){this.paused=false;return Promise.resolve()}}
const ids=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(x=>[x[1],new Element()]));
const document={getElementById:id=>{assert.ok(ids[id],id);return ids[id]},createElement:()=>new Element()};
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
vm.runInNewContext(script,{document,fetch:async()=>({ok:true,json:async()=>manifest})});await new Promise(setImmediate);
for(const [id,count]of Object.entries({snare:2,kick:3,touch:6,phrase:1,native:1}))assert.equal(ids[id].children.length,count);
ids.snare.children[1].onclick();assert.equal(ids.audio.src,'audio/kotlin-snare-matched.wav');assert.equal(ids.audio.paused,false);
ids.level.value='raw';ids.level.onchange();assert.equal(ids.audio.src,'audio/kotlin-snare.wav');assert.equal(ids.audio.paused,true);
ids.touch.children[0].onclick();assert.equal(ids.audio.src,'audio/snare-soft.wav');
ids.kick.children[1].onclick();assert.equal(ids.audio.src,'audio/kick-study.wav');
ids.stop.onclick();assert.equal(ids.audio.currentTime,0);assert.equal(ids.audio.paused,true);
ids.audio.onerror();assert.match(ids.error.textContent,/could not load/);
assert.match(html,/href="spore-approved-snare.zip" download/);
vm.runInNewContext(script,{document,fetch:async()=>({ok:false})});await new Promise(setImmediate);assert.match(ids.error.textContent,/manifest could not load/);
console.log('PASS: comparison, dynamics, stems, raw/matched, stop and failures in DOM mock. Audio decoding unverified.');
