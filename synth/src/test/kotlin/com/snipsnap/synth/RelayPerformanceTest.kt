package com.snipsnap.synth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Optimizing repeated previews must never make their audio depend on cache or thread history. */
class RelayPerformanceTest {
    @Test
    fun `wave tables retain recent entries and evict rather than grow without bound`() {
        val cache = Relay.WaveTableCache<String>(2)
        val loads = mutableMapOf<String, Int>()
        fun table(key: String) = cache.getOrBuild(key) {
            loads[key] = loads.getOrDefault(key, 0) + 1
            floatArrayOf(key.single().code.toFloat())
        }
        val a = table("A")
        val b = table("B")
        assertSame(a, table("A"), "a hit retains the complete immutable table")
        table("C")
        assertEquals(2, cache.size)
        assertSame(a, table("A"), "the least-recently used entry, not a recent hit, must leave")
        val rebuiltB = table("B")
        assertNotSame(b, rebuiltB, "an evicted table must be rebuilt")
        assertContentEquals(b, rebuiltB, "cache eviction cannot change generated values")
        assertEquals(mapOf("A" to 1, "B" to 2, "C" to 1), loads)
        assertEquals(2, cache.size)
    }

    @Test
    fun `simultaneous cache misses publish one complete table without holding the build lock`() {
        val cache = Relay.WaveTableCache<String>(2)
        val workers = Executors.newFixedThreadPool(4)
        val building = CountDownLatch(4)
        try {
            val tasks = List(4) {
                workers.submit<FloatArray> {
                    cache.getOrBuild("shared") {
                        building.countDown()
                        assertTrue(building.await(10, TimeUnit.SECONDS),
                            "table construction must not serialize unrelated render work under a monitor")
                        FloatArray(4097) { index -> index.toFloat() / 4096f }
                    }
                }
            }
            val published = tasks.map { it.get(20, TimeUnit.SECONDS) }
            published.forEach {
                assertSame(published.first(), it, "all callers use the winning complete table")
                assertEquals(0f, it.first())
                assertEquals(1f, it.last())
            }
            assertEquals(1, cache.size)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `cached spectral banks remain independent of the returned editable sample buffer`() {
        val patch = RelayPresets.forVoice(RelayVoice.KEY).first()
        val reference = patch.render().samples
        val editable = patch.render().samples
        assertNotSame(reference, editable)
        editable.fill(1f)
        assertContentEquals(reference, patch.render().samples,
            "editing a rendered pad cannot corrupt the shared spectral bank")
    }

    @Test
    fun `mixed concurrent previews preserve pitch color source and velocity dependencies`() {
        val base = RelayPresets.forVoice(RelayVoice.KEY).first()
        val patches = listOf(
            base,
            base.copy(macros = base.macros + ("TUNE" to .25f)),
            base.copy(macros = base.macros + ("COLOR" to .15f)),
            base.copy(velocity = .35f),
            base.copy(phrase = base.phrase.copy(sources = base.phrase.sources.map {
                if (it.role == RelayRole.PLUCK) it.copy(brightness = .2f) else it
            })),
            // The held root is fitted to output frames and therefore differs slightly
            // when its phrase clock changes; it cannot borrow the finite note's bank.
            base.copy(macros = base.macros + ("HOLD" to 1f)),
            base.copy(macros = base.macros + ("HOLD" to 1f),
                phrase = base.phrase.copy(tempoBpm = 131f)),
            RelayPresets.forVoice(RelayVoice.CHIME).first(),
        )
        val reference = patches.map { it.render().samples }
        for (i in reference.indices) for (j in 0 until i) {
            assertFalse(reference[i].contentEquals(reference[j]),
                "fixtures $i and $j must distinguish their pitch, color, source, velocity or held clock")
        }
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (patches.indices.reversed() + patches.indices).map { index ->
                workers.submit { assertContentEquals(reference[index], patches[index].render().samples,
                    "preview $index must not inherit another table's pitch, color, source or excitation") }
            }
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
    }
}
