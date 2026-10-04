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
     * and settles onto the note. Read on a long rubbed BLADE (HOLD .98, the longest one-shot: 3.8 s of contact) in the first tenth of a second
     * and after 2.5 s. R1 measured about +160 and -160 cents early and under 1 cent late.
     */
    @Test
    fun `the bend gesture glides into the note and settles on it`() {
        for ((bend, sign) in listOf(1f to 1.0, 0f to -1.0)) {
            val snip = render(MercuryVoice.BLADE, "TUNE" to 0.5f, "BEND" to bend, "WATER" to 0f, "HOLD" to 0.98f)
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
     * The same gesture on every voice, R2c's three included. BEND 1 starts above the note and BEND 0 below, by the voice's
     * own excursion (two semitones, SHARD's four) times how much of it is left in the first tenth of a second (its
     * gesture's time constant sets that), and each settles on the note by the end of a long contact. Read at RUB .85 so a
     * tapped voice sustains through the window too.
     */
    @Test
    fun `every voice's bend gesture glides into the note and settles on it`() {
        for (voice in MercuryVoice.entries) for ((bend, sign) in listOf(1f to 1.0, 0f to -1.0)) {
            val snip = render(voice, "TUNE" to 0.5f, "BEND" to bend, "WATER" to 0f, "RUB" to 0.85f, "HOLD" to 0.98f)
            val tau = Mercury.gestureSeconds(voice)
            // What is left of the excursion, averaged over 0.03-0.13 s: centre the read there, since FineTuning looks one semitone either side.
            val left = tau / 0.1 * (Math.exp(-0.03 / tau) - Math.exp(-0.13 / tau))
            val centre = Mercury.geometryOf(voice).bendExcursionSemitones * left
            val hz = Mercury.frequencyFor(voice, 0.5f)
            val early = FineTuning.cents(FineTuning.measuredHz(snip, (hz * Math.pow(2.0, sign * centre / 12)).toFloat(), 0.03f, 0.1f), hz.toDouble())
            val late = cents(voice, snip, 0.5f, 3.2, 0.4)
            println("MERCURY bend $voice $bend: early ${f(early)} cents, late ${f(late)} cents")
            assertTrue(sign * early > 40.0, "$voice BEND $bend should start well ${if (sign > 0) "above" else "below"} the note: ${f(early)} cents")
            assertTrue(abs(late) < 3.0, "$voice BEND $bend should settle on the note: ${f(late)} cents")
        }
    }

    /**
     * WATER as an ear hears it: what it adds to the same note held still, as pitch drift and as level swell. ARCO's
     * waveform measure (the test below) scored round 1's WATER 0.05 at 1.2, yet the owner could not hear WATER 0.05-0.2
     * on SING or BLADE (2026-10-02): a waveform difference counts any phase shift, and round 1's WATER added only 4-10
     * cents and under 0.6 dB. So the bar is in the ear's units, read every 20 ms over 60 ms windows against WATER 0 with
     * BEND flat: WATER 0.05 adds at least 8 cents of drift and 1.2 dB of swell on every voice, and more WATER never adds
     * less. Round 2 measured 10-21 cents and 1.6-2.6 dB at 0.05, and about 50 cents and 7-11 dB at 1.
     */
    @Test
    fun `a little water is heard as drift and swell, and more water moves more`() {
        fun series(snip: Snip, hz: Float, from: Double, to: Double): Pair<DoubleArray, DoubleArray> {
            val c = ArrayList<Double>()
            val d = ArrayList<Double>()
            var t = from
            while (t + 0.06 < to) {
                c += FineTuning.cents(FineTuning.measuredHz(snip, hz, t.toFloat(), 0.06f), hz.toDouble())
                d += 20 * kotlin.math.log10(rms(snip.samples, t, t + 0.06) + 1e-12)
                t += 0.02
            }
            return c.toDoubleArray() to d.toDoubleArray()
        }
        fun spread(a: DoubleArray, b: DoubleArray): Double {
            val x = DoubleArray(minOf(a.size, b.size)) { a[it] - b[it] }
            return x.max() - x.min()
        }
        for (voice in MercuryVoice.entries) {
            val hz = Mercury.frequencyFor(voice, 0.5f)
            val still = render(voice, "WATER" to 0f, "BEND" to 0.5f)
            val to = minOf(still.samples.size.toDouble() / rate - 0.3, 2.3)
            val (c0, d0) = series(still, hz, 0.3, to)
            val moved = listOf(0.05f, 0.2f, 1f).map { w ->
                val (c, d) = series(render(voice, "WATER" to w, "BEND" to 0.5f), hz, 0.3, to)
                spread(c, c0) to spread(d, d0)
            }
            println("MERCURY WATER $voice: 0.05 / 0.2 / 1 add " + moved.joinToString(" / ") { "${f(it.first, 1)} cents ${f(it.second, 1)} dB" })
            assertTrue(moved[0].first >= 8.0, "$voice: WATER 0.05 adds only ${f(moved[0].first, 1)} cents of drift")
            assertTrue(moved[0].second >= 1.2, "$voice: WATER 0.05 adds only ${f(moved[0].second, 1)} dB of swell")
            for (k in 1 until moved.size) {
                assertTrue(moved[k].first >= moved[k - 1].first, "$voice: more WATER drifts less (${moved.map { f(it.first, 1) }})")
                assertTrue(moved[k].second >= moved[k - 1].second, "$voice: more WATER swells less (${moved.map { f(it.second, 1) }})")
            }
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
     * 0.07 and the rub at 1.15 (SING), and 0.01 and 1.25 (BLADE). PING is the voice that is a tap by default and has its
     * own claims; the five that rub (R2c's EDDY, VESSEL and SHARD among them) are read here.
     */
    @Test
    fun `RUB turns a decaying tap into a sustained rub`() {
        for (voice in MercuryVoice.entries.filter { it != MercuryVoice.PING }) {
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
     * EDDY and VESSEL start at A2, and the bar is why: EDDY's bowl sat +5.3 cents sharp at F2, +3.3 at A2, so its root is A2.
     */
    @Test
    fun `the rub locks onto the note at the bottom of the range, at both ends of GLASS, coupled`() {
        for (voice in MercuryVoice.entries.filter { it != MercuryVoice.PING }) for (glass in listOf(0f, 1f)) {
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

    /**
     * HOLD is the contact's length: the render grows by exactly HOLD's seconds and nothing else moves its length. Its
     * top step is the LOOP (`MercuryLoopTest`), so the longest one-shot is HOLD .98. The expectation is R1's own mapping
     * (0.3 s to 4 s, exponential), written out here rather than read back through [Mercury.holdSeconds], so a rescaled
     * one-shot fails this test instead of moving with it.
     */
    @Test
    fun `HOLD sets the contact's length and GLASS the tail's`() {
        for (voice in MercuryVoice.entries) {
            val short = render(voice, "HOLD" to 0f).frameCount
            val long = render(voice, "HOLD" to 0.98f).frameCount
            val expected = (0.3 * Math.pow(4.0 / 0.3, 0.98) - 0.3) * rate
            assertTrue(abs((long - short) - expected) <= 2, "$voice: HOLD 0 to .98 added ${long - short} frames, expected $expected")
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

    /** Spectral centroid of the first 2048 samples (46 ms): the strike and the contact's first periods. */
    private fun onsetCentroid(snip: Snip): Double {
        val n = 2048
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until minOf(n, snip.samples.size)) re[i] = snip.samples[i] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * i / (n - 1)).toFloat())
        com.snipsnap.audio.Fft.forward(re, im)
        var num = 0.0
        var den = 0.0
        for (b in 1 until n / 2) {
            val m = kotlin.math.hypot(re[b].toDouble(), im[b].toDouble())
            num += m * b * rate / n
            den += m
        }
        return num / den
    }

    /** Seconds until the 10 ms RMS first reaches half its peak over the first 1.5 s: how fast the note comes in. */
    private fun halfRise(snip: Snip): Double {
        val w = rate / 100
        val levels = (0 until (1.5 * rate).toInt() / w).map { k -> rms(snip.samples, k * 0.01, (k + 1) * 0.01) }
        return levels.indexOfFirst { it >= 0.5 * levels.max() } * 0.01
    }

    /**
     * Velocity (decision 8), as the owner heard it (2026-10-02).
     *
     * The struck voices (PING, and R2c's VESSEL and SHARD, whose default is mostly a tap: [Mercury.velocityKind]) register
     * GLASS, which shortens the strike and tilts the pickup bright, after the house's sweep: the onset centroid rises at
     * every tenth of velocity's travel, and by at least 15% over all of it (PING 544 to 710 Hz, +31%). The owner heard
     * PING's and kept it.
     *
     * The rubbed ones (SING and BLADE, and R2c's EDDY) take velocity as the touch, the owner's choice of "attack and
     * bite". A rubbed body is close to a pure tone, so a harder touch is heard in how the note starts, not in brightness:
     * - the swell: the time to half level falls at every step from soft to hard, and the softest is at least three
     *   times the hardest (measured 0.35-0.38 s against 0.07-0.08 s);
     * - the bite: a hard touch catches with a scrape that rides the note's own swell. Round 3 levelled it against
     *   the held tone, so in the first tens of milliseconds, while the note was still coming in, it was 2-7.6 dB
     *   louder than the note, and the owner heard "a little snare or clap" (2026-10-03). So the claim is the gap
     *   itself: in every 5 ms of the first 80 ms, at velocity 1 and .65, the scrape is at least 14 dB under the note
     *   (measured: 15.9-16.1 dB at worst on the defaults, 14.8 dB on BLADE TAPPED STEEL, the closest), on the voice's
     *   defaults and on every one of its presets (the kit's A09-A16 among them).
     *   It is there: at full velocity its loudest 5 ms in the first 40 ms is within 22 dB of the note (measured 16).
     *   It is noise: the scrape alone is flat across its 0.4-1.5 kHz band. From 0.3 s on the scraped and unscraped
     *   notes are the same to the bit;
     * - and Velocity renders exactly that, with no macro moved and no soften.
     */
    @Test
    fun `velocity brightens the struck voices, and swells or bites the rubbed ones`() {
        for (voice in MercuryVoice.entries.filter { Mercury.velocityKind(it) == Mercury.VelocityKind.GLASS }) {
            val struck = MercuryPatch("Velocity", voice, Mercury.defaults(voice))
            assertEquals("GLASS", Velocity.brightnessSpec(struck)?.name, "$voice: velocity is not on GLASS")
            val centroids = (0..10).map { onsetCentroid(Velocity.atVelocity(struck, it / 10f)) }
            println("MERCURY velocity $voice onset centroid: " + centroids.joinToString(" ") { f(it, 0) })
            for (k in 1 until centroids.size) {
                assertTrue(centroids[k] >= centroids[k - 1], "$voice: velocity ${k / 10f} is darker than ${(k - 1) / 10f} (${centroids.map { f(it, 0) }})")
            }
            // PING's travel is +31% (the owner heard it and kept it). VESSEL's and SHARD's are lower objects with a longer
            // first 46 ms to fill, so the bar is the smallest that is still a monotone sweep a listener can follow.
            val bar = if (voice == MercuryVoice.PING) 1.15 else 1.08
            assertTrue(centroids.last() >= centroids.first() * bar, "$voice: velocity brightens the onset by only ${f(centroids.last() / centroids.first(), 3)}x (bar $bar)")
        }

        val raw = rate * Dsp.OVERSAMPLE
        for (voice in MercuryVoice.entries.filter { Mercury.velocityKind(it) == Mercury.VelocityKind.TOUCH }) {
            val macros = Mercury.defaults(voice)
            val patch = MercuryPatch("Velocity", voice, macros)
            assertEquals(null, Velocity.brightnessSpec(patch), "$voice: velocity has a brightness macro")
            assertContentEquals(Mercury.render(voice, macros, velocity = 0.3f).samples, Velocity.atVelocity(patch, 0.3f).samples, "$voice: Velocity does not render the touch")

            val rises = listOf(0f, 0.3f, 0.65f, 1f).map { halfRise(Mercury.render(voice, macros, velocity = it)) }
            println("MERCURY velocity $voice half-level rise at 0 / .3 / .65 / 1: " + rises.joinToString(" / ") { f(it, 2) } + " s")
            for (k in 1 until rises.size) assertTrue(rises[k] < rises[k - 1], "$voice: a harder touch does not come in faster ($rises)")
            assertTrue(rises.first() >= 3 * rises.last(), "$voice: the softest swell (${rises.first()} s) is not three times the hardest (${rises.last()} s)")

            val block = (0.005 * raw).toInt()
            fun blockRms(x: FloatArray, k: Int) = sqrt((k * block until (k + 1) * block).sumOf { x[it].toDouble() * x[it] } / block)
            val m0 = Mercury.settled(macros, voice)
            val hz0 = Mercury.frequencyFor(voice, m0.getValue("TUNE")).toDouble()

            // The held tone, to the bit, at the voice's own defaults and full length.
            val scrapedFull = Mercury.sound(voice, hz0, m0, 1.0, scrape = true)
            val cleanFull = Mercury.sound(voice, hz0, m0, 1.0, scrape = false)
            val after = (0.3 * raw).toInt()
            assertContentEquals(cleanFull.copyOfRange(after, cleanFull.size), scrapedFull.copyOfRange(after, scrapedFull.size), "$voice: the scrape reaches the held tone")
            // It is noise: the scrape alone, over its first 46 ms, is flat across its 0.4-1.5 kHz band.
            val alone = FloatArray(scrapedFull.size) { scrapedFull[it] - cleanFull[it] }
            val flat = flatness(alone, raw, 0, 8192, 400.0, 1500.0)
            println("MERCURY velocity $voice scrape alone: 0.4-1.5 kHz flatness ${f(flat, 3)}")
            assertTrue(flat >= 0.3, "$voice: the scrape is not noise (flatness ${f(flat, 3)})")
            // The gap is read with HOLD 0: the contact lasts at least 0.3 s whatever HOLD is, so the first 80 ms do
            // not depend on it, and a short render keeps 18 cases quick. Shown once here, to the bit.
            val shortHold = Mercury.sound(voice, hz0, Mercury.settled(macros + ("HOLD" to 0f), voice), 1.0, scrape = true)
            val window = (0.08 * raw).toInt()
            assertContentEquals(scrapedFull.copyOfRange(0, window), shortHold.copyOfRange(0, window), "$voice: HOLD moves the first 80 ms")

            // The gap, on the defaults and on every preset of the voice (the kit's A09-A16 are four of each).
            val cases = listOf("defaults" to macros) + MercuryPresets.forVoice(voice).map { it.name to it.macros }
            for ((name, preset) in cases) {
                val m = Mercury.settled(preset + ("HOLD" to 0f), voice)
                val hz = Mercury.frequencyFor(voice, m.getValue("TUNE")).toDouble()
                for (touch in listOf(1.0, 0.65)) {
                    val scraped = Mercury.sound(voice, hz, m, touch, scrape = true)
                    val clean = Mercury.sound(voice, hz, m, touch, scrape = false)
                    val scrape = FloatArray(scraped.size) { scraped[it] - clean[it] }
                    val gaps = (0 until 16).map { k -> 20 * kotlin.math.log10(blockRms(scrape, k) / blockRms(clean, k)) }
                    println("MERCURY velocity $voice $name $touch: scrape under the note, worst ${f(gaps.max(), 1)} dB")
                    assertTrue(gaps.all { it <= -14.0 }, "$voice $name: at velocity $touch the scrape comes within 14 dB of the note (${gaps.map { f(it, 1) }})")
                    if (touch == 1.0) assertTrue(gaps.take(8).max() >= -22.0, "$voice $name: the scrape is not there (${gaps.map { f(it, 1) }})")
                }
            }
        }
    }

    /** Spectral flatness (geometric over arithmetic mean power) between [lo] and [hi] Hz of [n] samples from [from]: 1 is noise, 0 is pure tones. */
    private fun flatness(x: FloatArray, sampleRate: Int, from: Int, n: Int, lo: Double, hi: Double): Double {
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) re[i] = (if (from + i < x.size) x[from + i] else 0f) * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * i / (n - 1)).toFloat())
        com.snipsnap.audio.Fft.forward(re, im)
        val p = (0 until n / 2).filter { it.toDouble() * sampleRate / n in lo..hi }.map { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] + 1e-30 }
        return kotlin.math.exp(p.sumOf { kotlin.math.ln(it) } / p.size) / p.average()
    }
}
