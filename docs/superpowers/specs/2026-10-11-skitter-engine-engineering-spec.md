# Skitter Engine — Engineering Specification

## 1. Summary

**Skitter** is a pitched synthesis engine for SnipSnap inspired by the EDP Wasp, extended into an imaginary circuit whose filter reacts to its recent signal history.

Two sharp oscillators temporarily diverge and recover at unequal speeds. Their signal and a noise exciter provoke a nonlinear multimode filter. The filter develops bounded agitation, flutters, and settles. This reaction is part of sound generation, not a distortion effect applied to a completed synth sound.

Sonic north star: **provoke → flutter → settle**. A compact bass should transform into a nervous, rasping electronic instrument while preserving a useful pitched body.

This document is an implementation handoff, not a completed engine. All new numerical mappings and defaults are prototype hypotheses unless stated as existing project requirements. Skitter is a working name; naming availability has not been assessed.

## 2. Historical Foundation and Proposed Extension

The original Wasp manual describes two oscillators, saw and square/pulse waveforms, oscillator interval adjustment, and noise/control facilities. Its service instructions describe temporary oscillator beating during glide before tuning settles again. These provide the oscillator foundation for Skitter.

Primary references:

- [Original Wasp user manual](https://www.polynominal.com/edp-wasp/edp-wasp-manual.pdf).
- [Original test instructions and service manual](https://www.polynominal.com/edp-wasp/edp-wasp-service-manual.pdf), including oscillator/glide checks 34–39 and digital noise check 44.

Skitter's signal-history memory, agitation dynamics, noise-driven perturbations, and repeated recovery gestures are new design proposals. Do not attribute them to the original hardware. V1 requires a stable perceptual tribute, not a transistor-level reconstruction, copied presets, or external samples.

## 3. Platform and Scope

Follow the supplied Gyre conventions: deterministic offline mono sample generation, 44.1 kHz output, 4× internal oversampling, five timbral macros plus HOLD, shared melodic loudness leveling, JSON patches, presets, kit integration, audition generation, and optional seamless held loops.

Confirm current repository interfaces before implementation. This handoff has not inspected or changed the source repository. V1 generates one requested root note per sample; it does not require live MIDI performance, keyboard touch sensing, polyphonic allocation, or stereo processing.

Target rounded basses, sharp leads, hollow filtered tones, nervous pulses, resonant chirps around sustained pitches, and noisy but melodic textures. Useful moderate sounds must exist before tuning extreme agitation.

## 4. Core Architecture

Use two band-limited oscillators, unequal pitch-recovery states, a seeded noise exciter, an excitation envelope, input-energy and signed-history detectors, a bounded agitation system, and one nonlinear state-variable-style multimode filter.

Per oversampled update:

1. Update gesture timing and oscillator pitch recovery.
2. Generate and mix oscillator waveforms and noise.
3. Read the previous agitation/history state to derive filter parameters.
4. Process the current input through the filter.
5. Update signal-history detectors and agitation for the next update.
6. Mix low-, band-, and high-pass outputs using VENT.

Then apply DC removal, output envelope as needed, antialias filtering, decimation, and shared leveling.

Explicitly delay the control feedback by one internal update or use a documented stable solver. Do not calculate instantaneous filter parameters from their own unsolved output. Keep diagnostics for raw signal, filter states, history, agitation, and pitch offsets.

## 5. Oscillators and BUZZ

Both oscillators follow the requested root, with voice-specific interval or restrained detune. Start most voices near unison. HOLLOW may use a fixed musical interval; this is part of its patch configuration, not independent note allocation.

BUZZ continuously changes waveform edge content and duty cycle: low values favor rounded triangle/saw blends; higher values emphasize brighter saw/pulse combinations and narrower pulses. Waveforms beyond the historical reference are deliberate Skitter extensions.

Use band-limited generation and bounded pulse width, initially approximately 0.12–0.50. Keep waveform gain comparable across the macro. “Pulse density” here means harmonic density from waveform shape, not extra note triggers or unconstrained frequency multiplication.

Retain enough direct fundamental in standard voices for filtered settings to remain melodic. VENT may preserve a quiet direct pitched path where needed, with its amount defined by voice. This support must not overwhelm the filter or erase VENT's audible effect.

## 6. Unequal Recovery and SKITTER

Represent each oscillator's pitch departure as an offset in cents from its own base frequency. Each offset approaches zero using a stable exponential relaxation:

```text
offsetA *= exp(-dt / recoveryTimeA)
offsetB *= exp(-dt / recoveryTimeB)
frequencyA = baseA * 2^(offsetA / 1200)
frequencyB = baseB * 2^(offsetB / 1200)
```

A gesture adds bounded departures with differing direction, magnitude, or time constant. Do not integrate unconstrained pitch drift. At a finite note's onset, initialize a deterministic gesture so offline rendering produces the defining effect without requiring a preceding MIDI note.

SKITTER raises excursion and increases repeated recovery activity. Suggested moderate excursions are 5–35 cents; experimental excursions may reach approximately 100–250 cents if probes retain a perceptible root. Begin with unequal recovery times around 20–350 ms. These are listening targets, not acceptance constants.

Repeated gestures come from a bounded deterministic scheduler influenced by prior agitation, with a minimum interval and explicit maximum event rate. Low settings favor one departure and settlement; high settings repeatedly pull the pair apart. Use hysteresis and a refractory interval to prevent rapid event chatter.

SKITTER = 0 disables these departures. The base tone remains useful. At nonzero low settings, audible early beating or phase movement must appear; do not reserve all behavior for the upper half of the control.

## 7. Filter With Memory

Use a stable multimode topology with coherent low-, band-, and high-pass outputs. Start with an existing tested repository filter if suitable. Nonlinear behavior belongs in the excitation/feedback structure of the filter, with explicit state and coefficient limits.

Maintain two slow memory variables:

- **Energy history:** a smoothed, normalized estimate of recent input power, optionally including a bounded contribution from previous band-pass power.
- **Signed history:** a much smaller smoothed estimate of signal imbalance that can move distortion asymmetry. Clamp it tightly and DC-block the resulting output.

Energy memory should rise quickly after a strong excitation and decay more slowly. A prototype range is 2–15 ms attack and 40–400 ms recovery, varied by voice and AGITATE. Normalize detector values against a defined internal signal scale, not the final leveled output.

Map history into bounded changes of drive, saturation asymmetry, effective damping/resonance, and cutoff emphasis. A sustained note after a hard attack should gradually change character as history settles. The same instantaneous waveform can therefore receive different treatment depending on what preceded it.

A possible zero-centered nonlinear stage is:

```text
shaped = tanh(drive * signal + bias) - tanh(bias)
```

Apply deliberate gain scaling, and constrain drive and bias. This equation is a candidate transfer function, not evidence of a Wasp circuit model. Do not replace the topology's stability analysis with a final limiter.

## 8. Agitation Dynamics

Use a bounded damped second-order control state, analogous to a flexible component recovering after excitation. Input-energy rises, oscillator recovery events, and noise deviations supply its forcing.

```text
acceleration = forcing - 2*damping*omega*velocity - omega^2*position
```

Implement with a stable discrete update or exact discretization. Scale forcing into defined control units. The continuous equation alone is not an implementation or stability guarantee.

AGITATE raises forcing sensitivity and modulation depth and may reduce damping within an explicitly safe interval. Begin with flutter frequencies around 3–30 Hz. At moderate settings the attack produces a few audible excursions that settle; high settings produce sustained nervousness when energy continues entering the system.

Position modifies cutoff in bounded log-frequency units and makes smaller changes to filter drive and resonance. It should not merely multiply final output amplitude. The filter's response must visibly and audibly influence the subsequent history/agitation state.

At AGITATE = 0, remove dynamic memory modulation while retaining the base filter character. Nonzero low settings should create a subtle recovery signature. Continuously driven high settings may remain active, but once excitation ends the agitation and audio states must decay.

## 9. STATIC — Noise as Provocation

Generate noise from the existing deterministic seed mechanism, such as `Dsp.seedFor`. Shape it to avoid uncontrolled high-frequency energy. STATIC increases both a limited audible noise feed and a separate bounded forcing contribution to agitation.

Use a zero-mean noise-derived perturbation, for example deviations from a smoothed noise-energy estimate, rather than only adding a constant positive agitation bias. This permits sputters and irregular chirps while preserving a stable operating point.

STATIC = 0 removes the noise source. Low nonzero values should create edge variation, not an inaudible region. At high values, standard pitched presets retain a strong oscillator body. Noise must not become a standalone hat/snare generator.

To prove this control's identity, compare two renders with matched audible noise gain: one with noise forcing disabled, the other enabled. The second should change the filter's behavior, not only add hiss.

## 10. VENT and Macro Set

VENT continuously blends low-pass at 0, band-pass near .5, and high-pass at 1. Crossfade coherent outputs from the same filter state; do not switch filter models or reset states. Tune gain compensation by measurement because these outputs are correlated and a generic equal-power blend may create unexpected peaks or notches.

| Macro | Main action | Starting neutral |
|---|---|---:|
| BUZZ | Waveform edge content and pulse-width character | .45 |
| SKITTER | Unequal pitch departure, recovery, and recurrence | .25 |
| AGITATE | Signal-history sensitivity and filter flutter | .35 |
| VENT | Low/band/high-pass emphasis | .25 |
| STATIC | Audible noise and noise-driven filter perturbation | .15 |
| HOLD | Finite sample to seamless sustained loop | .00 |

Required interactions: BUZZ × AGITATE changes how waveform edges provoke the filter; SKITTER × AGITATE makes recovery gestures stimulate flutter; STATIC × AGITATE produces noise-provoked reactions; VENT × AGITATE changes which parts of the reaction are exposed. Avoid five independent effects with no internal interaction.

## 11. Voices and Defaults

```kotlin
enum class SkitterVoice { BASS, LEAD, HOLLOW, FLUTTER, CHIRP, FRAY }
```

| Voice | BUZZ | SKITTER | AGITATE | VENT | STATIC | Purpose |
|---|---:|---:|---:|---:|---:|---|
| BASS | .40 | .20 | .30 | .10 | .05 | Compact bass with a recovering edge |
| LEAD | .70 | .45 | .45 | .25 | .10 | Sharp, briefly tearing melodic tone |
| HOLLOW | .35 | .30 | .25 | .50 | .10 | Open intervals and nasal resonance |
| FLUTTER | .55 | .55 | .70 | .40 | .20 | Repeated departures and filter movement |
| CHIRP | .50 | .25 | .65 | .65 | .35 | Resonant chirps around a pitched sustain |
| FRAY | .85 | .65 | .85 | .45 | .60 | Dense, noisy, agitated melodic texture |

Defaults are hypotheses. Voices vary envelopes, oscillator intervals, history times, agitation damping, and root support using the same architecture. CHIRP must remain a pitched voice rather than a transient-only effect.

## 12. Envelope, Velocity, and Duration

Velocity changes excitation energy, early waveform brightness, recovery-gesture strength, and initial filter provocation. Keep every influence bounded; velocity is not only final gain.

Start with finite duration targets of 1–4 seconds for BASS/CHIRP and 2–6 seconds for the other voices, subject to project limits and measured tails. Stop repeated gesture excitation before release. Allow filter and history states to settle, then terminate based on energy or apply the project-standard tail treatment at the duration cap.

Use shared `MELODIC_LOUDNESS_TARGET` leveling. Retain raw prelevel renders for diagnosis. Equalized final loudness must not conceal runaway internal states, dead controls, or classifier problems.

## 13. Determinism, Stability, and Aliasing

Identical voice, note, patch, velocity, seed context, and render settings must produce sample-identical output within the supported runtime. Use separate deterministic seed streams for noise and initial variation where repository conventions permit.

Bound filter coefficients/states, damping, drive, bias, agitation, detector outputs, event rates, and oscillator departures. Validate all-high controls and maximum velocity across registers. The engine contains powered oscillators; do not claim acoustic passivity as a stability proof.

4× oversampling is required but not sufficient for sharp pulses, nonlinear filter stages, or noise-driven parameter motion. Use band-limited oscillators, smoothed coefficients, suitable antialias filtering before decimation, and register-dependent depth ceilings if needed. Compare bright extremes against a higher-rate reference during development.

No NaN, infinity, persistent DC buildup, uncontrolled state growth, or hidden hard clipping is acceptable. Output limiting is not a repair for unstable dynamics.

## 14. HOLD Strategy

HOLD = 1 must loop the whole coupled system: oscillator phase, recovery offsets, gesture timer, detector histories, agitation position/velocity, filter state, envelopes, and noise position. A seamless oscillator alone does not make this engine seamless.

Use the existing pitched-loop method to preserve the requested note. Do not coarsely quantize pitch to fit an arbitrary duration. Lock repeated gesture timing to the selected loop period and use a deterministic periodic noise sequence with a treated noise wrap.

Preroll repeated periods until histories, agitation, and filter states converge within measured tolerances. Keep an explicit preroll/search cap and diagnostic failure path. For difficult settings, HOLD may use a periodic replay of gesture timing and reduced stochastic forcing while retaining the audible flutter/recovery character. Document this approximation; do not silently disable AGITATE or STATIC.

Apply the repository's minimal wrap crossfade only after selecting a stable region. Check exported, leveled audio using `Keys.seamError < 1e-3`, then listen for clicks, extra attacks, pitch movement at the boundary, and noise-pattern repetition that overwhelms the instrument.

## 15. Patches, Presets, and Integration

Implement `Skitter.kt`, `SkitterPatch`, `SkitterPresets`, patch registration, `SynthKits` entry, pitched `drumClassFor` mapping, audition generation, tests, and roadmap update. Parallel sibling patches such as `BorePatch`, confirming exact current names and serialization patterns.

Serialize voice, six macros, and exposed deterministic configuration. Derived filter states and render histories remain internal. JSON round trips must preserve behavior; handle defaults and invalid inputs through project conventions.

Initial preset plan: 36 patches comprising 8 bass, 8 lead, 6 hollow, 6 flutter, 4 chirp, and 4 frayed textures. Include clean patches, moderate reactions, experimental extremes, and held variants. Suggested names: Quiet Circuit, Unequal Return, Paper Buzz, Soft Sputter, Open Vent, Nervous Wire, Aftershock, and Frayed Sustain.

Core identity must survive an empty FX chain. Existing rack effects can complement presets after the engine works independently.

## 16. Probes and Auditions

Render every voice at defaults across low/middle/high notes and soft/medium/hard velocity. Initially probe MIDI 24–84; choose supported coverage from evidence. Sweep each timbral macro through 0, .25, .50, .75, and 1 with the others neutral. Add the four required macro interactions and all-high cases.

Measure raw peak/RMS, DC, pitch confidence/error, spectral centroid, macro distance, settling time, classifier result, loop seam, and render cost. Log detector history, agitation extrema, gesture counts, recovery offsets, and filter-state peaks.

Include mechanism ablations: memory frozen; agitation disabled; unequal recovery replaced by equal recovery; noise forcing disabled at matched noise gain. Use these to verify the proposed interactions rather than merely demonstrating different presets.

Set new numeric acceptance thresholds after Round 1 probes. Existing requirements such as finite output, serialization, determinism, and `Keys.seamError < 1e-3` remain binding. Standard pitched auditions must avoid KICK, SNARE, CLAP, HAT, and TOM classifications. Fix excessive transient/noise dominance in synthesis rather than only relabeling classifier output.

## 17. Implementation Stages and Acceptance

**Round 1 — Baseline:** deterministic oscillators, noise, envelope, and stable multimode filter. Establish useful clean sounds, raw gain budgets, and macro probes.

**Round 2 — Identity:** add unequal recovery, history detectors, and bounded agitation. A hard attack must provoke audible flutter that settles; held notes must remain recognizably pitched.

**Round 3 — Extremes:** tune interacting controls and register-dependent bounds. High STATIC/AGITATE must produce reactions beyond hiss, with no runaway behavior. Measure render cost and set a budget on an identified machine.

**Round 4 — HOLD and Integration:** implement periodic forcing, state convergence diagnostics, seam validation, patches, presets, and kits. Audition difficult bright/noisy held combinations.

Required automated checks: deterministic renders, finite output, internal-state bounds, DC, macro activity, standard pitched classifier outcomes, measured pitch tolerance for moderate settings, patch JSON round trip, and representative held seams. Run the current repository gate, expected from the supplied conventions to be `./gradlew --no-daemon test`; confirm it before relying on it. No source implementation or DSP tests were executed while creating this specification.

Production acceptance additionally requires the owner's listening verdict: BUZZ changes the excitation; SKITTER exposes unequal recovery; AGITATE makes the circuit react to history; VENT reveals different aspects of that reaction; STATIC provokes irregular behavior while retaining pitch; and HOLD preserves the identity without audible seams.

## 18. Implementation Principle

The filter is the main character. Oscillator edges, pitch recovery, and noise supply provocation; memory determines the response and recovery. Prefer a smaller stable coupled model with a clearly audible reaction over a large collection of independent modulation effects.
