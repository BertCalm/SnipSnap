package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Event strength must survive the public renderer's loudness conditioning. */
class CorollaVelocityTest {
    @Test
    fun `soft medium and firm events retain audible dynamics in finite notes and held loops`() {
        for (voice in CorollaVoice.entries) for (hold in listOf(0f, 1f)) {
            val macros = mapOf("HOLD" to hold)
            val levels = listOf(.3f, .65f, 1f).map { velocity ->
                val snip = Corolla.render(voice, macros, velocity)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f },
                    "$voice HOLD $hold velocity $velocity produces invalid audio")
                Loudness.of(snip).toDouble().also {
                    assertTrue(it > 1e-5, "$voice HOLD $hold velocity $velocity is silent")
                }
            }
            val softToFirmDb = 20 * log10(levels[2] / levels[0])
            val softToMediumDb = 20 * log10(levels[1] / levels[0])
            val mediumToFirmDb = 20 * log10(levels[2] / levels[1])
            // Allow headroom limiting and different crest factors, while excluding
            // the former same-loudness result at every event velocity.
            assertTrue(softToFirmDb >= 8.0,
                "$voice HOLD $hold erases soft-to-firm dynamics: $softToFirmDb dB, levels $levels")
            assertTrue(softToMediumDb >= 3.0 && mediumToFirmDb >= 2.0,
                "$voice HOLD $hold loses the middle dynamic: $softToMediumDb / $mediumToFirmDb dB")
            println("COROLLA VELOCITY $voice HOLD $hold: $levels, soft-to-firm $softToFirmDb dB")
        }
    }

    @Test
    fun `velocity conditioning preserves silence and the existing nonfinite fallback`() {
        val voice = CorollaVoice.TONGUE
        assertTrue(Corolla.render(voice, mapOf("HOLD" to 1f), velocity = 0f).samples.all { it == 0f },
            "zero velocity becomes audible during loop normalization")
        assertContentEquals(Corolla.render(voice, velocity = 1f).samples,
            Corolla.render(voice, velocity = Float.NaN).samples,
            "nonfinite velocity must use the source's existing full-strength fallback")
    }
}
