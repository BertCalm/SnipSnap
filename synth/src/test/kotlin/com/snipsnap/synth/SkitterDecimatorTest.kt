package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [Skitter.decimate]'s KDoc claims more than 70 dB of far-stopband rejection.
 * Nothing measured it: docs/SKITTER.md's 4x/8x comparison is a waveform
 * distance that, as it says, does not isolate aliasing. This feeds pure tones
 * through the decimator at every rate [Skitter.renderProbe] accepts and reads
 * the level that comes out.
 *
 * Every tone is a whole number of cycles in a [FRAMES]-frame periodic buffer,
 * so the periodic decimator returns one steady sinusoid whose level is the
 * kernel's response at that frequency, with no edge to leak. The far stopband
 * here is everything a decimated tone folds back from: the output Nyquist up to
 * the internal one.
 */
class SkitterDecimatorTest {
    private val frames = FRAMES

    /** Peak level in dB of a full-scale tone at [hz] after decimating from [oversample]x. */
    private fun levelDb(hz: Int, oversample: Int): Double {
        require(hz.toLong() * frames % Dsp.RATE == 0L) { "$hz Hz is not a whole number of cycles in $frames frames" }
        // A tone on a multiple of the output rate folds to DC, where RMS stops reading peak level.
        require(hz % Dsp.RATE != 0) { "$hz Hz folds to DC at the output rate" }
        val rate = Dsp.RATE.toDouble() * oversample
        val raw = FloatArray(frames * oversample) { sin(2.0 * PI * hz * it / rate).toFloat() }
        val out = Skitter.decimate(raw, oversample, periodic = true)
        val rms = sqrt(out.sumOf { it.toDouble() * it } / out.size)
        return 20.0 * log10(rms * sqrt(2.0))
    }

    /** The output Nyquist to the internal Nyquist: fine steps across the first sidelobes, coarse beyond. */
    private fun farStopband(oversample: Int): List<Int> {
        val nyquist = Dsp.RATE / 2
        val internalNyquist = Dsp.RATE * oversample / 2
        val fine = ((nyquist / STEP_HZ + 1) * STEP_HZ..26_000 step STEP_HZ).toList()
        val coarse = (27_000 until internalNyquist step 1_000).toList()
        return fine + coarse
    }

    @Test
    fun `the decimator keeps a passband tone at its level and folds nothing back within 74 dB of it`() {
        for (oversample in listOf(4, 8, 16)) {
            val passband = levelDb(PASSBAND_HZ, oversample)
            assertTrue(abs(passband) < PASSBAND_TOLERANCE_DB,
                "${oversample}x: a $PASSBAND_HZ Hz tone left the decimator at $passband dB, not unity")
            val (worstHz, worstDb) = farStopband(oversample).map { it to levelDb(it, oversample) }.maxBy { it.second }
            val rejection = passband - worstDb
            // Measured 75.3 dB at every rate, worst at 22.2 kHz just above the output Nyquist,
            // falling past 100 dB at the internal Nyquist. 74 sits under that and above the KDoc's 70.
            assertTrue(rejection >= REJECTION_DB,
                "${oversample}x: Skitter.decimate's kernel let a $worstHz Hz tone through only $rejection dB below " +
                    "the $PASSBAND_HZ Hz passband tone; its KDoc claims more than 70 dB of far-stopband rejection")
        }
    }

    private companion object {
        /** Fifty milliseconds at the output rate; tones on a 20 Hz grid fill it with whole cycles. */
        const val FRAMES = 2_205
        const val STEP_HZ = 20
        const val PASSBAND_HZ = 1_000
        const val PASSBAND_TOLERANCE_DB = .01
        const val REJECTION_DB = 74.0
    }
}
