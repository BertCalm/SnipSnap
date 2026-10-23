package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TidalDynamicsTest {
    private fun settings() = TidalDynamics.Settings(particleCount = 256, seed = 41L)

    @Test
    fun `same preparation is bit repeatable and seed changes particle realization`() {
        val first = TidalDynamics.prepare(settings())
        val again = TidalDynamics.prepare(settings())
        assertEquals(first.diagnostics, again.diagnostics)
        assertEquals(first.frames.size, again.frames.size)
        for (i in first.frames.indices) {
            assertEquals(first.frames[i].timeSeconds, again.frames[i].timeSeconds)
            assertEquals(first.frames[i].cohorts.toList(), again.frames[i].cohorts.toList())
            assertEquals(first.frames[i].centers.toList(), again.frames[i].centers.toList())
        }
        val changed = TidalDynamics.prepare(settings().copy(seed = 42L))
        assertNotEquals(first.diagnostics.finalParticleFingerprint, changed.diagnostics.finalParticleFingerprint)
        // Disk sampling does not perturb the massive centers or the common musical clock.
        assertEquals(first.diagnostics.minimumSeparation, changed.diagnostics.minimumSeparation)
        assertEquals(8.0, first.durationSeconds)
        assertEquals(0.0, first.frames.first().timeSeconds)
        assertEquals(first.durationSeconds, first.frames.last().timeSeconds)
    }

    @Test
    fun `softened pericenter and symmetric center force converge under step refinement`() {
        val fixture = settings().copy(approach = 0.0, passage = 1.0, step = .01)
        val coarse = TidalDynamics.prepare(fixture).diagnostics
        val fine = TidalDynamics.prepare(fixture.copy(step = .005)).diagnostics
        assertEquals(.7, fine.targetPericenter, 1e-12)
        assertEquals(fine.targetPericenter, fine.minimumSeparation, .0002)
        assertTrue(fine.maxCenterEnergyRelativeError < coarse.maxCenterEnergyRelativeError * .3,
            "center leapfrog energy did not improve with the expected second-order trend")
        assertTrue(fine.maxCenterEnergyRelativeError < .003)
        assertTrue(fine.maxCenterMomentum < 1e-12)
        assertTrue(fine.maxCenterPositionDrift < 1e-12)
        assertTrue(fine.encounterTimeSeconds in 1.0..5.0)
    }

    @Test
    fun `isolated softened circular motion does not become false extended structure`() {
        val isolated = TidalDynamics.prepare(settings().copy(
            passage = 1.0, bind = 1.0, tilt = .7, noTidalGradient = true,
        ))
        assertTrue(isolated.diagnostics.maxRadialRatioDeviation < .003,
            "softened initial circular velocity or moving-center compensation is inconsistent")
        assertEquals(0.0, isolated.diagnostics.maxExtension)
        assertTrue(isolated.frames.all { frame -> frame.cohorts.all { it.extension == 0.0 && it.stretch == 0.0 } })
    }

    @Test
    fun `close diffuse encounters produce structure absent with the tidal gradient removed`() {
        val fixture = settings().copy(approach = .25, passage = .9, tilt = 0.0, bind = 0.0, particleCount = 512)
        val close = TidalDynamics.prepare(fixture)
        val compact = TidalDynamics.prepare(fixture.copy(bind = 1.0))
        val distant = TidalDynamics.prepare(fixture.copy(passage = 0.0, bind = 1.0))
        val noTides = TidalDynamics.prepare(fixture.copy(noTidalGradient = true))
        assertTrue(close.diagnostics.maxExtension > .3)
        assertTrue(close.diagnostics.finalExtension > compact.diagnostics.finalExtension + .10,
            "a compact population should retain more of its original core in this fixture")
        assertTrue(distant.diagnostics.maxExtension < .0001)
        assertEquals(0.0, noTides.diagnostics.maxExtension)
        assertEquals(close.diagnostics.minimumSeparation, noTides.diagnostics.minimumSeparation)
        assertTrue(close.frames.first().cohorts.all { it.extension == 0.0 })
        assertTrue(close.frames.last().cohorts.any { it.extension > .3 })
    }

    @Test
    fun `inclination changes three dimensional particle motion while centers stay in the encounter plane`() {
        val coplanar = TidalDynamics.prepare(settings().copy(tilt = 0.0, passage = .8, bind = .25))
        val inclined = TidalDynamics.prepare(settings().copy(tilt = .7, passage = .8, bind = .25))
        assertEquals(0.0, coplanar.diagnostics.maxParticleHeight)
        assertTrue(inclined.diagnostics.maxParticleHeight > .1)
        assertNotEquals(coplanar.diagnostics.finalParticleFingerprint, inclined.diagnostics.finalParticleFingerprint)
        assertEquals(coplanar.diagnostics.minimumSeparation, inclined.diagnostics.minimumSeparation)
        assertTrue(inclined.frames.all { frame -> frame.centers.all { it.z == 0.0 } })
    }

    @Test
    fun `bounded controls retain original populations and a particle count independent source budget`() {
        val prepared = TidalDynamics.prepare(settings().copy(particleCount = 2_048, passage = 1.0, bind = 0.0))
        for (frame in prepared.frames) {
            assertEquals(32, frame.cohorts.size)
            assertEquals(1.0, frame.cohorts.sumOf { it.weight }, 1e-12)
            for ((id, cohort) in frame.cohorts.withIndex()) {
                assertEquals(id, cohort.id)
                assertEquals(id / 16, cohort.population)
                assertEquals(1.0 / 32, cohort.weight)
                assertEquals(1.0, cohort.core + cohort.extension, 1e-12)
                assertTrue(listOf(cohort.core, cohort.extension, cohort.stretch, cohort.concentration,
                    cohort.motion, cohort.affinity).all { it.isFinite() && it in 0.0..1.0 })
            }
        }
        assertEquals(0, prepared.diagnostics.outlierCount)
        assertTrue(prepared.diagnostics.maxParticleRadius.isFinite())
        assertTrue(prepared.diagnostics.maxParticleRadius < 256.0)
    }

    @Test
    fun `aggregate extension is similar with a finer particle estimate`() {
        val fixture = settings().copy(approach = .25, passage = .9, tilt = .1, bind = .2)
        val small = TidalDynamics.prepare(fixture)
        val large = TidalDynamics.prepare(fixture.copy(particleCount = 1_024))
        assertTrue(abs(small.diagnostics.finalExtension - large.diagnostics.finalExtension) < .10)
        assertEquals(small.diagnostics.minimumSeparation, large.diagnostics.minimumSeparation)
    }

    @Test
    fun `aggregate extended structure converges independently of center conservation`() {
        val fixture = settings().copy(particleCount = 512, approach = .25, passage = .7,
            tilt = .2, bind = .2, step = .01)
        val coarse = TidalDynamics.prepare(fixture)
        val medium = TidalDynamics.prepare(fixture.copy(step = .005))
        val fine = TidalDynamics.prepare(fixture.copy(step = .0025))
        fun meanTailDistance(a: TidalDynamics.Prepared, b: TidalDynamics.Prepared): Double {
            assertEquals(a.frames.size, b.frames.size)
            return a.frames.indices.sumOf { index ->
                val left = a.frames[index].cohorts.sumOf { it.weight * it.extension }
                val right = b.frames[index].cohorts.sumOf { it.weight * it.extension }
                abs(left - right)
            } / a.frames.size
        }
        val coarseError = meanTailDistance(coarse, medium)
        val fineError = meanTailDistance(medium, fine)
        assertTrue(fine.diagnostics.finalExtension > .05, "the convergence fixture must contain actual extension")
        assertTrue(fineError < .002, "fine-step aggregate tail distance=$fineError")
        assertTrue(fineError < coarseError * .6 + 1e-5,
            "tail descriptors did not converge: coarse=$coarseError fine=$fineError")
    }

    @Test
    fun `supported dynamics corners and maximum preparation quality stay bounded`() {
        for (approach in listOf(0.0, 1.0)) for (passage in listOf(0.0, 1.0))
            for (tilt in listOf(0.0, 1.0)) for (bind in listOf(0.0, 1.0)) {
                val probe = TidalDynamics.prepare(settings().copy(approach = approach, passage = passage,
                    tilt = tilt, bind = bind, particleCount = 128))
                assertEquals(probe.diagnostics.targetPericenter, probe.diagnostics.minimumSeparation, .0005)
                assertTrue(probe.diagnostics.maxCenterEnergyRelativeError < .003)
                assertEquals(0, probe.diagnostics.outlierCount)
                assertTrue(probe.diagnostics.maxParticleRadius < 256.0)
                assertTrue(probe.diagnostics.encounterTimeSeconds in 1.0..5.0)
            }
        // One expensive preparation verifies non-divisible cohort populations at 10,000.
        val maximum = TidalDynamics.prepare(settings().copy(approach = 1.0, passage = 1.0,
            tilt = 1.0, bind = 1.0, particleCount = 10_000, step = .0025))
        assertEquals(10_000, maximum.diagnostics.particleCount)
        assertEquals(1.0, maximum.frames.last().cohorts.sumOf { it.weight }, 1e-12)
        assertTrue(maximum.frames.all { frame -> frame.cohorts.all { it.extension.isFinite() && it.motion.isFinite() } })
        assertEquals(0, maximum.diagnostics.outlierCount)
    }

    @Test
    fun `unsupported settings and cancelled preparation fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { settings().copy(bind = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { settings().copy(passage = 1.01) }
        assertFailsWith<IllegalArgumentException> { settings().copy(step = .1) }
        assertFailsWith<IllegalArgumentException> { settings().copy(particleCount = 65) }
        assertFailsWith<CancellationException> { TidalDynamics.prepare(settings()) { true } }
        var calls = 0
        assertFailsWith<CancellationException> {
            TidalDynamics.prepare(settings()) { ++calls > 3 }
        }
        assertTrue(calls > 3, "cancellation was not polled during integration")
    }
}
