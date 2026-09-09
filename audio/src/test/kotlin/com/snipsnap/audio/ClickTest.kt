package com.snipsnap.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickTest {

    @Test
    fun `a click is short, at the requested rate, mono`() {
        val rate = 48_000
        val snip = DrumSynth.click(rate, accent = false)
        assertEquals(1, snip.channels)
        assertEquals(rate, snip.sampleRate)
        // ~30ms by default: a timing cue, not a sample to loop or chop.
        assertTrue(snip.durationSeconds in 0.02f..0.05f, "expected roughly 30ms, got ${snip.durationSeconds}s")
    }

    @Test
    fun `amplitude never clips and the burst actually sounds`() {
        for (accent in listOf(true, false)) {
            val snip = DrumSynth.click(44_100, accent)
            val peak = snip.samples.maxOf { abs(it) }
            assertTrue(peak in 0.01f..1.0f, "peak $peak out of a sane, non-clipping range")
        }
    }

    @Test
    fun `the envelope decays - the tail is much quieter than the head`() {
        val snip = DrumSynth.click(44_100, accent = false)
        val head = abs(snip.samples[1])
        val tail = abs(snip.samples.last())
        assertTrue(tail < head * 0.05f, "tail ($tail) should have decayed well below the head ($head)")
    }

    @Test
    fun `downbeat and offbeat are different sounds, not the same click twice`() {
        val accent = DrumSynth.click(44_100, accent = true)
        val regular = DrumSynth.click(44_100, accent = false)
        assertTrue(!accent.samples.contentEquals(regular.samples), "accent and regular clicks must differ")
    }

    @Test
    fun `synthesis is deterministic - same inputs, same output`() {
        val a = DrumSynth.click(48_000, accent = true)
        val b = DrumSynth.click(48_000, accent = true)
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `length tracks sample rate, not a fixed frame count`() {
        val at44 = DrumSynth.click(44_100, accent = false)
        val at48 = DrumSynth.click(48_000, accent = false)
        assertTrue(at48.frameCount > at44.frameCount, "the same 30ms is more frames at a higher rate")
    }
}
