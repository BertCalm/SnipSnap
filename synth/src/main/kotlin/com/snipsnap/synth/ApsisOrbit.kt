package com.snipsnap.synth

import kotlin.math.*

/** Focus-based Kepler geometry. Audio exchange never changes these trajectories. */
object ApsisOrbit {
    const val TAU = 2.0 * PI
    const val MAX_ITERATIONS = 80
    const val ANGLE_TOLERANCE = 2e-14
    const val RESIDUAL_TOLERANCE = 1e-14

    data class Elements(
        val a: Double,
        val e: Double,
        val period: Double,
        val mean0: Double = 0.0,
        val orientation: Double = 0.0,
    ) {
        init {
            require(a.isFinite() && a > 0.0) { "Apsis semimajor axis must be positive and finite" }
            require(e.isFinite() && e >= 0.0 && e < 1.0) { "Apsis eccentricity must be in [0, 1)" }
            require(period.isFinite() && period > 0.0) { "Apsis period must be positive and finite" }
            require(mean0.isFinite() && orientation.isFinite()) { "Apsis orbit phases must be finite" }
        }
        val b: Double get() = a * sqrt((1.0 - e) * (1.0 + e))
    }

    data class Point(
        val x: Double,
        val y: Double,
        val radius: Double,
        val speed: Double,
        /** Unwrapped, including the initial mean anomaly's cycle. */
        val eccentricAnomaly: Double,
        val meanAnomaly: Double,
        val solverResidual: Double = 0.0,
        val solverIterations: Int = 0,
    )

    data class Solution(
        val eccentricAnomaly: Double,
        val residual: Double,
        val iterations: Int,
        val bisections: Int,
        /** Retained separately so large unwrapped phases do not spoil trigonometry. */
        val principalEccentricAnomaly: Double,
    )

    /** Return an unwrapped E. A safeguarded Newton step always retains a root bracket. */
    fun solve(mean: Double, e: Double): Double = solveDetailed(mean, e).eccentricAnomaly

    fun solveDetailed(mean: Double, e: Double): Solution {
        require(mean.isFinite()) { "Apsis mean anomaly must be finite" }
        require(e.isFinite() && e >= 0.0 && e < 1.0) { "Apsis eccentricity must be in [0, 1)" }
        val wrapped = principal(mean)
        if (e == 0.0 || wrapped == 0.0) return Solution(mean, 0.0, 0, 0, wrapped)
        val direction = if (wrapped < 0.0) -1.0 else 1.0
        val m = abs(wrapped)
        var lower = m
        var upper = min(PI, m + e)
        var eccentric = if (m < .2 && e > .8) {
            min(m / (1.0 - e), Math.cbrt(6.0 * m / e))
        } else m + .85 * e
        eccentric = eccentric.coerceIn(lower, upper)
        var bisections = 0
        for (iteration in 1..MAX_ITERATIONS) {
            // E - e*sin(E) written without cancellation near e=1, E=0.
            val residual = (1.0 - e) * eccentric + e * minusSine(eccentric) - m
            val derivative = (1.0 - e) + 2.0 * e * sin(eccentric * .5).pow(2)
            if (abs(residual) <= RESIDUAL_TOLERANCE && abs(residual) / derivative <= ANGLE_TOLERANCE) {
                val local = direction * eccentric
                return Solution(mean + (local - wrapped), direction * residual, iteration, bisections, local)
            }
            if (residual > 0.0) upper = eccentric else lower = eccentric
            val newton = eccentric - residual / derivative
            val next = if (newton.isFinite() && newton > lower && newton < upper) newton else {
                bisections++
                lower + .5 * (upper - lower)
            }
            if (next == eccentric || upper - lower <= ANGLE_TOLERANCE) {
                val candidate = lower + .5 * (upper - lower)
                val checked = (1.0 - e) * candidate + e * minusSine(candidate) - m
                check(abs(checked) <= RESIDUAL_TOLERANCE) { "Apsis Kepler bracket closed with residual $checked" }
                val local = direction * candidate
                return Solution(mean + (local - wrapped), direction * checked, iteration, bisections, local)
            }
            eccentric = next
        }
        error("Apsis Kepler solve failed after $MAX_ITERATIONS iterations: M=$mean e=$e bracket=$lower..$upper")
    }

    fun sample(elements: Elements, seconds: Double): Point {
        require(seconds.isFinite()) { "Apsis orbit time must be finite" }
        val mean = elements.mean0 + TAU * (seconds / elements.period)
        check(mean.isFinite()) { "Apsis orbit time overflow" }
        val solution = solveDetailed(mean, elements.e)
        return point(elements, mean, solution.eccentricAnomaly, solution.principalEccentricAnomaly,
            solution.residual, solution.iterations)
    }

    /** Causal comparison only: same ellipse and initial position, constant true-angle speed. */
    fun sampleUniformAngular(elements: Elements, seconds: Double): Point {
        require(seconds.isFinite()) { "Apsis orbit time must be finite" }
        val initial = trueAnomaly(solve(elements.mean0, elements.e), elements.e)
        val trueAngle = initial + TAU * (seconds / elements.period)
        val eccentric = eccentricAtTrueAnomaly(trueAngle, elements.e)
        val mean = meanAtTrueAnomaly(trueAngle, elements.e)
        val local = principal(eccentric)
        val kepler = point(elements, mean, eccentric, local, 0.0, 0)
        val denominator = (1.0 - elements.e) + 2.0 * elements.e * sin(local * .5).pow(2)
        return kepler.copy(speed = kepler.speed * denominator * denominator /
            sqrt((1.0 - elements.e) * (1.0 + elements.e)))
    }

    /** Unwrapped true anomaly, measured from the unrotated orbit's periapsis. */
    fun trueAnomaly(eccentric: Double, e: Double): Double {
        validateAngle(eccentric, e)
        if (e == 0.0) return eccentric
        val local = principal(eccentric)
        val angle = atan2(sqrt((1.0 - e) * (1.0 + e)) * sin(local),
            (1.0 - e) - 2.0 * sin(local * .5).pow(2))
        return eccentric + (angle - local)
    }

    fun eccentricAtTrueAnomaly(trueAngle: Double, e: Double): Double {
        validateAngle(trueAngle, e)
        if (e == 0.0) return trueAngle
        val local = principal(trueAngle)
        val eccentric = atan2(sqrt((1.0 - e) * (1.0 + e)) * sin(local), e + cos(local))
        return trueAngle + (eccentric - local)
    }

    fun meanAtTrueAnomaly(trueAngle: Double, e: Double): Double {
        val eccentric = eccentricAtTrueAnomaly(trueAngle, e)
        return eccentric - e * sin(principal(eccentric))
    }

    /**
     * Every crossing of a fixed world direction in (fromSeconds, toSeconds].
     * Enumerating the exact mean-anomaly threshold cannot miss a fast/narrow encounter.
     * A sector's entry and exit are its center minus/plus half-width; a renderer can
     * use those times to schedule one finite, bandwidth-limited excitation per entry.
     */
    fun crossings(elements: Elements, worldAngle: Double, fromSeconds: Double, toSeconds: Double,
        maxEvents: Int = 10_000): DoubleArray = crossingsAtPhase(elements,
            meanAtTrueAnomaly(worldAngle - elements.orientation, elements.e), elements.mean0,
            fromSeconds, toSeconds, maxEvents)

    fun crossingsUniformAngular(elements: Elements, worldAngle: Double, fromSeconds: Double,
        toSeconds: Double, maxEvents: Int = 10_000): DoubleArray = crossingsAtPhase(elements,
            worldAngle - elements.orientation, trueAnomaly(solve(elements.mean0, elements.e), elements.e),
            fromSeconds, toSeconds, maxEvents)

    /** Signed focus-based ellipse sector, including any whole cycles between endpoints. */
    fun sweptArea(elements: Elements, eccentricStart: Double, eccentricEnd: Double): Double {
        require(eccentricStart.isFinite() && eccentricEnd.isFinite()) { "Apsis area endpoints must be finite" }
        return .5 * elements.a * elements.b * ((eccentricEnd - eccentricStart) - elements.e *
            (sin(principal(eccentricEnd)) - sin(principal(eccentricStart))))
    }

    /**
     * Optional immutable one-period runtime cache. Interpolation error is a separate
     * measured budget, never evidence for the reference solver's area accuracy.
     */
    class Trajectory(val elements: Elements, val samplesPerOrbit: Int = 8192) {
        init { require(samplesPerOrbit in 64..1_048_576) { "Apsis trajectory resolution out of range" } }
        private val points = Array(samplesPerOrbit + 1) { ApsisOrbit.sample(elements, it * elements.period / samplesPerOrbit) }

        fun sample(seconds: Double): Point {
            require(seconds.isFinite()) { "Apsis trajectory time must be finite" }
            val cycles = floor(seconds / elements.period)
            val phase = seconds / elements.period - cycles
            val at = phase * samplesPerOrbit
            val index = floor(at).toInt().coerceIn(0, samplesPerOrbit - 1)
            val mix = (at - index).coerceIn(0.0, 1.0)
            val a = points[index]
            val b = points[index + 1]
            fun interpolate(x: Double, y: Double) = x + (y - x) * mix
            return Point(interpolate(a.x, b.x), interpolate(a.y, b.y), interpolate(a.radius, b.radius),
                interpolate(a.speed, b.speed), interpolate(a.eccentricAnomaly, b.eccentricAnomaly) + cycles * TAU,
                elements.mean0 + TAU * seconds / elements.period,
                max(abs(a.solverResidual), abs(b.solverResidual)), max(a.solverIterations, b.solverIterations))
        }
    }

    private fun point(elements: Elements, mean: Double, eccentric: Double, local: Double,
        residual: Double, iterations: Int): Point {
        val sineHalf = sin(local * .5)
        val cosineHalf = cos(local * .5)
        val denominator = (1.0 - elements.e) + 2.0 * elements.e * sineHalf * sineHalf
        val x = elements.a * ((1.0 - elements.e) - 2.0 * sineHalf * sineHalf)
        val y = elements.b * sin(local)
        val c = cos(elements.orientation)
        val s = sin(elements.orientation)
        val speed = TAU * elements.a / elements.period * sqrt(
            ((1.0 - elements.e) + 2.0 * elements.e * cosineHalf * cosineHalf) / denominator)
        val result = Point(c * x - s * y, s * x + c * y, elements.a * denominator, speed,
            eccentric, mean, residual, iterations)
        check(result.x.isFinite() && result.y.isFinite() && result.radius.isFinite() && result.speed.isFinite()) {
            "Apsis orbit produced non-finite geometry"
        }
        return result
    }

    private fun crossingsAtPhase(elements: Elements, boundary: Double, initial: Double,
        from: Double, to: Double, maxEvents: Int): DoubleArray {
        require(from.isFinite() && to.isFinite() && to >= from) { "Apsis crossing interval must be finite and ordered" }
        require(boundary.isFinite() && maxEvents >= 0) { "Invalid Apsis crossing boundary or event limit" }
        if (from == to) return DoubleArray(0)
        val offset = principal(boundary - initial) / TAU * elements.period
        // Guard each end by one cycle, then compare actual event times. This makes
        // (from,to] ownership robust when a caller reuses a returned event as an
        // interval endpoint: dividing its reconstructed time can round across an integer.
        val first = floor((from - offset) / elements.period) - 1.0
        val last = ceil((to - offset) / elements.period) + 1.0
        val candidates = (last - first + 1.0).coerceAtLeast(0.0)
        require(candidates.isFinite() && candidates <= maxEvents.toDouble() + 5.0) {
            "Apsis crossing interval exceeds $maxEvents events"
        }
        val events = ArrayList<Double>()
        for (index in 0 until candidates.toInt()) {
            val time = offset + (first + index) * elements.period
            if (time > from && time <= to) {
                require(events.size < maxEvents) { "Apsis crossing interval exceeds $maxEvents events" }
                events.add(time)
            }
        }
        return events.toDoubleArray()
    }

    private fun principal(angle: Double): Double {
        var reduced = angle % TAU
        if (reduced > PI) reduced -= TAU
        if (reduced < -PI) reduced += TAU
        return reduced
    }

    private fun validateAngle(angle: Double, e: Double) {
        require(angle.isFinite() && e.isFinite() && e >= 0.0 && e < 1.0) { "Invalid Apsis angle/eccentricity" }
    }

    private fun minusSine(angle: Double): Double {
        if (abs(angle) >= .1) return angle - sin(angle)
        val square = angle * angle
        return angle * square * (1.0 / 6.0 + square * (-1.0 / 120.0 + square *
            (1.0 / 5040.0 + square * (-1.0 / 362880.0 + square *
                (1.0 / 39916800.0 - square / 6227020800.0)))))
    }
}
