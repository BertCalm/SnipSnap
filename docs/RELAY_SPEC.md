# Relay — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; naming checks pending

## 1. Product intent and reference

Relay is a preset-first pitched engine that makes short evolving phrases inside one note. A muted pluck may open into an organ-like body, then leave a bell tail. Timbre order and rhythmic emphasis are separately controllable.

The Korg Wavestation provides the documented direction of wave sequencing and vector blending: [manufacturer overview](https://www.korg.com/us/products/software/korg_iwavestation/). Relay's sources, controls, sequence rules, and presets are original proposals, not a reconstruction or import of factory PCM/program data.

Its signature is **ordered timbral handoffs with independently placed accents**. No repository was inspected; budgets and defaults require calibration. The supplied edited Gyre spec defines SnipSnap integration conventions.

## 2. Contract and architecture

Deterministic offline mono, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation, shared melodic loudness, patches, presets, kits, auditions, MPC export, and pitched classifier guards. Each render starts fresh; no external samples or shared pad state.

V1 uses four original source roles: rounded pluck, sustained harmonic/organ body, bright bell/FM detail, and soft filtered tone. Implement up to four source states, an eight-slot maximum phrase, a vector-weight controller, accent scheduler, amplitude/filter contours, and output conditioning.

All ordinary sequence sources share the requested root. Sequence steps change timbre, not melody. Explicit octave components are patch properties and subordinate to the root.

## 3. Sequence representation and ORDER

Patch fields include source definitions, slot source IDs, dwell durations, transition durations, accent pattern, phrase tempo, and phrase length. Every slot has positive duration. Start with four slots; permit eight only for deliberately longer phrases.

ORDER moves through a small roster of source-order permutations using continuous morphing between their source-weight schedules. Derive the schedules before rendering and blend weights at corresponding phrase times. Do not abruptly reorder an active sequence, jump oscillator state, or change event counts mid-render.

The alternatives retain the same phrase clock. ORDER is distinct from source brightness and from tempo. At endpoints a listener should recognize different handoff order; intermediate settings should form useful overlapping transitions.

## 4. PULSE: emphasis independent of order

PULSE changes accent contrast, attack emphasis, and bounded rhythmic gating of the timbral transitions. It does not change the source-order list or requested note. Low settings are flowing; high settings reveal stronger rhythmic figures.

Use a serialized accent roster with fixed phase relative to the phrase. Accents may emphasize an existing source or give it a finite envelope retrigger, but must not trigger unrestricted extra notes. Separate overall phrase tempo from PULSE intensity.

Suggested reference tempo is 100 BPM with a four-beat phrase. Tempo/length are patch settings, not additional timbral macros. Pulse timing is part of the rendered sample and scales in seconds under external sample transposition; it cannot remain host-tempo-locked afterward.

## 5. BLEND and vector transitions

BLEND controls overlap/transition softness: short clear handoffs through broad mixed-source passages. Smooth gains without zero-time switches. Maintain calibrated level when sources are correlated; equal-power weights alone do not guarantee equal perceived loudness.

For broad blends, several sources may remain active; each keeps its own phase/envelope history. For tight transitions, muted sources may pause only if restart handling is click-free and deterministic. Prefer continuous state in V1.

Transitions operate on generated source signals, not completed exported samples. The primary novelty is temporal arrangement; physical feedback coupling is not required.

## 6. COLOR and LENGTH

COLOR coordinates source brightness and shared filter response while preserving differences among the four roles. Avoid making every source identical at the dark end. Keep bright bell/noise detail subordinate to tonal body in normal presets.

LENGTH changes the finite phrase's envelope/gate duration and terminal tail, not the tempo or source order. Start with a partial-to-full phrase sustain window, preserving smooth release. Precompute the finite schedule so release does not cut a required transition with a discontinuity. Host limits may require a shorter documented phrase configuration.

Velocity scales excitation/accent energy and initial brightness. Inspect raw and normalized renders so shared leveling does not hide articulation behavior.

## 7. Macros and interactions

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| ORDER | First source-order schedule → alternate schedules | .30 | .30 |
| PULSE | Flowing contour → stronger rhythmic emphasis | .35 | .30 |
| BLEND | Defined handoffs → broad overlap | .50 | .50 |
| COLOR | Dark/rounded → bright/detailed | .50 | .50 |
| LENGTH | Short gesture → full phrase and longer tail | .55 | .50 |
| HOLD | Finite phrase → repeating seamless phrase | 0 | 0 |

Defaults are hypotheses; verify MacroSpec semantics. Required interactions: ORDER × PULSE, ORDER × BLEND, BLEND × COLOR, and PULSE × LENGTH. Every timbral control must be useful across normal voices.

## 8. Voices and presets

Proposed enum: `RelayVoice { KEY, BASS, PLUCK, ORGAN, CHIME, FLOW }`.

| Voice | ORDER | PULSE | BLEND | COLOR | LENGTH | Identity |
|---|---:|---:|---:|---:|---:|---|
| KEY | .25 | .30 | .45 | .50 | .50 | Key attack opening into body |
| BASS | .20 | .45 | .40 | .35 | .45 | Rhythmic low timbral phrase |
| PLUCK | .40 | .60 | .25 | .60 | .35 | Clear short handoffs |
| ORGAN | .35 | .30 | .65 | .45 | .65 | Rounded changing sustain |
| CHIME | .55 | .40 | .50 | .65 | .60 | Bell detail with tonal support |
| FLOW | .45 | .15 | .80 | .50 | .80 | Broad evolving vector tone |

Voices retain multiple source roles but bias their balance and contours. Target 36 curated presets: 8 keys, 6 bass, 6 plucks, 6 organ, 4 chime, 6 flowing textures. Names: First Handoff, Organ Afterglow, Bell Exit, Pocket Relay, Muted Passage, Soft Exchange, Four Corners. Approve dry identity before filling quotas.

## 9. HOLD and duration

Finite mode completes the selected gate/phrase and renders the tail, initially around 2–6 seconds within host limits. HOLD repeats a phrase with explicit excitation and periodic schedules.

Make order weights, accents, vector paths, source envelopes, phase/modulation, and processing compatible with the loop. Phrase closure alone does not guarantee source-state closure. Use bounded preroll, periodic seeded variation, and the existing pitched-loop method; avoid root detuning from coarse frequency quantization.

Apply project-standard wrap handling after selecting a stable region. Verify no doubled accents or broken handoffs and require `Keys.seamError < 1e-3` plus listening. LENGTH in HOLD affects gate/articulation within the repeating phrase rather than termination. Loop-only export contains the repeating region; initial gesture belongs to attack-plus-loop only if supported.

## 10. Integration and safety

Use seeded infrastructure such as `Dsp.seedFor`; identical complete inputs reproduce identical audio and event timing. Band-limit oscillators/FM and nonlinear processing, prevent DC/denormals/non-finite state, and bound mix, filter resonance, event density, and gain compensation. No limiter may conceal runaway stages.

Implement `Relay.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap using verified interfaces. Serialize sources, schedules, tempo, transitions, accent profiles, and loop configuration using sibling conventions. Use shared `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain. Standard pitched cases must avoid KICK/SNARE/CLAP/HAT/TOM classification, especially strong pulses and bright chime transitions.

## 11. Build and acceptance

1. Build source roles, static blends, tuning, and raw level/bandwidth probes.
2. Add ordered handoffs and independent accents; approve moderate dry identity.
3. Add continuous schedule morphing, voices, interaction tuning, and presets.
4. Complete HOLD/integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe pitch, peak/RMS, DC, decay, spectral order, event timing, aliasing, cost, macro distances, determinism, classifier, serialization, and seam before choosing bounds. Audition voices across note/velocity, five-point sweeps, interaction grids, all-high settings, tempo extremes, and difficult loops.

Acceptance: audible source-order change independent of accents; useful continuous blending; requested root preserved; clear dry preset roles; complete finite tails; deterministic bounded extremes; serialization/routing checks; and seam metric/listening success. Owner sonic approval precedes preset expansion.

## 12. Deferred scope

Defer Wavestation data compatibility, melody sequencing, external PCM imports, stereo, live vector joystick UI, and automatic host tempo tracking after export. Confirm range, duration, loop format, APIs, and name. Relay succeeds when one note becomes a compact playable timbral phrase.
