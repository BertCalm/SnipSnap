'use strict';

(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const audio = $('#audition-audio');
  const state = { manifest: null, clips: [], hero: null, current: null, mode: 'common', filter: 'all', loadAttempt: 0, playbackRequest: 0, metadataResume: null };
  const sectionInfo = {
    focus: { title: 'The accepted movement', label: 'Reference', description: 'The original plain-versus-moving comparison, preserved from R4.' },
    trace: { title: 'Choose the movement speed', label: 'TRACE', description: 'Native saved patches: reference speed, slower, and quicker.' },
    instrument: { title: 'Playable voices', label: 'Voices', description: 'Native saved presets on the same short phrase.' },
    hold: { title: 'Keep the passage open', label: 'HOLD', description: 'Four consecutive loop cycles, with no crossfade or makeup gain.' },
    source: { title: 'The accepted source choices', label: 'Sources', description: 'The original short source phrases from the accepted R2 audition.' },
    comparison: { title: 'Hear the passage change', label: 'A/B passage', description: 'A and B repeat the same phrase. Change only the gate chain.' },
    reference: { title: 'The returning tone', label: 'Passage', description: 'Crossed endpoints. More gates. The same original source.' },
    color: { title: 'Different harmonics survive', label: 'Color', description: 'Directional relationships change across the spectrum.' },
    rejection: { title: 'The discarded outline', label: 'Rejection', description: 'Listen to where the sound leaves the chain, and what it excites.' },
    musical: { title: 'Put it into a phrase', label: 'Music', description: 'The mechanism, heard as a musical instrument.' },
    range: { title: 'Across the keyboard', label: 'Range', description: 'The same source at different pitches and velocities.' },
    gesture: { title: 'The complete gesture', label: 'Gesture', description: 'Follow the original tone as the passage closes and rebuilds.' },
  };
  const sectionOrder = ['focus', 'trace', 'instrument', 'hold', 'source', 'comparison', 'reference', 'color', 'rejection', 'musical', 'range', 'gesture'];
  const playIcon = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M6 3.5v13l10-6.5z" fill="currentColor"/></svg>';
  const pauseIcon = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 4h3v12H5zM12 4h3v12h-3z" fill="currentColor"/></svg>';
  const linkIcon = '<svg width="9" height="9" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 10 10 2M2 2h8v8" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>';
  const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const time = value => {
    const seconds = Math.max(0, Number.isFinite(+value) ? +value : 0);
    return `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
  };
  const numeric = (value, digits = 3) => Number.isFinite(+value) ? (+value).toFixed(digits) : '—';
  const db = value => Number.isFinite(+value) && +value > 0 ? `${(20 * Math.log10(+value)).toFixed(1)} dBFS` : '−∞ dBFS';
  const cueLabel = value => {
    const text = String(value || '');
    if (/end source|release/i.test(text)) return 'Release';
    if (/sixteen|16 gates/i.test(text)) return '16 gates';
    if (/eight|8 gates/i.test(text)) return '8 gates';
    if (/four|4 gates/i.test(text)) return '4 gates';
    if (/three gates|3 gates|tone returns/i.test(text)) return '3 gates';
    if (/insert/i.test(text)) return 'Insert';
    if (/disappear|silence/i.test(text)) return 'Silence';
    if (/crosses|crossing/i.test(text)) return 'Crossing';
    if (/aligned/i.test(text)) return 'Aligned';
    return text;
  };
  const label = value => String(value).replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').replace(/^./, char => char.toUpperCase());
  function safeUrl(value) {
    if (typeof value !== 'string' || !value.trim() || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(value) || value.startsWith('/')) return null;
    const clean = value.split('/').filter(part => part !== '.');
    if (clean.includes('..')) return null;
    return './' + clean.map(part => encodeURIComponent(decodeSafe(part))).join('/');
  }
  function decodeSafe(value) { try { return decodeURIComponent(value); } catch { return value; } }
  function clipSection(clip) {
    const raw = String(clip.section || 'reference').toLowerCase();
    if (raw === 'focus') return 'focus';
    if (raw === 'trace') return 'trace';
    if (/instrument|native|preset/.test(raw)) return 'instrument';
    if (/hold|loop/.test(raw)) return 'hold';
    if (/source/.test(raw)) return 'source';
    if (/compar|a\/b|pair/.test(raw)) return 'comparison';
    if (/twist|reject|halo|outline/.test(raw)) return 'rejection';
    if (/color|colour|spectr/.test(raw)) return 'color';
    if (/music|phrase|melod/.test(raw)) return 'musical';
    if (/range|pitch|veloc|keyboard/.test(raw)) return 'range';
    if (/gest|start|hero/.test(raw)) return 'gesture';
    if (/ref|stage|gate|pass|restor/.test(raw)) return 'reference';
    return raw;
  }
  function announce(message) { $('#announcement').textContent = message; }
  function effectiveMode(clip, hero = false) { return !hero && !['focus', 'gesture'].includes(clipSection(clip)) && state.mode === 'matched' && safeUrl(clip.matchedFile) ? 'matched' : 'common'; }
  function clipUrl(clip, mode) { return safeUrl(mode === 'matched' ? clip.matchedFile : clip.file); }
  function recipeUrl(clip, mode) { return mode === 'matched' && safeUrl(clip.matchedRecipeFile) ? safeUrl(clip.matchedRecipeFile) : safeUrl(clip.recipeFile); }
  function cardModeText(clip) { return `${effectiveMode(clip) === 'matched' ? 'RMS-MATCHED' : 'COMMON GAIN'}${clipCues(clip).length > 1 ? ' · A/B' : ''}`; }
  function isFeatured(clip) {
    if (state.clips.some(item => clipSection(item) === 'trace')) return clipSection(clip) === 'trace';
    const native = state.clips.some(item => clipSection(item) === 'instrument');
    return native ? ['instrument', 'hold'].includes(clipSection(clip)) : clipSection(clip) === 'source';
  }

  function clipCues(clip) {
    return Array.isArray(clip.cues) ? clip.cues.filter(cue => Number.isFinite(+cue.time) && +cue.time >= 0) : [];
  }

  function renderCardCues(clip) {
    const cues = clipCues(clip);
    if (!cues.length) return '';
    return `<div class="clip-cues" role="group" aria-label="Cued steps for ${escape(clip.title)}">${cues.map((cue, index) => {
      const authored = String(cue.label || `Step ${index + 1}`);
      const prefix = authored.match(/^([A-Z])\s*[·:–—-]\s*/);
      const badge = prefix ? prefix[1] : String(index + 1);
      const description = prefix ? authored.slice(prefix[0].length) : authored;
      return `<button class="clip-cue" type="button" data-clip-cue="${escape(clip.id)}" data-cue-time="${+cue.time}" data-cue-index="${index}" aria-label="Play ${escape(authored)} in ${escape(clip.title)} at ${time(cue.time)}"><span class="cue-badge">${escape(badge)}</span><span class="cue-label">${escape(description)}</span><span class="cue-time">${time(cue.time)}</span></button>`;
    }).join('')}</div>`;
  }

  function waveform(clip, hero = false) {
    const values = Array.isArray(clip.waveform) ? clip.waveform.filter(value => Number.isFinite(+value)).map(value => Math.min(1, Math.abs(+value))) : [];
    const count = hero ? 160 : 75;
    const actual = values.length ? Array.from({ length: count }, (_, index) => values[Math.min(values.length - 1, Math.floor(index * values.length / count))]) : Array(count).fill(0.025);
    const bars = actual.map((value, index) => {
      const height = Math.max(1.3, value * 70);
      return `<rect x="${index * 4}" y="${(80 - height) / 2}" width="2" height="${height}" rx=".8"/>`;
    }).join('');
    const svg = `<svg viewBox="0 0 ${count * 4} 80" preserveAspectRatio="none" aria-hidden="true" fill="currentColor">${bars}</svg>`;
    return `<div class="waveform" data-wave="${escape(clip.id)}" data-context="${hero ? 'hero' : 'card'}">${svg}<div class="wave-progress">${svg}</div><span class="wave-cursor"></span><input type="range" min="0" max="1000" value="0" step="1" aria-label="Seek ${escape(clip.title)}" aria-valuetext="0:00" data-seek="${escape(clip.id)}" data-hero="${hero}"></div>`;
  }

  function renderHero(clip) {
    if (!clip) {
      $('#hero-player').innerHTML = '<p class="error-inline">The opening demonstration is not included in this audition.</p>';
      return;
    }
    const focus = clipSection(clip) === 'focus';
    const manifest = state.manifest || {};
    document.body.classList.toggle('focus-audition', focus);
    $('#hero-listen-title').textContent = manifest.heroTitle || (focus ? 'Plain pluck → ringing trace' : 'Hear it disappear. Then return.');
    $('#hero-listen-eyebrow').textContent = manifest.heroEyebrow || (focus ? 'START HERE · THE SAME THREE NOTES' : 'THE SHORT PROOF · ONE CONTINUOUS TAKE');
    $('#hero-listen-guide').textContent = manifest.heroDescription || (focus ? 'A plays the plain phrase. B moves the gates during each note. Listen for the body to thin and a ringing tail to remain.' : 'Follow the original tone as the passage closes, then intermediate gates restore it.');
    $('#hero-footnote-text').textContent = manifest.heroFootnote || (focus ? 'The notes and source level stay the same. The gates and rejected-signal mix move.' : 'The returning tone comes from the original source. No makeup gain.');
    const cues = clipCues(clip);
    const file = clipUrl(clip, 'common');
    const recipe = recipeUrl(clip, 'common');
    const patch = safeUrl(clip.patchFile);
    const downloads = `<div class="hero-downloads clip-links">${patch ? `<a href="${escape(patch)}" download data-hero-patch-link aria-label="Download saved TRACE patch">Patch</a>` : ''}${recipe ? `<a href="${escape(recipe)}" download aria-label="Download common-gain recipe for ${escape(clip.title)}">Recipe</a>` : ''}${file ? `<a href="${escape(file)}" download aria-label="Download WAV for ${escape(clip.title)}">WAV ${linkIcon}</a>` : ''}</div>`;
    $('#hero-player').innerHTML = `<div class="hero-controls"><button class="play-button hero-play" data-play="${escape(clip.id)}" data-hero="true" type="button" aria-label="Play ${escape(clip.title)}">${playIcon}</button>${waveform(clip, true)}<span class="hero-time" data-elapsed="${escape(clip.id)}">${time(clip.duration)}</span></div>${cues.length ? `<div class="hero-cues${focus ? ' focus-cues' : ''}" style="grid-template-columns:repeat(${cues.length},minmax(0,1fr))">${cues.map((cue, index) => `<button class="hero-cue${index === 0 ? ' active' : ''}" type="button" data-cue="${Number(cue.time) || 0}" title="${escape(cue.label)}" aria-label="Play ${escape(cue.label)} at ${time(cue.time)}">${escape(cueLabel(cue.label))}<span>${time(cue.time)}</span></button>`).join('')}</div>` : ''}${downloads}`;
  }

  function renderCard(clip) {
    const tags = Array.isArray(clip.tags) ? clip.tags : [];
    const tagline = tags.slice(0, 2).join(' / ') || sectionInfo[clipSection(clip)]?.label || 'STUDY';
    const file = clipUrl(clip, effectiveMode(clip));
    const recipe = recipeUrl(clip, effectiveMode(clip));
    const patchFile = safeUrl(clip.patchFile);
    const patchLink = patchFile ? `<a href="${escape(patchFile)}" download data-patch-link="${escape(clip.id)}" aria-label="Download native SnipSnap patch for ${escape(clip.title)}">Patch</a>` : '';
    const metrics = clip.metrics || {};
    const facts = [];
    const sourceProfile = clip.settings?.sourceProfile || clip.settings?.settings?.sourceProfile;
    if (typeof sourceProfile === 'string') facts.push(['Source', label(sourceProfile.toLowerCase())]);
    if (Number.isFinite(+metrics.peak)) facts.push(['Sample peak', db(metrics.peak)]);
    if (Number.isFinite(+metrics.rms)) facts.push(['RMS', db(metrics.rms)]);
    if (Number.isFinite(+metrics.dc)) facts.push(['DC mean', (+metrics.dc).toExponential(2)]);
    if (Number.isFinite(+metrics.pitchHz) && +metrics.pitchHz > 0) facts.push(['Detected pitch', `${(+metrics.pitchHz).toFixed(2)} Hz`]);
    if (Number.isFinite(+metrics.expectedHz) && +metrics.expectedHz > 0) facts.push(['Requested pitch', `${(+metrics.expectedHz).toFixed(2)} Hz`]);
    if (Number.isFinite(+metrics.renderMs)) facts.push(['Render time', `${Math.round(+metrics.renderMs)} ms`]);
    if (Number.isFinite(+metrics.seamError)) facts.push(['Loop seam error', (+metrics.seamError).toExponential(2)]);
    if (Number.isFinite(+metrics.fittedHz)) facts.push(['Loop pitch', `${(+metrics.fittedHz).toFixed(3)} Hz`]);
    const details = facts.length ? `<details class="clip-metrics"><summary>Render measurements <span aria-hidden="true">+</span></summary><dl>${facts.map(([key,value]) => `<div><dt>${escape(key)}</dt><dd>${escape(value)}</dd></div>`).join('')}</dl><p>Measurements describe the common-gain WAV.</p></details>` : '';
    return `<article class="clip-card ${escape(clipSection(clip))}" data-card="${escape(clip.id)}"><div class="clip-topline"><span class="clip-tag">${escape(tagline)}</span><span class="clip-duration">${time(clip.duration)}</span></div><h4>${escape(clip.title)}</h4><p class="clip-description">${escape(clip.description || '')}</p><div class="clip-player"><button class="play-button" type="button" data-play="${escape(clip.id)}" aria-label="Play ${escape(clip.title)}">${playIcon}</button>${waveform(clip)}</div>${renderCardCues(clip)}<div class="clip-footer"><span class="clip-level${effectiveMode(clip) === 'matched' ? ' matched' : ''}" data-mode-label="${escape(clip.id)}">${cardModeText(clip)}</span><div class="clip-links">${patchLink}${recipe ? `<a href="${escape(recipe)}" download data-recipe-link="${escape(clip.id)}" aria-label="Download ${effectiveMode(clip) === 'matched' && clip.matchedRecipeFile ? 'RMS-matched ' : 'common-gain '}recipe for ${escape(clip.title)}">Recipe</a>` : ''}${file ? `<a href="${escape(file)}" download data-wav-link="${escape(clip.id)}" aria-label="Download WAV for ${escape(clip.title)}">WAV <span aria-hidden="true">${linkIcon}</span></a>` : ''}</div></div>${details}</article>`;
  }

  function renderSources() {
    const clips = state.clips.filter(isFeatured).sort((a, b) => sectionOrder.indexOf(clipSection(a)) - sectionOrder.indexOf(clipSection(b)));
    const native = clips.some(clip => clipSection(clip) === 'instrument');
    const trace = clips.some(clip => clipSection(clip) === 'trace');
    $('#source-listen').hidden = !clips.length;
    $('#source-listen-title').textContent = trace ? 'One motion. Three speeds.' : native ? 'Short notes. Held tones.' : 'Find a source you like.';
    $('#source-listen-intro').innerHTML = trace ? 'Keep the reference.<br>Then try slower or quicker.' : native ? 'Three playable voices.<br>Then four unmodified loop cycles.' : 'Short phrases, before<br>the listening comparisons.';
    $('#source-footnote').textContent = trace ? 'Choose LATTICE → TRACE in SnipSnap. SPEED .5 keeps B; lower values slow the movement and higher values speed it up. HOLD extends a finite note. Each Patch download saves these controls.' : native ? 'These three source voices keep their original fixed gate settings. Each HOLD recording repeats its loop four times. Their patches and the earlier listening studies remain available below.' : 'Each source uses the same phrase. Compare the source character, then try the listening studies.';
    $('#source-clips').innerHTML = clips.map(renderCard).join('');
    const firstTarget = state.hero ? '#passage-proof' : clips.length ? '#source-listen' : '#listen';
    $('.skip-link').href = firstTarget;
    $('.skip-link').textContent = state.hero ? 'Skip to the opening comparison' : 'Skip to listening studies';
    $('#first-listen-link').href = firstTarget;
    $('#first-listen-link').innerHTML = `${state.hero ? clipSection(state.hero) === 'focus' ? 'Play the short comparison' : 'Play the passage proof' : clips.length ? 'Listen to the sources' : 'Explore the listening studies'} <span aria-hidden="true">↓</span>`;
  }

  function renderFilters() {
    const categories = [...new Set(state.clips.filter(clip => clip !== state.hero).map(clipSection))];
    categories.sort((a, b) => (sectionOrder.includes(a) ? sectionOrder.indexOf(a) : 99) - (sectionOrder.includes(b) ? sectionOrder.indexOf(b) : 99));
    $('#filters').innerHTML = '<button class="active" type="button" data-filter="all" aria-pressed="true">All studies</button>' + categories.map(key => `<button type="button" data-filter="${escape(key)}" aria-pressed="false">${escape(sectionInfo[key]?.label || label(key))}</button>`).join('');
  }

  function renderSections() {
    const clips = state.clips.filter(clip => clip !== state.hero && !isFeatured(clip) && (state.filter === 'all' || clipSection(clip) === state.filter));
    const groups = new Map();
    clips.forEach(clip => { const key = clipSection(clip); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(clip); });
    const keys = [...groups.keys()].sort((a, b) => (sectionOrder.includes(a) ? sectionOrder.indexOf(a) : 99) - (sectionOrder.includes(b) ? sectionOrder.indexOf(b) : 99));
    $('#clip-sections').innerHTML = keys.length ? keys.map((key, index) => {
      const info = sectionInfo[key] || { title: label(key), description: '' };
      const numberedOrder = sectionOrder.filter(item => item !== 'source');
      const number = String(numberedOrder.includes(key) ? numberedOrder.indexOf(key) + 1 : index + 1).padStart(2, '0');
      return `<section class="study-section" aria-labelledby="section-${escape(key)}"><div class="study-section-heading"><div class="study-title-group"><span class="section-number">${number}</span><h3 id="section-${escape(key)}">${escape(info.title)}</h3></div><p class="section-description">${escape(info.description)}</p></div><div class="clip-grid">${groups.get(key).map(renderCard).join('')}</div></section>`;
    }).join('') : state.clips.some(clip => isFeatured(clip) && clipSection(clip) === state.filter) ? '<p class="empty-state"><a href="#source-listen">Listen to these examples above ↑</a></p>' : '<p class="empty-state">No studies in this selection.</p>';
    $('#clip-sections').setAttribute('aria-busy', 'false');
    updatePlaybackUI();
  }

  function formatFact(value) {
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    if (typeof value === 'number') return Number.isInteger(value) ? value.toLocaleString('en-US') : Math.abs(value) < .001 && value !== 0 ? value.toExponential(2) : value.toPrecision(5).replace(/\.?0+$/, '');
    if (Array.isArray(value)) return value.map(formatFact).join(' · ');
    if (value && typeof value === 'object') return Object.entries(value).slice(0, 4).map(([key, val]) => `${label(key)}: ${formatFact(val)}`).join(' · ');
    return String(value ?? '—');
  }

  function renderEvidence(manifest) {
    const rows = Array.isArray(manifest.referenceChecks) ? manifest.referenceChecks : [];
    $('#reference-checks').innerHTML = rows.length ? rows.map(row => `<tr><td>${escape(row.gates)}</td><td>${numeric(row.amplitude, 6)}</td><td>${numeric(row.expected, 6)}</td><td>${Number.isFinite(+row.error) ? (+row.error).toExponential(2) : '—'}</td></tr>`).join('') : '<tr><td colspan="4">Reference measurements are available in the downloadable report.</td></tr>';
    const validation = manifest.validation && typeof manifest.validation === 'object' ? Object.entries(manifest.validation) : [];
    $('#validation-facts').innerHTML = validation.map(([key, value]) => `<div class="validation-item"><span class="small-label">${escape(label(key))}</span><span>${escape(formatFact(value))}</span></div>`).join('');
    const engine = manifest.engine || {};
    const rate = +engine.sampleRate || 44100;
    const facts = [ ['OUTPUT', `${numeric(rate / 1000, 1)} kHz · mono`], ['INTERNAL RATE', `${engine.oversample || 4}× oversampled`], ['EXTERNAL EFFECTS', 'None'], ['MODEL', String(engine.model ?? 'Finite prototype')] ];
    if (engine.sourceCommit) facts.push(['SOURCE BASE', String(engine.sourceCommit).slice(0, 10)]);
    $('#engine-facts').innerHTML = facts.map(([key, value]) => `<div><dt>${escape(key)}</dt><dd>${escape(value)}</dd></div>`).join('');
    const downloads = manifest.downloads || {};
    const links = [['traceKit', 'TRACE · 16 finite MPC pads', '↓'], ['tracePatches', 'TRACE · 3 saved speed patches', '↓'], ['traceNote', 'Ringing Trace · C3 note, PCM24', '↓'], ['kit', 'Original 16-pad MPC kit', '↓'], ['patches', 'Original 12 static patches', '↓'], ['zip', 'Download the audition pack', '↓'], ['measurements', 'Measurements JSON', '↗'], ['source', 'Engine source', '↗'], ['motionReference', 'Accepted R4 motion audition', '↓'], ['previous', 'Previous R3 instrument audition', '↓'], ['accepted', 'Accepted R2 audition archive', '↓'], ['original', 'Original R1 audition archive', '↓']].filter(([key]) => safeUrl(downloads[key]));
    $('#download-links').innerHTML = links.length ? links.map(([key, title, arrow]) => `<a href="${escape(safeUrl(downloads[key]))}" download>${title}<span aria-hidden="true">${arrow === '↗' ? linkIcon : arrow}</span></a>`).join('') : '<span class="muted">Use the WAV and recipe links on each study.</span>';
    const date = new Date(manifest.generatedAt);
    $('#footer-build').textContent = `Lattice instrument study${manifest.revision ? ` · R${manifest.revision}` : ''}${!Number.isNaN(date.getTime()) ? ' · ' + date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }) : ''}`;
  }

  function renderRevision(manifest) {
    const revision = Number(manifest.revision) || 1;
    document.title = `${manifest.title || 'Lattice'} — SnipSnap listening study`;
    $('#revision-kicker').textContent = revision > 1 ? `LISTENING REVISION ${String(revision).padStart(2, '0')}` : 'FIRST LISTEN';
    $('#revision-title').textContent = manifest.revisionTitle || (revision > 1 ? 'Softer source.\nClearer comparisons.' : 'A tone, lost.\nA passage, rebuilt.');
    $('#revision-summary').textContent = manifest.revisionSummary || manifest.subtitle || (revision > 1 ? 'Start with short musical sources. Then compare the same phrase through different gates, with cue buttons to move between A and B.' : 'Cross two gates and the tone disappears. Add smaller turns between them, and the same sound finds a way through.');
    $('#revision-note').hidden = !manifest.revisionNote;
    $('#revision-note').textContent = manifest.revisionNote || '';
  }

  async function loadManifest() {
    const attempt = ++state.loadAttempt;
    $('#page-error').hidden = true;
    $('#clip-sections').setAttribute('aria-busy', 'true');
    try {
      const response = await fetch('./manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error(`Manifest request returned ${response.status}`);
      const manifest = await response.json();
      if (!Array.isArray(manifest.clips) || !manifest.clips.length) throw new Error('The manifest has no audio clips.');
      if (attempt !== state.loadAttempt) return;
      state.manifest = manifest;
      state.clips = manifest.clips.map((clip, index) => ({ ...clip, id: String(clip.id || `clip-${index + 1}`), title: String(clip.title || `Study ${index + 1}`) })).filter(clip => safeUrl(clip.file));
      if (!state.clips.length) throw new Error('The manifest has no usable audio paths.');
      const matchedAvailable = state.clips.some(clip => safeUrl(clip.matchedFile));
      state.mode = manifest.preferredComparisonMode === 'matched' && matchedAvailable ? 'matched' : 'common';
      state.hero = state.clips.find(clip => clip.id === manifest.heroClipId) || state.clips.find(clip => clipSection(clip) === 'focus') || state.clips.find(clip => clipSection(clip) === 'gesture') || state.clips.find(clip => (clip.tags || []).some(tag => /hero|gesture/i.test(tag))) || null;
      renderRevision(manifest);
      renderHero(state.hero);
      renderSources();
      renderFilters();
      renderSections();
      renderEvidence(manifest);
      syncModeControls();
      $('[data-mode="matched"]').disabled = !matchedAvailable;
      $('[data-mode="matched"]').title = matchedAvailable ? 'RMS-matched copies compare timbre. Each A/B condition is scaled separately.' : 'This audition contains common-gain recordings only.';
      announce(`Loaded ${state.clips.length} Lattice listening studies.`);
    } catch (error) {
      if (attempt !== state.loadAttempt) return;
      $('#clip-sections').innerHTML = '';
      $('#clip-sections').setAttribute('aria-busy', 'false');
      $('#page-error').hidden = false;
      $('#hero-player').innerHTML = '<p class="error-inline">The audition is temporarily unavailable. Try reloading below.</p>';
      announce('The audition could not be loaded.');
      console.error('Lattice audition:', error);
    }
  }

  function findClip(id) { return state.clips.find(clip => clip.id === id); }

  function clearPendingSeek() {
    if (state.metadataResume) audio.removeEventListener('canplay', state.metadataResume);
    state.metadataResume = null;
  }

  async function startClip(clip, { hero = false, seek = null, force = false } = {}) {
    const mode = effectiveMode(clip, hero);
    const url = clipUrl(clip, mode);
    if (!url) return;
    const same = state.current?.clip.id === clip.id && state.current?.mode === mode && state.current?.hero === hero;
    if (same && seek === null && !force) {
      if (audio.paused) { try { await audio.play(); } catch (error) { playbackError(error); } }
      else audio.pause();
      updatePlaybackUI();
      return;
    }
    const request = ++state.playbackRequest;
    clearPendingSeek();
    audio.pause();
    state.current = { clip, mode, hero };
    const resume = () => {
      if (request !== state.playbackRequest) return;
      if (seek !== null) audio.currentTime = Math.max(0, Math.min(Number.isFinite(audio.duration) ? audio.duration : +clip.duration || 0, seek));
      updateProgress();
    };
    if (!same) audio.src = url;
    if (seek !== null) {
      if (audio.readyState >= 3 && audio.seekable.length > 0) resume();
      else {
        state.metadataResume = () => { state.metadataResume = null; resume(); };
        audio.addEventListener('canplay', state.metadataResume, { once: true });
      }
    }
    $('#transport').hidden = false;
    document.body.classList.add('has-transport');
    $('#transport-title').textContent = clip.title;
    $('#transport-mode').textContent = mode === 'matched' ? 'RMS-MATCHED · A/B COMPARISON' : 'COMMON GAIN';
    updatePlaybackUI();
    try {
      await audio.play();
      if (request === state.playbackRequest) announce(`Playing ${clip.title}${mode === 'matched' ? ', RMS-matched' : ', common gain'}.`);
    } catch (error) { if (request === state.playbackRequest) playbackError(error); }
  }

  function playbackError(error) {
    if (error?.name === 'AbortError') return;
    announce('This recording could not be played. Try its WAV download link.');
    $('#transport-mode').textContent = 'PLAYBACK UNAVAILABLE · TRY WAV DOWNLOAD';
    updatePlaybackUI();
  }

  function updatePlaybackUI() {
    const current = state.current;
    const playing = !!current && !audio.paused && !audio.ended;
    $$('[data-play]').forEach(button => {
      const isHero = button.dataset.hero === 'true';
      const active = current && button.dataset.play === current.clip.id && current.mode === effectiveMode(current.clip, isHero) && current.hero === isHero;
      const clip = findClip(button.dataset.play);
      button.innerHTML = active && playing ? pauseIcon : playIcon;
      button.classList.toggle('playing', !!active && playing);
      button.setAttribute('aria-label', `${active && playing ? 'Pause' : 'Play'} ${clip?.title || 'recording'}`);
    });
    $$('[data-card]').forEach(card => card.classList.toggle('is-current', !!current && card.dataset.card === current.clip.id && !current.hero));
    if (!current) $$('[data-clip-cue]').forEach(button => { button.classList.remove('active'); button.removeAttribute('aria-current'); });
    $('#transport-toggle').innerHTML = playing ? pauseIcon : playIcon;
    $('#transport-toggle').setAttribute('aria-label', playing ? 'Pause playback' : 'Resume playback');
    updateProgress();
  }

  function updateProgress() {
    if (!state.current) return;
    const { clip, hero, mode } = state.current;
    const duration = Number.isFinite(audio.duration) ? audio.duration : +clip.duration || 0;
    const progress = duration > 0 ? Math.max(0, Math.min(1, audio.currentTime / duration)) : 0;
    $$('[data-wave]').forEach(wave => {
      const isHero = wave.dataset.context === 'hero';
      const active = wave.dataset.wave === clip.id && isHero === hero && effectiveMode(clip, isHero) === mode;
      const value = active ? progress : 0;
      wave.classList.toggle('active', active);
      $('.wave-progress', wave).style.clipPath = `inset(0 ${100 - value * 100}% 0 0)`;
      $('.wave-cursor', wave).style.left = `${value * 100}%`;
      const input = $('input', wave);
      if (document.activeElement !== input) input.value = Math.round(value * 1000);
      input.setAttribute('aria-valuetext', `${time(active ? audio.currentTime : 0)} of ${time(active ? duration : findClip(wave.dataset.wave)?.duration)}`);
    });
    $('#transport-time').textContent = `${time(audio.currentTime)} / ${time(duration)}`;
    $('#transport-progress').style.width = `${progress * 100}%`;
    const heroTime = $(`[data-elapsed="${CSS.escape(state.hero?.id || '')}"]`);
    if (heroTime) heroTime.textContent = hero ? time(audio.currentTime) : time(state.hero?.duration);
    if (state.hero?.cues?.length) {
      let cueIndex = 0;
      if (hero) state.hero.cues.forEach((cue, index) => { if (audio.currentTime >= +cue.time) cueIndex = index; });
      $$('.hero-cue').forEach((button, index) => button.classList.toggle('active', index === cueIndex));
    }
    let currentCue = -1;
    if (!hero) clipCues(clip).forEach((cue, index) => { if (audio.currentTime >= +cue.time) currentCue = index; });
    $$('[data-clip-cue]').forEach(button => {
      const active = !hero && button.dataset.clipCue === clip.id && +button.dataset.cueIndex === currentCue;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
  }

  function syncModeControls() {
    $$('[data-mode]').forEach(button => { button.classList.toggle('active', button.dataset.mode === state.mode); button.setAttribute('aria-pressed', String(button.dataset.mode === state.mode)); });
    $('#level-explanation').textContent = state.mode === 'matched' ? 'RMS-matched comparisons scale each A/B condition separately to compare timbre. The opening comparison and restoration proof retain common gain, as do sources and unpaired recordings.' : 'Common-gain comparisons preserve the level lost through the chain. The opening comparison and restoration proof always use common gain.';
    $$('[data-mode-label]').forEach(element => { const clip = findClip(element.dataset.modeLabel); if (clip) { element.textContent = cardModeText(clip); element.classList.toggle('matched', effectiveMode(clip) === 'matched'); } });
    $$('[data-wav-link]').forEach(element => { const clip = findClip(element.dataset.wavLink); if (clip) element.href = clipUrl(clip, effectiveMode(clip)); });
    $$('[data-recipe-link]').forEach(element => {
      const clip = findClip(element.dataset.recipeLink);
      if (!clip) return;
      element.href = recipeUrl(clip, effectiveMode(clip));
      element.setAttribute('aria-label', `Download ${effectiveMode(clip) === 'matched' && clip.matchedRecipeFile ? 'RMS-matched' : 'common-gain'} recipe for ${clip.title}`);
    });
  }

  async function changeMode(mode) {
    state.mode = mode;
    syncModeControls();
    if (state.current && !state.current.hero && state.current.mode !== effectiveMode(state.current.clip)) {
      const wasPlaying = !audio.paused;
      const seek = audio.currentTime;
      const clip = state.current.clip;
      const playback = startClip(clip, { seek, force: true });
      const request = state.playbackRequest;
      await playback;
      if (!wasPlaying && request === state.playbackRequest) audio.pause();
    }
    updatePlaybackUI();
    announce(mode === 'matched' ? 'RMS-matched comparison mode selected. The opening comparison and restoration proof remain at common gain.' : 'Common-gain playback selected.');
  }

  function drawGates(n) {
    const svg = $('#gate-diagram');
    const theta = Math.PI / 2;
    const amplitude = Math.pow(Math.cos(theta / (n - 1)), n - 1);
    const gateCount = n;
    let vector = [1, 0];
    const positions = Array.from({ length: gateCount }, (_, index) => 73 + index * 374 / (gateCount - 1));
    const gateHeight = n > 4 ? 57 : 82;
    let content = '<title id="diagram-title">A chain of ' + n + ' projection gates</title><desc id="diagram-description">Crossed endpoints with ' + (n - 2) + ' intermediate gates. Calculated aligned-source amplitude transmission: ' + Math.round(amplitude * 100) + ' percent.</desc><defs><linearGradient id="beam-gradient" x1="0" x2="1"><stop stop-color="#dfb175" stop-opacity=".55"/><stop offset="1" stop-color="#dfb175" stop-opacity=".16"/></linearGradient></defs>';
    content += '<path d="M24 142H493" stroke="#464a3b" stroke-width="1" stroke-dasharray="2 6"/><path d="M24 142H493" stroke="url(#beam-gradient)" stroke-width="16" stroke-linecap="round"/><text x="23" y="181" fill="#777c6b" font-family="monospace" font-size="8" letter-spacing="1">SOURCE</text>';
    for (let index = 0; index < gateCount; index++) {
      const angle = theta * index / (gateCount - 1);
      const pass = vector[0] * Math.cos(angle) + vector[1] * Math.sin(angle);
      const reject = -vector[0] * Math.sin(angle) + vector[1] * Math.cos(angle);
      vector = [pass * Math.cos(angle), pass * Math.sin(angle)];
      const x = positions[index];
      const angleDegrees = index * 90 / (gateCount - 1);
      content += `<g transform="translate(${x} 142)"><ellipse rx="${n > 4 ? 8 : 18}" ry="${gateHeight / 2 + 13}" fill="#161812" stroke="#63684e" stroke-width=".8"/><path d="M0 ${-gateHeight / 2}V${gateHeight / 2}" stroke="#dfb175" stroke-width="${n > 4 ? 1.5 : 2.4}" transform="rotate(${-angleDegrees})" stroke-linecap="round"/>`;
      if (Math.abs(reject) > .01 && n < 8) content += `<path d="M0 ${gateHeight / 2 + 14}L16 ${gateHeight / 2 + 48}" stroke="#a9a0ce" stroke-width="1" opacity="${Math.min(.8, Math.abs(reject))}"/><circle cx="16" cy="${gateHeight / 2 + 48}" r="2" fill="#a9a0ce" opacity="${Math.min(.8, Math.abs(reject))}"/>`;
      if (n <= 4) content += `<text x="0" y="${-gateHeight / 2 - 25}" text-anchor="middle" fill="#a9ad9c" font-family="monospace" font-size="9">${Math.round(angleDegrees)}°</text>`;
      content += '</g>';
    }
    content += `<path d="M${positions[gateCount - 1] + (n > 4 ? 8 : 18)} 142H494" stroke="#efc38d" stroke-width="${Math.max(.5, amplitude * 9)}" stroke-linecap="round" opacity="${Math.max(.05, amplitude)}"/>`;
    svg.innerHTML = content;
    $('#diagram-value').innerHTML = `${Math.round(amplitude * 100)}<span>%</span>`;
    $$('[data-gates]').forEach(button => { const selected = +button.dataset.gates === n; button.classList.toggle('selected', selected); button.setAttribute('aria-pressed', String(selected)); });
  }

  document.addEventListener('click', event => {
    const playButton = event.target.closest('[data-play]');
    if (playButton) { const clip = findClip(playButton.dataset.play); if (clip) startClip(clip, { hero: playButton.dataset.hero === 'true' }); return; }
    const cue = event.target.closest('[data-cue]');
    if (cue && state.hero) { startClip(state.hero, { hero: true, seek: +cue.dataset.cue, force: true }); return; }
    const clipCue = event.target.closest('[data-clip-cue]');
    if (clipCue) { const clip = findClip(clipCue.dataset.clipCue); if (clip) startClip(clip, { seek: +clipCue.dataset.cueTime, force: true }); return; }
    const filter = event.target.closest('[data-filter]');
    if (filter) { state.filter = filter.dataset.filter; $$('[data-filter]').forEach(button => { const active = button === filter; button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active)); }); renderSections(); if (state.clips.some(clip => isFeatured(clip) && clipSection(clip) === state.filter)) $('#source-listen').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }); announce(`${filter.textContent} selected.`); return; }
    const mode = event.target.closest('[data-mode]');
    if (mode) { changeMode(mode.dataset.mode); return; }
    const gate = event.target.closest('[data-gates]');
    if (gate) drawGates(+gate.dataset.gates);
  });
  document.addEventListener('change', event => {
    if (!event.target.matches('[data-seek]')) return;
    const clip = findClip(event.target.dataset.seek);
    if (!clip) return;
    const seek = (+event.target.value / 1000) * (+clip.duration || 0);
    const hero = event.target.dataset.hero === 'true';
    const same = state.current?.clip.id === clip.id && state.current?.hero === hero && state.current?.mode === effectiveMode(clip, hero);
    if (same && audio.readyState >= 3 && audio.seekable.length > 0) { audio.currentTime = seek; updateProgress(); }
    else startClip(clip, { hero, seek, force: true });
  });
  $('#transport-toggle').addEventListener('click', () => { if (state.current) startClip(state.current.clip, { hero: state.current.hero }); });
  $('#transport-close').addEventListener('click', () => { ++state.playbackRequest; clearPendingSeek(); audio.pause(); audio.currentTime = 0; state.current = null; $('#transport').hidden = true; document.body.classList.remove('has-transport'); updatePlaybackUI(); $$('[data-wave]').forEach(wave => { wave.classList.remove('active'); $('.wave-progress', wave).style.clipPath = 'inset(0 100% 0 0)'; $('input', wave).value = 0; }); if (state.hero) $('[data-elapsed]').textContent = time(state.hero.duration); announce('Playback stopped.'); });
  $('#retry-button').addEventListener('click', loadManifest);
  ['play', 'pause', 'ended', 'loadedmetadata'].forEach(name => audio.addEventListener(name, updatePlaybackUI));
  audio.addEventListener('timeupdate', updateProgress);
  audio.addEventListener('error', () => { if (state.current) playbackError(audio.error); });
  audio.volume = .85;
  drawGates(4);
  loadManifest();
})();
