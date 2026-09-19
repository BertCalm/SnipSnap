package com.snipsnap.shell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RING freezes the ring's last few seconds into a SURFACE voice - once, on the tap. */
class RingSlotTest {

    private val rate = 44_100

    /** Half a second of room, then a tone that runs to the end - the ring's tail as the tap finds it. */
    private fun tail(): FloatArray = FloatArray(RingSlot.frames(rate)) { i ->
        if (i < rate / 2) 0f else (0.3 * sin(2.0 * PI * 220.0 * i / rate)).toFloat()
    }

    @Test
    fun `a freeze asks the ring for its last few seconds`() {
        assertEquals(4 * 44_100, RingSlot.frames(44_100))
        assertEquals(4 * 48_000, RingSlot.frames(48_000))
        assertEquals("RING 4.0 S", RingSlot.label(4f))
        assertEquals("RING 1.5 S", RingSlot.label(1.5f), "the readout wears what was kept, not the four asked for")
    }

    @Test
    fun `a freeze is the tail cleaned, at the ring's own rate`() {
        val voice = assertNotNull(RingSlot.freeze(tail(), rate))
        assertEquals(1, voice.channels)
        assertEquals(rate, voice.sampleRate)
        // The half second of room at the head is gone (a pre-roll of a few
        // frames stays, so a transient at the very edge survives)...
        assertTrue(voice.frameCount < RingSlot.frames(rate) - rate / 2 + 1_000, "trimmed to ${voice.frameCount} frames")
        // ...and the tone is levelled to the shelf's peak, not left at the
        // ring's own quiet 0.3.
        val peak = voice.samples.maxOf { abs(it) }
        assertTrue(peak > 0.9f, "levelled, got a peak of $peak")
    }

    @Test
    fun `a quiet room, an empty ring and a torn scrap are nothing, not a voice`() {
        assertNull(RingSlot.freeze(FloatArray(RingSlot.frames(rate)), rate), "digital silence")
        assertNull(RingSlot.freeze(FloatArray(0), rate), "a ring that has not started")
        // Below the shelf's own floor for dead air (-60 dBFS), so the trim
        // takes all of it: a quiet room is not a voice either.
        assertNull(RingSlot.freeze(FloatArray(rate) { i -> if (i % 2 == 0) 1e-4f else -1e-4f }, rate), "a quiet room")
    }

    @Test
    fun `a hot moment is kept, not refused`() {
        // A capture the doctor would call distorted: a clipped square at
        // full scale. RING is for exactly this, so it loads.
        val hot = FloatArray(rate) { i -> if ((i / 100) % 2 == 0) 1f else -1f }
        val voice = assertNotNull(RingSlot.freeze(hot, rate))
        assertTrue(voice.frameCount > rate / 2)
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { RingSlot.frames(0) }
        assertFailsWith<IllegalArgumentException> { RingSlot.freeze(FloatArray(10), 0) }
    }

    @Test
    fun `every bar counts the modulators' own bar from the same origin`() {
        // 120 BPM, 4/4: one bar is two seconds - PrintLength's arithmetic,
        // Modulator's arithmetic, and now RING's, one place.
        assertEquals(Modulator.periodSeconds(Modulator.DEFAULT_RATE_INDEX, 120f), RingSlot.barSeconds(120f))
        assertEquals(PrintLength.seconds(1, 120f), RingSlot.barSeconds(120f))
        assertEquals(PrintLength.seconds(1, com.snipsnap.kit.KitPreview.DEFAULT_BPM), RingSlot.barSeconds(null), "no tempo runs at GROOVE's default")
        val bar = RingSlot.barSeconds(120f)
        assertEquals(0L, RingSlot.barIndex(0.0, bar))
        assertEquals(0L, RingSlot.barIndex(1.99, bar))
        assertEquals(1L, RingSlot.barIndex(2.0, bar))
        assertEquals(3L, RingSlot.barIndex(7.5, bar))
        // Time before the origin, or none at all, is the first bar.
        assertEquals(0L, RingSlot.barIndex(-5.0, bar))
        assertEquals(0L, RingSlot.barIndex(Double.NaN, bar))
        assertEquals(listOf(RingSlot.Refresh.ONCE, RingSlot.Refresh.EVERY_BAR), RingSlot.Refresh.entries)
        assertFailsWith<IllegalArgumentException> { RingSlot.barIndex(1.0, 0f) }
        assertFailsWith<IllegalArgumentException> { RingSlot.barIndex(1.0, Float.NaN) }
    }
}
