package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockWatchTest {

    private val hold = 64
    private fun watch() = BlockWatch(SilenceWatch(holdFrames = hold))
    private fun silence(n: Int) = FloatArray(n)
    private fun sound(n: Int) = FloatArray(n) { 0.25f }

    @Test
    fun `silence while something plays is a block`() {
        val w = watch()
        // Short of the hold, there is no verdict either way: a gap between
        // words is not a blocked stream.
        assertFalse(w.feed(silence(32), 32) { true })
        assertFalse(w.blocked)
        // The hold is reached: now it is worth asking, and the answer is yes.
        assertTrue(w.feed(silence(32), 32) { true })
        assertTrue(w.blocked)
    }

    @Test
    fun `silence with nothing playing is not a block`() {
        // The armed-and-idle case, which must stay quiet: an app that is
        // not playing is not an app that is blocking.
        val w = watch()
        w.feed(silence(hold), hold) { false }
        assertFalse(w.blocked)
    }

    @Test
    fun `sound clears the verdict`() {
        val w = watch()
        w.feed(silence(hold), hold) { true }
        assertTrue(w.blocked)
        assertTrue(w.feed(sound(16), 16) { true })  // changed
        assertFalse(w.blocked)
    }

    @Test
    fun `the verdict does not flicker on the block after a tick`() {
        // The interaction this class exists to hold down. `feed` zeroes its
        // run when it ticks, so `silent` reads false on the tick block -
        // a clear-condition written against `silent` alone would drop the
        // verdict on the very next block while the stream is still dead.
        val w = watch()
        w.feed(silence(hold), hold) { true }
        assertTrue(w.blocked)
        repeat(5) {
            assertFalse(w.feed(silence(16), 16) { true })  // no change
            assertTrue(w.blocked, "the verdict dropped while the stream was still silent")
        }
    }

    @Test
    fun `the verdict comes back down when the playing stops`() {
        // A song paused mid-silence is not a block. Without this the flag
        // would latch true until sound arrived, and the screen would go on
        // reporting a problem that had gone away.
        val w = watch()
        w.feed(silence(hold), hold) { true }
        assertTrue(w.blocked)
        assertTrue(w.feed(silence(hold), hold) { false })  // next tick: changed
        assertFalse(w.blocked)
    }

    @Test
    fun `the platform is asked once per hold, never per block`() {
        // The binder call's whole cost argument. Nothing but this stops a
        // later edit from hoisting it out of the tick and running it at
        // block rate on the reader thread - about 21 times a second.
        val w = watch()
        var asked = 0
        val blocks = 40
        val per = 16  // 40 * 16 = 640 frames = ten holds
        repeat(blocks) { w.feed(silence(per), per) { asked++; true } }
        assertEquals(blocks * per / hold, asked)
        assertTrue(asked < blocks, "asked $asked times in $blocks blocks")
    }

    @Test
    fun `a change is reported exactly once`() {
        val w = watch()
        assertTrue(w.feed(silence(hold), hold) { true })   // false -> true
        assertFalse(w.feed(silence(hold), hold) { true })  // true -> true
        assertTrue(w.feed(sound(8), 8) { true })           // true -> false
        assertFalse(w.feed(sound(8), 8) { true })          // false -> false
    }

    @Test
    fun `reset forgets the run and the verdict`() {
        val w = watch()
        w.feed(silence(hold), hold) { true }
        assertTrue(w.blocked)
        w.reset()
        assertFalse(w.blocked)
        // And the run is gone with it: a full hold is needed again.
        assertFalse(w.feed(silence(hold - 1), hold - 1) { true })
    }

    @Test
    fun `forSeconds builds the hold the service asks for`() {
        val w = BlockWatch.forSeconds(3.0, 44_100)
        // Just short of three seconds is not yet a verdict.
        w.feed(silence(44_100), 44_100) { true }
        w.feed(silence(44_100), 44_100) { true }
        assertFalse(w.blocked)
        w.feed(silence(44_100), 44_100) { true }
        assertTrue(w.blocked)
    }
}
