package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GLINT's DEPTH on the held pad (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.3 and §4). */
class GlintDepthHeldTest {

    @Test
    fun `every voice's loop closes at DEPTH 0_3, 0_75 and 1 - the seam and the second breath`() {
        for (voice in GlintVoice.entries) {
            for (depth in listOf(0.3f, 0.75f, 1f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to 0.8f, "TUNE" to tune, "DEPTH" to depth))
                    val what = "$voice DEPTH $depth TUNE $tune"
                    assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR, "$what: the loop does not close")
                    // The second breath is the first, sample for sample, from its 64th frame on
                    // (see GlintBreatheTest: by then the decimator has forgotten the onset).
                    val len = held.audio.size - held.loopStart
                    var maxDiff = 0f
                    for (j in 64 until len) {
                        maxDiff = maxOf(maxDiff, abs(held.audio[held.loopStart - len + j] - held.audio[held.loopStart + j]))
                    }
                    assertEquals(0f, maxDiff, "$what: the second breath differs from the first")
                }
            }
        }
    }

    @Test
    fun `DEPTH 1 holds a bare sine at the note's pitch`() {
        for (voice in GlintVoice.entries) {
            val held = GlintHeld.render(voice, mapOf("DEPTH" to 1f))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            val size = Fft.floorPowerOfTwo(loop.size)
            val mag = Fft.magnitudeSpectrum(loop, size)
            val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, Glint.defaults(voice).getValue("TUNE")))
            val bin = Math.round(plan.f0 * size / Dsp.RATE).toInt()
            var total = 0.0
            var near = 0.0
            for (b in 1 until mag.size) {
                val e = mag[b].toDouble() * mag[b]
                total += e
                if (abs(b - bin) <= 2) near += e
            }
            assertTrue(near / total >= 0.999, "$voice: only ${near / total} of the loop's energy is within two bins of f0")
        }
    }

    @Test
    fun `at DEPTH 1 the voice does not matter - PEAK, BLOOM and BODY leave the loop alone`() {
        // SWEEP and VOWEL only: STEP's ladder length moves where the loop starts, and BRASS's level breathes with BLOOM.
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.VOWEL)) {
            val a = GlintHeld.render(voice, mapOf("DEPTH" to 1f, "PEAK" to 0.1f, "BLOOM" to 0.9f, "BODY" to 0.1f))
            val b = GlintHeld.render(voice, mapOf("DEPTH" to 1f, "PEAK" to 0.9f, "BLOOM" to 0.2f, "BODY" to 0.9f))
            val la = a.audio.copyOfRange(a.loopStart, a.audio.size)
            val lb = b.audio.copyOfRange(b.loopStart, b.audio.size)
            assertEquals(la.size, lb.size, "$voice: loop length")
            var worst = 0f
            for (i in la.indices) worst = maxOf(worst, abs(la[i] - lb[i]))
            assertTrue(worst < 1e-4f, "$voice: two different voices at DEPTH 1 differ by $worst")
        }
    }

    @Test
    fun `all nine zones of a SWEEP pad close at DEPTH 0_6, as MAKE INSTRUMENT renders them`() {
        // glintPad itself refuses a loop that does not close (requireSeam), so rendering is the check.
        for (midi in Keys.glintPadMidis(GlintVoice.SWEEP)) {
            val note = Keys.glintPad(GlintVoice.SWEEP, mapOf("BLOOM" to 0.85f, "DEPTH" to 0.6f), midi)
            assertTrue(note.loopStartFrame > 0, "SWEEP MIDI $midi")
        }
    }

    @Test
    fun `VOWEL's lowest, middle and highest zones close at DEPTH 0_6`() {
        val midis = Keys.glintPadMidis(GlintVoice.VOWEL)
        for (midi in listOf(midis.first(), midis[4], midis.last())) {
            val note = Keys.glintPad(GlintVoice.VOWEL, mapOf("BLOOM" to 0.85f, "DEPTH" to 0.6f), midi)
            assertTrue(note.loopStartFrame > 0, "VOWEL MIDI $midi")
        }
    }

    @Test
    fun `VOWEL at the top of TUNE with PEAK 0, where F1 pins to the fundamental, closes at DEPTH 0_75 and 1`() {
        for (depth in listOf(0.75f, 1f)) {
            val held = GlintHeld.render(GlintVoice.VOWEL, mapOf("TUNE" to 1f, "PEAK" to 0f, "BLOOM" to 0.5f, "DEPTH" to depth))
            assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR, "DEPTH $depth: the loop does not close")
            assertTrue(held.audio.all { it.isFinite() && abs(it) <= 0.99f + 1e-6f }, "DEPTH $depth: out of range")
        }
    }
}
