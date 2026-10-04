# GLINT Roster Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship GLINT's factory roster, 32 presets (eight per voice), exactly the ones the owner approved from the roster audition, wired into `Presets` and `Glint.scramble`, with the final listen prepared for him.

**Architecture:** `GlintPresets` (main sources) is a table in the house shape, its values generated from the numbered candidates of `GlintRosterCandidates` so none is retyped, and `GlintPresetsTest` pins it against those candidates to the bit. `Presets` gains a GLINT branch and `Glint.scramble` seeds from a random preset like SIREN's. A small generator renders each preset as a one-shot plus the pad at the lowest and top keys for the final listen.

**Tech Stack:** Kotlin 2.0 / JVM in `:synth` (tests with `kotlin.test` on JUnit), Gradle. `:shell`'s roster-line tooling and the app's preset chips follow from `Presets.forVoice` with no code of their own.

**Spec:** `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md` §6.1, §6.3 and the end of §6.2 (the final listen). This is the roster's second half; the first half is `docs/superpowers/plans/2026-10-02-glint-roster-candidates.md` (done: the candidates, the clips sent to the owner, the picks). Read §6 and that plan's As-built section before Task 1.

## Global Constraints

- **The roster is the 32 the owner approved ("All good", 2026-10-04), in this order, with these candidate numbers** (a candidate number is the clip's number in the roster audition, `<VOICE>-NN`):
  - SWEEP: 15 WARM DRIFT, 09 DEEP BLOOM, 01 OPEN SLIDE, 10 HOLLOW MID, 03 AMBER, 13 DARK RISE, 08 WARM RISE, 14 BRIGHT FALL
  - STEP: 07 SOFT LADDER, 14 WARM BUZZ, 04 LONG CLIMB, 15 FULL BUZZ, 09 BRIGHT CLIMB, 05 RISING STEPS, 03 TWIN STEPS, 16 GLASS LADDER
  - BRASS: 11 MELLOW HORN, 10 PLUSH HORN, 01 DARK TUBA, 02 SWAY HORN, 06 BRIGHT HORN, 05 BREATHY HORN, 16 STEADY HORN, 13 SLOW ROLL
  - VOWEL: 10 STILL AH, 14 BUZZY EH, 01 SOFT OH, 06 RISING OO, 03 PLAIN OO, 04 FALLING EH, 11 RISING OH, 16 OPEN AH
- **Six names differ from the list he approved, because a house rule forbids them** (`MercuryPresetsTest`'s law, repeated here: no word of a name may be an engine's name, one of the engine's voices or a rack section): OPEN SWEEP is now OPEN SLIDE and SOFT STEP and GLASS STEP are now SOFT LADDER and GLASS LADDER (a voice's own name), VELVET HORN is now PLUSH HORN (an engine's name), SWELL HORN and SLOW SWELL are now SWAY HORN and SLOW ROLL (a rack section's name). The controller tells the owner, and he can veto them again at the final listen.
- **Values are the candidates' exact floats, generated and not retyped.** The table lines below were printed from `GlintRosterCandidates.forVoice(voice)[number - 1].macros`; the project's own roster-line tool (`UserPresets.rosterLine`) writes every value exactly the same way ("the bytes the phone heard").
- **TUNE, DECAY and FOLLOW ship at their defaults** (FOLLOW is exactly inert at the audition's pitch, so nobody could hear it; the final listen plays it across the keyboard). They are flagged in the table's KDoc and in the PR.
- **The helper line is exact:** `private fun p(voice: GlintVoice, name: String, vararg macros: Pair<String, Float>)` (`:shell`'s `UserPresetsTest` checks that text in the file).
- **Names:** uppercase, at most 14 characters, one or two words, unique per voice, clear of `PresetTestSupport.trademarkBlocklist`. Order is a contract (`--preset N`, `.first()` pins): it is the rank, so it is final before this merges.
- **No model name in a code comment, commit message or PR text** (the harness's trailers are the only place). Write "the owner" and "a stated rule", not who applied it.
- **Do not touch:** the engine's DSP (`Glint*` rendering, `Dsp`, `Keys`), `Velocity`, `Patches`, `:cli`, `:app`. `Glint.scramble` is the one change to `Glint.kt`.
- **Commits:** plain declarative title (no `feat:`/`fix:` prefix, no ticket number), a body that says why, then exactly these two trailer lines:
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XdQuyqmnaSqRVPTo87MNUr
  ```
  The branch is `claude/glint-roster`. Never push, open a PR, approve or merge; the controller asks the owner first.
- **Worktree and tool rules:** work from `/Users/joshuacramblet/SnipSnap/.claude/worktrees/glint-depth-d1` and never `cd` elsewhere. Git commands one at a time, each plain (a compound git command is refused here); `git commit -F <file>` with the message written under the scratchpad directory.
- **Gradle:** one invocation at a time, never two, in the foreground with a 600000 ms timeout (the full suite in the background). Gate on the exit code AND on the result XMLs under `synth/build/test-results/test/`; never pass `-q` to a command `task-done` runs (an empty log makes it die silently); never `-x :app:test`.
- **Claims:** a guard is proven by undoing it alone and watching a test fail with a message that names it. Report a number you measured as measured and one you inferred as inferred.

## Review Focus

Failure modes the spec implies that the owner or a player would hit, most likely first. Each has its test in the task that owns the code.

1. **A transcription slip in 32 rows of seven values** that sounds fine and is not the clip he approved. (Task 1: every varied macro equals the numbered candidate's, to the bit; the values are generated, not typed.)
2. **A name a house rule forbids, or a trademark substring.** (Task 1: the taken-words law and the blocklist.)
3. **SCRAMBLE changing where it must not:** an explicit `near` patch, temperature 1 and the draw order of the flat roll. (Task 3: the three cases, and the older DEPTH pin reworded to the `near` form.)
4. **A preset that does not play as a pad at the ends of the keyboard** (FOLLOW at 0.8 moves the formant at the lowest and top keys; a loop that does not close, or a render that is not finite, would fail only there). (Task 1: each preset as a pad at the lowest, middle and top key.)
5. **The tooling that follows the roster list** (`:shell`'s roster-line law, the app's chips, the CLI) meeting a sixteenth engine. (Task 2 and the full suite in Task 6.)

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/GlintPresets.kt` (new) | The roster table: `forVoice`, `all`, the helper, 32 rows. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintPresetsTest.kt` (new) | The roster's contract and its provenance. |
| `synth/src/main/kotlin/com/snipsnap/synth/Presets.kt` | The GLINT branch, `all()`, one KDoc count. |
| `synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt` | The two cross-engine laws name GLINT. |
| `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` | `scramble` seeds from a preset. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`, `GlintDepthTest.kt` | Two new scramble tests; the DEPTH-era pin takes the `near` form. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterListenGenerator.kt` + `…Test.kt` (new), `synth/build.gradle.kts`, `.gitignore` | The final listen's files. |
| `docs/SYNTH_ROADMAP.md`, the DEPTH spec | S10 row; §6.2 records how the picks were made. |

---

### Task 1: The roster table and its contract

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintPresetsTest.kt`
- Create: `synth/src/main/kotlin/com/snipsnap/synth/GlintPresets.kt`

**Interfaces:**
- Consumes: `GlintPatch(name: String, voice: GlintVoice, macros: Map<String, Float>)` (a data class that validates names and ranges), `Glint.macrosFor(voice)`, `Glint.defaults(voice)`, `GlintRosterCandidates.forVoice(voice)[n - 1].macros` and `.varied(voice)` (test sources), `Keys.glintPadMidis(voice)`, `Keys.glintPad(voice, macros, midi)` (renders the held pad; throws if its loop does not close), `PresetTestSupport.trademarkBlocklist`, `FxChain.SECTION_NAMES`, `Patches.fromJsonText`, `Snip.peak()`.
- Produces: `GlintPresets.forVoice(voice: GlintVoice): List<GlintPatch>` and `GlintPresets.all(): List<GlintPatch>`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintPresetsTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GLINT's factory roster (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.3): eight per voice,
 * held to the contract every `<Engine>PresetsTest` holds (names, blocklist, every macro set, round trip, a clean render)
 * plus the roster's own claims: every preset is its numbered candidate of the roster audition, to the bit, and every
 * one plays as a pad at the lowest, the middle and the top key. No spread test: this roster is picked, and good picks
 * cluster (PLUCK, TINES and THUMP have none either).
 *
 * These prove the roster is sound and is what the owner was sent. They cannot prove it is good: only his ear can,
 * at the final listen.
 */
class GlintPresetsTest {

    private companion object {
        /** The frozen roster: names in order, per voice. */
        val names = mapOf(
            GlintVoice.SWEEP to listOf("WARM DRIFT", "DEEP BLOOM", "OPEN SLIDE", "HOLLOW MID", "AMBER", "DARK RISE", "WARM RISE", "BRIGHT FALL"),
            GlintVoice.STEP to listOf("SOFT LADDER", "WARM BUZZ", "LONG CLIMB", "FULL BUZZ", "BRIGHT CLIMB", "RISING STEPS", "TWIN STEPS", "GLASS LADDER"),
            GlintVoice.BRASS to listOf("MELLOW HORN", "PLUSH HORN", "DARK TUBA", "SWAY HORN", "BRIGHT HORN", "BREATHY HORN", "STEADY HORN", "SLOW ROLL"),
            GlintVoice.VOWEL to listOf("STILL AH", "BUZZY EH", "SOFT OH", "RISING OO", "PLAIN OO", "FALLING EH", "RISING OH", "OPEN AH"),
        )

        /** Each preset's number among the roster audition's sixteen candidates (its clip `<VOICE>-NN`), in roster order. */
        val candidates = mapOf(
            GlintVoice.SWEEP to listOf(15, 9, 1, 10, 3, 13, 8, 14),
            GlintVoice.STEP to listOf(7, 14, 4, 15, 9, 5, 3, 16),
            GlintVoice.BRASS to listOf(11, 10, 1, 2, 6, 5, 16, 13),
            GlintVoice.VOWEL to listOf(10, 14, 1, 6, 3, 4, 11, 16),
        )
    }

    @Test
    fun `eight per voice, 32 in all, and all() is the four voices in order`() {
        for (voice in GlintVoice.entries) {
            val presets = GlintPresets.forVoice(voice)
            assertEquals(8, presets.size, "$voice should ship 8 presets, has ${presets.size}")
            assertTrue(presets.all { it.voice == voice }, "$voice holds another voice's preset")
        }
        assertEquals(32, GlintPresets.all().size)
        assertEquals(GlintVoice.entries.flatMap { GlintPresets.forVoice(it) }, GlintPresets.all())
    }

    @Test
    fun `names are uppercase, at most 14 characters, one or two words, unique per voice, and the frozen roster in order`() {
        for (voice in GlintVoice.entries) {
            val got = GlintPresets.forVoice(voice).map { it.name }
            assertEquals(got.toSet().size, got.size, "$voice has duplicate preset names: $got")
            for (name in got) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
                val words = name.split(" ")
                assertTrue(words.size in 1..2 && words.all { it.isNotEmpty() }, "$voice/$name is not one or two words")
            }
            assertEquals(names.getValue(voice), got, "$voice's roster")
        }
    }

    @Test
    fun `no preset name is an engine's name, a voice's or a rack section's`() {
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
            MercuryPatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + GlintVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its rack sections")
        for (preset in GlintPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = GlintPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `every preset names every macro, in range, and DEPTH stays at 0_9 or under`() {
        for (preset in GlintPresets.all()) {
            val wanted = Glint.macrosFor(preset.voice).map { it.name }.toSet()
            assertEquals(wanted, preset.macros.keys, "${preset.voice} ${preset.name} leaves a macro at its default")
            for ((macro, value) in preset.macros) assertTrue(value in 0f..1f, "${preset.name}: $macro is $value")
            assertTrue(preset.macros.getValue("DEPTH") <= 0.9f, "${preset.name}: DEPTH ${preset.macros["DEPTH"]} is past 0.9, where every voice is the same sine")
        }
    }

    @Test
    fun `no two presets of a voice are the same sound`() {
        for (voice in GlintVoice.entries) {
            val presets = GlintPresets.forVoice(voice)
            assertEquals(presets.size, presets.map { it.macros }.toSet().size, "$voice has two presets with the same macros")
        }
    }

    @Test
    fun `every preset is its numbered candidate of the roster audition, to the bit`() {
        for (voice in GlintVoice.entries) {
            val spread = GlintRosterCandidates.forVoice(voice)
            for ((i, preset) in GlintPresets.forVoice(voice).withIndex()) {
                val number = candidates.getValue(voice)[i]
                for (macro in GlintRosterCandidates.varied(voice)) {
                    assertEquals(
                        spread[number - 1].macros.getValue(macro), preset.macros.getValue(macro),
                        "$voice ${preset.name}: $macro is not candidate $number's",
                    )
                }
            }
        }
    }

    @Test
    fun `TUNE, DECAY and FOLLOW ship at their defaults, which the final listen may move`() {
        for (preset in GlintPresets.all()) {
            val defaults = Glint.defaults(preset.voice)
            for (macro in listOf("TUNE", "DECAY", "FOLLOW")) {
                if (macro in defaults) {
                    assertEquals(defaults.getValue(macro), preset.macros.getValue(macro), "${preset.voice} ${preset.name}: $macro left its default")
                }
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in GlintPresets.all()) {
            val restored = Patches.fromJsonText(preset.toJsonText()) as GlintPatch
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `every preset renders clean audio, finite and at or under 0_99`() {
        for (preset in GlintPresets.all()) {
            val snip = preset.render()
            val what = "${preset.voice} ${preset.name}"
            assertTrue(snip.frameCount > 0, "$what rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "$what produced non-finite samples")
            assertTrue(snip.peak() <= 0.99f + 1e-6f, "$what peaks at ${snip.peak()}, over the 0.99 ceiling")
            assertTrue(snip.peak() > 0.05f, "$what is nearly silent: peak ${snip.peak()}")
        }
    }

    @Test
    fun `every preset plays as a pad at the lowest, the middle and the top key, and the loop closes`() {
        for (preset in GlintPresets.all()) {
            val midis = Keys.glintPadMidis(preset.voice)
            for (midi in listOf(midis.first(), midis[midis.size / 2], midis.last())) {
                // glintPad itself refuses a loop that does not close (requireSeam), so rendering is the check.
                val note = Keys.glintPad(preset.voice, preset.macros, midi)
                val what = "${preset.voice} ${preset.name} at MIDI $midi"
                assertTrue(note.snip.samples.all { it.isFinite() }, "$what: a sample is not finite")
                assertTrue(note.loopStartFrame in 1L until note.snip.frameCount.toLong(), "$what: the loop marker ${note.loopStartFrame} is outside the note")
            }
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintPresetsTest'
```

Expected: the build fails in `:synth:compileTestKotlin` with `Unresolved reference` for `GlintPresets`.

- [ ] **Step 3: Write the table**

Create `synth/src/main/kotlin/com/snipsnap/synth/GlintPresets.kt`:

```kotlin
package com.snipsnap.synth

/**
 * GLINT's factory presets (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6): eight per voice,
 * thirty-two in all.
 *
 * Each is one of the numbered candidates of the roster audition (`GlintRosterCandidates`: sixteen per voice, a
 * stratified spread of PEAK, BODY, BLOOM and DEPTH, rendered as held pads), and the trailing comment is its number.
 * The values are the candidate's, to the bit. The eight per voice were chosen by a stated rule: drop the shrill
 * (a centroid over 5 kHz, or most of the power above 4 kHz), drop the nearly-plain-sine (DEPTH 0.84 or more, or
 * nearly half the power in the fundamental), then take the eight whose settings differ most; and the owner was
 * sent them and approved them. The names describe what was measured.
 *
 * TUNE, DECAY and FOLLOW were not part of the spread, so they ship at their defaults: TUNE is overridden per zone in
 * a pad, a held pad ignores DECAY, and FOLLOW does nothing at the pitch the audition used. The final listen is where
 * FOLLOW is heard across the keyboard, and where it may be moved.
 *
 * Order is a contract (`--preset N`, `.first()` pins): the most typical first, each next the most different from
 * those before it. [GlintPresetsTest] pins the names, the order and the provenance.
 */
object GlintPresets {

    private fun p(voice: GlintVoice, name: String, vararg macros: Pair<String, Float>) =
        GlintPatch(name, voice, macros.toMap())

    fun forVoice(voice: GlintVoice): List<GlintPatch> = when (voice) {
        GlintVoice.SWEEP -> sweepPresets
        GlintVoice.STEP -> stepPresets
        GlintVoice.BRASS -> brassPresets
        GlintVoice.VOWEL -> vowelPresets
    }

    fun all(): List<GlintPatch> = GlintVoice.entries.flatMap { forVoice(it) }

    private val sweepPresets = listOf(
        p(GlintVoice.SWEEP, "WARM DRIFT", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.6666667f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.66f), // #15
        p(GlintVoice.SWEEP, "DEEP BLOOM", "TUNE" to 0.5f, "PEAK" to 0.06666667f, "FOLLOW" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #09
        p(GlintVoice.SWEEP, "OPEN SLIDE", "TUNE" to 0.5f, "PEAK" to 0.46666667f, "FOLLOW" to 0.8f, "BODY" to 0.46666667f, "BLOOM" to 0.8666667f, "DECAY" to 0.5f, "DEPTH" to 0.060000002f), // #01
        p(GlintVoice.SWEEP, "HOLLOW MID", "TUNE" to 0.5f, "PEAK" to 0.53333336f, "FOLLOW" to 0.8f, "BODY" to 0.93333334f, "BLOOM" to 0.6f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #10
        p(GlintVoice.SWEEP, "AMBER", "TUNE" to 0.5f, "PEAK" to 0.2f, "FOLLOW" to 0.8f, "BODY" to 0.2f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #03
        p(GlintVoice.SWEEP, "DARK RISE", "TUNE" to 0.5f, "PEAK" to 0.13333334f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #13
        p(GlintVoice.SWEEP, "WARM RISE", "TUNE" to 0.5f, "PEAK" to 0.4f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 0.13333334f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #08
        p(GlintVoice.SWEEP, "BRIGHT FALL", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "FOLLOW" to 0.8f, "BODY" to 0.73333335f, "BLOOM" to 0.8f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #14
    )

    private val stepPresets = listOf(
        p(GlintVoice.STEP, "SOFT LADDER", "TUNE" to 0.5f, "PEAK" to 0.46666667f, "FOLLOW" to 0.8f, "BODY" to 0.73333335f, "BLOOM" to 0.46666667f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #07
        p(GlintVoice.STEP, "WARM BUZZ", "TUNE" to 0.5f, "PEAK" to 0.0f, "FOLLOW" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 0.2f, "DECAY" to 0.5f, "DEPTH" to 0.17999999f), // #14
        p(GlintVoice.STEP, "LONG CLIMB", "TUNE" to 0.5f, "PEAK" to 0.13333334f, "FOLLOW" to 0.8f, "BODY" to 0.4f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #04
        p(GlintVoice.STEP, "FULL BUZZ", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.0f), // #15
        p(GlintVoice.STEP, "BRIGHT CLIMB", "TUNE" to 0.5f, "PEAK" to 0.4f, "FOLLOW" to 0.8f, "BODY" to 0.2f, "BLOOM" to 0.6666667f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #09
        p(GlintVoice.STEP, "RISING STEPS", "TUNE" to 0.5f, "PEAK" to 0.53333336f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #05
        p(GlintVoice.STEP, "TWIN STEPS", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "FOLLOW" to 0.8f, "BODY" to 1.0f, "BLOOM" to 0.73333335f, "DECAY" to 0.5f, "DEPTH" to 0.6f), // #03
        p(GlintVoice.STEP, "GLASS LADDER", "TUNE" to 0.5f, "PEAK" to 0.6f, "FOLLOW" to 0.8f, "BODY" to 0.93333334f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #16
    )

    private val brassPresets = listOf(
        p(GlintVoice.BRASS, "MELLOW HORN", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "FOLLOW" to 0.8f, "BODY" to 0.26666668f, "BLOOM" to 0.4f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #11
        p(GlintVoice.BRASS, "PLUSH HORN", "TUNE" to 0.5f, "PEAK" to 0.8f, "FOLLOW" to 0.8f, "BODY" to 1.0f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #10
        p(GlintVoice.BRASS, "DARK TUBA", "TUNE" to 0.5f, "PEAK" to 0.0f, "FOLLOW" to 0.8f, "BODY" to 0.8666667f, "BLOOM" to 0.6f, "DECAY" to 0.5f, "DEPTH" to 0.120000005f), // #01
        p(GlintVoice.BRASS, "SWAY HORN", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "FOLLOW" to 0.8f, "BODY" to 0.0f, "BLOOM" to 0.8666667f, "DECAY" to 0.5f, "DEPTH" to 0.71999997f), // #02
        p(GlintVoice.BRASS, "BRIGHT HORN", "TUNE" to 0.5f, "PEAK" to 0.73333335f, "FOLLOW" to 0.8f, "BODY" to 0.6f, "BLOOM" to 0.06666667f, "DECAY" to 0.5f, "DEPTH" to 0.24000001f), // #06
        p(GlintVoice.BRASS, "BREATHY HORN", "TUNE" to 0.5f, "PEAK" to 0.26666668f, "FOLLOW" to 0.8f, "BODY" to 0.53333336f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #05
        p(GlintVoice.BRASS, "STEADY HORN", "TUNE" to 0.5f, "PEAK" to 0.6f, "FOLLOW" to 0.8f, "BODY" to 0.8f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.17999999f), // #16
        p(GlintVoice.BRASS, "SLOW ROLL", "TUNE" to 0.5f, "PEAK" to 0.2f, "FOLLOW" to 0.8f, "BODY" to 0.13333334f, "BLOOM" to 0.93333334f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #13
    )

    private val vowelPresets = listOf(
        p(GlintVoice.VOWEL, "STILL AH", "TUNE" to 0.5f, "PEAK" to 0.4f, "BODY" to 0.4f, "BLOOM" to 0.53333336f, "DECAY" to 0.5f, "DEPTH" to 0.42f), // #10
        p(GlintVoice.VOWEL, "BUZZY EH", "TUNE" to 0.5f, "PEAK" to 0.8f, "BODY" to 0.06666667f, "BLOOM" to 0.8f, "DECAY" to 0.5f, "DEPTH" to 0.0f), // #14
        p(GlintVoice.VOWEL, "SOFT OH", "TUNE" to 0.5f, "PEAK" to 0.2f, "BODY" to 0.8666667f, "BLOOM" to 0.26666668f, "DECAY" to 0.5f, "DEPTH" to 0.78f), // #01
        p(GlintVoice.VOWEL, "RISING OO", "TUNE" to 0.5f, "PEAK" to 0.0f, "BODY" to 0.2f, "BLOOM" to 0.0f, "DECAY" to 0.5f, "DEPTH" to 0.48000002f), // #06
        p(GlintVoice.VOWEL, "PLAIN OO", "TUNE" to 0.5f, "PEAK" to 0.06666667f, "BODY" to 0.93333334f, "BLOOM" to 0.73333335f, "DECAY" to 0.5f, "DEPTH" to 0.35999998f), // #03
        p(GlintVoice.VOWEL, "FALLING EH", "TUNE" to 0.5f, "PEAK" to 0.6666667f, "BODY" to 0.13333334f, "BLOOM" to 1.0f, "DECAY" to 0.5f, "DEPTH" to 0.54f), // #04
        p(GlintVoice.VOWEL, "RISING OH", "TUNE" to 0.5f, "PEAK" to 0.33333334f, "BODY" to 0.8f, "BLOOM" to 0.2f, "DECAY" to 0.5f, "DEPTH" to 0.3f), // #11
        p(GlintVoice.VOWEL, "OPEN AH", "TUNE" to 0.5f, "PEAK" to 0.6f, "BODY" to 0.33333334f, "BLOOM" to 0.33333334f, "DECAY" to 0.5f, "DEPTH" to 0.060000002f), // #16
    )
}
```

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintPresetsTest'
```

Expected: exit code 0, and `synth/build/test-results/test/TEST-com.snipsnap.synth.GlintPresetsTest.xml` has eleven `<testcase` elements, no `<failure`, no `<error`. If the taken-words or blocklist test names a word, that is a house rule biting a name in the table: stop and report the name; do not weaken the test or pick a replacement (the controller renames with the owner).

- [ ] **Step 5: Prove the guards, each alone**

One change at a time to `GlintPresets.kt`, the same command, confirm the named test fails with its message, restore by hand, re-run once at the end for green:

1. Change `"AMBER"` to `"Amber"`. Expected: `names are uppercase…` fails ("is not uppercase").
2. Change `"PLAIN OO"` to `"RISING OO"`. Expected: `names are uppercase…` fails ("has duplicate preset names").
3. Change `"DARK RISE"` to `"DARK SWEEP"`. Expected: `no preset name is an engine's name, a voice's…` fails (names `[SWEEP]`).
4. Change SWEEP's `"WARM DRIFT"` row's `"PEAK" to 0.26666668f` to `0.267f`. Expected: `every preset is its numbered candidate…` fails ("PEAK is not candidate 15's").
5. Change a DEPTH to `0.95f`. Expected: `every preset names every macro…` fails ("past 0.9") and the provenance test fails too.
6. Delete `"BLOOM" to 1.0f, ` from DEEP BLOOM's row. Expected: `every preset names every macro…` fails ("leaves a macro at its default") and `GlintPatch`'s own validation may fail the class load: either way the message names the preset.

- [ ] **Step 6: Commit**

`git add` the two files, one per call. Title: `Give GLINT the factory roster the owner approved`. Body: the roster is 32 presets, eight per voice, each one of the numbered candidates of the stratified spread the owner was sent as held pads on his phone, in the rank order of the stated rule (not shrill, not nearly a plain sine, then the most different, most typical first), with values generated from the candidates and printed exactly (the project's own roster-line tool writes values the same way) so no row was retyped; the test pins every varied macro to its candidate to the bit, which is the guard against a slip in 32 rows. TUNE, DECAY and FOLLOW ship at their defaults and are flagged: FOLLOW is exactly inert at the pitch the audition used, so nobody could hear it. Six names differ from the list he approved because a house rule forbids a word that is a voice, an engine or a rack section (the test says so); he can veto them at the final listen. Each guard was shown to fail when undone on its own.

---

### Task 2: Wire the roster into `Presets`

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Presets.kt`

**Interfaces:**
- Consumes: `GlintPresets.forVoice`, `GlintPresets.all` (Task 1), `GlintPatch.ENGINE` (`"GLINT"`).
- Produces: `Presets.forVoice("GLINT", voice)` and `Presets.all()` include GLINT; `Presets.byName` finds a GLINT preset.

- [ ] **Step 1: Write the failing test**

In `PresetsTest.kt`, in `forVoice forwards every registered engine by name`, add after the MERCURY line:

```kotlin
        for (voice in GlintVoice.entries) assertEquals(GlintPresets.forVoice(voice), Presets.forVoice("GLINT", voice.name))
```

and in `all sums every registered engine's roster with nothing lost or duplicated`, change

```kotlin
            BorePresets.all() + ArcoPresets.all() + MercuryPresets.all()
        assertEquals(expected.size, Presets.all().size)
```

to

```kotlin
            BorePresets.all() + ArcoPresets.all() + MercuryPresets.all() + GlintPresets.all()
        assertEquals(expected.size, Presets.all().size)
```

and add this test beside `byName finds a preset and returns null for a miss`:

```kotlin
    @Test
    fun `byName finds a GLINT preset by voice and name`() {
        assertEquals("STILL AH", Presets.byName("GLINT", "VOWEL", "STILL AH")?.name)
        assertEquals(GlintPresets.forVoice(GlintVoice.SWEEP).first(), Presets.byName("GLINT", "SWEEP", "WARM DRIFT"))
        assertEquals(null, Presets.byName("GLINT", "SWEEP", "NOT A PRESET"))
    }
```

- [ ] **Step 2: Run it to make sure it fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.PresetsTest'
```

Expected: three failures: the forwarding test (`Presets.forVoice("GLINT", …)` is empty), the sum test (the expected list is 32 longer) and the new `byName` test (null).

- [ ] **Step 3: Wire it**

In `Presets.kt`, in `forVoice`, after the `MercuryPatch.ENGINE` branch and before `else -> emptyList()`, add:

```kotlin
        GlintPatch.ENGINE -> {
            val v = GlintVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            GlintPresets.forVoice(v)
        }
```

in `all()`, change `BorePresets.all() + ArcoPresets.all() + MercuryPresets.all()` to `BorePresets.all() + ArcoPresets.all() + MercuryPresets.all() + GlintPresets.all()`, and in the class KDoc change `This dispatcher now covers all fifteen` to `This dispatcher now covers all sixteen`.

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.PresetsTest' --tests 'com.snipsnap.synth.GlintPresetsTest'
```

Expected: exit code 0, no `<failure`, no `<error` in either result XML.

- [ ] **Step 5: Commit**

`git add` the two files, one per call. Title: `Register GLINT's roster with the preset dispatcher`. Body: the app's preset chips, the CLI's `--preset N` and `--all`, and the shell's roster-line tooling all read `Presets.forVoice`, so this is the whole wiring; `PresetsTest`'s two cross-engine laws are deliberate hand-written restatements and now name GLINT beside the other fifteen, so a seventeenth engine joining without them fails. The KDoc said fifteen.

---

### Task 3: SCRAMBLE seeds from a preset

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt` (two new tests beside `scrambles are reproducible and stay in range`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt` (the test at `SCRAMBLE's earlier macros draw as they did before DEPTH existed`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` (`scramble` and its KDoc)

**Interfaces:**
- Consumes: `GlintPresets.forVoice` (Task 1), `Dsp.scrambleNear(seed, temperature, random)` (temperature 0 returns the seed untouched; 1 discards it).
- Produces: `Glint.scramble(voice, random, temperature = 0.35f, near = null)` seeds from `near`, else the defaults at temperature 1 (no draw spent), else a random preset of the voice.

- [ ] **Step 1: Write the failing tests**

In `GlintTest.kt`, after `scrambles are reproducible and stay in range`, add:

```kotlin
    @Test
    fun `a scramble with no near patch rolls around a factory preset, and is exactly one at temperature 0`() {
        for (voice in GlintVoice.entries) {
            val presets = GlintPresets.forVoice(voice).map { it.macros }.toSet()
            for (seed in 0L until 12L) {
                val rolled = Glint.scramble(voice, Random(seed), temperature = 0f)
                assertTrue(rolled in presets, "$voice seed $seed: temperature 0 gave $rolled, which is no factory preset")
            }
            val seen = (0L until 40L).map { Glint.scramble(voice, Random(it), temperature = 0f) }.toSet()
            assertTrue(seen.size > 1, "$voice: every seed picked the same preset")
        }
    }

    @Test
    fun `an explicit near patch seeds the roll, and temperature 1 is the flat roll that spends no draw on a preset`() {
        for (voice in GlintVoice.entries) {
            val near = GlintPresets.forVoice(voice).last()
            assertEquals(near.macros, Glint.scramble(voice, Random(5), temperature = 0f, near = near), "$voice: near was ignored")
            assertEquals(
                Dsp.scrambleNear(Glint.defaults(voice), 1f, Random(3)),
                Glint.scramble(voice, Random(3), temperature = 1f),
                "$voice: temperature 1 is not the flat roll",
            )
        }
    }
```

In `GlintDepthTest.kt`, replace

```kotlin
            val now = Glint.scramble(voice, Random(7))
```

with

```kotlin
            // An explicit near patch with no macros seeds the roll from the defaults, so no preset is drawn and
            // these are the draws DEPTH's arrival must not have moved.
            val now = Glint.scramble(voice, Random(7), near = GlintPatch("Defaults", voice, emptyMap()))
```

- [ ] **Step 2: Run them to make sure the first fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintTest' --tests 'com.snipsnap.synth.GlintDepthTest'
```

Expected: `a scramble with no near patch rolls around a factory preset…` fails (today's scramble rolls around the defaults, which are no preset); the other two new or changed tests pass already (they describe what must not move). That one red is the meaningful one.

- [ ] **Step 3: Write the seeding**

In `Glint.kt`, replace

```kotlin
    /**
     * No preset roster yet — Phase 3 authors one and this gains the
     * preset-seeded form every other engine uses (see `Resin.scramble`).
     */
    fun scramble(
        voice: GlintVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }
```

with

```kotlin
    /**
     * SCRAMBLE near a factory preset (see [Thump.scramble]): with no [near] patch and a temperature below 1 the roll
     * is seeded from a random preset of the voice; at 1 it is the flat roll it always was, and a spent draw there
     * would shift a seeded Random's whole downstream sequence.
     */
    fun scramble(
        voice: GlintVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + GlintPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }
```

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintTest' --tests 'com.snipsnap.synth.GlintDepthTest'
```

Expected: exit code 0, no `<failure`, no `<error` in either result XML.

- [ ] **Step 5: Prove the guards, each alone**

One change at a time to `Glint.kt`'s `scramble`, same command, restore by hand, re-run once for green:

1. Drop the `near != null ->` branch (so `near` is ignored). Expected: `an explicit near patch seeds the roll…` fails ("near was ignored").
2. Drop the `temperature >= 1f -> base` branch. Expected: the same test fails ("temperature 1 is not the flat roll"), because a preset draw is spent first.
3. Replace the preset draw with `base` (today's behaviour). Expected: `a scramble with no near patch rolls around a factory preset…` fails.

- [ ] **Step 6: Commit**

`git add` the three files, one per call. Title: `Seed GLINT's SCRAMBLE from a factory preset, as every other engine's is`. Body: `Glint.scramble`'s own KDoc said the preset-seeded form arrives with the roster. It follows SIREN's: an explicit `near` patch seeds it, temperature 1 stays the flat roll and spends no draw on a preset (a spent draw shifts a seeded Random's whole downstream sequence, the reason THUMP carves it out), anything else rolls near a random preset of the voice. The DEPTH PR's pin that SCRAMBLE's earlier macros draw as they did before DEPTH existed asserted the defaults-seeded roll, true only while there was no roster; it now takes the explicit `near` form, which still seeds from the defaults and still pins what DEPTH's arrival must not move. The two other new tests describe what the seeding must keep, and each guard was shown to fail when undone.

---

### Task 4: The final listen's files

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterListenGeneratorTest.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterListenGenerator.kt`
- Modify: `synth/build.gradle.kts` (after the `generateGlintRosterAudition` block)
- Modify: `.gitignore` (after the `testkit/glint-roster-candidates/` entry)

**Interfaces:**
- Consumes: `GlintPresets.forVoice` (Task 1), `GlintPatch.render()` (the one-shot, a mono `Snip`), `Keys.glintPadMidis`, `Keys.glintPad`, `GlintPathsAuditionGenerator.heldFor`, `AuditionLevel.level`, `WavWriter.write`, `GlintRosterAuditionGenerator.prepare(dir)` (removes `<VOICE>-NN.wav` and `manifest.txt` only).
- Produces: `GlintRosterListenGenerator.main(args)`, `fileName(voice, number)`, `clipFor(preset): Snip`; the Gradle task `generateGlintRosterListen`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterListenGeneratorTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster's final listen (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2.5): one file per preset. */
class GlintRosterListenGeneratorTest {

    @Test
    fun `a preset's file is its one-shot, then the pad at the lowest key, then at the top key`() {
        val preset = GlintPresets.forVoice(GlintVoice.SWEEP).first()
        val clip = GlintRosterListenGenerator.clipFor(preset)
        val rate = clip.sampleRate
        val shortest = preset.render().frameCount + 2 * (0.6f * rate).toInt() + 2 * 4 * rate
        assertTrue(clip.frameCount >= shortest, "the file is ${clip.frameCount} frames, shorter than its parts ($shortest)")
        assertTrue(clip.samples.all { it.isFinite() }, "a sample is not finite")
        assertTrue(clip.samples.maxOf { abs(it) } <= 0.99f, "the file is over the audition guard")
        assertTrue(abs(clip.samples.last()) < 1e-6f, "the top pad does not end at zero")
    }

    @Test
    fun `the file names are the roster audition's pattern, so one cleaner serves both folders`() {
        val pattern = Regex("(SWEEP|STEP|BRASS|VOWEL)-\\d\\d\\.wav")
        for (voice in GlintVoice.entries) {
            for (n in 1..8) assertTrue(pattern.matches(GlintRosterListenGenerator.fileName(voice, n)), "$voice-$n: ${GlintRosterListenGenerator.fileName(voice, n)}")
        }
        assertEquals("STEP-03.wav", GlintRosterListenGenerator.fileName(GlintVoice.STEP, 3))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterListenGeneratorTest'
```

Expected: the build fails in `:synth:compileTestKotlin` with `Unresolved reference` for `GlintRosterListenGenerator`.

- [ ] **Step 3: Write the generator**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterListenGenerator.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * The GLINT roster's final listen (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2.5): each
 * of the 32 factory presets as the owner will meet it, in one file: what a kit pad plays (the one-shot), then the held
 * pad at the lowest key, then at the top key. The middle key is the one the roster audition used, and FOLLOW, the one
 * value nobody could hear there, only shows at the ends. Each part is levelled by [AuditionLevel]. The files are
 * `<VOICE>-01` to `<VOICE>-08` in roster order, and `manifest.txt` says which preset each is. Not a test - run with
 * `generateGlintRosterListen`.
 */
object GlintRosterListenGenerator {

    /** The least a pad runs; a long STEP climb runs it to the end of the first whole breath after the climb. */
    private const val PAD_SECONDS = 4f
    private const val GAP_SECONDS = 0.6f
    private const val FADE_SECONDS = 0.05f
    private const val MANIFEST = "manifest.txt"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-roster-final")
        GlintRosterAuditionGenerator.prepare(dir)
        val lines = GlintVoice.entries.flatMap { voice ->
            GlintPresets.forVoice(voice).mapIndexed { i, preset ->
                WavWriter.write(File(dir, fileName(voice, i + 1)), clipFor(preset), WavWriter.BitDepth.PCM_16)
                String.format(Locale.ROOT, "%s-%02d  %s", voice.name, i + 1, preset.name)
            }
        }
        File(dir, MANIFEST).writeText(lines.joinToString("\n", postfix = "\n"))
        println("wrote ${lines.size} presets and $MANIFEST to ${dir.absolutePath}")
    }

    internal fun fileName(voice: GlintVoice, number: Int): String = String.format(Locale.ROOT, "%s-%02d.wav", voice.name, number)

    /** The one-shot, a gap, the pad at the lowest key, a gap, the pad at the top key. */
    internal fun clipFor(preset: GlintPatch): Snip {
        val oneShot = AuditionLevel.level(preset.render())
        val rate = oneShot.sampleRate
        val midis = Keys.glintPadMidis(preset.voice)
        val gap = FloatArray((GAP_SECONDS * rate).toInt())
        val parts = listOf(oneShot.samples, gap, pad(preset, midis.first(), rate), gap, pad(preset, midis.last(), rate))
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return Snip(out, channels = 1, sampleRate = rate)
    }

    /** The pad at [midi]: [PAD_SECONDS], or to the end of the first whole breath after a long climb, faded out, levelled. */
    private fun pad(preset: GlintPatch, midi: Int, rate: Int): FloatArray {
        val note = Keys.glintPad(preset.voice, preset.macros, midi)
        val played = GlintPathsAuditionGenerator.heldFor(note, PAD_SECONDS)
        require(played.sampleRate == rate) { "the pad is at ${played.sampleRate} Hz and the one-shot at $rate" }
        val samples = played.samples.copyOf(maxOf((PAD_SECONDS * rate).toInt(), note.loopStartFrame.toInt()))
        val fade = (FADE_SECONDS * rate).toInt()
        for (i in 0 until fade) {
            val at = samples.size - fade + i
            samples[at] = (samples[at] * 0.5 * (1.0 + cos(PI * (i + 1) / fade))).toFloat()
        }
        return AuditionLevel.level(Snip(samples, channels = 1, sampleRate = rate)).samples
    }
}
```

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterListenGeneratorTest'
```

Expected: exit code 0, two `<testcase` elements, no `<failure`, no `<error`.

- [ ] **Step 5: Prove the guards, each alone**

One change to `GlintRosterListenGenerator.kt`, same command, restore by hand, re-run once for green:

1. In `clipFor`, drop the last `gap, pad(preset, midis.last(), rate)` pair (so the top key is missing). Expected: `a preset's file is its one-shot…` fails ("shorter than its parts").
2. In `pad`, set `val fade = 0`. Expected: the same test fails ("the top pad does not end at zero") or, if the cut happens to land near zero, change nothing: report which. (The first audition's no-fade mutation failed this way on SWEEP.)
3. In `fileName`, change `%02d` to `%d`. Expected: `the file names are the roster audition's pattern…` fails.

- [ ] **Step 6: Add the Gradle task and the ignore entry**

In `synth/build.gradle.kts`, after the `generateGlintRosterAudition` block and its blank line, add:

```kotlin
/** Render the GLINT roster's final listen (each preset as a one-shot and two pads, WAVs and a manifest) under testkit/glint-roster-final/. See GlintRosterListenGenerator. */
tasks.register<JavaExec>("generateGlintRosterListen") {
    group = "distribution"
    description = "Render the GLINT roster's final listen (one file per preset) under testkit/glint-roster-final/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintRosterListenGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-roster-final")
}
```

In `.gitignore`, after the `testkit/glint-roster-candidates/` line and its blank line, add:

```
# The GLINT roster's final-listen files (one per preset: the one-shot and two pads), rendered by
# :synth:generateGlintRosterListen and sent to the phone for the owner's last check; never committed.
testkit/glint-roster-final/
```

- [ ] **Step 7: Commit**

`git add` the four files, one per call. Title: `Render the roster's final listen: each preset as a one-shot and two pads`. Body: the spec's last gate before the PR is the owner hearing the final 32 as pads and as one-shots. The roster audition rendered every candidate at one key, the middle one, where FOLLOW changes nothing, so the final listen is where FOLLOW can be heard at all: each file is the one-shot a kit pad plays, then the held pad at the lowest and the top key (the middle key was already heard), each part levelled by the audition rule, a pad running to the end of the first whole breath after a long STEP climb. The file names are the roster audition's pattern so its cleaner serves this folder too. The tests pin the file's parts, its ending at silence and the names; each guard was shown to fail when undone.

---

### Task 5: Docs

**Files:**
- Modify: `docs/SYNTH_ROADMAP.md` (the S10 row, line 265, its last sentences)
- Modify: `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md` (§6.2, a paragraph after the one that ends "...so it is the one he is most likely to move.")

**Interfaces:** none (documentation).

- [ ] **Step 1: The roadmap row**

In `docs/SYNTH_ROADMAP.md`, replace exactly this text (the end of the S10 row's line)

```
**DEPTH built — awaiting its listen** — a seventh macro (VOWEL's sixth), default 0 so every sound is unchanged, that rounds the window's edge over its lower half and fades a plain sine in over its upper half (the sine scaled to the burst's loudness, its share of the power the square of `2·DEPTH − 1`, a bare sine at 1); both ingredients are zero at the wrap, so `k` still moves only there. It was heard as probe clips by the owner before it was specified. The factory roster (eight per voice, picked by ear from a stratified spread) is the next phase, after DEPTH merges. Design in `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`. | S1 + U5 + U6 |
```

with

```
**DEPTH shipped** (PR #431) — a seventh macro (VOWEL's sixth), default 0 so every sound is unchanged, that rounds the window's edge over its lower half and fades a plain sine in over its upper half (the sine scaled to the burst's loudness, its share of the power the square of `2·DEPTH − 1`, a bare sine at 1); both ingredients are zero at the wrap, so `k` still moves only there. It was heard as probe clips by the owner before it was specified, and on the real engine's pads before it merged. **Factory roster built** — eight presets per voice, 32 in all (`GlintPresets`), each one of the numbered held-pad candidates of a stratified spread of PEAK, BODY, BLOOM and DEPTH (`GlintRosterCandidates`, the clips sent to the owner), chosen by a stated rule (not shrill, not nearly a plain sine, then the most different) and approved by him; the final listen is his ear's check. FOLLOW is exactly inert at the audition's pitch, so it ships with TUNE and DECAY at its default and is heard across the keyboard at the final listen. SCRAMBLE now seeds from a random preset, as every other engine's does. Design in `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`. | S1 + U5 + U6 |
```

- [ ] **Step 2: Spec §6.2, how the picks were made**

In `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`, after the paragraph that ends `so it is the one he is most likely to move.` add a blank line and:

```
**As run.** Hearing 64 clips and choosing 32 was more than the owner wanted to do, so he had the eight per voice ranked for him and kept the veto: this is a waiver of "No value in the roster comes from Claude's taste", by the owner, for this step. The ranking is a stated rule applied to measurements of each clip's sustained breath (its last three seconds): drop the shrill (a spectral centroid over 5 kHz, or over 85% of the power above 4 kHz), drop the nearly-plain-sine (DEPTH 0.84 or more, or over 45% of the power in the fundamental), then take the eight whose settings differ most, starting from the most typical, which is the roster order. The rule used measurements, not ears, and no measurement says a sound is good; the owner was sent the eight per voice as one reel each and approved them, and hears all 32 at the final listen. Six names changed afterwards because the house naming rule forbids a word that is a voice, an engine or a rack section (`GlintPresetsTest`).
```

- [ ] **Step 3: Verify**

```
grep -c "DEPTH shipped" docs/SYNTH_ROADMAP.md
grep -c "As run" docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md
grep -c "DEPTH built — awaiting its listen" docs/SYNTH_ROADMAP.md
```

Expected: `1`, `1`, `0`.

- [ ] **Step 4: Commit**

`git add` the two files, one per call. Title: `Record how GLINT's roster was made`. Body: the roadmap row still said DEPTH was awaiting its listen and the roster was the next phase; both are done. The spec's §6.2 said no value comes from anyone's taste, which stopped being true of this step when the owner chose to have the eight per voice ranked for him and keep the veto; the paragraph records that waiver, the rule that did the ranking and what it can and cannot claim, so a reader of the roster knows what was heard and what was measured.

---

### Task 6: Verify, the final listen, and the owner's say (controller steps)

Not builder tasks: the controller runs them, because what comes out goes to the owner.

- [ ] **Step 1: The full suite**

```
./gradlew --no-daemon test
```

In the background, then read the exit code and sum the result XMLs of every module (`:shell`'s `UserPresetsTest` meets the sixteenth engine here). Expected: exit 0, no `<failure`, no `<error`. Report any failure by name, including one not caused by this branch.

- [ ] **Step 2: Render the final listen**

```
./gradlew --no-daemon :synth:generateGlintRosterListen
```

Expected: exit 0 and `wrote 32 presets and manifest.txt to …/testkit/glint-roster-final`. Check `ls testkit/glint-roster-final | wc -l` is 33 and `git status --short` prints nothing for `testkit/`. Check every file with a scratch script (frames at least one one-shot plus two gaps plus two four-second pads, peak at most 0.99, mono 16-bit).

- [ ] **Step 3: Stitch and send**

With a scratch script (macOS `say` for the names, numpy for the rest; not in the repo), build one reel per voice: for each preset in roster order, the name spoken, a short gap, the preset's file, a longer gap. Send the four reels (`SendUserFile`) with a message of this shape: the six renamed presets and why, what each reel holds (name, one-shot, pad at the lowest key, pad at the top key), that FOLLOW is the thing to listen for between the two pads (it is at its default 0.8 and is his to move), and that nothing is pushed until he says.

- [ ] **Step 4: His verdict, then the PR**

Record his reply in the ledger. A veto of a preset or a name is a one-line change plus the tests; a different FOLLOW is a change to that row's `"FOLLOW" to` value and to the `TUNE, DECAY and FOLLOW ship at their defaults` test, which then says which presets moved. When he is content: ask before pushing (`git push -u origin claude/glint-roster`), then open the PR against `claude/mobile-mpc-drum-sampler-t58x74` with a body in the steward's shape (what and why, how the picks were made and what the rule can and cannot claim, the six renamed presets, FOLLOW's flag, what was measured against inferred, the tests), ending with the attribution line the harness names.

---

## Where this plan stops

The PR is opened after the owner's say. Merging is his.
