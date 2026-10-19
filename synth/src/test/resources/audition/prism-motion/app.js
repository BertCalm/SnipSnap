(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const STORE = 'snipsnap-prism-motion-review-v1';
  const state = { manifest: null, clips: [], families: [], family: '', pitch: '', motionVariant: 'motion-medium', current: null, level: 'matched', reviews: {}, storage: true };
  let noticeTimer;
  let loadCounter = 0;
  try { state.reviews = JSON.parse(localStorage.getItem(STORE) || '{}') || {}; }
  catch { state.storage = false; }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function human(value) { return String(value || '').replace(/[_-]+/g, ' ').replace(/\b\w/g, char => char.toUpperCase()); }
  function familyName(value) {
    const data = familyInfo(value);
    return data.title || data.name || state.clips.find(clip => clip.family === value)?.familyTitle || human(value);
  }
  function familyInfo(value) {
    const families = state.manifest?.families;
    if (Array.isArray(families)) return families.find(item => item.id === value || item.family === value) || {};
    if (families && typeof families === 'object') return families[value] || {};
    return {};
  }
  function time(value) {
    if (!Number.isFinite(Number(value))) return '0:00';
    const seconds = Math.max(0, Number(value));
    return `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`;
  }
  function noteName(value) {
    if (value === null || value === undefined || value === '') return '—';
    const midi = Number(value);
    if (!Number.isFinite(midi)) return String(value);
    return ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((midi % 12) + 12) % 12] + (Math.floor(midi / 12) - 1);
  }
  function seconds(clip) { return Number.isFinite(Number(clip.seconds)) ? `${Number(clip.seconds).toFixed(1)} s` : ''; }
  function role(clip) {
    const text = String(clip.variant || '').toLowerCase();
    if (/baseline|static|still|unmoving|original|^off$/.test(text)) return 'baseline';
    if (/endpoint|destination|target|^end$/.test(text)) return 'endpoint';
    if (/motion|moving|morph|travel|transition|stagger/.test(text)) return 'moving';
    return '';
  }
  function isSynthDonor(clip) {
    const donor = clip.donor || clip.source || clip.endpointSource || clip.sourceKind || '';
    const text = `${clip.family} ${clip.variant} ${clip.title} ${clip.description} ${typeof donor === 'string' ? donor : JSON.stringify(donor)}`;
    return clip.family === 'donor-imprint' || clip.variant === 'donor' || /(?:synthesi[sz]ed|synthetic|generated)[^.]{0,60}(?:donor|fixture)|(?:donor|imprint)[^.]{0,60}(?:synthesi[sz]ed|synthetic|generated)/i.test(text) || clip.synthesizedDonor === true;
  }
  function safePath(value) {
    if (typeof value !== 'string' || !value) return null;
    try {
      const url = new URL(value, document.baseURI);
      return url.origin === location.origin && ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch { return null; }
  }
  function fileFor(clip) { return safePath(clip[state.level]) || safePath(clip[state.level === 'matched' ? 'raw' : 'matched']); }
  function link(node, value) {
    const url = safePath(value);
    if (url) { node.href = url; node.removeAttribute('aria-disabled'); node.removeAttribute('tabindex'); }
    else { node.removeAttribute('href'); node.setAttribute('aria-disabled', 'true'); node.setAttribute('tabindex', '-1'); }
  }
  function notify(message) {
    $('notification').textContent = message;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { $('notification').textContent = ''; }, 3800);
  }
  function persist() {
    try { localStorage.setItem(STORE, JSON.stringify(state.reviews)); }
    catch { state.storage = false; }
    $('save-status').textContent = state.storage ? 'Saved in this browser. Export a copy to keep it.' : 'Browser storage is unavailable. Export notes before leaving.';
    updateReviewCount();
  }
  function updateReviewCount() {
    const count = Object.values(state.reviews).filter(review => review && (review.note || review.verdict)).length;
    $('review-count').textContent = `${count} reviewed`;
  }
  function saveReview(changes) {
    if (!state.current) return;
    const id = state.current.id;
    state.reviews[id] = { ...(state.reviews[id] || {}), ...changes, updatedAt: new Date().toISOString() };
    persist();
    updateSelected();
  }
  function showReview() {
    const clip = state.current;
    const review = state.reviews[clip?.id] || {};
    $('review-clip').textContent = clip?.title || 'Select a render.';
    $('note').value = review.note || '';
    document.querySelectorAll('[data-verdict]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.verdict === review.verdict));
      button.disabled = !clip;
    });
    $('note').disabled = !clip;
    $('save-status').textContent = state.storage ? 'Notes stay in this browser. Export a copy to keep them.' : 'Browser storage is unavailable. Export notes before leaving.';
    renderDetails();
  }
  function renderDetails() {
    const target = $('render-details');
    target.replaceChildren();
    const clip = state.current;
    if (!clip) return;
    const hasMidi = clip.midi !== null && clip.midi !== undefined && clip.midi !== '' && Number.isFinite(Number(clip.midi));
    const rows = [['Family', familyName(clip.family)], ['Variant', human(clip.variant)], ['Note', hasMidi ? `${noteName(clip.midi)} / MIDI ${clip.midi}` : clip.variant === 'donor' ? 'Donor source / no assigned note' : 'Not specified'], ['Length', seconds(clip)], ['Playback', state.level === 'matched' ? 'Matched listening level' : 'Native engine level']];
    for (const [key, label] of [['rawPeak', 'Native peak'], ['rawRms', 'Native RMS'], ['nominalHz', 'Root Hz'], ['pitchHz', 'Pitch Hz']]) {
      if (Number.isFinite(clip.metrics?.[key])) rows.push([label, Number(clip.metrics[key]).toPrecision(4)]);
    }
    if (isSynthDonor(clip)) rows.push(['Donor source', 'Synthesized test source']);
    for (const [label, value] of rows) if (value) { target.append(el('dt', '', label), el('dd', '', value)); }
  }

  function guideFor(family) {
    const info = familyInfo(family);
    if (info.whatToHear || info.guide || info.description) return info.whatToHear || info.guide || info.description;
    const guides = {
      'metal-vowel': 'Hear the bright metallic strike turn toward a broad vowel colour. The carrier ratios and articulation stay fixed; judge the changing balance inside the note.',
      'organ-glass': 'Follow the organ body toward a sparse glass colour. C3 is the main comparison; C2 and C4 recheck the same gesture in another register.',
      'pluck-reed': 'The pluck keeps its articulation while its partials acquire a reed colour. Compare the ordinary movement with later-arriving upper groups.',
      'donor-imprint': 'Hear a smoothed colour borrowed from the synthesized VOX donor. PRISM supplies the carriers and articulation; this is a colour imprint from a generated test source.'
    };
    if (guides[family]) return guides[family];
    const text = family.toLowerCase();
    if (/donor|imprint|capture/.test(text)) return 'Hear the borrowed spectral colour over a freshly played root. A synthesized donor is a generated test source; judge the colour transfer, not a capture reconstruction.';
    if (/vowel|mouth|breath|reed/.test(text)) return 'Follow the vowel or reed colour through the note. Does the material change clearly while one pitch continues to carry it?';
    if (/glass|metal|bell|strike/.test(text)) return 'Listen past the contact into the body. Does the metallic colour turn into a useful destination without losing the note?';
    if (/group|stagger|split|bloom/.test(text)) return 'Listen for the spectral groups arriving at different times. The upper colour should develop around a stable low body.';
    if (/tilt|bright/.test(text)) return 'Compare a change in overall brightness with the moving material. Does the motion give the note a character beyond a simple dark-to-bright sweep?';
    return 'Compare the baseline, the moving note and its destination. Listen for a clear change of material, a stable root and a smooth tail.';
  }
  function setFamily(family, preferredPitch) {
    state.family = family;
    $('family-select').value = family;
    const clips = state.clips.filter(clip => clip.family === family);
    const pitches = [...new Set(clips.map(clip => clip.midi === null || clip.midi === undefined ? '' : String(clip.midi)))];
    pitches.sort((a, b) => Number(a) - Number(b));
    const main = clips.find(clip => clip.register === 'main' && clip.variant === 'motion-medium') || clips.find(clip => clip.register === 'main') || clips.find(clip => clip.variant === 'motion-medium');
    state.pitch = preferredPitch !== undefined && pitches.includes(String(preferredPitch)) ? String(preferredPitch) : main ? String(main.midi ?? '') : pitches.includes('60') ? '60' : pitches[Math.floor(pitches.length / 2)] || '';
    $('pitch-select').replaceChildren();
    for (const pitch of pitches) $('pitch-select').append(new Option(noteName(pitch), pitch));
    $('pitch-select').value = state.pitch;
    $('pitch-select').disabled = pitches.length < 2;
    $('family-guide').textContent = guideFor(family);
    $('family-count').textContent = `${String(state.families.indexOf(family) + 1).padStart(2, '0')} / ${String(state.families.length).padStart(2, '0')}`;
    renderCompare();
  }
  function renderCompare() {
    const clips = state.clips.filter(clip => clip.family === state.family && String(clip.midi ?? '') === state.pitch);
    const roles = ['baseline', 'moving', 'endpoint'];
    const moving = clips.find(clip => clip.variant === state.motionVariant) || clips.find(clip => clip.variant === 'motion-medium') || clips.find(clip => role(clip) === 'moving');
    const selected = roles.map(kind => kind === 'moving' ? moving : clips.find(clip => role(clip) === kind)).filter(Boolean);
    for (const clip of clips) if (selected.length < 3 && !selected.includes(clip)) selected.push(clip);
    const target = $('compare-cards');
    target.replaceChildren();
    selected.forEach((clip, i) => {
      const kind = role(clip);
      const button = el('button', 'compare-card');
      button.type = 'button';
      button.dataset.clipId = clip.id;
      button.setAttribute('aria-pressed', String(state.current?.id === clip.id));
      button.setAttribute('aria-label', `Play ${clip.title}`);
      const label = kind === 'baseline' ? 'BASELINE' : kind === 'moving' ? 'IN MOTION' : kind === 'endpoint' ? 'DESTINATION' : human(clip.variant).toUpperCase();
      button.append(el('span', 'step', `${String(i + 1).padStart(2, '0')} / ${label}`));
      button.append(el('strong', '', clip.title || human(clip.variant)));
      button.append(el('span', 'clip-hint', `${noteName(clip.midi)} · ${seconds(clip)}${isSynthDonor(clip) ? ' · synthesized donor' : ''}`));
      const icon = el('span', 'card-play', '▶︎'); icon.setAttribute('aria-hidden', 'true'); button.append(icon);
      button.addEventListener('click', () => selectClip(clip, true));
      target.append(button);
    });
    target.style.gridTemplateColumns = `repeat(${Math.max(1, selected.length)}, minmax(0,1fr))`;
    const choices = $('motion-buttons'); choices.replaceChildren();
    for (const [variant, label] of [['motion-fast', 'Fast'], ['motion-medium', 'Medium'], ['motion-slow', 'Slow'], ['staggered', 'Staggered']]) {
      const clip = clips.find(item => item.variant === variant);
      if (!clip) continue;
      const button = el('button', '', label); button.type = 'button';
      button.setAttribute('aria-pressed', String(moving?.variant === variant));
      button.setAttribute('aria-label', `Play ${label.toLowerCase()} movement in ${familyName(state.family)}`);
      button.addEventListener('click', () => { state.motionVariant = variant; selectClip(clip, true); renderCompare(); });
      choices.append(button);
    }
    $('motion-choices').hidden = choices.children.length < 2;
  }
  function renderLibrary() {
    const query = $('search').value.trim().toLowerCase();
    const family = $('library-family').value;
    const variant = $('library-variant').value;
    const clips = state.clips.filter(clip => (!family || clip.family === family) && (!variant || clip.variant === variant) && (!query || `${clip.title} ${clip.description} ${familyName(clip.family)} ${clip.variant} ${noteName(clip.midi)}`.toLowerCase().includes(query)));
    $('clip-count').textContent = `${clips.length} / ${state.clips.length} renders`;
    $('empty-state').hidden = clips.length > 0;
    const target = $('clip-list'); target.replaceChildren();
    for (const family of state.families) {
      const members = clips.filter(clip => clip.family === family);
      if (!members.length) continue;
      const section = el('section', 'library-group');
      const heading = el('h3', '', familyName(family)); heading.append(el('span', '', `${members.length} RENDERS`)); section.append(heading);
      for (const clip of members) {
        const row = el('article', 'clip-row'); row.dataset.rowId = clip.id;
        const play = el('button', 'row-play', '▶︎'); play.type = 'button'; play.dataset.clipId = clip.id;
        play.setAttribute('aria-label', `Play ${clip.title}`); play.setAttribute('aria-pressed', 'false');
        play.addEventListener('click', () => selectClip(clip, true));
        const copy = el('div', 'row-copy');
        const title = el('div', 'row-title', clip.title);
        const stamp = el('span', 'note-dot'); stamp.dataset.reviewId = clip.id; title.append(stamp); copy.append(title);
        if (clip.description) copy.append(el('p', 'row-description', clip.description));
        if (isSynthDonor(clip)) copy.append(el('span', 'donor-badge', 'SYNTHESIZED DONOR / GENERATED TEST SOURCE'));
        const variant = el('span', 'row-variant', human(clip.variant));
        const duration = el('span', 'row-time', `${noteName(clip.midi)} · ${seconds(clip)}`);
        const downloads = el('div', 'row-links');
        const wav = el('a', '', 'WAV ↓'); wav.download = ''; wav.dataset.downloadId = clip.id; link(wav, clip[state.level] || clip.raw || clip.matched);
        const recipe = el('a', '', 'JSON ↓'); recipe.download = ''; link(recipe, clip.recipe);
        if (wav.hasAttribute('href')) downloads.append(wav);
        if (recipe.hasAttribute('href')) downloads.append(recipe);
        row.append(play, copy, variant, duration, downloads); section.append(row);
      }
      target.append(section);
    }
    updateSelected();
  }
  function updateSelected() {
    document.querySelectorAll('[data-clip-id]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.clipId === state.current?.id)));
    document.querySelectorAll('[data-row-id]').forEach(row => row.classList.toggle('is-selected', row.dataset.rowId === state.current?.id));
    document.querySelectorAll('[data-review-id]').forEach(stamp => {
      const review = state.reviews[stamp.dataset.reviewId];
      stamp.textContent = review?.verdict ? review.verdict.toUpperCase() : review?.note ? 'NOTED' : '';
    });
    document.querySelectorAll('[data-verdict]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.verdict === state.reviews[state.current?.id]?.verdict)));
  }
  function selectClip(clip, autoplay) {
    audio.pause();
    state.current = clip;
    if (role(clip) === 'moving') state.motionVariant = clip.variant;
    if (state.family !== clip.family || state.pitch !== String(clip.midi ?? '')) setFamily(clip.family, clip.midi ?? '');
    else renderCompare();
    $('player-family').textContent = `${familyName(clip.family).toUpperCase()} / ${human(clip.variant).toUpperCase()}`;
    $('player-title').textContent = clip.title;
    $('duration').textContent = time(clip.seconds);
    $('current-time').textContent = '0:00'; $('seek').value = '0';
    $('seek').max = String(Number(clip.seconds) || 1);
    const file = fileFor(clip);
    if (file) { audio.src = file; audio.load(); }
    else audio.removeAttribute('src');
    link($('download-wav'), clip[state.level] || clip.raw || clip.matched);
    link($('download-recipe'), clip.recipe);
    $('player-dock').hidden = false;
    $('audio-status').textContent = isSynthDonor(clip) ? 'Synthesized donor · generated test source' : state.level === 'matched' ? 'Matched for comparison' : 'Native engine output';
    showReview(); updateSelected(); drawSpectrum();
    if (autoplay) play();
  }
  async function play() {
    if (!state.current || !audio.getAttribute('src')) return;
    try { await audio.play(); }
    catch (error) { if (error.name !== 'AbortError') $('audio-status').textContent = 'Playback unavailable. Try the browser player or download the WAV.'; }
  }
  function updateTransport() {
    const playing = !audio.paused && !audio.ended;
    $('play-icon').textContent = playing ? 'Ⅱ' : '▶︎';
    $('play').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} selected render`);
    $('play').setAttribute('aria-pressed', String(playing));
  }
  function stop() { audio.pause(); if (audio.readyState > 0) audio.currentTime = 0; updateTransport(); }
  function setLevel(level) {
    if (state.level === level) return;
    const wasPlaying = !audio.paused;
    state.level = level;
    $('level-matched').setAttribute('aria-pressed', String(level === 'matched'));
    $('level-raw').setAttribute('aria-pressed', String(level === 'raw'));
    if (state.current) selectClip(state.current, wasPlaying);
    document.querySelectorAll('[data-download-id]').forEach(anchor => {
      const clip = state.clips.find(item => item.id === anchor.dataset.downloadId);
      if (clip) link(anchor, clip[level] || clip.raw || clip.matched);
    });
  }

  function measuredSpectrum(clip) {
    const input = clip?.spectrum || clip?.spectral || clip?.metrics?.spectrum || clip?.metrics?.spectral;
    if (!input) return null;
    if (Array.isArray(input)) {
      if (input.every(value => typeof value === 'number')) return { points: input.map((value, i) => ({ x: i + 1, y: value })), db: input.some(value => value < 0), hz: false };
      const points = input.map((value, i) => ({ x: Number(value.hz ?? value.frequency ?? i + 1), y: Number(value.db ?? value.amplitude ?? value.magnitude ?? value.level) })).filter(point => Number.isFinite(point.x) && Number.isFinite(point.y));
      return points.length ? { points, db: input.some(value => value.db !== undefined), hz: input.some(value => value.hz || value.frequency) } : null;
    }
    const values = input.amplitudes || input.magnitudes || input.db;
    if (Array.isArray(values)) return { points: values.map((value, i) => ({ x: Number(input.frequencies?.[i] ?? input.hz?.[i] ?? i + 1), y: Number(value) })).filter(point => Number.isFinite(point.x) && Number.isFinite(point.y)), db: !!input.db, hz: !!(input.frequencies || input.hz) };
    return null;
  }
  function drawSpectrum() {
    const canvas = $('spectrum');
    if (!canvas || !canvas.clientWidth) return;
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth, height = canvas.clientHeight;
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    const context = canvas.getContext('2d'); if (!context) return;
    context.scale(ratio, ratio); context.clearRect(0, 0, width, height);
    const measured = measuredSpectrum(state.current);
    const points = measured?.points?.length ? measured.points : Array.from({ length: 40 }, (_, i) => ({ x: i + 1, y: (0.5 + 0.5 * Math.cos(i * .53)) * Math.exp(-i * .045) + .10 }));
    const max = Math.max(...points.map(point => point.y), .000001);
    const bottom = height - 5, plotHeight = height - 13;
    context.strokeStyle = '#dedfd4'; context.lineWidth = 1;
    for (let line = 0; line < 3; line++) { const y = bottom - plotHeight * line / 2; context.beginPath(); context.moveTo(0, y); context.lineTo(width, y); context.stroke(); }
    const positiveX = points.filter(point => point.x > 0);
    const minX = Math.max(.000001, Math.min(...positiveX.map(point => point.x)));
    const maxX = Math.max(minX * 1.01, ...points.map(point => point.x));
    points.forEach((point, index) => {
      const x = measured?.hz ? 4 + Math.log(Math.max(minX, point.x) / minX) / Math.log(maxX / minX) * (width - 8) : 4 + index / Math.max(1, points.length - 1) * (width - 8);
      const normalized = measured?.db ? Math.max(0, Math.min(1, (point.y - (max - 60)) / 60)) : Math.max(0, point.y / max);
      context.strokeStyle = index < points.length * .25 ? '#323b34' : index < points.length * .64 ? '#b64e3a' : '#8d9686';
      context.lineWidth = Math.max(1.5, Math.min(4, width / points.length * .22));
      context.beginPath(); context.moveTo(x, bottom); context.lineTo(x, bottom - normalized * plotHeight); context.stroke();
    });
    $('plot-label').textContent = measured ? 'RENDER SPECTRUM / MANIFEST DATA' : 'PARTIAL MOTION / ILLUSTRATION';
    $('plot-key').textContent = measured ? measured.hz ? 'Frequency →' : 'Partial index →' : 'Body → colour → air';
    const caption = measured ? `Manifest-provided ${measured.db ? 'dB' : 'amplitude'} values, displayed relative to this render’s maximum. ${measured.hz ? 'Log frequency axis.' : 'Partial index axis.'}` : 'Illustrative partial amplitudes. The audio above is the evidence.';
    $('plot-caption').textContent = caption;
    canvas.setAttribute('aria-label', caption);
  }

  async function load() {
    const call = ++loadCounter;
    $('load-status').hidden = false; $('load-error').hidden = true;
    try {
      const response = await fetch('./manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest request returned ${response.status}.`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('The manifest does not contain any renders.');
      if (call !== loadCounter) return;
      state.manifest = manifest;
      const ids = new Set();
      state.clips = manifest.clips.filter(clip => clip && clip.id && !ids.has(clip.id) && (clip.raw || clip.matched) && ids.add(clip.id)).map(clip => ({ ...clip, id: String(clip.id), family: String(clip.family || 'Study'), variant: String(clip.variant || 'render'), title: String(clip.title || human(clip.id)), description: String(clip.description || '') }));
      if (!state.clips.length) throw new Error('No playable render files are listed in the manifest.');
      state.families = [...new Set(state.clips.map(clip => clip.family))];
      if (manifest.title) document.title = `${manifest.title} · SnipSnap`;
      if (manifest.description) $('study-description').textContent = manifest.description;
      $('family-select').replaceChildren();
      $('library-family').replaceChildren(new Option('All families', ''));
      for (const family of state.families) { $('family-select').append(new Option(familyName(family), family)); $('library-family').append(new Option(familyName(family), family)); }
      $('library-variant').replaceChildren(new Option('All variants', ''));
      for (const variant of [...new Set(state.clips.map(clip => clip.variant))]) $('library-variant').append(new Option(human(variant), variant));
      $('study').hidden = false; $('load-status').hidden = true;
      $('donor-disclosure').hidden = !state.clips.some(isSynthDonor);
      const initial = state.clips.find(clip => clip.variant === 'motion-medium' && clip.register === 'main') || state.clips.find(clip => clip.variant === 'motion-medium') || state.clips.find(clip => role(clip) === 'moving') || state.clips[0];
      setFamily(initial.family, initial.midi ?? '');
      renderLibrary(); selectClip(initial, false); updateReviewCount();
    } catch (error) {
      if (call !== loadCounter) return;
      $('load-status').hidden = true; $('load-error').hidden = false;
      $('load-error-detail').textContent = `${error.message} Retry, or inspect the render manifest.`;
    }
  }

  $('family-select').addEventListener('change', event => { state.motionVariant = 'motion-medium'; setFamily(event.target.value); });
  $('pitch-select').addEventListener('change', event => { state.pitch = event.target.value; renderCompare(); });
  $('retry').addEventListener('click', load);
  $('search').addEventListener('input', renderLibrary);
  $('library-family').addEventListener('change', renderLibrary);
  $('library-variant').addEventListener('change', renderLibrary);
  $('reset-filters').addEventListener('click', () => { $('search').value = ''; $('library-family').value = ''; $('library-variant').value = ''; renderLibrary(); });
  $('note').addEventListener('input', event => saveReview({ note: event.target.value }));
  document.querySelectorAll('[data-verdict]').forEach(button => button.addEventListener('click', () => { const previous = state.reviews[state.current?.id]?.verdict; saveReview({ verdict: previous === button.dataset.verdict ? '' : button.dataset.verdict }); }));
  $('play').addEventListener('click', () => audio.paused ? play() : audio.pause());
  $('stop').addEventListener('click', stop);
  $('level-matched').addEventListener('click', () => setLevel('matched'));
  $('level-raw').addEventListener('click', () => setLevel('raw'));
  $('seek').addEventListener('input', event => { if (audio.readyState > 0) audio.currentTime = Number(event.target.value); });
  ['play', 'pause', 'ended'].forEach(event => audio.addEventListener(event, updateTransport));
  audio.addEventListener('loadedmetadata', () => { if (Number.isFinite(audio.duration)) { $('duration').textContent = time(audio.duration); $('seek').max = String(audio.duration); } });
  audio.addEventListener('timeupdate', () => { $('current-time').textContent = time(audio.currentTime); $('seek').value = String(audio.currentTime); });
  audio.addEventListener('error', () => { $('audio-status').textContent = 'WAV could not play. Try the browser player or download the file.'; updateTransport(); });
  document.addEventListener('keydown', event => {
    if (/INPUT|TEXTAREA|SELECT|BUTTON|A|SUMMARY/.test(event.target.tagName) || event.target.isContentEditable || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.code === 'Space' && state.current) { event.preventDefault(); audio.paused ? play() : audio.pause(); }
    if (event.key === 'Escape') stop();
    if (['1', '2', '3'].includes(event.key)) document.querySelectorAll('.compare-card')[Number(event.key) - 1]?.click();
  });
  $('export-notes').addEventListener('click', () => {
    const reviews = Object.entries(state.reviews).filter(([, review]) => review && (review.note || review.verdict)).map(([id, review]) => {
      const clip = state.clips.find(item => item.id === id);
      return { id, title: clip?.title || id, family: clip?.family, variant: clip?.variant, midi: clip?.midi, ...review };
    });
    const payload = { study: state.manifest?.title || 'PRISM motion study', exportedAt: new Date().toISOString(), formatVersion: 1, reviews };
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `prism-motion-notes-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notify(`${reviews.length} listening ${reviews.length === 1 ? 'note' : 'notes'} exported.`);
  });
  window.addEventListener('resize', drawSpectrum);
  window.addEventListener('storage', event => {
    if (event.key !== STORE) return;
    try { state.reviews = JSON.parse(event.newValue || '{}') || {}; showReview(); updateSelected(); updateReviewCount(); } catch { /* Keep this tab’s current notes if another tab has invalid data. */ }
  });
  load();
})();
