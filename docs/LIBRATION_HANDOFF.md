# Libration handoff

Updated 2026-11-07, from a GitHub status check at 15:56 UTC.

## Current status

The finite instrument, recurring HOLD path and optional two-touch held instruments are implemented. [Implementation PR #933](https://github.com/BertCalm/SnipSnap/pull/933) merged on 2026-11-07 at 02:45 UTC as `50df635ae70ac4994fbf3d70026a2ddb18bbdd21`. All eight checks on its final head completed successfully, including the Android build, native tests, sharded JVM suites, JVM aggregate and emulator suite. There were no submitted reviews or inline review comments at the handoff check.

The implementation is already committed and merged. This handoff is a documentation-only follow-up; no further implementation change is bundled with it.

| Reference | Value |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Final implementation head | `206a9428be7e2bf822822c264ae1ed18f3b71413` |
| Audio generation / full local JVM source | `d6613e7ce840728d1c033c76af0a3b643a90170e` |
| Two-touch audition | https://libration-hold-audition.bertcalm.chatgpt.site/touch/ |
| Earlier single-touch HOLD audition | https://libration-hold-audition.bertcalm.chatgpt.site |

The owner accepted finite phone/MPC behavior on 2026-10-31. New HOLD and two-touch listening/hardware acceptance have **not** been recorded. Software and emulator checks do not replace that acceptance. Phone render timing is also unmeasured.

Start future code work from the updated default branch in a fresh feature branch. The implementation branch `claude/libration-velocity-layers` was deleted from the remote after merging; the commits above remain reachable through the merged history. Read [AGENTS.md](../AGENTS.md), then the repository's Luthier skill for DSP work and Steward skill for PR/CI work. Never approve or merge automatically.

## Product and sound decisions

Libration adapts rotating-frame three-body trajectories into SnipSnap's offline synthesis and sampled-instrument workflow. Gravity is computed before the audio loop. The held path periodically traverses those descriptors with continuous excitation and modal state; this is a musical recurrence method, not a claim that every gravitational trajectory physically closes.

- The named voices remain **GATHER, TWINS and DEPARTURE**, with twelve existing finite presets. No fourth voice is scheduled. REGION already reaches L4, L1, L2, L3 and L5.
- The first audition made NUDGE and RESONANCE hard to distinguish. The owner preferred the revised controls and subsequent musical bank. Preserve those accepted versions and references.
- Finite models 1–3 retain their saved rendering behavior. Explicit `heldModel=1`, supported with source model 3, identifies recurring HOLD. Legacy finite recipes omit that metadata.
- HOLD keeps its real onset, settles continuously, then repeats a verified sustain cycle. Key-up release is the sample player's amplitude fade, not a newly simulated physical departure.
- Two-touch delivery captures the existing exciter at **35% and 100% of saved TOUCH**. Saved TOUCH remains the hard anchor. Windows are MIDI **1–63 / 64–127**.
- Remove the relative native `velocity^.7` capture-level reduction once, then apply one shared `.94` peak guard. Do not normalize each layer independently. The player still applies live key velocity gain.
- Each capture retains its own onset, loop marker, source recipe, gain and diagnostics. Rendering is serial and cancellable, reusing one bounded physical population.
- Single-layer instrument recipes retain version 1 and their exact JSON shape. Layered recipes use version 2 with `touchModel=1` and `touchLayers=true`. Single-sample APIs reject layered specs rather than discarding a capture.
- SYNTH → LIBRATION → MAKE INSTRUMENT defaults **TOUCH LAYERS ON**; OFF preserves the old path. This delivery choice does not edit the source patch. Expect roughly twice the render work and retained sample audio.

The two-touch change leaves six existing files byte-identical to its base `8b6914927`: `Libration.kt`, `LibrationHeld.kt`, `LibrationKeys.kt`, `LibrationTrajectory.kt`, `LibrationPatch.kt` and `LibrationPresets.kt`. The frozen finite kernel SHA-256 is `033ba2696d06cc6ff81c1f9ed9c3665a662ac56b4b1b581c2d0f62529ac4239c`.

## Code and design entry points

| Area | Entry point |
| --- | --- |
| Finite physics, source versions and accepted voicing | [Libration.kt](../synth/src/main/kotlin/com/snipsnap/synth/Libration.kt), [LibrationTrajectory.kt](../synth/src/main/kotlin/com/snipsnap/synth/LibrationTrajectory.kt), [LibrationPatch.kt](../synth/src/main/kotlin/com/snipsnap/synth/LibrationPatch.kt) |
| Held renderer, recurrence diagnostics and session reuse | [LibrationHeld.kt](../synth/src/main/kotlin/com/snipsnap/synth/LibrationHeld.kt), [LibrationKeys.kt](../synth/src/main/kotlin/com/snipsnap/synth/LibrationKeys.kt) |
| Soft/hard capture, source recall and common gain | [LibrationTouchKeys.kt](../synth/src/main/kotlin/com/snipsnap/synth/LibrationTouchKeys.kt) |
| Versioned instrument recipe, layered assembly/export and preview | [LibrationPadMaker.kt](../shell/src/main/kotlin/com/snipsnap/shell/LibrationPadMaker.kt) |
| Phone controls, held spec, progress and cancellation | [SynthScreen.kt](../app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt); search for `holdLibrationTouchLayers` and `HeldSpec.Libration` |
| Existing phone sample/layer selection | [InstrumentStore.kt](../kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt), [KeyHit.kt](../shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt), [InstrumentEngine.kt](../shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt) |
| Reproducible audition generator | [LibrationTouchAuditionGenerator.kt](../shell/src/test/kotlin/com/snipsnap/shell/LibrationTouchAuditionGenerator.kt), [shell Gradle tasks](../shell/build.gradle.kts) |
| New DSP and package tests | [LibrationTouchKeysTest.kt](../synth/src/test/kotlin/com/snipsnap/synth/LibrationTouchKeysTest.kt), [LibrationTouchPadMakerTest.kt](../shell/src/test/kotlin/com/snipsnap/shell/LibrationTouchPadMakerTest.kt) |
| Phone UI tests | [LibrationSynthScreenTest.kt](../app/src/androidTest/kotlin/com/snipsnap/app/ui/LibrationSynthScreenTest.kt) |

Read [LIBRATION.md](LIBRATION.md) for finite design/acceptance, [LIBRATION_HOLD.md](LIBRATION_HOLD.md) for the recurrence method, and [LIBRATION_TOUCH.md](LIBRATION_TOUCH.md) for the layered delivery contract and measurements. Their dated validation entries are historical snapshots.

## Verified results and scope

The complete local JVM command at `d6613e7ce` exited **0**: **8,737 tests, zero failures, errors or skips**. The run took about 2 hours 29 minutes on this cloud host. All tracked source hashes and the clean tree were checked at completion.

| Module | Tests |
| --- | ---: |
| json | 16 |
| xpm | 51 |
| audio | 457 |
| kit | 508 |
| mpc3 | 95 |
| synth | 4,733 |
| loop | 439 |
| cli | 288 |
| shell | 2,150 |

All five new tests passed both their focused checks and the complete run. They cover source/gain replay, root and real recurrence, level-matched articulation in all three voices, silence, cancellation, v1/v2 recall, actual PCM24 export through both MPC writers and the phone sidecar, 63/64 selection, sustained playback and key-up release.

The later commit `206a9428b` strengthens the Android test to start from a complete factory recipe for each voice and updates documentation. Production and JVM inputs remain byte-identical to the full-run source. SDK 35/JDK 21 debug, instrumentation and Strike FX builds passed; the final debug/instrumentation rebuild also exited 0. Four Libration UI cases compile. No emulator was run locally; the subsequent GitHub emulator job succeeded. The debug APK has a verified v2 signature and SYNTH uses 176 DEX registers; that register count alone is static evidence.

The now-completed GitHub runs are [JVM/Android/native](https://github.com/BertCalm/SnipSnap/actions/runs/42322474599) and [emulator](https://github.com/BertCalm/SnipSnap/actions/runs/42322474601). The audition's downloadable validation JSON was published earlier while CI was running; its timestamped CI snapshot is historical, not a live status feed.

Independent artifact checks verified 162 phone/MPC descriptor-layer mappings, all sampler roots and loop markers, all three ZIPs' exact entries/CRCs, and release to silence in all 12 performances. Chromium checks covered all 12 button-started clips, seeking, note persistence/export, desktop 1440×1050 and mobile 390×844 layouts, and all three browser-built ZIPs against the originals. Earlier listening audio and packages remain unchanged.

Measured fixtures: matched articulation residual 5.40–7.10%; hard/soft body RMS ratios 1.0027–1.0076; maximum PCM24 seam error `3.47e-14`; maximum root-fit error `.00273` cents. Fresh cloud captures took 1.97–8.63 seconds each, median 2.79. These are fixture/cloud measurements, not owner hearing judgments or phone performance claims.

## Listening assets and deployment

The two-touch audition contains ROUND KEYS, FINE KEYS and SPLIT PATH: three instruments, 27 sampled roots and 54 native PCM24 samples. Its 12 performances reload exported files through `InstrumentEngine`: soft long hold, hard long hold, velocity 62/63/64/65 boundary and alternating C3–C5 phrase for each voice.

Each complete instrument includes both MPC program formats, TrackData/WAVs, the phone sidecar and its versioned recipe. In native MPC 3, velocity layers are under `layersv` and stored hard-first; match layers by sample name rather than list index. Keep the authored clipped minor-third range and explicit NoteOn metadata.

The full ZIPs exceed the Site's per-asset limit. The browser fetches and hash-verifies individual package files, then creates the complete ZIP without changing samples. Preserve that path when maintaining the page.

| Hosting reference | Value |
| --- | --- |
| Sites project | `appgprj_6ae68d4e3f288191965ec333a6cee149` |
| Audience | Existing owner-private; preserve it |
| Site source commit | `8998f36bf9accb9eeafcc4d54307c5482d2db640` |
| Successful deployment | `appgdep_6aee7b456c3481918c6f0fdf965a8184` |
| Saved version | `appgprj_6ae68d4e3f288191965ec333a6cee149~appgver_e25e7437d09881918590a6db8fdc48e1` |
| Deployment completed | 2026-11-07 01:13 UTC |

The final archive was 184,568,667 bytes, expanded to 197,215,030 bytes and matched all 117 files in its pushed source. Every individual static asset was below 25 MiB and the expanded Site below 256 MiB. This deployment succeeded; do not poll or restart it. Future Site edits should use the Sites workflow and fresh source credentials, preserve project identity/audience, and keep audio provenance pinned to its actual render commit. Never store credentials in the handoff or repository.

## Reproduction and local artifacts

Use a clean, committed checkout. The generator refuses an existing output destination.

```sh
# Focused JVM checks:
./gradlew --no-daemon :synth:test --tests '*LibrationTouchKeysTest'
./gradlew --no-daemon :shell:test --tests '*LibrationTouchPadMakerTest'

# Full JVM suite when no Android SDK is configured:
./gradlew --no-daemon test
# With an Android SDK, use: ./gradlew --no-daemon test -x :app:test

# Android build; requires a working Android SDK/NDK:
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleStrikeFx

# Export fresh instruments, recordings, recipes, hashes and provenance:
./gradlew --no-daemon :shell:generateLibrationTouchAudition \
  -PlibrationTouchOutput=/absolute/path/to/new-output
```

Do not run simultaneous Gradle builds in the same worktree. A passing host JVM suite does not compile Android when the SDK is absent. Cloud builds may also need the session's proxy and CA settings; use the cloud runtime instructions instead of copying credentials or disabling TLS verification.

These paths are conveniences in the current workspace, **not durable files in a fresh clone**:

| Item | Current path |
| --- | --- |
| Code checkout | `/workspace/SnipSnap-libration-velocity` |
| Site checkout | `/workspace/sites/libration-hold` |
| Audition output | `/workspace/artifacts/libration-touch/audition-d6613e7ce` |
| Installable debug APK | `/workspace/artifacts/libration-touch/snipsnap-libration-touch-d6613e7ce.apk` |
| Final instrumentation APK | `/workspace/artifacts/libration-touch/snipsnap-libration-touch-206a9428b-androidTest.apk` |
| Local JVM evidence | `/workspace/scratch/libration-touch-full-jvm-summary.json` and `libration-touch-full-jvm-results/` |
| Android evidence | `/workspace/scratch/libration-touch-final-ui-summary.json` |
| Independent artifact/browser audits | `/workspace/scratch/libration-touch-artifact-audit.json`, `libration-touch-browser-qa.json` |
| Fresh GitHub handoff snapshot | `/workspace/scratch/libration-handoff-github-snapshot.json` |

Debug APK SHA-256: `b96518e7892ea39211c397b3ce41efd1250bc72ed58a7dbf70cb53925e4c122d`. It is byte-identical between the audio/full-JVM source and final implementation head. Final instrumentation APK SHA-256: `89b67f5fd2121da19f8bf38afaf333fa3c1654f825d7a7fa99d1fbab54eabd34`.

Durable handoff material is the merged source, this document, the three design documents, GitHub run links and the hosted audition's downloadable recipes/provenance/validation. Regenerate local packages or APKs if the workspace artifacts are unavailable.

## Remaining work

1. Record an owner listening verdict for new HOLD/two-touch playing. Start with the 62/63/64/65 examples: judge articulation, boundary level, moving sustain across multiple wraps and key-up release. Do not infer acceptance from silence or the earlier finite approval.
2. Check the new option on a real phone and MPC. On phone, toggle TOUCH LAYERS ON/OFF, confirm the source survives navigation, cancel a generation and retry. On MPC, extract the complete instrument, load the appropriate program with its TrackData, cross 63/64 and hold beyond the stored file length before releasing.
3. Measure phone cold/warm preview and complete instrument-generation time, retained memory/storage and cancellation response. Current timings are cloud-only.
4. Record device, recipe, velocities and the actual verdict in the evidence ledger. If a sound change is requested, keep accepted finite/source versions and old audition artifacts reproducible; version any new rendering behavior explicitly.

No additional voice is currently scheduled. Further voices would be a separate sound-design decision after this listening round.
