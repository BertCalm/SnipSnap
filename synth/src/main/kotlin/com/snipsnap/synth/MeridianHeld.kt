package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A fixed-plane adaptation of MERIDIAN, rather than continued precession.
 * LATITUDE selects the plane reached after two seconds of the finite gesture.
 * A whole number of swings then repeats in that plane. Periodic roughness and
 * an analytically reconstructed modal initial state close the actual source
 * and ringing states. The two evidence cycles are simulated consecutively;
 * neither is made by copying the other, and no wrap crossfade is applied.
 */
internal object MeridianHeld {
    private const val RATE = Meridian.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val T60_LN = 6.907755278982137
    private const val PREFIX_FRAMES = 13230 // .30 s, before either evidence cycle.
    private const val POSTROLL_FRAMES = 2048
    private const val HASH_START = -3750763034362895579L
    private const val HASH_MULTIPLIER = 1099511628211L

    data class Result(
        val snip: Snip,
        val loopStartFrame: Int,
        val loopEndFrameExclusive: Int,
        val seamError: Double,
        val cycleError: Double,
        val stateError: Double,
        val noiseStateError: Double,
        val loopFrames: Int,
        val swings: Int,
        val nominalSwingPeriodSeconds: Double,
        val effectiveSwingPeriodSeconds: Double,
        val rootHz: Double,
        val rootGridHz: Double,
        val rootCents: Double,
        val fixedOrientation: Double,
        val activeSurfaces: Int,
        val rawPeak: Double,
        val peakEnergy: Double,
        val inputHashes: LongArray,
        val roughnessHashes: LongArray,
    )

    private data class Surface(
        val id: Int,
        val setup: Meridian.SurfaceSetup,
        val bank: Modes.Bank,
        val input: DoubleArray,
    )
    private data class Excitation(val input: DoubleArray, val noiseStateError: Double)

    fun render(voice: MeridianVoice, macros: Map<String, Float>, midi: Int,
        velocity: Float, seedContext: Int): Result {
        require(midi in Meridian.MIDI_MIN..Meridian.MIDI_MAX)
        require(velocity.isFinite() && velocity in 0f..1f)
        stopIfUnwanted()
        val m = Meridian.settled(voice, macros)
        val latitude = m.getValue("LATITUDE").toDouble()
        val length = m.getValue("LENGTH").toDouble()
        val reach = m.getValue("REACH").toDouble()
        val hz = Meridian.frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val nominalPeriod = .15 * 10.0.pow(length)
        val swings = ceil(.75 / nominalPeriod).toInt().coerceAtLeast(1)
        // A short periodic force has a harmonic grid. Fit its duration to
        // whole root periods rather than pulling a low note onto the swing's
        // grid. The geometry clock bends slightly; modal tuning does not.
        val minFrames = maxOf(ceil(.75 * RATE).toInt(), ceil(.15 * swings * RATE).toInt())
        val maxFrames = floor(1.5 * RATE).toInt()
        val minPeriods = ceil((minFrames - .499999) * hz / RATE).toInt().coerceAtLeast(1)
        val maxPeriods = floor((maxFrames + .499999) * hz / RATE).toInt().coerceAtLeast(minPeriods)
        val rootPeriods = (swings * nominalPeriod * hz).roundToInt().coerceIn(minPeriods, maxPeriods)
        val frames = (rootPeriods * RATE / hz).roundToInt().coerceIn(minFrames, maxFrames)
        val count = frames * Dsp.OVERSAMPLE
        val loopSeconds = frames.toDouble() / RATE
        val effectivePeriod = loopSeconds / swings
        val rootGridHz = rootPeriods * RATE.toDouble() / frames
        val rootCents = 1200 * ln(rootGridHz / hz) / ln(2.0)
        val effectiveLength = (ln(effectivePeriod / .15) / ln(10.0)).coerceIn(0.0, 1.0)
        val orientation = MeridianMotion.INITIAL_ORIENTATION -
            2 * MeridianMotion.MAX_PRECESSION * sin(PI * (latitude - .5))
        // The middle full swing is outside the finite plan's onset/off gates.
        // Its weights and catches therefore retain the existing contact law.
        val motion = MeridianMotion.prepare(latitude, effectiveLength, reach, 3 * effectivePeriod + .10,
            initialOrientation = orientation, fixedAlignment = true).plan
        val setups = Meridian.setups(hz, m.getValue("SURFACE").toDouble(),
            m.getValue("MEMORY"), reach, velocity.toDouble())
        val inputHashes = LongArray(Meridian.SURFACES) { HASH_START }
        val roughnessHashes = LongArray(Meridian.SURFACES) { HASH_START }
        val surfaces = ArrayList<Surface>()
        var noiseStateError = 0.0
        for (j in 0 until Meridian.SURFACES) {
            val first = floor(effectivePeriod * MeridianMotion.CONTROL_RATE).toInt()
            val last = ceil(2 * effectivePeriod * MeridianMotion.CONTROL_RATE).toInt()
            if ((first..last).none { motion.weight(it, j) > 0.0 }) continue
            val setup = setups[j]
            val seed = Dsp.seedFor("MERIDIAN", Meridian.LEGACY_MODEL_VERSION, voice.name, seedContext, j)
            val excitation = periodicInput(motion, setup, j, seed, count, swings,
                effectivePeriod, reach, velocity.toDouble(), roughnessHashes)
            val input = excitation.input
            noiseStateError = maxOf(noiseStateError, excitation.noiseStateError)
            for (x in input) inputHashes[j] = (inputHashes[j] xor x.toBits()) * HASH_MULTIPLIER
            surfaces += Surface(j, setup, setup.newBank(), input)
        }

        val prefixCount = PREFIX_FRAMES * Dsp.OVERSAMPLE
        val coldTail = DoubleArray(prefixCount)
        // One zero-state response determines each independent mode's affine
        // one-cycle map. Only the conditioner prefix needs to be retained.
        for (i in 0 until count) {
            if (i and 4095 == 0) stopIfUnwanted()
            var sum = 0.0
            for (surface in surfaces) {
                val drive = surface.input[i]
                if (drive != 0.0) surface.bank.drive(surface.setup.participation, drive * INTERNAL_RATE)
                surface.bank.step()
                if (i >= count - prefixCount) sum += surface.bank.velocityAlong(surface.setup.pickup)
            }
            if (i >= count - prefixCount) coldTail[i - (count - prefixCount)] = sum
        }

        val modes = surfaces.size * Meridian.MODES_PER_SURFACE
        val initialRe = DoubleArray(modes)
        val initialIm = DoubleArray(modes)
        val correctionRe = DoubleArray(modes)
        val correctionIm = DoubleArray(modes)
        val rotationRe = DoubleArray(modes)
        val rotationIm = DoubleArray(modes)
        val pickup = DoubleArray(modes)
        for ((s, surface) in surfaces.withIndex()) for (k in 0 until Meridian.MODES_PER_SURFACE) {
            val at = s * Meridian.MODES_PER_SURFACE + k
            val omega = 2 * PI * surface.setup.frequencies[k]
            val decay = T60_LN / surface.setup.t60s[k]
            val radius = exp(-decay * loopSeconds)
            val angle = omega * loopSeconds
            val ar = radius * cos(angle)
            val ai = radius * sin(angle)
            val denominator = (1 - ar) * (1 - ar) + ai * ai
            val cr = surface.bank.displacement(k) * omega
            val ci = -surface.bank.velocity(k)
            // z0 = coldEnd / (1 - a^N), with z = omega*q - i*v.
            val zr = (cr * (1 - ar) - ci * ai) / denominator
            val zi = (ci * (1 - ar) + cr * ai) / denominator
            initialRe[at] = zr
            initialIm[at] = zi
            val prefixTime = (count - prefixCount).toDouble() / INTERNAL_RATE
            val prefixRadius = exp(-decay * prefixTime)
            val prefixAngle = omega * prefixTime
            correctionRe[at] = prefixRadius * (cos(prefixAngle) * zr - sin(prefixAngle) * zi)
            correctionIm[at] = prefixRadius * (sin(prefixAngle) * zr + cos(prefixAngle) * zi)
            val stepRadius = exp(-decay / INTERNAL_RATE)
            rotationRe[at] = stepRadius * cos(omega / INTERNAL_RATE)
            rotationIm[at] = stepRadius * sin(omega / INTERNAL_RATE)
            pickup[at] = surface.setup.pickup[k]
        }

        fun correctionStep(): Double {
            var sum = 0.0
            for (k in 0 until modes) {
                val re = correctionRe[k]
                val im = correctionIm[k]
                correctionRe[k] = rotationRe[k] * re - rotationIm[k] * im
                correctionIm[k] = rotationIm[k] * re + rotationRe[k] * im
                sum -= pickup[k] * correctionIm[k]
            }
            return sum
        }

        var stateError = 0.0
        fun measureState(): Double {
            var error = 0.0
            var level = 0.0
            for ((s, surface) in surfaces.withIndex()) for (k in 0 until Meridian.MODES_PER_SURFACE) {
                val at = s * Meridian.MODES_PER_SURFACE + k
                val re = surface.bank.displacement(k) * 2 * PI * surface.setup.frequencies[k] + correctionRe[at]
                val im = -surface.bank.velocity(k) + correctionIm[at]
                error += (re - initialRe[at]).pow(2) + (im - initialIm[at]).pow(2)
                level += initialRe[at].pow(2) + initialIm[at].pow(2)
            }
            return if (level == 0.0) 0.0 else sqrt(error / level)
        }

        val raw = FloatArray(prefixCount + 2 * count + POSTROLL_FRAMES * Dsp.OVERSAMPLE)
        var peak = 0.0
        var peakEnergy = 0.0
        for (i in coldTail.indices) {
            if (i and 4095 == 0) stopIfUnwanted()
            val sum = coldTail[i] + correctionStep()
            raw[i] = sum.toFloat()
            peak = maxOf(peak, abs(sum))
        }
        stateError = maxOf(stateError, measureState())
        // Banks and homogeneous states both continue across the two evidence
        // cycles. Only their external forcing table repeats at the boundary.
        for (i in 0 until raw.size - prefixCount) {
            if (i and 4095 == 0) stopIfUnwanted()
            var sum = 0.0
            val inputIndex = i % count
            for (surface in surfaces) {
                val drive = surface.input[inputIndex]
                if (drive != 0.0) surface.bank.drive(surface.setup.participation, drive * INTERNAL_RATE)
                surface.bank.step()
                sum += surface.bank.velocityAlong(surface.setup.pickup)
            }
            sum += correctionStep()
            raw[prefixCount + i] = sum.toFloat()
            peak = maxOf(peak, abs(sum))
            if (i and 255 == 0) {
                var energy = 0.0
                for ((s, surface) in surfaces.withIndex()) for (k in 0 until Meridian.MODES_PER_SURFACE) {
                    val at = s * Meridian.MODES_PER_SURFACE + k
                    val re = surface.bank.displacement(k) * 2 * PI * surface.setup.frequencies[k] + correctionRe[at]
                    val im = -surface.bank.velocity(k) + correctionIm[at]
                    energy += .5 * (re * re + im * im)
                }
                peakEnergy = maxOf(peakEnergy, energy)
            }
            if (i == count - 1 || i == 2 * count - 1) stateError = maxOf(stateError, measureState())
        }
        check(raw.all { it.isFinite() } && peakEnergy.isFinite()) { "MERIDIAN held state is nonfinite" }
        check(stateError < 1e-5) { "MERIDIAN held state did not recur: $stateError" }
        stopIfUnwanted()
        Tide.bandLimit(raw, INTERNAL_RATE)
        stopIfUnwanted()
        val down = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (x in down) mean += x
        mean /= down.size
        val hp = Dsp.OnePole(RATE)
        for (i in down.indices) {
            val x = (down[i] - mean).toFloat()
            down[i] = x - hp.lp(x, 18f)
        }
        val audio = down.copyOfRange(PREFIX_FRAMES, PREFIX_FRAMES + 2 * frames)
        val current = audio.copyOfRange(frames, audio.size)
        val loudness = Loudness.of(current, 1, RATE)
        if (loudness > 1e-6f) {
            val gain = Dsp.MELODIC_LOUDNESS_TARGET / loudness
            for (i in audio.indices) audio[i] *= gain
        }
        Dsp.limitPeak(audio, .99f)
        val cycleError = relativeError(audio, frames)
        val seam = if (audio.all { it == 0f }) 0.0 else Keys.seamError(audio, frames)
        check(cycleError < 1e-5) { "MERIDIAN held audio did not recur: $cycleError" }
        check(seam < Keys.MAX_SEAM_ERROR) { "MERIDIAN held seam did not close: $seam" }
        // This is the initial note attack only. The sampler loop starts a full
        // swing cycle later, and neither its head nor its tail receives a fade.
        val attackFrames = minOf((.004 * RATE).roundToInt(), frames)
        for (i in 0 until attackFrames) audio[i] *= i.toFloat() / attackFrames
        return Result(Snip(audio, channels = 1, sampleRate = RATE), frames, 2 * frames,
            seam, cycleError, stateError, noiseStateError, frames, swings, nominalPeriod, effectivePeriod,
            hz, rootGridHz, rootCents, orientation, surfaces.size, peak, peakEnergy, inputHashes, roughnessHashes)
    }

    private fun periodicInput(motion: MeridianMotion.Plan, setup: Meridian.SurfaceSetup,
        surface: Int, seed: Int, count: Int, swings: Int, period: Double, reach: Double,
        velocity: Double, roughnessHashes: LongArray): Excitation {
        val alpha = setup.noiseAlpha
        val q = 1 - alpha
        var noise1 = 0.0
        var noise2 = 0.0
        var noise = Dsp.Noise(seed)
        for (i in 0 until count) {
            if (i and 4095 == 0) stopIfUnwanted()
            noise1 += alpha * (noise.next() - noise1)
            noise2 += alpha * (noise1 - noise2)
        }
        // Exact fixed point of the two cascaded one-poles for this seeded
        // period. Their triangular transition has lower entry N*alpha*q^N.
        val qN = exp(count * ln(q))
        noise1 /= 1 - qN
        noise2 = (noise2 + count * alpha * qN * noise1) / (1 - qN)
        val initialNoise1 = noise1
        val initialNoise2 = noise2
        noise = Dsp.Noise(seed)
        val input = DoubleArray(count)
        val brushGain = (.35 + .40 * reach) / sqrt(INTERNAL_RATE.toDouble())
        val effort = velocity.pow(1.35)
        for (i in 0 until count) {
            if (i and 4095 == 0) stopIfUnwanted()
            val n = noise.next()
            roughnessHashes[surface] = (roughnessHashes[surface] xor n.toRawBits().toLong()) * HASH_MULTIPLIER
            noise1 += alpha * (n - noise1)
            noise2 += alpha * (noise1 - noise2)
            val phase = ((i.toLong() * swings) % count).toDouble() / count
            val at = (period + phase * period) * MeridianMotion.CONTROL_RATE
            val frame = at.toInt()
            val fraction = at - frame
            val weight = motion.weight(frame, surface) * (1 - fraction) + motion.weight(frame + 1, surface) * fraction
            input[i] = weight * brushGain * noise2 * setup.noiseScale
        }
        val noiseLevel = initialNoise1 * initialNoise1 + initialNoise2 * initialNoise2
        val noiseError = (noise1 - initialNoise1).pow(2) + (noise2 - initialNoise2).pow(2)
        val noiseStateError = if (noiseLevel == 0.0) sqrt(noiseError) else sqrt(noiseError / noiseLevel)
        check(noiseStateError < 1e-9) { "MERIDIAN held roughness did not recur: $noiseStateError" }
        for (event in motion.entries[surface]) {
            if (event.seconds < period || event.seconds >= 2 * period) continue
            val phase = (event.seconds - period) / period
            for (swing in 0 until swings) {
                val start = (swing + phase) * count / swings
                val first = floor(start).toInt()
                for (sample in first..first + setup.pulse.size) {
                    val local = sample - start
                    if (local < 0 || local >= setup.pulse.lastIndex) continue
                    val k = local.toInt()
                    val fraction = local - k
                    val pulse = setup.pulse[k] * (1 - fraction) + setup.pulse[k + 1] * fraction
                    input[sample % count] += event.strength * pulse
                }
            }
        }
        for (i in input.indices) input[i] *= effort
        return Excitation(input, noiseStateError)
    }

    private fun relativeError(audio: FloatArray, frames: Int): Double {
        var error = 0.0
        var level = 0.0
        for (i in 0 until frames) {
            val difference = audio[frames + i] - audio[i].toDouble()
            error += difference * difference
            level += audio[i].toDouble() * audio[i]
        }
        return if (level == 0.0) 0.0 else error / level
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("MERIDIAN held render no longer wanted")
    }
}
