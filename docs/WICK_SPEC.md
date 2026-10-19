# Wick Engine — Engineering Specification

## 1. Summary

**Wick** is a pitched synthesis engine for SnipSnap inspired by the Buchla Music Easel's complex-oscillator and low-pass-gate relationships. Its proposed extension is a small cascade of gates that open, expend a finite excitation reserve, and recover. One stage's release helps ignite the next.

The defining gesture is **ignite → cascade → recover**. Rounded electronic plucks and breathing tones should transform into bubbling articulations, metallic flickers, and rattling cascades within a single requested note.

This document specifies a proposed engine, not completed DSP. Numeric ranges and defaults are prototype hypotheses except established project requirements. Wick is a working name; availability has not been checked.

## 2. Reference and Platform

The Music Easel provides complex and modulation oscillators and dual low-pass gates that shape tone and amplitude. The historical architecture supplies the foundation; depletion, recuperation, and release-triggered cascades are new proposals.

References: [Buchla Music Easel manual](https://buchla.com/guides/Buchla_Music_Easel_Manual.pdf) and [Buchla's modern Easel description](https://buchla.com/product/music-easel-modern/). Do not attribute modern connectivity or these proposed behaviors to every historical version. V1 does not require exact circuit emulation, copied programs, samples, or a full modular patching interface.

Follow the supplied Gyre conventions: deterministic offline mono rendering, 44.1 kHz output, standard 4× oversampling, five timbral macros plus HOLD, shared melodic leveling, JSON patches, presets, kits, auditions, and seamless held loops. Confirm repository interfaces before coding; this handoff has not inspected the source.

V1 generates one root per sample. Multiple gate stages articulate that note and optional fixed-ratio components. It does not imply independent MIDI notes, live sequencer playback, or stereo circulation.

## 3. Core Architecture

Use one shared complex oscillator, one modulation oscillator, three low-pass-gate stages, gate charge/recovery states, a release-event detector, and a bounded cascade scheduler. Gate stages receive different mixtures of the same source and optional voice-selected harmonic components.

At each internal update:

1. Advance root and modulation phases.
2. Produce the complex source from bounded modulation and timbre shaping.
3. Process scheduled excitation pulses and update stage charge/availability.
4. Update gate opening and brightness from the stage state.
5. Process each gate's source feed and mix its output.
6. Detect completed-stage releases for future cascade events.

Events detected now may affect only a later update. Delayed scheduling prevents recursive same-sample ignition. Follow with DC removal, antialias filtering, decimation, and shared output leveling.

## 4. Shared Complex Source — TIMBRE

Start with a sine/triangle-like root. A modulation oscillator produces bounded FM or AM, with ratios selected by voice. TIMBRE increases modulation index and smooth nonlinear harmonic shaping while preserving a useful fundamental at moderate settings.

Use a single continuous source model, not a bank of unrelated samples. Smooth all parameter changes. If wavefolding is included, use an antialiased or sufficiently oversampled implementation with explicit input drive limits. Do not assume 4× oversampling guarantees clean high-index FM or folding.

Gate stages may emphasize different source spectra through input mixtures and their own brightness response. Some voices can include a quiet octave or fifth component; standard pitched presets retain the requested root. Metallic voices may use less simple modulation ratios within bounded pitch expectations.

TIMBRE must remain audible when cascade depth is low. Source complexity and gate activity should also interact: a bright source can yield different release texture through a partially open gate than through a fully open one.

## 5. Low-Pass Gates

Each stage combines a smoothed gain gate with a low-pass response derived from the same opening state. Opening increases both amplitude and brightness; closing darkens the tail while reducing amplitude.

Prototype structure:

```text
gain = opening^gainExponent
cutoff = exp(lerp(log(minCutoff), log(maxCutoff), opening^toneExponent))
output = gain * lowpass(source, cutoff)
```

Tune the correlation so it sounds like a single articulation mechanism rather than an unrelated envelope and filter sweep. Use stable coefficient updates, bounded resonance, and separate attack/release smoothing. Different stage time constants can suggest different materials.

A tested repository gate/filter primitive is preferable where it supports this behavior. This perceptual model is not a claim of exact vactrol or Buchla circuit reconstruction.

## 6. Charge, Depletion, and Recovery

Each gate has an available-excitation state in [0,1]. An accepted pulse consumes a bounded fraction of that reserve and adds charge to the gate-opening state. Subsequent pulses arriving before recovery produce weaker or differently shaped articulations.

Recover availability using an exact bounded update:

```text
availableNext = 1 - (1 - available) * exp(-dt / recoveryTime)
```

At an event, compute admitted excitation from current availability, pulse strength, and the stage's maximum charge. Subtract only the admitted amount according to a defined scale. Clamp charge and availability explicitly. Charge then decays into a gate-opening trajectory; recovery replenishes availability, not an uncontrolled audio-feedback loop.

RECOVERY controls recovery timing and related tail behavior. At low settings stages are ready again quickly; high settings leave longer depletion and soft, uneven subsequent pulses. Keep charge-release time and recovery time separate internally so a long tail does not automatically imply a fully depleted stage.

The oscillators remain powered sources. The reserve is an articulation-control metaphor and must not be described as a physical conservation proof for the entire synth.

## 7. Release-Triggered Cascade

Detect a gate closing through a threshold after having crossed a separate open threshold. Use hysteresis and one-shot arming. A falling edge schedules a bounded excitation pulse into the next stage after a delay.

For V1 use a directed three-stage chain with an optional weak final-to-first link at higher CASCADE. The event budget, finite excitation window, and availability system must prevent endless uncontrolled retriggering.

CASCADE increases transmission strength, depth, and the prominence of later stages. Low values favor the initial pluck; high values reveal a chain of secondary articulations. Proposed interstage delays are approximately 10–250 ms. Tune them by voice and listening rather than exposing a separate timing macro.

Each render has a defined maximum event rate and queued-event count. Coalesce or reject excess events according to a deterministic documented rule. Minimum delay/refractory timing must prevent a gate from triggering itself every internal sample.

A cascade must arise from actual prior gate release, not only a predetermined three-step amplitude pattern. Retain an event trace identifying parent stage, release time, destination, admitted strength, and availability.

## 8. SPARK and FEED

SPARK controls initial excitation hardness and how broadly the onset charges the gate network. Low values begin softly through the first stage; high values create a stronger initial opening and restrained direct excitation of later stages. Use bounded onset shaping, not a dominant broadband click.

FEED changes ongoing excitation supply. At low values the initial cascade spends its charge and ends. At higher values a bounded pulser replenishes articulations while the note is active. Voice configuration and gate recovery influence its useful range.

The pulser may be conditioned by network state—for example, admitting less excitation while gates are already open—so it behaves as part of the instrument rather than simply retriggering a finished sample. Use deterministic timing with a bounded rate. Proposed range for recurrent excitation is roughly 0.5–12 events/second, subject to voice and probes.

At FEED = 0, finite notes still produce the initial gesture. In HOLD, maintain a small declared sustain/periodic-excitation floor so held mode is not silent, while FEED continues to change density and replenishment strongly.

## 9. Macro Set and Interactions

| Macro | Low | High | Starting neutral |
|---|---|---|---:|
| SPARK | Soft initial opening | Harder, broader initial excitation | .40 |
| FEED | Initial gesture only | Replenished repeated excitation | .25 |
| CASCADE | Mostly first-stage articulation | Strong multi-stage release chain | .40 |
| TIMBRE | Rounded source | Rich modulation and harmonic edge | .35 |
| RECOVERY | Quick readiness | Longer depletion and recovery memory | .45 |
| HOLD | Finite render | Seamless sustained loop | .00 |

Required interactions: SPARK × CASCADE changes how far onset energy travels; FEED × RECOVERY changes whether repeated pulses remain even or become depleted; CASCADE × RECOVERY changes secondary articulation strength; TIMBRE × CASCADE changes how different stage openings expose the complex source.

Every timbral macro must remain audible in both finite and held operation. In HOLD, SPARK changes recurrent pulse attack/initial charge, not only the preroll onset that will be discarded. CASCADE = 0 disables release transmission without disabling the initial gate.

## 10. Voices and Defaults

```kotlin
enum class WickVoice { PLUCK, WOOD, BREATH, BUBBLE, FLICKER, RATTLE }
```

| Voice | SPARK | FEED | CASCADE | TIMBRE | RECOVERY | Character |
|---|---:|---:|---:|---:|---:|---|
| PLUCK | .45 | .05 | .20 | .25 | .30 | Rounded, compact pitched pluck |
| WOOD | .60 | .15 | .40 | .40 | .40 | Dry gate articulation and body |
| BREATH | .20 | .60 | .35 | .20 | .70 | Soft overlapping openings |
| BUBBLE | .40 | .65 | .70 | .45 | .50 | Cascading recurring articulations |
| FLICKER | .55 | .45 | .65 | .80 | .35 | Metallic brief openings |
| RATTLE | .75 | .80 | .85 | .65 | .65 | Dense depleted/recovering pulses |

Defaults are hypotheses. Voices select gate time constants, source ratios, delays, stage mix, and source envelopes within the same architecture. WOOD is a perceptual description, not a sampled acoustic body.

## 11. Pitch, Velocity, and Finite Duration

Keep the source's base root tied to requested MIDI pitch. Secondary articulations should reveal the same note, not become an arbitrary melodic sequence. Preserve enough pitched tail for low PLUCK and bright FLICKER to remain melodic.

Velocity changes admitted initial charge, excitation attack, and a bounded timbre effort. Maximum velocity respects availability, charge, drive, and event budgets. Final leveling uses the shared `MELODIC_LOUDNESS_TARGET`; retain raw prelevel output for diagnostics.

Initial duration targets are 1–4 seconds for PLUCK/WOOD/FLICKER and 2–6 seconds for BREATH/BUBBLE/RATTLE, subject to current project limits. End FEED and cascade admission before the final tail. A finite render must cease recurrent excitation even when the final-to-first link is active. Let existing gates decay and use standard capped-tail treatment if necessary.

## 12. Determinism and Safety

Use the current seed mechanism such as `Dsp.seedFor` for optional stage variation and pulse irregularity. Identical inputs must produce identical samples in the supported runtime. Event ordering must be deterministic, including simultaneous releases.

Bound every charge, availability, gate opening, coefficient, modulation index, gain, event rate, and queue. Check raw filter states as well as final audio. No NaN, infinity, DC accumulation, or uncontrolled clipping is acceptable. Final limiting must not hide instability.

Account for FM/folding sidebands, short envelope bandwidth, and coefficient motion in aliasing probes. Band-limit the source, smooth gate controls, and antialias before standard 4× decimation. Apply register-dependent timbre ceilings if necessary while preserving macro activity.

## 13. HOLD

HOLD must repeat oscillator phases, modulation phase, gate filter states, charge, availability, pulser phase, release-detector armed state, queued events, and any variation sequence. Repeating an amplitude envelope alone is insufficient.

Use the existing pitched-loop strategy to preserve requested pitch. Align recurring feed and stage delays with the selected loop period where appropriate, preserving musical timing as closely as practical. Preroll complete periods and compare the network state at boundaries. Use an explicit convergence/search cap and diagnostic failure path.

If the event-driven network settles into a nonrepeating pattern, an acceptable documented HOLD approximation is a periodic replay of an event schedule derived from the network, followed by periodic-state convergence of the gates. Keep depletion/recovery active during replay. Do not replace the source with a frozen waveform or remove CASCADE to obtain a seam.

Use minimal project-standard wrap treatment after selecting a stable region. Validate exported audio with `Keys.seamError < 1e-3` and listening for extra ignition at the wrap, missing cascade events, clicks, and changes in pulse strength.

## 14. Patches, Presets, and Integration

Implement `Wick.kt`, `WickPatch`, `WickPresets`, patch registration, `SynthKits`, pitched `drumClassFor`, audition generation, tests, and roadmap update. Confirm exact interfaces and follow current sibling conventions such as `BorePatch`.

Serialize voice, six macros, and exposed deterministic configuration; keep derived gate states internal. JSON round trips must preserve behavior. Follow existing defaults and invalid-value handling.

Plan 36 presets: 8 pluck, 6 wood, 6 breath, 8 bubble, 4 flicker, and 4 rattle. Include clean useful samples, experimental cascades, and held variants. Candidate names: Small Spark, Dry Wick, Soft Recovery, Bubble Chain, Metallic Ember, Slow Feed, Depleted Rattle, and Last Glow. Core identity must survive an empty FX chain.

## 15. Probes, Stages, and Acceptance

Render each voice at low/middle/high pitch and soft/medium/hard velocity; initially probe MIDI 24–84. Sweep timbral macros through 0, .25, .50, .75, and 1. Include required interaction pairs, all-high cases, final-to-first cascades, and difficult held patterns.

Measure raw peak/RMS, DC, pitch confidence/error, macro distance, event count/rate, queue depth, charge/availability extrema, depletion and recovery times, spectral evolution, classifier result, seam error, and render cost. Set new numeric thresholds from first-round evidence.

Compare depletion enabled versus constant availability, release-triggered cascade versus disconnected stages, and correlated gate tone/gain versus gain-only gating. These ablations should expose the engine's mechanism at matched loudness.

Build in four stages: stable complex source and one gate; three-stage charge/recovery network; event cascade and extreme/register refinement; HOLD and production integration. Required automated checks include determinism, finite output/states, explicit bounds, event budget enforcement, recovery behavior, macro activity, measured moderate pitch tolerance, patch round trip, classifier safety, and held seams.

Standard pitched auditions must avoid KICK, SNARE, CLAP, HAT, and TOM. Correct excessive clicks or weak harmonic tails in synthesis rather than only relabeling classification. Confirm and run the current repository gate, expected from supplied conventions to be `./gradlew --no-daemon test`. No implementation tests were executed while creating this specification.

The owner's listening verdict completes acceptance: the initial spark audibly starts a chain; releases cause later openings; rapid feeding reveals depletion; RECOVERY changes subsequent articulation; TIMBRE stays audible through the gates; moderate patches remain useful melodic samples; and held patterns preserve the cascade without audible seams.
