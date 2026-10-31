# Crease release experiment protocol

Job: Luthier CONCEIVE, one requested direction. This is an isolated experiment,
not a new engine or a change to accepted Kotlin rendering. Repository baseline:
`fd791f583579dda3dcab3d2ea9e34f9975b07a12`.

The characteristic gesture is **held pressure → key-up withdraws its source →
the existing deformation and recovery continue → the shared volume fade ends**.
The pressure and deformation equations run literally in Crease's actual private
Kotlin model. Paper/material language remains an analogy; no passive acoustic
body or state-aware sampler transport is claimed.

## Controlled comparison, specified before rendering

The Java harness invokes `Crease.Shape`, `Crease.Model.keyStep` and
`Crease.OutputPath` from a freshly built synth JAR. These private internals are
research access points, not proposed public APIs. Public `CreaseKeys.probe`
must match the harness's complete warm-up audio exactly. Model, detector, FIR,
DC/filter state and clocks are copied at key-up and checked before branching.

- A, fade only: periodic pressure continues, as in a looping sample.
- B, unfold: pressure source becomes zero; all existing state keeps evolving.
- Canary, reset: pressure is withdrawn but model state is reset. The output
  path and clocks are kept. This intentionally wrong ablation is diagnostic.
- All branches use the same 1.2-second linear post-conditioning fade, based on
  `InstrumentEngine`'s release arithmetic. No per-branch normalization.
- Approved Paper Keys, Reed Mark and Ink Ribbon, C4, four fixed gesture phases
  0, .25, .5, .75. Warm-up comes from rest and must converge within the existing
  28-cycle/64-second caps. The listening clip keeps one second before key-up.
- Export applies one common gain per preset over all phases/branches and a
  common 10 ms lead-in fade for the cropped listening preview only.
- Additional labelled tail-RMS-matched comparisons may use one scalar per
  whole recording. They never flatten the movement within a recording.

## Gates

1. Actual public keyboard prefix equality and complete fork-state error are zero.
   All A/B preview samples before key-up are equal.
2. Finite raw/output values; raw peak below the source's analytic `.91*velocity`
   bound; no limiter conceals growth. Pressure/deformation/recovery return below
   `1e-4` in a bounded silent mechanics audit. Detector energy is a descriptor,
   not a physical-energy certificate, and need not return to zero.
3. At phase .5, normalized harmonic-power TV in fixed 40–250 ms and 250–500 ms
   post-key-up windows: at least two voices reach .05 in one window, and all
   three reach .02 in one window. Other phases are reported without inventing
   a universal timbral floor. A listening verdict is still required.
4. Release pitch near the intended root stays within 10 cents with normalized
   correlation at least .90 where the release remains measurable. Report the
   estimator's root-constrained scope; do not call it independent octave proof.
5. At C6, 4x/16x normalized harmonic-power TV is below .01. This reference-shape
   check is not a complete off-harmonic alias-power proof.
6. First post-key-up A/B output difference is below `1e-4`, prefix continuity
   and exact carried state are verified, and the reset canary changes the tail.
7. Deterministic rerender and PCM16/FLAC decode identity. Gate failures are kept
   with their source snapshot before changing a method or threshold.

## Landing and playback feasibility

This is an experiment on Crease, whose deformation memory GLINT does not have;
BELLOWS supplies pressure-driven layers but not these coupled phase maps. It
adds no engine, voice, preset, macro, player field or Android control.

```text
approved patch → actual Kotlin circuit from rest → real recurring held state
                                              → exact fork at key-up
                    A: held forcing continues ─┤
                    B: forcing becomes zero ───┤
       continuously carried conditioning → identical linear player-style fade
                                        → one common gain → PCM16 comparisons
```

| Control | Value | Quantity and proof |
|---|---:|---|
| Key-up position | 0/.25/.5/.75 | Actual forcing/circuit phase; state captured per case |
| Pressure withdrawal | off/on | Only the post-key-up pressure source changes |
| Release fade | 1.2 s | Same external gain law in A and B |
| RELAX, DRAG, SPLIT | saved preset | Existing recovery/following/coupling laws are retained |

The current phone sidecar has sample length and loop start, with no separately
addressable release tail. `InstrumentEngine` and native `PadEngine` loop to the
sample/window end and apply an amplitude fade. The keygroup writers export the
same sustain-loop shape (`SliceLoopStart`/`SliceEnd`, `LoopStart`/`End`) and an
amp release. The exported `TailLength` is zero. These are repository facts,
not a claim about every MPC firmware feature. A fixed-key-up WAV can play as
an ordinary finite sample; arbitrary-time state-aware note-off needs further
transport/export design and hardware verification.

Waiting for the existing full gesture-loop boundary would delay the release by
up to about 1.45–2.1 seconds. Measure exact periods; do not recommend that as an
immediate musical key-up. A phase-bank approximation would need a quantified
latency/state-error/storage budget before changing any player or export schema.

## Why it is built this way

1. The actual circuit avoids mistaking a second implementation's differences
   for a new release gesture.
2. Identical gain envelopes make the pressure-history change the experiment.
3. Keeping the whole state lets the tail inherit what happened while held.
4. Four release positions expose memory dependence rather than one lucky cut.
5. Separating the sound proof from playback support prevents a fixed recording
   from being advertised as a playable release-aware instrument.
