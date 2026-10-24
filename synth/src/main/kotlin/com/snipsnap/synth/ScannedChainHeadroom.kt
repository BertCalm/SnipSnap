package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Initial-only physical headroom. Neither damping nor the moving state is normalized. */
internal object ScannedChainHeadroom {
    const val MASSES = 128
    const val COORDINATE_TARGET = .68
    const val MAX_STIFFNESS_GAIN = 3.0
    const val SOURCE_DRIVE_MULTIPLIER = 2.4
    private const val RING_GREEN_DIAGONAL = (MASSES * MASSES - 1.0) / (12 * MASSES)

    data class Plan(
        val initialGain: Double,
        val unscaledEnergy: Double,
        /** Integral of the unscaled external force norm, bounded independently of phase. */
        val forceIntegralBound: Double,
        /** Continuous-energy bound after applying [initialGain], with numerical headroom. */
        val predictedMaxDisplacement: Double,
        val authoredReferenceStrain: Double,
        /** The constitutive law's reference strain, after the same initial gain as coordinates. */
        val referenceStrain: Double,
        /** Unscaled displacement coefficient of the external force. */
        val sourceDriveScale: Double,
        val stiffnessGain: Double,
    )

    class Driver(seed: Int) {
        private val random = Random(seed)
        val phases = DoubleArray(3) { random.nextDouble() * 2 * PI }
        val shape = DoubleArray(MASSES) { i ->
            val p = 2 * PI * i / MASSES
            sin(p + phases[0]) + .28 * kotlin.math.cos(2 * p + phases[1]) + .16 * sin(3 * p + phases[2])
        }.also { values ->
            val mean = values.average()
            for (i in values.indices) values[i] -= mean
        }
    }

    fun forV2(config: ScannedChainModelV1.Config, authored: DoubleArray, driver: Driver): Plan =
        plan(config, authored, driver, boundedSpring = true)

    fun forLegacy(config: ScannedChainModelV1.Config, authored: DoubleArray, driver: Driver): Plan =
        plan(config, authored, driver, boundedSpring = false)

    private fun plan(
        config: ScannedChainModelV1.Config, authored: DoubleArray, driver: Driver, boundedSpring: Boolean,
    ): Plan {
        require(authored.size == MASSES && authored.all { it.isFinite() })
        val sourceRms = sqrt(authored.sumOf { it * it } / MASSES)
        val strain = sqrt(authored.indices.sumOf { i ->
            val r = authored[(i + 1) % MASSES] - authored[i]
            r * r
        } / MASSES)
        val g = if (boundedSpring) MAX_STIFFNESS_GAIN * config.nonlinear / 8.0 else config.nonlinear.toDouble()
        val driveScale = if (boundedSpring) SOURCE_DRIVE_MULTIPLIER * sourceRms else .12
        val omega = 2 * PI * config.mechanicalHz * config.motion
        val eigenvalue = 4 * sin(PI / MASSES) * sin(PI / MASSES)
        val k = omega * omega * (1 - config.restFraction) / eigenvalue
        val center = omega * omega * config.restFraction
        if (config.frozen || config.motion == 0f) {
            return Plan(1.0, 0.0, 0.0, authored.maxOf { abs(it) } * config.velocity,
                strain, strain, driveScale, g)
        }
        var energy = 0.0
        for (i in authored.indices) {
            val r = (authored[(i + 1) % MASSES] - authored[i]) * config.velocity
            energy += if (boundedSpring) potential(k, g, strain, r)
                else .5 * k * r * r + .25 * k * g * r * r * r * r
        }
        // |signal| <= 1.22; the complete sin² force window integrates to length/2.
        val window = (config.seconds - config.releaseSeconds).toDouble().coerceIn(.05, 2.0)
        val norm = sqrt(driver.shape.sumOf { it * it })
        val forceIntegral = driveScale * omega * omega * config.drive * config.velocity * norm * 1.22 * window / 2
        val rootEnergyBound = sqrt(energy) + forceIntegral / sqrt(2.0)
        // The ring Green-function diagonal bounds any centered coordinate, including a
        // continuous spatial Fourier scan. Positive centering energy only strengthens it.
        val unscaledBound = sqrt(2 * RING_GREEN_DIAGONAL / k) * rootEnergyBound
        var gain = if (unscaledBound > COORDINATE_TARGET) COORDINATE_TARGET / unscaledBound else 1.0
        if (!boundedSpring && g > 0 && rootEnergyBound > 0) {
            // For the old cubic law, r² <= 2E/k, hence 4*tangent+center <=
            // 4k+24*g*E+center. Keep its existing timestep and snapshot guards intact.
            val dt = 1.0 / config.physicsRate
            val snapshotOmega = 2 * PI * config.snapshotRate * .34
            val allowed = min(4 * .20 / (dt * dt), snapshotOmega * snapshotOmega)
            val spare = (allowed - 4 * k - center).coerceAtLeast(0.0)
            val rootEnergyAllowed = sqrt(spare / (24 * g))
            gain = min(gain, rootEnergyAllowed / rootEnergyBound)
        }
        check(gain.isFinite() && gain > 0) { "scanned-chain headroom could not establish a stable initial scale" }
        return Plan(gain, energy, forceIntegral, gain * unscaledBound,
            strain, strain * gain, driveScale, g)
    }

    /** Monotone spring force with global tangent bound k*(1+1.125*g). */
    fun spring(k: Double, g: Double, reference: Double, r: Double): Double {
        val r2 = r * r
        val s2 = reference * reference
        val ratio = if (s2 == 0.0) { if (r2 == 0.0) 0.0 else 1.0 } else r2 / (r2 + s2)
        return k * r * (1 + g * ratio)
    }

    /** Exact positive potential of [spring]; a series avoids cancellation near rest. */
    fun potential(k: Double, g: Double, reference: Double, r: Double): Double {
        val r2 = r * r
        val s2 = reference * reference
        val ratio = if (s2 == 0.0) 1.0 else {
            val u = r2 / s2
            if (u < 1e-3) u * (.5 - u / 3 + u * u / 4 - u * u * u / 5 + u * u * u * u / 6)
            else 1 - ln1p(u) / u
        }
        return .5 * k * r2 * (1 + g * ratio)
    }
}
