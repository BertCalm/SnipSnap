package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spec §2.4 and §4.7: nothing is built to hold the level steady across DEPTH, because the engine's own
 * levelling (`Dsp.levelTo` on the one-shot) already absorbs the roughly 10 dB the rounded window costs.
 * What it cannot absorb is the 0.99 peak ceiling, which depends on crest factor, not scale, so the final
 * loudness still moves. This is a regression guard on that, not a promise.
 *
 * Measured on the engine (every voice, DECAY 0, 0.5 and 1, DEPTH 0.25 to 1 against DEPTH 0): nothing at
 * DECAY 1; at most 2.2 dB at DECAY 0.5; at DECAY 0, STEP 5.5 dB, BRASS 3.7, SWEEP 3.7, VOWEL 3.0, all at
 * DEPTH 1. Every departure beyond 2 dB is DEPTH 1 being the louder end. At DECAY 0 the 0.99 ceiling binds
 * on every render. How hard depends on the render's peak-to-loudness ratio (peak over `Loudness.of` on the
 * enveloped note, from a diagnostic since deleted). That ratio is 8.7 to 11.0 at DEPTH 0, which holds the
 * render 4.2 to 6.2 dB under the loudness target. The middle DEPTHs sit up to 1.6 dB lower still. The bare
 * sine at DEPTH 1 is about 6 and lands 0.7 to 1.2 dB under. So DEPTH 1 ends up to 5.5 dB nearer the target
 * than DEPTH 0. The bar of 6 dB is the measured worst plus half a dB.
 */
class GlintDepthLoudnessTest {

    private fun dB(snip: Snip): Double = 20.0 * log10(Loudness.of(snip).toDouble())

    @Test
    fun `the engine's levelling keeps every DEPTH within 6 dB of DEPTH 0`() {
        val lines = mutableListOf<String>()
        var worst = 0.0
        for (voice in GlintVoice.entries) {
            for (decay in listOf(0f, 0.5f, 1f)) {
                val base = dB(Glint.render(voice, mapOf("DECAY" to decay)))
                val deltas = listOf(0.25f, 0.5f, 0.75f, 0.9f, 1f).map { depth ->
                    depth to dB(Glint.render(voice, mapOf("DECAY" to decay, "DEPTH" to depth))) - base
                }
                lines += "GLINT DEPTH loudness $voice DECAY $decay, dB against DEPTH 0: " +
                    deltas.joinToString(", ") { (d, v) -> "$d: ${"%+.2f".format(java.util.Locale.ROOT, v)}" }
                worst = maxOf(worst, deltas.maxOf { abs(it.second) })
            }
        }
        lines.forEach(::println)
        println("GLINT DEPTH loudness: worst departure from DEPTH 0 is ${"%.2f".format(java.util.Locale.ROOT, worst)} dB (bar $LIMIT_DB)")
        assertTrue(worst <= LIMIT_DB, "DEPTH moved the final loudness by $worst dB against DEPTH 0 (bar $LIMIT_DB dB)")
    }

    private companion object {
        const val LIMIT_DB = 6.0
    }
}
