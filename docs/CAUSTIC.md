# Caustic: finite Alloy instrument

Caustic turns an ordinary harmonic tone into a position-dependent singing body
through reflected paths. The finite native milestone is implemented: one ALLOY
voice, editable SOURCE/FOCUS/BODY/LENGTH controls, saved MIDI and velocity,
eight starting patches, and a dry sixteen-pad kit for both MPC formats. The
owner heard and approved the fuller-body direction after finding the Bloom/Deep
tail variants indistinguishable. Earlier listening studies remain available
for comparison; the broader moving-geometry design remains later work. The
working name has not been checked for release.

## Listen and reproduce

The [native instrument audition](https://caustic-snipsnap-audition.bertcalm.chatgpt.site/instrument/)
provides twenty finite recordings, Native and Level matched playback, eight
starting patches, portable JSON recipes, a compact comparison download and the
sixteen-pad starter kit. The browser plays exported recordings; it does not
render control changes in real time. Generate the native artifacts with:

```text
./gradlew --no-daemon :synth:generateCausticInstrumentAudition
./gradlew --no-daemon :synth:generateCausticKit
```

These tasks write `testkit/caustic-instrument-audition/` and
`testkit/caustic-kit/`. Native playback is the saved patch's complete house
output; Level matched copies apply `AuditionLevel` once for comparison.

The [archived private audition](https://caustic-snipsnap-audition.bertcalm.chatgpt.site/)
retains the earlier circular studies on ChatGPT Sites: a suggested listening
order, a depth-and-character comparison, the original position studies, mechanism
controls, register/velocity probes and local notes.

```text
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Caustic*'
./gradlew --no-daemon :synth:generateCausticAudition
./gradlew --no-daemon test
```

The generator writes `testkit/caustic-audition/`: 48 finite notes, shared-gain
and independently matched PCM24 WAVs, complete JSON recipes, measurements and
source fingerprints. All 44 recordings from the first three studies retain their
IDs and bytes. Separate original, depth, Alloy-bloom and Alloy-body ZIPs keep each download
compact. Use a local server or published page for decoded Web Audio playback.
The downloaded pack also supports local media playback. Repeat replays a finite
recording; it is not a HOLD or seam test.

In the archived studies, use **Level matched** to compare tone and **Shared gain**
to hear collection strength and velocity. One native export gain applies within each renderer
revision; the original pack keeps its original gain, including the source. No
per-position compensation or rack FX runs inside synthesis. Matched exports use
the existing `AuditionLevel` loudest-200-ms rule.

## Archived models and recipes

`Caustic.Config` stores SOURCE, FOCUS, MIDI, velocity, duration, aperture,
musical delay scale, ray count, renderer model and material character. Model 1
retains the first renderer and its original version-1 recipe bytes. Model 2
adds explicit BODY, REED and ALLOY choices; their recipes record the choice.
`CausticRecipe` adds an explicit diagnostic variant in strict, versioned JSON.
Replay a recipe with `CausticRecipe.fromJsonText(text).inspect()`. This
standalone prototype is outside the production `Patch` registry.

The circular boundary uses half-step, equal-arc quadrature and an explicit
transparent entrance: only interior-facing reflection points are illuminated.
Launch weights normalize before reception; nine pickups share a fixed budget.
SOURCE uses inverse distance with an exact parallel limit. FOCUS moves the same
band for all sources. Forward segments stop at the next circle boundary; a
smooth finite aperture bounds reception. Nonnegative density, coherent signed
audio and the mathematical envelope are separate quantities.

Up to twelve band-limited harmonics enter a finite envelope with a 12 ms attack
term and 1.1 s exponential decay. Velocity changes effort and harmonic slope
before excitation. Relative incident distance and forward path length determine
phase and envelope delay. `delayR * delayCycles / noteHz` is a musical scale,
not a physical cup dimension or measured speed of sound.

The two-exponential source permits causal coefficient events that preserve each
static path's delayed envelope and carrier phase without visiting every path at
every audio sample. Independent direct sums verify this representation,
including the lowest supported MIDI note at maximum delay scale. Moving paths
and arbitrary envelopes need separate validation.

In model 1, fixed pickup losses and signed participation feed sixteen shared,
uncoupled `Modes.Bank` resonances: twelve harmonic modes and four quiet
near-harmonic companions. Material gains are fixed; FOCUS adds no independent
oscillators. A quiet direct root supports sparse/canceling positions. Rendering
uses 4x processing, existing antialias filtering, decimation, DC removal and a
short finite-tail fade, yielding mono 44.1 kHz samples. Stable poles and
normalized launch weights do not establish acoustic energy conservation.

## Depth and character

Model 2 uses twenty-four modes: twelve main resonances and twelve nearby
sympathetic partners. A short transient and a longer tonal envelope illuminate
the same paths; each uses an exact causal delayed representation. The material
choices change excitation, spatial participation and resonant
frequency/lifetime relationships inside the vessel. BODY explores a fuller
low-mid response and slower bloom. REED emphasizes hollow harmonic colour and
nearby beating resonances. ALLOY explores a brighter, slightly inharmonic ring.
All retain the same circular reflection geometry, coherent path delays and
requested pitch. They are alternative listening candidates rather than a final
factory preset bank.

The first comparison holds note, effort, SOURCE and FOCUS constant against the
original. Register and pickup probes test whether each character carries beyond
one favourable note. Use matched playback for colour, and shared gain to assess
native balance. No external effects or room reverb create the added body.

## Alloy listening direction

Alloy was selected as the strongest direction in the second audition. Its
model-2 recordings remain the reference for the next pass, alongside the other
original recordings and their listening notes.

The Alloy bloom pass keeps the excitation, tuning, root balance and primary
resonances fixed. Model 3 adds one explicit `bloom` value: it lengthens the
companion resonances for partials 2–7 by up to 1.7 times their original T60. At
zero bloom it reproduces model 2 exactly. Half and full bloom provide two
controlled comparisons at C2, C4, C6 and a sparse pickup position. The new
notes ran for eight seconds to expose the late ring.

The owner found Bloom and Deep indistinguishable in normal listening.
Subsequent checks confirmed almost identical main-note spectra: their larger
relative changes occurred in a quiet tail. These recordings remain archived;
tail-energy percentages alone do not establish an audible choice.

Model 4 offers one fixed Fuller body comparison against the preferred Alloy.
It retains the metal transient, modal frequencies, gains, spatial routing and
lifetimes, but changes tonal excitation to a 35 ms attack, 1.5 s decay and
three times the force. This targets the audible body at 0.15–1.5 seconds. Tests
and exports compare normalized spectra and gain-fitted waveform differences in
that window, alongside pitch, headroom and exact legacy preservation.

The existing source and pickup controls still determine coherent excitation.
No output reverb or extra oscillators create this contrast. Model 4 requires
ALLOY and zero bloom; recipe schema 4 stores that explicit fixed model choice.

## Native finite instrument contract

The characteristic gesture is **metallic strike → tonal body grows beneath it
→ position-dependent ringing → finite release**. The core uses literal circular
reflection and coherent delayed excitation; it does not simulate a measured
metal vessel. Modal frequencies, material gains and musical path-delay scaling
are sound-design choices. MERCURY changes a body's response through water
loading and REVEL mixes a circular ensemble through moving microphones;
Caustic's distinguishing controls move source illumination and coherent pickup
paths through one shared body.

The first native voice is ALLOY. `CausticInstrument` is the production control
and rendering API; `CausticPatch` stores the name, voice, complete macro map,
explicit MIDI root, excitation velocity and native model version. Native model
1 is a separate format from the archived `CausticRecipe` models 1–4. Archived
recipes retain their original interpretation and files.

| Control | Default | Model quantity |
| --- | --- | --- |
| SOURCE | .50 | Source distance/illumination, including the exact parallel limit. |
| FOCUS | .19 | Position of the nine finite pickups; paths retain signed phase and delay. |
| BODY | 1 | Tonal attack, decay and drive between preferred Alloy and Fuller body; the short metal exciter remains fixed. |
| LENGTH | 1 | Half-second to eight-second finite gesture; tonal and modal decay scale together. |
| HOLD | 0 | Reserved. Positive values are refused until sustained excitation and an independently checked loop exist. |

The phone's ROOT and VELOCITY controls are saved outside the macro map. The
first native range is C2–C6 (MIDI 36–84); lower legacy diagnostic notes do not
establish a native playable range. BODY=0, LENGTH=1 retains the preferred
Alloy excitation. BODY=1, LENGTH=1 retains the approved Fuller-body excitation:
35 ms tonal attack, 1.5 s decay and force 210, above the same 6 ms metal edge.
Intermediate BODY positions are new listening candidates. LENGTH shortens the
source and modal release rather than cutting an otherwise unchanged recording.

Native output follows the house melodic conditioning and velocity policy.
Unconditioned/raw probes remain available for transfer, headroom and endpoint
comparisons. Loudness conditioning is outside the body model. Finite notes
longer than the classifier's 1.5-second threshold carry LOOP class metadata;
that class does not claim a held source or a seamless join. The starter uses
ordinary finite pad playback and has no held instrument export.

The bounded geometry cache is keyed by SOURCE, FOCUS, ray count and
aperture. Pitch, velocity, BODY and LENGTH do not invalidate static geometry.
Audio state belongs to each render. The native fast path folds the static
linear response into forced and free modal coefficients, preserving causal
arrivals and signed carrier phase. Independent delayed-path modal calculations
verify the result, including events that arrive between samples.
The archived renderer remains the reference, not a mutable production preset.

### Why it is built this way

- One voice keeps the heard metallic identity central while the controls are
  tested; the earlier Body and Reed studies remain available as references.
- BODY changes energy entering the resonant body during the audible main note.
  Extending only a quiet tail failed to provide a useful listening choice.
- Static path preparation is shared, while each note owns its modal state.
  This avoids repeated geometry work without carrying sound between renders.
- Exact linear response removes repeated projection work from the audio loop.
  Independent numerical checks verify causal arrivals and free ringing.
- HOLD is reserved because a decaying recording has no settled sustained state.
  Building the finite instrument first keeps that separate DSP task explicit.

## Comparisons and verification

- Source only: the original finite tone before the vessel.
- Full vessel: coherent paths excite the shared modes.
- Carrier phases aligned: retain delayed envelopes and signed routing while
  removing relative carrier phases.
- Bank bypassed: retain signed excitation and the quiet root; a fixed excitation
  gain replaces modal dynamics.
- Gain only: one scalar collection ratio changes a fixed reference vessel sound.

SOURCE 0, .5 and 1 share FOCUS .12, .19, .245, .38 and .85, covering
concentrations, shoulders and sparse positions. A density maximum can cancel in
coherent audio and need not be loudest. Other clips cover C2/C6 and soft/hard
effort. The owner's listening verdict completes sonic acceptance.

Tests cover reflection, endpoints, forward segments, budgets, ray convergence,
direct/compressed transfer, determinism, silence, raw/state bounds, spectral
activity, velocity, scalar controls, cancellation and strict recipe replay. The
generator checks raw/PCM bounds, early-window drum classification, moderate
measured pitch error in both the FFT root and broadband detector, and
representative sample-identical replay. The included exploratory geometry
figure does not establish `.001R` envelope accuracy.

The initial 24-clip run measured a maximum raw sample peak of 0.3713, maximum
modal-state amplitude of 0.3292 and worst constrained root error below 0.62
cents. One common export gain of 1.0 preserved the original amplitudes.
Preparation took about 24 ms and rendering about 4.34 s for 108 s of audio on
an OpenJDK 21 Linux amd64 container. These are this pack's measurements, not
phone budgets or a sonic acceptance verdict.

## Later work

Deformation, SCATTER, exact-stop ORBIT, verified sustained HOLD, additional
voices, the broader preset bank and held keygroup export remain later work.
The finite instrument includes native patch/kit registration and the phone
picker. Phone latency and the new kit's hardware load/play check remain
device verification, separate from JVM and export-reader checks. No real-time
ray-traced engine is required for this sample workflow.

## As built and evidence

- **Heard:** the owner preferred Alloy, could not distinguish Bloom/Deep in normal
  listening, then heard and approved Fuller body's main-note change. Native
  finishing, intermediate controls and six starter positions still need listening.
- **Verified:** native model-1 patches preserve MIDI and excitation velocity; four
  active macros plus reserved HOLD; one ALLOY voice, eight starter positions and
  sixteen dry finite pads. All 148 original study file hashes remain unchanged;
  the earlier 48-recording listening study is retained. Native patch and WAV hashes
  passed the artifact audit, and browser checks played all forty native/matched
  WAVs, including transport switching, load retry and nested page routing.
- **Measured:** the twenty-clip native pack has no generator warnings or hard
  failures. Its maximum native sample peak is 0.9900, worst measured broad pitch
  error is 13.43 cents, and minimum pitch confidence is 0.9333. The generator
  checks patch round trips, representative sample-identical replay and classifier
  safety; these measurements do not replace listening acceptance.
- **Export checked:** the starter contains sixteen native patches and WAVs,
  one MPC 3 track and one MPC 2 program. ZIP CRCs, twenty-two checksums and
  export-reader checks passed. All pads are finite one-shots and the MPC 2 loop
  flags are disabled. The reader does not check pad colour, one-shot or humanize;
  the separate artifact audit checks the exported one-shot and loop settings.
- **Render cost:** before the native rewrite, three eight-second C4 renders took
  4.36–5.19 s on this Linux/OpenJDK 21 container. The initial analytic renderer
  reached 0.329 s after warmup, with a full-note raw RMS error of 2.35e-7 against
  legacy Fuller body. In the native pack generated from `9c05bb8d`, warm
  eight-second examples took 151–157 ms and the two half-second notes took
  11–13 ms; other eight-second captures reached 499 ms during generation.
  These are container timings, not phone latency measurements.
- **Android:** the debug app, all four native ABIs and the instrumentation
  test APK assembled. Actual UI tests could not run: the software-only Android emulator's system watchdog killed
  its boot process, and the targeted timeout-property retry was unsupported.
  No phone performance or hardware MPC load/play claim follows from these checks.
- **DSP checks:** all 49 chromatic notes in the native C2–C6 range pass the
  requested-pitch check. Register/geometry corners, 4× versus 16× aliasing,
  causal modal evolution, low/middle control authority, deterministic replay
  and finite decay checks also passed in the recovered regression run.
- **JVM verification:** the initial 64 focused Caustic tests and authored-note
  SPREAD check passed. The added chromatic scan and DSP checks above also pass.
  The broader nine-module suite result is recorded in the change's pull-request
  validation section, separately from these focused and artifact checks.
