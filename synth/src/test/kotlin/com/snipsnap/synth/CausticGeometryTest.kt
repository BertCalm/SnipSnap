package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CausticGeometryTest {
    @Test
    fun `reflection preserves unit length and reverses only the normal component`() {
        for (step in 0 until 100) {
            val theta = 2.0 * PI * step / 100
            val normal = CausticGeometry.Vec(cos(theta), sin(theta))
            val tangent = CausticGeometry.Vec(-normal.y, normal.x)
            for (angle in listOf(-1.4, -0.7, 0.0, 0.7, 1.4)) {
                val incident = normal * cos(angle) + tangent * sin(angle)
                val reflected = CausticGeometry.reflect(incident, normal)
                assertEquals(1.0, reflected.dot(reflected), 2e-15)
                assertEquals(-incident.dot(normal), reflected.dot(normal), 2e-15)
                assertEquals(incident.dot(tangent), reflected.dot(tangent), 2e-15)
            }
        }
    }

    @Test
    fun `incident delay has an exact parallel limit and a continuous rim endpoint`() {
        for (step in 0 until 256) {
            val theta = (step + 0.5) * 2.0 * PI / 256
            val boundary = CausticGeometry.Vec(cos(theta), sin(theta))
            assertEquals(-boundary.x, CausticGeometry.incidentRelativeDistance(0.0, boundary), 0.0)
            assertEquals(-boundary.x, CausticGeometry.incidentRelativeDistance(1e-10, boundary), 1e-10)
            for (source in listOf(0.25, 0.5, 1.0)) {
                val dx = source * boundary.x - 1.0
                val dy = source * boundary.y
                val direct = (sqrt(dx * dx + dy * dy) - 1.0) / source
                assertEquals(direct, CausticGeometry.incidentRelativeDistance(source, boundary), 2e-15)
            }
        }
        for (source in listOf(0.0, 1e-12, 0.5, 1.0)) {
            val prepared = CausticGeometry.prepare(source, 0.2)
            assertTrue(prepared.paths.isNotEmpty())
            assertTrue(prepared.paths.all { it.delayR.isFinite() && it.weight.isFinite() })
        }
    }

    @Test
    fun `paths stay on the forward first reflection and conserve the fixed pickup allocation`() {
        for (source in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            for (focus in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) {
                val prepared = CausticGeometry.prepare(source, focus)
                val pickups = CausticGeometry.pickupPositions(focus)
                assertEquals(9, pickups.size)
                assertTrue(pickups.all { it.dot(it) < 1.0 })
                assertTrue(prepared.densities.sum() in 0.0..1.0)
                val sums = DoubleArray(9)
                for (path in prepared.paths) {
                    assertTrue(path.delayR in 0.0..4.0)
                    assertTrue(path.weight > 0.0 && path.weight <= 1.0 / 9)
                    assertTrue(path.boundaryTheta > 0.0 && path.boundaryTheta < 2.0 * PI)
                    val boundary = CausticGeometry.Vec(cos(path.boundaryTheta), sin(path.boundaryTheta))
                    val offset = CausticGeometry.Vec(source * boundary.x - 1.0, source * boundary.y)
                    val incident = offset * (1.0 / sqrt(offset.dot(offset)))
                    assertTrue(incident.dot(boundary) > 0.0)
                    val reflected = CausticGeometry.reflect(incident, boundary)
                    val along = (pickups[path.pickup] - boundary).dot(reflected)
                    val extent = -2.0 * boundary.dot(reflected)
                    assertTrue(along >= 0.0 && along <= extent)
                    // Trigonometry, normalization, reflection and the second intersection each
                    // contribute roundoff. Allow 64 ulps of squared unit radius (about 1.4e-14).
                    assertEquals(1.0, (boundary + reflected * extent).let { it.dot(it) }, 64.0 * Math.ulp(1.0))
                    assertEquals(CausticGeometry.incidentRelativeDistance(source, boundary) + along + 1.0,
                        path.delayR, 1e-15)
                    sums[path.pickup] += path.weight
                }
                assertContentEquals(sums, prepared.densities)
                for (pickup in 0..3) assertEquals(prepared.densities[pickup], prepared.densities[8 - pickup], 1e-14)
            }
        }
    }

    @Test
    fun `focus encounters dense and sparse regions and 256 rays converge to the reference`() {
        // This measures finite-aperture collection, not analytic envelope or acoustic accuracy.
        for (source in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val referenceLevels = ArrayList<Double>()
            var maxArrayError = 0.0
            var maxPickupError = 0.0
            var peakPickup = 0.0
            for (step in 0..120) {
                val focus = step / 120.0
                val reference = CausticGeometry.prepare(source, focus, rays = 4096)
                val candidate = CausticGeometry.prepare(source, focus)
                referenceLevels.add(reference.densities.sum())
                maxArrayError = max(maxArrayError, abs(candidate.densities.sum() - reference.densities.sum()))
                for (pickup in 0..8) {
                    maxPickupError = max(maxPickupError, abs(candidate.densities[pickup] - reference.densities[pickup]))
                    peakPickup = max(peakPickup, reference.densities[pickup])
                }
            }
            val sorted = referenceLevels.sorted()
            val peak = sorted.last()
            val lowerDecile = sorted[sorted.size / 10]
            println("CAUSTIC SOURCE=$source: dense/sparse=${peak / lowerDecile}, " +
                "array convergence=${maxArrayError / peak}, pickup convergence=${maxPickupError / peakPickup}")
            assertTrue(peak / lowerDecile > 10.0, "SOURCE=$source pickup travel lost its focus contrast")
            assertTrue(maxArrayError / peak < 0.004, "SOURCE=$source mean collection did not converge")
            assertTrue(maxPickupError / peakPickup < 0.007, "SOURCE=$source individual pickups did not converge")
        }
    }

    @Test
    fun `preparation is deterministic and finite controls clamp consistently`() {
        val a = CausticGeometry.prepare(0.5, 0.23)
        val b = CausticGeometry.prepare(0.5, 0.23)
        assertEquals(a.paths, b.paths)
        assertContentEquals(a.densities, b.densities)
        assertEquals(256, a.rays)
        assertEquals(CausticGeometry.prepare(0.0, 1.0).paths, CausticGeometry.prepare(-1.0, 2.0).paths)
        assertFailsWith<IllegalArgumentException> { CausticGeometry.prepare(Double.NaN, 0.5) }
        assertFailsWith<IllegalArgumentException> { CausticGeometry.prepare(0.5, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { CausticGeometry.prepare(0.5, 0.5, aperture = 0.0) }
        assertFailsWith<IllegalArgumentException> { CausticGeometry.prepare(0.5, 0.5, rays = 0) }
    }
}
