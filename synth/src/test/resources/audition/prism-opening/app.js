(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const STORE = 'prism-opening-review-v1';
  const STAGES = ['body', 'previous', 'destination', 'source'];
  const PRIMARY_STAGES = ['body', 'previous', 'destination'];
  const STAGE_LABELS = { body: 'Body opening', previous: 'Previous opening', destination: 'Destination', source: 'Original source' };
  const state = { manifest: null, clips: [], families: [], categories: [], family: '', current: null, level: 'matched', reviews: {}, storage: true };
  let noticeTimer;
  let loadCounter = 0;
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || '{}');
    state.reviews = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
  } catch { state.storage = false; }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function human(value) { return String(value || '').replace(/[_-]+/g, ' ').replace(/\b\w/g, char => char.toUpperCase()); }
  function familyClips(family) { return state.clips.filter(clip => clip.family === family); }
  function sourceFor(family) { const clips = familyClips(family); return clips.find(clip => clip.variant === 'source') || clips[0]; }
  function familyName(family) { const clip = sourceFor(family); return clip?.sourceName || clip?.familyTitle || human(family); }
  function categoryName(category) { return state.clips.find(clip => clip.category === category)?.categoryTitle || human(category); }
  function time(value) {
    const seconds = Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : 0;
    return `${Math.floor(seconds / 60)}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`;
  }
  function noteName(value) {
    if (value === null || value === undefined || value === '') return '—';
    const midi = Number(value);
    if (!Number.isFinite(midi)) return String(value);
    return ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((midi % 12) + 12) % 12] + (Math.floor(midi / 12) - 1);
  }
  function seconds(clip) { return Number.isFinite(Number(clip.seconds)) ? `${Number(clip.seconds).toFixed(1)} s` : ''; }
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
    persist(); updateSelected();
  }
  function showReview() {
    const clip = state.current;
    const review = state.reviews[clip?.id] || {};
    const prompts = {
      body: ['Does the body lead the note?', 'How does this opening compare?', 'Body, warmth, edge, the arrival of colour… What changes?'],
      previous: ['Which opening works better?', 'What does the earlier opening keep or lose?', 'Compare the earlier start with the body opening. Which beginning feels right?'],
      destination: ['Does this colour work?', 'Is this a useful destination for the note?', 'Judge the final colour on its own. Does the opening make its arrival worthwhile?'],
      source: ['What should survive?', 'Which part of the original character matters?', 'The source is a reference. What should the PRISM version carry forward?']
    };
    const prompt = prompts[clip?.variant] || prompts.body;
    $('review-title').textContent = prompt[0]; $('note-prompt').textContent = prompt[1]; $('note').placeholder = prompt[2];
    $('review-clip').textContent = clip ? `${familyName(clip.family)} · ${STAGE_LABELS[clip.variant] || human(clip.variant)}` : 'Select a render.';
    $('note').value = review.note || '';
    document.querySelectorAll('[data-verdict]').forEach(button => { button.setAttribute('aria-pressed', String(button.dataset.verdict === review.verdict)); button.disabled = !clip; });
    $('note').disabled = !clip;
    $('save-status').textContent = state.storage ? 'Notes stay in this browser. Export a copy to keep them.' : 'Browser storage is unavailable. Export notes before leaving.';
    renderDetails();
  }
  function renderDetails() {
    const target = $('render-details'); target.replaceChildren();
    const clip = state.current; if (!clip) return;
    const rows = [['Source preset', familyName(clip.family)], ['Source engine', clip.sourceEngine || sourceFor(clip.family)?.sourceEngine], ['Stage', STAGE_LABELS[clip.variant] || human(clip.variant)], ['Rendered note', noteName(clip.midi)], ['Length', seconds(clip)], ['Playback', state.level === 'matched' ? 'Matched listening level' : 'Native engine level']];
    const source = sourceFor(clip.family);
    if (source && clip.variant !== 'source' && String(source.midi) !== String(clip.midi)) rows.push(['Original source note', noteName(source.midi)]);
    for (const [key, label] of [['rawPeak', 'Native peak'], ['rawRms', 'Native RMS'], ['nominalHz', 'Root Hz'], ['pitchHz', 'Pitch estimate Hz']]) {
      if (Number.isFinite(clip.metrics?.[key])) rows.push([label, Number(clip.metrics[key]).toPrecision(4)]);
    }
    if (['body', 'previous'].includes(clip.variant)) rows.push(['Spectral transition', `${clip.motionSeconds ?? .8} s`]);
    for (const [label, value] of rows) if (value) target.append(el('dt', '', label), el('dd', '', value));
  }
  function guideFor(family) {
    const source = sourceFor(family);
    const guides = {
      'fork-soft-keys': 'Listen for a source-led low body at the beginning, then the arrival of upper colour. Compare it with the earlier opening into the same destination.',
      'tessera-paired-wire': 'Hear the lower-harmonic balance first, then the string-derived upper colour. Does this beginning give the transition a clearer sense of material?',
      'embouchure-relaxed-bow': 'Compare the body of the two openings as the bowed-source colour develops. The original bow’s noise and articulation remain a reference, not a reconstruction claim.',
      'fathom-hollow-growl': 'Follow the low body into the upper growl colour. Does the new beginning help the note develop without relying on extra buzz?'
    };
    return guides[family] || 'Compare the body opening with the previous opening. Both travel to the same spectral destination over 0.8 seconds.';
  }
  function renderCategories() {
    const target = $('category-tabs'); target.replaceChildren();
    const selectedCategory = sourceFor(state.family)?.category;
    state.categories.forEach((category, index) => {
      const button = el('button', '', categoryName(category)); button.type = 'button'; button.dataset.category = category;
      const number = el('span', '', String(index + 1).padStart(2, '0')); button.prepend(number);
      button.setAttribute('aria-pressed', String(category === selectedCategory));
      button.addEventListener('click', () => { const body = state.clips.find(clip => clip.category === category && clip.variant === 'body'); if (body) selectClip(body, false); });
      target.append(button);
    });
  }
  function setFamily(family) {
    state.family = family; $('family-select').value = family;
    const source = sourceFor(family);
    $('source-engine').textContent = source?.sourceEngine || '';
    $('category-label').textContent = `${String(state.categories.indexOf(source?.category) + 1).padStart(2, '0')} / ${categoryName(source?.category || '').toUpperCase()}`;
    $('family-guide').textContent = guideFor(family);
    $('family-count').textContent = `${String(state.families.indexOf(family) + 1).padStart(2, '0')} / ${String(state.families.length).padStart(2, '0')} SOURCES`;
    const reference = sourceFor(family);
    $('original-source').disabled = !reference || reference.variant !== 'source';
    $('original-source').setAttribute('aria-label', `Play original ${reference?.sourceEngine || 'source'} render for ${familyName(family)}`);
    renderCategories(); renderCompare();
  }
  function renderCompare() {
    const clips = familyClips(state.family);
    const target = $('compare-cards'); target.replaceChildren();
    const hints = { body: 'Lower body → upper colour · 0.8 s', previous: 'Earlier PRISM start · same 0.8 s', destination: 'Shared final colour · static' };
    PRIMARY_STAGES.forEach((stage, index) => {
      const clip = clips.find(item => item.variant === stage); if (!clip) return;
      const button = el('button', `compare-card stage-${stage}`); button.type = 'button'; button.dataset.clipId = clip.id;
      button.setAttribute('aria-pressed', String(state.current?.id === clip.id));
      button.setAttribute('aria-label', `Play ${STAGE_LABELS[stage]} for ${familyName(clip.family)}`);
      button.append(el('span', 'step', `${String(index + 1).padStart(2, '0')} / ${stage === 'body' ? 'BODY FIRST' : stage === 'previous' ? 'COMPARE' : 'SAME DESTINATION'}`));
      button.append(el('strong', '', STAGE_LABELS[stage]));
      button.append(el('span', 'stage-hint', hints[stage]));
      button.append(el('span', 'clip-hint', `${noteName(clip.midi)} · ${seconds(clip)}`));
      const icon = el('span', 'card-play', '▶︎'); icon.setAttribute('aria-hidden', 'true'); button.append(icon);
      button.addEventListener('click', () => selectClip(clip, true)); target.append(button);
    });
  }
  function renderLibrary() {
    const query = $('search').value.trim().toLowerCase(), category = $('library-category').value, variant = $('library-variant').value;
    const clips = state.clips.filter(clip => (!category || clip.category === category) && (!variant || clip.variant === variant) && (!query || `${clip.title} ${clip.description} ${familyName(clip.family)} ${categoryName(clip.category)} ${clip.sourceEngine} ${STAGE_LABELS[clip.variant]} ${noteName(clip.midi)}`.toLowerCase().includes(query)));
    $('clip-count').textContent = `${clips.length} / ${state.clips.length} renders`; $('empty-state').hidden = clips.length > 0;
    const target = $('clip-list'); target.replaceChildren();
    for (const category of state.categories) {
      const members = clips.filter(clip => clip.category === category); if (!members.length) continue;
      const categorySection = el('section', 'palette-group');
      categorySection.append(el('h3', 'palette-category', categoryName(category)));
      for (const family of state.families) {
        const familyMembers = members.filter(clip => clip.family === family); if (!familyMembers.length) continue;
        familyMembers.sort((a, b) => STAGES.indexOf(a.variant) - STAGES.indexOf(b.variant));
        const section = el('section', 'library-group');
        const heading = el('h4', 'family-shelf-title', familyName(family)); heading.append(el('span', '', sourceFor(family)?.sourceEngine || '')); section.append(heading);
        for (const clip of familyMembers) {
          const row = el('article', 'clip-row'); row.dataset.rowId = clip.id;
          const playButton = el('button', 'row-play', '▶︎'); playButton.type = 'button'; playButton.dataset.clipId = clip.id;
          playButton.setAttribute('aria-label', `Play ${STAGE_LABELS[clip.variant] || clip.variant} for ${familyName(family)}`); playButton.setAttribute('aria-pressed', 'false');
          playButton.addEventListener('click', () => selectClip(clip, true));
          const copy = el('div', 'row-copy'), title = el('div', 'row-title', STAGE_LABELS[clip.variant] || clip.title);
          const stamp = el('span', 'note-dot'); stamp.dataset.reviewId = clip.id; title.append(stamp); copy.append(title);
          if (clip.description) copy.append(el('p', 'row-description', clip.description));
          const engine = el('span', 'row-variant', clip.variant === 'source' ? clip.sourceEngine || 'Source' : 'PRISM');
          const duration = el('span', 'row-time', `${noteName(clip.midi)} · ${seconds(clip)}`), downloads = el('div', 'row-links');
          const wav = el('a', '', 'WAV ↓'); wav.download = ''; wav.dataset.downloadId = clip.id; link(wav, clip[state.level] || clip.raw || clip.matched);
          const recipe = el('a', '', 'JSON ↓'); recipe.download = ''; link(recipe, clip.recipe);
          if (wav.hasAttribute('href')) downloads.append(wav); if (recipe.hasAttribute('href')) downloads.append(recipe);
          row.append(playButton, copy, engine, duration, downloads); section.append(row);
        }
        categorySection.append(section);
      }
      target.append(categorySection);
    }
    updateSelected();
  }
  function updateSelected() {
    $('original-source').setAttribute('aria-pressed', String(state.current?.variant === 'source' && state.current.family === state.family));
    document.querySelectorAll('[data-clip-id]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.clipId === state.current?.id)));
    document.querySelectorAll('[data-row-id]').forEach(row => row.classList.toggle('is-selected', row.dataset.rowId === state.current?.id));
    document.querySelectorAll('[data-review-id]').forEach(stamp => { const review = state.reviews[stamp.dataset.reviewId]; stamp.textContent = review?.verdict ? review.verdict.toUpperCase() : review?.note ? 'NOTED' : ''; });
    document.querySelectorAll('[data-verdict]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.verdict === state.reviews[state.current?.id]?.verdict)));
  }
  function selectClip(clip, autoplay) {
    audio.pause(); state.current = clip;
    if (state.family !== clip.family) setFamily(clip.family); else renderCompare();
    $('player-family').textContent = `${familyName(clip.family).toUpperCase()} / ${(STAGE_LABELS[clip.variant] || human(clip.variant)).toUpperCase()}`;
    $('player-title').textContent = clip.variant === 'source' ? `${clip.sourceEngine || 'Source'} · ${familyName(clip.family)}` : `${STAGE_LABELS[clip.variant] || clip.title} · ${familyName(clip.family)}`;
    $('duration').textContent = time(clip.seconds); $('current-time').textContent = '0:00'; $('seek').value = '0'; $('seek').max = String(Number(clip.seconds) || 1);
    const file = fileFor(clip); if (file) { audio.src = file; audio.load(); } else audio.removeAttribute('src');
    link($('download-wav'), clip[state.level] || clip.raw || clip.matched); link($('download-recipe'), clip.recipe);
    $('player-dock').hidden = false;
    $('audio-status').textContent = state.level === 'matched' ? 'Matched for comparison' : clip.variant === 'source' ? 'Native source-engine output' : 'Native PRISM output';
    showReview(); updateSelected(); if (autoplay) play();
  }
  async function play() {
    if (!state.current || !audio.getAttribute('src')) return;
    try { await audio.play(); }
    catch (error) { if (error.name !== 'AbortError') $('audio-status').textContent = 'Playback unavailable. Try the browser player or download the WAV.'; }
  }
  function updateTransport() {
    const playing = !audio.paused && !audio.ended;
    $('play-icon').textContent = playing ? 'Ⅱ' : '▶︎'; $('play').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} selected render`); $('play').setAttribute('aria-pressed', String(playing));
  }
  function stop() { audio.pause(); if (audio.readyState > 0) audio.currentTime = 0; updateTransport(); }
  function setLevel(level) {
    if (state.level === level) return;
    const wasPlaying = !audio.paused; state.level = level;
    $('level-matched').setAttribute('aria-pressed', String(level === 'matched')); $('level-raw').setAttribute('aria-pressed', String(level === 'raw'));
    if (state.current) selectClip(state.current, wasPlaying);
    document.querySelectorAll('[data-download-id]').forEach(anchor => { const clip = state.clips.find(item => item.id === anchor.dataset.downloadId); if (clip) link(anchor, clip[level] || clip.raw || clip.matched); });
  }
  async function load() {
    const call = ++loadCounter; $('load-status').hidden = false; $('load-error').hidden = true;
    try {
      const response = await fetch('./manifest.json', { cache: 'no-cache' }); if (!response.ok) throw new Error(`Manifest request returned ${response.status}.`);
      const manifest = await response.json(); if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('The manifest does not contain any renders.');
      if (call !== loadCounter) return; state.manifest = manifest;
      const ids = new Set();
      state.clips = manifest.clips.filter(clip => clip && clip.id && !ids.has(String(clip.id)) && (clip.raw || clip.matched) && ids.add(String(clip.id))).map(clip => ({ ...clip, id: String(clip.id), family: String(clip.family || clip.sourceId || 'Source'), category: String(clip.category || 'Opening'), variant: String(clip.variant || 'body'), title: String(clip.title || human(clip.id)), description: String(clip.description || '') }));
      if (!state.clips.length) throw new Error('No playable render files are listed in the manifest.');
      state.families = [...new Set(state.clips.map(clip => clip.family))]; state.categories = [...new Set(state.clips.map(clip => clip.category))];
      if (manifest.title) document.title = `${manifest.title} · SnipSnap`;
      $('study-size').textContent = `${state.families.length} SOURCES / ONE OPENING CHANGE`;
      $('family-select').replaceChildren(); $('library-category').replaceChildren(new Option('All directions', ''));
      for (const category of state.categories) {
        const group = document.createElement('optgroup'); group.label = categoryName(category);
        for (const family of state.families.filter(family => sourceFor(family)?.category === category)) group.append(new Option(familyName(family), family));
        $('family-select').append(group); $('library-category').append(new Option(categoryName(category), category));
      }
      $('library-variant').replaceChildren(new Option('All stages', ''));
      for (const variant of STAGES) if (state.clips.some(clip => clip.variant === variant)) $('library-variant').append(new Option(STAGE_LABELS[variant], variant));
      $('study').hidden = false; $('load-status').hidden = true;
      const initial = state.clips.find(clip => clip.variant === 'body') || state.clips[0];
      setFamily(initial.family); renderLibrary(); selectClip(initial, false); updateReviewCount();
    } catch (error) {
      if (call !== loadCounter) return;
      $('load-status').hidden = true; $('load-error').hidden = false; $('load-error-detail').textContent = `${error.message} Retry, or inspect the render manifest.`;
    }
  }
  $('family-select').addEventListener('change', event => { const body = familyClips(event.target.value).find(clip => clip.variant === 'body'); if (body) selectClip(body, false); });
  $('original-source').addEventListener('click', () => { const source = sourceFor(state.family); if (source?.variant === 'source') selectClip(source, true); });
  $('retry').addEventListener('click', load); $('search').addEventListener('input', renderLibrary);
  $('library-category').addEventListener('change', renderLibrary); $('library-variant').addEventListener('change', renderLibrary);
  $('reset-filters').addEventListener('click', () => { $('search').value = ''; $('library-category').value = ''; $('library-variant').value = ''; renderLibrary(); });
  $('note').addEventListener('input', event => saveReview({ note: event.target.value }));
  document.querySelectorAll('[data-verdict]').forEach(button => button.addEventListener('click', () => { const previous = state.reviews[state.current?.id]?.verdict; saveReview({ verdict: previous === button.dataset.verdict ? '' : button.dataset.verdict }); }));
  $('play').addEventListener('click', () => audio.paused ? play() : audio.pause()); $('stop').addEventListener('click', stop);
  $('level-matched').addEventListener('click', () => setLevel('matched')); $('level-raw').addEventListener('click', () => setLevel('raw'));
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
      const clip = state.clips.find(item => item.id === id), source = clip ? sourceFor(clip.family) : null;
      return { id, title: clip?.title || id, family: clip?.family, category: clip?.category, stage: clip?.variant, sourceName: clip?.sourceName || source?.sourceName, sourceEngine: clip?.sourceEngine || source?.sourceEngine, sourceMidi: source?.midi, renderedMidi: clip?.midi, ...review };
    });
    const payload = { study: state.manifest?.title || 'PRISM body opening', reviewNamespace: STORE, exportedAt: new Date().toISOString(), formatVersion: 1, reviews };
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `prism-opening-notes-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    notify(`${reviews.length} listening ${reviews.length === 1 ? 'note' : 'notes'} exported.`);
  });
  window.addEventListener('storage', event => {
    if (event.key !== STORE) return;
    try { const reviews = JSON.parse(event.newValue || '{}'); if (reviews && typeof reviews === 'object' && !Array.isArray(reviews)) { state.reviews = reviews; showReview(); updateSelected(); updateReviewCount(); } } catch { /* Keep this tab’s notes if another tab has invalid data. */ }
  });
  load();
})();
