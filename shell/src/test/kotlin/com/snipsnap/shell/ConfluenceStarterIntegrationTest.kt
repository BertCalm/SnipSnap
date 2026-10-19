package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.ConfluenceVoiceBank
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfluenceStarterIntegrationTest {
    @Test
    fun `wordless starter lands playable source-backed pads and exports their credits`() {
        val root = createTempDirectory("confluence-app-starter").toFile()
        try {
            val bank = ConfluenceVoiceBank.bundled()
            val starter = assertNotNull(StarterKits.byId(StarterKits.CONFLUENCE_ID))
            assertEquals("CONFLUENCE", starter.displayName)
            assertFalse(starter.seeded)
            val directory = File(root, "native-kit")
            val kit = starter.render("Confluence Voices", directory)
            assertEquals((1..ConfluenceVoiceBank.STARTER_PAD_COUNT).toList(), kit.pads.map { it.slot })
            assertFalse(Preflight.check(kit, directory).blocked())

            val retained = ConfluenceVoiceBank.fromDirectory(File(directory, StarterKits.CONFLUENCE_BANK_DIR))
            assertEquals(bank.manifestText, retained.manifestText)
            assertTrue(File(directory, "${StarterKits.CONFLUENCE_BANK_DIR}/SOURCE-ATTRIBUTION.txt").readText().contains("Wilkins"))
            val loaded = KitStore.load(directory)
            assertEquals(kit.pads.map { it.source }, loaded.pads.map { it.source })
            for (pad in loaded.pads) {
                assertTrue(pad.oneShot, "wordless pads play their complete finite samples")
                assertNull(pad.recipe, "source-dependent recordings must not masquerade as asset-free Patch recipes")
                assertEquals(bank.id, pad.source["bankId"])
                assertTrue(pad.source.getValue("licenseUrl").contains("creativecommons.org/licenses/by/4.0"))
                assertTrue(pad.source.getValue("attribution").contains("Wilkins"))
                assertTrue(pad.source.getValue("sourceUrl").contains("1193957"))
                assertTrue(pad.source.getValue("modifications").isNotBlank())
                val audio = WavReader.read(File(directory, pad.sampleFile))
                assertTrue(audio.frameCount > 0 && audio.samples.all { it.isFinite() })
            }
            val recordedIds = loaded.pads.mapNotNull { it.source["voiceId"] }.toSet()
            assertEquals(bank.voices.map { it.id }.toSet(), recordedIds)
            assertTrue(bank.voices.all { it.wordless && !it.fixtureId.startsWith("phrase-") })

            val exported = KitExporter.exportProgramFolder(loaded, directory, File(root, "card"))
            val notices = exported.directory.walkTopDown().filter { it.isFile && it.extension == "txt" }.joinToString("\n") { it.readText() }
            for (credit in listOf("Wilkins", "Seetharaman", "Wahl", "Pardo", "creativecommons.org/licenses/by/4.0", "1193957", "modifications")) {
                assertTrue(notices.contains(credit, ignoreCase = true), "export must retain $credit")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
