package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class PatioCleanupTest {
    @Test fun `cleanup replays without mutating source and stays healthy`() {
        for (r in PatioStudy.recipes()) {
            val input = PatioCleanup.dry(r)
            val original = input.samples.copyOf()
            val result = PatioCleanup.process(input, r.seed)
            assertContentEquals(original, input.samples)
            assertContentEquals(result.samples, PatioCleanup.process(input, r.seed).samples)
            assertTrue(result.samples.all { it.isFinite() })
            assertTrue(result.peak() in .01f..1f)
            assertTrue(abs(result.samples.average()) < .001)
            assertTrue(abs(result.samples.last()) < .001)
        }
    }
    @Test fun `low drum cleanup preserves attack and reduces late energy`() {
        for (r in PatioStudy.recipes().filter { it.id in setOf("surdo", "cajon") }) {
            val before = PatioWorkingKit.dry(r).samples
            val after = PatioCleanup.dry(r).samples
            val attack = (Dsp.RATE * .10).toInt()
            assertContentEquals(before.copyOfRange(0, attack), after.copyOfRange(0, attack))
            val start = (Dsp.RATE * .30).toInt()
            fun energy(x: FloatArray) = (start until x.size).sumOf { x[it].toDouble() * x[it] }
            assertTrue(energy(after) < energy(before) * .15)
        }
    }
}
