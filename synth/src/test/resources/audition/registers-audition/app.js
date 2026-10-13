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
    'empty-filter', 'export-notes', 'manifest-revision'
  ].map((id) => [id, $(id)]));
  const nativeAudio = ui['native-audio'];
  const verdictButtons = [...document.querySelectorAll('[data-verdict]')];
  const sectionNames = {
    identities: 'First listen', velocity: 'Velocity', sweeps: 'Controls',
    loops: 'Held tones', references: 'Organ references'
  };
  const sectionCopy = {
    identities: 'PIPE and MUSETTE at three pitches, with restrained and expressive gestures. Start here, then compare the same pitch across voices.',
    velocity: 'A fixed patch at different velocities. Listen for a change in speech and body as well as level.',
    sweeps: 'One control moves at a time. Compare low, default, and high settings to hear what each gesture contributes.',
    loops: 'Settled, onset-free tones. The main player repeats the exact held region; listen for the seam and the persistence of the voice.',
    references: 'Existing SnipSnap organ voices at matching pitches. Compare what PIPE and MUSETTE add to the kit.'
  };
  const state = {
    manifest: null, selected: null, section: 'identities', mode: 'matched',
    notes: {}, storageKey: '', storageAvailable: true, context: null,
    source: null, gain: null, buffer: null, offset: 0, startedAt: 0,
    playing: false, nativePlaying: false, preparing: false, playToken: 0,
    selectionToken: 0, animation: 0, buffers: new Map(), comparison: null
  };
  const manifestUrl = new URL('manifest.json', document.baseURI);
  const resolveAsset = (path) => new URL(path, manifestUrl).href;
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
  const duration = () => isHeld() ? state.selected.loop.regionSeconds : (state.buffer?.duration || state.selected?.seconds || 0);
  const position = () => {
    if (state.nativePlaying) return nativeAudio.currentTime || 0;
    const value = state.offset + (state.playing && state.context ? state.context.currentTime - state.startedAt : 0);
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
      if (isHeld() && gain) {
        // Only settled held tones are ramped. Finite notes keep their authored onset.
        gain.gain.cancelScheduledValues(now);
        gain.gain.setValueAtTime(gain.gain.value, now);
        gain.gain.linearRampToValueAtTime(0, now + .012);
        try { source.stop(now + .013); } catch (_) { /* Already ended. */ }
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
      state.context = new Context();
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
      const now = context.currentTime;
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
      drawing.moveTo(x, canvas.height / 2 - high * canvas.height * .42);
      drawing.lineTo(x, canvas.height / 2 - low * canvas.height * .42);
    }
    drawing.stroke();
    ui['wave-message'].hidden = true;
    ui['wave-caption'].textContent = isHeld() ? 'ONE SETTLED LOOP REGION' : 'THE ACTUAL RENDER';
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
    if (clip.diagnostics) {
      if (Number.isFinite(clip.diagnostics.effectiveSpreadHz)) add('Fitted spread', `${nice(clip.diagnostics.effectiveSpreadHz)} Hz`);
      if (Number.isFinite(clip.diagnostics.motionHz)) add('Motion rate', `${nice(clip.diagnostics.motionHz)} Hz`);
      if (Number.isFinite(clip.diagnostics.effectiveHz)) add('Rendered pitch', `${nice(clip.diagnostics.effectiveHz)} Hz`);
    }
    ui['loop-detail'].textContent = isHeld(clip)
      ? `Onset-free held tone. The WAV contains ${clip.loop.playbackRepetitions || 3} copies; the main player repeats one ${nice(clip.loop.regionSeconds)} s region. Engine seam check: ${clip.loop.seamPass === false ? 'requires review' : clip.loop.seamPass === true ? 'passed' : 'see manifest'}.`
      : 'Finite note: its attack and release are part of the render.';
  }

  function restoreReview() {
    const saved = state.notes[state.selected.id] || {};
    ui['listening-note'].value = saved.note || '';
    verdictButtons.forEach((button) => button.setAttribute('aria-pressed', String(saved.verdict === button.dataset.verdict)));
    ui['save-status'].textContent = state.storageAvailable ? (saved.updatedAt ? 'Saved on this device.' : 'Your ears are the final check.') : 'Device storage unavailable. Export to keep your notes.';
  }

  function reviewedCount() {
    const count = Object.values(state.notes).filter((review) => review.verdict || review.note?.trim()).length;
    ui['review-count'].textContent = `${count} reviewed`;
  }

  function saveReview(verdict) {
    if (!state.selected) return;
    const previous = state.notes[state.selected.id] || {};
    const review = { ...previous, note: ui['listening-note'].value, updatedAt: new Date().toISOString() };
    if (verdict !== undefined) review.verdict = previous.verdict === verdict ? '' : verdict;
    state.notes[state.selected.id] = review;
    try { localStorage.setItem(state.storageKey, JSON.stringify(state.notes)); state.storageAvailable = true; }
    catch (_) { state.storageAvailable = false; }
    verdictButtons.forEach((button) => button.setAttribute('aria-pressed', String(review.verdict === button.dataset.verdict)));
    ui['save-status'].textContent = state.storageAvailable ? 'Saved on this device.' : 'Device storage unavailable. Export to keep your notes.';
    reviewedCount();
    // Keep keyboard focus on the card rather than rebuilding the render grid.
    document.querySelectorAll('.clip-card').forEach((card) => {
      const saved = state.notes[card.dataset.clipId];
      card.querySelector('.clip-review').textContent = saved?.verdict || (saved?.note?.trim() ? 'noted' : '');
    });
  }

  function selectClip(clip, comparison = null) {
    if (!clip) return;
    stopPlayback();
    state.selected = clip;
    state.comparison = comparison;
    ui['player-category'].textContent = (sectionNames[clip.section] || clip.section).toUpperCase();
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
    ui['macro-filter-wrap'].hidden = state.section !== 'sweeps';
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
      const title = node('h4', '', clip.label);
      const description = node('p', '', clip.description || `${clip.noteName} · ${clip.gesture || clip.setting || 'rendered note'}`);
      const foot = node('span', 'clip-card-foot');
      const reviewed = state.notes[clip.id];
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
    sections.forEach((section) => {
      const button = node('button', ''); button.type = 'button'; button.dataset.section = section.id;
      button.append(node('span', '', sectionNames[section.id] || section.label), node('span', 'tab-count', clips.filter((clip) => clip.section === section.id).length));
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
    selectClip(first);
    renderLibrary();
    reviewedCount();
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
      const response = await fetch(manifestUrl);
      if (!response.ok) throw new Error(`The manifest returned HTTP ${response.status}.`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length || !Array.isArray(manifest.sections)) throw new Error('The render manifest is missing its clip library.');
      if (manifest.clips.some((clip) => !clip.id || !clip.raw || !clip.matched || !clip.section || !clip.voice)) throw new Error('A render entry is missing its audio files or identity.');
      if (new Set(manifest.clips.map((clip) => clip.id)).size !== manifest.clips.length) throw new Error('The render manifest contains duplicate clip IDs.');
      state.manifest = manifest;
      state.section = manifest.sections.some((section) => section.id === 'identities') ? 'identities' : manifest.sections[0].id;
      state.storageKey = await revisionKey(manifest);
      try {
        const saved = JSON.parse(localStorage.getItem(state.storageKey) || '{}');
        state.notes = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
      } catch (_) { state.notes = {}; state.storageAvailable = false; }
      ui['player-format'].textContent = `${manifest.channels === 1 ? 'MONO' : `${manifest.channels || 1} CH`} / ${nice((manifest.sampleRate || 44100) / 1000)} kHz`;
      ui['manifest-revision'].textContent = `Revision ${manifest.revision || 1} · ${manifest.clips.length} offline renders · awaiting sonic approval`;
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
    const reviews = state.manifest.clips.filter((clip) => state.notes[clip.id]?.verdict || state.notes[clip.id]?.note?.trim()).map((clip) => ({
      clipId: clip.id, label: clip.label, voice: clip.voice, note: clip.note, noteName: clip.noteName,
      section: clip.section, sha256: clip.sha256, ...state.notes[clip.id]
    }));
    const review = { schemaVersion: 1, engine: state.manifest.engine || 'REGISTERS', auditionRevision: state.manifest.revision, audioHash: state.manifest.audioHash || null, exportedAt: new Date().toISOString(), reviews };
    const url = URL.createObjectURL(new Blob([`${JSON.stringify(review, null, 2)}\n`], { type: 'application/json' }));
    const anchor = node('a', ''); anchor.href = url; anchor.download = `registers-listening-notes-r${state.manifest.revision || 1}.json`;
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    ui['save-status'].textContent = `Exported ${reviews.length} listening review${reviews.length === 1 ? '' : 's'}.`;
  }

  ui['retry-load'].addEventListener('click', loadManifest);
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
