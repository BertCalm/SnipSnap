package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.OspreyCuratedPresets
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OspreyCuratedAuditionTest {
    @Test
    fun `curated audition retains editable recipes and one raw WAV set for both MPC programs`() {
        val directory = createTempDirectory("osprey-curated-audition").toFile()
        try {
            val result = OspreyCuratedAuditionGenerator.generate(directory,
                OspreyCuratedAuditionGenerator.Provenance("integration-test-fixture", mapOf("fixture" to "source-hash")))
            val receipt = Json.parse(result.manifest.readText()).obj()
            assertEquals("integration-test-fixture", receipt.getValue("sourceCommit").str())
            assertEquals("source-hash", receipt.getValue("sourceFilesSha256").obj().getValue("fixture").str())
            assertEquals("new presets awaiting listening", receipt.getValue("reviewStatus").str())
            assertTrue(receipt.getValue("acceptedModels").arr().isEmpty())
            assertEquals(16, receipt.getValue("padCount").int())
            assertEquals(16, receipt.getValue("waveFileCount").int())
            val archive = receipt.getValue("archive").obj()
            assertTrue(result.archive.length() < 25L * 1024 * 1024)
            assertEquals(result.archive.length(), archive.getValue("bytes").long())
            assertEquals(sha(result.archive.readBytes()), archive.getValue("sha256").str())
            assertTrue(archive.getValue("zipRoundTripByteExact").bool())

            val extracted = File(directory, "extracted")
            ZipFile(result.archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(16, entries.count { it.name.endsWith(".wav") })
                assertEquals(16, entries.count { it.name.contains("/recipes/") && it.name.endsWith(".json") })
                assertEquals(16, entries.count { it.name.contains("/patches/") && it.name.endsWith(".json") })
                assertEquals(1, entries.count { it.name.endsWith(".xtd") })
                assertEquals(1, entries.count { it.name.endsWith(".xpm") })
                assertTrue(entries.none { it.name.contains("/previews/") || it.name.contains("/snipsnap/") })
                for (entry in entries) {
                    val target = File(extracted, entry.name).canonicalFile
                    assertTrue(target.toPath().startsWith(extracted.canonicalFile.toPath()))
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    val recorded = archive.getValue("entries").arr().single { it.obj().getValue("path").str() == entry.name }.obj()
                    assertEquals(recorded.getValue("sha256").str(), sha(target.readBytes()))
                }
            }
            val kitFile = File(extracted, receipt.getValue("snipsnapKit").str())
            val kit = KitStore.load(kitFile.parentFile)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertTrue(kit.pads.all { it.oneShot && it.drumClass != DrumClass.LOOP && it.tuneCoarse == 0 && it.tuneFine == 0 })
            val catalog = receipt.getValue("clips").arr().map { it.obj() }
            assertEquals(OspreyCuratedPresets.entries.map { it.id }, catalog.map { it.getValue("id").str() })
            assertEquals(4, catalog.map { it.getValue("role").str() }.distinct().size)
            assertEquals(catalog, receipt.getValue("pads").arr().map { it.obj() })
            val target = receipt.getValue("listeningLevel").obj().getValue("commonTargetRms").num().toFloat()
            assertTrue(target > 0f && target <= .12f)
            for ((index, row) in catalog.withIndex()) {
                val patch = OspreyPatch.fromJsonText(File(extracted, row.getValue("patchFile").str()).readText())
                val recipe = PadRecipe.fromJsonText(File(extracted, row.getValue("recipeFile").str()).readText())
                assertEquals(OspreyCuratedPresets.entries[index].patch, patch)
                assertEquals(patch, recipe.patch)
                assertEquals(recipe.toJsonValue(), kit.pads[index].recipe)
                assertEquals(3, row.getValue("modelVersion").int())
                assertEquals(patch.midi, row.getValue("midi").int())
                assertEquals(patch.macros.getValue("SPACE"), row.getValue("macros").obj().getValue("SPACE").num().toFloat())
                assertFalse(row.getValue("loop").bool())
                assertFalse(row.getValue("externalFx").bool())
                val rawFile = File(extracted, row.getValue("rawFile").str())
                assertEquals(row.getValue("rawSha256").str(), sha(rawFile.readBytes()))
                assertEquals(row.getValue("mpcFile").str(), row.getValue("rawFile").str())
                assertEquals(24, WavInfo.read(rawFile).bitsPerSample)
                assertEquals((patch.durationSeconds * 44_100).toLong(), WavInfo.read(rawFile).frameCount)
                assertEquals(2, WavInfo.read(rawFile).channels)
                assertEquals(null, WavReader.readSmpl(rawFile))
                val previewFile = File(directory, row.getValue("previewFile").str())
                assertEquals(row.getValue("previewSha256").str(), sha(previewFile.readBytes()))
                val preview = WavReader.read(previewFile)
                assertEquals(16, WavInfo.read(previewFile).bitsPerSample)
                assertEquals(WavInfo.read(rawFile).frameCount.toInt(), preview.frameCount)
                assertTrue(preview.peak() <= .80004f)
                assertTrue(abs(Loudness.of(preview) - target) < 4e-5f, "${patch.name} preview is not at the common level")
                assertEquals(96, row.getValue("waveform").arr().size)
            }
            val root = File(extracted, OspreyCuratedAuditionGenerator.KIT_NAME)
            for (entry in receipt.getValue("files").arr()) {
                val row = entry.obj()
                val file = File(root, row.getValue("path").str())
                assertEquals(row.getValue("bytes").long(), file.length())
                assertEquals(row.getValue("sha256").str(), sha(file.readBytes()))
            }
            val validation = receipt.getValue("validation").obj()
            assertFalse(validation.getValue("hardwareVerified").bool())
            assertFalse(validation.getValue("phoneVerified").bool())
            for (counter in listOf("recipeRegenerationByteExactPads", "sharedRecipeRegenerationByteExactPads",
                "exportAudioByteExactPads", "nativeAndXpmFullLengthOneShotPads", "matchedPreviewHeadroomPads")) {
                assertEquals(16, validation.getValue(counter).int())
            }
            for (name in listOf("preflight", "sharedKitPreflight", "nativeXtd", "compatibilityXpm")) {
                val checks = validation.getValue(name).arr()
                assertTrue(checks.isNotEmpty())
                assertTrue(checks.none { it.obj().getValue("severity").str() == "FAIL" })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `common audition target lowers the whole bank when one clip cannot reach requested RMS`() {
        val frames = 44_100
        val smooth = Snip(FloatArray(frames * 2) { index -> (.2 * sin(2 * PI * 440 * (index / 2) / 44_100)).toFloat() }, 2, 44_100)
        val spike = Snip(FloatArray(frames * 2).apply { this[300] = .99f; this[301] = .99f }, 2, 44_100)
        val target = OspreyCuratedAuditionGenerator.commonTarget(listOf(smooth, spike))
        assertEquals(minOf(.12f, .8f * Loudness.of(smooth) / smooth.peak(), .8f * Loudness.of(spike) / spike.peak()), target)
        assertTrue(target < .12f)
        for (source in listOf(smooth, spike)) {
            val gain = target / Loudness.of(source)
            val matched = source.copy(samples = FloatArray(source.samples.size) { source.samples[it] * gain })
            assertTrue(matched.peak() <= .800001f)
            assertTrue(abs(Loudness.of(matched) - target) < 1e-6f)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
