'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const make = (tag, className, value) => { const el = document.createElement(tag); if (className) el.className = className; if (value != null) el.textContent = String(value); return el; };
  const stories = {
    STRIKE: ['Felt to point.', 'Compare the rounded contact with the harder hit. Listen for a change in the metal sources as well as the membrane.'],
    BALANCE: ['Body to air.', 'A stronger shift from membrane-led weight toward the higher metal sources. Listen for whether the quartet keeps enough body.'],
    MATERIAL: ['Let the surfaces speak.', 'Compare the colour and length of the room response. Listen beyond the attack, where the boundary materials have more space to answer.'],
    DIVIDE: ['One room. Separate answers.', 'Hear a more blended room at one end and more distinct boundary responses at the other. Does the difference become musically useful?'],
    PLACEMENT: ['Near to far.', 'Follow the direct strike and the reflections as the sources move closer to the listener or farther into the room.']
  };
  const audio = $('audio');
  let manifest, clips = [], voice = 'HYBRID', control = 'MATERIAL', mode = 'matched', current = null, token = 0;
  let storageKey = '', saved = { notes: {}, preferences: {} }, storageAvailable = true;
  const playingButtons = new Map();
  const asset = path => { const url = new URL(path, location.href); if (url.origin !== location.origin || !['http:', 'https:'].includes(url.protocol)) throw Error('Unexpected audio or download address.'); return url.href; };
  const pathFor = clip => asset(clip[mode] || clip.matched || clip.raw);
  function fail(message) { $('error').textContent = message; $('error').hidden = false; $('audio-status').textContent = message; }
  function store() {
    try { localStorage.setItem(storageKey, JSON.stringify(saved)); } catch { storageAvailable = false; $('storage-notice').hidden = false; }
    countNotes();
  }
  function countNotes() { const n = Object.keys(saved.notes).length, p = Object.keys(saved.preferences).length; $('note-count').textContent = n || p ? `${n} clip note${n === 1 ? '' : 's'} · ${p} range choice${p === 1 ? '' : 's'}${storageAvailable ? ' saved in this browser.' : ' in this session.'}` : 'No notes saved yet.'; }
  function sync() {
    playingButtons.forEach((button, id) => { const active = current?.id === id && !audio.paused; button.textContent = active ? 'Ⅱ Pause' : '▶ Listen'; button.setAttribute('aria-pressed', String(active)); button.closest('.clip-card')?.classList.toggle('is-playing', active); });
  }
  function stop() { ++token; audio.pause(); if (audio.readyState) audio.currentTime = 0; sync(); $('audio-status').textContent = 'Playback stopped.'; }
  async function play(clip) {
    if (current?.id === clip.id && !audio.paused) { audio.pause(); return; }
    const request = ++token;
    try {
      if (current?.id !== clip.id || audio.src !== pathFor(clip)) { audio.pause(); current = clip; audio.src = pathFor(clip); audio.load(); }
      $('transport').hidden = false; $('track-title').textContent = `${clip.title} · ${mode}`;
      await audio.play();
      if (request === token) { sync(); $('audio-status').textContent = `Playing ${clip.title} at ${mode} level.`; }
    } catch (error) { if (request === token && error.name !== 'AbortError') fail(error.message || 'Playback failed. Use the audio download to listen.'); }
  }
  function listenButton(clip) { const button = make('button', 'play', '▶ Listen'); button.type = 'button'; button.dataset.clipId = clip.id; button.setAttribute('aria-label', `Listen to ${clip.title}`); button.setAttribute('aria-pressed', 'false'); button.addEventListener('click', () => play(clip)); playingButtons.set(clip.id, button); return button; }
  function download(path, label) { const a = make('a', '', label); a.href = asset(path); a.download = ''; return a; }
  function card(clip) {
    const el = make('article', `clip-card ${clip.variant}`); el.dataset.clipId = clip.id;
    const meta = make('div', 'clip-meta'); meta.append(make('span', '', voice), make('span', '', 'C4 · VELOCITY 75%'));
    const wider = clip.variant === 'wider';
    el.append(meta, make('h4', '', wider ? 'Wider' : 'Current'), make('p', '', wider ? 'Expanded physical range. Compare the attack, source blend and room decay.' : 'The current accepted control range, at the same endpoint.'));
    const actions = make('div', 'clip-actions'); actions.append(listenButton(clip), make('span', 'duration', `${Number(clip.metrics?.durationSeconds || 4.5).toFixed(1)} s`)); el.append(actions);
    const downloads = make('div', 'downloads'); const sound = clip[mode] || clip.matched || clip.raw; downloads.append(download(sound, /\.flac$/i.test(sound) ? 'Audio FLAC ↓' : 'Audio WAV ↓')); if (clip.recipe) downloads.append(download(clip.recipe, 'Recipe ↓')); el.append(downloads);
    const details = make('details'); details.append(make('summary', '', saved.notes[clip.id] ? 'Listening note · saved' : 'Add a listening note'));
    const label = make('label', '', 'What changed?'); label.htmlFor = `note-${clip.id}`;
    const input = make('textarea'); input.id = label.htmlFor; input.maxLength = 10000; input.rows = 3; input.placeholder = 'Attack, body, room tail…'; input.value = saved.notes[clip.id]?.text || '';
    input.addEventListener('input', () => { if (input.value.trim()) saved.notes[clip.id] = { title: clip.title, text: input.value, model: clip.model, sourceRevision: clip.sourceRevision || manifest.sourceRevision, updatedAt: new Date().toISOString() }; else delete saved.notes[clip.id]; details.querySelector('summary').textContent = input.value.trim() ? 'Listening note · saved' : 'Add a listening note'; store(); });
    details.append(label, input); el.append(details); return el;
  }
  function render() {
    if (!manifest) return;
    playingButtons.clear();
    document.querySelectorAll('[data-voice]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.voice === voice)));
    document.querySelectorAll('[data-control]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.control === control)));
    document.querySelectorAll('[data-mode]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.mode === mode)));
    $('control-title').textContent = stories[control][0]; $('control-story').textContent = stories[control][1];
    const anchor = clips.find(c => c.voice === voice && !c.control);
    if (!anchor) throw Error(`Missing ${voice} accepted default.`);
    const anchorCopy = make('p'); anchorCopy.append(make('strong', '', `${voice} default`), document.createTextNode(' · Identical in the current and wider ranges. Start here to keep your bearings.'));
    $('anchor').replaceChildren(listenButton(anchor), anchorCopy);
    const sections = [0, 1].map(endpoint => {
      const section = make('section', 'endpoint'); section.dataset.endpoint = endpoint;
      const heading = make('div', 'endpoint-header'); const title = make('h3', '', endpoint === 0 ? 'The low end' : 'The high end'); title.id = `endpoint-${endpoint}`; section.setAttribute('aria-labelledby', title.id); heading.append(title, make('span', '', `${control} ${endpoint === 0 ? '0%' : '100%'}`));
      const pair = ['accepted', 'wider'].map(variant => { const found = clips.find(c => c.voice === voice && c.control === control && Number(c.endpoint) === endpoint && c.variant === variant); if (!found) throw Error(`Missing ${voice} ${control} ${endpoint} ${variant}.`); return found; });
      const cards = make('div', 'cards'); pair.forEach(c => cards.append(card(c)));
      const preference = make('div', 'preference'); preference.setAttribute('role', 'group'); preference.setAttribute('aria-label', `${voice} ${control} ${endpoint === 0 ? 'low' : 'high'} range preference`); preference.append(make('span', '', 'Which would you keep?'));
      const key = `${voice}:${control}:${endpoint}`;
      ['Current', 'Wider', 'Neither yet'].forEach(choice => { const button = make('button', '', choice); button.type = 'button'; button.setAttribute('aria-pressed', String(saved.preferences[key]?.choice === choice)); button.addEventListener('click', () => { saved.preferences[key] = { voice, control, endpoint, choice, updatedAt: new Date().toISOString() }; store(); preference.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b === button))); }); preference.append(button); });
      section.append(heading, cards, preference); return section;
    });
    $('endpoints').replaceChildren(...sections); sync();
  }
  async function setMode(next) {
    if (mode === next) return;
    const resume = !audio.paused, at = audio.currentTime;
    mode = next; render();
    if (!current) return;
    const request = ++token;
    audio.pause(); audio.src = pathFor(current); audio.load(); $('track-title').textContent = `${current.title} · ${mode}`;
    const position = () => { if (request === token && Number.isFinite(audio.duration)) audio.currentTime = Math.min(at, Math.max(0, audio.duration - .01)); };
    audio.addEventListener('loadedmetadata', position, { once: true });
    if (resume) { try { await audio.play(); } catch (error) { if (request === token && error.name !== 'AbortError') fail(error.message); } }
  }
  document.querySelectorAll('[data-voice]').forEach(b => b.addEventListener('click', () => { stop(); voice = b.dataset.voice; render(); }));
  document.querySelectorAll('[data-control]').forEach(b => b.addEventListener('click', () => { stop(); control = b.dataset.control; render(); }));
  document.querySelectorAll('[data-mode]').forEach(b => b.addEventListener('click', () => setMode(b.dataset.mode)));
  ['play', 'pause', 'ended'].forEach(event => audio.addEventListener(event, sync));
  audio.addEventListener('error', () => { if (audio.currentSrc) fail('This audio could not be opened. Try its download link.'); });
  $('stop').addEventListener('click', stop);
  $('repeat').addEventListener('click', () => { audio.loop = !audio.loop; $('repeat').setAttribute('aria-pressed', String(audio.loop)); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') stop(); });
  $('export-notes').addEventListener('click', () => { const value = { audition: manifest?.title, sourceRevision: manifest?.sourceRevision, exportedAt: new Date().toISOString(), listeningMode: mode, ...saved }; const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' })); const a = make('a'); a.href = url; a.download = 'chamber-extremes-notes.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
  fetch('manifest.json').then(response => { if (!response.ok) throw Error('The listening manifest could not be opened.'); return response.json(); }).then(data => {
    if (!Array.isArray(data.clips) || data.clips.length !== 42) throw Error('The endpoint study is incomplete.');
    manifest = data; clips = data.clips; storageKey = `snipsnap-chamber-extremes-notes-v1-${data.sourceRevision}`;
    try { const old = JSON.parse(localStorage.getItem(storageKey) || '{}'); if (old.notes && typeof old.notes === 'object') saved.notes = old.notes; if (old.preferences && typeof old.preferences === 'object') saved.preferences = old.preferences; localStorage.setItem(storageKey, JSON.stringify(saved)); } catch { storageAvailable = false; $('storage-notice').hidden = false; }
    countNotes(); render(); $('checks').textContent = '42 comparison recordings: 22 preserved current references and 20 wider endpoints. Default identity, finite output and complete-network energy checks are recorded in the report.';
  }).catch(error => fail(error.message || 'The listening study could not be opened.'));
})();
