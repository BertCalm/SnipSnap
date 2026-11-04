package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
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
 * built"): any OVERBLOW above 0 cracks a one-shot up an octave at one moment near its end, and the knob sets how rough the
 * crack is, in one of three flavours ([Bore.Rasp]) the owner is choosing between. The measures read the raw bore wave
 * ([Bore.blow]) as [BoreTest]'s do, so the level stage cannot hide a silent corner. `BoreFrozenSoundTest` is the proof that a
 * knob at 0 changes nothing; this file proves what a knob above 0 does, in every flavour.
 */
class BoreOverblowTest {

    private val rate = BoreMeasure.RAW_RATE
    private val flute = BoreVoice.FLUTE
    private val flavours = Bore.Rasp.entries

    private fun macros(tune: Float = 0.5f, breath: Float = 0.6f, lip: Float = 0.5f, chiff: Float = 0.4f, hold: Float = 0f, overblow: Float = 0f) =
        Bore.defaults(flute) + mapOf("TUNE" to tune, "BREATH" to breath, "LIP" to lip, "CHIFF" to chiff, "HOLD" to hold, "OVERBLOW" to overblow)

    private fun blow(m: Map<String, Float>, seconds: Float, flavour: Bore.Rasp = Bore.OVERBLOW_RASP): FloatArray =
        Bore.blow(flute, Bore.tunedHz(flute, Bore.frequencyFor(flute, m.getValue("TUNE"))), m, rate, gateSeconds = seconds, rasp = flavour)

    private fun attackN(m: Map<String, Float>) = (Bore.attackSeconds(m.getValue("CHIFF")) * rate).toInt().coerceAtLeast(1)

    /** The end of the blown part of a note blown for [seconds] (the attack and the held breath), in seconds: the release starts here. */
    private fun blownSeconds(m: Map<String, Float>, seconds: Float): Float = (attackN(m) + (seconds * rate).toInt()).toFloat() / rate

    /** Where the crack falls, in seconds, for these macros blown for [seconds]: [Bore.crackFrame] on the same gate [Bore.blow] builds. */
    private fun crackSeconds(m: Map<String, Float>, seconds: Float): Float =
        Bore.crackFrame(flute, m, attackN(m), (seconds * rate).toInt(), rate)!!.toFloat() / rate

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
     * The energy between the harmonics of [f0] against the energy at them, in dB, over the longest power-of-two stretch of [x]
     * from [from] seconds that fits before [to]: [BoreMeasure.betweenHarmonicsDb]'s reading with the note given rather than
     * measured, so a wrong mode in the window counts as roughness instead of moving the harmonics it is judged against.
     */
    private fun betweenDb(x: FloatArray, f0: Double, from: Float, to: Float): Double {
        val a = (from * rate).toInt()
        val n = Integer.highestOneBit((to * rate).toInt() - a)
        val re = FloatArray(n) { (x[a + it] * (0.5 - 0.5 * cos(2 * PI * it / n))).toFloat() }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var at = 0.0
        var between = 0.0
        for (k in 1 until n / 2) {
            val bin = k * rate.toDouble() / n
            if (bin < 100.0 || bin > 8000.0) continue
            val h = bin / f0
            val distance = abs(h - Math.rint(h)) * f0
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            if (distance < maxOf(3.0, 0.02 * f0)) at += power else if (distance > 0.3 * f0) between += power
        }
        return 10 * log10(between / at + 1e-18)
    }

    /** How much the level moves from [from] to [to] seconds: the spread of the rms on windows two of [f0]'s periods long, over its mean. */
    private fun modulationDepth(x: FloatArray, f0: Double, from: Float, to: Float): Double {
        val n = (2 * rate / f0).toInt()
        val levels = ArrayList<Double>()
        var a = (from * rate).toInt()
        while (a + n <= (to * rate).toInt()) {
            var s = 0.0
            for (i in a until a + n) s += x[i].toDouble() * x[i]
            levels.add(sqrt(s / n))
            a += n
        }
        val mean = levels.average()
        return sqrt(levels.sumOf { (it - mean) * (it - mean) } / levels.size) / mean
    }

    /** Seconds from [crack] until the note stays within 10 cents of the octave on 40 ms windows, up to [end]. */
    private fun settleSeconds(x: FloatArray, hz: Float, crack: Float, end: Float): Float {
        var t = crack
        var lastOff = crack - 0.04f
        while (t + 0.04f <= end) {
            if (abs(BoreMeasure.cents(x, 2 * hz, t, t + 0.04f)) > 10.0) lastOff = t
            t += 0.04f
        }
        return lastOff + 0.04f - crack
    }

    private fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

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
            assertNull(Bore.crackFrame(flute, absent, 100, 100_000, rate), "no crack for ${absent["OVERBLOW"]}")
        }
        assertNull(Bore.crackFrame(BoreVoice.SAX, m + ("OVERBLOW" to 1f), 100, 100_000, rate), "the reed never cracks")
        val plain = Bore.render(flute).samples
        for (flavour in flavours) {
            assertContentEquals(plain, Bore.render(flute, mapOf("OVERBLOW" to 0f), flavour).samples, "$flavour at 0")
            assertContentEquals(plain, Bore.render(flute, mapOf("OVERBLOW" to Float.NaN), flavour).samples, "$flavour: a NaN OVERBLOW is the default, 0")
        }
    }

    @Test
    fun `the crack falls at one moment near the end of the note, whatever the knob`() {
        for (seconds in listOf(0.3f, 0.5f, 1f, 2.43f, 4f)) {
            val holdN = (seconds * rate).toInt()
            val m = macros()
            val blown = attackN(m) + holdN
            val expected = (blown - maxOf(Bore.OVERBLOW_TAIL_SHARE * blown, Bore.OVERBLOW_TAIL_MIN_SECONDS * rate).toInt()).coerceAtLeast(0)
            for (overblow in listOf(0.01f, 0.25f, 0.5f, 1f)) {
                assertEquals(expected, Bore.crackFrame(flute, m + ("OVERBLOW" to overblow), attackN(m), holdN, rate), "$seconds s at OVERBLOW $overblow")
            }
            val tail = (blown - expected).toFloat() / rate
            assertTrue(tail >= minOf(Bore.OVERBLOW_TAIL_MIN_SECONDS - 1e-3f, blown.toFloat() / rate), "$seconds s: the octave has $tail s before the release")
        }
        assertEquals(0, Bore.crackFrame(flute, macros(overblow = 0.5f), attackN(macros()), (0.3f * rate).toInt(), rate), "a stab is overblown from its first sample")
        val long = attackN(macros()) + (4f * rate).toInt()
        assertEquals((long - (0.25f * long).toInt()), Bore.crackFrame(flute, macros(overblow = 0.5f), attackN(macros()), (4f * rate).toInt(), rate), "a long note keeps its last quarter overblown")
    }

    // ---- the octave, in every flavour ---------------------------------------------

    @Test
    fun `every flavour lands the octave in tune by the end, at every note, lip and breath, and the note is in tune before the crack`() {
        val summary = ArrayList<String>()
        for (flavour in flavours) for (overblow in listOf(0.25f, 1f)) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var weakest = -Double.MAX_VALUE
            var quietest = Double.MAX_VALUE
            var noisiest = -Double.MAX_VALUE
            val failures = ArrayList<String>()
            for (tune in listOf(0f, 0.5f, 1f)) for (lip in listOf(0f, 0.5f, 1f)) for (breath in listOf(0f, 0.6f, 1f)) {
                val m = macros(tune, breath, lip, overblow = overblow)
                val hz = Bore.frequencyFor(flute, tune)
                val y = blow(m, 2f, flavour)
                val crack = crackSeconds(m, 2f)
                val end = blownSeconds(m, 2f)
                val label = "$flavour $overblow TUNE $tune LIP $lip BREATH $breath"
                val before = BoreMeasure.cents(y, hz, 0.4f, crack - 0.02f)
                val c = BoreMeasure.cents(y, 2 * hz, end - 0.2f, end)
                val (h1, h2) = BoreMeasure.harmonicsDb(y, hz, end - 0.2f, end, n = 2)
                val rms = BoreMeasure.acRms(y, end - 0.2f, end)
                val aperiodic = BoreMeasure.aperiodicityDb(y, 2 * hz, end - 0.2f, end)
                lo = minOf(lo, c); hi = maxOf(hi, c); weakest = maxOf(weakest, h1); quietest = minOf(quietest, rms); noisiest = maxOf(noisiest, aperiodic)
                if (abs(before) > 7.0) failures += "$label: the low register plays $before cents off before the crack"
                if (abs(c) > OCTAVE_CENTS) failures += "$label: the octave ends $c cents off"
                if (h2 < -1e-6 || h1 > -20.0) failures += "$label: the octave does not lead at the end (fundamental $h1 dB)"
                if (rms < SPEAKS_OVERBLOWN) failures += "$label: the octave does not speak (raw rms $rms)"
                if (aperiodic > TONE_DB) failures += "$label: the octave is noise, not a tone ($aperiodic dB)"
            }
            summary += "$flavour %.2f: octave %+.1f to %+.1f c, fundamental at least %.0f dB under, raw rms from %.3f, aperiodicity at most %.1f dB".format(java.util.Locale.ROOT, overblow, lo, hi, -weakest, quietest, noisiest)
            println("BORE OVERBLOW end of note: " + summary.last())
            assertTrue(failures.isEmpty(), failures.joinToString("; "))
        }
    }

    @Test
    fun `the rasp grows with the knob, each flavour by its own measure`() {
        // Every flavour at three notes and three settings, read four ways: how long the octave takes to settle after the
        // crack and the energy between the octave's harmonics over the 0.4 s after it (FIGHT's crossing, a wrong mode on the
        // way), the same energy over the settled octave's last 0.35 s (BREATH's noise), and how much the level moves there
        // (GROWL's flutter). Printed whole, asserted on each flavour's own measure.
        val table = HashMap<Triple<Bore.Rasp, Float, Float>, DoubleArray>()
        for (flavour in flavours) for (tune in listOf(0f, 0.5f, 1f)) for (overblow in listOf(0.25f, 0.5f, 1f)) {
            val m = macros(tune, overblow = overblow)
            val hz = Bore.frequencyFor(flute, tune)
            val y = blow(m, 2f, flavour)
            val crack = crackSeconds(m, 2f)
            val end = blownSeconds(m, 2f)
            val row = doubleArrayOf(
                settleSeconds(y, hz, crack, end).toDouble(),
                betweenDb(y, 2.0 * hz, crack, crack + 0.4f),
                modulationDepth(y, 2.0 * hz, end - 0.4f, end - 0.05f),
                BoreMeasure.cents(y, 2 * hz, end - 0.2f, end),
                betweenDb(y, 2.0 * hz, end - 0.4f, end - 0.05f),
            )
            table[Triple(flavour, tune, overblow)] = row
            println("BORE OVERBLOW rasp: $flavour TUNE $tune OVERBLOW $overblow: settles ${f(row[0])} s, between the harmonics ${f(row[1])} dB after the crack and ${f(row[4])} dB on the settled octave, level moves ${f(row[2])}, the octave at the end %+.1f c".format(java.util.Locale.ROOT, row[3]))
        }
        fun at(flavour: Bore.Rasp, tune: Float, k: Int) = listOf(0.25f, 0.5f, 1f).map { table.getValue(Triple(flavour, tune, it))[k] }
        for (tune in listOf(0f, 0.5f, 1f)) {
            // FIGHT: a slower crossing holds the note off the octave longer, with more of it between the octave's harmonics.
            val settle = at(Bore.Rasp.FIGHT, tune, 0)
            assertTrue(settle[0] <= settle[1] && settle[1] <= settle[2] && settle[2] > settle[0], "FIGHT at TUNE $tune settles in $settle s, not later as the knob rises")
            val fight = at(Bore.Rasp.FIGHT, tune, 1)
            assertTrue(fight[0] < fight[1] && fight[1] < fight[2], "FIGHT at TUNE $tune puts $fight dB between the harmonics after the crack, not more as the knob rises")
            // GROWL: the level moves more as the knob rises, at every note.
            val growl = at(Bore.Rasp.GROWL, tune, 2)
            assertTrue(growl[0] < growl[1] && growl[1] < growl[2], "GROWL at TUNE $tune moves the level by $growl, not more as the knob rises")
            // BREATH: more of the settled octave between its harmonics as the knob rises, at every note.
            val breath = at(Bore.Rasp.BREATH, tune, 4)
            assertTrue(breath[0] < breath[1] && breath[1] < breath[2], "BREATH at TUNE $tune puts $breath dB between the settled octave's harmonics, not more as the knob rises")
        }
    }

    @Test
    fun `the crack leaves no step larger than the note's own steepest, in every flavour`() {
        val rows = ArrayList<String>()
        for (flavour in flavours) for (tune in listOf(0f, 0.5f, 1f)) {
            val m = macros(tune, overblow = 0.25f)
            val y = blow(m, 1.6f, flavour)
            val at = (crackSeconds(m, 1.6f) * rate).toInt()
            val steps = FloatArray((0.2f * rate).toInt()) { abs(y[at - (0.25f * rate).toInt() + it + 1] - y[at - (0.25f * rate).toInt() + it]) }
            steps.sort()
            val steepest = steps[((steps.size - 1) * 0.999).toInt()]
            var widest = 0f
            for (i in at - 64 until at + (Bore.OVERBLOW_RAMP_SECONDS * rate).toInt()) widest = maxOf(widest, abs(y[i + 1] - y[i]))
            rows += "$flavour TUNE $tune %.2f".format(java.util.Locale.ROOT, widest / steepest)
            assertTrue(widest <= CRACK_STEP_OVER_STEEPEST * steepest, "$flavour TUNE $tune: the crack steps ${widest / steepest} times the note's steepest")
        }
        println("BORE OVERBLOW step at the crack over the note's steepest: " + rows.joinToString(", "))
    }

    // ---- the house probes, overblown ------------------------------------------

    @Test
    fun `every overblown corner speaks, stays bounded and renders clean, in every flavour`() {
        for (flavour in flavours) for (overblow in listOf(0.5f, 1f)) for (tune in listOf(0f, 1f)) for (lip in listOf(0f, 1f)) for (breath in listOf(0f, 1f)) {
            val label = "$flavour OVERBLOW $overblow TUNE $tune LIP $lip BREATH $breath"
            val m = macros(tune, breath, lip, chiff = 1f, hold = 0.98f, overblow = overblow)
            val y = blow(m, 2f, flavour)
            val end = blownSeconds(m, 2f)
            assertTrue(y.all { it.isFinite() }, "$label went non-finite")
            assertTrue(y.maxOf { abs(it) } < 3f, "$label peaked at ${y.maxOf { abs(it) }}")
            assertTrue(BoreMeasure.acRms(y, end - 0.2f, end) >= SPEAKS_OVERBLOWN, "$label does not speak at the end of the note")
            val snip = Bore.render(flute, macros(tune, breath, lip, overblow = overblow, hold = 0.6f), flavour)
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$label: the render clips or is not finite")
            assertTrue(Loudness.of(snip) >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$label: the render is too quiet")
            assertTrue(abs(snip.samples.average()) < 0.01, "$label: DC ${snip.samples.average()}")
        }
    }

    @Test
    fun `every flavour renders the same twice`() {
        for (flavour in flavours) {
            val m = macros(overblow = 0.7f, hold = 0.6f)
            assertContentEquals(Bore.render(flute, m, flavour).samples, Bore.render(flute, m, flavour).samples, "$flavour")
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
        for (flavour in flavours) {
            assertEquals(Bore.render(flute, macros(hold = 0.45f)).frameCount, Bore.render(flute, macros(hold = 0.45f, overblow = 0.7f), flavour).frameCount, "$flavour")
        }
    }

    @Test
    fun `a LOOP is a held note, so it plays without the crack and every keygroup zone stays on its key`() {
        val loop = Bore.render(flute, mapOf("HOLD" to 1f, "OVERBLOW" to 0f)).samples
        for (flavour in flavours) for (overblow in listOf(0.3f, 1f)) {
            assertContentEquals(loop, Bore.render(flute, mapOf("HOLD" to 1f, "OVERBLOW" to overblow), flavour).samples, "the $flavour LOOP at OVERBLOW $overblow")
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
        assertEquals(0.6f, Bore.scramble(flute, Random(3), near = asked).getValue("OVERBLOW"), "the player's own rasp is kept, not rolled")
        assertFalse("OVERBLOW" in Bore.scramble(BoreVoice.SAX, Random(3)), "SAX has no such knob to roll")
    }

    private companion object {
        /** The octave's tolerance at the end of the note: the shipped FLUTE's own across its knobs is 7 cents ([BoreTest]). */
        const val OCTAVE_CENTS = 7.0

        /**
         * The upper register's raw rms floor. The octave is a quieter wave in the bore than the low register (whose floor,
         * [BoreTest]'s, is 0.5); the harness read 0.28 to 0.42 at the floored LIP with BREATH 0, against 0.0005 for the silent
         * corner the floor removed. With [TONE_DB] it says the note is a tone that sounds, not breath.
         */
        const val SPEAKS_OVERBLOWN = 0.15

        /**
         * At most this much of the note left after subtracting it from itself one period on: a pitched tone, even under BREATH's
         * extra turbulence (R1 read a clean FLUTE at -50 dB, its breathiest at -28).
         */
        const val TONE_DB = -10.0

        /** The crack's widest step against the note's steepest ordinary one: Round 1.7's fuzz allows a LOOP's join 1.1. */
        const val CRACK_STEP_OVER_STEEPEST = 1.1f
    }
}
