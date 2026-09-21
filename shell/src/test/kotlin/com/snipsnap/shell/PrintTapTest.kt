package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrintTapTest {

    @Test
    fun `a take before any block is null`() {
        assertNull(PrintTap.take(FloatArray(100), 0))
    }

    @Test
    fun `blocks append until the ceiling, which is never exceeded`() {
        val buffer = FloatArray(10)
        var filled = PrintTap.append(buffer, 0, floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(4, filled)
        filled = PrintTap.append(buffer, filled, floatArrayOf(5f, 6f, 7f, 8f))
        assertEquals(8, filled)
        // This block would overrun the ceiling by two - only the first two land.
        filled = PrintTap.append(buffer, filled, floatArrayOf(9f, 10f, 11f, 12f))
        assertEquals(10, filled)
        assertEquals((1..10).map { it.toFloat() }, buffer.toList())
        // Once full, another block changes nothing.
        filled = PrintTap.append(buffer, filled, floatArrayOf(99f, 99f))
        assertEquals(10, filled)
        assertEquals(10f, buffer.last())
    }

    @Test
    fun `take returns exactly what was written, trimmed to it`() {
        val buffer = FloatArray(20)
        val filled = PrintTap.append(buffer, 0, floatArrayOf(1f, 2f, 3f))
        assertEquals(listOf(1f, 2f, 3f), PrintTap.take(buffer, filled)?.toList())
    }

    @Test
    fun `a filled count outside the buffer is refused`() {
        assertFailsWith<IllegalArgumentException> { PrintTap.append(FloatArray(5), -1, floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { PrintTap.append(FloatArray(5), 6, floatArrayOf(1f)) }
    }
}
