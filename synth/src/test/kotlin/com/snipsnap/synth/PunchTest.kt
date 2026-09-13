package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [Punch] — U3 of `docs/SYNTH_UPGRADE.md`: transient shaping + saturation +
 * loudness-targeted normalise, proven standalone here before THUMP wires it
 * in behind the PUNCH macro.
 */
class PunchTest {

    private fun tone(seconds: Float = 0.1f, amp: Float = 0.5f, hz: Double = 200.0): FloatArray {
        val n = (seconds * Dsp.RATE).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / Dsp.RATE)).toFloat() }
    }

    @Test
    fun `amount 0 leaves the buffer untouched`() {
        val buf = tone()
        val original = buf.copyOf()
        Punch.apply(buf, 0f)
        assertTrue(original.contentEquals(buf), "amount 0 must be a no-op")
    }

    @Test
    fun `never produces non-finite samples at full amount`() {
        val buf = tone(amp = 0.95f)
        Punch.apply(buf, 1f)
        assertTrue(buf.all { it.isFinite() }, "every sample must stay finite")
    }

    @Test
    fun `emphasizes an attack relative to its own decay`() {
        // A short percussive hit - fast onset, smooth exponential decay -
        // the actual shape every THUMP voice has, not an artificial hard
        // step (which leaves the slow follower stranded above a
        // discontinuous drop and gets boosted right along with it).
        // Crest factor (peak over RMS) should rise once PUNCH pulls the
        // onset up relative to a loudness-matched whole.
        val n = (0.05f * Dsp.RATE).toInt()
        val t60 = 0.01f
        fun hit() = FloatArray(n) { i ->
            val t = i.toFloat() / Dsp.RATE
            (0.9f * Dsp.envAt(t, t60) * sin(2.0 * PI * 1000.0 * t)).toFloat()
        }
        fun crestFactor(buf: FloatArray): Float {
            val peak = buf.maxOf { abs(it) }
            val rms = kotlin.math.sqrt(buf.sumOf { (it * it).toDouble() } / buf.size).toFloat()
            return peak / rms
        }
        val before = hit()
        val after = hit()
        Punch.apply(after, 1f)
        assertTrue(
            crestFactor(after) > crestFactor(before),
            "PUNCH should widen the gap between the onset and the decay: ${crestFactor(before)} -> ${crestFactor(after)}",
        )
    }

    @Test
    fun `roughly preserves loudness - it reshapes, it does not just get louder`() {
        val buf = tone(seconds = 0.2f, amp = 0.6f)
        val loudBefore = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = Dsp.RATE))
        Punch.apply(buf, 1f)
        val loudAfter = Loudness.of(Snip(buf, channels = 1, sampleRate = Dsp.RATE))
        assertTrue(
            abs(loudAfter - loudBefore) < loudBefore * 0.15f,
            "should stay close to the pre-PUNCH loudness: $loudBefore -> $loudAfter",
        )
    }
}
