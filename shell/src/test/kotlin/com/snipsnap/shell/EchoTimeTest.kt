package com.snipsnap.shell

import com.snipsnap.kit.KitPreview
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EchoTimeTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-5f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun `free is first and is no time at all`() {
        assertEquals(0, EchoTime.FREE_INDEX)
        assertNull(EchoTime.DIVISIONS[EchoTime.FREE_INDEX])
        assertNull(EchoTime.seconds(EchoTime.FREE_INDEX, 120f), "the engine keeps its own time")
        assertEquals("FREE", EchoTime.label(EchoTime.FREE_INDEX))
    }

    @Test
    fun `a division is that fraction of PrintLength's bar at the kit's tempo`() {
        // 120 BPM: a bar is 2 s, so an eighth is a quarter second and the
        // dotted eighth three eighths of one.
        val bar = PrintLength.seconds(1, 120f)
        near(2f, bar)
        for ((i, d) in EchoTime.DIVISIONS.withIndex()) {
            if (d == null) continue
            near(bar * d.num / d.den, EchoTime.seconds(i, 120f)!!)
        }
        near(0.25f, EchoTime.seconds(EchoTime.DIVISIONS.indexOf(EchoTime.Division(1, 8)), 120f)!!)
        near(0.375f, EchoTime.seconds(EchoTime.DIVISIONS.indexOf(EchoTime.Division(3, 16)), 120f)!!)
        // Half the tempo, twice the time.
        near(0.5f, EchoTime.seconds(EchoTime.DIVISIONS.indexOf(EchoTime.Division(1, 8)), 60f)!!)
    }

    @Test
    fun `no tempo runs at the stand-in the modulators use`() {
        val i = EchoTime.DIVISIONS.indexOf(EchoTime.Division(1, 4))
        near(EchoTime.seconds(i, KitPreview.DEFAULT_BPM)!!, EchoTime.seconds(i, null)!!)
    }

    @Test
    fun `the labels are the note values, and the button cycles round`() {
        assertEquals(listOf("FREE", "1/16", "1/8", "3/16", "1/4", "1/2"), EchoTime.DIVISIONS.indices.map { EchoTime.label(it) })
        assertEquals(1, EchoTime.next(0))
        assertEquals(0, EchoTime.next(EchoTime.DIVISIONS.lastIndex), "round the loop")
    }

    @Test
    fun `every choice is within the engine's six-second ceiling at GROOVE's slowest tempo`() {
        // SurfaceEngine::kMaxEchoSeconds is 6 s, by hand here as kModTargets
        // is elsewhere; past it the engine falls back to the free time,
        // which would be a sync that silently is not one. The slowest
        // tempo GROOVE plays (KitPreview.MIN_BPM) is the longest bar a kit
        // can have, and half of it is the ceiling exactly.
        for (i in EchoTime.DIVISIONS.indices) {
            val s = EchoTime.seconds(i, KitPreview.MIN_BPM) ?: continue
            assertTrue(s <= 6f, "${EchoTime.label(i)} at ${KitPreview.MIN_BPM} BPM is $s s")
        }
        near(6f, EchoTime.seconds(EchoTime.DIVISIONS.lastIndex, KitPreview.MIN_BPM)!!)
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { EchoTime.seconds(-1, 120f) }
        assertFailsWith<IllegalArgumentException> { EchoTime.seconds(EchoTime.DIVISIONS.size, 120f) }
        assertFailsWith<IllegalArgumentException> { EchoTime.label(EchoTime.DIVISIONS.size) }
        assertFailsWith<IllegalArgumentException> { EchoTime.Division(0, 8) }
    }
}
