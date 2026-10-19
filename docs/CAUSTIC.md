# Caustic: first circular listening prototype

Caustic tests whether an ordinary harmonic tone acquires a position-dependent
singing body through reflected paths. This milestone is a finite Kotlin renderer
and portable listening study, awaiting the owner's sonic verdict. It is not the
complete proposed engine. The working name has not been checked for release.

## Listen and reproduce

The [private audition](https://caustic-snipsnap-audition.bertcalm.chatgpt.site)
uses ChatGPT Sites. It provides a suggested listening order,
position studies, mechanism controls, register/velocity probes and local notes.

```text
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Caustic*'
./gradlew --no-daemon :synth:generateCausticAudition
./gradlew --no-daemon test
```

The generator writes `testkit/caustic-audition/`: 24 finite notes, shared-gain
and independently matched PCM24 WAVs, complete JSON recipes, measurements,
source fingerprints and `audition.zip`. Use a local server or published page
for decoded Web Audio playback. The downloaded pack also supports local media
playback. Repeat replays a finite recording; it is not a HOLD or seam test.

Use **Level matched** to compare tone and **Shared gain** to hear collection
strength and velocity. One native export gain applies to every clip, including
the source. No per-position compensation or rack FX runs inside synthesis.
Matched exports use the existing `AuditionLevel` loudest-200-ms rule.

## Model and recipe

`Caustic.Config` stores SOURCE, FOCUS, MIDI, velocity, duration, aperture,
musical delay scale, ray count and renderer model. `CausticRecipe` adds an
explicit diagnostic variant in strict, versioned JSON. Replay a recipe with
`CausticRecipe.fromJsonText(text).inspect()`. This standalone prototype is
outside the production `Patch` registry.

The circular boundary uses half-step, equal-arc quadrature and an explicit
transparent entrance: only interior-facing reflection points are illuminated.
Launch weights normalize before reception; nine pickups share a fixed budget.
SOURCE uses inverse distance with an exact parallel limit. FOCUS moves the
same band for all sources. Forward segments stop at the next circle boundary;
a smooth finite aperture bounds reception. Nonnegative density, coherent signed
audio and the mathematical envelope are separate quantities.

Up to twelve band-limited harmonics enter a finite envelope with a 12 ms attack
term and 1.1 s exponential decay. Velocity changes effort and harmonic slope
before excitation. Relative incident distance and forward path length determine
phase and envelope delay. `delayR * delayCycles / noteHz` is a musical scale,
not a physical cup dimension or measured speed of sound.

The two-exponential source permits causal coefficient events that preserve each
static path's delayed envelope and carrier phase without visiting every path
at every audio sample. Independent direct sums verify this representation,
including the lowest supported MIDI note at maximum delay scale. Moving paths
and arbitrary envelopes need separate validation.

Fixed pickup losses and signed participation feed sixteen shared, uncoupled
`Modes.Bank` resonances: twelve harmonic modes and four quiet near-harmonic
companions. Material gains are fixed; FOCUS adds no independent oscillators.
A quiet direct root supports sparse/canceling positions. Rendering uses 4x
processing, existing antialias filtering, decimation, DC removal and a short
finite-tail fade, yielding mono 44.1 kHz samples. Stable poles and normalized
launch weights do not establish acoustic energy conservation.

## Comparisons and verification

- Source only: the original finite tone before the vessel.
- Full vessel: coherent paths excite the shared modes.
- Carrier phases aligned: retain delayed envelopes and signed routing while
  removing relative carrier phases.
- Bank bypassed: retain signed excitation and the quiet root; a fixed excitation
  gain replaces modal dynamics.
- Gain only: one scalar collection ratio changes a fixed reference vessel sound.

SOURCE 0, .5 and 1 share FOCUS .12, .19, .245, .38 and .85, covering
concentrations, shoulders and sparse positions. A density maximum can cancel
in coherent audio and need not be loudest. Other clips cover C2/C6 and soft/hard
effort. The owner's listening verdict completes sonic acceptance.

Tests cover reflection, endpoints, forward segments, budgets, ray convergence,
direct/compressed transfer, determinism, silence, raw/state bounds, spectral
activity, velocity, scalar controls, cancellation and strict recipe replay.
The generator checks raw/PCM bounds, early-window drum classification, moderate
measured pitch error and representative sample-identical replay. The included
exploratory geometry figure does not establish `.001R` envelope accuracy.

The initial 24-clip run measured a maximum raw sample peak of 0.3713, maximum
modal-state amplitude of 0.3292 and worst constrained root error below 0.62 cents.
One common export gain of 1.0 preserved the original amplitudes. Preparation
took about 24 ms and rendering about 4.34 s for 108 s of audio on an OpenJDK 21
Linux amd64 container. These are this pack's measurements, not phone budgets or
a sonic acceptance verdict.

## Later work

Deformation, SCATTER, exact-stop ORBIT, verified HOLD, the six-voice/36-preset
bank, phone picker, normal patch/kit registration and attack-plus-loop keygroup
export follow the static listening decision. No real-time ray-traced engine is
required for the proposed sample workflow.
