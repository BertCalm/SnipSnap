package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Cache boundaries and ownership, using an injected renderer so work counts are unambiguous. */
class ScannedChainRenderCacheTest {
    private val voice = ScannedChainVoice.PLUCK
    private val shape = ScannedChainShape.factory(voice)
    private val config = ScannedChainConfig.forVoice(voice).copy(seconds = .25f)

    @BeforeTest
    fun resetCache() = ScannedChainRenderCache.clear()

    @AfterTest
    fun releaseCache() {
        Thread.interrupted()
        ScannedChainRenderCache.clear()
    }

    private fun render(
        model: Int = 1,
        voice: ScannedChainVoice = this.voice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = 60,
        velocity: Float = .8f,
        shape: ScannedChainShape = this.shape,
        config: ScannedChainConfig = this.config,
        renderer: () -> Snip,
    ): Snip = ScannedChainRenderCache.render(model, voice, macros, midi, velocity, shape, config, renderer)

    private fun clip(value: Float = .125f): Snip = Snip(FloatArray(32) { if (it % 2 == 0) value else -value }, 1, 44_100)

    @Test
    fun `a real preview and renamed pad recipe share dry audio while effects stay separate`() {
        val patch = ScannedChainPatch("Preview", voice, shape = shape, config = config, model = 1)
        val preview = patch.render()
        val delivered = PadRecipe(patch.copy(name = "Saved pad")).render()
        assertContentEquals(preview.samples, delivered.samples)
        assertNotSame(preview.samples, delivered.samples)
        val processed = PadRecipe(patch.copy(name = "Reversed pad"), FxChain(reverse = true)).render()
        assertTrue(!preview.samples.contentEquals(processed.samples), "effects process the owned dry hit separately")
        delivered.samples.fill(0f)
        processed.samples.fill(0f)
        assertContentEquals(preview.samples, patch.render().samples)
        assertEquals(ScannedChainRenderCache.Stats(3, 1, 1, preview.samples.size), ScannedChainRenderCache.stats())
    }

    @Test
    fun `identical sonic settings reuse one completed render while every caller owns its samples`() {
        val calls = AtomicInteger()
        val producer = clip()
        val expected = producer.samples.copyOf()
        val first = render { calls.incrementAndGet(); producer }
        assertNotSame(producer.samples, first.samples)
        producer.samples.fill(.9f)
        first.samples.fill(-.9f)
        val second = render { calls.incrementAndGet(); error("the second request must be a hit") }
        assertContentEquals(expected, second.samples)
        second.samples.fill(0f)
        assertContentEquals(expected, render { error("the third request must be a hit") }.samples)
        assertEquals(1, calls.get())
        assertEquals(ScannedChainRenderCache.Stats(2, 1, 1, 32), ScannedChainRenderCache.stats())
    }

    @Test
    fun `resolved defaults map order labels and source provenance do not change the sonic key`() {
        val calls = AtomicInteger()
        render { calls.incrementAndGet(); clip() }
        val drawn = ScannedChainShape(shape.points, ScannedChainShapeSource.DRAWN, "Renamed drawing")
        val reversed = ScannedChain.defaults(voice).entries.reversed().associate { it.key to it.value }
        render(macros = reversed, shape = drawn) { calls.incrementAndGet(); clip(.2f) }
        val sample = ScannedChainShape(shape.points, ScannedChainShapeSource.SAMPLE, "A different recording label",
            ScannedChainSampleInfo(ScannedChainSampleMode.TIMBRAL_WINDOW, 48_000, 2, 120, 960, "a".repeat(64)))
        render(macros = mapOf("MOTION" to 1f), shape = sample) { calls.incrementAndGet(); clip(.3f) }
        val renamed = ScannedChainPatch("Saved under another name", voice, reversed, sample, config, model = 1)
        ScannedChainRenderCache.render(renamed.model, renamed.voice, renamed.macros, renamed.midi,
            renamed.velocity, renamed.shape, renamed.config) { calls.incrementAndGet(); clip(.4f) }
        assertEquals(1, calls.get())
        assertEquals(3L, ScannedChainRenderCache.stats().hits)
    }

    @Test
    fun `model note velocity macro configuration and exact point bits each invalidate the entry`() {
        val calls = AtomicInteger()
        fun produced(): Snip { calls.incrementAndGet(); return clip() }
        val changes: List<() -> Unit> = listOf(
            { render(model = 2, renderer = ::produced) },
            { render(midi = 61, renderer = ::produced) },
            { render(velocity = .7f, renderer = ::produced) },
            { render(macros = mapOf("MOTION" to .5f), renderer = ::produced) },
            { render(macros = mapOf("DAMP" to .5f), renderer = ::produced) },
            { render(macros = mapOf("BEND" to .5f), renderer = ::produced) },
            { render(macros = mapOf("BLOOM" to .5f), renderer = ::produced) },
            { render(config = config.copy(seed = 17), renderer = ::produced) },
            { render(config = config.copy(restFraction = .2f), renderer = ::produced) },
            { render(config = config.copy(seconds = .3f), renderer = ::produced) },
            { render(config = config.copy(attackSeconds = .1f), renderer = ::produced) },
            { render(config = config.copy(releaseSeconds = .1f), renderer = ::produced) },
            { render(shape = ScannedChainShape.drawn(FloatArray(128) { (.1 * sin(2 * PI * it / 128)).toFloat() }), renderer = ::produced) },
            { render(voice = ScannedChainVoice.METAL, config = ScannedChainConfig.forVoice(ScannedChainVoice.METAL).copy(seconds = .25f), renderer = ::produced) },
        )
        for (change in changes) {
            ScannedChainRenderCache.clear()
            val before = calls.get()
            render(renderer = ::produced)
            change()
            assertEquals(before + 2, calls.get(), "a changed sonic input must compute fresh audio")
        }
        // Numerical equality of +/-0 must not erase the saved point-bit distinction.
        ScannedChainRenderCache.clear()
        val points = FloatArray(128)
        render(shape = ScannedChainShape(points, ScannedChainShapeSource.DRAWN, "Zero"), renderer = ::produced)
        points[0] = -0f
        val before = calls.get()
        render(shape = ScannedChainShape(points, ScannedChainShapeSource.DRAWN, "Signed zero"), renderer = ::produced)
        assertEquals(before + 1, calls.get())
    }

    @Test
    fun `only one bounded completed clip is retained`() {
        val calls = AtomicInteger()
        fun produced(): Snip { calls.incrementAndGet(); return clip() }
        render(renderer = ::produced)
        render(midi = 61, renderer = ::produced)
        render(renderer = ::produced)
        assertEquals(3, calls.get(), "the cache is one completed source, not an accumulating history")
        repeat(2) {
            render(midi = 62) { calls.incrementAndGet(); Snip(FloatArray(ScannedChainRenderCache.MAX_CACHED_SAMPLES + 1), 1, 44_100) }
        }
        assertEquals(5, calls.get(), "an oversized result cannot become a retained entry")
        assertTrue(ScannedChainRenderCache.stats().retainedSamples <= ScannedChainRenderCache.MAX_CACHED_SAMPLES)
        render(renderer = ::produced)
        assertEquals(5, calls.get(), "an uncacheable result leaves the preceding valid entry usable")
    }

    @Test
    fun `failed or interrupted work cannot replace a completed source`() {
        val expected = render { clip() }.samples
        assertFailsWith<IllegalStateException> { render(midi = 61) { error("render failed before completion") } }
        assertFailsWith<CancellationException> {
            try {
                render(midi = 61) { Thread.currentThread().interrupt(); clip(.4f) }
            } finally {
                Thread.interrupted()
            }
        }
        assertContentEquals(expected, render { error("the earlier completed source must survive") }.samples)
        assertEquals(1L, ScannedChainRenderCache.stats().publications)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { render { error("a pre-interrupted hit must not return audio") } }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a running miss does not block hits and clear invalidates its eventual publication`() {
        val expected = render { clip() }.samples
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Snip> {
                render(midi = 61) {
                    started.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    clip(.3f)
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertContentEquals(expected, render { error("a preceding completed hit must remain available") }.samples)
            ScannedChainRenderCache.clear()
            finish.countDown()
            assertEquals(.3f, pending.get(5, TimeUnit.SECONDS).samples.first())
            assertEquals(0, ScannedChainRenderCache.stats().retainedSamples)
            render(midi = 61) { clip(.4f) }
            assertEquals(1L, ScannedChainRenderCache.stats().publications)
        } finally {
            finish.countDown()
            worker.shutdownNow()
        }
    }
}
