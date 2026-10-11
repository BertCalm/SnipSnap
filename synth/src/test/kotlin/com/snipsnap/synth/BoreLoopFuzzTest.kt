package com.snipsnap.synth

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every note of the knob, not three of them: a LOOP is cut from the held middle of a long one-shot and closed by a blend
 * ([Bore.renderLoopMeasured]), and a note the grid in [BoreTest] happened not to visit could be one whose held note never
 * settles, or whose two blended waves do not match, which would be a pad build that crashes ([Bore.renderLoop] refuses
 * it by name). This visits all 25 TUNE steps of both voices, once at the default knobs and once at a seeded random LIP,
 * BREATH and CHIFF, and holds each join to the same bounds [Bore.renderLoop] refuses at. `ResinHeldFuzzTest`'s own idea, at
 * BORE's size.
 */
class BoreLoopFuzzTest {

    @Test
    fun `every note of both voices renders as a loop whose join holds, at the defaults and at a random corner`() {
        val random = Random(20260929)
        var worstCorrelation = 1.0
        var worstDip = 0.0
        var worstWrap = 0.0
        for (voice in BoreVoice.entries) for (step in 0..Bore.TUNE_SEMITONES) {
            val tune = step / Bore.TUNE_SEMITONES.toFloat()
            val corners = listOf(
                Bore.defaults(voice),
                Bore.defaults(voice) + mapOf("LIP" to random.nextFloat(), "BREATH" to random.nextFloat(), "CHIFF" to random.nextFloat()),
            )
            for (base in corners) {
                val macros = base + mapOf("TUNE" to tune, "HOLD" to 1f)
                val r = Bore.renderLoopMeasured(voice, macros)
                val j = r.join
                val where = "$voice step $step $macros"
                worstCorrelation = minOf(worstCorrelation, j.correlation)
                worstDip = minOf(worstDip, j.blendDipDb)
                worstWrap = maxOf(worstWrap, j.wrapStep / j.steepestStep)
                assertTrue(j.correlation >= Bore.LOOP_MIN_CORRELATION, "$where: the blended waves correlate only ${j.correlation}")
                assertTrue(j.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "$where: the loop dips ${j.blendDipDb} dB inside its blend")
                assertTrue(j.wrapStep <= WRAP_OVER_STEEPEST * j.steepestStep, "$where: the step across the wrap is ${j.wrapStep / j.steepestStep} times the steepest ordinary step")
                assertTrue(r.loop.all { it.isFinite() && it in -1f..1f }, "$where: the loop clips or is not finite")
            }
        }
        println("BORE loop fuzz: 100 loops, worst correlation %.4f, deepest dip %.2f dB, widest wrap %.2fx the steepest step".format(java.util.Locale.ROOT, worstCorrelation, worstDip, worstWrap))
    }

    private companion object {
        /** A wrap step is a sample step: it must be no larger than the loop's own steepest ordinary one. */
        const val WRAP_OVER_STEEPEST = 1.0
    }
}
