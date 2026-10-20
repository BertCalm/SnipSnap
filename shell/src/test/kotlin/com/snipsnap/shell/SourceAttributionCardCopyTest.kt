package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.Severity
import com.snipsnap.kit.SourceAttribution
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceAttributionCardCopyTest {
    @Test
    fun `a program folder reaches the card as its program samples and source notice together`() {
        val temp = Files.createTempDirectory("credited-program-card-").toFile()
        try {
            val input = File(temp, "input").apply { mkdirs() }
            val sample = "A01_Wordless.wav"
            WavWriter.write(File(input, sample), Snip(FloatArray(4_410) { if (it % 2 == 0) .1f else -.1f }, 1, WavWriter.MPC_SAMPLE_RATE))
            val credit = mapOf("attribution" to "VocalSet — Julia Wilkins, Prem Seetharaman, Alison Wahl, Bryan Pardo (2018).",
                "license" to "CC-BY-4.0", "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
                "sourceUrl" to "https://doi.org/10.5281/zenodo.1193957", "modifications" to "Rendered wordless source arrangement.")
            val kit = Kit("Credited wordless kit", listOf(KitPad(1, sample, "WORDLESS", source = credit)))
            val exports = File(temp, "exports").apply { mkdirs() }
            File(exports, "unrelated-old-export.wav").writeBytes(byteArrayOf(1, 2, 3))
            val outcome = Exporters.export(ExportFormat.PROGRAM_FOLDER, kit, input, exports)
            assertTrue(outcome.primary.isDirectory, "the app must hand the whole program folder to CardCopy")
            val plan = CardCopy.plan(listOfNotNull(outcome.primary, outcome.companion))
            assertEquals(setOf(kit.name), plan.map { it.segments.first() }.toSet())
            assertEquals(setOf("${kit.name}.xpm", sample, SourceAttribution.fileName(kit), com.snipsnap.kit.KitStore.FILE_NAME),
                plan.filterNot { it.isDirectory }.map { it.name }.toSet())

            val card = File(temp, "card").apply { mkdirs() }
            for (entry in plan) {
                val target = entry.segments.fold(card) { parent, name -> File(parent, name) }
                if (entry.isDirectory) target.mkdirs() else entry.source.copyTo(target)
            }
            val landed = File(card, kit.name)
            assertContentEquals(File(input, sample).readBytes(), File(landed, sample).readBytes())
            assertEquals(SourceAttribution.render(kit), File(landed, SourceAttribution.fileName(kit)).readText())
            val readBack = ExportReadBack.verify(kit, outcome.copy(primary = landed))
            assertTrue(readBack.none { it.severity == Severity.FAIL }, readBack.toString())
            val directFileCompatibility = ExportReadBack.verify(kit, outcome.copy(primary = File(landed, "${kit.name}.xpm")))
            assertEquals(readBack, directFileCompatibility, "older direct-program callers must still read back")
        } finally {
            temp.deleteRecursively()
        }
    }
}
