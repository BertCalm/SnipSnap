import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const root=process.argv[2];const html=fs.readFileSync(`${root}/index.html`,'utf8');const m=JSON.parse(fs.readFileSync(`${root}/manifest.json`,'utf8'));
class Element {constructor(){this.children=[];this.classList={add(){},remove(){}};this.currentTime=0;this.textContent=''}append(...x){this.children.push(...x)}pause(){this.paused=true}play(){this.paused=false;return Promise.resolve()}}
const ids=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(x=>[x[1],new Element()]));
const document={getElementById:id=>{assert.ok(ids[id],id);return ids[id]},createElement:()=>new Element(),querySelectorAll:()=>[]};
vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],{document,fetch:async()=>({ok:true,json:async()=>m})});await new Promise(setImmediate);
for(const [id,count]of Object.entries({phrase:3,kick:3,snare:3,touch:8,mechanics:4}))assert.equal(ids[id].children.length,count);
ids.phrase.children[0].onclick();assert.equal(ids.audio.src,'audio/phrase-2-matched.wav');assert.equal(ids.audio.paused,false);
ids.snare.children[0].onclick();assert.equal(ids.audio.src,'audio/new-1-matched.wav');
ids.level.value='raw';ids.level.onchange();assert.equal(ids.audio.src,'audio/new-1.wav');assert.equal(ids.audio.paused,true);
ids.mechanics.children[0].onclick();assert.equal(ids.audio.src,'audio/heads-only.wav');
ids.stop.onclick();assert.equal(ids.audio.paused,true);assert.equal(ids.audio.currentTime,0);
assert.equal(ids.download.href,'spore-synthesis-prototype.zip');ids.audio.onerror();assert.ok(ids.error.textContent.includes('could not load'));
console.log('Prototype controls passed in DOM mock; browser audio decoding remains unverified.');
