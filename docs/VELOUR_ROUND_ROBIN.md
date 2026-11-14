# Velour round-robin candidates

Baseline inspected: `23cde0d1c` (2026-11-14). The approved Round 5 shelf bank
remains unchanged. `VelourRoundRobinKits.build(treatment)` is an opt-in listening
candidate for Close, Velour and Bloom: 16 pads, three velocity zones and four
takes per zone. It uses the existing `ArrangedPad.velocityTakes` path.

The gesture is repeated contact → small attack/tone variation → the same drum
body and printed atmosphere. This extends a kit, not an engine. Cosmic Breaks
already supplies the grid/persistence pattern; Velour supplies the approved
printed source identity. Neither needs a new playback mechanism.

## Design choice

Three approaches were considered: gain/pitch jitter, independent source-model
re-renders, and bounded processing of the approved samples. Gain-only copies
would not meet take diversity; pitch jitter could make the drums wobble. Fresh
source-model re-renders are a stronger future physical-strike model, but the
approved Python/native/reference-derived bank has no single editable renderer.
Bounded sample-derived variations are the smallest auditionable addition that
preserves all approved reference hits. They are not new independent strikes.

Take 1 is copied exactly. Takes 2–4 use opposing early contact/body envelopes
and a one-pole spectral balance at 1.8 kHz. A raised-cosine window ends the
processing at 240 ms. There is no resampling, pitch modulation, delay, noise
injection or additional effect board. Late tails remain sample-identical.
Early energy correction is smoothly tapered; it is approximate, not a loudness
normalizer. If a candidate exceeds 0.95 peak, its difference from the reference
is reduced rather than clipping the hit or scaling the preserved tail.
All shape constants are listening hypotheses, not measured physical parameters.

Fixed arrays determine take identity, so output is deterministic without random
state. Processing is linear in sample length, with no iterative solver or disk
cache. Build timing is printed by the generator. There is no real-time DSP cost
at playback: native assembly writes the resulting sample grid once.

## Reproduce

```
./gradlew :synth:generateVelourRoundRobin
./gradlew :synth:test --tests '*VelourRoundRobinKitsTest' :shell:test --tests '*VelourRoundRobinRoundTripTest'
```

Output: `testkit/velour-round-robin/`. Each treatment has a full-grid ZIP with
native kit.json, SFZ and DecentSampler programs sharing the chain WAV files.
The web audition uses individual PCM24 files emitted from the same Kotlin grid.
The first take of every velocity remains the exact approved PCM24 source.
Reference-derived tambourine provenance and the CC0 license travel in each ZIP.

## Acceptance ledger

- Owner-heard: approved Round 5 reference only. Alternate takes await listening.
- Candidate tests: deterministic/finite PCM, peak/DC limits, unchanged reference,
  unchanged lengths and tails, and velocity energy ordering. Pairwise residuals
  after fitting gain reject gain-only copies.
- Native and SFZ test: all 576 strike windows; saving/loading; all MIDI velocities
  1–127 and nine consecutive hits; PCM, playback windows, pitch, gain and choking.
- MPC 3: existing slice-map and physical cycling gate remains open.
- MPC 2: four layer slots do not represent three velocities × four takes on one
  pad. No reduced-take compatibility file is presented as full-grid completion.
- Shelf activation, genre arrangements and authored rolls/risers are separate
  follow-ups. This candidate does not change the approved factory defaults.
- Android/MPC and external sampler application playback remain unverified.

## Why it is built this way

1. Keeping take 1 exact anchors every comparison to the approved sound.
2. Attack/tone changes can vary contact without moving the drum's tuning.
3. Ending processing early preserves the approved board's late echoes.
4. Reusing the native grid keeps selection and persistence in one existing path.
5. Listening before shelf activation limits the risk that mathematical diversity
   merely sounds like alternating EQ, rather than natural repeated hits.

## Measured delivery (2026-11-14)

The focused Kotlin run passed all five test methods across the approved-bank
and candidate synth/shell classes (Gradle exit 0). The independent PCM/ZIP audit
verified all 576 delivered windows against the individual audition files, all
144 approved reference takes, and all 576 DecentSampler sequence entries.
Maximum peak: 0.94999993; absolute DC: 0.0003461; absolute energy change from the
reference: 0.3594 dB. Minimum pairwise residual after removing optimal gain:
0.00002788. Eight-hit repeat/cycle trains at 160 BPM and the audition's gain
peaked at 0.4599. These are numerical checks, not listening approval.

Measured whole-kit builds (16 pads × 12 takes): Close 423 ms, Velour 699 ms,
Bloom 876 ms in this environment, including source loading. No real-time render
path is introduced. The full JVM regression run was started separately; its
pending result is not counted as passing.
