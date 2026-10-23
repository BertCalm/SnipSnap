package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.CancellationException

/**
 * A constraint-eliminated sharp-rim cylinder reference, with world-frame vectors.
 * The body-to-world quaternion is [w,x,y,z]. A selected contact point changes as
 * the disk turns: its derivative is D r(n)[omega cross n], not omega cross r.
 *
 * With v = -omega cross r, the no-slip Newton--Euler equations reduce to
 * (I + m*(|r|² Id - r rᵀ))*omegaDot = m*r cross (g*k - omega cross rDot)
 *   + torque - (Iaxis-Itransverse)*(omega dot n)*(omega cross n).
 * This positive-definite 3x3 system is solved directly. No velocities are scaled
 * to repair energy. The independent Python reference uses DOP853; this port uses
 * error-controlled Dormand--Prince 5(4), with steps ending on descriptor times.
 * The quaternion is normalized only in the smooth orientation readout; its raw
 * norm error is measured. The source and loss constants describe an invented
 * instrument, and do not establish real-disk contact or final-impact behavior.
 *
 * Dormand--Prince coefficients: Dormand and Prince, JCAM 6 (1980), 19--26.
 */
internal object SkirlDynamics {
    const val MODEL_VERSION = 1
    private const val STATE_SIZE = 10
    private const val CACHE_SIZE = 12
    private const val TWO_PI = 2.0 * PI

    data class Parameters(
        val leanAlphaRad: Double,
        val muRoll: Double,
        val terminalAlphaRad: Double,
        /** Physical seconds per musical second; every phase/event uses this scale. */
        val timeScale: Double = 1.0,
        val mass: Double = .3,
        val radius: Double = .04,
        val thickness: Double = .01,
        val gravity: Double = 9.81,
        val staticFriction: Double = .8,
        val epsilonOmega: Double = .1,
        val maxSeconds: Double = 12.0,
        val descriptorRate: Double = 1000.0,
        val maxContactHz: Double = 200.0,
        val relativeTolerance: Double = 2e-9,
        val absoluteTolerance: Double = 2e-11,
        val maxStep: Double = .004,
        val maxEvaluations: Int = 250_000,
        val model: Int = MODEL_VERSION,
    ) {
        init {
            require(leanAlphaRad.isFinite() && leanAlphaRad > 0.0 && leanAlphaRad < PI / 2)
            require(terminalAlphaRad.isFinite() && terminalAlphaRad >= .005 && terminalAlphaRad < leanAlphaRad)
            // Zero is available only for a conservative numerical fixture. Production maps have a positive floor.
            require(muRoll.isFinite() && muRoll >= 0.0)
            for (x in doubleArrayOf(timeScale, mass, radius, thickness, gravity, staticFriction,
                    epsilonOmega, maxSeconds, descriptorRate, maxContactHz, relativeTolerance,
                    absoluteTolerance, maxStep)) require(x.isFinite() && x > 0.0)
            require(maxSeconds <= 60.0 && descriptorRate <= 8000.0 && maxContactHz <= 1000.0)
            require(maxSeconds/timeScale*descriptorRate <= 64_000.0) { "SKIRL descriptor memory limit" }
            require(maxEvaluations in 1..2_000_000)
            require(model == MODEL_VERSION)
        }
    }

    enum class Exit { TERMINAL, FAILURE, HORIZON }
    enum class Reason { TERMINAL_THRESHOLD, NORMAL_REACTION, STATIC_FRICTION, RATE_LIMIT,
        SOLVER_LIMIT, NONFINITE, HORIZON, INVALID_INITIAL_STATE }

    data class Diagnostics(
        val duration: Double,
        val physicalDuration: Double,
        val initialEnergy: Double,
        val finalEnergy: Double,
        val resistanceWork: Double,
        val maxEnergyError: Double,
        val relativeEnergyError: Double,
        val minNormal: Double,
        val maxFriction: Double,
        val maxQuaternionError: Double,
        val maxHeightVelocityError: Double,
        val minResistancePower: Double,
        val startContactHz: Double,
        val endContactHz: Double,
        val evaluations: Int,
        val acceptedSteps: Int,
        val rejectedSteps: Int,
        val velocityProjectionUsed: Boolean = false,
    )

    /** Arrays are immutable snapshots by convention; renderers must never modify them. */
    class Trace internal constructor(
        val times: DoubleArray,
        val alphas: DoubleArray,
        val contactPhases: DoubleArray,
        val rimPhases: DoubleArray,
        val normals: DoubleArray,
        /** Physical watts. Per musical-second power is this value times params.timeScale. */
        val resistancePower: DoubleArray,
        val resistanceWork: DoubleArray,
        val mechanicalEnergy: DoubleArray,
        val remainingEnergy: DoubleArray,
        val omegaAxis: DoubleArray,
        val bodyMarkerPhases: DoubleArray,
        val axisPhases: DoubleArray,
        /** Flattened body-to-world [w,x,y,z] snapshots for independent mechanics probes. */
        val quaternions: DoubleArray,
        /** Flattened world angular-velocity snapshots for independent mechanics probes. */
        val angularVelocities: DoubleArray,
        val params: Parameters,
        val exit: Exit,
        val reason: Reason,
        val diagnostics: Diagnostics,
    ) {
        val duration: Double get() = times.last()
    }

    private val cache = object : LinkedHashMap<Parameters, Trace>(CACHE_SIZE + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Parameters, Trace>?) = size > CACHE_SIZE
    }

    /** Only physical inputs form the key: pitch, acoustic material, edge and velocity never enter it. */
    fun trace(params: Parameters): Trace = synchronized(cache) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SKIRL mechanics cancelled")
        cache[params] ?: solve(params).also { cache[params] = it }
    }

    internal fun clearCache() = synchronized(cache) { cache.clear() }

    /** Steady no-loss launch, independently derived in the Python conservative fixture. */
    internal fun initialState(p: Parameters): DoubleArray {
        val s = sin(p.leanAlphaRad)
        val c = cos(p.leanAlphaRad)
        val a = p.thickness * .5
        val axis = .5 * p.mass * p.radius * p.radius
        val transverse = p.mass * (3 * p.radius * p.radius + p.thickness * p.thickness) / 12
        val b = p.radius * c - a * s
        val denominator = s * (transverse * c - axis * a / p.radius * s)
        require(b > 0.0 && denominator > 0.0) { "SKIRL launch family does not cover this geometry" }
        val precession = sqrt(p.mass * p.gravity * b / denominator)
        require(precession.isFinite()) { "SKIRL nonfinite launch" }
        return DoubleArray(STATE_SIZE).also {
            it[0] = cos(p.leanAlphaRad * .5)
            it[2] = sin(p.leanAlphaRad * .5)
            it[4] = -precession * b / p.radius * s
            it[6] = precession * (1 - b / p.radius * c)
        }
    }

    /** Scalar scratch keeps the ODE inner loop free of vector/array allocation. */
    internal class Readout {
        var alpha = 0.0; var energy = 0.0; var power = 0.0
        var normal = 0.0; var friction = 0.0; var rate = 0.0
        var quaternionError = 0.0; var heightVelocityError = 0.0
        var contactPhase = 0.0; var rimPhase = 0.0; var markerPhase = 0.0; var axisPhase = 0.0
        var axisVelocity = 0.0
        var normalX = 0.0; var normalY = 0.0; var normalZ = 0.0
        var contactX = 0.0; var contactY = 0.0; var contactZ = 0.0
        var velocityX = 0.0; var velocityY = 0.0; var velocityZ = 0.0
        var forceX = 0.0; var forceY = 0.0
    }

    /** Tests may request a derivative directly for independent finite-difference checks. */
    internal fun derivative(state: DoubleArray, p: Parameters, out: DoubleArray, d: Readout = Readout()) {
        val q0 = state[0]; val q1 = state[1]; val q2 = state[2]; val q3 = state[3]
        val qnorm = sqrt(q0*q0 + q1*q1 + q2*q2 + q3*q3)
        val w = q0/qnorm; val x = q1/qnorm; val y = q2/qnorm; val z = q3/qnorm
        val nx = 2*(x*z+w*y); val ny = 2*(y*z-w*x); val nz = 1-2*(x*x+y*y)
        val s = sqrt(max(nx*nx+ny*ny, 1e-24))
        val ox = state[4]; val oy = state[5]; val oz = state[6]
        val ndx = oy*nz-oz*ny; val ndy = oz*nx-ox*nz; val ndz = ox*ny-oy*nx
        val a = p.thickness*.5
        val b = p.radius*nz-a*s
        val rx = b*nx/s; val ry = b*ny/s; val rz = -p.radius*s-a*nz
        val rdScale = p.radius*nz/s-a
        val cdScale = p.radius*ndz/(s*s*s)
        val rdx = rdScale*ndx+cdScale*nx
        val rdy = rdScale*ndy+cdScale*ny
        val rdz = rdScale*ndz
        val axis = .5*p.mass*p.radius*p.radius
        val transverse = p.mass*(3*p.radius*p.radius+p.thickness*p.thickness)/12
        val delta = axis-transverse
        val axial = ox*nx+oy*ny+oz*nz
        val resistance = -p.muRoll*p.mass*p.gravity*p.radius /
            sqrt(ox*ox+oy*oy+p.epsilonOmega*p.epsilonOmega)
        val tx = resistance*ox; val ty = resistance*oy
        val wxrdx = oy*rdz-oz*rdy
        val wxrdy = oz*rdx-ox*rdz
        val wxrdz = ox*rdy-oy*rdx
        val fx = -wxrdx; val fy = -wxrdy; val fz = p.gravity-wxrdz
        val bx = p.mass*(ry*fz-rz*fy)+tx-delta*axial*ndx
        val by = p.mass*(rz*fx-rx*fz)+ty-delta*axial*ndy
        val bz = p.mass*(rx*fy-ry*fx)-delta*axial*ndz
        val m00 = transverse+delta*nx*nx+p.mass*(ry*ry+rz*rz)
        val m11 = transverse+delta*ny*ny+p.mass*(rx*rx+rz*rz)
        val m22 = transverse+delta*nz*nz+p.mass*(rx*rx+ry*ry)
        val m01 = delta*nx*ny-p.mass*rx*ry
        val m02 = delta*nx*nz-p.mass*rx*rz
        val m12 = delta*ny*nz-p.mass*ry*rz
        // Cholesky solves the SPD constrained inertia without a matrix allocation.
        val l00 = sqrt(m00)
        val l10 = m01/l00; val l20 = m02/l00
        val l11 = sqrt(m11-l10*l10)
        val l21 = (m12-l20*l10)/l11
        val l22 = sqrt(m22-l20*l20-l21*l21)
        val u0 = bx/l00
        val u1 = (by-l10*u0)/l11
        val u2 = (bz-l20*u0-l21*u1)/l22
        val az = u2/l22
        val ay = (u1-l21*az)/l11
        val ax = (u0-l10*ay-l20*az)/l00
        val vx = -(oy*rz-oz*ry); val vy = -(oz*rx-ox*rz); val vz = -(ox*ry-oy*rx)
        val accelerationX = -(ay*rz-az*ry)-wxrdx
        val accelerationY = -(az*rx-ax*rz)-wxrdy
        val accelerationZ = -(ax*ry-ay*rx)-wxrdz
        val reactionX = p.mass*accelerationX
        val reactionY = p.mass*accelerationY
        val reactionZ = p.mass*(accelerationZ+p.gravity)
        out[0] = -.5*(ox*q1+oy*q2+oz*q3)
        out[1] = .5*(q0*ox+oy*q3-oz*q2)
        out[2] = .5*(q0*oy+oz*q1-ox*q3)
        out[3] = .5*(q0*oz+ox*q2-oy*q1)
        out[4] = ax; out[5] = ay; out[6] = az
        out[7] = vx; out[8] = vy
        out[9] = -(tx*ox+ty*oy)
        d.alpha = atan2(s,nz)
        d.energy = .5*p.mass*(vx*vx+vy*vy+vz*vz) +
            .5*(transverse*(ox*ox+oy*oy+oz*oz)+delta*axial*axial)-p.mass*p.gravity*rz
        d.power = out[9]
        d.normal = reactionZ
        d.friction = sqrt(reactionX*reactionX+reactionY*reactionY)/max(reactionZ,1e-20)
        d.rate = (rx*rdy-ry*rdx)/(rx*rx+ry*ry)/TWO_PI*p.timeScale
        d.quaternionError = abs(qnorm-1)
        d.heightVelocityError = abs(vz+rdz)
        d.contactPhase = atan2(ry,rx)
        val bodyXx = 1-2*(y*y+z*z); val bodyXy = 2*(x*y+w*z); val bodyXz = 2*(x*z-w*y)
        val bodyYx = 2*(x*y-w*z); val bodyYy = 1-2*(x*x+z*z); val bodyYz = 2*(y*z+w*x)
        d.rimPhase = atan2(bodyYx*rx+bodyYy*ry+bodyYz*rz,bodyXx*rx+bodyXy*ry+bodyXz*rz)
        d.markerPhase = atan2(bodyXy,bodyXx)
        d.axisPhase = atan2(ny,nx)
        d.axisVelocity = axial
        d.normalX = nx; d.normalY = ny; d.normalZ = nz
        d.contactX = rx; d.contactY = ry; d.contactZ = rz
        d.velocityX = vx; d.velocityY = vy; d.velocityZ = vz
        d.forceX = reactionX; d.forceY = reactionY
    }

    private class SolverLimit : RuntimeException()

    private class Stepper(val p: Parameters) {
        val k = Array(7) { DoubleArray(STATE_SIZE) }
        val temporary = DoubleArray(STATE_SIZE)
        val result = DoubleArray(STATE_SIZE)
        val readout = Readout()
        var evaluations = 0

        private fun f(y: DoubleArray, out: DoubleArray) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("SKIRL mechanics cancelled")
            if (evaluations >= p.maxEvaluations) throw SolverLimit()
            evaluations++
            derivative(y,p,out,readout)
        }

        private fun stage(y: DoubleArray,h: Double,index: Int,coefficients: DoubleArray) {
            for (i in 0 until STATE_SIZE) {
                var increment = 0.0
                for (j in coefficients.indices) increment += coefficients[j]*k[j][i]
                temporary[i] = y[i]+h*increment
            }
            f(temporary,k[index])
        }

        /** result and readout describe the fifth-order endpoint; returns the scaled 5(4) error. */
        fun step(y: DoubleArray,h: Double): Double {
            f(y,k[0])
            stage(y,h,1,A2); stage(y,h,2,A3); stage(y,h,3,A4)
            stage(y,h,4,A5); stage(y,h,5,A6)
            for (i in 0 until STATE_SIZE) {
                var increment = 0.0
                for (j in B.indices) increment += B[j]*k[j][i]
                result[i] = y[i]+h*increment
            }
            f(result,k[6])
            var error = 0.0
            for (i in 0 until STATE_SIZE) {
                var difference = 0.0
                for (j in E.indices) difference += E[j]*k[j][i]
                val scale = p.absoluteTolerance+p.relativeTolerance*max(abs(y[i]),abs(result[i]))
                val ratio = h*difference/scale
                error += ratio*ratio
            }
            return sqrt(error/STATE_SIZE)
        }
    }

    private val A2 = doubleArrayOf(1.0/5)
    private val A3 = doubleArrayOf(3.0/40,9.0/40)
    private val A4 = doubleArrayOf(44.0/45,-56.0/15,32.0/9)
    private val A5 = doubleArrayOf(19372.0/6561,-25360.0/2187,64448.0/6561,-212.0/729)
    private val A6 = doubleArrayOf(9017.0/3168,-355.0/33,46732.0/5247,49.0/176,-5103.0/18656)
    private val B = doubleArrayOf(35.0/384,0.0,500.0/1113,125.0/192,-2187.0/6784,11.0/84)
    private val E = doubleArrayOf(71.0/57600,0.0,-71.0/16695,71.0/1920,-17253.0/339200,22.0/525,-1.0/40)

    private fun margin(d: Readout,p: Parameters,reason: Reason): Double = when(reason) {
        Reason.TERMINAL_THRESHOLD -> d.alpha-p.terminalAlphaRad
        Reason.NORMAL_REACTION -> d.normal
        Reason.STATIC_FRICTION -> p.staticFriction-d.friction
        // A denser solver cannot rescue a descriptor grid that loses phase turns.
        Reason.RATE_LIMIT -> min(p.maxContactHz,p.descriptorRate*.4)-abs(d.rate)
        else -> 1.0
    }
    private val BOUNDARIES = arrayOf(Reason.TERMINAL_THRESHOLD,Reason.NORMAL_REACTION,
        Reason.STATIC_FRICTION,Reason.RATE_LIMIT)

    /** No output conditioning can turn these exits into a successful terminal shudder. */
    internal fun solve(p: Parameters): Trace {
        val stepper = Stepper(p)
        val y = initialState(p)
        val dy = DoubleArray(STATE_SIZE)
        val d = Readout()
        derivative(y,p,dy,d)
        val builder = TraceBuilder(p,d.energy)
        builder.add(0.0,y,d)
        var time = 0.0
        var nextDescriptor = 1
        var step = min(p.maxStep,p.timeScale/p.descriptorRate)
        var accepted = 0
        var rejected = 0
        var reason = BOUNDARIES.firstOrNull { margin(d,p,it) <= 0.0 }
        var exit = if (reason == null) Exit.HORIZON else Exit.FAILURE
        if (reason == Reason.TERMINAL_THRESHOLD) exit = Exit.TERMINAL
        try {
            while (reason == null && time < p.maxSeconds) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("SKIRL mechanics cancelled")
                val nextSample = min(p.maxSeconds,nextDescriptor*p.timeScale/p.descriptorRate)
                val h = min(step,nextSample-time)
                if (h <= 1e-13) {
                    if (nextSample-time > 1e-12) {
                        reason = Reason.SOLVER_LIMIT; exit = Exit.FAILURE; break
                    }
                    time = nextSample
                    builder.add(time/p.timeScale,y,d)
                    nextDescriptor++
                    continue
                }
                val error = stepper.step(y,h)
                if (!error.isFinite() || stepper.result.any { !it.isFinite() }) {
                    reason = Reason.NONFINITE; exit = Exit.FAILURE; break
                }
                if (error > 1.0) {
                    rejected++
                    step = h*max(.1,.9*error.pow(-.2))
                    if (step < 1e-12) { reason = Reason.SOLVER_LIMIT; exit = Exit.FAILURE }
                    continue
                }
                // Check every accepted endpoint; bracket a crossing before appending descriptors.
                var crossing: Reason? = null
                var eventStep = h
                for (boundary in BOUNDARIES) {
                    if (margin(stepper.readout,p,boundary) <= 0.0) {
                        var lo = 0.0; var hi = h
                        repeat(28) {
                            val middle = (lo+hi)*.5
                            stepper.step(y,middle)
                            if (margin(stepper.readout,p,boundary) > 0.0) lo = middle else hi = middle
                        }
                        if (hi <= eventStep) { eventStep = hi; crossing = boundary }
                        // Restore the accepted endpoint before checking the next boundary.
                        stepper.step(y,h)
                    }
                }
                if (crossing != null) {
                    stepper.step(y,eventStep)
                    stepper.result.copyInto(y)
                    time += eventStep
                    derivative(y,p,dy,d)
                    accepted++
                    builder.observe(y,d)
                    builder.add(time/p.timeScale,y,d)
                    reason = crossing
                    exit = if (crossing == Reason.TERMINAL_THRESHOLD) Exit.TERMINAL else Exit.FAILURE
                    break
                }
                stepper.result.copyInto(y)
                time += h
                derivative(y,p,dy,d)
                accepted++
                builder.observe(y,d)
                if (abs(time-nextSample) < 1e-12) {
                    time = nextSample
                    builder.add(time/p.timeScale,y,d)
                    nextDescriptor++
                }
                step = min(p.maxStep,h*if (error < 1e-16) 5.0 else min(5.0,.9*error.pow(-.2)))
            }
        } catch (_: SolverLimit) {
            reason = Reason.SOLVER_LIMIT; exit = Exit.FAILURE
        }
        if (builder.lastTime < time/p.timeScale-1e-12) builder.add(time/p.timeScale,y,d)
        return builder.finish(exit,reason ?: Reason.HORIZON,stepper.evaluations,accepted,rejected,d)
    }

    private class TraceBuilder(val p: Parameters,val initialEnergy: Double) {
        private val capacity = ceil(p.maxSeconds/p.timeScale*p.descriptorRate).toInt()+2
        private val arrays = Array(12) { DoubleArray(capacity) }
        private val lastRawPhases = DoubleArray(12)
        private val quaternions = DoubleArray(capacity*4)
        private val velocities = DoubleArray(capacity*3)
        private var size = 0
        private var energyError = 0.0
        private var normalMin = Double.POSITIVE_INFINITY
        private var frictionMax = 0.0
        private var quaternionError = 0.0
        private var heightError = 0.0
        private var powerMin = Double.POSITIVE_INFINITY
        private var firstRate = 0.0
        val lastTime: Double get() = if (size == 0) -1.0 else arrays[0][size-1]

        fun observe(y: DoubleArray,d: Readout) {
            energyError = max(energyError,abs(d.energy-initialEnergy+y[9]))
            normalMin = min(normalMin,d.normal)
            frictionMax = max(frictionMax,d.friction)
            quaternionError = max(quaternionError,d.quaternionError)
            heightError = max(heightError,d.heightVelocityError)
            powerMin = min(powerMin,d.power)
        }

        private fun unwrap(value: Double,column: Int): Double {
            val previousRaw = lastRawPhases[column]
            lastRawPhases[column] = value
            if (size == 0) return value
            var delta = value-previousRaw
            if (delta > PI) delta -= TWO_PI
            if (delta < -PI) delta += TWO_PI
            return arrays[column][size-1]+delta
        }

        fun add(time: Double,y: DoubleArray,d: Readout) {
            if (size > 0 && time <= lastTime+1e-14) return
            check(size < capacity)
            if (size == 0) firstRate = d.rate
            observe(y,d)
            arrays[0][size] = time
            arrays[1][size] = d.alpha
            arrays[2][size] = unwrap(d.contactPhase,2)
            arrays[3][size] = unwrap(d.rimPhase,3)
            arrays[4][size] = d.normal
            arrays[5][size] = d.power
            arrays[6][size] = y[9]
            arrays[7][size] = d.energy
            arrays[8][size] = d.energy-p.mass*p.gravity*p.thickness*.5
            arrays[9][size] = d.axisVelocity
            arrays[10][size] = unwrap(d.markerPhase,10)
            arrays[11][size] = unwrap(d.axisPhase,11)
            for (i in 0..3) quaternions[size*4+i] = y[i]
            for (i in 0..2) velocities[size*3+i] = y[i+4]
            size++
        }

        fun finish(exit: Exit,reason: Reason,evaluations: Int,accepted: Int,rejected: Int,d: Readout): Trace {
            val a = Array(12) { arrays[it].copyOf(size) }
            val diagnostics = Diagnostics(a[0].last(),a[0].last()*p.timeScale,initialEnergy,
                a[7].last(),a[6].last(),energyError,energyError/max(initialEnergy,1e-30),
                normalMin,frictionMax,quaternionError,heightError,powerMin,firstRate,d.rate,
                evaluations,accepted,rejected)
            return Trace(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9],a[10],a[11],
                quaternions.copyOf(size*4),velocities.copyOf(size*3),p,exit,reason,diagnostics)
        }
    }
}
