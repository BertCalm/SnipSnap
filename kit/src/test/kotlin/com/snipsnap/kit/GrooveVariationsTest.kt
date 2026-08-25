package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrooveVariationsTest {

    private val base = Mpc3Clip(
        "Break Groove", 1,
        listOf(
            Mpc3Note(36, 0, 1.0f, 240),        // downbeat kick, strong
            Mpc3Note(42, 503, 0.30f, 120),     // pushed ghost hat
            Mpc3Note(38, 960, 0.85f, 240),     // backbeat snare, strong
            Mpc3Note(42, 1685, 0.25f, 120),    // another ghost, late of 1680
            Mpc3Note(36, 1920, 0.9f, 240),     // strong kick
        ),
    )

    @Test
    fun `the standard four derive provably from the base`() {
        val four = GrooveVariations.standard(base)
        assertEquals(4, four.size)
        assertEquals(
            listOf("Break Groove", "Break Tight", "Break Half", "Break Sparse"),
            four.map { it.name },
        )
        assertEquals(base, four[0], "slot one is the capture, untouched")
    }

    @Test
    fun `quantize snaps every note to the grid`() {
        val tight = GrooveVariations.quantize(base, Mpc3Clip.PULSES_PER_16TH)
        assertEquals(listOf(0L, 480L, 960L, 1680L, 1920L), tight.notes.map { it.timePulses })
        assertEquals(base.notes.map { it.velocity }, tight.notes.map { it.velocity }, "feel moves, dynamics don't")
        assertEquals(base.bars, tight.bars)
    }

    @Test
    fun `half time doubles times, lengths and bars`() {
        val half = GrooveVariations.halfTime(base)
        assertEquals(2, half.bars)
        assertEquals(base.notes.map { it.timePulses * 2 }, half.notes.map { it.timePulses })
        assertEquals(base.notes.map { it.lengthPulses * 2 }, half.notes.map { it.lengthPulses })
    }

    @Test
    fun `sparse keeps the strong hits and drops the ghosts`() {
        val sparse = GrooveVariations.sparse(base)
        assertEquals(3, sparse.notes.size, "the two ghost hats fall away")
        assertTrue(sparse.notes.all { it.velocity >= 0.85f })
        // A clip of uniform velocity thins to itself, never to nothing.
        val flat = base.copy(notes = base.notes.map { it.copy(velocity = 0.5f) })
        assertEquals(flat.notes.size, GrooveVariations.sparse(flat).notes.size)
    }

    @Test
    fun `swing pushes even 16ths late by exactly the formula and nothing else`() {
        val swung = GrooveVariations.swing(base, 66)
        // Quantized first (0, 480, 960, 1680, 1920), then every odd-index
        // 16th pushed by (66-50)*240/50 = 76 pulses.
        assertEquals(listOf(0L, 480L, 960L, 1680L + 76, 1920L), swung.notes.map { it.timePulses })
        assertEquals(base.notes.map { it.velocity }, swung.notes.map { it.velocity }, "swing is time, not dynamics")
        assertEquals("Break Swing 66", swung.name)

        // 50% is straight: identical to plain quantize, name aside.
        assertEquals(
            GrooveVariations.quantize(base, Mpc3Clip.PULSES_PER_16TH).notes,
            GrooveVariations.swing(base, 50).notes,
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> { GrooveVariations.swing(base, 49) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GrooveVariations.swing(base, 76) }
    }

    @Test
    fun `humanize is seeded, bounded, and leaves dynamics alone`() {
        val a = GrooveVariations.humanize(base, 1f, seed = 5)
        val b = GrooveVariations.humanize(base, 1f, seed = 5)
        assertEquals(a, b, "same seed, same feel")
        assertTrue(a != GrooveVariations.humanize(base, 1f, seed = 6), "different dice, different feel")

        base.notes.zip(a.notes).forEach { (orig, loose) ->
            assertTrue(
                kotlin.math.abs(loose.timePulses - orig.timePulses) <= Mpc3Clip.PULSES_PER_16TH / 2,
                "loose, not sloppy: ${orig.timePulses} -> ${loose.timePulses}",
            )
            assertEquals(orig.velocity, loose.velocity)
        }
        assertEquals(base.notes, GrooveVariations.humanize(base, 0f, seed = 5).notes, "zero amount is a no-op")
    }

    @Test
    fun `asking for swing swaps the tight slot, budget intact`() {
        val four = GrooveVariations.standard(base, swingPercent = 62)
        assertEquals(4, four.size)
        assertEquals("Break Swing 62", four[1].name)
        assertEquals(base, four[0])
        assertEquals("Break Half", four[2].name)
    }

    @Test
    fun `four clips ride the payload with keys one to four`() {
        val four = GrooveVariations.standard(base)
        val writer = com.snipsnap.mpc3.Mpc3TrackWriter()
        val payload = writer.payloadText(
            com.snipsnap.xpm.DrumProgram("Clips Kit", listOf(null)),
            clips = four,
        )
        for (clip in four) assertTrue("\"${clip.name}\"" in payload, clip.name)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            writer.payloadText(
                com.snipsnap.xpm.DrumProgram("Too Many", listOf(null)),
                clips = four + base,
            )
        }
    }
}
