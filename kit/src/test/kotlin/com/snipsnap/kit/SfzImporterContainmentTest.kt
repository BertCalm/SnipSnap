package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A sample reference that spells clean but resolves through a symlink
 * out of the .sfz's own folder must not be copied in: containment is
 * canonical, not textual.
 */
class SfzImporterContainmentTest {

    private val temp: File = Files.createTempDirectory("sfz-link").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `a symlinked sample outside the folder is skipped, not copied`() {
        val outside = File(temp, "outside")
        val inside = File(temp, "inside")
        outside.mkdirs(); inside.mkdirs()
        WavWriter.write(File(outside, "secret.wav"), Snip(FloatArray(441) { 0.4f }, 1, 44_100))
        Files.createSymbolicLink(File(inside, "linked.wav").toPath(), File(outside, "secret.wav").toPath())
        val sfz = File(inside, "linked.sfz")
        sfz.writeText("<region> sample=linked.wav key=36\n")

        // The only region escapes, so the import refuses outright and
        // names the sample — nothing lands, nothing is copied.
        val e = assertFailsWith<java.io.IOException> { SfzImporter.import(sfz, File(temp, "kits")) }
        assertTrue(e.message!!.contains("linked.wav"), "the escaped sample is named: ${e.message}")
        assertTrue(
            File(temp, "kits").walkTopDown().none { it.name == "linked.wav" },
            "no copy of the escaped sample may exist under the kits root",
        )
    }
}
