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

    /**
     * Spec §4.2 and §4.6. The step across each wrap is never the largest step: the rounded window and
     * the sine are both exactly zero there, so the wrap is not a click at any DEPTH. (Not "the biggest
     * step shrinks as DEPTH rises": each render is levelled on its own, and normalised by RMS the saw
     * and the rounded window do not differ that way.) Raw buffers, BLOOM still so that k is constant.
     *
     * The step into a cycle's last sample is not counted among the inner steps. A window that ends above
     * zero, over the last sample alone, leaves a one-sample spike: its rising edge is the step into that
     * last sample and its falling edge is the step across the wrap. Counted as an inner step, the rising
     * edge lifts the largest inner step as high as the falling edge, so the two rise together and the
     * click goes unseen. (Moving the shape table's last entry to 0.5 makes such a spike on VOWEL.)
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
                    if (i in starts) {
                        wrapStep = maxOf(wrapStep, d)
                    } else if ((i + 1) !in starts) {
                        innerStep = maxOf(innerStep, d)
                    }
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
            // Measured worst on the engine: 1.1e-11. The window is a Float table, about 6e-8 per entry, so
            // 1e-6 is still well above float noise, and it catches a subtle divergence between the shaped
            // loop and the saw loop, not only a gross one.
            assertTrue(worst < 1e-6f, "$voice: DEPTH 1e-9 moved the render by $worst")
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
}
