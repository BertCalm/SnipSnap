package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * RUSH: auto-swell -> sine ring -> amp -> envelope filter -> pitched stereo echo.
 *
 * AMT is macro intensity, not a delayed parallel dry/wet control. Zero is a
 * hard bypass. Every render starts fresh state and returns one printed hit,
 * without audition levelling, repetitions or a mono fold.
 *
 * The swell detects the input's linked magnitude with a Schmitt gate. The
 * filter follows its actual post-distortion input, including later onsets.
 * Ring is the shipped sine carrier; distortion is the shipped 4x VALVE amp
 * without its cabinet, not an emulation of a particular hard-clipping pedal.
 *
 * Echo is six progressively filtered, decaying feedforward taps, spaced at
 * 240 ms. The left alternates unison/+7 semitones; the right +7/unison.
 * Each shifted tap uses two fractional readheads with complementary Hann
 * windows. The 40 ms pitch window adds 0..40 ms of latency/spread to shifted
 * material. This is a bounded pitched echo, not a BBD model or a tempo-synced
 * live feedback bus; feedforward taps keep intervals from compounding.
 *
 * New stages never normalise quiet material upward. Their linked peak guards
 * attenuate only; Ring and Valve retain their existing shared peak matching.
 */
object Rush {
    const val MAX_TAIL_SECONDS = 1.5f
    const val DELAY_SECONDS = .240f
    const val PITCH_WINDOW_SECONDS = .040f
    const val ECHO_TAPS = 6
    const val FIRST_STEP_SEMITONES = 0
    const val SECOND_STEP_SEMITONES = 7
    const val RING_HZ = 180f

    fun process(snip: Snip, amount: Float = 1f): Snip {
        validateAmount(amount)
        if (amount == 0f || snip.frameCount == 0) return snip
        require(snip.sampleRate >= 8_000) { "RUSH needs a sample rate of at least 8 kHz" }
        require(snip.samples.all { it.isFinite() }) { "RUSH needs finite source audio" }
        var out = snip
        for (stage in stages(amount)) out = stage.run(out, stage.macros)
        check(out.frameCount.toLong() <= snip.frameCount.toLong() + (MAX_TAIL_SECONDS * snip.sampleRate).toLong()) {
            "RUSH exceeded its one-shot tail budget"
        }
        return out
    }

    internal class Stage(
        val name: String,
        val macros: Map<String, Float>,
        val run: (Snip, Map<String, Float>) -> Snip,
    )

    internal fun stages(amount: Float): List<Stage> {
        validateAmount(amount)
        if (amount == 0f) return emptyList()
        val ringFrequency = (ln((RING_HZ / Ring.LOW_HZ).toDouble()) /
            ln((Ring.HIGH_HZ / Ring.LOW_HZ).toDouble())).toFloat()
        return listOf(
            Stage("Auto-Swell", linkedMapOf(
                "ATTACK_MS" to (10f + 110f * amount), "RELEASE_MS" to 70f,
                "DEPTH" to amount, "OPEN_DB" to -28f, "CLOSE_DB" to -36f, "HOLD_MS" to 18f,
            ), ::autoSwell),
            Stage("Ring", linkedMapOf("FREQ" to ringFrequency, "MIX" to .38f * amount), Ring::process),
            Stage("Distortion", linkedMapOf(
                "DRIVE" to (.45f + .30f * amount), "SAG" to .08f * amount,
                "TONE" to (.5f + .12f * amount), "CAB" to 0f,
            ), Valve::process),
            Stage("Envelope Filter", linkedMapOf(
                "BASE_HZ" to Dsp.expMap(1f - amount, 650f, 12_000f),
                "DEPTH_OCT" to 4f * amount, "RESONANCE_K" to (1.4f - .6f * amount),
                "ATTACK_MS" to 4f, "RELEASE_MS" to 95f, "MIX" to amount,
            ), ::envelopeFilter),
            Stage("Pitched Echo", linkedMapOf(
                "TIME_MS" to DELAY_SECONDS * 1000f, "STEP_1_ST" to FIRST_STEP_SEMITONES.toFloat(),
                "STEP_2_ST" to SECOND_STEP_SEMITONES.toFloat(), "TAPS" to ECHO_TAPS.toFloat(),
                "PITCH_WINDOW_MS" to PITCH_WINDOW_SECONDS * 1000f,
                "DECAY" to (.25f + .15f * amount), "TONE_HZ" to (9_000f - 4_000f * amount),
                "MIX" to .28f * amount,
            ), ::pitchedEcho),
        )
    }

    private fun validateAmount(amount: Float) {
        require(amount.isFinite() && amount in 0f..1f) { "RUSH amount must be finite and in 0..1: $amount" }
    }

    /** Attack/release follower over max(abs(L),abs(R)), never the signed fold. */
    private class Follower(rate: Int, attackMs: Float, releaseMs: Float) {
        private val attack = coefficient(attackMs, rate)
        private val release = coefficient(releaseMs, rate)
        var value = 0f
            private set
        fun next(magnitude: Float): Float {
            value += (if (magnitude > value) attack else release) * (magnitude - value)
            return value
        }
    }

    /** Times here are one-pole time constants; auto-swell rise is defined separately below. */
    private fun coefficient(milliseconds: Float, rate: Int): Float =
        (1.0 - exp(-1.0 / (milliseconds.coerceAtLeast(.1f) * .001 * rate))).toFloat()

    private fun linkedMagnitude(snip: Snip, frame: Int): Float {
        var magnitude = 0f
        for (ch in 0 until snip.channels) magnitude = maxOf(magnitude, abs(snip.samples[frame * snip.channels + ch]))
        return magnitude
    }

    internal fun autoSwell(snip: Snip, macros: Map<String, Float>): Snip {
        val depth = macros.getValue("DEPTH")
        val peak = snip.peak()
        if (depth == 0f || peak == 0f || snip.frameCount == 0) return snip
        val follower = Follower(snip.sampleRate, 1.5f, 12f)
        val open = 10.0.pow(macros.getValue("OPEN_DB") / 20.0).toFloat()
        val close = 10.0.pow(macros.getValue("CLOSE_DB") / 20.0).toFloat()
        val hold = (macros.getValue("HOLD_MS") * .001f * snip.sampleRate).toInt().coerceAtLeast(1)
        // ATTACK_MS is time to 99%, not a time constant: a 120 ms swell
        // should arrive in 120 ms rather than taking half a second.
        val rise = (1.0 - exp(-4.605170186 / (macros.getValue("ATTACK_MS") * .001 * snip.sampleRate))).toFloat()
        val release = coefficient(macros.getValue("RELEASE_MS"), snip.sampleRate)
        var gate = false
        var below = 0
        var gain = 0f
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            val env = follower.next(linkedMagnitude(snip, f) / peak)
            if (!gate && env >= open) { gate = true; below = 0 }
            if (gate) {
                below = if (env <= close) below + 1 else 0
                if (below >= hold) gate = false
            }
            // Do not reset gain at retrigger: a new onset during release
            // reverses a smooth ramp rather than making an amplitude step.
            val target = if (gate) 1f else 0f
            gain += (if (gate) rise else release) * (target - gain)
            val applied = (1f - depth) + depth * gain
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                out[i] = snip.samples[i] * applied
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Exposed internally for signal-based follower tests, not an extra product control. */
    internal fun filterCutoffs(snip: Snip, macros: Map<String, Float>): FloatArray {
        val peak = snip.peak()
        val follower = Follower(snip.sampleRate, macros.getValue("ATTACK_MS"), macros.getValue("RELEASE_MS"))
        val ceiling = snip.sampleRate * .4f
        val base = macros.getValue("BASE_HZ").coerceAtMost(ceiling)
        val octaves = macros.getValue("DEPTH_OCT")
        val smoothing = coefficient(2f, snip.sampleRate)
        var logCutoff = ln(base.toDouble()).toFloat()
        return FloatArray(snip.frameCount) { f ->
            val magnitude = if (peak > 0f) linkedMagnitude(snip, f) / peak else 0f
            val env = follower.next(magnitude).coerceIn(0f, 1f)
            val target = ln(base.toDouble()).toFloat() + (ln(2.0) * octaves * env).toFloat()
            logCutoff += smoothing * (target - logCutoff)
            exp(logCutoff.toDouble()).toFloat().coerceAtMost(ceiling)
        }
    }

    internal fun envelopeFilter(snip: Snip, macros: Map<String, Float>): Snip {
        if (snip.frameCount == 0 || macros.getValue("MIX") == 0f) return snip
        val cutoffs = filterCutoffs(snip, macros)
        val filters = Array(snip.channels) { Dsp.TptSvf(snip.sampleRate) }
        val mix = macros.getValue("MIX")
        val k = macros.getValue("RESONANCE_K")
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) for (ch in 0 until snip.channels) {
            val i = f * snip.channels + ch
            val dry = snip.samples[i]
            filters[ch].process(dry, cutoffs[f], k)
            out[i] = dry + mix * (filters[ch].low - dry)
        }
        attenuateOnly(out, snip.peak())
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Dual Hann readheads. Each head jumps only where its own weight is zero. */
    internal fun pitchShift(snip: Snip, semitones: Float, windowMs: Float = PITCH_WINDOW_SECONDS * 1000f): Snip {
        if (semitones == 0f || snip.frameCount == 0) return snip
        val ratio = 2.0.pow(semitones / 12.0)
        val window = (windowMs * .001 * snip.sampleRate).toInt().coerceAtLeast(4)
        val frames = snip.frameCount + window
        val out = FloatArray(frames * snip.channels)
        val increment = (1.0 - ratio) / window
        var phase = 0.0
        for (f in 0 until frames) {
            val other = (phase + .5) % 1.0
            val weight = (.5 - .5 * cos(2.0 * PI * phase)).toFloat()
            val firstPos = f - phase * window
            val secondPos = f - other * window
            for (ch in 0 until snip.channels) {
                out[f * snip.channels + ch] = weight * readHermite(snip, firstPos, ch) +
                    (1f - weight) * readHermite(snip, secondPos, ch)
            }
            phase += increment
            phase -= floor(phase)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun readHermite(snip: Snip, position: Double, channel: Int): Float {
        val index = floor(position).toInt()
        val t = (position - index).toFloat()
        fun sample(f: Int): Float = if (f in 0 until snip.frameCount) snip.samples[f * snip.channels + channel] else 0f
        val a = sample(index - 1); val b = sample(index); val c = sample(index + 1); val d = sample(index + 2)
        val c1 = .5f * (c - a)
        val c2 = a - 2.5f * b + 2f * c - .5f * d
        val c3 = .5f * (d - a) + 1.5f * (b - c)
        return ((c3 * t + c2) * t + c1) * t + b
    }

    internal fun pitchedEcho(snip: Snip, macros: Map<String, Float>): Snip {
        if (snip.frameCount == 0) return snip
        val delay = (macros.getValue("TIME_MS") * .001f * snip.sampleRate).toInt().coerceAtLeast(1)
        val taps = macros.getValue("TAPS").toInt()
        val window = (macros.getValue("PITCH_WINDOW_MS") * .001f * snip.sampleRate).toInt()
        val tail = minOf(taps * delay + window, (MAX_TAIL_SECONDS * snip.sampleRate).toInt())
        val outFrames = snip.frameCount + tail
        val out = FloatArray(outFrames * 2)
        for (f in 0 until snip.frameCount) for (ch in 0..1) {
            out[f * 2 + ch] = snip.samples[f * snip.channels + if (snip.channels == 1) 0 else ch]
        }
        var filtered = snip
        var gain = macros.getValue("MIX")
        val step1 = macros.getValue("STEP_1_ST")
        val step2 = macros.getValue("STEP_2_ST")
        for (tap in 1..taps) {
            filtered = echoLowpass(filtered, macros.getValue("TONE_HZ"))
            val shifted1 = pitchShift(filtered, step1, macros.getValue("PITCH_WINDOW_MS"))
            val shifted2 = pitchShift(filtered, step2, macros.getValue("PITCH_WINDOW_MS"))
            for (ch in 0..1) {
                val stream = if ((tap + ch) % 2 == 1) shifted1 else shifted2
                val sourceChannel = if (snip.channels == 1) 0 else ch
                val available = minOf(stream.frameCount, outFrames - tap * delay)
                for (f in 0 until available) out[(f + tap * delay) * 2 + ch] +=
                    gain * stream.samples[f * stream.channels + sourceChannel]
            }
            gain *= macros.getValue("DECAY")
        }
        // Both natural source endings and the explicit one-shot ceiling are
        // protected; no extra tail or infinite feedback is hidden in the pad.
        val fadeFrames = minOf((.015f * snip.sampleRate).toInt(), tail)
        for (d in 0 until fadeFrames) {
            val gainAtEnd = d.toFloat() / fadeFrames
            val f = outFrames - 1 - d
            out[2 * f] *= gainAtEnd
            out[2 * f + 1] *= gainAtEnd
        }
        attenuateOnly(out, snip.peak())
        return Snip(out, 2, snip.sampleRate)
    }

    private fun echoLowpass(snip: Snip, cutoffHz: Float): Snip {
        val out = snip.samples.copyOf()
        for (ch in 0 until snip.channels) {
            val filter = Dsp.TptSvf(snip.sampleRate)
            for (f in 0 until snip.frameCount) {
                val i = f * snip.channels + ch
                filter.process(snip.samples[i], minOf(cutoffHz, snip.sampleRate * .35f), 1.41421356f)
                out[i] = filter.low
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun attenuateOnly(samples: FloatArray, inputPeak: Float) {
        var peak = 0f
        for (sample in samples) peak = maxOf(peak, abs(sample))
        val ceiling = minOf(inputPeak, .99f)
        if (peak > ceiling && peak > 0f) {
            val gain = ceiling / peak
            for (i in samples.indices) samples[i] *= gain
        }
    }
}
