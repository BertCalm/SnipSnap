package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.PadRecipe
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OspreyHandoffTest {
    @Test
    fun `handoff round trips an editable starter and both programs with one shared WAV set`() {
        val directory = createTempDirectory("osprey-handoff").toFile()
        try {
            val result = OspreyHandoffGenerator.generate(directory, baseline = null,
                OspreyHandoffGenerator.Provenance("integration-test-fixture", emptyMap()))
            val receipt = Json.parse(result.manifest.readText()).obj()
            val archive = receipt.getValue("archive").obj()
            assertEquals(16, receipt.getValue("padCount").int())
            assertEquals(16, receipt.getValue("waveFileCount").int())
            assertEquals(result.archive.length(), archive.getValue("bytes").long())
            assertEquals(sha(result.archive.readBytes()), archive.getValue("sha256").str())
            assertFalse(receipt.getValue("validation").obj().getValue("hardwareVerified").bool())
            assertFalse(receipt.getValue("validation").obj().getValue("phoneVerified").bool())
            val extracted = File(directory, "extracted")
            ZipFile(result.archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(16, entries.count { it.name.endsWith(".wav") })
                assertEquals(1, entries.count { it.name.endsWith(".xtd") })
                assertEquals(1, entries.count { it.name.endsWith(".xpm") })
                assertTrue(entries.single { it.name.endsWith(".xpm") }.name.contains("_[TrackData]/"))
                for (entry in entries) {
                    val target = File(extracted, entry.name).canonicalFile
                    assertTrue(target.toPath().startsWith(extracted.canonicalFile.toPath()))
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
            val root = File(extracted, OspreyHandoffGenerator.KIT_NAME)
            val kitFile = File(root, receipt.getValue("snipsnapKit").str())
            val kit = KitStore.load(kitFile.parentFile)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertTrue(kit.pads.all { File(kitFile.parentFile, it.sampleFile).isFile })
            assertFalse(File(root, "snipsnap").exists())
            assertEquals("BETWEEN SHORES", (PadRecipe.fromJsonValue(kit.pads.last().recipe!!).patch as OspreyPatch).name)
            for (row in receipt.getValue("files").arr()) {
                val file = row.obj()
                val bytes = File(root, file.getValue("path").str()).readBytes()
                assertEquals(file.getValue("bytes").long(), bytes.size.toLong())
                assertEquals(file.getValue("sha256").str(), sha(bytes))
            }
            val validation = receipt.getValue("validation").obj()
            assertTrue(validation.getValue("starterKitStoreLoadExact").bool())
            assertTrue(validation.getValue("sharedKitStoreLoadExact").bool())
            assertTrue(validation.getValue("sharedKitDiffersOnlyBySampleFilenames").bool())
            assertEquals(16, validation.getValue("sharedRecipeRegenerationByteExactPads").int())
            for (name in listOf("preflight", "sharedKitPreflight", "nativeXtd", "compatibilityXpm")) {
                val checks = receipt.getValue("validation").obj().getValue(name).arr()
                assertTrue(checks.isNotEmpty())
                assertTrue(checks.none { it.obj().getValue("severity").str() == "FAIL" })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
