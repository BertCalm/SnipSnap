package com.snipsnap.synth

import org.apache.commons.math3.ode.FirstOrderDifferentialEquations
import org.apache.commons.math3.ode.nonstiff.DormandPrince853Integrator
import org.apache.commons.math3.ode.sampling.StepHandler
import org.apache.commons.math3.ode.sampling.StepInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conservative, independent double pendulums for the finite SPLAY prototype.
 *
 * Model v1 fixes m1=m2=L1=L2=g=1. Angles are absolute, unwrapped, and measured from
 * downward vertical. Apache Commons Math 3.6.1's Dormand-Prince 8(5,3) integrator
 * supplies dense output; gravity is never integrated at the audio sample rate.
 * No energy correction, forcing, audio feedback, or per-participant random state
 * enters this model. The exact-zero gap case reuses one computed history.
 */
object SplayTrajectoryBank {
    const val MODEL_VERSION = 1
    const val DT_SECONDS = 0.001
    const val MAX_MEMBERS = 100
    const val MAX_SECONDS = 20.0
    const val MAX_SIMULATION_SECONDS = 500.0
    // The all-max 100-member, 210-simulation-second bank exhausted the old 8m cap at ID 94.
    const val MAX_EVALUATIONS = 12_000_000
    private const val MAX_MEMBER_EVALUATIONS = 500_000
    private const val MAX_PACKED_DOUBLES = 8_000_400L
    private const val ENERGY_SCALE = 3.0 // (m1+m2)gL1 + m2gL2; not |E0|, which can approach zero.

    data class SeparationPoint(
        val seconds: Double,
        val simulationSeconds: Double,
        val rmsStateDistance: Double,
        val maxStateDistance: Double,
        val rmsEndpointDistance: Double,
    )

    /**
     * [members] is packed per ID: frame*4 + theta1/theta2/omega1/omega2.
     * Snapshots are at [dtSeconds] in musical time, with a final endpoint guard.
     * Treat the arrays as immutable; identical members may share an array.
     */
    class Bank internal constructor(
        val dtSeconds: Double,
        val frames: Int,
        val seconds: Double,
        val speed: Double,
        val members: Array<DoubleArray>,
        val maxRelativeEnergyDrift: Double,
        val evaluations: Int,
        val separation: List<SeparationPoint>,
    ) {
        fun state(member: Int, seconds: Double): DoubleArray =
            DoubleArray(4).also { sample(member, seconds, it) }

        /** Allocation-free interpolation; times outside the gesture clamp to its endpoints. */
        fun sample(member: Int, seconds: Double, target: DoubleArray) {
            require(member in members.indices) { "Unknown Splay participant $member" }
            require(seconds.isFinite()) { "Splay sample time must be finite" }
            require(target.size >= 4) { "Splay state target needs four components" }
            interpolate(members[member], frames, seconds.coerceIn(0.0, this.seconds), target)
        }
    }

    /**
     * Deterministic initial-condition family followed on each member's own adaptive mesh.
     * [gapDegrees] is adjacent angular spacing, not a random seed. [speed] maps musical
     * seconds to simulation seconds uniformly for the whole bank.
     *
     * [maxEvaluations] includes all independently integrated members. It permits a
     * smaller diagnostic work budget but cannot raise the production hard ceiling.
     * Invalid controls throw [IllegalArgumentException]; numerical/work failures throw
     * [IllegalStateException] so the renderer can stop fresh excitation safely.
     */
    fun build(
        energy: Double,
        gapDegrees: Double,
        speed: Double,
        seconds: Double,
        count: Int,
        relativeTolerance: Double = 1e-11,
        maxEvaluations: Int = MAX_EVALUATIONS,
    ): Bank {
        require(energy.isFinite() && energy in 0.0..1.0) { "Splay ENERGY must be in 0..1" }
        require(gapDegrees.isFinite() && gapDegrees in 0.0..0.1) { "Splay gap must be in 0..0.1 degrees" }
        require(speed.isFinite() && speed > 0.0) { "Splay speed must be positive and finite" }
        require(seconds.isFinite() && seconds > 0.0 && seconds <= MAX_SECONDS) {
            "Splay gesture must be positive and at most $MAX_SECONDS seconds"
        }
        require(count in 1..MAX_MEMBERS) { "Splay population must be in 1..$MAX_MEMBERS" }
        require(relativeTolerance.isFinite() && relativeTolerance in 1e-14..1e-6) {
            "Splay relative tolerance must be in 1e-14..1e-6"
        }
        require(maxEvaluations in 1..MAX_EVALUATIONS) { "Invalid Splay evaluation budget" }

        val frames = ceil(seconds / DT_SECONDS).toInt() + 1
        val simulationEnd = (frames - 1) * DT_SECONDS * speed
        require(simulationEnd.isFinite() && simulationEnd <= MAX_SIMULATION_SECONDS) {
            "Splay simulation horizon exceeds $MAX_SIMULATION_SECONDS seconds"
        }
        val uniqueCount = if (gapDegrees == 0.0) 1 else count
        require(frames.toLong() * 4 * uniqueCount <= MAX_PACKED_DOUBLES) {
            "Splay trajectory storage exceeds the finite prototype memory cap"
        }

        // Degrees enter the physical model here, once. IDs never depend on population.
        val delta = gapDegrees * (PI / 180.0)
        val angle = releaseAngle(energy)
        var totalEvaluations = 0
        var maxDrift = 0.0
        val unique = Array(uniqueCount) { member ->
            val initial = doubleArrayOf(angle, angle + offsetFor(member) * delta, 0.0, 0.0)
            val initialEnergy = energyOf(initial)
            val packed = DoubleArray(frames * 4)
            initial.copyInto(packed)
            var nextFrame = 1
            val remaining = min(MAX_MEMBER_EVALUATIONS, maxEvaluations - totalEvaluations)
            check(remaining > 0) { "Splay trajectory bank exhausted its evaluation budget" }
            val integrator = DormandPrince853Integrator(
                1e-12, 0.25, relativeTolerance * 0.01, relativeTolerance,
            )
            integrator.maxEvaluations = remaining
            integrator.addStepHandler(object : StepHandler {
                override fun init(t0: Double, y0: DoubleArray, t: Double) = Unit

                override fun handleStep(interpolator: StepInterpolator, isLast: Boolean) {
                    while (nextFrame < frames) {
                        val simulationTime = nextFrame * DT_SECONDS * speed
                        if (simulationTime > interpolator.currentTime && !isLast) break
                        // Clamp only roundoff at the final endpoint, never a missed earlier step.
                        check(simulationTime <= interpolator.currentTime + 1e-9) {
                            "Splay dense output did not reach its requested endpoint"
                        }
                        interpolator.interpolatedTime = min(simulationTime, interpolator.currentTime)
                        val state = interpolator.interpolatedState
                        check(state.all { it.isFinite() }) { "Splay solver produced a non-finite state" }
                        state.copyInto(packed, nextFrame * 4)
                        maxDrift = max(maxDrift, abs(energyOf(state) - initialEnergy) / ENERGY_SCALE)
                        nextFrame++
                    }
                }
            })
            try {
                integrator.integrate(equations, 0.0, initial, simulationEnd, DoubleArray(4))
                check(nextFrame == frames) { "Splay dense output left incomplete trajectory frames" }
            } catch (failure: RuntimeException) {
                throw IllegalStateException(
                    "Splay participant $member failed within its $remaining-evaluation budget: ${failure.message}",
                    failure,
                )
            }
            totalEvaluations += integrator.evaluations
            packed
        }
        val members = Array(count) { unique[if (gapDegrees == 0.0) 0 else it] }
        return Bank(
            DT_SECONDS, frames, seconds, speed, members, maxDrift, totalEvaluations,
            measureSeparation(members, frames, seconds, speed),
        )
    }

    internal fun releaseAngle(energy: Double): Double = 0.15 + 2.65 * energy

    internal fun offsetFor(member: Int): Int {
        require(member in 0 until MAX_MEMBERS)
        return if (member % 2 == 1) (member + 1) / 2 else -member / 2
    }

    internal fun energyOf(y: DoubleArray): Double {
        val d = y[0] - y[1]
        return y[2] * y[2] + 0.5 * y[3] * y[3] + y[2] * y[3] * cos(d) -
            2.0 * cos(y[0]) - cos(y[1])
    }

    internal fun derivatives(y: DoubleArray, target: DoubleArray) {
        check(y.all { it.isFinite() }) { "Non-finite Splay integration input" }
        val d = y[0] - y[1]
        val s = sin(d)
        val c = cos(d)
        val b1 = -s * y[3] * y[3] - 2.0 * sin(y[0])
        val b2 = s * y[2] * y[2] - sin(y[1])
        val determinant = 2.0 - c * c // in [1,2], so the mass matrix stays positive definite.
        target[0] = y[2]
        target[1] = y[3]
        target[2] = (b1 - c * b2) / determinant
        target[3] = (2.0 * b2 - c * b1) / determinant
        check(target.all { it.isFinite() }) { "Non-finite Splay acceleration" }
    }

    private val equations = object : FirstOrderDifferentialEquations {
        override fun getDimension(): Int = 4
        override fun computeDerivatives(t: Double, y: DoubleArray, yDot: DoubleArray) = derivatives(y, yDot)
    }

    private fun interpolate(packed: DoubleArray, frames: Int, seconds: Double, target: DoubleArray) {
        val position = (seconds / DT_SECONDS).coerceIn(0.0, (frames - 1).toDouble())
        val frame = position.toInt().coerceAtMost(frames - 2)
        val fraction = position - frame
        val first = frame * 4
        for (component in 0..3) {
            val a = packed[first + component]
            target[component] = a + fraction * (packed[first + 4 + component] - a)
        }
    }

    private fun measureSeparation(
        members: Array<DoubleArray>, frames: Int, seconds: Double, speed: Double,
    ): List<SeparationPoint> {
        val reference = DoubleArray(4)
        val state = DoubleArray(4)
        fun point(time: Double): SeparationPoint {
            interpolate(members[0], frames, time, reference)
            val referenceX = sin(reference[0]) + sin(reference[1])
            val referenceY = -cos(reference[0]) - cos(reference[1])
            var stateSum = 0.0
            var endpointSum = 0.0
            var maximum = 0.0
            for (member in members) {
                interpolate(member, frames, time, state)
                val a = 2.0 * sin(0.5 * (state[0] - reference[0]))
                val b = 2.0 * sin(0.5 * (state[1] - reference[1]))
                val u = state[2] - reference[2] // velocity scale sqrt(g/L)=1.
                val v = state[3] - reference[3]
                val distanceSquared = a * a + b * b + u * u + v * v
                stateSum += distanceSquared
                maximum = max(maximum, sqrt(distanceSquared))
                val x = sin(state[0]) + sin(state[1]) - referenceX
                val y = -cos(state[0]) - cos(state[1]) - referenceY
                endpointSum += x * x + y * y
            }
            return SeparationPoint(
                time, time * speed, sqrt(stateSum / members.size), maximum,
                sqrt(endpointSum / members.size),
            )
        }
        val result = mutableListOf<SeparationPoint>()
        var frame = 0
        while (frame * DT_SECONDS < seconds) {
            result += point(frame * DT_SECONDS)
            frame += 50 // 20 Hz diagnostic trace; full-resolution states remain available above.
        }
        result += point(seconds)
        return result
    }
}
