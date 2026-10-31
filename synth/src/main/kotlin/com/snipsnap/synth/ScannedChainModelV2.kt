package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private typealias Config = ScannedChainModelV1.Config
private typealias Diagnostics = ScannedChainModelV1.Diagnostics
private typealias CompatibilityResult = ScannedChainModelV1.Result

/**
 * Literal mass/spring scan, model 2. BEND changes a positive bounded-stiffness spring;
 * its reference strain follows authored geometry, so initial safety gain does not erase it.
 * A conservative energy/force budget scales coordinates, spring reference and driver once.
 * Moving state is never clipped, normalized, or given hidden damping.
 */
internal object ScannedChainModelV2 {
    const val MASSES = 128
    private const val STATE_LIMIT = .9
    private const val COURANT_LIMIT = .25
    private const val SCANNED_BAND_HZ = 18_500.0

    data class Result(val compatibility: CompatibilityResult, val headroom: ScannedChainHeadroom.Plan) {
        val raw get() = compatibility.raw
        val diagnostics get() = compatibility.diagnostics
        val initialShape get() = compatibility.initialShape
        val finalShape get() = compatibility.finalShape
    }

    private fun condition(shape: FloatArray): DoubleArray {
        require(shape.size == MASSES && shape.all { it.isFinite() }) { "shape must contain 128 finite points" }
        val mean = shape.sumOf { it.toDouble() } / MASSES
        val centered = DoubleArray(MASSES) { shape[it] - mean }
        val peak = centered.maxOf { abs(it) }
        val gain = if (peak > .35) .35 / peak else 1.0
        for (i in centered.indices) centered[i] *= gain
        // Recenter in double precision before applying the mean-zero energy inequality.
        val residual = centered.average()
        for (i in centered.indices) centered[i] -= residual
        return centered
    }

    private fun validate(c: Config) {
        require(c.name.isNotBlank()) { "experiment needs a name" }
        require(c.midi in 36..96) { "scanner MIDI must be 36..96" }
        require(c.velocity.isFinite() && c.velocity in 0f..1f) { "velocity must be 0..1" }
        require(c.motion.isFinite() && c.motion in 0f..1f) { "motion must be 0..1" }
        require(c.seconds.isFinite() && c.seconds in .05f..8f) { "duration must be .05..8 seconds" }
        require(c.mechanicalHz.isFinite() && c.mechanicalHz in .1f..4f) { "mechanical frequency must be .1..4 Hz" }
        require(c.dampingPerSecond.isFinite() && c.dampingPerSecond in 0f..12f) { "damping must be 0..12 per second" }
        require(c.nonlinear.isFinite() && c.nonlinear in 0f..8f) { "cubic spring coefficient must be 0..8" }
        require(c.restFraction.isFinite() && c.restFraction in 0f.. .35f) { "rest fraction must be 0...35" }
        require(c.drive.isFinite() && c.drive in 0f..1f) { "drive must be 0..1" }
        require(c.driveHz.isFinite() && c.driveHz in .1f..8f) { "drive frequency must be .1..8 Hz" }
        require(c.physicsRate in 2_000..8_000) { "physics rate must be 2000..8000 Hz" }
        require(c.snapshotRate in 500..c.physicsRate && c.physicsRate % c.snapshotRate == 0) {
            "snapshot rate must divide the physics rate, and be at least 500 Hz"
        }
        require(c.attackSeconds.isFinite() && c.attackSeconds in 0f..1f) { "attack must be 0..1 second" }
        require(c.releaseSeconds.isFinite() && c.releaseSeconds in 0f..2f) { "release must be 0..2 seconds" }
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("scanned-chain audition no longer wanted")
        }
    }

    private data class Projection(
        val cosine: DoubleArray, val sine: DoubleArray,
        val cosineVelocity: DoubleArray, val sineVelocity: DoubleArray,
    )

    private class Chain(
        val config: Config, val original: DoubleArray,
        private val driver: ScannedChainHeadroom.Driver, private val headroom: ScannedChainHeadroom.Plan,
    ) {
        val x = original.copyOf()
        val v = DoubleArray(MASSES)
        private val nextX = DoubleArray(MASSES)
        private val halfV = DoubleArray(MASSES)
        private val force0 = DoubleArray(MASSES)
        private val force1 = DoubleArray(MASSES)
        private val drive0 = DoubleArray(MASSES)
        private val drive1 = DoubleArray(MASSES)
        private val bondForces = DoubleArray(MASSES)
        private val phases = driver.phases
        private val driveShape = driver.shape
        private val omega = 2 * PI * config.mechanicalHz * config.motion
        private val fundamentalEigenvalue = 4 * sin(PI / MASSES) * sin(PI / MASSES)
        private val k = omega * omega * (1 - config.restFraction) / fundamentalEigenvalue
        private val stiffnessGain = headroom.stiffnessGain
        private val referenceStrain = headroom.referenceStrain
        private val center = omega * omega * config.restFraction
        private val dt = 1.0 / config.physicsRate
        private val halfDamping = exp(-config.dampingPerSecond * config.motion * dt * .5)
        private val immobile = config.frozen || config.motion == 0f
        private val driveLength = (config.seconds - config.releaseSeconds).toDouble().coerceIn(.05, 2.0)
        private val driveAngularRate = 2 * PI * config.driveHz * config.motion
        private val secondaryDriveAngularRate = 2 * PI * config.driveHz * config.motion * .618
        private val driveAmplitude = (headroom.sourceDriveScale * headroom.initialGain * omega * omega
            * config.drive * config.velocity)
        // These bounds depend on the saved model and controls, never on moving state.
        private val tangentBound = k * (1 + 1.125 * stiffnessGain)
        private val omegaSquaredBound = 4 * tangentBound + center
        private val courantBound = omegaSquaredBound * dt * dt / 4
        private val modeHzBound = sqrt(omegaSquaredBound) / (2 * PI)
        var steps = 0; private set
        var time = 0.0; private set
        var dampingLoss = 0.0; private set
        var driveWork = 0.0; private set
        var maxDisplacement = original.maxOf { abs(it) }; private set
        var maxVelocity = 0.0; private set
        var maxCourant = 0.0; private set
        var maxLinearizedModeHz = 0.0; private set
        var motionSquared = 0.0; private set
        val initialEnergy = energy()
        var maxEnergy = initialEnergy; private set

        fun energy(): Double {
            var e = 0.0
            for (i in 0 until MASSES) {
                val r = x[(i + 1) % MASSES] - x[i]
                val offset = x[i] - original[i]
                e += .5 * v[i] * v[i] + ScannedChainHeadroom.potential(k, stiffnessGain, referenceStrain, r)
                e += .5 * center * offset * offset
            }
            return e
        }

        private fun forces(state: DoubleArray, t: Double, forces: DoubleArray, external: DoubleArray) {
            val driveWindow = if (!immobile && t < driveLength) sin(PI * t / driveLength).let { it * it } else 0.0
            val driveSignal = (sin(driveAngularRate * t + phases[0])
                + .22 * sin(secondaryDriveAngularRate * t + phases[1]))
            val amplitude = driveAmplitude * driveWindow * driveSignal
            // Each right-hand bond is also the next mass's left-hand bond. Reuse its
            // identical subtraction and spring evaluation without changing force order.
            for (i in 0 until MASSES) {
                val right = state[(i + 1) % MASSES] - state[i]
                bondForces[i] = ScannedChainHeadroom.spring(k, stiffnessGain, referenceStrain, right)
            }
            for (i in 0 until MASSES) {
                external[i] = amplitude * driveShape[i]
                forces[i] = (bondForces[i] - bondForces[(i + MASSES - 1) % MASSES]
                    - center * (state[i] - original[i]) + external[i])
            }
        }

        private fun measureBounds(state: DoubleArray) {
            // dF/dr <= k*(1+1.125*g) for every displacement, not just at initialization.
            for (i in 0 until MASSES) {
                val displacement = abs(state[i])
                check(state[i].isFinite() && displacement <= STATE_LIMIT) {
                    "scanned-chain displacement exceeded finite experiment bound at step $steps"
                }
                maxDisplacement = max(maxDisplacement, displacement)
            }
            // Gershgorin bound for the periodic spring tangent matrix plus centering springs.
            maxCourant = max(maxCourant, courantBound)
            check(courantBound < COURANT_LIMIT) { "scanned-chain conservative Verlet stability bound failed: $courantBound" }
            maxLinearizedModeHz = max(maxLinearizedModeHz, modeHzBound)
            check(immobile || modeHzBound < config.snapshotRate * .35) {
                "snapshot rate has insufficient headroom for the chain's linearized mechanical modes"
            }
        }

        /** Strang split exact viscous damping around simultaneous velocity-Verlet updates. */
        fun step() {
            if (immobile) { time += dt; return }
            measureBounds(x)
            forces(x, time, force0, drive0)
            for (i in 0 until MASSES) {
                val before = v[i]
                v[i] *= halfDamping
                dampingLoss += .5 * (before * before - v[i] * v[i])
                halfV[i] = v[i] + .5 * dt * force0[i]
                nextX[i] = x[i] + dt * halfV[i]
            }
            measureBounds(nextX)
            forces(nextX, time + dt, force1, drive1)
            for (i in 0 until MASSES) {
                driveWork += .5 * (drive0[i] + drive1[i]) * (nextX[i] - x[i])
                val before = halfV[i] + .5 * dt * force1[i]
                v[i] = before * halfDamping
                dampingLoss += .5 * (before * before - v[i] * v[i])
                check(v[i].isFinite()) { "scanned-chain velocity became non-finite" }
                maxVelocity = max(maxVelocity, abs(v[i]))
                x[i] = nextX[i]
                val change = x[i] - original[i]
                motionSquared += change * change
            }
            steps++
            time += dt
            maxEnergy = max(maxEnergy, energy())
        }

        fun advanceSnapshot() { repeat(config.physicsRate / config.snapshotRate) { step() } }
        fun measureInitialBounds() = measureBounds(x)
    }

    fun render(config: Config, initialShape: FloatArray): Result {
        validate(config)
        checkCancellation()
        val authored = condition(initialShape)
        val driver = ScannedChainHeadroom.Driver(config.seed)
        val headroom = ScannedChainHeadroom.forV2(config, authored, driver)
        val original = DoubleArray(MASSES) { authored[it] * config.velocity.toDouble() * headroom.initialGain }
        val chain = Chain(config, original, driver, headroom)
        chain.measureInitialBounds()
        val scanHz = Keys.midiHz(config.midi).toDouble()
        // One immutable bandwidth budget across all four physical macros. BEND must not
        // act as a hidden filter when MOTION is zero. This reserves linearized modal
        // headroom; it is not a proof of a strict nonlinear temporal spectral cutoff.
        val omega = 2 * PI * config.mechanicalHz
        val k = omega * omega * (1 - config.restFraction) / (4 * sin(PI / MASSES) * sin(PI / MASSES))
        val sidebandReserve = sqrt(4 * k * (1 + 1.125 * ScannedChainHeadroom.MAX_STIFFNESS_GAIN)
            + omega * omega * config.restFraction) / (2 * PI)
        val harmonics = floor((SCANNED_BAND_HZ - sidebandReserve) / scanHz).toInt().coerceIn(1, 63)
        val basisCos = Array(harmonics) { h -> DoubleArray(MASSES) { i -> cos(2 * PI * (h + 1) * i / MASSES) } }
        val basisSin = Array(harmonics) { h -> DoubleArray(MASSES) { i -> sin(2 * PI * (h + 1) * i / MASSES) } }
        fun projectionBuffer() = Projection(DoubleArray(harmonics), DoubleArray(harmonics), DoubleArray(harmonics), DoubleArray(harmonics))
        fun project(into: Projection): Projection {
            val a = into.cosine; val b = into.sine
            val av = into.cosineVelocity; val bv = into.sineVelocity
            val positions = chain.x; val velocities = chain.v
            for (h in 0 until harmonics) {
                val cosines = basisCos[h]; val sines = basisSin[h]
                // Keep each dot product's original sequential addition order. Locals
                // avoid four array read/write pairs per mass and remain render-private.
                var cosine = 0.0; var sine = 0.0
                var cosineVelocity = 0.0; var sineVelocity = 0.0
                for (i in 0 until MASSES) {
                    cosine += positions[i] * cosines[i]; sine += positions[i] * sines[i]
                    cosineVelocity += velocities[i] * cosines[i]; sineVelocity += velocities[i] * sines[i]
                }
                a[h] = cosine * (2.0 / MASSES); b[h] = sine * (2.0 / MASSES)
                av[h] = cosineVelocity * (2.0 / MASSES); bv[h] = sineVelocity * (2.0 / MASSES)
            }
            return into
        }
        var left = project(projectionBuffer())
        val frozen = config.frozen || config.motion == 0f
        var right = if (frozen) left else { chain.advanceSnapshot(); project(projectionBuffer()) }
        val snapshotDt = 1.0 / config.snapshotRate
        var interval = 0
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = (config.seconds * Dsp.RATE).roundToInt()
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        val cosPhase = DoubleArray(harmonics) { 1.0 }
        val sinPhase = DoubleArray(harmonics)
        val cosStep = DoubleArray(harmonics) { cos(2 * PI * scanHz * (it + 1) / rate) }
        val sinStep = DoubleArray(harmonics) { sin(2 * PI * scanHz * (it + 1) / rate) }
        val duration = frames.toDouble() / Dsp.RATE
        fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
        for (n in raw.indices) {
            if (n % 4096 == 0) checkCancellation()
            val time = n.toDouble() / rate
            if (!frozen) {
                while (time >= (interval + 1) * snapshotDt) {
                    interval++
                    val reused = left
                    left = right
                    chain.advanceSnapshot()
                    right = project(reused)
                }
            }
            val fraction = if (frozen) 0.0 else ((time - interval * snapshotDt) / snapshotDt).coerceIn(0.0, 1.0)
            val f2 = fraction * fraction; val f3 = f2 * fraction
            val h00 = 2 * f3 - 3 * f2 + 1; val h10 = f3 - 2 * f2 + fraction
            val h01 = -2 * f3 + 3 * f2; val h11 = f3 - f2
            val h10dt = h10 * snapshotDt; val h11dt = h11 * snapshotDt
            val leftCosine = left.cosine; val leftSine = left.sine
            val leftCosineVelocity = left.cosineVelocity; val leftSineVelocity = left.sineVelocity
            val rightCosine = right.cosine; val rightSine = right.sine
            val rightCosineVelocity = right.cosineVelocity; val rightSineVelocity = right.sineVelocity
            var sample = 0.0
            for (h in 0 until harmonics) {
                val a = (h00 * leftCosine[h] + h10dt * leftCosineVelocity[h]
                    + h01 * rightCosine[h] + h11dt * rightCosineVelocity[h])
                val b = (h00 * leftSine[h] + h10dt * leftSineVelocity[h]
                    + h01 * rightSine[h] + h11dt * rightSineVelocity[h])
                val phaseCosine = cosPhase[h]; val phaseSine = sinPhase[h]
                val stepCosine = cosStep[h]; val stepSine = sinStep[h]
                sample += a * phaseCosine + b * phaseSine
                val nextCos = phaseCosine * stepCosine - phaseSine * stepSine
                sinPhase[h] = phaseSine * stepCosine + phaseCosine * stepSine
                cosPhase[h] = nextCos
            }
            val attack = if (config.attackSeconds == 0f) 1.0 else smooth(time / config.attackSeconds)
            val release = if (config.releaseSeconds == 0f) 1.0 else smooth((duration - time) / config.releaseSeconds)
            raw[n] = (sample * attack * release).toFloat()
        }
        checkCancellation()
        val output = Dsp.decimate(raw, Dsp.RATE)
        checkCancellation()
        val dc = output.sumOf { it.toDouble() } / output.size
        for (i in output.indices) output[i] = (output[i] - dc).toFloat()
        check(output.all { it.isFinite() }) { "scanned-chain model 2 output became non-finite" }
        val finalEnergy = chain.energy()
        val rms = sqrt(output.sumOf { it.toDouble() * it } / output.size)
        val diagnostic = Diagnostics(chain.initialEnergy, finalEnergy, chain.maxEnergy,
            chain.driveWork, chain.dampingLoss, chain.maxDisplacement, chain.maxVelocity,
            chain.maxCourant, sqrt(chain.motionSquared / (MASSES * chain.steps.toDouble()).coerceAtLeast(1.0)),
            chain.steps, true, harmonics, scanHz, config.mechanicalHz * config.motion.toDouble(), rms,
            chain.maxLinearizedModeHz, finalEnergy - chain.initialEnergy - chain.driveWork + chain.dampingLoss)
        return Result(CompatibilityResult(Snip(output, channels = 1, sampleRate = Dsp.RATE), diagnostic,
            FloatArray(MASSES) { original[it].toFloat() }, FloatArray(MASSES) { chain.x[it].toFloat() }), headroom)
    }

    /** Successfully rendered model-1 patches never enter this compatibility fallback. */
    fun renderLegacyFallback(config: Config, initialShape: FloatArray): CompatibilityResult {
        validate(config)
        checkCancellation()
        val authored = condition(initialShape)
        val plan = ScannedChainHeadroom.forLegacy(config, authored, ScannedChainHeadroom.Driver(config.seed))
        val points = FloatArray(MASSES) { (authored[it] * plan.initialGain).toFloat() }
        return ScannedChainModelV1.render(config.copy(drive = (config.drive * plan.initialGain).toFloat()), points)
    }

}
