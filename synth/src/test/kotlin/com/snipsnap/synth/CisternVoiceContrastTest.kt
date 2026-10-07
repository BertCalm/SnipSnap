package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screens the almost-pure-root failure heard in the first audition. Comparing
 * waveforms or their envelopes alone missed it: changed landing phases made
 * large sample differences while all six voices kept almost the same timbre.
 * These checks compare normalized spectral energy at a shared note and level.
 * CisternTest separately checks tuning, causality, conservation and HOLD.
 */
class CisternVoiceContrastTest {

    @Test
    fun `default voices leave audible energy for their surface body`() {
        for (voice in CisternVoice.entries) {
            val spectrum = spectrum(voice)
            val bodyShare = 1.0 - spectrum[ROOT_BAND]
            println("CISTERN contrast $voice body energy share $bodyShare")
            // Five percent is a modest floor: the remaining body is roughly
            // 13 dB below the fundamental band, rather than effectively mute.
            assertTrue(bodyShare >= MIN_CONTRAST,
                "$voice leaves only ${bodyShare * 100}% of spectral energy outside the fundamental band")
        }
    }

    @Test
    fun `all six defaults have distinct timbres at the same note and velocity`() {
        val spectra = CisternVoice.entries.associateWith { spectrum(it) }
        for ((index, first) in CisternVoice.entries.withIndex()) {
            for (second in CisternVoice.entries.drop(index + 1)) {
                val contrast = distance(spectra.getValue(first), spectra.getValue(second))
                println("CISTERN contrast $first / $second spectral distance $contrast")
                // A five-percent redistribution of total energy is required;
                // gain, pitch choices in a kit, or sample phase cannot supply it.
                assertTrue(contrast >= MIN_CONTRAST,
                    "$first and $second redistribute only ${contrast * 100}% of spectral energy")
            }
        }
        // The two voices named in the feedback must retain their distinct
        // surfaces when every control is identical, as well as at defaults.
        val common = mapOf("STRIKE" to .5f, "SUSPENSION" to .5f, "DROP" to .5f,
            "SKIN" to .5f, "DRAIN" to .5f, "HOLD" to 0f)
        val commonContrast = distance(spectrum(CisternVoice.FIRST, common), spectrum(CisternVoice.DRIP, common))
        println("CISTERN contrast FIRST / DRIP identical controls spectral distance $commonContrast")
        assertTrue(commonContrast >= MIN_CONTRAST,
            "FIRST and DRIP with identical controls redistribute only ${commonContrast * 100}% of spectral energy")
    }

    @Test
    fun `strike contact and skin change timbre in FIRST and DRIP`() {
        for (voice in listOf(CisternVoice.FIRST, CisternVoice.DRIP)) {
            for (macro in listOf("STRIKE", "SKIN")) {
                val low = spectrum(voice, mapOf(macro to 0f))
                val high = spectrum(voice, mapOf(macro to 1f))
                val contrast = distance(low, high)
                println("CISTERN contrast $voice $macro endpoints spectral distance $contrast")
                assertTrue(contrast >= MIN_CONTRAST,
                    "$voice $macro endpoints redistribute only ${contrast * 100}% of spectral energy")
            }
        }
    }

    private fun spectrum(voice: CisternVoice, macros: Map<String, Float> = emptyMap()): DoubleArray {
        // Leave the ending fade outside the analysis window. Every comparison
        // uses C4 and full velocity, matching the neutral audition gestures.
        val samples = Cistern.renderInternal(voice, macros, midi = 60, velocity = 1f, seconds = 1.8f).raw
        val result = DoubleArray(BAND_EDGES.size - 1)
        val root = Keys.midiHz(60).toDouble()
        val end = minOf(samples.size, (1.6 * SAMPLE_RATE).toInt())
        val window = FloatArray(FFT_SIZE) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat() }
        var from = 0
        while (from + FFT_SIZE <= end) {
            val re = FloatArray(FFT_SIZE) { samples[from + it] * window[it] }
            val im = FloatArray(FFT_SIZE)
            Fft.forward(re, im)
            for (bin in 1 until FFT_SIZE / 2) {
                val ratio = bin.toDouble() * SAMPLE_RATE / FFT_SIZE / root
                val band = BAND_EDGES.indexOfLast { ratio >= it }
                if (band in result.indices) result[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            from += FFT_SIZE / 2
        }
        val total = result.sum()
        assertTrue(total > 1e-20 && total.isFinite(), "$voice has no finite spectral energy")
        return DoubleArray(result.size) { result[it] / total }
    }

    /** Total variation: the fraction of normalized energy moved between bands. */
    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]) } * 0.5

    private companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 8192
        const val ROOT_BAND = 1
        const val MIN_CONTRAST = 0.05
        // The generous fundamental band includes loading sag and spectral
        // leakage. Material/body differences must reach beyond that band.
        val BAND_EDGES = doubleArrayOf(0.0, .85, 1.15, 1.45, 1.9, 2.6, 3.6, 5.0, 7.0, 10.0, Double.POSITIVE_INFINITY)
    }
}
