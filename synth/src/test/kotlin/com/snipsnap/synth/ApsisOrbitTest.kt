package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class ApsisOrbitTest {
    @Test
    fun `safeguarded solver closes circular high eccentricity and multiple cycle fixtures`() {
        for (e in listOf(0.0, .2, .85, .99, .999999, Math.nextDown(1.0))) {
            for (base in listOf(0.0, 1e-15, 1e-10, 1e-5, .01, .1, 1.0, PI - 1e-10, PI)) {
                for (direction in listOf(-1.0, 1.0)) {
                    val mean = base * direction
                    val solved = ApsisOrbit.solveDetailed(mean, e)
                    assertTrue(solved.iterations <= ApsisOrbit.MAX_ITERATIONS)
                    assertTrue(abs(solved.residual) <= ApsisOrbit.RESIDUAL_TOLERANCE, "e=$e M=$mean $solved")
                    assertTrue(abs(solved.eccentricAnomaly - e * sin(solved.eccentricAnomaly) - mean) < 2e-14)
                    assertTrue(solved.eccentricAnomaly >= mean - e - 1e-14 && solved.eccentricAnomaly <= mean + e + 1e-14)
                }
            }
            for (cycle in -12..12) {
                val mean = .37 + cycle * ApsisOrbit.TAU
                assertEquals(ApsisOrbit.solve(.37, e) + cycle * ApsisOrbit.TAU,
                    ApsisOrbit.solve(mean, e), 3e-14)
            }
        }
    }

    @Test
    fun `the attraction center is the focus with declared radial extremes and closure`() {
        val orbit = ApsisOrbit.Elements(2.3, .85, 3.7)
        val near = ApsisOrbit.sample(orbit, 0.0)
        val far = ApsisOrbit.sample(orbit, orbit.period * .5)
        assertEquals(orbit.a * (1.0 - orbit.e), near.x, 1e-14)
        assertEquals(orbit.a * (1.0 - orbit.e), near.radius, 1e-14)
        assertEquals(-orbit.a * (1.0 + orbit.e), far.x, 1e-14)
        assertEquals(orbit.a * (1.0 + orbit.e), far.radius, 1e-14)
        assertEquals((1.0 + orbit.e) / (1.0 - orbit.e), near.speed / far.speed, 1e-12)
        for (step in 0..80) {
            val time = step * orbit.period / 80.0
            val point = ApsisOrbit.sample(orbit, time)
            val repeated = ApsisOrbit.sample(orbit, time + orbit.period)
            assertEquals(point.x, repeated.x, 1e-13)
            assertEquals(point.y, repeated.y, 1e-13)
            assertEquals(point.radius, hypot(point.x, point.y), 1e-13)
            assertEquals(point.eccentricAnomaly + ApsisOrbit.TAU, repeated.eccentricAnomaly, 3e-14)
        }
        val rotated = ApsisOrbit.sample(orbit.copy(orientation = PI / 2.0), 0.0)
        assertEquals(0.0, rotated.x, 1e-14)
        assertEquals(near.x, rotated.y, 1e-14)
    }

    @Test
    fun `twenty equal time sectors meet relative area tolerance and independent polar integration`() {
        for (e in listOf(0.0, .45, .85)) {
            val orbit = ApsisOrbit.Elements(1.7, e, 2.4, mean0 = -.17)
            val expected = PI * orbit.a * orbit.b / 20.0
            var totalNumerical = 0.0
            for (sector in 0 until 20) {
                val start = ApsisOrbit.sample(orbit, sector * orbit.period / 20.0)
                val end = ApsisOrbit.sample(orbit, (sector + 1) * orbit.period / 20.0)
                val exact = ApsisOrbit.sweptArea(orbit, start.eccentricAnomaly, end.eccentricAnomaly)
                assertTrue(abs(exact / expected - 1.0) < 1e-11, "e=$e sector=$sector area=$exact expected=$expected")
                // Independent geometric quadrature: integrate r(nu)^2/2 over true angle.
                // This is not a triangle between the sector's endpoints and the focus.
                val from = ApsisOrbit.trueAnomaly(start.eccentricAnomaly, e)
                val to = ApsisOrbit.trueAnomaly(end.eccentricAnomaly, e)
                val numerical = simpson(from, to, 2048) { angle ->
                    val radius = orbit.a * (1.0 - e * e) / (1.0 + e * cos(angle))
                    .5 * radius * radius
                }
                assertTrue(abs(numerical / expected - 1.0) < 1e-8,
                    "independent area e=$e sector=$sector numeric=$numerical expected=$expected")
                totalNumerical += numerical
            }
            assertEquals(PI * orbit.a * orbit.b, totalNumerical, 1e-9)
        }
    }

    @Test
    fun `common law persists across body ratios and common distance scaling`() {
        val clock = .95
        for (scale in listOf(.4, 1.0, 2.0)) {
            val invariants = listOf(1.0, 1.2, 1.5, 2.0, 3.0, 6.0).map { periodRatio ->
                val axis = scale * periodRatio.pow(2.0 / 3.0)
                val period = clock * axis.pow(1.5)
                val orbit = ApsisOrbit.Elements(axis, .4, period)
                orbit.period.pow(2) / orbit.a.pow(3)
            }
            for (value in invariants) assertEquals(clock * clock, value, 2e-15)
        }
    }

    @Test
    fun `analytic crossing enumeration catches a sub millisecond passage inside one control interval`() {
        val orbit = ApsisOrbit.Elements(1.0, .85, .4)
        val halfWidth = PI / 18.0
        val entries = ApsisOrbit.crossings(orbit, -halfWidth, -.002, .002)
        val exits = ApsisOrbit.crossings(orbit, halfWidth, -.002, .002)
        assertEquals(1, entries.size)
        assertEquals(1, exits.size)
        val duration = exits[0] - entries[0]
        assertTrue(duration in .0009..0.0011, "encounter duration=$duration")
        // Endpoints of this 1.45ms update both lie outside the encounter.
        val angleBefore = atan2(ApsisOrbit.sample(orbit, -.000725).y, ApsisOrbit.sample(orbit, -.000725).x)
        val angleAfter = atan2(ApsisOrbit.sample(orbit, .000725).y, ApsisOrbit.sample(orbit, .000725).x)
        assertTrue(angleBefore < -halfWidth && angleAfter > halfWidth)
        assertEquals(1, ApsisOrbit.crossings(orbit, -halfWidth, -.000725, .000725).size)
        val atEntry = ApsisOrbit.sample(orbit, entries[0])
        assertEquals(-halfWidth, atan2(atEntry.y, atEntry.x), 1e-12)
        val all = ApsisOrbit.crossings(orbit, -halfWidth, -.002, 1.2)
        assertEquals(4, all.size)
        for (i in 1 until all.size) assertEquals(orbit.period, all[i] - all[i - 1], 1e-15)
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.crossings(orbit, 0.0, 0.0, 10.0, maxEvents = 3) }
    }

    @Test
    fun `crossing intervals own their right boundary once and respect orientation and initial phase`() {
        val orbit = ApsisOrbit.Elements(1.0, .85, .4, mean0 = .8, orientation = .63)
        val event = ApsisOrbit.crossings(orbit, -.2, 0.0, .4).single()
        assertEquals(listOf(event), ApsisOrbit.crossings(orbit, -.2, 0.0, event).toList())
        assertTrue(ApsisOrbit.crossings(orbit, -.2, event, .4).isEmpty())
        val point = ApsisOrbit.sample(orbit, event)
        assertEquals(-.2, atan2(point.y, point.x), 1e-12)
    }

    @Test
    fun `uniform angular comparison shares initial geometry but loses equal time areas`() {
        val orbit = ApsisOrbit.Elements(1.0, .85, .4, mean0 = .2, orientation = .8)
        val actual = ApsisOrbit.sample(orbit, 0.0)
        val uniform = ApsisOrbit.sampleUniformAngular(orbit, 0.0)
        assertEquals(actual.x, uniform.x, 1e-14)
        assertEquals(actual.y, uniform.y, 1e-14)
        val area = (0 until 20).map { index ->
            val from = ApsisOrbit.sampleUniformAngular(orbit, index * orbit.period / 20.0)
            val to = ApsisOrbit.sampleUniformAngular(orbit, (index + 1) * orbit.period / 20.0)
            ApsisOrbit.sweptArea(orbit, from.eccentricAnomaly, to.eccentricAnomaly)
        }
        assertTrue(area.max() / area.min() > 10.0)
        val events = ApsisOrbit.crossingsUniformAngular(orbit, 1.2, 0.0, 1.2)
        assertEquals(3, events.size)
        for (event in events) {
            val point = ApsisOrbit.sampleUniformAngular(orbit, event)
            assertEquals(1.2, atan2(point.y, point.x), 1e-12)
        }
    }

    @Test
    fun `runtime interpolation is measured densely around the fastest passage`() {
        val orbit = ApsisOrbit.Elements(1.3, .85, .4, mean0 = -.1, orientation = .2)
        val trajectory = ApsisOrbit.Trajectory(orbit, 8192)
        var maximumPositionError = 0.0
        var maximumRadiusError = 0.0
        var maximumSpeedRelativeError = 0.0
        for (index in 0..8000) {
            val time = -.003 + index * .02 / 8000.0
            val reference = ApsisOrbit.sample(orbit, time)
            val interpolated = trajectory.sample(time)
            maximumPositionError = max(maximumPositionError, hypot(reference.x - interpolated.x, reference.y - interpolated.y) / orbit.a)
            maximumRadiusError = max(maximumRadiusError, abs(reference.radius - interpolated.radius) / orbit.a)
            maximumSpeedRelativeError = max(maximumSpeedRelativeError, abs(reference.speed - interpolated.speed) / reference.speed)
        }
        assertTrue(maximumPositionError < 4e-6, "runtime position error=$maximumPositionError")
        assertTrue(maximumRadiusError < 4e-6, "runtime radius error=$maximumRadiusError")
        assertTrue(maximumSpeedRelativeError < 4e-5, "runtime speed error=$maximumSpeedRelativeError")
    }

    @Test
    fun `sixteen output frame control interpolation stays bounded at fastest supported periapsis`() {
        val orbit = ApsisOrbit.Elements(1.0, .85, .4)
        val controlStep = 16.0 / 44_100.0
        var positionError = 0.0
        var radiusError = 0.0
        var speedError = 0.0
        for (interval in -32..32) {
            // Put periapsis halfway between controls, the difficult case.
            val startTime = (interval - .5) * controlStep
            val start = ApsisOrbit.sample(orbit, startTime)
            val end = ApsisOrbit.sample(orbit, startTime + controlStep)
            for (substep in 0..16) {
                val mix = substep / 16.0
                val reference = ApsisOrbit.sample(orbit, startTime + mix * controlStep)
                val x = start.x + (end.x - start.x) * mix
                val y = start.y + (end.y - start.y) * mix
                val radius = start.radius + (end.radius - start.radius) * mix
                val speed = start.speed + (end.speed - start.speed) * mix
                positionError = max(positionError, hypot(reference.x - x, reference.y - y))
                radiusError = max(radiusError, abs(reference.radius - radius))
                speedError = max(speedError, abs(reference.speed - speed) / reference.speed)
            }
        }
        assertTrue(positionError < 3e-4, "16-frame normalized position error=$positionError")
        assertTrue(radiusError < 3e-4, "16-frame normalized radius error=$radiusError")
        assertTrue(speedError < 1e-3, "16-frame relative speed error=$speedError")
    }

    @Test
    fun `invalid inputs fail before audio can receive invalid geometry`() {
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.Elements(0.0, .2, 1.0) }
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.Elements(1.0, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.Elements(1.0, .2, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.solve(Double.NaN, .2) }
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.solve(.2, -1.0) }
        assertFailsWith<IllegalArgumentException> { ApsisOrbit.sample(ApsisOrbit.Elements(1.0, .2, 1.0), Double.NaN) }
    }

    private fun simpson(from: Double, to: Double, pieces: Int, function: (Double) -> Double): Double {
        val step = (to - from) / pieces
        var sum = function(from) + function(to)
        for (index in 1 until pieces) sum += function(from + index * step) * if (index % 2 == 0) 2.0 else 4.0
        return sum * step / 3.0
    }
}
