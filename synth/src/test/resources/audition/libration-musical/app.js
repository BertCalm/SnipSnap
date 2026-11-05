'use strict';
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const audio = $('#audio');
const names = {GATHER: 'Gather', TWINS: 'Twins', DEPARTURE: 'Departure'};
const notesKey = 'snipsnap-libration-musical-notes-v3';
let manifest, current, selectedVoice = 'GATHER', selectedLayer = 1, request = 0, score = null, recipeUrl;
const blobs = new Map();
let notes = {};
try { notes = JSON.parse(localStorage.getItem(notesKey) || '{}'); } catch { /* private storage can be unavailable */ }
const noteName = (midi) => ['C','C♯','D','E♭','E','F','F♯','G','A♭','A','B♭','B'][midi % 12] + (Math.floor(midi / 12) - 1);
const time = (s) => `${Math.floor((s || 0) / 60)}:${String(Math.floor((s || 0) % 60)).padStart(2, '0')}`;
const pressed = (b, value) => b.setAttribute('aria-pressed', String(value));
const arrowIcon = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 16 16 4M4 4h12v12"/></svg>';
const svgNode = (tag, attrs) => { const el = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const [k,v] of Object.entries(attrs)) el.setAttribute(k, v); return el; };
function message(text = '') { $('#message').textContent = text; }
async function blobFor(path) {
  if (!blobs.has(path)) blobs.set(path, fetch(path).then(async (r) => { if (!r.ok) throw Error(`Audio could not load (${r.status}).`); return URL.createObjectURL(await r.blob()); }).catch((e) => { blobs.delete(path); throw e; }));
  return blobs.get(path);
}
function button(clip, label, className = '') {
  const b = document.createElement('button'); b.type = 'button'; b.dataset.clip = clip.id; b.className = className;
  b.textContent = label; pressed(b, current?.id === clip.id); b.addEventListener('click', () => select(clip, true)); return b;
}
function drawScore() {
  const svg = $('#score'); svg.replaceChildren();
  if (!current) return;
  const events = score?.events || (current.midi != null ? [{beat:0, patch:{midi:current.midi, velocity:current.velocity ?? 1, sourceSeconds:current.sourceSeconds}}] : []);
  const end = Math.max(1, ...events.map((e) => e.beat + (e.patch?.sourceSeconds || .3) * manifest.tempoBpm / 60));
  const span = Math.max(4, Math.ceil(end / 4) * 4);
  const pitches = events.map((e) => e.patch?.midi ?? 48 + (e.slot || 1));
  const lo = Math.min(...pitches, 48) - 2, hi = Math.max(...pitches, 72) + 2;
  for (let b=0; b<=span; b+=span > 8 ? 4 : 1) {
    const x = 16 + b / span * 608;
    svg.append(svgNode('line', {x1:x, y1:10, x2:x, y2:117, class:'grid'}));
    const t = svgNode('text', {x:x+3, y:133}); t.textContent = `${b / 4 + 1}`; if (b % 4 === 0) svg.append(t);
  }
  events.forEach((e,i) => {
    const duration = (e.patch?.sourceSeconds || .25) * manifest.tempoBpm / 60;
    const x = 16 + e.beat / span * 608, y = 104 - (pitches[i] - lo) / (hi - lo) * 85;
    const rect = svgNode('rect', {x, y, width:Math.max(4,duration/span*608), height:5, rx:2, class:'note', opacity:.3+.7*(e.patch?.velocity ?? e.velocity ?? 1)});
    rect.style.opacity = .3 + .7 * (e.patch?.velocity ?? e.velocity ?? 1);
    const title = svgNode('title', {}); title.textContent = e.patch ? `${noteName(e.patch.midi)} · touch ${Math.round(e.patch.velocity * 100)}% · ${e.patch.sourceSeconds}s source` : `Pad ${e.slot}`;
    rect.append(title); svg.append(rect);
  });
  const cursor = svgNode('line', {id:'score-cursor', x1:16, x2:16, y1:7, y2:117, class:'cursor'}); svg.append(cursor);
  svg.dataset.beats = span;
  $('#score-caption').textContent = events.length > 1 ? `${events.length} note events · ${manifest.tempoBpm} BPM · bars shown below` : 'Source length shown; the tail continues after it closes.';
}
function updatePosition() {
  $('#elapsed').textContent = time(audio.currentTime); $('#duration').textContent = time(audio.duration);
  $('#seek').value = Number.isFinite(audio.duration) && audio.duration > 0 ? Math.round(audio.currentTime / audio.duration * 1000) : 0;
  const cursor = $('#score-cursor');
  if (cursor) { const x = 16 + Math.min(1, audio.currentTime * manifest.tempoBpm / 60 / Number($('#score').dataset.beats)) * 608; cursor.setAttribute('x1',x); cursor.setAttribute('x2',x); }
}
async function select(clip, autoplay = false, preserveTime = 0) {
  const seq = ++request;
  audio.pause(); current = clip; score = null; message(); $('#play').disabled = true;
  $('#now-title').textContent = clip.title; $('#now-kind').textContent = clip.group === 'pad' ? 'MPC SOURCE SAMPLE' : clip.group === 'bank' ? `${names[clip.voice].toUpperCase()} / PRESET` : clip.group === 'music' || clip.group === 'kit-music' ? 'IN CONTEXT / FOUR BARS' : 'PLAYABILITY / ONE VARIABLE';
  $('#now-description').textContent = clip.description;
  $('#now-detail').textContent = clip.midi != null ? `${noteName(clip.midi)} · ${clip.sourceSeconds}s source${clip.velocity != null ? ` · ${Math.round(clip.velocity*100)}% touch` : ''}` : `${manifest.tempoBpm} BPM · complete tails`;
  const isPad = clip.group === 'pad';
  audio.volume = isPad ? .25 * Math.sqrt(.5) * clip.level : 1;
  $('#level-note').textContent = isPad ? 'Exact exported sample at a conservative monitor gain. Layer selection changes the source WAV; hardware can apply additional velocity gain.' : clip.group === 'touch' ? 'Shared hard-note gain preserves soft-to-hard dynamics in Matched mode.' : $('#level').value === 'native' ? 'Native engine-conditioned output at the saved touch. Musical phrases retain one fixed mix gain.' : 'Whole-clip matching sets a quiet listening level and preserves accents within each performance.';
  const path = $('#level').value === 'native' ? clip.nativeAudio : clip.audio;
  $('#wav').href = path; $('#wav').download = path.split('/').pop();
  if (recipeUrl) { URL.revokeObjectURL(recipeUrl); recipeUrl = undefined; }
  if (clip.recipeData) { recipeUrl = URL.createObjectURL(new Blob([JSON.stringify(clip.recipeData,null,2)],{type:'application/json'})); $('#recipe').href = recipeUrl; $('#recipe').download = `${clip.id}.json`; }
  else { $('#recipe').href = clip.recipe; $('#recipe').download = clip.recipe.split('/').pop(); }
  $$('[data-clip]').forEach((b) => pressed(b,b.dataset.clip === clip.id));
  $$('[data-pad]').forEach((b) => pressed(b,Number(b.dataset.pad) === clip.padSlot));
  $('#notes').value = typeof notes[clip.id] === 'string' ? notes[clip.id] : ''; $('#notes-label').textContent = `Notes for ${clip.title}`;
  drawScore();
  if (clip.score) fetch(clip.score).then((r) => { if(!r.ok) throw Error(); return r.json(); }).then((data) => { if(seq === request) { score = data; drawScore(); updatePosition(); } }).catch(() => { if(seq === request) message('The score could not load. Audio is still available.'); });
  try {
    const url = await blobFor(path); if(seq !== request) return;
    audio.src = url; audio.load();
    await new Promise((resolve,reject) => { audio.addEventListener('loadedmetadata',resolve,{once:true}); audio.addEventListener('error',reject,{once:true}); });
    if(seq !== request) return;
    if (preserveTime > 0 && preserveTime < audio.duration) audio.currentTime = preserveTime;
    $('#play').disabled = false; updatePosition();
    if (autoplay) await audio.play();
  } catch { if(seq === request) { $('#play').disabled = false; message('Playback could not start. Try Play again, or download the WAV.'); } }
}
function renderProbes() {
  const v = selectedVoice.toLowerCase();
  for (const [kind, ids] of [['touch',[30,65,100]], ['length',['short','medium','long']], ['root',[36,60,84]]]) {
    const node = $(`#${kind}-options`); node.replaceChildren();
    for (const value of ids) { const c = manifest.clips.find((x) => x.id === `${kind}-${v}-${value}`); node.append(button(c,c.title)); }
  }
  const c = manifest.clips.find((x) => x.id === `repeat-${v}`); $('#repeat-options').replaceChildren(button(c,'Hear six repeated notes'));
}
function playPad(slot) {
  const pad = manifest.pads.find((p) => p.slot === slot); const layer = pad.layers[selectedLayer];
  select({id:`pad-${slot}-${selectedLayer}`, title:`A${String(slot).padStart(2,'0')} / ${pad.title}`, group:'pad', padSlot:slot,
    description:`${selectedLayer ? 'Hard' : 'Soft'} source sample · MPC velocity zone ${layer.velocityStart}–${layer.velocityEnd}.`,
    audio:layer.audio, nativeAudio:layer.audio, recipeData:layer.recipe, level:pad.level, midi:pad.midi,
    sourceSeconds:pad.sourceSeconds, velocity:layer.sourceVelocity},true);
}
$('#play').addEventListener('click',async () => { if (!current) return; if (audio.paused) { try { if(!audio.src) await select(current,true); else await audio.play(); } catch { message('Press Play once more to begin.'); } } else audio.pause(); });
audio.addEventListener('play',() => { $('#play-icon').textContent = 'Ⅱ'; $('#play').setAttribute('aria-label','Pause selected sound'); });
audio.addEventListener('pause',() => { $('#play-icon').textContent = '▶'; $('#play').setAttribute('aria-label','Play selected sound'); });
audio.addEventListener('timeupdate',updatePosition);
audio.addEventListener('ended',() => { updatePosition(); $('#play-icon').textContent = '▶'; $('#play').setAttribute('aria-label','Play selected sound'); });
$('#seek').addEventListener('input',() => { if(Number.isFinite(audio.duration)) audio.currentTime = Number($('#seek').value)/1000*audio.duration; updatePosition(); });
$('#level').addEventListener('change',() => { if(current) select(current,!audio.paused,audio.currentTime); });
$('#filters').addEventListener('click',(e) => { const b = e.target.closest('[data-filter]'); if(!b) return; $$('#filters button').forEach((x) => pressed(x,x === b)); $$('.preset').forEach((x) => { x.hidden = b.dataset.filter !== 'all' && x.dataset.voice !== b.dataset.filter; }); });
$('#probe-voices').addEventListener('click',(e) => { const b = e.target.closest('[data-probe]'); if(!b) return; selectedVoice = b.dataset.probe; $$('#probe-voices button').forEach((x) => pressed(x,x === b)); renderProbes(); });
$('#pad-touch').addEventListener('click',(e) => { const b = e.target.closest('[data-layer]'); if(!b) return; selectedLayer = Number(b.dataset.layer); $$('#pad-touch button').forEach((x) => pressed(x,x === b)); if(current?.padSlot) playPad(current.padSlot); });
const keys = ['z','x','c','v','a','s','d','f','q','w','e','r','1','2','3','4'];
document.addEventListener('keydown',(e) => { if(e.repeat || e.ctrlKey || e.altKey || e.metaKey || /INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || e.target.isContentEditable || !manifest) return; const slot = keys.indexOf(e.key.toLowerCase())+1; if(slot) { e.preventDefault(); playPad(slot); } });
$('#notes').addEventListener('input',() => { if(!current) return; notes[current.id] = $('#notes').value; try { localStorage.setItem(notesKey,JSON.stringify(notes)); $('#note-status').textContent = 'Saved in this browser.'; } catch { $('#note-status').textContent = 'Storage unavailable. Export notes to keep them.'; } });
$('#export-notes').addEventListener('click',() => { const url = URL.createObjectURL(new Blob([JSON.stringify({engine:'LIBRATION',model:3,notes},null,2)],{type:'application/json'})); const a = document.createElement('a'); a.href = url; a.download = 'libration-in-play-notes.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000); });
async function init() {
  try {
    const response = await fetch('musical-manifest.json'); if(!response.ok) throw Error(); manifest = await response.json();
    for (const [i,c] of manifest.clips.filter((x) => x.group === 'music').entries()) {
      const b = button(c,'','music-card'); const index = document.createElement('span'); index.className='music-index'; index.textContent=['01 / WOOD','02 / GROVES','03 / GLASS'][i];
      const arrow=document.createElement('span'); arrow.className='arrow'; arrow.innerHTML=arrowIcon; arrow.setAttribute('aria-hidden','true');
      const title=document.createElement('strong'); title.textContent=['Bassline','Four chords','A way out'][i]; const sub=document.createElement('small'); sub.textContent=['Root Wood / accents','Round Keys / Twin Grove','Fine Keys / Open Exit'][i];
      const bars=document.createElement('span'); bars.className='mini-bars'; bars.setAttribute('aria-hidden','true'); for(let n=0;n<7;n++) { const bar=document.createElement('i'); bar.style.height=`${7+(n*13+i*7)%26}px`; bars.append(bar); }
      b.append(index,arrow,title,sub,bars); b.setAttribute('aria-label',`Play ${c.title}`); $('#music-grid').append(b);
    }
    for (const c of manifest.clips.filter((x) => x.group === 'bank')) {
      const b=button(c,'','preset'); b.dataset.voice=c.voice; const tag=document.createElement('span'); tag.className='voice-tag'; tag.textContent=names[c.voice].toUpperCase(); const arrow=document.createElement('span'); arrow.className='arrow'; arrow.innerHTML=arrowIcon;
      const title=document.createElement('strong'); title.textContent=c.title; const sub=document.createElement('small'); sub.textContent=`${noteName(c.midi)} / ${c.sourceSeconds}s source`; b.append(tag,arrow,title,sub); b.setAttribute('aria-label',`Play ${c.title}`); $('#bank-grid').append(b);
    }
    renderProbes();
    for (const slot of [13,14,15,16,9,10,11,12,5,6,7,8,1,2,3,4]) {
      const p=manifest.pads.find((x) => x.slot === slot); const b=document.createElement('button'); b.type='button'; b.className='pad'; b.dataset.pad=slot; b.dataset.voice=p.voice; pressed(b,false);
      const label=document.createElement('span'); label.className='pad-label'; label.textContent=`A${String(slot).padStart(2,'0')}`; const k=document.createElement('kbd'); k.textContent=keys[slot-1].toUpperCase(); label.append(k);
      const title=document.createElement('strong'); title.textContent=p.title; const root=document.createElement('small'); root.textContent=noteName(p.midi); const copy=document.createElement('span'); copy.append(title,root); b.append(label,copy); b.setAttribute('aria-label',`Play pad ${slot}, ${p.title}`); b.addEventListener('click',() => playPad(slot)); $('#pad-grid').append(b);
    }
    $('#kit-preview').addEventListener('click',() => select(manifest.clips.find((c) => c.id === 'music-kit'),true));
    const maxPitch=Math.max(...manifest.clips.filter((c) => c.pitchCents != null).map((c) => Math.abs(c.pitchCents)));
    for(const [value,label] of [[12,'Finite presets'],[32,'Native MPC samples'],[`${maxPitch.toFixed(1)}¢`,'Largest measured root error'],['44.1 kHz','Mono WAV / 4× DSP']]) { const d=document.createElement('div'); const strong=document.createElement('strong'); strong.textContent=value; const small=document.createElement('small'); small.textContent=label; d.append(strong,small); $('#stats').append(d); }
    fetch(manifest.profile).then((r) => r.json()).then((p) => { const cold=p.profiles.map((x) => x.coldTotalMs[1]); const warm=p.profiles.map((x) => x.warmRenderMs[1]); $('#profile-summary').textContent=`On this Linux JVM, the three representative presets measured ${Math.min(...cold).toFixed(0)}–${Math.max(...cold).toFixed(0)} ms median cold render and ${Math.min(...warm).toFixed(0)}–${Math.max(...warm).toFixed(0)} ms with physical trajectories cached. Three runs per preset; audio was still synthesized on every run. Phone response remains unmeasured.`; }).catch(() => {});
    await select(manifest.clips.find((c) => c.id === 'music-bass'));
  } catch { message('The listening bank could not load. Reload the page or use the listening-pack download.'); }
}
init();
