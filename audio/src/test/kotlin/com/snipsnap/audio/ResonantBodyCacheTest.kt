package com.snipsnap.audio

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class ResonantBodyCacheTest {
    private val source = Snip(FloatArray(128).also { it[0] = 0.8f }, 1, 44_100)
    private val settings = BodySettings(body = 0.5f)
    private fun key() = BodyCoefficientKey(BodyModels.identity(settings), 1, 44_100,
        settings.referenceHz, settings.rootT60, settings.damp.toDouble())

    @Test fun `every coefficient input including table identity and version invalidates the key`() {
        val cache = BodyCoefficientCache(true)
        val base = key()
        val changedModes = base.table.modes.mapIndexed { i, mode -> if (i == 1) mode.copy(ratio = 2.1) else mode }
        val variants = listOf(base, base.copy(rate = 48_000), base.copy(referenceHz = 220.0),
            base.copy(rootT60 = 0.3), base.copy(damping = 0.2), base.copy(algorithmVersion = 2),
            base.copy(table = base.table.copy(version = 2)), base.copy(table = base.table.copy(modes = changedModes)),
            base.copy(table = base.table.copy(modes = base.table.modes.dropLast(1))),
            base.copy(table = BodyModels.identity(settings.copy(from = BodyFamily.BAR, to = BodyFamily.HARMONIC))))
        for (variant in variants) {
            assertFalse(cache.get(variant).second, "$variant must miss")
            assertTrue(cache.get(variant).second)
            assertTrue(cache.size() <= 8)
        }
        assertEquals(8, cache.size())
        assertFalse(cache.get(base).second, "the least-recently used base entry was evicted")
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test fun `cache hit cold disabled eviction and unrelated sources give identical PCM`() {
        val processor = ResonantBody()
        val cold = processor.process(source, settings)
        assertFalse(cold.diagnostics.coefficientCacheHit)
        val warm = processor.process(source, settings)
        assertTrue(warm.diagnostics.coefficientCacheHit)
        assertContentEquals(cold.audio.samples, warm.audio.samples)
        assertContentEquals(cold.audio.samples, ResonantBody(false).process(source, settings).audio.samples)
        assertContentEquals(cold.audio.samples, processor.process(source, settings, captureWet = true).audio.samples,
            "capturing analysis audio must not change delivered PCM")
        for (i in 1..12) processor.process(source, settings.copy(tune = i / 36f))
        assertContentEquals(cold.audio.samples, processor.process(source, settings).audio.samples)
        processor.process(source.copy(samples = FloatArray(source.samples.size) { 0.5f }), settings)
        assertContentEquals(cold.audio.samples, processor.process(source, settings).audio.samples)
    }

    @Test fun `only output blend source and channel count change without rebuilding coefficients`() {
        val processor = ResonantBody()
        processor.process(source, settings)
        for (s in listOf(settings.copy(body = 0f), settings.copy(mix = 0.8f), settings.copy(body = 1f))) {
            assertTrue(processor.process(source, s).diagnostics.coefficientCacheHit)
        }
        assertTrue(processor.process(Snip(FloatArray(256), 2, 44_100), settings).diagnostics.coefficientCacheHit)
        processor.clearCoefficientCache()
        assertFalse(processor.process(source, settings).diagnostics.coefficientCacheHit)
    }

    @Test fun `concurrent renders cannot share a ringing state`() {
        val processor = ResonantBody()
        val expected = processor.process(source, settings).audio.samples
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..16).map { Callable {
                processor.process(source, settings).audio.samples
            } })
            results.forEach { assertContentEquals(expected, it.get()) }
        } finally { executor.shutdownNow() }
    }
}
