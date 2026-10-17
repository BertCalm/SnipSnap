package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conservative time-domain pitch-synchronous overlap/add candidate, not a general vocal model.
 * Source epochs retain their local waveform (and therefore its spectral envelope); a different
 * output pulse clock changes pitch without varispeeding the whole phrase. Local epoch dilation
 * supplies a separate, small formant-color adjustment. Unvoiced material follows the source's
 * chronology without the pitched pulse train. This is deliberately restricted to modest shifts.
 * No oscillator, learned voice, phoneme labeling, or generated breath enters the signal path.
 */
internal object ConfluenceVoice {
    private const val RATE = Confluence.RATE
    private const val ANALYSIS_DIVISOR = 4
    private const val HOP = 64
    private const val WINDOW = 512

    class Analysis(val samples: FloatArray, val periods: FloatArray, val voiced: FloatArray, val epochs: IntArray) {
        fun periodAt(frame: Double): Double = interpolate(periods, frame / (HOP * ANALYSIS_DIVISOR)).toDouble()
        fun voicedAt(frame: Double): Float = interpolate(voiced, frame / (HOP * ANALYSIS_DIVISOR))
        val voicedShare: Float get() = voiced.average().toFloat()
    }

    fun analyze(samples: FloatArray, referenceMidi: Float?): Analysis {
        val small = FloatArray((samples.size + 3) / 4) { i ->
            var sum = 0f; var n = 0
            for (j in i * 4 until minOf(i * 4 + 4, samples.size)) { sum += samples[j]; n++ }
            sum / n
        }
        val frames = (small.size + HOP - 1) / HOP + 1
        val periods = FloatArray(frames)
        val voiced = FloatArray(frames)
        var preceding = if (referenceMidi != null) RATE / (440.0 * 2.0.pow((referenceMidi - 69) / 12.0)) / 4 else 80.0
        var globalPeak = 0f
        for (v in small) globalPeak = max(globalPeak, abs(v))
        val x = DoubleArray(WINDOW)
        for (frame in 0 until frames) {
            val center = frame * HOP
            var mean = 0.0
            for (i in x.indices) { x[i] = small.getOrElse(center + i - WINDOW / 2) { 0f }.toDouble(); mean += x[i] }
            mean /= WINDOW
            var power = 0.0
            for (i in x.indices) { x[i] -= mean; power += x[i] * x[i] }
            var bestLag = preceding.roundToInt().coerceIn(18, 169)
            var bestScore = -1.0
            var bestCorrelation = 0.0
            val correlation = DoubleArray(171)
            for (lag in 18..169) {
                var xy = 0.0; var xx = 0.0; var yy = 0.0
                for (i in 0 until WINDOW - lag) { xy += x[i] * x[i + lag]; xx += x[i] * x[i]; yy += x[i + lag] * x[i + lag] }
                correlation[lag] = xy / sqrt(xx * yy + 1e-24)
            }
            // Compare local maxima rather than choosing the sloping edge of an octave candidate.
            for (lag in 19..168) {
                val c = correlation[lag]
                if (c < correlation[lag - 1] || c < correlation[lag + 1]) continue
                val continuityCost = .055 * abs(ln(lag / preceding))
                val score = c - continuityCost
                if (score > bestScore) { bestScore = score; bestLag = lag; bestCorrelation = c }
            }
            val left = correlation[bestLag - 1]; val peak = correlation[bestLag]; val right = correlation[bestLag + 1]
            val denominator = left - 2 * peak + right
            val delta = if (abs(denominator) > 1e-10) (.5 * (left - right) / denominator).coerceIn(-.5, .5) else 0.0
            val detected = bestLag + delta
            val rms = sqrt(power / WINDOW)
            val confidence = if (rms < max(1e-6, globalPeak * .012)) 0.0 else ((bestCorrelation - .48) / .35).coerceIn(0.0, 1.0)
            // Abrupt source energy changes belong to the original attack, not a harmonized pulse.
            var before = 0.0; var after = 0.0
            for (i in WINDOW / 2 - 48 until WINDOW / 2) before += x[i] * x[i]
            for (i in WINDOW / 2 until WINDOW / 2 + 48) after += x[i] * x[i]
            val onset = after > 4 * before && after > power * .08
            voiced[frame] = if (onset) 0f else confidence.toFloat()
            if (confidence > .45) preceding = detected
            periods[frame] = (preceding * 4).toFloat()
        }
        // A short low-pass avoids switching between pitched and unvoiced paths at a frame boundary.
        val smooth = FloatArray(frames) { i ->
            var sum = 0f; var weight = 0f
            for (j in -2..2) { val k = (i + j).coerceIn(0, frames - 1); val w = (3 - abs(j)).toFloat(); sum += voiced[k] * w; weight += w }
            sum / weight
        }
        val provisional = Analysis(samples, periods, smooth, IntArray(0))
        val epochs = ArrayList<Int>()
        var position = 0.0
        while (position < samples.size) {
            val period = provisional.periodAt(position).coerceIn(72.0, 680.0)
            val radius = (period * .22).roundToInt()
            val from = max(0, position.roundToInt() - radius)
            val to = minOf(samples.lastIndex, position.roundToInt() + radius)
            var winner = from
            for (i in from..to) if (samples[i] > samples[winner]) winner = i
            if (epochs.isEmpty() || winner > epochs.last() + period * .35) epochs += winner
            position = max(position + period * .6, winner + period)
        }
        return Analysis(samples, periods, smooth, epochs.toIntArray())
    }

    /** Whole-region transformation. Pitch and formant changes are inactive in TEXTURE mode. */
    fun render(
        analysis: Analysis,
        mode: ConfluenceSourceMode,
        pitchSemitones: Float,
        durationRatio: Float,
        formantScale: Float,
        vibratoCents: Float,
        vibratoHz: Float,
        vibratoPhase: Float,
    ): FloatArray {
        require(abs(pitchSemitones) <= 5f) { "prototype pitched transformation is limited to five semitones" }
        require(durationRatio in .94f..1.06f && formantScale in .9f..1.1f) { "prototype vocal transformation exceeds conservative bounds" }
        val source = analysis.samples
        val length = (source.size * durationRatio).roundToInt()
        val original = FloatArray(length) { i -> read(source, i / durationRatio.toDouble()) }
        if (mode == ConfluenceSourceMode.TEXTURE) return edge(original)
        val out = FloatArray(length)
        val weight = FloatArray(length)
        val pitchRatio = 2.0.pow(pitchSemitones / 12.0)
        var at = 0.0
        var epochIndex = 0
        while (at < length + 700) {
            val input = at / durationRatio
            while (epochIndex + 1 < analysis.epochs.size && abs(analysis.epochs[epochIndex + 1] - input) < abs(analysis.epochs[epochIndex] - input)) epochIndex++
            val center = analysis.epochs.getOrElse(epochIndex) { input.roundToInt() }
            val period = analysis.periodAt(input).coerceIn(72.0, 680.0)
            val radius = ceil(period / formantScale).toInt()
            for (offset in -radius..radius) {
                val destination = at.roundToInt() + offset
                if (destination !in out.indices) continue
                val w = (.5 + .5 * cos(PI * offset / radius)).toFloat()
                val sample = read(source, center + offset * formantScale.toDouble())
                out[destination] += sample * w
                weight[destination] += w
            }
            val t = at / RATE
            val vibrato = vibratoCents * (1 - exp(-max(0.0, t - .10) * 5)) * sin(2 * PI * (vibratoHz * t + vibratoPhase))
            at += period / (pitchRatio * 2.0.pow(vibrato / 1200.0))
        }
        for (i in out.indices) {
            val blend = analysis.voicedAt(i / durationRatio.toDouble())
            val pitched = if (weight[i] > 1e-4f) out[i] / weight[i] else original[i]
            out[i] = blend * pitched + (1 - blend) * original[i]
        }
        return edge(out)
    }

    private fun edge(samples: FloatArray): FloatArray {
        // Only a 5 ms safety edge, never a replacement for phrase selection or a sustain marker.
        val frames = minOf(220, samples.size / 4)
        for (i in 0 until frames) {
            val gain = (.5 - .5 * cos(PI * (i + 1) / frames)).toFloat()
            samples[i] *= gain; samples[samples.lastIndex - i] *= gain
        }
        return samples
    }

    private fun interpolate(values: FloatArray, at: Double): Float {
        val i = at.toInt().coerceIn(0, values.lastIndex)
        val j = minOf(i + 1, values.lastIndex)
        val fraction = (at - i).coerceIn(0.0, 1.0).toFloat()
        return values[i] + (values[j] - values[i]) * fraction
    }

    private fun read(samples: FloatArray, at: Double): Float {
        if (at < 0 || at >= samples.lastIndex) return if (at == samples.lastIndex.toDouble()) samples.last() else 0f
        val i = at.toInt(); val f = (at - i).toFloat()
        return samples[i] + f * (samples[i + 1] - samples[i])
    }
}
