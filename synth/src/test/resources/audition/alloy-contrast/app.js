'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const REVIEW_KEY = 'snipsnap.alloy.contrast-r2.reviews.v1';
  const SELECTED_VERSIONS = Object.freeze({ BASS: 'old', KEY: 'new', PLUCK: 'new', BRASS: 'new', STRING: 'new', SOFT: 'old' });
  const state = { manifest: null, voice: 'BASS', section: 'voices', variant: SELECTED_VERSIONS.BASS, midi: 48, role: null,
    selected: null, playing: false, loading: false, offset: 0, started: 0, repeat: false, request: 0 };
  let context, active, ticker;
  const cache = new Map(), pending = new Map(), queue = [];
  let decoding = 0;
  let reviews = {};
  try { reviews = JSON.parse(localStorage.getItem(REVIEW_KEY) || '{}'); } catch (_) { reviews = {}; }
  if (!reviews || typeof reviews !== 'object' || Array.isArray(reviews)) reviews = {};
  const time = value => { const t = Math.max(0, Number(value) || 0); return `${Math.floor(t / 60)}:${(t % 60).toFixed(1).padStart(4, '0')}`; };
  const clips = () => state.manifest.clips.filter(c => c.voice === state.voice && c.section === state.section);
  const currentOffset = () => {
    if (!state.playing || !active) return state.offset;
    const position = active.offset + context.currentTime - active.started;
    return active.loop ? position % active.buffer.duration : Math.min(position, active.buffer.duration);
  };
  function audioContext() {
    if (!context) context = new (window.AudioContext || window.webkitAudioContext)();
    return context;
  }
  function retire() {
    if (!active) return;
    const old = active;
    active = null;
    old.source.onended = null;
    const now = context.currentTime;
    old.gain.gain.cancelScheduledValues(now);
    old.gain.gain.setValueAtTime(old.gain.gain.value, now);
    old.gain.gain.linearRampToValueAtTime(0, now + .008);
    try { old.source.stop(now + .012); } catch (_) { /* Already ended. */ }
    old.source.addEventListener('ended', () => { old.source.disconnect(); old.gain.disconnect(); }, { once: true });
  }
  function stop(reset = true) {
    state.offset = reset ? 0 : currentOffset();
    state.request++;
    state.loading = false;
    state.playing = false;
    retire();
    transport();
  }
  function pump() {
    while (decoding < 2 && queue.length) {
      const job = queue.shift();
      decoding++;
      fetch(job.file).then(response => {
        if (!response.ok) throw new Error(`Audio download failed (${response.status}).`);
        return response.arrayBuffer();
      }).then(bytes => audioContext().decodeAudioData(bytes)).then(buffer => {
        cache.delete(job.file); cache.set(job.file, buffer);
        while (cache.size > 12) cache.delete(cache.keys().next().value);
        job.resolve(buffer);
      }).catch(job.reject).finally(() => { decoding--; pending.delete(job.file); pump(); });
    }
  }
  function decode(file) {
    if (cache.has(file)) { const b = cache.get(file); cache.delete(file); cache.set(file, b); return Promise.resolve(b); }
    if (pending.has(file)) return pending.get(file);
    const promise = new Promise((resolve, reject) => queue.push({ file, resolve, reject }));
    pending.set(file, promise); pump(); return promise;
  }
  async function play(offset = state.offset) {
    const clip = state.selected;
    if (!clip) return;
    const request = ++state.request;
    state.loading = true;
    $('audio-status').textContent = 'Preparing audio…';
    transport();
    try {
      const ctx = audioContext();
      await ctx.resume();
      const buffer = await decode(clip.file);
      if (request !== state.request || state.selected.id !== clip.id) return;
      retire();
      const source = ctx.createBufferSource(), gain = ctx.createGain();
      source.buffer = buffer;
      source.loop = clip.loop && state.repeat;
      source.loopStart = 0;
      source.loopEnd = buffer.duration;
      source.connect(gain); gain.connect(ctx.destination);
      const now = ctx.currentTime;
      gain.gain.setValueAtTime(0, now); gain.gain.linearRampToValueAtTime(1, now + .008);
      const cue = source.loop ? offset % buffer.duration : Math.min(Math.max(0, offset), Math.max(0, buffer.duration - .001));
      const voice = { source, gain, buffer, offset: cue, started: now, loop: source.loop };
      active = voice;
      source.onended = () => {
        source.disconnect(); gain.disconnect();
        if (active !== voice) return;
        active = null; state.offset = 0; state.playing = false; transport();
      };
      source.start(now, cue);
      state.offset = cue; state.playing = true; state.loading = false;
      $('audio-status').textContent = clip.loop && state.repeat ? 'Repeating the settled engine cycle.' : '';
      transport();
    } catch (error) {
      if (request !== state.request) return;
      state.loading = false; state.playing = false;
      $('audio-status').textContent = `${error.message || 'Audio could not start.'} Try Play again.`;
      transport();
    }
  }
  function transport() {
    $('play').innerHTML = `${state.playing ? 'Ⅱ' : '▶'} <span>${state.loading ? 'Loading…' : state.playing ? 'Pause' : 'Play'}</span>`;
    $('play').setAttribute('aria-label', state.playing ? 'Pause selected render' : 'Play selected render');
    $('repeat').disabled = !state.selected?.loop;
    $('repeat').setAttribute('aria-pressed', String(!!state.repeat));
    $('repeat').textContent = state.repeat ? '↻ Repeating' : '↻ Repeat';
    updateTime();
  }
  function updateTime() {
    const duration = state.selected?.duration || 0, position = currentOffset();
    $('elapsed').textContent = time(position); $('duration').textContent = time(duration);
    $('seek').value = duration ? Math.min(1000, position / duration * 1000) : 0;
    $('playhead').style.left = `${duration ? Math.min(100, position / duration * 100) : 0}%`;
  }
  function tick() { updateTime(); ticker = requestAnimationFrame(tick); }
  function drawWave() {
    const canvas = $('wave'), rect = canvas.getBoundingClientRect(), ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(rect.width * ratio); canvas.height = Math.round(rect.height * ratio);
    const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
    const values = state.selected?.waveform || [], maximum = Math.max(.001, ...values);
    ctx.strokeStyle = '#41483a'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(0, rect.height / 2); ctx.lineTo(rect.width, rect.height / 2); ctx.stroke();
    ctx.strokeStyle = '#b8c0a8'; ctx.lineWidth = Math.max(1, rect.width / Math.max(1, values.length) * .58);
    ctx.beginPath(); values.forEach((value, i) => { const x = (i + .5) / values.length * rect.width, height = value / maximum * rect.height * .4; ctx.moveTo(x, rect.height / 2 - height); ctx.lineTo(x, rect.height / 2 + height); }); ctx.stroke();
  }
  function select(clip, preserveCue = false) {
    const cue = preserveCue ? currentOffset() : 0;
    const continuePlaying = preserveCue && (state.playing || state.loading);
    stop();
    state.selected = clip;
    state.repeat = clip.loop;
    state.offset = clip.loop ? cue % clip.duration : Math.min(cue, Math.max(0, clip.duration - .001));
    $('player-kind').textContent = state.section === 'roles' ? 'CANDIDATE ROLE' : `${clip.variant === 'old' ? 'RELEASED CONFIGURATION' : 'CANDIDATE CONFIGURATION'} / ${clip.loop ? 'HELD' : 'FINITE'}`;
    $('player-title').textContent = state.section === 'roles' ? clip.role : `${state.voice} · ${clip.variant === 'old' ? 'Old' : 'New'}`;
    $('player-detail').textContent = clip.loop ? 'Settled loop-only body. The opening full attack is absent.' : state.section === 'roles' ? 'Complete candidate patch. Dry, with its own material, routing and envelope.' : 'Identical pitch, velocity and controls. Only the stored voice configuration changes.';
    $('player-tags').textContent = `${clip.note} · MIDI ${clip.midi} · velocity ${clip.velocity}${clip.loop ? ' · loop' : ''}`;
    for (const [id, file] of [['wav', clip.file], ['raw', clip.rawFile], ['patch', clip.patchFile]]) { $(id).hidden = !file; if (file) $(id).href = file; else $(id).removeAttribute('href'); }
    $('audio-status').textContent = '';
    const measurements = [ ['Raw peak', clip.rawPeak.toFixed(4)], ['Matched RMS · 200 ms', clip.matchedLoudness.toFixed(4)], ['Root', `${clip.diagnostics.rootHz.toFixed(2)} Hz`], ['Duration', `${clip.duration.toFixed(3)} s`] ];
    if (clip.loop) measurements.push(['Raw seam error', Number(clip.seamError).toExponential(2)], ['PCM16 seam error', Number(clip.exportedSeamError).toExponential(2)], ['Settled state error', Number(clip.stateError).toExponential(2)]);
    $('measurements').replaceChildren(...measurements.flatMap(([label, value]) => { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = value; return [dt, dd]; }));
    review(); drawWave(); transport();
    if (continuePlaying) play(state.offset);
  }
  function choose() {
    const options = clips();
    const clip = state.section === 'roles' ? options.find(c => c.presetId === state.role && c.midi === state.midi) : options.find(c => c.variant === state.variant);
    select(clip || options[0]);
  }
  function study() {
    $('selected-version').textContent = SELECTED_VERSIONS[state.voice] === 'old' ? 'Original' : 'Revised';
    $('selection-detail').textContent = `${state.voice} voice · ${state.section === 'roles' ? 'two-role presets remain candidates' : 'compare either version below'}`;
    document.querySelectorAll('[data-section]').forEach(button => { const selected = button.dataset.section === state.section; button.setAttribute('aria-selected', String(selected)); button.tabIndex = selected ? 0 : -1; });
    $('study-panel').setAttribute('aria-labelledby', `tab-${state.section}`);
    $('comparison').hidden = state.section === 'roles'; $('roles').hidden = state.section !== 'roles';
    const section = state.manifest.sections.find(s => s.id === state.section);
    $('study-title').textContent = state.section === 'voices' ? 'The voice, at equal settings.' : state.section === 'held' ? 'What remains after the strike?' : 'Two roles. Three registers.';
    $('study-description').textContent = section.description;
    $('study-meta').textContent = state.section === 'roles' ? 'C2 / C3 / C4 · VELOCITY .75' : 'C3 / MIDI 48 · VELOCITY .75';
    const roleClips = state.manifest.clips.filter(c => c.voice === state.voice && c.section === 'roles' && c.midi === 48);
    if (!roleClips.some(c => c.presetId === state.role)) state.role = roleClips[0].presetId;
    $('role-buttons').replaceChildren(...roleClips.map((clip, index) => {
      const button = document.createElement('button'), tag = document.createElement('span'); button.type = 'button'; tag.textContent = `ROLE 0${index + 1}`; button.append(tag, document.createTextNode(clip.role)); button.setAttribute('aria-pressed', String(clip.presetId === state.role));
      button.addEventListener('click', () => { state.role = clip.presetId; study(); }); return button;
    }));
    $('old').setAttribute('aria-pressed', String(state.variant === 'old')); $('new').setAttribute('aria-pressed', String(state.variant === 'new'));
    choose();
  }
  function review() {
    const entry = reviews[state.selected.id] || {};
    $('note').value = entry.note || '';
    document.querySelectorAll('[data-verdict]').forEach(button => button.setAttribute('aria-pressed', String(entry.verdict === button.dataset.verdict)));
    $('saved').textContent = entry.updatedAt ? 'Saved on this device. Released-library notes are separate.' : 'These notes belong to the new comparison only.';
  }
  function save(update) {
    const clip = state.selected;
    reviews[clip.id] = { ...reviews[clip.id], ...update, clipId: clip.id, presetId: clip.presetId,
      voice: clip.voice, section: clip.section, variant: clip.variant, midi: clip.midi, updatedAt: new Date().toISOString() };
    try { localStorage.setItem(REVIEW_KEY, JSON.stringify(reviews)); $('saved').textContent = 'Saved on this device. Released-library notes are separate.'; }
    catch (_) { $('saved').textContent = 'Browser storage is unavailable. Export your notes before leaving.'; }
  }
  $('play').addEventListener('click', () => { if (state.playing || state.loading) { stop(false); $('audio-status').textContent = ''; } else play(); });
  $('stop').addEventListener('click', () => { stop(); $('audio-status').textContent = ''; });
  $('repeat').addEventListener('click', () => {
    if (!state.selected?.loop) return;
    state.repeat = !state.repeat;
    if (active) {
      const cue = currentOffset();
      active.source.loop = state.repeat; active.loop = state.repeat;
      active.offset = cue; active.started = context.currentTime;
    }
    $('audio-status').textContent = state.repeat ? 'Repeating the settled engine cycle.' : 'Repeat off. Playback will stop at the end of this cycle.';
    transport();
  });
  $('seek').addEventListener('input', () => { const running = state.playing || state.loading, cue = Number($('seek').value) / 1000 * state.selected.duration; stop(); state.offset = cue; if (running) play(cue); else transport(); });
  for (const variant of ['old', 'new']) $(variant).addEventListener('click', () => {
    if (state.variant === variant) return;
    state.variant = variant;
    $('old').setAttribute('aria-pressed', String(variant === 'old')); $('new').setAttribute('aria-pressed', String(variant === 'new'));
    select(clips().find(c => c.variant === variant), true);
  });
  document.querySelectorAll('[data-section]').forEach(button => button.addEventListener('click', () => { state.section = button.dataset.section; study(); }));
  document.querySelector('.tabs').addEventListener('keydown', event => {
    const buttons = [...document.querySelectorAll('[data-section]')], index = buttons.indexOf(document.activeElement);
    if (index < 0 || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : -1) + 3) % 3;
    buttons[next].focus(); buttons[next].click();
  });
  document.querySelectorAll('[data-midi]').forEach(button => button.addEventListener('click', () => { state.midi = Number(button.dataset.midi); document.querySelectorAll('[data-midi]').forEach(b => b.setAttribute('aria-pressed', String(Number(b.dataset.midi) === state.midi))); choose(); }));
  $('note').addEventListener('input', () => save({ note: $('note').value }));
  document.querySelectorAll('[data-verdict]').forEach(button => button.addEventListener('click', () => { const previous = reviews[state.selected.id]?.verdict; save({ verdict: previous === button.dataset.verdict ? null : button.dataset.verdict }); review(); }));
  $('export').addEventListener('click', () => {
    if (!state.manifest) return;
    const blob = new Blob([JSON.stringify({ engine: 'ALLOY', experiment: 'voice-contrast-r2', exportedAt: new Date().toISOString(), provenance: state.manifest.provenance, reviews }, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'alloy-contrast-r2-review.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  window.addEventListener('resize', drawWave);
  document.addEventListener('visibilitychange', () => { if (document.hidden) stop(false); });
  window.addEventListener('pagehide', () => { stop(); cancelAnimationFrame(ticker); });
  fetch('manifest.json').then(response => { if (!response.ok) throw new Error(`Manifest download failed (${response.status}).`); return response.json(); }).then(manifest => {
    if (manifest.clips?.length !== 60) throw new Error('The contrast pack is incomplete.');
    state.manifest = manifest; state.voice = manifest.voices[0]; state.variant = SELECTED_VERSIONS[state.voice];
    $('voices').replaceChildren(...manifest.voices.map(voice => { const button = document.createElement('button'); button.type = 'button'; button.textContent = voice; button.setAttribute('aria-pressed', String(voice === state.voice)); button.addEventListener('click', () => { state.voice = voice; state.variant = SELECTED_VERSIONS[voice]; $('voices').querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b.textContent === voice))); study(); }); return button; }));
    $('downloads').replaceChildren(...manifest.downloads.map(item => { const link = document.createElement('a'); link.href = item.file; link.download = ''; link.textContent = item.title; return link; }));
    $('load-status').hidden = true; $('workspace').hidden = false; study(); ticker = requestAnimationFrame(tick);
  }).catch(error => { $('load-status').textContent = `${error.message} Reload this page to try again.`; });
})();
