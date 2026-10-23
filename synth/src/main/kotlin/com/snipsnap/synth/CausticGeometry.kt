package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Static, single-reflection circle for the first CAUSTIC listening experiment.
 *
 * Distances are in vessel radii. The entrance is deliberately transparent: incident rays illuminate
 * the interior-facing intersection (d dot n > 0), rather than an opaque closed mirror's near side.
 * SOURCE u puts the source at (1/u, 0), with u=0 the exact parallel limit. Its direction at boundary
 * p is normalize(u*p - (1,0)). Equal arc-length samples receive cos(incidence)/|u*p-(1,0)| weights,
 * normalized over the complete illuminated launch set before any pickup is queried.
 *
 * Nine finite pickups share one fixed launch budget. A path's weight includes its 1/9 allocation;
 * moving FOCUS never renormalizes surviving paths. This is an abstract sound-routing weight, not
 * an assertion of acoustic energy, coherent pressure, or caustic-envelope accuracy.
 */
internal object CausticGeometry {
    data class Vec(val x: Double, val y: Double) {
        operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)
        operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)
        operator fun times(scale: Double) = Vec(x * scale, y * scale)
        fun dot(other: Vec) = x * other.x + y * other.y
    }

    data class Path(
        val pickup: Int,
        val delayR: Double,
        val weight: Double,
        val boundaryTheta: Double,
    )

    data class Prepared(val paths: List<Path>, val densities: DoubleArray, val rays: Int)

    private data class Launch(
        val point: Vec,
        val direction: Vec,
        val weight: Double,
        val theta: Double,
        val incidentRelative: Double,
        val extent: Double,
    )

    /** A vertical pickup band wholly inside the unit circle throughout its normalized travel. */
    fun pickupPositions(focus: Double): List<Vec> {
        require(focus.isFinite()) { "FOCUS must be finite" }
        val x = -0.85 + 1.5 * focus.coerceIn(0.0, 1.0)
        return List(9) { pickup -> Vec(x, -0.18 + 0.045 * pickup) }
    }

    /** Specular reflection. Both arguments must be unit vectors. */
    internal fun reflect(incident: Vec, unitNormal: Vec): Vec =
        incident - unitNormal * (2.0 * incident.dot(unitNormal))

    /**
     * Incident distance relative to the vessel centre, in R, without subtracting large distances.
     * For u>0 this equals (|u*p-(1,0)|-1)/u. Rationalization gives a finite, exact parallel limit
     * of -p.x at u=0; it also works at the excluded rim-source point p=(1,0).
     */
    internal fun incidentRelativeDistance(source: Double, boundary: Vec): Double {
        val dx = source * boundary.x - 1.0
        val dy = source * boundary.y
        val length = sqrt(dx * dx + dy * dy)
        return (source * boundary.dot(boundary) - 2.0 * boundary.x) / (length + 1.0)
    }

    /**
     * Prepare all nonzero pickup paths. Half-step angular quadrature excludes the singular
     * rim-source point theta=0 explicitly, including for odd ray counts. Every reflected segment
     * stops at its next circle intersection; the aperture cannot collect a ray behind its launch
     * or after its next bounce. The compact smooth kernel is (1-(perpendicular/aperture)^2)^3.
     *
     * delayR = incidentRelativeDistance + forwardDistance + 1. The fixed one-radius offset makes
     * the causal first-listen delay nonnegative; it is shared by every SOURCE/FOCUS and never
     * aligned separately per patch. All delays are bounded by four radii.
     */
    fun prepare(source: Double, focus: Double, rays: Int = 256, aperture: Double = 0.05): Prepared {
        require(source.isFinite()) { "SOURCE must be finite" }
        require(rays >= 4) { "At least four boundary rays are required" }
        require(aperture.isFinite() && aperture > 0.0) { "Aperture must be finite and positive" }
        val u = source.coerceIn(0.0, 1.0)
        val pickups = pickupPositions(focus)
        val launches = ArrayList<Launch>(rays)
        var launchWeight = 0.0
        for (ray in 0 until rays) {
            val theta = (ray + 0.5) * (2.0 * PI / rays)
            val point = Vec(cos(theta), sin(theta))
            val offset = Vec(u * point.x - 1.0, u * point.y)
            val length = sqrt(offset.dot(offset))
            // Half-step sampling excludes this case; keep the policy explicit for roundoff.
            if (length <= 1e-12) continue
            val incident = offset * (1.0 / length)
            val incidence = incident.dot(point)
            if (incidence <= 0.0) continue
            val direction = reflect(incident, point)
            val weight = incidence / length
            val extent = max(0.0, -2.0 * point.dot(direction))
            launches.add(Launch(point, direction, weight, theta, incidentRelativeDistance(u, point), extent))
            launchWeight += weight
        }
        check(launchWeight > 0.0 && launchWeight.isFinite()) { "No finite illuminated launch set" }
        val paths = ArrayList<Path>()
        val densities = DoubleArray(pickups.size)
        val allocation = 1.0 / (pickups.size * launchWeight)
        for (launch in launches) {
            for ((pickup, position) in pickups.withIndex()) {
                val delta = position - launch.point
                val along = delta.dot(launch.direction)
                if (along < 0.0 || along > launch.extent) continue
                val perpendicular = delta - launch.direction * along
                val ratio = sqrt(perpendicular.dot(perpendicular)) / aperture
                val ratioSquared = ratio * ratio
                if (ratioSquared >= 1.0) continue
                val kernel = 1.0 - ratioSquared
                val weight = launch.weight * allocation * kernel * kernel * kernel
                val delayR = (launch.incidentRelative + along + 1.0).coerceIn(0.0, 4.0)
                paths.add(Path(pickup, delayR, weight, launch.theta))
                densities[pickup] += weight
            }
        }
        return Prepared(paths, densities, rays)
    }
}
