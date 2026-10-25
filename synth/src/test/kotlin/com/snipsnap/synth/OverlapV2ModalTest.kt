package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protect the accepted model-two modal identities using its actual mono output.
 * A reference root must be present, but need not be the strongest line or the
 * perceived pitch of a multi-family sound. Expected ratios describe the musical
 * contract; they are not calculated from the renderer's matrix or delay diagnostics.
 *
 * Mutation example: giving every topology the SELF matrix and geometry would
 * fail TREFOIL's missing-family checks and MIRROR/TORUS's even-harmonic rejection.
 * Checking only the reference root or matrix orthogonality would miss that change.
 */
class OverlapV2ModalTest {
    @Test
    fun `full depth keeps the four defining modal families across the supported register`() {
        for (midi in registers) for ((topology, contract) in contracts) {
            assertContract(spectrum(topology, midi, 1f), contract, "$topology MIDI $midi KNOT 1")
        }
    }

    @Test
    fun `zero depth restores the self modal families for every topology and register`() {
        val self = contracts.getValue(OverlapTopology.SELF)
        for (midi in registers) for (topology in OverlapTopology.entries) {
            assertContract(spectrum(topology, midi, 0f), self, "$topology MIDI $midi KNOT 0")
        }
    }

    private fun spectrum(topology: OverlapTopology, midi: Int, knot: Float): Spectrum {
        val probe = Overlap.probe(
            topology, mapOf("KNOT" to knot, "PULSE" to 0f, "LOCK" to 0f,
                "GLOW" to 0f, "RING" to .8f, "HOLD" to 0f),
            midi = midi, velocity = 1f, seconds = 2.0, model = 2,
        )
        assertEquals(RATE, probe.sampleRate)
        // Exclude the 40 ms hammer and analyze the unfaded body. This 1.486 s
        // window resolves the lowest reference (C1) and its nearby SELF families;
        // short pitch-detector windows would merge these distinct resonances.
        val start = (RATE * .08).toInt()
        assertTrue(start + FFT_SIZE <= probe.raw.size)
        val re = FloatArray(FFT_SIZE) { probe.raw[start + it] * window[it] }
        val im = FloatArray(FFT_SIZE)
        Fft.forward(re, im)
        val power = DoubleArray(FFT_SIZE / 2 + 1) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        assertTrue(power.maxOrNull()!!.let { it.isFinite() && it > 1e-20 }, "$topology MIDI $midi has no finite body spectrum")
        return Spectrum(power, 440.0 * 2.0.pow((midi - 69) / 12.0))
    }

    private fun assertContract(spectrum: Spectrum, contract: ModalContract, label: String) {
        val largest = spectrum.power.maxOrNull()!!
        val required = contract.required.map { ratio ->
            val peak = spectrum.peak(ratio)
            val cents = 1200.0 * ln(peak.hz / (spectrum.root * ratio)) / ln(2.0)
            assertTrue(abs(cents) < 15.0,
                "$label: expected $ratio x reference, found ${peak.hz} Hz ($cents cents)")
            // Some upper odd partials at C6 are heavily damped. Their presence
            // matters, rather than an arbitrary requirement that they dominate.
            assertTrue(peak.power > largest * 1e-8,
                "$label: $ratio x reference is only ${db(peak.power / largest)} dB relative to the strongest line")
            val shoulder = maxOf(spectrum.peak(ratio - .08, .015).power,
                spectrum.peak(ratio + .08, .015).power)
            assertTrue(peak.power > shoulder * 16.0,
                "$label: $ratio x reference has only ${db(peak.power / shoulder)} dB prominence over neighboring off-mode bands")
            peak.power
        }
        val reference = required.maxOrNull()!!
        for (ratio in contract.forbidden) {
            val fraction = spectrum.peak(ratio).power / reference
            assertTrue(fraction < 10.0.pow(-35.0 / 10.0),
                "$label: unexpected family at $ratio x reference (${db(fraction)} dB relative to the strongest required line)")
        }
    }

    private data class ModalContract(val required: List<Double>, val forbidden: List<Double> = emptyList())
    private data class Peak(val power: Double, val hz: Double)

    private class Spectrum(val power: DoubleArray, val root: Double) {
        fun peak(ratio: Double, halfWidth: Double = .02): Peak {
            val lo = ceil((ratio - halfWidth) * root / BIN_HZ).toInt().coerceAtLeast(1)
            val hi = floor((ratio + halfWidth) * root / BIN_HZ).toInt().coerceAtMost(power.lastIndex - 1)
            require(lo <= hi) { "The FFT cannot resolve the requested modal search band" }
            val bin = (lo..hi).maxBy { power[it] }
            // Log-power interpolation removes bin-position bias without claiming
            // that zero padding or a longer FFT creates more observed audio.
            val left = ln(power[bin - 1].coerceAtLeast(1e-30))
            val center = ln(power[bin].coerceAtLeast(1e-30))
            val right = ln(power[bin + 1].coerceAtLeast(1e-30))
            val curvature = left - 2.0 * center + right
            val offset = if (abs(curvature) > 1e-12) (.5 * (left - right) / curvature).coerceIn(-.5, .5) else 0.0
            return Peak(power[bin], (bin + offset) * BIN_HZ)
        }
    }

    private fun db(powerRatio: Double): Double = 10.0 * log10(powerRatio.coerceAtLeast(1e-30))

    private companion object {
        const val RATE = 44_100
        const val FFT_SIZE = 65_536
        const val BIN_HZ = 1.0 * RATE / FFT_SIZE
        val registers = listOf(24, 60, 84)
        val window = FloatArray(FFT_SIZE) { (.5 - .5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val contracts = linkedMapOf(
            // Six independent positive combs: the root and five upper intervals,
            // plus the root's second harmonic to protect their positive return.
            OverlapTopology.SELF to ModalContract(listOf(1.0, 1.25, 1.5, 5.0 / 3.0, 1.875, 2.0, 2.5)),
            // Two positive combs at root/fifth; the other SELF families are absent.
            OverlapTopology.TREFOIL to ModalContract(listOf(1.0, 1.5, 2.0, 3.0), listOf(1.25, 5.0 / 3.0, 1.875)),
            // Three negative circuits: odd families at root, 5/4 and 5/3.
            // Reject the second harmonic of each family, not just root octave.
            OverlapTopology.MIRROR to ModalContract(listOf(1.0, 1.25, 5.0 / 3.0, 3.0), listOf(2.0, 2.5, 10.0 / 3.0)),
            // One negative circuit: root and odd harmonics, without extra families.
            OverlapTopology.TORUS to ModalContract(listOf(1.0, 3.0), listOf(1.25, 1.5, 5.0 / 3.0, 2.0, 4.0)),
        )
    }
}
