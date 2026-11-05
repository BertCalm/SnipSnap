package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverlapBreathingPrototypeTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE
    private val os = Dsp.OVERSAMPLE

    @Test
    fun `steady raw is exactly the approved held circuit before any level change`() {
        val accepted = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.TREFOIL,
            mapOf("KNOT" to 1f, "RING" to .6f, "PULSE" to 0f, "LOCK" to 0f, "GLOW" to 0f, "HOLD" to 1f),
            midi = 48, velocity = .8f, periodSeconds = 1.5)
        val actual = results.getValue(OverlapBreathingPrototype.Mode.STEADY)
        assertContentEquals(accepted.raw, actual.raw, "isolated breathing proof changed the accepted steady physics")
        assertEquals(accepted.diagnostics.delaySamples, actual.plan.delaySamples)
        assertEquals(accepted.diagnostics.attempts, actual.diagnostics.attempts)
        assertEquals(accepted.diagnostics.internalFrames, actual.diagnostics.internalFrames)
        assertEquals(accepted.diagnostics.stateError, actual.diagnostics.stateError)
        assertTrue(actual.trace.gateMean.all { row -> row.all { it == 1.0 } })
        assertTrue(actual.trace.extraLossMean.all { row -> row.all { it == 0.0 } })
    }

    @Test
    fun `slow endpoint plans close on four unchanged source periods and refuse intermediate lock`() {
        val steady = results.getValue(OverlapBreathingPrototype.Mode.STEADY).plan
        val free = results.getValue(OverlapBreathingPrototype.Mode.INDEPENDENT).plan
        val locked = results.getValue(OverlapBreathingPrototype.Mode.SYNCHRONIZED).plan
        assertEquals(4 * steady.periodFrames, free.periodFrames)
        assertEquals(free.periodFrames, locked.periodFrames)
        assertEquals(steady.sourcePeriodFrames, free.sourcePeriodFrames)
        assertEquals(listOf(3, 4, 4, 5, 3, 5), free.counts)
        assertEquals(listOf(4, 4, 4, 4, 4, 4), locked.counts)
        assertEquals(listOf(.13, 1.23, 2.41, 3.07, 4.45, 5.51), free.offsets)
        assertTrue(locked.offsets.all { it == 0.0 })
        assertTrue(free.periodSeconds in 5.9..6.1)
        assertTrue(abs(free.ratesHz.average() - locked.ratesHz.average()) < 1e-15)
        assertTrue(locked.ratesHz.all { it in .65 .. .69 })
        assertEquals(OverlapBreathingPrototype.Mode.INDEPENDENT, OverlapBreathingPrototype.Mode.fromLock(0f))
        assertEquals(OverlapBreathingPrototype.Mode.SYNCHRONIZED, OverlapBreathingPrototype.Mode.fromLock(1f))
        for (lock in listOf(-.1f, .01f, .5f, .99f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OverlapBreathingPrototype.Mode.fromLock(lock) }
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                OverlapBreathingPrototype.Recipe(OverlapBreathingPrototype.Mode.INDEPENDENT, velocity)
            }
        }
    }

    @Test
    fun `all modes recur in full physical state with genuine preceding and following audio`() {
        for ((mode, result) in results) {
            val p = result.plan
            val d = result.diagnostics
            assertTrue(d.converged && d.attempts in 3..d.maxAttempts, "$mode recurrence budget")
            assertTrue(d.stateError < 2e-7 && d.audioError < 1e-8, "$mode state and audio recurrence")
            assertEquals(0, d.cycleSourceCursor)
            assertEquals(0, d.cycleControlCursor)
            assertTrue(d.stateSize > p.delaySamples.sumOf { (it * os).toInt() } + 30,
                "$mode full state omitted delay cells or persistent filters")
            assertEquals((d.attempts + 1L) * p.periodFrames * os + 256 * os, d.internalFrames)
            assertTrue(d.internalFrames <= d.maxInternalFrames)
            assertTrue(d.cancellationChecks > 100)
            assertTrue(d.minLoopGain > 0.0 && d.minLoopGain <= d.maxLoopGain && d.maxLoopGain < 1.0 && d.maxBufferAbs < 1.0)
            assertTrue(result.raw.all { it.isFinite() } && d.rawPeak > 1e-6)
            assertEquals(3 * p.periodFrames, result.rawContext.size)
            val before = result.rawContext.copyOfRange(0, p.periodFrames)
            val kept = result.rawContext.copyOfRange(p.periodFrames, 2 * p.periodFrames)
            val after = result.rawContext.copyOfRange(2 * p.periodFrames, 3 * p.periodFrames)
            assertContentEquals(result.raw, kept)
            assertTrue(relativeError(before, kept) < 1e-8, "$mode real predecessor does not recur")
            assertTrue(relativeError(kept, after) < 1e-8, "$mode real successor does not recur")
            assertEquals(Keys.seamError(before + kept, p.periodFrames), d.seam)
            assertEquals(Keys.seamError(kept + after, p.periodFrames), d.nextSeam)
            assertTrue(d.seam < Keys.MAX_SEAM_ERROR && d.nextSeam < Keys.MAX_SEAM_ERROR)
            // The repeated export also crosses the same physical boundary. This
            // is checked against real neighbors above, not accepted in isolation.
            assertTrue(Keys.seamError(kept + kept, p.periodFrames) < Keys.MAX_SEAM_ERROR)
            assertTrue(listOf(d.setupMillis, d.solveMillis, d.conditioningMillis, d.totalMillis)
                .all { it.isFinite() && it >= 0.0 })
        }
    }

    @Test
    fun `gates move actual node excitation and energy rather than scaling a static mixed recording`() {
        val steady = results.getValue(OverlapBreathingPrototype.Mode.STEADY)
        val free = results.getValue(OverlapBreathingPrototype.Mode.INDEPENDENT)
        val locked = results.getValue(OverlapBreathingPrototype.Mode.SYNCHRONIZED)
        for (result in listOf(free, locked)) {
            val trace = result.trace
            assertEquals(128, trace.nodeRms.size)
            assertEquals(0, trace.windowStartFrames.first())
            assertEquals(result.plan.periodFrames, trace.windowEndFrames.last())
            assertTrue(trace.gateMean.flatten().all { it in (.012 - 1e-12)..(1.0 + 1e-12) })
            assertTrue(trace.extraLossMean.flatten().all { it in 0.0..18.0 })
            assertTrue(trace.nodeEnergyShares.all { abs(it.sum() - 1.0) < 1e-12 })
            for (node in 0..5) {
                val drive = trace.excitationRms.map { it[node] }
                val energy = trace.nodeRms.map { it[node] }
                assertTrue(drive.maxOrNull()!! > 15.0 * drive.minOrNull()!!,
                    "${result.plan.recipe.mode} node $node excitation did not breathe")
                assertTrue(energy.maxOrNull()!! > 3.0 * energy.minOrNull()!!,
                    "${result.plan.recipe.mode} node $node read power did not follow physical gating")
            }
            // Best local scalar fits remove a hypothetical master VCA. A large
            // remaining waveform residual proves changes inside the resonator.
            assertTrue(masterGainResidual(steady.raw, result.raw, rate / 20) > .03,
                "${result.plan.recipe.mode} motion can be explained by a master gain on steady audio")
        }
        assertTrue(locked.trace.gateMean.all { row -> row.all { it == row.first() } },
            "synchronized nodes do not share the same actual excitation gate")
        assertTrue(free.trace.gateMean.any { row -> row.maxOrNull()!! - row.minOrNull()!! > .5 },
            "independent nodes never separate their actual excitation gates")
        assertTrue((0..5).any { node ->
            val share = free.trace.nodeEnergyShares.map { it[node] }
            share.maxOrNull()!! - share.minOrNull()!! > .1
        }, "independent physical circuit never changes relative node-read power balance")
        // Compare equal windows without a level dependency; endpoint cadence must
        // differ in its actual circuit energy, not just in the oscillator labels.
        val freeEnergy = free.trace.nodeRms.map { row -> sqrt(row.sumOf { it * it }) }
        val lockedEnergy = locked.trace.nodeRms.map { row -> sqrt(row.sumOf { it * it }) }
        assertTrue(correlation(freeEnergy, lockedEnergy) < .9, "free and synchronized circuit cadence collapsed")
        assertTrue(lockedEnergy.maxOrNull()!! > 8.0 * lockedEnergy.minOrNull()!!,
            "closed synchronized nodes did not sufficiently reduce circulating node-read power")
    }

    @Test
    fun `moving loop and physical traces are deterministic before gain or packaging`() {
        val expected = results.getValue(OverlapBreathingPrototype.Mode.INDEPENDENT)
        val again = OverlapBreathingPrototype.renderLoopMeasured(expected.plan.recipe)
        assertContentEquals(expected.raw, again.raw)
        assertContentEquals(expected.rawContext, again.rawContext)
        assertEquals(expected.plan, again.plan)
        assertEquals(expected.trace, again.trace)
        assertEquals(expected.diagnostics.stateError, again.diagnostics.stateError)
        assertEquals(expected.diagnostics.audioError, again.diagnostics.audioError)
        assertEquals(expected.diagnostics.internalFrames, again.diagnostics.internalFrames)
        assertEquals(expected.diagnostics.cancellationChecks, again.diagnostics.cancellationChecks)
    }

    @Test
    fun `zero velocity silences the circuit while control motion and recurrence remain defined`() {
        val result = OverlapBreathingPrototype.renderLoopMeasured(
            OverlapBreathingPrototype.Recipe(OverlapBreathingPrototype.Mode.SYNCHRONIZED, velocity = 0f))
        assertTrue(result.raw.all { it == 0f } && result.rawContext.all { it == 0f })
        assertEquals(0.0, result.diagnostics.rawPeak)
        assertEquals(0.0, result.diagnostics.rawRms)
        assertEquals(0.0, result.diagnostics.seam)
        assertTrue(result.trace.excitationRms.flatten().all { it == 0.0 })
        assertTrue(result.trace.nodeRms.flatten().all { it == 0.0 })
        assertTrue(result.trace.nodeEnergyShares.flatten().all { it == 0.0 })
        assertTrue(result.trace.gateMean.any { it.first() > .5 })
        assertTrue(result.trace.gateMean.any { it.first() < .02 })
    }

    @Test
    fun `cancellation stops before allocation during source setup and during the physical solve`() {
        val recipe = OverlapBreathingPrototype.Recipe(OverlapBreathingPrototype.Mode.INDEPENDENT)
        var calls = 0
        assertFailsWith<CancellationException> {
            OverlapBreathingPrototype.renderLoopMeasured(recipe) { calls++; true }
        }
        assertEquals(1, calls, "immediate cancellation did not stop before source setup")
        calls = 0
        assertFailsWith<CancellationException> {
            OverlapBreathingPrototype.renderLoopMeasured(recipe) { ++calls >= 3 }
        }
        assertEquals(3, calls, "source setup ignored cancellation")
        calls = 0
        // Six driver blocks and means use fewer than 450 checkpoints. The next
        // request therefore interrupts evolving delay/filter state, not setup.
        assertFailsWith<CancellationException> {
            OverlapBreathingPrototype.renderLoopMeasured(recipe) { ++calls >= 500 }
        }
        assertEquals(500, calls, "physical evolution ignored cancellation")
    }

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        var error = 0.0; var power = 0.0
        for (i in a.indices) {
            val delta = a[i].toDouble() - b[i]
            error += delta * delta; power += b[i].toDouble() * b[i]
        }
        return if (power <= 1e-30) error else error / power
    }

    private fun masterGainResidual(source: FloatArray, moving: FloatArray, window: Int): Double {
        var residual = 0.0; var power = 0.0
        for (start in moving.indices step window) {
            val end = minOf(moving.size, start + window)
            var xx = 0.0; var xy = 0.0; var yy = 0.0
            for (n in start until end) {
                val x = source[n % source.size].toDouble()
                val y = moving[n].toDouble()
                xx += x * x; xy += x * y; yy += y * y
            }
            residual += yy - if (xx > 1e-30) xy * xy / xx else 0.0
            power += yy
        }
        return residual / power
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val am = a.average(); val bm = b.average()
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in a.indices) {
            val x = a[i] - am; val y = b[i] - bm
            xy += x * y; xx += x * x; yy += y * y
        }
        return xy / sqrt(xx * yy)
    }

    companion object {
        // Expensive physical captures are immutable shared fixtures, not a new
        // production cache. No test mutates their audio or diagnostic collections.
        private val results by lazy {
            OverlapBreathingPrototype.Mode.entries.associateWith { mode ->
                OverlapBreathingPrototype.renderLoopMeasured(OverlapBreathingPrototype.Recipe(mode))
            }
        }
    }
}
