package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BEAT step 4's persistence: the note's `chance` in groove.json, its version, and the variations that carry it. */
class GrooveChanceTest {

    private val dir: File = java.nio.file.Files.createTempDirectory("groove-chance").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val plain = Mpc3Clip("Kit A", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 960, 0.8f)))
    private val chancy = Mpc3Clip("Kit B", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 960, 0.8f, chance = 50)))

    private fun versionOnDisk(): Int =
        Json.parse(File(dir, GrooveStore.FILE_NAME).readText()).obj().getValue("version").int()

    @Test
    fun `a file with no chance is still version 2 and has no chance key`() {
        GrooveStore.save(dir, listOf(plain))
        assertEquals(2, versionOnDisk())
        assertFalse(File(dir, GrooveStore.FILE_NAME).readText().contains("chance"))
    }

    @Test
    fun `a file with a chance is version 3 and reads back with its chances`() {
        GrooveStore.save(dir, listOf(plain, chancy))
        assertEquals(3, versionOnDisk())
        val text = File(dir, GrooveStore.FILE_NAME).readText()
        assertEquals(1, Regex("\"chance\"").findAll(text).count(), "only the note under 100 writes the key")
        assertEquals(listOf(plain, chancy), GrooveStore.load(dir))
    }

    @Test
    fun `a version 2 file reads every note as 100`() {
        GrooveStore.save(dir, listOf(plain))
        assertTrue(GrooveStore.load(dir).single().notes.all { it.chance == Mpc3Note.ALWAYS })
    }

    @Test
    fun `a version 4 file is refused`() {
        GrooveStore.save(dir, listOf(plain))
        val f = File(dir, GrooveStore.FILE_NAME)
        f.writeText(f.readText().replace("\"version\": 2", "\"version\": 4").replace("\"version\":2", "\"version\":4"))
        assertFailsWith<Exception> { GrooveStore.load(dir) }
    }

    @Test
    fun `the answer file does not need a new version for a clip with no chance`() {
        val json = Json.write(GrooveStore.clipToJson(plain))
        assertFalse(json.contains("chance"))
    }

    @Test
    fun `the variations carry the source's chance`() {
        val source = Mpc3Clip(
            "Break Groove", 1,
            listOf(
                Mpc3Note(36, 0, 1.0f, 240, chance = 50),
                Mpc3Note(42, 503, 0.30f, 120, chance = 25),
                Mpc3Note(38, 960, 0.85f, 240, chance = 75),
                Mpc3Note(36, 1920, 0.9f, 240, chance = 50),
            ),
        )
        val made = GrooveVariations.standard(source, swingPercent = 60) + GrooveVariations.sparse(source) + GrooveVariations.swing(source, 58)
        assertTrue(made.isNotEmpty())
        for (clip in made) {
            for (n in clip.notes) {
                val from = source.notes.filter { it.note == n.note }.minBy { kotlin.math.abs(it.timePulses - n.timePulses) }
                assertEquals(from.chance, n.chance, "${clip.name} kept ${n.note}'s chance")
            }
        }
        assertTrue(made.any { c -> c.notes.any { it.chance < 100 } })
    }
}
