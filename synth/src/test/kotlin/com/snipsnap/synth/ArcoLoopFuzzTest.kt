package com.snipsnap.synth

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every note of the knob, not three of them: `Arco.renderLoop` throws when a loop cannot close (a click shipped silently is
 * the worse failure), so a TUNE step or a GRIP, BOW or BODY corner the grid in [ArcoProductTest] happened not to visit would be
 * a pad build that crashes. This visits all 25 TUNE steps of CELLO and all 20 of ERHU, once at the default knobs and once at a
 * seeded random GRIP, BOW and BODY (a third of the draws land exactly on an end, since the ends are where a window has
 * its edges), and holds each loop to the Organ's real bar (`Keys.MAX_SEAM_ERROR`), not the half of it the claims test uses,
 * and to a finished wave: every sample finite and in -1..1. `BoreLoopFuzzTest`'s own idea, at ARCO's size, and `ResinHeldFuzzTest`'s
 * way of spending it (a seeded draw made up front, then the cases run in parallel, so a failure reproduces).
 *
 * R1b's own sweep of 225 loops (every step of both voices at the default and four other corners: GRIP 0, GRIP 1, BODY 0,
 * BODY 1) found the worst seam at 2.5e-4 against the bar of 1e-3, rendering 0.3 to 1.0 s a loop. The worst of this file's 90 is printed
 * per voice. The warm-up is what keeps it there: a 1.0 s, 100-period warm-up left ten GRIP 1 loops at C#2 to B2 over the bar (up to
 * 2e-2); the engine's 2.0 s and 200 periods close them all.
 */
class ArcoLoopFuzzTest {

    private fun knob(r: Random): Float = when (r.nextInt(6)) {
        0 -> 0f
        1 -> 1f
        else -> r.nextFloat()
    }

    private class Case(val voice: ArcoVoice, val step: Int, val corner: String, val macros: Map<String, Float>)

    private class Outcome(val case: Case, val seam: Double, val frames: Int, val finite: Boolean, val peak: Float)

    @Test
    fun `every note of both voices closes as a loop, at the defaults and at a random corner`() {
        val random = Random(20261001)
        val cases = ArcoVoice.entries.flatMap { voice ->
            (0..Arco.tuneSemitones(voice)).flatMap { step ->
                val tune = step / Arco.tuneSemitones(voice).toFloat()
                val random1 = Arco.defaults(voice) + mapOf("GRIP" to knob(random), "BOW" to knob(random), "BODY" to knob(random))
                listOf("defaults" to Arco.defaults(voice), "random" to random1).map { (corner, base) ->
                    Case(voice, step, corner, base + mapOf("TUNE" to tune, "HOLD" to 1f))
                }
            }
        }
        val outcomes = cases.parallelStream().map { c ->
            val r = Arco.renderLoopMeasured(c.voice, c.macros)
            Outcome(c, r.seam, r.loop.size, r.loop.all { it.isFinite() && it in -1f..1f }, r.loop.maxOf { kotlin.math.abs(it) })
        }.toList()
        for (voice in ArcoVoice.entries) {
            val mine = outcomes.filter { it.case.voice == voice }
            val worst = mine.maxBy { it.seam }
            println(
                "ARCO loop fuzz $voice: ${mine.size} loops, worst seam ${"%.2e".format(java.util.Locale.ROOT, worst.seam)} " +
                    "at step ${worst.case.step} (${worst.case.corner}) (bar ${"%.0e".format(java.util.Locale.ROOT, Keys.MAX_SEAM_ERROR)}), " +
                    "shortest loop ${"%.3f".format(java.util.Locale.ROOT, mine.minOf { it.frames }.toDouble() / Dsp.RATE)} s, highest peak ${"%.3f".format(java.util.Locale.ROOT, mine.maxOf { it.peak })}",
            )
        }
        for (o in outcomes) {
            val where = "${o.case.voice} step ${o.case.step} ${o.case.macros}"
            assertTrue(o.seam < Keys.MAX_SEAM_ERROR, "$where: the loop does not close (seam ${o.seam})")
            assertTrue(o.finite, "$where: the loop is not finite or clips")
            assertTrue(o.frames >= 2 * Dsp.RATE, "$where: the loop is ${o.frames} frames, under 2 s")
        }
    }
}
