# Shoal Engine — Engineering Specification

## 1. Summary

**Shoal** is a pitched additive synthesis engine for SnipSnap inspired by the Kawai K5. Its partials behave as a coordinated population: excitation gathers energy into spectral clusters, a current moves it between neighboring partials, cohesion encourages regrouping, and restrained departures let selected partials stray from the harmonic series.

The defining gesture is **gather → scatter → regroup**. A stable fundamental anchors glassy keys, luminous sustained tones, spectral ripples, and migrating whistles.

This is an engineering proposal, not an implemented engine. Numerical values are prototype hypotheses except established project requirements. Shoal is a working name; availability has not been checked.

## 2. Historical Foundation and Scope

The original Kawai K5 manual describes additive construction from individually controlled harmonics and assignment of harmonics to envelope groups. That is the reference mechanism. Dynamic energy exchange, cohesion, and coordinated straying are proposed extensions, not historical K5 features.

Reference: [Kawai K5 owner's manual scan](https://www.scribd.com/document/1012109019/Kawai-K5-Synthesizer-Manual), particularly the introduction and Digital Harmonics Generator sections. V1 need not reproduce the original harmonic count, processing chain, presets, or hardware limitations.

Follow the supplied Gyre conventions: offline deterministic mono generation, 44.1 kHz output, standard 4× internal oversampling, five timbral macros plus HOLD, shared melodic leveling, patch serialization, presets, kits, and auditions. Confirm current repository interfaces before coding. No repository source has been inspected for this handoff.

Generate one requested root per sample. No external samples, live polyphonic scheduler, literal fish simulation, or post-render spectral effect is required.

## 3. Core Architecture

Use a bank of sinusoidal partials with separate phase and nonnegative energy state. V1 starts with at most 48 moving partials plus one protected fundamental. Voice configurations define a baseline spectral profile, a few envelope groups, and cluster locations.

Per update:

1. Establish eligible partials from root pitch and output bandwidth.
2. Add bounded excitation energy to the spectral population.
3. Calculate transfers from the previous complete energy state.
4. Apply transfers simultaneously, then damping and supply limits.
5. Update cluster/current and pitch-departure control states.
6. Convert energy to smoothed amplitude and synthesize partials.
7. Add the protected root, condition, decimate, and level output.

Control-state updates may run at a lower deterministic rate; oscillator phases run at the oversampled audio rate. Interpolate amplitudes and frequency offsets to avoid zipper noise. Energy transport must operate before audio generation, not on an FFT of the finished sample.

## 4. Harmonic Bank and Root

Nominal moving-partial frequency is `n * f0`, with n an integer harmonic index. Track absolute frequency ceilings at the output sample rate. Fade partials out below a defined guard band as their frequency approaches Nyquist; do not abruptly remove them or synthesize inaudible high partials that fold back during decimation.

The protected fundamental has its own bounded envelope and does not donate energy to the transport network. DENSITY may vary its relative prominence modestly, but all standard pitched presets retain an audible root or a convincing harmonic foundation. A root anchor is not permission to bury pitch beneath an arbitrary noise cloud.

Start cluster positions in harmonic-index space. Energy moving from harmonic 5 to 6 changes which harmonic is strongest; it does not require gliding one oscillator's frequency between them. STRAY handles actual frequency departure separately.

## 5. Excitation and Energy Budget

Each note supplies a finite initial spectral energy distribution. Sustaining voices also receive a bounded continuous supply while excitation is active. Velocity changes initial supply, cluster concentration, and attack effort rather than only final gain.

Define the sum of moving-partial energies explicitly. DENSITY changes how this budget is distributed, not the total energy by an uncontrolled factor. Profiles can be sparse, broad, odd-biased, or upper-clustered, with smoothed transitions.

Amplitude derives from energy, approximately `amplitude[n] = sqrt(energy[n])`, with a defined scale and smoothing. Spectral-state energy conservation does not guarantee constant waveform peak because phases can sum coherently. Maintain a separate mix headroom budget and inspect raw output peaks.

When excitation ends, all donor and receiver energies decay. No cluster controller may spontaneously replenish the energy bank. Keep a measured optional tail floor out of finite mode unless required by existing project conventions.

## 6. Transport — CURRENT

Use a sparse directed neighbor graph in harmonic-index space. Start with adjacent neighbors and a small number of voice-selected longer links. CURRENT controls directional bias and transport speed. Its initial gesture is a rising or circulating spectral current; voices may reverse direction without exposing extra macros.

Calculate donor fractions from the previous energy snapshot. For each donor j, require nonnegative outgoing fractions whose sum is at most 1. The next energy state is:

```text
nextEnergy[i] = energy[i] * (1 - outgoingSum[i])
              + sum(transfer[j -> i] * energy[j])
```

Apply damping and external supply in a documented order afterward. This transfer step preserves total network energy apart from floating-point error. It does not create energy or allow negative values. Do not update donors sequentially in place, which would bias transport toward iteration order.

At bandwidth boundaries, retain unavailable transfer energy at the donor or redistribute it among valid links with the same total fraction. Do not silently discard large amounts of energy when register changes.

Choose transport coefficients from `1 - exp(-rate * controlDt)` so behavior remains consistent when the control update rate changes. Bound total outgoing transfer separately when combining mechanisms.

## 7. Cohesion and Clusters

Maintain a small number of cluster centers, initially 2–4 per voice. COHESION biases transfer toward occupied neighborhoods and those centers. High cohesion produces concentrated harmonic groups; low cohesion permits more dispersed motion.

The coupling must depend on the current energy distribution, such as local energy sums computed from the previous snapshot. Otherwise it is only a moving fixed envelope. Combine local attraction with CURRENT bias and a weak baseline-profile preference so clusters can disperse and return.

COHESION changes transfer probabilities, not oscillator phase synchronization. Do not accidentally synchronize all partial phases and create extreme periodic peaks. Normalize combined edge weights, apply the outgoing-transfer cap, and keep a minimum path diversity where needed to prevent the population from permanently collapsing onto one upper partial.

Cluster centers evolve slowly under bounded deterministic control. Excitation may initially concentrate energy; CURRENT carries it away; baseline attraction and cohesion enable regrouping. Initial prototype movement scales are approximately 0.2–8 seconds for broad migration, with faster small exchanges where useful.

## 8. STRAY — Pitch Departure

STRAY introduces bounded frequency departures to selected moving partials, leaving the protected fundamental fixed. Moderate departures should retain recognizable harmonic identity. Proposed initial moderate limits are approximately ±5–20 cents; experimental voices may expand selected upper partials toward ±60–150 cents after pitch and aliasing probes.

Tie departure targets to cluster membership and energy flow so neighboring partials can move together, then relax back as a cluster regroups. Use exact one-pole relaxation or a stable bounded second-order control model. No unbounded frequency walk is permitted.

Avoid changing all partials with the same detune signal, which would merely bend the whole note. Also avoid independent uncorrelated random detunes that erase cohesion. Any variation uses deterministic seeds and controlled group structure.

Fade or limit displaced frequencies near the bandwidth ceiling. STRAY must remain audible at moderate values without destroying fundamental confidence in standard presets.

## 9. GLOW — Spectral Persistence

GLOW controls how long higher clusters retain energy, the relative damping of different harmonic regions, and the amount of restrained continuous excitation in sustaining voices. Low values produce dry clear articulations; high values leave luminous, slowly regrouping spectral tails.

It must do more than extend a final output envelope. Make the decay and persistence of upper and lower regions differ audibly. Keep positive damping in finite mode, including the all-high corner.

In HOLD, maintain a steady bounded supply and use GLOW to change residence time and spectral smoothing. It must remain active after initial transients have passed.

## 10. Macro Set and Interactions

| Macro | Low | High | Starting neutral |
|---|---|---|---:|
| DENSITY | Few dominant partials | Broad populated spectrum | .40 |
| CURRENT | Slow local exchange | Faster directed spectral migration | .30 |
| COHESION | Dispersed population | Strong gathering and coordinated groups | .55 |
| STRAY | Harmonic alignment | Bounded departures and return | .15 |
| GLOW | Dry short persistence | Long-lived spectral energy | .45 |
| HOLD | Finite render | Seamless sustained loop | .00 |

Required interactions: CURRENT × COHESION changes whether migration remains clustered; DENSITY × COHESION changes how many groups become perceptible; COHESION × STRAY changes coordinated detuning; CURRENT × GLOW changes how far energy travels before decaying.

CURRENT = 0 disables directed transport but may leave a slow cohesion-driven settling response. COHESION = 0 removes attraction without disabling CURRENT. STRAY = 0 restores harmonic frequencies. Every macro must show activity across low-to-middle steps, not only extreme endpoints.

## 11. Voices and Defaults

```kotlin
enum class ShoalVoice { GLASS, ORGAN, RIPPLE, WHISTLE, DRIFT, SWARM }
```

| Voice | DENSITY | CURRENT | COHESION | STRAY | GLOW | Character |
|---|---:|---:|---:|---:|---:|---|
| GLASS | .30 | .20 | .70 | .05 | .40 | Clear keys with clustered tails |
| ORGAN | .65 | .15 | .55 | .00 | .60 | Stable harmonic sustain with movement |
| RIPPLE | .45 | .70 | .60 | .15 | .50 | Audible migrating spectral waves |
| WHISTLE | .25 | .55 | .85 | .25 | .45 | Narrow upper clusters over a root |
| DRIFT | .55 | .35 | .45 | .45 | .80 | Slow luminous departure and regrouping |
| SWARM | .85 | .80 | .65 | .65 | .70 | Dense coordinated spectral motion |

These are starting hypotheses. Voice differences come from baseline profiles, transport links, cluster counts, supply envelopes, and damping within one architecture.

## 12. Rendering, Safety, and Determinism

Start with finite durations of 1.5–5 seconds for GLASS/WHISTLE and 3–8 seconds for other voices, respecting current project limits. End continuous supply, allow energy to decay, and cap runtime explicitly. Low notes should not lose their pitch tail prematurely.

Use `Dsp.seedFor` or the current equivalent for phase initialization and small deterministic variation. Identical inputs must produce identical samples in the supported runtime. No nondeterministic scheduling, wall-clock seeds, or external assets are needed.

Keep raw energy, supply, gain, and partial-count budgets explicit. For ordinary sine partials, amplitude/frequency modulation still creates sidebands; limit control bandwidth and account for sidebands in the output guard band. Oversample and antialias before decimation. Reduce eligible harmonic count at high notes with smooth weighting.

Use the shared `MELODIC_LOUDNESS_TARGET` process after synthesis. Retain prelevel diagnostics so leveling cannot hide collapsed energy, excessive coherent peaks, or dead macros. No runaway growth, negative energy, NaN, infinity, or DC buildup is acceptable.

## 13. HOLD

HOLD must repeat oscillator phases, energy distribution, cluster positions, transport controls, STRAY offsets, smoothing states, supply envelope, and any noise/variation sequence. Periodic cluster centers alone do not guarantee recurrence of the energy network.

Use the existing pitched-loop strategy to preserve root pitch. Align slow controls and continuous supply to the selected period. Preroll repeated periods and measure state convergence with an explicit search cap. Inspect the complete energy vector rather than only output seam error.

Where nonlinear cohesion prevents a reliable recurring state, an acceptable HOLD approximation is a periodic energy/STRAY trajectory captured from the same model and smoothed at the control wrap. Document this change and retain audible gathering, scattering, and regrouping. Do not freeze the spectrum to obtain an easy loop.

Use project-standard minimal wrap treatment after selecting a stable region. Exported audio must satisfy `Keys.seamError < 1e-3` and pass listening for clicks, sudden brightness jumps, extra regrouping attacks, and pitch discontinuity.

## 14. Patches, Presets, and Integration

Implement `Shoal.kt`, `ShoalPatch`, `ShoalPresets`, patch registration, `SynthKits`, pitched `drumClassFor`, audition generation, tests, and a roadmap update. Follow current sibling-patch conventions such as `BorePatch` after confirming interfaces.

Serialize voice, six macros, and exposed deterministic configuration. Derived energies and control states remain internal. JSON round trips preserve behavior; use established defaults and validation.

Plan 36 presets: 8 glass, 6 organ, 8 ripple, 4 whistle, 6 drift, and 4 swarm. Include clean samples, experimental movement, and held variants. Candidate names: Glass Gathering, Quiet School, Rising Shoal, Narrow Current, Stray Light, Long Drift, Harmonic Tide, and Swarm Lantern. Verify identity with an empty FX chain.

## 15. Probes and Acceptance

Render every voice at low/middle/high pitch and soft/medium/hard velocity, initially probing MIDI 24–84. Sweep all timbral macros through 0, .25, .50, .75, and 1. Include required interaction pairs, all-high cases, high-register bandwidth limits, and sustained HOLD.

Measure raw peak/RMS, DC, fundamental confidence/error, spectral centroid, spectral entropy, energy distribution over time, transport conservation residual, minimum energy, cluster spread, macro distance, classifier result, seam, and CPU cost. Set new numeric audio tolerances from Round 1 probes.

Mechanism checks: disable transport while preserving average profile; remove local cohesion while retaining center motion; disable STRAY while preserving energy motion. The corresponding changes must be audible and traceable to internal state. A spectrogram should show energy migration, not only a global brightness sweep.

Implement in stages: stable additive profiles and budgets; conservative transport and cohesion; STRAY and extreme/register refinement; HOLD and production integration. Automated requirements include determinism, finite output/states, energy positivity and conservation of isolated transfers, macro activity, measured pitch tolerance, classifier safety, patch round trip, and held seams.

Standard pitched auditions must avoid KICK, SNARE, CLAP, HAT, and TOM. Correct noisy or transient-dominant synthesis instead of only overriding classification. Confirm and run the current repository gate, expected from supplied conventions to be `./gradlew --no-daemon test`. No implementation tests were executed for this specification.

The owner's listening verdict completes acceptance: partials audibly gather and migrate; COHESION changes their coordination; STRAY briefly loosens harmonic alignment; GLOW changes internal persistence; moderate patches remain musical; and held loops preserve the population's movement.
