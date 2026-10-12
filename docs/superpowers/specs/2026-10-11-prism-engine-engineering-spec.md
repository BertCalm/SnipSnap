# Prism — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; release naming checks pending

## 1. Intent and reference

Prism is a preset-first additive engine: familiar keys, organs, plucks, basses, and sustained textures built from harmonics that develop individually. Its signature is a stable pitched body whose partial groups gather, separate, and decay at different speeds.

The Kawai K5000 supplies the reference direction of additive synthesis, harmonic envelopes, and formant filtering. Manufacturer reference: [K5000W manual](https://kawaius.com/wp-content/uploads/2019/04/Kawai-K5000W-Synthesizer-Manual.pdf). These are retrieved architectural facts; the reduced model and musical mappings below are original proposals. Prism is not a reconstruction and uses no factory sound data.

No repository was inspected. Numeric budgets and defaults require prototype calibration. The supplied edited Gyre spec defines integration conventions, not a requirement to use physical modeling.

## 2. SnipSnap contract and budget

Deterministic offline mono, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation, pitched kit integration, patches, presets, auditions, and shared loudness processing. Each render starts a fresh note; no samples or cross-pad state.

Start with 32 sinusoidal partials, expandable to 64 only after audible benefit and profiling. Each has frequency ratio, amplitude contour, deterministic phase, and loss. Use 4–6 logical spectral groups, one optional weak noise attack, one spectral mask, and output conditioning. Omit partials outside the usable bandwidth before synthesis.

## 3. Additive core

For partial `n`, synthesize a phase-continuous sinusoid at `f0 * ratio[n]` and multiply by its time-varying amplitude. Sum with calibrated headroom. Ordinary voices use integer ratios; bell-like exceptions use an explicit bounded inharmonic map. Preserve a root-bearing partial in normal cases.

Spectral templates and envelope templates are patch properties. Interpolate amplitudes and time constants rather than crossfading finished samples. A template may contain a rounded attack group, a bright short transient group, and a slower middle-band tail. V1 does not require hundreds of exposed envelope editors.

## 4. GRAIN

GRAIN changes how partials gather into spectral groups. Move from smooth contiguous energy toward more separated bands, alternating partial emphasis, and sparse bright clusters. It describes harmonic texture, not granular audio sampling.

Use continuous weighting masks with a small nonzero floor for important root/body components. Avoid hard partial activation steps, random frequency reassignment, or automatic melody/chord changes. Different voices may use different masks, but the audible role must stay recognizable.

Normalize or bound the mask's aggregate energy using the actual template weights. Increasing sparsity must not unintentionally add gain or produce silent regions. A sparse spectrum can be deliberately quieter internally, but shared output leveling cannot be the only safeguard.

## 5. DRIFT

DRIFT controls differences in attack, decay, and spectral-motion timing between groups. At zero they develop together. Increasing DRIFT makes upper sheen, middle body, and lower anchor unfold at different rates, with smooth deterministic offsets.

The primary drift is in amplitudes/timing, not pitch. Optional small pitch movement is explicitly bounded and subordinate. Slow motion must be audible within normal sample lengths; do not place all change beyond the exported tail.

For finite keys/plucks, use differing group decay and delayed blooms. For sustained voices, use restrained periodic group evolution. Store profiles and reference durations in the patch. No free-running random walk.

## 6. SHAPE spectral envelope

SHAPE moves through original broad spectral envelopes: rounded body, hollow middle, concentrated nasal band, and open upper sheen. Apply the mask to partial amplitudes before summation; a post-filter alone cannot supply independent harmonic behavior.

Interpolate masks continuously in log-frequency space. Distinguish absolute-frequency spectral features from note-relative harmonic structure. Use a calibrated blend so a template's musical identity survives register changes. SHAPE need not imitate vowels or repeat Murmur's mouth target path.

Bound peak mask gain and smooth control updates. Wider masks may be necessary at high pitches where fewer partials sample the envelope. Do not boost absent harmonics beyond bandwidth limits.

## 7. STRIKE and BODY

STRIKE changes excitation character through partial attack times, transient-group energy, and early damping: soft rounded onset through sharply articulated harmonic strike. Velocity scales energy and brightness within this character. Any noise transient is short, seeded, and subordinate to pitch.

BODY controls lower/middle harmonic reinforcement, spectral density, and loss. Low values are thin/glassy; high values are rounded/woody or organ-like depending on template. BODY must not merely apply a final gain or duplicate SHAPE.

## 8. Macros

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| GRAIN | Smooth harmonic body → separated clusters | .35 | .30 |
| DRIFT | Unified development → independently evolving groups | .30 | .20 |
| SHAPE | Rounded → hollow/nasal/open spectral path | .40 | .40 |
| STRIKE | Soft onset → bright defined strike | .40 | .40 |
| BODY | Thin/glassy → rounded/dense | .55 | .50 |
| HOLD | Finite note → seamless held region | 0 | 0 |

Defaults are hypotheses. Confirm MacroSpec semantics. Required interactions: GRAIN × SHAPE, DRIFT × STRIKE, BODY × GRAIN, and DRIFT × SHAPE. Every control must remain useful across normal voices; sustained voices still have an onset, and low DRIFT must produce a detectable early development change.

## 9. Voices and presets

Proposed enum: `PrismVoice { KEY, PLUCK, ORGAN, BASS, GLASS, VEIL }`.

| Voice | GRAIN | DRIFT | SHAPE | STRIKE | BODY | Role |
|---|---:|---:|---:|---:|---:|---|
| KEY | .30 | .30 | .40 | .45 | .60 | Rounded harmonic key |
| PLUCK | .40 | .25 | .45 | .65 | .45 | Clear short articulation |
| ORGAN | .25 | .20 | .35 | .30 | .65 | Solid harmonic sustain |
| BASS | .20 | .20 | .30 | .40 | .80 | Root-bearing low body |
| GLASS | .65 | .45 | .60 | .50 | .30 | Sparse bright resonance |
| VEIL | .45 | .65 | .50 | .20 | .55 | Slowly separating harmonic texture |

Target 36 presets: 10 keys/plucks, 6 organs, 6 basses, 8 sustained textures, 6 glass/bell variants. Names: Soft Facets, Wooden Spectrum, Partial Tines, Hollow Register, Root Glass, Slow Separation, Amber Veil, Small Prism. Prioritize playable dry patches; do not fill quotas with tiny variants.

## 10. Duration and HOLD

Finite mode runs the selected attack/group contours, then decays to the existing silence criterion within host limits. Start around 1–4 seconds for keys/plucks and 3–7 for slow textures. Avoid clipping delayed harmonic blooms at the tail boundary.

HOLD settles into a stable sustain or a periodic partial-envelope pattern. Quantize group motion to integer cycles over the loop period, with deterministic periodic variation. STRIKE remains relevant to the attack and settling character, but loop-only export does not include that onset.

Carrier phases, amplitudes, motion state, and any processing must be loop-compatible. Use the established pitched-loop approach and bounded preroll; phase alignment of the slow envelope alone is insufficient. Avoid coarse carrier-frequency quantization that detunes the root. Apply project-standard wrapping and verify `Keys.seamError < 1e-3` plus listening, especially sparse high partials.

## 11. Numerical and integration requirements

Bound sum amplitude for worst-case coherent phases, mask gain, envelope slopes, and optional nonlinear processing. Prevent non-finite state, DC, denormals, zippering, and unintended aliasing. Omit out-of-band components and band-limit before decimation. Profile actual cost rather than assuming offline means unlimited partial counts.

Seed phase/variation through `Dsp.seedFor` or current infrastructure. Identical voice/note/macros/velocity/templates/patch/seed reproduce sample-identical output. Use shared `MELODIC_LOUDNESS_TARGET`; audition raw and normalized signals.

Implement `Prism.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap. Serialize template IDs, ratio overrides, group profiles, envelopes, and loop configuration as needed using sibling conventions. Standard melodic cases must avoid KICK/SNARE/CLAP/HAT/TOM classifications. Short low strikes and sparse noisy attacks need explicit probes.

## 12. Build and acceptance

1. Establish harmonic core, key/bass templates, pitch, and level/cost probes.
2. Add grouped contours, GRAIN, SHAPE, STRIKE, and BODY; approve dry identity.
3. Add DRIFT, full voices, extreme masks, and curated presets.
4. Complete HOLD and integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe peak/RMS, DC, pitch, spectral groups, decay, bandwidth, cost, determinism, macro distance, classifier, serialization, and seam before choosing bounds. Audition all voices across register/velocity, five-point sweeps, interaction grids, all-high cases, and HOLD extremes.

Acceptance: useful keys/bass without FX; distinct independent partial development; GRAIN changes spectral organization rather than level; SHAPE changes spectral envelope without accidental transposition; bounded deterministic extremes; valid serialization/routing; and loop metric/listening success. Owner sonic approval precedes preset expansion.

## 13. Deferred scope

Defer K5000 compatibility/data imports, full harmonic editor UI, arbitrary partial counts, stereo, live polyphony, automatic chords, and external samples. Confirm supported range, duration, velocity, HOLD format, APIs, and name. Prism succeeds when familiar notes reveal harmonics with different lifetimes.
