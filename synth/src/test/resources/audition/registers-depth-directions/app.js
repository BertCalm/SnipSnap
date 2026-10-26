'use strict';

(() => {
  const audio = document.getElementById('audio');
  const pitch = document.getElementById('note');
  const status = document.getElementById('status');
  const buttons = [...document.querySelectorAll('[data-direction]')];
  const names = {baseline: 'Current', body: 'Warmer body', ensemble: 'Richer ensemble'};
  let manifest;
  let direction = 'baseline';
  let playToken = 0;

  const asset = (path) => {
    const url = new URL(path, new URL('manifest.json', document.baseURI));
    if (manifest.audioHash) url.searchParams.set('audioHash', manifest.audioHash);
    return url.href;
  };

  async function select(next, play = false) {
    const token = ++playToken;
    direction = next;
    const clip = manifest.clips.find((entry) => entry.direction === direction && entry.note === Number(pitch.value));
    if (!clip) { status.textContent = 'This comparison is unavailable. Try another pitch.'; return; }
    audio.pause();
    audio.src = asset(clip.matched);
    document.getElementById('selected-title').textContent = `${names[direction]} · C${Number(pitch.value) / 12 - 1}`;
    buttons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.direction === direction)));
    for (const [id, path] of [['wav', clip.matched], ['recipe', clip.recipeUrl]]) {
      const link = document.getElementById(id);
      link.href = asset(path);
      link.hidden = false;
    }
    status.textContent = play ? 'Loading audio…' : 'Ready. Choose a direction to play.';
    if (play) {
      try { await audio.play(); }
      catch (error) { if (token === playToken) status.textContent = 'Press play in the audio player to start.'; }
    }
  }

  buttons.forEach((button) => button.addEventListener('click', () => select(button.dataset.direction, true)));
  pitch.addEventListener('change', () => select(direction));
  audio.addEventListener('playing', () => { status.textContent = 'Playing · matched level'; });
  audio.addEventListener('ended', () => { status.textContent = 'Finished. Choose another direction to compare.'; });
  audio.addEventListener('error', () => { status.textContent = 'The audio could not load. Reload the page to try again.'; });

  fetch('manifest.json', {cache: 'no-cache'}).then((response) => {
    if (!response.ok) throw new Error('manifest');
    return response.json();
  }).then((data) => {
    manifest = data;
    if (!Array.isArray(manifest.clips) || manifest.clips.length !== 9) throw new Error('clips');
    buttons.forEach((button) => { button.disabled = false; });
    select('baseline');
  }).catch(() => { status.textContent = 'The comparison could not open. Please reload to try again.'; });
})();
