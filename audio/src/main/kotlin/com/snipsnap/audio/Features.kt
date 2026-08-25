package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * What a snip looks like to the classifier.
 *
 * Deliberately a small set of cheap, interpretable numbers rather than a
 * learned embedding: this has to run instantly on a phone, and when a hit is
 * misfiled the user should be able to see why.
 */
data class Features(
    /** Spectral centre of mass, Hz. Kicks sit low, hats sit high. */
    val centroidHz: Float,
    /** Frequency below which 85% of the energy lives, Hz. */
    val rolloffHz: Float,
    /** Geometric over arithmetic mean of the spectrum, 0..1. High = noise-like. */
    val flatness: Float,
    /** Sign changes per sample, 0..1. Cheap noise/brightness proxy. */
    val zeroCrossingRate: Float,
    /** Share of energy below 200 Hz. */
    val lowRatio: Float,
    /** Share of energy between 200 Hz and 2 kHz. */
    val midRatio: Float,
    /** Share of energy above 2 kHz. */
    val highRatio: Float,
    /** Whole-snip length, seconds. */
    val durationSeconds: Float,
    /** Time from the peak down to -20 dB, milliseconds. */
    val decayMs: Float,
    val peak: Float,
    /**
     * Distinct attacks in the head of the snip ([Classifier.attackBurstCount]).
     * A clap is physically several impacts a few ms apart; one clean attack
     * is one hit. Carried here so a feature vector alone can be classified —
     * the teach-the-machine log stores features, never audio.
     */
    val attackBursts: Int = 1,
)

object FeatureExtractor {

    private const val FFT_SIZE = 4096
    private const val EPSILON = 1e-10f

    /**
     * Measure [snip].
     *
     * The spectrum is taken from the head of the snip — where a drum hit's
     * identity lives. Measuring the whole thing would average the body and tail
     * in and blur a kick into a tom.
     */
    fun extract(snip: Snip): Features {
        val mono = if (snip.channels == 1) snip.samples else Cleanup.toMono(snip).samples
        if (mono.isEmpty()) return silentFeatures()

        val head = mono.copyOfRange(0, min(mono.size, FFT_SIZE))
        val spectrum = Fft.magnitudeSpectrum(head, FFT_SIZE)

        var total = 0f
        for (m in spectrum) total += m

        if (total <= EPSILON) return silentFeatures(durationSeconds = snip.durationSeconds)

        var weighted = 0f
        var power = 0f
        var low = 0f
        var mid = 0f
        var high = 0f
        var logSum = 0.0

        for (bin in spectrum.indices) {
            val hz = Fft.binToHz(bin, FFT_SIZE, snip.sampleRate)
            val m = spectrum[bin]
            // Centroid is power-weighted (m²), deliberately: magnitude
            // weighting lets a -63 dB noise floor outvote a dominant sub
            // fundamental — a 12-bit-crunched 40 Hz kick measured identical
            // to a clean 150 Hz tom. Power weighting reports where the
            // energy actually lives. Band ratios stay magnitude-based.
            weighted += hz * m * m
            power += m * m
            when {
                hz < 200f -> low += m
                hz < 2000f -> mid += m
                else -> high += m
            }
            logSum += ln((m + EPSILON).toDouble())
        }

        val centroid = if (power > EPSILON) weighted / power else 0f

        var cumulative = 0f
        var rolloffBin = spectrum.size - 1
        val rolloffTarget = total * 0.85f
        for (bin in spectrum.indices) {
            cumulative += spectrum[bin]
            if (cumulative >= rolloffTarget) {
                rolloffBin = bin
                break
            }
        }

        val geometricMean = exp(logSum / spectrum.size).toFloat()
        val arithmeticMean = total / spectrum.size
        val flatness = (geometricMean / (arithmeticMean + EPSILON)).coerceIn(0f, 1f)

        return Features(
            centroidHz = centroid,
            rolloffHz = Fft.binToHz(rolloffBin, FFT_SIZE, snip.sampleRate),
            flatness = flatness,
            zeroCrossingRate = zeroCrossingRate(mono),
            lowRatio = low / total,
            midRatio = mid / total,
            highRatio = high / total,
            durationSeconds = snip.durationSeconds,
            decayMs = decayMs(mono, snip.sampleRate),
            peak = snip.peak(),
            attackBursts = Classifier.attackBurstCount(snip),
        )
    }

    private fun silentFeatures(durationSeconds: Float = 0f) = Features(
        centroidHz = 0f,
        rolloffHz = 0f,
        flatness = 0f,
        zeroCrossingRate = 0f,
        lowRatio = 0f,
        midRatio = 0f,
        highRatio = 0f,
        durationSeconds = durationSeconds,
        decayMs = 0f,
        peak = 0f,
    )

    private fun zeroCrossingRate(mono: FloatArray): Float {
        if (mono.size < 2) return 0f
        var crossings = 0
        for (i in 1 until mono.size) {
            if ((mono[i - 1] < 0f) != (mono[i] < 0f)) crossings++
        }
        return crossings.toFloat() / (mono.size - 1)
    }

    /**
     * Time from the peak until the envelope falls 20 dB.
     *
     * This is what separates a closed hat from an open one, and a kick from a
     * sustained bass note, more reliably than raw duration — trailing silence
     * inflates duration but not decay.
     */
    private fun decayMs(mono: FloatArray, sampleRate: Int): Float {
        if (mono.isEmpty()) return 0f

        val envelope = envelope(mono, sampleRate)
        if (envelope.isEmpty()) return 0f

        var peak = 0f
        for (value in envelope) if (value > peak) peak = value
        if (peak <= 0f) return 0f

        // The *first* hop that reaches the top, not whichever hop happens to
        // hold the single largest sample. Sustained material has near-identical
        // peaks throughout, so the argmax can land near the end — and measuring
        // decay from there reports almost none, turning a held bass note into a
        // kick.
        var peakIndex = 0
        val nearPeak = peak * 0.99f
        for (i in envelope.indices) {
            if (envelope[i] >= nearPeak) {
                peakIndex = i
                break
            }
        }

        val target = peak * 0.1f // -20 dB
        for (i in peakIndex until envelope.size) {
            if (envelope[i] <= target) {
                val frames = (i - peakIndex) * ENVELOPE_HOP
                return frames * 1000f / sampleRate
            }
        }
        // Never decayed — report the whole tail, which reads as "sustained".
        return (envelope.size - peakIndex) * ENVELOPE_HOP * 1000f / sampleRate
    }

    private const val ENVELOPE_HOP = 256

    /**
     * Envelope window, in frames. At 44.1 kHz this is ~23 ms.
     *
     * It must span at least one period of the lowest frequency we care about.
     * A shorter window tracks the waveform instead of its amplitude: a 55 Hz
     * tone has an 18 ms period, so a 1.5 ms window sees it fall to zero twice
     * per cycle and reports a sustained note as decaying in 12 ms — which reads
     * as a kick.
     */
    private const val ENVELOPE_WINDOW = 1024

    /** Peak amplitude per overlapping window. */
    private fun envelope(mono: FloatArray, @Suppress("UNUSED_PARAMETER") sampleRate: Int): FloatArray {
        val count = max(1, mono.size / ENVELOPE_HOP)
        val out = FloatArray(count)
        for (i in 0 until count) {
            var peak = 0f
            val start = i * ENVELOPE_HOP
            val end = min(mono.size, start + ENVELOPE_WINDOW)
            for (f in start until end) {
                val a = abs(mono[f])
                if (a > peak) peak = a
            }
            out[i] = peak
        }
        return out
    }
}
