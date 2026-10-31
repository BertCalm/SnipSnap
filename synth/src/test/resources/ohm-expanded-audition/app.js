"use strict";

(() => {
  const $ = id => document.getElementById(id);
  const audio = $("audio");
  const storageKey = "snipsnap.ohm.expanded.review.v1";
  const labels = { bow: "Bow", capture: "Captured hit", elder: "FM elder", grains: "Grains", freeze: "Freeze", sustain: "Sustain", phrases: "Phrases", held: "Held ×8", all: "All" };
  const allowedDecisions = new Set(["", "Keep", "Revise", "Drop"]);
  const cards = new Map();
  let manifest;
  let clips = [];
  let current;
  let level = "matched";
  let filter = "bow";
  let generation = 0;
  let pendingSeek;
  let storageAvailable = true;
  let reviews = readReviews();

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function asset(path) {
    if (typeof path !== "string" || !path || /^(?:[a-z][a-z\d+.-]*:|\/\/|\/)/i.test(path)) throw new Error("Invalid recording path");
    const base = new URL("./", location.href);
    const url = new URL(path, base);
    if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) throw new Error("Invalid recording path");
    return url.href;
  }

  function duration(seconds) {
    const value = Math.max(0, Number(seconds) || 0);
    return `${Math.floor(value / 60)}:${Math.floor(value % 60).toString().padStart(2, "0")}`;
  }

  function readReviews() {
    try {
      const stored = JSON.parse(localStorage.getItem(storageKey) || "{}");
      if (!stored || typeof stored !== "object" || Array.isArray(stored)) return {};
      const clean = {};
      for (const [id, value] of Object.entries(stored)) {
        if (!value || typeof value !== "object") continue;
        clean[id] = { decision: allowedDecisions.has(value.decision) ? value.decision : "", note: typeof value.note === "string" ? value.note.slice(0, 4000) : "" };
      }
      return clean;
    } catch (_) { storageAvailable = false; return {}; }
  }

  function saveReview() {
    if (!current) return;
    reviews[current.id] = { decision: $("review-decision").value, note: $("review-note").value.slice(0, 4000) };
    try { localStorage.setItem(storageKey, JSON.stringify(reviews)); }
    catch (_) { storageAvailable = false; }
    $("review-status").textContent = storageAvailable ? "Saved in this browser." : "Kept for this visit. Export to save it.";
    updateStorageLabel();
  }

  function updateStorageLabel() {
    $("storage-status").textContent = storageAvailable ? "Nothing is sent anywhere." : "Browser storage is unavailable. Export your review before leaving.";
  }

  function updateCards() {
    for (const [id, card] of cards) {
      const active = current && current.id === id;
      card.root.classList.toggle("is-current", !!active);
      card.button.setAttribute("aria-pressed", String(!!active && !audio.paused));
      card.button.textContent = active && !audio.paused ? "Pause Ⅱ" : card.entry.group === "features" ? "Play comparison ▶" : "Play recording ▶";
    }
  }

  function updatePartStatus() {
    if (!current) return;
    const frame = audio.currentTime * manifest.sampleRate;
    const index = current.parts.findIndex(part => frame >= part.onsetFrames && frame < part.endFrameExclusive);
    [...$("parts").children].forEach((button, i) => button.classList.toggle("is-current", i === index));
    $("part-status").textContent = index >= 0 ? current.parts[index].label : audio.ended ? "Finished" : frame > 0 ? "Short pause" : "Ready to listen";
  }

  function updateLabels() {
    $("level-description").textContent = level === "raw"
      ? "RAW · ONE COMMON PCM HEADROOM GAIN"
      : current && current.group === "features" ? "MATCHED · EACH PART LEVELED SEPARATELY" : "MATCHED · COMPLETE RECORDING LEVELED ONCE";
    if (!current) return;
    $("audio-download").href = asset(current[level]);
    $("audio-download").download = `${current.id}-${level}.wav`;
    $("audio-download").textContent = `${level === "raw" ? "Raw" : "Matched"} WAV ↓`;
    $("recipe-download").href = asset(current.recipe);
    $("recipe-download").download = `${current.id}.json`;
    audio.setAttribute("aria-label", `${current.title}, ${level} recording`);
  }

  async function play(expectedGeneration) {
    try {
      await audio.play();
      if (expectedGeneration === generation) $("audio-status").textContent = "";
    } catch (error) {
      if (expectedGeneration !== generation || error.name === "AbortError") return;
      $("audio-status").textContent = "Playback could not start. Use the audio controls or download the WAV.";
    }
    updateCards();
  }

  function choose(clip, start = false) {
    if (current && clip.id === current.id) {
      if (start && !audio.paused) audio.pause();
      else if (start) { if (audio.ended) audio.currentTime = 0; play(generation); }
      return;
    }
    generation += 1;
    audio.pause();
    current = clip;
    pendingSeek = { time: 0, play: start, generation };
    $("player-title").textContent = clip.title;
    $("player-description").textContent = clip.description;
    $("player-category").textContent = `MODEL 3 / ${clip.context ? clip.context.toUpperCase() : clip.group.toUpperCase()}`;
    $("review-title").textContent = `${clip.title}${clip.context ? ` · ${clip.context}` : ""}`;
    $("review-decision").value = reviews[clip.id]?.decision || "";
    $("review-note").value = reviews[clip.id]?.note || "";
    $("review-status").textContent = "";
    $("parts").replaceChildren();
    for (const part of clip.parts) {
      const button = element("button");
      button.type = "button";
      button.append(element("span", "", part.label), element("span", "", duration(part.onsetFrames / manifest.sampleRate)));
      button.setAttribute("aria-label", `Play ${part.label} from ${duration(part.onsetFrames / manifest.sampleRate)}`);
      button.addEventListener("click", () => {
        const time = part.onsetFrames / manifest.sampleRate;
        if (audio.readyState > 0) { audio.currentTime = time; play(generation); }
        else pendingSeek = { time, play: true, generation };
      });
      $("parts").append(button);
    }
    const svg = $("waveform");
    svg.replaceChildren();
    clip.waveform.forEach((value, i) => {
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      const height = Math.max(1, Math.min(1, value / Math.max(...clip.waveform, .00001)) * 56);
      line.setAttribute("x1", String(i * 5 + 2)); line.setAttribute("x2", String(i * 5 + 2));
      line.setAttribute("y1", String(35 - height / 2)); line.setAttribute("y2", String(35 + height / 2));
      svg.append(line);
    });
    updateLabels();
    $("audio-status").textContent = "Loading recording…";
    audio.src = asset(clip[level]);
    audio.load();
    updateCards(); updatePartStatus();
  }

  function visible(clip) {
    if (filter === "all") return true;
    if (filter === "phrases" || filter === "held") return clip.group === filter;
    return clip.group === "features" && clip.feature === filter;
  }

  function renderCollection() {
    const selected = clips.filter(visible);
    $("collection-count").textContent = `${selected.length} of ${clips.length} recordings`;
    $("clip-groups").replaceChildren(); cards.clear();
    for (const group of manifest.groups) {
      const entries = selected.filter(clip => clip.group === group.id);
      if (!entries.length) continue;
      const section = element("section", "clip-group");
      section.append(element("h3", "", group.title));
      for (const clip of entries) {
        const card = element("article", "clip-card");
        const meta = element("div", "card-meta");
        meta.append(element("span", "", clip.context ? `${clip.context.toUpperCase()} COMPANY` : clip.group === "held" ? "ARRIVAL + LOOP ×8" : "FOUR-NOTE PHRASE"), element("span", "", `${clip.seconds.toFixed(1)} s`));
        card.append(meta, element("h4", "", clip.title), element("p", "", clip.description));
        const bottom = element("div", "card-bottom");
        bottom.append(element("span", "", clip.group === "features" ? "OFF → ON" : clip.group === "held" ? "ONE ARRIVAL / EIGHT PASSES" : "C4 · E♭4 · G4 · C4"));
        const button = element("button", "", "Play comparison ▶"); button.type = "button";
        button.setAttribute("aria-label", `Play ${clip.title}${clip.context ? `, ${clip.context} companions` : ""}`);
        button.addEventListener("click", () => choose(clip, true));
        bottom.append(button); card.append(bottom); section.append(card);
        cards.set(clip.id, { root: card, button, entry: clip });
      }
      $("clip-groups").append(section);
    }
    document.querySelectorAll("[data-filter]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.filter === filter)));
    updateCards();
  }

  function setLevel(next) {
    if (!new Set(["raw", "matched"]).has(next) || next === level) return;
    const time = audio.currentTime || 0;
    const wasPlaying = !audio.paused && !audio.ended;
    level = next;
    document.querySelectorAll("[data-level]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.level === level)));
    updateLabels();
    if (!current) return;
    generation += 1; audio.pause();
    pendingSeek = { time, play: wasPlaying, generation };
    $("audio-status").textContent = "Switching playback level…";
    audio.src = asset(current[level]); audio.load();
  }

  function stop() {
    generation += 1; pendingSeek = undefined; audio.pause();
    if (audio.readyState > 0) audio.currentTime = 0;
    $("audio-status").textContent = "Stopped.";
    updateCards(); updatePartStatus();
  }

  function validate(data) {
    if (!data || data.schemaVersion !== 1 || data.sampleRate !== 44100 || !Array.isArray(data.clips) || !data.clips.length || !Array.isArray(data.groups)) throw new Error("The recording manifest is incomplete.");
    const ids = new Set();
    for (const clip of data.clips) {
      if (typeof clip.id !== "string" || ids.has(clip.id) || typeof clip.title !== "string" || !Number.isFinite(clip.seconds) || !Array.isArray(clip.parts) || !clip.parts.length || !Array.isArray(clip.waveform) || clip.waveform.length !== 96 || !clip.waveform.every(Number.isFinite)) throw new Error("A recording entry is incomplete.");
      ids.add(clip.id); asset(clip.raw); asset(clip.matched); asset(clip.recipe);
      for (const part of clip.parts) if (!Number.isInteger(part.onsetFrames) || !Number.isInteger(part.endFrameExclusive) || part.onsetFrames < 0 || part.endFrameExclusive <= part.onsetFrames) throw new Error("A recording's comparison timing is incomplete.");
    }
    return data;
  }

  async function load() {
    $("library-error").hidden = true; $("library-status").hidden = false;
    $("library-status").textContent = "Opening the listening room…";
    try {
      const response = await fetch("assets/manifest.json", { cache: "no-cache" });
      if (!response.ok) throw new Error(`The manifest returned ${response.status}.`);
      manifest = validate(await response.json()); clips = manifest.clips;
      $("filters").replaceChildren();
      for (const key of ["bow", "capture", "elder", "grains", "freeze", "sustain", "phrases", "held", "all"]) {
        const button = element("button", "", labels[key]); button.type = "button"; button.dataset.filter = key;
        button.addEventListener("click", () => { filter = key; renderCollection(); });
        $("filters").append(button);
      }
      $("bundle-download").href = asset(manifest.bundle);
      // The complete download contains the player, but does not nest another copy of itself.
      fetch(asset(manifest.bundle), { method: "HEAD" }).then(result => {
        $("bundle-download").hidden = !result.ok;
      }).catch(() => { $("bundle-download").hidden = true; });
      $("source-download").href = asset(manifest.sourceCapture.file);
      renderCollection(); choose(clips[0]);
      $("audition-content").hidden = false; $("library-status").hidden = true;
      $("first-play").disabled = false;
      updateStorageLabel();
    } catch (error) {
      $("library-status").hidden = true; $("library-error").hidden = false;
      $("error-message").textContent = `${error.message} Try again, or download the audition ZIP.`;
    }
  }

  audio.addEventListener("loadedmetadata", () => {
    if (!current || audio.currentSrc !== asset(current[level])) return;
    const request = pendingSeek;
    if (request && request.generation === generation) {
      audio.currentTime = Math.min(request.time, Math.max(0, audio.duration - .001));
      pendingSeek = undefined;
      if (request.play) play(generation);
      else $("audio-status").textContent = "Ready. Press Play to compare.";
    }
    updatePartStatus();
  });
  audio.addEventListener("timeupdate", updatePartStatus);
  for (const event of ["play", "pause", "ended"]) audio.addEventListener(event, () => {
    $("player-state").textContent = audio.ended ? "FINISHED" : audio.paused ? "READY" : "PLAYING";
    updateCards(); updatePartStatus();
  });
  audio.addEventListener("error", () => { $("audio-status").textContent = "This recording could not load. Try another example or download its WAV."; updateCards(); });
  $("first-play").addEventListener("click", () => { if (clips.length) { choose(clips[0], true); $("listen").scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" }); } });
  $("stop-all").addEventListener("click", stop);
  document.addEventListener("keydown", event => { if (event.key === "Escape") stop(); });
  document.querySelectorAll("[data-level]").forEach(button => button.addEventListener("click", () => setLevel(button.dataset.level)));
  $("retry-load").addEventListener("click", load);
  $("review-decision").addEventListener("change", saveReview);
  $("review-note").addEventListener("input", saveReview);
  $("export-review").addEventListener("click", () => {
    const review = { engine: "OHM", audition: "expanded", modelVersion: 3, sourceRevision: manifest.sourceRevision, exportedAt: new Date().toISOString(), reviews };
    const url = URL.createObjectURL(new Blob([JSON.stringify(review, null, 2) + "\n"], { type: "application/json" }));
    const link = element("a"); link.href = url; link.download = "ohm-expanded-listening-notes.json"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  load();
})();
