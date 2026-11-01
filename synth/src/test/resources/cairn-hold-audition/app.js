const $=s=>document.querySelector(s);
const status=$('#status');
let data, context, active, current, request=0, note=60;
const cache=new Map();
function stop(){request++;if(active){active.onended=null;try{active.stop();}catch{}active=null;}document.querySelectorAll('.playing').forEach(b=>{b.classList.remove('playing');b.setAttribute('aria-pressed','false');});current=null;status.textContent='Sound stopped.';}
$('#stop').addEventListener('click',stop);
document.addEventListener('keydown',e=>{if(e.code==='Space'&&!['BUTTON','INPUT','SELECT','SUMMARY'].includes(e.target.tagName)){e.preventDefault();stop();}});
async function buffer(path){if(!cache.has(path))cache.set(path,fetch(path).then(r=>{if(!r.ok)throw new Error(`Audio unavailable (${r.status}).`);return r.arrayBuffer();}).then(bytes=>context.decodeAudioData(bytes)));try{return await cache.get(path);}catch(e){cache.delete(path);throw e;}}
async function play(id,button){
 if(button===current){stop();return;}stop();const ticket=request;current=button;
 try{
  const clip=data?.clips.find(c=>c.id===id)||(data?.example.id===id?data.example:null);
  if(!clip)throw new Error('Recording not found.');
  context ||= new AudioContext();await context.resume();status.textContent=`Loading ${clip.voice||'the phrase'}…`;button.disabled=true;
  const audio=await buffer(clip.audio);if(ticket!==request)return;
  const source=context.createBufferSource(),gain=context.createGain();source.buffer=audio;source.loop=!!clip.held;
  const match=clip.previewGain??1;gain.gain.value=Number($('#volume').value)*match;
  source.connect(gain).connect(context.destination);source.level=gain;source.matchGain=match;
  active=source;source.onended=()=>{if(active===source){active=null;current=null;button.classList.remove('playing');button.setAttribute('aria-pressed','false');status.textContent='Ready for another listen.';}};
  source.start();if(clip.held)source.stop(context.currentTime+audio.duration*8);
  button.classList.add('playing');button.setAttribute('aria-pressed','true');
  status.textContent=clip.held?`${clip.voice} · C${Math.floor(clip.midi/12)-1} · HOLD ×8 · ${audio.duration*8} seconds`:
    clip.voice?`${clip.voice} · C${Math.floor(clip.midi/12)-1} · one launch${clip.section==='Light root'?` · model ${clip.model}`:''}`:'Dry phrase · 120 BPM · 16 seconds';
 }catch(e){if(ticket===request){status.textContent=e.message;current=null;}}finally{button.disabled=false;}
}
$('#volume').addEventListener('input',()=>{if(active)active.level.gain.setTargetAtTime(Number($('#volume').value)*active.matchGain,context.currentTime,.015);});
function button(label,id,secondary=false){const b=document.createElement('button');b.type='button';b.textContent=label;b.dataset.play=id;b.setAttribute('aria-pressed','false');if(secondary)b.className='secondary';return b;}
function edges(){const v=$('#voice').value.toLowerCase(),c=$('#control').value.toLowerCase();$('#edges').replaceChildren(button('Minimum ×8',`${v}-${c}-0`,true),button('Maximum ×8',`${v}-${c}-1`));}
$('#voice').addEventListener('change',edges);$('#control').addEventListener('change',edges);
document.querySelectorAll('[data-note]').forEach(b=>b.addEventListener('click',()=>{stop();note=Number(b.dataset.note);document.querySelectorAll('[data-note]').forEach(x=>{x.classList.toggle('selected',x===b);x.setAttribute('aria-pressed',String(x===b));});status.textContent=`C${note/12-1} selected. Choose a voice.`;}));
document.addEventListener('click',e=>{const b=e.target.closest('button[data-play],button[data-voice]');if(b)play(b.dataset.play||`${b.dataset.voice}-${b.dataset.mode}-${note}`,b);});
try{
 const response=await fetch('manifest.json');if(!response.ok)throw new Error('The recordings could not load. Please refresh.');data=await response.json();
 for(const voice of ['knock','answer']){
  const c=data.clips.find(c=>c.id===`${voice}-hold-60`),map=$(`#${voice}-map`),peak=Math.max(...c.contactEvents.map(e=>e.work));
  for(let stone=0;stone<4;stone++){const row=document.createElement('div');row.className=`contact-row ${stone?'upper':''}`;for(const event of c.contactEvents.filter(e=>e.stone===stone)){const mark=document.createElement('span');mark.style.left=`${Math.max(1,Math.min(99,event.seconds/c.seconds*100))}%`;mark.style.height=`${4+8*Math.sqrt(event.work/peak)}px`;mark.title=`${stone?'Higher':'Primary'} stone · ${event.seconds.toFixed(3)} s`;row.append(mark);}map.append(row);}
  $(`#${voice}-stat`).textContent=`Defaults: ${c.primaryContacts} direct · ${c.higherContacts} higher contacts per ${c.seconds}-second cycle`;
 }
 for(const [label,midi]of[['C3',48],['C4',60],['C5',72]]){const row=document.createElement('article');row.className='comparison';const title=document.createElement('div');const h=document.createElement('h3');h.textContent=`ANSWER / ${label}`;const p=document.createElement('p');p.textContent='WEIGHT 0 · shared middle controls';title.append(h,p);const actions=document.createElement('div');actions.className='actions';actions.append(button('Previous',`answer-light-${midi}-model1`,true),button('Clearer root',`answer-light-${midi}-model2`));row.append(title,actions);$('#root-comparisons').append(row);}
 edges();
 for(const voice of ['knock','answer']){const row=document.createElement('div');row.className='extra';const h=document.createElement('h3');h.textContent=`${voice.toUpperCase()} / BODY × FRICTION`;const actions=document.createElement('div');actions.className='actions';for(const body of [25,75])for(const friction of [25,75])actions.append(button(`${body}% / ${friction}%`,`${voice}-pair-${body}-${friction}`,true));row.append(h,actions);$('#extra-list').append(row);
 const soft=document.createElement('div');soft.className='extra';const title=document.createElement('h3');title.textContent=`${voice.toUpperCase()} / velocity`;const buttons=document.createElement('div');buttons.className='actions';for(const v of [35,65])buttons.append(button(`${v}% ×8`,`${voice}-soft-${v}`,true));buttons.append(button('100% ×8',`${voice}-hold-60`,true));soft.append(title,buttons);$('#extra-list').append(soft);}
 const held=data.clips.filter(c=>c.held),maxSeam=Math.max(...held.map(c=>c.seamError||0));
 for(const[value,label]of[['2 / 4 sec','KNOCK / ANSWER cradle cycles'],[String(held.length),'continuing renders checked'],[(maxSeam<1e-9?'＜ 1e−9':maxSeam.toExponential(1)),'largest measured seam error; limit 1e−3']]){const div=document.createElement('div'),strong=document.createElement('strong'),span=document.createElement('span');strong.textContent=value;span.textContent=label;div.append(strong,span);$('#evidence').append(div);}
 const p=document.createElement('p');p.textContent=`The first ANSWER HOLD render took ${data.coldFirstAnswerHoldMs.toFixed(1)} ms in a fresh server JVM. The 50 ms target still needs phone measurements.`;$('#measurements').append(p);
 for(const voice of ['KNOCK','ANSWER']){const values=data.benchmark.filter(b=>b.voice===voice&&b.held).map(b=>b.uncachedMs).sort((a,b)=>a-b),p=document.createElement('p');const n=values.length,median=(values[(n-1)>>1]+values[n>>1])/2;p.textContent=`${voice}: warmed render with new controls, median ${median.toFixed(1)} ms across ${n} trials. Includes motion, a continuing acoustic cycle and a real preceding segment and output conditioning.`;$('#measurements').append(p);}
 const r=document.createElement('p');r.textContent='The low-WEIGHT comparison changes root participation, not modal frequency. All six default one-shots match the previous version sample for sample. Saved model-1 recipes retain their original rendering.';$('#measurements').append(r);
}catch(e){status.textContent=e.message;document.querySelectorAll('button[data-play],button[data-voice]').forEach(b=>b.disabled=true);}
