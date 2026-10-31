'use strict';
const $ = (id) => document.getElementById(id);
const player = $('player');
let library = [], byId = new Map(), current = null, mode = 'matched', queue = [], nextTimer = null, geometry = null, buildId = '', model = 4;
const assetURL = (url) => url + (url.includes('?')?'&':'?') + 'r=' + encodeURIComponent(buildId);
const voiceTitles = {LOOP:'Round Seed',GLINT:'Fine Frost',VESSEL:'Open Vessel'};
const controlText = {
  DETAIL:'Maximum resolved scale, limited to the note’s safe bandwidth.',
  EDGE:'Fine-feature sharpness and bounded contour pressure.',
  ANGLE:'Pickup direction and contact spacing: rounded at 180°, open at 0°. A full turn returns to the start.',
  UNFOLD:'When fine bands emerge and recede within the note.',
  BODY:'Modal spacing, decay and the balance of direct sound and resonance.'
};
const escape = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const seconds = (clip) => `${Number(clip.metrics?.durationSeconds || 0).toFixed(2)} s`;
function note(midi) { const names=['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B']; return names[midi%12]+(Math.floor(midi/12)-1); }
function button(clip, label, className='play-button', context='') {
  if (!clip) return '';
  return `<button type="button" class="${className}" data-play="${escape(clip.id)}" aria-label="Play ${escape((context?context+' ':'')+clip.voice+' '+clip.title)}" aria-pressed="false"><span>${escape(label || clip.title)}</span><span class="triangle" aria-hidden="true">▶</span></button>`;
}
function renderBank() {
  const presets=library.filter(c=>c.section==='bank');
  if(!presets.length){$('bank').hidden=true;document.querySelector('.hero .primary').href='#listen';return;}
  $('bank-demo').innerHTML=button(byId.get('bank-kit-phrase'),'Play the full kit ↗');
  $('bank-presets').innerHTML=Object.keys(voiceTitles).map(voice=>`<div class="bank-voice"><div class="bank-voice-heading"><span class="voice-name">${voice}</span><span>${voice==='LOOP'?'Basses':voice==='GLINT'?'Keys & plucks':'Knocks, rings & textures'}</span></div><div class="bank-grid">${presets.filter(c=>c.voice===voice).map(c=>`<article class="bank-card"><span class="bank-role">${escape(c.role)}</span><h3>${escape(c.title)}</h3><p>${escape(c.description)}</p>${wave(c)}<div class="clip-info">${note(c.midi)} · ${seconds(c)}</div><div class="bank-actions">${button(c,'Single note','play-button','bank')}${button(byId.get(`${c.id}-phrase`),'Phrase','play-button','bank')}${button(byId.get(`${c.id}-touch`),'Soft → hard','play-button','bank')}</div><a class="recipe-link" href="${escape(c.files.patch)}" download>Saved patch ↓</a></article>`).join('')}</div></div>`).join('');
}
function wave(clip) {
  const peaks=clip.waveform || [], max=Math.max(...peaks, .001);
  return `<svg class="mini-wave" viewBox="0 0 288 54" aria-hidden="true">${peaks.map((v,i)=>`<line x1="${i*3+1}" x2="${i*3+1}" y1="${27-v/max*23}" y2="${27+v/max*23}" stroke="currentColor" stroke-width="1.5"/>`).join('')}</svg>`;
}
function renderStarters() {
  $('starters').innerHTML=library.filter(c=>c.section==='start').map(c=>{
    const phrase=byId.get(`${c.voice.toLowerCase()}-phrase`);
    return `<article class="starter"><div><span class="voice-name">${c.voice}</span><h3>${escape(c.title)}</h3><p>${escape(c.description)}</p><div class="clip-info">${note(c.midi)} · velocity ${Math.round(c.velocity*100)}% · ${seconds(c)}</div></div><div>${wave(c)}<div class="macro-mini">${Object.keys(controlText).map(k=>`<div>${k}<span><i style="width:${c.macros[k]*100}%"></i></span></div>`).join('')}</div></div><div class="starter-actions">${button(c,'Single note')}${button(phrase,'Six-note phrase')}<a class="recipe-link" href="${escape(c.files.recipe)}" download>Saved recipe ↓</a></div></article>`;
  }).join('');
}
function renderSweeps(voice='GLINT') {
  document.querySelectorAll('[data-voice]').forEach(b=>{b.setAttribute('aria-selected',String(b.dataset.voice===voice)); b.tabIndex=b.dataset.voice===voice?0:-1;});
  $('sweep-panel').setAttribute('aria-labelledby',`tab-${voice.toLowerCase()}`);
  $('sweeps').innerHTML=Object.entries(controlText).map(([macro,description])=>{
    const clips=library.filter(c=>c.section==='controls'&&c.voice===voice&&c.macro===macro).sort((a,b)=>a.value-b.value);
    return `<div class="sweep-row"><div class="sweep-label"><strong>${macro}</strong><p>${escape(description)}</p></div><div class="step-buttons">${clips.map(c=>`<button type="button" data-play="${c.id}" aria-label="Play ${voice} ${macro} at ${Math.round(c.value*(macro==='ANGLE'?360:100))} ${macro==='ANGLE'?'degrees':'percent'}" aria-pressed="false">${Math.round(c.value*(macro==='ANGLE'?360:100))}${macro==='ANGLE'?'°':'%'}${macro==='ANGLE'&&c.value===1?'<small class="same-angle">= 0°</small>':''}</button>`).join('')}</div><button type="button" class="sweep-play" data-sequence="${clips.map(c=>c.id).join(',')}">Play sweep ↗</button></div>`;
  }).join('');
  updateButtons();
}
function renderRevision(voice='GLINT') {
  const prefix=voice.toLowerCase();
  const steps=(name,values,labels,stem=prefix)=>values.map((v,i)=>button(byId.get(`${stem}-${name}-${v}`),labels[i],'play-button revision-play')).join('');
  $('revision-comparisons').innerHTML=`
    <article class="revision-row angle-comparison"><div><strong>ANGLE / ${voice}</strong><p>The rounded/open contrast you preferred.</p></div><div class="revision-buttons angle-pair">${steps('angle',[50,0],['180° · Rounded','0° · Open'])}<button type="button" class="play-button" data-sequence="${prefix}-angle-50,${prefix}-angle-0,${prefix}-angle-50,${prefix}-angle-0">Alternate pair ↗</button></div><div class="previous-clip"><button type="button" class="play-button previous-play" data-sequence="previous-${prefix}-angle-50,previous-${prefix}-angle-0,previous-${prefix}-angle-50,previous-${prefix}-angle-0">Original pair ↗</button></div></article>
    <article class="revision-row angle-return-comparison"><div><strong>ANGLE / RETURN</strong><p>180° → 225° → 270° → 315° → 360°</p></div><div class="revision-buttons angle-travel">${steps('angle',[63,75,88],['225°','270°','315°'])}<button type="button" class="play-button" data-sequence="${prefix}-angle-50,${prefix}-angle-63,${prefix}-angle-75,${prefix}-angle-88,${prefix}-angle-100">Play return ↗</button></div><div class="previous-clip"><button type="button" class="play-button previous-play" data-sequence="${prefix}-angle-50,model3-${prefix}-angle-63,model3-${prefix}-angle-75,model3-${prefix}-angle-88,${prefix}-angle-100">Previous return ↗</button></div></article>
    <article class="revision-row"><div><strong>BODY / ${voice}</strong><p>Direct contour → a larger resonating body.</p></div><div class="revision-buttons">${steps('body',[0,50,100],['0%','50%','100%'])}</div><div class="previous-clip">${button(byId.get(`legacy-${prefix}-body-100`),'Previous 100%','play-button previous-play')}</div></article>
    <article class="revision-row"><div><strong>UNFOLD / LOOP</strong><p>Detail at onset → detail arriving within the note.</p></div><div class="revision-buttons">${steps('unfold',[0,50,100],['0%','50%','100%'],'loop')}</div><div class="previous-clip">${button(byId.get('legacy-loop-unfold-100'),'Previous 100%','play-button previous-play')}</div></article>`;
  updateButtons();
}
$('revision-voice').addEventListener('change',event=>{clearQueue();renderRevision(event.target.value);});
function renderCausal() {
  const descriptions={LOOP:'Listen for changes in the hollow bass and the attack.',GLINT:'Listen for fine pressure and how the upper edge rings.',VESSEL:'Listen for the reveal, then the body after excitation ends.'};
  $('causal').innerHTML=Object.keys(voiceTitles).map(voice=>`<article class="causal-column"><span class="voice-name">${voice}</span><h3>${voiceTitles[voice]}</h3>${library.filter(c=>c.voice===voice&&c.section==='causal').map(c=>button(c)).join('')}<p>${descriptions[voice]}</p></article>`).join('');
}
function renderBench() {
  const value=$('search').value.toLowerCase(), section=$('section-filter').value;
  const clips=library.filter(c=>['register','interactions','stress'].includes(c.section)&&(section==='all'||c.section===section)&&`${c.voice} ${c.title} ${c.group||''} ${note(c.midi)}`.toLowerCase().includes(value));
  $('bench').innerHTML=clips.map(c=>{
    const cents=c.metrics?.fundamentalErrorCents ?? c.metrics?.measuredErrorCents ?? c.metrics?.fineErrorCents;
    return `<tr><td>${escape(c.title)}<small>${c.voice} · ${note(c.midi)}${c.group?' · '+escape(c.group):''}</small></td><td>${seconds(c)}</td><td>${cents==null?'—':Number(cents).toFixed(2)+' ¢'}</td><td>${Number(c.metrics?.internalPeak||0).toFixed(3)}</td><td>${button(c,'Play','table-play')}<a class="table-recipe" href="${escape(c.files.recipe)}" download>Recipe ↓</a></td></tr>`;
  }).join('');
  $('bench-empty').hidden=clips.length>0;
  updateButtons();
}
function clearQueue() { queue=[]; if(nextTimer){clearTimeout(nextTimer);nextTimer=null;} }
async function playClip(id, preserveQueue=false) {
  const clip=byId.get(id); if(!clip) return;
  if(!preserveQueue) clearQueue();
  if(current?.id===id&&!player.paused&&!preserveQueue){player.pause();updateButtons();return;}
  current=clip;
  player.src=assetURL(clip.files[mode]);
  $('now-title').textContent=`${clip.voice} / ${clip.title}`;
  $('playback-error').textContent='';
  updateDownload();
  try {await player.play();} catch(error) {
    if(error.name!=='AbortError') $('playback-error').textContent='Playback did not start. Press play in the player to retry.';
  }
  updateButtons();
}
function updateDownload(){if(!current)return;$('current-download').href=assetURL(current.files[mode]);$('current-download').hidden=false;}
function updateButtons(){
  const playing=current&&!player.paused&&!player.ended;
  document.querySelectorAll('[data-play]').forEach(b=>{const on=playing&&b.dataset.play===current.id;b.classList.toggle('is-playing',!!on);b.setAttribute('aria-pressed',String(!!on));});
  $('playing-dot').classList.toggle('active',!!playing);
}
document.addEventListener('click',event=>{
  const play=event.target.closest('[data-play]'); if(play){playClip(play.dataset.play);return;}
  const sequence=event.target.closest('[data-sequence]'); if(sequence){clearQueue();queue=sequence.dataset.sequence.split(',');const first=queue.shift();playClip(first,true);return;}
  const tab=event.target.closest('[data-voice]'); if(tab)renderSweeps(tab.dataset.voice);
});
$('voice-tabs').addEventListener('keydown',event=>{
  if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  const tabs=[...document.querySelectorAll('[data-voice]')], i=tabs.indexOf(document.activeElement);if(i<0)return;
  event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?2:(i+(event.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;
  renderSweeps(tabs[next].dataset.voice);tabs[next].focus();
});
player.addEventListener('ended',()=>{updateButtons();if(queue.length)nextTimer=setTimeout(()=>{nextTimer=null;playClip(queue.shift(),true);},180);});
['play','pause'].forEach(name=>player.addEventListener(name,updateButtons));
player.addEventListener('error',()=>{clearQueue();$('playback-error').textContent='This file could not be loaded. Retry playback or use the WAV download.';updateButtons();});
document.querySelectorAll('[name=mode]').forEach(input=>input.addEventListener('change',async()=>{
  mode=input.value;if(!current)return;
  const time=player.currentTime, wasPlaying=!player.paused;
  player.preload='metadata';
  player.addEventListener('loadedmetadata',()=>{player.currentTime=Math.min(time,Math.max(0,player.duration-.01));},{once:true});
  player.src=assetURL(current.files[mode]);player.load();updateDownload();
  if(wasPlaying)try{await player.play();}catch(error){if(error.name!=='AbortError')$('playback-error').textContent='Press play to resume this version.';}
  updateButtons();
}));
$('search').addEventListener('input',renderBench);$('section-filter').addEventListener('change',renderBench);
function path(points){return points.map((p,i)=>`${i?'L':'M'}${p[0].toFixed(5)},${p[1].toFixed(5)}`).join('')+'Z';}
function renderGeometry(){if(!geometry)return;const reconstruction=geometry.reconstructions[Number($('geometry-detail').value)];$('target-contour').setAttribute('d',path(geometry.target));$('active-contour').setAttribute('d',path(reconstruction.points));$('geometry-value').textContent=`|n| ≤ ${reconstruction.cutoff}`;}
$('geometry-detail').addEventListener('input',renderGeometry);
const notesKey='snipsnap-rime-model1-listening-notes';
try{$('listening-notes').value=localStorage.getItem(notesKey)||'';}catch{}
$('listening-notes').addEventListener('input',()=>{try{localStorage.setItem(notesKey,$('listening-notes').value);$('notes-status').textContent='Saved in this browser';}catch{$('notes-status').textContent='Download to keep these notes';}});
$('download-notes').addEventListener('click',()=>{
  const data={engine:'RIME',model,date:new Date().toISOString(),lastClip:current?.id||null,playback:mode,notes:$('listening-notes').value};
  const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='rime-listening-notes.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
async function load(){
  try{
    const fetchJson=async(url)=>{const response=await fetch(url,{cache:'no-store'});if(!response.ok)throw new Error(`${url}: ${response.status}`);return response.json();};
    const [manifest,contour]=await Promise.all([fetchJson('manifest.json'),fetchJson('geometry.json')]);
    library=manifest.clips;byId=new Map(library.map(c=>[c.id,c]));geometry=contour;buildId=manifest.sourceRevision;model=manifest.model;
    renderBank();renderStarters();renderSweeps();renderCausal();renderBench();renderGeometry();renderRevision();
    document.querySelectorAll('a[download][href]').forEach(link=>{link.href=assetURL(link.getAttribute('href'));});
    $('bench-count').textContent=`${library.filter(c=>['register','interactions','stress'].includes(c.section)).length} tests`;
    $('build-info').textContent=`${library.length} rendered clips · ${manifest.sampleRate.toLocaleString()} Hz · ${manifest.oversample}× internal DSP · ${new Date(manifest.generatedAt).toLocaleDateString(undefined,{year:'numeric',month:'long',day:'numeric'})}`;
    $('load-status').hidden=true;
  }catch(error){$('load-status').hidden=false;$('load-status').classList.add('error');$('load-status').textContent='The listening library could not load. Reload the page, or use the starter WAV download.';console.error(error);}
}
load();
