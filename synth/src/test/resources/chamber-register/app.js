'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const make = (tag, className, value) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value != null) element.textContent = String(value);
    return element;
  };
  const audio = $('audio');
  const noteNames = { 60: 'C4', 72: 'C5', 84: 'C6' };
  const variantNames = { before: 'Previous wider', after: 'Register candidate' };
  let manifest, clipById, cards = [], voice = 'HYBRID', midi = 84, mode = 'matched';
  let current = null, requestToken = 0, positionListener = null, storageKey = '';
  let saved = { notes: {}, preferences: {} }, storageAvailable = true;
  const buttons = new Map();

  function asset(path) {
    if (typeof path !== 'string' || !path.trim()) throw Error('Missing recording or download address.');
    const url = new URL(path, location.href);
    if (url.origin !== location.origin || !['http:', 'https:'].includes(url.protocol)) throw Error('Unexpected recording or download address.');
    return url.href;
  }
  const cardKey = card => `${card.voice}:${card.midi}:${card.variant}`;
  const pathFor = clip => asset(mode === 'raw' ? clip.raw : clip.matched);
  const pairFor = () => ['before', 'after'].map(variant => cards.find(card => card.voice === voice && card.midi === midi && card.variant === variant));
  const rawAvailable = pair => pair.every(card => Boolean(clipById.get(card.clipId).raw));

  function fail(message) {
    $('error').textContent = message;
    $('error').hidden = false;
    $('audio-status').textContent = message;
  }
  function noteCount() {
    const notes = Object.keys(saved.notes).length, choices = Object.keys(saved.preferences).length;
    $('note-count').textContent = notes || choices
      ? `${notes} clip note${notes === 1 ? '' : 's'} · ${choices} register choice${choices === 1 ? '' : 's'}${storageAvailable ? ' saved in this browser.' : ' in this session.'}`
      : 'No notes saved yet.';
  }
  function save() {
    try { localStorage.setItem(storageKey, JSON.stringify(saved)); }
    catch { storageAvailable = false; $('storage-notice').hidden = false; }
    noteCount();
  }
  function sync() {
    buttons.forEach((button, key) => {
      const active = current?.key === key && !audio.paused;
      button.textContent = active ? 'Ⅱ Pause' : '▶ Listen';
      button.setAttribute('aria-pressed', String(active));
      button.closest('.clip-card')?.classList.toggle('is-playing', active);
    });
  }
  function cancelPosition() {
    if (positionListener) audio.removeEventListener('loadedmetadata', positionListener);
    positionListener = null;
  }
  function stop() {
    ++requestToken;
    cancelPosition();
    audio.pause();
    if (audio.readyState) audio.currentTime = 0;
    sync();
    $('audio-status').textContent = 'Playback stopped.';
  }
  function load(clip, at, token) {
    cancelPosition();
    positionListener = () => {
      positionListener = null;
      if (token === requestToken && Number.isFinite(audio.duration)) {
        audio.currentTime = Math.min(at, Math.max(0, audio.duration - .01));
      }
    };
    audio.addEventListener('loadedmetadata', positionListener, { once: true });
    audio.src = pathFor(clip);
    audio.load();
  }
  function trackTitle(card) {
    return `${card.voice} · ${noteNames[card.midi]} · ${variantNames[card.variant]} · ${mode}`;
  }
  async function play(card) {
    const key = cardKey(card), clip = clipById.get(card.clipId);
    if (current?.key === key && !audio.paused) {
      ++requestToken;
      audio.pause();
      $('audio-status').textContent = 'Playback paused.';
      return;
    }
    const token = ++requestToken;
    try {
      if (current?.key !== key || audio.src !== pathFor(clip)) {
        audio.pause();
        load(clip, 0, token);
      }
      current = { key, card, clip };
      $('transport').hidden = false;
      $('track-title').textContent = trackTitle(card);
      await audio.play();
      if (token === requestToken) {
        sync();
        $('audio-status').textContent = `Playing ${trackTitle(card)}.`;
      }
    } catch (error) {
      if (token === requestToken && error.name !== 'AbortError') fail(error.message || 'Playback failed. Try the audio download.');
    }
  }
  function download(path, text) {
    const link = make('a', '', text);
    link.href = asset(path);
    link.download = '';
    return link;
  }
  function renderCard(card) {
    const clip = clipById.get(card.clipId), key = cardKey(card);
    const element = make('article', `clip-card ${card.variant}`);
    element.dataset.clipId = clip.id;
    element.dataset.variant = card.variant;
    element.dataset.midi = card.midi;
    const meta = make('div', 'clip-meta');
    meta.append(make('span', '', card.voice), make('span', '', `${noteNames[card.midi]} · VELOCITY 75%`));
    const copy = midi === 60 ? 'The preserved C4 reference. Both revisions use this exact recording.'
      : card.variant === 'before' ? 'The wider version from the previous audition, at this same note.'
        : 'Contact and upper boundary response follow the note. Compare the body and decay.';
    element.append(meta, make('h4', '', variantNames[card.variant]), make('p', '', copy));
    const actions = make('div', 'clip-actions'), button = make('button', 'play', '▶ Listen');
    button.type = 'button';
    button.dataset.cardKey = key;
    button.setAttribute('aria-label', `Listen to ${card.voice} ${noteNames[card.midi]} ${variantNames[card.variant]}`);
    button.setAttribute('aria-pressed', 'false');
    button.addEventListener('click', () => play(card));
    buttons.set(key, button);
    actions.append(button, make('span', 'duration', `${Number(clip.metrics?.durationSeconds || 4.5).toFixed(1)} s`));
    element.append(actions);
    const downloads = make('div', 'downloads'), sound = mode === 'raw' ? clip.raw : clip.matched;
    downloads.append(download(sound, /\.flac$/i.test(sound) ? 'Audio FLAC ↓' : 'Audio WAV ↓'));
    if (clip.recipe) downloads.append(download(clip.recipe, 'Recipe ↓'));
    element.append(downloads);
    const details = make('details'), summary = make('summary', '', saved.notes[clip.id] ? 'Listening note · saved' : 'Add a listening note');
    details.append(summary);
    const label = make('label', '', 'What do you hear after the attack?'), input = make('textarea');
    input.id = `note-${card.voice}-${card.midi}-${card.variant}`;
    input.dataset.noteClipId = clip.id;
    input.maxLength = 10000;
    input.rows = 3;
    input.placeholder = 'Pitched body, room answer, tail…';
    input.value = saved.notes[clip.id]?.text || '';
    label.htmlFor = input.id;
    input.addEventListener('input', () => {
      if (input.value.trim()) saved.notes[clip.id] = { title: clip.title, text: input.value, voice: card.voice,
        midi: card.midi, clipId: clip.id, model: clip.model, sourceRevision: clip.sourceRevision || manifest.sourceRevision,
        updatedAt: new Date().toISOString() };
      else delete saved.notes[clip.id];
      // The two C4 cards deliberately share one immutable recording and note.
      document.querySelectorAll('[data-note-clip-id]').forEach(peer => {
        if (peer.dataset.noteClipId === clip.id) {
          if (peer !== input) peer.value = input.value;
          peer.closest('details').querySelector('summary').textContent = input.value.trim() ? 'Listening note · saved' : 'Add a listening note';
        }
      });
      save();
    });
    details.append(label, input);
    element.append(details);
    return element;
  }
  function render() {
    if (!manifest) return;
    const pair = pairFor(), hasRaw = rawAvailable(pair);
    if (!hasRaw) mode = 'matched';
    buttons.clear();
    document.querySelectorAll('[data-voice]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.voice === voice)));
    document.querySelectorAll('[data-midi]').forEach(button => button.setAttribute('aria-pressed', String(Number(button.dataset.midi) === midi)));
    document.querySelectorAll('[data-mode]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
      if (button.dataset.mode === 'raw') button.disabled = !hasRaw;
    });
    $('raw-notice').hidden = hasRaw;
    $('register-title').textContent = `${noteNames[midi]} · ${midi === 60 ? 'Keep the reference.' : midi === 72 ? 'One octave higher.' : 'Two octaves higher.'}`;
    $('register-story').textContent = midi === 60
      ? 'The C4 recording stays identical. Use it to keep your bearings before moving up.'
      : 'Listen through the decay. Does the higher note keep a resonant body and an answering room?';
    $('anchor-notice').hidden = midi !== 60;
    $('comparison').replaceChildren(...pair.map(renderCard));
    const preference = $('preference'), key = `${voice}:${midi}`;
    preference.setAttribute('aria-label', `${voice} ${noteNames[midi]} listening preference`);
    preference.replaceChildren(make('span', '', 'Which would you keep?'));
    ['Previous wider', 'Register candidate', 'Neither yet'].forEach(choice => {
      const button = make('button', '', choice);
      button.type = 'button';
      button.setAttribute('aria-pressed', String(saved.preferences[key]?.choice === choice));
      button.addEventListener('click', () => {
        saved.preferences[key] = { voice, midi, noteLabel: noteNames[midi], choice, updatedAt: new Date().toISOString() };
        save();
        preference.querySelectorAll('button').forEach(peer => peer.setAttribute('aria-pressed', String(peer === button)));
      });
      preference.append(button);
    });
    sync();
  }
  async function setMode(next) {
    if (!manifest || mode === next || (next === 'raw' && !rawAvailable(pairFor()))) return;
    const resume = !audio.paused, at = audio.currentTime;
    mode = next;
    render();
    if (!current) return;
    const token = ++requestToken;
    audio.pause();
    load(current.clip, at, token);
    $('track-title').textContent = trackTitle(current.card);
    if (resume) {
      try { await audio.play(); }
      catch (error) { if (token === requestToken && error.name !== 'AbortError') fail(error.message || 'Playback failed.'); }
    }
  }
  function changeFilter(update) {
    stop();
    current = null;
    $('transport').hidden = true;
    update();
    render();
  }
  document.querySelectorAll('[data-voice]').forEach(button => button.addEventListener('click', () => changeFilter(() => { voice = button.dataset.voice; })));
  document.querySelectorAll('[data-midi]').forEach(button => button.addEventListener('click', () => changeFilter(() => { midi = Number(button.dataset.midi); })));
  document.querySelectorAll('[data-mode]').forEach(button => button.addEventListener('click', () => setMode(button.dataset.mode)));
  ['play', 'pause', 'ended'].forEach(event => audio.addEventListener(event, sync));
  audio.addEventListener('error', () => { if (current && audio.error) fail('This recording could not be opened. Try its audio download.'); });
  $('stop').addEventListener('click', stop);
  $('repeat').addEventListener('click', () => { audio.loop = !audio.loop; $('repeat').setAttribute('aria-pressed', String(audio.loop)); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') stop(); });
  $('export-notes').addEventListener('click', () => {
    const value = { audition: manifest?.title, sourceRevision: manifest?.sourceRevision, exportedAt: new Date().toISOString(), listeningMode: mode, ...saved };
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' }));
    const link = make('a');
    link.href = url;
    link.download = 'chamber-register-notes.json';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  fetch('manifest.json').then(response => {
    if (!response.ok) throw Error('The register manifest could not be opened.');
    return response.json();
  }).then(data => {
    if (typeof data.sourceRevision !== 'string' || !data.sourceRevision
        || !Array.isArray(data.clips) || data.clips.length !== 10
        || !Array.isArray(data.cards) || data.cards.length !== 12) throw Error('The register study is incomplete.');
    const byId = new Map(data.clips.map(clip => [clip.id, clip]));
    if (byId.size !== data.clips.length) throw Error('The register study contains duplicate recordings.');
    const keys = new Set(), usedClips = new Set();
    for (const card of data.cards) {
      const clip = byId.get(card.clipId), key = cardKey(card);
      if (!['HYBRID', 'AQUARIUM'].includes(card.voice) || !noteNames[card.midi] || !variantNames[card.variant]
          || keys.has(key) || !clip || !clip.matched || clip.voice !== card.voice || Number(clip.midi) !== card.midi) {
        throw Error('A register comparison is missing or inconsistent.');
      }
      asset(clip.matched);
      if (clip.raw) asset(clip.raw);
      if (clip.recipe) asset(clip.recipe);
      keys.add(key);
      usedClips.add(clip.id);
    }
    if (usedClips.size !== 10 || ['HYBRID', 'AQUARIUM'].some(room => {
      const anchors = data.cards.filter(card => card.voice === room && card.midi === 60);
      return anchors.length !== 2 || anchors[0].clipId !== anchors[1].clipId;
    })) throw Error('The unchanged C4 references are missing or inconsistent.');
    manifest = data;
    clipById = byId;
    cards = data.cards;
    storageKey = `snipsnap-chamber-register-notes-v1-${data.sourceRevision}`;
    try {
      const previous = JSON.parse(localStorage.getItem(storageKey) || '{}');
      if (previous.notes && typeof previous.notes === 'object' && !Array.isArray(previous.notes)) saved.notes = previous.notes;
      if (previous.preferences && typeof previous.preferences === 'object' && !Array.isArray(previous.preferences)) saved.preferences = previous.preferences;
      localStorage.setItem(storageKey, JSON.stringify(saved));
    } catch { storageAvailable = false; $('storage-notice').hidden = false; }
    noteCount();
    render();
    $('checks').textContent = `${data.clips.length} recordings form 12 comparison cards across two rooms and three notes. The C4 pair shares exact audio. Render checks and register measurements are recorded below.`;
  }).catch(error => fail(error.message || 'The register study could not be opened.'));
})();
