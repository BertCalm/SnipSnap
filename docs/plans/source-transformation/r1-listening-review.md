# R1 initial listening review

Luthier REVIEW, 2026-11-01. Code inspected at `1994c619ebcccff932cf0d69d5b7b61d16862651`; model version 1. The saved audition binds its implementation to SHA-256 `7bfa99f27653f376ca85dfea8c5d1db77eafed3ab034333f6a3f60adfddfdc79`.

**Heard, owner:** "Not very much distinction." This feedback followed delivery of the compact audition. The exact file, comparison, headphones/speakers, and playback path were not specified. The working interpretation is weak body-timbre distinction. No musical acceptance is recorded. The assistant has not heard the audio.

## Fix now

- **[A/D] Keep useful distinction as an open acceptance gate.** `ResonantBodyTest.kt:158–170` requires a normalized difference greater than 0.01 on an impulse/max-RING fixture. That prevents an inactive control; it does not establish an audible identity. All existing numerical tests passing is compatible with the owner's feedback.
- **[D] Compare the endpoints directly before interpreting quarter-step sweeps.** The sustained fixture's adjacent BODY steps have scale-invariant waveform correlations 0.9966–0.9973. Repeated A/B/A/B playback of complete saved endpoints reduces the comparison burden without changing the sound. It does not itself resolve the musical finding.

## What I measured

Python/NumPy analysis of the saved PCM, no rerendering and no phase/time alignment optimization. Waveform correlation is `dot(x,y)/sqrt(dot(x,x)*dot(y,y))`. Spectral overlap sums the minimum of the two unit-total rFFT power spectra over complete clips. Neither quantity is a perceptual pass/fail threshold.

| Existing endpoint pair | Waveform correlation | Power-spectrum overlap |
|---|---:|---:|
| Default burst, `burst-body-0` / `burst-body-4` | 0.669 | 65.5% |
| Default sustained source, `harmonic-body-0` / `harmonic-body-4` | 0.951 | 90.7% |
| C3 impulse/max RING, `root-48-0` / `root-48-2` | 0.337 | 34.5% |
| C5 impulse/max RING, `root-72-0` / `root-72-2` | 0.280 | 27.2% |

In the default sustained fixture, the band 111.19–150.43 Hz around C3 contains 88.3% of HARMONIC energy and 97.6% of BAR energy. This is a frequency-band statistic, not an exact separation of modal energies. In the default burst fixture the corresponding fractions are only 40.3% and 49.7%; do not generalize sustained-source root dominance to every input.

The measurement report is retained with the delivery artifacts as `resonant-body-distinction-measurements.json`. It records file hashes, settings, methods, individual corner results, and explicit hypotheses. It is not a Nothing Phone (3) speaker-response simulation.

## Verified code facts and their implications

- **[A] The fundamental response is identical in both banks.** `ResonantBodyModels.kt:47–55` assigns both families the same ratio-1 mode and normalized weight `1/2.3 = 0.43478`. Its decay, injection and source are also identical. `ResonantBody.kt:95–100` convexly blends bank outputs, so this common component remains unchanged while three upper resonances vary. That is a deliberate v1 design constraint, not proof of two useful material identities.
- **[A] The differing upper modes decay quickly at default DAMP.** From `ResonantBodyModels.kt:84`, the calculated root T60 is 173.2 ms. HARMONIC upper T60s are 57.7/41.5/34.6 ms; BAR upper T60s are 44.1/29.5/23.7 ms. These are coefficient calculations, not heard decay descriptions.
- **[A] Source excitation limits the transformation.** The generator's sustained source is four C3 harmonics. Only the fundamental aligns exactly with BAR's modal table. This fixed linear filter cannot continuously create absent bar-frequency overtones. The BODY comparison is already fully wet (MIX 1), so its similarity cannot be attributed to a dry-signal blend.
- **[A/C] DAMP is not a simple high-frequency attenuation law.** Shortening T60 also increases the `(1-r)` injection term (`ResonantBodyModels.kt:90`). In the saved short-RING burst corners, raising DAMP from 0 to 1 increases power above 400 Hz from 8.9% to 17.0% for HARMONIC and 4.0% to 19.0% for BAR. This generic frequency split is not a phone model or a listening judgment. Label controls accurately rather than promising a universally darker onset.

## Try next

The small endpoint comparison reuses these five existing pairs in order:

1. Default burst endpoints.
2. Default sustained-source endpoints, retaining the measured weak case.
3. Burst, maximum RING, DAMP 0. This is a diagnostic corner, not default acceptance.
4. C3 impulse, maximum RING.
5. C5 impulse, maximum RING. This checks register dependence without claiming a measured phone limitation.

Within each pair, the source and all controls except BODY match. Playback is A (HARMONIC), B (BAR), A, B, retaining complete PCM24 clips, the original saved matching gains and 150 ms silent gaps. There is no additional normalization, EQ, resampling, changed exciter or DSP revision. FLAC decode is checked against the concatenated original samples. The combined direct audio is 385,439 bytes; the labelled standalone HTML is 558,316 bytes. Reordering a saved audition is a presentation change, not a claim of improved body identity.

If the default still lacks useful identity, the next sound prototype should vary family-specific mode weights and decay profiles while preserving this baseline. The shared-root/upper-mode balance and decay-dependent injection should be evaluated together. This is a proposed model revision, not an implemented fix. Adding a nonlinear or generated-onset exciter would expand scope and require its own spectral, aliasing and source-dynamics checks.

The nearest existing mechanisms are legacy BODY and the modal `Modes` toolkit. This work remains a shared processor; no new synth engine, registration surface, saved recipe or factory preset is justified by this feedback. Read the versioned-change requirement in the implementation plan before changing tables or gain/decay laws. Do not silently replace v1 or change only a numerical difference threshold to manufacture an acceptance result.

## Disclosed, not fixed

The owner has reported weak distinction. The default sound has not been accepted. No physical-phone playback response, Android timing or real owner-capture result was measured. The new A/B contains original generated fixtures only; a stronger impulse contrast does not close these gaps.

After the endpoint A/B, the owner said "That's better, proceed as recommended" and authorized the separately versioned [model 2 candidate](r1-model2-candidate.md). That response concerns the existing endpoint comparison and the next implementation step. It is not a listening verdict on the new candidate.

On 2026-11-05, after the v2 delivery, the owner reported quiet output, easier distinction and a preference for the new sounds. The [level review](r1-level-calibration.md) records this favourable timbre feedback and the remaining level issue. Retain the preferred timbre direction during gain calibration; do not interpret this later feedback as rejection of those spectra.

## What I only read

The v1 coefficient/processing code, generator, activity tests and implementation contract were inspected at the commit above. The root/decay explanation follows those equations and measured fixtures; its contribution to the owner's perception remains a hypothesis. DSP code and the original audition files were not changed. The existing full JVM gate remains the earlier validation record; no new DSP regression run is claimed for this listening review and lossless repackaging.
