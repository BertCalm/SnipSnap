package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CleanupTest {

    private fun snip(vararg samples: Float, channels: Int = 1) =
        Snip(samples.toList().toFloatArray(), channels, 44_100)

    private fun tone(frames: Int, amplitude: Float = 0.5f, offset: Float = 0f) =
        Snip(
            FloatArray(frames) { offset + amplitude * sin(2.0 * Math.PI * 440.0 * it / 44_100).toFloat() },
            1,
            44_100,
        )

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-4f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected ~$expected, got $actual")

    // --- DC offset ---

    @Test
    fun `removes a dc offset`() {
        val biased = tone(1000, amplitude = 0.3f, offset = 0.2f)
        val fixed = Cleanup.removeDcOffset(biased)

        val mean = fixed.samples.sum() / fixed.samples.size
        assertClose(0f, mean)
    }

    @Test
    fun `removes dc per channel`() {
        // Channels can be biased differently; a single global mean would leave
        // one of them offset.
        val s = Snip(floatArrayOf(1f, 5f, 1f, 5f, 1f, 5f), channels = 2, sampleRate = 44_100)
        val fixed = Cleanup.removeDcOffset(s)

        assertTrue(fixed.samples.all { it == 0f }, "each channel should centre on its own mean")
    }

    @Test
    fun `leaves already centred audio alone`() {
        val centred = snip(-0.5f, 0.5f, -0.5f, 0.5f)
        assertTrue(Cleanup.removeDcOffset(centred).samples.all { abs(it) == 0.5f })
    }

    // --- Trim ---

    @Test
    fun `trims leading and trailing silence`() {
        val s = snip(0f, 0f, 0f, 0.5f, 0.8f, 0.5f, 0f, 0f, 0f)
        val trimmed = Cleanup.trimSilence(s, thresholdDb = -60f, preRollFrames = 0)

        assertEquals(3, trimmed.frameCount)
        assertClose(0.5f, trimmed.samples.first())
        assertClose(0.5f, trimmed.samples.last())
    }

    @Test
    fun `keeps pre-roll ahead of the transient`() {
        // Cutting exactly at the first loud sample shaves the attack. The
        // pre-roll is what keeps a kick from becoming a click.
        val s = snip(0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f)
        val trimmed = Cleanup.trimSilence(s, thresholdDb = -60f, preRollFrames = 3)

        assertEquals(5, trimmed.frameCount) // 3 pre-roll + 2 loud
        assertClose(0f, trimmed.samples.first())
    }

    @Test
    fun `pre-roll cannot run past the start`() {
        val s = snip(1f, 1f, 0f)
        val trimmed = Cleanup.trimSilence(s, thresholdDb = -60f, preRollFrames = 1000)
        assertEquals(2, trimmed.frameCount)
    }

    @Test
    fun `all-silence trims to nothing`() {
        val s = snip(0f, 0f, 0f, 0f)
        assertEquals(0, Cleanup.trimSilence(s, -60f, 0).frameCount)
    }

    @Test
    fun `quiet but audible material is not trimmed away`() {
        // -40 dBFS is quiet, not silent. Trimming it would eat real tails.
        val quiet = Cleanup.dbToLinear(-40f)
        val s = snip(quiet, quiet, quiet)
        assertEquals(3, Cleanup.trimSilence(s, thresholdDb = -60f, preRollFrames = 0).frameCount)
    }

    @Test
    fun `trims stereo on either channel being loud`() {
        // frame 1 is loud only in the right channel; it still counts as content.
        val s = Snip(floatArrayOf(0f, 0f, 0f, 0.9f, 0f, 0f), channels = 2, sampleRate = 44_100)
        val trimmed = Cleanup.trimSilence(s, thresholdDb = -60f, preRollFrames = 0)
        assertEquals(1, trimmed.frameCount)
    }

    // --- Normalize ---

    @Test
    fun `normalizes to the target peak`() {
        val quiet = tone(1000, amplitude = 0.1f)
        val loud = Cleanup.normalize(quiet, targetPeakDb = -0.3f)

        assertClose(Cleanup.dbToLinear(-0.3f), loud.peak(), tolerance = 1e-3f)
    }

    @Test
    fun `normalizing turns hot material down`() {
        val hot = tone(1000, amplitude = 1.0f)
        val tamed = Cleanup.normalize(hot, targetPeakDb = -6f)

        assertClose(Cleanup.dbToLinear(-6f), tamed.peak(), tolerance = 1e-3f)
        assertTrue(tamed.peak() < hot.peak())
    }

    @Test
    fun `normalizing silence does not divide by zero`() {
        val silence = snip(0f, 0f, 0f)
        val result = Cleanup.normalize(silence, -0.3f)

        assertTrue(result.samples.all { it == 0f })
        assertTrue(result.samples.none { it.isNaN() })
    }

    @Test
    fun `peak ignores a non-finite sample, so one NaN cannot silence the rest`() {
        // Normalization scales by target/peak; if peak() returned NaN, every
        // sample would go NaN and the whole snip would silence on export.
        // peak() must see through a poisoned sample to the real loudest one.
        val poisoned = Snip(floatArrayOf(0.4f, Float.NaN, Float.POSITIVE_INFINITY, -0.4f), 1, 44_100)
        // The loudest *finite* sample, not Inf and not NaN.
        assertClose(0.4f, poisoned.peak(), tolerance = 1e-6f)

        // With a finite peak, normalization turns the real audio up instead of
        // dividing the whole snip to silence (which a peak of Inf would force).
        val normalized = Cleanup.normalize(poisoned, targetPeakDb = -0.3f)
        assertTrue(normalized.samples[0].isFinite() && normalized.samples[3].isFinite())
        assertTrue(kotlin.math.abs(normalized.samples[0]) > 0.4f, "the real audio was turned up, not silenced")
    }

    @Test
    fun `normalized output never clips`() {
        val s = tone(1000, amplitude = 0.9f)
        val normalized = Cleanup.normalize(s, targetPeakDb = 0f)
        assertTrue(normalized.samples.all { it in -1f..1f })
    }

    // --- Fades ---

    @Test
    fun `fades start quiet and end quiet`() {
        val s = Snip(FloatArray(4410) { 1f }, 1, 44_100)
        val faded = Cleanup.applyFades(s, fadeInMs = 2f, fadeOutMs = 5f)

        assertTrue(faded.samples.first() < 0.1f, "head should be faded in")
        assertTrue(faded.samples.last() < 0.1f, "tail should be faded out")
        assertClose(1f, faded.samples[2205]) // untouched middle
    }

    @Test
    fun `fade reaches full gain at its end`() {
        val s = Snip(FloatArray(1000) { 1f }, 1, 44_100)
        val fadeFrames = (2f / 1000f * 44_100).toInt()
        val faded = Cleanup.applyFades(s, fadeInMs = 2f, fadeOutMs = 0f)

        assertClose(1f, faded.samples[fadeFrames - 1])
    }

    @Test
    fun `fades longer than the snip do not overrun`() {
        val s = Snip(FloatArray(10) { 1f }, 1, 44_100)
        val faded = Cleanup.applyFades(s, fadeInMs = 1000f, fadeOutMs = 1000f)

        assertEquals(10, faded.frameCount)
        assertTrue(faded.samples.all { it in 0f..1f })
    }

    @Test
    fun `fades apply to every channel`() {
        val s = Snip(FloatArray(2000) { 1f }, channels = 2, sampleRate = 44_100)
        val faded = Cleanup.applyFades(s, fadeInMs = 2f, fadeOutMs = 2f)

        assertTrue(faded.samples[0] < 0.1f)
        assertTrue(faded.samples[1] < 0.1f) // right channel too
    }

    // --- Pipeline ---

    @Test
    fun `the default chain produces a pad-ready one-shot`() {
        val raw = Snip(
            FloatArray(10_000) { i ->
                val body = if (i in 1000..8000) 0.05f * sin(2.0 * Math.PI * 220.0 * i / 44_100).toFloat() else 0f
                body + 0.02f // dc offset
            },
            1,
            44_100,
        )

        val clean = Cleanup.process(raw)

        assertTrue(clean.frameCount < raw.frameCount, "silence should be trimmed")
        assertTrue(clean.peak() > 0.9f, "should be normalized up, got ${clean.peak()}")
        assertTrue(clean.samples.all { it in -1f..1f }, "must not clip")
        assertTrue(abs(clean.samples.last()) < 0.05f, "tail must be faded to avoid a click")
    }

    @Test
    fun `the chain survives an all-silent snip`() {
        val silence = Snip(FloatArray(1000), 1, 44_100)
        val clean = Cleanup.process(silence)

        assertEquals(0, clean.frameCount)
        assertTrue(clean.samples.none { it.isNaN() })
    }

    @Test
    fun `steps can be turned off`() {
        val raw = tone(1000, amplitude = 0.1f)
        val untouched = Cleanup.process(
            raw,
            CleanupConfig(normalize = false, trimSilence = false, removeDcOffset = false, fadeInMs = 0f, fadeOutMs = 0f),
        )

        assertEquals(raw.frameCount, untouched.frameCount)
        assertClose(raw.peak(), untouched.peak())
    }

    // --- Mono fold ---

    @Test
    fun `folds stereo to mono by averaging`() {
        val s = Snip(floatArrayOf(1f, 0f, 0.5f, 0.5f), channels = 2, sampleRate = 44_100)
        val mono = Cleanup.toMono(s)

        assertEquals(1, mono.channels)
        assertEquals(2, mono.frameCount)
        assertClose(0.5f, mono.samples[0])
        assertClose(0.5f, mono.samples[1])
    }

    @Test
    fun `folding mono is a no-op`() {
        val s = snip(0.1f, 0.2f)
        assertEquals(2, Cleanup.toMono(s).frameCount)
    }

    // --- Units and guards ---

    @Test
    fun `db converts both ways`() {
        assertClose(1f, Cleanup.dbToLinear(0f))
        assertClose(0.5f, Cleanup.dbToLinear(-6.0206f), tolerance = 1e-3f)
        assertClose(0f, Cleanup.linearToDb(1f))
        assertEquals(Float.NEGATIVE_INFINITY, Cleanup.linearToDb(0f))
    }

    @Test
    fun `rejects malformed snips`() {
        assertFailsWith<IllegalArgumentException> { Snip(FloatArray(3), channels = 2, sampleRate = 44_100) }
        assertFailsWith<IllegalArgumentException> { Snip(FloatArray(4), channels = 0, sampleRate = 44_100) }
        assertFailsWith<IllegalArgumentException> { Snip(FloatArray(4), channels = 1, sampleRate = 0) }
    }
}
