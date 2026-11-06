package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * Smooth rubbing as an averaged regenerative-feedback normal form. The energy
 * law is literal; interpreting its pump/loss as a mineral contact is an analogy.
 * No microscopic stick/slip or elastic contact deformation is simulated.
 * A one-way traction readout drives the same stone family as Cairn's contacts.
 * Every constant here is a normalized design shape, not measured material data.
 */
internal object CairnSing {
    private const val CONTROL_RATE = 2000
    private const val PERIOD = 2.0

    /** Exact flow of dE/dt = 2 a E - 4 beta E² for one constant-pressure step. */
    internal fun advanceEnergy(energy: Double, a: Double, beta: Double, seconds: Double): Double {
        if (abs(a) < 1e-10) return energy / (1 + 4 * beta * energy * seconds)
        val growth = expm1(2 * a * seconds)
        return energy * (1 + growth) / (1 + 2 * beta / a * energy * growth)
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    fun motion(weight: Double, slope: Double, groove: Double, friction: Double, body: Double,
               hold: Double, velocity: Double, held: Boolean, isolated: Boolean): Cairn.Motion {
        val mass = .55 + 1.3 * weight
        val gamma = 3.5 + 5 * friction + 2.5 * (1 - body)
        val loadedDrive = (gamma + 9 + 18 * slope / (.8 + .4 * mass)) * (.8 + .2 * velocity)
        val traction = if (isolated) 0.0 else loadedDrive
        val cap = (.0025 + .004 * weight) * (.65 + .6 * friction) * velocity * velocity / (1 + .4 * groove)
        val beta = (loadedDrive - gamma) / (2 * max(cap, 1e-30))
        val dwell = 1.25 + .85 * hold + .2 * groove
        val lift = .12 + .16 * weight
        val duration = if (held) PERIOD else dwell + lift + .5 + 1.1 * body
        val steps = (duration * CONTROL_RATE).roundToInt()
        val dt = 1.0 / CONTROL_RATE
        val impact = if (held) 0.0 else .0024 * velocity * velocity
        fun pressure(time: Double): Double = if (held)
            1 - .08 * groove + .08 * groove * cos(2 * PI * time / PERIOD)
        else smooth((time - .08) / ((.07 + .16 * weight) * (1.2 - .2 * velocity))) * (1 - smooth((time - dwell) / lift))
        val pressures = List(steps + 1) { i ->
            if (i == steps) if (held) pressure(.5 * dt) else 0.0 else pressure((i + .5) * dt)
        }
        var energy = if (held && isolated) 0.0 else .0001 * velocity * velocity
        val bound = max(energy, max(0.0, traction - gamma) / (2 * beta))
        val nodes = doubleArrayOf(.1127016653792583, .5, .8872983346207417)
        val weights = doubleArrayOf(5.0 / 18, 8.0 / 18, 5.0 / 18)
        var preceding = emptyList<Double>()
        repeat(if (held) 8 else 1) { cycle ->
            val initial = energy
            var supplied = 0.0; var lost = 0.0; var increase = 0.0
            val energies = ArrayList<Double>(steps + 1).also { it += energy }
            val states = mutableListOf(Cairn.State(0.0, energy + impact, 0.0,
                listOf(sqrt(2 * energy), pressures[0], 0.0, 0.0, 0.0)))
            for (i in 0 until steps) {
                val pump = traction * pressures[i]
                val a = pump - gamma
                // Independent positive quadrature of input and loss power,
                // rather than assigning an energy-budget residual to loss.
                for (j in nodes.indices) {
                    val e = advanceEnergy(energy, a, beta, dt * nodes[j])
                    supplied += dt * weights[j] * 2 * pump * e
                    lost += dt * weights[j] * (2 * gamma * e + 4 * beta * e * e)
                }
                val next = advanceEnergy(energy, a, beta, dt)
                increase = max(increase, next - energy)
                energy = next; energies += energy
                check(energy.isFinite() && energy >= 0 && energy <= bound * (1 + 1e-10)) {
                    "CAIRN SING left its analytic energy bound"
                }
                if ((i + 1) % 20 == 0 || i + 1 == steps) {
                    val time = (i + 1) * dt
                    val reserve = if (!held && time < .018) impact else 0.0
                    states += Cairn.State(time, energy + reserve, impact - reserve,
                        listOf(sqrt(2 * energy), pressures[i + 1], time / PERIOD, 0.0, 0.0))
                }
            }
            check(abs(initial + supplied - energy - lost) < 1e-10) { "CAIRN SING work ledger does not close" }
            val scale = max(1e-30, energies.max())
            val residual = if (preceding.size == energies.size)
                energies.indices.maxOf { abs(energies[it] - preceding[it]) } / scale else Double.POSITIVE_INFINITY
            if (!held || cycle >= 2 && residual < 1e-9) {
                val events = if (!held && velocity > 0) listOf(Cairn.Event(.018, 0, 0, impact,
                    sqrt(2 * (impact + initial) / mass), sqrt(2 * initial / mass))) else emptyList()
                return Cairn.Motion(events, states.toList(), initial + impact, energy, impact, lost,
                    increase, 0.0, supplied, if (held) PERIOD else 0.0, if (held) cycle + 1 else 0,
                    if (held) residual else 0.0, emptyList(), Cairn.Rubbing(CONTROL_RATE,
                        energies.toList(), pressures, preceding, gamma, beta, traction, bound, dwell + lift))
            }
            preceding = energies.toList()
        }
        error("CAIRN SING rubbing did not settle within eight continuing cycles")
    }

    fun finiteFrames(rub: Cairn.Rubbing) = ceil((rub.energy.size - 1).toDouble() / rub.rate * Cairn.RATE).toInt()

    fun inspect(m: Map<String, Float>, hz: Double, velocity: Float, startAtContact: Boolean,
                options: Cairn.Options, motion: Cairn.Motion, cacheHit: Boolean, mechanicsMs: Double): Cairn.Render {
        val started = System.nanoTime()
        val rub = requireNotNull(motion.rubbing)
        val rate = Cairn.RATE * options.oversample
        val held = Cairn.isLoop(m)
        val period = (PERIOD * Cairn.RATE).roundToInt()
        val periodInternal = period * options.oversample
        val frequency = if (held) (hz * PERIOD).roundToInt() / PERIOD else hz
        val forceFrames = if (held) periodInternal else finiteFrames(rub) * options.oversample
        val stepAngle = 2 * PI * frequency / rate
        val rotateC = cos(stepAngle); val rotateS = sin(stepAngle)
        val second = (.08 + .3 * m.getValue("GROOVE")) * (.55 + .45 * velocity)
        val third = (.03 + .14 * m.getValue("FRICTION")) * (.4 + .6 * velocity)
        fun traction(energies: List<Double>): FloatArray {
            val result = FloatArray(forceFrames)
            var c = 1.0; var s = 0.0
            for (i in result.indices) {
                val x = i.toDouble() * rub.rate / rate
                val index = x.toInt().coerceAtMost(energies.size - 2)
                val mix = (x - index).coerceIn(0.0, 1.0)
                val energy = energies[index] + mix * (energies[index + 1] - energies[index])
                val pressure = rub.pressure[index] + mix * (rub.pressure[index + 1] - rub.pressure[index])
                // One smooth traction waveform. These harmonics belong to this
                // single rubbing contact, not higher stones or repeated knocks.
                result[i] = (.6 * sqrt(max(0.0, energy)) * pressure *
                    (s + second * 2 * s * c + third * s * (3 - 4 * s * s))).toFloat()
                val old = c; c = rotateC * old - rotateS * s; s = rotateS * old + rotateC * s
            }
            return result
        }
        val forces = traction(rub.energy)
        // The preceding excitation comes from its own continuing energy trace.
        // Convergence is checked before either trace enters the audio stage.
        val precedingForces = if (held) traction(rub.precedingEnergy) else forces
        val width = (.0007 + .0012 * m.getValue("WEIGHT")) * (1.35 - .65 * velocity)
        val pulseFrames = max(8, (width * rate).roundToInt())
        if (!held) for (event in motion.events) {
            val kernel = DoubleArray(pulseFrames) { .5 - .5 * cos(2 * PI * (it + .5) / pulseFrames) }
            val area = kernel.sum() / rate
            val first = (event.seconds * rate).roundToInt()
            for (i in kernel.indices) if (first + i < forces.size)
                forces[first + i] += (.12 * sqrt(event.work) * kernel[i] / area).toFloat()
        }
        val bank = Modes.Bank(6, rate)
        val ring = .45 + 1.1 * m.getValue("BODY")
        val ratios = doubleArrayOf(1.0, 2.0, 3.0, 2.71, 5.8, 1.47)
        val decays = doubleArrayOf(1.0, .8, .65, .45, .25, .6)
        for (i in ratios.indices) bank.tune(i, hz * ratios[i], ring * decays[i])
        val contact = bank.participation(doubleArrayOf(1.0, .65, .45, .22, .08, .15 + .2 * m.getValue("BODY")))
        val pickup = bank.participation(doubleArrayOf(1.0, .75, .5, .18, .06, .25))
        if (held) {
            // The normal-form trajectory has converged already. These are real
            // driven bank steps; only its linear periodic initial state is solved.
            repeat(if (options.fullLoopPreroll) 12 else 1) {
                for (force in precedingForces) { bank.drive(contact, force.toDouble()); bank.step() }
            }
            if (!options.fullLoopPreroll) bank.closeUncoupledCycle(periodInternal)
        }
        val prefix = if (held) period else 0
        val frames = if (held) 2 * period + 1024 else forceFrames / options.oversample
        val raw = FloatArray(frames * options.oversample)
        var maxEnergy = 0.0; var tailIncrease = 0.0; var previousEnergy = 0.0
        val lastForce = if (held) Int.MAX_VALUE else (rub.contactEndSeconds * rate).roundToInt() + 4
        for (i in raw.indices) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                throw java.util.concurrent.CancellationException("CAIRN SING render interrupted")
            val force = if (held) {
                if (i < periodInternal) precedingForces[i] else forces[(i - periodInternal) % periodInternal]
            } else forces.getOrElse(i) { 0f }
            bank.drive(contact, force.toDouble()); bank.step()
            raw[i] = bank.velocityAlong(pickup).toFloat()
            if (i % 32 == 0) {
                val e = bank.energy(); maxEnergy = max(maxEnergy, e)
                if (i > lastForce + 32) tailIncrease = max(tailIncrease, e - previousEnergy)
                previousEnergy = e
            }
        }
        val rawPeak = raw.maxOf { abs(it.toDouble()) }
        val rawRms = sqrt(raw.sumOf { it.toDouble() * it } / raw.size)
        val filtered = raw.copyOf(); Tide.bandLimit(filtered, rate)
        val full = if (options.oversample == Dsp.OVERSAMPLE) Dsp.decimate(filtered, Cairn.RATE)
            else Resampler.resample(Snip(filtered, 1, rate), Cairn.RATE).samples
        val mean = full.average().toFloat(); val hp = Dsp.OnePole(Cairn.RATE)
        for (i in full.indices) { val x = full[i] - mean; full[i] = x - hp.lp(x, 20f) }
        Dsp.levelTo(full, Cairn.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (!held) Dsp.fadeTail(full, rate = Cairn.RATE)
        val firstContact = if (motion.events.isEmpty()) 0 else (.018 * Cairn.RATE).roundToInt()
        val cue = if (held) prefix else if (startAtContact) (firstContact - (.002 * Cairn.RATE).roundToInt()).coerceAtLeast(0) else 0
        val kept = if (held) full.copyOf(prefix + period) else full
        val seam = if (held && velocity > 0) Cairn.silentBoundarySeam(kept, prefix) else null
        if (seam != null) require(seam < Keys.MAX_SEAM_ERROR) {
            "CAIRN SING: real rubbing continuation does not close (seam $seam)"
        }
        val samples = if (cue == 0) kept else kept.copyOfRange(cue, kept.size)
        return Cairn.Render(raw, kept, samples, Cairn.Diagnostics(motion, rawPeak, rawRms, maxEnergy,
            tailIncrease, firstContact, cue, cacheHit, mechanicsMs, (System.nanoTime() - started) / 1e6,
            hz, rate, if (held) prefix else 0, seam))
    }
}
