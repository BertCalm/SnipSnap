# SnipSnap STRIKE / FX: desktop handover

Prepared October 31, 2026. This document closes the cloud implementation session and hands the remaining audition and product work to a desktop session. No new sound or UI feature was implemented during this wrap-up.

The document and source archive are published on `claude/strike-fx-desktop-handover`. That branch is a dated handover snapshot; use the latest default branch for product work. A fresh default-branch clone may not contain this handover until its documentation PR is merged, so retain the [direct document link](https://github.com/BertCalm/SnipSnap/blob/claude/strike-fx-desktop-handover/docs/handoffs/2026-10-31-strike-fx-desktop.md).

## Start here

**The native thumb strip and the STRIKE FX listening demo are already merged.** Start new work from the current default branch, not from the old cloud checkout or the original PR head. Final pre-merge JVM, Android build, native and emulator checks all passed. The next open gate is Josh's phone audition of the stronger FX demo; no feedback on that demo is recorded in this conversation yet.

Josh's original goal was to make soft/hard hits change tone or effects such as reverb and delay, with a mobile-friendly control that one hand can move while the other plays pads. A browser prototype was well received. On the first native velocity-layer build, Josh said: **“I think it works. It's a bit hard to tell.”** He then approved a clearer dry → reverb → echo demo with the active layer highlighted. That demo was delivered. His latest request is to hand the remaining work to a desktop session.

| Reference | State checked October 31 |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Default head inspected for this handover | `2b3e16882d0fa2657c47058cfb3551b439e7cf47` |
| Original native strip | [PR #650](https://github.com/BertCalm/SnipSnap/pull/650), merged October 24 |
| FX demo | [PR #678](https://github.com/BertCalm/SnipSnap/pull/678), merged October 26 at 03:56:35 UTC |
| Original FX implementation commit | `cc5be9d4c37c043526ad999030f24ce361fffcb0` |
| Final pre-merge FX branch head | `b02a5654bfe9e2b13c343ea75239e34ddb9f652d` |
| FX merge commit | `09e70bef6fa13168559a0054afdb8e43a6dcfe2c` |
| Playing and implementation guide | [STRIKE_STRIP.md](../STRIKE_STRIP.md) |

The default head above contains the FX merge. It has additional work beyond the final pre-merge build. In particular, MIDI output was integrated with the pad callbacks, and the classic look was retired. Current strip highlights use `scheme.accent.tape`. Preserve those later changes; do not replace current files with the old implementation snapshots.

## What is implemented

### Native performance control

- PADS and FULL share an optional vertical STRIKE strip, with LEFT/RIGHT, STAY/RETURN and VOL OFF/ON.
- Top is hard, bottom is soft. With STRIKE disabled, the existing pad-position response remains: top of a pad soft, bottom hard.
- Each hit snapshots the strip value and chooses a cached velocity-layer sample or chain zone. Moving the strip does not change a ringing note or trigger another hit.
- Layer selection spans MIDI 1–127. Linked playback gain uses a separate 0.20–1.00 range; unlinked gain is 1.00 before the pad's level and pan. Keeping these curves separate gives the softest zone usable thumb travel.
- Settings are saved as screen performance state, not kit/export parameters. Existing MIDI input and sequencer velocities retain their own source.
- Long-lived pad pointer callbacks read current state through `rememberUpdatedState`, including after kit changes.
- The LAST row highlights the layer selected by the last successful hit. Sliding or RETURN leaves that snapshot alone until another hit. Kit changes clear it. Playback and the indicator share `PadHit.layerIndex`.
- Labels are optional `KitLayer.displayName` values, stored as `name` in each `kit.json` layer object. Unnamed layers show LAYER 1, etc.; an ordinary unlayered pad shows SINGLE.

### STRIKE FX starter

The starter ID is `strike-fx`, displayed as STRIKE FX near FACTORY. Four pads are populated: A01 RIM, A02 CLAP, A03 SNARE and A04 BLOCK. Each has DRY, REVERB and ECHO zones, in ascending strip order. The remaining pads are empty.

`StrikeDemoKit` reuses THUMP/TINES sources and the existing SPRING/ECHO rack. Both effects receive the same dry source independently. The room decay setting is approximately 0.52 seconds, and echoes are approximately 293 ms apart. Rack tails are bounded to one extra second, with a 4 ms terminal fade on the wet versions. Twelve WAVs are prepared when the starter is created and cached by the normal pad engine. No effect rendering runs during a pad tap or thumb move.

The main saved recipe reproduces the ECHO sample. **Rebuilding that recipe alone does not regenerate the complete three-layer bank.** Recreate the starter to recover the whole demo. Full-bank authoring and regeneration for arbitrary user pads remain future work.

The optional `strikeFx` build type produces `app/build/outputs/apk/strikeFx/app-strikeFx.apk`, identified as `com.snipsnap.app.strikefx` and labelled **SnipSnap STRIKE FX**. It has its own shelf, separate from the existing development audition app. CI retains both debug and STRIKE FX APK artifacts.

## Verified results and limits

These results are pinned to the final pre-merge head `b02a5654…`, not a claim that the October 31 default branch was retested during this handover.

| Check | Final result | Evidence |
| --- | --- | --- |
| JVM suites | Success | [Tests run 40250900211](https://github.com/BertCalm/SnipSnap/actions/runs/40250900211), job `121342336749` |
| Android APK builds and device-test compilation | Success | Same run, job `121342336737` |
| Native audio tests | Success | Same run, job `121342336406` |
| Emulator suite | Success | [Device run 40250900319](https://github.com/BertCalm/SnipSnap/actions/runs/40250900319), job `121342337232` |
| Real phone feel and clarity of the FX demo | Awaiting Josh's feedback | No audition report after delivery in this conversation |

The earlier original-head emulator run `40209538385` failed; the final pre-merge emulator run above passed. Do not carry the older “tests still running” PR-body text forward as current status, or assume that the early failed run is still an open failure. Its exact historical cause was not diagnosed during this wrap-up.

Earlier focused checks passed for deterministic, finite and bounded audio; unchanged dry attack shape; distinct reverb/echo timing; complete tail playback; all zones being reachable; and label persistence through saving and supported standalone MPC sidecars. The Compose test covers the named highlight, readability and RETURN retaining the last-hit selection.

Original desktop measurements, not phone measurements:

- Starter creation including WAV writes: 288 ms in the first focused run.
- RIM reverb energy at 120–240 ms: 0.0364 times the dry attack energy.
- ECHO energy in that gap: zero; first and second repeat energies: 0.7583 and 0.1639 times the dry attack energy.

Energy ratios and equal peaks do not establish equal perceived loudness. No assistant listening claim is made. The interrupted cloud follow-up test log is not evidence of a completed local suite; completed CI above supplies the final pre-merge result.

## First desktop session: reproduce and audition

1. Read `AGENTS.md`, the repository Luthier skill for DSP work, and Steward for PR/CI work. Read [STRIKE_STRIP.md](../STRIKE_STRIP.md).
2. Fetch the current default branch and inspect local changes before choosing a new worktree. Do not reopen or cherry-pick the merged feature PRs.
3. Build the current STRIKE FX variant, keeping the identity/signing notes below in mind.
4. Have Josh create STRIKE FX, enable STRIKE, choose STAY and VOL OFF, then tap A01 RIM at bottom, middle and top. Leave two seconds between hits. Confirm DRY → REVERB → ECHO and the LAST highlight; then try the other three pads and FULL.
5. Record whether the contrast is clear, whether either thumb can reach the strip comfortably, and whether simultaneous playing produces missed hits, unexpected triggers or clicks. Exercise RETURN, portrait/FULL transitions, kit changes, background/foreground and volume linking.
6. Check MIDI and beat playback at both strip extremes. Current touch output sends the layer-picking velocity over MIDI; gain scaling stays local. Preserve the required `fromMidi` distinction, successful-play guards and paired note-offs when editing `KitScreen`.

A safe checkout pattern, from an existing clone after inspecting its state:

```sh
git status --short
git fetch origin
git worktree add -b claude/strike-fx-next ../SnipSnap-strike-fx-next \
  origin/claude/mobile-mpc-drum-sampler-t58x74
```

Choose an unused branch/worktree name. Treat `2b3e16882…` as a dated reference, not a requirement to discard newer default-branch work.

## Remaining product work

The following is the recommended next sequence, not a claim that these features exist or a newly approved detailed design.

1. **Close the listening gate.** Establish that the strong demo is obvious and that the two-handed gesture works on Josh's phone. If it is still unclear, diagnose the actual selected layer and audible tail before adding more controls.
2. **Apply a response to an ordinary user pad.** Extend the existing layer-authoring path to prepare brightness, reverb or echo variations from one captured or synthesized source. Begin with one response and a small layer bank. The existing format supports at most four velocity layers; continuous live effects require a separate design.
3. **Make the complete bank reproducible.** Persist the source and per-layer effect settings so reopening, editing and rebuilding regenerate every zone. Protect existing captured material. The current main ECHO recipe is insufficient as a general bank recipe.
4. **Refine the control from audition feedback.** Consider depth, direction, response choice, strip reach and next-hit feedback only where listening or playing reveals a need. Keep gain independent. Three/four cached layers are stepped choices, even if the thumb position is continuous.
5. **Verify persistence and delivery.** Save/reopen and supported MPC exports must retain all audio and velocity windows; names travel as SnipSnap metadata. Use a stable desktop signing setup and a durable distribution route for future phone builds.

A useful next milestone is: create FX layers on one ordinary pad, play them with STRIKE at steady gain, save/reopen without changing the sounds, and export/import the supported kit format with the complete layer bank intact. Keep synthesis off the performance path. Do not fold continuous modulation of already-ringing tails into this milestone without a separate playback design.

## Code map

| Concern | Repository paths |
| --- | --- |
| Mapping and last-hit data | `shell/src/main/kotlin/com/snipsnap/shell/StrikeStrip.kt` |
| Shared zone selection and independent gain | `shell/src/main/kotlin/com/snipsnap/shell/PadHit.kt`; `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt` |
| Strip state, controls, fader, highlight | `app/src/main/kotlin/com/snipsnap/app/ui/StrikeStrip.kt` |
| Normal/fullscreen wiring and MIDI integration | `app/src/main/kotlin/com/snipsnap/app/ui/KitScreen.kt`; `PlayScreen.kt`; `PadGrid.kt` |
| Demo audio and starter registration | `synth/src/main/kotlin/com/snipsnap/synth/StrikeDemoKit.kt`; `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt` |
| Existing layer/effect mechanisms | `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt`; `FxChain.kt`; `Spring.kt`; `Echo.kt`; `PadRecipe.kt` |
| Layer names and persistence | `kit/src/main/kotlin/com/snipsnap/kit/Kit.kt`; `KitAssembler.kt`; `KitStore.kt`; `KitExporter.kt`; `ImportSidecar.kt` |
| Demo packaging | `app/build.gradle.kts`; `app/src/strikeFx/AndroidManifest.xml`; `.github/workflows/tests.yml` |

Relevant tests: `StrikeDemoKitTest` in synth; `StrikeDemoTest`, `StrikeStripTest`, `PadHitTest`, `StarterKitsTest` and `ConventionTest` in shell; `ImportSidecarTest` and `StandaloneExportSidecarTest` in kit; `StrikeStripTest` in app/androidTest.

## Build and checks

Use JDK 17 and an Android SDK. The inspected app uses SDK 35, NDK `27.2.12479018` and CMake `3.22.1`. Configure the SDK with `local.properties` or `ANDROID_HOME`; do not copy cloud-specific proxy settings or absolute SDK paths to the desktop.

Focused checks and APK:

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.StrikeDemoKitTest \
  :shell:test --tests com.snipsnap.shell.StrikeDemoTest \
  --tests com.snipsnap.shell.StrikeStripTest --tests com.snipsnap.shell.PadHitTest \
  :kit:test --tests com.snipsnap.kit.ImportSidecarTest \
  --tests com.snipsnap.kit.StandaloneExportSidecarTest
./gradlew --no-daemon :app:assembleStrikeFx :app:compileDebugAndroidTestKotlin
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.StrikeStripTest
```

For code changes, follow Steward's full JVM gate: `./gradlew --no-daemon test -x :app:test` when an SDK is present, or `./gradlew --no-daemon test` without one. Run the native CMake checks if changing C++ or its build configuration. Read the current workflow for the complete device gate. Judge success from exit codes and completed jobs. The full JVM library render tests can take tens of minutes; do not mistake a long render for a hung process. This handover only adds documentation and a reference archive, so the full application suite was not rerun.

## Downloads, signing and preserved browser source

- **Original delivered FX build:** [artifact 12605079202](https://github.com/BertCalm/SnipSnap/actions/runs/40209538348/artifacts/12605079202), original head `cc5be9d4…`, expires **November 1, 2026 at 16:03:43 UTC**. Its downloaded ZIP digest, APK signature, package identity and four ABIs were verified in the implementation session.
- **Final pre-merge build:** [artifact 12621226056](https://github.com/BertCalm/SnipSnap/actions/runs/40250900211/artifacts/12621226056), head `b02a5654…`, expires **November 2, 2026 at 00:51:26 UTC**. Metadata and successful build checked October 31; this newer APK was not separately downloaded or installed during wrap-up.
- Both artifacts were reported unexpired on October 31. Their filenames contain GitHub's temporary PR merge SHA, which differs from the feature head. Use the run/head mapping above.
- GitHub artifact-page links require GitHub sign-in. Previous workspace-only download links failed on the phone; a later directly shared signed URL expired before it was clicked. Use the artifact page, or a new durable distribution path, rather than sharing a cached signed URL.
- CI debug signing keys are ephemeral. A new CI APK with the same package may not update an earlier installation. Establish stable signing for desktop iterations; do not automatically uninstall an existing app and lose its shelf. The STRIKE FX variant installs alongside the earlier `.brassgrow` development app, but successive STRIKE FX builds share `.strikefx`.
- [Browser prototype](https://snipsnap-touch-lab.bertcalm.chatgpt.site): historical interaction reference, not proof of Android audio behavior. Hosting was not revalidated during wrap-up.
- [Preserved browser source ZIP](assets/2026-10-31-touch-lab-source.zip): all 15 prototype files, including its WAV assets, plus the original uncommitted `prototype/README.md` patch. The archive contents were byte-compared with the source and its ZIP integrity checked. SHA-256: `4f42eab6e491c95605b72c07b173e5a53405170336a432619cd79ae2f2c086c3`.

Unzip the source into a scratch directory. Run `python3 -m http.server 4173 --directory touch-lab`, open `http://localhost:4173`, and tap Start audio. Its optional check is `node touch-lab/verify.mjs`. The browser prepares four layers for each of brightness/echo/reverb; its JavaScript effects are experiments, not ports of the Kotlin rack. Its README is historical and predates the native implementation described here. The included working-tree patch is preserved for reference, not intended to be blindly applied to the current README.

## Cloud workspace inventory

These paths identify retained evidence in the old environment; the desktop does not need them to build the merged feature.

- `/workspace/SnipSnap-strike`: clean at original FX head `cc5be9d4…`, branch `claude/strike-fx-demo`. It is behind the final merged branch; do not use it as current product code.
- `/workspace/SnipSnap`: old `claude/thumb-strip-prototype` checkout at `fd76ffa38…`, with modified `prototype/README.md` and untracked `prototype/touch-lab/`. Both are preserved in the source archive and left untouched.
- `/workspace/SnipSnap-check`: old detached verification worktree, not current code.
- `/workspace/SnipSnap-handover`: isolated documentation worktree based on `2b3e16882…`.
- `/workspace/scratch/strike-fx-focused.log`: focused checks and original Android build.
- `/workspace/scratch/strike-fx-validation.log`: broad run with the subsequently corrected README starter-count failure.
- `/workspace/scratch/strike-fx-shell-full-results`: archived results from that shell run.
- `/workspace/scratch/strike-fx-final-validation.log`: incomplete follow-up log. The environment restarted; no old Gradle/emulator process was running at wrap-up. Do not attempt to resume prior tool session IDs.
- `/workspace/scratch/SnipSnap-STRIKE-FX-ci.zip` and `.apk`: verified original delivered binary, not the final pre-merge APK.

## Paste into the desktop session

> Continue SnipSnap's STRIKE / FX work. Read the handover at https://github.com/BertCalm/SnipSnap/blob/claude/strike-fx-desktop-handover/docs/handoffs/2026-10-31-strike-fx-desktop.md, then AGENTS.md and the relevant repository skills. PRs #650 and #678 are already merged, and the final pre-merge feature checks passed. Start from the latest default branch and preserve the later MIDI-send integration and modern UI. First establish Josh's feedback on the STRIKE FX dry/reverb/echo demo and verify the two-handed phone interaction. The recommended next milestone is preparing reproducible FX layers for one ordinary user pad, with independent volume, save/reopen/export coverage, and no DSP at pad-down. Distinguish stepped sample selection from live modulation of ringing notes. The original cloud checkout and browser README are historical; the preserved browser source archive is an interaction reference. Do not treat old pending CI notes or the early emulator failure as the current final-branch status.
