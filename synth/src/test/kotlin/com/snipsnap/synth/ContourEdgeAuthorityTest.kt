package com.snipsnap.synth

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Complete, conditioned factory tones must change in spectrum, including the quiet held layers. */
class ContourEdgeAuthorityTest {
    @Test
    fun `foreground EDGE reveals the weak held layers through its middle and full range`() {
        for (name in listOf("Bright Margin", "Soft Digital")) {
            val patch = ContourPresets.all().single { it.name == name }
            fun tone(response: ContourEdgeResponse, edge: Float) = ContourEngine.renderLoop(
                patch.voice, patch.macros + ("EDGE" to edge), patch.velocity,
                patch.config.copy(edgeResponse = response))
            val old = listOf(0f, .25f, .5f, 1f).associateWith { bands(tone(ContourEdgeResponse.LEGACY, it)) }
            val revised = listOf(0f, .25f, .5f, 1f).associateWith { bands(tone(ContourEdgeResponse.FOREGROUND, it)) }
            val oldFull = distance(old.getValue(0f), old.getValue(1f))
            val newFull = distance(revised.getValue(0f), revised.getValue(1f))
            val oldMiddle = distance(old.getValue(.25f), old.getValue(.5f))
            val newMiddle = distance(revised.getValue(.25f), revised.getValue(.5f))
            println("EDGE $name: full $oldFull -> $newFull dB; middle $oldMiddle -> $newMiddle dB")
            // These are regression margins against the measured failure, not hearing thresholds.
            assertTrue(newFull > 1 && newFull > oldFull * 3, "$name: EDGE still hides the settled layer")
            assertTrue(newMiddle > .15 && newMiddle > oldMiddle * 2.5, "$name: middle EDGE steps remain ineffective")
        }
    }

    @Test
    fun `zero EDGE remains body only for both responses in finite and held factory recipes`() {
        for (patch in ContourPresets.all()) for (hold in listOf(0f, 1f)) {
            val macros = patch.macros + mapOf("EDGE" to 0f, "HOLD" to hold)
            val legacy = ContourEngine.render(patch.voice, macros, patch.velocity,
                patch.config.copy(edgeResponse = ContourEdgeResponse.LEGACY))
            val revised = ContourEngine.render(patch.voice, macros, patch.velocity,
                patch.config.copy(edgeResponse = ContourEdgeResponse.FOREGROUND))
            assertContentEquals(legacy.samples, revised.samples, "${patch.name} HOLD $hold")
        }
    }

    @Test
    fun `a scalar gain change cannot pass the EDGE spectral measurement`() {
        val patch = ContourPresets.all().single { it.name == "Soft Digital" }
        val source = ContourEngine.renderLoop(patch.voice, patch.macros, patch.velocity, patch.config)
        assertTrue(distance(bands(source), bands(FloatArray(source.size) { source[it] * .5f })) < 1e-5)
    }

    private fun bands(samples: FloatArray): DoubleArray {
        val count = 40
        val size = 8192
        val energies = DoubleArray(count)
        val scale = count / ln(16_000.0 / 25.0)
        for (from in 0..samples.size - size step 2048) {
            val power = ContourTestAudio.power(samples, from, size)
            for (bin in 1 until power.size) {
                val hz = bin.toDouble() * Dsp.RATE / size
                if (hz >= 25 && hz < 16_000) {
                    val band = (ln(hz / 25) * scale).toInt().coerceIn(0, count - 1)
                    energies[band] += power[bin]
                }
            }
        }
        val total = energies.sum()
        require(total > 1e-12)
        return DoubleArray(count) { energies[it] / total }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = sqrt(a.indices.sumOf {
        val difference = 10 * log10(b[it].coerceAtLeast(1e-10) / a[it].coerceAtLeast(1e-10))
        (a[it] + b[it]) * .5 * difference * difference
    })
}
