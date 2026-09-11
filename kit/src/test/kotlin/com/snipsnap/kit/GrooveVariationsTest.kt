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
    fun `the fill owns the turn of every fourth bar and nothing else`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val ppb = Mpc3Clip.PULSES_PER_BAR
        // Four bars of a steady pattern: kick 1 and 3, snare 2 and 4, hats 8ths.
        val notes = mutableListOf<Mpc3Note>()
        for (bar in 0 until 4) {
            val b = bar * ppb
            notes += Mpc3Note(36, b, 0.9f)
            notes += Mpc3Note(36, b + 8 * s16, 0.85f)
            notes += Mpc3Note(38, b + 4 * s16, 0.85f)
            notes += Mpc3Note(38, b + 12 * s16, 0.9f)
            for (e in 0 until 8) notes += Mpc3Note(42, b + e * 2L * s16, 0.4f)
        }
        val fourBars = Mpc3Clip("Break Groove", 4, notes)
        val kit = Kit(
            "Fill Kit",
            listOf(
                KitPad(1, "A01_Kick_01.wav", drumClass = com.snipsnap.audio.DrumClass.KICK),
                KitPad(2, "A02_Snare_01.wav", drumClass = com.snipsnap.audio.DrumClass.SNARE),
                KitPad(3, "A03_HatClosed_01.wav", drumClass = com.snipsnap.audio.DrumClass.HAT_CLOSED),
            ),
        )
        val fill = GrooveVariations.fill(fourBars, kit, seed = 5)
        assertEquals("Break Fill", fill.name)

        fun inRange(clip: Mpc3Clip, from: Long, until: Long) =
            clip.notes.filter { it.timePulses in from until until }
                .sortedWith(compareBy({ it.timePulses }, { it.note }))

        // Bars 1-3 and the fill bar's first half are byte-equal to the base.
        assertEquals(inRange(fourBars, 0, 3 * ppb), inRange(fill, 0, 3 * ppb), "non-fill bars untouched")
        assertEquals(
            inRange(fourBars, 3 * ppb, 3 * ppb + 8 * s16),
            inRange(fill, 3 * ppb, 3 * ppb + 8 * s16),
            "the fill bar's first half stays as played",
        )

        // The turn is denser than it was, rolls on the kit's snare, and ramps.
        val turnBase = inRange(fourBars, 3 * ppb + 8 * s16, 4 * ppb)
        val turnFill = inRange(fill, 3 * ppb + 8 * s16, 4 * ppb)
        assertTrue(turnFill.size > turnBase.size, "the turn densifies: ${turnBase.size} -> ${turnFill.size}")
        val roll = turnFill.filter { it.note == 35 + 2 }
        assertTrue(roll.size >= 12, "16ths on beat 3, 32nds on beat 4: ${roll.size} snare hits")
        val beat4 = roll.filter { it.timePulses >= 3 * ppb + 12 * s16 }
        assertEquals(8, beat4.size, "beat 4 rolls 32nds")
        assertTrue(
            beat4.last().velocity > beat4.first().velocity,
            "the roll ramps into the turn: ${beat4.first().velocity} -> ${beat4.last().velocity}",
        )
        assertTrue(turnFill.all { it.note - 35 in setOf(1, 2, 3) }, "only pads the kit has")

        assertEquals(fill, GrooveVariations.fill(fourBars, kit, seed = 5), "same seed, same fill")
        assertTrue(fill != GrooveVariations.fill(fourBars, kit, seed = 6), "a new seed rerolls the jitter")

        // A one-bar groove fills its only bar; a kit with nothing to roll on refuses.
        val oneBar = GrooveVariations.fill(base, kit)
        assertTrue(oneBar.notes.any { it.timePulses >= 12 * s16 && it.note == 35 + 2 })
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GrooveVariations.fill(base, Kit("K", listOf(KitPad(1, "a.wav", drumClass = com.snipsnap.audio.DrumClass.KICK))))
        }
    }

    @Test
    fun `ghosts whisper around the backbeats and never pile on`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val kit = Kit(
            "Ghost Kit",
            listOf(
                KitPad(1, "A01_Kick_01.wav", drumClass = com.snipsnap.audio.DrumClass.KICK),
                KitPad(2, "A02_Snare_01.wav", drumClass = com.snipsnap.audio.DrumClass.SNARE),
            ),
        )
        // Step 3 (the e of 2) is already occupied - a ghost must not land there.
        val withE = base.copy(notes = base.notes + Mpc3Note(42, 3 * s16, 0.5f))
        val ghosted = GrooveVariations.ghosted(withE, kit, seed = 4)
        assertEquals("Break Ghosted", ghosted.name)

        val backbone = withE.notes.sortedWith(compareBy({ it.timePulses }, { it.note }))
        val kept = ghosted.notes.filter { it in withE.notes }.sortedWith(compareBy({ it.timePulses }, { it.note }))
        assertEquals(backbone, kept, "the backbone is untouched")

        val ghosts = ghosted.notes.filter { it !in withE.notes }
        assertTrue(ghosts.isNotEmpty(), "the grammar found room to whisper")
        for (g in ghosts) {
            val step = (g.timePulses / s16).toInt() % 16
            assertTrue(step in setOf(3, 5, 11, 13), "ghosts live on the e/a around 2 and 4, got step $step")
            assertTrue(step != 3, "an occupied candidate is left alone")
            assertTrue(
                g.velocity <= GrooveVariations.GHOST_VELOCITY_CEILING && g.velocity >= 0.15f,
                "a ghost is a whisper: ${g.velocity}",
            )
            assertEquals(35 + 2, g.note, "ghosts whisper on the snare")
        }
        assertEquals(ghosted, GrooveVariations.ghosted(withE, kit, seed = 4), "same seed, same ghosts")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GrooveVariations.ghosted(base, Kit("K", listOf(KitPad(1, "a.wav", drumClass = com.snipsnap.audio.DrumClass.KICK))))
        }
    }

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
        // 16th pushed by Mpc3Clip.swingPush(66).
        //
        // 77, not 76: (66-50)/50 of a 240-pulse 16th is 76.8, and this
        // used to reach 76 through `Long` division while a ring swung to
        // the same percent rounded to 77 and exported a different file.
        // One pulse, and two answers to one question; the push now has a
        // single home that rounds to nearest.
        assertEquals(77L, Mpc3Clip.swingPush(66))
        assertEquals(listOf(0L, 480L, 960L, 1680L + 77, 1920L), swung.notes.map { it.timePulses })
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
