package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The FACSIMILE renderer: the port of `prototype/facsimile/synth.py`, checked against it and on its own terms. */
class FacsimileTest {
    private val drums = listOf("113-k0", "113-d2", "90-k0", "90-d0", "163-d1", "163-d8", "134-d3", "106-d1")

    @Test fun `every loop's models load`() {
        for (key in listOf("90", "102", "103", "106", "113", "127", "134", "154", "163")) {
            val loop = Facsimile.loop(key)
            assertTrue(loop.drums.isNotEmpty(), key)
            assertTrue(loop.seconds > 4.0, key)   // 102 is two bars, 4.7 s
            for (m in loop.drums.values) {
                assertEquals(26, m.noiseDb.size, m.id)
                assertTrue(m.events.isNotEmpty(), m.id)
            }
        }
    }

    @Test fun `the band-pass is the prototype's 3rd-order Butterworth`() {
        // scipy.signal.butter(3, [lo, hi], btype="bandpass", fs=176400, output="sos") for the 1 kHz band, its gain at
        // the centre and at the edges (-3 dB) measured by running a sine through the sections
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val lo = Facsimile.BAND_EDGES[13]; val hi = Facsimile.BAND_EDGES[14]
        val secs = Facsimile.bandpass(lo, hi, rate, Facsimile.ORDER)
        assertEquals(3, secs.size)
        fun gainAt(hz: Double): Double {
            val n = rate
            var x = DoubleArray(n) { kotlin.math.sin(2 * Math.PI * hz * it / rate) }
            for (c in secs) {
                val o = DoubleArray(n); var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (i in 0 until n) { val s = c[0] * x[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2; x2 = x1; x1 = x[i]; y2 = y1; y1 = s; o[i] = s }
                x = o
            }
            var e = 0.0; for (i in n / 2 until n) e += x[i] * x[i]
            return sqrt(e / (n / 2) * 2)
        }
        assertEquals(1.0, gainAt(sqrt(lo * hi)), 0.01)
        assertEquals(1 / sqrt(2.0), gainAt(lo), 0.01)
        assertEquals(1 / sqrt(2.0), gainAt(hi), 0.01)
    }

    @Test fun `a strike is deterministic, finite and bounded`() {
        for (id in drums) {
            val m = Facsimile.model(id)
            val a = Facsimile.render(m, 1f, 1)
            val b = Facsimile.render(m, 1f, 1)
            assertContentEquals(a, b, id)
            assertTrue(a.all { it.isFinite() }, id)
            assertTrue(a.maxOf { abs(it) } in 1e-4f..4f, "$id peak ${a.maxOf { abs(it) }}")
        }
    }

    @Test fun `takes are different strikes and a soft strike is not a quieter copy`() {
        for (id in drums) {
            val m = Facsimile.model(id)
            val t0 = Facsimile.render(m, 1f, 0); val t1 = Facsimile.render(m, 1f, 1)
            assertTrue(t0.indices.any { abs(t0[it] - t1[it]) > 1e-4f }, "$id: takes 0 and 1 identical")
            val hard = Facsimile.render(m, 1f, 0); val soft = Facsimile.render(m, .45f, 0)
            val k = (rms(hard) / rms(soft)).toFloat()
            val scaled = FloatArray(soft.size) { soft[it] * k }
            val n = minOf(hard.size, scaled.size)
            var diff = 0.0; var ref = 0.0
            for (i in 0 until n) { diff += (hard[i] - scaled[i]).toDouble().let { it * it }; ref += hard[i].toDouble().let { it * it } }
            assertTrue(10 * log10(diff / ref) > -30, "$id: the soft strike is the hard one turned down (${10 * log10(diff / ref)} dB)")
        }
    }

    /** Set FACSIMILE_DUMP to a folder to write strikes and grooves as WAVs for the Python harness's parity check. */
    @Test fun `dump renders for the parity check`() {
        val dir = System.getenv("FACSIMILE_DUMP") ?: return
        val out = File(dir).apply { mkdirs() }
        val began = System.nanoTime()
        for (id in drums) for (v in listOf(1f, .45f)) for (t in 0..1) {
            WavWriter.write(File(out, "$id-v${(v * 100).toInt()}-t$t.wav"), Snip(Facsimile.render(Facsimile.model(id), v, t), 1, Dsp.RATE))
        }
        println("strikes rendered in ${(System.nanoTime() - began) / 1_000_000} ms")
    }

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)
}
