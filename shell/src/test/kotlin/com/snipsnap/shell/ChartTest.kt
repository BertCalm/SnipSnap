package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("chart").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val s16 = Mpc3Clip.PULSES_PER_16TH
    private val bar = Mpc3Clip.PULSES_PER_BAR

    /** Kick on A01, snare on A02, a closed hat with a long name on A03; A04 stays empty. */
    private fun kit(): Kit {
        val m = KitBuilderModel.create("Kick Groove", File(temp, "Kick Groove"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.hat(0.08f, 0.02), DrumClass.HAT_CLOSED)
        m.update(3) { it.copy(displayName = "Closed Hat From The Seventies Record") }
        return m.kit
    }

    private fun lines(text: String) = text.lines()

    /**
     * A pad above the wrap charts as its pad, not as a negative slot.
     *
     * The writer's map is chromatic with wraparound, so pad 93 plays note
     * 0. Reading the slot back as `note - 35` gives it -35, which is no
     * pad at all, and the row renders "NOTE 0 (NO PAD)" for a pad the kit
     * really has. GROOVE's CHART button reaches this.
     */
    @Test
    fun `a pad above the wrap charts as its pad, not as no pad`() {
        val m = KitBuilderModel.create("Upper", File(temp, "Upper"))
        m.assign(93, DrumSynth.snare(), DrumClass.SNARE)
        m.update(93) { it.copy(displayName = "High Snare") }
        val k = m.kit

        val note = Mpc3Note(Mpc3Note.noteFor(93), 0, 0.9f)
        val text = Chart.render(Mpc3Clip("High", 1, listOf(note)), k, 92f, bpmIsDefault = false)

        assertEquals(93, Chart.slotOf(note), "the chart must read the pad the writer wrote")
        assertFalse(text.contains("NO PAD"), "the kit has this pad:\n$text")
        assertTrue(text.contains("HIGH SNARE"), "the pad's own name should label its row:\n$text")
    }

    @Test
    fun `a straight two-bar groove charts row by row, glyph by velocity`() {
        val notes = buildList {
            for (b in 0 until 2) {
                add(Mpc3Note(36, b * bar, 0.9f)) // kick, beat 1
                add(Mpc3Note(36, b * bar + 8 * s16, 0.5f)) // kick, beat 3, mid
                add(Mpc3Note(37, b * bar + 4 * s16, 1f)) // snare, beats 2 and 4
                add(Mpc3Note(37, b * bar + 12 * s16, 0.8f))
                for (e in 0 until 16) add(Mpc3Note(38, b * bar + e * s16, if (e == 0) 0.6f else 0.3f)) // hats on every 16th
            }
        }
        val clip = Mpc3Clip("Kick Groove", 2, notes)
        val text = Chart.render(clip, kit(), 92f, bpmIsDefault = false, program = "PROG A")
        val ls = lines(text)

        assertEquals("KICK GROOVE · PROG A · 92 BPM · 2 BARS · 40 NOTES", ls[0])
        assertEquals("=".repeat(ls[0].length), ls[1])

        val kick = ls.first { it.startsWith("A01 KICK") }
        val snare = ls.first { it.startsWith("A02 SNARE") }
        val hat = ls.first { it.startsWith("A03 CLOSED HAT FROM") }
        assertTrue(hat.contains("CLOSED HAT FROM…"), "a long name is cut with an ellipsis, never silently: $hat")
        val labelWidth = kick.indexOf('|')
        assertEquals(labelWidth, snare.indexOf('|'), "the grid lines up under one label column")
        assertEquals(labelWidth, hat.indexOf('|'))

        assertEquals("|X.......x.......|X.......x.......|", kick.substring(labelWidth))
        assertEquals("|....X.......X...|....X.......X...|", snare.substring(labelWidth))
        assertEquals("|xooooooooooooooo|xooooooooooooooo|", hat.substring(labelWidth))

        val ruler = ls.first { it.contains("|1...2...3...4...|1...2...3...4...|") }
        assertEquals(labelWidth, ruler.indexOf('|'), "the beat ruler sits over the grid")
        assertTrue(ls.any { it.contains("BAR 1") && it.contains("BAR 2") }, "bars are numbered above the ruler")

        assertTrue("EVEN 16THS ON THE GRID. STRAIGHT." in text, text)
        assertFalse("OFF THE GRID" in text, "nothing to footnote on a straight groove")
        assertEquals(text, Chart.render(clip, kit(), 92f, false, "PROG A"), "same clip, same chart")
    }

    @Test
    fun `off-grid hits are drawn where they nearly are and named for where they really are`() {
        val notes = listOf(
            Mpc3Note(36, 0, 0.9f),
            Mpc3Note(37, 4 * s16 + 31, 0.5f), // 31 pulses late of beat 2
            Mpc3Note(37, bar + 12 * s16 - 20, 0.9f), // 20 pulses early of bar 2 beat 4
            Mpc3Note(36, 2 * bar - 1, 0.7f), // one pulse before the loop's end: nearest cell is the top
        )
        val clip = Mpc3Clip("Loose", 2, notes)
        val text = Chart.render(clip, kit(), 92f, bpmIsDefault = false)
        val ls = lines(text)
        val kick = ls.first { it.startsWith("A01 KICK") }
        val snare = ls.first { it.startsWith("A02 SNARE") }
        val w = kick.indexOf('|')

        assertEquals("|....>...........|............<...|", snare.substring(w))
        // The wrapped hit lands on bar 1 step 1 — the same cell as the
        // on-grid kick, which is louder and keeps the glyph.
        assertEquals("|X...............|................|", kick.substring(w))

        assertTrue("OFF THE GRID — DRAWN IN THE NEAREST CELL, NOT MOVED THERE:" in text, text)
        assertTrue("OFF-GRID 1: A02 BAR 1 STEP 5, +31 PULSES LATE (x)" in text, text)
        assertTrue("OFF-GRID 2: A02 BAR 2 STEP 13, −20 PULSES EARLY (X)" in text, text)
        assertTrue("OFF-GRID 3: A01 BAR 1 STEP 1, −1 PULSE EARLY, ACROSS THE LOOP'S END (x)" in text, text)
        assertTrue("1 HIT SHARES A CELL WITH ANOTHER." in text, text)
        assertEquals(Chart.Summary(notes = 4, offGrid = 3, pads = 2), Chart.summary(clip))
    }

    @Test
    fun `the swing line reads a swung clip back to its panel percent`() {
        val straight = Mpc3Clip(
            "Hats", 1,
            (0 until 16).map { Mpc3Note(38, it * s16, 0.6f) },
        )
        val swung = GrooveVariations.swing(straight, 62)
        val line = Chart.swingLine(swung)
        // (62−50)/50 of a 16th is 57.6, which Mpc3Clip.swingPush rounds to
        // 58. It read 57 while the push truncated through Long division -
        // the same pulse a ring swung to 62 never agreed with. Either way
        // the readback is 62%: the inverse is robust to the rounding, and
        // taking the figure from the constant keeps this from going stale
        // the next time the push is touched.
        assertEquals(58L, Mpc3Clip.swingPush(62))
        assertEquals("EVEN 16THS +${Mpc3Clip.swingPush(62)} PULSES, ≈ SWING 62%", line)
        assertEquals("EVEN 16THS ON THE GRID. STRAIGHT.", Chart.swingLine(straight))
        // The panel's maximum pushes every "and" by exactly half a 16th (120
        // pulses). A halfway hit belongs to the earlier cell, as its late
        // half - so max swing reads back as max swing, not as early
        // downbeats with no ands.
        val max = GrooveVariations.swing(straight, 75)
        assertEquals("EVEN 16THS +120 PULSES, ≈ SWING 75%", Chart.swingLine(max))
        val maxChart = Chart.render(max, kit(), 92f, bpmIsDefault = false)
        val hatRow = lines(maxChart).first { it.startsWith("A03") }
        assertEquals("|x>x>x>x>x>x>x>x>|", hatRow.substring(hatRow.indexOf('|')))
        assertTrue("OFF-GRID 1: A03 BAR 1 STEP 2, +120 PULSES LATE (x)" in maxChart, maxChart)
        assertFalse("PULSES EARLY" in maxChart, "no hit of a swung clip is footnoted as early")

        val early = straight.copy(notes = straight.notes.map { if ((it.timePulses / s16) % 2 == 1L) it.copy(timePulses = it.timePulses - 30) else it })
        assertEquals("EVEN 16THS −30 PULSES EARLY. NOT A SWING — NO PANEL SETTING LANDS EARLY.", Chart.swingLine(early))

        val downbeatsOnly = Mpc3Clip("Kick", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(36, 8 * s16, 0.9f)))
        assertEquals("NO HITS ON THE EVEN 16THS. NOTHING TO CALL SWING.", Chart.swingLine(downbeatsOnly))
    }

    @Test
    fun `long clips wrap into four-bar systems with the labels repeated`() {
        val notes = (0 until 6).map { b -> Mpc3Note(36, b * bar, 0.9f) } + Mpc3Note(36, 5 * bar + 8 * s16, 0.9f)
        val text = Chart.render(Mpc3Clip("Six", 6, notes), kit(), 120f, bpmIsDefault = false)
        val ls = lines(text)
        val kickRows = ls.filter { it.startsWith("A01 KICK") }
        assertEquals(2, kickRows.size, "six bars is two systems: four, then two")
        val w = kickRows[0].indexOf('|')
        assertEquals("|X...............|X...............|X...............|X...............|", kickRows[0].substring(w))
        assertEquals("|X...............|X.......X.......|", kickRows[1].substring(w))
        assertTrue(ls.any { it.contains("BAR 5") && it.contains("BAR 6") && !it.contains("BAR 4") })
    }

    @Test
    fun `the header says when the tempo is a stand-in, and a pad the kit lacks says so`() {
        val k = kit()
        val notes = listOf(
            Mpc3Note(36, 0, 0.9f),
            Mpc3Note(39, 4 * s16, 0.9f), // A04 exists as a slot but holds no pad
            // Note 30 used to chart as "NOTE 30 (NO PAD)", on the belief
            // that notes under 36 were off the map. They are not: the map
            // wraps, so 30 is pad 123 - H11 - which this kit simply does
            // not have. "(EMPTY)" is the honest answer; "NO PAD" was the
            // old inverse's arithmetic showing through.
            Mpc3Note(30, 8 * s16, 0.9f),
        )
        assertEquals(123, Mpc3Note.slotFor(30), "note 30 is a pad, not a gap")
        val text = Chart.render(Mpc3Clip("Odd", 1, notes), k, 92f, bpmIsDefault = true)
        assertTrue(text.startsWith("ODD · 92 BPM (STAND-IN, NO TEMPO SET) · 1 BAR · 3 NOTES"), text)
        assertTrue(lines(text).any { it.startsWith("A04 (EMPTY)") }, text)
        assertTrue(lines(text).any { it.startsWith("H11 (EMPTY)") }, text)
        assertFalse(text.contains("NO PAD"), "every note is some pad now:\n$text")
        // Rows run in slot order, so the wrapped note sorts last, not first.
        val order = lines(text).filter { it.startsWith("H11") || it.startsWith("A0") }.map { it.substringBefore(' ') }
        assertEquals(listOf("A01", "A04", "H11"), order)

        val empty = Chart.render(Mpc3Clip("Silence", 2, emptyList()), k, 92f, false)
        assertTrue("NO HITS. AN EMPTY CHART IS STILL A CHART." in empty, empty)
    }

    @Test
    fun `an arrangement charts every section under its own reason`() {
        val k = kit()
        val a = Mpc3Clip("Kick Groove", 2, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 4 * s16, 0.9f)))
        val b = Mpc3Clip("Kick Groove Half", 2, listOf(Mpc3Note(36, 0, 0.9f)))
        val arrangement = Arranger.Arrangement(
            "Kick Groove Song", 7,
            listOf(
                Arranger.Section("Intro", b, 1, "half time opens the room"),
                Arranger.Section("Theme", a, 2, "the break as captured"),
            ),
        )
        val text = Chart.render(arrangement, k, 92f, bpmIsDefault = false)
        val ls = lines(text)
        assertEquals("KICK GROOVE SONG · SEED 7 · 92 BPM · 6 BARS · 2 SECTIONS", ls[0])
        assertTrue("INTRO · 2 BARS (2 × 1) · KICK GROOVE HALF" in text, text)
        assertTrue("THEME · 4 BARS (2 × 2) · KICK GROOVE" in text, text)
        assertTrue("HALF TIME OPENS THE ROOM" in text)
        assertTrue("THE BREAK AS CAPTURED" in text)
        assertEquals(2, ls.count { it.startsWith("A01 KICK") }, "one grid per section, not per repeat")
        assertEquals(1, ls.count { it.startsWith("A02 SNARE") }, "the intro has no snare row - only pads the clip plays")
    }
}
