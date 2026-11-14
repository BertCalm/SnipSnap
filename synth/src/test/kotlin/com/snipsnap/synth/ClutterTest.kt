package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CLUTTER round 1: the house probes on every object, and the claims the design makes about its physics turned
 * into tests (a soft hit leaves the rice still; the pan's rivet only buzzes when the pan swings past its play;
 * the bucket's boom falls). Distances use the prototype's measure (prototype/clutter/measures.py): the RMS
 * difference of level-matched third-octave spectrograms, every band floored 80 dB under the loudest.
 */
class ClutterTest {

    private val all = ClutterVoice.entries

    private fun render(v: ClutterVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Clutter.render(v, macros, velocity, take).samples

    @Test fun `every object is finite, peak-matched, centred and as long as its object`() {
        for (v in all) {
            val y = render(v)
            assertTrue(y.all { it.isFinite() }, "$v finite")
            val peak = y.maxOf { abs(it) }
            assertEquals(Clutter.PEAK, peak, 1e-4f, "$v peak")
            assertTrue(abs(y.average()) < 0.005, "$v DC ${y.average()}")
            // The object's length at 44.1 kHz plus the MIC tail (12 % of a 120 ms window per unit of MIC).
            val expected = (v.seconds * Clutter.RATE).toInt() + (0.12 * Clutter.RATE * Clutter.defaults(v).getValue("MIC")).toInt()
            assertTrue(abs(y.size - expected) <= 64, "$v length ${y.size} against $expected")
        }
    }

    @Test fun `every take replays byte for byte and the four takes are four strikes`() {
        for (v in all) {
            val takes = (0..Clutter.MAX_TAKE).map { render(v, take = it) }
            for ((t, y) in takes.withIndex()) assertContentEquals(y, render(v, take = t), "$v take $t replays")
            var closest = Double.MAX_VALUE
            for (a in takes.indices) for (b in a + 1 until takes.size) closest = min(closest, distanceDb(takes[a], takes[b]))
            // ETCH's takes that the owner heard as one strike measured 0.5 to 0.8 dB on the unfloored measure.
            assertTrue(closest >= 1.0, "$v closest takes $closest dB")
        }
    }

    @Test fun `velocity changes the strike, not only the level`() {
        for (v in all) {
            val d = distanceDb(render(v, velocity = 0.35f), render(v, velocity = 1f))
            assertTrue(d >= 3.0, "$v soft against hard at matched level: $d dB")
        }
    }

    @Test fun `a soft hit leaves the loose parts lying still, a hard one throws them`() {
        // Below the fixed lift threshold the rattle branch never runs: RATTLE cannot change a soft hit at all.
        for (v in listOf(ClutterVoice.BOX, ClutterVoice.POT, ClutterVoice.BUCKET, ClutterVoice.TABLE, ClutterVoice.MUG)) {
            assertContentEquals(render(v, mapOf("RATTLE" to 0f), velocity = 0.3f), render(v, mapOf("RATTLE" to 0.5f), velocity = 0.3f), "$v soft")
            val hard = distanceDb(render(v, mapOf("RATTLE" to 0f)), render(v, mapOf("RATTLE" to 0.5f)))
            assertTrue(hard >= 1.5, "$v hard: RATTLE moved it $hard dB")
        }
    }

    @Test fun `the pan's rivet only buzzes when the pan swings past its play`() {
        val v = ClutterVoice.PAN
        assertContentEquals(render(v, mapOf("RATTLE" to 0f), velocity = 0.35f), render(v, mapOf("RATTLE" to 0.5f), velocity = 0.35f), "soft: inside the play")
        assertFalse(render(v, mapOf("RATTLE" to 0f)).contentEquals(render(v, mapOf("RATTLE" to 0.5f))), "hard: past the play")
    }

    /**
     * The bucket's plastic stiffens at large displacement, so its boom starts high and falls (the prototype: 77 Hz
     * in the first 30 ms, 64 Hz by 100 to 300 ms). A dominant-frequency measure cannot also prove the suitcase does
     * NOT glide: its lid (78 Hz) and its air (52 Hz) fade at different rates, so the loudest one changes with no
     * glide at all. That half of the claim stays a design statement, not a test.
     */
    @Test fun `the bucket's boom falls`() {
        val bucket = render(ClutterVoice.BUCKET)
        val early = dominantHz(bucket, 0.004, 0.03, 30.0, 300.0)
        val late = dominantHz(bucket, 0.1, 0.3, 30.0, 300.0)
        assertTrue(early > late * 1.1, "bucket $early Hz early, $late Hz late")
    }

    @Test fun `every control moves every object and an unknown control or a bad value changes nothing`() {
        for (v in all) {
            for (mac in listOf("TUNE", "TOOL", "RATTLE", "DAMP", "MIC")) {
                val d = fullDistanceDb(render(v, mapOf(mac to 0f)), render(v, mapOf(mac to 1f)))
                assertTrue(d >= 1.5, "$v $mac moved it $d dB between 0 and 1")
            }
            val plain = render(v)
            assertContentEquals(plain, render(v, mapOf("NOPE" to 0.9f)), "$v unknown control")
            assertContentEquals(plain, render(v, mapOf("TOOL" to Float.NaN)), "$v NaN control")
            assertContentEquals(plain, render(v, velocity = Float.NaN), "$v NaN velocity")
        }
    }

    @Test fun `the patch carries velocity, take and model and refuses what it cannot render`() {
        val p = ClutterPatch("Pot", ClutterVoice.POT, mapOf("RATTLE" to 0.8f), velocity = 0.6f, take = 2)
        val back = ClutterPatch.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertEquals("CLUTTER", back.engine)
        assertFailsWith<IllegalArgumentException> { ClutterPatch("x", ClutterVoice.POT, emptyMap(), take = Clutter.MAX_TAKE + 1) }
        assertFailsWith<IllegalArgumentException> { ClutterPatch("x", ClutterVoice.POT, emptyMap(), velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { ClutterPatch("x", ClutterVoice.POT, emptyMap(), model = Clutter.MODEL_VERSION + 1) }
    }

    @Test fun `every object files as the kit role it plays`() {
        val kicks = all.filter { Clutter.drumClassFor(it) == com.snipsnap.audio.DrumClass.KICK }.toSet()
        assertEquals(setOf(ClutterVoice.BUCKET, ClutterVoice.SUITCASE, ClutterVoice.STOMP), kicks)
        assertEquals(com.snipsnap.audio.DrumClass.SNARE, Clutter.drumClassFor(ClutterVoice.BOX))
        assertEquals(com.snipsnap.audio.DrumClass.HAT_OPEN, Clutter.drumClassFor(ClutterVoice.BINLID))
    }

    @Test fun `the kitchen's response is built once per MIC setting`() {
        assertSame(Clutter.micIr(0.3), Clutter.micIr(0.3))
    }

    // ------------------------------------------------------------------ measures (prototype/clutter/measures.py)

    private val thirdOct = DoubleArray(22) { 80.0 * 2.0.pow(it / 3.0) }

    private fun spectroDb(x: FloatArray, frameMs: Int, totalS: Double): Array<DoubleArray> {
        val rate = Clutter.RATE
        val take = min(x.size, (totalS * rate).toInt())
        var rms = 0.0
        for (i in 0 until take) rms += x[i].toDouble() * x[i]
        rms = sqrt(rms / max(1, take)) + 1e-12
        val hop = frameMs * rate / 1000
        val win = 4 * hop
        var size = 1
        while (size < win) size = size shl 1
        val padded = DoubleArray(max(take, win + hop)) { if (it < take) x[it] / rms else 0.0 }
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val rows = ArrayList<DoubleArray>()
        var i = 0
        while (i < max(1, padded.size - win)) {
            val re = FloatArray(size); val im = FloatArray(size)
            for (k in 0 until win) re[k] = (padded[i + k] * hann[k]).toFloat()
            Fft.forward(re, im)
            rows.add(DoubleArray(thirdOct.size) { b ->
                val lo = thirdOct[b] / 2.0.pow(1.0 / 6); val hi = thirdOct[b] * 2.0.pow(1.0 / 6)
                var e = 0.0
                for (bin in 0..size / 2) { val f = bin.toDouble() * rate / size; if (f >= lo && f < hi) e += re[bin].toDouble().pow(2) + im[bin].toDouble().pow(2) }
                10 * log10(e + 1e-12)
            })
            i += hop
        }
        val top = rows.maxOf { it.max() }
        return Array(rows.size) { r -> DoubleArray(thirdOct.size) { max(rows[r][it], top - 80.0) } }
    }

    private fun rmsDiff(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = min(a.size, b.size)
        var s = 0.0; var c = 0
        for (r in 0 until n) for (k in a[r].indices) { s += (a[r][k] - b[r][k]).pow(2); c++ }
        return sqrt(s / c)
    }

    /** The take distance: the first 150 ms in 5 ms frames. */
    private fun distanceDb(a: FloatArray, b: FloatArray) = rmsDiff(spectroDb(a, 5, 0.15), spectroDb(b, 5, 0.15))

    /** The whole render in 10 ms frames: DAMP and MIC live in the tail. */
    private fun fullDistanceDb(a: FloatArray, b: FloatArray): Double {
        val n = max(a.size, b.size)
        val pa = a.copyOf(n); val pb = b.copyOf(n)
        return rmsDiff(spectroDb(pa, 10, n.toDouble() / Clutter.RATE), spectroDb(pb, 10, n.toDouble() / Clutter.RATE))
    }

    /** The strongest frequency between [lo] and [hi] inside a time window, by a fine DFT scan (1 Hz steps). */
    private fun dominantHz(x: FloatArray, t0: Double, t1: Double, lo: Double, hi: Double): Double {
        val a = (t0 * Clutter.RATE).toInt(); val b = min(x.size, (t1 * Clutter.RATE).toInt())
        val n = b - a
        var best = lo; var bestE = -1.0
        var f = lo
        while (f <= hi) {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                val ph = 2 * PI * f * i / Clutter.RATE
                re += x[a + i] * w * cos(ph); im -= x[a + i] * w * sin(ph)
            }
            val e = re * re + im * im
            if (e > bestE) { bestE = e; best = f }
            f += 1.0
        }
        return best
    }
}
