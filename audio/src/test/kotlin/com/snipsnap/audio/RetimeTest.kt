package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetimeTest {

    private val rate = 44_100

    @Test
    fun `tempo changes, pitch does not, and the kick still punches`() {
        // Kicks over a 440 Hz bed - both halves of the hybrid have work.
        val n = 2 * rate
        val src = FloatArray(n) { i -> (0.15 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat() }
        val hitAt = listOf(0.1f, 0.6f, 1.1f, 1.6f)
        for (at in hitAt) {
            val start = (at * rate).toInt()
            // Kick for the punch, hat on top for a crisp broadband attack
            // (a bare sub swell over a tone bed is thin food for the
            // onset detector even before any retiming).
            for ((hit, gain) in listOf(DrumSynth.kick() to 0.8f, DrumSynth.closedHat() to 0.5f)) {
                for (i in hit.samples.indices) {
                    val idx = start + i
                    if (idx < n) src[idx] += hit.samples[i] * gain
                }
            }
        }
        val snip = Snip(src, 1, rate)
        val ratio = 1.2f
        val out = Retime.retime(snip, ratio, seed = 3)
        assertEquals((n * ratio.toDouble()).toInt(), out.frameCount, "the duration is the ratio's")

        // Pitch kept: the bed still sings 440, not 440/1.2.
        val at440 = CaptureDoctor.goertzel(out.samples, out.frameCount, 440f, rate)
        val atRepitched = CaptureDoctor.goertzel(out.samples, out.frameCount, 440f / ratio, rate)
        assertTrue(at440 > 5 * atRepitched, "no chipmunk, no drag: $at440 vs $atRepitched")

        // The hits land at their new times.
        val onsets = Transients.detect(out).map { it.frame.toFloat() / rate }
        for (at in hitAt) {
            val want = at * ratio
            assertTrue(
                onsets.any { Math.abs(it - want) < 0.04f },
                "the hit at ${at}s moved to ${want}s: heard $onsets",
            )
        }

        // And they still punch: attack rise stays in OLA territory, not
        // smeared across a vocoder frame.
        fun riseSec(s: FloatArray, aroundSec: Float): Float {
            val win = (0.001f * rate).toInt()
            val from = ((aroundSec - 0.03f) * rate).toInt().coerceAtLeast(0)
            val to = ((aroundSec + 0.06f) * rate).toInt().coerceAtMost(s.size)
            var peak = 0f
            for (i in from until to step win) {
                var acc = 0.0
                for (j in i until minOf(i + win, to)) acc += s[j] * s[j].toDouble()
                val r = Math.sqrt(acc / win).toFloat()
                if (r > peak) peak = r
            }
            var t10 = -1f
            var t90 = -1f
            for (i in from until to step win) {
                var acc = 0.0
                for (j in i until minOf(i + win, to)) acc += s[j] * s[j].toDouble()
                val r = Math.sqrt(acc / win).toFloat()
                if (t10 < 0 && r > 0.1f * peak) t10 = i.toFloat() / rate
                if (t90 < 0 && r > 0.9f * peak) t90 = i.toFloat() / rate
            }
            return t90 - t10
        }
        // The attack stays an attack - PGHI's phases don't smear it (the
        // measurement that retired the planned OLA hybrid, whose
        // unaligned sum smeared this same rise to 51 ms).
        val riseOrig = riseSec(src, 0.1f)
        val riseOut = riseSec(out.samples, 0.12f)
        assertTrue(
            riseOut < riseOrig + 0.012f,
            "the attack stays an attack: rise ${riseOrig}s -> ${riseOut}s",
        )

        assertFailsWith<IllegalArgumentException> { Retime.retime(snip, 3f, 1) }
        assertFailsWith<IllegalArgumentException> { Retime.retime(snip, 0.2f, 1) }
    }
}
