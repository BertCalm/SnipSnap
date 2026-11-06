package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class HydroformVoice { DOME, BULKHEAD }

/**
 * HYDROFORM model 1: the accepted Python R5 sources, dispersed pressure return,
 * dynamic slope folding and explicitly arrested ending. No fluid simulation.
 * Shape constants were auditioned in Python; the native house output is a new
 * listening gate. The hardest BULKHEAD corner is not claimed alias-free at 4x.
 * No random source exists: local filter state and analytic sources make replay pure.
 */
object Hydroform {
    const val MODEL_VERSION = 1
    const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private val ratios = doubleArrayOf(1.0, 1.593, 2.317, 3.711, 5.167)
    private val gains = doubleArrayOf(.85, .46, .34, .23, .16)
    private val decays = doubleArrayOf(.18, .13, .095, .07, .045)

    fun macrosFor(voice: HydroformVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f), MacroSpec("CHARGE", .7f), MacroSpec("STANDOFF", .35f),
        MacroSpec("YIELD", .5f), MacroSpec("ARREST", .65f),
    ) + if (voice == HydroformVoice.DOME) listOf(MacroSpec("HOLD", 0f)) else emptyList()

    fun defaults(voice: HydroformVoice) = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: HydroformVoice, macros: Map<String, Float>) =
        defaults(voice).mapValues { (key, default) ->
            (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
        }
    fun rootMidi(voice: HydroformVoice) = if (voice == HydroformVoice.DOME) 31 else 50
    fun midiFor(voice: HydroformVoice, tune: Float) = rootMidi(voice) + ((tune.coerceIn(0f, 1f) - .5f) * 24).roundToInt()
    fun frequencyFor(voice: HydroformVoice, tune: Float): Double =
        (if (voice == HydroformVoice.DOME) 49.0 else 147.0) * 2.0.pow(((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) - .5) * 2)
    fun isLoop(voice: HydroformVoice, macros: Map<String, Float>) =
        voice == HydroformVoice.DOME && settled(voice, macros).getValue("HOLD") == 1f
    fun drumClassFor(voice: HydroformVoice, macros: Map<String, Float> = emptyMap()) = when {
        isLoop(voice, macros) -> DrumClass.LOOP
        voice == HydroformVoice.DOME -> DrumClass.KICK
        else -> DrumClass.PERC
    }
    fun scramble(voice: HydroformVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)

    /** Stable delayed all-pass, with pole radii .5^(1/delay). No limiter hides feedback growth. */
    private class AllPass(size: Int) {
        private val inputs = DoubleArray(size)
        private val outputs = DoubleArray(size)
        private var head = 0
        fun step(x: Double): Double {
            val y = .5 * x + inputs[head] - .5 * outputs[head]
            inputs[head] = x; outputs[head] = y
            head = (head + 1) % inputs.size
            return y
        }
    }

    /** The source stage is separate so the Python raw parity probe can isolate the port. */
    private fun source(voice: HydroformVoice, t: Double, hz: Double, charge: Double, velocity: Double, rate: Int): Double {
        if (voice == HydroformVoice.DOME) {
            val phase = 2 * PI * hz * (t + 2.5 * .018 * (1 - exp(-t / .018)))
            val env = (1 - exp(-t / (.0006 * (3 - 2 * velocity)))) * exp(-t / .16)
            return sin(phase) * env * (.6 + 2.6 * charge) * (.25 + .75 * velocity)
        }
        var x = 0.0
        for (i in ratios.indices) if (hz * ratios[i] < rate * .4) {
            val tilt = if (i == 0) 1.0 else .2 + .8 * velocity
            val phase = 2 * PI * hz * ratios[i] * (t + .12 * .009 * (1 - exp(-t / .009)))
            x += gains[i] * tilt * sin(phase) * exp(-t / decays[i])
        }
        return x * (1 - exp(-t / (.00025 + .002 * (1 - velocity)))) *
            (.6 + 2.6 * charge) * (.25 + .75 * velocity) / 1.35
    }

    /** Audio before output conditioning, for cross-language and high-rate reference probes. */
    internal fun raw(voice: HydroformVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                     rate: Int = INTERNAL_RATE, frozenSlope: Boolean = false): FloatArray {
        require(rate >= INTERNAL_RATE)
        require(velocity.isFinite() && velocity in 0f..1f)
        val m = settled(voice, macros)
        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val charge = m.getValue("CHARGE").toDouble()
        val standoff = m.getValue("STANDOFF").toDouble()
        val delay = .0005 * 32.0.pow(standoff)
        // Python round is ties-to-even; preserve that convention at delay boundaries.
        val ap = doubleArrayOf(1.0, 1.41, 1.89).map { AllPass(Math.rint(delay * it * rate).toInt().coerceAtLeast(1)) }
        val front = Math.rint(.024 * standoff * rate).toInt()
        val frontBuffer = DoubleArray(front.coerceAtLeast(1))
        val k = tan(PI * 1800 / rate)
        val b = k / (1 + k)
        val a = (k - 1) / (k + 1)
        val threshold = .15 + 1.65 * m.getValue("YIELD")
        val arrest = .75 * m.getValue("ARREST")
        val stop = .65 * (.045 / .65).pow(arrest)
        val ramp = .0015 + .020 * (1 - arrest).pow(2)
        val drive = 1 + 2 * charge
        val x = DoubleArray(rate)
        val activation = DoubleArray(rate)
        var previousX = 0.0; var previousD = 0.0; var detector = 0.0
        var weighted = 0.0; var energy = 0.0
        for (i in x.indices) {
            val t = i.toDouble() / rate
            val dry = source(voice, t, hz, charge, velocity.toDouble(), rate)
            var wet = dry
            for (filter in ap) wet = filter.step(wet)
            if (front > 0) {
                val j = i % front
                val delayed = frontBuffer[j]; frontBuffer[j] = wet; wet = delayed
            }
            x[i] = dry + .95 * wet * exp(-t / .085)
            val d = abs(x[i] - previousX) * rate / (2 * PI * hz)
            detector = b * d + b * previousD - a * detector
            previousX = x[i]; previousD = d
            activation[i] = detector * detector / (detector * detector + threshold * threshold)
            weighted += activation[i] * x[i] * x[i]; energy += x[i] * x[i]
        }
        val mean = weighted / energy.coerceAtLeast(1e-20)
        return FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            val q = if (frozenSlope) mean else activation[i]
            val brittle = tanh(drive * x[i] * 1.5)
            val folded = sin(drive * x[i] * (1 + 4 * q))
            val u = ((t - (stop - ramp)) / ramp).coerceIn(0.0, 1.0)
            (((1 - .9 * q) * brittle + .9 * q * folded) * .5 * (1 + cos(PI * u)))
                .coerceIn(-.94, .94).toFloat()
        }
    }

    /** House filtering and DC stage. Level the retained loop only, not warm-up + loop. */
    internal fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size
        val hp = Dsp.FixedOnePole(20f)
        for (i in out.indices) {
            val x = out[i] - mean.toFloat()
            out[i] = x - hp.lp(x)
        }
        return out
    }

    internal data class LoopEvidence(val loop: FloatArray, val seam: Double, val continuation: Double)
    internal fun loopEvidence(macros: Map<String, Float>, velocity: Float = 1f): LoopEvidence {
        val hit = raw(HydroformVoice.DOME, macros, velocity)
        // Three cycles warm up actual filter state, followed by kept cycle + independent continuation.
        val sequence = FloatArray(INTERNAL_RATE * 10)
        for (start in sequence.indices step INTERNAL_RATE / 4)
            for (i in 0 until min(hit.size, sequence.size - start)) sequence[start + i] += hit[i]
        val conditioned = condition(sequence)
        val start = Dsp.RATE * 6; val length = Dsp.RATE * 2
        val seam = Keys.seamError(conditioned.copyOfRange(0, start + length), start)
        var diff = 0.0; var energy = 0.0
        for (i in 0 until length) {
            val d = conditioned[start + i] - conditioned[start + length + i].toDouble()
            diff += d * d; energy += conditioned[start + length + i].toDouble().pow(2)
        }
        return LoopEvidence(conditioned.copyOfRange(start, start + length), seam, diff / energy.coerceAtLeast(1e-20))
    }

    fun render(voice: HydroformVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        require(velocity.isFinite() && velocity in 0f..1f)
        val loop = isLoop(voice, macros)
        // A zero MIDI velocity is silence, not a softer strike restored by leveling.
        if (velocity == 0f) return Snip(FloatArray(Dsp.RATE * if (loop) 2 else 1), 1, Dsp.RATE)
        val out = if (loop) {
            val evidence = loopEvidence(macros, velocity)
            require(evidence.seam < Keys.MAX_SEAM_ERROR && evidence.continuation < Keys.MAX_SEAM_ERROR)
            evidence.loop
        } else condition(raw(voice, macros, velocity))
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (!loop) Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
}
