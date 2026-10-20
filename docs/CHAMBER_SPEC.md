# Chamber Engine — Engineering Specification

## 1. Summary

**Chamber** is a sample-free pitched ensemble engine for SnipSnap. A timpani-like membrane, vibraphone-like bar, tunable cowbell, and tunable triangle receive one simultaneous strike inside a room with independently assignable surface materials.

Instrument positions produce different direct arrivals, early reflections, surface excitation, and returning energy. The room can be uniform, have contrasting walls, or have different lower and upper regions—for example, concrete below an aquarium-like glass canopy.

The defining gesture is **shared strike → distinct surface answers → shared resonant tail**. Each instrument should reveal its own relationship with the same room.

This document is a proposed engineering handoff. No DSP has been implemented or auditioned for it. Numeric ranges and defaults are prototype hypotheses except established project requirements. Chamber is a working name; availability has not been assessed.

## 2. Platform and Scope

Follow the supplied Gyre conventions: deterministic offline mono sample generation, 44.1 kHz output, standard 4× internal oversampling, five timbral macros plus HOLD, melodic loudness leveling, serialized patches, presets, kits, and audition generation. Confirm current repository interfaces before coding; this handoff has not inspected the source.

V1 models an imaginary instrument and room. It does not require accurate orchestral emulation, measured acoustic material data, external samples, a full wave-equation room simulation, or stereo localization. Material names denote designed sonic families rather than universal physical properties of all wood, concrete, metal, or glass.

One note supplies a common pitch reference. Standard voices use unison/octave relationships and selected overtones, not four independently requested MIDI pitches. Explicit tuning ratios may be configured in the expanded patch.

The expanded editor must support the user's two examples: two wood walls plus two glass walls, and a concrete lower region plus a submerged-glass upper region. The data model and render support belong in V1 even if visual editing follows later.

## 3. Core Model

Use four struck modal instruments, direct propagation paths, surface-specific early reflection paths, surface resonator banks, a shared late field, and bounded delayed room-to-instrument coupling.

At each internal update:

1. Apply the shared finite strike excitation to the four instrument models.
2. Add delayed room-return excitation from the previous causal state.
3. Update instrument modal states and read their radiation outputs.
4. Feed direct paths, early paths, surface modes, and the late field through separate defined gain budgets.
5. Read the room at a listener position and at four instrument return positions.
6. Sum direct and room pickup, then DC-block, antialias, decimate, and level.

Feedback must have at least one explicit delay. No instantaneous algebraic instrument-room loop is permitted. Room returns act on internal instrument excitation or modal state, not only on a finished wet/dry output.

## 4. Shared Strike and Individual Responses

The four strike events have the same source timestamp. Their exciter waveforms may differ in width, hardness, spectral tilt, and strike-position weighting. They must not receive random onset offsets to imitate room placement; geometry supplies the arrival differences.

Use deterministic band-limited impulses or short shaped-noise bursts. STRIKE changes exciter energy and hardness with instrument-specific curves. A hard triangle strike and a hard membrane strike should not become the same click at different pitches.

Prototype exciter windows span approximately 0.5–20 ms according to instrument and voice. Preserve the common initiation while allowing natural attack development. Velocity increases excitation effort and bounded upper-mode participation, rather than only final output gain.

The finite strike has an explicit energy/gain budget. Returning room energy is a separate bounded input, not a second hidden note-on event.

## 5. Instrument Models and Tuning

Start with compact modal banks rather than four unrelated synth patches:

| Instrument | Initial modes | Required behavior |
|---|---:|---|
| Timpani-like membrane | 6–8 | Rounded low bloom, pitched decay, strike-position variation |
| Vibraphone-like bar | 5–7 | Clear pitched attack with sustained upper glow |
| Cowbell | 4–6 | Hollow midrange clang with distinct nonharmonic components |
| Triangle | 4–6 | Fine upper shimmer with a controllable pitch reference |

Mode ratios, damping, pickup weights, and excitation weights are voice configuration. They are not asserted as measured acoustic constants. Establish ratios through isolated instrument probes and listening.

Set vibraphone pitch directly from the requested root. Tune the membrane's perceived pitch to the same root or a configured lower octave. Cowbell and triangle tuning means scaling their modal families while preserving their characteristic spacing; it does not mean forcing every mode into the harmonic series.

Keep a clear fundamental-bearing component in the ensemble, primarily from the bar and membrane. A tunable triangle may remain perceptually ambiguous in isolation. Do not claim that its lowest mathematical mode always determines the heard note.

BALANCE retains a small contribution from all four instruments in standard macro mode. Explicit per-instrument mute is available in expanded configuration and diagnostics. Muting all four must silence new excitation and allow existing room energy to decay.

## 6. Room Geometry and Positions

Prototype a rectangular room with four walls, floor, and ceiling. Store dimensions, listener position, four instrument positions, and their orientation/height. Use normalized editor coordinates and derive bounded physical distances internally.

Starting dimension range: roughly 3–15 m per horizontal axis and 2.5–8 m height. Clamp instruments/listener inside valid bounds with a minimum clearance from surfaces and one another. Direct attenuation must use a minimum distance so near-coincident points never cause singular gains.

PLACEMENT traverses a continuous voice-specific path through valid configurations. It jointly changes instrument spacing, height, and proximity to boundary regions while keeping the listener fixed unless an explicit patch override says otherwise. A macro sweep must not teleport an instrument through a wall.

V1 may resolve positions once per offline render. Real-time moving-source Doppler simulation is not required. Within-render movement is deferred unless a voice explicitly supplies a bounded periodic trajectory and validates its interpolation artifacts.

## 7. Early Reflections and Surface Regions

Use a small deterministic set of direct and first-order reflected paths per instrument. An image-source-style construction is a suitable prototype. Compute each path's delay, distance attenuation, reflection point, and material response. Add a limited set of second-order paths only if they improve audible position specificity.

Each of the four walls supports lower and upper regions separated by a configurable horizontal boundary. Floor and ceiling have their own assignments, yielding up to ten primary surface regions. The four walls can be assigned independently; the lower/upper split enables the concrete/aquarium example.

Select a reflection's material from its actual boundary intersection. Blend across a narrow region boundary to avoid brittle changes when a path crosses the split. Do not merely average all wall materials and apply one global EQ.

Source-to-surface coupling and surface-to-listener response must depend on the relevant positions. Height changes alone do not inherently select brighter sounds: an upper region matters because the defined paths and coupling encounter it.

## 8. Material Families

Each material has separate reflection filtering, damping/decay, a small surface-mode profile, diffusion, and return-coupling limits. Prototype designed characters:

| Material | Reflection character | Surface response |
|---|---|---|
| WOOD | Warm, uneven spectral absorption | Restrained woody low/mid modes |
| CONCRETE | Dense, relatively hard return | Weighty room/low-mid emphasis |
| METAL | Bright defined reflections | Distinct longer ringing modes |
| GLASS | Clear upper detail | Narrow brittle glints |
| SUBMERGED_GLASS | Wavering, spectrally softened secondary returns | Fluid-loaded glass-like resonance |

Use these as musical targets, not guaranteed properties of real buildings. Thickness, mounting, geometry, and many other factors are outside this model. No measured material database is required.

MATERIAL continuously moves through WOOD at 0, CONCRETE near .25, METAL near .50, GLASS near .75, and SUBMERGED_GLASS at 1. Interpolate bounded filter responses and parallel modal contributions, not enum values or arbitrary raw biquad coefficients. Keep topology stable and state transitions smooth.

## 9. Mixed Materials — DIVIDE

At DIVIDE = 0, all surfaces follow the global material position. At high DIVIDE, the selected layout introduces distinct region targets. Blend each region's response toward its target without collapsing the entire room into one material average.

V1 layout enum should include UNIFORM, OPPOSING_WALLS, ALTERNATING_WALLS, and LOWER_UPPER. A voice selects the default layout and contrast palette; an expanded patch can assign each region explicitly.

Required example configurations:

- **Two wood/two glass:** two of the four walls target WOOD, the other two GLASS. Floor and ceiling have explicit independent assignments, not accidental inherited values.
- **Concrete/aquarium:** lower wall regions and floor target CONCRETE; upper wall regions and ceiling target SUBMERGED_GLASS. The split height is editable.

DIVIDE must remain useful across the MATERIAL range. Macro mode derives region targets from a documented palette/layout rule. Override mode uses explicit targets, with MATERIAL applying a declared common offset or morph policy. Serialize that policy; avoid hidden precedence between editor values and macros.

## 10. Submerged-Glass Boundary

The main ensemble remains in the room's air-like space. SUBMERGED_GLASS represents an imaginary fluid-loaded boundary or aquarium canopy whose vibrations return to that space. It is not a global underwater low-pass filter on the instruments.

Prototype a damped glass-mode bank with restrained pitch-dependent modulation and a separate delayed, diffused secondary path. Its extra delay and wavering quality are designed acoustic effects; they are not a claim that sound universally travels more slowly through water.

Keep dry attacks clear while the boundary answer acquires wavering resonances and softened detail. Apply modulation primarily to the boundary branch. V1 may leave that modulated branch out of the instrument-return feedback loop while a stationary bounded boundary component supplies return energy; document the distinction.

Use deterministic modulation and bounded depth. Compare with ordinary GLASS at matched loudness and direct level. The difference should remain audible beyond a longer decay or reduced treble.

## 11. Shared Late Field

Use one shared multichannel internal delay/scattering field or an equivalent compact room network, with instrument-specific injection and readout weights. The exported result remains mono. A separate late reverb for each instrument is unnecessary.

Maintain distinguishable surface contributions during early decay, then allow them to blend later. Material-region statistics may control late-field damping and diffusion, but early paths and surface modes retain the actual assignments.

Avoid counting full energy repeatedly in direct, early, modal, and late branches. Establish explicit send/return budgets and raw mix headroom. More reflective materials may ring longer without receiving an uncontrolled gain advantage.

Instrument positions must affect late-field injection/readout or surface excitation as well as direct delay. Otherwise PLACEMENT would be only a set of short delay taps.

## 12. Room-to-Instrument Return and Stability

Each instrument receives a low-level delayed combination of relevant boundary/room outputs, weighted by position and its modal susceptibility. This should extend or weakly re-excite instrument resonances after the original strike. Do not retrigger the attack exciter on every reflected peak.

Provide an engineering bypass for this coupling. At matched direct/wet levels, enabled coupling must change the instrument-state decay or spectral evolution, rather than only increasing room volume.

Analyze the complete coupled loop, not each filter in isolation. One conservative V1 approach bounds every stationary branch's induced gain using its absolute impulse-response sum (including analytic tail bounds), assembles an aggregate instrument-return gain matrix, and scales return sends so its maximum absolute row sum stays strictly below 1. A prototype design margin is at most .90. Include all parallel paths and cross-instrument returns in the bound.

The mathematical requirement is a valid complete-loop contraction/small-gain bound; .90 is a proposed margin, not a measured acoustic constant. An energy-normalized passive network with a documented proof is an acceptable alternative. A nominally small coupling coefficient or stable modal pole alone is insufficient.

For time-varying branches, establish a separate uniform gain bound or exclude them from feedback. Final clipping/limiting must not conceal internal growth. Test impulse decay and raw internal states at all-high controls, maximum velocity, and worst-case reflective layouts.

## 13. Macro Set

| Macro | Main action | Starting neutral |
|---|---|---:|
| STRIKE | Shared impact effort, hardness, and mode excitation | .45 |
| BALANCE | Moves emphasis through membrane, bar, cowbell, and triangle | .40 |
| MATERIAL | Wood → concrete → metal → glass → submerged glass | .35 |
| DIVIDE | Uniform room to contrasting surfaces/height regions | .45 |
| PLACEMENT | Instrument spacing, height, and boundary proximity | .50 |
| HOLD | Finite struck event to seamless sustained resonance | .00 |

STRIKE = 0 is a soft usable strike, not silence. BALANCE traverses smooth overlapping instrument weight profiles with a presence floor for standard voices; compensate average excitation without flattening instrument differences. PLACEMENT follows a meaningful path even in a uniform room.

Required interactions: MATERIAL × PLACEMENT changes which surface answers each instrument; DIVIDE × PLACEMENT exposes distinct paths rather than an averaged room; STRIKE × MATERIAL changes the resonances excited; BALANCE × DIVIDE changes which regional response dominates.

All five timbral controls must create measurable and audible changes across the normal voice roster, including low-to-middle steps. HOLD must not disable material contrast or room return just to obtain an easy loop.

## 14. Voices and Initial Defaults

```kotlin
enum class ChamberVoice { TIMBER, MASS, ALLOY, GLAZE, AQUARIUM, HYBRID }
```

| Voice | STRIKE | BALANCE | MATERIAL | DIVIDE | PLACEMENT | Character |
|---|---:|---:|---:|---:|---:|---|
| TIMBER | .35 | .35 | .05 | .20 | .35 | Warm struck ensemble with woody answers |
| MASS | .50 | .20 | .25 | .25 | .55 | Deep membrane/bar bloom |
| ALLOY | .60 | .55 | .50 | .40 | .65 | Ringing metal with midrange clang |
| GLAZE | .40 | .70 | .75 | .35 | .50 | Clear bar and triangle glints |
| AQUARIUM | .35 | .60 | .95 | .80 | .70 | Concrete foundation, wavering canopy |
| HYBRID | .50 | .45 | .40 | .90 | .60 | Strongly contrasting wall answers |

Defaults are hypotheses. Voices select excitation curves, instrument/modal ratios, geometry paths, default contrast layout, and boundary weighting within the same engine. AQUARIUM must ship with the explicit lower/upper assignment, not merely a wet global filter.

## 15. Pitch, Duration, and Output

Initially probe MIDI 36–84, with additional low-note tests for membrane-heavy voices. Choose production coverage from evidence. Room modes may remain partly fixed in absolute frequency while instrument pitch follows the note; do not transpose every room mode with every key unless a voice explicitly uses that impossible behavior.

Use initial finite durations around 2–8 seconds according to measured energy decay and current repository limits. Stop when instrument and room energy fall below established thresholds, or use project-standard tail treatment at the explicit duration cap.

Apply shared `MELODIC_LOUDNESS_TARGET` leveling after rendering. Retain raw prelevel instrument, surface, and room stems for probes. Direct attenuation and material contrast must not be judged only after independent normalization of every stem.

## 16. HOLD

A finite single strike cannot sustain indefinitely in a stable decaying room. HOLD therefore uses explicit restrained periodic unison excitation. Keep all four source strikes aligned at each excitation timestamp; propagation creates the later differences. Voice configuration controls pulse spacing and softness within project limits.

STRIKE remains audible by changing recurrent excitation effort. Do not silently turn a struck model into a continuously powered oscillator. The held result may range from distinguishable slow blooms to an overlapping resonant field, with no abrupt special effects at the wrap.

Align excitation and optional submerged-glass modulation to the selected loop period. Preserve requested pitch using the existing pitched-loop strategy. Delay buffers, all instrument modes, surface modes, late-field states, return paths, modulation, and scheduling must recur; a repeating excitation pattern alone is insufficient.

Preroll repeated periods to convergence with a bounded search and diagnostics. A documented more-periodic boundary modulation is acceptable in HOLD. Use minimal project-standard wrap treatment after a stable region is found. Require `Keys.seamError < 1e-3` on exported audio and listen for duplicate strikes, missing reflections, pitch discontinuity, or a room tail that collapses at the boundary.

## 17. Determinism, CPU, and Aliasing

Seed exciters and fixed modal variations using `Dsp.seedFor` or the current repository mechanism. Identical voice, patch, pitch, velocity, seed context, and render settings produce sample-identical audio within the supported runtime. Stable instrument/surface IDs prevent region edits from reseeding unrelated parts.

Prototype limits: four instrument banks totaling about 24 modes, ten surface regions with up to three modes each, approximately 24 first-order instrument-to-face early paths, and an 8–12 line late field. Region selection comes from path intersections; subdivision need not double every path. Evaluate second-order reflections only if auditions justify them.

Precompute static geometry once per render. Benchmark preparation, synthesis, peak memory, and convergence cost separately. Parameter smoothing or state-preserving response crossfades must avoid clicks when material values are compared or a future interactive editor changes them.

Keep generated modes below the output Nyquist guard band and band-limit strikes. Nonlinear richness, time-varying submerged paths, and fractional-delay interpolation need dedicated aliasing checks. Use standard 4× processing and antialias before decimation; oversampling alone is not a guarantee. Compare difficult bright cases with a higher-rate reference.

## 18. Patches, Presets, and Integration

Implement `Chamber.kt`, `ChamberPatch`, `ChamberPresets`, room/material configuration, patch registration, `SynthKits`, pitched `drumClassFor`, audition generation, tests, and roadmap update. Confirm exact sibling conventions, including patterns such as `BorePatch`.

Serialize voice, six macros, layout enum, material targets, lower/upper split, room dimensions, listener/instrument positions or macro-path overrides, tuning ratios, and exposed deterministic configuration. Keep derived delays and runtime states internal. Define macro/override precedence explicitly and preserve it through JSON round trips.

Plan 36 presets: six per voice. Include uniform materials, two-wood/two-glass walls, concrete/aquarium split, close and distant placements, soft and hard strikes, and held variants. Candidate names: Four Answers, Timber Room, Concrete Bloom, Metal Corners, Glass Above, Aquarium Ceiling, Split Walls, and One Strike Together. Core identity must survive an empty external FX chain.

## 19. Probes, Stages, and Acceptance

Render every voice across low/middle/high notes and soft/medium/hard velocities. Sweep timbral macros through 0, .25, .50, .75, and 1. Include required interaction pairs, all-high cases, each instrument solo, each surface-family isolation, position/height changes, both required mixed-room examples, and maximum-return tests.

Measure direct/first-reflection arrival times, raw peak/RMS, DC, pitch confidence/error, instrument and surface decay, modal-state peaks, return-loop bound, macro distance, late-field energy, classifier result, CPU/memory, and seam error. Establish new numeric audio tolerances from Round 1 evidence.

Mechanism checks: disable room-to-instrument return while retaining listener room pickup; replace split surfaces with their average response; move an instrument while keeping its source unchanged; and mute individual surface paths. The production engine should reveal re-excitation and position-specific material answers absent from these simplified versions.

Build in four stages: useful isolated pitched instruments and shared strike; geometry/material paths and mixed surfaces; bounded return coupling, submerged character, and extreme/CPU tuning; HOLD, presets, and integration.

Automated requirements include determinism, finite output/states, complete-loop stability bounds and decay, geometry validity, shared strike timestamps, material continuity, region assignment, macro activity, measured pitch tolerance, patch round trip, classifier safety, and held seams. Standard pitched auditions must avoid KICK, SNARE, CLAP, HAT, and TOM. Membrane-heavy cases need a clear pitched tail; bright triangle cases must retain ensemble body. Fix synthesis causes rather than only relabeling classifier output.

Confirm and run the current repository gate, expected from the supplied conventions to be `./gradlew --no-daemon test`. No source implementation or DSP tests were executed in creating this specification.

The owner's listening verdict completes acceptance: all four instruments initiate together yet meet the room differently; mixed surfaces remain distinguishable; placement changes more than delay or loudness; submerged glass adds an aquatic answer while preserving the direct strike; room returns affect instrument resonance; and held loops preserve the shared ensemble without audible seams.
