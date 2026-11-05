package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/** Unsmoothed planar circular restricted three-body dynamics, in a rotating frame. */
internal object LibrationTrajectory {
    const val MU = .000954
    const val EXCLUSION = .01
    const val FAR_FIELD = 8.0
    const val MAX_WORK = 120_000
    enum class Family { L4, L1, L2, L3, L5 }
    data class State(val x: Double, val y: Double, val vx: Double, val vy: Double) {
        fun array() = doubleArrayOf(x, y, vx, vy)
    }
    data class Template(val clock: Double, val neighborhood: Double, val excursion: Double,
        val spread: Double, val impulse: Double)
    // Separate phase-space and musical-time ranges. They are part of the rendering model.
    val templates = mapOf(
        Family.L4 to Template(18.0, .12, .0007, .0030, .00018),
        Family.L5 to Template(18.0, .12, .0007, .0030, .00018),
        Family.L1 to Template(1.05, .035, .000003, .000018, .000055),
        Family.L2 to Template(1.12, .055, .000003, .000018, .000060),
        Family.L3 to Template(17.0, .22, .00035, .0016, .00010),
    )
    data class Report(val jacobiDrift: Double, val minPrimaryDistance: Double,
        val maxExcursion: Double, val departureSeconds: Double?, val work: Int,
        val termination: String, val validSamples: Int)
    data class Path(val family: Family, val id: Int, val states: Array<State>, val rate: Int, val report: Report)

    private fun axisForce(x: Double): Double =
        x - (1 - MU) * (x + MU) / abs(x + MU).pow(3) - MU * (x - 1 + MU) / abs(x - 1 + MU).pow(3)
    private fun root(lo: Double, hi: Double): Double {
        var a = lo; var b = hi; var fa = axisForce(a)
        require(fa * axisForce(b) < 0)
        repeat(100) {
            val c = .5 * (a + b)
            if (c == a || c == b) return c
            val fc = axisForce(c)
            if (fc == 0.0) return c
            if (fa * fc < 0) b = c else { a = c; fa = fc }
        }
        return .5 * (a + b)
    }
    private val equilibria by lazy {
        mapOf(Family.L4 to State(.5 - MU, sqrt(3.0) / 2, 0.0, 0.0),
            Family.L5 to State(.5 - MU, -sqrt(3.0) / 2, 0.0, 0.0),
            Family.L1 to State(root(-MU + 1e-7, 1 - MU - 1e-7), 0.0, 0.0, 0.0),
            Family.L2 to State(root(1 - MU + 1e-7, 4.0), 0.0, 0.0, 0.0),
            Family.L3 to State(root(-4.0, -MU - 1e-7), 0.0, 0.0, 0.0))
    }
    fun equilibrium(family: Family): State = equilibria.getValue(family)
    fun distance(s: State, center: State) = hypot(s.x - center.x, s.y - center.y)
    fun primaryDistance(s: State) = min(hypot(s.x + MU, s.y), hypot(s.x - 1 + MU, s.y))
    fun jacobi(s: State): Double = s.x * s.x + s.y * s.y +
        2 * (1 - MU) / hypot(s.x + MU, s.y) + 2 * MU / hypot(s.x - 1 + MU, s.y) - s.vx * s.vx - s.vy * s.vy

    private fun derivative(s: DoubleArray, out: DoubleArray) {
        val a = s[0] + MU; val b = s[0] - 1 + MU
        val r1 = hypot(a, s[1]); val r2 = hypot(b, s[1])
        val g1 = (1 - MU) / (r1 * r1 * r1); val g2 = MU / (r2 * r2 * r2)
        out[0] = s[2]; out[1] = s[3]
        out[2] = 2 * s[3] + s[0] - g1 * a - g2 * b
        out[3] = -2 * s[2] + s[1] - (g1 + g2) * s[1]
    }
    fun acceleration(s: State): Pair<Double, Double> {
        val out = DoubleArray(4); derivative(s.array(), out)
        return out[2] to out[3]
    }

    /** Small oscillatory eigenmodes at the triangles; an unstable eigenvector at the saddles. */
    fun initial(family: Family, id: Int, wander: Double, nudge: Double,
        model: Int = Libration.MODEL_VERSION): State {
        require(model in 1..Libration.MODEL_VERSION)
        val e = equilibrium(family); val t = templates.getValue(family)
        // Keep particle identities and all random draws identical across saved models.
        val rng = Random(Dsp.seedFor("LIBRATION", 1, family.name, id))
        val phase = rng.nextDouble() * 2 * PI
        val s = e.array()
        // Saddle escape time is logarithmic in the kick. An exponential control law
        // spreads departure timing across the knob instead of crowding it near zero.
        val impulse = if (model == 1) t.impulse * nudge else {
            val maximum = when (family) {
                Family.L4, Family.L5 -> .0012
                Family.L1 -> .020
                Family.L2 -> .025
                Family.L3 -> .0030
            }
            val knee = t.impulse * .5
            knee * expm1(ln1p(maximum / knee) * nudge)
        }
        if (family == Family.L4 || family == Family.L5) {
            val disc = sqrt(1 - 27 * MU * (1 - MU))
            val uxy = (if (family == Family.L4) 1 else -1) * 3 * sqrt(3.0) * (1 - 2 * MU) / 4
            fun mode(omega: Double, amplitude: Double, phi: Double) {
                val denominator = uxy * uxy + 4 * omega * omega
                val re = -(omega * omega + .75) * uxy / denominator
                val im = (omega * omega + .75) * 2 * omega / denominator
                s[0] += amplitude * cos(phi)
                s[1] += amplitude * (re * cos(phi) - im * sin(phi))
                s[2] -= amplitude * omega * sin(phi)
                s[3] -= amplitude * omega * (re * sin(phi) + im * cos(phi))
            }
            mode(sqrt((1 - disc) / 2), (t.excursion + t.spread * wander) * (.72 + .56 * rng.nextDouble()), phase)
            mode(sqrt((1 + disc) / 2), .00010 + .00032 * wander, phase * 1.73)
            s[2] += impulse * cos(phase)
            s[3] += impulse * sin(phase)
        } else {
            val g = (1 - MU) / abs(e.x + MU).pow(3) + MU / abs(e.x - 1 + MU).pow(3)
            val xx = 1 + 2 * g; val yy = 1 - g; val b = 4 - xx - yy
            val disc = sqrt(b * b - 4 * xx * yy)
            val lambda = sqrt((-b + disc) / 2)
            val y = (lambda * lambda - xx) / (2 * lambda)
            val offset = (t.excursion + t.spread * wander) * (.55 + .90 * rng.nextDouble())
            val direction = if (family == Family.L1) -1.0 else 1.0
            s[0] += direction * offset; s[1] += direction * offset * y
            s[2] += direction * offset * lambda; s[3] += direction * offset * lambda * y
            val omega = sqrt((b + disc) / 2)
            val im = (omega * omega + xx) / (2 * omega)
            val a = (if (family == Family.L3) .00012 else .000016) * (.2 + wander)
            s[0] += a * cos(phase); s[1] -= a * im * sin(phase)
            s[2] -= a * omega * sin(phase); s[3] -= a * im * omega * cos(phase)
            val dv = if (model == 1)
                direction * t.impulse * nudge * (.65 + .35 * rng.nextDouble()) / sqrt(1 + y * y)
            else direction * impulse * (.65 + .35 * rng.nextDouble()) / sqrt(1 + y * y)
            s[2] += dv; s[3] += dv * y
        }
        return State(s[0], s[1], s[2], s[3])
    }

    private val A = arrayOf(doubleArrayOf(), doubleArrayOf(1.0 / 5), doubleArrayOf(3.0 / 40, 9.0 / 40),
        doubleArrayOf(44.0 / 45, -56.0 / 15, 32.0 / 9),
        doubleArrayOf(19372.0 / 6561, -25360.0 / 2187, 64448.0 / 6561, -212.0 / 729),
        doubleArrayOf(9017.0 / 3168, -355.0 / 33, 46732.0 / 5247, 49.0 / 176, -5103.0 / 18656),
        doubleArrayOf(35.0 / 384, 0.0, 500.0 / 1113, 125.0 / 192, -2187.0 / 6784, 11.0 / 84))
    private val B4 = doubleArrayOf(5179.0 / 57600, 0.0, 7571.0 / 16695, 393.0 / 640,
        -92097.0 / 339200, 187.0 / 2100, 1.0 / 40)

    private fun segmentPrimaryDistance(a: State, b: State): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val norm = dx * dx + dy * dy
        return listOf(-MU, 1 - MU).minOf { x ->
            val f = if (norm == 0.0) 0.0 else (((x - a.x) * dx - a.y * dy) / norm).coerceIn(0.0, 1.0)
            hypot(a.x + f * dx - x, a.y + f * dy)
        }
    }

    /** DP5(4), sampled at exact control times. No force evaluation occurs in the audio loop. */
    fun integrate(family: Family, id: Int, start: State, seconds: Double, rate: Int = 500,
        relativeTolerance: Double = 1e-9, absoluteTolerance: Double = 1e-11,
        maxWork: Int = MAX_WORK): Path {
        require(seconds.isFinite() && seconds > 0 && seconds <= 12)
        require(rate in 30..1000 && ceil(seconds * rate) <= 12_000)
        require(start.array().all { it.isFinite() } && primaryDistance(start) > EXCLUSION) { "initial state enters a primary exclusion" }
        val table = templates.getValue(family); val center = equilibrium(family)
        val frames = ceil(seconds * rate).toInt() + 1
        val states = Array(frames) { start }
        val k = Array(7) { DoubleArray(4) }; val tmp = DoubleArray(4)
        var state = start; var y = state.array(); var time = 0.0
        val dt = table.clock / rate
        var h = min(.02, dt); var work = 0; var valid = 1
        var reason = "horizon"; var drift = 0.0; var closest = primaryDistance(start)
        var excursion = distance(start, center); var departure: Double? = null
        val c0 = jacobi(start)
        loop@ for (frame in 1 until frames) {
            if (frame % 64 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Libration trajectory cancelled")
            val target = frame * dt
            while (time < target - 1e-13) {
                if (++work > maxWork) { reason = "work-cap"; break@loop }
                h = min(h, target - time)
                if (h < 1e-12) { reason = "step-underflow"; break@loop }
                for (stage in 0..6) {
                    for (q in 0..3) {
                        var v = y[q]
                        for (j in A[stage].indices) v += h * A[stage][j] * k[j][q]
                        tmp[q] = v
                    }
                    derivative(tmp, k[stage])
                }
                var error = 0.0
                for (q in 0..3) {
                    var fourth = y[q]
                    for (j in 0..6) fourth += h * B4[j] * k[j][q]
                    val scale = absoluteTolerance + relativeTolerance * max(abs(y[q]), abs(tmp[q]))
                    error = max(error, abs(tmp[q] - fourth) / scale)
                }
                if (!error.isFinite() || tmp.any { !it.isFinite() }) { h *= .2; continue }
                if (error <= 1) {
                    val next = State(tmp[0], tmp[1], tmp[2], tmp[3])
                    val minRadius = segmentPrimaryDistance(state, next)
                    closest = min(closest, minRadius)
                    if (minRadius <= EXCLUSION) { reason = "near-primary"; break@loop }
                    if (hypot(next.x, next.y) >= FAR_FIELD) { reason = "far-field"; break@loop }
                    state = next; y = next.array(); time += h
                    drift = max(drift, abs(jacobi(state) - c0) / max(1.0, abs(c0)))
                    excursion = max(excursion, distance(state, center))
                    if (departure == null && distance(state, center) > table.neighborhood)
                        departure = time / table.clock
                }
                h *= if (error == 0.0) 4.0 else (.9 * error.pow(-.2)).coerceIn(.2, 4.0)
            }
            states[frame] = state; valid = frame + 1
        }
        // A terminated particle keeps its last valid state. Its excitation is faded by the mapper;
        // it is never restarted, wrapped, or used to claim further Jacobi conservation.
        for (i in valid until frames) states[i] = state
        return Path(family, id, states, rate,
            Report(drift, closest, excursion, departure, work, reason, valid))
    }
}
