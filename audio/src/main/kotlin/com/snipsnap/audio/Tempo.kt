package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A tempo estimate and how much to trust it. */
data class TempoEstimate(
    val bpm: Float,
    /** 0..1. Below ~0.3, treat the material as non-rhythmic and don't label it. */
    val confidence: Float,
) {
    /** For filenames: `92bpm`. Rounded — nobody browses fractional BPM. */
    val label: String get() = "${bpm.roundToInt()}bpm"
}

/**
 * Tempo estimation for loop-length snips.
 *
 * Autocorrelation of the onset-strength curve: percussive material pulses,
 * and the lag at which the pulse best lines up with itself is the beat
 * period. No FFT, no beat grid — enough to write `92bpm` into a filename,
 * which is how producers actually browse loops.
 *
 * Estimates are folded into 70–180 BPM. Half/double-time ambiguity is
 * inherent to autocorrelation (a 4-on-the-floor 90 BPM loop correlates at
 * 180 too); the fold picks the conventional range rather than guessing feel.
 */
object Tempo {

    private const val MIN_BPM = 70f
    private const val MAX_BPM = 180f

    /** Anything shorter can't show two beats of the slowest tempo we detect. */
    private const val MIN_SECONDS = 1.8f

    /** Fewer hits than this and "tempo" would be numerology. */
    private const val MIN_ONSETS = 4

    fun estimate(snip: Snip, config: Transients.Config = Transients.Config()): TempoEstimate? {
        if (snip.durationSeconds < MIN_SECONDS) return null

        // Tempo needs beats. Without real onsets, autocorrelation happily
        // finds "rhythm" in measurement ripple — a pure drone once scored 0.82
        // confident at 159 BPM off nothing but window/period beating.
        if (Transients.detect(snip, config).size < MIN_ONSETS) return null

        val energyDb = Transients.shortTimeEnergyDb(snip, config)
        if (energyDb.size < 8) return null

        // Onset strength: half-wave rectified energy rise, same curve the
        // onset detector peaks on.
        val novelty = FloatArray(energyDb.size)
        for (i in 1 until energyDb.size) {
            novelty[i] = max(0f, energyDb[i] - energyDb[i - 1])
        }

        var sum = 0f
        for (v in novelty) sum += v
        if (sum <= 1e-6f) return null // silence has no tempo

        val mean = sum / novelty.size
        for (i in novelty.indices) novelty[i] -= mean

        val framesPerSecond = snip.sampleRate.toFloat() / config.hopFrames
        val minLag = (framesPerSecond * 60f / MAX_BPM).toInt().coerceAtLeast(2)
        val maxLag = (framesPerSecond * 60f / MIN_BPM).toInt()
            .coerceAtMost(novelty.size / 2)
        if (maxLag <= minLag) return null

        // Autocorrelation with first-harmonic support: the true beat period
        // also correlates at twice itself, which off-beat lags don't.
        val score = FloatArray(maxLag + 1)
        var zeroLag = 0f
        for (v in novelty) zeroLag += v * v
        if (zeroLag <= 1e-9f) return null

        for (lag in minLag..maxLag) {
            var r = 0f
            for (i in 0 until novelty.size - lag) r += novelty[i] * novelty[i + lag]
            score[lag] = r / zeroLag
        }
        for (lag in minLag..maxLag) {
            val doubled = lag * 2
            if (doubled <= maxLag) score[lag] += 0.5f * score[doubled]
        }

        var bestLag = minLag
        for (lag in minLag..maxLag) {
            if (score[lag] > score[bestLag]) bestLag = lag
        }

        // Parabolic refinement between neighbouring lags — one analysis frame
        // of quantisation is ±2 BPM up at 170, which is exactly the error
        // a producer would notice.
        var refined = bestLag.toFloat()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = score[bestLag - 1]
            val b = score[bestLag]
            val c = score[bestLag + 1]
            val denom = a - 2f * b + c
            if (abs(denom) > 1e-9f) refined = bestLag + 0.5f * (a - c) / denom
        }

        var bpm = 60f * framesPerSecond / refined
        while (bpm < MIN_BPM) bpm *= 2f
        while (bpm > MAX_BPM) bpm /= 2f

        // Confidence: how much the winning lag stands out over the average
        // correlation in range. Steady pulses stand way out; tonal drones and
        // speech barely beat the mean.
        var avg = 0f
        for (lag in minLag..maxLag) avg += max(0f, score[lag])
        avg /= (maxLag - minLag + 1)
        val peak = max(0f, score[bestLag])
        val confidence = if (peak <= 1e-9f) 0f else ((peak - avg) / peak).coerceIn(0f, 1f)

        return TempoEstimate(bpm, confidence)
    }

    /** True when the two tempos agree up to the half/double-time fold. */
    fun agree(a: Float, b: Float, toleranceBpm: Float = 3f): Boolean {
        for (factor in floatArrayOf(0.5f, 1f, 2f)) {
            if (abs(a * factor - b) <= toleranceBpm) return true
        }
        return false
    }
}
