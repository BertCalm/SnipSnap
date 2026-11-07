# Nacre: a playable finite harp

Nacre revision 3 keeps the first listening model's preferred paired-harp source and gives it the later coral and moving seaweed responses from the owner's “Much better” listening verdict. It renders finite six-second mono audio at 44.1 kHz. Saved patches select their exact renderer: revisions 1 and 2 retain their original synthesis, defaults and diagnostic behavior.

New patches select revision 3. Three factory recipes are available in the Nacre picker, CLI and shared preset roster: **Pearl Close**, **Coral Reply** and **Kelp Passage**. FRESH TAPE offers a separate velocity-layered NACRE bank. The playable-range audition is at https://nacre-first-audition.bertcalm.chatgpt.site; the new sampler-bank comparison is at https://nacre-first-audition.bertcalm.chatgpt.site/velocity/index.html. Software integration does not establish MPC hardware or musical acceptance.

## The gesture

Two related harp strums → softened coral replies or later opening seaweed paths → a finite decay. The active strings use the frozen first renderer's 4× waveguides, bodies and sympathetic bank. Venue processing acts on its already conditioned audio at output rate, with fixed delays, fixed absorption filters and smooth nonnegative apertures. It adds no feedback loop, new excitation spectrum or moving string delay.

The source's original CURRENT 0.20, BLOOM 0.35 and VENUE 0.15 stay pinned. TOUCH, DUET, MIDI, physical strike velocity and exact signed 64-bit seed remain source inputs. Revision 3's live space controls act downstream. CURRENT zero means **still returns**; it retains the reference source's internal water character.

## Control contract

| Control | Default | What changes |
|---|---:|---|
| TOUCH | 0.30 | The first renderer's effort, pick character and string participation. |
| DUET | 0.15 | The first renderer's answering offset, spacing and complete-gesture directions. |
| CURRENT | 0.20 | Coral early/late path balance and seaweed aperture speed/depth. Zero freezes return alignment; the reference maps to 2 Hz and 0.90 aperture depth. |
| VENUE | 0.15 | A continuous convex blend between coral at 0 and seaweed at 1. |
| BLOOM | 0.35 | Existing linear sympathetic contribution and return amount, with the heard balance pinned at the reference. Decay laws stay fixed. |
| HOLD | 0 | Reserved/refused: every nonzero value is rejected. |

Coral delays are 100/210/340 ms with 1600/1200/900 Hz absorption. Seaweed delays are 220/410/640 ms with 1200/900/650 Hz absorption. Reference path weights are 0.52/0.31/0.17. These are shaped listening parameters, not measured underwater acoustics. At the reference controls, endpoint Float mixes and PCM24 WAVs are pinned against the actual published preferred study.

Return gain uses the fixed reference calibration `0.8930654093975182`; it is not recomputed from each knob setting. The output uses the reference gain `3.2143452167510986`, followed only by a 0.95 peak guard. This deliberately preserves natural strike level and control authority instead of matching every patch back to the same loudness. Diagnostics record any safety attenuation. Old revisions keep their old output stages.

## Saved recipes and playback

An ordinary `NacrePatch` carries model, voice, macros, MIDI 28–72, velocity 0–1 and exact seed. JSON replay, `PadRecipe`, macro editing and native velocity rendering preserve the saved revision. The first two renderers and the second model's historical six-preset bank are frozen; the old audition task is explicitly pinned to that bank.

The Android picker exposes PEARL, the three starting sounds, root and strike controls. Loaded old patches keep their saved model, note, velocity and seed through edits and recreation. Nonzero HOLD remains unavailable. The app renders audio offline and plays that result; this is not a live string solver.

CLI example:

```sh
./gradlew :cli:run --args="synth NACRE PEARL --all --midi 48 --velocity 0.65 --out /tmp/nacre"
```

## MPC starter-kit trial

`./gradlew --no-daemon :synth:generateNacreKit` exports **SnipSnap-Nacre-Kit.zip** into `testkit/`. The optional `-PnacreKitOutput=/absolute/output/root` selects another destination. The trial contains a native MPC 3 track (`.xtd`), an MPC 2 program (`.xpm`), sixteen shared mono 44.1 kHz PCM24 WAVs, editable `kit.json`, separate complete pad recipes, a pad map, measurements and checksums. Its listening page plays the actual exported samples.

| Pads | Sound | Native notes | Baked strike |
|---|---|---|---|
| A01–A08 | Pearl Close | C3, Eb3, F3, G3, Bb3, C4, Eb4, F4 | 0.65 |
| A09–A10 | Coral Reply, Kelp Passage | C3 | 0.65 |
| A11–A12 | Pearl Close | C3 | 0.30, 1.00 |
| A13–A14 | Coral Reply | C3 | 0.30, 1.00 |
| A15–A16 | Kelp Passage | C3 | 0.30, 1.00 |

All pads retain revision 3, seed 7, their preset's existing controls and the complete six-second ending. The note and physical strike are rendered natively; there is no export pitch shift, rack processing or per-sample loudness matching. The program retains the house's uniform mixer headroom. Hardware pad velocity changes playback level; it does not rerun the harp or select additional velocity layers. The soft/medium/hard examples are separate authored strikes.

Every pad is a finite tonal one-shot with no mute group or sample loop. Different pads can overlap. The existing MPC 2 writer sets each pad to mono with polyphony 1, so retriggering the same pad can restart its tail. The native MPC 3 writer uses its existing non-monophonic configuration; the numeric polyphony field is not a verified voice-count claim. Check repeated strikes, chords, dynamic response and complete endings on the target MPC.

The generator checks both program formats by reading them back, compares exported audio with the native recipe renders and records source provenance. These checks establish the exported files and settings; successful loading and musical behavior on hardware remain pending. The original single-strike bank remains a separate, immutable listening reference. FRESH TAPE uses the layered bank below. The existing renderer, older recipes and auditions stay available.

## Velocity-layered starter

`./gradlew --no-daemon :synth:generateNacreVelocityKit` writes **SnipSnap-Nacre-Velocity-Kit.zip** into `testkit/`. `-PnacreVelocityKitOutput=/absolute/output/root` selects another destination. It contains sixteen tonal one-shot pads, forty-eight complete mono 44.1 kHz PCM24 WAVs, native MPC 3 and MPC 2 programs, the editable sidecar, full layer recipes, measurements and source hashes.

A01–A08 play Pearl Close at C3, Eb3, F3, G3, Bb3, C4, Eb4 and F4. A09–A12 play Coral Reply at C3, Eb3, G3 and C4; A13–A16 play Kelp Passage at those same four notes. Every pad has the same three touch zones:

| MIDI velocity | Source strike | Source TOUCH | Sampling gain, approximately |
|---|---:|---:|---:|
| 1–41 | 0.30 | 0.05 | 5.8020506 |
| 42–84 | 0.65 | 0.18 | 1.8642224 |
| 85–127 | 1.00 | 0.30 | 1 |

Velocity zero belongs to no sample zone. The hard layer is the exact ordinary preset performance at velocity 1, including the preferred TOUCH 0.30. Softer layers use gentler contact through the existing TOUCH control as well as lower native excitation. No synthesis algorithm or saved renderer changes. Seed 7, requested note, DUET, CURRENT, VENUE and BLOOM stay with the preset on all three layers.

The MPC applies its own velocity-controlled playback level. To avoid baking the source's explicit strike scaling into that curve a second time, the sampler files use one fixed input-based compensation law:

`samplingGain = effort(0.30) / (sourceVelocity * effort(sourceTouch))`, with `effort(t) = 0.22 + 0.78 * t^0.65`.

This is not peak or RMS matching. It leaves the accepted hard file unchanged; all gains and complete ordinary source recipes are recorded. `NacreVelocityRecipe.renderAllLayers()` replays the source and its stated gain exactly. Rendering refuses nonfinite audio or delivery above 0.95 instead of silently normalizing a layer. Every file keeps the original six-second ending. Browser comparisons play these actual delivered files at a common monitor level; they do not measure or emulate an MPC's configured velocity curve.

The typed `nacreVelocityLayers` source field contains all three independent source recipes, velocity windows and sampling gains. It survives ordinary sidecar and backup source-metadata round trips. Generic one-source editing and REPLAY refuse layered targets; they do not rebuild only the hard file and leave stale softer samples. Full-bank replay uses the explicit layer bundle. Standalone MPC sidecars additionally fingerprint every delivered velocity-layer WAV through optional `rawVelocitySha256` metadata; older sidecars without this field remain readable.

Different pads have no choke relationship. The existing MPC 2 mono-per-pad retrigger behavior remains unchanged. Loading, same-pad repeats, distinct-pad overlap and the player's velocity curve still need a target-MPC listen. Conch, cave, chromatic keygroups and HOLD are separate future work.

## Cache and cost

A four-entry LRU owns completed source buffers, with a retained audio bound of 25,401,600 bytes plus container overhead. Its key includes the resolved source voice, TOUCH, DUET, MIDI, physical velocity, full seed and source ablation flags. Return-only controls do not invalidate that source. New notes, strikes or strum settings can still need a cold source render.

Cache lookup and publication use short locks. A separate interruptible build lock permits only one cold build at a time, while warm hits, statistics and clearing stay available. Clearing advances a generation so in-flight work cannot repopulate the old cache. Cancelled or failed builds are not published, and returned samples/stems cannot mutate retained buffers.

The audition reports individual render timings and cache context. Warm venue editing is the targeted fast path; it is not a claim that every source edit renders in tens of milliseconds.

## Playable audition

Run `./gradlew --no-daemon :synth:generateNacrePlayableAudition`. Output in `testkit/nacre-playable-audition/` contains:

- Three starting sounds.
- MIDI 36, 48 and 60 at physical velocities 0.30, 0.65 and 1.00.
- Five steps each of VENUE, CURRENT and BLOOM, with the remaining controls fixed. CURRENT uses the seaweed endpoint to expose aperture motion; the other sweeps use the reference blend.
- One eight-note phrase at three venue positions, retaining actual tail overlap.
- Three short, explicitly trimmed control-sweep previews.

Every single-note WAV comes directly from an ordinary saved patch with export gain 1. Phrase recipes schedule eight ordinary patches with exact frame onsets, a shared safety gain and a final fade. Preview recipes describe their cuts and fades. The generator verifies exact PCM replay and bundles measurements, event traces, source snapshots and SHA-256 hashes. It prefers one complete ZIP and splits only if the hosted file limit requires it.

The prior 59-clip model comparison, gentle-return experiment and preferred timed-return study remain linked. Their audio and source snapshots are preserved.

## Verification and listening ledger

**Heard by owner:** the first renderer was pleasant; the second was overbearing and abrasive; the gentle venue lift had no audible difference; the later timed-return study was “Much better.” That establishes the direction, not acceptance of every new knob, register or velocity.

**Verified by source and automated checks:** independent legacy sample/event goldens; preferred endpoint mixes and WAVs; saved-patch/velocity/edit replay; fixed reference gains; bounded feed-forward returns; finite decay, pitch, peak and DC checks; meaningful control movement and interactions; cache identity, invalidation, ownership, concurrency, clearing and cancellation. The production handoff requires the full JVM suite to exit successfully. Android compilation and on-device testing are distinct checks and must be reported separately.

**Proposed:** the full control ranges, three starting recipes and musical overlap behavior await listening. Conch, cave, expanded voicing and held-key operation remain future work.

## Why it is built this way

- The preferred first-renderer source protects the pleasant foundation while the space gains a clear response in time.
- Fixed reference gain lets BLOOM and physical strike strength keep their effect; per-patch matching would disguise that change.
- Fixed delays and bounded apertures give movement without string detuning or a feedback stability problem.
- Versioned replay protects existing work while the new revision develops.
- Cached owned source buffers make return edits inexpensive without retaining live synthesis state.
