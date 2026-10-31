package com.snipsnap.synth

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NacreCoreCacheTest {
    @Test
    fun `acoustic edits reuse the source while actual source inputs invalidate it`() {
        Nacre.clearCoreCache()
        Nacre.inspect()
        assertEquals(1L, Nacre.coreCacheStats().builds)
        for (macro in listOf("VENUE", "CURRENT", "BLOOM")) Nacre.inspect(macros = mapOf(macro to 1f))
        assertEquals(1L, Nacre.coreCacheStats().builds, "return-only edits rebuilt the original harp")
        Nacre.inspect(macros = mapOf("TOUCH" to .4f))
        Nacre.inspect(macros = mapOf("DUET" to .8f))
        Nacre.inspect(velocity = .3f)
        Nacre.inspect(seed = 8L)
        val stats = Nacre.coreCacheStats()
        assertEquals(5L, stats.builds, "an actual core input was omitted from the cache key")
        assertEquals(4, stats.entries)
        assertEquals(4, stats.capacity)
        assertTrue(stats.bytes <= 4L * 6 * 6 * 44_100 * 4, "cache retained more than four six-array cores")
        assertEquals(0.0, Nacre.inspect().diagnostics.getValue("coreCacheHit"), "the LRU never evicted the oldest core")
    }

    @Test
    fun `mutating a returned probe cannot poison future warm renders`() {
        Nacre.clearCoreCache()
        val p = Nacre.inspect()
        val raw = p.raw.copyOf(); val native = p.snip.samples.copyOf()
        val expectedBass = p.stems.getValue("bass").copyOf()
        val expectedEvents = p.events.toList()
        p.raw.fill(Float.NaN); p.snip.samples.fill(Float.NaN)
        p.stems.values.forEach { it.fill(Float.NaN) }
        (p.events as MutableList<*>).clear()
        (p.diagnostics as MutableMap<String, Double>)["modelVersion"] = -1.0
        val again = Nacre.inspect()
        assertEquals(1.0, again.diagnostics.getValue("coreCacheHit"))
        assertContentEquals(raw, again.raw)
        assertContentEquals(native, again.snip.samples)
        assertContentEquals(expectedBass, again.stems.getValue("bass"))
        assertEquals(expectedEvents, again.events)
        assertEquals(3.0, again.diagnostics.getValue("modelVersion"))
    }

    @Test
    fun `warm hits snapshots and clear remain available during an unrelated cold build`() {
        val cache = NacreCoreCache<String, Int>(4) { 4L }
        cache.getOrBuild("warm") { 17 }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val cold = executor.submit<Pair<Int, Boolean>> {
                cache.getOrBuild("cold") {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    23
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val warm = executor.submit<Pair<Int, Boolean>> { cache.getOrBuild("warm") { error("rebuilt warm value") } }
            assertEquals(17 to true, warm.get(2, TimeUnit.SECONDS), "warm cache hit waited for unrelated synthesis")
            assertEquals(1, executor.submit<NacreCoreCache.Snapshot> { cache.snapshot() }.get(2, TimeUnit.SECONDS).entries)
            executor.submit { cache.clear() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(23 to false, cold.get(2, TimeUnit.SECONDS))
            assertEquals(0, cache.snapshot().entries, "a cleared in-flight build repopulated the cache")
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `a request waiting behind a cold build can be cancelled before that build finishes`() {
        val cache = NacreCoreCache<String, Int>(4) { 4L }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coldError = AtomicReference<Throwable?>()
        val cold = Thread {
            try { cache.getOrBuild("cold") { started.countDown(); release.await(); 1 } }
            catch (t: Throwable) { coldError.set(t) }
        }
        val waitingError = AtomicReference<Throwable?>()
        val waiter = Thread {
            try { cache.getOrBuild("waiting") { 2 } }
            catch (t: Throwable) { waitingError.set(t) }
        }
        try {
            cold.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (waiter.isAlive && waiter.state !in setOf(Thread.State.WAITING, Thread.State.BLOCKED, Thread.State.TIMED_WAITING) &&
                System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(waiter.isAlive, "waiting request unexpectedly bypassed the bounded builder")
            waiter.interrupt()
            waiter.join(2000)
            assertTrue(!waiter.isAlive, "waiting cancellation remained trapped behind the cold build")
            assertTrue(waitingError.get() is CancellationException, "wrong cancellation: ${waitingError.get()}")
            release.countDown(); cold.join(2000)
            assertEquals(null, coldError.get())
            assertEquals(1, cache.snapshot().entries)
        } finally {
            release.countDown(); waiter.interrupt(); cold.interrupt()
            waiter.join(2000); cold.join(2000)
        }
    }

    @Test
    fun `interrupted completed builders cannot publish their buffers`() {
        val cache = NacreCoreCache<String, Int>(4) { 4L }
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { cache.getOrBuild("cancelled") { Thread.currentThread().interrupt(); 9 } }
            catch (t: Throwable) { failure.set(t) }
        }
        thread.start(); thread.join(2000)
        assertTrue(!thread.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertEquals(0, cache.snapshot().entries)
        assertEquals(0L, cache.snapshot().builds)
    }
}
