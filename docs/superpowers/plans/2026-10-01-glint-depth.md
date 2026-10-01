# GLINT DEPTH Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GLINT's DEPTH macro (a trailing macro, default 0, on every voice) that rounds the window's edge over the lower half of its range and fades a loudness-matched plain sine in over the upper half, with DEPTH 0 bit for bit today's sound.

**Architecture:** One small immutable-per-render class, `GlintShape`, owns the rounded window (a per-render table), the equal-power weights and the sine's gain. The waveform is computed in exactly two places (`Glint.synthesize`, `GlintHeld.render`); each keeps its own literal DEPTH-0 lines behind `if (shape == null)`, because the two associate their multiplies differently and no shared helper can reproduce both. A frozen copy of today's two loops, compared bit for bit, is the first task and the guard for every later one. `GlintPath.sineGain()` computes the sine's amplitude by numerical integration at the path's resting ratios.

**Tech Stack:** Kotlin/JVM 17 in the `:synth` module (tests with `kotlin.test` on JUnit), Gradle. The `:app` slider appears with no app code.

**Spec:** `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md` (PR 1 only: its sections 2 to 5 and 7; the roster in section 6 gets its own plan after PR 1 merges). Read it before Task 1; this plan argues from it.

## Global Constraints

- **Name and shape of the macro:** `DEPTH`, last on every voice (path voices seven macros, VOWEL six), default 0, range 0 to 1, neutral 0 (the `MacroSpec` default). `MacroSpec("DEPTH", 0f)` is appended in both branches of `Glint.macrosFor`.
- **DEPTH 0 is today's sound, bit for bit,** on every path: one-shot, held pad, all four voices, a patch saved without the key, an explicit 0. Each render site keeps its literal expression behind `if (shape == null)`. Never add zero terms: a `-0f` sample plus a `+0f` product becomes `+0f`, and `FloatArray.contentEquals` tells them apart. `2.0 * PI * k[0] * phase` stays exactly as written.
- **The law:** `e = 2·DEPTH` and `u = 0` for DEPTH up to 0.5; above it `e = 1` and `u = 2·DEPTH − 1`. Rounded window for `e > 0`: `w_e(p) = a(p)·(1 − p)^(1 + e)`, `a(p) = ½·(1 − cos(π·min(1, p/(½·e))))`. Output on the shaped path: `burstWeight·(amp·burst + env2·second) + sineWeight·amp·sin(2π·phase)`, `burstWeight = sqrt(1 − u²)` (exactly 1 while `u = 0`), `sineWeight = u·sineGain` (exactly 0 while `u = 0`, and then the sine term is skipped, not zeroed). At `u = 1` the output is a bare sine at f0.
- **`GlintShape.of` returns null at DEPTH 0** and non-null for any DEPTH above 0, however small. The window is a per-render table of `(1 − p)^(1 + e)`: 4096 intervals, `table[4096] = 0f` exactly, linear interpolation, indexed by phase and never by a sample counter, with the attack `a(p)` analytic and run only while `p < ½·e`. Built in `Double`, stored as `Float`, per render, never shared.
- **`sineGain` is `√2·rB`,** `rB` the RMS over one cycle of the fully rounded burst pair `w₁(p)·(sin(2π·k0·p) + level2·sin(2π·k1·p))` at the path's resting ratios (`GlintPath.breathRatios(0f, …)`), by numerical integration (2048 midpoints) inside `GlintPath`.
- **Do not touch:** `Dsp.*` (shared by every engine), `Velocity`, `Patches`, `GlintPatch`, and anything in `:cli`, `:kit`, `:loop`, `:json`, `:xpm`, `:mpc3`, `:audio`. No loudness-compensation gain is added (the engine's own levelling cancels it).
- **Commits:** plain declarative title (no `feat:`/`fix:` prefix, no ticket number), a body that says why (including what was tried and abandoned), then exactly these two trailer lines:
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XdQuyqmnaSqRVPTo87MNUr
  ```
  No model identifier anywhere else: not in a commit body, a PR text or a code comment. The branch is `claude/glint-depth-macro`. Never push, open a PR, approve or merge; the controller asks the owner first.
- **Worktree and tool rules:** work from `/Users/joshuacramblet/SnipSnap/.claude/worktrees/glint-depth-d1` and never `cd` elsewhere. Multi-line heredocs and compound shell commands (`&&`, `;`, `cd … &&`) are refused here: use the Write and Edit tools for files, run git and Gradle as plain single commands, and commit with `git commit -F <message file>` (write the file with the Write tool under the session scratchpad directory the dispatch names).
- **Gradle:** one invocation at a time, never two. Run it in the foreground with a 600000 ms timeout. Gate on the exit code AND on the result XMLs under `synth/build/test-results/test/` (no `<failure`, no `<error`); never grep the console for "FAILED"; never `-x :app:test`. `:app` joins the build only with `ANDROID_HOME=/Users/joshuacramblet/Library/Android/sdk` on the command line (nothing written to a file); without it `:app` is simply absent. A single class: `./gradlew :synth:test --tests 'com.snipsnap.synth.<Class>' --console=plain`. The whole GLINT family: `--tests 'com.snipsnap.synth.Glint*'`.
- **Claims:** a guard is proven by reverting it alone and watching a test fail with a message that names the guard. Report a number you measured as measured and one you inferred as inferred.

## Review Focus

The inputs most likely to bite, each pinned by a named test:

1. **DEPTH 0 as `0f`, as an omitted key, and in a patch saved before DEPTH existed** must all be the same bits as the frozen copy (a `-0f` sample turning `+0f` is the failure). Pinned by `GlintFrozenReferenceTest` (Task 1).
2. **VOWEL at the top of TUNE with PEAK 0,** where F1 pins to k = 1 and the burst and the sine are in phase at f0: bounded, starts from exactly zero, loop closes. Pinned in `GlintDepthTest` (Task 6) and `GlintDepthHeldTest` (Task 5).
3. **A tiny DEPTH (1e-9)** must run the shaped path and change the render by almost nothing. Pinned in `GlintDepthTest` (Task 6).
4. **DECAY 0 and 1, PEAK 0 and 1 (k up to 40) at DEPTH above 0:** `sineGain` finite and sensible, audio clean. Pinned by `GlintSineGainTest` (Task 3) and `GlintDepthTest` (Task 4).
5. **STEP and BRASS held pads at DEPTH above 0** (STEP's onset length moves the loop's start; BRASS's level breathes and the sine rides it): the loop still closes exactly. Pinned by `GlintDepthHeldTest` (Task 5).

---

## File Structure

**Create**
- `synth/src/main/kotlin/com/snipsnap/synth/GlintShape.kt`: the rounded window, the weights, the table. One responsibility: what DEPTH does to one render.
- `synth/src/test/kotlin/com/snipsnap/synth/LegacyGlint.kt`: frozen copies of today's one-shot and held render code.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintFrozenReferenceTest.kt`: DEPTH 0 against the frozen copies.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintShapeTest.kt`
- `synth/src/test/kotlin/com/snipsnap/synth/GlintSineGainTest.kt`
- `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt`: the one-shot, the law, the edge cases.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt`: the held loop.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthLoudnessTest.kt`: the level regression guard.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthAuditionGenerator.kt`: the real-engine clips for the owner's listen.

**Modify**
- `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`: `macrosFor` (both branches), the one-shot loop in `synthesize`, two KDoc blocks.
- `synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt`: the inner loop, one KDoc paragraph.
- `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt`: `sineGain()`.
- `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt` (the all-ones corner, the macro-list test), `GlintVowelTest.kt` (the macro-list test), `GlintPathsAuditionGenerator.kt` (one visibility word).
- `synth/build.gradle.kts` (one task), `.gitignore` (one entry).
- `docs/SYNTH_ROADMAP.md` (the S10 row), `docs/superpowers/specs/2026-09-29-glint-paths-design.md` (§6, one sentence).

**Delete (untracked, never committed):** `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthProbe.kt`, the throwaway listening probe.

---

### Task 1: The frozen reference, before anything else moves

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/LegacyGlint.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintFrozenReferenceTest.kt`
- Delete (untracked): `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthProbe.kt`

**Interfaces:**
- Consumes (production, unchanged): `Glint.synthesize(voice, macros, rate): FloatArray`, `Glint.render(voice, macros): Snip`, `GlintHeld.render(voice, macros): GlintHeld.Held`, `GlintHeld.breathPlan`, `GlintPath.of`, `Dsp.*`.
- Produces: `LegacyGlint.synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray`, `LegacyGlint.render(voice, macros): FloatArray`, `LegacyGlint.held(voice, macros): GlintHeld.Held`. Every later task's DEPTH-0 claim rests on these.

No GLINT golden exists today: every identity test compares two renders from one build, so nothing detects a change against the base. This task adds one before the engine is touched. The repo's own pattern for it is `LegacyPluckLoop.kt` with `StringsTest.kt`. A frozen copy compared on the same JVM, not a stored hash, because Java does not guarantee `Math.sin` is bit-identical across CPUs.

- [ ] **Step 1: Move the throwaway probe out of the tree**

It is an untracked live `@Test` class that writes clips outside the repo; a full test run would execute it. Move it to the session scratchpad directory the dispatch names (it is never committed):

Run: `mv synth/src/test/kotlin/com/snipsnap/synth/GlintDepthProbe.kt <scratchpad>/GlintDepthProbe.kt.txt`
Run: `git status --short`
Expected: no output (a clean tree).

- [ ] **Step 2: Write the frozen copy**

Create `synth/src/test/kotlin/com/snipsnap/synth/LegacyGlint.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * GLINT's two render loops exactly as they stood before DEPTH existed (the
 * default branch at b5b8f5ae, 2026-10-01), frozen here so nothing can change
 * what DEPTH 0 sounds like without a test saying so. [GlintFrozenReferenceTest]
 * holds the production code to them, sample for sample.
 *
 * Copied, not shared. The window is this file's own `1f - phase`, not
 * [Glint.windowAt], so an edit to `windowAt` is caught as well. What the loops
 * call that DEPTH does not touch ([GlintPath], [Dsp], [GlintHeld.breathPlan])
 * is production code. Cancellation is left out: it never changes a sample.
 */
internal object LegacyGlint {
    private const val ATTACK_SECONDS = 0.002f

    private fun window(phase: Float): Float = 1f - phase.coerceIn(0f, 1f)

    /** `Glint.synthesize` as it was. */
    fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)
        val path = GlintPath.of(voice, m, f0)
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val env2 = if (voice == GlintVoice.VOWEL) {
            amp
        } else {
            Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60 * Glint.BODY_DECAY_RATIO)
        }
        fun x(t: Float): Float = if (voice == GlintVoice.BRASS) amp.at(t) else Dsp.envAt(t, Glint.BLOOM_T60)
        fun rung(t: Float): Int = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
        val k = FloatArray(2)
        path.ratios(1f, rung(0f), k)
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = window(phase.toFloat())
            val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
            val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            out[i] = amp.at(t) * burst + env2.at(t) * second
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                path.ratios(x(t), rung(t), k)
            }
        }
        return out
    }

    /** `Glint.render` as it was: the one-shot at four times oversampling, decimated, levelled, faded. */
    fun render(voice: GlintVoice, macros: Map<String, Float>): FloatArray {
        val raw = synthesize(voice, macros, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    /** `GlintHeld.render` as it was (without its cancellation polling). */
    fun held(voice: GlintVoice, macros: Map<String, Float>): GlintHeld.Held {
        val m = Glint.defaults(voice) + macros
        val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
        val os = Dsp.OVERSAMPLE
        val rate = Dsp.RATE * os
        val loopOs = plan.loopFrames.toLong() * os
        val n = plan.cycles.toLong()
        val path = GlintPath.of(voice, m, plan.f0.toFloat())
        val depth = abs(path.bloom)

        fun cycleOf(i: Long) = i * n / loopOs
        var w0 = ceil(path.onsetSeconds * rate).toLong().coerceAtLeast(1)
        while (cycleOf(w0) == cycleOf(w0 - 1)) w0++
        val i0 = (w0 + os - 1) / os * os
        val total = (i0 + 3 * loopOs).toInt()

        val out = FloatArray(total)
        val k = FloatArray(2)
        var amp = 0f
        var x = 1f
        var rung = -1
        var swing = 0f
        for (i in 0 until total) {
            val attack = (i.toFloat() / rate / ATTACK_SECONDS).coerceAtMost(1f)
            val breathing = i >= w0
            if (breathing) {
                val b = sin(2.0 * PI * Math.floorMod(i - i0, loopOs) / loopOs).toFloat()
                if (voice == GlintVoice.BRASS) {
                    amp = GlintHeld.BRASS_REST * (1f + GlintHeld.BREATHE_SHARE * depth * b)
                    swing = (amp - GlintHeld.BRASS_REST) / (1f - GlintHeld.BRASS_REST)
                } else {
                    amp = 1f
                    swing = GlintHeld.BREATHE_SHARE * b
                }
            } else {
                val t = i.toFloat() / rate
                val fall = Dsp.envAt(t, Glint.BLOOM_T60)
                x = fall
                amp = attack * (if (voice == GlintVoice.BRASS) GlintHeld.BRASS_REST + (1f - GlintHeld.BRASS_REST) * fall else 1f)
                rung = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
            }
            val phase = ((i.toLong() * n) % loopOs).toDouble() / loopOs
            if (i == 0 || cycleOf(i.toLong()) != cycleOf(i.toLong() - 1)) {
                if (breathing) path.breathRatios(swing, k) else path.ratios(x, rung, k)
            }
            val w = window(phase.toFloat())
            val second = if (voice == GlintVoice.VOWEL) amp else attack
            out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
        }

        val down = Dsp.decimate(out, Dsp.RATE)
        val loopStart = (i0 / os).toInt() + plan.loopFrames
        val audio = down.copyOfRange(0, loopStart + plan.loopFrames)
        level(audio, loopStart)
        return GlintHeld.Held(audio, loopStart)
    }

    private fun level(audio: FloatArray, loopStart: Int) {
        val loop = audio.copyOfRange(loopStart, audio.size)
        val probe = loop.copyOf()
        Dsp.levelTo(probe, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        var num = 0.0
        var den = 0.0
        for (i in loop.indices) { num += probe[i].toDouble() * loop[i]; den += loop[i].toDouble() * loop[i] }
        if (den <= 0.0) return
        var gain = (num / den).toFloat()
        val peak = audio.maxOf { abs(it) }
        if (peak * gain > 0.99f) gain = 0.99f / peak
        for (i in audio.indices) audio[i] *= gain
    }
}
```

- [ ] **Step 3: Write the test that holds production to it**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintFrozenReferenceTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * DEPTH 0 is today's sound, bit for bit
 * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §4.1).
 * [LegacyGlint] is the render code as it stood before DEPTH existed; every
 * case here must come out of the production code with the same raw bits,
 * `-0f` and `+0f` told apart.
 */
class GlintFrozenReferenceTest {

    private val corners: List<Pair<String, Map<String, Float>>> = listOf(
        "defaults" to emptyMap<String, Float>(),
        "bright corner" to mapOf("PEAK" to 1f, "TUNE" to 1f, "BLOOM" to 0.5f, "BODY" to 0f, "FOLLOW" to 1f),
        "still and long" to mapOf("BLOOM" to 0.5f, "DECAY" to 1f),
        "short, rising" to mapOf("DECAY" to 0f, "BLOOM" to 0f),
        "falling wide, low" to mapOf("BLOOM" to 1f, "PEAK" to 0f, "TUNE" to 0f),
    )

    /** The corners, plus all-zeros and all-ones over the macros the voice has, DEPTH left out (so it is 0). */
    private fun cornersFor(voice: GlintVoice): List<Pair<String, Map<String, Float>>> {
        val names = Glint.macrosFor(voice).map { it.name }.filter { it != "DEPTH" }
        return corners + listOf("all zeros" to names.associateWith { 0f }, "all ones" to names.associateWith { 1f })
    }

    private fun assertBitIdentical(expected: FloatArray, actual: FloatArray, what: String) {
        assertEquals(expected.size, actual.size, "$what: length")
        val first = expected.indices.firstOrNull { expected[it].toRawBits() != actual[it].toRawBits() }
        if (first != null) fail("$what: DEPTH 0 moved sample $first: frozen ${expected[first]}, now ${actual[first]}")
    }

    @Test
    fun `the one-shot loop is the frozen copy at DEPTH 0, at both render rates`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
                    assertBitIdentical(
                        LegacyGlint.synthesize(voice, macros, rate),
                        Glint.synthesize(voice, macros, rate),
                        "$voice $name at $rate",
                    )
                    cases++
                }
            }
        }
        assertEquals(4 * 7 * 2, cases)
    }

    @Test
    fun `render is the frozen copy at DEPTH 0, and so is a patch saved without DEPTH`() {
        for (voice in GlintVoice.entries) {
            for ((name, macros) in cornersFor(voice)) {
                val legacy = LegacyGlint.render(voice, macros)
                assertBitIdentical(legacy, Glint.render(voice, macros).samples, "$voice $name render")
                // A patch only holds macros its voice has (VOWEL has no FOLLOW).
                val saved = GlintPatch("Old", voice, macros.filterKeys { it in Glint.defaults(voice) })
                assertBitIdentical(legacy, saved.render().samples, "$voice $name, a patch saved without DEPTH")
            }
        }
    }

    @Test
    fun `the held loop is the frozen copy at DEPTH 0`() {
        var cases = 0
        for (voice in GlintVoice.entries) {
            for (tune in listOf(0f, 1f)) {
                for (bloom in listOf(0.5f, 0.85f)) {
                    val macros = mapOf("TUNE" to tune, "BLOOM" to bloom)
                    val legacy = LegacyGlint.held(voice, macros)
                    val now = GlintHeld.render(voice, macros)
                    assertEquals(legacy.loopStart, now.loopStart, "$voice TUNE $tune BLOOM $bloom: the loop marker moved")
                    assertBitIdentical(legacy.audio, now.audio, "$voice TUNE $tune BLOOM $bloom held")
                    cases++
                }
            }
        }
        assertEquals(16, cases)
    }

    @Test
    fun `an explicit DEPTH 0 and an omitted DEPTH are the same bits`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in GlintVoice.entries) {
            assertBitIdentical(
                Glint.synthesize(voice, emptyMap(), rate),
                Glint.synthesize(voice, mapOf("DEPTH" to 0f), rate),
                "$voice one-shot",
            )
            val omitted = GlintHeld.render(voice, emptyMap())
            val zero = GlintHeld.render(voice, mapOf("DEPTH" to 0f))
            assertEquals(omitted.loopStart, zero.loopStart, "$voice held loop marker")
            assertBitIdentical(omitted.audio, zero.audio, "$voice held")
        }
    }
}
```

- [ ] **Step 4: Run it: it must pass at this commit**

It compares the code with a copy of itself, so a pass is the baseline.

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintFrozenReferenceTest' --console=plain` (foreground, timeout 600000)
Expected: exit 0; `TEST-com.snipsnap.synth.GlintFrozenReferenceTest.xml` shows `tests="4" failures="0" errors="0"`.

- [ ] **Step 5: Prove the guard bites, with both loops mutated at once and reverted after**

In `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`, inside `synthesize`, change `val w = windowAt(phase.toFloat())` to `val w = windowAt(phase.toFloat()) * 1.0000001f`. In `synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt`, inside `render`, change `val w = Glint.windowAt(phase.toFloat())` to `val w = Glint.windowAt(phase.toFloat()) * 1.0000001f`.

Run the class again.
Expected: exit non-zero; the XML has failures in the one-shot test (`DEPTH 0 moved sample`), the render test and the held test, and none in the explicit-0 test (it compares production with production).

Then revert both mutations exactly:

Run: `git checkout -- synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt`
Run: `git status --short`
Expected: only the two new test files, untracked.

Run the class once more.
Expected: exit 0, all four pass.

- [ ] **Step 6: Commit**

Write the message to a file with the Write tool, then:

Run: `git add synth/src/test/kotlin/com/snipsnap/synth/LegacyGlint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintFrozenReferenceTest.kt`
Run: `git commit -F <message file>`

Title: `Freeze today's GLINT render loops so DEPTH 0 can be held to them`. Body: no GLINT golden existed (every identity test compared two renders from one build, so nothing detected a change against the base); the frozen copy follows the repo's `LegacyPluckLoop` pattern and uses its own window so an edit to `Glint.windowAt` is caught; a stored hash was rejected because `Math.sin` is not guaranteed bit-identical across CPUs; the guard was shown to bite by mutating both loops and reverting.

---

### Task 2: `GlintShape`: the rounded window, the table, the weights

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/GlintShape.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintShapeTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (used by Tasks 3 to 6):
  - `internal class GlintShape` with `val burstWeight: Float`, `val sineWeight: Float`, `fun window(phase: Float): Float`.
  - `GlintShape.Companion`: `const val FALL_INTERVALS = 4096`, `const val EDGE_FULL_AT = 0.5f`, `fun analyticWindow(phase: Double, edge: Double): Double`, `fun of(depth: Float, sineGain: () -> Float): GlintShape?` (null at DEPTH 0; the lambda is called only when the sine is on).

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintShapeTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2 and §3.2. */
class GlintShapeTest {

    private fun shape(depth: Float, gain: Float = 0.2f): GlintShape = GlintShape.of(depth) { gain }!!

    @Test
    fun `DEPTH 0 has no shape - the render loops keep their literal saw`() {
        assertNull(GlintShape.of(0f) { error("sineGain must not be asked for at DEPTH 0") })
    }

    @Test
    fun `any DEPTH above 0, however small, has a shape`() {
        assertNotNull(GlintShape.of(1e-9f) { error("no sine below DEPTH 0.5") })
        assertNotNull(GlintShape.of(Float.MIN_VALUE) { error("no sine below DEPTH 0.5") })
    }

    @Test
    fun `the sine's gain is only asked for above DEPTH 0_5`() {
        var asked = 0
        for (d in listOf(0.1f, 0.25f, 0.5f)) GlintShape.of(d) { asked++; 1f }
        assertEquals(0, asked)
        for (d in listOf(0.5001f, 0.75f, 1f)) GlintShape.of(d) { asked++; 1f }
        assertEquals(3, asked)
    }

    @Test
    fun `the weights follow the spec's table`() {
        for (d in listOf(0.1f, 0.25f, 0.5f)) {
            val s = shape(d)
            assertEquals(1f, s.burstWeight, "the burst alone at DEPTH $d")
            assertEquals(0f, s.sineWeight, "no sine at DEPTH $d")
        }
        val quarter = shape(0.75f, gain = 0.2f)            // u = 0.5
        assertEquals(sqrt(0.75f), quarter.burstWeight, 1e-6f)
        assertEquals(0.5f * 0.2f, quarter.sineWeight, 1e-6f)
        val most = shape(0.9f, gain = 0.2f)                // u = 0.8
        assertEquals(0.6f, most.burstWeight, 1e-6f)
        assertEquals(0.8f * 0.2f, most.sineWeight, 1e-6f)
        val one = shape(1f, gain = 0.2f)                   // the sine alone, exactly
        assertEquals(0f, one.burstWeight)
        assertEquals(0.2f, one.sineWeight)
    }

    @Test
    fun `the equal-power weights keep the sum of squares at one`() {
        for (d in listOf(0.55f, 0.6f, 0.75f, 0.9f, 0.99f, 1f)) {
            val s = shape(d, gain = 1f)
            assertEquals(1f, s.burstWeight * s.burstWeight + s.sineWeight * s.sineWeight, 1e-5f, "DEPTH $d")
        }
    }

    @Test
    fun `the window is exactly zero at both ends of the cycle, at every DEPTH`() {
        for (d in listOf(Float.MIN_VALUE, 1e-9f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val s = shape(d)
            assertEquals(0f, s.window(0f), "the window at phase 0, DEPTH $d")
            assertEquals(0f, s.window(1f), "the window at phase 1, DEPTH $d")
            assertTrue(s.window(Math.nextDown(1f)) < 1e-3f, "DEPTH $d: the window just before the wrap is not near zero")
        }
    }

    @Test
    fun `the table reproduces the analytic window`() {
        for (d in listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val e = if (d <= 0.5f) 2.0 * d else 1.0
            val s = shape(d)
            var worst = 0.0
            for (i in 0..2000) {
                val p = i / 2000.0
                worst = maxOf(worst, abs(s.window(p.toFloat()) - GlintShape.analyticWindow(p, e)))
            }
            assertTrue(worst < 2e-5, "DEPTH $d: the table is off the analytic window by $worst")
        }
    }

    @Test
    fun `a tiny DEPTH is the saw, except that it starts from zero`() {
        val s = shape(1e-9f)
        for (i in 1 until 2000) {
            val p = i / 2000f
            assertEquals(Glint.windowAt(p), s.window(p), 1e-5f, "phase $p")
        }
    }

    @Test
    fun `the attack is a raised cosine over the first half of e`() {
        // DEPTH 0.5: e = 1, so the attack spans the first half of the cycle.
        val s = shape(0.5f)
        // A quarter of the way through the attack the cosine is at its midpoint: 0.5 * (1 - 0.25)^2.
        assertEquals(0.5f * 0.5625f, s.window(0.25f), 1e-4f)
        // And where the attack ends it has reached 1, so the window is the fall alone: (1 - 0.5)^2.
        assertEquals(0.25f, s.window(0.5f), 1e-4f)
    }

    @Test
    fun `the window never leaves 0 to 1`() {
        for (d in listOf(1e-9f, 0.2f, 0.5f, 1f)) {
            val s = shape(d)
            for (i in 0..4000) {
                val w = s.window(i / 4000f)
                assertTrue(w in 0f..1.0000001f, "DEPTH $d phase ${i / 4000f}: $w")
            }
        }
    }

    @Test
    fun `analyticWindow is zero at both ends, and a real rounding leaves the wrap flat`() {
        for (e in listOf(1e-6, 0.3, 1.0)) {
            assertEquals(0.0, GlintShape.analyticWindow(0.0, e), "e $e at phase 0")
            assertEquals(0.0, GlintShape.analyticWindow(1.0, e), "e $e at phase 1")
        }
        // The saw falls into the wrap at a slope of 1. At e = 0.3 (slope about 0.09 here) and e = 1
        // (about 0.0003) the rounding has taken that slope away; a tiny e is still nearly the saw.
        for (e in listOf(0.3, 1.0)) {
            val h = 1e-4
            val slope = (GlintShape.analyticWindow(1.0 - 2 * h, e) - GlintShape.analyticWindow(1.0 - h, e)) / h
            assertTrue(slope < 0.15, "e $e: the window still falls into the wrap at a slope of $slope")
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintShapeTest' --console=plain`
Expected: the build fails to compile: `Unresolved reference: GlintShape`.

- [ ] **Step 3: Write the implementation**

Create `synth/src/main/kotlin/com/snipsnap/synth/GlintShape.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the DEPTH macro does to one render
 * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2 and §3.2).
 *
 * DEPTH 0 is today's engine and has no shape: [of] returns null, and both
 * render loops (`Glint.synthesize`, `GlintHeld.render`) then run their own
 * literal saw-window lines, because the two associate their multiplies
 * differently and no helper can reproduce both. Above 0 the window's edge is
 * rounded, fully so by DEPTH 0.5, and from there a plain sine at f0, scaled to
 * the burst's loudness, takes over by an equal-power law until at DEPTH 1 it
 * is all that is left. The rounded window and the sine are both exactly zero
 * at every wrap, so `k` still changes for free and only there.
 *
 * Built once per render and never shared: GLINT's renderers hold no mutable
 * state, and held zones render in parallel.
 */
internal class GlintShape private constructor(
    /** Half the edge rounding `e`: the raised-cosine attack lasts this much of a cycle. */
    private val attackSpan: Double,
    /** `(1 − p)^(1 + e)` at `p = i / FALL_INTERVALS`; the last entry is exactly 0. */
    private val fall: FloatArray,
    /** Multiplies the burst pair: `sqrt(1 − u²)`, exactly 1 while the sine is off. */
    val burstWeight: Float,
    /** Multiplies the sine, which rides the main envelope: `u · sineGain`, exactly 0 while the sine is off. */
    val sineWeight: Float,
) {

    /**
     * The rounded window at [phase]: zero at 0 and at 1, with no slope jump
     * across the wrap. The fall is looked up by phase alone (never by a sample
     * counter: a held loop's cycles differ by a sample and its exact closure
     * needs a pure function of phase); the attack is analytic and runs only
     * while `phase < attackSpan`, because at a small `e` it is narrower than a
     * table cell.
     */
    fun window(phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        val x = p * FALL_INTERVALS
        // phase.toFloat() can round up to 1.0f: the last cell then answers with the last entry, 0.
        val i = x.toInt().coerceAtMost(FALL_INTERVALS - 1)
        val lo = fall[i]
        val fell = lo + (fall[i + 1] - lo) * (x - i)
        if (p >= attackSpan) return fell
        return (0.5 * (1.0 - cos(PI * p / attackSpan))).toFloat() * fell
    }

    companion object {
        const val FALL_INTERVALS = 4096

        /** The DEPTH at which the edge is fully rounded and the sine begins to come in. */
        const val EDGE_FULL_AT = 0.5f

        /** The spec's `w_e(p)` for an edge rounding [edge] above 0, in `Double`. The table and the tests both read it. */
        fun analyticWindow(phase: Double, edge: Double): Double {
            val p = phase.coerceIn(0.0, 1.0)
            val span = 0.5 * edge
            val attack = if (p >= span) 1.0 else 0.5 * (1.0 - cos(PI * p / span))
            return attack * (1.0 - p).pow(1.0 + edge)
        }

        /**
         * The shape for [depth], or null at DEPTH 0. [sineGain] is asked for
         * only when the sine is on (DEPTH above 0.5): it integrates a cycle.
         */
        fun of(depth: Float, sineGain: () -> Float): GlintShape? {
            val d = depth.coerceIn(0f, 1f)
            if (d == 0f) return null
            val edge = if (d <= EDGE_FULL_AT) 2.0 * d else 1.0
            val u = if (d <= EDGE_FULL_AT) 0.0 else 2.0 * d - 1.0
            val fall = FloatArray(FALL_INTERVALS + 1) { i ->
                (1.0 - i.toDouble() / FALL_INTERVALS).pow(1.0 + edge).toFloat()
            }
            fall[FALL_INTERVALS] = 0f
            return GlintShape(
                attackSpan = 0.5 * edge,
                fall = fall,
                burstWeight = if (u == 0.0) 1f else sqrt(1.0 - u * u).toFloat(),
                sineWeight = if (u == 0.0) 0f else (u * sineGain()).toFloat(),
            )
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintShapeTest' --console=plain`
Expected: exit 0; the XML shows `failures="0" errors="0"` for all eleven tests.

- [ ] **Step 5: Prove the zero-at-the-wrap guard bites**

In `GlintShape.of`, change `fall[FALL_INTERVALS] = 0f` to `fall[FALL_INTERVALS] = 0.5f`. Run `GlintShapeTest`.
Expected: FAIL in `the window is exactly zero at both ends of the cycle, at every DEPTH` and in `the table reproduces the analytic window`, with messages naming the window at phase 1. Revert the line exactly and run again: exit 0.

- [ ] **Step 6: Commit**

Run: `git add synth/src/main/kotlin/com/snipsnap/synth/GlintShape.kt synth/src/test/kotlin/com/snipsnap/synth/GlintShapeTest.kt`
Run: `git commit -F <message file>`

Title: `Add GlintShape, the rounded window and the weights behind DEPTH`. Body: nothing calls it yet; DEPTH 0 has no shape so the render loops can keep their literal expressions; the fall is a per-render table indexed by phase (a held cycle differs by a sample, and exact loop closure needs a pure function of phase) with the attack analytic because it is narrower than a table cell at small `e`; the window is built in Double and stored as Float; the zero-at-the-wrap guard was shown to bite by moving the last table entry.

---

### Task 3: `GlintPath.sineGain()`

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt` (imports, `sineGain`, one constant)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintSineGainTest.kt`

**Interfaces:**
- Consumes: `GlintShape.analyticWindow(phase: Double, edge: Double): Double` (Task 2); `GlintPath.breathRatios(swing: Float, out: FloatArray)`, `GlintPath.level2` (existing).
- Produces: `fun sineGain(): Float` on `GlintPath`, the amplitude of the sine that carries the same power as the fully rounded burst pair at the path's resting ratios. Tasks 4 and 5 call it through `GlintShape.of(depth) { path.sineGain() }`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintSineGainTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2, `sineGain`. */
class GlintSineGainTest {

    private fun pathFor(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): GlintPath {
        val m = Glint.defaults(voice) + macros
        return GlintPath.of(voice, m, Glint.frequencyFor(voice, m.getValue("TUNE")))
    }

    private fun assertWithinDb(expected: Double, actual: Float, dB: Double, what: String) {
        val off = abs(20.0 * log10(actual / expected))
        assertTrue(off <= dB, "$what: $actual against $expected is ${"%.2f".format(java.util.Locale.ROOT, off)} dB off (bar $dB)")
    }

    @Test
    fun `SWEEP and VOWEL agree with the burst RMS the listening probe measured`() {
        // Round 2's probe measured rB, the RMS of the fully rounded burst pair over a breathing pad:
        // SWEEP 0.139636 and VOWEL 0.134822. The sine that matches it is sqrt(2) * rB. The engine's
        // number is stationary at the breath's centre, so it may sit a fraction of a dB off a breath
        // average (measured: SWEEP 0.0 dB, VOWEL 0.5 dB below).
        assertWithinDb(0.139636 * sqrt(2.0), pathFor(GlintVoice.SWEEP).sineGain(), 1.0, "SWEEP")
        assertWithinDb(0.134822 * sqrt(2.0), pathFor(GlintVoice.VOWEL).sineGain(), 1.0, "VOWEL")
    }

    @Test
    fun `it equals an independent, finer integration of the same burst pair`() {
        for (voice in GlintVoice.entries) {
            val path = pathFor(voice)
            val k = FloatArray(2)
            path.breathRatios(0f, k)
            val n = 16384
            var sum = 0.0
            for (i in 0 until n) {
                val p = (i + 0.5) / n
                val v = GlintShape.analyticWindow(p, 1.0) *
                    (sin(2.0 * PI * k[0] * p) + path.level2 * sin(2.0 * PI * k[1] * p))
                sum += v * v
            }
            val expected = sqrt(2.0 * sum / n)
            assertEquals(expected, path.sineGain().toDouble(), expected * 1e-3, "$voice")
        }
    }

    @Test
    fun `with no second burst it is the square root of the window's power`() {
        // BODY 0 leaves one burst. For a high k the sine's mean square is a half, so rB squared is half of
        // the integral of w1 squared (0.03617), and the matching sine's amplitude is sqrt(0.03617) = 0.1902.
        val gain = pathFor(GlintVoice.SWEEP, mapOf("BODY" to 0f, "PEAK" to 1f)).sineGain()
        assertEquals(0.1902f, gain, 0.1902f * 0.02f)
    }

    @Test
    fun `it is finite and sensible at both ends of PEAK, TUNE and BODY on every voice`() {
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0f, 1f)) {
                for (tune in listOf(0f, 1f)) {
                    for (body in listOf(0f, 1f)) {
                        val g = pathFor(voice, mapOf("PEAK" to peak, "TUNE" to tune, "BODY" to body)).sineGain()
                        assertTrue(g.isFinite() && g in 0.05f..0.6f, "$voice PEAK $peak TUNE $tune BODY $body: $g")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintSineGainTest' --console=plain`
Expected: compile error, `Unresolved reference: sineGain`.

- [ ] **Step 3: Write the implementation**

In `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt`, change the imports from

```kotlin
import kotlin.math.abs
import kotlin.math.pow
```

to

```kotlin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
```

Insert this function immediately before the KDoc that begins `     * VOWEL's two bursts at [position] on the vowel line, BODY's vocal-tract` (that is, after `breathRatios`):

```kotlin
    /**
     * The amplitude of the sine DEPTH mixes in, chosen so that sine and burst carry equal power
     * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2). It is `√2 · rB`, `rB`
     * being the RMS over one cycle of the fully rounded burst pair,
     * `w₁(p) · (sin(2π·k0·p) + level2 · sin(2π·k1·p))`, at this path's resting ratios (the breath's
     * centre: [breathRatios] at 0), by numerical integration. It is one number for a note whose ratios
     * move along their path, so it is right at rest and within about a dB elsewhere.
     */
    fun sineGain(): Float {
        val k = FloatArray(2)
        breathRatios(0f, k)
        var sum = 0.0
        for (i in 0 until SINE_GAIN_STEPS) {
            val p = (i + 0.5) / SINE_GAIN_STEPS
            val v = GlintShape.analyticWindow(p, 1.0) *
                (sin(2.0 * PI * k[0] * p) + level2 * sin(2.0 * PI * k[1] * p))
            sum += v * v
        }
        return sqrt(2.0 * sum / SINE_GAIN_STEPS).toFloat()
    }

```

Then, inside `companion object {`, add before the `of` function's KDoc:

```kotlin
        /** Midpoints per cycle in [sineGain]'s integral: 51 per cycle even at k = 40. */
        private const val SINE_GAIN_STEPS = 2048

```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintSineGainTest' --console=plain`
Expected: exit 0, four tests, no failures.

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintFrozenReferenceTest' --console=plain`
Expected: exit 0 (nothing the render loops use has changed).

- [ ] **Step 5: Commit**

Run: `git add synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt synth/src/test/kotlin/com/snipsnap/synth/GlintSineGainTest.kt`
Run: `git commit -F <message file>`

Title: `Give GlintPath the gain that matches DEPTH's sine to the burst`. Body: the round 2 listening probe matched the sine to the burst by measured RMS, so the engine must too and not by a guess (the guessed gain of round 1 made the sine 84% of the power at DEPTH 0.75 and Josh heard it as underwater); the integral runs at the path's resting ratios through `breathRatios(0f)`, which every voice already answers, so GlintPath needs no new private access; checked against the probe's measured burst RMS (SWEEP 0.0 dB, VOWEL 0.5 dB off, inside the 1 dB bar) and against a finer independent integral.

---

### Task 4: The DEPTH macro and the one-shot

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` (`macrosFor`, `synthesize`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt` (the all-ones corner, the macro-list test)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintVowelTest.kt` (the macro-list test)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt`

**Interfaces:**
- Consumes: `GlintShape.of(depth: Float, sineGain: () -> Float): GlintShape?`, `GlintShape.window(phase: Float): Float`, `GlintShape.burstWeight`, `GlintShape.sineWeight` (Task 2); `GlintPath.sineGain(): Float` (Task 3).
- Produces: `DEPTH` in `Glint.macrosFor` and `Glint.defaults` for every voice; `Glint.synthesize` honouring it. Task 5 mirrors the pattern in `GlintHeld`; Task 6 adds to `GlintDepthTest`.

- [ ] **Step 1: Update the two macro-list tests and the corner, and write the new tests**

In `GlintTest.kt`, replace the all-ones corner (inside `every voice renders clean audio at defaults and both corners`):

```kotlin
            val cases = listOf(
                emptyMap<String, Float>() to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 0f } to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 1f } to 0.35f,
            )
```

with

```kotlin
            // DEPTH is pinned to 0 in the all-ones corner: at 1 it is a bare sine, and this corner exists to
            // exercise the buzzy window. GlintDepthTest renders DEPTH's own corners.
            val cases = listOf(
                emptyMap<String, Float>() to 0.5f,
                Glint.macrosFor(voice).associate { it.name to 0f } to 0.5f,
                Glint.macrosFor(voice).associate { it.name to (if (it.name == "DEPTH") 0f else 1f) } to 0.35f,
            )
```

In `GlintTest.kt`, replace the macro-list test (from the comment line through the end of the test) with:

```kotlin
    // VOWEL declares six, without FOLLOW: `GlintVowelTest` holds its list.
    @Test
    fun `every path voice declares exactly the seven macros`() {
        for (voice in PATH_VOICES) {
            val macros = Glint.macrosFor(voice)
            assertEquals(
                listOf("TUNE", "PEAK", "FOLLOW", "BODY", "BLOOM", "DECAY", "DEPTH"),
                macros.map { it.name },
                "$voice's macro contract",
            )
            assertEquals(listOf(0.5f, 0.45f, 0.8f, 0.4f, 0.675f, 0.5f, 0f), macros.map { it.default }, "$voice's defaults")
            assertEquals(0.5f, macros.first { it.name == "BLOOM" }.neutral, "$voice's BLOOM is bipolar, neutral at its centre")
        }
    }
```

In `GlintVowelTest.kt`, replace the two list lines in `VOWEL has no FOLLOW - its formants are fixed Hz`:

```kotlin
        assertEquals(listOf("TUNE", "PEAK", "BODY", "BLOOM", "DECAY"), macros.map { it.name })
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.6f, 0.5f), macros.map { it.default }, "VOWEL's defaults")
```

with

```kotlin
        assertEquals(listOf("TUNE", "PEAK", "BODY", "BLOOM", "DECAY", "DEPTH"), macros.map { it.name })
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.6f, 0.5f, 0f), macros.map { it.default }, "VOWEL's defaults")
```

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GLINT's DEPTH on the one-shot (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2 and §4). */
class GlintDepthTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun raw(voice: GlintVoice, depth: Float, extra: Map<String, Float> = emptyMap()) =
        Glint.synthesize(voice, extra + ("DEPTH" to depth), rate)

    /** Sample indices where a new cycle begins, replaying the render loop's own phase accumulator. */
    private fun cycleStarts(f0: Float, frames: Int): IntArray {
        val step = f0.toDouble() / rate
        var phase = 0.0
        val starts = ArrayList<Int>()
        starts += 0
        for (i in 0 until frames) {
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                starts += i + 1
            }
        }
        return starts.toIntArray()
    }

    @Test
    fun `DEPTH is the last macro on every voice, default 0, neutral 0`() {
        for (voice in GlintVoice.entries) {
            val spec = Glint.macrosFor(voice).last()
            assertEquals("DEPTH", spec.name, "$voice")
            assertEquals(0f, spec.default, "$voice")
            assertEquals(0f, spec.neutral, "$voice")
        }
    }

    @Test
    fun `DEPTH 1 is the sine alone - sineGain times the note's envelope times sin 2 pi phase`() {
        for (voice in GlintVoice.entries) {
            val m = Glint.defaults(voice)
            val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
            val gain = GlintPath.of(voice, m, f0).sineGain()
            val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
            val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
            val out = raw(voice, 1f)
            val step = f0.toDouble() / rate
            var phase = 0.0
            var worst = 0f
            for (i in out.indices) {
                val expected = gain * amp.at(i.toFloat() / rate) * sin(2.0 * PI * phase).toFloat()
                worst = maxOf(worst, abs(out[i] - expected))
                phase += step
                if (phase >= 1.0) phase -= 1.0
            }
            assertTrue(worst < 1e-6f, "$voice: DEPTH 1 is not sineGain * envelope * sin, off by $worst")
        }
    }

    @Test
    fun `DEPTH 0_75 and 0_9 are the burst and the sine, weighted as the law says`() {
        for (voice in GlintVoice.entries) {
            val burst = raw(voice, 0.5f)   // e = 1, no sine: the burst pair alone
            val sine = raw(voice, 1f)      // the sine alone
            for (depth in listOf(0.75f, 0.9f)) {
                val u = 2.0 * depth - 1.0
                val burstWeight = sqrt(1.0 - u * u).toFloat()
                val sineWeight = u.toFloat()
                val mixed = raw(voice, depth)
                var worst = 0f
                for (i in mixed.indices) {
                    worst = maxOf(worst, abs(mixed[i] - (burstWeight * burst[i] + sineWeight * sine[i])))
                }
                assertTrue(worst < 1e-5f, "$voice DEPTH $depth: the mix is off the law by $worst")
            }
        }
    }

    @Test
    fun `at rest the sine and the burst carry the same power`() {
        // BODY 0 (no second burst on the path voices), BLOOM still, DECAY long: burst and sine share one
        // envelope, so the ratio of their RMS over whole cycles is the ratio of their stationary levels,
        // which sineGain was built to make 1.
        val still = mapOf("BODY" to 0f, "BLOOM" to 0.5f, "DECAY" to 1f)
        for (voice in GlintVoice.entries) {
            val f0 = Glint.frequencyFor(voice, Glint.defaults(voice).getValue("TUNE"))
            val burst = raw(voice, 0.5f, still)
            val sine = raw(voice, 1f, still)
            val starts = cycleStarts(f0, burst.size)
            val a = starts[20]
            val b = starts[60]
            fun rms(x: FloatArray): Double {
                var s = 0.0
                for (i in a until b) s += x[i].toDouble() * x[i]
                return sqrt(s / (b - a))
            }
            assertEquals(1.0, rms(sine) / rms(burst), 0.03, "$voice: sine over burst RMS at rest")
        }
    }

    @Test
    fun `a note starts from exactly zero at every DEPTH`() {
        for (voice in GlintVoice.entries) {
            for (depth in listOf(Float.MIN_VALUE, 0.25f, 0.5f, 0.75f, 1f)) {
                assertEquals(0f, raw(voice, depth)[0], "$voice DEPTH $depth: the first sample")
            }
        }
    }

    @Test
    fun `a patch naming DEPTH is accepted and round-trips, and DEPTH out of range is refused`() {
        for (voice in GlintVoice.entries) {
            val patch = GlintPatch("Soft", voice, mapOf("DEPTH" to 0.6f))
            val back = Patches.fromJsonText(patch.toJsonText()) as GlintPatch
            assertEquals(0.6f, back.macros.getValue("DEPTH"), "$voice")
            assertFailsWith<IllegalArgumentException> { GlintPatch("Bad", voice, mapOf("DEPTH" to 1.5f)) }
        }
    }

    @Test
    fun `SCRAMBLE's earlier macros draw as they did before DEPTH existed`() {
        for (voice in GlintVoice.entries) {
            val withoutDepth = Glint.defaults(voice).filterKeys { it != "DEPTH" }
            val before = Dsp.scrambleNear(withoutDepth, 0.35f, Random(7))
            val now = Glint.scramble(voice, Random(7))
            assertEquals(before, now.filterKeys { it != "DEPTH" }, "$voice: DEPTH moved an earlier macro's draw")
            assertTrue(now.getValue("DEPTH") in 0f..1f, "$voice: DEPTH rolled out of range")
        }
    }

    @Test
    fun `every voice renders clean audio across DEPTH, at defaults and both corners`() {
        for (voice in GlintVoice.entries) {
            val names = Glint.macrosFor(voice).map { it.name }.filter { it != "DEPTH" }
            val cases = listOf(emptyMap<String, Float>(), names.associateWith { 0f }, names.associateWith { 1f })
            for (macros in cases) {
                for (depth in listOf(0.1f, 0.5f, 0.75f, 0.9f, 1f)) {
                    val snip = Glint.render(voice, macros + ("DEPTH" to depth))
                    val what = "$voice DEPTH $depth at $macros"
                    assertTrue(snip.frameCount > 0, "$what rendered nothing")
                    assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$what broke range")
                    assertTrue(snip.peak() > 0.15f, "$what is too quiet")
                    assertTrue(abs(snip.samples.average().toFloat()) < 0.05f, "$what has DC")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintDepthTest' --tests 'com.snipsnap.synth.GlintTest' --tests 'com.snipsnap.synth.GlintVowelTest' --console=plain`
Expected: failures: the macro-list tests (no DEPTH yet), `DEPTH is the last macro` (`Glint.macrosFor(voice).last()` is DECAY), and the DEPTH-1, law and rest tests (DEPTH is ignored, so raw(voice, 1f) is the saw).

- [ ] **Step 3: Write the implementation**

In `Glint.kt`, `macrosFor`: replace

```kotlin
            MacroSpec("BLOOM", 0.6f, 0.5f),   // a short glide down into the vowel
            MacroSpec("DECAY", 0.5f),
        )
```

with

```kotlin
            MacroSpec("BLOOM", 0.6f, 0.5f),   // a short glide down into the vowel
            MacroSpec("DECAY", 0.5f),
            // Rounds the window's edge, then fades a plain sine in (GlintShape). 0 is today's sound.
            MacroSpec("DEPTH", 0f),
        )
```

and replace

```kotlin
            MacroSpec("BLOOM", 0.675f, 0.5f),
            MacroSpec("DECAY", 0.5f),
        )
```

with

```kotlin
            MacroSpec("BLOOM", 0.675f, 0.5f),
            MacroSpec("DECAY", 0.5f),
            // Rounds the window's edge, then fades a plain sine in (GlintShape). 0 is today's sound.
            MacroSpec("DEPTH", 0f),
        )
```

In `Glint.synthesize`, replace this block (copy it exactly from the file):

```kotlin
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(phase.toFloat())
            val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
            // Left uncorrected on purpose. The saw ramp isn't symmetric about
            // phase 0.5, so a saw-windowed burst carries 1/(2πk) of DC per
            // cycle: a few percent of peak in the head window on the path
            // voices (`BODY carries a small, bounded DC` in GlintTest), and up
            // to about 0.12 on VOWEL at the top of TUNE, where F1 pins to
            // k = 1. It decays with the note. Subtracting a constant would
            // stop the window reaching exactly zero at the wrap - the
            // property this file's class doc calls the whole engine.
            val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            out[i] = amp.at(t) * burst + env2.at(t) * second
            phase += step
```

with

```kotlin
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        // DEPTH. Null at 0, and then the loop below is exactly the saw-window loop this file has always
        // had: its expressions are left as they were, because adding a zero term can turn a -0f sample into
        // +0f. Above 0 it is the rounded window and, from DEPTH 0.5, the sine (GlintShape).
        val shape = GlintShape.of(m.getValue("DEPTH")) { path.sineGain() }
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (shape == null) {
                val w = windowAt(phase.toFloat())
                val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
                // Left uncorrected on purpose. The saw ramp isn't symmetric about
                // phase 0.5, so a saw-windowed burst carries 1/(2πk) of DC per
                // cycle: a few percent of peak in the head window on the path
                // voices (`BODY carries a small, bounded DC` in GlintTest), and up
                // to about 0.12 on VOWEL at the top of TUNE, where F1 pins to
                // k = 1. It decays with the note. Subtracting a constant would
                // stop the window reaching exactly zero at the wrap - the
                // property this file's class doc calls the whole engine.
                val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                out[i] = amp.at(t) * burst + env2.at(t) * second
            } else {
                val w = shape.window(phase.toFloat())
                val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
                val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                val main = amp.at(t)
                var v = shape.burstWeight * (main * burst + env2.at(t) * second)
                // The sine rides the main envelope. It is skipped, not zeroed, below DEPTH 0.5.
                if (shape.sineWeight != 0f) v += shape.sineWeight * main * sin(2.0 * PI * phase).toFloat()
                out[i] = v
            }
            phase += step
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintDepthTest' --tests 'com.snipsnap.synth.GlintTest' --tests 'com.snipsnap.synth.GlintVowelTest' --tests 'com.snipsnap.synth.GlintFrozenReferenceTest' --tests 'com.snipsnap.synth.GlintLegacyTest' --console=plain`
Expected: exit 0, no failures. `GlintFrozenReferenceTest` still passes: DEPTH 0 is today's one-shot, bit for bit.

- [ ] **Step 5: Prove the DEPTH-0 branch is guarded**

In the `shape == null` branch change `val w = windowAt(phase.toFloat())` to `val w = windowAt(phase.toFloat()) * 1.0000001f`. Run `GlintFrozenReferenceTest`.
Expected: FAIL in the one-shot test with `DEPTH 0 moved sample`. Undo the edit by hand (the file now holds the whole of this task's change, so `git checkout` would discard it) and run again: exit 0.

- [ ] **Step 6: Commit**

Run: `git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt synth/src/test/kotlin/com/snipsnap/synth/GlintVowelTest.kt synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt`
Run: `git commit -F <message file>`

Title: `Add the DEPTH macro and honour it in the one-shot`. Body: DEPTH is appended last on every voice with default 0 so a patch saved before it decodes with 0 and renders bit for bit as before (the frozen reference still passes); the DEPTH-0 branch keeps the saw loop's expressions verbatim because a zero term can turn `-0f` into `+0f`; the all-ones corner pins DEPTH 0 because all-ones would otherwise become a bare sine and the corner would silently lose its buzzy case; the earlier macros' SCRAMBLE draws are pinned unchanged.

---

### Task 5: DEPTH in the held pad

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt` (the inner loop; one KDoc paragraph)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt`

**Interfaces:**
- Consumes: `GlintShape.of`, `GlintShape.window`, `burstWeight`, `sineWeight` (Task 2), `GlintPath.sineGain()` (Task 3), `Glint.macrosFor` with DEPTH (Task 4).
- Produces: `GlintHeld.render` honouring DEPTH; `Keys.glintPad` and `GlintPadMaker` follow with no edit (`Keys.glintPad` filters macros by `Glint.defaults`, which now has DEPTH).

- [ ] **Step 1: Write the failing tests**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GLINT's DEPTH on the held pad (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.3 and §4). */
class GlintDepthHeldTest {

    @Test
    fun `every voice's loop closes at DEPTH 0_3, 0_75 and 1 - the seam and the second breath`() {
        for (voice in GlintVoice.entries) {
            for (depth in listOf(0.3f, 0.75f, 1f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to 0.8f, "TUNE" to tune, "DEPTH" to depth))
                    val what = "$voice DEPTH $depth TUNE $tune"
                    assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR, "$what: the loop does not close")
                    // The second breath is the first, sample for sample, from its 64th frame on
                    // (see GlintBreatheTest: by then the decimator has forgotten the onset).
                    val len = held.audio.size - held.loopStart
                    var maxDiff = 0f
                    for (j in 64 until len) {
                        maxDiff = maxOf(maxDiff, abs(held.audio[held.loopStart - len + j] - held.audio[held.loopStart + j]))
                    }
                    assertEquals(0f, maxDiff, "$what: the second breath differs from the first")
                }
            }
        }
    }

    @Test
    fun `DEPTH 1 holds a bare sine at the note's pitch`() {
        for (voice in GlintVoice.entries) {
            val held = GlintHeld.render(voice, mapOf("DEPTH" to 1f))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            val size = Fft.floorPowerOfTwo(loop.size)
            val mag = Fft.magnitudeSpectrum(loop, size)
            val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, Glint.defaults(voice).getValue("TUNE")))
            val bin = Math.round(plan.f0 * size / Dsp.RATE).toInt()
            var total = 0.0
            var near = 0.0
            for (b in 1 until mag.size) {
                val e = mag[b].toDouble() * mag[b]
                total += e
                if (abs(b - bin) <= 2) near += e
            }
            assertTrue(near / total >= 0.999, "$voice: only ${near / total} of the loop's energy is within two bins of f0")
        }
    }

    @Test
    fun `at DEPTH 1 the voice does not matter - PEAK, BLOOM and BODY leave the loop alone`() {
        // SWEEP and VOWEL only: STEP's ladder length moves where the loop starts, and BRASS's level breathes with BLOOM.
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
            val a = GlintHeld.render(voice, mapOf("DEPTH" to 1f, "PEAK" to 0.1f, "BLOOM" to 0.9f, "BODY" to 0.1f))
            val b = GlintHeld.render(voice, mapOf("DEPTH" to 1f, "PEAK" to 0.9f, "BLOOM" to 0.2f, "BODY" to 0.9f))
            val la = a.audio.copyOfRange(a.loopStart, a.audio.size)
            val lb = b.audio.copyOfRange(b.loopStart, b.audio.size)
            assertEquals(la.size, lb.size, "$voice: loop length")
            var worst = 0f
            for (i in la.indices) worst = maxOf(worst, abs(la[i] - lb[i]))
            assertTrue(worst < 1e-4f, "$voice: two different voices at DEPTH 1 differ by $worst")
        }
    }

    @Test
    fun `all nine zones of a SWEEP pad close at DEPTH 0_6, as MAKE INSTRUMENT renders them`() {
        // glintPad itself refuses a loop that does not close (requireSeam), so rendering is the check.
        for (midi in Keys.glintPadMidis(GlintVoice.SWEEP)) {
            val note = Keys.glintPad(GlintVoice.SWEEP, mapOf("BLOOM" to 0.85f, "DEPTH" to 0.6f), midi)
            assertTrue(note.loopStartFrame > 0, "SWEEP MIDI $midi")
        }
    }

    @Test
    fun `VOWEL's lowest, middle and highest zones close at DEPTH 0_6`() {
        val midis = Keys.glintPadMidis(GlintVoice.VOWEL)
        for (midi in listOf(midis.first(), midis[4], midis.last())) {
            val note = Keys.glintPad(GlintVoice.VOWEL, mapOf("BLOOM" to 0.85f, "DEPTH" to 0.6f), midi)
            assertTrue(note.loopStartFrame > 0, "VOWEL MIDI $midi")
        }
    }

    @Test
    fun `VOWEL at the top of TUNE with PEAK 0, where F1 pins to the fundamental, closes at DEPTH 0_75 and 1`() {
        for (depth in listOf(0.75f, 1f)) {
            val held = GlintHeld.render(GlintVoice.VOWEL, mapOf("TUNE" to 1f, "PEAK" to 0f, "BLOOM" to 0.5f, "DEPTH" to depth))
            assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR, "DEPTH $depth: the loop does not close")
            assertTrue(held.audio.all { it.isFinite() && abs(it) <= 0.99f + 1e-6f }, "DEPTH $depth: out of range")
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintDepthHeldTest' --console=plain`
Expected: the sine-purity and invariance tests fail (the held render ignores DEPTH, so DEPTH 1 is the saw pad); the closure tests may pass (a saw pad closes).

- [ ] **Step 3: Write the implementation**

In `GlintHeld.kt`, after the line `        val depth = abs(path.bloom)` add:

```kotlin
        // `depth` above is BLOOM's own travel. DEPTH the macro is the shape below: null at DEPTH 0, and the
        // loop is then today's, line for line (see GlintShape).
        val shape = GlintShape.of(m.getValue("DEPTH")) { path.sineGain() }
```

Replace this block (copy it exactly from the file):

```kotlin
            val w = Glint.windowAt(phase.toFloat())
            val second = if (voice == GlintVoice.VOWEL) amp else attack
            out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
```

with

```kotlin
            if (shape == null) {
                val w = Glint.windowAt(phase.toFloat())
                val second = if (voice == GlintVoice.VOWEL) amp else attack
                out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                    second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            } else {
                val w = shape.window(phase.toFloat())
                val second = if (voice == GlintVoice.VOWEL) amp else attack
                var v = shape.burstWeight * (
                    amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                        second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                    )
                // The sine rides the main level and is a pure function of phase, so the loop still closes.
                if (shape.sineWeight != 0f) v += shape.sineWeight * amp * sin(2.0 * PI * phase).toFloat()
                out[i] = v
            }
```

Also add this paragraph to the class KDoc, immediately before its closing ` */` (the line ` * 0.108 (0.19 at TUNE 1, PEAK 0, BODY 0).` is the last line of the doc):

```kotlin
 *
 * DEPTH (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md) is one value for
 * the whole render, so the loop stays periodic: the rounded window and the sine are pure
 * functions of phase, and at DEPTH 0 the loop is today's, line for line.
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.Glint*' --console=plain`
Expected: exit 0, no failures anywhere in the GLINT family (the frozen reference and the existing breathe and held tests included).

- [ ] **Step 5: Prove the DEPTH-0 branch of the held loop is guarded**

Change `val w = Glint.windowAt(phase.toFloat())` in the `shape == null` branch to `... * 1.0000001f`, run `GlintFrozenReferenceTest`.
Expected: FAIL in the held test (`DEPTH 0 moved sample`). Revert by hand; run again: exit 0.

- [ ] **Step 6: Commit**

Run: `git add synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt synth/src/test/kotlin/com/snipsnap/synth/GlintDepthHeldTest.kt`
Run: `git commit -F <message file>`

Title: `Honour DEPTH in the held pad`. Body: the held loop is the second of the two places the waveform is computed and associates its multiplies differently from the one-shot, so it keeps its own DEPTH-0 lines; DEPTH is constant for the render, and the rounded window and the sine are pure functions of phase, so the loop closes exactly (seam 0, second breath bit-identical, shown on all four voices at DEPTH 0.3, 0.75 and 1 and at both ends of TUNE); STEP's onset length moves where its loop starts and BRASS's level breathes with BLOOM, so only SWEEP and VOWEL are compared across macros at DEPTH 1.

---

### Task 6: The wrap, the edge cases, and the level guard

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt` (append tests)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthLoudnessTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2 to 5.
- Produces: tests only (no production change unless a test finds a defect, which is reported, not worked around).

- [ ] **Step 1: Append the wrap and edge-case tests to `GlintDepthTest`**

Add these tests inside the class (before its final closing brace):

```kotlin
    /**
     * Spec §4.2 and §4.6. The step across each wrap is never the largest step: the rounded window and
     * the sine are both exactly zero there, so the wrap is not a click at any DEPTH. (Not "the biggest
     * step shrinks as DEPTH rises": each render is levelled on its own, and normalised by RMS the saw
     * and the rounded window do not differ that way.) Raw buffers, BLOOM still so that k is constant.
     */
    @Test
    fun `the wrap is never the largest step - no click at any DEPTH`() {
        for (voice in GlintVoice.entries) {
            for (depth in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val f0 = Glint.frequencyFor(voice, Glint.defaults(voice).getValue("TUNE"))
                val out = raw(voice, depth, mapOf("BLOOM" to 0.5f))
                val starts = cycleStarts(f0, out.size).toHashSet()
                var wrapStep = 0f
                var innerStep = 0f
                for (i in 1 until out.size) {
                    val d = abs(out[i] - out[i - 1])
                    if (i in starts) wrapStep = maxOf(wrapStep, d) else innerStep = maxOf(innerStep, d)
                }
                assertTrue(
                    wrapStep <= innerStep * 1.05f,
                    "$voice DEPTH $depth: a step of $wrapStep across a wrap against $innerStep inside the cycles",
                )
            }
        }
    }

    @Test
    fun `a tiny DEPTH is a tiny change - the shaped path, not the saw`() {
        for (voice in GlintVoice.entries) {
            val saw = raw(voice, 0f)
            val tiny = raw(voice, 1e-9f)
            assertEquals(saw.size, tiny.size, "$voice")
            var worst = 0f
            for (i in saw.indices) worst = maxOf(worst, abs(saw[i] - tiny[i]))
            assertTrue(worst < 1e-4f, "$voice: DEPTH 1e-9 moved the render by $worst")
        }
    }

    @Test
    fun `VOWEL at the top of TUNE with PEAK 0, where F1 pins to the fundamental, stays bounded`() {
        // F1 pins to k = 1 (VOWEL_K_MIN): the burst and the sine are in phase at f0.
        for (depth in listOf(0.6f, 0.75f, 0.9f, 1f)) {
            val out = raw(GlintVoice.VOWEL, depth, mapOf("TUNE" to 1f, "PEAK" to 0f))
            assertEquals(0f, out[0], "DEPTH $depth: the first sample")
            assertTrue(out.all { it.isFinite() && abs(it) < 2f }, "DEPTH $depth: unbounded")
        }
    }

    @Test
    fun `STEP at the top of TUNE and PEAK renders clean at DEPTH 0_75`() {
        val snip = Glint.render(GlintVoice.STEP, mapOf("TUNE" to 1f, "PEAK" to 1f, "DEPTH" to 0.75f))
        assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "STEP broke range")
        assertTrue(snip.peak() > 0.15f, "STEP is too quiet")
    }
```

- [ ] **Step 2: Create the level guard**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthLoudnessTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spec §2.4 and §4.7: nothing is built to hold the level steady across DEPTH, because the engine's own
 * levelling (`Dsp.levelTo` on the one-shot) already absorbs the roughly 10 dB the rounded window costs.
 * What it cannot absorb is the 0.99 peak ceiling, which depends on crest factor, so the final loudness
 * still moves a little. This is a regression guard on that, not a promise: the spec's replica estimate
 * was within about 4 dB of DEPTH 0 (3.7 dB at DECAY 0, the worst of the cases it tried).
 */
class GlintDepthLoudnessTest {

    private fun dB(snip: Snip): Double = 20.0 * log10(Loudness.of(snip).toDouble())

    @Test
    fun `the engine's levelling keeps every DEPTH within a few dB of DEPTH 0`() {
        val lines = mutableListOf<String>()
        var worst = 0.0
        for (voice in GlintVoice.entries) {
            for (decay in listOf(0f, 0.5f, 1f)) {
                val base = dB(Glint.render(voice, mapOf("DECAY" to decay)))
                val deltas = listOf(0.25f, 0.5f, 0.75f, 0.9f, 1f).map { depth ->
                    depth to dB(Glint.render(voice, mapOf("DECAY" to decay, "DEPTH" to depth))) - base
                }
                lines += "GLINT DEPTH loudness $voice DECAY $decay, dB against DEPTH 0: " +
                    deltas.joinToString(", ") { (d, v) -> "$d: ${"%+.2f".format(java.util.Locale.ROOT, v)}" }
                worst = maxOf(worst, deltas.maxOf { abs(it.second) })
            }
        }
        lines.forEach(::println)
        println("GLINT DEPTH loudness: worst departure from DEPTH 0 is ${"%.2f".format(java.util.Locale.ROOT, worst)} dB (bar $LIMIT_DB)")
        assertTrue(worst <= LIMIT_DB, "DEPTH moved the final loudness by $worst dB against DEPTH 0 (bar $LIMIT_DB dB)")
    }

    private companion object {
        const val LIMIT_DB = 5.0
    }
}
```

- [ ] **Step 3: Run them**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintDepthTest' --tests 'com.snipsnap.synth.GlintDepthLoudnessTest' --console=plain`
Expected: exit 0.

If `GlintDepthLoudnessTest` fails, do not raise `LIMIT_DB`. Read the printed `GLINT DEPTH loudness` lines from the XML `<system-out>`, and report the table to the controller with the failing case: the spec's estimate said the worst case was about 3.7 dB, and a larger number is a finding about the engine, not about the bar. Likewise, if any other new test fails, report it as a finding; do not weaken the assertion.

Record the printed worst-case figure and the per-voice table in the report (the spec's §2.4 numbers were a replica estimate; these are the engine's own).

- [ ] **Step 4: Prove the wrap guard bites**

In `GlintShape.of`, change `fall[FALL_INTERVALS] = 0f` to `fall[FALL_INTERVALS] = 0.5f`. Run `GlintDepthTest`.
Expected: FAIL in `the wrap is never the largest step - no click at any DEPTH` (and in the shape tests). Revert exactly; run again: exit 0.

- [ ] **Step 5: Commit**

Run: `git add synth/src/test/kotlin/com/snipsnap/synth/GlintDepthTest.kt synth/src/test/kotlin/com/snipsnap/synth/GlintDepthLoudnessTest.kt`
Run: `git commit -F <message file>`

Title: `Pin DEPTH's wrap, its edge cases and the level it leaves`. Body: the spec's first wording of the click gate (the largest sample step falls as DEPTH rises) is true of the probe's unlevelled output and false of a levelled render, where the saw and the rounded window do not differ that way by RMS, so the gate is the step across each wrap against the largest step inside the cycles; the level guard records what the engine's own levelling leaves across DEPTH (the figure measured here replaces the spec's replica estimate) and is a regression guard, not a promise; the wrap guard was shown to bite by moving the last table entry.

---

### Task 7: Docs and the real-engine audition tooling

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` (class KDoc, `windowAt` KDoc)
- Modify: `docs/SYNTH_ROADMAP.md` (the S10 row)
- Modify: `docs/superpowers/specs/2026-09-29-glint-paths-design.md` (§6)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintPathsAuditionGenerator.kt` (`heldFor` visibility)
- Modify: `synth/build.gradle.kts` (one task), `.gitignore` (one entry)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthAuditionGenerator.kt`

**Interfaces:**
- Consumes: `Keys.glintPad`, `Keys.glintPadMidis`, `GlintPathsAuditionGenerator.heldFor(note: KeyNote, seconds: Float): Snip`, `AuditionLevel.level(snip): Snip`.
- Produces: `./gradlew :synth:generateGlintDepthAudition` writing `testkit/glint-depth-audition/NN-<voice>-depthNNN.wav` (ten files, gitignored).

- [ ] **Step 1: Rewrite the two KDoc blocks in `Glint.kt`**

In the class KDoc replace

```
 * Only the window's *slope* jumps across the wrap, and that slope
 * discontinuity is the buzz the engine is made of. Do not smooth it.
 *
 * A voice is the path the formant takes — see `GlintPath` and
 * docs/superpowers/specs/2026-09-29-glint-paths-design.md. One window, the
 * saw ramp.
```

with

```
 * Only the window's *slope* jumps across the wrap, and that slope
 * discontinuity is the buzz the engine is made of. At DEPTH 0 nothing is
 * smoothed: do not smooth it. DEPTH relaxes it on purpose (see `GlintShape`
 * and docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md):
 * it rounds the window's edge and then fades a plain sine in, and the window
 * still reaches exactly zero at the wrap, which is the property that matters.
 *
 * A voice is the path the formant takes — see `GlintPath` and
 * docs/superpowers/specs/2026-09-29-glint-paths-design.md. At DEPTH 0 one
 * window, the saw ramp.
```

and replace the `windowAt` KDoc

```
    /**
     * The one window: a ramp from 1 to exactly 0 at the cycle's end. It must
     * reach exactly zero at phase 1, because that is the instant every change
     * of k is scheduled on (see the class doc).
     */
```

with

```
    /**
     * The DEPTH 0 window, the saw: a ramp from 1 to exactly 0 at the cycle's
     * end. It must reach exactly zero at phase 1, because that is the instant
     * every change of k is scheduled on (see the class doc). Above DEPTH 0 the
     * render loops use [GlintShape.window], which ends at zero too.
     */
```

- [ ] **Step 2: Update the roadmap and the paths spec**

In `docs/SYNTH_ROADMAP.md`, in the S10 row, replace the text

`with a one-pole blocker through the three-breath render named as the fix if a chord shows it. | S1 + U5 + U6 |`

with

`with a one-pole blocker through the three-breath render named as the fix if a chord shows it. **DEPTH built — awaiting its listen** — a seventh macro (VOWEL's sixth), default 0 so every sound is unchanged, that rounds the window's edge over its lower half and fades a plain sine in over its upper half (the sine scaled to the burst's loudness, its share of the power the square of `2·DEPTH − 1`, a bare sine at 1); both ingredients are zero at the wrap, so `k` still moves only there. It was heard as probe clips by the owner before it was specified. The factory roster (eight per voice, picked by ear from a stratified spread) is the next phase, after DEPTH merges. Design in `docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md`. | S1 + U5 + U6 |`

In `docs/superpowers/specs/2026-09-29-glint-paths-design.md` §6 replace

```
DEPTH; factory presets (authored by ear after the audition, per the synth
depth reversal); registering GLINT with DE-SAMPLE; stereo.
```

with

```
DEPTH; factory presets (authored by ear after the audition, per the synth
depth reversal); registering GLINT with DE-SAMPLE; stereo. DEPTH and the
presets are designed in `2026-10-01-glint-depth-and-presets-design.md`.
```

- [ ] **Step 3: Write the audition generator and register it**

In `GlintPathsAuditionGenerator.kt` change `    private fun heldFor(note: KeyNote, seconds: Float): Snip {` to `    internal fun heldFor(note: KeyNote, seconds: Float): Snip {`.

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthAuditionGenerator.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * The GLINT DEPTH audition (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §4.11):
 * the real engine's held pads, not the listening probe, at DEPTH 0, 0.25, 0.5, 0.75 and 0.9 on a SWEEP
 * and a VOWEL pad, for the owner to hear on his phone before anything merges. The zone is MIDI 57
 * (220 Hz, the probe's pitch) and BLOOM 0.85, so the pad breathes as the probe's did. Levelled to the
 * repo's audition level. Not a test - run with `generateGlintDepthAudition`.
 */
object GlintDepthAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-depth-audition").apply { mkdirs() }
        var written = 0
        var n = 0
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
            val midi = Keys.glintPadMidis(voice)[4]
            for (depth in listOf(0f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                n++
                val note = Keys.glintPad(voice, mapOf("BLOOM" to 0.85f, "DEPTH" to depth), midi)
                val name = "%02d-%s-depth%03d".format(n, voice.name.lowercase(), Math.round(depth * 100))
                val clip = AuditionLevel.level(GlintPathsAuditionGenerator.heldFor(note, 6f))
                WavWriter.write(File(dir, "$name.wav"), clip, WavWriter.BitDepth.PCM_16)
                written++
            }
        }
        println("wrote $written clips to ${dir.absolutePath}")
    }
}
```

In `synth/build.gradle.kts`, immediately after the `generateGlintPathsAudition` registration block (which ends with `args("${rootDir}/testkit/glint-paths-audition")` and `}`), add:

```kotlin
/** Render the GLINT DEPTH audition (held pads, WAVs only) under testkit/glint-depth-audition/. See GlintDepthAuditionGenerator. */
tasks.register<JavaExec>("generateGlintDepthAudition") {
    group = "distribution"
    description = "Render the GLINT DEPTH audition pads under testkit/glint-depth-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintDepthAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-depth-audition")
}
```

In `.gitignore`, after the `testkit/glint-paths-audition/` line add:

```
# The GLINT DEPTH audition pads, rendered by :synth:generateGlintDepthAudition
# and sent to the phone for the listening gate; never committed.
testkit/glint-depth-audition/
```

- [ ] **Step 4: Run it and check the clips**

Run: `./gradlew :synth:generateGlintDepthAudition --console=plain` (foreground, timeout 600000)
Expected: exit 0 and the last line `wrote 10 clips to <the repo>/testkit/glint-depth-audition`.

Run: `ls -l testkit/glint-depth-audition`
Expected: ten files `01-sweep-depth000.wav` through `10-vowel-depth090.wav`, each about 880 KB (about 10 s of 16-bit mono at 44.1 kHz: `heldFor` plays the onset and two breaths, then the loop once more, and never less than the 6 s asked for).

Run: `git status --short`
Expected: the modified files and the new generator only. `testkit/glint-depth-audition/` does not appear (it is ignored).

- [ ] **Step 5: Build the GLINT family once more, then commit**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.Glint*' --console=plain`
Expected: exit 0.

Run: `git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt docs/SYNTH_ROADMAP.md docs/superpowers/specs/2026-09-29-glint-paths-design.md synth/src/test/kotlin/com/snipsnap/synth/GlintPathsAuditionGenerator.kt synth/src/test/kotlin/com/snipsnap/synth/GlintDepthAuditionGenerator.kt synth/build.gradle.kts .gitignore`
Run: `git commit -F <message file>`

Title: `Document DEPTH and add the real-engine audition for it`. Body: the class doc said "Do not smooth it" about the slope jump, which is still the rule at DEPTH 0 and no longer the whole story, so it now says where DEPTH relaxes it and why the window still reaching zero is the property that matters; the roadmap row and the paths spec's out-of-scope line point at the new design; the audition generator renders the real engine's pads at DEPTH 0 to 0.9 on the probe's pitch, because the probe's burst RMS was measured over a breathing pad while the engine's `sineGain` is stationary, and that difference is what the owner's listen is for.

---

### Task 8: The whole suite, the measurements, and the stop for the listen

**Files:** none changed in the repo (the measurement file is untracked and deleted).

**Interfaces:** consumes everything. Produces: a verified tree, the measured figures for the PR body, and the clips for the owner.

- [ ] **Step 1: The full JVM suite**

Run: `./gradlew --no-daemon test` (foreground, timeout 600000; if the harness backgrounds it at 600 s, wait for it; never start a second Gradle)
Expected: exit 0.

Run: `grep -rl --include='TEST-*.xml' -e '<failure' -e '<error' .`
Expected: no output (grep exits 1). Any file named is a failure to report.

- [ ] **Step 2: `:app` still compiles**

Run: `ANDROID_HOME=/Users/joshuacramblet/Library/Android/sdk ./gradlew --no-daemon :app:compileDebugKotlin` (foreground, timeout 600000)
Expected: exit 0. (The DEPTH slider is one `MacroSlider` per `macrosFor` entry, so no app code changes.)

- [ ] **Step 3: Measure render time and DC (a throwaway, never committed)**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintDepthMeasure.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test

/** THROWAWAY, never committed: held render time and DC at DEPTH 0 against above. Prints; asserts nothing. */
class GlintDepthMeasure {
    @Test
    fun measure() {
        fun ms(block: () -> Unit): Long {
            val t = System.nanoTime()
            block()
            return (System.nanoTime() - t) / 1_000_000
        }
        GlintHeld.render(GlintVoice.SWEEP, mapOf("BLOOM" to 0.85f)) // warm up
        for (depth in listOf(0f, 0.5f, 0.75f, 1f)) {
            val times = (1..3).map { ms { GlintHeld.render(GlintVoice.SWEEP, mapOf("BLOOM" to 0.85f, "DEPTH" to depth)) } }
            println("GLINT DEPTH measure: SWEEP held render at DEPTH $depth: ${times.sorted()[1]} ms (median of 3)")
        }
        for (depth in listOf(0f, 0.5f, 1f)) {
            val held = GlintHeld.render(GlintVoice.VOWEL, mapOf("TUNE" to 1f, "BLOOM" to 0.5f, "DEPTH" to depth))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            println("GLINT DEPTH measure: VOWEL TUNE 1 loop |mean|/peak at DEPTH $depth: ${abs(loop.average()) / loop.maxOf { abs(it) }}")
        }
    }
}
```

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintDepthMeasure' --console=plain`, then read the `GLINT DEPTH measure` lines from `synth/build/test-results/test/TEST-com.snipsnap.synth.GlintDepthMeasure.xml`.

Then delete the file: `rm synth/src/test/kotlin/com/snipsnap/synth/GlintDepthMeasure.kt`, and `git status --short` must show nothing untracked or modified.

Record in the report: the median held render times at each DEPTH (a DEPTH above 0 should cost a modest fraction more than DEPTH 0, not double it), and the DC at DEPTH 0, 0.5 and 1 (an expectation to check, nothing depends on it).

- [ ] **Step 4: Stop. The controller sends the clips to the owner.**

The clips are `testkit/glint-depth-audition/*.wav` from Task 7 (regenerate with `./gradlew :synth:generateGlintDepthAudition` if the tree has changed since). The owner listens to the real engine at DEPTH 0, 0.25, 0.5, 0.75 and 0.9 on a SWEEP and a VOWEL pad and says whether it sounds like the round 2 probe clips he approved. Nothing is pushed, no PR is opened and nothing is merged until he has answered; then the controller asks before pushing `claude/glint-depth-macro`.

If he hears a voice sitting wrong, the cheapest fix is a per-voice constant on `GlintPath.sineGain` (spec §9). That would be a new task, reviewed like the others.

---

## As built

What differs from the plan above, in the order the tasks ran (the run's ledger is deleted with its workspace; git history and this section are the record). All commits are on `claude/glint-depth-macro`.

- **Task 1** (b44d41de) and **Task 2** (f052b3a4): as planned.
- **Task 3** (b0f23c83, bf157790): as planned, plus a fix round. The `sineGain` KDoc said the gain was "within about a dB elsewhere" as if measured; it now says right at rest, an approximation elsewhere, the spec expecting about a dB, measured only at the SWEEP and VOWEL probe setups (BLOOM 0.85). A reviewer's static model (inferred, not measured on the engine) put the burst RMS along a path as much as +5.7 dB and -6.4 dB off the resting value at low PEAK with high BODY.
- **Task 4** (eaa07bab, eee31bfc): a first commit strengthened Task 1's reference before the engine moved: every corner also runs with an explicit `DEPTH 0f`, a DEPTH-less JSON patch is loaded through `Patches.fromJsonText`, and LegacyGlint's KDoc says what it shares with production. Then the plan's Task 4.
- **Task 5** (6c849aac, c6672d7f): a first commit widened the held side of the reference (BLOOM 0.15, BODY 0, the all-zeros and all-ones corners, each also with an explicit `DEPTH 0f`) and corrected one KDoc; then the plan's Task 5. A zero-BODY map does not catch a skipped zero term (the held loop makes no `-0f` sample), and no test pins the `sineWeight != 0f` gate in either loop: code review holds it.
- **Task 6** (e5d5421e): two measured findings made the plan's Task 6 wrong as written. The wrap test could not see a one-sample spike, so it now leaves the step into each cycle's last sample out of the inner maximum, and the plan's own table-end mutation makes it fail (VOWEL 9.28, 15.36 and 14.10 against the bar of 1.05; unmutated worst 1.0002 at STEP, DEPTH 1). The level guard's 5 dB bar failed at 5.54 dB (STEP, DECAY 0, DEPTH 1), so the bar is 6 dB, with the engine's measured table and the cause in the test's KDoc and in the spec (sections 2.4, 4.6, 4.7 and 9). The tiny-DEPTH bound went from 1e-4 to 1e-6 (measured worst 1.1e-11).
- **Task 7** (92fa62bb): as planned, plus three comment fixes (the sine comes in above DEPTH 0.5, not from it).
- **Task 8:** nothing committed. `./gradlew --no-daemon test`: exit 0, 364 suites, 3556 tests, 0 failures, 0 errors, 0 skipped; `:app:compileDebugKotlin` with `ANDROID_HOME` set: exit 0. Measured once on a loaded machine (warm-up, median of three): a SWEEP held render takes 59, 58, 66 and 65 ms at DEPTH 0, 0.5, 0.75 and 1; the held loop's DC (|mean| over peak, VOWEL at the top of TUNE) is 0.108, 0.055 and 0 at DEPTH 0, 0.5 and 1.
