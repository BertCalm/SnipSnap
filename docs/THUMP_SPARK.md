# THUMP SPARK and related hit families

Status: revision 2 built; 113 focused JVM tests and the final audition generator
pass with exit code 0. The owner heard insufficient control movement in R1;
the revised sound still awaits the owner's listen. The full JVM suite is running
and has no final pass result. Repository baseline read: `fd791f58` (2026-10-26).
This remains one THUMP voice. Android code is unchanged in this round; Android
compilation and phone/MPC hardware checks remain pending.

The characteristic gesture is strike → metallic flare → falling pitch → rounded
body. A related family moves from darker, longer hits toward brighter, more
metallic, shorter hits as separate editable snapshots.

## What the first audition established

The owner reported no audible PUNCH movement, and weak METAL/COLOR movement
except when both reached 1. That is the revision 1 listening result. Passing
numerical safety checks did not establish useful control travel.

The implementing session's matched revision 1 audit measured these attack/body
ratios. Each compares the RMS of 0–10 ms with 10–50 ms; one gain applied to the
complete clip cancels out of that ratio.

| PUNCH | 0 | 0.25 | 0.5 | 0.75 | 1 |
|---|---:|---:|---:|---:|---:|
| R1 attack/body ratio, dB | 3.442 | 3.647 | 3.748 | 4.064 | 4.835 |

The total measured range was only 1.393 dB. The first 40 ms power centroid also
showed how much the two timbre controls depended on their combined extreme:

| Revision 1 setting | Early power centroid |
|---|---:|
| Default: METAL 0.25, COLOR 0.5 | 246 Hz |
| METAL 1, COLOR 0.5 | 313 Hz |
| METAL 0.25, COLOR 1 | 254 Hz |
| METAL 1, COLOR 1 | 397 Hz |

These rounded baseline measurements support the owner's report; they are not a
new listening judgment. Revision 2 gives PUNCH a longer source articulation,
gives COLOR body harmonics to filter even when METAL is 0, and raises the edge's
authority at ordinary METAL settings. Its regression probes cover early
normalized spectra and each quarter step of PUNCH, with an audition still
required to judge the result.

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
SPARK combines a pitched harmonic body with an independently shaped metallic edge,
then exposes a repeatable relationship between separate hits. A new engine
registry, sequencer or general modulation matrix would not improve this first
proof.

```text
TUNE, DECAY, PUNCH, METAL, COLOR (constant during each hit)
                         |
                         v
       oscillator plan + source PUNCH attack/body envelope
                         |
       +-----------------+-------------------------+
       |                                           |
       v                                           v
  seven-partial body                     phase-modulated edge
  fundamental / harmonic decay           + sine × triangle ring pair
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

The following revision 2 mappings match the frozen `Thump.spark` implementation
used for the passing focused run and regenerated audition. Their constants
remain sound-design choices awaiting the owner's listening verdict.

The body uses seven finite partials with weights
`[1, 0.9, 0.8, 0.65, 0.5, 0.4, 0.3]`, divided by their sum. The fundamental
decays at body t60; upper harmonics decay at 0.90 times body t60. This gives
COLOR spectral material while letting the tail settle toward the root. Body
gain is `0.80 - 0.28 × METAL`; it remains present as METAL rises.

The edge combines sine phase modulation with ring modulation against a triangle
source at a ratio derived from √2. The triangle uses seven odd harmonics. The
phase-modulation index starts at `1.5 + 6 × METAL`, up to 7.5 before its decay
envelope. Edge gain is `0.95 × sqrt(METAL)`. Its interaction remains 0.65 phase
modulation plus 0.35 ring product. These gains and indices are shape choices.

Separate `Dsp.TptSvf` low-pass filters treat the body and edge independently.
COLOR moves body cutoff from 0.9 to 9 times the root and edge cutoff from 1.5
to 24 times the root. The edge cutoff then follows
`0.60 + 0.40 × modulation envelope`, retaining spectral motion as it decays.
COLOR no longer relies on a direct edge-gain multiplier.

Decay envelopes precede the filters. Edge t60 is
`body t60 × (0.55 - 0.10 × METAL)`; modulation t60 is 0.75 times body t60.
METAL also sets the initial pitch excess above the root through
`bendDepth = 0.08 + 0.92 × METAL`. COLOR sets that pitch fall's time constant
through `bendTime = 0.012 + 0.012 × COLOR` seconds. The oscillator phase uses
the exact integral of this exponentially falling frequency. There is no
recursive oscillator feedback or event solver.

PUNCH now acts in the source as well as through the existing THUMP finish. Its
raised-cosine rise moves exponentially from 18 ms at 0 to 0.35 ms at 1. Both
branches receive that rise times
`0.25 × (1 + 3 × PUNCH × exp(-time / 0.018))`. This changes the attack/body
relationship over a longer span of time; the fixed quarter scale bounds
unfinished gain before later level matching. The new PUNCH regression compares
independently defined 0–10 ms and 10–50 ms windows, so a uniformly louder copy
cannot pass it.

Only SPARK receives its new band-limit and DC-conditioning stages before the
shared THUMP finish. Existing voices keep their existing rendering path. The
finish follows THUMP's drum-specific PUNCH behavior, rather than replacing it
with the pitched-engine loudness stage described in the general conventions.

## Controls

| Control | Default | Changes | Required interaction / proof |
|---|---:|---|---|
| TUNE | 0.50 | Body root/partials, edge/modulator frequencies and both root-relative filter cutoffs | TUNE × METAL must retain a usable pitched body across the register. |
| DECAY | 0.35 | Fundamental, harmonic, edge and modulation t60 values, plus render duration | DECAY × METAL must change how the edge gives way to the body; duration increases with DECAY. |
| PUNCH | 0.50 | Source attack duration, 18 ms attack/body gain envelope and THUMP's existing finish shaping | Passed at least 1 dB per quarter step and 6 dB overall at METAL 0 and 0.25; measured full ranges 19.38 and 20.01 dB. |
| METAL | 0.25 | Body/edge gain balance, phase-modulation depth, ring contribution, initial pitch-drop depth and edge t60 ratio | Passed early normalized spectral movement at low/middle values with COLOR 0.25 and 0.5, plus scoped post-attack off-harmonic growth. |
| COLOR | 0.50 | Independent body/edge low-pass cutoffs and pitch-fall time constant | Passed quarter-step early centroid movement at METAL 0 and 0.25; measured full ratios 2.356× and 2.289×. |

Revision 2 checks establish scoped early spectral movement and envelope contrast.
They do not establish musical sufficiency. The listening pass must judge low and
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
the revision passes its listening gate. Defaults and SCRAMBLE still provide editable
starting points. Existing factory presets are unchanged.

The SYNTH picker receives the SPARK label and its existing THUMP macro/render
path. Revision 2 does not change Android code. Android compilation and hardware
checks remain separate from JVM validation. DE-SAMPLE deliberately excludes this prototype from its candidate
grid, preserving the original THUMP search points and tie order. Search
eligibility and factory presets follow a later listening verdict.

Render cost is linear in the sample count with a fixed number of oscillator
terms and filters. There is no solver or geometry cache to build. The playability
target is a warm one-shot render in tens of milliseconds on the target phone;
device timing remains pending. A four-hit family costs four independent renders
and should be generated as a batch, not inside the playback callback.

Generate the listening bundle with:

```sh
./gradlew --no-daemon :synth:generateThumpSparkAudition -PsparkBaseline=/path/to/preserved-r1-audition
```

The default destination is `testkit/thump-spark-audition/`; override it with
`-PsparkOutput=/absolute/folder`. The local `index.html` presents four expression
positions, each control at 0/0.25/0.5/0.75/1, three registers and the four
METAL × COLOR corners. Revision 2 also sweeps COLOR with METAL 0 and METAL with
COLOR 0.25. The generated matrix contains 46 settings, 92 native/matched PCM24
WAVs, plus two family strips.
With a preserved revision 1 baseline, the page adds 15 matched R1/R2 comparisons:
five positions each for PUNCH, METAL and COLOR, holding every other control fixed.
The original baseline assets and page are copied under `previous/`, with their
hashes checked. Current audio and recipe filenames have an `r2-` prefix to avoid
reusing cached R1 assets. Re-rendering an old recipe through new DSP would not preserve
the old sound. Omit `sparkBaseline` only when comparisons are not required.
Matching applies one gain per clip through
`AuditionLevel`, targeting 0.03 RMS in the loudest 200 ms window, with a 0.99
peak guard. It does not change the patch recipes.

`recipes/` contains independently replayable `PadRecipe` JSON; `manifest.json`
and `metrics.csv` identify settings, classes, durations and render observations.
The manifest also hashes the renderer source files used to generate the bundle.
`family-r2-native.wav` and `family-r2-matched.wav` compare the four related hits
in sequence. `thump-spark-r2-audition.zip` carries the listening bundle.
`thump-spark-r2-mpc-kit.zip` carries a 16-pad XPM kit: four TUNE rows at
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
comparisons. Neither revision promises a live controller or a motion sequencer.

## Revision 2 verification ledger

The focused Gradle run completed 113 tests across seven classes with zero
failures, errors or skips: SPARK 10, families 5, THUMP 38, THUMP presets 6,
DE-SAMPLE 5, PUNCH 6 and determinism 43. The final 46-clip generator also
completed with exit code 0. The published R2 manifest contains all 15 R1/R2
comparisons, and its four renderer source hashes match the current source.
Both ZIPs pass CRC checks. These are JVM results; Android was not built.

The harmonic body requires tests that distinguish its normal harmonics from
METAL's inharmonic edge. The post-attack METAL probe reads 60–160 ms, ignores
frequencies below 40 Hz and excludes bins within ±8% of the nearest integer
harmonic. Its quarter-step off-harmonic power increments exceed the retained
0.002 target in the specified range. COLOR's upper-band probe requires an
absolute increase of at least 0.075 in normalized upper power per tested step,
or 7.5 percentage points; a relative 3 dB test on R1's small upper-band floor
would measure a different property. The new early-spectrum and PUNCH authority
gates are retained alongside these mechanism-specific checks.

| R2 probe | Measured result | Tested scope |
|---|---:|---|
| Maximum unfinished source peak | 0.9199232 | All 32 corners of the five-control space. |
| Maximum absolute delivered mean | 0.00240994 | The same 32 corners, after THUMP's finish. |
| Maximum absolute root error | 5.34122 cents | Nine TUNE × METAL conditions: 0/0.5/1 each, DECAY 1 and PUNCH 0; body read from 0.20 s over a 0.25 s window. |
| Minimum pitch confidence | 0.8065 | The same nine root measurements. |
| Maximum 4×/8× body residual, PUNCH 0 | 0.0171150 relative RMS | TUNE 0/0.5/1, METAL 1, COLOR 1; first 10 ms excluded. |
| Maximum 4×/8× residual, PUNCH 1 | 0.0243008 relative RMS | Same tuning/timbre positions, including the onset. This includes filter/resampling differences, not pure alias power. |
| PUNCH complete attack/body range | 19.384 / 20.014 dB | METAL 0 / 0.25, COLOR 0.5; 0–10 ms versus 10–50 ms RMS. Every quarter step exceeds 1 dB. |
| COLOR complete early-centroid ratio | 2.356× / 2.289× | METAL 0 / 0.25, PUNCH 0.5; normalized first-80-ms power spectra. |
| Minimum adjacent early METAL spectral distance | 0.3019 | METAL 0/0.25/0.5/0.75 at COLOR 0.25 and 0.5, PUNCH 0.5; normalized first-80-ms spectral amplitudes. |

| R2 delivered audition observation | Measured result | Limit |
|---|---:|---|
| Current settings / matched R1–R2 comparisons | 46 / 15 | Preserved baseline included; two current family strips and 16-pad XPM kit. |
| Maximum native PCM sample peak | 1.0 | Finished output after THUMP's peak ceiling. |
| Maximum absolute native PCM mean | 0.00073667 | Across the 46 PCM24 settings. |
| Local render observation median | 15.900 ms | Single warmed local JVM observation per setting, not a phone benchmark. |
| Local render observation range | 10.001–54.701 ms | Same limited run. |

The complete JVM suite remains running without a final result. The focused pass
does not establish global regression success. The saved current report is
`spark-r2-validation.json`; listening, Android compilation and hardware checks
remain separate gates.

| Claim | Status | Evidence |
|---|---|---|
| Owner heard inadequate PUNCH and weak ordinary METAL/COLOR travel in R1 | Heard by owner | Report above; this is the reason for revision 2. |
| R2 source PUNCH articulation, harmonic body, separate filters and stronger edge balance | Built; source inspected | Frozen `Thump.spark` matches the current manifest's renderer hash. Constants remain shape choices until listening. |
| R2 focused numerical and integration regressions | Passed | 113 tests in seven classes, zero failures/errors/skips; includes early-spectrum, quarter-step PUNCH, corner, pitch and render-rate checks. |
| R2 audition, preserved R1 baseline and 15 matched comparisons | Generated and verified | 46 revised settings; generator exit code 0; matching source hashes and clean ZIP CRC checks. |
| Full JVM regression suite | Running; no final result | Focused success does not establish a full pass. |
| Android compilation and phone/MPC hardware checks | Pending | No Android code changes in this round; JVM checks cannot establish device behavior. |
| R2 control travel and useful sound character | Not heard | Owner's revision 2 audition verdict pending. |

## Revision 1 historical validation

Everything in this section describes the earlier source and audition. These
results remain useful as a baseline and do not establish revision 2 behavior.

For revision 1, the JVM Kotlin production and test sources compiled; that was
not an Android compilation result. Its focused Gradle test run and
`:synth:generateThumpSparkAudition` completed with exit code 0. Its final-code
bundle contains 36 settings, 72 PCM24
WAVs, two family strips, independently replayable recipes and the 16-pad XPM kit.
Recipe replay and delivered WAV readback checks passed. Its four renderer
source hashes matched the R1 files at export, and both ZIPs passed CRC checks.
All 110 audio/recipe SHA hashes matched the earlier R1 pack byte for byte. These
are historical integrity checks; the R1 renderer hashes do not match R2.

All 90 focused tests passed with zero failures, errors or skips across six
classes: SPARK 7, families 5, THUMP 38, THUMP presets 6, DE-SAMPLE 5 and
determinism 29. Their measured corner, pitch and convergence observations are
recorded below. The eight modules outside `synth` completed 2,896 tests with zero
failures, errors or skips. Two environment restarts interrupted the full synth
runs before a final Gradle result. That session left the full JVM suite
**unverified**, with no global pass claim. The saved R1 validation report is
`thump-spark-validation.json`; the new run is tracked in the R2 ledger above.

Revision 1 bundle observations from its `manifest.json` and `metrics.csv`:

| Observation | Measured result | Limit of the claim |
|---|---:|---|
| Individual settings rendered | 36 | The audition matrix, not the full macro space. |
| Maximum native PCM sample peak | 1.0 | Finished output after THUMP's peak ceiling. |
| Maximum absolute native PCM mean | 0.00113048 | DC after PCM24 encoding, across the 36 delivered settings. |
| Local render observation median | 14.969 ms | One warmed JVM observation per setting, after the focused tests completed. |
| Local render observation range | 8.152–49.529 ms | Same limited run; not a benchmark or target-phone result. |
| Default family classes | PERC, PERC, PERC, PERC | Other settings can classify differently. |
| Default family durations | 0.560, 0.466, 0.388, 0.323 s | Successive default expression positions. |

Revision 1 Kotlin probe observations from `ThumpSparkTest`:

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
| R1 used a sine body with a separately filtered metallic edge | Built; verified by source inspection at R1 | Original `Thump.kt`, scoped conditioning and common THUMP finish; superseded by R2's harmonic body and source PUNCH. |
| Family recipes use resolved settings and actual render classification | Built; final-code replay verified | `ThumpFamilies.kt` and the generator's successful recipe/readback checks. |
| Normal app SEND classifies delivered SPARK audio for new or fresh assignments | Built; verified by source inspection | `SynthScreen.kt`; reopened-pad identity policy remains in effect. |
| JVM Kotlin production and test sources compile | Verified | Successful focused Gradle session; Android compilation remains pending. |
| R1 PCM24 audition bundle and 16-pad kit export | Verified by its Gradle generator execution | `:synth:generateThumpSparkAudition` exit code 0; four renderer hashes and 110 audio/recipe hashes matched R1; both ZIP checks passed and the kit contained 16 WAVs. |
| R1 audition peak, DC, class and timing observations | Measured | Preserved R1 bundle `metrics.csv`; scoped results above. |
| Focused synthesis and integration regression checks | Passed | 90 tests across six classes, zero failures/errors/skips; includes all seven dedicated SPARK checks and family, THUMP, preset, DE-SAMPLE and determinism coverage. |
| R1 full JVM regression suite and legacy compatibility | Unverified in that session | Eight modules completed 2,896 tests, zero failures/errors/skips. Two environment restarts interrupted its full synth runs. No global pass claim. |
| Render time meets the target on an Android device | Proposed target | Device benchmark pending. |
| R1 control travel is musically sufficient | Rejected by owner's listen | PUNCH had no audible movement; METAL/COLOR were weak except at their combined maximum. |

## Why it is built this way

- The harmonic body gives COLOR something to filter while retaining a clear
  fundamental. A sine-only body left much of COLOR's travel with little effect.
- The separate edge combines phase modulation and multiplication for different
  spectral changes, then brightens and dies away without taking the body with
  it. One shared filter would make that relationship harder to control.
- PUNCH changes source attack timing and a longer attack/body envelope. The
  inherited finish alone passed safety checks but did not move enough in the
  owner's first audition.
- Related patches retain the expressive gesture after export: every hit can be
  edited and regenerated independently. A live wheel would require a different
  playback architecture.
- The risky decision is how much high-frequency edge remains useful at high
  tuning. Render-rate convergence and the matched audition decide that range; the design
  description cannot settle it.
