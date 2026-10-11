# Murmur — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; release naming checks pending

## 1. Product intent and reference

Murmur is a preset-first pitched engine combining FM articulation with animated vowel-like spectral shapes. Its familiar musical roles are bass, keys, plucks, leads, and choir-like tones. A patch changes its apparent mouth shape while retaining its musical role and requested root.

The Yamaha FS1R provides the reference direction: FM plus formant shaping and animated formant sequences. Manufacturer reference: [FS1R owner's manual](https://usa.yamaha.com/files/download/other_assets/5/333335/FS1RE1.PDF). That is retrieved architectural context; the topology, controls, presets, and values below are original design proposals. Murmur is not a hardware emulation and does not require FS1R ROMs, firmware, presets, or sequence data.

The signature is **pitched FM gesture → mouth-shaped spectral emphasis → optional breath/contact accent → moving vowel contour**. Default patches must be useful without rack effects. No repository implementation was inspected; numerical targets require prototype calibration.

## 2. SnipSnap contract

Follow the supplied edited Gyre contract: deterministic offline mono rendering; 44.1 kHz output through the standard 4× internal path; five timbral macros plus HOLD; excitation-sensitive velocity; shared melodic loudness processing; serialization; presets; MPC export; kit integration; auditions; and pitched classifier guards.

Each render initializes one complete voice at the requested root. No cross-pad state, speech input, sample assets, live polyphony, or linguistic text-to-speech is required. Choral character initially comes from voiced spectrum and restrained detuning, not automatic chords.

## 3. V1 architecture

| System | Initial budget | Purpose |
|---|---:|---|
| Tonal FM core | 4 phase operators | Root-bearing body and key/bass articulation |
| Formant layer | 4–5 parallel bands or equivalent voiced grains | Vowel-like spectral envelope |
| Unvoiced source | One seeded noise source, shaped by the same formant state | Breath and brief consonant-like accents |
| Mouth controller | Small original spectral-target roster and smooth trajectory | Static/moving vocal character |
| Body path | Direct tonal anchor plus bounded spectral tilt/loss | Preserve musical role |
| Output | DC control, decimation, shared leveling | Safe mono export |

A resonant formant bank is a valid V1 simplification. It must act on harmonically rich excitation and interact with source/noise balance; do not claim it reconstructs the FS1R's operator system. Keep a direct root-bearing anchor where needed.

## 4. FM core and EDGE

Use a small acyclic operator-routing roster: parallel carriers with separate modulators, two serial pairs, and one short serial stack. At most one bounded, explicitly delayed feedback connection may be added after stable dry identity is established.

Document whether implementation uses phase modulation or frequency modulation and express modulation index in the appropriate units. EDGE controls index, modulator envelope depth, transient bite, and a restrained feedback amount where present. Low EDGE is rounded; high EDGE introduces metallic/nasal detail while preserving a usable fundamental.

Use mostly integer ratios in normal melodic presets. A few explicit inharmonic ratios may appear in bell/pluck variants. Frequency ratios and routing are patch/voice properties, not hidden random changes on a macro sweep. Scale index and high components near the supported upper note range to control aliasing.

## 5. MOUTH and formant targets

MOUTH follows an ordered path through original vowel-like spectral targets, for example closed hum → rounded oo-like → open ah-like → narrower ee-like. These are timbral approximations, not phonetic accuracy claims.

Each target defines band center, width/Q, gain, and source balance. Interpolate centers/widths in logarithmic frequency space and gains through a smooth calibrated mapping. Do not crossfade independently rendered vowel samples. Keep resonator coefficients stable during motion, preferably using a topology suitable for continuous retuning.

Starting band centers can occupy roughly 250 Hz–5 kHz, with positive bandwidth floors and a maximum Q chosen from probes. Exact targets require listening. Formants stay substantially fixed in absolute frequency as note pitch changes, with restrained pitch-dependent adjustment at extreme registers. Fundamental frequency and mouth shape must remain distinct.

At low notes, multiple harmonics fall under each formant. At high notes, sparse harmonic sampling may weaken vowel identity; compensate source richness or target widths rather than falsely promising uniform vocal realism across the entire MIDI range.

## 6. BREATH and accented onset

BREATH mixes seeded unvoiced energy into the same changing formant envelope while adjusting voiced/unvoiced balance and onset softness. Low values are clear pitched tones. High values become breathier but retain a tonal anchor in ordinary patches.

Optional consonant-like onset is a finite shaped-noise/contact burst with its own short envelope. EDGE determines its sharpness; BREATH determines its audibility. Accent profiles belong to patches. The earlier ACCENT proposal is implemented within these mappings rather than exposing a sixth timbral control.

Noise must follow an explicit gesture and spectral shape. Avoid constant hiss, speech recognition, phoneme strings, or a detached noise track. Proposed onset duration is approximately 5–60 ms, calibrated per voice. Velocity changes energy and bite without causing uncontrolled resonance.

## 7. MOTION

MOTION controls depth and development speed of the mouth trajectory around the MOUTH-selected position. At zero, the formant target is stationary. Low nonzero values cause an audible small contour within ordinary sample lengths. Higher values traverse a wider section of the target path and animate source/breath balance.

Use a smooth attack-to-target trajectory for plucks/keys and a slower compatible cycle or one-way contour for sustained voices. Store the trajectory definition, direction, and reference duration in the patch. Motion changes timbre; it does not select melody notes or become final-output tremolo.

Do not make high MOTION automatically audio-rate or chaotic. A musically useful starting region might cover roughly 100 ms–3 seconds per contour; test actual behavior before setting bounds. Clamp target traversal smoothly at the path ends or use a continuous closed path for cycle presets.

## 8. BODY and envelope

BODY moves from thin/intimate toward rounded/full. Coordinate carrier balance, direct anchor strength, lower-formant width, low/mid support, and upper damping. Avoid replacing the voice with a sub oscillator at the high end.

Voice-specific amplitude/index contours define articulation. Finite mode uses a bounded sustain interval and release. Starting totals: bass/pluck 1–3 seconds, key 2–5, lead/choir 3–6, subject to host limits and existing silence criteria. Finish onset and motion gestures before tail termination.

## 9. Macros and interactions

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| MOUTH | Closed/rounded → open/narrow spectral shapes | .35 | .40 |
| EDGE | Rounded FM → bright metallic/nasal articulation | .35 | .35 |
| BREATH | Clear voiced tone → airy mixed excitation | .15 | .10 |
| MOTION | Static mouth → stronger/faster spectral contour | .30 | .20 |
| BODY | Thin/intimate → rounded/full | .55 | .50 |
| HOLD | Finite gesture → seamless held region | 0 | 0 |

Values are hypotheses; confirm MacroSpec semantics. Required interactions: MOUTH × EDGE, MOUTH × BREATH, MOTION × BREATH, and BODY × MOUTH. All normal voices need useful activity across each timbral macro; a clean bass may use restrained but audible breath rather than disabling it.

## 10. Voices and presets

Proposed enum: `MurmurVoice { BASS, KEY, PLUCK, LEAD, CHOIR, METAL }`.

| Voice | MOUTH | EDGE | BREATH | MOTION | BODY | Character |
|---|---:|---:|---:|---:|---:|---|
| BASS | .25 | .35 | .08 | .25 | .75 | Rounded talking bass |
| KEY | .40 | .40 | .12 | .25 | .55 | Vocal color behind a key strike |
| PLUCK | .35 | .55 | .10 | .35 | .45 | Short articulated spectral gesture |
| LEAD | .55 | .45 | .20 | .45 | .50 | Nasal/open expressive tone |
| CHOIR | .45 | .20 | .30 | .30 | .65 | Restrained humming sustain |
| METAL | .35 | .75 | .15 | .40 | .40 | FM bite softened by mouth shape |

Target 36 curated presets: 8 bass, 8 key/pluck, 8 lead, 6 choir, and 6 metal. Names: Closed Circuit, Round Hum, Mouth Bass, Dust Vowel, Tine Whisper, Short Answer, Open Throat, Pocket Voice, Small Choir, Copper Hum. Original patches only. Curate dry examples before filling quotas.

## 11. HOLD

HOLD sustains voiced energy with a loop-compatible mouth contour. Quantize cyclic motion to whole cycles over the loop period or select a stable static target. Make roughness/noise repeatable over that period; a newly seeded stream each wrap is insufficient.

Converge FM/feedback, envelopes, formant states, noise history, and trajectory state during bounded preroll. Use the existing pitched-loop approach for carrier compatibility; do not coarsely quantize every frequency at the expense of tuning. Apply project-standard wrap handling after selecting a stable region.

Require `Keys.seamError < 1e-3` and listening. Check sustained formants, breath, feedback, and high EDGE. A loop-only export contains settled sustain; the original consonant-like onset is included only if attack-plus-loop is supported.

## 12. Integration and numerical requirements

Use `Dsp.seedFor` or current seed infrastructure. Identical input/configuration must reproduce identical audio and trajectories. Bound index, feedback, Q, source level, and coefficient speed; prevent non-finite state, DC, denormals, zippering, and aliasing. Oversampling does not eliminate unrestricted FM sidebands; validate pitch-dependent bandwidth management.

Implement `Murmur.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, audition generation, tests, and roadmap using actual interfaces. Patch routing, ratios, formant/trajectory definitions, and envelopes must survive JSON round-trip following sibling conventions. Use shared `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain conventions.

Standard pitched cases must avoid KICK/SNARE/CLAP/HAT/TOM classification. Breath-heavy attacks and low bass transients are risks; preserve root-bearing body rather than bypassing guards.

## 13. Build and acceptance

1. Establish FM bass/key identity, pitch, raw level, and aliasing probes.
2. Add static mouth targets, breath/onsets, BODY, and interaction tuning.
3. Add trajectories, voices, preset auditions, and extreme bounds.
4. Complete HOLD and integration; run current required checks, with supplied baseline `./gradlew --no-daemon test`.

Probe peak/RMS, DC, pitch confidence, spectral bands, decay, aliasing, cost, classifier, macro distances, determinism, serialization, and seams before setting numeric acceptance bounds. Audition all voices at low/middle/high notes and velocities, five-point macro sweeps, interaction grids, and all-high cases.

Acceptance: useful dry defaults; root-preserving mouth movement; distinct FM articulation; breath integrated with formants; motion audible without output tremolo; finite deterministic extremes; patch/routing checks; and loop metric plus listening success. The owner approves sonic identity before preset expansion.

## 14. Deferred scope

Defer hardware reconstruction, eight-operator compatibility, factory data imports, speech/text input, literal phonetic accuracy, stereo, automatic chords, and live performance UI. Confirm supported note range, host duration, velocity, HOLD format, APIs, and working name. Murmur succeeds when a familiar musical patch can change its mouth shape without losing its role.
