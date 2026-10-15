package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OverlapTest {
    private val plain = mapOf("KNOT" to 1f, "PULSE" to 0f, "LOCK" to 0f,
        "GLOW" to 0f, "RING" to .65f, "HOLD" to 0f)

    @Test
    fun `serialized pad recipe and edits preserve the actual sound and note`() {
        val patch = OverlapPatch("Saved network", OverlapTopology.TREFOIL, plain, 57, .65f)
        val recipe = PadRecipe(patch = patch)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(patch, restored.patch)
        assertContentEquals(recipe.render().samples, restored.render().samples)
        val edited = Patches.edited(patch, "Edited network", plain + ("GLOW" to .4f)) as OverlapPatch
        assertEquals(57, edited.midi)
        assertEquals(.65f, edited.velocity)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("GLOW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(velocity = Float.POSITIVE_INFINITY) }
        assertTrue(patch.copy(velocity = 0f).render().samples.all { it == 0f })
    }

    @Test
    fun `zero routing depth produces the identical self network for every topology`() {
        val expected = Overlap.probe(OverlapTopology.SELF, plain + ("KNOT" to 0f), seconds = 2.0)
        for (topology in OverlapTopology.entries) {
            val actual = Overlap.probe(topology, plain + ("KNOT" to 0f), seconds = 2.0)
            assertContentEquals(expected.raw, actual.raw, "$topology changes the zero-depth network")
        }
    }

    @Test
    fun `raw impulse responses decay without limiting at extreme routing and glow settings`() {
        for (topology in OverlapTopology.entries) for (depth in listOf(0f, .5f, 1f)) {
            val p = Overlap.probe(topology, plain + mapOf("KNOT" to depth, "RING" to 1f, "GLOW" to 1f), seconds = 4.0)
            assertEquals(44_100, p.sampleRate)
            assertTrue(p.raw.all { it.isFinite() }, "$topology $depth became nonfinite")
            assertTrue(p.diagnostics.maxLoopGain < 1.0, "$topology $depth can expand feedback")
            assertTrue(p.diagnostics.maxBufferAbs < 1.0, "$topology $depth grew internally")
            assertTrue(p.diagnostics.rawPeak in .000001.. .99, "$topology $depth raw output clips or is silent")
            assertTrue(p.diagnostics.lateRms < p.diagnostics.earlyRms, "$topology $depth impulse response does not decay")
        }
    }

    @Test
    fun `routing endpoints carry the requested root at two registers`() {
        for (topology in OverlapTopology.entries) for (midi in listOf(48, 60)) {
            val p = Overlap.probe(topology, plain, midi = midi, seconds = 2.0)
            val estimate = assertNotNull(Pitch.detect(Snip(p.raw, 1, p.sampleRate), .10f, .30f), "$topology MIDI $midi has no detectable note")
            val cents = 1200.0 * ln(estimate.hz / p.diagnostics.targetHz) / ln(2.0)
            assertTrue(abs(cents) < 50.0, "$topology MIDI $midi root differs by $cents cents (${estimate.hz} Hz)")
        }
    }

    @Test
    fun `lock synchronizes the slow phases while glow leaves network state intact`() {
        val macros = plain + mapOf("PULSE" to .55f, "HOLD" to .8f, "RING" to .85f)
        val free = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 0f), seconds = 6.0)
        val locked = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 1f), seconds = 6.0)
        assertTrue(locked.diagnostics.phaseOrderEnd > .8, "locked phases did not synchronize")
        assertTrue(locked.diagnostics.phaseOrderEnd > free.diagnostics.phaseOrderEnd + .2, "LOCK does not change synchronization")
        val glowing = Overlap.probe(OverlapTopology.TREFOIL, macros + mapOf("LOCK" to 1f, "GLOW" to .7f), seconds = 6.0)
        assertEquals(locked.diagnostics.delaySamples, glowing.diagnostics.delaySamples)
        assertEquals(locked.diagnostics.phaseOrderHistory, glowing.diagnostics.phaseOrderHistory)
        assertEquals(locked.diagnostics.maxBufferAbs, glowing.diagnostics.maxBufferAbs)
        assertTrue(!locked.raw.contentEquals(glowing.raw), "GLOW does not alter the output")
    }
}
