package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WideTest {
    private fun probe(rate: Int = 44_100, channels: Int = 1): Snip {
        val frames = rate / 4
        return Snip(FloatArray(frames * channels) { i ->
            val f = i / channels
            if (channels == 2 && i % 2 == 1) 0f
            else (.3 * sin(2.0 * PI * 220.0 * f / rate) * (1.0 - f.toDouble() / frames)).toFloat()
        }, channels, rate)
    }

    @Test
    fun `zero is a bit exact hard bypass including signed zero and empty input`() {
        val source = Snip(floatArrayOf(-0.0f, .3f, -.7f, 0f), 2, 44_100)
        val before = source.samples.map { it.toRawBits() }
        assertSame(source, Wide.process(source, 0f))
        assertSame(source, BoardRecipe(amount = 0f).process(source))
        assertEquals(before, source.samples.map { it.toRawBits() })
        val empty = Snip(FloatArray(0), 1, 44_100)
        assertSame(empty, Wide.process(empty, 0f))
        assertSame(empty, Wide.process(empty, 1f))
    }

    @Test
    fun `amount rejects invalid values instead of clamping them`() {
        for (amount in listOf(-.001f, 1.001f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Wide.process(probe(), amount) }
            assertFailsWith<IllegalArgumentException> { BoardRecipe(amount = amount) }
        }
    }

    @Test
    fun `mono widens cleanly and stereo keeps each channel with a bounded tail`() {
        for (rate in listOf(44_100, 48_000)) for (amount in listOf(.35f, .7f, 1f)) {
            val mono = probe(rate)
            val before = mono.samples.copyOf()
            val out = Wide.process(mono, amount)
            assertEquals(2, out.channels)
            assertEquals(rate, out.sampleRate)
            assertTrue(out.samples.all { it.isFinite() })
            assertTrue(out.samples.indices.any { it % 2 == 0 && out.samples[it] != out.samples[it + 1] })
            assertTrue(out.frameCount > mono.frameCount)
            assertTrue(out.frameCount <= mono.frameCount + (Wide.MAX_TAIL_SECONDS * rate).toInt())
            assertEquals(mono.peak(), out.peak(), 1e-5f, "production keeps processor peak matching, without audition gain")
            assertContentEquals(before, mono.samples, "the source must remain available for later edits")

            // A silent right channel must remain silent: narrowing the input
            // to mono and widening it again would leak the left into it.
            val stereo = Wide.process(probe(rate, channels = 2), amount)
            assertEquals(2, stereo.channels)
            assertTrue(stereo.samples.indices.all { it % 2 == 0 || stereo.samples[it] == 0f })
            assertTrue(stereo.samples.indices.any { it % 2 == 0 && stereo.samples[it] != 0f })
        }
    }

    @Test
    fun `silence stays finite and rendering retains no state between calls`() {
        val source = probe(channels = 2)
        val recipe = BoardRecipe(amount = .7f)
        val first = recipe.process(source)
        val silence = recipe.process(Snip(FloatArray(1024), 1, 44_100))
        assertTrue(silence.samples.all { it.isFinite() && it == 0f })
        assertContentEquals(first.samples, recipe.process(source).samples)
        assertContentEquals(first.samples, BoardRecipe.fromJsonText(recipe.toJsonText()).process(source).samples)
    }

    @Test
    fun `accepted audition PCM pins all three intensity mappings and their processing order`() {
        // Captured from the first WIDE audition before moving its renderer
        // into production. These WAV hashes independently pin the audible
        // result, including Tape -> EQ (the reverse of FxChain's order).
        val expected = linkedMapOf(
            .35f to "54b9be8fb489f24dc90ef3cf11235864296273a7e3f7346641af7dfb632ab450",
            .7f to "a8ece0b6600b8654850d214487b6e6c8d2fff5b0f3e58878bf93b572e8e9a5db",
            1f to "795b0e983d54b8986943e2bdb9bf42e9796f5c98bec965fbf12c29011be4facf",
        )
        val source = Pluck.render(PluckVoice.NYLON)
        val full = Wide.process(source, 1f)
        val secondAt = (2.25 * source.sampleRate).roundToInt()
        val frames = maxOf(4 * source.sampleRate, secondAt + full.frameCount + (.2 * source.sampleRate).roundToInt())
        for ((amount, hash) in expected) {
            val hit = Wide.process(source, amount)
            val phrase = FloatArray(frames * 2)
            for (at in listOf(0, secondAt)) for (i in hit.samples.indices) phrase[at * 2 + i] += hit.samples[i]
            val levelled = AuditionLevel.level(Snip(phrase, 2, source.sampleRate))
            val wav = ByteArrayOutputStream()
            WavWriter.write(wav, levelled, WavWriter.BitDepth.PCM_24)
            val actual = MessageDigest.getInstance("SHA-256").digest(wav.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(hash, actual, "AMT $amount differs from the accepted first audition")
        }
    }
}
