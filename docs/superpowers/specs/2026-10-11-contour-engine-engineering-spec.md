# Contour — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; release naming checks pending

## 1. Intent and reference

Contour is a preset-first wavetable engine for solid basses, evolving keys, cutting leads, short digital plucks, and warm moving sustains. Its twist is a stable body layer paired with an edge layer that follows a related but different spectral path.

The original Waldorf Microwave supplies the reference direction of wavetable synthesis combined with analog filtering. Manufacturer context: [Waldorf Microwave overview](https://waldorfmusic.com/microwave/). This retrieved reference informs the direction; the architecture and presets below are original proposals. Contour uses generated wavetables and a chosen filter model, not copied factory assets or claimed circuit emulation.

No repository was inspected. Numeric targets require calibration. The supplied edited Gyre spec provides SnipSnap integration conventions; physical modeling is not a requirement for this engine.

## 2. Contract and budget

Deterministic offline mono rendering, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, excitation-sensitive velocity, patches, presets, MPC export, kits, auditions, and shared melodic loudness processing. Each render starts fresh; no external samples or persistent cross-pad state.

V1: two wavetable oscillators, optional root/sub anchor, two related scan coordinates, shared filter with bounded drive, amplitude/filter envelopes, and output conditioning. Start with 16–32 frames per original table and a small 8–12-table roster. Use reusable band-limited table infrastructure where possible.

## 3. Original wavetable library

Generate tables from harmonic recipes: rounded pulse evolution, saw-to-hollow progression, odd/even spectral shifts, bright digital bells, narrow harmonic clusters, and smooth formant-like shapes. Do not reuse Murmur's vocal trajectory as the default identity.

Precompute suitable pitch-band/mipmap versions and interpolate both phase and frame position. Normalize tables with a documented policy that preserves intended frame dynamics without large scan jumps. Continuous scans must not click at frame boundaries or table wrap.

Never interpolate unrelated topologies by changing table IDs abruptly during a ringing note. Table selection is a patch property; macros move continuously within the selected pair.

## 4. Body and edge layers

The body oscillator carries the requested root with a rounded spectrum. The edge oscillator adds changing detail at the same root or an explicit patch interval, defaulting to unison. Small deterministic detuning may add motion but must not erase root recognition or create fragile mono cancellation.

Give each layer its own scan offset, envelope projection, and filter-input level. They share the selected spectral relationship rather than playing independent finished patches. A sub anchor is optional and subordinate; it cannot be the sole identity of BASS.

Contour's defining behavior must be demonstrated at fixed output level: the main body stays recognizable while the edge moves through a different series of overtones.

## 5. SCAN

SCAN sets the base spectral position within the selected table pair. Low through high follows a calibrated useful progression, not a promise that every table gets monotonically brighter. Preset metadata should describe the direction when unusual.

At MOTION=0, SCAN still changes timbre clearly. Preserve useful energy and pitch at every normal position. Smooth scan coordinates and interpolate band-limited frames. Scale bandwidth appropriately with note pitch.

## 6. OFFSET

OFFSET sets the edge layer's displacement from the body's spectral position, plus a bounded difference in trajectory response. Low settings keep the layers close; high settings create a stronger distinction in contour. The knob does not primarily set pitch detuning or stereo width.

Map the two positions into valid table ranges through smooth clamping or cyclic coordinates for genuinely closed tables. Avoid reflected-path corners that create derivative discontinuities. A bounded mix adjustment may keep the body dominant as separation rises, but must not mute the edge and make the control ineffective.

## 7. MOTION

MOTION controls scan travel depth and speed. Finite plucks/keys use a defined attack-to-rest spectral contour; sustains use a slow cycle or a longer evolving gesture. The edge follows with a patch-defined lag, opposite direction, or different contour shape.

At zero, both scans are stationary. Low nonzero values must change the spectrum within ordinary render lengths. Start with contours around 100 ms–3 seconds; higher settings can produce faster digital movement after moderate identity works. No mandatory audio-rate scanning in V1.

Motion changes oscillator spectra before filtering, not merely final gain. It must remain audible with the filter open and rack FX removed.

## 8. FILTER and DRIVE

FILTER coordinates cutoff, restrained resonance, and envelope amount. Choose a stable low-pass model with a useful warm response; optional band-pass/hollow variants belong to patches. State clearly whether it is a generic filter or a measured circuit model. V1 uses a generic chosen model.

DRIVE changes pre-filter excitation level and bounded saturation, with compensation to keep loudness comparable. Moderate drive adds weight/edge; high drive may become rough but must retain pitch. Avoid excessive low-frequency intermodulation when the optional sub is active.

Implement nonlinear stages on the oversampled path, smooth parameters, and bound resonance. No limiter may disguise filter runaway. FILTER and DRIVE need independent audible roles: opening the filter must not simply substitute for increasing drive.

## 9. Macros

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| SCAN | Beginning → end of useful spectral path | .35 | .40 |
| OFFSET | Related layers → separated spectral perspectives | .30 | .25 |
| MOTION | Static spectra → deeper/faster related travel | .30 | .20 |
| FILTER | Dark/closed → open/brighter response | .55 | .55 |
| DRIVE | Clean → weighted/gritty excitation | .25 | .00 |
| HOLD | Finite gesture → seamless sustained region | 0 | 0 |

Defaults are hypotheses; confirm MacroSpec semantics. Required interactions: SCAN × OFFSET, OFFSET × MOTION, SCAN × FILTER, and FILTER × DRIVE. Every normal voice must demonstrate all five controls without requiring maximum settings.

## 10. Voices and presets

Proposed enum: `ContourVoice { BASS, KEY, PLUCK, LEAD, HOLLOW, SWELL }`.

| Voice | SCAN | OFFSET | MOTION | FILTER | DRIVE | Identity |
|---|---:|---:|---:|---:|---:|---|
| BASS | .25 | .20 | .25 | .40 | .35 | Solid body with changing bite |
| KEY | .35 | .35 | .30 | .55 | .20 | Defined attack and evolving edge |
| PLUCK | .40 | .40 | .45 | .65 | .25 | Short digital articulation |
| LEAD | .45 | .35 | .40 | .70 | .30 | Cutting related-layer lead |
| HOLLOW | .55 | .50 | .30 | .55 | .20 | Nasal/hollow spectral contrast |
| SWELL | .35 | .45 | .55 | .60 | .15 | Warm sustained scan |

Target 36 original presets: 10 bass, 8 keys/plucks, 8 leads, 6 swells, 4 hollow textures. Names: Rounded Edge, Moving Corner, Dark Table, Bright Margin, Hollow Step, Copper Scan, Twin Outline, Soft Digital, Long Contour. Cover materially different table pairs/envelopes rather than small macro variants.

## 11. Envelopes and HOLD

Finite mode uses voice-defined amplitude/filter contours and a bounded sustain interval. Velocity changes attack energy, filter motion, and spectral emphasis. Start around 1–3 seconds for bass/pluck, 2–5 for key/lead, and 3–7 for swell within host limits. End after excitation and tail decay using existing conventions.

HOLD converges source/filter/envelope and scan states during bounded preroll. Quantize scan cycles and layer lag relationships to a compatible loop period; static held patches need no forced scan motion. Deterministic drift, if used, must be periodic.

Use the established pitched-loop method for oscillators and detuned pairs. Do not detune the requested root to force a coarse integer-cycle fit. Apply project-standard crossfade after selecting a stable region. Verify `Keys.seamError < 1e-3` plus repeated-loop listening for fast scans, separated layers, high drive, and resonance.

Loop-only export represents settled sustain. Initial pluck/key attack belongs to attack-plus-loop only where the host supports it.

## 12. Safety and integration

Bound oscillator mix, scan rates, filter resonance, saturation, and parameter derivatives. Prevent non-finite state, DC, denormals, zippering, and unintended aliasing. Dynamic wavetable scanning and nonlinear filtering both need bandwidth checks; oversampling alone is insufficient. Share immutable tables across renders without sharing transient state.

Use `Dsp.seedFor` or current infrastructure for phase/variation. Identical inputs, table pair, trajectories, envelopes, and seed context reproduce identical samples. Shared `MELODIC_LOUDNESS_TARGET` follows generation; inspect raw and normalized auditions.

Implement `Contour.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap using current interfaces. Serialize table IDs, layer ratios/offset relation, trajectories, filter type, envelopes, and loop configuration as needed using sibling patch conventions. Standard melodic cases must avoid KICK/SNARE/CLAP/HAT/TOM classification; keep low bass and bright plucks clearly tonal.

## 13. Build and acceptance

1. Build original tables, band-limited interpolation, bass/key patches, and pitch/level probes.
2. Add related layers, OFFSET, scan motion, and approved moderate filter/drive identity.
3. Complete voices, macro interactions, extreme bounds, and curated preset auditions.
4. Complete HOLD and integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe pitch, peak/RMS, DC, spectral movement, decay, aliasing, cost, determinism, macro activity, classifier, serialization, and seam before fixing numeric bounds. Audition all voices across note/velocity, five-point sweeps, required interactions, all-high combinations, and HOLD extremes.

Diagnostic renders compare body-only, edge-only, combined, frozen scans, open filter, and clean drive at matched level. Acceptance: useful dry presets; stable root/body during edge movement; distinct SCAN/OFFSET/MOTION roles; useful filter/drive; continuous frame travel; finite deterministic extremes; patch/routing success; loop metric and listening success. Owner sonic approval precedes preset expansion.

## 14. Deferred scope

Defer hardware/circuit reconstruction, factory wavetable imports, arbitrary table editor UI, stereo, live polyphony, automatic chords, and external samples. Confirm supported register, velocity, duration, loop format, APIs, and name. Contour succeeds when a dependable musical body carries a distinctly moving digital edge.

## As built: EDGE response review, 2026-10-23

The Luthier review found that EDGE's source gain could be hidden by its own
resting envelope, especially for held Bright Margin and Soft Digital. Fresh
recipes now save `edgeResponse: FOREGROUND`, which blends the original projection
toward one in proportion to EDGE, alongside the existing layer gain. This adds
no distortion, filter bypass or loop state. Missing/null response fields select
the byte-compatible legacy law; model number and phase seeds remain unchanged.
The new midpoint intentionally differs from legacy. The focused
`generateContourEdgeAudition` task exports 180 finite/held comparisons and exact
recipes. Measurements, rationale and remaining listening limits are recorded in
[the EDGE review](../../CONTOUR_EDGE_REVIEW.md). User listening approval remains
the sonic gate.
