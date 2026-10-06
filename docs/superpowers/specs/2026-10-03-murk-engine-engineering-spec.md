# Murk — engine engineering specification

Version 0.1 • 3 October 2026, America/Chicago • Working name; release naming checks pending

## 1. Summary

Murk is a physically inspired pitched synthesis engine for SnipSnap. It imagines a row of tuned tonal-wood trees in an atmosphere much thicker than Earth air. Entities strike the trunks with bats or axes. Each impact produces a dense pitched clunk or sharp woody thwack, compresses the surrounding fog, and sends a pulsating pressure front through the grove. Owls inhabiting the trees become agitated by direct impacts and arriving pressure fronts, then answer with synthesized calls.

The defining gesture is **wooden strike → traveling atmospheric pulse → responding trunk → owl call → smaller atmospheric answer**. The atmosphere participates in excitation and loading. The owls respond to events in the grove rather than appearing as an unrelated wildlife layer.

This document is a generated design for an imaginary acoustic ecosystem. The fog’s propagation rules and owl behavior are musical inventions, not a validated model of dense gas, fog, tree acoustics, or animal behavior. Proposed equations, timings, defaults, and counts require implementation probes and listening. No repository implementation was inspected.

## 2. Goals and scope

Murk should span playable woody notes, dense hollow clunks, sharp tonal thwacks, slow pressure pulses, softened returning impacts, breathy hoots, agitated calls, and finite call-and-response textures. Extreme patches can become uneven conversations among wood, atmosphere, and animals, while ordinary patches preserve a recognizable requested pitch.

Follow the supplied SnipSnap baseline and the edited Gyre specification’s engine constraints: deterministic offline mono samples, macro-driven rendering, patch serialization, presets, pitched kit integration, shared loudness processing, auditions, and optional seamless held loops.

Expected audio path is final 44.1 kHz output using the established 4× internal rendering and band-limited decimation infrastructure. Confirm current repository interfaces before implementation. No external samples are required.

Each render initializes a complete grove at rest. Independently exported pads do not share fog state or owl agitation. Persistent phrase behavior may be explored in a diagnostic harness; it is not part of the public V1 contract. V1 is mono and does not simulate listener movement or stereo forest placement.

## 3. Non-goals

Murk is not a wood hit with delay and bird samples attached, a general environmental sound generator, an accurate owl-species emulator, a realistic tree-felling simulation, or a conventional reverb preset. The fog must affect trunk loading and excitation; calls must depend on modeled agitation and stimulus arrival.

The axe gesture changes contact mechanics and may introduce temporary modal disturbance. It does not progressively destroy the tree in V1. The imaginary trees recover between independently rendered gestures.

## 4. Initial component budget

| Component | Initial count | Purpose |
|---|---:|---|
| Tuned trees | 4 in a row | Dominant struck tree and three responding trees |
| Trunk modes | 4–6 per tree | Pitched wood vibration and upper woody modes |
| Cavity resonators | 1–2 per tree | Hollow trunk reinforcement |
| Fog links | 3 bidirectional neighbor links | Travel, attenuation, reflection, and re-excitation |
| Atmospheric loading states | 1 per tree | Slow compression and damping response |
| Owl behavioral states | 1 per tree | Agitation, latency, refractory time, and finite call budget |
| Owl audio voices | Maximum 4 concurrently | Synthesized hoot, bark, or controlled screech |

Use a reduced network, not a full three-dimensional acoustic simulation. Local trunk modes, delayed traveling signals, and bounded slow states are sufficient for the first complete engine.

## 5. Strike and entity interpretation

One note event starts a finite performer gesture against the dominant tree. The bat-to-axe STRIKE macro continuously changes the contact law: contact width, release sharpness, force envelope, upper-mode excitation, and a brief surface texture.

Bat-like contact has a broader pulse and emphasizes the trunk’s dense body. Axe-like contact is shorter, brighter, and concentrates excitation into upper wood modes. It may impose a short recoverable change in contact-region stiffness or loss. Smooth this change; do not retune a ringing resonator abruptly.

The impact is a band-limited finite force pulse projected into trunk modes. Seeded contact texture remains brief and subordinate to pitched wood. Avoid a snare-like noise burst or unpitched chopping Foley as the main source.

If the host provides velocity, use it to scale event energy and fog compression while STRIKE controls tool/contact character. Do not invent a second public velocity API. A stronger event should travel farther or provoke more responses, not merely increase final output gain.

Entities are gesture sources, not autonomous background performers. One-shot mode uses one primary strike by default. Internal repeated strikes belong only to explicit held behavior or documented presets supported by the current host contract.

## 6. Tuned wood and cavities

Tune the dominant trunk’s principal perceived mode to the requested note. Start with upper modal ratios near 1, 2.2, 3.7, and 5.9, then calibrate the woody identity. These are proposed timbral ratios, not measurements of a wood species.

Responding tree fundamentals initially use root-related ratios 2, 3, and 4, at lower levels. Voices may use selected simple intervals such as 3/2 if the requested root remains clear. Do not generate arbitrary chords or let an unintended lower resonance dominate.

TRUNK changes correlated material and geometry properties: effective mass, modal spacing, damping, cavity volume, wall compliance, and contact responsiveness. Compensate the dominant root so the macro changes the tree rather than transposing the note.

Cavity resonators receive modal energy and provide bounded loading back into the trunk. The direct wood tone must remain useful with minimal fog and minimal owl activity. Tree differences in damping, cavity tuning, and contact pickup weights are deterministic.

## 7. Imaginary atmosphere: propagation and loading

Separate the fast acoustic traveling signal from a slow compression/arrival envelope. The acoustic path transports audible wood and call energy through delayed, damped links. The envelope tracks pressure-front strength for loading and behavioral response. It is not itself an audio-rate sine oscillator.

FOG changes acoustic impedance/loading, travel time, attenuation, dispersion, and compression relaxation as a coordinated material parameter. GROVE changes distances, neighbor transfer, and boundary return behavior. Do not use one knob solely for delay time and the other solely for feedback gain.

In this imagined medium, thicker fog can produce slower, broader arrivals and stronger local loading. These are chosen musical rules; greater real-world density alone does not establish those acoustic consequences.

Start with adjacent travel times around 60–350 ms and broaden them at extreme FOG/GROVE settings within sample-duration limits. Use smooth fractional delays or the project equivalent. Band-dependent loss softens later arrivals without muffling the direct attack into an underwater sound.

The fog loads the original tree as well as its neighbors. Increasing FOG should change the initial wood response and tail even before a delayed pulse arrives. Apply loading through damping and small compensated stiffness changes, not only post-output filtering.

Returning pulses may re-excite trunks through the coupling ports. Prefer a passive scattering or demonstrably stable delay network. Attenuation applies at each passage; avoid independently feeding full-strength copies to every destination. Verify passive ringdown with all performers and owls silent.

## 8. Pulsation and finite propagation

Pulsation emerges from travel and reflection through the finite grove. A hard strike creates a larger initial front; softened returns can create additional audible swells and weak trunk attacks. Their timing follows distances and the link model, not a free-running tremolo LFO.

Use bounded nonlinear compression only where it represents the imagined material. Limit local pressure state and allow it to relax. Nonlinear paths must be evaluated on an appropriate oversampled clock or tested for aliasing.

Record arrival time, destination, and strength in diagnostics. Slow envelopes used for owl behavior should preserve the order of arrivals and avoid treating every acoustic oscillation as a separate stimulus.

One-shot acoustic circulation must decay below the established tail threshold. Feedback cannot remain at unity merely to create a long atmospheric bed. If a long patch needs replenishment, it requires an explicit powered source in HOLD mode.

## 9. Owl agitation and response

Each owl has agitation, a pending-response timer, refractory state, and remaining response budget. Direct contact events and arriving pressure-front envelopes increase agitation. Agitation decays toward rest with silence.

Conceptual update:

`agitationRate = directStimulus + arrivingFrontStimulus + arrivingCallStimulus − relaxation`

AGITATION changes sensitivity, response latency, call intensity, and articulation range. Separate detection from sound production: a stimulus first changes state, then schedules a bounded response. Use hysteresis and refractory times to prevent rapid repeated triggers at a threshold.

Initial response behavior:

| State | Musical response |
|---|---|
| Quiet | Occasional soft hoot after a sufficient stimulus |
| Alert | Shorter latency, firmer hoot or paired phrase |
| Agitated | Brief bark, faster call envelope, more throat noise |
| Startled | Short controlled screech followed by longer silence |

Transitions are continuous where practical; a few articulation thresholds are acceptable when they represent a behavioral event. Avoid a random selection of unrelated call samples.

Low AGITATION produces sparse quiet replies. At zero, calls may be fully disabled if consistent with product convention; the rest of the instrument remains pitched. Ensure a useful low range rather than a large dead region across every voice.

## 10. Synthesized owl voice

Use a breath-driven nonlinear source feeding a small throat/cavity resonator. A stable band-limited periodic source with pressure-dependent harmonics and filtered breath is an acceptable first implementation; literal avian vocal physiology is not required.

Hoots use rounded envelopes, restrained harmonics, and small shaped pitch bends. Barks shorten the envelope and increase harmonic density. Screeches increase roughness and noise while retaining a root-related tonal anchor. Do not make agitated calls unlimited broadband bursts.

Derive each owl’s tonal anchor from its tree and the requested root. Defaults should settle on the root or upper octaves; any alternate intervals are explicit voice configuration. Calls may bend toward or away from that anchor but must not obscure ordinary patches’ perceived pitch.

Tiny voice differences and breath roughness derive from the project seed. Keep individual calls identifiable, with distinct onset and offset, rather than converting all owls into a continuous pad.

Owls provide their own biological energy in the imaginary model. A pressure pulse triggers a call; it does not acoustically supply all of that call’s energy. Represent this distinction through a finite response budget rather than attempting to conserve passive acoustic energy across animal responses.

## 11. Calls can disturb the fog

Inject a quieter portion of owl audio and its event envelope into the atmosphere network. This allows one owl’s call to reach another tree and contribute to that owl’s agitation.

Prevent an endless self-exciting ecosystem with explicit limits. Each one-shot render has a finite call count and cumulative vocal-energy budget. A reply consumes budget. Refractory periods, stimulus attenuation, agitation decay, and a per-call propagation-generation limit provide additional safeguards. Choose initial caps by audition; a starting maximum of approximately 8–16 calls across the entire render is a proposal, not a production constant.

Do not count an owl’s own outgoing call as a new local stimulus before it travels. Returning self-calls may have reduced behavioral influence. Tag originating events in diagnostics so duplicate stimulus accounting can be detected.

The passive fog network remains stable independently of the active animals. Calls inject bounded energy into it, then that energy decays normally. Muting owl audio alone is not equivalent to disabling owl-generated fog excitation; diagnostic controls must distinguish those cases.

## 12. Macro set

Five timbral controls plus HOLD follow the existing normalized macro convention. Confirm MacroSpec neutral semantics. All values are proposed starting points.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| STRIKE | .40 | .35 | Broad bat-like clunk → sharp axe-like thwack |
| TRUNK | .50 | .50 | Tight lighter tonal wood → dense hollow resonating tree |
| FOG | .50 | .35 | Light loading and clear propagation → broad, heavy pressure fronts |
| AGITATION | .35 | .20 | Quiet sparse hoots → barks, overlaps, and restrained screeches |
| GROVE | .45 | .40 | Compact local response → spaced arrivals and extended conversation |
| HOLD | .00 | .00 | Finite strike and settling ecosystem → seamless recurring grove activity |

Required interactions: STRIKE × FOG changes pressure-front strength and attack loading; TRUNK × FOG changes resonant transfer; FOG × GROVE changes arrival spacing and pulse shape; GROVE × AGITATION changes who answers and when; STRIKE × AGITATION changes vocal articulation and latency.

Every normal voice exposes useful audible changes across all five timbral macros. GROVE should alter local coupling as well as distant timing so short patches still respond. AGITATION changes call character as well as threshold, preventing the whole lower range from producing identical audio.

## 13. Voices and proposed defaults

Proposed enum: `MurkVoice { CLUNK, THWACK, FRONT, HOOT, GROVE, ALARM }`.

| Voice | Character | STRIKE | TRUNK | FOG | AGITATION | GROVE |
|---|---|---:|---:|---:|---:|---:|
| CLUNK | Dense melodic bat strike with distant reply | .15 | .65 | .35 | .20 | .30 |
| THWACK | Sharp tonal wood and articulate returns | .85 | .45 | .40 | .30 | .40 |
| FRONT | Strong fog loading and traveling pulses | .45 | .60 | .85 | .25 | .65 |
| HOOT | Softer wood with clear vocal response | .25 | .50 | .45 | .50 | .50 |
| GROVE | Staggered trunk/animal conversation | .40 | .55 | .60 | .60 | .80 |
| ALARM | Hard gesture and controlled startled calls | .80 | .45 | .65 | .85 | .60 |

Voice configuration can change wood modal ratios, pulse loss profile, cavity response, owl throat shape, and behavioral timing. All voices use the same architecture. Vocal levels may differ, but no voice disables a timbral subsystem entirely.

Initial preset concepts: Hollow Bat, Tonal Axe, First Front, Close Trees, Distant Hoot, Heavy Atmosphere, Wary Silence, Answering Grove, Soft Bark, Returning Pulse, Deep Trunk, and Settling Forest. Names require release checks.

## 14. Duration and HOLD

One-shot mode uses one primary strike, bounded acoustic circulation, and a finite vocal response budget. Start with 2–6 seconds for direct patches and longer grove responses only within actual host limits. Preserve late calls where audible, but stop after excitation ends and acoustic energy falls below the established threshold. Use a maximum duration even if agitation has not reached exactly zero.

HOLD introduces explicit repeated performer gestures, using a deterministic periodic strike pattern. These are ongoing external energy input. The owl budget replenishes at a bounded rate in this mode, with refractory behavior retained. Do not sustain by setting fog feedback to unity.

Choose a sparse enough strike cycle that wood, fronts, and replies remain distinguishable. Dense ALARM patterns may exist as extreme presets, but are not the default loop behavior. Cycle timing is an internal voice/macro mapping; explicit host tempo synchronization is deferred unless already supported.

Quantize gesture cycles to the loop duration. Converge delay buffers, tree modes, compression, agitation, timers, budget replenishment, and pending calls with bounded preroll. Periodic strikes alone do not guarantee periodic owl behavior. Use periodic seeded variations or a deterministic repeating event schedule derived from the settled behavioral model where needed.

Select a stable cycle region, then use project-standard wrap handling or a short crossfade if required. Check that it does not duplicate calls or create double strikes. Target `Keys.seamError < 1e-3` if still the current contract, alongside listening for timing discontinuities and obvious repeated animal patterns.

Confirm attack-plus-loop support. If the host provides only a loop buffer, deliver the settled recurring grove and document that the initial isolated strike is excluded. Do not change the public sample API silently.

## 15. Numerical, deterministic, and output requirements

Use bounded modal coupling and a stable fog propagation network. Measure passive decay with owl and performer sources off. Bound nonlinear compression separately from vocal activity. Do not hide runaway feedback through final limiting.

Run bright contact, nonlinear vocal generation, and nonlinear atmospheric operations on the oversampled path where needed. Use smooth coefficient changes, fractional delays, band-limiting, and decimation through existing utilities. Avoid DC accumulation, denormals, aliased axe transients, and discontinuous call envelopes.

All structural variation, behavioral variation, breath, and contact noise derive from the existing seed mechanism, such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context must produce sample-identical output under the current render contract. No wall-clock randomness or unseeded response scheduling is allowed.

Apply shared melodic loudness targeting after synthesis. Compare raw and normalized clips so a tiny owl does not become artificially louder than the trunk or normalization conceal missing fog activity. Record render time and memory before optimization.

Diagnostics should expose direct trunks, re-excited trunks, atmosphere audio, pressure-front arrivals, owl audio, agitation, scheduled call events, call budgets, and vocal-generated fog input. Keep these out of the product control surface.

## 16. Repository integration

Expected additions: `Murk.kt`, `MurkVoice`, `MurkPatch`, `MurkPresets`, macro/renderer registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, meaningful tests, and roadmap update. Adapt identifiers to current organization.

Round-trip voice and all macros using existing JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain infrastructure. Dry rendering must reveal the three-part identity before effects.

Murk is a pitched melodic engine. Standard cases must avoid KICK, SNARE, CLAP, HAT, and TOM under current project guards. CLUNK needs sufficient tonal tail; THWACK must not become noise-only; ALARM calls must retain tonal anchors. Confirm actual routing behavior instead of inventing source interfaces.

## 17. Build rounds

**Round 1 — tonal tree.** Build bat-to-axe contact, one modal trunk, cavity, and initial fog loading. Establish useful pitched clunks and thwacks. Probe pitch, level, centroid, decay, DC, and render cost before fixing numeric acceptance constants.

**Round 2 — traveling front.** Add the reduced grove and passive propagation. Demonstrate distance-dependent arrivals, returning pulses, and re-excitation. Verify passive decay and separate slow response envelopes from acoustic oscillations.

**Round 3 — living response.** Add synthesized calls, agitation, refractory behavior, and finite budgets. Then add call-to-fog coupling. Demonstrate a bounded causal conversation rather than a wildlife sound layer. Tune moderate patches before alarm extremes.

**Round 4 — loops and delivery.** Add periodic performers, bounded vocal replenishment, loop convergence, patches, presets, kit routing, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm current repository instructions before treating that as the complete gate.

The owner’s listening verdict remains required for sonic acceptance. Numeric gates establish behavior and stability, not whether the grove feels like a coherent imaginary place.

## 18. Audition and acceptance

Add `generateMurkAudition` or the current equivalent. Include every voice, low/middle/high supported notes, quiet/medium/strong event energy, and macro sweeps at 0, .25, .5, .75, and 1 with neutral companions. Supply raw and matched-loudness comparisons.

Interaction grids: STRIKE × FOG, TRUNK × FOG, FOG × GROVE, GROVE × AGITATION, and STRIKE × AGITATION. Include all-high extremes, no owls, passive fog ringdown, isolated calls, long responses, high-register THWACK, low-register CLUNK, and difficult HOLD cases.

| Requirement | Evidence |
|---|---|
| Identity | Wood attack, atmospheric travel, and animal response are audibly distinct but connected |
| Propagation | Arrival events match link distances and rules; disabling links removes corresponding answers |
| Loading | FOG changes the original trunk response, not only its delayed copies |
| Owl causality | Calls follow stimuli and agitation; refractory times and budgets prevent endless triggering |
| Call feedback | Disabling owl-to-fog injection changes secondary responses while leaving direct calls intact |
| Pitch | Moderate patches retain requested root; calibrate the proposed ±10-cent direct-trunk target with analysis and listening |
| Passive stability | Atmosphere and trees decay with performers and owls off |
| Active stability | Extreme one-shots terminate; long HOLD renders remain bounded |
| Macros | Every normal voice demonstrates useful audible and measurable macro changes |
| Determinism | Identical render inputs produce sample-identical audio and event order |
| Patch/routing | JSON round-trip and current pitched-classifier guards pass |
| Loops | Representative HOLD clips pass seam metric and event-timing listening checks |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost limits pass |

Analyze direct trunk pitch separately from deliberately bending calls, then assess perceived pitch of the full mix. Do not loosen trunk tuning to accommodate an accidentally dominant owl or cavity resonance.

## 19. Deferred scope and open decisions

Defer persistent state across exported pads, independent notes on each tree, spatial/stereo simulation, species-accurate calls, literal atmospheric physics, progressive tree destruction, unrestricted autonomous performers, and sample-based environmental recordings.

Before implementation, confirm note range, velocity contract, duration limits, HOLD buffer format, current patch/render APIs, and naming restrictions. During probes, establish fog loss/scattering, trunk compensation, owl synthesis, stimulus thresholds, response budgets, and call-to-fog gain. Values above remain hypotheses until those rounds demonstrate useful behavior.

## 20. Sonic north star

A strike should sound dense and woody at its source. It should push something heavier than ordinary air through the grove, waking resonances farther away. An owl should answer because that disturbance reached its tree, and its smaller call should briefly disturb the same atmosphere before the forest settles.

Murk succeeds when the performer strikes one tree and hears a finite ecosystem respond.
