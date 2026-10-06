# Circuit — engine engineering specification

Version 0.1 • 3 October 2026, America/Chicago • Working name; release naming checks pending

## 1. Summary

Circuit is a pitched synthesis engine for SnipSnap imagined as a moving ensemble in a desert canyon. Three didgeridoo-inspired breath tubes remain near the center. A group of players from an invented canyon culture circles them, playing hollow stone rattles, forked wooden clappers, clay vessel drums, and periodic grunts and “uh-huh” vocal gestures.

The circle can expand or contract. Walking speed and playing speed are independent. Changing formation changes acoustic paths and echo arrival times. The ensemble follows an invented performance rule: leave a gap for the canyon to answer, then respond to selected returning accents.

The signature is **rooted breath trio → independently moving rhythm → changing canyon arrivals → bounded vocal and instrumental replies**. The source instruments, geometry, and event behavior interact; the result is not a drone with a generic reverb and percussion loop attached.

This is a generated engineering proposal. Acoustic mappings, instrument designs, patterns, and vocal behavior are invented or simplified for musical use. They do not claim authenticity to a real culture, language, ceremony, or performance tradition. No repository implementation or physical ensemble was inspected. Numeric values are hypotheses requiring probes and listening.

## 2. Scope and platform baseline

Follow the supplied SnipSnap architecture and the edited Gyre specification’s constraints: deterministic offline mono rendering, macros, patch round-trip, presets, pitched kit routing, shared loudness processing, audition generation, and optional seamless held loops.

Expected output is 44.1 kHz through the established 4× internal render and band-limited decimation infrastructure. Confirm current APIs, utilities, duration limits, and velocity semantics before implementation. No external sample assets are required.

V1 renders one requested note as a finite ensemble gesture or held region. All pitched sources derive from that root; there is no arbitrary chord progression. Each render begins with fresh deterministic geometry, performer state, and canyon buffers. Independently exported pads do not share ensemble memory. A persistent phrase harness is diagnostic work, not a public API extension.

The imagined culture is defined through its invented instruments and echo-response behavior. Do not assign real ethnic identifiers or fabricated historical claims in product text. The didgeridoo is the user’s stated sonic reference; the DSP model uses abstract lip-driven tubes rather than promising literal emulation.

## 3. Sonic goals and non-goals

Useful sounds include deep breath drones, voiced growls, pulsed tube articulations, dry interlocking percussion, paired wood knocks, rounded clay responses, chesty vocal punctuation, spacious delayed conversation, and dense orbiting ensemble textures.

Moderate patches should remain playable as pitched MPC material. The central root stays identifiable even with percussion and vocal gestures. Extremes can create rhythmic overlap and sideband-like tube complexity without runaway response events or broadband masking.

Circuit is not a sampled ethnographic ensemble, a speech synthesizer, a literal canyon simulator, a stereo rotary effect, or a free-running autonomous composition system. Dry sources must be useful before rack effects, and geometry must influence their paths before the final sum.

## 4. V1 component budget

| Component | Initial count | Function |
|---|---:|---|
| Central breath tubes | 3 | Root drone, pulsed articulation, upper/voiced texture |
| Moving players | 4 | Stone rattle, forked clapper, clay drum, vocal gestures |
| Path families | Direct plus 2 primary reflected paths per source | Geometry-dependent travel and filtering |
| Shared canyon network | 4–6 damped delay branches | Later reflection field |
| Response detectors | Per-player envelope and event state | Selected arrival cues and finite replies |

Use an abstract asymmetrical canyon and a small source network. No mesh-based wave solver, crowd simulation, full speech model, or many sampled performers are needed.

## 5. Central trio

The three tube voices share the requested root and a performance gesture, with independent bounded breath phases and articulation. They are not three copies of the same oscillator.

**Anchor:** dominant root with stable lip-driven resonance and slow pressure movement.

**Pulse:** root or upper octave with tongued/breath interruptions and a complementary accent pattern.

**Voice:** root-related upper resonance with throat modulation, growl, and occasional pitched overblow-like color. Keep ordinary operation in a defined register.

Use a stable nonlinear lip/pressure source feeding a tuned lossy waveguide or resonator. BREATH changes pressure, source stiffness, tract coloration, articulation strength, and relative contribution of the trio. The requested pitch is calibrated after feedback and loss are enabled.

Voiced growl may use a subordinate band-limited throat source interacting with tube excitation or source pressure. It must not become an unrelated lead voice. Limit roughness and sidebands at high pitches through oversampling and appropriate source band-limiting.

The trio exchanges bounded acoustic influence through a shared local field: a small portion of neighboring tube radiation alters excitation pressure or tract loading. Separate this from the canyon return. Demonstrate that coupling changes articulation or partials rather than merely increasing amplitude. With external breath off, the passive resonators decay.

Continuous breath is explicit performer energy. For one-shots, the breath envelope ends; HOLD sustains it. A “circular breathing” impression comes from smooth overlapping pressure gestures, not an unbounded hidden source after release.

## 6. Invented surrounding instruments

| Instrument | Proposed synthesis | Audible role |
|---|---|---|
| Hollow stone rattle | Finite collision impulses feeding damped mineral/cavity modes | Dry granular accents with identifiable individual shakes |
| Forked wooden clapper | Paired impulses into two differently tuned woody modal arms | Two-part knock whose reflections become answering rhythm |
| Clay vessel drum | Damped pitched membrane/cavity model with rounded contact | Root-related low-mid punctuation beneath the tubes |
| Breath voice | Band-limited glottal source, filtered air, and moving tract resonances | Grunts and two-part “uh-huh” gestures |

All percussion is generated from finite events. No continuous noise bed substitutes for a rattle. Clapper arms share a mounting response. Clay percussion retains tonal resonance without overpowering the drone or resembling an independent kick.

Tune prominent percussion modes to the root or simple upper ratios. Rattle texture may be noisy, but keep it subordinate to pitched material. Instrument character and mix weights are voice configuration, not additional macros in V1.

Footsteps are optional quiet gesture-linked contacts, not a required seventh source family. Add them only if auditions demonstrate a useful connection to movement without muddying the engine.

## 7. Vocal gestures

Grunts use short chesty voiced envelopes with restrained pitch bends. “Uh-huh” uses a two-part envelope and changing tract shape; intelligibility is approximate and need not match a real speaker or language. These are invented coordination gestures, not representations of ceremonial calls.

Vocal events mark selected rhythmic accents or respond to a returned tube/clapper cue. Keep them intermittent. Voice identity, timing offsets, breath roughness, and pitch deviations derive from deterministic configuration and seed.

The root or its upper octave anchors ordinary vocal pitches. Do not introduce spontaneous words or melodies. Record scheduled gesture type and trigger reason in diagnostics so source behavior is reviewable.

## 8. Circle geometry and independent motion

Give each moving player a fixed angular offset around the circle. Angular position advances from a movement clock:

`theta[i] = initialOffset[i] + orbitPhase`

Radius comes from DIAMETER. Use a smooth bounded radius mapping and optional small deterministic trajectory deviations. V1 does not require changing radius during the sample; patches with fixed diameter already change the formation. A future host automation path can interpolate it.

Playing events follow a separate clock controlled by PACE. Moving faster must not automatically increase the number of strikes. Conversely, faster playing must not make the players walk faster.

The observer is slightly off-center, and the canyon is asymmetrical. A centered listener in a perfectly symmetric environment would hear little direct-distance change from a constant-radius orbit. The off-center observer and unequal reflected paths provide genuine mono motion cues without stereo panning.

Movement changes distance, source facing, wall visibility, arrival delay, and spectral loss. Do not represent it only through final gain modulation. Doppler is optional and restrained; calibrated ordinary settings should not produce large unwanted pitch bends.

## 9. Playing clock and ensemble patterns

PACE controls event rate, phrase gaps, and accent density. Start with a gesture-rate range around 0.5–6 events per second per active pattern, with lower-rate instruments occupying subsets of that grid. These are proposed musical limits, not host-tempo claims.

Use short deterministic patterns with complementary roles: the rattle subdivides, the clapper leaves paired spaces, the vessel drum marks selected accents, and the voice answers sparingly. Small seed-derived timing and strength differences can avoid mechanical uniformity. Do not randomize the structure anew at every sample.

PACE does not change the requested tube fundamental. It may alter tonguing cadence and breath accents while preserving the drone’s pitch. Event density must be bounded, especially when canyon-triggered responses are enabled.

Low PACE should remain audible in short renders: include an initial ensemble accent and at least one useful later gesture where duration permits. Do not leave the first substantial part of the knob inactive solely because its period exceeds the sample length.

## 10. Canyon propagation

Compute direct and primary reflected path lengths from the source positions, fixed observer, and simplified canyon walls. Use fractional delays and path-dependent attenuation/filtering. A reflected arrival should move in time as the corresponding player moves.

CANYON changes wall spacing/shape, reflection losses, dispersion, and source-return influence. DIAMETER changes source geometry within that canyon. Maintain valid layouts throughout the macro range: the player circle cannot cross a wall or the observer singularity. Larger DIAMETER may also select a larger compatible canyon footprint, but keep CANYON’s independent audible effect.

Use a small stable network for later returns. Reflection feedback must be below the energy-generating region. Avoid a generic reverb as the sole implementation. Early paths should remain identifiable enough to cue response behavior.

Include separate observer paths and performer-listening paths. The player responds to a return reaching its own location, not to the final mono output. This distinction is necessary when geometry changes.

Smooth moving delay updates. Physical delay motion may produce Doppler; do not add a second pitch shifter on top. If ordinary motion is too dramatic, reduce the musical geometry/speed mapping or use a documented timbre-focused path approximation. Do not fabricate extreme walking velocities to make the control audible.

## 11. Echo-response behavior

The invented ensemble custom is to leave space for the canyon to answer. Each player has a scheduled base pattern, a bounded response opportunity, and a refractory timer. Selected returning accent envelopes may advance, delay, or insert a response within the next allowed slot.

Extract cues from designated path envelopes or tagged events, not every waveform peak. Distinguish a reflected tube accent from steady drone energy. Continuous sustain alone must not trigger infinitely many replies.

Responses have finite performer energy; canyon reflections cue them rather than supplying their whole energy. In one-shot mode, limit total added responses and cumulative reply energy. A starting allowance of approximately 4–12 extra events is a probe target. Keep response generation depth bounded and avoid a source responding immediately to its own outgoing signal.

At low PACE, gaps permit clear answers. At high PACE, incoming echoes overlap scheduled gestures; the model may skip a reply rather than continually add events. This creates a musical change in conversation instead of uncontrolled density growth.

CANYON influences response windows and the importance of returned cues, in addition to acoustic geometry. Turning it down leaves a useful dry ensemble. Disabling behavioral response in diagnostics must preserve ordinary canyon audio so its contribution can be compared separately.

## 12. Macros

Expose five timbral/behavioral macros plus HOLD. Diameter, movement speed, and playing speed remain independent. Values and neutral points are proposed; confirm current MacroSpec semantics.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| BREATH | .55 | .45 | Soft stable trio → stronger pressure, pulse, tract color, and voiced growl |
| DIAMETER | .45 | .40 | Close formation → wider circle and more separated paths |
| ORBIT | .30 | .00 | Stationary ensemble → faster movement through acoustic geometry |
| PACE | .40 | .35 | Sparse playing and reply gaps → rapid interlocking gestures |
| CANYON | .55 | .40 | Restrained local returns → pronounced wall response and echo conversation |
| HOLD | .00 | .00 | Finite ensemble phrase → seamless recurring breath and procession |

ORBIT=0 is a genuine stationary state. Low nonzero values also change facing/contact emphasis sufficiently to be audible within ordinary sample duration; do not rely solely on completing a full revolution. BREATH influences central source mechanics before the spatial path. Every normal voice must expose useful changes across all five macros.

Required interactions: DIAMETER × ORBIT changes arrival variation; DIAMETER × CANYON changes reflections and listening cues; PACE × CANYON changes available answer gaps; ORBIT × PACE changes which accents occur at which locations; BREATH × CANYON changes cue strength and returning tube influence.

## 13. Voices and initial defaults

Proposed enum: `CircuitVoice { ROOT, PROCESSION, ANSWER, VOICED, EXPANSE, CONFLUENCE }`.

| Voice | Character | BREATH | DIAMETER | ORBIT | PACE | CANYON |
|---|---|---:|---:|---:|---:|---:|
| ROOT | Clear central trio with sparse moving accents | .55 | .30 | .15 | .20 | .35 |
| PROCESSION | Interlocking playing with clear movement | .50 | .45 | .55 | .55 | .45 |
| ANSWER | Gaps and distinct canyon-cued replies | .45 | .55 | .25 | .30 | .75 |
| VOICED | Growls and periodic grunt/uh-huh gestures | .75 | .40 | .30 | .40 | .50 |
| EXPANSE | Wide, slow formation and long returns | .50 | .85 | .20 | .25 | .85 |
| CONFLUENCE | Rapid playing against faster movement | .70 | .55 | .75 | .80 | .65 |

Voices bias tube losses/tracts, ensemble weights, patterns, wall profile, and response sensitivity. They remain one engine. All source families retain some audible role; do not disable macros through zero mix weights.

Initial preset concepts: Three Breaths, Close Formation, Slow Procession, Wide Circle, Paired Wood, Clay Answer, Chest Gesture, Canyon Gap, Moving Accents, Returning Drone, Fast Confluence, and Held Circuit. Release naming checks remain necessary.

## 14. Finite phrases and HOLD

One-shot mode plays a finite breath/ensemble phrase and then allows reflections and resonators to decay. Start near 3–8 seconds subject to current host limits. The finite phrase includes a clear onset, internal variation, and release; it is not an arbitrary cut from endless performance.

HOLD maintains performer energy with periodic breath, playing, and response behavior. For a seamless loop, orbit and playing clocks must each complete compatible cycles. Independent controls remain independent, but HOLD may quantize them to nearby compatible rates. Document actual rates in diagnostics.

Use the longest supported useful loop when a slow orbit cannot fit. Do not silently round every low ORBIT setting to stationary. If a full slow revolution is impossible, use a documented compatible partial-motion approximation or nearest nonzero rate, then audition the change.

Converge tube states, canyon buffers, event phases, response timers, and pending gestures through bounded preroll. Periodic source clocks do not guarantee periodic cue-driven responses. Constrain seeded variations and response scheduling to a repeatable settled cycle in HOLD mode.

Select a stable region and apply project-standard wrap handling where necessary. Check that crossfading does not duplicate clapper pairs or vocal syllables. Target `Keys.seamError < 1e-3` if still the current contract, together with listening for interrupted phrases, timing jumps, and pumping.

Confirm attack-plus-loop support. If the host returns only a loop buffer, deliver the settled procession and document omission of the initiating gesture. Do not expand the public API silently.

## 15. Numerical and deterministic requirements

Bound tube feedback, breath input, local coupling, canyon feedback, event density, reply count, and vocal roughness. With performer input off, all passive paths decay. Detect non-finite states at their origin and recover deterministically; final limiting is not a stability strategy.

Use oversampling for nonlinear lip/throat sources and bright contacts, smooth moving path delays, and band-limit before decimation. Prevent DC accumulation, denormals, discontinuous envelopes, and high-register aliasing. Calibrate tuning after source feedback and tract loading.

All texture, humanizing offsets, patterns, and vocal variations derive from the existing seed mechanism, such as `Dsp.seedFor`. Identical voice, note, macros, velocity, patch, and seed context must produce identical audio and event order under the current render contract.

Apply shared melodic loudness targeting after synthesis. Compare raw and matched-loudness clips so normalization does not hide weak motion or elevate a tiny vocal gesture over the trio. Record render time and memory before optimization.

Retain diagnostics for isolated tubes, each ensemble instrument, vocal gestures, source positions, direct/reflected paths, performer-listening cues, scheduled/reply events, and actual HOLD rates. These are engineering surfaces, not additional product knobs.

## 16. Integration

Expected additions: `Circuit.kt`, `CircuitVoice`, `CircuitPatch`, `CircuitPresets`, macro and renderer registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, tests, and roadmap. Adapt names to the actual source layout.

Serialize voice and all macros through existing JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain handling. Dry sources and early paths establish core identity before rack effects.

Circuit is pitched melodic ensemble material. Standard auditions must satisfy existing guards against KICK, SNARE, CLAP, HAT, and TOM classification. Keep the central pitched trio present beneath percussion. Verify full-mix routing and classifier behavior; do not strip out the invented instruments solely to make a guard pass. Resolve any incompatible host policy explicitly.

## 17. Build rounds

**Round 1 — central breath.** Build three distinct stable tube sources and bounded local coupling. Probe pitch, level, DC, aliasing, decay, and cost before fixing numerical test bounds. Establish a useful dry root.

**Round 2 — invented ensemble.** Add the four source roles, independent playing clock, finite gesture pattern, and synthesized vocals. Verify that PACE changes rhythm without transposing the root.

**Round 3 — formation and canyon.** Add off-center mono observer, asymmetric paths, independent orbit, performer-listening returns, and bounded response behavior. Demonstrate a useful audible ORBIT sweep with PACE fixed and vice versa.

**Round 4 — HOLD and delivery.** Add compatible clocks, state convergence, seam handling, patches, presets, kit routing, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm repository instructions before treating this as the complete gate.

The owner’s listening verdict remains required. Metrics cannot establish whether the ensemble feels coordinated with its environment.

## 18. Audition and acceptance

Add `generateCircuitAudition` or the current equivalent. Render every voice at defaults, low/middle/high supported notes, and quiet/medium/strong event energy. Sweep macros at 0, .25, .5, .75, and 1 with neutral companions. Supply raw and matched-loudness comparisons.

Required grids: DIAMETER × ORBIT, DIAMETER × CANYON, PACE × CANYON, ORBIT × PACE, and BREATH × CANYON. Include stationary-fast-playing, fast-moving-sparse-playing, wide/close formations, no behavioral replies, isolated vowels, all-high extremes, and difficult HOLD clocks.

| Requirement | Acceptance evidence |
|---|---|
| Trio identity | Three distinct breath roles share an identifiable root |
| Independence | Changing ORBIT leaves base playing-event count unchanged; changing PACE leaves movement rate unchanged in one-shot mode |
| Geometry | Distances and wall paths predict arrival timing; movement is audible in mono without final-output tremolo |
| Response | Replies follow selected performer-listening cues and remain bounded |
| Vocals | Grunts and two-part gestures are intermittent, synthesized, and integrated into the rhythm |
| Pitch | Moderate central tones retain requested root; calibrate proposed ±10-cent stable-root target with analysis and listening |
| Passive stability | Tubes and canyon decay with performer input off |
| Active stability | Extreme phrases terminate; long HOLD renders stay bounded |
| Macro activity | Every normal voice exposes useful audible and measurable changes |
| Determinism | Identical render inputs produce sample-identical audio and event order |
| Serialization/routing | Patch round-trip and current pitched guards pass or a concrete host-policy conflict is resolved |
| Loops | Representative HOLD cases pass seam metric and phrase-timing review |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost limits pass |

## 19. Deferred scope and open decisions

Defer a real-culture instrument roster, historical performance claims, sampled chants, full speech, unrestricted autonomous composition, persistent cross-pad state, stereo/ambisonic output, detailed walking biomechanics, and explicit tempo synchronization unless already supported by the host.

Before implementation, confirm pitch range, duration limits, velocity semantics, HOLD format, patch/render APIs, routing policy for pitched ensembles with percussion, and naming constraints. During probes, establish tube register limits, instrument mix, geometric scaling, mono motion audibility, response windows, and compatible loop-rate mappings. All numeric proposals remain subject to calibration.

## 20. Sonic north star

Three breaths should stay rooted in the center while a surrounding rhythm moves independently. Expanding the formation should change how its gestures arrive and how the canyon answers. Increasing playing speed should fill those answer gaps; increasing walking speed should place the same gestures along changing acoustic paths.

Circuit succeeds when formation, motion, playing, and echoes sound like one coordinated imaginary ensemble.
