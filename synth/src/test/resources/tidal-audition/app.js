'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const make = (tag, className, value) => {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (value !== undefined && value !== null) el.textContent = String(value);
    return el;
  };
  const audio = $('audio-player');
  const sectionNames = {
    defaults: 'First listen', revision: 'Before & after', anatomy: 'Inside the sound', controls: 'Tail pickup',
    encounters: 'Encounter studies', mechanisms: 'Mechanism checks', registers: 'Notes & velocity', extremes: 'At the edges',
  };
  const sectionDescriptions = {
    revision: 'Compare the first source with this revision. STREAM keeps its encounter settings; PAIR also has a source-only comparison at its original settings.',
    anatomy: 'Hear the original populations, compact bodies, and extended pickup separately. Stems retain their reference gain.',
    controls: 'Move TAIL from compact bodies toward extended pickup. Both endpoints keep the full STREAM reference gain.',
    encounters: 'Change the encounter geometry and disk compactness. Listen for different amounts and kinds of afterstructure.',
    mechanisms: 'Keep the listening conditions while simplifying one mechanism. Does the evolving structure make an audible difference?',
    registers: 'The same instrument across roots and playing strengths. The requested root remains separate from encounter controls.',
    extremes: 'Difficult and weak-tail settings. Quiet extended pickup can be an honest outcome.',
  };
  const controlDescriptions = {
    APPROACH: 'Relative encounter speed. Listen to the passage and its duration.',
    PASSAGE: 'Distant pass to close encounter. Listen to what is left behind.',
    TILT: 'Disk orientation and spin alignment. Listen for a different structure.',
    BIND: 'Diffuse material to compact, attached cores. Listen for core survival.',
    TAIL: 'Compact bodies to extended harmonic streams. Listen beyond the passage.',
  };
  const state = { manifest: null, clips: [], byId: new Map(), mode: 'matched', section: 'revision', query: '', current: null, slots: { a: null, b: null }, generation: 0 };
  const cardElements = new Map();
  const plotElements = new Set();
  const traceCache = new Map();

  function modelOf(clip) { return Number(clip.model ?? state.manifest?.model ?? 1); }
  function noteName(midi) {
    const n = Number(midi);
    if (!Number.isFinite(n)) return 'Root —';
    const names = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
    return names[((Math.round(n) % 12) + 12) % 12] + (Math.floor(n / 12) - 1);
  }
  function clock(seconds) {
    if (!Number.isFinite(Number(seconds))) return '0:00';
    const s = Math.max(0, Math.floor(Number(seconds)));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }
  function fileUrl(path) {
    if (typeof path !== 'string' || !path.trim()) return null;
    try {
      const url = new URL(path, location.href);
      return ['http:', 'https:', 'file:'].includes(url.protocol) ? url.href : null;
    } catch { return null; }
  }
  function pathOf(clip, key) { return fileUrl(clip.paths?.[key] || clip[key]); }
  function audioPath(clip) { return pathOf(clip, state.mode) || pathOf(clip, state.mode === 'raw' ? 'matched' : 'raw'); }
  function announce(message) { $('audio-status').textContent = message; }
  function error(message) {
    const banner = $('error-banner');
    banner.replaceChildren(make('span', '', message));
    const close = make('button', '', 'Dismiss');
    close.type = 'button'; close.addEventListener('click', () => { banner.hidden = true; });
    banner.append(close); banner.hidden = false; announce(message);
  }
  function visibleValue(value, digits = 3) {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'number') {
      if (!Number.isFinite(value)) return '—';
      if (value !== 0 && Math.abs(value) < .001) return value.toExponential(2);
      return Number(value.toFixed(digits)).toString();
    }
    if (typeof value === 'object') return String(value.drumClass ?? value.classification ?? value.value ?? JSON.stringify(value));
    return String(value);
  }
  function linkedFile(label, href, className) {
    if (!href) return null;
    const a = make('a', className || '', label); a.href = href; a.setAttribute('download', ''); return a;
  }
  function updatePlayer() {
    const current = state.current;
    $('player-title').textContent = current ? current.title : 'Choose a clip to listen';
    $('player-kicker').textContent = current ? (audio.paused ? 'PAUSED / ' : 'NOW PLAYING / ') + state.mode.toUpperCase() : 'READY WHEN YOU ARE';
    $('player-subtitle').textContent = current ? `${current.voice || 'TIDAL'} · ${noteName(current.midi)} · model ${modelOf(current)} · finite` : 'Finite encounter / no autoplay';
    const total = Number.isFinite(audio.duration) ? audio.duration : Number(current?.durationSeconds) || 0;
    $('player-time').textContent = `${clock(audio.currentTime)} / ${clock(total)}`;
    for (const [id, entries] of cardElements) for (const entry of entries) {
      const active = id === current?.id;
      entry.card.classList.toggle('card-is-current', active);
      const playing = active && !audio.paused;
      entry.play.classList.toggle('is-playing', playing);
      entry.play.replaceChildren(make('span', 'play-icon', playing ? 'Ⅱ' : '▶'), make('span', '', playing ? 'Pause' : 'Listen'));
      entry.play.setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} ${entry.clip.title}`);
      entry.play.setAttribute('aria-pressed', String(playing));
      for (const slot of ['a', 'b']) entry[slot].classList.toggle('assigned', state.slots[slot]?.id === id);
    }
    for (const slot of ['a', 'b']) {
      const clip = state.slots[slot], button = $(`slot-${slot}`);
      button.disabled = !clip;
      $(`slot-${slot}-title`).textContent = clip ? clip.title : 'Assign a clip';
      button.title = clip ? `Switch to ${slot.toUpperCase()}: ${clip.title}` : `Assign a clip to ${slot.toUpperCase()} using its card`;
      button.classList.toggle('active', clip?.id === current?.id);
      button.setAttribute('aria-pressed', String(clip?.id === current?.id));
    }
    for (const plot of plotElements) if (plot.details.open && plot.frames) drawPlot(plot);
  }
  async function loadClip(clip, shouldPlay = true, seek = 0) {
    const url = audioPath(clip);
    if (!url) { error(`No audio file is available for ${clip.title}.`); return; }
    const generation = ++state.generation;
    state.current = clip;
    if (!state.slots.a) state.slots.a = clip;
    if (audio.src !== url) {
      audio.pause();
      audio.src = url;
      const onMetadata = () => {
        if (generation !== state.generation) return;
        if (Number.isFinite(audio.duration)) audio.currentTime = Math.min(Math.max(0, seek), Math.max(0, audio.duration - .01));
        updatePlayer();
      };
      audio.addEventListener('loadedmetadata', onMetadata, { once: true });
      audio.load();
    } else if (Number.isFinite(audio.duration)) audio.currentTime = Math.min(Math.max(0, seek), Math.max(0, audio.duration - .01));
    updatePlayer();
    if (shouldPlay) {
      try {
        await audio.play();
        if (generation === state.generation) announce(`Playing ${clip.title}, ${state.mode} level.`);
      } catch (e) {
        if (generation === state.generation && e.name !== 'AbortError') error(`Playback could not start. Try the player’s play button or download the WAV. ${e.message || ''}`);
      }
    }
    updatePlayer();
  }
  function playClip(clip) {
    if (state.current?.id === clip.id && !audio.paused) { audio.pause(); updatePlayer(); }
    else loadClip(clip, true, state.current?.id === clip.id && !audio.ended ? audio.currentTime : 0);
  }
  function assignSlot(slot, clip) {
    state.slots[slot] = clip; updatePlayer(); announce(`${clip.title} assigned to comparison ${slot.toUpperCase()}.`);
  }
  function switchSlot(slot) {
    const clip = state.slots[slot];
    if (clip) loadClip(clip, true, state.current ? audio.currentTime : 0);
  }
  function stop() {
    state.generation += 1; audio.pause();
    if (audio.readyState > 0) audio.currentTime = 0;
    updatePlayer(); announce('Playback stopped.');
  }
  function setMode(mode) {
    if (state.mode === mode) return;
    const play = !audio.paused, position = audio.currentTime;
    state.mode = mode;
    for (const button of document.querySelectorAll('[data-mode]')) {
      button.classList.toggle('active', button.dataset.mode === mode);
      button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
    }
    if (state.current) loadClip(state.current, play, position);
    announce(`${mode === 'raw' ? 'Raw common-gain exports' : 'Shared-reference matched levels'} selected.`);
  }
  function durationOf(clip) { return Number(clip.durationSeconds ?? clip.diagnostics?.durationSeconds) || 0; }
  function evidence(clip) {
    const details = make('details', 'clip-evidence');
    details.append(make('summary', '', 'Recipe & evidence'));
    const dl = make('dl');
    const add = (label, value, digits) => {
      if (value === undefined || value === null) return;
      const row = make('div'); row.append(make('dt', '', label), make('dd', '', visibleValue(value, digits))); dl.append(row);
    };
    add('Rendering model', modelOf(clip)); add('Requested root', `${noteName(clip.midi)} / MIDI ${clip.midi}`); add('Velocity', clip.velocity, 2);
    add('Render length', `${durationOf(clip).toFixed(2)} s`); add('Component', clip.component || 'mix');
    add('Raw export gain', clip.rawGain, 4); add('Matched gain', clip.matchedGain, 4);
    const metrics = clip.metrics || {}, raw = clip.rawMetrics || {}, d = clip.diagnostics || {};
    add('Pre-level peak', raw.peak ?? d.rawPeak); add('Pre-level RMS', raw.rms ?? d.rawRms); add('Pre-level DC', raw.dc ?? d.rawDc);
    add('Pitch estimate', metrics.pitchHz === undefined ? undefined : `${visibleValue(metrics.pitchHz, 2)} Hz`);
    add('File class', metrics.classifier); add('Opening class', metrics.onsetClassifier);
    add('Closest passage', d.minimumSeparation ?? d.minimumCenterSeparation ?? d.minCenterSeparation ?? d.minSeparation);
    add('Tail / core RMS', d.tailRms !== undefined && d.coreRms !== undefined ? `${visibleValue(d.tailRms)} / ${visibleValue(d.coreRms)}` : undefined);
    add('Preparation', d.preparationMillis === undefined ? undefined : `${visibleValue(d.preparationMillis, 1)} ms`);
    details.append(dl);
    const macros = make('div', 'control-values');
    for (const [name, value] of Object.entries(clip.macros || {})) macros.append(make('span', '', `${name} ${visibleValue(value, 2)}`));
    if (macros.childElementCount) details.append(macros);
    const links = make('div', 'evidence-links');
    for (const [key, label] of [['recipe', 'Patch JSON'], ['diagnosticRecipe', 'Diagnostic recipe'], ['diagnostics', 'Measurements'], ['trace', 'Control trace'], ['raw', 'Raw WAV'], ['matched', 'Matched WAV']]) {
      const link = linkedFile(label, pathOf(clip, key)); if (link) links.append(link);
    }
    details.append(links);
    const traceUrl = pathOf(clip, 'trace');
    if (traceUrl) {
      const plotBox = make('div', 'trace-plot'), canvas = make('canvas');
      canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', 'Measured core and tail control allocations over the finite encounter');
      const caption = make('p', 'trace-caption', 'Core / tail control allocations over time. Loading measured trace…');
      plotBox.append(canvas, caption); details.append(plotBox);
      const plot = { canvas, caption, details, clip, frames: null }; plotElements.add(plot);
      details.addEventListener('toggle', () => { if (details.open) loadTrace(plot, traceUrl); });
    }
    return details;
  }
  async function loadTrace(plot, url) {
    if (plot.frames) { drawPlot(plot); return; }
    try {
      if (!traceCache.has(url)) traceCache.set(url, fetch(url).then(r => { if (!r.ok) throw new Error('Trace unavailable'); return r.json(); }));
      const trace = await traceCache.get(url);
      const frames = Array.isArray(trace) ? trace : trace.frames || trace.trace || trace.overview || [];
      plot.frames = frames.filter(f => Number.isFinite(Number(f.timeSeconds ?? f.seconds ?? f.time)) && (Number.isFinite(Number(f.coreAllocation)) || Number.isFinite(Number(f.tailAllocation))));
      if (plot.frames.length < 2) { plot.caption.textContent = 'Download the control trace for the complete measured descriptors.'; plot.canvas.hidden = true; return; }
      plot.caption.textContent = 'Teal: core allocation · amber: extended allocation. Measured controls, not audio energy or a calibrated galaxy.';
      drawPlot(plot);
    } catch { plot.caption.textContent = 'The measured trace could not load. The WAV and recipe remain available.'; plot.canvas.hidden = true; }
  }
  function drawPlot(plot) {
    const { canvas, frames, clip } = plot;
    if (!frames || !canvas.isConnected) return;
    const width = canvas.getBoundingClientRect().width;
    if (width < 1) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2), height = 100;
    canvas.width = Math.round(width * ratio); canvas.height = height * ratio;
    const ctx = canvas.getContext('2d'); if (!ctx) return;
    ctx.scale(ratio, ratio); ctx.clearRect(0, 0, width, height);
    const end = Math.max(...frames.map(f => Number(f.timeSeconds ?? f.seconds ?? f.time))), left = 2, right = width - 2, top = 9, bottom = height - 17;
    ctx.strokeStyle = '#35424a'; ctx.lineWidth = .6;
    for (const y of [top, (top + bottom) / 2, bottom]) { ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke(); }
    for (const [key, color] of [['coreAllocation', '#8ee2cf'], ['tailAllocation', '#eab775']]) {
      ctx.strokeStyle = color; ctx.lineWidth = 1.2; ctx.beginPath();
      let started = false;
      for (const frame of frames) {
        const value = Number(frame[key]); if (!Number.isFinite(value)) continue;
        const x = left + (right - left) * Number(frame.timeSeconds ?? frame.seconds ?? frame.time) / (end || 1), y = bottom - (bottom - top) * Math.min(1, Math.max(0, value));
        if (!started) { ctx.moveTo(x, y); started = true; } else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }
    ctx.fillStyle = '#a7b2b5'; ctx.font = '8px monospace'; ctx.fillText('0 s', left, height - 3); ctx.textAlign = 'right'; ctx.fillText(`${end.toFixed(1)} s`, right, height - 3);
    if (state.current?.id === clip.id) {
      const x = left + (right - left) * Math.min(1, audio.currentTime / (end || 1));
      ctx.strokeStyle = '#f2efe6'; ctx.lineWidth = .8; ctx.setLineDash([2, 3]); ctx.beginPath(); ctx.moveTo(x, top); ctx.lineTo(x, bottom); ctx.stroke();
    }
  }
  function cardControls(clip, card, compact = false) {
    const actions = make('div', 'clip-actions'), play = make('button', 'play-button'); play.type = 'button';
    play.append(make('span', 'play-icon', '▶'), make('span', '', 'Listen'));
    play.setAttribute('aria-label', `Play ${clip.title}`); play.setAttribute('aria-pressed', 'false'); play.addEventListener('click', () => playClip(clip));
    const a = make('button', 'assign-button', 'A'), b = make('button', 'assign-button', 'B');
    for (const [slot, button] of [['a', a], ['b', b]]) {
      button.type = 'button'; button.title = `Assign to ${slot.toUpperCase()}`; button.setAttribute('aria-label', `Assign ${clip.title} to comparison ${slot.toUpperCase()}`); button.addEventListener('click', () => assignSlot(slot, clip));
    }
    if (compact) { card.append(play); actions.append(a, b); }
    else {
      actions.append(play, a, b);
      const wav = linkedFile('Matched WAV >', pathOf(clip, 'matched') || pathOf(clip, 'raw'), 'file-link'); if (wav) actions.append(wav);
    }
    card.append(actions);
    if (!cardElements.has(clip.id)) cardElements.set(clip.id, new Set());
    cardElements.get(clip.id).add({ clip, card, play, a, b });
  }
  function makeCard(clip, featured = false, index = 0) {
    const card = make('article', `${featured ? 'featured-card' : 'study-card'} voice-${String(clip.voice || '').toLowerCase()}`);
    const headingId = `title-${featured ? 'featured-' : ''}${clip.id}`; card.setAttribute('aria-labelledby', headingId);
    const top = make('div', 'card-topline'); top.append(make('span', 'voice-tag', clip.voice || 'TIDAL'), make('span', 'clip-index', `${featured ? 'CURRENT ' : ''}${String(index + 1).padStart(2, '0')}`));
    const h3 = make('h3', '', clip.title); h3.id = headingId; card.append(top, h3);
    if (clip.description) card.append(make('p', 'clip-description', clip.description));
    const meta = make('div', 'clip-meta'); meta.append(make('span', '', noteName(clip.midi)), make('span', '', `${durationOf(clip).toFixed(1)} s`), make('span', '', `Velocity ${visibleValue(clip.velocity, 2)}`), make('span', '', `Model ${modelOf(clip)}`)); card.append(meta);
    if (clip.question) card.append(make('p', 'clip-question', clip.question));
    cardControls(clip, card); card.append(evidence(clip));
    return card;
  }
  function makeSweep(name, clips) {
    const row = make('div', 'sweep-row'), label = make('div', 'sweep-description');
    label.append(make('h3', '', name), make('p', '', controlDescriptions[name] || 'Change one control.')); row.append(label);
    const buttons = make('div', 'sweep-buttons');
    for (const clip of [...clips].sort((a, b) => Number(a.step) - Number(b.step))) {
      const cell = make('article', 'sweep-clip'); cell.setAttribute('aria-label', `${name} ${visibleValue(clip.step, 2)}`);
      cell.append(make('span', 'sweep-step', Number(clip.step).toFixed(2)));
      cardControls(clip, cell, true); cell.append(evidence(clip)); buttons.append(cell);
    }
    row.append(buttons); return row;
  }
  function sectionMetadata(id) { return state.manifest?.sections?.find(s => s.id === id); }
  function renderComparisons() {
    const host = $('comparison-clips'); host.replaceChildren();
    for (const [id, entries] of cardElements) {
      for (const entry of entries) if (!entry.card.isConnected) entries.delete(entry);
      if (!entries.size) cardElements.delete(id);
    }
    for (const plot of plotElements) if (!plot.canvas.isConnected) plotElements.delete(plot);
    const searchable = (clip) => [clip.title, clip.description, clip.question, clip.voice, clip.sweep, noteName(clip.midi), `model ${modelOf(clip)}`].join(' ').toLowerCase();
    const clips = state.clips.filter(c => c.section !== 'defaults' && (state.section === 'all' || c.section === state.section) && (!state.query || searchable(c).includes(state.query)));
    $('clip-count').textContent = `${clips.length} ${clips.length === 1 ? 'clip' : 'clips'}`;
    $('study-description').textContent = state.section === 'all' ? 'All comparisons. Select a study to listen with one question in mind.' : sectionMetadata(state.section)?.description || sectionDescriptions[state.section] || '';
    if (!clips.length) { host.append(make('p', 'empty-copy', 'No clips match this search. Try another voice or control.')); updatePlayer(); return; }
    const sections = [...new Set(clips.map(c => c.section))];
    for (const section of sections) {
      const group = make('section', 'comparison-group');
      if (state.section === 'all') { const heading = make('div', 'group-heading'); heading.append(make('h3', '', sectionNames[section] || section), make('p', '', `${clips.filter(c => c.section === section).length} CLIPS`)); group.append(heading); }
      const members = clips.filter(c => c.section === section);
      const swept = members.filter(c => c.sweep && Number.isFinite(Number(c.step)));
      if (swept.length) {
        const list = make('div', 'sweep-list'); for (const name of [...new Set(swept.map(c => c.sweep))]) list.append(makeSweep(name, swept.filter(c => c.sweep === name))); group.append(list);
      }
      const regular = members.filter(c => !swept.includes(c));
      if (regular.length) {
        const grid = make('div', 'clip-grid'); regular.forEach((clip, i) => grid.append(makeCard(clip, false, i))); group.append(grid);
      }
      host.append(group);
    }
    updatePlayer();
  }
  function selectSection(id) {
    state.section = id;
    for (const button of $('study-tabs').children) { button.classList.toggle('active', button.dataset.section === id); button.setAttribute('aria-pressed', String(button.dataset.section === id)); }
    renderComparisons();
  }
  function setupSections() {
    const host = $('study-tabs');
    const present = new Set(state.clips.map(c => c.section));
    const ordered = ['revision', 'anatomy', 'controls', 'mechanisms', 'encounters', 'registers', 'extremes'].filter(s => present.has(s));
    for (const id of present) if (id !== 'defaults' && !ordered.includes(id)) ordered.push(id);
    ordered.push('all');
    for (const id of ordered) {
      const button = make('button', '', id === 'all' ? 'All' : sectionNames[id] || sectionMetadata(id)?.title || id); button.type = 'button'; button.dataset.section = id; button.setAttribute('aria-pressed', 'false'); button.addEventListener('click', () => selectSection(id)); host.append(button);
    }
    selectSection(ordered[0] || 'all');
  }
  function renderDownloads() {
    const host = $('download-links'); host.replaceChildren();
    const downloads = state.manifest.downloads || [];
    for (const download of downloads) {
      const url = fileUrl(download.path); if (!url) continue;
      const link = make('a', 'download-link'); link.href = url; link.setAttribute('download', '');
      const text = make('span'); text.append(make('strong', '', download.title || download.path)); if (download.description) text.append(make('small', '', download.description));
      link.append(text, make('span', '', 'v')); host.append(link);
    }
    if (!host.childElementCount) host.append(make('p', 'loading-copy', 'Use the WAV and recipe links on each clip.'));
  }
  async function init() {
    try {
      const response = await fetch('manifest.json'); if (!response.ok) throw new Error(`Manifest request returned ${response.status}`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('The audition manifest has no clips.');
      state.manifest = manifest;
      state.clips = manifest.clips.filter(c => c && typeof c.id === 'string' && typeof c.title === 'string');
      state.byId = new Map(state.clips.map(c => [c.id, c]));
      const featured = ['stream-default', 'pair-default'].map(id => state.byId.get(id)).filter(Boolean);
      if (!featured.length) featured.push(...state.clips.filter(c => c.section === 'defaults').slice(0, 2));
      if (!featured.length) featured.push(...state.clips.slice(0, 2));
      $('featured-clips').replaceChildren(...featured.map((clip, i) => makeCard(clip, true, i)));
      state.slots.a = state.byId.get(manifest.suggestedComparison?.a) || featured[0] || null;
      state.slots.b = state.byId.get(manifest.suggestedComparison?.b) || state.byId.get('legacy-stream') || featured[1] || null;
      setupSections(); renderDownloads(); updatePlayer();
      const verified = manifest.verification?.baselineRecipeReplayVerified;
      const referenceModels = [...new Set(state.clips.map(modelOf))].filter(model => model !== Number(manifest.model || 1)).sort();
      const modelLabel = `model ${manifest.model || 1}${referenceModels.length ? ' + model ' + referenceModels.join('/') + ' references' : ''}`;
      $('render-summary').textContent = `${manifest.clipCount || state.clips.length} rendered clips · ${modelLabel}${verified ? ' · recipe replay verified' : ''} · listening verdict pending`;
      if (manifest.gainPolicy?.raw || manifest.gainPolicy?.matched) {
        $('gain-explanation').textContent = 'Matched equalizes old/new full-mix loudness with common headroom per voice. Stems and mechanism checks retain the new full-mix gain. Raw uses one common export gain.';
      }
    } catch (e) {
      const message = location.protocol === 'file:' ? 'Open this listening pack through a local HTTP server so the manifest can load. All WAVs and recipes are also available in its folders.' : 'The listening manifest could not load. Refresh the page or use the audition downloads.';
      $('featured-clips').replaceChildren(make('p', 'empty-copy', message)); $('comparison-clips').replaceChildren(); $('clip-count').textContent = '';
      error(`${message} ${e.message || ''}`);
    }
  }
  for (const button of document.querySelectorAll('[data-mode]')) button.addEventListener('click', () => setMode(button.dataset.mode));
  $('clip-search').addEventListener('input', (event) => { state.query = event.target.value.trim().toLowerCase(); renderComparisons(); });
  $('repeat-clip').addEventListener('change', (event) => { audio.loop = event.target.checked; announce(audio.loop ? 'Repeat enabled. The complete finite clip will replay; this is not HOLD reconstruction.' : 'Repeat disabled.'); });
  $('stop-all').addEventListener('click', stop);
  $('slot-a').addEventListener('click', () => switchSlot('a')); $('slot-b').addEventListener('click', () => switchSlot('b'));
  for (const event of ['play', 'pause', 'ended', 'timeupdate', 'loadedmetadata', 'durationchange']) audio.addEventListener(event, updatePlayer);
  audio.addEventListener('error', () => { if (state.current && audio.error) error(`The audio for ${state.current.title} could not load. Try the WAV download or another clip.`); updatePlayer(); });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') { stop(); return; }
    if (event.code !== 'Space' || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (event.target.closest('input,button,a,select,textarea,audio,summary,[contenteditable="true"]')) return;
    if (state.current) { event.preventDefault(); if (audio.paused) loadClip(state.current, true, audio.currentTime); else audio.pause(); }
  });
  window.addEventListener('resize', () => { for (const plot of plotElements) if (plot.details.open && plot.frames) drawPlot(plot); });
  init();
})();
