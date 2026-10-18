# WAH Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `wah` section to the FX rack (a resonant band-pass swept by three moves held as weights), its three recipe characters, and the clip generator for the owner's gate. The WAH chip itself is NOT in this plan: it joins the PEDALS row only if the gate passes (Task 5, written after the gate).

**Architecture:** One new object, `Wah` (`synth`), one `Section` row after `contour` and one appended `FxChain` field; three characters in `Treatments.EXTRA`; a test-source generator. The pad sheet is untouched.

**Tech Stack:** Kotlin/JVM (`:synth`), Gradle, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-17-wah-section-design.md` (the section, its macros and DSP) and `docs/superpowers/specs/2026-10-04-pedal-board-design.md` (the pedal board's stages).

## Global Constraints

- Branch: a new branch from the current base (the controller creates it); nothing is pushed and no PR is opened without the owner's go. Read the repo's `steward` skill before pushing.
- `FxChain.VERSION` stays 1. The field is named `wah` and appended AFTER the last field (`valve` at the time of writing: re-read the constructor first; if another section was appended since, append after it). The section name `wah` is a permanent saved key.
- `Shuffle.TREATMENTS` is never touched; `Treatments.EXTRA` is appended to at its END (re-read it first), and `TreatmentsTest`'s names list is updated to match.
- A recipe never sets a macro exactly at its neutral (AMT could never move it; FxTest's "AMT scales every section a treatment sets" false-fails on it): the wah recipes set only a weight; SPEED and Q (neutrals 0.5 and 0.6) are left out.
- Never hardcode a sample rate; no literal `44100` in main source; no `.format` with digit conversions without `Locale.ROOT` in main source; no stacked KDoc; no double-asterisk-slash (the four `:shell` source laws, which run only when forced).
- Verification is always `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (allow up to an hour: other worktrees may run suites at the same time). Gate "green" on gradle's exit code, never on grepping output.
- Commit messages are plain declarative prose (no `feat:` or `fix:` prefixes), never name a model, and end with the two trailer lines: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01ND5AbnnrejW1pLnz7mNLMr`.

## Review Focus

1. **The first tap is at full strength.** `reach = clamp(1.5 * (TOUCH + AUTO + ROCK), 0, 1)`: the `wahed` recipe at the pad sheet's default AMT 0.7 must give reach 1 (pinned by a test); AMT 0.5 gives 0.75.
2. **TOUCH and ROCK park at the heel** for most of a long note (the spike measured about 3 s of a 4 s guitar note). That is a known property, not a defect: measure it, report it, do not hide it.
3. **A wah in front of the landing amp:** the section sits before SQUASH and VALVE, and VALVE lifts a quiet tail by its gain. The last 20 ms of a landed note must stay at least 50 dB under the peak (tested for both guitars).
4. **The rack's contract tests iterate `SECTIONS`** and apply to the new section with no edit; none of `contractExcluded` or `stereoExcluded` may grow.
5. **Documents that state the rack's order** (the `FxChain` KDoc order line and rationale, `README.md`'s order line) must change with the section.

---

### Task 1: The `Wah` object and its tests

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Wah.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/WahTest.kt`

**Interfaces:**
- Consumes: `MacroSpec(name, default, neutral)`, `Dsp.expMap`, `Dsp.TptSvf(rate)` (`process(input, freqHz, k)` sets `band`; `k` is clamped to at least 0.1), `Snip(samples, channels, sampleRate)`, `Snip.peak()`.
- Produces: `Wah.MACROS`, `Wah.defaults()`, `Wah.process(snip, macros)`, and the internal helpers the tests pin: `Wah.autoAt(t, hz)`, `Wah.rockAt(t, scale)`, `Wah.touchAt(db)`, `Wah.centreHz(pos, rate)`, `Wah.reach(macros)`.

- [ ] **Step 1: Write the failing tests.** Create `WahTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WahTest {

    private val rate = WavWriter.MPC_SAMPLE_RATE

    /** The power-weighted log-frequency centroid of one Hann window: 48 log-spaced Goertzel probes, 100 Hz to 8 kHz. */
    private fun centroidHz(x: FloatArray, from: Int, n: Int): Double {
        var num = 0.0
        var den = 0.0
        for (b in 0 until 48) {
            val f = 100.0 * 80.0.pow(b / 47.0)
            val c = 2.0 * cos(2.0 * PI * f / rate)
            var s1 = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
                val s = x[from + i] * hann + c * s1 - s2
                s2 = s1
                s1 = s
            }
            val p = s1 * s1 + s2 * s2 - c * s1 * s2
            num += p * ln(f)
            den += p
        }
        return if (den <= 0.0) 0.0 else exp(num / den)
    }

    /** The centroid of the first [ms] of [s]'s first channel, a 1024-sample window every 5 ms. */
    private fun centroidTrack(s: Snip, ms: Int): List<Double> {
        val x = FloatArray(s.frameCount) { s.samples[it * s.channels] }
        val hop = rate / 200
        val out = mutableListOf<Double>()
        var at = 0
        while (at * 1000 / rate < ms && at + 1024 <= x.size) {
            out += centroidHz(x, at, 1024)
            at += hop
        }
        return out
    }

    private fun octaves(track: List<Double>) = ln(track.max() / track.min()) / ln(2.0)

    /** How long, in ms, the track keeps differing from where it ends up (its median over the last 20 windows) by more than a quarter octave. */
    private fun movingMs(track: List<Double>): Int {
        val final = track.takeLast(20).sorted()[10]
        val last = track.indexOfLast { abs(ln(it / final) / ln(2.0)) > 0.25 }
        return (last + 1) * 5
    }

    private fun endDb(s: Snip): Double {
        val n = (0.02f * s.sampleRate).toInt() * s.channels
        var peak = 0f
        var end = 0f
        for (i in s.samples.indices) {
            val a = abs(s.samples[i])
            if (a > peak) peak = a
            if (i >= s.samples.size - n && a > end) end = a
        }
        return 20.0 * kotlin.math.log10(maxOf(end / peak, 1e-12f).toDouble())
    }

    @Test
    fun `the three moves have the shapes the design says`() {
        assertEquals(0f, Wah.autoAt(0f, 2f), 1e-6f)
        assertEquals(1f, Wah.autoAt(0.25f, 2f), 1e-5f)
        assertEquals(0f, Wah.rockAt(0f, 1f), 1e-6f)
        assertEquals(1f, Wah.rockAt(0.25f, 1f), 1e-5f)
        assertEquals(0.5f, Wah.rockAt(0.5f, 1f), 1e-5f)
        assertEquals(0f, Wah.rockAt(0.75f, 1f), 1e-5f)
        assertEquals(0f, Wah.rockAt(5f, 1f), 0f)
        assertEquals(1f, Wah.touchAt(0f), 1e-6f)
        assertEquals(0.5f, Wah.touchAt(-4f), 1e-6f)
        assertEquals(0f, Wah.touchAt(-8f), 1e-6f)
        assertEquals(0f, Wah.touchAt(-30f), 0f)
        assertEquals(280f, Wah.centreHz(0f, rate), 0.01f)
        assertEquals(2800f, Wah.centreHz(1f, rate), 0.1f)
        assertEquals(280f * kotlin.math.sqrt(10f), Wah.centreHz(0.5f, rate), 0.5f)
        assertEquals(0.45f * 4000f, Wah.centreHz(1f, 4000), 0.01f, "the centre is kept under 0.45 of the rate")
    }

    @Test
    fun `reach is the weights times one and a half, capped, so the pad sheet's default AMT is full strength`() {
        assertEquals(0f, Wah.reach(mapOf()), 0f)
        assertEquals(1f, Wah.reach(mapOf("AUTO" to 0.7f)), 0f)
        assertEquals(0.75f, Wah.reach(mapOf("AUTO" to 0.5f)), 1e-6f)
        assertEquals(1f, Wah.reach(mapOf("TOUCH" to 1f, "ROCK" to 1f)), 0f)
    }

    @Test
    fun `zero weights are a bit-exact copy, whatever SPEED and Q say`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val out = Wah.process(snare, mapOf("SPEED" to 0.9f, "Q" to 0.1f))
        assertTrue(out.samples.contentEquals(snare.samples), "no weight, no wah")
        assertTrue(out.samples !== snare.samples, "a copy, not the same array")
    }

    @Test
    fun `a wah is deterministic, finite, in range, peak-matched, and keeps a doubled pad stereo-identical`() {
        val choir = Vox.render(VoxVoice.CHOIR)
        val a = Wah.process(choir, mapOf("AUTO" to 1f, "TOUCH" to 0.4f))
        val b = Wah.process(choir, mapOf("AUTO" to 1f, "TOUCH" to 0.4f))
        assertTrue(a.samples.contentEquals(b.samples), "deterministic")
        assertTrue(a.samples.all { it.isFinite() && it in -1f..1f }, "finite and in range")
        assertTrue(abs(a.peak() - choir.peak()) < 0.05f, "peak ${a.peak()} against the input's ${choir.peak()}")
        val mono = Thump.render(ThumpVoice.SNARE)
        val doubled = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, mono.sampleRate)
        val out = Wah.process(doubled, mapOf("AUTO" to 1f, "TOUCH" to 1f, "ROCK" to 1f))
        for (f in 0 until doubled.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "frame $f: the two channels differ")
    }

    /**
     * What makes AUTO a wah and not a filter: the centroid of the first 600 ms covers at least 1.5
     * octaves and is still moving at least 100 ms in (the shipped CONTOUR recipe this section
     * replaces settled in 22 to 37 ms on the guitars). The spike measured 2.56 octaves on the choir
     * and 2.82 on JANGLE after the landing amp; measure the dry JANGLE note too and report it.
     */
    @Test
    fun `AUTO sweeps the centroid by at least one and a half octaves and keeps it moving`() {
        val jangle = Magnet.render(MagnetVoice.JANGLE)
        val sources = mapOf(
            "choir" to Vox.render(VoxVoice.CHOIR),
            "jangle after its amp" to Magnet.landingChain(MagnetVoice.JANGLE).process(jangle),
        )
        for ((name, src) in sources) {
            val track = centroidTrack(Wah.process(src, mapOf("AUTO" to 1f)), 600)
            val oct = octaves(track)
            val moving = movingMs(track)
            assertTrue(oct >= 1.5, "$name: AUTO covers only $oct octaves of centroid in the first 600 ms (the bar is 1.5)")
            assertTrue(moving >= 100, "$name: AUTO settles after only $moving ms (the bar is 100)")
        }
    }

    @Test
    fun `TOUCH opens on the hit and closes as the note dies`() {
        val random = java.util.Random(7)
        val x = FloatArray(rate) { i -> (random.nextGaussian() * 0.3 * exp(-i / (0.25 * rate))).toFloat() }
        val out = Wah.process(Snip(x, 1, rate), mapOf("TOUCH" to 1f))
        val early = centroidHz(out.samples, (0.03 * rate).toInt(), 1024)
        val late = centroidHz(out.samples, (0.5 * rate).toInt(), 1024)
        assertTrue(early / late >= 2.0, "TOUCH: the centroid is $early Hz at 30 ms and $late Hz at 500 ms, under one octave apart")
    }

    @Test
    fun `ROCK opens once and then waits at the heel`() {
        val random = java.util.Random(11)
        val x = FloatArray(rate * 2) { (random.nextGaussian() * 0.3).toFloat() }
        val out = Wah.process(Snip(x, 1, rate), mapOf("ROCK" to 1f))
        val atToe = centroidHz(out.samples, (0.22 * rate).toInt(), 1024)
        val parked = centroidHz(out.samples, (1.5 * rate).toInt(), 1024)
        assertTrue(atToe / parked >= 2.0, "ROCK: the centroid is $atToe Hz at the toe and $parked Hz parked, under one octave apart")
    }
}
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :synth:test --tests '*WahTest*'`. Expected: compile error (`Wah` unresolved).

- [ ] **Step 3: Write `Wah.kt`.**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * WAH - a resonant band-pass swept between a heel and a toe, the way a wah pedal's foot sweeps it,
 * by one of three moves: TOUCH (the pad's own envelope opens it on the hit and closes it as the
 * note dies), AUTO (a free-running sine) or ROCK (one heel-to-toe-to-heel gesture, then it waits).
 * The three are weights, not a switch: the app has no per-section dials, and AMT fades every
 * macro of a recipe toward its neutral, which would flip a stepped MODE macro part-way; a recipe
 * chooses a move by setting its weight to 1, and AMT shrinks the sweep smoothly.
 *
 * Sits after CONTOUR and before SQUASH: a wah is the first pedal on the board, in front of the
 * compressor and the amp. The filter is linear, so there is nothing to oversample. No tail.
 *
 * The moves are the owner's round-two prototypes (docs/superpowers/specs/2026-10-17-wah-section-design.md):
 * the shipped CONTOUR recipe this replaces settled in about 30 ms; these keep a resonant
 * filter travelling for hundreds of milliseconds. TOUCH and ROCK park at the heel after their
 * move (about 3 s of a 4 s guitar note); AUTO never parks.
 */
object Wah {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TOUCH", 0f),                       // the envelope-follower move's weight
        MacroSpec("AUTO", 0f),                        // the LFO move's weight
        MacroSpec("ROCK", 0f),                        // the one-rock gesture's weight
        MacroSpec("SPEED", 0.5f, neutral = 0.5f),     // LFO rate SLOW_HZ..FAST_HZ; the follower and the rock scale with it
        MacroSpec("Q", 0.6f, neutral = 0.6f),         // resonance: damping OPEN..TIGHT
    )

    /** The sweep's ends (shape): a decade, the spike's. */
    const val HEEL_HZ = 280f
    const val TOE_HZ = 2_800f

    /** The unfiltered signal kept under the band-pass (shape): minus 26 dB. */
    const val DRY = 0.05f

    /** SPEED 0 to 1 (exponential): 2 Hz at the default 0.5. */
    const val SLOW_HZ = 0.5f
    const val FAST_HZ = 8f
    private const val DEFAULT_HZ = 2f

    /** The envelope follower (shape): slow, so a pluck's own decay does not cancel the sweep. */
    private const val FOLLOW_ATTACK_S = 0.020f
    private const val FOLLOW_RELEASE_S = 0.100f

    /** TOUCH: the pad's own loudest moment is the toe, this many dB below it is the heel. */
    private const val FOLLOW_SPAN_DB = 8f

    /** ROCK: heel to toe, then back (shape), at the default SPEED. */
    private const val ROCK_UP_S = 0.250f
    private const val ROCK_DOWN_S = 0.500f

    /** Q 0..1 maps to damping OPEN (gentle) .. TIGHT (squealing); 0.6 is about 0.23, the Q of 5 the owner heard. */
    private const val DAMPING_OPEN = 0.8f
    private const val DAMPING_TIGHT = 0.1f

    /** The weights times this, capped at 1: the chip's first tap at the pad sheet's default AMT 0.7 is full strength (shape). */
    private const val REACH_GAIN = 1.5f

    /** Weights summing under this are a bypass. */
    private const val BYPASS = 0.001f

    /** The centre never goes above this share of the sample rate. */
    private const val CENTRE_CEILING = 0.45f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** TOUCH + AUTO + ROCK times [REACH_GAIN], capped at 1: how much of the filtered signal is heard, and how far the sweep travels. */
    internal fun reach(macros: Map<String, Float>): Float =
        (REACH_GAIN * ((macros["TOUCH"] ?: 0f) + (macros["AUTO"] ?: 0f) + (macros["ROCK"] ?: 0f))).coerceIn(0f, 1f)

    /** AUTO's position, 0 (heel) to 1 (toe): a sine that starts at the heel. */
    internal fun autoAt(t: Float, hz: Float): Float = (0.5 - 0.5 * cos(2.0 * PI * hz * t)).toFloat()

    /** ROCK's position at [t] seconds: up over 250 ms times [scale], down over 500 ms times [scale], then the heel. */
    internal fun rockAt(t: Float, scale: Float): Float {
        val up = ROCK_UP_S * scale
        val down = ROCK_DOWN_S * scale
        return when {
            t < up -> (0.5 - 0.5 * cos(PI * t / up)).toFloat()
            t < up + down -> (0.5 + 0.5 * cos(PI * (t - up) / down)).toFloat()
            else -> 0f
        }
    }

    /** TOUCH's position for a follower level [db] under the pad's own envelope peak: 0 dB is the toe, [FOLLOW_SPAN_DB] under is the heel. */
    internal fun touchAt(db: Float): Float = (1f + db / FOLLOW_SPAN_DB).coerceIn(0f, 1f)

    /** The band-pass centre for a position 0..1: exponential from the heel to the toe, under [CENTRE_CEILING] of [rate]. */
    internal fun centreHz(pos: Float, rate: Int): Float = minOf(Dsp.expMap(pos, HEEL_HZ, TOE_HZ), CENTRE_CEILING * rate)

    /** TOUCH's position for every frame: the larger channel's envelope, in dB under the pad's own peak (a first pass: the rack renders whole pads). */
    internal fun touchPositions(snip: Snip, scale: Float): FloatArray {
        val rate = snip.sampleRate
        val attack = 1f - exp(-1.0 / (rate * FOLLOW_ATTACK_S)).toFloat()
        val release = 1f - exp(-1.0 / (rate * FOLLOW_RELEASE_S * scale)).toFloat()
        val env = FloatArray(snip.frameCount)
        var e = 0f
        var peak = 0f
        for (f in 0 until snip.frameCount) {
            var a = 0f
            for (ch in 0 until snip.channels) a = max(a, abs(snip.samples[f * snip.channels + ch]))
            e += (if (a > e) attack else release) * (a - e)
            env[f] = e
            if (e > peak) peak = e
        }
        if (peak <= 0f) return FloatArray(snip.frameCount)
        return FloatArray(snip.frameCount) { f -> touchAt(20f * log10((env[f] / peak).coerceAtLeast(1e-6f))) }
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val touch = m.getValue("TOUCH")
        val auto = m.getValue("AUTO")
        val rock = m.getValue("ROCK")
        if (touch + auto + rock < BYPASS) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val reach = reach(m)
        val lfoHz = Dsp.expMap(m.getValue("SPEED"), SLOW_HZ, FAST_HZ)
        val scale = DEFAULT_HZ / lfoHz
        val k = Dsp.expMap(1f - m.getValue("Q"), DAMPING_TIGHT, DAMPING_OPEN)
        val touched = if (touch > 0f) touchPositions(snip, scale) else null
        val filters = Array(snip.channels) { Dsp.TptSvf(rate) }

        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            // One position across the frame, so a stereo pair sweeps together.
            val t = f.toFloat() / rate
            var move = 0f
            if (touched != null) move += touch * touched[f]
            if (auto > 0f) move += auto * autoAt(t, lfoHz)
            if (rock > 0f) move += rock * rockAt(t, scale)
            val centre = centreHz((REACH_GAIN * move).coerceIn(0f, 1f), rate)
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                filters[ch].process(x, centre, k)
                val y = k * filters[ch].band + DRY * x
                out[i] = (1f - reach) * x + reach * y
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val gain = inPeak / outPeak
            for (i in out.indices) out[i] *= gain
        }
        return Snip(out, snip.channels, rate)
    }
}
```

- [ ] **Step 4: Run.** `./gradlew :synth:test --tests '*WahTest*'`. Expected: PASS. If `AUTO sweeps ... one and a half octaves` fails for the dry-amp source, the failure message names the source and the octaves: report the numbers (do not loosen the 1.5 bar; the spike measured 2.82 after the landing amp). Tune only the constants marked "(shape)" if a bar fails, and print the table (constant x source x octaves x settle ms) in your report. Mutation checks to report: (a) set `REACH_GAIN` to 1: the reach test fails; (b) make `process` skip the first-pass `touched` (use a constant 1f): the TOUCH test fails; (c) start AUTO at the toe (`+ 0.5 cos`): the shapes test fails.

- [ ] **Step 5: Commit.** `git add synth/src/main/kotlin/com/snipsnap/synth/Wah.kt synth/src/test/kotlin/com/snipsnap/synth/WahTest.kt`; message: `Add the Wah object: a resonant sweep with touch, auto and one-rock moves` plus the trailers.

---

### Task 2: Register the section, and the rack's documents

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`, `README.md`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`, `synth/src/test/kotlin/com/snipsnap/synth/WahTest.kt`

**Interfaces:**
- Consumes: `Wah.MACROS`, `Wah::process`.
- Produces: `FxChain(wah = ...)`, `FxChain.SECTION_NAMES` with `wah` between `contour` and `squash`, `FxChain.withSection("wah", macros)`.

- [ ] **Step 1: Write the failing tests.** In `FxTest.kt`, after the VALVE placement test, add:

```kotlin
    // ---------- WAH ----------

    @Test
    fun `WAH sits right after CONTOUR and before SQUASH, and an absent section keeps old recipes byte-stable`() {
        val names = FxChain.SECTION_NAMES
        assertEquals(names.indexOf("contour") + 1, names.indexOf("wah"), "WAH is not right after CONTOUR: $names")
        assertEquals(names.indexOf("wah") + 1, names.indexOf("squash"), "WAH is not right before SQUASH: $names")

        val without = FxChain(squash = mapOf("AMOUNT" to 0.5f))
        assertTrue(!without.toJsonText().contains("wah"), "no wah key unless the section is set")
        assertEquals(without, FxChain.fromJsonText(without.toJsonText()))

        val with = FxChain(wah = mapOf("AUTO" to 1f))
        assertEquals(with, FxChain.fromJsonText(with.toJsonText()))
        val chained = with.process(snare)
        assertTrue(!chained.samples.contentEquals(snare.samples), "the wah section is a no-op in the chain")
        assertContentEquals(Wah.process(snare, mapOf("AUTO" to 1f)).samples, chained.samples, "the chain's wah row does not run Wah.process")
    }
```
  In `WahTest.kt` add (the end of a note through a hot amp):

```kotlin
    @Test
    fun `a wah in front of a guitar's landing amp still ends clean`() {
        for (voice in MagnetVoice.values()) {
            val landed = Magnet.landingChain(voice).withSection("wah", mapOf("AUTO" to 1f)).process(Magnet.render(voice))
            val end = endDb(landed)
            assertTrue(end <= -50.0, "$voice: the last 20 ms ends $end dB under the peak, over the -50 bar")
        }
    }
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :synth:test --tests '*FxTest*' --tests '*WahTest*'`. Expected: compile error (`wah` unknown to `FxChain`).

- [ ] **Step 3: Register.** In `FxChain.kt`: append `val wah: Map<String, Float>? = null,` after the last field of the constructor (at the time of writing `val valve: Map<String, Float>? = null,`; re-read it, and append after whatever is last). In `SECTIONS`, insert between the `contour` and `squash` rows:

```kotlin
            Section("wah", Wah.MACROS, { it.wah }, { c, m -> c.copy(wah = m) }, Wah::process),
```
  In the class KDoc change the order line to `... → EQ → CONTOUR → WAH → SQUASH → VALVE → ...` and add a rationale paragraph beside the contour and valve ones: "wah after contour and before squash: a wah is the first pedal on the board, a filter in front of the compressor and the amp, and the compressor then levels what the sweep did to the note." In `README.md` change the rack order line (`spike → eq → contour → squash → valve → crunch → ...`, near line 940 when this plan was written) to include `wah` after `contour`, and add `wah` to any prose list of sections or macros in the same README region (grep for `contour` and `CONTOUR` in the README and read each hit).

- [ ] **Step 4: Run.** `./gradlew :synth:test --tests '*FxTest*' --tests '*WahTest*'`. Expected: PASS, including every contract test that iterates `SECTIONS` (deterministic, finite, peak-matched, stereo-identical, JSON round trip, AMT 0 on every neutral). If a contract test fails for `wah`, STOP and report it: do not add an exemption. Report the end-of-note number for each guitar.

- [ ] **Step 5: Commit.** `git add synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt README.md synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt synth/src/test/kotlin/com/snipsnap/synth/WahTest.kt`; message: `Put the wah in the rack between the contour and the squash` plus the trailers.

---

### Task 3: The three characters

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`, `docs/CLI.md`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`

**Interfaces:**
- Consumes: `FxChain(wah = ...)`, `Wah.reach`.
- Produces: characters `wahed` (AUTO 1), `touchwahed` (TOUCH 1), `rockwahed` (ROCK 1), after `compressed` in `Treatments.names`. No pad-sheet chip: the chip joins only if the gate passes.

- [ ] **Step 1: Write the failing tests.** In `TreatmentsTest.kt`: the names-list test ends `..., "driven", "compressed", "wahed", "touchwahed", "rockwahed",` (re-read the list's current end first); add the three to the map in ``the pedal recipes set only their own section, and none sits on a neutral`` (`"wahed" to "wah"`, `"touchwahed" to "wah"`, `"rockwahed" to "wah"`); and add:

```kotlin
    @Test
    fun `the wah recipe is at full reach at the pad sheet's default AMT, and shrinks below it`() {
        val seventy = Treatments.chain("wahed", 0.7f).section("wah")!!
        assertEquals(1f, Wah.reach(seventy), 0f, "AMT 0.7 must give the full wah: the first tap is the one the owner hears")
        val half = Treatments.chain("wahed", 0.5f).section("wah")!!
        assertTrue(Wah.reach(half) < 1f, "AMT 0.5 gives a smaller wah, not the same one")
        assertEquals(null, Treatments.chain("wahed", 1f).section("wah")!!["SPEED"], "SPEED is left out: its neutral is its default")
    }
```

- [ ] **Step 2: Run to verify they fail.** `./gradlew :synth:test --tests '*TreatmentsTest*'`. Expected: FAIL (unknown treatment).

- [ ] **Step 3: Add the characters.** In `Treatments.kt`, after the `"compressed"` entry in `EXTRA`:

```kotlin
        // A wah: a resonant band-pass swept by a free-running sine. AUTO is the owner's pick of the
        // three moves at the pedal board's round-two listen; SPEED and Q are left out (each is its own
        // neutral, so AMT could never move it). The WAH chip is this character once its gate passes.
        "wahed" to FxChain(wah = mapOf("AUTO" to 1.0f)),
        // The same section driven by the pad's own envelope: opens on the hit, closes as the note dies.
        // CLI and saved kits only: no chip of its own.
        "touchwahed" to FxChain(wah = mapOf("TOUCH" to 1.0f)),
        // One heel-to-toe-to-heel gesture, then it waits. CLI and saved kits only.
        "rockwahed" to FxChain(wah = mapOf("ROCK" to 1.0f)),
```
  In `docs/CLI.md`'s `treat` reference (near the lines that list `driven` and `compressed`) add the three characters, one sentence each, in the section's own style, and say that the WAH chip is not on the pad sheet yet.

- [ ] **Step 4: Run.** `./gradlew :synth:test --tests '*TreatmentsTest*' --tests '*FxTest*'`. Expected: PASS (FxTest's "AMT scales every section a treatment sets" now covers the three).

- [ ] **Step 5: Commit.** `git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt docs/CLI.md`; message: `Add the wahed, touchwahed and rockwahed characters` plus the trailers.

---

### Task 4: The gate's clips

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/WahAuditionGenerator.kt`
- Modify: `synth/build.gradle.kts` (a `generateWahAudition` task beside `generatePedalAudition`), `.gitignore` (`testkit/wah-audition/`), `docs/SYNTH_ROADMAP.md` (the pedal board's row)

**Interfaces:**
- Consumes: `Treatments.apply`, `Treatments.chain`, `Wah.process`, `Magnet.render`, `Magnet.landingChain`, `FxChain.withSection`, `Thump.render`, `Vox.render`, `AuditionLevel.level`, `WavWriter`.
- Produces: `testkit/wah-audition/<source>/<id>.wav` (git-ignored) and `manifest.json` (rows `{"source","id","file","label"}`) for the controller's listening page.

- [ ] **Step 1: Write the generator** (read `PedalAuditionGenerator.kt` first: copy its `mono` fold and `write` helper, including the comment on why a stereo clip is folded):

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the WAH section's gate clips under testkit/wah-audition/ (gitignored), for the owner's
 * gate in docs/superpowers/specs/2026-10-17-wah-section-design.md: the shipped section (not the
 * round-two prototype) on a snare, a choir and both MAGNET guitars. AUTO (the `wahed` character) at
 * the pad sheet's AMT 70 and at 100, TOUCH and ROCK at 100 beside it, and the ends of SPEED and Q
 * (AUTO at 100) on the choir and on each guitar. Each guitar twice: tapped on the baked pad (as the
 * pad sheet does it) and in front of its landing amp (a rig: the wah section added to the landing
 * chain). Every clip shares one loudness ([AuditionLevel]). Writes manifest.json. Run via
 * `./gradlew :synth:generateWahAudition`.
 */
object WahAuditionGenerator {

    private val AMOUNTS = listOf(0.7f, 1f)

    private fun pct(amount: Float) = (amount * 100).roundToInt()

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/wah-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json") }.forEach { it.delete() }
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
        val ends = listOf(
            "speed_0" to mapOf("AUTO" to 1f, "SPEED" to 0f),
            "speed_100" to mapOf("AUTO" to 1f, "SPEED" to 1f),
            "q_0" to mapOf("AUTO" to 1f, "Q" to 0f),
            "q_100" to mapOf("AUTO" to 1f, "Q" to 1f),
        )
        val plain = linkedMapOf("SNARE" to Thump.render(ThumpVoice.SNARE), "CHOIR" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in plain) {
            write(name, "dry", "$name DRY", dry)
            for (amount in AMOUNTS) write(name, "auto_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}", Treatments.apply("wahed", dry, amount).snip)
            write(name, "touch_100", "$name WAH TOUCH AT 100", Treatments.apply("touchwahed", dry, 1f).snip)
            write(name, "rock_100", "$name WAH ROCK AT 100", Treatments.apply("rockwahed", dry, 1f).snip)
        }
        for ((id, macros) in ends) write("CHOIR", id, "CHOIR WAH AUTO, ${id.uppercase().replace('_', ' ')}", Wah.process(plain.getValue("CHOIR"), macros))
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val landing = Magnet.landingChain(voice)
            val baked = landing.process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for (amount in AMOUNTS) write(name, "sheet_auto_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}, ON THE BAKED PAD (AS THE PAD SHEET DOES IT)", Treatments.apply("wahed", baked, amount).snip)
            fun rig(id: String, label: String, macros: Map<String, Float>) =
                write(name, id, "$name $label, IN FRONT OF ITS LANDING AMP (A RIG)", landing.withSection("wah", macros).process(dry))
            for (amount in AMOUNTS) rig("rig_auto_${pct(amount)}", "WAH AUTO AT ${pct(amount)}", Treatments.chain("wahed", amount).section("wah")!!)
            rig("rig_touch_100", "WAH TOUCH AT 100", mapOf("TOUCH" to 1f))
            rig("rig_rock_100", "WAH ROCK AT 100", mapOf("ROCK" to 1f))
            for ((id, macros) in ends) rig("rig_$id", "WAH AUTO, ${id.uppercase().replace('_', ' ')}", macros)
        }
        check(entries.size == 36) { "expected 36 clips (SNARE 5, CHOIR 9, two guitars 11 each), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("wah audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
```

- [ ] **Step 2: Register the task and ignore the output.** In `synth/build.gradle.kts`, after the `generatePedalAudition` block:

```kotlin
/** Render the WAH section's gate clips and manifest under testkit/wah-audition/. See WahAuditionGenerator. */
tasks.register<JavaExec>("generateWahAudition") {
    group = "distribution"
    description = "Render the WAH section's gate clips and manifest under testkit/wah-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.WahAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/wah-audition")
}
```
  and add `testkit/wah-audition/` to `.gitignore` beside `testkit/pedal-audition/`.

- [ ] **Step 3: Run it.** `./gradlew :synth:generateWahAudition`. Expected: `wah audition: 36 clips`, `manifest.json` parses, 36 non-empty WAVs with distinct hashes, `git status --short` shows only the Kotlin, gradle and `.gitignore` files. Listen-free checks to report per clip family: the centroid range and settle time of `auto_100` on the choir and `rig_auto_100` on each guitar (reuse WahTest's helpers by copying them into a scratch run, not into the generator).

- [ ] **Step 4: The roadmap row.** In `docs/SYNTH_ROADMAP.md`, extend the pedal board's row (S30) in its own style and length: the WAH section built (a resonant band-pass with touch, auto and one-rock moves held as weights, after CONTOUR and before SQUASH), awaiting its owner gate; the WAH chip joins the PEDALS row only if it passes.

- [ ] **Step 5: Full verification.** `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test`: BUILD SUCCESSFUL, 0 failures; quote the three totals from the result XML files. `git status --short` shows only intended files.

- [ ] **Step 6: Commit.** Message: `Render the WAH section's gate clips and note the stage in the roadmap` plus the trailers. Then STOP: the controller builds the listening page from `testkit/wah-audition/manifest.json` and the owner decides at the gate. **Task 5 (the WAH chip: `PEDAL_SEGMENTS`, `CHARACTER_FOR`, `PadSheetTest`'s counts) is written after the gate, only if it passes.**
