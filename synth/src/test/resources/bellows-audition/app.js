'use strict';

(() => {
  const audio = document.getElementById('audio');
  const grid = document.getElementById('clip-grid');
  const status = document.getElementById('playback-status');
  const loading = document.getElementById('loading');
  const roomError = document.getElementById('room-error');
  const emptyState = document.getElementById('empty-state');
  const familyLabel = document.getElementById('transport-family');
  const titleLabel = document.getElementById('transport-title');
  const bars = document.getElementById('activity-bars');
  const matchedButton = document.getElementById('matched-button');
  const rawButton = document.getElementById('raw-button');
  const volume = document.getElementById('volume');
  const volumeValue = document.getElementById('volume-value');

  const collections = {
    library: {
      eyebrow: '01 / PLAY THE LIBRARY',
      title: 'Find a sound to play.',
      description: 'Twenty-four dry presets, six from each family. Start with a sound, then hear what happens in a phrase or chord.'
    },
    musical: {
      eyebrow: '02 / PUT IT IN A PHRASE',
      title: 'Give the notes some company.',
      description: 'Four phrases and four held chords at 96 BPM. Real attacks, looping sustains, changing touch and note-off releases. Dry mono, with one separate fixed C3 bed under the Wheel chords.'
    },
    'body-breath': {
      eyebrow: '03 / BODY & BREATH',
      title: 'Hear the full range.',
      description: 'The same note and gesture at both ends of each control. Play A and B separately, or listen to A then B.'
    },
    'first-listen': {
      eyebrow: '04 / MEET THE VOICES',
      title: 'Find your first favorite.',
      description: 'Two sounds from each family. Listen for the reeds, the air, and the motion.'
    },
    control: {
      eyebrow: '05 / ONE CHANGE AT A TIME',
      title: 'Follow the change.',
      description: 'Compare pressure, body, reed beating, direction, and buzz. Keep your ear on what moves.'
    },
    notes: {
      eyebrow: '06 / ACROSS THE KEYBOARD',
      title: 'Different notes. Different touch.',
      description: 'Hear how the voices respond across pitch and playing strength.'
    },
    hold: {
      eyebrow: '07 / LET IT SETTLE',
      title: 'Give the sound some time.',
      description: 'A pad plays each sound once. Turn on Repeat for a longer listen to a loop; the playable keygroups carry an attack and sustain path of their own.'
    }
  };
  const familyNumbers = { CHAMBER: '01', COUNTER: '02', FOLK: '03', WHEEL: '04' };
  let clips = [];
  let section = 'library';
  let family = 'all';
  let levelMode = 'matched';
  let selectedId = null;
  let serial = 0;
  let isSeeking = false;
  let loadingAudio = false;
  let viewCards = new Map();
  const repeats = new Map();

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function announce(message) {
    status.textContent = message;
  }

  function normalizeSection(value) {
    const normalized = String(value || 'first-listen').toLowerCase().replace(/[_\s]+/g, '-');
    if (normalized === 'library') return 'library';
    if (normalized === 'musical') return 'musical';
    if (/^(?:body-breath|body-and-breath)$/.test(normalized)) return 'body-breath';
    if (/^(?:first-listen|first|presets?|identity|audition)$/.test(normalized)) return 'first-listen';
    if (/pitch|velocity|note|touch/.test(normalized)) return 'notes';
    if (/(?:^|-)(?:held|hold|loop|sustain)(?:-|$)/.test(normalized)) return 'hold';
    return 'control';
  }

  function safeFile(value) {
    if (typeof value !== 'string' || !value.trim() || /^(?:[a-z]+:|\/\/|\/)/i.test(value)) return null;
    try {
      const base = new URL('.', window.location.href);
      const url = new URL(value, base);
      if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) return null;
      return value;
    } catch (_) { return null; }
  }

  function currentClip() { return clips.find(clip => clip.id === selectedId); }
  function visibleClips() { return clips.filter(clip => clip.collection === section && (family === 'all' || clip.voice === family)); }
  function sourceFor(clip) { return levelMode === 'raw' && clip.rawFile ? clip.rawFile : clip.file; }
  function isLoop(clip) { return clip.collection === 'hold' && (clip.loop !== false && clip.loopable !== false); }
  function comparisonName(comparison) { return comparison.parameter === 'pressure' ? 'BREATH' : 'BODY'; }
  function comparisonDescription(comparison) {
    if (comparison.endpoint === 'joined') return 'First A, a short pause, then B. The same note and playing gesture.';
    if (comparison.endpoint === 'step') return 'An intermediate setting between the two endpoints. Follow the change by ear.';
    if (comparison.parameter === 'body') return comparison.endpoint === 'low' ? 'The direct voice, with the enclosure bypassed.' : 'The same note through the full enclosure.';
    return comparison.endpoint === 'low' ? 'Gentle breath still plays a pitched note.' : 'The same note at the top of the breath control.';
  }
  function downloadName(file) {
    try { return decodeURIComponent(new URL(file, window.location.href).pathname.split('/').pop()); }
    catch (_) { return String(file).split('/').pop().split('?')[0]; }
  }

  function normalizeComparison(value, voice) {
    if (!value || typeof value !== 'object' || !['body', 'pressure'].includes(String(value.parameter).toLowerCase())) return null;
    const parameter = String(value.parameter).toLowerCase();
    const endpoint = String(value.endpoint || 'step').toLowerCase();
    const numericValue = value.value !== null && value.value !== undefined ? Number(value.value) : null;
    return {
      ...value,
      parameter,
      endpoint: ['low', 'high', 'joined', 'step'].includes(endpoint) ? endpoint : 'step',
      value: Number.isFinite(numericValue) ? numericValue : null,
      pairId: String(value.pairId || `${voice.toLowerCase()}-${parameter}`),
      lowLabel: String(value.lowLabel || (parameter === 'body' ? 'No body' : 'Gentle breath')),
      highLabel: String(value.highLabel || (parameter === 'body' ? 'Full body' : 'Full breath')),
      segments: Array.isArray(value.segments) ? value.segments.filter(segment => segment && Number.isFinite(Number(segment.startSeconds)) && Number.isFinite(Number(segment.durationSeconds))).map(segment => ({ ...segment, startSeconds: Number(segment.startSeconds), durationSeconds: Number(segment.durationSeconds), label: String(segment.label || '') })) : []
    };
  }

  function timeLabel(seconds) {
    const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
    return `${Math.floor(safe / 60)}:${Math.floor(safe % 60).toString().padStart(2, '0')}`;
  }

  function numberLabel(value) {
    if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)));
    if (typeof value === 'boolean') return value ? 'On' : 'Off';
    if (value && typeof value === 'object') return JSON.stringify(value);
    return String(value ?? '—');
  }

  function noteLabel(midi) {
    const value = Number(midi);
    if (!Number.isFinite(value)) return null;
    const notes = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
    const rounded = Math.round(value);
    return `${notes[((rounded % 12) + 12) % 12]}${Math.floor(rounded / 12) - 1}`;
  }

  function updateTransport() {
    const clip = currentClip();
    const playing = !audio.paused && !audio.ended;
    if (clip) {
      familyLabel.textContent = `${clip.voice} / ${levelMode === 'matched' ? 'MATCHED' : clip.rawFile ? 'RAW' : 'MATCHED · RAW UNAVAILABLE'}`;
      titleLabel.textContent = clip.title;
    } else {
      familyLabel.textContent = 'LISTENING ROOM';
      titleLabel.textContent = 'Pick a sound. Make some air.';
    }
    bars.classList.toggle('playing', playing);
    for (const [id, card] of viewCards) {
      const selected = id === selectedId;
      const active = selected && playing;
      card.root.classList.toggle('selected', selected);
      card.root.classList.toggle('playing', active);
      card.seek.hidden = !selected;
      card.playText.textContent = selected && loadingAudio ? 'Loading' : active ? 'Pause' : card.clip.comparison?.endpoint === 'joined' ? 'A → B' : 'Play';
      card.playIcon.textContent = active ? 'Ⅱ' : '▶';
      card.play.setAttribute('aria-label', `${active ? 'Pause' : 'Play'} ${card.clip.title}`);
      card.play.setAttribute('aria-pressed', active ? 'true' : 'false');
      card.stop.disabled = !selected;
      card.download.href = sourceFor(card.clip);
      card.download.download = downloadName(sourceFor(card.clip));
      if (card.repeat) card.repeat.setAttribute('aria-pressed', repeats.get(id) ? 'true' : 'false');
      if (!selected || !playing) for (const segment of card.segments) {
        segment.node.classList.remove('active');
        segment.node.removeAttribute('aria-current');
      }
    }
    updateSeek();
  }

  function updateSeek() {
    const card = viewCards.get(selectedId);
    if (!card) return;
    const length = Number.isFinite(audio.duration) ? audio.duration : Number(card.clip.durationSeconds) || 0;
    card.slider.max = length > 0 ? String(length) : '1';
    card.slider.disabled = !Number.isFinite(audio.duration) || audio.duration <= 0;
    if (!isSeeking) card.slider.value = String(Math.min(audio.currentTime || 0, length || 0));
    card.time.textContent = `${timeLabel(audio.currentTime)} / ${timeLabel(length)}`;
    card.slider.setAttribute('aria-valuetext', `${timeLabel(audio.currentTime)} of ${timeLabel(length)}`);
    for (const segment of card.segments) {
      const active = audio.currentTime >= segment.startSeconds && audio.currentTime < segment.startSeconds + segment.durationSeconds && !audio.paused;
      segment.node.classList.toggle('active', active);
      if (active) segment.node.setAttribute('aria-current', 'true');
      else segment.node.removeAttribute('aria-current');
    }
  }

  function stopAudio(message = 'Stopped') {
    serial += 1;
    loadingAudio = false;
    audio.pause();
    try { audio.currentTime = 0; } catch (_) {}
    announce(message);
    updateTransport();
  }

  async function playClip(clip) {
    const same = selectedId === clip.id && audio.dataset.file === sourceFor(clip);
    if (same && !audio.paused) {
      serial += 1;
      loadingAudio = false;
      audio.pause();
      announce('Paused');
      updateTransport();
      return;
    }
    const request = ++serial;
    if (!same) {
      audio.pause();
      selectedId = clip.id;
      isSeeking = false;
      audio.src = sourceFor(clip);
      audio.dataset.file = sourceFor(clip);
      audio.load();
    } else if (audio.ended) {
      audio.currentTime = 0;
    }
    audio.loop = isLoop(clip) && Boolean(repeats.get(clip.id));
    loadingAudio = true;
    announce(`Loading ${clip.title}…`);
    updateTransport();
    try {
      await audio.play();
      if (request !== serial) return;
      loadingAudio = false;
      announce(audio.loop ? 'Playing · Repeat on' : 'Playing');
      updateTransport();
    } catch (error) {
      if (request !== serial) return;
      loadingAudio = false;
      if (error.name === 'AbortError') announce('Playback stopped');
      else if (error.name === 'NotAllowedError') announce('Playback was blocked. Press Play to try again.');
      else announce('This audio could not load. Try another sound or save its WAV.');
      updateTransport();
    }
  }

  function addRecipe(card, clip) {
    const entries = clip.macros && typeof clip.macros === 'object' ? Object.entries(clip.macros) : [];
    if (!entries.length && !clip.gesture) return;
    const details = el('details', 'recipe');
    details.append(el('summary', '', 'SOUND RECIPE'));
    const body = el('div', 'recipe-body');
    if (clip.gesture) {
      let gesture = clip.gesture;
      if (typeof gesture === 'object') gesture = Object.entries(gesture).map(([key, value]) => `${key}: ${numberLabel(value)}`).join(' · ');
      body.append(el('p', '', `Gesture: ${gesture}`));
    }
    if (entries.length) {
      const values = el('dl', 'recipe-values');
      for (const [name, value] of entries) {
        const row = el('div');
        row.append(el('dt', '', name.replace(/[_-]/g, ' ')), el('dd', '', numberLabel(value)));
        values.append(row);
      }
      body.append(values);
    }
    details.append(body);
    card.append(details);
  }

  function buildCard(clip, index) {
    const card = el('article', 'clip-card');
    const comparison = clip.collection === 'body-breath' ? clip.comparison : null;
    if (comparison) card.classList.add('comparison-card');
    if (comparison?.endpoint === 'joined') card.classList.add('joined-card');
    card.dataset.family = clip.voice;
    const topline = el('div', 'card-topline');
    const tag = el('span', 'family-tag');
    const dot = el('span', 'family-dot');
    dot.setAttribute('aria-hidden', 'true');
    tag.append(dot, document.createTextNode(`${familyNumbers[clip.voice] || '—'} / ${clip.voice}`));
    topline.append(tag, el('span', 'card-number', String(index + 1).padStart(2, '0')));
    let cardTitle = clip.title;
    if (comparison?.endpoint === 'low') cardTitle = comparison.lowLabel;
    if (comparison?.endpoint === 'high') cardTitle = comparison.highLabel;
    if (comparison?.endpoint === 'joined') cardTitle = 'A then B';
    if (comparison?.endpoint === 'step') cardTitle = `${comparisonName(comparison)} ${Math.round(comparison.value * 100)}%`;
    const heading = el('h3', '', cardTitle);
    heading.id = `clip-title-${index}`;
    card.setAttribute('aria-labelledby', heading.id);
    card.append(topline);
    if (comparison) {
      const marker = comparison.endpoint === 'joined' ? `${comparisonName(comparison)} / FULL-RANGE A–B` : `${comparison.endpoint === 'low' ? 'A / ' : comparison.endpoint === 'high' ? 'B / ' : ''}${comparisonName(comparison)} ${comparison.value === null ? '' : `${Math.round(comparison.value * 100)}%`}`;
      card.append(el('div', 'endpoint-marker', marker));
    }
    if (clip.role && !comparison) card.append(el('div', 'preset-role', clip.role));
    card.append(heading, el('p', 'card-description', comparison ? comparisonDescription(comparison) : clip.description || 'A dry voice from the BELLOWS prototype.'));
    const segments = [];
    if (comparison?.endpoint === 'joined') {
      const route = el('div', 'comparison-route');
      const labels = [comparison.lowLabel, comparison.highLabel];
      for (let segmentIndex = 0; segmentIndex < 2; segmentIndex += 1) {
        if (segmentIndex) route.append(el('span', 'route-arrow', '→'));
        const segment = comparison.segments[segmentIndex];
        const label = el('span', 'route-segment', segment?.label || `${segmentIndex ? 'B' : 'A'} · ${labels[segmentIndex]}`);
        route.append(label);
        if (segment) segments.push({ node: label, startSeconds: segment.startSeconds, durationSeconds: segment.durationSeconds });
      }
      card.append(route);
    }
    const metadata = el('div', 'clip-metadata');
    const note = noteLabel(clip.midi);
    if (note && !clip.performance) metadata.append(el('span', '', note));
    if (!clip.performance && clip.velocity !== undefined && clip.velocity !== null) metadata.append(el('span', '', `VEL ${numberLabel(clip.velocity)}`));
    if (Number.isFinite(Number(clip.durationSeconds))) metadata.append(el('span', '', `${Number(clip.durationSeconds).toFixed(1)} SEC`));
    if (isLoop(clip)) metadata.append(el('span', '', 'LOOP'));
    if (clip.performance) metadata.append(el('span', '', `${clip.performance.bpm} BPM`));
    card.append(metadata);

    const controls = el('div', 'card-controls');
    const play = el('button', 'play-button');
    play.type = 'button';
    play.setAttribute('aria-pressed', 'false');
    const playIcon = el('span', 'play-icon', '▶');
    playIcon.setAttribute('aria-hidden', 'true');
    const playText = el('span', '', comparison?.endpoint === 'joined' ? 'A → B' : 'Play');
    play.append(playIcon, playText);
    play.addEventListener('click', () => playClip(clip));
    const stop = el('button', 'clip-stop', '■');
    stop.type = 'button';
    stop.setAttribute('aria-label', `Stop ${clip.title}`);
    stop.addEventListener('click', () => { if (selectedId === clip.id) stopAudio(); });
    controls.append(play, stop);
    let repeat;
    if (isLoop(clip)) {
      repeat = el('button', 'repeat-button');
      repeat.type = 'button';
      repeat.append(el('span', '', '↻'), document.createTextNode('Repeat'));
      repeat.setAttribute('aria-label', `Repeat ${clip.title}`);
      repeat.setAttribute('aria-pressed', repeats.get(clip.id) ? 'true' : 'false');
      repeat.addEventListener('click', () => {
        const enabled = !repeats.get(clip.id);
        repeats.set(clip.id, enabled);
        repeat.setAttribute('aria-pressed', enabled ? 'true' : 'false');
        if (selectedId === clip.id) {
          audio.loop = enabled;
          announce(enabled ? 'Repeat on' : 'Repeat off');
        }
      });
      controls.append(repeat);
    }
    const download = el('a', 'download-link');
    download.append(document.createTextNode('WAV'), el('span', 'download-arrow', '↓'));
    download.setAttribute('aria-label', `Save ${clip.title} as WAV`);
    controls.append(download);
    card.append(controls);

    const seek = el('div', 'card-seek');
    seek.hidden = true;
    const seekRow = el('div', 'seek-row');
    const slider = el('input');
    slider.type = 'range';
    slider.min = '0';
    slider.max = '1';
    slider.step = '0.01';
    slider.value = '0';
    slider.setAttribute('aria-label', `Playback position for ${clip.title}`);
    slider.addEventListener('pointerdown', () => { isSeeking = true; });
    slider.addEventListener('input', () => {
      if (selectedId !== clip.id || !Number.isFinite(audio.duration)) return;
      audio.currentTime = Number(slider.value);
      const length = audio.duration;
      time.textContent = `${timeLabel(audio.currentTime)} / ${timeLabel(length)}`;
      slider.setAttribute('aria-valuetext', `${timeLabel(audio.currentTime)} of ${timeLabel(length)}`);
    });
    slider.addEventListener('change', () => { isSeeking = false; updateSeek(); });
    slider.addEventListener('pointerup', () => { isSeeking = false; updateSeek(); });
    slider.addEventListener('pointercancel', () => { isSeeking = false; updateSeek(); });
    slider.addEventListener('blur', () => { isSeeking = false; });
    const time = el('span', 'seek-time', `0:00 / ${timeLabel(Number(clip.durationSeconds))}`);
    seekRow.append(slider, time);
    seek.append(seekRow);
    card.append(seek);
    addRecipe(card, clip);
    viewCards.set(clip.id, { root: card, clip, play, playIcon, playText, stop, download, repeat, seek, slider, time, segments });
    return card;
  }

  function renderComparisons(visible) {
    const groups = new Map();
    const ungrouped = [];
    for (const clip of visible) {
      if (!clip.comparison) { ungrouped.push(clip); continue; }
      const key = `${clip.voice}:${clip.comparison.pairId}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(clip);
    }
    let cardIndex = 0;
    let groupIndex = 0;
    for (const groupClips of groups.values()) {
      const reference = groupClips[0];
      const comparison = reference.comparison;
      const group = el('section', 'comparison-group');
      const header = el('div', 'comparison-pair-header');
      const title = el('h3', '', `${reference.voice} / ${comparisonName(comparison)}`);
      title.id = `comparison-title-${groupIndex++}`;
      group.setAttribute('aria-labelledby', title.id);
      const route = el('p', '', `${comparison.lowLabel} → ${comparison.highLabel}`);
      const note = el('span', 'small-label', 'SAME NOTE · SAME GESTURE');
      const headerCopy = el('div');
      headerCopy.append(title, route);
      header.append(headerCopy, note);
      group.append(header);
      const pair = el('div', 'comparison-pair-clips');
      const ordering = { low: 0, high: 1, joined: 2, step: 3 };
      const sorted = [...groupClips].sort((a, b) => ordering[a.comparison.endpoint] - ordering[b.comparison.endpoint] || (a.comparison.value || 0) - (b.comparison.value || 0));
      const steps = sorted.filter(clip => clip.comparison.endpoint === 'step');
      for (const clip of sorted.filter(clip => clip.comparison.endpoint !== 'step')) pair.append(buildCard(clip, cardIndex++));
      group.append(pair);
      if (steps.length) {
        const details = el('details', 'comparison-steps');
        details.append(el('summary', '', `More steps · ${steps.map(clip => `${Math.round(clip.comparison.value * 100)}%`).join(' / ')}`));
        const stepGrid = el('div', 'comparison-step-grid');
        for (const clip of steps) stepGrid.append(buildCard(clip, cardIndex++));
        details.append(stepGrid);
        group.append(details);
      }
      grid.append(group);
    }
    for (const clip of ungrouped) grid.append(buildCard(clip, cardIndex++));
  }

  function renderView() {
    viewCards = new Map();
    grid.replaceChildren();
    const visible = visibleClips();
    document.getElementById('level-description').textContent = section === 'musical' ? (levelMode === 'matched' ? 'Matched levels the whole performance; touch stays intact.' : 'Raw plays the native performance before audition matching.') : (levelMode === 'matched' ? 'Matched compares color at similar level.' : 'Raw preserves source dynamics.');
    grid.classList.toggle('comparison-grid', section === 'body-breath');
    document.getElementById('focus-guide').hidden = section !== 'body-breath';
    if (section === 'body-breath') renderComparisons(visible);
    else visible.forEach((clip, index) => grid.append(buildCard(clip, index)));
    emptyState.hidden = visible.length > 0 || !clips.length;
    document.getElementById('collection-eyebrow').textContent = collections[section].eyebrow;
    document.getElementById('section-title').textContent = collections[section].title;
    document.getElementById('section-description').textContent = collections[section].description;
    for (const button of document.querySelectorAll('[data-section]')) {
      const active = button.dataset.section === section;
      button.classList.toggle('selected', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    }
    for (const button of document.querySelectorAll('[data-family]')) {
      if (!button.classList.contains('family-filter')) continue;
      const active = button.dataset.family === family;
      button.classList.toggle('selected', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    }
    updateTransport();
  }

  function setLevel(mode) {
    if (levelMode === mode) return;
    levelMode = mode;
    stopAudio(mode === 'raw' ? 'Raw level selected. Press Play to compare.' : 'Matched level selected. Press Play to compare.');
    audio.removeAttribute('src');
    delete audio.dataset.file;
    audio.load();
    matchedButton.classList.toggle('selected', mode === 'matched');
    rawButton.classList.toggle('selected', mode === 'raw');
    matchedButton.setAttribute('aria-pressed', mode === 'matched' ? 'true' : 'false');
    rawButton.setAttribute('aria-pressed', mode === 'raw' ? 'true' : 'false');
    document.getElementById('level-description').textContent = section === 'musical' ? (mode === 'matched' ? 'Matched levels the whole performance; touch stays intact.' : 'Raw plays the native performance before audition matching.') : (mode === 'matched' ? 'Matched compares color at similar level.' : 'Raw preserves source dynamics.');
    updateTransport();
  }

  function setVolume(value) {
    const safe = Math.max(0, Math.min(1, Number(value) || 0));
    audio.volume = safe;
    volume.value = String(safe);
    volumeValue.textContent = `${Math.round(safe * 100)}%`;
    try { localStorage.setItem('bellows-volume', String(safe)); } catch (_) {}
  }

  function addDownloads(manifest) {
    if (Array.isArray(manifest.instrumentDownloads)) for (const item of manifest.instrumentDownloads) {
      const file = safeFile(item.file);
      if (!file) continue;
      const link = el('a', 'instrument-download', item.label || 'Instrument');
      link.href = file;
      link.download = downloadName(file);
      link.append(el('span', '', '↓'));
      document.querySelector('.download-buttons').append(link);
    }
    for (const [key, id] of [['libraryZip', 'download-library'], ['instrumentZip', 'download-instruments']]) {
      const file = safeFile(manifest[key]);
      if (file) {
        const link = document.getElementById(id);
        link.href = file;
        link.download = downloadName(file);
        link.hidden = false;
      }
    }
    const focusZip = safeFile(manifest.bodyBreathZip);
    if (focusZip) {
      const link = document.getElementById('download-body-breath');
      link.href = focusZip;
      link.download = downloadName(focusZip);
      link.hidden = false;
    }
    const zip = safeFile(manifest.firstListenZip);
    if (zip) {
      const link = document.getElementById('download-first-listen');
      link.href = zip;
      link.download = downloadName(zip);
      link.hidden = false;
    }
    let patches = safeFile(manifest.patches);
    if (!patches && manifest.patches && typeof manifest.patches === 'object') {
      patches = URL.createObjectURL(new Blob([JSON.stringify(manifest.patches, null, 2)], { type: 'application/json' }));
    }
    if (patches) {
      const link = document.getElementById('download-patches');
      link.href = patches;
      link.download = typeof manifest.patches === 'string' ? downloadName(patches) : 'bellows-patches.json';
      link.hidden = false;
    }
  }

  for (const button of document.querySelectorAll('.view-tab')) button.addEventListener('click', () => { section = button.dataset.section; renderView(); });
  for (const button of document.querySelectorAll('.family-filter')) button.addEventListener('click', () => { family = button.dataset.family; renderView(); });
  matchedButton.addEventListener('click', () => setLevel('matched'));
  rawButton.addEventListener('click', () => setLevel('raw'));
  document.getElementById('stop-all').addEventListener('click', () => stopAudio());
  volume.addEventListener('input', () => setVolume(volume.value));
  let savedVolume = 0.75;
  try { const saved = localStorage.getItem('bellows-volume'); if (saved !== null && Number.isFinite(Number(saved))) savedVolume = Number(saved); } catch (_) {}
  setVolume(savedVolume);

  audio.addEventListener('loadedmetadata', updateSeek);
  audio.addEventListener('durationchange', updateSeek);
  audio.addEventListener('timeupdate', updateSeek);
  audio.addEventListener('playing', () => {
    loadingAudio = false;
    if (currentClip()) announce(audio.loop ? 'Playing · Repeat on' : 'Playing');
    updateTransport();
  });
  audio.addEventListener('pause', updateTransport);
  audio.addEventListener('waiting', () => {
    if (!audio.paused && currentClip()) { loadingAudio = true; announce('Buffering audio…'); updateTransport(); }
  });
  audio.addEventListener('ended', () => {
    loadingAudio = false;
    try { audio.currentTime = 0; } catch (_) {}
    announce('Finished · Pick another sound');
    updateTransport();
  });
  audio.addEventListener('error', () => {
    if (!audio.error || !audio.dataset.file) return;
    loadingAudio = false;
    announce('This audio could not load. Try another sound or save its WAV.');
    updateTransport();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { stopAudio(); return; }
    if (event.code !== 'Space' || event.repeat || event.altKey || event.ctrlKey || event.metaKey) return;
    const target = event.target;
    if (target && target.closest('button, a, input, textarea, select, summary, [contenteditable="true"]')) return;
    const clip = currentClip() || visibleClips()[0];
    if (!clip) return;
    event.preventDefault();
    playClip(clip);
  });

  async function loadManifest() {
    try {
      const response = await fetch('manifest.json?v=3', { cache: 'no-store' });
      if (!response.ok) throw new Error('Manifest unavailable');
      const manifest = await response.json();
      if (!manifest || !Array.isArray(manifest.clips)) throw new Error('Invalid manifest');
      const ids = new Set();
      clips = manifest.clips.filter(clip => clip && safeFile(clip.file)).map((clip, index) => {
        let id = String(clip.id || `clip-${index}`);
        while (ids.has(id)) id += `-${index}`;
        ids.add(id);
        const voice = String(clip.voice || clip.model || 'BELLOWS').toUpperCase();
        return {
          ...clip,
          id,
          title: String(clip.title || `Audition ${index + 1}`),
          voice,
          collection: normalizeSection(clip.section),
          file: safeFile(clip.file),
          rawFile: safeFile(clip.rawFile),
          comparison: normalizeComparison(clip.comparison, voice)
        };
      });
      if (!clips.length) throw new Error('No audio in manifest');
      if (!clips.some(clip => clip.collection === 'body-breath')) section = 'first-listen';
      addDownloads(manifest);
      for (const count of document.querySelectorAll('[data-count]')) count.textContent = clips.filter(clip => clip.collection === count.dataset.count).length;
      rawButton.disabled = !clips.some(clip => clip.rawFile);
      if (rawButton.disabled) rawButton.title = 'Raw renders are unavailable for this audition.';
      loading.hidden = true;
      renderView();
    } catch (_) {
      loading.hidden = true;
      roomError.hidden = false;
      roomError.textContent = 'The audition manifest could not load. Refresh this page to try again. If you opened a downloaded copy, serve the folder through a local web server so the audio manifest can be read.';
      announce('Listening room unavailable');
    }
  }

  loadManifest();
})();
