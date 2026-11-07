package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.GrooveEdit.Lane
import com.snipsnap.kit.StepShape.NoteLength
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BEAT step 3: roll, nudge and length, as pure functions on the clip. */
class StepShapeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("step-shape").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val step = GrooveEdit.STEP_PULSES
    private val lane = Lane.HAT_CLOSED
    private val note = GrooveEdit.noteFor(lane)

    private fun lit(vararg steps: Int, bars: Int = 1): Mpc3Clip =
        steps.fold(Mpc3Clip("Kit E", bars, emptyList())) { c, s -> GrooveEdit.toggleStep(c, lane, s) }

    private fun times(c: Mpc3Clip, s: Int) = c.notes.filter { it.note == note && GrooveEdit.cellOf(it.timePulses) == s }.map { it.timePulses }

    // ---- roll ----

    @Test
    fun `a roll of N is N evenly spaced hits at the step's velocity, all inside the step's cell`() {
        for (want in 2..StepShape.MAX_ROLL) {
            for (s in listOf(0, 1, 7, 15)) {
                val c = StepShape.setRoll(lit(s), lane, s, want)
                val got = times(c, s)
                assertEquals(want, got.size, "step $s x$want")
                val gaps = got.zipWithNext { a, b -> b - a }.toSet()
                assertEquals(setOf(StepShape.rollSpacing(want)), gaps, "evenly spaced")
                assertEquals(s * step, got.first(), "starts on the step line")
                assertTrue(got.all { GrooveEdit.cellOf(it) == s }, "every hit in its own cell: $got")
                assertEquals(want, c.notes.size, "nothing else was written")
                assertEquals(setOf(GrooveEdit.VELOCITY_HAT_CLOSED), c.notes.map { it.velocity }.toSet())
            }
        }
    }

    @Test
    fun `the positions of x2, x3 and x4 are the documented ones`() {
        val c = lit(4)
        assertEquals(listOf(0L, 120L), times(StepShape.setRoll(c, lane, 4, 2), 4).map { it - 4 * step })
        assertEquals(listOf(0L, 80L, 160L), times(StepShape.setRoll(c, lane, 4, 3), 4).map { it - 4 * step })
        assertEquals(listOf(0L, 60L, 120L, 180L), times(StepShape.setRoll(c, lane, 4, 4), 4).map { it - 4 * step })
    }

    @Test
    fun `a roll takes the loudest velocity of the step and cuts each hit at the next`() {
        val loud = GrooveEdit.setStepVelocity(lit(2), lane, 2, 1.0f)
        val r = StepShape.setRoll(loud, lane, 2, 4)
        assertEquals(setOf(1.0f), r.notes.map { it.velocity }.toSet())
        assertEquals(setOf(60L), r.notes.map { it.lengthPulses }.toSet())
    }

    @Test
    fun `re-rolling replaces the step's notes and clearing the roll leaves one hit`() {
        val r4 = StepShape.setRoll(lit(3), lane, 3, 4)
        val r2 = StepShape.setRoll(r4, lane, 3, 2)
        assertEquals(2, times(r2, 3).size)
        assertEquals(2, r2.notes.size, "the ×4 hits are gone, not added to")
        val one = StepShape.setRoll(r2, lane, 3, 1)
        assertEquals(listOf(3 * step), times(one, 3))
        assertEquals(1, one.notes.size)
        assertEquals(step, one.notes.single().lengthPulses, "a plain hit is a full step again")
        assertEquals(0, StepShape.rollOf(lit(), lane, 3))
        assertEquals(1, StepShape.rollOf(lit(3), lane, 3))
        assertEquals(4, StepShape.rollOf(r4, lane, 3))
    }

    @Test
    fun `rolling the same count again, or an off step, returns the clip untouched`() {
        val r3 = StepShape.setRoll(lit(3), lane, 3, 3)
        assertSame(r3, StepShape.setRoll(r3, lane, 3, 3))
        val c = lit(3)
        assertSame(c, StepShape.setRoll(c, lane, 3, 1))
        assertSame(c, StepShape.setRoll(c, lane, 5, 4), "never adds a hit to an empty step")
    }

    @Test
    fun `a roll leaves other pads and other steps alone`() {
        val kick = GrooveEdit.toggleStep(lit(4, 5), Lane.KICK, 4)
        val r = StepShape.setRoll(kick, lane, 4, 4)
        assertEquals(1, r.notes.count { it.note == GrooveEdit.noteFor(Lane.KICK) })
        assertEquals(listOf(5 * step), times(r, 5))
    }

    @Test
    fun `a tap on a rolled step removes the whole roll, and velocity is set on every hit`() {
        val r = StepShape.setRoll(lit(6), lane, 6, 4)
        assertTrue(GrooveEdit.toggleStep(r, lane, 6).notes.isEmpty())
        val soft = GrooveEdit.setStepVelocity(r, lane, 6, GrooveEdit.VelocityLevel.SOFT.velocity)
        assertEquals(setOf(GrooveEdit.VelocityLevel.SOFT.velocity), soft.notes.map { it.velocity }.toSet())
        assertEquals(GrooveEdit.VelocityLevel.SOFT.velocity, GrooveEdit.velocityAt(soft, lane, 6))
    }

    @Test
    fun `a roll on the last step stays inside the clip`() {
        val last = GrooveEdit.stepsInClip(lit(0)) - 1
        val r = StepShape.setRoll(lit(last), lane, last, 4)
        assertTrue(r.notes.all { it.timePulses < r.lengthPulses && it.timePulses + it.lengthPulses <= r.lengthPulses })
    }

    // ---- nudge ----

    @Test
    fun `a nudge is an eighth of a step, and earlier and later undo each other`() {
        assertEquals(30L, StepShape.NUDGE_PULSES)
        val c = lit(5)
        val late = StepShape.nudge(c, lane, 5, StepShape.NUDGE_PULSES)
        assertEquals(30, StepShape.offsetOf(late, lane, 5))
        assertEquals(c, StepShape.nudge(late, lane, 5, -StepShape.NUDGE_PULSES))
        assertEquals(GrooveEdit.velocityAt(c, lane, 5), GrooveEdit.velocityAt(late, lane, 5), "still the same step")
    }

    @Test
    fun `a nudge stops at the cell edge and never leaves the step`() {
        var c = lit(5)
        repeat(20) { c = StepShape.nudge(c, lane, 5, StepShape.NUDGE_PULSES) }
        assertEquals(180, StepShape.offsetOf(c, lane, 5), "the cell ends 180 pulses after the line")
        assertEquals(1, times(c, 5).size)
        var e = lit(5)
        repeat(20) { e = StepShape.nudge(e, lane, 5, -StepShape.NUDGE_PULSES) }
        assertEquals(-59, StepShape.offsetOf(e, lane, 5), "the cell starts 59 pulses before the line")
        assertSame(e, StepShape.nudge(e, lane, 5, -1), "already at the stop")
        // Step 0 cannot go before the loop.
        val z = StepShape.nudge(lit(0), lane, 0, -StepShape.NUDGE_PULSES)
        assertEquals(lit(0), z)
        val c3 = lit(3)
        assertSame(c3, StepShape.nudge(c3, lane, 3, 0), "zero is a no-op")
    }

    @Test
    fun `early goes 30 then 29 pulses and stops, two presses in all`() {
        val one = StepShape.nudge(lit(5), lane, 5, -StepShape.NUDGE_PULSES)
        assertEquals(-30, StepShape.offsetOf(one, lane, 5))
        val two = StepShape.nudge(one, lane, 5, -StepShape.NUDGE_PULSES)
        assertEquals(-59, StepShape.offsetOf(two, lane, 5))
        assertSame(two, StepShape.nudge(two, lane, 5, -StepShape.NUDGE_PULSES), "a third press does nothing")
        assertEquals(5, GrooveEdit.cellOf(two.notes.single().timePulses))
    }

    @Test
    fun `a nudged roll moves as one and keeps its shape, stopping when its last hit reaches the edge`() {
        val r = StepShape.setRoll(lit(4), lane, 4, 2)
        val moved = StepShape.nudge(r, lane, 4, 90)
        assertEquals(listOf(60L, 180L), times(moved, 4).map { it - 4 * step }, "asked for 90, got what fits: the last hit stops on the edge")
        assertEquals(2, StepShape.rollOf(moved, lane, 4))
        assertTrue(times(moved, 4).all { GrooveEdit.cellOf(it) == 4 })
        val early = StepShape.nudge(r, lane, 4, -1000)
        assertEquals(-59, StepShape.offsetOf(early, lane, 4))
        assertEquals(listOf(120L), times(early, 4).zipWithNext { a, b -> b - a }, "the roll kept its spacing")
        assertEquals(r, StepShape.centre(early, lane, 4))
    }

    @Test
    fun `a x4 fills the cell to its end, so it cannot go late and goes at most 59 early, without a crash`() {
        val r = StepShape.setRoll(lit(4), lane, 4, 4)
        assertSame(r, StepShape.nudge(r, lane, 4, StepShape.NUDGE_PULSES), "no room late")
        val one = StepShape.nudge(r, lane, 4, -StepShape.NUDGE_PULSES)
        assertEquals(listOf(-30L, 30L, 90L, 150L), times(one, 4).map { it - 4 * step })
        val two = StepShape.nudge(one, lane, 4, -StepShape.NUDGE_PULSES)
        assertEquals(-59, StepShape.offsetOf(two, lane, 4))
        assertEquals(4, StepShape.rollOf(two, lane, 4))
        assertSame(two, StepShape.nudge(two, lane, 4, -StepShape.NUDGE_PULSES))
        assertEquals(r, StepShape.centre(two, lane, 4))
        // Rolling a step that was nudged late pulls the start back so the last hit stays in the cell.
        val late = StepShape.nudge(lit(4), lane, 4, 90)
        val rolled = StepShape.setRoll(late, lane, 4, 4)
        assertEquals(listOf(0L, 60L, 120L, 180L), times(rolled, 4).map { it - 4 * step })
    }

    @Test
    fun `a x4 roll is removed whole by a tap and takes a velocity level on every hit`() {
        val r = StepShape.setRoll(lit(6), lane, 6, 4)
        assertEquals(4, r.notes.size)
        assertTrue(GrooveEdit.toggleStep(r, lane, 6).notes.isEmpty())
        val soft = GrooveEdit.setStepVelocity(r, lane, 6, GrooveEdit.VelocityLevel.SOFT.velocity)
        assertEquals(setOf(GrooveEdit.VelocityLevel.SOFT.velocity), soft.notes.map { it.velocity }.toSet())
        assertEquals(4, soft.notes.size)
    }

    @Test
    fun `a hit 60 pulses before a step line belongs to the step before, 59 to this one`() {
        assertEquals(4, GrooveEdit.cellOf(5 * step - 60))
        assertEquals(5, GrooveEdit.cellOf(5 * step - 59))
        assertEquals(5, GrooveEdit.cellOf(5 * step + 180))
        assertEquals(6, GrooveEdit.cellOf(5 * step + 181))
        val r = StepShape.cellRange(lit(5), 5)
        assertEquals(5 * step - 59, r.first)
        assertEquals(5 * step + 180, r.last)
        assertEquals(5, GrooveEdit.cellOf(r.first))
        assertEquals(5, GrooveEdit.cellOf(r.last))
    }

    @Test
    fun `swing at 75 percent keeps an and step in its own cell, and a roll on it stays one unit`() {
        val push = Mpc3Clip.swingPush(75)
        assertEquals(120L, push)
        val swung = GrooveEdit.bakeSwing(lit(1, 5), 75)
        for (s in listOf(1, 5)) {
            assertEquals(listOf(s * step + push), times(swung, s))
            assertEquals(s, GrooveEdit.cellOf(s * step + push))
        }
        // A roll made on the swung step: the hits run from the swung position to the cell's end.
        val rolled = StepShape.setRoll(swung, lane, 5, 2)
        assertEquals(2, times(rolled, 5).size)
        assertTrue(times(rolled, 5).all { GrooveEdit.cellOf(it) == 5 })
        assertTrue(times(rolled, 4).isEmpty() && times(rolled, 6).isEmpty())
    }

    @Test
    fun `centre puts the earliest hit back on the step line`() {
        val c = StepShape.nudge(lit(7), lane, 7, -50)
        assertEquals(-50, StepShape.offsetOf(c, lane, 7))
        assertEquals(lit(7), StepShape.centre(c, lane, 7))
        val c7 = lit(7)
        assertSame(c7, StepShape.centre(c7, lane, 7), "already centred")
    }

    // ---- nudge and swing ----

    @Test
    fun `apply swing puts every nudge back on its step and folds a roll into one hit`() {
        val c = StepShape.nudge(lit(4, 5), lane, 4, 60)
        val rolled = StepShape.setRoll(c, lane, 5, 3)
        assertEquals(1, StepShape.rollsIn(rolled))
        val swung = GrooveEdit.bakeSwing(rolled, 62)
        val push = Mpc3Clip.swingPush(62)
        assertEquals(listOf(4 * step), times(swung, 4), "the nudge is gone, step 4 is straight")
        assertEquals(listOf(5 * step + push), times(swung, 5), "the roll is one hit, on the swung position")
        assertEquals(0, StepShape.rollsIn(swung))
    }

    @Test
    fun `a nudge after the swing is baked moves the step from where the swing put it, and stays in its cell`() {
        val swung = GrooveEdit.bakeSwing(lit(5), 66)
        val push = Mpc3Clip.swingPush(66).toInt()
        assertEquals(push, StepShape.offsetOf(swung, lane, 5))
        val early = StepShape.nudge(swung, lane, 5, -StepShape.NUDGE_PULSES)
        assertEquals(push - 30, StepShape.offsetOf(early, lane, 5))
        // A second apply of the same swing straightens it again, then swings: the nudge does not survive.
        assertEquals(swung, GrooveEdit.bakeSwing(early, 66))
        assertEquals(lit(5), StepShape.centre(swung, lane, 5), "CENTRE takes this step off the swing")
    }

    // ---- length ----

    @Test
    fun `length writes lengthPulses and touches nothing else`() {
        val c = lit(2, 3)
        for (choice in NoteLength.entries) {
            val w = StepShape.setLength(c, lane, 2, choice)
            assertEquals(choice.pulses, w.notes.first { GrooveEdit.cellOf(it.timePulses) == 2 }.lengthPulses)
            assertEquals(c.notes.first { GrooveEdit.cellOf(it.timePulses) == 3 }, w.notes.first { GrooveEdit.cellOf(it.timePulses) == 3 })
            assertEquals(c.notes.map { it.timePulses to it.velocity }, w.notes.map { it.timePulses to it.velocity })
        }
        assertEquals(NoteLength.STEP, StepShape.lengthOf(c, lane, 2), "a new step is a step long")
        assertEquals(NoteLength.TIE_4, StepShape.lengthOf(StepShape.setLength(c, lane, 2, NoteLength.TIE_4), lane, 2))
        assertSame(c, StepShape.setLength(c, lane, 2, NoteLength.STEP))
        assertSame(c, StepShape.setLength(c, lane, 9, NoteLength.HALF), "no hit, no change")
    }

    @Test
    fun `a length is cut at the end of the clip and at the next hit of a roll`() {
        val last = GrooveEdit.stepsInClip(lit(0)) - 1
        val w = StepShape.setLength(lit(last), lane, last, NoteLength.TIE_4)
        assertEquals(step, w.notes.single().lengthPulses, "the last step has a step left")
        val r = StepShape.setLength(StepShape.setRoll(lit(4), lane, 4, 4), lane, 4, NoteLength.TIE_2)
        assertEquals(setOf(60L), r.notes.map { it.lengthPulses }.toSet())
        assertEquals(null, StepShape.lengthOf(r, lane, 4), "a roll has no length of its own to show")
    }

    // ---- export ----

    @Test
    fun `a rolled, nudged, lengthened step exports to the xpj at the right pulses`() {
        var c = lit(4, 8)
        c = StepShape.setRoll(c, lane, 4, 4)
        c = StepShape.nudge(c, lane, 4, -30)
        c = StepShape.nudge(c, lane, 8, 30)
        c = StepShape.setLength(c, lane, 8, NoteLength.TIE_2)
        val tracks = listOf(Mpc3ProjectTrack.Drum(DrumProgram("Kit", listOf(Pad("Hat", 1000L))), clip = c))
        val root = Json.parse(Mpc3ProjectWriter().payloadText("T", tracks, tempoBpm = 120f)) as JsonValue.Obj
        val data = (root.entries["data"] as JsonValue.Obj).entries
        val seq = ((data["sequences"] as JsonValue.Arr).items[0] as JsonValue.Obj).entries["value"] as JsonValue.Obj
        val row = ((seq.entries["trackClipMaps"] as JsonValue.Arr).items[0] as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        val kit = (row.first { it["key"]!!.str() == "Kit" }["value"] as JsonValue.Obj).entries
        val events = (((kit["eventList"] as JsonValue.Obj).entries["events"]) as JsonValue.Arr).items.map { (it as JsonValue.Obj).entries }
        val got = events.map { e ->
            val n = (e["note"] as JsonValue.Obj).entries
            Triple((e["time"] as JsonValue.Num).value.toLong(), (n["length"] as JsonValue.Num).value.toLong(), (n["note"] as JsonValue.Num).value.toInt())
        }.sortedBy { it.first }
        val line4 = 4 * step
        assertEquals(
            listOf(
                Triple(line4 - 30, 60L, note), Triple(line4 + 30, 60L, note), Triple(line4 + 90, 60L, note), Triple(line4 + 150, 60L, note),
                Triple(8 * step + 30, 2 * step, note),
            ),
            got,
        )
    }

    // ---- names and the counter through the project ----

    @Test
    fun `pattern names and the number counter ride the project beat file`() {
        val kit = File(temp, "kit")
        val e0 = Mpc3Clip("Kit E", 1, listOf(Mpc3Note(note, 0, 0.9f)))
        val e2 = Mpc3Clip("Kit E3", 1, listOf(Mpc3Note(note, step, 0.9f)))
        // Program 2 was deleted once: the mark is past the highest held.
        GrooveStore.save(kit, listOf(e0, e2), names = mapOf(e0.name to "VERSE", e2.name to "CHORUS"), nextProg = 5)
        assertEquals(5, GrooveStore.loadNextProg(kit))
        val project = File(temp, "proj")
        ProjectStore.saveBeat(project, GrooveStore.load(kit), GrooveStore.loadLanePads(kit), GrooveStore.loadNames(kit), GrooveStore.loadNextProg(kit))
        assertEquals(mapOf(e0.name to "VERSE", e2.name to "CHORUS"), ProjectStore.beatNames(project))
        assertEquals(5, ProjectStore.beatNextProg(project))
        assertEquals(listOf(e0, e2), ProjectStore.beat(project))
        // An older caller keeps what the file holds; a rename in the kit replaces; a pattern that is gone loses its name.
        ProjectStore.saveBeat(project, GrooveStore.load(kit))
        assertEquals(mapOf(e0.name to "VERSE", e2.name to "CHORUS"), ProjectStore.beatNames(project))
        ProjectStore.saveBeat(project, listOf(e0), names = mapOf(e0.name to "INTRO"), nextProg = 5)
        assertEquals(mapOf(e0.name to "INTRO"), ProjectStore.beatNames(project))
        assertEquals(5, ProjectStore.beatNextProg(project), "the mark never goes down")
        ProjectStore.saveBeat(project, listOf(e0), nextProg = 2)
        assertEquals(5, ProjectStore.beatNextProg(project))
    }
}
