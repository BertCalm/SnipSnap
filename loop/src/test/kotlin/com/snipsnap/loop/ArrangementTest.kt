package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrangementTest {

    private fun track(blocks: Int) = Track("t", (1..blocks).map { LoopBlock("b$it.wav") })

    private fun session(vararg sizes: Int) =
        Session(sizes.map { track(it) }, bpm = 90f, barsPerInterval = 4, sampleRate = 48_000)

    @Test
    fun `a single block chain repeats forever`() {
        for (i in 0 until 50) assertEquals(0, Arrangement.indexAt(1, i))
    }

    @Test
    fun `a two block chain alternates`() {
        assertEquals(listOf(0, 1, 0, 1, 0, 1), (0..5).map { Arrangement.indexAt(2, it) })
    }

    @Test
    fun `a three block chain cycles`() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2), (0..5).map { Arrangement.indexAt(3, it) })
    }

    @Test
    fun `chains of two and three realign every six intervals`() {
        // The phasing claim, stated as a test: they agree at 0 and not again
        // until 6.
        val agree = (0..12).filter { Arrangement.indexAt(2, it) == 0 && Arrangement.indexAt(3, it) == 0 }
        assertEquals(listOf(0, 6, 12), agree)
    }

    @Test
    fun `the sketch's six tracks repeat after twelve intervals`() {
        // Chains of 2, 3, 2, 1, 4, 2 — LCM(2,3,1,4) = 12. This is the worked
        // example in spec section 01.
        assertEquals(12, Arrangement.cycleIntervals(session(2, 3, 2, 1, 4, 2)))
    }

    @Test
    fun `all single block chains give a cycle of one`() {
        assertEquals(1, Arrangement.cycleIntervals(session(1, 1, 1, 1, 1, 1)))
    }

    @Test
    fun `coprime chains multiply out`() {
        // 5, 7 and 8 are pairwise coprime except 8 and 8: LCM(5,7,8,3,1,1) = 840.
        assertEquals(840, Arrangement.cycleIntervals(session(5, 7, 8, 3, 1, 1)))
    }

    @Test
    fun `the state at one cycle length matches the state at zero`() {
        val s = session(2, 3, 2, 1, 4, 2)
        val cycle = Arrangement.cycleIntervals(s)
        for (t in s.tracks) {
            assertEquals(
                Arrangement.blockAt(t, 0),
                Arrangement.blockAt(t, cycle),
                "track ${t.name} did not return to its first block",
            )
        }
    }

    @Test
    fun `shortening a chain past the playing index wraps instead of throwing`() {
        // Spec section 07: the current block finishes, then i % newSize takes
        // over. At interval 5 a 4-block chain is on index 1; cut to 2 blocks
        // and index 1 is still valid, cut to 1 and it must fall back to 0.
        assertEquals(1, Arrangement.indexAt(4, 5))
        assertEquals(1, Arrangement.indexAt(2, 5))
        assertEquals(0, Arrangement.indexAt(1, 5))
    }
}
