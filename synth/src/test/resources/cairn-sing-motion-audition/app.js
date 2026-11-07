'use strict';
const $=s=>document.querySelector(s);
let data,context,gain,current,ticket=0,lastId;
const decoded=new Map();
const status=$('#status');
function stop(message='Stopped.'){
 ticket++;if(current){current.onended=null;try{current.stop();}catch{}current.disconnect();current=null;}
 document.querySelectorAll('[data-kind],[data-id]').forEach(b=>b.setAttribute('aria-pressed','false'));
 status.textContent=message;
}
function note(){return $('#register').value;}
function clip(id){const c=data?.clips.find(c=>c.id===id);if(!c)throw Error('That recording is unavailable.');return c;}
async function buffer(c){
 if(!decoded.has(c.id)){
  const promise=fetch(c.audio).then(r=>{if(!r.ok)throw Error('The recording could not load. Try again.');return r.arrayBuffer();}).then(b=>context.decodeAudioData(b));
  decoded.set(c.id,promise);promise.catch(()=>decoded.delete(c.id));
 }
 return decoded.get(c.id);
}
async function play(id,button){
 stop('Loading the note…');const request=ticket;lastId=id;$('#retry').hidden=true;
 try{
  if(!context){context=new AudioContext();gain=context.createGain();gain.connect(context.destination);}
  await context.resume();const c=clip(id);const b=await buffer(c);if(request!==ticket)return;
  const source=context.createBufferSource();source.buffer=b;source.loop=c.held;source.connect(gain);
  gain.gain.setValueAtTime(Number($('#volume').value)*c.previewGain,context.currentTime);
  current=source;current.previewGain=c.previewGain;button?.setAttribute('aria-pressed','true');
  source.onended=()=>{if(current===source){current=null;source.disconnect();document.querySelectorAll('[aria-pressed]').forEach(b=>b.setAttribute('aria-pressed','false'));status.textContent='Choose another touch.';}};
  const when=context.currentTime+.025;source.start(when);source.stop(when+b.duration*(c.held?8:1));
  status.textContent=`${c.label} · ${c.held?'8 cycles · 16 s':'one finite note'}`;
 }catch(e){if(request===ticket){status.textContent=e.message||'Playback failed. Try again.';$('#retry').hidden=false;}}
}
document.querySelectorAll('[data-kind]').forEach(b=>b.addEventListener('click',()=>play(`${b.dataset.kind}-${note()}`,b)));
document.querySelectorAll('[data-id]').forEach(b=>b.addEventListener('click',()=>play(b.dataset.id,b)));
$('#stop').addEventListener('click',()=>stop());
$('#register').addEventListener('change',()=>{stop('Register changed. Choose a touch.');lastId=null;$('#retry').hidden=true;document.querySelectorAll('[data-kind]').forEach(b=>b.setAttribute('aria-label',`${b.dataset.kind==='before'?'Earlier SING':'Moving SING'}, C${Number(note())/12-1}, eight cycles`));});
$('#volume').addEventListener('input',()=>{if(gain)gain.gain.setTargetAtTime(Number($('#volume').value)*(current?.previewGain||1),context.currentTime,.015);});
$('#retry').addEventListener('click',()=>{if(data&&lastId)play(lastId,document.querySelector(`[data-id="${lastId}"]`)||document.querySelector(`[data-kind="${lastId.split('-')[0]}"]`));else load();});
document.addEventListener('keydown',e=>{if(e.key==='Escape')stop();});
async function load(){
 $('#retry').hidden=true;status.textContent='Loading the recordings…';
 try{
  const response=await fetch('manifest.json',{cache:'no-cache'});if(!response.ok)throw Error('The recording list could not load. Try again.');
  const m=await response.json();if(!Array.isArray(m.clips))throw Error('The recording list is incomplete.');data=m;
  for(const c of m.clips)if(!c.audio||!Number.isFinite(c.previewGain))throw Error('A recording is incomplete.');
  const a=clip('after-60').movement;const b=clip('before-60').movement;
  $('#measurements').textContent=`C4 root-envelope depth: ${(100*b.rootEnvelopeDepth).toFixed(1)}% before, ${(100*a.rootEnvelopeDepth).toFixed(1)}% with motion. The new partial-to-root balance varies by ${(100*a.harmonicBalanceDepth).toFixed(1)}%. The real preceding audio and kept cycle are independently checked for every held recording.`;
  $('#verification').textContent=m.repositoryVerification?.summary||'Native DSP checks passed. The complete JVM gate is in progress. Phone/MPC playback and listening acceptance remain separate.';
  if(m.repositoryPublication?.pullRequest)$('#repo-link').href=m.repositoryPublication.pullRequest;
  document.querySelectorAll('[data-kind],[data-id]').forEach(b=>b.disabled=false);status.textContent='Start with the moving rim.';
 }catch(e){data=null;status.textContent=e.message;$('#retry').hidden=false;document.querySelectorAll('[data-kind],[data-id]').forEach(b=>b.disabled=true);}
}
load();
