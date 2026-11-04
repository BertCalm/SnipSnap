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
 * [LoopPadMaker] builds BORE FLUTE zones from the patch at HOLD's top step, moved to each zone's note by
 * [SynthRoot]; the engine's LOOP plays a held note without the knob, so a cracked patch makes exactly the
 * instrument the same patch makes uncracked, root and samples alike.
 */
class BoreOverblowKeysTest {

    private val plain = BorePatch("STEADY", BoreVoice.FLUTE, Bore.defaults(BoreVoice.FLUTE))
    private val cracked = plain.withMacros(plain.macros + ("OVERBLOW" to 1f))

    @Test
    fun `a cracked FLUTE makes the same keys instrument as an uncracked one, every zone on its key`() {
        assertTrue(LoopPadMaker.supports(cracked), "OVERBLOW does not take a FLUTE out of the keys maker")
        val a = LoopPadMaker.spec(plain, .5f)
        val b = LoopPadMaker.spec(cracked, .5f)
        assertEquals(LoopPadMaker.zoneMidis(a), LoopPadMaker.zoneMidis(b), "the roots follow TUNE, not the crack")
        assertEquals(SynthRoot.of(LoopPadMaker.held(plain))!!.midi, SynthRoot.of(LoopPadMaker.held(cracked))!!.midi)
        val midi = LoopPadMaker.zoneMidis(a).first()
        assertContentEquals(LoopPadMaker.renderZone(a, midi).snip.samples, LoopPadMaker.renderZone(b, midi).snip.samples, "the zone at $midi")
    }
}
