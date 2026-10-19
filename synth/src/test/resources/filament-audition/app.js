(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const audio = new Audio();
  audio.preload = 'metadata';
  audio.volume = 0.8;
  const state = {manifest: null, clips: [], groups: [], selected: null, group: 'all', voice: '', query: '', mode: 'native', track: 'mix', loadedUrl: '', notes: {}, generation: 0};
  const descriptions = {
    native: 'Direct engine output. Hear the fixed gain and intended instrument balance.',
    raw: 'Fixed export gain across renders. Compare depletion and energy without leveling it away.',
    matched: 'Listening levels are matched. Compare the tone while reducing loudness differences.'
  };
  const tracks = {
    mix: {name: 'Collected mix', label: 'MIX', help: 'The collected output combines retained sound and its escaping halo.'},
    source: {name: 'Source', label: 'SOURCE', help: 'The original pitched excitation, before its journey through the strands.'},
    through: {name: 'Through', label: 'THROUGH', help: 'The guided sound that remains after traveling through the bend sites.'},
    escaped: {name: 'Escape input', label: 'ESCAPE INPUT', help: 'Sound leaving the guided paths, before the damped halo.'},
    spill: {name: 'Spill', label: 'SPILL', help: 'The escaped sound after the damped halo. Listen for the ordered trail.'},
    seamContext: {name: 'Seam context', label: 'SEAM CONTEXT', help: 'Independently rendered neighboring held periods, supplied to inspect the repeating seam.'}
  };
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const filePath = (file) => typeof file === 'string' ? file : file && (file.url || file.path) || '';
  const clipModel = (clip) => Number(clip.model) || 1;
  const modelName = (clip) => clipModel(clip) >= 2 ? 'REVISED' : 'ORIGINAL';
  const modelLabel = (clip) => `${modelName(clip)} / MODEL ${clipModel(clip)}`;
  const voiceIntents = {THREAD:'A focused root, with upper glass glints that shed quickly.',PULSE:'Articulated odd/even groups, with shifting accents.',CURL:'Upper harmonics bloom after the root, then release slowly.'};
  const characterGroups = ['character','character-presets','character-context','character-sweep-bend','character-sweep-collect','character-mechanism'];
  const groupPriority = (id) => { const index = characterGroups.indexOf(id); return index >= 0 ? index : String(id).startsWith('character') ? characterGroups.length : characterGroups.length + 1; };
  const urlFor = (path) => path ? new URL(path, new URL('./', location.href)).href : '';
  const files = () => state.selected?.files || {};
  const currentPath = () => filePath(files()[state.track === 'mix' ? state.mode : state.track]);
  const visibleClips = () => state.clips.filter((clip) => (state.group === 'all' || clip.group === state.group) && (!state.voice || String(clip.voice) === state.voice) && (!state.query || [clip.title, clip.description, clip.voice, clip.kind, clip.id, modelLabel(clip)].join(' ').toLowerCase().includes(state.query)));
  const noteName = (midi) => Number.isFinite(Number(midi)) ? ['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'][((Math.round(Number(midi)) % 12) + 12) % 12] + (Math.floor(Number(midi) / 12) - 1) : '—';
  const time = (seconds, fine = false) => {
    if (!Number.isFinite(seconds)) return fine ? '0:00.0' : '0:00';
    if (fine) {
      const tenths = Math.max(0, Math.round(seconds * 10));
      return `${Math.floor(tenths / 600)}:${String(Math.floor(tenths / 10) % 60).padStart(2, '0')}.${tenths % 10}`;
    }
    return `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
  };
  const number = (value, digits = 3) => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits).replace(/\.?0+$/, '') : String(value ?? '—');
  function status(message) { $('audio-status').textContent = message; }
  function loadNotes() { try { const saved = JSON.parse(localStorage.getItem('filament-listening-notes-v1') || '{}'); state.notes = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {}; } catch { state.notes = {}; } }
  function saveNote() {
    if (!state.selected) return;
    if ($('notes').value.trim()) state.notes[state.selected.id] = {text: $('notes').value, title: state.selected.title, model:clipModel(state.selected), updatedAt: new Date().toISOString()};
    else delete state.notes[state.selected.id];
    try { localStorage.setItem('filament-listening-notes-v1', JSON.stringify(state.notes)); $('notes-status').textContent = 'Saved in this browser'; }
    catch { $('notes-status').textContent = 'Browser storage unavailable · export to keep'; }
  }
  function drawGroups() {
    const all = [{id:'all', title:'All renders'}, ...state.groups];
    $('group-nav').innerHTML = all.map((group) => `<button type="button" class="group-button${String(group.id).startsWith('character') ? ' featured' : ''}" data-group="${escapeHtml(group.id)}" aria-pressed="${state.group === group.id}">${escapeHtml(group.title)}<small>${group.id === 'all' ? state.clips.length : state.clips.filter((clip) => clip.group === group.id).length}</small></button>`).join('');
  }
  function drawList() {
    const clips = visibleClips();
    $('visible-count').textContent = `${clips.length} ${clips.length === 1 ? 'render' : 'renders'}`;
    if (!clips.length) { $('clip-list').innerHTML = '<p class="clip-no-results">No renders match. Try another collection, voice, or search.</p>'; return; }
    $('clip-list').innerHTML = clips.map((clip) => `<div class="clip-row${state.selected?.id === clip.id ? ' selected' : ''}" data-row="${escapeHtml(clip.id)}"><button type="button" class="clip-select" data-select="${escapeHtml(clip.id)}"${state.selected?.id === clip.id ? ' aria-current="true"' : ''}><span class="clip-row-title">${escapeHtml(clip.title)}</span><span class="clip-row-description">${escapeHtml(clip.description)}</span><span class="clip-row-meta"><span class="row-model${clipModel(clip) >= 2 ? ' revised' : ''}">${escapeHtml(modelLabel(clip))}</span> <span aria-hidden="true">·</span> ${escapeHtml(String(clip.voice || '').toUpperCase())} <span aria-hidden="true">·</span> ${escapeHtml(noteName(clip.midi))}${clip.loop ? ' · HELD' : ''}</span></button><button type="button" class="clip-play" data-play="${escapeHtml(clip.id)}" aria-label="Play ${escapeHtml(clip.title)}, ${escapeHtml(modelLabel(clip))}"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m5 3 8 5-8 5Z" fill="currentColor"/></svg></button></div>`).join('');
    updateSteppers();
  }
  function updateSteppers() {
    const clips = visibleClips();
    const index = clips.findIndex((clip) => clip.id === state.selected?.id);
    $('previous').disabled = index <= 0;
    $('next').disabled = index < 0 || index >= clips.length - 1;
  }
  function drawTracks() {
    $('track-buttons').innerHTML = Object.entries(tracks).filter(([key]) => key === 'mix' || filePath(files()[key])).map(([key, track]) => `<button type="button" class="track-button" data-track="${key}" aria-pressed="${state.track === key}">${track.name}</button>`).join('');
    $('track-label').textContent = state.track === 'mix' ? `${state.mode.toUpperCase()} MIX` : tracks[state.track].label;
    $('track-help').textContent = tracks[state.track].help + (state.track !== 'mix' ? ' Stem playback uses the supplied export gain.' : '');
    const path = currentPath();
    $('wav-download').href = path ? urlFor(path) : '#';
    $('wav-download').hidden = !path;
    $('wav-download').setAttribute('download', path.split('/').pop() || 'filament.wav');
  }
  function drawMacros(clip) {
    const raw = clip.macros || {};
    const entries = Array.isArray(raw) ? raw.map((value, i) => [value.name || ['INJECT','BEND','LENGTH','BUNDLE','COLLECT','HOLD'][i], value.value ?? value]) : Object.entries(raw);
    const lookup = Object.fromEntries(entries.map(([key, value]) => [String(key).toUpperCase(), value]));
    $('macros').innerHTML = ['INJECT','BEND','LENGTH','BUNDLE','COLLECT','HOLD'].map((name) => {
      const value = lookup[name];
      const numeric = Number(value);
      const fill = value == null || !Number.isFinite(numeric) ? 0 : Math.max(0, Math.min(100, numeric * 100));
      return `<div class="macro-item ${name.toLowerCase()}"><span class="macro-name">${name}</span><div class="macro-track" aria-hidden="true"><span class="macro-fill" style="width:${fill}%"></span></div><span class="macro-value">${value == null ? '—' : escapeHtml(number(value, 3))}</span></div>`;
    }).join('');
  }
  function drawMetrics(clip) {
    const m = clip.metrics || {};
    const values = [];
    if (m.durationSeconds != null) values.push(['DURATION', `${number(m.durationSeconds, 2)} s`]);
    if (m.actualHz != null || m.pitchHz != null) values.push(['PITCH', `${number(m.actualHz ?? m.pitchHz, 1)} Hz`]);
    if (m.rawPeak != null) values.push(['RAW PEAK', number(m.rawPeak, 4)]);
    if (m.budget?.error != null) values.push(['BUDGET Δ', Number(m.budget.error).toExponential(1)]);
    if (clip.loop && m.loopConverged != null) values.push(['LOOP', m.loopConverged ? 'converged' : 'not converged']);
    $('metrics').innerHTML = values.map(([label, value]) => `<div class="metric"><span class="metric-label">${label}</span><span class="metric-value">${escapeHtml(value)}</span></div>`).join('');
    $('metrics').hidden = !values.length;
  }
  function selectClip(id, play = false) {
    const clip = state.clips.find((item) => item.id === id);
    if (!clip) return;
    if (state.selected?.id === id) { if (play) playAudio(); return; }
    saveNote();
    audio.pause();
    state.selected = clip;
    state.track = 'mix';
    state.generation++;
    $('clip-empty').hidden = true;
    $('clip-detail').hidden = false;
    $('clip-title').textContent = clip.title || clip.id;
    $('clip-description').textContent = clip.description || '';
    const group = state.groups.find((item) => item.id === clip.group);
    $('clip-group').textContent = group?.title || clip.group || 'Render';
    $('clip-index').textContent = `${String(state.clips.indexOf(clip) + 1).padStart(2, '0')} / ${state.clips.length}`;
    $('clip-tags').innerHTML = `<span class="clip-tag model ${clipModel(clip) >= 2 ? 'revised' : 'original'}">${escapeHtml(modelLabel(clip))}</span><span class="clip-tag voice">${escapeHtml(String(clip.voice || 'FILAMENT').toUpperCase())}</span><span class="clip-tag">${escapeHtml(noteName(clip.midi))} · MIDI ${escapeHtml(clip.midi ?? '—')}</span><span class="clip-tag">VEL ${escapeHtml(number(clip.velocity))}</span>${clip.loop ? '<span class="clip-tag held">HELD / LOOPABLE</span>' : ''}`;
    const partner = state.clips.find((item) => item.id === clip.comparisonPartnerId);
    $('revision-comparison').hidden = !partner;
    $('compare-version').dataset.partnerId = partner?.id || '';
    $('compare-version').firstChild.textContent = partner ? `Hear ${modelName(partner).toLowerCase()} ` : 'Compare version ';
    $('compare-version').setAttribute('aria-label', partner ? `Hear ${modelName(partner).toLowerCase()}: ${partner.title}` : 'Compare version');
    const intent = voiceIntents[String(clip.voice || '').toUpperCase()];
    $('voice-intent').hidden = !intent || (clipModel(clip) < 2 && !partner);
    $('voice-intent').textContent = intent ? `Listening intent · ${intent}` : '';
    const failures = Array.isArray(clip.failures) ? clip.failures : clip.failures ? [clip.failures] : [];
    const warn = failures.length ? `Render notes: ${failures.map((entry) => typeof entry === 'string' ? entry : JSON.stringify(entry)).join(' · ')}` : '';
    $('clip-warning').hidden = !warn;
    $('clip-warning').textContent = warn;
    $('loop').checked = Boolean(clip.loop);
    audio.loop = $('loop').checked;
    drawMacros(clip);
    drawMetrics(clip);
    $('notes').value = state.notes[clip.id]?.text || '';
    $('notes-status').textContent = 'Saved in this browser';
    for (const type of ['recipe','diagnostics']) {
      $(`${type}-details`).open = false;
      $(`${type}-content`).textContent = `Open to load ${type}.`;
      $(`${type}-details`).dataset.loaded = '';
      const path = filePath(clip.files?.[type]);
      $(`${type}-details`).hidden = !path;
      $(`${type}-download`).href = path ? urlFor(path) : '#';
    }
    drawTracks();
    drawList();
    loadAudio({play});
    try { history.replaceState(null, '', `#clip=${encodeURIComponent(clip.id)}`); } catch { /* Playback remains usable without URL state. */ }
  }
  function showPlayState(playing) {
    $('play').setAttribute('aria-label', `${playing ? 'Pause' : 'Play'} ${state.selected?.title || 'selected clip'}`);
    $('play-icon').innerHTML = playing ? '<path d="M7 5h4v14H7zM15 5h4v14h-4z" fill="currentColor"/>' : '<path d="m9 5 11 7-11 7Z" fill="currentColor"/>';
  }
  function updateTime() {
    const duration = Number.isFinite(audio.duration) ? audio.duration : state.selected?.metrics?.durationSeconds || 0;
    const current = audio.currentTime || 0;
    const progress = duration > 0 ? Math.min(1000, current / duration * 1000) : 0;
    $('seek').value = String(progress);
    $('seek').style.setProperty('--progress', `${progress / 10}%`);
    const fine = duration > 0 && duration < 10;
    $('seek').setAttribute('aria-valuetext', `${time(current, fine)} of ${time(duration, fine)}`);
    $('audio-time').textContent = `${time(current, fine)} / ${time(duration, fine)}`;
  }
  function loadAudio({play = false, keepTime = false} = {}) {
    const path = currentPath();
    const previousTime = keepTime ? audio.currentTime || 0 : 0;
    audio.pause();
    $('play').disabled = !path;
    $('seek').disabled = true;
    if (!path) { status(`No ${state.mode} file was supplied for this render.`); audio.removeAttribute('src'); state.loadedUrl = ''; updateTime(); return; }
    state.loadedUrl = urlFor(path);
    audio.src = state.loadedUrl;
    if (previousTime) audio.addEventListener('loadedmetadata', () => { if (audio.duration > 0) audio.currentTime = Math.min(previousTime, Math.max(0, audio.duration - .05)); }, {once: true});
    audio.load();
    status('Loading audio…');
    updateTime();
    if (play) playAudio();
  }
  async function playAudio() {
    if (!currentPath()) return;
    try { await audio.play(); }
    catch (error) { status(error.name === 'NotAllowedError' ? 'Press play to begin listening.' : `Playback unavailable: ${error.message || 'could not load the audio file'}`); showPlayState(false); }
  }
  function switchTrack(track) {
    if (!tracks[track] || (track !== 'mix' && !filePath(files()[track]))) return;
    const playing = !audio.paused;
    state.track = track;
    drawTracks();
    loadAudio({play: playing, keepTime: true});
  }
  function changeMode(mode) {
    if (!descriptions[mode]) return;
    state.mode = mode;
    document.querySelectorAll('[data-mode]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.mode === mode)));
    $('mode-description').textContent = descriptions[mode];
    if (state.selected) {
      const playing = !audio.paused;
      drawTracks();
      if (state.track === 'mix') loadAudio({play: playing, keepTime: true});
    }
  }
  async function loadDetail(type) {
    const detail = $(`${type}-details`);
    if (!detail.open || detail.dataset.loaded || !state.selected) return;
    const id = state.selected.id;
    const path = filePath(files()[type]);
    detail.dataset.loaded = 'loading';
    $(`${type}-content`).textContent = 'Loading…';
    try {
      const response = await fetch(urlFor(path));
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const body = await response.text();
      let formatted = body;
      try { formatted = JSON.stringify(JSON.parse(body), null, 2); } catch { /* A plain-text recipe is also useful. */ }
      if (state.selected?.id !== id) return;
      $(`${type}-content`).textContent = formatted;
      detail.dataset.loaded = 'yes';
    } catch (error) {
      if (state.selected?.id !== id) return;
      $(`${type}-content`).textContent = `Could not load this file (${error.message}). Use the download link to retry.`;
      detail.dataset.loaded = '';
    }
  }
  function step(direction) {
    const clips = visibleClips();
    const index = clips.findIndex((clip) => clip.id === state.selected?.id);
    if (index >= 0 && clips[index + direction]) { const playing = !audio.paused; selectClip(clips[index + direction].id, playing); document.querySelector('[data-row].selected')?.scrollIntoView({block:'nearest'}); }
  }
  $('group-nav').addEventListener('click', (event) => {
    const button = event.target.closest('[data-group]');
    if (!button) return;
    state.group = button.dataset.group;
    drawGroups(); drawList(); $('clip-list').scrollTop = 0;
  });
  $('clip-list').addEventListener('click', (event) => {
    const play = event.target.closest('[data-play]');
    const select = event.target.closest('[data-select]');
    if (play) selectClip(play.dataset.play, true);
    else if (select) selectClip(select.dataset.select);
  });
  document.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => changeMode(button.dataset.mode)));
  $('track-buttons').addEventListener('click', (event) => { const button = event.target.closest('[data-track]'); if (button) switchTrack(button.dataset.track); });
  $('search').addEventListener('input', () => { state.query = $('search').value.trim().toLowerCase(); drawList(); });
  $('voice-filter').addEventListener('change', () => { state.voice = $('voice-filter').value; drawList(); });
  $('play').addEventListener('click', () => audio.paused ? playAudio() : audio.pause());
  $('compare-version').addEventListener('click', () => {
    const partner = state.clips.find((clip) => clip.id === $('compare-version').dataset.partnerId);
    if (!partner) return;
    if (!visibleClips().some((clip) => clip.id === partner.id)) {
      state.group = partner.group || 'all'; state.voice = ''; state.query = '';
      $('search').value = ''; $('voice-filter').value = ''; drawGroups();
    }
    selectClip(partner.id, true);
  });
  $('previous').addEventListener('click', () => step(-1));
  $('next').addEventListener('click', () => step(1));
  $('seek').addEventListener('input', () => { if (Number.isFinite(audio.duration)) audio.currentTime = Number($('seek').value) / 1000 * audio.duration; updateTime(); });
  $('volume').addEventListener('input', () => { audio.volume = Number($('volume').value); });
  $('loop').addEventListener('change', () => { audio.loop = $('loop').checked; });
  $('notes').addEventListener('input', saveNote);
  for (const type of ['recipe','diagnostics']) $(`${type}-details`).addEventListener('toggle', () => loadDetail(type));
  $('export-notes').addEventListener('click', () => {
    saveNote();
    const data = {project:'Filament / SnipSnap',exportedAt:new Date().toISOString(),notes:state.notes};
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], {type:'application/json'}));
    const link = document.createElement('a'); link.href = url; link.download = 'filament-listening-notes.json'; document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  audio.addEventListener('loadedmetadata', () => { $('seek').disabled = !Number.isFinite(audio.duration); updateTime(); if (audio.paused) status('Ready when you are.'); });
  audio.addEventListener('canplay', () => { if (audio.paused) status('Ready when you are.'); });
  audio.addEventListener('play', () => { showPlayState(true); status('Playing'); });
  audio.addEventListener('pause', () => { showPlayState(false); if (!audio.ended) status('Paused'); });
  audio.addEventListener('ended', () => { showPlayState(false); status('Finished · play again or choose another render.'); });
  audio.addEventListener('waiting', () => { status('Buffering…'); });
  audio.addEventListener('playing', () => { status('Playing'); });
  audio.addEventListener('timeupdate', updateTime);
  audio.addEventListener('error', () => { showPlayState(false); status('Could not load this audio file. Try another render or download the WAV.'); });
  document.addEventListener('keydown', (event) => {
    if (event.code !== 'Space' || event.repeat || /^(INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A)$/.test(document.activeElement?.tagName || '') || !state.selected) return;
    event.preventDefault(); audio.paused ? playAudio() : audio.pause();
  });
  window.addEventListener('pagehide', () => { audio.pause(); });
  async function init() {
    loadNotes();
    try {
      const response = await fetch('./manifest.json');
      if (!response.ok) throw new Error(`Manifest returned HTTP ${response.status}`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('No audio clips were supplied in the render manifest.');
      state.manifest = manifest;
      state.clips = [...manifest.clips].sort((a, b) => {
        const groupOrder = groupPriority(a.group) - groupPriority(b.group);
        if (groupOrder) return groupOrder;
        if (a.group === 'character' && b.group === 'character') {
          const voices = ['THREAD','PULSE','CURL'];
          return voices.indexOf(a.voice) - voices.indexOf(b.voice) || clipModel(b) - clipModel(a);
        }
        return 0;
      });
      if (filePath(manifest.download)) document.querySelectorAll('[data-pack-download]').forEach((link) => { link.href = urlFor(filePath(manifest.download)); });
      if (manifest.packScope?.description) $('pack-description').textContent = manifest.packScope.description;
      state.groups = (Array.isArray(manifest.groups) ? manifest.groups : []).map((group) => typeof group === 'string' ? {id: group, title: group} : group);
      const known = new Set(state.groups.map((group) => group.id));
      for (const clip of state.clips) if (clip.group && !known.has(clip.group)) { state.groups.push({id:clip.group,title:clip.group}); known.add(clip.group); }
      state.groups.sort((a, b) => groupPriority(a.id) - groupPriority(b.id));
      $('render-count').textContent = `${state.clips.length} RENDERED CLIPS`;
      const voices = [...new Set(state.clips.map((clip) => clip.voice).filter(Boolean))];
      $('voice-filter').innerHTML = '<option value="">All voices</option>' + voices.map((voice) => `<option value="${escapeHtml(voice)}">${escapeHtml(String(voice).toUpperCase())}</option>`).join('');
      drawGroups();
      let initialId = '';
      try { initialId = new URLSearchParams(location.hash.slice(1)).get('clip') || ''; } catch { /* Use the first render. */ }
      const initialClip = state.clips.find((clip) => clip.id === initialId)
        || state.clips.find((clip) => clip.id === 'character_thread_revised')
        || state.clips.find((clip) => clipModel(clip) === 2 && clip.group === 'character')
        || state.clips.find((clip) => clipModel(clip) === 2)
        || state.clips[0];
      if (initialClip.group === 'character') state.group = 'character';
      drawGroups();
      selectClip(initialClip.id);
      $('workstation').setAttribute('aria-busy', 'false');
    } catch (error) {
      $('load-error').textContent = `The listening bench could not load: ${error.message} Reload to retry, or download the listening pack above.`;
      $('load-error').hidden = false;
      $('clip-list').innerHTML = '<p class="clip-no-results">Render manifest unavailable.</p>';
      $('clip-empty').innerHTML = '<span class="empty-orbit" aria-hidden="true">∿</span><p>Audio collection unavailable.</p>';
      $('render-count').textContent = 'COLLECTION UNAVAILABLE';
      $('workstation').setAttribute('aria-busy', 'false');
    }
  }
  init();
})();
