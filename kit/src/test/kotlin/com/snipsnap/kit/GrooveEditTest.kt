package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GROOVE PROG E: the user's fork, its step edits, and its persistence. */
class GrooveEditTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("groove-edit").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val base = Mpc3Clip(
        "Break Groove", 2,
        listOf(
            Mpc3Note(36, 0, 1.0f, 240),      // on the grid already
            Mpc3Note(42, 503, 0.30f, 120),   // pushed off-grid
            Mpc3Note(38, 960, 0.85f, 240),   // on the grid
            Mpc3Note(42, 1685, 0.25f, 120),  // pushed off-grid
        ),
    )

    private fun dirWith(vararg clips: Mpc3Clip): File {
        val dir = File(temp, "kit-${java.util.UUID.randomUUID()}")
        GrooveStore.save(dir, clips.toList())
        return dir
    }

    @Test
    fun `fork quantizes the source clip's notes to the 16th grid`() {
        val dir = dirWith(base)
        val e = GrooveEdit.fork(dir, base)

        assertEquals(listOf(0L, 480L, 960L, 1680L), e.notes.map { it.timePulses }, "every start snaps to the grid")
        assertEquals(base.notes.map { it.velocity }, e.notes.map { it.velocity }, "quantizing moves time, not dynamics")
        assertEquals("Break Groove E", e.name)
        assertTrue(GrooveEdit.isProgE(e))
    }

    @Test
    fun `fork from a derived program works and leaves A untouched in the store`() {
        val dir = dirWith(base)
        val swungB = GrooveVariations.swing(base, 66) // "Break Swing 66" - a derived program, never stored

        val e = GrooveEdit.fork(dir, swungB)

        // E's name comes from the stored base, not from B's own derived name.
        assertEquals("Break Groove E", e.name)
        assertEquals(swungB.notes.map { it.note }, e.notes.map { it.note })

        val stored = GrooveStore.load(dir)
        assertEquals(2, stored.size, "the base and the new E, nothing else")
        assertEquals(base, stored.first { !GrooveEdit.isProgE(it) }, "A is byte-for-byte untouched")
    }

    @Test
    fun `re-fork replaces the stored E only when explicitly asked`() {
        val dir = dirWith(base)
        val e1 = GrooveEdit.fork(dir, base)
        val edited = GrooveEdit.toggleStep(e1, GrooveEdit.Lane.SNARE, 3)
        GrooveEdit.save(dir, edited)

        // Re-entering (no replace) hands back the same edited E, untouched.
        val reentered = GrooveEdit.fork(dir, base)
        assertEquals(edited, reentered, "re-entering edits the SAME E")
        assertEquals(edited, GrooveEdit.load(dir), "the store still carries the edit")

        // An explicit re-fork discards the edit and starts clean from A again.
        val refork = GrooveEdit.fork(dir, base, replace = true)
        assertEquals(GrooveEdit.quantized(base, "Break Groove E"), refork, "a fresh fork, edits gone")
        assertTrue(refork != edited)
        assertEquals(refork, GrooveEdit.load(dir))
    }

    @Test
    fun `toggling a step on then off round-trips exactly`() {
        val dir = dirWith(base)
        val e = GrooveEdit.fork(dir, base)

        val toggledOn = GrooveEdit.toggleStep(e, GrooveEdit.Lane.KICK, 7)
        assertTrue(
            toggledOn.notes.any { it.note == GrooveEdit.noteFor(GrooveEdit.Lane.KICK) && it.timePulses == 7 * GrooveEdit.STEP_PULSES },
            "the new step is there",
        )
        assertEquals(e.notes.size + 1, toggledOn.notes.size)

        val toggledOff = GrooveEdit.toggleStep(toggledOn, GrooveEdit.Lane.KICK, 7)
        assertEquals(e.notes.sortedBy { it.timePulses }, toggledOff.notes, "off undoes on, exactly")
    }

    @Test
    fun `new HAT_CLOSED steps are quieter, everything else lands at the default`() {
        val dir = dirWith(base)
        val e = GrooveEdit.fork(dir, base)

        val hat = GrooveEdit.toggleStep(e, GrooveEdit.Lane.HAT_CLOSED, 2)
        val hatNote = hat.notes.first { it.note == GrooveEdit.noteFor(GrooveEdit.Lane.HAT_CLOSED) }
        assertEquals(GrooveEdit.VELOCITY_HAT_CLOSED, hatNote.velocity)

        val perc = GrooveEdit.toggleStep(e, GrooveEdit.Lane.PERC, 5)
        val percNote = perc.notes.first { it.note == GrooveEdit.noteFor(GrooveEdit.Lane.PERC) }
        assertEquals(GrooveEdit.VELOCITY_DEFAULT, percNote.velocity)
    }

    @Test
    fun `clear bar wipes exactly one bar, every lane, other bars untouched`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val ppb = Mpc3Clip.PULSES_PER_BAR
        val twoBar = Mpc3Clip(
            "Two Bar E", 2,
            listOf(
                Mpc3Note(36, 0, 0.9f),                                        // bar 0, KICK lane
                Mpc3Note(GrooveEdit.noteFor(GrooveEdit.Lane.PERC), 4 * s16, 0.5f), // bar 0, PERC lane
                Mpc3Note(37, ppb, 0.9f),                                      // bar 1, SNARE lane
                Mpc3Note(GrooveEdit.noteFor(GrooveEdit.Lane.HAT_OPEN), ppb + 4 * s16, 0.5f), // bar 1
            ),
        )

        val cleared = GrooveEdit.clearBar(twoBar, 0)
        assertEquals(listOf(ppb, ppb + 4 * s16), cleared.notes.map { it.timePulses }, "bar 0 is gone, bar 1 stands")

        val clearedOther = GrooveEdit.clearBar(twoBar, 1)
        assertEquals(listOf(0L, 4 * s16), clearedOther.notes.map { it.timePulses })
    }

    @Test
    fun `off-lane notes survive a bar wipe`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val clip = Mpc3Clip(
            "Off Lane E", 1,
            listOf(
                Mpc3Note(36, 0, 0.9f),      // KICK lane - wiped
                Mpc3Note(99, 4 * s16, 0.5f), // not one of the editor's five lane notes - survives
            ),
        )

        val cleared = GrooveEdit.clearBar(clip, 0)

        assertEquals(listOf(4 * s16), cleared.notes.map { it.timePulses }, "the lane note is gone, the off-lane note stands")
        assertEquals(99, cleared.notes.single().note)
    }

    @Test
    fun `PROG E round-trips through the sidecar with its identity intact`() {
        val dir = dirWith(base)
        val e = GrooveEdit.fork(dir, base)

        val reloaded = GrooveStore.load(dir).first { GrooveEdit.isProgE(it) }
        assertEquals(e, reloaded)
        assertTrue(GrooveEdit.isProgE(reloaded), "the round-tripped clip is still recognisable as E")
        assertEquals(e, GrooveEdit.load(dir))
    }

    @Test
    fun `hasUserProgram tracks the fork and forgets on delete`() {
        val dir = dirWith(base)
        assertFalse(GrooveEdit.hasUserProgram(dir), "no fork yet")

        GrooveEdit.fork(dir, base)
        assertTrue(GrooveEdit.hasUserProgram(dir), "forked")

        assertTrue(GrooveEdit.deleteProgE(dir))
        assertFalse(GrooveEdit.hasUserProgram(dir), "deleted")
        assertFalse(GrooveEdit.deleteProgE(dir), "nothing left to delete")
        assertNull(GrooveEdit.load(dir))

        // The base clip survives the delete; only E was ever discarded.
        assertEquals(listOf(base), GrooveStore.load(dir))
    }

    @Test
    fun `a source note at the clip's last pulse forks to step 0, not an off-grid tail`() {
        val dir = dirWith(base)
        val tail = base.copy(notes = listOf(Mpc3Note(36, 7679, 0.7f)))
        val e = GrooveEdit.fork(dir, tail)
        assertEquals(listOf(0L), e.notes.map { it.timePulses }, "7679 wraps to the downbeat of the next pass")
    }

    @Test
    fun `the wrap window's exact bottom wraps forward, one pulse earlier snaps back`() {
        val dir = dirWith(base)
        val edge = base.copy(notes = listOf(Mpc3Note(36, 7560, 0.7f), Mpc3Note(38, 7559, 0.7f)))
        val e = GrooveEdit.fork(dir, edge)
        val byNote = e.notes.associateBy { it.note }
        assertEquals(0L, byNote.getValue(36).timePulses, "7560 is the window's bottom - it wraps to step 0")
        assertEquals(7440L, byNote.getValue(38).timePulses, "7559 snaps DOWN to step 31 instead")
    }

    @Test
    fun `a wrap collision keeps the louder note, not both`() {
        val dir = dirWith(base)
        val collide = base.copy(notes = listOf(Mpc3Note(36, 5, 0.4f), Mpc3Note(36, 7675, 0.9f)))
        val e = GrooveEdit.fork(dir, collide)
        assertEquals(1, e.notes.size, "both source notes land on the same (note, step) address")
        assertEquals(0L, e.notes.single().timePulses)
        assertEquals(0.9f, e.notes.single().velocity, "the louder hit survives the collision")
    }

    @Test
    fun `every note in a forked clip sits on the grid, even through a swing-75 push past the loop point`() {
        val dir = dirWith(base)
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val step31 = Mpc3Clip(
            "Step31 Groove", 2,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(38, 1920, 0.85f),
                Mpc3Note(42, 31 * s16, 0.5f), // step 31 - swing(75) pushes this straight past the loop point
            ),
        )
        val swung = GrooveVariations.swing(step31, 75) // push = (75-50)*240/50 = 120, exactly the report's repro

        val e = GrooveEdit.fork(dir, swung)

        assertTrue(e.notes.isNotEmpty())
        e.notes.forEach { n ->
            assertEquals(0L, n.timePulses % s16, "every forked note sits on a 16th: ${n.timePulses}")
        }
        assertTrue(
            e.notes.any { it.note == 42 && it.timePulses == 0L },
            "the step-31 hit wrapped to the downbeat instead of clamping to an unreachable tail",
        )
    }

    @Test
    fun `toggleStep removes a wrapped note by the step address it actually landed on`() {
        val dir = dirWith(base)
        val tail = base.copy(notes = listOf(Mpc3Note(GrooveEdit.noteFor(GrooveEdit.Lane.KICK), 7679, 0.7f)))
        val e = GrooveEdit.fork(dir, tail)
        assertEquals(listOf(0L), e.notes.map { it.timePulses }, "the wrapped note landed on step 0")

        val toggledOff = GrooveEdit.toggleStep(e, GrooveEdit.Lane.KICK, 0)
        assertTrue(toggledOff.notes.isEmpty(), "(lane, 0) addresses the wrapped note - toggling it off removes it")
    }

    @Test
    fun `PROG E flows through the MIDI export path like A through D`() {
        val standard = GrooveVariations.standard(base) // A, B(Tight), C(Half), D(Sparse)
        val dir = dirWith(base)
        val e = GrooveEdit.fork(dir, base)
        val programs = standard + e
        assertEquals(5, programs.size)

        for (clip in programs) {
            val bytes = MidiGroove.write(clip, 92f)
            assertTrue(bytes.isNotEmpty(), "MIDI bytes for ${clip.name}")
            val imported = MidiGroove.read(bytes, clip.name)
            assertEquals(clip.notes.size, imported.clip.notes.size, "notes survive export for ${clip.name}")
        }
        assertTrue(programs.any { GrooveEdit.isProgE(it) }, "E rides in the exported set alongside A-D")
    }
}
