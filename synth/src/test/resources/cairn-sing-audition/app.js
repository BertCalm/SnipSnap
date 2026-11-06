const $=s=>document.querySelector(s),status=$('#status');
let data,context,active,current,request=0,note=60;
const buffers=new Map(),NS='http://www.w3.org/2000/svg';
const roles=[
 ['knock','PUNCTUATION','A compact cluster of direct strikes. A short ring, then room for the next note.'],
 ['answer','CONVERSATION','A grounded call wakes quieter, higher stones. Leave space for the replies.'],
 ['tumble','CASCADE','One stone rebounds into a tightening scatter. Each smaller bounce arrives sooner.'],
 ['sing','SONG','A soft contact opens into a smooth rubbing tone. Let the note catch and bloom.']
];
function stop(){request++;if(active){active.onended=null;try{active.stop();}catch{}active=null;}document.querySelectorAll('.playing').forEach(b=>{b.classList.remove('playing');b.setAttribute('aria-pressed','false');});current=null;status.textContent='Sound stopped.';}
$('#stop').addEventListener('click',stop);$('#reload').addEventListener('click',()=>location.reload());
document.addEventListener('keydown',e=>{if(e.code==='Space'&&!['BUTTON','INPUT','SELECT','SUMMARY'].includes(e.target.tagName)){e.preventDefault();stop();}});
async function buffer(path){if(!buffers.has(path))buffers.set(path,fetch(path).then(r=>{if(!r.ok)throw Error('That recording could not load. Try it again.');return r.arrayBuffer();}).then(b=>context.decodeAudioData(b)));try{return await buffers.get(path);}catch(e){buffers.delete(path);throw e;}}
async function play(id,button){
 if(button===current){stop();return;}stop();const ticket=request;current=button;
 try{
  const clip=data?.clips.find(c=>c.id===id)||data?.examples.find(c=>c.id===id);if(!clip)throw Error('The listening set is still loading. Try again in a moment.');
  context||=new AudioContext({sampleRate:44100});await context.resume();status.textContent=`Loading ${clip.voice||'the phrase'}…`;button.disabled=true;
  const b=await buffer(clip.audio);if(ticket!==request)return;
  const source=context.createBufferSource(),gain=context.createGain();source.buffer=b;source.loop=!!clip.held;
  gain.gain.value=Number($('#volume').value)*(clip.previewGain??1);source.connect(gain).connect(context.destination);source.level=gain;source.matchGain=clip.previewGain??1;active=source;
  source.onended=()=>{if(active===source){active=null;current=null;button.classList.remove('playing');button.setAttribute('aria-pressed','false');status.textContent='Ready for another listen.';}};
  const start=context.currentTime+.015;source.start(start);if(clip.held)source.stop(start+b.duration*8);
  button.classList.add('playing');button.setAttribute('aria-pressed','true');
  status.textContent=clip.voice?`${clip.voice} · C${Math.floor(clip.midi/12)-1} · ${clip.held?`HOLD ×8 · ${b.duration*8} seconds`:'one touch'}`:clip.description;
 }catch(e){if(ticket===request){current=null;status.textContent=e.message;}}finally{button.disabled=false;}
}
$('#volume').addEventListener('input',()=>{if(active)active.level.gain.setTargetAtTime(Number($('#volume').value)*active.matchGain,context.currentTime,.015);});
function button(label,id,secondary=false){const b=document.createElement('button');b.type='button';b.textContent=label;b.dataset.play=id;b.setAttribute('aria-pressed','false');if(secondary)b.className='secondary';return b;}
function explorer(){const control=$('#control').value.toLowerCase(),mode=$('#mode').value;$('#control-buttons').replaceChildren(...[0,50,100].map((value,i)=>button(['Low · 0%','Middle · 50%','High · 100%'][i]+(mode==='hold'?' ×8':''),`sing-${control}-${value}-${mode}`,true)));}
function drawLevels(){
 const clips=roles.map(([voice])=>data.clips.find(c=>c.id===`${voice}-shot-${note}`)),seconds=Math.ceil(Math.max(...clips.map(c=>c.seconds)));
 for(const [i,c] of clips.entries()){
  const host=$(`#${roles[i][0]}-shape`);host.replaceChildren();const svg=document.createElementNS(NS,'svg');svg.setAttribute('viewBox','0 0 400 90');svg.setAttribute('role','img');svg.setAttribute('aria-label',`${c.voice} at C${Math.floor(c.midi/12)-1}, finite note level over ${seconds} seconds`);
  const peak=Math.max(...c.levels),points=c.levels.map((v,j)=>[Math.min(400,j*c.levelStepSeconds/seconds*400),77-(v/peak)*64]);
  const path=document.createElementNS(NS,'path');path.setAttribute('d',`M0 77 L${points.map(p=>p.map(v=>v.toFixed(2)).join(' ')).join(' L')} L${(c.seconds/seconds*400).toFixed(2)} 77 Z`);svg.append(path);host.append(svg);
  $(`#${roles[i][0]}-stat`).textContent=`${c.voice==='SING'?'One contact → continuous rubbing':`${c.primaryContacts} direct · ${c.higherContacts} higher contacts`} · ${c.seconds.toFixed(1)} s note`;
 }
}
$('#control').addEventListener('change',()=>{stop();explorer();});$('#mode').addEventListener('change',()=>{stop();explorer();});
document.querySelectorAll('[data-note]').forEach(b=>b.addEventListener('click',()=>{stop();note=Number(b.dataset.note);document.querySelectorAll('[data-note]').forEach(x=>{x.classList.toggle('selected',x===b);x.setAttribute('aria-pressed',String(x===b));});if(data)drawLevels();status.textContent=`C${note/12-1} selected for the default voice buttons.`;}));
document.addEventListener('click',e=>{const b=e.target.closest('button[data-play],button[data-voice]');if(b)play(b.dataset.play||`${b.dataset.voice}-${b.dataset.mode}-${note}`,b);});
try{
 const response=await fetch('manifest.json');if(!response.ok)throw Error('The listening set could not load. Please try loading it again.');data=await response.json();
 for(const [i,[voice,role,description]] of roles.entries()){
  const article=document.createElement('article');article.className=`voice ${voice}`;
  const label=document.createElement('p');label.className='voice-label';label.textContent=`0${i+1} / ${role}`;
  const h=document.createElement('h3');h.textContent=voice.toUpperCase();const p=document.createElement('p');p.textContent=description;
  const actions=document.createElement('div');actions.className='actions';for(const [mode,title]of[['shot','One touch'],['hold','HOLD ×8']]){const b=document.createElement('button');b.dataset.voice=voice;b.dataset.mode=mode;b.textContent=title;b.setAttribute('aria-pressed','false');if(mode==='hold')b.className='secondary';actions.append(b);}
  const shape=document.createElement('div');shape.id=`${voice}-shape`;shape.className='level-shape';const stat=document.createElement('p');stat.id=`${voice}-stat`;stat.className='stat';
  const common=document.createElement('div');common.className='common';const caption=document.createElement('span');caption.textContent='C4 · identical controls across voices';common.append(caption);for(const mode of ['shot','hold']){const b=button(mode==='shot'?'One touch':'HOLD ×8',`${voice}-common-${mode}`);b.className='text-button';common.append(b);}
  article.append(label,h,p,actions,shape,stat,common);$('#voices').append(article);
 }
 drawLevels();explorer();
 for(const mode of ['shot','hold']){
  const add=(title,buttons)=>{const box=document.createElement('div');box.className='extra';const h=document.createElement('h3');h.textContent=`${title} / ${mode==='hold'?'HOLD ×8':'one touch'}`;const row=document.createElement('div');row.className='actions';row.append(...buttons);box.append(h,row);$('#extras').append(box);};
  add('BODY × FRICTION',[25,75].flatMap(body=>[25,75].map(friction=>button(`${body}% / ${friction}%`,`sing-pair-${body}-${friction}-${mode}`,true))));
  add('Touch strength',[35,65,100].map(v=>button(`${v}%`,v===100?`sing-${mode}-60`:`sing-soft-${v}-${mode}`,true)));
  add('Control extremes',[0,1].map(v=>button(v?'All maximum':'All minimum',`sing-edge-${v}-${mode}`,true)));
 }
 const dwell=document.createElement('div');dwell.className='extra';const title=document.createElement('h3');title.textContent='Finite contact length / C4';const row=document.createElement('div');row.className='actions';row.append(button('Default','sing-shot-60',true),button('Longer contact','sing-dwell-50',true),button('Longest finite contact','sing-dwell-95',true));dwell.append(title,row);$('#extras').append(dwell);
 const held=data.clips.filter(c=>c.held),seam=Math.max(...held.map(c=>c.seamError||0));
 for(const [value,label] of [['4 roles','punctuation · conversation · cascade · song'],[String(held.length),'held recordings checked against actual preceding audio'],[seam<1e-9?'＜ 1e−9':seam.toExponential(1),'largest measured seam error; limit 1e−3']]){const div=document.createElement('div'),strong=document.createElement('strong'),span=document.createElement('span');strong.textContent=value;span.textContent=label;div.append(strong,span);$('#evidence').append(div);}
 const measure=text=>{const p=document.createElement('p');p.textContent=text;$('#measurements').append(p);};
 measure(`${data.clips.length} production Kotlin comparisons and two dry phrases. Held notes also agree with a separate warm-up reference; maximum relative RMS difference ${Math.max(...held.map(c=>c.warmReferenceRelativeRms)).toExponential(2)}.`);
 for(const held of [false,true]){const a=data.benchmark.filter(b=>b.held===held).map(b=>b.uncachedMs).sort((a,b)=>a-b),median=(a[(a.length-1)>>1]+a[a.length>>1])/2;measure(`SING ${held?'HOLD':'finite note'}: ${median.toFixed(1)} ms median across ${a.length} warmed renders with new controls on an idle server.`);}
 measure(`First SING HOLD, including initialization: ${data.coldFirstSingHoldMs.toFixed(1)} ms. The proposed warmed server target is 50 ms; these measurements do not establish phone performance.`);
 if(data.legacy?.length)measure(`Earlier audio replay: ${data.legacy.map(l=>`${l.cases} ${l.source} cases / ${l.wavs} WAVs unchanged`).join('; ')}.`);
 measure(data.repositoryVerification?.summary||'Focused DSP and render checks are recorded here; full repository validation is still in progress.');
 measure('SING’s regenerative energy trajectory is an averaged friction analogy. The output is a damped modal stone readout. The model does not simulate microscopic stick/slip or a measured real material.');
 if(data.repositoryPublication?.pullRequest)$('#repo-link').href=data.repositoryPublication.pullRequest;
 document.querySelectorAll('button[data-play],button[data-voice]').forEach(b=>b.disabled=false);status.textContent='Choose a voice to begin.';
}catch(e){status.textContent=e.message;$('#reload').hidden=false;document.querySelectorAll('button[data-play],button[data-voice]').forEach(b=>b.disabled=true);}
