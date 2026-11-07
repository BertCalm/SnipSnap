package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Relay model 2: the continuous PULSE-0 voice followed by the repeating gain gesture
 * selected in the listening audition. Knob travel adds an equal peak-to-trough dB range;
 * the 7 dB top step retains a body through every dip. No source restarts or color motion.
 *
 * This is the same finished-sample stage as the audition, after the unchanged source's
 * conditioning and finite fade, before optional engine leveling. Its bounded gain cannot
 * increase native source peaks. Multiplication can add a small DC component, measured by
 * the model tests rather than hidden by another filter that would change the audition.
 */
internal object RelayPulse {
    const val NOMINAL_RATE_HZ = 3.0
    const val MAX_DEPTH_DB = 7.0

    internal fun inspect(voice: RelayVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                         normalize: Boolean = true, phrase: RelayPhrase = Relay.phraseFor(voice)): RelayModels.Report {
        val settled = Relay.settled(voice, macros)
        val pulse = settled.getValue("PULSE").toDouble()
        // Model 1 remains the only implementation of source generation. Its PULSE-0
        // path prevents the old sparse accent brightness from leaking into this gesture.
        val base = Relay.inspect(voice, settled + ("PULSE" to 0f), velocity,
            normalize = normalize && pulse == 0.0, phrase = phrase)
        val cycles = if (base.loop) (NOMINAL_RATE_HZ * base.phraseSeconds).roundToInt().coerceAtLeast(1) else null
        val rate = cycles?.let { it / base.phraseSeconds } ?: NOMINAL_RATE_HZ
        val depth = MAX_DEPTH_DB * pulse
        val floor = 10.0.pow(-depth / 20.0)
        val motion = RelayModels.Pulse(depth, floor, rate, cycles)
        // Delegation at zero is exact for both native and normalized PCM.
        if (pulse == 0.0) return RelayModels.Report(base, base.snip, base.previousCycle, base.seamError, motion)

        fun gain(frame: Int): Double = floor + (1.0 - floor) *
            (.5 + .5 * cos(2.0 * PI * rate * frame / Dsp.RATE))

        stopIfUnwanted()
        val samples = FloatArray(base.snip.samples.size) { i ->
            if (i % 8192 == 0) stopIfUnwanted()
            (base.snip.samples[i] * gain(i)).toFloat()
        }
        // The preceding *rendered* cycle supplies the seam check. Negative time places
        // its gain phase one actual fitted phrase before the region being exported.
        val previous = FloatArray(base.previousCycle.size) { i ->
            if (i % 8192 == 0) stopIfUnwanted()
            (base.previousCycle[i] * gain(i - base.previousCycle.size)).toFloat()
        }
        if (normalize) {
            stopIfUnwanted()
            val peakBefore = samples.maxOfOrNull { abs(it) } ?: 0f
            Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val levelGain = if (peakBefore > 1e-12f) (samples.maxOfOrNull { abs(it) } ?: 0f) / peakBefore else 1f
            for (i in previous.indices) previous[i] *= levelGain
        }
        // The source's finite fade already ends at zero; the gain and leveling preserve it.
        // HOLD uses complete pulse cycles in the root-fitted phrase instead of cutting 3 Hz.
        val seam = if (base.loop && samples.any { it != 0f })
            Keys.seamError(previous + samples, previous.size) else 0.0
        check(!base.loop || seam < Keys.MAX_SEAM_ERROR) { "RELAY periodic pulse does not close: $seam" }
        stopIfUnwanted()
        return RelayModels.Report(base, Snip(samples, channels = 1, sampleRate = Dsp.RATE), previous, seam, motion)
    }
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("RELAY render no longer wanted")
    }
}
