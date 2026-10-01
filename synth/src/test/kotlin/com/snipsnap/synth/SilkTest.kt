package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilkTest {

    private fun cents(measured: Double, want: Double) = FineTuning.cents(measured, want)

    /** [FineTuning.measuredHz] - see that object for why a plain Goertzel scan isn't enough here. */
    private fun measuredHz(snip: Snip, wantHz: Float, fromSec: Float = 0.05f, bodySeconds: Float = 0.25f): Double =
        FineTuning.measuredHz(snip, wantHz, fromSec, bodySeconds)

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
     * PLUCK's own 5-cent rule, generalised: every degree of SANTUR's
     * default scale (SHUR) lands within 5 cents of the scale's own
     * target. Unlike OUD/GUZHENG, this is the test most likely to catch
     * a leftover Task 1 regression: SANTUR is the first voice actually
     * calling `Strings.course` at `count = 4` through the shared function
     * itself, not a per-voice custom loop. STIFF has no knob to disable
     * here (SANTUR's own B is fixed) - `StringsTest`'s own finding
     * already establishes the fundamental holds exact regardless (the
     * tuning budget charges the cascade's own delay before splitting the
     * loop length), so this isolates SCALE/TUNE/COURSE, not dispersion.
     */
    @Test
    fun `SANTUR is in tune at every degree of SHUR`() {
        val root = Silk.rootFor(SilkVoice.SANTUR)
        val scale = SilkScales.SHUR
        val size = scale.cents.size
        for (degree in 0..2 * size) {
            val tune = degree / (2f * size)
            val want = SilkScales.frequencyFor(root, scale, tune)
            val snip = Silk.render(SilkVoice.SANTUR, mapOf("TUNE" to tune, "COURSE" to 0f, "WASH" to 0f))
            val measured = measuredHz(snip, want)
            val off = cents(measured, want.toDouble())
            assertTrue(abs(off) <= 5.0, "degree $degree (tune=$tune): wanted $want Hz, measured $measured Hz ($off cents)")
        }
    }

    /**
     * PLUCK's own 5-cent rule, generalised, measured from 150 ms on (spec,
     * "Testing", item 1: "SHAMISEN is measured after its built-in glide
     * settles (from 150 ms)") - Task 2's own glide has fully settled onto
     * the plucked degree by its own end (100 ms), so this is 50 ms of
     * margin, not a tight bound.
     */
    @Test
    fun `SHAMISEN is in tune at every degree of MIYAKO_BUSHI`() {
        val root = Silk.rootFor(SilkVoice.SHAMISEN)
        val scale = SilkScales.MIYAKO_BUSHI
        val size = scale.cents.size
        for (degree in 0..2 * size) {
            val tune = degree / (2f * size)
            val want = SilkScales.frequencyFor(root, scale, tune)
            val snip = Silk.render(SilkVoice.SHAMISEN, mapOf("TUNE" to tune, "SAWARI" to 0f, "SLAP" to 0f))
            val measured = measuredHz(snip, want, fromSec = 0.15f)
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
     * SILK Phase 3, Task 2 (SHAMISEN's built-in glide): `Silk.shamisenLoop`
     * tested directly - the SHAMISEN voice itself doesn't exist yet
     * (Task 4), the same "internal fun, tested ahead of the full voice"
     * precedent `Silk.washModesFor` set in Phase 2. G1: "starts about
     * 3.5 % sharp ... falls to about 1.5 % within 100 ms" - the pitch
     * track should read clearly sharp early on and be settled onto the
     * plucked degree (not G1's own further ~0.8 % drift - see
     * `SHAMISEN_GLIDE_SECONDS`'s own KDoc for why) by 150 ms (spec,
     * "Testing", item 1).
     */
    @Test
    fun `SHAMISEN's built-in glide reads sharp early and settles onto the degree`() {
        val freq = 130.81f // C3, SHAMISEN's own root
        val damping = Strings.damping(0.3f, 7000f)
        val buf = Silk.shamisenLoop(
            freq, pick = 1f, seconds = 0.6f, damping = damping, pickHz = 9000f, position = 1f / 6f,
            jawari = 0f, dispersion = null, rate = Dsp.RATE, seed = 5,
        )
        val early = FineTuning.measuredHz(buf, Dsp.RATE, wantHz = freq * 1.03f, fromSec = 0.005f, bodySeconds = 0.03f)
        val late = FineTuning.measuredHz(buf, Dsp.RATE, wantHz = freq, fromSec = 0.15f, bodySeconds = 0.3f)
        assertTrue(early > freq * 1.005, "early in the glide ($early Hz) should still read clearly sharp of the target ($freq Hz)")
        assertTrue(abs(cents(late.toDouble(), freq.toDouble())) <= 5.0, "after the glide settles: wanted $freq Hz, measured $late Hz")
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

    /**
     * [Silk.washModesFor] (SILK Phase 2, SANTUR's WASH): every mode's own
     * frequency must be distinct - the plan review round's own finding
     * was that naively adding a separate "top-of-period" degree on top of
     * a full octave loop double-books the seam between consecutive
     * octaves (a period-top degree and the next octave's own degree 0 are
     * the identical frequency).
     */
    @Test
    fun `washModesFor produces no duplicate frequencies at the octave seams`() {
        for (scale in SilkScales.TABLE) {
            val modes = Silk.washModesFor(164.81f, scale, gain = 1f, t60 = 2f)
            val hz = modes.map { it.ratio }
            val distinct = hz.toSet()
            assertEquals(hz.size, distinct.size, "${scale.cents.size}-degree scale: duplicate frequencies in $hz")
        }
    }

    /** RMS over [samples] from [fromSec] to [toSec], 0 for any part of that window past the buffer's own end (silence, not an index error). */
    private fun tailRms(samples: FloatArray, rate: Int, fromSec: Float, toSec: Float): Double {
        val from = (fromSec * rate).toInt()
        val to = (toSec * rate).toInt()
        var acc = 0.0
        var n = 0
        for (i in from until to) {
            val v = if (i < samples.size) samples[i].toDouble() else 0.0
            acc += v * v
            n++
        }
        return kotlin.math.sqrt(acc / n.coerceAtLeast(1))
    }

    /**
     * The spec's own two WASH claims (SILK Phase 2, "Testing"), checked
     * directly on [Silk.washModesFor] plus [Strings.bodyRing] - the same
     * two calls SANTUR's own voice will chain once it exists (Task 5),
     * so this doesn't wait on that voice to verify the bank itself.
     */
    @Test
    fun `WASH rings on after the driven string, on the scale's own degrees`() {
        val root = 164.81f // E3, SANTUR's own root
        val scale = SilkScales.SHUR // SANTUR's own default
        val rate = Dsp.RATE
        val damping = Strings.damping(0.8f, 6500f) // high DAMP: a short-lived driven string
        val string = Strings.pluck(root, 0.5f, damping, 8000f, seed = 1, rate = rate)

        val washT60 = 2f
        val modes = Silk.washModesFor(root, scale, gain = 1f, t60 = washT60)
        val dry = Strings.bodyRing(string, modes, amount = 0f, rate = rate, ceilingSeconds = 4f)
        val wet = Strings.bodyRing(string, modes, amount = 1f, rate = rate, ceilingSeconds = 4f)

        // Well past the driven string's own 0.5 s buffer, where `dry` (WASH
        // 0, bodyRing's own no-op) has nothing left to contribute at all.
        val dryTail = tailRms(dry, rate, 1.0f, 1.3f)
        val wetTail = tailRms(wet, rate, 1.0f, 1.3f)
        assertTrue(wetTail > dryTail * 5.0, "WASH's own tail should ring on well past the driven string: dry=$dryTail, wet=$wetTail")

        // The tail's own spectral peaks land on SCALE's degrees, not
        // arbitrary points - checked at three of washModesFor's own modes
        // (the root, an interior degree, and the span's own top note).
        val checkHz = listOf(root, root * 2f.pow(scale.cents[scale.cents.size / 2] / 1200f), root * 2f.pow(3f))
        for (hz in checkHz) {
            val measured = FineTuning.measuredHz(wet, rate, hz, fromSec = 1.0f, bodySeconds = 0.5f)
            val off = FineTuning.cents(measured, hz.toDouble())
            assertTrue(abs(off) <= 20.0, "expected a WASH peak near $hz Hz, measured $measured Hz ($off cents)")
        }
    }

    /**
     * SILK Phase 3, Task 3 (SHAMISEN's SLAP): checked directly on
     * [Silk.withSlap] - the same "internal fun, tested ahead of the full
     * voice" precedent as [Silk.washModesFor] above. SLAP 0 is the plain
     * string, byte for byte (every character macro's own "0 is the input"
     * contract). Past `withSlap`'s own drive length (the burst plus the
     * skin table's own pad), its wet contribution is exactly zero by
     * construction, not merely decayed - so the render is byte-identical
     * to the dry string there too, a stronger and cheaper check than a
     * tail-RMS comparison.
     */
    @Test
    fun `SLAP 0 is the plain string, and past its own burst SLAP 1 changes nothing`() {
        val rate = Dsp.RATE
        val damping = Strings.damping(0.3f, 7000f)
        val string = Strings.pluck(130.81f, 0.6f, damping, 9000f, seed = 5, rate = rate)

        val dry = Silk.withSlap(string, slap = 0f, rate = rate, seed = 9)
        assertContentEquals(string, dry, "SLAP 0 should return the string untouched")

        val wet = Silk.withSlap(string, slap = 1f, rate = rate, seed = 9)
        val pastBurst = (0.2f * rate).toInt() // well past the ~20 ms burst plus the skin table's own pad
        for (i in pastBurst until string.size) {
            assertEquals(string[i], wet[i], "past the burst, SLAP should contribute nothing at sample $i")
        }
    }

    /**
     * The spec's own claim (spec, "Testing": "the first 20 ms gains
     * broadband energy with SLAP; the tail does not").
     */
    @Test
    fun `SLAP gains broadband energy in its first 20 ms, and nowhere past it`() {
        val rate = Dsp.RATE
        val damping = Strings.damping(0.3f, 7000f)
        val string = Strings.pluck(130.81f, 0.6f, damping, 9000f, seed = 5, rate = rate)
        val wet = Silk.withSlap(string, slap = 1f, rate = rate, seed = 9)

        val window = (0.02f * rate).toInt()
        fun rms(buf: FloatArray, from: Int, len: Int): Double {
            var acc = 0.0
            for (i in from until from + len) acc += buf[i].toDouble() * buf[i]
            return kotlin.math.sqrt(acc / len)
        }
        val dryOnset = rms(string, 0, window)
        val wetOnset = rms(wet, 0, window)
        assertTrue(wetOnset > dryOnset, "the first 20 ms should gain broadband energy with SLAP: dry=$dryOnset wet=$wetOnset")

        val tailFrom = (0.3f * rate).toInt()
        val dryTail = rms(string, tailFrom, window)
        val wetTail = rms(wet, tailFrom, window)
        assertEquals(dryTail, wetTail, "well past the burst, SLAP should not still be adding energy")
    }

    /**
     * Copilot's own finding on PR #414: the two tests above only check
     * that SLAP adds *some* energy, which also passed under the bug this
     * PR fixed (a three-orders-of-magnitude-too-small gain) - neither
     * would catch a regression back to it. This checks the actual
     * magnitude against [withSlap]'s own convention ("this many multiples
     * of the string's own loudness"): at SLAP 1, the isolated wet
     * contribution's RMS over the burst-plus-pad window should land near
     * the *string's* RMS over that same window, not three orders under it
     * (the string's RMS over its own much longer, quieter-on-average full
     * note - the exact mismatch the bug had).
     */
    @Test
    fun `SLAP 1's own contribution matches the string's own loudness, not three orders under it`() {
        val rate = Dsp.RATE
        val damping = Strings.damping(0.9f, 7000f)
        val string = Strings.pluck(130.81f, 1.5f, damping, 9000f, seed = 5, rate = rate)
        val wet = Silk.withSlap(string, slap = 1f, rate = rate, seed = 9)

        val window = (0.17f * rate).toInt() // the burst (20 ms) plus the skin table's own pad (150 ms t60)
        fun rms(buf: FloatArray, len: Int): Double {
            var acc = 0.0
            for (i in 0 until len) acc += buf[i].toDouble() * buf[i]
            return kotlin.math.sqrt(acc / len)
        }
        val contribution = FloatArray(window) { wet[it] - string[it] }
        val ratio = rms(contribution, window) / rms(string, window)
        assertTrue(ratio in 0.5..2.0, "SLAP 1's own contribution should land within 2x of the string's own loudness over the same window: ratio=$ratio")
    }
}
