package com.snipsnap.shell

import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.Keys
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ARCO's BUZZ (R2) through the held instrument. Until 2026-11-06 ARCO's keys came from [LoopPadMaker] (the loop at HOLD's top step, no
 * attack), and this test held every zone of a BUZZ 1 patch to that maker's join check. ARCO now has its own maker, [ArcoPadMaker], whose
 * keys begin with the bow's stroke and sustain on the same loop, so [LoopPadMaker] no longer offers it; this test follows BUZZ there.
 * A BUZZ 1 patch's middle zone in each voice, both layers, must render, close under the Organ's bar at its own loop start (the engine
 * refuses by name a zone that does not, after one retry at twice the warm-up), pass [LoopPadMaker]'s join reading on its loop, and
 * carry the buzz (it differs from the same zone at BUZZ 0). ArcoKeysTest closes BUZZ 1 at each voice's lowest, middle and top zone.
 */
class ArcoBuzzLoopPadTest {

    @Test
    fun `the loop-only maker no longer offers ARCO, and the held maker does`() {
        assertFalse(LoopPadMaker.offers(ArcoPatch.ENGINE), "ARCO's keys come from ArcoPadMaker now")
        for (voice in ArcoVoice.entries) {
            val patch = ArcoPatch("Rattle", voice, Arco.defaults(voice) + ("BUZZ" to 1f))
            assertFalse(LoopPadMaker.supports(patch), "$voice: the loop instrument still offers ARCO")
            assertTrue(ArcoPadMaker.supports(patch), "$voice: the held instrument does not offer a BUZZ patch")
        }
    }

    @Test
    fun `a BUZZ 1 patch's middle zone renders through the held instrument with a closed seam, and carries the buzz`() {
        for (voice in ArcoVoice.entries) {
            val spec = ArcoPadMaker.spec(ArcoPatch("Rattle", voice, Arco.defaults(voice) + ("BUZZ" to 1f)), 0.5f)
            val plain = ArcoPadMaker.spec(ArcoPatch("Plain", voice, Arco.defaults(voice)), 0.5f)
            val midis = ArcoPadMaker.zoneMidis(spec)
            val midi = midis[midis.size / 2]
            val (buzzing, bare) = listOf(spec, plain).parallelStream().map { ArcoPadMaker.renderZone(it, midi) }.toList()
            val rows = buzzing.indices.map { layer ->
                val note = buzzing[layer]
                val s = note.snip.samples
                val start = note.loopStartFrame.toInt()
                val seam = Keys.seamError(s, start)
                val ratio = LoopPadMaker.wrapRatio(s.copyOfRange(start, s.size))
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice midi $midi layer $layer: seam $seam")
                assertTrue(ratio <= LoopPadMaker.MAX_WRAP_RATIO, "$voice midi $midi layer $layer: the join reads $ratio")
                assertTrue(!s.contentEquals(bare[layer].snip.samples), "$voice midi $midi layer $layer: the BUZZ 1 zone equals the BUZZ 0 zone")
                "layer ${layer + 1} seam %.2e, join %.2f".format(java.util.Locale.ROOT, seam, ratio)
            }
            println("ARCO BUZZ 1 through the held instrument $voice midi $midi: ${rows.joinToString("; ")} (bars ${Keys.MAX_SEAM_ERROR} and ${LoopPadMaker.MAX_WRAP_RATIO})")
        }
    }
}
