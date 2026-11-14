package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opt-in model-four source gestures, not new material names.
 * DRAW supplies a coherent, pressure-gated rubbing force. LIFT keeps a bounded
 * pressure memory and supplies force only as the contact withdraws. This is a
 * musical pressure analogy, not a mechanical energy or stick-slip simulation.
 * The maintained trajectory is external: neither source reads acoustic state.
 * Existing independent modal banks and their exact periodic solve remain valid.
 */
internal object MeridianGestures {
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val HASH_START = -3750763034362895579L
    private const val HASH_MULTIPLIER = 1099511628211L
    private const val SOURCE_INCREMENT = .00006 // First-listen shape, before house leveling.
    private const val DRAIN_TIME_CONSTANTS = 6.0

    fun supports(voice: MeridianVoice) = voice == MeridianVoice.DRAW || voice == MeridianVoice.LIFT

    /** A convex scalar recurrence. MEMORY lengthens withdrawal as well as bank decay. */
    fun reservoirSeconds(surface: Double, memory: Float): Double = .025 + .12 * surface + .08 * memory

    data class Source(val input: DoubleArray, val initialPressure: Double,
        val finalPressure: Double, val pressureStateError: Double, val peakPressure: Double,
        val peakGate: Double, val gateWork: Double)

    /** One carrier per note, shared by all twelve surfaces; never restarted at contact. */
    fun carrier(voice: MeridianVoice, hz: Double, count: Int, surface: Double,
        velocity: Double, seedContext: Int, touch: Double = 0.0): DoubleArray {
        require(supports(voice) && hz.isFinite() && hz > 0 && count > 0)
        val seed = Dsp.Noise(Dsp.seedFor("MERIDIAN", 4, voice.name, seedContext))
        val phase = seed.next().toDouble() * PI
        val second = (if (voice == MeridianVoice.DRAW) .22 else .10) * surface * (.45 + .55 * velocity) *
            MeridianTouch.harmonicMul(velocity, touch)
        val third = (if (voice == MeridianVoice.DRAW) .07 else .16) * surface * velocity * MeridianTouch.harmonicMul(velocity, touch)
        val scale = 1 / (1 + abs(second) + abs(third))
        return DoubleArray(count) { i ->
            if (i and 4095 == 0) stop()
            val angle = 2 * PI * hz * i / INTERNAL_RATE + phase
            (sin(angle) + second * sin(2 * angle) + third * sin(3 * angle)) * scale
        }
    }

    private fun weight(motion: MeridianMotion.Plan, surface: Int, seconds: Double): Double {
        val at = seconds * MeridianMotion.CONTROL_RATE
        val frame = at.toInt()
        val fraction = at - frame
        return motion.weight(frame, surface) * (1 - fraction) + motion.weight(frame + 1, surface) * fraction
    }

    /**
     * Exact fixed point of c[n+1] = a*c[n] + (1-a)*w[n]. For w in [0,1],
     * the interval [0,1] is invariant and the cycle contracts by a^N. The
     * carrier has whole root periods in this same table, independently of the
     * swing count. The bank then evolves through two real consecutive cycles.
     */
    fun periodicSource(voice: MeridianVoice, motion: MeridianMotion.Plan, surface: Int,
        count: Int, swings: Int, period: Double, reach: Double, velocity: Double,
        surfaceMacro: Double, memory: Float, carrier: DoubleArray): Source {
        require(supports(voice) && carrier.size == count)
        val pressure = DoubleArray(count) { i ->
            if (i and 4095 == 0) stop()
            val phase = ((i.toLong() * swings) % count).toDouble() / count
            weight(motion, surface, period + phase * period)
        }
        val a = exp(-1 / (INTERNAL_RATE * reservoirSeconds(surfaceMacro, memory)))
        var cold = 0.0
        if (voice == MeridianVoice.LIFT) for (w in pressure) cold = a * cold + (1 - a) * w
        val initial = if (voice == MeridianVoice.LIFT) cold / -expm1(count * ln(a)) else 0.0
        return source(voice, pressure, carrier, reach, velocity, a, initial).also {
            check(it.pressureStateError < 1e-9) { "MERIDIAN LIFT pressure did not recur: ${it.pressureStateError}" }
        }
    }

    internal fun source(voice: MeridianVoice, pressure: DoubleArray, carrier: DoubleArray,
        reach: Double, velocity: Double, a: Double, initial: Double,
        releaseGate: ((Int) -> Double)? = null): Source {
        require(pressure.size == carrier.size)
        var state = initial
        var peakPressure = state
        var peakGate = 0.0
        var work = 0.0
        val effort = velocity.pow(1.35) * (.65 + .35 * reach) * SOURCE_INCREMENT
        val input = DoubleArray(pressure.size)
        for (i in pressure.indices) {
            if (i and 4095 == 0) stop()
            val w = pressure[i]
            check(w.isFinite() && w >= 0.0 && w <= 1.0000001) { "MERIDIAN pressure escaped its contact bound" }
            val gate = if (voice == MeridianVoice.LIFT) {
                state = a * state + (1 - a) * w
                maxOf(0.0, state - w)
            } else w
            val shaped = gate * (releaseGate?.invoke(i) ?: 1.0)
            peakPressure = maxOf(peakPressure, state)
            peakGate = maxOf(peakGate, shaped)
            work += shaped * shaped / INTERNAL_RATE
            input[i] = effort * shaped * carrier[i]
        }
        check(peakPressure <= 1.0000001 && peakGate <= 1.0000001)
        return Source(input, initial, state, abs(state - initial), peakPressure, peakGate, work)
    }

    /**
     * New voices have one shared source law in public rendering and diagnostics.
     * LIFT retains a six-time-constant drain after pressure ends; the final 30 ms
     * of that designed source ending tapers work before the existing bank damper.
     * The old fast finite path and all old source arithmetic remain untouched.
     */
    fun probe(voice: MeridianVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        seedContext: Int, activeOverride: Float?, tailOverride: Float?, normalize: Boolean,
        taps: Boolean, fixedAlignment: Boolean, pMax: Double, resetOnContact: Boolean,
        touch: Double = 0.0): Meridian.Probe {
        require(supports(voice))
        val m = Meridian.settled(voice, macros)
        val active = activeOverride?.toDouble() ?: Meridian.activeSeconds(m.getValue("HOLD"))
        val tau = reservoirSeconds(m.getValue("SURFACE").toDouble(), m.getValue("MEMORY"))
        val tail = tailOverride?.toDouble() ?: maxOf(Meridian.tailSeconds(m.getValue("MEMORY")),
            if (voice == MeridianVoice.LIFT) DRAIN_TIME_CONSTANTS * tau + .30 else 0.0)
        val prepared = MeridianMotion.prepare(m.getValue("LATITUDE").toDouble(), m.getValue("LENGTH").toDouble(),
            m.getValue("REACH").toDouble(), active, fixedAlignment = fixedAlignment, pMax = pMax)
        val motion = prepared.plan
        val hz = Meridian.frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val reach = m.getValue("REACH").toDouble()
        val surfaceMacro = m.getValue("SURFACE").toDouble()
        val configs = Meridian.setupsForVoice(voice, hz, surfaceMacro, m.getValue("MEMORY"), reach, velocity.toDouble(), touch)
        val count = ((active + tail) * Dsp.RATE).roundToInt() * Dsp.OVERSAMPLE
        val carrier = carrier(voice, hz, count, surfaceMacro, velocity.toDouble(), seedContext, touch)
        val sum = DoubleArray(count)
        val rawTaps = if (taps) Array(Meridian.SURFACES) { FloatArray(count) } else emptyArray()
        val inputEnergy = DoubleArray(Meridian.SURFACES)
        val inputPeak = DoubleArray(Meridian.SURFACES)
        val outputEnergy = DoubleArray(Meridian.SURFACES)
        val inputHashes = LongArray(Meridian.SURFACES) { HASH_START }
        val roughnessHashes = LongArray(Meridian.SURFACES) { HASH_START }
        val energyAt = DoubleArray((count + 255) / 256)
        val releaseFrame = maxOf(ceil(active * INTERNAL_RATE).toInt(), ((active + tail - .30) * INTERNAL_RATE).toInt())
        val drainEnd = active + DRAIN_TIME_CONSTANTS * tau
        for (j in 0 until Meridian.SURFACES) {
            stop()
            val pressure = DoubleArray(count) { i -> weight(motion, j, i.toDouble() / INTERNAL_RATE) }
            if (pressure.all { it == 0.0 }) continue
            val releaseGate: ((Int) -> Double)? = if (voice == MeridianVoice.LIFT)
                { i -> MeridianMotion.smooth((drainEnd - i.toDouble() / INTERNAL_RATE) / .03) } else null
            val input = source(voice, pressure, carrier, reach, velocity.toDouble(), exp(-1 / (INTERNAL_RATE * tau)), 0.0, releaseGate).input
            val config = configs[j]
            var bank = config.newBank()
            var released = false
            val resetFrames = if (resetOnContact) motion.entries[j].map { (it.seconds * INTERNAL_RATE).roundToInt() }.toSet() else emptySet()
            for (i in 0 until count) {
                if (i and 4095 == 0) stop()
                if (!released && i >= releaseFrame) {
                    for (k in 0 until Meridian.MODES_PER_SURFACE)
                        bank.tune(k, config.frequencies[k], minOf(config.t60s[k], .08))
                    released = true
                }
                if (i in resetFrames) bank = config.newBank(released)
                val increment = input[i]
                if (increment != 0.0) bank.drive(config.participation, increment * INTERNAL_RATE)
                bank.step()
                val y = bank.velocityAlong(config.pickup)
                sum[i] += y
                if (taps) rawTaps[j][i] = y.toFloat()
                inputEnergy[j] += increment * increment
                inputPeak[j] = maxOf(inputPeak[j], abs(increment))
                inputHashes[j] = (inputHashes[j] xor increment.toBits()) * HASH_MULTIPLIER
                outputEnergy[j] += y * y / INTERNAL_RATE
                if (i and 255 == 0) energyAt[i / 256] += bank.energy()
            }
        }
        val raw = FloatArray(count) { sum[it].toFloat() }
        check(raw.all { it.isFinite() } && energyAt.all { it.isFinite() })
        val samples = Meridian.finish(raw, normalize)
        val outputs = if (taps) Array(Meridian.SURFACES) { Meridian.finish(rawTaps[it], false) } else emptyArray()
        val diagnostics = Meridian.Diagnostics(sum.maxOf { abs(it) }, energyAt.maxOrNull() ?: 0.0, hz,
            DoubleArray(Meridian.SURFACES * Meridian.MODES_PER_SURFACE) {
                configs[it / Meridian.MODES_PER_SURFACE].frequencies[it % Meridian.MODES_PER_SURFACE] },
            active, tail, prepared.cacheHit, prepared.prepareNanos, inputEnergy, inputPeak, outputEnergy,
            inputHashes, roughnessHashes, IntArray(Meridian.SURFACES) { motion.entries[it].size }, motion.maxWeightSum)
        return Meridian.Probe(samples, outputs, diagnostics, motion)
    }

    private fun stop() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("MERIDIAN gesture render no longer wanted")
    }
}
