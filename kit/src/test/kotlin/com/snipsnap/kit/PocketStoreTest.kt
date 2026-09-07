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
}
