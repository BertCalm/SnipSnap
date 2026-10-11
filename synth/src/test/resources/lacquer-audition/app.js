'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const audio = $('audio');
  const canvas = $('waveform');
  const drawing = canvas.getContext('2d');
  const macros = ['RELIEF', 'SHEEN', 'BODY', 'CONTOUR', 'GLUE'];
  const state = { data: null, preset: null, clip: null, held: false, level: 'matched', tab: 'relief', group: '', playing: false, waveform: [], loading: 0 };
  const waveformCache = new Map();
  let decoder;
  let frame;

  function element(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = content;
    return node;
  }

  function number(value, fallback = 0) {
    const result = Number(value);
    return Number.isFinite(result) ? result : fallback;
  }

  function noteName(note) {
    const midi = Number(note);
    if (!Number.isFinite(midi)) return String(note ?? '—');
    return ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][(midi % 12 + 12) % 12] + (Math.floor(midi / 12) - 1);
  }

  function velocityLabel(value) {
    return number(value) <= 1 ? Math.round(number(value) * 100) + '%' : String(value);
  }

  function time(seconds) {
    if (!Number.isFinite(seconds)) return '—';
    return Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0');
  }

  function isHeld(clip) {
    return clip.hold === true || number(clip.hold) > 0 || /held|hold|loop/i.test(clip.kind || '');
  }

  function fileFor(clip) {
    return state.level === 'native' ? (clip.nativeFile || clip.file) : (clip.matchedFile || clip.file);
  }

  function status(message) {
    $('audio-status').textContent = message || '';
  }

  function renderPlayerState() {
    state.playing = !audio.paused;
    $('play-label').textContent = state.playing ? 'Pause tone' : 'Play tone';
    $('play-button').setAttribute('aria-label', state.playing ? 'Pause selected audio' : 'Play selected audio');
    $('play-icon').replaceChildren();
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', state.playing ? 'M6 5h4v14H6zM14 5h4v14h-4z' : 'M8 5v14l11-7z');
    $('play-icon').append(path);
    document.querySelectorAll('.diagnostic-button').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.clip === state.clip?.id)));
    if (state.playing && !frame) animate();
    if (!state.playing) {
      cancelAnimationFrame(frame);
      frame = undefined;
      drawWaveform();
    }
  }

  function updateTime() {
    $('play-time').replaceChildren(document.createTextNode(time(audio.currentTime) + ' '), element('span', 'time-divider', '/'), document.createTextNode(' ' + time(audio.duration)));
    $('duration-label').textContent = Number.isFinite(audio.duration) ? audio.duration.toFixed(2) + ' SECONDS' + (audio.loop ? ' · LOOP' : '') : '—';
  }

  function animate() {
    drawWaveform();
    frame = requestAnimationFrame(animate);
  }

  function drawWaveform() {
    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    const width = Math.round(rect.width * ratio);
    const height = Math.round(rect.height * ratio);
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    drawing.clearRect(0, 0, width, height);
    if (!state.waveform.length || width === 0 || height === 0) return;
    const progress = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.currentTime / audio.duration : 0;
    const gap = Math.max(2, Math.round(3 * ratio));
    const count = Math.floor(width / gap);
    const center = height / 2;
    for (let i = 0; i < count; i++) {
      const sample = state.waveform[Math.min(state.waveform.length - 1, Math.floor(i / count * state.waveform.length))];
      const extent = Math.max(ratio, sample * height * 0.43);
      drawing.fillStyle = i / count < progress ? '#eac48a' : '#7a8a6d';
      drawing.fillRect(i * gap, center - extent, Math.max(1, gap - ratio), extent * 2);
    }
    if (progress > 0 && progress < 1) {
      drawing.fillStyle = '#eac48a';
      drawing.fillRect(progress * width, 0, ratio, height);
    }
  }

  async function loadWaveform(file, token) {
    try {
      let samples = waveformCache.get(file);
      if (!samples) {
        const response = await fetch(file);
        if (!response.ok) throw new Error('WAV file unavailable');
        const bytes = await response.arrayBuffer();
        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) return;
        decoder ||= new AudioContext();
        const buffer = await decoder.decodeAudioData(bytes);
        const channel = buffer.getChannelData(0);
        const bins = 1000;
        samples = new Float32Array(bins);
        let peak = 0;
        for (let i = 0; i < bins; i++) {
          const start = Math.floor(i * channel.length / bins);
          const end = Math.floor((i + 1) * channel.length / bins);
          let value = 0;
          for (let j = start; j < end; j++) value = Math.max(value, Math.abs(channel[j]));
          samples[i] = value;
          peak = Math.max(peak, value);
        }
        // Normalize only the visual envelope. The WAV is played at its exported level.
        if (peak > 0) for (let i = 0; i < bins; i++) samples[i] /= peak;
        waveformCache.set(file, samples);
      }
      if (token === state.loading) {
        state.waveform = samples;
        drawWaveform();
      }
    } catch (_) {
      if (token === state.loading) state.waveform = [];
      drawWaveform();
    }
  }

  async function play() {
    if (!state.clip) return;
    status('');
    try { await audio.play(); }
    catch (error) { status(error.name === 'NotAllowedError' ? 'Press play to enable audio in your browser.' : 'This audio could not be loaded. Try another clip.'); }
  }

  function loadClip(clip, autoplay = false) {
    if (!clip?.file) return;
    audio.pause();
    state.clip = clip;
    state.waveform = [];
    const token = ++state.loading;
    const file = fileFor(clip);
    const primary = presetClips().some(item => item.id === clip.id);
    $('voice-label').textContent = (clip.voice || state.preset?.voice || 'Lacquer') + (primary ? ' / ORIGINAL LAYERED TONE' : ' / ENGINE COMPARISON');
    $('preset-name').textContent = primary ? state.preset.name : (clip.group || clip.label || 'Engine comparison');
    $('preset-description').textContent = primary ? (state.preset.description || 'An original Lacquer tone, rendered by SnipSnap.') : (clip.description || clip.label || 'An engine-rendered diagnostic comparison.');
    renderMacros(clip.macros || state.preset?.macros || {});
    audio.src = file;
    audio.loop = isHeld(clip);
    audio.load();
    $('play-button').disabled = false;
    $('clip-title').textContent = clip.label || state.preset?.name || 'Lacquer audition';
    $('clip-badge').textContent = isHeld(clip) ? 'HELD LOOP' : 'FINITE TONE';
    $('download').href = file;
    $('download').download = file.split('/').pop();
    $('download').setAttribute('aria-disabled', 'false');
    $('download').setAttribute('aria-label', 'Download ' + (clip.label || 'selected audio') + ' WAV');
    $('gesture-note').textContent = isHeld(clip) ? 'A settled engine-rendered sustain region, repeated by the player. RELIEF offsets gentle layer contours.' : clip.kind === 'boundary' ? 'This duration-boundary probe intentionally ends at the requested time. Normal finite presets keep their full decay.' : 'Finite tones include their full natural decay. Press space to play or pause.';
    if (state.level === 'native') $('gesture-note').textContent += ' Native preserves engine output level.';
    else if (clip.layer) $('gesture-note').textContent += ' Solo clips are independently level-matched; choose Native to hear relative layer gain.';
    else $('gesture-note').textContent += ' Matched clips use a shared loudness target.';
    status('');
    updateTime();
    renderPlayerState();
    drawWaveform();
    loadWaveform(file, token);
    if (autoplay) play();
  }

  function presetClips() {
    return state.preset?.clips || [];
  }

  function matchingPrimary() {
    const clips = presetClips().filter(clip => isHeld(clip) === state.held);
    return clips.find(clip => String(clip.note) === $('note-select').value && String(clip.velocity) === $('velocity-select').value)
      || clips.find(clip => String(clip.note) === $('note-select').value)
      || clips[0];
  }

  function renderSelect(select, values, label, previous) {
    select.replaceChildren();
    for (const value of values) {
      const option = element('option', '', label(value));
      option.value = value;
      select.append(option);
    }
    select.disabled = values.length < 2;
    if (values.some(value => String(value) === previous)) select.value = previous;
  }

  function renderOptions() {
    const all = presetClips();
    const hasFinite = all.some(clip => !isHeld(clip));
    const hasHeld = all.some(isHeld);
    if (state.held && !hasHeld) state.held = false;
    if (!state.held && !hasFinite) state.held = true;
    $('finite-mode').disabled = !hasFinite;
    $('held-mode').disabled = !hasHeld;
    $('finite-mode').setAttribute('aria-pressed', String(!state.held));
    $('held-mode').setAttribute('aria-pressed', String(state.held));
    const active = all.filter(clip => isHeld(clip) === state.held);
    const oldNote = $('note-select').value;
    const oldVelocity = $('velocity-select').value;
    const notes = [...new Set(active.map(clip => clip.note).filter(value => value !== undefined))].sort((a, b) => number(a) - number(b));
    renderSelect($('note-select'), notes, noteName, oldNote);
    const velocities = [...new Set(active.filter(clip => String(clip.note) === $('note-select').value).map(clip => clip.velocity).filter(value => value !== undefined))].sort((a, b) => number(a) - number(b));
    renderSelect($('velocity-select'), velocities, velocityLabel, oldVelocity);
  }

  function renderMacros(settings) {
    $('macro-defaults').replaceChildren();
    for (const macro of macros) {
      const value = settings[macro] ?? settings[macro.toLowerCase()];
      const item = element('div', 'macro');
      const header = element('div', 'macro-header', macro);
      header.append(element('span', 'macro-value', value === undefined ? '—' : number(value).toFixed(2)));
      const track = element('div', 'macro-track');
      const fill = element('span');
      fill.style.width = Math.max(0, Math.min(1, number(value))) * 100 + '%';
      track.append(fill);
      item.append(header, track);
      $('macro-defaults').append(item);
    }
  }

  function selectPreset(preset) {
    state.preset = preset;
    $('voice-label').textContent = (preset.voice || 'Lacquer') + ' / ORIGINAL LAYERED TONE';
    $('preset-name').textContent = preset.name;
    $('preset-description').textContent = preset.description || 'An original Lacquer tone, rendered by SnipSnap.';
    document.querySelectorAll('.preset-button').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.preset === preset.id)));
    renderMacros(preset.macros || {});
    renderOptions();
    loadClip(matchingPrimary());
    state.group = '';
    renderDiagnostics();
  }

  function renderPresets() {
    const presets = state.data.presets || [];
    $('preset-count').textContent = String(presets.length).padStart(2, '0');
    $('preset-list').replaceChildren();
    for (const [index, preset] of presets.entries()) {
      const button = element('button', 'preset-button');
      button.type = 'button';
      button.dataset.preset = preset.id;
      button.setAttribute('aria-pressed', 'false');
      const glyph = element('span', 'preset-glyph');
      glyph.setAttribute('aria-hidden', 'true');
      const shapes = [[9, 20, 29, 17, 9], [15, 29, 13, 22, 8], [8, 15, 27, 24, 12], [24, 17, 29, 12, 7], [10, 29, 21, 14, 8], [10, 15, 23, 29, 21]];
      for (const height of shapes[index % shapes.length]) {
        const line = element('i');
        line.style.height = height + 'px';
        glyph.append(line);
      }
      const copy = element('span', 'preset-button-copy');
      copy.append(element('span', 'preset-name', preset.name), element('span', 'preset-family', String(preset.voice || 'LACQUER').toUpperCase()));
      button.append(glyph, copy, element('span', 'preset-arrow', '↗'));
      button.addEventListener('click', () => selectPreset(preset));
      $('preset-list').append(button);
    }
    if (presets.length) selectPreset(presets[0]);
    else $('preset-list').append(element('p', 'loading', 'No presets were included in this render.'));
  }

  function category(clip) {
    const kind = String(clip.kind || '').toLowerCase();
    if (clip.layer || /solo|layer/.test(kind)) return 'layers';
    if (/interaction|grid|combination|all.?high|extreme|boundary/.test(kind) || (clip.macroA && clip.macroB)) return 'interactions';
    if (String(clip.macro || '').toUpperCase() === 'RELIEF' || /relief/.test(kind)) return 'relief';
    return 'macros';
  }

  function groupFor(clip) {
    if (state.tab === 'macros') return String(clip.macro || clip.group || 'Comparisons').toUpperCase();
    if (state.tab === 'interactions') return clip.group || clip.macro || 'Interactions';
    if (state.tab === 'relief') return clip.group || (isHeld(clip) ? 'Held loop' : 'Finite');
    return clip.group || 'Layer solos';
  }

  function diagnosticItems() {
    const all = (state.data.diagnostics || []).filter(clip => category(clip) === state.tab);
    // Scoped diagnostics follow the preset when provided; unscoped engine probes remain available.
    const scoped = all.filter(clip => !clip.presetId && !clip.preset && !clip.voice || clip.presetId === state.preset?.id || clip.preset === state.preset?.id || String(clip.voice).toUpperCase() === String(state.preset?.voice).toUpperCase());
    return scoped.length ? scoped : all;
  }

  const diagnosticCopy = {
    relief: ['Together → separated', 'RELIEF changes the timing of independently articulated layers. Compare the same tone at five positions.'],
    macros: ['Small controls. Real character.', 'Compare rendered macro settings. Each clip keeps its other parameters fixed.'],
    layers: ['Listen inside the blend.', 'Hear the body, edge, trail, and reinforcement on their own. Each source has an independent envelope and a distinct role.'],
    interactions: ['Where the controls meet.', 'Explore paired settings and demanding combinations. These are rendered engine probes, ready to compare by ear.']
  };

  function clipDisplay(clip) {
    if (state.tab === 'relief' || state.tab === 'macros') return clip.value !== undefined ? Math.round(number(clip.value) * 100) + '%' : clip.label || 'Listen';
    if (state.tab === 'layers') return String(clip.layer || clip.label || 'Layer').toLowerCase().replace(/^./, letter => letter.toUpperCase());
    if (clip.macroA && clip.macroB) return number(clip.value).toFixed(2) + ' / ' + number(clip.valueB).toFixed(2);
    return clip.shortLabel || clip.label || 'Listen';
  }

  function renderDiagnostics() {
    $('diagnostic-title').textContent = diagnosticCopy[state.tab][0];
    $('diagnostic-description').textContent = diagnosticCopy[state.tab][1];
    $('diagnostic-controls').replaceChildren();
    $('diagnostic-clips').replaceChildren();
    const all = diagnosticItems();
    const groups = [...new Set(all.map(groupFor))];
    if (!groups.includes(state.group)) state.group = groups[0] || '';
    if (groups.length > 1) {
      $('diagnostic-controls').className = 'diagnostic-controls';
      for (const group of groups) {
        const button = element('button', '', group);
        button.type = 'button';
        button.setAttribute('aria-pressed', String(group === state.group));
        button.addEventListener('click', () => { state.group = group; renderDiagnostics(); });
        $('diagnostic-controls').append(button);
      }
    }
    const clips = all.filter(clip => groupFor(clip) === state.group);
    if (state.tab === 'relief' || state.tab === 'macros') clips.sort((a, b) => number(a.value) - number(b.value));
    for (const clip of clips) {
      const button = element('button', 'diagnostic-button');
      button.type = 'button';
      button.dataset.clip = clip.id;
      button.setAttribute('aria-pressed', String(state.clip?.id === clip.id));
      button.setAttribute('aria-label', 'Play ' + (clip.label || clipDisplay(clip)));
      const copy = element('span');
      const title = element('strong', '', clipDisplay(clip));
      if (state.tab === 'interactions' || (state.tab === 'layers' && title.textContent.length > 9)) title.style.fontSize = '14px';
      copy.append(title, element('span', 'diagnostic-label', clip.label || (state.tab === 'relief' ? 'RELIEF ' + number(clip.value).toFixed(2) : 'Engine comparison')));
      copy.append(element('span', 'clip-meta', noteName(clip.note) + ' · vel ' + (clip.velocity === undefined ? '—' : velocityLabel(clip.velocity)) + (isHeld(clip) ? ' · held' : '')));
      button.append(copy, element('span', 'tiny-play', '▶'));
      button.addEventListener('click', () => loadClip(clip, true));
      $('diagnostic-clips').append(button);
    }
    if (!clips.length) $('diagnostic-clips').append(element('p', 'empty-diagnostics', 'No ' + state.tab + ' probes were included in this render.'));
    $('diagnostic-note').textContent = state.tab === 'layers'
      ? 'Matched solos are independently leveled for listening. Choose Native above to preserve the relative layer gain of the original blend.'
      : state.tab === 'relief' && clips.some(isHeld)
        ? 'Held RELIEF separates periodic layer contours in the settled loop. It does not repeat the full original attack.'
        : 'Choose Matched for loudness comparisons, or Native to hear the engine output level. Clicking a comparison replaces the current audio.';
  }

  function reportRow(label, value, className = '') {
    const row = element('div', 'report-row');
    row.append(element('span', '', label), element('strong', className, String(value)));
    $('report-summary').append(row);
  }

  function renderReport() {
    const data = state.data;
    $('render-meta').textContent = (number(data.sampleRate, 44100) / 1000).toFixed(1) + ' kHz · mono WAV';
    $('revision-label').textContent = data.revision ? 'REV ' + String(data.revision).slice(0, 10).toUpperCase() : 'AUDITION LAB';
    const report = data.report || {};
    $('report-summary').replaceChildren();
    reportRow('Audio source', 'SnipSnap offline render');
    reportRow('Sample rate', number(data.sampleRate, 44100).toLocaleString() + ' Hz / mono');
    reportRow('Auditions', (data.presets || []).reduce((count, preset) => count + (preset.clips || []).length, 0) + (data.diagnostics || []).length + ' rendered clips');
    if (data.revision) reportRow('Source revision', String(data.revision).slice(0, 12));
    const tests = report.tests || report.testResults;
    if (typeof tests === 'string') reportRow('Validation', tests);
    else if (tests && typeof tests === 'object') {
      if (tests.passed !== undefined) reportRow('Tests passed', tests.passed + (tests.total ? ' / ' + tests.total : ''), 'report-pass');
      if (tests.failed !== undefined && tests.failed > 0) reportRow('Tests failed', tests.failed);
    }
    if (report.deterministic !== undefined) reportRow('Determinism', report.deterministic === true ? 'Verified' : String(report.deterministic), report.deterministic === true ? 'report-pass' : '');
    const seam = report.maxSeamError ?? report.seamError ?? report.maxHoldSeamError;
    if (seam !== undefined) reportRow('Maximum loop seam', Number.isFinite(Number(seam)) ? Number(seam).toExponential(2) : seam);
    if (report.maxUnwrappedSeamError !== undefined) reportRow('Sustain before wrap', Number(report.maxUnwrappedSeamError).toExponential(2));
    if (report.summary) {
      const summary = element('p', 'diagnostic-note', typeof report.summary === 'string' ? report.summary : JSON.stringify(report.summary));
      $('report-summary').append(summary);
    }
  }

  $('play-button').addEventListener('click', () => audio.paused ? play() : audio.pause());
  $('finite-mode').addEventListener('click', () => { const playing = !audio.paused; state.held = false; renderOptions(); loadClip(matchingPrimary(), playing); });
  $('held-mode').addEventListener('click', () => { const playing = !audio.paused; state.held = true; renderOptions(); loadClip(matchingPrimary(), playing); });
  $('note-select').addEventListener('change', () => { const playing = !audio.paused; renderOptions(); loadClip(matchingPrimary(), playing); });
  $('velocity-select').addEventListener('change', () => loadClip(matchingPrimary(), !audio.paused));
  $('level-select').addEventListener('change', () => { const playing = !audio.paused; state.level = $('level-select').value; loadClip(state.clip, playing); });
  document.querySelectorAll('[data-tab]').forEach(button => {
    button.addEventListener('click', () => {
      state.tab = button.dataset.tab;
      state.group = '';
      document.querySelectorAll('[data-tab]').forEach(tab => tab.setAttribute('aria-selected', String(tab === button)));
      $('diagnostic-panel').setAttribute('aria-labelledby', button.id);
      renderDiagnostics();
    });
    button.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const tabs = [...document.querySelectorAll('[data-tab]')];
      const current = tabs.indexOf(button);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      tabs[next].focus();
      tabs[next].click();
    });
  });
  audio.addEventListener('play', renderPlayerState);
  audio.addEventListener('pause', renderPlayerState);
  audio.addEventListener('ended', renderPlayerState);
  audio.addEventListener('loadedmetadata', updateTime);
  audio.addEventListener('timeupdate', updateTime);
  audio.addEventListener('error', () => { if (state.clip) status('This WAV could not be loaded. Try another audition.'); });
  window.addEventListener('resize', drawWaveform);
  document.addEventListener('keydown', event => {
    if (event.code !== 'Space' || event.altKey || event.ctrlKey || event.metaKey || /INPUT|SELECT|TEXTAREA|BUTTON|A/.test(event.target.tagName)) return;
    event.preventDefault();
    if (state.clip) audio.paused ? play() : audio.pause();
  });

  fetch('audition.json').then(response => {
    if (!response.ok) throw new Error('The audition manifest is unavailable.');
    return response.json();
  }).then(data => {
    state.data = data;
    if (data.report?.kitFile) {
      $('kit-download').href = data.report.kitFile;
      $('kit-download-panel').hidden = false;
    }
    renderPresets();
    renderReport();
  }).catch(error => {
    $('preset-list').replaceChildren(element('p', 'loading', 'Audition files are unavailable.'));
    $('preset-name').textContent = 'Audio is unavailable';
    $('preset-description').textContent = 'The rendered audition manifest could not be loaded.';
    status(error.message);
  });
})();
