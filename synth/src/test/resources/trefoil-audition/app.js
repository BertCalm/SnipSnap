'use strict';

const $ = (id) => document.getElementById(id);
const audio = $('audio');
const state = { manifest: null, id: 'ordered', mode: 'matched', section: 'First listen', sweep: 'WEAVE', trace: null, request: 0, traceRequest: 0, raf: 0 };
const colors = ['#b16d50', '#4f8097', '#a19139'];
const playIcon = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2v12l10-6z"/></svg>';
const pauseIcon = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 2h3v12H3zm7 0h3v12h-3z"/></svg>';
const storageKey = 'snipsnap-trefoil-first-listen-notes-v1';
let notes = {};
try { notes = JSON.parse(localStorage.getItem(storageKey) || '{}'); } catch { notes = {}; }
let statusTimer;
const sections = {
  'First listen': ['Five ways into the weave', 'Listen for the three contributions becoming one changing pitched body.'],
  'Comparisons': ['Change one relationship', 'Compare moving geometry, frozen geometry, disconnected resonators, and sympathetic memory. Each branch keeps its full mix’s gain.'],
  'Stems': ['Listen inside the whole', 'Three additive participant pickups and one shared memory contribution, all at the complete mix’s gain.'],
  'Transfer proof': ['Strike one. Hear the others.', 'Only A is struck. With exchange enabled, B and C can receive energy from the network. Their disconnected clips are intentionally silent.'],
  'Control sweeps': ['One control, five positions', 'The complete gesture at 0, 25, 50, 75, and 100 percent. Each clip uses the same audition level target.'],
  'Notes & touch': ['Keep the movement. Change the note.', 'Low, middle, and high notes at three playing strengths. The underlying trajectory stays the same.'],
  'Extremes': ['At the edges', 'Fast contact and maximum controls test how far the pitched body holds together.']
};
const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const current = () => state.manifest?.clips.find((c) => c.id === state.id);
const clock = (s) => { s = Number.isFinite(s) ? Math.max(0, s) : 0; return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`; };
function announce(message, persistent = false) {
  clearTimeout(statusTimer); $('status').textContent = message; $('status').classList.add('visible');
  if (!persistent) statusTimer = setTimeout(() => $('status').classList.remove('visible'), 5000);
}
function waveform(values) {
  const max = Math.max(...values, 1e-12);
  return `<svg class="mini-wave" viewBox="0 0 200 48" aria-hidden="true">${values.map((v, i) => {
    const h = Math.max(1, 42 * v / max); return `<rect x="${i * 2}" y="${24 - h / 2}" width="1" height="${h}"/>`;
  }).join('')}</svg>`;
}
function renderTabs() {
  $('tabs').innerHTML = Object.keys(sections).map((name, i) => `<button role="tab" id="tab-${i}" aria-controls="clips" aria-selected="${name === state.section}" tabindex="${name === state.section ? 0 : -1}" data-section="${escapeHtml(name)}">${escapeHtml(name === 'Notes & touch' ? 'Notes & touch' : name)}</button>`).join('');
}
function renderClips() {
  const [title, description] = sections[state.section];
  $('collection-label').textContent = state.section.toUpperCase();
  $('collection-title').textContent = title; $('collection-description').textContent = description;
  const clips = state.manifest.clips.filter((c) => c.section === state.section && (state.section !== 'Control sweeps' || c.sweep === state.sweep));
  $('clip-count').textContent = `${clips.length} recordings`;
  const controls = state.section === 'Control sweeps' ? `<div class="sweep-select" aria-label="Control to compare">${['WEAVE', 'NUDGE', 'EXCHANGE', 'THREAD', 'MEMORY'].map((k) => `<button class="${k === state.sweep ? 'active' : ''}" aria-pressed="${k === state.sweep}" data-sweep="${k}">${k}</button>`).join('')}</div>` : '';
  $('clips').innerHTML = controls + clips.map((c) => `<article class="clip ${c.id === state.id ? 'active' : ''}" data-id="${c.id}">
    <button class="clip-play" data-play="${c.id}" aria-label="${c.id === state.id && !audio.paused ? 'Pause' : 'Play'} ${escapeHtml(c.title)}">${c.id === state.id && !audio.paused ? pauseIcon : playIcon}</button>
    <div><button class="clip-title" data-select="${c.id}">${escapeHtml(c.title)}</button><p class="clip-desc">${escapeHtml(c.description)}</p><div class="clip-meta"><span>${escapeHtml(c.note)}</span><span>${Math.round(c.velocity * 100)}% touch</span>${c.reference ? '<span>Shared gain</span>' : ''}${c.id.startsWith('transfer-off-') && !c.id.endsWith('-0') ? '<span>Silent receiver</span>' : ''}</div></div>
    ${waveform(c.waveform)}<span class="clip-duration">${c.seconds.toFixed(1)} s</span></article>`).join('');
  $('clips').setAttribute('aria-labelledby', `tab-${Object.keys(sections).indexOf(state.section)}`);
}
function changeSection(section, scroll = false) {
  state.section = section; renderTabs(); renderClips();
  if (scroll) $('library').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' });
}
function format(n) {
  if (n === null || n === undefined || !Number.isFinite(Number(n))) return '—';
  return Math.abs(n) < .0001 && n !== 0 ? Number(n).toExponential(2) : Number(n).toLocaleString(undefined, { maximumFractionDigits: 4 });
}
function updateDetails(c) {
  $('notebook-title').textContent = c.title; $('listen-for').textContent = c.question;
  $('sound-meta').innerHTML = `<span>${escapeHtml(c.note)}</span><span>${c.seconds.toFixed(1)} seconds</span><span>${escapeHtml(c.voice)}</span>`;
  $('verdict').value = notes[c.id]?.verdict || ''; $('note').value = notes[c.id]?.note || '';
  $('recipe-link').href = c.recipe; $('wav-link').href = c[state.mode];
  $('diagnostics-link').href = c.diagnostics; $('trace-link').href = c.trajectory;
  $('evidence-title').textContent = c.title;
  const m = c.metrics;
  const rows = [['Trajectory observation', m.trajectoryOutcome], ['Scaled energy drift', format(m.scaledEnergyDrift)],
    ['Solver evaluations', format(m.solverEvaluations)], ['Minimum separation', format(m.minSeparation)],
    ['Peak core energy', format(m.coreMaxEnergy)], ['Positive excitation work', format(m.positiveWork)],
    ['Unforced energy rise', format(m.unforcedRise)], ['Detected pitch, Hz', format(m.pitchHz)],
    ['Pitch confidence', format(m.pitchConfidence)], ['1.2 s prefix classifier', m.onsetClassifier], ['Whole-gesture classifier', m.lengthNeutralClassifier], ['Render time, ms', format(m.renderMs)]];
  $('metrics').innerHTML = rows.map(([key, value]) => `<dt>${escapeHtml(key)}</dt><dd>${escapeHtml(value ?? '—')}</dd>`).join('');
  $('gain-policy').textContent = m.gainPolicy;
  const p = state.manifest.provenance;
  $('source-note').textContent = `Source ${p.revision} · ${p.dirtyAtRender ? 'Input fingerprints recorded with working changes' : 'Clean source revision'} · ${p.renderer}`;
  $('now-title').textContent = c.title; $('now-subtitle').textContent = `${c.note} · ${state.mode === 'matched' ? 'Matched level' : 'Raw level'} · finite recording`;
  $('seek').max = c.seconds; $('duration').textContent = clock(c.seconds);
}
async function loadTrace(c) {
  const token = ++state.traceRequest;
  try {
    const response = await fetch(c.trajectory); if (!response.ok) throw new Error('Trajectory unavailable');
    const trace = await response.json(); if (token !== state.traceRequest) return;
    state.trace = trace; drawOrbit();
  } catch { if (token === state.traceRequest) { state.trace = null; $('orbit-caption').textContent = 'Trajectory could not be loaded; audio remains available.'; } }
}
function drawOrbit() {
  const canvas = $('orbit'), ctx = canvas.getContext('2d');
  const width = canvas.clientWidth, height = canvas.clientHeight;
  if (!width || !height) return;
  const ratio = Math.min(devicePixelRatio || 1, 2);
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) { canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio); }
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0); ctx.clearRect(0, 0, width, height);
  ctx.strokeStyle = '#d2d9c9'; ctx.lineWidth = .7;
  for (let x = 30; x < width; x += 35) { ctx.beginPath(); ctx.moveTo(x, 12); ctx.lineTo(x, height - 12); ctx.stroke(); }
  for (let y = 25; y < height; y += 35) { ctx.beginPath(); ctx.moveTo(12, y); ctx.lineTo(width - 12, y); ctx.stroke(); }
  if (!state.trace?.frames?.length) return;
  const frames = state.trace.frames;
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const f of frames) for (let b = 0; b < 3; b++) { minX = Math.min(minX, f[1 + b * 2]); maxX = Math.max(maxX, f[1 + b * 2]); minY = Math.min(minY, f[2 + b * 2]); maxY = Math.max(maxY, f[2 + b * 2]); }
  const scale = Math.min((width - 75) / Math.max(maxX - minX, .1), (height - 55) / Math.max(maxY - minY, .5));
  const px = (x) => width / 2 + (x - (maxX + minX) / 2) * scale;
  const py = (y) => height / 2 - (y - (maxY + minY) / 2) * scale;
  for (let b = 0; b < 3; b++) {
    ctx.beginPath(); ctx.strokeStyle = colors[b]; ctx.lineWidth = .9; ctx.globalAlpha = .27;
    frames.forEach((f, i) => { if (i === 0) ctx.moveTo(px(f[1 + b * 2]), py(f[2 + b * 2])); else ctx.lineTo(px(f[1 + b * 2]), py(f[2 + b * 2])); }); ctx.stroke();
  }
  ctx.globalAlpha = 1;
  const now = state.trace.frozen ? 0 : audio.currentTime;
  let at = frames.findIndex((f) => f[0] >= now); if (at < 0) at = frames.length - 1;
  const prev = Math.max(0, at - 1), a = frames[prev], z = frames[at];
  const blend = a === z ? 0 : Math.min(1, Math.max(0, (now - a[0]) / (z[0] - a[0])));
  const positions = colors.map((color, b) => [px(a[1 + b * 2] + (z[1 + b * 2] - a[1 + b * 2]) * blend), py(a[2 + b * 2] + (z[2 + b * 2] - a[2 + b * 2]) * blend), color]);
  ctx.strokeStyle = '#9cae8c'; ctx.lineWidth = .7; ctx.setLineDash([3, 4]);
  for (let b = 0; b < 3; b++) { ctx.beginPath(); ctx.moveTo(...positions[b].slice(0, 2)); ctx.lineTo(...positions[(b + 1) % 3].slice(0, 2)); ctx.stroke(); }
  ctx.setLineDash([]);
  positions.forEach(([x, y, color], b) => { ctx.beginPath(); ctx.arc(x, y, 6, 0, Math.PI * 2); ctx.fillStyle = color; ctx.fill(); ctx.strokeStyle = '#f5f3eb'; ctx.lineWidth = 2; ctx.stroke(); ctx.font = '10px Arial'; ctx.fillStyle = '#53634c'; ctx.fillText('ABC'[b], x + 10, y - 8); });
  $('orbit-time').textContent = `${audio.currentTime.toFixed(2)} s`;
  $('orbit-caption').textContent = state.trace.frozen ? 'Frozen descriptor snapshot. The reference path is shown faintly.' : 'Measured paths and positions, synchronized to this recording.';
}
function updatePlayback() {
  const playing = !audio.paused && !audio.ended;
  $('transport-play').innerHTML = playing ? pauseIcon : playIcon; $('transport-play').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} selected recording`);
  document.querySelectorAll('[data-play]').forEach((b) => { const active = b.dataset.play === state.id && playing; b.innerHTML = active ? pauseIcon : playIcon; b.setAttribute('aria-label', `${active ? 'Pause' : 'Play'} ${state.manifest.clips.find((c) => c.id === b.dataset.play).title}`); });
  $('seek').value = Number.isFinite(audio.currentTime) ? audio.currentTime : 0; $('elapsed').textContent = clock(audio.currentTime); drawOrbit();
  cancelAnimationFrame(state.raf); if (playing) state.raf = requestAnimationFrame(updatePlayback);
}
function loadAudio(c, play, at = 0) {
  const serial = ++state.request; audio.pause();
  audio.src = c[state.mode]; audio.loop = $('repeat').checked;
  audio.addEventListener('loadedmetadata', () => { if (serial !== state.request) return; audio.currentTime = Math.min(at, Math.max(0, audio.duration - .01)); updatePlayback(); }, { once: true });
  audio.load();
  if (play) audio.play().catch((error) => { if (serial === state.request && error.name !== 'AbortError') announce('Playback could not start. Use the play button again, or download the WAV.'); });
}
function choose(id, play = true) {
  const c = state.manifest.clips.find((item) => item.id === id); if (!c) return;
  const same = id === state.id;
  if (same && play && audio.getAttribute('src') === c[state.mode]) {
    if (!audio.paused) audio.pause(); else { if (audio.ended) audio.currentTime = 0; audio.play().catch(() => announce('Use play again to start this recording.')); }
    updatePlayback(); return;
  }
  state.id = id; updateDetails(c); renderClips(); loadTrace(c); loadAudio(c, play);
}
function saveNotes() {
  if (!current()) return;
  notes[state.id] = { verdict: $('verdict').value, note: $('note').value, title: current().title, updatedAt: new Date().toISOString() };
  try { localStorage.setItem(storageKey, JSON.stringify(notes)); $('note-status').textContent = 'Saved in this browser.'; }
  catch { $('note-status').textContent = 'Browser storage unavailable. Export notes to keep them.'; }
}
$('tabs').addEventListener('click', (e) => { const b = e.target.closest('[data-section]'); if (b) changeSection(b.dataset.section); });
$('tabs').addEventListener('keydown', (e) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
  e.preventDefault(); const names = Object.keys(sections); let i = names.indexOf(state.section);
  i = e.key === 'Home' ? 0 : e.key === 'End' ? names.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + names.length) % names.length;
  changeSection(names[i]); document.querySelector(`[data-section="${names[i]}"]`).focus();
});
$('clips').addEventListener('click', (e) => {
  const play = e.target.closest('[data-play]'), select = e.target.closest('[data-select]'), sweep = e.target.closest('[data-sweep]');
  if (play) choose(play.dataset.play); else if (select) choose(select.dataset.select, false); else if (sweep) { state.sweep = sweep.dataset.sweep; renderClips(); }
});
for (const mode of ['matched', 'raw']) $(mode).addEventListener('click', () => {
  if (state.mode === mode || !current()) return; const at = audio.currentTime, play = !audio.paused; state.mode = mode;
  $('matched').setAttribute('aria-pressed', mode === 'matched'); $('raw').setAttribute('aria-pressed', mode === 'raw');
  $('gain-note').textContent = mode === 'raw' ? 'Raw files use one unchanged pre-leveling gain. They are quieter than some matched files; isolated receivers keep their true level.' : 'Matched playback uses a common audition target. Diagnostic branches and stems keep their reference mix’s gain.';
  updateDetails(current()); loadAudio(current(), play, at);
});
$('begin').addEventListener('click', () => { if (state.manifest) { changeSection('First listen'); choose('ordered'); } });
$('transfer-link').addEventListener('click', () => { if (state.manifest) { changeSection('Transfer proof', true); choose('transfer-on', false); } });
$('transport-play').addEventListener('click', () => { if (current()) choose(state.id); });
$('stop').addEventListener('click', () => { audio.pause(); audio.currentTime = 0; updatePlayback(); });
$('repeat').addEventListener('change', () => { audio.loop = $('repeat').checked; });
$('seek').addEventListener('input', () => { if (audio.readyState) { audio.currentTime = Number($('seek').value); updatePlayback(); } });
$('verdict').addEventListener('change', saveNotes); $('note').addEventListener('input', saveNotes);
$('export-notes').addEventListener('click', () => {
  const payload = { study: 'Trefoil first listen', exportedAt: new Date().toISOString(), sourceRevision: state.manifest?.provenance.revision, notes };
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'trefoil-listening-notes.json'; document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
for (const name of ['play', 'pause', 'ended', 'timeupdate', 'seeked']) audio.addEventListener(name, updatePlayback);
audio.addEventListener('error', () => announce('This audio file could not be loaded. Try another recording or download the starter.'));
document.addEventListener('keydown', (e) => {
  if (e.code === 'Escape') { audio.pause(); audio.currentTime = 0; updatePlayback(); return; }
  if (/INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A/.test(document.activeElement?.tagName || '')) return;
  if (e.code === 'Space' && current()) { e.preventDefault(); choose(state.id); }
});
window.addEventListener('resize', drawOrbit);
async function init() {
  try {
    const response = await fetch('manifest.json'); if (!response.ok) throw new Error(`Manifest HTTP ${response.status}`);
    state.manifest = await response.json(); if (!state.manifest.clips?.length) throw new Error('No recordings found');
    renderTabs(); renderClips(); choose('ordered', false); $('status').classList.remove('visible');
  } catch { announce('The recordings could not be loaded. Refresh this page or download the listening starter.', true); $('clips').innerHTML = '<p class="loading">Listening manifest unavailable. The starter download below contains the recordings.</p>'; }
}
init();
