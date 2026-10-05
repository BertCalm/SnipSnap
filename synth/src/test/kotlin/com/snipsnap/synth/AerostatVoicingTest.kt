package com.snipsnap.synth

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

class AerostatVoicingTest {
    private val voice = AerostatVoice.FLOAT
    private val base = Aerostat.defaults(voice) + mapOf("TUNE" to 0f, "LIFT" to 0f)

    private fun rms(samples: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt()
        val end = minOf(samples.size, (until * Dsp.RATE).toInt())
        return kotlin.math.sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))
    }

    @Test
    fun `the full mix leaves room for airflow as its valve opens`() {
        for (tune in listOf(0f, 0.5f, 1f)) {
            val macros = base + ("TUNE" to tune)
            val tube = Aerostat.render(voice, macros, tap = AerostatTap.TUBE, normalize = false).samples
            val flow = Aerostat.render(voice, macros, tap = AerostatTap.FLOW, normalize = false).samples
            assertTrue(rms(flow, 0.04, 0.15) > rms(tube, 0.04, 0.15) * 0.75,
                "airflow buried during onset at TUNE $tune")
            val full = Aerostat.render(voice, macros).samples
            assertTrue(rms(full, 0.15, 0.35) > rms(full, 0.0, 0.04) * 0.3,
                "levelling buried the body at TUNE $tune")
        }
    }

    private fun upperFraction(macros: Map<String, Float>): Double {
            val samples = Aerostat.render(voice, macros).samples
            val n = 8192
            val start = (0.15 * Dsp.RATE).toInt()
            val re = FloatArray(n) { i ->
                (samples[start + i] * (0.5 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * i / (n - 1)))).toFloat()
            }
            val im = FloatArray(n)
            com.snipsnap.audio.Fft.forward(re, im)
            fun energy(range: IntRange) = range.sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
            val split = (Aerostat.frequencyFor(0f) * 4 / Dsp.RATE * n).toInt()
            return energy(split..n / 2) / energy(1..n / 2)
    }

    @Test
    fun `pressure changes the full mix air spectrum after levelling`() {
        val low = upperFraction(base + ("PRESSURE" to 0f))
        val high = upperFraction(base + ("PRESSURE" to 1f))
        assertTrue(high > low * 1.5, "pressure spectrum too similar: $low, $high")
    }

    @Test
    fun `lift changes the full mix air spectrum with the same strike`() {
        val low = upperFraction(base + ("LIFT" to 0f))
        val high = upperFraction(base + ("LIFT" to 1f))
        assertTrue(high > low * 1.5, "lift spectrum too similar: $low, $high")
    }

    @Test
    fun `the held voice contains air rather than only a repeated tone period`() {
        val samples = Aerostat.render(voice, base + ("HOLD" to 1f), normalize = false).samples
        val period = (Dsp.RATE / Aerostat.frequencyFor(0f)).roundToInt()
        var residual = 0.0
        var energy = 0.0
        for (i in period until samples.size) {
            val d = (samples[i] - samples[i - period]).toDouble()
            residual += d * d
            energy += samples[i].toDouble() * samples[i]
        }
        assertTrue(residual > energy * 0.002, "held air disappeared: ${residual / energy}")
        assertTrue(Aerostat.renderLoopMeasured(base + ("HOLD" to 1f), 1f).seam < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `the tube attack remains audible beyond the contact pulse`() {
        val samples = Aerostat.render(voice, base, tap = AerostatTap.TUBE, normalize = false).samples
        fun energy(from: Double, until: Double) =
            ((from * Dsp.RATE).toInt() until (until * Dsp.RATE).toInt()).sumOf {
                samples[it].toDouble() * samples[it]
            }
        assertTrue(energy(0.04, 0.10) > energy(0.0, 0.02) * 0.05, "tube collapsed to a click")
    }

    @Test
    fun `silent event and boundary renders stay finite and bounded`() {
        assertTrue(Aerostat.render(voice, base, velocity = 0f).samples.all { it == 0f })
        for (knob in listOf("STRIKE", "PRESSURE", "INERTIA", "RELEASE", "LIFT", "HOLD")) {
            for (value in listOf(0f, 1f)) {
                val samples = Aerostat.render(voice, base + (knob to value)).samples
                assertTrue(samples.all { it.isFinite() && it in -1f..1f }, "$knob $value")
            }
        }
    }
}
