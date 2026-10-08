# Tessera — engine engineering specification

Version 0.1 • 5 October 2026 • Working name; release naming checks pending

## 1. Product intent

Tessera combines tubular-bell-inspired metal tubes, hammered-dulcimer-inspired paired strings, and marimba-inspired wooden bars on a shared frame inside a chamber that changes size and shape. A strike travels through both the frame and the air. Returning pressure can excite a different material from the one initially struck.

The signature is **mixed-material attack → frame response → changing reflected path → differently voiced answer**. Expanding walls separate arrivals; folding routes favors different receiving materials; gesture-driven wall movement changes echoes already traveling.

This is a generated imaginary DSP design, not literal emulation or validated chamber physics. Numeric targets and mechanisms are proposals. No repository implementation was inspected.

## 2. SnipSnap contract

Use deterministic offline mono rendering, the established 44.1 kHz output/4× internal path, seeded variation, shared loudness processing, patch serialization, presets, pitched kit integration, auditions, and optional held loops. Confirm current interfaces against the repository rather than assuming every identifier below exists.

One render represents one requested note and a freshly initialized complete object. Independently exported pads share no chamber state. No external samples are required. Dry synthesis must establish identity before rack FX.

## 3. V1 components

| System | Initial budget | Purpose |
|---|---:|---|
| Metal tube | 6–8 modes | Clear strike, long metallic upper resonance |
| String courses | 2 pairs | Wiry attack and deterministic paired-string shimmer |
| Wooden bars | 2, 4 modes each | Rounded pitched body |
| Shared frame | 3 modes | Immediate mechanical transfer |
| Primary chamber paths | 4–6 | Distinct source-to-receiver returns |
| Later chamber field | Small damped scattering/delay network | Supporting diffuse decay |
| Wall coordinates | 2–3 slow states | Size, folding, and motion |
| Pressure collectors | 3 receiving mechanisms | Bounded return excitation |

The three material families sound at the requested root, with subordinate upper modes. Do not turn material morphing into a chord progression.

## 4. Shared hammer and material morph

One finite band-limited hammer force feeds the material network. HAMMER changes contact duration, hardness, excitation width, and bounded rebound. Velocity, if available, scales event energy within that character.

MATERIAL maps continuously through wood, paired strings, and tube metal, with overlapping excitation weights. It also changes effective contact projection and load. All sources remain inside one coupled render. Crossfading independent finished samples is insufficient.

The selected material receives the strongest initial force. Others receive weaker energy through the frame and later chamber returns. Near morph endpoints, retain small receiving weights so chamber geometry can still answer in a different material.

Frame coupling must be reciprocal or demonstrably passive. With hammer and wall power off, the material/frame network decays. Avoid independently adding positive feedback to every branch.

## 5. Material models and tuning

Use modal tubes and wooden bars; reuse existing string/waveguide infrastructure where practical. Tube partials may be inharmonic, wood upper modes sparse and damped, and string pairs slightly different in loss and stiffness. These timbral distinctions require audition rather than assumptions from labels.

Calibrate the principal perceived pitch of every family to the requested note after coupling. Strong tube upper modes must not create a different apparent root. Small paired-string detuning is deterministic and initially limited to a few cents.

Suggested starting upper-mode ratios: tube 1, 2.7, 4.6, 6.9; wood 1, 4, 9; strings approximately harmonic with modest stiffness. These are design hypotheses, not instrument measurements. Attenuate modes outside the usable bandwidth.

## 6. Changing chamber

SCALE sets the reference chamber dimensions. FOLD changes path topology through smoothly interpolated scattering/receiving weights and route lengths. MOTION changes the response of powered walls to smoothed instrument energy.

A strong gesture drives bounded expansion or folding; springs/damping return geometry toward rest. Wall movement is slower than audio vibration. Start with 80–800 ms response times and early arrivals around 30–450 ms, then calibrate within supported sample lengths.

At MOTION=0 the chamber remains stationary but SCALE and FOLD still alter paths and loading. At low nonzero motion, include an audible small gesture response; do not require a full slow cycle before anything changes.

Keep all delay lengths positive and within allocated bounds. FOLD must not discontinuously replace a ringing buffer or change a feedback matrix into an unstable one. Use stable scattering interpolation or energy-bounded path crossfades.

## 7. Echoes in moving paths

Use continuously varying fractional delays or a documented traveling-wave approximation. Contraction can shorten remaining travel and produce compressed brighter returns; expansion can stretch arrival texture. These are explicit musical rules, not a claim of accurate time-varying room simulation.

Changing delays naturally changes pitch. Limit delay velocity at ordinary settings and preserve a clear direct root. Do not add a second pitch shifter accidentally duplicating the effect. If path compression is extreme, make it an explicit region of the macro mapping.

Powered wall movement may exchange energy with the sound. Track that as an active source with bounded work; do not assume every time-varying delay remains passive. Static chamber paths must decay when external sources stop.

## 8. Return excitation and pressure collectors

Give each material a receiving port. Most returns produce continuous weak acoustic force. Selected strong envelopes load a pressure collector that can release a small mechanical answer into another material: a string hammer, tube striker, or bar flexure.

For one-shots, collector strikes draw from a finite stored spring budget or modeled collected energy. Do not trigger a full-strength note from every echo. Each release reduces stored energy; refractory intervals, thresholds with hysteresis, and an initial 3–8 release cap prevent endless event chains.

The continuous return and collector event must not double-count the same full energy. Use an explicit split. Route FOLD so some paths favor metal, some strings, and some wood; keep the routing inspectable.

No external reverb supplies the core return behavior. The final mix can include chamber radiation, but re-excitation must occur inside the instrument model.

## 9. Macros

Proposed normalized defaults and neutral values require calibration and confirmation of MacroSpec semantics.

| Macro | Default | Neutral proposal | Low → high |
|---|---:|---:|---|
| MATERIAL | .45 | .45 | Wood → paired strings → metal, continuously |
| HAMMER | .50 | .40 | Soft broad contact → hard bright contact/rebound |
| SCALE | .50 | .40 | Compact rapid returns → spacious separated arrivals |
| FOLD | .45 | .35 | Simple open routes → selective intersecting paths |
| MOTION | .35 | .00 | Static chamber → stronger gesture-driven reshaping |
| HOLD | .00 | .00 | Finite gesture → powered loopable continuation |

Required interactions: MATERIAL × FOLD, HAMMER × MOTION, SCALE × MOTION, SCALE × FOLD, and FOLD × MOTION. Every timbral macro must produce useful audible variation in every normal voice.

## 10. Voices and presets

Proposed enum: `TesseraVoice { WOOD, COURSE, TUBE, ANSWER, FOLDING, CHAMBER }`.

| Voice | MATERIAL | HAMMER | SCALE | FOLD | MOTION | Identity |
|---|---:|---:|---:|---:|---:|---|
| WOOD | .10 | .40 | .35 | .30 | .20 | Rounded bar and wiry answers |
| COURSE | .45 | .55 | .45 | .40 | .25 | Paired strings with metal tail |
| TUBE | .90 | .45 | .60 | .35 | .20 | Clear metal and wood returns |
| ANSWER | .40 | .50 | .65 | .70 | .35 | Distinct differently voiced replies |
| FOLDING | .55 | .60 | .50 | .80 | .75 | Active route changes |
| CHAMBER | .50 | .35 | .85 | .60 | .50 | Spacious evolving resonance |

Voices bias losses, path preferences, and collector compliance, not separate architectures. Suggested presets: Wooden Arrival, Paired Wire, Long Tube, Other Material, Closing Passage, Expanding Hall, Crossed Returns, and Held Chamber.

## 11. Duration and HOLD

One-shot mode has one primary hammer gesture, bounded wall work, finite collector budget, and passive decay afterward. Start near 2–6 seconds with longer tails only within host limits. End on the established silence criterion or maximum duration.

HOLD explicitly replenishes collector energy and supplies restrained periodic hammer/frame excitation. Walls use a bounded compatible cycle or stable equilibrium. It cannot rely on unity feedback.

Converge sources, frame, chamber buffers, wall state, collector stores, and pending events with bounded preroll. Quantize wall cycles to loop length where needed; phase alignment alone is insufficient. Use periodic seeded texture and the project wrap utility after selecting stable material.

Check for double strikes and path jumps at crossfades. Require `Keys.seamError < 1e-3` if still current, plus listening. Confirm attack-plus-loop support; a loop-only buffer contains settled material rather than promising the initial isolated gesture.

## 12. Numerical and integration requirements

Bound modal coupling, collector energy, event density, delay velocity, and wall power. Smooth contact and coefficients, avoid DC/denormals, and band-limit nonlinear/bright paths before decimation. Detect non-finite state at its source; a final limiter cannot fix unstable internal feedback.

Use existing deterministic seeds such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context reproduce identical audio/event order. Apply shared melodic loudness targeting; compare raw and normalized auditions.

Expected surfaces: `Tessera.kt`, voice/patch/presets, renderer/macros, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain conventions. Standard pitched cases must satisfy current KICK/SNARE/CLAP/HAT/TOM guards without removing material identity.

## 13. Build rounds and acceptance

1. Build the three materials and passive frame; establish pitch and dry identity.
2. Add static geometry and receiving ports; demonstrate cross-material replies.
3. Add bounded wall motion and collector releases; tune moderate settings before extremes.
4. Complete HOLD, patches, presets, kit integration, auditions, and repository checks. The supplied baseline names `./gradlew --no-daemon test`; confirm current instructions.

Measure pitch, peak, RMS, DC, decay, aliasing, cost, and macro activity before fixing numeric bounds. Audition every voice at low/middle/high supported notes and velocity, macro values 0/.25/.5/.75/1, required interaction grids, all-high settings, and difficult HOLD cases.

Acceptance requires deterministic finite output; passive decay with powered sources off; bounded moving-wall extremes; root recognition in moderate full mixes; useful macro changes; patch round-trip; current routing guards; seamless metric and listening gates. Diagnostic isolation must show that muting receiving ports removes cross-material answers while leaving ordinary chamber audio.

The owner’s sonic verdict remains necessary. Proposed ±10-cent direct-source tuning is a calibration starting point, not proof of perceived pitch in an inharmonic full mix.

## 14. Open decisions and deferred scope

Confirm note range, velocity, duration, HOLD format, APIs, and naming. Calibrate morph curves, modal ratios, motion work bounds, collector thresholds, and path compression. Defer full room simulation, arbitrary chords, stereo, persistent pad state, and sample assets.

Tessera succeeds when a note travels through changing space and returns as another material’s answer.
