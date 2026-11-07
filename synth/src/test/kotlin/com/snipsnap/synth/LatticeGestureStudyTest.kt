package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LatticeGestureStudyTest {
    @Test
    fun `moving pluck changes harmonic balance then leaves a causal collector tail`() {
        val curve = LatticeGestureStudy.controls(LatticeGestureStudy.frames, 2.0)
        for (midi in listOf(48, 55, 51)) {
            val settings = LatticeGestureStudy.settings(midi)
            val plain = Lattice.probe(settings)
            val moving = Lattice.probe(settings, curve)
            val withoutCollector = Lattice.probe(settings.copy(haloEnabled = false), curve)
            val early = (0 until (.025 * Dsp.RATE).toInt()).maxOf {
                abs(plain.snip.samples[it] - moving.snip.samples[it]).toDouble()
            }
            assertTrue(early < 1e-8, "MIDI $midi must begin with the same unaltered attack: $early")
            assertEquals(plain.diagnostics.sourceGain, moving.diagnostics.sourceGain, "the gates cannot change source gain")
            assertTrue(moving.diagnostics.allocationResidual < 1e-12, "motion must retain the projection energy partition")
            val evidence = LatticeGestureStudy.evidence(moving, settings)
            val tail = evidence.getValue("tailOutputRms") as Double
            val dryTail = evidence.getValue("tailPassingRms") as Double
            val noCollectorTail = LatticeGestureStudy.rms(withoutCollector.snip, .6, 1.2)
            val balanceChange = evidence.getValue("harmonicPowerBalanceL1") as Double
            println("LATTICE moving MIDI $midi: $evidence; noCollectorTailRms=$noCollectorTail; initialDifference=$early")
            assertTrue(balanceChange > .15, "MIDI $midi must change harmonic ratios, not just gain: $balanceChange")
            val firstTailRatio = evidence.getValue("postSourceToEarlyRmsRatio") as Double
            assertTrue(firstTailRatio > .03, "MIDI $midi post-source ring must retain a useful measured fraction of its attack: $firstTailRatio")
            assertTrue(tail > 1e-5, "MIDI $midi must leave a measured resonator tail: $tail")
            assertTrue(dryTail < tail * 1e-3, "MIDI $midi direct path must have closed before the tail window")
            assertTrue(noCollectorTail < tail * 1e-3, "MIDI $midi tail must disappear when the reject collector is removed")
            assertTrue(moving.snip.samples.all { it.isFinite() && abs(it) < .99f }, "motion must retain headroom")
        }
        val closed = Lattice.compile(curve(.5)).groups.map { abs(it.transmission) }
        assertTrue(closed.all { it < 1e-12 }, "every harmonic group must close, including colored groups: $closed")
    }

    @Test
    fun `saved motion rejects invalid or ambiguous keyframes`() {
        val a = LatticeGestureStudy.frames.first()
        assertFailsWith<IllegalArgumentException> { LatticeGestureStudy.controls(listOf(a), 2.0) }
        assertFailsWith<IllegalArgumentException> { LatticeGestureStudy.controls(listOf(a, a), 2.0) }
        assertFailsWith<IllegalArgumentException> { LatticeGestureStudy.controls(listOf(a.copy(time = .1), a.copy(time = 1.0)), 2.0) }
        assertFailsWith<IllegalArgumentException> { LatticeGestureStudy.controls(listOf(a, a.copy(time = 3.0)), 2.0) }
        assertFailsWith<IllegalArgumentException> { LatticeGestureStudy.controls(listOf(a, a.copy(time = 1.0, color = Double.NaN)), 2.0) }
    }
}
