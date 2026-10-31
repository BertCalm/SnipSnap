# EROSION native first round

Status: native first proof built; focused checks passed, full regression unverified. The owner accepted the original
Python audition on 2026-10-31. That acceptance applies to the Python clips;
the Kotlin port and its velocity layers still need listening.
Repository baseline: `d7c2379c16520df2679457679844b56d32777712`.

The characteristic gesture is charge → irregular discharge → delayed collapse
→ thinning dust. This is a bounded energy-reservoir analogy for electrical
discharge machining, not a plasma, heat-transfer or bubble-dynamics simulation.

## Identity retained

ARC and CRATER preserve the Python parameter laws and defaults. ARC emphasizes
dense fine discharges; CRATER uses a larger release threshold and more collapse.
The native renderer uses `Dsp.seedFor` / `Dsp.Noise`, rather than the Python
stand-in's seed hash and RNG. It uses the house constant-peak RBJ bandpasses
rather than SciPy's peak filter. It therefore preserves the design and parameter
ranges, not byte identity with the accepted clips. The original WAVs remain the
listening reference.

The nearest siblings remain THUMP, including its oscillator-based SPARK voice,
and SKIN's noise-driven hats and grain-shaped shaker. EROSION adds a bounded
charge/recovery event schedule with collapse funded by the same discharge.

```text
finite reservoir -> charge -> discharge -> recovery        @8 kHz
                                |
                    Event(time, energy, variation)
                         /                 \
             smooth bipolar pulse     delayed collapse chirp
                       |                    |
                4 broad bandpasses         |
                         \                 /
                               mix                         @176.4 kHz
                                |
             band-limit -> decimate -> mean/DC high-pass
                       -> level -> tail fade               @44.1 kHz mono
```

## Controls and voices

All constants below are sound-design shapes, not physically measured values.

| Control | ARC | CRATER | Quantity |
| --- | ---: | ---: | --- |
| TUNE | .50 | .50 | Nominal collapse frequency, 80–320 Hz exponentially; weakly transposes fluid centers. No stable MIDI-root promise. |
| VOLTAGE | .65 | .30 | Energy threshold .012–.00065, recovery 1.8–.18 ms, exponential. CRATER threshold multiplier 1.6. |
| FEED | .35 | .50 | Supply e-folding time 18–140 ms; supply ends at eight time constants. |
| FLUID | .60 | .60 | Pulse width 200–45 μs, filter base 1.4–5 kHz. |
| SLAG | .08 | .65 | Collapse energy fraction 0–.65, delay 1–8 ms, duration 25–65 ms. |

HOLD is not exposed. Saved patches reject unknown controls, including HOLD;
the direct renderer drops unknown keys and does not create or label a loop.
This is a percussion engine. Its actual rendered class is used when requested;
it does not force a hat-like waveform into a pitched-engine PERC constraint.

## Energy and timing

An 8 kHz scheduler transfers the exact exponential supply integral each tick
from a reservoir initialized to one into a charge accumulator. A threshold
crossing releases 90% of accumulated charge. Seeded variation affects the next
threshold and recovery duration, not energy creation. The invariant is
`reservoir + charge + emitted = 1`. It bounds the excitation proxy, not acoustic
power after overlapping kernels. Raw audio peak is tested separately before
output conditioning.

The schedule seed contains engine, model, voice and a purpose tag. Audio
controls TUNE, FLUID and SLAG do not alter the event sequence. The scheduler's
maximum work is 8,960 ticks. Output allocation follows the output frame count;
the longest hit is about 1.24 seconds, below the classifier's strictly-greater
1.5-second LOOP threshold.

Source kernels are evaluated from the exact event age. Rounding an onset to the
nearest sample at each render rate introduces timing differences that can
masquerade as aliasing. Collapse kernel energy normalization uses one canonical
4× reference per configuration, not per-event or per-rate normalization. A
kernel table is prepared once per render and sampled at fractional event ages.

Four broad, fixed-Q RBJ bandpasses reuse `Dsp.Biquad`. Each is stable at its
bounded frequency and positive Q. No audio feeds back into charge scheduling.
The source is band-limited with `Tide.bandLimit`, then uses `Dsp.decimate`, mean
subtraction, a 20 Hz high-pass, `Dsp.levelTo` and `Dsp.fadeTail` in that order.
No change was made to shared DSP or previously shipped engines.

## Native touch

Velocity is saved beside the model in `ErosionPatch`, rather than overloading
a macro. At full velocity the contact laws retain the original ranges. Softer
velocity widens the spark kernel by up to 2.5× and lowers fluid centers toward
55% of their saved positions. Discharge timing stays fixed. Zero velocity is
silent. Tests compare soft/full waveforms after fitting one gain to show that
the response is more than amplitude scaling. Velocity-zone dispatch multiplies
the saved touch by the layer's touch, preserving already-soft recipes.

## Integration and reproduction

The native patch participates in `Patches` JSON dispatch, both exhaustive
`Velocity` decisions, per-voice determinism canaries and patch/recipe round-trip
fixtures. It is explicitly in `PatchTypes.PROTOTYPES`: acceptance of the Python
proof is not acceptance of a blind native factory library. Factory presets,
app picker, starter kit and seamless HOLD remain later rounds. Android work
requires an SDK-backed build; none is available in this session.

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.ErosionTest
./gradlew --no-daemon :synth:generateErosionAudition
./gradlew --no-daemon test
```

The generator writes native and matched PCM24 WAVs, independently replayable
native patch recipes, a browser audition and a source-hashed manifest under
`testkit/erosion-native-audition/`. Every macro has five steps; both voices have
four touch layers and VOLTAGE × FEED corners. Matching uses one gain per clip
against the loudest 50 ms window with a .95 peak ceiling. Matched files are
listening conveniences; recipe playback produces the native file.

The render-rate comparison independently synthesizes at 4× and 8×. It resamples
the 8× source to 4×, then applies the identical house delivery path to both.
This controls the different-resampler confound in the Python comparison. The
remaining residual still includes source sampling, filter discretization and
resampling differences. It is convergence evidence, not a pure alias-power
measurement. No claim about actual phone render time is made; tens of ms
remains the target.

## Why it is built this way

- The finite reservoir keeps the accepted ending instead of placing an arbitrary
  fade over an endless random click generator.
- Recovery and a shared event ledger connect the spark and collapse gestures.
- Exact event ages make render-rate comparisons test the audio model rather
  than different onset rounding.
- Native touch changes contact and spectral response while keeping the saved
  discharge pattern recognizable.
- The main listening risk is that house filters and a new RNG change the fine
  texture of the accepted Python reference. Compare them before adding voices.

## As built and verification ledger

The Kotlin probe measured all 64 macro corners before output conditioning:
maximum raw peak 0.789721, maximum delivered absolute mean 0.00253235, and
maximum excitation-accounting error 6.67e-16. All corners were finite and
remained finite one-shots below 1.5 seconds.

At TUNE, FLUID and VOLTAGE 1 with SLAG at 0 and 1, the matched-chain 4×/8×
relative RMS residual ranged from 0.001637 to 0.002355 (0.164–0.236%). These
are Kotlin measurements with the comparison described above. They replace the
unresolved Python 15% comparison as native convergence evidence; neither
number is a pure alias-power measurement.

Soft/full waveform residual after fitting one gain was .952 ARC / .745 CRATER
at velocity .35 versus 1. This verifies a timbral change; it does not prove
that the touch range is musically convincing.

The final focused ErosionTest run passed all seven tests and
`:synth:generateErosionAudition` completed with exit code 0. The generator
produced 68 settings, 136 PCM24 WAVs and 68 saved native recipes. All five
renderer source hashes match the generation manifest; every delivered WAV
header and every audio/recipe page link was checked. The earlier focused
PatchesTest (3), PadRecipeTest (20) and DeterminismTest (51) classes passed.

Across the 68 generated native cases, every class was PERC. Maximum native
sample peak was .99000007 and maximum absolute mean .00104292 before PCM
encoding. The median single local render observation was 7.40 ms. This is not
a repeatable benchmark or a target-phone result.

The required full JVM command was launched and remained running when this
native listening round was prepared. It is explicitly unverified: no full-suite
pass, Android compilation or release-readiness claim is made. The native change
is kept as a draft for review. The repository documents full suite durations
exceeding an hour on CI; this focused listening deliverable does not wait for
that entire run. Full JVM completion is still required before merging.
Owner-heard evidence: positive feedback on the first Python audition. Kotlin
listening, phone performance and MPC hardware validation remain open.
