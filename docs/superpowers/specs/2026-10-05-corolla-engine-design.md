# COROLLA — coupled mechanical flower

Built from the supplied 3 October 2026 Corolla engineering specification. This is a reduced musical DSP object; constants are proposed sound-design choices, not measurements from hardware. The provisional name and factory names remain subject to the owner's release review.

## Host decisions

- Offline, deterministic, mono, 44.1 kHz; nonlinear interactions run on the existing 4× clock. No external samples or persistent state between pads.
- All voices use TUNE over C3–C5 (MIDI 48–72), snapped to semitones, default C4. Five timbral macros and HOLD retain the proposed voice defaults and neutral values. TUNE is the existing host's requested-note interface.
- Velocity is the existing `Velocity.render` argument. It scales physical pull displacement and available powered input, rather than introducing another product control. Macro PULL selects release character within that event.
- Finite renders last 2.2–6 seconds. FIELD drive stops after a bounded envelope; FIELD zero has no powered input. HOLD below .99 lengthens the finite powered envelope. HOLD at .99 or above returns settled sustain material only. The current `Snip` sample API has no attack-plus-loop marker, so the initial blossom is absent from that buffer.
- Engine metadata routes finite notes as TONAL so pitched pads retain IN KEY and tonal retuning; HOLD routes as LOOP. The generic audio classifier independently labels these long recordings LOOP by its 1.5-second duration rule. This explicit engine metadata takes precedence when sending an authored Corolla patch to a pad. Only HOLD's top step closes as a loop.
- Patches, twelve complete factory presets, a sixteen-pad dry factory kit, generic CLI/shell replay, and the Android synth picker use existing interfaces. Dry synthesis contains the instrument's identity before rack processing.

## Passive object

Six petals have three flexural modes each. Fundamentals follow 1, 2, 3, 4, 5, 6 times the requested note. Each voice has its own upper-mode ratios, pull projections, modal decay, pickup radiation, neighbor transfer, chamber loading and field preference. Small deterministic variations remain between petals. These are modal choices for the imaginary instrument, not measurements of metal tongues. Modes approaching the final 19 kHz ceiling are attenuated before band-limiting and decimation.

| Voice | Root-petal mode ratios | Source character |
| --- | --- | --- |
| TONGUE | 1, 2.73, 5.43 | Local metallic pull, weaker ring transfer and chamber pickup; upper modes contribute to the attack and body. |
| BLOSSOM | 1, 2.15, 3.85 | Stronger reciprocal ring/chamber links, longer upper-mode response and a large aperture-dependent change in upper-mode radiation. |
| CHOIR | 1, 2.006, 4.012 | Sustained near-harmonic upper modes overlap neighboring petals' harmonic fundamentals; broader field distribution allows changing interference. |
| CHATTER | 1, 3.13, 7.47 | Wide metallic spectrum, smaller contact clearance, soft compliant repulsion and contact projection into all three modes. |
| ORBIT | 1, 2.66, 5.75 | Broad rotating field preference and magnetic releases inside the responding petals; stronger neighbor radiation. |
| HUSK | 1, 1.48, 3.12 | Close upper modes and substantially stronger chamber loading/radiation form a hollow body around the requested note. |

Voice differences originate in the resonator bank and its physical pickup projections. PULL changes the release's modal excitation; BLOOM changes relative upper-mode radiation as well as mechanical geometry. The shared loudness stage cannot supply these differences.

Each mode has mass-normalised displacement q, velocity v, and x = omega q. Free motion is an exact damped rotation of (x,v). The performer prescribes a short raised-cosine displacement ramp on the root petal and releases it with a consistent derivative. Neighbors receive no duplicate note-on impulse.

Six links form a ring. Each interaction is computed once per force evaluation with equal-and-opposite spring forces and relative-velocity damping. A smooth tanh travel bound limits the force gradient, and the corresponding log-cosh potential is included in stored energy. These links combine structural transfer and passive magnetic loading: FIELD zero still rings as a coupled object. They connect fundamental coordinates; contact projects into all three modes.

The chamber has four fixed-scale modes and reciprocal links to the root mounting. CHAMBER changes scale (about 620–145 Hz for its lowest mode), damping, pickup and loading. Voice profiles vary how strongly the body loads and radiates, with HUSK assigning it a substantial source role. BLOOM changes aperture losses and enclosure radiation. These chamber modes exchange energy with the petals; they are not independently played at note-on.

Spring self-load is removed from the free modal stiffness, leaving the static matrix's diagonal at its target value. Link strengths are bounded against each participating mode's unloaded stiffness, preserving positive free stiffness even with strong chamber loading. The full reciprocal stiffness matrix is eigensolved once per note, and the root eigenmode is compensated. Moving geometry and contact can make transient pitch shifts; moderate settings remain subject to the ±10-cent pitch gate. This compensation includes chamber and neighbor links.

Passive integration applies a half force kick, exact damped free rotation and a second half kick. Hinge updates preserve physical displacement q when omega changes. Symmetric splitting retains normal spring/contact exchange, but moving geometry and the reduced integrator are not exactly conservative. After each passive step, modal energy plus bounded-link and compliant-contact potentials is compared with the previous total. Any increase is removed by a uniform state contraction before powered input or pickup. The contraction is solved against the actual nonlinear potentials instead of assuming all energy scales quadratically. This formulation explicitly allows loss and forbids a passive energy gain; it is not an exact energy-conserving physical simulation. The source resets non-finite states deterministically.

## Opening, power, contact

Opening has one shared position and velocity. Smoothed measured vibration energy pushes against a return spring and damping; BLOOM sets resting opening and energy-response strength. Opening lags the pull and folds as the energy falls. It changes ring-link strength, collision gaps, chamber losses and radiation. It is never driven by a free-running opening oscillator.

FIELD zero turns the core off. Positive values log-interpolate from .15 Hz near zero through .3, 2, 10 and 40 Hz at macro .25, .5, .75 and 1. Its rotating spatial preference acts on amplitude-bounded velocity-feedback forces **inside** the petal modes. Voice-specific field depth changes that preference, while field spread sets responding petals' target energy. Smooth magnetic passage/release forces excite responding petals that feedback alone cannot wake from zero. The root receives a finite initial release, then velocity feedback maintains its free pitched motion; repeating impulses on it had entrained the note toward a core harmonic. All release work uses the same source budget; no oscillator or amplitude modulation is mixed onto the finished recording.

Powered feedback accounts for each mode's aperture and mounting losses before bounded growth and saturation. Each velocity kick proposes net work `v*dv + .5*dv²`. The requested root fundamental reserves a share of each step's available work; all other modes share the remainder. Separate quadratic work solves bound both groups, preventing isolated upper modes from exhausting the root's maintenance supply. Release forces can also absorb energy, so recorded powered input is net work. The combined positive net input is bounded by `.025 + .45 * FIELD` mass-normalised energy units per second times event strength, further scaled by FIELD in finite mode and by its finite envelope.

HOLD at FIELD zero uses gentle powered velocity feedback to offset losses of the same network. This is an explicit maintenance source, not passive endless sustain or a substituted oscillator. With no pull and powered input disabled, the zero-state object is silent. Diagnostics separate input work from stored passive energy.

Adjacent petal contact uses projected displacement and velocity, a smooth compliant repulsive force, and closing-only damping. CONTACT zero removes the entire collision path. Clearance depends on CONTACT, opening and voice geometry; strong events and folding promote chatter. Elastic stiffness, damping and modal projections vary by voice.

For positive normalized penetration z, the elastic force uses `z² / (.03 + z)`. Its stored potential is `CONTACT * elastic * (.5*z² - .03*z + .03²*ln(1 + z/.03))`. This potential returns work to the petal coordinates during separation. Closing-only damping dissipates work separately. CHATTER projects contact weakly onto the fundamental and strongly onto upper flexural modes, representing a contact point near a fundamental node; this retains the requested pitch while making the upper structure chatter. Measuring the potential prevents the passive guard from treating ordinary elastic rebound as a new source. Contact activity, stored contact energy and correction work are independently observable; no contact noise is pasted onto the mix.

## Held loops and output

Held root cycles snap to integer output frames, with less than about 0.1 cent of frame quantization. Loop periods are 1.8–4 seconds and contain an integer number of core orbits. Very slow cores use the nearest compatible **nonzero** rate; their exact one-shot rate is not promised in HOLD.

The entire network runs through bounded preroll and three candidate periods. Two successive candidates are compared by full-buffer waveform change. The less-changing region is selected, then Tremor's unity-sum smoothstep wrap blend closes the final 1,280 frames against the preceding region; the last 256 are identical. This closes output despite remaining chamber, contact and hinge-state differences; it is not a claim of an exactly periodic physical state. The full buffer passes `Keys.seamError < 1e-3`. Tests also compare the actual last-to-first step and slope to the signal's interior changes, and check settled pitch.

Shared band-limiting and cascaded decimation precede DC removal and `MELODIC_LOUDNESS_TARGET`. Finite tails are faded; held buffers are not. Diagnostic `finish(normalize=false)` and `renderLoop(normalize=false)` support raw versus loudness-matched audition. Recorded internal taps are root, responding petals, chamber, contact activity, opening, total stored energy, net powered work, stored contact energy and passive correction work, all at 176.4 kHz.

## Review and validation

`./gradlew --no-daemon test` is the JVM gate when no Android SDK is installed. Engine tests cover passive decay, source isolation, neighbor causality, opening lag/folding, input work, contact removal, macro activity, velocity, root prominence and pitch, deterministic renders, serialization, factory names/routing, finite extremes and held seams. Android assembly remains a separate SDK-dependent check.

`CorollaContactEnergyTest` isolates passive contact with fixed geometry and no links, chamber or powered input. It requires nonzero stored elastic work, measured return into modal energy during rebound, non-increasing total energy after release, and guard correction below 10% of released energy. CONTACT zero must store exactly zero contact energy.

`CorollaTimbreTest` holds C4 and render time fixed. It compares normalized 4,096-frame spectral-band envelopes in early and body windows; gain, phase drift and different tail lengths cannot supply the required separation. All fifteen voice pairs must exceed the envelope-distance floor, and the body must retain the requested root. Named control sweeps separately require spectral changes for PULL, BLOOM, FIELD, CONTACT and CHAMBER. Source-role checks require ongoing CHATTER contacts and a substantial HUSK chamber contribution after the attack. ORBIT's normalized centroid and partial balance must move at the core rate, beyond the rejected version's gain-dominated response. These regression checks accompany pitch and loop gates; they do not replace owner listening.

Run `./gradlew :synth:generateCorollaAudition` for the full 373-case listening matrix, or add `-PcorollaQuick` for 18 cases. Both include six C4 comparisons cropped to 1.25 seconds before shared level matching, followed by defaults and held versions of each voice. Open `testkit/corolla-audition/index.html` to compare raw and matched clips and save listening notes. The full matrix also includes pitches/velocities, five-step sweeps, the five required interaction grids, corners, all factory presets and coupling isolation. Automated checks establish behavior and stability; the owner's listening verdict is still needed for sonic acceptance.

### Listening regression (5 October 2026)

The first audition passed pitch, stability and routing checks, but the owner heard mostly note and ring-length changes. Measurement of those default clips confirmed that 97–99.8% of the .12–.70-second body energy lay within ±2% of C4, with spectral centroids around 262–274 Hz. Large raw waveform distances and very strong root dominance had accepted nearly sine-like results. That calibration is superseded by the per-voice source profiles and fixed-duration spectral gates above.

Final revision measurements use C4, velocity 1, a fixed 1.05-second render and normalized spectral frames in the .20–.60-second body. Root power here is the broader .75–1.22 f0 band; held pitch is measured independently from the delivered settled loop.

| Voice | Body root-band power | Body centroid | Default held pitch error |
|---|---:|---:|---:|
| TONGUE | 93.1% | 293 Hz | +0.05 cents |
| BLOSSOM | 15.9% | 557 Hz | +1.39 cents |
| CHOIR | 66.8% | 374 Hz | +0.29 cents |
| CHATTER | 90.0% | 337 Hz | +0.61 cents |
| ORBIT | 72.6% | 395 Hz | +5.68 cents |
| HUSK | 66.2% | 307 Hz | −0.62 cents |

The minimum mean early/body envelope distance across fifteen pairs is .07534, versus .00132 on the rejected source measured with the same fixed-frame method. TONGUE/CHATTER remain the closest pair. Named macro distances are .147 for PULL, .388 for BLOOM, .178 for FIELD, .381 for CONTACT and .503 for CHAMBER. CHATTER contacts remain active in 7.89% of body samples; HUSK chamber/direct RMS is .378, with a further 33% of its spectral power between 1.22 and 1.8 f0. ORBIT's normalized centroid moves 17.3 Hz and its root power 3.39 percentage points at the 2.76 Hz diagnostic core rate; the rejected source moved 1.06 Hz and .406 points. All nine calibrated held pitch cases, including FIELD-zero maintenance at C3/C5, lie between −.62 and +5.68 cents. These are source and regression measurements; sonic acceptance remains the owner's listening decision.

Deferred: independently played petals, phrase/pad memory, stereo rotation, hardware, full field simulation, thermal behavior and recorded surface materials. Dedicated held keygroup instruments are not part of this V1; the loop buffer can be exported through the existing sample route.
