package com.snipsnap.synth

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * BUZZ, ARCO's sixth macro (R2): the chien by analogy, [Terra]'s contact-buzz shape on the raw string before the box ([Arco.buzzed]).
 *
 * What this file holds, each with a control that can fail:
 *  - **Nothing moved at 0.** The SHA-256 of every factory preset's render and of each voice's default note and default LOOP, taken from the
 *    code before BUZZ existed (the branch at d78489ba) by a throwaway probe, and the same renders with the BUZZ key absent, as a patch saved
 *    before R2 has it. [Arco.buzzed] hands back the very buffer it was given at 0.
 *  - **SCRAMBLE's dice are the dice they were.** A frozen copy of `Arco.scramble` as it was before BUZZ draws the same five macros from the
 *    same seed and leaves the random stream where it left it.
 *  - **A LOOP still closes with BUZZ at its top.** R1b's own grid of 225 loops (every TUNE step of both voices at the defaults, GRIP 0, GRIP 1,
 *    BODY 0 and BODY 1), at BUZZ 1, under the Organ's bar. The noise is read from a table one loop long, so the buzz repeats with the loop;
 *    the control is the same loop with the noise running free, which does not close.
 *  - **Monotone, and audible at the low settings** by a stated proxy: the energy between the harmonics of a LOOP rises at every step of BUZZ,
 *    and a one-shot moves by at least [AUDIBLE_AT_QUARTER] of the note at BUZZ 0.25 and more at every step. The laws are measured side by side.
 *  - **The owner's top.** The owner heard the first build and wrote "For Buzz we need to recalibrate. 40 is probably the top of the range."
 *    (2026-11-04), so BUZZ 1 is that build's 0.4, held to the bit, one-shot and LOOP.
 *  - **Bounded at every corner** of the other five knobs, at BUZZ 0.5 and 1: finite, unclipped, DC-free, the length unchanged.
 */
class ArcoBuzzTest {

    private fun f(v: Double, d: Int = 3) = "%.${d}f".format(Locale.ROOT, v)
    private fun e(v: Double) = "%.2e".format(Locale.ROOT, v)

    private fun sha(samples: FloatArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(samples.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putInt(java.lang.Float.floatToRawIntBits(s))
        return md.digest(buf.array()).joinToString("") { "%02x".format(it) }
    }

    /** RMS of the difference over RMS of [a] (the longer one's tail counts as difference): ArcoProductTest's measure of a macro moving a note. */
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        var diff = 0.0
        var energy = 0.0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0f }.toDouble()
            val y = b.getOrElse(i) { 0f }.toDouble()
            diff += (x - y) * (x - y)
            energy += x * x
        }
        return sqrt(diff / energy.coerceAtLeast(1e-30))
    }

    /**
     * The share of a LOOP's energy that is not at the note's harmonics, in dB. A LOOP is [periods] whole periods in its whole frames (to 3e-7,
     * the retune's stop), so the harmonics sit on the DFT bins that are multiples of [periods] and nowhere else: their energy is read by
     * Goertzel at those bins (Parseval: the energy is the sum of the bins' powers over N, a real signal's bins k and N - k both counted) and the
     * rest of the energy is what lies between them. A frozen string has almost none; the buzz's noise is what puts it there.
     */
    private fun betweenHarmonicsDb(loop: FloatArray, periods: Int): Double {
        val n = loop.size
        var total = 0.0
        for (x in loop) total += x.toDouble() * x
        fun binPower(k: Int): Double {
            val c = 2.0 * cos(2.0 * PI * k / n)
            var s1 = 0.0
            var s2 = 0.0
            for (x in loop) {
                val s = x + c * s1 - s2
                s2 = s1
                s1 = s
            }
            return s1 * s1 + s2 * s2 - c * s1 * s2
        }
        var harmonic = binPower(0) / n
        var k = periods
        while (2 * k < n) {
            harmonic += 2.0 * binPower(k) / n
            k += periods
        }
        val between = (total - harmonic).coerceAtLeast(total * 1e-15)
        return 10.0 * log10(between / total)
    }

    // ---- nothing moved at 0 ----------------------------------------------------

    /**
     * SHA-256 of the float bits (little-endian) of each render on the code before BUZZ (d78489ba): printed by a throwaway probe test run on that
     * code (`p.render()` for every preset, [Arco.render] at each voice's defaults and at HOLD 1) and copied here. Never regenerate these from the
     * engine: they are the "before".
     */
    private val before = mapOf(
        "CELLO SLOW BOW" to "a433582661ab710931f464d5fddfb15443aae81fb1d2076dc57b9ac44078ee4e",
        "CELLO SHORT STAB" to "2294f4312feb5c38b8626969a289f0b0c98c2a1a110bd1654a2861a1db889659",
        "CELLO DEEP PEDAL" to "3fc9a620a6f909298574f3382d040bd1c02fdca0ae0ae883d5da89be709a86ac",
        "CELLO GRIT BOW" to "83120befc0bbac5fa1257d0550ed647a19e7ba0a268fe720ec8e8e5fc20c3496",
        "CELLO DRY SCRAPE" to "b1d02fab35227615ea448ce69058b152b32a6c22c5d8085b1a7fd865301846d5",
        "CELLO CINEMA LOW" to "a8590a06c2f8792427706425a3b224997a74c78703ca277132417babe0ca6e2b",
        "CELLO HORSEHAIR" to "ca796024fcf46caa72957d5baacd9da418e60aff4f43e1ceba6943c845ca3023",
        "CELLO ENDLESS DRAW" to "1a5a370e5c07d9c1bcf508666fada1465f6aaa2725eafed9a6ba12e88e96a1a3",
        "ERHU NASAL LINE" to "59327be21d2eb9e0ef56192901499014836dabbef7ce3b735756fabf058bb94f",
        "ERHU MOON FIDDLE" to "ca2171d8e7e50f9c2ea042e3d1fcff9fa54f19d21f25ad50a2de7100891332b4",
        "ERHU THIN SCRAPE" to "e42b34ee3195c8a5380e027af5461b606b75476a596ef2af03ee47cbe73c110c",
        "ERHU HIGH CRY" to "714fe36b00c9053bba947944205c65d577e6d20b7429523e80f0017052f8cb6c",
        "ERHU SLOW CRY" to "c4d57c66678ed2edd6e246e0c989e953de12faf5e62d6288dd3499c8dee43799",
        "ERHU TEA HOUSE" to "60df75055cdf498aa56cb88c1f162e5553a27be76fe61b144ea35d0e86a40502",
        "ERHU TWO STRING" to "c2edb050d21574e98aab2942bdb9f0dc8f974771c4fb015fc6a37f907138dbc7",
        "ERHU ENDLESS CRY" to "d35c1af2b556dc439347aba45e7409857278e9c8d565626933658ea5cbd4e269",
        "CELLO DEFAULT NOTE" to "7905cbafeaa2b1d2c9fdad5b99ec6593fa55c7fcdafe683a5f28a9372574b0fb",
        "CELLO DEFAULT LOOP" to "5cf782048472de0de31647be5198f4b541f624b49956691509b9d6809b0e7e37",
        "ERHU DEFAULT NOTE" to "841561826981dd44bb6e837402da8163f909d2b8271c877157826ea7831995c0",
        "ERHU DEFAULT LOOP" to "5fea0f9f71fc22742ec33efdcd56bec61d7930501a2be8f2bbe9f4f458b4c408",
    )

    /**
     * A render as BUZZ left it: a one-shot exactly as the engine plays it, and a LOOP with R2's loop vibrato (the owner's later request, a separate
     * change on purpose) turned off, because the vibrato moves every loop and the hashes are of the loops before it. The one-shots have no such
     * switch: nothing after BUZZ touches them.
     */
    private fun asBuzzLeftIt(voice: ArcoVoice, macros: Map<String, Float>): FloatArray =
        if (Arco.isLoop(Arco.settled(macros, voice).getValue("HOLD"))) Arco.render(voice, macros, Arco.BUZZ_LAW, Arco.LoopVibrato.OFF).samples
        else Arco.render(voice, macros).samples

    @Test
    fun `at BUZZ 0 every preset, each default note and each default loop is the code before BUZZ, sample for sample`() {
        val checked = mutableListOf<String>()
        for (p in ArcoPresets.all()) {
            val key = "${p.voice} ${p.name}"
            assertEquals(0f, p.macros.getValue("BUZZ"), "$key: a factory preset is written at BUZZ 0")
            assertEquals(before.getValue(key), sha(asBuzzLeftIt(p.voice, p.macros)), "$key changed")
            assertEquals(before.getValue(key), sha(asBuzzLeftIt(p.voice, p.macros - "BUZZ")), "$key, saved before BUZZ (no key), changed")
            if (!Arco.isLoop(p.macros.getValue("HOLD"))) assertEquals(before.getValue(key), sha(p.render().samples), "$key, through the patch, changed")
            checked += key
        }
        for (v in ArcoVoice.entries) {
            val note = "$v DEFAULT NOTE"
            val loop = "$v DEFAULT LOOP"
            assertEquals(before.getValue(note), sha(Arco.render(v, Arco.defaults(v)).samples), "$note changed")
            assertEquals(before.getValue(note), sha(Arco.render(v, Arco.defaults(v) - "BUZZ").samples), "$note, with no BUZZ key, changed")
            assertEquals(before.getValue(loop), sha(asBuzzLeftIt(v, Arco.defaults(v) + ("HOLD" to 1f))), "$loop changed")
            checked += note
            checked += loop
        }
        assertEquals(before.keys, checked.toSet(), "every hash taken before BUZZ was checked")
        // the control: the comparison sees BUZZ. A preset at BUZZ 0.25, and a loop at BUZZ 0.25, are not their own hashes.
        val p = ArcoPresets.all().first()
        assertNotEquals(before.getValue("${p.voice} ${p.name}"), sha(Arco.render(p.voice, p.macros + ("BUZZ" to 0.25f)).samples), "control: BUZZ 0.25 rendered the BUZZ 0 hash")
        val v = ArcoVoice.ERHU
        assertNotEquals(before.getValue("$v DEFAULT LOOP"), sha(asBuzzLeftIt(v, Arco.defaults(v) + mapOf("HOLD" to 1f, "BUZZ" to 0.25f))), "control: a loop at BUZZ 0.25 rendered the BUZZ 0 hash")
        // and the loop as shipped (with R2's loop vibrato) is not the loop before BUZZ: the switch above is needed, and the loop moved on purpose.
        assertNotEquals(before.getValue("$v DEFAULT LOOP"), sha(Arco.render(v, Arco.defaults(v) + ("HOLD" to 1f)).samples), "control: the shipped loop has no vibrato")
        println("ARCO BUZZ 0: ${checked.size} renders match their hashes from before BUZZ (16 presets, with and without the key, and each voice's default note and loop; loops with R2's loop vibrato off)")
    }

    @Test
    fun `BUZZ 0 hands back the very buffer, and anything above it does not touch its input`() {
        val m = Arco.settled(emptyMap(), ArcoVoice.CELLO)
        val raw = Arco.bow(ArcoVoice.CELLO, Arco.frequencyFor(ArcoVoice.CELLO, 0.5f), m)
        val copy = raw.copyOf()
        assertSame(raw, Arco.buzzed(raw, ArcoVoice.CELLO, 0f, 0.3f), "BUZZ 0 must be the same array, not a copy")
        assertSame(raw, Arco.buzzed(raw, ArcoVoice.CELLO, -0.2f, 0.3f), "a BUZZ under 0 is off")
        assertSame(raw, Arco.buzzed(raw, ArcoVoice.CELLO, Float.NaN, 0.3f), "a NaN BUZZ is off")
        val out = Arco.buzzed(raw, ArcoVoice.CELLO, 0.5f, Arco.rmsOf(raw, 0, raw.size))
        assertContentEquals(copy, raw, "the input was changed")
        assertTrue(!out.contentEquals(raw), "control: BUZZ 0.5 changed nothing")
    }

    @Test
    fun `a saved patch with no BUZZ key loads at 0, and BUZZ round-trips through JSON`() {
        val old = """{"engine":"ARCO","version":${Patches.VERSION},"name":"Old","voice":"CELLO","macros":{"TUNE":0.5,"BOW":0.5,"GRIP":0.6,"BODY":0.5,"HOLD":0.4}}"""
        val loaded = ArcoPatch.fromJsonText(old)
        assertEquals(null, loaded.macros["BUZZ"], "the old patch carries no BUZZ")
        assertEquals(0f, Arco.settled(loaded.macros, loaded.voice).getValue("BUZZ"))
        assertContentEquals(Arco.render(ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO)).samples, loaded.render().samples, "an old patch is the default note")
        val buzzing = ArcoPatch("Rattle", ArcoVoice.ERHU, Arco.defaults(ArcoVoice.ERHU) + ("BUZZ" to 0.6f))
        val back = ArcoPatch.fromJsonText(buzzing.toJsonText())
        assertEquals(buzzing, back)
        assertContentEquals(buzzing.render().samples, back.render().samples)
        assertTrue(!buzzing.render().samples.contentEquals(loaded.render().samples), "control: BUZZ 0.6 is not the default note")
    }

    // ---- SCRAMBLE --------------------------------------------------------------

    /** `Arco.scramble` as it was before BUZZ (the five knobs, nothing else), frozen: never edited. */
    private fun scrambleBeforeBuzz(voice: ArcoVoice, random: Random, temperature: Float, near: Patch?): Map<String, Float> {
        val base = linkedMapOf("TUNE" to 0.5f, "BOW" to Arco.DEFAULT_BOW, "GRIP" to Arco.DEFAULT_GRIP, "BODY" to Arco.DEFAULT_BODY, "HOLD" to Arco.DEFAULT_HOLD)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ArcoPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(Arco.SCRAMBLE_HOLD_CEILING)
        return rolled
    }

    @Test
    fun `SCRAMBLE draws the five knobs it drew before BUZZ, never rolls BUZZ, and leaves the dice where it found them`() {
        val near = ArcoPatch("Near", ArcoVoice.CELLO, Arco.defaults(ArcoVoice.CELLO) + mapOf("GRIP" to 0.2f, "BUZZ" to 0.7f))
        var rolls = 0
        for (voice in ArcoVoice.entries) for (temperature in listOf(0.35f, 1f)) for (n in listOf(null, near)) for (seed in 0 until 100) {
            val a = Random(seed)
            val b = Random(seed)
            val now = Arco.scramble(voice, a, temperature, n)
            val then = scrambleBeforeBuzz(voice, b, temperature, n)
            assertEquals(then, now - "BUZZ", "$voice t $temperature seed $seed: the five knobs differ from before BUZZ")
            assertEquals(if (n == null) 0f else 0.7f, now.getValue("BUZZ"), "$voice t $temperature seed $seed: BUZZ was rolled")
            assertEquals(b.nextLong(), a.nextLong(), "$voice t $temperature seed $seed: the random stream was left somewhere else")
            rolls++
        }
        // the control: rolling BUZZ with the others (the naive change) draws one more number, so the stream check above would see it.
        val naive = Random(3)
        Dsp.scrambleNear(Arco.defaults(ArcoVoice.CELLO), 1f, naive)
        val frozen = Random(3)
        scrambleBeforeBuzz(ArcoVoice.CELLO, frozen, 1f, null)
        assertNotEquals(frozen.nextLong(), naive.nextLong(), "control: a roll with BUZZ in the map left the stream where the frozen copy does")
        println("ARCO BUZZ scramble: $rolls rolls draw what the frozen copy draws and leave the stream where it does")
    }

    // ---- the LOOP closes ---------------------------------------------------------

    private class LoopCase(val voice: ArcoVoice, val step: Int, val corner: String, val macros: Map<String, Float>)

    private class LoopOutcome(val case: LoopCase, val seam: Double, val finite: Boolean, val peak: Float, val frames: Int, val millis: Long)

    /**
     * R1b's own grid (ArcoLoopFuzzTest's header: every step of both voices at the default and four other corners, GRIP 0, GRIP 1, BODY 0 and BODY 1,
     * 225 loops), every one at BUZZ 1, the most the buzz does. Each is held to the Organ's bar (`Keys.MAX_SEAM_ERROR`), read the way the engine reads
     * it: the kept stretch against itself one loop later, so a buzz that did not repeat with the loop would show (the control below). `Arco.renderLoop`,
     * what a LOOP pad, a SURFACE loop and `LoopPadMaker` render through, refuses by name above the same bar, and it is called at the six highest seams.
     */
    @Test
    fun `at BUZZ 1 every loop of R1b's grid closes under the Organ's bar`() {
        val cases = ArcoVoice.entries.flatMap { voice ->
            (0..Arco.tuneSemitones(voice)).flatMap { step ->
                val tune = step / Arco.tuneSemitones(voice).toFloat()
                listOf(
                    "defaults" to emptyMap(),
                    "GRIP 0" to mapOf("GRIP" to 0f),
                    "GRIP 1" to mapOf("GRIP" to 1f),
                    "BODY 0" to mapOf("BODY" to 0f),
                    "BODY 1" to mapOf("BODY" to 1f),
                ).map { (corner, knobs) -> LoopCase(voice, step, corner, Arco.defaults(voice) + knobs + mapOf("TUNE" to tune, "HOLD" to 1f, "BUZZ" to 1f)) }
            }
        }
        val outcomes = cases.parallelStream().map { c ->
            val started = System.nanoTime()
            val r = Arco.renderLoopMeasured(c.voice, c.macros)
            LoopOutcome(c, r.seam, r.loop.all { it.isFinite() && it in -1f..1f }, r.loop.maxOf { abs(it) }, r.loop.size, (System.nanoTime() - started) / 1_000_000L)
        }.toList()
        assertEquals(225, outcomes.size)
        for (voice in ArcoVoice.entries) for (corner in listOf("defaults", "GRIP 0", "GRIP 1", "BODY 0", "BODY 1")) {
            val row = outcomes.filter { it.case.voice == voice && it.case.corner == corner }
            val worst = row.maxBy { it.seam }
            println(
                "ARCO BUZZ 1 loops $voice $corner: ${row.size} loops, worst seam ${e(worst.seam)} at step ${worst.case.step} (bar ${e(Keys.MAX_SEAM_ERROR)}), " +
                    "highest peak ${f(row.maxOf { it.peak }.toDouble())}, ${row.sumOf { it.millis }} ms summed (${row.maxOf { it.millis }} ms the slowest)",
            )
        }
        for (o in outcomes) {
            val where = "${o.case.voice} step ${o.case.step} ${o.case.corner} at BUZZ 1"
            assertTrue(o.seam < Keys.MAX_SEAM_ERROR, "$where: the loop does not close (seam ${o.seam})")
            assertTrue(o.finite, "$where: the loop is not finite or clips")
            assertTrue(o.frames >= 2 * Dsp.RATE, "$where: the loop is under 2 s")
        }
        // Arco.renderLoop's own refusal at the six highest seams, and the same loops at BUZZ 0 beside them: what the string does on its own.
        for (o in outcomes.sortedByDescending { it.seam }.take(6)) {
            Arco.renderLoop(o.case.voice, o.case.macros)
            val plain = Arco.renderLoopMeasured(o.case.voice, o.case.macros + ("BUZZ" to 0f)).seam
            println("ARCO BUZZ 1 loops, a high seam: ${o.case.voice} step ${o.case.step} ${o.case.corner}: ${e(o.seam)} at BUZZ 1, ${e(plain)} at BUZZ 0")
        }
    }

    @Test
    fun `the control - with the noise running free the same loop does not close`() {
        val m = Arco.defaults(ArcoVoice.CELLO) + mapOf("HOLD" to 1f, "BUZZ" to 1f)
        val free = Arco.BuzzLaw(Arco.BUZZ_THRESHOLD_FROM, Arco.BUZZ_THRESHOLD_TO, Arco.BUZZ_GAIN, loopTable = false, top = Arco.BUZZ_TOP, curve = Arco.BUZZ_CURVE)
        val tabled = Arco.renderLoopMeasured(ArcoVoice.CELLO, m)
        val loose = Arco.renderLoopMeasured(ArcoVoice.CELLO, m, free)
        println("ARCO BUZZ loop control CELLO C3 at BUZZ 1: seam ${e(tabled.seam)} with the noise one loop long, ${e(loose.seam)} with it running free (bar ${e(Keys.MAX_SEAM_ERROR)})")
        assertTrue(tabled.seam < Keys.MAX_SEAM_ERROR, "the engine's loop does not close: ${tabled.seam}")
        assertTrue(loose.seam > Keys.MAX_SEAM_ERROR, "control: a free-running buzz closed the loop (${loose.seam}), so the seam test cannot see the noise")
    }

    // ---- monotone, audible, and the two laws --------------------------------------

    /**
     * The proxy bar for "audible at the low settings": how far BUZZ moves a one-shot at 0.25, as a share of the note (ArcoProductTest's measure).
     * 0.03 is -30 dB of noise against the note. A statement about numbers, chosen before the laws were measured, not about hearing: the owner's ear
     * set the top of the range (2026-11-04), and the curve of the knob is what keeps 0.25 over this bar.
     */
    private val AUDIBLE_AT_QUARTER = 0.03

    private val BUZZ_STEPS = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)

    /** The root and the default note of each voice (CELLO C2 and C3, ERHU D4 and C5). */
    private val TUNES = listOf(0f, 0.5f)

    /**
     * Monotone, read between the harmonics of R1's loop (the LOOP with R2's loop vibrato off, [Arco.LoopVibrato.OFF]): a frozen string puts almost
     * nothing there, so what rises is the buzz alone. Since R2 the shipped loop's vibrato spreads each harmonic into sidebands (about -16 dB of the loop
     * at CELLO C2), which would hide BUZZ's low steps; and a sample-by-sample difference of two shipped loops is no measure either, because BUZZ moves
     * where [Siren.bestCut] cuts the loop, so the two are the same loop rotated (a first try read -13 dB at BUZZ 0.1 and +3 dB at 1 that way). The
     * one-shot law test below is the measure on the sound as it ships.
     */
    @Test
    fun `BUZZ is monotone - the energy between a loop's harmonics rises at every step`() {
        for (voice in ArcoVoice.entries) for (tune in TUNES) {
            val hz = Arco.frequencyFor(voice, tune)
            val periods = Arco.planLoop(hz).periods
            val base = Arco.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f)
            val dbs = BUZZ_STEPS.map { b ->
                betweenHarmonicsDb(Arco.renderLoopMeasured(voice, base + ("BUZZ" to b), loopVibrato = Arco.LoopVibrato.OFF).loop, periods)
            }
            println("ARCO BUZZ between the harmonics $voice midi ${Arco.midiFor(voice, tune)}: " + BUZZ_STEPS.zip(dbs).joinToString(", ") { (b, d) -> "BUZZ $b ${f(d, 1)} dB" })
            for (i in 1 until dbs.size) {
                assertTrue(dbs[i] > dbs[i - 1] + 0.2, "$voice tune $tune: BUZZ ${BUZZ_STEPS[i]} is not more between the harmonics than ${BUZZ_STEPS[i - 1]} (${dbs[i]} against ${dbs[i - 1]} dB)")
            }
        }
    }

    /**
     * The engine's law ([Arco.BUZZ_LAW]: the knob bent onto the drive, `drive = 0.4 * BUZZ^0.3`, then the threshold falling from 1.5 to 0.5 times the
     * note's RMS and the gain 3.0 times the drive) beside the first build's ([Arco.BUZZ_LAW_V1], the knob straight onto the drive, which the owner heard
     * as too much past 0.4) and TERRA's (a threshold at the RMS, TERRA's gain of 0.45), so the recalibration and the choice of gain can both be read.
     * The measure is the house's for "a macro changes the sound", on the root and the default note of each voice. The engine's law must clear the
     * low-end proxy bar at 0.25 and rise at every step. The first build's middle bar (0.06 at 0.5) is retired: the owner's ear put the top of the range
     * at a level (0.066 to 0.100) not far over it, so a middle bar would be a second top. The printed lines are the comparison the design record quotes.
     */
    @Test
    fun `BUZZ moves a one-shot at the low and middle settings, and the laws side by side`() {
        val laws = listOf(
            "drive 0.4 x BUZZ^0.3, falling 1.5 to 0.5, gain ${Arco.BUZZ_GAIN} (the engine's)" to Arco.BUZZ_LAW,
            "the first build's: the knob onto the drive" to Arco.BUZZ_LAW_V1,
            "fixed 1.0, gain 0.45 (TERRA's)" to Arco.BuzzLaw(1f, 1f, 0.45f),
        )
        val levels = listOf(0.25f, 0.4f, 0.5f, 0.75f, 0.9f, 1f)
        for (voice in ArcoVoice.entries) for (tune in TUNES) {
            val base = Arco.defaults(voice) + ("TUNE" to tune)
            val plain = Arco.render(voice, base).samples
            for ((name, law) in laws) {
                val moves = levels.map { b -> relativeDifference(plain, Arco.render(voice, base + ("BUZZ" to b), law).samples) }
                println("ARCO BUZZ law $voice midi ${Arco.midiFor(voice, tune)} [$name]: " + levels.zip(moves).joinToString(", ") { (b, d) -> "BUZZ $b ${f(d)} (${f(20 * log10(d), 1)} dB)" })
                if (law === Arco.BUZZ_LAW) {
                    assertTrue(moves[0] >= AUDIBLE_AT_QUARTER, "$voice tune $tune: BUZZ 0.25 moves the note by ${moves[0]}, under $AUDIBLE_AT_QUARTER")
                    for (i in 1 until moves.size) assertTrue(moves[i] > moves[i - 1], "$voice tune $tune: BUZZ ${levels[i]} moves the note less than ${levels[i - 1]}")
                }
            }
        }
    }

    /**
     * The recalibration's promise, held to the bit: the engine's BUZZ 1 is the first build's BUZZ 0.4 (the same drive, so the same threshold, 1.1 times
     * the RMS, and the same gain, 1.2), one-shot and LOOP, both voices, at the root and the default note. The control: the engine's BUZZ 0.4 is not it.
     */
    @Test
    fun `BUZZ 1 is the first build's 0 point 4, to the bit`() {
        assertEquals(0.4f, Arco.buzzDrive(1f), "the drive at BUZZ 1")
        assertEquals(Arco.buzzDrive(0.4f, Arco.BUZZ_LAW_V1), Arco.buzzDrive(1f), "the first build's drive at 0.4")
        for (voice in ArcoVoice.entries) for (tune in TUNES) {
            val base = Arco.defaults(voice) + ("TUNE" to tune)
            val now = Arco.render(voice, base + ("BUZZ" to 1f)).samples
            assertContentEquals(Arco.render(voice, base + ("BUZZ" to 0.4f), Arco.BUZZ_LAW_V1).samples, now, "$voice tune $tune: BUZZ 1 is not the first build's 0.4")
            assertTrue(!now.contentEquals(Arco.render(voice, base + ("BUZZ" to 0.4f)).samples), "control: the engine's BUZZ 0.4 is its BUZZ 1")
            val loop = base + ("HOLD" to 1f)
            assertContentEquals(
                Arco.render(voice, loop + ("BUZZ" to 0.4f), Arco.BUZZ_LAW_V1).samples, Arco.render(voice, loop + ("BUZZ" to 1f)).samples,
                "$voice tune $tune: the LOOP at BUZZ 1 is not the first build's at 0.4",
            )
        }
        println("ARCO BUZZ recalibration: the drive at BUZZ 0.25 / 0.5 / 0.75 / 1 is " + listOf(0.25f, 0.5f, 0.75f, 1f).joinToString(" / ") { f(Arco.buzzDrive(it).toDouble()) })
    }

    // ---- bounded ----------------------------------------------------------------

    /**
     * Every corner of the other five knobs (TUNE, BOW, GRIP and BODY at 0 and 1; HOLD at 0 and 0.95, the top of the one-shot travel), at BUZZ 0.5
     * and 1, both voices: 128 one-shots. Each is finite, its peak is in 0.01 to 0.99 (the level's own ceiling), its mean is under 0.005, its length
     * is [Arco.renderFrames] (BUZZ never moves a note's length, so [Arco.drumClassFor] stays closed form), and the buzzed raw string is under
     * `1 + BUZZ_GAIN * BUZZ` times the bow's ceiling ([Strings.Bow.RAW_PEAK_CEILING]), the bound [Arco.buzzed] promises.
     */
    @Test
    fun `BUZZ is bounded at every corner of the other knobs`() {
        class Corner(val voice: ArcoVoice, val macros: Map<String, Float>)
        class Result(val corner: Corner, val problems: List<String>, val peak: Float, val rawPeak: Float)
        val corners = ArcoVoice.entries.flatMap { voice ->
            val out = mutableListOf<Corner>()
            for (t in listOf(0f, 1f)) for (bow in listOf(0f, 1f)) for (grip in listOf(0f, 1f)) for (body in listOf(0f, 1f)) for (hold in listOf(0f, Arco.SCRAMBLE_HOLD_CEILING)) for (buzz in listOf(0.5f, 1f)) {
                out += Corner(voice, mapOf("TUNE" to t, "BOW" to bow, "GRIP" to grip, "BODY" to body, "HOLD" to hold, "BUZZ" to buzz))
            }
            out
        }
        val results = corners.parallelStream().map { c ->
            val p = mutableListOf<String>()
            val s = Arco.render(c.voice, c.macros).samples
            val peak = s.maxOf { abs(it) }
            val mean = s.sumOf { it.toDouble() } / s.size
            if (!s.all { it.isFinite() }) p += "not finite"
            if (peak !in 0.01f..(0.99f + 1e-6f)) p += "peak $peak"
            if (abs(mean) > 0.005) p += "mean $mean"
            if (s.size != Arco.renderFrames(c.voice, c.macros)) p += "length ${s.size} against ${Arco.renderFrames(c.voice, c.macros)}"
            val m = Arco.settled(c.macros, c.voice)
            val hz = Arco.frequencyFor(c.voice, m.getValue("TUNE"))
            val raw = Arco.bow(c.voice, hz, m)
            val strung = Arco.buzzed(raw, c.voice, m.getValue("BUZZ"), Arco.bowOnLevel(raw, c.voice, hz, m))
            val rawPeak = strung.maxOf { abs(it) }
            val bound = (1f + Arco.BUZZ_GAIN * Arco.buzzDrive(m.getValue("BUZZ"))) * Strings.Bow.RAW_PEAK_CEILING
            if (rawPeak > bound) p += "raw peak $rawPeak over $bound"
            Result(c, p, peak, rawPeak)
        }.toList()
        for (voice in ArcoVoice.entries) {
            val mine = results.filter { it.corner.voice == voice }
            println("ARCO BUZZ corners $voice: ${mine.size} one-shots, finished peaks ${f(mine.minOf { it.peak }.toDouble())} .. ${f(mine.maxOf { it.peak }.toDouble())}, buzzed raw string at most ${f(mine.maxOf { it.rawPeak }.toDouble())}")
        }
        val bad = results.filter { it.problems.isNotEmpty() }
        assertTrue(bad.isEmpty(), bad.joinToString("\n") { "${it.corner.voice} ${it.corner.macros}: ${it.problems}" })
    }
}
