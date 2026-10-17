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

  function createClip(clip, index) {
    const article = node('article', 'clip-card');
    const safeId = String(clip.id || `clip-${index}`).replace(/[^a-zA-Z0-9_-]/g, '-');
    const titleId = `title-${safeId}-${players.length}`;
    article.setAttribute('aria-labelledby', titleId);

    const top = node('div', 'clip-topline');
    const voice = node('span', 'clip-voice');
    const voiceName = String(clip.voice || 'FACET');
    const mark = node('span', `voice-mark ${voiceName.toLowerCase().includes('shimmer') ? 'shimmer' : ''}`);
    mark.setAttribute('aria-hidden', 'true');
    voice.append(mark, document.createTextNode(voiceName));
    top.append(voice, node('span', 'clip-duration', secondsText(clip.seconds)));
    const title = node('h4', 'clip-title', clip.title || prettyName(clip.id || `Reflection ${index + 1}`));
    title.id = titleId;
    article.append(top, title);
    if (clip.description) article.append(node('p', 'clip-description', clip.description));

    const meta = node('div', 'clip-meta');
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

    const record = { clip, audio, article, download, variantNote, file, unavailable, transition: 0 };
    players.push(record);
    updateVariantNote(record);
    audio.addEventListener('play', () => {
      if (activePlayer && activePlayer !== audio) activePlayer.pause();
      activePlayer = audio;
      article.classList.add('is-playing');
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

  function renderDownloads(downloads) {
    const target = document.getElementById('downloads');
    const entries = [
      ['First-listen pack', downloads && (downloads.zip || downloads.audio || downloads.allAudio)],
      ['Engine & render source', downloads && (downloads.source || downloads.sourceZip)],
      ['Listening report', downloads && (downloads.report || downloads.analysis)]
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

  function render(data) {
    if (!data || !Array.isArray(data.sections) || !data.sections.length) throw new Error('The audition manifest has no listening sections.');
    elements.sections.replaceChildren();
    elements.nav.replaceChildren();
    players.length = 0;
    data.sections.forEach((section, i) => {
      const id = `comparison-${String(section.id || i + 1).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
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
      const grid = node('div', `clip-grid${clips.length === 1 ? ' solo' : ''}`);
      clips.forEach((clip, index) => grid.append(createClip(clip, index)));
      wrapper.append(heading, grid);
      elements.sections.append(wrapper);
      const navLink = node('a');
      navLink.href = `#${id}`;
      navLink.append(node('span', 'nav-number', number), document.createTextNode(section.navTitle || section.title || `Comparison ${i + 1}`));
      elements.nav.append(navLink);
    });
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
