# Tremor ROLL handoff

**State: R3 is a published listening prototype. It has not been accepted by Josh or integrated into the app.**

The next step is Josh's verdict on R3, rather than another automatic redesign or a production merge.
Keep the preferred R1 LIGHT BED audio exact. Do not overwrite existing saved Tremor sounds.

## Start here

- [Latest R3 audition: Previous / Looser](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-roll-shaker-r3/).
- Compare SETTLING BED at Middle and Hard, then ROLL DEFAULT and HELD ROLL.
- Previous is the exact R2 audio Josh reviewed. Looser is R3. LIGHT BED is the preferred R1 reference.
- The page has 12 comparison clips and three reference clips. Soft comparison pairs are omitted because no beads move at that touch in these recipes.
- All auditions remain owner-private. Preserve that audience and every historical route and feedback key.

The durable source package is [experiments/tremor-roll](../../experiments/tremor-roll/).
This document belongs to the same handoff commit as that package; use the commit containing this document as the checkpoint.

## What Josh has heard

| Round | Verdict | Consequence |
| --- | --- | --- |
| Earlier production Tremor R4 variation | "Tremor has good variation now" | Integrated with saved-model compatibility in [PR #743](https://github.com/BertCalm/SnipSnap/pull/743), subsequently merged. |
| ROLL before shaker studies | "It sound have a little more of a shaker sound, now it gives guiro" | Work narrowed to ROLL, LIGHT BED, SETTLING BED and HELD ROLL. |
| R1 | LIGHT BED: prefer Shaker. Other three recipes: revise. "Right direction, just not there yet" | Keep LIGHT BED as the reference. |
| R1 follow-up | "Still too scratchy or harsh" | R2 rounded stronger attacks and reduced the brittle upper band. |
| R2 | All three comparison recipes: revise. "Better, maybe just a little bit of disorder in the shake. It sound like all the beads land at the same time" | R3 changes arrival timing while preserving R2's individual grains. |
| R3 | No verdict yet | Do not mark the whole ROLL revision approved or ship it as an accepted sound. |

Exact available verdicts and their source provenance are retained under [the experiment evidence](../../experiments/tremor-roll/evidence/).
The R2 verdict was exported on 2026-11-07 at 13:49:29.937 UTC.
Only Josh has listened; the assistant has not heard or approved the audio.

## Three different Git histories

1. **SnipSnap production baseline:** audio source commit `8c9c01254ed5c363b0127b3a40cf52067b8c7e92`.
   The later model-2 integration/editor head is `2f7282a9a1a93a04984808da6985044576a0ceae`, already merged through PR #743.
   Relevant runtime sources match the pinned baseline; [baseline-sources.json](../../experiments/tremor-roll/baseline-sources.json) enforces this.
2. **This handoff branch:** `codex/tremor-roll-handoff`, based on that already-merged `2f7282a9a` checkpoint.
   It archives experiments and documentation. It is not a fresh integration against today's default branch.
3. **Separate Sites source repository:** current published source is `c9d0aa037d318b293aa157d81f1b99d37a5793b7`, Site version **15**.
   That commit is not a SnipSnap Git object. Do not run `git show` for it in the SnipSnap checkout.

The SnipSnap default branch is `claude/mobile-mpc-drum-sampler-t58x74`, not `main`.
The separate Site source repository uses `main`; obtain access through the Sites connector, not an old token.

## Published rounds and identities

| Round | Page | Separate Site source commit |
| --- | --- | --- |
| R1 | [Shaker](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-roll-shaker/) | `e73539848322fc04ddaf64d5b12a3f7cfad666a0` |
| R2 | [Rounded](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-roll-shaker-r2/) | `0672dfe575ba685029e187bd902e0d4bce7a20d4` |
| R3 | [Looser](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-roll-shaker-r3/) | `c9d0aa037d318b293aa157d81f1b99d37a5793b7` |

Each round has its own manifest, audio, notes key and verdict phase. Never replace an old phase in place.

| Bundle | SHA-256 |
| --- | --- |
| R1 | `1322af6dda586402c6d852195a19c469b3916e85e01c7467d89fc2ceda025c4a` |
| R2 | `a472d6bf0b030c2ada13c87064fdec58bd607a0e9d015db09dd8d558099d90f0` |
| R3 | `c26081aa4cecafc42628d10d10b0999268b9e8799d3fa46c6cfe2269bbee8705` |

Bundle hashes identify the compact JSON list of source filenames and their individual hashes, in recorded order.
See [source-bundles.json](../../experiments/tremor-roll/source-bundles.json) for the exact records.

R3 source file hashes:

- `TremorRollLooseCandidate.kt`: `602fa8ac273eadd9763b61b428b460bc3b4ecc210b4ca70f25cada6c65e72b6a`.
- `RollLoosePackets.kt`: `692e013ba6719f8034fd4503b0f03aa33970bdce6f921b663ee903d42ed5a56e`.

Current Site identifiers, copied without reformatting:

```text
project: appgprj_6ac2e11be4848191b1c340709302b992
deployment: appgdep_6aef3082cd8c81918c3cee5e4d85e9fe
saved version: appgprj_6ac2e11be4848191b1c340709302b992~appgver_3f65854ac870819199ee627169c77424
phase: tremor_roll_shaker_r3
```

## What the prototypes do

**Production model 2** has a shared, capped 1.8 ms noise envelope for bead contacts.
Many beads receive the same tray motion and can strike together. A louder shared envelope produced the scraping impression Josh reported.

**R1** hears each actual bead/tray collision through a finite packet of one to three independent noise grains.
Each packet uses deterministic causal delays and bounded future-audio buffers. It does not create unrelated noise or extra physical collisions.
LIGHT BED's R1 result is the preferred reference.

**R2** keeps that mechanism but rounds the denser settings: a smooth BEADS law starts above `.20` and reaches full effect at `.75`.
It caps the extra radiation gain, reduces the strong-impact knee from `2.0` toward `.65`, lengthens grains up to `1.8x`, lowers their filter cutoffs and blends a second low-pass pole.
The explicit low-BEADS bypass keeps LIGHT BED at `.18` exactly equal to R1.

**R3** keeps R2's exact individual grain waveforms, strengths, envelopes, filters and original child delays.
Each bead gets a separate seeded fixed arrival offset of 0–56 ms, plus up to approximately ±4 ms per-impact jitter.
The extra delay is clamped to 0–60 ms and scaled by the same BEADS law. All children of one packet move together.
This creates uneven audible arrivals; it does not recompute bead flight or move a whole batch by one common delay.

Existing audio/layout seeds and draw order, the mechanical solver, membrane strikes, circuit and non-ROLL branches remain unchanged. R3 adds a separate seeded arrival stream.
A parallel legacy-contact energy reference preserves the original quiet-stop time for this comparison.
Changing only the audible readout without that reference previously changed SETTLING BED's render length by 512 frames.

## Verified results

The evidence records the frozen standalone Kotlin/JVM auditions, rather than a new app integration or a listening verdict.

- 15 R3 page clips; nine deterministic candidate repeats; nine exact paired mechanical traces.
- 54 register / BEADS / touch / HOLD matrix cases; 20 non-ROLL finite/held buffers exact.
- Nine Previous/reference PCM files and native float buffers exact to the earlier page, including all three LIGHT BED references.
- No raw rail hits, dropped radiation packets, pending packets at render end or dropped grain-trace rows in the tested cases.
- Maximum raw peak: `0.5222464799880981`.
- Worst matrix global spectral-peak error: attack `0.7172123502047179` cents; held `1.4369822714201623` cents.
- 536 individual grain waveforms and shape records exact to R2; 12 ordered aggregate waveform digests match.
- The diagnostic R2 copy itself matches all 12 frozen R2 float buffers, preventing the trace instrumentation from silently changing the reference.
- Observed additional delay: 0–58.36734693877551 ms; maximum total grain delay: 93.71882086167801 ms.
- Browser: all 15 decodes and button mappings, four exact held regions, six source hashes, Stop/switching/races, feedback persistence/export and mobile/desktop layouts pass.
- 1,246 earlier Site files remain byte-identical; only the hub intentionally changed to add the R3 link.

The earlier production PR #743 had 7,174 local JVM tests and the 292-test emulator suite pass, alongside Android/native CI.
Those are historical production-integration results. Do not describe them as fresh R3 app validation.

For this handoff, the portable runner was also executed from the committed source layout:
all 15 regenerated WAV files, native float hashes and levelled float hashes match the published R3 set exactly; all 54 numerical cases pass.
The required repository JVM command then exited 0 in 9 seconds, with all 42 Gradle tasks up to date because production inputs are unchanged.
That gate reuses the existing passing JVM results; it is not a claim that 7,174 tests or the browser/device suites executed again.
See `experiments/tremor-roll/evidence/handoff-reproduction.json` and `handoff-checks.json`.

## Reproduce from a new checkout

Requirements: JDK 17 (recorded run: Temurin `17.0.21+9`), Kotlin compiler `2.0.21`, Gradle wrapper `8.14.3`, Python 3 and NumPy for verification.
The runner discovers compiler dependencies through the selected Gradle cache and rebuilds the baseline project JARs.
It must not depend on `/workspace/tools`, an attachment path, the old compiled classes or committed binary JARs.

From the repository root, with baseline runtime sources still matching the pinned hashes:

```bash
export JAVA_HOME=/path/to/jdk17
python3 experiments/tremor-roll/reproduce.py --baseline . --output experiments/tremor-roll/build/reproduced
python3 experiments/tremor-roll/verify.py --render-dir experiments/tremor-roll/build/reproduced/rendered
```

These commands rebuild the comparison and numerical matrix and verify their recorded outputs.
They do not rerun the browser tests or the independent diagnostic-copy 536-grain audit; those results remain archived evidence.

`--skip-baseline-build` is available when the matching baseline JARs have already been built.
Use `--java-home PATH` or `--gradle-user-home PATH` when those locations are not supplied through the normal environment/defaults.
If current default-branch runtime sources have moved, use a separate pinned baseline instead of editing production files back:

```bash
git worktree add --detach ../snipsnap-tremor-baseline 8c9c01254ed5c363b0127b3a40cf52067b8c7e92
python3 experiments/tremor-roll/reproduce.py --baseline ../snipsnap-tremor-baseline --output experiments/tremor-roll/build/reproduced
python3 experiments/tremor-roll/verify.py --render-dir experiments/tremor-roll/build/reproduced/rendered
```

The baseline supplies `synth`, `audio`, `json`, `kit`, `mpc3` and `xpm`; Kotlin stdlib is also required.
Prototype compilation uses module name `synth`, JVM target 17 and Kotlin friend access to the baseline synth JAR.
Do not infer byte-identical JAR ZIPs from identical source: build timestamps can differ. Verify source hashes and generated audio hashes.
Native float hashes use big-endian IEEE-754 Float32 samples; they are distinct from PCM16 WAV hashes.

The package contains six byte-exact Kotlin sources under `r1/`, `r2/`, `r3/`, the Java render harness, portable scripts and JSON evidence.
Generated WAVs, JARs, classes, browser output and build products belong under the ignored `build/` directory.
Historical evidence may retain original execution paths; those paths are provenance, not reproduction dependencies.
The separate Site commit retains the published HTML/audio. Do not copy or reuse a stored Site credential.

## Limits that must survive the handoff

- R3's naturalness is still for Josh to judge. Numbers do not establish that it sounds right.
- The physical collision solver still has synchronous impacts. This proposal disperses audible radiation packets.
- The within-impact arrival span grows from roughly 26–32 ms to 61–77 ms, but not every density measure improves.
  DEFAULT hard still reaches 13 grain onsets in a 5 ms window; do not claim universal declumping.
- HELD ROLL changes only its opening bead scatter. Its marked loop starts at frame `47187` / 1.07 seconds and retains the existing drum sustain.
  No continuing held-shaker bed was added. The join remains constructed, not proof of natural mechanical convergence.
- The future rings contain 8,192 output frames (about 186 ms). Readout work is capped at 64 packets per fixed 10 ms actual-impact window.
  The tested cases have no drops; that is not a universal claim about every continuous macro combination.
- Grain hashing/traces add diagnostic overhead. Recorded render timing is a small serial shared-workspace JVM benchmark, not phone timing or isolated timing-law overhead.
- Recipe JSON carries the original model-2 controls. The prototype variant is identified separately by its source bundle.
  Importing that recipe into the current app does not select the unshipped R3 renderer.

## Resume after Josh's R3 verdict

1. Match the new verdict's phase and candidate bundle to R3 above. Preserve the file and distinguish preferences per recipe.
2. If more work is requested, keep LIGHT BED exact, retain all older routes/notes, make a new phase and change only the requested dimension.
3. If Josh accepts the remaining sounds, fetch and inspect the latest SnipSnap default before implementing anything.
   Inspect current `Tremor.kt`, `TremorPatch.kt`, `TremorV1.kt`, `TremorV2.kt`, `Velocity.kt` and the editor's saved-model handling.
4. Select an unused compatible sound-model version after that inspection; do not assume version 3 is still free.
   Keep missing/null saved-model JSON on model 1 and every explicit model-2 recipe on its original renderer.
5. Port the accepted behavior, replace scratch-only diagnostic state as appropriate, and pin the heard buffers and preferred reference in meaningful regressions.
   Verify fresh/factory choices, native velocity, edits, drafts, save/send/reopen and older saved recipes retain the intended version.
   Existing guard files include `TremorCharacterTest`, `TremorModelCompatibilityTest`, `TremorVelocityTest` and `TremorSynthScreenTest`.
6. Run the repository's required JVM and applicable Android/runtime checks, following `AGENTS.md`, Luthier and Steward.
   With no SDK in the Gradle graph use `./gradlew --no-daemon test`; do not add an invalid `-x :app:test` exclusion.
7. Open or update the review branch/PR with the concrete change and validation. Never approve or merge a PR.
   Physical phone/MPC listening remains a separate owner acceptance step.
