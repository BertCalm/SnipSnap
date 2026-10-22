package com.snipsnap.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmPlaybackTest {
    @Test
    fun `stereo stream preserves both channel signals and original frame duration across uneven blocks`() {
        val rate = 44_100
        val frames = 4_410
        val samples = FloatArray(frames * 2) { sample ->
            val frame = sample / 2
            val frequency = if (sample % 2 == 0) 440.0 else 660.0
            sin(2.0 * PI * frequency * frame / rate).toFloat()
        }
        val playback = PcmPlayback(samples, channels = 2)
        val block = FloatArray(257 * 2)
        val heard = ArrayList<Float>(samples.size)
        var writtenFrames = 0
        while (true) {
            val count = playback.readFrames(block)
            if (count == 0) break
            writtenFrames += count
            heard.addAll(block.take(count * playback.channels))
        }
        assertEquals(frames, writtenFrames, "an LR pair advances one frame")
        assertEquals(.1, writtenFrames.toDouble() / rate, 1e-12, "100 ms stays 100 ms at the source clock")
        assertContentEquals(samples, heard.toFloatArray(), "no channel fold, swap or phase shift at a block boundary")
        assertEquals(frames, playback.positionFrame)
    }

    @Test
    fun `stereo seek and bounded read use frame positions`() {
        val source = stereoFrames(6)
        val playback = PcmPlayback(source, channels = 2, startFrame = 2)
        val block = FloatArray(8) { -999f }
        assertEquals(2, playback.readFrames(block, maxFrames = 2))
        assertContentEquals(source.copyOfRange(4, 8), block.copyOfRange(0, 4))
        assertContentEquals(FloatArray(4) { -999f }, block.copyOfRange(4, 8))
        assertEquals(4, playback.positionFrame)
        assertEquals(2, playback.readFrames(block))
        assertContentEquals(source.copyOfRange(8, 12), block.copyOfRange(0, 4))
        assertEquals(0, playback.readFrames(block))
    }

    @Test
    fun `stereo loop wraps complete LR frames at the requested out point`() {
        val source = stereoFrames(8)
        val playback = PcmPlayback(source, channels = 2, startFrame = 2, loop = 2..4)
        val block = FloatArray(8)
        repeat(3) {
            assertEquals(3, playback.readFrames(block))
            assertContentEquals(source.copyOfRange(4, 10), block.copyOfRange(0, 6))
            assertEquals(5, playback.positionFrame)
        }
    }

    @Test
    fun `mono default retains existing seek and loop behavior`() {
        val source = FloatArray(9) { it.toFloat() }
        val playback = PcmPlayback(source, startFrame = 2)
        val block = FloatArray(4)
        assertEquals(4, playback.readFrames(block))
        assertContentEquals(floatArrayOf(2f, 3f, 4f, 5f), block)
        assertEquals(3, playback.readFrames(block))
        assertContentEquals(floatArrayOf(6f, 7f, 8f), block.copyOf(3))
        assertEquals(0, playback.readFrames(block))

        val looped = PcmPlayback(source, startFrame = 1, loop = 1..3)
        repeat(2) {
            assertEquals(3, looped.readFrames(block))
            assertContentEquals(floatArrayOf(1f, 2f, 3f), block.copyOf(3))
        }
        assertEquals(0, PcmPlayback(source, startFrame = 99).readFrames(block))
    }

    @Test
    fun `partial frames are refused and a loop beyond the clip falls back to one shot`() {
        assertFailsWith<IllegalArgumentException> { PcmPlayback(FloatArray(3), channels = 2) }
        assertFailsWith<IllegalArgumentException> { PcmPlayback(FloatArray(6), channels = 3) }
        val playback = PcmPlayback(stereoFrames(3), channels = 2, loop = 5..8)
        assertFailsWith<IllegalArgumentException> { playback.readFrames(FloatArray(3)) }
        assertEquals(3, playback.readFrames(FloatArray(6)))
        assertEquals(0, playback.readFrames(FloatArray(6)))
    }

    private fun stereoFrames(count: Int) = FloatArray(count * 2) { sample ->
        if (sample % 2 == 0) sample / 2 + .1f else -(sample / 2) - .2f
    }
}
