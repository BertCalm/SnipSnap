package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausticFftTest {
    @Test
    fun `planned forward and inverse retain every house FFT bit for real and complex signals`() {
        for (power in 5..16) for (complex in listOf(false, true)) {
            val size = 1 shl power
            val noise = Dsp.Noise(Dsp.seedFor("CAUSTIC_FFT_TEST", size, complex))
            val real = FloatArray(size) { noise.next() }
            val imaginary = FloatArray(size) { if (complex) noise.next() else 0f }
            compare(real, imaginary, inverse = false, "$size complex=$complex forward")
            compare(real, imaginary, inverse = true, "$size complex=$complex inverse")
        }
    }

    @Test
    fun `impulses signed zeros and the single sample retain exact butterfly behavior`() {
        for (size in listOf(1, 32, 512, 8192, 65536)) {
            val real = FloatArray(size) { if (it == size - 1) .7f else if (it % 2 == 0) -0f else 0f }
            val imaginary = FloatArray(size) { if (it % 2 == 0) 0f else -0f }
            compare(real, imaginary, inverse = false, "$size impulse forward")
            compare(real, imaginary, inverse = true, "$size impulse inverse")
        }
        // The house API accepts the same array twice. Preserve its sequential
        // swaps/writes even though CAUSTIC's convolution uses separate arrays.
        val shared = FloatArray(64) { (it - 32) / 32f }
        for (inverse in listOf(false, true)) {
            val expected = shared.copyOf()
            val actual = shared.copyOf()
            if (inverse) { Fft.inverse(expected, expected); CausticFft.inverse(actual, actual) }
            else { Fft.forward(expected, expected); CausticFft.forward(actual, actual) }
            assertBits(expected, actual, "aliased arrays inverse=$inverse")
        }
    }

    @Test
    fun `immutable plans can be reused concurrently without sharing audio state`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 16).map { task -> Callable {
                val size = 1 shl (8 + task % 5)
                val noise = Dsp.Noise(Dsp.seedFor("CAUSTIC_FFT_THREADS", task))
                val re = FloatArray(size) { noise.next() }
                val im = FloatArray(size) { noise.next() }
                compare(re, im, inverse = task % 2 == 0, "concurrent $task")
            } }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `plan cache evicts by recency and stays within entry and byte limits`() {
        CausticFft.clearCache()
        for (power in 5..12) {
            val size = 1 shl power
            CausticFft.forward(FloatArray(size), FloatArray(size))
        }
        CausticFft.forward(FloatArray(32), FloatArray(32))
        CausticFft.forward(FloatArray(8192), FloatArray(8192))
        val lru = CausticFft.cacheStats()
        assertTrue(32 in lru.sizes, "a hit must keep the oldest plan alive")
        assertFalse(64 in lru.sizes, "the least recently used plan must be evicted")
        for (power in 13..20) {
            val size = 1 shl power
            val impulse = FloatArray(size).also { it[0] = 1f }
            CausticFft.forward(impulse, FloatArray(size))
            val stats = CausticFft.cacheStats()
            assertTrue(stats.sizes.size <= CausticFft.MAX_CACHE_ENTRIES)
            assertTrue(stats.bytes in 1..CausticFft.MAX_CACHE_BYTES)
        }
        val largest = CausticFft.cacheStats()
        assertTrue(CausticFft.MAX_SIZE in largest.sizes)
        assertFalse(CausticFft.MAX_SIZE / 4 in largest.sizes,
            "the byte budget must evict a large earlier plan even before the entry limit")
        CausticFft.clearCache()
        assertEquals(0L, CausticFft.cacheStats().bytes)
        assertTrue(CausticFft.cacheStats().sizes.isEmpty())
    }

    @Test
    fun `invalid lengths are rejected before either array is changed`() {
        for (inverse in listOf(false, true)) {
            for ((real, imaginary) in listOf(
                FloatArray(0) to FloatArray(0), FloatArray(3) { 1f } to FloatArray(3) { -1f },
                FloatArray(32) { 1f } to FloatArray(64) { -1f },
                FloatArray(CausticFft.MAX_SIZE * 2) to FloatArray(CausticFft.MAX_SIZE * 2),
            )) {
                val a = real.copyOf(); val b = imaginary.copyOf()
                assertFailsWith<IllegalArgumentException> {
                    if (inverse) CausticFft.inverse(real, imaginary) else CausticFft.forward(real, imaginary)
                }
                assertBits(a, real, "invalid real inverse=$inverse")
                assertBits(b, imaginary, "invalid imaginary inverse=$inverse")
            }
        }
    }

    private fun compare(real: FloatArray, imaginary: FloatArray, inverse: Boolean, label: String) {
        val expectedReal = real.copyOf(); val expectedImaginary = imaginary.copyOf()
        val actualReal = real.copyOf(); val actualImaginary = imaginary.copyOf()
        if (inverse) {
            Fft.inverse(expectedReal, expectedImaginary)
            CausticFft.inverse(actualReal, actualImaginary)
        } else {
            Fft.forward(expectedReal, expectedImaginary)
            CausticFft.forward(actualReal, actualImaginary)
        }
        assertBits(expectedReal, actualReal, "$label real")
        assertBits(expectedImaginary, actualImaginary, "$label imaginary")
    }

    private fun assertBits(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals(expected.size, actual.size, label)
        for (i in expected.indices) if (expected[i].toRawBits() != actual[i].toRawBits())
            assertEquals(expected[i].toRawBits(), actual[i].toRawBits(), "$label index $i")
    }
}
