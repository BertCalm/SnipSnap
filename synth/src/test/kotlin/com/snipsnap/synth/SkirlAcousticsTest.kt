package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkirlAcousticsTest {
    private fun originalRoll(terminalDegrees: Double = 7.0, horizon: Double = 12.0) =
        SkirlDynamics.trace(SkirlDynamics.Parameters(
            leanAlphaRad = Math.toRadians(22.85), muRoll = .002 + .03 * .4.pow(1.5),
            terminalAlphaRad = Math.toRadians(terminalDegrees), maxSeconds = horizon))

    private fun energy(routes: Array<DoubleArray>, rate: Int = SkirlAcoustics.RATE): Double =
        routes.sumOf { row -> row.sumOf { it * it } } / rate

    @Test
    fun `coherent overlapping contacts share one two-route budget`() {
        val rate = SkirlAcoustics.RATE
        val duration = 2.0
        val power = .3
        for (width in listOf(.002, .050)) for (frequency in listOf(5, 20, 80)) {
            val routes = Array(2) { DoubleArray((duration * rate).toInt()) }
            val naive = Array(2) { DoubleArray(routes[0].size) }
            val pulse = SkirlAcoustics.unitPulse(width)
            var incremental = 0.0
            val weights = doubleArrayOf(.8, .6)
            repeat((duration * frequency).toInt()) { contact ->
                val start = (contact.toDouble() / frequency * rate).toInt()
                val budget = power / frequency
                val result = SkirlAcoustics.addBudgetedPulse(routes, pulse, start, budget, .8, .6)
                assertTrue(result.amplitude <= sqrt(budget) + 1e-14)
                assertTrue(result.incrementalEnergy <= budget + 1e-12)
                incremental += result.incrementalEnergy
                for (route in 0..1) for (i in pulse.indices) {
                    if (start + i < naive[route].size)
                        naive[route][start + i] += sqrt(budget) * weights[route] * pulse[i]
                }
            }
            val measured = energy(routes)
            assertTrue(measured <= duration * power + 1e-11, "width $width / $frequency Hz: $measured")
            assertTrue(abs(incremental - measured) < 1e-10)
            if (width == .050 && frequency == 80) {
                // This control fails the same bound without the overlap term.
                assertTrue(energy(naive) > 2.5 * duration * power)
                assertTrue(measured > .58, "The accumulator should spend available work without a broad fade")
            } else assertTrue(abs(measured - duration * power) < 1e-10)
        }
    }

    @Test
    fun `truncated contacts never amplify fragments or duplicate routed energy`() {
        val pulse = SkirlAcoustics.unitPulse(.050)
        for ((start, frames) in listOf(-pulse.size / 2 to pulse.size * 2,
            pulse.size / 2 to pulse.size, -pulse.size * 2 to pulse.size,
            pulse.size * 2 to pulse.size)) {
            val routes = Array(2) { DoubleArray(frames) }
            val use = SkirlAcoustics.addBudgetedPulse(routes, pulse, start, .04, .8, .6)
            assertTrue(use.amplitude <= .2)
            assertTrue(energy(routes) <= .04 + 1e-12)
            assertTrue(use.unusedEnergy > 0)
        }
        for ((disk, support) in listOf(1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, .01 to .5)) {
            val routes = Array(2) { DoubleArray(pulse.size) }
            SkirlAcoustics.addBudgetedPulse(routes, pulse, 0, .04, disk, support)
            assertTrue(abs(energy(routes) - .04) < 1e-12)
        }
        val routes = Array(2) { DoubleArray(pulse.size) }
        val first = SkirlAcoustics.addBudgetedPulse(routes, pulse, 0, .04, .8, .6)
        val second = SkirlAcoustics.addBudgetedPulse(routes, pulse, 0, .04, .8, .6)
        assertTrue(second.amplitude < first.amplitude)
        assertTrue(abs(energy(routes) - .08) < 1e-12)
        val before = routes.map { it.clone() }
        val zero = SkirlAcoustics.addBudgetedPulse(routes, pulse, 0, 0.0, .8, .6)
        assertEquals(0.0, zero.incrementalEnergy)
        for (i in 0..1) assertContentEquals(before[i], routes[i])
    }

    @Test
    fun `signed acoustic contacts spend at most their budget even under destructive overlap`() {
        val pulse = SkirlAcoustics.unitPulse(.010)
        val bipolar = pulse.mapIndexed { i, value -> if (i < pulse.size / 2) value else -value }.toDoubleArray()
        for (kernel in listOf(pulse, bipolar)) for (direction in listOf(-.8 to -.6, -.8 to .6, .8 to -.6)) {
            val routes = Array(2) { DoubleArray(pulse.size * 2) }
            SkirlAcoustics.addBudgetedPulse(routes, pulse, 0, .04, .8, .6)
            val before = energy(routes)
            val result = SkirlAcoustics.addBudgetedPulse(routes, kernel, 0, .04, direction.first, direction.second)
            val measuredIncrement = energy(routes) - before
            assertTrue(result.amplitude <= .2)
            assertTrue(measuredIncrement <= .04 + 1e-12)
            assertTrue(abs(measuredIncrement - result.incrementalEnergy) < 1e-12)
            if (kernel === pulse && direction.first < 0 && direction.second < 0) {
                assertTrue(measuredIncrement < 0, "Opposed contacts must be allowed to cancel acoustic input")
                assertTrue(result.unusedEnergy > .04)
            }
        }
        val trace = originalRoll()
        val signed = SkirlAcoustics.sources(trace, .4f, .5f, 0f, 1f,
            Dsp.seedFor("SKIRL", 1, "ROLL"), signedTexture = true)
        assertTrue(signed.diagnostics.combinedBudgetSatisfied)
        assertEquals(0f, signed.diagnostics.restExcitationPeak)
        assertTrue(signed.diagnostics.signedTexture)
        val replay = SkirlAcoustics.sources(trace, .4f, .5f, 0f, 1f,
            Dsp.seedFor("SKIRL", 1, "ROLL"), signedTexture = true)
        assertContentEquals(signed.disk, replay.disk)
        assertContentEquals(signed.support, replay.support)
    }

    @Test
    fun `contact crossings use unwrapped contact azimuth in either direction`() {
        val times = doubleArrayOf(0.0, 1.0)
        val positive = SkirlAcoustics.contactEvents(times, doubleArrayOf(0.0, 6 * PI))
        val negative = SkirlAcoustics.contactEvents(times, doubleArrayOf(0.0, -6 * PI))
        assertEquals(4, positive.size)
        assertContentEquals(positive, negative)
        for (i in 0..3) assertTrue(abs(positive[i] - i / 3.0) < 1e-14)
        val fourFeatures = SkirlAcoustics.contactEvents(times, doubleArrayOf(0.0, 2 * PI), 4)
        assertContentEquals(doubleArrayOf(0.0, .25, .5, .75, 1.0), fourFeatures)
        val reversing = SkirlAcoustics.contactEvents(doubleArrayOf(0.0, 1.0, 2.0),
            doubleArrayOf(.5 * PI, 6.5 * PI, .5 * PI))
        assertTrue(reversing.asList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(7, reversing.size)
    }

    @Test
    fun `mechanical work funds rolling and authored terminal contacts then REST is silent`() {
        val trace = originalRoll()
        assertEquals(SkirlDynamics.Exit.TERMINAL, trace.exit)
        val savedRim = trace.rimPhases.clone()
        val savedContact = trace.contactPhases.clone()
        for (edge in listOf(.05f, .4f, .95f)) for (fine in listOf(false, true)) {
            val source = SkirlAcoustics.sources(trace, edge, .5f, 0f, 1f, 0, fine)
            val d = source.diagnostics
            assertTrue(d.assignedRollingEnergy > 0)
            assertTrue(d.combinedBudgetSatisfied)
            assertTrue(d.measuredRollingEnergy <= d.assignedRollingEnergy + 1e-10)
            assertTrue(d.assignedTerminalEnergy > 0)
            assertTrue(d.assignedTerminalEnergy <= .05 * d.assignedRollingEnergy + 1e-12)
            assertTrue(d.incrementalTerminalEnergy <= d.assignedTerminalEnergy + 1e-10)
            assertEquals(5, d.terminalEvents.size)
            assertTrue(d.terminalEvents.all { it.time >= trace.duration && it.time < d.restStartSeconds })
            // This filter energy check is independent of the accumulator's own diagnostic.
            var measured = 0.0
            for (i in source.disk.indices) {
                assertTrue(source.disk[i].isFinite() && source.support[i].isFinite())
                measured += source.disk[i].toDouble() * source.disk[i] +
                    source.support[i].toDouble() * source.support[i]
                if (i >= kotlin.math.ceil(d.restStartSeconds * SkirlAcoustics.RATE).toInt()) {
                    assertEquals(0f, source.disk[i])
                    assertEquals(0f, source.support[i])
                }
            }
            assertTrue(measured / SkirlAcoustics.RATE <= d.prefilterEnergy + 2e-6)
            if (fine) assertTrue(d.rollingEvents.any { it.feature > 0 })
        }
        assertContentEquals(savedRim, trace.rimPhases)
        assertContentEquals(savedContact, trace.contactPhases)
        val silent = SkirlAcoustics.sources(trace, .4f, .5f, 0f, 0f, 0)
        assertTrue(silent.disk.all { it == 0f } && silent.support.all { it == 0f })
        assertEquals(0.0, silent.diagnostics.assignedRollingEnergy)
    }

    @Test
    fun `failed and unresolved histories withdraw without a successful shudder`() {
        val failed = originalRoll(terminalDegrees = 1.9)
        val truncated = originalRoll(horizon = .05)
        val immediate = SkirlDynamics.trace(originalRoll().params.copy(maxEvaluations = 1))
        assertEquals(SkirlDynamics.Exit.FAILURE, failed.exit)
        assertEquals(SkirlDynamics.Exit.HORIZON, truncated.exit)
        assertEquals(SkirlDynamics.Exit.FAILURE, immediate.exit)
        for (trace in listOf(failed, truncated, immediate)) {
            val source = SkirlAcoustics.sources(trace, .4f, .5f, .85f, 1f, 0)
            val d = source.diagnostics
            assertTrue(d.terminalEvents.isEmpty())
            assertEquals(0.0, d.assignedTerminalEnergy)
            assertEquals(0.0, d.terminalSeconds)
            assertEquals(.04, d.failureWithdrawalSeconds)
            assertTrue(d.combinedBudgetSatisfied)
            assertTrue(source.disk.drop((d.restStartSeconds * SkirlAcoustics.RATE).toInt() + 1).all { it == 0f })
        }
    }

    @Test
    fun `fixed compensated modes remain finite deterministic and freely decay at low notes`() {
        val trace = originalRoll()
        for (midi in listOf(36, 48)) {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val seed = Dsp.seedFor("SKIRL", 1, "ROLL")
            val render = SkirlAcoustics.render(trace, hz, .4f, .5f, 0f, 1f, seed, signedTexture = true)
            val replay = SkirlAcoustics.render(trace, hz, .4f, .5f, 0f, 1f, seed, signedTexture = true)
            assertContentEquals(render.raw, replay.raw)
            assertTrue(render.raw.all { it.isFinite() })
            fun rms(from: Double, to: Double): Double {
                val start = (from * SkirlAcoustics.RATE).toInt()
                val end = (to * SkirlAcoustics.RATE).toInt()
                var sum = 0.0
                for (i in start until end) sum += render.raw[i].toDouble() * render.raw[i]
                return sqrt(sum / (end - start))
            }
            val rest = render.diagnostics.restStartSeconds
            assertTrue(rms(rest + 1.5, rest + 1.8) < .02 * rms(rest + .1, rest + .4))
        }
    }

    @Test
    fun `bright high-note output agrees with an independently sampled 8x reference`() {
        // Same mechanics, different acoustic sampling grids. Short unresolved
        // history keeps this test cheap and exercises real rolling contacts.
        // MIDI84 is the top of the explicit production-note contract; MIDI108
        // deliberately stresses upper modes beyond output Nyquist internally.
        val trace = originalRoll(horizon = .30)
        fun exported(rate: Int, hz: Double, edge: Float): FloatArray {
            val render = SkirlAcoustics.render(trace, hz, edge, .5f, 0f, 1f, 0,
                rate = rate, signedTexture = true)
            Tide.bandLimit(render.raw, rate)
            val normal = if (rate == SkirlAcoustics.RATE) render.raw else
                Resampler.resample(Snip(render.raw, channels = 1, sampleRate = rate), SkirlAcoustics.RATE).samples
            return Dsp.decimate(normal, Dsp.RATE)
        }
        fun mass(samples: FloatArray): DoubleArray {
            val size = 32768
            val re = FloatArray(size)
            val im = FloatArray(size)
            val first = (Dsp.RATE * .02).toInt()
            val count = (Dsp.RATE * .25).toInt()
            for (i in 0 until count) re[i] = samples[first + i] *
                (.5 - .5 * kotlin.math.cos(2 * PI * i / (count - 1))).toFloat()
            Fft.forward(re, im)
            val bounds = doubleArrayOf(0.0, 500.0, 1500.0, 3000.0, 6000.0, 10000.0, 15000.0, 22051.0)
            val bands = DoubleArray(bounds.size - 1)
            for (bin in 1 until size / 2) {
                val hz = bin.toDouble() * Dsp.RATE / size
                val band = bands.indices.first { hz >= bounds[it] && hz < bounds[it + 1] }
                bands[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            val total = bands.sum()
            assertTrue(total > 1e-20)
            for (i in bands.indices) bands[i] /= total
            return bands
        }
        for (midi in listOf(84, 108)) for (edge in listOf(0f, 1f)) {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val native = mass(exported(SkirlAcoustics.RATE, hz, edge))
            val reference = mass(exported(SkirlAcoustics.RATE * 2, hz, edge))
            // Comparing normalized mass removes the expected 2x continuous-
            // forcing gain of the 8x discrete bank. It is not alias energy.
            val difference = native.indices.sumOf { abs(native[it] - reference[it]) }
            println("SKIRL 4x/8x MIDI$midi EDGE$edge normalized band-mass L1=$difference; " +
                "15k-Nyquist mass=${native.last()}/${reference.last()}")
            assertTrue(difference < .02, "4x/8x spectral distribution differs: $difference")
            assertTrue(native.last() < 1e-4, "Unexpected output energy near Nyquist: ${native.last()}")
        }
    }
}
