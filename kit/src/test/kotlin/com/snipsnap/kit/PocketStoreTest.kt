package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PocketStoreTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("pocket").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val s16 = Mpc3Clip.PULSES_PER_16TH

    /** A donor leaning: offbeats late by 60, downbeats accented. */
    private fun donor(): Mpc3Clip = Mpc3Clip(
        "Donor", 1,
        (0 until 16).filter { it % 4 != 3 }.map { i ->
            Mpc3Note(42, i * s16 + if (i % 2 == 1) 60L else 0L, if (i % 4 == 0) 1.0f else 0.6f)
        },
    )

    @Test
    fun `a pocket round-trips - nulls, offsets and accents intact`() {
        val template = GrooveFeel.extract(donor())
        assertTrue(template.offsets.any { it == null }, "the donor skips positions, so nulls are in play")

        val file = File(temp, "swing.pocket")
        PocketStore.save(PocketStore.Pocket("Donor", template), file)
        val back = PocketStore.read(file)
        assertEquals("Donor", back.name)
        assertEquals(template.offsets, back.template.offsets)
        assertEquals(template.accents, back.template.accents)

        // The exit test's real claim: the read-back pocket moves a clip
        // exactly the way the original template would.
        val target = Mpc3Clip("Straight", 1, (0 until 16).map { Mpc3Note(36, it * s16, 0.8f) })
        assertEquals(GrooveFeel.apply(template, target), GrooveFeel.apply(back.template, target))
    }

    @Test
    fun `an unknown version is refused and torn files throw, not lie`() {
        val file = File(temp, "bad.pocket")
        file.writeText("""{"version": 99, "name": "X", "offsets": [], "accents": []}""")
        assertFailsWith<com.snipsnap.json.JsonException> { PocketStore.read(file) }
        file.writeText("not json at all")
        assertFailsWith<Exception> { PocketStore.read(file) }
        assertFailsWith<java.io.IOException> { PocketStore.read(File(temp, "missing.pocket")) }
    }

    @Test
    fun `a v2 pocket round-trips its lane layer`() {
        val template = GrooveFeel.Template(
            offsets = List(GrooveFeel.POSITIONS) { 0L },
            accents = List(GrooveFeel.POSITIONS) { null },
            laneOffsets = mapOf(GrooveEdit.Lane.SNARE to 18L, GrooveEdit.Lane.HAT_CLOSED to -6L),
            laneAccents = mapOf(GrooveEdit.Lane.SNARE to 1.2f),
        )
        val file = PocketStore.save(PocketStore.Pocket("dilla", template), File(temp, "x.pocket"))
        val back = PocketStore.read(file)
        assertEquals(18L, back.template.laneOffsets[GrooveEdit.Lane.SNARE], "the snare's drag survives the file")
        assertEquals(-6L, back.template.laneOffsets[GrooveEdit.Lane.HAT_CLOSED], "and so does the hat's push")
        assertEquals(1.2f, back.template.laneAccents[GrooveEdit.Lane.SNARE], "lane accents round-trip too")
    }

    @Test
    fun `a v1 pocket still reads, with an empty lane layer`() {
        val file = File(temp, "old.pocket")
        file.writeText(
            """{"version":1,"name":"old","offsets":[${List(16) { "0" }.joinToString(",")}],""" +
                """"accents":[${List(16) { "null" }.joinToString(",")}]}""",
        )
        val back = PocketStore.read(file)
        assertEquals("old", back.name, "a v1 file is still readable")
        assertTrue(back.template.laneOffsets.isEmpty(), "v1 carried no lane layer, so it reads as none")
        assertTrue(back.template.laneAccents.isEmpty(), "and no lane accents")
    }

    @Test
    fun `an unknown version is still refused`() {
        val file = File(temp, "future.pocket")
        file.writeText("""{"version":99,"name":"x","offsets":[],"accents":[]}""")
        assertFailsWith<com.snipsnap.json.JsonException> { PocketStore.read(file) }
    }

    @Test
    fun `an unrecognized lane name in the lane layer is dropped, not thrown`() {
        val file = File(temp, "unknown-lane.pocket")
        file.writeText(
            """{"version":2,"name":"x","offsets":[${List(16) { "0" }.joinToString(",")}],""" +
                """"accents":[${List(16) { "null" }.joinToString(",")}],""" +
                """"laneOffsets":{"SNARE":5,"COWBELL":9},""" +
                """"laneAccents":{"SNARE":1.1,"COWBELL":2.0}}""",
        )
        val back = PocketStore.read(file)
        assertEquals(5L, back.template.laneOffsets[GrooveEdit.Lane.SNARE], "the known lane survives")
        assertEquals(1, back.template.laneOffsets.size, "the unrecognized lane name is dropped, not thrown")
        assertEquals(1.1f, back.template.laneAccents[GrooveEdit.Lane.SNARE], "the known lane's accent survives too")
        assertEquals(1, back.template.laneAccents.size, "same drop, same reason, for lane accents")
    }

    @Test
    fun `version 0 is refused - below the readable set, not just above it`() {
        // READABLE is an explicit membership set ({1, 2}), not a relaxed
        // upper-bound check like `version <= VERSION`. Every other version
        // test here uses 99, which a relaxed `<= VERSION` check would also
        // reject - so none of them can tell a real set from a sloppy bound.
        // version 0 is the case that only a genuine set check refuses: it's
        // <= VERSION (2), so a relaxed check would let it through and read
        // a pocket this build never agreed to understand.
        val file = File(temp, "zero.pocket")
        file.writeText("""{"version":0,"name":"x","offsets":[],"accents":[]}""")
        assertFailsWith<com.snipsnap.json.JsonException> { PocketStore.read(file) }
    }
}
