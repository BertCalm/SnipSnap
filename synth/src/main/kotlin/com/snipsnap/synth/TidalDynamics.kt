package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Finite, dimensionless restricted encounter. Two unit masses interact through
 * the same softened potential used by massless particles. Nothing here runs at
 * audio rate. Original population/radial/azimuthal membership never changes.
 *
 * This is a musical preparation domain, not an astrophysical calibration.
 * The complete 22-unit simulation always maps to eight seconds; macros change
 * initial conditions, never the playback clock. HOLD is deliberately separate.
 */
internal object TidalDynamics {
    const val MODEL_VERSION = 1
    const val COHORT_COUNT = 32
    const val SOFTENING = .08
    const val SIMULATION_DURATION = 22.0
    const val AUDIO_DURATION = 8.0
    private const val INITIAL_SEPARATION = 8.0
    private const val TOTAL_MASS = 2.0
    private const val FRAME_STEP = .04
    private const val DESCRIPTOR_SMOOTHING_SECONDS = .035
    private const val DOMAIN_RADIUS = 256.0
    private const val GRID_SIDE = 17
    private const val GRID_VOLUME = GRID_SIDE * GRID_SIDE * GRID_SIDE

    data class Settings(
        val approach: Double = .45,
        val passage: Double = .55,
        val tilt: Double = .35,
        val bind: Double = .50,
        val seed: Long = 0L,
        val particleCount: Int = 2_048,
        val step: Double = .005,
        /** Remove differential companion force, retaining uniform center acceleration. */
        val noTidalGradient: Boolean = false,
    ) {
        init {
            require(listOf(approach, passage, tilt, bind).all { it.isFinite() && it in 0.0..1.0 }) {
                "TIDAL dynamics macros must be finite 0..1"
            }
            require(particleCount in 64..10_000 && particleCount % 2 == 0) {
                "TIDAL preparation requires an even 64..10000 particles"
            }
            require(step.isFinite() && step in .00125.. .01) { "TIDAL step must be .00125..01" }
        }
    }

    data class Point(val x: Double, val y: Double, val z: Double)

    data class Cohort(
        val id: Int,
        val population: Int,
        val weight: Double,
        val core: Double,
        val extension: Double,
        val stretch: Double,
        val concentration: Double,
        /** Signed outward radial motion encoded 0..1; .5 is stationary radius. */
        val motion: Double,
        /** Original-center affinity: one is original, zero is companion. */
        val affinity: Double,
    )

    data class Frame(
        val timeSeconds: Double,
        val cohorts: Array<Cohort>,
        val centers: Array<Point>,
        val centerSeparation: Double,
        val encounter: Double,
    )

    data class Diagnostics(
        val targetPericenter: Double,
        val minimumSeparation: Double,
        val encounterTimeSeconds: Double,
        val maxCenterEnergyRelativeError: Double,
        val maxCenterMomentum: Double,
        val maxCenterPositionDrift: Double,
        val maxParticleRadius: Double,
        val maxParticleHeight: Double,
        val maxRadialRatioDeviation: Double,
        val outlierCount: Int,
        val maxExtension: Double,
        val finalExtension: Double,
        val particleCount: Int,
        val step: Double,
        val finalParticleFingerprint: Long,
    )

    data class Prepared(val durationSeconds: Double, val frames: List<Frame>, val diagnostics: Diagnostics)

    private class State(count: Int) {
        val x = DoubleArray(count); val y = DoubleArray(count); val z = DoubleArray(count)
        val vx = DoubleArray(count); val vy = DoubleArray(count); val vz = DoubleArray(count)
        val ax = DoubleArray(count); val ay = DoubleArray(count); val az = DoubleArray(count)
    }

    /** Softened circular speed around one unit center, consistent with the force. */
    internal fun circularSpeed(radius: Double): Double {
        require(radius.isFinite() && radius > 0.0)
        val r2 = radius * radius + SOFTENING * SOFTENING
        return sqrt(radius * radius / (r2 * sqrt(r2)))
    }

    fun prepare(settings: Settings, cancel: () -> Boolean = { false }): Prepared {
        checkCancelled(cancel)
        val centers = State(2)
        val particles = State(settings.particleCount)
        val ids = IntArray(settings.particleCount)
        val initialRadius = DoubleArray(settings.particleCount)
        val initialSpeed = DoubleArray(settings.particleCount)
        val counts = IntArray(COHORT_COUNT)
        val outerRadius = .65 + .8 * (1.0 - settings.bind)
        val innerRadius = .18 + .22 * (1.0 - settings.bind)
        val targetPericenter = 3.8 - 3.1 * settings.passage
        val speedAtInfinity = .25 + 1.1 * settings.approach
        initializeCenters(centers, targetPericenter, speedAtInfinity)
        val random = Random(Dsp.seedFor("TIDAL_DYNAMICS", MODEL_VERSION, settings.seed))
        val half = settings.particleCount / 2
        for (i in ids.indices) {
            val population = i / half
            val localCohort = (i % half) * 16 / half
            val radialBin = localCohort / 4
            val azimuthBin = localCohort % 4
            val id = population * 16 + localCohort
            ids[i] = id
            counts[id]++
            // Equal-area radial bands, with deterministic stratification inside each sector.
            val fraction = (radialBin + random.nextDouble()) * .25
            val radius = sqrt(innerRadius * innerRadius +
                (outerRadius * outerRadius - innerRadius * innerRadius) * fraction)
            val angle = (azimuthBin + random.nextDouble()) * PI * .5
            val speed = circularSpeed(radius)
            initialRadius[i] = radius
            initialSpeed[i] = speed
            // Inclination passes continuously through polar to retrograde for population B.
            val inclination = settings.tilt * PI * if (population == 0) .2 else 1.0
            val node = settings.tilt * PI * if (population == 0) -.15 else .35
            val ca = cos(angle); val sa = sin(angle)
            val cn = cos(node); val sn = sin(node)
            val ci = cos(inclination); val si = sin(inclination)
            particles.x[i] = centers.x[population] + radius * (cn * ca - sn * ci * sa)
            particles.y[i] = centers.y[population] + radius * (sn * ca + cn * ci * sa)
            particles.z[i] = centers.z[population] + radius * si * sa
            particles.vx[i] = centers.vx[population] + speed * (-cn * sa - sn * ci * ca)
            particles.vy[i] = centers.vy[population] + speed * (-sn * sa + cn * ci * ca)
            particles.vz[i] = centers.vz[population] + speed * si * ca
        }
        val cellWidth = outerRadius / 3.0
        val occupancy = IntArray(GRID_VOLUME * 2)
        val cells = IntArray(settings.particleCount)
        fun fillOccupancy() {
            occupancy.fill(0)
            for (i in ids.indices) {
                val population = ids[i] / 16
                val gx = floor((particles.x[i] - centers.x[population]) / cellWidth).toInt() + GRID_SIDE / 2
                val gy = floor((particles.y[i] - centers.y[population]) / cellWidth).toInt() + GRID_SIDE / 2
                val gz = floor((particles.z[i] - centers.z[population]) / cellWidth).toInt() + GRID_SIDE / 2
                // Outside-grid particles do not collapse into a shared boundary cell.
                val cell = if (gx in 0 until GRID_SIDE && gy in 0 until GRID_SIDE && gz in 0 until GRID_SIDE)
                    population * GRID_VOLUME + (gx * GRID_SIDE + gy) * GRID_SIDE + gz else -1
                cells[i] = cell
                if (cell >= 0) occupancy[cell]++
            }
        }
        fillOccupancy()
        val initialOccupancy = DoubleArray(COHORT_COUNT)
        for (i in ids.indices) initialOccupancy[ids[i]] += occupancy[cells[i]].toDouble() / counts[ids[i]]
        val extensionState = DoubleArray(COHORT_COUNT)
        val stretchState = DoubleArray(COHORT_COUNT)
        val concentrationState = DoubleArray(COHORT_COUNT) { 1.0 }
        val motionState = DoubleArray(COHORT_COUNT) { .5 }
        val affinityState = DoubleArray(COHORT_COUNT)
        val frames = ArrayList<Frame>()
        var lastFrameTime = 0.0
        var maxParticleRadius = 0.0
        var maxParticleHeight = 0.0
        var maxRadialRatioDeviation = 0.0
        var maxExtension = 0.0
        fun capture(time: Double) {
            fillOccupancy()
            val extension = DoubleArray(COHORT_COUNT)
            val stretch = DoubleArray(COHORT_COUNT)
            val concentration = DoubleArray(COHORT_COUNT)
            val motion = DoubleArray(COHORT_COUNT)
            val affinity = DoubleArray(COHORT_COUNT)
            for (i in ids.indices) {
                val id = ids[i]; val population = id / 16; val other = 1 - population
                val dx = particles.x[i] - centers.x[population]
                val dy = particles.y[i] - centers.y[population]
                val dz = particles.z[i] - centers.z[population]
                val radiusSquared = dx * dx + dy * dy + dz * dz
                val radius = sqrt(radiusSquared)
                val ratio = radius / initialRadius[i]
                maxParticleRadius = max(maxParticleRadius, radius)
                maxParticleHeight = max(maxParticleHeight, abs(dz))
                maxRadialRatioDeviation = max(maxRadialRatioDeviation, abs(ratio - 1.0))
                val weight = 1.0 / counts[id]
                // Winding of an unperturbed radial/azimuthal sector is NOT tail production.
                // Require both expansion of its original orbit and an extended disk location.
                extension[id] += smooth((ratio - 1.15) / 1.4) *
                    smooth((radius / outerRadius - 1.0) / .6) * weight
                stretch[id] += smooth((ratio - 1.05) / 1.95) * weight
                concentration[id] += (if (cells[i] >= 0) occupancy[cells[i]].toDouble() else 1.0) * weight
                val radialVelocity = ((particles.vx[i] - centers.vx[population]) * dx +
                    (particles.vy[i] - centers.vy[population]) * dy +
                    (particles.vz[i] - centers.vz[population]) * dz) / max(radius, .001)
                motion[id] += (.5 + .5 * tanh(radialVelocity / initialSpeed[i])) * weight
                val ox = particles.x[i] - centers.x[other]
                val oy = particles.y[i] - centers.y[other]
                val oz = particles.z[i] - centers.z[other]
                val otherSquared = ox * ox + oy * oy + oz * oz + SOFTENING * SOFTENING
                affinity[id] += otherSquared / (otherSquared + radiusSquared + SOFTENING * SOFTENING) * weight
            }
            val seconds = time * AUDIO_DURATION / SIMULATION_DURATION
            val alpha = if (frames.isEmpty()) 1.0 else
                1.0 - exp(-(seconds - lastFrameTime) / DESCRIPTOR_SMOOTHING_SECONDS)
            val cohorts = Array(COHORT_COUNT) { id ->
                extensionState[id] += alpha * (extension[id] - extensionState[id])
                stretchState[id] += alpha * (stretch[id] - stretchState[id])
                concentrationState[id] += alpha *
                    ((concentration[id] / initialOccupancy[id]).coerceIn(0.0, 1.0) - concentrationState[id])
                motionState[id] += alpha * (motion[id] - motionState[id])
                affinityState[id] += alpha * (affinity[id] - affinityState[id])
                val tail = extensionState[id].coerceIn(0.0, 1.0)
                Cohort(id, id / 16, counts[id].toDouble() / settings.particleCount,
                    1.0 - tail, tail, stretchState[id].coerceIn(0.0, 1.0),
                    concentrationState[id].coerceIn(0.0, 1.0), motionState[id].coerceIn(0.0, 1.0),
                    affinityState[id].coerceIn(0.0, 1.0))
            }
            val separation = separation(centers)
            val encounterRatio = separation / 1.6
            val encounter = 1.0 / (1.0 + encounterRatio * encounterRatio * encounterRatio * encounterRatio)
            frames.add(Frame(seconds, cohorts, Array(2) { Point(centers.x[it], centers.y[it], centers.z[it]) },
                separation, encounter))
            maxExtension = max(maxExtension, cohorts.sumOf { it.weight * it.extension })
            lastFrameTime = seconds
        }
        capture(0.0)
        val initialEnergy = centerEnergy(centers)
        var minimumSeparation = INITIAL_SEPARATION
        var encounterTime = 0.0
        var maxEnergyError = 0.0
        var maxMomentum = 0.0
        var maxPositionDrift = 0.0
        val steps = ceil(SIMULATION_DURATION / settings.step).toInt()
        val dt = SIMULATION_DURATION / steps
        var nextFrameTime = FRAME_STEP
        acceleration(centers, particles, ids, settings.noTidalGradient)
        for (step in 1..steps) {
            if (step % 16 == 0) checkCancelled(cancel)
            drift(centers, dt)
            drift(particles, dt)
            acceleration(centers, particles, ids, settings.noTidalGradient)
            finishKick(centers, dt)
            finishKick(particles, dt)
            val time = step * dt
            val separation = separation(centers)
            if (separation < minimumSeparation) { minimumSeparation = separation; encounterTime = time }
            maxEnergyError = max(maxEnergyError, abs(centerEnergy(centers) - initialEnergy) / abs(initialEnergy))
            maxMomentum = max(maxMomentum, vectorLength(centers.vx[0] + centers.vx[1],
                centers.vy[0] + centers.vy[1], centers.vz[0] + centers.vz[1]))
            maxPositionDrift = max(maxPositionDrift, vectorLength((centers.x[0] + centers.x[1]) * .5,
                (centers.y[0] + centers.y[1]) * .5, (centers.z[0] + centers.z[1]) * .5))
            if (time + 1e-10 >= nextFrameTime || step == steps) {
                capture(time)
                nextFrameTime += FRAME_STEP
            }
        }
        checkCancelled(cancel)
        check(abs(minimumSeparation - targetPericenter) < .002) { "TIDAL encounter missed target pericenter" }
        var fingerprint = 17L
        for (i in ids.indices) for (value in doubleArrayOf(particles.x[i], particles.y[i], particles.z[i],
            particles.vx[i], particles.vy[i], particles.vz[i])) fingerprint = fingerprint * 31 + value.toBits()
        val diagnostics = Diagnostics(targetPericenter, minimumSeparation,
            encounterTime * AUDIO_DURATION / SIMULATION_DURATION, maxEnergyError, maxMomentum,
            maxPositionDrift, maxParticleRadius, maxParticleHeight, maxRadialRatioDeviation,
            0, maxExtension, frames.last().cohorts.sumOf { it.weight * it.extension },
            settings.particleCount, dt, fingerprint)
        return Prepared(AUDIO_DURATION, frames, diagnostics)
    }

    private fun initializeCenters(c: State, pericenter: Double, speedAtInfinity: Double) {
        // Specific relative energy and angular momentum in -G(M1+M2)/sqrt(r²+eps²).
        val energy = .5 * speedAtInfinity * speedAtInfinity
        val angularMomentum = sqrt(2.0 * pericenter * pericenter *
            (energy + TOTAL_MASS / sqrt(pericenter * pericenter + SOFTENING * SOFTENING)))
        val tangential = angularMomentum / INITIAL_SEPARATION
        val radial = -sqrt(2.0 * (energy + TOTAL_MASS /
            sqrt(INITIAL_SEPARATION * INITIAL_SEPARATION + SOFTENING * SOFTENING)) - tangential * tangential)
        c.x[0] = -INITIAL_SEPARATION * .5; c.x[1] = INITIAL_SEPARATION * .5
        c.vx[0] = -radial * .5; c.vx[1] = radial * .5
        c.vy[0] = -tangential * .5; c.vy[1] = tangential * .5
    }

    private fun acceleration(c: State, p: State, ids: IntArray, noTidalGradient: Boolean) {
        val dx = c.x[1] - c.x[0]; val dy = c.y[1] - c.y[0]; val dz = c.z[1] - c.z[0]
        val r2 = dx * dx + dy * dy + dz * dz + SOFTENING * SOFTENING
        val inverseCube = 1.0 / (r2 * sqrt(r2))
        c.ax[0] = dx * inverseCube; c.ax[1] = -c.ax[0]
        c.ay[0] = dy * inverseCube; c.ay[1] = -c.ay[0]
        c.az[0] = dz * inverseCube; c.az[1] = -c.az[0]
        for (i in ids.indices) {
            val population = ids[i] / 16
            val ownX = p.x[i] - c.x[population]
            val ownY = p.y[i] - c.y[population]
            val ownZ = p.z[i] - c.z[population]
            val ownSquared = ownX * ownX + ownY * ownY + ownZ * ownZ
            check(ownSquared.isFinite() && ownSquared <= DOMAIN_RADIUS * DOMAIN_RADIUS) {
                "TIDAL particle $i left preparation domain; no teleport or wrapping is applied"
            }
            if (noTidalGradient) {
                val softened = ownSquared + SOFTENING * SOFTENING
                val inverse = 1.0 / (softened * sqrt(softened))
                p.ax[i] = -ownX * inverse + c.ax[population]
                p.ay[i] = -ownY * inverse + c.ay[population]
                p.az[i] = -ownZ * inverse + c.az[population]
            } else {
                var ax = 0.0; var ay = 0.0; var az = 0.0
                for (center in 0..1) {
                    val px = p.x[i] - c.x[center]; val py = p.y[i] - c.y[center]; val pz = p.z[i] - c.z[center]
                    val softened = px * px + py * py + pz * pz + SOFTENING * SOFTENING
                    val inverse = 1.0 / (softened * sqrt(softened))
                    ax -= px * inverse; ay -= py * inverse; az -= pz * inverse
                }
                p.ax[i] = ax; p.ay[i] = ay; p.az[i] = az
            }
        }
    }

    private fun drift(state: State, dt: Double) {
        for (i in state.x.indices) {
            state.vx[i] += .5 * dt * state.ax[i]; state.x[i] += dt * state.vx[i]
            state.vy[i] += .5 * dt * state.ay[i]; state.y[i] += dt * state.vy[i]
            state.vz[i] += .5 * dt * state.az[i]; state.z[i] += dt * state.vz[i]
        }
    }

    private fun finishKick(state: State, dt: Double) {
        for (i in state.x.indices) {
            state.vx[i] += .5 * dt * state.ax[i]
            state.vy[i] += .5 * dt * state.ay[i]
            state.vz[i] += .5 * dt * state.az[i]
        }
    }

    private fun centerEnergy(c: State): Double {
        val vx = c.vx[1] - c.vx[0]; val vy = c.vy[1] - c.vy[0]; val vz = c.vz[1] - c.vz[0]
        val radius = separation(c)
        return .5 * (vx * vx + vy * vy + vz * vz) - TOTAL_MASS / sqrt(radius * radius + SOFTENING * SOFTENING)
    }

    private fun separation(c: State) = vectorLength(c.x[1] - c.x[0], c.y[1] - c.y[0], c.z[1] - c.z[0])
    private fun vectorLength(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
    private fun smooth(value: Double): Double { val t = value.coerceIn(0.0, 1.0); return t * t * (3.0 - 2.0 * t) }
    private fun checkCancelled(cancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancel()) throw CancellationException("TIDAL preparation cancelled")
    }
}
