package com.snipsnap.synth

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GLINT held as a keys instrument (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3). */
class GlintHeldTest {

    @Test
    fun `nine zones every minor third from the voice's own root`() {
        assertEquals((45..69 step 3).toList(), Keys.glintPadMidis(GlintVoice.SWEEP))
        assertEquals((57..81 step 3).toList(), Keys.glintPadMidis(GlintVoice.STEP))
    }

    @Test
    fun `a midi outside the zones is refused`() {
        assertFailsWith<IllegalArgumentException> { Keys.glintPad(GlintVoice.SWEEP, emptyMap(), 44) }
        assertFailsWith<IllegalArgumentException> { Keys.glintPad(GlintVoice.SWEEP, emptyMap(), 70) }
    }

    @Test
    fun `the marker sits at the loop and the loop closes`() {
        val note = Keys.glintPad(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f), 57)
        assertTrue(note.loopStartFrame > 0)
        assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `every zone lands on its MIDI pitch`() {
        // Not Pitch.detect on the rendered audio. It answers in whole-frame lags, and this
        // waveform's formant sits several harmonics above f0 (k = 9 at MIDI 45 and 48 with
        // the default PEAK and FOLLOW, 8 or 7 above), so its smallest-lag rule locks onto the
        // ninth harmonic at those two zones (measured: MIDI 45 reads 1002 Hz, MIDI 48 reads
        // 1192 Hz, both about 3825 cents up). At the other seven zones its readings are
        // 44100 / f rounded to a whole lag (up to 4.5 cents, at MIDI 60): the detector's
        // resolution, not the engine's tuning.
        //
        // The pitch is the breath plan's: N whole cycles in L whole frames, f0' = N * rate / L
        // (GlintBreatheTest pins the plan and that the audio closes on it). So this pins the
        // two things Keys.glintPad adds: the pad IS the held render at the zone's own TUNE,
        // marker included, and the plan for that TUNE lands on the key's MIDI pitch.
        val voice = GlintVoice.SWEEP
        val macros = mapOf("BLOOM" to 0.5f)
        val zones = Keys.glintPadMidis(voice)
        for (midi in zones) {
            val tune = (midi - zones.first()) / Glint.TUNE_SEMITONES.toFloat()
            val note = Keys.glintPad(voice, macros, midi)
            val direct = GlintHeld.render(voice, Glint.defaults(voice) + macros + ("TUNE" to tune))
            assertTrue(note.snip.samples.contentEquals(direct.audio), "MIDI $midi: the pad is not the held render at TUNE $tune")
            assertEquals(direct.loopStart.toLong(), note.loopStartFrame, "MIDI $midi: the marker moved")

            val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, tune))
            val want = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val cents = abs(1200 * log2(plan.f0 / want))
            println(
                "GLINT SWEEP MIDI $midi: ${plan.cycles} cycles in ${plan.loopFrames} frames = %.4f Hz, want %.4f Hz, %.5f cents"
                    .format(Locale.ROOT, plan.f0, want, cents),
            )
            assertTrue(cents < 5, "MIDI $midi plays ${plan.f0} Hz, $cents cents off")
        }
    }

    @Test
    fun `the zone's TUNE comes from the key, not the panel`() {
        val a = Keys.glintPad(GlintVoice.BRASS, mapOf("TUNE" to 0f), 48)
        val b = Keys.glintPad(GlintVoice.BRASS, mapOf("TUNE" to 1f), 48)
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
    }

    @Test
    fun `a held zone nobody wants any more stops`() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            Keys.glintPad(GlintVoice.STEP, mapOf("BLOOM" to 1f), 57) { true }
        }
    }

    @Test
    fun `every voice's first and last zone is on pitch, ends one breath after the marker and closes`() {
        for (voice in GlintVoice.entries) {
            val zones = Keys.glintPadMidis(voice)
            for (midi in listOf(zones.first(), zones.last())) {
                val note = Keys.glintPad(voice, emptyMap(), midi)
                val s = note.snip.samples
                val marker = note.loopStartFrame.toInt()
                val tune = (midi - zones.first()) / Glint.TUNE_SEMITONES.toFloat()
                val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, tune))
                // STEP's roots sit an octave above the others': a rootMidi that disagrees with
                // its rootHz fails here.
                val want = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val cents = abs(1200 * log2(plan.f0 / want))
                assertTrue(cents < 5, "$voice MIDI $midi plays ${plan.f0} Hz, $cents cents off")
                // The loop's length can't say which TUNE the pad was rendered at: 110, 220, 440
                // and 880 Hz all fill exactly 3 s, so a zone sent to TUNE 0.5 has the root's and
                // the top's 132300-frame loop. The render comparison can.
                val direct = GlintHeld.render(voice, Glint.defaults(voice) + ("TUNE" to tune))
                assertTrue(s.contentEquals(direct.audio), "$voice MIDI $midi: the pad is not the held render at TUNE $tune")
                assertEquals(direct.loopStart, marker, "$voice MIDI $midi: the marker moved")
                assertEquals(plan.loopFrames, s.size - marker, "$voice MIDI $midi: the file ends one breath after the marker")
                assertTrue(marker > plan.loopFrames, "$voice MIDI $midi: an onset and a whole first breath come before the marker")
                val seam = Keys.seamError(s, marker)
                assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice MIDI $midi: seam $seam")
                println(
                    "GLINT pad $voice MIDI $midi: ${plan.cycles} cycles in ${plan.loopFrames} frames, " +
                        "loopStart $marker, file ${s.size} frames (%.3f s), seam $seam, plan %.5f cents"
                            .format(Locale.ROOT, s.size.toDouble() / note.snip.sampleRate, cents),
                )
            }
        }
    }
}
