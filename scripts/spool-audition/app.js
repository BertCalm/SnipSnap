'use strict';
(() => {
  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const audio = $('#audio');
  const MACROS = ['HEAD', 'STRAIN', 'RETURN', 'IMPRINT', 'SPLICE'];
  const VOICES = ['STRING', 'CHOIR', 'FLUTE', 'BRASS', 'PALIMPSEST', 'CUT'];
  const VOICE_NAMES = { STRING: 'Soft Frame', CHOIR: 'Returning Choir', FLUTE: 'Flute Memory', BRASS: 'Worn Brass', PALIMPSEST: 'Ghost Copy', CUT: 'Short Splice' };
  const MACRO_COPY = {
    HEAD: ['Aligned pickup becomes a diagonal encounter through track and recorded time.', 'Aligned', 'Diagonal'],
    STRAIN: ['Both directions share gentle tape wow and flutter. Raising STRAIN adds hesitation, stretch, and recovery.', 'Gentle flutter', 'Competing'],
    RETURN: ['Listen for the backward swell rising through the held forward tone. Raising RETURN brings that reversed layer into focus.', 'Silent', 'Pronounced'],
    IMPRINT: ['A delayed neighboring trace is softly written, then heard during a finite reread.', 'Original', 'Inherited'],
    SPLICE: ['The continuous recording becomes a deterministic sequence of overlapped, reordered sections.', 'Continuous', 'Reordered']
  };
  const PAIR_COPY = {
    head_imprint: 'Head position changes where an inherited trace is heard.',
    strain_return: 'Competing strip loads shape the overlapping backward layer.',
    return_imprint: 'The backward layer becomes material for a quieter inherited trace.',
    head_splice: 'A diagonal encounter meets a reordered recording.',
    strain_splice: 'Transport hesitation stretches the spaces between splices.'
  };
  const EXTRA_NAMES = { 'voice-comparison': 'Voice comparison', 'transition-comparison': 'Before / after', registers: 'Registers', velocity: 'Velocity', gates: 'Gates', extremes: 'All high', diagnostics: 'Diagnostics', 'repeated-loops': 'Repeated loops', kit: 'Kit' };
  const EXTRA_COPY = {
    'voice-comparison': 'Four source families share identical controls and velocity. Each C3, C4 or C5 group shares a root note; Matched uses the common listening level. Listen for bowed STRING, fixed-vowel CHOIR, airy FLUTE, and bright BRASS.',
    'transition-comparison': 'Before is the previous reverse-swell and flutter version. After gives STRING, CHOIR, FLUTE, and BRASS clearer source characters while keeping the shared tape movement. Each pair keeps the same voice and one-shot, loop, or three-cycle form.',
    diagnostics: 'Hear the forward and backward pickups on their own. Start with a backward pickup, then return to the full voice to hear its swell in the mix.'
  };
  const state = { manifest: null, clips: [], selected: null, level: 'matched', macro: 'HEAD', pair: '', extra: 'voice-comparison', repeat: false, notes: readNotes(), loading: false, request: 0 };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (s) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[s]));
  const clamp = (n, low = 0, high = 1) => Math.min(high, Math.max(low, Number(n) || 0));
  const percent = (n) => Math.round(clamp(n) * 100);
  const time = (seconds) => { const n = Math.max(0, Math.floor(Number(seconds) || 0)); return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`; };
  const duration = (clip) => `${Number(clip.seconds || 0).toFixed(1)}s`;
  const noteName = (midi) => { const n = Math.round(Number(midi)); return Number.isFinite(n) ? `${['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((n % 12) + 12) % 12]}${Math.floor(n / 12) - 1}` : 'C4'; };
  const macroValue = (clip, macro) => Number(clip.macros?.[macro] ?? clip.macros?.[macro.toLowerCase()] ?? 0);
  const playIcon = '<span class="play-triangle play-glyph" aria-hidden="true"></span>';
  const clipButton = (clip, text = 'Play') => `<button class="clip-play" data-clip="${esc(clip.id)}" aria-label="Play ${esc(clip.label)}"><span class="play-circle">${playIcon}</span><span class="play-label">${esc(text)}</span></button>`;
  const empty = (copy) => `<p class="empty-state">${esc(copy)}</p>`;

  function safeUrl(path) {
    if (!path || typeof path !== 'string') return null;
    try { const url = new URL(path, document.baseURI); return ['http:', 'https:', 'file:'].includes(url.protocol) ? url.href : null; } catch { return null; }
  }
  function pathFor(clip) { return state.level === 'raw' ? (clip.rawPath || clip.matchedPath || clip.file) : (clip.matchedPath || clip.file || clip.rawPath); }
  function readNotes() { try { return JSON.parse(localStorage.getItem('spool-listening-notes-v1') || '{}') || {}; } catch { return {}; } }
  function noteKey() { return state.selected?.id || '_general'; }
  function saveNotes() {
    state.notes[noteKey()] = { label: state.selected?.label || 'General review', text: $('#listening-notes').value, updated: new Date().toISOString() };
    try { localStorage.setItem('spool-listening-notes-v1', JSON.stringify(state.notes)); $('#notes-status').textContent = 'Saved locally in this browser'; }
    catch { $('#notes-status').textContent = 'Browser storage unavailable — export to keep your notes'; }
  }
  function showNotes() {
    $('#notes-clip-name').textContent = `/ ${state.selected?.label || 'General review'}`;
    $('#listening-notes').value = state.notes[noteKey()]?.text || '';
  }

  function tapeMark(index) {
    return `<div class="tape-mark" aria-hidden="true">${Array.from({ length: 69 }, (_, i) => `<i style="--height:${4 + ((i * 19 + index * 11) % 23)}px"></i>`).join('')}</div>`;
  }
  function renderVoices() {
    const clips = state.clips.filter((c) => c.section === 'voices').sort((a, b) => VOICES.indexOf(a.voice) - VOICES.indexOf(b.voice));
    $('#voice-grid').innerHTML = clips.length ? clips.map((clip, index) => `<article class="voice-card" data-card-id="${esc(clip.id)}"><div class="voice-card-top"><span class="voice-card-number">${String(index + 1).padStart(2, '0')}</span><span>${esc(clip.voice)}</span></div><h3>${esc(clip.label || VOICE_NAMES[clip.voice])}</h3><p class="voice-description">${esc(clip.description)}</p>${tapeMark(index)}<div class="voice-play-row">${clipButton(clip)}<span class="clip-time">${duration(clip)} · ${noteName(clip.midi)}</span></div><div class="voice-macros">${MACROS.map((macro) => `<div class="mini-macro" title="${macro} ${percent(macroValue(clip, macro))}%"><span class="mini-macro-label">${macro}</span><span class="mini-macro-track"><i style="--value:${percent(macroValue(clip, macro))}%"></i></span></div>`).join('')}</div></article>`).join('') : empty('The voice recordings are not present in this audition pack.');
    if (clips[0]) { $('#hero-play').disabled = false; $('#hero-play').dataset.clip = clips[0].id; $('#hero-play').innerHTML = `${playIcon} <span class="play-label">Hear ${esc(clips[0].label)}</span>`; $('#hero-play').setAttribute('aria-label', `Play ${clips[0].label}`); }
  }
  function renderMacros() {
    $('#macro-tabs').innerHTML = MACROS.map((macro) => `<button role="tab" id="tab-${macro.toLowerCase()}" data-macro="${macro}" aria-selected="${macro === state.macro}" aria-controls="macro-panel" tabindex="${macro === state.macro ? 0 : -1}">${macro}</button>`).join('');
    const [copy, low, high] = MACRO_COPY[state.macro];
    $('#macro-index').textContent = `CONTROL ${String(MACROS.indexOf(state.macro) + 1).padStart(2, '0')}`;
    $('#macro-title').textContent = state.macro;
    $('#macro-description').textContent = copy;
    $('#macro-low').textContent = low;
    $('#macro-high').textContent = high;
    $('#macro-panel').setAttribute('aria-labelledby', `tab-${state.macro.toLowerCase()}`);
    const clips = state.clips.filter((c) => c.section === 'macros' && String(c.macro || '').toUpperCase() === state.macro).sort((a, b) => Number(a.value) - Number(b.value));
    $('#sweep-grid').innerHTML = clips.length ? clips.map((clip) => `<article class="sweep-point" data-card-id="${esc(clip.id)}"><div class="sweep-value">${percent(clip.value)}<span>%</span></div><div class="sweep-meter" aria-hidden="true"><i style="--value:${percent(clip.value)}%"></i></div>${clipButton(clip)}<span class="clip-time">${duration(clip)} · ${esc(clip.voice)}</span></article>`).join('') : empty('This macro sweep is not present in the audition pack.');
    updateButtons();
  }
  function renderInteractions() {
    const clips = state.clips.filter((c) => c.section === 'interactions');
    const groups = [...new Set(clips.map((c) => c.groupKey).filter(Boolean))];
    if (!groups.includes(state.pair)) state.pair = groups[0] || '';
    $('#interaction-select').innerHTML = groups.map((group) => `<option value="${esc(group)}" ${group === state.pair ? 'selected' : ''}>${esc(group.split('_').join(' × ').toUpperCase())}</option>`).join('');
    const [x = 'HEAD', y = 'IMPRINT'] = state.pair.toUpperCase().split('_');
    $('#interaction-x').textContent = `${x} →`;
    $('#interaction-y').textContent = `${y} →`;
    $('#interaction-description').textContent = PAIR_COPY[state.pair] || 'Listen to how two controls change the same recorded gesture.';
    const groupClips = clips.filter((c) => c.groupKey === state.pair).sort((a, b) => macroValue(b, y) - macroValue(a, y) || macroValue(a, x) - macroValue(b, x));
    $('#interaction-grid').innerHTML = groupClips.length ? groupClips.map((clip) => `<button class="interaction-cell" data-clip="${esc(clip.id)}" data-card-id="${esc(clip.id)}" aria-label="Play ${esc(x)} ${percent(macroValue(clip, x))} percent, ${esc(y)} ${percent(macroValue(clip, y))} percent" title="${esc(x)} ${percent(macroValue(clip, x))}% / ${esc(y)} ${percent(macroValue(clip, y))}%">${playIcon}<span class="cell-values">${percent(macroValue(clip, x))} / ${percent(macroValue(clip, y))}</span></button>`).join('') : empty('Interaction recordings are not present.');
    updateButtons();
  }
  function renderLoops() {
    const clips = state.clips.filter((c) => c.section === 'loops');
    $('#loop-grid').innerHTML = clips.length ? clips.map((clip) => `<article class="loop-card" data-card-id="${esc(clip.id)}"><div class="loop-card-top"><span>${esc(clip.voice)}</span><span aria-hidden="true">↻ HOLD</span></div><h3>${esc(VOICE_NAMES[clip.voice] || clip.voice)}</h3>${clipButton(clip, 'Play loop')}<div class="loop-details"><span>${duration(clip)} CYCLE</span><span>${Number.isFinite(Number(clip.seamError)) && clip.seamError != null ? `SEAM ${Number(clip.seamError).toExponential(1)}` : 'SNAPSHOT'}</span></div></article>`).join('') : empty('HOLD loops are not present in this audition pack.');
  }
  function renderExtras() {
    const sections = Object.keys(EXTRA_NAMES).filter((section) => state.clips.some((c) => c.section === section));
    if (!sections.includes(state.extra)) state.extra = sections[0] || '';
    $('#extra-filters').innerHTML = sections.map((section) => `<button data-extra="${section}" class="${section === state.extra ? 'is-active' : ''}" aria-pressed="${section === state.extra}">${EXTRA_NAMES[section]}</button>`).join('');
    let context = $('#extra-context');
    if (!context) { context = document.createElement('p'); context.id = 'extra-context'; context.className = 'small-muted'; context.style.marginBottom = '16px'; $('#extra-clips').before(context); }
    context.hidden = !EXTRA_COPY[state.extra];
    context.textContent = EXTRA_COPY[state.extra] || '';
    const clips = state.clips.filter((c) => c.section === state.extra);
    if (state.extra === 'voice-comparison') clips.sort((a, b) => Number(a.midi) - Number(b.midi) || VOICES.indexOf(a.voice) - VOICES.indexOf(b.voice));
    $('#extra-clips').innerHTML = clips.length ? clips.map((clip) => `<div class="extra-clip" data-card-id="${esc(clip.id)}">${clipButton(clip, '')}<div class="extra-clip-copy"><h4>${esc(clip.label)}</h4><p>${duration(clip)} · ${noteName(clip.midi)} · VEL ${Math.round(Number(clip.velocity || 0) * 100)}%</p></div></div>`).join('') : empty('No additional recordings are included.');
    updateButtons();
  }
  function renderDownloads() {
    const downloads = state.manifest.downloads || {};
    const parts = Array.isArray(downloads.audioParts) ? downloads.audioParts.filter((part) => safeUrl(part.path)) : [];
    const audioItems = parts.length ? parts.map((part, index) => [part.path, part.label || `Audio ZIP ${index + 1} of ${parts.length}`, true]) : [[downloads.audio || downloads.audioPack || 'spool-audition-audio.zip', 'Audio pack', true]];
    const items = [...audioItems, [downloads.source || 'spool-engine.patch', 'Source patch', false], [downloads.patches || 'patches.json', 'Preset patches', false]];
    if (downloads.kit || state.manifest.kit?.download) items.push([downloads.kit || state.manifest.kit.download, 'MPC kit', false]);
    $('#download-links').innerHTML = items.filter(([path]) => safeUrl(path)).map(([path, label, primary]) => `<a class="download-link${primary ? ' primary' : ''}" href="${esc(safeUrl(path))}" download>${label}<span aria-hidden="true">↓</span></a>`).join('');
  }
  function formatDb(value) { const n = Number(value); return n > 0 && Number.isFinite(n) ? (20 * Math.log10(n)).toFixed(1) : '−∞'; }
  function renderMetrics() {
    const clip = state.selected;
    if (!clip) { $('#metric-grid').innerHTML = empty('Play a recording to see its measurements.'); $('#diagnostic-details').hidden = true; return; }
    const metrics = (state.level === 'raw' ? clip.rawMetrics : clip.matchedMetrics) || {};
    const hasSeam = clip.seamError != null && Number.isFinite(Number(clip.seamError));
    const rows = [ ['Peak', formatDb(metrics.peak), 'dBFS'], ['RMS', formatDb(metrics.rms), 'dBFS'], ['DC offset', Number.isFinite(Number(metrics.dc)) ? Number(metrics.dc).toExponential(1) : '—', ''], ['Duration', Number(clip.seconds || 0).toFixed(2), 's'], ['Render time', Number(clip.renderMs || 0).toFixed(0), 'ms'], [hasSeam ? 'Loop seam error' : 'Requested root', hasSeam ? Number(clip.seamError).toExponential(1) : noteName(clip.midi), ''] ];
    if (clip.forwardPitch && Number.isFinite(Number(clip.forwardPitch.hz))) rows.push(['Forward pitch', Number(clip.forwardPitch.hz).toFixed(1), 'Hz'], ['Pitch offset', Number(clip.forwardPitch.cents).toFixed(1), 'cents'], ['Root power', (Number(clip.forwardPitch.rootPowerShare) * 100).toFixed(1), '%']);
    if (clip.overlap) rows.push(['Audible overlap', Number(clip.overlap.totalSeconds).toFixed(2), 's'], ['Return with both layers', (Number(clip.overlap.postGateFraction) * 100).toFixed(0), '%']);
    $('#metric-download-links').innerHTML = [[clip.rawPath, 'Raw WAV'], [clip.matchedPath || clip.file, 'Matched WAV'], [clip.stems?.forward?.rawPath, 'Forward pickup WAV'], [clip.stems?.reverse?.rawPath, 'Backward pickup WAV'], [clip.diagnostics?.tracePath, 'Transport trace'], [clip.diagnostics?.pickupTracePath, 'Pickup trace'], [clip.diagnostics?.eventsPath, 'Imprint events']].filter(([path]) => safeUrl(path)).map(([path, label]) => `<a href="${esc(safeUrl(path))}" download>${label} ↓</a>`).join('');
    $('#metric-grid').innerHTML = rows.map(([label, value, unit]) => `<div class="metric"><span class="metric-label">${esc(label)}</span><span class="metric-value">${esc(value)}${unit ? `<small>${esc(unit)}</small>` : ''}</span></div>`).join('');
    $('#measurement-label').textContent = `${clip.label} / ${state.level}`;
    const rawGain = Number(clip.rawExportGain ?? 1);
    $('#measurement-context').textContent = `${clip.description || ''} ${state.level === 'raw' ? `Raw measurements describe native engine output before PCM export${rawGain < 1 ? ` (export gain ${rawGain.toFixed(4)})` : ''}.` : 'Matched files use the common quiet audition level of 0.03 over the loudest 200 ms.'} Listening approval remains a separate step.`;
    $('#measurement-context').classList.add('measurement-context');
    $('#diagnostic-details').hidden = !clip.diagnostics;
    $('#diagnostic-json').textContent = JSON.stringify({ ...(clip.diagnostics || {}), macros: clip.macros, midi: clip.midi, velocity: clip.velocity, rawExportGain: clip.rawExportGain, forwardPitch: clip.forwardPitch, overlap: clip.overlap, stems: clip.stems, provenance: clip.provenance || state.manifest.provenance }, null, 2);
  }
  function updateButtons() {
    const playing = !!state.selected && !audio.paused && !audio.ended;
    $$('[data-card-id]').forEach((card) => { const current = card.dataset.cardId === state.selected?.id; card.classList.toggle('is-selected', current); card.classList.toggle('is-playing', current && playing); });
    $$('[data-clip]').forEach((button) => {
      const clip = state.clips.find((c) => c.id === button.dataset.clip);
      if (!clip) return;
      const active = clip.id === state.selected?.id && playing;
      button.setAttribute('aria-label', `${active ? 'Pause' : 'Play'} ${clip.label}`);
      const glyph = button.querySelector('.play-glyph');
      if (glyph) { glyph.className = active ? 'pause-mark play-glyph' : 'play-triangle play-glyph'; glyph.textContent = active ? 'Ⅱ' : ''; }
      const label = button.querySelector('.play-label');
      if (label && button.id === 'hero-play') label.textContent = `${active ? 'Pause' : 'Hear'} ${clip.label}`;
      else if (label && label.textContent) label.textContent = active ? 'Pause' : clip.loop ? 'Play loop' : 'Play';
    });
    $('#player-play').setAttribute('aria-label', playing ? 'Pause selected recording' : 'Play selected recording');
    $('#player-play').innerHTML = playing ? '<span class="pause-mark" aria-hidden="true">Ⅱ</span>' : playIcon;
    $('#player-repeat').setAttribute('aria-pressed', String(state.repeat));
    $('#player-title').textContent = state.selected?.label || 'Select a recording';
    $('#player-subtitle').textContent = state.selected ? `${state.level.toUpperCase()} · ${noteName(state.selected.midi)} · ${Number(state.manifest.sampleRate || 44100).toLocaleString()} HZ MONO${state.selected.loop ? ' · HOLD' : ''}` : 'Generated WAV';
  }
  function playerError(message) { $('#player-status').textContent = message; $('#player-status').hidden = !message; }
  function setRepeat(repeat) { state.repeat = repeat; audio.loop = repeat; updateButtons(); }
  async function playClip(clip) {
    if (!clip) return;
    playerError('');
    $('#player-shell').hidden = false;
    if (state.selected?.id === clip.id) {
      if (!audio.paused) { audio.pause(); return; }
      if (audio.ended) audio.currentTime = 0;
    } else {
      saveNotes();
      audio.pause();
      state.request++;
      state.selected = clip;
      audio.src = safeUrl(pathFor(clip)) || '';
      setRepeat(!!clip.loop);
      $('#player-current').textContent = '0:00';
      $('#player-duration').textContent = time(clip.seconds);
      $('#player-seek').value = '0';
      showNotes(); renderMetrics(); updateButtons();
    }
    try { await audio.play(); }
    catch (error) { playerError(error.name === 'NotAllowedError' ? 'Playback needs a click. Use the play button to start this recording.' : 'This recording could not be loaded. Try another clip or download the audio pack.'); }
    updateButtons();
  }
  async function changeLevel(level) {
    if (level === state.level) return;
    const wasPlaying = !audio.paused;
    const position = audio.currentTime || 0;
    const request = ++state.request;
    state.level = level;
    $$('[data-level]').forEach((button) => { button.classList.toggle('is-active', button.dataset.level === level); button.setAttribute('aria-pressed', String(button.dataset.level === level)); });
    if (state.selected) {
      const clip = state.selected;
      audio.pause();
      audio.src = safeUrl(pathFor(clip)) || '';
      audio.addEventListener('loadedmetadata', async function seekToPrevious() {
        if (request !== state.request) return;
        audio.currentTime = Math.min(position, Math.max(0, (audio.duration || clip.seconds) - .01));
        if (wasPlaying) { try { await audio.play(); } catch { playerError('Press play to continue with the selected level.'); } }
      }, { once: true });
      audio.load();
      renderMetrics(); updateButtons();
    }
  }

  document.addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.clip) playClip(state.clips.find((c) => c.id === button.dataset.clip));
    else if (button.dataset.level) changeLevel(button.dataset.level);
    else if (button.dataset.macro) { state.macro = button.dataset.macro; renderMacros(); $(`#tab-${state.macro.toLowerCase()}`).focus(); }
    else if (button.dataset.extra) { state.extra = button.dataset.extra; renderExtras(); }
  });
  $('#macro-tabs').addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const index = MACROS.indexOf(state.macro);
    state.macro = event.key === 'Home' ? MACROS[0] : event.key === 'End' ? MACROS.at(-1) : MACROS[(index + (event.key === 'ArrowRight' ? 1 : -1) + MACROS.length) % MACROS.length];
    renderMacros(); $(`#tab-${state.macro.toLowerCase()}`).focus();
  });
  $('#interaction-select').addEventListener('change', (event) => { state.pair = event.target.value; renderInteractions(); });
  $('#player-play').addEventListener('click', () => playClip(state.selected));
  $('#player-stop').addEventListener('click', () => { audio.pause(); audio.currentTime = 0; });
  $('#player-close').addEventListener('click', () => { audio.pause(); $('#player-shell').hidden = true; });
  $('#player-repeat').addEventListener('click', () => setRepeat(!state.repeat));
  $('#player-seek').addEventListener('input', (event) => { if (Number.isFinite(audio.duration) && audio.duration > 0) audio.currentTime = Number(event.target.value) / 1000 * audio.duration; });
  ['play', 'pause', 'ended'].forEach((event) => audio.addEventListener(event, updateButtons));
  audio.addEventListener('timeupdate', () => { $('#player-current').textContent = time(audio.currentTime); if (Number.isFinite(audio.duration) && audio.duration > 0) $('#player-seek').value = String(Math.round(audio.currentTime / audio.duration * 1000)); });
  audio.addEventListener('loadedmetadata', () => { $('#player-duration').textContent = time(audio.duration); playerError(''); });
  audio.addEventListener('error', () => { playerError('This WAV could not be loaded. Try another recording or download the audio pack.'); updateButtons(); });
  $('#listening-notes').addEventListener('input', saveNotes);
  $('#export-notes').addEventListener('click', () => {
    saveNotes();
    const entries = Object.values(state.notes).filter((entry) => typeof entry?.text === 'string' && entry.text.trim());
    const body = `# Spool listening review\n\nExported ${new Date().toISOString()}\n\nSix prototype voices; dry sonic identity awaits owner approval.\n\n${entries.length ? entries.map((entry) => `## ${entry.label}\n\n${entry.text}\n`).join('\n') : 'No listening notes recorded yet.\n'}`;
    const url = URL.createObjectURL(new Blob([body], { type: 'text/markdown' }));
    const link = document.createElement('a'); link.href = url; link.download = 'spool-listening-review.md'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    $('#notes-status').textContent = 'Review notes exported';
  });
  showNotes();
  async function init() {
    try {
      const response = await fetch('manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest HTTP ${response.status}`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('No clips');
      state.manifest = manifest;
      state.clips = manifest.clips.filter((clip) => clip.id && (clip.rawPath || clip.matchedPath || clip.file));
      $('#load-status').textContent = '';
      renderVoices(); renderMacros(); renderInteractions(); renderLoops(); renderExtras(); renderDownloads(); renderMetrics();
    } catch {
      $('#load-status').textContent = 'The audition pack could not be loaded. Reload the page to try again.';
      $('#voice-grid').innerHTML = empty('Recordings are unavailable. The page does not synthesize replacement audio.');
      $('#hero-play').disabled = true;
      $('#download-links').innerHTML = '<a class="download-link" href="https://github.com/BertCalm/SnipSnap" target="_blank" rel="noopener noreferrer">SnipSnap on GitHub <span aria-hidden="true">↗</span></a>';
      renderMacros(); renderInteractions(); renderLoops(); renderExtras();
    }
  }
  init();
})();
