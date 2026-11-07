# Lattice TRACE keys: implementation handoff

Prepared November 7, 2026. The engine, TRACE instrument path and revised held
balance are implemented and merged. The next work is listening and physical
phone/MPC acceptance. The revised hold has been published, but Josh has not yet
given a listening verdict on that revision.

## Start here

Use the latest default branch for product work. Do not restore whole files from
the older feature branch or audition source ZIP over newer upstream work.

| Reference | Verified state on November 7 |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Default head inspected | `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`, contains the TRACE merge |
| Implementation PR | [#930](https://github.com/BertCalm/SnipSnap/pull/930), merged November 7 at 02:45:01 UTC |
| Merge commit | `6682e81bf1f6a6cb2889820891cfef789dfe83ee` |
| Final tested implementation and R7 audio source | `9eca241562cb6f84661d63e61abf67f55e24a001` |
| Implementation/handoff branch | `claude/lattice-trace-keygroups` |
| Engine record | [LATTICE.md](../LATTICE.md) |
| Current private audition | <https://snipsnap-lattice-audition.bertcalm.chatgpt.site> |

For a fresh product checkout:

```sh
git clone https://github.com/BertCalm/SnipSnap.git
cd SnipSnap
git switch claude/mobile-mpc-drum-sampler-t58x74
git pull --ff-only
```

## The listening decision to preserve

The characteristic TRACE gesture is a solid pluck changing into a hollow,
ringing outline. Ordered projection gates change the passing tone, and their
signed rejection feeds a bounded resonant collector. This is offline mono
synthesis at 44.1 kHz with four-times internal rendering.

Josh rejected the first source, then accepted the improved source and clearer
comparisons. His later verdict on the moving finite example was **“B sounds
cool”**, followed by authorization to bring it into SnipSnap. Default finite
TRACE, SPEED .5 / HOLD 0, reproduces that accepted B reference. Keep it as the
fixed listening anchor.

The first keyboard capture had a much quieter sustained body. Josh reported:
**“There is a jump down in volume after the initial hit that is jarring.”**
R7 revises the held renderer in response. It does not change the accepted finite
B. No subsequent listening acceptance of R7 is recorded.

## What is built

- Native WARM, PLUCK and HOLLOW voices, complete patches, factory presets,
  static settled HOLD loops and the original sixteen-pad starter.
- Native TRACE with saved pitch, excitation velocity, SPEED and finite HOLD;
  Ringing Trace, Slow Trace and Quick Trace presets; finite pad/kit exports.
- TRACE-only **MAKE INSTRUMENT** in SYNTH and the matching CLI instrument path.
  The authored attack leads through a causal settling prefix into measured
  sustain. Nine roots every minor third, MIDI 48–72, cover C3–C5. Edge zones
  stay inside that range.
- MPC 3 `.xty`, MPC 2 `.xpm`, PCM24 WAV root/loop metadata, the phone instrument
  sidecar and a `.lattice-keys.json` source recipe. Playback reloads the exported
  files rather than relying on a separate browser synthesizer.
- RELEASE .1–1.5 seconds, default .6. Note-off fades the running sample through
  the player envelope. There is no extra attack control or synthesized physical
  collector tail after release.
- One captured velocity-shaped spectrum per root. Capture gain is removed
  once; the player supplies note-velocity gain. There are no touch/timbre layers.
  Ordinary within-zone sample transposition also changes baked motion timing.

Finite TRACE HOLD extends a one-shot. Keyboard sustain is controlled by note
length and ignores that finite HOLD setting while preserving it in the source
recipe. Static loop-only HOLD and TRACE attack-plus-loop keys are separate paths.

## The held-volume fix and replay contract

Held model 2 preserves the original raw source curve through .035 reference
seconds, then uses a monotone C1 Hermite transition to a .80 source-effort floor
by .08 seconds. A fixed collector pickup of -2.0 removes the near cancellation
between the passing tone and collector during the old handoff. SPEED still
changes the motion/source-envelope clock.

The same source, gates and collector run continuously from rest. There is no
per-result leveling, normalization, crossfade or seam repair. The raw-source
35 ms boundary is not a claim that 35 ms of conditioned output is bit-identical;
the antialiasing filter has lookahead.

Measured across MIDI 48/60/72 × SPEED 0/.5/1 × velocity .2/.75/1, steady native
output is 3.7–5.2 dB below the fixed .01–.035-second attack window. The isolated
handoff's largest adjacent 50 ms drop is about 1.05 dB. The original C3/C4 held
body was roughly 21 dB below its initial body. These are measurements, not a
listening verdict; the earlier authored gate closure still has audible dynamics.

**Keep these independent versions separate:** finite `LatticePatch.model` is 2;
keyboard recipe schema `version` is 1; keyboard `heldModel` is 1 or 2. New captures
default to held model 2. Saved held model 1 must continue to dispatch to its
original renderer, including its quieter .20 floor and original pickup.
Twenty-seven frozen native sample hashes in `lattice-held-v1.json` protect exact
legacy replay. A separate finite B hash protects the accepted one-shot.

## Where to work

| Responsibility | Source |
| --- | --- |
| Gates, collector, finite rendering and continuous TRACE key probe | [`Lattice.kt`](../../synth/src/main/kotlin/com/snipsnap/synth/Lattice.kt) |
| Held versions, source effort, pickup and measured key-note API | [`LatticeKeys.kt`](../../synth/src/main/kotlin/com/snipsnap/synth/LatticeKeys.kt) |
| Native recipe, motion and presets | `synth/src/main/kotlin/com/snipsnap/synth/LatticePatch.kt`, `LatticeMotion.kt`, `LatticePresets.kt` |
| Keyboard recipe, bounded zones, package export and player preview | [`LatticePadMaker.kt`](../../shell/src/main/kotlin/com/snipsnap/shell/LatticePadMaker.kt) |
| CLI routing | `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt` |
| Phone controls and instrument creation | `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` |
| Held regression and legacy fixtures | `synth/src/test/kotlin/com/snipsnap/synth/LatticeKeysTest.kt`, `synth/src/test/resources/lattice-held-v1.json` |
| Package and player regression | `shell/src/test/kotlin/com/snipsnap/shell/LatticePadMakerTest.kt` |
| CLI and phone regressions | `cli/src/test/kotlin/com/snipsnap/cli/LatticeSynthCommandTest.kt`, `app/src/androidTest/kotlin/com/snipsnap/app/ui/LatticeSynthScreenTest.kt` |
| Reproducible held performances | [`LatticeKeysAuditionGenerator.kt`](../../shell/src/test/kotlin/com/snipsnap/shell/LatticeKeysAuditionGenerator.kt) |
| Audition frontend source | `docs/lattice-audition/` |

## Audition and downloads

Start with **“Same C3 press: original, then revised handoff.”** It plays model 1,
then model 2 at the same playback gain, with note-off at two seconds and .6-second
release. It deliberately ends before the first sample wrap. Then play **“C3:
keep the body through the handoff”**, the C4 example and the C minor chord. Those
three full performances wrap and release. The accepted B and older held takes
remain available for comparison.

Downloads are relative to the private audition URL:

- `lattice-trace-keys-v2.zip`: the current nine-root MPC/phone instrument.
- `lattice-trace-keys-v2-listening.zip`: four current listening WAVs, recipes,
  exported-sample replay dependencies and source snapshot. SHA-256:
  `e54f1039eb3107dcbb620a2d3eebf8c03e3dc866d96682f3a66ce93e439b7636`.
- `lattice-keys-v2-source.zip`: the 47-file implementation source snapshot.
- `lattice-trace-keys-v2-evidence.zip`: current numerical evidence.
- `keys2/measurements.json` and `keys2/production-corner-metrics.json`: current
  held-model-2 measurements. Historical root `keys-measurements.json`,
  `publication-validation.json` and root manifest engine provenance remain
  older records; do not use them as R7 measurements.

The Site has a separate source repository. Its published R7 source commit is
`100b18e8b3ee73800b01d909ca53c934f376f517`. The local checkout is
`/workspace/sites/lattice-audition`; generated audio belongs in its `dist/`, not
in the SnipSnap source repository. Keep the owner-private audience and existing
historical asset URLs if publishing a later revision. Product code commits do
not automatically update the Site.

## Build, generate and replay

Use JDK 21. An Android SDK is required to include `:app`; without one, the JVM
gate does not compile or execute Android code. Read [AGENTS.md](../../AGENTS.md),
[Luthier](../../.claude/skills/luthier/SKILL.md) and
[Steward](../../.claude/skills/steward/SKILL.md) before changing DSP or handling CI.

```sh
# Export a fresh current-model TRACE instrument.
./gradlew --no-daemon :cli:run \
  --args='synth LATTICE TRACE --preset 1 --instrument --release 0.6 --out /tmp/lattice-keys'

# Generate held examples and evidence into a fresh directory.
./gradlew --no-daemon :shell:generateLatticeKeysAudition \
  -PlatticeKeysOutput=/absolute/fresh/lattice-keys-audition

# Replay a downloaded performance with its original exported files in place.
./gradlew --no-daemon :shell:generateLatticeKeysAudition \
  -PlatticeKeysRecipe=/absolute/extracted/keys2/recipes/keys2-c3-hold.json \
  -PlatticeKeysReplayOutput=/absolute/replay.wav

# Focused held, package and CLI regressions.
./gradlew --no-daemon :synth:test --tests '*LatticeKeysTest' \
  :shell:test --tests '*LatticePadMakerTest' \
  :cli:test --tests '*LatticeSynthCommandTest'

# Full JVM gate without an Android SDK.
./gradlew --no-daemon test

# With an SDK: CI's JVM gate and Android compilation.
./gradlew --no-daemon test -x :app:test
./gradlew --no-daemon :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Replay checks sidecar/sample SHA-256 values and exact note-on/off frames. Keep
the ZIP's `keys2/recipes`, `keys2/instruments` and `keys2/before-v1` layout intact.
To compare against the original published model-1 package while regenerating,
also supply `-PlatticeKeysBaseline=/absolute/full/R6-root`. Without that option
the generator makes a fresh model-1 package and labels it as a fresh export.
Regeneration alone does not assemble or publish the complete historical Site.

## What passed

All results below refer to implementation commit `9eca241562cb6f84661d63e61abf67f55e24a001`,
not every later default-branch change.

- Full local JVM gate: **8,746 tests**, zero failures, errors or skips; Gradle
  exit 0 on November 6. The 67 focused Lattice tests also passed.
- [JVM, native and Android CI](https://github.com/BertCalm/SnipSnap/actions/runs/42299496469):
  all three jobs succeeded. The Android job assembled the app and compiled
  device tests.
- [Emulator CI](https://github.com/BertCalm/SnipSnap/actions/runs/42299496486):
  succeeded. This is software-emulator evidence, not physical-phone acceptance.
- R7 artifact checks: nine actual PCM24 root samples and loop metadata checked;
  all 26 finite primary WAVs and all 119 historical WAVs remained byte-identical.
- All four current recipes replayed byte-identically from the extracted
  standalone listening ZIP. Source snapshot fingerprints matched all 47 files.
- Local desktop/mobile viewport browser checks decoded 33 WAVs, exercised cues,
  downloads and seeking, and found no page errors or horizontal overflow.
  The R7 deployment reported success at the existing private URL.

Session-only evidence remains under `/workspace/scratch/`:
`lattice-balance-gate-result.json`, `lattice-balance-gate.log`,
`lattice-r7-publication.json`, `lattice-r7-publish-lean/r7-artifact-qa.json`,
`lattice-r7-browser-qa.json` and `lattice-r7-extracted-replay-qa.json`.
Use the repository, CI links and published packs for a portable handoff; those
scratch paths may disappear with this cloud workspace.

## Next acceptance checks, in order

1. **Listen with Josh.** Compare the short original/revised C3 press without
   loudness matching, then hold C3/C4 and the chord through their loop and
   release. Record whether the body now stays connected to the pluck and
   whether the sustained tone is musically useful. Keep finite B unchanged.
2. **Use a physical phone.** In SYNTH choose LATTICE → TRACE → Ringing Trace,
   MAKE INSTRUMENT, vary SPEED/RELEASE, play, save, reopen and cancel a render.
   Check immediate edits reach the package, note-off/chords work and rendering
   is responsive on the actual device.
3. **Import on the MPC Live III.** Check the MPC 3 instrument and, where
   supported, its MPC 2 twin. Play every root and zone boundary, hold through
   several wraps, release at different phases, vary velocity and play chords.
   Verify tuning, bounded C3–C5 mapping, loop behavior, release and polyphony.
4. If another voicing change is needed, keep saved model-1/model-2 replay exact
   and explicitly version the new held behavior. Regenerate evidence and
   repeat listening. Broader voices, touch layers and a physical note-off tail
   are possible later work, not requirements established by this handoff.

The original engineering concept is not declared complete. This handoff records
the implemented instrument and the outstanding acceptance checks.

## Prompt for the receiving session

> Read `docs/handoffs/2026-11-07-lattice-trace-keys.md` and `docs/LATTICE.md`.
> Start from the latest default branch; PR #930 is merged and all its CI checks
> passed. Help me judge R7's original/revised C3 comparison, then validate MAKE
> INSTRUMENT on my phone and the exported keygroups on my MPC. Preserve the
> accepted finite B and exact saved held-model replay. Record what I hear
> separately from measurements and emulator results.
