# ORIGAMI TOUCH handoff

Prepared 2026-11-07 for the next SnipSnap session.

## Start here

The ORIGAMI engine and its TOUCH response are implemented and merged. **Continue
from the default branch; do not reimplement the engine or restart the old draft
PR.** Josh's latest feedback is **“I can tell the difference.”** The soft/hard
contrast is now heard. The next decision is whether soft → mid → hard feels
smooth and useful across the presets, especially on held FAN/STILL notes.

This dated handoff supersedes the **current-status** statements in the November 5
brief and workspace snapshots that still say CI, the PR, or owner listening are
pending. Those older files remain valid historical evidence. In particular, the
interrupted local test run must never be relabeled as a successful full run.

| Reference | Checked 2026-11-07 |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Implementation commits | `cce04c5b2c0cb6ed9eff594412114c4b9c45dce4`, then `509df075b21baff215068854ad6f3d3c39e25147` |
| Implementation PR | [#887](https://github.com/BertCalm/SnipSnap/pull/887), merged |
| Merge commit | `35ceeb176286d5f78b53a136a94102a32575996b` |
| Published audio/APK source | `509df075b21baff215068854ad6f3d3c39e25147` |
| Default-branch snapshot used for this handoff | `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`, which contains the merge |
| Handoff branch | `claude/origami-touch-handoff` |
| Handoff path | `docs/handoffs/2026-11-07-origami-touch.md` |

The ORIGAMI-named source files on that default-branch snapshot match the published
implementation. Shared integration files may also contain subsequent work for
other engines. The links below pin the implementation to its actual source
commit.

## What exists

ORIGAMI is a standalone, offline-rendered spectral-folding synth in SnipSnap.
Its three voices are **CRANE**, **FAN**, and **STILL**. The six controls remain
**TUNE, CREASE, FOLD, TURN, WARP, MOTION**. No extra macro or new engine was added
for TOUCH.

- CRANE supplies finite notes; FAN/STILL also supply held sampled instruments.
- STEADY retains the original behavior, including axis motion. BLOOM and UNFOLD
  perform one gesture; in those modes MOTION controls its duration. A held key
  finishes the gesture before looping.
- There are still twelve factory choices, four per voice: six original LEVEL
  references and six current TOUCH performance recipes.
- The sixteen-pad starter's membership and audio are unchanged.
- Saved recipes retain note, authored velocity, seed, macros, gesture, expression,
  and model metadata. Old LEVEL/FOLD recipes retain their existing behavior.

The current performance pairs are:

| Voice | BLOOM | UNFOLD | FOLD | MOTION |
| --- | --- | --- | ---: | ---: |
| CRANE | Paper Bloom | Paper Unfold | .65 | .35 |
| FAN | Fifth Bloom | Fifth Unfold | .65 | .55 |
| STILL | Soft Bloom | Soft Unfold | .62 | .70 |

These factories keep seed `20261012`, velocity `.85`, geometry model 4, and
TUNE/CREASE/TURN/WARP at `.5`. Their intentional change from the published
November 1 family is the explicit expression choice, FOLD → TOUCH.
`OrigamiPresets.foldPerformance()` preserves the old six;
`performance()` returns the current TOUCH six.

## Why TOUCH was needed

Josh reported that soft and hard sounded the same. The old files were distinct
and correctly routed, but FOLD changed relatively weak folded material while the
shared root/octave dominated the body. UNFOLD eventually reached zero fold at
every velocity, leaving nearly identical sustained color. Matched playback also
removed the volume difference between separately normalized takes.

TOUCH reduces the complete non-anchor bank for softer strikes, including the dry
source and common octave. This keeps a difference after UNFOLD has settled. The
accepted hard sound remains the ceiling.

## DSP contract to preserve

The existing FOLD-depth response remains:

```text
depthScale(v) = .25 + .75 * v^1.25
```

TOUCH additionally applies:

```text
upperScale(v) = .1 + .9 * min(v / .85, 1)^2
raw = root + upperScale(v) * (old combined raw - root)
    -> one existing whole-note output level stage
```

- Validate actual velocity as finite and in 0–1.
- At actual velocity **.85 or above**, return literal unity and delegate to the
  old FOLD render path. This preserves the hard endpoint exactly.
- The “upper bank” means **all non-anchor components**, including reflected
  components that can fall below the root. It is not a frequency cutoff.
- Root oscillator, partial frequencies/phases, geometry, gesture timing, and
  loop plans stay fixed. Final root amplitude can still change through the normal
  uniform whole-note level stage.
- Apply the balance after the existing linear conditioning and before one level
  stage. Do not add a second velocity gain or normalize component banks
  independently.
- SOURCE and zero-FOLD diagnostics intentionally receive TOUCH balance.
  ROOT_ONLY remains exact; FOLDED_ONLY is scaled directly.
- The law uses **absolute performed velocity**. A quieter saved ceiling is not
  renormalized to reach unity at its own FULL preview.
- At the factory ceiling, SOFT/MID/FULL use actual velocities
  `.255/.510/.850`, giving upper scales `.181/.424/1`.
- LEVEL and FOLD remain exact compatibility paths. Expression metadata remains
  model 1 with the explicit `TOUCH` discriminant; geometry/gesture/held model
  versions are unchanged.
- Retain the bounded two-entry geometry cache. Velocity and upper scale must not
  enter its geometry key. Do not introduce a cache per strike.

Finite patch and gesture entry points intercept TOUCH before mode resolution
clears the expression. Held synthesis uses the same touch-colored finite
reference and scales only non-root tracks before synthesis. Its raw/final
reference supplies the existing uniform body gain.

## User workflow

**Android:** choose a current performance preset or explicitly select TOUCH.
SOFT/MID/FULL changes render quietly. **Tap AUDITION after each selection.** A tap
while rendering queues the latest result. Preview strength does not replace the
authored velocity in SAVE or SEND.

**Finite pads:** SEND retains one reference sound; use the existing **SOFT HITS**
workflow for velocity layers. SEND does not generate those layers automatically.

**Held keys:** select FAN or STILL and use **MAKE INSTRUMENT**. Existing sampled
instruments retain their old WAVs and must be rebuilt to acquire TOUCH layers.

**MPC:** use the newly generated packages. These ZIPs are **not Android import
files**. Keep the root `.xty`, sibling `_[TrackData]/` containing `.xpm` and
WAVs, `.instrument.json`, and `.origami-keys.json` together.

Each of the four held programs contains nine sampled roots, MIDI 48–72 in
three-semitone steps, with three layers per root: **27 mono 44.1 kHz PCM24 WAVs**.
Layer windows are **1–42 / 43–84 / 85–127**. Each layer owns its loop. Exported
outer zones span MIDI 0–127 by transposing edge samples. Default key-up release
is **.6 seconds**. Preserve the existing peak matching and **one playback key
gain**.

## Published listening and downloads

[TOUCH listening page](https://origami-snipsnap-audition.bertcalm.chatgpt.site/performance/?v=touch-20261105)

The page starts in **Native**, with A/B on Fifth Bloom soft/hard. Native keeps
the playing-level difference; **Matched** compares tone at equal loudness.
The portable starter embeds eighteen Native single notes.

The following Library links resolve in the original ChatGPT workspace. Transfer
the files to another machine, or regenerate them from the source below. Large
binaries and QA output are intentionally outside the Git repository.

| Artifact | Library link / workspace path | Bytes |
| --- | --- | ---: |
| Android debug APK | [SnipSnap-ORIGAMI-TOUCH-debug.apk](/workspace/library-files/SnipSnap-ORIGAMI-TOUCH-debug.apk) | 68,779,847 |
| Complete offline listening bank, including four MPC ZIPs | [ORIGAMI-TOUCH-bank.zip](/workspace/library-files/ORIGAMI-TOUCH-bank.zip) | 154,623,963 |
| Canonical generated folder | `/workspace/library-files/origami-touch-bank-Nov5` | Immutable input |

SHA-256:

```text
APK   faac291a8edd4c03eaea64914f60a15c60d7318f82bdf4b48af8dcf70be9a567
ZIP   49b265f323102c9e294a7b34cf5ddf13aaf8c3addc1ce868080acf5da02e009e
manifest.json
      e2e9a0343516991a254be8e2b978679fd27cd10199648acb7887d5c93acc5a81
```

The APK package is `com.snipsnap.app.brassgrow`, signed with the same debug key
as the preceding ORIGAMI build. The offline ZIP has 101 files, including all 56
listening WAVs, recipes, source snapshots, pages, and four complete MPC archives.
Only duplicate unpacked instrument folders are excluded.

The Site's publication succeeded November 5 at source
`68e332b30280bd33f8f34b5283680abe200eb15e`. Its existing owner-private audience
was preserved. This Site source commit is distinct from the synth source commit.
Site-only adaptations keep files within hosting limits: two Soft ZIPs are
reassembled from verified parts into one normal download; four long STILL
listening takes use lossless FLAC with independently verified identical decoded
PCM. Audio, helper, descriptor, and part URLs use content-hash versions to avoid
stale old audio. Canonical WAVs, manifests, and MPC ZIPs remain unchanged.
Authenticated production browser playback was not independently verified.

## Verification and listening ledger

### Verified

- [Tests workflow, run 41974615218](https://github.com/BertCalm/SnipSnap/actions/runs/41974615218):
  **success**. Its `jvm-tests`, `android-build`, and `native-tests` jobs all
  completed successfully for the implementation.
- [Emulator workflow, run 41974615311](https://github.com/BertCalm/SnipSnap/actions/runs/41974615311):
  **success**, including its latest `emulator-tests` job.
- Separate local focused validation: **182 tests passed, actual exit 0**. Both
  debug and instrumentation APK assembly tasks passed.
- The local unfiltered run was interrupted by a workspace restart after **7,507
  pass events**. Eight modules completed **3,855 tests** with no failures,
  errors, or skips. Synthesis did not finish and no overall exit was captured.
  This remains an interrupted run, independently of later successful CI.
- All **12 hard listening WAVs** and **36 hard program WAVs**, including sampler
  headers, are byte-identical to the prior FOLD bank.
- Complete held Native/Matched PCM reconstructs exactly from the exported
  samples, selected layers, loops, release, and one key gain: twelve selected
  events and 36 actual wraps were verified.
- Browser QA covered 56 full-page and eighteen portable playback starts,
  uninterrupted 109.95-second STILL playback, downloads, recovery, notes, and
  mobile layouts. Site-specific query, FLAC seek/play, ZIP reconstruction, and
  simulated stale-cache checks passed locally.
- All offline ZIP member hashes and CRCs passed.

### Measured

The new minimum gain-independent body distances across the six families were
**.179274825** for adjacent touches and **.516368523** for soft/hard. The proposed
regression bars are .12 and .30 for the defined post-gesture window. These are
numerical regression criteria, not thresholds of audibility.

Existing production-generator host timings were roughly 94–532 ms for a first
soft render with an empty geometry cache, 30–106 ms for a populated-cache mid
render, and 11–37 ms for a populated-cache hard render. These are individual
different-touch measurements, not repeated cold/warm benchmark trials or phone
latency. Maximum recorded basis payload was 16,934,400 bytes in two cache entries.

Each held FAN bank has 16,890,432 bytes of decoded Float32 sample payload; each
STILL bank has 55,738,944 bytes. Generate/export one bank at a time and load only
the three C4 samples for the long audition. Payload figures exclude buffers,
objects, and peak heap. See the detailed cost report before making performance
claims.

### Heard

Josh reported on November 7: **“I can tell the difference.”** This confirms the
revised soft/hard contrast. It does not establish smooth adjacent steps, a
preferred curve, acceptance of every preset, or physical phone/MPC performance.
No assistant listening verdict is claimed.

### Proposed next work

1. Listen to SOFT → MID → FULL across all six presets, particularly sustained
   Fifth Unfold and Soft Unfold. Judge whether the steps feel natural and
   whether the soft body is too plain.
2. Check lower saved velocity ceilings, such as .42, so the absolute-velocity
   behavior is understood before changing the curve.
3. On a physical phone, check manual AUDITION, preset recall, SEND/SOFT HITS,
   MAKE INSTRUMENT, key-up release, and rebuilt layers.
4. On an MPC, load each complete package, test all velocity boundaries,
   sampled roots and edge transposition, sustain wraps, and release.
5. Tune a specific musical issue only after that feedback. Preserve the hard
   identity and saved LEVEL/FOLD behavior; add a focused regression for the
   actual issue. No further DSP feature is committed by this handoff.

## Source map

All links pin the implementation to `509df075b21baff215068854ad6f3d3c39e25147`.

| Area | Files under the repository root |
| --- | --- |
| Expression law and raw mix | [OrigamiExpression.kt](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/synth/src/main/kotlin/com/snipsnap/synth/OrigamiExpression.kt) |
| Finite/gesture entry points | [OrigamiPatch.kt](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/synth/src/main/kotlin/com/snipsnap/synth/OrigamiPatch.kt), [OrigamiGestureRenderer.kt](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/synth/src/main/kotlin/com/snipsnap/synth/OrigamiGestureRenderer.kt) |
| Held reference, tracks, and loop continuity | [OrigamiHeld.kt](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/synth/src/main/kotlin/com/snipsnap/synth/OrigamiHeld.kt) |
| Current and legacy factories | [OrigamiPresets.kt](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/synth/src/main/kotlin/com/snipsnap/synth/OrigamiPresets.kt) |
| Finite and held velocity layers | `synth/.../Velocity.kt`, `shell/.../OrigamiPadMaker.kt` |
| Android / CLI | `app/.../ui/SynthScreen.kt`, `cli/.../SynthCommand.kt`, `cli/.../Main.kt` |
| Production bank generator | `shell/src/test/kotlin/com/snipsnap/shell/OrigamiPerformanceBankGenerator.kt` |
| Regression coverage | `synth/.../OrigamiTouchTest.kt`, `shell/.../OrigamiTouchWorkflowTest.kt`, `app/src/androidTest/.../OrigamiSynthScreenTest.kt`, `cli/.../OrigamiSynthCommandTest.kt` |
| Engine guide | [docs/ORIGAMI.md](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/docs/ORIGAMI.md) |
| Detailed TOUCH contract | [November 5 brief](https://github.com/BertCalm/SnipSnap/blob/509df075b21baff215068854ad6f3d3c39e25147/docs/superpowers/specs/2026-11-05-origami-touch-response.md) |

### Local evidence, if this workspace is retained

- `/workspace/library-files/origami-touch-validation-Nov5/focused-final/summary.json`
- `/workspace/library-files/origami-touch-validation-Nov5/full-interrupted/summary.json`
- `/workspace/library-files/origami-touch-bank-qa-Nov5/metrics-complete.json`
- `/workspace/library-files/origami-touch-bank-qa-Nov5/metrics-held-report.md`
- `/workspace/library-files/origami-touch-bank-qa-Nov5/render-cost-summary.md`
- `/workspace/library-files/origami-touch-bank-qa-Nov5/browser-assets/report.json`
- `/workspace/library-files/origami-touch-site-qa-Nov5/final-freeze.json`
- `/workspace/library-files/origami-touch-bank-archive.json`
- `/workspace/library-files/origami-touch-validation-Nov5/touch-site-publication.json`

Raw November 5 evidence remains unchanged. Its older “heard pending” or “CI
running” fields describe that date; use this handoff and the linked GitHub runs
for the November 7 status.

## Resume and reproduce

Start from current default-branch source on the next machine. Read `AGENTS.md`,
the repository's Luthier bundle, and Steward before changing DSP or handling CI.
Current CI has subsequently gained synthesis-test sharding; follow the current
Steward and workflow files rather than copying the old monolithic job layout
from the November 5 run.

```sh
git clone https://github.com/BertCalm/SnipSnap.git
cd SnipSnap
git switch claude/mobile-mpc-drum-sampler-t58x74
git switch -c claude/origami-next
```

Generate the current bank into a **new** output directory:

```sh
./gradlew --no-daemon :shell:generateOrigamiPerformanceBank \
  -PorigamiPerformanceOutput=/absolute/path/to/new-origami-touch-bank
```

For the historical FOLD family, use a separate output directory and additionally
pass `-PorigamiPerformanceLegacyFold=true`. Direct generator invocation accepts
`--legacy-fold`. Do not overwrite the canonical November 5 outputs or relabel
old FOLD WAVs as TOUCH.

Run checks appropriate to any new code change. A full unfiltered JVM run is
`./gradlew --no-daemon test` without an Android SDK; with an SDK use the current
Steward command and compile both app/test APKs. Do not edit sources while a
Gradle run is reading them. Native source changes require the native harness.
Keep actual exit codes, source revision, report scope, and device-vs-host
distinctions with every new verification result.

## Why it is built this way

- The common octave and dry upper body change with touch so UNFOLD can retain
  contrast after its fold reaches zero.
- The accepted hard endpoint is preserved exactly instead of adding new bright
  material to create a difference.
- An explicit expression mode keeps old saved sounds reproducible.
- Quiet preview rendering and explicit AUDITION preserve the current phone
  interaction contract.
- Native keeps playing dynamics; Matched lets Josh judge the tone separately.
