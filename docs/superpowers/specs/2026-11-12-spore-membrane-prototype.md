# Spore Membrane: kick and snare listening prototype

Status: two synthesized voices for owner audition. This is not a complete engine or a replacement for the current acoustic shelf kit.
Repository inspected: `5f42c761`, then merged current base `1d8f24ec`. Verified APIs: `Modes.Bank` tune/connect/step/drive/velocity/displacement/energy, `Dsp.Noise`, `Tide.bandLimit`, `Dsp.decimate`, `KitAssembler`, `PadHit`, `KitStore`, `SfzWriter`, `SfzImporter`.

The characteristic gesture is **finite stick/beater contact → coupled heads and shell → wire collisions → decaying body**.

## Mechanism and landing

This is a sample-free Kotlin prototype, independent of SKIN, BACKWASH and the recorded bank. The audition generator alone reads acoustic recordings for clearly labeled comparisons. Five modes per head use the existing circular-membrane ratios in `Modes.tableFor(MEMBRANE)`. Three shell modes and sixteen snare-wire modes complete the system. Head participation, shell frequencies, losses, wire gaps/masses and restitution are voicing hypotheses, not fitted measurements. There is no full membrane PDE, air-flow solver, anatomical drummer or room simulation.

Nearest siblings: SKIN has independent decaying partials and shaped snare noise; BACKWASH uses a finite contact but its wire-noise envelope follows body energy in one direction. Neither source implements reciprocal head/wire collision impulses. Existing `Modes.Bank` supplies bounded head-to-head and head-to-shell coupling; the prototype adds unilateral collision response.

## Three directions considered

| Direction | Gesture/novelty | Risk/cost | Decision |
|---|---|---|---|
| SPLICE a better exciter into existing drums | Better onset, same subsequent gesture | Low/low | Insufficient for the feedback |
| COUPLE two heads and contact wires | Energy transfer and contact chatter emerge during decay | Moderate/moderate | Selected |
| Full spatial membrane and wire-chain solver | Richer spatial/contact detail | High/high | Defer until the reduced model earns a listening pass |

## Signal flow

```
velocity + take → finite noisy contact pulse → batter head ↔ bottom head
                                             ↕ shell       ↕ collision impulses
                                                           wire modes
velocity + contact-force radiation pickups → 4× low-pass/decimate → DC removal → fixed radiation gain/final fade
```

Noise is restricted to the finite excitation window. There is no independently enveloped snare-noise layer, sampled transient, added reverb, compressor or per-hit normalization. Noise/participation changes come from `Dsp.seedFor` and take. RR changes strike position, duration, force and surface roughness; it does not retune the instrument. Fixed radiation gains preserve velocity dynamics. Snare readout combines head velocity, high-passed wire velocity and band-limited contact-force impulses. The contact-force term is an observation of actual collision impulses, not another excitation. These radiation weights/filters are voicing approximations, not a calibrated microphone or acoustic field. Audition loudness matching happens outside the model and can be disabled.

## Controls

| Control | Default | Model quantity | Interaction/proof |
|---|---:|---|---|
| TUNE | .50 | Head mode frequencies; kick 48–85 Hz, snare 200–320 Hz | BODY changes coupled modes; no promise of exact musical root |
| DAMP | .45 | Head and shell t60 losses | Shortens head motion available to excite wires |
| STRIKE | .40 | Modal participation and contact width | Velocity additionally changes contact width and high-mode drive |
| BODY | .55 | Head/shell coupling and bottom-head tuning ratio | Disabling coupling leaves the unstruck bottom head stationary |
| WIRES (snare) | .60 | Wire frequencies, decay, contact gap and restitution | Disconnecting contacts silences the wire modes |

Hits are finite 1.2-second buffers with a 4 ms final fade; HOLD is not applicable to this first drum prototype. Parameters are strict finite 0–1. A model version and explicit settings accompany the render manifest; no new sealed Patch or app picker is registered before listening acceptance.

## Stability and energy

The shared bank validates positive stiffness and split-step stability; maximum node coupling here is below .53. For a contact vector b and relative velocity u>0, `J=-(1+e)u/||b||²`. The instantaneous kinetic change is `-.5(1-e²)u²/||b||²`, nonpositive for the chosen restitution .12–.38. Equal-and-opposite impulses act on bottom-head participation and wire mass coordinates. No position correction injects energy. This is a reduced velocity-impact model, not a resolved elastic contact deformation.

Diagnostics measure actual bank energy before/after every contact, post-excitation energy and final energy before audio conditioning. Tests check energy growth against the independent end-of-excitation state, coupling transfer, real contact counts, determinism, velocity-dependent waveform shape, RR, silence at zero velocity, finite/DC/peak/tail behavior, and each applicable control. The output runs at 4× before `Tide.bandLimit` and decimation; nonlinear contacts still require spectral/oversampling-convergence review before production, not a claim that all aliasing is eliminated.

## Performance and integration

Coefficients and contact/participation vectors are built once per render, outside the sample loop. There are fixed 13/29 modes and at most 16 contact checks per internal frame. No geometry solve or unbounded iteration runs in the audio loop. The target is tens of milliseconds per rendered hit; the generator records actual JVM timings, and phone timing remains unverified. If timing exceeds the target, optimize/profile the contact path before app integration.

The prototype ZIP contains two native/SFZ pads, each with three velocities and three deterministic physical restrikes. Its generator checks saved PCM windows and SFZ export/import; the shell integration test checks actual PadHit playback cycling including wrap and velocity gains. It does not claim MPC hardware completion. No acoustic recordings are in this prototype ZIP; reference audio on the page is separately credited under CC0.

## Gates and ledger

- Verified/measured evidence is recorded by tests, diagnostics and the generation manifest; it is not a listening judgment.
- Owner listening: pending. Realism and jungle character remain hypotheses.
- Native/SFZ save/playback/export: generator and shell integration test verify the prototype's complete grids.
- Drum shelf variants, authored roll/riser, full kit/cymbals, registered patch editing, Android and MPC hardware: pending; this is deliberately the kick/snare proof first.
- Melodic/keygroup/chord-tool and string STRUM requirements: not applicable to these drum voices.
- Existing acoustic Spore Station shelves/downloads remain separate and clearly labeled.

## Measured prototype run

The focused mechanics suite (four tests) and native playback integration test pass. Asset validation passes for 42 WAV files, two native pads and 18 SFZ regions. Median hit render time was 42.20 ms, maximum 82.58 ms on this JVM, not a phone. The default hard snare produced 10,019 contact events with no measured positive contact-energy addition. Page controls passed a DOM mock; browser audio decoding and listening acceptance remain unverified. The full repository test suite was not rerun.

## Why it is built this way

- Coupling lets the second head answer the first rather than layering another pre-shaped sound.
- Wire collisions consume mechanical energy and alter head motion, so their chatter depends on the strike.
- Changing contact position and duration creates repeat variation while preserving the drum's tuning.
- Acoustic, earlier-synth and mechanism-off comparisons expose whether the new source actually earns the next development round.
