package com.snipsnap.audio

import kotlin.math.sqrt

/**
 * Perceived level of a snip, as one comparable number.
 *
 * Peak says nothing about loudness — a clicky hat peaks like a kick and
 * sounds a third as loud. This measures the RMS of the loudest 200 ms
 * window after a low cut (sub bass reads quieter than it meters), which
 * tracks "how loud does this hit feel" well enough to balance a kit, at a
 * fraction of a real LUFS meter's complexity.
 */
object Loudness {

    private const val WINDOW_SECONDS = 0.2f
    private const val HOP_SECONDS = 0.05f
    private const val LOW_CUT_HZ = 120f

    fun of(snip: Snip): Float = of(snip.samples, snip.channels, snip.sampleRate)

    /**
     * [of] over raw interleaved [samples], so a caller holding a bare
     * buffer (a synth voice mid-render, say) need not copy it into a
     * [Snip] just to be measured. Non-finite samples read as silence:
     * one NaN used to poison the low-cut's state and make the whole
     * measurement NaN, which downstream gain math then spread across
     * an entire render.
     */
    fun of(samples: FloatArray, channels: Int, sampleRate: Int): Float {
        require(channels in 1..2) { "channels must be 1 or 2: $channels" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        val mono = if (channels == 1) samples else Cleanup.toMono(Snip(samples, channels, sampleRate)).samples
        if (mono.isEmpty()) return 0f

        // Gentle low cut: subtract a one-pole low-pass.
        val filtered = FloatArray(mono.size)
        var lp = 0f
        val a = (1.0 - Math.exp(-2.0 * Math.PI * LOW_CUT_HZ / sampleRate)).toFloat()
        for (i in mono.indices) {
            val x = if (mono[i].isFinite()) mono[i] else 0f
            lp += a * (x - lp)
            filtered[i] = x - lp * 0.7f // keep some body; a full cut over-dims kicks
        }

        val window = (WINDOW_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val hop = (HOP_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        var best = 0.0
        var start = 0
        while (start < filtered.size) {
            val end = minOf(filtered.size, start + window)
            var sum = 0.0
            for (i in start until end) sum += filtered[i].toDouble() * filtered[i]
            val rms = sum / (end - start)
            if (rms > best) best = rms
            if (end == filtered.size) break
            start += hop
        }
        return sqrt(best).toFloat()
    }
}
