# TIDAL rendering and playability

The accepted model-2 source is preserved while production rendering becomes
cheaper and the finite instrument joins SYNTH and FRESH TAPE. Models 1 and 2
keep their existing audio, saved controls and 9.8-second duration.

## Measured desktop result

These are Kotlin/JVM render-call measurements from 2026-10-24, not phone
measurements. Both primary runs used Java 21.0.12.1, an Intel Xeon Platinum
8573C host, four visible processors, `-Xms256m -Xmx768m`, the same frozen
benchmark bytecode, two warmups and five timed samples per case/state. The
recipes are PAIR and STREAM at MIDI 48, velocity 0.75, seed 41, source model 2.
Other builds and tests were paused during both timing runs.

| Recipe and state | Before, median ms | After, median ms |
| --- | ---: | ---: |
| PAIR, geometry and audio caches cleared | 2281.607 | 1233.264 |
| STREAM, geometry and audio caches cleared | 2351.674 | 1225.436 |
| PAIR, geometry warm, fresh audio | 2231.316 | 1018.479 |
| STREAM, geometry warm, fresh audio | 2154.085 | 1000.867 |
| PAIR, identical complete recipe repeated | 2196.695 | 0.305 |
| STREAM, identical complete recipe repeated | 2205.619 | 0.373 |

Fresh audio with warm geometry is about 2.2 times faster for these two cases.
An identical cached performance returns a caller-owned copy without synthesis.
Changing an audible recipe input still requires a full render, around one
second on this host. These results do not establish instant arbitrary knob
edits, Android performance, or musical acceptance of the whole preset bank.

"Caches cleared" means geometry and finished audio are cleared after classes
and JIT have been warmed. It does not mean application cold start. Cache reset,
explicit garbage collection, hashing, validation, logging, JSON and file I/O
are outside the timers. Report those costs separately if measuring a complete
user interaction.

## Memory evidence

The four independent source channels formerly retained 27,659,520 bytes of
oversampled sample storage. They now share one 6,914,880-byte work buffer,
a 75% reduction in that particular storage. This is not a measurement of
total application memory.

| Warm fresh render | Before allocation, MiB | After allocation, MiB | Before pool-peak envelope, MiB | After pool-peak envelope, MiB |
| --- | ---: | ---: | ---: | ---: |
| PAIR | 79.55 | 49.15 | 90.58 | 57.38 |
| STREAM | 83.00 | 52.60 | 94.59 | 61.39 |

Allocation is the render thread's HotSpot `ThreadMXBean` allocated-byte
counter delta. The pool-peak envelope sums per-heap-pool peak used values
after resetting them immediately before each render. Those peaks need not
occur simultaneously: the figure is an upper envelope, not process RSS or
simultaneous live heap. Each table entry is the median of five observations.
Raw observations and definitions are retained in the reports below.

A completed-cache hit allocates about 1.65 MiB, primarily the returned sample
copy. The cache retains at most 8 MiB of audio and excludes names from its
key. Voice, source model, exact seed, note, velocity and all resolved controls
are part of that key. Geometry remains separately bounded to eight settings.

## Why it is built this way

- The source channels are independent, so one oversampled buffer can be reused
  without changing each carrier's phase history, sum order, Float conversions,
  or conditioning. Mixing before conditioning would risk changing saved audio.
- Preparation, the hot core/tail source loops, conditioning, and diagnostics
  have separate boundaries. Production avoids derived taps, traces and RMS
  measurements; diagnostic rendering keeps them for the audition.
- Zero-amplitude carriers retain phase advancement if later excitation is
  possible. Entire silent channels and unobserved zero suffixes can skip work.
  Validated zero-velocity production renders return their exact silent buffer.
- Completed audio belongs to the cache. Copies on insertion and return protect
  future playback from a caller trimming or applying effects to its own pad.
- Preview cancellation stops obsolete edits, including requests that encounter
  a completed-cache hit. No expensive synthesis runs while a cache lock is held.

JFR sampling identified source generation and output conditioning as the main
costs. The separate before/after profiles are supporting evidence, not the
primary timing run. Both old and new large methods were below the JVM's usual
8,000-byte huge-method limit; no speedup is attributed to crossing that limit.

## Audio preservation and retained evidence

Twelve frozen reference recipes cover both voices, both saved models, MIDI
36/48/60/84, zero/low/full velocity, signed seed extremes and macro corners.
All twelve final prelevel and normalized Float32 hashes match the frozen
accepted renderer exactly. They are checked by `TidalRenderTest` using
[`tidal-render-references.json`](../synth/src/test/resources/tidal-render-references.json).
The original published model-1 and model-2 defaults also match. This is
numerical evidence of preservation; only the owner judges the sound.
The same twelve recipes also match under Temurin Java 17.0.21. Four original
diagnostic downloads (population A, STREAM tail, no-tidal-gradient tail and
fixed-envelope tail) replay with exact Float32 and PCM16 WAV identity there.

The baseline was frozen from the previously compiled accepted renderer before
editing. Its jar/class hashes and the loaded optimized class hashes identify
the measured implementations independently of the dirty checkout's revision.
The primary benchmark's optional reflection helper was subsequently tightened
to discover only public profiling hooks. Both primary runs used identical
benchmark bytecode; the corrected hook was exercised in the separate stage
profile and is included in the reproducible task.

- [Before measurements](benchmarks/tidal-rendering/before.json)
- [After measurements](benchmarks/tidal-rendering/after.json)
- [Comparison, runtime and loaded class hashes](benchmarks/tidal-rendering/comparison.json)
- [Frozen baseline provenance](benchmarks/tidal-rendering/baseline-provenance.json)
- [Final twelve-case equality report](benchmarks/tidal-rendering/final-references.json)
- [Java 17 twelve-case equality report](benchmarks/tidal-rendering/java17-references.json)
- [Java 17 published stem replay](benchmarks/tidal-rendering/java17-stem-replays.json)
- [Original profile](benchmarks/tidal-rendering/baseline-profile.json)
- [Revised stage profile](benchmarks/tidal-rendering/after-profile.json)
- [Original JFR sample counts](benchmarks/tidal-rendering/baseline-jfr-top.tsv)
- [Revised JFR sample counts](benchmarks/tidal-rendering/after-jfr-top.tsv)

## Reproduce a current measurement

```sh
./gradlew --no-daemon :synth:benchmarkTidal \
  -PtidalBenchmarkPath=production \
  -PtidalBenchmarkState=all \
  -PtidalBenchmarkWarmups=2 \
  -PtidalBenchmarkSamples=5 \
  -PtidalBenchmarkProcessors=4 \
  -PtidalBenchmarkLabel=current \
  -PtidalBenchmarkOutput=/tmp/tidal-performance.json
```

The task uses a 256 MiB initial and 768 MiB maximum heap. Match Java version, heap flags, processor
count and host load when making a before/after comparison. Select
`tidalBenchmarkPath=uncached` to bypass completed audio, or `diagnostics` to
measure the audition path; `tidalBenchmarkCases=references` selects the twelve
reference performances. `tidalBenchmarkProfile=true` collects a separate
stage profile outside timed samples. Timing never decides a unit-test pass.

## Remaining device checks

On a target phone, measure initial load, geometry-changing edits, note/velocity
edits, identical retriggers, memory pressure and cancellation while dragging.
Verify save/reopen, switching ORIGINAL/MATERIAL, exact variation seeds and
finite SEND TO PAD playback. On an MPC, load both exported formats and confirm
authored pitch, one-shot behavior, levels and complete passive tails. SDK
assembly, emulator UI tests and file read-back do not replace those checks.
