(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const manifest = window.CAUSTIC_INSTRUMENT_MANIFEST;
  const audio = $('audio');
  const state = { mode: 'matched', active: null, filter: 'all', clips: [], cards: new Map(), request: 0, handoff: null };
  const sections = {
    anchors: ['Start with the instrument', 'Three requested notes, rebuilt by the same native Alloy renderer.'],
    body: ['Two anchors. One new midpoint.', 'BODY 0 follows Preferred Alloy; BODY 1 is the Fuller Body default above. The midpoint is a new listening candidate.'],
    geometry: ['Move the light. Move the pickup.', 'SOURCE changes illumination. FOCUS chooses another place inside the vessel. These are fixed recordings.'],
    length: ['Give the note an ending', 'LENGTH changes actual decay and release. The default above is LENGTH 1.'],
    touch: ['Change the effort', 'Each velocity is rendered separately and remains saved in its patch.'],
    pairs: ['Let the controls meet', 'Two small comparisons for SOURCE × FOCUS and BODY × LENGTH.'],
    presets: ['New starting positions', 'Additional factory patches to audition. Six new positions await your listening verdict.']
  };
  const fmt = (value, digits = 2) => Number(value).toFixed(digits).replace(/\.?0+$/, '');
  const note = midi => ['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'][midi % 12] + (Math.floor(midi / 12) - 1);
  const el = (tag, className, text) => { const e = document.createElement(tag); if (className) e.className = className; if (text !== undefined) e.textContent = text; return e; };
  const path = clip => state.mode === 'matched' ? clip.matched : clip.raw;
  const address = relative => new URL(relative, document.baseURI).href;
  function download(id, target) {
    const element = $(id); element.hidden = !target;
    if (target) { element.href = address(target); element.download = target.split('/').pop(); }
  }
  function updatePlayer() {
    if (!state.active) return;
    const clip = state.active;
    $('player-heading').textContent = clip.title;
    $('playing-description').textContent = clip.description;
    $('playing-meta').textContent = `${note(clip.midi)} · Velocity ${fmt(clip.velocity)} · ${fmt(clip.seconds, 2)} sec · ALLOY`;
    download('playing-patch', clip.recipe);
    download('playing-wav', path(clip));
    state.cards.forEach((card, id) => {
      const selected = id === clip.id;
      card.root.classList.toggle('active', selected);
      card.play.classList.toggle('is-playing', selected && !audio.paused);
      card.play.setAttribute('aria-label', `${selected && !audio.paused ? 'Pause' : 'Play'} ${card.clip.title}`);
      card.wav.href = address(path(card.clip));
      card.wav.download = path(card.clip).split('/').pop();
    });
  }
  async function select(clip, play = true) {
    const request = ++state.request;
    state.handoff?.abort(); state.handoff = null;
    $('playback-error').hidden = true;
    if (state.active?.id === clip.id && play && !audio.paused) { audio.pause(); updatePlayer(); return; }
    audio.pause(); state.active = clip; audio.src = address(path(clip)); audio.load(); updatePlayer();
    if (play) {
      try { await audio.play(); }
      catch (error) { if (request !== state.request) return; $('playback-error').textContent = `The recording could not start: ${error.message}. Try Play, or download the WAV.`; $('playback-error').hidden = false; }
      if (request !== state.request) return;
      updatePlayer();
    }
  }
  function card(clip, index) {
    const root = el('article', 'clip'); root.id = `clip-${clip.id}`;
    const top = el('div', 'clip-top');
    top.append(el('span', 'clip-label', `${String(index + 1).padStart(2, '0')} / ${note(clip.midi)} / ${fmt(clip.seconds)} SEC`));
    const play = el('button', 'clip-play'); play.type = 'button'; play.setAttribute('aria-label', `Play ${clip.title}`); play.addEventListener('click', () => select(clip)); top.append(play);
    root.append(top, el('h3', '', clip.title));
    if (clip.presetNames?.length) root.append(el('p', 'preset-tags', `STARTING PATCH · ${clip.presetNames.join(' / ')}`));
    root.append(el('p', 'clip-description', clip.description));
    const macros = el('div', 'macro-values');
    ['SOURCE', 'FOCUS', 'BODY', 'LENGTH'].forEach(name => { const value = el('span', '', name); value.append(el('strong', '', fmt(clip.macros[name]))); macros.append(value); });
    root.append(macros);
    const links = el('div', 'clip-links'); const ref = state.clips.find(c => c.id === clip.comparison);
    if (ref) { const compare = el('button', 'reference-button', 'Play reference →'); compare.type = 'button'; compare.addEventListener('click', () => select(ref)); links.append(compare); }
    else links.append(el('span', '', 'Native model 1'));
    const files = el('div'); const patch = el('a', '', 'Patch ↓'); patch.href = address(clip.recipe); patch.download = clip.recipe.split('/').pop();
    const wav = el('a', '', 'WAV ↓'); wav.href = address(path(clip)); wav.download = path(clip).split('/').pop(); files.append(patch, wav); links.append(files); root.append(links);
    state.cards.set(clip.id, { root, play, wav, clip }); return root;
  }
  function render() {
    const container = $('recordings'); container.replaceChildren(); state.cards.clear();
    const visible = state.clips.filter(clip => state.filter === 'all' || (state.filter === 'anchors' ? clip.section === 'anchors' || clip.id === 'body-0' : state.filter === 'presets' ? clip.presetNames?.length : clip.section !== 'anchors' && clip.section !== 'presets'));
    const grouped = new Map();
    visible.forEach(clip => { const key = state.filter === 'presets' ? 'presets' : clip.section; if (!grouped.has(key)) grouped.set(key, []); grouped.get(key).push(clip); });
    for (const [section, clips] of grouped) {
      const definition = sections[section] || [section, '']; const wrapper = el('section', 'study-section');
      const heading = el('div', 'section-heading'); heading.append(el('h2', '', state.filter === 'presets' ? `${clips.length} editable starting patches` : definition[0]), el('p', '', state.filter === 'presets' ? 'Heard timbral anchors and new native listening candidates. Every entry retains its complete patch.' : definition[1]));
      const grid = el('div', 'clip-grid'); clips.forEach(clip => grid.append(card(clip, state.clips.indexOf(clip)))); wrapper.append(heading, grid); container.append(wrapper);
    }
    updatePlayer();
  }
  function mediaReady(test, events, signal) {
    if (signal.aborted) return Promise.reject(new DOMException('Playback selection changed', 'AbortError'));
    if (test()) return Promise.resolve();
    return new Promise((resolve, reject) => {
      let timer;
      const clear = () => { clearTimeout(timer); events.forEach(event => audio.removeEventListener(event, check)); audio.removeEventListener('error', fail); signal.removeEventListener('abort', abort); };
      const check = () => { if (test()) { clear(); resolve(); } };
      const fail = () => { clear(); reject(new Error('The selected WAV could not load.')); };
      const abort = () => { clear(); reject(new DOMException('Playback selection changed', 'AbortError')); };
      events.forEach(event => audio.addEventListener(event, check)); audio.addEventListener('error', fail); signal.addEventListener('abort', abort, { once: true });
      timer = setTimeout(() => { clear(); reject(new Error('The WAV did not become ready in time.')); }, 8000);
    });
  }
  async function level(mode) {
    if (state.mode === mode) return;
    const request = ++state.request;
    state.handoff?.abort();
    const handoff = new AbortController(); state.handoff = handoff;
    const playing = !audio.paused; const offset = audio.currentTime || 0; state.mode = mode;
    $('matched-mode').setAttribute('aria-pressed', String(mode === 'matched')); $('native-mode').setAttribute('aria-pressed', String(mode === 'native'));
    $('level-explanation').textContent = mode === 'matched' ? 'Level matched reduces loudness differences so you can compare tone. Each matched file uses AuditionLevel once.' : 'Native output is exactly the finished CausticPatch render. It includes the instrument’s house finish and has no extra audition leveling.';
    if (state.active) {
      audio.pause(); audio.preload = 'auto'; audio.src = address(path(state.active)); audio.load();
      try {
        // WAV metadata can arrive before Chrome can retain a seek. Buffer first,
        // then complete the seek while paused, and only then resume playback.
        await mediaReady(() => audio.readyState >= 3 && Number.isFinite(audio.duration), ['canplay', 'loadeddata', 'progress'], handoff.signal);
        if (request !== state.request) return;
        const target = Math.min(offset, Math.max(0, audio.duration - .02));
        if (target > .01 && Math.abs(audio.currentTime - target) > .02) {
          audio.currentTime = target;
          await mediaReady(() => !audio.seeking && audio.readyState >= 2 && Math.abs(audio.currentTime - target) < .08, ['seeked', 'canplay'], handoff.signal);
        }
        if (request !== state.request) return;
        if (playing) await audio.play();
      } catch (error) {
        if (request !== state.request || error.name === 'AbortError') return;
        $('playback-error').textContent = `${error.message} Press Play to retry, or download the WAV.`; $('playback-error').hidden = false;
      } finally {
        if (state.handoff === handoff) { state.handoff = null; audio.preload = 'none'; }
      }
    }
    updatePlayer();
  }
  if (!manifest?.clips?.length) {
    $('recordings').replaceChildren(el('p', 'loading', 'The recording manifest is missing. Keep manifest.js beside this page, or rebuild the native audition pack.')); return;
  }
  state.clips = manifest.clips; audio.volume = .65;
  $('clip-count').textContent = `${state.clips.length} fixed recordings · One ALLOY voice · Native editable patches`;
  $('start-listening').disabled = false; $('start-listening').addEventListener('click', () => { select(state.clips.find(c => c.id === 'default-c4') || state.clips[0]); $('audio').scrollIntoView({ behavior: 'smooth', block: 'center' }); });
  $('matched-mode').addEventListener('click', () => level('matched')); $('native-mode').addEventListener('click', () => level('native'));
  document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => { state.filter = button.dataset.filter; document.querySelectorAll('[data-filter]').forEach(b => b.setAttribute('aria-pressed', String(b === button))); render(); }));
  ['play', 'pause', 'ended'].forEach(event => audio.addEventListener(event, updatePlayer));
  audio.addEventListener('error', () => { $('playback-error').textContent = 'This WAV could not load. Try the download link or reopen the complete comparison folder.'; $('playback-error').hidden = false; });
  download('kit-download', manifest.downloads?.kit); download('kit-download-bottom', manifest.downloads?.kit); download('comparison-download', manifest.downloads?.comparison);
  if (!manifest.downloads?.kit && !manifest.downloads?.comparison) document.querySelector('.downloads').hidden = true;
  if (location.protocol === 'file:') document.querySelectorAll('.history-link').forEach(link => link.hidden = true);
  const summary = manifest.summary || {}; const evidence = $('evidence-content');
  const revision = manifest.sourceRevision || {};
  evidence.append(el('p', '', `Native model ${manifest.model} · ${summary.sampleRate || 44100} Hz · Mono · PCM ${summary.bitDepth || 24} bit. Generated ${manifest.generatedAt || 'from the native renderer'}.`));
  if (summary.maximumNativePeak !== undefined) evidence.append(el('p', '', `Measured native peak ${fmt(summary.maximumNativePeak, 4)} · Sample headroom ${fmt(summary.worstSampleHeadroomDb, 2)} dB · Worst broad pitch estimate ${Number.isFinite(summary.worstPitchCents) ? fmt(summary.worstPitchCents, 2) + ' cents' : 'unavailable'}. These measurements are not a listening verdict.`));
  evidence.append(el('p', '', `Source ${String(revision.commit || '').slice(0, 12)} · Fingerprint ${revision.sourceFingerprintSha256 || 'available in manifest.json'}.`));
  if (summary.warnings?.length) evidence.append(el('p', '', `Probe notes: ${summary.warnings.join(' / ')}`));
  const metadata = el('p'); const manifestLink = el('a', '', 'Manifest, hashes and full measurements ↓'); manifestLink.href = address('manifest.json'); manifestLink.download = 'manifest.json'; metadata.append(manifestLink); evidence.append(metadata);
  render(); select(state.clips.find(c => c.id === 'default-c4') || state.clips[0], false);
})();
