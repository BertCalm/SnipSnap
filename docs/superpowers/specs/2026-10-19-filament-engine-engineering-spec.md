# Filament Engine — Engineering Specification

## 1. Summary

**Filament** is a sample-free pitched synthesis engine for SnipSnap. A pluck, pulse, or sustained tone enters an imaginary glass strand. Guided paths carry it toward an endpoint; curvature lets selected components escape into a separate halo along the strand. Several strands can carry differently delayed, filtered versions of the same excitation and meet again at collection.

The defining gesture is **contain → bend → spill**. A clear note gradually sheds a luminous trail as its guide curls. Bending must redistribute sound between transmitted and escaped branches rather than merely lowering volume or adding unrelated reverb.

This is a proposed engineering handoff, not implemented DSP. Numerical mappings and defaults are prototype hypotheses except established project requirements. Filament is a working name; availability has not been checked.

## 2. Optical Reference and Musical Interpretation

Total internal reflection depends on incidence measured from the interface normal and both refractive indices. Fiber launch acceptance is a separate angle measured against the axis in the external launch medium. References: [RP Photonics: Total Internal Reflection](https://www.rp-photonics.com/total_internal_reflection.html) and [Acceptance Angle in Fiber Optics](https://www.rp-photonics.com/acceptance_angle_in_fiber_optics.html).

Using the user's example indices nCore = 1.4682 and nCladding = 1.4629 gives a calculated critical interface angle of about 85.130° from the normal, an internal meridional axial limit of about 4.870°, and external acceptance in air of about 7.160° under the elementary straight step-index ray model. The often-cited approximately 41.8° glass-to-air example is a different interface.

These are reference calculations, not measured engine performance. Ray models do not fully describe real fiber modes or bend radiation. V1 uses a documented musical bend-loss approximation; it must not claim exact Fresnel/Snell tracing of all real curved-fiber behavior.

## 3. Platform and Scope

Follow the supplied Gyre conventions: deterministic offline mono generation, 44.1 kHz output, standard 4× internal oversampling, five timbral macros plus HOLD, shared melodic leveling, patch JSON, presets, kits, and auditions. Confirm current repository interfaces before implementation; no codebase was inspected for this handoff.

V1 generates one requested note. No external samples, telecom-rate modulation, real optical-frequency computation, physical light emitter, stereo localization, or full electromagnetic solver is required. An audio-input effects version is future scope.

Use a feed-forward strand network with bounded paths and stable halo resonators. Closed optical-cavity feedback is unnecessary for the contain/spill identity and deferred from V1. Length controls musically scaled travel time, not literal optical flight time rendered at human scale.

## 4. Core Architecture

Use one band-limited source, an injection distribution, up to four strands with several guided-path classes, a segmented bend/transmission model, endpoint collectors, and a shared escaped-energy halo.

Per configuration or control frame:

1. Establish strand layouts, path lengths, and curvature profiles.
2. Calculate launch allocation, segment absorption, and bend-dependent retained/escaped fractions.
3. Derive endpoint and leakage-site delays, spectral responses, and collector weights.

Per oversampled audio update:

1. Generate excitation and its envelope.
2. Feed path inputs through their defined allocation.
3. Propagate each path, splitting its remaining signal at leakage sites.
4. Collect endpoint signals into THROUGH.
5. Collect escaped signals into SPILL and its stable resonant tail.
6. Mix collectors, DC-block, antialias, decimate, and level.

Leakage must be calculated from the remaining branch at its actual segment, not by copying a full-level source into every halo tap. Retain diagnostic stems and allocation traces.

## 5. Excitation — INJECT

Support three voice-configured source envelopes: finite pluck, short pulse train, and finite sustained tone. Use a sine/triangle-dominant harmonic source with restrained upper partials, plus a bounded deterministic attack burst where useful.

INJECT changes attack sharpness and the distribution across path classes: low values favor a narrow, direct launch; high values excite a broader set of path angles and harmonic components. It may increase source richness within an explicit gain budget.

The metaphorical angle distribution affects which path classes are admitted, delayed, or lost. It is not an arbitrary bank of detuned oscillators. Source spectral changes alone must not account for all INJECT activity.

Static linear propagation cannot create absent harmonics from a perfect sine. Richer sustained voices therefore contain suitable source harmonics for the strand and halo to select. Any time-varying curvature may add modulation sidebands, but that is a separate optional extension.

Velocity changes source effort, attack, and bounded launch spread rather than only final gain. INJECT = 0 remains a soft playable source.

## 6. Paths, Length, and Dispersion

Prototype four to eight path classes per strand with differing nominal axial angle, transit delay, absorption, and boundary sensitivity. They are a reduced musical representation of guided propagation, not an exact modal count.

LENGTH jointly increases common transit time, separation between path arrivals, and frequency-dependent loss. Suggested musical endpoint delay range is approximately 2–180 ms, with relative arrival spread around .5–60 ms. Voice settings can narrow these ranges for clear notes or widen them for trails.

Longer path classes should take longer and generally accumulate more attenuation/boundary interactions according to the chosen model. Do not change delay without any corresponding path behavior. Bound delays and interpolation history explicitly.

A finite pulse should reveal several related arrivals. A sustained tone should blend them into spectral reinforcement/cancellation and silky ringing. Broadband delay filtering alone is not enough if path-dependent leakage remains unchanged.

V1 may hold geometry constant within each render. Adjacent macro renders can use different delays without implementing moving delay heads. Any future within-render movement requires smooth interpolation and separate pitch/aliasing tests.

## 7. BEND and Leakage Sites

Represent each strand as a short ordered sequence, initially 4–8 sections, with a curvature profile and stable segment IDs. BEND raises curvature and changes where path classes become poorly retained.

Use a smooth bounded retained-power function of launch class, local curvature, and voice-defined confinement margin. For example, map a normalized bend severity through a smooth threshold per class. Near-axis classes may survive longer while marginal classes spill earlier. This is a reduced bend-loss model, not a derived exact wave-optics equation.

Differentiate a gentle curve, a concentrated tight bend, and a distributed coil through voice geometry. At equal nominal BEND, they should expose different leakage positions/timing rather than only different global gain.

For a segment with retained fraction R, escaped fraction T, and absorbed fraction A, require all to be nonnegative and R + T + A = 1. Define whether losses occur before or after the split and keep that ordering consistent. Under perfect retention, R = 1 and T = A = 0.

Transitions must be smooth across the macro; no threshold clicks or accidental negative power. A tight bend can leave a weak endpoint signal while producing strong spill. Do not normalize the endpoint independently to conceal its depletion.

## 8. Through/Spill Allocation

Power fractions and audio amplitude gains are different quantities. In a power-normalized branch model, a split uses amplitude factors sqrt(R) and sqrt(T), not R and T applied indiscriminately to audio.

Allocate the source among strands/path classes with a defined normalized splitter. Track remaining power through every section. Escaped allocation at an early segment is no longer available to escape again at later segments. Include absorption as an explicit sink.

Branch-level accounting is a model invariant, not a guarantee that a mono waveform's instantaneous peak stays constant. Related signals may sum coherently. Normalize collector readout and reserve raw headroom; do not merely add every branch at unity gain.

V1 should use a documented passive or gain-bounded splitter/collector construction. One practical approach uses a unit-length amplitude allocation vector and collector weights with Euclidean norm at most one, plus contractive branch processors. Account for halo filter/resonator gains before asserting an energy bound.

Compare budgets before final loudness leveling. Bending should audibly transfer emphasis between branches while keeping injected excitation fixed for a controlled comparison.

## 9. SPILL Halo

Each leakage site feeds the halo with its own accumulated propagation delay, escape weighting, and spectral content. Near-input leakage should arrive differently from leakage near the endpoint. A generic full-level reverb send independent of bend position is insufficient.

Use a modest shared resonator/diffusion structure, initially 8–16 damped modes and a few short dispersive paths. Modes mainly follow root-related ratios with restrained upper inharmonicity in experimental voices. Normalize branch gain and provide explicit positive damping.

The halo can make fine glints, soft partial clouds, or a longer luminous aftersound. Its contribution requires actual escaped input. Under a perfect-retention diagnostic configuration, SPILL must remain silent; no hidden oscillator or noise generator may keep it alive.

No uncontrolled self-oscillation or spill-to-source return is required. Finite excitation must eventually decay throughout both collectors.

## 10. BUNDLE

BUNDLE grows from one strand to as many as four differently routed strands. Every strand has persistent ID, length offset, bend-location pattern, launch preference, and restrained spectral identity. Keep existing strands unchanged when activating another, except for documented allocation changes.

Use smooth participation weights between roster sizes. Normalize total source allocation across active strands; more strands must not simply produce greater input energy. Do not reseed the entire bundle at macro thresholds.

Strands remain rooted in the same requested note. Their route lengths and phase relationships create reinforcement or hollowing when collected. Detune is optional, restrained secondary character; the core bundle effect must remain meaningful with detune disabled.

V1 uses parallel strands sharing injection and collection. True tree branching/recombination inside strands is deferred. The UI should not imply a recursive optical network that the engine does not implement.

## 11. COLLECT

COLLECT moves from endpoint emphasis at 0 toward escaped-halo emphasis at 1. Intermediate values reveal both. Use gain curves appropriate for correlated signals and the collector budget; generic equal-power mixing is not automatically correct.

At maximum bend, THROUGH may become quiet. At ideal straight retention, SPILL may be silent. These are meaningful structural states, not failures to be hidden by automatic branch makeup gain. Standard presets should generally retain a useful mix, but diagnostic/expanded settings may intentionally isolate near-silence.

Require macro activity across normal presets and appropriate structural fixtures. Do not demand that COLLECT reveal a nonexistent halo in a perfect-retention fixture or add fake leakage to satisfy a dead-knob test.

If direct root support is used, make it a documented retained path inside the budget. Do not inject a hidden bypass oscillator that contradicts endpoint depletion or spill-only isolation.

## 12. Macro Set and Interactions

| Macro | Main action | Starting neutral |
|---|---|---:|
| INJECT | Excitation sharpness and launch-path distribution | .40 |
| BEND | Curvature and leakage distribution | .35 |
| LENGTH | Transit time, arrival spread, and path loss | .40 |
| BUNDLE | One strand through four coordinated routes | .30 |
| COLLECT | Endpoint tone through escaped halo | .40 |
| HOLD | Finite source through seamless sustained propagation | .00 |

Required interactions: BEND × COLLECT reveals depletion versus escape; INJECT × BEND changes which paths survive; LENGTH × INJECT changes the arrival procession; BUNDLE × BEND distributes leakage among routes; LENGTH × COLLECT distinguishes early escape from endpoint travel.

Every timbral macro must have audible low-to-middle activity across the standard voice roster, subject to the declared perfect-retention/collector exceptions. Avoid broad dead regions where bend or length changes only hidden metadata.

## 13. Voices and Initial Defaults

```kotlin
enum class FilamentVoice { THREAD, PULSE, CURL, BRAID, HALO, FRAY }
```

| Voice | INJECT | BEND | LENGTH | BUNDLE | COLLECT | Character |
|---|---:|---:|---:|---:|---:|---|
| THREAD | .25 | .15 | .25 | .00 | .20 | Clear compact guided note |
| PULSE | .65 | .30 | .65 | .20 | .35 | Distinct procession of arrivals |
| CURL | .45 | .65 | .45 | .15 | .50 | Tight bend with audible escaping partials |
| BRAID | .45 | .40 | .60 | .85 | .40 | Several routes meeting at collection |
| HALO | .35 | .75 | .65 | .60 | .85 | Soft escaped cloud around a weak through tone |
| FRAY | .80 | .90 | .75 | .90 | .70 | Dense bright route leakage |

Defaults are hypotheses. Voices choose source envelope, strand profiles, path-class distributions, absorption curves, and halo response within one architecture. Names describe musical behavior rather than measured fiber types.

## 14. Duration, Pitch, and HOLD

Start with finite durations around 1.5–5 seconds for THREAD/PULSE/CURL and 3–8 seconds for BRAID/HALO/FRAY, respecting project limits. Include the full endpoint transit and escaped tail after excitation ends. Do not cut a long route before its first arrival.

Initially probe MIDI 36–84. Preserve requested root perception in standard presets through source and supported path allocation. Spectral cancellation can weaken f0; audition that independently of mathematical source tuning. Avoid globally retuning or adding hidden root support merely to pass every isolated-branch case.

HOLD maintains a sustained periodic source, or a repeating pulse schedule for PULSE-like voices. Align source, optional modulation, and excitation period to the selected loop using the established pitched-loop strategy. Static fractional-delay propagation does not require every delay length to be an integer divisor of the loop; it requires the input and full delay/filter state to recur.

Preroll enough periods to populate all path buffers and settle halo modes. Bound the convergence/search duration and emit diagnostics on failure. All strand participation, launch schedules, smoothing states, delay history, modal state, and deterministic variation must repeat.

Use minimal project-standard wrap treatment after selecting a stable region. Require `Keys.seamError < 1e-3` on exported audio plus listening for clicks, duplicated pulses, missing late arrivals, and a halo that collapses at the wrap. Do not freeze or remove leakage to obtain an easy loop.

## 15. Determinism, Safety, and CPU

Use `Dsp.seedFor` or the current equivalent for fixed strand/path variation, source phases, and optional attack noise. Identical patch, voice, note, velocity, seed context, and render settings produce sample-identical output within the supported runtime. Stable IDs and deterministic mix order are required.

Keep splitter fractions, gains, delay lengths, attenuation filters, resonator states, and source bandwidth bounded. No NaN, infinity, DC accumulation, unexpected state growth, or uncontrolled clipping is acceptable. Finite input must leave no persistent output after all stable tails decay.

Use band-limited excitation, suitable fractional-delay interpolation, standard 4× internal processing, and antialias before decimation. Nonlinear source shaping or optional time-varying geometry requires additional high-rate reference comparisons. Oversampling does not itself fix interpolation artifacts or coarse parameter jumps.

Prototype budget: four strands, four to eight path classes per strand, four to eight section loss sites, and 8–16 shared halo modes. Collapse static ordered segment gains/delays into an equivalent bounded tap structure when mathematically equivalent, preserving leakage-site timing and filtering.

Precompute static path data once per render. Benchmark preparation, synthesis, memory, and HOLD convergence separately on an identified machine. Increase complexity only if auditions justify it.

## 16. Reference Checks and Model Invariants

Keep optical reference checks separate from musical audio acceptance. Verify critical-angle and launch-angle calculations with explicit normal/axis/external-medium conventions. If a diagnostic implements a planar Fresnel split, test both polarizations or a declared unpolarized average using power coefficients, including the correct transmission factors. Do not treat field-amplitude coefficients as power fractions.

Perfect total reflection, zero leakage, and critical-region behavior are useful nominal fixtures. A reduced curvature leakage curve is not a numerical verification of real macrobend radiation. No history/date or global internet-traffic claim is needed for the engine.

At the model level, verify nonnegative R/T/A, unit segment allocation sums, conservation of retained-plus-escaped-plus-absorbed allocation through the chain, no repeated donation of already escaped input, invariant launch budget across strand/path counts, and correct early/late escape order.

At the audio level, validate actual gain and decay independently; modal coloration and coherent mono summation must be included. Do not use allocation bookkeeping alone to assert overall physical passivity.

## 17. Patches, Presets, and Integration

Implement `Filament.kt`, `FilamentPatch`, `FilamentPresets`, path/strand preparation, collector routing, patch registration, `SynthKits`, pitched `drumClassFor`, audition generation, tests, and roadmap update. Confirm exact current sibling conventions such as `BorePatch` before implementation.

Serialize voice, six macros, fixed strand/launch/collector overrides, and exposed deterministic configuration. Derived tap data and runtime states remain internal. Define macro/override precedence and preserve behavior through JSON round trip. No external asset dependencies are required.

Plan 36 presets: six per voice, including narrow launch, broad launch, straight guide, tight bend, short and long paths, one and four strands, through-dominant and spill-dominant mixes, and held variants. Candidate names: Glass Thread, Narrow Arrival, Loose Curl, Four Routes, Bend Glow, Spilled Silver, Long Passage, and Frayed Light. Core identity must survive an empty external FX chain.

## 18. Probes and Acceptance

Render each voice across low/middle/high notes and soft/medium/hard velocities. Sweep macros through 0, .25, .50, .75, and 1. Include required interaction pairs, all-high cases, ideal retention, isolated collectors, endpoint depletion, maximum length, and difficult held pulse sequences.

Record raw peak/RMS, DC, fundamental confidence/error, impulse arrival times/spread, through/spill/absorption allocation, raw collector levels, spectral evolution, macro distance, classifier result, seam error, CPU, and memory. Set new numeric audio tolerances from first-round probes.

Mechanism checks: bypass bend splitting while preserving average output level; collapse paths to one delay; replace leakage-site timing with one generic reverb send; and compare one/four strands at matched input energy. The production version should expose path-dependent depletion and spatially ordered escape that these simplified versions lose.

Build in four stages: useful source and guided paths; ordered bend splitting and true spill collection; bundle/halo plus aliasing/performance refinement; HOLD, presets, and integration. Required automated checks include determinism, finite/bounded states, allocation invariants, arrival order, macro activity with declared structural exceptions, measured moderate pitch tolerance, JSON round trip, classifier safety, and representative held seams.

Standard pitched auditions must avoid KICK, SNARE, CLAP, HAT, and TOM. Fix excessively sharp pulses or weak pitched sustain in synthesis rather than only relabeling classifier output. Confirm and run the current repository gate, expected from supplied conventions to be `./gradlew --no-daemon test`. No source implementation, DSP tests, or listening auditions were executed in creating this specification.

The owner's listening verdict completes acceptance: straight paths carry a clear note; length unfolds distinct arrivals; bending depletes THROUGH while feeding SPILL; bundles create related route interference rather than only chorus; COLLECT reveals where sound went; and held output preserves the glass-thread identity without audible seams.

## As built — 2026-10-23 follow-through

The owner approved model 3 of THREAD, PULSE and CURL after the third listening
revision. Twelve dry recipes, factory/phone selection and a sixteen-pad
starter implement this accepted subset. Models 1–3 retain their saved recipes;
the four-path, four-section and eight-halo-mode bounds remain within the
proposal. Native output uses fixed gain 1.20 to preserve branch depletion and
velocity behavior, as documented in [FILAMENT.md](../../FILAMENT.md).

Playback and diagnostics share one DSP core, with optional diagnostic capture,
cooperative cancellation and bounded phone preview caching. The model-3 THREAD
keygroup pilot retains actual zero-state startup before an independently
verified recurring cycle, using existing MPC 2/MPC 3 export support. It covers
C3–C5 with native C3/C4/C5 roots and one velocity layer; the host owns gain and
note-off release. Physical phone/MPC acceptance, independent halo release,
the remaining voices and the larger preset target remain later work. Current
validation, render measurements and reproduction commands live in FILAMENT.md.
