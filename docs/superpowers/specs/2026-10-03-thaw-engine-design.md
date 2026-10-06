# Thaw — engine engineering specification

Version 0.1 • 3 October 2026, America/Chicago • Working name; naming checks pending

## 1. Summary

Thaw is a physically inspired pitched synthesis engine for SnipSnap. It imagines a ring of tuned ice plates suspended inside a refrigerated wooden enclosure. Copper runners press against the plates and travel across their surfaces. Contact first produces a brittle pitched attack. Friction warms the contact zone, creating a thin melt layer that changes scraping into slipping and singing. Meltwater enters channels between plates, changing their loading and coupling. Cooling subsequently freezes the channels and restores a more brittle response.

The signature gesture is **crystalline contact → warming friction → smoother wavering sustain → refreezing resonance**. The material state changes during the note and changes the mechanism that produces the next part of that note.

This is a generated imaginary-instrument design, not a validated simulation of ice or a hardware construction plan. Material relationships, equations, timings, and defaults are proposals requiring DSP probes and listening. In particular, melting is not assumed to improve real-world friction excitation universally, and freezing is not assumed to create audible cracks automatically. Thaw defines those behaviors explicitly within its musical model.

## 2. Scope and architecture baseline

Follow the supplied SnipSnap baseline and the constraints in the edited Gyre specification: deterministic offline mono samples, macro-driven rendering, patches, presets, pitched kit routing, shared loudness processing, auditions, and optional seamless held loops.

Expected audio path: final 44.1 kHz output through the existing 4× internal rendering and band-limited decimation infrastructure. Confirm current repository interfaces and utilities before implementation. No repository checkout was inspected for this specification.

V1 renders one requested note through a small coupled ice structure. Every independently rendered sample starts from an explicit deterministic frozen state. Repeated notes inside an optional diagnostic phrase harness may retain wetness and thermal state, but independently exported pads do not share that history. Cross-pad thermal memory is deferred.

No external sample assets are required. The engine must establish its material transformation with an empty or minimal effects chain.

## 3. Sonic goals and non-goals

Useful regions should include clear glassy-but-damped plucks, brittle bowed attacks, smooth translucent sustains, thin wavering leads, resonant frozen clouds, channel-coupled overtones, and fine stress-release events in the tail. Extremes may produce unstable pitch capture, scraping, frost-like granulation, and constrained fracture texture.

Thaw must remain a pitched resonator instrument. It is not a generic bowed oscillator with crackle added, a watery chorus effect, a granular sample player, or a filter sweep called temperature. Thermal and phase state must influence contact, damping, loading, and coupling before the final sum.

Ordinary settings should retain the requested note. Avoid turning every patch into a pitch slide or an unpitched melting sound effect.

## 4. V1 component budget

| Component | Starting count | Function |
|---|---:|---|
| Ice plates | 4 | Dominant pitched plate and three responding plates |
| Acoustic modes | 4 per plate | Pitched body, inharmonic upper modes, stress response |
| Driven runners | 1 main, 1 quieter linked runner | Contact and finite or sustained carriage motion |
| Contact/thermal zones | 1 per plate | Local heat and melt-layer state |
| Channels | 4 adjacent connections around a ring | Wetness-dependent loading and coupling |
| Wooden enclosure modes | 3–4 | Warm supporting body and absorption |

Use reduced modal plates and scalar thermal/channel states. No fluid solver, full fracture mesh, or literal three-dimensional heat simulation is necessary. Neighboring plates receive mechanical energy through the structure and channels; they are not all directly struck at note-on.

## 5. Contact and carriage

The performer’s gesture lowers a copper runner onto the dominant plate and moves it across the surface. CONTACT controls normal force, engagement sharpness, and contact footprint. Velocity, if supported, scales gesture energy within that character.

Contact onset produces a short band-limited force pulse projected into the plate modes. The runner then feeds the same modal structure through a stable friction law. Keep the initial attack and later sustain connected by shared plate state.

Use relative velocity between the runner and the vibrating surface. A regularized bristle or stick/slip model is preferred over an unconstrained nonlinear equation. Parameters depend on wetness and temperature. The runner’s carriage is an explicit powered energy source; heat and acoustic output draw from its contact work.

At low CONTACT, retain an audible light attack and delicate friction over the normal voice roster. High CONTACT should add harmonic density, scrape, loading, and heat, but may also inhibit free ringing. Pressure must not simply increase final gain.

The linked runner engages later and more softly, with a deterministic timing offset. It can create a secondary warming zone without directly duplicating the dominant attack. Keep its plate at a root-related overtone and its level subordinate by default.

## 6. Thermal and phase-state model

Separate temperature proxy, local liquid fraction, channel liquid content, and structural stress. Temperature is not itself wetness. A lightweight enthalpy-style state is recommended so heating can first reach a transition region, then change liquid fraction, and finally warm the liquid region.

Conceptually:

`heatRate = frictionWork × heatEfficiency + carriageHeater − coolingLoss − neighborTransfer`

HEAT increases conversion into contact-zone heat and a small explicitly powered runner-heater term. FREEZE controls cooling strength. These sources and sinks must be documented in normalized units. Do not present them as measured thermodynamic constants.

Use bounded latent-transition behavior or a smooth approximation. Cooling should reverse the phase evolution with modest hysteresis to avoid rapid state chatter. Local liquid fraction stays in [0,1]. The contact layer represents a thin surface region; it does not consume the entire tuned plate. Plate thickness remains available as a structural parameter.

Suggested musical time scales: detectable warming in approximately 100–700 ms; channel response somewhat later; cooling on the order of 0.3–3 seconds. These are exaggerated design timings to make a transformation audible in finite samples.

At HEAT=0, ordinary mechanical friction may still warm the contact zone slowly. At FREEZE=0, retain bounded baseline environmental cooling or a documented render termination rule so finite one-shots do not require an endless tail. Macro extremes do not remove state bounds.

## 7. Wetness-dependent excitation

Wetness changes the contact law rather than selecting a different instrument. Dry contact has more irregular catches, higher-frequency surface texture, and a brittle attack. A moderate melt layer softens contact and enables a more consistent pitched friction region in this imaginary model. Excessive wetness reduces traction and can weaken or interrupt excitation.

Use a continuous non-monotonic mapping: dry scrape → useful singing region → slippery, less strongly excited region. Never assume that maximum HEAT means maximum sustain or maximum volume.

Friction force depends on relative velocity, normal loading, contact state, and wetness. It acts on the same resonant modes throughout. If the proposed law produces only noise, revise its excitation and loading before adding an independent pitched oscillator. A stable pitched capture region is a listening and diagnostic acceptance requirement.

Seeded microscopic surface texture may modulate the friction law. Filter it according to carriage speed and bandwidth. When carriage motion and powered drive stop, new friction excitation stops; remaining plate resonance decays naturally.

## 8. Plates, thickness, and tuning

Tune the dominant plate’s principal perceived mode to the requested pitch. Start with upper-mode ratios near 1, 2.3, 4.0, and 6.1, then establish the ice-like identity through audition. These are proposed timbral ratios, not acoustic measurements of ice.

Other plate fundamentals begin at 2, 3, and 4 times the requested root. Keep their levels lower. A voice may substitute a simple ratio such as 3/2 if pitch remains clear. Avoid a dominant lower resonance or an arbitrary chord.

THICKNESS changes modal spacing, effective mass, contact compliance, thermal response speed, and losses. Compensate the root mode so changing thickness does not transpose the requested note. Contact and melt loading may produce small transient deviations, but ordinary sustained pitch should remain within an initially proposed ±10-cent target.

Permit greater drift in explicitly extreme patches, with a documented bound and audible root identity. Do not use a free-running vibrato oscillator as the primary source of wavering: fluctuations should follow contact and material-state evolution.

Use stable modal coefficient updates when loading changes. Avoid abrupt retuning of a ringing resonator, which can produce clicks or inject energy. Attenuate modes beyond the usable bandwidth.

## 9. Channels and inter-plate response

CHANNELS changes channel capacity, transfer conductance, acoustic coupling, and load sensitivity. Melt produced at a contact zone fills adjacent channels through a bounded delayed transfer model. Track capacity and conserve the model’s normalized liquid quantity except for explicit melting, freezing, and drainage terms.

Wet channels alter mass loading and dissipative coupling between plates. Frozen bridges restore a different, stiffer coupling regime. Interpolate smoothly between these regimes. Wetness should not simply increase feedback gain; it can also increase loss and lower the strength of a ring.

Represent acoustic transfer with reciprocal or otherwise demonstrably passive couplings. Separate slow liquid transport from fast acoustic vibration. Neither a full water simulation nor a generic reverb is needed.

Responding plates receive energy through these links and the mounting. Stronger channel interaction should change which partials answer and how their tails evolve. It must not merely add a global chorus or prolong every resonance equally.

## 10. Freezing, stress, and fine fracture events

As liquid fraction decreases, a bounded stress state develops from constrained phase change and structural loading. A release event occurs when that state crosses a threshold. Event strength is limited by stored stress energy and resets or reduces the corresponding state.

This is an imaginary musical microfracture model, not a claim that every real frozen channel makes a sound. Freeze rate, available liquid, and constraint determine whether an event occurs. A dry plate with no phase change should not generate arbitrary refreezing crackle.

Project short release forces into plate and mounting modes. Add only a small accompanying band-limited surface texture. The result should sound like fine events inside the resonance, not a constant vinyl-crackle layer.

Limit event rate, impose refractory behavior, and keep event amplitudes subordinate at default settings. High FREEZE and CHANNELS may make the tail more articulated. Stress release must reduce stored energy; it cannot become an unlimited excitation source after cooling has settled.

## 11. Wooden enclosure

The enclosure receives mounting forces and loads the plates through a small stable reaction path. Give it a restrained warm low-mid response and high-frequency absorption. It supports the ice identity rather than dominating the requested pitch.

Its properties are voice configuration in V1; no seventh product control is required. THICKNESS may correlate mounting load with plate mass, but must not become a disguised body filter. No generic reverb substitutes for the enclosure.

## 12. Macro set

Use five timbral macros plus HOLD. Confirm current MacroSpec neutral semantics. Values are initial hypotheses.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| CONTACT | .45 | .40 | Light, delicate runner engagement → firm contact, scrape, and greater work |
| HEAT | .50 | .40 | Slow warming and dry character → rapid melt transition and slippery extremes |
| FREEZE | .45 | .40 | Long wet relaxation → fast restoration, stronger constrained stress response |
| CHANNELS | .40 | .30 | Mostly local behavior → delayed liquid loading and inter-plate response |
| THICKNESS | .50 | .50 | Thin, responsive, bright structure → heavier, slower material response |
| HOLD | .00 | .00 | Finite contact/warming gesture → loopable sustained material cycle |

Required interactions: CONTACT × HEAT changes warming and friction capture; HEAT × FREEZE sets the phase trajectory; CHANNELS × FREEZE changes stress-release behavior; THICKNESS × HEAT changes response time; CONTACT × THICKNESS changes loading and articulation.

Every normal voice must expose useful audible changes across all five timbral controls. CHANNELS should affect resting acoustic linkage as well as later liquid transfer so short or nearly dry gestures still respond. FREEZE may also alter initial cold-state contact within a restrained range; do not leave it inactive until a very long tail.

## 13. Voices and initial defaults

Proposed enum: `ThawVoice { BRITTLE, RUNNER, MELT, CHANNEL, FROST, SHEET }`.

| Voice | Character | CONTACT | HEAT | FREEZE | CHANNELS | THICKNESS |
|---|---|---:|---:|---:|---:|---:|
| BRITTLE | Clear cold attack and restrained melt | .40 | .20 | .70 | .25 | .35 |
| RUNNER | Audible dry-to-singing friction gesture | .60 | .50 | .40 | .30 | .50 |
| MELT | Smooth translucent sustain with slipping edges | .40 | .75 | .25 | .45 | .45 |
| CHANNEL | Coupled ringing plates and delayed response | .45 | .55 | .45 | .80 | .55 |
| FROST | Cooling articulation and fine stress releases | .50 | .55 | .85 | .70 | .40 |
| SHEET | Darker heavy plate with slow transformation | .55 | .45 | .35 | .55 | .85 |

Voice differences include modal ratios, carriage profile, surface texture, losses, channel capacity, and constraint strength. All voices use the same causal engine and retain active macros.

Suggested presets: First Contact, Thin Ice, Copper Runner, Soft Melt, Clear Channel, Frozen Bridge, Slow Sheet, Returning Frost, Wet Edge, Cold Choir, Fine Fracture, and Thermal Cycle. Names require release checks.

## 14. One-shot duration and material restoration

One-shots use a finite carriage envelope: engage, travel, withdraw. Start with a 2–6 second total render range, extending long channel tails only within current host limits. Continue cooling and resonance after withdrawal, then stop when audible activity falls below the established threshold or the maximum duration is reached.

Do not wait for exact thermal equilibrium after audio has become silent. State restoration is internal to each render and the optional diagnostic phrase harness; it does not delay export indefinitely.

The default patch should demonstrate a meaningful material transition within its sample duration. If HEAT and FREEZE balance to a nearly static state, other settings must still expose a clear warm/cold contrast. Document that a stationary equilibrium is a valid region, not the sole behavior of the engine.

## 15. HOLD and seamless loops

HOLD powers a repeating carriage/heater/cooling cycle or a stable balanced contact state, depending on voice. The same thermal, friction, and plate network remains active. A cyclic mode must alternate enough heating and cooling to return to a compatible material state without resetting it at the wrap.

Choose a bounded cycle period compatible with the supported loop duration. For slowly evolving presets, use the longest supported useful loop or a documented compressed musical time scale. Do not promise an arbitrarily long thermal history inside a short buffer.

Render bounded preroll and compare consecutive complete cycles. Convergence checks include contact state, temperature/enthalpy, liquid fractions, channel quantities, stress, plate modes, and enclosure modes. Periodic carriage phase alone is insufficient.

Use periodic seeded roughness in HOLD mode and ensure stress events repeat compatibly or converge to a stable event pattern. A small project-standard wrap crossfade is acceptable after selecting a stable region. Check that it does not duplicate fracture events or flatten the material transformation.

Require `Keys.seamError < 1e-3` if still the current repository contract, alongside listening for clicks, cyclic pumping, and repeated-tail artifacts. Confirm whether the host can store an attack plus loop region; if it only returns a loop buffer, deliver settled sustain material and document the missing initial cold attack.

## 16. Numerical and rendering requirements

Use stable friction integration and smooth state-dependent modal updates. Bound contact force, thermal state, liquid capacity, stress, coupling gradients, and acoustic energy. Passive networks must decay after powered excitation is off; thermal-state coefficient changes must not inject uncontrolled energy.

Run nonlinear contact and short stress impulses on the oversampled path. Thermal/channel updates may use a slower clock with interpolation; start near 1 kHz and validate convergence. Avoid coefficient zippering, DC accumulation, denormals, and aliased roughness.

All noise, structural variations, and threshold perturbations use the existing deterministic seed mechanism, such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context must reproduce identical samples under the current render contract.

Detect non-finite states at their source and recover deterministically. Do not hide unstable feedback or contact behind final limiting. Band-limit before decimation and apply shared melodic loudness targeting afterward.

Retain debug taps for runner velocity, contact force/work, temperature, liquid fraction, channel contents, stress events, direct/neighbor plate audio, and enclosure audio. Compare raw and normalized clips so leveling cannot disguise a weak phase transition or missing pitched source.

## 17. Integration

Expected surfaces: `Thaw.kt`, `ThawVoice`, `ThawPatch`, `ThawPresets`, renderer dispatch, macro registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, tests, and roadmap. Adapt identifiers to the actual repository.

Round-trip voice and all macros through established JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and existing FxChain infrastructure. Core transformation must work before rack effects.

Thaw is pitched melodic material. Standard audition cases must avoid KICK, SNARE, CLAP, HAT, and TOM under existing guards. Preserve a tonal tail in BRITTLE, keep FROST releases secondary, and retain root energy beneath RUNNER scrape. Verify current routing rather than assuming a particular source interface.

## 18. Build rounds and listening gates

**Round 1 — cold contact.** Build one plate and one powered runner. Establish pitched friction capture and a useful brittle attack. Collect pitch, peak, RMS, DC, decay, and render-cost observations before setting acceptance constants.

**Round 2 — material transition.** Add thermal and liquid state. Demonstrate that heating changes contact behavior and that cooling reverses it. Compare full state evolution against a frozen-state diagnostic using the same gesture.

**Round 3 — channels and restoration.** Add responding plates, liquid transfer, frozen bridges, stress-release events, and enclosure. Confirm delayed coupling and bounded restoration. Tune ordinary musical regions before extreme scrape or fracture patches.

**Round 4 — HOLD and delivery.** Add repeating material cycles, seam handling, patches, presets, kit routing, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm current repository instructions before treating this as the complete gate.

The owner’s sonic verdict is required. Metrics alone cannot establish whether the listener hears a changing material rather than a bowed tone with texture attached.

## 19. Audition and acceptance

Add `generateThawAudition` or the current equivalent. Render every voice, low/middle/high supported notes, and quiet/medium/strong gesture energy. Sweep every macro at 0, .25, .5, .75, and 1, holding companions at neutral. Supply raw and matched-loudness comparisons.

Interaction grids: CONTACT × HEAT, HEAT × FREEZE, CHANNELS × FREEZE, THICKNESS × HEAT, and CONTACT × THICKNESS. Include nearly dry contact, maximum wetness, rapid cooling, all-high extremes, high-register scrape, stopped carriage, long passive tails, and difficult HOLD cycles.

| Requirement | Evidence |
|---|---|
| Identity | The gesture audibly progresses from brittle engagement into a different friction/material state |
| Phase causality | Heat changes liquid state; liquid state changes contact and loading; cooling reverses the path |
| Pitch | Moderate patches retain requested root; calibrate the proposed ±10-cent target using analysis and listening |
| Channels | Disabling channel transfer removes delayed loading/coupling behavior |
| Stress events | Releases follow phase/constraint state; no arbitrary freeze crackle on a dry idle plate |
| Energy | With powered contact off, passive acoustic energy decays; stress releases have a finite budget |
| Stability | Long extreme renders remain finite and bounded without final-limiter dependence |
| Macros | Every normal voice demonstrates useful audible and measurable macro activity |
| Determinism | Identical inputs produce sample-identical output |
| Serialization/routing | Patch round-trip and current pitched-classifier guards pass |
| Loops | Representative HOLD cases pass seam metric and material-cycle listening checks |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost requirements pass |

Do not count silence, unpitched scrape, or a static generic pad as a successful transformation merely because the output is finite. The same plate must audibly carry both the attack and the evolved tone.

## 20. Deferred work and engineer decisions

Defer actual three-dimensional melting, structural destruction, free fluid simulation, environmental field recordings, stereo propagation, persistent state across exported pads, and physical refrigeration or runner hardware.

Before implementation, confirm note range, duration limits, velocity behavior, HOLD sample format, current patch/render APIs, and naming constraints. During probes, select the contact law, transition mapping, thermal compression, channel loss model, stress threshold, and root compensation. All numeric values above remain proposals until those rounds establish workable behavior.

## 21. Sonic north star

The first contact should sound cold and definite. As the runner moves, the same plate should soften into a wavering singing tone. Its neighbors should answer through a changing network of wet and frozen connections. Withdrawal should leave a finer, more brittle resonance as the imaginary material restores itself.

Thaw succeeds when playing changes the material, and the changed material changes how the instrument continues to sound.
