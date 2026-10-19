(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const localFiles = location.protocol === 'file:';
  const sectionDefinitions = {
    first: { number: '01', title: 'Start at the source', description: 'The dry source is the reference. Listen for what the reflected paths and resonant body add as you move through the study.' },
    positions: { number: '02', title: 'Source × focus', description: 'Each group holds the source still and moves the pickup. Compare the same focus across source groups, then listen along a group for the bloom.' },
    mechanism: { number: '03', title: 'What makes it sing?', description: 'Compare the full model with the controls at the same source and focus. Use level matching to judge the change in tone, then shared gain to hear its strength.' },
    range: { number: '04', title: 'Register & touch', description: 'Does the character survive a change in note or velocity? Listen for pitch, attack, and body across these fixed-position renders.' }
  };
  const state = { manifest: null, clips: [], mode: 'matched', active: null, context: null, gain: null, source: null, media: null, buffer: null, duration: 0, offset: 0, startedAt: 0, playing: false, loading: false, seeking: false, request: 0, repeat: false, volume: .65, cache: new Map(), notes: {}, storageKey: '', cards: new Map() };
  let toastTimer;

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function canonicalSection(section) {
    const key = String(section || '').toLowerCase().replaceAll('_', '-');
    if (/first|^source$|anchor|baseline|comparison/.test(key)) return 'first';
    if (/mechanism|control|ablation/.test(key)) return 'mechanism';
    if (/range|register|velocity|touch|note/.test(key)) return 'range';
    if (/position|focus|sweep/.test(key)) return 'positions';
    return key || 'positions';
  }
  function pretty(value) { return String(value || '').replace(/[-_]+/g, ' ').replace(/\b\w/g, c => c.toUpperCase()); }
  function numeric(value, digits = 2) { return Number.isFinite(Number(value)) ? Number(value).toFixed(digits).replace(/\.?0+$/, '') : String(value ?? '—'); }
  function clock(seconds) {
    const s = Math.max(0, Number(seconds) || 0);
    return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
  }
  function midiName(midi) {
    const number = Number(midi);
    if (!Number.isFinite(number)) return '';
    return `${['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((Math.round(number) % 12) + 12) % 12]}${Math.floor(number / 12) - 1}`;
  }
  function fileName(path) { try { return decodeURIComponent(new URL(path, document.baseURI).pathname.split('/').pop()); } catch { return String(path); } }
  function activePath(clip = state.active) { return clip ? (state.mode === 'matched' ? clip.matched || clip.raw : clip.raw || clip.matched) : ''; }
  function announce(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3500);
  }
  function storageRead() {
    try { state.notes = JSON.parse(localStorage.getItem(state.storageKey) || '{}') || {}; }
    catch { state.notes = {}; }
  }
  function storageWrite() {
    try { localStorage.setItem(state.storageKey, JSON.stringify(state.notes)); }
    catch { announce('Browser storage is unavailable. Export your notes before leaving.'); }
    updateNotesCount();
  }
  function updateNotesCount() {
    const count = Object.values(state.notes).filter(n => n && (n.verdict || n.text)).length;
    $('notes-count').textContent = `${count} ${count === 1 ? 'clip' : 'clips'} annotated`;
  }
  function addMetadata(parent, text) { if (text) parent.append(node('span', '', text)); }

  function createNotes(clip) {
    const saved = state.notes[clip.id] || {};
    const details = node('details', `clip-notes${saved.verdict || saved.text ? ' has-notes' : ''}`);
    const summary = node('summary', '', saved.verdict ? `Your note · ${saved.verdict}` : saved.text ? 'Your note · saved' : 'Add a listening note');
    details.append(summary);
    const form = node('div', 'note-form');
    const label = node('label', '', 'First impression');
    const select = node('select');
    select.id = `verdict-${clip.id}`;
    label.htmlFor = select.id;
    [['', 'Choose an impression'], ['Interesting', 'Interesting'], ['Too plain', 'Too plain'], ['Harsh', 'Harsh'], ['Other', 'Other']].forEach(([value, title]) => {
      const option = node('option', '', title); option.value = value; select.append(option);
    });
    select.value = saved.verdict || '';
    const text = node('textarea');
    text.value = saved.text || '';
    text.placeholder = 'What changes? What would you keep?';
    text.setAttribute('aria-label', `Listening note for ${clip.title}`);
    text.maxLength = 10000;
    const status = node('span', 'note-saved', 'Saved automatically in this browser');
    const save = () => {
      const verdict = select.value;
      const content = text.value;
      if (verdict || content) state.notes[clip.id] = { verdict, text: content, updatedAt: new Date().toISOString(), gainMode: state.mode };
      else delete state.notes[clip.id];
      details.classList.toggle('has-notes', Boolean(verdict || content));
      summary.textContent = verdict ? `Your note · ${verdict}` : content ? 'Your note · saved' : 'Add a listening note';
      storageWrite();
    };
    select.addEventListener('change', save);
    text.addEventListener('input', save);
    form.append(label, select, text, status);
    details.append(form);
    return details;
  }

  function createCard(clip, index) {
    const card = node('article', 'clip-card');
    card.id = `clip-${clip.id}`;
    card.setAttribute('aria-labelledby', `title-${clip.id}`);
    const top = node('div', 'card-top');
    const label = node('div', 'clip-index');
    label.append(node('span', '', String(index + 1).padStart(2, '0')));
    if (clip.comparison) label.append(node('span', 'variant-tag', String(clip.comparison)));
    else if (clip.variant && !/^full$/i.test(clip.variant)) label.append(node('span', 'variant-tag', pretty(clip.variant)));
    const play = node('button', 'play-button', '▶');
    play.type = 'button';
    play.setAttribute('aria-label', `Play ${clip.title}`);
    play.addEventListener('click', () => { if (state.active?.id === clip.id && state.playing) pause(); else playClip(clip); });
    top.append(label, play);
    const title = node('h4', '', clip.title);
    title.id = `title-${clip.id}`;
    const description = node('p', 'clip-description', clip.description || '');
    const metadata = node('div', 'clip-metadata');
    if (clip.source !== undefined) addMetadata(metadata, `SOURCE ${numeric(clip.source)}`);
    if (clip.focus !== undefined) addMetadata(metadata, `FOCUS ${numeric(clip.focus, 3)}`);
    if (clip.midi !== undefined) addMetadata(metadata, midiName(clip.midi));
    if (clip.velocity !== undefined) addMetadata(metadata, `VEL ${numeric(clip.velocity)}`);
    const bottom = node('div', 'card-bottom');
    const links = node('div', 'card-links');
    const wav = node('a', 'card-wav', 'WAV ↗');
    wav.href = activePath(clip); wav.download = fileName(activePath(clip));
    wav.setAttribute('aria-label', `Download ${clip.title} WAV`);
    const recipe = node('a', '', 'Recipe ↗');
    recipe.href = clip.recipe; recipe.download = fileName(clip.recipe);
    recipe.setAttribute('aria-label', `Download ${clip.title} recipe`);
    links.append(wav, recipe);
    bottom.append(links, node('span', 'duration', `${numeric(clip.seconds, 1)} sec · mono`));
    card.append(top, title, description, metadata, bottom, createNotes(clip));
    const error = node('p', 'clip-error'); error.hidden = true; error.setAttribute('role', 'alert');
    card.append(error);
    state.cards.set(clip.id, { card, play, wav, error });
    return card;
  }

  function renderStudies() {
    const groups = new Map();
    state.clips.forEach(clip => {
      const section = canonicalSection(clip.section);
      if (!groups.has(section)) groups.set(section, []);
      groups.get(section).push(clip);
    });
    const order = [...Object.keys(sectionDefinitions).filter(key => groups.has(key)), ...[...groups.keys()].filter(key => !sectionDefinitions[key])];
    const fragment = document.createDocumentFragment();
    state.cards.clear();
    $('section-filter').replaceChildren(new Option('All studies', 'all'));
    order.forEach((key, sectionIndex) => {
      const clips = groups.get(key);
      const definition = sectionDefinitions[key] || { number: String(sectionIndex + 1).padStart(2, '0'), title: pretty(key), description: '' };
      $('section-filter').append(new Option(definition.title, key));
      const section = node('section', 'study-section');
      section.dataset.section = key;
      section.id = `study-${key}`;
      section.setAttribute('aria-labelledby', `heading-${key}`);
      const heading = node('div', 'study-heading');
      heading.append(node('span', 'section-number', definition.number));
      const copy = node('div');
      const h = node('h3', '', definition.title); h.id = `heading-${key}`;
      copy.append(h, node('p', '', definition.description));
      heading.append(copy, node('span', 'section-quantity', `${clips.length} ${clips.length === 1 ? 'render' : 'renders'}`));
      section.append(heading);
      if (key === 'first') {
        const recommended = state.clips.filter(clip => Number.isFinite(clip.recommendedOrder)).sort((a, b) => a.recommendedOrder - b.recommendedOrder);
        if (recommended.length) {
          const route = node('div', 'listening-route');
          route.append(node('p', 'route-label', `SUGGESTED ROUTE / ${recommended.length} LISTENS`));
          const list = node('ol', 'route-list');
          recommended.forEach(clip => {
            const item = node('li');
            const button = node('button', '', clip.title);
            button.type = 'button';
            button.setAttribute('aria-label', `Play recommended clip ${clip.recommendedOrder}: ${clip.title}`);
            button.addEventListener('click', () => playClip(clip));
            item.append(button); list.append(item);
          });
          route.append(list); section.append(route);
        }
      }
      const comparisonGroups = new Map();
      clips.forEach(clip => {
        const group = clip.group || '';
        if (!comparisonGroups.has(group)) comparisonGroups.set(group, []);
        comparisonGroups.get(group).push(clip);
      });
      comparisonGroups.forEach((members, groupName) => {
        const group = node('div', 'clip-group');
        if (groupName && (comparisonGroups.size > 1 || key === 'positions')) group.append(node('h4', 'group-heading', pretty(groupName)));
        const grid = node('div', `clip-grid${members.length === 2 ? ' two-clips' : members.length === 1 ? ' one-clip' : ''}`);
        members.forEach(clip => grid.append(createCard(clip, state.clips.indexOf(clip))));
        group.append(grid); section.append(group);
      });
      fragment.append(section);
    });
    $('studies').replaceChildren(fragment);
  }

  async function loadManifest() {
    const status = $('load-status');
    status.hidden = false;
    try {
      let manifest = window.CAUSTIC_MANIFEST;
      if (!manifest) {
        const response = await fetch(new URL('manifest.json', document.baseURI));
        if (!response.ok) throw new Error(`Manifest returned HTTP ${response.status}`);
        manifest = await response.json();
      }
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('The manifest contains no audio clips.');
      const seen = new Set();
      manifest.clips.forEach(clip => {
        if (!clip.id || !clip.title || !clip.raw || !clip.matched || !clip.recipe) throw new Error('A clip is missing its ID, title, WAV, or recipe.');
        if (seen.has(clip.id)) throw new Error('The manifest contains duplicate clip IDs.');
        seen.add(clip.id);
      });
      state.manifest = manifest;
      state.clips = manifest.clips;
      state.storageKey = `snipsnap:caustic-notes:${manifest.model || 'prototype'}`;
      storageRead();
      renderStudies();
      updateNotesCount();
      $('clip-count').textContent = `${manifest.clips.length} Kotlin-rendered WAVs`;
      const summary = manifest.summary || {};
      const sampleRate = Number(summary.sampleRate || 44100);
      $('format-label').textContent = `Mono · ${numeric(sampleRate / 1000, 1)} kHz`;
      const revision = typeof manifest.sourceRevision === 'object' ? manifest.sourceRevision : { commit: manifest.sourceRevision };
      const provenance = [manifest.model ? `Model ${manifest.model}` : null, revision?.commit ? `Source ${String(revision.commit).slice(0, 10)}${revision.dirty ? ' + working changes' : ''}` : null, manifest.generatedAt ? `Rendered ${new Date(manifest.generatedAt).toLocaleDateString('en-GB', { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' })}` : null].filter(Boolean);
      $('provenance').textContent = provenance.join(' · ');
      if (revision?.sourceFingerprintSha256) $('provenance').title = `Source fingerprint SHA-256: ${revision.sourceFingerprintSha256}`;
      status.hidden = true;
      if (localFiles) {
        $('repeat').disabled = true;
        $('repeat').closest('label').title = 'Decoded, gapless replay needs this folder served over HTTP.';
        $('gain-explanation').textContent += ' Local file playback uses your browser’s media player. Serve this folder over HTTP for decoded, gapless replay.';
      }
    } catch (error) {
      status.replaceChildren(node('span', '', `The listening study could not load. ${error.message}`));
      const retry = node('button', '', 'Retry'); retry.type = 'button'; retry.addEventListener('click', loadManifest); status.append(retry);
    }
  }

  function ensureAudio() {
    if (localFiles) return;
    if (!state.context) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (!AudioContext) throw new Error('This browser does not support Web Audio. Download a WAV to listen.');
      state.context = new AudioContext();
      state.gain = state.context.createGain();
      state.gain.gain.value = state.volume;
      state.gain.connect(state.context.destination);
    }
    return state.context.resume();
  }
  function position() {
    if (localFiles && state.media) return state.media.currentTime || 0;
    if (!state.playing || !state.context) return state.offset;
    const elapsed = state.offset + state.context.currentTime - state.startedAt;
    return state.repeat && state.duration ? elapsed % state.duration : Math.min(elapsed, state.duration);
  }
  function halt(reset = false) {
    const current = position();
    if (state.source) {
      state.source.onended = null;
      try { state.source.stop(); } catch { /* The source may already have ended. */ }
      state.source.disconnect();
      state.source = null;
    }
    if (state.media) state.media.pause();
    state.offset = reset ? 0 : current;
    if (reset && state.media) { try { state.media.currentTime = 0; } catch { /* No media metadata yet. */ } }
    state.playing = false;
  }
  function pause() {
    state.request++;
    state.loading = false;
    halt();
    updatePlayer();
  }
  function finish() {
    state.playing = false;
    state.offset = state.duration;
    state.source?.disconnect();
    state.source = null;
    updatePlayer();
  }
  async function decodedBuffer(path, retry) {
    const url = new URL(path, document.baseURI).href;
    if (retry) state.cache.delete(url);
    if (!state.cache.has(url)) {
      const loading = (async () => {
        const response = await fetch(url);
        if (!response.ok) throw new Error(`Audio returned HTTP ${response.status}.`);
        return state.context.decodeAudioData(await response.arrayBuffer());
      })();
      state.cache.set(url, loading);
      loading.catch(() => state.cache.delete(url));
    }
    return state.cache.get(url);
  }
  async function playLocal(clip, offset, request) {
    const path = new URL(activePath(clip), document.baseURI).href;
    if (!state.media || state.media.src !== path) {
      if (state.media) { state.media.pause(); state.media.removeAttribute('src'); state.media.load(); }
      const media = new Audio(path);
      media.preload = 'auto'; media.volume = state.volume;
      state.media = media;
      await new Promise((resolve, reject) => {
        if (media.readyState >= 1) resolve();
        else {
          media.addEventListener('loadedmetadata', resolve, { once: true });
          media.addEventListener('error', () => reject(new Error('The local WAV could not load. Serve this folder over HTTP or download the WAV.')), { once: true });
        }
      });
      media.onended = finish;
    }
    if (request !== state.request) return;
    state.duration = state.media.duration;
    state.media.currentTime = offset < state.duration ? offset : 0;
    await state.media.play();
  }
  async function playClip(clip, explicitOffset, retry = false) {
    const request = ++state.request;
    const sameClip = state.active?.id === clip.id;
    const keep = $('keep-position').checked;
    let offset = explicitOffset ?? (sameClip || keep ? position() : 0);
    if (!Number.isFinite(offset)) offset = 0;
    halt();
    state.active = clip;
    state.loading = true;
    state.duration = Number(clip.seconds) || 0;
    state.offset = offset;
    $('player').hidden = false;
    document.body.classList.add('has-player');
    $('playback-error').hidden = true;
    const card = state.cards.get(clip.id);
    if (card) card.error.hidden = true;
    updatePlayer();
    try {
      await ensureAudio();
      if (request !== state.request) return;
      if (localFiles) await playLocal(clip, offset, request);
      else {
        const buffer = await decodedBuffer(activePath(clip), retry);
        if (request !== state.request) return;
        state.buffer = buffer;
        state.duration = buffer.duration;
        if (offset >= buffer.duration - .01) offset = 0;
        state.offset = Math.max(0, offset);
        const source = state.context.createBufferSource();
        source.buffer = buffer; source.loop = state.repeat;
        source.connect(state.gain);
        source.onended = () => { if (state.source === source && !state.repeat) finish(); };
        state.source = source;
        state.startedAt = state.context.currentTime;
        source.start(0, state.offset);
      }
      if (request !== state.request) return;
      state.loading = false;
      state.playing = true;
      updatePlayer();
    } catch (error) {
      if (request !== state.request) return;
      state.loading = false; state.playing = false;
      const message = `${error.message || 'Audio could not load.'} Press play to retry, or download the WAV.`;
      $('playback-error').textContent = message; $('playback-error').hidden = false;
      if (card) { card.error.textContent = message; card.error.hidden = false; }
      updatePlayer();
    }
  }

  function updatePlayer() {
    state.cards.forEach(({ card, play }, id) => {
      const selected = state.active?.id === id;
      card.classList.toggle('is-selected', selected);
      card.classList.toggle('is-playing', selected && state.playing);
      card.classList.toggle('is-loading', selected && state.loading);
      play.textContent = selected && state.loading ? '···' : selected && state.playing ? 'Ⅱ' : '▶';
      const clip = state.clips.find(item => item.id === id);
      play.setAttribute('aria-label', `${selected && state.loading ? 'Loading' : selected && state.playing ? 'Pause' : 'Play'} ${clip?.title || ''}`);
      play.setAttribute('aria-pressed', String(selected && state.playing));
    });
    if (!state.active) return;
    $('player-title').textContent = state.active.title;
    $('player-mode').textContent = state.loading ? 'LOADING AUDIO…' : state.mode === 'matched' ? 'LEVEL MATCHED' : 'SHARED GAIN';
    $('player-toggle').firstElementChild.textContent = state.loading ? '···' : state.playing ? 'Ⅱ' : '▶';
    $('player-toggle').setAttribute('aria-label', state.playing ? 'Pause selected clip' : 'Play selected clip');
    $('player-download').href = activePath();
    $('player-download').download = fileName(activePath());
    $('player-filename').textContent = fileName(activePath());
    $('player-filename').title = fileName(activePath());
    $('seek').max = String(state.duration || 1);
    updateTime();
  }
  function updateTime() {
    if (!state.active) return;
    const value = position();
    if (!state.seeking) $('seek').value = String(value);
    $('seek').setAttribute('aria-valuetext', `${clock(value)} of ${clock(state.duration)}`);
    $('player-time').textContent = `${clock(value)} / ${clock(state.duration)}`;
  }
  function downloadJSON(data, filename) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' }));
    const link = node('a'); link.href = url; link.download = filename; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  document.querySelectorAll('input[name="gain-mode"]').forEach(input => input.addEventListener('change', async () => {
    if (!input.checked || state.mode === input.value) return;
    const current = position();
    const wasPlaying = state.playing || state.loading;
    halt();
    state.request++;
    state.loading = false;
    state.mode = input.value;
    $('gain-explanation').textContent = state.mode === 'matched' ? 'Level matched reduces loudness differences so changes in tone are easier to hear.' : 'Shared gain applies the same export gain to every clip, preserving differences in bloom strength.';
    if (localFiles) $('gain-explanation').textContent += ' Local playback uses the browser’s media player; serve over HTTP for decoded, gapless replay.';
    state.cards.forEach(({ wav }, id) => {
      const clip = state.clips.find(item => item.id === id);
      wav.href = activePath(clip); wav.download = fileName(activePath(clip));
    });
    if (state.active && wasPlaying) playClip(state.active, current);
    else { state.buffer = null; state.offset = current; updatePlayer(); }
  }));
  $('section-filter').addEventListener('change', event => {
    document.querySelectorAll('.study-section').forEach(section => { section.hidden = event.target.value !== 'all' && section.dataset.section !== event.target.value; });
  });
  $('player-toggle').addEventListener('click', () => { if (!state.active) return; if (state.playing || state.loading) pause(); else playClip(state.active); });
  $('player-stop').addEventListener('click', () => { state.request++; state.loading = false; halt(true); updatePlayer(); });
  $('close-player').addEventListener('click', () => {
    state.request++; state.loading = false; halt(true); const active = state.active; state.active = null; updatePlayer();
    $('player').hidden = true; document.body.classList.remove('has-player'); state.cards.get(active?.id)?.play.focus({ preventScroll: true });
  });
  $('seek').addEventListener('input', event => { state.seeking = true; $('player-time').textContent = `${clock(event.target.value)} / ${clock(state.duration)}`; });
  $('seek').addEventListener('change', event => {
    const offset = Number(event.target.value);
    state.seeking = false;
    if (state.playing) playClip(state.active, offset);
    else { state.offset = offset; if (state.media) state.media.currentTime = offset; updateTime(); }
  });
  $('repeat').addEventListener('change', event => { const current = position(); state.repeat = event.target.checked; if (state.source) { state.source.loop = state.repeat; state.offset = current; state.startedAt = state.context.currentTime; } });
  $('volume').addEventListener('input', event => {
    state.volume = Number(event.target.value);
    if (state.gain) state.gain.gain.setTargetAtTime(state.volume, state.context.currentTime, .01);
    if (state.media) state.media.volume = state.volume;
  });
  $('export-notes').addEventListener('click', () => {
    if (!state.manifest) return;
    const notes = state.clips.filter(clip => state.notes[clip.id]).map(clip => ({ clipId: clip.id, title: clip.title, source: clip.source, focus: clip.focus, midi: clip.midi, velocity: clip.velocity, variant: clip.variant, ...state.notes[clip.id] }));
    downloadJSON({ study: state.manifest.title || 'Caustic listening study', model: state.manifest.model, sourceRevision: state.manifest.sourceRevision, exportedAt: new Date().toISOString(), notes }, `caustic-listening-notes-${new Date().toISOString().slice(0, 10)}.json`);
    announce('Listening notes exported.');
  });
  $('clear-notes').addEventListener('click', () => {
    if (!Object.keys(state.notes).length) { announce('There are no saved notes to clear.'); return; }
    if (!window.confirm('Clear all saved notes for this Caustic model in this browser? Export them first if you want to keep a copy.')) return;
    state.notes = {}; storageWrite();
    const selected = $('section-filter').value;
    renderStudies(); $('section-filter').value = selected; $('section-filter').dispatchEvent(new Event('change')); updatePlayer();
    announce('Saved notes cleared.');
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) updateTime(); });
  setInterval(() => { if (!document.hidden && state.playing) updateTime(); }, 100);
  loadManifest();
})();
