package com.snipsnap.synth

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class LibrationRenderSessionTest {
    private fun note(voice: LibrationVoice = LibrationVoice.GATHER) = LibrationPatch(
        "Cache", voice, Libration.defaults(voice) + mapOf("SWARM" to .2f, "RESONANCE" to .1f),
        sourceSeconds = .24,
    )

    @Test fun `cold cached and renamed previews reproduce the public renderer without exposing cache samples`() {
        for (voice in LibrationVoice.entries) {
            val patch = note(voice)
            val session = LibrationRenderSession()
            val first = session.renderTimed(patch)
            assertFalse(first.timing.audioCacheHit)
            assertFalse(first.timing.populationCacheHit)
            val expected = patch.render().samples
            assertContentEquals(expected, first.snip.samples)
            first.snip.samples.fill(0f)
            val recall = session.renderTimed(patch.copy(name = "Renamed"))
            assertTrue(recall.timing.audioCacheHit)
            assertContentEquals(expected, recall.snip.samples)
            recall.snip.samples.fill(0f)
            assertContentEquals(expected, session.render(patch).samples)
        }
    }

    @Test fun `root touch and memory reuse physics while physical controls and length invalidate it`() {
        val session = LibrationRenderSession(maxAudioBytes = 0)
        val patch = note()
        session.render(patch)
        for (edit in listOf(patch.copy(midi = 43), patch.copy(velocity = .35f),
            patch.copy(macros = patch.macros + ("RESONANCE" to .2f)))) {
            val result = session.renderTimed(edit)
            assertTrue(result.timing.populationCacheHit)
            assertFalse(result.timing.audioCacheHit)
            assertContentEquals(edit.render().samples, result.snip.samples)
        }
        for (name in listOf("REGION", "WANDER", "NUDGE", "SWARM")) {
            val edit = patch.copy(macros = patch.macros + (name to .31f))
            assertFalse(session.renderTimed(edit).timing.populationCacheHit, name)
        }
        assertFalse(session.renderTimed(patch.copy(sourceSeconds = .3)).timing.populationCacheHit)
    }

    @Test fun `audio budget population eviction mutable input and clear cannot produce stale recalls`() {
        val controls = note().macros.toMutableMap()
        val patch = note().copy(macros = controls)
        val initial = patch.copy(macros = controls.toMap())
        val session = LibrationRenderSession(maxAudioBytes = 1, maxPopulations = 1)
        val first = session.render(patch)
        controls["WANDER"] = .9f
        assertFalse(session.renderTimed(patch).timing.populationCacheHit)
        val old = session.renderTimed(initial)
        assertFalse(old.timing.audioCacheHit)
        assertFalse(old.timing.populationCacheHit)
        assertContentEquals(first.samples, old.snip.samples)
        assertTrue(session.renderTimed(initial).timing.populationCacheHit)
        session.clear()
        assertFalse(session.renderTimed(initial).timing.populationCacheHit)
        assertFailsWith<IllegalArgumentException> { LibrationRenderSession(maxAudioBytes = -1) }
        assertFailsWith<IllegalArgumentException> { LibrationRenderSession(maxPopulations = -1) }
    }

    @Test fun `interrupting a long preview stops work and leaves the replacement render usable`() {
        val session = LibrationRenderSession()
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            try {
                session.render(note().copy(sourceSeconds = 8.0, macros =
                    note().macros + mapOf("REGION" to .375f, "SWARM" to 1f, "RESONANCE" to 1f)))
            } catch (e: Throwable) { failure.set(e) }
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(35)
            worker.interrupt()
            worker.join(5_000)
            assertFalse(worker.isAlive, "cancelled preview still running")
            assertIs<CancellationException>(failure.get())
            val replacement = note()
            val result = session.renderTimed(replacement)
            assertFalse(result.timing.audioCacheHit)
            assertContentEquals(replacement.render().samples, result.snip.samples)
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test fun `held cache separates playback models reuses fixed physics and cannot leak mutable audio`() {
        val finite = note()
        val held = finite.copy(macros = finite.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION)
        val session = LibrationRenderSession()
        val first = session.renderTimed(held)
        assertFalse(first.timing.audioCacheHit)
        assertContentEquals(held.render().samples, first.snip.samples)
        val expected = first.snip.samples.copyOf()
        first.snip.samples.fill(0f)
        val recalled = session.renderTimed(held.copy(name = "Held renamed", sourceSeconds = .77))
        assertTrue(recalled.timing.audioCacheHit, "finite LENGTH is inactive for a held orbit")
        assertContentEquals(expected, recalled.snip.samples)
        assertTrue(session.renderTimed(held.copy(midi = 48)).timing.populationCacheHit)
        assertFalse(session.renderTimed(finite).timing.audioCacheHit, "held and finite audio must never share an entry")
        assertFalse(session.renderTimed(held.copy(macros = held.macros + ("NUDGE" to .97f))).timing.populationCacheHit)
        val uncached = LibrationRenderSession(maxAudioBytes = 1, maxPopulations = 0)
        uncached.render(held)
        val repeated = uncached.renderTimed(held)
        assertFalse(repeated.timing.audioCacheHit)
        assertFalse(repeated.timing.populationCacheHit)
        assertContentEquals(expected, repeated.snip.samples)
    }
}
