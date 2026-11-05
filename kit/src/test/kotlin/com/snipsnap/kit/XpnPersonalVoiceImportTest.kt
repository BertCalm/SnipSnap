package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XpnPersonalVoiceImportTest {
    private val temp = Files.createTempDirectory("xpn-personal-voice-").toFile()
    private val id = "1234567890abcdef1234567890abcdef"
    private val rate = WavWriter.MPC_SAMPLE_RATE
    private val editingKeys = setOf("personalVoiceId", "personalRecipe", "personalVoiceManifestSha256",
        "personalSourceSha256", "personalSourceFileSha256", "sourceHashes", "referenceMidi", "rootMidi")

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** The kit layer needs the bound portable audio, not the shell's DSP assessment metadata. */
    private fun fixture(): Pair<Kit, File> {
        val dir = File(temp, "inputs/Personal voice").apply { mkdirs() }
        val samples = FloatArray(rate / 2) { (sin(it * .018) * .3).toFloat() }
        val sourceDir = File(dir, "${ConfluencePersonalFiles.DIRECTORY}/$id").apply { mkdirs() }
        val raw = File(sourceDir, "raw.wav")
        raw.writeBytes(ByteBuffer.allocate(44 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(3); putShort(1); putInt(rate); putInt(rate * 4); putShort(4); putShort(32)
            put("data".toByteArray()); putInt(samples.size * 4); samples.forEach { putFloat(it) }
        }.array())
        val source = File(sourceDir, "source.wav")
        WavWriter.write(source, Snip(samples, 1, rate))
        val decoded = WavReader.read(source)
        val pcmHash = hash(ByteBuffer.allocate(8 + decoded.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(rate); putInt(decoded.frameCount); decoded.samples.forEach { putFloat(it) }
        }.array())
        fun n(value: Number) = JsonValue.Num(value.toDouble())
        fun asset(file: File, encoding: String, bits: Int) = JsonValue.Obj(linkedMapOf(
            "path" to JsonValue.Str(file.name), "fileSha256" to JsonValue.Str(hash(file.readBytes())),
            "bytes" to n(file.length()), "frames" to n(samples.size), "sampleRate" to n(rate),
            "channels" to n(1), "encoding" to JsonValue.Str(encoding), "bitDepth" to n(bits),
        ))
        val manifest = File(sourceDir, "voice.json")
        manifest.writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str("snipsnap-confluence-personal-voice"), "schema" to n(1),
            "id" to JsonValue.Str(id), "name" to JsonValue.Str("My voice"),
            "raw" to asset(raw, "IEEE_FLOAT_32", 32),
            "source" to JsonValue.Obj(asset(source, "PCM_24", 24).entries + ("pcmHash" to JsonValue.Str(pcmHash))),
            "region" to JsonValue.Obj(mapOf("assetHash" to JsonValue.Str(pcmHash))),
        ))) + "\n")
        val rendered = File(dir, "A01_Loop_01.wav")
        WavWriter.write(rendered, Snip(FloatArray(rate / 10) { (sin(it * .06) * .2).toFloat() }, 1, rate))
        val metadata = linkedMapOf(
            "engine" to "CONFLUENCE_PERSONAL", "personalVoiceId" to id, "personalVoiceName" to "My voice",
            "personalVoiceManifestSha256" to hash(manifest.readBytes()), "personalSourceSha256" to pcmHash,
            "personalSourceFileSha256" to hash(source.readBytes()), "sourceHashes" to pcmHash,
            "personalRecipe" to "{\"schemaVersion\":1,\"mode\":\"ENSEMBLE\"}",
            "referenceMidi" to "48.0", "rootMidi" to "48", "origin" to "RECORDING",
            "attribution" to "My voice — supplied by the owner.\nPreserve this line exactly.\n",
            "rawSha256" to hash(rendered.readBytes()),
        )
        val ordinary = File(dir, "A02_Perc_01.wav").also { rendered.copyTo(it) }
        val kit = Kit("Personal voice", listOf(
            KitPad(1, rendered.name, drumClass = DrumClass.LOOP, source = metadata),
            KitPad(2, ordinary.name, drumClass = DrumClass.PERC, source = mapOf("recording" to "ordinary source")),
        ))
        KitStore.save(kit, dir)
        return kit to dir
    }

    private fun packed(kit: Kit, dir: File, name: String): File =
        XpnPackager.write(kit, dir, File(temp, name), Exporters.defaultMeta(kit))

    private fun assertRendered(original: Kit, result: XpnImporter.ImportResult) {
        val expected = (original.pad(1)!!.source - editingKeys) + ("engine" to "CONFLUENCE_PERSONAL_RENDERED")
        assertEquals(expected, result.kit.pad(1)!!.source)
        assertEquals(original.pad(2)!!.source, result.kit.pad(2)!!.source)
        assertEquals(result.kit, KitStore.load(result.directory), "render-only identity must be persisted")
        assertTrue(ConfluencePersonalFiles.referencedIds(result.kit).isEmpty())
        assertFalse(File(result.directory, ConfluencePersonalFiles.DIRECTORY).exists())
    }

    @Test fun `ordinary XPN import and importAll keep provenance and audio without claiming an editable source`() {
        val (kit, dir) = fixture()
        val archive = packed(kit, dir, "ordinary.xpn")
        ZipFile(archive).use { zip -> assertTrue(zip.entries().asSequence().none { it.name.contains(ConfluencePersonalFiles.DIRECTORY) }) }
        val single = XpnImporter.import(archive, File(temp, "single"))
        val all = XpnImporter.importAll(archive, File(temp, "all"))
        assertTrue(all.skipped.isEmpty(), all.skipped.toString())
        for (result in listOf(single, all.kits.single())) {
            assertRendered(kit, result)
            for (pad in result.kit.pads) assertContentEquals(File(dir, pad.sampleFile).readBytes(), File(result.directory, pad.sampleFile).readBytes())
        }
        val backup = KitBackup.backup(single.directory.parentFile, File(temp, "rendered-backup.zip"))
        assertEquals(listOf(kit.name), backup.packed, "a rendered-only import must remain backupable without private source files")
        assertTrue(backup.skipped.isEmpty())
        assertRendered(kit, KitBackup.restore(backup.file, File(temp, "rendered-restored")).kits.single())
    }

    @Test fun `adjacent kit sidecar cannot revive personal editing references during ordinary import`() {
        val (kit, dir) = fixture()
        val archive = packed(kit, dir, "with-sidecar.xpn")
        val contents = ZipFile(archive).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }
            .map { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }.toList() }
        val sidecar = File(temp, "adjacent.xpn")
        ZipOutputStream(sidecar.outputStream()).use { zip ->
            for ((name, bytes) in contents + ("Programs/${KitStore.FILE_NAME}" to File(dir, KitStore.FILE_NAME).readBytes())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        assertRendered(kit, XpnImporter.import(sidecar, File(temp, "adjacent-import")))
    }

    @Test fun `native backup keeps exact personal editing metadata and its portable source closure`() {
        val (kit, dir) = fixture()
        val backup = KitBackup.backup(dir.parentFile, File(temp, "native-backup.zip"))
        assertTrue(backup.skipped.isEmpty(), backup.skipped.toString())
        val expected = listOf("voice.json", "raw.wav", "source.wav").associateWith {
            File(dir, "${ConfluencePersonalFiles.DIRECTORY}/$id/$it").readBytes()
        }
        assertTrue(dir.parentFile.deleteRecursively())
        val restored = KitBackup.restore(backup.file, File(temp, "native-restored")).kits.single()
        assertEquals(kit.pad(1)!!.source, restored.kit.pad(1)!!.source, "the native validation hook must receive and keep exact references")
        for ((name, bytes) in expected) assertContentEquals(bytes, File(restored.directory, "${ConfluencePersonalFiles.DIRECTORY}/$id/$name").readBytes())
        assertEquals(restored.kit, KitStore.load(restored.directory))
    }
}
