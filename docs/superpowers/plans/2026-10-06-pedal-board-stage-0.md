# Pedal Board Stage 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a seventh pad-sheet row, PEDALS, with three recipe chips (DRIVE, COMP, WAH) built from sections the rack already has, and the tooling for the short Stage 0 listening gate.

**Architecture:** A chip is a name in `PadSheet.CHARACTER_FOR` that resolves to a named recipe in `Treatments.EXTRA`; the row is `PadSheet.PEDAL_SEGMENTS`, appended to `PadSheet.ROWS`, which the app's `TreatmentCard` already draws generically. No new DSP, no rack change, no app change. The gate's clips come from a new test-source generator, the same shape as `ValveAuditionGenerator`.

**Tech Stack:** Kotlin/JVM (`:synth`, `:shell`), Gradle, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-04-pedal-board-design.md` (the Stage 0 section and "A finding from planning").

## Global Constraints

- Prerequisite for Task 3 only: PR #438 (MAGNET) has merged, so `Magnet`, `MagnetVoice` and `Magnet.landingChain` exist. Tasks 1, 2 and 4 do not need it, but the spec says nothing in this design starts before #438 merges: start all four then.
- `Shuffle.TREATMENTS` is never touched (a seeded remix bank indexes it; `TreatmentsTest` pins its five names in order). New characters are appended to the END of `Treatments.EXTRA` (the full names list is pinned in order too).
- A recipe never sets a macro to exactly its neutral (AMT could never move it; FxTest's "AMT scales every section a treatment sets" false-fails on it). Neutrals: VALVE DRIVE 0, SAG 0, TONE 0.5, CAB 0; SQUASH AMOUNT 0, ATTACK 0; CONTOUR CUTOFF 1, CREAM 0, SWEEP 0.
- A chip word appears on one row only; a row is at most 6 chips wide (`PadSheetTest`).
- Never hardcode a sample rate; no literal `44100` in main source; no `.format` with digit conversions without `Locale.ROOT` in main source; no stacked KDoc; no double-asterisk-slash (the four `:shell` source-scanning laws, which run only when forced).
- Verification is always: `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test`.
- Commit trailer on every commit:
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01ND5AbnnrejW1pLnz7mNLMr`.
- No push, no PR without the owner's go. The listening page is built by the controller from the generator's manifest, outside the repo.

## Review Focus

1. **DRIVE on a guitar pad is a second amp, not a pedal.** A character chip rewrites the pad's current (baked) audio (`KitBuilder.characterPad` → `rewriteEveryFile`), and a MAGNET pad is already through its landing VALVE; a rack chain has one VALVE. Expect: DRIVE on a guitar pad stacks an amp on an amp. The gate hears it two ways (Task 3) precisely so the owner can judge.
2. **COMP and WAH on a baked guitar pad land after the amp**, the reverse of a rig. Same gate, same two versions.
3. **AMT at the pad sheet's default (0.7)** fades each recipe toward its neutrals: the recipes must still read as their pedal at 0.7, not only at 1. The gate hears 0.7 and 1.
4. **WAH on a long or sustained pad:** CONTOUR's sweep starts at the hit and ends; on a pad that rings for seconds the wah is over early. Listen on the choir.
5. **A three-chip row among rows of five and six:** the app sizes chips by the widest row; confirm the row reads correctly on the phone (no code expected; a visual check at the gate).

---

### Task 1: The three recipes

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt` (append to `EXTRA`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`

**Interfaces:**
- Consumes: `FxChain(valve = ..., squash = ..., contour = ...)`, `Treatments.chain`, `Treatments.apply`, `FxChain.SECTION_NAMES`, `FxChain.macrosOf`.
- Produces: characters `"driven"`, `"compressed"`, `"quacked"` in `Treatments.names` (after `"sectioned"`), used by Task 2's `CHARACTER_FOR`.

- [ ] **Step 1: Write the failing tests.** In `TreatmentsTest.kt`, add `import kotlin.math.abs` and `import kotlin.test.assertFalse` (if absent), change the expected list in ``the named characters are the bank's five and then the extras, smeared among them`` to end `..., "ensembled", "amped", "sectioned", "driven", "compressed", "quacked",`, and add:

```kotlin
    @Test
    fun `the pedal recipes set only their own section, and none sits on a neutral`() {
        val expected = mapOf("driven" to "valve", "compressed" to "squash", "quacked" to "contour")
        for ((name, section) in expected) {
            val chain = Treatments.chain(name)
            assertEquals(
                setOf(section),
                FxChain.SECTION_NAMES.filter { chain.section(it) != null }.toSet(),
                "$name sets only $section",
            )
            val neutrals = FxChain.macrosOf(section).associate { it.name to it.neutral }
            for ((macro, v) in chain.section(section)!!) {
                assertTrue(v != neutrals.getValue(macro), "$name.$macro sits on its neutral $v: AMT could never move it")
            }
        }
    }

    @Test
    fun `each pedal recipe changes a snare and leaves it finite and audible`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        for (name in listOf("driven", "compressed", "quacked")) {
            val out = Treatments.apply(name, snare, 1f).snip
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "$name stays finite and in range")
            assertTrue(out.samples.any { abs(it) > 0.05f }, "$name is audible")
            assertFalse(out.samples.contentEquals(snare.samples), "$name changes the hit")
        }
    }
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :synth:test --tests '*TreatmentsTest*'`. Expected: the names-list test and both new tests FAIL (unknown treatment 'driven').

- [ ] **Step 3: Write the recipes.** In `Treatments.kt`, append after the `"sectioned"` entry (inside `EXTRA`, before its closing parenthesis):

```kotlin
        // A drive pedal: VALVE with a middling DRIVE and only part of a speaker. TONE is left out (its
        // centre is its neutral, so AMT could never move it); CAB is set above its neutral (0, no
        // cabinet) so it can move. DRIVE and SAG are starting shape values, heard at the pedal board's
        // Stage 0 listen (docs/superpowers/specs/2026-10-04-pedal-board-design.md). On a pad that is
        // already through an amp this is a second amp, not a pedal in front of one.
        "driven" to FxChain(valve = mapOf("DRIVE" to 0.45f, "SAG" to 0.2f, "CAB" to 0.3f)),
        // A compressor pedal: SQUASH alone, leaning on the body for sustain. The bank's "punched" is
        // SQUASH plus CRUNCH; this has no crunch.
        "compressed" to FxChain(squash = mapOf("AMOUNT" to 0.7f, "ATTACK" to 0.55f)),
        // A wah: the ladder's contour with the resonance up and a deep, fast sweep (a one-shot's
        // sweep from the hit works as an auto-wah). "contoured" is CUTOFF 0.3, CREAM 0.6, SWEEP 0.7.
        "quacked" to FxChain(contour = mapOf("CUTOFF" to 0.45f, "CREAM" to 0.85f, "SWEEP" to 0.85f)),
```

- [ ] **Step 4: Run.** `./gradlew :synth:test --tests '*TreatmentsTest*' --tests '*FxTest*'`. Expected: PASS (FxTest's "AMT scales every section a treatment sets" now also covers the three).

- [ ] **Step 5: Commit.** `git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`; message `feat(pedals): the DRIVE, COMP and WAH recipes` plus the trailers.

---

### Task 2: The PEDALS row

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`

**Interfaces:**
- Consumes: Task 1's `"driven"`, `"compressed"`, `"quacked"`.
- Produces: `PadSheet.PEDAL_SEGMENTS = listOf("DRIVE", "COMP", "WAH")`, the last entry of `PadSheet.ROWS` (so `ALL_SEGMENTS` is 34 chips), and `CHARACTER_FOR` entries for the three.

- [ ] **Step 1: Write the failing tests.** In `PadSheetTest.kt`:
  - In ``the card reads in zones, and holds every chip`` add `assertEquals(listOf("DRIVE", "COMP", "WAH"), PadSheet.PEDAL_SEGMENTS)` after the `KEYED_SEGMENTS` assertion, and change the two `31` to `34` (the count and its message "the card should draw 34 chips").
  - In ``the regroup keeps every chip the card already drew, ...`` rename the test by appending ` and the pedals' three thirty-four` before the closing backtick, add `"DRIVE", "COMP", "WAH",` (with the comment `// the pedal board's Stage 0`) to the `expected` set, and change both `31` to `34`.
  - Add:

```kotlin
    @Test
    fun `the pedals are the last row, and each chip draws a rack character`() {
        assertEquals(PadSheet.PEDAL_SEGMENTS, PadSheet.ROWS.last())
        assertEquals(PadSheet.Treatment.Character("driven"), PadSheet.treatmentFor("DRIVE"))
        assertEquals(PadSheet.Treatment.Character("compressed"), PadSheet.treatmentFor("COMP"))
        assertEquals(PadSheet.Treatment.Character("quacked"), PadSheet.treatmentFor("WAH"))
        for (segment in PadSheet.PEDAL_SEGMENTS) {
            val t = PadSheet.treatmentFor(segment)!!
            assertEquals(segment, PadSheet.segmentFor(t), "segmentFor is not treatmentFor's inverse for $segment")
            assertTrue(t.name in com.snipsnap.synth.Treatments.names, "$segment names a character the rack does not have")
        }
    }
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :shell:test --tests '*PadSheetTest*'`. Expected: compile error or FAIL on `PEDAL_SEGMENTS`.

- [ ] **Step 3: Implement.** In `PadSheet.kt`:
  - After `KEYED_SEGMENTS` add:

```kotlin
    /**
     * Row seven, the pedals: the guitar-pedal words over the rack's own sections (the pedal board's
     * Stage 0, docs/superpowers/specs/2026-10-04-pedal-board-design.md). The one row grouped by what an
     * effect *is* rather than what it does: a guitarist looks for a pedal by its name. FUZZ, TREM and
     * FLANGE join it as their sections pass their listening gates.
     */
    val PEDAL_SEGMENTS: List<String> = listOf("DRIVE", "COMP", "WAH")
```
  - Change `ROWS` to end `..., TRANSPORT_SEGMENTS, KEYED_SEGMENTS, PEDAL_SEGMENTS,`.
  - In `CHARACTER_FOR`, after the `"SECTION" to "sectioned",` entry add:

```kotlin
        // A drive pedal: VALVE with a middling DRIVE and part of a speaker.
        "DRIVE" to "driven",
        // A compressor pedal: SQUASH alone, for sustain.
        "COMP" to "compressed",
        // A wah: CONTOUR's ladder with the resonance up and a fast, deep sweep from the hit.
        "WAH" to "quacked",
```
  - In the object KDoc, change "Six rows, grouped by what an effect *does* rather than by which family" to "Seven rows, six of them grouped by what an effect *does* rather than by which family", and after the sentence ending "the treatments that read the kit's own key and tempo." add "Row seven is the pedals ([PEDAL_SEGMENTS]), grouped by what an effect *is*."

- [ ] **Step 4: Run.** `./gradlew :shell:test --tests '*PadSheetTest*' --tests '*PersonalityTest*' --tests '*UatSimTest*'`. Expected: PASS (the width test reads `ROWS.maxOf { it.size } <= 6`; the labels test needs each `Copy.treatmentBusy(...)` line at most 40 characters).

- [ ] **Step 5: Commit.** `git add shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`; message `feat(pedals): the PEDALS row on the pad sheet` plus the trailers.

---

### Task 3: The Stage 0 listening clips

(Needs PR #438 merged.)

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/PedalAuditionGenerator.kt`
- Modify: `synth/build.gradle.kts` (a `generatePedalAudition` task beside `generateValveAudition`), `.gitignore` (`testkit/pedal-audition/`)

**Interfaces:**
- Consumes: `Treatments.apply`, `Treatments.chain`, `FxChain.SECTION_NAMES`, `FxChain.withSection`, `Magnet.render`, `Magnet.landingChain`, `MagnetVoice`, `Thump.render`, `Vox.render`, `AuditionLevel.level`, `WavWriter`.
- Produces: `testkit/pedal-audition/<source>/<id>.wav` (git-ignored) and `manifest.json` (`{"source","id","file","label"}` rows, as `ValveAuditionGenerator` writes) for the controller's listening page.

- [ ] **Step 1: Write the generator.**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the pedal board's Stage 0 listening clips under testkit/pedal-audition/ (gitignored),
 * for the gate in docs/superpowers/specs/2026-10-04-pedal-board-design.md: DRIVE, COMP and WAH on a
 * snare and a choir, and on both MAGNET guitar pads two ways (as the pad sheet does it, on the pad's
 * baked audio, and as a rig would, the chip's section in front of the landing amp: COMP and WAH only,
 * because a rack chain has one VALVE). Each chip at AMT 0.7 (the pad sheet's default) and 1. Every clip
 * shares one loudness ([AuditionLevel]). Writes manifest.json. Run via `./gradlew :synth:generatePedalAudition`.
 */
object PedalAuditionGenerator {

    /** The pad sheet's chip words and the characters they draw. */
    private val CHIPS = linkedMapOf("DRIVE" to "driven", "COMP" to "compressed", "WAH" to "quacked")
    private val AMOUNTS = listOf(0.7f, 1f)

    private fun idOf(chip: String, amount: Float) = chip.lowercase() + "_" + (amount * 100).toInt()

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pedal-audition")
        root.mkdirs()
        val entries = mutableListOf<String>()
        fun mono(s: Snip): Snip =
            if (s.channels == 1) s
            else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)
        fun write(source: String, id: String, label: String, snip: Snip) {
            check(listOf(source, id, label).none { s -> s.any { it == '"' || it == '\\' || it < ' ' } }) { "manifest field needs escaping: $source / $id / $label" }
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        val plain = linkedMapOf("SNARE" to Thump.render(ThumpVoice.SNARE), "CHOIR" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in plain) {
            write(name, "dry", "$name DRY", dry)
            for ((chip, character) in CHIPS) for (amount in AMOUNTS) {
                write(name, idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}", Treatments.apply(character, dry, amount).snip)
            }
        }
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val baked = Magnet.landingChain(voice).process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for ((chip, character) in CHIPS) for (amount in AMOUNTS) {
                write(name, "sheet_" + idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}, ON THE BAKED PAD (AS THE PAD SHEET DOES IT)", Treatments.apply(character, baked, amount).snip)
            }
            for (chip in listOf("COMP", "WAH")) for (amount in AMOUNTS) {
                val board = Treatments.chain(CHIPS.getValue(chip), amount)
                var rig = Magnet.landingChain(voice)
                for (section in FxChain.SECTION_NAMES) board.section(section)?.let { rig = rig.withSection(section, it) }
                write(name, "rig_" + idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}, IN FRONT OF ITS LANDING AMP (A RIG)", rig.process(dry))
            }
        }
        check(entries.size == 36) { "expected 36 clips (2 plain sources x 7, 2 guitars x 11), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("pedal audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
```

- [ ] **Step 2: Register the task and ignore the output.** In `synth/build.gradle.kts`, after the `generateValveAudition` block:

```kotlin
/** Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/. See PedalAuditionGenerator. */
tasks.register<JavaExec>("generatePedalAudition") {
    group = "distribution"
    description = "Render the pedal board's Stage 0 listening clips and manifest under testkit/pedal-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PedalAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pedal-audition")
}
```
  and add the line `testkit/pedal-audition/` to `.gitignore` beside `testkit/valve-audition/`.

- [ ] **Step 3: Run it.** `./gradlew :synth:generatePedalAudition`. Expected: `pedal audition: 36 clips under .../testkit/pedal-audition`, `manifest.json` present, every WAV non-empty (`ls -l`), `git status --short` shows only the Kotlin, gradle and `.gitignore` files.

- [ ] **Step 4: Commit.** Message `test(pedals): the Stage 0 listening clips` plus the trailers.

---

### Task 4: Roadmap and verification

**Files:**
- Modify: `docs/SYNTH_ROADMAP.md` (a new row), `docs/superpowers/specs/2026-10-04-pedal-board-design.md` (status line)

- [ ] **Step 1: The roadmap row.** Take the next unused `S` number after the highest row the table has when this task runs. Row text, in the table's own style: `**Stage 0 built, awaiting the gate** - the pedal board's first stage: a seventh pad-sheet row, PEDALS, with three recipe chips over sections the rack already has (DRIVE is VALVE at a middling drive, COMP is SQUASH for sustain, WAH is CONTOUR with the resonance up), no new DSP. A chip rewrites the pad's baked audio, so on a guitar pad COMP and WAH land after the amp and DRIVE stacks a second amp; the gate hears the chips both ways. FUZZ, then TREMOLO and FLANGER, follow only if the gate passes.` and a pointer to the spec.
- [ ] **Step 2: The spec's status line** becomes "Stage 0 built; awaiting its listening gate".
- [ ] **Step 3: Full verification.** `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test`. Expected: BUILD SUCCESSFUL, 0 failures; quote the three totals in the report.
- [ ] **Step 4: Commit** (`docs(pedals): the roadmap row and the spec's status`) plus the trailers. Then STOP: the controller builds the listening page from `testkit/pedal-audition/manifest.json` and the owner decides at the gate.
