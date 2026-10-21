/**
 * Run after exporting the Kotlin atlas:
 * FACET_GEOMETRY_PATH=/path/to/keyboard/geometry.json \
 * FACET_REFERENCE_ROOT=/path/to/published/site/dist node --test keyboard/engine.test.mjs
 *
 * The PCM references are the actual accepted listening exports, not fixtures
 * reconstructed from this JavaScript engine. No repository-wide Gradle run is needed.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import { FacetKeyboardEngine, Noise, modeCoefficients, renderOffline } from './engine.js';

const here = dirname(fileURLToPath(import.meta.url));
const geometry = JSON.parse(readFileSync(process.env.FACET_GEOMETRY_PATH || resolve(here, 'geometry.json'), 'utf8'));
const referenceRoot = process.env.FACET_REFERENCE_ROOT || resolve(process.cwd(), '../sites/facet-audition/dist');
const rates = [44100, 48000];
const textures = ['glass', 'air'];

function note(overrides = {}) {
  const voice = overrides.voice || 'BLOOM';
  const macros = geometry.voices[voice].macros;
  return {
    type: 'noteOn', id: 'note', midi: 48, velocity: .8, texture: 'glass', voice,
    brightness: .5, density: macros.FACETS, movement: macros.REVOLVE,
    attack: .2, release: .4, initialAlignment: 0, ...overrides,
  };
}

function engine(sampleRate = 44100, options = {}) {
  return new FacetKeyboardEngine({ geometry, sampleRate, maxVoices: 4, gain: 1, ...options });
}

function rms(samples, start = 0, end = samples.length) {
  let square = 0;
  for (let i = start; i < end; i++) square += samples[i] ** 2;
  return Math.sqrt(square / Math.max(1, end - start));
}

function peak(samples) {
  let value = 0;
  for (const sample of samples) {
    assert.ok(Number.isFinite(sample), 'audio must remain finite');
    value = Math.max(value, Math.abs(sample));
  }
  return value;
}

function assertClose(actual, expected, tolerance, message) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${message}: ${actual} vs ${expected}`);
}

function chunks(synth, total, pattern) {
  const output = new Float32Array(total);
  let offset = 0, block = 0;
  while (offset < total) {
    const size = Math.min(total - offset, pattern[block++ % pattern.length]);
    const buffer = new Float32Array(size);
    synth.process(buffer);
    output.set(buffer, offset);
    offset += size;
  }
  return output;
}

function waveDistance(a, b) {
  assert.equal(a.length, b.length);
  let aa = 0, bb = 0, ab = 0;
  for (let i = 0; i < a.length; i++) { aa += a[i] ** 2; bb += b[i] ** 2; ab += a[i] * b[i]; }
  assert.ok(aa > 0 && bb > 0, 'comparison audio must be non-silent');
  const correlation = ab / Math.sqrt(aa * bb);
  return Math.sqrt(Math.max(0, 2 - 2 * correlation));
}

test('Kotlin-exported random samples and modal coefficients match the actual engine', () => {
  for (const [name, voice] of Object.entries(geometry.voices)) {
    assert.equal(voice.noiseSeeds.length, 17);
    for (let source = 0; source < 17; source++) {
      const noise = new Noise(voice.noiseSeeds[source]);
      assert.deepEqual(Array.from({ length: 16 }, () => noise.next()), voice.noiseFirst16[source].map(Math.fround),
        `${name} fixed source ${source}`);
    }
  }
  let checked = 0;
  for (const reference of geometry.model.referenceCoefficients) {
    if (!['GLASS_GLINTS', 'AIRY_RESONANCE'].includes(reference.direction)) continue;
    const actual = modeCoefficients({
      texture: reference.direction === 'GLASS_GLINTS' ? 'glass' : 'air',
      midi: reference.midi, density: reference.density, sampleRate: reference.rate / 4,
    });
    for (const key of ['poleReal', 'poleImag', 'injection', 'modeLevel']) {
      assert.equal(actual[key].length, 16, key);
      for (let i = 0; i < 16; i++) assertClose(actual[key][i], reference[key][i], 2e-11,
        `${reference.voice}/${reference.direction}/${reference.midi} ${key}[${i}]`);
    }
    checked++;
  }
  assert.ok(checked >= 8, 'both textures, voices and accepted registers need Kotlin coefficient fixtures');
});

test('seeded output is deterministic and invariant to render block partitioning', () => {
  for (const rate of rates) {
    const options = note({ voice: 'SHIMMER', texture: 'air' });
    const one = engine(rate), irregular = engine(rate), replay = engine(rate);
    for (const synth of [one, irregular, replay]) synth.handleMessage(options);
    const frames = 8192;
    const expected = chunks(one, frames, [128]);
    assert.deepEqual(chunks(irregular, frames, [1, 7, 64, 255, 13, 129]), expected, `${rate} arbitrary audio blocks`);
    assert.deepEqual(chunks(replay, frames, [128]), expected, `${rate} identical seed replay`);
    assert.ok(rms(expected) > 1e-7, 'deterministic silence would not exercise the renderer');
    one.handleMessage({ type: 'params', params: {
      brightness: options.brightness, density: options.density, movement: options.movement,
      attack: options.attack, release: options.release,
    } });
    assert.deepEqual(one.render(4096), irregular.render(4096), `${rate} unchanged controls must not reset or perturb a voice`);
  }
});

test('attack changes the excitation onset and note-off lets the released voice finish', () => {
  for (const texture of textures) {
    const fast = engine(), slow = engine();
    fast.handleMessage(note({ texture, attack: .01, movement: 0 }));
    slow.handleMessage(note({ texture, attack: .9, movement: 0 }));
    const fastOnset = fast.render(11025), slowOnset = slow.render(11025);
    const fastRms = rms(fastOnset, 2205, 8820), slowRms = rms(slowOnset, 2205, 8820);
    assert.ok(fastRms > slowRms * 1.8, `${texture} attack must have audible early authority: ${fastRms}/${slowRms}`);
    assert.ok(peak(fastOnset) < 1, 'raw excitation onset should have headroom');
    fast.handleMessage({ type: 'noteOff', id: 'note' });
    assert.ok(fast.getState().voices.some(voice => voice.id === 'note' && voice.released), 'note-off must release its voice');
    const tail = fast.render(44100 * 5);
    assert.ok(rms(tail, 0, 4410) > 1e-8, 'key release should retain a modal tail');
    assert.ok(rms(tail, tail.length - 4410) < 1e-6, `${texture} release must become quiet`);
    assert.equal(fast.getState().activeVoices, 0, 'finished voices must leave the active pool');
  }
});

test('release control changes tail duration while panic and repeated note IDs cannot leave stuck notes', () => {
  const short = engine(), long = engine();
  for (const [synth, release] of [[short, .06], [long, 1.2]]) {
    synth.handleMessage(note({ texture: 'air', attack: .01, release, movement: 0 }));
    synth.render(22050);
    synth.handleMessage({ type: 'noteOff', id: 'note' });
  }
  const a = short.render(44100), b = long.render(44100);
  assert.ok(rms(b, 22050, 39690) > rms(a, 22050, 39690) * 1.3, 'long release must retain more late energy');
  long.handleMessage(note({ id: 'note', midi: 60, texture: 'glass' }));
  assert.ok(long.getState().activeVoices <= 4, 'retrigger must respect the voice pool');
  long.handleMessage({ type: 'panic' });
  const stopped = long.render(12000);
  assert.equal(long.getState().activeVoices, 0, 'panic must clear held and releasing notes');
  assert.ok(rms(stopped, stopped.length - 2400) < 1e-8, 'panic must settle to silence within 250 ms');
  long.handleMessage(note({ id: 'new-note' }));
  assert.ok(rms(long.render(11025)) > 1e-7, 'panic must not permanently mute the engine');
});

test('movement integrates the existing phase and density changes preserve the sounding voice', () => {
  const synth = engine();
  synth.handleMessage(note({ voice: 'SHIMMER', texture: 'air', movement: 0, initialAlignment: .3125 }));
  synth.render(11025);
  const frozen = synth.getState().voices.find(voice => voice.id === 'note');
  assertClose(frozen.phase, .3125, 1e-12, 'stopped alignment');
  assert.ok(Number.isFinite(frozen.ageFrames), 'debug state must expose voice age to detect state resets');
  assert.ok(Number.isFinite(frozen.modeEnergy) && frozen.modeEnergy > 0, 'the modal bank must be active before changing density');
  synth.handleMessage({ type: 'params', params: { movement: .65, density: .1 } });
  const edited = synth.getState().voices.find(voice => voice.id === 'note');
  assert.equal(edited.modeEnergy, frozen.modeEnergy, 'changing density must retain the existing modal memories');
  assert.equal(edited.noiseTicks, frozen.noiseTicks, 'changing density must retain the excitation clock');
  assertClose(edited.phase, frozen.phase, 1e-12,
    'changing movement must not reset phase');
  synth.render(1);
  const firstPhase = synth.getState().voices.find(voice => voice.id === 'note').phase;
  const firstAdvance = Math.abs(firstPhase - frozen.phase);
  assert.ok(Math.min(firstAdvance, 1 - firstAdvance) < .001,
    'new speed must integrate from the existing angle, rather than multiply the elapsed note time');
  const moving = synth.render(11024);
  const after = synth.getState().voices.find(voice => voice.id === 'note');
  assert.ok(after.ageFrames >= frozen.ageFrames + 11025, 'density must preserve noise time and existing voice age');
  assert.notEqual(after.phase, frozen.phase, 'positive movement must actually advance the surface');
  assert.ok(!after.released && rms(moving) > 1e-7, 'density changes must keep the note sounding');
  synth.handleMessage({ type: 'params', params: { movement: 0, density: .9 } });
  synth.render(8820);
  const stopped = synth.getState().voices.find(voice => voice.id === 'note');
  synth.render(8820);
  const still = synth.getState().voices.find(voice => voice.id === 'note');
  assertClose(still.phase, stopped.phase, 1e-12, 'zero movement must settle to an exact stopped phase');
  assert.ok(still.ageFrames > stopped.ageFrames, 'stopping geometry must not freeze the source or resonances');
});

test('four-voice chords, extremes and voice stealing stay finite at 44.1 and 48 kHz', () => {
  for (const sampleRate of rates) for (const texture of textures) {
    const synth = engine(sampleRate);
    for (const [i, midi] of [36, 48, 72, 84].entries()) synth.handleMessage(note({
      id: `voice-${i}`, midi, texture, voice: 'SHIMMER', attack: .01, density: 1, movement: 1, brightness: 1,
    }));
    synth.render(Math.round(sampleRate * .1));
    const started = performance.now();
    const chord = chunks(synth, Math.round(sampleRate), [128]);
    const elapsedMs = performance.now() - started;
    console.log(JSON.stringify({ check: 'cpu-observation', sampleRate, texture, voices: 4, blockFrames: 128,
      audioSeconds: 1, elapsedMs, realtimeRatio: elapsedMs / 1000,
      note: 'Observation only; JIT, hardware and concurrent work affect timing.' }));
    assert.ok(peak(chord) < 2, `${sampleRate}/${texture} raw four-voice headroom`);
    assert.ok(rms(chord) > 1e-6, 'polyphony must produce sound across C2–C6');
    const counters = synth.getState();
    assert.equal(counters.nonFiniteSamples, 0, 'the output safety fallback must not conceal non-finite synthesis');
    assert.equal(counters.clippedSamples, 0, 'the output clamp must not conceal raw four-voice overload');
    assert.ok(counters.voices.every(voice => Number.isFinite(voice.modeEnergy)), 'internal resonant memories must stay finite');
    assert.ok(synth.getState().activeVoices <= 4, 'voice limit');
    for (let i = 0; i < 7; i++) {
      synth.handleMessage(note({ id: `steal-${i}`, midi: 40 + i * 5, texture, voice: 'SHIMMER' }));
      assert.ok(peak(synth.render(257)) < 2, 'stealing must remain bounded');
      assert.ok(synth.getState().activeVoices <= 4, 'repeated stealing must respect the pool');
    }
    synth.handleMessage({ type: 'panic' });
    synth.render(Math.round(sampleRate * .3));
    assert.equal(synth.getState().activeVoices, 0, 'polyphonic panic');
  }
});

test('brightness, reflection density and movement change sound, not only displayed state', () => {
  const render = parameters => {
    const synth = engine();
    synth.handleMessage(note({
      texture: 'air', voice: 'SHIMMER', attack: .01, initialAlignment: .125,
      brightness: .5, density: .8, movement: .65, ...parameters,
    }));
    return synth.render(52920);
  };
  const dark = render({ brightness: 0 }), bright = render({ brightness: 1 });
  const darkSpectrum = spectrum(dark, 44100, .15, 1.15), brightSpectrum = spectrum(bright, 44100, .15, 1.15);
  console.log(JSON.stringify({ check: 'brightness-authority', darkCentroidHz: darkSpectrum.centroid,
    brightCentroidHz: brightSpectrum.centroid }));
  assert.ok(brightSpectrum.centroid > darkSpectrum.centroid * 1.1, 'brightness must raise the modal/air spectral balance');
  const coarse = render({ density: 0 }), fine = render({ density: 1 });
  const densityDistance = waveDistance(coarse.subarray(6615), fine.subarray(6615));
  assert.ok(densityDistance > .03, `coarse/fine reflections need audible waveform authority: ${densityDistance}`);
  const stopped = render({ movement: 0 }), moving = render({ movement: .65 });
  const movementDistance = waveDistance(stopped.subarray(6615), moving.subarray(6615));
  assert.ok(movementDistance > .03, `movement must alter reflection excitation: ${movementDistance}`);
  console.log(JSON.stringify({ check: 'reflection-control-authority', densityDistance, movementDistance }));
});

test('dark and neutral brightness retain the published seeded PCM output', () => {
  // Captured before changing the bright-half veil balance. Float32 bytes are
  // written explicitly as little endian so these hashes do not depend on host byte order.
  const goldens = {
    'BLOOM/glass/0': 'fa871ab5d0a7017aa03f5091560f3a13454ff9e542f8a835c6898fc62d15c547',
    'BLOOM/glass/0.25': '2312eefe09143f38f6ee8c31c949277de1a82e593d38f871a01c32f24a92b8f6',
    'BLOOM/glass/0.5': 'e549e728fb0e84203c15028924a01c3a923f295045dd3944846e35a4e9872a3e',
    'BLOOM/air/0': 'a9405fbef4a3855518426c602fb4a8c2d5d525da1364432e425ec60e53bece4b',
    'BLOOM/air/0.25': '0253cc59aaabd8f02b1ab8f716fa55fe2b79f9931295a21b065b65a0ee6ffc4c',
    'BLOOM/air/0.5': '51358dd1c567e0ffe97ce01932e534c16cbe6396d23fa370536be15b1a297dd0',
    'SHIMMER/glass/0': 'ff32b9481982d17dede4b60a20c7cc1debb6041e7f33db1a1b5c1514832896cb',
    'SHIMMER/glass/0.25': '3a3a8e3f30d20b4e1dbca371b21ceed5f1947440ec1ccb7d34fa5bc06aa57675',
    'SHIMMER/glass/0.5': 'a4028a582d65f13749df0d8346d5e38b8b643032a7919e973effee54df5edcf5',
    'SHIMMER/air/0': '991ca27a63281b83e34d39ee850ee5814749cdced3f9c5c246c0515b09a62144',
    'SHIMMER/air/0.25': '38a57ae046d4563592645cfa0fd075d27c5ddef1c625ddd1d2ae135d11ef4ec4',
    'SHIMMER/air/0.5': '0c73be06a1e2b5e2749d6297cb0e0433f9b79e56adb5016fe7d995a2e15f4d7c',
  };
  for (const [configuration, expected] of Object.entries(goldens)) {
    const [voice, texture, value] = configuration.split('/');
    const synth = engine();
    synth.handleMessage(note({ id: 'brightness-regression', voice, texture, brightness: Number(value) }));
    const samples = synth.render(52920);
    const bytes = Buffer.alloc(samples.length * 4);
    samples.forEach((sample, i) => bytes.writeFloatLE(sample, i * 4));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected, configuration);
  }
});

test('bright settings expose the ringing modes without raising the diffuse noise veil', () => {
  for (const voice of ['BLOOM', 'SHIMMER']) for (const texture of textures) {
    const observations = [.5, .75, 1].map(brightness => {
      const synth = engine();
      synth.handleMessage(note({ id: 'brightness-regression', voice, texture, brightness }));
      const samples = synth.render(52920);
      const measured = spectrum(samples, 44100, .35, 1.15);
      const ratios = geometry.model[texture === 'air' ? 'airRatios' : 'glassRatios'];
      const rootHz = 440 * 2 ** ((48 - 69) / 12);
      let diffusePower = 0, modalPower = 0;
      for (let i = 0; i < measured.power.length; i++) {
        const hz = i * measured.binHz;
        if (hz < 2000 || hz > 10000) continue;
        // A 35 Hz exclusion is wider than the modal lines and FFT skirts.
        // The remaining power measures the broad upper veil in actual rendered audio.
        if (ratios.some(ratio => Math.abs(hz - rootHz * ratio) < 35)) modalPower += measured.power[i];
        else diffusePower += measured.power[i];
      }
      return { brightness, diffusePower: diffusePower / measured.windows,
        modalPower: modalPower / measured.windows, diffuseToModal: diffusePower / modalPower };
    });
    const [neutral, brighter, maximum] = observations;
    console.log(JSON.stringify({ check: 'brightness-noise-balance', voice, texture, observations }));
    for (const bright of [brighter, maximum]) {
      assert.ok(bright.diffusePower < neutral.diffusePower * .95,
        `${voice}/${texture}/${bright.brightness} must not raise the broad noise floor`);
      assert.ok(bright.diffuseToModal < neutral.diffuseToModal * .7,
        `${voice}/${texture}/${bright.brightness} must expose the ringing body at equal playback gain`);
      assert.ok(bright.modalPower >= neutral.modalPower * .95,
        `${voice}/${texture}/${bright.brightness} must retain upper modal energy`);
    }
    assert.ok(maximum.diffuseToModal < brighter.diffuseToModal * .9,
      `${voice}/${texture} moving farther into brightness must keep improving ring-to-noise balance`);
  }
});

test('calibrated playback has headroom for coherent four-note chords before the UI volume', () => {
  // Identical note/seed/start combinations deliberately sum coherently. Other
  // cases cover chords and both density/movement corners without an expensive
  // full Cartesian sweep. Gain is the real playback calibration, not raw gain 1.
  const cases = [
    { sampleRate: 44100, voice: 'BLOOM', texture: 'glass', density: 0, movement: 0, notes: [36, 36, 36, 36] },
    { sampleRate: 44100, voice: 'BLOOM', texture: 'air', density: 1, movement: .7, notes: [48, 48, 48, 48] },
    { sampleRate: 44100, voice: 'SHIMMER', texture: 'glass', density: 1, movement: 0, notes: [36, 48, 60, 72] },
    { sampleRate: 44100, voice: 'SHIMMER', texture: 'air', density: 0, movement: .7, notes: [60, 60, 60, 60] },
    { sampleRate: 48000, voice: 'BLOOM', texture: 'glass', density: 1, movement: .7, notes: [48, 48, 48, 48] },
    { sampleRate: 48000, voice: 'BLOOM', texture: 'air', density: 0, movement: 0, notes: [48, 55, 60, 67] },
    { sampleRate: 48000, voice: 'SHIMMER', texture: 'glass', density: 0, movement: .7, notes: [36, 36, 36, 36] },
    { sampleRate: 48000, voice: 'SHIMMER', texture: 'air', density: 1, movement: 0, notes: [48, 48, 48, 48] },
  ];
  const failures = [];
  for (const configuration of cases) {
    const synth = engine(configuration.sampleRate, { gain: null });
    configuration.notes.forEach((midi, i) => synth.handleMessage(note({
      ...configuration, id: `coherent-${i}`, midi, attack: .01, brightness: 1,
    })));
    const output = chunks(synth, Math.round(configuration.sampleRate * .75), [128]);
    const state = synth.getState();
    const observation = {
      check: 'calibrated-headroom', ...configuration, brightness: 1, velocity: .8,
      peak: peak(output), rms: rms(output), clippedSamples: state.clippedSamples,
      nonFiniteSamples: state.nonFiniteSamples,
    };
    console.log(JSON.stringify(observation));
    if (state.clippedSamples !== 0 || state.nonFiniteSamples !== 0 ||
        state.voices.some(voice => !Number.isFinite(voice.modeEnergy))) failures.push(observation);
  }
  assert.deepEqual(failures, [], 'the playback safety clamp must not conceal calibrated four-voice overload');
});

function readWav(path) {
  const data = readFileSync(path);
  assert.equal(data.toString('ascii', 0, 4), 'RIFF', path);
  assert.equal(data.toString('ascii', 8, 12), 'WAVE', path);
  let format, channels, sampleRate, bits, pcm;
  for (let position = 12; position + 8 <= data.length;) {
    const tag = data.toString('ascii', position, position + 4), size = data.readUInt32LE(position + 4), body = position + 8;
    if (tag === 'fmt ') {
      format = data.readUInt16LE(body); channels = data.readUInt16LE(body + 2);
      sampleRate = data.readUInt32LE(body + 4); bits = data.readUInt16LE(body + 14);
      if (format === 65534 && size >= 40) format = data.readUInt16LE(body + 24);
    } else if (tag === 'data') pcm = data.subarray(body, body + size);
    position = body + size + (size & 1);
  }
  assert.equal(channels, 1, 'reference is genuine mono Kotlin output');
  assert.ok(pcm && [1, 3].includes(format), 'supported PCM reference');
  const bytes = bits / 8, samples = new Float64Array(pcm.length / bytes);
  for (let i = 0; i < samples.length; i++) samples[i] = format === 3 ? pcm.readFloatLE(i * bytes)
    : pcm.readIntLE(i * bytes, bytes) / 2 ** (bits - 1);
  return { sampleRate, samples };
}

function spectrum(samples, sampleRate, from = .35, until = 2.7) {
  const length = 16384, power = new Float64Array(length / 2 + 1);
  let windows = 0;
  for (let start = Math.round(from * sampleRate); start + length <= Math.min(samples.length, until * sampleRate); start += length / 2) {
    const re = new Float64Array(length), im = new Float64Array(length);
    for (let i = 0; i < length; i++) re[i] = samples[start + i] * (.5 - .5 * Math.cos(2 * Math.PI * i / (length - 1)));
    for (let i = 1, j = 0; i < length; i++) {
      let bit = length >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) [re[i], re[j]] = [re[j], re[i]];
    }
    for (let size = 2; size <= length; size *= 2) {
      const angle = -2 * Math.PI / size, stepRe = Math.cos(angle), stepIm = Math.sin(angle);
      for (let start = 0; start < length; start += size) {
        let wr = 1, wi = 0;
        for (let j = 0; j < size / 2; j++) {
          const a = start + j, b = a + size / 2;
          const tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
          re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
          const old = wr; wr = old * stepRe - wi * stepIm; wi = old * stepIm + wi * stepRe;
        }
      }
    }
    for (let i = 0; i < power.length; i++) power[i] += (re[i] ** 2 + im[i] ** 2);
    windows++;
  }
  assert.ok(windows > 0, 'spectral window coverage');
  const edges = [0, 200, 500, 1000, 2000, 4000, 8000, 16000, sampleRate / 2 + 1];
  const bands = new Float64Array(edges.length - 1);
  let total = 0, weighted = 0;
  for (let i = 0; i < power.length; i++) {
    const frequency = i * sampleRate / length, value = power[i];
    total += value; weighted += value * frequency;
    for (let j = 0; j < bands.length; j++) if (frequency >= edges[j] && frequency < edges[j + 1]) { bands[j] += value; break; }
  }
  assert.ok(total > 1e-20, 'reference comparison must have spectral energy');
  return { bands: bands.map(value => value / total), centroid: weighted / total,
    power, binHz: sampleRate / length, windows };
}

function alignment(actual, expected, sampleRate) {
  const from = Math.round(.35 * sampleRate), until = Math.round(2.7 * sampleRate);
  let bestLag = 0, best = -Infinity;
  for (let lag = -64; lag <= 64; lag++) {
    let aa = 0, bb = 0, ab = 0;
    for (let i = from; i < until; i += 16) {
      const a = actual[i + lag], b = expected[i]; aa += a*a; bb += b*b; ab += a*b;
    }
    const correlation = ab / Math.sqrt(aa*bb);
    if (correlation > best) { best = correlation; bestLag = lag; }
  }
  const fitted = new Float64Array(expected.length);
  for (let i = 0; i < fitted.length; i++) fitted[i] = actual[i + bestLag] || 0;
  return { samples: fitted, lag: bestLag, correlation: best };
}

function envelopeDistance(actual, reference, sampleRate) {
  const a = [], b = [], width = Math.round(.1 * sampleRate), hop = Math.round(.025 * sampleRate);
  for (let i = Math.round(.35 * sampleRate); i + width < 2.7 * sampleRate; i += hop) {
    a.push(rms(actual, i, i + width)); b.push(rms(reference, i, i + width));
  }
  return waveDistance(a, b);
}

test('default streaming sound retains the accepted Kotlin PCM spectrum and glint envelope', () => {
  for (const voice of ['BLOOM', 'SHIMMER']) for (const midi of [48, 60]) for (const texture of textures) {
    const file = texture === 'glass'
      ? resolve(referenceRoot, 'light-study/raw', `light_${voice.toLowerCase()}_${midi}_glass_glints.wav`)
      : resolve(referenceRoot, 'air-revision/raw', `air_${voice.toLowerCase()}_${midi}_airy_resonance.wav`);
    const reference = readWav(file);
    assert.equal(reference.sampleRate, 44100);
    const macros = geometry.voices[voice].macros;
    const rendered = renderOffline({
      geometry, sampleRate: 44100, seconds: 3, finiteReferenceSeconds: 6, gain: 1,
      voice, texture, midi, velocity: .8, initialAlignment: 0, brightness: .5,
      density: macros.FACETS, movement: macros.REVOLVE,
    });
    const raw = rendered.samples;
    assert.ok(peak(raw) < 1, 'unlevelled stream should retain source headroom');
    const aligned = alignment(raw, reference.samples, 44100);
    const a = spectrum(aligned.samples, 44100), b = spectrum(reference.samples, 44100);
    const spectralL1 = a.bands.reduce((sum, value, i) => sum + Math.abs(value - b.bands[i]), 0);
    const temporal = envelopeDistance(aligned.samples, reference.samples, 44100);
    console.log(JSON.stringify({ check: 'kotlin-reference', voice, midi, texture,
      lag: aligned.lag, correlation: aligned.correlation, spectralL1, envelopeDistance: temporal,
      centroidHz: a.centroid, kotlinCentroidHz: b.centroid }));
    assert.ok(aligned.correlation > .995, `${voice}/${midi}/${texture} seeded waveform drift`);
    assert.ok(spectralL1 < .03, `${voice}/${midi}/${texture} spectral balance changed: ${spectralL1}`);
    assert.ok(Math.abs(a.centroid / b.centroid - 1) < .04, 'accepted glint/air brightness');
    assert.ok(temporal < .04, `${voice}/${midi}/${texture} glint envelope changed: ${temporal}`);
  }
});
