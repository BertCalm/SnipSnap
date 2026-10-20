> Supplied design handoff, retained verbatim below. The first finite implementation intentionally reduces its scope; see [NACRE.md](../../NACRE.md) for the implemented topology and deferred requirements. Numerical defaults remain listening hypotheses.

# Nacre Engine — Engineering Specification

## 1. Summary

**Nacre** is a sample-free pitched synthesis engine for SnipSnap: a mermaid playing an upper harp and a merman playing a bass harp, strumming in different octaves inside imaginary underwater venues.

The defining sound is a low strum opening beneath a high strum, their strings continuing to answer one another while water moves against, around, and through the venue. The direct plucks remain clear; aquatic character emerges through shared loading, sympathetic response, changing passages, and wavering returns.

The two performers must feel like a duet with stable differences in touch and timing. Venue selection changes the acoustic mechanism, not just a reverb preset.

This is a proposed engineering handoff, not completed DSP. Numerical mappings and defaults are prototype hypotheses except established project requirements. Nacre is a working name; availability has not been assessed.

## 2. Scope and Platform

Follow the supplied Gyre conventions: deterministic offline mono generation, 44.1 kHz output, standard 4× internal oversampling, five timbral macros plus HOLD, shared melodic leveling, serialized patches, presets, kit integration, and auditions. Confirm current repository interfaces before implementation; no repository source has been inspected for this handoff.

The merfolk, moving water, and venues are musical design metaphors. V1 does not require accurate submerged harp acoustics, fluid simulation, sampled instruments, environmental recordings, or stereo localization. No singing or vocal model is implied by the characters.

One MIDI note establishes the bass harp's primary reference f0; the corresponding upper harp reference is 2*f0. The standard instrument remains a single-note melodic generator. A configurable chord/voicing arrangement is an expanded feature and must not silently introduce arbitrary chord tones into ordinary patches.

## 3. Core Model

Use two independently excited string banks, separate harp bodies, sympathetic strings, a shared water-control field, a bounded coupling path, and four venue response models.

Per oversampled update:

1. Read scheduled plucks for the two performers.
2. Evaluate the shared current field at each harp and relevant venue region.
3. Derive bounded excitation, damping, and coupling coefficients.
4. Update strings and bodies using explicitly delayed shared returns.
5. Feed venue paths with distinct bass and upper-harp injection weights.
6. Update venue state and read direct plus returning sound.
7. DC-block, antialias, decimate, and apply shared leveling.

Current must influence sound generation, not merely final output volume. Keep diagnostic stems for each harp, sympathetic response, venue response, and shared return.

## 4. The Two Harps

Prototype four active strings per harp plus four sympathetic resonators per harp. Each active string is a waveguide or equivalent physically inspired plucked resonator. Reuse existing `Strings.Loop` infrastructure if its current interface supports required excitation and coupling.

For the default octave gesture, corresponding strings have a 2:1 upper-to-bass frequency relationship. Initial within-harp ratios can be 1, 2, 4, and 8, with progressively restrained high-string levels and appropriate damping. Fade or omit components approaching the output bandwidth ceiling rather than allowing aliasing.

These ratios are a synthetic single-note voicing, not a literal harp scale. Expanded chord mode may use explicit semitone/ratio arrays, with corresponding octave-separated voicings on the two harps. Serialize those arrays; expose chord mode separately from ordinary note rendering.

The bass harp favors rounded attacks, deeper body modes, and longer low-string decay. The upper harp favors clearer transients and fine ringing partials. Neither role should be defined only by pitch shifting the same completed sound.

Preserve a sufficiently strong f0-bearing bass string so the requested note remains useful even when the upper harp is more prominent. Assess octave ambiguity explicitly in pitch probes.

## 5. Pluck and TOUCH

Use finite deterministic excitation: shaped noise, initial string displacement, or the project's established pluck primitive. Vary excitation position, hardness, and body transfer by performer and string.

TOUCH moves from soft distinct plucks to fuller, brighter strums by increasing excitation effort and upper-string participation and changing the pluck-position spectrum. It may modestly change strum compression, but DUET remains responsible for the relationship between performers.

TOUCH = 0 must produce a quiet useful double-harp gesture, not silence. Keep both harp identities audible at moderate settings. Velocity changes initial excitation effort and bounded hardness in addition to any normal output-level handling.

No pluck may inject indefinite energy. At the end of a finite gesture, all active and sympathetic strings, bodies, and venue states must decay under their defined losses.

## 6. Strum Scheduler and DUET

Schedule each string pluck explicitly. Define a gesture origin, per-string delay, strum direction, performer offset, and a bounded deterministic timing variation. Stable performer tendencies persist across gestures; do not regenerate unrelated personalities every sample.

At low DUET, the two strums begin closely together and share direction with subtle differences. Middle values reveal an answering offset. High values increase separation and favor crossing directions, such as a rising bass strum beneath a descending upper strum.

Prototype within-harp string spacing is approximately 8–60 ms, and inter-performer offsets approximately 0–250 ms, tuned by voice. Use a continuous timing relationship. Strum-direction changes are discrete event-order changes: switch them between complete gestures with explicit profile thresholds, while keeping nearby settings perceptually continuous through timing/weight adjustment. Do not reverse an in-progress string sequence abruptly.

DUET must change articulation, overlap, and who leads rather than simply adding a delay to a completed harp sample. In finite V1, render one complete paired gesture; voices may include a small explicitly configured second answer. Bound event count and total gesture duration.

Log all scheduled events, performer IDs, string IDs, times, directions, and excitation strengths. HOLD repeats complete gestures according to Section 15.

## 7. Harp Bodies and Sympathetic Coupling

Give each harp a small distinct body-mode bank, initially three modes each. Body outputs drive their respective sympathetic banks, whose tuning uses root-related ratios and receives no direct note-on pluck.

The shared medium allows the bass body to weakly excite upper-harp sympathetic strings and vice versa. Cross-harp transfer should leave an audible relationship: muting the upper harp's direct plucks can still reveal a faint ringing response to the bass gesture.

BLOOM increases sympathetic excitation, selected decay times, and venue participation within explicit gain/stability limits. It must not act only as final reverb wetness. Keep a dry direct region at low BLOOM and distinct string resonances at high BLOOM.

Use an explicitly causal delayed connection for shared returns. Excitation of an unplucked string must enter its internal model, not merely add a filtered copy of the other harp at output.

## 8. Shared Current Field

Use a compact deterministic field with one main slow phase and a few related components, evaluated at fixed positions for the two harps and venue regions. This produces related motion with spatial phase differences rather than unrelated random LFOs.

Separate the field into three functions:

| Flow relationship | Internal action | Audible result |
|---|---|---|
| Against | Changes bounded body/venue loading and excitation transfer | Rounded surges and responsive surfaces |
| Around | Changes selected propagation pickup weights and restrained delayed returns | Sound seeming to travel and ripple |
| Through | Changes aperture/transmission weights between venue sections | Resonances revealed and concealed |

CURRENT controls field speed and depth using saturating curves. At 0, freeze the field at a stable alignment, retain the venue, and remove current-induced fluctuations. Nonzero low values must have audible effect over supported render lengths.

Suggested ordinary field rates are roughly .15–3 Hz, with secondary ripples up to approximately 8 Hz in extreme voices. These are musical control rates, not physical water-speed claims. Avoid a mandatory high-frequency water-noise layer.

## 9. Current Touching the Strings

Use the field to alter effective damping, body loading, and sympathetic transfer. Low-to-middle CURRENT should change how the plucked states evolve, rather than only adding chorus after the string output.

If small pitch/loading variation is included, keep the root deviations restrained, initially around ±2–8 cents in moderate voices. Upper modes can move differently within a voice's bounded spectrum. Do not apply the same global pitch LFO to both harps and call it shared-medium interaction.

Dynamic delay/tension changes can inject energy or detune waveguide loops. V1 should prefer stable damping and coupling changes inside the closed return network, with wavering propagation implemented in a feed-forward pickup branch. Any variable-delay string loading needs its own pitch and uniform stability validation before inclusion.

CURRENT must remain useful in every venue, but each venue translates it differently. Seaweed should reveal changing transmission; conch should expose shifting resonant passages; cave should emphasize returning paths; coral should animate small local cavity responses.

## 10. Four Venue Mechanisms

| Venue | Proposed structure | Required identity |
|---|---|---|
| CORAL_CAFE | Several short uneven reflection paths and a small cavity bank | Intimate, intricate answers to individual strings |
| CONCH_AMPHITHEATRE | A small chain/network of coupled delay-resonant sections with distinct inlet/outlet weighting | Rounded, gathering, spiral-like blooms |
| CAVE_HALL | Spaced early returns, restrained low modes, shared diffuse tail | Deep bass answers and distant upper glints |
| SEAWEED_FIELD | Multiple soft transmission paths with current-driven apertures and absorption | An open field whose ringing sound finds shifting gaps |

These are invented acoustic interpretations. No real underwater venue measurements or general material behavior are asserted. In particular, a seaweed field should not receive the same enclosed late reverb as a cave with a darker filter added.

Both harps feed every venue with different weights and path timing. Separate their injection points so their different registers and positions reveal different venue responses. Mono output conveys those relationships through time and spectrum, not left/right placement.

Keep direct plucks clear across venues. Aquatic identity must survive without a global muffling filter or broad random vibrato.

## 11. VENUE Morph

Map VENUE to CORAL_CAFE at 0, CONCH_AMPHITHEATRE near 1/3, CAVE_HALL near 2/3, and SEAWEED_FIELD at 1. Adjacent models interpolate smoothly in their output and influence on shared loading.

Do not interpolate arbitrary delay lengths or raw filter coefficients between incompatible networks. A practical V1 implementation runs the two adjacent venue models and blends their separately bounded contributions. Preserve state during a render; a future live change must not flush tails or reset strings.

Budget combined output and return gains for correlated paths. Crossfade weights for feedback/loading must form a nonnegative sum no greater than one unless a separately validated normalization applies. Do not let midpoint blends double return energy.

The expanded patch may select a single discrete venue with overrides for size, harp/listener placement, and flow direction. Macro mode and override mode need documented precedence. Fixed geometry in V1 is sufficient; dynamic moving instruments are not required.

## 12. Macro Set and Required Interactions

| Macro | Main action | Starting neutral |
|---|---|---:|
| TOUCH | Pluck effort, hardness, and string participation | .40 |
| DUET | Tight shared gesture through answers and crossing directions | .35 |
| CURRENT | Still water through stronger interacting ripples | .30 |
| VENUE | Coral → conch → cave → seaweed | .33 |
| BLOOM | Direct strings through shared sympathetic/venue resonance | .45 |
| HOLD | Finite paired strum to seamless gesture/current cycles | .00 |

Required interactions: DUET × CURRENT changes how strums encounter the current phase; CURRENT × VENUE changes passages/loading in a venue-specific way; BLOOM × VENUE exposes distinct resonant tails; TOUCH × BLOOM changes how strongly a gesture awakens unplucked strings.

Every timbral macro must create measurable and audible change through low-to-middle steps. CURRENT = 0 is a declared still-water condition, not a silent venue. BLOOM = 0 retains a restrained body/venue floor so location remains meaningful without a large wash.

## 13. Voices and Defaults

```kotlin
enum class NacreVoice { PEARL, CORAL, CONCH, GROTTO, KELP, CROSSING }
```

| Voice | TOUCH | DUET | CURRENT | VENUE | BLOOM | Character |
|---|---:|---:|---:|---:|---:|---|
| PEARL | .30 | .15 | .20 | .15 | .35 | Clear close octave strums |
| CORAL | .40 | .30 | .35 | .00 | .45 | Small cavity replies and fine upper strings |
| CONCH | .45 | .40 | .35 | .33 | .65 | Gathering rounded resonance |
| GROTTO | .35 | .50 | .25 | .67 | .70 | Low foundation and distant glints |
| KELP | .25 | .45 | .65 | 1.00 | .45 | Soft strums through changing apertures |
| CROSSING | .60 | .85 | .70 | .45 | .65 | Distinct answering/crossing gestures |

Defaults are listening hypotheses. Voices configure performer touch, strum order, string/body character, venue geometry, and field phases using one shared architecture. Venue endpoints remain available in all voices.

## 14. Energy, Stability, and Output

Distinguish finite pluck input, dissipative string/body evolution, cross-harp exchange, venue return, and feed-forward wavering pickup. A visually gentle current is not a numerical stability guarantee.

Use a documented passive/contractive coupling network or a conservative complete-loop gain bound that includes both harps, bodies, sympathetic banks, and every active venue return. For a gain-bound approach, bound stationary branch operators including resonant tails, then constrain the aggregate return operator strictly below unity. A prototype design margin is .90; the binding requirement is a valid bound, not that particular number.

Individual stable filters and small-looking send coefficients do not prove the complete network is stable. Time-varying damping/coupling needs a uniform bound over CURRENT and VENUE. Keep branches without a justified bound out of the closed loop in V1. Measure internal decay as well as final audio.

Use shared `MELODIC_LOUDNESS_TARGET` leveling only after synthesis. Keep raw stems for diagnostics. No NaN, infinity, uncontrolled clipping, DC buildup, or persistent growth after excitation ends is acceptable. Do not use final limiting to hide failure.

## 15. Duration and HOLD

Prototype finite duration is approximately 2–8 seconds, subject to current project limits and measured tails. Preserve both complete strums and their answer before terminating. Stop admitting additional gestures before release, allow strings/venue to decay, and apply existing capped-tail treatment when required.

HOLD must repeat complete paired strum gestures. Use restrained recurring plucks rather than pretending a finite pluck supplies infinite sustain. Gesture spacing and strength are voice configuration; TOUCH and DUET must remain audible in held mode.

Select loop duration with the existing pitched-loop strategy, preserving the requested note. Align current phases, gesture periods, and deterministic timing variation to the loop. A strum can cross the boundary if its cyclic event schedule and state continuation are correct; do not duplicate its first pluck at the wrap.

Preroll until strings, bodies, sympathetic states, venue delays/modes, return paths, smoothing, and schedule state recur. Use a bounded convergence/search duration and diagnostics. A more periodic current in HOLD is acceptable if the movement and venue identity remain clear.

Apply minimal project-standard wrap treatment after selecting a stable region. Require `Keys.seamError < 1e-3` on exported, leveled audio and listening for clicks, doubled plucks, missing answers, sudden damping changes, or a collapsing venue tail.

## 16. Determinism, Bandwidth, and CPU

Use `Dsp.seedFor` or the current equivalent for pluck noise, tiny string differences, stable performer variations, and field phase offsets. Identical voice, patch, note, velocity, seed context, and render settings must produce sample-identical output within the supported runtime.

Keep stable harp/string IDs so editing one performer does not reseed the other. Event order and accumulation order must remain deterministic. No wall-clock randomness or external sound assets are required.

Initially probe MIDI 28–72 and extend only where both octave roles remain useful. Band-limit excitation and suppress upper strings/modes near the output Nyquist guard band. Preserve the dominant bass and upper voices when reducing high components.

Use the standard 4× path and antialias before decimation. Small nonlinearities, fractional delays, and time-varying paths need dedicated checks; oversampling alone does not guarantee clean output. Compare bright/high-current cases to a higher-rate reference.

Prototype budget: eight active string loops, eight sympathetic resonators, six body modes, and up to two adjacent compact venue models during morph. Precompute static geometry. Benchmark preparation, render time, peak memory, and HOLD convergence on an identified machine; set production budgets from measured evidence.

## 17. Patches, Presets, and Integration

Implement `Nacre.kt`, `NacrePatch`, `NacrePresets`, duet scheduler, shared-current/venue configuration, patch registration, `SynthKits`, pitched `drumClassFor`, audition generation, tests, and roadmap update. Confirm exact current sibling patterns such as `BorePatch` before coding.

Serialize voice, six macros, performer/gesture overrides, tuning mode/ratios, geometry overrides, and exposed deterministic configuration. Derived waveguide/delay states remain internal. Expanded voicing or venue editing must have explicit macro/override precedence and survive JSON round trip.

Plan 36 presets: six per voice. Cover clean octave gestures, close and delayed answers, crossing strums, each venue endpoint, still water, strong current, sympathetic response, and held variants. Candidate names: Pearl Duet, Coral Table, Shell Audience, Deep Grotto, Kelp Curtain, Crossing Tides, Bass Beneath, and Last Upper String. Core identity must survive an empty external FX chain.

## 18. Probes and Acceptance

Render every voice across low/middle/high notes and soft/medium/hard velocities. Sweep timbral macros through 0, .25, .50, .75, and 1. Add required interaction pairs, venue endpoints and morph midpoints, all-high cases, still water, and difficult held crossing gestures.

Record pitch confidence/error including octave ambiguity, raw peak/RMS, DC, pluck times, performer/string identity, spectral evolution, sympathetic excitation, venue decay, current coefficients, complete-loop gain bounds, macro distance, classifier outcome, seam error, and CPU/memory. Establish new numeric audio thresholds from first-round evidence.

Mechanism checks: mute one harp's direct plucks while retaining sympathetic excitation; disable cross-harp coupling at matched output level; move current processing entirely after synthesis for comparison; and bypass venue return while retaining listener pickup. The production model must reveal string-state interaction and venue-specific flow absent from those simpler versions.

Build in four stages: two useful octave-separated harps and deterministic double strum; shared sympathetic coupling and bounded current interaction; four distinct venues plus safe morph/extreme/CPU tuning; HOLD, presets, and integration.

Required automated checks include determinism, finite output/states, bounded coupling and decay, correct event order and octave ratios, macro activity, measured moderate pitch tolerance, JSON round trip, classifier safety, and representative held seams. Standard pitched cases must avoid KICK, SNARE, CLAP, HAT, and TOM. Correct excessive pluck clicks or weak pitched tails in synthesis rather than only changing labels.

Confirm and run the current repository gate, expected from the supplied conventions to be `./gradlew --no-daemon test`. No source implementation, DSP tests, or listening auditions were executed in creating this specification.

The owner's listening verdict completes acceptance: the two harps sound like distinct performers in different octaves; DUET reveals a shared or answering gesture; water affects the instrument interaction; each venue has its own response; seaweed feels open and shifting rather than like a dark cave; and held cycles preserve the beautiful double-strum ripple without audible seams.
