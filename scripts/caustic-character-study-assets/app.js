(() => {
  'use strict';
  const manifest = window.CAUSTIC_CHARACTER_MANIFEST || window.CAUSTIC_STUDY_MANIFEST || window.CAUSTIC_MANIFEST;
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const families = [
    { id: 'fuller-body', name: 'Fuller Body', gesture: 'A bright strike opens into a rounded metal body.', description: 'The unchanged native sound. Use it as the reference for the other three directions.' },
    { id: 'hollow-mallet', name: 'Hollow Mallet', gesture: 'A firm knock, a hollow center and a resonant release.', description: 'Listen for the space inside the attack and the weight left behind it.' },
    { id: 'rubbed-ring', name: 'Rubbed Ring', gesture: 'A softer entrance gathers friction into a ringing body.', description: 'Listen for how the note arrives and how its brightness develops.' },
    { id: 'travelling-catches', name: 'Travelling Catches', gesture: 'Small catches move through the note, shifting its weight and brightness.', description: 'Listen for movement within the body, beyond the first strike.' },
  ];
  const state = { midi: 60, family: 'fuller-body', mode: 'matched', current: null, request: 0 };
  const cards = new Map();
  const text = (tag, className, value) => { const node = document.createElement(tag); if (className) node.className = className; node.textContent = value; return node; };
  const note = midi => ({36: 'C2', 60: 'C4', 84: 'C6'}[midi] || `MIDI ${midi}`);
  const clipFor = family => manifest?.clips?.find(clip => clip.family === family && Number(clip.midi) === state.midi);
  const familyFor = id => families.find(family => family.id === id);
  const address = path => new URL(path, document.baseURI).href;
  const setLink = (link, path, label) => { link.hidden = !path; if (path) link.href = address(path); if (label) link.textContent = label; };
  const recordingPath = clip => state.mode === 'matched' ? clip.matched : clip.raw;

  function showError(message) {
    $('playback-error').textContent = message;
    $('playback-error').hidden = !message;
  }

  function reflectPlayback() {
    const playing = !audio.paused && !audio.ended;
    $('player-status').textContent = playing ? 'NOW PLAYING' : audio.ended ? 'RECORDING FINISHED' : 'READY TO PLAY';
    for (const [id, card] of cards) {
      const active = state.current?.family === id;
      card.root.classList.toggle('is-current', active);
      card.button.classList.toggle('is-playing', active && playing);
      card.button.setAttribute('aria-pressed', String(active && playing));
      card.button.title = active && playing ? `Pause ${card.family.name}` : `Play ${card.family.name}`;
    }
  }

  function updateCards() {
    for (const family of families) {
      const card = cards.get(family.id);
      const clip = clipFor(family.id);
      card.button.disabled = !clip;
      card.gesture.textContent = typeof clip?.gesture === 'string' && clip.gesture ? clip.gesture : family.gesture;
      card.description.textContent = clip?.description || family.description;
      card.meta.textContent = clip ? `${note(state.midi)} · ${Number(clip.seconds).toFixed(1)} sec` : `${note(state.midi)} · Recording unavailable`;
      setLink(card.recipe, clip?.recipe, 'Study recipe JSON');
    }
    $('reference-play').disabled = !clipFor('fuller-body');
    for (const button of document.querySelectorAll('[data-midi]')) button.setAttribute('aria-pressed', String(Number(button.dataset.midi) === state.midi));
    reflectPlayback();
  }

  function selectClip(clip, play = false, position = 0) {
    if (!clip) return;
    const path = recordingPath(clip);
    if (!path) { showError('This recording is missing from the study pack.'); return; }
    const request = ++state.request;
    state.current = clip;
    state.family = clip.family;
    const family = familyFor(clip.family);
    $('player-name').textContent = `${family?.name || clip.title} · ${note(Number(clip.midi))}`;
    $('player-detail').textContent = `${clip.family === 'fuller-body' ? 'Native reference' : 'Listening prototype'} · ${Number(clip.seconds).toFixed(1)} seconds · ${state.mode === 'matched' ? 'Level matched' : 'Renderer level'}`;
    audio.setAttribute('aria-label', `${family?.name || clip.title} ${note(Number(clip.midi))} recording`);
    setLink($('current-recipe'), clip.recipe, 'Study recipe JSON');
    setLink($('current-wav'), path, 'WAV');
    showError('');
    audio.pause();
    audio.src = address(path);
    audio.load();
    if (position > 0) audio.addEventListener('loadedmetadata', () => {
      if (request === state.request && Number.isFinite(audio.duration)) audio.currentTime = Math.min(position, Math.max(0, audio.duration - .01));
    }, { once: true });
    reflectPlayback();
    if (play) {
      const promise = audio.play();
      if (promise?.catch) promise.catch(error => {
        if (request === state.request && error.name !== 'AbortError') showError('Playback could not start. Use the player’s Play control to try again.');
      });
    }
  }

  function playFamily(family) {
    const clip = clipFor(family);
    if (!clip) return;
    if (state.current?.id === clip.id && !audio.paused && !audio.ended) { audio.pause(); return; }
    selectClip(clip, true);
  }

  function setMode(mode) {
    if (state.mode === mode) return;
    const playing = !audio.paused && !audio.ended;
    const position = audio.currentTime || 0;
    state.mode = mode;
    $('matched-mode').setAttribute('aria-pressed', String(mode === 'matched'));
    $('raw-mode').setAttribute('aria-pressed', String(mode === 'raw'));
    $('level-note').textContent = mode === 'matched'
      ? 'Level matched reduces loudness differences so you can compare character.'
      : 'Renderer level plays the exported renderer output, without the extra comparison leveling.';
    selectClip(state.current, playing, position);
  }

  function createCards() {
    const grid = $('sound-cards');
    grid.replaceChildren();
    families.forEach((family, index) => {
      const root = text('article', 'sound-card');
      root.dataset.family = family.id;
      const marker = text('div', 'card-marker');
      marker.append(text('span', 'card-index', `0${index + 1}`), text('span', '', index === 0 ? 'NATIVE REFERENCE' : 'LISTENING PROTOTYPE'));
      const heading = text('h3', '', family.name);
      const gesture = text('p', 'gesture');
      const description = text('p', 'card-description');
      const button = text('button', 'card-play');
      button.type = 'button';
      button.append(text('span', '', `Play ${family.name}`));
      const icon = text('span', 'play-icon'); icon.setAttribute('aria-hidden', 'true'); button.append(icon);
      button.addEventListener('click', () => playFamily(family.id));
      const bottom = text('div', 'card-bottom');
      const meta = text('span');
      const recipe = text('a'); recipe.download = ''; bottom.append(meta, recipe);
      root.append(marker, heading, gesture, description, button, bottom);
      cards.set(family.id, { family, root, gesture, description, button, meta, recipe });
      grid.append(root);
    });
  }

  if (!manifest?.clips?.length) {
    $('sound-cards').replaceChildren(text('p', 'loading', 'The recording manifest is missing. Keep manifest.js beside this page and the audio files.'));
    $('player-detail').textContent = 'No recordings are available.';
    $('reference-play').disabled = true;
    for (const button of document.querySelectorAll('.level-switch button, [data-midi]')) button.disabled = true;
    return;
  }

  audio.volume = .7;
  createCards();
  updateCards();
  selectClip(clipFor('fuller-body'));
  $('matched-mode').addEventListener('click', () => setMode('matched'));
  $('raw-mode').addEventListener('click', () => setMode('raw'));
  $('reference-play').addEventListener('click', () => playFamily('fuller-body'));
  for (const button of document.querySelectorAll('[data-midi]')) button.addEventListener('click', () => {
    const midi = Number(button.dataset.midi);
    if (midi === state.midi) return;
    const playing = !audio.paused && !audio.ended;
    state.midi = midi;
    updateCards();
    selectClip(clipFor(state.family), playing);
  });
  $('volume').addEventListener('input', event => {
    audio.volume = Number(event.target.value);
    audio.muted = false;
  });
  audio.addEventListener('volumechange', () => {
    $('volume').value = audio.muted ? 0 : audio.volume;
    $('volume-value').textContent = `${Math.round((audio.muted ? 0 : audio.volume) * 100)}%`;
  });
  for (const event of ['play', 'pause', 'ended']) audio.addEventListener(event, reflectPlayback);
  audio.addEventListener('error', () => {
    if (audio.error) showError('This WAV could not be opened. Keep the audio folder beside the page, or download the study pack again.');
  });
  const downloads = manifest.downloads || manifest.summary?.downloads || {};
  setLink($('zip-download'), downloads.comparison || downloads.full || downloads.zip || manifest.summary?.zip);
  const details = $('provenance-content');
  const revision = manifest.sourceRevision;
  const revisionText = typeof revision === 'string' ? revision : revision?.commit || revision?.revision || '';
  details.append(text('p', '', `${manifest.clips.length} fixed recordings · C2 / C4 / C6 · Generated ${manifest.generatedAt || 'from the study renderer'}.`));
  if (revisionText) details.append(text('p', '', `Source ${revisionText}.`));
  if (manifest.description) details.append(text('p', '', manifest.description));
})();
