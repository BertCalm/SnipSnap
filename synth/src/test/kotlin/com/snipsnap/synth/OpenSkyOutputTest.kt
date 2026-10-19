package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenSkyOutputTest {
    @Test
    fun `valid strong wet settings receive uniform headroom without clipping or changing stereo shape`() {
        val patch = OpenSkyPresets.all().single { it.name == "Blue Hour" }.copy(
            macros = OpenSky.defaults(OpenSkyVoice.SOAR).mapValues { 1f },
            midi = 108, gateSeconds = 3f,
        )
        val raw = OpenSky.render(patch.voice, patch.macros, patch.midi, patch.velocity,
            patch.gateSeconds, patch.wet, timbre = patch.timbre)
        assertTrue(raw.peak() > 1f, "fixture no longer exposes the wet-summation clipping risk")
        val playable = patch.render()
        assertTrue(playable.peak() <= OpenSkyPatch.OUTPUT_PEAK + 1e-6f)
        val gain = playable.peak() / raw.peak()
        assertTrue(gain in 0f..1f)
        assertTrue(raw.samples.indices.all { abs(playable.samples[it] - raw.samples[it] * gain) < 1e-6f },
            "headroom changed the waveform, stereo balance or decay rather than applying one gain")
        assertTrue(patch.copy(velocity = 0f).render().samples.all { it == 0f })
    }
}
