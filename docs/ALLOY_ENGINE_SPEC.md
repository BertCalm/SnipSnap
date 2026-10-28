# Alloy — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; naming checks pending

## 1. Intent and reference

Alloy is a preset-first hybrid attack/FM engine. Original synthesized wooden knocks, tine strikes, breathy bursts, and string-like attacks combine with sustained tonal bodies. FUSE moves from audible layering toward the attack directly modulating the tonal source.

The Yamaha SY77 supplies the documented reference of AWM2/AFM and PCM participation in its RCM system: [manufacturer history](https://usa.yamaha.com/products/contents/music_production/synth_50th/history/chapter003.html). Alloy's generated fragments and reduced modulation model are proposals, not hardware reconstruction or copied sample/operator data.

Its signature is **a recognizable attack that changes the tonal body as it enters it**. No repository was inspected; all values require calibration. Use the supplied edited Gyre integration contract.

## 2. Contract and V1 architecture

Deterministic offline mono, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation, shared melodic loudness, patches, presets, kits, auditions, MPC export, and pitched classifier guards. Fresh render state, no external assets.

Start with a four-operator phase-modulation core, one synthesized attack generator, an attack-conditioned modulation port, direct attack path, one bounded filter/envelope stage, and output conditioning. Specify actual phase/frequency modulation units in implementation.

The attack generator uses a small modal strike, pluck/waveguide, or shaped noise/tonal burst depending on patch. It renders at the target note into the current source path; cached original generated fragments are permissible only if they preserve deterministic pitch/velocity behavior and introduce no external asset dependency.

## 3. Attack identity and MATERIAL

MATERIAL traverses a useful continuous roster of attack projections: rounded wood → tine/string detail → breathy/brassy contact. Blend excitation parameters and overlapping generator weights inside one render. Source ratios and losses are explicit patch data.

Keep attack energy finite and normalize its modulation projection with a calibrated reference scale, not instantaneous peak normalization that exaggerates quiet tails. Every attack supplies both a direct audible component and, at nonzero FUSE, a modulation contribution.

A weak direct reference may remain at high FUSE, but the difference must be heard in the tonal body rather than only in a changing attack mix.

## 4. FUSE: layering to interaction

One proposed formulation is `carrier = sin(carrierPhase + conventionalPM + fuseDepth * conditionedAttack)`. This is phase modulation by a generated transient, not a claim of literal convolution or faithful RCM emulation.

Condition the attack by removing DC, bounding amplitude/slope, and limiting usable bandwidth. Define fuseDepth in radians and derive its supported maximum from aliasing/pitch probes. Do not inject arbitrary unbounded audio into an oscillator's frequency integrator.

As FUSE rises, increase transient modulation while reducing the detached direct layer modestly. Preserve body/root support. A source-ablation render with attack modulation removed must eliminate the fused timbral behavior even if the direct attack remains audible.

V1 fusion is primarily transient. It may leave an evolving index/filter contour but should not create a hidden second sustained sequencer or feedback loop.

## 5. EDGE, BODY, and LENGTH

EDGE changes conventional FM/PM index, attack hardness, and brightness. It is distinct from FUSE: EDGE brightens an ordinary tonal patch even with FUSE=0; FUSE introduces the selected attack's specific modulation structure.

BODY controls carrier balance, lower harmonic support, filter loading/tilt, and decay density. Low is thin/wiry, high rounded/full. Do not replace every high-BODY patch with the same sub bass.

LENGTH changes finite sustain window, modulator decay, and release. Coordinate attack modulation decay without stretching all attack fragments into sustained noise. Proposed finite totals are 1–4 seconds for bass/pluck/key and 3–6 for leads/soft sustains within host limits.

Velocity scales attack energy, modulation work/index, and carrier-envelope intensity inside bounds. Inspect raw and leveled renders. Main pitch and material color remain distinct; attack ratios may include subordinate octave modes but must not create accidental melody notes.

## 6. Macros

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| MATERIAL | Wood → tine/string → breath/contact | .35 | .35 |
| FUSE | Layered attack → attack-driven tonal modulation | .40 | .30 |
| EDGE | Rounded → bright/metallic articulation | .40 | .40 |
| BODY | Thin/wiry → rounded/dense | .60 | .50 |
| LENGTH | Tight gesture → sustained phrase | .45 | .45 |
| HOLD | Finite note → seamless held region | 0 | 0 |

Defaults are hypotheses; confirm MacroSpec semantics. Required interactions: MATERIAL × FUSE, FUSE × EDGE, BODY × EDGE, and FUSE × LENGTH. Useful activity is required across normal voices without maximum settings.

## 7. Voices and presets

Proposed enum: `AlloyVoice { BASS, KEY, PLUCK, BRASS, STRING, SOFT }`.

| Voice | MATERIAL | FUSE | EDGE | BODY | LENGTH | Identity |
|---|---:|---:|---:|---:|---:|---|
| BASS | .25 | .35 | .35 | .80 | .35 | Rounded struck/FM low tone |
| KEY | .45 | .45 | .45 | .60 | .55 | Attack-inflected electric key |
| PLUCK | .35 | .55 | .60 | .45 | .25 | Defined fused strike |
| BRASS | .80 | .40 | .50 | .60 | .55 | Breath/contact opening into tone |
| STRING | .55 | .40 | .35 | .55 | .60 | Wiry onset and tonal sustain |
| SOFT | .40 | .25 | .20 | .65 | .75 | Gentle evolving hybrid |

Target 36 original presets: 10 bass, 10 keys/plucks, 6 brass-like leads, 4 string-like, 6 soft sustains. Names: Wooden Current, Tine Alloy, Rubber Wire, Breath Metal, Soft Forge, Muted Fusion, Warm Circuit. Distinct material/core combinations matter more than meeting a quota.

## 8. HOLD

A finite attack cannot remain a meaningful fusion source forever. For held mode, derive a restrained periodic low-energy excitation from the selected material and feed the same conditioned modulation port. Do not repeatedly replay the full initial attack by default. FUSE and MATERIAL remain audible in settled sustain.

Patch-defined held excitation may use a smooth cyclic modal/tonal component or shaped periodic roughness. Bound drive and avoid uncontrolled hiss. Make its period compatible with the loop, and converge oscillator, filter, modulator, source, and envelope state during bounded preroll.

Use the existing pitched-loop approach and periodic seeds; avoid coarse carrier detuning. Apply project-standard wrap handling after selecting a settled region and require `Keys.seamError < 1e-3` plus listening. Initial attack belongs to attack-plus-loop only if supported; loop-only exports contain the modified held excitation, which must be documented.

## 9. Safety and integration

Bound modulation index, conditioned attack, filter resonance, source mix, and coefficient rates. Scale bandwidth/index at high notes; 4× oversampling alone does not eliminate FM/PM sidebands. Prevent DC, non-finite state, denormals, aliasing, and clicks. A limiter cannot hide runaway internals.

Use `Dsp.seedFor` or current infrastructure; identical complete inputs reproduce samples. Shared `MELODIC_LOUDNESS_TARGET` follows synthesis. Implement `Alloy.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap using current interfaces. Serialize generator/material/routing/envelope/held configuration using sibling conventions.

Standard pitched cases avoid KICK/SNARE/CLAP/HAT/TOM classification. Short wood attacks and breath bursts need probes; preserve pitched body without bypassing guards. Core identity must work with empty FX.

## 10. Build and acceptance

1. Establish dry FM body and original attack generators; probe pitch, level, and bandwidth.
2. Add direct layering, conditioned modulation, and FUSE ablation comparisons.
3. Tune macros, voices, extremes, and presets after dry sonic approval.
4. Complete held excitation/HOLD and integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe pitch, peak/RMS, DC, decay, spectral difference, aliasing, cost, determinism, macro activity, classifier, serialization, and seam. Audition voice/register/velocity coverage, five-point sweeps, interaction grids, all-high settings, and difficult HOLD combinations before fixing numeric bounds.

Acceptance: useful dry presets; MATERIAL-specific fused timbre; FUSE interaction survives matched-level comparison; EDGE remains distinct; root preserved; meaningful held fusion; deterministic bounded extremes; patch/routing checks; and seam metric/listening success. Owner approval precedes preset expansion.

## 11. Deferred scope

Defer SY77 data compatibility, factory PCM imports, unrestricted audio-rate modulation routing, live polyphony, stereo, and automatic chords. Confirm ranges, duration, velocity, HOLD format, APIs, and name. Alloy succeeds when attack and body become one playable hybrid gesture.

## 12. As built: selected-voice release preparation

The listening selection retains original BASS and SOFT, and adopts revised KEY,
PLUCK, BRASS and STRING configurations. The preceding factory bank had six presets
per voice. Complete saved configurations and the original missing-config model-1
fallback keep their existing sound.

The selected release generator uses one resolved configuration for each render,
its manifest and its saved recipe. Selected-recipe control tests extend the legacy
regressions rather than replacing them. The selected audition and MPC kit are
generated separately from the frozen original and contrast packs. Diagnostic
source ablations retain their extra attack-modulation flag outside the importable
patch schema.

Verified and measured results belong to the corresponding test reports and pack
manifest. The owner has heard and approved the voice choice; final preset review,
phone render profiling, physical MPC playback and release naming remain distinct
acceptance work. This preparation adds no synthesis mechanism or model revision.

## 13. As built: distinct factory roles

The owner heard the separate role comparison, reported a substantial improvement,
and authorized integration. The active factory bank now contains 24 complete
patches: each voice's first-preset reference and three authored roles with distinct
operator balances, routing and envelopes. Sonic inputs match the published role
patches; Elastic Contact is labeled Elastic Touch to fit the existing preset strip.
The six previous held references remain available separately, with Held Filament
and Endless Ember retained on the starter kit's last two pads.

The new factory audition has its own generated folder and review namespace.
It exports the full bank in three eight-pad MPC programs and a regenerated
sixteen-pad starter. Existing original, contrast, selected and role-comparison
publications remain references. Saved configurations, voice defaults and model-1
fallback semantics are unchanged. The integration adds no synthesis mechanism.

Verified recipe correspondence and numerical/export checks belong to the release
reports. The owner approved the role direction by listening; phone performance and
physical MPC playback still require device acceptance. The authored roles preserve
the approved matching wood/tine projections, so MATERIAL below .5 is flat for those
patches; selected-default diagnostic studies keep separate material projections.
The original 108 register/classification regressions remain over frozen earlier
inputs. New roles are additionally measured over complete events so slow swells
are not mistaken for short decays by a cropped capture. Picked Fibre at C2 retains
its approved short pitched gesture and a disclosed generic TOM classification;
the engine's TONAL export class and saved root remain intact.
