# Relay / SnipSnap handoff

Prepared 2026-11-07, from repository and CI records checked during this handoff.

## Current state

Relay's refined repeating pulse is implemented and merged into SnipSnap.

- Repository: https://github.com/BertCalm/SnipSnap
- Merged PR: https://github.com/BertCalm/SnipSnap/pull/941
- Merge commit: `43b63ef50323cb883a84edf13b0efd9035644c5c`
- Merge time: 2026-11-07 14:22:16 UTC.
- Final implementation branch head before this handoff: `12e059bfb1b41b48b897f2596bbfc958676265a8`.
- Working branch: `claude/relay-periodic-pulse`.
- Default branch: `claude/mobile-mpc-drum-sampler-t58x74`.
- Published audition: https://snipsnap-relay-audition.bertcalm.chatgpt.site

This handoff is a documentation-only commit on the existing Relay branch, added after PR #941 merged. It is not part of that original merge. The implementation and subsequent emulator-test correction are already committed; no production DSP changes are included in this handoff.

The remaining work is verification, listening and the separate CI follow-up recorded below. Do not restart pulse design from the earlier sparse-accent candidates.

## What the owner selected

The original concern was that voices sounded too similar, and timbral handoffs felt like a sequence rather than one evolving voice. Earlier work refined continuous source history and transition overlap.

The later PULSE feedback was more specific:

1. The eased `nominal^0.75` mapping did not produce the expected audible difference.
2. Wider sparse accents remained too minor.
3. An exaggerated, smooth 3 Hz amplitude reference established the desired repeating shape.
4. The owner wanted a toned-down version of that reference.
5. Revision 9 used 3.5 dB at PULSE 0.5 and 7 dB at PULSE 1. The owner called it “Better” and authorized the musical audition and integration.

The nine new musical comparisons still have no recorded owner listening verdict. The approved 36-preset bank's earlier listening approval is separate.

## Implemented sound and compatibility contract

### New patches: model 2

Model 2 reuses the actual original Relay renderer at PULSE 0, then applies a smooth gain contour to its finished PCM before optional leveling:

```text
depthDb = 7 * PULSE
floor = 10^(-depthDb / 20)
gain(t) = floor + (1 - floor) * (0.5 + 0.5 * cos(2 * pi * rateHz * t))
```

- Finite notes use 3 Hz and start at the top of the contour.
- Each note owns its clock. There is no shared live host-tempo clock.
- PULSE 0 delegates exactly to the original PULSE-0 path.
- PULSE 0.5 gives a 3.5 dB dip; PULSE 1 gives a 7 dB dip.
- The full-depth gain floor is approximately 0.4467. The earlier exaggerated reference used 0.18.
- HOLD fits a positive whole number of pulse cycles to the actual root-fitted phrase duration. Its resulting rate can differ from 3 Hz.
- The same periodic gain and output-level gain apply to the real preceding rendered cycle. Seam checks use that genuine predecessor.
- No extra DC filter was added: it would alter the selected reference. Output DC is measured and bounded.
- The added pass polls interruption. It introduces no new physical solver.

### Existing sounds: model 1

Keep these protections intact:

- Explicit model 1, missing model and null model decode to the original behavior.
- All 36 factory recipes and all 16 starter pads remain explicitly model 1.
- Older phone navigation bags containing macros without a complete recipe retain model 1.
- New `RelayPatch` instances default to model 2.
- Copying, macro edits, velocity edits, save/reopen and recipe replay preserve the selected model and full phrase.
- Unknown model numbers are rejected.
- The original `Relay.kt` renderer remains unchanged.
- The phone offers **ORIGINAL ACCENTS / REPEATING PULSE** as an explicit choice.
- Async YOURS restoration gates controls and writeback so initial defaults do not overwrite a saved recipe.

The later upstream kick-velocity change at `97a5d6309` was reviewed separately. It changes typed THUMP/ONSET kick paths; Relay, its shared DSP dependencies and UI were unchanged in the clean merge tree. Relay's velocity path still copies the complete patch before rendering.

## Code map

Paths are relative to the repository root.

| Path | Purpose |
| --- | --- |
| [Relay.kt](../synth/src/main/kotlin/com/snipsnap/synth/Relay.kt) | Original source renderer; retained unchanged. |
| [RelayModels.kt](../synth/src/main/kotlin/com/snipsnap/synth/RelayModels.kt) | Versioned dispatch and diagnostic report. |
| [RelayPulse.kt](../synth/src/main/kotlin/com/snipsnap/synth/RelayPulse.kt) | Repeating gain contour, fitted held cycles and predecessor handling. |
| [RelayPatch.kt](../synth/src/main/kotlin/com/snipsnap/synth/RelayPatch.kt) | Fresh defaults, legacy decoding and model-preserving recipe operations. |
| [RelayPresets.kt](../synth/src/main/kotlin/com/snipsnap/synth/RelayPresets.kt) | Approved bank pinned to model 1. |
| [SynthKits.kt](../synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt) | Sixteen-pad Relay starter retained as model 1. |
| [SynthScreen.kt](../app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt) | Preview, legacy navigation, explicit behavior choice, restore and save/send integration. |
| [RelayModelsTest.kt](../synth/src/test/kotlin/com/snipsnap/synth/RelayModelsTest.kt) | Seven model, reference-audio, velocity, pitch/classification and seam tests. |
| [Reference hashes](../synth/src/test/resources/relay-model2-audition-reference.json) | Twelve immutable published depth-audition raw WAV hashes. |
| [RelaySynthScreenTest.kt](../app/src/androidTest/kotlin/com/snipsnap/app/ui/RelaySynthScreenTest.kt) | Four instrumentation tests for fresh/legacy/edit/save/reopen flows. |
| [Musical generator](../synth/src/test/kotlin/com/snipsnap/synth/RelayPulseMusicalAuditionGenerator.kt) | Nine actual production-rendered musical comparisons. |
| [RELAY.md](RELAY.md) | Full engine behavior, compatibility, listening history and validation. |
| [RELAY_SPEC.md](RELAY_SPEC.md) | Original engineering specification. |

The shared screen merge preserved both `OnsetPatch` and `RelayPatch` in `currentDrafts()`, alongside upstream Lattice HOLD and Libration touch workflows.

## Audition and publication

The current logical audio revision is **10**:

- Bassline: BASS / ROUND TRIP, six notes with native velocity variation.
- Chords: KEY / VELVET TURN, C-minor/F-minor voicings.
- Held texture: FLOW / WARM DRIFT, its actual held region repeated three times.
- Each performance compares PULSE 0, 0.5 and 1.
- Nine comparisons produce 18 raw/matched PCM24 WAVs.
- One common summing gain per performance family preserves the depth comparison and complete overlapping releases.
- Finite examples use 90 BPM; note timing aligns the 3 Hz contour with two cycles per beat.
- Matched exports use the existing loudest-200 ms listening target.
- The player switches synchronized buffers without restarting playback.

The published page retains 76 earlier cases / 152 approved WAVs and adds the nine musical cases: **85 clips / 170 WAVs**. All retained approved WAVs are byte-identical. Browser notes retain the key `snipsnap-relay-v1-listening-notes`.

Publication identifiers, for a maintainer using Sites:

| Item | Value |
| --- | --- |
| Existing Site project | `appgprj_6acba3919774819183e0060ba0c5a47a` |
| Audience | Owner-private; preserve existing access. |
| Published Site source commit | `e3ceee14b808ae4ea8c54c9f20b14e26cf62f173` |
| Verified deployment | `appgdep_6aeeaab937b881919b2e1f25b0664779` |
| Deployment succeeded | 2026-11-07 04:36:22 UTC |
| Musical audio's recorded source | `6c31ae3e6a6b5e28cd07118f3d493ecd2905a5cd` |

The Site source is a separate managed repository. Reuse the existing project; do not create another Site.

Useful published evidence, requiring the owner's Site access:

- [Manifest](https://snipsnap-relay-audition.bertcalm.chatgpt.site/manifest.json)
- [Source and reproduction](https://snipsnap-relay-audition.bertcalm.chatgpt.site/integration/README.md)
- [Validation checkpoint](https://snipsnap-relay-audition.bertcalm.chatgpt.site/integration/validation.md)
- [Review history](https://snipsnap-relay-audition.bertcalm.chatgpt.site/integration/review.md)
- [Canonical render agreement](https://snipsnap-relay-audition.bertcalm.chatgpt.site/integration/canonical-agreement.json)

**Status-text follow-up:** those Site evidence files and its banner describe the earlier publication checkpoint and may still say PR #941 is open or integration is under review. The PR is now merged. A future metadata-only update can reconcile that text while preserving all 170 WAV hashes, notes storage and audience.

## Verified implementation checkpoint

The local full run at `fabf2873557ed0d665fb9cd17bd517111995abd4` finished with actual Gradle exit **0**, in 1h 20m 44s.

```sh
./gradlew --no-daemon :synth:generateRelayPulseMusicalAudition test
```

| Module | Tests | Result source |
| --- | ---: | --- |
| synth | 4,770 | Fresh XML |
| shell | 2,188 | Fresh XML |
| kit | 540 | Fresh XML |
| loop | 439 | Fresh XML |
| cli | 290 | Fresh XML |
| audio | 457 | Explicit Gradle UP-TO-DATE |
| mpc3 | 95 | Explicit Gradle UP-TO-DATE |
| xpm | 51 | Explicit Gradle UP-TO-DATE |
| json | 16 | Explicit Gradle UP-TO-DATE |
| **Total** | **8,846** | **0 failures, errors or skips** |

8,227 tests executed; 619 unchanged results were reused. All seven new Relay cases passed in fresh XML.

Other recorded checks:

- Android main and instrumentation-test Kotlin compiled locally under Temurin 17.0.21 with the pinned SDK/NDK. This was compilation, not local device execution.
- All 578 legacy native/normalized PCM and complete-recipe fingerprints matched exactly.
- All 16 actual starter-pad samples, frame counts and recipes matched exactly.
- Model 2 reproduced all 12 published depth-audition raw WAV hashes.
- Independent reconstruction matched all 18 musical WAVs within 8.94e-8 PCM.
- Canonical regeneration at both the earlier checkpoint and `fabf287` reproduced all 18 musical WAVs exactly, with unchanged source/class hashes.
- Browser playback, synchronized switching, seek, repeat, volume, keyboard, notes, retry, downloads and mobile layouts passed. All 18 decoded musical buffers were checked.
- Two warmed desktop Temurin 21 runs measured median added render cost of 2.30 ms, approximately 3.8%. This is desktop evidence, not phone or MPC timing.

These are scoped checkpoints; they do not imply every later merged tree or device suite passed.

## Current CI and the emulator correction

PR #941 is merged. Do not carry forward the earlier “PR open / CI running” summary.

| Revision / run | Verified result |
| --- | --- |
| `05f235de2`, [tests 42338955942](https://github.com/BertCalm/SnipSnap/actions/runs/42338955942) | All three synth shards, other JVM tests, JVM aggregator, Android build and native tests passed. |
| Same head, [emulator 42338955833](https://github.com/BertCalm/SnipSnap/actions/runs/42338955833) | Failed: the Relay preset-name fixture and two other SynthScreen reopen cases. |
| [Correction 12e059b](https://github.com/BertCalm/SnipSnap/commit/12e059bfb1b41b48b897f2596bbfc958676265a8) | Test-only fix: `MY PULSING FLOW` exceeded the 14-character limit. It now uses `MY PULSE FLOW` and asserts SAVE is enabled. Production audio code is unchanged. |
| Corrected head `12e059b` | Direct workflow query returned no runs; a complete green corrected-head emulator run was not independently verified. |
| [Associated downstream emulator 42373769882](https://github.com/BertCalm/SnipSnap/actions/runs/42373769882) | Logs show checkout `20c6033`, integrating Relay merge `43b63ef` into `eb505b0`. 523 tests, one Contour failure, no Relay failure logged. |
| [Associated downstream tests 42373769867](https://github.com/BertCalm/SnipSnap/actions/runs/42373769867) | Cancelled synth shard 0 caused the JVM aggregator to fail. Other synth shards, other JVM tests, Android build and native tests passed. This is not an all-green run. |

The remaining downstream emulator failure is:

```text
ContourSynthScreenTest.grouped_controls_and_make_export_the_complete_edited_recipe
IllegalArgumentException: List has more than one element
(single())
```

Treat that as a separate UI-suite investigation. The absence of a Relay failure in that downstream run is useful evidence, but is not a blanket green result for the corrected head.

## Next maintainer actions

1. Fetch the current default branch and read [AGENTS.md](../AGENTS.md), Luthier and [Steward](../.claude/skills/steward/SKILL.md). Preserve saved-sound compatibility.
2. Verify the corrected Relay instrumentation cases on the current merged branch. Inspect current CI rather than relying on the historical states above.
3. Investigate the separate Contour selector failure and the cancelled JVM shard as needed for a complete green integration run. Do not disable or exclude failing tests to manufacture green.
4. Obtain the owner's listening verdict on the bassline, chords and held texture at 0 / 0.5 / 1.
5. Verify phone/MPC playability, velocity response, held seams and render timing on actual hardware.
6. Refresh the Site's stale integration-status text if needed; preserve its audio hashes and access configuration.

No new DSP redesign is required to hand over the selected pulse. Further sound changes need a new listening comparison and an explicit compatibility decision.

## Reproduction commands

Generate the musical comparison pack:

```sh
./gradlew --no-daemon :synth:generateRelayPulseMusicalAudition
```

Output is ignored source-control material under `testkit/relay-pulse-musical-audition/`. An alternate destination can be supplied with `-PrelayPulseMusicalOutput=/absolute/output/directory`.

Run JVM checks without an Android SDK in the build environment:

```sh
./gradlew --no-daemon test
```

With an Android SDK included, follow Steward's `test -x :app:test` form. Check Gradle's actual exit code.

Compile Android main and instrumentation sources with a valid SDK/NDK:

```sh
./gradlew --no-daemon :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

On an emulator or attached device, focus the Relay UI checks:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.RelaySynthScreenTest
```

Then use the complete instrumentation suite for integration validation.

## Workspace recovery and evidence

Historical workspace locations, if the original environment snapshot is retained:

- Source: `/workspace/SnipSnap-relay-production`
- Site: `/workspace/sites/relay-audition`
- Detailed proofs/logs: `/workspace/scratch/relay-production-proof/`
- Local test JDK: `/workspace/scratch/relay-jdk17`
- Local Android SDK: `/workspace/scratch/relay-android-sdk`

Important local proof files include `merged-full-checks.log`, `merged-full-monitor-final.json`, `canonical-agreement.json`, `final-validation-evidence.json`, `compatibility-evidence.json`, `starter-compatibility-evidence.json`, `latency-evidence.json`, `musical-pcm.json`, `browser-v10.json` and `SESSION-STATUS.json`.

Scratch files are ephemeral. Use committed source and the published evidence when transferring to another environment. Old local status JSON may predate the merge and emulator-test correction; this handoff records the later repository/CI audit.

The execution environment restarted during handoff preparation. The previous source checkout and scratch proof directory were absent in the fresh environment. This document was prepared from committed source, GitHub CI records and the verified implementation checkpoints above. No new local test run is claimed for this documentation-only change.
