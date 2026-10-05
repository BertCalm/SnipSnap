package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * CIRCUIT's dry surrounding players. Each call owns its excitation, modal
 * states and random sequence; geometry and returned cues belong to the engine.
 * Durations are 250/260/480/280/580 ms in [Kind] order, including quiet tails.
 * Contact areas are sample-rate independent, so these sources can run directly
 * at the engine's 4x rate. Nothing remains excited after a finite gesture.
 * [gesture]'s optional power cutoff ends excitation at that relative time;
 * the returned buffer still contains the instrument's passive response.
 */
internal object CircuitInstruments {
    enum class Kind { RATTLE, CLAPPER, CLAY, GRUNT, UH_HUH }

    fun gesture(
        kind: Kind,
        rootHz: Double,
        rate: Int,
        energy: Double,
        seed: Long,
        breath: Double = .55,
        poweredSeconds: Double? = null,
    ): FloatArray {
        require(rate >= 8_000) { "Circuit gesture rate must be at least 8000 Hz" }
        val root = if (rootHz.isFinite()) rootHz.coerceIn(20.0, 5_000.0) else 130.8128
        val strength = if (energy.isFinite()) energy.coerceIn(0.0, 1.0) else 0.0
        val air = if (breath.isFinite()) breath.coerceIn(0.0, 1.0) else .55
        val random = Random(seed)
        val seconds = when (kind) {
            Kind.RATTLE -> .25
            Kind.CLAPPER -> .26
            Kind.CLAY -> .48
            Kind.GRUNT -> .28
            Kind.UH_HUH -> .58
        }
        val result = FloatArray((seconds * rate).toInt())
        // This ends excitation, while existing modal and tract states continue decaying.
        val poweredFrames = when {
            poweredSeconds == null || poweredSeconds == Double.POSITIVE_INFINITY -> result.size
            !poweredSeconds.isFinite() -> 0
            else -> ceil(poweredSeconds.coerceAtLeast(0.0) * rate).toInt().coerceIn(0, result.size)
        }
        if (strength == 0.0 || poweredFrames == 0) return result
        when (kind) {
            Kind.RATTLE -> rattle(result, root, rate, random, poweredFrames)
            Kind.CLAPPER -> clapper(result, root, rate, random, poweredFrames)
            Kind.CLAY -> clay(result, root, rate, random, poweredFrames)
            Kind.GRUNT, Kind.UH_HUH -> vocal(result, root, rate, random, air, kind == Kind.UH_HUH, poweredFrames)
        }
        finish(result, rate, strength)
        return result
    }

    private data class Mode(val ratio: Double, val decay: Double, val weight: Double)

    /** A rounded contact with a fixed impulse area, rather than a rate-dependent spike. */
    private fun contact(force: FloatArray, rate: Int, at: Double, width: Double, amount: Double, poweredFrames: Int) {
        val start = (at * rate).toInt()
        val length = max(3, (width * rate).toInt())
        val scale = 2.0 * amount / (length - 1)
        for (i in 0 until length) {
            val frame = start + i
            if (frame >= 0 && frame < min(force.size, poweredFrames)) {
                force[frame] += (scale * .5 * (1.0 - cos(2.0 * PI * i / (length - 1)))).toFloat()
            }
        }
    }

    /** Exact damped rotations: finite contacts ring down without a continuing noise source. */
    private fun modes(output: FloatArray, force: FloatArray, root: Double, rate: Int, modes: Array<Mode>) {
        val ceiling = min(13_500.0, rate * .42)
        for (mode in modes) {
            var frequency = root * mode.ratio
            // Octave folding preserves the mode's relation to high requested roots.
            while (frequency > ceiling) frequency *= .5
            val radius = exp(-1.0 / (mode.decay * rate))
            val angle = 2.0 * PI * frequency / rate
            val c = radius * cos(angle)
            val s = radius * sin(angle)
            var x = 0.0
            var y = 0.0
            for (i in output.indices) {
                y += force[i]
                val nextX = c * x + s * y
                y = c * y - s * x
                x = nextX
                output[i] += (mode.weight * x).toFloat()
            }
        }
    }

    private fun rattle(output: FloatArray, root: Double, rate: Int, random: Random, poweredFrames: Int) {
        val force = FloatArray(output.size)
        val collisions = random.nextInt(7, 13)
        // One finite shake: individual collisions accelerate then scatter, all before 100 ms.
        for (i in 0 until collisions) {
            val position = i.toDouble() / (collisions - 1)
            val at = .003 + .085 * position.pow(.8) + random.nextDouble(0.0, .004)
            val strength = random.nextDouble(.45, .95) * sin(PI * (.15 + .70 * position))
            contact(force, rate, at, random.nextDouble(.00018, .00048), strength, poweredFrames)
        }
        modes(output, force, root, rate, arrayOf(
            Mode(1.5, .027, .035), // hollow cavity; pitched below the granular mineral surface
            Mode(3.0, .020, .055),
            Mode(4.5, .015, .052),
            Mode(7.07, .009, .033),
            Mode(10.6, .006, .022),
            Mode(15.2, .004, .013),
        ))
    }

    private fun clapper(output: FloatArray, root: Double, rate: Int, random: Random, poweredFrames: Int) {
        val first = FloatArray(output.size)
        val second = FloatArray(output.size)
        contact(first, rate, .003, .00065, random.nextDouble(.85, 1.0), poweredFrames)
        contact(second, rate, random.nextDouble(.046, .057), .00085, random.nextDouble(.82, 1.0), poweredFrames)
        modes(output, first, root, rate, arrayOf(
            Mode(2.0, .019, .15), Mode(3.0, .012, .085), Mode(5.08, .006, .045),
        ))
        modes(output, second, root, rate, arrayOf(
            Mode(3.0, .022, .145), Mode(4.5, .013, .08), Mode(7.63, .006, .04),
        ))
        // Both arms transfer force to the same mounting cavity, rather than two unrelated hits.
        for (i in first.indices) first[i] += second[i]
        modes(output, first, root, rate, arrayOf(Mode(1.0, .035, .042), Mode(1.5, .025, .022)))
    }

    private fun clay(output: FloatArray, root: Double, rate: Int, random: Random, poweredFrames: Int) {
        val force = FloatArray(output.size)
        contact(force, rate, .002, random.nextDouble(.0025, .0045), random.nextDouble(.90, 1.0), poweredFrames)
        // A membrane and vessel, with no kick-style descending oscillator or sub-bass boost.
        modes(output, force, root, rate, arrayOf(
            Mode(1.0, .073, .145), Mode(1.5, .060, .095),
            Mode(2.0, .042, .054), Mode(2.76, .029, .031), Mode(4.07, .014, .018),
        ))
    }

    private fun vocal(output: FloatArray, root: Double, rate: Int, random: Random, breath: Double, two: Boolean, poweredFrames: Int) {
        val ceiling = min(11_000.0, rate * .40)
        var fundamental = root * if (root < 75.0) 2.0 else 1.0
        while (fundamental * 1.04 > ceiling) fundamental *= .5
        val harmonics = floor(ceiling / (fundamental * 1.04)).toInt().coerceIn(1, 32)
        val weights = DoubleArray(harmonics) { k -> 1.0 / (k + 1.0).pow(1.35) }
        val sourceScale = 1.0 / weights.sum()
        val identity = random.nextDouble(.91, 1.08)
        val bend = random.nextDouble(.012, .025)
        val phaseOffset = random.nextDouble()
        val formants = Array(3) { TractBand(rate) }
        val airLowCoefficient = 1.0 - exp(-2.0 * PI * min(4_500.0, rate * .30) / rate)
        val airHighCoefficient = 1.0 - exp(-2.0 * PI * 350.0 / rate)
        var airLow = 0.0
        var airBase = 0.0
        var chest = 0.0
        val chestCoefficient = 1.0 - exp(-2.0 * PI * min(ceiling, fundamental * 3.5) / rate)
        var phase = phaseOffset
        val update = max(1, rate / 1_000)
        for (i in output.indices) {
            val time = i.toDouble() / rate
            val second = two && time >= .235
            val start = if (second) .235 else .004
            val duration = if (two) { if (second) .225 else .155 } else .218
            val progress = ((time - start) / duration).coerceIn(0.0, 1.0)
            val envelope = syllable(time - start, duration, if (two) .020 else .023, .045)
            val vowel = if (two) { if (second) 1.12 - .14 * progress else .83 + .13 * progress } else .86 + .10 * progress
            if (i % update == 0) {
                formants[0].set(min(ceiling, max(340.0 * identity * vowel, fundamental * 1.22)), 100.0)
                formants[1].set(min(ceiling, max(980.0 * identity / vowel, fundamental * 2.15)), 175.0)
                formants[2].set(min(ceiling, max(2_230.0 * identity, fundamental * 3.10)), 270.0)
            }
            val pitch = fundamental * (1.0 + bend * (if (second) progress - .4 else .5 - progress))
            phase += pitch / rate
            phase -= floor(phase)
            val angle = 2.0 * PI * phase
            val s = sin(angle)
            val c = cos(angle)
            var hs = s
            var hc = c
            var glottal = 0.0
            val powered = i < poweredFrames && envelope > 0.0
            // A tilted glottal flow derivative synthesized from a bounded harmonic series.
            // Recurrence avoids a separate transcendental oscillator for every partial.
            if (powered) {
                for (k in weights.indices) {
                    glottal += weights[k] * hs
                    val next = hs * c + hc * s
                    hc = hc * c - hs * s
                    hs = next
                }
            }
            glottal *= sourceScale
            val noise = if (powered) random.nextDouble(-1.0, 1.0) else 0.0
            airLow += airLowCoefficient * (noise - airLow)
            airBase += airHighCoefficient * (airLow - airBase)
            // A short unvoiced onset separates the second syllable's "h" from its vowel.
            val voicedGate = if (second) smooth((time - start - .009) / .018) else 1.0
            val air = (airLow - airBase) * (.018 + breath * .065)
            val source = if (powered) glottal * voicedGate + air else 0.0
            val throat = .50 * formants[0].process(source) + .27 * formants[1].process(source) + .11 * formants[2].process(source)
            val chestInput = if (powered) .20 * s * voicedGate else 0.0
            chest += chestCoefficient * (chestInput - chest)
            output[i] = (.55 * envelope * (throat + chest) * if (two && !second) .84 else 1.0).toFloat()
        }
    }

    /** Double-precision constant-peak bandpass; coefficients only change at the tract control rate. */
    private class TractBand(private val rate: Int) {
        private var b = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun set(frequency: Double, bandwidth: Double) {
            val angle = 2.0 * PI * frequency / rate
            val alpha = sin(angle) * bandwidth / (2.0 * frequency)
            b = alpha / (1.0 + alpha)
            a1 = -2.0 * cos(angle) / (1.0 + alpha)
            a2 = (1.0 - alpha) / (1.0 + alpha)
        }

        fun process(input: Double): Double {
            val result = b * (input - x2) - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = result
            return result
        }
    }

    private fun smooth(value: Double): Double {
        val v = value.coerceIn(0.0, 1.0)
        return v * v * (3.0 - 2.0 * v)
    }

    private fun syllable(time: Double, duration: Double, attack: Double, release: Double): Double {
        if (time <= 0.0 || time >= duration) return 0.0
        return smooth(time / attack) * smooth((duration - time) / release) * exp(-.65 * time / duration)
    }

    private fun finish(output: FloatArray, rate: Int, energy: Double) {
        // Remove infrasonic contact displacement before fading the finite buffer.
        val pole = exp(-2.0 * PI * 18.0 / rate)
        val attack = max(2, (rate * .001).toInt())
        val release = max(2, (rate * .018).toInt())
        var previousInput = 0.0
        var previousOutput = 0.0
        var sum = 0.0
        var windowSum = 0.0
        for (i in output.indices) {
            val input = output[i].toDouble()
            val high = input - previousInput + pole * previousOutput
            previousInput = input
            previousOutput = high
            val window = smooth(i.toDouble() / attack) * smooth((output.lastIndex - i).toDouble() / release)
            val sample = high * window * energy
            output[i] = sample.toFloat()
            sum += output[i]
            windowSum += window
        }
        // A weighted residual correction keeps zero-valued endpoints and removes buffer DC.
        val correction = sum / windowSum
        var peak = 0.0
        for (i in output.indices) {
            val window = smooth(i.toDouble() / attack) * smooth((output.lastIndex - i).toDouble() / release)
            output[i] = (output[i] - correction * window).toFloat()
            peak = max(peak, abs(output[i].toDouble()))
        }
        // Only reduce exceptional contact coincidences; never raise a quiet source to a target.
        if (peak > .44) {
            val gain = (.44 / peak).toFloat()
            for (i in output.indices) output[i] *= gain
        }
    }
}
