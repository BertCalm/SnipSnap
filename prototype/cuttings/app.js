'use strict';
const $=id=>document.getElementById(id);
let manifest, ctx, master, lastVoice='kick', generation=0, currentBank=0;
const cache=new Map(), active=new Set(), counts=new Map();
const status=text=>$('status').textContent=text;
function stop(){generation++;for(const s of active){try{s.stop();}catch{}}active.clear();status('Stopped.');}
async function ready(){
  if(!ctx){ctx=new (window.AudioContext||window.webkitAudioContext)();master=ctx.createGain();master.gain.value=+$('volume').value;master.connect(ctx.destination);}
  await ctx.resume();
}
async function buffer(file){
  if(!cache.has(file))cache.set(file,fetch(file).then(r=>{if(!r.ok)throw Error('Audio could not load');return r.arrayBuffer();}).then(b=>ctx.decodeAudioData(b)).catch(e=>{cache.delete(file);throw e;}));
  return cache.get(file);
}
function play(buf,gain,at=ctx.currentTime){
  const s=ctx.createBufferSource(), g=ctx.createGain();s.buffer=buf;g.gain.value=gain;s.connect(g);g.connect(master);active.add(s);s.onended=()=>{active.delete(s);s.disconnect();g.disconnect();};s.start(at);return s;
}
function entry(voice,d,t,stage){return manifest.entries.find(e=>e.voice===voice&&e.dynamic===d&&e.take===t&&e.stage===stage);}
function flash(voice){const p=document.querySelector(`[data-voice="${voice}"]`);p.classList.add('active');setTimeout(()=>p.classList.remove('active'),130);}
async function hit(voice){
  const ticket=generation;const d=+$('dynamic').value,stage=$('stage').value,choice=$('take').value;
  const key=voice+':'+d,t=choice==='cycle'?(counts.get(key)||0)%4:+choice;
  if(choice==='cycle')counts.set(key,t+1);
  lastVoice=voice;await ready();const b=await buffer(entry(voice,d,t,stage).file);if(ticket!==generation)return;
  play(b,manifest.comparisonGain[voice][stage]);flash(voice);status(`${manifest.names[voice]} · ${['soft','medium','hard'][d]} · take ${t+1} · ${stage}`);
}
async function sequence(){
  stop();const ticket=generation;const voice=lastVoice,d=+$('dynamic').value,stage=$('stage').value;
  status('Loading two take cycles…');await ready();const bs=await Promise.all([0,1,2,3].map(t=>buffer(entry(voice,d,t,stage).file)));if(ticket!==generation)return;
  const start=ctx.currentTime+.06;
  const spacing=Math.max(.45,...bs.map(b=>b.duration+.08));
  for(let i=0;i<8;i++)play(bs[i%4],manifest.comparisonGain[voice][stage],start+i*spacing);
  status(`${manifest.names[voice]} · ${['soft','medium','hard'][d]} · 1 2 3 4 / 1 2 3 4`);
}
async function groove(){
  stop();const ticket=generation,stage=$('stage').value;status('Loading groove…');await ready();const g=manifest.grooves[stage+($('context').checked?'':'_no_context')],b=await buffer(g.file);if(ticket!==generation)return;
  const s=play(b,g.gain);const cleanup=s.onended;s.onended=()=>{cleanup();if(generation===ticket)status('Groove ended. Tap a pad to inspect the cut.');};
  status(`Four-bar groove · 89 BPM · ${stage} · authored dynamics / cycling takes`);
}
function attempt(fn){return ()=>Promise.resolve().then(fn).catch(e=>status('Playback failed: '+e.message));}
document.querySelectorAll('[data-voice]').forEach(p=>p.addEventListener('click',attempt(()=>hit(p.dataset.voice))));
$('groove').onclick=attempt(groove);$('sequence').onclick=attempt(sequence);$('stop').onclick=stop;
$('volume').oninput=()=>{if(master)master.gain.setTargetAtTime(+$('volume').value,ctx.currentTime,.01);};
// Clear cycles when changing stages, so the first A/B hit uses the same take.
$('context').onchange=()=>{stop();status('Groove traces changed. Press Play groove.');};
$('stage').onchange=()=>{stop();counts.clear();status('Comparison changed. Tap a pad or play the groove.');};
document.querySelectorAll('[data-bank]').forEach(button=>button.addEventListener('click',()=>{
 stop();currentBank=+button.dataset.bank;
 document.querySelectorAll('[data-bank]').forEach(b=>b.setAttribute('aria-pressed',String(+b.dataset.bank===currentBank)));
 document.querySelectorAll('[data-voice]').forEach(p=>p.hidden=+p.dataset.bankId!==currentBank);
 lastVoice=document.querySelector('.pad:not([hidden])').dataset.voice;status('Crate selected. Tap a find.');
}));
document.addEventListener('keydown',e=>{if(e.key==='Escape')stop();if(e.repeat||/SELECT|INPUT|BUTTON/.test(e.target.tagName)||!manifest)return;const v=[...document.querySelectorAll('.pad:not([hidden])')][+e.key-1]?.dataset.voice;if(v)attempt(()=>hit(v))();});
fetch('manifest.json').then(r=>{if(!r.ok)throw Error('Manifest could not load');return r.json();}).then(m=>{
 manifest=m;document.querySelectorAll('button[disabled]').forEach(b=>b.disabled=false);status('Ready. Start with the groove, or tap a find.');
}).catch(e=>status(e.message+' · Please reload.'));
