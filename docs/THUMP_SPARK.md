# THUMP SPARK and related hit families

Status: prototype built; focused JVM checks and final audition generation pass.
The full JVM suite is unverified after environment interruptions; CI must finish
that validation. Android compilation, phone/MPC hardware checks and the owner's
listening verdict remain pending. Repository baseline read: `3ecbdb78`
(2026-10-24). This adds a voice to THUMP; it does not add an engine or replace
existing drum voices.

The characteristic gesture is strike → metallic flare → falling pitch → rounded
body. A related family moves from darker, longer hits toward brighter, more
metallic, shorter hits as separate editable snapshots.

## What carries over from the references

The research has two distinct parts. The Yamaha RY30 contributes per-hit
expression: wheel positions alter pitch, decay, filter cutoff or the balance
between two wave layers as notes are entered or encountered in parameter
recording. Its filters belong to the separate wave layers, with independent
cutoff and envelope controls. They are not a pair of generic serial filters.
See the [Yamaha operating manual](https://usa.yamaha.com/files/download/other_assets/0/317230/RY30E.pdf),
printed pp23, 28–30, 41–42 and 85–86.

The original Korg ER-1 contributes simple synthesized percussion sources,
pitch modulation extending into audio rates, ring modulation and the distinction
between smooth motion and values held at each trigger. Its four synthesized
parts coexist with PCM parts. Its dedicated interpart ring modulation differs
from the later mkII's interpart frequency cross modulation; the original manual
also calls its fast internal pitch modulation cross-modulation effects. See the
[original ER-1 manual](https://cdn.korg.com/us/support/download/files/77b10f93dbdbf465c8ad01e62467f42e.pdf),
pp6, 18, 24–25 and 28, and the
[mkII manual](https://cdn.korg.com/us/support/download/files/ad483aaec5378de168bfd3ff25d3b36a.pdf),
pp9 and 25.

SPARK distils those behaviors into one new THUMP voice. Oscillators,
multiplication, phase modulation and filters are literal DSP. The coordinated
family offsets are a sound-design analogy for expression, not a simulation of
either machine's wheel, firmware or analog circuitry. No original samples,
factory names or interface artwork are used.

## Scope and signal flow

The nearest existing siblings are THUMP, already home to short oscillator drum
voices, and TANGLE, whose oscillators influence one another over a longer pitched
gesture. SPARK keeps a bounded one-way percussion model; it does not need
TANGLE's reciprocal encounter and capture system. Within THUMP, COWBELL's
two-tone source is the closest metallic voice; TINES' FM percussion is another
useful audition comparison. A THUMP voice is the lighter landing:
SPARK combines a stable sine body with an independently shaped metallic edge,
then exposes a repeatable relationship between separate hits. A new engine
registry, sequencer or general modulation matrix would not improve this first
proof.

```text
TUNE, DECAY, METAL, COLOR (constant during each hit)
                         |
                         v
            bounded oscillator / envelope plan
                         |
       +-----------------+-------------------------+
       |                                           |
       v                                           v
  sine body                              phase-modulated edge
  body decay                             + sine × triangle ring pair
       |                                 faster modulation / edge decay
       v                                           |
  body low-pass                                    v
       |                                      edge low-pass
       +--------------------+----------------------+
                            |
                            v
       sum at 176.4 kHz → band-limit → DC removal / 20 Hz high-pass
                            |
                            v
       existing THUMP finish: normalize → oversampled PUNCH
                         → peak ceiling → tail fade / 44.1 kHz output
```

SPARK is a finite mono one-shot. TUNE maps exponentially from 110 to 440 Hz;
0.5 is 220 Hz. DECAY maps exponentially from 0.16 to 1.0 seconds for the body's
t60. The raw render lasts 1.4 times t60, so the duration is approximately
0.224–1.4 seconds. There is no HOLD or seamless-loop claim. These mappings and
the edge's irrational frequency ratio are shape decisions until listening.

The body stays present as METAL rises. The edge combines phase modulation with
ring modulation against a triangle source at a ratio derived from √2. The
triangle uses seven odd harmonics, rather than a discontinuous waveform.
The phase-modulation index starts at `0.8 + 3.2 × METAL`, up to 4 before its
decay envelope. The dry body has fixed weight 0.55. Edge gain is
`0.45 × sqrt(METAL) × (0.65 + 0.70 × COLOR)`, so low and middle METAL settings
receive more travel and COLOR coordinates edge balance with spectral opening.
These gains and indices remain shape choices. Separate `Dsp.TptSvf` low-pass
filters preserve a dark body underneath a brighter edge. The body cutoff moves
from 1.5 to 8 times the root; the edge cutoff moves from 200 to 5,000 Hz and
closes as the modulation dies away.

Decay envelopes precede the filters. Edge t60 is
`body t60 × (0.80 - 0.15 × METAL)`; modulation t60 is 0.90 times body t60.
Both decay faster than the body without confining the metallic effect to the
first instant. METAL also sets the initial pitch excess above the root through
`bendDepth = 0.08 + 0.92 × METAL`. COLOR sets that pitch fall's time constant
through `bendTime = 0.012 + 0.012 × COLOR` seconds. The oscillator phase uses
the exact integral of this exponentially falling frequency. There is no
recursive oscillator feedback or event solver.

Only SPARK receives its new band-limit and DC-conditioning stages before the
shared THUMP finish. Existing voices keep their existing rendering path. The
finish follows THUMP's drum-specific PUNCH behavior, rather than replacing it
with the pitched-engine loudness stage described in the general conventions.

## Controls

| Control | Default | Changes | Required interaction / proof |
|---|---:|---|---|
| TUNE | 0.50 | Sine root, the edge/modulator frequency context and root-relative body cutoff | TUNE × METAL must retain a usable pitched body across the register. |
| DECAY | 0.35 | Body t60, the shorter edge/modulation envelopes and render duration | DECAY × METAL must change how the edge gives way to the body; duration increases with DECAY. |
| PUNCH | 0.50 | THUMP's existing attack/body shaping | PUNCH must move the envelope without replacing the source's identity. |
| METAL | 0.25 | Edge blend through a square-root law, phase-modulation depth, ring contribution, initial pitch-drop depth and edge t60 ratio | METAL × COLOR must expose sidebands and ringing at matched level. |
| COLOR | 0.50 | Body and edge low-pass response, edge/body balance and pitch-fall time constant | Spectral probes cover TUNE 0.5, METAL 0.65, PUNCH 0 at COLOR 0.25/0.5/0.75; low-METAL audibility remains a listening target. |

The numerical checks establish scoped spectral movement; they do not establish
audibility across every combination. The listening pass must judge low and
middle control settings as well as the extremes. Non-finite or missing
control values fall back to this voice's defaults. Unknown controls do not add
hidden synthesis behavior.

## Related families are separate hits

`ThumpFamilies.patches` accepts a SPARK `ThumpPatch` and 2–16 finite, unique,
strictly ascending positions in 0..1. The default positions are 0, 1/3, 2/3 and 1.
Each result stores the complete resolved macro map, so its ordinary patch JSON
can regenerate the audio without the source patch or a family sidecar.

For offset `position - 0.5`, the current shape law is:

```text
METAL += 0.70 × offset
COLOR += 0.70 × offset
DECAY -= 0.30 × offset
TUNE and PUNCH remain fixed
each value is clamped to 0..1
```

Position 0.5 preserves the resolved source settings. Near a macro boundary,
clamping deliberately reduces further movement. Family members remain ordinary
patches; this operation does not create a continuously moving macro inside a
hit. `ThumpFamilies.render` renders each patch and assigns its actual audio
classifier result. The SYNTH picker uses a nominal PERC tint. Normal app SEND
classifies the delivered audio before a new assignment or fresh replacement,
including the saved processing recipe. A pad reopened for editing retains its
existing identity under the app's established policy.

## Integration and the audition gate

SPARK is appended to `ThumpVoice`, so existing enum names and saved patches
keep their identity. `ThumpPresets.forVoice(SPARK)` returns an empty roster until
the owner has heard the voice. Defaults and SCRAMBLE still provide editable
starting points. Existing factory presets are unchanged.

The SYNTH picker receives the SPARK label and its existing THUMP macro/render
path. The native screen changes require an Android build where an SDK is
available. DE-SAMPLE deliberately excludes this prototype from its candidate
grid, preserving the original THUMP search points and tie order. Search
eligibility and factory presets follow a later listening verdict.

Render cost is linear in the sample count with a fixed number of oscillator
terms and filters. There is no solver or geometry cache to build. The playability
target is a warm one-shot render in tens of milliseconds on the target phone;
device timing remains pending. A four-hit family costs four independent renders
and should be generated as a batch, not inside the playback callback.

Generate the listening bundle with:

```sh
./gradlew --no-daemon :synth:generateThumpSparkAudition
```

The default destination is `testkit/thump-spark-audition/`; override it with
`-PsparkOutput=/absolute/folder`. The local `index.html` presents four expression
positions, each control at 0/0.25/0.5/0.75/1, three registers and the four
METAL × COLOR corners. PCM24 WAVs live under `audio/`, with both native THUMP
output and audition-matched copies: 36 settings, 72 WAVs, plus two family strips.
Matching applies one gain per clip through
`AuditionLevel`, targeting 0.03 RMS in the loudest 200 ms window, with a 0.99
peak guard. It does not change the patch recipes.

`recipes/` contains independently replayable `PadRecipe` JSON; `manifest.json`
and `metrics.csv` identify settings, classes, durations and render observations.
The manifest also hashes the renderer source files used to generate the bundle.
`family-native.wav` and `family-matched.wav` compare the four related hits in
sequence. `thump-spark-audition.zip` carries the listening bundle.
`thump-spark-mpc-kit.zip` carries a 16-pad XPM kit: four TUNE rows at
0.15/0.40/0.65/0.90 by four expression columns, with actual rendered classes and
complete recipes. MPC3/XTD export is outside this audition's scope. Device and
MPC hardware validation remain pending.

The listening pass should judge whether the related hits retain one identity
and whether dark and low-METAL settings already have useful character. The
render-rate convergence probe compares 4× delivery against an independently
downsampled 8× render. Its residual includes integration, filter phase and
resampling differences; it is not a pure alias-power measurement. Generator
timings are single warmed local JVM observations, not phone performance or a
repeatable benchmark.

MOVE is deferred. A later intra-hit trajectory would require its own time law,
phase/envelope behavior, deterministic recipe representation and listening
comparisons. R1 does not promise a live controller or a motion sequencer.

## As built and verification ledger

The JVM Kotlin production and test sources compile; this is not an Android
compilation result. The focused Gradle test run and
`:synth:generateThumpSparkAudition` completed with exit code 0. Its final-code
bundle contains 36 settings, 72 PCM24
WAVs, two family strips, independently replayable recipes and the 16-pad XPM kit.
Recipe replay and delivered WAV readback checks passed. The manifest's four
renderer source hashes match the current files, and both ZIPs pass CRC checks.
All 110 audio/recipe SHA hashes match the earlier final-code pack byte for byte.

All 90 focused tests passed with zero failures, errors or skips across six
classes: SPARK 7, families 5, THUMP 38, THUMP presets 6, DE-SAMPLE 5 and
determinism 29. Their measured corner, pitch and convergence observations are
recorded below. The eight modules outside `synth` completed 2,896 tests with zero
failures, errors or skips. Two environment restarts interrupted the full synth
runs before a final Gradle result. The full JVM suite is therefore **unverified**;
there is no global pass claim. CI must complete that regression gate. The saved
validation report is `thump-spark-validation.json` alongside the delivered bundle.

Final bundle observations from `manifest.json` and `metrics.csv`:

| Observation | Measured result | Limit of the claim |
|---|---:|---|
| Individual settings rendered | 36 | The audition matrix, not the full macro space. |
| Maximum native PCM sample peak | 1.0 | Finished output after THUMP's peak ceiling. |
| Maximum absolute native PCM mean | 0.00113048 | DC after PCM24 encoding, across the 36 delivered settings. |
| Local render observation median | 14.969 ms | One warmed JVM observation per setting, after the focused tests completed. |
| Local render observation range | 8.152–49.529 ms | Same limited run; not a benchmark or target-phone result. |
| Default family classes | PERC, PERC, PERC, PERC | Other settings can classify differently. |
| Default family durations | 0.560, 0.466, 0.388, 0.323 s | Successive default expression positions. |

Dedicated Kotlin probe observations from `ThumpSparkTest`:

| Probe | Measured result | Tested scope |
|---|---:|---|
| Maximum unfinished source peak | 0.8880578 | All 32 corners of the five-control space, before output conditioning. |
| Maximum absolute delivered mean | 0.00254330 | The same 32 corners, after THUMP's finish. |
| Maximum absolute root error | 2.948 cents | Nine TUNE × METAL conditions: 0/0.5/1 each, DECAY 1 and PUNCH 0; body read from 0.20 s over a 0.25 s window. |
| Minimum pitch confidence | 0.9107 | The same nine body-root measurements. |
| Maximum 4×/8× relative RMS residual | 0.00597116 | TUNE 0/0.5/1, METAL 1, COLOR 1 and PUNCH 0; render-rate convergence includes filter and resampling differences. |

These numbers describe measured DSP behavior. They do not establish that the
macro travel or the resulting percussion is musically convincing.

The voice has no fixed-class promise throughout its tuning range. Exported
family pads and newly sent app pads use their actual delivered audio class;
the picker tint remains nominal.

| Claim | Status | Evidence |
|---|---|---|
| Existing THUMP preset dispatch, rendering and SYNTH screen integration points exist | Verified by source inspection | Baseline `3ecbdb78`; `Thump.kt`, `ThumpPresets.kt`, `SynthScreen.kt`, `Desample.kt`. |
| The two references separate source synthesis from per-hit expression | Verified research | Maker-authored manuals and page references above. |
| SPARK uses the oscillator/edge/filter structure above | Built; verified by source inspection | `Thump.kt` re-read after implementation, including SPARK's scoped conditioning and common THUMP finish. |
| Family recipes use resolved settings and actual render classification | Built; final-code replay verified | `ThumpFamilies.kt` and the generator's successful recipe/readback checks. |
| Normal app SEND classifies delivered SPARK audio for new or fresh assignments | Built; verified by source inspection | `SynthScreen.kt`; reopened-pad identity policy remains in effect. |
| JVM Kotlin production and test sources compile | Verified | Successful focused Gradle session; Android compilation remains pending. |
| Final PCM24 audition bundle and 16-pad kit export | Verified by Gradle generator execution | `:synth:generateThumpSparkAudition` exit code 0; four renderer hashes and 110 audio/recipe hashes match; both ZIP checks pass and the kit contains 16 WAVs. |
| Final audition peak, DC, class and timing observations | Measured | Final bundle `metrics.csv`; scoped results above. |
| Focused synthesis and integration regression checks | Passed | 90 tests across six classes, zero failures/errors/skips; includes all seven dedicated SPARK checks and family, THUMP, preset, DE-SAMPLE and determinism coverage. |
| Full JVM regression suite and legacy compatibility | Unverified | Eight modules complete: 2,896 tests, zero failures/errors/skips. Two environment restarts interrupted the full synth runs; CI must finish. No global pass claim. |
| Render time meets the target on an Android device | Proposed target | Device benchmark pending. |
| SPARK has useful character and each family retains one identity | Not heard | Owner's audition verdict pending. |

## Why it is built this way

- The sine body gives the hit a clear center. Removing it would leave the most
  metallic settings with less pitch and weight to relate back to the soft ones.
- The separate edge can brighten and die away faster without taking the body
  with it. One shared filter would make that relationship harder to control.
- Phase modulation and multiplication produce different spectral changes.
  Keeping them bounded lets METAL add character without requiring a large
  collection of synthesis controls.
- Related patches retain the expressive gesture after export: every hit can be
  edited and regenerated independently. A live wheel would require a different
  playback architecture.
- The risky decision is how much high-frequency edge remains useful at high
  tuning. Render-rate convergence and the matched audition decide that range; the design
  description cannot settle it.
