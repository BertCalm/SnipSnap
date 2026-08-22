# Loop Player — Design

Date: 2026-08-22
Status: Approved, pending implementation plan

## 1. What this is

A performance surface for SnipSnap: a grid of tracks, each holding a vertical chain of blocks. Every engaged track plays at once. At each interval boundary every track advances to the next block in its own chain and wraps at its own end.

Because chains have different lengths, tracks fall in and out of phase with each other. A track of 2 blocks and a track of 3 blocks do not realign for 6 intervals. The full arrangement repeats only after the least common multiple of all chain lengths.

Worked example, taken from the originating sketch — six tracks with chains of 2, 3, 2, 1, 4 and 2 blocks:

```
LCM(2, 3, 1, 4) = 12 intervals
```

At 4 bars per interval that is a 48-bar piece, roughly 128 seconds at 90 BPM, generated from 14 blocks and no arrangement work. That combinatorial payoff is the feature. Everything below exists to serve it.

## 2. Why it does not fit the existing architecture

SnipSnap today is an instrument builder. The shipped arc runs one direction: capture, clean, chop, classify, place on a 4x4 grid, export `.xpm` plus WAVs. The MPC does the playing.

The loop player is the first feature that asks SnipSnap itself to perform. That means it needs machinery the project has deliberately never built:

- No transport, clock, playhead, scheduler or sequencer exists anywhere.
- No model of a bar, clip, pattern or arrangement exists anywhere.
- Nothing outputs audio to a device. `Dsp.kt` states it plainly: "Nothing here is real-time." Every synth voice is `fun render(): Snip`, computing a whole buffer up front.
- Nothing reads sample data off disk. `WavWriter` encodes; `WavInfo` reads headers only. There is no decoder.
- There is no resampler. `WavWriter` refuses non-44.1kHz input rather than converting it.

## 3. Non-goals

- **No MPC sequence export.** Writing `.sxq` standard MIDI or MPC3 `sharedClipMap` JSON means building a writer from scratch against a format that is only partly reverse-engineered. The bounced WAV re-enters SnipSnap's own pipeline instead (see section 8), which reaches the MPC without any new format code.
- **No time-stretching or pitch-shifting DSP.** Block fitting uses slice-and-retrigger against existing onset detection.
- **No per-track offsets.** All tracks start together at block 1 and advance in lockstep.
- **No sub-interval launch quantization.** Changes land on interval boundaries.

## 4. The fitting rule

A session fixes three values:

| Value | Editable | Source |
|---|---|---|
| `bpm` | live | Seeded from the first **loop** block placed, via `Tempo.estimate`. If the session's first block is a pattern block, BPM defaults to 90. User-overridable in both cases. |
| `barsPerInterval` | fixed at session creation | User choice at creation (1, 2, 4 or 8) |
| `sampleRate` | fixed | Queried once from the audio sink at session start |

From these:

```
intervalFrames = barsPerInterval * 4 * (60 / bpm) * sampleRate
```

**Every block bakes to exactly `intervalFrames`.** This invariant is what makes mixing trivial: all resident buffers are the same length and sample-aligned by construction, so summing tracks is `dst[i] += src[i]` with no interpolation.

Baking differs by block type:

**Loop block.** Decode the WAV, resample to the device rate, then fit:

- If the source is within 2% of `intervalFrames`, trim or zero-pad to length. This avoids slicing artifacts on material that is already correct.
- Otherwise detect onsets with `Transients`, slice with `Chopper`, and retrigger the slices onto the interval grid.

Fitting is **always on**, not a tempo-change special case. Captured audio from a video, another app or the mic will never be exactly N bars at session tempo, and `Tempo.kt` cannot conform it — its own comment says it is "for filename labeling only, no FFT, no beat grid." Making fitting unconditional removes a branch and puts `Transients`/`Chopper` on the critical path from day one.

**Pattern block.** Render hits from the referenced kit at step offsets computed from `bpm`, at 16 steps per bar, into a buffer of exactly `intervalFrames`. A pattern block therefore holds `16 * barsPerInterval` steps — 64 at the default 4 bars. Because `barsPerInterval` is fixed for the life of a session, a pattern block's step count never changes after creation.

## 5. Data model

New pure-JVM module `:loop`.

```
Session(tracks, bpm, barsPerInterval, sampleRate)
Track(name, chain: List<Block>, engaged, level, pan)

sealed Block
  LoopBlock(sampleFile)
  PatternBlock(steps: List<Step>, kitRef)

Step(slot, velocity, microOffset)
```

`sampleFile` is a bare filename resolved against the session folder, following the constraint `KitPad.sampleFile` already enforces: a session and its audio travel together as one directory.

**Playback position is a single integer.** Track `t` at interval `i` plays `t.chain[i % t.chain.size]`. There are no per-track cursors and nothing that can drift. The set of playing blocks is a pure function of `(chains, i)`, so the entire arrangement layer is unit-testable headless with no audio hardware.

Fixed at six tracks, matching the originating sketch. Chains are variable length, capped at 8 blocks.

## 6. Engine

| Component | Responsibility | Thread |
|---|---|---|
| `Transport` | Interval counter and frame position. Pure logic, no audio. | any |
| `BlockBaker` | `Block` to `Snip` of exactly `intervalFrames`. | worker |
| `Residency` | Holds baked buffers for the current and next block of each track — 12 buffers. | shared |
| `Mixer` | Sums engaged tracks' current buffers into the output block. | audio |
| `AudioSink` | `write(FloatArray)`. | audio |

The audio thread reads floats, sums them, and advances a counter. It never decodes, never allocates, never schedules a note, and never blocks on I/O.

`AudioSink` has two implementations. The Android one wraps AudioTrack or Oboe and lives in `:app`. The offline one wraps `WavWriter` and *is* the bounce feature — playback and export are the same code path with a different sink.

**Sample rate.** The sink's native rate is queried once at session start and everything bakes at that rate. Android output is commonly 48kHz while SnipSnap is 44.1kHz throughout, so resampling is required — but it happens once per bake, off-thread, never in the callback.

**Memory.** At 48kHz, 4 bars at 90 BPM is 10.67 seconds, or about 4.1MB as stereo float32. Twelve resident buffers is roughly 49MB. Buffers stay float32 because `Snip` is already `FloatArray` and it keeps the mixing loop simple. Dropping to int16 halves it to about 25MB if memory pressure demands it; that is a tuning lever, not a design change.

## 7. Live controls

Every control reduces to the same operation: **bake off-thread, swap atomically at the next interval boundary.** One mechanism, four surfaces.

| Control | Cost |
|---|---|
| Engage / mute a track | A gain flag the mixer reads. No bake. |
| Swap a block's audio | Bake one buffer, swap one reference. |
| Add / remove blocks in a chain | Swap the chain list atomically. |
| Change BPM | Recompute `intervalFrames`, re-bake everything resident, swap. |

**Shortening a chain past the playing index:** the current block finishes, then `i % newSize` takes over at the next boundary. Blocks are never cut mid-play.

**Changing BPM** causes a brief re-bake pause rather than continuous real-time stretching. This is an explicit trade: a pause on an occasional control, against zero DSP cost in the hot path forever.

**`barsPerInterval` is deliberately not live.** BPM change preserves the invariant — the same bars occupy a different duration, so buffers re-bake and play on. Changing bars-per-interval breaks it: every resident buffer becomes the wrong length, and truncation semantics would have to be invented for a control nobody reaches for mid-performance.

## 8. Persistence and export

**Session file.** `loop.json` beside the audio, mirroring the folder-plus-sidecar pattern `KitStore` already uses for `kit.json`, written with the existing dependency-free `:json` module. Stores tracks, chains, block references, engaged state, BPM and bars-per-interval.

**Bounce.** Render LCM-many intervals through the same `Mixer` into a `WavWriter` sink — stereo, or per-track stems. Because it reuses the playback path, it costs roughly the price of the `AudioSink` interface.

**Cycle length must be surfaced before rendering.** LCM grows fast: chains of 5, 7 and 8 give 280 intervals, and adding a 3 gives 840. At 4 bars per interval that is a 3,360-bar bounce. The chain cap of 8 bounds it; showing the computed cycle length before the user commits keeps it honest.

**Reaching the MPC.** The bounced WAV re-enters SnipSnap's own pipeline: `Chopper` slices it at transients, `Classifier` labels the pieces, `AutoPlace` lays them on the pad grid, `KitExporter` writes the `.xpm`. The jam becomes source material for a kit the MPC can load, with no sequence-format code.

## 9. Modules and new code

The delivery vehicle is a new Android `:app` module — Compose UI, AudioTrack or Oboe sink. The engine stays in a pure-JVM `:loop` module.

This split is load-bearing. The six existing modules are JVM 17 specifically so Android can consume them as plain JARs; `:app` becomes the seventh consumer, not a replacement. Arrangement logic, transport and mixing stay headless and testable. Only the sink and the UI are Android.

| New code | Home | Note |
|---|---|---|
| WAV decoder | `:audio` | Beside `WavWriter`. Nothing in SnipSnap reads sample data today. |
| Resampler | `:audio` | 44.1kHz to device rate, at bake time only. |
| `Transport`, `Residency`, `Mixer`, `BlockBaker` | `:loop` | No transport concept exists today. |
| Pattern renderer | `:loop` | Borrows `Groove.kt`'s mixing loop. The step grid, per-step velocity and choke handling are new. |
| `AudioSink` + Android impl | `:loop` / `:app` | First real-time audio in the project. |
| Session model + `loop.json` store | `:loop` | Follows `KitStore`. |
| `:app` module | new | Android Gradle Plugin, Compose. |

**`Groove.kt` is thinner precedent than it first appears.** It renders a fixed, generated 16-steps-per-bar pattern from a kit's own pads. The pattern renderer borrows its mixing loop but needs a user-authored step grid, per-step velocity and choke groups on top.

**CI must change in the same commit as `:app`.** `.github/workflows/tests.yml` sets up Temurin 17 and runs `./gradlew --no-daemon test` and nothing else. The moment an Android Gradle Plugin module lands, that workflow needs an Android SDK or the build fails, taking all 384 existing tests down with it.

## 10. Testing

The lockstep rule makes the valuable parts testable without audio:

- **Arrangement** — `chain[i % size]` across many intervals and chain-length combinations, including LCM wraparound and chains shortened past the playing index. Pure function, no hardware.
- **Fitting** — every bake produces exactly `intervalFrames`, for both block types, across BPM and bars-per-interval combinations, and for sources shorter than, longer than, and within 2% of target.
- **Decoder and resampler** — round-trip against `WavWriter` output; the `reference/golden` corpus supplies real-world files.
- **Mixer** — determinism and correct summing of engaged versus muted tracks.
- **Bounce** — an offline sink render matches an expected buffer sample for sample, which also proves playback and export share one path.

Only the Android sink needs a device.

## 11. Decisions taken, with reasons

1. **`barsPerInterval` fixed at session creation, BPM live.** Section 7.
2. **Six fixed tracks, chains capped at 8 blocks.** Matches the sketch; the cap bounds LCM blow-up.
3. **Resident buffers are float32.** Matches `Snip`; int16 is a tuning lever.
4. **Both block types unify as "exactly one interval of audio."** This is what avoids building two engines. Pattern blocks pre-render, so the sequencer, voice pool and choke handling all leave the real-time path — which is where this codebase has always put its DSP.
5. **No MPC export.** Section 3 and section 8.
6. **No per-track offsets.** They add no musical possibility when every cycle is heard in full, and they would replace a single integer of transport state with six.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Two large unknowns land together: a from-scratch Android app and the project's first real-time audio. | Keep `:loop` pure JVM and fully tested before `:app` needs to make a sound. |
| Adding `:app` breaks CI for all 384 existing tests. | Update `tests.yml` in the same commit; keep engine tests out of `:app`. |
| Slice-and-retrigger fitting sounds bad on sustained or non-percussive material. | The 2% passthrough covers well-formed loops. Fitting quality on pads and vocals needs a listening test early. |
| Re-bake pause on BPM change is disruptive in performance. | Twelve buffers, bake in parallel off-thread. Measure before optimizing. |
| No JDK on the development machine. | Blocking, and step zero: JDK 17, Android Studio and SDK before any code. |

## 13. Prerequisite

There is currently no Java runtime on the development machine — not on PATH, not in `/Library/Java/JavaVirtualMachines`, no Homebrew `openjdk`, no SDKMAN. The project cannot be compiled or tested locally today. Installing JDK 17 plus Android Studio and the Android SDK precedes all implementation work.
