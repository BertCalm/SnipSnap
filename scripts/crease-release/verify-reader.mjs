import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { webcrypto, createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { gzipSync } from 'node:zlib';
import { decodeCache, digest } from './player/cache.mjs';
import { ReleaseReader } from './player/pcm-engine.mjs';
globalThis.crypto ??= webcrypto;

const out = process.argv[2];
const manifestBytes = fs.readFileSync(path.join(out, 'manifest.json'));
const m = JSON.parse(manifestBytes);
const packed = fs.readFileSync(path.join(out, m.container.file));
const decodedAt = performance.now();
const pcm = await decodeCache(m, packed);
const decodeMilliseconds = performance.now() - decodedAt;
const fixture = JSON.parse(fs.readFileSync(path.join(out, 'raw/reader-fixtures.json')));
const fixturePath = path.join(out, 'raw/reader-fixtures.f32');
const fixtureHash = createHash('sha256');
for await (const chunk of fs.createReadStream(fixturePath)) fixtureHash.update(chunk);
assert.equal(fixtureHash.digest('hex'), fixture.sha256);
assert.equal(fs.statSync(fixturePath).size, fixture.cuts.length * fixture.frames * 4);
const fixtureFile = fs.openSync(fixturePath, 'r');
const expected = new Float32Array(fixture.frames);
const expectedBytes = Buffer.from(expected.buffer);
const block = new Float32Array(128);
let maxPortError = 0;
for (let row = 0; row < fixture.cuts.length; row++) {
  assert.equal(fs.readSync(fixtureFile, expectedBytes, 0, expectedBytes.length, row * expectedBytes.length), expectedBytes.length);
  const reader = new ReleaseReader(m, pcm); reader.start(1);
  reader.voices[0].pos = fixture.cuts[row]; reader.release(1);
  for (let at = 0; at < fixture.frames; at += 128) {
    const part = block.subarray(0, Math.min(128, fixture.frames - at));
    reader.process(part);
    for (let i = 0; i < part.length; i++) maxPortError = Math.max(maxPortError, Math.abs(part[i] - expected[at + i]));
  }
  assert.equal(reader.voices.length, 0, `stuck release at ${fixture.cuts[row]}`);
}
fs.closeSync(fixtureFile);
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
for (const voice of full.voices) full.release(voice.id);
const releaseBegin = performance.now();
for (let i = 0; i < 345; i++) { full.process(block); assert(block.every(Number.isFinite)); }
const releaseMilliseconds = performance.now() - releaseBegin;
const at48k = new ReleaseReader(m, pcm); at48k.start(1);
at48k.process(new Float32Array(48000), 48000);
assert(Math.abs(at48k.voices[0].pos - 44100) < 1e-5, '48k source clock');
at48k.release(1); at48k.process(new Float32Array(58000), 48000);
assert.equal(at48k.voices.length, 0, '48k release ends');

let corruptRejected = false;
const corrupt = Buffer.from(packed); corrupt[corrupt.length >> 1] ^= 1;
try { await decodeCache(m, corrupt); } catch { corruptRejected = true; }
assert(corruptRejected, 'corrupt cache accepted');
// The declared format selects the reader. Unknown extra metadata in a legacy
// manifest must not turn its full-tail PCM into an unvalidated shared basis.
const legacyFrames = m.heldFrames + 2 * m.tailFrames;
const legacyBytes = Buffer.alloc(legacyFrames * 2), legacyPacked = gzipSync(legacyBytes);
const legacyMeta = { ...m, format: 'crease-playable-pcm-v1', tailCuts: [0, m.loopStart],
  shared: { components: 32, pad: 89, offsets: [0, 0], coefficients: [1], coefficientsSha256: '0'.repeat(64) },
  container: { ...m.container, pcmBytes: legacyBytes.length, compressedBytes: legacyPacked.length,
    deltaOrder: 0, pcmSha256: await digest(legacyBytes), compressedSha256: await digest(legacyPacked) } };
const legacyPcm = await decodeCache(legacyMeta, legacyPacked), legacyReader = new ReleaseReader(legacyMeta, legacyPcm);
assert.equal(legacyReader.coefficients, null, 'legacy metadata selected the shared reader');
assert.equal(legacyReader.tail(0, 10000), 0, 'legacy full-tail PCM was read as a basis');
assert.deepEqual(fs.readFileSync(path.join(out, 'manifest.json')), manifestBytes, 'manifest changed during verification');
const report = { result: 'passed', cacheIdentity: m.cacheIdentity, manifestSha256: await digest(manifestBytes), cases: fixture.cuts.length, maxJsPythonSampleError: maxPortError,
  cacheIntegerIdentity: true, interpolationInvariants: true, corruptRejected,
  zeroLiveGainSilent: true, rapidRetriggersEnd: true, output48kClockAndEnd: true,
  eightVoicePeak: peak, desktopEightVoiceMillisecondsPerAudioSecond: millisecondsPerAudioSecond,
  desktopDecodeMilliseconds: decodeMilliseconds,
  desktopEightUnfoldVoicesMillisecondsPerAudioSecond: releaseMilliseconds,
  legacyMetadataCannotSelectSharedReader: true,
  scope: 'Node PCM decoder and actual streaming JS reader. Not hardware or speaker latency.' };
fs.writeFileSync(path.join(out, 'reader-verification.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({...report, cacheIdentity: "bound in receipt"}));
