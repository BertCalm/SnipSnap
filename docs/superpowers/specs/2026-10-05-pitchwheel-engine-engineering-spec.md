# Pitchwheel — engine engineering specification

Version 0.1 • 5 October 2026 • Working name; release naming checks pending

## 1. Product intent

Pitchwheel is a large resonant wooden wheel with tuned teeth turning through thick resin. A finite push moves the wheel. Its teeth catch flexible wooden fingers, producing pitched releases. Resin filaments attach, stretch, bow against the teeth, and sometimes snap. Their resistance can stop the wheel or pull it backward, letting a tooth encounter a finger again.

The signature is **push → catching tooth → pitched release → stretching resin → hesitation or recoil**. Motion warms the resin, changing subsequent resistance; resting allows it to cool. The phrase develops from mechanical state rather than a sequencer.

This is an imaginary DSP instrument, not a validated model of resin or a literal build plan. Numeric values are calibration proposals. No repository implementation was inspected.

## 2. SnipSnap contract

Use deterministic offline mono rendering, the established 44.1 kHz output/4× internal path, seeded variation, shared melodic loudness processing, patch serialization, presets, pitched kit integration, auditions, and optional held loops. Confirm current interfaces against the repository before adopting the names below.

Each render starts a fresh complete object at one requested root. Exported pads do not share heat, wheel phase, or resin state. No external samples are required. The dry mechanism must establish the sound before rack FX.

## 3. V1 components

| System | Starting budget | Purpose |
|---|---:|---|
| Wheel rotation | One angular position and velocity | Event timing and resistance |
| Teeth | 8–12 contact locations | Repeated encounters, not melodic steps |
| Flexible fingers | 2–3 damped deflection states | Catch, store energy, release |
| Resin filaments | Up to 4 simultaneous attachments | Drag, bow, stretch, snap |
| Pitched tooth/finger resonance | 4–6 modes | Root-bearing plucked or bowed sound |
| Wooden wheel/body | 4–6 modes | Dense woody response and sympathetic ring |
| Resin temperature | One bounded slow state | History-dependent resistance |

Use one shared resonant network with contact-specific excitation projections. Teeth need not have separate full modal banks. Slight deterministic tooth differences change attack and upper-mode balance while retaining the requested root.

## 4. Push and wheel motion

PUSH controls initial angular impulse and the resulting encounter density. If velocity is supplied by the host, scale gesture energy within the PUSH character. Do not equate wheel speed with oscillator pitch.

Use bounded dynamics of the form `I * angularAcceleration = pushTorque + fingerTorque + filamentTorque - dragTorque`. Choose consistent internal units and document them. Include wheel inertia, viscous loss, and a smooth low-speed resistance model. Static resistance may hold a wheel at rest when available torque is insufficient.

Start with an initial speed that yields roughly 2–10 tooth encounters per second across useful settings. Angular phase and tooth positions determine actual timing. Modest nonuniform spacing can produce organic timing, but must be generated from the render seed and remain fixed during that render.

A finite push supplies finite energy. Later acceleration can occur when a stretched finger or filament releases stored energy, or in HOLD with explicit powered torque. Heating reduces resistance; it does not manufacture energy. A warm wheel can move farther or decelerate more slowly than a cold one with the same input.

## 5. Tooth and finger interaction

Detect contact as a tooth crosses a finger's angular contact region in either direction. Capture incoming speed and contact state. Bend the finger continuously while contact persists, then release when the tooth clears its edge or the bounded contact force reaches a release threshold.

TOOTH moves from rounded, compliant catches to shorter, harder releases. It changes contact profile, finger stiffness, release threshold, and excitation projection. Keep its influence distinct from PUSH: increasing TOOTH sharpens articulation without necessarily increasing encounter rate.

The finger's stored elastic energy pays for the release excitation. Apply the reaction torque to the wheel. Avoid both retaining all finger energy and independently adding a full-strength pluck. Use an energy ledger or an explicitly passive contact mapping.

For reverse motion, allow a tooth to encounter the finger from the other side with a related but distinct release shape. Use contact hysteresis and geometric clearance so slow oscillation at one boundary does not create unlimited attacks. A repeat requires a real disengagement and recrossing.

## 6. Resin attachment, bowing, and snap

Attach a filament when a tooth enters the resin region, subject to a deterministic attachment threshold and the active filament budget. Track attachment position, stretch, stretch rate, and a bounded adhesion state. Remove a filament when it releases or snaps.

ADHESION raises attachment likelihood, maximum stretch, and resistance. At low values, the resin gives a light drag with occasional short release. At medium values, stretched filaments can sustain a smooth bowed layer between tooth attacks. At high values, they can hold the wheel, produce long strain, and pull it backward before release.

Model the filament as a lossy elastic element with a bounded force law. Its reaction torque must oppose stretch or restore stored displacement. Avoid simply multiplying noise by wheel speed and calling it friction.

Continuous bow excitation comes from relative slip at the tooth/filament interface, using a regularized stick-slip or other bounded nonlinear interaction. Snap converts a bounded share of remaining elastic energy into an impulse through the same tooth/body network. Any residual becomes loss. Snap texture follows attachments and force thresholds; it is not an independent random crackle track.

Separate structural resin drag from acoustic coloration. A silent state probe should still show slowing, holding, and recoil. An acoustic probe should show that snap and bowed energy vanish when the corresponding physical events are absent.

## 7. Temperature and memory

HEAT sets the starting temperature and cooling reference. Local work lost to resin friction raises temperature during the gesture. Cooling approaches the reference when motion declines.

Begin with warming times of approximately 0.3–2 seconds and cooling times of 1–4 seconds; calibrate so change is audible within supported sample lengths. Temperature reduces viscous resistance and changes attachment/release behavior within bounded ranges. Keep adhesion meaningful when warm rather than switching it off completely.

Use nonnegative dissipated work for heating. Elastic energy returned to the wheel is not heat. Bound temperature, its rate of change, and all resulting coefficients. Thermal state is an intentional expressive approximation, not a materials simulation.

HEAT must also produce a useful difference in short gestures by changing initial resistance. Do not require a long warm-up for the control to become audible. All thermal history resets with a fresh render; HOLD may settle at a warmer operating state.

## 8. Pitch and wooden body

Tune the principal tooth/finger resonance to the requested root. Wheel rotation and tooth encounters control rhythm, while resonant modes control pitch. Changing PUSH must not transpose the instrument.

Start with a root-bearing plucked bank and sparse, damped upper modes. Illustrative ratios such as 1, 2.1, 3.9, and 6.2 are design hypotheses, not measurements. A bowed state may favor a more harmonic subset. Preserve root recognition when harder contacts emphasize upper modes.

BODY changes the shared wheel's effective acoustic size, stiffness, loss, and receiving projection: compact dry clunks through broad hollow wooden resonance. Compensate principal tuning as necessary so this changes character rather than selecting a new note. Couple tooth and body modes passively or with bounded transfer.

Do not use resonance strength to create endless motion. Acoustic vibration may weakly affect a finger/contact state if useful, but that transfer must take energy from the resonator and remain stable. V1 may omit this reverse coupling if it adds cost without an audible benefit.

## 9. Controls

Expose five normalized timbral macros and a HOLD toggle. Values below are initial patch proposals; calibrate through probes and listening.

| Macro | Low → high | Default | Neutral reference |
|---|---|---:|---:|
| PUSH | Few slow encounters → strong dense gesture | .50 | .45 |
| TOOTH | Rounded flexible catch → hard short release | .45 | .40 |
| ADHESION | Light drag → strong stretch, stall, recoil | .55 | .35 |
| HEAT | Cool resistant resin → warm easier motion | .40 | .45 |
| BODY | Compact dry wood → broad hollow resonance | .55 | .45 |
| HOLD | Finite push → powered continuing motion | 0 | 0 |

Neutral reference means a comparison patch for macro audition, not a disabled mechanism. Smooth audible parameter changes on the appropriate timescale. Recalculate bounded contact limits and pitch compensation consistently.

PUSH × ADHESION is the primary interaction: enough energy can pull through a sticky region, while a weaker gesture may strain and reverse. HEAT changes how that relationship evolves. BODY and TOOTH change the resulting sound without replacing the motion model.

## 10. Voices and presets

Proposed enum: `PitchwheelVoice { CLUNK, PLUCK, DRAW, RECOIL, THAWED, TURN }`.

| Voice | PUSH | TOOTH | ADHESION | HEAT | BODY | Identity |
|---|---:|---:|---:|---:|---:|---|
| CLUNK | .45 | .25 | .30 | .40 | .75 | Dense rounded wooden catches |
| PLUCK | .55 | .70 | .35 | .55 | .40 | Clear pitched tooth releases |
| DRAW | .45 | .40 | .65 | .50 | .60 | Resin bow between attacks |
| RECOIL | .45 | .50 | .85 | .20 | .55 | Strain, reverse, repeated tooth |
| THAWED | .65 | .45 | .55 | .80 | .60 | Easier passage with changing drag |
| TURN | .55 | .45 | .50 | .55 | .55 | Balanced continuing motion |

Voices select contact, loss, and excitation calibrations within the same mechanism. TURN is the preferred starting voice for HOLD, not an automatic HOLD switch. Suggested presets: Wooden Ratchet, Resin Thread, Returning Tooth, Cold Wheel, Warm Passage, Hollow Rim, Sticky Bow, and Endless Turn.

## 11. Duration and HOLD

One-shot mode supplies a finite initial push; wheel motion stops and acoustic energy decays. Start with useful durations around 2–6 seconds. Render until the existing tail threshold or host duration limit. Prevent a silent stuck wheel from consuming the full render unnecessarily.

HOLD supplies an explicit bounded torque source with smooth speed regulation and a maximum power/work budget. It replenishes losses without overriding local resistance: catching and resin may still slow the wheel. If contact stalls indefinitely, use a calibrated drive limit or less resistant HOLD operating range; do not teleport wheel position to the next tooth.

For looping, prefer a repeatable settled orbit. Converge wheel phase/speed, finger deflections, attachment lifecycle, temperature, resonator modes, controller state, and any buffers during bounded preroll. A full wheel revolution alone does not guarantee a seam because resin and heat retain history.

If chaotic contact prevents a repeatable state, constrain the HOLD attachment cycle or offer a loop-specific stable calibration. Document that choice. Use existing wrap/crossfade handling only after selecting a stable region; verify repeated contacts, peak level, pitch, and timing after wrapping. Target the current `Keys.seamError < 1e-3` contract if still applicable, plus listening. Confirm whether the host supports attack-plus-loop or settled loop-only output.

## 12. Numerical and integration requirements

Run nonlinear finger/resin interactions on the oversampled path; slower wheel/thermal state may use a documented lower-rate solver with smooth interpolation and accurate boundary crossing detection. Resolve multiple tooth crossings within a step. Validate that solver-rate changes do not materially alter event counts or phrase behavior.

Bound force derivatives, elastic energy, angular speed, filament count, stretch, temperature, and contact penetration. Avoid discontinuous zero-speed sign functions and unresolved instantaneous coupling loops. Use finite restitution/loss at releases. A final limiter cannot conceal an unstable contact solver.

Prevent DC, aliasing, coefficient zippering, denormals, and non-finite state. Seed tooth variation, attachment decisions, and roughness through existing deterministic infrastructure such as `Dsp.seedFor`. Identical inputs must reproduce both event trace and audio. Apply shared `MELODIC_LOUDNESS_TARGET` processing if that remains the project contract; inspect raw and normalized renders.

Expected surfaces include `Pitchwheel.kt`, voice and patch definitions, presets, renderer/macros, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap. Follow current serialization conventions, including established sibling patch structures. Keep pitched exports out of KICK/SNARE/CLAP/HAT/TOM routing under current guards.

## 13. Build rounds and acceptance

1. Build finite wheel motion, tooth encounters, passive finger releases, and root-bearing wood resonance. Establish useful dry identity.
2. Add bounded resin attachments, continuous bowing, snap, and recoil. Inspect energy and event traces before tuning extremes.
3. Add thermal evolution, macro interactions, voices, loudness, and note-range calibration.
4. Complete stable HOLD, patch serialization, kit integration, auditions, and required repository checks. The supplied baseline is `./gradlew --no-daemon test`; verify any additional current requirements.

Probe pitch, raw energy, level, DC, decay, attachment count, reversals, encounter timing, temperature, aliasing, and render cost. Audition low/middle/high notes, velocity extremes where supported, macro sweeps at 0/.25/.5/.75/1, PUSH × ADHESION and ADHESION × HEAT grids, all-high settings, and difficult HOLD cases.

Acceptance requires:

- PUSH changes encounter timing without systematic pitch transposition.
- Each pluck follows a real tooth/finger release; each snap follows a filament event.
- A designated RECOIL patch produces an audible reverse encounter without boundary chatter.
- Heating changes resistance over a gesture; colder and warmer starts differ in short renders.
- With powered sources off, total available mechanical/acoustic energy cannot grow without an accounted transfer, and a finite gesture eventually rests.
- The root survives bowed, hollow, and hard-contact settings across the supported range.
- Renders are deterministic and finite at extremes; serialization round-trips; controls are useful; routing guards pass.
- HOLD satisfies the current seam metric and repeated-loop listening checks.

The owner must approve dry auditions before widening preset scope. A parameter merely changing a metric does not establish a musically useful sound.

## 14. Open decisions and deferred scope

Confirm note range, velocity support, maximum duration, HOLD export format, identifiers, and release naming. Calibrate inertia, tooth spacing, finger profile, resin force law, warming/cooling rates, mode counts, and energy transfer fractions from prototypes.

Defer literal fluid simulation, a full deformable wooden wheel, melody assigned to individual teeth, stereo motion, persistent cross-pad thermal memory, and physical construction. Pitchwheel succeeds when resistance audibly shapes the phrase while the instrument remains clearly pitched.
