/* Alloy factory role bank. Playback uses each complete saved render recipe. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const sectionDefinitions = [
    { id: "presets", title: "Presets", sources: ["presets"], description: "Twenty-four factory patches, four per instrument: the current reference and three roles from the comparison you preferred. Each patch carries its own authored configuration." },
    { id: "voices", title: "Voices", sources: ["voices"], description: "The selected voice defaults across registers and velocities. These studies use the voice foundations; factory presets carry their own authored configurations." },
    { id: "controls", title: "Controls", sources: ["sweeps", "grids", "controls"], description: "Five-point macro sweeps and interaction grids. Choose a study below and compare adjacent steps at the same note and velocity." },
    { id: "fusion", title: "Fusion", sources: ["fuse", "fusion"], description: "Matched-level comparisons with the direct attack preserved. Switch the attack modulation on and off at the same playback position; listen for the change inside the tonal body." },
    { id: "hold", title: "Hold", sources: ["hold"], description: "Settled, loop-only renders with restrained material excitation. The opening strike is omitted. Web Audio loops the complete rendered buffer without playback gaps." },
    { id: "stress", title: "Stress", sources: ["extremes", "stress"], description: "Difficult macro corners and high-drive combinations. Listen for pitch support, harsh sidebands, unexpected noise, and clean endings." }
  ];
  const factoryRoleDescriptions = Object.freeze({
    "Wooden Current": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Rounded Plug": "Muted bass anchor · Soft contact → nearly pure root → short rounded decay.",
    "Elastic Touch": "Rubbery bass attack · Hard contact → bright phase bend → hollow octave body.",
    "Hollow Rail": "Odd harmonic bass line · Firm onset → persistent hollow reed spectrum → deliberate release.",
    "Tine Alloy": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Felt Relay": "Damped wooden key · Rounded hammer → warm harmonic body → compact tail.",
    "Long Prism": "Ringing electric key · Sharp tine → strong upper harmonics → long glassy decay.",
    "Reed Switch": "Hollow electric key · Soft click → odd harmonic reed tone → steady held body and release.",
    "Muted Fusion": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Damped Pick": "Muted pluck · Small pick click → compact harmonic body → rapid damping.",
    "Open Chime": "Ringing wire pluck · Bright wire impact → separated upper harmonics → long ring.",
    "Nasal Snap": "Biting pluck lead · Hard narrow pick → strong decaying phase buzz → dry pitched finish.",
    "Breath Metal": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Round Horn": "Warm sustained horn · Rounded swell → low harmonic body → soft breath-free release.",
    "Hollow Copper": "Hollow reed horn · Clear tongue → pronounced odd harmonics → level sustained line.",
    "Bright Stamp": "Brass stab · Fast bright tongue → nested phase bite → short punched release.",
    "Drawn Wire": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Dark Bow": "Slow wooden string · Slow bow entrance → dark harmonic sustain → long soft release.",
    "Picked Fibre": "Short picked string · Immediate pick → bright harmonic ping → fast damped finish.",
    "Beating Glass": "Shimmering string sustain · Gentle onset → close detuned partial beating → long clear release.",
    "Soft Forge": "Current factory reference · The first factory preset at the same C3 and velocity as every candidate.",
    "Felt Drop": "Soft percussive key · Gentle tap → nearly pure warm note → compact felt decay.",
    "Velvet Rise": "Soft slow pad · Slow rounded swell → warm even-harmonic sustain → long falling release.",
    "Hollow Glow": "Soft odd harmonic pad · Soft bloom → hollow organ-like harmonic body → clear lingering release."
  });
  const macroNames = ["MATERIAL", "FUSE", "EDGE", "BODY", "LENGTH", "HOLD"];
  const reviewKey = "snipsnap.alloy.factory-roles.reviews.v1";
  const selectedVoices = Object.freeze({ BASS: "original", KEY: "revised", PLUCK: "revised", BRASS: "revised", STRING: "revised", SOFT: "original" });
  const state = { manifest: null, sections: [], clips: [], section: "presets", selected: null, filters: { voice: "", group: "", note: "" }, reviews: {}, audioContext: null, buffer: null, source: null, sourceGain: null, playing: false, offset: 0, startedAt: 0, loop: false, selectionToken: 0, playbackToken: 0, previewObserver: null, decodeJobs: 0, decodeQueue: [], buffers: new Map(), peaks: new Map(), pending: new Map(), storageAvailable: true };
  try { const stored = JSON.parse(localStorage.getItem(reviewKey) || "{}"); state.reviews = stored && typeof stored === "object" && !Array.isArray(stored) ? stored : {}; } catch (_) { state.storageAvailable = false; }

  const escapeText = value => String(value ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const titleCase = text => String(text || "").toLowerCase().replace(/(^|[ _-])\w/g, value => value.toUpperCase()).replace(/_/g, " ");
  const timeLabel = seconds => { const safe = Math.max(0, Number(seconds) || 0); return `${Math.floor(safe / 60)}:${(safe % 60).toFixed(1).padStart(4, "0")}`; };
  const pathFor = file => typeof file === "string" ? file : (file && (file.file || file.path || file.url)) || "";
  const macroValue = (clip, key) => { const value = clip.macros?.[key] ?? clip.macros?.[key.toLowerCase()]; return typeof value === "number" && Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : null; };
  const sectionClips = () => state.sections.find(section => section.id === state.section)?.clips || [];
  const filteredClips = () => sectionClips().filter(clip => (!state.filters.voice || clip.voice === state.filters.voice) && (!state.filters.group || clip.group === state.filters.group) && (!state.filters.note || clip.note === state.filters.note));
  const clipById = id => state.clips.find(clip => clip.id === id);
  const selectedVersion = voice => String(state.manifest?.selectedVoices?.[voice] || selectedVoices[voice] || "selected");
  const safeUrl = file => { try { const url = new URL(pathFor(file), document.baseURI); return ["http:", "https:", "file:"].includes(url.protocol) ? url.href : ""; } catch (_) { return ""; } };

  function normalizeManifest(manifest) {
    const sourceSections = Array.isArray(manifest.sections) ? manifest.sections : [];
    state.clips = sourceSections.flatMap(section => (section.clips || []).map((clip, index) => ({ ...clip, detail: String(section.id || "").toLowerCase() === "presets" ? factoryRoleDescriptions[clip.title || clip.name] || clip.detail : clip.detail, id: String(clip.id || `${section.id}-${index}`), title: clip.title || clip.name || `Render ${index + 1}`, file: clip.file || clip.src, voice: String(clip.voice || "ALLOY").toUpperCase(), note: String(clip.note || (clip.midi != null ? `MIDI ${clip.midi}` : "")).replace(/♯/g, "#").replace(/♭/g, "b"), group: String(clip.group || ""), duration: Number(clip.duration) || 0, sourceSection: String(section.id || "").toLowerCase(), loop: Boolean(clip.loop) })));
    state.sections = sectionDefinitions.map(definition => ({ ...definition, clips: state.clips.filter(clip => definition.sources.includes(clip.sourceSection)) }));
    for (const clip of state.clips) {
      if (Array.isArray(clip.waveform) && clip.waveform.length && clip.waveform.every(value => Number.isFinite(value) && value >= 0)) {
        state.peaks.set(clip.file, new Float32Array(clip.waveform.flatMap(value => [-value, value])));
      }
    }
  }

  async function loadManifest() {
    $("load-status").hidden = false;
    $("load-error").hidden = true;
    try {
      const response = await fetch("manifest.json", { cache: "no-cache" });
      if (!response.ok) throw new Error(`The server returned ${response.status} for manifest.json.`);
      const manifest = await response.json();
      normalizeManifest(manifest);
      if (!state.clips.length || !state.clips.every(clip => clip.file)) throw new Error("The manifest has no playable renders.");
      state.manifest = manifest;
      if (manifest.stage !== "factory-role-release") throw new Error("This folder does not contain the factory role pack.");
      $("preset-count").textContent = state.sections.find(section => section.id === "presets").clips.length;
      $("load-status").hidden = true;
      $("listening-desk").hidden = false;
      $("library-tools").hidden = false;
      renderTabs();
      renderVoiceChoices();
      renderDownloads(manifest.downloads || []);
      const hashSection = location.hash.slice(1).toLowerCase();
      if (state.sections.some(section => section.id === hashSection)) state.section = hashSection;
      if (!sectionClips().length) state.section = state.sections.find(section => section.clips.length).id;
      switchSection(state.section, false);
    } catch (error) {
      $("load-status").hidden = true;
      $("load-error").hidden = false;
      $("load-error-detail").textContent = `${error.message} Check your connection and try again.`;
      $("clip-count").textContent = "Library unavailable";
    }
  }

  function renderTabs() {
    $("section-tabs").innerHTML = state.sections.map(section => `<button class="tab" type="button" id="tab-${section.id}" role="tab" aria-selected="${section.id === state.section}" aria-controls="section-panel" tabindex="${section.id === state.section ? 0 : -1}" data-section="${section.id}">${section.title}<span>${section.clips.length}</span></button>`).join("");
  }

  function renderVoiceChoices() {
    $("chosen-voices").innerHTML = Object.keys(selectedVoices).map(voice => `<button type="button" data-browse-voice="${voice}" aria-pressed="${state.filters.voice === voice}"><span>${voice}</span><small>${escapeText(titleCase(selectedVersion(voice)))} foundation</small></button>`).join("");
  }

  function updateVoiceChoices() {
    $("chosen-voices").querySelectorAll("[data-browse-voice]").forEach(button => button.setAttribute("aria-pressed", String(state.section === "presets" && state.filters.voice === button.dataset.browseVoice)));
  }

  function switchSection(id, focus = false) {
    const section = state.sections.find(section => section.id === id);
    if (!section) return;
    state.section = id;
    state.filters = { voice: "", group: "", note: "" };
    for (const tab of $("section-tabs").querySelectorAll("[role=tab]")) {
      const active = tab.dataset.section === id;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && focus) { tab.focus(); tab.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" }); }
    }
    $("section-panel").setAttribute("aria-labelledby", `tab-${id}`);
    $("section-title").textContent = section.title;
    $("section-description").textContent = section.description;
    renderFilters();
    renderCards();
    updateVoiceChoices();
    if (section.clips.length) selectClip(section.clips[0].id);
    $("library-hint").textContent = id === "fusion" ? "Use the player toggles for a same-cue comparison." : id === "controls" ? "Filter by study to hear a sweep or interaction." : "Choose a render to listen.";
  }

  function setOptions(element, values, placeholder) {
    element.innerHTML = `<option value="">${placeholder}</option>${values.map(value => `<option value="${escapeText(value)}">${escapeText(value)}</option>`).join("")}`;
  }

  function renderFilters() {
    const clips = sectionClips();
    const voices = [...new Set(clips.map(clip => clip.voice))].sort();
    const groups = [...new Set(clips.map(clip => clip.group).filter(Boolean))];
    const notes = [...new Set(clips.map(clip => clip.note).filter(Boolean))].sort((a, b) => (clips.find(clip => clip.note === a)?.midi || 0) - (clips.find(clip => clip.note === b)?.midi || 0));
    setOptions($("voice-filter"), voices, "All voices");
    setOptions($("group-filter"), groups, "All studies");
    setOptions($("note-filter"), notes, "All notes");
    $("group-filter-wrap").hidden = groups.length < 2 || state.section === "presets";
    $("note-filter-wrap").hidden = notes.length < 2;
  }

  function renderCards() {
    if (state.previewObserver) state.previewObserver.disconnect();
    const clips = filteredClips();
    $("clip-count").textContent = `${clips.length} ${clips.length === 1 ? "render" : "renders"}`;
    $("empty-filter").hidden = clips.length > 0;
    $("clip-grid").innerHTML = clips.map((clip, index) => {
      const review = state.reviews[clip.id];
      const tag = clip.loop ? "HELD LOOP" : clip.note;
      const detail = clip.detail || [clip.note, clip.velocity != null ? `velocity ${clip.velocity}` : "", clip.group].filter(Boolean).join(" · ");
      const fuse = macroValue(clip, "FUSE");
      return `<article class="clip-card${state.selected?.id === clip.id ? " is-selected" : ""}" data-clip-id="${escapeText(clip.id)}"><div class="card-top"><span>${escapeText(clip.voice)} / ${clip.sourceSection === "presets" ? "FACTORY ROLE" : "RENDER STUDY"}${clip.ablation ? " / ABLATION" : ""}</span><span class="card-number">${String(index + 1).padStart(2, "0")}</span></div><button class="card-title" type="button" data-select="${escapeText(clip.id)}">${escapeText(clip.title)}</button><p class="card-detail">${escapeText(detail)}</p><div class="card-wave-wrap is-loading"><canvas class="card-wave" data-wave="${escapeText(clip.id)}" aria-hidden="true"></canvas></div><div class="card-bottom"><span class="card-tags"><span>${escapeText(tag)}</span>${fuse != null ? `<span>FUSE ${fuse.toFixed(2)}</span>` : ""}</span><span class="card-verdict">${escapeText(review?.verdict ? titleCase(review.verdict) : "")}</span><button class="card-play" type="button" data-play="${escapeText(clip.id)}" aria-label="Play ${escapeText(clip.title)}"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 3 11 7-11 7z"/></svg></button></div></article>`;
    }).join("");
    observeWaveforms();
    updateCardStates();
  }

  function ensureAudioContext() {
    if (!state.audioContext) {
      const Constructor = window.AudioContext || window.webkitAudioContext;
      if (!Constructor) throw new Error("This browser does not support Web Audio. Download a WAV to listen.");
      state.audioContext = new Constructor();
    }
    return state.audioContext;
  }

  function queueDecode(job, priority = false) {
    return new Promise((resolve, reject) => {
      const queued = { job, resolve, reject };
      if (priority) state.decodeQueue.unshift(queued); else state.decodeQueue.push(queued);
      pumpDecodes();
    });
  }

  function pumpDecodes() {
    while (state.decodeJobs < 3 && state.decodeQueue.length) {
      const entry = state.decodeQueue.shift();
      state.decodeJobs++;
      Promise.resolve().then(entry.job).then(entry.resolve, entry.reject).finally(() => { state.decodeJobs--; pumpDecodes(); });
    }
  }

  async function getBuffer(clip, priority = false) {
    if (state.buffers.has(clip.file)) {
      const buffer = state.buffers.get(clip.file);
      state.buffers.delete(clip.file);
      state.buffers.set(clip.file, buffer);
      return buffer;
    }
    if (state.pending.has(clip.file)) return state.pending.get(clip.file);
    const promise = queueDecode(async () => {
      const response = await fetch(safeUrl(clip.file));
      if (!response.ok) throw new Error(`The audio file could not load (${response.status}).`);
      const buffer = await ensureAudioContext().decodeAudioData(await response.arrayBuffer());
      state.buffers.set(clip.file, buffer);
      state.peaks.set(clip.file, waveformPeaks(buffer));
      while (state.buffers.size > 16) { const oldest = state.buffers.keys().next().value; state.buffers.delete(oldest); }
      return buffer;
    }, priority).finally(() => state.pending.delete(clip.file));
    state.pending.set(clip.file, promise);
    return promise;
  }

  function waveformPeaks(buffer) {
    const samples = buffer.getChannelData(0);
    const length = 420;
    const peaks = new Float32Array(length * 2);
    for (let i = 0; i < length; i++) {
      const start = Math.floor(i * samples.length / length);
      const end = Math.max(start + 1, Math.floor((i + 1) * samples.length / length));
      let low = 0, high = 0;
      for (let j = start; j < end; j++) { low = Math.min(low, samples[j]); high = Math.max(high, samples[j]); }
      peaks[i * 2] = low; peaks[i * 2 + 1] = high;
    }
    return peaks;
  }

  function drawWaveform(canvas, peaks, main = false) {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(rect.width * dpr); canvas.height = Math.round(rect.height * dpr);
    const context = canvas.getContext("2d");
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    const width = rect.width, height = rect.height, center = height / 2;
    context.clearRect(0, 0, width, height);
    context.strokeStyle = main ? "#59654e" : "#d8ddce";
    context.lineWidth = .5;
    context.beginPath(); context.moveTo(0, center); context.lineTo(width, center); context.stroke();
    if (main) {
      context.strokeStyle = "#35412d";
      for (let x = 0; x <= width; x += width / 8) { context.beginPath(); context.moveTo(x, 13); context.lineTo(x, height - 13); context.stroke(); }
    }
    if (!peaks) return;
    let absolutePeak = .01;
    for (const peak of peaks) absolutePeak = Math.max(absolutePeak, Math.abs(peak));
    const scale = (height * (main ? .37 : .38)) / absolutePeak;
    const columns = Math.min(Math.floor(width / (main ? 2.8 : 2.4)), peaks.length / 2);
    context.strokeStyle = main ? "#b5c79c" : "#8b9b77";
    context.lineWidth = main ? 1.3 : 1.2;
    context.lineCap = "round";
    for (let i = 0; i < columns; i++) {
      const index = Math.floor(i * peaks.length / 2 / columns) * 2;
      const x = (i + .5) * width / columns;
      context.beginPath(); context.moveTo(x, center + peaks[index] * scale); context.lineTo(x, Math.max(center + .8, center + peaks[index + 1] * scale)); context.stroke();
    }
  }

  function observeWaveforms() {
    const loadPreview = async canvas => {
      const clip = clipById(canvas.dataset.wave);
      if (!clip) return;
      try {
        if (!state.peaks.has(clip.file)) await getBuffer(clip);
        if (canvas.isConnected) { drawWaveform(canvas, state.peaks.get(clip.file)); canvas.parentElement.classList.remove("is-loading"); }
      } catch (_) { if (canvas.isConnected) { canvas.parentElement.classList.remove("is-loading"); canvas.parentElement.title = "Waveform unavailable. Select this render to retry."; } }
    };
    if ("IntersectionObserver" in window) {
      state.previewObserver = new IntersectionObserver(entries => entries.forEach(entry => { if (entry.isIntersecting) { state.previewObserver.unobserve(entry.target); loadPreview(entry.target); } }), { rootMargin: "120px" });
      $("clip-grid").querySelectorAll("[data-wave]").forEach(canvas => state.previewObserver.observe(canvas));
    } else $("clip-grid").querySelectorAll("[data-wave]").forEach(loadPreview);
  }

  async function selectClip(id, autoplay = false, preservePosition = null) {
    const clip = clipById(id);
    if (!clip) return;
    const token = ++state.selectionToken;
    const playbackToken = state.playbackToken;
    const wasPlaying = state.playing;
    const position = preservePosition == null ? 0 : preservePosition;
    if (autoplay || (preservePosition != null && wasPlaying)) {
      try { await ensureAudioContext().resume(); } catch (error) { $("audio-status").textContent = error.message; }
    }
    if (token !== state.selectionToken) return;
    stopSource();
    state.selected = clip;
    state.buffer = null;
    state.offset = position;
    state.loop = clip.loop;
    updatePlayerDetails();
    updateCardStates();
    $("wave-message").hidden = false;
    $("wave-message").textContent = "Loading the actual waveform…";
    $("audio-status").textContent = "";
    $("play-button").disabled = true;
    if (state.peaks.has(clip.file)) { drawWaveform($("main-wave"), state.peaks.get(clip.file), true); $("wave-message").hidden = true; }
    try {
      const buffer = await getBuffer(clip, true);
      if (token !== state.selectionToken) return;
      state.buffer = buffer;
      state.offset = state.loop ? position % buffer.duration : Math.min(position, Math.max(0, buffer.duration - .001));
      $("duration").textContent = timeLabel(buffer.duration);
      $("wave-message").hidden = true;
      $("play-button").disabled = false;
      drawWaveform($("main-wave"), state.peaks.get(clip.file), true);
      updateProgress();
      if (playbackToken === state.playbackToken && (autoplay || (preservePosition != null && wasPlaying))) startPlayback();
      if (clip.pair) {
        const counterpart = state.clips.find(item => item.pair === clip.pair && item.id !== clip.id);
        if (counterpart) getBuffer(counterpart, true).catch(() => { /* The A/B selection reports its own download error. */ });
      }
    } catch (error) {
      if (token !== state.selectionToken) return;
      $("wave-message").textContent = "Audio unavailable";
      $("audio-status").textContent = `${error.message} Select the render again to retry, or download its WAV.`;
      $("play-button").disabled = true;
    }
  }

  function updatePlayerDetails() {
    const clip = state.selected;
    if (!clip) return;
    $("player-title").textContent = clip.title;
    $("player-voice").textContent = clip.voice;
    $("selected-version").textContent = `${clip.sourceSection === "presets" ? "Factory role" : "Render study"} · ${titleCase(selectedVersion(clip.voice))} foundation`;
    $("player-detail").textContent = clip.detail || "Listen for the attack changing the tonal body as it enters.";
    $("player-category").textContent = state.section === "presets" ? "FACTORY PRESET" : clip.loop ? "SETTLED / LOOP-ONLY RENDER" : `${state.sections.find(section => section.id === state.section)?.title || "ALLOY"} / OFFLINE RENDER`;
    $("duration").textContent = timeLabel(clip.duration);
    updateRepeatButton();
    $("loop-button").title = clip.loop ? "Repeat the complete settled buffer without playback gaps" : "Repeat this complete finite render";
    const seam = clip.exportedSeamError ?? clip.seamError;
    $("player-tags").innerHTML = [clip.note, clip.velocity != null ? `VEL ${clip.velocity}` : "", clip.loop ? "LOOP ONLY" : "FINITE", seam != null && clip.loop ? `SEAM ${Number(seam).toExponential(1)}` : ""].filter(Boolean).map(tag => `<span>${escapeText(tag)}</span>`).join("");
    $("wav-download").href = safeUrl(clip.file);
    $("wav-download").setAttribute("download", `${clip.id}.wav`);
    $("raw-download").hidden = !clip.rawFile;
    if (clip.rawFile) { $("raw-download").href = safeUrl(clip.rawFile); $("raw-download").setAttribute("download", `${clip.id}-raw.wav`); $("raw-download").title = "Dry render before listening-level matching."; }
    const patch = clip.ablation ? "" : pathFor(clip.patchFile);
    $("patch-download").hidden = !patch;
    if (patch) { $("patch-download").href = safeUrl(patch); $("patch-download").setAttribute("download", `${clip.id}.json`); }
    const renderRecipe = pathFor(clip.renderRecipeFile);
    $("render-recipe-download").hidden = !renderRecipe;
    if (renderRecipe) { $("render-recipe-download").href = safeUrl(renderRecipe); $("render-recipe-download").setAttribute("download", `${clip.id}-render-recipe.json`); $("render-recipe-download").title = "Exact render settings, including the attack-modulation state."; }
    $("macro-grid").innerHTML = macroNames.map(key => {
      const value = macroValue(clip, key);
      return `<div class="macro-cell${key === "FUSE" ? " is-fuse" : key === "HOLD" ? " is-hold" : ""}"><div class="macro-head"><span>${key}</span><span class="macro-value">${value == null ? "—" : value.toFixed(2)}</span></div><div class="macro-track" style="--value:${value == null ? 0 : value * 100}%" aria-hidden="true"></div></div>`;
    }).join("");
    const index = sectionClips().findIndex(item => item.id === clip.id);
    $("review-index").textContent = `${String(index + 1).padStart(2, "0")} / ${sectionClips().length}`;
    const review = state.reviews[clip.id] || {};
    $("review-note").value = review.note || "";
    document.querySelectorAll("[data-verdict]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.verdict === review.verdict)));
    const previousRender = review.updatedAt && (review.sourceSha256 !== state.manifest?.provenance?.sourceSha256 || review.rosterSha256 !== state.manifest?.provenance?.rosterSha256);
    $("review-saved").textContent = state.storageAvailable ? (previousRender ? "Note from an earlier render. Compare it with this audio before revising." : review.updatedAt ? "Factory-bank review saved on this device." : "These notes belong to the factory role bank.") : "Device storage unavailable. Export to keep your notes.";
    const paired = clip.pair ? state.clips.filter(item => item.pair === clip.pair) : [];
    const normal = paired.find(item => !item.ablation), ablated = paired.find(item => item.ablation);
    $("fusion-switch").hidden = !(normal && ablated);
    if (normal && ablated) {
      $("fusion-on").dataset.clip = normal.id;
      $("fusion-off").dataset.clip = ablated.id;
      $("fusion-on").setAttribute("aria-pressed", String(!clip.ablation));
      $("fusion-off").setAttribute("aria-pressed", String(Boolean(clip.ablation)));
    }
    updateProgress();
  }

  function playbackPosition() {
    if (!state.playing || !state.buffer) return state.offset;
    const position = state.offset + state.audioContext.currentTime - state.startedAt;
    return state.loop ? position % state.buffer.duration : Math.min(position, state.buffer.duration);
  }

  function stopSource() {
    const source = state.source, gain = state.sourceGain;
    state.source = null; state.sourceGain = null;
    if (source) {
      source.onended = null;
      const now = state.audioContext.currentTime;
      if (gain) {
        if (typeof gain.gain.cancelAndHoldAtTime === "function") gain.gain.cancelAndHoldAtTime(now);
        else { gain.gain.cancelScheduledValues(now); gain.gain.setValueAtTime(gain.gain.value, now); }
        gain.gain.linearRampToValueAtTime(0, now + .008);
      }
      try { source.stop(now + .012); } catch (_) { /* The source may already have ended. */ }
      setTimeout(() => { source.disconnect(); if (gain) gain.disconnect(); }, 32);
    } else if (gain) gain.disconnect();
    state.playing = false;
    updateTransport();
  }

  function startPlayback() {
    if (!state.buffer) return;
    if (state.source) stopSource();
    const context = ensureAudioContext();
    if (state.offset >= state.buffer.duration - .001) state.offset = 0;
    const source = context.createBufferSource();
    source.buffer = state.buffer;
    source.loop = state.loop;
    source.loopStart = 0;
    source.loopEnd = state.buffer.duration;
    const gain = context.createGain();
    gain.gain.setValueAtTime(0, context.currentTime);
    gain.gain.linearRampToValueAtTime(1, context.currentTime + .008);
    source.connect(gain); gain.connect(context.destination);
    state.source = source; state.sourceGain = gain;
    state.startedAt = context.currentTime;
    state.playing = true;
    source.onended = () => { if (state.source !== source) return; state.offset = 0; stopSource(); updateProgress(); };
    source.start(0, state.offset);
    $("audio-status").textContent = "";
    updateTransport();
  }

  async function togglePlayback() {
    if (!state.selected) return;
    const playbackToken = ++state.playbackToken;
    if (state.playing) { state.offset = playbackPosition(); stopSource(); updateProgress(); return; }
    try {
      await ensureAudioContext().resume();
      if (playbackToken !== state.playbackToken) return;
      if (!state.buffer) { await selectClip(state.selected.id, true); return; }
      startPlayback();
    } catch (error) { $("audio-status").textContent = error.message; }
  }

  async function switchFusionClip(id) {
    const clip = clipById(id);
    if (!clip) return;
    // Advance the token first so an A→B→A click sequence cancels B's pending load.
    const token = ++state.selectionToken;
    const playbackToken = state.playbackToken;
    if (id === state.selected?.id) return;
    try {
      if (state.playing) await ensureAudioContext().resume();
      const buffer = await getBuffer(clip, true);
      if (token !== state.selectionToken || playbackToken !== state.playbackToken) return;
      // Keep the current source running while loading its counterpart. The cue is
      // captured at the actual switch, then the old source fades into the new one.
      const position = playbackPosition(), playing = state.playing;
      stopSource();
      state.selected = clip; state.buffer = buffer;
      state.offset = state.loop ? position % buffer.duration : Math.min(position, Math.max(0, buffer.duration - .001));
      updatePlayerDetails();
      $("duration").textContent = timeLabel(buffer.duration);
      $("wave-message").hidden = true;
      drawWaveform($("main-wave"), state.peaks.get(clip.file), true);
      updateCardStates();
      updateProgress();
      if (playing) startPlayback();
    } catch (error) { if (token === state.selectionToken) $("audio-status").textContent = `${error.message} The current comparison remains selected.`; }
  }

  function updateTransport() {
    $("play-button").classList.toggle("is-playing", state.playing);
    $("play-label").textContent = state.playing ? "Pause" : "Play";
    $("play-button").setAttribute("aria-label", `${state.playing ? "Pause" : "Play"} selected render`);
    updateCardStates();
  }

  function updateRepeatButton() {
    $("loop-button").setAttribute("aria-pressed", String(state.loop));
    $("loop-button").innerHTML = `<span aria-hidden="true">↻</span> ${state.loop ? "Repeating" : "Repeat"}`;
  }

  function updateCardStates() {
    for (const card of $("clip-grid").querySelectorAll("[data-clip-id]")) {
      const selected = card.dataset.clipId === state.selected?.id;
      card.classList.toggle("is-selected", selected);
      card.classList.toggle("is-playing", selected && state.playing);
      const button = card.querySelector("[data-play]");
      if (button) {
        button.setAttribute("aria-label", `${selected && state.playing ? "Pause" : "Play"} ${clipById(card.dataset.clipId).title}`);
        button.innerHTML = selected && state.playing ? '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 3h4v14H5zM12 3h4v14h-4z"/></svg>' : '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 3 11 7-11 7z"/></svg>';
      }
    }
  }

  function updateProgress() {
    const position = playbackPosition();
    $("current-time").textContent = timeLabel(position);
    $("wave-playhead").hidden = !state.buffer;
    $("wave-playhead").style.left = `${state.buffer ? Math.min(100, position / state.buffer.duration * 100) : 0}%`;
    $("wave-seek").setAttribute("aria-label", `Audio position ${timeLabel(position)} of ${timeLabel(state.buffer?.duration || state.selected?.duration)}. Use left and right arrow keys to seek.`);
  }

  function seekTo(position) {
    if (!state.buffer) return;
    const playing = state.playing;
    stopSource();
    state.offset = Math.max(0, Math.min(position, state.buffer.duration - .001));
    if (playing) startPlayback();
    updateProgress();
  }

  function saveReview(verdict) {
    if (!state.selected) return;
    const previous = state.reviews[state.selected.id] || {};
    const review = { ...previous, clipId: state.selected.id, note: $("review-note").value, updatedAt: new Date().toISOString(), sourceSha256: state.manifest?.provenance?.sourceSha256, rosterSha256: state.manifest?.provenance?.rosterSha256, stage: state.manifest?.stage, patch: state.selected.completePatch, attackModulation: state.selected.attackModulation, renderRecipeFile: state.selected.renderRecipeFile };
    if (verdict !== undefined) review.verdict = previous.verdict === verdict ? "" : verdict;
    state.reviews[state.selected.id] = review;
    try { localStorage.setItem(reviewKey, JSON.stringify(state.reviews)); $("review-saved").textContent = "Factory-bank review saved on this device."; } catch (_) { state.storageAvailable = false; $("review-saved").textContent = "Device storage unavailable. Export to keep your notes."; }
    document.querySelectorAll("[data-verdict]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.verdict === review.verdict)));
    for (const card of $("clip-grid").querySelectorAll("[data-clip-id]")) { if (card.dataset.clipId === state.selected.id) card.querySelector(".card-verdict").textContent = review.verdict ? titleCase(review.verdict) : ""; }
  }

  function exportReview() {
    const review = { engine: "ALLOY", version: state.manifest?.version || "0.1", stage: state.manifest?.stage, selectedVoices: state.manifest?.selectedVoices, exportedAt: new Date().toISOString(), audition: state.manifest?.title || "Alloy factory role bank", provenance: state.manifest?.provenance, reviewCount: Object.keys(state.reviews).length, reviews: Object.entries(state.reviews).map(([id, notes]) => { const clip = clipById(id); return { id, title: clip?.title || id, voice: clip?.voice, file: clip?.file, note: clip?.note, velocity: clip?.velocity, macros: clip?.macros, ...notes }; }) };
    const url = URL.createObjectURL(new Blob([JSON.stringify(review, null, 2)], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = `alloy-factory-review-${new Date().toISOString().slice(0, 10)}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function renderDownloads(downloads) {
    const files = downloads.filter(download => pathFor(download));
    if (!files.length) return;
    let container = $("library-downloads");
    if (!container) { container = document.createElement("div"); container.id = "library-downloads"; container.className = "library-downloads"; $("section-panel").append(container); }
    container.innerHTML = `<span>TAKE IT WITH YOU</span>${files.map(file => `<a href="${escapeText(safeUrl(file))}" download>${escapeText(file.title || file.name || "Download")} <span aria-hidden="true">↓</span></a>`).join("")}`;
  }

  $("section-tabs").addEventListener("click", event => { const tab = event.target.closest("[data-section]"); if (tab) switchSection(tab.dataset.section); });
  $("section-tabs").addEventListener("keydown", event => {
    const tabs = [...$("section-tabs").querySelectorAll("[role=tab]")];
    const index = tabs.indexOf(event.target);
    if (index < 0) return;
    let next = null;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    if (next != null) { event.preventDefault(); switchSection(tabs[next].dataset.section, true); }
  });
  $("clip-grid").addEventListener("click", event => {
    const play = event.target.closest("[data-play]");
    if (play) { if (state.selected?.id === play.dataset.play) togglePlayback(); else selectClip(play.dataset.play, true); return; }
    const select = event.target.closest("[data-select]");
    if (select) selectClip(select.dataset.select);
  });
  $("chosen-voices").addEventListener("click", event => { const button = event.target.closest("[data-browse-voice]"); if (!button) return; switchSection("presets"); state.filters.voice = button.dataset.browseVoice; $("voice-filter").value = state.filters.voice; renderCards(); updateVoiceChoices(); const clips = filteredClips(); if (clips.length) selectClip(clips[0].id); $("audition-title").scrollIntoView({ behavior: "smooth", block: "start" }); });
  [["voice-filter", "voice"], ["group-filter", "group"], ["note-filter", "note"]].forEach(([id, key]) => $(id).addEventListener("change", event => { state.filters[key] = event.target.value; renderCards(); updateVoiceChoices(); const clips = filteredClips(); if (clips.length && !clips.some(clip => clip.id === state.selected?.id)) selectClip(clips[0].id); }));
  $("play-button").addEventListener("click", togglePlayback);
  $("stop-button").addEventListener("click", () => { state.playbackToken++; stopSource(); state.offset = 0; updateProgress(); });
  $("loop-button").addEventListener("click", () => { const position = playbackPosition(); const playing = state.playing; state.loop = !state.loop; if (playing) { stopSource(); state.offset = position; startPlayback(); } updateRepeatButton(); });
  $("wave-seek").addEventListener("click", event => { const rect = event.currentTarget.getBoundingClientRect(); seekTo((event.clientX - rect.left) / rect.width * (state.buffer?.duration || 0)); });
  $("wave-seek").addEventListener("keydown", event => { if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return; event.preventDefault(); seekTo(event.key === "Home" ? 0 : event.key === "End" ? state.buffer?.duration || 0 : playbackPosition() + (event.key === "ArrowLeft" ? -1 : 1)); });
  ["fusion-on", "fusion-off"].forEach(id => $(id).addEventListener("click", event => { const clipId = event.currentTarget.dataset.clip; if (clipId) switchFusionClip(clipId); }));
  document.querySelectorAll("[data-verdict]").forEach(button => button.addEventListener("click", () => saveReview(button.dataset.verdict)));
  $("review-note").addEventListener("input", () => saveReview());
  $("export-review").addEventListener("click", exportReview);
  $("retry-load").addEventListener("click", loadManifest);
  document.addEventListener("visibilitychange", () => { if (document.hidden) { state.playbackToken++; state.offset = playbackPosition(); stopSource(); updateProgress(); } });
  window.addEventListener("pagehide", () => { state.playbackToken++; stopSource(); });
  let resizeTimer;
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { if (state.selected) drawWaveform($("main-wave"), state.peaks.get(state.selected.file), true); $("clip-grid").querySelectorAll("[data-wave]").forEach(canvas => { const clip = clipById(canvas.dataset.wave); if (clip) drawWaveform(canvas, state.peaks.get(clip.file)); }); }, 100); });
  function tick() { if (state.playing) updateProgress(); requestAnimationFrame(tick); }
  requestAnimationFrame(tick);
  loadManifest();
})();
