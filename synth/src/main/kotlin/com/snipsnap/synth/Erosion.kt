package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class ErosionVoice { ARC, CRATER }

/**
 * Charge -> irregular discharge -> delayed collapse -> thinning dust.
 * A bounded energy-reservoir analogy, not a plasma or bubble simulation.
 * The first Python audition was accepted by the owner; this native model
 * retains its parameter laws but uses the house seed/RNG and filters.
 * Constants are sound-design shapes, not physically calibrated quantities.
 */
object Erosion {
    const val MODEL_VERSION = 1
    private const val CLOCK = 8000
    private const val GAIN = .035
    private val RATIOS = doubleArrayOf(1.0, 1.39, 1.91, 2.67)
    private val controls = listOf("TUNE", "VOLTAGE", "FEED", "FLUID", "SLAG")

    fun macrosFor(voice: ErosionVoice): List<MacroSpec> {
        val values = if (voice == ErosionVoice.ARC) listOf(.5f, .65f, .35f, .6f, .08f)
            else listOf(.5f, .3f, .5f, .6f, .65f)
        return controls.zip(values).map { (name, value) -> MacroSpec(name, value) }
    }
    fun defaults(voice: ErosionVoice) = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: ErosionVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, fallback) ->
            macros[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback
        }
    fun scramble(voice: ErosionVoice, random: Random): Map<String, Float> =
        defaults(voice).mapValues { random.nextFloat() }
    private fun map(x: Float, lo: Double, hi: Double) = lo * (hi / lo).pow(x.toDouble())

    data class Event(val seconds: Double, val energy: Double, val variation: Double)
    data class Trace(val events: List<Event>, val reservoir: Double, val charge: Double,
                     val emitted: Double, val supplySeconds: Double) {
        val conservationError get() = abs(1.0 - reservoir - charge - emitted)
    }

    /** Audio controls never alter the random sequence or the charge trajectory. */
    internal fun schedule(voice: ErosionVoice, macros: Map<String, Float>): Trace {
        val m = settled(voice, macros)
        val noise = Dsp.Noise(Dsp.seedFor("EROSION", MODEL_VERSION, voice.name, "schedule"))
        val tau = map(m.getValue("FEED"), .018, .14)
        val threshold = map(m.getValue("VOLTAGE"), .012, .00065) *
            if (voice == ErosionVoice.CRATER) 1.6 else 1.0
        val recoveryTime = map(m.getValue("VOLTAGE"), .0018, .00018)
        var reservoir = 1.0
        var charge = 0.0
        var emitted = 0.0
        var recovery = 0
        var target = threshold * (1.0 + .3 * noise.next())
        val events = ArrayList<Event>()
        for (tick in 0 until ceil(8 * tau * CLOCK).toInt()) {
            val t = tick.toDouble() / CLOCK
            val transfer = min(reservoir, exp(-t / tau) - exp(-(t + 1.0 / CLOCK) / tau))
            reservoir -= transfer
            charge += transfer
            if (recovery > 0) recovery--
            else if (charge >= target) {
                val energy = .9 * charge
                charge -= energy
                emitted += energy
                events += Event(t, energy, noise.next().toDouble())
                recovery = max(1, (CLOCK * recoveryTime * (1 + .25 * noise.next())).roundToInt())
                target = threshold * (1 + .3 * noise.next())
            }
        }
        return Trace(events, reservoir, charge, emitted, 8 * tau)
    }

    /**
     * Evaluate continuous kernels at exact event ages. Rounding each event
     * to a different sample at 4x/8x otherwise changes the experiment itself.
     * Velocity widens the contact and lowers the fluid centers; schedule and
     * energy accounting stay fixed, so soft layers retain the same gesture.
     */
    internal fun source(voice: ErosionVoice, macros: Map<String, Float> = emptyMap(),
                        velocity: Float = 1f, rate: Int = Dsp.RATE * 4): FloatArray {
        require(rate == Dsp.RATE * 4 || rate == Dsp.RATE * 8) { "EROSION supports 4x and 8x probes" }
        val m = settled(voice, macros)
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val trace = schedule(voice, m)
        val frames = ceil((trace.supplySeconds + .12) * Dsp.RATE).toInt()
        val sparks = FloatArray(frames * (rate / Dsp.RATE))
        val body = FloatArray(sparks.size)
        if (v == 0f) return body
        val root = map(m.getValue("TUNE"), 80.0, 320.0)
        val slag = m.getValue("SLAG").toDouble()
        val share = .65 * slag
        val width = map(m.getValue("FLUID"), .00020, .000045) * (1 + 1.5 * (1 - v))
        val sparkNorm = sqrt(width * sqrt(PI) / 2)
        val duration = .025 + .040 * slag
        val decay = duration / 6
        val bendTime = .002 + .004 * slag
        fun collapse(age: Double): Double = sin(2 * PI * root *
            (age + .65 * bendTime * (1 - exp(-age / bendTime)))) *
            exp(-age / decay) * (1 - exp(-age / .0005))
        // One canonical energy reference per configuration, independent of
        // event, amplitude and probe rate. It is not per-hit normalization.
        val referenceRate = Dsp.RATE * 4
        var norm = 0.0
        for (i in 0 until ceil(duration * referenceRate).toInt()) {
            val y = collapse(i.toDouble() / referenceRate)
            norm += y * y / referenceRate
        }
        val bodyNorm = sqrt(norm).coerceAtLeast(1e-12)
        // One lookup per source: the long collapse kernel need not be
        // recalculated for hundreds of events. Interpolate fractional ages.
        val kernel = DoubleArray(ceil(duration * rate).toInt() + 1) { collapse(it.toDouble() / rate) / bodyNorm }
        for (event in trace.events) {
            val gain = GAIN * sqrt(event.energy) * v
            val start = ceil(event.seconds * rate).toInt()
            val end = min(sparks.size, ceil((event.seconds + 8 * width) * rate).toInt())
            for (i in start until end) {
                val z = ((i.toDouble() / rate - event.seconds) - 4 * width) / width
                sparks[i] += (gain * sqrt(1 - share) * -z * exp(-.5 * z * z) / sparkNorm).toFloat()
            }
            if (share > 0) {
                val onset = event.seconds + (.001 + .007 * slag) * (1 + .2 * event.variation)
                val first = ceil(onset * rate).toInt()
                val last = min(body.size, floor((onset + duration) * rate).toInt())
                for (i in first until last) {
                    val position = i - onset * rate
                    val k = position.toInt()
                    val frac = position - k
                    body[i] += (gain * sqrt(share) * (kernel[k] * (1 - frac) + kernel[k + 1] * frac)).toFloat()
                }
            }
        }
        val base = map(m.getValue("FLUID"), 1400.0, 5000.0)
        val filters = RATIOS.map { ratio ->
            Dsp.Biquad().apply { bandpass(min(15000.0, base * ratio * (root / 160).pow(.25))
                .times(.55 + .45 * v).toFloat(), 1f, rate) }
        }
        for (i in body.indices) {
            var value = 0f
            for (filter in filters) value += filter.process(sparks[i]) * .25f
            body[i] += value
        }
        return body
    }

    internal fun finish(raw: FloatArray): FloatArray {
        val buf = raw.copyOf()
        Tide.bandLimit(buf, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(buf, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) { val x = (out[i] - mean).toFloat(); out[i] = x - hp.lp(x, 20f) }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }
    fun render(voice: ErosionVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f) =
        Snip(finish(source(voice, macros, velocity)), channels = 1, sampleRate = Dsp.RATE)

    /** A percussion engine: nominal PERC does not override the actual audio class. */
    fun drumClassFor(voice: ErosionVoice, macros: Map<String, Float> = emptyMap()) =
        Classifier.classify(render(voice, macros)).drumClass
}
