package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Macro corners must be safe as raw PCM as well as after the production loudness pass. */
class BellowsBoundsTest {
    @Test
    fun `source headroom bounds low and high macro corners across the supported register and hold policy`() {
        for (voice in BellowsVoice.entries) for (hold in listOf(0f, .98f, 1f)) {
            for (tune in listOf(0f, 1f)) for (corner in listOf(0f, 1f)) {
                val macros = Bellows.defaults(voice).mapValues { (key, _) -> when (key) {
                    "TUNE" -> tune
                    "HOLD" -> hold
                    else -> corner
                } }
                val label = "$voice HOLD $hold TUNE $tune corner $corner"
                val raw = Bellows.renderRaw(voice, macros).samples
                assertTrue(raw.isNotEmpty(), "$label has no samples")
                assertTrue(raw.all { it.isFinite() }, "$label has nonfinite source PCM")
                val peak = raw.maxOf { abs(it) }
                assertTrue(peak > 0f && peak < 1f, "$label raw source peak is $peak")
                if (!Bellows.isLoop(hold)) {
                    assertTrue(raw.takeLast(256).all { it == 0f }, "$label source stores failed to release")
                }
            }
        }
    }

    @Test
    fun `the production matcher stays bounded at the hottest source corners`() {
        for (voice in BellowsVoice.entries) for (hold in listOf(.98f, 1f)) {
            val macros = Bellows.defaults(voice).mapValues { (key, _) -> when (key) {
                "TUNE" -> 1f
                "HOLD" -> hold
                else -> 1f
            } }
            val rendered = Bellows.render(voice, macros).samples
            assertTrue(rendered.all { it.isFinite() && abs(it) <= .99001f }, "$voice HOLD $hold exceeds production PCM ceiling")
            assertTrue(rendered.any { abs(it) > .01f }, "$voice HOLD $hold lost positive-velocity source energy")
        }
    }
}
