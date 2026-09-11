package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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

    @Test
    fun `a generated template is deterministic for a seed`() {
        assertEquals(GrooveFeel.generated(7).offsets, GrooveFeel.generated(7).offsets, "same seed, same feel")
    }

    @Test
    fun `different seeds give different feels`() {
        assertNotEquals(GrooveFeel.generated(1).offsets, GrooveFeel.generated(2).offsets, "the dice actually roll")
    }

    @Test
    fun `downbeats barely move and weak sixteenths move most`() {
        // The pulse must survive the roll. Every seed, not a lucky one.
        val downbeatCap = Math.round(0.25 * GrooveFeel.FEEL_MAX_OFFSET_PULSES)
        for (seed in 1..200) {
            val t = GrooveFeel.generated(seed)
            for (pos in 0 until GrooveFeel.POSITIONS step 4) {
                val o = t.offsets[pos]!!
                assertTrue(Math.abs(o) <= downbeatCap, "seed $seed moved downbeat $pos by $o, cap $downbeatCap")
            }
            t.offsets.forEach { o ->
                assertTrue(Math.abs(o!!) <= GrooveFeel.FEEL_MAX_OFFSET_PULSES, "seed $seed exceeded the ceiling: $o")
            }
        }
    }

    @Test
    fun `generation covers every position and no accents`() {
        val t = GrooveFeel.generated(3)
        assertEquals(GrooveFeel.POSITIONS, t.offsets.size, "every position has an offset")
        assertTrue(t.offsets.all { it != null }, "a generated template is never silent at a position")
        assertTrue(t.accents.all { it == null }, "subsystem A generates timing only, never accents")
        assertTrue(t.laneOffsets.isEmpty(), "and no lane pocket - that is subsystem B's to supply")
    }

    private fun twoBar(vararg pulses: Long) = Mpc3Clip(
        "break", 2,
        pulses.mapIndexed { i, p -> Mpc3Note(note = 36 + (i % 3), timePulses = p, velocity = 0.8f) },
    )

    private val ZERO = GrooveFeel.Template(
        offsets = List(GrooveFeel.POSITIONS) { 0L },
        accents = List(GrooveFeel.POSITIONS) { null },
    )

    @Test
    fun `t of zero is note-for-note identity`() {
        val clip = twoBar(37L, 1201L, 3799L, 7600L)
        val out = GrooveFeel.applyFeel(clip, 0f, GrooveFeel.generated(5))
        assertEquals(clip.notes, out.notes, "AS PLAYED must be exactly what was played")
        assertEquals(clip.name, out.name, "and it does not rename the clip")
    }

    @Test
    fun `full tight equals GrooveEdit quantized`() {
        val clip = twoBar(37L, 1201L, 3799L, 7600L)
        val tight = GrooveFeel.applyFeel(clip, -1f, ZERO)
        val expected = GrooveEdit.quantized(clip, clip.name)
        assertEquals(expected.notes.map { it.timePulses }, tight.notes.map { it.timePulses },
            "the tight endpoint IS the wrapping quantize, not a second implementation")
    }

    @Test
    fun `half tight lerps toward the unwrapped grid, not across the bar`() {
        // 7600 snaps to 7680, which wraps to 0. Lerping toward the WRAPPED
        // target would give 3800 - the middle of the bar. Correct is 7640.
        val out = GrooveFeel.applyFeel(twoBar(7600L), -0.5f, ZERO)
        assertEquals(7640L, out.notes[0].timePulses, "halfway to the downbeat, not halfway across the clip")
    }

    @Test
    fun `full loose adds the template offset`() {
        val t = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 30L },
            accents = List(GrooveFeel.POSITIONS) { null },
        )
        val out = GrooveFeel.applyFeel(twoBar(1200L), 1f, t)
        assertEquals(1230L, out.notes[0].timePulses, "the loose endpoint is original plus offset")
    }

    @Test
    fun `a loose push past the loop end wraps instead of clamping`() {
        val t = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 200L },
            accents = List(GrooveFeel.POSITIONS) { null },
        )
        val out = GrooveFeel.applyFeel(twoBar(7600L), 1f, t)
        assertEquals(120L, out.notes[0].timePulses, "7800 wraps to 120, it does not clamp to 7679")
    }

    @Test
    fun `the lane pocket survives full tight`() {
        val t = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 0L },
            accents = List(GrooveFeel.POSITIONS) { null },
            laneOffsets = mapOf(GrooveEdit.Lane.SNARE to 24L),
        )
        val snare = Mpc3Clip("b", 2, listOf(Mpc3Note(note = GrooveEdit.noteFor(GrooveEdit.Lane.SNARE), timePulses = 1210L, velocity = 0.8f)))
        val out = GrooveFeel.applyFeel(snare, -1f, t)
        assertEquals(1200L + 24L, out.notes[0].timePulses, "tight, but the snare still drags - the pocket is not on the axis")
    }

    @Test
    fun `an off-lane note takes position offsets and no lane offset`() {
        val t = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 10L },
            accents = List(GrooveFeel.POSITIONS) { null },
            laneOffsets = mapOf(GrooveEdit.Lane.SNARE to 24L),
        )
        // Pad 6 (note 41) is not one of the five named lanes.
        val offLane = Mpc3Clip("b", 2, listOf(Mpc3Note(note = 41, timePulses = 1200L, velocity = 0.8f)))
        val out = GrooveFeel.applyFeel(offLane, 1f, t)
        assertEquals(1210L, out.notes[0].timePulses, "position offset applies; the snare's pocket does not")
    }

    @Test
    fun `continuity across the detent`() {
        val clip = twoBar(1207L)
        val justTight = GrooveFeel.applyFeel(clip, -0.001f, ZERO).notes[0].timePulses
        val justLoose = GrooveFeel.applyFeel(clip, 0.001f, GrooveFeel.generated(4)).notes[0].timePulses
        assertTrue(Math.abs(justTight - 1207L) <= 1L, "just tight of centre is still essentially as played: $justTight")
        assertTrue(Math.abs(justLoose - 1207L) <= 1L, "and so is just loose of it: $justLoose")
    }

    @Test
    fun `a template accent scales velocity on the loose side only`() {
        val t = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 0L },
            accents = List(GrooveFeel.POSITIONS) { 0.5f },
        )
        val clip = Mpc3Clip("b", 2, listOf(Mpc3Note(note = 36, timePulses = 1200L, velocity = 0.8f)))
        assertEquals(0.4f, GrooveFeel.applyFeel(clip, 1f, t).notes[0].velocity, 1e-4f, "full loose takes the accent")
        assertEquals(0.8f, GrooveFeel.applyFeel(clip, -1f, t).notes[0].velocity, 1e-4f, "tightening timing must not touch dynamics")
    }

    @Test
    fun `t outside the range is refused in words`() {
        val e = assertFailsWith<IllegalArgumentException> { GrooveFeel.applyFeel(twoBar(0L), 1.5f, ZERO) }
        assertTrue(e.message!!.contains("-1"), "the refusal names the range: ${e.message}")
    }
}
