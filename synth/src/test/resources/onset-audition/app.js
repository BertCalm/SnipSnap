/* ONSET first listen. Every audio clip is an offline engine render. */
'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const audio = $('audio');
  const STORAGE_KEY = 'snipsnap-onset-first-listen-v1';
  const VOICES = {
    KICK: { title: 'Kick', left: 'OSCILLATOR BODY', right: 'MODAL MEMBRANE', description: 'A swept, weighted body meets the ring of a struck membrane. Listen for the attack staying together as the material changes.' },
    HAT_CLOSED: { title: 'Closed hat', left: 'SQUARE CLUSTER', right: 'RESONANT NOISE', description: 'A metallic oscillator cluster meets resonant noise. Listen for a useful shift in texture without losing the short, clear hat.' }
  };
  const state = { manifest: null, clips: [], selected: null, mode: 'matched', playing: false, loading: false, token: 0, sequence: [], sequenceIndex: 0, sequenceVoice: null, notes: {}, storageAvailable: true,
    context: null, buffers: new Map(), source: null, backend: null, frame: 0, startAt: 0, duration: 0 };
  const OUTPUT_WARMUP_SECONDS = .15;
  const cards = new Map();
  const playButtons = new Map();
  const verdicts = new Map();
  const sequenceButtons = new Map();

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function pct(clip) { return `${Math.round(clip.blend * 100)}%`; }
  function name(clip) { return clip.title || `${VOICES[clip.voice]?.title || 'Beat'} · Blend ${pct(clip)}`; }
  function path(clip) { return clip?.[state.mode] || ''; }
  function time(seconds) { const value = Math.max(0, Math.floor(Number(seconds) || 0)); return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`; }
  function noteFor(id) { return state.notes[id] || { verdict: '', text: '', mode: 'matched' }; }
  function announce(message, isError = false) { $('audio-status').textContent = message; $('audio-status').classList.toggle('error-status', isError); }
  function loadNotes() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      if (saved && typeof saved === 'object' && !Array.isArray(saved)) {
        for (const [id, note] of Object.entries(saved)) {
          if (!note || typeof note !== 'object') continue;
          state.notes[id] = { verdict: ['keep', 'maybe', 'pass'].includes(note.verdict) ? note.verdict : '', text: typeof note.text === 'string' ? note.text : '', mode: note.mode === 'raw' ? 'raw' : 'matched' };
        }
      }
    } catch (_) { state.storageAvailable = false; }
  }
  function persistNotes() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state.notes)); }
    catch (_) { state.storageAvailable = false; }
    $('note-status').textContent = state.storageAvailable ? 'Saved in this browser.' : 'Kept for this visit. Export to keep your notes.';
    updateReviewCount();
  }
  function updateReviewCount() {
    const count = state.clips.filter((clip) => { const note = noteFor(clip.id); return note.verdict || note.text.trim(); }).length;
    $('review-count').textContent = `${count} of ${state.clips.length} reviewed`;
  }
  function updateNoteEditor() {
    const clip = state.selected;
    $('note-clip').disabled = !clip;
    $('note-text').disabled = !clip;
    if (!clip) return;
    const note = noteFor(clip.id);
    $('note-clip').value = clip.id;
    $('note-text').value = note.text;
    $('note-mode').textContent = `Listening: ${state.mode === 'matched' ? 'matched levels' : 'raw levels'}`;
  }
  function updateCards() {
    for (const clip of state.clips) {
      const selected = state.selected?.id === clip.id;
      const playing = selected && (state.playing || state.loading);
      cards.get(clip.id)?.classList.toggle('selected', selected);
      cards.get(clip.id)?.classList.toggle('playing', playing);
      const button = playButtons.get(clip.id);
      if (button) {
        button.setAttribute('aria-pressed', String(playing));
        const action = state.sequence.length ? 'Play' : playing ? 'Stop' : 'Play';
        button.setAttribute('aria-label', `${action} ${name(clip)}${state.sequence.length ? ' independently' : ''}, ${state.mode === 'matched' ? 'matched' : 'raw'} levels`);
        button.querySelector('.play-symbol').textContent = playing ? state.sequence.length ? '↻' : '■' : '▶';
      }
    }
    for (const [voice, button] of sequenceButtons) {
      const active = state.sequenceVoice === voice && state.sequence.length > 0;
      button.textContent = active ? '■ Stop comparison' : '▶ Play all five';
      button.setAttribute('aria-pressed', String(active));
      button.setAttribute('aria-label', active ? `Stop ${VOICES[voice].title} comparison` : `Play all five ${VOICES[voice].title} blends in order`);
    }
  }
  function updateTransport() {
    const clip = state.selected;
    $('stop-all').disabled = !state.playing && !state.loading && !state.sequence.length;
    $('replay').disabled = !clip;
    $('replay').classList.toggle('playing', state.playing);
    $('replay').firstElementChild.textContent = state.sequence.length ? '↻' : state.playing || state.loading ? '■' : '▶';
    $('replay').setAttribute('aria-label', state.sequence.length ? 'Replay selected sound independently' : `${state.playing || state.loading ? 'Stop' : 'Play'} selected sound`);
    $('transport-label').textContent = clip ? `${clip.kind === 'beat' ? 'BEAT' : VOICES[clip.voice]?.title.toUpperCase() || clip.voice} / BLEND ${pct(clip)} / ${state.mode === 'matched' ? 'MATCHED' : 'RAW'}` : 'ONSET / READY';
    $('transport-title').textContent = clip ? name(clip) : 'Choose a sound to listen.';
    const download = $('download-wav');
    if (clip) {
      download.href = path(clip);
      download.download = path(clip).split('/').pop();
      download.setAttribute('aria-disabled', 'false');
      download.removeAttribute('tabindex');
    } else {
      download.removeAttribute('href');
      download.setAttribute('aria-disabled', 'true');
      download.tabIndex = -1;
    }
    updateCards();
  }
  function stop(message = 'Playback stopped.') {
    state.token++;
    cancelAnimationFrame(state.frame);
    state.frame = 0;
    const source = state.source;
    state.source = null;
    state.backend = null;
    if (source) {
      source.onended = null;
      try { source.stop(); } catch (_) { /* Source already ended. */ }
      source.disconnect();
    }
    audio.pause();
    try { audio.currentTime = 0; } catch (_) { /* No loaded audio yet. */ }
    state.playing = false;
    state.loading = false;
    state.sequence = [];
    state.sequenceIndex = 0;
    state.sequenceVoice = null;
    $('playback-progress').value = 0;
    $('transport-time').textContent = '0:00';
    updateTransport();
    announce(message);
  }
  function select(clip) { state.selected = clip; updateNoteEditor(); updateTransport(); }
  function audioContext() {
    // Local downloaded pages can play relative WAVs, but browsers block fetching
    // those files for decoding. Keep native playback for that offline case.
    const Constructor = window.AudioContext || window.webkitAudioContext;
    if (!Constructor || location.protocol === 'file:') return null;
    if (!state.context || state.context.state === 'closed') {
      state.context = new Constructor();
      state.buffers.clear();
    }
    return state.context;
  }
  function decodedBuffer(context, url) {
    if (state.buffers.has(url)) return state.buffers.get(url);
    const promise = fetch(url).then((response) => {
      if (!response.ok) throw new Error('Audio unavailable');
      return response.arrayBuffer();
    }).then((bytes) => context.decodeAudioData(bytes)).catch((error) => {
      // A failed download/decode must be retryable on the next click.
      if (state.buffers.get(url) === promise) state.buffers.delete(url);
      throw error;
    });
    state.buffers.set(url, promise);
    return promise;
  }
  function started(clip, token) {
    if (token !== state.token || !state.loading) return;
    state.loading = false;
    state.playing = true;
    announce(state.sequence.length ? `Comparing ${VOICES[clip.voice]?.title || 'sounds'} · ${state.sequenceIndex + 1} of ${state.sequence.length}` : `Playing ${name(clip)}.`);
    updateTransport();
  }
  function trackProgress(clip, source, token) {
    if (token !== state.token || state.source !== source) return;
    const elapsed = Math.max(0, state.context.currentTime - state.startAt);
    if (state.context.currentTime >= state.startAt) started(clip, token);
    $('playback-progress').value = Math.min(1, elapsed / state.duration);
    $('transport-time').textContent = time(Math.min(elapsed, state.duration));
    state.frame = requestAnimationFrame(() => trackProgress(clip, source, token));
  }
  function finished(token) {
    if (token !== state.token) return;
    state.playing = false;
    state.loading = false;
    state.backend = null;
    if (state.sequence.length && state.sequenceIndex + 1 < state.sequence.length) {
      updateTransport();
      announce('A short pause before the next blend.');
      setTimeout(() => {
        if (token !== state.token || !state.sequence.length) return;
        state.sequenceIndex++; play(state.sequence[state.sequenceIndex], true);
      }, 450);
    } else {
      const wasSequence = state.sequence.length > 0;
      state.sequence = []; state.sequenceVoice = null;
      updateTransport(); announce(wasSequence ? 'Comparison complete. Replay a point to listen again.' : 'Finished. Choose another blend or replay this sound.');
    }
  }
  async function play(clip, preserveSequence = false) {
    if (!preserveSequence) stop('');
    const token = ++state.token;
    audio.pause();
    state.playing = false;
    state.loading = true;
    select(clip);
    const url = path(clip);
    $('playback-progress').value = 0;
    $('transport-time').textContent = '0:00';
    announce(`Opening ${name(clip)}…`);
    updateTransport();
    try {
      const context = audioContext();
      if (context) {
        state.backend = 'web';
        // Call resume in the original click stack, before fetching or awaiting:
        // mobile browsers require this gesture to unlock their audio route.
        const resumed = context.resume();
        const [, buffer] = await Promise.all([resumed, decodedBuffer(context, url)]);
        if (token !== state.token) return;
        if (context.state !== 'running') {
          const error = new Error('Audio context is suspended'); error.name = 'NotAllowedError'; throw error;
        }
        const source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(context.destination);
        state.source = source;
        state.duration = buffer.duration;
        // The running destination emits silence during this short lead-in. It
        // gives a cold output route time to open before the kick's first frame.
        state.startAt = context.currentTime + OUTPUT_WARMUP_SECONDS;
        source.onended = () => {
          if (token !== state.token || state.source !== source) return;
          cancelAnimationFrame(state.frame); state.frame = 0;
          state.source = null;
          source.disconnect();
          $('playback-progress').value = 1;
          $('transport-time').textContent = time(state.duration);
          finished(token);
        };
        source.start(state.startAt);
        announce(`Ready to play ${name(clip)}…`);
        trackProgress(clip, source, token);
      } else {
        state.backend = 'media';
        audio.src = url;
        audio.load();
        await audio.play();
        if (token !== state.token) return;
        started(clip, token);
      }
    } catch (error) {
      if (token !== state.token) return;
      stop('');
      announce(error.name === 'NotAllowedError' ? 'Your browser blocked playback. Choose the sound again to play it.' : 'This sound could not play. Try again or download its WAV.', true);
    }
  }
  function toggle(clip) {
    // A pad chosen during comparison becomes an independent hit, including
    // the pad the sequence is currently playing.
    if (state.sequence.length) play(clip);
    else if (state.selected?.id === clip.id && (state.playing || state.loading)) stop();
    else play(clip);
  }
  function makeCard(clip, index) {
    const beat = clip.kind === 'beat';
    const card = el('article', `clip-card${beat ? ' beat-card' : ''}`);
    card.style.setProperty('--blend', String(clip.blend));
    card.dataset.clipId = clip.id;
    const button = el('button', 'clip-play');
    button.type = 'button';
    const number = el('span', 'clip-number');
    number.append(el('span', '', beat ? `BLEND ${pct(clip)}` : String(index + 1).padStart(2, '0')), el('span', 'play-symbol', '▶'));
    number.lastChild.setAttribute('aria-hidden', 'true');
    button.append(number);
    if (beat) {
      const title = clip.blend === 0 ? 'Electronic' : clip.blend === 1 ? 'Physical' : 'Hybrid';
      button.append(el('span', 'beat-title', title), el('span', 'clip-caption', clip.blend === 0 ? 'Oscillator kick. Cluster hat.' : clip.blend === 1 ? 'Modal kick. Resonant noise hat.' : 'Both partners, in one beat.'));
    } else {
      const value = el('span', 'blend-value', String(Math.round(clip.blend * 100)));
      value.append(el('span', 'blend-unit', '%'));
      button.append(value, el('span', 'clip-caption', clip.blend === 0 ? 'Electronic' : clip.blend === 1 ? 'Physical' : clip.blend === .5 ? 'Hybrid' : clip.blend < .5 ? 'Electronic lean' : 'Physical lean'));
    }
    button.addEventListener('click', () => toggle(clip));
    const verdict = el('select', 'clip-verdict');
    verdict.setAttribute('aria-label', `Listening verdict for ${name(clip)}`);
    for (const [value, label] of [['', 'Your verdict'], ['keep', 'Keep'], ['maybe', 'Maybe'], ['pass', 'Pass']]) {
      const option = el('option', '', label); option.value = value; verdict.append(option);
    }
    verdict.value = noteFor(clip.id).verdict;
    verdict.classList.toggle('rated', Boolean(verdict.value));
    verdict.addEventListener('change', () => {
      state.notes[clip.id] = { ...noteFor(clip.id), verdict: verdict.value, mode: state.mode };
      verdict.classList.toggle('rated', Boolean(verdict.value));
      persistNotes();
      // Changing a verdict selects its note without interrupting another sound.
      if (!state.playing && !state.loading) select(clip);
    });
    card.append(button, verdict);
    cards.set(clip.id, card); playButtons.set(clip.id, button); verdicts.set(clip.id, verdict);
    return card;
  }
  function renderBench() {
    const rows = $('voice-rows'); rows.replaceChildren();
    Object.entries(VOICES).forEach(([voice, info], voiceIndex) => {
      const clips = state.clips.filter((clip) => clip.kind === 'hit' && clip.voice === voice).sort((a, b) => a.blend - b.blend);
      if (!clips.length) return;
      const section = el('section', 'voice-row');
      section.setAttribute('aria-label', `${info.title} blend comparison`);
      const heading = el('div', 'voice-heading');
      const title = el('h3'); title.append(el('span', 'voice-number', String(voiceIndex + 1).padStart(2, '0')), el('span', '', info.title));
      const sequence = el('button', 'sequence-button', '▶ Play all five');
      sequence.type = 'button'; sequence.setAttribute('aria-pressed', 'false');
      sequence.setAttribute('aria-label', `Play all five ${info.title} blends in order`);
      sequence.addEventListener('click', () => {
        if (state.sequenceVoice === voice && state.sequence.length) { stop(); return; }
        stop('');
        state.sequence = clips; state.sequenceIndex = 0; state.sequenceVoice = voice;
        play(clips[0], true);
      });
      sequenceButtons.set(voice, sequence);
      heading.append(title, sequence);
      const axis = el('div', 'blend-axis'); axis.append(el('span', '', info.left), el('span', '', info.right));
      const grid = el('div', 'blend-grid'); clips.forEach((clip, index) => grid.append(makeCard(clip, index)));
      section.append(heading, el('p', 'voice-description', info.description), axis, grid); rows.append(section);
    });
    const beats = $('beat-cards'); beats.replaceChildren();
    state.clips.filter((clip) => clip.kind === 'beat').sort((a, b) => a.blend - b.blend).forEach((clip, index) => beats.append(makeCard(clip, index)));
    const selector = $('note-clip'); selector.replaceChildren();
    state.clips.forEach((clip) => { const option = el('option', '', name(clip)); option.value = clip.id; selector.append(option); });
    const comparison = state.manifest.comparison || {};
    $('beat-context').textContent = `${comparison.bpm || 104} BPM · ${comparison.bars || 2} bars\nThe rhythm stays put.`;
    $('beat-context').style.whiteSpace = 'pre-line';
    $('beat-description').textContent = comparison.fixedBacking ? 'ONSET kick and hat change together; the backing stays fixed.' : 'Kick and closed hat together, without effects. Only BLEND changes.';
    $('copy-notes').disabled = false; $('export-notes').disabled = false;
    renderDetails(); updateReviewCount();
    // A selection prepares the notes and download; it never starts audio.
    select(state.clips.find((clip) => clip.voice === 'KICK' && clip.blend === .5) || state.clips[0]);
  }
  function renderDetails() {
    const details = $('render-details'); details.replaceChildren();
    details.append(el('p', '', 'The same voice settings and deterministic excitation are used across each five-point row; BLEND is the variable. A shared attack and linked pitch/decay help the partners speak as one sound.'));
    details.append(el('p', '', 'Matched levels are for comparing character. Raw levels retain the renderer’s output balance. Changing the level view stops any sequence and replays the selected sound only if it was playing.'));
    details.append(el('p', '', 'This first study covers kick and closed hat. It does not demonstrate live morphing, per-step synthesis automation, or cross-voice coupling. The manifest records the rendered settings and measurements.'));
    if (state.manifest.engine?.controls?.length) details.append(el('p', '', `Engine controls: ${state.manifest.engine.controls.join(' · ')}.`));
  }
  function notesText() {
    const lines = ['ONSET — first listening notes', 'Listening question: Does the middle deserve a place in the kit?', ''];
    state.clips.forEach((clip) => {
      const note = noteFor(clip.id);
      if (!note.verdict && !note.text.trim()) return;
      lines.push(`${name(clip)} [${clip.id}]`, `Verdict: ${note.verdict || 'unrated'} · ${note.mode === 'raw' ? 'raw' : 'matched'} levels`);
      if (note.text.trim()) lines.push(note.text.trim());
      lines.push('');
    });
    if (lines.length === 3) lines.push('No listening notes yet.');
    return lines.join('\n');
  }
  function exportNotes() {
    const url = URL.createObjectURL(new Blob([notesText()], { type: 'text/plain;charset=utf-8' }));
    const link = el('a'); link.href = url; link.download = 'onset-listening-notes.txt';
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function loadManifest() {
    const inline = $('onset-manifest');
    if (inline) return JSON.parse(inline.textContent);
    const response = await fetch('manifest.json');
    if (!response.ok) throw new Error('Manifest unavailable');
    return response.json();
  }
  function validateManifest(manifest) {
    if (!Array.isArray(manifest?.clips) || manifest.clips.length !== 13) throw new Error('Expected the complete 13-clip first listening pack');
    const ids = new Set();
    manifest.clips.forEach((clip) => {
      if (!clip.id || ids.has(clip.id) || !['hit', 'beat'].includes(clip.kind) || !Number.isFinite(clip.blend) || clip.blend < 0 || clip.blend > 1 || typeof clip.raw !== 'string' || typeof clip.matched !== 'string' || !clip.raw || !clip.matched) throw new Error('Invalid render in manifest');
      if (![clip.raw, clip.matched].every((url) => /^audio\/[a-zA-Z0-9._-]+\.wav$/.test(url))) throw new Error('Unexpected audio path');
      ids.add(clip.id);
    });
    for (const voice of Object.keys(VOICES)) {
      const blends = manifest.clips.filter((clip) => clip.kind === 'hit' && clip.voice === voice).map((clip) => clip.blend).sort((a, b) => a - b);
      if (blends.join(',') !== '0,0.25,0.5,0.75,1') throw new Error('Incomplete voice comparison');
    }
    const beats = manifest.clips.filter((clip) => clip.kind === 'beat').map((clip) => clip.blend).sort((a, b) => a - b);
    if (beats.join(',') !== '0,0.5,1') throw new Error('Incomplete beat comparison');
    return manifest;
  }

  document.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => {
    if (button.dataset.mode === state.mode) return;
    const resume = state.playing || state.loading;
    stop('Level view changed. Choose a sound to listen.');
    state.mode = button.dataset.mode;
    document.querySelectorAll('[data-mode]').forEach((item) => { item.classList.toggle('active', item.dataset.mode === state.mode); item.setAttribute('aria-pressed', String(item.dataset.mode === state.mode)); });
    $('mode-help').textContent = state.mode === 'matched' ? 'Levels matched to compare character.' : 'The renderer’s original output balance.';
    updateNoteEditor(); updateTransport();
    if (resume && state.selected) play(state.selected);
  }));
  $('stop-all').addEventListener('click', () => stop());
  $('replay').addEventListener('click', () => { if (state.selected) toggle(state.selected); });
  $('note-clip').addEventListener('change', () => {
    const clip = state.clips.find((item) => item.id === $('note-clip').value);
    if (!clip) return;
    stop('Selected sound changed. Press play to listen.'); select(clip);
  });
  $('note-text').addEventListener('input', () => {
    if (!state.selected) return;
    state.notes[state.selected.id] = { ...noteFor(state.selected.id), text: $('note-text').value, mode: state.mode };
    persistNotes();
  });
  $('export-notes').addEventListener('click', exportNotes);
  $('copy-notes').addEventListener('click', async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(notesText());
      $('note-status').textContent = 'Listening notes copied.';
    } catch (_) { exportNotes(); $('note-status').textContent = 'Clipboard unavailable. Notes downloaded instead.'; }
  });
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape') { event.preventDefault(); stop(); } });
  document.addEventListener('visibilitychange', () => { if (document.hidden && (state.playing || state.loading || state.sequence.length)) stop('Playback stopped while this page is in the background.'); });
  audio.addEventListener('timeupdate', () => {
    if (state.backend !== 'media') return;
    $('playback-progress').value = audio.duration > 0 ? audio.currentTime / audio.duration : 0;
    $('transport-time').textContent = time(audio.currentTime);
  });
  audio.addEventListener('ended', () => {
    if (state.backend !== 'media' || !audio.ended) return;
    $('playback-progress').value = 1;
    finished(state.token);
  });
  audio.addEventListener('error', () => {
    if (state.backend !== 'media' || (!state.loading && !state.playing)) return;
    stop(''); announce('This sound could not load. Try again or download its WAV.', true);
  });
  loadNotes();
  loadManifest().then(validateManifest).then((manifest) => {
    state.manifest = manifest; state.clips = manifest.clips;
    renderBench();
    if (!state.storageAvailable) $('note-status').textContent = 'Browser storage unavailable. Export to keep your notes.';
  }).catch(() => {
    $('voice-rows').replaceChildren();
    $('loading-error').hidden = false;
    $('loading-error').textContent = 'The listening pack could not open. Reload this page, or download the WAV pack to listen directly.';
    announce('The listening pack is unavailable.', true);
  });
})();
