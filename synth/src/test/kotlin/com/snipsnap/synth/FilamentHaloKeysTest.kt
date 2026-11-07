package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.*

class FilamentHaloKeysTest {
    @Test
    fun `one clock and one gain reconstruct the unchanged CURL capture`() {
        val patch = FilamentPresets.forVoice(FilamentVoice.CURL).first().copy(midi = 60)
        val saved = patch.toJsonText()
        val parts = FilamentHaloKeys.capture(patch)
        val legacy = FilamentKeys.note(patch)
        assertContentEquals(legacy.snip.samples, parts.mixed.snip.samples)
        assertEquals(saved, patch.toJsonText())
        for (part in listOf(parts.body, parts.halo)) {
            assertEquals(legacy.loopStartFrame, part.loopStartFrame)
            assertEquals(legacy.snip.frameCount, part.snip.frameCount)
            assertTrue(part.snip.samples.all { it.isFinite() && abs(it) < 1f })
            assertTrue(part.snip.samples.any { abs(it) > 1e-5f })
            assertTrue(Keys.seamError(part.snip.samples, part.loopStartFrame.toInt()) < 1e-3)
        }
        val maxError = legacy.snip.samples.indices.maxOf { i ->
            abs(parts.body.snip.samples[i] + parts.halo.snip.samples[i] - legacy.snip.samples[i])
        }
        assertTrue(maxError < 1e-7f, "common-gain reconstruction error $maxError")
        val repeat = FilamentHaloKeys.capture(patch)
        assertContentEquals(parts.body.snip.samples, repeat.body.snip.samples)
        assertContentEquals(parts.halo.snip.samples, repeat.halo.snip.samples)
    }

    @Test
    fun `prototype is explicit and cancellation reaches the same solver`() {
        val patch = FilamentPresets.forVoice(FilamentVoice.CURL).first()
        assertFailsWith<IllegalArgumentException> { FilamentHaloKeys.capture(patch.copy(model = 2)) }
        assertFailsWith<IllegalArgumentException> { FilamentHaloKeys.capture(patch.copy(voice = FilamentVoice.THREAD)) }
        assertFailsWith<CancellationException> { FilamentHaloKeys.capture(patch, cancelled = { true }) }
    }
}
