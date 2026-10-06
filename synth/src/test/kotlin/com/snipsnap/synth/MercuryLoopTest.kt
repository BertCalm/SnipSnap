package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MERCURY's LOOP (R2): HOLD's top step renders the held rub as one seamless loop. These are its claims; what it
 * sounds like after eight wraps is the audition's question.
 */
class MercuryLoopTest {

    private val rate = Dsp.RATE

    private fun f(x: Double) = "%.1e".format(java.util.Locale.ROOT, x)

    /**
     * The spec's R2 gate: the seam under the house bar ([Keys.MAX_SEAM_ERROR]) over every voice at the bottom, middle
     * and top of TUNE, at the defaults and at each corner that pulls hardest on the loop. The defaults close as asked
     * (no nudge). The hard corner (low GLASS, COUPLE 1, WATER 1) is where the rub can sustain two resonances at once;
     * it closes after the nudge, and at the bottom of the range it needs one. Measured in R2's probe: 312 of 312
     * preset × TUNE loops and every default closed as asked, and 31 of 315 random corners needed the nudge.
     */
    @Test
    fun `every voice closes at the bottom, middle and top, the hard corner after a nudge`() {
        val corners = listOf(
            "defaults" to emptyMap(),
            "WATER 1" to mapOf("WATER" to 1f),
            "GLASS 0" to mapOf("GLASS" to 0f),
            "COUPLE 1" to mapOf("COUPLE" to 1f),
            "hard" to mapOf("GLASS" to 0.1f, "COUPLE" to 1f, "WATER" to 1f),
        )
        val cases = MercuryVoice.entries.flatMap { v -> listOf(0f, 0.5f, 1f).map { v to it } }
        // Each case is independent and a render is seconds of audio, so they spread over the cores; the claims are
        // asserted in the parallel body, whose first failure rethrows here.
        val lines = cases.parallelStream().map { (voice, tune) ->
            val line = corners.map { (name, c) ->
                val r = Mercury.renderLoopNudged(voice, Mercury.defaults(voice) + c + ("TUNE" to tune))
                assertTrue(r.seam < Keys.MAX_SEAM_ERROR, "$voice $tune $name: seam ${f(r.seam)}")
                if (name == "defaults") assertEquals(0f, r.nudge, "$voice $tune: the defaults were nudged")
                "$name ${f(r.seam)}${if (r.nudge > 0f) " nudged ${r.nudge}" else ""}"
            }
            "MERCURY LOOP $voice $tune: " + line.joinToString(", ")
        }.toList()
        lines.forEach(::println)
        val bottom = Mercury.renderLoopNudged(MercuryVoice.SING, Mercury.defaults(MercuryVoice.SING) + mapOf("TUNE" to 0f, "GLASS" to 0.1f, "COUPLE" to 1f, "WATER" to 1f))
        assertTrue(bottom.nudge > 0f, "the hard corner at the bottom of SING closed without a nudge; the ladder is untested")
    }

    /**
     * R2c's gate on the keys: each new voice's defaults close as asked, with no nudge, at every one of the 25 TUNE steps
     * (the keys of its held instrument). EDDY's friction is chaotic from key to key (its first tuning failed at C4
     * and G4 only), and VESSEL's failures were at the four lowest keys, so three keys cannot stand for them. The
     * three older voices' defaults were swept in R2a; their renders are unchanged to the bit.
     */
    @Test
    fun `the new voices' defaults close un-nudged at every key`() {
        val keys = listOf(MercuryVoice.EDDY, MercuryVoice.VESSEL, MercuryVoice.SHARD).flatMap { v -> (0..Mercury.TUNE_SEMITONES).map { v to it } }
        val worst = HashMap<MercuryVoice, Double>()
        val rows = keys.parallelStream().map { (voice, step) ->
            val r = Mercury.renderLoopNudged(voice, Mercury.defaults(voice) + ("TUNE" to step / Mercury.TUNE_SEMITONES.toFloat()))
            assertTrue(r.seam < Keys.MAX_SEAM_ERROR, "$voice step $step: seam ${f(r.seam)}")
            assertEquals(0f, r.nudge, "$voice step $step: the defaults needed a nudge")
            Triple(voice, step, r.seam)
        }.toList()
        for ((voice, _, seam) in rows) worst.merge(voice, seam) { a, b -> maxOf(a, b) }
        println("MERCURY LOOP defaults, 25 keys each, worst seam: " + worst.entries.joinToString(", ") { "${it.key} ${f(it.value)}" })
    }

    /** HOLD's top step is the LOOP: render gives the loop itself, it is filed LOOP, and below the step a one-shot is unchanged. */
    @Test
    fun `HOLD's top step renders the loop and files it LOOP`() {
        for (voice in MercuryVoice.entries) {
            val m = Mercury.defaults(voice) + ("HOLD" to 1f)
            assertTrue(Mercury.isLoop(1f) && Mercury.isLoop(Mercury.LOOP_THRESHOLD) && !Mercury.isLoop(0.98f))
            val snip = Mercury.render(voice, m)
            assertContentEquals(Mercury.renderLoop(voice, m), snip.samples, "$voice: render at HOLD 1 is not the loop")
            assertEquals(DrumClass.LOOP, Mercury.drumClassFor(voice, m))
            val plan = Mercury.planLoop(Mercury.frequencyFor(voice, 0.5f).toDouble(), m.getValue("WATER").toDouble())
            assertEquals(plan.frames, snip.frameCount, "$voice: the loop is not its plan's length")
            assertTrue(snip.samples.all { it.isFinite() }, "$voice: not finite")
        }
    }

    /**
     * The plan: an even number of frames (so the 8-sample control grid lands on the same samples every loop), whole
     * periods of the note within 0.1 cent of it, and whole orbits of the WATER mass within 5% of its rate, at least
     * [Mercury.LOOP_SECONDS] long and lengthened, not zeroed, for a slow orbit.
     */
    @Test
    fun `a loop is whole frames, whole periods and whole orbits`() {
        for (water in listOf(0.0, 0.05, 0.15, 0.5, 1.0)) for (hz in listOf(130.8, 392.0, 1046.5)) {
            val p = Mercury.planLoop(hz, water)
            assertEquals(0, p.frames % 2, "frames must be even")
            assertTrue(p.frames >= Mercury.LOOP_SECONDS * rate - 2, "loop too short: ${p.frames}")
            if (water > 0.0) {
                val orbits = p.orbitHz * p.frames / rate
                assertTrue(abs(orbits - Math.round(orbits)) < 1e-9 && orbits >= 1, "WATER $water: $orbits orbits")
                assertTrue(abs(p.orbitHz / Mercury.orbitHz(water) - 1) < 0.05, "WATER $water: the orbit moved ${p.orbitHz} from ${Mercury.orbitHz(water)}")
            }
            val played = p.periods * rate.toDouble() / p.frames
            val cents = 1200 * kotlin.math.ln(played / hz) / kotlin.math.ln(2.0)
            assertTrue(abs(cents) < 0.1, "$hz: the whole periods move the note $cents cents")
        }
    }

    /** The loop plays the note: with WATER still, read over the whole loop, within 3 cents of TUNE. */
    @Test
    fun `the loop is in tune`() {
        for (voice in MercuryVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val loop = Mercury.renderLoop(voice, Mercury.defaults(voice) + mapOf("TUNE" to tune, "WATER" to 0f))
            val hz = Mercury.frequencyFor(voice, tune)
            val snip = Snip(loop + loop, channels = 1, sampleRate = rate)
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, 0.1f, 1.4f), hz.toDouble())
            println("MERCURY LOOP $voice $tune: ${"%.2f".format(cents)} cents")
            assertTrue(abs(cents) < 3.0, "$voice $tune: the loop plays $cents cents off the note")
        }
    }

    /** WATER is not rounded away in a LOOP (the spec's §13.4): it still drifts the pitch by at least 8 cents across the loop. */
    @Test
    fun `WATER still moves inside the loop`() {
        fun drift(loop: FloatArray, hz: Float): Double {
            val snip = Snip(loop + loop, channels = 1, sampleRate = rate)
            val reads = ArrayList<Double>()
            var t = 0.05
            while (t + 0.06 < loop.size.toDouble() / rate) {
                reads += FineTuning.cents(FineTuning.measuredHz(snip, hz, t.toFloat(), 0.06f), hz.toDouble())
                t += 0.02
            }
            return reads.max() - reads.min()
        }
        for (voice in MercuryVoice.entries) {
            val hz = Mercury.frequencyFor(voice, 0.5f)
            val still = drift(Mercury.renderLoop(voice, Mercury.defaults(voice) + mapOf("WATER" to 0f)), hz)
            val moving = drift(Mercury.renderLoop(voice, Mercury.defaults(voice) + mapOf("WATER" to 0.5f)), hz)
            println("MERCURY LOOP $voice: pitch drift ${"%.1f".format(still)} cents still, ${"%.1f".format(moving)} at WATER .5")
            assertTrue(moving >= 8.0 && moving > still + 5.0, "$voice: WATER .5 drifts the loop only $moving cents (still: $still)")
        }
    }

    /** SCRAMBLE never lands on the LOOP: the top step is a choice, not a roll. */
    @Test
    fun `scramble stays under the LOOP`() {
        val random = Random(4)
        for (voice in MercuryVoice.entries) repeat(200) {
            val hold = Mercury.scramble(voice, random, temperature = 1f).getValue("HOLD")
            assertTrue(hold <= Mercury.SCRAMBLE_HOLD_CEILING && !Mercury.isLoop(hold), "$voice: scramble rolled HOLD $hold")
        }
    }

    /** The loop is levelled like every melodic render, and has a body: no near-silent loop passes for a closed one. */
    @Test
    fun `the loop is levelled and sounding`() {
        for (voice in MercuryVoice.entries) {
            val loop = Mercury.renderLoop(voice, Mercury.defaults(voice) + ("HOLD" to 1f))
            var e = 0.0
            for (v in loop) e += v.toDouble() * v
            val rms = sqrt(e / loop.size)
            assertTrue(rms > 0.05, "$voice: the loop's RMS is $rms")
            assertTrue(loop.all { abs(it) <= 1f }, "$voice: the loop clips")
        }
    }
}
