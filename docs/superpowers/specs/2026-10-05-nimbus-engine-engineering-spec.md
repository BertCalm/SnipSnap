# Nimbus — engine engineering specification

Version 0.1 • 5 October 2026 • Working name; release naming checks pending

## 1. Product intent

Nimbus imagines six metal cymbals stacked vertically, electromagnetically suspended inside a funnel chamber. All six share the requested note while retaining distinct modal spectra, attacks, losses, and flexural character. Their spacing and height change independently. A strong excitation temporarily spreads or tilts the stack; the suspension draws it back together as the sound decays.

The signature is **selected metal attack → neighboring rings → yielding stack → gathering shimmer**. Close spacing can optionally introduce fine rim contact. Height changes chamber loading and which cymbals dominate the tail.

This is a generated imaginary DSP design, not literal cymbal tuning or a validated levitation/hardware model. Numeric values and acoustic mappings require probes and listening. No repository implementation was inspected.

## 2. SnipSnap contract

Use deterministic offline mono rendering, existing 44.1 kHz output/4× internal path, seeded variation, shared melodic loudness, patches, presets, pitched kit integration, auditions, and optional seamless HOLD. Confirm current repository interfaces.

Each note render initializes one complete six-cymbal stack. Independent exported pads do not share suspension state. No external samples are required; identity must be audible dry before rack effects.

## 3. Six persistent cymbal identities

| Cymbal | Character | Model emphasis |
|---|---|---|
| Body | Rounded gong-like weight | Strong principal mode, restrained upper loss |
| Bell | Sharp central definition | Clear attack and selected bright modes |
| Paper | Thin shimmer | Numerous quiet short upper modes |
| Dark | Muted metallic wash | Strong middle modes and absorbed top |
| Flex | Wavering after-strike response | Greater bounded nonlinear flexibility |
| Wire | Wiry buzzing edge | Sparse sharp modes and contact sensitivity |

These are six components present in every engine voice, not six mutually exclusive voices. Changing a preset must not reduce the instrument to a single cymbal.

Start with 5–8 modes per cymbal, six slow height coordinates, optional simplified tilt, five adjacent coupling links/contact pairs, and 4 funnel modes plus a few early paths.

## 4. Pitch and modal model

All six principal perceived modes are calibrated to the same requested root. No octave-separated primary cymbals, random chords, or six fixed detuned fundamentals. Differences arise primarily from upper modal ratios, excitation projection, damping, and coupling.

Use deliberately inharmonic upper modes with distinct per-cymbal configurations. Example ratio families may begin around 1/2.4/4.3/6.7 for Body and 1/3.1/5.2/8.1 for Bell, but these are timbral proposals rather than measurements. Paper/Dark differ in weights and losses; Flex adds bounded state-dependent stiffness; Wire emphasizes sparse upper modes.

Compensate predictable static suspension/loading shifts so macros do not transpose the note. Small transient movement-related deviation is acceptable, initially aiming below ±10 cents in ordinary patches. Optional tiny detuning must be documented and should not be the primary means of making six characters distinct.

Calibrate perceived pitch after full coupling. Cymbal-like spectra can obscure a fundamental; root recognition requires listening as well as an estimator. Suppress unsupported upper modes at the note-range boundaries.

## 5. Excitation

Note-on gives a finite magnetic flex impulse predominantly to a selected cymbal, with weaker neighbor excitation through the coupled field and chamber. EXCITE morphs from soft distributed pull/release toward a concentrated, harder striker-like contact. Both feed the same modal structure.

If velocity exists, it scales event energy and transient stack displacement within EXCITE’s contact character. A harder event changes modal excitation and suspension response, not only final gain.

Voices select initial excitation preference and distribution, but every component remains capable of answering. Do not seed all six with identical simultaneous envelopes and call the result sympathetic motion.

## 6. Suspension and energy

Separate acoustic flexural vibration from slow vertical/tilt motion. The electromagnetic system is an active suspension controller with bounded forces and damping; it is not a source of unlimited free resonance.

Each slow coordinate follows resting height, neighbor spacing, bounded vibration-induced disturbance, and controller restoration. FIELD changes compliance, restoration speed, damping, and limited coupling. Strong fields need not mean louder tones: firmer stabilization can restrict movement.

A smoothed local vibration-energy estimate may drive temporary spreading in the imaginary model. Its projection must be bounded and energetically accounted for through stored modal energy or controller work. Avoid a positive loop where sound pushes the stack apart and geometry injects still more energy indefinitely.

Static magnetic coupling redistributes energy. Powered sustain in HOLD is separate from ordinary stabilization. With event excitation and acoustic drive off, the audible structure decays even if the stack remains levitated.

## 7. Spacing and neighboring coupling

SPACING sets resting separation independent of stack height. Wide settings weaken neighbor transfer and let characters ring separately. Close settings strengthen acoustic/magnetic loading and change decay, modal splitting, and response timing.

Use smooth bounded displacement-dependent pair forces rather than singular inverse-distance attraction. Project reciprocal reactions into modes and include dissipative relative motion terms. Enforce positive effective restoring stiffness across supported geometry.

At extreme close settings, optional compliant rim contacts can produce fine sizzling. Contact uses bounded repulsion and damping, not a separate buzz oscillator. Zero collision is a diagnostic option; normal wide settings should have no collisions.

Coupling can split exactly coincident resonances. Calibrate ordinary gains/compensation to preserve the perceived requested note rather than disguising large pitch shifts with normalization.

## 8. Height and funnel

HEIGHT moves the whole resting stack from the narrow throat toward the wide mouth. FUNNEL changes chamber taper, scale, loss, and return weighting. Keep these independent: the same height can sound different in a broad or narrow funnel.

Geometry maps to modal loading, radiation, and a few source-specific early return paths. The chosen musical rule is greater enclosure concentration near the throat and more exposed direct metal near the mouth. This is a design mapping, not a universal acoustic assertion.

Height must not simply change final output gain. It changes which components receive returning pressure and how their modes decay. Use bounded acoustic reaction to re-excite or load the same cymbals.

Maintain valid geometry at all SPACING/HEIGHT combinations. At wide spacing near an endpoint, compress the feasible stack travel smoothly or map coordinates within a larger compatible funnel. Never let plates pass through one another or a chamber boundary.

Mono output preserves motion through changing loading/paths, not stereo panning. Smooth fractional delay changes; limit unwanted Doppler at ordinary settings.

## 9. Macros

Proposed normalized defaults/neutral values require MacroSpec confirmation and calibration.

| Macro | Default | Neutral proposal | Low → high |
|---|---:|---:|---|
| EXCITE | .45 | .40 | Soft magnetic flex → hard concentrated contact |
| SPACING | .50 | .45 | Close coupled stack → separated individual rings |
| HEIGHT | .50 | .50 | Narrow throat → wide mouth |
| FIELD | .50 | .40 | Yielding suspension → firmer restoration and coupling |
| FUNNEL | .55 | .40 | Open shallow loading → deeper tapered enclosure |
| HOLD | .00 | .00 | Finite event → powered seamless metal sustain |

Required interactions: EXCITE × FIELD, SPACING × FIELD, HEIGHT × FUNNEL, SPACING × FUNNEL, and EXCITE × SPACING. Every timbral macro must work audibly across normal voices. HEIGHT and SPACING must never collapse into the same size control.

## 10. Voices and presets

Proposed enum: `NimbusVoice { RING, SHIMMER, GATHER, THROAT, CONTACT, SUSPEND }`.

| Voice | EXCITE | SPACING | HEIGHT | FIELD | FUNNEL | Identity |
|---|---:|---:|---:|---:|---:|---|
| RING | .50 | .70 | .65 | .55 | .35 | Six clear same-note characters |
| SHIMMER | .35 | .50 | .70 | .45 | .50 | Fine upper-mode response |
| GATHER | .65 | .35 | .50 | .35 | .55 | Strong spread-and-return gesture |
| THROAT | .45 | .45 | .15 | .50 | .85 | Concentrated dark chamber |
| CONTACT | .65 | .10 | .50 | .55 | .55 | Pitched fine rim sizzling |
| SUSPEND | .30 | .55 | .55 | .65 | .65 | Long supported metallic texture |

Voices bias initial excitation, modal loss, and suspension calibration while preserving all six identities. Suggested presets: Six Rings, Thin Crown, Dark Plate, Gathering Stack, Narrow Throat, Wide Mouth, Soft Field, Rim Kiss, and Held Metal.

## 11. Duration and HOLD

One-shot mode supplies finite excitation and bounded controller work, then audible resonance decays. Start near 2–6 seconds with longer rings only within host limits. End at the established tail threshold or maximum duration.

HOLD adds explicit weak powered modal excitation through the magnetic drive. Levitation alone is not sustain. Use bounded continuous drive or a smooth compatible cycle without repeated attack clicks unless a rhythmic preset intends them.

Converge acoustic modes, stack positions/velocities, tilt/contact, funnel buffers, and control state with bounded preroll. Periodic drive phase does not ensure periodic contacts. Restrict seeded roughness and drive to a repeatable stable HOLD state where needed.

Use project-standard wrap handling after selecting a stable region. Verify contact continuity, peaks, and pitch after crossfade. Target `Keys.seamError < 1e-3` if still current, plus listening. Confirm attack-plus-loop support; loop-only output represents settled sustain rather than the initial gathering gesture.

## 12. Numerical and integration requirements

Bound modal energy, displacement, controller work, contact penetration, and force gradients. Avoid unresolved instantaneous coupling loops. Run nonlinear contact/flexural excitation on the oversampled path; interpolate slow state smoothly and validate solver-rate convergence.

Prevent DC, denormals, coefficient zippering, aliasing, and geometry singularities. Recover non-finite state deterministically at its source. A final limiter cannot hide unstable suspension or coupling.

Use existing seeds such as `Dsp.seedFor`; identical render inputs reproduce identical audio/mechanical behavior. Apply shared melodic loudness targeting and compare raw/normalized auditions.

Expected surfaces: `Nimbus.kt`, voice/patch/presets, renderer/macros, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap. Reuse current output and FX conventions. Because metal shimmer can resemble hats, standard pitched cases must preserve root-bearing resonance and satisfy current KICK/SNARE/CLAP/HAT/TOM guards.

## 13. Build rounds and acceptance

1. Build six isolated same-note modal characters and test tuning/identity.
2. Add passive neighbor coupling and bounded active suspension; demonstrate finite spread and restoration.
3. Add funnel height/loading and optional rim contact; tune ordinary patches before extremes.
4. Complete HOLD and repository integration. Confirm required checks; supplied baseline names `./gradlew --no-daemon test`.

Probe pitch, modal balance, level, DC, decay, aliasing, cost, and macro activity before choosing bounds. Audition every voice at low/middle/high notes and velocity, control sweeps 0/.25/.5/.75/1, interaction grids, all-high settings, and difficult HOLD cases.

Acceptance requires six distinct isolated spectra/envelopes with the same root; full-mix root recognition; independent spacing/height behavior; restoration tied to bounded disturbance; contact tied to actual rim motion; passive acoustic decay without sustain drive; deterministic finite extremes; useful controls; patch round-trip; current routing guards; seam metric and listening success.

## 14. Open decisions and deferred scope

Confirm note range, velocity, duration, HOLD format, APIs, and naming. Calibrate mode counts, suspension compliance, vibration resistance projection, root compensation, chamber loading, and rim clearances. Defer hardware electromagnetics, full plate meshes, chords across the six primary cymbals, stereo, and persistent pad state.

Nimbus succeeds when six characters spread under excitation and gather back into the same note.
