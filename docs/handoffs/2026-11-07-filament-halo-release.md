# SnipSnap Filament CURL halo release handoff

Prepared **2026-11-07 UTC**. The CURL release prototype is implemented,
committed, published for listening, and merged. The next work is listening and
physical-device admission before expanding the feature.

## Start here

| Item | Location / verified state |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Implementation PR | [#943](https://github.com/BertCalm/SnipSnap/pull/943), merged 2026-11-07 at 14:22:16 UTC |
| Merge commit | `ae8a8d9efcec88d63f6fec9e7a0a31a95dd63760` |
| Implementation branch / tested head | `codex/filament-halo-release` / `e94928c7ab2313de8e8fde9ec4d59a6a38d1fa95` |
| Audio capture source | `35006a74ac0efd25134294ed0d49a2358f69a756` |
| Private A/B and download | <https://filament-snipsnap-audition.bertcalm.chatgpt.site/release-study/> |
| As-built contract | [FILAMENT_HALO_RELEASE.md](../FILAMENT_HALO_RELEASE.md) |
| Earlier engine work | [FILAMENT.md](../FILAMENT.md) and [original engineering spec](../superpowers/specs/2026-10-19-filament-engine-engineering-spec.md) |

**Start new implementation from the current default branch.** The runtime work
is already merged; do not reapply its commits. This cloud checkout remains on
the feature branch, with this documentation commit following the tested head.
The default branch observed during handoff was
`ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`; refresh it before new work.

Read [AGENTS.md](../../AGENTS.md), the repository
[Luthier skill](../../.claude/skills/luthier/SKILL.md) for DSP changes, and
[Steward](../../.claude/skills/steward/SKILL.md) for PR/CI work. Do not skip tests,
force-push, approve, or merge PRs as part of this handoff.

## What is built

The gesture is **strike → sustain → release the body → let the halo linger**.

This is one model-3 CURL preset, **Loose Curl**, captured at C4/MIDI 60 with one
excitation strength. The program covers MIDI 54–66. It is a release experiment;
the standard THREAD/PULSE/CURL makers, presets, touch banks and wide-key choices
retain their established behavior.

- One network run captures the weighted through collector as the body. The
  remaining weighted spill collector is the halo: escaped branch sound plus
  resonators. Both share the actual onset, clock, FIR, gain and loop marker.
- The halo is the residual of the conditioned mixed and body captures. There
  is no per-stem normalization or second synthesis run.
- A uses one mixed sample with a 180 ms whole-note release. B uses two
  overlapping keygroups with a 180 ms body release and an 850 ms halo release
  on the phone/reference player.
- The phone starts both samples with the existing atomic
  `NativePads.noteOnLayers`, then sends each voice its own release fade.
  The reference player counts and steals the pair as one logical note.
- Per-zone releases reach both MPC formats. Phone sidecars opt into v3 with
  `stackZones: true` and a required release on every zone. Ordinary sidecars
  retain their v2 shape; v1/v2 overlap remains first-match.

**The release fades a captured periodic spill. It does not stop injection and
simulate an unforced physical decay.** In particular, test early note-off for
a later bloom that feels detached from the strike.

The resolver requires equal selected sample lengths, loop markers and pitch
ratios across components. A missing or incompatible component silences the
whole note. It permits at most four components; this prototype uses two.
Eight key presses remain eight logical notes. The native pool has 32 sample
voices, including release tails, so overload behavior still needs device testing.

## Code map

Paths are relative to the repository root. These APIs were verified on the
implementation head above; inspect the current default before extending them.

| Responsibility | Entry point |
| --- | --- |
| Public CURL-only capture API | [`FilamentHaloKeys.kt`](../../synth/src/main/kotlin/com/snipsnap/synth/FilamentHaloKeys.kt), `capture`, `Components`, capture version 1 |
| Same-network body history and shared conditioning | [`Filament.kt`](../../synth/src/main/kotlin/com/snipsnap/synth/Filament.kt), `keyComponents`, `retainComponents` |
| Zone release and phone stack persistence | [`InstrumentStore.kt`](../../kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt), `STACK_VERSION`, `Zone.release`, `Instrument.stackZones`, `releaseFor`, `toProgram` |
| Program model and XPM envelope | [`KeygroupWriter.kt`](../../xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt), nullable `Keygroup.volumeRelease`, opt-in `KeygroupProgram.stackZones` |
| XTY envelope | [`Mpc3TrackWriter.kt`](../../mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt), per-zone amp-envelope `Release.value0`; global amp envelope remains unlinked |
| Shared selection and timing contract | [`KeyHit.kt`](../../shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt), `resolveAll`, `resolveAllByZone`, `Component` |
| Reference rendering and whole-note stealing | [`InstrumentEngine.kt`](../../shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt) |
| Live phone adapter | [`InstrumentPlayer.kt`](../../app/src/main/kotlin/com/snipsnap/app/InstrumentPlayer.kt) |
| Audition/export generator | [`FilamentHaloAuditionGenerator.kt`](../../shell/src/test/kotlin/com/snipsnap/shell/FilamentHaloAuditionGenerator.kt), task in [`shell/build.gradle.kts`](../../shell/build.gradle.kts) |
| Development phone import | [`install-filament-halo-prototype.sh`](../../scripts/install-filament-halo-prototype.sh) |

Tests: [`FilamentHaloKeysTest`](../../synth/src/test/kotlin/com/snipsnap/synth/FilamentHaloKeysTest.kt),
[`StackedReleaseTest`](../../shell/src/test/kotlin/com/snipsnap/shell/StackedReleaseTest.kt),
and `pad_engine_synchronized_components_release_independently` in
[`engine_tests.cpp`](../../app/src/main/cpp/test/engine_tests.cpp).

Implementation commits, in order:

1. `35006a74a` — capture, player/export semantics, tests, generator and contract.
2. `34c7dbf3f` — explicit development phone import helper and instructions.
3. `e94928c7a` — locate the native proof beside atomic-trigger tests, preserving
   upstream tests during integration. Assertions and runtime code are unchanged.

## Evidence and CI status

Local validation at the implementation head passed with process exit 0:

| Check | Result |
| --- | --- |
| Full nine-module JVM suite | 8,771 tests; zero failures, errors or skips |
| Android host tests | 10 passed |
| Android debug APK | Assembled successfully |
| Android instrumentation Kotlin | Compiled; this is not a device execution |
| Native CMake/CTest harness | All six suites passed |
| New prototype JVM cases | All eight passed |
| Exported delivery | PCM24, sampler sheets, archive CRC and 13 captured-file hashes verified |
| Import helper | Syntax and local checks passed; host simulation verified 11 imported files, preserved an unrelated file, and refused repeat import |

PR #943's [main CI run](https://github.com/BertCalm/SnipSnap/actions/runs/42333003715)
passed `jvm-other`, all three synthesis shards, `jvm-tests`, `android-build`
and `native-tests`.

Its [historical emulator run](https://github.com/BertCalm/SnipSnap/actions/runs/42333003687)
failed two `com.snipsnap.app.ui.SynthScreenTest` cases with
`AssertionError: REOPEN PAD RECIPE is disabled`:

- `new_onset_voices_land_as_native_recipes_and_reopen_without_losing_their_model`
- `original_and_model_three_recipes_keep_their_renderers_when_reopened_after_model_four`

A later **full default-branch push** emulator run
[42381836629](https://github.com/BertCalm/SnipSnap/actions/runs/42381836629)
**succeeded** at `f728e09a386d1f1ca7c42b9c41a739f2cb584daf`.
The newest default-branch emulator run observed during preparation,
[42386085814](https://github.com/BertCalm/SnipSnap/actions/runs/42386085814),
was still running at `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`.
These are time-specific observations. Check the current default before
investigating the older failure; no root cause or particular fix is claimed here.
The older emulator report artifact is `13559793322`, named
`emulator-test-reports-af10c3ee4b38017da8a3da78e963a8a7c7da8de4`.

Neither emulator success nor local compilation establishes physical phone or
MPC acceptance. Owner listening acceptance has not been recorded.

## Audition and artifact provenance

The performances read the exported PCM24 through `InstrumentEngine`, at host
velocity 0.8 and the existing 0.35 voice level. Both hold past the first wrap.

| Measurement | Value |
| --- | --- |
| Native frames / loop start | 405,896 / 304,422 |
| Note-off frame / time | 512,882 / 11.629977 seconds |
| Maximum held-prefix difference, A versus B | `3.7252903e-8`, below one PCM24 step |
| A tail RMS, 333–667 ms after release | `0.0` |
| B tail RMS, same window | `9.138287154897082e-4` |
| Observed workstation capture | 3,947 ms while other checks ran; not a phone timing claim |

The saved patch has model 3, velocity 1, seed 47, profile `VOICE`, and macros
INJECT 0.38, BEND 0.50, LENGTH 0.68, BUNDLE 0.55, COLLECT 0.58, HOLD 0.
Capture forces HOLD to 1 without rewriting that saved patch.

The private page serves `curl-release-prototype.zip`. Its handoff-time SHA-256 is
`bea333e8b615982eefe4d7747d25bad753a86d60afe3339701ea7bc3a241ebac`.
The root manifest records audio source `35006a74a`; `phone-admission.json`
separately records helper source `34c7dbf3f`. Later commits do not change PCM.

Cloud-only recovery paths, which may not exist on another machine:

- `/workspace/scratch/filament-halo-validated-audition/` — complete delivery,
  source programs, manifest and supplemented ZIP.
- `/workspace/scratch/filament-halo-handoff/validation.json` — local test receipt.
- `/workspace/scratch/filament-halo-handoff/snipsnap-curl-halo-prototype-e94928c7a.apk`
  — local debug build, 77,256,119 bytes, SHA-256
  `8aebd00d6f9e573930a80fb0104e9eae69eb92f6fd8cbf239693eb1ea7d948f2`.
- `/workspace/scratch/filament-halo-gate.log` — full 72-minute gate;
  `filament-halo-final-gate.log` and `filament-halo-final-native.log` — final checks.
- `/workspace/sites/filament-snipsnap-audition/` — separate Site source repository.

The Site project is `appgprj_6ad69f3c0a18819188be36e58a6c2959`, published source
`d419bb7cab5d23df03e57e5feb68e59c439ce839`. Preserve its private audience and all
earlier listening assets. Deployment succeeded; browser playback QA was not
performed during the implementation session. Publishing credentials are not
stored in this handoff.

## Reproduce and load

Use JDK 17. With an installed Android SDK, the implementation gate was:

```sh
./gradlew --no-daemon --parallel --max-workers=4 \
  -Dorg.gradle.jvmargs=-Xmx4g -Pkotlin.compiler.execution.strategy=in-process \
  test -x :app:test :app:testDebugUnitTest \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Without an SDK, run `./gradlew --no-daemon test` and omit Android tasks and the
`:app:test` exclusion. Native checks are the standalone CMake/CTest commands in
Steward. For focused iteration:

```sh
./gradlew :synth:test --tests '*FilamentHaloKeysTest' \
  :shell:test --tests '*StackedReleaseTest'
./gradlew :shell:generateFilamentHaloAudition \
  -PfilamentHaloOutput=/absolute/fresh/output/directory
```

Generate from a clean checkout: the manifest records Git dirtiness and the
phone helper requires a clean-source delivery. Always use a fresh directory.
The generator creates the base audition ZIP. The published ZIP was subsequently
supplemented with the tracked import helper, `PHONE-ADMISSION.txt`, and
`phone-admission.json`. Preserve/repackage those extras if regenerating delivery;
the task alone does not add them. The Site copy extracts inline CSS/JS into
`study.css` and `study.js` for hosting; audio bytes remain unchanged.

For phone admission, install a compatible debuggable build, open it once, then
close it. Unzip the download on the computer and run:

```sh
bash install-filament-halo-prototype.sh --check-only .
bash install-filament-halo-prototype.sh .
```

The helper needs Bash, Python 3, tar, and authorized ADB for device import.
It defaults to `com.snipsnap.app.brassgrow`; a final argument selects another
debuggable package. It verifies hashes, refuses existing destinations and
copies the two programs into app-private `files/Kits/Instruments`. It does not
install/uninstall the app. Open **MAKE → INSTRUMENTS**, compare the two CURL
programs at C4, first with short taps and then a hold of at least 12 seconds.

For MPC 3, retain each `.xty` beside its complete matching `_[TrackData]`
directory. For MPC 2, load the `.xpm` inside that data directory beside its WAVs.

## Next work, in order

1. **Listen to A/B at unchanged gain.** Decide whether the halo belongs to the
   strike, releases clearly, and leaves room for the next note. Record the
   owner's judgment; measurements do not establish listening acceptance.
2. **Admit on a physical phone.** Check quick taps, release at different loop
   phases, first wrap, pedal/all-off, eight-note chords and repeated-note tail
   pressure. The 32-voice native pool can be stressed by release tails.
3. **Admit on an MPC.** Verify both overlapping keygroups actually play and
   sustain, then measure separate releases. Phone/reference values are seconds;
   the existing MPC writers emit raw envelope parameters. Exact MPC durations
   and a seconds-to-parameter calibration remain unverified. Corpus evidence
   supports the structure, not timing equivalence.
4. **After acceptance, expand deliberately.** Extend to the remaining voices,
   existing touch layers and native root layouts, then add opt-in controls and
   an appropriate preparation cache. Keep shared gain/phase and preserve the
   legacy default path. A cache must include the complete patch, MIDI, capture
   version and sample rate; release edits should reuse prepared PCM.

No new synthesis voice, full-bank rollout, production release UI, unforced
decay solver, or physical-device acceptance is completed by this prototype.
