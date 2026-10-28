package com.snipsnap.synth

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrismMotionSourcesTest {
    private val source get() = PrismMotionSources.all().first()
    private val frequencies get() = Prism.partialFrequencies(PrismVoice.KEY)
    private val coefficients get() = List(Prism.PARTIALS) { if (it == 0) 1f else .2f }

    @Test
    fun `the fixed catalog contains the eight named palette sources without a file import door`() {
        val catalog = PrismMotionSources.all()
        assertEquals(listOf("fork-soft-keys", "fork-mellow-reed", "tessera-wooden-arrival", "tessera-paired-wire",
            "embouchure-soft-entry", "embouchure-relaxed-bow", "fathom-hollow-growl", "fathom-driven-sub"), catalog.map { it.id })
        assertEquals(listOf("SOFT KEYS", "MELLOW REED", "Wooden Arrival", "Paired Wire", "Soft Entry", "Relaxed Bow",
            "HOLLOW GROWL", "DRIVEN SUB"), catalog.map { it.title })
        assertEquals(mapOf("electric-keys" to 2, "wood-strings" to 2, "breath-friction" to 2, "dark-saturated" to 2),
            catalog.groupingBy { it.category }.eachCount())
        for (entry in catalog) assertSame(entry, PrismMotionSources.byId(entry.id))
        assertNull(PrismMotionSources.byId(null))
        assertNull(PrismMotionSources.byId("missing-source"))
        assertFailsWith<UnsupportedOperationException> { (catalog as MutableList<PrismMotionSources.Source>).clear() }
        assertFailsWith<IllegalArgumentException> {
            PrismMotionSources.applySource(PrismOpeningPresets.all().first(), source.copy(id = "arbitrary-source"))
        }
    }

    @Test
    fun `source selection saves all coefficients while retaining edited playing and motion fields`() {
        val base = PrismOpeningPresets.all().first()
        val template = base.template.copy(attackSeconds = .063f, decaySeconds = 2.3f,
            ratioOverrides = List(Prism.PARTIALS) { if (it < 5) it + 1f else 2f * it },
            groupProfiles = List(Prism.GROUPS) { PrismGroupProfile(1f + it * .1f, .9f, it * .01f, it + 1, .3f) })
        val original = base.copy(name = "Edited source model", velocity = .27f, template = template,
            macros = base.macros + mapOf("TUNE" to .75f, "BODY" to .22f, "STRIKE" to .71f),
            motion = base.motion!!.copy(morph = .13f, evolve = .43f, seconds = 1.7f, groupLag = .32f, repeat = true))
        val selected = PrismMotionSources.applySource(original, source)
        val motion = assertNotNull(selected.motion)
        assertEquals(original, selected.copy(motion = original.motion), "only source A/B and its provenance change")
        assertEquals(original.motion, motion.copy(amplitudesA = original.motion!!.amplitudesA,
            amplitudesB = original.motion!!.amplitudesB, sourceId = original.motion!!.sourceId))
        assertEquals(source.id, motion.sourceId)
        assertTrue(Prism.partialFrequencies(original.voice, original.macros, original.template).last() > Prism.USABLE_BANDWIDTH_HZ)
        assertEquals(Prism.PARTIALS, motion.amplitudesB.size, "retain entries above this note's output bandwidth too")
        assertEquals(PrismOpeningPresets.bodyAmplitudes(motion.amplitudesB), motion.amplitudesA)
        assertSame(source.patch, PrismMotionSources.originalSource(selected))
        assertNull(PrismMotionSources.originalSource(base), "old accepted recipes retain null source provenance")
        assertNull(PrismMotionSources.originalSource(selected.copy(motion = motion.copy(sourceId = "missing-source"))))
        val reopened = PrismPatch.fromJsonText(selected.toJsonText())
        assertEquals(selected, reopened)
        assertContentEquals(selected.render().samples, reopened.render().samples,
            "recipe replay uses its saved spectra, voice, template and energy without calling a source")
    }

    @Test
    fun `endpoint cache keys the entire source recipe and all carrier frequencies`() {
        val calls = AtomicInteger()
        val cache = PrismMotionSources.EndpointCache(8) { _, _, _ -> calls.incrementAndGet(); coefficients }
        val first = cache.endpoint(source.patch, frequencies)
        assertSame(first, cache.endpoint(Patches.fromJsonText(source.patch.toJsonText()), frequencies.toList()))
        assertEquals(1, calls.get(), "equal complete source JSON is a cache hit even after decoding")
        cache.endpoint(source.patch.withMacros(source.patch.macros + ("STRIKE" to .123f)), frequencies)
        assertEquals(2, calls.get(), "an edited source recipe must be analyzed independently")
        cache.endpoint(source.patch, frequencies.map { it * 2f })
        assertEquals(3, calls.get(), "another carrier pitch needs a different fixed-Hz envelope")
        assertFailsWith<UnsupportedOperationException> { (first as MutableList<Float>)[0] = .5f }
    }

    @Test
    fun `endpoint cache evicts the least recently used entry at its fixed capacity`() {
        val calls = AtomicInteger()
        val cache = PrismMotionSources.EndpointCache(2) { _, _, _ -> calls.incrementAndGet(); coefficients }
        val a = frequencies
        val b = a.map { it * 2f }
        val c = a.map { it * 3f }
        cache.endpoint(source.patch, a)
        cache.endpoint(source.patch, b)
        cache.endpoint(source.patch, a)
        cache.endpoint(source.patch, c)
        assertEquals(3, calls.get())
        cache.endpoint(source.patch, a)
        assertEquals(3, calls.get(), "the accessed A entry stays resident")
        cache.endpoint(source.patch, b)
        assertEquals(4, calls.get(), "B was evicted rather than retaining every visited note")
    }

    @Test
    fun `concurrent selections compute one endpoint and share the completed immutable value`() {
        val calls = AtomicInteger()
        val workersReady = CountDownLatch(4)
        val go = CountDownLatch(1)
        val computationStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val cache = PrismMotionSources.EndpointCache(2) { _, _, _ ->
            calls.incrementAndGet(); computationStarted.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            coefficients
        }
        try {
            val tasks = List(4) { executor.submit<List<Float>> {
                workersReady.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS))
                cache.endpoint(source.patch, frequencies)
            } }
            assertTrue(workersReady.await(5, TimeUnit.SECONDS)); go.countDown()
            assertTrue(computationStarted.await(5, TimeUnit.SECONDS)); release.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            results.forEach { assertSame(results.first(), it) }
        } finally {
            go.countDown(); release.countDown(); executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled preparation never publishes a cache entry or a changed recipe`() {
        val cancelled = AtomicBoolean(true)
        val calls = AtomicInteger()
        val cache = PrismMotionSources.EndpointCache(2) { _, _, _ ->
            calls.incrementAndGet(); cancelled.set(true); coefficients
        }
        assertFailsWith<CancellationException> { cache.endpoint(source.patch, frequencies) { cancelled.get() } }
        assertEquals(0, calls.get())
        cancelled.set(false)
        assertFailsWith<CancellationException> { cache.endpoint(source.patch, frequencies) { cancelled.get() } }
        assertEquals(1, calls.get())
        cancelled.set(false)
        assertFailsWith<CancellationException> { cache.endpoint(source.patch, frequencies) { cancelled.get() } }
        assertEquals(2, calls.get(), "a cancelled analysis must be recomputed, not reused")
        val original = PrismOpeningPresets.all().first()
        assertFailsWith<CancellationException> { PrismMotionSources.applySource(original, source) { true } }
        assertEquals(original, PrismOpeningPresets.all().first())
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { cache.endpoint(source.patch, frequencies) }
        } finally {
            Thread.interrupted()
        }
    }
}
