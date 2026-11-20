import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import assert from 'node:assert/strict';
const root=process.argv[2],source=process.argv[3]||path.dirname(new URL(import.meta.url).pathname),nodes={},pads=[],banks=[],calls=[];
function el(id){return nodes[id]??={value:({dynamic:'2',take:'cycle',volume:'.65'})[id]||'',dataset:{},disabled:true,hidden:false,textContent:'',setAttribute(k,v){this[k]=v},append(x){if(id==='pads')pads.push(x)},addEventListener(){}};}
for(let i=0;i<3;i++)banks.push({...el('bank'+i),dataset:{bank:String(i)}});
const durations=new Map();const m=JSON.parse(fs.readFileSync(path.join(root,'manifest.json')));for(const k of m.kits)for(const e of k.entries)durations.set(e.file,e.frames/44100);
class Context{currentTime=0;destination={};async resume(){}async decodeAudioData(b){return {file:b.file,duration:durations.get(b.file)||10}}createGain(){return {gain:{value:0,setTargetAtTime(v){this.value=v},cancelScheduledValues(){}},connect(){},disconnect(){}}}createBufferSource(){return {connect(){},disconnect(){},start(at){calls.push({file:this.buffer.file,at,source:this})},stop(at){this.stopped=true;this.stopAt=at}}}}
const sandbox={console,Map,Set,Promise,window:{AudioContext:Context},document:{getElementById:el,createElement:()=>({dataset:{},hidden:false}),querySelectorAll:q=>q==='[data-bank]'?banks:pads,addEventListener(){}},fetch:async file=>({ok:fs.existsSync(path.join(root,file)),json:async()=>JSON.parse(fs.readFileSync(path.join(root,file))),arrayBuffer:async()=>({file})})};
vm.createContext(sandbox);vm.runInContext(fs.readFileSync(path.join(source,'app.js'),'utf8'),sandbox);await new Promise(r=>setImmediate(r));
assert.equal(pads.length,10);assert.equal(pads.filter(p=>!p.hidden).length,4);
for(let i=0;i<8;i++)await pads[0].onclick();assert.deepEqual(calls.map(c=>c.file),[0,1,2,3,0,1,2,3].map(t=>`open-road/audio/kick-v2-r${t}.wav`));
el('dynamic').value='0';el('dynamic').onchange();await pads[1].onclick();assert.equal(calls.at(-1).file,'open-road/audio/snare-v0-r0.wav');
await pads[3].onclick();const open=calls.at(-1).source;await pads[2].onclick();assert.equal(open.stopped,true);
el('kit').value='angular';el('kit').onchange();await pads[0].onclick();assert.equal(calls.at(-1).file,'angular/audio/kick-v0-r0.wav');assert.equal(el('download').href,'angular.zip');
banks[2].onclick();assert.equal(pads.filter(p=>!p.hidden).length,2);await pads[9].onclick();const before=calls.length;await el('sequence').onclick();for(let i=1;i<8;i++)assert.ok(calls[before+i].at-calls[before+i-1].at>=durations.get(calls[before+i-1].file));
await el('groove').onclick();assert.equal(calls.at(-1).file,'angular/groove.wav');el('stop').onclick();assert.ok(calls.every(c=>c.source.stopped));
console.log('PASS: kit selection, bank visibility, all four cyclic takes, velocity routing, open-hat choke, finite phrase spacing, groove and Stop. Simulated DOM only.');
