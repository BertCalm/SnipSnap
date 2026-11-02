package com.snipsnap.synth

import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fifteen autonomous pendulums and their reciprocal translating support.
 *
 * Time here is normalized mechanical time tau, never audio time. A canonical
 * reference cycle is [REFERENCE_PERIOD] tau units. PACE only traverses this
 * immutable history; pitch, velocity and acoustic settings never enter its RHS.
 * RK4 integrates work as an auxiliary state so a limiter cannot conceal a bad
 * mechanical energy balance. There is no spring, phase correction or reset.
 */
internal object AccordDynamics {
    const val COUNT = 15
    const val STATE_SIZE = 32
    const val DEFAULT_STEPS_PER_CYCLE = 256
    const val DESCRIPTORS_PER_CYCLE = 40
    const val DEFAULT_CYCLES = 48
    const val MAX_CYCLES = 256
    const val REFERENCE_SEED = 20261022L
    const val GLOBAL_D_FLOOR = .0796
    private const val H = .02
    private const val K = .02
    private const val THETA0 = .38
    private const val MAX_RHS_CALLS = 1_048_576
    private const val MIN_CROSSING_SPEED = 1e-10
    val REFERENCE_OMEGA: Double = sqrt(K / .024)
    val REFERENCE_PERIOD: Double = 2 * PI / REFERENCE_OMEGA

    data class Parameters(
        val kappa: Double = 1.0,
        val spread: Double = .01,
        val baseMass: Double = .6,
        val damping: Double = .05,
        /** Zero is available for conservation tests, not a timbral macro. */
        val drive: Double = .1,
    ) {
        init {
            require(kappa.isFinite() && kappa in 0.0..1.0) { "ACCORD invalid coupling $kappa" }
            require(spread.isFinite() && spread in 0.0..0.04) { "ACCORD invalid rate spread $spread" }
            require(baseMass.isFinite() && baseMass in .05..2.4) { "ACCORD invalid platform mass $baseMass" }
            require(damping.isFinite() && damping in 0.0..0.2) { "ACCORD invalid platform damping $damping" }
            require(drive.isFinite() && drive in 0.0..0.1) { "ACCORD invalid sustaining drive $drive" }
        }

        val translatingMass: Double get() = baseMass + COUNT * H
        /** Bound independent of the actual angles or seeded offset realization. */
        val analyticLowerBound: Double
            get() = translatingMass - kappa * kappa * COUNT * H * H / (.024 / ((1 + spread) * (1 + spread)))
    }

    data class Event(
        val cycle: Double,
        val mechanism: Int,
        val direction: Int,
        val canonicalSpeed: Double,
        val ordinal: Long,
    )

    data class Diagnostics(
        val steps: Int,
        val rhsEvaluations: Int,
        val minD: Double,
        val analyticLowerBound: Double,
        val maxAbsTheta: Double,
        val maxAbsOmega: Double,
        val maxAbsBasePosition: Double,
        val maxAbsBaseVelocity: Double,
        val relativeEnergyWorkError: Double,
        val maxMomentumChange: Double,
        val eventCount: Int,
    )

    /** Arrays stay owned by this trace; reads and concurrent cache hits cannot mutate it. */
    class Trace internal constructor(
        val parameters: Parameters,
        val cycles: Int,
        val stepsPerCycle: Int,
        private val states: DoubleArray,
        private val work: DoubleArray,
        sourceEvents: List<Event>,
        val diagnostics: Diagnostics,
    ) {
        val events: List<Event> = Collections.unmodifiableList(ArrayList(sourceEvents))
        private val compatibilityStates = DoubleArray((cycles * DESCRIPTORS_PER_CYCLE + 1) * STATE_SIZE)
        val retainedBytes: Long
            get() = (states.size.toLong() + work.size + compatibilityStates.size) * 8 + events.size * 64L + 256

        init {
            val scratch = DoubleArray(STATE_SIZE)
            for (j in 0..cycles * DESCRIPTORS_PER_CYCLE) {
                stateAt(j.toDouble() / DESCRIPTORS_PER_CYCLE, scratch)
                scratch.copyInto(compatibilityStates, j * STATE_SIZE)
            }
        }

        fun stateAt(cycle: Double): DoubleArray = DoubleArray(STATE_SIZE).also { stateAt(cycle, it) }

        /** Position-Hermite interpolation; its derivative supplies omega and V. */
        fun stateAt(cycle: Double, out: DoubleArray) {
            require(cycle.isFinite() && cycle in 0.0..cycles.toDouble()) { "ACCORD state outside prepared history: $cycle" }
            require(out.size >= STATE_SIZE)
            val position = cycle * stepsPerCycle
            val step = min(floor(position).toInt(), cycles * stepsPerCycle)
            val fraction = position - step
            val offset = step * STATE_SIZE
            if (fraction == 0.0 || step == cycles * stepsPerCycle) {
                states.copyInto(out, 0, offset, offset + STATE_SIZE)
                return
            }
            val next = offset + STATE_SIZE
            val dt = REFERENCE_PERIOD / stepsPerCycle
            for (i in 0 until COUNT) {
                interpolatePosition(states[offset + i], states[next + i], states[offset + COUNT + i],
                    states[next + COUNT + i], dt, fraction, out, i, COUNT + i)
            }
            interpolatePosition(states[offset + 30], states[next + 30], states[offset + 31],
                states[next + 31], dt, fraction, out, 30, 31)
        }

        /**
         * Match the approved readout: interpolate theta/omega from the forty
         * descriptor knots first, then let the caller evaluate nonlinear pressure
         * on its 2 kHz musical grid. Interpolating coarse pressure is different.
         */
        fun compatibilityStateAt(cycle: Double, out: DoubleArray) {
            require(cycle.isFinite() && cycle in 0.0..cycles.toDouble())
            require(out.size >= STATE_SIZE)
            val position = cycle * DESCRIPTORS_PER_CYCLE
            val knot = min(floor(position).toInt(), cycles * DESCRIPTORS_PER_CYCLE)
            val fraction = position - knot
            val offset = knot * STATE_SIZE
            if (fraction == 0.0 || knot == cycles * DESCRIPTORS_PER_CYCLE) {
                compatibilityStates.copyInto(out, 0, offset, offset + STATE_SIZE)
            } else {
                for (i in 0 until STATE_SIZE) {
                    val a = compatibilityStates[offset + i]
                    out[i] = a + (compatibilityStates[offset + STATE_SIZE + i] - a) * fraction
                }
            }
        }

        fun workAtKnot(knot: Int): Double {
            require(knot in work.indices)
            return work[knot]
        }

        /** Same-direction crossings define phase; incomplete edge cycles return NaN. */
        fun phaseOrderAt(cycle: Double, harmonic: Int = 1): Double {
            require(harmonic == 1 || harmonic == 2)
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until COUNT) {
                var before = Double.NaN
                var after = Double.NaN
                for (event in events) {
                    if (event.mechanism != i || event.direction != 1) continue
                    if (event.cycle <= cycle) before = event.cycle else {
                        after = event.cycle
                        break
                    }
                }
                if (!before.isFinite() || !after.isFinite()) return Double.NaN
                val phase = 2 * PI * harmonic * (cycle - before) / (after - before)
                real += cos(phase)
                imaginary += sin(phase)
            }
            return sqrt(real * real + imaginary * imaginary) / COUNT
        }
    }

    /** A model owns precomputed inertias; mutable scratch is private to one preparation. */
    class Model internal constructor(val parameters: Parameters, offsets: DoubleArray) {
        private val inertia = DoubleArray(COUNT) {
            val natural = REFERENCE_OMEGA * (1 + parameters.spread * offsets[it])
            K / (natural * natural)
        }
        private val q = DoubleArray(COUNT)
        private val b = DoubleArray(COUNT)
        var minimumD: Double = Double.POSITIVE_INFINITY
            private set
        val actualLowerBound: Double = parameters.translatingMass - inertia.sumOf { parameters.kappa * parameters.kappa * H * H / it }

        init {
            require(offsets.size == COUNT && offsets.all { it.isFinite() && it in -1.0..1.0 })
            check(inertia.all { it >= .02 })
            check(actualLowerBound >= GLOBAL_D_FLOOR - 1e-12)
        }

        fun inertias(): DoubleArray = inertia.copyOf()

        /** Schur solve of the symmetric 16-by-16 mass matrix, with optional work derivative. */
        fun derivative(state: DoubleArray, out: DoubleArray) {
            require(state.size >= STATE_SIZE && out.size >= STATE_SIZE)
            check(state.all { it.isFinite() }) { "ACCORD non-finite mechanical state" }
            var denominator = parameters.translatingMass
            var base = -parameters.damping * state[31]
            var reaction = 0.0
            var power = -parameters.damping * state[31] * state[31]
            for (i in 0 until COUNT) {
                val theta = state[i]
                val omega = state[COUNT + i]
                val sine = sin(theta)
                val ratio = theta / THETA0
                val torque = inertia[i] * parameters.drive * (1 - ratio * ratio) * omega
                q[i] = parameters.kappa * H * cos(theta)
                b[i] = torque - K * sine
                denominator -= q[i] * q[i] / inertia[i]
                reaction += q[i] * b[i] / inertia[i]
                base += parameters.kappa * H * sine * omega * omega
                power += torque * omega
            }
            check(denominator.isFinite() && denominator >= actualLowerBound - 1e-12) {
                "ACCORD invalid mass matrix: D=$denominator, bound=$actualLowerBound"
            }
            minimumD = min(minimumD, denominator)
            val baseAcceleration = (base - reaction) / denominator
            for (i in 0 until COUNT) {
                out[i] = state[COUNT + i]
                out[COUNT + i] = (b[i] - q[i] * baseAcceleration) / inertia[i]
            }
            out[30] = state[31]
            out[31] = baseAcceleration
            if (out.size > STATE_SIZE) out[STATE_SIZE] = power
            check(out.all { it.isFinite() }) { "ACCORD non-finite mechanical derivative" }
        }

        fun energy(state: DoubleArray): Double {
            val velocity = state[31]
            var result = .5 * parameters.translatingMass * velocity * velocity
            for (i in 0 until COUNT) {
                val omega = state[COUNT + i]
                result += .5 * inertia[i] * omega * omega + parameters.kappa * H * velocity * cos(state[i]) * omega + K * (1 - cos(state[i]))
            }
            return result
        }

        fun momentum(state: DoubleArray): Double {
            var result = parameters.translatingMass * state[31]
            for (i in 0 until COUNT) result += parameters.kappa * H * cos(state[i]) * state[COUNT + i]
            return result
        }
    }

    fun model(parameters: Parameters, seed: Long = REFERENCE_SEED,
              initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022): Model =
        Model(parameters, rateOffsets(seed, initializer))

    fun initialState(parameters: Parameters, seed: Long = REFERENCE_SEED,
                     initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022): DoubleArray {
        val phases = phases(seed, initializer)
        val offsets = rateOffsets(seed, initializer)
        return DoubleArray(STATE_SIZE).also { state ->
            for (i in 0 until COUNT) {
                state[i] = .76 * cos(phases[i])
                state[COUNT + i] = -.76 * REFERENCE_OMEGA * (1 + parameters.spread * offsets[i]) * sin(phases[i])
            }
        }
    }

    fun rateOffsets(seed: Long, initializer: AccordInitializer): DoubleArray {
        if (initializer == AccordInitializer.REFERENCE_SINE_20261022) {
            require(seed == REFERENCE_SEED) { "ACCORD reference initializer requires seed $REFERENCE_SEED" }
            return REFERENCE_OFFSETS.copyOf()
        }
        val random = Dsp.Noise(Dsp.seedFor("ACCORD_MECHANICS", 1, seed.toString(), "rate"))
        val result = DoubleArray(COUNT) { random.next().toDouble().coerceIn(-1.0, 1.0) }
        val mean = result.sum() / COUNT
        var maximum = 0.0
        for (i in result.indices) {
            result[i] -= mean
            maximum = max(maximum, abs(result[i]))
        }
        if (maximum <= 1e-12) return DoubleArray(COUNT)
        for (i in result.indices) result[i] /= maximum
        return result
    }

    private fun phases(seed: Long, initializer: AccordInitializer): DoubleArray {
        if (initializer == AccordInitializer.REFERENCE_SINE_20261022) {
            require(seed == REFERENCE_SEED)
            return REFERENCE_PHASES.copyOf()
        }
        val random = Dsp.Noise(Dsp.seedFor("ACCORD_MECHANICS", 1, seed.toString(), "phase"))
        return DoubleArray(COUNT) { 2 * PI * ((random.next().toDouble() + 1) * .5).coerceIn(0.0, 1 - 1.0 / 16_777_216) }
    }

    fun prepare(
        parameters: Parameters = Parameters(),
        seed: Long = REFERENCE_SEED,
        initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022,
        cycles: Int = DEFAULT_CYCLES,
        stepsPerCycle: Int = DEFAULT_STEPS_PER_CYCLE,
        cancelled: (() -> Boolean)? = { Thread.currentThread().isInterrupted },
    ): Trace = integrate(parameters, seed, initializer, cycles, stepsPerCycle, cancelled, null)

    /** An independent-state entry point for causal and conservation probes. */
    internal fun integrate(
        parameters: Parameters,
        seed: Long,
        initializer: AccordInitializer,
        cycles: Int,
        stepsPerCycle: Int,
        cancelled: (() -> Boolean)?,
        initial: DoubleArray?,
    ): Trace {
        require(cycles in 1..MAX_CYCLES)
        require(stepsPerCycle in 64..1024 && stepsPerCycle.countOneBits() == 1)
        val steps = cycles * stepsPerCycle
        check(steps.toLong() * 4 <= MAX_RHS_CALLS) { "ACCORD mechanical work cap exhausted" }
        if (cancelled?.invoke() == true) throw CancellationException("ACCORD preparation cancelled")
        val model = model(parameters, seed, initializer)
        val state = DoubleArray(STATE_SIZE + 1)
        (initial ?: initialState(parameters, seed, initializer)).also {
            require(it.size == STATE_SIZE && it.all { value -> value.isFinite() })
            it.copyInto(state)
        }
        val states = DoubleArray((steps + 1) * STATE_SIZE)
        val work = DoubleArray(steps + 1)
        state.copyInto(states, 0, 0, STATE_SIZE)
        val initialEnergy = model.energy(state)
        val initialMomentum = model.momentum(state)
        val scale = max(abs(initialEnergy), 1e-12)
        val stages = Array(4) { DoubleArray(STATE_SIZE + 1) }
        val temporary = DoubleArray(STATE_SIZE + 1)
        val previous = DoubleArray(STATE_SIZE + 1)
        val dt = REFERENCE_PERIOD / stepsPerCycle
        val events = ArrayList<Event>()
        val ordinals = LongArray(COUNT)
        val eventCuts = DoubleArray(4)
        var maxAngle = 0.0
        var maxOmega = 0.0
        var maxPosition = abs(state[30])
        var maxVelocity = abs(state[31])
        var energyError = 0.0
        var momentumChange = 0.0
        for (step in 0 until steps) {
            if (step % stepsPerCycle == 0 && cancelled?.invoke() == true) throw CancellationException("ACCORD preparation cancelled")
            state.copyInto(previous)
            model.derivative(state, stages[0])
            for (i in state.indices) temporary[i] = state[i] + .5 * dt * stages[0][i]
            model.derivative(temporary, stages[1])
            for (i in state.indices) temporary[i] = state[i] + .5 * dt * stages[1][i]
            model.derivative(temporary, stages[2])
            for (i in state.indices) temporary[i] = state[i] + dt * stages[2][i]
            model.derivative(temporary, stages[3])
            for (i in state.indices) state[i] += dt / 6 * (stages[0][i] + 2 * stages[1][i] + 2 * stages[2][i] + stages[3][i])
            check(state.all { it.isFinite() }) { "ACCORD non-finite integrated state" }
            state.copyInto(states, (step + 1) * STATE_SIZE, 0, STATE_SIZE)
            work[step + 1] = state[STATE_SIZE]
            energyError = max(energyError, abs(model.energy(state) - initialEnergy - state[STATE_SIZE]) / scale)
            momentumChange = max(momentumChange, abs(model.momentum(state) - initialMomentum))
            for (i in 0 until COUNT) {
                maxAngle = max(maxAngle, max(abs(previous[i]), abs(state[i])))
                maxOmega = max(maxOmega, max(abs(previous[COUNT + i]), abs(state[COUNT + i])))
                extractEvents(previous[i], state[i], previous[COUNT + i], state[COUNT + i], dt,
                    step, stepsPerCycle, i, ordinals, events, eventCuts)
            }
            maxPosition = max(maxPosition, abs(state[30]))
            maxVelocity = max(maxVelocity, abs(state[31]))
        }
        if (cancelled?.invoke() == true) throw CancellationException("ACCORD preparation cancelled")
        events.sortWith(compareBy<Event> { it.cycle }.thenBy { it.mechanism }.thenBy { it.direction }.thenBy { it.ordinal })
        return Trace(parameters, cycles, stepsPerCycle, states, work, events,
            Diagnostics(steps, steps * 4, model.minimumD, model.actualLowerBound, maxAngle, maxOmega,
                maxPosition, maxVelocity, energyError, momentumChange, events.size))
    }

    private fun extractEvents(y0: Double, y1: Double, v0: Double, v1: Double, dt: Double,
                              step: Int, resolution: Int, mechanism: Int, ordinals: LongArray,
                              events: MutableList<Event>, cuts: DoubleArray) {
        val a = 2 * y0 - 2 * y1 + dt * (v0 + v1)
        val b = -3 * y0 + 3 * y1 - dt * (2 * v0 + v1)
        val c = dt * v0
        // Split the cubic into monotone pieces so a same-sign pair cannot hide two passages.
        var count = 1
        cuts[0] = 0.0
        if (abs(a) > 1e-18) {
            val discriminant = b * b - 3 * a * c
            if (discriminant > 0) {
                val root = sqrt(discriminant)
                val r0 = (-b - root) / (3 * a)
                val r1 = (-b + root) / (3 * a)
                if (r0 > 0 && r0 < 1) cuts[count++] = r0
                if (r1 > 0 && r1 < 1) cuts[count++] = r1
            }
        } else if (abs(b) > 1e-18) {
            val r = -c / (2 * b)
            if (r > 0 && r < 1) cuts[count++] = r
        }
        cuts[count++] = 1.0
        java.util.Arrays.sort(cuts, 0, count)
        for (segment in 0 until count - 1) {
            var low = cuts[segment]
            var high = cuts[segment + 1]
            var fLow = ((a * low + b) * low + c) * low + y0
            val fHigh = ((a * high + b) * high + c) * high + y0
            // Each interval owns its right root. Its left root was already processed.
            if (!((fLow < 0 && fHigh >= 0) || (fLow > 0 && fHigh <= 0))) continue
            if (fHigh != 0.0) repeat(44) {
                val middle = (low + high) * .5
                val fMiddle = ((a * middle + b) * middle + c) * middle + y0
                if ((fLow < 0 && fMiddle < 0) || (fLow > 0 && fMiddle > 0)) {
                    low = middle
                    fLow = fMiddle
                } else high = middle
            }
            val u = high
            val velocity = (3 * a * u * u + 2 * b * u + c) / dt
            if (abs(velocity) <= MIN_CROSSING_SPEED) continue
            events += Event((step + u) / resolution, mechanism, if (velocity > 0) 1 else -1,
                abs(velocity), ordinals[mechanism]++)
        }
    }

    private fun interpolatePosition(y0: Double, y1: Double, v0: Double, v1: Double, dt: Double,
                                    u: Double, out: DoubleArray, position: Int, velocity: Int) {
        val a = 2 * y0 - 2 * y1 + dt * (v0 + v1)
        val b = -3 * y0 + 3 * y1 - dt * (2 * v0 + v1)
        val c = dt * v0
        out[position] = ((a * u + b) * u + c) * u + y0
        out[velocity] = (3 * a * u * u + 2 * b * u + c) / dt
    }

    private val REFERENCE_PHASES = doubleArrayOf(
        .8966527134072003, .01371931073871436, 1.032307611146228, 1.6333219162822996,
        .8954696745107658, 1.7364706805937027, 4.686724916365365, 2.881533644331152,
        .984617281081893, 2.197295625035535, 4.92245680526012, .4100143347613006,
        1.7232504142699445, 4.663003291521934, 6.222979243758299,
    )
    private val REFERENCE_OFFSETS = doubleArrayOf(
        .2702849749993048, -.2162914734369595, .8923071623543083, -.8332611591935914,
        -.7394243316245537, -1.0, .7656632647226754, .5644466614602919,
        .21773618789379703, -.3851930196340212, .38047645960622606, -.6053016041020003,
        -.11061557259250432, .4369388347714392, .36223361477558763,
    )
}
