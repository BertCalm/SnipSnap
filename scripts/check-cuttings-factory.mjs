// Simulated DOM/Web Audio check. Does not claim browser or hardware audio proof.
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import assert from 'node:assert/strict';
const root=process.argv[2],elements={},calls=[],lengths=new Map();
for(const id of ['cuttings-pocket','cuttings-night','cuttings-circuit']){
 const m=JSON.parse(fs.readFileSync(path.join(root,id,'manifest.json')));
 for(const e of m.entries)lengths.set(id+'/'+e.file,e.frames/44100);
}
function el(id){return elements[id]??={value:({kit:'cuttings-pocket',stage:'finished',dynamic:'1',take:'cycle',volume:'.75',compression:'finished'})[id]??'',checked:true,textContent:'',disabled:false,dataset:{},hidden:false,classList:{add(){},remove(){}},setAttribute(k,v){this[k]=v;},addEventListener(k,f){this[k]=f;},querySelector(k){return el(id+'/'+k);}};}
const pads=Array.from({length:20},(_,i)=>{const p=el('p'+String(i+1).padStart(2,'0'));p.dataset={voice:'p'+String(i+1).padStart(2,'0'),bankId:String(Math.floor(i/4))};p.hidden=i>=4;return p;});
const banks=Array.from({length:5},(_,i)=>{const b=el('bank'+i);b.dataset.bank=String(i);return b;});
function select(q){if(q==='[data-bank]')return banks;if(q==='[data-voice]')return pads;if(q==='.pad:not([hidden])')return pads.filter(p=>!p.hidden);if(q==='[data-voice],#groove,#sequence')return [...pads,el('groove'),el('sequence')];return [];}
class AudioContext{currentTime=0;destination={};async resume(){}async decodeAudioData(b){return {file:b.file,duration:lengths.get(b.file)??10};}createGain(){return {gain:{value:0,setTargetAtTime(v){this.value=v;}},connect(){},disconnect(){}};}createBufferSource(){return {connect(){},disconnect(){},start(at){calls.push({file:this.buffer.file,at,source:this});},stop(){this.stopped=true;}};}}
const sandbox={console,Map,Set,Promise,window:{AudioContext},setTimeout:fn=>fn(),document:{getElementById:el,addEventListener(){},querySelectorAll:select,querySelector:q=>q==='.pad:not([hidden])'?select(q)[0]:pads.find(p=>q.includes(p.dataset.voice))},fetch:async file=>({ok:fs.existsSync(path.join(root,file)),json:async()=>JSON.parse(fs.readFileSync(path.join(root,file))),arrayBuffer:async()=>({file})})};
vm.createContext(sandbox);vm.runInContext(fs.readFileSync(path.join(root,'app.js'),'utf8'),sandbox);await new Promise(r=>setImmediate(r));
assert.equal(elements['p01/b'].textContent,'Pocket');
for(let i=0;i<8;i++)await elements.p01.click();
assert.deepEqual(calls.map(c=>c.file),[0,1,2,3,0,1,2,3].map(t=>`cuttings-pocket/audio/p01_v1_r${t}_finished.wav`));
elements.kit.value='cuttings-night';await elements.kit.onchange();assert.equal(elements['p01/b'].textContent,'Trapdoor');await elements.p01.click();assert.equal(calls.at(-1).file,'cuttings-night/audio/p01_v1_r0_finished.wav');
elements.dynamic.value='2';elements.take.value='3';await elements.p02.click();assert.equal(calls.at(-1).file,'cuttings-night/audio/p02_v2_r3_finished.wav');
banks[4].click();assert.equal(pads.filter(p=>!p.hidden).length,4);await elements.p20.click();const start=calls.length;await elements.sequence.onclick();
for(let i=1;i<8;i++)assert.ok(calls[start+i].at-calls[start+i-1].at>=lengths.get(calls[start+i-1].file));
elements.kit.value='cuttings-circuit';await elements.kit.onchange();const before=calls.length;await elements.groove.onclick();
const loop=calls.slice(before);assert.equal(loop.length,3);assert.ok(loop.every(c=>c.at===loop[0].at&&c.source.loop));assert.match(elements.status.textContent,/124 BPM/);
for(const mode of ['glue','squeeze','finished']){
 elements.compression.value=mode;elements.compression.onchange();assert.equal(calls.length,before+3);
 for(const [i,key] of ['finished','glue','squeeze'].entries()){
  assert.equal(loop[i].file,`cuttings-circuit/audio/groove${key==='finished'?'':'_'+key+'_v2'}.wav`);
  assert.equal(loop[i].source.auditionGain.gain.value>0,key===mode);
 }
}
elements.stop.onclick();assert.ok(calls.every(c=>c.source.stopped));
console.log('PASS: native kit switching, labels, velocity/take selection, RR reset, five pad banks, long-phrase spacing, genre groove and Stop. Simulated browser only.');
