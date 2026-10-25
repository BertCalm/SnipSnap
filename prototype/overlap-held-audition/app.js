(() => {
  'use strict';
  const storageKey = 'snipsnap-overlap-held-proof-v1';
  const groups = {
    loops: { number: '01', title: 'Let the body return.', description: 'Four settled loop bodies, each repeated eight times. Compare their steady character and listen through every return.' },
    release: { number: '02', title: 'Take the drive away.', description: 'Four notes held for about 2.5 seconds, then drive withdrawn over 0.25 seconds. Listen to the network’s release and decay.' }
  };
  const entries = [];
  let mode = 'matched';
  let manifest = null;
  let active = null;
  let generation = 0;
  let storageWorks = true;
  let notes = {};
  try { notes = JSON.parse(localStorage.getItem(storageKey) || '{}'); if (!notes || typeof notes !== 'object' || Array.isArray(notes)) notes = {}; }
  catch (_) { storageWorks = false; notes = {}; }

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function say(text) { document.getElementById('announcement').textContent = text; }
  function seconds(value) { return Number.isFinite(Number(value)) ? `${Number(value).toFixed(2)} s` : 'WAV'; }
  function titleCase(value) { return value.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]/g, ' ').replace(/^./, letter => letter.toUpperCase()); }
  function valueText(value) {
    if (value === null) return 'n/a';
    if (Array.isArray(value)) return value.map(valueText).join(', ');
    if (typeof value === 'object') return Object.entries(value).map(([key, item]) => `${titleCase(key)}: ${valueText(item)}`).join(' · ');
    if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(7)));
    return String(value);
  }
  function dataList(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return node('p', '', valueText(value));
    const list = node('dl');
    Object.entries(value).forEach(([key, item]) => list.append(node('dt', '', titleCase(key)), node('dd', '', valueText(item))));
    return list;
  }
  function link(text, href, filename) {
    const element = node('a', '', text); element.href = href;
    if (filename) element.download = filename;
    return element;
  }
  function audioPath(clip, level) {
    const value = clip[level];
    return typeof value === 'string' ? value : value && (value.path || value.url || value.file) || `assets/audio/${clip.id}-${level}.wav`;
  }
  function updateTransport() {
    const playing = active && !active.audio.paused && !active.audio.ended;
    document.body.classList.toggle('is-playing', !!playing);
    document.getElementById('transport-state').textContent = playing ? 'NOW PLAYING' : active ? 'PAUSED' : 'STEADY HELD PROOF';
    document.getElementById('transport-title').textContent = active ? active.clip.title : 'Choose a sound to begin';
  }
  function stopAll() {
    generation++;
    entries.forEach(entry => { entry.audio.pause(); entry.audio.currentTime = 0; entry.card.classList.toggle('is-active', false); });
    active = null; updateTransport(); say('All audio stopped.');
  }
  function saveNotes() {
    try { localStorage.setItem(storageKey, JSON.stringify(notes)); storageWorks = true; }
    catch (_) { storageWorks = false; }
    updateNoteStatus();
  }
  function updateNoteStatus() {
    const count = entries.filter(({ clip }) => notes[clip.id] && (notes[clip.id].rating || notes[clip.id].text && notes[clip.id].text.trim())).length;
    document.getElementById('notes-count').textContent = count ? `${count} clip${count === 1 ? '' : 's'} with notes.` : 'No notes yet.';
    entries.forEach(entry => {
      const value = notes[entry.clip.id];
      entry.saved.textContent = value && (value.rating || value.text && value.text.trim()) ? storageWorks ? 'Saved here' : 'In this tab' : '';
      entry.local.textContent = storageWorks ? 'Saved in this browser. Not sent anywhere.' : 'Browser storage is unavailable. Export before closing this tab.';
    });
  }
  function loadJsonDetails(details, content, path, label) {
    let requested = false;
    details.addEventListener('toggle', async () => {
      if (!details.open || requested) return;
      requested = true; content.textContent = 'Loading…';
      try {
        const response = await fetch(path);
        if (!response.ok) throw new Error('Unavailable JSON');
        const value = await response.json();
        content.replaceChildren(dataList(value));
      } catch (_) { content.textContent = `${label} could not be loaded here. Use its download link to try directly.`; requested = false; }
    });
  }
  function makeClip(clip, index) {
    const card = node('article', 'clip'); card.id = `clip-${clip.id}`;
    const heading = node('div', 'clip-head');
    const name = node('div');
    const reference = [clip.topology, clip.note || 'C3 reference'].filter(Boolean).join(' / ');
    name.append(node('div', 'clip-label', `TAKE ${String(index + 1).padStart(2, '0')} · ${reference}`));
    const title = node('h4', '', clip.title || clip.id); title.id = `title-${clip.id}`; card.setAttribute('aria-labelledby', title.id);
    name.append(title); heading.append(name, node('span', 'clip-time', seconds(clip.seconds)));
    card.append(heading, node('p', 'clip-description', clip.description || 'Listen to the sustained body and its decay.'));
    const surface = node('div', 'audio-surface');
    const audio = document.createElement('audio'); audio.controls = true; audio.preload = 'none'; audio.src = audioPath(clip, mode);
    audio.setAttribute('aria-label', `${clip.title}, ${mode} audio`);
    audio.append(document.createTextNode('Your browser does not support audio. Download the WAV below.'));
    surface.append(audio); card.append(surface);
    const links = node('div', 'clip-links');
    const currentDownload = link(`${mode.toUpperCase()} WAV ↓`, audioPath(clip, mode), `${clip.id}-${mode}.wav`);
    currentDownload.setAttribute('aria-label', `Download ${clip.title}, ${mode} WAV`); links.append(currentDownload);
    if (clip.loop) links.append(link('Native loop WAV ↓', clip.loop, `${clip.id}-body.wav`));
    if (typeof clip.recipe === 'string') links.append(link('Recipe JSON ↓', clip.recipe, `${clip.id}-recipe.json`));
    if (typeof clip.diagnostics === 'string') links.append(link('Diagnostics ↓', clip.diagnostics, `${clip.id}-diagnostics.json`));
    card.append(links);

    const metrics = node('details', 'clip-details'); metrics.append(node('summary', '', 'Measured output'));
    const metricsContent = node('div', 'details-content'); metricsContent.append(dataList(clip.metrics || {}));
    if (clip.hashes) { metricsContent.append(node('h5', '', 'SHA-256'), dataList(clip.hashes)); }
    metrics.append(metricsContent); card.append(metrics);
    if (clip.recipe) {
      const details = node('details', 'clip-details'); details.append(node('summary', '', 'Prototype recipe (not PadRecipe)'));
      const content = node('div', 'details-content'); content.append(node('p', '', 'Experimental proof recipe; not an app PadRecipe.'));
      if (typeof clip.recipe === 'string') loadJsonDetails(details, content, clip.recipe, 'The recipe');
      else content.append(dataList(clip.recipe));
      details.append(content); card.append(details);
    }

    const noteDetails = node('details', 'clip-notes'); const summary = node('summary');
    const saved = node('span', 'note-saved'); summary.append(node('span', '', 'Your listening note'), saved);
    const content = node('div', 'note-content'); const field = node('fieldset', 'rating-field');
    field.setAttribute('aria-describedby', title.id); field.append(node('legend', '', 'Does this body earn another listen?'));
    const old = notes[clip.id] || {}; const ratingButtons = [];
    ['keep', 'revise', 'pass'].forEach(rating => {
      const button = node('button', '', titleCase(rating)); button.type = 'button'; button.setAttribute('aria-pressed', String(old.rating === rating));
      button.addEventListener('click', () => {
        const previous = notes[clip.id] || {}; const next = previous.rating === rating ? '' : rating;
        notes[clip.id] = { ...previous, rating: next, updatedAt: new Date().toISOString() };
        ratingButtons.forEach(item => item.button.setAttribute('aria-pressed', String(item.rating === next))); saveNotes();
      });
      ratingButtons.push({ button, rating }); field.append(button);
    });
    const label = node('label', 'note-label', 'Body, repetition, release: what do you hear?'); label.id = `note-label-${clip.id}`; label.htmlFor = `note-${clip.id}`;
    const textarea = node('textarea'); textarea.id = `note-${clip.id}`; textarea.rows = 3; textarea.maxLength = 6000;
    textarea.setAttribute('aria-labelledby', `${title.id} ${label.id}`); textarea.value = typeof old.text === 'string' ? old.text : '';
    textarea.placeholder = 'Character, clicks, pumping, how it lets go…';
    textarea.addEventListener('input', () => { notes[clip.id] = { ...(notes[clip.id] || {}), text: textarea.value, updatedAt: new Date().toISOString() }; saveNotes(); });
    const local = node('p', 'local-note'); content.append(field, label, textarea, local); noteDetails.append(summary, content); card.append(noteDetails);
    const entry = { clip, card, audio, currentDownload, saved, local }; entries.push(entry);
    audio.addEventListener('play', () => {
      entries.forEach(other => { if (other !== entry) { other.audio.pause(); other.card.classList.toggle('is-active', false); } });
      active = entry; entry.card.classList.toggle('is-active', true); updateTransport(); say(`Playing ${clip.title}, ${mode} level.`);
    });
    audio.addEventListener('pause', () => { entry.card.classList.toggle('is-active', false); if (active === entry) updateTransport(); });
    audio.addEventListener('ended', () => { entry.card.classList.toggle('is-active', false); if (active === entry) { active = null; updateTransport(); } });
    audio.addEventListener('error', () => { if (active === entry) updateTransport(); say(`Audio unavailable for ${clip.title}. Try the WAV download.`); });
    return card;
  }
  function setMode(next) {
    if (next === mode) return;
    const token = ++generation; const previous = active;
    const resume = previous && !previous.audio.paused; const position = previous ? previous.audio.currentTime : 0;
    mode = next;
    document.querySelectorAll('[data-mode]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.mode === mode)));
    document.getElementById('transport-mode').textContent = mode.toUpperCase();
    document.getElementById('mode-explanation').textContent = mode === 'matched' ? 'MATCHED shares one gain per topology’s loop/release pair. The louder loudest-200-ms RMS targets 0.03, with a 0.90 peak ceiling. NATIVE shares one only-down safety gain across the whole proof.' : 'NATIVE is the prototype rendering, with one only-down safety gain shared across the whole collection. The manifest records that gain. Matched versions use a gain shared by each topology’s loop/release pair.';
    entries.forEach(entry => {
      entry.audio.pause(); entry.audio.src = audioPath(entry.clip, mode); entry.audio.setAttribute('aria-label', `${entry.clip.title}, ${mode} audio`);
      entry.currentDownload.href = audioPath(entry.clip, mode); entry.currentDownload.download = `${entry.clip.id}-${mode}.wav`;
      entry.currentDownload.textContent = `${mode.toUpperCase()} WAV ↓`; entry.currentDownload.setAttribute('aria-label', `Download ${entry.clip.title}, ${mode} WAV`);
      entry.card.classList.toggle('is-active', false);
    });
    if (previous) {
      active = previous;
      previous.audio.addEventListener('loadedmetadata', () => {
        if (token !== generation || active !== previous) return;
        if (Number.isFinite(previous.audio.duration)) previous.audio.currentTime = Math.min(position, Math.max(0, previous.audio.duration - .01));
        if (resume) previous.audio.play().catch(() => { updateTransport(); say('Level changed. Press play to resume.'); });
        else updateTransport();
      }, { once: true });
      previous.audio.load();
    }
    updateTransport(); say(`Playback changed to ${mode}.`);
  }
  document.querySelectorAll('[data-mode]').forEach(button => button.addEventListener('click', () => setMode(button.dataset.mode)));
  document.getElementById('stop-all').addEventListener('click', stopAll);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') stopAll(); });
  document.getElementById('export-notes').addEventListener('click', () => {
    const result = { audition: 'OVERLAP / isolated steady held proof', prototypeVersion: manifest && manifest.prototypeVersion || 1,
      engineModelUnchanged: manifest && manifest.engineModelUnchanged || 2, exportedAt: new Date().toISOString(), playbackMode: mode,
      source: manifest && manifest.source || null,
      notes: entries.filter(({ clip }) => notes[clip.id] && (notes[clip.id].rating || notes[clip.id].text && notes[clip.id].text.trim())).map(({ clip }) => ({ id: clip.id, title: clip.title, topology: clip.topology, group: clip.group, ...notes[clip.id] })) };
    const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2) + '\n'], { type: 'application/json' }));
    const download = link('', url, 'snipsnap-overlap-held-proof-notes.json'); document.body.append(download); download.click(); download.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000); say('Notes exported. Nothing was sent to a server.');
  });

  async function load() {
    try {
      const response = await fetch('assets/manifest.json'); if (!response.ok) throw new Error('Manifest unavailable');
      manifest = await response.json(); const clips = manifest.clips;
      if (!Array.isArray(clips) || !clips.length) throw new Error('No clips');
      let index = 0; const root = document.getElementById('clips');
      for (const [key, group] of Object.entries(groups)) {
        const items = clips.filter(clip => clip.group === key); if (!items.length) continue;
        const section = node('section', 'clip-group'); section.id = `group-${key}`; section.setAttribute('aria-labelledby', `heading-${key}`);
        const heading = node('div', 'group-heading'); const name = node('div', 'group-name');
        const title = node('h3', '', group.title); title.id = `heading-${key}`; name.append(node('span', '', group.number), title);
        heading.append(name, node('p', '', group.description)); const grid = node('div', 'clip-grid');
        items.forEach(clip => grid.append(makeClip(clip, index++))); section.append(heading, grid); root.append(section);
      }
      if (!entries.length) throw new Error('No recognized proof groups');
      document.getElementById('loading').hidden = true;
      if (manifest.bundle) document.getElementById('bundle-link').href = manifest.bundle;
      const evidence = document.getElementById('manifest-evidence'); evidence.replaceChildren();
      if (manifest.levelPolicy) evidence.append(node('h3', '', 'Level policy'), dataList(manifest.levelPolicy));
      if (manifest.source) evidence.append(node('h3', '', 'Source'), dataList(manifest.source));
      if (Array.isArray(manifest.notes) && manifest.notes.length) { const list = node('ul'); manifest.notes.forEach(note => list.append(node('li', '', String(note)))); evidence.append(node('h3', '', 'Generation notes'), list); }
      if (!evidence.children.length) evidence.append(node('p', '', 'See the audio manifest and each clip’s diagnostics for generation details.'));
      updateNoteStatus(); say(`${entries.length} proof recordings ready. Matched level selected.`);
    } catch (error) {
      const loading = document.getElementById('loading'); loading.className = 'notice'; loading.setAttribute('role', 'alert');
      loading.textContent = 'The held proof could not be loaded. Refresh to try again, or use the audio manifest below.';
      console.error('Held proof load failed:', error);
    }
  }
  load();
})();
