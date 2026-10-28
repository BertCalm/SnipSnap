package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The finite UPRIGHT voice of PLUCK: released string displacement, a tuned
 * lossy delay, and a one-way wooden-body readout. Ported from the owner's
 * approved upright-python-ks-1 listening proof (2026-10-23). Its constants
 * are heard design choices, not measurements of an acoustic upright.
 *
 * The body does not feed the string. There is no fingerboard collision,
 * moving-finger solver, slap, bow or sustaining drive. The fractional delay
 * compensates the loop low-pass and interpolation phase at the fundamental.
 * Interpolation and the loss filter are convex, and feedback is below one,
 * so the unforced string cannot exceed its initialized displacement peak.
 *
 * Why it is built this way: one string keeps the note clear; the same
 * string drives the body; damping changes the string's actual loss; touch
 * changes its initial displacement spectrum rather than adding a click.
 */
object UprightBass {
    const val ROOT_MIDI = 28
    const val TUNE_SEMITONES = 24
    const val DEFAULT_TUNE = 5f / 24f
    const val MODEL_VERSION = 1
    const val SECONDS = 3.2f

    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CACHE_ENTRIES = 8
    private const val CEILING = .99f
    private val specs = listOf(
        MacroSpec("TUNE", DEFAULT_TUNE), MacroSpec("TOUCH", .5f),
        MacroSpec("DAMP", .18f), MacroSpec("WOOD", .55f),
    )

    fun macrosFor(): List<MacroSpec> = specs

    fun defaults(): Map<String, Float> = specs.associate { it.name to it.default }

    /** Finite values clamp; non-finite and missing values use the default. */
    fun settled(macros: Map<String, Float>): Map<String, Float> = specs.associate { spec ->
        spec.name to (macros[spec.name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: spec.default)
    }

    fun frequencyFor(tune: Float): Float {
        val t = if (tune.isFinite()) tune.coerceIn(0f, 1f) else DEFAULT_TUNE
        val midi = ROOT_MIDI + (t * TUNE_SEMITONES).roundToInt()
        return (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
    }

    private data class Key(val tune: Float, val touch: Float, val damp: Float, val wood: Float)
    private data class Reference(val audio: FloatArray, val gain: Double)
    private val references = object : LinkedHashMap<Key, Reference>(CACHE_ENTRIES, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Reference>?): Boolean = size > CACHE_ENTRIES
    }

    /**
     * The reference excitation at velocity one selects the house loudness
     * gain. Every softer excitation uses that same gain, so normalization
     * cannot turn quiet playing into full-strength playing. The bounded
     * cache owns at most eight 3.2 s reference notes, about 4.31 MiB of PCM.
     * Callers always receive their own array.
     */
    fun render(macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros)
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else 1f
        if (v == 0f) return Snip(FloatArray((SECONDS * Dsp.RATE).roundToInt()), 1, Dsp.RATE)
        // TUNE is a note selector. Nearby knob positions that choose the
        // same semitone must share a reference, rather than rebuilding PCM.
        val noteTune = (m.getValue("TUNE") * TUNE_SEMITONES).roundToInt().toFloat() / TUNE_SEMITONES
        val key = Key(noteTune, m.getValue("TOUCH"), m.getValue("DAMP"), m.getValue("WOOD"))
        val reference = synchronized(references) {
            references[key] ?: run {
                val audio = condition(raw(frequencyFor(key.tune), m, INTERNAL_RATE, 1f))
                val before = peak(audio)
                Dsp.levelTo(audio, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET, CEILING)
                val gain = if (before > 1e-12) peak(audio) / before else 1.0
                Reference(audio, gain).also { references[key] = it }
            }
        }
        val out = if (v == 1f) reference.audio.copyOf() else {
            condition(raw(frequencyFor(key.tune), m, INTERNAL_RATE, v)).also { audio ->
                for (i in audio.indices) audio[i] = (audio[i] * reference.gain).toFloat()
                // A final output ceiling is independent of the string's
                // stability. It never sits in the feedback loop.
                Dsp.limitPeak(audio, CEILING)
            }
        }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    /** Raw oversampled model, also used by the PLUCK rate-comparison path. */
    fun raw(frequency: Float, macros: Map<String, Float>, rate: Int, velocity: Float = 1f): FloatArray =
        synthesize(frequency, settled(macros), rate, velocity, 1.0).audio

    internal data class Diagnostics(
        val feedbackGain: Double,
        val lossCoefficient: Double,
        val fractionalDelay: Double,
        val initialPeak: Double,
        val unforcedPeak: Double,
    )

    internal data class RawResult(val audio: FloatArray, val diagnostics: Diagnostics)

    /** The multiplier exists only for the energy test's unstable counterexample. */
    internal fun rawForTest(
        frequency: Float, macros: Map<String, Float>, rate: Int = INTERNAL_RATE,
        velocity: Float = 1f, feedbackMultiplier: Double = 1.0,
    ): RawResult = synthesize(frequency, settled(macros), rate, velocity, feedbackMultiplier)

    private fun synthesize(
        frequency: Float, m: Map<String, Float>, rate: Int, velocity: Float,
        feedbackMultiplier: Double,
    ): RawResult {
        require(frequency.isFinite() && frequency in 20f..1_000f) { "UPRIGHT frequency must be finite in 20..1000 Hz" }
        require(rate >= 22_050) { "UPRIGHT rate must be at least 22050 Hz" }
        require(feedbackMultiplier.isFinite() && feedbackMultiplier > 0) { "invalid feedback multiplier" }
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f).toDouble() else 1.0
        val hz = frequency.toDouble()
        val touch = m.getValue("TOUCH").toDouble()
        val damp = m.getValue("DAMP").toDouble()
        val wood = m.getValue("WOOD").toDouble()
        val t60 = 3.5 * .19.pow(damp)
        val lossHz = 3_600.0 * .24.pow(damp)
        val alpha = 1.0 - exp(-2 * PI * lossHz / rate)
        val (whole, frac) = tunedDelay(hz, rate, alpha)
        val gain = exp(-ln(1000.0) / (hz * t60)) * feedbackMultiplier
        val size = whole + 3
        val line = DoubleArray(size)
        val cutoff = 180.0 * 18.0.pow(touch) * (.25 + 1.8 * v * v)
        val partials = min(96, (10_000 / hz).toInt())
        for (harmonic in 1..partials) {
            var weight = sin(harmonic * PI * .18) * sin(harmonic * PI * .11) / (harmonic.toDouble() * harmonic)
            weight *= exp(-((harmonic * hz) / cutoff).pow(2))
            val omega = 2 * PI * harmonic / (whole + frac)
            for (i in line.indices) line[i] += weight * cos(omega * i)
        }
        // This is a bounded perturbation of the initial displacement. The
        // seed deliberately excludes velocity, TOUCH, DAMP and WOOD, so an
        // A/B changes its stated model quantity, not its random identity.
        val midi = (69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt()
        val noise = Dsp.Noise(Dsp.seedFor("UPRIGHT", MODEL_VERSION, midi, "finger-shape"))
        val smoother = Biquad.lowPass(min(cutoff, rate * .2), rate)
        val texture = DoubleArray(size) { smoother.process(noise.next().toDouble()) }
        val noiseMean = texture.average()
        for (i in line.indices) line[i] += (texture[i] - noiseMean) * .018 * touch * v
        val mean = line.average()
        val amplitude = .65 * v.pow(.85)
        for (i in line.indices) line[i] = (line[i] - mean) * amplitude
        val initialPeak = line.maxOf { abs(it) }
        val audio = FloatArray((SECONDS.toDouble() * rate).roundToInt())
        var write = 0
        var low = 0.0
        var unforcedPeak = 0.0
        val attackFrames = max(4, ((.0045 - .0025 * touch) * rate).toInt())
        val bodyScale = 1.15 - .3 * wood
        val body = arrayOf(
            Biquad.bandPass(130.0 * bodyScale, 1.2, rate),
            Biquad.bandPass(360.0 * bodyScale, 2.0, rate),
            Biquad.bandPass(1_050.0 * bodyScale, 2.8, rate),
        )
        for (i in audio.indices) {
            var index = write - whole
            if (index < 0) index += size
            val previous = if (index == 0) size - 1 else index - 1
            val delayed = (1 - frac) * line[index] + frac * line[previous]
            low += alpha * (delayed - low)
            val y = gain * low
            line[write] = y
            unforcedPeak = max(unforcedPeak, abs(y))
            write++
            if (write == size) write = 0
            val string = if (i < attackFrames) y * (.5 - .5 * cos(PI * i / (attackFrames - 1))) else y
            val response = .20 * body[0].process(string) + .18 * body[1].process(string) + .12 * body[2].process(string)
            audio[i] = (string + wood * response).toFloat()
        }
        return RawResult(audio, Diagnostics(gain, alpha, frac, initialPeak, unforcedPeak))
    }

    /** Includes both the loop-filter phase and the actual linear-interpolation phase. */
    private fun tunedDelay(hz: Double, rate: Int, alpha: Double): Pair<Int, Double> {
        val omega = 2 * PI * hz / rate
        val a = 1 - alpha
        val phase = -atan2(a * sin(omega), 1 - a * cos(omega))
        val desired = rate / hz + phase / omega
        val whole = floor(desired).toInt()
        var lo = 0.0
        var hi = 1.0
        repeat(44) {
            val frac = (lo + hi) * .5
            val interpolationPhase = atan2(-frac * sin(omega), 1 - frac + frac * cos(omega))
            val lag = whole - interpolationPhase / omega
            if (lag < desired) lo = frac else hi = frac
        }
        return whole to (lo + hi) * .5
    }

    private fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - mean
            out[i] = x - dc.lp(x)
        }
        Dsp.fadeTail(out)
        return out
    }

    private fun peak(audio: FloatArray): Double = audio.maxOfOrNull { abs(it.toDouble()) } ?: 0.0

    /** Double state keeps the low body resonances well-conditioned at 176.4 kHz. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            /** Butterworth second-order low-pass, matching the prototype's contact smoother. */
            fun lowPass(hz: Double, rate: Int): Biquad {
                val omega = 2 * PI * hz / rate
                val c = cos(omega)
                val alpha = sin(omega) / sqrt(2.0)
                val a0 = 1 + alpha
                return Biquad((1 - c) / (2 * a0), (1 - c) / a0, (1 - c) / (2 * a0), -2 * c / a0, (1 - alpha) / a0)
            }

            /** Unity-peak digital bandpass: the prototype's one-way iirpeak readout. */
            fun bandPass(hz: Double, q: Double, rate: Int): Biquad {
                val omega = 2 * PI * hz / rate
                val beta = tan(omega / (2 * q))
                val gain = 1 / (1 + beta)
                return Biquad(1 - gain, 0.0, gain - 1, -2 * gain * cos(omega), 2 * gain - 1)
            }
        }
    }
}
