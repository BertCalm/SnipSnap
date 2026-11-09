// Exercise page controls against the generated manifest; this is a DOM mock, not browser playback QA.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const root=process.argv[2]||'testkit/spore-station';
const html=fs.readFileSync(`${root}/index.html`,'utf8');
const manifest=JSON.parse(fs.readFileSync(`${root}/manifest.json`,'utf8'));
class Element {
  constructor(){this.children=[];this.attributes={};this.dataset={};this.hidden=false;this.textContent='';this.currentTime=0;this.src='';this.classList={add:()=>{},remove:()=>{}};}
  append(...xs){this.children.push(...xs)}
  replaceChildren(...xs){this.children=xs}
  setAttribute(k,v){this.attributes[k]=v}
  pause(){this.paused=true}
  play(){this.paused=false;return Promise.resolve()}
}
const ids=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
const tabs=['Core','Mutations','Dry'].map(bank=>{const e=new Element();e.dataset.bank=bank;return e});
const document={getElementById:id=>{assert.ok(ids[id],id);return ids[id]},createElement:()=>new Element(),querySelectorAll:selector=>selector==='[data-bank]'?tabs:[]};
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
vm.runInNewContext(script,{document,fetch:async()=>({ok:true,json:async()=>manifest}),console});
await new Promise(resolve=>setImmediate(resolve));
assert.equal(ids.grooves.children.length,4);assert.equal(ids.pads.children.length,16);assert.equal(ids.sweeps.children.length,5);assert.equal(ids.pairs.children.length,3);assert.equal(ids.downloads.children.length,2);
ids.pads.children[0].onclick();assert.equal(ids.audio.src,'audio/core-1-matched.wav');assert.equal(ids.audio.paused,false);
ids.level.value='raw';ids.level.onchange();assert.equal(ids.audio.src,'audio/core-1.wav');assert.equal(ids.audio.paused,true);
tabs[1].onclick();assert.equal(ids.pads.children.length,16);ids.pads.children[15].onclick();assert.equal(ids.audio.src,'audio/mutation-16.wav');
tabs[2].onclick();ids.pads.children[2].onclick();assert.equal(ids.audio.src,'audio/dry-3.wav');
ids.stop.onclick();assert.equal(ids.audio.paused,true);assert.equal(ids.audio.currentTime,0);
ids.sweeps.children[0].children[1].onclick();assert.equal(ids.audio.src,'audio/sweep-body-1.wav');
ids.error.textContent='';ids.audio.play=()=>Promise.reject(Object.assign(new Error('interrupted'),{name:'AbortError'}));
ids.pads.children[0].onclick();await new Promise(resolve=>setImmediate(resolve));assert.equal(ids.error.textContent,'');
ids.audio.onerror();assert.ok(ids.error.textContent.includes('could not load'));
console.log('Page control smoke checks passed: bank switching, raw/matched selection, playback dispatch, stop, sweeps and error state. Browser audio decoding remains unverified.');
