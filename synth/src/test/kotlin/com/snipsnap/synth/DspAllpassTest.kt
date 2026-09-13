package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class DspAllpassTest {

    /** An allpass passes every magnitude and changes only phase. */
    @Test
    fun `an allpass keeps the level of every tone it is given`() {
        for (toneHz in listOf(100f, 440f, 2_000f, 8_000f)) {
            val n = 44_100
            val input = FloatArray(n) { sin(2.0 * Math.PI * toneHz * it / 44_100).toFloat() * 0.5f }
            val bq = Dsp.Biquad()
            bq.allpass(1_000f, 0.7f)
            val output = FloatArray(n) { bq.process(input[it]) }
            // Compare RMS over the second half, past the filter's settling.
            fun rms(a: FloatArray): Float {
                var s = 0.0
                for (i in a.size / 2 until a.size) s += (a[i] * a[i]).toDouble()
                return sqrt(s / (a.size / 2)).toFloat()
            }
            assertTrue(
                abs(rms(output) - rms(input)) < 0.01f,
                "allpass changed the level of ${toneHz}Hz: ${rms(input)} -> ${rms(output)}",
            )
        }
    }
}
