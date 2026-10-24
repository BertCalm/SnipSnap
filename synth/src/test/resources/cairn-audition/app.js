const $ = s => document.querySelector(s);
const status = $('#status');
let context, active, current, playRequest = 0;
const buffers = new Map();
const data = await fetch('manifest.json').then(r => {if(!r.ok) throw new Error('Measurements failed to load.');return r.json();}).catch(e => {status.textContent=e.message; throw e;});
const clips = new Map(data.clips.map(c => [c.id,c]));
const references = data.references || [];
function stop(){playRequest++;if(active){active.onended=null;try{active.stop();}catch{}active=null;}document.querySelectorAll('.playing').forEach(e=>{e.classList.remove('playing');e.setAttribute('aria-pressed','false');});current=null;status.textContent='Sound stopped.';}
$('#stop').addEventListener('click',stop);
document.addEventListener('keydown',e=>{if(e.code==='Space' && !['INPUT','SELECT','BUTTON','SUMMARY'].includes(e.target.tagName)){e.preventDefault();stop();}});
async function getBuffer(url){if(!buffers.has(url))buffers.set(url,fetch(url).then(r=>{if(!r.ok)throw new Error(`Audio unavailable (${r.status}).`);return r.arrayBuffer();}).then(b=>context.decodeAudioData(b)));try{return await buffers.get(url);}catch(e){buffers.delete(url);throw e;}}
async function play(id,button){
 if(current===button){stop();return;}stop();const ticket=playRequest;current=button;
 try{
  context ||= new AudioContext();await context.resume();
  const c=clips.get(id) || references.find(r=>r.id===id);if(!c)throw new Error('Recording not found.');
  const full=$('#launch').checked;const path=full && c.full ? c.full : c.audio;
  status.textContent=`Loading ${c.voice || c.id}…`;button.disabled=true;
  const buffer=await getBuffer(path);if(ticket!==playRequest)return;
  const source=context.createBufferSource();const gain=context.createGain();source.buffer=buffer;
  gain.gain.value=Number($('#volume').value)*(c.previewGain ?? 1);source.connect(gain).connect(context.destination);
  source.onended=()=>{if(active===source){active=null;current=null;button.classList.remove('playing');button.setAttribute('aria-pressed','false');status.textContent='Ready for another listen.';}};
  active=source;active.level=gain;active.matchGain=c.previewGain ?? 1;
  const offset=full ? 0 : c.reference ? Math.max(0,(c.firstContactMs-2)/1000) : c.outputStartMs/1000;
  source.start(0,offset);button.classList.add('playing');button.setAttribute('aria-pressed','true');
  status.textContent=`${c.voice || c.id} · ${c.reference?'earlier recording':c.section} · ${full?'full launch':'first contact'}`;
 }catch(e){if(ticket===playRequest){status.textContent=e.message;current=null;}}finally{button.disabled=false;}
}
$('#volume').addEventListener('input',()=>{if(active)active.level.gain.setTargetAtTime(Number($('#volume').value)*active.matchGain,context.currentTime,.015);});
function button(label,id,secondary=false){const b=document.createElement('button');b.type='button';b.textContent=label;b.dataset.play=id;b.setAttribute('aria-pressed','false');if(secondary)b.className='secondary';return b;}
for(const voice of ['knock','answer']){
 const notes=document.querySelector(`[data-voice="${voice}"]`);for(const [note,midi] of [['C3',48],['C4',60],['C5',72]])notes.append(button(note,`${voice}-default-${midi}`));
 const c=clips.get(`${voice}-default-60`);$(`#${voice}-stat`).textContent=`Defaults: ${c.primaryContacts} direct · ${c.higherContacts} higher contacts · ${c.seconds.toFixed(2)} seconds`;
 const map=$(`#${voice}-map`);for(let stone=0;stone<4;stone++){const row=document.createElement('div');row.className=`contact-row ${stone?'upper':''}`;for(const event of c.contactEvents || [])if(event.stone===stone){const bar=document.createElement('span');bar.style.left=`${Math.min(99,event.seconds/c.fullSeconds*100)}%`;bar.title=`Stone ${stone+1} at ${event.seconds.toFixed(3)} s`;row.append(bar);}map.append(row);}
}
for(const voice of ['KNOCK','ANSWER'])for(const middle of [false,true]){
 const row=document.createElement('article');row.className='reference';const label=document.createElement('div');const h=document.createElement('h3');h.textContent=`${voice} · ${middle?'same middle settings':'own defaults'}`;const p=document.createElement('p');p.textContent=middle?'A test of the usable control range.':'Your earlier reference beside the new model.';label.append(h,p);const actions=document.createElement('div');actions.className='buttons';actions.append(button('Earlier recording',`reference-${voice.toLowerCase()}-${middle?'middle':'default'}`,true),button('Reconstruction',`${voice.toLowerCase()}-${middle?'middle':'default-60'}`));row.append(label,actions);$('#reference-list').append(row);
}
function controls(){const v=$('#voice').value.toLowerCase(), key=$('#control').value;$('#steps').replaceChildren();for(let i=0;i<5;i++){$('#steps').append(button(key==='HOLD'&&i===4?'95%':`${i*25}%`,`${v}-${key.toLowerCase()}-${i}`));}$('#control-detail').textContent=key==='HOLD'?'Below the top step, HOLD extends the finite ring. A looping cradle is deferred.':'TUNE .50 · WEIGHT .50 · SLOPE .40 · GROOVE .35 · FRICTION .30 · BODY .40 · HOLD 0, except the selected control.';}
$('#voice').addEventListener('change',controls);$('#control').addEventListener('change',controls);controls();
for(const voice of ['knock','answer']){const panel=document.createElement('div');panel.className='extra';const h=document.createElement('h3');h.textContent=`${voice.toUpperCase()} · velocity`;const actions=document.createElement('div');actions.className='buttons';for(const n of [35,65])actions.append(button(`${n}%`,`${voice}-velocity-${n}`));actions.append(button('100%',`${voice}-default-60`));panel.append(h,actions);$('#extra-list').append(panel);
 const pair=document.createElement('div');pair.className='extra';const title=document.createElement('h3');title.textContent=`${voice.toUpperCase()} · BODY × FRICTION`;const buttons=document.createElement('div');buttons.className='buttons';for(const b of [25,75])for(const f of [25,75])buttons.append(button(`${b}% / ${f}%`,`${voice}-pair-${b}-${f}`));pair.append(title,buttons);$('#extra-list').append(pair);}
const bench=data.benchmark || [];const answer=bench.filter(x=>x.voice==='ANSWER').map(x=>x.uncachedMs).sort((a,b)=>a-b);const median=answer.length ? answer[Math.floor(answer.length/2)] : null;
for(const [value,label] of [[`${data.proof.middleCubePoints}`,'middle settings checked'],[`${data.proof.minimumHigherContacts}+`,'higher contacts at every tested point'],[median?`${median.toFixed(1)} ms`:'Measured','median ANSWER render on this JVM']]){const d=document.createElement('div');const s=document.createElement('strong');s.textContent=value;const t=document.createElement('span');t.textContent=label;d.append(s,t);$('#evidence').append(d);}
const cold=document.createElement('p');cold.className='small';cold.textContent=`The first ANSWER render took ${data.coldFirstAnswerMs.toFixed(1)} ms in a fresh JVM, including initialisation. A 50 ms target still needs a phone measurement.`;$('#evidence').after(cold);
document.addEventListener('click',e=>{const b=e.target.closest('button[data-play]');if(b)play(b.dataset.play,b);});
