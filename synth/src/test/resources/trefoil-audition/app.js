'use strict';

const $ = (id) => document.getElementById(id);
const audio = $('audio');
const state = { manifest: null, id: 'ordered', mode: 'matched', section: 'First listen', sweep: 'WEAVE', trace: null, request: 0, traceRequest: 0, raf: 0 };
const colors = ['#b16d50', '#4f8097', '#a19139'];
const playIcon = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2v12l10-6z"/></svg>';
const pauseIcon = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 2h3v12H3zm7 0h3v12h-3z"/></svg>';
const storageKey = 'snipsnap-trefoil-model-2-notes-v1';
const pairs = {
  revision: { ids: ['original-ordered', 'ordered'], labels: ['Original sound model', 'Revised sound model'], range: [.4, 3.5], prompt: 'Start here. The same note, touch, trajectory and controls feed the original and revised acoustic models. Compare the body of the note, then try exchange and default memory below.', level: 'Both complete clips use the same loudest-200-ms RMS target. This is a comparison between acoustic models.' },
  motion: { ids: ['unsettled-live', 'unsettled-frozen'], labels: ['Moving geometry', 'Frozen starting geometry'], range: [.4, 3.5], prompt: 'Compare the swell and changing contact through the middle of the note. The perturbed patch is identical; B holds its geometry at the starting snapshot.', level: 'Both branches keep the moving mix’s gain. A level or envelope change is part of this comparison.' },
  transfer: { ids: ['transfer-on-1', 'transfer-off-1'], labels: ['Receiver B · connected', 'Receiver B · disconnected'], range: [0, 1], prompt: 'Only body A was struck. Hear the quiet, unstruck body B with exchange connected, then disconnected. The disconnected receiver is silent. This isolates transfer; it does not establish how prominent exchange is in the whole mix.', level: 'Both receiver recordings keep the complete transfer mix’s gain. This is a quiet isolated stem.' },
  memory: { ids: ['memory-0', 'memory-100'], labels: ['Memory · 0%', 'Memory · 100%'], range: [3.5, 6], prompt: 'Listen after the excitation ends at 3.5 seconds. Compare how long the note hangs on. These endpoints make a wider comparison than the default memory on/off test.', level: 'Each complete clip uses the same loudest-200-ms RMS target. The focus passage receives no extra gain.' },
  'ordered-exchange': { ids: ['ordered-live', 'ordered-independent'], labels: ['Ordered · exchange on', 'Ordered · exchange off'], range: [.4, 3.5], prompt: 'The revised whole mix with exchange on and off. Listen for changes in the upper harmonics and their movement. Both sides continue to receive trajectory-driven excitation.', level: 'Both branches keep the full mix’s gain. No difference signal or enhancement is added.' },
  'ordered-memory': { ids: ['ordered-live', 'ordered-no-memory'], labels: ['Default · memory on', 'Default · memory off'], range: [3.5, 6], prompt: 'Listen after excitation ends at 3.5 seconds. The revised default memory keeps upper harmonics ringing beyond the dry bodies. B removes only that memory contribution.', level: 'Both branches keep the full mix’s gain. The three dry pickups are identical in this pair.' }
};
const deck = { active: false, playing: false, loading: false, side: 0, key: 'revision', context: null, cache: new Map(), buffers: [], sources: [], gains: [], offset: 0, started: 0, runBounds: [0, 6], looping: false, request: 0, generation: 0 };
let notes = {};
try { notes = JSON.parse(localStorage.getItem(storageKey) || '{}'); } catch { notes = {}; }
let statusTimer;
const sections = {
  'First listen': ['Five starting points', 'Five starting points in the revised acoustic model. Judge the body, moving harmonics and lingering tail; the original model remains available below.'],
  'Comparisons': ['Change one relationship', 'Revised whole-mix comparisons at fixed reference gain. Use the guided A/B panel above to switch without restarting.'],
  'Stems': ['Listen inside the whole', 'Three additive participant pickups and one shared memory contribution, all at the complete mix’s gain.'],
  'Transfer proof': ['Strike one. Hear the others.', 'Only A is struck. With exchange enabled, B and C can receive energy from the network. Their disconnected clips are intentionally silent.'],
  'Control sweeps': ['One control, five positions', 'The complete gesture at 0, 25, 50, 75, and 100 percent. Each clip uses the same audition level target.'],
  'Notes & touch': ['Keep the movement. Change the note.', 'Low, middle, and high notes at three playing strengths. The underlying trajectory stays the same.'],
  'Original model': ['The first published sounds', 'Preserved model-one recipes, replayed through the versioned renderer. Your earlier verdict still applies to these originals.'],
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
  const now = state.trace.frozen ? 0 : playbackPosition();
  let at = frames.findIndex((f) => f[0] >= now); if (at < 0) at = frames.length - 1;
  const prev = Math.max(0, at - 1), a = frames[prev], z = frames[at];
  const blend = a === z ? 0 : Math.min(1, Math.max(0, (now - a[0]) / (z[0] - a[0])));
  const positions = colors.map((color, b) => [px(a[1 + b * 2] + (z[1 + b * 2] - a[1 + b * 2]) * blend), py(a[2 + b * 2] + (z[2 + b * 2] - a[2 + b * 2]) * blend), color]);
  ctx.strokeStyle = '#9cae8c'; ctx.lineWidth = .7; ctx.setLineDash([3, 4]);
  for (let b = 0; b < 3; b++) { ctx.beginPath(); ctx.moveTo(...positions[b].slice(0, 2)); ctx.lineTo(...positions[(b + 1) % 3].slice(0, 2)); ctx.stroke(); }
  ctx.setLineDash([]);
  positions.forEach(([x, y, color], b) => { ctx.beginPath(); ctx.arc(x, y, 6, 0, Math.PI * 2); ctx.fillStyle = color; ctx.fill(); ctx.strokeStyle = '#f5f3eb'; ctx.lineWidth = 2; ctx.stroke(); ctx.font = '10px Arial'; ctx.fillStyle = '#53634c'; ctx.fillText('ABC'[b], x + 10, y - 8); });
  $('orbit-time').textContent = `${playbackPosition().toFixed(2)} s`;
  $('orbit-caption').textContent = state.trace.frozen ? 'Frozen descriptor snapshot. The reference path is shown faintly.' : 'Measured paths and positions, synchronized to this recording.';
}
function updatePlayback() {
  const playing = deck.active ? deck.playing : !audio.paused && !audio.ended;
  $('transport-play').innerHTML = playing ? pauseIcon : playIcon; $('transport-play').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} selected recording`);
  document.querySelectorAll('[data-play]').forEach((b) => { const active = b.dataset.play === state.id && playing; b.innerHTML = active ? pauseIcon : playIcon; b.setAttribute('aria-label', `${active ? 'Pause' : 'Play'} ${state.manifest.clips.find((c) => c.id === b.dataset.play).title}`); });
  const at = playbackPosition(), bounds = deck.active ? comparisonBounds() : [0, current()?.seconds || 6];
  $('seek').min = bounds[0]; $('seek').max = bounds[1]; $('seek').value = at; $('elapsed').textContent = clock(at); drawOrbit(); updateCompareControls();
  cancelAnimationFrame(state.raf); if (playing) state.raf = requestAnimationFrame(updatePlayback);
}
function loadAudio(c, play, at = 0) {
  leaveComparison();
  const serial = ++state.request; audio.pause();
  audio.src = c[state.mode]; audio.loop = $('repeat').checked;
  audio.addEventListener('loadedmetadata', () => { if (serial !== state.request) return; audio.currentTime = Math.min(at, Math.max(0, audio.duration - .01)); updatePlayback(); }, { once: true });
  audio.load();
  if (play) audio.play().catch((error) => { if (serial === state.request && error.name !== 'AbortError') announce('Playback could not start. Use the play button again, or download the WAV.'); });
}
function comparisonBounds() { return $('compare-focus').checked ? pairs[deck.key].range : [0, deck.buffers[0]?.duration || current()?.seconds || 6]; }
function playbackPosition() {
  if (!deck.active) return Number.isFinite(audio.currentTime) ? audio.currentTime : 0;
  const [lo, hi] = deck.playing ? deck.runBounds : comparisonBounds();
  let at = deck.offset + (deck.playing ? Math.max(0, deck.context.currentTime - deck.started) : 0);
  if (deck.playing && deck.looping && at >= hi) at = lo + (at - lo) % (hi - lo);
  return Math.min(hi, Math.max(lo, at));
}
function stopCompareSources() {
  deck.generation++;
  for (const source of deck.sources) { source.onended = null; try { source.stop(); } catch {} source.disconnect(); }
  for (const gain of deck.gains) gain.disconnect();
  deck.sources = []; deck.gains = []; deck.playing = false;
}
function leaveComparison() { if (!deck.active) return; deck.request++; stopCompareSources(); deck.active = false; deck.loading = false; updateCompareControls(); }
function updateCompareControls() {
  const pair = pairs[deck.key];
  $('compare-prompt').textContent = pair.prompt; $('compare-level').textContent = `${pair.level} Focus loops use 5 ms edge fades.`;
  $('compare-window').textContent = `(${pair.range[0].toFixed(1)}–${pair.range[1].toFixed(1)} s)`;
  ['a', 'b'].forEach((side, i) => { $(`compare-${side}`).setAttribute('aria-pressed', deck.side === i); $(`compare-${side}`).querySelector('span').textContent = pair.labels[i]; });
  const playing = deck.active && deck.playing;
  $('compare-play').textContent = deck.loading ? 'Loading comparison…' : playing ? 'Pause comparison' : 'Play comparison';
  $('compare-play').disabled = deck.loading;
  const status = deck.loading ? 'Loading both recordings…' : deck.active ? `${deck.side ? 'B' : 'A'} · ${pair.labels[deck.side]} · ${playing ? 'Playing; switch A or B at any time.' : 'Paused. Ready to compare.'}` : 'Ready. Start playback, then switch A and B.';
  if ($('compare-status').textContent !== status) $('compare-status').textContent = status;
}
function showComparisonSide() {
  const c = state.manifest.clips.find((c) => c.id === pairs[deck.key].ids[deck.side]);
  state.id = c.id; updateDetails(c); renderClips(); loadTrace(c); updateCompareControls();
}
function switchComparison(side) {
  deck.side = side;
  if (deck.active) {
    const now = deck.context?.currentTime || 0;
    deck.gains.forEach((gain, i) => { const p = gain.gain; if (p.cancelAndHoldAtTime) p.cancelAndHoldAtTime(now); else { p.cancelScheduledValues(now); p.setValueAtTime(p.value, now); } p.linearRampToValueAtTime(i === side ? 1 : 0, now + .008); });
    showComparisonSide();
  }
  updateCompareControls(); updatePlayback();
}
function startComparison() {
  if (!deck.active || deck.loading || !deck.buffers.length) return;
  const [lo, hi] = comparisonBounds();
  if (deck.offset < lo || deck.offset >= hi - .005) deck.offset = lo;
  stopCompareSources(); const generation = deck.generation;
  deck.runBounds = [lo, hi]; deck.looping = $('compare-focus').checked || $('repeat').checked;
  deck.started = deck.context.currentTime + .02;
  deck.buffers.forEach((buffer, i) => {
    const source = deck.context.createBufferSource(), gain = deck.context.createGain();
    // The selected passages stay aligned. Tiny equal edge fades keep a loop cut
    // from being mistaken for contact in the instrument; original WAVs stay intact.
    const focus = $('compare-focus').checked;
    if (focus) {
      const first = Math.round(lo * buffer.sampleRate), size = Math.round((hi - lo) * buffer.sampleRate);
      const excerpt = deck.context.createBuffer(buffer.numberOfChannels, size, buffer.sampleRate), fade = Math.round(.005 * buffer.sampleRate);
      for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
        const data = excerpt.getChannelData(channel); data.set(buffer.getChannelData(channel).subarray(first, first + size));
        for (let j = 0; j < fade; j++) { const g = .5 - .5 * Math.cos(Math.PI * j / fade); data[j] *= g; data[size - 1 - j] *= g; }
      }
      source.buffer = excerpt;
    } else source.buffer = buffer;
    source.loop = deck.looping; source.loopStart = focus ? 0 : lo; source.loopEnd = focus ? hi - lo : hi;
    gain.gain.value = i === deck.side ? 1 : 0; source.connect(gain); gain.connect(deck.context.destination);
    if (i === 0) source.onended = () => { if (deck.generation !== generation) return; stopCompareSources(); deck.offset = hi; updatePlayback(); };
    deck.sources.push(source); deck.gains.push(gain); source.start(deck.started, focus ? deck.offset - lo : deck.offset);
  });
  deck.playing = true; updatePlayback();
}
async function prepareComparison(play = false, at = null) {
  const token = ++deck.request; stopCompareSources(); deck.active = true; deck.loading = true; deck.buffers = []; state.request++; audio.pause();
  deck.offset = at ?? ($('compare-focus').checked ? pairs[deck.key].range[0] : 0);
  showComparisonSide(); updatePlayback();
  try {
    deck.context ||= new (window.AudioContext || window.webkitAudioContext)();
    if (play) await deck.context.resume();
    const buffers = await Promise.all(pairs[deck.key].ids.map(async (id) => {
      const url = state.manifest.clips.find((c) => c.id === id)[state.mode];
      if (!deck.cache.has(url)) deck.cache.set(url, fetch(url).then((r) => { if (!r.ok) throw new Error('Recording unavailable'); return r.arrayBuffer(); }).then((data) => deck.context.decodeAudioData(data)).catch((e) => { deck.cache.delete(url); throw e; }));
      return deck.cache.get(url);
    }));
    if (token !== deck.request) return;
    deck.buffers = buffers; deck.loading = false;
    if (play) startComparison(); else updatePlayback();
  } catch { if (token === deck.request) { leaveComparison(); updatePlayback(); announce('The A/B recordings could not load. The individual WAV players below are still available.'); } }
}
async function toggleComparison() {
  if (!deck.active) { await prepareComparison(true); return; }
  if (deck.loading) return;
  if (deck.playing) { deck.offset = playbackPosition(); stopCompareSources(); updatePlayback(); }
  else { await deck.context.resume(); startComparison(); }
}
function seekComparison(at) {
  const playing = deck.playing, [lo, hi] = comparisonBounds(); stopCompareSources(); deck.offset = Math.max(lo, Math.min(at, hi));
  if (playing) startComparison(); else updatePlayback();
}
function stopPlayback() {
  if (deck.active) { stopCompareSources(); deck.offset = comparisonBounds()[0]; }
  else { audio.pause(); audio.currentTime = 0; }
  updatePlayback();
}
function choose(id, play = true) {
  const c = state.manifest.clips.find((item) => item.id === id); if (!c) return;
  const same = id === state.id && !deck.active; leaveComparison();
  if (same && play && audio.getAttribute('src') === c[state.mode]) {
    if (!audio.paused) audio.pause(); else { if (audio.ended) audio.currentTime = 0; audio.play().catch(() => announce('Use play again to start this recording.')); }
    updatePlayback(); return;
  }
  state.id = id; updateDetails(c); renderClips(); loadTrace(c); loadAudio(c, play);
}
function saveNotes() {
  if (!current()) return;
  notes[state.id] = { verdict: $('verdict').value, note: $('note').value, title: current().title, updatedAt: new Date().toISOString(), comparison: deck.active ? { pair: deck.key, ids: pairs[deck.key].ids, side: deck.side, mode: state.mode } : null };
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
  if (state.mode === mode || !current()) return; const at = playbackPosition(), play = deck.active ? deck.playing : !audio.paused; state.mode = mode;
  $('matched').setAttribute('aria-pressed', mode === 'matched'); $('raw').setAttribute('aria-pressed', mode === 'raw');
  $('gain-note').textContent = mode === 'raw' ? 'Raw files use one unchanged pre-leveling gain. They are quieter than some matched files; isolated receivers keep their true level.' : 'Matched playback uses a common audition target. Diagnostic branches and stems keep their reference mix’s gain.';
  updateDetails(current()); if (deck.active) prepareComparison(play, at); else loadAudio(current(), play, at);
});
$('compare-play').addEventListener('click', toggleComparison);
$('compare-a').addEventListener('click', () => switchComparison(0)); $('compare-b').addEventListener('click', () => switchComparison(1));
$('compare-pair').addEventListener('change', () => { const active = deck.active; leaveComparison(); deck.key = $('compare-pair').value; deck.side = 0; updateCompareControls(); if (active) prepareComparison(false); });
$('compare-focus').addEventListener('change', () => { if (deck.active) seekComparison(playbackPosition()); updateCompareControls(); });
$('begin').addEventListener('click', () => { if (state.manifest) { changeSection('First listen'); choose('ordered'); } });
$('transfer-link').addEventListener('click', () => { if (state.manifest) { changeSection('Transfer proof', true); choose('transfer-on', false); } });
$('transport-play').addEventListener('click', () => { if (deck.active) toggleComparison(); else if (current()) choose(state.id); });
$('stop').addEventListener('click', stopPlayback);
$('repeat').addEventListener('change', () => { if (deck.active) seekComparison(playbackPosition()); else audio.loop = $('repeat').checked; });
$('seek').addEventListener('input', () => { if (deck.active) seekComparison(Number($('seek').value)); else if (audio.readyState) { audio.currentTime = Number($('seek').value); updatePlayback(); } });
$('verdict').addEventListener('change', saveNotes); $('note').addEventListener('input', saveNotes);
$('export-notes').addEventListener('click', () => {
  const payload = { study: 'Trefoil first listen', exportedAt: new Date().toISOString(), sourceRevision: state.manifest?.provenance.revision, notes };
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'trefoil-listening-notes.json'; document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
for (const name of ['play', 'pause', 'ended', 'timeupdate', 'seeked']) audio.addEventListener(name, updatePlayback);
audio.addEventListener('error', () => announce('This audio file could not be loaded. Try another recording or download the starter.'));
document.addEventListener('keydown', (e) => {
  if (e.code === 'Escape') { stopPlayback(); return; }
  if (deck.active && !e.altKey && !e.ctrlKey && !e.metaKey && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName || '') && ['KeyA', 'KeyB'].includes(e.code)) { e.preventDefault(); switchComparison(e.code === 'KeyB' ? 1 : 0); return; }
  if (/INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A/.test(document.activeElement?.tagName || '')) return;
  if (e.code === 'Space' && current()) { e.preventDefault(); if (deck.active) toggleComparison(); else choose(state.id); }
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
