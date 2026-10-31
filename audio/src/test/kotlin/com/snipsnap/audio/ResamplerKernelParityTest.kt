package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The general sinc loop is an independent oracle for the optimized interior and clipped edges. */
class ResamplerKernelParityTest {
    private val rates = listOf(176_400 to 88_200, 176_400 to 44_100, 176_400 to 22_050,
        176_400 to 11_025, 96_000 to 3_000)
    private val lengths = listOf(0, 1, 2, 3, 15, 16, 17, 30, 31, 32, 33, 34,
        63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513, 1023, 1024, 1025)

    private fun check(nonFinite: Boolean) {
        var state = 81237
        for ((rate, target) in rates) for (channels in 1..2) for (frames in lengths) {
            val samples = FloatArray(frames * channels) { index ->
                state = state * 1103515245 + 12345
                when {
                    nonFinite && index % 53 == 0 -> Float.fromBits(0x7fc01234)
                    nonFinite && index % 59 == 0 -> Float.POSITIVE_INFINITY
                    nonFinite && index % 61 == 0 -> Float.NEGATIVE_INFINITY
                    index % 47 == 0 -> -0.0f
                    else -> ((state ushr 8) and 0xffff) / 32768f - 1f
                }
            }
            val input = Snip(samples, channels, rate)
            val expected = Resampler.resample(input, target, powerOfTwoFastPath = false)
            val actual = Resampler.resample(input, target)
            val label = "$rate->$target, $channels ch, $frames frames, nonfinite=$nonFinite"
            assertEquals(expected.samples.size, actual.samples.size, label)
            for (i in expected.samples.indices) {
                val reference = expected.samples[i]
                if (reference.isNaN()) assertTrue(actual.samples[i].isNaN(), "$label sample $i lost NaN propagation")
                else assertEquals(reference.toRawBits(), actual.samples[i].toRawBits(), "$label sample $i")
            }
        }
    }

    @Test
    fun `power of two interiors and clipped kernels preserve all finite sample bits`() = check(nonFinite = false)

    @Test
    fun `power of two kernels preserve signed zero and nonfinite propagation`() = check(nonFinite = true)
}
