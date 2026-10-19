"use strict";

(() => {
  const STORAGE_KEY = "snipsnap.orphica.audition.review.v1";
  const allowedDecisions = new Set(["Keep", "Revise", "Drop"]);
  const audio = document.getElementById("audio");
  const player = document.getElementById("player-panel");
  const groupsContainer = document.getElementById("clip-groups");
  const filtersContainer = document.getElementById("group-filters");
  const loadStatus = document.getElementById("load-status");
  const playerStatus = document.getElementById("player-status");
  const reviewSummary = document.getElementById("review-summary");
  const firstPlay = document.getElementById("first-play");
  const cards = new Map();
  const downloadLinks = new Map();
  const recipeUrls = [];
  let manifest = null;
  let entries = [];
  let current = null;
  let level = "matched";
  let selectedGroup = "All";
  let sourceGeneration = 0;
  let storageAvailable = true;
  let review = readReview();

  function readReview() {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
      if (!stored || typeof stored !== "object" || Array.isArray(stored)) return {};
      const clean = {};
      for (const [id, value] of Object.entries(stored)) {
        if (!value || typeof value !== "object") continue;
        clean[id] = {
          decision: allowedDecisions.has(value.decision) ? value.decision : "",
          note: typeof value.note === "string" ? value.note.slice(0, 4000) : "",
          updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : ""
        };
      }
      return clean;
    } catch (_) {
      storageAvailable = false;
      return {};
    }
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function localAsset(path) {
    if (typeof path !== "string" || !path || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(path)) return null;
    const url = new URL(path, location.href);
    if (url.origin !== location.origin) return null;
    return url.href;
  }

  function clipSource(entry) {
    return localAsset(level === "raw" ? entry.rawFile : entry.file);
  }

  function formatDuration(seconds) {
    if (!Number.isFinite(Number(seconds))) return "";
    const duration = Math.max(0, Number(seconds));
    return `${duration.toFixed(1)}s`;
  }

  function updatePlayerLabels() {
    if (!current) return;
    document.getElementById("player-title").textContent = current.title;
    document.getElementById("player-description").textContent = current.description;
    document.getElementById("player-level").textContent = `${level.toUpperCase()} · ${current.group}`;
    audio.setAttribute("aria-label", `${current.title}, ${level} recording`);
  }

  function updateCardStates() {
    for (const [id, card] of cards) {
      const selected = !!current && current.id === id;
      const playing = selected && !audio.paused && !audio.ended;
      card.root.classList.toggle("is-current", selected);
      card.play.setAttribute("aria-pressed", String(playing));
      card.play.setAttribute("aria-label", `${playing ? "Pause" : "Play"} ${card.entry.title}`);
      card.play.querySelector(".play-symbol").textContent = playing ? "Ⅱ" : "▶";
      card.play.querySelector(".play-label").textContent = playing ? "Pause" : "Play";
    }
  }

  async function startPlayback(generation) {
    try {
      await audio.play();
      if (generation !== sourceGeneration) return;
      playerStatus.textContent = "";
    } catch (error) {
      if (generation !== sourceGeneration || error.name === "AbortError") return;
      playerStatus.textContent = "Playback could not start. Try the player’s play button or download the WAV.";
      updateCardStates();
    }
  }

  function chooseClip(entry) {
    player.hidden = false;
    document.body.classList.add("player-open");
    if (current && current.id === entry.id) {
      if (!audio.paused && !audio.ended) {
        audio.pause();
        return;
      }
      if (audio.ended) audio.currentTime = 0;
      startPlayback(sourceGeneration);
      return;
    }
    sourceGeneration += 1;
    audio.pause();
    current = entry;
    updatePlayerLabels();
    playerStatus.textContent = "Loading recording…";
    audio.src = clipSource(entry);
    audio.load();
    updateCardStates();
    startPlayback(sourceGeneration);
  }

  function stopAll(close = false) {
    sourceGeneration += 1;
    audio.pause();
    if (audio.readyState > 0) audio.currentTime = 0;
    playerStatus.textContent = "";
    updateCardStates();
    if (close) {
      player.hidden = true;
      document.body.classList.remove("player-open");
      if (current && cards.has(current.id)) cards.get(current.id).play.focus();
    }
  }

  function setLevel(nextLevel) {
    if (!new Set(["matched", "raw"]).has(nextLevel) || nextLevel === level) return;
    const wasPlaying = !audio.paused && !audio.ended;
    const position = Number.isFinite(audio.currentTime) ? audio.currentTime : 0;
    level = nextLevel;
    document.querySelectorAll("[data-level]").forEach(button => {
      button.setAttribute("aria-pressed", String(button.dataset.level === level));
    });
    document.getElementById("level-description").textContent = level === "matched"
      ? "Matched brings clips to a comparable level."
      : "Raw uses one shared gain, preserving level differences.";
    for (const [id, link] of downloadLinks) {
      const entry = cards.get(id).entry;
      link.href = clipSource(entry);
      link.textContent = `↓ WAV · ${level}`;
      link.download = `${entry.id}-${level}.wav`;
    }
    if (!current) return;
    sourceGeneration += 1;
    const generation = sourceGeneration;
    audio.pause();
    updatePlayerLabels();
    playerStatus.textContent = "Switching recording level…";
    audio.src = clipSource(current);
    audio.addEventListener("loadedmetadata", () => {
      if (generation !== sourceGeneration) return;
      audio.currentTime = Math.min(position, Math.max(0, audio.duration - 0.01));
      playerStatus.textContent = "";
      if (wasPlaying) startPlayback(generation);
    }, { once: true });
    audio.load();
    updateCardStates();
  }

  function persistReview(id, decision, note) {
    review[id] = { decision, note, updatedAt: new Date().toISOString() };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(review));
      storageAvailable = true;
    } catch (_) {
      storageAvailable = false;
    }
    updateReviewSummary();
  }

  function updateReviewSummary() {
    const reviewed = entries.filter(entry => {
      const item = review[entry.id];
      return item && (item.decision || item.note.trim());
    });
    const decisions = [...allowedDecisions].map(decision => {
      const count = reviewed.filter(entry => review[entry.id].decision === decision).length;
      return `${count} ${decision.toLowerCase()}`;
    }).join(" · ");
    const saving = storageAvailable ? "Saved in this browser." : "Browser storage is unavailable; export to keep your notes.";
    reviewSummary.textContent = reviewed.length
      ? `${reviewed.length} of ${entries.length} reviewed · ${decisions}. ${saving}`
      : `No recordings reviewed yet. ${saving}`;
    document.getElementById("export-review").disabled = reviewed.length === 0;
  }

  function buildCard(entry, index) {
    const root = element("article", "clip-card");
    root.dataset.clipId = entry.id;
    const titleId = `clip-title-${index}`;
    root.setAttribute("aria-labelledby", titleId);
    const top = element("div", "clip-card-top");
    top.append(element("span", "clip-index", String(index + 1).padStart(2, "0")));
    top.append(element("span", "", formatDuration(entry.durationSeconds)));
    const title = element("h4", "", entry.title);
    title.id = titleId;
    const description = element("p", "clip-description", entry.description);
    const descriptionId = `clip-description-${index}`;
    description.id = descriptionId;
    const playRow = element("div", "clip-play-row");
    const play = element("button", "clip-play");
    play.type = "button";
    play.setAttribute("aria-label", `Play ${entry.title}`);
    play.setAttribute("aria-describedby", descriptionId);
    play.setAttribute("aria-pressed", "false");
    const symbol = element("span", "play-symbol", "▶");
    symbol.setAttribute("aria-hidden", "true");
    play.append(symbol, element("span", "play-label", "Play"));
    play.addEventListener("click", () => chooseClip(entry));
    playRow.append(play, element("span", "clip-format", entry.channels === 2 ? "STEREO" : entry.channels === 1 ? "MONO" : "WAV"));
    const downloads = element("div", "clip-downloads");
    const wav = element("a", "", `↓ WAV · ${level}`);
    wav.href = clipSource(entry);
    wav.download = `${entry.id}-${level}.wav`;
    wav.setAttribute("aria-label", `Download ${entry.title} WAV`);
    downloadLinks.set(entry.id, wav);
    downloads.append(wav);
    const patchUrl = localAsset(entry.patchFile);
    let recipeUrl = patchUrl;
    if (!recipeUrl && entry.config) {
      recipeUrl = URL.createObjectURL(new Blob([JSON.stringify(entry.config, null, 2)], { type: "application/json" }));
      recipeUrls.push(recipeUrl);
    }
    if (recipeUrl) {
      const recipe = element("a", "", "↓ Recipe JSON");
      recipe.href = recipeUrl;
      recipe.download = `${entry.id}-recipe.json`;
      recipe.setAttribute("aria-label", `Download ${entry.title} recipe JSON`);
      downloads.append(recipe);
    }
    const reviewArea = element("div", "clip-review");
    reviewArea.append(element("span", "small-label", "YOUR TAKE"));
    const decisions = element("div", "review-choices");
    decisions.setAttribute("role", "group");
    decisions.setAttribute("aria-label", `Review ${entry.title}`);
    const saved = review[entry.id] || { decision: "", note: "" };
    let decision = saved.decision;
    const note = element("textarea", "");
    note.id = `clip-note-${index}`;
    note.rows = 2;
    note.maxLength = 4000;
    note.placeholder = "What catches your ear?";
    note.value = saved.note;
    for (const choice of allowedDecisions) {
      const button = element("button", "", choice);
      button.type = "button";
      button.setAttribute("aria-pressed", String(choice === decision));
      button.addEventListener("click", () => {
        decision = decision === choice ? "" : choice;
        [...decisions.children].forEach(child => child.setAttribute("aria-pressed", String(child.textContent === decision)));
        persistReview(entry.id, decision, note.value);
      });
      decisions.append(button);
    }
    const label = element("label", "note-label", "Listening note · saved locally");
    label.htmlFor = note.id;
    note.addEventListener("input", () => persistReview(entry.id, decision, note.value));
    reviewArea.append(decisions, label, note);
    root.append(top, title, description, playRow, downloads, reviewArea);
    cards.set(entry.id, { root, play, entry });
    return root;
  }

  function applyFilter(group) {
    selectedGroup = group;
    let visibleCount = 0;
    [...groupsContainer.children].forEach(section => {
      section.hidden = group !== "All" && section.dataset.group !== group;
      if (!section.hidden) visibleCount += Number(section.dataset.count);
    });
    [...filtersContainer.children].forEach(button => button.setAttribute("aria-pressed", String(button.dataset.group === group)));
    document.getElementById("collection-count").textContent = `${visibleCount} ${visibleCount === 1 ? "recording" : "recordings"}${group === "All" ? " · all groups" : ` · ${group}`}`;
  }

  function renderCollection() {
    groupsContainer.replaceChildren();
    filtersContainer.replaceChildren();
    cards.clear();
    downloadLinks.clear();
    recipeUrls.splice(0).forEach(url => URL.revokeObjectURL(url));
    const groups = [...new Set(entries.map(entry => entry.group))];
    for (const group of ["All", ...groups]) {
      const button = element("button", "", group);
      button.type = "button";
      button.dataset.group = group;
      button.setAttribute("aria-pressed", String(group === selectedGroup));
      button.addEventListener("click", () => applyFilter(group));
      filtersContainer.append(button);
    }
    groups.forEach(group => {
      const collection = entries.filter(entry => entry.group === group);
      const section = element("section", "clip-group");
      section.dataset.group = group;
      section.dataset.count = String(collection.length);
      const heading = element("div", "group-heading");
      heading.append(element("h3", "", group), element("span", "", `${String(collection.length).padStart(2, "0")} RECORDINGS`));
      const grid = element("div", "clip-grid");
      collection.forEach(entry => grid.append(buildCard(entry, entries.indexOf(entry))));
      section.append(heading, grid);
      groupsContainer.append(section);
    });
    filtersContainer.hidden = false;
    applyFilter(groups.includes(selectedGroup) ? selectedGroup : "All");
    const sampleRate = Number(manifest.sampleRate);
    document.getElementById("audio-format").textContent = Number.isFinite(sampleRate)
      ? `${(sampleRate / 1000).toFixed(1)} kHz · WAV`
      : "WAV RECORDINGS";
    const first = entries.find(entry => /clean|dry harp/i.test(entry.title)) || entries[0];
    firstPlay.disabled = false;
    firstPlay.replaceChildren(element("span", "", "▶"), document.createTextNode(`Hear ${first.title}`));
    firstPlay.onclick = () => chooseClip(first);
    loadStatus.hidden = true;
    document.getElementById("retry-load").hidden = true;
    updateReviewSummary();
  }

  async function loadCollection() {
    loadStatus.hidden = false;
    loadStatus.textContent = "Preparing the recordings…";
    document.getElementById("retry-load").hidden = true;
    try {
      const response = await fetch("manifest.json", { cache: "no-cache" });
      if (!response.ok) throw new Error(`Manifest returned ${response.status}`);
      const data = await response.json();
      if (!Array.isArray(data.entries) || !data.entries.length) throw new Error("The collection is empty");
      const seen = new Set();
      for (const entry of data.entries) {
        if (!entry || typeof entry.id !== "string" || !entry.id || seen.has(entry.id)
          || typeof entry.title !== "string" || typeof entry.description !== "string"
          || !localAsset(entry.file) || !localAsset(entry.rawFile)) {
          throw new Error("The recording manifest contains an invalid entry");
        }
        seen.add(entry.id);
      }
      manifest = data;
      entries = data.entries.map(entry => ({ ...entry, group: typeof entry.group === "string" && entry.group ? entry.group : "Recordings" }));
      renderCollection();
    } catch (_) {
      loadStatus.textContent = "The recordings couldn’t load. Check your connection, then try again.";
      document.getElementById("collection-count").textContent = "Collection unavailable";
      document.getElementById("retry-load").hidden = false;
      firstPlay.disabled = true;
    }
  }

  document.getElementById("stop-all").addEventListener("click", () => stopAll());
  document.getElementById("close-player").addEventListener("click", () => stopAll(true));
  document.getElementById("retry-load").addEventListener("click", loadCollection);
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") stopAll();
  });
  document.querySelectorAll("[data-level]").forEach(button => {
    button.addEventListener("click", () => setLevel(button.dataset.level));
  });
  ["play", "pause", "ended"].forEach(event => audio.addEventListener(event, updateCardStates));
  audio.addEventListener("error", () => {
    playerStatus.textContent = "This recording could not load. Try another clip or download its WAV.";
    updateCardStates();
  });
  audio.addEventListener("playing", () => { playerStatus.textContent = ""; });
  document.getElementById("export-review").addEventListener("click", () => {
    const reviewed = entries.filter(entry => review[entry.id] && (review[entry.id].decision || review[entry.id].note.trim()));
    const result = {
      instrument: "ORPHICA / SnipSnap",
      prototypeVersion: manifest?.prototypeVersion || "0.1",
      exportedAt: new Date().toISOString(),
      listeningLevel: level,
      reviews: reviewed.map(entry => ({ id: entry.id, title: entry.title, group: entry.group, ...review[entry.id], config: entry.config || null }))
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: "application/json" }));
    const link = element("a", "");
    link.href = url;
    link.download = `snipsnap-orphica-review-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  loadCollection();
})();
