package com.snipsnap.shell

import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoVoice
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ARCO's BUZZ (R2) through the shared loop instrument: [LoopPadMaker] offers ARCO CELLO and ERHU and renders each zone at HOLD's top step,
 * so a patch saved with BUZZ on reaches it. Every zone of a BUZZ 1 patch in each voice must render (the engine's LOOP refuses by name a loop
 * that does not close under the Organ's bar), must pass [LoopPadMaker]'s own join check, and must carry the buzz (it differs from the same
 * zone at BUZZ 0). The maker is not changed: this only reads what it does with the macro.
 */
class ArcoBuzzLoopPadTest {

    @Test
    fun `every zone of a BUZZ 1 patch renders through the loop instrument, with a clean join`() {
        for (voice in ArcoVoice.entries) {
            val patch = ArcoPatch("Rattle", voice, Arco.defaults(voice) + ("BUZZ" to 1f))
            val plain = ArcoPatch("Plain", voice, Arco.defaults(voice))
            assertTrue(LoopPadMaker.supports(patch), "$voice: the loop instrument does not offer a BUZZ patch")
            val spec = LoopPadMaker.spec(patch, 0.5f)
            val plainSpec = LoopPadMaker.spec(plain, 0.5f)
            val midis = LoopPadMaker.zoneMidis(spec)
            assertTrue(midis.size >= 3, "$voice: ${midis.size} zones")
            val ratios = midis.map { midi ->
                val note = LoopPadMaker.renderZone(spec, midi)
                val loop = note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.samples.size)
                val ratio = LoopPadMaker.wrapRatio(loop)
                assertTrue(ratio <= LoopPadMaker.MAX_WRAP_RATIO, "$voice midi $midi: the join reads $ratio")
                val bare = LoopPadMaker.renderZone(plainSpec, midi)
                assertTrue(!note.snip.samples.contentEquals(bare.snip.samples), "$voice midi $midi: the BUZZ 1 zone equals the BUZZ 0 zone")
                ratio
            }
            println("ARCO BUZZ 1 through the loop instrument $voice: ${midis.size} zones (midi ${midis.first()} to ${midis.last()}), join ratios ${ratios.joinToString(" ") { "%.2f".format(java.util.Locale.ROOT, it) }} (bar ${LoopPadMaker.MAX_WRAP_RATIO})")
        }
    }
}
