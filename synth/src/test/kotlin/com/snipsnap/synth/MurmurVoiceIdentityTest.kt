package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differences between roles at the same root and velocity, after shared leveling.
 * These are mechanical contrast guards, not a claim that a numerical distance approves a sound.
 * The model-1 audition's post-onset KEY/LEAD distance is .036 and KEY/METAL is .219;
 * its held KEY/LEAD distance is .062. Different attacks or gains must not hide those similarities.
 */
class MurmurVoiceIdentityTest {
    private val focusedPairs = listOf(
        MurmurVoice.KEY to MurmurVoice.LEAD,
        MurmurVoice.KEY to MurmurVoice.METAL,
        MurmurVoice.LEAD to MurmurVoice.CHOIR,
        MurmurVoice.BASS to MurmurVoice.PLUCK,
    )

    /** Average actual spectral energy in several body frames; the first 250 ms are excluded. */
    private fun profile(samples: FloatArray, held: Boolean = false): DoubleArray {
        val starts = if (held) (0..(samples.size - FFT_SIZE) step FFT_SIZE / 2).toList()
            else listOf(.25, .40, .55, .70, .85, 1.0).map { (it * Dsp.RATE).roundToInt() }
        val powers = DoubleArray(FFT_SIZE / 2)
        for (start in starts) {
            val real = FloatArray(FFT_SIZE) { i ->
                if (start + i < samples.size) (samples[start + i] * (.5 - .5 * cos(2 * PI * i / FFT_SIZE))).toFloat()
                else 0f
            }
            val imaginary = FloatArray(FFT_SIZE)
            Fft.forward(real, imaginary)
            for (bin in 1 until powers.size) {
                powers[bin] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            }
        }
        val bands = DoubleArray(BAND_EDGES.size - 1)
        for (bin in 1 until powers.size) {
            val hz = bin.toDouble() * Dsp.RATE / FFT_SIZE
            val band = BAND_EDGES.indexOfFirst { it > hz } - 1
            if (band >= 0) bands[band] += powers[bin]
        }
        val sum = bands.sum()
        assertTrue(sum > 1e-12, "a quiet/empty analysis cannot establish a voice's spectrum")
        return DoubleArray(bands.size) { bands[it] / sum }
    }

    /** Energy allocation after the attack; gain cancels and duration alone cannot alter pitch. */
    private fun articulation(samples: FloatArray): DoubleArray {
        val boundaries = listOf(.25, .60, 1.20, 2.50).map {
            (it * Dsp.RATE).roundToInt().coerceAtMost(samples.size)
        } + samples.size
        val energy = DoubleArray(boundaries.size - 1) { band ->
            (boundaries[band] until boundaries[band + 1]).sumOf { samples[it].toDouble() * samples[it] }
        }
        val total = energy.sum()
        assertTrue(total > 1e-12, "an attack-only sound has no useful body articulation to compare")
        return DoubleArray(energy.size) { energy[it] / total }
    }

    private fun distance(first: DoubleArray, second: DoubleArray): Double =
        first.indices.sumOf { abs(first[it] - second[it]) }

    private fun pairs(): List<Pair<MurmurVoice, MurmurVoice>> = MurmurVoice.entries.flatMapIndexed { i, first ->
        MurmurVoice.entries.drop(i + 1).map { second -> first to second }
    }

    private fun checkSeparation(
        profiles: Map<MurmurVoice, DoubleArray>, label: String, minimum: Double, focusedMinimum: Double,
    ) {
        val failures = pairs().mapNotNull { pair ->
            val (first, second) = pair
            val change = distance(profiles.getValue(first), profiles.getValue(second))
            val floor = if (pair in focusedPairs) focusedMinimum else minimum
            println("MURMUR IDENTITY $label $first/$second: normalized spectral distance $change, minimum $floor")
            if (change <= floor) "$first/$second $change <= $floor" else null
        }
        assertTrue(failures.isEmpty(), "$label role spectra converge: ${failures.joinToString()}")
    }

    @Test
    fun `all six roles have distinct body spectra at the same root and matched velocity`() {
        val profiles = defaultFinite.mapValues { (_, samples) -> profile(samples) }
        checkSeparation(profiles, "BODY", minimum = .15, focusedMinimum = .30)
    }

    @Test
    fun `struck and sustained roles retain different body and tail articulation after matching gain`() {
        val profiles = defaultFinite.mapValues { (_, samples) -> articulation(samples) }
        val failures = focusedPairs.mapNotNull { (first, second) ->
            val change = distance(profiles.getValue(first), profiles.getValue(second))
            val minimum = if (first == MurmurVoice.BASS || first == MurmurVoice.KEY && second == MurmurVoice.LEAD) .20 else .10
            println("MURMUR IDENTITY ARTICULATION $first/$second: post-onset energy distance $change")
            if (change <= minimum) "$first/$second $change <= $minimum" else null
        }
        assertTrue(failures.isEmpty(), "body/tail articulation converges: ${failures.joinToString()}")
    }

    @Test
    fun `role spectra differ with identical macros and a stationary mouth`() {
        val profiles = commonFinite.mapValues { (_, samples) -> profile(samples) }
        checkSeparation(profiles, "COMMON", minimum = .12, focusedMinimum = .25)
    }

    @Test
    fun `held role spectra stay distinct when original attacks and envelopes are absent`() {
        val profiles = commonHeld.mapValues { (_, samples) -> profile(samples, held = true) }
        checkSeparation(profiles, "HELD", minimum = .12, focusedMinimum = .25)
    }

    @Test
    fun `a scalar volume change cannot satisfy spectral or articulation contrast`() {
        val original = defaultFinite.getValue(MurmurVoice.KEY)
        val quieter = FloatArray(original.size) { original[it] * .17f }
        assertTrue(distance(profile(original), profile(quieter)) < 1e-5)
        assertTrue(distance(articulation(original), articulation(quieter)) < 1e-5)
    }

    companion object {
        private const val MIDI = 60
        private const val FFT_SIZE = 8192
        private val BAND_EDGES = doubleArrayOf(0.0, 150.0, 250.0, 400.0, 650.0, 1000.0, 1500.0,
            2200.0, 3500.0, 6000.0, 10000.0, 16000.0, 22051.0)
        private val COMMON = mapOf("MOUTH" to .40f, "EDGE" to .40f, "BREATH" to .12f,
            "MOTION" to 0f, "BODY" to .55f, "HOLD" to 0f)

        // Shared immutable audio avoids repeating renders when JUnit creates a fresh test instance.
        private val defaultFinite by lazy { renderRoles(null) }
        private val commonFinite by lazy { renderRoles(COMMON) }
        private val commonHeld by lazy { renderRoles(COMMON + ("HOLD" to 1f)) }

        private fun renderRoles(macros: Map<String, Float>?): Map<MurmurVoice, FloatArray> =
            MurmurVoice.entries.associateWith { voice ->
                Murmur.render(voice, macros ?: Murmur.defaults(voice), midi = MIDI, velocity = 1f,
                    config = Murmur.configurationFor(voice, model = 2)).samples
            }
    }
}
