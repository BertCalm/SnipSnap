# APSIS GRAIN and ANCHOR handoff

Prepared November 7, 2026, after checking GitHub at approximately 15:36 UTC.

## Start here

The native voices and the MPC acceptance bank are implemented and merged.
The audition page is published. The next step is owner listening and physical
MPC acceptance, followed by changes driven by those observations.

| Reference | Verified state |
| --- | --- |
| Repository | [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap) |
| Default branch | `claude/mobile-mpc-drum-sampler-t58x74` |
| Native GRAIN/ANCHOR port | [PR #824](https://github.com/BertCalm/SnipSnap/pull/824), merged |
| Acceptance bank | [PR #956](https://github.com/BertCalm/SnipSnap/pull/956), merged November 7 at 15:22 UTC |
| Acceptance merge | `d304fcb46ed98104370d5984c2fad34619d4d7f4` |
| CI for acceptance implementation | [Run 42380280199](https://github.com/BertCalm/SnipSnap/actions/runs/42380280199), successful |
| Published audition and kit download | [SnipSnap APSIS audition](https://snipsnap-apsis-audition.bertcalm.chatgpt.site) |
| Design and rendering notes | [APSIS](../APSIS.md), [GRAIN/ANCHOR port](../APSIS-POPULATIONS.md), [acceptance bank](../APSIS-ACCEPTANCE.md) |

Start a new checkout from the current default branch. This handoff was prepared
from `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`, which already contains #956.
Do not resume the old implementation branch as though that PR were still open.
This document is on `claude/apsis-grain-anchor-handoff` until its documentation
change is merged.

## User intent and decisions to preserve

The original aim was to adapt the supplied APSIS engineering concept into
SnipSnap and publish an audition page. The listening rounds led to two preferred
identities, GRAIN and ANCHOR, rather than a single voice. TETHER, VESSEL and
CORONA remain listening prototypes; PASSAGE remains the existing native voice.

The user specifically objected to repeated attacks at the same apparent
loudness. Multiple attacks should vary in strength and character, and the
previous attack should bleed into the next. The selected voice work addresses
internal excitation and resonance. The new acceptance bank also distinguishes
that behavior from the sampler choking a previous note on the same pad.

Keep the chosen identities and native velocity response. Do not independently
level every attack or trim away the approach to make a note land on a beat.
Retain earlier audition takes and recipe/model compatibility when iterating.
The acceptance bank is a download-only artifact; this round did not add an app
starter bank or change the DSP, factory presets or original PASSAGE bank.

## What is delivered

`SnipSnap Apsis Duo` contains 16 pads, 48 native mono 44.1 kHz PCM24 WAVs,
MPC 3 `.xtd`, MPC 2 `.xpm`, editable kit data, complete recipes, checksums and a
physical acceptance checklist. Copy the entire extracted folder. The MPC 3
program sits beside its `_[TrackData]` folder; the MPC 2 program is inside it.

| Pads | Role |
| --- | --- |
| A01–A04 | Grain Passage at MIDI 60/63/67/70 |
| A05–A08 | Anchor Bass at MIDI 48/51/55/58 |
| A09 / A10 | Scattered Grains C4 / Hollow Surge C3 |
| A11 / A12 | Grain Passage C3 / Anchor Bass C4 |
| A13 / A14 | Exact copies of A01 / A05 with separate mute groups for tail overlap |
| A15 / A16 | Held Grains C4 / Held Anchor C3 |

Every pad has three native excitation layers: MIDI velocities 1–41 select `.35`,
42–84 select `.65`, and 85–127 select `1.0`. The normal layer retains the original
factory touch reference. Pads use native note renders, level 1 and individual
mute groups. Held pads loop the complete settled buffer, without an onset
prefix, and use release control 0 in the exported programs.

The page opens with six performances rendered from the actual exported WAVs:
the duo performance, held wrap/retrigger/release, each voice's soft/normal/hard
layers, and each voice's same-pad retrigger versus alternating-copy carry.
Sixteen hard-layer previews are also available. All 159 earlier clips and the
original PASSAGE bank remain available, making 181 clips in total.

Raw scores use fixed `.35` monitor gain. Matched scores apply one gain to the
whole file, targeting the loudest 200 ms RMS window at `.12` and capping peaks
at `.85`. The primary performance uses MIDI 127 and unity pad gain. Touch
inspections select sample layers at unity gain before the hardware's unmeasured
velocity-to-amplitude scaling.

## Validation and limits

- **Implementation CI passed:** all three synth shards, `jvm-other`, aggregate
  `jvm-tests`, `android-build` and `native-tests` in the linked run. This
  supersedes the earlier chat/PR description saying the full suite was pending.
- **Focused local tests passed:** both tests in `ApsisAcceptanceTest` and both
  in `ApsisKitTest`. Checks include both program readbacks, native/quantized PCM,
  SMPL roots and loops, complete kit recipes, preflight, copy-pad identity and
  replay of all six saved scores from checksum-verified exported samples.
- **Page/artifact checks passed:** 530 checks, including restoration of earlier
  audio, both bank selectors, raw/matched playback, held preview wraps, mobile
  layout and byte-identical browser downloads. No browser errors were recorded.
- **Production was checked:** 181 clips, the six acceptance scores, live
  performance playback and a downloaded ZIP matching the original bytes.
- **The separate local full-suite log is incomplete:** it stopped without a
  terminal Gradle result, and its process no longer exists. Do not describe that
  local run as passing or still running. The completed GitHub CI run above is
  the full-suite evidence.
- **Still unverified:** physical MPC velocity scaling, audible layer boundaries,
  actual release/retrigger behavior, phone render latency, and the owner's sonic
  verdict on the conditioned Kotlin output. Automated decoding is not hearing.

No Android source changed in the acceptance-bank PR; no device-instrumentation
result is claimed for that round. Earlier port/integration coverage is recorded
in the linked port notes.

## Next session, in order

1. Open the page and use **Download the selected MPC bank** with GRAIN + ANCHOR
   selected. Load the complete folder on the MPC.
2. Play the A01–A04 and A05–A08 note walks, then A09/A10. Check voice identity,
   root and useful register. Compare soft, normal and hard touch on A01/A05.
3. Retrigger A01 alone, then alternate A01/A13; repeat with A05/A14. Distinguish
   same-pad choking from internal attack variation and continuing resonance.
4. Hold A15/A16 across several joins. Release and retrigger, listening for
   clicks, double attacks, missing arrivals and release lag.
5. Record MPC model/firmware, pad, touch, gate duration and what was heard.
   Compare against the six exported-sample scores before changing DSP or export
   behavior. Keep measurements and listening judgments separate.
6. If changes are warranted, read `AGENTS.md`, the repository Luthier skill and
   its conventions first. Use Steward for PR/CI work. Preserve old model recipes,
   the original bank and earlier audition audio; add comparisons for changes.

## Reproduce and find the code

From a checkout with JDK 17 and dependencies available:

```sh
./gradlew --no-daemon :synth:test \
  --tests com.snipsnap.synth.ApsisAcceptanceTest \
  --tests com.snipsnap.synth.ApsisKitTest
./gradlew --no-daemon :synth:generateApsisAcceptance
```

Output defaults to ignored `testkit/apsis-acceptance/`; override with
`-PapsisAcceptanceOutput=/path`. The ZIP, kit manifest, six score JSON files and
raw/matched audio live there. Score sample paths resolve against that output
root. A newly generated archive can have different metadata/checksums; the
checksum below identifies the currently published artifact.

| File | Responsibility |
| --- | --- |
| `synth/src/main/kotlin/com/snipsnap/synth/Apsis.kt` and neighboring `Apsis*` files | Native engine, voices, recipes and factory presets |
| `synth/src/test/kotlin/com/snipsnap/synth/ApsisAcceptanceGenerator.kt` | Bank layout, three layers, native export, readbacks, scores, ZIP and evidence |
| `synth/src/test/kotlin/com/snipsnap/synth/ApsisAcceptanceTest.kt` | Acceptance-bank and exported-score integration checks |
| `synth/src/test/kotlin/com/snipsnap/synth/ApsisKitGenerator.kt` | Original PASSAGE bank and shared MPC program verification |
| `synth/build.gradle.kts` | `generateApsisAcceptance` task |
| `scripts/prepare_apsis_acceptance_site.py` | Stage acceptance artifacts and lossless browser delivery into an opened Site checkout |

For the full JVM suite use `./gradlew --no-daemon test`; when an Android SDK
includes `:app` in the build, add `-x :app:test`. See Steward for CI shards and
native/device checks. This handoff is documentation only; it does not change
or rerun the implementation.

## Published Site and storage constraints

The Site has a separate source repository from SnipSnap. Its last published
source commit is `e9b7215b654dfdb75464ef573d6eb207493788ab`, version 10, project
`appgprj_6ad77cc101f88191966f02c493e347e6`. Owner-only access was retained.
Reuse that Site and `.openai/hosting.json`; do not create a replacement or
change its audience as part of resuming this work.

The host allows 25 MiB per file. The standard kit ZIP is 26,795,728 bytes, so
the page stores it in bounded parts. The download button joins those parts,
checks length and SHA-256, and offers the original standard ZIP. A direct
`downloads/SnipSnap-Apsis-Grain-Anchor-Kit.zip` URL therefore is not a download
endpoint; use the page's bank button. Its SHA-256 is:

```text
fd1a47b9bfdd0625d0ae5964499b8d34b572823cb993cc03772083c8417a10e4
```

Playback WAVs are stored with lossless gzip compression to fit the total hosting
budget. Playback and WAV downloads restore the original bytes. Original score
source WAV paths remain intact for command-line replay. Do not remove history
or reduce PCM precision to recover space.

To stage a later update, first open the existing Site source using the Sites
workflow, then run from the SnipSnap checkout:

```sh
python3 scripts/prepare_apsis_acceptance_site.py --site /path/to/opened/site
```

The script stages files only. Read the Sites skill before publishing; push the
exact reviewed Site source, package from that state, deploy its saved version,
and verify production playback and restored downloads. Never commit credentials.

## Current cloud workspace (convenience, not portable dependencies)

- `/workspace/SnipSnap-apsis-handoff`: this documentation branch.
- `/workspace/SnipSnap-apsis-acceptance`: old implementation worktree on
  `claude/apsis-grain-anchor-kit`, including generated kit and score artifacts.
- `/workspace/sites/apsis-audition`: published Site source checkout, clean at
  the Site commit above when checked for handoff.
- `/workspace/scratch/apsis-acceptance-qa/`: browser results, live results,
  screenshots and downloaded files from verification.
- `/workspace/scratch/apsis-acceptance-focused.log`: successful focused build.
- `/workspace/scratch/apsis-acceptance-full.log`: incomplete local broad run;
  prefer the successful GitHub run as authoritative full-suite evidence.

These workspace artifacts may disappear in another session. The committed
generator, documentation, GitHub CI evidence and published Site are the durable
handoff. Other existing worktrees belong to earlier rounds; leave them intact.
