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
After merging the newer development branch, 31 focused native/registry/recipe
checks passed, including the EROSION determinism canary. The 64 shell convention
checks also passed after keeping the native-proof note outside the README's
machine-read engine list. Total final focused checks: 95, zero failures/errors.

Across the 68 generated native cases, every class was PERC. Maximum native
sample peak was .99000007 and maximum absolute mean .00104292 before PCM
encoding. The median single local render observation was 7.40 ms. This is not
a repeatable benchmark or a target-phone result.

The required full JVM command was launched and interrupted before completion
so the current development branch could be merged into the draft without
changing files under a running build. It is explicitly unverified: no full-suite
pass, Android compilation or release-readiness claim is made. The native change
is kept as a draft for review. The repository documents full suite durations
exceeding an hour on CI. Full JVM completion is still required before merging.
The merged development changes only add a design document and adjust an Android
test; all five native renderer source hashes remain unchanged.
Owner-heard evidence: positive feedback on the first Python audition. Kotlin
listening, phone performance and MPC hardware validation remain open.

## Playing bank and SCAR study, 2026-11-02

Status: provisional playing recipes and a separate native third-voice experiment.
Baseline read: `e4e7b29320da3af052715268319aeff1c2b1514d`. The first native PR
was merged as #781 before this work. ARC and CRATER's render path is preserved.
No new bank or SCAR listening acceptance is claimed.

### What earns a pad

The sixteen-pad bank uses eight ARC and eight CRATER recipes. Short ticks,
closed textures, wire accents, open dust and low/mid/high craters have complete
five-control snapshots in `ErosionBank`. Each has native touch at .35, .65 and
1.0. Names describe intended roles; they remain hypotheses for the next listen.
The bank is separate from the global factory preset roster and app picker.

The nearest synthesis siblings remain THUMP's oscillator-based SPARK and SKIN's
noise hats/shaker. WICK is a timing sibling: its pitched low-pass-gate release
cascade does not provide EROSION's bounded percussive charge schedule. The
selected SCAR direction extends the event schedule by withholding energy until
a second discharge phase. The user selected this direction after the bank
recommendation; additional voice directions are outside this round.

### SCAR gesture and mechanism

The characteristic gesture is initial crack → clustered sparks → recovery gap
→ quieter aftershock. The event model literally runs; “scar” and material
removal are analogies. There is no erosion mesh, plasma, fluid or heat solver.

```text
one unit of energy
   | .30 initial crack + .58 cluster supply
   v
primary events -> existing spark/fluid/collapse readout
   |
   | no events during recovery; existing sound loses energy
   |
   + .12 held reserve -> aftershock events -> same readout
```

All constants below are shape choices. SCAR begins with a .30-energy event at
t=0. The remaining .70 stays in the reservoir. A .58 supply budget transfers
exponentially into the capacitor during the initial cluster. A .12 budget is
withheld until the aftershock. Each threshold firing releases 90% of accumulated
capacitor energy. Undischarged energy stays in reservoir/capacitor accounting:
`reservoir + charge + emitted = 1` remains the invariant. No pulse is normalized
separately and no extra energy is added for the aftershock.

FEED sets cluster end to `.025 + .100*FEED` seconds, recovery duration to
`.095 + .100*FEED`, and aftershock duration to `.048 + .088*FEED`. Each supply
phase uses six exponential time constants over its declared duration. The
recovery interval exceeds the longest collapse kernel and lets the primary
sound die away. At defaults the cluster ends at 60 ms and the aftershock begins
at 190 ms. The aftershock-off diagnostic holds the reserve without releasing it;
all primary events remain identical. An acoustic comparison passes both sources
through the same output filters and one shared gain. It omits global mean
subtraction and independent level matching, which would otherwise alter the
shared primary. Because offline sinc resampling has a short look-ahead, the
bit-identical delivered-prefix check ends 5 ms before aftershock onset.

SCAR defaults are TUNE .45, VOLTAGE .45, FEED .35, FLUID .65 and SLAG .35.
TUNE/FLUID/SLAG retain their existing readout laws. VOLTAGE retains the discharge
threshold and refractory-time laws. FEED now coordinates cluster, recovery and
aftershock timing. No extra macro is needed. Macro interactions are auditioned
through the existing material/energy controls; the generic macro/corner tests
also cover the new voice. HOLD is still unavailable. The finite bank and SCAR
study complete this round; sustained loops remain a later design.

### Playable export and musical evidence

`generateErosionBank` creates a dry sixteen-pad program for MPC 3 (`.xtd`) and
MPC 2 (`.xpm`) sharing 48 PCM24 samples, with three velocity zones per pad:
1–41, 42–84 and 85–127. Velocity zero is note-off. Soft and medium native touch
renders are peak-matched to their hard render using `Velocity.peakMatch`; the
hardware then applies its velocity-level curve. Native clips in the listening
page keep their ordinary patch output level. Every native layer has a complete
`PadRecipe` JSON and is replay-checked. Exported soft-zone recipes reproduce
the native touch before peak matching; the hard patch and explicit layer policy
in the sidecar provide the reference needed to rebuild the zone level.

Both program formats are checked by `ExportReadBack`, and every encoded export
layer is checked against the corresponding native/peak-matched WAV. The kit
sidecar round-trips. The four-bar 112 BPM demonstration selects the actual
exported WAVs using their recorded velocity windows, then uses a documented
linear velocity/127 gain and one master gain. It is a score demonstration, not
a simulation of an MPC's analog output or hardware velocity curve. No external
samples or effect chain is included. MPC hardware loading remains unverified.

SCAR stays outside the sixteen-pad ARC/CRATER bank. Its page provides the native
default, shorter/longer recovery and an aftershock on/off comparison at shared
gain, with event times and energy in `scar-diagnostics.json`.

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.ErosionTest \
  --tests com.snipsnap.synth.ErosionBankTest
./gradlew --no-daemon :synth:generateErosionBank
```

Output is `testkit/erosion-playable-bank/`; it is ignored generated data. The
existing generator and previously published reference files are retained.
Rendering remains offline mono 44.1 kHz with 4× internal DSP. SCAR has a fixed
8 kHz event clock, two finite supply phases, four filters and one cached-per-
render collapse kernel. No iterative solver or new shared-DSP toolkit is
introduced. Phone render time remains a tens-of-ms target, not a measurement.

### Verification ledger for this round

- Verified by source inspection: existing ARC/CRATER laws, patch/velocity
  registration, native rendering and kit/velocity-zone export APIs on the
  baseline above. SCAR reuses the same saved patch shape and readout.
- Measured before DSP edits: 198 frozen ARC/CRATER float renders, covering
  defaults and every macro corner at three touch levels. Their hashes also
  match when replayed through Java 17, as well as the original Java 21 run.
- New measurements: focused bank/voice tests and export-generation result are
  recorded with the delivered `validation.json`; no full-suite pass is inferred
  from those results.
- Proposed: pad roles, SCAR defaults, musical value of the third gesture and
  usefulness of the three touch zones.
- Heard: the owner's positive first Python-round feedback. The new playing bank
  and SCAR study await listening. Android/MPC hardware and seamless HOLD are
  unverified.

### Why it is built this way

- A playing bank gives the existing voices repeatable jobs on sixteen pads.
- The native touch layers preserve the discharge pattern while changing contact.
- SCAR spends a held reserve after a real recovery interval, so its new ending
  changes the gesture rather than simply changing the filter.
- Original-voice hash checks guard the sound already delivered. SCAR's reserve
  ablation proves that its primary gesture does not depend on the aftershock.
- The main listening risk is whether the aftershock adds musical punctuation or
  becomes an unwanted second hit. The shared-gain comparison is built to judge it.

### As built for the playing round

The final merged baseline is `406b9be3a00851c612d8f3c43bcdb4ed67548617`.
All 78 focused tests passed on that tree (including source, bank, legacy render
protection, patch dispatch, the EROSION canary and shell conventions), and the
bank generator completed with exit code 0. It produced 48 native layer recipes,
53 audition clips, an exported-sample phrase, 16 pads with 48 MPC sample files,
both MPC program formats, read-back reports and the separate SCAR study.

All 198 frozen ARC/CRATER float-render comparisons passed. The same hashes
were independently reproduced on Java 17 and Java 21. Across 96 corners, the
maximum raw peak was 1.317485 and maximum delivered absolute mean .00253235.
The energy-ledger error stayed below 6.67e-16. SCAR's bright-corner 4x/8x
matched-chain relative RMS residual was .001632 with SLAG 0 and .006603 with
SLAG 1; these are convergence observations, not pure alias-power measurements.

At the default SCAR setting, the first 50 ms aftershock window has .1362 times
the raw source power of the first 50 ms attack window, about -8.66 dB. The
primary source is identical with the reserve held or released. After the common
output chain, the shared comparison is bit-identical through 184.99 ms, with
a 5 ms guard before aftershock onset for offline resampling look-ahead.

Both MPC read-back checks passed, all 48 encoded zone samples match their
expected native/peak-matched renders, and every native layer recipe replays
exactly. Native playing clips range up to .8333 seconds, with maximum absolute
mean .00110706 before PCM encoding. The full JVM suite is attempted separately
and remains unverified until its final exit code is recorded. The new bank,
SCAR and physical MPC loading still await the owner's listening/hardware pass.
