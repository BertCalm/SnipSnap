/* A listening bench for offline Kotlin renders. The browser only plays files. */
'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const ui = Object.fromEntries([
    'load-status', 'load-error', 'load-error-detail', 'retry-load', 'loaded-content',
    'player-category', 'player-format', 'player-title', 'player-detail', 'player-voice',
    'waveform', 'wave-cursor', 'wave-message', 'wave-caption', 'play-button', 'play-label',
    'play-symbol', 'stop-button', 'current-time', 'duration', 'held-indicator', 'seek',
    'seek-wrap', 'mode-matched', 'mode-raw', 'level-hint', 'audio-status', 'wav-link',
    'recipe-link', 'selected-tags', 'native-audio', 'native-fallback', 'native-help',
    'review-count', 'review-prompt', 'listening-note', 'save-status', 'patch-details',
    'loop-detail', 'compare-a', 'compare-b', 'play-a', 'play-b', 'assign-a', 'assign-b',
    'section-tabs', 'section-title', 'section-description', 'clip-count', 'voice-filter',
    'note-filter', 'macro-filter', 'macro-filter-wrap', 'reset-filters', 'clip-grid',
    'empty-filter', 'export-notes', 'manifest-revision', 'revision-study',
    'revision-pair', 'revision-description', 'revision-original', 'revision-revised',
    'legacy-history', 'legacy-count', 'legacy-list', 'library-filters'
  ].map((id) => [id, $(id)]));
  const nativeAudio = ui['native-audio'];
  const verdictButtons = [...document.querySelectorAll('[data-verdict]')];
  const sectionNames = {
    identities: 'First listen', velocity: 'Velocity', sweeps: 'Controls',
    loops: 'Held tones', references: 'Organ references', comparisons: 'More chiff · R1 / R2'
  };
  const sectionCopy = {
    identities: 'PIPE and MUSETTE at three pitches, with restrained and expressive gestures. Start here, then compare the same pitch across voices.',
    velocity: 'A fixed patch at different velocities. Listen for a change in speech and body as well as level.',
    sweeps: 'One control moves at a time. Compare low, default, and high settings to hear what each gesture contributes.',
    loops: 'Settled, onset-free tones. The main player repeats the exact held region; listen for the seam and the persistence of the voice.',
    references: 'Existing SnipSnap organ voices at matching pitches. Compare what PIPE and MUSETTE add to the kit.',
    comparisons: 'Twelve finite PIPE pairs: three pitches with two gestures, three velocities, and three EDGE settings. Original R1 audio is preserved; More chiff R2 changes the first speech.'
  };
  const state = {
    manifest: null, selected: null, section: 'identities', mode: 'matched',
    notes: {}, legacyNotes: {}, legacySources: [], legacyKeys: {}, baselineManifest: null,
    storageKey: '', storageAvailable: true, context: null, pairId: null,
    source: null, gain: null, buffer: null, offset: 0, startedAt: 0,
    playing: false, nativePlaying: false, preparing: false, playToken: 0, nextStartAt: 0,
    selectionToken: 0, animation: 0, buffers: new Map(), comparison: null
  };
  const manifestUrl = new URL('manifest.json', document.baseURI);
  const resolveAsset = (path) => {
    const url = new URL(path, manifestUrl);
    if (state.manifest?.audioHash) url.searchParams.set('audioHash', state.manifest.audioHash);
    return url.href;
  };
  const getClip = (id) => state.manifest?.clips.find((clip) => clip.id === id);
  const clipUrl = (clip = state.selected) => resolveAsset(clip[state.mode]);
  const isHeld = (clip = state.selected) => Boolean(clip?.loop?.regionSeconds > 0);
  const nice = (value) => typeof value === 'number'
    ? (Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, '').replace(/\.$/, ''))
    : String(value ?? '—');
  const clock = (seconds) => {
    const value = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
    return `${Math.floor(value / 60)}:${(value % 60).toFixed(1).padStart(4, '0')}`;
  };
  const node = (tag, className, content) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (content !== undefined) element.textContent = content;
    return element;
  };
  const setStatus = (message, error = false) => {
    ui['audio-status'].textContent = message;
    ui['audio-status'].classList.toggle('error', error);
  };
  const duration = () => state.nativePlaying
    ? (Number.isFinite(nativeAudio.duration) ? nativeAudio.duration : state.selected?.seconds || 0)
    : isHeld() ? state.selected.loop.regionSeconds : (state.buffer?.duration || state.selected?.seconds || 0);
  const position = () => {
    if (state.nativePlaying) return nativeAudio.currentTime || 0;
    const value = state.offset + (state.playing && state.context ? Math.max(0, state.context.currentTime - state.startedAt) : 0);
    return isHeld() && duration() > 0 ? value % duration() : Math.min(value, duration());
  };

  function updateTransport() {
    const running = state.playing || state.nativePlaying;
    ui['play-label'].textContent = state.preparing ? 'Loading…' : running ? (isHeld() ? 'Pause hold' : 'Pause') : 'Play';
    ui['play-symbol'].textContent = running ? 'Ⅱ' : '▶';
    ui['play-button'].disabled = state.preparing || !state.selected;
    ui['play-button'].setAttribute('aria-label', running ? 'Pause selected render' : 'Play selected render');
    ui['held-indicator'].hidden = !isHeld();
    ui['seek-wrap'].hidden = isHeld();
    ui.duration.textContent = clock(duration());
    ui.seek.max = String(Math.max(.01, duration()));
    ['a', 'b'].forEach((slot) => ui[`play-${slot}`].setAttribute('aria-pressed', String(running && state.comparison === slot)));
    updatePosition();
  }

  function updatePosition() {
    const elapsed = position();
    ui['current-time'].textContent = clock(elapsed);
    ui.seek.value = String(elapsed);
    ui['wave-cursor'].hidden = !state.buffer && !state.nativePlaying;
    ui['wave-cursor'].style.left = `${duration() ? Math.min(100, elapsed / duration() * 100) : 0}%`;
  }

  function animate() {
    cancelAnimationFrame(state.animation);
    if (state.playing || state.nativePlaying) {
      updatePosition();
      state.animation = requestAnimationFrame(animate);
    }
  }

  function stopPlayback(reset = true, pauseNative = true) {
    state.playToken += 1;
    state.preparing = false;
    if (!reset) state.offset = position();
    const source = state.source;
    const gain = state.gain;
    if (source) {
      source.onended = null;
      const now = state.context.currentTime;
      if (gain) {
        // A stop ramp prevents transport clicks. Finite ENTRY remains unmodified.
        const fadeSeconds = isHeld() ? .012 : .005;
        gain.gain.cancelScheduledValues(now);
        gain.gain.setValueAtTime(gain.gain.value, now);
        gain.gain.linearRampToValueAtTime(0, now + fadeSeconds);
        try { source.stop(now + fadeSeconds + .001); } catch (_) { /* Already ended. */ }
        state.nextStartAt = now + fadeSeconds + .002;
        setTimeout(() => { source.disconnect(); gain.disconnect(); }, 40);
      } else {
        try { source.stop(); } catch (_) { /* Already ended. */ }
        source.disconnect();
        gain?.disconnect();
      }
    }
    state.source = null;
    state.gain = null;
    state.playing = false;
    state.nativePlaying = false;
    if (pauseNative) nativeAudio.pause();
    if (reset) {
      state.offset = 0;
      try { nativeAudio.currentTime = 0; } catch (_) { /* Metadata may not exist yet. */ }
    }
    cancelAnimationFrame(state.animation);
    updateTransport();
  }

  function audioContext() {
    if (!state.context) {
      const Context = window.AudioContext || window.webkitAudioContext;
      if (!Context) throw new Error('This browser does not offer the main audio player.');
      // Decode at the exported rate to keep held-region frame boundaries exact.
      try { state.context = new Context({ sampleRate: state.manifest?.sampleRate || 44100 }); }
      catch (_) { state.context = new Context(); }
    }
    return state.context;
  }

  async function getBuffer(clip) {
    const url = clipUrl(clip);
    if (!state.buffers.has(url)) {
      const pending = (async () => {
        const response = await fetch(url);
        if (!response.ok) throw new Error(`The WAV could not load (HTTP ${response.status}).`);
        const bytes = await response.arrayBuffer();
        if (!bytes.byteLength) throw new Error('The WAV file is empty.');
        return audioContext().decodeAudioData(bytes);
      })();
      state.buffers.set(url, pending);
      pending.catch(() => state.buffers.delete(url));
    }
    return state.buffers.get(url);
  }

  async function playSelected() {
    if (!state.selected) return;
    if (state.playing || state.nativePlaying) { stopPlayback(false); return; }
    const token = ++state.playToken;
    const clip = state.selected;
    state.preparing = true;
    updateTransport();
    try {
      const context = audioContext();
      await context.resume();
      const buffer = await getBuffer(clip);
      if (token !== state.playToken) return;
      if (context.state !== 'running') throw new Error('The browser has paused audio. Try the native player below.');
      nativeAudio.pause();
      state.buffer = buffer;
      const held = isHeld(clip);
      const region = held ? clip.loop.regionSeconds : buffer.duration;
      if (held && region > buffer.duration + 1 / buffer.sampleRate) throw new Error('The held region is longer than the WAV.');
      const source = context.createBufferSource();
      const gain = context.createGain();
      source.buffer = buffer;
      source.loop = held;
      if (held) { source.loopStart = 0; source.loopEnd = region; }
      source.connect(gain);
      gain.connect(context.destination);
      // A previous held tone may have a short stop ramp still scheduled.
      const now = Math.max(context.currentTime, state.nextStartAt);
      gain.gain.setValueAtTime(held ? 0 : 1, now);
      if (held) gain.gain.linearRampToValueAtTime(1, now + .012);
      if (state.offset >= region) state.offset = 0;
      source.start(now, state.offset);
      state.source = source;
      state.gain = gain;
      state.startedAt = now;
      state.playing = true;
      state.nativePlaying = false;
      state.preparing = false;
      source.onended = () => {
        if (state.source !== source) return;
        source.disconnect(); gain.disconnect();
        state.source = null; state.gain = null; state.playing = false; state.offset = 0;
        cancelAnimationFrame(state.animation);
        updateTransport();
      };
      drawWaveform(buffer);
      setStatus(held ? 'Repeating the settled region. Entry and stop are gently ramped.' : 'Playing the rendered note from its authored onset.');
      updateTransport();
      animate();
    } catch (error) {
      if (token !== state.playToken) return;
      state.preparing = false;
      setStatus(`${error.message || 'Audio could not play.'} Use the native player or download the WAV.`, true);
      ui['native-fallback'].open = true;
      updateTransport();
    }
  }

  function drawWaveform(buffer) {
    const canvas = ui.waveform;
    const bounds = canvas.getBoundingClientRect();
    if (!bounds.width) return;
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(bounds.width * scale);
    canvas.height = Math.round(bounds.height * scale);
    const drawing = canvas.getContext('2d');
    if (!drawing) return;
    drawing.clearRect(0, 0, canvas.width, canvas.height);
    const data = buffer.getChannelData(0);
    const count = isHeld() ? Math.min(data.length, Math.round(state.selected.loop.regionSeconds * buffer.sampleRate)) : data.length;
    const columns = Math.floor(bounds.width);
    const stride = Math.max(1, Math.floor(count / columns));
    let displayPeak = .000001;
    for (let sample = 0; sample < count; sample++) displayPeak = Math.max(displayPeak, Math.abs(data[sample]));
    const displayScale = canvas.height * .42 / displayPeak;
    drawing.strokeStyle = '#bd6377';
    drawing.lineWidth = scale;
    drawing.beginPath();
    for (let column = 0; column < columns; column++) {
      let low = 0; let high = 0;
      const first = Math.floor(column / columns * count);
      const last = Math.min(count, first + stride);
      // Preserve the actual range in each display column rather than a decorative curve.
      for (let sample = first; sample < last; sample++) { low = Math.min(low, data[sample]); high = Math.max(high, data[sample]); }
      const x = (column + .5) * scale;
      drawing.moveTo(x, canvas.height / 2 - high * displayScale);
      drawing.lineTo(x, canvas.height / 2 - low * displayScale);
    }
    drawing.stroke();
    ui['wave-message'].hidden = true;
    ui['wave-caption'].textContent = isHeld() ? 'HELD REGION · AUTO SCALED' : 'WAVEFORM · AUTO SCALED';
  }

  async function prepareWaveform() {
    const token = ++state.selectionToken;
    ui['wave-message'].hidden = false;
    ui['wave-message'].textContent = 'Loading the actual waveform…';
    state.buffer = null;
    const drawing = ui.waveform.getContext('2d');
    drawing?.clearRect(0, 0, ui.waveform.width, ui.waveform.height);
    try {
      const buffer = await getBuffer(state.selected);
      if (token !== state.selectionToken) return;
      state.buffer = buffer;
      drawWaveform(buffer);
      updateTransport();
    } catch (_) {
      if (token !== state.selectionToken) return;
      ui['wave-message'].textContent = 'Waveform unavailable. Try the native player or download the WAV.';
    }
  }

  function renderDetails(clip) {
    const details = ui['patch-details'];
    details.replaceChildren();
    const add = (name, value) => { details.append(node('dt', '', name), node('dd', '', value)); };
    add('Pitch', `${clip.noteName} · MIDI ${clip.note}`);
    add('Velocity', nice(clip.velocity));
    if (clip.comparisonLabel) add('Version', clip.comparisonLabel);
    if (clip.gesture) add('Gesture', clip.gesture);
    if (clip.macro) add('Study', `${clip.macro} · ${clip.setting || nice(clip.value)}`);
    for (const [name, value] of Object.entries(clip.macros || {})) add(name, nice(value));
    add('WAV duration', `${nice(clip.seconds)} s`);
    const metrics = clip.metrics?.[state.mode];
    if (metrics) {
      if (Number.isFinite(metrics.peak)) add('Peak', `${(20 * Math.log10(Math.max(metrics.peak, 1e-12))).toFixed(1)} dBFS`);
      if (Number.isFinite(metrics.rms)) add('RMS', `${(20 * Math.log10(Math.max(metrics.rms, 1e-12))).toFixed(1)} dBFS`);
    }
    if (clip.sha256?.[state.mode]) add('Audio SHA-256', `${clip.sha256[state.mode].slice(0, 16)}…`);
    if (clip.rawOrigin) add('Raw source', clip.rawOrigin);
    if (Number.isFinite(clip.rawExportGain)) add('Raw export gain', nice(clip.rawExportGain));
    if (clip.diagnostics) {
      if (Number.isFinite(clip.diagnostics.effectiveSpreadHz)) add('Fitted spread', `${nice(clip.diagnostics.effectiveSpreadHz)} Hz`);
      if (Number.isFinite(clip.diagnostics.motionHz)) add('Motion rate', `${nice(clip.diagnostics.motionHz)} Hz`);
      if (Number.isFinite(clip.diagnostics.effectiveHz)) add('Rendered pitch', `${nice(clip.diagnostics.effectiveHz)} Hz`);
    }
    const pair = state.manifest.comparisonPairs?.find((entry) => entry.id === clip.comparisonPair);
    const transient = pair?.transient?.[clip.id === pair.originalId ? 'original' : 'revised']?.[state.mode];
    if (transient) {
      if (Number.isFinite(transient.rms)) add('First 80 ms RMS', transient.rms.toFixed(5));
      if (Number.isFinite(transient.bandRms)) add(`${nice(transient.bandLowHz / 1000)}–${nice(transient.bandHighHz / 1000)} kHz attack RMS`, transient.bandRms.toFixed(5));
    }
    ui['loop-detail'].textContent = isHeld(clip)
      ? `Onset-free held tone. The WAV contains ${clip.loop.playbackRepetitions || 3} copies; the main player repeats one ${nice(clip.loop.regionSeconds)} s region. Engine seam check: ${clip.loop.seamPass === false ? 'requires review' : clip.loop.seamPass === true ? 'passed' : 'see manifest'}.`
      : 'Finite note: its attack and release are part of the render.';
  }

  function reviewFor(clip) {
    if (!clip) return {};
    if (Object.prototype.hasOwnProperty.call(state.notes, clip.id)) return state.notes[clip.id] || {};
    const baseline = state.manifest?.baseline;
    if (!baseline || clip.reviewAudioHash !== baseline.audioHash || String(clip.reviewRevision) !== String(baseline.revision)) return {};
    const originalId = clip.reviewClipId || clip.id;
    const legacy = state.legacyNotes[originalId];
    return legacy ? { ...legacy, inherited: true, provenance: {
      reviewRevision: baseline.revision, auditionAudioHash: baseline.audioHash,
      soundRevision: baseline.revision, soundAudioHash: baseline.audioHash,
      sourceClipId: originalId, sourceStorageKey: state.legacyKeys[originalId]
    } } : {};
  }

  function restoreReview() {
    const saved = reviewFor(state.selected);
    ui['listening-note'].value = saved.note || '';
    verdictButtons.forEach((button) => button.setAttribute('aria-pressed', String(saved.verdict === button.dataset.verdict)));
    ui['save-status'].textContent = !state.storageAvailable ? 'Device storage unavailable. Export to keep your notes.'
      : saved.inherited ? 'Original R1 note. Edits save separately for this revision.'
        : saved.updatedAt ? 'Saved on this device for this version.' : 'Your ears are the final check.';
  }

  function reviewedCount() {
    const count = state.manifest.clips.filter((clip) => {
      const review = reviewFor(clip); return review.verdict || review.note?.trim();
    }).length;
    ui['review-count'].textContent = `${count} reviewed`;
  }

  function saveReview(verdict) {
    if (!state.selected) return;
    const previous = reviewFor(state.selected);
    const clip = state.selected;
    const review = { ...previous, inherited: false, note: ui['listening-note'].value, updatedAt: new Date().toISOString(), provenance: {
      reviewRevision: state.manifest.revision, auditionAudioHash: state.manifest.audioHash,
      soundRevision: clip.reviewRevision || state.manifest.revision,
      soundAudioHash: clip.reviewAudioHash || state.manifest.audioHash,
      sourceClipId: clip.reviewClipId || clip.id,
      ...(previous.inherited ? { basedOn: previous.provenance } : previous.provenance?.basedOn ? { basedOn: previous.provenance.basedOn } : {})
    } };
    if (verdict !== undefined) review.verdict = previous.verdict === verdict ? '' : verdict;
    state.notes[state.selected.id] = review;
    try { localStorage.setItem(state.storageKey, JSON.stringify(state.notes)); state.storageAvailable = true; }
    catch (_) { state.storageAvailable = false; }
    verdictButtons.forEach((button) => button.setAttribute('aria-pressed', String(review.verdict === button.dataset.verdict)));
    ui['save-status'].textContent = state.storageAvailable ? 'Saved separately for this version.' : 'Device storage unavailable. Export to keep your notes.';
    reviewedCount();
    // Keep keyboard focus on the card rather than rebuilding the render grid.
    document.querySelectorAll('.clip-card').forEach((card) => {
      card.querySelector('.clip-review').textContent = card.dataset.pairId
        ? pairVerdict(state.manifest.comparisonPairs.find((pair) => pair.id === card.dataset.pairId))
        : verdictText(reviewFor(getClip(card.dataset.clipId)));
    });
  }

  const verdictText = (review) => review?.verdict || (review?.note?.trim() ? 'noted' : '');
  function pairVerdict(pair) {
    if (!pair) return '';
    const original = verdictText(reviewFor(getClip(pair.originalId)));
    const revised = verdictText(reviewFor(getClip(pair.revisedId)));
    return [original && `R1: ${original}`, revised && `R2: ${revised}`].filter(Boolean).join(' · ');
  }

  function syncRevisionControls() {
    const pair = state.manifest?.comparisonPairs?.find((entry) => entry.id === state.pairId);
    if (!pair) return;
    ui['revision-pair'].value = pair.id;
    ui['revision-description'].textContent = pair.description || 'Compare the first speech at the same pitch and gesture. Matched playback keeps the listening level consistent.';
    ui['revision-original'].setAttribute('aria-pressed', String(state.selected?.id === pair.originalId));
    ui['revision-revised'].setAttribute('aria-pressed', String(state.selected?.id === pair.revisedId));
    document.querySelectorAll('[data-pair-id]').forEach((card) => {
      const selected = card.dataset.pairId === pair.id;
      card.setAttribute('aria-pressed', String(selected));
      card.querySelector('.clip-icon').textContent = selected ? '●' : '↗';
    });
  }

  function selectRevisionPair(pair, version = 'revised', play = false) {
    if (!pair) return;
    state.pairId = pair.id;
    ui['compare-a'].value = pair.originalId;
    ui['compare-b'].value = pair.revisedId;
    selectClip(getClip(version === 'original' ? pair.originalId : pair.revisedId), version === 'original' ? 'a' : 'b');
    syncRevisionControls();
    if (play) playSelected();
  }

  function selectClip(clip, comparison = null) {
    if (!clip) return;
    stopPlayback();
    state.selected = clip;
    state.comparison = comparison;
    if (clip.comparisonPair) state.pairId = clip.comparisonPair;
    ui['player-category'].textContent = clip.comparisonLabel
      ? `PIPE ATTACK / ${clip.comparisonLabel.toUpperCase()}`
      : (sectionNames[clip.section] || clip.section).toUpperCase();
    ui['player-title'].textContent = clip.label;
    ui['player-detail'].textContent = clip.description || '';
    ui['player-voice'].textContent = clip.voice;
    ui['selected-tags'].textContent = `${clip.noteName} · ${isHeld(clip) ? 'SETTLED' : clip.gesture || `VEL ${nice(clip.velocity)}`}`;
    ui['wav-link'].href = clipUrl(clip);
    ui['wav-link'].download = `${clip.id}-${state.mode}.wav`;
    ui['recipe-link'].hidden = !clip.recipeUrl;
    if (clip.recipeUrl) { ui['recipe-link'].href = resolveAsset(clip.recipeUrl); ui['recipe-link'].download = `${clip.id}.json`; }
    nativeAudio.src = clipUrl(clip);
    nativeAudio.loop = isHeld(clip);
    ui['native-help'].textContent = isHeld(clip)
      ? 'The main player repeats one exact settled region with entry and stop ramps. This fallback repeats the full held WAV, which contains three copies. Only one player runs at a time.'
      : 'Use your browser’s player if the main player cannot decode this WAV. Only one player runs at a time.';
    ui['review-prompt'].textContent = isHeld(clip)
      ? 'Listen for a clean wrap, a stable pitch, and a body worth holding.'
      : clip.voice === 'MUSETTE' ? 'Listen for moving reeds, a useful bellows gesture, and a clear root.'
          : clip.voice === 'PIPE' ? 'Listen to the pipe’s first speech, its wind, and the body finding its tone.'
          : 'Use this existing voice as a reference. What does the prototype add?';
    renderDetails(clip);
    restoreReview();
    document.querySelectorAll('.clip-card').forEach((card) => {
      const selected = card.dataset.clipId === clip.id;
      card.setAttribute('aria-pressed', String(selected));
      card.querySelector('.clip-icon').textContent = selected ? '●' : '↗';
    });
    syncRevisionControls();
    setStatus(isHeld(clip) ? 'Settled tone, without an attack. Play to repeat the held region.' : 'Ready. The main player preserves the finite note’s onset.');
    updateTransport();
    prepareWaveform();
  }

  function setMode(mode) {
    if (!state.selected || mode === state.mode) return;
    const running = state.playing || state.nativePlaying;
    const clip = state.selected;
    const comparison = state.comparison;
    stopPlayback();
    state.mode = mode;
    ui['mode-matched'].setAttribute('aria-pressed', String(mode === 'matched'));
    ui['mode-raw'].setAttribute('aria-pressed', String(mode === 'raw'));
    ui['level-hint'].textContent = mode === 'matched' ? 'Matched for comparison' : 'Source render level';
    selectClip(clip, comparison);
    if (running) playSelected();
    else setStatus(mode === 'matched' ? 'Matched version selected. Gain is set offline for level comparison.' : 'Raw source version selected. See patch details for its render origin; source levels can differ.');
  }

  function renderLibrary() {
    const selectedSection = state.manifest.sections.find((section) => section.id === state.section);
    ui['section-title'].textContent = sectionNames[state.section] || selectedSection?.label || state.section;
    ui['section-description'].textContent = sectionCopy[state.section] || selectedSection?.description || '';
    document.querySelectorAll('[data-section]').forEach((button) => button.setAttribute('aria-current', String(button.dataset.section === state.section)));
    const paired = state.section === 'comparisons' && state.manifest.comparisonPairs?.length;
    ui['revision-study'].hidden = !paired;
    ui['library-filters'].hidden = Boolean(paired);
    ui['macro-filter-wrap'].hidden = state.section !== 'sweeps';
    if (paired) {
      ui['clip-count'].textContent = `${state.manifest.comparisonPairs.length} pairs`;
      ui['empty-filter'].hidden = true;
      ui['clip-grid'].replaceChildren();
      state.manifest.comparisonPairs.forEach((pair, index) => {
        const clip = getClip(pair.revisedId);
        const card = node('button', 'clip-card');
        card.type = 'button'; card.dataset.pairId = pair.id;
        card.setAttribute('aria-label', `Choose comparison ${pair.label}`);
        const top = node('span', 'clip-card-top');
        top.append(node('span', '', `${String(index + 1).padStart(2, '0')} / PIPE ATTACK`), node('span', 'clip-icon', '↗'));
        const foot = node('span', 'clip-card-foot');
        foot.append(node('span', '', `${clip?.noteName || ''} · R1 / R2`), node('span', 'clip-review', pairVerdict(pair)));
        card.append(top, node('span', 'clip-card-title', pair.label), node('span', 'clip-card-description', pair.description || 'Original R1 and More chiff R2.'), foot);
        card.addEventListener('click', () => selectRevisionPair(pair));
        ui['clip-grid'].append(card);
      });
      syncRevisionControls();
      return;
    }
    const clips = state.manifest.clips.filter((clip) => clip.section === state.section
      && (!ui['voice-filter'].value || clip.voice === ui['voice-filter'].value)
      && (!ui['note-filter'].value || String(clip.note) === ui['note-filter'].value)
      && (state.section !== 'sweeps' || !ui['macro-filter'].value || clip.macro === ui['macro-filter'].value));
    ui['clip-count'].textContent = `${clips.length} render${clips.length === 1 ? '' : 's'}`;
    ui['clip-grid'].replaceChildren();
    ui['empty-filter'].hidden = clips.length > 0;
    clips.forEach((clip, index) => {
      const card = node('button', 'clip-card');
      card.type = 'button'; card.dataset.clipId = clip.id;
      card.setAttribute('aria-pressed', String(clip.id === state.selected?.id));
      card.setAttribute('aria-label', `Select ${clip.label}, ${clip.noteName}, ${clip.voice}`);
      const top = node('span', 'clip-card-top');
      top.append(node('span', '', `${String(index + 1).padStart(2, '0')} / ${clip.voice}`), node('span', 'clip-icon', clip.id === state.selected?.id ? '●' : '↗'));
      const title = node('span', 'clip-card-title', clip.label);
      const description = node('span', 'clip-card-description', clip.description || `${clip.noteName} · ${clip.gesture || clip.setting || 'rendered note'}`);
      const foot = node('span', 'clip-card-foot');
      const reviewed = reviewFor(clip);
      foot.append(node('span', '', `${clip.noteName} · ${isHeld(clip) ? 'HELD' : `${nice(clip.seconds)}s`}`), node('span', 'clip-review', reviewed?.verdict || (reviewed?.note?.trim() ? 'noted' : '')));
      card.append(top, title, description, foot);
      card.addEventListener('click', () => selectClip(clip));
      ui['clip-grid'].append(card);
    });
  }

  function fillSelect(select, values, label) {
    select.replaceChildren(node('option', '', label));
    select.firstChild.value = '';
    values.forEach(([value, text]) => { const option = node('option', '', text); option.value = value; select.append(option); });
  }

  function buildLibrary() {
    const { clips, sections } = state.manifest;
    ui['section-tabs'].replaceChildren();
    const orderedSections = [...sections].sort((a, b) => (a.id === 'comparisons' ? -1 : b.id === 'comparisons' ? 1 : 0));
    orderedSections.forEach((section) => {
      const button = node('button', ''); button.type = 'button'; button.dataset.section = section.id;
      const count = section.id === 'comparisons' ? state.manifest.comparisonPairs?.length || 0 : clips.filter((clip) => clip.section === section.id).length;
      button.append(node('span', '', sectionNames[section.id] || section.label), node('span', 'tab-count', count));
      button.addEventListener('click', () => {
        state.section = section.id;
        // Every study opens with all of its renders visible.
        ui['voice-filter'].value = ''; ui['note-filter'].value = ''; ui['macro-filter'].value = '';
        renderLibrary();
      });
      ui['section-tabs'].append(button);
    });
    fillSelect(ui['voice-filter'], [...new Set(clips.map((clip) => clip.voice))].map((voice) => [voice, voice]), 'All voices');
    const pitches = [...new Map(clips.map((clip) => [clip.note, clip.noteName])).entries()].sort((a, b) => a[0] - b[0]);
    fillSelect(ui['note-filter'], pitches.map(([note, name]) => [String(note), name]), 'All pitches');
    fillSelect(ui['macro-filter'], [...new Set(clips.map((clip) => clip.macro).filter(Boolean))].map((macro) => [macro, macro]), 'All controls');
    ['a', 'b'].forEach((slot) => {
      ui[`compare-${slot}`].replaceChildren();
      sections.forEach((section) => {
        const group = node('optgroup', ''); group.label = sectionNames[section.id] || section.label;
        clips.filter((clip) => clip.section === section.id).forEach((clip) => {
          const option = node('option', '', `${clip.label} · ${clip.noteName}`); option.value = clip.id; group.append(option);
        });
        ui[`compare-${slot}`].append(group);
      });
    });
    const first = clips.find((clip) => clip.section === 'identities' && clip.voice === 'PIPE' && clip.note === 60 && clip.gesture === 'restrained') || clips[0];
    const second = clips.find((clip) => clip.section === 'identities' && clip.voice === 'MUSETTE' && clip.note === first.note && clip.gesture === 'restrained') || clips[1] || first;
    ui['compare-a'].value = first.id; ui['compare-b'].value = second.id;
    if (state.manifest.comparisonPairs?.length) {
      ui['revision-pair'].replaceChildren();
      state.manifest.comparisonPairs.forEach((pair) => {
        const option = node('option', '', pair.label); option.value = pair.id; ui['revision-pair'].append(option);
      });
      const initialPair = state.manifest.comparisonPairs.find((pair) => pair.revisedId === first.id) || state.manifest.comparisonPairs[0];
      selectRevisionPair(initialPair);
    } else selectClip(first);
    renderLibrary();
    reviewedCount();
    renderLegacyHistory();
  }

  function renderLegacyHistory() {
    const reviews = Object.entries(state.legacyNotes).filter(([, review]) => review.verdict || review.note?.trim());
    ui['legacy-history'].hidden = reviews.length === 0;
    ui['legacy-count'].textContent = `(${reviews.length})`;
    ui['legacy-list'].replaceChildren();
    reviews.forEach(([id, review]) => {
      const clip = state.baselineManifest?.clips?.find((entry) => entry.id === id);
      const entry = node('article', 'legacy-entry');
      const header = node('div', 'legacy-entry-header');
      header.append(node('h4', '', clip?.label || id.replaceAll('_', ' ')), node('span', '', review.verdict || 'noted'));
      entry.append(header, node('p', '', review.note || 'No written note.'));
      const loadable = state.manifest.clips.find((current) => current.reviewClipId === id && current.reviewAudioHash === state.manifest.baseline?.audioHash);
      if (loadable) {
        const button = node('button', 'text-button', 'Load original audio ↑'); button.type = 'button';
        button.addEventListener('click', () => { selectClip(loadable); ui['play-button'].focus(); });
        entry.append(button);
      }
      ui['legacy-list'].append(entry);
    });
  }

  function loadLegacyNotes() {
    const baseline = state.manifest.baseline;
    state.legacyNotes = {}; state.legacySources = []; state.legacyKeys = {}; state.baselineManifest = null;
    if (!baseline?.audioHash) return;
    const keys = [`registers:${baseline.revision}:${baseline.audioHash}`, `snipsnap.registers.notes.v1.${baseline.revision}.${baseline.audioHash}`];
    keys.forEach((key) => {
      try {
        const raw = localStorage.getItem(key);
        if (!raw) return;
        const records = JSON.parse(raw);
        if (!records || typeof records !== 'object' || Array.isArray(records)) return;
        state.legacySources.push({ storageKey: key, notes: records });
        Object.entries(records).forEach(([id, review]) => {
          if (review && typeof review === 'object') { state.legacyNotes[id] = review; state.legacyKeys[id] = key; }
        });
      } catch (_) { /* Earlier records stay untouched even if a store cannot be read. */ }
    });
    if (baseline.manifest) fetch(resolveAsset(baseline.manifest)).then((response) => {
      if (!response.ok) throw new Error('Baseline metadata unavailable.');
      return response.json();
    }).then((manifest) => { state.baselineManifest = manifest; renderLegacyHistory(); }).catch(() => { /* IDs still identify the original notes. */ });
  }

  async function revisionKey(manifest) {
    if (manifest.audioHash) return `snipsnap.registers.notes.v1.${manifest.revision}.${manifest.audioHash}`;
    const identity = manifest.clips.map((clip) => `${clip.id}:${clip.sha256?.raw || clip.raw}:${clip.sha256?.matched || clip.matched}`).sort().join('|');
    if (window.crypto?.subtle) {
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(identity));
      return `snipsnap.registers.notes.v1.${manifest.revision}.${[...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')}`;
    }
    // Including every file identity still isolates revisions when crypto is unavailable.
    return `snipsnap.registers.notes.v1.${manifest.revision}.${identity}`;
  }

  async function loadManifest() {
    ui['load-status'].hidden = false; ui['load-error'].hidden = true;
    try {
      const response = await fetch(manifestUrl, { cache: 'no-store' });
      if (!response.ok) throw new Error(`The manifest returned HTTP ${response.status}.`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length || !Array.isArray(manifest.sections)) throw new Error('The render manifest is missing its clip library.');
      if (manifest.clips.some((clip) => !clip.id || !clip.raw || !clip.matched || !clip.section || !clip.voice)) throw new Error('A render entry is missing its audio files or identity.');
      if (new Set(manifest.clips.map((clip) => clip.id)).size !== manifest.clips.length) throw new Error('The render manifest contains duplicate clip IDs.');
      state.manifest = manifest;
      if (manifest.comparisonPairs?.some((pair) => !getClip(pair.originalId) || !getClip(pair.revisedId))) throw new Error('A comparison is missing its original or revised audio.');
      state.section = manifest.comparisonPairs?.length ? 'comparisons' : manifest.sections.some((section) => section.id === 'identities') ? 'identities' : manifest.sections[0].id;
      state.storageKey = await revisionKey(manifest);
      try {
        const saved = JSON.parse(localStorage.getItem(state.storageKey) || '{}');
        state.notes = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
      } catch (_) { state.notes = {}; state.storageAvailable = false; }
      loadLegacyNotes();
      ui['player-format'].textContent = `${manifest.channels === 1 ? 'MONO' : `${manifest.channels || 1} CH`} / ${nice((manifest.sampleRate || 44100) / 1000)} kHz`;
      ui['manifest-revision'].textContent = `Revision ${manifest.revision || 1} · ${manifest.clips.length} offline renders${manifest.baseline ? ' · original R1 preserved' : ''} · awaiting sonic approval`;
      ui['load-status'].hidden = true; ui['loaded-content'].hidden = false;
      buildLibrary();
    } catch (error) {
      ui['load-status'].hidden = true; ui['load-error'].hidden = false;
      const localHint = location.protocol === 'file:' ? ' Open this folder through a web server; browsers restrict file:// fetches.' : ' Check your connection, then try again.';
      ui['load-error-detail'].textContent = `${error.message || 'The manifest could not be read.'}${localHint}`;
    }
  }

  function playComparison(slot) {
    const clip = getClip(ui[`compare-${slot}`].value);
    if (!clip) return;
    selectClip(clip, slot);
    playSelected();
  }

  function exportNotes() {
    if (!state.manifest) return;
    const reviews = state.manifest.clips.filter((clip) => {
      const review = reviewFor(clip); return review.verdict || review.note?.trim();
    }).map((clip) => ({
      clipId: clip.id, label: clip.label, voice: clip.voice, note: clip.note, noteName: clip.noteName,
      section: clip.section, version: clip.comparisonLabel || clip.reviewRevision || state.manifest.revision,
      sha256: clip.sha256, ...reviewFor(clip)
    }));
    const visibleVersions = state.manifest.clips.map((clip) => ({
      clipId: clip.id, label: clip.label, section: clip.section,
      soundRevision: clip.reviewRevision || state.manifest.revision,
      soundAudioHash: clip.reviewAudioHash || state.manifest.audioHash,
      comparisonPair: clip.comparisonPair || null, comparisonLabel: clip.comparisonLabel || null, sha256: clip.sha256
    }));
    const legacyReviews = state.manifest.baseline ? {
      revision: state.manifest.baseline.revision, audioHash: state.manifest.baseline.audioHash,
      manifest: state.manifest.baseline.manifest, sources: state.legacySources,
      reviews: Object.entries(state.legacyNotes).map(([id, saved]) => ({
        clipId: id, label: state.baselineManifest?.clips?.find((clip) => clip.id === id)?.label || id,
        ...saved, provenance: { reviewRevision: state.manifest.baseline.revision,
          auditionAudioHash: state.manifest.baseline.audioHash, sourceClipId: id, sourceStorageKey: state.legacyKeys[id] }
      }))
    } : null;
    const review = { schemaVersion: 2, engine: state.manifest.engine || 'REGISTERS', auditionRevision: state.manifest.revision, audioHash: state.manifest.audioHash || null, exportedAt: new Date().toISOString(), visibleVersions, reviews, legacyReviews };
    const url = URL.createObjectURL(new Blob([`${JSON.stringify(review, null, 2)}\n`], { type: 'application/json' }));
    const anchor = node('a', ''); anchor.href = url; anchor.download = `registers-listening-notes-${state.manifest.revision || 1}.json`;
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    ui['save-status'].textContent = `Exported ${reviews.length} listening review${reviews.length === 1 ? '' : 's'}.`;
  }

  ui['retry-load'].addEventListener('click', loadManifest);
  ui['revision-pair'].addEventListener('change', () => {
    const previous = state.manifest.comparisonPairs.find((pair) => pair.id === state.pairId);
    const version = state.selected?.id === previous?.originalId ? 'original' : 'revised';
    selectRevisionPair(state.manifest.comparisonPairs.find((pair) => pair.id === ui['revision-pair'].value), version);
  });
  ['original', 'revised'].forEach((version) => ui[`revision-${version}`].addEventListener('click', () => {
    const pair = state.manifest.comparisonPairs?.find((entry) => entry.id === state.pairId);
    selectRevisionPair(pair, version, true);
  }));
  ui['play-button'].addEventListener('click', playSelected);
  ui['stop-button'].addEventListener('click', () => { stopPlayback(); setStatus('Stopped. Ready from the beginning.'); });
  ui['mode-matched'].addEventListener('click', () => setMode('matched'));
  ui['mode-raw'].addEventListener('click', () => setMode('raw'));
  ui.seek.addEventListener('input', () => {
    if (!state.selected || isHeld()) return;
    const running = state.playing || state.nativePlaying;
    const target = Number(ui.seek.value);
    stopPlayback(); state.offset = target; updatePosition();
    if (running) playSelected();
  });
  ['voice-filter', 'note-filter', 'macro-filter'].forEach((id) => ui[id].addEventListener('change', renderLibrary));
  ui['reset-filters'].addEventListener('click', () => { ['voice-filter', 'note-filter', 'macro-filter'].forEach((id) => { ui[id].value = ''; }); renderLibrary(); });
  ui['listening-note'].addEventListener('input', () => saveReview());
  verdictButtons.forEach((button) => button.addEventListener('click', () => saveReview(button.dataset.verdict)));
  ['a', 'b'].forEach((slot) => {
    ui[`play-${slot}`].addEventListener('click', () => playComparison(slot));
    ui[`assign-${slot}`].addEventListener('click', () => {
      if (!state.selected) return;
      ui[`compare-${slot}`].value = state.selected.id;
      setStatus(`Comparison ${slot.toUpperCase()} is now ${state.selected.label}.`);
    });
    ui[`compare-${slot}`].addEventListener('change', () => {
      if (state.comparison === slot) { state.comparison = null; updateTransport(); }
    });
  });
  ui['export-notes'].addEventListener('click', exportNotes);
  nativeAudio.addEventListener('play', () => {
    // Stop Web Audio without pausing the native player that the user just started.
    const offset = nativeAudio.currentTime;
    stopPlayback(false, false);
    state.nativePlaying = true;
    state.offset = offset;
    updateTransport(); animate();
    setStatus(isHeld() ? 'Native player: repeating the complete held WAV. Use the main player for a single-region loop with ramps.' : 'Playing through the browser’s native player.');
  });
  nativeAudio.addEventListener('pause', () => {
    if (!state.nativePlaying) return;
    state.offset = nativeAudio.currentTime;
    state.nativePlaying = false; cancelAnimationFrame(state.animation); updateTransport();
  });
  nativeAudio.addEventListener('ended', () => { state.nativePlaying = false; state.offset = 0; cancelAnimationFrame(state.animation); updateTransport(); });
  nativeAudio.addEventListener('error', () => {
    if (!nativeAudio.getAttribute('src')) return;
    setStatus('The native player could not open this WAV. Try the main player, or download the file for a local audio app.', true);
  });
  document.addEventListener('keydown', (event) => {
    if (!state.manifest || event.altKey || event.ctrlKey || event.metaKey || event.repeat) return;
    const target = event.target;
    if (target.closest('input,textarea,select,[contenteditable="true"]')) return;
    if (event.code === 'Space') {
      if (target.closest('button,a,summary')) return;
      event.preventDefault(); playSelected();
    }
    else if (event.key.toLowerCase() === 'a') { event.preventDefault(); playComparison('a'); }
    else if (event.key.toLowerCase() === 'b') { event.preventDefault(); playComparison('b'); }
    else if (event.key === 'Escape') { event.preventDefault(); stopPlayback(); }
  });
  window.addEventListener('resize', () => { if (state.buffer) drawWaveform(state.buffer); });
  window.addEventListener('pagehide', () => stopPlayback());
  loadManifest();
})();
