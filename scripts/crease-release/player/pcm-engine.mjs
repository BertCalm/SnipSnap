// Research sample transport. No Crease equations run while a note is playing.
export const VOICE_GAIN = .12; // Eight overlapping cached voices retain bus headroom.

export function validateManifest(m) {
  const integer = (v, lo, hi) => Number.isSafeInteger(v) && v >= lo && v <= hi;
  if (!m || m.format !== 'crease-playable-pcm-v1' || m.rate !== 44100 || m.midi !== 60 ||
      !integer(m.heldFrames, 2, 44100 * 64) || !integer(m.loopStart, 0, m.heldFrames - 2) ||
      !integer(m.loopFrames, 2, m.heldFrames) || m.loopStart + m.loopFrames !== m.heldFrames ||
      !integer(m.rootCycles, 8, m.loopFrames) || !integer(m.leadFrames, 4, 512) ||
      !integer(m.tailFrames, 44100, 60000) || m.releaseSeconds !== 1.2 ||
      !integer(m.releaseFrames, 52900, 53000) || m.crossfadeFrames !== 221 || m.maxVoices !== 8 ||
      !Array.isArray(m.tailCuts) || !m.tailCuts.length || m.tailCuts[0] !== 0 ||
      !m.tailCuts.includes(m.loopStart) || m.tailCuts.some((x, i) => !integer(x, 0, m.heldFrames - 1) || (i && x <= m.tailCuts[i - 1]))) {
    throw new Error('This release cache has an unsupported or incomplete layout.');
  }
  const frames = m.heldFrames + m.tailCuts.length * m.tailFrames;
  const c = m.container;
  if (frames * 2 > 32 * 1024 ** 2 || !c || c.pcmBytes !== frames * 2 || c.compression !== 'gzip' ||
      c.bytePlanes !== 2 || !integer(c.deltaOrder, 0, 2) || !integer(c.compressedBytes, 1, 12 * 1024 ** 2) ||
      !/^[\w.-]+\.cpcb$/.test(c.file) || !/^[a-f0-9]{64}$/.test(c.pcmSha256) || !/^[a-f0-9]{64}$/.test(c.compressedSha256)) {
    throw new Error('This release cache exceeds its limits or has invalid integrity data.');
  }
  return frames;
}

export function neighbours(position, m) {
  const p = position < m.loopStart ? position : m.loopStart + (position - m.loopStart) % m.loopFrames;
  const grid = m.tailCuts;
  let lo = 0, hi = grid.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (grid[mid] <= p) lo = mid + 1; else hi = mid; }
  const left = Math.max(0, lo - 1), right = left + 1 < grid.length ? left + 1 : grid.indexOf(m.loopStart);
  const coordinate = left + 1 < grid.length ? grid[right] : m.heldFrames;
  return { left, right, weight: (p - grid[left]) / Math.max(1, coordinate - grid[left]) };
}

export class ReleaseReader {
  constructor(manifest, pcm) {
    const frames = validateManifest(manifest);
    if (!(pcm instanceof Int16Array) || pcm.length !== frames) throw new Error('Release PCM dimensions differ.');
    this.meta = manifest; this.pcm = pcm; this.voices = []; this.lastPeak = 0;
  }
  sample(offset, length, pos, loop = -1) {
    if (loop >= 0 && pos >= length) pos = loop + (pos - loop) % (length - loop);
    const i = Math.floor(pos), t = pos - i;
    const read = (k) => {
      if (loop >= 0 && k >= length) k = loop + (k - loop) % (length - loop);
      return this.pcm[offset + Math.max(0, Math.min(length - 1, k))] / 32767;
    };
    const t2 = t * t, t3 = t2 * t;
    return .5 * (2 * t2 - t3 - t) * read(i - 1) + (1 - 2.5 * t2 + 1.5 * t3) * read(i) +
      (.5 * t + 2 * t2 - 1.5 * t3) * read(i + 1) + .5 * (t3 - t2) * read(i + 2);
  }
  held(pos) { return this.sample(0, this.meta.heldFrames, pos, this.meta.loopStart); }
  tail(index, pos) {
    return this.sample(this.meta.heldFrames + index * this.meta.tailFrames, this.meta.tailFrames, pos);
  }
  start(id, mode = 'unfold', velocity = 1) {
    if (!Number.isSafeInteger(id) || !['unfold', 'fade'].includes(mode) || !Number.isFinite(velocity)) return false;
    // One-key audition: a new press replaces the preceding gesture through a short fade.
    this.stop();
    if (this.voices.length >= this.meta.maxVoices) this.voices.shift();
    this.voices.push({ id, mode, gain: Math.max(0, Math.min(1, velocity)) * VOICE_GAIN,
      pos: 0, releasing: false, level: 1, age: 0, forced: false });
    return true;
  }
  release(id) {
    const m = this.meta, period = m.loopFrames / m.rootCycles;
    for (const v of this.voices) if (v.id === id && !v.releasing) {
      v.releasing = true; v.cut = v.pos; v.age = 0;
      const n = neighbours(v.cut, m);
      Object.assign(v, n);
      const shift = (key) => ((v.cut - key + period / 2) % period + period) % period - period / 2;
      v.shiftLeft = shift(m.tailCuts[v.left]); v.shiftRight = shift(m.tailCuts[v.right]);
    }
  }
  stop() { for (const v of this.voices) if (!v.forced) { v.forced = true; v.stopLevel = v.level; } }
  process(out, rate = 44100) {
    if (!Number.isFinite(rate) || rate <= 0) throw new Error('Invalid output rate.');
    const m = this.meta, step = m.rate / rate;
    const decrement = Math.fround(1 / Math.fround(Math.fround(m.releaseSeconds) * rate));
    out.fill(0); this.lastPeak = 0;
    for (const v of this.voices) {
      for (let frame = 0; frame < out.length && v.level > 0; frame++) {
        let x = this.held(v.pos);
        if (v.releasing) {
          if (v.mode === 'unfold') {
            const a = this.tail(v.left, m.leadFrames + v.shiftLeft + v.age);
            const b = this.tail(v.right, m.leadFrames + v.shiftRight + v.age);
            const q = Math.min(1, v.age / m.crossfadeFrames), blend = q * q * (3 - 2 * q);
            x += ((1 - v.weight) * a + v.weight * b - x) * blend;
          }
          v.age += step;
        }
        if (v.forced) v.level = Math.max(0, v.level - v.stopLevel / (.005 * rate));
        else if (v.releasing) v.level = Math.max(0, Math.fround(v.level - decrement));
        out[frame] += x * v.gain * v.level;
        v.pos += step;
      }
    }
    this.voices = this.voices.filter((v) => v.level > 0);
    for (const x of out) this.lastPeak = Math.max(this.lastPeak, Math.abs(x));
    return this.voices.length;
  }
}
