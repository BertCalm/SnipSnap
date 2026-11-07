# Scanned Chain / Short Pluck Keys handoff

Prepared 7 November 2026. Implementation and the listening page are published.
The code is on an open PR; it has not been merged by this session.

## Start here

| Item | Location / state |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| PR | [#961 — Build Short Pluck keyboard banks from Scanned Chain](https://github.com/BertCalm/SnipSnap/pull/961) |
| Branch | `claude/scanned-chain-short-pluck-keys` |
| Target branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Implementation head | `d8bceca8d591d140ac2f6b2f410f2cea63639975` |
| Main implementation commit | `e627c941c63a98b3e166553930a3fa276eed7aea` |
| Listening page | [Short Pluck Keys](https://snipsnap-scanned-chain-refined.bertcalm.chatgpt.site/#keys) |
| Local checkout | `/workspace/scanned-chain-short-pluck-keys` |
| Local artifacts | `/workspace/scanned-chain-short-pluck-output` |

This handoff is a documentation follow-up to the implementation head above.
The validation and CI snapshot below refer to that implementation, rather than
claiming a new CI run for the handoff commit.

Read [AGENTS.md](../../AGENTS.md), then the repository's
[Luthier instructions](../../.claude/skills/luthier/SKILL.md) for sound work and
[Steward instructions](../../.claude/skills/steward/SKILL.md) for PR/CI work.
On another machine, fetch and check out the work branch if #961 is still open;
use the default branch once the PR has merged.

## User intent and accepted scope

The project began by adapting the
[XObscura concept](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/xobscura_design_spec.md)
into SnipSnap's Scanned Chain engine. Earlier rounds already delivered model-two
BEND/BLOOM behavior, editable shapes, presets, control comparisons and a 16-pad kit.
Those earlier features remain intact.

This round implements the accepted next step: first check Short Pluck at three
notes and three physical touch strengths, then build a playable MPC keyboard
bank and publish a melodic demo. Josh explicitly requested publication and has
said hardware is fine. Do not reopen general hardware validation as a new gate.
No new hardware audition or human listening verdict was claimed in this round.

## Delivered instrument

- **39 finite samples:** 13 native roots, MIDI 36–72 / C2–C5, spaced every three
  semitones, at physical velocities **0.30, 0.65 and 1.00**.
- Mono 44.1 kHz, PCM24; the default source is the unchanged model-two **Short
  Pluck** preset. Each native capture is **0.8 seconds**.
- All three descriptors—phone sidecar, MPC 2 `.xpm` and MPC 3 `.xty`—map
  **C2–C5 only**. Outside notes are unmapped. Interior notes transpose a nearby
  capture by at most one semitone.
- Velocity windows are **0–42, 43–84, 85–127**, with hard layer switching.
  MIDI note-on velocity zero is conventionally note-off.
- **POLY / NoteOn**, with zero sustain loops. Holding a key lets the finite
  sample finish. Releasing early applies a short host fade.
- Host release is **0.05**: approximately 50 ms in SnipSnap's reference player.
  MPC envelope fields use the same setting; their timing was not calibrated in
  seconds. The source's **0.65-second RELEASE** is a separate ending fade,
  counting backward from the sample's end.

**Keep the capture-gain rule.** `ScannedChain.render` already multiplies its
finishing target and ceiling by physical velocity. The bank divides the finished
source by that capture velocity **once**, so the sampler can apply playing
velocity. The physical trajectory remains in the recording. Bank layers are not
independently RMS-matched. A saved source recipe reproduces the native render;
apply the manifest's gain rule to reproduce the exported WAV.

The primary source patch, all 39 layer patches and a versioned delivery manifest
travel in TrackData. Editing a PLUCK source and regenerating the bank is supported
through `--patch`; saved model, shape, macros, seed, duration and envelopes remain
editable. The bank supplies roots and physical touches. Custom sources can have
different duration or touch contrast; the measurements here describe the default.

## Code and documentation

| File | Purpose |
| --- | --- |
| `shell/src/main/kotlin/com/snipsnap/shell/ScannedChainKeysMaker.kt` | Render touches, remove capture gain, assemble zones, stage complete exports and protect existing deliveries. |
| `cli/src/main/kotlin/com/snipsnap/cli/ScannedChainKeysCommand.kt` | `scanned-chain-keys` command and deterministic ZIP delivery. |
| `cli/src/main/kotlin/com/snipsnap/cli/Main.kt` | Command dispatch and help. |
| `cli/build.gradle.kts` | `:cli:generateScannedChainKeys` task. |
| `shell/src/test/kotlin/com/snipsnap/shell/ScannedChainKeysMakerTest.kt` | Six builder, metadata, gain, cancellation and finite-playback tests. |
| `cli/src/test/kotlin/com/snipsnap/cli/ScannedChainKeysCommandTest.kt` | Five CLI refusal, source-edit and complete-package tests. |
| [Short Pluck Keys guide](../SCANNED_CHAIN_KEYS.md) | Design choices, loading, behavior and numerical evidence. |
| [Scanned Chain guide](../SCANNED_CHAIN.md) / [controls guide](../SCANNED_CHAIN_CONTROLS.md) | Existing engine and parameter behavior. |

No DSP renderer, saved model, factory preset or Android UI was changed for this
bank. The `.gitignore` entry keeps generated output out of source control and was
placed with the other instrument exports to avoid a concurrent append conflict.

## Rebuild and load

Use Java 17 and the repository's Gradle wrapper:

```sh
./gradlew :cli:generateScannedChainKeys
```

Outputs:

```text
testkit/scanned-chain-keys/Scanned-Short-Pluck-Keys.zip
testkit/scanned-chain-keys/playable/LOADME.txt
testkit/scanned-chain-keys/playable/card/SCANNED SHORT PLUCK.xty
testkit/scanned-chain-keys/playable/card/SCANNED SHORT PLUCK.instrument.json
testkit/scanned-chain-keys/playable/card/SCANNED SHORT PLUCK_[TrackData]/
```

TrackData holds the `.xpm`, 39 WAVs, `scanned-chain.patch.json`,
`scanned-chain-keys.json` and `recipes/` with 39 exact source patches.
For an edited source:

```sh
./gradlew :cli:run --args='scanned-chain-keys --patch "PATH/TO/scanned-chain.patch.json" --out "NEW_DIRECTORY"'
```

The command accepts `[--patch FILE] [--out DIR] [--overwrite]`. Existing output
is protected unless `--overwrite` is supplied. The dedicated generation task
explicitly regenerates its own output directory.

On MPC, extract the whole ZIP and copy the contents of `card/` together. Load the
top-level `.xty` on MPC 3, or the `.xpm` inside TrackData on MPC 2. Preserve the
folder and filenames. SnipSnap's keygroup ZIP share/import path is unsupported;
phone provisioning requires developer placement of the sidecar and TrackData in
the app-private `files/Kits/Instruments` folder. This delivery adds no phone
MAKE INSTRUMENT button or tap-to-import promise.

## Published page and artifacts

The existing Site now contains the nine-capture study, engine-level and
RMS-matched comparisons, soft → medium → firm sequencing, a **nine-second dry
110 BPM phrase** through the actual exported bank and reference sample player,
and the instrument download. Existing comparison and preset players remain.

- [Instrument ZIP](https://snipsnap-scanned-chain-refined.bertcalm.chatgpt.site/downloads/scanned-chain-short-pluck-keys.zip)
- [Complete study ZIP](https://snipsnap-scanned-chain-refined.bertcalm.chatgpt.site/downloads/scanned-chain-short-pluck-study.zip)
- [Measurements, provenance and build results](https://snipsnap-scanned-chain-refined.bertcalm.chatgpt.site/downloads/scanned-chain-short-pluck-checks.json)

The instrument ZIP is **3,931,255 bytes**, SHA-256:

```text
d15a8785d0b81a6b722b400cb498f5921fb699789d5c61c69c85421fb45d808c
```

Site project: `appgprj_6adcf82e5a148191a8e1c05fc266288c`.
Site source commit: `1315d6361672fd5e7721b229a26be5036d8b1265`.
Final deployment: `appgdep_6aef40b6ee4c8191a8b65ffe94b9428a`, **succeeded** at
`2026-11-07T15:16:15.117097+00:00`. The existing owner-only custom audience was
preserved. The Site is a separate source repository; committing this handoff to
SnipSnap does not require another Site deployment.

## Verification and limits

**Full local JVM suite: 8,866 tests; zero failures, errors or skips; Gradle exit 0.**
The completed run took about 83 minutes. Its command was:

```sh
./gradlew --no-daemon --max-workers=4 -Pkotlin.daemon.jvmargs=-Xmx4g \
  -Dkotlin.daemon.autoshutdownIdleSeconds=30 :cli:generateScannedChainKeys test
```

There was no Android SDK included in that build. In an SDK-equipped checkout,
follow Steward's `test -x :app:test` JVM invocation. The local full run is complete;
there is no old Gradle test session to resume.

Other completed checks:

- All 39 source recipes, divided by capture velocity, match exported PCM24 within
  **5.97e-8**; both generated ZIPs and all 83 card files reproduced exactly.
- **117 pitch probes:** maximum error **6.41 cents**, minimum confidence **0.841**.
  Bank peak at most **0.686**, absolute DC below **2.07e-9**.
- Actual descriptors agree on ranges and layers; WAV sampler roots are correct
  and sustain-loop counts are zero. Held C4 ends naturally at 0.8 seconds;
  early key-up is silent within 51 ms in the reference player.
- The initial C2/C4/C5 study retains modest non-gain spectral/envelope differences
  between touches. These are numerical differences, not perceived percentages
  or a listening verdict. See the guide for the measurement definitions.
- Browser QA passed all 18 study selections, playback/seek, sequencing and
  cancellation, cross-player exclusivity, four viewport widths and error
  fallbacks, with zero page or console errors. Desktop/mobile were inspected.
- Independent source review found compatibility with default commit `06ad3a56a`:
  new stack/zone-release fields are opt-in, and ordinary v2 banks retain behavior.
  A clean merge was subsequently checked against `aab33ce4e`. This was a source
  review and merge check, not a local build of the combined default branch.

**GitHub snapshot checked 7 November 2026 at about 15:36 UTC**, on implementation
head `d8bceca8d`: PR open and mergeable; Android build and native tests succeeded;
`jvm-other` and all three `jvm-synth` shards were still running. Workflow run:
[42385434917](https://github.com/BertCalm/SnipSnap/actions/runs/42385434917).
Refresh that state and check for any runs created by the documentation follow-up
before calling remote CI green.

Local evidence remains under `/workspace/scanned-chain-short-pluck-output/`:
`HANDOFF.json`, `full-jvm-results.json`, `full-exit.json`, `full.log`, `study/`,
`bank/`, and `validation/`. Page QA is at
`/workspace/sites/scanned-chain-keys-page-qa/`; the separate Site checkout is
`/workspace/sites/snipsnap-scanned-chain-refined`.

## Next session

1. Refresh #961 and its CI. Address actual failures if any; leave PR review and
   merge to the owner under Steward's repository workflow.
2. Keep the currently published bank as the current delivery. Sound changes,
   another preset bank, Android integration, wider range or a real HOLD renderer
   are future work only if Josh requests them.
3. If a sound change is requested, use the complete saved source and the raw /
   RMS-matched study to isolate it. Preserve saved models, finite endings and the
   once-only capture-gain rule. Record listening separately from measurements.
