# SILK Phase 1a Implementation Plan — the string toolkit, lifted out of PLUCK

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move PLUCK's Karplus-Strong loop — its tuning budget, its pick exciter, the loop itself, the decay-following trim and the body drive — into a shared `Strings.kt` that SILK will build on, **without changing a single sample PLUCK renders.**

**Architecture:** `Pluck.ks` today builds the exciter, solves the loop length and runs the feedback loop in one function, writing into its own output buffer. This phase splits that into four named pieces in `internal object Strings` — `damping`, `tune`, `pluckExciter`, and a per-sample `Loop` class — plus `trimToDecay` and `bodyRing` generalised off `PluckVoice`. `Pluck.ks`, `Pluck.trimToDecay` and `Pluck.withBody` stay, as one-line delegates, so every existing caller and test is untouched. Nothing new is added to the loop: dispersion, collision, courses and the pitch envelope are Phase 1b's.

**Tech Stack:** Kotlin/JVM (`:synth` module, Gradle, JDK 17, JUnit via `kotlin("test")`), the existing `Dsp` and `Modes` helpers.

**Spec:** `docs/superpowers/specs/2026-09-27-silk-string-engine-design.md` — sections "A shared string toolkit, extracted from PLUCK" (including "The guard on the move") and "Phasing and gates" (row 1a).

## Why this is its own phase

The spec's rule: the extraction ships as its own PR **with no audio change**, and SILK is built on it after. Every PLUCK pad in every saved `kit.json` regenerates from its recipe, so a one-bit drift in the loop would silently change sounds people have already made. Two independent proofs guard against that (Tasks 1 and 2), and both stay in the suite after this phase so that Phase 1b's additions — which must default to "off" — are held to the same bar.

## Global Constraints

- **Bit-identical, not "close".** Every assertion in this phase is exact equality (`assertEquals` on `contentHashCode()`, `assertContentEquals` on arrays). No tolerance, no "within a cent". If a refactor step changes a single sample, the step is wrong, not the test.
- **Keep the order of floating-point operations.** Float arithmetic is not associative. `x + fb * lp(...)` must stay exactly that expression; `0.5f * (a + b)` must not become `0.5f * a + 0.5f * b`; `rate / freq` must stay `Int / Float` (a `Float`) before it meets the `Double` filter delay. The code blocks below are written to preserve the originals' order; do not "tidy" them.
- **`Strings.MIN_LOOP_SAMPLES` keeps its `require`** with a message that names the cause, as `Pluck.ks`'s does today. `PluckTest`'s two boundary tests must still pass unmodified.
- **No behaviour is added.** No new macro, no new stage, no new parameter with a non-default value. If a helper needs a parameter PLUCK does not use, it is not part of this phase.
- **The 48 PLUCK presets are not touched**, and `PluckTest`, `PluckPresetsTest`, `TuningAccuracyTest` and `DeterminismTest` are not edited except where a task says so.
- **Measured baseline (2026-09-27, before any change):** `PluckTest` 35/35, `PluckPresetsTest` 4/4, `TuningAccuracyTest` 4/4 pass.
- **Commands run from the repo root.** One class: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`. The whole suite: `./gradlew --no-daemon test`.
- **Every commit ends with these two lines** (project attribution rule):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim
  ```
- **Branch:** `claude/sound-design-tools-mdmrbw`. This plan lands first in its own docs PR, for review; the implementation (Tasks 1–5) is one further PR, opened after Task 5 — restart the branch from the base if the plan PR has merged by then.

---

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt` | **new** — `internal object Strings`: `MIN_LOOP_SAMPLES`, `Damping`/`damping`, `Tuning`/`tune`, `pluckExciter`, `Loop`, `pluck`, `trimToDecay`, `bodyRing` |
| `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` | `ks`, `trimToDecay`, `withBody` become delegates; their bodies and the private `rms`/`fadeCeiling` helpers move out; `MIN_LOOP_SAMPLES` is removed in favour of `Strings`' |
| `synth/src/test/kotlin/com/snipsnap/synth/LegacyPluckLoop.kt` | **new** — a frozen, verbatim copy of today's `Pluck.ks`, the reference the extraction is compared against |
| `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt` | **new** — the equivalence grid, the piece-level tests |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` | one new test: the pinned render hashes |
| `docs/SYNTH_ROADMAP.md` | an S13 row for SILK, now that implementation starts |

---

### Task 1: Pin PLUCK's renders before anything moves

This is the RESIN held-pad pattern (`ResinTest`, "the one-shot render is pinned"): record what PLUCK renders *today*, so every later task is checked against yesterday rather than against itself.

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` (one new test at the top of the class)

- [ ] **Step 1: Write the test with placeholder hashes.** The render set covers every voice at its defaults, at every macro 0, at every macro 1, and at its first preset — so STRIKE's comb, DOUBLE's second `ks` call, BODY's drive, both trim branches and the decimator are all on the path.

```kotlin
    /**
     * DeterminismTest proves two renders agree with each other; this proves
     * they agree with yesterday. Captured before SILK Phase 1a moved the
     * loop into Strings.kt (docs/superpowers/plans/2026-09-27-silk-phase-1a.md,
     * Task 1) - every PLUCK pad in a kit.json depends on it.
     */
    @Test
    fun `the render is pinned - the Strings extraction must not move it by a bit`() {
        val expected: Map<String, Int> = mapOf(
            // Filled in by Step 2 - one line per voice x render.
        )
        val actual = LinkedHashMap<String, Int>()
        for (voice in PluckVoice.entries) {
            val names = Pluck.macrosFor(voice).map { it.name }
            val renders = listOf(
                "defaults" to emptyMap<String, Float>(),
                "all 0" to names.associateWith { 0f },
                "all 1" to names.associateWith { 1f },
                "first preset" to PluckPresets.forVoice(voice).first().macros,
            )
            for ((label, macros) in renders) {
                actual["$voice $label"] = Pluck.render(voice, macros).samples.contentHashCode()
            }
        }
        // One comparison of the whole table, so a failure prints every
        // hash at once - the capture in Step 2 is one run, not sixteen.
        assertEquals(expected, actual)
    }
```

- [ ] **Step 2: Capture the real hashes.** Run the test once. It fails, and the message prints the whole `actual` map — sixteen entries, `NYLON defaults=-123456789` and so on. Paste them into `expected` as `"NYLON defaults" to -123456789,` lines, in the printed order.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.PluckTest"`
Expected: FAIL once, with `expected: <{}> but was: <{NYLON defaults=…, …}>` listing all sixteen.

- [ ] **Step 3: Confirm it passes and is stable.** Run it twice more.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.PluckTest"`
Expected: PASS, 36/36 both times (a hash that changes between runs means something nondeterministic is on the path — stop and find it; it would be a bug today, before any refactor).

- [ ] **Step 4: Commit.**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt
git commit -m "PLUCK: pin every voice's render before the loop moves

Defaults, every macro at 0 and at 1, and the first preset, per voice,
as contentHashCode - the RESIN held-pad pattern. SILK Phase 1a moves
PLUCK's loop into a shared Strings.kt; this is the guard that it moves
without changing a sample.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 2: Freeze today's loop as a reference, and build `Strings` beside it

The pinned hashes cover sixteen renders. The loop itself is reachable with inputs no voice uses today — and SILK will use them (other roots, other rates, other pick positions). So this task also keeps a verbatim copy of today's `Pluck.ks` in the test tree and compares the new `Strings.pluck` against it sample for sample over a wide grid. `Pluck.ks` itself is **not** changed in this task: the two implementations live side by side until the grid passes.

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/LegacyPluckLoop.kt`
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt`

- [ ] **Step 1: Freeze the reference.** Copy the body of `Pluck.ks` (today `Pluck.kt:463-611`, from `internal fun ks(` to its closing brace) into a new test-only object, **verbatim** — the only edits allowed are the function's home and its KDoc. Keep every comment inside the body; it is the record of why each line is there.

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PLUCK's Karplus-Strong loop exactly as it stood before SILK Phase 1a
 * moved it into [Strings] (docs/superpowers/plans/2026-09-27-silk-phase-1a.md).
 * Frozen: never edit this body. [StringsTest] holds [Strings.pluck] to it
 * sample for sample, so every later change to the shared loop has to prove
 * that with its new stages off it is still this.
 */
internal object LegacyPluckLoop {
    private const val MIN_LOOP_SAMPLES = 2.0

    fun ks(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
        rate: Int,
        position: Float = 0f,
    ): FloatArray {
        // ... the body of Pluck.ks, copied verbatim ...
    }
}
```

- [ ] **Step 2: Write the failing equivalence test.**

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StringsTest {

    /**
     * The extraction's whole claim: the shared loop IS PLUCK's loop. Wider
     * than any voice reaches - roots from 55 Hz to 12 kHz, both render
     * rates, the pick at the bridge, a quarter and the centre, both ends of
     * DAMP and of the pick filter - because SILK will reach them.
     */
    @Test
    fun `Strings pluck is the frozen PLUCK loop, sample for sample`() {
        var cases = 0
        for (rate in listOf(Dsp.RATE, Dsp.RATE * Dsp.OVERSAMPLE)) {
            for (freq in listOf(55f, 110f, 147f, 196f, 440f, 1000f, 3000f, 12_000f)) {
                for (damp in listOf(0f, 0.45f, 1f)) {
                    for (position in listOf(0f, 0.03f, 0.25f, 0.5f)) {
                        for (pickHz in listOf(1200f, 14_000f)) {
                            val seed = Dsp.seedFor("STRINGS", freq, damp, position)
                            val legacy = LegacyPluckLoop.ks(freq, 0.25f, damp, 4200f, pickHz, seed, rate, position)
                            val shared = Strings.pluck(freq, 0.25f, Strings.damping(damp, 4200f), pickHz, seed, rate, position)
                            assertContentEquals(legacy, shared, "rate=$rate freq=$freq damp=$damp position=$position pickHz=$pickHz")
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(384, cases)
    }
}
```

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: FAIL to compile — `Unresolved reference: Strings`.

- [ ] **Step 3: Write `Strings.kt`.** The expressions below are the originals', in the originals' order — read Global Constraints before changing any of them.

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * STRINGS — the Karplus-Strong string, shared.
 *
 * PLUCK's loop, lifted out whole so SILK can build on it
 * (docs/superpowers/specs/2026-09-27-silk-string-engine-design.md, "A shared
 * string toolkit"): the tuning budget ([tune]), the pick exciter
 * ([pluckExciter]), the loop itself ([Loop]), the decay-following cut
 * ([trimToDecay]) and the body drive ([bodyRing]). [pluck] composes the first
 * three into the loop `Pluck.ks` always was, and [StringsTest] holds it to a
 * frozen copy of that loop sample for sample.
 *
 * The long comments on why each stage is there live with the code they
 * explain, moved from Pluck.kt unchanged.
 */
internal object Strings {

    /**
     * The smallest loop length [tune] accepts, in samples. Below this,
     * splitting the loop into an integer delay plus a fractional allpass
     * stops being meaningful - see [tune]'s `require`.
     */
    const val MIN_LOOP_SAMPLES = 2.0

    /** The loop's low-pass corner and feedback gain - DAMP's two parameters. */
    class Damping(val loopHz: Float, val fb: Float)

    /**
     * DAMP closes the loop filter and pulls the feedback gain down together
     * - one knob, two parameters, always musical.
     */
    fun damping(damp: Float, bodyLoopHz: Float): Damping = Damping(
        loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f),
        fb = Dsp.lin(1f - damp, 0.94f, 0.998f),
    )

    /** The loop's integer delay [n] and the allpass coefficient [a] that carries the fraction. */
    class Tuning(val exact: Double, val n: Int, val a: Float)

    /**
     * The tuning budget: the loop's total delay must equal one period of
     * [freq], and every stage in it pays for its own delay at the
     * fundamental. (Move the long comment from Pluck.ks - "The loop length
     * is almost never a whole number of samples ..." through "... reproduces
     * the pre-fix engine's harmonic balance." - here, unchanged.)
     */
    fun tune(freq: Float, loopHz: Float, rate: Int): Tuning {
        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w

        val exact = (rate / freq) - filterDelay - 0.5
        // (Move the "n and frac must come from the SAME exact" comment here.)
        require(exact >= MIN_LOOP_SAMPLES) {
            "String loop length ($exact samples, freq=$freq Hz at rate=$rate) fell " +
                "below the Karplus-Strong minimum of $MIN_LOOP_SAMPLES samples - a " +
                "note this high (or a filter delay this large) needs either a lower " +
                "root, a narrower TUNE span, or this allpass revisited; " +
                "coercing the loop length up here without also correcting the " +
                "fractional remainder used to produce an unconditionally unstable " +
                "feedback allpass."
        }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        return Tuning(exact, n, a)
    }

    /**
     * One period of filtered, zero-mean noise, combed by the pick position -
     * the 1983 exciter with Jaffe & Smith's position comb. Returns at most
     * [maxLen] samples; they enter the loop as input, never as its state.
     * (Move the "Zero-mean the exciter" and "Pick position (Jaffe & Smith
     * 1983)" comments here, unchanged.)
     */
    fun pluckExciter(n: Int, freq: Float, pickHz: Float, position: Float, seed: Int, rate: Int, maxLen: Int): FloatArray {
        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        val combDelay = if (position > 0f) (position * rate / freq).roundToInt().coerceIn(1, n) else 0
        val excLen = min(n + combDelay, maxLen)
        val out = FloatArray(excLen)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (combDelay > 0 && i - combDelay in 0 until n) burst[i - combDelay] else 0f
            out[i] = x - xd
        }
        return out
    }

    /**
     * The feedback loop, one sample at a time: `y = x + fb * lp(ap(avg))`,
     * where `avg` is the two-tap average of the output `n` and `n + 1`
     * samples back, `ap` the tuning allpass and `lp` the loop's one-pole.
     * For the first `n + 1` samples there is no history to feed back, and
     * the output is the input - exactly as `Pluck.ks`'s loop starts at
     * `n + 1`. The history is a ring of `n + 2` outputs: enough for both
     * taps and no more.
     */
    class Loop(private val n: Int, private val a: Float, private val fb: Float, private val loopHz: Float, rate: Int) {
        private val size = n + 2
        private val history = FloatArray(size)
        private var i = 0
        private var apX1 = 0f
        private var apY1 = 0f
        private val loopLp = Dsp.OnePole(rate)

        fun next(x: Float): Float {
            val y = if (i <= n) {
                x
            } else {
                val d = 0.5f * (history[(i - n) % size] + history[(i - n - 1) % size])
                // First-order allpass: y[i] = a*(x[i] - y[i-1]) + x[i-1]. Order
                // matters here - it's the *tuned* sample that must feed both
                // the loop filter and the output, or the correction never
                // reaches the loop it was meant to fix.
                val tuned = a * (d - apY1) + apX1
                apX1 = d
                apY1 = tuned
                x + fb * loopLp.lp(tuned, loopHz)
            }
            history[i % size] = y
            i++
            return y
        }
    }

    /** The 1983 plucked string: [pluckExciter] into a [Loop] tuned by [tune]. */
    fun pluck(freq: Float, seconds: Float, damping: Damping, pickHz: Float, seed: Int, rate: Int, position: Float = 0f): FloatArray {
        val t = tune(freq, damping.loopHz, rate)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t.n + 2))
        val exc = pluckExciter(t.n, freq, pickHz, position, seed, rate, out.size)
        val loop = Loop(t.n, t.a, damping.fb, damping.loopHz, rate)
        for (i in out.indices) out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        return out
    }
}
```

Two things to check by reading, before running:

1. `x + fb * loopLp.lp(tuned, loopHz)` is the original `out[i] += fb * loopLp.lp(tuned, loopHz)` — `out[i]` held the exciter sample (or `0f`), so the sum is the same float addition.
2. The ring index: when `i > n`, `i - n - 1 >= 0`, and the slot written at `i % size` held the output `n + 2` samples back, which neither tap reads any more.

- [ ] **Step 4: Run the grid.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: PASS — 384 cases, every one identical. A failure names the case; the first thing to check is an operation reordered against the original, the second is an off-by-one in the ring.

- [ ] **Step 5: Add the piece-level tests.** These pin the pieces SILK will call on their own.

```kotlin
    @Test
    fun `tune fails loudly below the KS minimum, and names the cause`() {
        val e = assertFailsWith<IllegalArgumentException> { Strings.tune(70_000f, 4200f, 176_400) }
        assertTrue(e.message!!.contains("Karplus-Strong minimum"), e.message)
    }

    @Test
    fun `tune keeps the allpass stable at every fraction`() {
        for (freq in listOf(55f, 147f, 440f, 3000f, 12_000f)) {
            val t = Strings.tune(freq, 4200f, Dsp.RATE * Dsp.OVERSAMPLE)
            assertTrue(t.n >= Strings.MIN_LOOP_SAMPLES, "n=${t.n} at $freq Hz")
            assertTrue(t.a > 0f && t.a <= 1f, "a=${t.a} at $freq Hz - |a| < 1 is what keeps the allpass stable")
        }
    }

    @Test
    fun `the loop passes its input straight through until it has history`() {
        val loop = Strings.Loop(n = 8, a = 0.5f, fb = 0.99f, loopHz = 4000f, rate = Dsp.RATE)
        val input = FloatArray(9) { (it + 1).toFloat() }
        for (x in input) assertEquals(x, loop.next(x))
    }

    @Test
    fun `the exciter is zero-mean with the pick off, and the comb lengthens it`() {
        val plain = Strings.pluckExciter(n = 300, freq = 147f, pickHz = 6000f, position = 0f, seed = 7, rate = Dsp.RATE, maxLen = 10_000)
        assertEquals(300, plain.size)
        assertTrue(kotlin.math.abs(plain.sum()) < 1e-3f, "sum=${plain.sum()}")
        val combed = Strings.pluckExciter(n = 300, freq = 147f, pickHz = 6000f, position = 0.25f, seed = 7, rate = Dsp.RATE, maxLen = 10_000)
        assertTrue(combed.size > plain.size, "the comb's delayed copy extends the exciter")
    }
```

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: PASS, 5/5.

- [ ] **Step 6: Commit.**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Strings.kt \
        synth/src/test/kotlin/com/snipsnap/synth/LegacyPluckLoop.kt \
        synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt
git commit -m "Strings: PLUCK's loop as shared pieces, proved against a frozen copy

damping, tune, pluckExciter and a per-sample Loop, composed by
Strings.pluck. A verbatim copy of today's Pluck.ks is kept in the test
tree as LegacyPluckLoop, and 384 cases across both render rates, roots
from 55 Hz to 12 kHz, DAMP, pick position and pick brightness must match
it sample for sample. Pluck.ks itself is not changed yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 3: `Pluck.ks` delegates

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`ks`, and the `MIN_LOOP_SAMPLES` constant near the top of `object Pluck`)

- [ ] **Step 1: Replace the body.** `ks` keeps its signature and its KDoc (update the KDoc's first line to say the loop now lives in [Strings]); the body becomes one call.

```kotlin
    internal fun ks(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
        rate: Int,
        position: Float = 0f,
    ): FloatArray = Strings.pluck(freq, seconds, Strings.damping(damp, bodyLoopHz), pickHz, seed, rate, position)
```

- [ ] **Step 2: Remove `Pluck.MIN_LOOP_SAMPLES`** and point its one remaining mention (the `ks` KDoc) at `Strings.MIN_LOOP_SAMPLES`. Remove any imports the build now reports unused (`atan2`, `floor`, `roundToInt` and the like — let the compiler's warnings name them; do not guess).

- [ ] **Step 3: Run PLUCK's suite and the grid.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.StringsTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.TuningAccuracyTest"`
Expected: PASS — `PluckTest` 36/36 (the pin from Task 1 included), `StringsTest` 5/5, `PluckPresetsTest` 4/4, `TuningAccuracyTest` 4/4.

- [ ] **Step 4: Commit.**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt
git commit -m "PLUCK: ks delegates to Strings.pluck

The loop's only home is now Strings.kt. The pinned renders and the
frozen-copy grid both hold.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 4: The trim and the body drive move, generalised off `PluckVoice`

SILK needs both: a render that ends where its string stops ringing, and a string driving a fixed body table by its first difference. Today both are tied to PLUCK — `trimToDecay` reads `RING_FLOOR_SECONDS`/`RING_CEILING_SECONDS`, `withBody` takes a `PluckVoice`. They move with those as parameters; PLUCK passes its own.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`trimToDecay`, `fadeCeiling`, `withBody`, `rms`)

- [ ] **Step 1: Move `trimToDecay` and `fadeCeiling`.** In `Strings`, `trimToDecay(buf, rate, floorSeconds, ceilingSeconds)` is today's body with `RING_FLOOR_SECONDS` → `floorSeconds` and `RING_CEILING_SECONDS` → `ceilingSeconds`, nothing else changed; `fadeCeiling` moves as a `private fun`. Move the KDoc with them, rewording "`synthesize`" to "the caller".

In `Pluck`:

```kotlin
    internal fun trimToDecay(buf: FloatArray, rate: Int): FloatArray =
        Strings.trimToDecay(buf, rate, RING_FLOOR_SECONDS, RING_CEILING_SECONDS)
```

- [ ] **Step 2: Move the body drive as `bodyRing`.** In `Strings`, `bodyRing(string, table, amount, rate, ceilingSeconds, differentiate = true)` is today's `withBody` body with `bodyFor(voice)` → the `table` parameter and `RING_CEILING_SECONDS` → `ceilingSeconds`. Keep both early returns in the same order (`amount <= 0f`, then `table.isEmpty()`) — `PluckTest`'s "BODY zero is the string, byte for byte" checks the returned array is the *same object* (`===`). `rms` moves as a `private fun`. Move the long KDoc on the first-difference drive with it, unchanged.

In `Pluck`:

```kotlin
    internal fun withBody(string: FloatArray, voice: PluckVoice, amount: Float, rate: Int, differentiate: Boolean = true): FloatArray =
        Strings.bodyRing(string, bodyFor(voice), amount, rate, RING_CEILING_SECONDS, differentiate)
```

- [ ] **Step 3: Run PLUCK's suite and the grid.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.StringsTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.TuningAccuracyTest"`
Expected: PASS, same counts as Task 3. The pinned hashes are the proof here: every voice's render runs through both `trimToDecay` and (at BODY > 0) `withBody`.

- [ ] **Step 4: Commit.**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Strings.kt synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt
git commit -m "Strings: the decay-following trim and the body drive move too

trimToDecay takes its floor and ceiling, bodyRing takes its mode table
and ceiling, so SILK can call both with its own. PLUCK passes its own
constants and bodyFor(voice); the pinned renders do not move.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 5: Whole-suite verification, the roadmap row, the PR

- [ ] **Step 1: Run everything.**

Run: `./gradlew --no-daemon test`
Expected: PASS across all modules. Nothing outside `:synth` should notice, but `Keys` (the Harp instrument) and the kit tests render PLUCK too, and this is where they would say so.

Also run the native harness, which the session hook builds: `ctest --test-dir build/native-tests`. Expected: unchanged (this phase touches no C++); it is run so the PR's claim "nothing else moved" is checked rather than assumed.

- [ ] **Step 2: Re-read the diff adversarially.** `git diff origin/claude/mobile-mpc-drum-sampler-t58x74 -- synth/src/main` should show: one new file, and in `Pluck.kt` only deletions plus three one-line delegates. Any other edit in `Pluck.kt` (a renamed variable, a reordered expression, a changed default) is out of scope — revert it.

- [ ] **Step 3: Add the roadmap row.** In `docs/SYNTH_ROADMAP.md`'s phasing table, after the S12 row:

```markdown
| S13 | **Phase 1a shipped** — SILK's string toolkit: PLUCK's Karplus-Strong loop lifted into `Strings.kt` (the tuning budget, the pick exciter, a per-sample `Loop`, the decay-following trim, the body drive) with no change to a single PLUCK sample — pinned render hashes per voice, and a frozen copy of the old loop matched sample for sample over 384 cases. SILK itself (OUD, GUZHENG, SANTUR, SHAMISEN; SCALE and INFLECT) is Phases 1b–4 — design in `docs/superpowers/specs/2026-09-27-silk-string-engine-design.md`, research in `docs/superpowers/plans/2026-09-27-silk-research.md` | S3.5 (PLUCK) |
```

- [ ] **Step 4: Commit, push, open the PR.**

```bash
git add docs/SYNTH_ROADMAP.md
git commit -m "Roadmap: S13, SILK Phase 1a

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
git push -u origin claude/sound-design-tools-mdmrbw
```

The PR body says what the reviewer needs: no audio change; how that is proved (the pin, the frozen-copy grid, the full suite); that `LegacyPluckLoop` is deliberate and permanent; and that Phase 1b (SCALE, INFLECT, OUD, GUZHENG, dispersion, the pitch envelope, courses) is next.

---

## What Phase 1b inherits

- `Strings.Loop` gains optional in-loop stages (dispersion, later collision), each **off by default** — and `StringsTest`'s frozen-copy grid is the proof that "off" means "PLUCK's loop, exactly".
- `Strings.tune` gains the dispersion term in its budget (`dispersionDelay(f0)`), again zero when there is no cascade.
- The pitch envelope needs `Loop` to re-solve its tuning as the delay moves; that is a new code path beside the fixed one, not a change to it, so PLUCK never takes it.
