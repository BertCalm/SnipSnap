package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitInstrumentsTest {
    @Test
    fun `every player is finite deterministic bounded and has a finite gesture`() {
        for (rate in listOf(44_100, 176_400)) for (kind in CircuitInstruments.Kind.entries) {
            val first = CircuitInstruments.gesture(kind, 130.8128, rate, .9, 7123)
            val repeat = CircuitInstruments.gesture(kind, 130.8128, rate, .9, 7123)
            assertTrue(first.contentEquals(repeat), "$kind at $rate is not deterministic")
            assertTrue(first.all { it.isFinite() }, "$kind has nonfinite samples")
            assertTrue(first.size <= (rate * .65).toInt(), "$kind exceeds its finite event duration")
            val peak = first.maxOf { abs(it) }
            assertTrue(peak in .005f.. .440001f, "$kind peak $peak")
            assertTrue(first.first() == 0f, "$kind starts with an audible discontinuity")
            assertTrue(first.last() == 0f, "$kind ends with an audible discontinuity")
            assertTrue(abs(first.sumOf { it.toDouble() } / first.size) < 1e-7, "$kind retains DC")
            val otherSeed = CircuitInstruments.gesture(kind, 130.8128, rate, .9, 8181)
            assertTrue(!first.contentEquals(otherSeed), "$kind ignores its player seed")
        }
    }

    @Test
    fun `clapper has two substantial knocks separated by a quieter mounting tail`() {
        val rate = 44_100
        val samples = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAPPER, 130.8128, rate, 1.0, 33)
        val first = rms(samples, rate, .004, .026)
        val gap = rms(samples, rate, .033, .044)
        val second = rms(samples, rate, .047, .080)
        assertTrue(first > gap * 1.6, "first $first, gap $gap")
        assertTrue(second > gap * 1.6, "second $second, gap $gap")
    }

    @Test
    fun `uh huh retains two voiced syllables with a clear intervening gap`() {
        val rate = 44_100
        val samples = CircuitInstruments.gesture(CircuitInstruments.Kind.UH_HUH, 130.8128, rate, .9, 33)
        val first = rms(samples, rate, .030, .125)
        val gap = rms(samples, rate, .180, .225)
        val second = rms(samples, rate, .270, .415)
        assertTrue(first > gap * 5, "first $first, gap $gap")
        assertTrue(second > gap * 5, "second $second, gap $gap")
        assertTrue(first > .005 && second > .005, "both syllables must carry voiced energy")
    }

    @Test
    fun `clay body retains the requested pitched mode without a kick sweep`() {
        val rate = 44_100
        for (root in listOf(98.0, 196.0, 392.0)) {
            val samples = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAY, root, rate, .8, 33)
            val pitched = projection(samples, rate, root, .065, .215)
            val betweenModes = projection(samples, rate, root * 1.22, .065, .215)
            assertTrue(pitched > betweenModes * 4, "$root Hz: mode $pitched, off-mode $betweenModes")
        }
    }

    @Test
    fun `finite event energy turns off excitation and scales a quiet clay contact`() {
        val rate = 44_100
        for (kind in CircuitInstruments.Kind.entries) {
            assertTrue(CircuitInstruments.gesture(kind, 130.8128, rate, 0.0, 33).all { it == 0f })
        }
        val full = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAY, 130.8128, rate, .7, 33)
        val half = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAY, 130.8128, rate, .35, 33)
        for (i in full.indices) assertEquals(full[i] * .5f, half[i], 1e-7f)
    }

    @Test
    fun `power cutoff prevents later strikes and syllables while preserving passive tails`() {
        val rate = 44_100
        for (kind in CircuitInstruments.Kind.entries) {
            val silent = CircuitInstruments.gesture(kind, 130.8128, rate, .9, 33, poweredSeconds = 0.0)
            assertTrue(silent.all { it == 0f }, "$kind is excited with power already off")
        }
        val clapper = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAPPER, 130.8128, rate, .9, 33, poweredSeconds = .025)
        val clapperLater = CircuitInstruments.gesture(CircuitInstruments.Kind.CLAPPER, 130.8128, rate, .9, 33, poweredSeconds = .040)
        // Both cutoffs are after the first contact and before the second: the same passive response remains.
        assertTrue(clapper.contentEquals(clapperLater), "the mounting and arm tails should not need continued power")
        assertTrue(rms(clapper, rate, .026, .045) > .001, "cutoff hard-truncates the clapper's passive response")

        val voice = CircuitInstruments.gesture(CircuitInstruments.Kind.UH_HUH, 130.8128, rate, .9, 33, poweredSeconds = .100)
        val fullVoice = CircuitInstruments.gesture(CircuitInstruments.Kind.UH_HUH, 130.8128, rate, .9, 33)
        assertTrue(rms(voice, rate, .100, .105) > .0001, "cutoff hard-truncates the voice tract")
        assertTrue(rms(voice, rate, .270, .415) < rms(fullVoice, rate, .270, .415) * .02, "a second syllable starts after power is off")
    }

    private fun rms(samples: FloatArray, rate: Int, from: Double, to: Double): Double {
        val start = (from * rate).toInt()
        val end = (to * rate).toInt()
        var energy = 0.0
        for (i in start until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - start))
    }

    private fun projection(samples: FloatArray, rate: Int, frequency: Double, from: Double, to: Double): Double {
        val start = (from * rate).toInt()
        val end = (to * rate).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (i in start until end) {
            val window = .5 - .5 * cos(2.0 * PI * (i - start) / (end - start - 1))
            val angle = 2.0 * PI * frequency * i / rate
            real += samples[i] * window * cos(angle)
            imaginary += samples[i] * window * sin(angle)
        }
        return real * real + imaginary * imaginary
    }
}
