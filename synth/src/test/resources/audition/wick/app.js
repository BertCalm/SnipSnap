'use strict';

(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const sections = ['start', 'mechanism', 'controls', 'held', 'range'];
  const sectionLabels = { start: 'Start here', mechanism: 'Mechanism', controls: 'Controls', held: 'Held loops', range: 'Range & recipes' };
  const audio = new Audio();
  audio.preload = 'none';
  const state = { manifest: null, clips: new Map(), groups: [], section: 'start', level: 'matched', current: null, sequence: [], sequenceIndex: -1, seeking: false, repeat: false };
  const notesKey = 'snipsnap-wick-listening-notes-v1';
  let notes = {};
  let noticeTimer;
  let cardCounter = 0;
  let loadToken = 0;
  let audioContext;
  const heldCache = new Map();
  let held = null;
  try { notes = JSON.parse(localStorage.getItem(notesKey) || '{}') || {}; } catch (_) { /* Local notes can be unavailable in a private browser. */ }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function link(label, href, className = '') {
    const node = el('a', className, label);
    node.href = safePath(href);
    node.download = '';
    return node;
  }
  function safePath(path) {
    if (typeof path !== 'string' || !path || /^(?:javascript|data|vbscript):/i.test(path)) return '#';
    try {
      const parsed = new URL(path, location.href);
      return parsed.origin === location.origin ? path : '#';
    } catch (_) { return '#'; }
  }
  function finite(value) { return typeof value === 'number' && Number.isFinite(value); }
  function numeric(value, digits = 3) { return finite(value) ? value.toFixed(digits).replace(/\.?0+$/, '') || '0' : String(value); }
  function time(value) {
    if (!finite(value) || value < 0) return '0:00';
    return `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}`;
  }
  function noteName(midi) {
    if (!finite(midi)) return null;
    const names = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
    return `${names[((midi % 12) + 12) % 12]}${Math.floor(midi / 12) - 1}`;
  }
  function tell(message) {
    const notice = $('#notice');
    notice.textContent = message;
    notice.hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { notice.hidden = true; }, 5500);
  }
  function isPlaying() { return held ? !held.paused : !audio.paused && !audio.ended; }
  function playbackDuration() { return held ? held.buffer.duration : audio.duration; }
  function playbackTime() {
    if (!held) return audio.currentTime;
    const position = held.paused ? held.offset : held.offset + audioContext.currentTime - held.startedAt;
    return held.loop ? position % held.buffer.duration : Math.min(position, held.buffer.duration);
  }
  function stopHeld() {
    if (!held) return;
    if (held.source) { held.source.onended = null; try { held.source.stop(); } catch (_) {} held.source.disconnect(); }
    held = null;
  }
  function startHeldSource(offset = 0) {
    if (!held) return;
    if (held.source) { held.source.onended = null; try { held.source.stop(); } catch (_) {} held.source.disconnect(); }
    const source = audioContext.createBufferSource();
    source.buffer = held.buffer;
    source.loop = Boolean(state.repeat && state.current?.loop && !state.sequence.length);
    source.connect(audioContext.destination);
    held.source = source;
    held.offset = Math.max(0, Math.min(offset, held.buffer.duration - .0001));
    held.startedAt = audioContext.currentTime;
    held.paused = false;
    held.loop = source.loop;
    source.onended = () => { if (!held || held.source !== source) return; held.paused = true; held.offset = held.buffer.duration; onPlaybackEnded(); };
    source.start(0, held.offset);
    updateCards();
  }
  function pausePlayback() {
    if (!held) { audio.pause(); return; }
    held.offset = playbackTime();
    held.paused = true;
    if (held.source) { held.source.onended = null; try { held.source.stop(); } catch (_) {} held.source.disconnect(); held.source = null; }
    updateCards();
  }
  function resumePlayback() {
    if (!held) return audio.play().catch(() => tell('Audio could not start. Try again.'));
    audioContext.resume().then(() => { if (held) startHeldSource(held.offset >= held.buffer.duration ? 0 : held.offset); }).catch(() => tell('Audio could not start. Try again.'));
  }
  function seekPlayback(position) {
    if (!held) { if (finite(audio.duration)) audio.currentTime = position; return; }
    if (held.paused) held.offset = position;
    else startHeldSource(position);
    updateProgress();
  }
  function updateProgress() {
    const duration = playbackDuration();
    const current = playbackTime();
    const proportion = finite(duration) && duration > 0 ? current / duration : 0;
    if (!state.seeking) $('#playhead').value = String(Math.round(proportion * 1000));
    $('#playhead').setAttribute('aria-valuetext', `${time(current)} of ${time(duration)}`);
    $('#elapsed').textContent = time(current);
    $('#duration').textContent = time(duration);
    if (state.current) $$('.clip-card').filter(card => card.dataset.clip === String(state.current.id)).forEach(card => { $('.card-progress span', card).style.width = `${proportion * 100}%`; });
  }
  function heldTick() { if (held && !held.paused) updateProgress(); requestAnimationFrame(heldTick); }
  requestAnimationFrame(heldTick);
  function clipSource(clip) {
    return safePath(state.level === 'matched' ? clip.file : (clip.nativeFile || clip.rawFile || clip.file));
  }
  function isSourceStudy(clip) {
    return /^source_(current|warm_wire|hollow_reed|rubbed_glass)_/.test(String(clip?.id || ''));
  }
  function refreshLevelControls() {
    const matchedOnly = isSourceStudy(state.current) && !state.current.nativeFile && !state.current.rawFile;
    if (matchedOnly) state.level = 'matched';
    $$('[data-level]').forEach(button => {
      button.disabled = matchedOnly && button.dataset.level === 'native';
      button.setAttribute('aria-pressed', String(button.dataset.level === state.level));
    });
    $('#level-note').textContent = matchedOnly ? 'Body comparisons use matched levels.' : state.level === 'matched' ? 'Matched files make differences easier to hear.' : !state.current || state.current.nativeFile ? 'Native files preserve the engine’s rendered level.' : 'Raw files use a shared −6 dB export gain.';
  }
  function sectionFor(group, clips) {
    const id = String(group.id || '').toLowerCase();
    const text = `${id} ${group.title || ''}`.toLowerCase();
    if (id === 'source-register' || id === 'source-phrases') return 'start';
    if (id === 'source-held') return 'held';
    if (id === 'musical-patterns' || clips.some(clip => clip.kind === 'pattern')) return 'start';
    if (id === 'bubble-tone' || id === 'breath-sustain' || id === 'flicker-register' || id === 'rattle-register' || id === 'rattle-reserve' || id === 'rattle-cohesion') return 'start';
    if (id === 'defaults' || /^(default|start|reference)/.test(id)) return 'start';
    if (/mechanism|comparison|ablation|cascade.disabled|depletion.disabled/.test(text) || clips.some(c => c.kind === 'comparison')) return 'mechanism';
    if (/held|hold/.test(id) || clips.every(c => c.kind === 'hold' || c.kind === 'held-sweep')) return 'held';
    if (/sweep/.test(id) || clips.every(c => c.kind === 'sweep')) return 'controls';
    if (clips.some(c => c.kind === 'defaults')) return 'start';
    return 'range';
  }
  function normalizeGroups(manifest, entries) {
    const groups = (Array.isArray(manifest.groups) ? manifest.groups : []).map(group => ({ ...group, clipIds: Array.isArray(group.clipIds) ? group.clipIds : [] }));
    const assigned = new Set();
    for (const group of groups) {
      if (!group.clipIds.length) group.clipIds = entries.filter(clip => clip.groupId === group.id || clip.group === group.title).map(clip => clip.id);
      group.clipIds.forEach(id => assigned.add(String(id)));
    }
    for (const clip of entries.filter(item => !assigned.has(String(item.id)))) {
      const id = clip.groupId || clip.kind || 'other';
      let group = groups.find(item => item.id === id);
      if (!group) { group = { id, title: clip.group || clip.kind || 'More sounds', description: '', clipIds: [] }; groups.push(group); }
      group.clipIds.push(clip.id);
    }
    return groups.map(group => {
      const clips = group.clipIds.map(id => state.clips.get(String(id))).filter(Boolean);
      return { ...group, clips, section: sectionFor(group, clips) };
    }).filter(group => group.clips.length);
  }
  function switchTab(name, focus = false) {
    if (!sections.includes(name)) return;
    state.section = name;
    $$('[data-tab]').forEach(button => {
      const selected = button.dataset.tab === name;
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
      if (selected && focus) button.focus();
    });
    $$('[data-panel]').forEach(panel => { panel.hidden = panel.dataset.panel !== name; });
    fillFeedback();
  }
  $$('[data-tab]').forEach(button => {
    button.addEventListener('click', () => switchTab(button.dataset.tab));
    button.addEventListener('keydown', event => {
      let index = sections.indexOf(state.section);
      if (event.key === 'ArrowRight') index = (index + 1) % sections.length;
      else if (event.key === 'ArrowLeft') index = (index + sections.length - 1) % sections.length;
      else if (event.key === 'Home') index = 0;
      else if (event.key === 'End') index = sections.length - 1;
      else return;
      event.preventDefault();
      switchTab(sections[index], true);
    });
  });

  function setCurrent(clip) {
    state.current = clip;
    refreshLevelControls();
    $('#transport').hidden = false;
    document.body.classList.add('has-player');
    $('#transport-title').textContent = clipAccessibleTitle(clip);
    $('#transport-voice').textContent = `${clip.voice || 'WICK'}${clip.loop ? ' / HOLD' : ''} / ${state.level === 'matched' ? 'MATCHED' : clip.nativeFile ? 'NATIVE' : 'RAW (−6 dB)'}`;
    audio.loop = Boolean(state.repeat && clip.loop && !state.sequence.length);
    updateCards();
  }
  async function playClip(clip, preserveSequence = false, position = 0) {
    if (!clip || clip.status === 'failed') return;
    if (!preserveSequence) { state.sequence = []; state.sequenceIndex = -1; }
    const token = ++loadToken;
    state.loadingToken = token;
    stopHeld();
    audio.pause();
    setCurrent(clip);
    const source = clipSource(clip);
    if (source === '#') { tell('This render has no playable audio file.'); return; }
    const Context = window.AudioContext || window.webkitAudioContext;
    if (clip.loop && Context) {
      try {
        if (!audioContext) audioContext = new Context();
        await audioContext.resume();
        let buffer = heldCache.get(source);
        if (!buffer) {
          const response = await fetch(source);
          if (!response.ok) throw new Error('Audio fetch failed');
          buffer = await audioContext.decodeAudioData(await response.arrayBuffer());
          if (heldCache.size >= 6) heldCache.delete(heldCache.keys().next().value);
          heldCache.set(source, buffer);
        }
        if (token !== loadToken) return;
        held = { buffer, source: null, offset: position, startedAt: 0, paused: true, loop: false };
        startHeldSource(position);
        state.loadingToken = 0;
        updateProgress();
        $('#audio-status').textContent = `Playing ${clipAccessibleTitle(clip)}.`;
        return;
      } catch (_) {
        if (token !== loadToken) return;
        tell('Buffered playback is unavailable. Browser audio repeat may introduce a small playback gap.');
      }
    } else if (clip.loop && !Context) tell('Browser audio repeat may introduce a small playback gap.');
    if (token !== loadToken) return;
    audio.src = source;
    if (position > 0) {
      audio.addEventListener('loadedmetadata', function seek() {
        if (token === loadToken && finite(audio.duration)) audio.currentTime = Math.min(position, Math.max(0, audio.duration - .03));
      }, { once: true });
    }
    try {
      await audio.play();
      if (token !== loadToken) return;
      state.loadingToken = 0;
      $('#audio-status').textContent = `Playing ${clipAccessibleTitle(clip)}.`;
      updateCards();
    } catch (error) {
      if (token !== loadToken || error.name === 'AbortError') return;
      state.loadingToken = 0;
      state.sequence = [];
      state.sequenceIndex = -1;
      updateCards();
      tell('Audio could not start. Try the clip again or download its WAV.');
    }
  }
  function toggleClip(clip) {
    if (state.loadingToken || (clip.loop && !held)) { playClip(clip, false, state.current?.id === clip.id ? state.pendingPosition || 0 : 0); state.pendingPosition = 0; return; }
    if (state.current?.id === clip.id && (held || !audio.ended)) {
      state.sequence = [];
      state.sequenceIndex = -1;
      audio.loop = Boolean(state.repeat && clip.loop);
      if (held) { held.loop = Boolean(state.repeat && clip.loop); if (held.source) held.source.loop = held.loop; }
      if (isPlaying()) pausePlayback(); else resumePlayback();
    } else playClip(clip);
  }
  function updateCards() {
    const playing = state.current && isPlaying();
    $$('.clip-card').forEach(card => {
      const current = state.current && String(state.current.id) === card.dataset.clip;
      card.classList.toggle('is-playing', Boolean(current && playing));
      const button = $('.clip-play', card);
      const clip = state.clips.get(card.dataset.clip);
      button.setAttribute('aria-label', `${current && playing ? 'Pause' : 'Play'} ${clip ? clipAccessibleTitle(clip) : card.dataset.clip}`);
      button.replaceChildren(el('span', current && playing ? 'pause-icon' : 'play-icon'));
      button.firstChild.setAttribute('aria-hidden', 'true');
      button.setAttribute('aria-pressed', String(Boolean(current && playing)));
      if (!current) $('.card-progress span', card).style.width = '0%';
    });
    $('#transport-toggle').setAttribute('aria-label', playing ? 'Pause audio' : 'Play audio');
    $('#transport-toggle').replaceChildren(el('span', playing ? 'pause-icon' : 'play-icon'));
    $('#transport-toggle').firstChild.setAttribute('aria-hidden', 'true');
    $$('.sequence-button').forEach(button => {
      const active = state.sequence.length && button.dataset.sequence === state.sequenceGroup;
      button.replaceChildren(el('span', active ? 'pause-icon' : 'play-icon'), document.createTextNode(active ? 'Stop sequence' : 'Play sequence'));
      button.firstChild.setAttribute('aria-hidden', 'true');
      button.setAttribute('aria-pressed', String(Boolean(active)));
    });
  }
  audio.addEventListener('play', updateCards);
  audio.addEventListener('pause', updateCards);
  audio.addEventListener('loadedmetadata', () => { $('#duration').textContent = time(audio.duration); });
  audio.addEventListener('timeupdate', () => { if (!held) updateProgress(); });
  function onPlaybackEnded() {
    if (state.sequence.length && state.sequenceIndex + 1 < state.sequence.length) {
      state.sequenceIndex += 1;
      playClip(state.sequence[state.sequenceIndex], true);
    } else {
      state.sequence = [];
      state.sequenceIndex = -1;
      updateCards();
      $('#audio-status').textContent = 'Playback finished.';
    }
  }
  audio.addEventListener('ended', () => { if (!held) onPlaybackEnded(); });
  audio.addEventListener('error', () => {
    if (held || !state.current || !audio.error || audio.error.code === 1) return;
    state.sequence = [];
    state.sequenceIndex = -1;
    updateCards();
    tell('This audio file could not be loaded. You can try its audio download.');
  });
  $('#transport-toggle').addEventListener('click', () => { if (state.current) toggleClip(state.current); });
  $('#transport-stop').addEventListener('click', () => {
    loadToken += 1;
    state.loadingToken = 0;
    stopHeld();
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    state.current = null;
    state.sequence = [];
    state.sequenceIndex = -1;
    refreshLevelControls();
    $('#transport').hidden = true;
    document.body.classList.remove('has-player');
    updateCards();
  });
  $('#playhead').addEventListener('input', event => {
    state.seeking = true;
    const duration = playbackDuration();
    if (finite(duration)) { seekPlayback(Number(event.target.value) / 1000 * duration); $('#elapsed').textContent = time(playbackTime()); }
  });
  $('#playhead').addEventListener('change', () => { state.seeking = false; });
  $$('[data-level]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.level === state.level) return;
    const position = playbackTime();
    const wasPlaying = state.current && (isPlaying() || Boolean(state.loadingToken));
    state.level = button.dataset.level;
    refreshLevelControls();
    if (state.current) {
      if (wasPlaying) playClip(state.current, Boolean(state.sequence.length), position);
      else if (held) {
        state.pendingPosition = position;
        stopHeld();
        audio.pause();
        audio.src = clipSource(state.current);
        setCurrent(state.current);
        audio.addEventListener('loadedmetadata', () => { if (finite(audio.duration)) audio.currentTime = Math.min(position, audio.duration); }, { once: true });
      } else {
        const clip = state.current;
        setCurrent(clip);
        audio.src = clipSource(clip);
        audio.addEventListener('loadedmetadata', () => { if (finite(audio.duration)) audio.currentTime = Math.min(position, audio.duration); }, { once: true });
      }
    }
  }));
  $('#repeat-held').addEventListener('change', event => {
    state.repeat = event.target.checked;
    audio.loop = Boolean(state.repeat && state.current?.loop && !state.sequence.length);
    if (held) { held.loop = audio.loop; if (held.source) held.source.loop = held.loop; }
    if (state.repeat && state.sequence.length) tell('Repeat is enabled for individual held clips. Sequences play each clip once.');
  });

  function isBubbleTone(clip) {
    return clip?.groupId === 'bubble-tone' || /^bubble_tone_\d+_(reference|candidate)$/.test(String(clip?.id || ''));
  }
  function isRattleCohesion(clip) {
    return clip?.groupId === 'rattle-cohesion' || /^rattle_cohesion_\d+_(reference|candidate)$/.test(String(clip?.id || ''));
  }
  function clipTitle(clip) {
    if (isSourceStudy(clip)) {
      const names = { current: 'Previous body', warm_wire: 'Warm wire', hollow_reed: 'Hollow reed', rubbed_glass: 'Rubbed glass' };
      const family = /^source_(current|warm_wire|hollow_reed|rubbed_glass)_/.exec(clip.id)[1];
      return `${names[family]}${clip.kind === 'pattern' ? ' · phrase' : clip.loop ? ' · held' : ''}`;
    }
    if (isBubbleTone(clip)) {
      if (/_reference$/.test(clip.id)) return 'Earlier pops';
      if (/_candidate$/.test(clip.id)) return 'Longer pitched tone';
    }
    if (isRattleCohesion(clip)) {
      if (/_reference$/.test(clip.id)) return 'Earlier separate hits';
      if (/_candidate$/.test(clip.id)) return 'Connected body';
    }
    return clip?.title || clip?.id || 'Wick clip';
  }
  function clipAccessibleTitle(clip) {
    if (isSourceStudy(clip) && finite(clip.midi) && clip.kind !== 'pattern') return `${clipTitle(clip)} at ${noteName(clip.midi)} (MIDI ${clip.midi})`;
    return `${clipTitle(clip)}${(isBubbleTone(clip) || isRattleCohesion(clip)) && finite(clip.midi) ? ` at MIDI ${clip.midi}` : ''}`;
  }

  function createCard(clip) {
    const unique = `clip-detail-${++cardCounter}`;
    const card = el('article', `clip-card${clip.status === 'failed' ? ' failed' : ''}`);
    card.dataset.clip = String(clip.id);
    const top = el('div', 'clip-top');
    top.append(el('span', 'voice-tag', clip.voice || 'WICK'));
    if (isSourceStudy(clip)) {
      const badge = clip.id.startsWith('source_current_') ? 'Previous body' : clip.id.startsWith('source_warm_wire_') ? 'Default body' : 'Saved body';
      top.append(el('span', 'source-tag', badge));
    }
    if (clip.loop) top.append(el('span', 'hold-tag', 'HOLD'));
    top.append(el('span', 'clip-id', clip.id));
    const main = el('div', 'clip-main');
    const play = el('button', 'clip-play');
    play.type = 'button';
    play.setAttribute('aria-label', `Play ${clipAccessibleTitle(clip)}`);
    play.setAttribute('aria-pressed', 'false');
    const icon = el('span', 'play-icon');
    icon.setAttribute('aria-hidden', 'true');
    play.append(icon);
    play.disabled = clip.status === 'failed' || !clip.file;
    play.addEventListener('click', () => toggleClip(clip));
    main.append(play, el('h5', 'clip-name', clipTitle(clip)));
    const description = el('p', 'clip-description', clip.description || `${clip.voice || 'Wick'} · ${clip.kind || 'render'}`);
    const meta = el('div', 'clip-meta');
    const pattern = clip.kind === 'pattern';
    const note = noteName(clip.midi);
    if (pattern) {
      meta.append(el('span', '', 'Pattern'), el('span', '', 'Notes & velocity vary'));
      if (finite(clip.bpm)) meta.append(el('span', '', `${numeric(clip.bpm)} BPM`));
      if (finite(clip.bars)) meta.append(el('span', '', `${numeric(clip.bars)} bars`));
    } else {
      if (note) meta.append(el('span', '', `${note} · MIDI ${clip.midi}`));
      if (finite(clip.velocity)) meta.append(el('span', '', `Vel ${numeric(clip.velocity)}`));
    }
    if (clip.macro && finite(clip.value)) meta.append(el('span', '', `${clip.macro} ${numeric(clip.value, 2)}`));
    if (!clip.macro) meta.append(el('span', '', 'Empty FX chain'));
    if (clip.loop && /replay|schedule|approx/i.test(String(clip.diagnostics?.loop?.strategy || ''))) meta.append(el('span', '', 'Event-replay approximation'));
    const progress = el('div', 'card-progress');
    progress.setAttribute('aria-hidden', 'true');
    progress.append(el('span'));
    const footer = el('div', 'clip-footer');
    if (clip.file) footer.append(link(/\.flac$/i.test(clip.file) ? 'FLAC ↓' : 'WAV ↓', clip.file));
    if (pattern) {
      const sequenceRecipe = clip.sequenceRecipeFile || clip.recipeFile;
      if (sequenceRecipe) footer.append(link('Sequence recipe ↓', sequenceRecipe));
    } else {
      if (clip.patchFile) footer.append(link('Patch ↓', clip.patchFile));
      if (clip.recipeFile) footer.append(link('Recipe ↓', clip.recipeFile));
    }
    const details = el('details', 'clip-details');
    const summary = el('summary', '', 'Diagnostics');
    summary.setAttribute('aria-controls', unique);
    details.append(summary);
    footer.append(details);
    const diagnostic = el('div', 'diagnostics-panel');
    diagnostic.id = unique;
    diagnostic.hidden = true;
    details.addEventListener('toggle', () => {
      diagnostic.hidden = !details.open;
      if (details.open && !diagnostic.dataset.loaded) {
        diagnostic.dataset.loaded = 'true';
        populateDiagnostics(diagnostic, clip);
      }
    });
    card.append(top, main, description, meta);
    if (clip.status === 'failed') card.append(el('p', 'failure-note', `Render flagged: ${(clip.failures || ['Not ready for audition']).join('; ')}`));
    card.append(progress, footer, diagnostic);
    return card;
  }
  function createGroup(group) {
    const bubbleTone = group.id === 'bubble-tone';
    const rattleCohesion = group.id === 'rattle-cohesion';
    const prioritizeRattle = group.id === 'defaults' || group.id === 'musical-patterns';
    const clips = rattleCohesion ? group.clips.slice().sort((a, b) => ([60, 48, 72].indexOf(a.midi) - [60, 48, 72].indexOf(b.midi)) || Number(/_candidate$/.test(a.id)) - Number(/_candidate$/.test(b.id))) : bubbleTone ? group.clips.slice().sort((a, b) => (a.midi - b.midi) || Number(/_candidate$/.test(a.id)) - Number(/_candidate$/.test(b.id))) : prioritizeRattle ? group.clips.slice().sort((a, b) => Number(b.voice === 'RATTLE') - Number(a.voice === 'RATTLE')) : group.clips.slice();
    if (group.id === 'source-register') {
      const familyOrder = clip => ['current', 'warm_wire', 'hollow_reed', 'rubbed_glass'].findIndex(family => String(clip.id).startsWith(`source_${family}_`));
      clips.sort((a, b) => ([60, 48, 72].indexOf(a.midi) - [60, 48, 72].indexOf(b.midi)) || familyOrder(a) - familyOrder(b));
    }
    const sweep = group.clips.some(clip => clip.kind === 'sweep' || clip.kind === 'held-sweep');
    const section = el('section', `clip-group${sweep ? ' sweep-group' : ''}`);
    section.dataset.group = String(group.id);
    section.dataset.voices = [...new Set(group.clips.map(clip => String(clip.voice || '').toUpperCase()))].join(',');
    section.dataset.macros = [...new Set(group.clips.map(clip => String(clip.macro || '').toUpperCase()))].join(',');
    const header = el('div', 'group-header');
    const heading = el('div', 'group-heading');
    const headingId = `group-${String(group.id).replace(/[^\w-]/g, '-')}`;
    const musicalPatterns = group.id === 'musical-patterns';
    const title = el('h4', '', group.id === 'source-register' ? 'RATTLE · Three bodies, one movement' : rattleCohesion ? 'RATTLE · Earlier separate hits / Connected body' : musicalPatterns ? 'Six voices · Short musical phrases' : bubbleTone ? 'BUBBLE · Earlier pops / Longer pitched tone' : group.title || group.id);
    title.id = headingId;
    section.setAttribute('aria-labelledby', headingId);
    heading.append(title);
    if (rattleCohesion) heading.append(el('p', '', 'This earlier articulation comparison retains the previous folded-FM body. Compare the separate openings with the connected response at the same pitch, velocity, and controls. C4 comes first, followed by C3 and C5. The saved bodies above keep the connected movement.'));
    else if (musicalPatterns) heading.append(el('p', '', 'Hear the same phrase played by RATTLE, FLICKER, BREATH, PLUCK, WOOD, and BUBBLE. The established three-voice arrangement follows, combining PLUCK, WOOD, and BUBBLE. These finite renders include the exact sequence recipes.'));
    else if (bubbleTone) heading.append(el('p', '', 'Can you hear the note ring through the smaller pops? Compare each pair at MIDI 48, 60, and 72. Pitch, velocity, and all controls match within each pair; FEED stays at 25%.'));
    else if (group.description) heading.append(el('p', '', group.description));
    header.append(heading);
    const playable = clips.filter(clip => clip.status !== 'failed' && clip.file);
    if (playable.length > 1) {
      const sequence = el('button', 'sequence-button');
      sequence.type = 'button';
      sequence.dataset.sequence = String(group.id);
      sequence.setAttribute('aria-label', `Play ${group.title || group.id} in sequence`);
      sequence.setAttribute('aria-pressed', 'false');
      const icon = el('span', 'play-icon');
      icon.setAttribute('aria-hidden', 'true');
      sequence.append(icon, document.createTextNode('Play sequence'));
      sequence.addEventListener('click', () => {
        if (state.sequence.length && state.sequenceGroup === String(group.id)) {
          state.sequence = [];
          state.sequenceIndex = -1;
          pausePlayback();
          updateCards();
          return;
        }
        state.sequence = playable;
        state.sequenceIndex = 0;
        state.sequenceGroup = String(group.id);
        playClip(playable[0], true);
      });
      header.append(sequence);
    }
    const grid = el('div', 'clip-grid');
    clips.forEach(clip => grid.append(createCard(clip)));
    section.append(header, grid);
    return section;
  }

  function showValueList(parent, pairs) {
    const list = el('dl');
    for (const [label, value] of pairs) {
      if (value === undefined || value === null) continue;
      list.append(el('dt', '', label), el('dd', '', typeof value === 'number' ? numeric(value, 6) : String(value)));
    }
    if (list.children.length) parent.append(list);
  }
  function addEventLane(parent, data) {
    const events = data.events || data.diagnostics?.events || data.eventTrace || data.trace?.events;
    if (!Array.isArray(events) || !events.length) return;
    const timing = item => {
      for (const key of ['timeSeconds', 'timeSec', 'time', 't']) if (finite(item[key])) return item[key];
      const frame = item.frame ?? item.sample ?? item.sampleIndex;
      return finite(frame) ? frame / (data.sampleRate || state.manifest.sampleRate || 44100) : null;
    };
    const stageFor = item => String(item.kind || '').toUpperCase() === 'RELEASE'
      ? item.parentStage : (item.stage ?? item.gate ?? item.stageIndex ?? item.destination ?? -1);
    const mapped = events.map(item => ({ item, at: timing(item), stage: Number(stageFor(item)) })).filter(event => finite(event.at));
    if (!mapped.length) return;
    const last = Math.max(...mapped.map(event => event.at), .1);
    const svgNS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 440 108');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', `Recorded events across ${numeric(last, 2)} seconds. The full trace is available to download.`);
    const draw = (type, attrs, text) => { const node = document.createElementNS(svgNS, type); Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value)); if (text) node.textContent = text; svg.append(node); return node; };
    const oneBased = mapped.some(event => event.stage === 3) && !mapped.some(event => event.stage === 0);
    for (let i = 0; i < 3; i += 1) {
      const y = 24 + i * 25;
      draw('line', { x1: 39, y1: y, x2: 425, y2: y, stroke: '#d4d8c8', 'stroke-width': 1 });
      draw('text', { x: 10, y: y + 3, fill: '#69745c', 'font-size': 8, 'font-family': 'monospace' }, `0${i + 1}`);
    }
    mapped.slice(0, 1400).forEach(({ item, at, stage }) => {
      const index = oneBased ? stage - 1 : stage;
      if (index < 0 || index > 2) return;
      const x = 40 + (at / last) * 380;
      const kind = String(item.type || item.kind || item.cause || '').toLowerCase();
      draw('line', { x1: x, y1: 18 + index * 25, x2: x, y2: 30 + index * 25, stroke: /release/.test(kind) ? '#79896a' : '#b53e28', 'stroke-width': 1.5 });
    });
    draw('text', { x: 40, y: 99, fill: '#69745c', 'font-size': 8, 'font-family': 'monospace' }, '0 s');
    draw('text', { x: 425, y: 99, fill: '#69745c', 'font-size': 8, 'text-anchor': 'end', 'font-family': 'monospace' }, `${numeric(last, 2)} s`);
    parent.append(el('h5', '', 'Recorded gate events'), svg, el('p', '', 'Ember: excitation. Green: releases on the gate that closed. Each lane is one gate; see the trace for event causes.'));
    if (data.diagnostics?.loop || data.loop) parent.append(el('p', '', 'The held trace includes preroll and measurement cycles; its timeline is longer than the exported loop.'));
  }
  async function populateDiagnostics(parent, clip) {
    parent.append(el('p', '', 'Loading this render’s diagnostics…'));
    let data = clip.diagnostics || {};
    let failed = false;
    if (clip.diagnosticsFile) {
      try {
        const response = await fetch(safePath(clip.diagnosticsFile));
        if (!response.ok) throw new Error(String(response.status));
        data = await response.json();
      } catch (_) { failed = true; }
    }
    parent.replaceChildren();
    if (failed) parent.append(el('p', 'diag-status', 'The full trace could not be loaded. The manifest summary is shown below.'));
    const diagnosticData = data.diagnostics || data;
    const loop = diagnosticData.loop || clip.diagnostics?.loop || {};
    if (clip.loop && loop.strategy) {
      const replay = /replay|schedule|approx/i.test(String(loop.strategy));
      parent.append(el('p', replay ? 'diag-status' : '', `Held-loop strategy: ${loop.strategy}.${replay ? ' This uses an event-replay approximation; it is not a claim that the free-running network naturally repeats.' : ''}`));
    }
    const metrics = { ...(clip.metrics || {}), ...(data.metrics || {}), ...diagnosticData };
    showValueList(parent, [
      [clip.kind === 'pattern' ? 'MIDI note range' : 'MIDI note', clip.kind === 'pattern' ? clip.noteRange ? `${clip.noteRange.minMidi}–${clip.noteRange.maxMidi}` : 'Varies by sequence event' : finite(clip.midi) ? `${clip.midi} (${noteName(clip.midi)})` : undefined],
      [clip.kind === 'pattern' ? 'Velocity range' : 'Velocity', clip.kind === 'pattern' ? clip.velocityRange ? `${numeric(clip.velocityRange.min)}–${numeric(clip.velocityRange.max)}` : 'Varies by sequence event' : clip.velocity],
      ['Render status', clip.status],
      ['Underlying source', clip.sourceFamily || clip.rattleSource],
      ['Raw peak', metrics.rawPeak],
      ['Event count', diagnosticData.eventCount ?? clip.diagnostics?.eventCount ?? (Array.isArray(diagnosticData.events) ? diagnosticData.events.length : undefined)],
      ['Matched loudness', clip.metrics?.matchedLoudness],
      ['Onset classifier', metrics.onsetClassifier],
      ['Onset classifier confidence', metrics.onsetClassifierConfidence],
      ['Onset classification window (s)', metrics.onsetClassifierWindowSeconds],
      ['Exported seam error', clip.metrics?.exportedSeamError],
      ['Requested frequency (Hz)', loop.requestedHz],
      ['Actual frequency (Hz)', loop.actualHz],
      ['Loop audio error', loop.audioError]
    ]);
    if (clip.macros && typeof clip.macros === 'object') {
      parent.append(el('h5', '', 'Exact macro settings'));
      showValueList(parent, Object.entries(clip.macros));
    }
    if (metrics.onsetClassifier) parent.append(el('p', '', 'The onset classification uses the short window shown above. Long full-file renders may be classified as LOOP by duration.'));
    const options = Object.entries(clip.options || {}).filter(([key, value]) => value === true || ((key === 'oversample' || key === 'rattleSource') && value !== undefined));
    if (options.length) { parent.append(el('h5', '', 'Diagnostic render options')); showValueList(parent, options); }
    addEventLane(parent, data);
    if (clip.diagnosticsFile) parent.append(link('Download full trace JSON ↓', clip.diagnosticsFile));
    if (clip.seamContextFile) {
      const format = /\.flac$/i.test(clip.seamContextFile) ? 'FLAC' : 'WAV';
      parent.append(el('br'), link(`Download two-cycle seam context ${format} ↓`, clip.seamContextFile));
    }
    const all = el('details');
    all.append(el('summary', '', 'View complete diagnostic data'), el('pre', '', JSON.stringify(data, null, 2)));
    all.style.marginTop = '14px';
    parent.append(all);
  }

  function filterControls() {
    const voice = $('#voice-filter').value;
    const macro = $('#macro-filter').value;
    $$('.clip-group', $('[data-host="controls"]')).forEach(group => {
      group.hidden = (voice !== 'all' && !group.dataset.voices.split(',').includes(voice)) || (macro !== 'all' && !group.dataset.macros.split(',').includes(macro));
    });
  }
  $('#voice-filter').addEventListener('change', filterControls);
  $('#macro-filter').addEventListener('change', filterControls);
  function fillFeedback() {
    const current = notes[state.section] || {};
    $('#feedback-verdict').value = current.verdict || '';
    $('#feedback-text').value = current.text || '';
    $('#feedback-title').textContent = state.section === 'start' ? 'What did you hear?' : `Your ${sectionLabels[state.section].toLowerCase()} notes.`;
    $('#save-note').textContent = 'Saved locally as you type';
  }
  function saveFeedback() {
    notes[state.section] = { verdict: $('#feedback-verdict').value, text: $('#feedback-text').value, updatedAt: new Date().toISOString() };
    try { localStorage.setItem(notesKey, JSON.stringify(notes)); $('#save-note').textContent = 'Saved in this browser'; }
    catch (_) { $('#save-note').textContent = 'Browser storage unavailable — export to keep your notes'; }
  }
  $('#feedback-verdict').addEventListener('change', saveFeedback);
  $('#feedback-text').addEventListener('input', saveFeedback);
  function downloadJson(value, filename) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
    const anchor = link('', url);
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  $('#export-feedback').addEventListener('click', () => {
    saveFeedback();
    downloadJson({ study: 'SnipSnap Wick listening prototype', exportedAt: new Date().toISOString(), generatedAt: state.manifest?.generatedAt, schemaVersion: state.manifest?.schemaVersion, sections: notes }, 'wick-listening-notes.json');
    tell('Listening notes exported. Nothing was sent to a server.');
  });
  window.addEventListener('pagehide', () => { if ($('#feedback-text').value || $('#feedback-verdict').value) saveFeedback(); });

  async function loadBuildInfo() {
    const host = $('#build-info');
    try {
      const response = await fetch('build-info.json');
      if (!response.ok) throw new Error(String(response.status));
      const info = await response.json();
      host.replaceChildren();
      const pairs = [['Prototype', info.status || info.scope || info.description], ['Source commit', info.commit || info.sourceCommit], ['Built', info.generatedAt || info.builtAt]];
      pairs.forEach(([label, value]) => { if (value && typeof value === 'string') host.append(el('p', '', `${label}: ${value}`)); });
      const tests = info.tests || info.validation || info.checks;
      if (tests) {
        host.append(el('p', '', 'Recorded validation:'));
        if (Array.isArray(tests)) {
          const list = el('ul');
          tests.forEach(item => list.append(el('li', '', typeof item === 'string' ? item : [item.name || item.command, item.status || item.result, item.summary].filter(Boolean).join(' — ') || JSON.stringify(item))));
          host.append(list);
        } else if (typeof tests === 'string') host.append(el('p', '', tests));
        else host.append(el('pre', '', JSON.stringify(tests, null, 2)));
      }
      if (info.higherRateReferences) host.append(el('p', '', 'Higher-rate comparison renders are documented in the audio manifest.'));
      const limits = info.limitations || info.notes;
      if (Array.isArray(limits)) limits.forEach(item => host.append(el('p', '', String(item))));
      else if (limits) host.append(el('p', '', String(limits)));
      if (!host.children.length) host.append(el('pre', '', JSON.stringify(info, null, 2)));
      host.append(link('Download build information ↓', 'build-info.json'));
    } catch (_) {
      host.replaceChildren(el('p', '', 'This page presents the RATTLE/FLICKER/BREATH/WOOD/PLUCK/BUBBLE prototype and finite musical phrases. Per-render metrics and diagnostic traces are available on each clip; no full-release validation is claimed.'));
    }
  }
  async function init() {
    try {
      const response = await fetch('manifest.json');
      if (!response.ok) throw new Error(`Manifest ${response.status}`);
      const manifest = await response.json();
      let entries = Array.isArray(manifest.entries) ? manifest.entries : Array.isArray(manifest.clips) ? manifest.clips : [];
      if (!entries.length) throw new Error('No clips found');
      let sourceStudy = null;
      if (manifest.sourceStudyFile) {
        try {
          const studyResponse = await fetch(safePath(manifest.sourceStudyFile));
          if (!studyResponse.ok) throw new Error(`Source study ${studyResponse.status}`);
          const study = await studyResponse.json();
          if (study.format !== 'wick-source-study-v1' || !Array.isArray(study.entries) || !study.entries.length || !Array.isArray(study.groups)) throw new Error('Invalid source study');
          const ids = new Set(entries.map(clip => String(clip.id)));
          for (const clip of study.entries) {
            if (!isSourceStudy(clip) || ids.has(String(clip.id))) throw new Error('Invalid source-study clip ID');
            ids.add(String(clip.id));
          }
          entries = [...study.entries, ...entries];
          manifest.entries = entries;
          manifest.groups = [...study.groups, ...(Array.isArray(manifest.groups) ? manifest.groups : [])];
          sourceStudy = study;
        } catch (_) { tell('The body comparisons are unavailable. The main renders and earlier comparisons are still here.'); }
      }
      if (sourceStudy) {
        $('#source-study-intro').hidden = false;
        $('#standard-intro').hidden = true;
        $('#first-play-label').textContent = 'Hear Warm wire at C4';
        $('#source-study-download').hidden = false;
        $('#source-study-download').href = safePath(manifest.sourceStudyFile);
        $('#audition-scope').textContent = 'RATTLE now uses Warm wire by default, with Hollow reed and Rubbed glass as saved variants. Download a normal editable patch from each body’s single-note or held card, or try all three at C4 in the playable kit. The previous body remains as a comparison. The five accepted voices are preserved. All renders are dry; the body names describe tonal directions, not physical models.';
      }
      state.manifest = manifest;
      entries.forEach((clip, index) => { clip.id = String(clip.id ?? `clip-${index + 1}`); state.clips.set(clip.id, clip); });
      state.groups = normalizeGroups(manifest, entries);
      for (const name of sections) {
        const host = $(`[data-host="${name}"]`);
        const groups = state.groups.filter(group => group.section === name);
        if (name === 'start') {
          const priority = group => group.id === 'source-register' ? -2 : group.id === 'source-phrases' ? -1 : group.id === 'rattle-cohesion' ? 0 : group.id === 'defaults' ? 1 : group.id === 'rattle-register' ? 2 : group.id === 'rattle-reserve' ? 3 : group.id === 'musical-patterns' ? 4 : group.id === 'flicker-register' ? 5 : group.id === 'breath-sustain' ? 6 : group.id === 'bubble-tone' ? 8 : 7;
          groups.sort((a, b) => priority(a) - priority(b));
        }
        groups.forEach(group => host.append(createGroup(group)));
        if (!groups.length) host.append(el('p', 'empty-state', 'No renders in this section are included in this build.'));
      }
      const macros = [...new Set(state.groups.filter(group => group.section === 'controls').flatMap(group => group.clips.map(clip => clip.macro).filter(Boolean)))];
      macros.forEach(macro => { const option = el('option', '', macro); option.value = String(macro).toUpperCase(); $('#macro-filter').append(option); });
      const first = entries.find(clip => clip.id === 'source_warm_wire_60' && !clip.loop && clip.status !== 'failed') || entries.find(clip => clip.kind === 'defaults' && clip.voice === 'RATTLE' && !clip.loop && clip.status !== 'failed') || entries.find(clip => clip.id === 'rattle_cohesion_60_candidate' && !clip.loop && clip.status !== 'failed') || entries.find(clip => clip.kind === 'defaults' && !clip.loop && clip.status !== 'failed') || entries.find(clip => !clip.loop && clip.status !== 'failed');
      if (first) {
        $('#first-play').disabled = false;
        $('#first-play').addEventListener('click', () => { switchTab('start'); playClip(first); });
      }
      $('#load-state').hidden = true;
      $('.feedback').hidden = false;
      fillFeedback();
      const failed = entries.filter(clip => clip.status === 'failed').length;
      if (failed) tell(`${failed} render${failed === 1 ? ' is' : 's are'} flagged for review. Diagnostics include the details.`);
      const hash = location.hash.replace('#', '');
      if (sections.includes(hash)) switchTab(hash);
      else {
        const group = state.groups.find(item => `group-${item.id}` === hash);
        if (group) { switchTab(group.section); requestAnimationFrame(() => document.getElementById(hash)?.scrollIntoView()); }
      }
    } catch (error) {
      $('#load-state').textContent = 'The audio manifest could not be loaded. Please refresh the page to try again.';
      $('#load-state').classList.add('error');
      $$('[data-panel]').forEach(panel => { panel.hidden = true; });
    }
  }
  $$('[data-tab-link]').forEach(anchor => anchor.addEventListener('click', event => {
    event.preventDefault();
    switchTab(anchor.dataset.tabLink);
    const target = document.getElementById(anchor.hash.slice(1));
    if (target) { history.replaceState(null, '', anchor.hash); target.scrollIntoView(); }
  }));
  init();
  loadBuildInfo();
})();
