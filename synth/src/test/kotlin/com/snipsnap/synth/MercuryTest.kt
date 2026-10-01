package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MERCURY's R1 claims, on the engine itself. Every bar sits between what R1 measured (printed by each
 * test) and the failure it guards. The bank under it has its own claims (`ModesBankTest`), and the
 * physics behind the mapping is Phase 0's record. Nothing here says the engine sounds good: the
 * audition page decides that.
 */
class MercuryTest {

    private val rate = Dsp.RATE

    private fun f(x: Double, d: Int = 2) = "%.${d}f".format(java.util.Locale.ROOT, x)

    private fun render(voice: MercuryVoice, vararg macros: Pair<String, Float>): Snip = Mercury.render(voice, Mercury.defaults(voice) + macros.toMap())

    /** Cents from the note TUNE names, read on [fromSec, fromSec + body) of the finished render. */
    private fun cents(voice: MercuryVoice, snip: Snip, tune: Float, fromSec: Double, body: Double = 0.3): Double {
        val hz = Mercury.frequencyFor(voice, tune)
        return FineTuning.cents(FineTuning.measuredHz(snip, hz, fromSec.toFloat(), body.toFloat()), hz.toDouble())
    }

    /** ARCO's measure: the RMS of the difference over the RMS of [a], the longer one's tail counted as difference. */
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        val n = maxOf(a.size, b.size)
        var d = 0.0
        var e = 0.0
        for (i in 0 until n) {
            val x = if (i < a.size) a[i].toDouble() else 0.0
            val y = if (i < b.size) b[i].toDouble() else 0.0
            d += (x - y) * (x - y)
            e += x * x
        }
        return sqrt(d / e)
    }

    private fun rms(x: FloatArray, fromSec: Double, toSec: Double): Double {
        val a = (fromSec * rate).toInt().coerceIn(0, x.size)
        val b = (toSec * rate).toInt().coerceIn(a, x.size)
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return if (b > a) sqrt(s / (b - a)) else 0.0
    }

    @Test
    fun `every voice renders finite, levelled audio of its predicted length at the bottom, middle and top of TUNE`() {
        for (voice in MercuryVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val m = Mercury.defaults(voice) + ("TUNE" to tune)
            val snip = Mercury.render(voice, m)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice TUNE $tune: not finite")
            assertTrue(snip.samples.all { it in -1f..1f }, "$voice TUNE $tune: clipped")
            assertEquals(Mercury.renderFrames(Mercury.settled(m, voice)), snip.frameCount, "$voice TUNE $tune: the predicted length is not the rendered one")
            val loud = Loudness.of(snip)
            assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$voice TUNE $tune is too quiet: $loud")
            assertTrue(abs(snip.samples.average()) < 0.01, "$voice TUNE $tune: DC ${snip.samples.average()}")
        }
    }

    /**
     * With BEND centred (no gesture) and WATER at 0 (no drift), the note is the note: the tapped PING read just after
     * its strike, the rubbed SING and BLADE read late in their contact, at both ends and the middle of TUNE. R1 measured
     * within 0.53 cents everywhere; the bar is 3, Phase 0's own.
     */
    @Test
    fun `with BEND centred and no water, every voice sits on its note`() {
        var worst = 0.0
        for (voice in MercuryVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val snip = render(voice, "TUNE" to tune, "BEND" to 0.5f, "WATER" to 0f)
            val hold = Mercury.holdSeconds(Mercury.DEFAULT_HOLD).toDouble()
            val from = if (voice == MercuryVoice.PING) 0.15 else hold - 0.4
            val c = cents(voice, snip, tune, from)
            worst = maxOf(worst, abs(c))
            assertTrue(abs(c) < 3.0, "$voice TUNE $tune sounds ${f(c)} cents off its note")
        }
        println("MERCURY in tune: worst ${f(worst)} cents")
    }

    /**
     * BEND is a gesture into the note: it starts up to [Mercury.BEND_EXCURSION_SEMITONES] away (up for BEND 1, down for 0)
     * and settles onto the note. Read on a long rubbed BLADE (HOLD 1, four seconds of contact) in the first tenth of a second
     * and after 2.5 s. R1 measured about +160 and -160 cents early and under 1 cent late.
     */
    @Test
    fun `the bend gesture glides into the note and settles on it`() {
        for ((bend, sign) in listOf(1f to 1.0, 0f to -1.0)) {
            val snip = render(MercuryVoice.BLADE, "TUNE" to 0.5f, "BEND" to bend, "WATER" to 0f, "HOLD" to 1f)
            // FineTuning searches one semitone either side of the frequency it is given, and the gesture is about 1.6 semitones
            // out in this window, so the early read is centred 1.5 semitones out, where the gesture is, and reported against the note.
            val hz = Mercury.frequencyFor(MercuryVoice.BLADE, 0.5f)
            val early = FineTuning.cents(FineTuning.measuredHz(snip, (hz * Math.pow(2.0, sign * 1.5 / 12)).toFloat(), 0.03f, 0.1f), hz.toDouble())
            val late = cents(MercuryVoice.BLADE, snip, 0.5f, 2.5, 0.4)
            println("MERCURY bend $bend: early ${f(early)} cents, late ${f(late)} cents")
            assertTrue(sign * early > 60.0, "BEND $bend should start well ${if (sign > 0) "above" else "below"} the note: ${f(early)} cents")
            assertTrue(abs(late) < 3.0, "BEND $bend should settle on the note: ${f(late)} cents")
        }
    }

    /**
     * No dead knob (playability rule 1), on ARCO's measure: each knob moved alone from its default (to 0.1 if the default is
     * over a half, else to 0.9) changes the finished render by over 0.1 of the note. WATER is held to the spec's own harder
     * claim too: 0.05 against 0, by the same bar. R1 measured every knob at 0.13 (PING's HOLD, which on a tapped voice is
     * mostly length) to 1.45, and WATER 0.05 at 1.2-1.3. An unknown key must change nothing at all, to the bit.
     */
    @Test
    fun `every macro changes the sound, a little water too, and an unknown key does not`() {
        for (voice in MercuryVoice.entries) {
            val base = Mercury.render(voice, Mercury.defaults(voice)).samples
            assertContentEquals(base, Mercury.render(voice, Mercury.defaults(voice) + ("SPARKLE" to 0.9f)).samples, "$voice: an unknown key changed the render")
            val diffs = Mercury.macrosFor(voice).associate { spec ->
                val moved = Mercury.defaults(voice) + (spec.name to if (spec.default > 0.5f) 0.1f else 0.9f)
                spec.name to relativeDifference(base, Mercury.render(voice, moved).samples)
            }
            val water = relativeDifference(
                Mercury.render(voice, Mercury.defaults(voice) + ("WATER" to 0f)).samples,
                Mercury.render(voice, Mercury.defaults(voice) + ("WATER" to 0.05f)).samples,
            )
            println("MERCURY macros $voice: " + diffs.entries.joinToString(" ") { "${it.key} ${f(it.value, 3)}" } + ", WATER 0 against 0.05 ${f(water, 3)}")
            for ((name, diff) in diffs) assertTrue(diff > 0.1, "$voice $name did nothing (${f(diff, 4)})")
            assertTrue(water > 0.1, "$voice: WATER 0.05 is indistinguishable from 0 (${f(water, 4)})")
        }
    }

    /**
     * RUB turns a tap into a rub on the same object: at RUB 0 the note decays through its contact, at RUB 1 it sustains.
     * Read as the level over the last 0.2 s of a 2.4 s contact against the 0.2 s after the strike. R1 measured the tap at
     * 0.07 and the rub at 1.15 (SING), and 0.01 and 1.25 (BLADE).
     */
    @Test
    fun `RUB turns a decaying tap into a sustained rub`() {
        for (voice in listOf(MercuryVoice.SING, MercuryVoice.BLADE)) {
            val hold = Mercury.holdSeconds(0.8f).toDouble()
            fun ratio(rub: Float): Double {
                val s = render(voice, "RUB" to rub, "HOLD" to 0.8f, "WATER" to 0f).samples
                return rms(s, hold - 0.2, hold) / rms(s, 0.05, 0.25)
            }
            val tap = ratio(0f)
            val rub = ratio(1f)
            println("MERCURY rub $voice: tap ${f(tap)}, rub ${f(rub)}")
            assertTrue(tap < 0.6, "$voice RUB 0 should decay through its contact: ${f(tap)}")
            assertTrue(rub > 0.8, "$voice RUB 1 should sustain to the end of its contact: ${f(rub)}")
        }
    }

    /**
     * The rub sings on the fundamental, not on mode 1, at the bottom of each rubbed voice and at both ends of GLASS (the
     * selective, long-ringing end and the rough end), with the springs at COUPLE 1. Phase 0 saw mode 1 capture SING's low
     * notes before the contact taper and the period-scaled onset; this is the guard. R1 measured within 0.42 cents.
     */
    @Test
    fun `the rub locks onto the note at the bottom of the range, at both ends of GLASS, coupled`() {
        for (voice in listOf(MercuryVoice.SING, MercuryVoice.BLADE)) for (glass in listOf(0f, 1f)) {
            val snip = render(voice, "TUNE" to 0f, "RUB" to 1f, "BEND" to 0.5f, "WATER" to 0f, "GLASS" to glass, "COUPLE" to 1f, "HOLD" to 0.8f)
            val hold = Mercury.holdSeconds(0.8f).toDouble()
            val c = cents(voice, snip, 0f, hold - 0.5, 0.4)
            println("MERCURY lock $voice GLASS $glass: ${f(c)} cents")
            assertTrue(abs(c) < 4.0, "$voice GLASS $glass: the rub sits ${f(c)} cents off the note, as if another mode had it")
        }
    }

    /** COUPLE 1 repels the near vessel mode and would pull the note flat by tens of cents; the anchor fix puts it back. R1: within 0.41 cents. */
    @Test
    fun `the anchor fix holds the note at full COUPLE`() {
        for (voice in MercuryVoice.entries) {
            val snip = render(voice, "COUPLE" to 1f, "BEND" to 0.5f, "WATER" to 0f)
            val hold = Mercury.holdSeconds(Mercury.DEFAULT_HOLD).toDouble()
            val c = cents(voice, snip, 0.5f, if (voice == MercuryVoice.PING) 0.15 else hold - 0.4)
            println("MERCURY COUPLE 1 $voice: ${f(c)} cents")
            assertTrue(abs(c) < 3.0, "$voice COUPLE 1 sounds ${f(c)} cents off its note")
        }
    }

    /**
     * Every corner of the five sound knobs, at the bottom and top of TUNE, on the raw object (no level to hide a runaway):
     * finite and bounded. R1 measured a raw peak of 0.043 at the worst corner, against a finger speed of 0.03; the bar is 0.2.
     */
    @Test
    fun `every corner of the knobs stays finite and bounded`() {
        val cases = MercuryVoice.entries.flatMap { v -> (0 until 32).flatMap { mask -> listOf(0f, 1f).map { t -> Triple(v, mask, t) } } }
        val peaks = cases.parallelStream().map { (voice, mask, tune) ->
            val bit = { k: Int -> if (mask shr k and 1 == 1) 1f else 0f }
            val m = Mercury.settled(mapOf("TUNE" to tune, "BEND" to bit(0), "RUB" to bit(1), "WATER" to bit(2), "GLASS" to bit(3), "COUPLE" to bit(4), "HOLD" to 0f), voice)
            val raw = Mercury.sound(voice, Mercury.frequencyFor(voice, tune).toDouble(), m)
            Triple("$voice $m", raw.all { it.isFinite() }, raw.maxOf { abs(it) })
        }.toList()
        val worst = peaks.maxBy { it.third }
        println("MERCURY corners: ${peaks.size} renders, worst raw peak ${f(worst.third.toDouble(), 4)} at ${worst.first}")
        for ((label, finite, peak) in peaks) {
            assertTrue(finite, "$label: not finite")
            assertTrue(peak < 0.2f, "$label: raw peak $peak")
        }
    }

    /**
     * What a pad is filed as follows the classifier's own 1.5 s length line, to the frame: the shortest MERCURY (GLASS 0,
     * HOLD 0: 0.93 s) is filed PERC and the classifier hears no drum in it; the default is filed LOOP and the classifier
     * agrees.
     */
    @Test
    fun `the filed class follows the classifier's length line`() {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        for (voice in MercuryVoice.entries) {
            val short = Mercury.defaults(voice) + mapOf("GLASS" to 0f, "HOLD" to 0f)
            val snip = Mercury.render(voice, short)
            val heard = Classifier.classify(snip).drumClass
            println("MERCURY short $voice: ${f(snip.frameCount.toDouble() / rate)} s, filed ${Mercury.drumClassFor(voice, short)}, heard $heard")
            assertTrue(snip.frameCount.toFloat() / rate < Mercury.LOOP_THRESHOLD_SECONDS)
            assertEquals(DrumClass.PERC, Mercury.drumClassFor(voice, short))
            assertTrue(heard !in choking && heard != DrumClass.LOOP, "$voice short: the classifier heard $heard")
            val long = Mercury.render(voice)
            assertTrue(long.frameCount.toFloat() / rate > Mercury.LOOP_THRESHOLD_SECONDS)
            assertEquals(DrumClass.LOOP, Mercury.drumClassFor(voice))
            assertEquals(DrumClass.LOOP, Classifier.classify(long).drumClass)
        }
    }

    /** HOLD is the contact's length: the render grows by exactly HOLD's seconds and nothing else moves its length. */
    @Test
    fun `HOLD sets the contact's length and GLASS the tail's`() {
        for (voice in MercuryVoice.entries) {
            val short = render(voice, "HOLD" to 0f).frameCount
            val long = render(voice, "HOLD" to 1f).frameCount
            val expected = (Mercury.HOLD_MAX_SECONDS - Mercury.HOLD_MIN_SECONDS) * rate
            assertTrue(abs((long - short) - expected) <= 2, "$voice: HOLD 0 to 1 added ${long - short} frames, expected $expected")
            assertEquals(render(voice, "COUPLE" to 0f).frameCount, render(voice, "COUPLE" to 1f).frameCount, "$voice: COUPLE moved the length")
            assertTrue(render(voice, "GLASS" to 1f).frameCount > render(voice, "GLASS" to 0f).frameCount, "$voice: GLASS should lengthen the ring")
        }
    }

    @Test
    fun `an out of range macro is clamped and an unknown key is dropped`() {
        for (voice in MercuryVoice.entries) {
            val s = Mercury.settled(mapOf("GLASS" to 1.7f, "WATER" to -0.4f, "SPARKLE" to 0.3f), voice)
            assertEquals(1f, s.getValue("GLASS"))
            assertEquals(0f, s.getValue("WATER"))
            assertTrue("SPARKLE" !in s)
            assertEquals(Mercury.macrosFor(voice).map { it.name }.toSet(), s.keys)
        }
    }

    @Test
    fun `a recipe with an unknown macro, a value out of range or a blank name is refused`() {
        assertFailsWith<IllegalArgumentException> { MercuryPatch("Bad", MercuryVoice.PING, mapOf("SPARKLE" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { MercuryPatch("Bad", MercuryVoice.SING, mapOf("GLASS" to 1.5f)) }
        assertFailsWith<IllegalArgumentException> { MercuryPatch(" ", MercuryVoice.BLADE, emptyMap()) }
        assertFailsWith<JsonException> { MercuryPatch.fromJsonText("""{"engine":"MERCURY","version":1,"name":"x","voice":"SAW","macros":{}}""") }
    }

    @Test
    fun `every voice declares the same seven knobs in the same order`() {
        for (voice in MercuryVoice.entries) {
            assertEquals(listOf("TUNE", "BEND", "RUB", "WATER", "GLASS", "COUPLE", "HOLD"), Mercury.macrosFor(voice).map { it.name })
        }
    }

    /**
     * Velocity with no brightness macro falls back to `soften` (BORE's case): MERCURY registers no override until a
     * monotonic centroid sweep earns one (decision 8 of the design). The layer must still be softer when it is quieter.
     */
    @Test
    fun `velocity falls back to soften, and a soft layer is darker than a hard one`() {
        for (voice in MercuryVoice.entries) {
            val patch = MercuryPatch("Velocity", voice, Mercury.defaults(voice))
            assertContentEquals(Velocity.soften(patch.render(), 0.7f).samples, Velocity.atVelocity(patch, 0.3f).samples, "$voice: atVelocity is not the soften fallback")
            val soft = com.snipsnap.audio.FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = com.snipsnap.audio.FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")
        }
    }
}
