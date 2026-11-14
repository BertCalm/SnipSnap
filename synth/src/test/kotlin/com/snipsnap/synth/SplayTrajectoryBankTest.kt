package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SplayTrajectoryBankTest {
    @Test
    fun `downward equilibrium and independent mass matrix acceleration`() {
        val derivative = DoubleArray(4)
        SplayTrajectoryBank.derivatives(doubleArrayOf(0.0, 0.0, 0.0, 0.0), derivative)
        assertTrue(derivative.all { it == 0.0 })
        assertEquals(-3.0, SplayTrajectoryBank.energyOf(DoubleArray(4)))

        // Horizontal first arm, downward second arm, released from rest: A12=0.
        SplayTrajectoryBank.derivatives(doubleArrayOf(PI / 2.0, 0.0, 0.0, 0.0), derivative)
        assertEquals(-1.0, derivative[2], 1e-14)
        assertEquals(0.0, derivative[3], 1e-14)
    }

    @Test
    fun `zero gap reuses identical histories and population keeps stable IDs`() {
        val same = SplayTrajectoryBank.build(0.65, 0.0, 10.0, 1.0, 16)
        for (member in 1 until same.members.size) assertSame(same.members[0], same.members[member])
        assertTrue(same.separation.all { it.maxStateDistance == 0.0 && it.rmsEndpointDistance == 0.0 })

        val small = SplayTrajectoryBank.build(0.65, 1e-6, 10.0, 1.0, 3)
        val larger = SplayTrajectoryBank.build(0.65, 1e-6, 10.0, 1.0, 5)
        for (member in small.members.indices) assertContentEquals(small.members[member], larger.members[member])
        assertEquals(listOf(0, 1, -1, 2, -2, 3, -3), (0..6).map(SplayTrajectoryBank::offsetFor))
    }

    @Test
    fun `degrees convert once and endpoint interpolation is guarded`() {
        val bank = SplayTrajectoryBank.build(0.65, 0.1, 1.0, 0.0035, 3)
        val angle = SplayTrajectoryBank.releaseAngle(0.65)
        assertEquals(angle, bank.state(0, 0.0)[1])
        assertEquals(angle + PI / 1800.0, bank.state(1, 0.0)[1], 1e-15)
        assertEquals(angle - PI / 1800.0, bank.state(2, 0.0)[1], 1e-15)
        assertEquals(5, bank.frames)
        assertTrue(bank.state(0, 0.0035).all { it.isFinite() })
        assertContentEquals(bank.state(0, 0.0035), bank.state(0, 99.0))
        assertContentEquals(bank.state(0, 0.0), bank.state(0, -1.0))
    }

    @Test
    fun `member energy remains conservative through large angle motion`() {
        val bank = SplayTrajectoryBank.build(0.85, 0.001, 10.0, 6.0, 4)
        println("SPLAY max own-energy drift / gravitational scale: ${bank.maxRelativeEnergyDrift}; evaluations=${bank.evaluations}")
        assertTrue(bank.maxRelativeEnergyDrift < 1e-8)
        assertTrue(bank.members.all { packed -> packed.all { it.isFinite() } })
        assertTrue(bank.members.any { packed -> (packed.indices step 4).any { abs(packed[it + 1]) > PI } })
    }

    @Test
    fun `short horizon tight tolerance converges and millionth degree fixture separates`() {
        val normal = SplayTrajectoryBank.build(0.65, 1e-6, 10.0, 5.2, 2)
        val tighter = SplayTrajectoryBank.build(0.65, 1e-6, 10.0, 5.2, 2, relativeTolerance = 1e-13)
        for (member in 0..1) {
            val a = normal.state(member, 1.0)
            val b = tighter.state(member, 1.0)
            assertTrue(distance(a, b) < 1e-7)
        }
        val early = distance(normal.state(0, 1.0), normal.state(1, 1.0))
        val late = distance(normal.state(0, 5.0), normal.state(1, 5.0))
        println("SPLAY millionth-degree state distances: early=$early late=$late")
        assertTrue(early < 1e-5)
        assertTrue(late > 0.1)
        val normalOnset = normal.separation.first { it.maxStateDistance >= 0.1 }.seconds
        val tighterOnset = tighter.separation.first { it.maxStateDistance >= 0.1 }.seconds
        assertEquals(normalOnset, tighterOnset, 0.05)
    }

    @Test
    fun `low energy family stays ordered during the finite audition`() {
        val bank = SplayTrajectoryBank.build(0.0, 1e-6, 10.0, 8.0, 4)
        assertTrue(bank.separation.maxOf { it.maxStateDistance } < 1e-4)
    }

    @Test
    fun `full population at maximum trace energy and gap fits the bounded work budget`() {
        // The real all-max macro corner previously exhausted the old 8m total cap at ID 94.
        // Keep every independent history, the original tolerance, and the per-ID work cap.
        val bank = SplayTrajectoryBank.build(
            1.0, Splay.gapDegrees(1f), Splay.motionSpeed(1f), Splay.ACTIVE_SECONDS, 100,
        )
        println("SPLAY all-max bank: evaluations=${bank.evaluations}; own-energy drift=${bank.maxRelativeEnergyDrift}")
        assertEquals(100, bank.members.size)
        assertTrue(bank.evaluations <= SplayTrajectoryBank.MAX_EVALUATIONS)
        assertTrue(bank.maxRelativeEnergyDrift < 1e-8)
        assertTrue(bank.members.all { packed -> packed.all { it.isFinite() } })
        assertTrue(bank.separation.maxOf { it.maxStateDistance } > 1.0)
        for (member in 0 until 100) assertTrue(bank.state(member, 6.0).all { it.isFinite() })
    }

    @Test
    fun `nonfinite controls horizon and work overflow terminate explicitly`() {
        assertFailsWith<IllegalArgumentException> { SplayTrajectoryBank.build(Double.NaN, 0.0, 1.0, 1.0, 1) }
        assertFailsWith<IllegalArgumentException> { SplayTrajectoryBank.build(0.65, Double.POSITIVE_INFINITY, 1.0, 1.0, 1) }
        assertFailsWith<IllegalArgumentException> { SplayTrajectoryBank.build(0.65, 0.0, 100.0, 8.0, 1) }
        assertFailsWith<IllegalArgumentException> { SplayTrajectoryBank.build(0.65, 0.0, 1.0, 1.0, 101) }
        val failure = assertFailsWith<IllegalStateException> {
            SplayTrajectoryBank.build(0.65, 1e-6, 10.0, 1.0, 2, maxEvaluations = 10)
        }
        assertTrue(failure.message.orEmpty().contains("budget"))
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        val x = 2.0 * sin((a[0] - b[0]) * 0.5)
        val y = 2.0 * sin((a[1] - b[1]) * 0.5)
        val u = a[2] - b[2]
        val v = a[3] - b[3]
        return sqrt(x * x + y * y + u * u + v * v)
    }
}
