package com.snipsnap.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.audio.PcmPlayback
import com.snipsnap.audio.Snip
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismMotionSources
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Playback repeats the authored HOLD region; comparison clips keep their own finite mode. */
@RunWith(AndroidJUnit4::class)
class PrismHoldPlaybackTest {
    @Test
    fun main_hold_keeps_playing_beyond_the_rendered_file_without_changing_its_samples() {
        val patch = PrismPatch("HELD KEY", PrismVoice.KEY, Prism.defaults(PrismVoice.KEY) + ("HOLD" to 1f))
        val snip = patch.render()
        val heard = prismAuditionPlayback(patch, snip)
        assertEquals(0 until snip.frameCount, heard.loop)
        val reader = PcmPlayback(heard.snip.samples, loop = heard.loop)
        val block = FloatArray(snip.frameCount)
        repeat(3) {
            assertEquals("HOLD must not reach EOF after its first exported region", snip.frameCount, reader.readFrames(block))
            assertArrayEquals("each wrap retains the exact unfaded sustain", snip.samples, block, 0f)
        }
        assertNull(prismAuditionPlayback(patch.copy(macros = patch.macros + ("HOLD" to .2f)), snip).loop)
    }

    @Test
    fun moving_uses_its_rendered_hold_while_source_and_static_comparisons_stay_finite() {
        val opening = PrismOpeningPresets.all().single { it.name == "Bowed Bloom" }
        val selected = PrismMotionSources.applySource(opening,
            requireNotNull(PrismMotionSources.byId("fork-mellow-reed")))
        val held = selected.copy(macros = selected.macros + ("HOLD" to 1f),
            motion = requireNotNull(selected.motion).copy(repeat = true, seconds = .2f))
        val savedRecipe = held.toJsonText()
        val moving = renderPrismMotionPreview(held, "MOVING")
        assertEquals(0 until moving.snip.frameCount, moving.loop)
        assertNull(renderPrismMotionPreview(held, "SOURCE").loop)
        assertNull(renderPrismMotionPreview(held, "STATIC").loop)
        val finite = held.copy(macros = held.macros + ("HOLD" to .2f))
        assertNull(renderPrismMotionPreview(finite, "MOVING").loop)
        assertEquals("comparison rendering must retain the complete saved source", savedRecipe, held.toJsonText())
    }

    @Test
    fun a_reopened_hold_with_echo_plays_its_complete_processed_tail_once() {
        val patch = PrismPatch("ECHO HOLD", PrismVoice.KEY, Prism.defaults(PrismVoice.KEY) + ("HOLD" to 1f))
        val dry = patch.render()
        val recipe = PadRecipe(patch = patch,
            fx = FxChain(echo = mapOf("TIME" to 1f, "REPEAT" to 1f, "MIX" to .5f)))
        val finished = recipe.process(dry)
        assertTrue("the real ECHO recipe must produce a tail", finished.frameCount > dry.frameCount)
        assertTrue("the retained tail must contain audio", (dry.samples.size until finished.samples.size).any {
            kotlin.math.abs(finished.samples[it]) > 1e-5f
        })
        val heard = prismAuditionPlayback(patch, dry, finished)
        assertNull("processed onset and fading tail are not the periodic HOLD region", heard.loop)
        assertSame("playback must preserve the complete processed result", finished, heard.snip)
        val reader = PcmPlayback(heard.snip.samples, loop = heard.loop)
        val block = FloatArray(finished.samples.size)
        assertEquals(finished.frameCount, reader.readFrames(block))
        assertArrayEquals(finished.samples, block, 0f)
        assertEquals("the ECHO tail finishes instead of wrapping into the onset", 0, reader.readFrames(block))

        val sameLength = PadRecipe(patch = patch, fx = FxChain(echo = mapOf("MIX" to 0f))).process(dry)
        assertEquals(dry.frameCount, sameLength.frameCount)
        assertNull("equal length alone does not prove a processed result is periodic",
            prismAuditionPlayback(patch, dry, sameLength).loop)
        val untouched = PadRecipe(patch = patch).process(dry)
        assertSame(dry, untouched)
        assertEquals(0 until dry.frameCount, prismAuditionPlayback(patch, dry, untouched).loop)
    }

    @Test
    fun replaying_a_source_clip_cannot_inherit_the_bench_hold_or_an_older_clip_loop() {
        val studio = PrismMotionStudioState()
        val sustain = Snip(floatArrayOf(.2f, -.2f), 1, 44_100)
        studio.rememberPlayback(HeldPreviewPlayback(sustain, 0 until sustain.frameCount))
        assertEquals(0..1, studio.loopFor(sustain))
        val source = Snip(floatArrayOf(.2f, -.2f), 1, 44_100)
        assertNull("same format and samples do not make this the rendered HOLD", studio.loopFor(source))
        studio.rememberPlayback(HeldPreviewPlayback(source))
        assertNull(studio.loopFor(source))
        assertNull("the old clip's loop is obsolete after a finite comparison", studio.loopFor(sustain))
    }
}
