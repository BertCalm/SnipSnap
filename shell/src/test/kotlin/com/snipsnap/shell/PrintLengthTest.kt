package com.snipsnap.shell

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrintLengthTest {

    private fun near(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-4f, "expected $expected, got $actual")

    @Test
    fun `bars at a tempo are seconds`() {
        near(10f, PrintLength.seconds(4, 96f))   // 16 beats at 0.625 s
        near(2f, PrintLength.seconds(1, 120f))
        near(3f, PrintLength.seconds(1, 60f, beatsPerBar = 3))
    }

    @Test
    fun `free and nonsense are refused in words, and the labels read as a player says them`() {
        assertFailsWith<IllegalArgumentException> { PrintLength.seconds(0, 120f) }
        assertFailsWith<IllegalArgumentException> { PrintLength.seconds(2, 0f) }
        assertFailsWith<IllegalArgumentException> { PrintLength.seconds(2, Float.NaN) }
        assertEquals(listOf("FREE", "1 BAR", "2 BARS", "4 BARS", "8 BARS"), PrintLength.BARS.map { PrintLength.label(it) })
    }
}
