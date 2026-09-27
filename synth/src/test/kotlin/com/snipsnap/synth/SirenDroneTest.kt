package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 4 (S12.3). */
class SirenDroneTest {

    /** One 1-bar interval at 90 BPM, a fresh loop-grid session's — ResinDroneTest's own fixture. */
    private fun interval(rate: Int): Long = (4 * (60.0 / 90) * rate).roundToInt().toLong()

    /** Energy of period 2 minus period 1, over period 1's — ResinDroneTest's own seam metric. */
    private fun periodDiff(two: FloatArray, frames: Int): Double {
        var d = 0.0
        var e = 0.0
        for (i in 0 until frames) {
            val a = two[i].toDouble()
            val b = two[frames + i].toDouble()
            d += (b - a) * (b - a)
            e += a * a
        }
        return d / e
    }

    private val corners = listOf(
        Triple(SirenDrone.Spec(SirenVoice.WAIL, mapOf("RATE" to 0f, "DEPTH" to 1f, "GRIT" to 1f)), 60, "WAIL slowest RATE, full DEPTH and GRIT"),
        Triple(SirenDrone.Spec(SirenVoice.TRILL, mapOf("RATE" to 1f, "DEPTH" to 1f, "GRIT" to 1f)), 63, "TRILL fastest RATE, full DEPTH and GRIT (a genuine step LFO)"),
        Triple(SirenDrone.Spec(SirenVoice.LASER, mapOf("RATE" to 0.5f, "DEPTH" to 0.5f, "GRIT" to 0f)), 66, "LASER mid, no GRIT"),
        Triple(SirenDrone.Spec(SirenVoice.BIRD, mapOf("RATE" to 0f, "DEPTH" to 0f, "GRIT" to 1f)), 69, "BIRD, DEPTH at zero"),
    )

    @Test
    fun `every corner loops to the bit at both session rates`() {
        for (rate in listOf(44_100, 48_000)) {
            val frames = interval(rate)
            for ((s, root, label) in corners) {
                val two = SirenDrone.synthesize(s, root, frames, rate, periods = 2)
                val d = periodDiff(two, frames.toInt())
                assertTrue(d < 1e-8, "$label at $rate Hz: period to period $d")
                val seam = Keys.seamError(two, frames.toInt())
                assertTrue(seam < 1e-8, "$label at $rate Hz: seam $seam")
            }
        }
    }

    /** Magnitude of the component that completes exactly [cycles] cycles in [s]. */
    private fun bin(s: FloatArray, cycles: Long): Double {
        var re = 0.0
        var im = 0.0
        for (i in s.indices) {
            val w = 2 * PI * cycles * i / s.size
            re += s[i] * cos(w)
            im -= s[i] * sin(w)
        }
        return kotlin.math.hypot(re, im) / s.size
    }

    @Test
    fun `the note is the snapped note, within the tuning promise, at DEPTH zero`() {
        val rate = 44_100
        val clean = mapOf("RATE" to 0.5f, "DEPTH" to 0f, "GRIT" to 0.2f)
        for ((voice, root, span) in listOf(Triple(SirenVoice.WAIL, 60, 4), Triple(SirenVoice.LASER, 66, 2), Triple(SirenVoice.BIRD, 72, 1))) {
            val frames = span * interval(rate)
            val s = SirenDrone.render(SirenDrone.Spec(voice, clean), root, frames, rate)
            val seconds = frames.toDouble() / rate
            val rootHz = 440.0 * 2.0.pow((root - 69) / 12.0)
            val m = Math.round(rootHz * seconds)
            val on = bin(s, m)
            val off = maxOf(bin(s, m - 1), bin(s, m + 1))
            assertTrue(off < on * 1e-3, "$voice: $m cycles $on, a cycle off $off")
            val cents = 1200 * ln(m / seconds / rootHz) / ln(2.0)
            assertTrue(abs(cents) <= 3.0, "$voice at a $span-bar span is $cents cents off")
        }
    }

    /** Brightness per window: first-difference energy over energy — ResinDroneTest's own probe. */
    private fun brightness(s: FloatArray, windows: Int): DoubleArray {
        val w = s.size / windows
        return DoubleArray(windows) { k ->
            var e = 1e-12
            var d = 0.0
            for (i in k * w + 1 until (k + 1) * w) {
                e += s[i].toDouble() * s[i]
                val x = s[i] - s[i - 1].toDouble()
                d += x * x
            }
            d / e
        }
    }

    @Test
    fun `RATE moves the wail, and not at all at DEPTH zero`() {
        val rate = 44_100
        val frames = 2 * interval(rate)
        val moving = brightness(SirenDrone.render(SirenDrone.Spec(SirenVoice.WAIL, mapOf("RATE" to 0.5f, "DEPTH" to 1f, "GRIT" to 0.3f)), 60, frames, rate), 32)
        assertTrue(moving.max() / moving.min() > 2.0, "DEPTH 1 barely moves: ${moving.min()}..${moving.max()}")
        val still = brightness(SirenDrone.render(SirenDrone.Spec(SirenVoice.WAIL, mapOf("RATE" to 0.5f, "DEPTH" to 0f, "GRIT" to 0.3f)), 60, frames, rate), 32)
        assertTrue(still.max() / still.min() < 1.2, "DEPTH 0 still moves: ${still.min()}..${still.max()}")
    }

    @Test
    fun `TUNE, HOLD and SWEEP have nothing to act on`() {
        val rate = 44_100
        val frames = interval(rate)
        val a = SirenDrone.render(SirenDrone.Spec(SirenVoice.LASER, mapOf("TUNE" to 0f, "HOLD" to 0f, "SWEEP" to 0f)), 66, frames, rate)
        val b = SirenDrone.render(SirenDrone.Spec(SirenVoice.LASER, mapOf("TUNE" to 1f, "HOLD" to 1f, "SWEEP" to 1f)), 66, frames, rate)
        assertContentEquals(a, b)
    }

    @Test
    fun `the same recipe renders the same drone, at the melodic level`() {
        val rate = 48_000
        val frames = interval(rate)
        val s = SirenDrone.Spec(SirenVoice.LASER, Siren.defaults(SirenVoice.LASER))
        val a = SirenDrone.render(s, 66, frames, rate)
        assertContentEquals(a, SirenDrone.render(s, 66, frames, rate))
        assertEquals(frames.toInt(), a.size)
        val db = 20 * log10(Loudness.of(Snip(a, 1, rate)) / Dsp.MELODIC_LOUDNESS_TARGET)
        assertTrue(abs(db) < 1.0, "loudness $db dB off the target")
        assertTrue(a.all { abs(it) <= 0.99f })
    }

    @Test
    fun `a spec round-trips through JSON, and another engine's recipe is not a SIREN spec`() {
        val s = SirenDrone.Spec(SirenVoice.TRILL, mapOf("RATE" to 0.4f, "DEPTH" to 0.6f))
        assertEquals(s, SirenDrone.Spec.fromJson(Json.parse(Json.write(s.toJson()))))
        assertNull(SirenDrone.Spec.fromJson(Json.parse("""{"engine":"RESIN","voice":"TRILL","macros":{}}""")))
        assertNull(SirenDrone.Spec.fromJson(Json.parse("""{"engine":"SIREN","voice":"KAZOO","macros":{}}""")))
    }

    @Test
    fun `a render nobody wants any more stops, fast`() {
        val frames = 4 * interval(44_100)
        var asked = 0
        val t0 = System.nanoTime()
        assertFailsWith<java.util.concurrent.CancellationException> {
            SirenDrone.render(SirenDrone.Spec(SirenVoice.WAIL, emptyMap()), 60, frames, 44_100) { ++asked > 3 }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_000, "a cancelled render ran on for ${ms}ms")
        assertEquals(4, asked)
    }

    @Test
    fun `a render stops when its thread is interrupted`() {
        var thrown: Throwable? = null
        val t = Thread {
            thrown = runCatching {
                SirenDrone.render(SirenDrone.Spec(SirenVoice.WAIL, emptyMap()), 60, 8 * interval(44_100), 44_100)
            }.exceptionOrNull()
        }
        t.start()
        Thread.sleep(200)
        t.interrupt()
        t.join(5_000)
        assertTrue(!t.isAlive, "the render ignored the interrupt")
        assertTrue(thrown is java.util.concurrent.CancellationException, "expected a cancellation, got $thrown")
    }
}
