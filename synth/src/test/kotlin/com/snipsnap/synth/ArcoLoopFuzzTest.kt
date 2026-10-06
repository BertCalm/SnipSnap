package com.snipsnap.synth

import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every note of the knob, not three of them: `Arco.renderLoop` throws when a loop cannot close (a click shipped silently is
 * the worse failure), so a TUNE step or a GRIP, BOW or BODY corner the grid in [ArcoProductTest] happened not to visit would be
 * a pad build that crashes. This visits all 25 TUNE steps of CELLO and all 20 of ERHU, once at the default knobs, once at a
 * seeded random GRIP, BOW and BODY (a third of the draws land exactly on an end, since the ends are where a window has
 * its edges; a sixth land on BODY 1 and about a third more between 0.5 and 1, where R1g's lift runs on the stretch before the seam is read), once at
 * BODY 0.75 and once at BODY 1 with the other knobs at their defaults, at every step whatever the dice said: the two stops of the lift (3.5 and 6.0 dB per element, R1g), the second of them the loudest the knob goes. Each loop is held to the Organ's real bar
 * (`Keys.MAX_SEAM_ERROR`), not the half of it the claims test uses,
 * and to a finished wave: every sample finite and in -1..1. `BoreLoopFuzzTest`'s own idea, at ARCO's size, and `ResinHeldFuzzTest`'s
 * way of spending it (a seeded draw made up front, then the cases run in parallel, so a failure reproduces).
 *
 * R1b's own sweep of 225 loops (every step of both voices at the default and four other corners: GRIP 0, GRIP 1, BODY 0,
 * BODY 1) found the worst seam at 2.5e-4 against the bar of 1e-3, rendering 0.3 to 1.0 s a loop. The worst of this file's loops is printed
 * per voice, and the worst of each of the rows beside it. R1c saw, CELLO then ERHU: the defaults row 1.68e-4 (step 7) and 1.20e-5, the random row 2.44e-4 (step 2)
 * and 8.51e-5, and the BODY 1 row 6.64e-5 (step 2) and 9.72e-6 (with R1c's climbed box, which R1g retired), a quarter of the bar at the worst, which is
 * R1b's own worst. The warm-up is what keeps it there: a 1.0 s, 100-period warm-up left ten GRIP 1 loops at C#2 to B2 over the bar (up to
 * 2e-2); the engine's 2.0 s and 200 periods close them all. R1g saw the two new rows close well under the bar, CELLO then ERHU: the BODY 0.75 row 1.09e-04 (step 7) and 8.25e-06 (step 2), the BODY 1 row 1.00e-04 (step 7) and 6.82e-06 (step 2) (R1f's design
 * predicted a worst of about 1e-4 and 7.3e-6), the random row 2.37e-04 (step 2) and 8.51e-05; the highest finished peak of the rows at BODY 0.75 and 1 is 0.940 (CELLO, the lift's cap) and 0.859 (ERHU), the shortest loop 2.000 s. The bar is the same one at every row (1e-3).
 * R1g's review (T3) added the peak and halving bounds, which cost nothing (the peaks were already computed): every BODY 0.75 and BODY 1 loop peaks at most `max(Arco.PEAK_CAP, the same note's default-knob loop peak)` plus 1e-6 and under the 0.95 bar, no loop's lift was halved (0 halvings is how a peak that was not monotone in the lift would show), and where the cap acted the loop peaks within 0.01 of that limit (so the cap did not take more than it had to). R1g saw
 * the highest lifted loop peak at 0.940 (CELLO, the cap acting in 4 loops at BODY 0.75 and 9 at BODY 1, steps 0 to 8) and 0.859 (ERHU, the cap in none), the default-knob loops' highest peaks at 0.676 and 0.433, and 0 halvings in all 180 loops. The control that the bound can fail: the cap acted in at least one CELLO loop at BODY 1, so the bound was not met by a lift that never reached it.
 * Cost: a row of 25 or 20 loops sums to 8 to 10 s of render time (about 2.5 s on four cores), so the two lift rows added well under a minute and not the 3 minutes at which the design would have cut the grid, which stays all 45 steps. The control that the new rows are rows of
 * the lift and not of the plain: at the root, middle and top step of each voice the BODY 0.75 and BODY 1 loops are louder in RMS than the BODY 0.5 loop, by more than 5 percent (the lift adds about 3.5 and 6 dB per element, +3 dB or more in RMS even where the cap holds it
 * back at C2), so a branch that ignored BODY above the knee fails it.
 */
class ArcoLoopFuzzTest {

    private fun knob(r: Random): Float = when (r.nextInt(6)) {
        0 -> 0f
        1 -> 1f
        else -> r.nextFloat()
    }

    private class Case(val voice: ArcoVoice, val step: Int, val corner: String, val macros: Map<String, Float>)

    private class Outcome(val case: Case, val seam: Double, val frames: Int, val finite: Boolean, val peak: Float, val millis: Long, val halvings: Int, val capActed: Boolean)

    @Test
    fun `every note of both voices closes as a loop, at the defaults, at a random corner, at BODY 0 point 75 and at BODY 1`() {
        val random = Random(20261001)
        val cases = ArcoVoice.entries.flatMap { voice ->
            (0..Arco.tuneSemitones(voice)).flatMap { step ->
                val tune = step / Arco.tuneSemitones(voice).toFloat()
                val random1 = Arco.defaults(voice) + mapOf("GRIP" to knob(random), "BOW" to knob(random), "BODY" to knob(random))
                listOf(
                    "defaults" to Arco.defaults(voice),
                    "random" to random1,
                    "BODY 0.75" to Arco.defaults(voice) + mapOf("BODY" to 0.75f),
                    "BODY 1" to Arco.defaults(voice) + mapOf("BODY" to 1f),
                ).map { (corner, base) ->
                    Case(voice, step, corner, base + mapOf("TUNE" to tune, "HOLD" to 1f))
                }
            }
        }
        val outcomes = cases.parallelStream().map { c ->
            val started = System.nanoTime()
            val r = Arco.renderLoopMeasured(c.voice, c.macros)
            Outcome(c, r.seam, r.loop.size, r.loop.all { it.isFinite() && it in -1f..1f }, r.loop.maxOf { kotlin.math.abs(it) }, (System.nanoTime() - started) / 1_000_000L, r.lift.halvings, r.lift.delivered < r.lift.asked)
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
        for (voice in ArcoVoice.entries) for (corner in listOf("defaults", "random", "BODY 0.75", "BODY 1")) {
            val row = outcomes.filter { it.case.voice == voice && it.case.corner == corner }
            val worst = row.maxBy { it.seam }
            println(
                "ARCO loop fuzz $voice $corner: ${row.size} loops, worst seam ${"%.2e".format(java.util.Locale.ROOT, worst.seam)} at step ${worst.case.step}, " +
                    "highest peak ${"%.3f".format(java.util.Locale.ROOT, row.maxOf { it.peak })}, the lift's cap acted in ${row.count { it.capActed }}, halved in ${row.count { it.halvings != 0 }}, " +
                    "render time ${row.sumOf { it.millis }} ms summed over the row (${row.maxOf { it.millis }} ms the slowest)",
            )
        }
        // the default-knob loop of each note is the plain's (BODY 0.5, the knee): what the lifted loops are held to when the plain is itself the hotter of the two.
        val plainPeak = outcomes.filter { it.case.corner == "defaults" }.associate { (it.case.voice to it.case.step) to it.peak }
        for (o in outcomes) {
            val where = "${o.case.voice} step ${o.case.step} ${o.case.macros}"
            assertTrue(o.seam < Keys.MAX_SEAM_ERROR, "$where: the loop does not close (seam ${o.seam})")
            assertTrue(o.finite, "$where: the loop is not finite or clips")
            assertTrue(o.frames >= 2 * Dsp.RATE, "$where: the loop is ${o.frames} frames, under 2 s")
            assertEquals(0, o.halvings, "$where: the loop's lift was halved ${o.halvings} times, so its peak is not monotone in the lift")
            if (o.case.corner == "BODY 0.75" || o.case.corner == "BODY 1") {
                val plain = plainPeak.getValue(o.case.voice to o.case.step)
                val limit = max(Arco.PEAK_CAP, plain)
                assertTrue(o.peak <= limit + 1e-6f, "$where: the lifted loop peaks at ${o.peak}, over the cap ${Arco.PEAK_CAP} (or the plain loop's own $plain if higher)")
                assertTrue(o.peak < Arco.FINISHED_PEAK_BAR, "$where: the lifted loop peaks at ${o.peak}, not under the finished-peak bar ${Arco.FINISHED_PEAK_BAR}")
                if (o.capActed) assertTrue(o.peak > limit - 0.01f, "$where: the cap acted but the loop peaks at ${o.peak}, well under the limit $limit, so the cap took more than it had to")
            }
        }
        // the control that the peak bound above can fail: the cap acts on the loops it is there for (the lowest CELLO notes at BODY 1), so the bound is not met by a lift that never reached it.
        val capped = outcomes.filter { it.case.voice == ArcoVoice.CELLO && it.case.corner == "BODY 1" && it.capActed }
        println("ARCO loop fuzz cap control: the cap acted in ${capped.size} of ${outcomes.count { it.case.voice == ArcoVoice.CELLO && it.case.corner == "BODY 1" }} CELLO loops at BODY 1 (steps ${capped.map { it.case.step }})")
        assertTrue(capped.isNotEmpty(), "control: the cap acted in no CELLO loop at BODY 1, so the loop peak bound never saw the cap work")
    }

    /**
     * The control for the rows above: a loop at BODY 0.75 and at BODY 1 carries the lift, so it is louder in RMS than the same loop at BODY 0.5 (the plain), at the root, middle and top step of each voice (CELLO steps 0, 12 and 24: C2, C3, C4; ERHU steps 0, 9 and 19:
     * D4, B4, A5), by more than 5 percent (0.42 dB). R1g saw the rise on its `ARCO loop fuzz lift` lines: CELLO +3.24 dB at C2 at both BODYs (the cap holds the lift back there, so the two loops are the same samples), +3.77 and +6.55 dB at C3, +3.57 and +6.33 at C4; ERHU
     * +3.76 and +6.50 at D4, +3.70 and +6.44 at B4, +3.49 and +6.16 at A5. The bound is 1.05 (0.42 dB), about an eighth of the smallest of those. A branch that ignored BODY above the knee reads 1.00 and fails it.
     */
    @Test
    fun `a loop above the knee is louder than the plain's loop, so the BODY 0 point 75 and BODY 1 rows are rows of the lift`() {
        fun rms(x: FloatArray): Double = kotlin.math.sqrt(x.sumOf { it.toDouble() * it } / x.size)
        for (voice in ArcoVoice.entries) {
            val top = Arco.tuneSemitones(voice)
            for (step in listOf(0, top / 2, top)) {
                val base = Arco.defaults(voice) + mapOf("TUNE" to step / top.toFloat(), "HOLD" to 1f)
                val plain = rms(Arco.renderLoopMeasured(voice, base + ("BODY" to 0.5f)).loop)
                for (body in listOf(0.75f, 1f)) {
                    val lifted = rms(Arco.renderLoopMeasured(voice, base + ("BODY" to body)).loop)
                    val ratio = lifted / plain
                    println("ARCO loop fuzz lift $voice step $step BODY $body: loop RMS ${"%.4f".format(java.util.Locale.ROOT, lifted)} against the plain's ${"%.4f".format(java.util.Locale.ROOT, plain)}, ${"%.2f".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(ratio))} dB")
                    assertTrue(ratio > 1.05, "$voice step $step BODY $body: the loop is not louder than the plain's (ratio $ratio): the lift is not in it")
                }
            }
        }
    }
}
