package com.snipsnap.synth

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModesSparseTest {
    private fun bank(size: Int = 9): Modes.Bank = Modes.Bank(size, Dsp.RATE * Dsp.OVERSAMPLE).also { bank ->
        repeat(size) { bank.tune(it, 85.0 + 193.0 * it, 0.17 + it * 0.09) }
        for (i in 1 until size) bank.connect(0, i, 0.015)
    }

    @Test
    fun `compiled participation preserves dense drive and pickup through coupled motion`() {
        val dense = bank()
        val sparse = bank()
        val drive = doubleArrayOf(0.0, -.73, 0.0, 0.0, 1.25, 0.0, .41, 0.0, 0.0)
        val pickup = doubleArrayOf(.3, 0.0, -.2, 0.0, .5, 0.0, 0.0, -.8, 0.0)
        val path = sparse.participation(drive)
        val weights = sparse.participation(pickup)
        repeat(4096) { i ->
            val force = if (i < 200) 71.0 * sin(i * .173) else 0.0
            dense.drive(drive, force); sparse.drive(path, force)
            dense.step(); sparse.step()
            assertEquals(dense.velocityAlong(pickup), sparse.velocityAlong(weights), "pickup at sample $i")
            repeat(dense.size) { assertEquals(dense.velocity(it), sparse.velocity(it), "mode $it at sample $i") }
        }
    }

    @Test
    fun `all-zero and dense compiled vectors preserve the dense result`() {
        for (values in listOf(DoubleArray(9), DoubleArray(9) { (it - 4) * .19 })) {
            val dense = bank(); val sparse = bank()
            val path = sparse.participation(values)
            repeat(64) { i ->
                dense.drive(values, i * .01); sparse.drive(path, i * .01)
                dense.step(); sparse.step()
                assertEquals(dense.velocityAlong(values), sparse.velocityAlong(path))
            }
        }
    }

    @Test
    fun `compiled weights are a snapshot and incompatible dimensions are refused`() {
        val sparse = bank()
        val values = DoubleArray(9).also { it[3] = .7 }
        val path = sparse.participation(values)
        values.fill(9.0)
        val reference = bank()
        val original = DoubleArray(9).also { it[3] = .7 }
        sparse.drive(path, 7.0); reference.drive(original, 7.0)
        sparse.step(); reference.step()
        assertEquals(reference.velocityAlong(original), sparse.velocityAlong(path))
        assertFailsWith<IllegalArgumentException> { sparse.participation(DoubleArray(8)) }
        assertFailsWith<IllegalArgumentException> { bank(8).drive(path, 1.0) }
        assertFailsWith<IllegalArgumentException> { bank(8).velocityAlong(path) }
    }
}
