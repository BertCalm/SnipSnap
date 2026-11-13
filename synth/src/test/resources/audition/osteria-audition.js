/* Osteria listens to finite offline renders. No synthesis runs in this page. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const storageKey = "snipsnap-osteria-listening-v2";
  const manifestUrl = new URL("manifest.json", document.baseURI);
  const state = {
    manifest: null, groups: [], clips: [], group: "", selected: null,
    level: "matched", context: null, buffer: null, source: null,
    offset: 0, startedAt: 0, playing: false, animation: 0,
    nativeUrl: "", nativeSrc: "", mediaPending: false, mediaIntent: "ready",
    previewCache: new Map(), previewJobs: new Map(), previewQueue: [],
    previewFailures: new Map(), previewWanted: new Set(), previewStopped: false,
    loadToken: 0, playToken: 0, loading: false, loadPromise: null,
    controller: null, traceController: null, downloadController: null,
    downloadToken: 0, buffers: new Map(),
    bankController: null, bankToken: 0, bankObjectUrl: "",
    reviews: {}, storageAvailable: true, traceLoaded: false
  };
  const macroOrder = ["shore", "tension", "wander", "memory", "room", "hold"];
  const colors = ["#722f37", "#8b7947", "#466e70", "#ab7048"];
  const escape = value => String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
  const humanize = value => String(value ?? "").replace(/[-_]/g, " ").replace(/\b\w/g, ch => ch.toUpperCase());
  const secondsLabel = seconds => { const s = Math.max(0, Number(seconds) || 0); return `${Math.floor(s / 60)}:${(s % 60).toFixed(1).padStart(4, "0")}`; };
  const noteName = midi => Number.isFinite(Number(midi)) ? `${["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"][Number(midi) % 12]}${Math.floor(Number(midi) / 12) - 1}` : "";
  const clipById = id => state.clips.find(clip => clip.id === id);
  const audioFingerprint = clip => [clip.rawSha256 || resourcePath(clip.raw), clip.matchedSha256 || resourcePath(clip.matched)].join("|");
  const reviewIsCurrent = (clip, review) => Boolean(review && (review.audioFingerprint ? review.audioFingerprint === audioFingerprint(clip) : !(Number(clip.presetRevision) > 1)));
  const currentGroup = () => state.groups.find(group => group.id === state.group);
  const media = $("browser-audio");
  const usesNative = () => Boolean(state.nativeUrl);
  const playbackActive = () => state.playing || (usesNative() && (state.mediaPending || !media.paused));
  const resourcePath = file => typeof file === "string" ? file : file?.path || file?.file || "";
  function resourceUrl(file) {
    const path = resourcePath(file);
    if (!path) return "";
    try {
      const url = new URL(path, manifestUrl);
      if (!["http:", "https:", "file:"].includes(url.protocol)) return "";
      if (url.origin !== manifestUrl.origin) return "";
      return url.href;
    } catch (_) { return ""; }
  }
  const browserPreviewUrl = clip => resourceUrl(clip?.[state.level === "matched" ? "browserMatched" : "browserRaw"]);
  function cachedPreview(url) {
    const entry = state.previewCache.get(url);
    if (entry) { state.previewCache.delete(url); state.previewCache.set(url, entry); }
    return entry;
  }
  function setNativeSource(src) {
    state.nativeSrc = src;
    media.dataset.previewUrl = state.nativeUrl;
    media.volume = 1; media.muted = false;
    if (src) media.src = src; else media.removeAttribute("src");
    media.load();
  }
  function usePreparedPreview() {
    const entry = cachedPreview(state.nativeUrl);
    if (!entry) return false;
    if (state.nativeSrc !== entry.objectUrl) setNativeSource(entry.objectUrl);
    return true;
  }
  function trimPreviewCache() {
    let bytes = [...state.previewCache.values()].reduce((sum, entry) => sum + entry.bytes, 0);
    const entries = [...state.previewCache].sort(([a], [b]) => Number(state.previewWanted.has(a)) - Number(state.previewWanted.has(b)));
    for (const [url, entry] of entries) {
      if (state.previewCache.size <= 20 && bytes <= 4 * 1024 * 1024) break;
      if (entry.objectUrl === state.nativeSrc) continue;
      URL.revokeObjectURL(entry.objectUrl); state.previewCache.delete(url); bytes -= entry.bytes;
    }
  }
  function pumpPreviewQueue() {
    if (state.previewStopped) return;
    while (state.previewJobs.size < 3 && state.previewQueue.length) {
      const url = state.previewQueue.shift();
      if (!state.previewWanted.has(url) || state.previewCache.has(url) || state.previewJobs.has(url)) continue;
      const job = { controller: new AbortController(), timedOut: false };
      const timeout = setTimeout(() => { job.timedOut = true; job.controller.abort(); }, 12000);
      state.previewJobs.set(url, job);
      (async () => {
        try {
          const response = await fetch(url, { signal: job.controller.signal });
          if (!response.ok) throw new Error(`Audio request returned ${response.status}.`);
          const maximumBytes = 1024 * 1024;
          if (Number(response.headers.get("Content-Length")) > maximumBytes) throw new Error("The browser preview is too large.");
          const bytes = await response.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > maximumBytes) throw new Error("The browser preview is empty or too large.");
          if (job.controller.signal.aborted || state.previewStopped || !state.previewWanted.has(url) || state.previewJobs.get(url) !== job) return;
          const entry = { objectUrl: URL.createObjectURL(new Blob([bytes], { type: "audio/mpeg" })), bytes: bytes.byteLength };
          state.previewCache.set(url, entry); state.previewFailures.delete(url);
          if (state.nativeUrl === url) {
            usePreparedPreview();
            if (!playbackActive()) setAudioStatus("Ready. Press Play to listen.");
          }
          trimPreviewCache();
        } catch (error) {
          if ((!job.timedOut && (error.name === "AbortError" || job.controller.signal.aborted)) || !state.previewWanted.has(url) || state.previewJobs.get(url) !== job) return;
          const message = job.timedOut ? "The audio took too long to load." : error.message;
          state.previewFailures.set(url, message);
          if (state.nativeUrl === url) setAudioStatus(`${message} Press Retry to prepare the audio again, or download a WAV.`, true);
        } finally {
          clearTimeout(timeout);
          if (state.previewJobs.get(url) === job) state.previewJobs.delete(url);
          pumpPreviewQueue(); updateTransport(); updateProgress();
        }
      })();
    }
  }
  function prepareGroupPreviews() {
    if (state.previewStopped) return;
    // All current groups fit this limit. Only the visible group's current level
    // is prepared, with three requests and four MiB of retained preview data.
    const urls = [...new Set([state.nativeUrl, ...(currentGroup()?.clips || []).slice(0, 20).map(browserPreviewUrl)].filter(Boolean))];
    state.previewWanted = new Set(urls);
    for (const [url, job] of state.previewJobs) {
      if (!state.previewWanted.has(url)) { job.controller.abort(); state.previewJobs.delete(url); }
    }
    for (const url of state.previewFailures.keys()) { if (!state.previewWanted.has(url)) state.previewFailures.delete(url); }
    state.previewQueue = urls.filter(url => !state.previewCache.has(url) && !state.previewJobs.has(url) && !state.previewFailures.has(url));
    pumpPreviewQueue();
  }
  async function unpackAudioTransport(bytes, signal) {
    const header = new Uint8Array(bytes, 0, Math.min(2, bytes.byteLength));
    // Content-Encoding may already have unpacked the response. Inspect bytes,
    // rather than the filename, so ordinary WAVs follow the original path.
    if (header[0] !== 0x1f || header[1] !== 0x8b) return bytes;
    if (typeof DecompressionStream !== "function") throw new Error("This browser cannot open the audio file. Please try a current browser.");
    const maximumBytes = 32 * 1024 * 1024;
    const chunks = [];
    let reader, length = 0;
    try {
      reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"), { signal }).getReader();
      while (true) {
        if (signal?.aborted) throw new DOMException("Audio request cancelled.", "AbortError");
        const { value, done } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > maximumBytes) {
          await reader.cancel();
          throw new Error("This audio file is too large to open in the listening page.");
        }
        chunks.push(value);
      }
      const result = new Uint8Array(length);
      let at = 0;
      for (const chunk of chunks) { result.set(chunk, at); at += chunk.byteLength; }
      return result.buffer;
    } catch (error) {
      if (error.name === "AbortError" || length > maximumBytes) throw error;
      throw new Error("The audio file could not be opened. Check the connection and try again.");
    } finally { reader?.releaseLock(); }
  }
  async function downloadWav(link) {
    // Only one prepared download is kept at a time; decoded playback cache
    // remains separate and bounded. Downloading never changes the transport.
    state.downloadController?.abort();
    state.downloadController = new AbortController();
    const controller = state.downloadController, token = ++state.downloadToken;
    const selectedId = state.selected?.id;
    setAudioStatus("Preparing the WAV download…");
    try {
      const response = await fetch(link.href, { signal: controller.signal });
      if (!response.ok) throw new Error(`The WAV download returned ${response.status}.`);
      const bytes = await unpackAudioTransport(await response.arrayBuffer(), controller.signal);
      if (controller.signal.aborted || token !== state.downloadToken) return;
      const header = new Uint8Array(bytes, 0, Math.min(12, bytes.byteLength));
      if (header.length < 12 || String.fromCharCode(...header.slice(0, 4)) !== "RIFF" || String.fromCharCode(...header.slice(8, 12)) !== "WAVE") throw new Error("The downloaded file is not a WAV. Please try again.");
      const url = URL.createObjectURL(new Blob([bytes], { type: "audio/wav" }));
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = decodeURIComponent(new URL(link.href).pathname.split("/").pop()).replace(/\.gz$/i, "");
      document.body.append(anchor); anchor.click(); anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      if (state.selected?.id === selectedId) setAudioStatus("WAV download ready.");
    } catch (error) {
      if (error.name === "AbortError" || token !== state.downloadToken) return;
      if (state.selected?.id === selectedId) setAudioStatus(`${error.message} Try the download again.`, true);
    } finally { if (token === state.downloadToken) state.downloadController = null; }
  }
  const maximumBankBytes = 64 * 1024 * 1024;
  const bankFilename = "SnipSnap-Osteria-MPC2.zip";
  function resetBankDownload() {
    ++state.bankToken;
    state.bankController?.abort(); state.bankController = null;
    if (state.bankObjectUrl) URL.revokeObjectURL(state.bankObjectUrl);
    state.bankObjectUrl = "";
    $("bank-link").hidden = true; $("bank-link").removeAttribute("href");
    $("bank-prepare").hidden = true; $("bank-prepare").disabled = false;
    $("bank-status").textContent = "";
  }
  function configureBankDownload() {
    resetBankDownload();
    const bank = state.manifest?.playableBank;
    $("bank-play").hidden = !bank?.candidateBank;
    if (bank?.candidateBank) {
      $("bank-download").hidden = false;
      $("bank-play").href = resourceUrl(bank.auditionPage || "bank/");
      $("bank-status").textContent = "Play all 16 pads with soft, medium and hard touch, then save for MPC or SnipSnap.";
      return;
    }
    const directUrl = resourceUrl(bank?.path);
    const hasParts = Array.isArray(bank?.parts) && bank.parts.length > 0;
    $("bank-download").hidden = !directUrl && !hasParts;
    if (directUrl) {
      $("bank-link").href = directUrl; $("bank-link").download = bankFilename;
      $("bank-link").textContent = "Download the 16-pad bank ↓"; $("bank-link").hidden = false;
    } else if (hasParts) {
      $("bank-prepare").hidden = false; $("bank-prepare").textContent = "Prepare 16-pad bank ↓";
      $("bank-status").textContent = "Prepare the ZIP here, then choose Save 16-pad bank. Audio playback stays available.";
    }
  }
  async function readBankPart(response, maximumBytes, signal) {
    if (Number(response.headers.get("Content-Length")) > maximumBytes) throw new Error("The bank download exceeds its expected size.");
    if (!response.body?.getReader) {
      const bytes = await response.arrayBuffer();
      if (signal.aborted) throw new DOMException("Bank preparation cancelled.", "AbortError");
      if (!bytes.byteLength || bytes.byteLength > maximumBytes) throw new Error("A bank part is empty or exceeds its expected size.");
      return [new Uint8Array(bytes)];
    }
    const reader = response.body.getReader(), chunks = [];
    let length = 0;
    try {
      while (true) {
        if (signal.aborted) throw new DOMException("Bank preparation cancelled.", "AbortError");
        const { value, done } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > maximumBytes) {
          await reader.cancel();
          throw new Error("The bank download exceeds its expected size.");
        }
        chunks.push(value);
      }
      if (!length) throw new Error("A bank part is empty.");
      return chunks;
    } finally { reader.releaseLock(); }
  }
  async function prepareBankDownload() {
    if (state.bankController || state.bankObjectUrl) return;
    const bank = state.manifest?.playableBank, token = ++state.bankToken;
    const controller = new AbortController(); state.bankController = controller;
    $("bank-prepare").disabled = true; $("bank-prepare").textContent = "Preparing bank…";
    $("bank-status").textContent = "Preparing the bank ZIP…";
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 120000);
    try {
      const expectedBytes = Number(bank?.bytes);
      if (!Number.isSafeInteger(expectedBytes) || expectedBytes < 1 || expectedBytes > maximumBankBytes) throw new Error("The bank has no supported download size.");
      if (!Array.isArray(bank.parts) || !bank.parts.length || bank.parts.length > 8) throw new Error("The bank has no supported download parts.");
      const urls = bank.parts.map(resourceUrl);
      if (urls.some(url => !url)) throw new Error("A bank part has an unavailable download address.");
      if (!/^[a-f0-9]{64}$/i.test(bank.sha256 || "")) throw new Error("The bank has no valid integrity checksum.");
      const chunks = [];
      let length = 0;
      for (let index = 0; index < urls.length; index++) {
        $("bank-status").textContent = `Preparing bank: part ${index + 1} of ${urls.length}…`;
        const response = await fetch(urls[index], { signal: controller.signal });
        if (!response.ok) throw new Error(`Bank part ${index + 1} returned ${response.status}.`);
        const part = await readBankPart(response, expectedBytes - length, controller.signal);
        if (controller.signal.aborted || token !== state.bankToken) return;
        for (const bytes of part) { chunks.push(bytes); length += bytes.byteLength; }
      }
      if (length !== expectedBytes) throw new Error("The bank download is incomplete or has an unexpected size.");
      const zip = new Blob(chunks, { type: "application/zip" });
      if (window.crypto?.subtle) {
        $("bank-status").textContent = "Checking the bank ZIP…";
        const digest = await window.crypto.subtle.digest("SHA-256", await zip.arrayBuffer());
        const actual = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
        if (actual !== bank.sha256.toLowerCase()) throw new Error("The bank failed its integrity check.");
      }
      if (controller.signal.aborted || token !== state.bankToken) return;
      state.bankObjectUrl = URL.createObjectURL(zip);
      $("bank-link").href = state.bankObjectUrl; $("bank-link").download = bankFilename;
      $("bank-link").textContent = "Save 16-pad bank ↓"; $("bank-link").hidden = false;
      $("bank-prepare").hidden = true;
      $("bank-status").textContent = "Bank ready. Choose Save 16-pad bank to save the ZIP. It is prepared in this tab.";
    } catch (error) {
      if (token !== state.bankToken) return;
      $("bank-prepare").hidden = false; $("bank-prepare").textContent = "Retry bank preparation ↓";
      const reason = timedOut ? "The bank took too long to load." : error.name === "AbortError" ? "Bank preparation was interrupted." : error.message;
      $("bank-status").textContent = `${reason} Choose Retry bank preparation to try again.`;
    } finally {
      clearTimeout(timeout);
      if (token === state.bankToken) { state.bankController = null; $("bank-prepare").disabled = false; }
    }
  }
  function readReviews() {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
      if (saved && typeof saved === "object" && !Array.isArray(saved)) state.reviews = saved;
    } catch (_) { state.storageAvailable = false; }
  }
  function context() {
    if (!state.context) {
      const Constructor = window.AudioContext || window.webkitAudioContext;
      if (!Constructor) throw new Error("This browser cannot play Web Audio. Download a WAV to listen.");
      state.context = new Constructor();
      state.context.addEventListener("statechange", () => {
        if (!usesNative() && state.playing && state.context.state !== "running") {
          stopPlayback(false);
          setAudioStatus("Audio was interrupted. Press Play to continue.", true);
        }
      });
    }
    return state.context;
  }
  function normalize(manifest) {
    if (!manifest || !Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error("The manifest contains no renders.");
    const ids = new Set();
    state.clips = manifest.clips.map((clip, index) => {
      const id = String(clip.id || `render-${index + 1}`);
      if (ids.has(id)) throw new Error(`The manifest repeats a render ID: ${id}.`);
      ids.add(id);
      const hasWavs = resourceUrl(clip.raw) && resourceUrl(clip.matched);
      const hasPreviews = resourceUrl(clip.browserRaw) && resourceUrl(clip.browserMatched);
      if (!hasWavs && !hasPreviews) throw new Error(`A render has no usable Raw and Matched audio: ${id}.`);
      return { ...clip, id, group: String(clip.group || "renders"), title: String(clip.title || humanize(id)), description: String(clip.description || "Rendered listening study."), durationSeconds: Number(clip.durationSeconds) || 0 };
    });
    const groups = Array.isArray(manifest.groups) ? manifest.groups : [];
    const groupIds = [...new Set([...groups.map(group => String(group.id)), ...state.clips.map(clip => clip.group)])];
    state.groups = groupIds.map(id => {
      const group = groups.find(item => String(item.id) === id) || {};
      return { id, title: String(group.title || humanize(id)), description: String(group.description || "Compare the rendered studies below."), clips: state.clips.filter(clip => clip.group === id) };
    }).filter(group => group.clips.length);
    state.manifest = manifest;
  }
  async function loadLibrary() {
    $("library-status").hidden = false;
    $("library-error").hidden = true;
    $("retry-library").disabled = true;
    try {
      let manifest;
      try {
        const response = await fetch(manifestUrl, { cache: "no-cache" });
        if (!response.ok) throw new Error(`The server returned ${response.status} for manifest.json.`);
        manifest = await response.json();
      } catch (error) {
        try { manifest = JSON.parse($("embedded-manifest").textContent); }
        catch (_) { throw error; }
      }
      normalize(manifest);
      configureBankDownload();
      if (manifest.auditionKind === "voice-candidates") {
        document.querySelector(".edition").textContent = "A concept in sound · Study 04";
        document.querySelector(".hero-copy .eyebrow").textContent = "Bass · pluck · reed · pad";
        document.querySelector(".tagline").innerHTML = "Four voices.<br>One evolving note.";
        document.querySelector(".quartet-card .card-topline span").textContent = "Inside each voice";
        document.querySelector(".quartet-card > p").textContent = "Each candidate has one fixed root and one trigger. Four internal color layers move within one articulation.";
        document.querySelector(".card-bottomline").textContent = "Four internal layers";
        $("listening-title").textContent = "Which voice should we keep?";
        document.querySelector("#listening > .bench-note").textContent = "Start with Bass, Pluck, Reed and Pad at the same C3 and velocity. Their natural attacks and decays differ. Matched includes Reed at −1.5 dB and Pad at +1.5 dB. Raw preserves the original source levels. The approved Note is included as a reference.";
        $("guide-title").textContent = "Find a distinct character.";
        document.querySelector(".listening-guide > div > p:last-child").textContent = "These are provisional voice candidates. Listen for a useful identity while each remains one evolving note.";
        const guide = document.querySelectorAll(".listening-guide li div");
        for (const [index, text] of [
          ["Compare at one pitch.", "Hear all four candidates at C3, velocity 0.8 and the same held interval. Compare their color and natural articulation with the approved Note."],
          ["Try their registers.", "Hear Bass lower down, then Pluck, Reed and Pad higher. Pitch changes are separate examples, with one fixed note per render."],
          ["Hear the movement.", "Compare each candidate with its fixed-color counterpart. Mark Keep, Revise or Drop and export your notes."],
        ].entries()) { guide[index].querySelector("h3").textContent = text[0]; guide[index].querySelector("p").textContent = text[1]; }
        document.querySelector("#bank-download > .bench-note:last-child").textContent = "Original Note bank: the existing 16 pads and velocity layers. The four voice candidates are separate WAV downloads below and are not in this bank yet.";
        document.querySelector(".footer p").textContent = "Four provisional voices. One shared note behavior.";
      }
      if (manifest.auditionKind === "preset-candidates") {
        document.querySelector(".edition").textContent = "A concept in sound · Study 05";
        document.querySelector(".hero-copy .eyebrow").textContent = "The original eight preset comparisons";
        document.querySelector(".tagline").innerHTML = "Four voices.<br>Two directions each.";
        document.querySelector(".quartet-card .card-topline span").textContent = "Inside each preset";
        document.querySelector(".quartet-card > p").textContent = "One fixed note, one trigger. Four internal color layers travel together, with memory shaping how the tone develops.";
        document.querySelector(".card-bottomline").textContent = "Root · Body · Air · Detail";
        $("listening-title").textContent = "Which presets belong in the bank?";
        document.querySelector("#listening > .bench-note").textContent = "Roaming Bass now opens from a hollow edge into a fuller body. Both Reeds have more breath-led movement in their body and air. Anchor Bass, both Plucks and both Pads are retained for comparison. All eight are dry, at the same C3 and touch. Matched retains Reed at −1.5 dB and Pad at +1.5 dB.";
        $("guide-title").textContent = "Choose a useful direction.";
        document.querySelector(".listening-guide > div > p:last-child").textContent = "These are provisional recipes. Keep the presets you would use, and mark what should change before the new bank is built.";
        const guide = document.querySelectorAll(".listening-guide li div");
        for (const [index, text] of [
          ["Compare each pair.", "Hear dark versus textured Bass, rounded versus bright Pluck, compact versus opening Reed, and close versus drifting Pad. Each has one fixed pitch and one trigger."],
          ["Try register and touch.", "Hear Bass at C2 and the other voices at C4. Compare soft and hard excitation; Pad keeps a longer press so its slow onset has room to develop."],
          ["Take memory away.", "Compare Roaming Bass, Remembered Reed and Memory Drift with their memory-off counterparts. Mark Keep, Revise or Drop and export your notes."],
        ].entries()) { guide[index].querySelector("h3").textContent = text[0]; guide[index].querySelector("p").textContent = text[1]; }
        document.querySelector("#bank-download > .bench-note:last-child").textContent = manifest.playableBank?.candidateBank ? "The playable bank now has six voices: Note, Bass, Pluck, Reed, Pad and Bowed. Its twelve presets fill sixteen pads with 48 rendered velocity samples. Download both MPC formats or the SnipSnap import with twelve user presets for editing on a fresh pad." : "Original Note bank: the existing 16 pads and velocity layers. These eight preset proposals are separate WAV downloads below; the new four-voice bank follows your listening choices.";
        document.querySelector(".footer p").textContent = "Eight dry proposals. One evolving note at a time.";
        if (manifest.playableBank?.candidateBank) {
          $("listening-title").textContent = "Compare the preset pairs.";
          document.querySelector(".listening-guide > div > p:last-child").textContent = "These original four-family comparisons remain available. The playable bank adds the original Note and the new Bowed voice, with two presets for each of the six voices.";
        }
      }
      const previewGain = Number(manifest.browserPlayback?.commonGainDb);
      if (Number.isFinite(previewGain)) $("browser-preview-note").textContent = state.manifest.browserPlayback?.originalWavDownloads === false ? `These comparison previews use a common ${previewGain >= 0 ? "+" : ""}${previewGain} dB listening gain. The pad view plays the bank's soft, medium and hard hits.` : `MP3 previews use a common ${previewGain >= 0 ? "+" : ""}${previewGain} dB listening gain. WAV downloads retain their original levels.`;
      $("total-count").textContent = String(state.clips.length);
      const channels = Number(manifest.channels) === 1 ? "mono" : Number(manifest.channels) === 2 ? "stereo" : "audio";
      const rate = manifest.sampleRate ? `${Number(manifest.sampleRate) / 1000} kHz` : "";
      $("render-format").textContent = ["Offline renders", rate, channels].filter(Boolean).join(" · ");
      if (manifest.description) $("study-description").textContent = manifest.description;
      if (typeof manifest.summary === "string" && manifest.summary) {
        $("manifest-summary").textContent = manifest.summary;
        $("manifest-summary").hidden = false;
      }
      document.title = `${manifest.title || "Osteria"} — SnipSnap listening study`;
      $("library-status").hidden = true;
      $("desk").hidden = false;
      $("library-heading").hidden = false;
      state.group = state.groups.some(group => group.id === "first-listen") ? "first-listen" : state.groups[0].id;
      renderTabs();
      switchGroup(state.group);
    } catch (error) {
      $("library-status").hidden = true;
      $("library-error").hidden = false;
      $("library-error-detail").textContent = `${error.message} Check the connection and try again.`;
      $("group-title").textContent = "Library unavailable";
      $("group-description").textContent = "The audio files are listed in the audition manifest.";
      $("group-count").textContent = "Unavailable";
    } finally { $("retry-library").disabled = false; }
  }
  function renderTabs() {
    $("group-tabs").innerHTML = state.groups.map((group, index) => `<button class="tab" type="button" id="group-tab-${index}" role="tab" aria-selected="${state.group === group.id}" aria-controls="group-panel" tabindex="${state.group === group.id ? "0" : "-1"}" data-group="${escape(group.id)}">${escape(group.title)}<span>${group.clips.length}</span></button>`).join("");
  }
  function switchGroup(id, focus = false) {
    const group = state.groups.find(item => item.id === id);
    if (!group) return;
    state.group = id;
    for (const [index, tab] of [...$("group-tabs").children].entries()) {
      const active = tab.dataset.group === id;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active) {
        $("group-panel").setAttribute("aria-labelledby", `group-tab-${index}`);
        if (focus) { tab.focus(); tab.scrollIntoView({ block: "nearest", inline: "nearest" }); }
      }
    }
    $("group-title").textContent = group.title;
    $("group-description").textContent = group.description;
    $("group-count").textContent = `${group.clips.length} ${group.clips.length === 1 ? "render" : "renders"}`;
    renderRows();
    selectClip(group.clips[0].id);
  }
  function clipMeta(clip) {
    const revision = Number(clip.presetRevision), currentRevision = Number(state.manifest?.auditionRevision);
    const revisionLabel = revision > 1 ? currentRevision && revision < currentRevision ? "Retained" : "Revised" : "";
    return [revisionLabel, clip.midi != null ? `${noteName(clip.midi)} · MIDI ${clip.midi}` : "", clip.velocity != null ? `Velocity ${Number(clip.velocity).toFixed(2)}` : "", Number.isFinite(clip.gateSeconds) ? `Press ${clip.gateSeconds.toFixed(2)} s` : "", Number.isFinite(clip.attackSeconds) ? `Attack ${Math.round(clip.attackSeconds * 1000)} ms` : "", Number.isFinite(clip.releaseSeconds) ? `Release ${Math.round(clip.releaseSeconds * 1000)} ms` : "", clip.durationSeconds ? `${clip.durationSeconds.toFixed(2)} s audio` : "", Number.isFinite(clip.balanceTrimDb) && clip.balanceTrimDb !== 0 ? `Matched ${clip.balanceTrimDb > 0 ? "+" : ""}${clip.balanceTrimDb.toFixed(1)} dB balance` : "", clip.role ? humanize(clip.role) : ""].filter(Boolean);
  }
  function renderRows() {
    $("clip-list").innerHTML = currentGroup().clips.map((clip, index) => `<article class="clip-row" data-clip="${escape(clip.id)}"><span class="clip-index" aria-hidden="true">${String(index + 1).padStart(2, "0")}</span><div class="clip-copy"><button class="clip-title" type="button" data-select="${escape(clip.id)}">${escape(clip.title)}</button><p class="clip-description">${escape(clip.description)}</p><div class="clip-meta">${clipMeta(clip).map(item => `<span>${escape(item)}</span>`).join("")}<span class="verdict-tag" data-review="${escape(clip.id)}"></span></div></div><button class="row-play" type="button" data-play="${escape(clip.id)}" aria-label="Play ${escape(clip.title)}"><span aria-hidden="true">▶</span><span>Play</span></button></article>`).join("");
    updateRows();
  }
  function updateRows() {
    for (const row of $("clip-list").children) {
      const selected = row.dataset.clip === state.selected?.id;
      const playing = selected && playbackActive();
      row.classList.toggle("is-selected", selected);
      row.querySelector(".clip-title").setAttribute("aria-current", selected ? "true" : "false");
      const button = row.querySelector(".row-play");
      const previewUrl = browserPreviewUrl(clipById(row.dataset.clip));
      const preparing = Boolean(previewUrl && !state.previewCache.has(previewUrl));
      button.disabled = preparing;
      button.classList.toggle("is-playing", playing);
      button.firstElementChild.textContent = preparing ? "…" : playing ? "■" : "▶";
      button.lastElementChild.textContent = preparing ? state.previewFailures.has(previewUrl) ? "Unavailable" : "Preparing" : playing ? "Stop" : "Play";
      button.setAttribute("aria-label", `${preparing ? "Preparing" : playing ? "Stop" : "Play"} ${clipById(row.dataset.clip).title}`);
      const clip = clipById(row.dataset.clip), review = state.reviews[clip.id];
      row.querySelector(".verdict-tag").textContent = review?.verdict ? `${reviewIsCurrent(clip, review) ? "" : "Earlier version: "}${humanize(review.verdict)}` : "";
    }
    const reviews = currentGroup()?.clips.filter(clip => reviewIsCurrent(clip, state.reviews[clip.id]) && state.reviews[clip.id]?.verdict).length || 0;
    $("review-count").textContent = reviews ? `${reviews} listening ${reviews === 1 ? "decision" : "decisions"} in this group · device-local` : "Your listening decisions stay on this device.";
  }
  function renderSelected() {
    const clip = state.selected;
    $("player-group").textContent = state.groups.find(group => group.id === clip.group)?.title || humanize(clip.group);
    $("player-title").textContent = clip.title;
    $("player-description").textContent = clip.description;
    const details = clipMeta(clip);
    if (Number.isFinite(Number(clip.peak))) details.push(`Raw peak ${Number(clip.peak).toFixed(3)}`);
    if (Number.isFinite(Number(clip.rms))) details.push(`Raw RMS ${Number(clip.rms).toFixed(4)}`);
    $("render-details").innerHTML = details.map(detail => `<span>${escape(detail)}</span>`).join("");
    $("downloads").innerHTML = [["raw", "Raw WAV"], ["matched", "Matched WAV"], ["recipe", "Recipe"], ["trace", "Trace CSV"]].filter(([key]) => resourceUrl(clip[key])).map(([key, title]) => `<a href="${escape(resourceUrl(clip[key]))}" download>${title}<span aria-hidden="true">↓</span></a>`).join("");
    const entries = Object.entries(clip.macros || {}).filter(([, value]) => typeof value === "number" && Number.isFinite(value)).sort(([a], [b]) => {
      const ai = macroOrder.indexOf(a.toLowerCase()), bi = macroOrder.indexOf(b.toLowerCase());
      return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi);
    });
    $("macros").innerHTML = entries.map(([name, value]) => `<div class="macro"><div class="macro-label"><span>${escape(name)}</span><span class="macro-value">${value.toFixed(2)}</span></div><div class="macro-track" aria-hidden="true"><span style="width:${Math.max(0, Math.min(1, value)) * 100}%"></span></div></div>`).join("") || `<p class="trace-note">See the recipe for this render’s controls.</p>`;
    const review = state.reviews[clip.id] || {};
    $("notes").value = typeof review.notes === "string" ? review.notes : "";
    const currentReview = reviewIsCurrent(clip, review);
    for (const button of document.querySelectorAll("[data-verdict]")) button.setAttribute("aria-pressed", String(currentReview && button.dataset.verdict === review.verdict));
    $("save-status").textContent = (review.notes || review.verdict) && !currentReview ? "These notes belong to an earlier version. Your next decision will review the revised sound; the earlier review stays in your export." : state.storageAvailable ? "Device-local notes. Export a copy to share or keep." : "Device storage is unavailable. Notes remain in this tab; export a copy.";
    const hasTrace = Boolean(clip.shoreTrace?.length || resourceUrl(clip.trace));
    $("motion-details").hidden = !hasTrace;
    $("trace").replaceChildren();
    $("trace-status").textContent = "";
    state.traceLoaded = false;
    if (hasTrace && $("motion-details").open) loadTrace(clip);
    updateRows();
  }
  function setAudioStatus(message, isError = false) {
    $("audio-status").textContent = message;
    $("audio-status").classList.toggle("is-error", isError);
  }
  async function selectClip(id, force = false) {
    const clip = clipById(id);
    if (!clip) return null;
    if (!force && state.selected?.id === id) return state.loadPromise || state.buffer;
    stopPlayback(true);
    state.controller?.abort();
    state.traceController?.abort();
    const token = ++state.loadToken;
    state.selected = clip;
    state.buffer = null;
    state.loading = true;
    state.offset = 0;
    state.nativeUrl = browserPreviewUrl(clip);
    state.mediaIntent = "ready";
    $("browser-audio-details").hidden = !usesNative();
    $("browser-preview-note").hidden = !usesNative();
    const preview = usesNative() ? cachedPreview(state.nativeUrl) : null;
    setNativeSource(preview?.objectUrl || "");
    renderSelected();
    updateLevel();
    updateTransport();
    $("wave-status").textContent = "Loading the rendered waveform…";
    $("wave-status").hidden = false;
    drawWaveform();
    setAudioStatus(usesNative() ? state.nativeSrc ? "Ready. Press Play to listen." : "Preparing browser audio… Play will be ready shortly." : `Loading ${state.level === "matched" ? "matched" : "raw"} render…`);
    prepareGroupPreviews();
    state.controller = new AbortController();
    const controller = state.controller;
    const url = resourceUrl(clip[state.level]);
    if (!url && usesNative()) {
      state.loading = false;
      $("wave-status").textContent = "Comparison preview. Full bank WAVs and recipes are in the performance-bank download.";
      updateTransport();
      return null;
    }
    const job = (async () => {
      try {
        let buffer = state.buffers.get(url);
        if (buffer) {
          state.buffers.delete(url); state.buffers.set(url, buffer);
        } else {
          const response = await fetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`Audio request returned ${response.status}.`);
          const bytes = await unpackAudioTransport(await response.arrayBuffer(), controller.signal);
          if (token !== state.loadToken || controller.signal.aborted) return null;
          buffer = await decodeWav(bytes);
          if (token !== state.loadToken || controller.signal.aborted) return null;
          state.buffers.set(url, buffer);
          while (state.buffers.size > 6) state.buffers.delete(state.buffers.keys().next().value);
        }
        if (token !== state.loadToken) return null;
        state.buffer = buffer;
        state.loading = false;
        $("wave-status").hidden = true;
        $("seek").max = String(buffer.duration);
        $("duration").textContent = secondsLabel(buffer.duration);
        drawWaveform(); updateTransport(); updateProgress();
        if (!usesNative()) setAudioStatus("Ready. Press Play to listen.");
        return buffer;
      } catch (error) {
        if (error.name === "AbortError" || token !== state.loadToken) return null;
        state.loading = false;
        $("wave-status").textContent = usesNative() ? "Waveform unavailable. Press Play to listen." : "Waveform unavailable. WAV downloads are still below.";
        if (!usesNative()) setAudioStatus(`${error.message} Press Play to retry, or download the WAV.`, true);
        updateTransport();
        return null;
      }
    })();
    state.loadPromise = job.finally(() => { if (token === state.loadToken) state.loadPromise = null; });
    return state.loadPromise;
  }
  async function decodeWav(bytes) {
    const view = new DataView(bytes);
    const tag = at => String.fromCharCode(view.getUint8(at), view.getUint8(at + 1), view.getUint8(at + 2), view.getUint8(at + 3));
    if (bytes.byteLength < 44 || tag(0) !== "RIFF" || tag(8) !== "WAVE") throw new Error("The render is not a supported RIFF WAV.");
    let format, channels, sampleRate, blockAlign, bits, dataOffset = 0, dataLength = 0;
    for (let at = 12; at + 8 <= bytes.byteLength;) {
      const name = tag(at), length = view.getUint32(at + 4, true), start = at + 8;
      if (start + length > bytes.byteLength) throw new Error("The WAV file ended before its audio data.");
      if (name === "fmt " && length >= 16) {
        format = view.getUint16(start, true); channels = view.getUint16(start + 2, true);
        sampleRate = view.getUint32(start + 4, true); blockAlign = view.getUint16(start + 12, true); bits = view.getUint16(start + 14, true);
        if (format === 65534 && length >= 40) format = view.getUint16(start + 24, true);
      } else if (name === "data") { dataOffset = start; dataLength = length; }
      at = start + length + (length % 2);
    }
    if (!dataOffset || !channels || !blockAlign || !sampleRate) throw new Error("The WAV header is incomplete.");
    const pcm = format === 1 && [8, 16, 24, 32].includes(bits), floating = format === 3 && bits === 32;
    if (!pcm && !floating) return context().decodeAudioData(bytes);
    if (channels > 8 || blockAlign < channels * bits / 8) throw new Error("The WAV channel layout is unsupported.");
    const frames = Math.floor(dataLength / blockAlign), buffer = context().createBuffer(channels, frames, sampleRate);
    for (let channel = 0; channel < channels; channel++) {
      const samples = buffer.getChannelData(channel);
      for (let frame = 0; frame < frames; frame++) {
        const at = dataOffset + frame * blockAlign + channel * bits / 8;
        let value;
        if (floating) value = view.getFloat32(at, true);
        else if (bits === 8) value = (view.getUint8(at) - 128) / 128;
        else if (bits === 16) value = view.getInt16(at, true) / 32768;
        else if (bits === 32) value = view.getInt32(at, true) / 2147483648;
        else { let integer = view.getUint8(at) | view.getUint8(at + 1) << 8 | view.getUint8(at + 2) << 16; if (integer & 0x800000) integer -= 0x1000000; value = integer / 8388608; }
        samples[frame] = Number.isFinite(value) ? value : 0;
      }
    }
    return buffer;
  }
  function currentDuration() {
    if (usesNative()) return Number.isFinite(media.duration) && media.duration > 0 ? media.duration : state.selected?.durationSeconds || 0;
    return state.buffer?.duration || state.selected?.durationSeconds || 0;
  }
  function currentPosition() {
    if (usesNative()) return Math.min(currentDuration(), Math.max(0, media.currentTime || 0));
    if (!state.buffer) return 0;
    return Math.min(state.buffer.duration, Math.max(0, state.offset + (state.playing ? context().currentTime - state.startedAt : 0)));
  }
  function detachSource() {
    if (state.source) {
      state.source.onended = null;
      try { state.source.stop(); } catch (_) { /* The source may have just ended. */ }
      state.source.disconnect(); state.source = null;
    }
    state.playing = false;
    cancelAnimationFrame(state.animation);
  }
  function stopPlayback(reset = true) {
    ++state.playToken;
    const position = reset ? 0 : currentPosition();
    state.mediaIntent = reset ? "stopped" : "paused";
    state.mediaPending = false;
    media.pause();
    if (reset) { try { media.currentTime = 0; } catch (_) { /* Metadata may not have arrived yet. */ } }
    detachSource(); state.offset = position;
    updateTransport(); updateProgress();
  }
  function startSource() {
    if (!state.buffer) return;
    if (context().state !== "running") throw new Error("Audio is paused by the browser. Press Play again to continue.");
    if (state.offset >= state.buffer.duration - .01) state.offset = 0;
    const source = context().createBufferSource();
    source.buffer = state.buffer; source.connect(context().destination);
    state.source = source; state.startedAt = context().currentTime; state.playing = true;
    source.onended = () => {
      if (state.source !== source) return;
      source.disconnect(); state.source = null; state.playing = false; state.offset = 0;
      cancelAnimationFrame(state.animation); updateTransport(); updateProgress();
      setAudioStatus("Finished. Press Play to hear it again.");
    };
    source.start(0, state.offset);
    updateTransport(); tick();
    setAudioStatus(`Playing the ${state.level === "matched" ? "matched" : "raw"} render.`);
    setTimeout(() => {
      if (state.source === source && state.playing && context().currentTime - state.startedAt < .01) {
        stopPlayback(false);
        setAudioStatus("Audio did not start. Press Play again, or download a WAV.", true);
      }
    }, 500);
  }
  function withTimeout(promise, milliseconds, message) {
    let timer;
    const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), milliseconds); });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
  }
  async function playClip(id = state.selected?.id) {
    if (!id) return;
    const clip = clipById(id);
    if (!clip) return;
    const nativeUrl = browserPreviewUrl(clip);
    if (nativeUrl) {
      // Preloaded Blob URLs support seeking on hosts without byte ranges.
      // No network await occurs between this user gesture and media.play().
      if (state.selected?.id !== id) selectClip(id);
      if (!usePreparedPreview()) {
        state.previewFailures.delete(nativeUrl); prepareGroupPreviews();
        setAudioStatus("Preparing browser audio… Press Play when it is ready."); updateTransport();
        return;
      }
      if (media.error) media.load();
      const intent = ++state.playToken, selection = state.loadToken;
      state.mediaIntent = "playing"; state.mediaPending = true;
      media.volume = 1; media.muted = false;
      if (media.ended || media.currentTime >= currentDuration() - .01) media.currentTime = 0;
      setAudioStatus("Starting audio…"); updateTransport();
      try {
        const playback = media.play();
        await withTimeout(playback, 8000, "Audio did not start. Check the connection and press Play again.");
        if (intent !== state.playToken || selection !== state.loadToken || state.selected?.id !== id || media.paused) return;
        state.mediaPending = false; state.playing = true;
        updateTransport(); cancelAnimationFrame(state.animation); tick();
        setAudioStatus(`Playing the ${state.level === "matched" ? "matched" : "raw"} render.`);
      } catch (error) {
        if (intent !== state.playToken || selection !== state.loadToken || state.selected?.id !== id) return;
        stopPlayback(false);
        const message = error.name === "NotAllowedError" ? "Playback was blocked. Press Play again, or use Browser audio controls below." : `${error.message} Use Browser audio controls below, or download a WAV.`;
        setAudioStatus(message, true);
      }
      return;
    }
    let intent, selection;
    try {
      // Resume inside the click/key gesture, before any network await.
      const resume = context().resume();
      let load;
      if (state.selected?.id !== id) load = selectClip(id);
      else if (!state.buffer && !state.loading) load = selectClip(id, true);
      else load = state.loadPromise;
      intent = ++state.playToken; selection = state.loadToken;
      await withTimeout(resume, 3000, "Audio is paused by the browser. Press Play again to continue.");
      if (load) await load;
      if (intent !== state.playToken || selection !== state.loadToken || state.selected?.id !== id || !state.buffer) return;
      detachSource(); startSource();
    } catch (error) {
      if (intent !== undefined && (intent !== state.playToken || selection !== state.loadToken)) return;
      setAudioStatus(`${error.message} Download a WAV if playback is unavailable.`, true);
    }
  }
  function updateLevel() {
    const matched = state.level === "matched";
    $("player-level").textContent = matched ? "Matched level" : "Raw level";
    const balancedCandidates = ["voice-candidates", "preset-candidates"].includes(state.manifest?.auditionKind);
    $("level-note").textContent = matched ? balancedCandidates ? "Matched for listening: Reed −1.5 dB, Pad +1.5 dB." : "Same renders, level adjusted for listening." : usesNative() ? "Relative rendered levels; original gain in WAV downloads." : "Original rendered level, with no listening adjustment.";
  }
  function updateTransport() {
    const active = playbackActive();
    const preparing = usesNative() && !state.nativeSrc && !state.previewFailures.has(state.nativeUrl);
    const retry = usesNative() && !state.nativeSrc && state.previewFailures.has(state.nativeUrl);
    $("play").disabled = !state.selected || (usesNative() ? preparing : state.loading);
    $("stop").disabled = !usesNative() && !state.buffer && !state.loading;
    $("seek").disabled = usesNative() ? media.readyState < 1 : !state.buffer;
    $("play-label").textContent = preparing ? "Preparing" : retry ? "Retry" : active ? "Pause" : "Play";
    $("play-symbol").textContent = preparing ? "…" : active ? "Ⅱ" : "▶";
    $("play").setAttribute("aria-label", `${preparing ? "Preparing" : retry ? "Retry" : active ? "Pause" : "Play"} ${state.selected?.title || "selected render"}`);
    $("duration").textContent = secondsLabel(currentDuration()); $("seek").max = String(currentDuration() || 1);
    updateRows();
  }
  function updateProgress() {
    const position = currentPosition(), duration = currentDuration();
    $("elapsed").textContent = secondsLabel(position);
    $("seek").value = String(position);
    $("seek").setAttribute("aria-valuetext", `${position.toFixed(1)} of ${duration.toFixed(1)} seconds`);
    $("wave-playhead").hidden = !state.buffer;
    $("wave-playhead").style.left = `${duration ? position / duration * 100 : 0}%`;
  }
  function tick() { updateProgress(); if (state.playing) state.animation = requestAnimationFrame(tick); }
  function drawWaveform() {
    const canvas = $("waveform"), rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(rect.width * pixelRatio); canvas.height = Math.round(rect.height * pixelRatio);
    const drawing = canvas.getContext("2d");
    if (!drawing) return;
    drawing.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    const width = rect.width, height = rect.height, center = height / 2;
    drawing.clearRect(0, 0, width, height); drawing.strokeStyle = "#d5c5af"; drawing.lineWidth = .6;
    drawing.beginPath(); drawing.moveTo(0, center); drawing.lineTo(width, center); drawing.stroke();
    for (let x = 0; x < width; x += width / 8) { drawing.beginPath(); drawing.moveTo(x, 8); drawing.lineTo(x, height - 8); drawing.stroke(); }
    if (!state.buffer) return;
    const samples = state.buffer.getChannelData(0), columns = Math.floor(width / 2);
    let peak = .001;
    for (let i = 0; i < samples.length; i++) peak = Math.max(peak, Math.abs(samples[i]));
    const scale = (height * .38) / peak;
    drawing.strokeStyle = "#722f37"; drawing.lineWidth = 1.15;
    for (let column = 0; column < columns; column++) {
      const from = Math.floor(column * samples.length / columns), to = Math.min(samples.length, Math.max(from + 1, Math.floor((column + 1) * samples.length / columns)));
      let low = 0, high = 0;
      for (let sample = from; sample < to; sample++) { low = Math.min(low, samples[sample]); high = Math.max(high, samples[sample]); }
      const x = column * width / columns;
      drawing.beginPath(); drawing.moveTo(x, center - high * scale); drawing.lineTo(x, center - low * scale); drawing.stroke();
    }
  }
  function saveReview(verdict) {
    const clip = state.selected;
    if (!clip) return;
    const previous = state.reviews[clip.id] || {};
    const currentReview = reviewIsCurrent(clip, previous);
    const previousVerdict = currentReview ? previous.verdict || "" : "";
    const { earlierReviews = [], ...earlier } = previous;
    const history = !currentReview && (previous.verdict || previous.notes) ? [...earlierReviews, earlier] : earlierReviews;
    state.reviews[clip.id] = { verdict: verdict === undefined ? previousVerdict : previousVerdict === verdict ? "" : verdict, notes: $("notes").value, updatedAt: new Date().toISOString(), audioFingerprint: audioFingerprint(clip), presetRevision: clip.presetRevision || null, earlierReviews: history };
    try { localStorage.setItem(storageKey, JSON.stringify(state.reviews)); state.storageAvailable = true; }
    catch (_) { state.storageAvailable = false; }
    for (const button of document.querySelectorAll("[data-verdict]")) button.setAttribute("aria-pressed", String(button.dataset.verdict === state.reviews[clip.id].verdict));
    $("save-status").textContent = state.storageAvailable ? "Saved on this device. Export a copy to share or keep." : "Device storage is unavailable. Notes remain in this tab; export a copy.";
    updateRows();
  }
  function exportReviews() {
    const review = { engine: "OSTERIA", model: state.manifest?.model || 1, title: state.manifest?.title || "Osteria listening study", exportedAt: new Date().toISOString(), reviews: state.clips.filter(clip => state.reviews[clip.id]?.verdict || state.reviews[clip.id]?.notes || state.reviews[clip.id]?.earlierReviews?.length).map(clip => ({ clipId: clip.id, title: clip.title, currentAudioFingerprint: audioFingerprint(clip), currentPresetRevision: clip.presetRevision || null, appliesToCurrentAudio: reviewIsCurrent(clip, state.reviews[clip.id]), ...state.reviews[clip.id] })) };
    const url = URL.createObjectURL(new Blob([JSON.stringify(review, null, 2) + "\n"], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = `osteria-listening-notes-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    $("save-status").textContent = `${review.reviews.length} ${review.reviews.length === 1 ? "review" : "reviews"} exported. Notes stay on this device.`;
  }
  async function loadTrace(clip) {
    if (state.traceLoaded || !clip || state.selected?.id !== clip.id) return;
    state.traceLoaded = true;
    const token = state.loadToken;
    $("trace-status").textContent = "Loading the motion trace…";
    try {
      let points = clip.shoreTrace;
      if (!Array.isArray(points) || !points.length) {
        state.traceController?.abort(); state.traceController = new AbortController();
        const response = await fetch(resourceUrl(clip.trace), { signal: state.traceController.signal });
        if (!response.ok) throw new Error("The trace could not load.");
        const lines = (await response.text()).trim().split(/\r?\n/), columns = lines.shift().split(",");
        const at = name => columns.indexOf(name), roles = ["bass", "harmony", "melody", "rhythm"];
        points = lines.map(line => { const values = line.split(",").map(Number); return { timeSeconds: values[at("timeSeconds")], positions: roles.map(role => values[at(`${role}Position`)]), remembered: roles.map(role => values[at(`${role}Remembered`)]) }; });
      }
      if (token !== state.loadToken || state.selected?.id !== clip.id) { state.traceLoaded = false; return; }
      points = points.filter(point => Number.isFinite(point.timeSeconds) && Array.isArray(point.positions) && point.positions.length === 4);
      if (points.length < 2) throw new Error("No usable motion points were found.");
      const memory = clip.macros?.memory ?? clip.macros?.MEMORY;
      const memoryActive = Number(memory) > 0 && clip.probeOptions?.memoryEnabled !== false;
      drawTrace(points, memoryActive);
      $("motion-details").querySelector(".trace-note").textContent = memoryActive
        ? "Solid lines show each layer’s current position; dashed lines show its remembered position. The vertical axis is position within the timbre palette."
        : "Solid lines show each layer’s current position within the timbre palette. Memory is inactive in this render, so remembered positions are omitted.";
      $("trace-status").textContent = "Visual guide only. Use the sound to judge the relationship.";
    } catch (error) {
      if (error.name === "AbortError" || token !== state.loadToken) return;
      $("trace-status").textContent = "Motion preview unavailable. The CSV download remains below the player.";
      state.traceLoaded = false;
    }
  }
  function drawTrace(points, memoryActive) {
    const left = 32, top = 12, width = 510, height = 136, end = Math.max(.01, points[points.length - 1].timeSeconds);
    let markup = `<rect x="${left}" y="${top}" width="${width}" height="${height}" fill="#f3eadb"/>`;
    for (let value = 0; value <= 4; value++) {
      const y = top + (1 - value / 4) * height;
      markup += `<path d="M${left} ${y}H${left + width}" stroke="#d7cabb" stroke-width=".6"/><text x="24" y="${y + 3}" text-anchor="end">${value}</text>`;
    }
    for (let role = 0; role < 4; role++) {
      for (const kind of ["remembered", "positions"]) {
        if (kind === "remembered" && !memoryActive) continue;
        const valid = points.filter(point => Number.isFinite(point[kind]?.[role]));
        if (valid.length < 2) continue;
        const path = valid.map((point, index) => `${index ? "L" : "M"}${(left + point.timeSeconds / end * width).toFixed(2)} ${(top + (1 - Math.max(0, Math.min(4, point[kind][role])) / 4) * height).toFixed(2)}`).join(" ");
        markup += `<path d="${path}" fill="none" stroke="${colors[role]}" stroke-width="${kind === "remembered" ? 1 : 1.6}" ${kind === "remembered" ? 'stroke-dasharray="4 3" opacity=".55"' : ""}/>`;
      }
    }
    markup += `<text x="${left}" y="169">0 s</text><text x="${left + width}" y="169" text-anchor="end">${end.toFixed(1)} s</text>`;
    $("trace").innerHTML = markup;
  }
  $("group-tabs").addEventListener("click", event => { const tab = event.target.closest("[data-group]"); if (tab) switchGroup(tab.dataset.group); });
  $("group-tabs").addEventListener("keydown", event => {
    const tabs = [...$("group-tabs").children], current = tabs.indexOf(event.target);
    if (current < 0 || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const index = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (current + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
    switchGroup(tabs[index].dataset.group, true);
  });
  $("clip-list").addEventListener("click", event => {
    const select = event.target.closest("[data-select]"), play = event.target.closest("[data-play]");
    if (select) selectClip(select.dataset.select);
    if (play) { if (playbackActive() && state.selected?.id === play.dataset.play) { stopPlayback(); setAudioStatus("Stopped. Press Play to listen from the start."); } else playClip(play.dataset.play); }
  });
  $("downloads").addEventListener("click", event => {
    const link = event.target.closest("a[download]");
    if (!link || !/\.wav\.gz$/i.test(new URL(link.href).pathname)) return;
    event.preventDefault();
    downloadWav(link);
  });
  for (const input of document.querySelectorAll('input[name="level"]')) input.addEventListener("change", () => {
    if (!input.checked || input.value === state.level) return;
    stopPlayback(); state.level = input.value; updateLevel();
    if (state.selected) selectClip(state.selected.id, true);
  });
  $("play").addEventListener("click", () => { if (playbackActive()) { stopPlayback(false); setAudioStatus("Paused. Press Play to continue."); } else playClip(); });
  $("stop").addEventListener("click", () => { stopPlayback(); setAudioStatus("Stopped. Press Play to listen from the start."); });
  $("seek").addEventListener("input", () => {
    if (usesNative()) {
      if (media.readyState < 1) return;
      try { media.currentTime = Math.max(0, Math.min(currentDuration(), Number($("seek").value))); }
      catch (_) { setAudioStatus("That position is not available yet. Try again when the audio loads.", true); }
      updateProgress(); return;
    }
    if (!state.buffer) return;
    const playing = state.playing; ++state.playToken; detachSource();
    state.offset = Math.max(0, Math.min(state.buffer.duration, Number($("seek").value)));
    if (playing) {
      try { startSource(); } catch (error) { updateTransport(); updateProgress(); setAudioStatus(error.message, true); }
    } else { updateTransport(); updateProgress(); }
  });
  const currentMedia = () => usesNative() && Boolean(state.nativeSrc) && media.getAttribute("src") === state.nativeSrc;
  for (const event of ["loadedmetadata", "durationchange", "seeked"]) media.addEventListener(event, () => {
    if (currentMedia()) { updateTransport(); updateProgress(); }
  });
  media.addEventListener("play", () => {
    if (!currentMedia() || media.paused) return;
    detachSource(); state.mediaIntent = "playing";
    updateTransport();
  });
  media.addEventListener("playing", () => {
    if (!currentMedia() || media.paused) return;
    state.playing = true; state.mediaPending = false; state.mediaIntent = "playing";
    updateTransport(); cancelAnimationFrame(state.animation); tick();
    setAudioStatus(`Playing the ${state.level === "matched" ? "matched" : "raw"} render.`);
  });
  media.addEventListener("pause", () => {
    if (!currentMedia() || !media.paused) return;
    state.playing = false; state.mediaPending = false;
    cancelAnimationFrame(state.animation); updateTransport(); updateProgress();
    if (state.mediaIntent === "playing" && !media.ended) {
      ++state.playToken; state.mediaIntent = "paused";
      setAudioStatus("Paused. Press Play to continue.");
    }
  });
  media.addEventListener("ended", () => {
    if (!currentMedia() || !media.ended) return;
    ++state.playToken; state.playing = false; state.mediaPending = false; state.mediaIntent = "finished";
    cancelAnimationFrame(state.animation);
    try { media.currentTime = 0; } catch (_) { /* The browser may have released the media. */ }
    updateTransport(); updateProgress();
    setAudioStatus("Finished. Press Play to hear it again.");
  });
  media.addEventListener("timeupdate", () => { if (currentMedia()) updateProgress(); });
  for (const event of ["waiting", "stalled"]) media.addEventListener(event, () => {
    if (currentMedia() && !media.paused) setAudioStatus("Buffering audio… Check the connection if it does not continue.");
  });
  media.addEventListener("error", () => {
    if (!currentMedia() || !media.error) return;
    const reason = media.error.code === 2 ? "The audio could not load. Check the connection and press Play again." : "This browser could not play the audio.";
    stopPlayback(false);
    setAudioStatus(`${reason} Use Browser audio controls below, or download a WAV.`, true);
  });
  for (const button of document.querySelectorAll("[data-verdict]")) button.addEventListener("click", () => saveReview(button.dataset.verdict));
  $("notes").addEventListener("input", () => saveReview());
  $("export-review").addEventListener("click", exportReviews);
  $("bank-prepare").addEventListener("click", prepareBankDownload);
  $("retry-library").addEventListener("click", loadLibrary);
  $("motion-details").addEventListener("toggle", () => { if ($("motion-details").open) loadTrace(state.selected); });
  window.addEventListener("resize", drawWaveform);
  window.addEventListener("pagehide", event => {
    stopPlayback(); state.controller?.abort(); state.traceController?.abort(); state.downloadController?.abort();
    state.previewStopped = true; state.previewQueue = [];
    for (const job of state.previewJobs.values()) job.controller.abort();
    if (!event.persisted) {
      resetBankDownload();
      setNativeSource("");
      for (const entry of state.previewCache.values()) URL.revokeObjectURL(entry.objectUrl);
      state.previewCache.clear();
    }
  });
  window.addEventListener("pageshow", event => { if (event.persisted) { state.previewStopped = false; prepareGroupPreviews(); } });
  readReviews(); loadLibrary();
})();
