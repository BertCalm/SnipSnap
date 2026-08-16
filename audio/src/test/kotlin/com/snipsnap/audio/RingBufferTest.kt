package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RingBufferTest {

    private fun mono(capacityFrames: Int) = RingBuffer(capacityFrames, channels = 1, sampleRate = 44_100)

    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    @Test
    fun `starts empty`() {
        val rb = mono(10)
        assertEquals(0, rb.availableFrames)
        assertEquals(0, rb.snapshot().size)
    }

    @Test
    fun `returns what fits before wrapping`() {
        val rb = mono(10)
        rb.write(ramp(0, 4))

        assertEquals(4, rb.availableFrames)
        assertContentEquals(floatArrayOf(0f, 1f, 2f, 3f), rb.snapshot())
    }

    @Test
    fun `keeps the most recent frames once full`() {
        val rb = mono(5)
        rb.write(ramp(0, 8)) // 0..7, only the last 5 survive

        assertEquals(5, rb.availableFrames)
        assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f), rb.snapshot())
    }

    @Test
    fun `stays in order across a wrap`() {
        // The wrap is where a ring buffer goes wrong: written in two pieces,
        // it still has to read back as one chronological run.
        val rb = mono(5)
        rb.write(ramp(0, 3)) // 0,1,2
        rb.write(ramp(3, 4)) // 3,4,5,6 — wraps

        assertContentEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f), rb.snapshot())
    }

    @Test
    fun `survives many small wrapping writes`() {
        val rb = mono(7)
        for (i in 0 until 50) rb.write(floatArrayOf(i.toFloat()))

        assertEquals(7, rb.availableFrames)
        assertContentEquals(floatArrayOf(43f, 44f, 45f, 46f, 47f, 48f, 49f), rb.snapshot())
    }

    @Test
    fun `a write larger than capacity keeps only its tail`() {
        val rb = mono(4)
        rb.write(ramp(0, 100))

        assertEquals(4, rb.availableFrames)
        assertContentEquals(floatArrayOf(96f, 97f, 98f, 99f), rb.snapshot())
    }

    @Test
    fun `writes correctly after an oversized write`() {
        // The oversized path resets the write head; the next ordinary write has
        // to pick up from there rather than from a stale position.
        val rb = mono(4)
        rb.write(ramp(0, 100)) // leaves 96..99
        rb.write(ramp(100, 2)) // 100,101

        assertContentEquals(floatArrayOf(98f, 99f, 100f, 101f), rb.snapshot())
    }

    @Test
    fun `snapshot can ask for less than is held`() {
        val rb = mono(10)
        rb.write(ramp(0, 10))

        assertContentEquals(floatArrayOf(7f, 8f, 9f), rb.snapshot(3))
    }

    @Test
    fun `snapshot asking for more than is held returns what exists`() {
        val rb = mono(10)
        rb.write(ramp(0, 3))

        assertContentEquals(floatArrayOf(0f, 1f, 2f), rb.snapshot(100))
    }

    @Test
    fun `interleaves stereo frames correctly across a wrap`() {
        val rb = RingBuffer(capacityFrames = 3, channels = 2, sampleRate = 44_100)
        // frames: (1,-1) (2,-2) (3,-3) (4,-4)
        rb.write(floatArrayOf(1f, -1f, 2f, -2f))
        rb.write(floatArrayOf(3f, -3f, 4f, -4f))

        assertEquals(3, rb.availableFrames)
        assertContentEquals(floatArrayOf(2f, -2f, 3f, -3f, 4f, -4f), rb.snapshot())
    }

    @Test
    fun `honours a partial count`() {
        val rb = mono(10)
        val scratch = ramp(0, 10)
        rb.write(scratch, count = 4) // as AudioRecord.read would report

        assertContentEquals(floatArrayOf(0f, 1f, 2f, 3f), rb.snapshot())
    }

    @Test
    fun `rejects a partial frame`() {
        val rb = RingBuffer(capacityFrames = 10, channels = 2, sampleRate = 44_100)
        assertFailsWith<IllegalArgumentException> { rb.write(FloatArray(3)) }
    }

    @Test
    fun `clear drops everything`() {
        val rb = mono(5)
        rb.write(ramp(0, 5))
        rb.clear()

        assertEquals(0, rb.availableFrames)
        assertEquals(0, rb.snapshot().size)
    }

    @Test
    fun `sizes itself in seconds`() {
        val rb = RingBuffer.ofSeconds(60f)
        assertEquals(60f * 44_100, rb.capacityFrames.toFloat())
        assertEquals(2, rb.channels)
        assertEquals(60f, rb.capacitySeconds)
    }

    @Test
    fun `reports how much audio it is holding`() {
        val rb = RingBuffer.ofSeconds(2f, channels = 1, sampleRate = 1000)
        rb.write(FloatArray(500))

        assertEquals(0.5f, rb.availableSeconds)
        assertEquals(250, rb.snapshotSeconds(0.25f).size)
    }

    @Test
    fun `a sixty second stereo buffer is a sane size`() {
        val rb = RingBuffer.ofSeconds(60f)
        val megabytes = rb.capacityFrames.toLong() * rb.channels * 4 / (1024 * 1024)
        assertTrue(megabytes in 15..25, "expected ~21 MB, got $megabytes MB")
    }

    @Test
    fun `rejects nonsense construction`() {
        assertFailsWith<IllegalArgumentException> { RingBuffer(0, 2, 44_100) }
        assertFailsWith<IllegalArgumentException> { RingBuffer(10, 3, 44_100) }
        assertFailsWith<IllegalArgumentException> { RingBuffer(10, 2, 0) }
        assertFailsWith<IllegalArgumentException> { RingBuffer.ofSeconds(0f) }
    }

    @Test
    fun `concurrent writes and snapshots stay consistent`() {
        // Not a proof of thread safety, but it catches a snapshot that reads a
        // torn write — which is the failure this lock exists to prevent.
        val rb = RingBuffer.ofSeconds(1f, channels = 1, sampleRate = 44_100)
        val writer = Thread {
            repeat(2_000) { rb.write(FloatArray(64) { 1f }) }
        }
        writer.start()

        repeat(500) {
            val snap = rb.snapshot(1024)
            assertTrue(snap.all { it == 0f || it == 1f }, "snapshot contained a torn value")
        }
        writer.join()
    }
}
