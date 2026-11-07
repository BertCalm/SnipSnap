# Trefoil native HOLD handoff

Prepared 2026-11-07 UTC. The implementation is committed, pushed and merged.
This handoff adds documentation only. The next work is reconciling the emulator
result with the current default branch, physical-phone measurement and listening
to the new held instrument on an MPC.

## Start here

| Item | Location / state |
| --- | --- |
| Repository | https://github.com/BertCalm/SnipSnap |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Implementation PR | https://github.com/BertCalm/SnipSnap/pull/944 — merged 2026-11-07 at 14:22:16 UTC |
| Merge commit | `261624934d47babb66d6725748d2acc07f354f07` |
| Implementation head | `712013b07332bdfe492a95b9b533ff945104e922` |
| Historical work branch | `claude/trefoil-held-proof`; this handoff is committed there |
| Default head when checked | `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`, containing the merge |
| Native audition | https://trefoil-snipsnap-audition.bertcalm.chatgpt.site/held/native/ |
| MPC trial ZIP | https://trefoil-snipsnap-audition.bertcalm.chatgpt.site/held/native/Trefoil-Held-MPC.zip |
| As-built design | https://github.com/BertCalm/SnipSnap/blob/712013b07332bdfe492a95b9b533ff945104e922/docs/TREFOIL_HELD.md |
| Published verification | https://trefoil-snipsnap-audition.bertcalm.chatgpt.site/held/native/verification.json |
| Source replay bundle | https://trefoil-snipsnap-audition.bertcalm.chatgpt.site/held/native/native-source.zip |

The audition was published with the Site's existing owner-only access. Its
recordings and downloads are available to the owner. The repository links are
the portable source references.

Start new development from the current default branch, which already contains
this implementation. The old cloud work branch is behind later upstream work.

```sh
git clone https://github.com/BertCalm/SnipSnap.git
cd SnipSnap
git switch -c claude/trefoil-hold-followup origin/claude/mobile-mpc-drum-sampler-t58x74
git merge-base --is-ancestor 261624934d47babb66d6725748d2acc07f354f07 HEAD
```

Read `AGENTS.md` and `.claude/skills/steward/SKILL.md`. For DSP changes also read
`.claude/skills/luthier/SKILL.md` and its conventions. The reset-default correction,
integration merge and comment cleanup are complete. Do not reapply old patches
or old scratch-file fast-forward instructions.

## Owner decisions and listening status

- The finite Trefoil bank and its hardware audition were accepted on 2026-11-01.
- The owner approved the Python natural note-off sound, reported clipping or
  repeat clicks elsewhere, then accepted the revised playback and authorized
  the Kotlin port on 2026-11-07.
- The native Kotlin comparison and this new held MPC trial have no recorded
  owner listening verdict yet. Physical phone rendering has not been measured.
- NUDGE remains zero in held mode. Preserve the accepted finite sounds and their
  saved recipes while working on HOLD.

The native page has three sections: Kotlin/Python loop comparison, natural DSP
note-off, and the actual exported-sample demonstration. The A/B should sound
very close; listen for changes in character, movement and the repeat. The
sampler demo crosses a repeat at about 2.556 seconds and lifts the key at 5.35
seconds. The older Python study remains at `/held/`, and the accepted finite
bank remains at `/playable/` on the same Site.

## What is implemented

`TrefoilPatch` has explicit FINITE/HELD playback. Finite models 1 and 2 keep their
existing behavior and default JSON shape. Held mode uses model 2 with a separate
`heldVersion=1`, `HOLD=1`, `NUDGE=0`, and a saved sampler release in seconds.
MIDI is bounded to 36–84. WEAVE is either zero for stationary geometry or
.34–1 for moving geometry; its held default is .42 for every voice. Release is
.1–1.5 seconds, default .6.

The DSP solves the repeating modal state using an affine period solve or a
bounded contractive iteration, then solves seven feedforward memory modes.
Geometry is a cached ordered figure-eight orbit. Output is mono 44.1 kHz from
4× internal rendering, using the shared band limiter/decimator and continuous
16 Hz DC blocker. The genuine adjacent cycles establish recurrence. Keep the
versioned source law `seedFor("TREFOIL-HELD-1", seed, midi)` stable.

The phone prepares samples off the UI thread, cancels stale work, and uses the
existing sample player for held playback. The audio cache is bounded to 12
entries and 8 MiB. Choosing FINITE restores the previous finite timbre/model
while retaining current root, velocity and release. SEND/SPREAD make finite
copies with a 20 ms terminal fade. MAKE INSTRUMENT retains complete sustain
loops and exports three octave-spaced native roots.

`TrefoilHeld.renderNote` withdraws contact and lets stored resonator energy
decay. Phone key lift and exported keygroups use a sampler envelope. Both have
separate demonstrations. The trial uses one authored touch per root; live key
velocity scales playback level. Its default native roots are C3/C4/C5. Open
the `.xty` on MPC 3 or the `.xpm` inside TrackData on MPC 2, keeping the complete
extracted folder together. An MPC envelope value is not a measured hardware
release time.

### Code map

Paths are relative to the repository root.

| Area | Main files |
| --- | --- |
| DSP and bounded cache | `synth/src/main/kotlin/com/snipsnap/synth/TrefoilHeld.kt` |
| Recipe / compatibility | `synth/src/main/kotlin/com/snipsnap/synth/TrefoilPatch.kt`; `Velocity.kt`; `SynthCaches.kt` in the same folder |
| Native zones and sampler export | `shell/src/main/kotlin/com/snipsnap/shell/TrefoilPadMaker.kt` |
| Phone controls and held preview | `app/src/main/kotlin/com/snipsnap/app/ui/TrefoilHeldPreview.kt`; `SynthScreen.kt` |
| Core regressions | `synth/src/test/kotlin/com/snipsnap/synth/TrefoilHeldTest.kt`; `TrefoilTest.kt`; `DeterminismTest.kt` |
| Export / replay integration | `shell/src/test/kotlin/com/snipsnap/shell/TrefoilHeldIntegrationTest.kt` |
| Device tests / benchmark | `app/src/androidTest/kotlin/com/snipsnap/app/ui/TrefoilHeldScreenTest.kt`; `TrefoilHeldBenchmark.kt` |
| Prototype, independent reference and native page | `experiments/trefoil-hold/` |

## Validation and its exact scope

| Check | Result and revision |
| --- | --- |
| Unfiltered nine-module JVM suite | 8,779 tests, 1,086 reports; zero failures, errors or skips; exit 0, Temurin 17; frozen DSP `c3e7c2469a5fa51a46c9ee69ed70600d49602b2c` on base `22a6b4005`; duration 1h 21m 12s |
| Subsequent integration and default correction | 40 focused tests, zero failures/errors/skips; `8e86303069570039f37a2ed546ce0b11640d274f`, incorporating upstream `2f79ee6ef9587d30354c65cb51d77aac595c56cb` |
| Android local build | `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` passed at `8e8630306`; no local device-test execution |
| Final implementation source | App/JVM sources at `712013b07` match the tested `8e8630306`; later commits added evidence/documentation |
| Independent Python reference | Six cases pass; shared-force relative error energy ≤6.8e-16, maximum sample error <3.8e-9; native state residual <1.5e-12 |
| Original Python comparison | Different source noise/filter implementation; after gain/delay fitting, correlation ≥.999984 across six cases; no perceptual identity claim |
| Regeneration after integration | 132 files byte-identical: native recordings, raw states, forces, recipes, MPC files/demos, all 88 approved finite WAVs and both finite programs |
| Source replay | Patch applies to base `2f79ee6ef`; all 43 changed files match implementation head `712013b07` |
| Browser | Nine checks pass: actual audio, matched/position-aligned A/B, exact restored note-off WAV, repeat/key-lift markers, MPC ZIP/RIFF metadata, downloads, saved notes and 390 px layout |
| Existing Site audio | All 364 earlier WAVs preserved |

### GitHub CI snapshot, checked 2026-11-07 UTC

Checks attached to implementation head `712013b07` have completed:

- **Passed:** `jvm-tests`, `jvm-other`, all three `jvm-synth` shards,
  `native-tests`, and `android-build`.
- **Failed:** `emulator-tests`, specifically the **Run the on-device suite**
  step. PR #944 was subsequently merged. This historical failure has not been
  diagnosed in this session.

The current default branch has later UI-test fixes, including `12e059bfb`
(Relay preset naming), `8ad63a5ae` (Meridian saved-name lookup), `c25632ba1`
(Contour draft selection), and `435a1429e` (32-program pattern bounds).
Whether these explain the older failure is not established. Its current head
`ae4bd8d11` has passed Android/native/resource checks while JVM checks are still
running. Compare the failed report with current code before changing anything;
the older failed run does not establish that the current default still fails.

Failed job:
https://github.com/BertCalm/SnipSnap/actions/runs/42334760657/job/128289057333

Retained report artifact:
https://github.com/BertCalm/SnipSnap/actions/runs/42334760657/artifacts/13560645521

Artifact name:
`emulator-test-reports-a5db251e6cf33e3a79658b4f3c430dcd707d6ed4`.

The current cloud session could read job metadata, but log and artifact downloads
returned HTTP 403 from the redirected storage endpoint. The failing test names
and root cause are therefore **not established**. Do not label this unrelated
or flaky, or infer that the Trefoil device test/benchmark passed. Inspect the
linked report from an environment that can retrieve it:

```sh
gh run download 42334760657 --repo BertCalm/SnipSnap --dir emulator-reports
```

## Next work, in order

1. Inspect that emulator failure, compare it with the later default-branch
   fixes, and reproduce any remaining failing test. Record whether Trefoil's
   device tests ran and their results. Keep any fix focused on the observed
   failure; refresh CI status before relying on this snapshot.
2. On a physical phone, exercise FINITE/HELD return, root/velocity edits,
   MOVING/STILL, sustained playback through multiple wraps, release changes,
   cancellation while editing and background/navigation silence. Run the
   included benchmark and keep its JSON with device/model/runtime details.
3. On the MPC, load the trial and listen to C3/C4/C5, notes between zones,
   overlaps, soft/hard key velocities, long holds and key lift near a repeat.
   Record model, firmware, verdict and any audible seam or release problem.
4. Use the measurements and owner listening to decide any preparation-speed or
   sound changes. Cold preparation remains above the tens-of-ms control target.

Measured **host** preparation after JIT warmup is 608–649 ms per uncached capture,
with 0.08–0.23 ms owned cache hits and about 1.4 s for the first observed capture.
These are not phone timings. The app prepares asynchronously; prepared playback
uses the sample player.

## Commands for the receiving developer

Use a full JDK 17. For an SDK-free JVM session, unset `ANDROID_HOME` and
`ANDROID_SDK_ROOT`; `settings.gradle.kts` excludes `:app` when no SDK is found.
Keep test filters attached to each task as shown:

```sh
./gradlew --no-daemon :synth:test --tests '*Trefoil*' \
  --tests '*DeterminismTest.*TREFOIL*' :shell:test --tests '*Trefoil*'
./gradlew --no-daemon test
```

With an Android SDK and a connected target:

```sh
./gradlew --no-daemon :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.TrefoilHeldScreenTest
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.TrefoilHeldBenchmark
adb shell run-as com.snipsnap.app cat files/trefoil-held-benchmark.json
```

For exact native recordings, MPC export, the actual sampler-repeat demo and
the independent reference check:

```sh
python3 -m pip install -r experiments/trefoil-hold/requirements.txt
./gradlew --no-daemon :synth:generateTrefoilHeldAudition :shell:generateTrefoilHeldMpc
python3 experiments/trefoil-hold/render_mpc_preview.py testkit/trefoil-held-mpc
python3 experiments/trefoil-hold/native_reference_check.py testkit/trefoil-held-native
./gradlew --no-daemon :synth:generateTrefoilPlayableBank
```

Outputs are under ignored `testkit/trefoil-held-native/`,
`testkit/trefoil-held-mpc/`, and `testkit/trefoil-playable-bank/`. The source replay
ZIP targets its recorded historical base; use a separate checkout for that
reproduction instead of applying it to the current default branch.

## Retained cloud workspace and publication

These paths are session-local conveniences, not fresh-clone dependencies:

- Implementation checkout: `/workspace/SnipSnap-trefoil-hold`.
- Tested integration checkout: `/workspace/SnipSnap-trefoil-integration`.
- JDK 17: `/workspace/jdk-trefoil-17`; SDK: `/workspace/android-sdk-trefoil`.
- Validation summaries and logs: `/workspace/scratch/trefoil-native-*`.
- Full-suite XML/log archive: `/workspace/scratch/trefoil-native-full-results.zip`.
- Current state journal: `/workspace/scratch/trefoil-kotlin-state.json`.
- Built APK: `/workspace/SnipSnap-trefoil-integration/app/build/outputs/apk/debug/app-debug.apk`.

The Site checkout is `/workspace/sites/trefoil-snipsnap-audition`, separate from
SnipSnap. Published version 10 uses Site source commit
`3a60c6ba79cd31ee2fcf93f76f570ed0f60c8f58`, project
`appgprj_6ad7fbede9c48191aa756ff3f6df3e4f`, deployment
`appgdep_6aee9d5f8ecc819181a45cbda804d59f`. No Site change is part of this handoff.

If the Site later needs an update, use the Sites workflow, preserve its audience
and existing media, and reuse its project identity. The expanded TAR is already
268,206,080 bytes against a 268,435,456-byte limit, leaving 229,376 bytes.
Headers and padding count. Native note-off is stored losslessly as `.wav.gz`;
the browser restores exact WAV bytes. `pack_native.py` also consumes local
verification reports, so inspect its prerequisites before using it in a fresh
environment. Keep the large APK out of the Site package.

## Suggested first message to the receiving session

> Continue Trefoil native HOLD from the current SnipSnap default branch,
> containing merge `261624934d47babb66d6725748d2acc07f354f07`. Read this handoff,
> `AGENTS.md`, Steward and the as-built HOLD design. First inspect the failed
> emulator job linked above and compare it with later fixes on the default
> branch; its root cause is unresolved in this handoff. Then measure on the
> physical phone and audition the new MPC trial with Josh. Preserve finite
> recipes/audio, NUDGE=0 in held mode, the versioned source law and real loop
> recurrence. Record measurements separately from owner listening. Do not
> reapply the already completed integration/default fixes, and do not approve
> or merge pull requests.
