package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ARCO's product claims: what a player gets, not what the string does (docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md).
 * The finished 44.1 kHz render, the length of a note, the LOOP, the recipe. Every bound is on a number R1b's
 * own probe *saw* (or R1c's, where the KDoc says R1c: the box's curve and the vibrato's shape are R1c's), with room, and the KDoc of the test says what the number was, so a bound that moves can be
 * read against the measurement it came from. The tables a bound was set from are printed, one line each
 * starting `ARCO `, so the next person reads them from the log and not from a guess.
 *
 * Where a test could pass whatever the engine did, it carries its own negative control: the same measure
 * on a case that must fail (a loop cut short, a note with noise added, a knob that is not there), so
 * "it passed" can only mean the engine is right.
 */
class ArcoProductTest {

    private val rate = Dsp.RATE
    private val rawRate = Dsp.RATE * Dsp.OVERSAMPLE

    /** Root, middle and top of the knob. Not three notes of one voice: CELLO's are C2 C3 C4, ERHU's D4 C5 A5. */
    private val threeTunes = listOf(0f, 0.5f, 1f)

    private fun macros(
        voice: ArcoVoice,
        tune: Float = 0.5f,
        bow: Float = Arco.DEFAULT_BOW,
        grip: Float = Arco.DEFAULT_GRIP,
        body: Float = Arco.DEFAULT_BODY,
        hold: Float = Arco.DEFAULT_HOLD,
    ) = Arco.defaults(voice) + mapOf("TUNE" to tune, "BOW" to bow, "GRIP" to grip, "BODY" to body, "HOLD" to hold)

    /** The TUNE value that lands exactly on [step] semitones above the voice's root. */
    private fun tuneOf(voice: ArcoVoice, step: Int) = step / Arco.tuneSemitones(voice).toFloat()

    private fun label(voice: ArcoVoice, tune: Float) = "$voice ${Arco.midiFor(voice, tune)}"

    private fun f(v: Double, digits: Int = 2) = "%.${digits}f".format(java.util.Locale.ROOT, v)

    private fun <T, R> List<T>.parMap(transform: (T) -> R): List<R> = parallelStream().map { transform(it) }.toList()

    // ---- spectral helpers (ForkTest's own, private there, so private here) ----------------------

    /** A windowed FFT's power at [start]..[start]+[n), Blackman-Harris, zero past the end of [samples]. */
    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    /** Energy within 8 Hz of a harmonic of [f0] against everything else from f0/2 to 20 kHz, in dB: the TIDE and SIREN aliasing bar. */
    private fun harmonicClarity(samples: FloatArray, f0: Float, start: Int, n: Int = 1 shl 16): Double {
        val mag = magnitudes(samples, start, n)
        var on = 0.0
        var off = 0.0
        for (b in 1 until mag.size) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            if (abs(hz - k * f0) < 8f) on += mag[b] else off += mag[b]
        }
        return 10 * log10(on / off)
    }

    /** The energy-weighted mean frequency over the window. */
    private fun centroid(samples: FloatArray, start: Int, n: Int): Double {
        val mag = magnitudes(samples, start, n)
        var num = 0.0
        var den = 0.0
        for (b in mag.indices) {
            val hz = b.toFloat() * rate / n
            num += hz * mag[b]
            den += mag[b]
        }
        return if (den > 0) num / den else 0.0
    }

    private fun steepestStep(x: FloatArray, fromSeconds: Double, toSeconds: Double): Float {
        var m = 0f
        val from = (fromSeconds * rate).toInt().coerceAtLeast(1)
        val to = minOf(x.size, (toSeconds * rate).toInt())
        for (i in from until to) m = maxOf(m, abs(x[i] - x[i - 1]))
        return m
    }

    /** RMS of the difference over RMS of [a], for arrays of one length or not (the longer one's tail counts as a difference). */
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

    /** Pitch in cents from [hz] on [window]-second windows stepped [hop] seconds from [from] to [to], read with [BoreMeasure] at the finished rate. */
    private fun centsOver(x: FloatArray, hz: Float, from: Float, to: Float, window: Float = 0.07f, hop: Float = 0.02f): List<Double> {
        val out = ArrayList<Double>()
        var t = from
        while (t + window <= to) {
            out += BoreMeasure.cents(x, hz, t, t + window, rate)
            t += hop
        }
        return out
    }

    /** The core through the same box and finish as [Arco.render], with the baked vibrato switched off if asked: the reference a vibrato is read against. */
    private fun finishedCore(voice: ArcoVoice, m: Map<String, Float>, vibrato: Boolean = true, bowPoint: FloatArray? = null): FloatArray {
        val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
        val raw = Arco.bow(voice, hz, m, rawRate, vibrato = vibrato, bowPointOut = bowPoint)
        return Arco.finished(raw, voice, m.getValue("BODY"), rawRate)
    }

    // ---- TUNE ---------------------------------------------------------------

    /**
     * Each voice's knob is its own span of semitones from its own root: CELLO C2 to C4 (24), ERHU D4 to A5 (19, not
     * the two octaves the other voices have: R1b's maps found no cell that slips once a period above B5, and stopped two
     * semitones short of that edge). The checks are on the arithmetic, so the bounds are exact, except where the
     * note's own frequency is compared with its name (a hundredth of a hertz, the rounding of the names).
     * 0.04 rounds to one semitone on both spans (0.96 of one on CELLO's, 0.76 of one on ERHU's) and 0.02 to none
     * (0.48, 0.38): the pair is the control, a knob that always moved one step would pass the first alone.
     * ERHU's middle, 0.5, is 9.5 semitones and rounds up to the tenth, C5: its default note is not D5.
     */
    @Test
    fun `TUNE snaps to semitones over each voice's own span`() {
        assertEquals(36, Arco.CELLO_ROOT_MIDI)
        assertEquals(62, Arco.ERHU_ROOT_MIDI)
        assertEquals(24, Arco.tuneSemitones(ArcoVoice.CELLO))
        assertEquals(19, Arco.tuneSemitones(ArcoVoice.ERHU))
        assertEquals(36, Arco.rootMidi(ArcoVoice.CELLO))
        assertEquals(62, Arco.rootMidi(ArcoVoice.ERHU))
        for (voice in ArcoVoice.entries) {
            val root = Arco.rootMidi(voice)
            val span = Arco.tuneSemitones(voice)
            assertEquals(root, Arco.midiFor(voice, 0f))
            assertEquals(root + span, Arco.midiFor(voice, 1f))
            assertEquals(root + 1, Arco.midiFor(voice, 0.04f), "$voice: 0.04 of the span rounds to one semitone")
            assertEquals(root, Arco.midiFor(voice, 0.02f), "$voice: 0.02 of the span rounds to none")
            assertEquals(root, Arco.midiFor(voice, -2f), "$voice: TUNE below 0 is the root")
            assertEquals(root + span, Arco.midiFor(voice, 7f), "$voice: TUNE above 1 is the top")
            for (step in 0..span) {
                val tune = tuneOf(voice, step)
                assertEquals(root + step, Arco.midiFor(voice, tune), "$voice step $step")
                assertEquals(Keys.midiHz(root + step), Arco.frequencyFor(voice, tune), "$voice step $step: frequencyFor is not the note's Hz")
                assertEquals(root + step, Arco.midiFor(voice, tune + 0.25f / span), "$voice step $step: a quarter step up moved the note")
                assertEquals(root + step, Arco.midiFor(voice, tune - 0.25f / span), "$voice step $step: a quarter step down moved the note")
                if (step > 0) {
                    val ratio = Arco.frequencyFor(voice, tune) / Arco.frequencyFor(voice, tuneOf(voice, step - 1))
                    assertTrue(abs(ratio - 2f.pow(1f / 12)) < 1e-4f, "$voice step $step: neighbours are a factor $ratio apart, not a semitone")
                }
            }
            val octave = Arco.frequencyFor(voice, tuneOf(voice, 12)) / Arco.frequencyFor(voice, 0f)
            assertTrue(abs(octave - 2f) < 1e-3f, "$voice: an octave of TUNE is not a factor of two: $octave")
        }
        assertEquals(36 + 12, Arco.midiFor(ArcoVoice.CELLO, 0.5f), "CELLO's default note is C3")
        assertEquals(62 + 10, Arco.midiFor(ArcoVoice.ERHU, 0.5f), "ERHU's default note is C5: 9.5 semitones round up")
        assertEquals(65.41f, Arco.frequencyFor(ArcoVoice.CELLO, 0f), 0.01f)
        assertEquals(261.63f, Arco.frequencyFor(ArcoVoice.CELLO, 1f), 0.01f)
        assertEquals(293.66f, Arco.frequencyFor(ArcoVoice.ERHU, 0f), 0.01f)
        assertEquals(880f, Arco.frequencyFor(ArcoVoice.ERHU, 1f), 0.01f)
    }

    // ---- HOLD and the length of a note --------------------------------------

    /**
     * HOLD is the bow-on time, `0.3 s * (4 / 0.3) ^ (HOLD / 0.99)`: 0.3 s at 0, 4 s just under the LOOP step, which is
     * the top 0.99 of the knob (SIREN's own numbers). The held-note audition's three seconds is 0.88: [Arco.holdFor] is the
     * inverse, and the round trip is held to a hundredth of a second.
     */
    @Test
    fun `HOLD sets the length of the note, and its top step is a loop`() {
        assertEquals(Arco.HOLD_MIN_SECONDS, Arco.holdSeconds(0f), 1e-4f)
        assertTrue(abs(Arco.holdSeconds(Arco.LOOP_THRESHOLD - 1e-4f) - Arco.HOLD_MAX_SECONDS) < 0.02f)
        val steps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 0.98f).map { Arco.holdSeconds(it) }
        assertEquals(steps.sorted(), steps, "HOLD is not monotonic: $steps")
        assertEquals(steps.distinct(), steps, "HOLD has a flat step: $steps")
        assertTrue(!Arco.isLoop(0.98f), "0.98 is a note")
        assertTrue(Arco.isLoop(0.99f), "0.99 is the LOOP step")
        assertTrue(Arco.isLoop(1f))
        val held = Arco.holdFor(3f)
        println("ARCO holdFor(3 s) = ${f(held.toDouble(), 4)}, holdSeconds back = ${f(Arco.holdSeconds(held).toDouble(), 4)}")
        assertEquals(3f, Arco.holdSeconds(held), 0.01f, "holdFor is not holdSeconds' inverse")
    }

    /**
     * The one place a note's length is worked out serves [Arco.bow] and [Arco.renderFrames] alike, and the
     * pad's filed class is read off the second, so it has to be the frame count of a real render, to the frame:
     * through the decimator's floored 2:1 steps and the box's cut. R1b printed 24 cases equal; this is 60 (two
     * voices, three TUNEs, ten HOLDs from the shortest note to a 3.6 s one), every one compared with a real render.
     */
    @Test
    fun `the predicted frame count is what a render makes - every voice, three TUNEs, ten HOLDs`() {
        val holds = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.85f, 0.95f)
        val cases = ArcoVoice.entries.flatMap { v -> threeTunes.flatMap { t -> holds.map { h -> Triple(v, t, h) } } }
        val rows = cases.parMap { (voice, tune, hold) ->
            val m = macros(voice, tune, hold = hold)
            Triple("${label(voice, tune)} HOLD $hold", Arco.renderFrames(voice, m), Arco.render(voice, m).frameCount)
        }
        for ((name, predicted, real) in rows) println("ARCO frames $name: predicted $predicted rendered $real (${f(real.toDouble() / rate, 3)} s)")
        for ((name, predicted, real) in rows) assertEquals(real, predicted, "$name: the predicted frame count is not what a render makes")
    }

    /**
     * The default HOLD (0.4, 0.85 s of bow) is a note and not a LOOP at every TUNE step of both voices: a pad that
     * lands on the default and files itself LOOP would loop a stab. R1b saw the default render 1.332 s at every CELLO step
     * and 1.054 to 1.300 s at ERHU, so under the classifier's 1.5 s line with 0.17 s to spare at the worst step. The
     * negative control is the same sweep at HOLD 0.8 (2.2 s of bow): it must be over the line at every
     * step, so the line is one this sweep can cross.
     */
    @Test
    fun `the default HOLD renders a note under the classifier's line at every TUNE step of both voices`() {
        for (voice in ArcoVoice.entries) {
            val steps = (0..Arco.tuneSemitones(voice)).toList()
            val rows = steps.parMap { step ->
                val m = macros(voice, tuneOf(voice, step))
                val snip = Arco.render(voice, m)
                Triple(step, snip, Classifier.classify(snip).drumClass)
            }
            val seconds = rows.map { it.second.frameCount.toDouble() / rate }
            println("ARCO default HOLD $voice: ${f(seconds.min(), 3)} .. ${f(seconds.max(), 3)} s over ${steps.size} steps (the line is 1.5)")
            for ((step, snip, heard) in rows) {
                val name = "$voice step $step"
                assertTrue(snip.frameCount.toFloat() / snip.sampleRate <= 1.5f, "$name: the default HOLD renders ${snip.frameCount.toFloat() / snip.sampleRate} s, past the LOOP line")
                assertEquals(DrumClass.PERC, Arco.drumClassFor(voice, macros(voice, tuneOf(voice, step))), "$name: the default is not filed PERC")
                assertNotEquals(DrumClass.LOOP, heard, "$name: the classifier reads the default note as a LOOP")
            }
            for (step in steps) {
                val m = macros(voice, tuneOf(voice, step), hold = 0.8f)
                assertTrue(Arco.renderFrames(voice, m).toFloat() / rate > 1.5f, "$voice step $step: HOLD 0.8 is not over the line, so the sweep above cannot fail")
                assertEquals(DrumClass.LOOP, Arco.drumClassFor(voice, m), "$voice step $step: HOLD 0.8 is not filed LOOP")
            }
        }
    }

    /**
     * BODY is the box's ring and must not make a note longer: [Strings.bodyRing] follows the box out past the string's
     * end (CELLO's box adds 0.254 s, ERHU's as dressed 0.3 s), which once rendered the default CELLO 1.585 s and
     * filed it a LOOP, so [Arco.withBody] cuts the ring where the stopped string ends. BODY 0, 0.5, 0.75 (past the knee, where the box stays at the knee's size and BODY adds the lift) and 1 render the same number of frames at three TUNEs of each voice and three HOLDs, and the notes themselves differ (the control: if BODY
     * did nothing the frame count could not be the evidence of anything). R1c saw BODY 0 against 1 differ by 0.44 (ERHU D4 at HOLD 0.6) to 1.05
     * (CELLO C2 at HOLD 0) of the note (R1b saw 0.35 to 0.88, with a box only as loud as the string at BODY 1), and the bar is 0.2. R1g saw the frame counts equal at all four BODY values in all 18 cells and BODY 0 against 1 differ by 0.73 (CELLO C2 at HOLD 0.6) to 1.66 (ERHU A5 at HOLD 0.4) of the note,
     * larger than R1c's because the lift is added at the plain's own gain and is not levelled away; the bar is unchanged.
     */
    @Test
    fun `BODY does not change the length of a note`() {
        val cases = ArcoVoice.entries.flatMap { v -> threeTunes.flatMap { t -> listOf(0f, Arco.DEFAULT_HOLD, 0.6f).map { h -> Triple(v, t, h) } } }
        val rows = cases.parMap { (voice, tune, hold) ->
            val renders = listOf(0f, 0.5f, 0.75f, 1f).map { body -> Arco.render(voice, macros(voice, tune, body = body, hold = hold)) }
            Triple("${label(voice, tune)} HOLD $hold", renders.map { it.frameCount }, relativeDifference(renders[0].samples, renders[3].samples))
        }
        for ((name, frames, diff) in rows) {
            println("ARCO body length $name: frames at BODY 0/0.5/0.75/1 = $frames, BODY 0 against 1 differ by ${f(diff, 3)} of the note")
            assertEquals(1, frames.distinct().size, "$name: BODY changed the length: $frames")
            assertTrue(diff > 0.2, "$name: BODY 0 and 1 are nearly the same note (${f(diff, 4)}), so the length check proves nothing")
        }
    }

    // ---- BODY's curve (R1c) ------------------------------------------------------

    /** What [Arco.withBody] is given: a voice's raw string at one TUNE and the default knobs, before the box, with no vibrato. */
    private fun rawString(voice: ArcoVoice, tune: Float): FloatArray =
        Arco.bow(voice, Arco.frequencyFor(voice, tune), macros(voice, tune), rawRate, vibrato = false)

    private fun rmsOf(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** The box's RMS over the string's: the RMS of `boxed - raw` over the string's length against the RMS of [raw]. */
    private fun boxOverString(raw: FloatArray, boxed: FloatArray): Double = rmsOf(FloatArray(raw.size) { boxed[it] - raw[it] }) / rmsOf(raw)

    /**
     * The curve of BODY, in its two halves. The box ([Arco.boxAmountFor]: how many strings the box rings at) is the identity up to [Arco.BODY_KNEE] 0.5 (equal to its argument exactly at 0, 0.1, 0.25 and 0.5, so the default BODY and every
     * preset at or under it are what they were) and frozen at the knee's size above it: `min(BODY, 0.5)` at 0, 0.1, 0.25, 0.5, 0.75 and 1, with no step over the knee (the value a hair over 0.5 and a hair under it are both within 1e-5 of 0.5).
     * R1c climbed it to 1.75 at BODY 1 (BODY 0.75 was 1.125) and the level step after the box took the climb back (the owner then wrote "Body doesn't seem to do anything"); R1g froze it, and what BODY adds above the knee is the lift.
     * The lift ([Arco.liftDbFor], dB for the shelf and the same again for the bell) is 0 at and under the knee, 3.5 dB at BODY 0.75 (R1e's small rung, within 0.02), 6.0 at BODY 1 (R1e's large rung, within 0.001), 1.52 at BODY 0.6 (within 0.02),
     * and the one quadratic through them: 0.78 at 0.55, 2.22 at 0.65, 2.88 at 0.7, 4.08 at 0.8, 5.12 at 0.9 and 5.58 at 0.95, each within 0.02. It is strictly increasing in hundredths of BODY, has no step at the knee (a hair over 0.5 is within
     * 1e-3 dB of 0), and keeps rising as long as 3 T is more than 4 H, which the constants satisfy (18 against 14).
     * R1f's design saw those values; the bound on each is the tolerance beside it. The controls run the very predicate they protect: the frozen-box check and the strictly-increasing check are each one local function, called on the engine (which must pass it) and on a
     * control (which must fail it). The frozen-box predicate is called on R1c's climbed box (BODY 0.75 at 1.125 times the string, 1.75 at BODY 1), which it must flag at every hundredth of BODY over the knee, and the strictly-increasing predicate on the same
     * quadratic with 4 H over 3 T (H 5.0, T 6.0), which turns over before BODY 1 and which it must flag; a third call on the quadratic with the engine's own constants must agree with the engine to 1e-5 dB and pass, so the control's quadratic is the engine's curve and not another one.
     * R1g saw the engine pass both and both controls fail them: R1c's box flagged at all 50 hundredths over the knee, the H 5.0 quadratic not rising at 6 steps of BODY, the first from 0.94 to 0.95.
     */
    @Test
    fun `the box's curve is the identity to its knee and frozen above it, and the lift's curve rises from nothing at the knee to its two stops`() {
        assertEquals(0.5f, Arco.BODY_KNEE)
        for (body in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) assertEquals(minOf(body, 0.5f), Arco.boxAmountFor(body), "BODY $body: the box is not min(BODY, the knee)")
        assertTrue(abs(Arco.boxAmountFor(0.5000001f) - 0.5f) < 1e-5f, "the curve jumps over the knee: ${Arco.boxAmountFor(0.5000001f)}")
        assertTrue(abs(Arco.boxAmountFor(0.4999999f) - 0.5f) < 1e-5f, "the curve jumps under the knee: ${Arco.boxAmountFor(0.4999999f)}")
        val hundredths = (0..100).map { it / 100f }
        // The two predicates the curve is held to, written once each and called on the engine and on its control, so a control can only fail by the very check that guards the engine.
        /** The hundredths of BODY over the knee at which [box] is not the knee's own 0.5: empty means the box is frozen above the knee. */
        fun thawed(box: (Float) -> Float): List<Float> = hundredths.filter { it > 0.5f && box(it) != 0.5f }
        /** The steps (from, to) of hundredths of BODY from the knee up over which [lift] does not rise: empty means strictly increasing from the knee to BODY 1. */
        fun notRising(lift: (Float) -> Float): List<Pair<Float, Float>> = hundredths.filter { it >= 0.5f }.zipWithNext().filter { (a, b) -> lift(b) <= lift(a) }

        for ((a, b) in hundredths.zipWithNext()) if (b <= 0.5f) assertTrue(Arco.boxAmountFor(b) > Arco.boxAmountFor(a), "the box does not get louder from BODY $a to $b, under the knee")
        assertEquals(emptyList(), thawed { Arco.boxAmountFor(it) }, "the box is not frozen at the knee's 0.5 at these BODY values over the knee")
        // The control for the box: R1c's own curve (the knob's value to the knee, then straight to R1C_BODY_TOP times the string at BODY 1) through the same predicate must be flagged at every hundredth over the knee.
        fun r1cBox(body: Float) = if (body <= 0.5f) body else 0.5f + (body - 0.5f) * (ArcoBodyCandidates.R1C_BODY_TOP - 0.5f) / (1f - 0.5f)
        assertEquals(1.125f, r1cBox(0.75f), 1e-6f)
        assertEquals(1.75f, r1cBox(1f), 1e-6f)
        val climbed = thawed { r1cBox(it) }
        println("ARCO BODY curve control: R1c's box reads ${r1cBox(0.75f)} at BODY 0.75 and ${r1cBox(1f)} at 1 against the engine's ${Arco.boxAmountFor(0.75f)} and ${Arco.boxAmountFor(1f)}; the frozen-box check flags R1c's curve at ${climbed.size} of the 50 hundredths over the knee")
        assertEquals(50, climbed.size, "the frozen-box check does not flag R1c's climbed box at every BODY over the knee, so it cannot see a climb")

        val expected = listOf(0.55f to 0.78f, 0.6f to 1.52f, 0.65f to 2.22f, 0.7f to 2.88f, 0.75f to 3.5f, 0.8f to 4.08f, 0.9f to 5.12f, 0.95f to 5.58f, 1f to 6f)
        println("ARCO BODY curve, the box at BODY 0 0.25 0.5 0.75 1 (times the string): ${listOf(0f, 0.25f, 0.5f, 0.75f, 1f).joinToString(" ") { f(Arco.boxAmountFor(it).toDouble(), 3) }}")
        println("ARCO BODY curve, the lift in dB per element at BODY ${expected.joinToString(" ") { (b, _) -> "$b: ${f(Arco.liftDbFor(b).toDouble(), 3)}" }}")
        for (body in listOf(0f, 0.1f, 0.25f, 0.5f)) assertEquals(0f, Arco.liftDbFor(body), "BODY $body: the lift is not nothing at or under the knee")
        for ((body, db) in expected) assertEquals(db, Arco.liftDbFor(body), 0.02f, "BODY $body: the lift is ${Arco.liftDbFor(body)} dB per element, R1f's table says $db")
        assertEquals(Arco.LIFT_HALF_DB, Arco.liftDbFor(0.75f), 0.02f, "BODY 0.75 is not R1e's small rung")
        assertEquals(Arco.LIFT_TOP_DB, Arco.liftDbFor(1f), 0.001f, "BODY 1 is not R1e's large rung")
        assertEquals(emptyList(), notRising { Arco.liftDbFor(it) }, "the lift does not rise at these steps of BODY, from the knee to BODY 1")
        assertTrue(abs(Arco.liftDbFor(0.5000001f)) < 1e-3f, "the lift jumps over the knee: ${Arco.liftDbFor(0.5000001f)}")
        assertTrue(3f * Arco.LIFT_TOP_DB > 4f * Arco.LIFT_HALF_DB, "3 T is not more than 4 H, so the lift's curve turns over before BODY 1")

        // The control: the same quadratic through (0, 0), (0.5, H) and (1, T). With the engine's own H and T it must agree with the engine and pass the strictly-increasing predicate (so it is the engine's curve); with H 5.0 and T 6.0 (4 H over 3 T) it turns over, and the same predicate must flag it.
        fun quadratic(h: Float, t: Float): (Float) -> Float = { body -> if (body <= 0.5f) 0f else (4f * h - t) * ((body - 0.5f) / 0.5f) + (2f * t - 4f * h) * ((body - 0.5f) / 0.5f) * ((body - 0.5f) / 0.5f) }
        val same = quadratic(Arco.LIFT_HALF_DB, Arco.LIFT_TOP_DB)
        for (body in hundredths) assertEquals(Arco.liftDbFor(body), same(body), 1e-5f, "BODY $body: the control's quadratic is not the engine's")
        assertEquals(emptyList(), notRising(same), "the control's quadratic with the engine's own H and T does not rise: it is not the engine's curve")
        val turned = notRising(quadratic(5f, 6f))
        println("ARCO BODY curve control: the lift with H 5.0 and T 6.0 does not rise at ${turned.size} steps of BODY, the first from ${turned.firstOrNull()?.first} to ${turned.firstOrNull()?.second}")
        assertTrue(turned.isNotEmpty(), "the control with 4 H over 3 T does not turn over, so the strictly-increasing check could not fail")
    }

    private class BoxRow(
        val name: String, val dryIsString: Boolean, val quarterIsR1b: Boolean, val halfIsR1b: Boolean, val frozen: Boolean,
        val half: Double, val threeQuarters: Double, val one: Double, val r1cTop: Double,
    )

    /**
     * [Arco.withBody] rings the box at [Arco.boxAmountFor] of BODY, and up to the knee that is the call R1b made. At CELLO's and ERHU's root, middle and top, on the raw string at the default knobs: BODY 0 hands back the string's contents unchanged
     * (and does not touch the string), and BODY 0.25 and 0.5 are, sample for sample, R1b's call, `Strings.bodyRing(raw, bodyFor(voice), BODY, rate, BODY_CEILING_SECONDS)` cut to the string's length, so the default and every preset at or under 0.5
     * render as they did. Above the knee the box is frozen at the size it has there: [Arco.withBody] at BODY 0.75 and at BODY 1 is [Arco.withBody] at BODY 0.5 to the sample, and the box's RMS over the string's, which `bodyRing` makes the amount it is
     * given, is 0.50 at BODY 0.5, 0.75 and 1, each held to 0.50 within 0.02 (R1c saw 0.50 at BODY 0.5 and 1.125 and 1.75 at 0.75 and 1; R1b's own call at BODY 1 read 1.0). The control is R1c's climb: the same measure on `bodyRing` rung at R1c's
     * 1.75 reads 1.75 within 0.05, over 1.2, so a box that climbed again fails the 0.50 bar by more than a factor of three. R1g saw the lift, not the box, carry BODY above the knee: the box at BODY 0.75 and 1 is bit for bit the one at 0.5.
     */
    @Test
    fun `the box rings at the curve's amount, exactly as it did up to the knee and frozen at the knee's size above it`() {
        val rows = ArcoVoice.entries.flatMap { v -> threeTunes.map { v to it } }.parMap { (voice, tune) ->
            val raw = rawString(voice, tune)
            val before = raw.copyOf()
            fun r1b(body: Float): FloatArray {
                val rung = Strings.bodyRing(raw, Arco.bodyFor(voice), body, rawRate, Arco.BODY_CEILING_SECONDS)
                return if (rung.size == raw.size) rung else rung.copyOf(raw.size)
            }
            val atKnee = Arco.withBody(raw, voice, 0.5f, rawRate)
            BoxRow(
                label(voice, tune),
                dryIsString = Arco.withBody(raw, voice, 0f, rawRate).contentEquals(before) && raw.contentEquals(before),
                quarterIsR1b = r1b(0.25f).contentEquals(Arco.withBody(raw, voice, 0.25f, rawRate)),
                halfIsR1b = r1b(0.5f).contentEquals(atKnee),
                frozen = atKnee.contentEquals(Arco.withBody(raw, voice, 0.75f, rawRate)) && atKnee.contentEquals(Arco.withBody(raw, voice, 1f, rawRate)),
                half = boxOverString(raw, atKnee),
                threeQuarters = boxOverString(raw, Arco.withBody(raw, voice, 0.75f, rawRate)),
                one = boxOverString(raw, Arco.withBody(raw, voice, 1f, rawRate)),
                r1cTop = boxOverString(raw, r1b(ArcoBodyCandidates.R1C_BODY_TOP)),
            )
        }
        for (r in rows) {
            println(
                "ARCO box ${r.name}: BODY 0 is the string ${r.dryIsString}, BODY 0.25 is R1b's ${r.quarterIsR1b}, BODY 0.5 is R1b's ${r.halfIsR1b}, BODY 0.75 and 1 are BODY 0.5's ${r.frozen}; " +
                    "box over string at BODY 0.5 / 0.75 / 1: ${f(r.half, 3)} ${f(r.threeQuarters, 3)} ${f(r.one, 3)}, R1c's top ${f(r.r1cTop, 3)}",
            )
            assertTrue(r.dryIsString, "${r.name}: BODY 0 is not the string's contents")
            assertTrue(r.quarterIsR1b, "${r.name}: BODY 0.25 is not what R1b's call made")
            assertTrue(r.halfIsR1b, "${r.name}: BODY 0.5 is not what R1b's call made")
            assertTrue(r.frozen, "${r.name}: the box at BODY 0.75 or 1 is not the box at BODY 0.5, to the sample")
            assertEquals(0.5, r.half, 0.02, "${r.name}: the box at BODY 0.5 is ${f(r.half, 3)} times the string")
            assertEquals(0.5, r.threeQuarters, 0.02, "${r.name}: the box at BODY 0.75 is ${f(r.threeQuarters, 3)} times the string, not the frozen 0.50")
            assertEquals(0.5, r.one, 0.02, "${r.name}: the box at BODY 1 is ${f(r.one, 3)} times the string, not the frozen 0.50")
            assertEquals(1.75, r.r1cTop, 0.05, "${r.name}: the control's R1c top reads ${f(r.r1cTop, 3)} times the string, not 1.75")
            assertTrue(r.r1cTop > 1.2, "${r.name}: R1c's climb reads ${f(r.r1cTop, 3)}, under 1.2, so the 0.50 bar cannot tell a climbed box from the frozen one")
        }
    }

    /** The finished peak bar at BODY 1: [Dsp.levelTo] turns a peak down only past 0.99, so a limited note sits at exactly 0.99 and fails 0.95, where a bar at 0.99 could not fail. */
    private val bodyPeakBar = Arco.FINISHED_PEAK_BAR

    /**
     * Nothing clips or goes non-finite at BODY 1: every TUNE step of both voices (25 and 20), defaults otherwise, through the whole render. Every sample is finite
     * and the peak is at most [bodyPeakBar], the bar ArcoTest's grid holds the finished peak to. R1c saw the highest finished peaks at 0.577 (CELLO, step 1) and
     * 0.521 (ERHU, step 4), 0.37 and 0.43 under the bar, all samples finite (the grid's 162 cells a voice in ArcoTest, which take BODY at 0, 0.5 and 1, read at most 0.761 at ERHU A5
     * with the louder box), so the bar had room and a limited note, which sits at 0.99, would fail it. R1g saw the highest finished peak at 0.940 (CELLO, on the cap: several of the lowest steps tie to three places, step 3 printed) and 0.871 (ERHU, step 3), all samples finite, which is 0.01 and 0.079 under
     * the bar: at BODY 1 the bar has 0.01 of room at the capped CELLO steps, because the lift is capped to leave exactly that much ([Arco.PEAK_CAP] is the bar less 0.01), which is why it is capped; the bound is a peak at most [bodyPeakBar] and a limited note at 0.99 would still fail it.
     */
    @Test
    fun `at BODY 1 nothing clips or goes non-finite, at any TUNE step of either voice`() {
        for (voice in ArcoVoice.entries) {
            val steps = (0..Arco.tuneSemitones(voice)).toList()
            val rows = steps.parMap { step ->
                val snip = Arco.render(voice, macros(voice, tuneOf(voice, step), body = 1f))
                Triple(step, snip.samples.all { it.isFinite() }, snip.peak())
            }
            val worst = rows.maxByOrNull { it.third }!!
            println("ARCO BODY 1 $voice: ${rows.size} steps, finite ${rows.all { it.second }}, highest finished peak ${f(worst.third.toDouble(), 3)} at step ${worst.first} (bar $bodyPeakBar)")
            for ((step, finite, peak) in rows) {
                assertTrue(finite, "$voice step $step at BODY 1: not finite")
                assertTrue(peak <= bodyPeakBar, "$voice step $step at BODY 1: finished peak $peak, over $bodyPeakBar")
            }
        }
    }

    // ---- the classifier ------------------------------------------------------

    /**
     * The HOLD at which a note's length crosses the classifier's 1.5 s line, found on the predicted frame count (which is
     * arithmetic, so a bisection costs nothing): the last HOLD under it and the first over it, to the float's last bit.
     * The line is not one HOLD. R1b measured it crossed at 0.4472 at C2 and C3, 0.4647 at C4, 0.4805 at D4,
     * 0.5497 at C5 and 0.5604 at A5: every TUNE has its own.
     */
    private fun lineHold(voice: ArcoVoice, tune: Float): Pair<Float, Float> {
        fun over(h: Float) = Arco.renderFrames(voice, macros(voice, tune, hold = h)).toFloat() / rate > 1.5f
        var lo = 0.3f
        var hi = 0.7f
        assertTrue(!over(lo) && over(hi), "${label(voice, tune)}: HOLD $lo..$hi does not straddle the line")
        while (true) {
            val mid = lo + (hi - lo) / 2
            if (mid == lo || mid == hi) break
            if (over(mid)) hi = mid else lo = mid
        }
        return lo to hi
    }

    private class LineRow(val name: String, val hold: Float, val frames: Int, val predicted: Int, val filed: DrumClass, val heard: DrumClass)

    private fun lineRow(voice: ArcoVoice, tune: Float, hold: Float): LineRow {
        val m = macros(voice, tune, hold = hold)
        val snip = Arco.render(voice, m)
        return LineRow("${label(voice, tune)} HOLD $hold", hold, snip.frameCount, Arco.renderFrames(voice, m), Arco.drumClassFor(voice, m), Classifier.classify(snip).drumClass)
    }

    /**
     * The classifier calls a render a LOOP when `frameCount / sampleRate > 1.5f`, and the filed class has to make the same
     * comparison on the same frame count (not on a duration with padding: BORE's first review filed a 1.48998 s
     * render a LOOP). So for each voice at three TUNEs this finds the HOLD where the line is crossed and renders 41
     * HOLDs in steps of 0.0003 around it (about 38 frames a step), with the last HOLD under the line and the first over
     * it to the float's last bit: 258 renders, 66150 frames under and 66151 over at all six notes (R1b saw it).
     * Every one is held to: the predicted frame count equal to the real one, the filed class LOOP exactly when the
     * comparison says so, the real [Classifier] saying LOOP over the line and anything but LOOP under it.
     * Each cell has rows on both sides, so the sweep can be failed from either. What the classifier calls the notes
     * under the line is the next test's.
     */
    @Test
    fun `the filed class agrees with the classifier's own length line, to the frame`() {
        val cells = ArcoVoice.entries.flatMap { v -> threeTunes.map { v to it } }
        val lines = cells.map { (voice, tune) -> Triple(voice, tune, lineHold(voice, tune)) }
        val jobs = lines.flatMap { (voice, tune, line) ->
            val holds = (0..40).map { line.first + (it - 20) * 0.0003f } + listOf(line.first, line.second)
            holds.map { Triple(voice, tune, it) }
        }
        val rows = jobs.parMap { (voice, tune, hold) -> lineRow(voice, tune, hold) }
        for ((voice, tune, line) in lines) {
            val mine = rows.filter { it.name.startsWith(label(voice, tune) + " ") }
            val under = mine.filter { it.frames.toFloat() / rate <= 1.5f }
            val over = mine.filter { it.frames.toFloat() / rate > 1.5f }
            assertTrue(under.isNotEmpty() && over.isNotEmpty(), "${label(voice, tune)}: the sweep did not cross the classifier's 1.5 s line")
            println(
                "ARCO line ${label(voice, tune)}: crosses at HOLD ${line.first} / ${line.second}, " +
                    "frames ${under.maxOf { it.frames }} under, ${over.minOf { it.frames }} over, the classifier says ${under.map { it.heard }.distinct()} under",
            )
        }
        for (r in rows) {
            val over = r.frames.toFloat() / rate > 1.5f
            assertEquals(r.frames, r.predicted, "${r.name}: the predicted frame count is not what a render makes")
            assertEquals(if (over) DrumClass.LOOP else DrumClass.PERC, r.filed, "${r.name} (${r.frames} frames): the filed class")
            if (over) {
                assertEquals(DrumClass.LOOP, r.heard, "${r.name} (${r.frames} frames): over the line and the classifier does not say LOOP")
            } else {
                assertNotEquals(DrumClass.LOOP, r.heard, "${r.name} (${r.frames} frames): under the line and the classifier says LOOP")
            }
        }
    }

    /**
     * Ten HOLD steps per voice and TUNE, from the shortest note to a 3.6 s one, and the default HOLD at every TUNE step of
     * both voices, against the real classifier: the contract a pad rests on ("a pitched note, never a drum with a choke
     * group"; BORE's first roster lost 8 of 16 pads to it). Over the line it is exact (LOOP both ways); under it the filed
     * class is PERC and the classifier must say PERC or TONAL. The table is printed first and every miss is listed before
     * the assertion.
     *
     * The classifier reads ARCO's lowest and highest notes as drums, and the engine files them PERC anyway: the misses line the
     * test prints counts 24 of 105 rows so (CELLO C2 and ERHU A5 at HOLD 0.4 are in both sweeps, so each counts twice).
     * CELLO C2 (MIDI 36) reads KICK at HOLD 0 to 0.4 and TONAL just under the line (HOLD 0.4472); at the default HOLD CELLO
     * MIDI 36 to 42 read KICK, 43 reads TOM, and the rest read PERC. ERHU A5 (81) reads SNARE at HOLD 0 to 0.5, and at the
     * default HOLD ERHU MIDI 77 to 81 read SNARE and the rest PERC. The other four notes of the grid (CELLO C3 and C4, ERHU D4 and C5) read PERC
     * at every HOLD under the line. The low reads are the low note's body under 200 Hz with a decay under 500 ms
     * (the bass branch's kick and tom rules), the high reads the bright top note's noise and high share. Exactly which of the
     * lowest notes read as a drum depends on the stroke, which is why the bar below is the two corners and not that list.
     * It is the classifier's limit and not the engine's: nothing consults the classifier for a synth pad (a kit pad
     * is filed by [Arco.drumClassFor], and the roster test holds all 16 presets clear of every drum), only a sample loaded from
     * outside is classified. So the bar is: over the line the classifier says LOOP and the filed class is LOOP, under it the
     * filed class is PERC always, and wherever the classifier hears a drum it is at one of the two corners listed (CELLO MIDI 36 to
     * 43, ERHU MIDI 77 to 81); a new corner that starts reading as a drum fails here and is named.
     */
    @Test
    fun `a note under the line is filed PERC, and the classifier hears a drum only at the two known corners`() {
        val holds = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.95f)
        val cells = ArcoVoice.entries.flatMap { v -> threeTunes.flatMap { t -> holds.map { h -> Triple(v, t, h) } } }
        val steps = ArcoVoice.entries.flatMap { v -> (0..Arco.tuneSemitones(v)).map { Triple(v, tuneOf(v, it), Arco.DEFAULT_HOLD) } }
        val rows = (cells + steps).parMap { (voice, tune, hold) -> lineRow(voice, tune, hold) }
        val grid = rows.take(cells.size)
        val defaults = rows.drop(cells.size)
        for (voice in ArcoVoice.entries) for (tune in threeTunes) {
            val mine = grid.filter { it.name.startsWith(label(voice, tune) + " ") }
            println("ARCO classifier ${label(voice, tune)}: " + mine.joinToString(" ") { "${it.hold}=${it.heard}/${it.filed}" })
        }
        for (voice in ArcoVoice.entries) {
            val mine = defaults.filter { it.name.startsWith("$voice ") }
            println("ARCO classifier default HOLD $voice: " + mine.joinToString(" ") { "${it.name.substringAfter(' ').substringBefore(' ')}=${it.heard}" })
        }
        val misses = rows.filter { r ->
            val over = r.frames.toFloat() / rate > 1.5f
            if (over) r.heard != DrumClass.LOOP else r.heard !in setOf(DrumClass.PERC, DrumClass.TONAL)
        }
        println("ARCO classifier misses: ${misses.size} of ${rows.size}: " + misses.joinToString(", ") { "${it.name}=${it.heard}" })
        for (r in rows) {
            val over = r.frames.toFloat() / rate > 1.5f
            assertEquals(if (over) DrumClass.LOOP else DrumClass.PERC, r.filed, "${r.name}: the filed class")
        }
        fun midiOf(name: String) = name.split(" ")[1].toInt()
        val unexpected = misses.filter { r ->
            val over = r.frames.toFloat() / rate > 1.5f
            val drum = r.heard in setOf(DrumClass.KICK, DrumClass.TOM, DrumClass.SNARE)
            val known = (r.name.startsWith("CELLO") && midiOf(r.name) in 36..43) || (r.name.startsWith("ERHU") && midiOf(r.name) in 77..81)
            over || !drum || !known
        }
        assertTrue(unexpected.isEmpty(), "${unexpected.size} notes read as something the engine does not expect of the classifier: " + unexpected.joinToString(", ") { "${it.name}=${it.heard}" })
    }

    // ---- the LOOP ------------------------------------------------------------

    /**
     * The pitch of [x] in Hz by a normalised, mean-removed autocorrelation with a parabola through the peak, the lag nearest
     * `rate / hz` within 6 percent. It is not [Arco]'s own tuner (which reads the raw 176.4 kHz stretch through [Bore]'s lag
     * ladder before the render is cut): this reads the finished 44.1 kHz loop, played three times over, and
     * [FineTuning]'s FFT is a second, different way to the same number.
     */
    private fun autocorrelationHz(x: FloatArray, hz: Float): Double {
        val period = rate / hz.toDouble()
        val lo = (period * 0.94).toInt().coerceAtLeast(2)
        val hi = (period * 1.06).toInt() + 1
        val n = x.size - hi - 1
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var energy = 0.0
        for (i in 0 until n) energy += (x[i] - mean) * (x[i] - mean)
        val r = DoubleArray(hi + 2)
        for (lag in lo - 1..hi + 1) {
            var s = 0.0
            for (i in 0 until n) s += (x[i] - mean) * (x[i + lag] - mean)
            r[lag] = s / energy
        }
        var best = lo
        for (lag in lo..hi) if (r[lag] > r[best]) best = lag
        val d = r[best - 1] - 2 * r[best] + r[best + 1]
        return rate / (best + if (d != 0.0) 0.5 * (r[best - 1] - r[best + 1]) / d else 0.0)
    }

    private data class LoopCase(val voice: ArcoVoice, val tune: Float, val a: Arco.LoopRender, val b: Arco.LoopRender)

    /** A bright sawtooth at [hz] for [seconds], at [rate]: a stand-in for a bowed note whose pitch is known to the last bit. */
    private fun sawtooth(hz: Double, seconds: Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> (0.8 * (2.0 * ((i * hz / rate) % 1.0) - 1.0)).toFloat() }

    /**
     * The negative control for the pitch measures below: a sawtooth 10 cents sharp of its note must read about 10 cents
     * by both of them, so a 5-cent bar is one they can fail. R1b saw the FFT read +10.76, +10.09 and +9.95 and the autocorrelation
     * +10.21, +10.37 and +8.40 at 65, 131 and 523 Hz; the bound is 3 cents either way.
     */
    @Test
    fun `the loop's two pitch measures see a ten cent error`() {
        for (hz in listOf(65.41f, 130.81f, 523.25f)) {
            val sharp = sawtooth(hz * 2.0.pow(10.0 / 1200), 4.0)
            val viaFft = FineTuning.cents(FineTuning.measuredHz(sharp, rate, hz, fromSec = 0.1f, bodySeconds = 1.4f), hz.toDouble())
            val viaAutocorrelation = FineTuning.cents(autocorrelationHz(sharp, hz), hz.toDouble())
            println("ARCO measure control ${f(hz.toDouble(), 2)} Hz at +10 c: FFT ${f(viaFft, 2)} c, autocorrelation ${f(viaAutocorrelation, 2)} c")
            assertEquals(10.0, viaFft, 3.0, "$hz Hz: the FFT measure misses a 10 cent error")
            assertEquals(10.0, viaAutocorrelation, 3.0, "$hz Hz: the autocorrelation measure misses a 10 cent error")
        }
    }

    /**
     * A LOOP is a held bow, cut in whole periods: at least 2 s of them, in whole frames, so the wrap is seamless and the classifier
     * sees a phrase. Each of these is checked on three notes of each voice, one after another (R1b's numbers are in the printed table):
     *  - whole periods: the loop is the planned length, at least 2 s (88200 frames; R1b saw 88200 to 88326), and the planned periods fill it
     *    to 0.05 cents of TUNE (arithmetic; seen 0.0000 to 0.0061);
     *  - seam: the kept stretch against itself one loop later ([Keys.seamError], the Organ's bar of 1e-3) under half the bar. R1b
     *    saw these six at 6.6e-9 to 1.6e-5 and the worst of the 225 loops of its own map at 2.5e-4; the bar here is 5e-4, twice that worst, and
     *    [ArcoLoopFuzzTest] holds all 45 notes of the knob to the whole bar;
     *  - bit for bit: two calls give the same loop, seam and all (the retune reads its own output twice, and a stray
     *    race or a clock would show);
     *  - steady: the first quarter's RMS within 0.98 to 1.02 of the last, since a bow held at constant speed neither swells nor
     *    decays (seen 1.0000 to 1.0012);
     *  - in tune: the loop's *played* pitch within 5 cents of the plan by two measures that are not [Arco]'s tuner, an FFT
     *    with a parabola on three copies played end to end ([FineTuning], as BORE's test reads it), and an autocorrelation of
     *    its own on the same three copies. The control test above shows both see a 10 cent error. Seen: the FFT -0.69 to +0.18 cents (it is
     *    coarsest at C2, where a bin is 1 percent of the note) and the autocorrelation -0.01 to +0.09;
     *  - the wrap does not click: the step across the join is no bigger than 1.1 times the loop's steepest step inside it (seen 0.07 to 0.22 of it:
     *    the cut is made where the two neighbours are smallest), and the same loop cut 1 to 32 thirty-seconds of a period short, the worst
     *    of them, is the control: it must step over 1.5 times that (seen 2.5 to 8.8 times).
     */
    @Test
    fun `a LOOP closes on itself in whole periods, at exactly TUNE, steady, across the range`() {
        val cells = ArcoVoice.entries.flatMap { v -> threeTunes.map { v to it } }
        val results = cells.parMap { (voice, tune) ->
            val m = macros(voice, tune, hold = 1f)
            LoopCase(voice, tune, Arco.renderLoopMeasured(voice, m), Arco.renderLoopMeasured(voice, m))
        }
        for ((voice, tune, a, b) in results) {
            val hz = Arco.frequencyFor(voice, tune)
            val name = label(voice, tune)
            val plan = Arco.planLoop(hz)
            assertEquals(plan.frames, a.loop.size, "$name: the loop is not the planned length")
            assertTrue(a.loop.size >= 2 * rate, "$name: the loop is ${a.loop.size.toDouble() / rate} s, under 2 s")
            val planned = 1200 * ln(plan.periods * rate.toDouble() / (plan.frames * hz)) / ln(2.0)
            assertTrue(abs(planned) < 0.05, "$name: $planned cents between the loop's whole periods and TUNE")
            assertTrue(a.seam < Keys.MAX_SEAM_ERROR / 2, "$name: the loop does not close (seam ${a.seam})")
            assertContentEquals(a.loop, b.loop, "$name: two calls gave different loops")
            assertEquals(a.seam, b.seam, "$name: two calls gave different seams")
            assertTrue(a.loop.all { it.isFinite() && abs(it) <= 0.99f + 1e-6f }, "$name: the loop is not finite or is over the ceiling")
            val q = a.loop.size / 4
            fun rms(from: Int) = sqrt((from until from + q).sumOf { a.loop[it].toDouble() * a.loop[it] } / q)
            val steady = rms(0) / rms(a.loop.size - q)
            assertTrue(steady in 0.98..1.02, "$name: the loop's first quarter is $steady of its last")
            val three = a.loop + a.loop + a.loop
            val viaFft = FineTuning.cents(FineTuning.measuredHz(three, rate, hz, fromSec = 0.1f, bodySeconds = 1.4f), hz.toDouble())
            val viaAutocorrelation = FineTuning.cents(autocorrelationHz(three, hz), hz.toDouble())
            val inside = steepestStep(a.loop, 0.0, a.loop.size.toDouble() / rate)
            val wrap = abs(a.loop[0] - a.loop[a.loop.size - 1])
            val period = rate / hz
            val cut = (1..32).maxOf { eighth ->
                val n = a.loop.size - (eighth * period / 32).toInt()
                abs(a.loop[0] - a.loop[n - 1])
            }
            println(
                "ARCO loop $name: ${a.loop.size} frames ${f(a.loop.size.toDouble() / rate, 3)} s ${plan.periods} periods, seam ${"%.2e".format(java.util.Locale.ROOT, a.seam)}, " +
                    "planned ${f(planned, 4)} c, played FFT ${f(viaFft, 2)} c autocorrelation ${f(viaAutocorrelation, 2)} c, " +
                    "quarters ${f(steady, 4)}, wrap step ${f(wrap.toDouble(), 4)} inside ${f(inside.toDouble(), 4)} cut short ${f(cut.toDouble(), 4)}",
            )
            assertTrue(abs(viaFft) < 5.0, "$name: the loop plays $viaFft cents from TUNE by the FFT")
            assertTrue(abs(viaAutocorrelation) < 5.0, "$name: the loop plays $viaAutocorrelation cents from TUNE by autocorrelation")
            assertTrue(wrap <= 1.1f * inside, "$name: the wrap steps $wrap, the loop's steepest step inside is $inside")
            assertTrue(cut > 1.5f * inside, "$name: a loop cut short steps only $cut against $inside inside: the wrap check cannot fail")
        }
    }

    /**
     * A LOOP does not depend on BOW or on how far past the LOOP step HOLD is: the stroke is a fixed 63 ms with no bite and the
     * warm-up is thrown away, so the loop is the same loop. Two settings, bit for bit; BODY, which a loop does carry,
     * is the control: it changes the samples (R1c saw BODY 0 against 1 differ by 0.73 of the loop at CELLO and 0.83 at ERHU, R1b 0.78 and 0.84; the bar is 0.3).
     */
    @Test
    fun `a LOOP is the same loop whatever BOW and HOLD say, and BODY changes it`() {
        for (voice in ArcoVoice.entries) {
            val base = Arco.renderLoopMeasured(voice, macros(voice, hold = 1f, bow = 0f)).loop
            assertContentEquals(base, Arco.renderLoopMeasured(voice, macros(voice, hold = 0.99f, bow = 1f)).loop, "$voice: BOW or HOLD changed the loop")
            val dry = Arco.renderLoopMeasured(voice, macros(voice, hold = 1f, body = 0f)).loop
            val diff = relativeDifference(Arco.renderLoopMeasured(voice, macros(voice, hold = 1f, body = 1f)).loop, dry)
            println("ARCO loop BODY $voice: BODY 0 against 1 differ by ${f(diff, 3)} of the loop")
            assertTrue(diff > 0.3, "$voice: BODY does nothing to a loop ($diff)")
        }
    }

    /**
     * The shared seam check's refusal, and not Arco's own. [Keys.requireSeam] refuses a loop whose seam is over the Organ's bar
     * with an IllegalArgumentException that names the loop and says it does not close. [Arco.renderLoop] has a `require` of its
     * own with the same message form ("ARCO <voice> <note>: the loop does not close"), and this test does not reach it: no macro
     * setting makes a real ARCO loop fail to close (that is the point of [ArcoLoopFuzzTest]), and the wrapper's seam and bar
     * cannot be injected without editing the engine, so Arco's own refusal path is untested. What the first half does is
     * [ResinHeldTest]'s check of the message form: half a period of a sine left over at the wrap is a seam that cannot close,
     * and the refusal must name the loop and say why. The second half is a real check on the wrapper: `renderLoop` on a real
     * note returns exactly the loop `renderLoopMeasured` measured, so the wrapper adds a check and nothing else.
     */
    @Test
    fun `the shared seam check refuses a loop that does not close by name, and renderLoop passes a good loop through unchanged`() {
        val period = 100
        val loopStart = 2_000
        val s = FloatArray(loopStart + period * 10 + period / 2) { sin(2.0 * PI * it / period).toFloat() }
        val e = assertFailsWith<IllegalArgumentException> { Keys.requireSeam("ARCO CELLO 36", s, loopStart) }
        assertTrue("ARCO CELLO 36" in e.message.orEmpty(), "the refusal does not name the loop: ${e.message}")
        assertTrue("does not close" in e.message.orEmpty(), "the refusal does not say why: ${e.message}")
        val whole = FloatArray(loopStart + period * 10) { sin(2.0 * PI * it / period).toFloat() }
        Keys.requireSeam("ARCO CELLO 36", whole, loopStart)
        for (voice in ArcoVoice.entries) {
            val m = macros(voice, hold = 1f)
            assertContentEquals(Arco.renderLoopMeasured(voice, m).loop, Arco.renderLoop(voice, m), "$voice: renderLoop is not the measured loop")
        }
    }

    // ---- a held note ---------------------------------------------------------

    /**
     * The pass rule is a held three-second note (HOLD 0.88): a finished render at three TUNEs per voice that is finite, under
     * the 0.99 ceiling, DC-free, not a whisper, and filed LOOP by [Arco.drumClassFor] and by the real classifier (over 3 s is far
     * past its line). Its length is the bow's 3 s, the 0.05 s ramp and the stop: at least 3.2 s (the stop's floor is 0.15 s) and at
     * most 4.55 s (the stop is never longer than half the hold, 1.5 s). R1b saw 3.200 s at A5, 4.550 s at C2 (the cap: the
     * free ring there is longer than it), and 4.036 s at C3 ("about 4 s": the bound there is 3.9 to 4.25); peaks 0.348 to 0.659
     * (the render is levelled for loudness, not for peak, so the bound is under the quietest of them, 0.25) and a mean of 2e-6 (bound 0.01).
     * The tone of a 3 s note is the physics tests'; this is its length and its level.
     */
    @Test
    fun `a held three second note renders clean, files LOOP, and lasts about four seconds at C3`() {
        val cells = ArcoVoice.entries.flatMap { v -> threeTunes.map { v to it } }
        val rows = cells.parMap { (voice, tune) ->
            val m = macros(voice, tune, hold = Arco.holdFor(3f))
            val snip = Arco.render(voice, m)
            Triple(voice to tune, snip, Classifier.classify(snip).drumClass)
        }
        val problems = ArrayList<String>()
        for ((cell, snip, heard) in rows) {
            val (voice, tune) = cell
            val name = label(voice, tune)
            val seconds = snip.frameCount.toDouble() / rate
            val mean = snip.samples.average()
            val filed = Arco.drumClassFor(voice, macros(voice, tune, hold = Arco.holdFor(3f)))
            println("ARCO held 3 s $name: ${f(seconds, 3)} s, peak ${f(snip.peak().toDouble(), 3)}, mean ${"%.2e".format(java.util.Locale.ROOT, mean)}, filed $filed, heard $heard")
            if (!snip.samples.all { it.isFinite() }) problems += "$name: not finite"
            if (snip.peak() > 0.99f + 1e-6f) problems += "$name: peak ${snip.peak()} is over the 0.99 ceiling"
            if (snip.peak() < 0.25f) problems += "$name: peak ${snip.peak()}: the note is a whisper"
            if (abs(mean) >= 0.01) problems += "$name: DC offset $mean"
            if (seconds !in 3.15..4.6) problems += "$name: $seconds s for a 3 s bow"
            if (filed != DrumClass.LOOP) problems += "$name: a held 3 s note is filed $filed, not LOOP"
            if (heard != DrumClass.LOOP) problems += "$name: the classifier reads a held 3 s note as $heard, not LOOP"
            if (voice == ArcoVoice.CELLO && tune == 0.5f && seconds !in 3.9..4.25) problems += "CELLO C3 held 3 s lasts $seconds s, not about 4"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The stop does not click. The string is stopped, not cut: the bow's velocity ramps to nothing over 50 ms, the bow lifts
     * and the string's loss rises (a constant extra loss at the bridge, so the tail is a plain exponential), and the box's
     * ring is cut where the string ends and a 4 ms fade closes the render. Whatever of that went wrong would show as a
     * step, so the steepest sample step from the lift to the end of the render (the cut and the fade included) is held to
     * twice the steady note's steepest (the record's bar, and StringsBowTest's click test's own), on a held 3 s note at C2
     * (where a long ring is cut), D4 and A5 (where the steps are biggest). The steps in the bow's own 50 ms release ramp
     * are held to the same bar.
     *
     * Printed by the test: after the lift the steepest step is 0.73 times the steady note's at C2, 0.78 at D4 and 0.67 at A5, so
     * the bar of 2 times has room for a factor of 2.6 or more, and in the ramp it is 0.97 to 1.00 times. The control is a click:
     * one sample just after the lift, at the note's loudest, pushed away from its neighbour by the note's own peak. It steps
     * 9.03 times the steady step at C2, 4.05 at D4 and 2.66 at A5, so the bar can be failed at all three. (A hard cut to silence
     * cannot be the control at the top: a flyback there is most of the note's height, a cut steps by under 2 times the steady
     * step at D4 and A5, and the 2 times bar could not see it.)
     */
    @Test
    fun `the stop does not click, at the bottom, the middle and the top of the range`() {
        val problems = ArrayList<String>()
        for ((voice, tune) in listOf(ArcoVoice.CELLO to 0f, ArcoVoice.ERHU to 0f, ArcoVoice.ERHU to 1f)) {
            val m = macros(voice, tune, hold = Arco.holdFor(3f))
            val x = Arco.render(voice, m).samples
            val bowOn = Arco.holdSeconds(m.getValue("HOLD")).toDouble()
            val lift = bowOn + Arco.RELEASE_RAMP_SECONDS
            val end = x.size.toDouble() / rate
            val steady = steepestStep(x, 1.5, bowOn - 0.1)
            val ramp = steepestStep(x, bowOn, lift)
            val after = steepestStep(x, lift, end)
            val hz = Arco.frequencyFor(voice, tune)
            val from = (lift * rate).toInt()
            var loudest = from
            for (i in from until from + (2 * rate / hz).toInt()) if (abs(x[i]) > abs(x[loudest])) loudest = i
            val peak = x.maxOf { abs(it) }
            val spiked = x.copyOf()
            spiked[loudest + 1] += if (x[loudest] >= x[loudest + 1]) -peak else peak
            val cutStep = steepestStep(spiked, lift, end)
            val name = label(voice, tune)
            println(
                "ARCO stop $name: steady steepest step ${f(steady.toDouble(), 4)}, in the ramp ${f(ramp.toDouble(), 4)} (${f(ramp / steady.toDouble(), 2)}x), " +
                    "after the lift ${f(after.toDouble(), 4)} (${f(after / steady.toDouble(), 2)}x), spike ${f(cutStep.toDouble(), 4)} (${f(cutStep / steady.toDouble(), 2)}x)",
            )
            if (steady <= 0f) problems += "$name: the steady note has no steps"
            if (ramp > 2f * steady) problems += "$name: the bow's release steps $ramp against the steady note's $steady"
            if (after > 2f * steady) problems += "$name: the stop clicks: steepest step $after after the lift, $steady in the steady note"
            if (cutStep <= 2f * steady) problems += "$name: a click as tall as the note steps only $cutStep against $steady, so this check cannot fail"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- vibrato -------------------------------------------------------------

    /**
     * The baked vibrato is a read-back delay of the finished wave, +-10 cents at 6.1 Hz (ERHU's, a sine; CELLO's drifts about that, in rate, depth and
     * lean, and swells in over half a second: [the human vibrato wanders, swells in and stays centred, and the plain one is a sine]), scaled by how long the bow is on: none at 0.6 s or less,
     * full at 1.5 s or more, and none at all in a LOOP, which cannot carry a signal that does not repeat. Read on the *finished*
     * render with 70 ms windows stepped 20 ms ([BoreMeasure.cents], the way BORE's vibrato test reads it), so a
     * 6.1 Hz swing is resolved but its peak is averaged down: a 70 ms window passes a +-10 cent swing as
     * 10 * sin(pi * 6.1 * 0.07) / (pi * 6.1 * 0.07) = +-7.3 cents.
     *
     * The excursion is the windowed pitch of the note with its vibrato less the same note without it (the engine's own
     * `vibrato = false`), window by window, so the measure's own scatter on a settled string is not counted as vibrato.
     * "None" is then exact: up to 0.6 s of bow the two are the same render, bit for bit (the control: at the default
     * HOLD, 0.85 s, they are not).
     *
     * R1c saw, at CELLO C3 (the human vibrato) and ERHU C5 (the plain one): 3.34 and 3.20 c at 1 s of bow (44 percent of the depth, as the ramp from 0.6 s
     * to 1.5 s says), 8.53 and 7.69 at 1.5 s and 8.65 and 7.90 at 3 s, and 8.52 and 7.90 about the note's own mean, a little over the 7.3 the window arithmetic gives
     * (CELLO's drifts of depth and rate put it up to 0.8 c above ERHU's). ERHU's vibrato is R1b's merged one, bit for bit (the plain shape, held to it below), so ERHU's 3.20, 7.69, 7.90 and 7.90 are R1b's figures too. The 3.5, 7.9 to 8.1, 7.95 to 8.05 and
     * 8.3 and 7.95 that R1b's version of this KDoc quoted were read on R1b's first vibrato, a retune of the bow every 64 samples, which the read-back delay replaced (commit 2d75455) before R1b merged; they were never re-measured and are not a comparator.
     * The bars: 2 to 5.5 c at 1 s, 6 to 10 at 1.5 s and 3 s (CELLO's 8.65 has 1.35 c of room), growing between 1 s and 1.5 s by over 2 c; and the record's, at most 15 c peak at 3 s.
     */
    @Test
    fun `vibrato is none at a short hold, grows with HOLD, and stays under 15 cents at three seconds`() {
        val problems = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val tune = 0.5f
            val hz = Arco.frequencyFor(voice, tune)
            for (hold in listOf(0f, 0.2f, 0.26f)) {
                val m = macros(voice, tune, hold = hold)
                assertTrue(Arco.holdSeconds(hold) <= Arco.VIBRATO_HOLD_FROM_SECONDS, "$voice: HOLD $hold is not a short note")
                val vib = finishedCore(voice, m)
                assertContentEquals(vib, finishedCore(voice, m, vibrato = false), "$voice HOLD $hold: a short note carries vibrato")
                assertContentEquals(Arco.render(voice, m).samples, vib, "$voice HOLD $hold: finishedCore is not what render makes")
            }
            val control = macros(voice, tune)
            assertTrue(!finishedCore(voice, control).contentEquals(finishedCore(voice, control, vibrato = false)), "$voice: the default HOLD carries no vibrato, so the equality above proves nothing")

            fun excursion(seconds: Float): Pair<Double, Double> {
                val m = macros(voice, tune, hold = Arco.holdFor(seconds))
                val bowOn = Arco.holdSeconds(m.getValue("HOLD"))
                val withVibrato = centsOver(finishedCore(voice, m), hz, 0.6f, bowOn)
                val without = centsOver(finishedCore(voice, m, vibrato = false), hz, 0.6f, bowOn)
                val mean = withVibrato.average()
                val peak = withVibrato.zip(without).maxOf { (a, b) -> abs(a - b) }
                val spread = withVibrato.maxOf { abs(it - mean) }
                println("ARCO vibrato $voice ${f(bowOn.toDouble(), 2)} s of bow: peak against the same note without it ${f(peak, 2)} c, peak about its own mean ${f(spread, 2)} c over ${withVibrato.size} windows")
                return peak to spread
            }
            val one = excursion(1.0f)
            val oneAndAHalf = excursion(1.5f)
            val three = excursion(3.0f)
            if (one.first !in 2.0..5.5) problems += "$voice: a 1 s note's vibrato is ${one.first} c, not 2 to 5.5"
            if (oneAndAHalf.first !in 6.0..10.0) problems += "$voice: a 1.5 s note's vibrato is ${oneAndAHalf.first} c, not 6 to 10"
            if (oneAndAHalf.first < one.first + 2.0) problems += "$voice: vibrato did not grow from 1 s to 1.5 s: ${one.first} -> ${oneAndAHalf.first} c"
            if (three.first !in 6.0..10.0) problems += "$voice: a 3 s note's vibrato differs from the plain note by ${three.first} c, not 6 to 10"
            if (three.second !in 6.0..15.0) problems += "$voice: a 3 s note's vibrato peaks at ${three.second} c about its mean, not 6 to 15"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The vibrato never touches the string: it is a read-back delay of the finished wave ([Arco.vibrato]), so the string's own
     * velocity under the bow, the wave the slips are read from, is bit for bit the plain string's, and a held 3 s note has no gap
     * between slips that is not one period from 1.2 s on (the slowest default lock is 0.63 s, see the next test). The output wave
     * is the same as the plain one before the vibrato starts (0.35 s) and differs after it.
     *
     * R1b's first vibrato retuned the bow every 64 samples and it was not like this: at the defaults and 3 s of bow ERHU steps 6, 10, 11,
     * 12, 16 and 19 (MIDI 68, 72, 73, 74, 78 and 81) had one or two real extra slips between 1.2 s and the lift (a gap of 0.18 to 0.24
     * of a period and then one of 0.76 to 0.82, the string's velocity at the bow going back to -6 to -7.4 times the bow's speed),
     * about one in 1 to 2 seconds, always at the same phase of the swing on the same note (MIDI 73 at phase 0.34 three times, MIDI 78 at
     * 0.63 twice); 37 events in 24,746 ERHU slips (CELLO: 1 in 8,337) and in 91 of 200 rolled ERHU notes; none without the vibrato. This
     * test is that regression's guard: with the retune it fails at those steps.
     */
    @Test
    fun `a held note slips once a period through its vibrato, because the string never feels it`() {
        val problems = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val steps = (0..Arco.tuneSemitones(voice)).toList()
            val rows = steps.parMap { step ->
                val tune = tuneOf(voice, step)
                val m = macros(voice, tune, hold = Arco.holdFor(3f))
                val hz = Arco.frequencyFor(voice, tune)
                val onTap = FloatArray(((3.2f + 4f) * rawRate).toInt())
                val offTap = FloatArray(onTap.size)
                val on = Arco.bow(voice, hz, m, rawRate, vibrato = true, bowPointOut = onTap)
                val off = Arco.bow(voice, hz, m, rawRate, vibrato = false, bowPointOut = offTap)
                val start = (Arco.VIBRATO_DELAY_SECONDS * rawRate).toInt()
                val gaps = ArcoMeasure.uncleanGaps(ArcoMeasure.slipTimes(onTap, (Arco.holdSeconds(m.getValue("HOLD")) * rawRate).toInt()), hz, 1.2)
                val same = onTap.contentEquals(offTap)
                val before = (0 until start).all { on[it] == off[it] }
                val after = (start + rawRate until on.size / 2).any { on[it] != off[it] }
                listOf(gaps, if (same) 0 else 1, if (before) 0 else 1, if (after) 0 else 1)
            }
            println("ARCO vibrato string $voice: unclean gaps from 1.2 s " + steps.indices.joinToString(" ") { "${steps[it]}=${rows[it][0]}" })
            for ((i, step) in steps.withIndex()) {
                if (rows[i][0] != 0) problems += "$voice step $step: ${rows[i][0]} gaps between slips are not one period through the vibrato"
                if (rows[i][1] != 0) problems += "$voice step $step: the vibrato changed the string's own velocity under the bow"
                if (rows[i][2] != 0) problems += "$voice step $step: the wave differs from the plain one before the vibrato starts"
                if (rows[i][3] != 0) problems += "$voice step $step: the vibrato does not change the wave once it has started"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The default note is a tone before its bow lifts. At the default knobs the bow is on for 0.854 s (HOLD 0.4), and the string
     * has to have locked into one slip a period by then at every TUNE step, or a default stab at that note is a scratch. The lock
     * is read on the raw core bowed for 3 s without the vibrato ([ArcoMeasure.lockSeconds]), so the time itself is seen and not only
     * "after the lift" (a default note's own vibrato is 2.8 cents at 0.85 s of bow).
     *
     * R1b's first default BOW was 0.6 (a 44 ms stroke with a 1.15 times bite), and it failed this: four CELLO steps locked after the
     * default bow lifted, E2 (step 4, MIDI 40) at 1.00 s, F2 (step 5) at 0.86 s, G2 (step 7) at 0.90 s and G#2 (step 8) at 1.01 s,
     * against 0.854 s of bow, so a default stab on those notes was a scratch that ended before the string settled. The lock time
     * is erratic in the stroke's length (without any bite, at BOW 0.6, CELLO C2 to G2 lock at 1.0 to 1.2 s; at BOW 0.7 at 0.4 to 0.85 s;
     * at BOW 0.5 at 0.32 to 0.63 s, falling steadily with the note), so the default moved to BOW 0.5, a 63 ms stroke with no bite, where
     * every step locks by 0.63 s at the default GRIP; any other BOW is playable and some notes are slow at some strokes (the lock
     * across BOW test prints it). ERHU locks by 0.23 s.
     */
    @Test
    fun `the default note locks into one slip before its bow lifts, at every TUNE step`() {
        val problems = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val steps = (0..Arco.tuneSemitones(voice)).toList()
            val rows = steps.parMap { step ->
                val tune = tuneOf(voice, step)
                val m = macros(voice, tune)
                val hz = Arco.frequencyFor(voice, tune)
                val longBow = 3f
                val bowPoint = FloatArray(((longBow + 4f) * rawRate).toInt())
                Arco.bow(voice, hz, m, rawRate, gateSeconds = longBow, vibrato = false, bowPointOut = bowPoint)
                step to ArcoMeasure.lockSeconds(bowPoint, (longBow * rawRate).toInt(), hz)
            }
            println("ARCO default lock $voice (bow on ${f(Arco.holdSeconds(Arco.DEFAULT_HOLD).toDouble(), 2)} s): " + rows.joinToString(" ") { "${it.first}:${f(it.second, 2)}" })
            for ((step, lock) in rows) if (lock < 0 || lock > Arco.holdSeconds(Arco.DEFAULT_HOLD)) problems += "$voice step $step locks at $lock s, after the default bow lifts at ${Arco.holdSeconds(Arco.DEFAULT_HOLD)}"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * A LOOP carries no vibrato: its pitch over time is flat to the measure's own floor. Read as the vibrato test reads a note
     * (70 ms windows, 20 ms apart) on three copies of the loop played end to end, away from the first and last 0.3 s. A
     * vibrato would show as +-8 cents; R1b saw the loop's spread, the largest distance of any window from the mean, at 0.99 c
     * (CELLO C3) and 0.59 (ERHU C5), and the bar is 2 cents. The 3 s note's own spread (R1c saw 8.52 and 7.90) is the control: it is
     * far over 2.5 times the bar, so a loop that carried the vibrato would fail.
     */
    @Test
    fun `a LOOP carries no vibrato - its pitch is flat to the measure's floor`() {
        for (voice in ArcoVoice.entries) {
            val hz = Arco.frequencyFor(voice, 0.5f)
            val loop = Arco.renderLoopMeasured(voice, macros(voice, hold = 1f)).loop
            val three = loop + loop + loop
            val cents = centsOver(three, hz, 0.3f, three.size.toFloat() / rate - 0.3f)
            val mean = cents.average()
            val spread = cents.maxOf { abs(it - mean) }
            val held = finishedCore(voice, macros(voice, hold = Arco.holdFor(3f)))
            val heldCents = centsOver(held, hz, 0.6f, 3f)
            val heldSpread = heldCents.maxOf { abs(it - heldCents.average()) }
            println("ARCO loop vibrato $voice: loop spread ${f(spread, 2)} c over ${cents.size} windows, the held 3 s note's ${f(heldSpread, 2)} c")
            assertTrue(spread <= 2.0, "$voice: the loop's pitch wanders $spread cents")
            assertTrue(heldSpread > 2.5 * 2.0, "$voice: the 3 s note's vibrato ($heldSpread c) is not far over the loop's bar: the check cannot fail")
        }
    }

    // ---- the vibrato's shape (R1c) --------------------------------------------

    /**
     * R1b's vibrato, copied from the engine as it stood before R1c: a sine at [Arco.VIBRATO_HZ], rising in over [Arco.VIBRATO_RISE_SECONDS] from
     * [Arco.VIBRATO_DELAY_SECONDS], read back through a four-point cubic. The reference [Arco.VIBRATO_PLAIN] is held to, bit for bit.
     */
    private fun r1bVibrato(buf: FloatArray, cents: Float, rate: Int): FloatArray {
        val depth = Arco.vibratoDepthSamples(cents, rate)
        val out = FloatArray(buf.size)
        val last = buf.size - 1
        for (i in buf.indices) {
            val t = i.toDouble() / rate
            if (t <= Arco.VIBRATO_DELAY_SECONDS) {
                out[i] = buf[i]
                continue
            }
            val rise = ((t - Arco.VIBRATO_DELAY_SECONDS) / Arco.VIBRATO_RISE_SECONDS).coerceAtMost(1.0)
            val pos = i - depth * rise * sin(2.0 * PI * Arco.VIBRATO_HZ * (t - Arco.VIBRATO_DELAY_SECONDS))
            val k = Math.floor(pos).toInt()
            val x = (pos - k).toFloat()
            val p0 = buf[(k - 1).coerceIn(0, last)]
            val p1 = buf[k.coerceIn(0, last)]
            val p2 = buf[(k + 1).coerceIn(0, last)]
            val p3 = buf[(k + 2).coerceIn(0, last)]
            out[i] = p1 + 0.5f * x * (p2 - p0 + x * (2f * p0 - 5f * p1 + 4f * p2 - p3 + x * (3f * (p1 - p2) + p3 - p0)))
        }
        return out
    }

    /** A busy wave at the raw rate, [seconds] long: three sines and a sawtooth, so a read at the wrong place is a different sample. */
    private fun busyWave(seconds: Double): FloatArray = FloatArray((seconds * rawRate).toInt()) { i ->
        val t = i.toDouble() / rawRate
        (0.4 * sin(2 * PI * 130.8 * t) + 0.25 * sin(2 * PI * 391.9 * t + 0.4) + 0.15 * sin(2 * PI * 1046.5 * t + 1.1) + 0.2 * (2.0 * ((t * 261.6) % 1.0) - 1.0)).toFloat()
    }

    /**
     * [Arco.VIBRATO_PLAIN] is R1b's vibrato bit for bit, so ERHU's (the one the owner heard as a yes) is untouched. R1b's loop is copied into
     * this file ([r1bVibrato]) and held against [Arco.vibrato] called with no shape and with [Arco.VIBRATO_PLAIN], on a 3.2 s busy wave at the raw rate
     * and 10 cents (the delay is 0.35 s and the rise 0.2 s, so 2.65 s of it is full swing): equal to the last bit by `contentEquals`, and not by a tolerance.
     * The control is [Arco.VIBRATO_HUMAN] on the same wave, which must not equal it (R1c saw it differ from R1b's by 0.31 of the wave's RMS, and the plain one
     * by 0.0000): so the same comparison refuses a vibrato that is not R1b's. Two calls of either give the same array, and a cents of 0 hands the buffer back.
     */
    @Test
    fun `the plain vibrato is R1b's, bit for bit, and the human one is not`() {
        val buf = busyWave(3.2)
        val r1b = r1bVibrato(buf, 10f, rawRate)
        assertContentEquals(r1b, Arco.vibrato(buf, 10f, rawRate), "the default shape is not R1b's vibrato")
        assertContentEquals(r1b, Arco.vibrato(buf, 10f, rawRate, Arco.VIBRATO_PLAIN), "VIBRATO_PLAIN is not R1b's vibrato")
        val human = Arco.vibrato(buf, 10f, rawRate, Arco.VIBRATO_HUMAN)
        println("ARCO vibrato shapes: the human one differs from R1b's by ${f(relativeDifference(r1b, human), 4)} of the wave's RMS; the plain one by ${f(relativeDifference(r1b, Arco.vibrato(buf, 10f, rawRate, Arco.VIBRATO_PLAIN)), 4)}")
        assertTrue(!r1b.contentEquals(human), "VIBRATO_HUMAN is R1b's vibrato: the comparison cannot tell a different vibrato")
        assertContentEquals(human, Arco.vibrato(buf, 10f, rawRate, Arco.VIBRATO_HUMAN), "the human vibrato is not a pure function of its input")
        assertTrue(Arco.vibrato(buf, 0f, rawRate, Arco.VIBRATO_HUMAN) === buf, "no cents is no vibrato")
    }

    /**
     * The engine uses the shape of its voice ([Arco.vibratoShapeFor]): CELLO the human one, ERHU the plain one. A 3 s hold at the middle of each voice's TUNE is
     * rendered by [Arco.bow] with the vibrato on and compared, bit for bit, with the same raw string without it put through [Arco.vibrato] at
     * [Arco.vibratoCentsFor] 3 s and the voice's shape (the cents are 10: a hold past 1.5 s is full depth); and the CELLO render is not what the plain shape
     * makes of the same string, nor the ERHU render what the human shape makes (the two controls: a wiring of both voices to one shape fails one of them).
     */
    @Test
    fun `each voice's engine vibrato is its own shape's`() {
        assertTrue(Arco.vibratoShapeFor(ArcoVoice.CELLO) === Arco.VIBRATO_HUMAN, "CELLO's shape is not the human one")
        assertTrue(Arco.vibratoShapeFor(ArcoVoice.ERHU) === Arco.VIBRATO_PLAIN, "ERHU's shape is not the plain one")
        val cents = Arco.vibratoCentsFor(3f)
        assertEquals(Arco.VIBRATO_MAX_CENTS, cents, "a 3 s hold is not full depth")
        for (voice in ArcoVoice.entries) {
            val m = macros(voice, 0.5f, hold = Arco.holdFor(3f))
            val hz = Arco.frequencyFor(voice, 0.5f)
            val on = Arco.bow(voice, hz, m, rawRate, gateSeconds = 3f, vibrato = true)
            val dry = Arco.bow(voice, hz, m, rawRate, gateSeconds = 3f, vibrato = false)
            val own = Arco.vibrato(dry, cents, rawRate, Arco.vibratoShapeFor(voice))
            val other = Arco.vibrato(dry, cents, rawRate, if (voice == ArcoVoice.CELLO) Arco.VIBRATO_PLAIN else Arco.VIBRATO_HUMAN)
            println("ARCO vibrato wiring $voice: equals its own shape's ${on.contentEquals(own)}, equals the other shape's ${on.contentEquals(other)}, the two shapes differ by ${f(relativeDifference(own, other), 4)} of the note's RMS")
            assertContentEquals(own, on, "$voice: the engine's vibrato is not its shape's")
            assertTrue(!on.contentEquals(other), "$voice: the engine's vibrato is the other voice's shape: the wiring cannot be told")
        }
    }

    /**
     * What a shape does to the pitch, in cents against the plain note, one value a raw sample for [seconds] from the start of the vibrato (u = 0 is
     * [Arco.VIBRATO_DELAY_SECONDS] into the note). The read is delayed by `depth * rise * unit` samples ([Arco.vibrato]), and a delay that grows by d' a
     * sample slows the read by d', so the pitch is `1200 log2(1 - d')`. [extraSlope] adds a steady drift of the delay, which a control uses.
     */
    private fun pitchTrack(shape: Arco.VibratoShape, seconds: Double, extraSlope: Double = 0.0): DoubleArray {
        val depth = Arco.vibratoDepthSamples(Arco.VIBRATO_MAX_CENTS, rawRate)
        val n = (seconds * rawRate).toInt()
        val delay = DoubleArray(n + 1) { i ->
            val u = i.toDouble() / rawRate
            depth * (u / shape.riseSeconds).coerceAtMost(1.0) * Arco.vibratoUnit(u, shape) + extraSlope * i
        }
        return DoubleArray(n) { i -> 1200.0 * log2(1.0 - (delay[i + 1] - delay[i])) }
    }

    private class Turn(val at: Double, val cents: Double)

    /** The swing's turning points between [from] and [to] seconds: where the pitch track changes direction, never less than 40 ms apart. */
    private fun turns(track: DoubleArray, from: Double, to: Double): List<Turn> {
        val found = ArrayList<Turn>()
        for (i in 1 until track.size - 1) {
            if ((track[i] - track[i - 1]) * (track[i + 1] - track[i]) < 0.0) {
                val at = i.toDouble() / rawRate
                if (at in from..to && (found.isEmpty() || at - found.last().at >= 0.04)) found.add(Turn(at, track[i]))
            }
        }
        return found
    }

    /**
     * How much one swing differs from the next *in the same direction*, which is what a mechanical vibrato does not do. [peaks] is the ratio of the highest to the lowest maximum of the pitch track (and, separately, of the
     * highest to the lowest minimum: the smaller of the two ratios is kept, so both directions must vary) and [periods] the ratio of the longest to the shortest full cycle, a maximum to the next maximum or a minimum to the next
     * minimum. A lean in the swing (the skew's second harmonic) makes the up swing taller and quicker than the down one, but makes every up swing the same: comparing an up swing with a down one, as the first version of this
     * measure did, counts that lean as a difference, and a drift-free skewed wave read 1.15 on it. Comparing like with like reads 1.000 on it.
     */
    private class Cycles(val peaks: Double, val periods: Double, val maxima: Int, val minima: Int)

    private fun cycles(track: DoubleArray, from: Double, to: Double): Cycles {
        val t = turns(track, from, to)
        val up = t.filter { it.cents > 0.0 }
        val down = t.filter { it.cents < 0.0 }
        fun spread(heights: List<Double>) = heights.max() / heights.min()
        val full = (up.zipWithNext() + down.zipWithNext()).map { (a, b) -> b.at - a.at }
        return Cycles(minOf(spread(up.map { it.cents }), spread(down.map { -it.cents })), full.max() / full.min(), up.size, down.size)
    }

    private fun maxCents(track: DoubleArray, from: Double, to: Double): Double = (((from * rawRate).toInt()) until minOf(track.size, (to * rawRate).toInt())).maxOf { abs(track[it]) }

    private fun meanCents(track: DoubleArray, from: Double, to: Double): Double = (((from * rawRate).toInt()) until minOf(track.size, (to * rawRate).toInt())).sumOf { track[it] } / (((to - from) * rawRate).toInt())

    /**
     * The human vibrato is not mechanical and the plain one is: the pitch track of each shape at the full 10 cents, over 4 s from the start of the vibrato, read swing to swing *in the same direction* ([cycles]).
     * Over u = 0.9 to 3.6 s (after the rise of either) HUMAN's swings differ from one another in height by at least 10 percent (the highest maximum of the pitch track over the lowest, and the same for the minima, the smaller of the two
     * kept) and its full cycles in length by at least 5 percent (the longest, from one maximum to the next or one minimum to the next, over the shortest). R1c saw 16 maxima and 16 minima, the heights differing x1.248 (25 percent)
     * and the cycles x1.0996 (10 percent), so the bars have 0.148 and 0.050 of room.
     *
     * There are two controls, read with the same measure, each under 1 percent. PLAIN, a sine, reads x1.0000 and x1.0000 (R1c saw 17 maxima and 16 minima). The skewed wave with no drift, `VibratoShape(HUMAN's rise, 0.0, 0.0, HUMAN's skew)`,
     * reads x1.0000 and x1.0000 as well, and it is the one that matters: HUMAN's lean (the skew's second harmonic) alone makes the up swing taller and quicker than the down one while every up swing is the same, so a vibrato with
     * the lean and no drift is mechanical, and the first version of this measure, which compared every turning point of the pitch track with every other, called it varied: it read that drift-free wave at x1.1536 on peaks, over its bar of 1.15 by
     * 0.004, so the test passed whether or not the rate and depth drift were there. Compared like with like the lean is not counted, and the bars hold the drift alone.
     *
     * The rest of what makes it a finger and not a fault: its peak |cents| from 1.2 s on stays under 15 (R1c saw 12.75 over the 4 s; the held-note bar is
     * the same 15); its mean over each whole second from 1 s to 4 s stays within 0.3 cents of nought (R1c saw +0.186, -0.103 and -0.109, PLAIN -0.110, -0.014 and +0.081; a bounded
     * delay cannot give much more than 0.7 over a second), with a delay that drifts by one thousandth of a sample a sample, which is about 1.7 cents of pitch (R1c saw it read -1.55 over 1
     * to 2 s, with the swing's own +0.19), as the control that the mean can be seen to fail; and it swells in: the peak over its first 0.25 s is under half the peak from 1.0 s on
     * (R1c saw 5.27 against 12.75, 0.41, 0.09 of room) while PLAIN's first 0.25 s is already at least 0.9 of its steady swing (10.00 against 10.06).
     */
    @Test
    fun `the human vibrato wanders, swells in and stays centred, and the plain one is a sine`() {
        val human = pitchTrack(Arco.VIBRATO_HUMAN, 4.0)
        val plain = pitchTrack(Arco.VIBRATO_PLAIN, 4.0)
        val skewed = pitchTrack(Arco.VibratoShape(Arco.HUMAN_RISE_SECONDS, 0.0, 0.0, Arco.VIBRATO_HUMAN.skew), 4.0)
        val h = cycles(human, 0.9, 3.6)
        val p = cycles(plain, 0.9, 3.6)
        val k = cycles(skewed, 0.9, 3.6)
        for ((name, c) in listOf("HUMAN" to h, "PLAIN" to p, "SKEWED, no drift" to k)) {
            println("ARCO vibrato $name: ${c.maxima} maxima and ${c.minima} minima from 0.9 to 3.6 s, peaks differ x${f(c.peaks, 4)} (like with like), full cycles differ x${f(c.periods, 4)}")
        }
        val every = turns(skewed, 0.9, 3.6).map { abs(it.cents) }
        println("ARCO vibrato SKEWED, no drift, the first version of the measure (every turning point against every other): peaks differ x${f(every.max() / every.min(), 4)}")
        assertTrue(h.peaks >= 1.10, "the human swings differ by only ${f((h.peaks - 1) * 100, 1)} percent from one to the next in the same direction, under 10")
        assertTrue(h.periods >= 1.05, "the human full cycles differ by only ${f((h.periods - 1) * 100, 1)} percent, under 5")
        assertTrue(p.peaks < 1.01 && p.periods < 1.01, "the plain vibrato's swings differ by ${f((p.peaks - 1) * 100, 2)} percent and its cycles by ${f((p.periods - 1) * 100, 2)}: the measure cannot call a sine mechanical")
        assertTrue(k.peaks < 1.01 && k.periods < 1.01, "the skewed wave with no drift is called varied (peaks x${f(k.peaks, 3)}, cycles x${f(k.periods, 3)}): the measure counts the lean as wander, so the human drift is not what passes it")
        assertTrue(minOf(p.maxima, p.minima, h.maxima, h.minima, k.maxima, k.minima) > 12, "too few turning points to say: ${p.maxima} ${p.minima} ${h.maxima} ${h.minima} ${k.maxima} ${k.minima}")

        val loudest = maxCents(human, 1.2, 4.0)
        val early = maxCents(human, 0.0, 0.25)
        val steady = maxCents(human, 1.0, 4.0)
        println("ARCO vibrato HUMAN: peak |cents| from 1.2 s ${f(loudest)}, from 1.0 s ${f(steady)}, over the first 0.25 s ${f(early)} (${f(early / steady, 2)} of it); PLAIN first 0.25 s ${f(maxCents(plain, 0.0, 0.25))} of ${f(maxCents(plain, 1.0, 4.0))}")
        assertTrue(loudest < 15.0, "the human vibrato reaches ${f(loudest)} cents")
        assertTrue(early < 0.5 * steady, "the human vibrato's first 0.25 s reaches ${f(early)} cents against ${f(steady)} later: it does not swell in")
        assertTrue(maxCents(plain, 0.0, 0.25) >= 0.9 * maxCents(plain, 1.0, 4.0), "the plain vibrato has not reached its full swing by 0.25 s")

        for (second in 1..3) {
            val hm = meanCents(human, second.toDouble(), second + 1.0)
            val pm = meanCents(plain, second.toDouble(), second + 1.0)
            println("ARCO vibrato mean pitch over ${second}..${second + 1} s from the start: HUMAN ${f(hm, 3)} c, PLAIN ${f(pm, 3)} c")
            assertTrue(abs(hm) < 0.3, "the human vibrato's pitch sits ${f(hm, 3)} cents off the note over ${second} to ${second + 1} s")
        }
        val drifting = pitchTrack(Arco.VIBRATO_HUMAN, 4.0, extraSlope = 0.001)
        val drift = meanCents(drifting, 1.0, 2.0)
        println("ARCO vibrato control: a delay drifting by 0.001 a sample reads ${f(drift, 2)} c of mean pitch over 1 to 2 s")
        assertTrue(abs(drift) > 1.0, "the mean's measure does not see a drift of 1.7 cents: ${f(drift, 3)}")
    }

    /**
     * Nothing of the vibrato is heard before it begins, in either shape: with [Arco.VIBRATO_DELAY_SECONDS] of the note copied sample for sample,
     * the first sample that differs is after it, and some sample in the second after it does differ (a vibrato that only copied would pass the first
     * half, so the second half is its control). A unit swing is a pure function of the time since the vibrato began: two calls give the same value.
     */
    @Test
    fun `no vibrato before its delay, in either shape, and a swing is a pure function of time`() {
        val buf = busyWave(2.0)
        val delayN = (Arco.VIBRATO_DELAY_SECONDS * rawRate).toInt()
        for ((name, shape) in listOf("PLAIN" to Arco.VIBRATO_PLAIN, "HUMAN" to Arco.VIBRATO_HUMAN)) {
            val out = Arco.vibrato(buf, 10f, rawRate, shape)
            val first = buf.indices.first { out[it] != buf[it] }
            println("ARCO vibrato $name: copies the note exactly up to sample $first (the delay is $delayN)")
            assertTrue((0 until delayN).all { out[it] == buf[it] }, "$name: the note changed before the vibrato's delay")
            assertTrue(first >= delayN, "$name: the first changed sample is $first, before the delay at $delayN")
            assertTrue((delayN until delayN + rawRate).any { out[it] != buf[it] }, "$name: the vibrato changes nothing in the second after its delay")
            for (u in listOf(0.0, 0.123, 1.0, 2.5, 3.9)) assertEquals(Arco.vibratoUnit(u, shape), Arco.vibratoUnit(u, shape), "$name: the unit swing at $u is not a function of the time")
        }
    }

    // ---- the aliasing floor ----------------------------------------------------

    /**
     * The clarity of the harmonic series that fits [samples] best: [harmonicClarity] with the series' fundamental searched over
     * +-12 cents of [f0] in quarter-cent steps. The bow's pitch is within a few cents of its note, not exactly on it (R1b: up to 4 c),
     * and 4 cents at the 40th harmonic of C4 is 24 Hz, three times the 8 Hz a harmonic is allowed: a nominal series reads the
     * brightest part of the spectrum as noise. (R1b saw CELLO C4 at the default grip and bow read 57.7 dB on the nominal series and 73.7 fitted,
     * and ERHU A5 40.1 and 70.7.)
     * Returns the clarity and the cents at which the series fitted.
     */
    private fun fittedClarity(samples: FloatArray, f0: Float, start: Int, n: Int = 1 shl 16): Pair<Double, Double> {
        val mag = magnitudes(samples, start, n)
        var best = Double.NEGATIVE_INFINITY
        var at = 0.0
        var cents = -12.0
        while (cents <= 12.0) {
            val series = f0 * 2.0.pow(cents / 1200)
            var on = 0.0
            var off = 0.0
            for (b in 1 until mag.size) {
                val hz = b.toFloat() * rate / n
                if (hz > 20_000f) break
                if (hz < f0 / 2) continue
                val k = Math.round(hz / series)
                if (abs(hz - k * series) < 8.0) on += mag[b] else off += mag[b]
            }
            val clarity = 10 * log10(on / off)
            if (clarity > best) { best = clarity; at = cents }
            cents += 0.25
        }
        return best to at
    }

    /**
     * Spectral clarity: the energy within 8 Hz of a harmonic over everything else from f0/2 to 20 kHz, the bar TIDE, SIREN and FORK
     * hold (45 dB), at the top note of each voice with the grip and the bow at their brightest (GRIP 1, BOW 1), where a bright
     * sawtooth through the 4x oversampled chain has the most to alias. It is read on the finished render of a held 3 s note with
     * the vibrato switched off in the core (a vibrato spreads each of an A5's harmonics by far more than 8 Hz) from 1.0 s, a
     * 1.49 s window that is wholly sustain: no onset, no stop. The finished note of a hold of 0.59 s of bow (HOLD 0.26, the
     * longest with no vibrato) cannot be read this way at all, and the printed line says so: the window is longer than the note,
     * most of it the stop's fall (-60 dB in the last 0.15 to 0.3 s) and the build-up, and every line smears; R1c saw 12.4 to 8.6 dB
     * (CELLO; R1b's bite read 14.6 to 11.1) and 13.5 to 10.0 (ERHU) from 0.2 s to 0.4 s, which is the window and not the aliasing.
     *
     * R1c saw 77.2 dB (CELLO C4, the series fitting at +0.5 c; R1b saw 76.7, before CELLO's bite grew) and 69.1 (ERHU A5, +0.5 c), 32 and 24 dB over the record's 45. The bar is 60, so a floor
     * 9 dB worse than ERHU's today fails, and the record's 45 is met with 24 dB to spare at the least. The control is the same render with white noise added at
     * -55 dB of its own level, which read 56.5 and 55.7 dB (R1b: 56.6 and 55.7): it must fall under 60 (3.5 and 4.3 dB under it, 21 and 13 dB under the measurements), so the bar
     * is one the measure can fail.
     */
    @Test
    fun `the top note of each voice is clean of aliasing`() {
        val problems = ArrayList<String>()
        for (voice in ArcoVoice.entries) {
            val hz = Arco.frequencyFor(voice, 1f)
            val short = Arco.render(voice, macros(voice, 1f, bow = 1f, grip = 1f, hold = 0.26f))
            val shortReads = listOf(0.2f, 0.3f, 0.4f).map { fittedClarity(short.samples, hz, (it * rate).toInt()).first }
            println("ARCO clarity $voice ${label(voice, 1f)} at HOLD 0.26 (${f(short.frameCount.toDouble() / rate, 3)} s, window longer than the note): ${shortReads.joinToString(" ") { f(it, 1) }} dB from 0.2 0.3 0.4 s")
            val long = macros(voice, 1f, bow = 1f, grip = 1f, hold = Arco.holdFor(3f))
            val sustain = finishedCore(voice, long, vibrato = false)
            val (clarity, cents) = fittedClarity(sustain, hz, (1.0f * rate).toInt())
            val nominal = harmonicClarity(sustain, hz, (1.0f * rate).toInt())
            val rng = Random(7)
            val rms = sqrt(sustain.sumOf { it.toDouble() * it } / sustain.size)
            val noisy = FloatArray(sustain.size) { sustain[it] + (0.00178 * rms * sqrt(3.0) * (2 * rng.nextDouble() - 1)).toFloat() }
            val control = fittedClarity(noisy, hz, (1.0f * rate).toInt()).first
            println("ARCO clarity $voice ${label(voice, 1f)} GRIP 1 BOW 1, 3 s no vibrato from 1.0 s: ${f(clarity, 1)} dB fitted at ${f(cents, 2)} c (${f(nominal, 1)} dB on the nominal series), with -55 dB noise ${f(control, 1)} dB")
            if (clarity < 60.0) problems += "$voice: energy between the harmonics is only ${f(clarity, 1)} dB down (the bar is 60, the record's 45)"
            if (control >= 60.0) problems += "$voice: -55 dB of noise still reads ${f(control, 1)} dB clear, so the bar cannot fail"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---- settled, validated, and the recipe -------------------------------------

    /**
     * What goes in is settled the way every engine does it: a macro outside 0..1 is clamped, a key the engine does not have is
     * dropped, a missing one is its default. So TUNE 9 with a made-up key *is* TUNE 1, bit for bit, and TUNE -3 is TUNE 0.
     * The control is the same macros without the clamp: TUNE 0.5 is a different note.
     */
    @Test
    fun `an out of range macro is clamped and an unknown key is dropped`() {
        for (voice in ArcoVoice.entries) {
            val settled = Arco.settled(mapOf("TUNE" to 9f, "SPARKLE" to 0.7f, "BODY" to -1f), voice)
            assertEquals(Arco.defaults(voice) + mapOf("TUNE" to 1f, "BODY" to 0f), settled, "$voice: settled")
            assertEquals(Arco.defaults(voice), Arco.settled(emptyMap(), voice), "$voice: a missing key is not its default")
            val wild = Arco.render(voice, mapOf("TUNE" to 9f, "SPARKLE" to 0.7f))
            val clamped = Arco.render(voice, mapOf("TUNE" to 1f))
            assertContentEquals(clamped.samples, wild.samples, "$voice: TUNE 9 with an unknown key is not the clamped render")
            assertContentEquals(Arco.render(voice, mapOf("TUNE" to 0f)).samples, Arco.render(voice, mapOf("TUNE" to -3f)).samples, "$voice: TUNE -3 is not TUNE 0")
            assertTrue(!Arco.render(voice, mapOf("TUNE" to 0.5f)).samples.contentEquals(clamped.samples), "$voice: TUNE 0.5 and 1 are the same render")
        }
    }

    /**
     * A recipe is checked when it is made ([Patches.validateMacros]): an unknown macro, a value outside 0..1 (and NaN, which is
     * outside every range) and a blank name are each an IllegalArgumentException that says which, and the ends of the range
     * (0 and 1, HOLD 1 the LOOP) are fine: the control that the check is not "refuse everything".
     */
    @Test
    fun `a recipe with an unknown macro, a value out of range or a blank name is refused`() {
        for (voice in ArcoVoice.entries) {
            val unknown = assertFailsWith<IllegalArgumentException> { ArcoPatch("X", voice, mapOf("SPARKLE" to 0.5f)) }
            assertTrue("SPARKLE" in unknown.message.orEmpty(), "the refusal does not name the macro: ${unknown.message}")
            val high = assertFailsWith<IllegalArgumentException> { ArcoPatch("X", voice, mapOf("GRIP" to 1.5f)) }
            assertTrue("GRIP" in high.message.orEmpty(), "the refusal does not name the macro: ${high.message}")
            assertFailsWith<IllegalArgumentException> { ArcoPatch("X", voice, mapOf("GRIP" to -0.1f)) }
            assertFailsWith<IllegalArgumentException> { ArcoPatch("X", voice, mapOf("HOLD" to Float.NaN)) }
            val blank = assertFailsWith<IllegalArgumentException> { ArcoPatch("   ", voice, mapOf("GRIP" to 0.5f)) }
            assertTrue("name" in blank.message.orEmpty(), "the refusal does not say it is the name: ${blank.message}")
            val ends = ArcoPatch("Ends", voice, mapOf("TUNE" to 0f, "BOW" to 1f, "GRIP" to 0f, "BODY" to 1f, "HOLD" to 1f))
            assertEquals(voice.name, ends.voiceName)
            assertEquals("ARCO", ends.engine)
        }
    }

    /**
     * No seed and no noise: the render is a pure function of the macros, so a patch renders the same bits every time, one-shot
     * and LOOP (the LOOP path reads its own output twice to correct its pitch, so a stray race or a clock would show there first),
     * `ArcoPatch.render()` is `Arco.render`, and a recipe through its own JSON text is the same sound.
     */
    @Test
    fun `a patch renders the same bits every time, and through its recipe`() {
        for (voice in ArcoVoice.entries) {
            val shot = ArcoPatch("Canary", voice, Arco.defaults(voice))
            val first = shot.render().samples
            assertContentEquals(first, shot.render().samples, "$voice one-shot")
            assertContentEquals(Arco.render(voice, shot.macros).samples, first, "$voice: ArcoPatch.render is not Arco.render")
            val loop = ArcoPatch("Canary", voice, Arco.defaults(voice) + ("HOLD" to 1f))
            val looped = loop.render().samples
            assertContentEquals(looped, loop.render().samples, "$voice loop")
            assertContentEquals(Arco.render(voice, loop.macros).samples, looped, "$voice: ArcoPatch.render is not Arco.render for a LOOP")
            val restored = ArcoPatch.fromJsonText(shot.toJsonText())
            assertEquals(shot, restored, "$voice: the recipe did not round-trip")
            assertContentEquals(first, restored.render().samples, "$voice: the recipe renders differently after a round trip")
        }
    }

    // ---- every macro ---------------------------------------------------------

    /**
     * No dead knob (playability rule 1): each of TUNE, BOW, GRIP, BODY and HOLD, moved alone from its default (to 0.1 if the default is over a
     * half, else to 0.9), changes the finished render, and by a measured amount: the RMS of the difference over the RMS of the note, with
     * the tail of the longer one counted as difference. BODY is the one a quiet engine loses first, so it is named: BODY from its
     * default 0.5 to 0.9 moves the note by 0.44 (CELLO) and 0.45 (ERHU), and from 0 to 1 by 0.92 and 0.92 (R1c's curve: 0.9 rings the box at 1.5 times the
     * string and 1 at 1.75; R1b's were 0.23 and 0.24, and 0.70 and 0.71); R1g saw 0.945 and 0.955 from 0.5 to 0.9 and 1.480 and 1.495 from 0 to 1 (above the knee the box no longer climbs, and the lift is added at the plain's own gain, 5.12 dB per element at BODY 0.9 and 6.0 at BODY 1, so the
     * note moves more, not less). The printed line has the other four at
     * 1.0 to 1.9 of the note (CELLO and ERHU: TUNE 1.37 and 1.41, BOW 1.78 and 1.79 (R1b: 1.08 at CELLO, before its bite grew), GRIP 1.02 and 1.16, HOLD 1.89 and 1.75: a different note, a
     * different attack, a different bridge, a different length). The bars are 0.1 of the note for every knob and 0.3 for BODY's two ends, under the
     * smallest of them with room. The control is a key the engine does not have, which must change nothing at all, to the bit, so the
     * measure can tell "did nothing" from "did something".
     */
    @Test
    fun `every macro changes the sound, BODY too, and an unknown key does not`() {
        for (voice in ArcoVoice.entries) {
            val base = Arco.render(voice, Arco.defaults(voice)).samples
            val none = Arco.render(voice, Arco.defaults(voice) + ("SPARKLE" to 0.9f)).samples
            assertContentEquals(base, none, "$voice: an unknown key changed the render")
            val diffs = Arco.macrosFor(voice).associate { spec ->
                val moved = Arco.defaults(voice) + (spec.name to if (spec.default > 0.5f) 0.1f else 0.9f)
                spec.name to relativeDifference(base, Arco.render(voice, moved).samples)
            }
            val bodyEnds = relativeDifference(
                Arco.render(voice, Arco.defaults(voice) + ("BODY" to 0f)).samples,
                Arco.render(voice, Arco.defaults(voice) + ("BODY" to 1f)).samples,
            )
            println("ARCO macros $voice: " + diffs.entries.joinToString(" ") { "${it.key} ${f(it.value, 3)}" } + ", BODY 0 against 1 ${f(bodyEnds, 3)}")
            for ((name, diff) in diffs) assertTrue(diff > 0.1, "$voice $name did nothing (${f(diff, 4)})")
            assertTrue(bodyEnds > 0.3, "$voice: BODY from 0 to 1 moves the note by only ${f(bodyEnds, 4)}: a dead knob")
        }
    }

    // ---- SCRAMBLE ----------------------------------------------------------------

    private class Roll(val macros: Map<String, Float>, val lock: Double, val late: Int, val peak: Float, val finite: Boolean)

    /**
     * Where a rolled note must have locked by, in seconds: 2.0 s, the bar the lock-across-BOW test (in ArcoTest) holds CELLO to. R1c saw CELLO's slowest rolled lock without vibrato at 1.906 s (at temperature 1; 400 rolls over both temperatures)
     * and the slowest at temperature 0.35 at 1.723 s; R1b's slowest was 1.426 s (at temperature 0.35) and 1.080 s (at 1), and ERHU's is 0.329 s. The bound leaves 0.094 s over R1c's slowest CELLO roll, the
     * thinnest margin in this file (R1b's left 0.57 s), and the 3 s bow leaves 1.0 s of watching after the bound. The temperature 0.35 rolls are drawn around the factory presets, so a change to the roster re-rolls them
     * and can move the slowest lock: the range the test prints is where to look.
     */
    private val lockBySeconds = 2.0

    /**
     * The raw core of a rolled sound, bowed for 3 s (long enough for the slowest lock measured, with a second of watching
     * after it), read on the bow-point tap. With the vibrato off by default: whether the string locks is a property of the window the macros draw,
     * and the vibrato has its own tests (it never touches the string, so the lock is the same either way). [pressure] and [cornerHz] are the core's own overrides, for the control that leaves the window.
     */
    private fun roll(voice: ArcoVoice, macros: Map<String, Float>, vibrato: Boolean = false, pressure: Float? = null, cornerHz: Float? = null): Roll {
        val m = Arco.settled(macros, voice)
        val hz = Arco.frequencyFor(voice, m.getValue("TUNE"))
        val gate = 3f
        val bowPoint = FloatArray(((gate + 4f) * rawRate).toInt())
        val core = Arco.bow(voice, hz, m, rawRate, pressure = pressure, cornerHz = cornerHz, gateSeconds = gate, vibrato = vibrato, bowPointOut = bowPoint)
        val end = (gate * rawRate).toInt()
        val slips = ArcoMeasure.slipTimes(bowPoint, end)
        val late = ArcoMeasure.uncleanGaps(slips, hz, lockBySeconds)
        return Roll(m, ArcoMeasure.lockSeconds(bowPoint, end, hz), late, BowMeter.maxAbs(core), core.all { it.isFinite() })
    }

    /** A roll that does not lock into one slip a period by [lockBySeconds]: it never locks, locks late, or leaves a gap that is not one period after it. */
    private fun late(r: Roll) = r.lock < 0 || r.lock > lockBySeconds || r.late != 0

    /**
     * SCRAMBLE is the engine's dice (`Arco.scramble`, around a factory preset, then HOLD held short of the LOOP step), and an
     * identity claim rests on it: every roll is a bowed note, whatever the dice said. 200 rolls per voice are bowed on the raw
     * core for 3 s (a bow-on long enough for the lock) and each must: lock into one slip a period (`ArcoMeasure.lockSeconds` finds
     * the lock, at or before 2.0 s; `late` counts the gaps that are not one period after the same 2.0 s, none for a roll that locks
     * in time, and it is what the HOLE line prints for one that does not), stay under `Strings.Bow.RAW_PEAK_CEILING` and be finite,
     * and never land on the LOOP step (HOLD under 0.99). Then 200 more per voice at temperature 1, where every macro is a uniform roll
     * and the dice go to the corners no preset visits. The claim is strict for both voices, as it was in R1b. A roll that does not lock
     * is a hole in the window and is listed with its macros, and not hidden.
     *
     * R1c saw no hole: the 800 rolls (CELLO and ERHU, both temperatures) lock at 0.139 to 1.906 s (CELLO; the slowest at temperature 0.35 is 1.723 s) and 0.051 to 0.329 s (ERHU), so the 2.0 s bound
     * leaves 0.094 s over the slowest CELLO roll (R1b's slowest was 1.426 s, 0.57 s of room: CELLO's bigger bite, 3.0 times for 60 ms with half of its share in the pressure, took most of it, and that margin is the one that moves with the engine),
     * the worst raw peak is 0.833 (CELLO, temperature 0.35) and 0.616 (ERHU) against the ceiling of 1.25 (R1b's 0.80 and 0.62), and no roll reaches the LOOP step. The lock is read on the string, which the vibrato never
     * touches (the vibrato is a read-back delay of the finished wave), so it is the same with the vibrato on or off. The bite is chaotic in the macros, and this is the bar that decides between bites: R1c's interim settings,
     * 2.75 times for 60 ms and 2.5 times for 120 ms with a quarter of the share in the pressure, each left one of the 400 CELLO rolls late (at 2.05 s and at 2.68 s), which is why the bite's three numbers were searched against this test as well as the roster's other bars.
     *
     * The control is a string outside the window: CELLO C2 at a pressure of 0.6 and the full 3023.6 Hz corner, which R1a measured slipping three times a period, at the default BOW 0.5 (no bite: R1c saw it never lock, with 196 unclean gaps).
     * It must fail the judgement the rolls go through, so the judgement can fail. (The same string at BOW 1 is not a control for the shipped bite: the bite's climb of the pressure carries it toward the window and it locks at 1.085 s with none late,
     * where the interim 2.5 times, 120 ms bite left it locking at 2.263 s with 45 unclean gaps, so it is only the BOW 0.5 string, which has no bite to rescue it, that tests the judgement.)
     */
    @Test
    fun `every scramble roll locks into one slip, is unclipped, and never lands on the LOOP step`() {
        val problems = ArrayList<String>()
        val outside = roll(ArcoVoice.CELLO, macros(ArcoVoice.CELLO, tune = 0f), pressure = 0.6f, cornerHz = Strings.Bow.BRIDGE_HZ)
        val outsideBite = roll(ArcoVoice.CELLO, macros(ArcoVoice.CELLO, tune = 0f, bow = 1f), pressure = 0.6f, cornerHz = Strings.Bow.BRIDGE_HZ)
        println(
            "ARCO scramble control: CELLO C2 at pressure 0.6 and the full corner locks at ${f(outside.lock, 3)} s, ${outside.late} unclean gaps after $lockBySeconds s; " +
                "at BOW 1 (printed, not a control) it locks at ${f(outsideBite.lock, 3)} s, ${outsideBite.late} unclean gaps",
        )
        if (!late(outside)) problems += "a string outside the window passes the lock check, so the check cannot fail"
        for (voice in ArcoVoice.entries) for ((temperature, seed) in listOf(0.35f to 20260930, 1f to 20261001)) {
            val rolls = (0 until 200).map { i -> Arco.scramble(voice, Random(seed + i), temperature) }
            for (macros in rolls) {
                if (!macros.values.all { it in 0f..1f }) problems += "$voice scrambled out of range: $macros"
                if (macros.getValue("HOLD") > Arco.SCRAMBLE_HOLD_CEILING || Arco.isLoop(macros.getValue("HOLD"))) problems += "$voice scrambled into the LOOP step: $macros"
            }
            val results = rolls.parMap { roll(voice, it) }
            val bad = results.filter { late(it) }
            val locks = results.map { it.lock }.filter { it >= 0 }
            println(
                "ARCO scramble $voice temperature $temperature: ${results.size} rolls, lock ${f(locks.min(), 3)} .. ${f(locks.max(), 3)} s, " +
                    "worst raw peak ${f(results.maxOf { it.peak }.toDouble(), 3)} (ceiling ${Strings.Bow.RAW_PEAK_CEILING}), ${bad.size} that do not lock by $lockBySeconds s",
            )
            for (r in bad) println("ARCO scramble HOLE $voice: lock ${f(r.lock, 3)} s, ${r.late} unclean gaps late, ${r.macros}")
            if (!results.all { it.finite }) problems += "$voice: a roll went non-finite"
            for (r in results) if (r.peak >= Strings.Bow.RAW_PEAK_CEILING) problems += "$voice: raw peak ${r.peak} is over the ceiling at ${r.macros}"
            if (bad.isNotEmpty()) problems += "$voice temperature $temperature: ${bad.size} of ${results.size} rolls do not lock into one slip: ${bad.take(3).map { it.macros }}"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The same rolls through the whole render: finite, in -1..1 (the 0.99 ceiling), not silent, 12 per voice, a few of them 3.6 s
     * notes and so not cheap. And the same seed rolls the same sound (BoreTest's own canary), with a different seed a different one.
     */
    @Test
    fun `scramble rolls render clean through the whole render, and one seed is one roll`() {
        val random = Random(42)
        for (voice in ArcoVoice.entries) {
            val rolls = (0 until 12).map { Arco.scramble(voice, random) }
            val snips = rolls.parMap { Arco.render(voice, it) }
            for ((macros, snip) in rolls.zip(snips)) {
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice scrambled to a non-finite or clipping render: $macros")
                assertTrue(snip.peak() > 0.05f, "$voice scrambled to silence: $macros")
            }
            assertEquals(Arco.scramble(voice, Random(7)), Arco.scramble(voice, Random(7)), "$voice: the same seed must roll the same sound")
            assertNotEquals(Arco.scramble(voice, Random(7), 1f), Arco.scramble(voice, Random(8), 1f), "$voice: two seeds rolled the same sound")
        }
    }

    // ---- velocity ----------------------------------------------------------------

    /**
     * ARCO registers no brightness macro of Velocity's (GRIP's sustain centroid and BOW's onset centroid are printed below, as
     * information for whoever decides to register one), so velocity falls back to [Velocity.soften], the way BORE and VOX do: a soft
     * note is a dulled one, and its centroid must fall. This is the canary that stays: if a brightness override is ever
     * registered for ARCO the first assertion fails and says to look at this test. The sweeps are nine steps each; "monotone" is
     * FORK's own reading, no step more than 11 percent under the one before and none under 0.97 of the first.
     */
    @Test
    fun `velocity softens through the fallback and is darker at low velocity`() {
        for (voice in ArcoVoice.entries) {
            val patch = ArcoPatch("Vel Canary", voice, Arco.defaults(voice))
            assertEquals(null, Velocity.brightnessSpec(patch), "$voice: a brightness macro is registered; the fallback canary is out of date")
            val viaFallback = Velocity.atVelocity(patch, 0.3f)
            val viaSoften = Velocity.soften(patch.render(), 0.7f)
            assertTrue(viaFallback.samples.contentEquals(viaSoften.samples), "$voice: atVelocity is not the soften fallback")
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: soft centroid $soft should be below hard centroid $hard")

            fun monotone(c: List<Double>) = (1 until c.size).all { c[it] >= c[it - 1] * 0.89 && c[it] >= c[0] * 0.97 }
            val steps = (0..8).map { it / 8f }
            val grip = steps.map { g ->
                val s = Arco.render(voice, macros(voice, grip = g))
                centroid(s.samples, (0.5f * rate).toInt(), 1 shl 14)
            }
            val bow = steps.map { b ->
                val s = Arco.render(voice, macros(voice, bow = b))
                centroid(s.samples, 0, 1 shl 9)
            }
            println("ARCO velocity $voice GRIP sustain centroid (from 0.5 s, Hz): ${grip.joinToString(" ") { f(it, 0) }} monotone within 11%: ${monotone(grip)}")
            println("ARCO velocity $voice BOW onset centroid (first 11.6 ms, Hz): ${bow.joinToString(" ") { f(it, 0) }} monotone within 11%: ${monotone(bow)}")
        }
    }
}
