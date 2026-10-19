/* Confluence listening notebook. Real recordings, one shared audio transport. */
(() => {
  'use strict';

  const STORAGE_KEY = 'snipsnap-confluence-listening-notes-v1';
  const LEVEL_KEY = 'snipsnap-confluence-listening-level-v1';
  const $ = (id) => document.getElementById(id);
  const audio = $('audio');
  const state = { manifest: null, clips: [], selected: null, group: 'all', level: 'matched', notes: {}, heardRecipes: {}, storageAvailable: true, loadToken: 0 };
  let toastTimer;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function icon(isPause = false) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', isPause ? 'M7 5h4v14H7zm6 0h4v14h-4z' : 'M8 5l11 7-11 7z');
    svg.append(path);
    return svg;
  }

  function time(seconds) {
    if (!Number.isFinite(Number(seconds)) || Number(seconds) < 0) return '0:00';
    const whole = Math.floor(Number(seconds));
    return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
  }

  function safeUrl(path) {
    if (typeof path !== 'string' || !path.trim()) return null;
    try {
      const url = new URL(path, new URL('manifest.json', document.baseURI));
      return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch (_) { return null; }
  }

  function clipAudioUrl(clip, level) {
    const matched = level === 'matched';
    const path = safeUrl(matched ? clip.matchedPath : clip.rawPath);
    if (!path) return null;
    const url = new URL(path);
    const hash = matched ? clip.matchedSha256 : clip.rawSha256;
    if (typeof hash === 'string' && hash) url.searchParams.set('sha256', hash);
    return url.href;
  }

  function addLink(parent, label, path, download = true) {
    const url = safeUrl(path);
    if (!url) return;
    const link = el('a', '', label);
    link.href = url;
    if (download) link.setAttribute('download', '');
    else { link.target = '_blank'; link.rel = 'noopener'; }
    parent.append(link);
  }

  function showToast(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4200);
  }

  function readStorage() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      if (saved && typeof saved === 'object' && !Array.isArray(saved)) {
        for (const [id, value] of Object.entries(saved)) {
          if (!value || typeof value !== 'object') continue;
          state.notes[id] = {
            verdict: ['keep', 'revise', 'pass'].includes(value.verdict) ? value.verdict : '',
            note: typeof value.note === 'string' ? value.note.slice(0, 5000) : '',
            updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : '',
            recipeSha256: typeof value.recipeSha256 === 'string' ? value.recipeSha256 : ''
          };
        }
      }
      state.level = localStorage.getItem(LEVEL_KEY) === 'raw' ? 'raw' : 'matched';
    } catch (_) { state.storageAvailable = false; }
    for (const radio of document.querySelectorAll('input[name="level"]')) radio.checked = radio.value === state.level;
  }

  function noteCount() {
    return state.clips.filter((clip) => state.notes[clip.id]?.verdict || state.notes[clip.id]?.note?.trim()).length;
  }

  function updateNotesSummary() {
    const count = noteCount();
    $('notes-summary').textContent = state.storageAvailable
      ? `${count ? `${count} recording${count === 1 ? '' : 's'} with notes. ` : ''}Your notes stay in this browser until you export them.`
      : 'Browser storage is unavailable. Export your notes before closing this page.';
    const note = state.notes[state.selected?.id];
    const hasNote = note && (note.verdict || note.note?.trim());
    const version = !hasNote ? '' : !note.recipeSha256 ? 'Version unknown' :
      state.selected?.recipeSha256 && note.recipeSha256 !== state.selected.recipeSha256 ? 'Earlier version' : '';
    const saved = state.storageAvailable ? 'Saved on this device' : 'Export to keep your notes';
    $('note-save-state').textContent = version ? `${version} · ${saved}` : saved;
  }

  function saveNote() {
    if (!state.selected) return;
    const verdict = document.querySelector('input[name="verdict"]:checked')?.value || '';
    state.notes[state.selected.id] = {
      verdict,
      note: $('listening-note').value,
      updatedAt: new Date().toISOString(),
      recipeSha256: state.heardRecipes[state.selected.id] || state.notes[state.selected.id]?.recipeSha256 || ''
    };
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state.notes)); }
    catch (_) { state.storageAvailable = false; }
    updateNotesSummary();
    updateVerdictIndicators();
  }

  function updateVerdictIndicators() {
    for (const node of document.querySelectorAll('[data-verdict-for]')) {
      const verdict = state.notes[node.dataset.verdictFor]?.verdict;
      node.textContent = verdict || '';
    }
  }

  function exportNotes() {
    const notes = state.clips.filter((clip) => state.notes[clip.id]?.verdict || state.notes[clip.id]?.note?.trim()).map((clip) => ({
      clipId: clip.id,
      title: clip.title,
      profile: clip.profile || null,
      performerCount: clip.performerCount ?? null,
      ...state.notes[clip.id],
      currentRecipeSha256: clip.recipeSha256 || null
    }));
    const result = {
      schemaVersion: 1,
      study: state.manifest?.title || 'Confluence audition',
      manifestSchemaVersion: state.manifest?.schemaVersion || null,
      engineVersion: state.manifest?.engineVersion || null,
      exportedAt: new Date().toISOString(),
      listeningLevelAtExport: state.level,
      notes
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2) + '\n'], { type: 'application/json' }));
    const link = el('a');
    link.href = url;
    link.download = `confluence-listening-notes-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast(`Exported notes for ${notes.length} recording${notes.length === 1 ? '' : 's'}.`);
  }

  function populateNotebook(clip) {
    $('notebook').hidden = false;
    $('notebook-title').textContent = clip.title;
    $('selected-description').textContent = clip.description || '';
    const note = state.notes[clip.id] || {};
    for (const radio of document.querySelectorAll('input[name="verdict"]')) radio.checked = radio.value === note.verdict;
    $('listening-note').value = note.note || '';
    $('selected-links').replaceChildren();
    addLink($('selected-links'), 'Matched audio ↓', clipAudioUrl(clip, 'matched'));
    addLink($('selected-links'), 'Raw audio ↓', clipAudioUrl(clip, 'raw'));
    addLink($('selected-links'), 'Render recipe ↓', clip.recipePath);
    addLink($('selected-links'), 'Diagnostics ↓', clip.diagnosticsPath);
    updateNotesSummary();
  }

  function updatePlayerState() {
    const playing = !audio.paused && !audio.ended;
    $('play-symbol').hidden = playing;
    $('pause-symbol').hidden = !playing;
    $('player-toggle').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} ${state.selected?.title || 'selected recording'}`);
    for (const node of document.querySelectorAll('[data-clip-id]')) {
      const selected = node.dataset.clipId === state.selected?.id;
      node.classList.toggle('is-selected', selected);
      node.classList.toggle('is-playing', selected && playing);
      if (node.classList.contains('featured-clip') || node.classList.contains('role-clip')) {
        node.setAttribute('aria-pressed', String(selected));
        node.setAttribute('aria-label', `${selected && playing ? 'Pause' : 'Play'} ${node.dataset.clipTitle}`);
        node.querySelector('.featured-action').replaceChildren(icon(selected && playing), document.createTextNode(selected && playing ? 'Playing' : 'Listen'));
      } else if (node.classList.contains('clip-row')) {
        const play = node.querySelector('.row-play');
        play.setAttribute('aria-label', `${selected && playing ? 'Pause' : 'Play'} ${node.dataset.clipTitle}`);
        play.replaceChildren(icon(selected && playing));
      }
    }
  }

  function updateTimeline() {
    const duration = Number.isFinite(audio.duration) ? audio.duration : Number(state.selected?.seconds) || 0;
    $('player-elapsed').textContent = time(audio.currentTime);
    $('player-duration').textContent = time(duration);
    $('player-seek').value = duration > 0 ? String(Math.round(audio.currentTime / duration * 1000)) : '0';
    $('player-seek').setAttribute('aria-valuetext', `${time(audio.currentTime)} of ${time(duration)}`);
  }

  async function startPlaying() {
    try { await audio.play(); $('player-status').textContent = `Playing ${state.selected?.title || 'recording'}.`; }
    catch (error) {
      if (error.name !== 'AbortError') {
        $('player-status').textContent = 'Playback could not start. Press play to try again.';
        showToast('Playback could not start. Press play to try again.');
      }
    }
    updatePlayerState();
  }

  function loadAudio(clip, shouldPlay = true, resumeAt = 0) {
    const url = clipAudioUrl(clip, state.level);
    if (!url) { showToast('This recording is missing its audio file.'); return; }
    const token = ++state.loadToken;
    audio.pause();
    audio.src = url;
    $('player-duration').textContent = time(clip.seconds);
    $('player-elapsed').textContent = time(resumeAt);
    $('player-seek').value = '0';
    audio.addEventListener('loadedmetadata', () => {
      if (token !== state.loadToken) return;
      if (resumeAt > 0) audio.currentTime = Math.min(resumeAt, Math.max(0, audio.duration - 0.05));
      updateTimeline();
    }, { once: true });
    if (shouldPlay) startPlaying();
    updatePlayerState();
  }

  function selectClip(clip, shouldPlay = true, toggle = true) {
    if (state.selected?.id === clip.id) {
      if (shouldPlay) {
        if (toggle && !audio.paused) audio.pause();
        else startPlaying();
      }
      return;
    }
    state.selected = clip;
    $('player-dock').hidden = false;
    document.body.classList.add('has-player');
    $('player-title').textContent = clip.title;
    $('player-mode').textContent = state.level === 'matched' ? 'MATCHED LEVEL' : 'RAW RENDER';
    populateNotebook(clip);
    loadAudio(clip, shouldPlay);
  }

  function setLevel(level) {
    if (!['matched', 'raw'].includes(level)) return;
    state.level = level;
    try { localStorage.setItem(LEVEL_KEY, level); } catch (_) { /* Notes still work without persistent preferences. */ }
    $('level-explanation').textContent = level === 'matched'
      ? 'Role clips share the complete gathering’s matched gain; the isolated parts stay quieter. Earlier crowd comparisons match each clip separately.'
      : 'Raw files keep the rendered mix before loudness leveling, with any export peak protection recorded in diagnostics. Role clips share the gathering’s master gain.';
    $('player-mode').textContent = level === 'matched' ? 'MATCHED LEVEL' : 'RAW RENDER';
    if (state.selected) loadAudio(state.selected, !audio.paused, audio.currentTime);
  }

  function renderRoles() {
    const roles = [
      { id: 'low_foundation', label: 'Low foundation.', tag: 'ISOLATED ROLE / LOW', description: 'Hear the sustained lower role on its own.', color: 'low' },
      { id: 'airy_upper', label: 'Airy upper group.', tag: 'ISOLATED ROLE / UPPER', description: 'Hear the recorded breathy upper voices, with the other roles silent.', color: 'airy' },
      { id: 'clear_answer', label: 'Clear answering phrase.', tag: 'ISOLATED ROLE / ANSWER', description: 'A clear phrase answers the upper group. Its opening rest is part of the arrangement.', color: 'answer' },
      { id: 'role_gathering', label: 'The complete gathering.', tag: 'ALL THREE ROLES', description: 'The low foundation, airy upper group and clear answer, together.', color: 'gathering' }
    ];
    const fragment = document.createDocumentFragment();
    for (const [index, role] of roles.entries()) {
      const clip = state.clips.find((item) => item.id === role.id && item.group === 'roles');
      if (!clip) continue;
      const button = el('button', `role-clip role-${role.color}`);
      button.type = 'button';
      button.dataset.clipId = clip.id;
      button.dataset.clipTitle = clip.title;
      button.setAttribute('aria-pressed', 'false');
      button.setAttribute('aria-label', `Play ${clip.title}`);
      const top = el('span', 'role-top');
      top.append(el('span', 'role-index', String(index + 1).padStart(2, '0')), el('span', 'role-tag', role.tag));
      const copy = el('span', 'role-copy');
      const description = el('span', 'role-description', role.description);
      description.id = `role-description-${role.id}`;
      button.setAttribute('aria-describedby', description.id);
      copy.append(el('span', 'role-title', role.label), description);
      const transport = el('span', 'role-transport');
      const action = el('span', 'featured-action');
      action.append(icon(), document.createTextNode('Listen'));
      transport.append(action, el('span', 'role-time', time(clip.seconds)));
      button.append(top, copy, transport);
      button.addEventListener('click', () => selectClip(clip));
      fragment.append(button);
    }
    $('role-clips').replaceChildren(fragment);
    $('roles-unavailable').hidden = $('role-clips').childElementCount > 0;
  }

  function renderFeatured() {
    const fragment = document.createDocumentFragment();
    const counts = [1, 2, 4, 8];
    for (const [index, count] of counts.entries()) {
      const candidates = state.clips.filter((clip) => Number(clip.performerCount) === count);
      const clip = candidates.find((item) => item.group === 'crowd' && /unison/i.test(item.profile || item.title))
        || candidates.find((item) => item.group === 'crowd')
        || candidates.find((item) => /unison/i.test(item.profile || ''))
        || candidates[0];
      if (!clip) continue;
      const button = el('button', 'featured-clip');
      button.type = 'button';
      button.dataset.clipId = clip.id;
      button.dataset.clipTitle = clip.title;
      button.setAttribute('aria-pressed', 'false');
      button.setAttribute('aria-label', `Play ${clip.title}`);
      const top = el('span', 'featured-top');
      top.append(el('span', 'featured-index', `TAKE ${String(index + 1).padStart(2, '0')}`));
      const dots = el('span', 'performer-dots');
      dots.setAttribute('aria-hidden', 'true');
      for (let i = 0; i < count; i++) dots.append(el('i'));
      top.append(dots);
      const bottom = el('span', 'featured-bottom');
      const action = el('span', 'featured-action');
      action.append(icon(), document.createTextNode('Listen'));
      bottom.append(action, el('span', 'featured-length', time(clip.seconds)));
      button.append(top, el('span', 'featured-count', String(count).padStart(2, '0')), el('span', 'featured-label', count === 1 ? 'performer / transformed source' : 'performers / unison'), bottom);
      button.addEventListener('click', () => selectClip(clip));
      fragment.append(button);
    }
    $('featured-clips').replaceChildren(fragment);
  }

  function renderGroups() {
    const manifestGroups = Array.isArray(state.manifest.groups) ? state.manifest.groups : [];
    const groups = [{ id: 'all', title: 'All recordings' }, ...manifestGroups.filter((group) => group.id === 'roles'), ...manifestGroups.filter((group) => group.id !== 'roles')];
    const fragment = document.createDocumentFragment();
    const seen = new Set();
    for (const group of groups) {
      if (!group || typeof group.id !== 'string' || seen.has(group.id)) continue;
      seen.add(group.id);
      const count = group.id === 'all' ? state.clips.length : state.clips.filter((clip) => clip.group === group.id).length;
      const button = el('button', 'group-tab', group.title || group.id);
      button.type = 'button';
      button.setAttribute('aria-pressed', String(state.group === group.id));
      button.dataset.group = group.id;
      button.append(el('span', 'group-count', String(count).padStart(2, '0')));
      if (group.description) button.title = group.description;
      button.addEventListener('click', () => {
        state.group = group.id;
        for (const tab of $('group-nav').querySelectorAll('button')) tab.setAttribute('aria-pressed', String(tab.dataset.group === state.group));
        renderLibrary();
      });
      fragment.append(button);
    }
    $('group-nav').replaceChildren(fragment);
  }

  function renderLibrary() {
    const clips = state.clips.filter((clip) => state.group === 'all' || clip.group === state.group);
    const fragment = document.createDocumentFragment();
    for (const clip of clips) {
      const row = el('article', 'clip-row');
      row.dataset.clipId = clip.id;
      row.dataset.clipTitle = clip.title;
      const play = el('button', 'row-play');
      play.type = 'button';
      play.setAttribute('aria-label', `Play ${clip.title}`);
      play.append(icon());
      play.addEventListener('click', () => selectClip(clip));
      const detail = el('div', 'clip-detail');
      const title = el('button', 'clip-select', clip.title);
      title.type = 'button';
      title.title = 'Select this recording and open its listening note';
      title.addEventListener('click', () => selectClip(clip, false));
      const verdict = el('span', 'verdict-indicator', state.notes[clip.id]?.verdict || '');
      verdict.dataset.verdictFor = clip.id;
      title.append(verdict);
      detail.append(title);
      if (clip.description) detail.append(el('p', 'clip-description', clip.description));
      const count = Number(clip.performerCount);
      const meta = el('div', 'clip-meta');
      meta.append(el('span', '', Number.isFinite(count) && count > 0 ? `${count} ${count === 1 ? 'performer' : 'performers'}` : 'Source recording'), el('br'), el('span', '', String(clip.profile || (clip.group === 'source' ? 'ORIGINAL' : '')).toUpperCase()));
      row.append(play, detail, meta, el('span', 'clip-duration', time(clip.seconds)));
      fragment.append(row);
    }
    $('clip-list').replaceChildren(fragment);
    $('empty-state').hidden = clips.length > 0;
    updatePlayerState();
  }

  function renderCredits() {
    const fragment = document.createDocumentFragment();
    for (const source of Array.isArray(state.manifest.sources) ? state.manifest.sources : []) {
      if (!source || typeof source !== 'object') continue;
      const item = el('li');
      const title = source.title || source.name || source.id || 'VocalSet source';
      const path = source.sourceUrl || source.url || source.originalUrl || source.downloadUrl;
      if (safeUrl(path)) addLink(item, title, path, false);
      else item.append(document.createTextNode(String(title)));
      if (safeUrl(source.path)) {
        item.append(document.createTextNode(' · '));
        addLink(item, 'Source WAV ↓', source.path);
      }
      const file = source.originalFile || source.fileName || source.filename;
      if (file && String(file) !== String(title)) item.append(document.createTextNode(` / ${file}`));
      fragment.append(item);
    }
    $('source-credits').replaceChildren(fragment);
    const versions = [];
    if (state.manifest.engineVersion) versions.push(`Engine ${state.manifest.engineVersion}`);
    if (state.manifest.analysisVersion) versions.push(`Analysis ${state.manifest.analysisVersion}`);
    versions.push(`${state.clips.length} recordings`);
    $('build-meta').textContent = versions.join(' · ');
  }

  function renderDownloads() {
    const kit = state.manifest.renderedKit || {};
    const artifacts = [
      { title: 'Rendered MPC expansion', description: `${kit.pads || 'Selected'} ordinary sample pads · XPN`, path: kit.xpnPath },
      { title: 'Rendered kit files', description: 'Samples and kit exports · ZIP', path: kit.zipPath },
      { title: 'Sources, recipes & diagnostics', description: 'Prototype replay archive · ZIP', path: state.manifest.sourceBundlePath }
    ];
    for (const artifact of artifacts) {
      const url = safeUrl(artifact.path);
      if (!url) continue;
      const link = el('a', 'artifact-link');
      link.href = url;
      link.setAttribute('download', '');
      const text = el('span');
      text.append(el('strong', '', artifact.title), el('span', '', artifact.description));
      link.append(text, el('span', 'artifact-arrow', '↓'));
      $('artifact-links').append(link);
    }
    $('downloads-section').hidden = !$('artifact-links').childElementCount;
  }

  async function loadManifest() {
    try {
      const response = await fetch('manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest request returned ${response.status}`);
      const manifest = await response.json();
      if (!manifest || !Array.isArray(manifest.clips) || manifest.clips.length === 0) throw new Error('The manifest contains no recordings.');
      state.manifest = manifest;
      const ids = new Set();
      state.clips = manifest.clips.filter((clip) => {
        if (!clip || typeof clip.id !== 'string' || ids.has(clip.id) || !safeUrl(clip.rawPath) || !safeUrl(clip.matchedPath)) return false;
        ids.add(clip.id);
        return true;
      }).map((clip) => ({ ...clip, title: typeof clip.title === 'string' ? clip.title : clip.id }));
      if (!state.clips.length) throw new Error('No playable recording paths were found.');
      renderRoles();
      renderFeatured();
      renderGroups();
      renderLibrary();
      renderCredits();
      renderDownloads();
      $('compare-source').hidden = !state.clips.some((clip) => clip.group === 'source');
      updateNotesSummary();
      $('load-status').hidden = true;
    } catch (error) {
      $('load-status').classList.add('is-error');
      $('load-status').textContent = 'The audition recordings could not be loaded. Reload the page to try again, or inspect the manifest below.';
      const link = el('a', '', 'Open manifest');
      link.href = 'manifest.json';
      link.style.marginLeft = '12px';
      $('load-status').append(link);
      $('export-notes').disabled = true;
      console.error('Confluence audition load failed:', error);
    }
  }

  for (const radio of document.querySelectorAll('input[name="level"]')) radio.addEventListener('change', () => setLevel(radio.value));
  for (const radio of document.querySelectorAll('input[name="verdict"]')) radio.addEventListener('change', saveNote);
  $('listening-note').addEventListener('input', saveNote);
  $('export-notes').addEventListener('click', exportNotes);
  $('compare-source').addEventListener('click', () => {
    const sourceTab = $('group-nav').querySelector('[data-group="source"]');
    if (sourceTab) sourceTab.click();
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    $('library-title').scrollIntoView({ behavior: reduced ? 'instant' : 'smooth', block: 'start' });
    $('clip-list').querySelector('.row-play')?.focus({ preventScroll: true });
  });
  $('player-toggle').addEventListener('click', () => {
    if (!state.selected) return;
    if (audio.paused) startPlaying(); else audio.pause();
  });
  $('player-seek').addEventListener('input', () => {
    if (Number.isFinite(audio.duration)) {
      audio.currentTime = Number($('player-seek').value) / 1000 * audio.duration;
      updateTimeline();
    }
  });
  $('open-note').addEventListener('click', () => {
    $('notebook').scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' });
    $('listening-note').focus({ preventScroll: true });
  });
  audio.addEventListener('play', updatePlayerState);
  audio.addEventListener('playing', () => {
    if (state.selected && audio.currentSrc === clipAudioUrl(state.selected, state.level)) {
      state.heardRecipes[state.selected.id] = state.selected.recipeSha256 || '';
    }
  });
  audio.addEventListener('pause', updatePlayerState);
  audio.addEventListener('ended', () => { updatePlayerState(); $('player-status').textContent = 'Recording finished.'; });
  audio.addEventListener('timeupdate', updateTimeline);
  audio.addEventListener('durationchange', updateTimeline);
  audio.addEventListener('error', () => {
    if (!state.selected) return;
    $('player-status').textContent = 'The audio file could not be loaded.';
    showToast('The audio file could not be loaded. Try its download link.');
    updatePlayerState();
  });
  readStorage();
  setLevel(state.level);
  loadManifest();
})();
