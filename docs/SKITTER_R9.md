# Skitter R9: a lower bass foundation

The characteristic gesture is **touch → source and filter exchange spectral memory → the fundamental carries a changing second-harmonic growl → the phrase settles or repeats under HOLD**.

This is a focused BASS revision prompted by the owner's report that R8 BASS sounds like brass. Repository base: `bf17f4561bda7418cc4ebf7bae8e78d7c8c45329`, whose Skitter source matches R8 commit `eab42bf300d4607e72d4122e3c5efe2ea5751857`. The report is listening evidence about R8. R9 remains a candidate for the owner to judge.

## Review and direction

**Fix now [A].** BASS already has a protected fundamental and moving second/third harmonics. At the published common VENT `.35`, its filter readout is `.30 × low-pass + .868 × band-pass`. With hard C3 touch, measured R8 cutoff averages about `2,497 Hz`, roughly `19 × f0`, over `.50–1.25 s`. Settings: common controls below, velocity `1`, seed `0`, 4×, finite note. This is a plausible mechanism for upper-mid emphasis, not a measurement of perceived brassiness.

| Direction | Result and tradeoff |
|---|---|
| **Chosen: INVERT the readout emphasis** | Read a compact fundamental/second-harmonic body from the existing source geometry, alongside the original filtered sound. Existing memories shape the low growl. No new feedback or recursive state. |
| **MUTATE the filter operating point** | Lower cutoff and reshape pressure response. Prototypes passed individual balance checks but introduced seed and held-state sensitivity. Leave the original circuit intact. |
| **STACK another low-pass** | Darken completed audio with another filter, adding state while also suppressing the detail just established in R8. Unnecessary here. |

This lands as one voice revision. FATHOM already provides root-led bass sources through drive/filter stages, and RESIN voices a stack through a nonlinear ladder. Skitter keeps its own source/filter memories and phase histories; no sibling engine, effect chain or new preset is imported.

## Signal flow and implementation

```text
R8 source -----------------> R8 nonlinear SVF -> original VENT mix ----+
   ^   |                            |                               |
   |   +-> actual upper -> R8 bridge <- actual band                  |
   +--------------------------+                                     |
   |                                                                v
   +-> bounded carrier / second-harmonic tap -----------------> audible blend
                                                                  |
                                              original root support + DC removal
```

The source waveform, normalization, input, SVF coefficients, cutoff law, history detectors, agitation, noise, four-state bridge, phase integration and phrase schedule retain R8's arithmetic. The new tap reads existing source geometry and bridge returns. It never enters excitation, either bridge input or the history detector. Only the audible output and its existing downstream DC accumulator differ.

For oscillator phase A, let `s = sin(A)` and `square = s² - .5`. The original source computes charge and its bridge-loaded aperture. The added readout is:

```text
u = 2 * bridge.phaseLag * bridge.phaseAlign
v = bridge.phaseAlign² - bridge.phaseLag²
memory = .65 * bridge.agitate * (u-v) / sqrt(2)
h2 = .40 + .05 * tanh(.75 * loadedAperture * charge) + memory
body = -(1.6 * s + 2 * h2 * square) / sqrt(1.6² + h2²)
w = .80 * (1 - .60 * VENT)
lowLane = body * sourceScale + .11 * STATIC * existingColoredNoise
y = .68 * ((1-w) * originalVENTmix + w * lowLane)
    + originalSupport * originalRoot
```

Without a bridge, `memory = 0` and the existing unladen aperture is used. The low lane also preserves a quiet feed from the existing two-pole colored-noise state, so STATIC remains responsive near zero after the filtered lane becomes quieter. That state already follows key velocity and the note envelope; there is no new random draw or feedback. STATIC-zero movement remains a separate unchanged requirement.

Source load and relative lag already reach the aperture. The new `phaseAlign` is the dot-product companion to the existing cross-product `phaseLag`, using the same individually regularized complex-load vectors. Their joint norm is at most one. The double-angle pair `(u,v)` consequently also has norm at most one, and its 45° projection `(u-v)/sqrt(2)` remains bounded. This oriented readout exposes relative phase motion even when the sine-like lag alone lies near its extremum. The second-harmonic coefficient may change sign, staying within `(-.25, 1.10)`; the carrier remains protected. It is a function of the current memories, not another oscillator clock. The AGITATE factor fades it continuously to zero.

All new coefficients are **shape choices**. With a fixed coefficient, `|h2| < 1.1` gives the isolated tap a fundamental-power fraction of at least `1.6² / (1.6² + 1.1²) ≈ .679`. Time-varying loading also creates sidebands, and the full audible mix includes filtered sound and root support: this is not a guaranteed fraction for a rendered note. The tap is bounded, its normalizer stays positive, and `w` stays in `.32–.80`, a convex blend. Actual pitch, aliasing, DC and headroom still require measurement.

`bassBody` is scratch data recomputed before each circuit step, not new recursive state. The original source/filter/bridge trajectories remain the same. The existing DC accumulator follows the different output and remains in the complete HOLD state vector. No convergence cap, tolerance, phase fitting or seam requirement is relaxed.

| Control | R9 BASS contract |
|---|---|
| BUZZ | Original source edge, charge and filter drive; charge also colors the bounded low body. |
| SKITTER | Original seeded pitch departures and primary/reply/breath timing. |
| AGITATE | Original circuit reaction and spectral memories, now also heard in second-harmonic growl. |
| VENT | Original coherent LP/BP/HP morph in the filtered lane; body share decreases from `.80` to `.32` toward high-pass. |
| STATIC | Original noise feed and forcing, heard directly through the filtered lane and indirectly through loaded body shape. |
| HOLD | Original periodic forcing, capped full-state convergence and independently evolved seam. |

`SkitterProbe.bassFoundationDisabled = true` selects the exact R8 readout. `depthDisabled` retains the R7 compatibility path, and `quartetDisabled` retains its earlier source/readout path. The foundation is available at AGITATE zero as a voicing. The other five voices, patch format, macros, defaults and presets are unchanged.

## Listening comparison and validation

The focused audition compares fresh R8/R9 BASS at C1, C2 and C3 with soft, medium and hard touch. It starts at C2/medium/R9. Both sides use BUZZ `.55`, SKITTER `.50`, AGITATE `.60`, VENT `.35`, STATIC `.15`, HOLD `0`, seed `0`, 4× and complete natural finite notes. Synchronized switching, fixed shared gain, optional whole-note matching and original PCM24 downloads support the listening decision. The earlier R8 page remains unchanged. The archived R7/R8 generator explicitly selects the R8 readout so future regeneration does not relabel R9 BASS as R8.

The pre-candidate sound criteria use sustained common-control C2/C3 at medium/hard touch: at least a `3 dB` reduction in third-through-eighth-harmonic power relative to the fundamental, and at least a one-percentage-point increase in the fundamental's total power fraction. Three 16,384-frame Hann windows start at `.50`, `.70` and `.90 s`, ending before the natural gate. Attack and body are reported separately; no constant gain can pass these normalized measures. C1 and soft/default cells remain diagnostic comparisons. These criteria do not classify a sound as bass or brass.

All previous identity, touch, temporal depth, numerical and HOLD bars remain unchanged. The full-versus-`depthDisabled` BASS comparison includes the new readout and the bridge because that flag preserves R7's earlier voice. Filter-return and exchange ablations keep the new readout active on both sides and provide separate causal checks. A new trace-equality check protects the unchanged source/filter/bridge trajectories.

Frozen `Skitter.kt` SHA-256: `e87db196391a1f292017792bb36581b1f5438ee7e555dd24699832afb07d97fb`. The three new BASS methods retain both pre-candidate balance/health methods and add exact circuit-trace/state-peak equality across nine common cases. The existing 45 methods are unchanged.

| Common sustained note | Third–eighth / fundamental change | Fundamental fraction increase |
|---|---:|---:|
| C2, medium | −13.196 dB | +21.04 percentage points |
| C2, hard | −10.399 dB | +29.83 percentage points |
| C3, medium | −12.368 dB | +29.88 percentage points |
| C3, hard | −10.159 dB | +31.89 percentage points |

These are Kotlin Float32 raw renders independently analyzed with NumPy, without house leveling or PCM quantization. All 18 default/common C1–C3/three-touch cells reduce the measured upper/root ratio; the four predetermined common cells pass both balance criteria. C3 medium normalized temporal spread is `0.012058` with STATIC `.15` and `0.012101` with STATIC zero; centered trajectory distance from R7 is `0.019795` and `0.020085`. Both unchanged `.01` bars pass at the existing 2048-frame resolution. The longer 8192-frame spread remains smaller; this is not a claim of equal movement at every time resolution.

Independent isolated-loader comparison against 29 sealed original R8 classes matched all 126 outcomes: 125 successful renders with 18,495,964 raw/leveled Float values bit-exact, plus one pre-existing FRAY HOLD refusal with identical exception type/message. The latter is recorded separately and is not called bit-exact audio. Cases include the BASS R8 bypass, optional R7 depth bypass, and five unaffected voices at defaults, bright upper-register and common controls. Shared DSP sources are unchanged; both versions execute on this runtime, not on separate historical JVM installations.

Warm desktop JVM cost, run alone: C2/medium/common/seed0, finite `1.3 s`, 4×, three warmups and seven timed renders per side. R8 compatibility median `89.530 ms` (86.308–117.560); R9 median `97.968 ms` (97.038–103.414), about `8.44 ms` extra. This includes engine rendering and conditioning, excludes WAV/browser work, and makes no phone or HOLD timing claim. The source/bridge coefficients remain cached per render; the added readout uses fixed work per internal sample and no solver.

The complete Skitter admission and unfiltered JVM gate are reported with the pull request; source publication waits for the unfiltered gate's actual exit status.

## Evidence ledger

- **Verified:** the original R8 circuit emphasis, repository base, unchanged recursive circuit arithmetic, bounded low-body readout and compatibility branches.
- **Proposed:** emphasizing the fundamental and loaded second harmonic should make BASS lower and less brass-like while retaining movement.
- **Measured:** the balance, temporal-motion, exact-state, historical-outcome and desktop-cost checks above. Complete-suite status is recorded on the pull request.
- **Heard:** the owner described R8 BASS as brass-like. No R9 listening verdict is recorded.

## Why it is built this way

1. The fundamental and second harmonic can carry bass weight and growl without relying on the brighter upper filter response.
2. Both spectral memories still shape the low body. The new tone follows the actual instrument rather than another modulation clock.
3. The proven feedback circuit stays intact. The added readout cannot start a new source/filter feedback oscillation.
4. VENT still exposes the original filter, with less low-body contribution toward high-pass.
5. The R8 probe and sealed baseline make the comparison reproducible. Listening decides whether the balance is useful.
