package com.snipsnap.synth

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every note of the knob, not three of them: [Bore.renderLoop] throws when a loop cannot close
 * (a click shipped silently is the worse failure), so a TUNE step or a LIP/BREATH corner the
 * grid in [BoreTest] happened not to visit would be a pad build that crashes. This visits all
 * 25 TUNE steps of both voices, once at the default knobs and once at a seeded random LIP,
 * BREATH and CHIFF, and holds each to the Organ's real bar (`Keys.MAX_SEAM_ERROR`), not the
 * quarter of it the claims test uses. `ResinHeldFuzzTest`'s own idea, at BORE's size.
 */
class BoreLoopFuzzTest {

    @Test
    fun `every note of both voices closes as a loop, at the defaults and at a random corner`() {
        val random = Random(20260929)
        var worst = 0.0
        for (voice in BoreVoice.entries) for (step in 0..Bore.TUNE_SEMITONES) {
            val tune = step / Bore.TUNE_SEMITONES.toFloat()
            val corners = listOf(
                Bore.defaults(voice),
                Bore.defaults(voice) + mapOf("LIP" to random.nextFloat(), "BREATH" to random.nextFloat(), "CHIFF" to random.nextFloat()),
            )
            for (base in corners) {
                val macros = base + mapOf("TUNE" to tune, "HOLD" to 1f)
                val r = Bore.renderLoopMeasured(voice, macros)
                worst = maxOf(worst, r.seam)
                assertTrue(r.seam < Keys.MAX_SEAM_ERROR, "$voice step $step $macros: the loop does not close (seam ${r.seam})")
                assertTrue(r.loop.all { it.isFinite() && it in -1f..1f }, "$voice step $step: the loop clips or is not finite")
            }
        }
        println("BORE loop fuzz: 100 loops, worst seam %.2e (bar %.0e)".format(worst, Keys.MAX_SEAM_ERROR))
    }
}
