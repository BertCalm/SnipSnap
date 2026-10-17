(() => {
  'use strict';

  const storageKey = 'snipsnap-overlap-audition-v2';
  const groupInfo = {
    routing: { number: '01', title: 'Change the connections.', description: 'The same excitation through four revised routing patterns. Listen for contrasting bodies, colours and decays; compare with the first audition if useful.' },
    depth: { number: '02', title: 'Turn the knot.', description: 'Compare zero and half depth with full-depth TREFOIL above. Listen for a half-depth tail that keeps ringing. Tuning across the full morph remains under evaluation.' },
    breathing: { number: '03', title: 'Let it breathe.', description: 'Compare free, independent breaths with a shared pulse. Listen for a stronger common cadence when LOCK brings the motion together.' },
    glow: { number: '04', title: 'Bring up the glow.', description: 'A harmonic layer follows the resonating sound. Compare the clean network with its illuminated counterpart.' },
    musical: { number: '05', title: 'Try the instrument.', description: 'Step back from the comparison. A short pluck and an evolving tail suggest where this could live in SnipSnap.' }
  };
  const groupOrder = Object.keys(groupInfo);
  const clipsRoot = document.getElementById('clips');
  const status = document.getElementById('announcement');
  const audioEntries = [];
  let manifest = null;
  let mode = 'matched';
  let activeEntry = null;
  let modeGeneration = 0;
  let notes = {};
  let canStore = true;

  try { notes = JSON.parse(localStorage.getItem(storageKey) || '{}'); if (!notes || typeof notes !== 'object' || Array.isArray(notes)) notes = {}; }
  catch (_) { canStore = false; notes = {}; }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function announce(message) { status.textContent = message; }

  function duration(seconds) {
    const value = Number(seconds);
    if (!Number.isFinite(value)) return 'WAV';
    return `${Math.floor(value / 60).toString().padStart(2, '0')}:${Math.floor(value % 60).toString().padStart(2, '0')}`;
  }

  function sourceFor(clip, selectedMode) {
    const source = clip[selectedMode];
    if (typeof source === 'string') return source;
    if (source && typeof source === 'object') return source.url || source.path || source.file || '';
    return `assets/audio/${clip.id}-${selectedMode}.wav`;
  }

  function playIcon(paused) {
    return paused ? '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2 14 8 4 14Z"/></svg>' : '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 2h4v12H3zm6 0h4v12H9z"/></svg>';
  }

  function setEntryState(entry, isPlaying) {
    entry.play.innerHTML = playIcon(!isPlaying);
    entry.play.setAttribute('aria-label', `${isPlaying ? 'Pause' : 'Play'} ${entry.clip.title}`);
    entry.card.classList.toggle('is-active', isPlaying);
  }

  function updateDeck() {
    const playing = !!activeEntry && !activeEntry.audio.paused && !activeEntry.audio.ended;
    document.body.classList.toggle('is-playing', playing);
    document.getElementById('deck-state').textContent = playing ? 'PLAYING' : activeEntry ? 'PAUSED' : 'STANDBY';
    document.getElementById('deck-title').textContent = activeEntry ? activeEntry.clip.title.toUpperCase() : 'READY WHEN YOU ARE';
    document.getElementById('deck-time').textContent = duration(activeEntry ? activeEntry.audio.currentTime : 0);
    document.getElementById('transport-state').textContent = playing ? 'NOW PLAYING' : activeEntry ? 'PAUSED' : 'LISTENING BENCH';
    document.getElementById('transport-title').textContent = activeEntry ? activeEntry.clip.title : 'Choose a sound to begin';
  }

  function stopAll() {
    for (const entry of audioEntries) { entry.audio.pause(); entry.audio.currentTime = 0; setEntryState(entry, false); }
    activeEntry = null;
    updateDeck();
    announce('All audio stopped.');
  }

  function humanize(key) {
    return key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]/g, ' ').replace(/^./, value => value.toUpperCase());
  }

  function prettyValue(value) {
    if (value === null) return '—';
    if (Array.isArray(value)) return value.map(prettyValue).join(', ');
    if (typeof value === 'object') return Object.entries(value).map(([key, item]) => `${humanize(key)}: ${prettyValue(item)}`).join(' · ');
    if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(5)));
    return String(value);
  }

  function addData(parent, value, heading) {
    if (value === undefined || value === null) return;
    parent.append(el('h5', '', heading));
    if (typeof value === 'object' && !Array.isArray(value)) {
      const list = el('dl');
      for (const [key, item] of Object.entries(value)) { list.append(el('dt', '', humanize(key)), el('dd', '', prettyValue(item))); }
      parent.append(list);
    } else { parent.append(el('p', '', prettyValue(value))); }
  }

  function saveNotes() {
    try { localStorage.setItem(storageKey, JSON.stringify(notes)); canStore = true; }
    catch (_) { canStore = false; }
    updateNotesCount();
  }

  function updateNotesCount() {
    const count = Object.values(notes).filter(note => note && (note.rating || (note.text && note.text.trim()))).length;
    document.getElementById('notes-count').textContent = count ? `${count} sound${count === 1 ? '' : 's'} with listening notes.` : 'No listening notes yet.';
    for (const entry of audioEntries) {
      const note = notes[entry.clip.id];
      const saved = note && (note.rating || (note.text && note.text.trim()));
      entry.saved.textContent = saved ? canStore ? 'Saved here' : 'In this tab' : '';
      entry.localMessage.textContent = canStore ? 'Saved automatically in this browser. Not sent anywhere.' : 'Browser storage is unavailable. Export your notes before closing this tab.';
    }
  }

  function buildClip(clip, index) {
    const card = el('article', 'clip');
    card.id = `clip-${clip.id}`;
    const heading = el('div', 'clip-top');
    const label = el('div');
    label.append(el('div', 'clip-number', `TAKE ${String(index + 1).padStart(2, '0')}`));
    const title = el('h4', '', clip.title || clip.id);
    title.id = `title-${clip.id}`;
    card.setAttribute('aria-labelledby', title.id);
    label.append(title);
    heading.append(label, el('span', 'clip-duration', duration(clip.seconds)));
    card.append(heading, el('p', 'clip-description', clip.description || 'Listen, compare, and leave a note.'));

    const sound = el('div', 'clip-sound');
    const play = el('button', 'play-button');
    play.type = 'button';
    play.innerHTML = playIcon(true);
    play.setAttribute('aria-label', `Play ${clip.title}`);
    const audio = document.createElement('audio');
    audio.controls = true;
    audio.preload = 'none';
    audio.src = sourceFor(clip, mode);
    audio.setAttribute('aria-label', `${clip.title} audio, ${mode} level`);
    audio.append(document.createTextNode('Your browser does not support audio playback. Use the WAV download below.'));
    sound.append(play, audio);
    card.append(sound);
    const foot = el('div', 'clip-foot');
    const tagValue = clip.tags ? (Array.isArray(clip.tags) ? clip.tags.join(' / ') : clip.tags) : clip.topology || humanize(clip.group || 'audition');
    foot.append(el('span', 'clip-tags', tagValue));
    const download = el('a', 'download-link', 'WAV ↓');
    download.href = sourceFor(clip, mode);
    download.download = `${clip.id}-${mode}.wav`;
    download.setAttribute('aria-label', `Download ${clip.title}, ${mode} WAV`);
    foot.append(download);
    card.append(foot);

    const details = el('details', 'clip-details');
    details.append(el('summary', '', 'Recipe & measured output'));
    const content = el('div', 'details-content');
    if (typeof clip.recipe === 'string' && /\.json(?:\?|$)/.test(clip.recipe)) {
      const recipeHeading = el('h5', '', 'Render recipe');
      const recipeStatus = el('p', '', 'Open these details to load the rendering recipe.');
      const recipeArea = el('div');
      const recipeDownload = el('a', '', 'Download recipe JSON ↓');
      recipeDownload.href = clip.recipe;
      recipeDownload.download = `${clip.id}.json`;
      recipeDownload.setAttribute('aria-label', `Download ${clip.title} render recipe`);
      recipeArea.append(recipeHeading, recipeStatus, recipeDownload);
      content.append(recipeArea);
      let recipeRequested = false;
      details.addEventListener('toggle', async () => {
        if (!details.open || recipeRequested) return;
        recipeRequested = true;
        recipeStatus.textContent = 'Loading recipe…';
        try {
          const response = await fetch(clip.recipe);
          if (!response.ok) throw new Error('Recipe unavailable');
          const recipe = await response.json();
          recipeArea.replaceChildren();
          addData(recipeArea, recipe, 'Render recipe');
          recipeArea.append(recipeDownload);
        } catch (_) {
          recipeStatus.textContent = 'The recipe could not be loaded here. Use the JSON download to try it directly.';
          recipeRequested = false;
        }
      });
    } else {
      addData(content, clip.recipe, 'Render recipe');
    }
    addData(content, clip.metrics, 'Measured output');
    if (!clip.recipe && !clip.metrics) content.append(el('p', '', 'See the audio manifest for the full render specification.'));
    const downloads = el('div', 'details-downloads');
    for (const level of ['matched', 'raw']) {
      const link = el('a', '', `${humanize(level)} WAV ↓`);
      link.href = sourceFor(clip, level); link.download = `${clip.id}-${level}.wav`;
      link.setAttribute('aria-label', `Download ${clip.title}, ${level} WAV`);
      downloads.append(link);
    }
    content.append(downloads); details.append(content); card.append(details);

    const noteDetails = el('details', 'clip-notes');
    const noteSummary = el('summary', 'note-toggle');
    const saved = el('span', 'saved-state');
    noteSummary.append(el('span', '', 'Your listening note'), saved);
    const noteContent = el('div', 'note-content');
    const field = el('fieldset', 'rating-field');
    field.setAttribute('aria-describedby', title.id);
    field.append(el('legend', '', 'Does this sound earn its place?'));
    const ratingButtons = [];
    const initialNote = notes[clip.id] || {};
    for (const value of ['keep', 'revise', 'pass']) {
      const button = el('button', 'rating-button', humanize(value));
      button.type = 'button';
      button.setAttribute('aria-pressed', String(initialNote.rating === value));
      button.addEventListener('click', () => {
        const old = notes[clip.id] || {};
        const next = old.rating === value ? '' : value;
        notes[clip.id] = { ...old, rating: next, updatedAt: new Date().toISOString() };
        ratingButtons.forEach(item => item.button.setAttribute('aria-pressed', String(item.value === next)));
        saveNotes();
      });
      ratingButtons.push({ button, value }); field.append(button);
    }
    const textareaId = `note-${clip.id}`;
    const noteLabel = el('label', 'note-label', 'What do you hear? What would you change?');
    noteLabel.htmlFor = textareaId;
    noteLabel.id = `note-label-${clip.id}`;
    const textarea = el('textarea');
    textarea.id = textareaId; textarea.rows = 3; textarea.maxLength = 6000;
    textarea.setAttribute('aria-labelledby', `${title.id} ${noteLabel.id}`);
    textarea.placeholder = 'Attack, body, movement, decay…';
    textarea.value = typeof initialNote.text === 'string' ? initialNote.text : '';
    textarea.addEventListener('input', () => {
      notes[clip.id] = { ...(notes[clip.id] || {}), text: textarea.value, updatedAt: new Date().toISOString() };
      saveNotes();
    });
    const localMessage = el('p', 'local-note');
    noteContent.append(field, noteLabel, textarea, localMessage);
    noteDetails.append(noteSummary, noteContent); card.append(noteDetails);

    const entry = { clip, card, play, audio, download, saved, localMessage };
    audioEntries.push(entry);
    play.addEventListener('click', () => {
      if (audio.paused) audio.play().catch(() => announce(`Could not play ${clip.title}. Try its native audio controls or download the WAV.`));
      else audio.pause();
    });
    audio.addEventListener('play', () => {
      for (const other of audioEntries) if (other !== entry) { other.audio.pause(); setEntryState(other, false); }
      activeEntry = entry; setEntryState(entry, true); updateDeck();
      announce(`Playing ${clip.title}, ${mode} level.`);
    });
    audio.addEventListener('pause', () => { setEntryState(entry, false); if (activeEntry === entry) updateDeck(); });
    audio.addEventListener('ended', () => { setEntryState(entry, false); if (activeEntry === entry) { activeEntry = null; updateDeck(); } });
    audio.addEventListener('timeupdate', () => { if (activeEntry === entry) document.getElementById('deck-time').textContent = duration(audio.currentTime); });
    audio.addEventListener('error', () => {
      if (activeEntry === entry) { setEntryState(entry, false); updateDeck(); }
      announce(`Audio unavailable for ${clip.title}. Try downloading its WAV.`);
    });
    return card;
  }

  function setMode(next) {
    if (mode === next) return;
    const generation = ++modeGeneration;
    const previousActive = activeEntry;
    const wasPlaying = previousActive && !previousActive.audio.paused;
    const resumeTime = previousActive ? previousActive.audio.currentTime : 0;
    mode = next;
    for (const button of document.querySelectorAll('[data-mode]')) button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
    for (const id of ['deck-mode', 'transport-mode']) document.getElementById(id).textContent = mode.toUpperCase();
    document.getElementById('mode-explanation').textContent = mode === 'matched' ? 'Matched files use the same loudest-200-ms target so you can compare timbre.' : 'Raw files keep one common engine gain. Differences in level and decay are preserved.';
    for (const entry of audioEntries) {
      entry.audio.pause();
      entry.audio.src = sourceFor(entry.clip, mode);
      entry.audio.setAttribute('aria-label', `${entry.clip.title} audio, ${mode} level`);
      entry.download.href = sourceFor(entry.clip, mode);
      entry.download.download = `${entry.clip.id}-${mode}.wav`;
      entry.download.setAttribute('aria-label', `Download ${entry.clip.title}, ${mode} WAV`);
      setEntryState(entry, false);
    }
    if (previousActive) {
      activeEntry = previousActive;
      const restore = () => {
        if (generation !== modeGeneration || activeEntry !== previousActive) return;
        if (Number.isFinite(previousActive.audio.duration)) previousActive.audio.currentTime = Math.min(resumeTime, Math.max(0, previousActive.audio.duration - 0.05));
        if (wasPlaying) previousActive.audio.play().catch(() => { updateDeck(); announce('Playback level changed. Press play to resume.'); });
        else updateDeck();
      };
      previousActive.audio.addEventListener('loadedmetadata', restore, { once: true });
      previousActive.audio.load();
    }
    updateDeck();
    announce(`Playback changed to ${mode} level.`);
  }

  for (const button of document.querySelectorAll('[data-mode]')) button.addEventListener('click', () => setMode(button.dataset.mode));
  document.getElementById('stop-all').addEventListener('click', stopAll);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') stopAll(); });
  document.getElementById('export-notes').addEventListener('click', () => {
    const feedback = {
      audition: 'OVERLAP / SnipSnap second listen',
      auditionRevision: 2,
      exportedAt: new Date().toISOString(),
      playbackMode: mode,
      renderVersion: manifest && (manifest.modelVersion || manifest.version) || null,
      renderedAt: manifest && (manifest.renderedAt || manifest.generatedAt || manifest.generated_at) || null,
      sourceRevision: manifest && manifest.sourceRevision || null,
      notes: audioEntries.filter(({ clip }) => notes[clip.id] && (notes[clip.id].rating || (notes[clip.id].text && notes[clip.id].text.trim()))).map(({ clip }) => ({ id: clip.id, title: clip.title, group: clip.group, ...notes[clip.id] }))
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(feedback, null, 2) + '\n'], { type: 'application/json' }));
    const link = document.createElement('a'); link.href = url; link.download = 'snipsnap-overlap-second-listen-notes.json';
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    announce('Listening notes exported. Nothing was sent to a server.');
  });

  async function load() {
    try {
      const response = await fetch('assets/manifest.json');
      if (!response.ok) throw new Error('The audio manifest is unavailable.');
      manifest = await response.json();
      const clips = Array.isArray(manifest) ? manifest : manifest.clips || manifest.samples || [];
      if (!clips.length) throw new Error('There are no audio clips in this session.');
      const availableGroups = [...new Set(clips.map(clip => clip.group || 'musical'))];
      const groups = [...groupOrder.filter(key => availableGroups.includes(key)), ...availableGroups.filter(key => !groupOrder.includes(key))];
      let index = 0;
      for (const group of groups) {
        const info = groupInfo[group] || { number: String(groups.indexOf(group) + 1).padStart(2, '0'), title: humanize(group), description: '' };
        const section = el('section', 'clip-group'); section.id = `group-${group}`; section.setAttribute('aria-labelledby', `heading-${group}`);
        const heading = el('div', 'group-heading');
        const name = el('div', 'group-name');
        const groupTitle = el('h3', '', info.title); groupTitle.id = `heading-${group}`;
        name.append(el('span', '', info.number), groupTitle);
        heading.append(name, el('p', 'group-description', info.description)); section.append(heading);
        const grid = el('div', 'clip-grid');
        for (const clip of clips.filter(item => (item.group || 'musical') === group)) grid.append(buildClip(clip, index++));
        section.append(grid); clipsRoot.append(section);
      }
      document.getElementById('loading').hidden = true;
      for (const anchor of document.querySelectorAll('.section-nav a')) if (!document.querySelector(anchor.getAttribute('href'))) anchor.hidden = true;
      const bundle = manifest.bundle || manifest.downloadZip || manifest.download_zip;
      if (bundle) { const link = document.getElementById('bundle-link'); link.href = typeof bundle === 'string' ? bundle : bundle.url || bundle.path; link.hidden = false; }
      updateNotesCount();
      announce(`${clips.length} sounds ready. Matched playback level selected.`);
    } catch (error) {
      const loading = document.getElementById('loading');
      loading.className = 'notice'; loading.setAttribute('role', 'alert');
      loading.textContent = 'The audio session could not be loaded. Refresh the page to try again, or download the audio manifest below.';
      console.error('Audition session load failed:', error);
    }
  }
  load();
})();
