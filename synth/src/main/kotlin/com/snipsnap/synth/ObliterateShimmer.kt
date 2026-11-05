package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The edition-005 octave-room pre-stage, ported from Python at 0c00ad0c.
 * It is a standalone offline input effect, with no engine or Android registration.
 *
 * Why it is built this way:
 * - Unity dry retains the strike while a filtered octave room supplies a halo.
 * - Half the tank return traverses the two Hann heads again, so pitch recirculates.
 * - Convex filters/readers and Hadamard row sums bound tank history by .76 per return.
 * - Delay rings retain only the required history instead of entire render timelines.
 * - The 1.5 s tail and wet-only boundary fade preserve the finite listening reference.
 *
 * Double state, SciPy-generated highpass coefficients and integer octave geometry retain
 * the reference equations. This is not pitch-synchronous PSOLA: tuning smear and folded
 * highs remain disclosed. Peak protection only attenuates and is outside the feedback tank.
 */
object ObliterateShimmer {
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    const val TAIL_FRAMES = 66_150
    const val MAX_INPUT_SECONDS = 9
    const val MODEL = "obliterate-shimmer-kotlin-r1"
    const val REFERENCE_MODEL = "obliterate-shimmer-python-r1"
    const val PEAK_CEILING = .98
    const val FEEDBACK = .38
    const val CONTRACTION_BOUND = .76
    private const val WINDOW = 4410
    private const val BASE_DELAY = 512
    private const val HISTORY = WINDOW + BASE_DELAY + 1
    private const val FADE = 441
    private val delays = intArrayOf(1493, 1733, 1999, 2381)
    private val signs = arrayOf(
        intArrayOf(1, 1, 1, 1), intArrayOf(1, -1, 1, -1),
        intArrayOf(1, 1, -1, -1), intArrayOf(1, -1, -1, 1),
    )
    // One immutable table; no audio or mutable filter/tank state survives a call.
    private val weights = DoubleArray(WINDOW) { .5 - .5 * cos(2.0 * PI * (it.toDouble() / WINDOW)) }
    private const val LOWPASS_A = 0.5496912067567652
    private const val DAMP_A = 0.39609821829371555

    data class Settings(val mix: Double = .15, val pitched: Boolean = true) {
        init { require(mix.isFinite() && mix in 0.0..0.5) { "shimmer mix must be finite and in 0..0.5" } }
    }
    data class Diagnostics(
        val settings: Settings, val inputFrames: Int, val inputChannels: Int,
        val outputFrames: Int, val outputChannels: Int, val tailFrames: Int,
        val bypass: Boolean, val guardGain: Double, val preGuardPeak: Double,
        val statePeak: Double, val shiftedFeedbackPeak: Double,
        val last100msStatePeak: Double, val wetLast100msPeakBeforeFade: Double,
        val wetPeak: Double, val delayHistorySamples: Int,
        val contractionBound: Double = CONTRACTION_BOUND,
        val limiterInsideTank: Boolean = false,
        val model: String = MODEL, val referenceModel: String = REFERENCE_MODEL,
    )
    data class RenderResult(val audio: Snip, val diagnostics: Diagnostics, val wet: Snip? = null)

    fun render(snip: Snip, settings: Settings = Settings(), includeWet: Boolean = false): RenderResult {
        require(snip.sampleRate == RATE) { "OBLITERATE shimmer requires the MPC sample rate" }
        require(snip.frameCount in 1..MAX_INPUT_SECONDS * RATE) { "shimmer input must contain 1 frame to 9 seconds" }
        require(snip.samples.all { it.isFinite() && it in -1f..1f }) { "finite PCM in -1..1 required" }
        if (settings.mix == 0.0) return RenderResult(snip, Diagnostics(
            settings, snip.frameCount, snip.channels, snip.frameCount, snip.channels, 0,
            true, 1.0, snip.samples.maxOf { abs(it.toDouble()) }, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
        ), if (includeWet) Snip(FloatArray(snip.samples.size), snip.channels, RATE) else null)
        val frames = snip.frameCount + TAIL_FRAMES
        val output = DoubleArray(frames * 2)
        val wetOutput = if (includeWet) DoubleArray(frames * 2) else null
        val tank = Array(4) { DoubleArray(delays[it]) }
        val inputHistory = if (settings.pitched) Array(2) { DoubleArray(HISTORY) } else emptyArray()
        val feedbackHistory = if (settings.pitched) Array(4) { DoubleArray(HISTORY) } else emptyArray()
        val hp = Array(2) { HighPass() }
        val inputLow = Array(2) { Lowpass4() }
        val returnLow = Array(4) { Lowpass4() }
        val dampState = DoubleArray(4)
        val returned = DoubleArray(4)
        val blended = DoubleArray(4)
        val excitation = DoubleArray(2)
        var statePeak = 0.0
        var shiftedPeak = 0.0
        var lastStatePeak = 0.0
        var preFadeTailPeak = 0.0
        var wetPeak = 0.0
        var preGuardPeak = 0.0
        for (n in 0 until frames) {
            val phase = n % WINDOW
            val other = (phase + WINDOW / 2) % WINDOW
            val weight = weights[phase]
            for (channel in 0..1) {
                val dry = if (n < snip.frameCount) snip.samples[n * snip.channels + minOf(channel, snip.channels - 1)].toDouble() else 0.0
                val filtered = inputLow[channel].next(hp[channel].next(dry))
                if (settings.pitched) {
                    inputHistory[channel][n % HISTORY] = filtered
                    excitation[channel] = shifted(inputHistory[channel], n, phase, other, weight)
                } else excitation[channel] = filtered
            }
            for (line in 0..3) {
                val d = tank[line][n % delays[line]]
                returned[line] = d
                val damped = (1.0 - DAMP_A) * d + dampState[line]
                dampState[line] = DAMP_A * damped
                if (settings.pitched) {
                    feedbackHistory[line][n % HISTORY] = returnLow[line].next(damped)
                    val octave = shifted(feedbackHistory[line], n, phase, other, weight)
                    shiftedPeak = maxOf(shiftedPeak, abs(octave))
                    blended[line] = .5 * damped + .5 * octave
                } else blended[line] = damped
            }
            for (line in 0..3) {
                var scatter = 0.0
                for (j in 0..3) scatter += blended[j] * signs[line][j] * .5
                val injection = excitation[0] * .5 + excitation[1] * signs[line][1] * .5
                val written = injection + FEEDBACK * scatter
                tank[line][n % delays[line]] = written
                statePeak = maxOf(statePeak, abs(written))
                if (n >= frames - WINDOW) lastStatePeak = maxOf(lastStatePeak, abs(written))
            }
            for (channel in 0..1) {
                var room = 0.0
                for (line in 0..3) room += returned[line] * signs[line][channel] * .5
                if (n >= frames - WINDOW) preFadeTailPeak = maxOf(preFadeTailPeak, abs(room))
                val fade = if (n >= frames - FADE) 1.0 - (n - (frames - FADE)).toDouble() / (FADE - 1) else 1.0
                val wet = settings.mix * (room * fade)
                wetPeak = maxOf(wetPeak, abs(wet))
                val dry = if (n < snip.frameCount) snip.samples[n * snip.channels + minOf(channel, snip.channels - 1)].toDouble() else 0.0
                val value = dry + wet
                output[n * 2 + channel] = value
                wetOutput?.set(n * 2 + channel, wet)
                preGuardPeak = maxOf(preGuardPeak, abs(value))
            }
        }
        val gain = minOf(1.0, PEAK_CEILING / maxOf(preGuardPeak, 1e-30))
        val audio = Snip(FloatArray(output.size) { (output[it] * gain).toFloat() }, 2, RATE)
        val wet = wetOutput?.let { Snip(FloatArray(it.size) { i -> (it[i] * gain).toFloat() }, 2, RATE) }
        return RenderResult(audio, Diagnostics(
            settings, snip.frameCount, snip.channels, frames, 2, TAIL_FRAMES, false, gain,
            preGuardPeak, statePeak, shiftedPeak, lastStatePeak, preFadeTailPeak, wetPeak * gain,
            tank.sumOf { it.size } + inputHistory.sumOf { it.size } + feedbackHistory.sumOf { it.size },
        ), wet)
    }

    private fun shifted(history: DoubleArray, n: Int, phase: Int, other: Int, weight: Double): Double {
        val a = n - BASE_DELAY - WINDOW + phase
        val b = n - BASE_DELAY - WINDOW + other
        return (if (a >= 0) history[a % HISTORY] * weight else 0.0) +
            (if (b >= 0) history[b % HISTORY] * (1.0 - weight) else 0.0)
    }
    private class Lowpass4 {
        private val state = DoubleArray(4)
        fun next(input: Double): Double {
            var value = input
            for (pole in 0..3) {
                value = (1.0 - LOWPASS_A) * value + state[pole]
                state[pole] = LOWPASS_A * value
            }
            return value
        }
    }
    private class HighPass {
        private var z1 = 0.0
        private var z2 = 0.0
        fun next(x: Double): Double {
            val y = HP[0] * x + z1
            z1 = HP[1] * x - HP[4] * y + z2
            z2 = HP[2] * x - HP[5] * y
            return y
        }
    }
    // SciPy 1.17.0 butter(2,150,btype='highpass',fs=RATE,output='sos').
    private val HP = doubleArrayOf(0.9850017578724192, -1.9700035157448383, 0.9850017578724192, 1.0, -1.96977855582618, 0.9702284756634976)
}
