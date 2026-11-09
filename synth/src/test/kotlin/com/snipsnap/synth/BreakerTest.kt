package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BREAKER's probes (docs/BREAKER.md): the house set (finite, bounded, centred, its length to the frame,
 * every control moves it, JSON, non-finite fallback, velocity and takes) and the three claims the
 * owner heard at round 0: the spring's echoes rise and fall away, DRIP puts that spring after the hit,
 * and CRUSH past halfway swells the tail back up behind it.
 */
class BreakerTest {
    private val rate = Dsp.RATE
    private val frames = (Breaker.SECONDS * rate).toInt()

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt(); val b = minOf(x.size, (to * rate).toInt())
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, b - a))
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    /** The tail (100 to 400 ms) against the hit (its first 20 ms), in dB. */
    private fun tailToHit(x: FloatArray) = db(rms(x, .1, .4)) - db(rms(x, 0.0, .02))

    private fun band(x: FloatArray, hz: Float, q: Float): FloatArray {
        val bp = Dsp.Biquad().apply { bandpass(hz, q, rate) }
        val bp2 = Dsp.Biquad().apply { bandpass(hz, q, rate) }
        return FloatArray(x.size) { bp2.process(bp.process(x[it])) }
    }

    @Test
    fun `every hit is finite, bounded, centred and exactly its length`() {
        for (voice in BreakerVoice.entries) {
            val corners = listOf(emptyMap(), Breaker.macrosFor(voice).associate { it.name to 0f }, Breaker.macrosFor(voice).associate { it.name to 1f })
            for (m in corners) {
                val x = Breaker.render(voice, m).samples
                assertEquals(frames, x.size, "$voice $m length")
                assertTrue(frames < (1.5 * rate).toInt(), "a hit stays under the loop threshold")
                assertTrue(x.all { it.isFinite() }, "$voice $m finite")
                val peak = x.maxOf { abs(it) }
                assertTrue(peak in .01f..1f, "$voice $m peak $peak")
                assertTrue(abs(x.average()) < .005, "$voice $m DC ${x.average()}")
            }
        }
    }

    @Test
    fun `every control changes the sound and an unknown key does not`() {
        for (voice in BreakerVoice.entries) {
            val base = Breaker.render(voice).samples
            for (spec in Breaker.macrosFor(voice)) {
                val lo = Breaker.render(voice, mapOf(spec.name to .15f)).samples
                val hi = Breaker.render(voice, mapOf(spec.name to .85f)).samples
                val diff = lo.indices.sumOf { abs(lo[it] - hi[it]).toDouble() } / lo.size
                assertTrue(diff > 1e-4, "$voice ${spec.name} moves the sound (mean |diff| $diff)")
            }
            assertContentEquals(base, Breaker.render(voice, mapOf("NOT_A_CONTROL" to .3f)).samples, "$voice ignores an unknown key")
            assertContentEquals(base, Breaker.render(voice, mapOf("DUST" to Float.NaN)).samples, "$voice falls back on a non-finite control")
        }
    }

    @Test
    fun `past halfway CRUSH swells the tail back up behind the hit`() {
        for (voice in BreakerVoice.entries) {
            val r = listOf(.2f, .5f, .9f).map { tailToHit(Breaker.render(voice, mapOf("CRUSH" to it, "DRIP" to .6f)).samples) }
            println("BREAKER $voice tail-to-hit at CRUSH .2/.5/.9 (DRIP .6): ${r.map { "%.1f".format(it) }} dB")
            assertTrue(r[2] > r[1] + 1.0 && r[2] > r[0] + 1.0, "$voice: the crush after the spring lifts the tail ($r)")
        }
    }

    @Test
    fun `DRIP puts the spring after the hit`() {
        for (voice in BreakerVoice.entries) {
            val e = listOf(.0f, .6f).map {
                val y = band(Breaker.render(voice, mapOf("DRIP" to it, "SLAP" to 0f)).samples, 2000f, .7f)
                db(rms(y, .05, .4)) - db(rms(y, 0.0, .05))
            }
            println("BREAKER $voice 2 kHz band after the hit against its first 50 ms, DRIP 0 / .6: ${e.map { "%.1f".format(it) }} dB")
            assertTrue(e[1] > e[0] + 3.0, "$voice: the spring rings after the hit ($e)")
        }
    }

    @Test
    fun `the tank's echoes rise in pitch and fall away`() {
        val impulse = DoubleArray(frames).also { it[10] = 1.0 }
        val ir = BreakerChain.spring(impulse, .6, frames, rate)
        val window = (.1 * rate).toInt()
        val energy = (0 until frames / window).map { w -> (w * window until (w + 1) * window).sumOf { ir[it] * ir[it] } }
        val levels = energy.map { 10 * log10(it / energy[0] + 1e-30) }
        println("BREAKER tank energy per 100 ms, dB re the first: ${levels.map { "%.1f".format(it) }}")
        for (w in 1 until levels.size) assertTrue(levels[w] < levels[w - 1] + .5, "the tank never gains energy (window $w: ${levels[w]} after ${levels[w - 1]})")
        assertTrue(levels.last() < levels.first() - 30, "the tank decays")
        // The first echo: its 2.5 kHz arrives after its 300 Hz (lows travel faster in a spring).
        val first = FloatArray((.08 * rate).toInt()) { ir[it].toFloat() }
        fun arrival(hz: Float): Int { val y = band(first, hz, 2f); return y.indices.maxBy { abs(y[it]) } }
        val low = arrival(300f); val high = arrival(2500f)
        println("BREAKER first echo: 300 Hz peaks at ${"%.1f".format(low * 1000.0 / rate)} ms, 2.5 kHz at ${"%.1f".format(high * 1000.0 / rate)} ms")
        assertTrue(high > low, "the chirp rises: highs arrive later")
    }

    @Test
    fun `velocity and takes strike it again, deterministically`() {
        for (voice in BreakerVoice.entries) {
            val patch = BreakerPatch("Probe", voice, emptyMap())
            val full = patch.render(1f).samples
            val soft = patch.render(.5f).samples
            assertFalse(full.contentEquals(soft), "$voice: velocity changes the strike")
            assertTrue(Restrike.canRestrike(patch), "$voice re-strikes")
            val take1 = assertNotNull(Restrike.take(patch, 1f, 7, 1)).samples
            assertEquals(full.size, take1.size)
            assertFalse(full.contentEquals(take1), "$voice: a second take is a fresh strike")
            assertContentEquals(take1, assertNotNull(Restrike.take(patch, 1f, 7, 1)).samples, "$voice: takes are deterministic")
        }
    }

    @Test
    fun `a saved patch round-trips through JSON with its model`() {
        for (voice in BreakerVoice.entries) {
            val patch = BreakerPatch("Round trip", voice, mapOf("DRIP" to .3f, "CRUSH" to .7f))
            val back = BreakerPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, back)
            assertEquals(Breaker.MODEL_VERSION, back.model)
        }
    }

    @Test
    fun `what the classifier hears, and the render cost`() {
        for (voice in BreakerVoice.entries) {
            Breaker.render(voice)
            val t0 = System.nanoTime()
            val snip = Breaker.render(voice)
            val ms = (System.nanoTime() - t0) / 1e6
            println("BREAKER $voice: classified ${Classifier.classify(snip).drumClass}, declared ${Breaker.drumClassFor(voice)}, render ${"%.0f".format(ms)} ms (desktop JVM, warm)")
        }
    }
}
