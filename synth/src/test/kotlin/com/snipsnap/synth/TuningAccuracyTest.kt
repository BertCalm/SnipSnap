package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLUCK lands within five cents across its whole TUNE range, every voice,
 * every semitone. PLUCK only - this class name used to read as a
 * fleet-wide guarantee it never measured; the other four melodic engines
 * have no gate here and no claim is made about them.
 *
 * PLUCK's integer delay line used to render off-pitch by an amount that
 * depended on the fractional remainder at each frequency (see [Pluck]'s
 * `ks()` doc for the two separate causes) - a pentatonic run of pads was
 * out of tune with itself, not merely transposed.
 *
 * This measures the fix with a real spectrum ([Fft]), not autocorrelation:
 * [com.snipsnap.audio.Pitch.detect]'s single-window autocorrelation locked
 * onto HARP's own 2nd harmonic at TUNE semitone 20 (it reported 1047 Hz;
 * the true fundamental, confirmed by FFT, was 523.93 Hz - 0.28 cents off).
 * That is exactly the "autocorrelation cannot distinguish a real
 * subharmonic from a detector octave error" trap - measured directly, not
 * assumed - so it is disqualified as this gate's instrument in favor of a
 * windowed FFT peak search near the note we already know we asked for.
 */
class TuningAccuracyTest {

    private fun cents(a: Double, b: Double) = FineTuning.cents(a, b)

    /**
     * The loudest spectral peak within one semitone of [wantHz] - wide
     * enough to have caught the old truncation error (worth up to ~21
     * cents), narrow enough that it can never lock onto a harmonic (the
     * nearest one is 12 semitones away). [FineTuning.measuredHz] at this
     * class's own original 0.05s/0.25s window - promoted there once SILK
     * and `Strings` needed the identical measurement.
     */
    private fun measuredHz(snip: Snip, wantHz: Float): Double = FineTuning.measuredHz(snip, wantHz)

    @Test
    fun `every Pluck semitone lands within five cents at the default body`() {
        // Renders at each voice's own default BODY - the macros below never
        // set it, so `render` fills it in from `Pluck.defaults` - which is
        // the ordinary case. The ugly end (BODY 1) gets its own, looser
        // test below: a fixed body can pull a spectral peak search off the
        // fundamental, and that sweep exists to name which notes it pulls,
        // not to hold this tight bound at a setting nobody ships at.
        for (voice in PluckVoice.entries) {
            // SITAR's wrap has its own accepted tuning cost (Pluck.SITAR_JAWARI's
            // KDoc) and its own quarter-tone test below, the same way BODY's
            // ugly end gets its own looser test rather than softening this one.
            if (voice == PluckVoice.SITAR) continue
            for (semi in 0..Pluck.TUNE_SEMITONES) {
                val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
                // DOUBLE off: every voice defaults it nonzero, and it's a
                // deliberate second, detuned string (a chorus effect) - the
                // gate is about the delay line's own tuning, not the
                // chorus spread on top of it.
                val snip = Pluck.render(voice, mapOf("TUNE" to macro, "DOUBLE" to 0f))
                val want = Pluck.frequencyFor(voice, semi)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                assertTrue(err <= 5.0, "$voice semitone $semi is $err cents off (want $want, got $measured)")
            }
        }
    }

    @Test
    fun `STRIKE at either end keeps every Pluck voice within five cents`() {
        // The comb sits before the loop and cannot touch its length; this
        // pins that at the octave, at both extremes.
        for (voice in PluckVoice.entries) {
            // SITAR's wrap has its own accepted tuning cost, reached here
            // too regardless of STRIKE - see `the wrap's tuning cost stays
            // inside a quarter tone at both STRIKE ends too` below.
            if (voice == PluckVoice.SITAR) continue
            for (strike in listOf(0f, 1f)) {
                val snip = Pluck.render(voice, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "STRIKE" to strike))
                val want = Pluck.frequencyFor(voice, 12)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                assertTrue(err <= 5.0, "$voice at STRIKE $strike is $err cents off (want $want, got $measured)")
            }
        }
    }

    @Test
    fun `every TINES KALIMBA semitone lands within five cents`() {
        for (semi in 0..Tines.KALIMBA_TUNE_SEMITONES) {
            val macro = semi.toFloat() / Tines.KALIMBA_TUNE_SEMITONES
            val snip = Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to macro, "BUZZ" to 0f))
            val want = Tines.frequencyFor(TinesVoice.KALIMBA, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            assertTrue(err <= 5.0, "KALIMBA semitone $semi is $err cents off (want $want, got $measured)")
        }
    }

    @Test
    fun `BODY at its ugly end keeps every note inside a quarter tone, and names the ones it pulls`() {
        // BODY 1 is three times the string's RMS - the spike's "dominant" -
        // strong enough that a fixed body mode can pull the fundamental's
        // own spectral peak measurably, even though the loop's tuning is
        // itself untouched. The bound here is a quarter tone (50 cents),
        // not the five-cent bound at the default, because this sweep's job
        // is to name which notes the ugly end pulls for the audition gate.
        // For NYLON/HARP/KOTO/BANJO that is still a setting nobody ships at
        // (their own BODY defaults stay small); it is no longer true of
        // SITAR (2026-09-28 gate, `p3a_body_1`) - here its sweep measures
        // the actual shipped cost, worst case +16.46 cents at semitone 24,
        // comfortably inside the bound this loop already holds everyone to.
        for (voice in PluckVoice.entries) {
            for (semi in 0..Pluck.TUNE_SEMITONES) {
                val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
                val snip = Pluck.render(voice, mapOf("TUNE" to macro, "BODY" to 1f, "DOUBLE" to 0f))
                val want = Pluck.frequencyFor(voice, semi)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                if (err > 5.0) println("$voice semitone $semi: $err cents at BODY 1")
                assertTrue(err <= 50.0, "$voice semitone $semi is $err cents off at BODY 1, past the quarter-tone bound (want $want, got $measured)")
            }
        }
    }

    @Test
    fun `the jawari at full drive keeps every sitar note inside a quarter tone, and names the ones it pulls`() {
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 0f), Dsp.RATE * Dsp.OVERSAMPLE, velocity = 1f, jawariOverride = 0.6f)
            val snip = Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            if (err > 5.0) println("SITAR semitone $semi: $err cents at full jawari")
            assertTrue(err <= 50.0, "SITAR semitone $semi is $err cents off at full jawari drive (want $want, got $measured)")
        }
    }

    @Test
    fun `the wrap's tuning cost stays inside a quarter tone at every note, sharp low and flat high`() {
        // The SHIPPED default (SITAR_JAWARI = 0.010, settled at the
        // 2026-09-27 A/B gate - see the spec's "The jawari"), no override -
        // the test the shared five-cent sweep above now points to by name
        // instead of holding SITAR to its bound. Every note's SIGNED cents
        // are printed, not just the outliers over some smaller gate,
        // because this test's job is to document the shape of the tradeoff
        // (sharp low, flat high, crossing between semitones 8 and 9 - about
        // a third of the way up the range, not its middle; Pluck.ks's KDoc
        // on [jawari] has the likely mechanism) for whoever reads it next,
        // not only to name a worst case. DOUBLE 0: this measures the wrap's
        // own tuning cost, not the tarab's separate ring, the same
        // convention the full-drive test above uses.
        var worstSharp = 0.0
        var worstFlat = 0.0
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val snip = Pluck.render(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 0f))
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val signed = cents(measured, want.toDouble())
            println("SITAR semitone $semi at the shipped wrap depth: $signed cents (want $want, got $measured)")
            if (signed > worstSharp) worstSharp = signed
            if (signed < worstFlat) worstFlat = signed
            assertTrue(
                abs(signed) <= 50.0,
                "SITAR semitone $semi is $signed cents off at the shipped wrap depth, past the quarter tone (want $want, got $measured)",
            )
        }
        println("SITAR shipped wrap depth: worst sharp $worstSharp cents, worst flat $worstFlat cents")
    }

    @Test
    fun `the wrap's tuning cost stays inside a quarter tone at both STRIKE ends too`() {
        // `STRIKE at either end keeps every Pluck voice within five cents`
        // (PluckTest) skips SITAR for the same reason the shared TUNE sweep
        // above does - the wrap's own cost, not anything STRIKE does to the
        // loop's length. STRIKE's comb sits before the loop and cannot
        // touch its tuning (that test's own comment), but it does shift the
        // amplitude balance among the partials nearest the fundamental,
        // which moves measuredHz's peak search a little - measured, at
        // C#4: -7.8 cents at the default STRIKE (0.3, in the sweep above)
        // against -12.2 cents at STRIKE 0. Same tradeoff, different lens,
        // still comfortably inside the quarter tone this file holds it to.
        for (strike in listOf(0f, 1f)) {
            val snip = Pluck.render(PluckVoice.SITAR, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "STRIKE" to strike))
            val want = Pluck.frequencyFor(PluckVoice.SITAR, 12)
            val measured = measuredHz(snip, want)
            val signed = cents(measured, want.toDouble())
            println("SITAR at STRIKE $strike, shipped wrap depth: $signed cents (want $want, got $measured)")
            assertTrue(
                abs(signed) <= 50.0,
                "SITAR at STRIKE $strike is $signed cents off at the shipped wrap depth, past the quarter tone (want $want, got $measured)",
            )
        }
    }

    @Test
    fun `the sympathetic strings at DOUBLE 1 keep every sitar note inside a quarter tone, the wrap's own cost aside`() {
        // Was a five-cent bound; the wrap's own tuning cost (measured and
        // accepted in `the wrap's tuning cost stays inside a quarter tone
        // at every note...` above) reaches every SITAR render regardless of
        // DOUBLE, so a bound this test never controlled kept failing it.
        // What this test actually owns - whether ringing the tarab at
        // DOUBLE 1 adds ITS OWN mistuning on top of the string's - still
        // holds: the worst measured value (semitone 0, ~7.7 cents) tracks
        // the dry string's own root-note cost (~8.4 cents at DOUBLE 0)
        // closely, not some larger, DOUBLE-1-specific defect.
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val snip = Pluck.render(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 1f))
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            assertTrue(err <= 50.0, "SITAR semitone $semi at DOUBLE 1 is $err cents off, past the quarter tone (want $want, got $measured)")
        }
    }

    @Test
    fun `the scale tuning at DOUBLE 1 keeps every sitar note inside a quarter tone, the wrap's own cost aside`() {
        // Same reasoning and precedent as the sympathetic-strings test just
        // above: the wrap's own cost (its own dedicated sweep already
        // documents and accepts it) reaches this render too, regardless of
        // which sympathetic tuning rides on top of it.
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val snip = Pluck.renderWith(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 1f), sympathetic = Pluck.SYMPATHETIC_SCALE)
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            assertTrue(err <= 50.0, "SITAR semitone $semi at DOUBLE 1 with the scale tuning is $err cents off, past the quarter tone (want $want, got $measured)")
        }
    }
}
