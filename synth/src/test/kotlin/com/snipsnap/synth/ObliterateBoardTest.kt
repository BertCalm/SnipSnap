package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class ObliterateBoardTest {
    @BeforeTest fun reset() { ObliterateBoardCache.clear() }
    private fun source() = Snip(FloatArray(2400) { (.25 * sin(2 * PI * 220 * it / 44100)).toFloat() }, 1, 44100)

    @Test fun `version two saves every control and renderer identity while legacy bytes stay fixed`() {
        val state = ObliterateBoard.Settings(.3, false, 93.0, 8, 0xffff_ffffL)
        val recipe = BoardRecipe(BoardType.OBLITERATE, .7f, state)
        val json = recipe.toJsonValue()
        assertEquals(setOf("board", "type", "amount", "obliterate"), json.entries.keys)
        assertEquals(JsonValue.Num(2.0), json.entries["board"])
        assertEquals(recipe, BoardRecipe.fromJsonValue(json))
        assertEquals(recipe.toJsonText(), BoardRecipe.fromJsonText(recipe.toJsonText()).toJsonText())
        for (type in listOf(BoardType.WIDE, BoardType.RUSH)) {
            assertEquals("{\n  \"board\": 1,\n  \"type\": \"${type.id}\",\n  \"amount\": 0.5\n}", BoardRecipe(type, .5f).toJsonText())
        }
    }

    @Test fun `future models and incomplete or malformed settings refuse without changing cache`() {
        val state = ObliterateBoard.Settings().toJsonValue()
        val invalid = listOf(
            "model" to JsonValue.Str("future"), "coreModel" to JsonValue.Str("future"), "shimmerModel" to JsonValue.Str("future"),
            "shimmerMix" to JsonValue.Num(Double.NaN), "shimmerMix" to JsonValue.Num(.500000001),
            "bpm" to JsonValue.Num(39.9), "bpm" to JsonValue.Num(Double.POSITIVE_INFINITY),
            "division" to JsonValue.Num(16.5), "division" to JsonValue.Num(3.0),
            "seed" to JsonValue.Num(-1.0), "seed" to JsonValue.Num(4294967296.0), "seed" to JsonValue.Num(.5),
            "pitched" to JsonValue.Str("true"), "unknown" to JsonValue.Null,
        )
        for ((key, value) in invalid) assertFailsWith<JsonException>("accepted $key=$value") {
            ObliterateBoard.Settings.fromJsonValue(JsonValue.Obj(state.entries + (key to value)))
        }
        for (key in state.entries.keys) assertFailsWith<JsonException> {
            ObliterateBoard.Settings.fromJsonValue(JsonValue.Obj(state.entries - key))
        }
        assertFailsWith<JsonException> { BoardRecipe.fromJsonText("""{"board":1,"type":"obliterate","amount":0.5}""") }
        assertFailsWith<IllegalArgumentException> { BoardRecipe(BoardType.WIDE, .5f, ObliterateBoard.Settings()) }
        assertEquals(0, ObliterateBoardCache.stats().retainedSamples)
    }

    @Test fun `zero is same source even for signed zeros empty and over budget sources`() {
        val recipe = BoardRecipe(BoardType.OBLITERATE, 0f)
        for (s in listOf(Snip(floatArrayOf(-0f, .2f), 1, 44100), Snip(floatArrayOf(), 2, 44100), Snip(FloatArray(10 * 44100), 1, 44100))) {
            assertTrue(recipe.isBypass); assertSame(s, recipe.process(s))
        }
        assertEquals(0, ObliterateBoardCache.stats().retainedSamples)
    }

    @Test fun `mapping keeps the chosen inner send and blends the full board below the heard anchor`() {
        for (mix in listOf(0.0, .15, .3, .5)) {
            val state = ObliterateBoard.Settings(mix)
            assertEquals(mix, state.at(0f).shimmer.mix)
            assertEquals(0.0, ObliterateBoard.engagement(0.0))
            for (amount in listOf(.35f, .7f, 1f)) {
                assertEquals(mix, state.at(amount).shimmer.mix)
                assertEquals(amount.toDouble(), state.at(amount).core.amount)
            }
            assertEquals(mix, state.at(.1f).shimmer.mix)
            assertEquals(.1f.toDouble() / .35f.toDouble(), ObliterateBoard.engagement(.1f.toDouble()), 1e-15)
        }
    }

    @Test fun `the full filter and halo path approaches dry at tiny amount with a fading bounded tail`() {
        val input = Snip(FloatArray(4096) { (.2 * sin(2 * PI * 12000 * it / 44100)).toFloat() }, 1, 44100)
        val amount = .000001f
        val recipe = BoardRecipe(BoardType.OBLITERATE, amount)
        val inner = Obliterate.render(input, recipe.obliterate!!.at(amount)).audio
        val actual = recipe.process(input)
        // The fixture distinguishes the fix: the unchanged core's fixed filters alone
        // measurably depart from dry, even at this near-zero amount.
        val unblendedError = input.samples.indices.maxOf { kotlin.math.abs(input.samples[it] - inner.samples[it * 2]) }
        assertTrue(unblendedError > .01f)
        val error = input.samples.indices.maxOf { kotlin.math.abs(input.samples[it] - actual.samples[it * 2]) }
        assertTrue(error < .00001f, "near-zero AMT still changes the dry spectrum: $error")
        val blend = ObliterateBoard.engagement(amount.toDouble())
        for (i in actual.samples.indices) {
            val dry = if (i / 2 < input.frameCount) input.samples[i / 2].toDouble() else 0.0
            assertEquals(((1.0 - blend) * dry + blend * inner.samples[i].toDouble()).toFloat(), actual.samples[i])
        }
        assertTrue(actual.samples.drop(input.frameCount * 2).maxOf { kotlin.math.abs(it) } <= .98 * blend + 1e-10)
        assertTrue(actual.samples.all { it.isFinite() && it in -1f..1f })
    }

    @Test fun `cold and cached boards are sample exact to their public processor composition and own all buffers`() {
        val input = source(); val before = input.samples.copyOf()
        val state = ObliterateBoard.Settings(.3, true, 94.0, 8, 77L)
        val recipe = BoardRecipe(BoardType.OBLITERATE, .7f, state)
        val expected = Obliterate.render(input, state.at(recipe.amount)).audio
        val cold = recipe.process(input)
        assertContentEquals(expected.samples, cold.samples)
        cold.samples.fill(0f)
        assertContentEquals(expected.samples, recipe.process(input.copy(samples = input.samples.copyOf())).samples)
        assertContentEquals(before, input.samples)
        assertEquals(1L, ObliterateBoardCache.stats().fullHits)
        assertEquals(1L, ObliterateBoardCache.stats().fullMisses)
    }

    @Test fun `clock pattern and high amount edits reuse shimmer and every sonic change invalidates full audio`() {
        val s = source(); val base = ObliterateBoard.Settings()
        BoardRecipe(BoardType.OBLITERATE, .35f, base).process(s)
        for (state in listOf(base.copy(bpm = 97.0), base.copy(division = 8), base.copy(seed = 90)))
            BoardRecipe(BoardType.OBLITERATE, .35f, state).process(s)
        BoardRecipe(BoardType.OBLITERATE, .7f, base).process(s)
        assertEquals(4L, ObliterateBoardCache.stats().preHits)
        assertEquals(1L, ObliterateBoardCache.stats().preMisses)
        assertEquals(5L, ObliterateBoardCache.stats().fullMisses)
        BoardRecipe(BoardType.OBLITERATE, .7f, base.copy(pitched = false)).process(s)
        BoardRecipe(BoardType.OBLITERATE, .7f, base.copy(shimmerMix = .3)).process(s)
        assertEquals(3L, ObliterateBoardCache.stats().preMisses)
        assertTrue(ObliterateBoardCache.stats().retainedSamples <= ObliterateBoardCache.MAX_PRE_SAMPLES + ObliterateBoardCache.MAX_OUTPUT_SAMPLES)
    }

    @Test fun `mutated input and a changed channel layout cannot borrow a previous result`() {
        val recipe = BoardRecipe(BoardType.OBLITERATE, .35f); val s = source()
        recipe.process(s); s.samples[100] += .1f
        val changed = recipe.process(s)
        assertContentEquals(Obliterate.render(s, recipe.obliterate!!.at(recipe.amount)).audio.samples, changed.samples)
        val stereo = s.copy(channels = 2)
        val rendered = recipe.process(stereo)
        assertEquals(stereo.frameCount + ObliterateShimmer.TAIL_FRAMES + ObliterateCore.TAIL_FRAMES, rendered.frameCount)
        assertEquals(3L, ObliterateBoardCache.stats().fullMisses)
    }

    @Test fun `invalid input and interrupted hits never replace a completed result`() {
        val recipe = BoardRecipe(BoardType.OBLITERATE, .35f); val s = source(); val expected = recipe.process(s)
        for (bad in listOf(s.copy(sampleRate = 48000), Snip(floatArrayOf(Float.NaN), 1, 44100), Snip(floatArrayOf(1.1f), 1, 44100)))
            assertFailsWith<IllegalArgumentException> { recipe.process(bad) }
        Thread.currentThread().interrupt()
        try { assertFailsWith<InterruptedException> { recipe.process(s) } }
        finally { Thread.interrupted() }
        assertContentEquals(expected.samples, recipe.process(s).samples)
        assertEquals(1L, ObliterateBoardCache.stats().fullMisses)
    }

    @Test fun `a blocked miss cannot block hits and clear prevents its later publication`() {
        val cache = ObliterateBoardCache.Completed<String>(100)
        val source = source().copy(samples = FloatArray(30) { .1f })
        cache.render("ready") { source }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try { cache.render("blocked") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); source } }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertContentEquals(source.samples, cache.render("ready") { error("a hit rendered") }.samples)
            cache.clear()
        } finally { release.countDown(); worker.join(10_000) }
        assertFalse(worker.isAlive); assertNull(failure.get()); assertEquals(0, cache.retained())
    }

    @Test fun `failed oversized and interrupted work cannot replace the last completed buffer`() {
        val cache = ObliterateBoardCache.Completed<String>(100); val source = source().copy(samples = FloatArray(30) { .1f })
        cache.render("ready") { source }
        assertFailsWith<IllegalStateException> { cache.render("failed") { error("failed render") } }
        assertFailsWith<IllegalStateException> { cache.render("large") { source.copy(samples = FloatArray(101)) } }
        try {
            assertFailsWith<InterruptedException> { cache.render("cancelled") { Thread.currentThread().interrupt(); source } }
        } finally { Thread.interrupted() }
        assertContentEquals(FloatArray(30) { .1f }, cache.render("ready") { error("lost cache") }.samples)
    }
}
