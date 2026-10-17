(() => {
  'use strict';

  const qualityLabels = { matched: 'Level matched', native: 'Native', raw: 'Raw' };
  const qualityDescriptions = {
    matched: 'Level-matched files make differences in tone easier to compare.',
    native: 'Hear each available native render at its original playback level.',
    raw: 'Hear the available raw files for a closer inspection of the engine.'
  };
  const elements = {
    status: document.getElementById('load-status'),
    sections: document.getElementById('sections'),
    nav: document.getElementById('section-nav'),
    footer: document.getElementById('listening-footer'),
    qualityDescription: document.getElementById('quality-description')
  };
  let quality = 'matched';
  let activePlayer = null;
  let manifest = null;
  let observer = null;
  const players = [];

  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined && text !== null) item.textContent = String(text);
    return item;
  }

  function arrowIcon() {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('arrow-icon');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', 'M12 4v16M6 14l6 6 6-6');
    svg.append(path);
    return svg;
  }

  function assetPath(value) {
    const path = typeof value === 'string' ? value : value && (value.url || value.path || value.href || value.file);
    if (!path || typeof path !== 'string') return null;
    try {
      const url = new URL(path, document.baseURI);
      return ['http:', 'https:', 'blob:'].includes(url.protocol) ? path : null;
    } catch (_) { return null; }
  }

  function displayNumber(value, digits = 2) {
    const num = Number(value);
    return Number.isFinite(num) ? num.toFixed(digits).replace(/\.?0+$/, '') : String(value);
  }

  function prettyName(value) {
    return String(value).replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').replace(/\b\w/g, c => c.toUpperCase());
  }

  function secondsText(seconds) {
    const num = Number(seconds);
    if (!Number.isFinite(num) || num <= 0) return '';
    return num >= 60 ? `${Math.floor(num / 60)}:${String(Math.round(num % 60)).padStart(2, '0')}` : `${displayNumber(num, 1)} sec`;
  }

  function noteName(midi) {
    const num = Number(midi);
    if (!Number.isFinite(num)) return null;
    return `${['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((num % 12) + 12) % 12]}${Math.floor(num / 12) - 1}`;
  }

  function resolveFile(clip, requested = quality) {
    const variants = clip.audio || clip.files || clip;
    for (const key of [requested, 'matched', 'native', 'raw']) {
      const path = assetPath(variants[key] || clip[key]);
      if (path) return { path, key };
    }
    return null;
  }

  function addMeta(parent, text) {
    if (text) parent.append(node('span', '', text));
  }

  function sourceVariant(clip) {
    const comparison = clip.comparison && typeof clip.comparison === 'object' ? clip.comparison : {};
    const variant = String(comparison.variant || clip.variant || clip.sourceModel || '').toLowerCase();
    if (variant === 'r1' || variant.includes('original') || Number(clip.modelVersion) === 1) return 'r1';
    if (variant.includes('clean') || clip.sourceMotion === false) return 'r2_clean';
    if (variant.includes('living') || Number(clip.modelVersion) === 2) return 'r2_living';
    return null;
  }

  function sourceLabel(clip) {
    if (clip.directionLabel || clip.auditionLabel) return clip.directionLabel || clip.auditionLabel;
    if (clip.comparison && typeof clip.comparison === 'object' && clip.comparison.label) return clip.comparison.label;
    return { r1: 'R1 original', r2_clean: 'R2 motion off', r2_living: 'R2 full source' }[sourceVariant(clip)] || clip.sourceModelLabel || null;
  }

  function createRecipe(clip) {
    const recipe = clip.recipe;
    const recipeUrl = assetPath(recipe);
    const content = typeof recipe === 'object' && recipe && !recipeUrl ? recipe : null;
    if (!content && !clip.macros && clip.alignment === undefined) return null;
    const details = node('details', 'clip-recipe');
    const summary = node('summary', '', recipeUrl ? 'View the control settings' : 'View the sound recipe');
    const pre = node('pre', '', JSON.stringify(content || {
      voice: clip.voice,
      midi: clip.midi,
      velocity: clip.velocity,
      seconds: clip.seconds,
      modelVersion: clip.modelVersion,
      sourceMotion: clip.sourceMotion,
      macros: clip.macros,
      alignment: clip.alignment
    }, null, 2));
    details.append(summary, pre);
    return details;
  }

  function createWaveform(metrics) {
    const data = metrics && (metrics.waveform || metrics.peaks);
    if (!Array.isArray(data) || data.length < 8) return null;
    const values = data.slice(0, 200).map(v => Math.abs(Number(Array.isArray(v) ? v[1] : v))).filter(Number.isFinite);
    if (values.length < 8) return null;
    const peak = Math.max(...values, 0.00001);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 500 34');
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('aria-hidden', 'true');
    svg.classList.add('clip-waveform');
    const path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', values.map((value, i) => {
      const x = ((i + .5) / values.length) * 500;
      const height = Math.max(.6, value / peak * 15);
      return `M${x.toFixed(2)} ${(17 - height).toFixed(2)}V${(17 + height).toFixed(2)}`;
    }).join(''));
    svg.append(path);
    return svg;
  }

  function createClip(clip, index, options = {}) {
    const article = node('article', 'clip-card');
    const safeId = `${options.prefix || ''}${String(clip.id || `clip-${index}`).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
    const titleId = `title-${safeId}-${players.length}`;
    article.setAttribute('aria-labelledby', titleId);

    const top = node('div', 'clip-topline');
    const voice = node('span', 'clip-voice');
    const voiceName = String(clip.voice || 'FACET');
    const mark = node('span', `voice-mark ${voiceName.toLowerCase().includes('shimmer') ? 'shimmer' : ''}`);
    mark.setAttribute('aria-hidden', 'true');
    voice.append(mark, document.createTextNode(voiceName));
    top.append(voice, node('span', 'clip-duration', secondsText(clip.seconds)));
    const title = node(options.headingTag || 'h4', 'clip-title', options.title || clip.title || prettyName(clip.id || `Reflection ${index + 1}`));
    title.id = titleId;
    article.append(top, title);
    if (options.sourceComparison) {
      const status = clip.directionStatus || clip.auditionStatus || (options.archive ? 'Previous study' : 'Reference');
      const badge = node('div', 'source-status', status);
      article.append(badge);
    }
    if (clip.description) article.append(node('p', 'clip-description', clip.description));

    const meta = node('div', 'clip-meta');
    if (!options.sourceComparison && sourceLabel(clip)) addMeta(meta, sourceLabel(clip));
    if (clip.midi !== undefined && clip.midi !== null) addMeta(meta, `${noteName(clip.midi) || 'Note'} · MIDI ${clip.midi}`);
    if (clip.velocity !== undefined && clip.velocity !== null) addMeta(meta, `Velocity ${displayNumber(clip.velocity)}`);
    if (clip.alignment !== undefined && clip.alignment !== null) {
      addMeta(meta, typeof clip.alignment === 'object' ? `Angle ${displayNumber(clip.alignment.angle ?? clip.alignment.phase ?? 0, 3)} turns` : `Angle ${displayNumber(clip.alignment, 3)} turns`);
    }
    if (meta.childElementCount) article.append(meta);

    const macros = clip.macros;
    if (macros && typeof macros === 'object' && !Array.isArray(macros)) {
      const list = node('dl', 'clip-params');
      for (const [name, value] of Object.entries(macros)) {
        if (value === null || value === undefined || typeof value === 'object') continue;
        const item = node('div', 'facet-param');
        item.append(node('dt', '', prettyName(name)), node('dd', '', typeof value === 'number' ? displayNumber(value, 3) : value));
        list.append(item);
      }
      if (list.childElementCount) article.append(list);
    }

    const waveform = createWaveform(clip.waveform ? { waveform: clip.waveform } : clip.metrics);
    if (waveform) article.append(waveform);
    const file = resolveFile(clip);
    const playerWrap = node('div', 'clip-player');
    const audio = node('audio');
    audio.controls = true;
    audio.preload = 'none';
    audio.setAttribute('aria-label', `Listen to ${clip.title || clip.id || voiceName}`);
    if (file) audio.src = file.path;
    else audio.hidden = true;
    const unavailable = node('p', 'clip-unavailable', 'This audio file is not available.');
    unavailable.hidden = !!file;
    playerWrap.append(audio, unavailable);

    const bottom = node('div', 'clip-bottom');
    const links = node('div', 'clip-file-links');
    const download = node('a', '', 'WAV');
    download.append(arrowIcon());
    download.download = '';
    if (file) download.href = file.path;
    else download.hidden = true;
    download.setAttribute('aria-label', `Download ${clip.title || voiceName} audio`);
    links.append(download);
    const recipeUrl = assetPath(clip.recipe);
    if (recipeUrl) {
      const recipeLink = node('a', '', 'Recipe');
      recipeLink.append(arrowIcon());
      recipeLink.href = recipeUrl;
      recipeLink.download = '';
      recipeLink.setAttribute('aria-label', `Download ${clip.title || voiceName} recipe`);
      links.append(recipeLink);
    } else if (clip.recipe && typeof clip.recipe === 'object') {
      const recipeLink = node('a', '', 'Recipe');
      recipeLink.append(arrowIcon());
      recipeLink.href = URL.createObjectURL(new Blob([JSON.stringify(clip.recipe, null, 2)], { type: 'application/json' }));
      recipeLink.download = `${safeId}-recipe.json`;
      recipeLink.setAttribute('aria-label', `Download ${clip.title || voiceName} recipe`);
      links.append(recipeLink);
    }
    const settingsUrl = assetPath(clip.diagnostics && clip.diagnostics.settings);
    if (settingsUrl) {
      const settingsLink = node('a', '', 'Settings');
      settingsLink.append(arrowIcon());
      settingsLink.href = settingsUrl;
      settingsLink.download = '';
      settingsLink.setAttribute('aria-label', `Download full render settings for ${clip.title || voiceName}`);
      links.append(settingsLink);
    }
    const variantNote = node('span', 'clip-quality-note');
    bottom.append(links, variantNote);
    article.append(playerWrap, bottom);
    const recipeDetails = createRecipe(clip);
    if (recipeDetails) article.append(recipeDetails);

    const record = { clip, audio, article, download, variantNote, file, unavailable, transition: 0, isArchive: !!options.archive };
    players.push(record);
    updateVariantNote(record);
    audio.addEventListener('play', () => {
      if (activePlayer && activePlayer !== audio) activePlayer.pause();
      activePlayer = audio;
      article.classList.add('is-playing');
      if (record.comparisonGroup) {
        record.comparisonGroup.last = record;
        record.comparisonGroup.records.forEach(item => item.switchButton.setAttribute('aria-pressed', String(item === record)));
      }
    });
    const stop = () => article.classList.remove('is-playing');
    audio.addEventListener('pause', stop);
    audio.addEventListener('ended', stop);
    audio.addEventListener('error', () => {
      unavailable.textContent = 'The audio could not be loaded. Try its download link or another playback level.';
      unavailable.hidden = false;
    });
    return article;
  }

  function switchSource(record, group) {
    const current = group.records.find(item => item.audio === activePlayer) || group.last;
    const time = current ? current.audio.currentTime || 0 : 0;
    const request = ++group.request;
    if (activePlayer && activePlayer !== record.audio) activePlayer.pause();
    const seek = () => {
      if (group.request !== request) return;
      const duration = record.audio.duration;
      record.audio.currentTime = Math.min(time, Number.isFinite(duration) ? Math.max(0, duration - .05) : time);
      group.status.textContent = `${sourceLabel(record.clip)} · ${displayNumber(record.audio.currentTime, 1)} sec`;
    };
    if (record.audio.readyState >= 1) seek();
    else record.audio.addEventListener('loadedmetadata', seek, { once: true });
    // Keep play() inside the click gesture; metadata can arrive after playback is requested.
    record.audio.play().catch(() => {
      if (group.request === request && record.audio === activePlayer) group.status.textContent = 'Use the audio play button to continue.';
    });
  }

  function createSourceComparisons(clips, options = {}) {
    const container = node('div', 'revision-groups');
    const jump = node('nav', 'revision-jump');
    jump.setAttribute('aria-label', 'Choose a voice and register to compare');
    container.append(jump);
    const groups = new Map();
    clips.forEach(clip => {
      const key = clip.comparison && typeof clip.comparison === 'object' && clip.comparison.group || `${clip.voice || 'FACET'}-${clip.midi}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(clip);
    });
    groups.forEach((groupClips, key) => {
      groupClips.sort((a, b) => (a.comparison && a.comparison.order || 0) - (b.comparison && b.comparison.order || 0));
      const groupElement = node('div', 'revision-group');
      const label = `${groupClips[0].voice || 'FACET'} · ${noteName(groupClips[0].midi) || groupClips[0].noteName || ''}`;
      groupElement.id = `source-${options.prefix || ''}${String(key).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
      const jumpLink = node('a', '', label);
      jumpLink.href = `#${groupElement.id}`;
      jump.append(jumpLink);
      const heading = node('div', 'revision-group-heading');
      heading.append(node('h4', '', label), node('span', '', options.archive ? 'Previous listening study' : 'Same voice · same note'));
      const tools = node('div', 'source-switch-tools');
      const buttons = node('div', 'source-switch-buttons');
      buttons.setAttribute('role', 'group');
      buttons.setAttribute('aria-label', `Switch source at the same position for ${label}`);
      const status = node('span', 'source-switch-status', 'Start any version; switch at the same position.');
      status.setAttribute('aria-live', 'polite');
      const grid = node('div', 'clip-grid revision-grid');
      const group = { key, records: [], last: null, status, request: 0 };
      groupClips.forEach((clip, index) => {
        grid.append(createClip(clip, index, { ...options, headingTag: 'h5', title: sourceLabel(clip), sourceComparison: true }));
        const record = players[players.length - 1];
        const button = node('button', '', sourceLabel(clip));
        button.type = 'button';
        button.setAttribute('aria-label', `Hear ${sourceLabel(clip)} at the same position for ${label}`);
        button.setAttribute('aria-pressed', 'false');
        button.addEventListener('click', () => switchSource(record, group));
        record.comparisonGroup = group;
        record.switchButton = button;
        group.records.push(record);
        buttons.append(button);
      });
      tools.append(node('span', 'source-switch-label', 'Switch source'), buttons, status);
      groupElement.append(heading, tools, grid);
      container.append(groupElement);
    });
    return container;
  }

  function updateVariantNote(record) {
    record.variantNote.textContent = record.file ? `${qualityLabels[record.file.key]}${record.file.key !== quality ? ' · fallback' : ''}` : 'Unavailable';
    record.variantNote.classList.toggle('fallback', !!record.file && record.file.key !== quality);
  }

  function switchQuality(next) {
    if (!qualityLabels[next] || quality === next) return;
    quality = next;
    document.querySelectorAll('[data-quality]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.quality === quality)));
    elements.qualityDescription.textContent = qualityDescriptions[quality];
    for (const record of players) {
      const nextFile = resolveFile(record.clip);
      const previousFile = record.file;
      record.file = nextFile;
      updateVariantNote(record);
      if (!nextFile || (previousFile && previousFile.path === nextFile.path)) continue;
      const time = record.audio.currentTime || 0;
      const wasPlaying = !record.audio.paused;
      const transition = ++record.transition;
      record.audio.pause();
      record.audio.src = nextFile.path;
      record.download.href = nextFile.path;
      record.download.hidden = false;
      record.unavailable.hidden = true;
      // Fetch the new variant only when this player was already in use.
      if (time > 0 || wasPlaying) {
        record.audio.addEventListener('loadedmetadata', () => {
          if (transition !== record.transition) return;
          record.audio.currentTime = Math.min(time, Number.isFinite(record.audio.duration) ? Math.max(0, record.audio.duration - .05) : time);
          if (wasPlaying) record.audio.play().catch(() => {});
        }, { once: true });
        record.audio.load();
      }
    }
  }

  function renderDownloads(downloads, target = document.getElementById('downloads')) {
    const entries = [
      ['First-listen pack', downloads && (downloads.zip || downloads.audio || downloads.allAudio)],
      ['Engine & render source', downloads && (downloads.source || downloads.sourceZip)],
      ['Listening report', downloads && (downloads.report || downloads.analysis)],
      ['R1 original measurements', downloads && (downloads.r1Measurements || downloads.originalMeasurements || downloads.r1Metadata)],
      ['R1 original metadata archive', downloads && (downloads.r1Archive || downloads.originalArchive)]
    ];
    const added = [];
    if (downloads && Array.isArray(downloads.archives)) {
      downloads.archives.forEach(archive => entries.push([archive.title || 'Audio archive', archive]));
    }
    for (const [label, value] of entries.reverse()) {
      const path = assetPath(value);
      if (!path || added.includes(path)) continue;
      const link = node('a', 'download-link', label);
      link.href = path;
      link.download = '';
      link.append(arrowIcon());
      target.prepend(link);
      added.push(path);
    }
  }

  function renderSections(data, target, nav, options = {}) {
    data.sections.forEach((section, i) => {
      const id = `comparison-${options.prefix || ''}${String(section.id || i + 1).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
      const number = String(i + 1).padStart(2, '0');
      const wrapper = node('section', 'audition-section');
      wrapper.id = id;
      const headingId = `${id}-title`;
      wrapper.setAttribute('aria-labelledby', headingId);
      const heading = node('div', 'comparison-heading');
      const titleWrap = node('div');
      const title = node('h3', '', section.title || `Comparison ${i + 1}`);
      title.id = headingId;
      titleWrap.append(title);
      if (section.description) titleWrap.append(node('p', 'comparison-description', section.description));
      const clips = Array.isArray(section.clips) ? section.clips : [];
      heading.append(node('span', 'comparison-number', number), titleWrap, node('span', 'comparison-count', `${clips.length} ${clips.length === 1 ? 'reflection' : 'reflections'}`));
      wrapper.append(heading);
      if (section.id === 'revision' || clips.some(clip => clip.comparison && typeof clip.comparison === 'object' && clip.comparison.group)) {
        wrapper.classList.add('source-study');
        wrapper.append(createSourceComparisons(clips, options));
      } else {
        const grid = node('div', `clip-grid${clips.length === 1 ? ' solo' : ''}`);
        clips.forEach((clip, index) => grid.append(createClip(clip, index, options)));
        wrapper.append(grid);
      }
      target.append(wrapper);
      const navLink = node('a');
      navLink.href = `#${id}`;
      navLink.append(node('span', 'nav-number', number), document.createTextNode(section.navTitle || section.title || `Comparison ${i + 1}`));
      nav.append(navLink);
    });
  }

  function preparePrevious(config) {
    const details = document.getElementById('previous-study');
    const content = document.getElementById('previous-content');
    if (!config) { details.hidden = true; return; }
    details.hidden = false;
    let loaded = false;
    let loading = false;
    const previousPath = assetPath(typeof config === 'string' ? config : config.manifest || config.url || config.path);
    const openLink = node('a', '', 'Earlier studies');
    openLink.href = '#previous-study';
    openLink.addEventListener('click', () => { details.open = true; });
    elements.nav.append(openLink);

    async function loadPrevious() {
      if (loaded || loading) return;
      loading = true;
      const status = node('p', 'archive-status', 'Loading the previous listening study…');
      status.setAttribute('role', 'status');
      content.replaceChildren(status);
      try {
        let previous = config;
        if (!previous || !Array.isArray(previous.sections)) {
          if (!previousPath) throw new Error('No previous manifest path was supplied.');
          const response = await fetch(previousPath, { cache: 'no-cache' });
          if (!response.ok) throw new Error(`Previous manifest returned ${response.status}`);
          previous = await response.json();
        }
        if (!Array.isArray(previous.sections) || !previous.sections.length) throw new Error('The previous listening study is empty.');
        const count = previous.sections.reduce((sum, section) => sum + (Array.isArray(section.clips) ? section.clips.length : 0), 0);
        document.getElementById('previous-title').textContent = `Previous study · R1/R2 · ${count} renders`;
        const provenance = node('div', 'previous-provenance');
        provenance.append(node('p', '', typeof config === 'object' && config.description || 'The earlier R1/R2 comparisons are preserved here for reference. Their files, settings, and measurements belong to that study.'));
        const revision = [previous.title || (typeof config === 'object' && config.title), previous.revision ? `Revision ${previous.revision}` : '', previous.generatedAt ? `Rendered ${previous.generatedAt}` : ''].filter(Boolean).join(' · ');
        if (revision) provenance.append(node('p', 'render-revision', revision));
        const nav = node('nav', 'section-nav archive-nav');
        nav.setAttribute('aria-label', 'Previous R1/R2 listening comparisons');
        const archiveSections = node('div', 'archive-sections');
        const archiveDownloads = node('div', 'downloads archive-downloads');
        renderDownloads(previous.downloads, archiveDownloads);
        if (previousPath) {
          const manifestLink = node('a', 'download-link', 'Previous study manifest');
          manifestLink.href = previousPath;
          manifestLink.download = '';
          manifestLink.append(arrowIcon());
          archiveDownloads.prepend(manifestLink);
        }
        renderSections(previous, archiveSections, nav, { prefix: 'previous-', archive: true });
        content.replaceChildren(provenance, nav, archiveSections, archiveDownloads);
        loaded = true;
      } catch (error) {
        const retry = node('button', 'retry-button', 'Try loading the previous study again');
        retry.type = 'button';
        retry.addEventListener('click', loadPrevious);
        content.replaceChildren(node('p', 'archive-status', 'The previous study could not be loaded. The new comparisons are still available above.'), retry);
        console.error('FACET previous study:', error);
      } finally { loading = false; }
    }

    details.addEventListener('toggle', () => {
      if (details.open) loadPrevious();
      else players.filter(record => record.isArchive).forEach(record => record.audio.pause());
    });
  }

  function render(data) {
    if (!data || !Array.isArray(data.sections) || !data.sections.length) throw new Error('The audition manifest has no listening sections.');
    elements.sections.replaceChildren();
    elements.nav.replaceChildren();
    players.length = 0;
    renderSections(data, elements.sections, elements.nav);
    preparePrevious(data.previousStudy);
    elements.status.hidden = true;
    elements.nav.hidden = false;
    elements.footer.hidden = false;
    renderDownloads(data.downloads);
    const count = players.length;
    const duration = players.reduce((total, { clip }) => total + (Number(clip.seconds) || 0), 0);
    const summary = `${count} finite renders across ${data.sections.length} listening comparisons${duration ? ` · ${secondsText(duration)} of material` : ''}.`;
    document.getElementById('render-summary').textContent = typeof data.summary === 'string' ? data.summary : summary;
    const revision = [data.revision ? `Revision ${data.revision}` : '', data.generatedAt ? `Rendered ${data.generatedAt}` : ''].filter(Boolean).join(' · ');
    document.getElementById('render-revision').textContent = revision;
    observeSections();
  }

  function observeSections() {
    if (observer) observer.disconnect();
    if (!('IntersectionObserver' in window)) return;
    observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
      if (!visible) return;
      elements.nav.querySelectorAll('a').forEach(link => {
        if (link.hash === `#${visible.target.id}`) link.setAttribute('aria-current', 'true');
        else link.removeAttribute('aria-current');
      });
    }, { rootMargin: '-10% 0px -65% 0px', threshold: 0 });
    elements.sections.querySelectorAll('section').forEach(section => observer.observe(section));
  }

  async function load() {
    elements.status.hidden = false;
    elements.status.classList.remove('error');
    elements.status.replaceChildren(node('span', 'loading-mark', '✧'), node('p', '', 'Preparing the listening room…'));
    try {
      const response = await fetch('manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest returned ${response.status}`);
      manifest = await response.json();
      render(manifest);
    } catch (error) {
      elements.status.classList.add('error');
      const retry = node('button', 'retry-button', 'Try again');
      retry.type = 'button';
      retry.addEventListener('click', load);
      elements.status.replaceChildren(node('span', 'loading-mark', '✧'), node('p', '', 'The listening room could not be loaded. Please try again.'), retry);
      console.error('FACET audition:', error);
    }
  }

  document.querySelectorAll('[data-quality]').forEach(button => button.addEventListener('click', () => switchQuality(button.dataset.quality)));
  const toolbar = document.querySelector('.listening-toolbar');
  const measureToolbar = () => document.getElementById('listening-room').style.setProperty('--toolbar-height', `${toolbar.getBoundingClientRect().height}px`);
  measureToolbar();
  if ('ResizeObserver' in window) new ResizeObserver(measureToolbar).observe(toolbar);
  else window.addEventListener('resize', measureToolbar);
  load();
})();
