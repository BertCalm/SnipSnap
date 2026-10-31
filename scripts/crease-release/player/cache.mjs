import { validateManifest } from './pcm-engine.mjs';

export async function digest(bytes) {
  const hash = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function decodeCache(m, compressed) {
  const frames = validateManifest(m), c = m.container;
  if (compressed.byteLength !== c.compressedBytes || await digest(compressed) !== c.compressedSha256) {
    throw new Error('The release download is incomplete or changed. Reload to try again.');
  }
  if (typeof DecompressionStream !== 'function') throw new Error('This browser cannot open the release cache. Use the fixed recordings below.');
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip'));
  // A bounded stream reader prevents a corrupt compressed file from allocating without limit.
  const reader = stream.getReader(), bytes = new Uint8Array(c.pcmBytes);
  let count = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    if (count + value.length > bytes.length) { await reader.cancel(); throw new Error('Release cache expanded beyond its declared size.'); }
    bytes.set(value, count); count += value.length;
  }
  if (count !== c.pcmBytes) throw new Error('The release cache has missing samples.');
  const pcm = new Int16Array(frames);
  for (let i = 0; i < frames; i++) pcm[i] = bytes[i] | (bytes[i + frames] << 8);
  for (let order = 0; order < c.deltaOrder; order++) {
    let sum = 0;
    for (let i = 0; i < pcm.length; i++) { sum = (sum + pcm[i]) << 16 >> 16; pcm[i] = sum; }
  }
  if (await digest(pcm.buffer) !== c.pcmSha256) throw new Error('Release PCM failed its integrity check.');
  return pcm;
}
