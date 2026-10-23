'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const create = (tag, className, value) => {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (value !== undefined && value !== null) el.textContent = String(value);
    return el;
  };
  const sectionInfo = {
    'first-listen': ['First listen', 'The two mixed rooms.'],
    presets: ['Room portraits', 'The prototype’s voiced configurations.'],
    mechanisms: ['What makes the room answer?', 'Compare each mechanism with its reference render.'],
    stems: ['Inside the quartet', 'Hear the sources and room components separately.'],
    materials: ['Surface materials', 'Listen for the color of each boundary.'],
    registers: ['Pitch & strike', 'Hear how the quartet behaves across registers and velocities.'],
    extremes: ['At the edges', 'Diagnostic renders at the extremes of the prototype.'],
    rooms: ['The room materials', 'Keep the strike. Change what surrounds it.'],
    placement: ['Source placement', 'Listen to the relationship between a source and its surfaces.'],
    returns: ['Energy returning to the instruments', 'Listen for the effect on the instrument’s decay.'],
    sources: ['Inside the quartet', 'Hear the sources separately.'],
    controls: ['The controls', 'Change one parameter at a time.'],
    notes: ['Notes & velocity', 'Hear how the quartet behaves across pitch and strike energy.'],
    timpani: ['Previous / revised timpani', 'The same room, note and strike. Quartet and dry membrane solos share the revised quartet’s gain.'],
  };
  const storageKey = 'snipsnap-chamber-listening-notes-v1';
  let manifest, clips = [], firstIds = new Set(), mode = 'matched', notes = {}, storageAvailable = true;
  let context, source, buffer, currentClip, currentUrl, offset = 0, startedAt = 0;
  let status = 'idle', repeating = false, requestId = 0, frame = 0;
  const buffers = new Map();
  const cards = new Map();

  function text(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }
  function fileUrl(path) {
    if (typeof path !== 'string' || !path.trim()) return null;
    try {
      const url = new URL(path, location.href);
      return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch { return null; }
  }
  function soundPath(clip) { return fileUrl(mode === 'raw' ? (clip.raw || clip.matched) : (clip.matched || clip.raw)); }
  function roomOf(clip) { return /aquarium/i.test([clip.voice, clip.title, clip.id, ...(clip.tags || [])].join(' ')) ? 'AQUARIUM' : /hybrid/i.test([clip.voice, clip.title, clip.id, ...(clip.tags || [])].join(' ')) ? 'HYBRID' : ''; }
  function noteName(midi) {
    if (!Number.isFinite(Number(midi))) return '';
    const n = Math.round(Number(midi));
    return ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((n % 12) + 12) % 12] + (Math.floor(n / 12) - 1);
  }
  function time(seconds) {
    if (!Number.isFinite(seconds)) return '—';
    const rounded = Math.max(0, Math.floor(seconds));
    return Math.floor(rounded / 60) + ':' + String(rounded % 60).padStart(2, '0');
  }
  function durationOf(clip) {
    return Number(clip.durationSeconds ?? clip.duration ?? clip.metrics?.durationSeconds ?? clip.metrics?.durationSec ?? clip.metrics?.duration) || null;
  }
  function report(message) {
    const banner = $('error-banner');
    banner.replaceChildren(create('span', '', message));
    const dismiss = create('button', '', 'Dismiss');
    dismiss.type = 'button';
    dismiss.addEventListener('click', () => { banner.hidden = true; });
    banner.append(dismiss);
    banner.hidden = false;
    $('audio-status').textContent = message;
  }

  function loadNotes() {
    try {
      const parsed = JSON.parse(localStorage.getItem(storageKey) || '{}');
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) notes = parsed;
      localStorage.setItem(storageKey, JSON.stringify(notes));
    } catch { storageAvailable = false; $('storage-notice').hidden = false; }
    updateNoteCount();
  }
  function saveNote(clip, note, rating) {
    if (!note.trim() && !rating) delete notes[clip.id];
    else notes[clip.id] = { title: clip.title, model: clip.model ?? manifest?.model, sourceRevision: clip.sourceRevision ?? manifest?.sourceRevision, note, rating: rating ? Number(rating) : null, updatedAt: new Date().toISOString() };
    try { localStorage.setItem(storageKey, JSON.stringify(notes)); }
    catch { storageAvailable = false; $('storage-notice').hidden = false; }
    updateNoteCount();
  }
  function updateNoteCount() {
    const count = Object.keys(notes).length;
    $('note-count').textContent = count ? count + ' clip' + (count === 1 ? '' : 's') + ' annotated' + (storageAvailable ? ' in this browser' : ' in this session') : 'No notes saved yet';
  }
  function exportNotes() {
    const data = { audition: manifest?.title || 'CHAMBER / SnipSnap', model: manifest?.model, sourceRevision: manifest?.sourceRevision, exportedAt: new Date().toISOString(), listeningMode: mode, notes };
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' }));
    const link = create('a'); link.href = url; link.download = 'chamber-listening-notes.json'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    $('audio-status').textContent = 'Listening notes exported as JSON.';
  }

  function buildCard(clip, featured = false) {
    const room = roomOf(clip);
    const card = create('article', 'clip-card' + (featured ? ' featured' : '') + (room === 'AQUARIUM' ? ' aquarium' : ''));
    card.dataset.clipId = clip.id;
    const kicker = create('div', 'clip-kicker');
    const meta = [noteName(clip.midi), clip.velocity !== undefined ? 'VEL ' + clip.velocity : '', clip.model !== undefined ? 'MODEL ' + clip.model : ''].filter(Boolean).join(' · ');
    kicker.append(create('span', '', meta || clip.variant || 'ENGINE AUDITION'));
    if (room) kicker.append(create('span', 'room-badge', room));
    else if (clip.voice) kicker.append(create('span', 'room-badge', clip.voice));
    const title = create('h3', 'clip-title', clip.title || clip.id);
    const description = create('p', 'clip-description', clip.description || '');
    card.append(kicker, title, description);
    if (clip.question) {
      const question = create('p', 'clip-question');
      question.append(create('b', '', 'Listen for'), document.createTextNode(clip.question));
      card.append(question);
    }
    const playback = create('div', 'clip-playback');
    const play = create('button', 'play-button'); play.type = 'button'; play.dataset.action = 'play';
    play.append(create('span', 'play-icon', '▶'), create('span', 'play-label', 'Listen'));
    play.setAttribute('aria-label', 'Listen to ' + (clip.title || clip.id));
    play.addEventListener('click', () => toggleClip(clip));
    const repeat = create('button', 'repeat-button', 'Repeat clip'); repeat.type = 'button'; repeat.dataset.action = 'repeat';
    repeat.setAttribute('aria-pressed', 'false'); repeat.setAttribute('aria-label', 'Repeat complete clip: ' + (clip.title || clip.id));
    repeat.title = 'Repeat the complete finite strike recording. This is not a sustained HOLD sound.';
    repeat.addEventListener('click', () => repeatClip(clip));
    const duration = create('span', 'clip-duration', durationOf(clip) ? time(durationOf(clip)) : 'WAV'); duration.dataset.duration = '';
    playback.append(play, repeat, duration); card.append(playback);
    const bottom = create('div', 'clip-bottom');
    const localLabel = create('span', '', 'Rendered audio');
    const downloads = create('div', 'clip-downloads');
    const wav = create('a', '', 'WAV ↓'); wav.download = ''; wav.dataset.download = 'wav';
    const path = soundPath(clip); if (path) wav.href = path; else wav.hidden = true;
    downloads.append(wav);
    if (fileUrl(clip.recipe)) { const recipe = create('a', '', 'Recipe ↓'); recipe.href = fileUrl(clip.recipe); recipe.download = ''; downloads.append(recipe); }
    bottom.append(localLabel, downloads); card.append(bottom);
    const details = create('details', 'clip-details');
    const saved = notes[clip.id] || {};
    details.append(create('summary', '', saved.note || saved.rating ? 'Listening notes · saved locally' : 'Add listening notes'));
    const form = create('div', 'note-form');
    const noteId = 'note-' + clip.id.replace(/[^a-z0-9_-]/gi, '-');
    const label = create('label', '', 'What do you hear?'); label.htmlFor = noteId;
    const textarea = create('textarea'); textarea.id = noteId; textarea.placeholder = 'Attack, pitch, surface color, decay…'; textarea.value = saved.note || ''; textarea.maxLength = 10000;
    const ratingRow = create('div', 'rating-row');
    const ratingLabel = create('label', '', 'Musical interest'); ratingLabel.htmlFor = 'rating-' + noteId;
    const rating = create('select'); rating.id = 'rating-' + noteId;
    [['', 'Not rated'], ['1', '1 — Low'], ['2', '2'], ['3', '3 — Promising'], ['4', '4'], ['5', '5 — Strong']].forEach(([value, display]) => { const option = create('option', '', display); option.value = value; rating.append(option); });
    rating.value = saved.rating ? String(saved.rating) : '';
    const save = () => { saveNote(clip, textarea.value, rating.value); details.querySelector('summary').textContent = textarea.value.trim() || rating.value ? 'Listening notes · saved locally' : 'Add listening notes'; };
    textarea.addEventListener('input', save); rating.addEventListener('change', save);
    ratingRow.append(ratingLabel, rating);
    form.append(label, textarea, ratingRow, create('p', 'note-local-label', 'Saved only in this browser. Export below to keep a copy.'));
    details.append(form);
    if (clip.metrics && typeof clip.metrics === 'object') {
      const metrics = create('div', 'metrics-list');
      Object.entries(clip.metrics).forEach(([key, value]) => {
        if (value === null || typeof value === 'object') return;
        const prettyKey = key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ');
        const display = typeof value === 'number' ? Number(value.toPrecision(5)) : value;
        metrics.append(create('span', '', prettyKey + ': ' + display));
      });
      details.append(metrics);
    }
    card.append(details); cards.set(clip.id, card); return card;
  }

  function renderFirst() {
    let selected = clips.filter((clip) => clip.section === 'first-listen');
    if (!selected.length) selected = ['HYBRID', 'AQUARIUM'].map((room) => clips.find((clip) => roomOf(clip) === room)).filter(Boolean);
    if (!selected.length) selected = clips.slice(0, 2);
    selected = selected.slice(0, 2); firstIds = new Set(selected.map((clip) => clip.id));
    $('first-clips').replaceChildren(...selected.map((clip) => buildCard(clip, true)));
    if (!selected.length) $('first-clips').append(create('p', 'loading-copy', 'No rendered clips are available in this manifest.'));
  }
  function sectionTitle(section) { return sectionInfo[section]?.[0] || section.replace(/[-_]/g, ' ').replace(/^./, (c) => c.toUpperCase()); }
  function renderComparisons() {
    const selected = $('section-filter').value;
    const query = $('clip-search').value.trim().toLowerCase();
    const filtered = clips.filter((clip) => !firstIds.has(clip.id) && (selected === 'all' || (clip.section || 'other') === selected) && (!query || [clip.title, clip.voice, clip.description, clip.question, clip.variant, clip.midi, noteName(clip.midi), ...(clip.tags || [])].join(' ').toLowerCase().includes(query)));
    const groups = new Map();
    filtered.forEach((clip) => { const section = clip.section || 'other'; if (!groups.has(section)) groups.set(section, []); groups.get(section).push(clip); });
    const container = $('comparison-clips');
    container.querySelectorAll('[data-clip-id]').forEach((card) => cards.delete(card.dataset.clipId));
    container.replaceChildren();
    groups.forEach((groupClips, section) => {
      const advanced = /source|stem|control|note|register|extreme|diagnostic|stress|grid|sweep/i.test(section) || groupClips.length > 14;
      const group = create(advanced ? 'details' : 'section', 'study-group' + (advanced ? ' advanced-group' : ''));
      if (advanced && (selected !== 'all' || query)) group.open = true;
      const heading = create(advanced ? 'summary' : 'div', 'study-heading');
      heading.append(create(advanced ? 'span' : 'h3', 'study-name', sectionTitle(section)), create('span', 'study-description', sectionInfo[section]?.[1] || groupClips.length + ' listening examples'));
      if (advanced) heading.append(create('span', 'expand-label', 'Open study +'));
      const list = create('div', 'study-clips'); groupClips.forEach((clip) => list.append(buildCard(clip)));
      group.append(heading, list); container.append(group);
    });
    $('clip-count').textContent = filtered.length + ' sound' + (filtered.length === 1 ? '' : 's');
    $('no-results').hidden = filtered.length !== 0;
    updateControls();
  }
  function renderEvidence() {
    if (manifest.summary) $('manifest-summary').textContent = text(manifest.summary);
    if (manifest.auditionRevision === 'timpani') {
      $('hero-eyebrow').textContent = 'TIMPANI REVISION';
      $('hero-note').textContent = 'Listen for the membrane’s rounded body and pitched decay. Compare the previous and revised quartet, then hear the timpani source alone at its actual mix level.';
      $('first-title').replaceChildren(document.createTextNode('The timpani.'), create('br'), document.createTextNode('In the quartet.'));
      $('first-intro').textContent = 'Start with the revised C3 quartet in each room. Then compare previous and revised strikes at C3 and C4 below. The room, controls and note stay fixed within each group.';
      $('comparison-intro').textContent = 'Previous and revised full quartets, followed by their dry timpani sources. Each room and note uses one gain from the revised quartet.';
    }
    if (manifest.auditionRevision === 'timpani-boost') {
      const lift = Number(manifest.membraneMixLiftDb);
      const liftText = Number.isFinite(lift) ? String(lift) : '3';
      $('hero-eyebrow').textContent = 'TIMPANI MIX LIFT';
      $('hero-note').textContent = `The same timpani sound, with ${liftText} dB more presence in the quartet. Compare the accepted and boosted mix, then hear the membrane source at the same playback gain.`;
      $('first-title').replaceChildren(document.createTextNode('The timpani.'), create('br'), document.createTextNode(`Lifted ${liftText} dB.`));
      $('first-intro').textContent = 'Start with the boosted C3 quartet in each room. The membrane voicing and decay stay the same. Compare the previous and boosted mixes at C3 and C4 below.';
      $('comparison-intro').textContent = 'Accepted model 2 and boosted model 3 quartets, followed by their dry timpani sources. Each room and note shares one gain from the boosted quartet.';
    }
    const previous = manifest.previousAudition;
    const previousUrl = fileUrl(previous?.url);
    if (previousUrl) {
      $('previous-audition').href = previousUrl;
      $('previous-audition').textContent = previous.title || 'Open the original audition';
      $('previous-audition').hidden = false;
    }
    $('normalization-note').textContent = text(manifest.normalizationNote || manifest.normalization?.description || 'Matched listening uses 0.03 loudness; mechanism pairs share the reference gain so room/return differences retain their true levels.');
    const models = Array.isArray(manifest.models) ? manifest.models : [];
    const engineLabel = models.length > 1 ? 'CHAMBER / models ' + models.join(' & ') : manifest.model ? 'CHAMBER / model ' + manifest.model : 'CHAMBER prototype';
    const facts = [['Engine', engineLabel], ['Renders', clips.length], ['Sample rate', manifest.sampleRate ? (Number(manifest.sampleRate) / 1000) + ' kHz' : null], ['Source revision', manifest.sourceRevision], ['Rendered', manifest.createdAt ? text(manifest.createdAt).slice(0, 10) : null]];
    $('render-facts').replaceChildren();
    facts.forEach(([key, value]) => {
      if (value === null || value === undefined || value === '') return;
      const row = create('div'); row.append(create('dt', '', key)); const dd = create('dd');
      if (key === 'Source revision' && /^[a-f\d]{7,40}$/i.test(String(value))) { const a = create('a', '', String(value).slice(0, 10) + ' ↗'); a.href = 'https://github.com/BertCalm/SnipSnap/tree/' + value; a.target = '_blank'; a.rel = 'noreferrer'; dd.append(a); }
      else dd.textContent = text(value);
      row.append(dd); $('render-facts').append(row);
    });
    const checks = manifest.checks;
    if (checks && !Array.isArray(checks) && typeof checks === 'object') {
      const summary = [];
      if (checks.rendered !== undefined && checks.requested !== undefined) summary.push(checks.rendered + ' of ' + checks.requested + ' requested clips rendered.');
      if (checks.passed === true) summary.push('All render checks passed: finite audio, bounded internal energy, and valid source timing.');
      else if (checks.passed === false) summary.push('Some checks failed. Read the render report before judging those clips.');
      const defaults = checks.defaults || checks.defaultReplays;
      if (Array.isArray(defaults) && defaults.length && defaults.every((item) => item.deterministicRaw && item.recipeRoundTrip && item.productionRecipeSampleReplay)) summary.push('Both first-listen recipes regenerate sample for sample.');
      renderList('checks-panel', 'Render checks', summary);
      const report = fileUrl(checks.report || manifest.downloads?.checks);
      if (report) { const link = create('a', 'text-link', 'Full render report ↗'); link.href = report; link.target = '_blank'; link.rel = 'noreferrer'; $('checks-panel').append(link); }
    } else renderList('checks-panel', 'Render checks', checks);
    renderList('limitations-panel', 'Prototype boundaries', manifest.limitations);
    const zip = fileUrl(manifest.downloads?.zip);
    if (zip) { $('zip-download').href = zip; $('zip-download').download = ''; $('zip-download').hidden = false; }
    const manifestLink = fileUrl(manifest.downloads?.manifest || 'manifest.json');
    if (manifestLink) $('manifest-download').href = manifestLink;
  }
  function renderList(id, title, input) {
    if (!input) return;
    let values = Array.isArray(input) ? input : typeof input === 'object' ? Object.entries(input).map(([key, value]) => key + ': ' + text(value)) : [input];
    const items = values.map((value) => typeof value === 'object' ? value.description || value.message || [value.name || value.check, value.status || value.result].filter(Boolean).join(': ') || JSON.stringify(value) : text(value)).filter(Boolean);
    if (!items.length) return;
    const list = create('ul', 'evidence-list'); items.forEach((item) => list.append(create('li', '', item)));
    $(id).replaceChildren(create('h4', 'evidence-title', title), list);
  }

  function ensureContext() {
    if (!context) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) throw new Error('This browser does not support Web Audio playback. Download the WAV files to listen.');
      context = new AudioContextClass();
    }
    return context;
  }
  function disconnectSource() {
    if (!source) return;
    source.onended = null;
    try { source.stop(); } catch { /* A completed buffer is already stopped. */ }
    source.disconnect(); source = null;
  }
  function position() {
    if (!buffer) return offset;
    const elapsed = status === 'playing' ? offset + context.currentTime - startedAt : offset;
    return repeating ? elapsed % buffer.duration : Math.min(elapsed, buffer.duration);
  }
  async function decoded(url) {
    if (!buffers.has(url)) {
      const promise = (async () => {
        const response = await fetch(url);
        if (!response.ok) throw new Error('Audio file could not be loaded (' + response.status + ').');
        return ensureContext().decodeAudioData(await response.arrayBuffer());
      })();
      buffers.set(url, promise);
      promise.catch(() => buffers.delete(url));
    }
    return buffers.get(url);
  }
  function startBuffer(at = 0) {
    disconnectSource();
    offset = Math.min(Math.max(0, at), Math.max(0, buffer.duration - .001));
    source = context.createBufferSource(); source.buffer = buffer; source.loop = repeating;
    source.loopStart = 0; source.loopEnd = buffer.duration; source.connect(context.destination);
    const thisSource = source;
    source.onended = () => {
      if (source !== thisSource) return;
      thisSource.disconnect(); source = null; status = 'idle'; offset = 0;
      updateControls(); updateProgress(); $('audio-status').textContent = (currentClip?.title || 'Clip') + ' finished.';
    };
    startedAt = context.currentTime; status = 'playing'; source.start(0, offset);
    updateControls(); tick();
    $('audio-status').textContent = 'Playing ' + currentClip.title + ', ' + mode + ' level' + (repeating ? ', repeating complete clip.' : '.');
  }
  async function playClip(clip, at = 0) {
    const id = ++requestId;
    disconnectSource(); cancelAnimationFrame(frame); currentClip = clip; status = 'loading'; offset = at; buffer = null;
    const url = soundPath(clip); currentUrl = url;
    updateDiagram(clip); updateControls();
    try {
      if (!url) throw new Error('No audio file was supplied for this clip.');
      const ctx = ensureContext(); await ctx.resume();
      const loaded = await decoded(url);
      if (id !== requestId) return;
      buffer = loaded;
      const card = cards.get(clip.id); if (card) card.querySelector('[data-duration]').textContent = time(buffer.duration);
      startBuffer(at);
    } catch (error) {
      if (id !== requestId) return;
      status = 'idle'; offset = 0; updateControls(); report(error.message || 'Playback failed. Download the WAV file to listen.');
    }
  }
  function pause() {
    if (status === 'loading') { ++requestId; status = 'idle'; disconnectSource(); updateControls(); return; }
    if (status !== 'playing') return;
    offset = position(); disconnectSource(); status = 'paused'; cancelAnimationFrame(frame); updateControls(); updateProgress();
    $('audio-status').textContent = 'Paused ' + currentClip.title + '.';
  }
  async function resume() {
    if (!currentClip) return;
    if (status === 'paused' && buffer && currentUrl === soundPath(currentClip)) {
      const id = ++requestId;
      try { await ensureContext().resume(); if (id === requestId) startBuffer(offset); } catch (error) { report(error.message); }
    } else await playClip(currentClip);
  }
  function toggleClip(clip) {
    if (currentClip?.id === clip.id) {
      if (status === 'playing' || status === 'loading') pause();
      else resume();
    } else playClip(clip);
  }
  function stop() {
    ++requestId; disconnectSource(); cancelAnimationFrame(frame); status = 'idle'; offset = 0;
    updateControls(); updateProgress(); $('audio-status').textContent = 'Playback stopped.';
  }
  function setRepeat(value) {
    const at = position(); repeating = value;
    if (source && status === 'playing') { offset = at; startedAt = context.currentTime; source.loop = repeating; }
    updateControls();
  }
  function repeatClip(clip) {
    if (currentClip?.id === clip.id) { setRepeat(!repeating); if (status === 'idle') playClip(clip); }
    else { repeating = true; playClip(clip); }
  }
  function updateDiagram(clip) {
    const room = roomOf(clip);
    if (!room) return;
    $('diagram-room-name').textContent = room;
    const figure = document.querySelector('.room-figure'); figure.classList.toggle('aquarium', room === 'AQUARIUM');
    const labels = figure.querySelectorAll('.surface-label');
    labels[0].textContent = room === 'AQUARIUM' ? 'CONCRETE / LOWER ROOM' : 'WOOD / BODY';
    labels[1].textContent = room === 'AQUARIUM' ? 'SUBMERGED GLASS / CANOPY' : 'GLASS / BRIGHTNESS';
  }
  function updateControls() {
    const active = currentClip?.id;
    cards.forEach((card, id) => {
      const selected = id === active;
      card.classList.toggle('is-active', selected);
      const play = card.querySelector('[data-action=play]');
      play.querySelector('.play-icon').textContent = selected && status === 'playing' ? 'Ⅱ' : selected && status === 'loading' ? '…' : '▶';
      play.querySelector('.play-label').textContent = selected && status === 'playing' ? 'Pause' : selected && status === 'loading' ? 'Loading' : selected && status === 'paused' ? 'Resume' : 'Listen';
      play.setAttribute('aria-label', (selected && status === 'playing' ? 'Pause ' : selected && status === 'paused' ? 'Resume ' : 'Listen to ') + (clips.find((clip) => clip.id === id)?.title || id));
      card.querySelector('[data-action=repeat]').setAttribute('aria-pressed', String(selected && repeating));
      const link = card.querySelector('[data-download=wav]');
      const clip = clips.find((c) => c.id === id); const url = clip ? soundPath(clip) : null;
      if (url) { link.href = url; link.hidden = false; }
      link.setAttribute('aria-label', 'Download ' + mode + ' WAV');
    });
    $('transport').hidden = !currentClip; document.body.classList.toggle('has-transport', Boolean(currentClip));
    if (!currentClip) return;
    $('transport-title').textContent = currentClip.title + ' · ' + (mode === 'matched' ? 'Matched level' : 'Raw level') + (status === 'loading' ? ' · Loading…' : '');
    $('transport-play').textContent = status === 'playing' ? 'Ⅱ' : status === 'loading' ? '…' : '▶';
    $('transport-play').setAttribute('aria-label', status === 'playing' ? 'Pause current clip' : status === 'paused' ? 'Resume current clip' : 'Play current clip');
    $('transport-repeat').setAttribute('aria-pressed', String(repeating));
    $('transport-repeat').textContent = 'Repeat clip';
    $('seek').disabled = status === 'loading' || !buffer;
    updateProgress();
  }
  function updateProgress() {
    const duration = buffer && currentUrl === soundPath(currentClip || {}) ? buffer.duration : durationOf(currentClip || {}) || 0;
    const at = status === 'loading' ? 0 : position();
    $('elapsed').textContent = time(at); $('duration').textContent = time(duration);
    $('seek').value = duration ? Math.round(at / duration * 1000) : 0;
    $('seek').setAttribute('aria-valuetext', time(at) + ' of ' + time(duration));
  }
  function tick() { cancelAnimationFrame(frame); updateProgress(); if (status === 'playing') frame = requestAnimationFrame(tick); }
  function setMode(next) {
    if (next === mode) return;
    const wasPlaying = status === 'playing' || status === 'loading';
    const at = position(); mode = next;
    document.querySelectorAll('.mode-button').forEach((button) => { button.classList.toggle('active', button.dataset.mode === mode); button.setAttribute('aria-pressed', String(button.dataset.mode === mode)); });
    if (currentClip && wasPlaying) playClip(currentClip, at);
    else if (currentClip && status === 'paused') { ++requestId; disconnectSource(); status = 'idle'; offset = 0; buffer = null; updateControls(); }
    else updateControls();
    $('audio-status').textContent = (mode === 'matched' ? 'Matched' : 'Raw') + ' listening level selected.';
  }

  document.querySelectorAll('.mode-button').forEach((button) => button.addEventListener('click', () => setMode(button.dataset.mode)));
  $('section-filter').addEventListener('change', renderComparisons);
  $('clip-search').addEventListener('input', renderComparisons);
  $('export-notes').addEventListener('click', exportNotes);
  $('transport-play').addEventListener('click', () => status === 'playing' || status === 'loading' ? pause() : resume());
  $('transport-stop').addEventListener('click', stop);
  $('transport-repeat').addEventListener('click', () => setRepeat(!repeating));
  $('transport-repeat').title = 'Repeat the complete finite strike recording, not a sustained HOLD sound.';
  $('seek').addEventListener('input', () => {
    if (!buffer || status === 'loading') return;
    const at = Number($('seek').value) / 1000 * buffer.duration;
    if (status === 'playing') startBuffer(at); else { offset = at; status = 'paused'; updateProgress(); }
  });
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape') stop(); });
  window.addEventListener('pagehide', stop);
  loadNotes();
  fetch('manifest.json').then((response) => {
    if (!response.ok) throw new Error('The render manifest could not be loaded (' + response.status + ').');
    return response.json();
  }).then((data) => {
    manifest = data;
    if (!Array.isArray(data.clips)) throw new Error('The render manifest does not contain a clips list.');
    const ids = new Set();
    clips = data.clips.filter((clip) => clip && typeof clip.id === 'string' && !ids.has(clip.id) && ids.add(clip.id));
    if (data.title) document.title = data.title + ' — SnipSnap listening room';
    [...new Set(clips.map((clip) => clip.section || 'other'))].filter((section) => section !== 'first-listen').forEach((section) => {
      const option = create('option', '', sectionTitle(section)); option.value = section; $('section-filter').append(option);
    });
    renderFirst(); renderComparisons(); renderEvidence();
  }).catch((error) => {
    $('first-clips').replaceChildren(create('p', 'loading-copy', 'The listening room could not load its audio list.'));
    $('comparison-clips').replaceChildren(); report(error.message);
  });
})();
