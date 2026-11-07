# OHM Reply and Ring handoff

Prepared November 7, 2026. The feature is implemented and merged. The next step is
listening on the phone and MPC, then responding to the owner's feedback.

## Start here

| Reference | Verified state |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Feature PR | [#950, Add Reply and Ring controls to OHM](https://github.com/BertCalm/SnipSnap/pull/950), merged November 7 at 14:22:16 UTC |
| Feature merge commit | `2484710018b32db500ff81f13fe3c3542694705a` |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74`; observed tip `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd` includes the feature |
| Retained work branch | `claude/ohm-reply-ring-integration`; this handoff is a documentation-only follow-up on that branch |
| Final feature/CI revision | `c25632ba1554f539fd98f4bbb0ca4ec540e26d4e` |
| Delivered APK and instrument source | `174a450a4b9c9f91437c748637a1cecbc3491436` |
| Approved listening reference | [Reply/Ring audition](https://ohm-snipsnap-audition.bertcalm.chatgpt.site/reply-ring/) |
| Product and engineering guide | [`docs/OHM_REPLY_RING.md`](https://github.com/BertCalm/SnipSnap/blob/c25632ba1554f539fd98f4bbb0ca4ec540e26d4e/docs/OHM_REPLY_RING.md) |

Start further implementation from the current default branch, which already
contains OHM. Do not reapply the feature or reopen the merged PR. The worktree
used for the artifacts and this handoff is `/workspace/SnipSnap-ohm-reply-ring-app`.

The only change between the artifact revision and the final feature revision is
an instrumentation-test correction in `ContourSynthScreenTest.kt`. It selects
the CONTOUR draft by patch type instead of calling `.single()` across all engines.
A fresh OHM bench now also saves its default draft, even when another engine is
displayed. Production code and OHM audio did not change in that correction.

## What the owner approved

The useful gesture is **pluck → companions answer → shared resonance fades**.
The owner preferred the depth-two/depth-three sound, found bowed contact and the
earlier audition confusing, and found Grain and Memory too subtle. The accepted
replacement has two audible controls:

- **Reply:** changes the spacing of the companions' answers.
- **Ring:** changes the companions' damping and ringing duration.

Choose **OHM → Natural Replies** on the phone. Compare **Tight Replies** with
**Spaced Replies** for timing, and **Short Ring** with **Long Ring** for decay.
These five additional factory recipes reproduce the approved study's raw PCM16
audio byte for byte. The study source reference is
`e902689ab9661bf70518569978de6589e6cec18c`; its website remains published.

The approved middle recipe uses MIDI 60, velocity .8, seed 731, STEADY, TOUCH/BODY
.5, LENGTH .65, MEDDLING .9, COMMUNE 1, Reply/Ring .5 and release .3 seconds.

No further sound redesign is requested. Changes to the accepted sound should
follow listening feedback, with the approved audio retained as a reference.

## Behavior and compatibility to preserve

- Renderer **5** is a finite, seven-second pluck. `Ohm.MODEL_VERSION` is 5;
  `Ohm.DEFAULT_MODEL` remains 2 and `Ohm.SOURCE_MODEL` remains 4.
- `Ohm.replyRingPatch()` supplies the complete approved defaults. A fresh phone
  bench starts there. Existing drafts, pads and presets retain their saved
  renderer; select Natural Replies explicitly when reopening an older bench.
- `OhmPatch.reply` and `.ring` are saved performance fields, each 0..1 with a
  midpoint default. They survive macro edits, transposition, velocity, SCRAMBLE,
  engine navigation, save/reopen, pad delivery and supported breeding.
- Models 1–4 keep their existing renderers. The original five factory sounds
  and original sixteen-pad starter remain on model 2. Missing model metadata in
  old JSON still means model 1.
- Source and held capabilities belong only to models 3/4. Source/sustain
  upgrades target 4 explicitly. Model 5 rejects nondefault source options,
  GATHER and any HOLD key, including HOLD 0, instead of converting the sound.
- SEND makes a normal tonal pad. MAKE INSTRUMENT makes finite native zones
  across C3–C5, stepping by three semitones and retaining an exact authored root.
  The factory instruments have nine zones each. Keyboard spanning is bounded.
- Model-5 keygroups use Note On playback, full finite samples and a real saved
  note-off release. They have **no sustain loop**. A WAV can contain `smpl`
  metadata for its root pitch while still containing zero loops. RELEASE does
  not alter the offline seven-second waveform.
- CLI `--ohm-reply` and `--ohm-ring` select approved defaults for a fresh single
  patch when neither a saved patch nor an explicit preset is supplied. Saved
  patches and selected presets retain authored fields apart from explicit
  overrides. Ordinary legacy defaults stay on model 2; unqualified `--all`
  includes ten factory choices, while renderer/control selection restricts the
  matching family. Invalid combinations fail before output is created.

## Delivered files

These are local build artifacts, not GitHub release assets. Copy the APK, ZIP and
receipt when moving to another machine; a fresh repository checkout does not
contain them. The hashes identify these deliveries, not every future rebuild.

| File | Location |
| --- | --- |
| Development APK | `/workspace/artifacts/SnipSnap-OHM-Reply-Ring-174a450a4.apk` |
| Five-instrument MPC ZIP | `/workspace/artifacts/SnipSnap-OHM-Reply-Ring-Instruments-174a450a4.zip` |
| Build receipt | `/workspace/artifacts/SnipSnap-OHM-Reply-Ring-174a450a4.build.json` |
| Full JVM results | `/workspace/artifacts/ohm-reply-ring-app-full-result.json` |
| Full JVM log / exit status | `/workspace/artifacts/ohm-reply-ring-app-merged-full.log` / `.exit` |
| Android and export build log / exit status | `/workspace/artifacts/ohm-reply-ring-app-merged-build.log` / `.exit` |

```text
APK: 81,244,063 bytes
SHA-256: fe4c6c34fa51ab54691f6d2532ba70df920fe4943df34f5e22b4bf152c0b18a2

MPC ZIP: 30,052,295 bytes
SHA-256: 81b0a56ddbeb1ebac645871c234f2e32cbded2edb94aacc111017a5110b71e26
```

The APK is **SnipSnap Development**, application ID `com.snipsnap.app.brassgrow`,
version `0.1-development`, minimum Android API 29. It includes four ABIs and uses
the established development signer, so it can update the earlier development
installation. A new machine's default debug key will not match that signer.

```text
Signer certificate SHA-256:
50cfe932129693d8720e7e787f3a40d84e65aa9d7a61ff10af2a28d15bd1adfe
```

The MPC ZIP contains five programs and 45 mono PCM24 samples, each 308,700 frames
at 44.1 kHz. Load `.xty` on MPC 3 or `.xpm` on MPC 2, keeping matching sample and
TrackData folders together. This is a keygroup package, not a SnipSnap drum-kit
import. It also contains complete replay recipes, five C4 previews, 32 source
snapshots and checksums.

The receipt's publication section is a historical snapshot from before merge.
Its artifact hashes and local test results remain valid; use the status above
and current GitHub checks for merge and CI state.

## Validation completed

| Check | Result |
| --- | --- |
| Full local JVM suite at `174a450a4` | Exit 0; **8,873 tests**, zero failures, errors or skips across all nine modules |
| Local execution/cache breakdown | 7,866 executed; 1,007 valid cached results in unchanged audio, JSON, MPC3 and loop modules |
| Focused integration checks | 111 passed: 89 synth, 10 shell, 12 CLI; these overlap the full suite |
| Approved/legacy audio | Five approved raw PCM16 pins; 134 model-2 recipes; 50 saved model-3 recipes; original factory/starter checks passed |
| Android | Debug APK assembly and Android-test compilation passed locally |
| Native instrument generation | All 45 zone recipes replayed exact PCM24; release/EOF passed in the reference player |
| Independent ZIP review | CRCs, 149 checksums, 32 source snapshots, five exact C4 previews and 1,371 descriptor/sidecar checks passed |
| Independent APK review | Signature, package/version, CRCs and 593 unique ZIP entries passed |
| Final feature CI at `c25632ba1` | **All eight checks passed**, including emulator tests |

CI evidence:

- [JVM, Android and native run 42377157618](https://github.com/BertCalm/SnipSnap/actions/runs/42377157618)
- [Emulator run 42377157465](https://github.com/BertCalm/SnipSnap/actions/runs/42377157465), completed November 7 at 14:10:02 UTC

The earlier emulator run at artifact revision `174a450a4` is superseded. Do not
treat it as the current CI result. The owner heard and approved the web study;
physical phone and MPC listening of these deliveries has not been recorded here.

## Code map and rebuild commands

Paths are relative to the repository root.

| Area | Entry points |
| --- | --- |
| Dispatch and capabilities | `synth/src/main/kotlin/com/snipsnap/synth/Ohm.kt` |
| Approved finite DSP | `synth/src/main/kotlin/com/snipsnap/synth/OhmV5.kt` |
| Saved performance / factory recipes | `synth/src/main/kotlin/com/snipsnap/synth/OhmPatch.kt`, `OhmPresets.kt` |
| Phone controls and restoration | `app/src/main/kotlin/com/snipsnap/app/ui/OhmControls.kt`, `SynthScreen.kt` |
| Native finite keygroups | `shell/src/main/kotlin/com/snipsnap/shell/OhmPadMaker.kt` |
| Promotion / breeding | `shell/src/main/kotlin/com/snipsnap/shell/UserPresets.kt`, `Breed.kt` |
| CLI selection and validation | `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt` |
| Instrument-package generator | `shell/src/test/kotlin/com/snipsnap/shell/OhmReplyRingHandoffGenerator.kt` |
| Audio/recipe tests | `OhmV5Test`, `OhmV5PatchTest`, `OhmPerformanceCompatibilityTest` in `synth/src/test` |
| Export/CLI/device tests | `OhmReplyRingPadMakerTest`, `OhmReplyRingCommandTest`, `OhmControlsTest`, OHM cases in `SynthScreenTest` |
| Frozen study | `synth/src/ohmReplyRingStudy/`; context in `docs/OHM_REPLY_RING_STUDY.md` |

With JDK 17 and an Android SDK available:

```sh
./gradlew --no-daemon test -x :app:test
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew :shell:generateOhmReplyRingHandoff \
  -PohmReplyRingHandoffOutput=/absolute/output/directory
```

Without an SDK, run plain `test` and omit `-x :app:test`, because `:app` is then
absent from the build. The generator defaults to `testkit/ohm-reply-ring-handoff`.
Changing the source revision or build metadata can change the archive hash;
the approved raw-audio tests remain the sound reference.

This workspace has JDK 17, Gradle 8.14.3 and Android SDK 35. Its local build helper
is `/workspace/tools/android-build-env.sh`; the Gradle runner is
`/workspace/tools/run-gradle-ohm-reply-ring-app.py`, which is tied to this worktree.
Those helpers are not repository files. The full suite can take nearly two hours
on the four-core host when results are cold. Scheduling with four forks changes
no assertions or test selection. No builds remain running from this handoff.

Read `AGENTS.md`, `.claude/skills/luthier/SKILL.md` and
`.claude/skills/steward/SKILL.md` before further engineering. The source/sustain
renderer is explicitly 4, not “latest”; do not replace that with 5. Draft tests
must select their engine's patch instead of assuming only one draft exists.

## Remaining listening and next-session prompt

1. Install/update the delivered APK. Choose OHM → Natural Replies, then compare
   the timing pair and decay pair. Verify save/reopen and SEND retain the edits.
2. Play the MPC keygroup across C3–C5 and release keys at different times. Confirm
   the finite endings and saved release feel right on hardware.
3. Record the owner's words and requested changes. The existing high-register
   feedback ceiling limits long-ring decay targets; late mixed pitch estimates
   can follow an upper harmonic as the pluck fades. Keep that measured limit
   separate from a claim about what the owner heard.

Suggested prompt for the next session:

> Read this OHM handoff and `docs/OHM_REPLY_RING.md`. PR #950 is merged and its
> final CI is green. Start from the current default branch. Help me audition the
> existing APK and MPC keygroups, preserve old patches and the approved Reply/Ring
> reference, and implement only the changes supported by my listening feedback.
