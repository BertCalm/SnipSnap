package com.snipsnap.shell

import com.snipsnap.synth.Bore
import com.snipsnap.synth.BorePatch
import com.snipsnap.synth.BoreVoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OVERBLOW is a one-shot's gesture: a crack to the upper register at a moment of the note
 * (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "R2: OVERBLOW, as built"). A keys
 * instrument holds its notes, so it must never crack, or a zone would play an octave off its key.
 * [BorePadMaker] builds BORE zones from the held note the LOOP is cut from (`Bore.heldNote`), which plays
 * the knob as 0, so a cracked patch makes exactly the instrument the same patch makes uncracked, zones and
 * samples alike, both layers. (Until 2026-11-06 BORE's keys came from [LoopPadMaker]; this test moved with them.)
 */
class BoreOverblowKeysTest {

    private val plain = BorePatch("STEADY", BoreVoice.FLUTE, Bore.defaults(BoreVoice.FLUTE))
    private val cracked = plain.withMacros(plain.macros + ("OVERBLOW" to 1f))

    @Test
    fun `a cracked FLUTE makes the same keys instrument as an uncracked one, every zone on its key`() {
        assertTrue(BorePadMaker.supports(cracked), "OVERBLOW does not take a FLUTE out of the keys maker")
        val a = BorePadMaker.spec(plain, .5f)
        val b = BorePadMaker.spec(cracked, .5f)
        assertEquals(BorePadMaker.zoneMidis(a), BorePadMaker.zoneMidis(b), "the zones follow the voice, not the crack")
        val midi = BorePadMaker.zoneMidis(a).first()
        val za = BorePadMaker.renderZone(a, midi)
        val zb = BorePadMaker.renderZone(b, midi)
        assertEquals(za.size, zb.size)
        for (layer in za.indices) {
            assertContentEquals(za[layer].snip.samples, zb[layer].snip.samples, "the zone at $midi, layer $layer")
            assertEquals(za[layer].loopStartFrame, zb[layer].loopStartFrame, "the zone at $midi, layer $layer")
        }
    }
}
