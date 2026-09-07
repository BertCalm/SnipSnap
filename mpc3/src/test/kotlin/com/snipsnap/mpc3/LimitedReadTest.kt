package com.snipsnap.mpc3

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LimitedReadTest {

    @Test
    fun `an honest stream reads through`() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val read = LimitedRead.bytes(data.inputStream(), limit = 4096)
        assertTrue(read.contentEquals(data))
    }

    @Test
    fun `a stream past the limit is refused before it is fully read`() {
        val big = ByteArray(10_000)
        assertFailsWith<LimitedRead.TooLargeException> {
            LimitedRead.bytes(big.inputStream(), limit = 4096, what = "test")
        }
    }

    @Test
    fun `a gzip bomb is refused before it inflates, wrapped as bad input`() {
        // Tiny on disk, 16 MB inflated - the classic shape. Read with a 1 MB
        // ceiling so the refusal is fast and never allocates the payload.
        val bomb = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz ->
                val zeros = ByteArray(1024 * 1024)
                repeat(16) { gz.write(zeros) }
            }
        }.toByteArray()
        assertTrue(bomb.size < 100_000, "the bomb is small on disk: ${bomb.size}")

        val start = System.nanoTime()
        val err = assertFailsWith<AcvsException> { Acvs.read(bomb, inflateLimit = 1024 * 1024) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("decompression bomb" in err.message!!, err.message!!)
        assertTrue(ms < 2_000, "refused fast, not after inflating it all: ${ms}ms")
    }

    @Test
    fun `TooLargeException is a bad-input exception`() {
        // So the CLI's existing IllegalArgumentException handling refuses it.
        assertTrue(IllegalArgumentException::class.java.isAssignableFrom(LimitedRead.TooLargeException::class.java))
    }
}
