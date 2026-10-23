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
import kotlin.random.Random

/**
 * Version-1 production solver, copied from the immutable listening experiment: a periodic
 * mass/spring chain is a moving waveform, not an
 * audio-rate string tuned to the note. The scanner bears the note; the mechanical fundamental
 * is deliberately slow. There is no extra root oscillator, per-step leveling, state clipping,
 * live voice allocator, stereo path, or loop export.
 *
 * Fourier projection limits the *spatial* harmonics before scanning. Velocity-aware Hermite
 * interpolation between complete snapshots avoids a staircase controller. This is a bounded
 * numerical approximation, not a claim that nonlinear mechanical modulation has a strict
 * spectral cutoff. The final 4x decimator remains necessary.
 */
internal object ScannedChainModelV1 {
    const val MASSES = 128
    private const val STATE_LIMIT = .9
    private const val COURANT_LIMIT = .25 // theoretical linear Verlet limit is 1 in these units
    private const val SCANNED_BAND_HZ = 18_500.0

    enum class Voice { PLUCK, METAL, BLOOM }

    data class Config(
        val name: String,
        val voice: Voice = Voice.PLUCK,
        val midi: Int = 60,
        val velocity: Float = .8f,
        val seconds: Float = 3f,
        val motion: Float = 1f,
        val frozen: Boolean = false,
        val physicsRate: Int = 4_000,
        val snapshotRate: Int = physicsRate,
        val mechanicalHz: Float = 1.2f,
        val dampingPerSecond: Float = 1f,
        val nonlinear: Float = 4f,
        val restFraction: Float = .08f,
        val drive: Float = 0f,
        val driveHz: Float = 1.5f,
        val seed: Int = 1729,
        val attackSeconds: Float = .008f,
        val releaseSeconds: Float = .25f,
    )

    data class Diagnostics(
        val initialEnergy: Double,
        val finalEnergy: Double,
        val maxEnergy: Double,
        val driveWork: Double,
        val dampingLoss: Double,
        val maxDisplacement: Double,
        val maxVelocity: Double,
        val maxCourant: Double,
        val shapeMotionRms: Double,
        val physicsSteps: Int,
        val finite: Boolean,
        val retainedHarmonics: Int,
        val scannerHz: Double,
        val mechanicalFundamentalHz: Double,
        val rawRms: Double,
        val maxLinearizedModeHz: Double,
        val energyBalanceError: Double,
    )

    data class Result(
        val raw: Snip,
        val diagnostics: Diagnostics,
        val initialShape: FloatArray,
        val finalShape: FloatArray,
    )

    fun factoryConfigs(): List<Config> = listOf(
        Config("PLUCK", Voice.PLUCK, mechanicalHz = 1.15f, dampingPerSecond = 1.4f,
            nonlinear = 2f, restFraction = .16f),
        Config("METAL", Voice.METAL, mechanicalHz = 3.1f, dampingPerSecond = .8f,
            nonlinear = 5f, restFraction = .12f),
        Config("BLOOM", Voice.BLOOM, mechanicalHz = .62f, dampingPerSecond = .85f,
            nonlinear = 3f, restFraction = .20f, drive = .8f, driveHz = .67f,
            attackSeconds = .035f, releaseSeconds = .4f),
    )

    /** Authored waveform before velocity; render also accepts an arbitrary 128-point drawing. */
    fun initialShape(config: Config): FloatArray = condition(FloatArray(MASSES) { i ->
        val p = 2 * PI * i / MASSES
        when (config.voice) {
            Voice.PLUCK -> (sin(p) + .35 * sin(2 * p + .3) + .22 * sin(3 * p)
                + .12 * cos(5 * p)).toFloat()
            Voice.METAL -> (.65 * sin(p) + .34 * cos(2 * p) + .38 * sin(3 * p + .4)
                + .28 * sin(5 * p) + .18 * cos(8 * p) + .12 * sin(13 * p)).toFloat()
            Voice.BLOOM -> (.85 * sin(p) + .34 * sin(2 * p + .8)
                + .28 * cos(3 * p) + .20 * sin(5 * p)).toFloat()
        }
    })

    /** An original hand-authored circular trace, interpolated only once at initialization. */
    fun drawnShape(): FloatArray {
        val points = doubleArrayOf(0.0, .58, .94, .82, .25, -.32, -.4, -.1,
            .28, .40, .03, -.55, -.96, -.68, -.30, -.08)
        return condition(FloatArray(MASSES) { i ->
            val p = i.toDouble() * points.size / MASSES
            val j = floor(p).toInt()
            val t = p - j
            val a = points[(j + points.size - 1) % points.size]
            val b = points[j]
            val c = points[(j + 1) % points.size]
            val d = points[(j + 2) % points.size]
            (.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t
                + (-a + 3 * b - 3 * c + d) * t * t * t)).toFloat()
        })
    }

    /** Initial-only coordinate conditioning: zero mean, and scale down if over .35. */
    private fun condition(shape: FloatArray): FloatArray {
        require(shape.size == MASSES && shape.all { it.isFinite() }) { "shape must contain 128 finite points" }
        val mean = shape.sumOf { it.toDouble() } / MASSES
        val centered = DoubleArray(MASSES) { shape[it] - mean }
        val peak = centered.maxOf { abs(it) }
        val gain = if (peak > .35) .35 / peak else 1.0
        return FloatArray(MASSES) { (centered[it] * gain).toFloat() }
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

    private class Chain(val config: Config, val original: DoubleArray) {
        val x = original.copyOf()
        val v = DoubleArray(MASSES)
        private val nextX = DoubleArray(MASSES)
        private val halfV = DoubleArray(MASSES)
        private val force0 = DoubleArray(MASSES)
        private val force1 = DoubleArray(MASSES)
        private val drive0 = DoubleArray(MASSES)
        private val drive1 = DoubleArray(MASSES)
        private val driver = Random(config.seed)
        private val phases = DoubleArray(3) { driver.nextDouble() * 2 * PI }
        private val driveShape = DoubleArray(MASSES) { i ->
            val p = 2 * PI * i / MASSES
            sin(p + phases[0]) + .28 * cos(2 * p + phases[1]) + .16 * sin(3 * p + phases[2])
        }.also { a -> val mean = a.average(); for (i in a.indices) a[i] -= mean }
        private val omega = 2 * PI * config.mechanicalHz * config.motion
        private val fundamentalEigenvalue = 4 * sin(PI / MASSES) * sin(PI / MASSES)
        private val k = omega * omega * (1 - config.restFraction) / fundamentalEigenvalue
        private val k3 = k * config.nonlinear
        private val center = omega * omega * config.restFraction
        private val dt = 1.0 / config.physicsRate
        private val halfDamping = exp(-config.dampingPerSecond * config.motion * dt * .5)
        private val immobile = config.frozen || config.motion == 0f
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
                e += .5 * v[i] * v[i] + .5 * k * r * r + .25 * k3 * r * r * r * r
                e += .5 * center * offset * offset
            }
            return e
        }

        private fun forces(state: DoubleArray, t: Double, forces: DoubleArray, external: DoubleArray) {
            val driveLength = (config.seconds - config.releaseSeconds).toDouble().coerceIn(.05, 2.0)
            val driveWindow = if (!immobile && t < driveLength) sin(PI * t / driveLength).let { it * it } else 0.0
            val driveSignal = (sin(2 * PI * config.driveHz * config.motion * t + phases[0])
                + .22 * sin(2 * PI * config.driveHz * config.motion * .618 * t + phases[1]))
            val amplitude = .12 * omega * omega * config.drive * config.velocity * driveWindow * driveSignal
            for (i in 0 until MASSES) {
                val right = state[(i + 1) % MASSES] - state[i]
                val left = state[i] - state[(i + MASSES - 1) % MASSES]
                external[i] = amplitude * driveShape[i]
                forces[i] = (k * (right - left) + k3 * (right * right * right - left * left * left)
                    - center * (state[i] - original[i]) + external[i])
            }
        }

        private fun measureBounds(state: DoubleArray) {
            var tangent = k
            for (i in 0 until MASSES) {
                val displacement = abs(state[i])
                check(state[i].isFinite() && displacement <= STATE_LIMIT) {
                    "scanned-chain displacement exceeded finite experiment bound at step $steps"
                }
                maxDisplacement = max(maxDisplacement, displacement)
                val r = state[(i + 1) % MASSES] - state[i]
                tangent = max(tangent, k + 3 * k3 * r * r)
            }
            // Gershgorin bound for the periodic spring tangent matrix plus centering springs.
            val omegaSquaredBound = 4 * tangent + center
            val courant = omegaSquaredBound * dt * dt / 4
            maxCourant = max(maxCourant, courant)
            check(courant < COURANT_LIMIT) { "scanned-chain conservative Verlet stability bound failed: $courant" }
            val modeHz = sqrt(omegaSquaredBound) / (2 * PI)
            maxLinearizedModeHz = max(maxLinearizedModeHz, modeHz)
            check(immobile || modeHz < config.snapshotRate * .35) {
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

    fun render(config: Config, initialShape: FloatArray? = null): Result {
        validate(config)
        checkCancellation()
        val authored = condition(initialShape ?: ScannedChainModelV1.initialShape(config))
        val original = DoubleArray(MASSES) { authored[it] * config.velocity.toDouble() }
        val chain = Chain(config, original)
        chain.measureInitialBounds()
        val scanHz = Keys.midiHz(config.midi).toDouble()
        // Reserve at maximum motion so frozen, zero-motion, and moving controls retain exactly
        // the same spatial harmonics. This bounds linearized modes at the allowed displacement,
        // not every possible nonlinear modulation harmonic.
        val omega = 2 * PI * config.mechanicalHz
        val k = omega * omega * (1 - config.restFraction) / (4 * sin(PI / MASSES) * sin(PI / MASSES))
        val sidebandReserve = sqrt(4 * k * (1 + 3 * config.nonlinear * 4 * STATE_LIMIT * STATE_LIMIT)
            + omega * omega * config.restFraction) / (2 * PI)
        val harmonics = floor((SCANNED_BAND_HZ - sidebandReserve) / scanHz).toInt().coerceIn(1, 63)
        val basisCos = Array(harmonics) { h -> DoubleArray(MASSES) { i -> cos(2 * PI * (h + 1) * i / MASSES) } }
        val basisSin = Array(harmonics) { h -> DoubleArray(MASSES) { i -> sin(2 * PI * (h + 1) * i / MASSES) } }
        fun projectionBuffer() = Projection(DoubleArray(harmonics), DoubleArray(harmonics), DoubleArray(harmonics), DoubleArray(harmonics))
        fun project(into: Projection): Projection {
            val a = into.cosine; val b = into.sine
            val av = into.cosineVelocity; val bv = into.sineVelocity
            a.fill(0.0); b.fill(0.0); av.fill(0.0); bv.fill(0.0)
            for (h in 0 until harmonics) {
                for (i in 0 until MASSES) {
                    a[h] += chain.x[i] * basisCos[h][i]; b[h] += chain.x[i] * basisSin[h][i]
                    av[h] += chain.v[i] * basisCos[h][i]; bv[h] += chain.v[i] * basisSin[h][i]
                }
                a[h] *= 2.0 / MASSES; b[h] *= 2.0 / MASSES
                av[h] *= 2.0 / MASSES; bv[h] *= 2.0 / MASSES
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
            var sample = 0.0
            for (h in 0 until harmonics) {
                val a = (h00 * left.cosine[h] + h10 * snapshotDt * left.cosineVelocity[h]
                    + h01 * right.cosine[h] + h11 * snapshotDt * right.cosineVelocity[h])
                val b = (h00 * left.sine[h] + h10 * snapshotDt * left.sineVelocity[h]
                    + h01 * right.sine[h] + h11 * snapshotDt * right.sineVelocity[h])
                sample += a * cosPhase[h] + b * sinPhase[h]
                val nextCos = cosPhase[h] * cosStep[h] - sinPhase[h] * sinStep[h]
                sinPhase[h] = sinPhase[h] * cosStep[h] + cosPhase[h] * sinStep[h]
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
        check(output.all { it.isFinite() && abs(it) <= 1f }) { "scanned-chain output exceeded finite audition bound" }
        val finalEnergy = chain.energy()
        val rms = sqrt(output.sumOf { it.toDouble() * it } / output.size)
        val diagnostic = Diagnostics(chain.initialEnergy, finalEnergy, chain.maxEnergy,
            chain.driveWork, chain.dampingLoss, chain.maxDisplacement, chain.maxVelocity,
            chain.maxCourant, sqrt(chain.motionSquared / (MASSES * chain.steps.toDouble()).coerceAtLeast(1.0)),
            chain.steps, true, harmonics, scanHz, config.mechanicalHz * config.motion.toDouble(), rms,
            chain.maxLinearizedModeHz, finalEnergy - chain.initialEnergy - chain.driveWork + chain.dampingLoss)
        return Result(Snip(output, channels = 1, sampleRate = Dsp.RATE), diagnostic,
            FloatArray(MASSES) { original[it].toFloat() }, FloatArray(MASSES) { chain.x[it].toFloat() })
    }
}
