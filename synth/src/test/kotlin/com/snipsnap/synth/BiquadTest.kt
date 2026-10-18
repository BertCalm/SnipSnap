package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The DF-II-transposed contract: at low centre frequencies on oversampled
 * rates, a Float biquad must stay near the gain its coefficients promise.
 * The double-precision reference below is the same RBJ math with none of
 * the Float rounding, so the gap measured is purely the structure's.
 */
class BiquadTest {

    private fun lowShelfDouble(f0: Double, gainDb: Double, rate: Double): DoubleArray {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / rate
        val cw = kotlin.math.cos(w0)
        val sw = kotlin.math.sin(w0)
        val alpha = sw / 2.0 * sqrt(2.0)
        val sqA = sqrt(a)
        val b0 = a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cw)
        val b2 = a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha)
        val a0 = (a + 1) + (a - 1) * cw + 2 * sqA * alpha
        val a1 = -2.0 * ((a - 1) + (a + 1) * cw)
        val a2 = (a + 1) + (a - 1) * cw - 2 * sqA * alpha
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    @Test
    fun `a low shelf at an oversampled rate holds its promised gain`() {
        // The worst probe from the DF-I measurement: 60 Hz, +9 dB, at the
        // oversampled render rate, probed deep in the shelf's flat region.
        // DF-I measured -0.25 dB off the double reference here.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val c = lowShelfDouble(60.0, 9.0, rate.toDouble())
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        // Reference and DUT must see the same samples, so run them in one pass.
        val shelf = Dsp.Biquad().apply { lowShelf(60f, 9f, rate) }
        val n = rate * 2
        var dutSum = 0.0; var refSum = 0.0; var cnt = 0
        for (i in 0 until n) {
            val x = sin(2 * PI * 20.0 * i / rate)
            val dut = shelf.process(x.toFloat()).toDouble()
            val ref = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1; x1 = x; y2 = y1; y1 = ref
            if (i > n / 2) { dutSum += dut * dut; refSum += ref * ref; cnt++ }
        }
        val errDb = 20 * log10(sqrt(dutSum / cnt) / sqrt(refSum / cnt))
        assertTrue(kotlin.math.abs(errDb) < 0.05, "shelf gain error ${errDb} dB (Float DF-I measured ~0.25 dB here)")
    }

    @Test
    fun `a flat biquad is a wire`() {
        val b = Dsp.Biquad().apply { lowShelf(100f, 0f, 44_100) }
        var worst = 0f
        for (i in 0 until 1000) {
            val x = sin(2 * PI * 440.0 * i / 44_100).toFloat()
            worst = maxOf(worst, kotlin.math.abs(b.process(x) - x))
        }
        assertTrue(worst < 0.01f, "0 dB shelf should pass audio nearly untouched, worst error $worst")
    }
}
