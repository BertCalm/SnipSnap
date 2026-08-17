package com.snipsnap.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A detected hit: where it starts, and how strongly it announced itself. */
data class Onset(
    /** Sample frame within the snip. */
    val frame: Int,
    /** Novelty value in dB — how big the jump in energy was. Useful for ranking. */
    val strength: Float,
)

/**
 * Onset detection for chopping a captured loop into pads.
 *
 * Energy-based rather than spectral: percussive material announces itself as a
 * sharp rise in short-time energy, and a log-domain energy derivative catches
 * that without needing an FFT. That matters on a phone, where this runs on a
 * captured bar the moment the user taps chop.
 *
 * The consequence is that it finds *hits*, not *notes* — a swelling pad or a
 * legato line won't be sliced, which for a drum sampler is the right bias.
 */
object Transients {

    data class Config(
        /** Analysis hop. 256 frames @ 44.1 kHz ≈ 5.8 ms — fine enough for hi-hats. */
        val hopFrames: Int = 256,
        /** Analysis window; overlapping windows smooth the envelope. */
        val windowFrames: Int = 1024,
        /** Minimum gap between hits. Below this they're the same hit's decay. */
        val minSliceMs: Float = 30f,
        /** Novelty must exceed the local median by this multiple. */
        val thresholdFactor: Float = 1.6f,
        /** ...and by at least this many dB, so quiet passages don't self-trigger. */
        val thresholdFloorDb: Float = 3f,
        /** Frames quieter than this can't start a hit. */
        val silenceFloorDb: Float = -55f,
        /**
         * Nudge each cut slightly earlier.
         *
         * Detection necessarily lags the true attack by up to a hop, and cutting
         * even fractionally late shaves the transient — the difference between a
         * kick and a click.
         */
        val backoffFrames: Int = 128,
        /** Width of the running median, in analysis frames. Odd. */
        val medianWindow: Int = 17,
    ) {
        init {
            require(hopFrames > 0) { "hopFrames must be positive" }
            require(windowFrames >= hopFrames) { "windowFrames must be >= hopFrames" }
            require(minSliceMs >= 0f) { "minSliceMs must not be negative" }
            require(medianWindow >= 1) { "medianWindow must be positive" }
            require(backoffFrames >= 0) { "backoffFrames must not be negative" }
        }
    }

    private const val FLOOR_DB = -120f

    /**
     * Find the hits in [snip], in time order.
     *
     * Returns an empty list for silence or for material with no clear attacks.
     */
    fun detect(snip: Snip, config: Config = Config()): List<Onset> {
        if (snip.frameCount < config.windowFrames) return emptyList()

        val energyDb = shortTimeEnergyDb(snip, config)
        if (energyDb.size < 3) return emptyList()

        // Half-wave rectified derivative: energy rising is a hit starting,
        // energy falling is just a decay and carries no onset information.
        val novelty = FloatArray(energyDb.size)
        for (i in 1 until energyDb.size) {
            novelty[i] = max(0f, energyDb[i] - energyDb[i - 1])
        }

        val threshold = adaptiveThreshold(novelty, config)
        val minGapFrames = (config.minSliceMs / 1000f * snip.sampleRate).toInt()

        val onsets = mutableListOf<Onset>()
        // -1 means "no onset yet". Not Int.MIN_VALUE: `frame - lastFrame` would
        // overflow to a negative number and the gap check would reject the very
        // first onset of every snip.
        var lastFrame = -1

        for (i in 1 until novelty.size - 1) {
            val value = novelty[i]
            if (value < threshold[i]) continue
            // Local maximum, so one attack yields one onset rather than a burst.
            if (value < novelty[i - 1] || value < novelty[i + 1]) continue
            if (energyDb[i] < config.silenceFloorDb) continue

            val frame = refineAttack(snip, i, config)
            if (lastFrame >= 0 && frame - lastFrame < minGapFrames) continue

            onsets += Onset(frame, value)
            lastFrame = frame
        }

        return onsets
    }

    /**
     * Narrow an onset from an analysis frame down to the actual attack.
     *
     * A window is credited at its start, so the window that first *contains* an
     * attack can begin up to [Config.windowFrames] before it — around 23 ms,
     * which is a lot of dead air at the head of a slice. Scan that region for
     * where the waveform genuinely takes off, then back off deliberately.
     */
    private fun refineAttack(snip: Snip, analysisIndex: Int, config: Config): Int {
        val from = analysisIndex * config.hopFrames
        val to = min(snip.frameCount, from + config.windowFrames + config.hopFrames)
        if (to <= from) return max(0, from - config.backoffFrames)

        var peak = 0f
        for (f in from until to) {
            for (ch in 0 until snip.channels) {
                val a = kotlin.math.abs(snip.samples[f * snip.channels + ch])
                if (a > peak) peak = a
            }
        }
        if (peak <= 0f) return max(0, from - config.backoffFrames)

        val trigger = peak * ATTACK_FRACTION
        for (f in from until to) {
            for (ch in 0 until snip.channels) {
                if (kotlin.math.abs(snip.samples[f * snip.channels + ch]) >= trigger) {
                    return max(0, f - config.backoffFrames)
                }
            }
        }
        return max(0, from - config.backoffFrames)
    }

    /** Fraction of a hit's local peak that counts as "the attack has started". */
    private const val ATTACK_FRACTION = 0.1f

    /** RMS per analysis frame, in dB. */
    internal fun shortTimeEnergyDb(snip: Snip, config: Config): FloatArray {
        val frames = snip.frameCount
        val count = (frames - config.windowFrames) / config.hopFrames + 1
        if (count <= 0) return FloatArray(0)

        val out = FloatArray(count)
        for (i in 0 until count) {
            val start = i * config.hopFrames
            var sum = 0.0
            for (f in start until start + config.windowFrames) {
                // Analyse the channel sum; a hit in either channel is a hit.
                var mixed = 0f
                for (ch in 0 until snip.channels) mixed += snip.samples[f * snip.channels + ch]
                mixed /= snip.channels
                sum += (mixed * mixed).toDouble()
            }
            val rms = sqrt(sum / config.windowFrames).toFloat()
            out[i] = if (rms <= 0f) FLOOR_DB else max(FLOOR_DB, 20f * log10(rms))
        }
        return out
    }

    /**
     * Threshold that follows the material.
     *
     * A fixed threshold either misses hits in a quiet passage or shreds a loud
     * one. Tracking a running median means "louder than what's around it",
     * which is what a hit actually is.
     */
    internal fun adaptiveThreshold(novelty: FloatArray, config: Config): FloatArray {
        val half = config.medianWindow / 2
        val out = FloatArray(novelty.size)
        val scratch = FloatArray(config.medianWindow)

        for (i in novelty.indices) {
            val from = max(0, i - half)
            val to = min(novelty.size - 1, i + half)
            val n = to - from + 1
            System.arraycopy(novelty, from, scratch, 0, n)
            java.util.Arrays.sort(scratch, 0, n)
            val median = scratch[n / 2]
            out[i] = max(median * config.thresholdFactor, config.thresholdFloorDb)
        }
        return out
    }

    /**
     * The strongest [count] onsets, back in time order.
     *
     * For "chop this bar onto 16 pads" — take the most convincing hits rather
     * than the first N, which would just be the start of the loop.
     */
    fun detectStrongest(snip: Snip, count: Int, config: Config = Config()): List<Onset> {
        require(count >= 0) { "count must not be negative: $count" }
        val all = detect(snip, config)
        if (all.size <= count) return all
        return all.sortedByDescending { it.strength }
            .take(count)
            .sortedBy { it.frame }
    }

    /** Nearest zero crossing at or before [frame]; falls back to [frame]. */
    fun zeroCrossingBefore(snip: Snip, frame: Int, searchFrames: Int = 128): Int {
        if (frame <= 0 || snip.channels < 1) return max(0, frame)
        val from = max(1, frame - searchFrames)
        for (f in frame downTo from) {
            val prev = snip.samples[(f - 1) * snip.channels]
            val cur = snip.samples[f * snip.channels]
            if (prev == 0f) return f - 1
            if ((prev < 0f) != (cur < 0f)) return f
        }
        return frame
    }
}
