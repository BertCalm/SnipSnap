package com.snipsnap.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grid reads like an MPC, not like a page: A01 is bottom-left and A13
 * is the top row. Getting this backwards would put the kick where the
 * cymbal belongs on every kit ever made.
 */
class PadGridLayoutTest {

    @Test
    fun `the bottom-left cell is A01`() {
        assertEquals(1, slotForCell(row = 3, col = 0))
    }

    @Test
    fun `the top row is slots 13 to 16`() {
        assertEquals(listOf(13, 14, 15, 16), (0..3).map { slotForCell(row = 0, col = it) })
    }

    @Test
    fun `the bottom row is slots 1 to 4`() {
        assertEquals(listOf(1, 2, 3, 4), (0..3).map { slotForCell(row = 3, col = it) })
    }

    @Test
    fun `every cell maps to a distinct slot covering the bank`() {
        val slots = (0..3).flatMap { r -> (0..3).map { c -> slotForCell(r, c) } }
        assertEquals((1..16).toSet(), slots.toSet())
    }

    @Test
    fun `bank B continues where bank A stopped`() {
        assertEquals(17, slotForCell(row = 3, col = 0, bankIndex = 1))
        assertEquals(32, slotForCell(row = 0, col = 3, bankIndex = 1))
    }

    @Test
    fun `cellForSlot inverts slotForCell`() {
        for (r in 0..3) {
            for (c in 0..3) {
                assertEquals(r to c, cellForSlot(slotForCell(r, c)))
            }
        }
    }
}
