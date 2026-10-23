package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedOnePoleTest {
    @Test
    fun `fixed cutoff retains every Float bit of the shared filter`() {
        for (rate in listOf(44_100, 176_400)) {
            for (cutoff in listOf(0f, 3f, 110f, 261.62555f, 4_000f, 10_000f, rate.toFloat())) {
                val legacy = Dsp.OnePole(rate)
                val fixed = Dsp.FixedOnePole(cutoff, rate)
                val noise = Dsp.Noise(731)
                for (i in 0 until 8_192) {
                    val input = when {
                        i == 0 -> 1f
                        i < 2_048 -> .17f + noise.next() * .3f
                        i < 4_096 -> sin(2 * PI * i * 523.25 / rate).toFloat()
                        else -> 0f
                    }
                    assertEquals(legacy.lp(input, cutoff).toRawBits(), fixed.lp(input).toRawBits(),
                        "$rate Hz / cutoff $cutoff / sample $i")
                }
            }
        }
    }

    @Test
    fun `opt in string filter preserves feedback nonlinearities and retuning bits`() {
        for (rate in listOf(44_100, 176_400)) {
            for (variant in 0..3) {
                val hz = 220f
                val loopHz = if (variant == 0) 900f else 6_000f
                val stiffness = if (variant >= 1) -.2f else 0f
                val jawari = if (variant >= 2) .3f else 0f
                val dispersion = if (variant == 3) Strings.Dispersion(3, -.15f) else null
                val dcBlock = variant >= 2
                val tuning = Strings.tune(hz, loopHz, rate, stiffness, jawari, dispersion, dcBlock)
                fun loop(cached: Boolean) = Strings.Loop(
                    tuning.n, tuning.a, .996f, loopHz, rate, stiffness, jawari, .8f,
                    dispersion, dcBlock, cacheLoopFilter = cached,
                )
                val legacy = loop(false)
                val fixed = loop(true)
                val noise = Dsp.Noise(731)
                for (i in 0 until 12_000) {
                    if (i == 3_000) {
                        legacy.retune(hz * 1.03f)
                        fixed.retune(hz * 1.03f)
                    }
                    if (i == 6_000) {
                        legacy.gain(.97f)
                        fixed.gain(.97f)
                    }
                    val input = when {
                        i == 0 -> -0.0f
                        i < 500 -> noise.next() * .3f
                        i in 4_000..5_000 -> .03f
                        else -> 0f
                    }
                    assertEquals(legacy.reflected().toRawBits(), fixed.reflected().toRawBits(),
                        "$rate Hz / variant $variant / reflected sample $i")
                    assertEquals(legacy.next(input).toRawBits(), fixed.next(input).toRawBits(),
                        "$rate Hz / variant $variant / output sample $i")
                }
            }
        }
    }
}
