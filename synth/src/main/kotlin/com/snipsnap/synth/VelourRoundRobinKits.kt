package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Listening candidates derived from the approved printed hits, not independent recordings.
 * Take one is untouched. Alternate contact envelopes and spectral balances keep the pitch
 * and timing fixed. Changes fade out over the first 240 ms so late board echoes remain intact.
 * Round 6 has owner listening approval. This remains opt-in while MPC grid export
 * validation is open; it does not replace the existing factory shelf.
 */
object VelourRoundRobinKits {
    const val VERSION = 1
    const val TAKES = 4

    fun build(treatment: VelourApprovedKits.Treatment): List<ArrangedPad> =
        VelourApprovedKits.build(treatment).map { pad ->
            val grid = (pad.softVariants + pad.snip).map { original ->
                List(TAKES) { take -> variation(original, take) }
            }
            pad.copy(snip = grid.last().first(), softVariants = emptyList(), layerNames = emptyList(),
                velocityTakes = grid, source = pad.source + mapOf(
                    "roundRobin" to "4 sample-derived candidates per velocity",
                    "roundRobinVersion" to VERSION.toString(),
                    "approval" to "round6 four-take audition owner-approved"))
        }

    internal fun variation(original: Snip, take: Int): Snip {
        require(take in 0 until TAKES)
        if (take == 0) return original.copy(samples = original.samples.copyOf())
        require(original.channels == 1)
        val x = original.samples
        val y = x.copyOf()
        // Opposing contact/body contours avoid a gain-only copy. No resampling, pitch
        // shift, random tail or added delay: those would alter the approved identity.
        val tilt = doubleArrayOf(0.0, -.16, .13, -.06)[take]
        val contact = doubleArrayOf(0.0, .12, -.10, -.16)[take]
        val body = doubleArrayOf(0.0, -.08, .09, .13)[take]
        val a = 1.0 - exp(-2 * PI * 1800 / original.sampleRate)
        var low = 0.0
        for (i in x.indices) {
            val t = i.toDouble() / original.sampleRate
            low += a * (x[i] - low)
            val window = if (t < .24) .5 * (1 + cos(PI * t / .24)) else 0.0
            val envelope = contact * exp(-t / .008) + body * (t / .035) * exp(1 - t / .035)
            y[i] = (x[i] * (1 + window * envelope) + window * tilt * (x[i] - low)).toFloat()
        }
        // Preserve level without modifying late echoes: calibrate only the changed
        // region, leaving everything from 240 ms onward sample-identical.
        val n = minOf(x.size, ceil(.24 * original.sampleRate).toInt())
        val before = (0 until n).sumOf { x[it].toDouble().pow(2) }
        val after = (0 until n).sumOf { y[it].toDouble().pow(2) }
        // A smooth correction avoids a hard gain boundary at the preserved tail.
        val correction = if (after > 0) sqrt(before / after) - 1 else 0.0
        for (i in 0 until n) {
            val window = .5 * (1 + cos(PI * i / (.24 * original.sampleRate)))
            y[i] = (y[i] * (1 + correction * window)).toFloat()
        }
        val peak = y.maxOf { abs(it) }
        if (peak > .95f) {
            // Scale the difference, not the reference, to keep the tail untouched.
            var blend = 1.0f
            for (i in y.indices) {
                val d = y[i] - x[i]
                if (abs(y[i]) > .95f && d != 0f)
                    blend = minOf(blend, ((if (y[i] > 0) .95f else -.95f) - x[i]) / d)
            }
            for (i in y.indices) y[i] = x[i] + blend.coerceIn(0f, 1f) * (y[i] - x[i])
        }
        y[y.lastIndex] = 0f
        return original.copy(samples = y)
    }
}
