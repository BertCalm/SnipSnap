# Rime Engine — Engineering Specification
Version 1.0 · 18 October 2026
Status: proposed engineering handoff; implementation, measurements, and listening approval remain outstanding.

## 1. Summary

Rime is a pitched SnipSnap synthesis engine built from a two-dimensional periodic contour represented by complex Fourier coefficients. A virtual pickup reads the contour as a waveform, while additional components progressively reveal finer structure. Contour motion also supplies a bounded contact excitation to a resonant body.

The sonic gesture is **pure loop → sculpted tone → intricate edge → crystalline rasp**. A note should retain its identity as finer detail emerges within it, then lose that detail naturally during release.

The reference visual is a Koch snowflake reconstructed through rotating Fourier components. The instrument uses finite, explicitly defined approximations. Its body, excitation mechanics, and musical unfolding are design proposals, not physical consequences of Fourier's theorem.

## 2. Goals and boundaries

Support hollow basses, flute-like beginnings, geometric keys, reed-like leads, fine digital strings, glassy plucks, crystalline sustained tones, and bright textures with organized internal motion.

Detail must enter the source-generation process rather than merely increasing a final filter cutoff. ANGLE must operate on the contour pickup. BODY must change a resonating structure. UNFOLD must reveal and conceal contour detail within a note.

No external samples are required. V1 does not include user-drawn shapes, SVG import, a full elastic snowflake simulation, or a visual editor. A diagnostic contour display is useful for development but is not a required product screen.

The updated Gyre document supplies the project conventions. Check actual repository names, signatures, and test commands during implementation. This handoff does not claim a repository inspection or successful engine tests.

## 3. SnipSnap contract

Use deterministic offline mono generation, 44.1 kHz output, the standard 4× internal DSP path, and band-limited decimation.

Expose five normalized timbral/behavior macros plus HOLD. Use existing pitch, velocity, duration, seed-context, normalization, audition, and MPC export surfaces.

All stochastic excitation must derive from the project's seed mechanism, such as `Dsp.seedFor`. Final leveling follows the shared process and `MELODIC_LOUDNESS_TARGET`.

Implement the normal BORE-sibling patch architecture, JSON round trip, presets, kits, classifier mapping, auditions, tests, and roadmap entry. HOLD output must meet `Keys.seamError < 1e-3` plus owner listening approval.

## 4. Architecture

Separate four stages:

1. Procedural contour generation and equal-distance sampling.
2. Fourier coefficient extraction and validated band-limited representations.
3. Note rendering with projected pickups, unfolding, and contact excitation.
4. Resonant body, output conditioning, decimation, and shared leveling.

Working components: `RimeContour`, `RimeSpectrum`, `RimeExciter`, `RimeBody`, and `RimeHold`. Adapt to repository organization.

The mathematical reconstruction must be independently testable without audio. The body must be testable with a known input independent of the contour.

No live FFT is required per sample. Precompute reusable contour data; prepare note-specific bandwidth and envelope data before rendering.

## 5. Finite reference contour

Generate an equilateral triangle with a documented orientation, starting vertex, and traversal direction. Replace each edge with the standard four-segment outward Koch construction.

Use iteration 5 as the initial production candidate: 3,072 edges. Compare iterations 4 and 6 during probes. These are finite polygonal approximations, not the limiting fractal with infinitely many corners.

Resample the polygon at equal cumulative arc-length intervals. Begin with 16,384 samples for iteration 5; compare with 32,768 samples for coefficient convergence. Do not duplicate the endpoint in the DFT input.

Subtract the centroid and normalize to a documented reference radius. Keep the original normalization and iteration metadata. Apply the same starting-point convention across all variants to avoid accidental phase jumps.

Equal arc-length sampling applies to the chosen finite polygon. Do not claim that this is a uniform finite-speed traversal of the infinite-perimeter limiting Koch curve.

## 6. Fourier convention

Represent the sampled contour as `z(u)=x(u)+i*y(u)`, where `u` runs from 0 to 1:

```text
c_n = (1/M) * sum_(k=0..M-1) z(k/M) * exp(-i*2*pi*n*k/M)
z_N(u) = sum_(n=-N..N) c_n * exp(i*2*pi*n*u)
```

Remove `c_0` after centering. Negative indices rotate in the opposite direction. Radius is `abs(c_n)`; starting phase is `arg(c_n)`; angular speed is proportional to signed n.

Terms are not required to become monotonically smaller as frequency rises. Use signed frequency indices rather than treating every component as a positive-frequency audio oscillator.

Begin with a maximum absolute index of 512 for contour synthesis. This means up to 1,024 non-DC signed slots, not “512 circles” if both signs are populated. Store actual nonzero term counts.

Use a fixed transform convention, coefficient ordering, precision, and model version. Avoid reliance on platform-specific FFT ordering without tests.

## 7. DETAIL and component admission

DETAIL controls the maximum resolved scale using a monotonic harmonic cutoff and a smooth admission window.

Suggested reference-index targets:

| DETAIL | Approximate maximum absolute index |
|---:|---:|
| 0.00 | 1 |
| 0.25 | 8 |
| 0.50 | 32 |
| 0.75 | 128 |
| 1.00 | 512 |

Interpolate logarithmically and admit boundary terms fractionally. Use a transition band rather than suddenly adding one full-amplitude term.

At DETAIL zero, retain a normalized fundamental circular reference component even if a chosen contour variant has a weak first coefficient. Blend this reference into the reconstructed contour through a documented low-detail transition. Above that region, use the actual centered contour coefficients.

Order admission primarily by absolute frequency, not coefficient amplitude. Magnitude sorting can be useful in a display, but it does not create a consistent coarse-to-fine musical hierarchy.

Do not normalize every partial independently. Preserve relative contour information and apply only bounded overall gain compensation.

## 8. Source projection and ANGLE

For pickup angle alpha:

`p_alpha(u) = Re(exp(-i*alpha) * z_N(u))`

A real projected waveform combines positive and negative contour terms. Its positive-frequency coefficient is:

`a_n(alpha) = 0.5 * (exp(-i*alpha)*c_n + exp(i*alpha)*conj(c_-n))`

Apply DETAIL, EDGE, and bandwidth weights before evaluating this formula. Test it against direct projection of the complex path.

Map ANGLE over a full 2*pi rotation with periodic endpoints. ANGLE 0 and 1 intentionally coincide.

A single projection of a symmetric shape may change only harmonic phases, not their magnitudes. Do not promise a dramatic spectral sweep from projection alone.

Use a second fixed-orientation reference pickup feeding a different body port. Its level starts around 0.25–0.4 relative to the rotating pickup. The two ports have different, versioned modal readouts and path responses. Their coherent combination makes relative pickup orientation consequential without redefining ANGLE as a filter knob.

Do not normalize each pickup separately. At the exact single-circle endpoint, ANGLE may reduce to phase or level variation; this is a documented geometric degeneracy. Normal multi-component presets must demonstrate an audible angle effect after final leveling.

## 9. EDGE

EDGE controls the treatment of active fine-scale features, separately from DETAIL.

At low EDGE, apply a smooth high-index taper. At high EDGE, retain more of each admitted coefficient and mix in a restrained slope/contact contribution. It must not manufacture infinite corners or automatically enable terms excluded by DETAIL.

An example taper is `exp(-lambda*(abs(n)/N_active)^2)`, with lambda decreasing as EDGE rises. Calibrate the function and its range.

For slope-related excitation, compute the analytic derivative of the finite series, then normalize by a bounded derivative-energy estimate. Differentiation multiplies coefficients by frequency and can otherwise produce excessive gain.

Smooth or band-limit any rectification or nonlinear contact stage. Keep a persistent position-derived pitched component. Maximum EDGE should sound finely serrated, not become uncontrolled white noise.

## 10. UNFOLD

UNFOLD controls within-note development of DETAIL and the relative timing of fine-scale contact excitation. It does not change the requested note frequency.

At zero, the DETAIL-selected contour is present from the onset. Increasing UNFOLD starts from a coarser representation, reveals detail later, and lets the finest admitted components recede before the main body finishes ringing.

Suggested one-shot timing:
- low: near-immediate reveal;
- middle: reveal over roughly 50–300 ms;
- high: extended reveal over roughly 0.3–1.5 seconds, scaled to sample duration.

These are proposed ranges. At high UNFOLD, include a quiet lead-in if necessary to avoid a discontinuity, while retaining useful note onset.

Use smooth admission envelopes per frequency band, staggered by scale. Do not phase-reset a harmonic when its admission weight rises.

Keep unfolding duration independent from the audio-period phase. Avoid stepping the contour once per note cycle. DETAIL controls maximum available structure; UNFOLD controls when that structure appears.

At DETAIL zero, there is no fine detail to reveal. A complete UNFOLD effect at that exact endpoint is not required; do not add unrelated modulation merely to hide the dependency.

## 11. Excitation and contour contact

Maintain two related source contributions:
- the projected contour waveform, preserving the note;
- bounded contact excitation derived from contour slope, turning, and scale-specific movement.

The contact path may combine a shaped onset impulse, derivative-related periodic pressure, and low-level deterministic friction noise. Its modulation must follow the contour features. Arbitrary random crackles are not a substitute for self-similar detail.

Compute curvature-like features only with a regularized denominator or a smooth turning-angle estimate; local contour speed can approach zero. No division by an unguarded derivative magnitude is permitted.

Noise should remain subordinate at moderate settings. It may become more apparent with high EDGE and high DETAIL, but the pitched component must persist.

Velocity changes onset energy, contact hardness, and body excitation before loudness leveling. High velocity must not destabilize any resonator.

## 12. BODY

Use a lightweight modal resonator with approximately 6–10 modes and two pickup/input ports. The contour and contact sources excite the same body.

BODY changes a correlated set of properties: effective scale, modal spacing, damping, low-frequency reinforcement, absorption, and direct/body balance. It is not a single cutoff sweep.

| BODY | Intended behavior |
|---:|---|
| 0.00 | Small, dry, direct contour |
| 0.35 | Compact hollow body |
| 0.60 | Clear resonant shell |
| 0.80 | Deep cavity with fine upper ringing |
| 1.00 | Oversized synthetic resonator |

Maintain some direct contour at all settings. Keep the requested root represented in both source and body. Upper body modes may be mildly inharmonic, with stronger damping where they would obscure pitch.

V1 can use a feedforward excited modal bank without body-to-source feedback. If feedback is later added, specify its stability bound independently. No final limiter may conceal internal growth.

## 13. Pitch and bandwidth

The phase accumulator advances at the requested fundamental:

`u += f0 / internalSampleRate`

Signed contour index n produces frequency magnitude `abs(n)*f0` before projection. Contour detail must never be interpreted as an arbitrary unrelated oscillator frequency.

Derive note-specific anti-alias weights from final output bandwidth as well as internal sample rate. With 44.1 kHz output, a 512th harmonic at many normal notes cannot survive export. The highest permitted geometric detail and the highest audible harmonic are different quantities.

Use a smooth output-band transition and remove unsafe terms before synthesis. Oversampling alone does not make all 512 harmonics usable.

UNFOLD amplitude modulation and contact nonlinearities can create sidebands. Reserve headroom for their bandwidth, oversample nonlinear stages, and compare against a higher-rate reference.

At high notes where harmonic count is small, preserve continuity of timbre and pitch. Do not force high-frequency detail back into the audible band by aliasing it.

## 14. Rendering implementation

Prefer precomputed band-limited tables or a harmonic-domain oscillator that preserves the exact coefficient convention.

A practical table implementation groups harmonics into a small number of nested scale bands. Each band's waveform is precomputed for the patch and note bandwidth; UNFOLD interpolates their gains without rebuilding an FFT per audio sample.

Use interpolation and table sizes adequate for the highest admitted harmonic. Compare against direct complex-series evaluation as the reference. Bound table memory and preparation time.

Do not interpolate between arbitrary full waveforms with unrelated phase origins. Preserve phase alignment across DETAIL, EDGE, and UNFOLD representations.

Static controls may be prepared once per offline render. Time-varying band gains and contact coefficients require smooth audio evaluation. Define coefficient/cache versioning so a saved patch remains reproducible.

## 15. Macro contract

| Macro | Neutral/default starting point | Main role |
|---|---:|---|
| DETAIL | .50 | Maximum contour complexity |
| EDGE | .35 | Fine-feature sharpness and contact |
| ANGLE | .20 | Rotating pickup relative to reference/body ports |
| UNFOLD | .35 | Timing of detail emergence and recession |
| BODY | .45 | Correlated resonating-object properties |
| HOLD | 0 | One-shot versus seamless sustain |

Values are voicing hypotheses. Probe and listen before adopting them as production defaults.

Required interactions: DETAIL × EDGE changes the character of admitted fine structure; DETAIL × UNFOLD changes scale development; ANGLE × BODY changes pickup interference through the resonator; EDGE × BODY changes how contact detail rings.

Test normal presets for measurable and audible macro activity. Preserve documented geometric dependencies at exact endpoints rather than introducing arbitrary substitute effects.

## 16. Voice roster

Implement `RimeVoice` with six designs using the same contour-based mechanism.

| Voice | Character | DETAIL | EDGE | ANGLE | UNFOLD | BODY |
|---|---|---:|---:|---:|---:|---:|
| LOOP | Rounded geometric bass/lead | .25 | .15 | .20 | .10 | .25 |
| REED | Nasal contour with a hollow body | .45 | .45 | .35 | .25 | .40 |
| GLINT | Fine, bright keys | .70 | .60 | .65 | .35 | .55 |
| FILIGREE | Intricate scale-by-scale motion | .90 | .50 | .45 | .80 | .45 |
| CRUST | Dense crystalline edge | 1.00 | .90 | .75 | .45 | .35 |
| VESSEL | Slower unfolding through a large body | .65 | .30 | .25 | .85 | .85 |

Voice differences may include body mode tables, contact balance, and deterministic mild contour asymmetry. The reference snowflake reconstruction remains available as a mathematical fixture.

Any asymmetry must be generated before coefficient extraction and documented as an instrument variant. Do not claim that an altered shape remains the exact Koch reference.

## 17. Presets and duration

Ship six reviewed presets per voice, 36 total, covering bass, melodic keys, sustained tone, soft onset, bright detail, and held-loop applications.

Initial naming ideas: Round Seed, Hollow Tooth, Fine Frost, Glass Reed, Slow Filigree, Crystal Husk, Open Vessel, Serrated Air, Quiet Facets, and Melting Edge. Names describe sound rather than promising literal materials.

Start with approximately 1–6-second one-shots, allowing up to 8 seconds for slow unfolding/body decay where project limits permit. Render until excitation has ended and body energy falls below a measured threshold, with a hard cap.

Preset development follows the core empty-FX listening pass. Optional rack recipes use the existing `FxChain`; they must not supply missing engine identity.

## 18. HOLD

At HOLD on, distinguish periodic source phase from slower unfolding phase.

For static held tones, freeze detail at a useful sustained state and preroll the body until a repeatable region is available.

For moving held tones, replace one-shot reveal/recession with a smooth periodic scale envelope. Use an integer number of envelope cycles within the exported loop. Explicitly store or derive the chosen hold adaptation.

All active oscillator, band-envelope, noise, modal, and filter states must be loop-compatible. Use periodic seeded roughness or reduce its contribution where necessary.

Preserve the requested root using the project's pitch-preserving loop method. Do not quantize the root to an arbitrary sample-window frequency just to close phase.

Apply a minimal project-standard wrap crossfade if needed after finding an appropriate sustained region. Measure exported audio after decimation and leveling. Require `Keys.seamError < 1e-3` plus repeated-playback listening for clicks, gain dips, and conspicuous detail resets.

## 19. Determinism, safety, and output

Identical voice, pitch, velocity, macro values, HOLD, seed context, and model version must produce identical samples under the project determinism contract.

Use fixed traversal conventions, stable band ordering, deterministic FFT/setup behavior, and stable RNG substream IDs. Adding a higher detail band must not reseed existing lower bands.

Clamp or reject malformed/nonfinite inputs according to repository policy. Bound derivative scaling, contact energy, modal gain, and decay. Block DC before normal output conditioning.

Use shared leveling with bounded compensation. A nearly cancelled pickup must not trigger arbitrarily large automatic gain. Include a denominator floor and cap; fall back to a valid contour readout if necessary.

## 20. Mathematical and audio probes

First measure actual behavior, then set production numeric bounds.

Mathematical probes:
- DFT/inverse reconstruction of the sampled contour;
- correct signed-frequency and phase conventions;
- direct projection versus the a_n formula;
- centered c_0 near numerical zero;
- sample-density convergence;
- reference contour closure;
- decreasing full-contour squared reconstruction error for nested untapered frequency sets;
- correct periodic ANGLE endpoints.

Do not transfer the supplied “500 circles, 0.022%” claim into acceptance criteria without reproducing its contour definition, term selection, normalization, and error metric. Measure RMS pointwise error and maximum pointwise error for Rime's own parameterized reference. Distinguish those from geometric nearest-curve distance.

Audio probes:
- determinism, finite internal state, DC, peak, loudness, and decay;
- root tracking across notes and macro combinations;
- spectral progression as detail bands enter;
- angle differences after leveling on normal presets;
- monotonic band-envelope timing for one-shot unfolding;
- high-note and high-EDGE alias behavior against a higher-rate reference;
- unforced body decay;
- classifier outcome, JSON round trip, and exported HOLD seam.

No engine tests have been run while writing this specification.

## 21. Integration and auditions

Implement `Rime.kt`, `RimePatch`, `RimePresets`, `Patches` registration, `siblingPatches`, `SynthKits`, `drumClassFor`, audition generation, tests, and roadmap updates using actual repository conventions.

Persist voice, macros, HOLD, required deterministic contour/model version, and ordinary patch metadata. JSON round trips must preserve rendering behavior.

Keep standard pitched cases out of KICK, SNARE, CLAP, HAT, and TOM. Low LOOP attacks and bright CRUST noise are primary classifier risks; preserve harmonic tails and constrain transient dominance.

Add `generateRimeAudition` or its repository equivalent. Include all voices, five-step macro sweeps, low/middle/high notes, velocities, required interactions, all-high settings, slow unfolds, high-note bandwidth limits, and difficult HOLD cases.

Diagnostic views should show the target contour, truncated contour, and active audio spectrum separately. They are distinct objects once pickup projection and output bandwidth are applied.

## 22. Delivery and acceptance

Round 1: generate the finite contour, validate Fourier conventions, and build a direct-series reference renderer.

Round 2: implement DETAIL, EDGE, ANGLE, the reference pickup, and note-aware bandwidth. Establish a useful pitched core before contact noise.

Round 3: add UNFOLD, contour-driven contact, and BODY. Tune moderate settings before extreme crystalline material.

Round 4: complete both HOLD adaptations and validate exported loops.

Round 5: finish 36 presets, integration, classifier probes, auditions, applicable repository tests, and owner listening. Run `./gradlew --no-daemon test` if it remains the required project gate.

Acceptance requires a recognizable stable note whose internal detail changes audibly; a useful contour-pickup interaction; unfolding that changes source structure; a body that sounds like a resonating object; clean high-register behavior; deterministic safe extremes; and seamless held samples.

Mathematical background: [Fourier Series — Wolfram MathWorld](https://mathworld.wolfram.com/FourierSeries.html), specifically the complex coefficient representation. The acoustic mapping, parameter ranges, voice designs, and implementation choices here are generated engineering proposals. Final accuracy, performance, and sonic thresholds require measurement.

Rime's sonic north star is a smooth vibrating silhouette acquiring increasingly elaborate edges while remaining the same playable instrument.

