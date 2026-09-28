package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilkTest {

    private fun cents(measured: Double, want: Double) = 1200.0 * ln(measured / want) / ln(2.0)

    /**
     * The loudest spectral peak within one semitone of [wantHz], read from
     * [fromSec] over [bodySeconds] and zero-padded to a large FFT with
     * parabolic interpolation - [TuningAccuracyTest]'s own `measuredHz`,
     * parameterised so it can look anywhere in a render rather than only
     * at the start. A plain narrow Goertzel scan (`PluckSpectra.peakHz`)
     * turned out to be a full-blown octave-of-cents short of this at
     * OUD's low root (65 Hz): its bin width there is tens of cents wide,
     * wider than the ±5-cent bound being measured.
     */
    private fun measuredHz(snip: Snip, wantHz: Float, fromSec: Float = 0.05f, bodySeconds: Float = 0.25f): Double {
        val rate = snip.sampleRate
        val from = (fromSec * rate).toInt().coerceIn(0, snip.samples.size)
        val bodyLen = minOf(snip.samples.size - from, (bodySeconds * rate).toInt())
        require(bodyLen > 8) { "measuredHz needs samples past $fromSec s (buffer is ${snip.samples.size} samples)" }
        var n = 1
        while (n < 65536) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until bodyLen) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (bodyLen - 1)).toFloat()
            re[i] = snip.samples[from + i] * w
        }
        Fft.forward(re, im)
        val mag = DoubleArray(n / 2) { hypot(re[it].toDouble(), im[it].toDouble()) }
        val binHz = rate.toDouble() / n
        val radiusBins = maxOf(1, (wantHz * 0.059 / binHz).toInt())
        val centerBin = (wantHz / binHz).toInt()
        var bestBin = centerBin
        var bestMag = -1.0
        for (b in maxOf(1, centerBin - radiusBins)..minOf(mag.size - 2, centerBin + radiusBins)) {
            if (mag[b] > bestMag) {
                bestMag = mag[b]
                bestBin = b
            }
        }
        val a = mag[bestBin - 1]
        val b2 = mag[bestBin]
        val c = mag[bestBin + 1]
        val denom = a - 2.0 * b2 + c
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        return (bestBin + delta) * binHz
    }

    /**
     * PLUCK's own 5-cent rule (spec, "Testing", item 1), generalised: every
     * degree of OUD's default scale (RAST) lands within 5 cents of RAST's
     * own target, not 12-EDO's.
     */
    @Test
    fun `OUD is in tune at every degree of RAST`() {
        val root = Silk.rootFor(SilkVoice.OUD)
        val scale = SilkScales.RAST
        val size = scale.cents.size
        for (degree in 0..2 * size) {
            val tune = degree / (2f * size)
            val want = SilkScales.frequencyFor(root, scale, tune)
            val snip = Silk.render(SilkVoice.OUD, mapOf("TUNE" to tune, "COURSE" to 0f))
            val measured = measuredHz(snip, want)
            val off = cents(measured, want.toDouble())
            assertTrue(abs(off) <= 5.0, "degree $degree (tune=$tune): wanted $want Hz, measured $measured Hz ($off cents)")
        }
    }

    @Test
    fun `COURSE audibly changes the render`() {
        val unison = Silk.render(SilkVoice.OUD, mapOf("COURSE" to 0f)).samples
        val wide = Silk.render(SilkVoice.OUD, mapOf("COURSE" to 1f)).samples
        assertFalse(unison.contentEquals(wide), "COURSE 0 and 1 should not render identically")
    }

    /**
     * OUD's SLIDE (spec, "OUD"): the pitch track starts below the target -
     * at [Silk.oneDegreeBelow] - and lands on it within 5 cents. The glide
     * itself (0.35 s at SLIDE 1, `Silk.oudCourseLoop`'s own shape constant)
     * is a continuously moving pitch, so an "early" read only checks the
     * direction (still clearly under the target, not already arrived);
     * the precise 5-cent claim is checked well after the glide settles.
     */
    @Test
    fun `SLIDE glides in from a degree below, and lands on the target`() {
        val root = Silk.rootFor(SilkVoice.OUD)
        val scale = SilkScales.RAST
        val tune = 3 / (2f * scale.cents.size) // an interior degree, with room below it
        val target = SilkScales.frequencyFor(root, scale, tune)
        val below = Silk.oneDegreeBelow(root, scale, tune)

        val snip = Silk.render(SilkVoice.OUD, mapOf("TUNE" to tune, "SLIDE" to 1f, "COURSE" to 0f, "DAMP" to 0.1f))
        val early = measuredHz(snip, below, fromSec = 0.01f, bodySeconds = 0.05f)
        val late = measuredHz(snip, target, fromSec = 0.5f, bodySeconds = 0.3f)
        assertTrue(early < target * 0.995, "early in the glide ($early Hz) should still read clearly under the target ($target Hz)")
        assertTrue(abs(cents(late, target.toDouble())) <= 5.0, "after the glide: wanted $target Hz, measured $late Hz")
    }

    /**
     * PLUCK's own 5-cent rule, generalised: every degree of GUZHENG's
     * default scale (PENTATONIC) lands within 5 cents of the scale's own
     * target. STIFF is left at 0 here: at nonzero STIFF the fundamental
     * still holds exact (the tuning budget charges the cascade's own
     * delay before splitting the loop length - StringsTest's own
     * dispersion test proves this at the Strings level), but this test's
     * job is SCALE/TUNE, not STIFF, so it isolates them.
     */
    @Test
    fun `GUZHENG is in tune at every degree of PENTATONIC`() {
        val root = Silk.rootFor(SilkVoice.GUZHENG)
        val scale = SilkScales.PENTATONIC
        val size = scale.cents.size
        for (degree in 0..2 * size) {
            val tune = degree / (2f * size)
            val want = SilkScales.frequencyFor(root, scale, tune)
            val snip = Silk.render(SilkVoice.GUZHENG, mapOf("TUNE" to tune, "STIFF" to 0f))
            val measured = measuredHz(snip, want)
            val off = cents(measured, want.toDouble())
            assertTrue(abs(off) <= 5.0, "degree $degree (tune=$tune): wanted $want Hz, measured $measured Hz ($off cents)")
        }
    }

    /**
     * PRESS (spec, "GUZHENG"): at each of its four snapped stops, the
     * pitch track starts on the plucked degree and settles 0/100/200/300
     * cents above it - measured well after the 0.12 s rise
     * (`Silk.guzhengLoop`'s own shape constant).
     */
    @Test
    fun `PRESS lands at each of its four stops`() {
        val root = Silk.rootFor(SilkVoice.GUZHENG)
        val scale = SilkScales.PENTATONIC
        val tune = 0.3f
        val plucked = SilkScales.frequencyFor(root, scale, tune)
        for ((press, expectCents) in listOf(0f to 0, 0.34f to 100, 0.67f to 200, 1f to 300)) {
            val target = plucked * 2f.pow(expectCents / 1200f)
            val snip = Silk.render(SilkVoice.GUZHENG, mapOf("TUNE" to tune, "PRESS" to press, "DAMP" to 0.1f))
            val measured = measuredHz(snip, target, fromSec = 0.3f, bodySeconds = 0.3f)
            val off = cents(measured, target.toDouble())
            assertTrue(abs(off) <= 5.0, "PRESS=$press: wanted $target Hz (+$expectCents c), measured $measured Hz ($off cents)")
        }
    }

    /**
     * STIFF's wiring, at the voice level: `StringsTest`'s own dispersion
     * proofs run at the `Strings.Dispersion`/`Strings.Loop` layer with a
     * deliberately strong synthetic coefficient (real GUZHENG-scale B
     * measures too weak to move a partial - see `Silk.guzheng`'s own
     * comment); this checks the path a regression in the `STIFF`->B
     * mapping, the `forB` call, or wiring the cascade into `guzhengLoop`
     * would actually break, without asserting an audible amount.
     */
    @Test
    fun `STIFF actually reaches the render, not just Strings Dispersion in isolation`() {
        val zero = Silk.render(SilkVoice.GUZHENG, mapOf("STIFF" to 0f)).samples
        val one = Silk.render(SilkVoice.GUZHENG, mapOf("STIFF" to 1f)).samples
        assertFalse(zero.contentEquals(one), "STIFF 0 and 1 should not render identically")
    }

    @Test
    fun `is deterministic`() {
        for (voice in SilkVoice.entries) {
            assertTrue(
                Silk.render(voice).samples.contentEquals(Silk.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `scrambles are reproducible, stay in range, and never touch INFLECT`() {
        for (voice in SilkVoice.entries) {
            val a = Silk.scramble(voice, Random(2))
            val b = Silk.scramble(voice, Random(2))
            assertTrue(a == b, "$voice scramble not reproducible")
            repeat(8) { seed ->
                val macros = Silk.scramble(voice, Random(seed))
                assertTrue(macros.getValue("INFLECT") == 0.5f, "$voice roll $seed moved INFLECT: ${macros["INFLECT"]}")
                val snip = Silk.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
            }
        }
    }

    @Test
    fun `every macro at 0, 0-5 and 1 renders finite and bounded`() {
        for (voice in SilkVoice.entries) {
            val names = Silk.macrosFor(voice).map { it.name }
            for (name in names) {
                for (v in listOf(0f, 0.5f, 1f)) {
                    val snip = Silk.render(voice, mapOf(name to v))
                    assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice $name=$v broke")
                }
            }
        }
    }

    /**
     * TONAL is only reachable through the classifier's bass-dominant path
     * (`lowRatio > 0.55`, `Classifier.classifyBass`) - a plucked string's
     * own energy sits in its low-*mid* harmonics, not sub-150Hz content
     * (measured: OUD's default lowRatio is 0.09), so no plucked voice gets
     * there without being a fundamentally different, bass-heavy sound.
     * PLUCK's own factory-defaults test already documents this and expects
     * PERC for exactly this reason ("Classifier has no pitch feature...
     * every other voice must still read PERC"); SILK's plucked strings are
     * the same case, not a different one - the spec's "all four defaults
     * read TONAL" line was this plan's own mistake, not a design intent.
     *
     * GUZHENG is allowed to read SNARE too, the same exception PLUCK's own
     * test carries for BANJO: a bright fingerpicked default (PICK high,
     * per the spec) puts enough energy above the classifier's high-band
     * threshold to cross it, exactly the mechanism PLUCK's own comment
     * documents ("two thirds of its attack" above 2 kHz).
     */
    @Test
    fun `factory defaults classify as PERC, same as PLUCK's own plucked strings`() {
        for (voice in SilkVoice.entries) {
            val allowed = if (voice == SilkVoice.GUZHENG) setOf(DrumClass.PERC, DrumClass.SNARE) else setOf(DrumClass.PERC)
            val c = Classifier.classify(Silk.render(voice))
            assertTrue(c.drumClass in allowed, "$voice default classified ${c.drumClass}, expected one of $allowed")
        }
    }
}
