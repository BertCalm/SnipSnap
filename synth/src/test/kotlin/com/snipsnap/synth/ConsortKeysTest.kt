package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CONSORT's held keys ([ConsortKeys]): each sample its attack then a sustain loop cut from a held note whose slow
 * motions, noise and per-player pitch repeat every two seconds. The instrument (zones, windows, export, playback)
 * is pinned in `:shell`'s `ConsortPadMakerTest`.
 */
class ConsortKeysTest {
    private val rate = Dsp.RATE

    private fun plain(voice: ConsortVoice) = ConsortPresets.forVoice(voice).first { it.chairs.isEmpty() }

    @Test
    fun `nine zones by minor thirds across each voice's two octaves, six layers a zone in keygroup order`() {
        for (voice in ConsortVoice.entries) {
            val root = Consort.rootMidi(voice)
            assertEquals((0..24 step 3).map { root + it }, ConsortKeys.padMidis(voice))
            val cells = ConsortKeys.cells(plain(voice))
            assertEquals(9 * ConsortKeys.LAYERS, cells.size)
            assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1), cells.take(6).map { it.touch to it.take })
        }
        assertEquals(listOf(0.3f, Consort.DEFAULT_VELOCITY, 1f), ConsortKeys.TOUCHES)
        assertEquals(listOf(0, 2), ConsortKeys.TAKES)
        assertFailsWith<IllegalArgumentException> { ConsortKeys.zone(plain(ConsortVoice.SAX), 47) }
    }

    @Test
    fun `every voice's bottom, middle and top keys loop at four seconds with the wave matched and no dip`() {
        for (voice in ConsortVoice.entries) for (midi in ConsortKeys.padMidis(voice).let { listOf(it.first(), it[4], it.last()) }) {
            for (touch in ConsortKeys.TOUCHES.indices) {
                val held = ConsortKeys.measured(plain(voice), ConsortKeys.Cell(midi, touch, 0))
                val label = "$voice $midi touch $touch"
                assertTrue(held.join.correlation >= Bore.LOOP_MIN_CORRELATION, "$label correlates ${held.join.correlation}")
                assertTrue(held.join.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "$label dips ${held.join.blendDipDb}")
                assertTrue(abs(held.join.lengthFrames - Consort.KEYS_PERIOD_SECONDS * rate) <= rate / 40,
                    "$label loops at ${held.join.lengthFrames} frames, not its period")
                assertTrue(held.note.snip.samples.all { it.isFinite() && it in -1f..1f }, label)
            }
        }
    }

    @Test
    fun `every provisional section loops on its top key, the hardest place for its chairs`() {
        for (preset in ConsortPresets.all().filter { it.chairs.isNotEmpty() }) {
            val held = ConsortKeys.measured(preset, ConsortKeys.Cell(ConsortKeys.padMidis(preset.voice).last(), 1, 0))
            assertTrue(held.join.correlation >= ConsortKeys.SECTION_MIN_CORRELATION, "${preset.name} correlates ${held.join.correlation}")
            assertTrue(held.join.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "${preset.name} dips ${held.join.blendDipDb}")
        }
        assertEquals(ConsortKeys.SECTION_MIN_CORRELATION, ConsortKeys.minCorrelation(ConsortPresets.all().first { it.chairs.isNotEmpty() }))
        assertEquals(Bore.LOOP_MIN_CORRELATION, ConsortKeys.minCorrelation(plain(ConsortVoice.OBOE)))
    }

    @Test
    fun `every gesture holds and loops, flutter on its level guard alone`() {
        for (gesture in ConsortGesture.entries) for (voice in listOf(ConsortVoice.FLUTE, ConsortVoice.OBOE)) {
            val patch = plain(voice).withMacros(plain(voice).macros + ("GESTURE" to Consort.gestureValue(gesture)) + ("MOTION" to 1f))
            val held = ConsortKeys.measured(patch, ConsortKeys.Cell(ConsortKeys.padMidis(voice)[4], 2, 1))
            assertTrue(held.join.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "$voice $gesture dips ${held.join.blendDipDb}")
            assertTrue(held.join.correlation >= ConsortKeys.minCorrelation(patch), "$voice $gesture correlates ${held.join.correlation}")
        }
        val flutter = plain(ConsortVoice.FLUTE).withMacros(plain(ConsortVoice.FLUTE).macros + ("GESTURE" to Consort.gestureValue(ConsortGesture.FLUTTER)))
        assertEquals(0.0, ConsortKeys.minCorrelation(flutter))
    }

    @Test
    fun `the touches differ by timbre at one loudness, the takes differ, and a key never overblows`() {
        val patch = plain(ConsortVoice.FLUTE)
        val midi = ConsortKeys.padMidis(ConsortVoice.FLUTE)[4]
        val notes = ConsortKeys.TOUCHES.indices.map { ConsortKeys.measured(patch, ConsortKeys.Cell(midi, it, 0)) }
        fun loop(h: BoreKeys.Held) = h.note.snip.samples.copyOfRange(h.join.startFrames, h.join.startFrames + h.join.lengthFrames)
        val loud = notes.map { Loudness.of(loop(it), 1, rate) }
        for (l in loud) assertEquals(Dsp.MELODIC_LOUDNESS_TARGET, l, 0.02f, "touches are levelled on their loops")
        val bright = notes.map { FeatureExtractor.extract(Snip(loop(it), 1, rate)).centroidHz }
        assertTrue(bright[0] < bright[1] && bright[1] < bright[2], "harder touches are brighter: $bright")
        // The hard touch is blown under the overblow line: the flute stays on its note, not the octave.
        val hard = Snip(loop(notes[2]), 1, rate)
        val pitch = Pitch.detect(hard)!!
        assertTrue(abs(1200 * Math.log(pitch.hz.toDouble() / Keys.midiHz(midi)) / Math.log(2.0)) < 50, "hard key plays ${pitch.hz} Hz for MIDI $midi")
        val takes = ConsortKeys.TAKES.indices.map { ConsortKeys.render(patch, ConsortKeys.Cell(midi, 1, it)).snip.samples }
        assertFalse(takes[0].contentEquals(takes[1]), "the two takes are one performance")
    }

    @Test
    fun `a key starts its loop after the shaped onset, is deterministic, and can be cancelled`() {
        val patch = ConsortPresets.all().first { it.chairs.size == 2 }
        val cell = ConsortKeys.Cell(ConsortKeys.padMidis(patch.voice)[2], 1, 1)
        val a = ConsortKeys.render(patch, cell)
        assertTrue(a.loopStartFrame >= (HeldStart.SHORT.handover * rate).toLong() - 1 && a.loopStartFrame < rate, "loop starts at ${a.loopStartFrame}")
        assertTrue(a.loopStartFrame < a.snip.frameCount)
        assertContentEquals(a.snip.samples, ConsortKeys.render(patch, cell).snip.samples)
        assertFailsWith<CancellationException> { ConsortKeys.render(patch, cell) { true } }
    }
}
