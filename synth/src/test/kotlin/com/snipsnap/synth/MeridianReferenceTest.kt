package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reference fixture only: the small-angle, rotating-frame Foucault equations.
 * Meridian's audio path uses independently clocked musical geometry instead.
 *
 * Equations/analytic form: https://www.phys.unsw.edu.au/~jw/pendulumdetails.html
 * Declared convention: x east, y north, counterclockwise positive from above.
 * Standard g = 9.80665 m/s²; L = 67 m; sidereal day = 86164.0905 s;
 * initial x = .04 m, y = vx = vy = 0; all integration uses Float64.
 * This validates this linear reference, not finite-amplitude/contact physics.
 */
class MeridianReferenceTest {
    private val gravity = 9.80665
    private val length = 67.0
    private val siderealSeconds = 86164.0905
    private val amplitude = .04
    private val omega0 = sqrt(gravity / length)

    private data class State(val x: Double, val y: Double, val vx: Double, val vy: Double) {
        operator fun plus(other: State) = State(x + other.x, y + other.y, vx + other.vx, vy + other.vy)
        operator fun times(scale: Double) = State(x * scale, y * scale, vx * scale, vy * scale)
    }

    private fun rate(latitudeDegrees: Double) = 2 * PI / siderealSeconds * sin(latitudeDegrees * PI / 180)

    private fun derivative(s: State, p: Double) = State(s.vx, s.vy,
        -omega0 * omega0 * s.x + 2 * p * s.vy,
        -omega0 * omega0 * s.y - 2 * p * s.vx)

    private fun advance(s: State, p: Double, dt: Double): State {
        val a = derivative(s, p)
        val b = derivative(s + a * (dt / 2), p)
        val c = derivative(s + b * (dt / 2), p)
        val d = derivative(s + c * dt, p)
        return s + (a + b * 2.0 + c * 2.0 + d) * (dt / 6)
    }

    private fun analytic(time: Double, p: Double): State {
        val nu = sqrt(omega0 * omega0 + p * p)
        val wr = amplitude * cos(nu * time)
        // The rest initial condition includes this small ellipse. Dropping it is not exact.
        val wi = amplitude * p / nu * sin(nu * time)
        val c = cos(p * time)
        val s = sin(p * time)
        val velocity = -amplitude * omega0 * omega0 / nu * sin(nu * time)
        return State(wr * c + wi * s, wi * c - wr * s, velocity * c, -velocity * s)
    }

    private fun numerical(latitude: Double, seconds: Double = 600.0, dt: Double = .02): List<State> {
        val states = ArrayList<State>((seconds / dt).toInt() + 1)
        var s = State(amplitude, 0.0, 0.0, 0.0)
        states += s
        repeat((seconds / dt).toInt()) {
            s = advance(s, rate(latitude), dt)
            states += s
        }
        return states
    }

    @Test
    fun `the reference uses a sidereal day and the calculated 67 metre period`() {
        assertEquals(16.423167864604345, 2 * PI / omega0, 1e-12)
        assertTrue(abs(2 * PI / siderealSeconds - 2 * PI / 86400) > 1e-7,
            "A rounded solar day must not silently replace the declared sidereal day")
        val initial = analytic(0.0, rate(48.8566))
        assertEquals(amplitude, initial.x, 0.0)
        assertTrue(listOf(initial.y, initial.vx, initial.vy).all { abs(it) == 0.0 })
    }

    @Test
    fun `independent RK4 agrees with the rest initial condition analytic solution`() {
        for (latitude in listOf(0.0, 48.8566, -48.8566, 90.0, -90.0)) {
            val states = numerical(latitude)
            var positionError = 0.0
            var velocityError = 0.0
            for ((i, s) in states.withIndex()) {
                val exact = analytic(i * .02, rate(latitude))
                positionError = maxOf(positionError, hypot(s.x - exact.x, s.y - exact.y) / amplitude)
                velocityError = maxOf(velocityError, hypot(s.vx - exact.vx, s.vy - exact.vy) / (amplitude * omega0))
            }
            println("MERIDIAN reference latitude=$latitude RK4 dt=.02s position=$positionError velocity=$velocityError")
            assertTrue(positionError < 1e-7 && velocityError < 1e-7,
                "linear reference differs from analytic solution at latitude $latitude")
        }
    }

    /** Axis from the covariance over four complete swings, rather than the reversing bob angle. */
    private fun axis(states: List<State>, centerSeconds: Double): Double {
        val halfWindow = 2 * (2 * PI / omega0)
        val from = ((centerSeconds - halfWindow) / .02).toInt()
        val to = ((centerSeconds + halfWindow) / .02).toInt()
        val n = (to - from).toDouble()
        var mx = 0.0
        var my = 0.0
        for (i in from until to) { mx += states[i].x / n; my += states[i].y / n }
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        for (i in from until to) {
            val x = states[i].x - mx
            val y = states[i].y - my
            xx += x * x
            xy += x * y
            yy += y * y
        }
        return .5 * atan2(2 * xy, xx - yy)
    }

    @Test
    fun `windowed undirected axis drifts clockwise north and anticlockwise south`() {
        val measured = mutableMapOf<Double, Double>()
        for (latitude in listOf(0.0, 48.8566, -48.8566, 90.0)) {
            val states = numerical(latitude)
            // Double the axis angle before unwrapping: the line is equivalent modulo pi.
            val doubled = 2 * (axis(states, 480.0) - axis(states, 120.0))
            val change = .5 * atan2(sin(doubled), cos(doubled))
            val p = -change / 360
            measured[latitude] = p
            println("MERIDIAN reference axis latitude=$latitude measured=$p expected=${rate(latitude)} rad/s")
            assertEquals(rate(latitude), p, maxOf(1e-12, abs(rate(latitude)) * .01))
        }
        assertTrue(measured.getValue(48.8566) > 0.0 && measured.getValue(-48.8566) < 0.0)
        assertEquals(measured.getValue(48.8566), -measured.getValue(-48.8566), 1e-12)
    }
}
