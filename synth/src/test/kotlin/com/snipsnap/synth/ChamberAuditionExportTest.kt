package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChamberAuditionExportTest {
    @Test
    fun `MPC download requires the exact supplied kit manifest and preserves its checksum`() {
        val root = createTempDirectory("chamber-download-provenance").toFile()
        try {
            val kit = File(root, ChamberKitGenerator.KIT_NAME).also { it.mkdirs() }
            val manifest = File(kit, "manifest.json").also { it.writeText("{\"engine\":\"CHAMBER\",\"revision\":\"current\"}\n") }
            val page = File(root, "audition").also { it.mkdirs() }
            assertTrue(ChamberAuditionGenerator.copyKitDownload(page, kit).isEmpty())
            val archive = File(root, ChamberKitGenerator.ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("${ChamberKitGenerator.KIT_NAME}/manifest.json"))
                zip.write(manifest.readBytes())
                zip.closeEntry()
            }
            val checksum = File(root, "${archive.name}.sha256").also { it.writeText("${sha(archive)}  ${archive.name}\n") }
            val downloads = ChamberAuditionGenerator.copyKitDownload(page, kit)
            assertContentEquals(archive.readBytes(), File(page, downloads.getValue("mpcKit")).readBytes())
            assertContentEquals(checksum.readBytes(), File(page, downloads.getValue("mpcKitChecksum")).readBytes())
            checksum.writeText("wrong checksum  ${archive.name}\n")
            assertTrue(assertFailsWith<IllegalArgumentException> { ChamberAuditionGenerator.copyKitDownload(page, kit) }
                .message.orEmpty().contains("checksum does not match"))
            checksum.delete()
            manifest.writeText("{\"engine\":\"CHAMBER\",\"revision\":\"newer\"}\n")
            assertTrue(assertFailsWith<IllegalArgumentException> { ChamberAuditionGenerator.copyKitDownload(page, kit) }
                .message.orEmpty().contains("does not match the supplied kit manifest"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `finite score preserves exported PCM timing playback gain and complete tails`() {
        val root = createTempDirectory("chamber-performance-replay").toFile()
        try {
            val sample = File(root, "performance-sources/A01.wav")
            WavWriter.write(sample, Snip(floatArrayOf(0f, .25f, .5f, .25f, 0f, -.25f, -.5f, 0f), 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
            val recipe = File(root, "recipes/score.json")
            val score = score(sample)
            ChamberAuditionGenerator.writeJson(recipe, score)
            val source = WavReader.read(sample).samples
            val expected = FloatArray(11)
            for (i in source.indices) expected[i] += source[i] * 1f * .25f
            for (i in source.indices) expected[i + 3] += source[i] * .5f * .25f
            assertContentEquals(expected, ChamberPerformanceReplay.render(recipe).samples)
            for ((mode, gain) in listOf("raw" to 1f, "matched" to 2f)) {
                val output = File(root, "$mode.wav")
                ChamberDiagnosticReplay.main(arrayOf(recipe.path, output.path, mode))
                val bytes = ByteArrayOutputStream().also { stream ->
                    val samples = FloatArray(expected.size) { expected[it] * gain }
                    if (mode == "matched") Dsp.fadeTail(samples)
                    WavWriter.write(stream, Snip(samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
                }.toByteArray()
                assertContentEquals(bytes, output.readBytes(), "$mode score replay changed the exported PCM")
            }
            ChamberAuditionGenerator.writeJson(recipe, score + ("frames" to 10))
            assertTrue(assertFailsWith<IllegalArgumentException> { ChamberPerformanceReplay.render(recipe) }
                .message.orEmpty().contains("truncate a finite room tail"))
            ChamberAuditionGenerator.writeJson(recipe, score)
            sample.appendBytes(byteArrayOf(0))
            assertTrue(assertFailsWith<IllegalArgumentException> { ChamberPerformanceReplay.render(recipe) }
                .message.orEmpty().contains("exported sample hash changed"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `model three archive preserves every historic asset and relocates only its navigation`() {
        val root = createTempDirectory("chamber-archive-preservation").toFile()
        try {
            val source = File(root, "accepted")
            val target = File(root, "finite").also { it.mkdirs() }
            fun write(path: String, text: String) = File(source, path).also {
                it.parentFile.mkdirs()
                it.writeText(text)
            }
            write("index.html", "accepted mix page")
            write("source-provenance.json", "source provenance")
            write("raw/accepted.wav", "accepted PCM bytes")
            write("recipes/accepted.json", "accepted recipe bytes")
            write("archive/model-2/index.html", "model two page")
            write("archive/model-2/manifest.json", "{\"model\":2}")
            write("archive/model-2/raw/original.wav", "model two PCM bytes")
            write("archive/model-1/index.html", "model one page")
            write("archive/model-1/manifest.json", "{\"model\":1}")
            write("archive/model-1/raw/original.wav", "model one PCM bytes")
            val original = "{\"model\":3,\"sourceRevision\":\"accepted-commit\",\"previousAudition\":{\"model\":2,\"url\":\"archive/model-2/index.html\"}}"
            write("manifest.json", original)
            val before = source.walkTopDown().filter { it.isFile }.associate { it.relativeTo(source).invariantSeparatorsPath to it.readBytes() }
            val archived = ChamberAuditionGenerator.archivePrevious(source, target, 3)
            assertEquals("archive/model-3/index.html", archived["url"])
            for ((path, bytes) in before) {
                assertContentEquals(bytes, File(source, path).readBytes(), "archiving mutated its source $path")
                val copied = if (path.startsWith("archive/")) File(target, path) else File(target, "archive/model-3/$path")
                if (path != "manifest.json") assertContentEquals(bytes, copied.readBytes(), "archiving changed $path")
            }
            assertEquals(original, File(target, "archive/model-3/manifest-original.json").readText())
            val updated = Json.parse(File(target, "archive/model-3/manifest.json").readText()).obj()
            assertEquals("../model-2/index.html", updated.getValue("previousAudition").obj().getValue("url").str())
            val navigation = updated.getValue("navigationRelocation").obj()
            assertEquals(sha(File(target, "archive/model-3/manifest-original.json")), navigation.getValue("originalManifestSha256").str())
            assertTrue(updated.getValue("model") is JsonValue.Num)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun score(sample: File): Map<String, Any?> = linkedMapOf(
        "performanceRecipe" to 1, "engine" to ChamberPatch.ENGINE, "sampleRate" to Dsp.RATE,
        "frames" to 11, "mixGain" to .25f, "rawGain" to 1f, "matchedGain" to 2f,
        "matchedTailTreatment" to "Dsp.fadeTail",
        "sources" to listOf(mapOf("id" to "A01", "sample" to "../performance-sources/A01.wav", "sampleSha256" to sha(sample))),
        "events" to listOf(mapOf("pad" to "A01", "atFrame" to 0, "gain" to 1f),
            mapOf("pad" to "A01", "atFrame" to 3, "gain" to .5f)),
    )

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
