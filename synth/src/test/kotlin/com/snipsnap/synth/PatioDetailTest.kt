package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class PatioDetailTest {
    private fun t90(x: FloatArray): Int {
        val target = x.sumOf { it.toDouble() * it } * .90
        var total = 0.0
        return x.indices.first { total += x[it].toDouble() * x[it]; total >= target }
    }
    @Test fun `supporting voices retain more body through the unchanged cleaner chain`() {
        for (r in PatioStudy.recipes().filter { it.id in PatioDetail.IDS }) {
            val output = PatioDetail.render(r)
            val before = PatioCleanup.render(r)
            assertTrue(t90(output.samples) > t90(before.samples) * 1.35, r.id)
            assertContentEquals(output.samples, PatioDetail.render(r).samples)
            assertTrue(output.samples.all { it.isFinite() })
            assertTrue(output.peak() in .01f..1f)
            assertTrue(abs(output.samples.average()) < .001)
            assertEquals(PatioDetail.patch(r).toJsonText(), Patches.fromJsonText(PatioDetail.patch(r).toJsonText()).toJsonText())
        }
    }
    @Test fun `other pads remain the selected cleaner renders`() {
        for (r in PatioStudy.recipes().filter { it.id !in PatioDetail.IDS })
            assertContentEquals(PatioCleanup.render(r).samples, PatioDetail.render(r).samples, r.id)
    }
}
