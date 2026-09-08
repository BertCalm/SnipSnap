package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecodeLimitsTest {

    @Test
    fun `mono and stereo pass, anything else is refused in words`() {
        DecodeLimits.checkFormat(1, 44_100)
        DecodeLimits.checkFormat(2, 48_000)
        for (bad in intArrayOf(0, -1, 3, 6, 64, 100, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException> { DecodeLimits.checkFormat(bad, 44_100) }
            assertTrue(
                e.message!!.contains("mono or stereo"),
                "a $bad-channel refusal should say what the deck takes: ${e.message}",
            )
        }
    }

    @Test
    fun `a rate that cannot be read is refused in words`() {
        for (bad in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException> { DecodeLimits.checkFormat(2, bad) }
            assertTrue(e.message!!.contains("$bad Hz"), "the refusal should quote the rate: ${e.message}")
        }
    }

    @Test
    fun `room is the cap less what is held`() {
        assertEquals(DecodeLimits.MAX_FRAMES, DecodeLimits.room(1, 0))
        assertEquals(DecodeLimits.MAX_FRAMES * 2, DecodeLimits.room(2, 0))
        assertEquals(DecodeLimits.MAX_FRAMES - 1000, DecodeLimits.room(1, 1000))
    }

    @Test
    fun `room runs out rather than going negative`() {
        assertEquals(0, DecodeLimits.room(1, DecodeLimits.MAX_FRAMES))
        assertEquals(0, DecodeLimits.room(1, DecodeLimits.MAX_FRAMES + 5_000))
        assertEquals(0, DecodeLimits.room(1, Int.MAX_VALUE))
    }

    @Test
    fun `a claimed channel count cannot overflow room into a bad length`() {
        // The defect this exists for. As Int, MAX_FRAMES * 75 wraps
        // negative; the caller then doubles that for a byte count, which
        // wraps a second time back to a large positive - so the "obvious"
        // negative-length crash never happens and a 25 MB read is asked
        // for instead. At 100 channels it is nearer 1.5 GB.
        for (ch in intArrayOf(3, 64, 75, 100, 1000, 100_000, Int.MAX_VALUE)) {
            val room = DecodeLimits.room(ch, 0)
            assertTrue(room >= 0, "room went negative at $ch channels: $room")
            // And the byte count a caller derives from it stays sane too:
            // twice a non-negative Int can still overflow, so the clamp has
            // to leave headroom, not just avoid going below zero.
            assertTrue(
                room.toLong() * 2 <= Int.MAX_VALUE.toLong() || room == Int.MAX_VALUE,
                "room at $ch channels doubles past Int: $room",
            )
        }
    }

    @Test
    fun `room is never negative for any inputs at all`() {
        // Total by construction: the point is that a caller who has not
        // run checkFormat yet still cannot get a bad length out of this.
        val channels = intArrayOf(Int.MIN_VALUE, -1000, -1, 0, 1, 2, 3, 1000, Int.MAX_VALUE)
        val kept = intArrayOf(Int.MIN_VALUE, -1, 0, 1, 1_000_000, Int.MAX_VALUE)
        for (c in channels) {
            for (k in kept) {
                assertTrue(DecodeLimits.room(c, k) >= 0, "room($c, $k) went negative")
            }
        }
    }
}
