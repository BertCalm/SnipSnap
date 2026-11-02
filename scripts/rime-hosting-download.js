'use strict';
// Hosted previews use lossless FLAC; the player's download remains PCM24 WAV.
// Offline decoding at 44.1 kHz avoids a device-rate resample before re-encoding.
document.addEventListener('click', async event => {
  const link = event.target.closest?.('#current-download');
  if (!link || !new URL(link.href, location.href).pathname.endsWith('.flac')) return;
  event.preventDefault();
  if (link.dataset.preparing === 'yes') return;
  link.dataset.preparing = 'yes';
  link.setAttribute('aria-busy', 'true');
  const source = link.href;
  try {
    const response = await fetch(source);
    if (!response.ok) throw new Error('Preview download failed');
    const Context = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    const context = new Context(1, 1, 44100);
    const audio = await context.decodeAudioData(await response.arrayBuffer());
    if (audio.sampleRate !== 44100 || audio.numberOfChannels !== 1) throw new Error('Unexpected preview format');
    const pcm = audio.getChannelData(0);
    const dataBytes = pcm.length * 3;
    const pad = dataBytes % 2;
    const bytes = new ArrayBuffer(44 + dataBytes + pad);
    const out = new DataView(bytes);
    const tag = (offset, text) => [...text].forEach((character, i) => out.setUint8(offset + i, character.charCodeAt(0)));
    tag(0, 'RIFF'); out.setUint32(4, 36 + dataBytes + pad, true); tag(8, 'WAVE');
    tag(12, 'fmt '); out.setUint32(16, 16, true); out.setUint16(20, 1, true); out.setUint16(22, 1, true);
    out.setUint32(24, 44100, true); out.setUint32(28, 44100 * 3, true); out.setUint16(32, 3, true); out.setUint16(34, 24, true);
    tag(36, 'data'); out.setUint32(40, dataBytes, true);
    for (let i = 0; i < pcm.length; i++) {
      const value = Math.max(-8388608, Math.min(8388607, Math.round(pcm[i] * 8388608)));
      out.setUint8(44 + i * 3, value & 255);
      out.setUint8(45 + i * 3, (value >> 8) & 255);
      out.setUint8(46 + i * 3, (value >> 16) & 255);
    }
    const url = URL.createObjectURL(new Blob([bytes], {type: 'audio/wav'}));
    const download = document.createElement('a');
    download.href = url;
    download.download = decodeURIComponent(new URL(source).pathname.split('/').pop()).replace(/\.flac$/, '.wav');
    download.click();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  } catch (error) {
    const status = document.getElementById('playback-error');
    if (status) status.textContent = 'The WAV download could not be prepared. Try the instrument ZIP.';
  } finally {
    delete link.dataset.preparing;
    link.removeAttribute('aria-busy');
  }
}, true);
