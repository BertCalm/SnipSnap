package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Foldback evidence, separate from the existing near-Nyquist brightness guard.
 *
 * A stationary, breathless HOLD with integer operator ratios contains only root harmonics
 * before sampling. Its complete coherent period puts those harmonics on exact DFT bins.
 * Energy outside those bins measures distinguishable foldback plus numerical leakage.
 * Aliases coincident with genuine harmonics are excluded, so this is not a total alias bound.
 */
class MurmurAliasingTest {
    private data class Case(val voice: MurmurVoice, val midi: Int, val ceiling: Double)

    @Test
    fun `bright stationary stacks retain their measured foldback limits across registers`() {
        // No-regression ceilings, roughly 2-3 times the largest measured power share at each
        // register in the 4x/8x/16x reference review of 84471534a. These certify neither hearing
        // nor alias freedom. LEAD C5 and METAL C4 expose the final decimator's transition band;
        // their strongest folded line is 21.6 kHz. The approved production audio stays intact.
        val cases = listOf(
            Case(MurmurVoice.LEAD, 48, 1e-8), Case(MurmurVoice.LEAD, 60, 1e-5),
            Case(MurmurVoice.LEAD, 72, .0012), Case(MurmurVoice.LEAD, 84, 5e-6),
            Case(MurmurVoice.LEAD, 96, 7e-7), Case(MurmurVoice.METAL, 48, 7e-6),
            Case(MurmurVoice.METAL, 60, .0004), Case(MurmurVoice.METAL, 72, 1e-6),
            Case(MurmurVoice.METAL, 84, 4e-7), Case(MurmurVoice.METAL, 96, 3e-7),
        )
        for (case in cases) for (mouth in listOf(.25f, .75f)) {
            val probe = Murmur.probe(case.voice, mapOf(
                "MOUTH" to mouth, "EDGE" to 1f, "BODY" to 0f,
                "BREATH" to 0f, "MOTION" to 0f, "HOLD" to 1f,
            ), midi = case.midi)
            val exactCycles = probe.rootHz * probe.samples.size / Dsp.RATE
            val cycles = exactCycles.roundToInt()
            assertTrue(abs(exactCycles - cycles) < 1e-8, "the measurement needs a coherent period")
            val share = offHarmonicPower(probe.samples, cycles)
            println("MURMUR FOLDBACK ${case.voice} MIDI ${case.midi} MOUTH $mouth: $share")
            assertTrue(share < case.ceiling,
                "${case.voice} MIDI ${case.midi} MOUTH $mouth increased distinguishable foldback: $share >= ${case.ceiling}")
        }
    }

    /** Goertzel projections avoid padding or windowing a non-power-of-two coherent period. */
    private fun offHarmonicPower(samples: FloatArray, cycles: Int): Double {
        var total = 0.0
        for (sample in samples) total += sample.toDouble() * sample
        assertTrue(total > 0.0, "a silent render cannot validate alias rejection")
        var harmonic = 0.0
        for (bin in cycles..samples.size / 2 step cycles) {
            val coefficient = 2.0 * cos(2.0 * PI * bin / samples.size)
            var previous = 0.0
            var beforePrevious = 0.0
            for (sample in samples) {
                val next = sample + coefficient * previous - beforePrevious
                beforePrevious = previous
                previous = next
            }
            val power = previous * previous + beforePrevious * beforePrevious -
                coefficient * previous * beforePrevious
            harmonic += power * (if (bin * 2 == samples.size) 1.0 else 2.0) / samples.size
        }
        return ((total - harmonic) / total).coerceAtLeast(0.0)
    }
}
