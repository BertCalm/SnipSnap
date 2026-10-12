# Tangle Engine — Engineering Specification

## 1. Summary

**Tangle** is an experimental pitched synthesis engine for SnipSnap. Two oscillators continually influence one another, briefly settle into captured relationships, then escape into beating, growl, and metallic chatter. Their own phase encounters generate held control patterns. A quieter root component keeps the requested note perceptible underneath the interaction.

The sonic north star is **a bass or lead whose oscillators seem to wrestle, catch, and release each other**. The interaction belongs inside synthesis, rather than being an effect on a finished tone.

This is an engineering proposal, not a completed implementation. Numeric mappings and defaults below are prototype hypotheses unless identified as an existing project requirement. Naming is provisional; trademark availability has not been assessed.

## 2. Reference and Scope

The Cat provides the historical starting point: combinable oscillator waveforms, oscillator cross-modulation, dual sub-oscillators, sync, and configurable sample-and-hold. The primary reference is the [interview with original inventor Carmine Bonanno](https://cherryaudio.com/news/interview-with-octave-cat-inventor-carmine-bonanno).

Tangle's asymmetric response, temporary capture, encounter-derived control, and root anchor are proposed extensions. Do not present these as features of the original instrument. V1 does not require circuit emulation, copied factory programs, external samples, or an exact recreation of its filter.

Target the established SnipSnap workflow: deterministic offline mono generation, 44.1 kHz output, 4× internal oversampling, serialized patches, kit integration, audition generation, shared melodic leveling, and optional seamless held loops. Confirm current repository interfaces before implementation; names in this handoff follow the supplied Gyre conventions.

## 3. Musical Range

Cover knotted basses, tearing sync-like leads, hollow fixed intervals, crawling pulses, metallic sustains, and unstable but pitched textures. Moderate settings must also produce useful uncomplicated melodic samples.

V1 renders one requested root per sample. A voice may place the second oscillator at a fixed interval above it. This does not imply live duophonic performance or independent keyboard voice allocation.

Avoid permanent root displacement, arbitrary random note sequences, uniform broadband fuzz, and a capture mechanism heard only as clicks. Empty FX must preserve the engine's identity.

## 4. Synthesis Architecture

Use two phase accumulators, waveform mixers, two derived octave-down sub-oscillators, a low-level root anchor, an encounter detector, a capture controller, held control registers, and a stable resonant filter/saturation stage.

Signal relationships:

1. Pitch and voice establish A and B base frequencies.
2. Previous oversampled oscillator outputs drive reciprocal modulation.
3. Phase encounters feed the capture controller and held control registers.
4. Capture changes phase attraction between A and B.
5. Held values alter pulse width, influence balance, and filter emphasis.
6. Oscillators and subs mix into the filter; the root anchor supports the resulting pitched body.
7. DC removal, antialias filtering, decimation, and shared loudness leveling produce the sample.

The modulation and capture loops must affect future oscillator generation. Avoid an implementation that only crossfades two finished oscillator recordings.

## 5. Oscillators and Root Support

Initial waveform set: A blends saw, pulse, and triangle; B blends saw and pulse. Normalize waveform mixtures deliberately so macro comparisons are not dominated by accidental gain changes. Voice configurations choose the blend; held controls may move it within bounded ranges.

Each oscillator has an octave-down square sub derived from its phase relationship. Band-limit those transitions too. Keep sub gain independent of the main oscillator gain budget.

The root anchor follows the requested MIDI pitch, with no reciprocal frequency modulation. It may use a restrained triangle/sine blend. WEIGHT controls its prominence and the sub mix. Standard pitched presets retain a small root floor at WEIGHT = 0; this floor must not mask the oscillator behavior or make the macro inaudible.

Proposed starting pitch coverage is MIDI 24–84. Establish production coverage through pitch, aliasing, and classifier probes rather than assuming every experimental setting remains equally tonal throughout that range.

## 6. Reciprocal Influence

Use explicitly causal feedback: each oversampled update reads the preceding update's A and B outputs. No algebraic loop or unbounded pitch integration is allowed.

One suitable initial model expresses modulation in octaves:

```text
offsetA = depthBA * smoothedPreviousB
offsetB = depthAB * smoothedPreviousA
frequencyA = baseA * 2^clamp(offsetA, -pitchBoundA, pitchBoundA)
frequencyB = baseB * 2^clamp(offsetB, -pitchBoundB, pitchBoundB)
```

Clamp positive instantaneous frequencies to a safe internal ceiling. Scale permitted depth with register to control aliasing. Use different response times and modulation depths in the two directions; CHASE controls their relative dominance. These equations describe frequency modulation, not accumulated tuning drift.

Start with a modest musical region and expand toward approximately one octave of instantaneous deviation only if probes support it. Pulse-width modulation should remain approximately within 0.10–0.90 in the initial implementation. Slew modulating signals without erasing the intended audible-rate character.

## 7. Temporary Capture — Defining Feature

Capture has four states: FREE, APPROACH, CAUGHT, and RELEASE. Encounters may initiate APPROACH; phase attraction rises smoothly, remains active for a bounded dwell, then falls during RELEASE. A refractory interval prevents immediate retriggering.

Use smooth phase attraction in V1. For a selected relationship, compute wrapped phase error and apply a bounded correction to the follower's instantaneous phase rate. The leader remains influenced by reciprocal modulation, so capture is a temporary relationship rather than a frozen tone.

Begin with 1:1 capture, plus a fixed integer-ratio option for appropriate voices. For ratio capture, define and test the actual phase relation, such as wrapped `phaseB - r * phaseA`; do not reuse a 1:1 error calculation without accounting for the ratio.

CHASE continuously changes directional coupling weights. It must not abruptly exchange oscillator identities at the midpoint. CATCH increases capture opportunity, attraction strength, and dwell within voice-specific limits. At zero, capture is disabled while TANGLE remains active.

Proposed event scale: roughly 0.5–12 eligible capture opportunities per second, with dwell and refractory timing sharing an explicit budget. High settings should create repeated audible catches, not thousands of imperceptible state switches. Instrument the schedule and tune these limits by listening.

Hard phase resets are optional future work and require their own discontinuity correction. A naive reset is not an acceptable substitute for smooth capture.

## 8. Encounter-Derived Held Controls

Detect phase encounters with wrap-safe crossing logic. Divide or gate the potentially audio-rate encounters to a useful control-event rate. Sample a bounded combination of current A and B waveform values at each accepted event.

Store that value until the next accepted event, then slew changes into pulse width, cross-modulation balance, and filter emphasis. Destinations use distinct amounts and polarities. Preserve an event trace so the engineer can verify which oscillator relationship produced each update.

The held control must be generated from the oscillator system, rather than an unrelated random LFO. Deterministic seeded offsets may vary initial phases and response asymmetry, but do not replace the encounter mechanism with random control steps.

## 9. Macro Set

Five timbral macros plus HOLD, each normalized to 0–1:

| Macro | Low | High | Initial neutral |
|---|---|---|---:|
| TANGLE | Restrained reciprocal influence | Dense mutual modulation and waveform movement | 0.30 |
| CHASE | A dominates B | B dominates A; midpoint balances influence | 0.50 |
| CATCH | Free interaction, no capture | Frequent, stronger, longer temporary capture | 0.25 |
| BITE | Rounded, restrained filter response | Brighter resonance and nonlinear edge | 0.40 |
| WEIGHT | Lean body, quiet root floor | Stronger root and octave-down support | 0.45 |
| HOLD | Finite one-shot | Seamless sustained loop | 0.00 |

TANGLE controls both reciprocal pitch influence and encounter-derived timbral movement. CHASE also changes response-time asymmetry so its effect remains audible at low TANGLE. CATCH must remain audible in moderately clean patches through phase/timbre settling, without requiring extreme FM. BITE combines cutoff, safe resonance, and bounded prefilter drive. WEIGHT changes spectral support, not only overall volume.

Avoid broad dead regions. Verify low-to-middle steps as well as endpoint differences. Exact mappings must be established through sweeps and listening.

## 10. Voices and Starting Defaults

```kotlin
enum class TangleVoice { BASS, LEAD, HOLLOW, PULSE, METAL, KNOT }
```

| Voice | TANGLE | CHASE | CATCH | BITE | WEIGHT | Purpose |
|---|---:|---:|---:|---:|---:|---|
| BASS | .30 | .25 | .35 | .30 | .80 | Rooted growl and caught bass |
| LEAD | .45 | .70 | .60 | .70 | .30 | Tearing, recapturing melodic lead |
| HOLLOW | .25 | .50 | .25 | .40 | .35 | Open fixed-interval beating |
| PULSE | .40 | .35 | .50 | .45 | .45 | Encounter-driven crawling movement |
| METAL | .75 | .65 | .40 | .80 | .20 | Sidebands with retained pitch |
| KNOT | .85 | .50 | .80 | .60 | .60 | Dense capture/release interaction |

These are starting hypotheses. Voices adjust waveform blends, base interval, response asymmetry, envelopes, and capture timing; they should share one synthesis architecture.

## 11. Envelope, Velocity, and Output

Proposed finite durations: BASS 1–4 seconds, LEAD/HOLLOW/PULSE 2–5 seconds, METAL/KNOT 2–6 seconds. Follow actual project duration conventions. End excitation before the final tail and release the filter state smoothly.

Velocity changes early envelope energy, filter opening, and a bounded initial modulation effort. It must do more than multiply the finished output. Maximum velocity must respect the same modulation, resonance, and peak budgets.

Use `MELODIC_LOUDNESS_TARGET` through the shared leveling process. Keep raw prelevel renders available for probes so leveling cannot conceal instability or inactive controls. Mono output may suggest movement spectrally; no stereo orbit is required.

## 12. Stability and Aliasing

Provide explicit bounds for frequency, pulse width, phase correction, resonance, drive, held-control depth, event frequency, and capture duration. Oscillators are powered sources; stability means bounded numerical behavior, not acoustic passivity.

Use band-limited waveform generation and the standard 4× path. FM, moving pulse width, sub transitions, and nonlinear filtering need separate aliasing attention. Oversampling alone does not guarantee acceptable spectra. Reduce modulation bandwidth or depth at high notes where necessary, maintaining useful macro movement.

No NaN, infinity, accumulating DC, runaway filter state, or uncontrolled clipping is acceptable. Output limiting must not hide an unstable internal stage. Compare extreme bright patches against a higher-rate reference render during development.

## 13. Determinism and HOLD

Seed initial phases and any roughness using the repository mechanism, such as `Dsp.seedFor`. Identical patch, pitch, velocity, seed context, and render settings must yield sample-identical output within the supported runtime.

HOLD requires recurrence of the full system: oscillator phases, reciprocal-modulation history, capture state/timers, encounter divider, held registers, smoothing state, filter memory, and envelope. Locking only the oscillators is insufficient.

Choose a loop duration with the existing pitched-loop strategy, preserving requested pitch rather than coarsely rounding the note to an arbitrary loop length. In HOLD, an acceptable approximation is a repeating capture/control schedule derived from a prerender of the same oscillator interaction. Keep CATCH audible and document this periodic constraint. Any seeded variation must also repeat.

Preroll and compare consecutive periods for convergence. Bound the search duration; failure to converge must produce a diagnostic, not silently consume unlimited render time. Use the project's short wrap treatment only after a stable candidate exists. Validate exported, leveled samples with `Keys.seamError < 1e-3` and listening for clicks, pitch changes, rhythmic stutters, or a dulled capture texture.

## 14. Presets and Integration

Initial roster: 36 presets—10 bass, 8 lead, 6 pulse, 6 metallic, and 6 experimental. Include clean starting points and held variants across categories. Example names: Root Knot, Soft Pursuit, Catch Wire, Hollow Pair, Crawling Teeth, Quiet Trap, Metal Recoil, and Long Escape.

Implement `Tangle.kt`, `TanglePatch`, `TanglePresets`, patch registration, `SynthKits` integration, pitched `drumClassFor` mapping, an audition task, tests, and a roadmap entry using current project naming. Parallel sibling patches such as `BorePatch` where applicable.

Serialize voice, six macros, and any exposed deterministic configuration. Internal derived settings should not become accidental public patch fields. JSON round trips must preserve patch behavior. Follow existing handling of defaults and invalid values.

## 15. Probe and Audition Plan

Render each voice at defaults, low/middle/high notes, and soft/medium/hard velocities. Sweep each timbral macro through 0, .25, .50, .75, and 1 with other controls neutral. Include TANGLE × CATCH, CHASE × CATCH, TANGLE × BITE, and WEIGHT × TANGLE, plus all-high cases.

Capture raw peak/RMS, DC, spectral centroid, pitch confidence, fundamental error, macro distance, classifier result, render time, seam error, and capture/held-event traces. Establish new numeric acceptance ranges from these probes rather than inventing thresholds after inspecting a favorable preset.

Standard pitched cases must avoid KICK, SNARE, CLAP, HAT, and TOM classifications. Low bass attacks need a sustained harmonic body; bright metallic patches need a perceptible pitched component. Fix synthesis causes rather than only overriding a classifier label.

## 16. Delivery Stages and Acceptance

**Stage 1:** Complete causal oscillators, root support, filtering, deterministic rendering, and macro probes. Verify useful moderate tones before widening extremes.

**Stage 2:** Implement temporary capture and encounter-derived controls. Compare enabled/disabled examples at matched loudness. Logs must demonstrate bounded dwell, release, and refractory behavior; listening must distinguish catches from ordinary beating.

**Stage 3:** Implement HOLD, state convergence diagnostics, preset coverage, serialization, and kit integration. Record render cost on an identified machine and set a project-appropriate budget from measured results.

**Stage 4:** Run automated checks for determinism, finite output, internal bounds, DC, macro activity, pitch, classifier behavior, patch round trip, and representative HOLD seams. Run the repository-required `./gradlew --no-daemon test` after confirming the current gate. These checks have not been executed as part of this specification.

Production acceptance also requires the owner's listening verdict: moderate presets are playable; CHASE changes who leads; CATCH audibly settles and releases the oscillators; held controls feel generated by their interaction; extremes remain distinctive and bounded; and held loops preserve that behavior without audible seams.

The defining implementation principle is simple: **make the relationship between the oscillators the instrument.**
