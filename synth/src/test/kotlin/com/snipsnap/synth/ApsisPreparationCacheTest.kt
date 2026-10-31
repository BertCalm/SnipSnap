package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.test.*

/** Preparation reuse and playing invariants; measured spectral activity is not a hearing verdict. */
class ApsisPreparationCacheTest {
    private val short = Apsis.ProbeOptions(seconds = .7f, recordStems = false, recordTraces = false)

    @Test fun `LRU bounds retained bytes and entries and never retains an oversized value`() {
        val cache = ApsisPreparationCache<String, String>(maxEntries = 2, maxBytes = 12, maxEntryBytes = 8)
        fun get(key: String, bytes: Long = 6) = cache.getOrPrepare(key, bytes, { false }) { key }
        assertFalse(get("a").hit); assertFalse(get("b").hit)
        assertTrue(get("a").hit)
        get("c")
        assertFalse(get("b").hit, "least recently used entry was not evicted")
        assertFalse(get("oversized", 13).retained)
        val stats = cache.snapshot()
        assertEquals(2, stats.entries); assertEquals(12L, stats.bytes)
    }

    @Test fun `cancelled and failed preparations cannot publish partial shared values`() {
        val cache = ApsisPreparationCache<String, String>()
        var cancelled = false
        assertFailsWith<CancellationException> {
            cache.getOrPrepare("cancelled", 10, { cancelled }) { cancelled = true; "partial" }
        }
        assertEquals(0, cache.snapshot().entries)
        assertFailsWith<IllegalStateException> {
            cache.getOrPrepare("failed", 10, { false }) { error("preparation failed") }
        }
        assertEquals(0, cache.snapshot().entries)
    }

    @Test fun `concurrent misses publish a complete deterministic value without blocking a hit`() {
        val cache = ApsisPreparationCache<String, String>()
        cache.getOrPrepare("ready", 8, { false }) { "ready-value" }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit<String> {
                cache.getOrPrepare("slow", 8, { false }) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "complete-value"
                }.value
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val hit = pool.submit<String> { cache.getOrPrepare("ready", 8, { false }) { error("hit rebuilt") }.value }
            assertEquals("ready-value", hit.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("complete-value", slow.get(5, TimeUnit.SECONDS))
            assertEquals("complete-value", cache.getOrPrepare("slow", 8, { false }) { error("missing completed value") }.value)
            val bothEntered = CountDownLatch(2)
            val completeTogether = CountDownLatch(1)
            val duplicateMisses = List(2) {
                pool.submit<String> {
                    cache.getOrPrepare("same-key", 8, { false }) {
                        bothEntered.countDown()
                        check(completeTogether.await(5, TimeUnit.SECONDS))
                        "same-complete-value"
                    }.value
                }
            }
            assertTrue(bothEntered.await(5, TimeUnit.SECONDS))
            completeTogether.countDown()
            assertEquals(listOf("same-complete-value", "same-complete-value"), duplicateMisses.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals(3, cache.snapshot().entries)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `finite geometry reuses preparation across notes velocities seeds and population changes`() {
        Apsis.clearPreparationCache()
        val first = Apsis.probe(midi = 60, velocity = .25f, config = ApsisConfig(3, 1), options = short)
        assertFalse(first.diagnostics.preparationCacheHit)
        val changed = Apsis.probe(macros = mapOf("BODIES" to .8f, "COUPLE" to .8f), midi = 72,
            velocity = 1f, config = ApsisConfig(3, 99), options = short)
        assertTrue(changed.diagnostics.preparationCacheHit)
        val fresh = Apsis.probe(macros = mapOf("BODIES" to .8f, "COUPLE" to .8f), midi = 72,
            velocity = 1f, config = ApsisConfig(3, 99), options = short.copy(usePreparationCache = false))
        assertContentEquals(changed.samples, fresh.samples)
        val repeated = Apsis.probe(midi = 60, velocity = .25f, config = ApsisConfig(3, 1), options = short)
        assertContentEquals(first.samples, repeated.samples)
    }

    @Test fun `motion timing and held pitch fitting separate cache keys`() {
        Apsis.clearPreparationCache()
        Apsis.probe(config = ApsisConfig(3), options = short)
        for (macro in listOf("STRETCH", "DISTANCE", "ALIGN")) {
            val p = Apsis.probe(macros = mapOf(macro to .9f), config = ApsisConfig(3), options = short)
            assertFalse(p.diagnostics.preparationCacheHit, "$macro reused another geometry")
        }
        val held = mapOf("HOLD" to 1f)
        val a = Apsis.probe(macros = held, midi = 60, config = ApsisConfig(3), options = short)
        val b = Apsis.probe(macros = held, midi = 61, config = ApsisConfig(3), options = short)
        assertFalse(a.diagnostics.preparationCacheHit)
        assertFalse(b.diagnostics.preparationCacheHit)
        assertNotEquals(a.diagnostics.periods[0], b.diagnostics.periods[0])
        assertTrue(Apsis.preparationCacheSnapshot().bytes <= 32L * 1024 * 1024)
    }

    @Test fun `production diagnostic bypass preserves exact audio`() {
        for (model in 1..3) {
            val measured = Apsis.probe(config = ApsisConfig(model), options = short)
            val fast = Apsis.probe(config = ApsisConfig(model), options = short.copy(recordDiagnostics = false))
            assertContentEquals(measured.samples, fast.samples, "model $model changed when diagnostics were disabled")
            assertTrue(measured.diagnostics.diagnosticsRecorded)
            assertFalse(fast.diagnostics.diagnosticsRecorded)
        }
    }

    @Test fun `oversized diagnostic control grids cache contacts without allocating an eager table`() {
        Apsis.clearPreparationCache()
        // This valid fine grid would otherwise eagerly allocate over 39 MiB for .7 seconds,
        // and the same accepted settings grow past a gigabyte on a long diagnostic.
        val fine = short.copy(controlStride = 1)
        val first = Apsis.probe(config = ApsisConfig(3), options = fine)
        val repeated = Apsis.probe(config = ApsisConfig(3), options = fine)
        val uncached = Apsis.probe(config = ApsisConfig(3), options = fine.copy(usePreparationCache = false))
        assertTrue(first.diagnostics.preparationEntryBytes < 1024 * 1024,
            "an oversized control table was prepared instead of retaining only its contacts")
        assertTrue(repeated.diagnostics.preparationCacheHit)
        assertContentEquals(first.samples, repeated.samples)
        assertContentEquals(first.samples, uncached.samples)
    }

    @Test fun `native velocity changes excitation color while preserving motion and ordered energy`() {
        val probes = listOf(.25f, .65f, 1f).map { velocity ->
            Apsis.probe(velocity = velocity, config = ApsisConfig(3),
                options = Apsis.ProbeOptions(recordStems = false, recordTraces = false))
        }
        val energy = probes.map { p -> p.samples.sumOf { it.toDouble() * it } }
        val color = probes.map { upperToRoot(it.samples, 60) }
        println("APSIS model3 velocities .25/.65/1 energy=$energy upper/root=$color")
        assertTrue(energy.zipWithNext().all { (a, b) -> b > a })
        assertTrue(color.zipWithNext().all { (a, b) -> b > a }, "harder excitation lost its upper color")
        val times = probes.map { p -> p.events.map { Triple(it.body, it.timeSeconds, it.durationSeconds) } }
        assertEquals(times[0], times[1]); assertEquals(times[1], times[2])
        assertContentEquals(probes[0].diagnostics.transientCounts, probes[2].diagnostics.transientCounts)
        val zero = Apsis.probe(velocity = 0f, config = ApsisConfig(3), options = short)
        assertTrue(zero.samples.all { it == 0f })
    }

    @Test fun `shared material port awakens a body only through the passive network`() {
        val options = Apsis.ProbeOptions(seconds = 3f, directExcitationDisabled = 1, soloBody = 1,
            recordStems = false, recordTraces = false)
        val connected = Apsis.probe(macros = mapOf("COUPLE" to 1f), config = ApsisConfig(3), options = options)
        val disconnected = Apsis.probe(macros = mapOf("COUPLE" to 0f), config = ApsisConfig(3), options = options)
        assertTrue(connected.samples.any { abs(it) > 1e-8f })
        assertTrue(disconnected.samples.all { it == 0f })
        assertTrue(connected.diagnostics.maxUnforcedEnergyRise <= 1e-10)
    }

    private fun upperToRoot(samples: FloatArray, midi: Int): Double {
        val n = 4096
        val wanted = Keys.midiHz(midi).toDouble()
        var root = 0.0; var upper = 0.0
        for (center in (Dsp.RATE * .8).toInt()..(Dsp.RATE * 3.2).toInt() step 1024) {
            val real = FloatArray(n) { i -> samples[center - n / 2 + i] * (.5 - .5 * cos(2 * PI * i / (n - 1))).toFloat() }
            val imaginary = FloatArray(n)
            Fft.forward(real, imaginary)
            for (bin in 1 until n / 2) {
                val hz = bin * Dsp.RATE.toDouble() / n
                val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                if (abs(hz - wanted) < 35) root += power
                if (hz > wanted * 1.5 && hz < 10_000) upper += power
            }
        }
        return upper / root.coerceAtLeast(1e-30)
    }
}
