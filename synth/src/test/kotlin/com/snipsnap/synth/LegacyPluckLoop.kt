package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PLUCK's Karplus-Strong loop exactly as it stood at two points, both frozen
 * here as separate references SILK Phase 1a never gets to touch again:
 *
 * - [ks] is the loop before SILK Phase 1a moved it into [Strings]
 *   (docs/superpowers/plans/2026-09-27-silk-phase-1a.md) - four voices, no
 *   stiffness, no jawari.
 * - [ksWithSitar] is PLUCK Phase 3a's loop (the SITAR voice: stiffness,
 *   jawari, and their own terms in the tuning budget) as it stood the moment
 *   that work and Phase 1a's extraction were merged - the two were built in
 *   parallel against the same original [ks], neither aware of the other,
 *   and [ksWithSitar] is what Phase 3a's own `Pluck.ks` computed before this
 *   merge folded stiffness and jawari into [Strings] too.
 *
 * [StringsTest] holds [Strings.pluck] to both, sample for sample, so a
 * later change to the shared loop has to prove it still reproduces this -
 * SITAR's own measured constants (StiffnessTest and the rest of PluckTest's
 * SITAR-specific tests) are the second, independent proof that nothing
 * about the sound moved.
 */
internal object LegacyPluckLoop {
    private const val MIN_LOOP_SAMPLES = 2.0

    fun ks(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
        rate: Int,
        position: Float = 0f,
    ): FloatArray {
        val loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f)
        val fb = Dsp.lin(1f - damp, 0.94f, 0.998f)

        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w

        val exact = (rate / freq) - filterDelay - 0.5
        require(exact >= MIN_LOOP_SAMPLES) {
            "Pluck loop length ($exact samples, freq=$freq Hz at rate=$rate) fell " +
                "below the Karplus-Strong minimum of $MIN_LOOP_SAMPLES samples - a " +
                "note this high (or a filter delay this large) needs either a lower " +
                "root, a narrower TUNE_SEMITONES span, or this allpass revisited; " +
                "coercing the loop length up here without also correcting the " +
                "fractional remainder used to produce an unconditionally unstable " +
                "feedback allpass."
        }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f

        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(n + 2))

        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        val combDelay = if (position > 0f) (position * rate / freq).roundToInt().coerceIn(1, n) else 0
        val excLen = min(n + combDelay, out.size)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (combDelay > 0 && i - combDelay in 0 until n) burst[i - combDelay] else 0f
            out[i] = x - xd
        }

        val loopLp = Dsp.OnePole(rate)
        for (i in n + 1 until out.size) {
            val d = 0.5f * (out[i - n] + out[i - n - 1])
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            out[i] += fb * loopLp.lp(tuned, loopHz)
        }
        return out
    }

    /**
     * PLUCK Phase 3a's loop, frozen: SITAR's stiffness allpass and jawari
     * bridge limiter, each with its own term in the tuning budget, copied
     * verbatim from `Pluck.ks` as it stood at the point this file's own
     * merge conflict was resolved. Never edit this body.
     */
    fun ksWithSitar(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
        rate: Int,
        position: Float = 0f,
        stiffness: Float = 0f,
        jawari: Float = 0f,
    ): FloatArray {
        require(stiffness > -1f && stiffness <= 0f) { "stiffness must be in (-1, 0], got $stiffness" }
        require(jawari in 0f..0.95f) { "jawari drive must be in [0, 0.95], got $jawari" }
        val loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f)
        val fb = Dsp.lin(1f - damp, 0.94f, 0.998f)

        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w

        val stiffDelay = if (stiffness != 0f) {
            val c = stiffness.toDouble()
            val phase = atan2(-sin(w), c + cos(w)) - atan2(-c * sin(w), 1.0 + c * cos(w))
            -phase / w
        } else 0.0

        val dcA = (1.0 - exp(-2.0 * PI * 2.0 / rate)).toFloat()
        val dcDelay = if (jawari > 0f) {
            val r = 1.0 - dcA
            val phase = atan2(sin(w), 1.0 - cos(w)) - atan2(r * sin(w), 1.0 - r * cos(w))
            -phase / w
        } else 0.0
        val exact = (rate / freq) - filterDelay - stiffDelay - dcDelay - 0.5
        require(exact >= MIN_LOOP_SAMPLES) {
            "Pluck loop length ($exact samples, freq=$freq Hz at rate=$rate) fell " +
                "below the Karplus-Strong minimum of $MIN_LOOP_SAMPLES samples - a " +
                "note this high (or a filter delay this large) needs either a lower " +
                "root, a narrower TUNE_SEMITONES span, or this allpass revisited; " +
                "coercing the loop length up here without also correcting the " +
                "fractional remainder used to produce an unconditionally unstable " +
                "feedback allpass."
        }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f
        var stX1 = 0f
        var stY1 = 0f

        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(n + 2))

        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        var p0 = 1e-6f
        for (v in burst) if (kotlin.math.abs(v) > p0) p0 = kotlin.math.abs(v)
        var dc = 0f

        val combDelay = if (position > 0f) (position * rate / freq).roundToInt().coerceIn(1, n) else 0
        val excLen = min(n + combDelay, out.size)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (combDelay > 0 && i - combDelay in 0 until n) burst[i - combDelay] else 0f
            out[i] = x - xd
        }

        val loopLp = Dsp.OnePole(rate)
        for (i in n + 1 until out.size) {
            val d = 0.5f * (out[i - n] + out[i - n - 1])
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            val stiff = if (stiffness != 0f) {
                val s = stiffness * (tuned - stY1) + stX1
                stX1 = tuned
                stY1 = s
                s
            } else tuned
            var y = loopLp.lp(stiff, loopHz)
            if (jawari > 0f) {
                if (y > 0f) y -= jawari * min(y, p0) * y / p0
                dc += dcA * (y - dc)
                y -= dc
            }
            out[i] += fb * y
        }
        return out
    }
}
