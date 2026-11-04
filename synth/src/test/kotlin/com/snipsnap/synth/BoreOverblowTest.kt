package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OVERBLOW, FLUTE's sixth macro (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "R2: OVERBLOW, as
 * built"): the knob sets when a one-shot cracks to its upper register, an octave up. The measures read the raw bore wave
 * ([Bore.blow]) as [BoreTest]'s do, so the level stage cannot hide a silent corner. `BoreFrozenSoundTest` is the proof that
 * a knob at 0 changes nothing; this file proves what a knob above 0 does.
 */
class BoreOverblowTest {

    private val rate = BoreMeasure.RAW_RATE
    private val flute = BoreVoice.FLUTE

    private fun macros(tune: Float = 0.5f, breath: Float = 0.6f, lip: Float = 0.5f, chiff: Float = 0.4f, hold: Float = 0f, overblow: Float = 0f) =
        Bore.defaults(flute) + mapOf("TUNE" to tune, "BREATH" to breath, "LIP" to lip, "CHIFF" to chiff, "HOLD" to hold, "OVERBLOW" to overblow)

    private fun blow(m: Map<String, Float>, seconds: Float): FloatArray =
        Bore.blow(flute, Bore.tunedHz(flute, Bore.frequencyFor(flute, m.getValue("TUNE"))), m, rate, gateSeconds = seconds)

    /** Where the crack falls, in seconds, for these macros blown for [seconds]: [Bore.crackFrame] on the same gate [Bore.blow] builds. */
    private fun crackSeconds(m: Map<String, Float>, seconds: Float): Float {
        val attackN = (Bore.attackSeconds(m.getValue("CHIFF")) * rate).toInt().coerceAtLeast(1)
        val holdN = (seconds * rate).toInt()
        return Bore.crackFrame(flute, m, attackN, holdN)!!.toFloat() / rate
    }

    /** The Hann-windowed magnitude of [x] at [hz] over [n] samples from [from]. */
    private fun magnitude(x: FloatArray, from: Int, n: Int, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (k in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * k / n)
            val ph = 2 * PI * hz * k / rate
            re += w * x[from + k] * cos(ph)
            im += w * x[from + k] * sin(ph)
        }
        return sqrt(re * re + im * im)
    }

    /**
     * Seconds from [from] until the octave first carries more than the note's own fundamental, on 10 ms windows of a note that
     * is sounding (a raw rms of at least [SOUNDING]: the first milliseconds of the attack are breath and nothing else, where any
     * bin can lead); null if it never does.
     */
    private fun octaveLeadsAt(x: FloatArray, hz: Float, from: Float, to: Float): Float? {
        val n = (0.01f * rate).toInt()
        var a = (from * rate).toInt()
        while (a + n <= minOf(x.size, (to * rate).toInt())) {
            val sounding = BoreMeasure.acRms(x, a.toFloat() / rate, (a + n).toFloat() / rate) >= SOUNDING
            if (sounding && magnitude(x, a, n, 2.0 * hz) > magnitude(x, a, n, hz.toDouble())) return a.toFloat() / rate
            a += n / 2
        }
        return null
    }

    // ---- the knob ------------------------------------------------------------

    @Test
    fun `OVERBLOW is FLUTE's alone, its last knob, and 0 by default`() {
        assertEquals("OVERBLOW", Bore.macrosFor(flute).last().name)
        assertEquals(0f, Bore.defaults(flute).getValue("OVERBLOW"))
        assertEquals(listOf("TUNE", "BREATH", "LIP", "CHIFF", "HOLD"), Bore.macrosFor(flute).dropLast(1).map { it.name }, "the five knobs before it are where they were")
        assertFalse("OVERBLOW" in Bore.macrosFor(BoreVoice.SAX).map { it.name }, "the reed has no register vent, so no knob that does nothing")
        assertFalse("OVERBLOW" in Bore.settled(mapOf("OVERBLOW" to 1f), BoreVoice.SAX), "a SAX map drops the key, as it drops any unknown one")
        assertFailsWith<IllegalArgumentException>("a SAX patch naming OVERBLOW is refused") { BorePatch("NO VENT", BoreVoice.SAX, mapOf("OVERBLOW" to 1f)) }
    }

    @Test
    fun `at 0, absent or not a number the note never reaches the crack, and the render is the knob at 0`() {
        val m = Bore.defaults(flute)
        for (absent in listOf(m - "OVERBLOW", m + ("OVERBLOW" to 0f), m + ("OVERBLOW" to -0f), m + ("OVERBLOW" to Float.NaN))) {
            assertNull(Bore.crackFrame(flute, absent, 100, 1000), "no crack for ${absent["OVERBLOW"]}")
        }
        assertNull(Bore.crackFrame(BoreVoice.SAX, m + ("OVERBLOW" to 1f), 100, 1000), "the reed never cracks")
        val plain = Bore.render(flute).samples
        assertContentEquals(plain, Bore.render(flute, mapOf("OVERBLOW" to 0f)).samples)
        assertContentEquals(plain, Bore.render(flute, mapOf("OVERBLOW" to Float.NaN)).samples, "a NaN OVERBLOW is the default, 0")
    }

    @Test
    fun `the crack comes later the less the knob is turned, from the first sample at 1`() {
        assertEquals(0, Bore.crackFrame(flute, mapOf("OVERBLOW" to 1f), 100, 1000))
        var last = Int.MAX_VALUE
        for (step in 1..20) {
            val at = Bore.crackFrame(flute, mapOf("OVERBLOW" to step / 20f), 4_410, 176_400)!!
            assertTrue(at < last, "OVERBLOW ${step / 20f} cracks at $at, not before the last step's $last")
            last = at
        }
        // Measured on the note: the moment the octave takes over moves the same way, and follows the crack closely.
        val hz = Bore.frequencyFor(flute, 0.5f)
        var lastLead = Float.MAX_VALUE
        val rows = ArrayList<String>()
        for (overblow in listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)) {
            val m = macros(overblow = overblow)
            val y = blow(m, 2f)
            val crack = crackSeconds(m, 2f)
            val lead = octaveLeadsAt(y, hz, 0f, 2f)
            rows += "OVERBLOW %.1f: crack %.3f s, the octave leads at %s s".format(java.util.Locale.ROOT, overblow, crack, lead)
            println("BORE OVERBLOW break at C5: " + rows.last())
            assertTrue(lead != null, "OVERBLOW $overblow never reached the octave")
            assertTrue(lead < lastLead, "OVERBLOW $overblow: the octave leads at $lead, not before the last step's $lastLead")
            assertTrue(lead >= crack - 0.01f && lead <= crack + OCTAVE_WITHIN_SECONDS, "OVERBLOW $overblow: the octave leads at $lead s, the crack is at $crack s")
            lastLead = lead
        }
    }

    // ---- the octave -----------------------------------------------------------

    @Test
    fun `an overblown note plays the octave, in tune, at every note, lip and breath`() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var weakestFundamental = -Double.MAX_VALUE
        var quietest = Double.MAX_VALUE
        var noisiest = -Double.MAX_VALUE
        val rows = ArrayList<String>()
        for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 0.6f, 1f)) {
            val m = macros(tune, breath, lip, overblow = 1f)
            val hz = Bore.frequencyFor(flute, tune)
            val y = blow(m, 1f)
            val label = "TUNE $tune LIP $lip BREATH $breath"
            val c = BoreMeasure.cents(y, 2 * hz, 0.5f, 0.9f)
            val (h1, h2) = BoreMeasure.harmonicsDb(y, hz, 0.5f, 0.9f, n = 2)
            rows += "%s %+.1f c, f0 %.0f dB".format(java.util.Locale.ROOT, label, c, h1)
            lo = minOf(lo, c)
            hi = maxOf(hi, c)
            weakestFundamental = maxOf(weakestFundamental, h1)
            assertEquals(0.0, h2, 1e-9, "$label: the octave is not the strongest of the two")
            assertTrue(h1 < -20.0, "$label: the old fundamental is still there, $h1 dB under the octave")
            assertTrue(abs(c) <= OCTAVE_CENTS, "$label: the octave plays $c cents off")
            val rms = BoreMeasure.acRms(y, 0.7f, 0.9f)
            val aperiodic = BoreMeasure.aperiodicityDb(y, 2 * hz, 0.5f, 0.9f)
            quietest = minOf(quietest, rms)
            noisiest = maxOf(noisiest, aperiodic)
            assertTrue(rms >= SPEAKS_OVERBLOWN, "$label: the overblown note does not speak (raw rms $rms)")
            assertTrue(aperiodic <= TONE_DB, "$label: the overblown note is breath, not a tone ($aperiodic dB aperiodic)")
        }
        println("BORE OVERBLOW 1, the octave: %+.1f to %+.1f cents, the old fundamental at least %.0f dB under it, raw rms from %.3f, aperiodicity at most %.1f dB".format(java.util.Locale.ROOT, lo, hi, -weakestFundamental, quietest, noisiest))
        println("BORE OVERBLOW grid: " + rows.joinToString("; "))
    }

    @Test
    fun `a crack mid-note leaves the low register in tune and lands the octave in tune after it`() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var slowest = 0f
        for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 1f)) {
            val m = macros(tune, breath, lip, overblow = 0.5f)
            val hz = Bore.frequencyFor(flute, tune)
            val y = blow(m, 2.4f)
            val crack = crackSeconds(m, 2.4f)
            val label = "TUNE $tune LIP $lip BREATH $breath, crack at $crack s"
            val before = BoreMeasure.cents(y, hz, 0.4f, crack - 0.02f)
            assertTrue(abs(before) <= 7.0, "$label: the low register plays $before cents off before the crack")
            // The octave's pitch, read on 0.1 s windows from the crack on: the way there, then where it settles.
            val track = BoreMeasure.centsTrack(y, 2 * hz, crack, 2.4f, rate, window = 0.1f, hop = 0.1f)
            val trackText = track.joinToString(" ") { "%+.0f".format(java.util.Locale.ROOT, it) }
            println("BORE OVERBLOW 0.5 $label, cents from the octave every 0.1 s after it: $trackText")
            val lead = octaveLeadsAt(y, hz, crack - 0.05f, 2.4f)
            assertTrue(lead != null && lead <= crack + OCTAVE_WITHIN_SECONDS, "$label: the octave leads at $lead s")
            slowest = maxOf(slowest, lead - crack)
            val after = BoreMeasure.cents(y, 2 * hz, crack + 0.6f, 2.4f)
            lo = minOf(lo, after)
            hi = maxOf(hi, after)
            assertTrue(abs(after) <= OCTAVE_CENTS, "$label: the octave after the crack plays $after cents off (track $trackText)")
            assertTrue(BoreMeasure.acRms(y, crack + 0.6f, 2.4f) >= SPEAKS_OVERBLOWN, "$label: the note does not speak after the crack")
        }
        println("BORE OVERBLOW 0.5, the octave settled after the crack: %+.1f to %+.1f cents; it leads at most %.2f s after the crack".format(java.util.Locale.ROOT, lo, hi, slowest))
    }

    @Test
    fun `the jet's glide leaves no step at the crack larger than the note's own steepest`() {
        val rows = ArrayList<String>()
        for (tune in listOf(0f, 0.5f, 1f)) {
            val m = macros(tune, overblow = 0.5f)
            val y = blow(m, 1.6f)
            val at = (crackSeconds(m, 1.6f) * rate).toInt()
            val steps = FloatArray((0.2f * rate).toInt()) { abs(y[at - (0.25f * rate).toInt() + it + 1] - y[at - (0.25f * rate).toInt() + it]) }
            steps.sort()
            val steepest = steps[((steps.size - 1) * 0.999).toInt()]
            var widest = 0f
            for (i in at - 64 until at + (0.02f * rate).toInt()) widest = maxOf(widest, abs(y[i + 1] - y[i]))
            rows += "TUNE $tune %.2f".format(java.util.Locale.ROOT, widest / steepest)
            assertTrue(widest <= CRACK_STEP_OVER_STEEPEST * steepest, "TUNE $tune: the crack steps ${widest / steepest} times the note's steepest")
        }
        println("BORE OVERBLOW step at the crack over the note's steepest: " + rows.joinToString(", "))
    }

    // ---- the house probes, overblown ------------------------------------------

    @Test
    fun `every overblown corner speaks, stays bounded and renders clean`() {
        for (overblow in listOf(0.5f, 1f)) for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 1f)) for (breath in listOf(0f, 1f)) {
            val label = "OVERBLOW $overblow TUNE $tune LIP $lip BREATH $breath"
            val y = blow(macros(tune, breath, lip, chiff = 1f, hold = 0.98f, overblow = overblow), 2f)
            assertTrue(y.all { it.isFinite() }, "$label went non-finite")
            assertTrue(y.maxOf { abs(it) } < 3f, "$label peaked at ${y.maxOf { abs(it) }}")
            assertTrue(BoreMeasure.acRms(y, 1.7f, 1.9f) >= SPEAKS_OVERBLOWN, "$label does not speak at the end of the note")
            val snip = Bore.render(flute, macros(tune, breath, lip, overblow = overblow, hold = 0.6f))
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$label: the render clips or is not finite")
            assertTrue(Loudness.of(snip) >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$label: the render is too quiet")
            assertTrue(abs(snip.samples.average()) < 0.01, "$label: DC ${snip.samples.average()}")
        }
    }

    @Test
    fun `OVERBLOW moves neither the length nor the class of a note`() {
        for (hold in listOf(0f, 0.45f, 0.9f, 1f)) {
            val plain = macros(hold = hold)
            val cracked = macros(hold = hold, overblow = 0.7f)
            assertEquals(Bore.renderFrames(plain), Bore.renderFrames(cracked), "HOLD $hold")
            assertEquals(Bore.drumClassFor(flute, plain), Bore.drumClassFor(flute, cracked), "HOLD $hold")
        }
        assertEquals(Bore.render(flute, macros(hold = 0.45f)).frameCount, Bore.render(flute, macros(hold = 0.45f, overblow = 0.7f)).frameCount)
    }

    @Test
    fun `a LOOP is a held note, so it plays without the crack and every keygroup zone stays on its key`() {
        val loop = Bore.render(flute, mapOf("HOLD" to 1f, "OVERBLOW" to 0f)).samples
        for (overblow in listOf(0.3f, 1f)) {
            assertContentEquals(loop, Bore.render(flute, mapOf("HOLD" to 1f, "OVERBLOW" to overblow)).samples, "the LOOP at OVERBLOW $overblow")
        }
        assertEquals(Bore.held(mapOf("OVERBLOW" to 0.8f, "LIP" to 0.2f)), mapOf("OVERBLOW" to 0f, "LIP" to 0.2f))
        assertEquals(Bore.held(mapOf("LIP" to 0.2f)), mapOf("LIP" to 0.2f), "a map without the knob is left as it is")
    }

    // ---- saved sounds and SCRAMBLE ---------------------------------------------

    @Test
    fun `a FLUTE saved before OVERBLOW loads at 0 and plays as it did, and a cracked one round-trips`() {
        val old = BorePatch("OLD FLUTE", flute, mapOf("TUNE" to 0.4f, "BREATH" to 0.7f, "LIP" to 0.3f, "CHIFF" to 0.6f, "HOLD" to 0.5f))
        val text = old.toJsonText()
        assertFalse("OVERBLOW" in text, "the saved text predates the knob")
        val loaded = BorePatch.fromJsonText(text)
        assertEquals(old, loaded)
        assertEquals(0f, Bore.settled(loaded.macros, flute).getValue("OVERBLOW"))
        assertContentEquals(BorePatch("OLD FLUTE", flute, old.macros + ("OVERBLOW" to 0f)).render().samples, loaded.render().samples)

        val cracked = BorePatch("CRACKED", flute, old.macros + ("OVERBLOW" to 0.5f))
        val back = BorePatch.fromJsonText(cracked.toJsonText())
        assertEquals(cracked, back)
        assertContentEquals(cracked.render().samples, back.render().samples)
    }

    @Test
    fun `SCRAMBLE never rolls OVERBLOW - it keeps what it starts from`() {
        val random = Random(11)
        repeat(40) {
            assertEquals(0f, Bore.scramble(flute, random).getValue("OVERBLOW"), "a roll near the presets cracked")
            assertEquals(0f, Bore.scramble(flute, random, temperature = 1f).getValue("OVERBLOW"), "a wild roll cracked")
        }
        val asked = BorePatch("ASKED", flute, Bore.defaults(flute) + ("OVERBLOW" to 0.6f))
        assertEquals(0.6f, Bore.scramble(flute, Random(3), near = asked).getValue("OVERBLOW"), "the player's own crack is kept, not rolled")
        assertFalse("OVERBLOW" in Bore.scramble(BoreVoice.SAX, Random(3)), "SAX has no such knob to roll")
    }

    private companion object {
        /** The octave's tolerance: the shipped FLUTE's own across its knobs is 7 cents ([BoreTest]); the harness measured the octave within -2.3 to +5.9 at LIP 0.35 and up. */
        const val OCTAVE_CENTS = 7.0

        /**
         * The upper register's raw rms floor. The octave is a quieter wave in the bore than the low register (whose floor,
         * [BoreTest]'s, is 0.5); the harness read 0.28 to 0.42 at the floored LIP with BREATH 0, against 0.0005 for the silent
         * corner the floor removed. With [TONE_DB] it says the note is a tone that sounds, not breath.
         */
        const val SPEAKS_OVERBLOWN = 0.15

        /** At most this much of the note left after subtracting it from itself one period on: a pitched tone (R1 read a clean FLUTE at -50 dB, a breathy one at -28). */
        const val TONE_DB = -15.0

        /** A window the octave detector reads: the note under way, not the breath before it. */
        const val SOUNDING = 0.3

        /**
         * How soon after the crack the octave must lead. With the crack's seed ([Bore.OVERBLOW_SEED]) the engine measured 0.03 to
         * 0.08 s across the mid-note grid and at C5; without it, 0.31 s at C5 and 0.97 s at C4 with the hardest breath.
         */
        const val OCTAVE_WITHIN_SECONDS = 0.2f

        /** The crack's widest step against the note's steepest ordinary one: Round 1.7's fuzz allows a LOOP's join 1.1. */
        const val CRACK_STEP_OVER_STEEPEST = 1.1f
    }
}
