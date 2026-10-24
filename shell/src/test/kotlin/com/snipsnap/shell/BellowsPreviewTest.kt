package com.snipsnap.shell

import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsKeys
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsVoice
import java.util.concurrent.CancellationException
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BellowsPreviewTest {
    private val rate = 44_100
    private val holdFrames = rate * 6

    private fun patch(voice: BellowsVoice) = BellowsPatch("PREVIEW", voice, Bellows.defaults(voice))

    private fun rms(samples: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))

    @Test
    fun `all four previews hold the same attack and loop then obey the chosen release`() {
        for (voice in BellowsVoice.entries) {
            val patch = patch(voice)
            val sourcePatch = BellowsPadMaker.melodyPatch(patch)
            val source = BellowsKeys.layer(sourcePatch.voice, sourcePatch.macros, 60, .65f, sourcePatch.gesture, sourcePatch.model)
            assertTrue(source.snip.frameCount < holdFrames, "$voice preview must cross the source EOF")
            val short = BellowsPadMaker.preview(BellowsPadMaker.Spec(patch, .1f))
            val long = BellowsPadMaker.preview(BellowsPadMaker.Spec(patch, 1.5f))
            assertEquals(1, short.channels)
            assertEquals(rate, short.sampleRate)
            assertEquals(holdFrames + 4_410 + 441, short.frameCount, "$voice short duration")
            assertEquals(holdFrames + 66_150 + 441, long.frameCount, "$voice long duration")
            assertContentEquals(short.samples.copyOfRange(0, holdFrames), long.samples.copyOfRange(0, holdFrames),
                "$voice release must not alter the source attack or held sound")
            assertTrue(short.samples.all { it.isFinite() && it in -1f..1f }, "$voice short is bounded")
            assertTrue(long.samples.all { it.isFinite() && it in -1f..1f }, "$voice long is bounded")
            assertTrue(rms(short.samples, holdFrames - 4_410, holdFrames) > .001, "$voice sustains past the source EOF")
            val afterShortRelease = holdFrames + 6_615 // 150 ms after note-off
            assertTrue(rms(long.samples, afterShortRelease, afterShortRelease + 4_410) > .001,
                "$voice long release must still be sounding after a short release has ended")
            assertTrue(short.samples.takeLast(220).all { it == 0f }, "$voice short ends in silence")
            assertTrue(long.samples.takeLast(220).all { it == 0f }, "$voice long ends in silence")
        }
    }

    @Test
    fun `preview uses the exported middle timbre at host gain and is deterministic without changing the patch`() {
        val patch = patch(BellowsVoice.FOLK)
        val macros = patch.macros.toMap()
        val sourcePatch = BellowsPadMaker.melodyPatch(patch)
        val source = BellowsKeys.layer(sourcePatch.voice, sourcePatch.macros, 60, .65f, sourcePatch.gesture, sourcePatch.model)
        val before = source.snip.samples.copyOf()
        val spec = BellowsPadMaker.Spec(patch)
        val first = BellowsPadMaker.preview(spec)
        val second = BellowsPadMaker.preview(spec)
        assertContentEquals(first.samples, second.samples)
        assertEquals(macros, patch.macros, "preview may not edit the saved macros")
        val gain = .65f * InstrumentEngine.VOICE_LEVEL
        val loopStart = source.loopStartFrame.toInt()
        for (i in 0 until holdFrames) {
            val sourceFrame = if (i < before.size) i else loopStart + (i - before.size) % (before.size - loopStart)
            assertEquals(before[sourceFrame] * gain, first.samples[i], 1e-6f, "source frame at preview $i")
        }
        assertContentEquals(before, source.snip.samples, "the independently retained source remains untouched")
        val after = BellowsKeys.layer(sourcePatch.voice, sourcePatch.macros, 60, .65f, sourcePatch.gesture, sourcePatch.model)
        assertContentEquals(before, after.snip.samples, "preview leaves future export source PCM unchanged")
    }

    @Test
    fun `preview cancellation stops both source rendering and the sample transport`() {
        val patch = patch(BellowsVoice.CHAMBER)
        val spec = BellowsPadMaker.Spec(patch)
        assertFailsWith<CancellationException> { BellowsPadMaker.preview(spec) { true } }
        val sourcePatch = BellowsPadMaker.melodyPatch(patch)
        var sourceChecks = 0
        BellowsKeys.layer(sourcePatch.voice, sourcePatch.macros, 60, .65f, sourcePatch.gesture, sourcePatch.model) {
            sourceChecks++
            false
        }
        var checks = 0
        assertFailsWith<CancellationException> { BellowsPadMaker.preview(spec) { ++checks > sourceChecks + 1 } }
        assertTrue(checks > sourceChecks, "cancellation must also be checked after the source render")
    }
}
