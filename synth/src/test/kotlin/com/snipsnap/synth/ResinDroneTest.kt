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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-25-resin-drone-design.md, "What the probe measured". */
class ResinDroneTest {

    /** One 1-bar interval at 90 BPM, a fresh loop-grid session's. */
    private fun interval(rate: Int): Long = (4 * (60.0 / 90) * rate).roundToInt().toLong()

    /** Energy of period 2 minus period 1, over period 1's: the seam metric across a whole loop. */
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

    private fun spec(voice: ResinVoice, stack: Float, cutoff: Float, cream: Float, motion: Float, rate: Int) =
        ResinDrone.Spec(voice, mapOf("STACK" to stack, "CUTOFF" to cutoff, "CREAM" to cream), motion, rate)

    /** The probe's pre-roll table: the slowest-settling corners it found. */
    private val corners = listOf(
        Triple(spec(ResinVoice.BASS, 0.8f, 0.1f, 1f, 1f, 1), 33, "BASS dark, full motion"),
        Triple(spec(ResinVoice.BASS, 0.8f, 0f, 1f, 1f, 4), 33, "BASS darkest, fast breaths"),
        Triple(spec(ResinVoice.BRASS, 0.8f, 0.1f, 1f, 1f, 1), 45, "BRASS dark"),
        Triple(spec(ResinVoice.LEAD, 0.8f, 0.1f, 1f, 0.5f, 4), 57, "LEAD dark"),
        Triple(spec(ResinVoice.BASS, 0.3f, 0f, 1f, 0f, 1), 33, "BASS slowest ring"),
    )

    @Test
    fun `every corner loops to the bit at both session rates`() {
        for (rate in listOf(44_100, 48_000)) {
            val frames = interval(rate)
            for ((s, root, label) in corners) {
                val two = ResinDrone.synthesize(s, root, frames, rate, periods = 2)
                val d = periodDiff(two, frames.toInt())
                assertTrue(d < 1e-12, "$label at $rate Hz: period to period $d")
                val seam = Keys.seamError(two, frames.toInt())
                assertTrue(seam < 1e-12, "$label at $rate Hz: seam $seam")
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

    /**
     * The note plays where the grid thinks it does: all its energy sits on
     * exactly 2m cycles per loop, m the snapped sub-octave count, with none
     * a cycle either side. A whole-loop measure, exact where a pitch
     * detector is good to a couple of percent.
     */
    @Test
    fun `the note is the snapped note, and within the tuning promise`() {
        val rate = 44_100
        val clean = mapOf("STACK" to 0f, "CUTOFF" to 0.8f, "CREAM" to 0.2f)
        for ((voice, root, span) in listOf(Triple(ResinVoice.BASS, 33, 4), Triple(ResinVoice.BRASS, 45, 2), Triple(ResinVoice.LEAD, 57, 1))) {
            val frames = span * interval(rate)
            val s = ResinDrone.render(ResinDrone.Spec(voice, clean, 0f, 1), root, frames, rate)
            val seconds = frames.toDouble() / rate
            val rootHz = 440.0 * 2.0.pow((root - 69) / 12.0)
            val m = Math.round(rootHz / 2 * seconds)
            val on = bin(s, 2 * m)
            val off = maxOf(bin(s, 2 * m - 1), bin(s, 2 * m + 1))
            assertTrue(off < on * 1e-4, "$voice: ${2 * m} cycles $on, a cycle off $off")
            val cents = 1200 * ln(2.0 * m / seconds / rootHz) / ln(2.0)
            assertTrue(abs(cents) <= 3.0, "$voice at a $span-bar span is $cents cents off")
        }
    }

    /** Brightness per window: first-difference energy over energy. */
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

    /** The breath rate that dominates a brightness series: the strongest non-DC cycle count per loop. */
    private fun dominantCycles(v: DoubleArray): Int {
        val logs = v.map { kotlin.math.ln(it) }
        val mean = logs.average()
        return (1..v.size / 2).maxBy { c ->
            var re = 0.0
            var im = 0.0
            for (i in logs.indices) {
                val w = 2 * PI * c * i / logs.size
                re += (logs[i] - mean) * cos(w)
                im += (logs[i] - mean) * sin(w)
            }
            re * re + im * im
        }
    }

    @Test
    fun `MOTION breathes the filter RATE times a drone, and not at all at zero`() {
        val rate = 44_100
        val frames = 2 * interval(rate)
        for (breaths in ResinDrone.RATES) {
            val s = ResinDrone.render(spec(ResinVoice.BASS, 0.8f, 0.3f, 0.3f, 1f, breaths), 33, frames, rate)
            val b = brightness(s, 64)
            assertEquals(breaths, dominantCycles(b), "RATE $breaths: brightness ${b.joinToString { "%.3g".format(it) }}")
            assertTrue(b.max() / b.min() > 2.0, "RATE $breaths barely moves: ${b.min()}..${b.max()}")
        }
        // At STACK 0.3 the detuned square is silent (Resin.stackGains), so
        // its beat against the saws can't move the brightness: what moves
        // here is the filter, and only MOTION moves it.
        // Sixteen windows, not 64: a 55 Hz note needs a window of many
        // cycles, or where it cuts the waveform moves the reading by itself.
        val still = brightness(ResinDrone.render(spec(ResinVoice.BASS, 0.3f, 0.3f, 0.3f, 0f, 1), 33, frames, rate), 16)
        val moving = brightness(ResinDrone.render(spec(ResinVoice.BASS, 0.3f, 0.3f, 0.3f, 1f, 1), 33, frames, rate), 16)
        assertTrue(still.max() / still.min() < 1.2, "MOTION 0 still moves: ${still.min()}..${still.max()}")
        assertTrue(moving.max() / moving.min() > 2.0, "MOTION 1 at STACK 0.3 barely moves: ${moving.min()}..${moving.max()}")
    }

    @Test
    fun `CONTOUR, DECAY and TUNE have nothing to act on`() {
        val rate = 44_100
        val frames = interval(rate)
        val a = ResinDrone.render(ResinDrone.Spec(ResinVoice.BRASS, mapOf("CONTOUR" to 0f, "DECAY" to 0f, "TUNE" to 0f), 0.5f, 2), 45, frames, rate)
        val b = ResinDrone.render(ResinDrone.Spec(ResinVoice.BRASS, mapOf("CONTOUR" to 1f, "DECAY" to 1f, "TUNE" to 1f), 0.5f, 2), 45, frames, rate)
        assertContentEquals(a, b)
    }

    @Test
    fun `the same recipe renders the same drone, at the melodic level`() {
        val rate = 48_000
        val frames = interval(rate)
        val s = ResinDrone.Spec(ResinVoice.LEAD, Resin.defaults(ResinVoice.LEAD), 0.4f, 2)
        val a = ResinDrone.render(s, 60, frames, rate)
        assertContentEquals(a, ResinDrone.render(s, 60, frames, rate))
        assertEquals(frames.toInt(), a.size)
        val db = 20 * log10(Loudness.of(Snip(a, 1, rate)) / Dsp.MELODIC_LOUDNESS_TARGET)
        assertTrue(abs(db) < 1.0, "loudness $db dB off the target")
        assertTrue(a.all { abs(it) <= 0.99f })
    }

    @Test
    fun `a spec round-trips through JSON, and another engine's recipe is not a RESIN spec`() {
        val s = ResinDrone.Spec(ResinVoice.BASS, mapOf("CUTOFF" to 0.35f, "CREAM" to 0.6f), 0.7f, 4)
        assertEquals(s, ResinDrone.Spec.fromJson(Json.parse(Json.write(s.toJson()))))
        assertNull(ResinDrone.Spec.fromJson(Json.parse("""{"engine":"VELVET","voice":"BASS","macros":{},"motion":0,"rate":1}""")))
        assertNull(ResinDrone.Spec.fromJson(Json.parse("""{"engine":"RESIN","voice":"KAZOO","macros":{},"motion":0,"rate":1}""")))
        assertNull(ResinDrone.Spec.fromJson(Json.parse("""{"engine":"RESIN","voice":"BASS","macros":{},"motion":0,"rate":3}""")))
    }

    @Test
    fun `a spec refuses a motion or rate the loop cannot close on`() {
        assertFailsWith<IllegalArgumentException> { ResinDrone.Spec(ResinVoice.BASS, emptyMap(), 1.5f, 1) }
        assertFailsWith<IllegalArgumentException> { ResinDrone.Spec(ResinVoice.BASS, emptyMap(), 0.5f, 3) }
    }

    /** Not a timing test: a guard at four times the probe's cost, and the numbers for the spec. */
    @Test
    fun `cost stays in the range the probe measured`() {
        val rate = 44_100
        val frames = 4 * interval(rate)
        val rt = Runtime.getRuntime()
        rt.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        ResinDrone.render(ResinDrone.Spec(ResinVoice.BASS, emptyMap(), 0.5f, 1), 33, frames, rate)
        val cpu = (System.nanoTime() - t0) / 1e9
        val used = (rt.totalMemory() - rt.freeMemory() - before) / (1024.0 * 1024.0)
        val perSecond = cpu / (frames.toDouble() / rate + ResinDrone.DRONE_PREROLL_SECONDS)
        println("drone cost: %.2f s for %.2f s of loop, %.3f s per rendered second, ~%.0f MB live after".format(cpu, frames.toDouble() / rate, perSecond, used))
        assertTrue(perSecond < 1.0, "a drone costs $perSecond s per rendered second")
    }
}
