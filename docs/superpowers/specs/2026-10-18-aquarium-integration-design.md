# Aquarium integration — pads and a shared environment

**Date:** 2026-10-18  
**Direction:** the six-effect audition received the owner's positive verdict; integrate both placements in stages.  
**This change:** shared native DSP foundation, a standalone recipe contract, and host checks. No Android audio path or visible control is connected yet.

## Decision and listening record

Keep **Fathom → Drift → Tide → Reef → Surface → Biolume** as one independently bypassable, ordered Aquarium processor. It follows the pad's existing character processing. A pad render makes that environment portable in a WAV; a shared stereo performance bus lets the environment respond to the combined performance and continue moving between hits.

The owner answered **“It's all good” on 2026-10-18**, after being asked for a verdict on all six effects and pad/bus/both placement in the [private Aquarium and Pedals audition](https://snipsnap-aquarium-pedals-audition.bertcalm.chatgpt.site). This is positive listening evidence for the presented suite and both placements. It authorizes integration work within that sound and behavior. It does not establish phone performance, Android routing, automated parameter changes, or MPC playback of new exports. The audition's earlier machine-generated note saying “no listening verdict” predates this answer.

Do not impose another listening pause to copy the accepted behavior into the app. If a later revision materially changes that behavior, such as replacing Biolume's pitch treatment, compare the revision with the accepted model and record the new evidence separately.

## Provenance: the accepted sound is a corrected extraction

The source concept is [Aquatic Master FX Suite](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/concepts/aquatic_master_fx_concept.md). The audition used real source DSP, extracted without JUCE/APVTS from [XO_OX-XOmnibus at `a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e`](https://github.com/BertCalm/XO_OX-XOmnibus/tree/a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e), with documented repairs. It is not an unchanged desktop plug-in and not merely existing SnipSnap effects relabelled as Aquarium.

The SnipSnap-generated audition sources were pinned to [`8266ae915ecef98967c4d1d1b16ffa1ac92b1d81`](https://github.com/BertCalm/SnipSnap/tree/8266ae915ecef98967c4d1d1b16ffa1ac92b1d81). Guitar and choir examples were synthesized MAGNET/MURMUR material, not recorded performers. The field-source attribution and source/treatment recipes remain in the audition's downloadable provenance. Integration starts from SnipSnap [`45ba33832950530bb0148435378b4556045c6cf6`](https://github.com/BertCalm/SnipSnap/tree/45ba33832950530bb0148435378b4556045c6cf6).

The extraction keeps the upstream MIT license and copyright headers. Retain the pinned originals, reproducible extraction, parameter registry and provenance alongside the imported core. The corrected audition headers reviewed independently had these SHA-256 digests:

| File | SHA-256 |
| --- | --- |
| `src/DSP/FastMath.h` | `3e5530e4caebcabb1ddb225b05d72048945e1c1985f4fbc8ce43dbf1f7b092a5` |
| `src/DSP/Effects/MultibandCompressor.h` | `9d336d8c253f65a9c197e35381076586a57cec452f812b29b46caf212141aa21` |
| `src/DSP/Effects/AquaticFXSuite.h` | `43de603e5427ba7ee1cb541750306e3e85af78a124b18bcbaced8d5eb561a042` |

Adaptations needed for an in-repository wrapper must be listed and checked against this baseline; the hashes identify the audition extraction, not a promise that every integration file is byte-identical.

The audible baseline includes these repairs:

- Host separation, validated sample rates, initialized/reset state, and the canonical `aqua_fathom_highXover` spelling.
- Corrected Surface/Tide TPT filter mixing, neutral gain, shelf directions, resonance damping and gain-dependent prewarping.
- Corrected Fathom attack/release selection and right-channel crossover input, retaining the complementary split and linked dynamics.
- Rate-dependent, sample-rate-scaled deterministic Drift movement, retaining the delay voices, feedback filtering and blend.
- Sample-accurate Tide stereo phase and seeded sample-and-hold; raw rate controls operate alongside the existing movement mapping.
- Operative Drift diffusion and Reef size/decay controls alongside the existing movement/space mappings.
- Biolume gate-close state clocking, right decorrelator output, bounded triangular folding, and the effective 2000 Hz high-pass setting in the audition preset.
- Standard exponential/tangent math where the extraction corrected accuracy or undefined behavior. Existing denormal treatment and retained approximations are documented with the core.

The prior native validation reported 214 behavior checks. Independent AddressSanitizer/UndefinedBehaviorSanitizer review also exercised 144 random/boundary configurations without observed memory-safety or undefined-behavior failures. These are audition results, not the foundation branch's test count or evidence of mobile callback cost.

## Relationship to PEDALS

The [pedal-board design](2026-10-04-pedal-board-design.md), including its approved seventh TREATMENT row and revised DRIVE/COMP recipes, remains its own plan. Aquarium does not reopen that row, alter VALVE/SQUASH, restore the rejected CONTOUR-as-WAH chip, or claim that future WAH, COMPRESSOR, TREMOLO, FLANGER and FUZZ stages have shipped.

The audition's board comparison used current post-baked-pad treatment semantics, with COMP and DRIVE in series at AMT 0.7. It did not implement the proposed pedal-before-landing-amp guitar rerender. Preserve that distinction: Aquarium follows the audio the pad currently produces, including its baked landing amp or treatments. Do not reconstruct a different guitar signal path while integrating it.

The two systems may share low-level utilities after comparison tests establish equivalent behavior. They keep separate identities, order and versioned settings: pedal tremolo is not automatically Tide, a flanger is not Brownian Drift, and SPRING is not Reef. In particular, Aquarium's tempo synchronization and stereo behavior do not expand the free-running, per-pad pedal milestones.

## Six effects, three pairs

| Pair | Effects | Role |
| --- | --- | --- |
| **Depth** | Fathom + Surface | Pressure/weight and the submerged-to-air boundary |
| **Motion** | Drift + Tide | Wandering movement and a common rhythmic breath |
| **Color** | Reef + Biolume | Enclosed space and added harmonic light |

The pairs organize controls and presets. They do not make the effects mutually exclusive, change their signal order, or force one member down when the other rises.

New instances start **dry**: all six stage mixes are zero. Each zero-mix stage is an exact bypass; an all-zero-mix suite leaves both channels bit-for-bit unchanged. Audition `full` is a comparison preset, not the product default. Enabling a stage is a user choice.

## One core, two lifetimes

The shared C++17 processor is independent of Oboe, JNI, Compose, files and WAV writing. It prepares outside the audio callback for the host's real sample rate and block capacity. The foundation's boundary is `app/src/main/cpp/aquarium/AquariumProcessor.h` / `.cpp`: `prepare` accepts 8–192 kHz and a maximum block size of 1–8192 frames; `reset` takes an unsigned 32-bit seed; `applySnapshot` validates a complete fixed 52-control snapshot before applying it. Processing accepts left/right sample buffers, frame count and tempo, is `noexcept`, and performs no allocation. The host owns input validation, channel interleaving, source lifetime and output policy.

The core is compiled into the Android native target so the production toolchain sees it. Existing playback and JNI paths do not call it in this foundation change. Compiled code is not connected audio routing.

The wrapper requires disjoint writable L/R buffers, no more than the prepared block capacity, and finite input samples with absolute magnitude at most 32. Tempo zero selects free-running modulation; other accepted tempos are finite and no greater than 1000 BPM. Validation failures return a result without changing input buffers or DSP state. These are input/API bounds, not an output limiter or a phone-performance result.

The processor preserves its six-stage order internally. Do not insert its components into `FxChain.SECTIONS`: the existing per-pad chain has its own fixed order, stereo rules, version and one-second total-tail policy. Keep all existing `FxChain` and `PadRecipe` constructors, serialized bytes and rendering behavior unchanged in the foundation change.

| Placement | State lifetime | Meaning |
| --- | --- | --- |
| Pad render | Fresh prepared/reset state per render, from the saved seed and settings | Every trigger plays the same baked environment. Its movement starts in the rendered sample. |
| Shared performance bus | One prepared state per output session, spanning audio blocks, hits and silent gaps | Dynamics react to the mixed signal; drift, tide and tails continue across the performance. |

The bus is not implemented by processing each voice separately, summing treated WAVs, resetting on note-on, or calling the offline renderer on every callback. An active stage continues receiving silence when voices stop. Bypass/re-enable transitions need their own state and ramp policy before live use; exact static bypass alone does not establish click-free automation.

## Canonical recipe boundary

Introduce a **standalone versioned `AquariumRecipe` settings value** in `:synth`, rather than modifying the existing pad/rack recipe in this first change. It is a reusable DSP identity; pad and bus hosts can later embed it in their own envelopes. It is not a new `PadRecipe` field, and neither the existing pad-model nor rack-model version changes.

The version-1 boundary records:

| Field | Contract |
| --- | --- |
| Format version | Identifies the Aquarium JSON shape; unknown versions fail explicitly. |
| Render model | Identifies the corrected audition DSP behavior. Model 1 preserves the behavior documented here; materially different behavior needs an explicit model decision. |
| Seed | Exact unsigned 32-bit value, represented losslessly on the Kotlin/JSON side; reset reproduces its random streams. |
| Parameters | Complete snapshot of all **52 canonical `aqua_*` controls**, in registry order and native physical units. |

The imported `app/src/main/cpp/aquarium/parameters.json` is the reference for IDs, defaults, finite ranges and optional selector steps. A registry-declared `step: 1` restricts that selector to whole labelled states in both languages; do not infer a discrete domain from the parameter's spelling. This deliberately makes the new boundary stricter than the extraction CLI's accept-float-then-round behavior, without changing DSP laws or any accepted audition preset. Gradle packages that same file as a JVM resource; do not maintain a second literal table in Kotlin. Preserve physical units such as hertz, milliseconds, decibels and degrees; a future 0..1 UI mapping is a presentation layer. Save resolved values rather than only preset names, pair macros or omitted defaults. The complete snapshot keeps a later default or preset-table edit from changing an earlier recipe.

Parsing rejects unknown IDs, unsupported versions/models, non-finite values, invalid seed, out-of-range values and values between registry-declared selector steps. Sparse authoring input may be expanded using model-1 defaults before serialization, but saved canonical output is complete and byte-stable. If the low-level audition setter still clamps interactive input, that is not permission to silently repair malformed saved recipes. Registry names/order, defaults, steps and any Kotlin/native packing order must have a cross-language parity check.

Placement and tempo are outside this shared value. A later complete pad-render envelope records the exact input WAV/source identity and hash, earlier recipe metadata, actual BPM, sample rate, channels, and explicit tail/fade/render policy. Replaying that operation cannot accidentally borrow the tempo of whichever kit is open. A bus envelope instead records session settings and ownership, and feeds current host BPM while running; it does not serialize delay buffers or restart state on every note. Replaying a captured-source operation still requires the source audio. Settings alone do not regenerate an arbitrary recording.

Existing `RecipeReplay` truth remains: most current treatments record the **last operation**, not a complete history. The future Aquarium pad envelope must preserve the current baked input and its prior metadata explicitly. It must not claim that a synth's raw engine patch reconstructs any unrecorded intermediate treatments. Existing recipes must keep their current readers and output until the new integration door is connected deliberately.

## Per-pad path: portable sound

Future offline rendering uses this same native core. Duplicate a mono input into L/R before processing and keep the resulting stereo WAV; preserve stereo inputs without folding them to mono. Reset with the saved seed for every independent render. Block partitioning must not change the rendered samples on the same build.

Apply to every file the chosen pad references under the existing whole-pad/layer guards, rather than treating only its loudest velocity layer. Preserve the original source and previous recipe before writing derived audio; amount edits regenerate from that source instead of repeatedly processing the previous wet output. A failed render must leave the current kit usable.

Tail duration, ending fade and peak policy must be explicit, saved and tested in this later stage. Do not silently apply `FxChain`'s one-second cap or the audition's three-second demonstration tail as a universal product rule. Looping material also needs an explicit contract: baked random movement, modulation and reverb can create a seam. A finite derived render must say it is finite; do not retain a misleading loop marker after changing its length or boundary.

MPC exporters continue writing normal rendered WAVs and native program data. They do not install Aquarium on the MPC or invent MPC FX fields. A pad's baked sound travels through the existing export formats. Shared-bus coloration travels as a recorded/bounced performance; it cannot be reproduced by exporting the dry individual pads alone.

## Shared bus path: one stereo environment

There is no universal app master insert today. `PadEngine` has a stereo voice mix and print tap; `SurfaceEngine` has its own mono render/print path before duplicating audio to L/R; loop/ORBIT playback and bounces have their own mixers. An Aquarium control must state which output path it affects. Do not draw a global switch while some routes silently miss the processor.

For each supported route, place Aquarium after the final voice sum and before the corresponding output/print tap so PRINT records what the listener hears. A stereo bus needs stereo print/storage all the way through; applying Aquarium before SURFACE's existing mono duplication would discard its image. Refactor that route deliberately when it joins the feature. The live processor receives actual stream sample rate and continuous host tempo, including during zero-input tail time.

Settings updates use a validated control-thread snapshot and a bounded handoff. No JSON parsing, string lookup, allocation, file access, lock acquisition or exceptions may enter the callback. Preparation/repreparation, route changes, device loss, panic, transport restart and bypass must each have declared state/transition behavior. Add parameter smoothing or bounded transitions before exposing live sliders. Preserve the existing print and voice-allocation handshakes.

Arbitrary settings can boost level substantially; the independent audition probe observed a finite peak of about 7.85. The core contains neither a final limiter nor loudness normalization. Decide and measure headroom/output protection at the host boundary before enabling the live bus. Browser comparison level matching is not that policy and must not be copied into per-block audio.

## Future panel

Use TapeOS: a tool window/group box around a dark audio display, existing type/tokens and at least 44 dp targets. Keep **AQUARIUM** as the qualified suite name because FATHOM/TIDE are already engines, SURFACE is already a screen, and DRIFT already names a mutation move.

A panel has presets, BEFORE/dry comparison, explicit **PAD / PERFORMANCE** scope, and the three pair tabs. Each pair reveals two named effects with independent bypass, primary plain-word controls and folded details. A closed summary reports actual saved settings. Pair controls are conveniences over the same recipe, not a second parameter system. Do not append an unapproved eighth chip row or replace the PEDALS row to make room.

The foundation ships no inactive entry point, disabled promise or switch that changes only metadata. Add a visible door only with working processing, undo/source behavior, persistence and the relevant output path. Phone-width labels, access semantics, busy feedback and audible bypass transitions are checked when that panel lands.

## Known model-1 limits

- **Biolume:** feedforward harmonic shimmer, not a connected octave-feedback reverb. Its inherited unwindowed 2× pitch-ring reader retains grain seams about every 100 ms at 48 kHz. The high-pass path is an input-minus-cascaded-lowpass approximation. Gate clocking/fold repairs do not remove those characteristics. Do not describe it as a polished seamless octave shimmer.
- **Reef:** the inherited 5000-sample delay ceiling changes long-delay choices at higher sample rates. Stability at several sample rates does not establish identical room acoustics.
- **Fathom:** complementary cascaded one-pole crossovers, not the textbook Linkwitz–Riley topology named by upstream comments. Upward compression is source-level sensitive.
- **Tide:** the retained audition shapes and clock apply; the concept's proposed lunar waveform and the pedal plan's starts-open tremolo are not added by this integration.
- **Host:** no Android CPU/power/latency evidence, live automation contract, routing coverage or hardware-export acceptance follows from native or browser checks.

These limits are part of model 1's record. They do not prevent foundation work or require repeating the accepted audition before integration.

## Delivery and gates

The companion [foundation plan](../plans/2026-10-18-aquarium-foundation.md) separates the current change from later connected features.

1. **Foundation, this change:** preserve the audited corrected core and provenance; add the canonical settings/recipe boundary and native host tests. Verify exact dry bypass, reset/seed reproducibility, block invariance, filter/dynamics repairs, stereo behavior, finite tails and registry parity. Confirm existing constructors, bytes and playback are unchanged. No audio route/UI claim.
2. **Offline bridge and pad semantics:** render real `Snip` inputs through the shared core; prove source preservation, deterministic replay, layer coverage, tail/ending/loop policy, transactional writes and exported stereo WAV integrity.
3. **Continuous bus routing:** connect explicitly supported outputs and print/bounce taps; prove state continuity, actual stereo capture, no callback allocations, safe transitions, headroom and stream recovery. Measure callback CPU/memory on a phone before describing it as performant there.
4. **Panel and end-to-end use:** connect controls to real scope, persistent settings and supported audio. Check phone-size access/readability and complete a pad/export and live-print round trip. Record physical phone/MPC observations separately from compilation and host checks.

The owner's existing verdict supplies the listening direction for these stages. Mechanical failures are fixed as implementation work; materially different sound is documented and compared rather than silently replacing model 1.
