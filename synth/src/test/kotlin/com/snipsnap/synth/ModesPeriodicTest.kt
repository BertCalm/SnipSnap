package com.snipsnap.synth

import kotlin.math.*
import kotlin.test.*

class ModesPeriodicTest {
    private fun bank() = Modes.Bank(2, 8000).also {
        it.tune(0, 261.625565, 2.7); it.tune(1, 628.0, .85)
    }
    private val b = doubleArrayOf(1.0, .45)
    private fun cycle(bank: Modes.Bank, count: Int): DoubleArray = DoubleArray(count) { i ->
        if (i in 30..44 || i in 351..367) bank.drive(b, 30.0 * sin((i + 1) * .13))
        bank.step(); bank.velocityAlong(b)
    }

    @Test fun `closed state matches a continuous independently warmed bank`() {
        val fast = bank(); val reference = bank(); val n = 4000
        cycle(fast, n); fast.closeUncoupledCycle(n)
        repeat(15) { cycle(reference, n) }
        val a = cycle(fast, n); val r = cycle(reference, n)
        assertTrue(a.indices.maxOf { abs(a[it] - r[it]) } < 1e-10)
        val continuation = cycle(fast, n)
        assertTrue(a.indices.maxOf { abs(a[it] - continuation[it]) } < 1e-12)
    }

    @Test fun `sparse free advance agrees with real steps and refuses coupled use`() {
        val fast = bank(); val reference = bank()
        fast.drive(b, 200.0); reference.drive(b, 200.0)
        fast.advanceUncoupled(17000); repeat(17000) { reference.step() }
        for (i in 0..1) {
            assertEquals(reference.velocity(i), fast.velocity(i), 1e-12)
            assertEquals(reference.displacement(i), fast.displacement(i), 1e-12)
        }
        fast.connect(0, 1, .1)
        assertFailsWith<IllegalArgumentException> { fast.advanceUncoupled(10) }
        assertFailsWith<IllegalArgumentException> { fast.closeUncoupledCycle(100) }
    }
}
