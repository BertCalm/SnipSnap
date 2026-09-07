package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmTest {

    @Test
    fun `int16 reads little-endian, full scale both ways, ignoring a trailing odd byte`() {
        // 0x7FFF, 0x8000, 0x0000, 0x4000 then one stray byte.
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x40, 0x11,
        )
        val out = FloatArray(6) { 9f }
        val n = Pcm.int16ToFloat(bytes, 0, bytes.size, out, 1)
        assertEquals(4, n)
        assertEquals(9f, out[0], "nothing before outOffset is touched")
        assertEquals(1f, out[1], 0f)
        assertEquals(-1f, out[2], 0f, "the most negative code clamps to -1")
        assertEquals(0f, out[3], 0f)
        assertEquals(0x4000 / 32_767f, out[4], 1e-7f)
        assertEquals(9f, out[5], "nothing past the written samples is touched")
    }

    @Test
    fun `float reads little-endian and scrubs non-finite samples`() {
        fun le(x: Float): ByteArray {
            val b = x.toRawBits()
            return byteArrayOf(b.toByte(), (b shr 8).toByte(), (b shr 16).toByte(), (b shr 24).toByte())
        }
        val bytes = le(0.5f) + le(-0.25f) + le(Float.NaN) + le(Float.POSITIVE_INFINITY) + le(2f)
        val out = FloatArray(5)
        assertEquals(5, Pcm.floatToFloat(bytes, 0, bytes.size, out, 0))
        assertEquals(0.5f, out[0], 0f)
        assertEquals(-0.25f, out[1], 0f)
        assertEquals(0f, out[2], 0f, "NaN becomes silence")
        assertEquals(0f, out[3], 0f, "Inf becomes silence")
        assertEquals(1f, out[4], 0f, "over-full-scale clamps")
    }

    @Test
    fun `windows outside the arrays are refused`() {
        val bytes = ByteArray(8)
        assertFailsWith<IllegalArgumentException> { Pcm.int16ToFloat(bytes, 4, 8, FloatArray(8), 0) }
        assertFailsWith<IllegalArgumentException> { Pcm.int16ToFloat(bytes, 0, 8, FloatArray(2), 0) }
        assertFailsWith<IllegalArgumentException> { Pcm.floatToFloat(bytes, 0, 8, FloatArray(1), 0) }
        assertFailsWith<IllegalArgumentException> { Pcm.floatToFloat(bytes, -1, 4, FloatArray(4), 0) }
    }
}
