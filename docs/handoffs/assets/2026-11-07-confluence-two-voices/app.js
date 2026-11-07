/* Six real audio files. Browser playback only; no decoding, processing or simulated playback. */
(() => {
  'use strict';
  const IDS = ['a-alone', 'b-alone', 'a-plus-a', 'a-plus-b', 'synthetic-b-alone', 'a-plus-synthetic-b'];
  const LABELS = {
    'a-alone': ['Recorded A', 'recorded'],
    'b-alone': ['Recorded B', 'recorded'],
    'a-plus-a': ['Same-take doubling control', 'control'],
    'a-plus-b': ['Recorded two-take reference', 'recorded'],
    'synthetic-b-alone': ['DSP prototype · derived from A', 'prototype'],
    'a-plus-synthetic-b': ['DSP prototype · A with transformed A', 'prototype']
  };
  const players = [];
  const $ = (id) => document.getElementById(id);
  let loading = false;

  function el(tag, className, value) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = String(value);
    return node;
  }

  function safeUrl(value) {
    if (typeof value !== 'string' || !value.trim()) return null;
    try {
      const url = new URL(value, document.baseURI);
      return ['https:', 'http:'].includes(url.protocol) ? url : null;
    } catch (_) { return null; }
  }

  function localAudio(value) {
    const url = safeUrl(value);
    const base = new URL('.', document.baseURI);
    return url && url.origin === base.origin && url.pathname.startsWith(base.pathname) ? url : null;
  }

  function addLink(parent, title, path, download = false) {
    const url = safeUrl(path);
    if (!url) return;
    const link = el('a', '', title);
    link.href = url.href;
    if (download) link.download = '';
    else { link.target = '_blank'; link.rel = 'noopener'; }
    parent.append(link);
  }

  function lines(value) {
    if (typeof value === 'string') return value.trim() ? [value] : [];
    return Array.isArray(value) ? value.filter((item) => typeof item === 'string' && item.trim()) : [];
  }

  function check(value) {
    if (!value || !Array.isArray(value.clips) || value.clips.length !== IDS.length) throw new Error('Six audition clips are required.');
    const conditions = value.conditions;
    if (!conditions || conditions.mono !== true || conditions.dry !== true || conditions.matched !== true ||
      conditions.channels !== 1 || !Number.isFinite(conditions.seconds) || conditions.seconds <= 0 ||
      !Number.isFinite(conditions.sampleRate) || conditions.sampleRate <= 0) throw new Error('The audition conditions are incomplete.');
    const clips = IDS.map((id) => {
      const matches = value.clips.filter((clip) => clip?.id === id);
      const clip = matches[0];
      if (matches.length !== 1 || !localAudio(clip.path) || typeof clip.title !== 'string' ||
        typeof clip.description !== 'string' || !/^[a-f0-9]{64}$/i.test(clip.sha256 || '') ||
        !Number.isFinite(clip.seconds) || Math.abs(clip.seconds - conditions.seconds) > 1 / conditions.sampleRate ||
        !lines(clip.processing).length) throw new Error('An audition clip is incomplete.');
      return clip;
    });
    return { ...value, clips };
  }

  function pauseAll() { for (const audio of players) audio.pause(); }

  function renderClips(manifest) {
    const fragment = document.createDocumentFragment();
    const processing = document.createDocumentFragment();
    for (const [index, clip] of manifest.clips.entries()) {
      const row = el('article', 'clip-row'); row.dataset.clipId = clip.id;
      const titleId = `clip-title-${clip.id}`;
      row.setAttribute('aria-labelledby', titleId);
      row.append(el('span', 'clip-number', String(index + 1).padStart(2, '0')));
      const copy = el('div', 'clip-copy');
      const [kind, style] = LABELS[clip.id];
      const title = el('h2', 'clip-title', clip.title); title.id = titleId;
      copy.append(el('p', `clip-kind ${style}`, kind), title, el('p', 'clip-description', clip.description));
      const audio = el('audio', 'clip-audio');
      const url = localAudio(clip.path); url.searchParams.set('v', clip.sha256);
      audio.controls = true; audio.preload = 'metadata'; audio.src = url.href;
      audio.defaultPlaybackRate = 1; audio.playbackRate = 1; audio.loop = false;
      audio.setAttribute('controlsList', 'noplaybackrate');
      audio.setAttribute('aria-label', `Listen to ${clip.title}`);
      const error = el('p', 'clip-error'); error.hidden = true; error.setAttribute('role', 'status');
      const fail = (message) => { audio.pause(); row.classList.remove('is-playing'); error.textContent = message; error.hidden = false; };
      audio.addEventListener('play', () => {
        if (audio.paused) return;
        for (const other of players) if (other !== audio) other.pause();
        audio.playbackRate = 1; audio.loop = false; row.classList.add('is-playing');
      });
      audio.addEventListener('pause', () => row.classList.remove('is-playing'));
      audio.addEventListener('ended', () => row.classList.remove('is-playing'));
      audio.addEventListener('ratechange', () => { if (audio.playbackRate !== 1) audio.playbackRate = 1; });
      audio.addEventListener('loadedmetadata', () => {
        if (!Number.isFinite(audio.duration) || Math.abs(audio.duration - clip.seconds) > .05) {
          audio.controls = false;
          fail('This file does not match the audition window. Reload the page before comparing.');
        }
      });
      audio.addEventListener('error', () => fail('This recording could not load. Reload the page to try again.'));
      players.push(audio); row.append(copy, audio, error); fragment.append(row);
      const detail = el('div', 'processing-credit'); detail.append(el('h3', '', clip.title));
      const list = el('ul'); for (const line of lines(clip.processing)) list.append(el('li', '', line));
      detail.append(list); processing.append(detail);
    }
    $('clip-list').replaceChildren(fragment);
    $('processing-list').replaceChildren(processing);
  }

  function renderCredits(manifest) {
    const fragment = document.createDocumentFragment();
    const sources = Array.isArray(manifest.sources) ? manifest.sources : manifest.sources && typeof manifest.sources === 'object'
      ? Object.entries(manifest.sources).map(([id, source]) => ({ id, ...source })) : [];
    for (const [index, source] of sources.entries()) {
      const block = el('div', 'source-credit');
      block.append(el('h3', '', `Recording ${index === 0 ? 'A' : index === 1 ? 'B' : source.id || index + 1}`));
      const creditLines = [source.performerCredit, source.performer, source.performerCreditNote, source.credit, source.attribution,
        source.description, source.cropBasis, source.modifications, source.boundaryNote];
      for (const line of [...new Set(creditLines.flatMap(lines))]) block.append(el('p', '', line));
      const links = el('div', 'source-links');
      addLink(links, 'Original source →', source.sourceUrl || source.url);
      addLink(links, source.licenseId || source.license || 'Licence →', source.licenseUrl);
      block.append(links); fragment.append(block);
    }
    for (const credit of lines(manifest.credits)) fragment.append(el('p', 'small-copy', credit));
    if (!fragment.childNodes.length) fragment.append(el('p', 'small-copy', 'Full source-file attribution and licensing are included in the audition manifest.'));
    $('source-credits').replaceChildren(fragment);
    $('matching-details').textContent = typeof manifest.conditions.matching === 'string' ? manifest.conditions.matching : '';
    const reports = $('report-links'); reports.replaceChildren(); addLink(reports, 'Audition manifest ↓', 'manifest.json', true);
    const references = [manifest.sourceReferences, manifest.reports, manifest.reportLinks].flatMap((value) => {
      if (Array.isArray(value)) return value;
      if (value && typeof value === 'object') return Object.entries(value).map(([title, path]) => typeof path === 'string' ? { title, path } : { title, ...path });
      return [];
    });
    for (const reference of references) {
      if (typeof reference === 'string') addLink(reports, reference.split('/').pop(), reference);
      else if (reference) addLink(reports, reference.title || reference.label || 'Source details →', reference.path || reference.url || reference.href);
    }
  }

  async function load() {
    if (loading) return;
    loading = true; pauseAll(); players.length = 0;
    $('clip-list').replaceChildren();
    const status = $('load-status'); status.hidden = false; status.classList.remove('is-error'); status.textContent = 'Loading the audition…';
    try {
      const response = await fetch('manifest.json', { cache: 'no-cache' });
      if (!response.ok) throw new Error('The audition manifest could not load.');
      const manifest = check(await response.json());
      renderClips(manifest); renderCredits(manifest);
      const seconds = Number(manifest.conditions.seconds.toFixed(2));
      $('conditions').textContent = `Same ${seconds}-second window · Mono · Dry · Level matched`;
      status.hidden = true;
    } catch (error) {
      pauseAll(); players.length = 0; $('clip-list').replaceChildren();
      $('conditions').textContent = 'Audio conditions have not loaded.';
      status.classList.add('is-error'); status.textContent = 'The audition could not load. ';
      const retry = el('button', '', 'Try again'); retry.type = 'button'; retry.addEventListener('click', load); status.append(retry);
      console.error('Two-singer audition:', error);
    } finally { loading = false; }
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) pauseAll(); });
  window.addEventListener('pagehide', pauseAll);
  load();
})();
