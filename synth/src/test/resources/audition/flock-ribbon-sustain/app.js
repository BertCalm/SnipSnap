(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const state = { manifest: null, clips: new Map(), selected: null, level: 'matched', token: 0, loading: false };
  const el = (tag, className, text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; };
  const number = (value, digits = 2) => Number(value).toFixed(digits);
  const time = value => { const total = Math.max(0, Math.round(Number(value) || 0)); return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`; };
  const safe = path => { if (typeof path !== 'string' || !path || /^(?:[a-z]+:|\/\/|\/)/i.test(path) || path.split('/').includes('..')) throw new Error('Invalid asset path in manifest.'); return new URL(path, location.href).href; };
  const setLink = (node, path) => { node.href = safe(path); };
  const pairFor = clip => state.manifest.pairs.find(pair => pair.id === clip.pairId);
  const error = message => { $('player-error').textContent = message; $('player-error').hidden = !message; };

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
        const clip = state.clips.get(id); const button = el('button', 'clip-button'); button.type = 'button'; button.dataset.clip = id; button.setAttribute('aria-pressed', 'false'); button.setAttribute('aria-label', `Play ${pair.label}, ${clip.kind === 'continuous' ? 'continuous performance' : 'repeated six-second body'}`);
        const label = el('span'); label.append(el('span', 'play-icon', '▶'), document.createTextNode(clip.kind === 'continuous' ? 'Continuous' : 'Repeated body')); button.append(label, el('small', '', clip.kind === 'continuous' ? 'keeps evolving' : `${number(manifest.settings.loopSeconds, 0)} s cycle`));
        button.addEventListener('click', () => select(clip, true)); buttons.append(button);
      }
      card.append(buttons);
      const links = el('div', 'card-links'); const continuousRecipe = el('a', '', 'Continuous recipe ↗'); setLink(continuousRecipe, state.clips.get(pair.continuousId).recipeSrc); const loopRecipe = el('a', '', 'Loop recipe ↗'); setLink(loopRecipe, state.clips.get(pair.loopedId).recipeSrc); links.append(continuousRecipe, loopRecipe); card.append(links);
      const details = el('details'); details.append(el('summary', '', 'Loop timing and sample'));
      const list = el('dl');
      for (const [key, value] of [['Capture', `${number(pair.timing.captureStartSeconds, 1)}–${number(pair.timing.captureEndSeconds, 1)} s`], ['Crossfade', `${number(manifest.settings.crossfadeSeconds, 1)} s`], ['Returns before release', String(pair.timing.wrapSeconds.length)], ['First return', `${number(pair.timing.wrapSeconds[0], 1)} s`]]) list.append(el('dt', '', key), el('dd', '', value));
      details.append(list);
      const sample = el('a', '', 'Experimental sampler WAV ↓'); sample.download = ''; setLink(sample, pair.samplerSrc); details.append(sample); card.append(details);
      $('flow-cards').append(card);
    }
    updateLevel();
    const settings = manifest.settings;
    const source = manifest.source;
    const lines = [['Voice / model', `RIBBON / finite engine model ${settings.sourceModel}; experimental sustain v1`], ['Root / velocity', `C4 (MIDI ${settings.midi}) / ${number(settings.velocity)}`], ['Seed', settings.seed], ['Companion macros', Object.entries(settings.macros).filter(([key]) => key !== 'FLOW').map(([key, value]) => `${key} ${number(value)}${key === 'HOLD' ? ' (unused; duration is explicit)' : ''}`).join(' · ')], ['Rendering', `${settings.sampleRate.toLocaleString()} Hz mono PCM16; ${settings.oversample}× synthesis oversampling; dry`], ['Source revision', `${source.revision}${source.dirty ? ' + recorded working-tree snapshots' : ''}`], ['Listening scope', 'A sustain experiment, outside the app patch and factory-kit schema. No finite recipe changes.']];
    $('settings').replaceChildren(); for (const [key, value] of lines) $('settings').append(el('dt', '', key), el('dd', '', value));
    if (!state.selected) select(state.clips.get(manifest.pairs[1].continuousId), false);
  }

  function updateLevel() {
    $('level-note').textContent = state.level === 'matched' ? state.manifest.levelPolicy.matched : state.manifest.levelPolicy.raw;
    if (state.selected) setLink($('wav-link'), state.selected[state.level === 'matched' ? 'src' : 'rawSrc']);
  }

  async function select(clip, autoplay, positionOverride) {
    const previous = state.selected; const wasPlaying = !audio.paused;
    const keep = $('keep-position').checked && previous;
    const position = positionOverride ?? (keep ? audio.currentTime : 0);
    const token = ++state.token; audio.pause(); state.selected = clip; state.loading = true; error('');
    document.querySelectorAll('[data-clip]').forEach(button => { const selected = button.dataset.clip === clip.id; button.setAttribute('aria-pressed', String(selected)); button.setAttribute('aria-busy', String(selected)); });
    $('player').hidden = false;
    const pair = pairFor(clip);
    $('player-kicker').textContent = `RIBBON / FLOW ${number(pair.flow)} / ${number(pair.motionRate)}×`;
    $('player-label').textContent = clip.kind === 'continuous' ? 'Continuous performance' : 'Repeated body';
    $('player-subtitle').textContent = state.level === 'matched' ? 'Level matched · shared pair gain' : 'Raw · shared safety gain';
    $('seek').max = String(clip.duration); $('total').textContent = time(clip.duration);
    $('next-wrap').disabled = clip.kind !== 'looped';
    setLink($('wav-link'), clip[state.level === 'matched' ? 'src' : 'rawSrc']);
    audio.src = safe(clip[state.level === 'matched' ? 'src' : 'rawSrc']);
    $('play').disabled = true; updatePlay();
    try {
      await new Promise((resolve, reject) => {
        const cleanup = () => { audio.removeEventListener('loadedmetadata', loaded); audio.removeEventListener('error', failed); clearTimeout(timer); };
        const loaded = () => { cleanup(); resolve(); };
        const failed = () => { cleanup(); reject(new Error('This WAV could not load. Try its download link or choose another recording.')); };
        const timer = setTimeout(() => { cleanup(); reject(new Error('The WAV is taking too long to load. Try again.')); }, 30000);
        audio.addEventListener('loadedmetadata', loaded, { once: true }); audio.addEventListener('error', failed, { once: true }); audio.load();
      });
      if (token !== state.token) return;
      audio.currentTime = Math.min(Math.max(0, Number(position) || 0), Math.max(0, clip.duration - .02));
      state.loading = false; $('play').disabled = false; updateTime();
      if (autoplay || wasPlaying) await audio.play();
    } catch (cause) { if (token === state.token) { state.loading = false; $('play').disabled = false; error(cause.message || 'Playback could not start. Press Play to try again.'); } }
    finally { if (token === state.token) { document.querySelectorAll('[data-clip]').forEach(button => button.setAttribute('aria-busy', 'false')); updatePlay(); } }
  }

  function updatePlay() { const playing = !audio.paused; $('play').setAttribute('aria-label', playing ? 'Pause' : 'Play'); $('play').firstElementChild.textContent = playing ? 'Ⅱ' : '▶'; }
  function updateTime() {
    if (!state.selected) return;
    const value = audio.currentTime || 0; $('seek').value = String(value); $('elapsed').textContent = time(value);
    const pair = pairFor(state.selected); const release = state.manifest.settings.heldSeconds;
    if (value >= release) $('phase').textContent = 'Release';
    else if (state.selected.kind === 'continuous') $('phase').textContent = value < .045 ? 'Attack' : 'Simulation keeps evolving';
    else if (value < pair.timing.captureStartSeconds) $('phase').textContent = 'Attack / lead-in';
    else { const previous = pair.timing.wrapSeconds.filter(at => at <= value).length; const next = pair.timing.wrapSeconds.find(at => at > value); $('phase').textContent = previous ? `Return ${previous}${next ? ` · next ${time(next)}` : ''}` : `First pass · return at ${time(pair.timing.wrapSeconds[0])}`; }
  }

  $('retry').addEventListener('click', load);
  document.querySelectorAll('input[name=level]').forEach(input => input.addEventListener('change', () => { state.level = input.value; updateLevel(); if (state.selected) select(state.selected, false, audio.currentTime); }));
  $('play').addEventListener('click', async () => { if (audio.paused) { error(''); try { await audio.play(); } catch (cause) { error(cause.message || 'Playback could not start.'); } } else audio.pause(); });
  $('stop').addEventListener('click', () => { audio.pause(); audio.currentTime = 0; updateTime(); });
  $('repeat').addEventListener('change', event => { audio.loop = event.target.checked; });
  $('seek').addEventListener('input', event => { audio.currentTime = Number(event.target.value); updateTime(); });
  $('next-wrap').addEventListener('click', async () => { if (!state.selected || state.selected.kind !== 'looped') return; const wraps = pairFor(state.selected).timing.wrapSeconds; const next = wraps.find(at => at > audio.currentTime + 1) ?? wraps[0]; audio.currentTime = Math.max(0, next - 1); updateTime(); try { await audio.play(); } catch { error('Press Play to hear the next join.'); } });
  for (const event of ['play', 'pause', 'ended']) audio.addEventListener(event, updatePlay);
  audio.addEventListener('timeupdate', updateTime);
  audio.addEventListener('error', () => { if (!state.loading) error('The recording could not play. Try the WAV download or another render.'); });
  if ('mediaSession' in navigator) { navigator.mediaSession.setActionHandler('play', () => audio.play().catch(() => error('Press Play to start audio.'))); navigator.mediaSession.setActionHandler('pause', () => audio.pause()); }
  load();
})();
