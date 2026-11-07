package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pattern names: a display name per program of yours kept apart from the
 * `" E"` disk marker, a number that is never handed out twice, and the cap
 * at thirty-two. All pure `:kit`, on a temp kit directory.
 */
class PatternNamesTest {
    private val temp = File.createTempFile("pattern-names", "").also { it.delete(); it.mkdirs() }

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private val base = Mpc3Clip("Break", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 4 * 240L, 0.8f)))

    private fun kit(): File {
        val dir = File(temp, "kit-${java.util.UUID.randomUUID()}")
        GrooveStore.save(dir, GrooveVariations.standard(base))
        return dir
    }

    private fun idsOf(dir: File) = GrooveEdit.loadAll(dir).map { GrooveEdit.progIndexOf(it) }

    // ---------- the display name ----------

    @Test
    fun `a name round-trips through groove json and the disk name does not change`() {
        val dir = kit()
        val e = GrooveEdit.fork(dir, base)
        assertEquals(GrooveEdit.NameCheck.Ok("VERSE"), GrooveEdit.rename(dir, e, "verse"))
        assertEquals(mapOf("Break E" to "VERSE"), GrooveStore.loadNames(dir))
        assertEquals(listOf("Break E"), GrooveEdit.loadAll(dir).map { it.name }, "the marker is untouched")
        assertEquals("VERSE", GrooveEdit.labelOf("Break E", GrooveStore.loadNames(dir)))
        assertEquals(mapOf(0 to "VERSE"), GrooveEdit.namesById(dir))
    }

    @Test
    fun `an older file with no names or counter loads with the default labels`() {
        val dir = File(temp, "old").also { it.mkdirs() }
        val clip = """{"name":"Break E","bars":1,"notes":[]}"""
        File(dir, GrooveStore.FILE_NAME).writeText("""{"version":2,"clips":[{"name":"Break","bars":1,"notes":[]},$clip,{"name":"Break E2","bars":1,"notes":[]}]}""")
        assertEquals(3, GrooveStore.load(dir).size)
        assertEquals(emptyMap(), GrooveStore.loadNames(dir))
        assertEquals(0, GrooveStore.loadNextProg(dir))
        assertEquals("YOURS 1", GrooveEdit.labelOf("Break E", emptyMap()))
        assertEquals("YOURS 2", GrooveEdit.labelOf("Break E2", emptyMap()))
        assertEquals(2, GrooveEdit.nextProgramId(dir), "no counter: one past the highest it holds")
        assertNull(GrooveEdit.labelOf("Break", emptyMap()), "the captured clip is not a program of yours")
    }

    @Test
    fun `an older build's file still reads and a bad name entry is skipped, not fatal`() {
        val dir = File(temp, "odd").also { it.mkdirs() }
        File(dir, GrooveStore.FILE_NAME).writeText(
            """{"version":2,"clips":[{"name":"Break","bars":1,"notes":[]},{"name":"Break E","bars":1,"notes":[]}],"names":{"Break E":7,"Gone E9":"X"},"nextProg":"nope"}""",
        )
        assertEquals(emptyMap(), GrooveStore.loadNames(dir).filterKeys { it == "Break E" })
        assertEquals(0, GrooveStore.loadNextProg(dir))
    }

    @Test
    fun `names ride along every other write and go with the program that is deleted`() {
        val dir = kit()
        val e = GrooveEdit.fork(dir, base)
        GrooveEdit.rename(dir, e, "drop")
        val two = GrooveEdit.forkNew(dir, base)!!
        GrooveEdit.rename(dir, two, "bridge")
        // An edit saved, a take landed and a take undone all rewrite the sidecar.
        GrooveEdit.save(dir, e.copy(notes = e.notes + Mpc3Note(42, 8 * 240L, 0.5f)))
        LiveRecord.land(dir, base)

        LiveRecord.undo(dir, base)
        assertEquals(mapOf("Break E" to "DROP", "Break E2" to "BRIDGE"), GrooveStore.loadNames(dir))
        assertEquals(2, GrooveStore.loadNextProg(dir))

        assertTrue(GrooveEdit.deleteProgE(dir) { it == 1 })
        assertEquals(mapOf("Break E" to "DROP"), GrooveStore.loadNames(dir), "a deleted pattern takes its name with it")
        assertEquals(2, GrooveStore.loadNextProg(dir), "and the counter does not go back")
    }

    @Test
    fun `undoing a take with only programs left keeps their names and counter`() {
        val dir = File(temp, "only-yours")
        GrooveStore.save(dir, listOf(Mpc3Clip("Break E", 1, base.notes), Mpc3Clip("Break E2", 1, base.notes)))
        GrooveEdit.rename(dir, GrooveEdit.loadAll(dir)[1], "bridge")
        LiveRecord.undo(dir, null)
        assertEquals(mapOf("Break E2" to "BRIDGE"), GrooveStore.loadNames(dir))
        assertEquals(2, GrooveStore.loadNextProg(dir))
    }

    @Test
    fun `a name is tidied, capped, and refused when it is another pattern's or one of SONG's own words`() {
        assertEquals("VERSE 2", GrooveEdit.cleanName("  verse   2 "))
        assertEquals("AB-B!", GrooveEdit.cleanName("a*b-b!"))
        assertEquals("ABCDEFGHIJKL", GrooveEdit.cleanName("abcdefghijklmnop"))
        assertEquals("", GrooveEdit.cleanName("   "))

        val dir = kit()
        val one = GrooveEdit.fork(dir, base)
        val two = GrooveEdit.forkNew(dir, base)!!
        GrooveEdit.rename(dir, one, "verse")
        val names = GrooveStore.loadNames(dir)
        val programs = GrooveEdit.loadAll(dir).map { it.name }
        assertEquals(GrooveEdit.NameCheck.Taken, GrooveEdit.checkName("Verse", two.name, programs, names))
        assertEquals(GrooveEdit.NameCheck.Taken, GrooveEdit.checkName("yours 1", two.name, programs, names), "a default label that is not its own")
        assertEquals(GrooveEdit.NameCheck.Taken, GrooveEdit.checkName("yours 7", two.name, programs, names))
        assertEquals(GrooveEdit.NameCheck.Taken, GrooveEdit.checkName("captured", two.name, programs, names), "a SONG clip word")
        assertEquals(GrooveEdit.NameCheck.Ok("CHORUS"), GrooveEdit.checkName("chorus", two.name, programs, names))
        assertEquals(GrooveEdit.NameCheck.Ok("VERSE"), GrooveEdit.checkName("verse", one.name, programs, names), "its own name is not taken from itself")
        assertEquals(GrooveEdit.NameCheck.Default, GrooveEdit.checkName("", two.name, programs, names))
        assertEquals(GrooveEdit.NameCheck.Default, GrooveEdit.checkName("yours 2", two.name, programs, names), "its own default label means no name")

        assertEquals(GrooveEdit.NameCheck.Taken, GrooveEdit.rename(dir, two, "verse"))
        assertEquals(mapOf("Break E" to "VERSE"), GrooveStore.loadNames(dir), "a refusal writes nothing")
        assertEquals(GrooveEdit.NameCheck.Default, GrooveEdit.rename(dir, one, ""))
        assertEquals(emptyMap(), GrooveStore.loadNames(dir), "empty clears it")
    }

    // ---------- a number is never reused ----------

    @Test
    fun `a deleted number is never handed to a different pattern`() {
        val dir = kit()
        GrooveEdit.fork(dir, base)
        GrooveEdit.forkNew(dir, base)
        GrooveEdit.forkNew(dir, base)
        assertEquals(listOf(0, 1, 2), idsOf(dir))

        // Delete the HIGHEST: the old lowest-hole rule would have handed E3 straight back.
        assertTrue(GrooveEdit.deleteProgE(dir) { it == 2 })
        assertEquals(3, GrooveEdit.nextProgramId(dir))
        assertEquals("Break E4", GrooveEdit.forkNew(dir, base)!!.name)
        assertEquals(listOf(0, 1, 3), idsOf(dir))

        // EDIT STEPS on the empty slot asks for index 2 by position and gets a new one.
        assertTrue(GrooveEdit.deleteProgE(dir) { true })
        val again = GrooveEdit.fork(dir, base, index = 0)
        assertEquals("Break E5", again.name, "E, E2 and E4 were all given out once")
    }

    @Test
    fun `a first program on a fresh kit is still E`() {
        val dir = kit()
        assertEquals("Break E", GrooveEdit.fork(dir, base).name)
        assertEquals(1, GrooveStore.loadNextProg(dir))
    }

    @Test
    fun `replacing the one on screen keeps its number`() {
        val dir = kit()
        GrooveEdit.fork(dir, base)
        GrooveEdit.forkNew(dir, base)
        assertEquals("Break E2", GrooveEdit.fork(dir, base, replace = true, index = 1).name)
        assertEquals(listOf(0, 1), idsOf(dir))
    }

    // ---------- thirty-two ----------

    @Test
    fun `a kit holds thirty-two and the numbers can climb past that`() {
        assertEquals(32, GrooveEdit.MAX_USER_PROGRAMS)
        val dir = kit()
        repeat(32) { assertNotNull(GrooveEdit.forkNew(dir, base), "pattern ${it + 1}") }
        assertEquals(32, GrooveEdit.loadAll(dir).size)
        assertNull(GrooveEdit.forkNew(dir, base), "the thirty-third is refused")
        assertNull(GrooveEdit.nextProgramId(dir))

        assertTrue(GrooveEdit.deleteProgE(dir) { it == 5 })
        val reborn = GrooveEdit.forkNew(dir, base)!!
        assertEquals("Break E33", reborn.name, "room again, but under a number nobody has used")
        assertEquals(32, GrooveEdit.loadAll(dir).size)
        assertEquals(32, GrooveEdit.progIndexOf(reborn))
        // The list is in number order whatever order the file holds.
        assertEquals(idsOf(dir), idsOf(dir).sortedBy { it })
    }

    @Test
    fun `thirty-two programs and the base fit an xpj in one fewer than all of them, programs first`() {
        val dir = kit()
        repeat(32) { GrooveEdit.forkNew(dir, base) }
        val stored = GrooveStore.load(dir)
        assertEquals(36, stored.size, "base, three derived and thirty-two of yours")
        val cut = GrooveEdit.forSequences(stored, 32)
        assertEquals(32, cut.size)
        assertEquals("Break", cut.first().name, "the base leads")
        assertEquals(31, cut.count { GrooveEdit.isProgE(it) }, "yours displace the derived clips")
        assertTrue(cut.none { !GrooveEdit.isProgE(it) && it.name != "Break" }, "no derived variation outranks a pattern of yours")

        val few = GrooveEdit.forSequences(GrooveStore.load(kit()), 32)
        assertEquals(GrooveStore.load(kit()).map { it.name }, few.map { it.name }, "a kit with room is untouched")
    }

    @Test
    fun `what an xpj leaves out is named, programs by their own names`() {
        val dir = kit()
        repeat(32) { GrooveEdit.forkNew(dir, base) }
        val last = GrooveEdit.loadAll(dir).last()
        GrooveEdit.rename(dir, last, "outro")
        val stored = GrooveStore.load(dir)
        val kept = GrooveEdit.forSequences(stored, 32)
        val out = GrooveEdit.leftOut(stored, kept, GrooveStore.loadNames(dir))
        assertEquals(4, out.size, "the three derived variations and the last pattern")
        assertTrue("OUTRO" in out, out.toString())
        assertTrue(GrooveEdit.leftOutMessage(out).contains("left out: "), GrooveEdit.leftOutMessage(out))
        assertEquals(emptyList(), GrooveEdit.leftOut(GrooveStore.load(kit()), GrooveStore.load(kit()), emptyMap()))
    }
}
