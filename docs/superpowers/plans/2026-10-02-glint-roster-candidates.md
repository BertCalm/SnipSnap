# GLINT Roster Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run the tooling that puts sixteen candidate held pads per GLINT voice (64 clips) in the owner's hands, so the factory roster can be picked by ear, and fix the four documentation statements the DEPTH review parked.

**Architecture:** `GlintRosterCandidates` (test sources) turns the spec's rule into numbers: each varied macro takes its sixteen levels `i/15` exactly once, shuffled per macro by a recorded seed. `GlintRosterAuditionGenerator` renders each candidate through the engine's own pad path (`Keys.glintPad`), cuts it to five seconds, levels it with `AuditionLevel` and writes `<VOICE>-NN.wav`, plus a plain-text manifest that only the builder reads. A `generateGlintRosterAudition` Gradle task runs it. No production code changes except one KDoc.

**Tech Stack:** Kotlin 2.0 / JVM in the `:synth` module (tests with `kotlin.test` on JUnit), Gradle. Nothing in `:app`, `:shell` or `:cli` is touched.

**Spec:** `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`. This plan is the roster's first half: §6.2 steps 1 to 3 (the candidates, the spread, sending them). The second half (§6.1 the roster file, §6.2 steps 4 and 5 names and the final listen, §6.3 wiring and gates) gets its own plan once the owner has picked, because its shape depends on his picks (spec §7). Read §6 before Task 2; this plan argues from it.

## Global Constraints

- **Sixteen candidates per voice, 64 clips,** "rendered by the real engine as held pads on one zone near the middle of the keyboard, the sound MAKE INSTRUMENT gives, levelled by the repo's audition rule, 5 s each, named `<VOICE>-01` to `-16`" (spec §6.2.1).
- **The values are a stratified spread, not a choice** (spec §6.2.2): "The varied macros are PEAK, FOLLOW, BODY, BLOOM and DEPTH (VOWEL: PEAK, BODY, BLOOM, DEPTH). Each takes 16 evenly spaced levels (`i/15`) exactly once across the 16 candidates, shuffled per macro by a recorded seed, so both ends of every range appear (the ugly end included). DEPTH's levels span 0 to 0.9, never 1.0, which is the same sine on every voice. TUNE and DECAY are not varied: TUNE is overridden per zone in a pad, and held pads ignore DECAY, so any value would be unheard."
- **No value comes from Claude's taste** ("No agent invents a preset value"). Nothing in these tasks may pick, tune, drop or reorder a candidate by listening or by judgment. The seed is chosen once, mechanically (Task 2), and recorded.
- **The numbering is a contract with what the owner is sent.** After the clips go to his phone, `SWEEP-07` must keep naming the same settings: do not change `SEED`, the draw or the order of `varied` afterwards.
- **Test sources only,** plus one KDoc line pair in `GlintPath.kt` (Task 1) and one Gradle task and one `.gitignore` entry (Task 3). No `src/main` behaviour changes, nothing in `Dsp`, `Keys`, `Glint*` rendering, `Presets` or `Patches`.
- **Generated clips are never committed:** `testkit/glint-roster-candidates/` is git-ignored (Task 3).
- **Commits:** plain declarative title (no `feat:`/`fix:` prefix, no ticket number), a body that says why (including what was tried and abandoned), then exactly these two trailer lines:
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XdQuyqmnaSqRVPTo87MNUr
  ```
  No model identifier anywhere else: not in a commit body, a PR text or a code comment. The branch is `claude/glint-roster`. Never push, open a PR, approve or merge; the controller asks the owner first.
- **Worktree and tool rules:** work from `/Users/joshuacramblet/SnipSnap/.claude/worktrees/glint-depth-d1` and never `cd` elsewhere. Multi-line heredocs and compound shell commands (`&&`, `;`, `cd … &&`) are refused here: use the Write and Edit tools for files and `git commit -F <file>` for messages (write the message file under the scratchpad directory, not in the repo).
- **Gradle:** one invocation at a time, never two. Run it in the foreground with a 600000 ms timeout. Gate on the exit code AND on the result XMLs under `synth/build/test-results/test/` (no `<failure`, no `<error`); never grep the console for `FAILED` alone. Never `-x :app:test`; this plan needs no `:app` task.
- **Claims:** a guard is proven by reverting it alone and watching a test fail with a message that names the guard. Report a number you measured as measured and one you inferred as inferred.

## Review Focus

Failure modes the spec implies that a person using these clips would hit, most likely first. Each has its test in the task that owns the code.

1. **A rerun after any change leaves an earlier draw's clips beside the new ones**, and `SWEEP-12` then names a sound the manifest does not describe. (Task 3: `prepare` removes this tool's own file names and nothing else.)
2. **A clip cut at five seconds ends in a click.** The held render is longer than five seconds, so the cut lands mid-waveform. (Task 3: the clip ends at zero.)
3. **A macro name that is wrong or that a voice does not have is silently dropped:** `Keys.glintPad` runs `macros.filterKeys { it in defaults }`, and VOWEL has no FOLLOW. A misspelt or misplaced name would render a sound that does not vary and nobody would see it. (Task 2: every candidate sets exactly its voice's macros, and every varied name is one the voice has.)
4. **A clip at the wrong loudness decides the pick.** All 64 must sit at the audition level or the owner hears loudness, not timbre. (Task 3: loudness 0.03, peak at most 0.99.)
5. **The manifest and the files disagree about a number**, so the owner's "keep 3, 7, 12" wires the wrong settings. (Task 3: file names, manifest lines and candidates agree, and the manifest's numbers parse back to the candidate's values.)

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidates.kt` (new) | The spread: which macros vary, the recorded seed, the sixteen candidates per voice. Pure numbers; renders nothing. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidatesTest.kt` (new) | Pins the spread against spec §6.2.2. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGenerator.kt` (new) | Renders a candidate to a clip, writes the clips and the manifest, cleans its own folder. |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGeneratorTest.kt` (new) | Pins the clip (length, fade, level), the folder cleaning, and the file/manifest agreement. |
| `synth/build.gradle.kts` | `generateGlintRosterAudition`, beside the two GLINT tasks already there. |
| `.gitignore` | `testkit/glint-roster-candidates/`. |
| `GlintPath.kt`, `GlintDepthHeldTest.kt`, the DEPTH spec | Four wording fixes (Task 1). |

---

### Task 1: The four statements the DEPTH review parked

Documentation and comments only; no behaviour changes. They were found in the final review of PR #431, parked there, and promised for this PR's first change.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt:101-103` (the KDoc of `sineGain`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt:14-15` (a comment)
- Modify: `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md` (§2.2, one sentence in the paragraph that begins "`sineGain = rB / rS`"; §4, item 4)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing later tasks use.

What is wrong with each, so the new wording can be checked against the code:

- **N1 and N2 (KDoc and spec §2.2).** Both list "a one-shot's start" among the places where `sineGain` is "right at rest". It is not: `sineGain` is matched at the held breath's centre (`breathRatios(0f, …)`), and a one-shot starts off that centre unless BLOOM is neutral. Spec §2.2 also says "elsewhere it is expected to be within about a dB" in the same paragraph as inferred figures of +5.7 and -6.4 dB; the one-dB expectation was measured only at the two probe setups, and the larger figures are inferred.
- **N3 (the test comment).** It says a rising BLOOM "takes the negative-BLOOM branch of every voice's breath". `GlintPath.breathRatios` has that branch (`val exponent = if (bloom < 0f) -swing else swing`) for the path voices only; VOWEL's breath is `vowelRatios(vowelPos + swing * Glint.VOWEL_LAST * bloom, out)`, which has no branch: a signed BLOOM just walks the vowel line the other way.
- **N4 (spec §4 item 4).** "`sineGain` differs between those settings" has no antecedent after the parenthesis about STEP and BRASS. The test it describes (`at DEPTH 1 the voice does not matter - PEAK, BLOOM and BODY leave the loop alone`) renders SWEEP and VOWEL at two different PEAK/BLOOM/BODY settings. `sineGain` is built from `kBase`, `k2`, `level2` (PEAK and BODY) and, on VOWEL, `vowelPos` and `scale`; `breathRatios(0f, …)` multiplies BLOOM by a zero swing, so on SWEEP and VOWEL it does not read BLOOM.

- [ ] **Step 1: Edit `sineGain`'s KDoc**

In `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt`, replace exactly these three lines

```
     * move along their path: right at rest wherever both bursts ride one level (the held SWEEP, STEP and
     * VOWEL pads, a one-shot's start), an approximation elsewhere. The spec expects it within about a dB
     * there, which has been measured only at the SWEEP and VOWEL probe setups (defaults, BLOOM 0.85).
```

with

```
     * move along their path: right at rest wherever both bursts ride one level (the held SWEEP, STEP and
     * VOWEL pads), an approximation elsewhere, a one-shot's start included. The spec expected it within
     * about a dB elsewhere, but that was measured only at the SWEEP and VOWEL probe setups (defaults,
     * BLOOM 0.85).
```

- [ ] **Step 2: Edit the closure test's comment**

In `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt`, replace exactly these two lines

```
        // BLOOM both ways: 0.8 falls into PEAK and 0.2 rises into it. A rising BLOOM runs STEP's ladder
        // upward and takes the negative-BLOOM branch of every voice's breath, BRASS's included.
```

with

```
        // BLOOM both ways: 0.8 falls into PEAK and 0.2 rises into it. A rising BLOOM runs STEP's ladder
        // upward and takes the negative-BLOOM branch of the path voices' breath, BRASS's included.
        // VOWEL's breath has no such branch: its signed BLOOM walks the vowel line the other way.
```

- [ ] **Step 3: Edit spec §2.2**

In `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`, replace exactly this text (one line of the file, inside the paragraph that begins "`sineGain = rB / rS = √2 · rB`")

```
so it is right at rest wherever both bursts ride one level (the held SWEEP, STEP and VOWEL pads, a one-shot's start); elsewhere it is expected to be within about a dB, which has been measured only at the two probe setups (SWEEP 0.0 dB and VOWEL 0.5 dB off the probe's breath-averaged figure). That is why the real engine is listened to before anything merges (§4).
```

with

```
so it is right at rest wherever both bursts ride one level (the held SWEEP, STEP and VOWEL pads) and an approximation elsewhere, a one-shot's start included. It was expected to be within about a dB elsewhere, but that was measured only at the two probe setups (SWEEP 0.0 dB and VOWEL 0.5 dB off the probe's breath-averaged figure), and the larger figures below are inferred. That is why the real engine is listened to before anything merges (§4).
```

- [ ] **Step 4: Edit spec §4 item 4**

In the same file, replace exactly this text

```
(STEP's ladder moves its loop start and BRASS's level breathes with BLOOM; `sineGain` differs between those settings, and the levelling cancels it).
```

with

```
(STEP and BRASS are left out: STEP's ladder length moves where its loop starts, and BRASS's level breathes with BLOOM. On SWEEP and VOWEL `sineGain` differs between the PEAK and BODY settings and not with BLOOM, and the levelling cancels the difference).
```

- [ ] **Step 5: Verify**

Run each, one at a time:

```
grep -n "a one-shot's start" synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md
grep -n "every voice's breath" synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt
grep -n "those settings\|it is expected to be within about a dB\|The spec expects it" docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt
```

Expected: the first prints exactly two lines, both containing "a one-shot's start included"; the second and third print nothing. Then:

```
./gradlew --no-daemon :synth:compileTestKotlin
```

Expected: exit code 0 (a KDoc and a comment changed, so this proves only that nothing was mangled).

- [ ] **Step 6: Commit**

Write the message to the scratchpad directory as `commit-task1.txt` and run `git commit -F <that file>` after `git add` of the three files, one `git add` per call.

Title: `Correct four statements the DEPTH review left open`

Body (say why): the KDoc and spec §2.2 listed a one-shot's start as a place where `sineGain` is right at rest, and set "expected within about a dB" beside inferred figures several dB larger; a test comment gave every voice a negative-BLOOM branch in its breath although VOWEL's breath has none (read in `breathRatios`); spec §4 item 4 said `sineGain` "differs between those settings" with nothing for "those" to refer to. The final review of #431 found all four, they were parked there so that review's fix wave stayed closed, and they were promised for the roster's first change. Documentation and comments only; `:synth:compileTestKotlin` is green and no behaviour moves.

---

### Task 2: The stratified spread

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidates.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidatesTest.kt`

**Interfaces:**
- Consumes: `Glint.defaults(voice): Map<String, Float>`, `Glint.macrosFor(voice): List<MacroSpec>` (each `MacroSpec` has `name`), `GlintVoice.entries` (`SWEEP, STEP, BRASS, VOWEL`, in that order).
- Produces (Task 3 and the roster's second half rely on these exact names):
  - `GlintRosterCandidates.COUNT: Int` (16), `DEPTH_MAX: Float` (0.9f), `SEED: Long`
  - `class GlintRosterCandidates.Candidate(val number: Int, val macros: Map<String, Float>)` (`number` 1 to 16; `macros` holds every macro of the voice)
  - `GlintRosterCandidates.varied(voice: GlintVoice): List<String>`
  - `GlintRosterCandidates.forVoice(voice: GlintVoice): List<Candidate>`

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidatesTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2): a stratified spread, not a choice. */
class GlintRosterCandidatesTest {

    private fun top(name: String): Float = if (name == "DEPTH") GlintRosterCandidates.DEPTH_MAX else 1f

    /** Which of the sixteen evenly spaced levels, 0 to 15, candidate [c] has for [name]. */
    private fun levelOf(name: String, c: GlintRosterCandidates.Candidate): Int =
        Math.round(c.macros.getValue(name) / top(name) * 15f)

    @Test
    fun `sixteen candidates per voice, numbered 1 to 16, each setting every macro its voice has and no other`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            assertEquals((1..16).toList(), candidates.map { it.number }, "$voice: numbering")
            val names = Glint.macrosFor(voice).map { it.name }.toSet()
            for (c in candidates) {
                assertEquals(names, c.macros.keys, "$voice-${c.number}: the macros it sets")
                for ((name, value) in c.macros) {
                    assertTrue(value in 0f..1f, "$voice-${c.number}: $name is $value, outside 0..1")
                }
            }
        }
    }

    @Test
    fun `each varied macro takes each of its sixteen evenly spaced levels exactly once`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            for (name in GlintRosterCandidates.varied(voice)) {
                assertEquals((0..15).toList(), candidates.map { levelOf(name, it) }.sorted(), "$voice $name: the levels taken")
                for (c in candidates) {
                    val value = c.macros.getValue(name)
                    val want = levelOf(name, c) / 15f * top(name)
                    assertTrue(abs(value - want) < 1e-6f, "$voice-${c.number} $name is $value, not level ${levelOf(name, c)}/15 ($want)")
                }
            }
        }
    }

    @Test
    fun `DEPTH reaches 0 and 0_9 and never 1, which is the same bare sine on every voice`() {
        for (voice in GlintVoice.entries) {
            val depths = GlintRosterCandidates.forVoice(voice).map { it.macros.getValue("DEPTH") }
            assertTrue(depths.max() <= 0.9f + 1e-6f, "$voice: DEPTH reaches ${depths.max()}")
            assertTrue(depths.min() == 0f, "$voice: DEPTH never reaches 0, its lowest is ${depths.min()}")
        }
    }

    @Test
    fun `TUNE, DECAY and every macro that is not varied stay at their defaults`() {
        for (voice in GlintVoice.entries) {
            val defaults = Glint.defaults(voice)
            val varied = GlintRosterCandidates.varied(voice)
            assertTrue("TUNE" !in varied && "DECAY" !in varied, "$voice: TUNE and DECAY are not to be varied")
            for (c in GlintRosterCandidates.forVoice(voice)) {
                for ((name, value) in c.macros) {
                    if (name !in varied) assertEquals(defaults.getValue(name), value, "$voice-${c.number}: $name left its default")
                }
            }
        }
    }

    @Test
    fun `the varied macros are the spec's, and each is one its voice has`() {
        for (voice in GlintVoice.entries) {
            val want = if (voice == GlintVoice.VOWEL) listOf("PEAK", "BODY", "BLOOM", "DEPTH")
            else listOf("PEAK", "FOLLOW", "BODY", "BLOOM", "DEPTH")
            assertEquals(want, GlintRosterCandidates.varied(voice), "$voice: the varied macros")
            val has = Glint.macrosFor(voice).map { it.name }
            assertTrue(want.all { it in has }, "$voice: a varied macro that the voice does not have")
        }
    }

    @Test
    fun `the macros are shuffled against one another, not stepped in lockstep`() {
        for (voice in GlintVoice.entries) {
            val candidates = GlintRosterCandidates.forVoice(voice)
            val names = GlintRosterCandidates.varied(voice)
            val levels = names.associateWith { name -> candidates.map { levelOf(name, it) } }
            for ((name, seq) in levels) {
                assertTrue(seq != seq.sorted() && seq != seq.sortedDescending(), "$voice $name: its levels are in order, not shuffled")
            }
            for (a in names.indices) {
                for (b in a + 1 until names.size) {
                    assertTrue(levels.getValue(names[a]) != levels.getValue(names[b]), "$voice: ${names[a]} and ${names[b]} take the same level at every candidate")
                }
            }
        }
    }

    @Test
    fun `a voice draws the same twice, and voices that share their macros draw differently`() {
        val sweep = GlintRosterCandidates.forVoice(GlintVoice.SWEEP).map { it.macros }
        assertEquals(sweep, GlintRosterCandidates.forVoice(GlintVoice.SWEEP).map { it.macros }, "SWEEP: a second draw differs from the first")
        for (other in listOf(GlintVoice.STEP, GlintVoice.BRASS)) {
            assertTrue(sweep != GlintRosterCandidates.forVoice(other).map { it.macros }, "$other draws exactly as SWEEP does")
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterCandidatesTest'
```

Expected: the build fails in `:synth:compileTestKotlin` with `Unresolved reference` for `GlintRosterCandidates`.

- [ ] **Step 3: Write the spread**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterCandidates.kt`:

```kotlin
package com.snipsnap.synth

import java.util.Random

/**
 * The GLINT factory roster's candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2):
 * sixteen per voice, whose values are a stratified spread and not a choice. Each varied macro takes sixteen
 * evenly spaced levels, `i / 15`, exactly once across the sixteen candidates, in an order shuffled per macro
 * by a recorded seed, so both ends of every range are heard, the ugly end included. DEPTH's levels are scaled
 * to 0 through [DEPTH_MAX], never 1: full DEPTH is the same bare sine on every voice. Nothing here comes from
 * anyone's taste. TUNE is not varied (a pad overrides it per zone) and neither is DECAY (a held pad ignores
 * it); both stay at their defaults, as does any macro this does not name.
 *
 * The owner hears the clips ([GlintRosterAuditionGenerator]) and replies with the numbers to keep; those
 * candidates' values become the roster. The numbering is therefore a contract with whatever was sent: change
 * [SEED], the draw or the order of [varied], and "SWEEP-07" names a different sound.
 */
internal object GlintRosterCandidates {

    const val COUNT = 16

    /** DEPTH's top level. 1 is a bare sine on every voice, so it would be the same candidate four times. */
    const val DEPTH_MAX = 0.9f

    /**
     * The recorded seed, the date the spread was drawn. A voice draws from `Random(SEED + voice.ordinal)`, one
     * shuffle per macro in [varied]'s order, so the voices that share their macros still draw differently.
     */
    const val SEED = 20261002L

    class Candidate(val number: Int, val macros: Map<String, Float>)

    /** The macros whose levels are spread, in the order their shuffles are drawn. VOWEL has no FOLLOW. */
    fun varied(voice: GlintVoice): List<String> =
        if (voice == GlintVoice.VOWEL) listOf("PEAK", "BODY", "BLOOM", "DEPTH")
        else listOf("PEAK", "FOLLOW", "BODY", "BLOOM", "DEPTH")

    /** The sixteen candidates for [voice], numbered 1 to [COUNT]. Every macro of the voice is set. */
    fun forVoice(voice: GlintVoice): List<Candidate> {
        val random = Random(SEED + voice.ordinal)
        val orders = varied(voice).associateWith { shuffled(random) }
        val defaults = Glint.defaults(voice)
        return (0 until COUNT).map { i ->
            val macros = defaults.toMutableMap()
            for ((name, order) in orders) {
                val level = order[i] / (COUNT - 1).toFloat()
                macros[name] = if (name == "DEPTH") level * DEPTH_MAX else level
            }
            Candidate(i + 1, macros)
        }
    }

    /**
     * Fisher-Yates over 0 until [COUNT], from [random] alone. `java.util.Random`'s sequence is specified, so a
     * seed means the same order on every JVM; the stdlib's own shuffle makes no such promise.
     */
    private fun shuffled(random: Random): IntArray {
        val order = IntArray(COUNT) { it }
        for (i in COUNT - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val held = order[i]
            order[i] = order[j]
            order[j] = held
        }
        return order
    }
}
```

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterCandidatesTest'
```

Expected: exit code 0, and `synth/build/test-results/test/TEST-com.snipsnap.synth.GlintRosterCandidatesTest.xml` has seven `<testcase` elements, no `<failure`, no `<error`.

**If the "shuffled against one another" test fails on `SEED`** (a macro whose levels come out in order, or two macros in lockstep: odds of about 1e-12 per macro, so a failure means a bug, not bad luck), fix the code. Do not tune `SEED` to taste; the seed is the date and stays the date.

- [ ] **Step 5: Prove the guards, each alone**

Make one change at a time, run the same command, confirm the named test fails with its message, then restore that line by hand with Edit (the files are not committed yet, so there is nothing for git to restore from):

1. In `shuffled`, replace `val j = random.nextInt(i + 1)` with `val j = i`. Expected: `the macros are shuffled against one another` fails ("its levels are in order, not shuffled").
2. Set `DEPTH_MAX = 1f`. Expected: `DEPTH reaches 0 and 0_9 and never 1` fails ("DEPTH reaches 1.0").
3. In `forVoice`, change `Random(SEED + voice.ordinal)` to `Random(SEED)`. Expected: `a voice draws the same twice, and voices that share their macros draw differently` fails ("STEP draws exactly as SWEEP does").
4. In `varied`, change `"FOLLOW"` to `"FOLLOWS"`. Expected: `the varied macros are the spec's` fails, and so does `sixteen candidates per voice` ("the macros it sets": the extra name appears).

Restore all four, re-run Step 4's command once, expect exit code 0.

- [ ] **Step 6: Commit**

`git add` the two files, one per call. Title: `Spread the roster's candidates by a recorded seed, not by choice`. Body: spec §6.2 says no roster value comes from Claude's taste, so the sixteen candidates per voice are a stratified spread: each varied macro takes its sixteen evenly spaced levels once, shuffled per macro, so the ugly end of every range is heard and DEPTH stops at 0.9 (1.0 is the same bare sine on every voice). `java.util.Random` rather than the stdlib shuffle because its sequence is specified and a seed has to keep meaning the same order, since the owner will answer by number. The seed is the date and was not tuned. The tests pin the spec's rules and each was shown to fail when its guard was undone (the shuffle, DEPTH's ceiling, the per-voice seed, the macro names); `Keys.glintPad` silently drops a macro name a voice does not have, so a misspelt name would otherwise render a sound that does not vary.

---

### Task 3: The clips, the manifest and the Gradle task

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGenerator.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGeneratorTest.kt`
- Modify: `synth/build.gradle.kts` (after the `generateGlintDepthAudition` block, around line 99)
- Modify: `.gitignore` (after the `testkit/glint-depth-audition/` entry, around line 54)

**Interfaces:**
- Consumes: Task 2's `GlintRosterCandidates.forVoice`, `varied`, `Candidate`, `SEED`, `DEPTH_MAX`; `Keys.glintPadMidis(voice): List<Int>` (nine zones); `Keys.glintPad(voice, macros, midi): KeyNote` (renders the held pad, throws if the loop does not close); `GlintPathsAuditionGenerator.heldFor(note: KeyNote, seconds: Float): Snip` (internal: plays the onset, then the loop again, for at least `seconds` and always two loops plus half a second, so about ten seconds here); `AuditionLevel.level(snip: Snip): Snip` (internal: loudest-200 ms RMS to 0.03, peak guard 0.99); `WavWriter.write(file, snip, WavWriter.BitDepth.PCM_16)`; `Loudness.of(snip): Float`.
- Produces: `GlintRosterAuditionGenerator` with `main(args)`, and these internals used by its test: `prepare(dir: File)`, `renderVoice(voice: GlintVoice, dir: File): List<String>` (writes the voice's sixteen clips, returns their manifest lines), `clipName(voice, number): String`, `clipFor(voice, candidate): Snip`, `manifestLine(voice, candidate): String`; the Gradle task `generateGlintRosterAudition`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGeneratorTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The roster candidates' clips (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2): what is sent must be what the manifest says. */
class GlintRosterAuditionGeneratorTest {

    @Test
    fun `a clip is five seconds, ends at silence, is finite, and sits at the audition level`() {
        for (voice in GlintVoice.entries) {
            val clip = GlintRosterAuditionGenerator.clipFor(voice, GlintRosterCandidates.forVoice(voice).first())
            assertEquals(5 * clip.sampleRate, clip.frameCount, "$voice: length in frames")
            assertTrue(clip.samples.all { it.isFinite() }, "$voice: a sample is not finite")
            assertTrue(abs(clip.samples.last()) < 1e-6f, "$voice: the clip ends at ${clip.samples.last()}, not at zero")
            val peak = clip.samples.maxOf { abs(it) }
            assertTrue(peak <= 0.99f, "$voice: peak $peak is over the audition guard")
            val loudness = Loudness.of(clip)
            assertTrue(abs(loudness - 0.03f) < 5e-4f, "$voice: loudness $loudness is not the audition level 0.03")
        }
    }

    @Test
    fun `a voice's sixteen clips and its manifest lines agree on their numbers and values`() {
        val dir = Files.createTempDirectory("glint-roster").toFile()
        try {
            for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
                val lines = GlintRosterAuditionGenerator.renderVoice(voice, dir)
                assertEquals(16, lines.size, "$voice: manifest lines")
                for (c in GlintRosterCandidates.forVoice(voice)) {
                    val name = "%s-%02d".format(Locale.ROOT, voice.name, c.number)
                    assertEquals("$name.wav", GlintRosterAuditionGenerator.clipName(voice, c.number), "$name: the clip's file name")
                    assertTrue(File(dir, "$name.wav").length() > 44, "$name.wav was not written")
                    val tokens = lines[c.number - 1].trim().split(Regex("\\s+"))
                    assertEquals(name, tokens.first(), "$name: the manifest line's own name")
                    val shown = tokens.drop(1).associate { it.substringBefore('=') to it.substringAfter('=').toFloat() }
                    assertEquals(GlintRosterCandidates.varied(voice).toSet(), shown.keys, "$name: the macros the line lists")
                    for ((macro, value) in shown) {
                        val real = c.macros.getValue(macro)
                        assertTrue(abs(value - real) < 5e-4f, "$name: $macro reads $value in the manifest and is $real in the candidate")
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preparing the folder removes an earlier draw's clips and manifest and nothing else`() {
        val dir = Files.createTempDirectory("glint-roster").toFile()
        try {
            val stale = listOf("SWEEP-01.wav", "VOWEL-16.wav", "manifest.txt")
            // Each kept name differs from every stale one by more than case: macOS's file system is
            // case-insensitive, so "sweep-01.wav" would be the very file "SWEEP-01.wav".
            val keep = listOf("notes.txt", "SWEEP-01.mp3", "sweep-02.wav", "SWEEP-1.wav")
            for (name in stale + keep) File(dir, name).writeText("x")
            GlintRosterAuditionGenerator.prepare(dir)
            for (name in stale) assertTrue(!File(dir, name).exists(), "$name was left behind")
            for (name in keep) assertTrue(File(dir, name).exists(), "$name was deleted, and it is not this tool's")
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterAuditionGeneratorTest'
```

Expected: the build fails in `:synth:compileTestKotlin` with `Unresolved reference` for `GlintRosterAuditionGenerator`.

- [ ] **Step 3: Write the generator**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintRosterAuditionGenerator.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * The GLINT roster audition (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2):
 * [GlintRosterCandidates]' sixteen candidates per voice, each rendered by the engine as a held pad on one zone
 * near the middle of the keyboard (the sound MAKE INSTRUMENT gives), five seconds long, levelled by
 * [AuditionLevel] and named `<VOICE>-01` to `<VOICE>-16`, for the owner to hear on his phone and answer with
 * the numbers to keep. A clip carries no settings, so nothing is heard through a number; `manifest.txt` beside
 * the clips says what each one is, for whoever wires the picks, and is not sent. Not a test - run with
 * `generateGlintRosterAudition`.
 */
object GlintRosterAuditionGenerator {

    /** The zone is the fifth of [Keys.glintPadMidis]' nine: an octave above the voice's lowest key. */
    private const val ZONE = 4

    /** The spec's "5 s each". [GlintPathsAuditionGenerator.heldFor] plays longer, so the clip is cut. */
    private const val SECONDS = 5f

    /** Long enough that the cut is no click, short enough to leave the pad's body alone. */
    private const val FADE_SECONDS = 0.05f

    private const val MANIFEST = "manifest.txt"
    private val CLIP_FILE = Regex("[A-Z]+-\\d\\d\\.wav")

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-roster-candidates")
        prepare(dir)
        val lines = GlintVoice.entries.flatMap { renderVoice(it, dir) }
        File(dir, MANIFEST).writeText(manifestHeader() + lines.joinToString("\n", postfix = "\n"))
        println("wrote ${lines.size} clips and $MANIFEST to ${dir.absolutePath}")
    }

    /**
     * Makes the folder and empties it of this tool's own files, so a rerun cannot leave an earlier draw's clip
     * beside the new one. Only `<VOICE>-NN.wav` and `manifest.txt` go; anything else in the folder stays.
     */
    internal fun prepare(dir: File) {
        dir.mkdirs()
        dir.listFiles { f -> f.isFile && (f.name == MANIFEST || CLIP_FILE.matches(f.name)) }?.forEach { it.delete() }
    }

    /** Writes [voice]'s sixteen clips into [dir] and returns their manifest lines, in clip order. */
    internal fun renderVoice(voice: GlintVoice, dir: File): List<String> =
        GlintRosterCandidates.forVoice(voice).map { c ->
            WavWriter.write(File(dir, clipName(voice, c.number)), clipFor(voice, c), WavWriter.BitDepth.PCM_16)
            manifestLine(voice, c)
        }

    internal fun clipName(voice: GlintVoice, number: Int): String =
        String.format(Locale.ROOT, "%s-%02d.wav", voice.name, number)

    /**
     * The candidate's held pad as the owner hears it: the first [SECONDS] of the key's onset and breaths, the
     * last [FADE_SECONDS] faded to nothing (the held render runs on, so the cut lands mid-waveform), then
     * levelled. The fade comes first; the level is one gain, so the order does not change the loudness.
     */
    internal fun clipFor(voice: GlintVoice, candidate: GlintRosterCandidates.Candidate): Snip {
        val note = Keys.glintPad(voice, candidate.macros, Keys.glintPadMidis(voice)[ZONE])
        val played = GlintPathsAuditionGenerator.heldFor(note, SECONDS)
        val samples = played.samples.copyOf((SECONDS * played.sampleRate).toInt())
        val fade = (FADE_SECONDS * played.sampleRate).toInt()
        for (i in 0 until fade) {
            val gain = 0.5 * (1.0 + cos(PI * (i + 1) / fade))
            val at = samples.size - fade + i
            samples[at] = (samples[at] * gain).toFloat()
        }
        return AuditionLevel.level(Snip(samples, channels = 1, sampleRate = played.sampleRate))
    }

    /** `SWEEP-01  PEAK=0.800 FOLLOW=0.133 ...`: the clip's name, then each varied macro to three places, `.` as the point whatever the locale. */
    internal fun manifestLine(voice: GlintVoice, candidate: GlintRosterCandidates.Candidate): String =
        clipName(voice, candidate.number).removeSuffix(".wav") + "  " +
            GlintRosterCandidates.varied(voice).joinToString(" ") {
                String.format(Locale.ROOT, "%s=%.3f", it, candidate.macros.getValue(it))
            }

    private fun manifestHeader(): String = buildString {
        appendLine("GLINT roster candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2)")
        appendLine("seed ${GlintRosterCandidates.SEED} plus the voice's ordinal; DEPTH spans 0 to ${GlintRosterCandidates.DEPTH_MAX}; TUNE and DECAY at their defaults")
        appendLine("zone MIDI: " + GlintVoice.entries.joinToString(", ") { "${it.name} ${Keys.glintPadMidis(it)[ZONE]}" })
        appendLine()
    }
}
```

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.GlintRosterAuditionGeneratorTest'
```

Expected: exit code 0, and `synth/build/test-results/test/TEST-com.snipsnap.synth.GlintRosterAuditionGeneratorTest.xml` has three `<testcase` elements, no `<failure`, no `<error`.

- [ ] **Step 5: Prove the guards, each alone**

One change at a time, same command, confirm the named test fails with its message, restore by hand, and re-run once at the end for green:

1. Delete the `for (i in 0 until fade) { … }` loop in `clipFor`. Expected: `a clip is five seconds, ends at silence` fails ("the clip ends at … not at zero").
2. In `prepare`, change the filter to `f.isFile && f.name.endsWith(".wav")`. Expected: `preparing the folder removes … nothing else` fails ("SWEEP-1.wav was deleted, and it is not this tool's").
3. In `manifestLine`, change `candidate.number` to `candidate.number + 1`. Expected: `a voice's sixteen clips and its manifest lines agree` fails ("the manifest line's own name").
4. In `clipFor`, replace `AuditionLevel.level(Snip(…))` by returning the `Snip(…)` unlevelled. Expected: `a clip is five seconds` fails ("is not the audition level 0.03").

- [ ] **Step 6: Add the Gradle task and the ignore entry**

In `synth/build.gradle.kts`, after the `generateGlintDepthAudition` block and its blank line, add:

```kotlin
/** Render the GLINT roster candidates (16 held pads per voice, WAVs and a manifest) under testkit/glint-roster-candidates/. See GlintRosterAuditionGenerator. */
tasks.register<JavaExec>("generateGlintRosterAudition") {
    group = "distribution"
    description = "Render the GLINT roster candidates (16 held pads per voice) under testkit/glint-roster-candidates/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintRosterAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-roster-candidates")
}
```

In `.gitignore`, after the `testkit/glint-depth-audition/` line and its blank line, add:

```
# The GLINT roster candidates (sixteen held pads per voice and their manifest), rendered by
# :synth:generateGlintRosterAudition and sent to the phone for the owner to pick from; never committed.
testkit/glint-roster-candidates/
```

- [ ] **Step 7: Commit**

`git add` the four files (generator, test, `synth/build.gradle.kts`, `.gitignore`), one per call. Title: `Render the roster candidates as five-second held pads, with a manifest`. Body: the owner picks the factory roster by ear from sixteen candidates per voice, so the clips must be exactly what the manifest says and nothing else may be in the folder. A clip is the engine's own pad path (`Keys.glintPad`, the sound MAKE INSTRUMENT gives) at the fifth of the nine zones, cut to the spec's five seconds with a 50 ms fade (the played-out render is about ten seconds, so the cut lands mid-waveform and clicks without it) and levelled by `AuditionLevel` so loudness cannot decide a pick. Clips carry no settings, so nothing is heard through a number; the manifest, which is not sent, says what each is, printed with `Locale.ROOT` so a comma-locale machine cannot write `0,800`. A rerun removes only this tool's own file names first, because a stale `SWEEP-12.wav` from an earlier draw would sit beside the new set and name the wrong sound. Tests pin the clip's length, fade and level, the agreement of file names, manifest lines and candidates, and the folder cleaning; each was shown to fail when its guard was undone.

---

### Task 4: Render the 64 clips and check them (controller step)

Not a builder task: the controller runs it, because what comes out goes to the owner's phone and is the thing he judges.

**Files:** none tracked. Output goes to `testkit/glint-roster-candidates/`, which Task 3 git-ignores.

- [ ] **Step 1: Render**

```
./gradlew --no-daemon :synth:generateGlintRosterAudition
```

Expected: exit code 0, and the last line `wrote 64 clips and manifest.txt to …/testkit/glint-roster-candidates`. If it throws, it is `Keys.glintPad` refusing a candidate (a loop that does not close, or a non-finite render): stop, report which voice and number (its settings are `GlintRosterCandidates.forVoice(voice)[number - 1].macros`) and do not alter the spread. That is an engine finding for the owner, not something to route around.

- [ ] **Step 2: Check the folder**

```
ls testkit/glint-roster-candidates
```

Expected: `BRASS-01.wav` to `BRASS-16.wav`, `STEP-01.wav` to `STEP-16.wav`, `SWEEP-01.wav` to `SWEEP-16.wav`, `VOWEL-01.wav` to `VOWEL-16.wav` and `manifest.txt`: 65 entries. Then `git status --short` must print nothing for `testkit/` (the ignore entry works).

- [ ] **Step 3: Check the audio**

Write `check_roster.py` in the scratchpad directory (not the repo) that opens each WAV with the `wave` module and prints, per file, frames, channels, sample width, peak (as a fraction of full scale) and the RMS of the loudest 200 ms; then run it with `python3`. Expected for all 64: 220500 frames, 1 channel, 2-byte samples, peak at most 0.99, loudest-200 ms RMS within 10% of 0.03 (the script's window search is not `Loudness.of`'s own, so this is a sanity bound; the unit test holds the exact figure on each voice's first candidate). Report any clip outside those bounds by name; do not drop or alter a clip.

The same script also prints each clip's **phone-band level**: the RMS through a 300 Hz second-order Butterworth high-pass, in dB against the clip's whole-band RMS (numpy is installed, scipy is not: take `numpy.fft.rfft` of the clip and weight each bin's power by `1 / (1 + (300 / f)^4)`, which is that filter's power response; by Parseval the weighted sum is the filtered signal's power). Save it to the scratchpad as a table beside the manifest, not into the repo. Why: every clip is levelled by whole-band loudness (the spec's rule), but a phone speaker plays little below about 300 Hz, and the SWEEP, BRASS and VOWEL clips sit at 220 Hz while every clip above DEPTH 0.5 is a quarter to two thirds plain sine at that pitch. A clip that is 12 dB down in the phone band will sound faint and dull on a speaker whatever its timbre. The table lets the controller tell, when the picks come back, whether they lean away from high DEPTH because of level or because of taste. It changes no clip and no pick.

- [ ] **Step 4: Read the manifest once**

`sed -n 1,25p testkit/glint-roster-candidates/manifest.txt`. Expected: the three header lines, then `SWEEP-01  PEAK=… FOLLOW=… BODY=… BLOOM=… DEPTH=…`. Confirm that each varied macro of one voice shows 0.000 and 1.000 once (DEPTH: 0.000 and 0.900) by reading its sixteen lines. This is a look, not a judgment of the values.

- [ ] **Step 5: Send, by voice**

Send each voice's sixteen clips to the owner's phone (`SendUserFile`), one voice per batch, with a message of this shape and no settings in it:

> SWEEP 01-16, 5 s each, levelled alike. Reply with the numbers you'd keep. Earbuds if you can: a phone speaker hides the soft low ones.

("Levelled alike", not "same loudness": equal flat level is not equal loudness on a phone speaker, which is the point of the phone-band table above.)

If he keeps more than eight on a voice, ask him to cut to eight; if fewer than eight, a second round of variants near his keeps is rendered for that voice (spec §6.2.3). Record his replies in the scratchpad as he gives them, per voice.

---

## Where this plan stops

The owner's picks are the next input. Then, in a second plan written from them (spec §7): names proposed from the settings (uppercase, at most 14 characters, one or two plain words, unique per voice, clear of the trademark blocklist) for him to veto; `GlintPresets.kt` with the exact helper line `private fun p(voice: GlintVoice, name: String, vararg macros: Pair<String, Float>)`, eight per voice, every macro set, TUNE and DECAY at their defaults and flagged; `Presets.forVoice` and `Presets.all`; the hand-listed engine lists in `PresetsTest`; `GlintPresetsTest` (spec §6.3); the preset-seeded `Glint.scramble`; the final listen of the 32 as pads and as one-shots; the full suite; and the PR.

Two inputs for that plan, so they are not lost. **Provenance:** record each pick as a candidate number, and let `GlintPresetsTest` assert that every preset's varied macros equal `GlintRosterCandidates.forVoice(voice)[number - 1].macros` within the rounding the file uses (4 places is enough; the manifest's 3-place text is not the source). That proves no value came from Claude's taste and catches a mis-wired number; TUNE and DECAY are excluded because the final listen may move them. **FOLLOW** is key tracking and one zone cannot hear what it does across the keyboard, so the final listen plays each pick at a low, a middle and a top zone as well as the middle one these clips use.
