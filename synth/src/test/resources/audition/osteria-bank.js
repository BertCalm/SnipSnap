/* A finite sample bank: prepared native previews, eight simultaneous notes, no live synthesis. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const manifestUrl = new URL("manifest.json", document.baseURI);
  const touches = ["soft", "medium", "hard"];
  const keyboard = ["1", "2", "3", "4", "q", "w", "e", "r", "a", "s", "d", "f", "z", "x", "c", "v"];
  const maxPreviewBytes = 1024 * 1024;
  const maxCacheBytes = 12 * 1024 * 1024;
  const maxArchiveBytes = 120 * 1024 * 1024;
  const state = {
    manifest: null, pads: [], clips: new Map(), touch: "medium", selected: "", loadToken: 0,
    cache: new Map(), cacheBytes: 0, wanted: new Set(), queue: [], jobs: new Map(), failures: new Map(),
    voices: new Map(), nextVoice: 0, stopped: false, libraryController: null,
    archiveController: null, archiveToken: 0, archiveObjectUrl: "", nativeObjectUrl: ""
  };
  const escape = value => String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
  const titleCase = value => String(value || "").toLowerCase().replace(/\b\w/g, ch => ch.toUpperCase());
  const voiceLabel = voice => voice === "QUARTET" ? "Note" : titleCase(voice);
  const megabytes = value => `${(value / (1024 * 1024)).toFixed(1)} MB`;
  const duration = value => `${Number(value).toFixed(2).replace(/0$/, "")} s`;
  const padById = id => state.pads.find(pad => pad.id === id);

  function resourceUrl(path) {
    if (typeof path !== "string" || !path) throw new Error("A bank resource has no usable path.");
    const url = new URL(path, manifestUrl);
    if (!["http:", "https:"].includes(url.protocol) || url.origin !== manifestUrl.origin) throw new Error("A bank resource is outside this listening room.");
    return url.href;
  }
  function status(message, error = false) {
    $("bank-status").textContent = message;
    $("bank-status").classList.toggle("is-error", error);
  }
  function clipFor(pad) {
    if (!pad) return null;
    return pad.clips.map(id => state.clips.get(id)).find(clip => clip?.touch === state.touch) || null;
  }
  function previewUrl(clip) { return resourceUrl(clip.preview); }
  function activeCount(id) { return [...state.voices.values()].filter(voice => voice.padId === id).length; }
  function cached(url) {
    const entry = state.cache.get(url);
    if (entry) { state.cache.delete(url); state.cache.set(url, entry); }
    return entry;
  }
  function deleteCached(url) {
    const entry = state.cache.get(url);
    if (!entry) return;
    URL.revokeObjectURL(entry.objectUrl); state.cacheBytes -= entry.bytes; state.cache.delete(url);
  }
  function makeCacheRoom(bytes) {
    const playingUrls = new Set([...state.voices.values()].map(voice => voice.url));
    const candidates = [...state.cache.keys()].sort((a, b) => Number(state.wanted.has(a)) - Number(state.wanted.has(b)));
    for (const url of candidates) {
      if (state.cacheBytes + bytes <= maxCacheBytes && state.cache.size < 48) return;
      if (!playingUrls.has(url) && !state.wanted.has(url)) deleteCached(url);
    }
    if (state.cacheBytes + bytes > maxCacheBytes || state.cache.size >= 48) throw new Error("The audio preparation limit was reached. Stop a few notes and retry.");
  }
  async function boundedBytes(response, limit, signal, progress) {
    if (!response.ok) throw new Error(`The request returned ${response.status}.`);
    if (response.url && new URL(response.url).origin !== manifestUrl.origin) throw new Error("The request left this listening room.");
    if (Number(response.headers.get("Content-Length")) > limit) throw new Error("The downloaded file exceeds its size limit.");
    if (!response.body?.getReader) {
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (signal.aborted) throw new DOMException("Cancelled", "AbortError");
      if (!bytes.length || bytes.length > limit) throw new Error("The downloaded file is empty or too large.");
      progress?.(bytes); return progress ? null : bytes;
    }
    const reader = response.body.getReader();
    const chunks = []; let size = 0;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        if (signal.aborted) throw new DOMException("Cancelled", "AbortError");
        size += value.byteLength;
        if (size > limit) { await reader.cancel(); throw new Error("The downloaded file exceeds its size limit."); }
        if (progress) progress(value); else chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    if (!size) throw new Error("The downloaded file is empty.");
    if (progress) return null;
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return bytes;
  }
  function pumpPreviews() {
    if (state.stopped) return;
    // A rapid touch switch can return to a URL whose earlier fetch was aborted.
    // Reconcile after every completed job, so those pads never stay preparing.
    const queued = new Set(state.queue);
    for (const url of state.wanted) {
      if (!state.cache.has(url) && !state.jobs.has(url) && !state.failures.has(url) && !queued.has(url)) {
        state.queue.push(url); queued.add(url);
      }
    }
    while (state.jobs.size < 3 && state.queue.length) {
      const url = state.queue.shift();
      if (!state.wanted.has(url) || state.cache.has(url) || state.jobs.has(url)) continue;
      const job = { controller: new AbortController(), timedOut: false };
      const timeout = setTimeout(() => { job.timedOut = true; job.controller.abort(); }, 15000);
      state.jobs.set(url, job);
      (async () => {
        try {
          const response = await fetch(url, { cache: "no-store", signal: job.controller.signal });
          const bytes = await boundedBytes(response, maxPreviewBytes, job.controller.signal);
          if (state.stopped || job.controller.signal.aborted || !state.wanted.has(url) || state.jobs.get(url) !== job) return;
          makeCacheRoom(bytes.length);
          state.cache.set(url, { bytes: bytes.length, objectUrl: URL.createObjectURL(new Blob([bytes], { type: "audio/mpeg" })) });
          state.cacheBytes += bytes.length; state.failures.delete(url);
        } catch (error) {
          if (state.stopped || !state.wanted.has(url) || (!job.timedOut && (error.name === "AbortError" || job.controller.signal.aborted))) return;
          state.failures.set(url, job.timedOut ? "Audio took too long to prepare." : error.message);
        } finally {
          clearTimeout(timeout);
          if (state.jobs.get(url) === job) state.jobs.delete(url);
          pumpPreviews(); updatePads(); updatePreparationStatus();
        }
      })();
    }
  }
  function prepareTouch() {
    if (!state.manifest || state.stopped) return;
    const selectedClip = clipFor(padById(state.selected));
    state.wanted = new Set([...(selectedClip ? [previewUrl(selectedClip)] : []), ...state.pads.map(pad => previewUrl(clipFor(pad)))]);
    for (const [url, job] of state.jobs) if (!state.wanted.has(url)) job.controller.abort();
    state.queue = [...state.wanted].filter(url => !state.cache.has(url) && !state.jobs.has(url) && !state.failures.has(url));
    pumpPreviews(); updatePads(); updatePreparationStatus();
  }
  function updatePreparationStatus() {
    if (!state.manifest || state.stopped) return;
    const ready = [...state.wanted].filter(url => state.cache.has(url)).length;
    const failures = [...state.wanted].filter(url => state.failures.has(url));
    $("retry-audio").hidden = !failures.length;
    if (failures.length) status(`${ready} of 16 ${state.touch} pads ready. ${failures.length} could not prepare. ${state.failures.get(failures[0])} Retry audio to try again.`, true);
    else if (ready < 16) status(`Preparing ${state.touch} pads… ${ready} of 16 ready. You can play the ready pads.`);
    else status(`All 16 ${state.touch} pads are ready. Tap a pad or use its keyboard key.`);
  }
  function updatePads() {
    for (const button of $("pad-grid").children) {
      const pad = padById(button.dataset.pad), clip = clipFor(pad);
      if (!clip) continue;
      const url = previewUrl(clip), ready = state.cache.has(url), playing = activeCount(pad.id);
      button.disabled = !ready;
      button.classList.toggle("is-selected", state.selected === pad.id);
      button.classList.toggle("is-playing", playing > 0);
      button.dataset.ready = String(ready); button.dataset.activeVoices = String(playing);
      button.setAttribute("aria-label", `Play ${pad.slot}, ${pad.presetName}, ${pad.noteName}, ${state.touch} touch${playing ? `; ${playing} sounding` : ready ? "; ready" : "; preparing"}`);
      const indicator = button.querySelector(".ready-state");
      indicator.textContent = playing ? playing > 1 ? `${playing} notes` : "Sounding" : ready ? "Ready" : state.failures.has(url) ? "Retry audio" : "Preparing";
      indicator.classList.toggle("is-playing", playing > 0);
    }
    $("voice-count").textContent = `${state.voices.size} / 8 notes`;
    $("stop-all").disabled = !state.voices.size;
  }
  function updateSelected() {
    const pad = padById(state.selected), clip = clipFor(pad);
    if (!pad || !clip) return;
    $("selected-slot").textContent = `${pad.slot} · ${titleCase(state.touch)} touch`;
    $("selected-name").textContent = pad.presetName;
    $("selected-family").textContent = `${voiceLabel(pad.voice)} · ${pad.noteName} · MIDI ${pad.midi}`;
    $("selected-description").textContent = pad.description || "One fixed note, with color developing through its held portion.";
    const details = [["Attack", `${Math.round(pad.attackSeconds * 1000)} ms`], ["Develops for", duration(pad.gateSeconds)], ["Release", duration(pad.releaseSeconds)], ["Touch", Number(clip.velocity).toFixed(2)], ["Total note", duration(clip.durationSeconds)], ["Root", pad.noteName]];
    $("selected-details").innerHTML = details.map(([label, value]) => `<div><dt>${escape(label)}</dt><dd>${escape(value)}</dd></div>`).join("");
    $("recipe-link").href = resourceUrl(clip.recipe); $("recipe-link").hidden = false;
  }
  function finishVoice(id, pause = false) {
    const voice = state.voices.get(id);
    if (!voice) return;
    state.voices.delete(id); voice.audio.onended = null; voice.audio.onerror = null;
    if (pause) voice.audio.pause();
    voice.audio.removeAttribute("src"); voice.audio.load(); voice.audio.remove(); updatePads();
  }
  function stopAll(announce = true) {
    for (const id of [...state.voices.keys()]) finishVoice(id, true);
    if (announce && state.manifest) {
      updatePreparationStatus();
      if ([...state.wanted].every(url => state.cache.has(url))) status("All notes stopped. The pads are ready for another touch.");
    }
  }
  function triggerPad(id) {
    const pad = padById(id), clip = clipFor(pad);
    if (!pad || !clip || state.stopped) return;
    state.selected = id; updateSelected();
    const url = previewUrl(clip), entry = cached(url);
    if (!entry) { status(state.failures.has(url) ? "This pad could not prepare. Choose Retry audio." : "This pad is still preparing. Try a ready pad, or tap again when it is ready.", true); updatePads(); return; }
    if (state.voices.size >= 8) finishVoice(state.voices.keys().next().value, true);
    const voiceId = ++state.nextVoice, audio = document.createElement("audio");
    audio.preload = "auto"; audio.volume = 1; audio.muted = false;
    audio.setAttribute("playsinline", ""); audio.dataset.pad = id; audio.dataset.clip = clip.id;
    audio.src = entry.objectUrl; $("voice-rack").append(audio);
    state.voices.set(voiceId, { audio, padId: id, url });
    audio.onended = () => finishVoice(voiceId);
    audio.onerror = () => { finishVoice(voiceId, true); state.failures.set(url, "This audio could not play."); deleteCached(url); updatePads(); updatePreparationStatus(); };
    // This play call belongs to the user's click or key press. Loading a preview
    // never starts a note on its own, and each voice owns its native audio state.
    try {
      const playing = audio.play();
      playing?.catch(error => { if (!state.voices.has(voiceId)) return; finishVoice(voiceId, true); status(error.name === "NotAllowedError" ? "Playback did not start. Tap the pad again to listen." : "This note could not start. Try the pad again.", true); });
    } catch (_) { finishVoice(voiceId, true); status("This note could not start. Try the pad again.", true); }
    updatePads();
  }
  function renderPads() {
    $("pad-grid").innerHTML = state.pads.map((pad, index) => `<button class="bank-pad ${escape(pad.voice.toLowerCase())}" type="button" data-pad="${escape(pad.id)}" aria-keyshortcuts="${escape(keyboard[index])}" disabled><span class="pad-topline"><span>${escape(pad.slot)}</span><kbd>${escape(keyboard[index].toUpperCase())}</kbd></span><span class="pad-name${pad.candidateId === "remembered-reed" ? " has-compact-name" : ""}"><span class="pad-name-full">${escape(pad.presetName)}</span>${pad.candidateId === "remembered-reed" ? '<span class="pad-name-compact" aria-hidden="true">Rem. Reed</span>' : ""}</span><span class="pad-note"><b>${escape(pad.noteName)}</b><span class="ready-state">Preparing</span></span></button>`).join("");
  }
  function selectTouch(touch) {
    if (!touches.includes(touch) || touch === state.touch) return;
    state.touch = touch;
    for (const button of document.querySelectorAll("[data-touch]")) button.setAttribute("aria-pressed", String(button.dataset.touch === touch));
    updateSelected(); prepareTouch();
  }
  function validateManifest(manifest) {
    if (manifest?.auditionKind !== "performance-bank" || manifest.voiceCount !== 6 || manifest.presetCount !== 12 || !Array.isArray(manifest.pads) || manifest.pads.length !== 16 || !Array.isArray(manifest.clips) || manifest.clips.length !== 48) throw new Error("The manifest does not describe a six-voice, sixteen-pad bank with three touches.");
    const clipIds = new Set(), padIds = new Set(), slots = new Set(), families = new Set(), candidates = new Set();
    const expectedVoices = ["QUARTET", "BASS", "PLUCK", "REED", "BOWED", "PAD"];
    for (const clip of manifest.clips) {
      if (typeof clip.id !== "string" || !/^[a-z0-9-]+$/.test(clip.id) || clipIds.has(clip.id) || !touches.includes(clip.touch) || !Number.isFinite(clip.velocity) || clip.velocity <= 0 || clip.velocity > 1 || !Number.isFinite(clip.durationSeconds) || clip.durationSeconds <= 0 || clip.durationSeconds > 10) throw new Error("The bank has an invalid rendered touch.");
      resourceUrl(clip.preview); resourceUrl(clip.recipe); clipIds.add(clip.id);
    }
    const byId = new Map(manifest.clips.map(clip => [clip.id, clip]));
    for (const pad of manifest.pads) {
      if (typeof pad.id !== "string" || !/^[a-z0-9-]+$/.test(pad.id) || padIds.has(pad.id) || !/^A(?:0[1-9]|1[0-6])$/.test(pad.slot) || slots.has(pad.slot) || typeof pad.presetName !== "string" || !expectedVoices.includes(pad.voice) || typeof pad.candidateId !== "string" || typeof pad.noteName !== "string" || !Number.isInteger(pad.midi) || ![pad.attackSeconds, pad.gateSeconds, pad.releaseSeconds].every(value => Number.isFinite(value) && value > 0) || !Array.isArray(pad.clips) || pad.clips.length !== 3) throw new Error("The bank has an invalid pad recipe.");
      const clips = pad.clips.map(id => byId.get(id));
      if (clips.some(clip => !clip || clip.padId !== pad.id) || new Set(clips.map(clip => clip.touch)).size !== 3) throw new Error("A pad is missing one of its three touches.");
      padIds.add(pad.id); slots.add(pad.slot); families.add(pad.voice); candidates.add(pad.candidateId);
    }
    if (families.size !== 6 || candidates.size !== 12) throw new Error("The bank is missing one of its six voices or twelve presets.");
    if (manifest.clips.some(clip => !padIds.has(clip.padId))) throw new Error("The bank contains a touch without a pad.");
    const archive = manifest.archive;
    if (!archive || !Number.isSafeInteger(archive.bytes) || archive.bytes <= 0 || archive.bytes > maxArchiveBytes || !Number.isInteger(archive.crc32) || archive.crc32 < 0 || archive.crc32 > 0xffffffff || !/^[0-9a-f]{64}$/.test(archive.sha256) || !Array.isArray(archive.parts) || !archive.parts.length || archive.parts.length > 16) throw new Error("The bank download information is incomplete.");
    archive.parts.forEach(resourceUrl);
    const presets = manifest.userPresets;
    if (!presets || presets.count !== 12 || !Number.isSafeInteger(presets.bytes) || presets.bytes <= 0 || presets.bytes > 2 * 1024 * 1024 || !/^[0-9a-f]{64}$/.test(presets.sha256)) throw new Error("The saved user presets are missing from the bank.");
    resourceUrl(presets.path);
  }
  async function loadLibrary() {
    const token = ++state.loadToken;
    state.libraryController?.abort(); state.libraryController = new AbortController();
    ++state.archiveToken; state.archiveController?.abort(); state.archiveController = null;
    if (state.archiveObjectUrl) URL.revokeObjectURL(state.archiveObjectUrl);
    if (state.nativeObjectUrl) URL.revokeObjectURL(state.nativeObjectUrl);
    state.archiveObjectUrl = ""; state.nativeObjectUrl = "";
    $("save-download").hidden = true; $("save-snipsnap").hidden = true; $("cancel-download").hidden = true;
    $("prepare-download").hidden = false; $("prepare-download").textContent = "Prepare bank downloads ↓";
    stopAll(false); state.manifest = null; state.stopped = false;
    for (const job of state.jobs.values()) job.controller.abort();
    for (const url of [...state.cache.keys()]) deleteCached(url);
    state.pads = []; state.clips.clear();
    state.wanted = new Set(); state.queue = []; state.failures.clear();
    $("library-error").hidden = true; $("bank-desk").hidden = true; $("prepare-download").disabled = true;
    status("Opening the bank…");
    try {
      const response = await fetch(manifestUrl, { cache: "no-store", signal: state.libraryController.signal });
      const bytes = await boundedBytes(response, 2 * 1024 * 1024, state.libraryController.signal);
      const manifest = JSON.parse(new TextDecoder().decode(bytes));
      if (token !== state.loadToken) return;
      validateManifest(manifest);
      state.manifest = manifest; state.clips = new Map(manifest.clips.map(clip => [clip.id, clip]));
      state.pads = [...manifest.pads].sort((a, b) => {
        const left = Number(a.slot.slice(1)) - 1, right = Number(b.slot.slice(1)) - 1;
        return Math.floor(right / 4) - Math.floor(left / 4) || left % 4 - right % 4;
      });
      state.selected = state.pads.find(pad => pad.slot === "A01")?.id || state.pads[0].id;
      renderPads(); updateSelected(); $("bank-desk").hidden = false;
      const gain = Number(manifest.browserPlayback?.commonGainDb);
      if (Number.isFinite(gain)) $("preview-note").textContent = gain === 0 ? "Previews represent the bank at soft, medium and hard strikes, preserving their difference in level and tone." : `All previews use the same ${gain > 0 ? "+" : ""}${gain.toFixed(1)} dB listening adjustment, preserving the soft-to-hard difference.`;
      $("archive-summary").textContent = `${megabytes(manifest.archive.bytes)} ZIP · 16 pads · 48 WAVs and recipes`;
      $("prepare-download").disabled = false; prepareTouch();
    } catch (error) {
      if (token !== state.loadToken || error.name === "AbortError") return;
      $("library-error-detail").textContent = error.message;
      $("library-error").hidden = false; status("The bank could not open. Try loading it again.", true);
    }
  }
  function archiveStatus(message, error = false) {
    $("download-status").textContent = message;
    $("download-status").classList.toggle("is-error", error);
  }
  function crc32(bytes) {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  }
  function storedZip(entries) {
    const local = [], directory = []; let offset = 0, directorySize = 0;
    for (const entry of entries) {
      const name = new TextEncoder().encode(entry.name);
      if (name.length > 65535 || !Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || entry.bytes > 0xffffffff || offset > 0xffffffff) throw new Error("The SnipSnap import exceeds its format limit.");
      const header = new Uint8Array(30 + name.length), view = new DataView(header.buffer);
      view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true); view.setUint16(6, 0x0800, true);
      view.setUint16(8, 0, true); view.setUint16(10, 0, true); view.setUint16(12, 0x0021, true);
      view.setUint32(14, entry.crc32, true); view.setUint32(18, entry.bytes, true); view.setUint32(22, entry.bytes, true);
      view.setUint16(26, name.length, true); view.setUint16(28, 0, true); header.set(name, 30);
      local.push(header, entry.blob);
      const record = new Uint8Array(46 + name.length), central = new DataView(record.buffer);
      central.setUint32(0, 0x02014b50, true); central.setUint16(4, 20, true); central.setUint16(6, 20, true);
      central.setUint16(8, 0x0800, true); central.setUint16(10, 0, true); central.setUint16(12, 0, true); central.setUint16(14, 0x0021, true);
      central.setUint32(16, entry.crc32, true); central.setUint32(20, entry.bytes, true); central.setUint32(24, entry.bytes, true);
      central.setUint16(28, name.length, true); central.setUint32(42, offset, true); record.set(name, 46);
      directory.push(record); directorySize += record.length; offset += header.length + entry.bytes;
    }
    const end = new Uint8Array(22), closing = new DataView(end.buffer);
    closing.setUint32(0, 0x06054b50, true); closing.setUint16(8, entries.length, true); closing.setUint16(10, entries.length, true);
    closing.setUint32(12, directorySize, true); closing.setUint32(16, offset, true);
    return new Blob([...local, ...directory, end], { type: "application/zip" });
  }
  async function fingerprint(bytes) {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    return [...digest].map(value => value.toString(16).padStart(2, "0")).join("");
  }
  async function prepareArchive() {
    if (!state.manifest || state.archiveController || state.archiveObjectUrl) return;
    const archive = state.manifest.archive;
    if (!globalThis.crypto?.subtle) { archiveStatus("This browser cannot verify the bank download. Open this listening room in a current browser and try again.", true); return; }
    const token = ++state.archiveToken, controller = new AbortController(); state.archiveController = controller;
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 120000);
    $("prepare-download").disabled = true; $("prepare-download").textContent = "Preparing bank…"; $("cancel-download").hidden = false;
    try {
      const presets = state.manifest.userPresets;
      const presetResponse = await fetch(resourceUrl(presets.path), { cache: "no-store", signal: controller.signal });
      const presetBytes = await boundedBytes(presetResponse, presets.bytes, controller.signal);
      if (presetBytes.length !== presets.bytes || await fingerprint(presetBytes) !== presets.sha256) throw new Error("The saved presets did not pass their file check. Prepare the download again.");
      const bytes = new Uint8Array(archive.bytes); let loaded = 0;
      for (const part of archive.parts) {
        if (controller.signal.aborted) throw new DOMException("Cancelled", "AbortError");
        const response = await fetch(resourceUrl(part), { cache: "no-store", signal: controller.signal });
        await boundedBytes(response, Math.min(25 * 1024 * 1024, archive.bytes - loaded), controller.signal, chunk => {
          if (loaded + chunk.length > archive.bytes) throw new Error("The bank download is larger than expected.");
          bytes.set(chunk, loaded); loaded += chunk.length;
          archiveStatus(`Preparing ${megabytes(loaded)} of ${megabytes(archive.bytes)}… ${Math.floor(loaded * 100 / archive.bytes)}%. You can keep playing.`);
        });
      }
      if (loaded !== archive.bytes) throw new Error("The bank download is incomplete.");
      archiveStatus("Checking the complete bank… You can keep playing.");
      const actual = await fingerprint(bytes);
      if (actual !== archive.sha256) throw new Error("The bank download did not pass its file check. Prepare it again.");
      if (token !== state.archiveToken || controller.signal.aborted) return;
      const bankBlob = new Blob([bytes], { type: "application/zip" });
      const nativeBlob = storedZip([
        { name: "Osteria-Six-Voice.xpn", bytes: archive.bytes, crc32: archive.crc32, blob: bankBlob },
        { name: "presets.json", bytes: presetBytes.length, crc32: crc32(presetBytes), blob: new Blob([presetBytes], { type: "application/json" }) }
      ]);
      state.archiveObjectUrl = URL.createObjectURL(bankBlob); state.nativeObjectUrl = URL.createObjectURL(nativeBlob);
      $("save-download").href = state.archiveObjectUrl;
      $("save-download").download = typeof archive.downloadName === "string" && /^[\w .-]+\.zip$/i.test(archive.downloadName) ? archive.downloadName : "SnipSnap-Osteria-Six-Voice-Bank.zip";
      $("save-snipsnap").href = state.nativeObjectUrl; $("save-snipsnap").download = typeof archive.snipSnapDownloadName === "string" && /^[\w .-]+\.zip$/i.test(archive.snipSnapDownloadName) ? archive.snipSnapDownloadName : "Osteria-Six-Voice-SnipSnap.zip";
      $("save-download").hidden = false; $("save-snipsnap").hidden = false; $("prepare-download").hidden = true;
      archiveStatus("The bank and saved presets are checked and ready. Choose Save for MPC 2 / 3 or Save for SnipSnap.");
    } catch (error) {
      if (token !== state.archiveToken) return;
      $("prepare-download").textContent = error.name === "AbortError" ? "Prepare bank downloads ↓" : "Retry bank downloads ↓";
      archiveStatus(timedOut ? "The bank took too long to prepare. Try the download again; the pads are still available." : error.name === "AbortError" ? "Bank preparation cancelled. The pads are still ready to play." : error.message, timedOut || error.name !== "AbortError");
    } finally {
      clearTimeout(timeout);
      if (token === state.archiveToken) { state.archiveController = null; $("prepare-download").disabled = false; $("cancel-download").hidden = true; }
    }
  }
  $("pad-grid").addEventListener("click", event => { const pad = event.target.closest("[data-pad]"); if (pad && !pad.disabled) triggerPad(pad.dataset.pad); });
  for (const button of document.querySelectorAll("[data-touch]")) button.addEventListener("click", () => selectTouch(button.dataset.touch));
  $("stop-all").addEventListener("click", () => stopAll());
  $("retry-library").addEventListener("click", loadLibrary);
  $("retry-audio").addEventListener("click", () => {
    for (const url of state.wanted) if (state.failures.has(url)) { state.failures.delete(url); deleteCached(url); }
    prepareTouch();
  });
  $("prepare-download").addEventListener("click", prepareArchive);
  $("cancel-download").addEventListener("click", () => state.archiveController?.abort());
  document.addEventListener("keydown", event => {
    if (event.repeat || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.target.closest("input,textarea,select,[contenteditable=true]")) return;
    if (event.key === "Escape") { event.preventDefault(); stopAll(); return; }
    const index = keyboard.indexOf(event.key.toLowerCase());
    if (index >= 0 && state.pads[index]) { event.preventDefault(); triggerPad(state.pads[index].id); }
  });
  window.addEventListener("pagehide", () => {
    state.stopped = true; ++state.loadToken; ++state.archiveToken;
    state.libraryController?.abort(); state.archiveController?.abort();
    for (const job of state.jobs.values()) job.controller.abort();
    state.queue = []; stopAll(false);
    for (const url of [...state.cache.keys()]) deleteCached(url);
    if (state.archiveObjectUrl) URL.revokeObjectURL(state.archiveObjectUrl);
    if (state.nativeObjectUrl) URL.revokeObjectURL(state.nativeObjectUrl);
  });
  window.addEventListener("pageshow", event => { if (event.persisted) loadLibrary(); });
  loadLibrary();
})();
