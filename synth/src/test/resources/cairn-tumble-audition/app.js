const $=s=>document.querySelector(s),status=$('#status');
let data,context,active,current,request=0,note=60;
const buffers=new Map();
function stop(){request++;if(active){active.onended=null;try{active.stop();}catch{}active=null;}document.querySelectorAll('.playing').forEach(b=>{b.classList.remove('playing');b.setAttribute('aria-pressed','false');});current=null;status.textContent='Sound stopped.';}
$('#stop').addEventListener('click',stop);
document.addEventListener('keydown',e=>{if(e.code==='Space'&&!['BUTTON','INPUT','SELECT','SUMMARY'].includes(e.target.tagName)){e.preventDefault();stop();}});
async function buffer(path){if(!buffers.has(path))buffers.set(path,fetch(path).then(r=>{if(!r.ok)throw new Error('That recording could not load. Try it again.');return r.arrayBuffer();}).then(bytes=>context.decodeAudioData(bytes)));try{return await buffers.get(path);}catch(e){buffers.delete(path);throw e;}}
async function play(id,button){
 if(button===current){stop();return;}stop();const ticket=request;current=button;
 try{
  const clip=data?.clips.find(c=>c.id===id)||data?.examples.find(c=>c.id===id);
  if(!clip)throw new Error('The listening set is still loading. Please try again.');
  context ||= new AudioContext();await context.resume();status.textContent=`Loading ${clip.voice||'the phrase'}…`;button.disabled=true;
  const audio=await buffer(clip.audio);if(ticket!==request)return;
  const source=context.createBufferSource(),gain=context.createGain();source.buffer=audio;source.loop=!!clip.held;
  const match=clip.previewGain??1;gain.gain.value=Number($('#volume').value)*match;source.connect(gain).connect(context.destination);
  source.level=gain;source.matchGain=match;active=source;
  source.onended=()=>{if(active===source){active=null;current=null;button.classList.remove('playing');button.setAttribute('aria-pressed','false');status.textContent='Ready for another listen.';}};
  const start=context.currentTime+.015;source.start(start);if(clip.held)source.stop(start+audio.duration*8);
  button.classList.add('playing');button.setAttribute('aria-pressed','true');
  status.textContent=clip.voice?`${clip.voice} · C${Math.floor(clip.midi/12)-1} · ${clip.held?'HOLD ×8 · '+(audio.duration*8)+' seconds':'one launch'}`:clip.description;
 }catch(e){if(ticket===request){current=null;status.textContent=e.message;}}finally{button.disabled=false;}
}
$('#volume').addEventListener('input',()=>{if(active)active.level.gain.setTargetAtTime(Number($('#volume').value)*active.matchGain,context.currentTime,.015);});
function button(label,id,secondary=false){const b=document.createElement('button');b.type='button';b.textContent=label;b.dataset.play=id;b.setAttribute('aria-pressed','false');if(secondary)b.className='secondary';return b;}
function explorer(){const control=$('#control').value.toLowerCase(),mode=$('#mode').value;$('#control-buttons').replaceChildren(...[0,50,100].map((value,i)=>button(['Minimum','Middle','Maximum'][i]+(mode==='hold'?' ×8':''),`tumble-${control}-${value}-${mode}`,true)));}
$('#control').addEventListener('change',()=>{stop();explorer();});$('#mode').addEventListener('change',()=>{stop();explorer();});
document.querySelectorAll('[data-note]').forEach(b=>b.addEventListener('click',()=>{stop();note=Number(b.dataset.note);document.querySelectorAll('[data-note]').forEach(x=>{x.classList.toggle('selected',x===b);x.setAttribute('aria-pressed',String(x===b));});status.textContent=`C${note/12-1} selected for the default voice buttons.`;}));
document.addEventListener('click',e=>{const b=e.target.closest('button[data-play],button[data-voice]');if(b)play(b.dataset.play||`${b.dataset.voice}-${b.dataset.mode}-${note}`,b);});
try{
 const response=await fetch('manifest.json');if(!response.ok)throw new Error('The listening set could not load. Please refresh the page.');data=await response.json();
 for(const voice of ['knock','answer','tumble']){
  const c=data.clips.find(c=>c.id===`${voice}-hold-60`),map=$(`#${voice}-map`),peak=Math.max(...c.contactEvents.map(e=>e.work));
  for(let stone=0;stone<(voice==='tumble'?1:4);stone++){const row=document.createElement('div');row.className=`contact-row ${stone?'upper':''}`;for(const event of c.contactEvents.filter(e=>e.stone===stone)){const mark=document.createElement('span');mark.style.left=`${Math.max(1,Math.min(99,event.seconds/c.seconds*100))}%`;mark.style.height=`${4+9*Math.sqrt(event.work/peak)}px`;mark.title=`${voice==='tumble'?'Rebound':stone?'Higher':'Direct'} · ${event.seconds.toFixed(3)} s`;row.append(mark);}map.append(row);}
  $(`#${voice}-stat`).textContent=voice==='tumble'?`${c.primaryContacts} rebounds · gaps ${(1000*c.firstGapSeconds).toFixed(0)} → ${(1000*c.lastGapSeconds).toFixed(0)} ms`:`${c.primaryContacts} direct · ${c.higherContacts} higher contacts`;
 }
 for(const [value,title]of[[0,'Closed bed'],[50,'Room to rebound'],[100,'The little trail']]){const c=data.clips.find(c=>c.id===`tumble-groove-${value}-shot`),article=document.createElement('article'),copy=document.createElement('div'),h=document.createElement('h3'),p=document.createElement('p');h.textContent=title;p.textContent=`GROOVE ${value}% · ${c.primaryContacts} impacts`;copy.append(h,p);article.append(copy,button('Listen ▶',c.id));$('#groove-list').append(article);}
 explorer();
 for(const mode of ['shot','hold']){
  const row=document.createElement('div');row.className='extra';const h=document.createElement('h3');h.textContent=`BODY × FRICTION / ${mode==='hold'?'HOLD ×8':'one launch'}`;const actions=document.createElement('div');actions.className='actions';for(const body of [25,75])for(const friction of [25,75])actions.append(button(`${body}% / ${friction}%`,`tumble-pair-${body}-${friction}-${mode}`,true));row.append(h,actions);$('#extras').append(row);
  const soft=document.createElement('div');soft.className='extra';const title=document.createElement('h3');title.textContent=`Velocity / ${mode==='hold'?'HOLD ×8':'one launch'}`;const buttons=document.createElement('div');buttons.className='actions';for(const value of [35,65])buttons.append(button(`${value}%`,`tumble-soft-${value}-${mode}`,true));buttons.append(button('100%',`tumble-${mode}-60`,true));soft.append(title,buttons);$('#extras').append(soft);
  const edge=document.createElement('div');edge.className='extra';const eh=document.createElement('h3');eh.textContent=`Bed return and control extremes / ${mode==='hold'?'HOLD ×8':'one launch'}`;const eb=document.createElement('div');eb.className='actions';eb.append(button('Bed return off',`tumble-isolated-${mode}`,true),button('All minimum',`tumble-edge-0-${mode}`,true),button('All maximum',`tumble-edge-1-${mode}`,true));edge.append(eh,eb);$('#extras').append(edge);
 }
 const held=data.clips.filter(c=>c.held),maxSeam=Math.max(...held.map(c=>c.seamError||0));
 for(const[value,label]of[['3 gestures','compact cluster · higher replies · tightening rebounds'],[String(held.length),'continuing renders checked against a real preceding segment'],[maxSeam<1e-9?'＜ 1e−9':maxSeam.toExponential(1),'largest measured seam error; limit 1e−3']]){const div=document.createElement('div'),strong=document.createElement('strong'),span=document.createElement('span');strong.textContent=value;span.textContent=label;div.append(strong,span);$('#evidence').append(div);}
 const measure=text=>{const p=document.createElement('p');p.textContent=text;$('#measurements').append(p);};
 measure(`${data.clips.length} Kotlin comparison renders and two dry phrases. Each held render also has a full preceding-cycle reference; the largest relative RMS difference is ${Math.max(...held.map(c=>c.fullPrerollReferenceRelativeRms)).toExponential(2)}.`);
 for(const held of [false,true]){const a=data.benchmark.filter(b=>b.held===held).map(b=>b.uncachedMs).sort((a,b)=>a-b),median=(a[(a.length-1)>>1]+a[a.length>>1])/2;measure(`TUMBLE ${held?'HOLD':'one launch'}: ${median.toFixed(1)} ms median across ${a.length} warmed renders with new controls on this server. First TUMBLE HOLD, including initialization: ${data.coldFirstTumbleHoldMs.toFixed(1)} ms. These timings do not establish phone latency.`);}
 if(data.legacyHold)measure(`All ${data.legacyHold.replayed} previous comparison WAVs regenerate byte for byte. The earlier two voices keep their versioned behavior; new TUMBLE recipes require model 3.`);
 measure('These timings were collected on an idle server after all 7,450 JVM tests passed. Warmed renders meet the proposed 50 ms server target; the cold first HOLD does not. Phone and MPC acceptance remain open.');
 measure('The mechanics use normalized shape constants. Gravity and restitution determine the contacts, but this is not a measured stone or a full three-dimensional collision model. Small contacts share one fixed work-to-impulse calibration; they are not individually normalized.');
 status.textContent='Choose a voice to begin.';
}catch(e){status.textContent=e.message;document.querySelectorAll('button[data-play],button[data-voice]').forEach(b=>b.disabled=true);}
