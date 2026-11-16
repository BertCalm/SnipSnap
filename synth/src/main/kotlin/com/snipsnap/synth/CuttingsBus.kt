package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import kotlin.math.*

/** Offline whole-mix compression from the accepted CUTTINGS second audition.
 * This belongs after summing the groove: printing it into individual hits cannot
 * preserve the shared detector's pumping. No live MPC effect is implied. */
object CuttingsBus {
    const val VERSION = 2
    enum class Mode(val threshold: Double, val ratio: Double, val attack: Double, val release: Double) {
        GLUE(-24.0, 4.5, .0004, .090), SQUEEZE(-34.0, 12.0, .0001, .055)
    }
    val DEFAULT = Mode.GLUE

    /** Same frame positions and duration as input; 2 ms noncausal peak lookahead.
     * Returns independently owned, equally RMS-levelled comparison buffers. */
    fun comparisons(input: FloatArray): Map<String, FloatArray> {
        require(input.all { it.isFinite() && abs(it) <= 1f })
        if (input.isEmpty()) return listOf("finished", "glue", "squeeze").associateWith { FloatArray(0) }
        val detector = DoubleArray(input.size)
        // Monotonic deque keeps the centred 177-sample peak window linear-time.
        val queue = IntArray(input.size); var head = 0; var tail = 0; var next = 0
        for (i in input.indices) {
            while (next <= min(input.lastIndex, i + 88)) {
                while (tail > head && abs(input[queue[tail - 1]]) <= abs(input[next])) tail--
                queue[tail++] = next++
            }
            while (head < tail && queue[head] < i - 88) head++
            detector[i] = abs(input[queue[head]]).toDouble()
        }
        val results = linkedMapOf("finished" to input.map { it.toDouble() }.toDoubleArray())
        for (mode in Mode.entries) {
            val attack = exp(-1 / (WavWriter.MPC_SAMPLE_RATE * mode.attack)); val release = exp(-1 / (WavWriter.MPC_SAMPLE_RATE * mode.release))
            var envelope = 0.0
            results[mode.name.lowercase()] = DoubleArray(input.size) { i ->
                val coefficient = if (detector[i] > envelope) attack else release
                envelope = coefficient * envelope + (1 - coefficient) * detector[i]
                val over = 20 * log10(max(envelope, 1e-9)) - mode.threshold
                val reduction = when {
                    over < -3 -> 0.0
                    over > 3 -> over * (1 - 1 / mode.ratio)
                    else -> (over + 3).pow(2) / 12 * (1 - 1 / mode.ratio)
                }
                input[i] * 10.0.pow(-reduction / 20)
            }
        }
        fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
        var target = rms(results.getValue("finished"))
        for (x in results.values) {
            val peak = x.maxOf { abs(it) }
            if (peak > 0) target = min(target, .88 * rms(x) / peak)
        }
        return results.mapValues { (_, x) ->
            val level = rms(x); val gain = if (level == 0.0) 0.0 else target / level
            FloatArray(x.size) { (x[it] * gain).toFloat() }
        }
    }
}
