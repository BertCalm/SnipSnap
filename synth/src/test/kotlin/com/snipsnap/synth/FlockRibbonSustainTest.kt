package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlockRibbonSustainTest {
    private fun quick(flow: Float = .78f, velocity: Float = 1f, hold: Float = .72f) = FlockRibbonSustain.render(
        macros = Flock.defaults(FlockVoice.RIBBON) + mapOf("FLOW" to flow, "HOLD" to hold), velocity = velocity,
        heldSeconds = 9.0, loopSeconds = 2.0, crossfadeSeconds = .25, releaseSeconds = .5, oversample = 1)

    @Test
    fun `continuous source retains the finite flock equations and carriers without its envelope`() {
        val m = Flock.defaults(FlockVoice.RIBBON) + ("HOLD" to 0f)
        val continuous = Flock.renderContinuousRibbon(2.0, m, 60, 1f, 42001L, oversample = 1)
        val finite = Flock.renderDetailed(FlockVoice.RIBBON, m, seed = 42001L, oversample = 1)
        assertEquals(finite.trace, continuous.trace)
        fun smooth(u: Double) = u.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
        for (i in 0 until Dsp.RATE) {
            val time = i.toDouble() / Dsp.RATE
            val env = smooth(time / .045) * (.82 + .18 * exp(-time / .30))
            assertTrue(abs(finite.rawSamples[i] - continuous.rawSamples[i] * env) < 2e-8,
                "continuous path substituted a carrier at frame $i")
        }
        assertTrue(abs(continuous.rawSamples.last()) > 1e-6, "continuous source was tail-faded")
        assertTrue(finite.rawSamples.last() == 0f)
    }

    @Test
    fun `captured playback preserves moving source and natural wrap context with one pair gain`() {
        val a = quick()
        // HOLD still belongs to the finite recipe; explicit experiment durations replace it here.
        val b = quick(hold = 0f)
        assertContentEquals(a.rawReference, b.rawReference)
        assertContentEquals(a.rawLooped, b.rawLooped)
        assertContentEquals(a.loopBody, b.loopBody)
        assertEquals(a.plan, b.plan)
        assertEquals(a.diagnostics, b.diagnostics)
        val p = a.plan
        assertEquals(3, p.wrapFrames.size)
        assertEquals((9.5 * Dsp.RATE).toInt(), p.totalFrames)
        assertEquals(p.totalFrames, a.rawReference.size)
        assertEquals(p.loopFrames, a.rawLoopBody.size)
        assertEquals(p.loopEndExclusive, a.attackPlusLoop.samples.size)
        assertContentEquals(a.source.rawSamples.copyOfRange(p.loopStartFrame, p.sourceBlendStartFrame),
            a.rawLoopBody.copyOfRange(0, p.loopFrames - p.crossfadeFrames))
        for (frame in listOf(p.loopStartFrame) + p.wrapFrames) {
            assertEquals(a.source.rawSamples[p.loopStartFrame - 1], a.rawLooped[frame - 1])
            assertEquals(a.source.rawSamples[p.loopStartFrame], a.rawLooped[frame])
        }
        for (i in a.rawReference.indices step 97) {
            assertEquals((a.rawReference[i] * a.diagnostics.commonGain).toFloat(), a.continuousReference.samples[i])
            assertEquals((a.rawLooped[i] * a.diagnostics.commonGain).toFloat(), a.loopedAudition.samples[i])
        }
        assertTrue(a.diagnostics.capturedMotion.rmsPitchDepthSemitones > .01)
        assertTrue(a.diagnostics.capturedMotion.rmsPitchSpeedSemitonesPerSecond > .01)
        assertTrue(a.diagnostics.edges.filter { it.kind != "note-off" }.all {
            it.jump <= a.diagnostics.naturalStepMax * 1.1 &&
                it.slopeChange <= a.diagnostics.naturalSlopeChangeMax * 1.1
        }, "a blend or wrap exceeds the original waveform's natural sample changes")
        assertTrue(a.diagnostics.outputPeak <= .900001)
    }

    @Test
    fun `release phase wraps are included in the actual playback evidence`() {
        val probe = FlockRibbonSustain.render(heldSeconds = 9.0, loopSeconds = 2.0,
            crossfadeSeconds = .25, releaseSeconds = 2.5, oversample = 1)
        val p = probe.plan
        assertEquals(3, p.wrapFrames.count { it < p.releaseStartFrame })
        val releasedWraps = p.wrapFrames.filter { it >= p.releaseStartFrame }
        assertEquals(listOf(10 * Dsp.RATE), releasedWraps)
        assertEquals(releasedWraps, probe.diagnostics.edges.filter { it.kind == "loop-wrap" && it.phase == "release" }.map { it.outputFrame })
        assertTrue(probe.diagnostics.edges.any { it.kind == "blend-start" && it.phase == "release" })
        assertTrue(probe.diagnostics.edges.all { it.outputFrame in 2 until p.totalFrames - 1 })
    }

    @Test
    fun `long FLOW comparisons retain motion and release from whichever loop phase is playing`() {
        val slow = FlockRibbonSustain.render(macros = Flock.defaults(FlockVoice.RIBBON) + ("FLOW" to 0f), oversample = 1)
        val fast = FlockRibbonSustain.render(macros = Flock.defaults(FlockVoice.RIBBON) + ("FLOW" to 1f), oversample = 1)
        assertEquals(.12, slow.source.diagnostics.motionRate)
        assertEquals(4.0, fast.source.diagnostics.motionRate)
        assertEquals(slow.source.trace.first(), fast.source.trace.first())
        assertTrue(slow.plan.wrapFrames.size >= 3)
        assertTrue(fast.diagnostics.continuedMotion.characteristicMotionHz >
            2.0 * slow.diagnostics.continuedMotion.characteristicMotionHz,
            "FLOW did not change long-running movement: ${slow.diagnostics.continuedMotion} / ${fast.diagnostics.continuedMotion}")
        val distance = sqrt(slow.rawLooped.indices.sumOf { i ->
            val d = slow.rawLooped[i].toDouble() - fast.rawLooped[i]; d * d
        } / slow.rawLooped.size)
        assertTrue(distance > .01, "FLOW has no audible output over a sustained recording")
        for (probe in listOf(slow, fast)) {
            val p = probe.plan
            assertEquals(0f, probe.rawReference.first())
            assertEquals(0f, probe.rawLooped.first())
            assertEquals(0f, probe.rawReference.last())
            assertEquals(0f, probe.rawLooped.last())
            var previousGain = 1.0
            for (i in p.releaseStartFrame until p.totalFrames) {
                val source = probe.rawLoopBody[(i - p.loopStartFrame) % p.loopFrames]
                val u = (i - p.releaseStartFrame).toDouble() / (p.releaseFrames - 1)
                val gain = 1.0 - u * u * (3.0 - 2.0 * u)
                assertTrue(gain <= previousGain && gain >= 0.0)
                assertEquals(if (gain == 0.0) 0f else (source * gain).toFloat(), probe.rawLooped[i])
                previousGain = gain
            }
            assertTrue(probe.rawLooped.all { it.isFinite() })
            assertTrue(probe.diagnostics.blendMin50msDb.isFinite())
            assertTrue(probe.diagnostics.blendMax50msDb.isFinite())
        }
    }

    @Test
    fun `silent velocity cancellation and invalid audition plans are explicit`() {
        val silent = quick(velocity = 0f)
        assertTrue(silent.rawReference.all { it == 0f })
        assertTrue(silent.rawLooped.all { it == 0f })
        assertTrue(silent.loopedAudition.samples.all { it == 0f })
        assertTrue(silent.diagnostics.edges.all { it.jump == 0.0 && it.slopeChange == 0.0 })
        assertFailsWith<CancellationException> { FlockRibbonSustain.render(cancelled = { true }) }
        assertFailsWith<IllegalArgumentException> { FlockRibbonSustain.render(heldSeconds = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FlockRibbonSustain.render(heldSeconds = 10.0) }
        assertFailsWith<IllegalArgumentException> { FlockRibbonSustain.render(loopSeconds = .25) }
        assertFailsWith<IllegalArgumentException> { FlockRibbonSustain.render(crossfadeSeconds = 4.0) }
        assertFailsWith<IllegalArgumentException> { FlockRibbonSustain.render(releaseSeconds = 0.0) }
    }
}
