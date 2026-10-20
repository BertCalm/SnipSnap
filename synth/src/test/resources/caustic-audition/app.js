(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const localFiles = location.protocol === 'file:';
  const sectionDefinitions = {
    body: { number: '04', title: 'A fuller Alloy body', description: 'One new body setting beneath the same Alloy strike. Compare the first second after the attack against the preserved reference.' },
    alloy: { number: '03', title: 'Alloy, with more room', description: 'The preferred Alloy is the reference. Bloom and Deep keep that direction and give its companion resonances longer to unfold.' },
    depth: { number: '02', title: 'Depth & character', description: 'One geometry, three new directions. Compare the original with a fuller body, a hollow reed character, and a brighter alloy voice.' },
    first: { number: '01', title: 'Start at the source', description: 'The dry source is the reference. Listen for what the reflected paths and resonant body add as you move through the study.' },
    positions: { number: '02', title: 'Source × focus', description: 'Each group holds the source still and moves the pickup. Compare the same focus across source groups, then listen along a group for the bloom.' },
    mechanism: { number: '03', title: 'What makes it sing?', description: 'Compare the full model with the controls at the same source and focus. Use level matching to judge the change in tone, then shared gain to hear its strength.' },
    range: { number: '04', title: 'Register & touch', description: 'Does the character survive a change in note or velocity? Listen for pitch, attack, and body across these fixed-position renders.' }
  };
  const state = { manifest: null, clips: [], family: 'depth', mode: 'matched', active: null, context: null, gain: null, source: null, media: null, buffer: null, duration: 0, offset: 0, startedAt: 0, playing: false, loading: false, seeking: false, request: 0, repeat: false, volume: .65, cache: new Map(), notes: {}, storageModels: [], cards: new Map(), depthMidi: 60, depthButtons: [] };
  const depthCharacters = [
    { name: 'Original', letter: 'A', cue: 'The first reflected vessel.', detail: 'The reference. Keep its attack and body in mind.' },
    { name: 'Body', letter: 'B', cue: 'Low mids. Slower bloom.', detail: 'Listen for fullness and close sympathetic pairs.' },
    { name: 'Reed', letter: 'C', cue: 'Hollow tone. Breathing attack.', detail: 'Listen for odd harmonics and vocal beating.' },
    { name: 'Alloy', letter: 'D', cue: 'Bright rings. Longer decay.', detail: 'Listen for the slightly inharmonic upper body.' }
  ];
  const alloyCharacters = [
    { name: 'Preferred Alloy', key: 'preferred', letter: 'A', cue: 'Your preferred character. Preserved.', detail: 'The unchanged Alloy reference from the depth study.' },
    { name: 'Bloom', key: 'bloom', letter: 'B', cue: 'A little longer to unfold.', detail: 'Compare the attack, then follow the companion ring.' },
    { name: 'Deep', key: 'deep', letter: 'C', cue: 'The longest companion bloom.', detail: 'Listen for what the extra tail adds to the Alloy body.' }
  ];
  const bodyCharacters = [
    { name: 'Preferred Alloy', key: 'preferred', letter: 'A', cue: 'Your preferred character. Preserved.', detail: 'The unchanged Alloy reference from the depth study.' },
    { name: 'Fuller body', key: 'fuller', letter: 'B', cue: 'Same metallic strike. New body setting.', detail: 'Compare the body in the first second after the attack.' }
  ];
  let characters = depthCharacters;
  let toastTimer;

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function canonicalSection(section) {
    const key = String(section || '').toLowerCase().replaceAll('_', '-');
    if (key === 'body') return 'body';
    if (/alloy|bloom/.test(key)) return 'alloy';
    if (/depth|character/.test(key)) return 'depth';
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
  function clipModel(clip) { return String(clip.model ?? state.manifest?.model ?? 1); }
  function noteKey(clip) { return `${clipModel(clip)}:${clip.id}`; }
  function rawModeLabel() { return state.storageModels.length > 1 ? 'Revision gain' : 'Shared gain'; }
  function announce(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3500);
  }
  function storageRead() {
    state.notes = {};
    state.storageModels = [...new Set(state.clips.map(clipModel))];
    state.storageModels.forEach(model => {
      try {
        const notes = JSON.parse(localStorage.getItem(`snipsnap:caustic-notes:${model}`) || '{}') || {};
        Object.entries(notes).forEach(([id, note]) => { state.notes[`${model}:${id}`] = note; });
      } catch { /* An unavailable or malformed model store must not block listening. */ }
    });
  }
  function storageWrite() {
    try {
      state.storageModels.forEach(model => {
        const prefix = `${model}:`;
        const notes = Object.fromEntries(Object.entries(state.notes).filter(([key]) => key.startsWith(prefix)).map(([key, note]) => [key.slice(prefix.length), note]));
        localStorage.setItem(`snipsnap:caustic-notes:${model}`, JSON.stringify(notes));
      });
    }
    catch { announce('Browser storage is unavailable. Export your notes before leaving.'); }
    updateNotesCount();
  }
  function updateNotesCount() {
    const count = Object.values(state.notes).filter(n => n && (n.verdict || n.text)).length;
    $('notes-count').textContent = `${count} ${count === 1 ? 'clip' : 'clips'} annotated`;
  }
  function addMetadata(parent, text) { if (text) parent.append(node('span', '', text)); }

  function createNotes(clip) {
    const saved = state.notes[noteKey(clip)] || {};
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
      if (verdict || content) state.notes[noteKey(clip)] = { verdict, text: content, updatedAt: new Date().toISOString(), gainMode: state.mode };
      else delete state.notes[noteKey(clip)];
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
    if (/^[A-D]$/i.test(clip.comparison || '')) label.append(node('span', 'variant-tag', String(clip.comparison)));
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
    if (['depth', 'alloy', 'body'].includes(canonicalSection(clip.section))) addMetadata(metadata, `${pretty(clip.profile || '')} · M${clipModel(clip)}`);
    const bottom = node('div', 'card-bottom');
    const links = node('div', 'card-links');
    const wav = node('a', 'card-wav', 'WAV →');
    wav.href = activePath(clip); wav.download = fileName(activePath(clip));
    wav.setAttribute('aria-label', `Download ${clip.title} WAV`);
    const recipe = node('a', '', 'Recipe →');
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

  function comparisonClip(profile, midi) {
    if (state.family === 'body') {
      const register = { 36: 'c2', 60: 'c4', 84: 'c6' }[midi];
      const prefix = profile === 'Preferred Alloy' ? 'depth-alloy' : 'alloy-body';
      return state.clips.find(clip => clip.id === `${prefix}-${register}`);
    }
    if (state.family === 'alloy') {
      const register = { 36: 'c2', 60: 'c4', 84: 'c6' }[midi];
      const prefix = { 'Preferred Alloy': 'depth-alloy', Bloom: 'alloy-bloom', Deep: 'alloy-deep' }[profile];
      return state.clips.find(clip => clip.id === `${prefix}-${register}`);
    }
    if (profile === 'Original') {
      const reference = { 36: 'register-low', 60: 'mechanism-full', 84: 'register-high' }[midi];
      return state.clips.find(clip => clip.id === reference) || state.clips.find(clip => Number(clip.midi) === midi && clipModel(clip) === '1' && clip.variant === 'FULL');
    }
    return state.clips.find(clip => Number(clip.midi) === midi && String(clip.profile || '').toLowerCase() === profile.toLowerCase() && canonicalSection(clip.section) === 'depth' && Math.abs(Number(clip.focus) - .19) < .00001);
  }
  function updateDepthComparator() {
    state.depthButtons.forEach(({ button, character, name, caption, clipLink }) => {
      const clip = comparisonClip(character.name, state.depthMidi);
      const selected = Boolean(clip && state.active?.id === clip.id);
      button.dataset.clip = clip?.id || '';
      button.disabled = !clip;
      button.classList.toggle('is-selected', selected);
      button.classList.toggle('is-playing', selected && state.playing);
      button.classList.toggle('is-loading', selected && state.loading);
      button.setAttribute('aria-pressed', String(selected && state.playing));
      button.setAttribute('aria-label', `${selected && state.playing ? 'Pause' : 'Play'} ${character.name} at ${midiName(state.depthMidi)}`);
      name.textContent = character.name;
      caption.textContent = !clip ? 'Not in this pack' : selected && state.loading ? 'Loading…' : selected && state.playing ? 'Playing · click to pause' : `Play ${midiName(state.depthMidi)} →`;
      clipLink.href = clip ? `#clip-${clip.id}` : '#';
      clipLink.hidden = !clip;
    });
    document.querySelectorAll('.register-tabs button').forEach(button => {
      button.setAttribute('aria-pressed', String(Number(button.dataset.midi) === state.depthMidi));
    });
    const mode = $('depth-mode');
    if (mode) mode.textContent = `${state.mode === 'matched' ? 'Level matched' : rawModeLabel()} · ${midiName(state.depthMidi)} · SOURCE .50 / FOCUS .19`;
  }
  function createDepthComparator() {
    const panel = node('div', 'depth-comparator');
    panel.id = `${state.family === 'body' ? 'body' : state.family === 'alloy' ? 'alloy' : 'depth'}-comparison`;
    const top = node('div', 'depth-controls');
    const copy = node('div');
    copy.append(node('p', 'depth-eyebrow', state.family === 'body' ? 'SAME STRIKE. SAME NOTE. COMPARE THE BODY.' : state.family === 'alloy' ? 'SAME ALLOY. SAME NOTE. FOLLOW THE BLOOM.' : 'SAME NOTE. SAME POSITION. SWITCH THE BODY.'));
    const mode = node('p', 'depth-mode'); mode.id = 'depth-mode'; copy.append(mode);
    const registers = node('div', 'register-tabs'); registers.setAttribute('role', 'group'); registers.setAttribute('aria-label', 'Comparison register');
    [36, 60, 84].forEach(midi => {
      const button = node('button', '', midiName(midi)); button.type = 'button'; button.dataset.midi = String(midi);
      button.addEventListener('click', () => {
        const activeCharacter = characters.find(character => comparisonClip(character.name, state.depthMidi)?.id === state.active?.id);
        state.depthMidi = midi;
        updateDepthComparator();
        if (activeCharacter && state.playing) { const clip = comparisonClip(activeCharacter.name, midi); if (clip) playClip(clip, 0); }
        else if (activeCharacter) { halt(true); updatePlayer(); }
      });
      registers.append(button);
    });
    top.append(copy, registers);
    const grid = node('div', `depth-profile-grid${characters.length === 2 ? ' two-profiles' : characters.length === 3 ? ' three-profiles' : ''}`);
    state.depthButtons = [];
    characters.forEach(character => {
      const wrapper = node('div', `depth-profile-wrap profile-${character.key || character.name.toLowerCase()}`);
      const button = node('button', 'depth-profile'); button.type = 'button';
      const upper = node('span', 'profile-upper');
      upper.append(node('span', 'profile-letter', character.letter), node('span', 'profile-signal'));
      const name = node('strong', 'profile-name', character.name);
      const cue = node('span', 'profile-cue', character.cue);
      const detail = node('span', 'profile-detail', character.detail);
      const caption = node('span', 'profile-action');
      button.append(upper, name, cue, detail, caption);
      button.addEventListener('click', () => {
        const clip = comparisonClip(character.name, state.depthMidi);
        if (!clip) return;
        if (state.active?.id === clip.id && state.playing) pause(); else playClip(clip);
      });
      const clipLink = node('a', 'profile-clip-link', 'Clip, recipe & notes ↓');
      clipLink.addEventListener('click', () => {
        const target = $(clipLink.hash.slice(1));
        const archive = target?.closest('details.archive-study');
        if (archive?.hidden || target?.closest('.study-section')?.hidden) {
          $('section-filter').value = 'all';
          $('section-filter').dispatchEvent(new Event('change'));
        }
        if (archive) archive.open = true;
      });
      wrapper.append(button, clipLink); grid.append(wrapper);
      state.depthButtons.push({ button, character, name, caption, clipLink });
    });
    const hint = node('p', 'depth-hint', state.family === 'body' ? 'Compare just after the strike. Switch at the same playhead, or turn off “Keep playhead” to restart each attack. Preferred Alloy lasts 6.5 seconds; Fuller body lasts 8 seconds.' : state.family === 'alloy' ? 'Switch at the current playhead to compare the ring, or turn off “Keep playhead” to hear each attack. Preferred Alloy lasts 6.5 seconds; Bloom and Deep last 8 seconds. Changing register starts a new note.' : 'Click another character to compare at the current playhead. Turn off “Keep playhead” to hear each attack from the beginning. Changing register starts a new note.');
    panel.append(top, grid, hint);
    return panel;
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
    const leadKey = groups.has('body') ? 'body' : groups.has('alloy') ? 'alloy' : groups.has('depth') ? 'depth' : 'first';
    const hasDepth = leadKey !== 'first';
    const preferredLead = ['body', 'alloy'].includes(leadKey);
    let archive;
    if (hasDepth) {
      archive = node('details', 'archive-study'); archive.id = 'original-study';
      const summary = node('summary');
      const originalCount = state.clips.filter(clip => canonicalSection(clip.section) !== leadKey).length;
      const compact = state.manifest.summary?.originalClipsOmitted;
      const archiveTitle = preferredLead ? compact ? 'Preferred Alloy references' : 'The previous listening studies' : compact ? 'Original references' : 'The original listening study';
      summary.append(node('span', '', archiveTitle), node('span', 'archive-count', `${originalCount} preserved renders`), node('span', 'archive-plus', '+'));
      archive.append(summary);
      const intro = node('div', 'archive-intro');
      intro.append(node('p', '', preferredLead ? compact ? 'This compact pack includes four unchanged Alloy references: C2, C4, C6, and the sparse focus position. Earlier full studies remain in their original archives.' : 'The preferred Alloy and all earlier recordings, recipes, and notes stay preserved. Revisit earlier body and bloom experiments, source/focus scans, and mechanism controls here.' : compact ? 'This compact pack includes three original register references for comparing the new characters. The complete source/focus study and mechanism controls remain in the original audition archive.' : 'The original recordings, recipes, and listening notes are preserved. Open the source/focus study and mechanism controls to trace where the character began.'));
      const archiveDownloads = node('div', 'archive-downloads');
      for (const [key, label] of [['bloom', 'Download the bloom study →'], ['depth', 'Download the depth study →'], ['original', 'Download the original study →']]) {
        if (state.manifest.downloads?.[key]) { const link = node('a', 'text-link', label); link.href = state.manifest.downloads[key]; link.download = ''; archiveDownloads.append(link); }
      }
      if (archiveDownloads.childElementCount) intro.append(archiveDownloads);
      archive.append(intro);
    }
    $('section-filter').replaceChildren(new Option('All studies', 'all'));
    order.forEach((key, sectionIndex) => {
      const clips = groups.get(key);
      const baseDefinition = sectionDefinitions[key] || { number: String(sectionIndex + 1).padStart(2, '0'), title: pretty(key), description: '' };
      const definition = state.manifest.summary?.originalClipsOmitted && preferredLead && key === 'depth' ? {
        ...baseDefinition, title: 'Preferred Alloy · preserved references', description: `The unchanged Alloy recordings selected from the depth study. Compare these with ${state.family === 'body' ? 'Fuller body' : 'Bloom and Deep'} at the same note and focus.`
      } : state.manifest.summary?.originalClipsOmitted && ['mechanism', 'range'].includes(key) ? {
        ...baseDefinition,
        title: key === 'mechanism' ? 'Original reference · C4' : 'Original references · C2 & C6',
        description: 'The original model at the same position, note, and effort as the new characters. Use these recordings as references for the depth comparison.'
      } : baseDefinition;
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
      if (hasDepth && key === leadKey) section.append(createDepthComparator());
      if (key === leadKey) {
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
      if (hasDepth && key !== leadKey) archive.append(section); else fragment.append(section);
    });
    if (archive) fragment.append(archive);
    $('studies').replaceChildren(fragment);
    updateDepthComparator();
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
      state.family = state.clips.some(clip => canonicalSection(clip.section) === 'body') ? 'body' : state.clips.some(clip => canonicalSection(clip.section) === 'alloy') ? 'alloy' : 'depth';
      characters = state.family === 'body' ? bodyCharacters : state.family === 'alloy' ? alloyCharacters : depthCharacters;
      storageRead();
      $('raw-mode-label').textContent = rawModeLabel();
      renderStudies();
      updateNotesCount();
      $('clip-count').textContent = `${manifest.clips.length} Kotlin-rendered WAVs`;
      const downloads = manifest.downloads;
      const packLink = document.querySelector('.pack-link');
      if (downloads && !downloads.full) packLink.hidden = true;
      else { packLink.hidden = false; packLink.href = downloads?.full || 'audition.zip'; }
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
    $('player').dataset.state = state.loading ? 'loading' : state.playing ? 'playing' : 'paused';
    updateDepthComparator();
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
    $('player-mode').textContent = state.loading ? 'LOADING AUDIO…' : state.mode === 'matched' ? 'LEVEL MATCHED' : rawModeLabel().toUpperCase();
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
    $('gain-explanation').textContent = state.mode === 'matched' ? 'Level matched reduces loudness differences so changes in tone are easier to hear.' : state.storageModels.length > 1 ? 'Raw WAVs share one gain within each revision. Export gains can differ across revisions; use Level matched for tone comparisons.' : 'Shared gain applies the same export gain to every clip, preserving differences in bloom strength.';
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
    const archive = $('original-study');
    if (archive) {
      archive.hidden = event.target.value === state.family;
      if (event.target.value !== 'all' && event.target.value !== state.family) archive.open = true;
    }
  });
  $('player-toggle').addEventListener('click', () => { if (!state.active) return; if (state.playing || state.loading) pause(); else playClip(state.active); });
  $('player-stop').addEventListener('click', () => { state.request++; state.loading = false; halt(true); updatePlayer(); });
  $('close-player').addEventListener('click', () => {
    state.request++; state.loading = false; halt(true); const active = state.active; state.active = null; updatePlayer();
    $('player').hidden = true; document.body.classList.remove('has-player');
    const returnButton = state.depthButtons.find(item => item.button.dataset.clip === active?.id)?.button || state.cards.get(active?.id)?.play;
    returnButton?.focus({ preventScroll: true });
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
    const notes = state.clips.filter(clip => state.notes[noteKey(clip)]).map(clip => ({ clipId: clip.id, model: clipModel(clip), profile: clip.profile, title: clip.title, source: clip.source, focus: clip.focus, midi: clip.midi, velocity: clip.velocity, variant: clip.variant, ...state.notes[noteKey(clip)] }));
    downloadJSON({ study: state.manifest.title || 'Caustic listening study', model: state.manifest.model, sourceRevision: state.manifest.sourceRevision, exportedAt: new Date().toISOString(), notes }, `caustic-listening-notes-${new Date().toISOString().slice(0, 10)}.json`);
    announce('Listening notes exported.');
  });
  $('clear-notes').addEventListener('click', () => {
    if (!Object.keys(state.notes).length) { announce('There are no saved notes to clear.'); return; }
    if (!window.confirm('Clear all saved notes for the Caustic models in this study, in this browser? Export them first if you want to keep a copy.')) return;
    state.notes = {}; storageWrite();
    const selected = $('section-filter').value;
    renderStudies(); $('section-filter').value = selected; $('section-filter').dispatchEvent(new Event('change')); updatePlayer();
    announce('Saved notes cleared.');
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) updateTime(); });
  setInterval(() => { if (!document.hidden && state.playing) updateTime(); }, 100);
  loadManifest();
})();
