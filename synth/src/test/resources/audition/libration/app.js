'use strict';
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const audio = new Audio();
audio.preload = 'metadata';
let manifest, current, compareVoice = 'GATHER', selectedAblation = 'FULL', particles = [], loadSequence = 0;
const traceCache = new Map();
const audioCache = new Map();
const names = { GATHER: 'Gather', TWINS: 'Twins', DEPARTURE: 'Departure' };
const colors = { wood: '#e5ae79', glass: '#91cbd1' };
function notify(text = '') { $('#message').textContent = text; }
function pressed(button, yes) { button.classList.toggle('selected', yes); button.setAttribute('aria-pressed', String(yes)); }
function timeLabel(time) { const s = Math.max(0, Math.floor(time || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; }
function baseId(voice, ablation = 'FULL') { return voice.toLowerCase() + (ablation === 'FULL' ? '' : '-' + ablation.toLowerCase()); }
function updatePlay() {
  const playing = !audio.paused && !audio.ended;
  $('#play').setAttribute('aria-label', playing ? 'Pause selected audio' : 'Play selected audio');
  $('#play-icon').innerHTML = playing ? '<path d="M6 5h4v14H6zm8 0h4v14h-4z"/>' : '<path d="M8 5v14l11-7z"/>';
}
function updateTime() {
  $('#elapsed').textContent = timeLabel(audio.currentTime);
  $('#duration').textContent = timeLabel(audio.duration || current?.seconds);
  $('#seek').value = audio.duration ? Math.round(1000 * audio.currentTime / audio.duration) : 0;
}
async function play() {
  if (!current) return;
  if (!audio.paused) audio.pause();
  else { try { await audio.play(); notify(); } catch (e) { notify('Playback could not start. Press play again, or download the WAV below.'); } }
}
async function selectClip(id, { preserve = false, autoplay = false } = {}) {
  const clip = manifest.clips.find((c) => c.id === id);
  if (!clip) return;
  const seq = ++loadSequence;
  const wasPlaying = !audio.paused;
  const position = preserve ? audio.currentTime : 0;
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  $('#play').disabled = true;
  current = clip;
  compareVoice = clip.voice;
  selectedAblation = clip.ablation;
  $('#now-title').textContent = clip.section === 'voices' ? clip.title : `${names[clip.voice]} · ${clip.title}`;
  $('#now-label').textContent = clip.section === 'comparison' ? 'CONTROLLED COMPARISON' : clip.section === 'regions' ? 'REGION STUDY' : clip.section === 'controls' ? 'CONTROL STUDY' : 'SELECTED VOICE';
  $('#now-description').textContent = clip.description;
  $('#plot-label').textContent = `${names[clip.voice].toUpperCase()} / CLOUD MOTION`;
  $('#recipe-link').href = clip.recipe;
  $('#wav-link').href = $('#level').value === 'raw' ? clip.rawAudio : clip.audio;
  $$('[data-voice]').forEach((b) => { const active = b.dataset.voice === clip.voice; b.classList.toggle('active', active); b.setAttribute('aria-pressed', String(active)); });
  $$('[data-compare-voice]').forEach((b) => pressed(b, b.dataset.compareVoice === compareVoice));
  $$('[data-ablation]').forEach((b) => pressed(b, (clip.section === 'voices' || clip.section === 'comparison') && b.dataset.ablation === selectedAblation));
  $$('[data-clip]').forEach((b) => pressed(b, b.dataset.clip === id));
  notify();
  const audioPath = $('#level').value === 'raw' ? clip.rawAudio : clip.audio;
  let audioUrl = audioCache.get(audioPath);
  if (!audioUrl) {
    try {
      const response = await fetch(audioPath);
      if (!response.ok) throw new Error('audio');
      audioUrl = URL.createObjectURL(await response.blob());
      audioCache.set(audioPath, audioUrl);
      while (audioCache.size > 8) { const oldest = audioCache.keys().next().value; URL.revokeObjectURL(audioCache.get(oldest)); audioCache.delete(oldest); }
    } catch (e) {
      if (seq === loadSequence) notify('This audio could not load. Try another example or download the listening pack.');
      return;
    }
  }
  if (seq !== loadSequence) return;
  const onReady = async () => {
    if (seq !== loadSequence) return;
    const target = Math.min(position, Math.max(0, audio.duration - .05));
    if (target > .001) {
      const seeked = new Promise((resolve) => audio.addEventListener('seeked', resolve, { once: true }));
      audio.currentTime = target;
      await seeked;
    }
    if (seq !== loadSequence) return;
    $('#play').disabled = false;
    updateTime();
    if (autoplay || preserve && wasPlaying) {
      try { await audio.play(); } catch (e) { notify('The audio is ready. Press play to listen.'); }
    }
  };
  audio.addEventListener('canplay', onReady, { once: true });
  audio.src = audioUrl;
  audio.load();
  if (traceCache.has(id)) particles = traceCache.get(id);
  else {
    try {
      const response = await fetch(clip.trajectories);
      if (!response.ok) throw new Error('trace');
      const trace = await response.json();
      traceCache.set(id, trace.particles);
      if (seq === loadSequence) particles = trace.particles;
    } catch (e) { if (seq === loadSequence) particles = []; }
  }
  if (seq === loadSequence) drawOrbit();
}
function drawOrbit() {
  const canvas = $('#orbit');
  const rect = canvas.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return;
  const ratio = Math.min(2, window.devicePixelRatio || 1);
  const w = rect.width, h = rect.height;
  if (canvas.width !== Math.round(w * ratio) || canvas.height !== Math.round(h * ratio)) { canvas.width = Math.round(w * ratio); canvas.height = Math.round(h * ratio); }
  const c = canvas.getContext('2d'); c.setTransform(ratio, 0, 0, ratio, 0, 0); c.clearRect(0, 0, w, h);
  const cx = w * .5, cy = h * .5;
  c.strokeStyle = '#b8c5ca0b'; c.lineWidth = 1;
  for (let r = 40; r < Math.max(w, h); r += 46) { c.beginPath(); c.arc(cx, cy, r, 0, 2 * Math.PI); c.stroke(); }
  c.beginPath(); c.moveTo(15, cy); c.lineTo(w - 15, cy); c.moveTo(cx, 10); c.lineTo(cx, h - 10); c.stroke();
  if (!particles.length) { c.fillStyle = '#99a3aa'; c.font = '11px system-ui'; c.textAlign = 'center'; c.fillText('Loading integrated cloud motion…', cx, cy + 25); return; }
  let extentX = 1e-5, extentY = 1e-5;
  for (const p of particles) for (const s of p.trace) { extentX = Math.max(extentX, Math.abs(s[1])); extentY = Math.max(extentY, Math.abs(s[2])); }
  const scale = Math.min((w - 70) / (2 * extentX), (h - 50) / (2 * extentY));
  const time = audio.currentTime || 0;
  for (const p of particles) {
    const color = p.glass ? colors.glass : colors.wood;
    c.strokeStyle = color; c.lineWidth = .8; c.globalAlpha = .12 * Math.min(1, p.weight);
    c.beginPath(); p.trace.forEach((s, i) => { const x = cx + s[1] * scale, y = cy - s[2] * scale; if (i) c.lineTo(x, y); else c.moveTo(x, y); }); c.stroke();
    let index = p.trace.findIndex((s) => s[0] >= time);
    if (index < 0) index = p.trace.length - 1;
    const show = audio.paused && time === 0 ? Math.max(1, Math.floor(p.trace.length * .23)) : index;
    c.globalAlpha = .30 * Math.min(1, p.weight); c.lineWidth = 1.15; c.beginPath();
    for (let i = 0; i <= show; i++) { const s = p.trace[i], x = cx + s[1] * scale, y = cy - s[2] * scale; if (i) c.lineTo(x, y); else c.moveTo(x, y); } c.stroke();
    const s = p.trace[index];
    c.globalAlpha = (.20 + .75 * s[3]) * Math.min(1, p.weight); c.fillStyle = color;
    c.beginPath(); c.arc(cx + s[1] * scale, cy - s[2] * scale, 2.1 + 1.1 * s[4], 0, 2 * Math.PI); c.fill();
  }
  c.globalAlpha = 1;
  c.fillStyle = '#eef0ec'; c.beginPath(); c.arc(cx, cy, 3, 0, 2 * Math.PI); c.fill();
  c.strokeStyle = '#eef0ec30'; c.beginPath(); c.arc(cx, cy, 9, 0, 2 * Math.PI); c.stroke();
  $('#plot-note').textContent = current?.ablation === 'FROZEN' || current?.ablation === 'PITCH_ONLY' ? 'Underlying path · some descriptors frozen in audio' : 'Displacement from each equilibrium';
}
function animate() { if (!audio.paused) { updateTime(); drawOrbit(); requestAnimationFrame(animate); } }
audio.addEventListener('play', () => { updatePlay(); requestAnimationFrame(animate); });
audio.addEventListener('pause', updatePlay);
audio.addEventListener('ended', () => { updatePlay(); updateTime(); drawOrbit(); });
audio.addEventListener('timeupdate', updateTime);
audio.addEventListener('error', () => notify('This audio could not load. Try another example or download the listening pack.'));
$('#play').addEventListener('click', play);
$('#seek').addEventListener('input', (e) => { if (audio.duration) { audio.currentTime = Number(e.target.value) / 1000 * audio.duration; updateTime(); drawOrbit(); } });
$('#level').addEventListener('change', () => { if (current) selectClip(current.id, { preserve: true }); });
$$('[data-voice]').forEach((b) => b.addEventListener('click', () => selectClip(baseId(b.dataset.voice), { autoplay: true })));
$$('[data-compare-voice]').forEach((b) => b.addEventListener('click', () => { compareVoice = b.dataset.compareVoice; selectClip(baseId(compareVoice, selectedAblation), { preserve: true }); }));
$$('[data-ablation]').forEach((b) => b.addEventListener('click', () => selectClip(baseId(compareVoice, b.dataset.ablation), { preserve: true, autoplay: audio.paused })));
const resize = new ResizeObserver(drawOrbit); resize.observe($('#orbit'));
function clipButton(clip, label, className = '') {
  const b = document.createElement('button'); b.type = 'button'; b.dataset.clip = clip.id; b.className = className; b.textContent = label;
  b.setAttribute('aria-pressed', 'false'); b.setAttribute('aria-label', `Play ${clip.title}`);
  b.addEventListener('click', () => selectClip(clip.id, { autoplay: true })); return b;
}
const notesKey = 'snipsnap-libration-model-1-notes';
try { $('#notes').value = localStorage.getItem(notesKey) || ''; } catch (e) { $('#notes-status').textContent = 'Browser storage unavailable; export to keep your notes.'; }
$('#notes').addEventListener('input', () => { try { localStorage.setItem(notesKey, $('#notes').value); $('#notes-status').textContent = 'Saved in this browser only.'; } catch (e) { $('#notes-status').textContent = 'Export to keep your notes.'; } });
$('#export-notes').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify({ study: `Libration model ${manifest?.model}`, notes: $('#notes').value, selectedClip: current?.id, exportedAt: new Date().toISOString() }, null, 2)], { type: 'application/json' });
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'libration-listening-notes.json'; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
});
async function start() {
  try {
    const response = await fetch('manifest.json'); if (!response.ok) throw new Error('manifest');
    manifest = await response.json();
    $('#plot-links').hidden = !manifest.hasPlots;
    for (const clip of manifest.clips.filter((c) => c.section === 'regions')) {
      const b = clipButton(clip, clip.title.split(' · ')[0], clip.value * 4 % 1 ? 'blend' : '');
      const small = document.createElement('small'); small.textContent = `${Number((clip.value * 100).toFixed(1))}%`; b.append(small); $('#region-options').append(b);
    }
    const controls = [ ['NUDGE', 'Cloud leaves later → earlier · steady root stays'], ['RESONANCE', 'Source ends at 0:02 · listen to the after-ring'], ['WANDER', 'Excursion size · Gather'], ['SWARM', 'Population · Twins'] ];
    for (const [name, description] of controls) {
      const card = document.createElement('div'); card.className = 'control-card';
      const text = document.createElement('div'), h = document.createElement('h3'), p = document.createElement('p'); h.textContent = name; p.textContent = description; text.append(h, p);
      const steps = document.createElement('div'); steps.className = 'steps'; steps.setAttribute('role', 'group'); steps.setAttribute('aria-label', `${name} comparisons`);
      manifest.clips.filter((c) => c.control === name).forEach((clip) => steps.append(clipButton(clip, `${Math.round(clip.value * 100)}%`)));
      card.append(text, steps); $('#control-options').append(card);
    }
    for (const [id, name] of [['twins-midi-48', 'C3'], ['twins', 'C4'], ['twins-midi-72', 'C5']]) $('#root-options').append(clipButton(manifest.clips.find((c) => c.id === id), name));
    const drift = Math.max(...manifest.clips.map((c) => c.diagnostics.jacobiDrift));
    const pitch = Math.max(...manifest.clips.map((c) => Math.abs(c.diagnostics.pitchCents)));
    for (const [value, label] of [[manifest.clips.length, 'Actual rendered examples'], [drift.toExponential(1), 'Maximum relative Jacobi drift'], [`${pitch.toFixed(1)}¢`, 'Largest measured root error'], ['44.1 kHz', 'Mono WAV · 4× internal DSP']]) {
      const div = document.createElement('div'), strong = document.createElement('strong'), span = document.createElement('span'); strong.textContent = value; span.textContent = label; div.append(strong, span); $('#evidence-stats').append(div);
    }
    $('#play').disabled = false;
    await selectClip('nudge-0');
  } catch (e) {
    $('#now-description').textContent = 'The study data could not load.';
    notify('Reload the page to try again. You can also download the complete listening pack from the header.');
  }
}
start();
