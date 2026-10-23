package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** On bypass [inputPeak] is unmeasured (zero sentinel); [activeModes] is zero and PCM is never scanned. */
data class BodyDiagnostics(
    val modelVersion: Int,
    val coefficientVersion: Int,
    val tableIdentity: String,
    val rootMidi: Int,
    val referenceHz: Double,
    val rootT60: Double,
    val tailFrames: Int,
    val activeModes: Int,
    val coefficientCacheHit: Boolean,
    val inputPeak: Double,
    val statePeak: Double,
    val wetPeak: Double,
    val mixedPeak: Double,
    val attenuation: Double,
)

/** [rawWet] is opt-in, faded wet audio before the delivery guard; it can exceed full scale. */
data class BodyRender(val audio: Snip, val diagnostics: BodyDiagnostics, val rawWet: Snip? = null)

/**
 * A capture excites two fixed four-mode spectra; BODY blends their outputs, not their poles.
 * The source's native 44.1/48 kHz clock and mono/stereo layout survive unchanged. No source
 * analysis, normalization, nonlinear drive, motion, or legacy BODY/rack integration occurs.
 *
 * Each mode is z[n]=exp(iθ)*(r*z[n-1]+(1-r)*x[n]); its imaginary part is the output.
 * Rotation has norm one and 0<r<1, so |z| cannot exceed the largest input magnitude.
 * Convex mode/bank weights retain that bound. Fixed makeup is deliberately separate from
 * the one attenuation-only, stereo-linked delivery guard. Neither guard nor a test limiter
 * supplies the stability argument. See docs/plans/source-transformation/r1-implementation-plan.md.
 */
class ResonantBody(cacheEnabled: Boolean = true) {
    private val cache = BodyCoefficientCache(cacheEnabled)

    fun clearCoefficientCache() = cache.clear()

    fun process(source: Snip, settings: BodySettings = BodySettings(), captureWet: Boolean = false): BodyRender {
        if (settings.mix == 0f) {
            // Do not even scan PCM: bypass must preserve unusual rates, signed zeros and non-finites.
            return BodyRender(source, diagnostics(settings, "bypass", 0, 0, false, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        val nativeRate = WavWriter.MPC_SAMPLE_RATE
        require(source.sampleRate == nativeRate || source.sampleRate == 48_000) { "body supports $nativeRate or 48000 Hz" }
        require(source.frameCount in 1..(8 * source.sampleRate)) { "body source must contain 1 frame through 8 seconds" }
        var inputPeak = 0.0
        for (value in source.samples) {
            require(value.isFinite() && value in -1f..1f) { "body source PCM must be finite and in -1..1" }
            inputPeak = max(inputPeak, abs(value.toDouble()))
        }

        val key = BodyCoefficientKey(BodyModels.identity(settings), COEFFICIENT_VERSION, source.sampleRate,
            settings.referenceHz, settings.rootT60, if (settings.damp == 0f) 0.0 else settings.damp.toDouble())
        val (coefficients, hit) = cache.get(key)
        val tailFrames = ceil(settings.rootT60 * source.sampleRate).toInt()
        val frames = source.frameCount + tailFrames
        val output = FloatArray(frames * source.channels)
        val rawWet = if (captureWet) FloatArray(output.size) else null
        // State belongs to this render and channel. A warm cache cannot remember an earlier sound.
        val real = DoubleArray(8 * source.channels)
        val imaginary = DoubleArray(real.size)
        val body = settings.body.toDouble()
        val mix = settings.mix.toDouble()
        val first = if (body == 1.0) 4 else 0
        val end = if (body == 0.0) 4 else 8
        val fadeFrames = ceil(0.010 * source.sampleRate).toInt()
        var maxStateSquared = 0.0
        var wetPeak = 0.0
        var mixedPeak = 0.0
        for (frame in 0 until frames) {
            // Only the appended wet tail is faded; the dry source's timing and end are untouched.
            val fade = if (frame < frames - fadeFrames) 1.0 else (frames - 1 - frame).toDouble() / (fadeFrames - 1)
            for (channel in 0 until source.channels) {
                val index = frame * source.channels + channel
                val x = if (frame < source.frameCount) source.samples[index].toDouble() else 0.0
                var fromBank = 0.0
                var toBank = 0.0
                for (mode in first until end) {
                    val c = coefficients[mode]
                    val state = channel * 8 + mode
                    val previousReal = real[state]
                    val previousImaginary = imaginary[state]
                    val re = c.rotateReal * previousReal - c.rotateImaginary * previousImaginary + c.injectReal * x
                    val im = c.rotateImaginary * previousReal + c.rotateReal * previousImaginary + c.injectImaginary * x
                    real[state] = re
                    imaginary[state] = im
                    maxStateSquared = max(maxStateSquared, re * re + im * im)
                    if (mode < 4) fromBank += c.weight * im else toBank += c.weight * im
                }
                val wet = MAKEUP * ((1 - body) * fromBank + body * toBank) * fade
                wetPeak = max(wetPeak, abs(wet))
                rawWet?.set(index, wet.toFloat())
                val sample = (1 - mix) * x + mix * wet
                mixedPeak = max(mixedPeak, abs(sample))
                output[index] = sample.toFloat()
            }
        }
        check(maxStateSquared.isFinite() && wetPeak.isFinite() && mixedPeak.isFinite()) { "non-finite body state" }
        // Linked, attenuation only. Quiet input and numerical residue are never amplified.
        val attenuation = if (mixedPeak > CEILING) CEILING / mixedPeak else 1.0
        if (attenuation < 1.0) for (i in output.indices) output[i] = (output[i] * attenuation).toFloat()
        val identity = "model${key.table.version}:${settings.from}:${settings.to}:4:$COEFFICIENT_VERSION"
        return BodyRender(Snip(output, source.channels, source.sampleRate),
            diagnostics(settings, identity, tailFrames, end - first, hit, inputPeak, sqrt(maxStateSquared), wetPeak, mixedPeak, attenuation),
            rawWet?.let { Snip(it, source.channels, source.sampleRate) })
    }

    private fun diagnostics(s: BodySettings, table: String, tail: Int, active: Int, hit: Boolean,
        input: Double, state: Double, wet: Double, mixed: Double, attenuation: Double) =
        BodyDiagnostics(s.modelVersion, COEFFICIENT_VERSION, table, s.rootMidi, s.referenceHz, s.rootT60,
            tail, active, hit, input, state, wet, mixed, attenuation)

    companion object {
        const val MODEL_VERSION = 1
        const val COEFFICIENT_VERSION = 1
        const val MAKEUP = 4.0
        const val CEILING = 0.98
    }
}
