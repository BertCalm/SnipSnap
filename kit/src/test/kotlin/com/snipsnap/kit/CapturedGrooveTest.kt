package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapturedGrooveTest {

    private val rate = 44_100

    private fun hit(slot: Int, frame: Long, length: Long = 22_050, vel: Float = 0.8f) =
        CapturedGroove.Hit(slot, frame, length, vel)

    @Test
    fun `pulse math is exact at a known tempo`() {
        // 120 BPM: a quarter is 0.5 s = 22050 frames = 960 pulses.
        val clip = CapturedGroove.clip(
            "G",
            listOf(hit(1, 0), hit(2, 11_025), hit(3, 22_050)),
            bpm = 120f, sampleRate = rate,
        )
        assertEquals(listOf(0L, 480L, 960L), clip.notes.map { it.timePulses })
        assertEquals(listOf(36, 37, 38), clip.notes.map { it.note }, "pad A0N plays 36+N-1")
        assertEquals(960L, clip.notes[0].lengthPulses, "22050 frames at 120bpm is a quarter")
        assertEquals(1, clip.bars)
    }

    @Test
    fun `the first hit anchors time - captures rarely start on the one`() {
        val clip = CapturedGroove.clip(
            "G",
            listOf(hit(1, 50_000), hit(2, 50_000 + 22_050)),
            bpm = 120f, sampleRate = rate,
        )
        assertEquals(0L, clip.notes.first().timePulses)
        assertEquals(960L, clip.notes.last().timePulses)
    }

    @Test
    fun `quantize snaps to the grid, as-captured keeps the push`() {
        // A hit 12 ms late of the eighth: 480 pulses + ~23 (1920 pulses/s at 120).
        val late = 11_025L + 529
        val loose = CapturedGroove.clip("G", listOf(hit(1, 0), hit(2, late)), 120f, rate)
        assertTrue(loose.notes.last().timePulses in 500L..506L, "as captured keeps the feel")

        val tight = CapturedGroove.clip(
            "G", listOf(hit(1, 0), hit(2, late)), 120f, rate,
            quantizeTo = Mpc3Clip.PULSES_PER_16TH,
        )
        assertEquals(480L, tight.notes.last().timePulses, "quantized to the 16th grid")
    }

    @Test
    fun `bars grow with the capture and velocities carry dynamics`() {
        // Second bar territory: 4100 pulses in.
        val frameAt4100 = (4100.0 / (120.0 / 60 * 960) * rate).toLong()
        val clip = CapturedGroove.clip(
            "G",
            listOf(hit(1, 0, vel = 1f), hit(2, frameAt4100, vel = 0.3f)),
            120f, rate,
        )
        assertEquals(2, clip.bars)
        assertEquals(listOf(1f, 0.3f), clip.notes.map { it.velocity })
    }

    @Test
    fun `note lengths are clamped into musical range`() {
        val tiny = CapturedGroove.clip("G", listOf(hit(1, 0, length = 10)), 120f, rate)
        assertEquals(CapturedGroove.MIN_NOTE_PULSES, tiny.notes.single().lengthPulses)

        val huge = CapturedGroove.clip("G", listOf(hit(1, 0, length = rate * 20L)), 120f, rate)
        assertEquals(CapturedGroove.MAX_NOTE_PULSES, huge.notes.single().lengthPulses)
    }

    @Test
    fun `bad input is refused with reasons`() {
        assertFailsWith<IllegalArgumentException> { CapturedGroove.clip("G", emptyList(), 120f, rate) }
        assertFailsWith<IllegalArgumentException> { CapturedGroove.clip("G", listOf(hit(1, 0)), 0f, rate) }
        assertFailsWith<IllegalArgumentException> { hit(0, 0) }
        assertFailsWith<IllegalArgumentException> { hit(1, 0, vel = 1.2f) }
    }
}
