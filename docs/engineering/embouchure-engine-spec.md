# Embouchure — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; naming checks pending

## 1. Intent and reference

Embouchure is a preset-first physical-modeling lead/bass engine focused on conventional expressive roles: reeds, muted brass-like tones, breathy bass, whistle-like leads, and restrained bowed sounds. Presets include a playing gesture rather than only a static tone.

The Yamaha VL1 supplies documented inspiration for a pressure-driven exciter, resonating pipe/string, and virtual playing input: [manufacturer glossary](https://www.yamaha.com/ussub/GlossaryDetail.html?CNTID=10060). The simplified drivers, gestures, and controls here are original proposals, not Yamaha algorithm reconstruction or factory-data reproduction.

Its signature is **effort changing how a note speaks**, beyond volume. No repository was inspected; numeric targets need prototype calibration. The supplied edited Gyre contract governs SnipSnap integration.

## 2. Contract and budget

Deterministic offline mono, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation, shared melodic loudness, patches, presets, kits, auditions, MPC export, and pitched classifier guards. Each render starts fresh; no samples, breath-controller hardware, cross-note legato, or persistent pad state.

V1 uses one root-bearing waveguide/impedance resonator, one selected nonlinear exciter, one gesture controller, a small 2–3-mode body, one seeded roughness source, and output conditioning. Choose reed-like flow, lip-like periodic contact, or bow-like friction per voice; do not run all drivers by default.

Unlike Gyre, no multi-string bank, sympathetic cloud, or rotating field is required. Keep the architecture focused on one modeled instrument and one expressive player gesture.

## 3. Driver/resonator coupling

The driver must receive resonator pressure/velocity feedback and deliver energy into that same resonator. A static oscillator with a breath-noise overlay is insufficient. Use stable regularized flow/contact/friction laws, borrowing current waveguide infrastructure where appropriate.

Reed-like mode derives flow from bounded mouth-to-bore pressure difference and reed aperture. Lip-like mode uses a compliant driven element coupled to bore pressure. Bow-like mode uses relative velocity and pressure through a smooth bounded friction model.

Document equations, units, state update order, contact bounds, and energy source. Source work ends when the virtual player's finite gesture ends. Driver/body/resonator losses must cause passive decay afterward. A final limiter cannot hide an unstable self-oscillating loop.

## 4. EFFORT and playing gestures

EFFORT scales gesture intensity: pressure/speed trajectory, contact loading, onset articulation, and the depth of temporary spectral/pitch deviations. Low settings speak gently; high settings lean into the note, add bite, or briefly approach a higher oscillation regime.

Patch gestures include soft entry, leaning swell, brief overpressure, pressure release, and bowed capture. Represent each as a small set of smooth time/value segments, not opaque random motion. Velocity changes input energy within the selected EFFORT character.

For pitched defaults, any overblown region must be brief and return to the requested root. Do not let high effort silently latch into a permanent octave. Probe capture behavior across register and use driver-specific limiting/calibration where necessary.

## 5. REED and driver character

REED is a generalized contact-character macro. In reed voices it changes aperture, stiffness, and closure response. In lip voices it changes compliance/capture. In bowed voices it changes friction shape/contact sharpness. Low values favor softer rounded contact; high values favor tighter bite.

The selected driver type stays fixed per voice/patch. REED does not abruptly switch a reed into a bow. Driver-specific correlated mappings provide a comparable musical role without pretending the mechanics are identical.

Keep working pressure/contact regions audible at both ends. Avoid a large silent threshold zone or numerical stiffness that increases cost without useful sound.

## 6. SHAPE, GRAIN, and LENGTH

SHAPE changes resonator impedance/body character: compact/open through darker/hollower/narrower response according to voice. Coordinate bore loss, reflection/radiation, body modes, and excitation position where relevant. Compensate effective tuning so normal macro changes preserve the requested root.

GRAIN increases seeded friction/airflow roughness, restrained turbulence, and small contact instability. Noise follows the driver gesture and is spectrally integrated. At zero the tone is clear; at high settings normal presets remain pitched. Do not inject arbitrary noise into the feedback loop without a bounded transfer.

LENGTH changes finite gesture duration, sustain window, relaxation, and release. Proposed totals are 1–3 seconds for short leads/bass and 3–6 for sustained gestures within host limits. Preserve enough time to hear the effort contour; short settings compress it smoothly rather than truncating pressure segments discontinuously.

## 7. Tuning and playable register

Calibrate the resonator's effective delay/impedance after driver and body loading. Driver-dependent pitch capture may differ from nominal delay tuning. Establish supported note ranges through probes and listening, not an untested full-MIDI promise.

Moderate patches must speak reliably at the requested root. Preserve a useful tonal anchor during breathy or rough transitions. Seed small variations deterministically and keep ordinary deviations restrained. The model's response to effort should affect articulation/spectrum more than sustained tuning.

## 8. Macros

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| EFFORT | Gentle speaking → strong expressive gesture | .45 | .40 |
| REED | Soft compliant contact → tighter bite | .45 | .45 |
| SHAPE | Open/compact → hollow/narrow body character | .40 | .40 |
| GRAIN | Clear → rougher air/friction texture | .20 | .10 |
| LENGTH | Short gesture → sustained phrase | .50 | .50 |
| HOLD | Finite playing gesture → seamless sustain | 0 | 0 |

Defaults are hypotheses; confirm MacroSpec semantics. Required interactions: EFFORT × REED, EFFORT × GRAIN, REED × SHAPE, and EFFORT × LENGTH. Every normal voice needs useful activity from all five timbral controls.

## 9. Voices and presets

Proposed enum: `EmbouchureVoice { REED, BRASS, BASS, WHISTLE, BOW, SOFT }`.

| Voice | EFFORT | REED | SHAPE | GRAIN | LENGTH | Driver/role |
|---|---:|---:|---:|---:|---:|---|
| REED | .45 | .50 | .40 | .20 | .50 | Reed-like articulate lead |
| BRASS | .55 | .45 | .45 | .15 | .50 | Lip-like muted/brassy lead |
| BASS | .45 | .40 | .55 | .20 | .40 | Root-bearing breathy low tone |
| WHISTLE | .35 | .30 | .20 | .10 | .55 | Clear high-register driver calibration |
| BOW | .45 | .45 | .40 | .25 | .65 | Bow-like friction sustain |
| SOFT | .30 | .25 | .50 | .20 | .75 | Gentle pressure-driven body |

WHISTLE is initially a simplified air/reed-derived clear-tone approximation; V1 does not require a separate accurate jet-edge model. Describe it honestly in source metadata.

Target 36 original presets: 8 reed leads, 6 brass-like, 6 bass, 4 whistle, 6 bowed, 6 soft sustains. Names: Leaning Reed, Muted Bell, Breath Pocket, Soft Entry, Short Overblow, Relaxed Bow, Narrow Air, Held Effort. Approve dry identity before widening the roster.

## 10. HOLD

HOLD provides explicit continuing player energy. Use a settled pressure/contact state or a low-depth repeatable effort contour, preserving EFFORT and LENGTH relevance through held articulation/contour shape. The finite onset belongs to attack-plus-loop only if the host supports it.

Converge driver, waveguide, body, gesture, and noise states during bounded preroll. Limit chaotic driver regimes for HOLD if required and document that calibration. Periodic gesture phase alone does not make nonlinear oscillation periodic.

Use periodic seeded roughness and the existing pitched-loop approach; do not force coarse frequency quantization that changes root pitch. Apply project-standard wrap handling after selecting a settled region and require `Keys.seamError < 1e-3` plus listening. Check pitch capture, rough bowed cases, and high-effort pressure cycles.

## 11. Safety and integration

Bound source pressure/speed, driver gradients, aperture/contact, energy, coupling, resonator losses, and coefficient rate. Choose an appropriate stable solver for stiff driver interactions. Validate solver/control-rate convergence and high-register bandwidth. Prevent DC, denormals, non-finite state, clicks, and aliasing; band-limit before decimation.

Use `Dsp.seedFor` or current seed infrastructure. Identical inputs/driver/gesture/patch/seed reproduce samples and state traces. Use shared `MELODIC_LOUDNESS_TARGET` after synthesis and compare raw/normalized velocity responses.

Implement `Embouchure.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap with current interfaces. Serialize driver/resonator/gesture/envelope/loop configuration using sibling conventions. Standard pitched cases avoid KICK/SNARE/CLAP/HAT/TOM classification; noisy entries and low pressure bursts need probes.

## 12. Build and acceptance

1. Build one stable reed/resonator and root capture; probe energy, pitch, level, and loss.
2. Add smooth effort gestures and macros; approve moderate dry identity.
3. Add lip/bow calibrations, voice ranges, extremes, and presets.
4. Complete HOLD/integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe pitch capture, peak/RMS, DC, decay, gesture response, noise balance, aliasing, solver stability, cost, determinism, macro activity, classifier, serialization, and seam before fixing bounds. Audition voices across supported notes/velocity, five-point sweeps, interaction grids, all-high cases, and difficult HOLD states.

Acceptance: reliable root speaking; effort changes contact/spectrum beyond gain; driver feedback ablation removes modeled interaction; useful SHAPE/GRAIN; finite passive decay after source removal; bounded deterministic extremes; meaningful held gesture; patch/routing checks; and seam metric/listening success. Owner sonic approval precedes preset expansion.

## 13. Deferred scope

Defer VL1 algorithm/data compatibility, literal acoustic accuracy, live breath controllers, cross-note legato, stereo, automatic chords, and large resonator networks. Confirm supported register, duration, velocity, loop format, APIs, and name. Embouchure succeeds when a preset sounds like a note played with a particular kind of effort.
