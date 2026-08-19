package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a snip appears to be. */
enum class DrumClass {
    KICK,
    SNARE,
    CLAP,
    HAT_CLOSED,
    HAT_OPEN,
    TOM,
    PERC,
    TONAL,
    LOOP,
    UNKNOWN,
}

data class Classification(
    val drumClass: DrumClass,
    /** 0..1. Low means "this is a guess" — the UI should say so rather than hide it. */
    val confidence: Float,
    val features: Features,
)

/**
 * Rule-based classification of a snip into drum families.
 *
 * Rules rather than a learned model, deliberately. It runs instantly, it ships
 * without a model file, and when it puts a rimshot on the snare pad the reason
 * is inspectable instead of mysterious. Auto-placement is a convenience that
 * has to be easy to override — a classifier that is wrong in explainable ways
 * beats one that is wrong in confusing ones.
 *
 * **Thresholds are tuned against synthetic material** (see `DrumSynth` in the
 * tests) and are a starting point, not a finished calibration. They want a pass
 * over real captured hits before shipping.
 */
object Classifier {

    // Bass family
    private const val BASS_DOMINANT_LOW_RATIO = 0.55f
    private const val TONAL_DECAY_MS = 500f
    private const val KICK_MAX_CENTROID_HZ = 100f

    // A kick with a beater click or a hard pitch sweep measures a centroid
    // above 100 Hz while still being unmistakably a kick to the ear. When the
    // low band utterly dominates, stretch the boundary; a tom's tonal centre
    // pulls more energy into the mids, so this doesn't swallow real toms.
    private const val KICK_STRETCH_CENTROID_HZ = 130f
    private const val KICK_STRETCH_MIN_LOW_RATIO = 0.85f

    // Bright family
    private const val HAT_MIN_CENTROID_HZ = 12_000f
    private const val HAT_MIN_HIGH_RATIO = 0.95f
    private const val CLOSED_HAT_MAX_DECAY_MS = 120f
    private const val SNARE_MIN_HIGH_RATIO = 0.5f

    // Anything longer than this is a phrase, not a hit.
    private const val LOOP_MIN_SECONDS = 1.5f

    fun classify(snip: Snip): Classification {
        val features = FeatureExtractor.extract(snip)

        if (features.peak <= 0f || snip.frameCount == 0) {
            return Classification(DrumClass.UNKNOWN, confidence = 0f, features = features)
        }

        // Length decides first: a two-bar phrase is a loop whatever its spectrum
        // says, and its spectrum only describes its first hit anyway.
        if (features.durationSeconds > LOOP_MIN_SECONDS) {
            return Classification(DrumClass.LOOP, confidence = 0.9f, features = features)
        }

        return if (features.lowRatio > BASS_DOMINANT_LOW_RATIO) {
            classifyBass(features)
        } else {
            classifyBright(snip, features)
        }
    }

    private fun classifyBass(features: Features): Classification {
        // Sustains rather than decays: a held note, not a drum.
        if (features.decayMs > TONAL_DECAY_MS) {
            return Classification(DrumClass.TONAL, margin(features.decayMs, TONAL_DECAY_MS, 500f), features)
        }
        val isKick = features.centroidHz < KICK_MAX_CENTROID_HZ ||
            (features.centroidHz < KICK_STRETCH_CENTROID_HZ &&
                features.lowRatio > KICK_STRETCH_MIN_LOW_RATIO)
        if (isKick) {
            return Classification(
                DrumClass.KICK,
                margin(KICK_STRETCH_CENTROID_HZ - features.centroidHz, 0f, 90f),
                features,
            )
        }
        return Classification(DrumClass.TOM, margin(features.lowRatio, BASS_DOMINANT_LOW_RATIO, 0.3f), features)
    }

    private fun classifyBright(snip: Snip, features: Features): Classification {
        // Cymbals: bright and almost entirely high-band.
        if (features.centroidHz > HAT_MIN_CENTROID_HZ && features.highRatio > HAT_MIN_HIGH_RATIO) {
            val closed = features.decayMs < CLOSED_HAT_MAX_DECAY_MS
            return Classification(
                if (closed) DrumClass.HAT_CLOSED else DrumClass.HAT_OPEN,
                margin(features.centroidHz, HAT_MIN_CENTROID_HZ, 4000f),
                features,
            )
        }

        // A clap is several bursts in quick succession, which is what separates
        // it from a snare — the spectra are too similar to split reliably.
        if (attackBurstCount(snip) >= CLAP_MIN_BURSTS) {
            return Classification(DrumClass.CLAP, confidence = 0.7f, features = features)
        }

        if (features.highRatio > SNARE_MIN_HIGH_RATIO) {
            return Classification(
                DrumClass.SNARE,
                margin(features.highRatio, SNARE_MIN_HIGH_RATIO, 0.4f),
                features,
            )
        }

        return Classification(DrumClass.PERC, confidence = 0.4f, features = features)
    }

    private const val CLAP_MIN_BURSTS = 3
    private const val BURST_WINDOW_SECONDS = 0.08f
    private const val BURST_HOP = 64
    private const val BURST_THRESHOLD = 0.4f
    /** Fraction of the trigger level the envelope must fall to before another burst counts. */
    private const val BURST_REARM = 0.6f

    /**
     * Count distinct attacks in the head of a snip.
     *
     * A hand clap is physically several impacts a few milliseconds apart. One
     * clean attack means one hit; three or more means a clap (or a flam, which
     * lands on the same pad often enough that we call it a win).
     */
    internal fun attackBurstCount(snip: Snip): Int {
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        val limit = min(mono.size, (BURST_WINDOW_SECONDS * snip.sampleRate).toInt())
        if (limit < BURST_HOP * 3) return 0

        val points = limit / BURST_HOP
        val envelope = FloatArray(points)
        for (i in 0 until points) {
            var peak = 0f
            for (f in i * BURST_HOP until min(limit, (i + 1) * BURST_HOP)) {
                val a = abs(mono[f])
                if (a > peak) peak = a
            }
            envelope[i] = peak
        }

        val peak = envelope.max()
        if (peak <= 0f) return 0

        // Hysteresis, not peak-picking. A decaying noise burst throws off plenty
        // of local maxima above any fixed threshold; a genuinely separate impact
        // is preceded by the envelope actually falling away first.
        //
        // Deliberately unsmoothed: a clap's dips sit one hop from the next
        // impact, so even a 3-point average lifts them back above the re-arm
        // level and the whole thing reads as one hit. The hysteresis is what
        // rejects jitter here — smoothing would only destroy the evidence.
        val trigger = peak * BURST_THRESHOLD
        val rearm = trigger * BURST_REARM

        var count = 0
        var armed = true
        for (value in envelope) {
            if (armed && value >= trigger) {
                count++
                armed = false
            } else if (!armed && value < rearm) {
                armed = true
            }
        }
        return count
    }

    /** Map how far a value cleared its threshold onto a 0.5..0.95 confidence. */
    private fun margin(value: Float, threshold: Float, fullScale: Float): Float {
        if (fullScale <= 0f) return 0.5f
        val over = ((value - threshold) / fullScale).coerceIn(0f, 1f)
        return 0.5f + 0.45f * over
    }
}
