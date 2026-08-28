package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.KitMerge
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineageTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("lineage").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kit(name: String, source: Map<String, String>): File {
        val dir = File(temp, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.update(1) { it.copy(source = source) }
        m.save()
        return dir
    }

    @Test
    fun `a resample chain walks to its dug origin in order`() {
        kit("Origin", mapOf("song" to "Track 07.wav", "at" to "1:32", "file" to "Track 07.wav"))
        kit("Origin Gen 2", mapOf("resampledFrom" to "Origin", "generation" to "2", "song" to "Track 07.wav"))
        val gen3 = kit(
            "Origin Gen 3",
            mapOf("resampledFrom" to "Origin Gen 2", "generation" to "3", "song" to "Track 07.wav"),
        )

        val text = Lineage.render(Lineage.trace(gen3))
        val lines = text.trimEnd().split('\n')
        assertEquals(
            listOf(
                "Origin Gen 3",
                "`- resampled from Origin Gen 2",
                "   `- resampled from Origin",
                "      `- dug from Track 07.wav at 1:32",
            ),
            lines,
            "the full chain, in order, origins only at the root",
        )
        assertEquals(text, Lineage.render(Lineage.trace(gen3)), "deterministic")
    }

    @Test
    fun `a merged kit shows both parents and their own roots`() {
        val aDir = kit("Alpha", mapOf("file" to "break.wav"))
        val bDir = kit("Bravo", emptyMap())
        KitMerge.merge(aDir, bDir, File(temp, "Alpha AB"))

        val text = Lineage.render(Lineage.trace(File(temp, "Alpha AB")))
        assertContains(text, "merged from Alpha")
        assertContains(text, "merged from Bravo")
        assertContains(text, "chopped from break.wav", message = "Alpha's own origin rides under it")
        assertContains(text, "made from scratch", message = "Bravo owns up to having no story")
    }

    @Test
    fun `a parent that left the crate is shown honestly and a png renders`() {
        val orphan = kit("Orphan Gen 2", mapOf("resampledFrom" to "Long Gone", "generation" to "2"))
        val tree = Lineage.trace(orphan)
        assertContains(Lineage.render(tree), "resampled from Long Gone (not in the crate)")

        val png = Lineage.renderPng(tree, File(temp, "card.png"))
        assertTrue(png.isFile && png.length() > 0, "the card lands on disk")
        val img = javax.imageio.ImageIO.read(png)
        assertEquals(900, img.width)
    }
}
