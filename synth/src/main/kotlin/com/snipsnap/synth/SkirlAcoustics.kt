package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One-way acoustic readout of SKIRL's rolling history, ported from the owner's
 * Python audition. Contact azimuth schedules features; the independently read
 * rim phase changes their transmission. No acoustic value feeds the mechanics.
 *
 * Resistance work funds one shared disk/support input budget. A quadratic
 * overlap correction keeps coherent contacts, including the final shudder,
 * inside that budget. These units are squared input amplitude times seconds,
 * not calibrated radiated joules. The finite shudder is an authored musical
 * transition; FAILURE and HORIZON receive only withdrawal of existing input.
 */
internal object SkirlAcoustics {
    const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val ACOUSTIC_WORK_SCALE = 13.0
    private const val MAX_CROSSINGS = 100_000
    private const val TWO_PI = 2.0 * PI

    data class BudgetUse(
        val amplitude: Double,
        val nominalAmplitude: Double,
        val incrementalEnergy: Double,
        val unusedEnergy: Double,
        val crossEnergy: Double,
        val frames: Int,
    )

    data class Event(
        val time: Double,
        val feature: Int,
        val assignedEnergy: Double,
        val incrementalEnergy: Double,
        val amplitude: Double,
        val normal: Double,
        val rimPhase: Double,
    )

    data class Diagnostics(
        val rollingSeconds: Double,
        val terminalSeconds: Double,
        val failureWithdrawalSeconds: Double,
        val restStartSeconds: Double,
        val sourceSeconds: Double,
        val resistanceWork: Double,
        val assignedRollingEnergy: Double,
        val measuredRollingEnergy: Double,
        val remainingMechanicalEnergy: Double,
        val assignedTerminalEnergy: Double,
        val incrementalTerminalEnergy: Double,
        val prefilterEnergy: Double,
        val postfilterEnergy: Double,
        val restExcitationPeak: Float,
        val fineTexture: Boolean,
        val seedContext: Int,
        val rollingEvents: List<Event>,
        val terminalEvents: List<Event>,
        val signedTexture: Boolean = false,
        val contactWidthSeconds: Double = 0.0,
    ) {
        val combinedBudgetSatisfied: Boolean get() =
            prefilterEnergy <= assignedRollingEnergy + assignedTerminalEnergy + 1e-9
    }

    data class Result(val raw: FloatArray, val diagnostics: Diagnostics)

    /** Test/audition view before either modal bank, at the standard 4x rate. */
    data class Sources(val disk: FloatArray, val support: FloatArray, val diagnostics: Diagnostics)

    private val diskModes = listOf(
        Modes.Mode(1f, 1f, 1.10f), Modes.Mode(2f, .25f, .80f),
        Modes.Mode(3.02f, .17f, .62f), Modes.Mode(4.17f, .10f, .49f),
        Modes.Mode(5.61f, .07f, .36f), Modes.Mode(7.29f, .045f, .25f),
    )
    private val supportModes = listOf(
        Modes.Mode(1f, .40f, 1.65f), Modes.Mode(1.52f, .05f, .65f),
        Modes.Mode(2.1f, .09f, .84f), Modes.Mode(2.88f, .055f, .65f),
        Modes.Mode(4.25f, .035f, .42f),
    )

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("SKIRL acoustic render no longer wanted")
    }

    private fun unit(value: Float, fallback: Float): Double =
        (if (value.isFinite()) value else fallback).coerceIn(0f, 1f).toDouble()

    /** Full-pulse normalization survives truncation; a fragment is never boosted. */
    fun unitPulse(widthSeconds: Double, rate: Int = RATE): DoubleArray {
        require(widthSeconds.isFinite() && widthSeconds > 0 && rate > 0)
        val count = max(4, (widthSeconds * rate).roundToInt())
        val pulse = DoubleArray(count) { sin(PI * it / (count - 1)).pow(2) }
        pulse[0] = 0.0
        pulse[count - 1] = 0.0
        val norm = sqrt(pulse.sumOf { it * it } / rate)
        for (i in pulse.indices) pulse[i] /= norm
        return pulse
    }

    /**
     * Add to finite, unfiltered routes. For proposed unit contact p and
     * accumulated x, the added energy is q*a^2 + 2*c*a. Its rationalized positive
     * root avoids cancellation at strong overlap. Both routes share one budget;
     * changing transmission direction cannot duplicate the contact's energy.
     */
    fun addBudgetedPulse(
        routes: Array<DoubleArray>, pulse: DoubleArray, start: Int, budget: Double,
        diskWeight: Double, supportWeight: Double, rate: Int = RATE,
    ): BudgetUse {
        require(routes.size == 2 && routes[0].size == routes[1].size && rate > 0)
        require(budget.isFinite() && budget >= 0)
        require(diskWeight.isFinite() && supportWeight.isFinite() &&
            diskWeight in -1.0..1.0 && supportWeight in -1.0..1.0)
        val norm = sqrt(diskWeight * diskWeight + supportWeight * supportWeight)
        require(norm > 0)
        val fullEnergy = pulse.sumOf { require(it.isFinite()); it * it } / rate
        require(abs(fullEnergy - 1.0) <= 2e-7) { "Contact pulse must have unit energy" }
        val nominal = sqrt(budget)
        val left = max(0, start)
        val right = min(routes[0].size.toLong(), start.toLong() + pulse.size).toInt()
        if (budget == 0.0 || right <= left)
            return BudgetUse(0.0, nominal, 0.0, budget, 0.0, 0)
        val weights = doubleArrayOf(diskWeight / norm, supportWeight / norm)
        var q = 0.0
        var c = 0.0
        for (route in 0..1) for (i in left until right) {
            val previous = routes[route][i]
            require(previous.isFinite())
            val p = weights[route] * pulse[i - start]
            q += p * p
            c += previous * p
        }
        q /= rate
        c /= rate
        if (q == 0.0) return BudgetUse(0.0, nominal, 0.0, budget, c, 0)
        val discriminant = sqrt(c * c + q * budget)
        val root = if (c >= 0.0) budget / (discriminant + c) else (discriminant - c) / q
        val amplitude = min(nominal, root)
        for (route in 0..1) for (i in left until right)
            routes[route][i] += amplitude * weights[route] * pulse[i - start]
        val incremental = q * amplitude * amplitude + 2 * c * amplitude
        return BudgetUse(amplitude, nominal, incremental, max(0.0, budget - incremental), c, right - left)
    }

    /** Crossings are ordered in time even when azimuth reverses or wraps past pi. */
    fun contactEvents(times: DoubleArray, phase: DoubleArray, featuresPerRevolution: Int = 1): DoubleArray {
        require(times.size == phase.size && times.isNotEmpty() && featuresPerRevolution in 1..4)
        val events = ArrayList<Double>()
        events.add(times[0])
        val period = TWO_PI / featuresPerRevolution
        fun add(i: Int, level: Long) {
            val fraction = (level * period - phase[i]) / (phase[i + 1] - phase[i])
            val event = times[i] + fraction * (times[i + 1] - times[i])
            if (event > events.last() + 1e-9) events.add(event)
            check(events.size <= MAX_CROSSINGS) { "SKIRL contact readout exceeded its work bound" }
        }
        for (i in 0 until times.lastIndex) {
            if (i and 1023 == 0) interrupted()
            require(times[i].isFinite() && phase[i].isFinite() && times[i + 1] > times[i])
            val a = phase[i]
            val b = phase[i + 1]
            require(b.isFinite())
            if (b > a) {
                val first = floor(a / period).toLong() + 1
                val last = floor(b / period).toLong()
                check(last - first < MAX_CROSSINGS) { "Unresolved SKIRL phase increment" }
                for (level in first..last) add(i, level)
            } else if (b < a) {
                val first = ceil(a / period).toLong() - 1
                val last = ceil(b / period).toLong()
                check(first - last < MAX_CROSSINGS) { "Unresolved SKIRL phase increment" }
                for (level in first downTo last) add(i, level)
            }
        }
        return events.toDoubleArray()
    }

    private fun at(times: DoubleArray, values: DoubleArray, time: Double): Double {
        val index = times.binarySearch(time)
        if (index >= 0) return values[index]
        val upper = (-index - 1).coerceIn(1, times.lastIndex)
        val lower = upper - 1
        val fraction = ((time - times[lower]) / (times[upper] - times[lower])).coerceIn(0.0, 1.0)
        return values[lower] + fraction * (values[upper] - values[lower])
    }

    private fun energy(routes: Array<DoubleArray>, rate: Int): Double =
        routes.sumOf { row -> row.sumOf { it * it } } / rate

    private fun energy(disk: FloatArray, support: FloatArray, rate: Int): Double {
        var sum = 0.0
        for (i in disk.indices) sum += disk[i].toDouble() * disk[i] + support[i].toDouble() * support[i]
        return sum / rate
    }

    /** Periodic body-coordinate acoustic polarity; event timing is unchanged. */
    private class RimPolarity(seed: Int) {
        private val noise = Dsp.Noise(seed)
        private val values = FloatArray(1024) { noise.next() }
        fun at(rimPhase: Double): Double {
            val turns = rimPhase / TWO_PI
            val position = (turns - floor(turns)) * values.size
            val lower = floor(position).toInt().coerceIn(0, values.lastIndex)
            val fraction = position - lower
            val value = values[lower] * (1 - fraction) + values[(lower + 1) % values.size] * fraction
            return if (value >= 0.0) 1.0 else -1.0
        }
    }

    /**
     * No random scheduler or noise floor. Optional signed transmission samples a
     * periodic seeded rim map at the measured contact events. It decorrelates
     * the pressure comb so a low acoustic root can ring instead of following a
     * contact harmonic. This is acoustic readout, never signed contact force.
     */
    fun sources(
        trace: SkirlDynamics.Trace, edge: Float, plate: Float, collapse: Float,
        velocity: Float, seed: Int, fineTexture: Boolean = true, rate: Int = RATE,
        signedTexture: Boolean = false,
        fundamentalHz: Double? = null,
    ): Sources {
        interrupted()
        require(rate == RATE || rate == RATE * 2) { "SKIRL supports its house rate and an 8x audit reference" }
        require(fundamentalHz == null || fundamentalHz.isFinite() && fundamentalHz > 0)
        val edgeValue = unit(edge, .4f)
        val plateValue = unit(plate, .5f)
        val collapseValue = unit(collapse, 0f)
        val touch = unit(velocity, 1f)
        val times = trace.times
        require(times.isNotEmpty())
        val onset = times.last()
        require(onset.isFinite() && onset >= 0 && onset <= 60)
        val success = trace.exit == SkirlDynamics.Exit.TERMINAL
        // An immediate solver failure can contain only the launch snapshot.
        // It has no work-funded phase cell and therefore produces quiet withdrawal.
        val lastRate = if (times.size > 1) {
            val lastDt = times.last() - times[times.lastIndex - 1]
            max(abs((trace.contactPhases.last() - trace.contactPhases[trace.contactPhases.lastIndex - 1]) /
                (TWO_PI * lastDt)), 1.0)
        } else 1.0
        val terminalDuration = if (success) (2.6 / lastRate).coerceIn(.040, .200) else .040
        val restTime = onset + terminalDuration
        // High PLATE lengthens the support's free decay, so retain its full t60.
        val decayScale = 2.0.pow(2.0 * (plateValue - .5))
        val tail = max(2.0, 1.65 * decayScale)
        val frames = ceil((restTime + tail) * rate).toInt()
        val restFrame = (restTime * rate).roundToInt()
        // REST is forced to zero after the causal input filters. Their later
        // state cannot affect an earlier sample, so do not allocate or filter
        // the discarded free-decay tail. Modal output still retains that tail.
        val routes = Array(2) { DoubleArray(restFrame) }
        val polarity = if (signedTexture) RimPolarity(seed) else null
        // The audition supplies the energy/hardness law. A softer touch spends
        // less acoustic work and widens the note-aware contact below; the
        // mechanical launch is reused. The Kotlin readout is not audio-identical
        // to the Python audition's fixed millisecond widths.
        // House leveling later normalizes overall level: velocity zones carry
        // timbre here, while the MPC owns their playback level (Velocity.kt).
        // A fixed millisecond mallet can place a high note beyond the Hann
        // kernel's first spectral zero. Scale both endpoints to the acoustic
        // period, retaining EDGE's full range and the softer touch's width.
        // This changes acoustic hardness only; no mechanical event is moved.
        val softWidth = if (fundamentalHz == null) .006 else min(.006, .75 / fundamentalHz)
        val hardWidth = if (fundamentalHz == null) .0007 else min(.0007, .20 / fundamentalHz)
        val width = softWidth * (hardWidth / softWidth).pow(edgeValue) * (1.6 - .6 * touch)
        val pulse = unitPulse(width, rate)
        val finePulse = unitPulse(width * .65, rate)
        val coarseEvents = contactEvents(times, trace.contactPhases)
        val fineEvents = if (fineTexture) contactEvents(times, trace.contactPhases, 4) else doubleArrayOf()
        val mg = trace.params.mass * trace.params.gravity
        var assigned = 0.0
        val rolling = ArrayList<Event>()
        fun add(event: Double, budget: Double, feature: Int) {
            val rim = at(times, trace.rimPhases, event)
            val azimuth = at(times, trace.contactPhases, event)
            val normal = at(times, trace.normals, event)
            val sign = polarity?.at(rim) ?: 1.0
            val use = addBudgetedPulse(routes, if (feature == 0) pulse else finePulse,
                (event * rate).roundToInt(), budget,
                sign * (.82 + .18 * cos(rim + .2)), sign * .85 * (.82 + .18 * cos(azimuth + .9)), rate)
            assigned += budget
            rolling.add(Event(event, feature, budget, use.incrementalEnergy, use.amplitude, normal, rim))
        }
        var fineIndex = 0
        for (i in coarseEvents.indices) {
            interrupted()
            val event = coarseEvents[i]
            val next = if (i < coarseEvents.lastIndex) coarseEvents[i + 1] else onset
            if (next <= event) continue
            val work = at(times, trace.resistanceWork, next) - at(times, trace.resistanceWork, event)
            val load = (at(times, trace.normals, event) / (1.3 * mg)).coerceIn(0.0, 1.0)
            val budget = ACOUSTIC_WORK_SCALE * max(work, 0.0) * load * touch * touch
            if (!fineTexture) {
                add(event, budget, 0)
                continue
            }
            val degrees = at(times, trace.alphas, event) * 180 / PI
            val aperture = ((25.0 - degrees) / 20.0).coerceIn(0.0, 1.0)
            val exposed = aperture * aperture * (3 - 2 * aperture)
            val fineShare = (.15 + .60 * edgeValue) * exposed
            add(event, budget * (1 - fineShare), 0)
            while (fineIndex < fineEvents.size && fineEvents[fineIndex] <= event + 1e-8) fineIndex++
            var feature = 1
            while (fineIndex < fineEvents.size && fineEvents[fineIndex] < next - 1e-8) {
                if (feature <= 3) add(fineEvents[fineIndex], budget * fineShare / 3, feature++)
                fineIndex++
            }
        }
        val rollingEnergy = energy(routes, rate)
        val remaining = max(trace.remainingEnergy.last(), 0.0)
        val terminalBudget = if (success) min(ACOUSTIC_WORK_SCALE * remaining *
            (.10 + .08 * collapseValue) * touch * touch, .05 * assigned) else 0.0
        val terminal = ArrayList<Event>()
        if (success) {
            val weights = doubleArrayOf(1.0, .65, .45, .30, .18)
            val weightEnergy = weights.sumOf { it * it }
            val offsets = doubleArrayOf(0.0, .4, .9, 1.6, 2.2)
            val offsetScale = min(1.0, max(terminalDuration - width - .010, 0.0) / (offsets.last() / lastRate))
            val terminalPulse = unitPulse(width * (.60 + .30 * collapseValue), rate)
            val sign = polarity?.at(trace.rimPhases.last()) ?: 1.0
            for (i in weights.indices) {
                val event = onset + offsets[i] / lastRate * offsetScale
                val budget = terminalBudget * weights[i] * weights[i] / weightEnergy
                val use = addBudgetedPulse(routes, terminalPulse, (event * rate).roundToInt(), budget, sign, sign * .7, rate)
                terminal.add(Event(event, i, budget, use.incrementalEnergy, use.amplitude,
                    trace.normals.last(), trace.rimPhases.last()))
            }
        }
        val beforeFilter = energy(routes, rate)
        val diskInput = FloatArray(restFrame) { routes[0][it].toFloat() }
        val supportInput = FloatArray(restFrame) { routes[1][it].toFloat() }
        // Sixth-order 8 kHz Butterworth input filtering, using the house's
        // fixed-coefficient TPT sections. Oversampling alone is insufficient.
        for (route in listOf(diskInput, supportInput)) {
            for (damping in floatArrayOf(1.9318517f, 1.4142136f, .5176381f)) {
                interrupted()
                Dsp.TptSvf(rate).lowPassInPlace(route, 8000f, damping)
            }
            val withdrawal = min((.010 * rate).roundToInt(), restFrame)
            for (i in 0 until withdrawal) route[restFrame - withdrawal + i] *=
                (1.0 - i.toDouble() / (withdrawal - 1).coerceAtLeast(1)).toFloat()
        }
        val disk = diskInput.copyOf(frames)
        val support = supportInput.copyOf(frames)
        val diagnostics = Diagnostics(onset, if (success) terminalDuration else 0.0,
            if (success) 0.0 else terminalDuration, restTime, frames.toDouble() / rate,
            trace.resistanceWork.last(), assigned, rollingEnergy, remaining, terminalBudget,
            beforeFilter - rollingEnergy, beforeFilter, energy(diskInput, supportInput, rate), 0f,
            fineTexture, seed, rolling.toList(), terminal.toList(), signedTexture, width)
        check(diagnostics.combinedBudgetSatisfied) { "SKIRL contact input exceeded its work proxy" }
        return Sources(disk, support, diagnostics)
    }

    fun render(
        trace: SkirlDynamics.Trace, hz: Double, edge: Float, plate: Float,
        collapse: Float, velocity: Float, seed: Int, fineTexture: Boolean = true, rate: Int = RATE,
        signedTexture: Boolean = false,
    ): Result {
        require(hz.isFinite() && hz > 0 && hz < Dsp.RATE / 2.0)
        val source = sources(trace, edge, plate, collapse, velocity, seed, fineTexture, rate, signedTexture,
            fundamentalHz = hz)
        val material = unit(plate, .5f) - .5
        val scale = 1.6.pow(material)
        val damping = 2.0.pow(2 * material)
        val support = supportModes.mapIndexed { i, mode ->
            // The shared root stays fixed. Size moves the remaining spacings,
            // while correlated damping/transmission change the support itself.
            mode.copy(ratio = if (i == 0) 1f else (mode.ratio / scale).toFloat(),
                t60 = (mode.t60 * damping).toFloat())
        }
        fun compensated(modes: List<Modes.Mode>) = modes.map { mode ->
            mode.copy(gain = (mode.gain * sin(TWO_PI * hz * mode.ratio / rate)).toFloat())
        }
        interrupted()
        // Modes.ring intentionally leaves the sin(theta) factor to callers.
        // Applying it per mode reproduces the heard Python compensation without
        // per-event normalization or a late-stage safety limiter.
        val raw = Modes.ring(source.disk, hz.toFloat(), compensated(diskModes), rate)
        interrupted()
        val plateRaw = Modes.ring(source.support, hz.toFloat(), compensated(support), rate)
        val transmission = .30 * 3.0.pow(material)
        val direct = 1.0 - .15 * material
        for (i in raw.indices) {
            if (i and 16383 == 0) interrupted()
            raw[i] = (raw[i] * direct + plateRaw[i] * transmission).toFloat()
            check(raw[i].isFinite()) { "SKIRL acoustic state became nonfinite" }
        }
        return Result(raw, source.diagnostics)
    }
}
