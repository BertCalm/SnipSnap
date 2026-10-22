# Validation and audition requirements

Status: test design. No tests or audio renders have been run for these proposed processors.

## Body processor gates

| Requirement | Meaningful check |
|---|---|
| Determinism | Same source, parameters, version, and seed produce identical sample arrays on repeated runs; random model construction must use an explicit stable seed. |
| Bypass and compatibility | Null/disabled stage and zero mix return the original samples, length, rate, and channel layout without pitch analysis. Existing serialized fixtures and DSP goldens remain valid. |
| Silence | Silence stays silent; do not peak-normalize numerical residue into a tone. |
| Stable decay | Impulse and sustained-input-then-silence tests across extremes. Compare decay against an independent post-excitation reference window, not a maximum over the entire render. Exercise all morph paths; confirm output does not develop runaway growth. |
| Finite output | Sweep root, body, decay, damping, mix, source level, source type, and sample rate. Capture internal peaks as well as final output; an output limiter is not evidence of internal stability. |
| Frequency response | Sweep/impulse spectral estimates match the authored resonant locations within explicit FFT resolution and estimator tolerance. Check low and high registers. |
| Pitch | Where a model is explicitly pitched, compare expected root against a suitable detector and spectrum. Proposed starting tolerance is 25 cents on clean fixtures; confidence and octave errors must be reported separately. Inharmonic models use reference-mode checks instead. |
| Decay controls | Measure band-specific decay slopes from impulse responses. RING should alter duration; DAMP should shorten high modes relative to low modes. Account for overlap/noise floor in the estimator. |
| Macro activity | Sweep low/mid/high values and pair corners. Compare level-matched waveforms and spectra so gain-only changes do not pass as body changes. Include a deliberately disabled-control case to verify the test detects a dead macro. |
| Gain and headroom | Test tones exactly on resonances, not only noise and impulses. Record raw peak, RMS/loudness, and final peak. Establish a steady-input gain policy before applying house output conditioning. |
| Aliasing | Exclude or taper modes beyond the relevant Nyquist limit. For drive, rapid modulation, and later grain processing, compare with a higher-rate reference. Four-times oversampling alone is not a pass condition. |
| Channels | Mono stays mono; stereo processing preserves layout and tests channel isolation. Dry/wet latency compensation must be checked with an impulse. |
| Tail and classification | Confirm exact frame count, end fade, tail cap, and downstream class behavior. Long decay must not be silently truncated or mislabeled. |
| Performance | Report device/runtime/build, input length, internal rate, active bank count, cold/warm timings, and allocations. Use multiple runs; include median and slow-tail latency. Desktop Python time is not Android Kotlin time. |

Any numeric ceiling or threshold must be chosen in the R0 plan from existing repository contracts or labeled as a proposed acceptance target. Do not silently apply pitched-engine class restrictions to a general processor that also processes drums.

## Later source-transform gates

- Clean known-pitch fixtures: sweep transposition while holding formants fixed, then sweep formants while holding pitch fixed. Measure pitch and spectral envelope independently.
- Speech transitions: report behavior on vowels, consonants, breaths, silence, and abrupt pitch changes. Audit confidence gates and octave-error recovery.
- Harmonic translation: use an additive fixture with known partials. Check integer shifts, fractional-step crossfades, ratio settings, sideband suppression, and neutral bypass. The manual's octave-down/smoothing caveat is a special reference behavior, not a requirement to reproduce a hidden pitch failure.
- Cycle alternation: verify grain boundaries and requested up/down ratios; compare short and long cycle groups. Check join clicks and subharmonic ambiguity.
- Resampling: upward shifts of high-frequency sources must be antialiased. Record errors against a suitable reference and listen to matched samples.
- Duration and source boundaries: preserve the agreed timing contract; test short clips and tails, not only sustained interior samples.

## Audition package

Supply a manifest, WAVs, machine-readable metrics, and an audition page using the repo's established pattern. Include model version, source provenance/hash, parameters, render duration, gain treatment, and timing for every case. Originals and raw wet output must remain available even when matched-level examples are supplied.

R1 inputs: generated impulse, short noise burst, sustained harmonic tone, and an owner-approved dry capture. Later add a spoken syllable, sustained vowel, bass note, and deliberately difficult noisy/polyphonic source. Do not claim a synthetic vowel proves recorded-speech quality.

Audition sections: body endpoints; intermediate morph positions; root/register sweep; RING/DAMP corners; dry/wet attack comparisons; maximum resonance; long-tail policy; mono/stereo checks when supported. Ask the owner whether source identity remains useful, body changes are distinct, pitch is usable where intended, and the effect earns a pad.

No HOLD loop is promised in R1. If introduced later, measure a kept segment against its independently rendered continuation; repeating a crossfaded buffer is not proof that the original process joins correctly.

## Evidence ledger required in every report

| Label | Required evidence |
|---|---|
| Verified code | Commit, file, declaration or line, and what was actually read. |
| Manual statement | Supplied manual version and PDF page. |
| Proposed | Explicit hypothesis or acceptance target; no implied measurement. |
| Measured | Named harness/test, runtime, fixture, parameters, result, and limits. |
| Heard | Owner's listening feedback and exact audition case. Never infer listening from a spectrum. |

Run the repository's required test gate for eventual Kotlin changes and report its exit code. For this documentation handoff, verify local links, source hashes, extraction page counts, and the resulting PR file list; an application build is not needed.
