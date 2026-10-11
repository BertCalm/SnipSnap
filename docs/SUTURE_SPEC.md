# Suture — engine engineering specification

Version 0.1 • 4 October 2026, America/Chicago • Working name; naming checks pending

## 1. Summary

Suture is a physically inspired pitched synthesis engine for SnipSnap. It imagines curved bronze plates forming a cracked hollow vessel. Elastic cords cross the gaps through wooden eyelets. A note spreads two plates apart and releases them. Their ring pulls the cords tight; sliding cords excite woody chirps and stretched harmonics. Powered mechanical stitchers draw the vessel closed while vibration resists closure. Near closure, plate edges touch and add a delicate buzzing seam.

The defining gesture is **open bronze bloom → stretching/sliding cords → resisted closure → enclosed resonance → seam murmur**. Its body changes shape while sounding, and that shape changes contact, coupling, and radiation.

This is a generated engineering proposal for an imaginary DSP instrument. Geometry, friction, mechanical relationships, timings, and defaults are hypotheses requiring implementation probes and listening. No physical prototype or repository implementation was inspected.

## 2. Scope and platform baseline

Follow the supplied SnipSnap architecture and edited Gyre constraints: deterministic offline mono samples, macro-driven variation, patch serialization, presets, pitched kit integration, shared loudness processing, auditions, and optional seamless HOLD loops.

Expected output is 44.1 kHz through the established 4× internal render and band-limited decimation path. Confirm repository interfaces before implementation. No external samples are required. Core identity must work before rack effects.

V1 renders one requested pitch with one complete vessel state. Independent exported pads do not share opening, stitch position, or cord tension. Persistent phrase state is deferred; a diagnostic harness may investigate it without silently changing the public API.

## 3. Goals and non-goals

The useful range includes deep bronze plucks, slow metallic blooms, stretched-cord harmonics, wooden friction chirps, changing cavity tones, seam buzz, and controlled opening/closing sustains. Moderate patches retain an identifiable requested note; extremes may become inharmonic, rough, or mechanically pulsing.

Suture is not a bell plus string pad, a filter envelope labeled closure, a sampled squeaking mechanism, or a static reverb. The plate, cord, eyelet, cavity, and seam branches must share state and exchange bounded forces.

## 4. Reduced V1 model

| Component | Starting count | Role |
|---|---:|---|
| Bronze plates | 4, with 4–5 acoustic modes each | Root-bearing ring and answering metallic modes |
| Slow opening coordinates | 2 linked opposing gaps | Vessel shape and closure resistance |
| Elastic cords | 4, with 2–3 modes or short waveguides each | Tension, sliding, stretched harmonics |
| Wooden eyelets | 4 small modal contacts | Woody chirps and mounting response |
| Stitcher drive | 1 coordinated mechanism | Powered closure and optional repeated opening |
| Seam contacts | 2 adjacent edge pairs | Closure-dependent buzz and loss |
| Cavity modes | 4 | Shape-dependent enclosure resonance |

Use modal plates, reduced cord geometry, and scalar opening states. Full finite elements, knot mechanics, unrestricted collisions, and literal robotics are unnecessary.

## 5. Energy and time scales

Separate fast plate/cord vibration from slow vessel opening and stitch travel. Note-on supplies finite opening/release energy. Stitchers supply powered work while closing. Cord friction, seam contact, material damping, and radiation dissipate energy.

Passive coupling redistributes energy; it does not create sustained sound. A one-shot drive ends and the network settles. HOLD supplies explicit periodic mechanical work. Do not sustain a vessel by leaving passive feedback at unity.

The stitcher may do work against vibrating plates, and slipping cords may receive that work. Track powered input separately from passive energy so ringing resistance does not become an unbounded positive-feedback loop.

## 6. Opening gesture and bronze plates

Note-on spreads the opposing plate groups over a brief smooth gesture, then releases them. GAP controls initial opening, spread distance, and geometry-dependent coupling. Velocity, if available, scales event energy and release sharpness within this gesture.

Project finite release forces into the plate modes. Tune the dominant perceived plate mode to the requested pitch. Suggested upper ratios begin near 1, 2.6, 4.5, and 6.8, with lower levels and frequency-dependent decay. These are proposed timbral ratios, not bronze measurements.

Other plates use root-related fundamental ratios such as 2, 3, and 4. Keep the root dominant at ordinary settings. Plate pickup weights and mounting differences are deterministic.

Opening changes plate mounting load and inter-plate coupling. Compensate predictable static root shifts so changing GAP does not transpose the instrument. Allow restrained transient pitch movement, initially targeting approximately ±10 cents at moderate settings. Extreme patches may exceed this within a documented bound.

## 7. Stitcher and resisted closure

STITCH controls closure force, speed target, and linkage compliance. Use a bounded actuator with a force limit rather than an ideal position controller that moves regardless of resistance.

Maintain opening position and velocity. Conceptually:

`openingMass × gapAcceleration = gestureForce + acousticResistance + cordReaction − stitcherForce − damping`

Model vibration resistance through a smooth bounded estimate of local plate motion or acoustic energy. A louder ring increases average resistance and can slow closure. This is an explicit imaginary mechanical relationship, not a literal assertion that every ringing plate opens a vessel.

Do not directly convert total audio RMS into an unlimited opening force. Calibrate the motion projection, cap resistance, and keep its energy budget consistent with stored plate energy or powered drive. With stitcher input off, the passive vessel cannot open itself indefinitely.

Start with closure on the order of 100 ms to 2 seconds across normal settings. A hard gesture may close later than a soft one because of resistance. A compliant linkage may briefly stall, slip, or settle; none should be a random delay envelope disconnected from the acoustic state.

Fully closed is a stable bounded equilibrium with finite pressure/contact load. STITCH=0 may retain a weak passive return spring so a normal one-shot still settles, while diagnostic zero drive separates that behavior from powered closure.

## 8. Cords, tension, and eyelets

Cord length follows gap geometry and stitch take-up. Maintain elastic strain or a reduced tension state; tension is bounded and cannot become negative. CORD changes elasticity, loss, surface roughness, and mode contribution.

Gap release stretches cords and supplies finite excitation. Stitch movement draws cord through wooden eyelets. Relative sliding velocity and normal loading drive friction. Use a stable regularized stick/slip or bristle model; forces act on the same cord and eyelet resonators throughout.

Sliding can produce short woody chirps and excite cord harmonics. These must follow actual modeled travel or slips. A stationary cord may ring down but cannot generate indefinite chirping noise. At high roughness, catches may briefly resist stitch travel before releasing.

Tune cord modes to the root or upper harmonically useful ratios at their reference tension. Increasing strain may raise their pitch. Keep tension-induced excursions bounded and subordinate so the cords do not replace the requested root with an unrelated melody. Predictable reference shifts can be compensated; retain some audible stretch where musically useful.

Eyelet resonators have a short woody response and load the cord/contact. A small seeded friction texture may follow sliding work. Avoid an unpitched squeak sample or constant noise floor as the main cord identity.

CORD remains active at low GAP through internal preload and stitch travel. GAP remains audible at low CORD through plate and cavity geometry. Do not make either control depend entirely on the other being high.

## 9. Seam contacts

As gaps narrow, neighboring plate edges can contact. SEAM changes clearance, contact compliance, edge roughness, and damping. At zero, remove edge collision while retaining the vessel’s geometry and cavity.

Use smooth compliant repulsion and dissipative relative-velocity damping. Contact force excites plate modes and can cause repeated fine buzz. Contact activity follows gap and vibration; do not paste a buzz oscillator over every tail.

At defaults, seam sound is a restrained murmur inside the bronze decay. Higher SEAM can chatter, shorten the ring, and create rough upper modes. Keep a tonal center and avoid permanent broadband fizz.

Seam damping should remove energy. A clamp that adds energy on penetration correction is unacceptable. Run short nonlinear contacts on an adequate oversampled clock and validate numerical convergence.

## 10. Changing cavity

The hollow body’s volume and aperture follow the plate geometry. Closing reduces aperture, changes radiation losses, and alters cavity modes and their loading on the plates. This is more than a final filter cutoff.

The proposed fifth macro is **CAVITY**, replacing the brainstorm label VOLUME so it is not mistaken for output gain. It controls enclosure scale, modal spacing, absorption, and sensitivity to aperture changes. Low values give a compact direct shell; high values give a deeper oversized vessel.

Use a small damped modal cavity with stable bidirectional loading. Geometry-dependent coefficients change smoothly. Cavity resonances may color the requested note, but must not become a dominant independent bass pitch at ordinary settings.

Closing may produce a warmer enclosed tone, but this is an audition target rather than a universal physical rule. Establish it through modal/radiation mapping. The dry bronze and the closed-body sound should remain recognizably the same object.

## 11. Macros

Expose five timbral controls plus HOLD. Defaults and neutral values are proposals; confirm current MacroSpec semantics.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| GAP | .50 | .40 | Small release and compact geometry → broad opening and longer shape change |
| STITCH | .50 | .40 | Gentle compliant closure → stronger, faster take-up and friction work |
| CORD | .45 | .35 | Soft restrained elastic links → wiry tension, catches, and eyelet chirps |
| SEAM | .25 | .00 | Separated edges → fine contact buzz and rougher closure |
| CAVITY | .55 | .40 | Small direct shell → deep enclosure with stronger shape response |
| HOLD | .00 | .00 | Finite opening/closure gesture → powered repeating or balanced sustain |

Required interactions: GAP × STITCH changes closure duration and actuator work; STITCH × CORD changes slips and chirp rhythm; GAP × CAVITY changes the enclosure trajectory; STITCH × SEAM changes contact onset and pressure; CORD × SEAM changes where stored energy is lost or returned to the plates.

Every normal voice must expose useful audible changes across the five controls. SEAM may be a true off state at zero; ensure its useful range is not confined to a tiny extreme. CAVITY changes acoustic loading even if closure is rapid.

## 12. Voices and starting defaults

Proposed enum: `SutureVoice { BLOOM, THREAD, CLOSE, MURMUR, STRAIN, SHELL }`.

| Voice | Character | GAP | STITCH | CORD | SEAM | CAVITY |
|---|---|---:|---:|---:|---:|---:|
| BLOOM | Broad bronze ring and slow closure | .75 | .30 | .30 | .15 | .55 |
| THREAD | Cord harmonics and wooden chirps | .50 | .60 | .75 | .20 | .40 |
| CLOSE | Clear open-to-enclosed trajectory | .60 | .65 | .40 | .30 | .65 |
| MURMUR | Quiet seam buzz inside a warm tail | .35 | .50 | .45 | .70 | .65 |
| STRAIN | Resisted take-up and rough elastic contact | .70 | .75 | .85 | .50 | .50 |
| SHELL | Deep body and restrained upper texture | .45 | .40 | .35 | .25 | .85 |

Voices bias modes, linkage mass/compliance, cord preload, eyelet roughness, and cavity losses. They share the same causal architecture and retain active macros.

Initial presets: Open Bronze, Soft Thread, Wooden Eye, Slow Take-Up, Closing Shell, Fine Seam, Resisted Stitch, Tight Cord, Deep Vessel, Quiet Murmur, Strained Edge, and Returning Gap. Release naming checks remain necessary.

## 13. One-shot duration and HOLD

One-shot mode has one finite opening/release and a bounded powered closure interval. The stitcher stops after closure or its finite gesture horizon; plate, cord, eyelet, and cavity states then decay. Start near 2–6 seconds, extending resonant tails only within current host limits.

Do not wait for every mechanical state to reach exact zero after audio becomes silent. Use the project’s tail threshold and a hard maximum duration. A stalled extreme linkage cannot force indefinite export.

HOLD adds a powered reopening mechanism or a bounded balanced partial-gap cycle. Opening work, stitch closure, cord slips, and seam contact repeat within one material system. Do not reset gap position or use a new hidden note at the loop boundary.

Voice configuration may choose slow open/close cycling or a small continuously driven seam/cord region. Both require explicit powered work and a useful pitched result. Avoid repeated obvious attack clicks unless a particular rhythmic preset intends them.

For loop construction, make actuator cycles compatible with the loop duration and converge plate modes, cord strain, contact states, opening velocity, and cavity response through bounded preroll. Periodic actuator phase alone does not ensure periodic stick/slip events.

Use repeatable seeded surface texture and constrained drive where needed in HOLD. Select a stable cycle, then apply the existing loop utility or short wrap crossfade if necessary. Check that crossfading does not double chirps or seam contacts. Target `Keys.seamError < 1e-3` if still the current contract, alongside listening for shape or rhythm discontinuities.

Confirm attack-plus-loop support. If the host returns only a loop buffer, deliver settled powered material and document omission of the initial spread/release gesture. Do not silently change the public sample API.

## 14. Numerical and deterministic requirements

Use stable modal/contact integration and smooth geometry mappings. Bound gap, speed, tension, actuator force/work, friction gradients, seam penetration, and acoustic energy. Check passive decay with the gesture and stitcher disabled.

Avoid unresolved instantaneous algebraic loops among plates, cords, and cavity. Use a documented passive coupling scheme or explicit stable update order. Changing stiffness/mass/load must not inject uncontrolled energy. Output limiting is not a feedback stability fix.

Run nonlinear friction and seam excitation through the oversampled path. Slow opening/actuator states may use a lower clock, initially around 1 kHz, with convergence checks and interpolation. Prevent DC accumulation, denormals, aliased chirps, and coefficient zippering.

All roughness, structural variation, and micro-slip differences use the existing deterministic seed mechanism, such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context must reproduce identical audio and event behavior under the current render contract.

Band-limit before decimation and use shared melodic loudness targeting. Compare raw and normalized clips so leveling cannot conceal a missing closure gesture or an excessively weak cord branch. Record render time and memory before optimization.

Diagnostics: isolated plates, cords, eyelets, cavity, and seam; gap position/velocity; tension and slip events; powered actuator work; vibration resistance; passive energy/loss. Keep diagnostics outside the product macro surface.

## 15. Repository integration

Expected additions: `Suture.kt`, `SutureVoice`, `SuturePatch`, `SuturePresets`, renderer/macro registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, tests, and roadmap. Adapt naming to actual repository organization.

Round-trip voice and all macros through existing JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain handling. Core identity must be audible dry.

Suture is pitched melodic material. Standard cases must satisfy existing guards against KICK, SNARE, CLAP, HAT, and TOM. Preserve tonal tails in short CLOSE patches, root energy in SHELL, and pitched structure under STRAIN/MURMUR roughness. Confirm actual classifier and routing interfaces.

## 16. Build rounds

**Round 1 — opened shell.** Build tuned plates, finite spread/release, slow gap, and cavity. Probe pitch, peak, RMS, DC, decay, and render cost before setting numeric test bounds. Establish a useful bronze-to-enclosed trajectory.

**Round 2 — threaded links.** Add geometry-driven strain, sliding friction, and eyelet response. Verify chirps follow travel and that the same links feed plate energy. Test no-motion and passive-decay diagnostics.

**Round 3 — resisted closure.** Add bounded actuator, acoustic resistance, and seam contact. Demonstrate different closure trajectories for quiet and strong gestures without arbitrary timing substitution. Tune ordinary settings before unstable-sounding extremes.

**Round 4 — HOLD and delivery.** Add powered cycles, convergence, seam handling, patches, presets, kits, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm applicable repository instructions before treating that as the complete gate.

The owner’s sonic verdict remains necessary. Metrics establish mechanics and safety bounds, not whether the vessel sounds like one evolving object.

## 17. Audition and acceptance

Add `generateSutureAudition` or the current equivalent. Render every voice, low/middle/high supported notes, and quiet/medium/strong event energy. Sweep controls at 0, .25, .5, .75, and 1 with neutral companions. Provide raw and matched-loudness clips.

Required grids: GAP × STITCH, STITCH × CORD, GAP × CAVITY, STITCH × SEAM, and CORD × SEAM. Include all-high extremes, no seam, no powered closure, stopped cords, quiet/strong closure comparisons, high-register chirps, long passive tails, and difficult HOLD cases.

| Requirement | Evidence |
|---|---|
| Identity | Bronze bloom, sliding cords, and enclosed seam tail sound connected |
| Resistance | Stronger vibration measurably affects closure under the same actuator settings |
| Cords | Chirps follow sliding/slip events; stationary unpowered cords create no new sustained excitation |
| Geometry | Gap changes coupling and cavity loading before the final output |
| Seam | Buzz follows contact activity and disappears with contact disabled |
| Pitch | Moderate full mixes retain requested root; calibrate proposed tuning bounds using analysis and listening |
| Passive stability | With drive off, the coupled object settles and acoustic energy decays |
| Active stability | Extreme one-shots terminate; long powered HOLD stays bounded |
| Macro activity | Every normal voice exhibits useful audible and measurable control changes |
| Determinism | Identical inputs reproduce audio and event order |
| Patch/routing | JSON round-trip and current pitched guards pass |
| Loops | Representative HOLD cases pass seam metric and mechanical-cycle listening review |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost requirements pass |

## 18. Deferred work and engineer decisions

Defer full plate deformation, literal knots/stitches, progressive tearing, physical robot control, independent playable plate notes, stereo positioning, persistent cross-pad state, and sample-based mechanical recordings.

Before implementation, confirm pitch range, velocity semantics, duration limits, HOLD format, current render/patch APIs, and release naming restrictions. During probes, establish modal ratios, tension mapping, friction capture, actuator limits, resistance projection, cavity trajectory, and root compensation. Values above remain proposals until those rounds demonstrate workable behavior.

## 19. Sonic north star

A broad bronze note should pull on elastic links and make the wooden eyelets answer. The vessel should struggle gently against its stitchers, then close into a warmer ring whose seams begin to murmur. Its closure should be shaped by the vibration it is containing.

Suture succeeds when closing the instrument becomes part of playing it.
