import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { decodeCache, digest } from './player/cache.mjs';
import { ReleaseReader } from './player/pcm-engine.mjs';
globalThis.crypto ??= webcrypto;

const out = process.argv[2];
const m = JSON.parse(fs.readFileSync(path.join(out, 'manifest.json')));
const packed = fs.readFileSync(path.join(out, m.container.file));
const decodedAt = performance.now();
const pcm = await decodeCache(m, packed);
const decodeMilliseconds = performance.now() - decodedAt;
const fixture = JSON.parse(fs.readFileSync(path.join(out, 'raw/reader-fixtures.json')));
const bytes = fs.readFileSync(path.join(out, 'raw/reader-fixtures.f32'));
assert.equal(await digest(bytes), fixture.sha256);
const expected = new Float32Array(bytes.buffer, bytes.byteOffset, bytes.length / 4);
const block = new Float32Array(128);
let maxPortError = 0;
for (let row = 0; row < fixture.cuts.length; row++) {
  const reader = new ReleaseReader(m, pcm); reader.start(1);
  reader.voices[0].pos = fixture.cuts[row]; reader.release(1);
  for (let at = 0; at < fixture.frames; at += 128) {
    const part = block.subarray(0, Math.min(128, fixture.frames - at));
    reader.process(part);
    for (let i = 0; i < part.length; i++) maxPortError = Math.max(maxPortError, Math.abs(part[i] - expected[row * fixture.frames + at + i]));
  }
  assert.equal(reader.voices.length, 0, `stuck release at ${fixture.cuts[row]}`);
}
assert(maxPortError < 1e-7, `JS/Python transport differs: ${maxPortError}`);

// Algebraic invariants catch coefficient errors independently of the Python port.
const r = new ReleaseReader(m, pcm), saved = pcm.slice(8, 17);
for (const fraction of [.1, .25, .5, .9]) {
  pcm.fill(8192, 8, 17);
  assert(Math.abs(r.held(11 + fraction) - 8192 / 32767) < 1e-12, 'fractional constant gain');
  for (let i = 8; i < 17; i++) pcm[i] = i * 100;
  assert(Math.abs(r.held(11 + fraction) - (11 + fraction) * 100 / 32767) < 1e-12, 'linear interpolation');
}
pcm.set(saved, 8);

const zero = new ReleaseReader(m, pcm); zero.start(1, 'unfold', 0); zero.process(block); zero.release(1);
for (let i = 0; i < 420; i++) { zero.process(block); assert(block.every((x) => x === 0), 'zero live gain'); }
assert.equal(zero.voices.length, 0);
const rapid = new ReleaseReader(m, pcm);
for (let id = 0; id < 100; id++) { rapid.start(id); rapid.process(block); rapid.release(id); rapid.process(block); }
rapid.stop(); for (let i = 0; i < 3; i++) rapid.process(block);
assert.equal(rapid.voices.length, 0, 'short cleanup fade');

const full = new ReleaseReader(m, pcm);
for (let id = 0; id < 8; id++) full.start(id);
for (const voice of full.voices) voice.forced = false;
let peak = 0;
const begin = performance.now();
for (let i = 0; i < 345; i++) { full.process(block); peak = Math.max(peak, full.lastPeak); assert(block.every(Number.isFinite)); }
const millisecondsPerAudioSecond = performance.now() - begin;
assert(peak < 1, 'eight-voice bus clips');
assert(full.voices.every((voice) => voice.pos > 44100));
const at48k = new ReleaseReader(m, pcm); at48k.start(1);
at48k.process(new Float32Array(48000), 48000);
assert(Math.abs(at48k.voices[0].pos - 44100) < 1e-5, '48k source clock');
at48k.release(1); at48k.process(new Float32Array(58000), 48000);
assert.equal(at48k.voices.length, 0, '48k release ends');

let corruptRejected = false;
const corrupt = Buffer.from(packed); corrupt[corrupt.length >> 1] ^= 1;
try { await decodeCache(m, corrupt); } catch { corruptRejected = true; }
assert(corruptRejected, 'corrupt cache accepted');
const report = { result: 'passed', cacheIdentity: m.cacheIdentity, cases: fixture.cuts.length, maxJsPythonSampleError: maxPortError,
  cacheIntegerIdentity: true, interpolationInvariants: true, corruptRejected,
  zeroLiveGainSilent: true, rapidRetriggersEnd: true, output48kClockAndEnd: true,
  eightVoicePeak: peak, desktopEightVoiceMillisecondsPerAudioSecond: millisecondsPerAudioSecond,
  desktopDecodeMilliseconds: decodeMilliseconds,
  scope: 'Node PCM decoder and actual streaming JS reader. Not hardware or speaker latency.' };
fs.writeFileSync(path.join(out, 'reader-verification.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({...report, cacheIdentity: "bound in receipt"}));
