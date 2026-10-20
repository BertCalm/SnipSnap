(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const state = { manifest: null, clips: new Map(), selected: null, level: 'matched', token: 0, loading: false, ready: false, position: 0, playIntent: false, cancelMetadata: null };
  const wavCache = new Map();
  const el = (tag, className, text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; };
  const number = (value, digits = 2) => Number(value).toFixed(digits);
  const time = value => { const total = Math.max(0, Math.round(Number(value) || 0)); return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`; };
  const safe = path => { if (typeof path !== 'string' || !path || /^(?:[a-z]+:|\/\/|\/)/i.test(path) || path.split('/').includes('..')) throw new Error('Invalid asset path in manifest.'); return new URL(path, location.href).href; };
  const setLink = (node, path) => { node.href = safe(path); };
  const pairFor = clip => state.manifest.pairs.find(pair => pair.id === clip.pairId);
  const error = message => { $('player-error').textContent = message; $('player-error').hidden = !message; };

  // Full, unchanged WAV bytes make seeking work even when hosting has no Range support.
  // This cache performs no audio decoding, synthesis, crossfade or other processing.
  function cachedWav(path) {
    const key = safe(path);
    if (wavCache.has(key)) return wavCache.get(key).promise;
    const entry = { url: null, promise: null };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30000);
    entry.promise = (async () => {
      try {
        const response = await fetch(key, { cache: 'no-cache', signal: controller.signal });
        if (!response.ok) throw new Error(`The WAV request returned ${response.status}. Choose this recording again to retry.`);
        const blob = await response.blob();
        if (!blob.size) throw new Error('The WAV download was empty. Choose this recording again to retry.');
        entry.url = URL.createObjectURL(new Blob([blob], { type: 'audio/wav' }));
        return entry.url;
      } catch (cause) {
        if (wavCache.get(key) === entry) wavCache.delete(key);
        if (cause.name === 'AbortError') throw new Error('The WAV is taking too long to download. Choose this recording again to retry.');
        throw cause;
      } finally { clearTimeout(timer); }
    })();
    wavCache.set(key, entry);
    return entry.promise;
  }

  function discardWav(path) {
    const key = safe(path); const entry = wavCache.get(key);
    if (!entry) return;
    wavCache.delete(key);
    if (entry.url) URL.revokeObjectURL(entry.url);
  }

  function loadMetadata(url) {
    return new Promise((resolve, reject) => {
      const cleanup = () => { audio.removeEventListener('loadedmetadata', loaded); audio.removeEventListener('error', failed); clearTimeout(timer); if (state.cancelMetadata === cancel) state.cancelMetadata = null; };
      const loaded = () => { cleanup(); resolve(); };
      const failed = () => { cleanup(); reject(new Error('This WAV could not load. Press Play to try again, or use its download link.')); };
      const cancel = () => { cleanup(); reject(new Error('Recording selection changed.')); };
      const timer = setTimeout(() => { cleanup(); reject(new Error('The downloaded WAV could not open. Press Play to try again.')); }, 15000);
      state.cancelMetadata = cancel;
      audio.addEventListener('loadedmetadata', loaded, { once: true }); audio.addEventListener('error', failed, { once: true });
      audio.src = url; audio.load();
    });
  }

  function enableTransport() {
    $('play').disabled = state.loading;
    $('seek').disabled = !state.ready || state.loading;
    $('stop').disabled = !state.ready || state.loading;
    $('next-wrap').disabled = !state.ready || state.loading || state.selected?.kind !== 'looped';
  }

  async function load() {
    $('loading').hidden = false; $('load-error').hidden = true; $('study').hidden = true;
    try {
      const response = await fetch('./manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest request returned ${response.status}.`);
      const manifest = await response.json();
      if (manifest.engine !== 'FLOCK' || manifest.experiment !== 'ribbon-sustain-v1' || !Array.isArray(manifest.pairs) || manifest.pairs.length !== 3 || !Array.isArray(manifest.clips) || manifest.clips.length !== 6) throw new Error('This is not the expected six-render sustain manifest.');
      state.manifest = manifest; state.clips = new Map(manifest.clips.map(clip => [clip.id, clip]));
      for (const pair of manifest.pairs) if (!state.clips.has(pair.continuousId) || !state.clips.has(pair.loopedId)) throw new Error('A comparison render is missing.');
      render(); $('study').hidden = false;
    } catch (cause) { $('load-error-detail').textContent = cause.message || 'The render manifest is unavailable.'; $('load-error').hidden = false; }
    finally { $('loading').hidden = true; }
  }

  function render() {
    const manifest = state.manifest;
    $('duration-label').textContent = `${number(manifest.settings.heldSeconds, 0)} s held + ${number(manifest.settings.releaseSeconds, 1)} s release`;
    $('capture-note').textContent = manifest.captureNote;
    $('flow-cards').replaceChildren();
    for (const pair of manifest.pairs) {
      const card = el('article', 'flow-card');
      const top = el('div', 'card-top'); top.append(el('span', '', `FLOW ${number(pair.flow)}`), el('span', '', pair.id === 'flow-default' ? 'ORIGINAL DEFAULT' : 'CLOCK ENDPOINT')); card.append(top);
      card.append(el('h3', '', pair.label), el('p', 'flow-rate', `${number(pair.motionRate, 2)}× simulation clock`), el('p', 'flow-description', pair.description));
      const buttons = el('div', 'pair-buttons'); buttons.setAttribute('role', 'group'); buttons.setAttribute('aria-label', `${pair.label} comparison`);
      for (const id of [pair.continuousId, pair.loopedId]) {
        const clip = state.clips.get(id); const button = el('button', 'clip-button'); button.type = 'button'; button.dataset.clip = id; button.setAttribute('aria-pressed', 'false'); button.setAttribute('aria-label', `Play ${pair.label}, ${clip.kind === 'continuous' ? 'continuous performance' : 'repeated captured body'}`);
        const label = el('span'); label.append(el('span', 'play-icon'), document.createTextNode(clip.kind === 'continuous' ? 'Continuous' : 'Repeated body')); button.append(label, el('small', '', clip.kind === 'continuous' ? 'keeps evolving' : `${number(pair.timing.loopPeriodSeconds, 2)} s cycle`));
        button.addEventListener('click', () => select(clip, true)); buttons.append(button);
      }
      card.append(buttons);
      const links = el('div', 'card-links'); const continuousRecipe = el('a', '', 'Continuous recipe'); setLink(continuousRecipe, state.clips.get(pair.continuousId).recipeSrc); const loopRecipe = el('a', '', 'Loop recipe'); setLink(loopRecipe, state.clips.get(pair.loopedId).recipeSrc); links.append(continuousRecipe, loopRecipe); card.append(links);
      const details = el('details'); details.append(el('summary', '', 'Loop timing and sample'));
      const list = el('dl');
      for (const [key, value] of [['Capture', `${number(pair.timing.captureStartSeconds, 2)}–${number(pair.timing.captureEndSeconds, 2)} s`], ['Nominal / actual cycle', `${number(manifest.settings.loopSeconds, 1)} / ${number(pair.timing.loopPeriodSeconds, 3)} s`], ['Crossfade', `${number(manifest.settings.crossfadeSeconds, 1)} s`], ['Returns before release', String(pair.timing.wrapSeconds.filter(at => at < manifest.settings.heldSeconds).length)], ['First return', `${number(pair.timing.wrapSeconds[0], 3)} s`]]) list.append(el('dt', '', key), el('dd', '', value));
      details.append(list);
      const sample = el('a', '', 'Experimental sampler WAV'); sample.download = ''; setLink(sample, pair.samplerSrc); details.append(sample); card.append(details);
      $('flow-cards').append(card);
    }
    updateLevel();
    const settings = manifest.settings;
    const source = manifest.source;
    const lines = [['Voice / model', `RIBBON / finite engine model ${settings.sourceModel}; experimental sustain v1`], ['Root / velocity', `C4 (MIDI ${settings.midi}) / ${number(settings.velocity)}`], ['Seed', settings.seed], ['Nominal loop target', `${number(settings.loopSeconds, 1)} seconds; each actual period is shown above`], ['Endpoint selection', manifest.endpointSearchPolicy], ['Companion macros', Object.entries(settings.macros).filter(([key]) => key !== 'FLOW').map(([key, value]) => `${key} ${number(value)}${key === 'HOLD' ? ' (unused; duration is explicit)' : ''}`).join(' · ')], ['Rendering', `${settings.sampleRate.toLocaleString()} Hz mono PCM16; ${settings.oversample}× synthesis oversampling; dry`], ['Source revision', `${source.revision}${source.dirty ? ' + recorded working-tree snapshots' : ''}`], ['Listening scope', 'A sustain experiment, outside the app patch and factory-kit schema. No finite recipe changes.']];
    $('settings').replaceChildren(); for (const [key, value] of lines) $('settings').append(el('dt', '', key), el('dd', '', value));
    if (!state.selected) select(state.clips.get(manifest.pairs[1].continuousId), false);
  }

  function updateLevel() {
    $('level-note').textContent = state.level === 'matched' ? state.manifest.levelPolicy.matched : state.manifest.levelPolicy.raw;
    if (state.selected) setLink($('wav-link'), state.selected[state.level === 'matched' ? 'src' : 'rawSrc']);
  }

  async function select(clip, autoplay, positionOverride) {
    const previous = state.selected; const wasPlaying = state.loading ? state.playIntent : !audio.paused;
    const keep = $('keep-position').checked && previous;
    const position = positionOverride ?? (keep ? (state.loading ? state.position : audio.currentTime) : 0);
    const shouldPlay = autoplay || wasPlaying;
    const token = ++state.token;
    if (state.cancelMetadata) state.cancelMetadata();
    audio.pause(); state.selected = clip; state.loading = true; state.ready = false; state.position = position; state.playIntent = shouldPlay; error('');
    document.querySelectorAll('[data-clip]').forEach(button => { const selected = button.dataset.clip === clip.id; button.setAttribute('aria-pressed', String(selected)); button.setAttribute('aria-busy', String(selected)); });
    $('player').hidden = false;
    const pair = pairFor(clip);
    $('player-kicker').textContent = `RIBBON / FLOW ${number(pair.flow)} / ${number(pair.motionRate)}×`;
    $('player-label').textContent = clip.kind === 'continuous' ? 'Continuous performance' : 'Repeated body';
    const subtitle = state.level === 'matched' ? 'Level matched · shared pair gain' : 'Raw · shared safety gain';
    $('player-subtitle').textContent = 'Loading the complete recording…';
    $('seek').max = String(clip.duration); $('total').textContent = time(clip.duration);
    $('seek').value = String(position); $('elapsed').textContent = time(position); $('phase').textContent = 'Loading recording…';
    const path = clip[state.level === 'matched' ? 'src' : 'rawSrc'];
    setLink($('wav-link'), path);
    enableTransport(); updatePlay();
    let mediaLoaded = false;
    try {
      const url = await cachedWav(path);
      if (token !== state.token) return;
      await loadMetadata(url);
      if (token !== state.token) return;
      audio.currentTime = Math.min(Math.max(0, Number(position) || 0), Math.max(0, clip.duration - .02));
      mediaLoaded = true; state.loading = false; state.ready = true; $('player-subtitle').textContent = subtitle; enableTransport(); updateTime();
      if (shouldPlay) await audio.play();
    } catch (cause) {
      if (token === state.token) {
        state.loading = false;
        if (!mediaLoaded) { discardWav(path); state.ready = false; $('player-subtitle').textContent = 'Recording unavailable · press Play to retry'; $('phase').textContent = 'Could not load recording'; }
        enableTransport(); error(cause.message || 'Playback could not start. Press Play to try again.');
      }
    } finally { if (token === state.token) { document.querySelectorAll('[data-clip]').forEach(button => button.setAttribute('aria-busy', 'false')); updatePlay(); } }
  }

  function updatePlay() { const playing = !audio.paused; $('play').setAttribute('aria-label', playing ? 'Pause' : 'Play'); $('play').classList.toggle('is-playing', playing); }
  function updateTime() {
    if (!state.selected || state.loading || !state.ready) return;
    const value = audio.currentTime || 0; $('seek').value = String(value); $('elapsed').textContent = time(value);
    const pair = pairFor(state.selected); const release = state.manifest.settings.heldSeconds;
    if (value >= release) $('phase').textContent = 'Release';
    else if (state.selected.kind === 'continuous') $('phase').textContent = value < .045 ? 'Attack' : 'Simulation keeps evolving';
    else if (value < pair.timing.captureStartSeconds) $('phase').textContent = 'Attack / lead-in';
    else { const previous = pair.timing.wrapSeconds.filter(at => at <= value).length; const next = pair.timing.wrapSeconds.find(at => at > value); $('phase').textContent = previous ? `Return ${previous}${next ? ` · next ${time(next)}` : ''}` : `First pass · return at ${time(pair.timing.wrapSeconds[0])}`; }
  }

  $('retry').addEventListener('click', load);
  document.querySelectorAll('input[name=level]').forEach(input => input.addEventListener('change', () => { state.level = input.value; updateLevel(); if (state.selected) select(state.selected, false, state.loading ? state.position : audio.currentTime); }));
  async function playCurrent() {
    if (state.loading) return;
    if (!state.ready && state.selected) { await select(state.selected, true, state.position); return; }
    error(''); try { await audio.play(); } catch (cause) { error(cause.message || 'Playback could not start.'); }
  }
  $('play').addEventListener('click', () => { if (!state.ready || audio.paused) playCurrent(); else audio.pause(); });
  $('stop').addEventListener('click', () => { audio.pause(); audio.currentTime = 0; updateTime(); });
  $('repeat').addEventListener('change', event => { audio.loop = event.target.checked; });
  $('seek').addEventListener('input', event => { audio.currentTime = Number(event.target.value); updateTime(); });
  $('next-wrap').addEventListener('click', async () => { if (!state.selected || state.selected.kind !== 'looped') return; const wraps = pairFor(state.selected).timing.wrapSeconds; const next = wraps.find(at => at > audio.currentTime + 1) ?? wraps[0]; audio.currentTime = Math.max(0, next - 1); updateTime(); try { await audio.play(); } catch { error('Press Play to hear the next join.'); } });
  for (const event of ['play', 'pause', 'ended']) audio.addEventListener(event, updatePlay);
  audio.addEventListener('timeupdate', updateTime);
  audio.addEventListener('error', () => { if (!state.loading) error('The recording could not play. Try the WAV download or another render.'); });
  if ('mediaSession' in navigator) { navigator.mediaSession.setActionHandler('play', playCurrent); navigator.mediaSession.setActionHandler('pause', () => audio.pause()); }
  load();
})();
