package com.snipsnap.kit

import com.snipsnap.kit.GrooveEdit.Lane
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BEAT step 2: baked swing, bar copy/paste, double/half, and the step undo stack. */
class GrooveStepToolsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("groove-tools").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val kick = Mpc3Note.noteFor(1)
    private val hat = Mpc3Note.noteFor(3)

    /** Kick on the beats and a hat on every 16th: both parities, both bars. */
    private fun straight(bars: Int = 2): Mpc3Clip {
        val step = GrooveEdit.STEP_PULSES
        val notes = (0 until bars * 16).map { Mpc3Note(hat, it * step, 0.6f) } +
            (0 until bars * 4).map { Mpc3Note(kick, it * 4 * step, 0.9f) }
        return Mpc3Clip("Kit E", bars, notes.sortedBy { it.timePulses })
    }

    // ---- swing ----

    @Test
    fun `a bake of a straight pattern is exactly what the SWING program is, so exports match`() {
        val clip = straight()
        for (percent in listOf(50, 58, 62, 66, 75)) {
            val baked = GrooveEdit.bakeSwing(clip, percent)
            val program = GrooveVariations.swing(clip, percent)
            assertEquals(program.notes, baked.notes, "at $percent%")
            assertEquals(program.bars, baked.bars)
        }
    }

    @Test
    fun `applying the same swing twice is the same clip`() {
        val once = GrooveEdit.bakeSwing(straight(), 62)
        assertEquals(once, GrooveEdit.bakeSwing(once, 62))
        for (p in 50..75) {
            val a = GrooveEdit.bakeSwing(straight(), p)
            assertEquals(a, GrooveEdit.bakeSwing(a, p), "idempotent at $p%")
        }
    }

    @Test
    fun `applying another amount re-bakes from straight, it does not stack`() {
        val at70then58 = GrooveEdit.bakeSwing(GrooveEdit.bakeSwing(straight(), 70), 58)
        assertEquals(GrooveEdit.bakeSwing(straight(), 58), at70then58)
        assertEquals(straight().notes, GrooveEdit.bakeSwing(GrooveEdit.bakeSwing(straight(), 75), 50).notes, "50 straightens")
    }

    @Test
    fun `swung notes stay in their own cell, at every amount`() {
        for (p in 50..75) {
            val baked = GrooveEdit.bakeSwing(straight(), p)
            for (n in baked.notes) {
                val cell = GrooveEdit.cellOf(n.timePulses)
                val expectedStraight = (n.timePulses - if (cell % 2 == 1) Mpc3Clip.swingPush(p) else 0L)
                assertEquals(cell * GrooveEdit.STEP_PULSES, expectedStraight, "note at ${n.timePulses} at $p%")
            }
        }
    }

    @Test
    fun `a swung step is lit in its cell, toggles off from it and keeps its velocity edits`() {
        val baked = GrooveEdit.bakeSwing(straight(), 75)
        val swungHat = baked.notes.first { it.note == hat && it.timePulses == GrooveEdit.STEP_PULSES + Mpc3Clip.swingPush(75) }
        assertEquals(0.6f, GrooveEdit.velocityAt(baked, Lane.HAT_CLOSED, 1))
        val louder = GrooveEdit.setStepVelocity(baked, Lane.HAT_CLOSED, 1, 1f)
        assertEquals(1f, louder.notes.first { it.timePulses == swungHat.timePulses && it.note == hat }.velocity)
        assertEquals(baked.notes.size, louder.notes.size)
        val off = GrooveEdit.toggleStep(baked, Lane.HAT_CLOSED, 1)
        assertEquals(baked.notes.size - 1, off.notes.size)
        assertNull(GrooveEdit.velocityAt(off, Lane.HAT_CLOSED, 1))
        // Off then on lands a straight note in that cell: it reads back and is not doubled.
        val on = GrooveEdit.toggleStep(off, Lane.HAT_CLOSED, 1)
        assertEquals(0.6f, GrooveEdit.velocityAt(on, Lane.HAT_CLOSED, 1))
        assertEquals(baked.notes.size, on.notes.size)
        // The neighbours (cells 0 and 2) are untouched by a toggle in cell 1, even at the full 75% push.
        assertEquals(0.6f, GrooveEdit.velocityAt(on, Lane.HAT_CLOSED, 0))
        assertEquals(0.6f, GrooveEdit.velocityAt(on, Lane.HAT_CLOSED, 2))
    }

    @Test
    fun `cells own a window from just before the step to half a step after`() {
        assertEquals(0, GrooveEdit.cellOf(0))
        assertEquals(0, GrooveEdit.cellOf(120))
        assertEquals(1, GrooveEdit.cellOf(121))
        assertEquals(1, GrooveEdit.cellOf(240 + 120))
        assertEquals(2, GrooveEdit.cellOf(480 - 119))
    }

    @Test
    fun `the last step swings without leaving the loop and a late hit wraps like a fork does`() {
        val last = 31
        val clip = Mpc3Clip("Kit E", 2, listOf(Mpc3Note(hat, last * GrooveEdit.STEP_PULSES, 0.5f)))
        val baked = GrooveEdit.bakeSwing(clip, 75)
        assertTrue(baked.notes.single().timePulses < clip.lengthPulses)
        assertEquals(last, GrooveEdit.cellOf(baked.notes.single().timePulses))
        val wrap = Mpc3Clip("Kit E", 1, listOf(Mpc3Note(kick, 15 * 240L + 200, 0.5f)))
        assertEquals(0L, GrooveEdit.bakeSwing(wrap, 62).notes.single().timePulses)
    }

    @Test
    fun `two notes landing on one pulse keep the louder, and velocity and length are untouched`() {
        val clip = Mpc3Clip(
            "Kit E", 1,
            listOf(Mpc3Note(kick, 230, 0.4f, lengthPulses = 100), Mpc3Note(kick, 250, 0.8f, lengthPulses = 77)),
        )
        val baked = GrooveEdit.bakeSwing(clip, 66)
        assertEquals(1, baked.notes.size)
        assertEquals(0.8f, baked.notes.single().velocity)
        assertEquals(77L, baked.notes.single().lengthPulses)
    }

    @Test
    fun `an out of range amount is refused`() {
        assertFailsWith<IllegalArgumentException> { GrooveEdit.bakeSwing(straight(), 80) }
    }

    @Test
    fun `a baked pattern survives the file untouched`() {
        val dir = File(temp, "kit")
        val base = Mpc3Clip("Kit", 2, emptyList())
        val e = GrooveEdit.bakeSwing(straight(), 62)
        GrooveStore.save(dir, listOf(base, e))
        assertEquals(e, GrooveStore.load(dir).first { it.name == "Kit E" })
    }

    // ---- copy / paste ----

    @Test
    fun `copy then paste puts bar one's notes over bar two, whatever was there, on every pad`() {
        val step = GrooveEdit.STEP_PULSES
        var clip = straight()
        clip = clip.copy(notes = (clip.notes + Mpc3Note(Mpc3Note.noteFor(15), 16 * step + 5, 0.3f)).sortedBy { it.timePulses })
        val copy = GrooveBars.copyBar(clip, 0)
        assertEquals(clip.notes.count { it.timePulses < 16 * step }, copy.notes.size)
        assertTrue(copy.notes.all { it.timePulses in 0 until 16 * step })
        val pasted = GrooveBars.pasteBar(clip, 1, copy)
        assertEquals(clip.notes.filter { it.timePulses < 16 * step }, pasted.notes.filter { it.timePulses < 16 * step }, "bar one is untouched")
        val bar2 = pasted.notes.filter { it.timePulses >= 16 * step }.map { it.copy(timePulses = it.timePulses - 16 * step) }
        assertEquals(copy.notes, bar2)
        assertEquals(2, pasted.bars)
    }

    @Test
    fun `paste onto the bar it came from is the same clip, and an empty bar pastes silence`() {
        val clip = straight()
        assertEquals(clip, GrooveBars.pasteBar(clip, 0, GrooveBars.copyBar(clip, 0)))
        val silent = GrooveBars.pasteBar(clip, 1, GrooveBars.BarCopy(clip.pulsesPerBar, emptyList()))
        assertTrue(silent.notes.none { it.timePulses >= clip.pulsesPerBar })
    }

    @Test
    fun `a bar of another meter is refused by paste and nothing changes`() {
        val clip = straight()
        val waltz = Mpc3Clip("W E", 1, listOf(Mpc3Note(kick, 0, 0.9f)), pulsesPerBar = 3 * 960L)
        val copy = GrooveBars.copyBar(waltz, 0)
        assertEquals(false, GrooveBars.fits(clip, copy))
        assertSame(clip, GrooveBars.pasteBar(clip, 0, copy))
    }

    // ---- double / half ----

    @Test
    fun `double repeats the pattern one length later and halves the grid back`() {
        val clip = straight(2)
        val d = GrooveBars.doubled(clip)
        assertEquals(4, d.bars)
        assertEquals(clip.notes.size * 2, d.notes.size)
        assertEquals(clip.notes, d.notes.filter { it.timePulses < clip.lengthPulses })
        assertEquals(clip.notes.map { it.copy(timePulses = it.timePulses + clip.lengthPulses) }, d.notes.filter { it.timePulses >= clip.lengthPulses })
        assertEquals(clip, GrooveBars.halved(d), "half of a double is the original")
    }

    @Test
    fun `double stops at the cap and says so by returning the clip`() {
        val big = Mpc3Clip("Kit E", 33, emptyList())
        assertSame(big, GrooveBars.doubled(big))
        val sixtyFour = Mpc3Clip("Kit E", 64, emptyList())
        assertEquals(false, GrooveBars.canDouble(sixtyFour))
        assertEquals(64, GrooveBars.doubled(Mpc3Clip("Kit E", 32, emptyList())).bars)
        val eight = Mpc3Clip("Kit E", 8, emptyList())
        assertSame(eight, GrooveBars.doubled(eight, maxBars = 8))
        assertEquals(8, GrooveBars.doubled(Mpc3Clip("Kit E", 4, emptyList()), maxBars = 8).bars)
    }

    @Test
    fun `half keeps the first half, cuts lengths at the new end, and refuses odd or single bars`() {
        val clip = Mpc3Clip("Kit E", 2, listOf(Mpc3Note(kick, 0, 0.9f), Mpc3Note(kick, 3800, 0.9f, 500), Mpc3Note(hat, 3840, 0.5f)))
        val h = GrooveBars.halved(clip)
        assertEquals(1, h.bars)
        assertEquals(listOf(0L, 3800L), h.notes.map { it.timePulses })
        assertEquals(40L, h.notes.last().lengthPulses)
        val three = Mpc3Clip("Kit E", 3, emptyList())
        assertSame(three, GrooveBars.halved(three))
        val one = Mpc3Clip("Kit E", 1, emptyList())
        assertSame(one, GrooveBars.halved(one))
    }

    @Test
    fun `double keeps a baked swing and the lanes read the same in the new bars`() {
        val baked = GrooveEdit.bakeSwing(straight(1), 62)
        val d = GrooveBars.doubled(baked)
        assertEquals(GrooveEdit.bakeSwing(d, 62), d, "still a fixed point of the same swing")
        assertEquals(0.6f, GrooveEdit.velocityAt(d, Lane.HAT_CLOSED, 17))
    }

    // ---- undo ----

    @Test
    fun `history pops newest first and is empty past the oldest`() {
        val h = StepHistory<Int>()
        h.push(1); h.push(2); h.push(3)
        assertEquals(3, h.size)
        assertEquals(3, h.pop()); assertEquals(2, h.pop()); assertEquals(1, h.pop())
        assertNull(h.pop())
        assertEquals(0, h.size)
    }

    @Test
    fun `history forgets the oldest past 32 and never grows past it`() {
        val h = StepHistory<Int>()
        assertEquals(32, h.limit)
        for (i in 1..40) h.push(i)
        assertEquals(32, h.size)
        val all = generateSequence { h.pop() }.toList()
        assertEquals((40 downTo 9).toList(), all)
    }

    @Test
    fun `undoing a chain of edits walks the clip back state by state`() {
        val h = StepHistory<Mpc3Clip>()
        var clip = Mpc3Clip("Kit E", 1, emptyList())
        val states = mutableListOf(clip)
        val edits: List<(Mpc3Clip) -> Mpc3Clip> = listOf(
            { GrooveEdit.toggleStep(it, Lane.KICK, 0) },
            { GrooveEdit.setStepVelocity(it, Lane.KICK, 0, 1f) },
            { GrooveEdit.bakeSwing(it, 62) },
            { GrooveBars.doubled(it) },
        )
        for (edit in edits) {
            h.push(clip)
            clip = edit(clip)
            states += clip
        }
        for (i in states.lastIndex - 1 downTo 0) assertEquals(states[i], h.pop())
    }

    @Test
    fun `lane maps ride the project beat file with the clips`() {
        val kit = File(temp, "kit2")
        val e = Mpc3Clip("Kit E", 1, listOf(Mpc3Note(kick, 0, 0.9f)))
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 5)
        GrooveStore.save(kit, listOf(e), mapOf(e.name to pads))
        val project = File(temp, "proj")
        ProjectStore.saveBeat(project, GrooveStore.load(kit), GrooveStore.loadLanePads(kit))
        assertEquals(mapOf(e.name to pads), ProjectStore.beatLanePads(project))
        ProjectStore.saveBeat(project, GrooveStore.load(kit))
        assertEquals(mapOf(e.name to pads), ProjectStore.beatLanePads(project), "an older caller keeps the maps the file holds")
    }
}
