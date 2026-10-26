# Crease release motion experiment

Status: numerical prototype passed; listening and live transport remain open.
Luthier job: CONCEIVE, extending the existing Crease gesture.
Repository baseline: `fd791f583579dda3dcab3d2ea9e34f9975b07a12`.
Measured render source: `adcee4b84aac331f974af27f22b0f5f85003f8f3`.

The characteristic gesture is **held pressure → key-up withdraws its source →
the existing deformation and recovery continue → the shared volume fade ends**.

## What was built

An isolated Java/Python harness invokes the actual private model-3 Kotlin
`Crease.Shape`, `Crease.Model.keyStep` and `Crease.OutputPath`. It changes no
production Kotlin, saved patch, phone player, export schema or factory preset.
The complete warm-up must reproduce public `CreaseKeys.probe(normalize=false)`
exactly before any release result is accepted.

At each key-up the model, detector, filter, DC, FIR history and clocks are copied
with zero state error. Fade only continues the held forcing. Unfold sets the
pressure source to zero and evolves the carried state. Both receive the same
1.2-second linear post-conditioning fade, following `InstrumentEngine`'s Float
release arithmetic. A deliberately reset model is retained as a diagnostic
canary, not a listening choice. No crossfade or replacement cycle repairs a
state transition.

This literally runs the existing equations; it is not a passive acoustic-energy
simulation. The oscillator still exists behind the external fade. Inaudible
deformation draining is audited separately; detector residual energy is not
mislabelled as physical energy or required to become zero.

The [protocol](../../../scripts/crease-release/protocol.md) was committed before
contrast was measured. Its prefix-equality gate applies to the common-gain pair.
The separately labelled tail-matched takes apply the allowed whole-take scalar,
so their held-prefix levels can differ. No gate or DSP factor was retuned.

## Signal and controls

```text
approved patch → actual Kotlin circuit from rest → verified recurring hold
                                            → complete state fork at key-up
                 A: periodic forcing continues ─┤
                 B: forcing becomes zero ───────┤
       carried output conditioning → identical linear fade → common preset gain
                                    → common 10 ms preview lead-in → PCM16
```

| Listening control | Values | What changes |
|---|---|---|
| Sound | Paper Keys / Reed Mark / Ink Ribbon | Existing approved model-3 patch |
| Key-up position | 0 / .25 / .5 / .75 | Actual phase of the slow pressure gesture |
| Ending | Fade only / Unfold | Whether key-up withdraws the pressure source |
| Level | Same gain / Tail RMS matched | Optional fixed full-take scalar; no dynamic flattening |

Every preview contains one second of already-held audio before key-up. This is
not a new onset test. The common 10 ms lead-in only prevents a cropped-file start
click. Tail matching measures 40–500 ms after key-up. The WAV ZIP contains 48
fixed, mono 44.1 kHz PCM16 performances; FLAC previews decode to identical PCM.

## Measured results

Java ran the actual Kotlin model; Python/SciPy measured the recordings. There
were 63 circuit cases: 36 normal C4 branches, 18 C6 4x/16x branches, and nine
zero-velocity branches. Every public warm-up prefix and complete state fork was
exact. Repeated unfolding forks were bit-identical, and all silent cases stayed
silent. Raw samples stayed within the existing analytic `.91*velocity` ceiling.

At the fixed .5 gesture phase, normalized harmonic-power total variation was:

| Sound | 40–250 ms after key-up | 250–500 ms | Real settling cycles |
|---|---:|---:|---:|
| Paper Keys | 0.19655 | 0.19730 | 4 |
| Reed Mark | 0.41506 | 0.30200 | 4 |
| Ink Ribbon | 0.12146 | 0.25163 | 10 |

All three exceed the preregistered crest floors. Other phases are reported
without a universal contrast requirement; Paper Keys at the falling position
has a smaller maximum TV of 0.04617. This phase dependence is a result, not a
listening verdict. TV is a normalized power-distribution distance, not decibels.

- Worst root-constrained pitch estimate: 0.11951 cents, within the 10-cent gate.
  The estimator searches within ±5% of the intended root; this is not independent
  octave-error detection.
- Worst C6 4x/16x harmonic-power TV: 0.00016159, below .01. This is a reference
  shape check, not a complete off-harmonic alias-power proof.
- First post-key-up A/B sample difference: zero in all 12 normal pairs. The
  release source changes behind continuously carried conditioning.
- Largest final deformation/pressure/recovery residual: `7.205e-7`, below `1e-4`.
  The bounded silent mechanics audit took up to 17.532 simulated seconds for
  the selected patches; it does not extend the audible fade.
- Independent PCM readers verified all 48 WAV/FLAC round trips, all 12 common
  prefixes, silent endings and all WAV ZIP entries. The maximum actual PCM16
  matched-tail RMS discrepancy was `0.0000190 dB`.

The frozen numeric summary is in [the results ledger](2026-10-26-crease-release-results.json).
No result is labelled heard; the audition is the listening gate.

## Phone and MPC feasibility

The phone sidecar (`InstrumentStore.Layer`) has a sample length and loop start,
with no separately addressable release tail. `InstrumentEngine.noteOff` sets a
release flag; its reader keeps wrapping `[loopStart, end)` while the gain falls.
Native `PadEngine` has the same window/loop arrangement. The keygroup writers
export `SliceLoopStart`/`SliceEnd` or `LoopStart`/`End`, an amplitude release, and
currently set native `TailLength` to zero. A recorded ending cannot reconstruct
the missing arbitrary-time state transition by itself.

The official [MPC 3.7 Beta guide](https://cdn.inmusicbrands.com/Software/37/MPC%20Software%20v3.7%20-%20Beta%20User%20Guide%20-%20RevA.pdf),
pp. 62 and 109, describes sample-loop and tail controls and Auto Sampler capture.
The official [looping help](https://support.akaipro.com/en/support/solutions/articles/69000859436-mpc-standalone-how-to-loop-your-sample)
also describes note-on playback. These establish recording/loop facilities, not
phase-aware release-state selection. Do not infer that every MPC firmware lacks
a useful release facility; device/format experiments remain necessary.

| Exact current gesture period | Paper Keys | Reed Mark | Ink Ribbon |
|---|---:|---:|---:|
| Frames at 44.1 kHz | 63,885 | 72,819 | 92,540 |
| Maximum boundary-only release wait | 1.449 s | 1.651 s | 2.098 s |
| Maximum next-bin wait, hypothetical 16-bin bank | 90.54 ms | 103.20 ms | 131.15 ms |

Waiting for the full slow loop is unsuitable for immediate key-up. Sixteen bins
reduce that wait but do not solve oscillator phase, conditioning history or
storage. An immediate nearest-tail jump needs a measured state/waveform error
budget. No such player mode or bank is exported here. Fixed performances can be
loaded as ordinary finite samples; they are not release-aware instruments.

## Cost and reproducibility

Run from the repository root with a JDK, NumPy, SciPy, Matplotlib and FFmpeg:

```sh
python3 scripts/crease-release/prototype.py
```

The script builds the real synth JAR, compiles the research harness, runs the
cases, checks the gates and writes `testkit/crease-release-experiment/`.
`--analyse-only` explicitly reuses a saved raw execution after checking the Java
harness, production-source and runtime-JAR hashes; it never relabels that
execution as a new render. Generated binaries/audio stay out of Git.

Measured desktop Java batches took 9.43 s (normal), 25.75 s (higher-rate
reference) and 4.16 s (silence); the end-to-end run was 200.39 s including Gradle
startup and export. These are offline experiment costs, not phone timings or a
per-knob budget. A future cache must include model/voice/macros, root, excitation,
release phase and duration, render version, and any phase-bank policy. Expensive
simulation must remain an explicit cached operation, not a knob-turn side effect.

## Verified / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| Pressure withdrawal changes these tails while retaining the state | Measured | Actual Kotlin forks and Python spectral probes |
| Prefix and state copying are faithful | Verified/measured | Public-prefix and full-state zero-error checks |
| Browser previews preserve delivered PCM | Measured | WAV/FLAC round trips and separate browser decode checks |
| Current repository player/export cannot describe this transition | Verified | Source paths and fields above |
| A phase-bank or new tail transport can approximate the gesture acceptably | Proposed | No implementation or hardware result |
| The ending is musically preferable | Unheard | Owner listening still required |

## Why it is built this way

1. The real circuit keeps the comparison about release behavior rather than a
   second implementation's different voicing.
2. Identical fades separate pressure-history changes from volume shaping.
3. Keeping the complete state lets the ending inherit the held note's memory;
   resetting it would invent a different transient.
4. Four lift positions reveal when the effect is strong or restrained.
5. Fixed recordings prove the sound mechanism without promising a live sampler
   feature that the current transport does not implement.
