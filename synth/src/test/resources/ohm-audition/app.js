/* OHM listening room. Selects finite rendered files; no browser synthesis. */
"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const audio = $("audio");
  const storageKey = "snipsnap.ohm.first-listen.notes.v1";
  const macroNames = ["TOUCH", "BODY", "LENGTH", "MEDDLING", "COMMUNE"];
  const identityCopy = {
    solo: { title: "Solo", description: "The foreground pluck, with room to breathe.", caption: "ONE CLEAR VOICE" },
    argument: { title: "Argument", description: "The companions interrupt and stand apart.", caption: "A LITTLE FRICTION" },
    communion: { title: "Communion", description: "The company gathers inside a shared body.", caption: "FINDING EACH OTHER" },
    gather: { title: "Gather", description: "A solo becomes a conversation, then comes together.", caption: "A CHANGING GESTURE" },
    "quiet-influence": { title: "A quieter influence", description: "Little activity. A more connected body.", caption: "LISTEN UNDERNEATH" }
  };
  const controlDescriptions = {
    TOUCH: "From a gentler beginning to a firmer, brighter pluck.",
    BODY: "From a lean string to a fuller resonant character.",
    LENGTH: "From a short reply to a longer ring.",
    MEDDLING: "Bring the companions into the conversation. COMMUNE stays at the middle setting.",
    COMMUNE: "Draw separate voices into a shared resonance. MEDDLING stays at the middle setting."
  };
  const state = { manifest: null, clips: [], byId: new Map(), selected: null, level: "matched", loading: false, audioError: false, notes: {}, storageAvailable: true };

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function clipTitle(clip) { return identityCopy[clip.id]?.title || clip.title || clip.id; }
  function clipDescription(clip) { return identityCopy[clip.id]?.description || clip.description || "A rendered listening example."; }
  function formatTime(seconds) {
    const value = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
    return `${Math.floor(value / 60)}:${Math.floor(value % 60).toString().padStart(2, "0")}`;
  }
  function durationLabel(clip) { return `${Math.round(clip.seconds || 0)}s`; }
  function macroPercent(value) { return `${Math.round(Math.max(0, Math.min(1, Number(value) || 0)) * 100)}`; }
  function setAudioStatus(message, isError = false) {
    $("audio-status").textContent = message;
    $("audio-status").classList.toggle("is-error", isError);
  }
  function setPlaybackState(label) { $("playback-state").textContent = label; }

  function readNotes() {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
      if (saved && typeof saved === "object" && !Array.isArray(saved)) {
        for (const [id, value] of Object.entries(saved)) {
          if (typeof value === "string") state.notes[id] = value;
        }
      }
    } catch (_) {
      state.storageAvailable = false;
      $("notes-local").textContent = "Notes stay in this tab. Export them before leaving.";
    }
  }

  function saveNote() {
    if (!state.selected) return;
    const text = $("listening-note").value;
    if (text.trim()) state.notes[state.selected.id] = text;
    else delete state.notes[state.selected.id];
    try {
      localStorage.setItem(storageKey, JSON.stringify(state.notes));
      state.storageAvailable = true;
      $("notes-status").textContent = text.trim() ? "Saved in this browser." : "Nothing leaves this device.";
    } catch (_) {
      state.storageAvailable = false;
      $("notes-status").textContent = "Kept in this tab. Export before leaving.";
      $("notes-local").textContent = "Browser storage is unavailable; use Export to keep your notes.";
    }
  }

  function exportNotes(format) {
    saveNote();
    const entries = state.clips.filter((clip) => state.notes[clip.id]).map((clip) => ({
      id: clip.id, title: clipTitle(clip), note: state.notes[clip.id], macros: clip.macros,
      midi: clip.midi, velocity: clip.velocity, recipe: clip.recipe
    }));
    const exportedAt = new Date().toISOString();
    const documentData = { title: "OHM / SnipSnap — First-listen notes", exportedAt, playbackLevel: state.level, notes: entries };
    let text;
    if (format === "json") text = JSON.stringify(documentData, null, 2) + "\n";
    else {
      text = `OHM / SnipSnap — First-listen notes\n${exportedAt}\nPlayback level: ${state.level}\n\n`;
      text += entries.length ? entries.map((entry) => `${entry.title}\n${"—".repeat(entry.title.length)}\n${entry.note}\n`).join("\n") : "No listening notes recorded yet.\n";
    }
    const blob = new Blob([text], { type: format === "json" ? "application/json" : "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = node("a");
    anchor.href = url;
    anchor.download = `ohm-listening-notes-${exportedAt.slice(0, 10)}.${format}`;
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    $("notes-status").textContent = `${entries.length} ${entries.length === 1 ? "note" : "notes"} exported as .${format}.`;
  }

  function makeClipCard(clip, options = {}) {
    if (!clip) return node("p", "study-footnote", "This listening example is not available.");
    const button = node("button", "clip-card clip-select");
    button.type = "button";
    button.dataset.clip = clip.id;
    button.setAttribute("aria-pressed", String(state.selected?.id === clip.id));
    button.setAttribute("aria-label", `Select ${options.title || clipTitle(clip)}. ${options.description || clipDescription(clip)}`);
    const top = node("span", "card-top");
    top.append(node("span", "", options.caption || identityCopy[clip.id]?.caption || "LISTENING EXAMPLE"), node("span", "", "↗"));
    const bottom = node("span", "card-bottom");
    bottom.append(node("span", "", options.bottom || (clip.gesture === "GATHER" ? "SOLO → TOGETHER" : "CHOOSE TO LISTEN")), node("span", "", durationLabel(clip)));
    button.append(top, node("span", "card-title", options.title || clipTitle(clip)), node("span", "card-description", options.description || clipDescription(clip)), bottom);
    button.classList.toggle("is-selected", state.selected?.id === clip.id);
    button.addEventListener("click", () => selectClip(clip.id));
    return button;
  }

  function renderFirst() {
    const container = $("first-clips");
    container.replaceChildren(...["solo", "argument", "communion", "gather"].map((id) => makeClipCard(state.byId.get(id))));
  }

  function renderRelationship() {
    const container = $("relationship-matrix");
    const labels = [["Solo", "Solo", "Solo"], ["Apart", "In company", "In accord"], ["Argument", "Meeting", "Communion"]];
    const buttons = [];
    for (let m = 2; m >= 0; m--) {
      for (let c = 0; c < 3; c++) {
        const id = `matrix-m${m}-c${c}`;
        const clip = state.byId.get(id);
        if (!clip) continue;
        const meddling = macroPercent(clip.macros?.MEDDLING);
        const commune = macroPercent(clip.macros?.COMMUNE);
        const button = node("button", "matrix-cell clip-select");
        button.type = "button";
        button.dataset.clip = id;
        button.setAttribute("aria-label", `Select ${labels[m][c]}: MEDDLING ${meddling} percent, COMMUNE ${commune} percent`);
        button.setAttribute("aria-pressed", "false");
        button.append(node("b", "", labels[m][c]), node("span", "", `M ${meddling} / C ${commune}`));
        button.addEventListener("click", () => selectClip(id));
        buttons.push(button);
      }
    }
    container.replaceChildren(...buttons);
    const quiet = state.byId.get("quiet-influence");
    if (quiet) {
      const wrap = node("div", "quiet-card");
      const button = node("button", "clip-select", "Try a quieter influence");
      button.type = "button";
      button.dataset.clip = quiet.id;
      button.setAttribute("aria-pressed", "false");
      button.append(node("span", "", "↗"));
      button.addEventListener("click", () => selectClip(quiet.id));
      wrap.append(button, node("p", "", "Little activity can still leave a shared resonance behind."));
      $("quiet-clip").replaceChildren(wrap);
    }
  }

  function renderControls() {
    const control = $("control-select").value;
    $("control-description").textContent = controlDescriptions[control];
    const ids = control === "MEDDLING" ? ["matrix-m0-c1", "matrix-m1-c1", "matrix-m2-c1"] : control === "COMMUNE" ? ["matrix-m1-c0", "matrix-m1-c1", "matrix-m1-c2"] : [0, 1, 2].map((n) => `${control.toLowerCase()}-${n}`);
    const titles = ["Low", "Middle", "High"];
    $("control-clips").replaceChildren(...ids.map((id, i) => {
      const clip = state.byId.get(id);
      return makeClipCard(clip, { title: titles[i], caption: control, description: `${macroPercent(clip?.macros?.[control])}% ${control.toLowerCase()}`, bottom: "SELECT" });
    }));
  }

  function renderPhrases() {
    $("phrase-clips").replaceChildren(...["solo", "argument", "communion"].map((kind) => makeClipCard(state.byId.get(`phrase-${kind}`), {
      title: identityCopy[kind].title, caption: "MUSICAL PHRASE", description: identityCopy[kind].description, bottom: "SELECT"
    })));
  }

  function renderRange() {
    const register = $("register-select").value;
    const labels = ["Soft", "Medium", "Firm"];
    $("range-clips").replaceChildren(...[0, 1, 2].map((index) => {
      const clip = state.byId.get(`note-${register}-v${index}`);
      return makeClipCard(clip, { title: labels[index], caption: clip?.noteLabel || `MIDI ${register}`, description: `${Math.round((clip?.velocity || 0) * 100)}% playing strength`, bottom: "SELECT" });
    }));
  }

  function renderMacroValues(clip) {
    $("macro-values").replaceChildren(...macroNames.map((name) => {
      const wrap = node("div", "macro-value");
      const label = node("div", "macro-label");
      label.append(node("span", "", name), node("output", "", macroPercent(clip.macros?.[name])));
      const track = node("div", "macro-track");
      track.setAttribute("aria-hidden", "true");
      const fill = node("span");
      fill.style.width = `${macroPercent(clip.macros?.[name])}%`;
      track.append(fill);
      wrap.append(label, track);
      return wrap;
    }));
  }

  function updateSelectedButtons() {
    document.querySelectorAll(".clip-select").forEach((button) => {
      const selected = button.dataset.clip === state.selected?.id;
      button.classList.toggle("is-selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
  }

  function resetTransport() {
    audio.pause();
    $("player").classList.remove("is-playing");
    $("play-icon").textContent = "▶";
    $("play-text").textContent = "Play";
    $("play-button").setAttribute("aria-label", "Play selected example");
    $("progress").value = "0";
    $("progress").disabled = true;
    $("elapsed").textContent = "0:00";
  }

  function sourceFor(clip) { return clip[state.level] || clip.raw; }

  function loadSelectedAudio() {
    if (!state.selected) return;
    resetTransport();
    state.loading = true;
    state.audioError = false;
    setPlaybackState("OPENING AUDIO");
    setAudioStatus("Opening the selected example…");
    $("play-button").disabled = false;
    const source = sourceFor(state.selected);
    audio.src = source;
    audio.load();
    $("audio-download").href = source;
    $("audio-download").download = `${state.selected.id}-${state.level}.wav`;
    $("level-description").textContent = state.level === "matched" ? "MATCHED LEVEL · FOR COMPARISON" : "RAW LEVEL · ORIGINAL DYNAMICS";
    $("duration").textContent = formatTime(state.selected.seconds);
  }

  function selectClip(id) {
    const clip = state.byId.get(id);
    if (!clip) return;
    if (state.selected?.id === id) return;
    saveNote();
    state.selected = clip;
    const index = state.clips.indexOf(clip) + 1;
    const category = clip.group === "phrases" ? "MUSICAL PHRASE" : clip.group === "matrix" ? "THE RELATIONSHIP" : clip.group === "controls" ? "CONTROL STUDY" : clip.group === "register" ? "NOTE & TOUCH" : "FIRST LISTEN";
    $("player-category").textContent = `${category} / ${String(index).padStart(2, "0")}`;
    $("player-title").textContent = clipTitle(clip);
    $("player-description").textContent = clipDescription(clip);
    $("gesture-label").hidden = clip.gesture !== "GATHER";
    $("player-context").textContent = clip.midi === null || clip.midi === undefined ? "PHRASE" : `${clip.noteLabel || `MIDI ${clip.midi}`} · ${Math.round((clip.velocity || 0) * 100)}%`;
    $("patch-download").href = clip.recipe;
    $("patch-download").download = `${clip.id}.json`;
    $("patch-download").textContent = clip.recipeType === "OHM_PHRASE" ? "Selected phrase score ↓" : "Selected patch ↓";
    $("note-clip-title").textContent = clipTitle(clip);
    $("listening-note").value = state.notes[clip.id] || "";
    $("notes-status").textContent = state.notes[clip.id] ? "Your saved note for this example." : "Nothing leaves this device.";
    renderMacroValues(clip);
    updateSelectedButtons();
    loadSelectedAudio();
  }

  async function playSelected(fromStart = false) {
    if (!state.selected) return;
    if (!audio.paused && !fromStart) { audio.pause(); return; }
    if (state.audioError) loadSelectedAudio();
    if (fromStart || audio.ended) {
      try { audio.currentTime = 0; } catch (_) { /* Metadata may still be loading. */ }
    }
    try {
      setAudioStatus(state.loading ? "Opening audio… playback will begin when ready." : "");
      await audio.play();
    } catch (error) {
      if (error?.name === "AbortError") return;
      setPlaybackState("PRESS PLAY TO TRY AGAIN");
      setAudioStatus(error?.name === "NotAllowedError" ? "Playback needs a click or tap. Press Play to listen." : "The audio could not play. Check your connection, then press Play to retry.", true);
    }
  }

  function updateProgress() {
    const duration = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : state.selected?.seconds || 0;
    const current = Number.isFinite(audio.currentTime) ? audio.currentTime : 0;
    $("elapsed").textContent = formatTime(current);
    $("duration").textContent = formatTime(duration);
    $("progress").value = duration > 0 ? String(Math.round((current / duration) * 1000)) : "0";
    $("progress").setAttribute("aria-valuetext", `${formatTime(current)} of ${formatTime(duration)}`);
  }

  function setTab(name, moveFocus = false) {
    document.querySelectorAll("[data-tab]").forEach((tab) => {
      const active = tab.dataset.tab === name;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      $(tab.getAttribute("aria-controls")).hidden = !active;
      if (active && moveFocus) tab.focus();
    });
    updateSelectedButtons();
  }

  async function loadLibrary() {
    $("library-status").hidden = false;
    $("library-error").hidden = true;
    $("audition-content").hidden = true;
    try {
      const response = await fetch("assets/manifest.json", { cache: "no-cache" });
      if (!response.ok) throw new Error(`The audio library returned ${response.status}.`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error("The audio library has no listening examples yet.");
      const ids = new Set();
      for (const clip of manifest.clips) {
        if (!clip.id || ids.has(clip.id) || typeof clip.raw !== "string" || typeof clip.matched !== "string" || typeof clip.recipe !== "string") throw new Error("The audio library contains an incomplete example.");
        ids.add(clip.id);
      }
      state.manifest = manifest;
      state.clips = manifest.clips;
      state.byId = new Map(state.clips.map((clip) => [clip.id, clip]));
      renderFirst();
      renderRelationship();
      renderControls();
      renderPhrases();
      renderRange();
      $("bundle-download").href = manifest.bundle || "assets/ohm-first-listen.zip";
      $("bundle-download").download = "ohm-first-listen.zip";
      $("library-summary").textContent = `${state.clips.length} rendered examples · finite notes & phrases`;
      const technicalNotes = [
        `${(manifest.sampleRate || 44100).toLocaleString()} Hz · ${manifest.channels === 2 ? "stereo" : "mono"} WAV audio`,
        `Model version ${manifest.modelVersion ?? 1}${manifest.renderedAt ? ` · rendered ${manifest.renderedAt}` : ""}`,
        ...(Array.isArray(manifest.notes) ? manifest.notes.filter((entry) => typeof entry === "string") : [])
      ];
      $("technical-notes").replaceChildren(...technicalNotes.map((text) => node("li", "", text)));
      $("library-status").hidden = true;
      $("audition-content").hidden = false;
      state.selected = null;
      selectClip(state.byId.has("solo") ? "solo" : state.clips[0].id);
    } catch (error) {
      $("library-status").hidden = true;
      $("library-error").hidden = false;
      $("library-error-text").textContent = `${error.message || "The audio library is unavailable."} Reload the page or try again.`;
    }
  }

  document.querySelectorAll("[data-level]").forEach((button) => button.addEventListener("click", () => {
    if (state.level === button.dataset.level) return;
    state.level = button.dataset.level;
    document.querySelectorAll("[data-level]").forEach((item) => item.setAttribute("aria-pressed", String(item.dataset.level === state.level)));
    loadSelectedAudio();
  }));
  document.querySelectorAll("[data-tab]").forEach((tab) => {
    tab.addEventListener("click", () => setTab(tab.dataset.tab));
    tab.addEventListener("keydown", (event) => {
      const tabs = [...document.querySelectorAll("[data-tab]")];
      const index = tabs.indexOf(tab);
      let next;
      if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
      else if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
      else if (event.key === "Home") next = 0;
      else if (event.key === "End") next = tabs.length - 1;
      if (next !== undefined) { event.preventDefault(); setTab(tabs[next].dataset.tab, true); }
    });
  });
  $("control-select").addEventListener("change", () => { renderControls(); updateSelectedButtons(); });
  $("register-select").addEventListener("change", () => { renderRange(); updateSelectedButtons(); });
  $("play-button").addEventListener("click", () => playSelected());
  $("replay-button").addEventListener("click", () => playSelected(true));
  $("retry-button").addEventListener("click", loadLibrary);
  $("listening-note").addEventListener("input", saveNote);
  $("export-text").addEventListener("click", () => exportNotes("txt"));
  $("export-json").addEventListener("click", () => exportNotes("json"));
  $("progress").addEventListener("input", () => {
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      audio.currentTime = (Number($("progress").value) / 1000) * audio.duration;
      updateProgress();
    }
  });
  audio.addEventListener("loadedmetadata", () => { $("progress").disabled = !Number.isFinite(audio.duration); updateProgress(); });
  audio.addEventListener("canplay", () => {
    state.loading = false;
    if (audio.paused) { setPlaybackState("READY TO LISTEN"); setAudioStatus("Press Play to listen."); }
  });
  audio.addEventListener("playing", () => {
    state.loading = false;
    state.audioError = false;
    $("player").classList.add("is-playing");
    $("play-icon").textContent = "Ⅱ";
    $("play-text").textContent = "Pause";
    $("play-button").setAttribute("aria-label", "Pause selected example");
    setPlaybackState("NOW LISTENING");
    setAudioStatus(state.selected?.gesture === "GATHER" ? "Listen as the companions arrive, then come together." : "");
  });
  audio.addEventListener("pause", () => {
    $("player").classList.remove("is-playing");
    $("play-icon").textContent = "▶";
    $("play-text").textContent = audio.ended ? "Play again" : "Play";
    $("play-button").setAttribute("aria-label", "Play selected example");
    if (!audio.ended && !state.loading && !state.audioError) setPlaybackState("PAUSED");
  });
  audio.addEventListener("ended", () => {
    setPlaybackState("END OF EXAMPLE");
    $("play-text").textContent = "Play again";
    setAudioStatus("That is the full example. Press Play or Replay to hear it again.");
    updateProgress();
  });
  audio.addEventListener("waiting", () => { setPlaybackState("BUFFERING"); setAudioStatus("Loading audio…"); });
  audio.addEventListener("stalled", () => { if (!audio.paused) setAudioStatus("Waiting for the audio file. Check your connection if playback does not resume."); });
  audio.addEventListener("timeupdate", updateProgress);
  audio.addEventListener("error", () => {
    if (!state.selected) return;
    state.loading = false;
    state.audioError = true;
    $("progress").disabled = true;
    setPlaybackState("AUDIO UNAVAILABLE");
    setAudioStatus("This audio file could not load. Check your connection, then press Play to try again.", true);
  });
  readNotes();
  loadLibrary();
})();
