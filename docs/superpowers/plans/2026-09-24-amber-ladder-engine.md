# AMBER Ladder Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Design:** [`docs/superpowers/specs/2026-09-24-amber-ladder-engine-design.md`](../specs/2026-09-24-amber-ladder-engine-design.md) — read it first; this plan does not repeat its reasoning, only its decisions.

**Goal:** Add `Dsp.Ladder`, a measured four-pole transistor-ladder low-pass; AMBER, a ninth synth engine with three voices (BASS/LEAD/BRASS) built around it; and CONTOUR, a rack section that puts the same filter on a captured pad.

**Architecture:** One filter class in `Dsp.kt`. One engine file `Amber.kt` shaped exactly like `Velvet.kt`. One rack section file `Contour.kt` shaped exactly like `Ring.kt`. Registry touchpoints in `Patches.kt`, `Presets.kt`, `Velocity.kt`, `FxChain.kt`, `Treatments.kt`, `PadSheet.kt`, `SynthScreen.kt`.

**Tech Stack:** Kotlin/JVM 2.0.21, Gradle wrapper, `kotlin.test`. No new dependencies.

## How to hand this to a session

Paste this as the opening prompt of a fresh session on this repository:

```
Implement docs/superpowers/plans/2026-09-24-amber-ladder-engine.md task by
task, on branch claude/sound-design-tools-mdmrbw (restart it from the
default branch, claude/mobile-mpc-drum-sampler-t58x74, if its PR has merged).
Read the design spec it links first, and .claude/skills/steward/SKILL.md
before your first push. Commit after every task with the message the plan
gives. Run ./gradlew --no-daemon test before every push. Open one PR when
Task 10's verification is green.
```

## Running tests here

This is a cloud session with no Android SDK, so `:app` is not in the Gradle
graph. Use exactly these lines (`.claude/skills/steward/SKILL.md` explains
why CI's own `-x :app:test` line fails here):

```bash
./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.LadderTest"   # one class
./gradlew --no-daemon :synth:test                                             # the module
./gradlew --no-daemon test                                                    # everything, before every push
```

`SynthScreen.kt` (Task 6) **cannot be compiled in this session.** It is
proved by the `android-build` CI job only. Make that edit by reading the
file, mirroring FATHOM's entries exactly, and re-reading your diff twice.

## Global Constraints

- **Naming (legal guardrail, `docs/SYNTH_ROADMAP.md`):** no trademarked names, model numbers, or near-misses, in code, comments, KDoc, presets, or commit messages. The engine is `AMBER`, voices `BASS`/`LEAD`/`BRASS`, the filter `Dsp.Ladder`, the section `contour`. Do not write the name of the company whose filter this models anywhere in the diff. The word "ladder" is the published technical term and is fine.
- **Every macro is a `Float` in `0f..1f`**, every value in that range is a sound. Clamp with `coerceIn(0f, 1f)`.
- **Six macros per voice**, all shared: `TUNE STACK CUTOFF CREAM CONTOUR DECAY`.
- **`TUNE` snaps to semitones** over `TUNE_SEMITONES = 24`, exactly as `Velvet.frequencyFor`.
- **Renders are deterministic.** Phases come from `Dsp.phases(3, Dsp.seedFor("AMBER", voice.name))`. No `Random` inside `render`.
- **Oversampled.** `synthesize(voice, macros, rate)` is `internal`, called by `render` at `RATE * Dsp.OVERSAMPLE`, then `Dsp.decimate`. Same shape as `Velvet.synthesize`.
- **Thresholds are measurements.** Every number in a test came from the design spec's measured table. If a Kotlin render misses one, print both values, write the measurement into the test's comment, and set the threshold from the measurement with ~20 % margin. Never loosen a threshold to pass without writing down what was measured.
- **Keep each task's diff to the files it names.** No drive-by refactors.
- **Commit messages are plain declarative prose** (see `git log`): no `feat:` prefixes, no ticket numbers, no model identifiers.

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt` | **Modify.** Add `class Ladder` after `class TptSvf`; update `OVERSAMPLE`'s KDoc. |
| `synth/src/test/kotlin/com/snipsnap/synth/LadderTest.kt` | **Create.** The filter's measured suite. |
| `synth/src/main/kotlin/com/snipsnap/synth/Amber.kt` | **Create.** Voice enum, macro specs, frequency snapping, the stack, `synthesize`, `render`. |
| `synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt` | **Create.** Engine tests. |
| `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt` | **Modify.** `AmberPatch` + decoder branch. |
| `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` | **Modify.** `is AmberPatch ->` branch + KDoc line. |
| `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt` | **Modify.** AMBER canary. |
| `synth/src/main/kotlin/com/snipsnap/synth/AmberPresets.kt` | **Create.** 36 presets. |
| `synth/src/test/kotlin/com/snipsnap/synth/AmberPresetsTest.kt` | **Create.** |
| `synth/src/main/kotlin/com/snipsnap/synth/Presets.kt` | **Modify.** Registry. |
| `synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt` | **Modify.** |
| `shell/src/test/kotlin/com/snipsnap/shell/UserPresetsTest.kt` | **Modify.** Roster. |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | **Modify.** `Engine.AMBER`. |
| `synth/src/main/kotlin/com/snipsnap/synth/Contour.kt` | **Create.** The rack section. |
| `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt` | **Modify.** Field, section, order KDoc. |
| `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt` | **Modify.** `"contoured"`. |
| `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`, `TreatmentsTest.kt` | **Modify.** |
| `shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt`, `shell/src/test/.../PadSheetTest.kt` | **Modify.** The chip. |
| `README.md`, `docs/SYNTH_ROADMAP.md` | **Modify.** |

---

### Task 1: `Dsp.Ladder` — the filter, measured before it is used

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/LadderTest.kt`

**Interfaces:**
- Produces: `Dsp.Ladder(rate: Int = RATE)` with `process(input: Float, freqHz: Float, resonance: Float): Float`, `out`, `reset()`, `Ladder.MAX_RESONANCE = 4.3f`.

- [ ] **Step 1: Write the failing test**

Create `LadderTest.kt`. Every assertion is a row of the design spec's measured table.

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ladder's signature, pinned from the design spec's prototype
 * (docs/superpowers/specs/2026-09-24-amber-ladder-engine-design.md,
 * "Measured, not guessed"): 24 dB/oct, a 1/(1+r) passband loss as
 * resonance rises, bounded self-oscillation past r = 4. All at the rate
 * every engine synthesises at, because that is where the filter runs.
 */
class LadderTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun db(v: Float) = 20f * log10(maxOf(v, 1e-12f))

    /** Steady-state gain of a small sine through the filter, as a ratio. */
    private fun steadyGain(fc: Float, r: Float, toneHz: Double, amp: Float = 0.02f): Float {
        val ladder = Dsp.Ladder(rate)
        val settle = (0.15f * rate).toInt()
        val measure = (0.15f * rate).toInt()
        var sumSq = 0.0
        for (n in 0 until settle + measure) {
            val x = amp * sin(2.0 * PI * toneHz * n / rate).toFloat()
            val y = ladder.process(x, fc, r)
            if (n >= settle) sumSq += (y * y).toDouble()
        }
        val outRms = sqrt(sumSq / measure).toFloat()
        return outRms / (amp / sqrt(2f))
    }

    /** The tail after a tick: zero input for a second, the last half kept. */
    private fun tailAfterTick(fc: Float, r: Float): FloatArray {
        val ladder = Dsp.Ladder(rate)
        val n = rate
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ladder.process(if (i == 0) 1e-3f else 0f, fc, r)
        return out.copyOfRange(n / 2, n)
    }

    private fun rms(xs: FloatArray): Float = sqrt(xs.sumOf { (it * it).toDouble() } / xs.size).toFloat()

    private fun zeroCrossHz(xs: FloatArray): Float {
        var c = 0
        for (i in 1 until xs.size) if ((xs[i - 1] < 0f) != (xs[i] < 0f)) c++
        return c / 2f / (xs.size.toFloat() / rate)
    }

    @Test
    fun `passband is flat and the slope is 24 dB per octave`() {
        // Measured: -0.24 dB at 125 Hz; -49.19 at 4 kHz, -72.40 at 8 kHz (23.2 dB/oct).
        val pass = db(steadyGain(1_000f, 0f, 125.0))
        val at4k = db(steadyGain(1_000f, 0f, 4_000.0))
        val at8k = db(steadyGain(1_000f, 0f, 8_000.0))
        assertTrue(pass > -1f, "passband should be flat: $pass dB at 125 Hz")
        assertTrue(at4k - at8k > 20f, "one octave above the knee should cost > 20 dB: $at4k -> $at8k")
        assertTrue(at8k < -60f, "three octaves up should be gone: $at8k dB")
    }

    @Test
    fun `resonance peaks at the cutoff`() {
        // Measured: +6.85 dB at 975 Hz for fc = 1 kHz, r = 3.5; -12.04 dB there at r = 0.
        var bestHz = 0
        var bestDb = -1e9f
        for (f in 600..1400 step 25) {
            val g = db(steadyGain(1_000f, 3.5f, f.toDouble()))
            if (g > bestDb) { bestDb = g; bestHz = f }
        }
        assertTrue(abs(bestHz - 1_000) <= 50, "peak should sit within 5% of fc, found $bestHz Hz")
        val flat = db(steadyGain(1_000f, 0f, 1_000.0))
        assertTrue(bestDb - flat > 12f, "resonance should lift fc by > 12 dB: $flat -> $bestDb")
    }

    @Test
    fun `resonance thins the bass - the passband drops by about 1 over 1 plus r`() {
        // Measured: -0.17 dB at r = 0, -12.98 dB at r = 3.5 (100 Hz tone, fc = 1 kHz).
        val loss = db(steadyGain(1_000f, 0f, 100.0)) - db(steadyGain(1_000f, 3.5f, 100.0))
        assertTrue(loss in 10f..16f, "r = 3.5 should cost 10-16 dB of passband, cost $loss")
    }

    @Test
    fun `DC gain is exactly 1 over 1 plus r`() {
        // Measured: 0.5000 at r = 0, 0.1111 at r = 3.5 for 0.5 DC in.
        for ((r, expected) in listOf(0f to 0.5f, 3.5f to 0.5f / 4.5f)) {
            val ladder = Dsp.Ladder(rate)
            var y = 0f
            for (i in 0 until (0.3f * rate).toInt()) y = ladder.process(0.5f, 1_000f, r)
            assertTrue(abs(y - expected) < expected * 0.01f, "DC at r=$r: expected $expected, got $y")
        }
    }

    @Test
    fun `past r = 4 the filter sings at the cutoff, bounded`() {
        // Measured: 498 Hz at fc = 500, 1009 Hz at fc = 1000; peak 0.09-0.11.
        for (fc in listOf(500f, 1_000f)) {
            val tail = tailAfterTick(fc, 4.3f)
            assertTrue(tail.all { it.isFinite() }, "fc=$fc blew up")
            assertTrue(rms(tail) > 0.01f, "fc=$fc should self-oscillate, tail rms ${rms(tail)}")
            assertTrue(tail.maxOf { abs(it) } < 1f, "fc=$fc self-oscillation must stay bounded")
            val hz = zeroCrossHz(tail)
            assertTrue(abs(hz - fc) < fc * 0.03f, "fc=$fc should sing within 3%, sang at $hz Hz")
        }
    }

    @Test
    fun `below r = 4 a tick dies`() {
        val tail = tailAfterTick(1_000f, 3.5f)
        assertTrue(rms(tail) < 1e-3f, "r = 3.5 must not self-oscillate, tail rms ${rms(tail)}")
    }

    @Test
    fun `stable under a full-scale saw with the cutoff swept to 16 kHz at full resonance`() {
        // Measured: finite, peak 0.419.
        val ladder = Dsp.Ladder(rate)
        var ph = 0.0
        var peak = 0f
        for (n in 0 until rate) {
            ph += 55.0 / rate
            val saw = (2.0 * (ph - Math.floor(ph)) - 1.0).toFloat()
            val fc = 20f * Math.pow(800.0, n.toDouble() / rate).toFloat()
            val y = ladder.process(saw, fc, 4.3f)
            assertTrue(y.isFinite(), "blew up at sample $n (fc=$fc)")
            if (abs(y) > peak) peak = abs(y)
        }
        assertTrue(peak < 1f, "saturating loop should keep the output inside unity, peak $peak")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.LadderTest"`
Expected: FAIL — `Unresolved reference: Ladder`.

- [ ] **Step 3: Add the class to `Dsp.kt`**

Immediately after `class TptSvf { ... }` (before the `Biquad` KDoc), add:

```kotlin
    /**
     * The four-pole transistor-ladder low-pass — four one-poles in
     * series, the fourth fed back to the input and soft-clipped at every
     * stage. The simplified Huovilainen form most open ports use.
     *
     * [resonance] is the feedback, 0..[MAX_RESONANCE]: the linear model
     * self-oscillates at 4, the tanh pushes that slightly higher and
     * bounds it when it does. Two things this topology does that
     * [TptSvf] does not, both measured in the design spec
     * (docs/superpowers/specs/2026-09-24-amber-ladder-engine-design.md)
     * and pinned by `LadderTest`: the slope is 24 dB/oct, and the
     * passband drops by 1/(1+r) as resonance rises — the "thinning" that
     * makes a resonant sweep on this filter sound hollow rather than
     * peaky. Neither is compensated: the loss is the character, and every
     * engine ends in [levelTo].
     *
     * No tuning compensation either. The unit delay in the feedback pulls
     * the resonant peak slightly flat and the self-oscillation slightly
     * sharp (measured: 975 Hz for 1000; +0.9% at 1 kHz, +5.4% at 4 kHz,
     * at RATE * OVERSAMPLE). Under a quarter-tone everywhere a CUTOFF
     * knob with no Hz readout will land, and a published polynomial fix
     * would be a tuned constant shipped unmeasured.
     *
     * Do not replace the per-stage tanh pair with one shared tanh: the
     * `tanh(in) - tanh(y)` form is what makes each stage's DC gain
     * exactly 1, which is what keeps the passband flat at r = 0.
     *
     * One instance per voice per pass; process() advances one sample.
     */
    class Ladder(private val rate: Int = RATE) {
        private val y = FloatArray(4)
        var out = 0f
            private set

        fun process(input: Float, freqHz: Float, resonance: Float): Float {
            val fc = freqHz.coerceIn(10f, rate * 0.45f)
            val g = (1.0 - exp(-2.0 * PI * fc / rate)).toFloat()
            val r = resonance.coerceIn(0f, MAX_RESONANCE)
            var u = tanh(input - r * y[3])
            for (i in 0 until 4) {
                y[i] += g * (u - tanh(y[i]))
                u = tanh(y[i])
            }
            out = y[3]
            return out
        }

        fun reset() { y.fill(0f); out = 0f }

        companion object {
            /** Past the linear model's threshold of 4, so the top of a CREAM knob sings. */
            const val MAX_RESONANCE = 4.3f
        }
    }
```

`Dsp.kt` already imports `kotlin.math.exp`, `PI` and `tanh` (check the import block; add any that are missing).

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.LadderTest"`
Expected: PASS, seven tests. If a threshold fails, follow the Global Constraints rule: record the Kotlin measurement in the test comment and re-derive the threshold with margin. A slope under 20 dB/oct or a passband loss outside 10–16 dB means the port is wrong (compare against the spec's appendix), not the table.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt synth/src/test/kotlin/com/snipsnap/synth/LadderTest.kt
git commit -m "Dsp.Ladder: the four-pole ladder low-pass, measured before it is used"
```

---

### Task 2: `Amber.kt` — the engine

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Amber.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt` (the first half; Task 3 adds the behaviour tests)

**Interfaces:**
- Consumes: `Dsp.RATE`, `Dsp.OVERSAMPLE`, `Dsp.lin`, `Dsp.expMap`, `Dsp.envAt`, `Dsp.keyTrack`, `Dsp.phases`, `Dsp.seedFor`, `Dsp.scrambleNear`, `Dsp.Env`, `Dsp.Ladder`, `Dsp.decimate`, `Dsp.levelTo`, `Dsp.fadeTail`, `Dsp.MELODIC_LOUDNESS_TARGET`, `MacroSpec`, `Snip`.
- Produces:
  - `enum class AmberVoice { BASS, LEAD, BRASS }`
  - `Amber.TUNE_SEMITONES = 24`, `Amber.MAX_CUTOFF_HZ = 16_000f`
  - `Amber.macrosFor / defaults / scramble / frequencyFor / render`
  - `internal Amber.synthesize(voice, macros, rate)`, `internal Amber.stackGains(stack)`

Note: `scramble` references `AmberPresets`, which Task 5 creates. Until then, write `scramble` **without** the preset branch (the `near == null && temperature < 1f` case seeds from `base`), and Task 5 restores the VELVET-shaped three-way `when`. This keeps every commit compiling.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmberTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in AmberVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Amber.macrosFor(voice).associate { it.name to 0f },
                Amber.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Amber.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six shared macros`() {
        for (voice in AmberVoice.entries) {
            assertEquals(
                listOf("TUNE", "STACK", "CUTOFF", "CREAM", "CONTOUR", "DECAY"),
                Amber.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in AmberVoice.entries) {
            val a = Amber.render(voice, mapOf("CREAM" to 0.7f))
            val b = Amber.render(voice, mapOf("CREAM" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in AmberVoice.entries) {
            val a = Amber.scramble(voice, Random(11))
            val b = Amber.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Amber.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        // The same mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX carry
        // (VelvetTest has the full reasoning): render() must not be a
        // native-rate synthesize() finished the same way.
        for (voice in AmberVoice.entries) {
            val actual = Amber.render(voice)
            val direct = Amber.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.002, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.AmberTest"`
Expected: FAIL — `Unresolved reference: Amber`.

- [ ] **Step 3: Write `Amber.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.random.Random

/**
 * AMBER — the ladder engine.
 *
 * VELVET is subtractive through a state-variable filter; FATHOM slides and
 * beats; neither can make the sound this engine exists for, because that
 * sound *is* a filter: four one-poles in a row, the last fed back to the
 * first and soft-clipped inside the loop ([Dsp.Ladder]). Resonance thins
 * the bass on the way up and sings at the top, and the drive is the
 * filter's own input stage — there is no separate saturator to route
 * wrong.
 *
 * Three oscillators feed it: a saw at the note, a saw an octave down, and
 * a detuned square. STACK is their mixer on one knob (thin → deep → fat).
 * CUTOFF tracks the note, CONTOUR is the filter envelope's amount and
 * speed together (the wah, then the snap), CREAM is the feedback. TUNE
 * snaps to semitones. One-shot stabs onto pads, like every other engine.
 *
 * Design: docs/superpowers/specs/2026-09-24-amber-ladder-engine-design.md.
 */
enum class AmberVoice { BASS, LEAD, BRASS }

object Amber {

    const val TUNE_SEMITONES = 24

    /** A taste ceiling, not a stability one (`g < 1` for any finite fc); keeps the tuning error inside the measured range. */
    const val MAX_CUTOFF_HZ = 16_000f

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass — a table edit here, not a refactor of
     * [render], same as every melodic engine.
     */
    private val LOUDNESS_OFFSET: Map<AmberVoice, Float> = AmberVoice.entries.associateWith { 0f }

    fun macrosFor(voice: AmberVoice): List<MacroSpec> = when (voice) {
        AmberVoice.BASS -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("STACK", 0.5f), MacroSpec("CUTOFF", 0.35f),
            MacroSpec("CREAM", 0.35f), MacroSpec("CONTOUR", 0.4f), MacroSpec("DECAY", 0.5f),
        )
        AmberVoice.LEAD -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("STACK", 0.8f), MacroSpec("CUTOFF", 0.55f),
            MacroSpec("CREAM", 0.5f), MacroSpec("CONTOUR", 0.5f), MacroSpec("DECAY", 0.45f),
        )
        AmberVoice.BRASS -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("STACK", 0.6f), MacroSpec("CUTOFF", 0.4f),
            MacroSpec("CREAM", 0.3f), MacroSpec("CONTOUR", 0.75f), MacroSpec("DECAY", 0.5f),
        )
    }

    fun defaults(voice: AmberVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: AmberVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + AmberPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /**
     * PLACEHOLDER awaiting the audition gate — how hard CUTOFF tracks the
     * note. Inherited from VELVET's constant of the same name, whose KDoc
     * has the measurement and the caveat; nothing here re-derives it.
     */
    internal const val CUTOFF_KEY_TRACK_AMOUNT = 0.6f

    /** The tuning centre of the voice's own TUNE range — see [Velvet.keyTrackReferenceHz]. */
    private fun keyTrackReferenceHz(voice: AmberVoice): Float = frequencyFor(voice, 0.5f)

    fun frequencyFor(voice: AmberVoice, tune: Float): Float {
        val root = when (voice) {
            AmberVoice.BASS -> 55f     // A1
            AmberVoice.LEAD -> 220f    // A3
            AmberVoice.BRASS -> 110f   // A2
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /** CUTOFF's floor and ceiling per voice, Hz, before key tracking. */
    private fun cutoffRange(voice: AmberVoice): Pair<Float, Float> = when (voice) {
        AmberVoice.BASS -> 60f to 5_000f
        AmberVoice.LEAD -> 200f to 14_000f
        AmberVoice.BRASS -> 120f to 9_000f
    }

    /** Naive saw from a 0..1 phase. Aliases; the render is oversampled. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    /** Naive square from a 0..1 phase. */
    private fun square(phase: Double): Float = if (phase - Math.floor(phase) < 0.5) 1f else -1f

    /**
     * STACK's mixer: osc 2 (the sub-octave saw) rises over the bottom half
     * of the knob, osc 3 (the detuned square) over the top half. Osc 1 is
     * always at 1. `internal` so a test can assert the curve rather than
     * infer it from a spectrum. PLACEHOLDER awaiting the audition gate:
     * authored from the DSP, never listened to.
     */
    internal fun stackGains(stack: Float): Pair<Float, Float> {
        val s = stack.coerceIn(0f, 1f)
        val g2 = 0.9f * (s * 2f).coerceIn(0f, 1f)
        val g3 = ((s - 0.4f) / 0.6f).coerceIn(0f, 1f)
        return g2 to g3
    }

    /**
     * The raw synth loop at whatever [rate] the caller wants — split out
     * of [render] so the oversampled dispatch can be tested against a
     * native-rate render (VelvetTest has the reasoning).
     */
    internal fun synthesize(voice: AmberVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val stack = m.getValue("STACK")
        val cutoff = m.getValue("CUTOFF")
        val cream = m.getValue("CREAM")
        val contour = m.getValue("CONTOUR")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 1.2f)

        // The stack is scaled to unity so how hard it hits the filter's
        // tanh does not change with STACK: the knob is a mixer, not a drive.
        val (g2, g3) = stackGains(stack)
        val mixScale = 1f / (1f + g2 + g3)
        // Width, not movement: no beat floor (VELVET's is for FAT, which promises motion).
        val detune = 2f.pow(Dsp.lin(stack, 3f, 14f) / 1200f)

        // Tracked to the note so brightness is an interval above the
        // fundamental, not a fixed Hz (Velvet.kt has the measurement).
        val (lo, hi) = cutoffRange(voice)
        val floorHz = Dsp.keyTrack(Dsp.expMap(cutoff, lo, hi), base, keyTrackReferenceHz(voice), CUTOFF_KEY_TRACK_AMOUNT)
        // CONTOUR is amount and speed together, the same one-knob device as
        // VELVET's SQUEEZE: a bigger sweep falls faster. In octaves, not Hz,
        // because a ladder sweep is heard in octaves. The sweep's T60 is at
        // most 0.6 of the note's, so it always lands before the note ends.
        // PLACEHOLDER awaiting the audition gate: the 0.6 -> 0.2 shape is taste.
        val octavesUp = Dsp.lin(contour, 0f, 4f)
        val contourT60 = t60 * Dsp.lin(contour, 0.6f, 0.2f)
        // The top of the knob is past the linear threshold: the filter sings.
        val resonance = Dsp.lin(cream, 0f, Dsp.Ladder.MAX_RESONANCE)

        val out = FloatArray((t60 * 1.4f * rate).toInt().coerceAtLeast(64))
        val ladder = Dsp.Ladder(rate)
        val env = Dsp.Env(attackSeconds = 0.003f, decay2T60 = t60)
        // Seeded per voice so the stack never opens phase-locked.
        val ph = Dsp.phases(3, Dsp.seedFor("AMBER", voice.name))
        var p1 = ph[0]
        var p2 = ph[1]
        var p3 = ph[2]
        for (i in out.indices) {
            val t = i.toFloat() / rate
            p1 += base / rate
            p2 += base * 0.5 / rate
            p3 += base * detune / rate
            val stackOut = mixScale * (saw(p1) + g2 * saw(p2) + g3 * square(p3))
            val fc = (floorHz * 2f.pow(octavesUp * Dsp.envAt(t, contourT60))).coerceAtMost(MAX_CUTOFF_HZ)
            out[i] = ladder.process(stackOut, fc, resonance) * env.at(t)
        }
        return out
    }

    fun render(voice: AmberVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the naive
        // oscillators' and the ladder's tanh harmonics fold down above
        // 22.05 kHz instead of into the audible band, then decimate.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, RATE)
        // Loudness, not peak (Dsp.MELODIC_LOUDNESS_TARGET's KDoc has the
        // measurement); the ladder's own 1/(1+r) passband loss is
        // restored here as loudness, never inside the filter.
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
```

For this task only, replace the `else ->` branch of `scramble` with `else -> base` and leave a `// Task 5 restores the preset seed.` comment; Task 5 puts the `AmberPresets` line back.

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.AmberTest"`
Expected: PASS, five tests.

If `every voice renders clean audio…` fails on `peak > 0.5` at the all-zeros corner: that corner is BASS at 55 Hz through a 60 Hz cutoff, a near-sine. `Dsp.levelTo` can only raise a low-crest signal so far; FATHOM's DEEP passes the same floor with a sine, so this should too. If it does not, report the measured peak in the PR rather than raising the cutoff floor to dodge it.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Amber.kt synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt
git commit -m "AMBER: three voices through the ladder, the engine and its first tests"
```

---

### Task 3: The macros do what they say — measured

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt`

These tests are the point of the engine. Each measures the macro's own promise in the render.

- [ ] **Step 1: Add the tests**

Append inside `class AmberTest`:

```kotlin
    /** [snip] between two times, mono, for a windowed measurement. */
    private fun slice(snip: Snip, fromSec: Float, toSec: Float): Snip {
        val a = (fromSec * snip.sampleRate).toInt().coerceIn(0, snip.samples.size)
        val b = (toSec * snip.sampleRate).toInt().coerceIn(a, snip.samples.size)
        return Snip(snip.samples.copyOfRange(a, b), 1, snip.sampleRate)
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Amber.frequencyFor(AmberVoice.LEAD, i / 100f))
        assertEquals(Amber.TUNE_SEMITONES + 1, distinct.size)

        // One saw, no sweep, gentle resonance: a clean pitch read.
        // LEAD 0.5 -> 1.0 is one octave, 440 Hz -> 880 Hz.
        val clean = mapOf("STACK" to 0f, "CONTOUR" to 0f, "CREAM" to 0.2f, "CUTOFF" to 0.8f)
        val low = TestPitch.estimate(Amber.render(AmberVoice.LEAD, clean + ("TUNE" to 0.5f)), fromSec = 0.05f, windowSec = 0.2f)
        val high = TestPitch.estimate(Amber.render(AmberVoice.LEAD, clean + ("TUNE" to 1f)), fromSec = 0.05f, windowSec = 0.2f)
        assertTrue(high > low * 1.8f && high < low * 2.2f, "TUNE 0.5 -> 1 is one octave: $low Hz -> $high Hz")
    }

    @Test
    fun `CUTOFF opens`() {
        for (voice in AmberVoice.entries) {
            val still = mapOf("CREAM" to 0f, "CONTOUR" to 0f)
            val dark = FeatureExtractor.extract(Amber.render(voice, still + ("CUTOFF" to 0.05f)))
            val open = FeatureExtractor.extract(Amber.render(voice, still + ("CUTOFF" to 0.95f)))
            assertTrue(open.centroidHz > dark.centroidHz * 1.5f, "$voice CUTOFF up should brighten: ${dark.centroidHz} -> ${open.centroidHz}")
        }
    }

    @Test
    fun `CREAM thins the bass - the ladder's signature, heard in the render`() {
        // The design spec measured the filter losing 13 dB of passband at
        // r = 3.5 while the peak at the cutoff rose 7 dB. On a bass note
        // whose fundamental and second harmonic sit under 200 Hz, that is
        // a large drop in Features.lowRatio at a fixed cutoff. Measured
        // 2026-09-24 in the render: lowRatio 0.773 at CREAM 0, 0.502 at
        // 0.5, 0.364 at 0.85, 0.280 at 1 - a 0.47x drop at 0.85, pinned
        // at 0.6x with margin.
        val still = mapOf("TUNE" to 0.3f, "CUTOFF" to 0.5f, "CONTOUR" to 0f, "STACK" to 0.5f)
        val plain = FeatureExtractor.extract(Amber.render(AmberVoice.BASS, still + ("CREAM" to 0f)))
        val creamy = FeatureExtractor.extract(Amber.render(AmberVoice.BASS, still + ("CREAM" to 0.85f)))
        assertTrue(
            creamy.lowRatio < plain.lowRatio * 0.6f,
            "CREAM should thin the bass: lowRatio ${plain.lowRatio} -> ${creamy.lowRatio}",
        )
    }

    @Test
    fun `CONTOUR sweeps the head, then lands`() {
        // Measured 2026-09-24 at exactly this setting: first-10 ms centroid
        // 951 Hz at CONTOUR 1 against a 596 Hz tail (1.60x); 630 Hz at
        // CONTOUR 0 (1.06x); and the tail reads 595.8 Hz at every CONTOUR,
        // because the sweep's T60 is at most 0.6 of the note's and has
        // landed long before the last 30%. Ten milliseconds, not forty: at
        // CONTOUR 1 the sweep's T60 is 85 ms, so a 40 ms window already
        // averages in the landed filter (measured 1.13x there).
        // FeatureExtractor measures the first 4096 samples of whatever it
        // is handed, so the windows are cut first and measured second.
        val still = mapOf("STACK" to 0f, "CREAM" to 0.3f, "CUTOFF" to 0.5f, "DECAY" to 0.5f)
        fun measure(contour: Float): Pair<Float, Float> {
            val snip = Amber.render(AmberVoice.LEAD, still + ("CONTOUR" to contour))
            val head = FeatureExtractor.extract(slice(snip, 0f, 0.01f)).centroidHz
            val tail = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds)).centroidHz
            return head to tail
        }
        val (sweptHead, sweptTail) = measure(1f)
        val (flatHead, flatTail) = measure(0f)
        assertTrue(sweptHead > sweptTail * 1.3f, "CONTOUR 1 should open the head above the tail: $sweptHead vs $sweptTail")
        assertTrue(flatHead < flatTail * 1.2f, "CONTOUR 0 should leave head and tail alike: $flatHead vs $flatTail")
        assertTrue(
            kotlin.math.abs(sweptTail - flatTail) < flatTail * 0.05f,
            "the sweep must have landed by the tail: $sweptTail vs $flatTail",
        )
    }

    @Test
    fun `STACK thickens - the sub-octave saw takes the pitch down an octave`() {
        val (g2Lo, g3Lo) = Amber.stackGains(0f)
        val (g2Mid, _) = Amber.stackGains(0.5f)
        val (g2Hi, g3Hi) = Amber.stackGains(1f)
        assertEquals(0f, g2Lo); assertEquals(0f, g3Lo)
        assertTrue(g2Mid > 0.8f, "the sub joins over the bottom half: $g2Mid")
        assertTrue(g2Hi > 0.8f && g3Hi == 1f, "the top is all three: $g2Hi, $g3Hi")

        // Measured 2026-09-24: LEAD reads 441 Hz at STACK 0 and 220.5 Hz
        // from STACK 0.25 up; BASS 110 -> 55. The centroid barely moves
        // (652 -> 604 Hz: power-weighted, the fundamental dominates), so
        // the pitch detector is the honest instrument for "the sub joined".
        val still = mapOf("CUTOFF" to 0.8f, "CREAM" to 0f, "CONTOUR" to 0f, "TUNE" to 0.5f)
        for (voice in listOf(AmberVoice.LEAD, AmberVoice.BASS)) {
            val thin = TestPitch.estimate(Amber.render(voice, still + ("STACK" to 0f)), fromSec = 0.05f, windowSec = 0.2f)
            val deep = TestPitch.estimate(Amber.render(voice, still + ("STACK" to 0.5f)), fromSec = 0.05f, windowSec = 0.2f)
            assertTrue(thin > deep * 1.8f && thin < deep * 2.2f, "$voice: the sub should read an octave down: $thin -> $deep")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in AmberVoice.entries) {
            val short = FeatureExtractor.extract(Amber.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Amber.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(long.decayMs > short.decayMs * 1.5f, "$voice DECAY should stretch the note: ${short.decayMs} -> ${long.decayMs}")
        }
    }

    @Test
    fun `factory defaults are harmonic, not noise`() {
        // No DrumClass predicted (FathomTest has the reasoning): flatness
        // measures the thing that matters.
        for (voice in AmberVoice.entries) {
            val f = FeatureExtractor.extract(Amber.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        val voice = AmberVoice.LEAD
        val base = Amber.defaults(voice)
        assertEquals(base, Amber.scramble(voice, Random(3), temperature = 0f, near = null).let { base }, "temperature 0 is the seed")
        val near = AmberPatch("X", voice, mapOf("CREAM" to 0.9f))
        val nearRoll = Amber.scramble(voice, Random(3), temperature = 0f, near = near)
        assertEquals(0.9f, nearRoll["CREAM"], "near seeds the roll")
    }
```

The last test needs `AmberPatch` (Task 4). Add it in Task 4's step 1 instead if you are running strictly task by task; it is listed here so the macro suite reads as one unit.

- [ ] **Step 2: Run the tests**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.AmberTest"`
Expected: PASS. Each threshold has margin over the spec's numbers; on a miss, apply the Global Constraints rule and record the measurement in the test comment.

- [ ] **Step 3: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt
git commit -m "AMBER's macros keep their promises: CREAM thins, CONTOUR sweeps, STACK thickens"
```

---

### Task 4: `AmberPatch` — an AMBER pad survives `kit.json`

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt` (KDoc only)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt`, `DeterminismTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `AmberTest`:

```kotlin
    @Test
    fun `an AMBER patch round-trips through JSON`() {
        val patch = AmberPatch("Cream Test", AmberVoice.BRASS, mapOf("CONTOUR" to 0.9f, "CREAM" to 0.4f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `an AMBER patch rejects a macro the voice does not have`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AmberPatch("Bad", AmberVoice.BASS, mapOf("GLIDE" to 0.5f))
        }
    }
```

Add to `DeterminismTest`, after the FATHOM canary:

```kotlin
    @Test
    fun `AMBER is byte-identical across renders`() {
        val patch = AmberPatch("Canary", AmberVoice.LEAD, Amber.defaults(AmberVoice.LEAD))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }
```

(Task 5 may switch it to `AmberPresets.forVoice(AmberVoice.LEAD).first()` for parity with the others; either is a real `render()` path.) Update that file's comment listing the engines that call `Dsp.seedFor` to include `Amber.kt`.

- [ ] **Step 2: Run to see the compile failure**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.AmberTest"`
Expected: FAIL — `Unresolved reference: AmberPatch`.

- [ ] **Step 3: Add `AmberPatch` to `Patches.kt`**

After `FathomPatch`'s class, mirroring it exactly:

```kotlin
/** A saved AMBER sound. */
data class AmberPatch(
    override val name: String,
    val voice: AmberVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Amber.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Amber.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "AMBER"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> AmberVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                AmberPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
```

And one branch in `Patches.fromJsonValue`'s `when`, after `FathomPatch.ENGINE`:

```kotlin
            AmberPatch.ENGINE -> AmberPatch.fromJsonValue(value)
```

- [ ] **Step 4: The sealed `when` in `Velocity.kt`**

`Patch` is a sealed interface, so `Velocity.macroSpecsFor`'s `when` stops compiling until it knows the new type. After `is FathomPatch -> Fathom.macrosFor(patch.voice)` add:

```kotlin
        is AmberPatch -> Amber.macrosFor(patch.voice)
```

In the `BRIGHTNESS_MACROS` KDoc, change the CUTOFF bullet's parenthetical to `(VELVET all voices; FATHOM all voices; AMBER all voices)` — AMBER's soft zone is its cutoff, found by name, no other change.

- [ ] **Step 5: `Dsp.OVERSAMPLE`'s KDoc**

Change `All 8 engines - [Thump], [Tines], [Velvet], [Fathom], [Tonewheel], [Vox], [Pluck], [Skin] - are wired` to `All 9 engines - [Thump], [Tines], [Velvet], [Fathom], [Tonewheel], [Vox], [Pluck], [Skin], [Amber] - are wired`, and append a sentence: `Amber, like Skin, was born wired to it.`

- [ ] **Step 6: Run the module's tests**

Run: `./gradlew --no-daemon :synth:test`
Expected: PASS. (`PadRecipeTest` and `ExportRegressionTest` exercise `Patches.fromJsonValue` generically; nothing else should move.)

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Patches.kt synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt synth/src/test/kotlin/com/snipsnap/synth/AmberTest.kt synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt
git commit -m "AmberPatch: an AMBER pad regenerates from kit.json like every other engine's"
```

---

### Task 5: Thirty-six factory presets

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/AmberPresets.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/AmberPresetsTest.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Amber.kt` (restore `scramble`'s preset seed), `Presets.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt`, `shell/src/test/kotlin/com/snipsnap/shell/UserPresetsTest.kt`

- [ ] **Step 1: Write the failing test**

`AmberPresetsTest.kt` is `VelvetPresetsTest.kt` with the names swapped (`AmberVoice`, `AmberPresets`, `AmberPatch`) and this KDoc:

```kotlin
/**
 * U1 of `docs/SYNTH_UPGRADE.md`, AMBER's turn: twelve presets per voice
 * (thirty-six total). No classifier identity check — every AMBER voice is
 * statically TONAL in `SynthScreen`'s mapping, like VELVET's — so this is
 * the rest of the playability contract: a real, clean sound; a faithful
 * JSON round-trip; listbox-legal names under the naming rule. Authored
 * from `Amber.kt`'s DSP (the STACK curve, CONTOUR's octave sweep,
 * CREAM's 1/(1+r) thinning), not by ear.
 */
```

Also add to `PresetsTest`: an `assertEquals(AmberPresets.forVoice(AmberVoice.LEAD), Presets.forVoice("AMBER", "LEAD"))` beside the FATHOM line, and `+ AmberPresets.all()` in the `all()` sum. In `UserPresetsTest`'s `voices` list add `"AMBER" to AmberVoice.entries.map { it.name },` with the import.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.AmberPresetsTest"`
Expected: FAIL — `Unresolved reference: AmberPresets`.

- [ ] **Step 3: Write `AmberPresets.kt`**

Macro order in every line: `TUNE, STACK, CUTOFF, CREAM, CONTOUR, DECAY`.

```kotlin
package com.snipsnap.synth

/**
 * AMBER's factory presets — U1 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Twelve per voice, thirty-six total, spread across STACK (thin → deep →
 * fat), CUTOFF, CREAM (the top sings) and CONTOUR (the wah, then the
 * snap). Authored from `Amber.kt`'s DSP, not by ear, and checked by
 * [AmberPresetsTest]. Named for what they sound like — never for the
 * machine whose filter this is — per the naming rule.
 */
object AmberPresets {

    private fun p(voice: AmberVoice, name: String, vararg macros: Pair<String, Float>) =
        AmberPatch(name, voice, macros.toMap())

    fun forVoice(voice: AmberVoice): List<AmberPatch> = when (voice) {
        AmberVoice.BASS -> bassPresets
        AmberVoice.LEAD -> leadPresets
        AmberVoice.BRASS -> brassPresets
    }

    fun all(): List<AmberPatch> = AmberVoice.entries.flatMap { forVoice(it) }

    private val bassPresets = listOf(
        p(AmberVoice.BASS, "DEEP CREAM", "TUNE" to 0.2f, "STACK" to 0.5f, "CUTOFF" to 0.3f, "CREAM" to 0.45f, "CONTOUR" to 0.35f, "DECAY" to 0.55f),
        p(AmberVoice.BASS, "SUB WOOD", "TUNE" to 0.1f, "STACK" to 0.55f, "CUTOFF" to 0.2f, "CREAM" to 0.2f, "CONTOUR" to 0.2f, "DECAY" to 0.6f),
        p(AmberVoice.BASS, "ROUND SUB", "TUNE" to 0.15f, "STACK" to 0.45f, "CUTOFF" to 0.25f, "CREAM" to 0.3f, "CONTOUR" to 0.25f, "DECAY" to 0.5f),
        p(AmberVoice.BASS, "WARM STACK", "TUNE" to 0.3f, "STACK" to 0.7f, "CUTOFF" to 0.35f, "CREAM" to 0.35f, "CONTOUR" to 0.4f, "DECAY" to 0.5f),
        p(AmberVoice.BASS, "FAT BOTTOM", "TUNE" to 0.25f, "STACK" to 0.95f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.45f, "DECAY" to 0.45f),
        p(AmberVoice.BASS, "SLOW SWELL", "TUNE" to 0.2f, "STACK" to 0.6f, "CUTOFF" to 0.3f, "CREAM" to 0.5f, "CONTOUR" to 0.15f, "DECAY" to 0.8f),
        p(AmberVoice.BASS, "RUBBER BOOM", "TUNE" to 0.35f, "STACK" to 0.4f, "CUTOFF" to 0.45f, "CREAM" to 0.6f, "CONTOUR" to 0.7f, "DECAY" to 0.35f),
        p(AmberVoice.BASS, "HOLLOW LOW", "TUNE" to 0.3f, "STACK" to 0.85f, "CUTOFF" to 0.3f, "CREAM" to 0.55f, "CONTOUR" to 0.3f, "DECAY" to 0.5f),
        p(AmberVoice.BASS, "TIGHT KNOCK", "TUNE" to 0.4f, "STACK" to 0.3f, "CUTOFF" to 0.5f, "CREAM" to 0.4f, "CONTOUR" to 0.85f, "DECAY" to 0.2f),
        p(AmberVoice.BASS, "DARK DRONE", "TUNE" to 0.05f, "STACK" to 0.5f, "CUTOFF" to 0.15f, "CREAM" to 0.25f, "CONTOUR" to 0.1f, "DECAY" to 0.9f),
        p(AmberVoice.BASS, "SINGING LOW", "TUNE" to 0.3f, "STACK" to 0.2f, "CUTOFF" to 0.35f, "CREAM" to 0.95f, "CONTOUR" to 0.5f, "DECAY" to 0.5f),
        p(AmberVoice.BASS, "THICK BUTTER", "TUNE" to 0.25f, "STACK" to 0.8f, "CUTOFF" to 0.3f, "CREAM" to 0.65f, "CONTOUR" to 0.4f, "DECAY" to 0.55f),
    )

    private val leadPresets = listOf(
        p(AmberVoice.LEAD, "SOLO CREAM", "TUNE" to 0.5f, "STACK" to 0.8f, "CUTOFF" to 0.55f, "CREAM" to 0.55f, "CONTOUR" to 0.5f, "DECAY" to 0.45f),
        p(AmberVoice.LEAD, "SCREAM LEAD", "TUNE" to 0.6f, "STACK" to 0.9f, "CUTOFF" to 0.7f, "CREAM" to 0.9f, "CONTOUR" to 0.6f, "DECAY" to 0.4f),
        p(AmberVoice.LEAD, "WOOD FLUTE", "TUNE" to 0.7f, "STACK" to 0.1f, "CUTOFF" to 0.35f, "CREAM" to 0.3f, "CONTOUR" to 0.2f, "DECAY" to 0.5f),
        p(AmberVoice.LEAD, "THIN SAW", "TUNE" to 0.5f, "STACK" to 0f, "CUTOFF" to 0.8f, "CREAM" to 0.2f, "CONTOUR" to 0.3f, "DECAY" to 0.4f),
        p(AmberVoice.LEAD, "FAT LEAD", "TUNE" to 0.4f, "STACK" to 1f, "CUTOFF" to 0.5f, "CREAM" to 0.45f, "CONTOUR" to 0.45f, "DECAY" to 0.5f),
        p(AmberVoice.LEAD, "SQUARE SING", "TUNE" to 0.55f, "STACK" to 0.75f, "CUTOFF" to 0.45f, "CREAM" to 0.95f, "CONTOUR" to 0.35f, "DECAY" to 0.5f),
        p(AmberVoice.LEAD, "LASER ZAP", "TUNE" to 0.8f, "STACK" to 0.3f, "CUTOFF" to 0.9f, "CREAM" to 1f, "CONTOUR" to 1f, "DECAY" to 0.15f),
        p(AmberVoice.LEAD, "SOFT WHISTLE", "TUNE" to 0.75f, "STACK" to 0.05f, "CUTOFF" to 0.3f, "CREAM" to 0.85f, "CONTOUR" to 0.1f, "DECAY" to 0.6f),
        p(AmberVoice.LEAD, "BRIGHT STACK", "TUNE" to 0.5f, "STACK" to 0.85f, "CUTOFF" to 0.85f, "CREAM" to 0.35f, "CONTOUR" to 0.55f, "DECAY" to 0.4f),
        p(AmberVoice.LEAD, "SLOW OPENER", "TUNE" to 0.45f, "STACK" to 0.7f, "CUTOFF" to 0.25f, "CREAM" to 0.5f, "CONTOUR" to 0.05f, "DECAY" to 0.85f),
        p(AmberVoice.LEAD, "NASAL LEAD", "TUNE" to 0.55f, "STACK" to 0.6f, "CUTOFF" to 0.4f, "CREAM" to 0.7f, "CONTOUR" to 0.8f, "DECAY" to 0.35f),
        p(AmberVoice.LEAD, "GLASS SING", "TUNE" to 0.65f, "STACK" to 0.35f, "CUTOFF" to 0.6f, "CREAM" to 0.9f, "CONTOUR" to 0.65f, "DECAY" to 0.45f),
    )

    private val brassPresets = listOf(
        p(AmberVoice.BRASS, "BIG BRASS", "TUNE" to 0.4f, "STACK" to 0.7f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.75f, "DECAY" to 0.5f),
        p(AmberVoice.BRASS, "SOFT HORN", "TUNE" to 0.35f, "STACK" to 0.5f, "CUTOFF" to 0.3f, "CREAM" to 0.2f, "CONTOUR" to 0.45f, "DECAY" to 0.55f),
        p(AmberVoice.BRASS, "WAH STAB", "TUNE" to 0.45f, "STACK" to 0.65f, "CUTOFF" to 0.35f, "CREAM" to 0.6f, "CONTOUR" to 0.95f, "DECAY" to 0.3f),
        p(AmberVoice.BRASS, "THIN REED", "TUNE" to 0.6f, "STACK" to 0.15f, "CUTOFF" to 0.5f, "CREAM" to 0.5f, "CONTOUR" to 0.5f, "DECAY" to 0.4f),
        p(AmberVoice.BRASS, "DARK HORN", "TUNE" to 0.3f, "STACK" to 0.6f, "CUTOFF" to 0.2f, "CREAM" to 0.25f, "CONTOUR" to 0.4f, "DECAY" to 0.6f),
        p(AmberVoice.BRASS, "FANFARE", "TUNE" to 0.55f, "STACK" to 0.8f, "CUTOFF" to 0.55f, "CREAM" to 0.35f, "CONTOUR" to 0.7f, "DECAY" to 0.4f),
        p(AmberVoice.BRASS, "PUNCHY STAB", "TUNE" to 0.4f, "STACK" to 0.75f, "CUTOFF" to 0.45f, "CREAM" to 0.4f, "CONTOUR" to 0.9f, "DECAY" to 0.2f),
        p(AmberVoice.BRASS, "CREAM HORN", "TUNE" to 0.4f, "STACK" to 0.55f, "CUTOFF" to 0.35f, "CREAM" to 0.7f, "CONTOUR" to 0.6f, "DECAY" to 0.5f),
        p(AmberVoice.BRASS, "WIDE SECTION", "TUNE" to 0.35f, "STACK" to 1f, "CUTOFF" to 0.4f, "CREAM" to 0.3f, "CONTOUR" to 0.55f, "DECAY" to 0.55f),
        p(AmberVoice.BRASS, "QUICK BLAT", "TUNE" to 0.5f, "STACK" to 0.6f, "CUTOFF" to 0.5f, "CREAM" to 0.45f, "CONTOUR" to 1f, "DECAY" to 0.15f),
        p(AmberVoice.BRASS, "MELLOW WAH", "TUNE" to 0.3f, "STACK" to 0.4f, "CUTOFF" to 0.25f, "CREAM" to 0.55f, "CONTOUR" to 0.65f, "DECAY" to 0.65f),
        p(AmberVoice.BRASS, "SINGING HORN", "TUNE" to 0.45f, "STACK" to 0.45f, "CUTOFF" to 0.35f, "CREAM" to 0.95f, "CONTOUR" to 0.7f, "DECAY" to 0.45f),
    )
}
```

Every name is ≤ 14 characters, uppercase, unique per voice, and clear of `PresetTestSupport.trademarkBlocklist` (checked: no digits, and none contains `emu`, `acid`, `dmx` or `linn` as a substring — the blocklist matches bare substrings on purpose).

- [ ] **Step 4: Register**

`Amber.scramble`: restore the three-way `when` from Task 2's listing (the `else ->` branch seeds from `AmberPresets.forVoice(voice).random(random)`).

`Presets.kt`: after the `FathomPatch.ENGINE` branch add the same three lines for `AmberPatch.ENGINE` / `AmberVoice` / `AmberPresets`; append `+ AmberPresets.all()` to `all()`; change the KDoc's "all eight registered engines" to nine.

- [ ] **Step 5: Run the tests**

Run: `./gradlew --no-daemon :synth:test && ./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.UserPresetsTest"`
Expected: PASS. If a preset fails `peak > 0.5`, adjust **that preset's** values (a darker CUTOFF is the usual cause) and say so in the commit body; do not touch the floor.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/AmberPresets.kt synth/src/test/kotlin/com/snipsnap/synth/AmberPresetsTest.kt synth/src/main/kotlin/com/snipsnap/synth/Amber.kt synth/src/main/kotlin/com/snipsnap/synth/Presets.kt synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt shell/src/test/kotlin/com/snipsnap/shell/UserPresetsTest.kt
git commit -m "AMBER's factory presets: thirty-six sounds, named for what they are"
```

---

### Task 6: AMBER joins the SYNTH picker

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt`

**This file does not compile in a cloud session.** Read the whole private `Engine` enum (search for `private enum class Engine`) and its `drumClass` extension properties before editing. Mirror FATHOM's entry in every `when`; the Kotlin compiler in CI's `android-build` job will refuse a missed branch, and that is the only check you get.

- [ ] **Step 1: Imports**

Beside the `Fathom` imports add `com.snipsnap.synth.Amber`, `com.snipsnap.synth.AmberPatch`, `com.snipsnap.synth.AmberVoice`.

- [ ] **Step 2: The enum and every `when`**

- `THUMP, SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, FATHOM, AMBER;` and extend `next()`'s KDoc cycle `… → FATHOM → AMBER → THUMP`.
- `voices()`: `AMBER -> AmberVoice.entries`
- `macrosFor`: `AMBER -> Amber.macrosFor(voice as AmberVoice)`
- `defaults`: `AMBER -> Amber.defaults(voice as AmberVoice)`
- `scramble`: `AMBER -> Amber.scramble(voice as AmberVoice, random)`
- `render`: `AMBER -> Amber.render(voice as AmberVoice, macros)`
- `drumClass`: `AMBER -> (voice as AmberVoice).drumClass`
- `buildPatch`: `AMBER -> AmberPatch(name, voice as AmberVoice, macros)`

- [ ] **Step 3: The class mapping**

After `FathomVoice.drumClass`, add:

```kotlin
// AMBER's three voices are pitched notes through a filter — the same
// "tonal-pitched voices -> TONAL" fallback VELVET/VOX/PLUCK/TONEWHEEL
// take above, never judged from a render (AmberPresetsTest says why).
private val AmberVoice.drumClass: DrumClass
    get() = DrumClass.TONAL
```

Update the block comment above the mappings that says "VELVET/VOX/PLUCK/TONEWHEEL … all four engines are TONAL across the board" to include AMBER (five engines).

- [ ] **Step 4: The KDoc**

The file's top KDoc says "multiplexed over all eight registered engines" and lists "seven more engines (SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, FATHOM)". Make it nine and eight, adding AMBER.

- [ ] **Step 5: Re-read the diff**

`git diff app/` — count the `AMBER ->` branches: there must be seven inside the enum plus the property. Check every cast says `AmberVoice`, not a copy-pasted `FathomVoice`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt
git commit -m "AMBER joins the SYNTH picker, after FATHOM"
```

---

### Task 7: CONTOUR — the ladder on a captured pad

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Contour.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`, `Treatments.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`, `TreatmentsTest.kt`

- [ ] **Step 1: Write the failing tests**

In `FxTest`, after the RING block:

```kotlin
    // ---------- CONTOUR ----------

    @Test
    fun `CONTOUR CUTOFF low darkens and SWEEP brightens the head`() {
        // Measured on THUMP's snare, 2026-09-24: head centroid 9971 Hz at
        // CUTOFF 1 -> 180 Hz at CUTOFF 0.2 (SWEEP 0, CREAM 0.2); first-10 ms
        // centroid 558 Hz at SWEEP 0 -> 1197 Hz at SWEEP 1 (CUTOFF 0.6,
        // CREAM 0.4), a 2.1x lift, pinned at 1.5x. Ten milliseconds, not
        // twenty: at SWEEP 1 the contour's T60 is 60 ms, so a 20 ms window
        // already averages in the landed filter (545 vs 283 Hz there, and
        // at CUTOFF 0.3 the 20 ms ordering even inverts, 173 vs 200).
        val still = mapOf("CREAM" to 0.2f, "SWEEP" to 0f)
        val open = FeatureExtractor.extract(Contour.process(snare, still + ("CUTOFF" to 1f)))
        val dark = FeatureExtractor.extract(Contour.process(snare, still + ("CUTOFF" to 0.2f)))
        assertTrue(dark.centroidHz < open.centroidHz * 0.6f, "CUTOFF should darken: ${open.centroidHz} -> ${dark.centroidHz}")

        fun head10(sweep: Float): Float {
            val out = Contour.process(snare, mapOf("CUTOFF" to 0.6f, "CREAM" to 0.4f, "SWEEP" to sweep))
            val head = (0.01f * out.sampleRate).toInt()
            return FeatureExtractor.extract(Snip(out.samples.copyOfRange(0, head), 1, out.sampleRate)).centroidHz
        }
        val swept = head10(1f)
        val flat = head10(0f)
        assertTrue(swept > flat * 1.5f, "SWEEP 1 should open the first 10 ms well above SWEEP 0: $swept vs $flat")
    }

    @Test
    fun `a gently contoured kick is still a kick`() {
        // Measured 2026-09-24: KICK, centroid 45.7 Hz, lowRatio 0.978 - nowhere near a boundary.
        val mild = Contour.process(kick, mapOf("CUTOFF" to 0.6f, "CREAM" to 0.3f, "SWEEP" to 0.3f))
        assertEquals(DrumClass.KICK, Classifier.classify(mild).drumClass)
    }

    @Test
    fun `contoured exists, sets contour, and is a bypass at AMT 0`() {
        assertTrue("contoured" in Treatments.names)
        assertTrue(Treatments.chain("contoured", 1f).section("contour") != null)
        assertTrue(Treatments.chain("contoured", 0f).isBypass)
    }
```

In `TreatmentsTest`'s ordered `names` assertion, append `"contoured"` after `"pitched"`.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Contour`.

- [ ] **Step 3: Write `Contour.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow

/**
 * CONTOUR — AMBER's ladder filter on a pad that was never a synth: a
 * ripped snare or a vocal chop swept through [Dsp.Ladder] from its
 * onset. SWEEP is the filter contour's amount and speed together (the
 * same one-knob device as the engine's own CONTOUR macro), CUTOFF the
 * floor it falls to, CREAM the feedback.
 *
 * Not `PadFilter`: that previews the shape the MPC renders from
 * metadata, and a ladder there would make the phone sound different
 * from the hardware. The rack bakes into the WAV, which is where a sound
 * the hardware cannot make has to live.
 *
 * Runs at the snip's own rate, not oversampled (like RING and WOBBLE),
 * so CREAM stops at 4.0: at 44.1 kHz the loop does not reliably
 * self-oscillate above ~1 kHz, and a section that cannot promise singing
 * should not have a knob that claims to. Peak matched, no seed.
 */
object Contour {

    val MACROS: List<MacroSpec> = listOf(
        // Fully open is where it does nothing, so AMT fades toward 1.
        MacroSpec("CUTOFF", 0.35f, neutral = 1f),
        MacroSpec("CREAM", 0.5f),
        MacroSpec("SWEEP", 0.6f),
    )

    const val LOW_HZ = 80f
    const val HIGH_HZ = 16_000f

    /** Below the ladder's own 4.3: no singing promised at native rate. */
    const val MAX_RESONANCE = 4f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The floor for a CUTOFF setting at [rate], Hz. */
    fun floorHz(macro: Float, rate: Int): Float =
        minOf(Dsp.expMap(macro.coerceIn(0f, 1f), LOW_HZ, HIGH_HZ), rate * 0.4f)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val rate = snip.sampleRate
        val floor = floorHz(m.getValue("CUTOFF"), rate)
        val ceiling = rate * 0.4f
        val resonance = Dsp.lin(m.getValue("CREAM"), 0f, MAX_RESONANCE)
        val sweep = m.getValue("SWEEP")
        // A captured pad is already cut to its attack by Cleanup, so the
        // onset is frame 0 and the contour starts there.
        val octaves = Dsp.lin(sweep, 0f, 4f)
        val sweepT60 = Dsp.lin(sweep, 0.25f, 0.06f)

        val channels = snip.channels
        val out = FloatArray(snip.samples.size)
        val ladders = Array(channels) { Dsp.Ladder(rate) }
        for (f in 0 until snip.frameCount) {
            // One contour across the frame, so a stereo pair sweeps together.
            val t = f.toFloat() / rate
            val fc = (floor * 2f.pow(octaves * Dsp.envAt(t, sweepT60))).coerceAtMost(ceiling)
            for (ch in 0 until channels) {
                val i = f * channels + ch
                out[i] = ladders[ch].process(snip.samples[i], fc, resonance)
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, channels, rate)
    }
}
```

- [ ] **Step 4: Register in the rack**

`FxChain`: add the field at the end of the constructor (after `swell`), with the same "later in the parameter list than in the rack" note the others carry:

```kotlin
    val contour: Map<String, Float>? = null,
```

`Section` entry immediately after `eq`'s:

```kotlin
            Section("contour", Contour.MACROS, { it.contour }, { c, m -> c.copy(contour = m) }, Contour::process),
```

The order KDoc at the top of the class becomes `… → SPIKE → EQ → CONTOUR → SQUASH → CRUNCH → …`, with one added sentence: "contour after EQ and before the dynamics, so SQUASH tames the resonant peak rather than the peak riding over the squash".

`Treatments.EXTRA`: append after `"pitched"`:

```kotlin
        // The ladder's own contour, dropped onto a hit that was never a synth.
        "contoured" to FxChain(contour = mapOf("CUTOFF" to 0.3f, "CREAM" to 0.6f, "SWEEP" to 0.7f)),
```

- [ ] **Step 5: Run the module**

Run: `./gradlew --no-daemon :synth:test`
Expected: PASS. The shared contract (`every effect is deterministic, clean and peak-matched everywhere`, the stereo clause) now covers `contour` by construction. If `a gently contoured kick is still a kick` fails, do not weaken it silently: print the class and the features, and if the classifier honestly reads TOM at those settings, pick milder settings and record the boundary in the test comment.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Contour.kt synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt
git commit -m "CONTOUR: the ladder on a captured pad, after EQ and before the squash"
```

---

### Task 8: The pad sheet draws CONTOUR

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt`
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`

The app's `PadSheetScreen` reads `PadSheet.ROWS`; no `:app` change is needed. The row ceiling is six chips and the character row has five.

- [ ] **Step 1: Update the tests**

In `PadSheetTest`: the `CHARACTER_SEGMENTS` assertion becomes `listOf("PUNCH", "RING", "DUB", "VINYL", "PHASE", "CONTOUR")`; both `28` counts become `29`; the inventory test's expected set gains `"CONTOUR"` (with a comment: `// the ladder's contour, from the AMBER plan`) and its name says twenty-nine.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.PadSheetTest"`
Expected: FAIL on the row assertion.

- [ ] **Step 3: Edit `PadSheet.kt`**

`CHARACTER_SEGMENTS` gains `"CONTOUR"` last. `CHARACTER_FOR` gains:

```kotlin
        // The ladder's own filter contour, swept from the hit's onset.
        "CONTOUR" to "contoured",
```

- [ ] **Step 4: Run the shell tests**

Run: `./gradlew --no-daemon :shell:test`
Expected: PASS (`every segment names something real` resolves CONTOUR through `Treatments.names`).

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt
git commit -m "The pad sheet draws CONTOUR on the character row"
```

---

### Task 9: Document the engine

**Files:**
- Modify: `README.md`, `docs/SYNTH_ROADMAP.md`

- [ ] **Step 1: README**

In the `:synth` section, after the SKIN paragraph and before the `Velocity` paragraph, add one paragraph in the README's voice:

> AMBER is the ladder engine, S8 of the roadmap: four one-poles in a row, the last fed back to the first and soft-clipped inside the loop (`Dsp.Ladder`, measured before it was used — 24 dB an octave, a passband that thins by 1/(1+r) as resonance rises, a top that sings). Three oscillators feed it and STACK is their mixer on one knob; CONTOUR is the filter envelope's amount and speed together; CREAM is the feedback. BASS, LEAD, BRASS. CONTOUR is also a rack section — the same filter swept from a captured hit's onset — because the synth's job is to sit with what was captured.

Change "eight synth engines" in the status line and "eight engines in the `Engine` picker counting SKIN" to nine. Add `Dsp.Ladder` nowhere else; the paragraph is enough.

- [ ] **Step 2: Roadmap**

In `docs/SYNTH_ROADMAP.md`'s phasing table, after the S6 row:

```
| S8 | **shipped** — AMBER, the ladder engine (BASS/LEAD/BRASS: a three-oscillator STACK through `Dsp.Ladder`, the four-pole transistor-ladder low-pass with tanh in the loop, measured before use; CUTOFF key-tracked, CREAM the feedback up to self-oscillation, CONTOUR the filter envelope's amount and speed on one knob) and CONTOUR, the same filter as a rack section after EQ — design in `docs/superpowers/specs/2026-09-24-amber-ladder-engine-design.md` | S1 + U5 + U6 |
```

(S7 is the photo engine, already numbered; S8 follows it in the table even though S7's rows sit in their own section below.)

- [ ] **Step 3: Commit**

```bash
git add README.md docs/SYNTH_ROADMAP.md
git commit -m "Document AMBER and CONTOUR"
```

---

### Task 10: Verification and the PR

- [ ] **Step 1: The whole JVM suite**

Run: `./gradlew --no-daemon test`
Expected: PASS, every module. The native harness does not need re-running (nothing under `app/src/main/cpp` changed).

- [ ] **Step 2: The naming sweep**

Run: `git diff origin/claude/mobile-mpc-drum-sampler-t58x74 | grep -n -i -E 'moog|minimoog|model d' ` — expected: no output. Also `grep -rn -i 'ladder' synth/src/main/kotlin/com/snipsnap/synth/Amber.kt synth/src/main/kotlin/com/snipsnap/synth/Contour.kt` should only find KDoc references to `Dsp.Ladder`, never a product string.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin claude/sound-design-tools-mdmrbw
```

Open the PR against `claude/mobile-mpc-drum-sampler-t58x74` (not `main`; see the steward skill). Title: `AMBER, the ladder engine, and CONTOUR, its filter on a captured pad`. Body: what shipped, the measured table from `LadderTest` (copy the Kotlin numbers, not the Python ones), the three placeholders awaiting the audition gate, and the one file CI alone can compile (`SynthScreen.kt`). Then watch `android-build`: a missed `when` branch there is the likeliest red, and it is fixed by reading the log, not by re-running.

---

## Verification record

The reference code in this plan is proven, not plausible. On 2026-09-24
every listing above was placed into a working tree at the default branch's
head (`20271f9c`), compiled, and run, then reverted so the branch carries
only the two documents:

| Suite | Result |
|---|---|
| `LadderTest` | 7/7 — every number from the spec's prototype held in `Float` |
| `AmberTest` | 15/15 |
| `AmberPresetsTest` | 4/4 — all 36 presets clean, in range, peak > 0.5, byte-stable through JSON |
| `FxTest` (with `contour` in `SECTIONS`) | 59/59 — the shared peak-match and stereo contracts cover the section unchanged |
| `TreatmentsTest`, `DeterminismTest`, `PresetsTest` | green |
| `./gradlew --no-daemon test` | green — `:synth` 574 tests (0 failed, 30 m on one worker), `:shell` 854, `:cli` 90; the six modules the change does not reach were up to date and skipped |

Three of the first-draft behaviour tests failed on that run and were
rewritten from what was measured, not loosened — the listings above are
the rewritten ones, with the measurements in their comments:

- `CONTOUR sweeps the head, then lands` measured a 40 ms head window at
  1.13× the body, against a 1.4× threshold. `FeatureExtractor` reads the
  first 4096 samples of whatever it is handed, and at `CONTOUR 1` the
  sweep's T60 is 85 ms, so a 40 ms window averages the landed filter in.
  A 10 ms window reads 1.60×; the tail reads 595.8 Hz at every `CONTOUR`.
- `STACK thickens` measured the power-weighted centroid moving 652 → 604 Hz
  (0.93×) against a 0.9× threshold, because the fundamental dominates a
  power-weighted centroid. The detected pitch moves 441 → 220.5 Hz, exactly
  the octave the sub-octave saw promises, so the test now measures pitch.
- The rack section's `SWEEP` test measured a 20 ms head at 0.93× the rest
  at `SWEEP 1` versus 1.07× at `SWEEP 0` — inverted — for the same
  windowing reason at a 60 ms T60. A 10 ms window at `CUTOFF 0.6` reads
  558 → 1197 Hz (2.1×).

`SynthScreen.kt` (Task 6) was **not** part of that run: `:app` is outside
the JVM build here and is proved by CI's `android-build` only.

## Notes for the implementer

- **`Dsp` is `internal`.** `Dsp.Ladder` is reachable from `:synth` tests and nothing else; that is by design, and it is why the class can share a name with `com.snipsnap.shell.Ladder` without anyone ever seeing both.
- **`Patch` is sealed.** Adding `AmberPatch` breaks `Velocity.macroSpecsFor` until Task 4 step 4 lands; do Task 4's steps in order and the build never goes red between commits.
- **`:app` is not in the build here.** Task 6 is proved by CI only. Read the file, mirror FATHOM, re-read the diff.
- **`tanh` on `Float`.** `kotlin.math.tanh(Float)` exists; do not widen to `Double` in the loop for "precision" — the prototype's margins are wide and the JVM float path is what ships.
- **Don't compensate the filter.** No passband make-up inside `Dsp.Ladder`, no tuning polynomial. Both decisions are argued in the spec; a listening pass may revisit them, a plan may not.
- **Thresholds.** Every number in `LadderTest` and `AmberTest` came from a measurement in the spec. On a miss: print, record, re-derive with margin, never loosen blind.
- **Test time.** An AMBER render is ~300k samples at nine `tanh` each; the presets suite renders 36 sounds a few times. Expect `:synth:test` to grow by tens of seconds, not minutes. If it grows by minutes, something is rendering at the oversampled rate twice.
- **Commit style.** Plain prose titles, long bodies that say why and what was tried. No model identifiers anywhere in the repository.
- **One quantity in one place.** The repo's recurring defect shape. `MAX_CUTOFF_HZ`, `Ladder.MAX_RESONANCE`, `Contour.MAX_RESONANCE` and the `0.4 * rate` ceiling each live exactly once; tests reference the constants, never a retyped number.
