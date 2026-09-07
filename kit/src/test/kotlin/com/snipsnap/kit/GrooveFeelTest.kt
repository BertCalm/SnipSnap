package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrooveFeelTest {

    private val s16 = Mpc3Clip.PULSES_PER_16TH

    /** A swung donor: even 16ths on the grid, odd 16ths 76 pulses late. */
    private fun swungDonor() = Mpc3Clip(
        "Donor Groove", 1,
        (0 until 16).map { step ->
            val late = if (step % 2 == 1) 76L else 0L
            Mpc3Note(42, step * s16 + late, if (step % 4 == 0) 1.0f else 0.5f)
        },
    )

    /** A dead-straight, flat-velocity target. */
    private fun straightTarget() = Mpc3Clip(
        "Target Groove", 1,
        (0 until 16).map { step -> Mpc3Note(36 + (step % 4), step * s16, 0.7f) },
    )

    @Test
    fun `the donor's swing lands on the target's notes`() {
        val template = GrooveFeel.extract(swungDonor())
        assertEquals(List(8) { 0L }, template.offsets.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(List(8) { 76L }, template.offsets.filterIndexed { i, _ -> i % 2 == 1 })

        val felt = GrooveFeel.apply(template, straightTarget())
        assertEquals("Target Feel", felt.name)
        felt.notes.forEachIndexed { step, n ->
            val expected = step * s16 + if (step % 2 == 1) 76L else 0L
            assertEquals(expected, n.timePulses, "step $step took the donor's pocket")
        }
        // Same donor, same result — a template is a pure fact.
        assertEquals(felt, GrooveFeel.apply(GrooveFeel.extract(swungDonor()), straightTarget()))
    }

    @Test
    fun `velocities follow the donor's accent shape`() {
        val template = GrooveFeel.extract(swungDonor())
        val felt = GrooveFeel.apply(template, straightTarget())
        // Donor mean velocity: 4×1.0 + 12×0.5 = 0.625. Downbeats accent
        // 1.0/0.625 = 1.6, the rest 0.5/0.625 = 0.8.
        felt.notes.forEachIndexed { step, n ->
            if (step % 4 == 0) {
                assertTrue(n.velocity > 0.9f, "downbeat $step hits harder: ${n.velocity}")
            } else {
                assertTrue(n.velocity < 0.6f, "offbeat $step sits back: ${n.velocity}")
            }
        }
    }

    @Test
    fun `donor-silent positions stay straight instead of guessing`() {
        // The donor only plays the first four 16ths.
        val sparseDonor = Mpc3Clip(
            "Sparse Donor", 1,
            (0 until 4).map { Mpc3Note(42, it * s16 + 30L, 0.8f) },
        )
        val template = GrooveFeel.extract(sparseDonor)
        assertEquals(30L, template.offsets[0])
        assertEquals(null, template.offsets[8], "no data, no opinion")

        val felt = GrooveFeel.apply(template, straightTarget())
        assertEquals(8 * s16, felt.notes[8].timePulses, "uncovered position stays on the grid")
        assertEquals(0.7f, felt.notes[8].velocity, "and keeps its own velocity")
        assertEquals(30L, felt.notes[0].timePulses, "covered position leans")
    }

    @Test
    fun `an empty donor is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GrooveFeel.extract(Mpc3Clip("Empty", 1, emptyList()))
        }
    }
}
