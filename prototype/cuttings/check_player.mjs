// Playback selection/scheduling regression in a simulated Web Audio environment.
// This is not evidence of browser audio output or MPC playback.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const manifest=JSON.parse(fs.readFileSync(process.argv[2]+'/manifest.json'));
const calls=[],elements={};
function element(id){return elements[id]??={value:({stage:'finished',dynamic:'1',take:'cycle',volume:'.75'})[id]??'',checked:true,textContent:'',disabled:true,dataset:{},classList:{add(){},remove(){}},setAttribute(name,value){this[name]=value;},addEventListener(name,fn){this[name]=fn;}};}
const groups=[['kick','snare','hat','wood'],['thump','brush','clap','rim'],['openhat','hand','shaker','metal'],['impact','cymbal','roll','riser']];
const pads=groups.flatMap((vs,i)=>vs.map(v=>{const p=element(v);p.dataset.voice=v;p.dataset.bankId=String(i);p.hidden=i!==0;return p;}));
const banks=groups.map((_,i)=>{const b=element('bank'+i);b.dataset.bank=String(i);return b;});
function select(selector){if(selector==='[data-bank]')return banks;if(selector==='[data-voice]')return pads;if(selector==='.pad:not([hidden])')return pads.filter(p=>!p.hidden);return [];}
class AudioContext{
 currentTime=0;destination={};
 async resume(){} async decodeAudioData(b){return {...b,duration:/roll|riser/.test(b.file)?3.0166:.30};}
 createGain(){return {gain:{value:0,setTargetAtTime(){}},connect(){},disconnect(){}};}
 createBufferSource(){return {connect(){},disconnect(){},start(at){calls.push({file:this.buffer.file,at,source:this});},stop(){this.stopped=true;}};}
}
const sandbox={console,Map,Set,Promise,window:{AudioContext},setTimeout:fn=>fn(),
 document:{getElementById:element,addEventListener(){},querySelector:selector=>selector==='.pad:not([hidden])'?select(selector)[0]:pads.find(p=>selector.includes(p.dataset.voice)),querySelectorAll:select},
 fetch:async file=>({ok:true,json:async()=>manifest,arrayBuffer:async()=>({file})})};
vm.createContext(sandbox);vm.runInContext(fs.readFileSync(new URL('./app.js',import.meta.url),'utf8'),sandbox);
await new Promise(r=>setImmediate(r));
for(let i=0;i<8;i++)await elements.kick.click();
assert.deepEqual(calls.map(c=>c.file),[0,1,2,3,0,1,2,3].map(r=>`audio/kick_v1_r${r}_finished.wav`));
elements.dynamic.value='2';await elements.kick.click();assert.match(calls.at(-1).file,/kick_v2_r0_finished/);
elements.stage.value='source';elements.stage.onchange();await elements.kick.click();assert.match(calls.at(-1).file,/kick_v2_r0_source/);
elements.stage.value='clean';elements.stage.onchange();await elements.kick.click();assert.match(calls.at(-1).file,/kick_v2_r0_clean/);
elements.stage.value='source';elements.stage.onchange();
elements.take.value='3';await elements.snare.click();assert.match(calls.at(-1).file,/snare_v2_r3_source/);
const before=calls.length;await elements.sequence.onclick();
assert.deepEqual(calls.slice(before).map(c=>c.file),[0,1,2,3,0,1,2,3].map(r=>`audio/snare_v2_r${r}_source.wav`));
for(let i=1;i<8;i++)assert.ok(Math.abs(calls[before+i].at-calls[before+i-1].at-.45)<1e-8);
elements.context.checked=false;await elements.groove.onclick();assert.equal(calls.at(-1).file,'audio/groove_source_no_context.wav');
banks[3].click();assert.equal(pads.filter(p=>!p.hidden).length,4);assert.equal(elements.roll.hidden,false);assert.equal(elements.kick.hidden,true);
await elements.roll.click();const phraseStart=calls.length;await elements.sequence.onclick();
for(let i=1;i<8;i++)assert.ok(calls[phraseStart+i].at-calls[phraseStart+i-1].at>=3.0166);
elements.stop.onclick();assert.ok(calls.every(c=>c.source.stopped));
console.log('PASS: velocity selection, take cycle, fixed take, comparison reset, eight-hit schedule, context-free groove, four-pad crate navigation, non-overlapping phrase cycles, stop. Simulated Web Audio only.');
