package com.snipsnap.synth

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Production optimizations must preserve the approved sound, including old held contracts. */
class FilamentRenderTest {
    @Test
    fun `production PCM retains frozen finite and held samples for every saved model and voice`() {
        val rows = javaClass.getResourceAsStream("/filament-approved-float32.tsv")!!
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(18, rows.size)
        val rosters = mapOf(1 to FilamentPresets.original(), 2 to FilamentPresets.revision2(), 3 to FilamentPresets.all())
        for (row in rows) {
            val (model, voice, name, frames, digest) = row.split('\t')
            val patch = rosters.getValue(model.toInt()).single { it.voiceName == voice && it.name == name }
            val samples = patch.render().samples
            assertEquals(frames.toInt(), samples.size, "$model/$voice/$name frame count")
            assertEquals(digest, floatHash(samples), "$model/$voice/$name approved PCM changed")
        }
    }

    @Test
    fun `diagnostic and ordinary paths use the same source routing DC and held convergence`() {
        for (roster in listOf(FilamentPresets.original(), FilamentPresets.revision2(), FilamentPresets.all())) {
            for (patch in listOf(roster.first(), roster.first { Filament.isLoop(it.macros.getValue("HOLD")) })) {
                val probe = Filament.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, model = patch.model)
                assertContentEquals(probe.samples, patch.render().samples, "${patch.name} model${patch.model}")
                assertTrue(probe.source.isNotEmpty() && probe.through.isNotEmpty() && probe.spill.isNotEmpty())
                if (probe.diagnostics.loop) assertTrue(probe.diagnostics.loopConverged)
            }
        }
    }

    @Test
    fun `callback cancellation stops before allocation and during propagation`() {
        val patch = FilamentPresets.all().first()
        assertFailsWith<CancellationException> {
            Filament.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, patch.model, cancelled = { true })
        }
        val checks = AtomicInteger()
        assertFailsWith<CancellationException> {
            Filament.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, patch.model,
                cancelled = { checks.incrementAndGet() > 360 })
        }
        assertEquals(361, checks.get(), "source and propagation must poll cancellation without finishing obsolete audio")
    }

    @Test
    fun `interrupting a running render stops its worker and preserves the interrupt flag`() {
        val patch = FilamentPresets.all().first { it.name == "Held Procession" }
        val enteredPropagation = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val checks = AtomicInteger()
        val outcome = AtomicReference<Throwable?>(null)
        val retainedInterrupt = AtomicReference(false)
        val worker = thread(name = "filament-cancellation-test", isDaemon = true) {
            try {
                Filament.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, patch.model,
                    cancelled = {
                        if (checks.incrementAndGet() == 500) enteredPropagation.countDown()
                        false
                    })
                outcome.set(AssertionError("obsolete render ran to completion"))
            } catch (e: Throwable) {
                outcome.set(e)
                retainedInterrupt.set(Thread.currentThread().isInterrupted)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(enteredPropagation.await(10, TimeUnit.SECONDS), "render did not reach its propagation loop")
            worker.interrupt()
            assertTrue(finished.await(5, TimeUnit.SECONDS), "interrupted synthesis did not release its worker")
            assertTrue(outcome.get() is CancellationException, "unexpected outcome ${outcome.get()}")
            assertTrue(retainedInterrupt.get(), "the renderer must not consume its caller's interrupt flag")
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    private fun floatHash(samples: FloatArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (sample in samples) {
            val value = sample.toRawBits()
            for (byte in bytes.indices) bytes[byte] = (value ushr (byte * 8)).toByte()
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
