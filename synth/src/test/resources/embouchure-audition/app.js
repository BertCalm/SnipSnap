/* Static listening bench. Audio is rendered by the Kotlin engine; this page never synthesizes. */
'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const VOICES = ['REED', 'BRASS', 'BASS', 'WHISTLE', 'BOW', 'SOFT'];
  const MACROS = ['EFFORT', 'REED', 'SHAPE', 'GRAIN', 'LENGTH'];
  const MACRO_HELP = {
    EFFORT: 'Gesture intensity: gentle speaking to stronger pressure or contact.',
    REED: 'Contact character: compliant to tighter contact, within the selected driver.',
    SHAPE: 'Resonator and body character.',
    GRAIN: 'Seeded air or friction roughness.',
    LENGTH: 'Finite gesture duration, sustain window and release.'
  };
  const STORAGE_KEY = 'snipsnap-embouchure-listening-v1';
  const PAGE_SIZE = 12;
  const audio = $('audio');
  const canvas = $('waveform');
  const state = { manifest: null, clips: [], selected: null, voice: 'ALL', collection: 'presets', group: 'all', query: '', limit: PAGE_SIZE, mode: 'production', waveform: [], waveformToken: 0, frame: 0, notes: {}, storageAvailable: true, sweeps: new Map(), baselines: new Map(), audioContext: null };

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function numeric(value, fallback = 0) { return Number.isFinite(Number(value)) ? Number(value) : fallback; }
  function macroValue(clip, macro) { return numeric(clip?.macros?.[macro], 0); }
  function isPreset(clip) { return String(clip.group).toUpperCase() === 'PRESETS'; }
  function clipName(clip) { return clip.title || clip.name || clip.id; }
  function voiceName(voice) { return String(voice || 'ENGINE').toUpperCase(); }
  function pretty(text) { return String(text || '').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (s) => s.toUpperCase()); }
  function noteName(clip) { return clip.note || (clip.midi !== undefined ? `MIDI ${clip.midi}` : ''); }
  function velocityLabel(value) { const n = numeric(value); return n <= 1 ? n.toFixed(2) : String(Math.round(n)); }
  function duration(clip) { return numeric(clip.durationSeconds, numeric(clip.frames) / numeric(state.manifest?.sampleRate, 44100)); }
  function time(seconds) { const value = Math.max(0, numeric(seconds)); return `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}`; }
  function pathFor(clip, mode = state.mode) { return mode === 'raw' && clip.rawPath ? clip.rawPath : mode === 'matched' && clip.matchedPath ? clip.matchedPath : clip.path; }
  function valueLabel(value) { return numeric(value).toFixed(2).replace(/^0\./, '.'); }
  function rawModeLabel(clip, detailed = false) {
    const gain = numeric(clip.rawExportGain, 1);
    if (gain > 0 && gain !== 1) {
      const db = Math.round(20 * Math.log10(gain));
      return detailed ? `raw, fixed ${db} dB export` : `Raw ${db} dB`;
    }
    return detailed ? 'raw level' : 'Raw';
  }
  function groupName(id) { return state.manifest?.groups?.find((group) => group.id === id)?.title || pretty(id); }
  function downloadable(data, filename) {
    const blob = new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = element('a'); link.href = url; link.download = filename; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function filteredClips() {
    return state.clips.filter((clip) => {
      if (state.voice !== 'ALL' && voiceName(clip.voice) !== state.voice) return false;
      if (state.collection === 'presets' ? !isPreset(clip) : isPreset(clip)) return false;
      if (state.collection === 'probes' && state.group !== 'all' && clip.group !== state.group) return false;
      if (state.query) {
        const haystack = [clipName(clip), clip.id, clip.voice, clip.preset, clip.description, clip.group, clip.gesture, clip.driver, clip.note, clip.midi, clip.sweep?.macro].join(' ').toLowerCase();
        if (!state.query.split(/\s+/).every((term) => haystack.includes(term))) return false;
      }
      return true;
    });
  }

  function renderVoiceFilters() {
    const filters = $('voice-filters'); filters.replaceChildren();
    ['ALL', ...VOICES].forEach((voice) => {
      const count = state.clips.filter((clip) => isPreset(clip) && (voice === 'ALL' || voiceName(clip.voice) === voice)).length;
      const button = element('button', `voice-filter${state.voice === voice ? ' active' : ''}`);
      button.append(element('span', '', voice === 'ALL' ? 'All voices' : pretty(voice)), element('span', 'filter-count', count));
      button.type = 'button'; button.setAttribute('aria-pressed', String(state.voice === voice));
      button.addEventListener('click', () => {
        state.voice = voice; state.limit = PAGE_SIZE; renderVoiceFilters(); renderLibrary();
        if (voice !== 'ALL' && voiceName(state.selected?.voice) !== voice) {
          const next = state.clips.find((clip) => isPreset(clip) && voiceName(clip.voice) === voice) || state.baselines.get(voice) || state.clips.find((clip) => voiceName(clip.voice) === voice);
          if (next) selectClip(next);
        }
      });
      filters.append(button);
    });
  }

  function renderLibrary() {
    const clips = filteredClips();
    const list = $('clip-list'); list.replaceChildren();
    $('collection-count').textContent = String(clips.length).padStart(2, '0');
    $('presets-tab').classList.toggle('active', state.collection === 'presets');
    $('probes-tab').classList.toggle('active', state.collection === 'probes');
    $('presets-tab').setAttribute('aria-pressed', String(state.collection === 'presets'));
    $('probes-tab').setAttribute('aria-pressed', String(state.collection === 'probes'));
    $('group-control').hidden = state.collection !== 'probes';
    clips.slice(0, state.limit).forEach((clip, index) => {
      const selected = state.selected?.id === clip.id;
      const button = element('button', `clip-row${selected ? ' active' : ''}`);
      button.type = 'button'; button.setAttribute('aria-pressed', String(selected));
      const copy = element('span', 'clip-row-copy');
      copy.append(element('span', 'clip-row-name', clipName(clip)));
      copy.append(element('span', 'clip-row-meta', [voiceName(clip.voice), noteName(clip), clip.loop ? 'HOLD' : `${duration(clip).toFixed(1)}s`].filter(Boolean).join(' / ')));
      button.append(element('span', 'clip-number', String(index + 1).padStart(2, '0')), copy, element('span', 'clip-row-arrow', '→'));
      button.addEventListener('click', () => selectClip(clip)); list.append(button);
    });
    if (!clips.length) list.append(element('p', 'empty-state', state.query ? 'No renders match this search. Try another voice or a broader term.' : 'No renders in this group for this voice. Choose All voices or another group.'));
    $('load-more').hidden = clips.length <= state.limit;
    $('load-more').textContent = `Show ${Math.min(PAGE_SIZE, clips.length - state.limit)} more clips ↓`;
  }

  function renderMacroControls() {
    const target = $('macro-controls'); target.replaceChildren();
    const voice = voiceName(state.selected?.voice);
    const baseline = state.baselines.get(voice);
    MACROS.forEach((macro) => {
      const clips = state.sweeps.get(`${voice}:${macro}`) || [];
      const row = element('div', 'macro-row');
      const label = element('div', 'macro-label'); label.title = MACRO_HELP[macro];
      label.append(element('span', '', macro), element('span', 'macro-current', valueLabel(macroValue(state.selected, macro))));
      const points = element('div', 'macro-points'); points.setAttribute('role', 'group'); points.setAttribute('aria-label', `${pretty(macro)} sweep for ${pretty(voice)}`);
      [0, .25, .5, .75, 1].forEach((value) => {
        const clip = clips.find((item) => Math.abs(numeric(item.sweep?.value) - value) < .001);
        const active = Boolean(clip && state.selected?.id === clip.id);
        const atBaseline = Boolean(baseline && Math.abs(macroValue(baseline, macro) - value) < .001);
        const button = element('button', `macro-point${active ? ' active' : ''}${atBaseline ? ' baseline' : ''}`, valueLabel(value));
        button.type = 'button'; button.disabled = !clip;
        button.setAttribute('aria-pressed', String(active));
        button.setAttribute('aria-label', `${pretty(macro)} ${value.toFixed(2)}${clip ? `, ${pretty(voice)} baseline sweep` : ', render unavailable'}`);
        button.title = clip ? `${MACRO_HELP[macro]} All other controls use the ${pretty(voice)} baseline.` : 'No rendered sweep at this point.';
        if (clip) button.addEventListener('click', () => selectClip(clip));
        points.append(button);
      });
      row.append(label, points); target.append(row);
    });
    $('reset-voice').disabled = !baseline;
    $('gesture-label').textContent = [state.selected?.gesture ? `Gesture: ${pretty(state.selected.gesture)}` : 'Gesture: see recipe', state.selected?.driver ? `${pretty(state.selected.driver)} driver` : ''].filter(Boolean).join(' / ');
  }

  async function selectClip(clip, preserveMode = false) {
    const wasPlaying = !audio.paused;
    audio.pause(); state.selected = clip;
    if (!preserveMode || !pathFor(clip, state.mode)) state.mode = 'production';
    if (state.mode === 'raw' && !clip.rawPath || state.mode === 'matched' && !clip.matchedPath) state.mode = 'production';
    $('audio-error').hidden = true;
    $('clip-label').textContent = `${voiceName(clip.voice)} / ${groupName(clip.group)}`;
    $('clip-title').textContent = clipName(clip);
    $('clip-subtitle').textContent = [noteName(clip), clip.velocity !== undefined ? `velocity ${velocityLabel(clip.velocity)}` : '', clip.loop ? 'HOLD render' : 'finite gesture', state.mode === 'production' ? 'host normalized' : state.mode === 'raw' ? rawModeLabel(clip, true) : 'equal audition loudness'].filter(Boolean).join(' · ');
    const path = pathFor(clip);
    $('download-wav').href = path;
    $('download-wav').download = path.split('/').pop();
    $('download-wav').setAttribute('aria-disabled', 'false');
    $('play-button').disabled = false; $('seek').disabled = false; $('download-recipe').disabled = false;
    $('duration').textContent = time(duration(clip)); $('current-time').textContent = '0:00'; $('seek').value = '0';
    $('loop-control').disabled = !clip.loop;
    if (!clip.loop) $('loop-control').checked = false;
    audio.loop = Boolean(clip.loop && $('loop-control').checked);
    const modeBox = $('diagnostic-mode'); modeBox.hidden = !clip.rawPath && !clip.matchedPath;
    modeBox.querySelectorAll('button').forEach((button) => {
      const mode = button.dataset.audioMode;
      if (mode === 'raw') { button.textContent = rawModeLabel(clip); button.title = clip.rawExportGain ? 'Raw diagnostic with the same fixed headroom gain on every raw file; relative source amplitudes are preserved.' : 'Raw diagnostic audio.'; }
      button.hidden = mode === 'raw' ? !clip.rawPath : mode === 'matched' ? !clip.matchedPath : false;
      button.classList.toggle('active', mode === state.mode); button.setAttribute('aria-pressed', String(mode === state.mode));
    });
    audio.src = path; audio.load();
    state.waveform = state.mode === 'production' && Array.isArray(clip.waveform) ? clip.waveform.map((n) => Math.abs(numeric(n))) : [];
    renderLibrary(); renderMacroControls(); loadNote(); drawWaveform();
    try { const url = new URL(location.href); url.searchParams.set('clip', clip.id); history.replaceState(null, '', url); } catch (_) { /* Optional on locally opened pages. */ }
    if (!state.waveform.length) loadWaveform(path);
    if (wasPlaying) play();
  }

  async function loadWaveform(path) {
    const token = ++state.waveformToken;
    try {
      const AudioContextType = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextType) return;
      if (!state.audioContext) state.audioContext = new AudioContextType();
      const response = await fetch(path);
      if (!response.ok) throw new Error('Waveform unavailable');
      const buffer = await state.audioContext.decodeAudioData(await response.arrayBuffer());
      if (token !== state.waveformToken || path !== pathFor(state.selected)) return;
      const samples = buffer.getChannelData(0); const bars = 180; const step = Math.max(1, Math.floor(samples.length / bars));
      state.waveform = Array.from({ length: bars }, (_, i) => {
        let peak = 0; for (let j = i * step; j < Math.min(samples.length, (i + 1) * step); j++) peak = Math.max(peak, Math.abs(samples[j]));
        return peak;
      });
      drawWaveform();
    } catch (_) { /* Audio remains playable if a browser cannot decode the optional waveform. */ }
  }

  function drawWaveform() {
    const bounds = canvas.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.round(bounds.width * ratio); const height = Math.round(bounds.height * ratio);
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    const ctx = canvas.getContext('2d'); if (!ctx) return;
    ctx.clearRect(0, 0, width, height);
    const midpoint = height / 2; const progress = audio.duration > 0 ? audio.currentTime / audio.duration : 0;
    ctx.strokeStyle = '#414c38'; ctx.lineWidth = ratio; ctx.beginPath(); ctx.moveTo(0, midpoint); ctx.lineTo(width, midpoint); ctx.stroke();
    if (!state.waveform.length) return;
    const peaks = state.waveform; const maximum = Math.max(.001, ...peaks);
    const count = Math.min(peaks.length, Math.floor(bounds.width / 4)); const pitch = width / count;
    for (let i = 0; i < count; i++) {
      const a = Math.floor(i * peaks.length / count); const b = Math.max(a + 1, Math.floor((i + 1) * peaks.length / count));
      let peak = 0; for (let j = a; j < b; j++) peak = Math.max(peak, peaks[j]);
      const barHeight = Math.max(ratio, peak / maximum * height * .86);
      ctx.fillStyle = i / count < progress ? '#d4b18a' : '#899d70';
      ctx.fillRect(i * pitch, midpoint - barHeight / 2, Math.max(ratio, pitch - 2 * ratio), barHeight);
    }
    if (progress > 0) { ctx.fillStyle = '#f0dec5'; ctx.fillRect(Math.min(width - ratio, progress * width), 0, ratio, height); }
  }

  function updatePlayback() {
    $('current-time').textContent = time(audio.currentTime);
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      $('duration').textContent = time(audio.duration);
      $('seek').value = String(Math.round(audio.currentTime / audio.duration * 1000));
    }
    drawWaveform();
  }
  function animate() { updatePlayback(); if (!audio.paused) state.frame = requestAnimationFrame(animate); }
  async function play() {
    if (!state.selected) return;
    try {
      if (state.audioContext?.state === 'suspended') state.audioContext.resume().catch(() => {});
      await audio.play(); $('audio-error').hidden = true;
    } catch (_) { $('audio-error').textContent = 'Playback could not start. Press play again, or download the WAV to listen locally.'; $('audio-error').hidden = false; }
  }
  function togglePlayback() { if (audio.paused) play(); else audio.pause(); }

  function noteKey() { return `${state.selected?.id || ''}:${state.mode}`; }
  function loadNote() {
    const note = state.notes[noteKey()];
    $('note-verdict').value = note?.verdict || 'unreviewed'; $('note-text').value = note?.note || '';
    updateNoteStatus();
  }
  function updateNoteStatus() {
    const count = Object.values(state.notes).filter((note) => note.note || note.verdict !== 'unreviewed').length;
    $('note-status').textContent = state.storageAvailable ? `${count ? `${count} listening note${count === 1 ? '' : 's'} saved here.` : 'Notes stay on this device.'}` : 'Browser storage unavailable. Export notes before leaving.';
  }
  function saveNote() {
    if (!state.selected) return;
    const note = $('note-text').value; const verdict = $('note-verdict').value;
    if (!note && verdict === 'unreviewed') delete state.notes[noteKey()];
    else state.notes[noteKey()] = { clipId: state.selected.id, title: clipName(state.selected), voice: state.selected.voice, audioMode: state.mode, verdict, note, updatedAt: new Date().toISOString() };
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state.notes)); } catch (_) { state.storageAvailable = false; }
    updateNoteStatus();
  }

  function registerEvents() {
    $('presets-tab').addEventListener('click', () => { state.collection = 'presets'; state.limit = PAGE_SIZE; renderLibrary(); });
    $('probes-tab').addEventListener('click', () => { state.collection = 'probes'; state.limit = PAGE_SIZE; renderLibrary(); });
    $('clip-search').addEventListener('input', (event) => { state.query = event.target.value.trim().toLowerCase(); state.limit = PAGE_SIZE; renderLibrary(); });
    $('group-select').addEventListener('change', (event) => { state.group = event.target.value; state.limit = PAGE_SIZE; renderLibrary(); });
    $('load-more').addEventListener('click', () => { state.limit += PAGE_SIZE; renderLibrary(); });
    $('play-button').addEventListener('click', togglePlayback);
    $('seek').addEventListener('input', (event) => { if (audio.duration > 0) { audio.currentTime = numeric(event.target.value) / 1000 * audio.duration; updatePlayback(); } });
    $('loop-control').addEventListener('change', (event) => { audio.loop = Boolean(state.selected?.loop && event.target.checked); });
    $('reset-voice').addEventListener('click', () => { const baseline = state.baselines.get(voiceName(state.selected?.voice)); if (baseline) selectClip(baseline); });
    $('diagnostic-mode').querySelectorAll('button').forEach((button) => button.addEventListener('click', () => { state.mode = button.dataset.audioMode; selectClip(state.selected, true); }));
    $('download-recipe').addEventListener('click', () => {
      if (!state.selected) return;
      downloadable({ schemaVersion: 1, engine: state.manifest.engine, sampleRate: state.manifest.sampleRate, clipId: state.selected.id, audioMode: state.mode, path: pathFor(state.selected), midi: state.selected.midi, note: state.selected.note, velocity: state.selected.velocity, macros: state.selected.macros, driver: state.selected.driver, gesture: state.selected.gesture, loop: state.selected.loop, recipe: state.selected.recipe, render: state.selected.render, metrics: state.selected.metrics, metricsAudioPath: state.selected.path, rawExportGain: state.selected.rawExportGain, rawSourceMetrics: state.selected.rawSourceMetrics, rawMetrics: state.selected.rawMetrics, rawMetricsAudioPath: state.selected.rawPath }, `${state.selected.id}-${state.mode}-recipe.json`);
    });
    $('note-text').addEventListener('input', saveNote); $('note-verdict').addEventListener('change', saveNote);
    $('export-notes').addEventListener('click', () => {
      saveNote();
      downloadable({ schemaVersion: 1, engine: state.manifest?.engine || 'Embouchure', exportedAt: new Date().toISOString(), acceptance: state.manifest?.acceptance, manifest: 'manifest.json', notes: Object.values(state.notes).map((note) => { const clip = state.clips.find((item) => item.id === note.clipId); return { ...note, audioPath: clip ? pathFor(clip, note.audioMode) : null, rawExportGain: clip?.rawExportGain, midi: clip?.midi, velocity: clip?.velocity, macros: clip?.macros, recipe: clip?.recipe }; }) }, `embouchure-listening-notes-${new Date().toISOString().slice(0, 10)}.json`);
    });
    audio.addEventListener('play', () => { $('play-button').classList.add('playing'); $('play-button').setAttribute('aria-label', 'Pause selected clip'); cancelAnimationFrame(state.frame); animate(); });
    audio.addEventListener('pause', () => { $('play-button').classList.remove('playing'); $('play-button').setAttribute('aria-label', 'Play selected clip'); cancelAnimationFrame(state.frame); updatePlayback(); });
    audio.addEventListener('ended', updatePlayback); audio.addEventListener('loadedmetadata', updatePlayback); audio.addEventListener('timeupdate', updatePlayback);
    audio.addEventListener('error', () => { if (!state.selected) return; $('audio-error').textContent = 'This audio file could not be loaded. Download the WAV or choose another render.'; $('audio-error').hidden = false; });
    document.addEventListener('keydown', (event) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || /INPUT|TEXTAREA|SELECT|BUTTON|A|SUMMARY/.test(event.target.tagName) || event.target.isContentEditable) return;
      if (event.code === 'Space') { event.preventDefault(); togglePlayback(); }
      if (event.code === 'ArrowLeft' || event.code === 'ArrowRight') {
        const clips = filteredClips(); if (!clips.length) return;
        event.preventDefault(); const current = clips.findIndex((clip) => clip.id === state.selected?.id);
        const next = current < 0 ? 0 : (current + (event.code === 'ArrowRight' ? 1 : -1) + clips.length) % clips.length;
        if (next >= state.limit) state.limit = Math.ceil((next + 1) / PAGE_SIZE) * PAGE_SIZE;
        selectClip(clips[next]);
      }
    });
    if (typeof ResizeObserver !== 'undefined') new ResizeObserver(drawWaveform).observe(canvas);
    else window.addEventListener('resize', drawWaveform);
  }

  async function initialize() {
    registerEvents();
    try { state.notes = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}'); if (!state.notes || typeof state.notes !== 'object' || Array.isArray(state.notes)) state.notes = {}; } catch (_) { state.notes = {}; state.storageAvailable = false; }
    try {
      const inline = document.getElementById('embouchure-manifest');
      if (inline) state.manifest = JSON.parse(inline.textContent);
      else {
        const response = await fetch('manifest.json');
        if (!response.ok) throw new Error(`Manifest returned HTTP ${response.status}`);
        state.manifest = await response.json();
      }
      state.clips = state.manifest.clips || [];
      if (!state.clips.length) throw new Error('No rendered clips found in manifest.json');
      state.clips.forEach((clip) => {
        const voice = voiceName(clip.voice);
        if (String(clip.group).toUpperCase() === 'VOICES' && !state.baselines.has(voice)) state.baselines.set(voice, clip);
        if (clip.sweep?.macro) { const key = `${voice}:${String(clip.sweep.macro).toUpperCase()}`; if (!state.sweeps.has(key)) state.sweeps.set(key, []); state.sweeps.get(key).push(clip); }
      });
      VOICES.forEach((voice) => { if (!state.baselines.has(voice)) { const clip = state.clips.find((item) => voiceName(item.voice) === voice && isPreset(item)); if (clip) state.baselines.set(voice, clip); } });
      const groups = state.manifest.groups || [...new Set(state.clips.map((clip) => clip.group))].map((id) => ({ id, title: pretty(id) }));
      groups.filter((group) => String(group.id).toUpperCase() !== 'PRESETS').forEach((group) => { const option = element('option', '', group.title || pretty(group.id)); option.value = group.id; $('group-select').append(option); });
      $('bench-count').textContent = `${state.clips.filter(isPreset).length} presets / ${state.clips.length} dry renders`;
      if (state.manifest.sourceUrl) document.querySelectorAll('a[href^="https://github.com/BertCalm/SnipSnap"]').forEach((link) => { link.href = state.manifest.sourceUrl; });
      if (state.manifest.bundlePath) { $('download-bundle').href = state.manifest.bundlePath; $('download-bundle').hidden = false; }
      if (state.manifest.curated) {
        document.querySelector('.hero-detail').textContent = 'Listen to Embouchure’s first dry port into SnipSnap. This curated pack contains voices and selected probes; leave a listening verdict.';
        $('controls-title').textContent = 'The rendered settings.';
        document.querySelector('.panel-description').textContent = 'The values at left describe this render. Sweep points are disabled in this curated download; the full five-point control matrix is on the published listening bench.';
      } else if (state.manifest.completeMatrix === false) {
        document.querySelector('.panel-description').textContent = 'Preset values are shown at left. This pack includes selected listening renders; points without a rendered sweep are disabled. The full control matrix is on the published listening bench.';
      }
      if (state.manifest.kit) {
        const kit = state.manifest.kit; const links = $('kit-downloads');
        links.append(element('span', '', `${kit.name || 'Embouchure kit'} / ${kit.pads || 16} PADS`));
        [['mpc3Path', 'MPC 3 kit'], ['mpc2Path', 'MPC 2 kit'], ['recipePath', 'Kit recipes']].forEach(([key, title]) => {
          if (!kit[key]) return; const link = element('a', '', `${title} ↓`); link.href = kit[key]; link.download = ''; links.append(link);
        });
        links.hidden = false;
      }
      const details = $('render-details'); details.replaceChildren();
      details.append(element('p', '', `${state.manifest.engine || 'Embouchure'} · ${numeric(state.manifest.sampleRate, 44100).toLocaleString()} Hz · ${state.manifest.channels || 1} channel · ${state.manifest.format || 'PCM WAV'}. ${state.manifest.acceptance || 'Owner listening verdict pending.'}`));
      if (state.manifest.caveats?.length) { const list = element('ul'); state.manifest.caveats.forEach((caveat) => list.append(element('li', '', typeof caveat === 'string' ? caveat : JSON.stringify(caveat)))); details.append(list); }
      const rawGain = state.clips.find((clip) => clip.rawExportGain !== undefined)?.rawExportGain;
      if (rawGain > 0 && rawGain !== 1) details.append(element('p', '', `Every raw diagnostic WAV uses the same fixed ${(20 * Math.log10(rawGain)).toFixed(2)} dB export gain (${rawGain}), preserving relative source amplitudes. Recipe downloads include this gain, untouched source metrics, and exported raw metrics. The gain is identical for every clip.`));
      if (state.manifest.bundlePath) details.append(element('p', '', 'The curated download contains a smaller listening collection and a 16-pad MPC kit. The full probe and sweep matrix stays on this page.'));
      details.append(element('p', '', 'Production audio is host normalized with expressive velocity gain. Raw and equal-audition-loudness modes appear only where diagnostic companion renders exist. Waveforms show actual rendered audio, scaled visually to the clip peak. HOLD looping repeats the supplied loop file; the page adds no synthesis, effects, or crossfade.'));
      renderVoiceFilters(); renderLibrary();
      const requested = new URLSearchParams(location.search).get('clip');
      const first = state.clips.find((clip) => clip.id === requested) || state.clips.find(isPreset) || state.clips[0];
      if (!isPreset(first)) {
        state.collection = 'probes';
        if (requested) {
          state.group = first.group; $('group-select').value = state.group;
          const index = filteredClips().findIndex((clip) => clip.id === first.id);
          state.limit = Math.max(PAGE_SIZE, Math.ceil((index + 1) / PAGE_SIZE) * PAGE_SIZE);
        }
      }
      await selectClip(first);
    } catch (error) {
      $('bench-count').textContent = 'Collection unavailable'; $('clip-title').textContent = 'The collection is unavailable';
      $('clip-subtitle').textContent = 'The rendered audio manifest could not be opened.';
      $('clip-list').replaceChildren(element('p', 'empty-state', 'No collection loaded.'));
      $('loading-error').textContent = `The listening collection could not load. Serve this folder over HTTP with manifest.json and its audio files. ${error.message}`; $('loading-error').hidden = false;
      $('render-details').replaceChildren(element('p', '', 'Render metadata is unavailable until the audio manifest loads.'));
    }
  }
  initialize();
})();
