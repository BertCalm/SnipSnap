package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.*

class BristleRoundRobinTest {
    @Test fun `second take survives edits and codecs with genuine waveform diversity`() {
        for (base in BristlePresets.all()) {
            val patch = base.copy(take = 1, velocity = .65f)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(1, (patch.withMacros(patch.macros + ("GRIT" to .7f)) as BristlePatch).take)
            assertFails { patch.copy(take = 2) }
            val a = base.copy(velocity = .65f).render().samples
            val b = patch.render().samples
            assertContentEquals(b, patch.render().samples)
            // Reject gain-only copies by measuring the residual after least-squares gain matching.
            val gain = a.indices.sumOf { a[it].toDouble()*b[it] } / a.sumOf { it.toDouble()*it }
            val error = a.indices.sumOf { val d=b[it]-a[it]*gain; d*d }
            assertTrue(error / b.sumOf { it.toDouble()*it } > .01)
        }
    }
    @Test fun `second take stays bounded and closes held loops across the sampled grid`() {
        for (base in BristlePresets.all()) for (midi in 24..48) for (touch in listOf(.35f,.65f,1f)) {
            val patch = base.copy(midi = midi, velocity = touch, take = 1)
            val finite = patch.render().samples
            val held = BristleHeld.render(patch).note
            for (pcm in listOf(finite, held.snip.samples)) {
                assertTrue(pcm.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(pcm.any { abs(it) > .01f })
            }
            assertTrue(Keys.seamError(held.snip.samples, held.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
        }
    }
}
