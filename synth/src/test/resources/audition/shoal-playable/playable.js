'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const manifestURL = new URL('playable/manifest.json', location.href);
  const assetBase = new URL('.', manifestURL);
  const asset = path => new URL(path, assetBase).href;
  const audioCache = new Map(), traceCache = new Map();
  const native = $('native');
  let manifest, materials = [], clips = [], visible = [], order = [], notes = {};
  let mode = 'native', blind = false, current = null, context = null, source = null, gain = null;
  let buffer = null, playing = false, fallback = false, offset = 0, startedAt = 0, request = 0;
  let traceFrames = [], traceSeconds = 0, traceMessage = 'Loading measured controller…', traceRequest = 0;
  let storageKey = '', previousGraphTime = 0;

  function node(tag, text, className) {
    const value = document.createElement(tag);
    if (text !== undefined) value.textContent = text;
    if (className) value.className = className;
    return value;
  }
  function fmt(value, digits = 3) { return value === null || value === undefined ? '—' : Number(value).toFixed(digits); }
  function clock(time) { const s = Math.floor(Math.max(0, time || 0)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }
  function announce(message) { $('announcement').textContent = message; }
  function noteKey(clip) { return manifest.revision + ':native:' + clip.nativeSha256; }
  function label(clip) {
    if (!clip) return '';
    return blind ? 'Candidate ' + String.fromCharCode(65 + order.indexOf(clip.material)) : materials.find(m => m.id === clip.material)?.label || clip.material;
  }
  function motionLabel(id) {
    const item = manifest.motions.find(d => (typeof d === 'string' ? d : d.id) === id);
    return typeof item === 'string' ? item : item?.label || id;
  }
  function saveNotes() {
    try { localStorage.setItem(storageKey, JSON.stringify(notes)); }
    catch (error) { announce('Browser storage unavailable. Export your listening notes before closing.'); }
  }
  function duration() { return buffer?.duration || current?.durationSeconds || 0; }
  function position() {
    if (fallback) return Number.isFinite(native.currentTime) ? native.currentTime : offset;
    if (!playing || !context) return offset;
    return Math.min(offset + context.currentTime - startedAt, duration());
  }
  function state(message) {
    $('now-status').textContent = message || (playing ? 'Playing' : 'Paused') + ' / ' + (mode === 'matched' ? 'matched loudness' : 'native velocity');
    $('now-title').textContent = current ? label(current) + ' / ' + current.motion + ' / MIDI ' + current.midi + ' / velocity ' + fmt(current.velocity, 2) : 'Choose a material above.';
    $('toggle').textContent = playing ? 'Ⅱ' : '▶';
    $('toggle').setAttribute('aria-label', playing ? 'Pause' : 'Play');
    for (const id of ['previous', 'toggle', 'replay', 'stop', 'next', 'seek']) $(id).disabled = !current;
    document.querySelectorAll('[data-clip]').forEach(card => card.classList.toggle('playing', playing && card.dataset.clip === current?.id));
  }
  function halt(reset = false) {
    const at = position();
    playing = false;
    offset = reset ? 0 : at;
    if (source) {
      source.onended = null;
      try { source.stop(); } catch (error) {}
      source.disconnect();
      source = null;
    }
    if (gain) { gain.disconnect(); gain = null; }
    native.pause();
    if (reset && native.readyState >= 1) native.currentTime = 0;
    state();
  }
  async function decoded(path) {
    if (audioCache.has(path)) {
      const found = audioCache.get(path);
      audioCache.delete(path);
      audioCache.set(path, found);
      return found;
    }
    const response = await fetch(path);
    if (!response.ok) throw new Error('Audio unavailable (' + response.status + ')');
    const value = await context.decodeAudioData(await response.arrayBuffer());
    audioCache.set(path, value);
    if (audioCache.size > 8) audioCache.delete(audioCache.keys().next().value);
    return value;
  }
  async function play(clip, at = 0) {
    const token = ++request;
    halt(true);
    current = clip;
    const changedVelocity = Math.abs(Number($('velocity').value) - clip.velocity) > 1e-6;
    const changedMaterial = $('touch-material').value !== clip.material;
    $('velocity').value = String(clip.velocity);
    $('touch-material').value = clip.material;
    if (changedVelocity) renderCandidates();
    if (changedMaterial) renderTouchButtons();
    setFacts(clip);
    loadTrace(clip);
    offset = at;
    buffer = null;
    fallback = false;
    native.removeAttribute('src');
    native.load();
    $('fallback').hidden = true;
    state('Loading / ' + mode);
    const path = asset(clip[mode + 'Path']);
    try {
      context = context || new (window.AudioContext || window.webkitAudioContext)();
      await context.resume();
      const loaded = await decoded(path);
      if (token !== request) return;
      buffer = loaded;
      offset = Math.min(Math.max(0, at), Math.max(0, buffer.duration - .001));
      source = context.createBufferSource();
      gain = context.createGain();
      source.buffer = buffer;
      gain.gain.value = Number($('volume').value);
      source.connect(gain);
      gain.connect(context.destination);
      startedAt = context.currentTime;
      playing = true;
      const activeSource = source;
      source.onended = () => {
        if (source !== activeSource) return;
        source.disconnect();
        source = null;
        gain?.disconnect();
        gain = null;
        playing = false;
        offset = 0;
        state('Finished / ' + mode);
      };
      source.start(0, offset);
      state();
      announce('Playing ' + label(clip) + ' at ' + mode + ' level');
    } catch (error) {
      if (token !== request) return;
      fallback = true;
      $('fallback').hidden = false;
      native.src = path;
      native.volume = Number($('volume').value);
      native.addEventListener('loadedmetadata', () => {
        if (token === request && at > 0) native.currentTime = Math.min(at, native.duration - .001);
      }, { once: true });
      try {
        await native.play();
        if (token !== request) { native.pause(); return; }
        playing = true;
        state();
        announce('Playing ' + label(clip) + ' using the media player');
      } catch (mediaError) {
        if (token !== request) return;
        playing = false;
        state('Playback unavailable / use WAV download');
        announce('Playback unavailable. The WAV download is in the candidate evidence panel.');
      }
    }
  }
  function setMode(next) {
    if (mode === next) return;
    const at = position(), wasPlaying = playing;
    ++request;
    halt(false);
    mode = next;
    fallback = false;
    buffer = null;
    offset = at;
    $('matched').setAttribute('aria-pressed', String(mode === 'matched'));
    $('native-mode').setAttribute('aria-pressed', String(mode === 'native'));
    conditionDescription();
    if (current) {
      if (wasPlaying) play(current, at);
      else state('Paused / ' + mode);
    }
  }
  function metric(host, text, value) {
    const n = node('div', text);
    n.append(node('b', value));
    host.append(n);
  }
  function download(host, text, path) {
    if (!path) return;
    const a = node('a', text);
    a.href = asset(path);
    a.download = path.split('/').pop();
    host.append(a);
  }
  function card(clip, index) {
    const material = materials.find(m => m.id === clip.material);
    const article = node('article', undefined, 'candidate');
    article.dataset.clip = clip.id;
    const top = node('div', undefined, 'candidate-top');
    top.append(node('span', String.fromCharCode(65 + index), 'letter'), node('span', blind ? 'Label hidden' : clip.material === 'HARMONIC' ? 'Harmonic' : 'Model 3 material', 'type'));
    article.append(top, node('h3', label(clip)), node('p', blind ? 'Same motion, root and touch. Listen for its own articulation, color and decay.' : material.description, 'description'));
    const button = node('button', '▶ Listen', 'listen-button');
    button.setAttribute('aria-label', 'Listen to ' + label(clip) + ', ' + clip.motion + ' motion, MIDI ' + clip.midi + ', velocity ' + fmt(clip.velocity, 2));
    button.onclick = () => play(clip);
    article.append(button);
    const facts = node('div', undefined, 'candidate-facts');
    facts.append(node('span', fmt(clip.durationSeconds, 2) + ' s'), node('span', 'velocity ' + fmt(clip.velocity, 2)));
    article.append(facts);
    const detail = node('details');
    detail.append(node('summary', blind ? 'Listening notes' : 'Evidence, downloads & notes'));
    const metrics = node('div', undefined, 'metrics');
    metric(metrics, 'Native peak / RMS', fmt(clip.nativeMetrics?.peak) + ' / ' + fmt(clip.nativeMetrics?.rms));
    metric(metrics, 'Matched loudness', fmt(clip.matchedMetrics?.loudness, 4));
    metric(metrics, 'Production patch / repeat', clip.nativePatchEquivalent && clip.deterministicRepeat ? 'Exact / deterministic' : 'See measurements');
    metric(metrics, 'Prelevel probe peak', fmt(clip.rawProbeMetrics?.peak));
    if(clip.materialDiagnostics){metric(metrics,'Contact cutoff',fmt(clip.materialDiagnostics.contactCutoffHz,0)+' Hz');metric(metrics,'Contact duration',fmt(clip.materialDiagnostics.contactSeconds*1000,2)+' ms');}
    metric(metrics, 'Native WAV SHA256', clip.nativeSha256.slice(0, 16));
    metric(metrics, 'Matched WAV SHA256', clip.matchedSha256.slice(0, 16));
    if (!blind) detail.append(metrics);
    const downloads = node('div', undefined, 'downloads');
    download(downloads, 'Native WAV ↓', clip.nativePath);
    download(downloads, 'Matched WAV ↓', clip.matchedPath);
    download(downloads, 'SnipSnap patch ↓', clip.recipePath);
    download(downloads, 'Production state ↓', clip.tracePath);
    if (!blind) detail.append(downloads);
    const textarea = node('textarea');
    textarea.placeholder = 'What rings? Does its character stay with the motion?';
    textarea.setAttribute('aria-label', 'Listening notes for ' + label(clip) + ', ' + clip.motion + ' motion, MIDI ' + clip.midi + ', velocity ' + fmt(clip.velocity, 2));
    textarea.value = notes[noteKey(clip)] || '';
    textarea.oninput = () => { notes[noteKey(clip)] = textarea.value; saveNotes(); };
    detail.append(textarea);
    article.append(detail);
    return article;
  }
  function conditionDescription() {
    if (!visible.length) return;
    $('condition-description').textContent = $('motion').value + ' motion · MIDI ' + $('midi').value + ' · velocity ' + fmt(visible[0].velocity, 2) + ' · ' + (mode === 'matched' ? 'matched loudness' : 'native velocity');
  }
  function renderCandidates() {
    const selected = clips.filter(c => c.motion === $('motion').value && c.midi === Number($('midi').value) && Math.abs(c.velocity-Number($('velocity').value))<1e-6);
    visible = order.map(id => selected.find(c => c.material === id)).filter(Boolean);
    if (visible.length !== materials.length) throw new Error('Incomplete material comparison for this condition');
    $('candidates').replaceChildren(...visible.map(card));
    conditionDescription();
    state();
  }
  function renderTouchOptions(selected) {
    $('touch-material').replaceChildren(...order.map(id => {
      const option = node('option', label({ material: id }));
      option.value = id;
      return option;
    }));
    $('touch-material').value = order.includes(selected) ? selected : order[0];
  }
  function renderTouchButtons() {
    const material = $('touch-material').value;
    const chosen = clips.filter(c => c.material === material && c.motion === $('motion').value && c.midi === Number($('midi').value))
      .sort((a, b) => a.velocity - b.velocity);
    $('touch-buttons').replaceChildren(...chosen.map(clip => {
      const touch = clip.velocity < .3 ? 'Soft' : clip.velocity < .8 ? 'Medium' : 'Hard';
      const button = node('button', touch + ' · ' + fmt(clip.velocity, 2));
      button.dataset.clip = clip.id;
      button.setAttribute('aria-label', 'Play ' + label(clip) + ' ' + touch.toLowerCase() + ' touch');
      button.onclick = () => play(clip);
      return button;
    }));
    state();
  }
  function setFacts(clip) {
    $('condition-facts').replaceChildren(
      node('div', label(clip) + ' / ' + motionLabel(clip.motion) + ' motion / MIDI ' + clip.midi + ' / velocity ' + fmt(clip.velocity, 2)),
      node('div', 'Model 3 · finite native SnipSnap patch'),
    );
  }
  function canvasSize(canvas) {
    const dpr = Math.min(window.devicePixelRatio || 1, 2), rect = canvas.getBoundingClientRect();
    const w = Math.round(rect.width * dpr), h = Math.round(rect.height * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    return [w, h];
  }
  function drawTrace() {
    const canvas = $('trace'), ctx = canvas.getContext('2d'), [w, h] = canvasSize(canvas);
    if (!w || !h) return;
    ctx.fillStyle = '#29463d';
    ctx.fillRect(0, 0, w, h);
    if (!traceFrames.length) {
      ctx.fillStyle = '#b3c3ad';
      ctx.textAlign = 'center';
      ctx.font = '12px system-ui';
      ctx.fillText(traceMessage, w / 2, h / 2);
      return;
    }
    const count = Math.max(...traceFrames.map(f => f.energy.length));
    traceFrames.forEach((frame, x) => {
      const total = frame.energy.reduce((a, b) => a + b, 0) || 1;
      frame.energy.forEach((energy, y) => {
        const light = Math.min(1, Math.pow(energy / total * 4.5, .62));
        ctx.fillStyle = 'hsl(' + (139 - 92 * light) + ' ' + (15 + 28 * light) + '% ' + (16 + 56 * light) + '%)';
        ctx.fillRect(x * w / traceFrames.length, (count - y - 1) * h / count, Math.ceil(w / traceFrames.length) + .2, Math.ceil(h / count) + .2);
      });
    });
    if (playing && traceSeconds > 0) {
      const x = Math.min(position() / traceSeconds, 1) * (w - 1);
      ctx.strokeStyle = '#f0ead4aa';
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, h);
      ctx.stroke();
    }
  }
  async function loadTrace(clip) {
    const token = ++traceRequest;
    const path = asset(clip.tracePath);
    traceFrames = [];
    traceSeconds = 0;
    traceMessage = 'Loading measured controller…';
    $('trace-tag').textContent = label(clip) + ' / MIDI ' + clip.midi;
    $('trace-time').textContent = 'Time →';
    drawTrace();
    try {
      let data = traceCache.get(path);
      if (!data) {
        const response = await fetch(path);
        if (!response.ok) throw new Error('Controller trace unavailable');
        data = await response.json();
        if (!Array.isArray(data.controller) || !data.controller.length) throw new Error('No controller frames');
        traceCache.set(path, data);
        if (traceCache.size > 12) traceCache.delete(traceCache.keys().next().value);
      }
      if (token !== traceRequest) return;
      const radiating = Array.isArray(data.radiation) && data.radiation.length;
      const frames = radiating ? data.radiation : data.controller;
      $('trace-heading').textContent = radiating ? 'Material radiating energy' : 'Harmonic controller energy';
      traceSeconds = frames[frames.length - 1].seconds;
      traceFrames = frames.length <= 96 ? frames : Array.from({ length: 96 }, (_, i) => frames[Math.round(i * (frames.length - 1) / 95)]);
      $('trace-time').textContent = fmt(traceSeconds, 2) + ' s →';
      $('trace-caption').textContent = 'Actual production ' + (radiating ? 'material radiation' : 'controller') + ' state, sampled in time. Brighter cells hold a larger share in each moving-partial slot; root energy is separate. This is not an audio spectrogram.';
      drawTrace();
    } catch (error) {
      if (token !== traceRequest) return;
      traceMessage = 'Controller trace unavailable';
      $('trace-caption').textContent = 'The trace could not load. Audio playback remains available, and controller JSON is downloadable in each evidence panel.';
      drawTrace();
    }
  }
  function changeCondition() {
    ++request;
    halt(true);
    current = null;
    buffer = null;
    fallback = false;
    $('elapsed').textContent = '0:00';
    $('duration').textContent = '0:00';
    $('seek').value = 0;
    renderCandidates();
    renderTouchButtons();
    const selected=visible.find(c=>c.material===$('touch-material').value)||visible[0];
    setFacts(selected);
    loadTrace(selected);
  }
  function shuffled(values) {
    const result = values.slice();
    for (let i = result.length - 1; i > 0; i--) {
      const random = new Uint32Array(1);
      crypto.getRandomValues(random);
      const j = random[0] % (i + 1);
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
  }
  function exportNotes() {
    const result = {
      study: 'SHOAL_PLAYABLE', modelVersion: 3, revision: manifest.revision,
      sourceRevision: manifest.sourceRevision,
      sourceSnapshotSha256: manifest.sourceSnapshotSha256,
      exportedAt: new Date().toISOString(), noteKeyFormat: '<revision>:native:<WAV SHA256>', notes,
      clipAudioRevisions: clips.map(c => ({ id: c.id, material: c.material, motion: c.motion, midi: c.midi, velocity:c.velocity,nativeSha256:c.nativeSha256,matchedSha256: c.matchedSha256, noteKey: noteKey(c) })),
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' }));
    const a = node('a');
    a.href = url;
    a.download = 'shoal-playable-notes-' + manifest.revision + '.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    announce('Playable material notes exported');
  }

  $('matched').onclick = () => setMode('matched');
  $('native-mode').onclick = () => setMode('native');
  $('toggle').onclick = () => { if (!current) return; if (playing) { ++request; halt(false); } else play(current, offset); };
  $('replay').onclick = () => { if (current) play(current); };
  $('stop').onclick = () => { ++request; halt(true); state('Stopped / ' + mode); };
  function move(direction) {
    if (!visible.length) return;
    const index = visible.findIndex(c => c.id === current?.id);
    play(visible[(Math.max(0, index) + direction + visible.length) % visible.length]);
  }
  $('previous').onclick = () => move(-1);
  $('next').onclick = () => move(1);
  $('volume').oninput = () => { const value = Number($('volume').value); native.volume = value; if (gain && context) gain.gain.setTargetAtTime(value, context.currentTime, .01); };
  $('seek').onchange = () => {
    if (!current) return;
    const at = Number($('seek').value) / 1000 * duration();
    if (playing) play(current, at);
    else { offset = at; if (fallback && native.readyState >= 1) native.currentTime = at; }
  };
  $('blind').onclick = () => {
    blind = !blind;
    if (blind) order = shuffled(order);
    $('blind').setAttribute('aria-pressed', String(blind));
    $('blind').textContent = blind ? 'Reveal labels' : 'Hide labels';
    renderTouchOptions($('touch-material').value);
    renderCandidates();
    renderTouchButtons();
    document.querySelector('.trace-panel').hidden=blind;
    const selected = current || visible.find(clip => clip.material === $('touch-material').value) || visible[0];
    if (selected) setFacts(selected);
    announce(blind ? 'Material labels hidden; candidate order reshuffled.' : 'Material labels revealed.');
  };
  for (const id of ['motion', 'midi', 'velocity']) $(id).onchange = changeCondition;
  $('touch-material').onchange = () => {
    ++request;
    halt(true);
    current = null;
    buffer = null;
    fallback = false;
    $('elapsed').textContent = '0:00';
    $('duration').textContent = '0:00';
    $('seek').value = 0;
    renderTouchButtons();
    const selected = visible.find(clip => clip.material === $('touch-material').value);
    if (selected) { setFacts(selected); loadTrace(selected); }
  };
  $('export-notes').onclick = exportNotes;
  native.addEventListener('play', () => { if (fallback) { playing = true; state(); } });
  native.addEventListener('pause', () => { if (fallback) { offset = native.currentTime; playing = false; state(); } });
  native.addEventListener('ended', () => { if (fallback) { playing = false; offset = 0; state('Finished / ' + mode); } });
  window.addEventListener('resize', drawTrace);
  window.addEventListener('pagehide', () => { ++request; ++traceRequest; halt(true); native.removeAttribute('src'); native.load(); });

  async function initialize() {
    try {
      const response = await fetch(manifestURL);
      if (!response.ok) throw new Error('Study manifest unavailable (' + response.status + ')');
      manifest = await response.json();
      materials = manifest.materials;
      clips = manifest.clips;
      if (!Array.isArray(materials) || materials.length !== 4 || !Array.isArray(clips) || clips.length !== 48) throw new Error('Incomplete playable manifest');
      order = materials.map(m => m.id);
      storageKey = 'snipsnap-shoal-playable-notes-' + manifest.revision;
      try { notes = JSON.parse(localStorage.getItem(storageKey) || '{}'); } catch (error) {}
      $('motion').replaceChildren(...manifest.motions.map(d => {
        const option = node('option', typeof d === 'string' ? d : d.label || d.id);
        option.value = typeof d === 'string' ? d : d.id;
        return option;
      }));
      $('midi').replaceChildren(...manifest.noteMatrix.map(midi => {
        const option = node('option', (midi === 48 ? 'C3' : midi === 60 ? 'C4' : 'Note') + ' · MIDI ' + midi);
        option.value = String(midi);
        return option;
      }));
      for (const id of ['motion', 'midi', 'velocity', 'touch-material', 'blind', 'export-notes']) $(id).disabled = false;
      $('method-description').textContent = 'Every sound is the production ShoalPatch renderer: the same material, movement, root and velocity saved in its downloadable native patch. No browser synthesis or rack effects are involved.';
      $('method-limit').textContent = 'Each material has its own tuning, strike response and passive ring. Velocity changes contact and brightness as well as native output level. These 48 patches are finite notes.';
      $('scope').textContent = '48 production cases: four materials × two motions × C3/C4 × soft/medium/hard velocity (.25/.6/1). Native velocity is exact Patch.render output. Matched loudness levels the same sound for comparing color. All native recipes reconstruct their audio exactly; prior studies remain separate.';
      $('provenance').textContent = 'Model 3 / '+manifest.revision+' / source '+(manifest.sourceRevision||'recorded in recipes')+(manifest.sourceTreeDirty?' with working-tree changes.':'.')+' Source snapshot SHA256: '+manifest.sourceSnapshotSha256+'.';
      $('measurement-note').textContent = (Array.isArray(manifest.metricNotes) ? manifest.metricNotes.join(' ') : manifest.metricNotes) || '';
      $('footer-revision').textContent = 'MODEL 3 / NATIVE SNIPSNAP PATCHES\n'+manifest.revision;
      const pack = manifest.packPath || manifest.archivePath;
      if (pack) { $('pack-link').href = asset(pack); $('pack-link').download = pack.split('/').pop(); $('pack-link').hidden = false; }
      for (const [id,path] of [['source-download',manifest.sourceArchivePath],['readme-download',manifest.readmePath],['validation-download',manifest.validationPath]]) { if(path) {$(id).href=asset(path);$(id).download=path.split('/').pop();$(id).hidden=false;} }
      $('velocity').replaceChildren(...manifest.velocityMatrix.map(v=>{const option=node('option',(v<.3?'Soft':v<.8?'Medium':'Hard')+' · '+fmt(v,2));option.value=String(v);return option}));
      $('velocity').value=String(manifest.velocityMatrix.find(v=>Math.abs(v-(manifest.defaultVelocity||.6))<1e-6));
      renderTouchOptions('WOOD');
      changeCondition();
    } catch (error) {
      $('candidates').replaceChildren(node('p', 'The playable data could not load. Reload the page or open the manifest to check the recorded assets.', 'load-error'));
      $('condition-description').textContent = 'Audition unavailable';
      announce('The playable material data could not load.');
    }
  }
  function tick(time) {
    if (current) {
      const at = position();
      $('elapsed').textContent = clock(at);
      $('duration').textContent = clock(duration());
      if (document.activeElement !== $('seek')) $('seek').value = duration() ? Math.round(at / duration() * 1000) : 0;
    }
    if (time - previousGraphTime > 70) { drawTrace(); previousGraphTime = time; }
    requestAnimationFrame(tick);
  }
  initialize();
  requestAnimationFrame(tick);
})();
