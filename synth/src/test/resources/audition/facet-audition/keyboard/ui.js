(() => {
  'use strict';
  const panel = document.getElementById('play-facet');
  if (!panel) return;
  const controls = Object.fromEntries([...panel.querySelectorAll('[data-live-control]')].map(input => [input.dataset.liveControl, input]));
  const enableButton = document.getElementById('live-enable');
  const textureInput = document.getElementById('live-texture');
  const voiceInput = document.getElementById('live-voice');
  const keybed = document.getElementById('live-keys');
  const status = document.getElementById('live-audio-status');
  const notesLabel = document.getElementById('live-notes');
  const voiceCount = document.getElementById('live-voice-count');
  const downButton = document.getElementById('live-octave-down');
  const upButton = document.getElementById('live-octave-up');
  const compact = window.matchMedia('(max-width: 600px)');
  const keyMap = new Map([
    ['KeyZ', 0], ['KeyS', 1], ['KeyX', 2], ['KeyD', 3], ['KeyC', 4], ['KeyV', 5],
    ['KeyG', 6], ['KeyB', 7], ['KeyH', 8], ['KeyN', 9], ['KeyJ', 10], ['KeyM', 11],
    ['KeyQ', 12], ['Digit2', 13], ['KeyW', 14], ['Digit3', 15], ['KeyE', 16], ['KeyR', 17],
    ['Digit5', 18], ['KeyT', 19], ['Digit6', 20], ['KeyY', 21], ['Digit7', 22], ['KeyU', 23], ['KeyI', 24]
  ]);
  const hintMap = new Map([...keyMap].map(([code, offset]) => [offset, code.replace('Key', '').replace('Digit', '')]));
  const held = new Map();
  let context = null;
  let worklet = null;
  let master = null;
  let initializing = null;
  let phase = 'idle';
  let generation = 0;
  let sequence = 0;
  let baseMidi = 48;
  let soundingVoices = 0;

  function noteName(midi) {
    return `${['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][midi % 12]}${Math.floor(midi / 12) - 1}`;
  }
  function parameters() {
    return { texture: textureInput.value, voice: voiceInput.value, ...Object.fromEntries(Object.entries(controls).map(([name, input]) => [name, Number(input.value)])) };
  }
  function snapshot() {
    return { phase, enabled: phase === 'ready' && context && context.state === 'running', contextState: context ? context.state : 'none', baseMidi, activeVoices: soundingVoices, held: [...held.values()].map(({ id, midi, sent }) => ({ id, midi, sent })), params: parameters() };
  }
  function updateKeys() {
    const midiSet = new Set([...held.values()].map(record => record.midi));
    keybed.querySelectorAll('[data-midi]').forEach(button => {
      const active = midiSet.has(Number(button.dataset.midi));
      button.classList.toggle('is-held', active);
      button.setAttribute('aria-pressed', String(active));
    });
    const notes = [...midiSet].sort((a, b) => a - b).map(noteName);
    const notesText = notes.length ? notes.join(' · ') : 'No notes held';
    const countText = `${soundingVoices} / 4 voices`;
    if (notesLabel.textContent !== notesText) notesLabel.textContent = notesText;
    if (voiceCount.textContent !== countText) voiceCount.textContent = countText;
    window.dispatchEvent(new CustomEvent('facet:live-state', { detail: snapshot() }));
  }
  function updateOutputs() {
    Object.entries(controls).forEach(([name, input]) => {
      const value = Number(input.value);
      const text = name === 'volume' ? `${Math.round(value * 100)}%` : `${value.toFixed(2)}${name === 'attack' || name === 'release' ? ' s' : ''}`;
      document.getElementById(`live-${name}-value`).textContent = text;
      input.setAttribute('aria-valuetext', text);
    });
  }
  function sendParameters() {
    updateOutputs();
    const params = parameters();
    if (worklet && phase === 'ready') worklet.port.postMessage({ type: 'params', params });
    if (master && context) master.gain.setTargetAtTime(params.volume, context.currentTime, .025);
  }
  function setEnableState() {
    const ready = phase === 'ready' && context && context.state === 'running';
    enableButton.disabled = ready || phase === 'loading';
    enableButton.textContent = phase === 'loading' ? 'Preparing audio…' : ready ? 'Audio enabled' : phase === 'error' ? 'Retry audio' : context ? 'Resume audio' : 'Enable audio';
    panel.classList.toggle('audio-ready', !!ready);
    panel.setAttribute('aria-busy', String(phase === 'loading'));
  }
  function dropVoice(id) {
    let changed = false;
    for (const [source, record] of held) if (record.id === id) { held.delete(source); changed = true; }
    if (changed) updateKeys();
  }
  function panic(message) {
    generation += 1;
    held.clear();
    soundingVoices = 0;
    if (worklet) worklet.port.postMessage({ type: 'panic' });
    updateKeys();
    if (message) status.textContent = message;
  }
  async function enableAudio() {
    if (initializing) return initializing;
    if (phase === 'ready' && context && context.state !== 'closed') {
      try { await context.resume(); setEnableState(); status.textContent = 'Ready. Hold a key to play.'; }
      catch (_) { phase = 'error'; setEnableState(); status.textContent = 'Audio could not resume. Try enabling it again.'; }
      return;
    }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !window.AudioWorkletNode) {
      phase = 'error'; setEnableState();
      status.textContent = 'Live audio is unavailable in this browser. You can listen to the reference recordings below.';
      return;
    }
    phase = 'loading'; setEnableState(); status.textContent = 'Preparing the instrument. It will be ready in a moment.';
    generation += 1;
    if (context && context.state !== 'closed') context.close().catch(() => {});
    worklet = null; master = null;
    let localContext;
    try { localContext = new AudioContextClass({ latencyHint: 'interactive', sampleRate: 44100 }); }
    catch (_) { phase = 'error'; setEnableState(); status.textContent = 'Audio could not start. Try enabling it again.'; return; }
    context = localContext;
    const resume = localContext.resume(); // A direct consequence of the Enable audio click.
    let timeout;
    const abort = new AbortController();
    initializing = (async () => {
      try {
        if (!localContext.audioWorklet) throw new Error('Audio worklets are not available.');
        const resources = Promise.all([
          resume,
          localContext.audioWorklet.addModule(new URL('keyboard/worklet.js', document.baseURI).href),
          fetch(new URL('keyboard/geometry.json', document.baseURI).href, { signal: abort.signal }).then(response => {
            if (!response.ok) throw new Error('The instrument could not be downloaded.');
            return response.json();
          })
        ]);
        const result = await Promise.race([resources, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Audio preparation timed out.')), 20000); })]);
        worklet = new AudioWorkletNode(localContext, 'facet-live', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2], processorOptions: { geometry: result[2] } });
        master = localContext.createGain();
        master.gain.value = Number(controls.volume.value);
        worklet.connect(master).connect(localContext.destination);
        worklet.port.onmessage = event => {
          const message = event.data || {};
          if (message.type === 'status') {
            soundingVoices = Number.isFinite(message.activeVoices) ? message.activeVoices : 0;
            [...(message.expiredIds || []), ...(message.stolenIds || [])].forEach(dropVoice);
            updateKeys();
          } else if (['voiceStolen', 'voiceEnded', 'voiceExpired'].includes(message.type)) dropVoice(message.id);
        };
        worklet.addEventListener('processorerror', () => {
          panic(); phase = 'error'; setEnableState(); status.textContent = 'Audio stopped unexpectedly. Enable it again to retry.';
          localContext.close().catch(() => {});
        });
        localContext.addEventListener('statechange', () => {
          if (localContext !== context || phase === 'loading') return;
          if (localContext.state !== 'running') panic();
          setEnableState();
        });
        phase = 'ready'; sendParameters(); setEnableState(); status.textContent = 'Ready. Hold a key to play.';
      } catch (_) {
        abort.abort(); panic(); phase = 'error';
        worklet = null; master = null;
        await localContext.close().catch(() => {});
        if (context === localContext) context = null;
        setEnableState(); status.textContent = 'The instrument could not be prepared. Check your connection and try again; the recordings remain below.';
        throw new Error('Audio initialization failed.');
      } finally { clearTimeout(timeout); initializing = null; }
    })();
    return initializing;
  }
  function endHold(source) {
    const record = held.get(source);
    if (!record) return;
    held.delete(source);
    if (record.sent && worklet) worklet.port.postMessage({ type: 'noteOff', id: record.id });
    updateKeys();
  }
  function startHold(source, midi, velocity = .8) {
    if (held.has(source)) return;
    if (phase !== 'loading' && !(phase === 'ready' && context && context.state === 'running')) {
      status.textContent = 'Enable audio before playing the keys.';
      return;
    }
    if (held.size >= 4) endHold(held.keys().next().value);
    const record = { id: `facet-${++sequence}`, midi, sent: false, generation };
    held.set(source, record); updateKeys();
    const start = () => {
      if (held.get(source) !== record || record.generation !== generation || phase !== 'ready' || !worklet || context.state !== 'running') return;
      document.querySelectorAll('audio').forEach(audio => audio.pause());
      window.dispatchEvent(new CustomEvent('facet:live-play', { detail: { id: record.id, midi } }));
      record.sent = true;
      worklet.port.postMessage({ type: 'noteOn', id: record.id, midi, velocity, ...parameters() });
    };
    if (phase === 'ready') start();
    else initializing.then(start).catch(() => {});
  }
  function renderKeyboard() {
    const semitones = compact.matches ? 12 : 24;
    const maxBase = compact.matches ? 72 : 60;
    baseMidi = Math.max(36, Math.min(maxBase, baseMidi));
    const whiteCount = semitones === 12 ? 8 : 15;
    keybed.style.setProperty('--white-keys', whiteCount);
    keybed.replaceChildren();
    let whiteIndex = 0;
    for (let offset = 0; offset <= semitones; offset += 1) {
      const midi = baseMidi + offset;
      const black = [1, 3, 6, 8, 10].includes(midi % 12);
      const button = document.createElement('button');
      button.type = 'button'; button.className = `piano-key ${black ? 'black-key' : 'white-key'}`;
      button.dataset.midi = midi;
      button.dataset.note = noteName(midi);
      button.setAttribute('aria-label', `${noteName(midi)}${hintMap.has(offset) ? `. Computer key ${hintMap.get(offset)}.` : ''}`);
      button.setAttribute('aria-pressed', 'false');
      if (black) { button.style.left = `${(whiteIndex - .31) / whiteCount * 100}%`; button.style.width = `${.62 / whiteCount * 100}%`; }
      else whiteIndex += 1;
      const name = document.createElement('span'); name.className = 'piano-note'; name.textContent = noteName(midi);
      const hint = document.createElement('span'); hint.className = 'piano-computer-key'; hint.textContent = hintMap.get(offset) || '';
      button.append(name, hint);
      button.addEventListener('pointerdown', event => {
        if (event.pointerType === 'mouse' && event.button !== 0) return;
        event.preventDefault();
        startHold(`pointer:${event.pointerId}`, midi);
        try { button.setPointerCapture(event.pointerId); } catch (_) {}
      });
      button.addEventListener('pointerup', event => {
        endHold(`pointer:${event.pointerId}`);
        try { if (button.hasPointerCapture(event.pointerId)) button.releasePointerCapture(event.pointerId); } catch (_) {}
      });
      button.addEventListener('pointercancel', () => panic('Notes stopped.'));
      button.addEventListener('lostpointercapture', event => { if (held.has(`pointer:${event.pointerId}`)) panic('Notes stopped.'); });
      button.addEventListener('keydown', event => {
        if (!['Space', 'Enter'].includes(event.code)) return;
        event.preventDefault();
        if (!event.repeat) startHold(`focus:${event.code}`, midi);
      });
      button.addEventListener('keyup', event => { if (['Space', 'Enter'].includes(event.code)) { event.preventDefault(); endHold(`focus:${event.code}`); } });
      button.addEventListener('click', event => {
        // Assistive technology can activate a button without pointer or physical-key events.
        if (event.detail !== 0 || held.has('focus:Space') || held.has('focus:Enter')) return;
        const source = `accessible:${++sequence}`;
        startHold(source, midi);
        setTimeout(() => endHold(source), 450);
      });
      keybed.append(button);
    }
    downButton.disabled = baseMidi <= 36;
    upButton.disabled = baseMidi >= maxBase;
    document.getElementById('live-octave-range').textContent = `${noteName(baseMidi)} — ${noteName(baseMidi + semitones)}`;
    updateKeys();
  }
  function resetSound() {
    panic();
    Object.assign(controls.brightness, { value: .5 });
    controls.density.value = voiceInput.value === 'SHIMMER' ? .8 : .25;
    controls.movement.value = voiceInput.value === 'SHIMMER' ? .65 : .15;
    controls.attack.value = textureInput.value === 'air' ? .45 : .2;
    controls.release.value = .35;
    controls.volume.value = .6;
    sendParameters(); status.textContent = phase === 'ready' ? 'Sound reset. Ready to play.' : 'Sound reset. Enable audio when you’re ready.';
  }
  enableButton.addEventListener('click', () => enableAudio().catch(() => {}));
  document.getElementById('live-stop').addEventListener('click', () => { panic('All notes stopped.'); document.querySelectorAll('audio').forEach(audio => audio.pause()); });
  document.getElementById('live-reset').addEventListener('click', resetSound);
  Object.values(controls).forEach(input => input.addEventListener('input', sendParameters));
  textureInput.addEventListener('change', () => {
    panic();
    controls.attack.value = textureInput.value === 'air' ? .45 : .2;
    sendParameters();
  });
  voiceInput.addEventListener('change', () => {
    panic();
    controls.density.value = voiceInput.value === 'SHIMMER' ? .8 : .25;
    controls.movement.value = voiceInput.value === 'SHIMMER' ? .65 : .15;
    sendParameters();
  });
  downButton.addEventListener('click', () => { panic(); baseMidi -= 12; renderKeyboard(); });
  upButton.addEventListener('click', () => { panic(); baseMidi += 12; renderKeyboard(); });
  window.addEventListener('keydown', event => {
    if (event.code === 'Escape') { panic('All notes stopped.'); return; }
    if (event.repeat || event.ctrlKey || event.metaKey || event.altKey || !keyMap.has(event.code)) return;
    if (event.target.closest && event.target.closest('input, textarea, select, [contenteditable="true"], [role="textbox"]')) return;
    const midi = baseMidi + keyMap.get(event.code);
    if (midi > 84) return;
    event.preventDefault();
    startHold(`key:${event.code}`, midi);
  });
  window.addEventListener('keyup', event => { endHold(`key:${event.code}`); endHold(`focus:${event.code}`); });
  window.addEventListener('blur', () => panic());
  document.addEventListener('visibilitychange', () => { if (document.hidden) panic(); });
  window.addEventListener('pagehide', () => panic());
  window.addEventListener('facet:reference-play', () => panic());
  document.addEventListener('play', event => { if (event.target instanceof HTMLMediaElement) panic(); }, true);
  const resizeKeyboard = () => { panic(); renderKeyboard(); };
  if (compact.addEventListener) compact.addEventListener('change', resizeKeyboard);
  else compact.addListener(resizeKeyboard);
  window.FacetKeyboard = Object.freeze({ getState: snapshot, getAudioNodes: () => ({ context, output: master, worklet }), panic: () => panic('All notes stopped.') });
  updateOutputs(); renderKeyboard();
})();
