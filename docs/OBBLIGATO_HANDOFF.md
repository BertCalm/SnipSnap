# OBBLIGATO performance handoff

Prepared 2026-11-07 UTC. Both requested additions are implemented, published and
merged: sustained note-off playback and the native C3–C6 register.

## Repository and commit state

- Repository: <https://github.com/BertCalm/SnipSnap>.
- Default branch: `claude/mobile-mpc-drum-sampler-t58x74`.
- Implementation: `71306ad59444a933b22f6bb67d82aa51b145f0af`.
- Integrated and CI-tested feature head: `c2fa246728c08a2ea926c32a2b764b5432d23ad9`.
- [PR #942](https://github.com/BertCalm/SnipSnap/pull/942) was merged by the owner
  on November 7 at 14:22:16 UTC, through
  `33003ea63c02bf67c7157540a68b6b7a8022cde3`.
- This documentation branch starts at default-branch snapshot
  `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`. Later batch work changed shared UI,
  effects and native playback files. The four OBBLIGATO core/held/patch/pad-maker
  files remain unchanged from the tested feature head.

The test results below belong to the named implementation and integrated heads.
They are not a fresh full-suite certification of the later default-branch snapshot.
The feature needs no further build work identified by this handoff. Listening
acceptance, MPC hardware checks and phone timing remain open.

## Published deliverables

- **Current private audition and four MPC instruments:**
  <https://obbligato-sustain-range.bertcalm.chatgpt.site>.
- **Earlier auditions and the finite sixteen-pad kit:**
  <https://obbligato-snipsnap-audition.bertcalm.chatgpt.site/>.
- **Android debug APK artifact:**
  <https://github.com/BertCalm/SnipSnap/actions/runs/42332866647/artifacts/13557693398>.
  It expires **2026-11-14 at 03:18:52 UTC**. CI metadata ties it to the tested
  feature; its PR merge-ref build SHA is `e84b2afc782b35668d22a9dafc9fad6b68433dd0`.
  Artifact bytes were not downloaded in this workspace.

The audition has 44 PCM24 tracks: 32 PHRASE/HELD comparisons across four
relationships and C3/C4/C5/C6, four approved C4 references, and eight actual C4
flute/reed taps. Each held listening WAV unfolds two complete loop passes and
then sampler note-off/release. The instrument ZIPs contain the looping source
WAVs, MPC 2 and MPC 3 programs, phone sidecars and complete source patches.

The packages each have thirteen native roots at MIDI 48, 51, …, 84, with keyboard
coverage bounded to MIDI 48–84. The published source strike is velocity .8;
these are single-source-strike instruments, not three-layer touch banks. Complete
performances share an audition level; source taps inherit their duet's gain.
Native instrument samples retain their engine levels.

## Behavior and compatibility to preserve

The characteristic gesture is **flute opening → joined exchange → overlapping
replies while held → sampler release on key-up**.

- The owner approved the gentler finite sound and longer handoffs in renderer 10.
  Keep its 290 ms overlap and closer tuning. Protect must expose its initial
  flute; Fight must trade actual prominence continuously rather than pulse in unison.
- Renderer 11 is the new default and accepts MIDI 48–84 (C3–C6). Its finite
  output within C4–C5 matches renderer 10 exactly. Explicit renderers 1–10 retain
  MIDI 60–72 and their saved audio. Missing `model` still means renderer 5.
- `playback = FINITE` is the default, including recipes with no playback field.
  PHRASE's HOLD remains a finite duration control. A top HOLD value alone does
  not turn an old recipe into a loop.
- HELD explicitly requires renderer 11, `HOLD = 1` and `heldVersion = 1`.
  The patch stores playback, capture version, MIDI, velocity, seed, macros and
  sampler release. Release defaults to .6 s and accepts .1–1.5 s.
- The app hides HOLD in HELD, saves complete edits immediately and preserves
  the selected wide note when returning to PHRASE. An older finite renderer is
  restored when the note remains inside its original register.
- MAKE INSTRUMENT renders zones serially. SEND/SPREAD create finite pad deliveries
  with a saved 20 ms terminal fade. The existing sixteen-pad starter remains finite.

This is a captured continuing conversation, not streaming DSP or shared memory
between keys. Each press plays the opening once, then repeats a recorded exchange.
The new held articulation and wider register have no recorded owner listening
verdict yet; do not describe numerical health as musical acceptance.

## Held capture design

First simulate a ten-second source performance, plus its .75-second release.
Search before SETTLE for a four-to-eight-second sustain section while retaining
the opening. Only a typed `NoSafeLoopException` permits one fresh fourteen-second
source attempt. Cancellation, invalid input, non-finite audio and invariant errors
must propagate directly. Existing successful ten-second captures keep their audio.

In the soft-strike native-bank probe, Protect C3 and F-sharp3 need that longer
source; the other fifty cases use the ordinary source. Do not replace this with
an unbounded retry or loosen the quality limits:

| Check | Current limit |
| --- | --- |
| Sustain length | 4–8 seconds |
| Shared blend | 120 ms |
| Original mixed endpoint correlation | at least .90 |
| Each source endpoint phase | non-opposed |
| Worst local mixture blend loss | at most 1.5 dB |
| Worst local individual-source blend loss | at most 3 dB |
| Absolute endpoint level difference | at most 2.5 dB |
| Full Play/Fight coverage | both actual foreground leaders and at least two actor changes |

Both actual taps use one blend and gain, and reconstruct the duet. Copying source
neighbor frames establishes the assembled wrap; zero assembled seam error is not
proof of physical-state recurrence. Retain independent endpoint, energy and
repeated-playback checks. Cached captures return owned arrays; the cache is
bounded by eight entries and 32 MiB and is cleared by `SynthCaches.clearAll()`.

Desktop Java 17 measurements: ordinary cold captures about .89–1.16 s; cached
medians .32–1.03 ms. Soft Protect C3's fallback took 2.53 s cold and .57 ms cached.
These are single cold observations and five cached calls per case, not phone
benchmarks. The app prepares audio off-thread and supports cancellation.

## Code map

Paths are relative to the repository root.

| Area | Entry points |
| --- | --- |
| Core and note validation | `synth/src/main/kotlin/com/snipsnap/synth/Obbligato.kt` |
| Complete recipe/schema | `synth/src/main/kotlin/com/snipsnap/synth/ObbligatoPatch.kt` |
| Capture, search, taps, cache | `synth/src/main/kotlin/com/snipsnap/synth/ObbligatoHeld.kt`, `SynthCaches.kt` |
| Instrument, preview and export | `shell/src/main/kotlin/com/snipsnap/shell/ObbligatoPadMaker.kt`, `SynthRoot.kt` |
| Phone controls and draft state | `app/src/main/kotlin/com/snipsnap/app/ui/ObbligatoHeldSpec.kt`, `SynthScreen.kt` |
| CLI | `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt`, `Main.kt` |
| Audition generator | `shell/src/test/kotlin/com/snipsnap/shell/ObbligatoPerformanceAuditionGenerator.kt` |
| Regression tests | `ObbligatoHeldTest`, `ObbligatoPerformanceTest`, `ObbligatoCompatibilityTest`, `ObbligatoAcceptedTest`, `ObbligatoHeldPerformanceTest`, `ObbligatoHeldCommandTest`, `ObbligatoSynthScreenTest` |

Read [the performance design](OBBLIGATO_PERFORMANCE.md),
[accepted sound/history](OBBLIGATO.md), and the
[CLI usage](CLI.md#obbligato-extended-notes-and-held-instruments) before changing behavior.
Repository work instructions are [AGENTS.md](../AGENTS.md),
[Luthier](../.claude/skills/luthier/SKILL.md) and
[Steward](../.claude/skills/steward/SKILL.md).

## Completed validation

- **Full local JVM suite on implementation `71306ad59`:** exit 0, 1 h 7 m 27 s;
  nine-module XML totals 8,777 tests, zero failures/errors/skips. Some unchanged
  modules were up to date. No Android SDK was installed locally.
- **CI on integrated `c2fa2467`:** every JVM shard/aggregate, Android assembly and
  device-test compilation, native tests and Luthier resource checks passed.
  [Build run](https://github.com/BertCalm/SnipSnap/actions/runs/42332866647).
- **Focused:** 77 OBBLIGATO synthesis checks, three global-cache checks, six shell
  checks and six CLI checks passed. The held grid covers 24 relationship/note/strike
  cases, including independent analysis of the fourteen-second fallback.
- **Compatibility:** 53 immutable recipes across renderers 1–10 retain their raw
  audio, PCM24 exports and JSON. Never regenerate these fixtures to bless a change.
- **Range:** 112 finite captures across C3–C6 retain both sources, with measured
  root errors below 4.1 cents. All 52 main-strike exported roots and 52 soft-strike
  native captures pass their quality checks.
- **Delivery/browser:** all 44 tracks, exact repeated passes and release tails,
  source-tap additivity, four thirteen-zone packages, references, downloads,
  listening-note persistence and 390/1365 px layouts passed QA. After the soft-note
  repair, all 44 recordings/raw hashes, 52 native WAVs and four complete ZIPs
  remain byte-identical. Only two source fingerprints changed.

### Historical emulator result and later test-harness correction

[PR device run 42332866613](https://github.com/BertCalm/SnipSnap/actions/runs/42332866613)
ran 504 tests, reporting two Onset failures and two explicitly skipped benchmarks.
No OBBLIGATO failure was reported; the console did not list individual passes.
Both failures said `REOPEN PAD RECIPE is disabled`:

- `new_onset_voices_land_as_native_recipes_and_reopen_without_losing_their_model`
- `original_and_model_three_recipes_keep_their_renderers_when_reopened_after_model_four`

The exact failures already occurred on ancestor `631fcfe40` in
[run 42330728802](https://github.com/BertCalm/SnipSnap/actions/runs/42330728802).
Source review pointed to the frozen Compose test clock observing the write
callback before final busy-state recomposition.

**The handoff snapshot already includes subsequent test-harness fixes:**
`6954ae4fbd134c7a43151f9070be238d9cc6ea53` adds the completed frame after
`waitForPadWrite()`, and `8c8f357593287e3b6d6b609133631795b94bdace` orders the
entry update before the completion signal and improves diagnostics. These are
later main-branch work, not changes made for this handoff. Check current CI before
treating the historical failure as an outstanding blocker; this handoff does not
claim a fresh emulator pass on the later snapshot. Keep the assertions enabled.

## Reproduce and continue

Use Java 17 and the repository's configured dependency/network setup. With no SDK:

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Obbligato*' --tests 'com.snipsnap.synth.SynthCachesTest'
./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.Obbligato*' :cli:test --tests 'com.snipsnap.cli.Obbligato*'
./gradlew --no-daemon test
./gradlew --no-daemon :shell:generateObbligatoPerformanceAudition
```

With an SDK, CI's full host-test command adds `-x :app:test`; Android build and
on-device checks are separate. Do not run concurrent Gradle builds or edit source
while a build reads it. The generator writes ignored
`testkit/obbligato-performance-audition/`; retain its audio, patches, diagnostics,
instruments and manifest together. CLI examples are in the linked guide.

Next useful work is listening: start Play/Fight at C4, then C3/C6; verify Protect's
opening flute, overlap and key-up release. Test complete instruments on MPC 2/3
hardware and measure cold preparation on a phone. Make further sonic changes only
against a new listening request and keep approved/legacy audio versioned.

## Optional workspace receipts and hosting upkeep

These paths help in the current workspace but are not portable dependencies:

- Feature checkout: `/workspace/obbligato-responsive-duet`.
- Site checkout: `/workspace/sites/obbligato-sustain-range`; project
  `appgprj_6aee8256121c8191962a072e9d0f68a5`, source head
  `aa0f3df66b8653d02e10f381c3c29cd3ca83ff9d`, owner-private.
- Local full log: `/workspace/scratch/obbligato-performance-full-final.log`.
- Measurements: `/workspace/scratch/obbligato-performance-probe/`, especially
  `final-held-v4-report.json` and `held-soft-bank-v4-report.json`.
- QA/parity: `/workspace/scratch/obbligato-performance-site-qa.json`,
  `obbligato-performance-provenance-diff-qa.json` and
  `obbligato-performance-published-parity.json` in the same scratch directory.

The Site holds 123 files / 150,050,795 bytes; its largest asset is 13,542,820 bytes.
Preserve earlier auditions and audience. The source service's per-push limit is
64 MiB, so the initial upload used five commits/batches; do not retry an oversized
push unchanged. Use the Sites skill for later publication and keep credentials
out of source, commands and handoff files. APK blob downloads were blocked by the
workspace proxy; the normal authenticated GitHub artifact link above is the
handoff, not an attempted bypass.

## Why it is built this way

- The original opening keeps Protect's flute and the instrument's entry gesture.
- Whole exchanges preserve the duet's alternating prominence during sustain.
- Versioned recipes protect the accepted finite sound and old saved work.
- One blend and gain preserve the real balance of the two sources.
- Bounded, cancellable preparation and caching keep source work off key-down.
