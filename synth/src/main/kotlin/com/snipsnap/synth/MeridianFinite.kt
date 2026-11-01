package com.snipsnap.synth

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The finite model's allocation-free audio loop, arranged one surface at a time.
 *
 * Surfaces do not feed each other or the maintained motion. Each therefore has
 * exactly the same noise/filter, drive, free step and pickup sequence when it is
 * completed before the next one. A double accumulator keeps additions in stable
 * surface-ID order, with the one Float rounding at the same final boundary as
 * Meridian's diagnostic sample-major pass. Modes.Bank and the house output
 * stage retain their original arithmetic. This is an execution-order change,
 * not a smaller modal model or a quiet-tail truncation.
 */
internal object MeridianFinite {
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    fun raw(configs: Array<Meridian.SurfaceSetup>, motion: MeridianMotion.Plan,
        active: Double, tail: Double, reach: Double, velocity: Float,
        voice: MeridianVoice, seedContext: Int): FloatArray {
        stopIfUnwanted()
        val count = ((active + tail) * Dsp.RATE).roundToInt() * Dsp.OVERSAMPLE
        if (velocity == 0f) return FloatArray(count)
        val activeFrames = ceil(active * INTERNAL_RATE).toInt()
        val releaseFrame = maxOf(activeFrames, ((active + tail - .30) * INTERNAL_RATE).toInt())
        val brushGain = (.35 + .40 * reach) / sqrt(INTERNAL_RATE.toDouble())
        val effort = velocity.toDouble().pow(1.35)
        val sum = DoubleArray(count)
        // These descriptors are identical for every surface. Keep the original
        // floating expression rather than approximating its interpolation clock.
        val controlFrames = IntArray(activeFrames)
        val fractions = DoubleArray(activeFrames)
        for (i in 0 until activeFrames) {
            if (i and 4095 == 0) stopIfUnwanted()
            val at = i.toDouble() * MeridianMotion.CONTROL_RATE / INTERNAL_RATE
            val frame = at.toInt()
            controlFrames[i] = frame
            fractions[i] = at - frame
        }
        for (surface in 0 until Meridian.SURFACES) {
            stopIfUnwanted()
            val config = configs[surface]
            var lastContactFrame = -1
            for (frame in 0 until motion.frameCount) {
                if (motion.weight(frame, surface) > 0.0) lastContactFrame = frame
            }
            val entries = motion.entries[surface]
            if (lastContactFrame < 0 && entries.isEmpty()) continue
            val eventFrames = DoubleArray(entries.size) { entries[it].seconds * INTERNAL_RATE }
            val eventStrengths = DoubleArray(entries.size) { entries[it].strength }
            val pulse = config.pulse
            val eventCount = eventFrames.size
            // After the final possible pressure sample and finite pulse, unused
            // roughness cannot affect this surface again. Its ringing bank still
            // evolves all the way through the tail, with no amplitude threshold.
            val pressureEnd = ceil((lastContactFrame + 1.0) * INTERNAL_RATE / MeridianMotion.CONTROL_RATE).toInt()
            val catchEnd = if (eventCount == 0) 0 else ceil(eventFrames.last() + pulse.lastIndex).toInt()
            val forcedUntil = minOf(activeFrames, maxOf(pressureEnd, catchEnd))
            val bank = config.newBank()
            val noise = Dsp.Noise(Dsp.seedFor("MERIDIAN", Meridian.LEGACY_MODEL_VERSION,
                voice.name, seedContext, surface))
            val alpha = config.noiseAlpha
            val noiseScale = config.noiseScale
            val participation = config.participation
            val pickup = config.pickup
            var noise1 = 0.0
            var noise2 = 0.0
            var controlFrame = -1
            var row0 = 0.0
            var row1 = 0.0
            var nextEvent = 0
            var sounding = false
            var released = false
            for (i in 0 until count) {
                if (i and 4095 == 0) stopIfUnwanted()
                if (!released && i >= releaseFrame) {
                    for (mode in 0 until Meridian.MODES_PER_SURFACE)
                        bank.tune(mode, config.frequencies[mode], minOf(config.t60s[mode], .08))
                    released = true
                }
                var increment = 0.0
                if (i < forcedUntil) {
                    val frame = controlFrames[i]
                    if (frame != controlFrame) {
                        row0 = motion.weight(frame, surface)
                        row1 = motion.weight(frame + 1, surface)
                        controlFrame = frame
                    }
                    val n = noise.next()
                    noise1 += alpha * (n - noise1)
                    noise2 += alpha * (noise1 - noise2)
                    val weight = row0 + fractions[i] * (row1 - row0)
                    increment = weight * brushGain * noise2 * noiseScale
                    while (nextEvent < eventCount && i - eventFrames[nextEvent] >= pulse.lastIndex)
                        nextEvent++
                    if (nextEvent < eventCount) {
                        val local = i - eventFrames[nextEvent]
                        if (local >= 0.0) {
                            val n0 = local.toInt()
                            val fraction = local - n0
                            val value = pulse[n0] * (1 - fraction) + pulse[n0 + 1] * fraction
                            increment += eventStrengths[nextEvent] * value
                        }
                    }
                    increment *= effort
                }
                if (increment != 0.0) {
                    bank.drive(participation, increment * INTERNAL_RATE)
                    sounding = true
                }
                if (sounding) {
                    bank.step()
                    sum[i] += bank.velocityAlong(pickup)
                }
            }
        }
        stopIfUnwanted()
        return FloatArray(count) { sum[it].toFloat() }.also { raw ->
            check(raw.all { it.isFinite() }) { "MERIDIAN produced nonfinite internal output" }
        }
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("MERIDIAN render no longer wanted")
    }
}
