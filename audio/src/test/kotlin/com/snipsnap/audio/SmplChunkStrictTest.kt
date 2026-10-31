package com.snipsnap.audio

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmplChunkStrictTest {
    private val root = SmplChunk(64)
    private val looped = SmplChunk(64, SmplChunk.Loop(10, 300))
    private val smplHeader = 36
    private val smplBody = smplHeader + 8

    private fun wav(sheet: SmplChunk?, frames: Int = 300, depth: WavWriter.BitDepth = WavWriter.BitDepth.PCM_24): ByteArray {
        val source = Snip(FloatArray(frames) { (.2 * sin(2 * Math.PI * 220 * it / 44100)).toFloat() }, 1, 44100)
        return ByteArrayOutputStream().also { WavWriter.write(it, source, depth, sheet) }.toByteArray()
    }
    private fun u32(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { value, shift -> value or ((bytes[at + shift].toLong() and 255L) shl (shift * 8)) }
    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0..3) bytes[at + i] = (value ushr (i * 8)).toByte()
    }
    private fun resized(bytes: ByteArray): ByteArray = bytes.also { put32(it, 4, it.size - 8L) }
    private fun changed(original: ByteArray, at: Int, value: Long) = original.copyOf().also { put32(it, at, value) }
    private fun refuses(bytes: ByteArray) {
        assertFailsWith<IllegalArgumentException> { WavReader.readSmplStrict(bytes) }
    }

    @Test fun `valid absent root-only and forward-loop sheets retain audio and both overloads agree`() {
        for (depth in WavWriter.BitDepth.entries) for (sheet in listOf(null, root, looped)) {
            val bytes = wav(sheet, depth = depth)
            val before = bytes.copyOf()
            assertEquals(sheet, WavReader.readSmplStrict(bytes))
            assertEquals(sheet, WavReader.readSmpl(bytes))
            assertContentEquals(before, bytes)
            val file = File.createTempFile("smpl-strict", ".wav")
            try {
                file.writeBytes(bytes)
                assertEquals(sheet, WavReader.readSmplStrict(file))
                assertEquals(sheet, WavReader.readSmpl(file))
            } finally { file.delete() }
        }
    }

    @Test fun `strict rejects declared loop bodies absent from a root-only chunk while tolerant reading stays compatible`() {
        val bytes = changed(wav(root), smplBody + 28, 1)
        assertEquals(root, WavReader.readSmpl(bytes), "the old reader's root-only recovery is retained")
        val failure = assertFailsWith<IllegalArgumentException>("declared-loop metadata cannot become an editable finite source") {
            WavReader.readSmplStrict(bytes)
        }
        assertTrue(failure.message.orEmpty().contains("loop bodies"), "the strict refusal must name the missing declared loop body")
        val unclaimedBody = changed(wav(looped), smplBody + 28, 0)
        assertEquals(root, WavReader.readSmpl(unclaimedBody))
        refuses(unclaimedBody)
    }

    @Test fun `strict rejects multiple unsigned loop counts and unsupported directions`() {
        val original = wav(looped)
        for (count in listOf(2L, 0xFFFFFFFFL)) refuses(changed(original, smplBody + 28, count))
        for (type in listOf(1L, 2L, 0xFFFFFFFFL)) {
            val bytes = changed(original, smplBody + 40, type)
            assertEquals(looped, WavReader.readSmpl(bytes), "the tolerant reader's legacy direction handling is unchanged")
            refuses(bytes)
        }
    }

    @Test fun `strict refuses reversed out-of-audio and unsigned overflowing loop ranges`() {
        val original = wav(looped)
        val reversed = changed(changed(original, smplBody + 44, 20), smplBody + 48, 19)
        assertEquals(root, WavReader.readSmpl(reversed))
        refuses(reversed)
        for (end in listOf(300L, 0xFFFFFFFFL)) {
            val bytes = changed(original, smplBody + 48, end)
            assertNull(WavReader.readSmpl(bytes))
            refuses(bytes)
        }
    }

    @Test fun `strict refuses duplicate sampler chunks instead of choosing one of them`() {
        val original = wav(root)
        val chunk = original.copyOfRange(smplHeader, smplBody + SmplChunk.HEADER_BYTES)
        val doubled = resized(original + chunk)
        assertEquals(root, WavReader.readSmpl(doubled))
        refuses(doubled)
    }

    @Test fun `strict checks truncated headers chunk bodies and padding without changing tolerant recovery`() {
        val original = wav(root)
        val shortHeader = resized(original.copyOf(smplBody + 20))
        assertNull(WavReader.readSmpl(shortHeader))
        refuses(shortHeader)
        val partialChunkHeader = resized(original + byteArrayOf(1, 2, 3))
        assertEquals(root, WavReader.readSmpl(partialChunkHeader))
        refuses(partialChunkHeader)
        val odd = wav(root, frames = 301)
        val missingPad = resized(odd.copyOf(odd.size - 1))
        assertEquals(root, WavReader.readSmpl(missingPad))
        refuses(missingPad)
        val dataHeader = smplBody + SmplChunk.HEADER_BYTES
        val truncatedData = changed(original, dataHeader + 4, u32(original, dataHeader + 4) + 3)
        assertEquals(root, WavReader.readSmpl(truncatedData))
        refuses(truncatedData)
    }

    @Test fun `strict checks root notes RIFF length and sampler-specific data declarations`() {
        val original = wav(root)
        val invalidRoot = changed(original, smplBody + 12, 128)
        assertNull(WavReader.readSmpl(invalidRoot))
        refuses(invalidRoot)
        val wrongRiffSize = changed(original, 4, 0)
        assertEquals(root, WavReader.readSmpl(wrongRiffSize))
        refuses(wrongRiffSize)
        val missingSamplerData = changed(original, smplBody + 32, 2)
        assertEquals(root, WavReader.readSmpl(missingSamplerData))
        refuses(missingSamplerData)
        refuses(ByteArray(3))
        assertNull(WavReader.readSmpl(ByteArray(3)))
    }

    @Test fun `properly declared opaque sampler data is supported without inventing a loop`() {
        val original = wav(root)
        val end = smplBody + SmplChunk.HEADER_BYTES
        val extended = resized(original.copyOfRange(0, end) + byteArrayOf(8, 9) + original.copyOfRange(end, original.size))
        put32(extended, smplHeader + 4, SmplChunk.HEADER_BYTES + 2L)
        put32(extended, smplBody + 32, 2)
        assertEquals(root, WavReader.readSmplStrict(extended))
        assertContentEquals(WavReader.read(original).samples, WavReader.read(extended).samples)
    }
}
