'use strict';
const $=id=>document.getElementById(id);let manifest,kit,ctx,master,bank=0,last='kick',generation=0;
const counts=new Map(),cache=new Map(),active=new Set();
const status=s=>$('status').textContent=s;
function stop(){generation++;for(const s of active){try{s.stop()}catch{}}active.clear();status('Stopped.');}
async function ready(){if(!ctx){ctx=new(window.AudioContext||window.webkitAudioContext)();master=ctx.createGain();master.gain.value=+$('volume').value;master.connect(ctx.destination);}await ctx.resume();}
async function buffer(file){if(!cache.has(file))cache.set(file,fetch(file).then(r=>{if(!r.ok)throw Error('Audio could not load');return r.arrayBuffer()}).then(b=>ctx.decodeAudioData(b)).catch(e=>{cache.delete(file);throw e}));return cache.get(file);}
function play(b,voice,at=ctx.currentTime,gain=1){
 if(voice==='hat')for(const s of active)if(s.voice==='openhat'){const g=s.level.gain;g.cancelScheduledValues(at);g.setTargetAtTime(0,at,.003);s.stop(at+.02);}
 const s=ctx.createBufferSource(),level=ctx.createGain();s.buffer=b;s.voice=voice;s.level=level;level.gain.value=gain;s.connect(level);level.connect(master);active.add(s);
 s.onended=()=>{active.delete(s);s.disconnect();level.disconnect();};s.start(at);return s;
}
const entry=(voice,d,t)=>kit.entries.find(e=>e.voice===voice&&e.dynamic===d&&e.take===t);
async function hit(voice){const ticket=generation,d=+$('dynamic').value,key=voice+':'+d,choice=$('take').value,t=choice==='cycle'?(counts.get(key)||0)%4:+choice;counts.set(key,t+1);last=voice;
 const file=entry(voice,d,t).file;await ready();const b=await buffer(file);if(ticket!==generation)return;play(b,voice);status(`${manifest.voices[voice]} · ${['soft','medium','hard'][d]} · take ${t+1}`);
}
async function groove(){stop();const ticket=generation,file=kit.groove,title=kit.name,bpm=kit.bpm;status('Loading groove…');await ready();const b=await buffer(file);if(ticket!==generation)return;const s=play(b,'groove');const cleanup=s.onended;s.onended=()=>{cleanup();if(ticket===generation)status('Groove ended. Tap a pad to inspect it.');};status(`${title} · four bars · ${bpm} BPM`);}
async function sequence(){stop();const ticket=generation,d=+$('dynamic').value,voice=last,files=[0,1,2,3].map(t=>entry(voice,d,t).file);status('Loading take comparison…');await ready();const bs=await Promise.all(files.map(buffer));if(ticket!==generation)return;const spacing=Math.max(.45,...bs.map(b=>b.duration+.07)),at=ctx.currentTime+.06;for(let i=0;i<8;i++)play(bs[i%4],voice,at+i*spacing);status(`${manifest.voices[voice]} · takes 1 2 3 4 / 1 2 3 4`);}
const attempt=fn=>()=>Promise.resolve().then(fn).catch(e=>status(e.message+' · Try again.'));
function showBank(){document.querySelectorAll('[data-bank]').forEach(b=>b.setAttribute('aria-pressed',String(+b.dataset.bank===bank)));document.querySelectorAll('[data-voice]').forEach(p=>p.hidden=+p.dataset.bankId!==bank);last=Object.keys(manifest.voices)[bank*4];}
function selectKit(){stop();counts.clear();cache.clear();kit=manifest.kits.find(k=>k.id===$('kit').value);$('description').textContent=kit.description;$('reference').textContent='Reference: '+kit.reference+' · study tempo '+kit.bpm+' BPM';$('download').href=kit.id+'.zip';showBank();status(kit.name+' · ready.');}
$('groove').onclick=attempt(groove);$('sequence').onclick=attempt(sequence);$('stop').onclick=stop;$('kit').onchange=selectKit;
$('volume').oninput=()=>{if(master)master.gain.setTargetAtTime(+$('volume').value,ctx.currentTime,.01);};
$('dynamic').onchange=()=>{stop();counts.clear();};$('take').onchange=()=>{stop();counts.clear();};
document.querySelectorAll('[data-bank]').forEach(b=>b.onclick=()=>{if(!manifest)return;stop();bank=+b.dataset.bank;showBank();});
document.addEventListener('keydown',e=>{if(e.key==='Escape')stop();if(e.repeat||/SELECT|INPUT|BUTTON/.test(e.target.tagName)||!manifest)return;const voice=Object.keys(manifest.voices)[bank*4+(+e.key-1)];if(/^[1-4]$/.test(e.key)&&voice)attempt(()=>hit(voice))();});
fetch('manifest.json').then(r=>{if(!r.ok)throw Error('Study could not load');return r.json()}).then(m=>{
 manifest=m;m.kits.forEach(k=>{const o=document.createElement('option');o.value=k.id;o.textContent=k.name+' · '+k.reference;$('kit').append(o);});
 Object.entries(m.voices).forEach(([voice,name],i)=>{const b=document.createElement('button');b.className='pad';b.dataset.voice=voice;b.dataset.bankId=String(Math.floor(i/4));b.innerHTML=`<small>${String(i+1).padStart(2,'0')}</small><b>${name}</b><small>${i<8?'Three strikes · four takes':'Authored finite phrase'}</small>`;b.onclick=attempt(()=>hit(voice));$('pads').append(b);});
 $('kit').value=m.kits[0].id;['kit','groove','sequence'].forEach(id=>$(id).disabled=false);selectKit();
}).catch(e=>status(e.message+' · Reload to retry.'));
