package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2, `sineGain`. */
class GlintSineGainTest {

    private fun pathFor(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): GlintPath {
        val m = Glint.defaults(voice) + macros
        return GlintPath.of(voice, m, Glint.frequencyFor(voice, m.getValue("TUNE")))
    }

    private fun assertWithinDb(expected: Double, actual: Float, dB: Double, what: String) {
        val off = abs(20.0 * log10(actual / expected))
        assertTrue(off <= dB, "$what: $actual against $expected is ${"%.2f".format(java.util.Locale.ROOT, off)} dB off (bar $dB)")
    }

    @Test
    fun `SWEEP and VOWEL agree with the burst RMS the listening probe measured`() {
        // Round 2's probe measured rB, the RMS of the fully rounded burst pair over a breathing pad:
        // SWEEP 0.139636 and VOWEL 0.134822. The sine that matches it is sqrt(2) * rB. The engine's
        // number is stationary at the breath's centre, so it may sit a fraction of a dB off a breath
        // average (measured: SWEEP 0.0 dB, VOWEL 0.5 dB below).
        assertWithinDb(0.139636 * sqrt(2.0), pathFor(GlintVoice.SWEEP).sineGain(), 1.0, "SWEEP")
        assertWithinDb(0.134822 * sqrt(2.0), pathFor(GlintVoice.VOWEL).sineGain(), 1.0, "VOWEL")
    }

    @Test
    fun `it equals an independent, finer integration of the same burst pair`() {
        for (voice in GlintVoice.entries) {
            val path = pathFor(voice)
            val k = FloatArray(2)
            path.breathRatios(0f, k)
            val n = 16384
            var sum = 0.0
            for (i in 0 until n) {
                val p = (i + 0.5) / n
                val v = GlintShape.analyticWindow(p, 1.0) *
                    (sin(2.0 * PI * k[0] * p) + path.level2 * sin(2.0 * PI * k[1] * p))
                sum += v * v
            }
            val expected = sqrt(2.0 * sum / n)
            assertEquals(expected, path.sineGain().toDouble(), expected * 1e-3, "$voice")
        }
    }

    @Test
    fun `with no second burst it is the square root of the window's power`() {
        // BODY 0 leaves one burst. For a high k the sine's mean square is a half, so rB squared is half of
        // the integral of w1 squared (0.03617), and the matching sine's amplitude is sqrt(0.03617) = 0.1902.
        val gain = pathFor(GlintVoice.SWEEP, mapOf("BODY" to 0f, "PEAK" to 1f)).sineGain()
        assertEquals(0.1902f, gain, 0.1902f * 0.02f)
    }

    @Test
    fun `it is finite and sensible at both ends of PEAK, TUNE and BODY on every voice`() {
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0f, 1f)) {
                for (tune in listOf(0f, 1f)) {
                    for (body in listOf(0f, 1f)) {
                        val g = pathFor(voice, mapOf("PEAK" to peak, "TUNE" to tune, "BODY" to body)).sineGain()
                        assertTrue(g.isFinite() && g in 0.05f..0.6f, "$voice PEAK $peak TUNE $tune BODY $body: $g")
                    }
                }
            }
        }
    }
}
