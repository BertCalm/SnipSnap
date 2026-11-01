"use strict";

(() => {
  const $ = id => document.getElementById(id);
  const audio = $("audio");
  const assetRoot = new URL("./", document.baseURI);
  const tabLabels = ["Bow", "Grains", "Memory"];
  let library;
  let clips;
  let comparisonIndex = 0;
  let variantIndex = 0;
  let current;
  let referenceSelected = false;
  let generation = 0;
  let libraryGeneration = 0;
  let hasError = false;

  const url = path => new URL(path, assetRoot).href;
  const isCurrentAudio = () => current && audio.currentSrc === current.url;
  const status = message => { $("audio-status").textContent = message; };

  function time(value) {
    const seconds = Math.max(0, Math.floor(Number(value) || 0));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
  }

  function updateTransport() {
    const playing = !audio.paused && !audio.ended;
    $("play-label").textContent = playing ? "Pause" : "Play";
    $("play-icon").textContent = playing ? "Ⅱ" : "▶";
    $("play-pause").setAttribute("aria-label", `${playing ? "Pause" : "Play"} ${current?.title || "recording"}`);
    $("replay").setAttribute("aria-label", `Replay ${current?.title || "recording"} from the start`);
  }

  function updateProgress() {
    const duration = isCurrentAudio() && Number.isFinite(audio.duration) ? audio.duration : 0;
    const position = duration > 0 ? Math.min(audio.currentTime || 0, duration) : 0;
    $("progress").disabled = duration <= 0;
    $("progress").max = String(duration || 1);
    $("progress").value = String(position);
    $("progress").setAttribute("aria-valuetext", `${time(position)} of ${time(duration)}`);
    $("elapsed").textContent = time(position);
    $("duration").textContent = time(duration);
  }

  function fail(message) {
    hasError = true;
    $("audio-error").hidden = false;
    $("error-message").textContent = message;
    status(`Could not play: ${current.title}.`);
    updateTransport();
  }

  function loadCurrent() {
    hasError = false;
    $("audio-error").hidden = true;
    status(`Loading: ${current.title}…`);
    audio.src = current.url;
    audio.load();
    updateProgress();
  }

  async function play() {
    if (!current) return;
    if (hasError) { generation += 1; loadCurrent(); }
    const expected = generation;
    if (audio.ended) audio.currentTime = 0;
    try { await audio.play(); }
    catch (error) {
      if (expected !== generation || error.name === "AbortError") return;
      fail("Playback could not start. Press Try again to reload this recording.");
    }
  }

  function markSelection() {
    document.querySelectorAll("[data-comparison]").forEach(button => {
      if (!referenceSelected && Number(button.dataset.comparison) === comparisonIndex) button.setAttribute("aria-current", "step");
      else button.removeAttribute("aria-current");
    });
    document.querySelectorAll("[data-variant]").forEach(button => button.setAttribute("aria-pressed", String(!referenceSelected && Number(button.dataset.variant) === variantIndex)));
    document.querySelectorAll("[data-reference]").forEach(button => button.setAttribute("aria-pressed", String(referenceSelected && button.dataset.reference === current.id)));
  }

  function selectClip(clipId, autoPlay = false) {
    const clip = clips.get(clipId);
    generation += 1;
    audio.pause();
    current = { ...clip, url: url(clip.matched) };
    const comparison = library.comparisons[comparisonIndex];
    $("current-kind").textContent = referenceSelected ? "ORIGINAL DEPTH REFERENCE" : `COMPARISON ${comparisonIndex + 1} OF 3`;
    $("sound-title").textContent = referenceSelected ? clip.title : comparison.title;
    $("sound-description").textContent = referenceSelected ? "The unchanged recording from the original three-sound guide." : comparison.description;
    $("listening-prompt").hidden = referenceSelected;
    $("listening-prompt").textContent = referenceSelected ? "" : comparison.question;
    $("variants").hidden = referenceSelected;
    $("selected-sound").textContent = `Selected: ${clip.title} · OHM model ${clip.modelVersion}`;
    $("audio-download").href = url(clip.matched);
    $("recipe-download").href = url(clip.recipe);
    $("raw-download").href = url(clip.raw);
    $("step-navigation").hidden = referenceSelected;
    $("return-comparison").hidden = !referenceSelected;
    $("previous").disabled = comparisonIndex === 0;
    $("next").disabled = comparisonIndex === library.comparisons.length - 1;
    $("next").textContent = comparisonIndex < 2 ? `Next: ${tabLabels[comparisonIndex + 1].toLowerCase()} →` : "Last comparison";
    markSelection();
    loadCurrent();
    updateTransport();
    if (autoPlay) play();
  }

  function selectComparison(index) {
    if (!library || index < 0 || index >= library.comparisons.length) return;
    comparisonIndex = index;
    variantIndex = 0;
    referenceSelected = false;
    const comparison = library.comparisons[index];
    $("variants").replaceChildren(...comparison.variants.map((variant, i) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "secondary-button";
      button.dataset.variant = String(i);
      button.textContent = variant.label;
      button.setAttribute("aria-label", `Play ${variant.label}`);
      button.addEventListener("click", () => {
        referenceSelected = false;
        variantIndex = i;
        selectClip(variant.clipId, true);
      });
      return button;
    }));
    selectClip(comparison.variants[0].clipId);
  }

  function stop() {
    if (!current) return;
    generation += 1;
    audio.pause();
    if (audio.readyState > 0) audio.currentTime = 0;
    updateProgress();
    updateTransport();
    status(`Stopped: ${current.title}.`);
  }

  function validate(value) {
    if (!Array.isArray(value.clips) || !Array.isArray(value.comparisons) || value.comparisons.length !== 3 || !Array.isArray(value.references) || value.references.length !== 2) throw new Error("Incomplete listening collection");
    const byId = new Map(value.clips.map(clip => [clip.id, clip]));
    if (byId.size !== value.clips.length) throw new Error("Duplicate recording identifier");
    for (const clip of value.clips) {
      if (![clip.id, clip.title, clip.matched, clip.raw, clip.recipe].every(item => typeof item === "string" && item.length > 0) || !Number.isInteger(clip.modelVersion)) throw new Error("Incomplete recording");
    }
    for (const comparison of value.comparisons) {
      if (![comparison.title, comparison.description, comparison.question].every(item => typeof item === "string") || !Array.isArray(comparison.variants) || comparison.variants.length !== 2) throw new Error("Incomplete comparison");
      for (const variant of comparison.variants) if (!byId.has(variant.clipId) || typeof variant.label !== "string") throw new Error("Missing comparison recording");
    }
    for (const reference of value.references) if (!byId.has(reference.clipId) || typeof reference.label !== "string") throw new Error("Missing depth reference");
    if (typeof value.bundle !== "string") throw new Error("Missing download bundle");
    return byId;
  }

  async function loadLibrary() {
    const expected = ++libraryGeneration;
    $("library-error").hidden = true;
    $("library-status").hidden = false;
    $("library-status").textContent = "Opening the recordings…";
    try {
      const response = await fetch(url("assets/manifest.json"), { cache: "no-store" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const value = await response.json();
      const byId = validate(value);
      if (expected !== libraryGeneration) return;
      library = value;
      clips = byId;
      $("comparisons").replaceChildren(...library.comparisons.map((comparison, index) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "step-button";
        button.dataset.comparison = String(index);
        const number = document.createElement("span");
        number.className = "step-number";
        number.textContent = String(index + 1);
        const label = document.createElement("span");
        label.textContent = tabLabels[index];
        button.append(number, label);
        button.addEventListener("click", () => selectComparison(index));
        return button;
      }));
      $("references").replaceChildren(...library.references.map(reference => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "secondary-button";
        button.dataset.reference = reference.clipId;
        button.textContent = reference.label;
        button.setAttribute("aria-pressed", "false");
        button.addEventListener("click", () => {
          referenceSelected = true;
          selectClip(reference.clipId, true);
        });
        return button;
      }));
      $("bundle-download").href = url(library.bundle);
      $("library-status").hidden = true;
      $("player").hidden = false;
      $("extra-content").hidden = false;
      $("stop-all").disabled = false;
      selectComparison(0);
    } catch (error) {
      if (expected !== libraryGeneration) return;
      $("library-status").textContent = "The recordings are not ready.";
      $("library-error-message").textContent = "The listening collection could not load. Press Try again to reopen it.";
      $("library-error").hidden = false;
    }
  }

  $("previous").addEventListener("click", () => selectComparison(comparisonIndex - 1));
  $("next").addEventListener("click", () => selectComparison(comparisonIndex + 1));
  $("return-comparison").addEventListener("click", () => selectComparison(comparisonIndex));
  $("play-pause").addEventListener("click", () => { if (!audio.paused) audio.pause(); else play(); });
  $("replay").addEventListener("click", () => { if (audio.readyState > 0) audio.currentTime = 0; play(); });
  $("stop-all").addEventListener("click", stop);
  $("retry").addEventListener("click", () => { generation += 1; audio.pause(); loadCurrent(); updateTransport(); });
  $("retry-library").addEventListener("click", loadLibrary);
  document.addEventListener("keydown", event => { if (event.key === "Escape") stop(); });
  $("progress").addEventListener("input", () => { if (isCurrentAudio() && audio.readyState > 0) { audio.currentTime = Number($("progress").value); updateProgress(); } });
  audio.addEventListener("loadedmetadata", () => {
    if (!isCurrentAudio()) return;
    updateProgress();
    if (audio.paused) status(`Ready: ${current.title}. Press Play.`);
  });
  audio.addEventListener("play", () => { if (isCurrentAudio()) { status(`Playing: ${current.title}.`); updateTransport(); } });
  audio.addEventListener("pause", () => { if (!isCurrentAudio()) return; if (!audio.ended && !hasError) status(`Paused: ${current.title}.`); updateTransport(); });
  audio.addEventListener("ended", () => { if (isCurrentAudio()) { status(`Finished: ${current.title}. Replay it, or choose another version.`); updateTransport(); } });
  audio.addEventListener("timeupdate", updateProgress);
  audio.addEventListener("durationchange", updateProgress);
  audio.addEventListener("error", () => { if (isCurrentAudio()) fail("This recording could not load. Press Try again to reload it."); });
  loadLibrary();
})();
