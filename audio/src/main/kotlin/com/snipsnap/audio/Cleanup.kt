package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A snip's worth of audio: interleaved float samples plus its format.
 */
data class Snip(
    val samples: FloatArray,
    val channels: Int,
    val sampleRate: Int,
) {
    init {
        require(channels in 1..2) { "channels must be 1 or 2: $channels" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(samples.size % channels == 0) { "sample count is not a whole number of frames" }
    }

    val frameCount: Int get() = samples.size / channels
    val durationSeconds: Float get() = frameCount.toFloat() / sampleRate

    /** Loudest absolute sample, 0f for silence. */
    fun peak(): Float {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        return peak
    }

    // Data class with an array member: identity semantics would be surprising,
    // and content equality on megabytes of audio would be worse. Compare format
    // and length only, which is what tests and caches actually want.
    override fun equals(other: Any?): Boolean =
        other is Snip &&
            channels == other.channels &&
            sampleRate == other.sampleRate &&
            samples.size == other.samples.size

    override fun hashCode(): Int =
        (channels * 31 + sampleRate) * 31 + samples.size
}

/**
 * How aggressively to clean a snip on commit.
 *
 * The defaults are the ones the app applies automatically; the trim editor sets
 * the boundaries, this makes what's inside them behave on a pad.
 */
data class CleanupConfig(
    /** Anything quieter than this at the head/tail is dead air. */
    val silenceThresholdDb: Float = -60f,
    /** Frames of quiet kept before the first real sample, so transients survive. */
    val preRollFrames: Int = 64,
    /** Peak-normalise to this level. -0.3 dBFS leaves headroom against inter-sample peaks. */
    val targetPeakDb: Float = -0.3f,
    /** Click guard at the head. */
    val fadeInMs: Float = 2f,
    /** Click guard at the tail — the one that actually matters on a truncated one-shot. */
    val fadeOutMs: Float = 5f,
    val removeDcOffset: Boolean = true,
    val trimSilence: Boolean = true,
    val normalize: Boolean = true,
)

/**
 * The commit-time cleanup chain.
 *
 * Order matters and is not arbitrary: DC offset first (it biases every
 * subsequent measurement), then trim (so normalisation isn't computed against
 * dead air), then normalise, then fades last (so a fade isn't scaled away).
 */
object Cleanup {

    fun process(snip: Snip, config: CleanupConfig = CleanupConfig()): Snip {
        var out = snip
        if (config.removeDcOffset) out = removeDcOffset(out)
        if (config.trimSilence) out = trimSilence(out, config.silenceThresholdDb, config.preRollFrames)
        if (config.normalize) out = normalize(out, config.targetPeakDb)
        out = applyFades(out, config.fadeInMs, config.fadeOutMs)
        return out
    }

    /**
     * Subtract each channel's mean.
     *
     * Captured audio picks up DC bias surprisingly often, and it eats headroom
     * and clicks on pad triggers without being audible on its own.
     *
     * Assumes its input is audio — content that oscillates around some bias, so
     * that the mean *is* the bias. Given a non-oscillating signal (a constant
     * level, say) the mean is the signal, and subtracting it lifts the silent
     * regions off zero, which then defeats [trimSilence] downstream. Real
     * captures don't look like that; synthetic test material can.
     */
    fun removeDcOffset(snip: Snip): Snip {
        if (snip.frameCount == 0) return snip
        val out = snip.samples.copyOf()

        for (ch in 0 until snip.channels) {
            var sum = 0.0
            var i = ch
            while (i < out.size) {
                sum += out[i]
                i += snip.channels
            }
            val mean = (sum / snip.frameCount).toFloat()
            if (mean == 0f) continue

            i = ch
            while (i < out.size) {
                out[i] -= mean
                i += snip.channels
            }
        }
        return snip.copy(samples = out)
    }

    /**
     * Drop leading and trailing near-silence, keeping [preRollFrames] of run-up.
     *
     * The pre-roll is not cosmetic: cutting exactly at the first sample above
     * threshold shaves the attack off a transient, which is the difference
     * between a kick and a click.
     */
    fun trimSilence(snip: Snip, thresholdDb: Float, preRollFrames: Int): Snip {
        if (snip.frameCount == 0) return snip
        val threshold = dbToLinear(thresholdDb)

        val first = firstFrameAbove(snip, threshold)
        if (first < 0) return snip.copy(samples = FloatArray(0)) // all silence

        val last = lastFrameAbove(snip, threshold)

        val start = max(0, first - preRollFrames)
        val end = min(snip.frameCount - 1, last)
        if (start == 0 && end == snip.frameCount - 1) return snip

        val frames = end - start + 1
        val out = FloatArray(frames * snip.channels)
        System.arraycopy(snip.samples, start * snip.channels, out, 0, out.size)
        return snip.copy(samples = out)
    }

    /** Scale so the loudest sample sits at [targetPeakDb]. Silence is left alone. */
    fun normalize(snip: Snip, targetPeakDb: Float): Snip {
        val peak = snip.peak()
        if (peak <= 0f) return snip

        val target = dbToLinear(targetPeakDb)
        val gain = target / peak
        if (gain == 1f) return snip

        val out = FloatArray(snip.samples.size)
        for (i in snip.samples.indices) {
            out[i] = (snip.samples[i] * gain).coerceIn(-1f, 1f)
        }
        return snip.copy(samples = out)
    }

    /**
     * Linear fades at both ends.
     *
     * Every sample cut mid-waveform clicks on trigger. Five milliseconds at the
     * tail is inaudible on a drum hit and removes the click entirely.
     */
    fun applyFades(snip: Snip, fadeInMs: Float, fadeOutMs: Float): Snip {
        if (snip.frameCount == 0) return snip

        val fadeInFrames = min(msToFrames(fadeInMs, snip.sampleRate), snip.frameCount)
        val fadeOutFrames = min(msToFrames(fadeOutMs, snip.sampleRate), snip.frameCount)
        if (fadeInFrames == 0 && fadeOutFrames == 0) return snip

        val out = snip.samples.copyOf()

        for (f in 0 until fadeInFrames) {
            val gain = (f + 1).toFloat() / fadeInFrames
            for (ch in 0 until snip.channels) out[f * snip.channels + ch] *= gain
        }

        for (f in 0 until fadeOutFrames) {
            val frame = snip.frameCount - 1 - f
            val gain = (f + 1).toFloat() / fadeOutFrames
            for (ch in 0 until snip.channels) out[frame * snip.channels + ch] *= gain
        }

        return snip.copy(samples = out)
    }

    /** Average the channels down to mono. Halves kit size when stereo buys nothing. */
    fun toMono(snip: Snip): Snip {
        if (snip.channels == 1) return snip
        val out = FloatArray(snip.frameCount)
        for (f in out.indices) {
            var sum = 0f
            for (ch in 0 until snip.channels) sum += snip.samples[f * snip.channels + ch]
            out[f] = sum / snip.channels
        }
        return Snip(out, channels = 1, sampleRate = snip.sampleRate)
    }

    private fun firstFrameAbove(snip: Snip, threshold: Float): Int {
        for (f in 0 until snip.frameCount) {
            for (ch in 0 until snip.channels) {
                if (abs(snip.samples[f * snip.channels + ch]) > threshold) return f
            }
        }
        return -1
    }

    private fun lastFrameAbove(snip: Snip, threshold: Float): Int {
        for (f in snip.frameCount - 1 downTo 0) {
            for (ch in 0 until snip.channels) {
                if (abs(snip.samples[f * snip.channels + ch]) > threshold) return f
            }
        }
        return -1
    }

    private fun msToFrames(ms: Float, sampleRate: Int): Int =
        max(0, (ms / 1000f * sampleRate).toInt())

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(linear)
}
