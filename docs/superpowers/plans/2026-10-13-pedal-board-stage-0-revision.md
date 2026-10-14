# Pedal Board Stage 0 Revision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Stage 0 branch to what the owner picked at the two listens: the PEDALS row with two chips, DRIVE (VALVE at DRIVE 1.0 with a pre-amp end fade) and COMP (the strongest SQUASH recipe), and no WAH chip.

**Architecture:** Change the two recipes in `Treatments.EXTRA`, delete the `quacked` recipe and the WAH chip, add a pre-amp end fade for `driven` in `Treatments.apply` (not in VALVE: VALVE is merged and its goldens pinned), and bring the tests, the listening-clip generator and the documents to the two-chip row.

**Tech Stack:** Kotlin/JVM (`:synth`, `:shell`), Gradle, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-04-pedal-board-design.md` ("What the listens found" and "Stage 0 (revised)"). This plan amends `2026-10-06-pedal-board-stage-0.md`, which built the row; its text for the three-chip row is superseded by this plan.

## Global Constraints

- Work on branch `claude/pedal-board-stage-0` (it already holds Stage 0's commits; this plan adds commits on top). Nothing is pushed; no PR without the owner's go.
- `Shuffle.TREATMENTS` is never touched; `Treatments.EXTRA` is appended to, never reordered (the names list is pinned in order): here one entry is removed from its END (`quacked`) and two are edited in place.
- A recipe never sets a macro to exactly its neutral. Neutrals: VALVE DRIVE 0, SAG 0, TONE 0.5, CAB 0; SQUASH AMOUNT 0, ATTACK 0. So COMP's ATTACK is 0.05, not 0, and DRIVE's CAB is 0.08, not 0.
- A chip word appears on one row only; a row is at most 6 chips wide (`PadSheetTest`).
- Never hardcode a sample rate; no literal `44100` in main source; no `.format` with digit conversions without `Locale.ROOT` in main source; no stacked KDoc; no double-asterisk-slash (the four `:shell` source laws, which run only when forced).
- Verification is always `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (allow up to 45 minutes: another worktree may be running its own suite).
- Commit trailers on every commit: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01ND5AbnnrejW1pLnz7mNLMr`.

## Review Focus

1. **The end fade must not change what the owner heard** beyond the tail: it applies only to `driven`, only to the last tens of milliseconds, and `compressed` must be byte-identical to `Treatments.chain("compressed").process(source)`.
2. **AMT 0.7 and 1.0 both end clean** on a snare and a choir through `driven` (the last 20 ms at least 50 dB under the peak, 3 dB of margin; the spike measured 19.8 dB at AMT 1 without the fade).
3. **No stale pinned number:** chip counts (34 becomes 33), the names list (`quacked` gone), the generator's clip count, the roadmap and CLI docs.
4. **Deleting a recipe that was in a branch commit** is safe because nothing is pushed or saved: confirm no kit, fixture or test references `quacked` or the WAH chip (grep).

---

### Task 6: Recipes, the WAH chip's removal, and the pre-amp end fade

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`, `shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`

**Interfaces:**
- Consumes: `FxChain`, `Treatments.chain`, `Treatments.fade`, `Snip(samples, channels, sampleRate)`, `Snip.frameCount`.
- Produces: `driven` = valve DRIVE 1.0 / SAG 0.15 / CAB 0.08; `compressed` = squash AMOUNT 1.0 / ATTACK 0.05; no `quacked`; `PadSheet.PEDAL_SEGMENTS = listOf("DRIVE", "COMP")`; `Treatments.apply` fades the end of the pad for `driven`.

- [ ] **Step 1: Write the failing tests.** In `TreatmentsTest.kt`:
  - In the names-list test, remove `"quacked"` from the expected list (it ends `..., "sectioned", "driven", "compressed"`).
  - In ``the pedal recipes set only their own section, and none sits on a neutral`` remove the `"quacked" to "contour"` entry.
  - In ``each pedal recipe changes a snare...`` change the list to `listOf("driven", "compressed")`.
  - Change the DRIVE test's bars: THD at AMT 0.7 at least 15, at AMT 1.0 at least 30 (the spike measured 35.99 and 38.05 for these macros); keep the measuring method and the failure messages quoting the measured value.
  - Re-measure ``COMP lifts a snare's tail against its body and flattens a choir`` for the new recipe (SQUASH AMOUNT 1.0, ATTACK 0.05) and update both arms' bars and the KDoc's measured numbers: the snare arm (the tail 100 to 200 ms against the body 5 to 50 ms, at AMT 1) was +4.42 dB for the old recipe and the spike measured +7.78 for the new one: set the bar at 6 dB; the choir arm (the peak-to-RMS ratio, 10.64 dry, 9.46 for the old recipe) must be re-measured, and its bar set at about 70 percent of the measured drop (never below 0.5). Keep each failure message quoting its measured value. Its AMOUNT-0.05 mutation must still fail the test (say so in the report).
  - Add a private helper and a test for the end fade:

```kotlin
    private fun endDb(s: Snip): Double {
        val n = (0.02f * s.sampleRate).toInt() * s.channels
        var peak = 0f
        var end = 0f
        for (i in s.samples.indices) {
            val a = abs(s.samples[i])
            if (a > peak) peak = a
            if (i >= s.samples.size - n && a > end) end = a
        }
        return 20.0 * log10(maxOf(end / peak, 1e-12f).toDouble())
    }

    @Test
    fun `driven ends clean at the pad sheet's AMT and at full, on a short pad and a long one`() {
        val sources = mapOf("snare" to Thump.render(ThumpVoice.SNARE), "choir" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in sources) {
            for (amount in listOf(0.7f, 1f)) {
                val out = Treatments.apply("driven", dry, amount).snip
                val end = endDb(out)
                assertTrue(end <= -53.0, "$name at AMT $amount ends $end dB under its peak, past the -50 bar with 3 dB of margin")
                assertTrue(abs(out.samples.average()) < 1e-4, "$name at AMT $amount leaves DC")
            }
        }
    }

    @Test
    fun `the end fade is driven's alone`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val applied = Treatments.apply("compressed", snare, 1f).snip
        val direct = Treatments.chain("compressed", 1f).process(snare)
        assertTrue(applied.samples.contentEquals(direct.samples), "compressed is not faded: the door applies the chain and nothing else")
    }
```
  (add `import kotlin.math.log10` if absent; `abs` was added in Task 1). In `PadSheetTest.kt`: `PEDAL_SEGMENTS` is `listOf("DRIVE", "COMP")`; both `34` become `33` (the count and its message, the inventory test and its message and its title's "thirty-four" becomes "thirty-three"); remove `"WAH"` from the inventory set and from the pedals test (its `treatmentFor("WAH")` line).

- [ ] **Step 2: Run to verify they fail.** `./gradlew :synth:test --tests '*TreatmentsTest*'` and `./gradlew :shell:test --tests '*PadSheetTest*'`. Expected: FAIL (names list, bars, `PEDAL_SEGMENTS`).

- [ ] **Step 3: Change the recipes.** In `Treatments.kt` replace the `driven`, `compressed` and `quacked` entries with:

```kotlin
        // A drive pedal: VALVE at the top of its gain law (DRIVE 1.0 is gain 1000; at the pad sheet's
        // AMT 0.7 the recipe's DRIVE fades to 0.7, which is VALVE's own default gain 11.3) with little
        // sag and the speaker barely in. TONE is left out (its centre is its neutral); CAB takes a small
        // non-neutral value so AMT can move it (a missing macro would be VALVE's default 0.6). The
        // owner's pick, heard against a hot crunch and a lead tone at the pedal board's round two
        // (docs/superpowers/specs/2026-10-04-pedal-board-design.md). On a pad that is already through an
        // amp this is a second amp, not a pedal in front of one. apply() fades the pad's last
        // milliseconds before it, see PRE_AMP_FADE.
        "driven" to FxChain(valve = mapOf("DRIVE" to 1.0f, "SAG" to 0.15f, "CAB" to 0.08f)),
        // A compressor pedal for now: the strongest SQUASH there is (AMOUNT at its top, the fastest
        // ATTACK that is not its neutral), the owner's pick at round two. The sustain compressor the
        // owner asked for is its own section (spec, Stage 2); this recipe moves to it then. The bank's
        // "punched" is SQUASH plus CRUNCH; this has no crunch.
        "compressed" to FxChain(squash = mapOf("AMOUNT" to 1.0f, "ATTACK" to 0.05f)),
```
  and delete the `quacked` entry and its comment (the WAH returns with its own section). Also fix the `"sectioned"` comment wording if it still claims to be last.

- [ ] **Step 4: Add the pre-amp end fade.** In `Treatments.kt` (the file already imports `Snip`), above `apply`:

```kotlin
    /**
     * Characters that run a hot amp, and how much of the pad's end fades out BEFORE the chain: VALVE
     * lifts a quiet tail by its own gain (1000 at the top of DRIVE), so a cut nobody hears dry ends
     * in a click through it, and a fade ahead of the amp survives the amp (MAGNET's lesson). The
     * length and shape come from the measurement in the plan: the shortest, mildest fade for which a
     * snare and a choir end at least 53 dB under their peaks at AMT 0.7 and 1.
     */
    private val PRE_AMP_FADE: Map<String, EndFade> = mapOf("driven" to EndFade(ms = 80f, power = 2))

    private class EndFade(val ms: Float, val power: Int)

    /** [snip] with its last [fade] milliseconds faded to silence by `x^power` (x is 0 at the last frame, 1 at the fade's start). */
    private fun endFaded(snip: Snip, fade: EndFade): Snip {
        val frames = snip.frameCount
        val n = minOf(frames, (fade.ms / 1000f * snip.sampleRate).toInt())
        if (n <= 0) return snip
        val out = snip.samples.copyOf()
        for (d in 0 until n) {
            val g = Math.pow(d.toDouble() / n, fade.power.toDouble()).toFloat()
            val frame = frames - 1 - d
            for (c in 0 until snip.channels) out[frame * snip.channels + c] *= g
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
```
  and in `apply`, replace `return Treated(fx.process(snip), recipe)` with:

```kotlin
        val input = PRE_AMP_FADE[name]?.let { endFaded(snip, it) } ?: snip
        return Treated(fx.process(input), recipe)
```
  **Choose the numbers by measurement, not by this text:** before committing, run the new `driven ends clean` test with every combination of ms in {20, 40, 80, 150} and power in {1, 2, 3} (a scratch loop, not committed), print a table (ms x power x worst end dB over the four cells x DC mean), and set `EndFade` to the shortest ms, then the smallest power, that passes (`-53` dB and DC under 1e-4 on all four cells). The `80f, power = 2` above is a starting guess. If none passes, STOP and report the table (status NEEDS_CONTEXT).

- [ ] **Step 5: Remove the WAH chip.** In `PadSheet.kt`: `PEDAL_SEGMENTS = listOf("DRIVE", "COMP")`, delete the `"WAH" to "quacked"` entry and its comment from `CHARACTER_FOR`, and in the KDoc of `PEDAL_SEGMENTS` say that WAH, FUZZ, TREM and FLANGE join as their sections pass. Grep the repo for `quacked` and for the WAH chip: nothing outside `docs/superpowers/plans/2026-10-06-pedal-board-stage-0.md` and the spec's history may still name them.

- [ ] **Step 6: Run.** `./gradlew :synth:test --tests '*TreatmentsTest*' --tests '*FxTest*'` and `./gradlew :shell:test --tests '*PadSheetTest*' --tests '*PersonalityTest*' --tests '*UatSimTest*'`. Expected: PASS. Mutation checks to report: (a) set `PRE_AMP_FADE` to empty: the `driven ends clean` test must FAIL; (b) set `driven`'s DRIVE back to 0.6: the DRIVE THD test must FAIL.

- [ ] **Step 7: Commit.** `git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`; message `feat(pedals): the owner's DRIVE and COMP, no WAH chip, and a pre-amp end fade for DRIVE` plus the trailers.

---

### Task 7: The generator, and the documents

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PedalAuditionGenerator.kt`, `docs/SYNTH_ROADMAP.md`, `docs/CLI.md`

- [ ] **Step 1: The generator.** In `PedalAuditionGenerator.kt` remove `"WAH" to "quacked"` from `CHIPS`; fix the clip arithmetic: each plain source is `dry` + 2 chips x 2 amounts + `punched` + `amped` = 7 (14), each guitar is `baked` + 4 `sheet_` + 2 `rig_comp` + 2 `rig_drive` = 9 (18): the total is 32 (change the `check`, its message, the KDoc's list of chips and any mention of WAH). The rig loop's `listOf("COMP", "WAH")` becomes `listOf("COMP")`. Run `./gradlew :synth:generatePedalAudition`: expect `pedal audition: 32 clips`, a parsing manifest, 32 non-empty distinct WAVs.
- [ ] **Step 2: The roadmap row (S30).** Rewrite it to the revised Stage 0: the PEDALS row with two chips (DRIVE: VALVE at the top of its gain law with a pre-amp end fade; COMP: the strongest SQUASH, interim), the WAH chip held back for its own section, the owner's two listens, and a pointer to the spec's staging (WAH section, COMPRESSOR section, the board in front of the amp, TREMOLO and FLANGER, FUZZ deferred). Keep the table's style and one line.
- [ ] **Step 3: `docs/CLI.md`.** In the `treat` reference (around lines 355 to 399 as of this branch) list `driven` and `compressed` (one sentence each, in the section's own style) and the PEDALS row (seven rows, the last with two chips); do NOT list `quacked`. If `quacked` or three characters are named there, fix them.
- [ ] **Step 4: Full verification.** `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test`: BUILD SUCCESSFUL, 0 failures; quote the three totals from the result XML. `git status --short` shows only intended files.
- [ ] **Step 5: Commit** (`docs(pedals): the generator, the roadmap row and the CLI reference for the two-chip row`) plus the trailers. Then STOP: the controller reviews and decides about the PR with the owner.
