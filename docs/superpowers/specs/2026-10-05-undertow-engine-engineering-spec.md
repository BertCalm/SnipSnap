# Undertow — engine engineering specification

Version 0.1 • 5 October 2026, America/Chicago • Working name; release naming checks pending

## 1. Summary

Undertow is a physically inspired pitched synthesis engine for SnipSnap. It imagines a large spiral shell with tuned chambers. Flexible leather flaps cover chamber openings, each carrying a small ceramic weight. A piston draws air inward through the shell. Suction bends the flaps until weights strike chamber rims. The resulting ceramic knocks excite the shell, and flow through partially open apertures produces tuned breath tones.

Flaps can flutter or seal an opening. Sealing redirects the shared suction toward other chambers; their changing pressure and vibration can open another flap. One draw therefore unfolds into a coupled phrase of ceramic contact, hollow breath, flutter, and closure.

The signature is **draw → weighted contact → pitched breath → competing apertures → settling shell**. Every chamber changes the airflow available to the others.

This is a generated imaginary-instrument proposal. The shell, material relationships, timing, and parameter values are design hypotheses, not a validated acoustic prototype or hardware construction plan. No repository implementation was inspected.

## 2. Scope and platform baseline

Follow the supplied SnipSnap architecture and the edited Gyre constraints: deterministic offline mono rendering, macro variation, patch serialization, presets, pitched kit routing, shared loudness processing, auditions, and optional seamless HOLD loops.

Expected output is 44.1 kHz using the existing 4× internal rendering and band-limited decimation path. Confirm actual source interfaces and utilities before implementation. No external samples are required. The dry engine must establish its identity before rack effects.

V1 renders one requested note through a small complete shell network. Independent exported pads begin from fresh deterministic pressure, flap, and chamber states. Cross-pad airflow memory is deferred. A persistent phrase harness is diagnostic work, not a public API extension.

## 3. Goals and non-goals

Useful regions include rounded ceramic notes, soft hollow whistles, breathy melodic tails, throaty low tones, irregular flutter, aperture-switching phrases, and sustained coupled wind textures. Moderate settings preserve a clear requested root. Extremes may introduce chatter, register instability, and dense airflow texture while remaining bounded.

Undertow is not a whistle with a delay, a ceramic hit plus unrelated pad, a noise filter sweep, or a suction envelope applied only to final volume. The apertures participate in excitation, loading, and pressure competition before the final sum.

## 4. Reduced V1 architecture

| Component | Starting count | Function |
|---|---:|---|
| Shared suction reservoir | 1 | Piston-driven pressure deficit and common flow demand |
| Piston drive | 1 finite or sustained gesture | Explicit powered energy source |
| Chambers | 4 | Dominant root and three responding resonances |
| Weighted flaps | 1 per chamber | Opening, bending, contact, sealing, and flutter |
| Ceramic/rim modes | 2–3 per chamber | Rounded contact notes |
| Airflow resonators | 1 per chamber | Flow-dependent tuned breath tones |
| Shell/body modes | 3–4 | Shared hollow resonance and loading |

Use reduced flap mechanics, chamber resonators, and a bounded pressure model. No full fluid simulation, deformable leather mesh, or three-dimensional spiral solver is necessary.

## 5. Energy and pressure interpretation

The piston supplies work by maintaining a pressure deficit inside the shell. Inward airflow and pressure forces move the flaps and feed the tone-producing elements. Ceramic weights and passive shell coupling redistribute or release stored mechanical energy; they do not generate unlimited sustain.

Define normalized suction s as exterior pressure minus reservoir pressure. Larger s means a stronger inward pressure difference. A conceptual reservoir update is:

`ds/dt = pistonExtraction − sum(inwardFlows) − leakEqualization`

Define units and normalization in code. Inward flow replenishes reservoir air and reduces the deficit; piston extraction increases it. Do not accidentally make every opened chamber increase suction indefinitely.

Use bounded piston capacity and finite reservoir compliance. DRAW controls drive strength, extraction profile, and gesture duration. In ordinary one-shot mode, extraction ends and pressure relaxes. HOLD supplies ongoing powered drive explicitly.

## 6. Shared flow competition

Each chamber flow depends on available pressure and effective aperture area. A bounded square-root pressure mapping is an initial option. Flap position determines area, with a small documented baseline leak where needed to avoid a completely dead state.

Opening several chambers draws more total flow than opening one, changing the reservoir trajectory. Closing an aperture reduces its flow; the continuing piston draw can then increase the deficit and alter other flaps. Thus sealing redirects demand rather than creating energy.

Track any local chamber compliance separately from the shared reservoir. SPIRAL may increase inter-chamber impedance and delayed loading, but must not replace shared competition with four independent envelopes.

Retain a diagnostic independent-reservoir mode for comparison. The production shared version must show different pressure and onset trajectories when another aperture opens or seals.

## 7. Weighted leather flaps

Maintain flap displacement, velocity, and contact/seal state. Separate slow gross bending from any fast flexural or flutter component. A conceptual mechanical equation is:

`mass × acceleration = pressureForce + acousticReaction − elasticReturn − damping − contactReaction`

FLAP controls compliance, loss, resting clearance, and flexural response. WEIGHT controls effective mass, ceramic contact loading, and related inertia. Greater weight should not simply increase audio gain; it changes catch timing, impact character, and motion.

Use a stable damped model and smooth bounded force laws. Pressure gradients and travel limits must not become singular. The pressure source supplies the work for sustained flutter. A static undriven flap cannot generate continuous sound.

Different flaps have small deterministic resting offsets and stiffness differences. The dominant chamber catches first at defaults; others may answer later as shared pressure changes. Do not trigger every contact simultaneously at note-on.

A useful operating range includes partial opening, compliant flutter, rim contact, and sealing. Choose resting geometry so low DRAW remains musically audible through a soft mechanical/contact gesture or weak breath, rather than widespread silent patches.

## 8. Rim contact and sealing

The ceramic weight strikes a rim when flap travel crosses its contact boundary. Contact forces excite local ceramic/rim modes and the common shell. Use a smooth compliant collision model with dissipative damping and bounded restitution.

Contact strength follows relative velocity, weight, and compliance. The force pulse is finite and band-limited. A small seeded contact texture may accompany it, but modal ring provides the tonal identity.

Sealing is related to contact geometry but is not identical to every impact. Define a seal region with opening/closing hysteresis and a smooth aperture transition. A flap may tap the rim without fully sealing, or rest sealed after a stronger draw.

Acoustic pressure can disturb a seal if the corresponding mechanical force crosses the release threshold. This allows bounded switching between chambers. Avoid threshold chatter that toggles on each audio sample.

At full closure, LEAK or the piston’s drive envelope permits eventual relaxation. Do not strand the system permanently in a silent locked state at default settings. Diagnostic perfectly sealed behavior may be useful but is not a normal playable region.

## 9. Tuned airflow generation

Flow through an opening does not automatically generate a stable musical pitch. Give each chamber an explicit tone-producing element: an inward-facing jet/edge cavity or a flap-mediated reed-like feedback path. Prefer one consistent mechanism across voices for V1.

Use a tuned acoustic delay or resonator with flow-dependent nonlinear excitation and loss. Calibrate feedback sign for inward flow. Pitched onset, breath, and harmonic density follow flow and aperture geometry. Filtered noise alone does not meet the requirement.

Flap motion modulates the excitation and loading of this same resonator. A partially open flap can support a steady tone; flutter can periodically disturb it; sealing cuts the flow and leaves a decaying chamber ring. Do not crossfade independently rendered whistle and flutter instruments.

The dominant chamber carries the requested root. Responding chambers initially use root-related ratios 2, 3, and 4 at lower levels. Ceramic/rim tones should support those anchors rather than introduce unrelated notes. Preserve the root across the actual supported pitch range.

Compensate predictable aperture/loading shifts at moderate settings, initially targeting approximately ±10 cents for a stable dominant tone. Extreme register jumps may exist in explicit presets, not ordinary defaults. All pitch targets require probe calibration and listening.

## 10. Spiral shell and acoustic coupling

SPIRAL changes shell scale, internal path impedance, chamber-to-body coupling, modal spacing, radiation loss, and limited inter-chamber propagation delay. It does not act as a generic reverb-size control.

Chambers feed the common shell. A stable reaction path loads the chamber/flap systems and can influence their catch or flutter behavior. Keep acoustic coupling passive with the piston off. Use reciprocal or otherwise demonstrably bounded couplings.

A small delay network may suggest the curved shell’s paths, but local cavity and body modes must remain perceptible. Early internal returns can disturb excitation; a global post-output echo cannot be the entire spiral contribution.

Body modes may color the note without becoming a dominant independent bass. Use smooth coefficient changes and root compensation where needed. At ordinary settings, the listener should hear one hollow object with several openings.

## 11. Leakage and release

LEAK controls bypass aperture, seal imperfection, and relaxation behavior. Higher leakage reduces stored suction and weakens pressure competition while adding a restrained flow-derived breath component. Lower leakage increases catch/hold behavior and sharper chamber interactions.

Leak noise exists only when flow exists. Do not add constant hiss to every render. Leakage must affect the pressure and mechanical model before output rather than merely mixing noise.

Provide a minimum baseline relaxation or finite drive horizon so low LEAK cannot force an endless one-shot. A high-leak normal patch should still produce useful ceramic contact and a pitched response; avoid a macro range that only silences the instrument.

## 12. Macro set

Expose five timbral controls plus HOLD. Values and neutral points are proposals; confirm MacroSpec semantics.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| DRAW | .55 | .45 | Soft short pull → stronger extraction and longer breath gesture |
| FLAP | .45 | .40 | Firmer controlled motion → compliant flutter and changing aperture |
| WEIGHT | .50 | .50 | Light ceramic contact → heavier inertia and rounded loaded knocks |
| SPIRAL | .55 | .40 | Compact direct shell → deeper coupled paths and hollow body |
| LEAK | .35 | .30 | Tight pressure retention → faster relaxation and breathier bypass |
| HOLD | .00 | .00 | Finite piston gesture → powered loopable suction behavior |

Required interactions: DRAW × FLAP changes catch/flutter onset; FLAP × WEIGHT changes contact timing and articulation; DRAW × LEAK changes pressure retention; SPIRAL × FLAP changes acoustic reaction; WEIGHT × LEAK changes how the system releases after contact.

Every normal voice must expose useful audible changes across the five controls. Mechanically meaningful onset thresholds are acceptable, but wide silent/dead regions across the voice roster are not. Probe both raw and matched-level control sweeps.

## 13. Voices and proposed defaults

Proposed enum: `UndertowVoice { KNOCK, BREATH, FLUTTER, SEAL, HOLLOW, SURGE }`.

| Voice | Character | DRAW | FLAP | WEIGHT | SPIRAL | LEAK |
|---|---|---:|---:|---:|---:|---:|
| KNOCK | Clear ceramic catch and short breath | .45 | .30 | .60 | .35 | .55 |
| BREATH | Smooth hollow pitched airflow | .55 | .40 | .35 | .55 | .40 |
| FLUTTER | Compliant repeated aperture motion | .60 | .80 | .40 | .50 | .35 |
| SEAL | Pressure competition and alternating openings | .65 | .55 | .60 | .60 | .20 |
| HOLLOW | Deep shell body with restrained contact | .50 | .45 | .65 | .85 | .35 |
| SURGE | Strong coupled draw and rough breath | .85 | .65 | .70 | .65 | .30 |

Voices bias resting geometry, contact compliance, chamber losses, excitation shape, and shell modes. They remain one coupled architecture and retain active macros.

Initial presets: First Draw, Ceramic Rim, Soft Inlet, Hollow Breath, Loose Leather, Alternating Seal, Deep Spiral, Heavy Catch, Gentle Bypass, Flutter Chamber, Returning Air, and Held Suction. Naming checks remain necessary.

## 14. One-shot duration and HOLD

One-shots use a finite piston gesture: ramp extraction, maintain a short draw, then release. Pressure, flap motion, and resonances settle afterward. Start near 2–6 seconds, extending deep shell tails only within current host limits. Use existing tail thresholds and a hard maximum duration.

HOLD supplies sustained or periodically renewed piston extraction. A replenishing bypass/exhaust path prevents unlimited piston travel in the imaginary mechanism. Powered work is explicit; passive feedback remains damped.

Voice configuration may favor stable continuous flow or a bounded pumping cycle that creates recurring aperture changes. At low DRAW and high LEAK, provide a calibrated maintenance drive sufficient for useful sustained material without repeatedly retriggering a separate contact note.

Loop convergence must include reservoir pressure, local compliances, flap position/velocity, seal hysteresis, tone resonators, body modes, and pumping phase. Integer pitch periods alone do not guarantee a seamless mechanical loop.

Render bounded preroll, compare successive cycles, and use repeatable seeded breath/roughness in HOLD. Constrain drive to a periodic useful state where necessary. Select stable material before applying the project wrap utility or short crossfade. Check for doubled rim contacts, missing breaths, and pressure jumps at the seam.

Target `Keys.seamError < 1e-3` if still the current repository contract, alongside listening for cyclic pumping and timbral discontinuities. Confirm attack-plus-loop support; if only a loop buffer exists, deliver settled suction material and document omission of the first catch. Do not silently extend the public sample API.

## 15. Numerical and deterministic requirements

Use stable pressure and contact integration, smooth aperture changes, and bounded flow laws. Limit reservoir deficit, flap travel, velocity, force gradients, resonator energy, and piston input. Passive decay must hold when extraction ends.

Avoid unresolved instantaneous loops between acoustic pressure and flap position. Document update ordering or use a stable implicit/lagged formulation with convergence checks. Threshold transitions need hysteresis and smoothing, not arbitrary one-sample resets.

Run nonlinear jet/reed excitation, flutter/contact, and bright rim impulses on the oversampled path where necessary. Slow reservoir or gross bending updates may use a lower clock, initially around 1 kHz, with interpolation and convergence validation. Band-limit before decimation.

Prevent DC accumulation, denormals, coefficient zippering, and high-register aliasing. Recover non-finite state deterministically at the failing subsystem; output limiting is not a feedback stability strategy.

All texture and structural variation use the existing seed mechanism, such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context must reproduce identical samples under the current render contract.

Apply shared melodic loudness targeting after synthesis. Compare raw and normalized auditions so a missing ceramic catch or weak shared-flow response is not hidden. Record render time and memory before optimization.

Diagnostics: reservoir/local pressure, individual flows, apertures, contact/seal events, flap mechanics, piston work, ceramic audio, airflow audio, and shell audio. Keep these outside the product control surface.

## 16. Repository integration

Expected surfaces: `Undertow.kt`, `UndertowVoice`, `UndertowPatch`, `UndertowPresets`, renderer/macro registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, tests, and roadmap. Adapt names to the actual source organization.

Round-trip voice and all macros through established JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain handling. Dry audio must demonstrate shared suction, contact, and pitched breath before effects.

Undertow is pitched melodic material. Standard auditions must satisfy existing guards against KICK, SNARE, CLAP, HAT, and TOM. Preserve pitched tails in KNOCK, tonal body in SURGE, and root energy beneath FLUTTER breath. Verify actual routing/classifier interfaces.

## 17. Build rounds

**Round 1 — one catching inlet.** Build finite extraction, pressure, flap, rim contact, and one tuned airflow resonator. Establish a causal knock-to-breath gesture. Probe pitch, peak, RMS, DC, decay, and cost before setting numerical bounds.

**Round 2 — competing chambers.** Add the shared reservoir, responding flaps, and sealing. Compare shared and isolated reservoirs. Demonstrate that closing one inlet changes another’s trajectory.

**Round 3 — one spiral object.** Add shell modes, local compliance, acoustic reaction, and controlled flutter. Verify passive ringdown and tune ordinary musical regions before rough extremes.

**Round 4 — HOLD and delivery.** Add sustained pumping, convergence, seam handling, patches, presets, kits, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm repository instructions before treating this as the complete gate.

The owner’s listening verdict remains necessary. Numeric gates establish mechanics and stability, not whether the chambers sound like one convincing object.

## 18. Audition and acceptance

Add `generateUndertowAudition` or the current equivalent. Render every voice, low/middle/high supported notes, and quiet/medium/strong event energy. Sweep macros at 0, .25, .5, .75, and 1 with neutral companions. Provide raw and matched-loudness comparisons.

Required grids: DRAW × FLAP, FLAP × WEIGHT, DRAW × LEAK, SPIRAL × FLAP, and WEIGHT × LEAK. Include isolated inlet, shared/independent reservoirs, contact-disabled diagnostics, high-leak soft draws, low-leak seals, all-high extremes, high-register flutter, and difficult HOLD cases.

| Requirement | Evidence |
|---|---|
| Identity | Ceramic catch and pitched breath unfold through the same aperture mechanics |
| Competition | Opening/sealing another chamber changes shared pressure and subsequent flow |
| Contact | Knocks follow rim crossings and disappear with contact disabled |
| Flutter | Repetition follows powered flap/flow interaction rather than a final-output LFO |
| Leakage | Bypass changes pressure relaxation and excitation, not only hiss level |
| Pitch | Moderate dominant tones retain requested root; calibrate proposed tuning limits using analysis and listening |
| Stability | Passive network decays after extraction; long powered extremes remain bounded |
| Macro activity | Every normal voice exhibits useful audible and measurable control changes |
| Determinism | Identical inputs reproduce audio and mechanical event behavior |
| Patch/routing | JSON round-trip and current pitched guards pass |
| Loops | Representative HOLD cases pass seam metric and pressure/contact continuity review |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost requirements pass |

## 19. Deferred scope and engineer decisions

Defer actual fluid dynamics, leather deformation meshes, literal spiral geometry, hardware piston construction, independently playable chamber notes, stereo propagation, persistent cross-pad state, and sampled airflow assets.

Before implementation, confirm pitch range, velocity semantics, duration limits, HOLD format, current render/patch APIs, and release naming constraints. During probes, establish reservoir normalization, flap equilibrium, seal geometry, inward-flow excitation law, stable coupling, root compensation, and useful pumping cycles. Proposed numeric values remain subject to calibration.

## 20. Sonic north star

A gentle draw should bend one weighted flap into a rounded ceramic note and open a hollow breath behind it. A stronger draw should make neighboring openings compete, flutter, and briefly seal, changing which part of the shell speaks next. Release should let the pressure ease and the object settle.

Undertow succeeds when drawing air through one opening changes the voice of the whole shell.
