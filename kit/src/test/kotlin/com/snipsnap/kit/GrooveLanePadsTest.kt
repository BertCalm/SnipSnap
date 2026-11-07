package com.snipsnap.kit

import com.snipsnap.kit.GrooveEdit.Lane
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BEAT step 1: lanes that play any pad, and a velocity per step. */
class GrooveLanePadsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("groove-lanes").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kitDir() = File(temp, "kit-${java.util.UUID.randomUUID()}")

    private val e = Mpc3Clip("Kit E", 1, listOf(Mpc3Note(Mpc3Note.noteFor(1), 0L, 0.9f)))
    private val base = Mpc3Clip("Kit", 1, emptyList())

    @Test
    fun `the default map is the old fixed one and every lane note is where it was`() {
        assertEquals(listOf(1, 2, 6, 3, 4, 9, 12), GrooveEdit.DEFAULT_LANE_PADS)
        assertTrue(GrooveEdit.isValidLanePads(GrooveEdit.DEFAULT_LANE_PADS))
        for (lane in Lane.entries) {
            assertEquals(Mpc3Note.noteFor(GrooveEdit.LANE_SLOT.getValue(lane)), GrooveEdit.noteFor(lane))
        }
    }

    @Test
    fun `setLanePad moves a lane to a free pad and leaves the others`() {
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        assertEquals(listOf(16, 2, 6, 3, 4, 9, 12), pads)
        assertTrue(GrooveEdit.isValidLanePads(pads))
    }

    @Test
    fun `setLanePad onto another lane's pad swaps the two so no pad is on two rows`() {
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 2)
        assertEquals(listOf(2, 1, 6, 3, 4, 9, 12), pads)
        assertTrue(GrooveEdit.isValidLanePads(pads))
        assertSame(GrooveEdit.DEFAULT_LANE_PADS, GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 1))
    }

    @Test
    fun `setLanePad refuses a pad that does not exist`() {
        assertFailsWith<IllegalArgumentException> { GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 17) }
        assertFailsWith<IllegalArgumentException> { GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 0) }
    }

    @Test
    fun `reassigning a lane keeps every note and the old pad's notes become off-lane`() {
        val clip = Mpc3Clip(
            "Kit E", 1,
            listOf(
                Mpc3Note(Mpc3Note.noteFor(1), 0L, 0.9f),
                Mpc3Note(Mpc3Note.noteFor(2), 960L, 0.9f),
            ),
        )
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        // Nothing in the clip moved: reassigning is a change to the map alone.
        assertEquals(2, clip.notes.size)
        val off = GrooveEdit.offLaneNotes(clip, pads)
        assertEquals(listOf(Mpc3Note.noteFor(1)), off.map { it.note }, "the kick pad's note is now on no lane, and is still there")
        assertTrue(GrooveEdit.offLaneNotes(clip).isEmpty(), "on the default map every one of these is on a lane")
    }

    @Test
    fun `toggle and clear follow the lane map and leave off-lane notes alone`() {
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        val on = GrooveEdit.toggleStep(e, Lane.KICK, 4, pads)
        assertTrue(on.notes.any { it.note == Mpc3Note.noteFor(16) && it.timePulses == 4 * GrooveEdit.STEP_PULSES })
        // The old kick pad's note at step 0 is untouched by that toggle...
        assertTrue(on.notes.any { it.note == Mpc3Note.noteFor(1) && it.timePulses == 0L })
        // ...and CLEAR BAR wipes the lanes' pads only, so it survives that too.
        val cleared = GrooveEdit.clearBar(on, 0, pads)
        assertEquals(listOf(Mpc3Note.noteFor(1)), cleared.notes.map { it.note })
    }

    @Test
    fun `the lane map round-trips through groove json beside the clips`() {
        val dir = kitDir()
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.PERC, 15)
        GrooveStore.save(dir, listOf(base, e), mapOf(e.name to pads))
        assertEquals(mapOf(e.name to pads), GrooveStore.loadLanePads(dir))
        assertEquals(listOf(base, e), GrooveStore.load(dir), "the clips read as they always did")
        assertTrue(File(dir, GrooveStore.FILE_NAME).readText().contains("\"${GrooveStore.LANE_PADS_KEY}\""))
    }

    @Test
    fun `a file written before lane maps loads with none and so the default lanes`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base, e))
        assertFalse(File(dir, GrooveStore.FILE_NAME).readText().contains(GrooveStore.LANE_PADS_KEY), "no key until someone reassigns")
        assertTrue(GrooveStore.loadLanePads(dir).isEmpty())
        assertEquals(2, GrooveStore.load(dir).size)
    }

    @Test
    fun `adding the key needs no version bump`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base, e), mapOf(e.name to GrooveEdit.DEFAULT_LANE_PADS))
        val version = com.snipsnap.json.Json.parse(File(dir, GrooveStore.FILE_NAME).readText()).obj().getValue("version").int()
        assertEquals(GrooveStore.VERSION, version)
        assertEquals(2, version)
    }

    @Test
    fun `saving clips again keeps the lane maps of the clips still there and drops the rest`() {
        val dir = kitDir()
        val e2 = e.copy(name = "Kit E2")
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        GrooveStore.save(dir, listOf(base, e, e2), mapOf(e.name to pads, e2.name to pads))
        // The editor's own autosave path knows nothing about lane maps.
        GrooveEdit.save(dir, e.copy(notes = emptyList()))
        assertEquals(setOf(e.name, e2.name), GrooveStore.loadLanePads(dir).keys)
        // Discarding E2 takes its map with it: slot reuse starts from the default.
        GrooveEdit.deleteProgE(dir) { it == 1 }
        assertEquals(setOf(e.name), GrooveStore.loadLanePads(dir).keys)
    }

    @Test
    fun `replacing a program with a fresh fork resets its lane map`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base, e))
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        GrooveStore.saveLanePads(dir, mapOf(e.name to pads))
        GrooveEdit.fork(dir, base)
        assertEquals(pads, GrooveStore.loadLanePads(dir)[e.name], "re-entering the editor keeps the lanes")
        GrooveEdit.fork(dir, base, replace = true)
        assertNull(GrooveStore.loadLanePads(dir)[e.name], "REPLACE starts from the default lanes")
    }

    @Test
    fun `saveLanePads replaces only the maps`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base, e))
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.TOM, 8)
        GrooveStore.saveLanePads(dir, mapOf(e.name to pads))
        assertEquals(pads, GrooveStore.loadLanePads(dir)[e.name])
        assertEquals(listOf(base, e), GrooveStore.load(dir))
    }

    @Test
    fun `a damaged lane map falls back to the default instead of refusing the file`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base, e))
        val file = File(dir, GrooveStore.FILE_NAME)
        file.writeText(file.readText().trimEnd().removeSuffix("}") + ",\"lanePads\":{\"Kit E\":[1,1,2],\"Other\":[1,2,3,4,5,6,99]}}")
        assertTrue(GrooveStore.loadLanePads(dir).isEmpty())
        assertEquals(2, GrooveStore.load(dir).size)
    }

    @Test
    fun `velocity is set at a step and read back, and touches nothing else`() {
        val clip = GrooveEdit.toggleStep(GrooveEdit.toggleStep(e, Lane.SNARE, 4), Lane.SNARE, 8)
        val soft = GrooveEdit.setStepVelocity(clip, Lane.SNARE, 4, GrooveEdit.VelocityLevel.SOFT.velocity)
        assertEquals(0.35f, GrooveEdit.velocityAt(soft, Lane.SNARE, 4))
        assertEquals(0.9f, GrooveEdit.velocityAt(soft, Lane.SNARE, 8), "the neighbour keeps its own")
        assertEquals(clip.notes.size, soft.notes.size)
        assertEquals(clip.notes.map { it.timePulses to it.lengthPulses }, soft.notes.map { it.timePulses to it.lengthPulses })
    }

    @Test
    fun `setting a velocity where there is no note changes nothing`() {
        assertSame(e, GrooveEdit.setStepVelocity(e, Lane.SNARE, 4, 1f))
        assertNull(GrooveEdit.velocityAt(e, Lane.SNARE, 4))
    }

    @Test
    fun `velocity follows the lane map`() {
        val pads = GrooveEdit.setLanePad(GrooveEdit.DEFAULT_LANE_PADS, Lane.KICK, 16)
        val clip = GrooveEdit.toggleStep(e, Lane.KICK, 2, pads)
        val set = GrooveEdit.setStepVelocity(clip, Lane.KICK, 2, 1f, pads)
        assertEquals(1f, GrooveEdit.velocityAt(set, Lane.KICK, 2, pads))
        assertNull(GrooveEdit.velocityAt(set, Lane.KICK, 2), "the old kick pad has nothing at step 2")
    }

    @Test
    fun `the four levels name what the editor and a take already write`() {
        assertEquals(GrooveEdit.VelocityLevel.HARD, GrooveEdit.levelOf(GrooveEdit.VELOCITY_DEFAULT))
        assertEquals(GrooveEdit.VelocityLevel.MED, GrooveEdit.levelOf(GrooveEdit.VELOCITY_HAT_CLOSED))
        assertEquals(GrooveEdit.VelocityLevel.SOFT, GrooveEdit.levelOf(GrooveVariations.GHOST_VELOCITY_CEILING))
        assertEquals(GrooveEdit.VelocityLevel.ACCENT, GrooveEdit.levelOf(1f))
        for (level in GrooveEdit.VelocityLevel.entries) assertEquals(level, GrooveEdit.levelOf(level.velocity))
    }

    @Test
    fun `velocity survives a save and load`() {
        val dir = kitDir()
        GrooveStore.save(dir, listOf(base))
        val clip = GrooveEdit.setStepVelocity(GrooveEdit.toggleStep(e, Lane.SNARE, 4), Lane.SNARE, 4, 1f)
        GrooveEdit.save(dir, clip)
        assertEquals(1f, GrooveEdit.velocityAt(GrooveEdit.load(dir)!!, Lane.SNARE, 4))
    }
}
