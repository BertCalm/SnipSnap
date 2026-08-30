package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionTest {

    private fun loopTrack(name: String, blocks: Int) =
        Track(name, (1..blocks).map { LoopBlock("$name-$it.wav") })

    private fun session(vararg sizes: Int, bpm: Float = 90f, bars: Int = 4) =
        Session(
            tracks = sizes.mapIndexed { i, n -> loopTrack("t$i", n) },
            bpm = bpm,
            barsPerInterval = bars,
            sampleRate = 48_000,
        )

    @Test
    fun `computes interval length in frames`() {
        // 4 bars at 90 bpm is 16 beats of 2/3 s = 10.667 s; at 48 kHz that is
        // 512000 frames. This number is the invariant every block bakes to.
        assertEquals(512_000, session(1, 1, 1, 1, 1, 1).intervalFrames)
    }

    @Test
    fun `interval length tracks bpm`() {
        val fast = session(1, 1, 1, 1, 1, 1, bpm = 180f)
        assertEquals(256_000, fast.intervalFrames)
    }

    @Test
    fun `requires exactly six tracks`() {
        val e = assertFailsWith<IllegalArgumentException> { session(1, 1, 1) }
        assertTrue(e.message!!.contains("6"), "message should name the count: ${e.message}")
    }

    @Test
    fun `rejects an empty chain`() {
        assertFailsWith<IllegalArgumentException> {
            Session(
                tracks = List(6) { Track("t$it", emptyList()) },
                bpm = 90f,
                barsPerInterval = 4,
                sampleRate = 48_000,
            )
        }
    }

    @Test
    fun `rejects a chain longer than the cap`() {
        assertFailsWith<IllegalArgumentException> { session(9, 1, 1, 1, 1, 1) }
    }

    @Test
    fun `rejects a bars per interval value that is not a power of two up to eight`() {
        assertFailsWith<IllegalArgumentException> { session(1, 1, 1, 1, 1, 1, bars = 3) }
    }

    @Test
    fun `tracks are engaged by default`() {
        assertTrue(session(1, 1, 1, 1, 1, 1).tracks.all { it.engaged })
    }
}
