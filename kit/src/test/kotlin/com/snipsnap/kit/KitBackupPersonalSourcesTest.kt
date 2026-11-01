package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.LimitedRead
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitBackupPersonalSourcesTest {
    private val temp = Files.createTempDirectory("personal-backup-test").toFile()
    private val id = "1234567890abcdef1234567890abcdef"
    private val rate = WavWriter.MPC_SAMPLE_RATE

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun source(kitDir: File, voiceId: String = id): Map<String, String> {
        val dir = File(kitDir, "${ConfluencePersonalFiles.DIRECTORY}/$voiceId").apply { mkdirs() }
        val samples = FloatArray(rate / 2) { (kotlin.math.sin(it * .018) * .3).toFloat() }
        val raw = File(dir, "raw.wav")
        val rawBytes = ByteBuffer.allocate(44 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(3); putShort(1); putInt(rate); putInt(rate * 4); putShort(4); putShort(32)
            put("data".toByteArray()); putInt(samples.size * 4); samples.forEach { putFloat(it) }
        }.array()
        raw.writeBytes(rawBytes)
        val canonical = File(dir, "source.wav")
        WavWriter.write(canonical, Snip(samples, 1, rate))
        val decoded = WavReader.read(canonical)
        val pcm = ByteBuffer.allocate(8 + decoded.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(rate); putInt(decoded.frameCount); decoded.samples.forEach { putFloat(it) }
        }.array()
        val pcmHash = hash(pcm)
        fun n(value: Number) = JsonValue.Num(value.toDouble())
        fun description(file: File, encoding: String, bits: Int): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "path" to JsonValue.Str(file.name), "fileSha256" to JsonValue.Str(hash(file.readBytes())),
            "frames" to n(samples.size), "sampleRate" to n(rate), "channels" to n(1),
            "bitDepth" to n(bits), "encoding" to JsonValue.Str(encoding), "bytes" to n(file.length()),
        ))
        val manifest = File(dir, "voice.json")
        manifest.writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str("snipsnap-confluence-personal-voice"), "schema" to n(1),
            "id" to JsonValue.Str(voiceId), "name" to JsonValue.Str("My voice"),
            "raw" to description(raw, "IEEE_FLOAT_32", 32),
            "source" to JsonValue.Obj(description(canonical, "PCM_24", 24).entries + ("pcmHash" to JsonValue.Str(pcmHash))),
            "region" to JsonValue.Obj(mapOf("assetHash" to JsonValue.Str(pcmHash))),
        ))) + "\n")
        return linkedMapOf("engine" to "CONFLUENCE_PERSONAL", "personalVoiceId" to voiceId,
            "personalVoiceManifestSha256" to hash(manifest.readBytes()), "personalSourceSha256" to pcmHash,
            "personalSourceFileSha256" to hash(canonical.readBytes()), "sourceHashes" to pcmHash,
            "personalRecipe" to "{\"schema\":1,\"mode\":\"UNISON\",\"performers\":4}", "personalVoiceName" to "My voice")
    }

    private fun makeKit(root: File, name: String, personal: Boolean = false, singleLayer: Boolean = false, voiceId: String = id): Kit {
        val dir = File(root, name).apply { mkdirs() }
        val samples = FloatArray(rate / 10) { (kotlin.math.sin(it * .06) * .2).toFloat() }
        WavWriter.write(File(dir, "A01_Loop_01.wav"), Snip(samples, 1, rate))
        val pad = KitPad(1, "A01_Loop_01.wav", drumClass = DrumClass.LOOP,
            source = if (personal) source(dir, voiceId) else emptyMap(),
            recipe = JsonValue.Obj(mapOf("engine" to JsonValue.Str("unclosed-wrapper"))),
            velocityLayers = if (singleLayer) listOf(KitLayer("A01_Loop_01.wav", 0, 127)) else emptyList())
        return Kit(name, listOf(pad)).also { KitStore.save(it, dir) }
    }

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            add(entry.name to zip.readBytes())
        } }
    }

    private fun alterBackup(file: File, alter: (String, ByteArray) -> ByteArray): File = File(temp, "altered-${System.nanoTime()}.zip").apply {
        writeBytes(zipBytes(entries(file.readBytes()).map { (name, bytes) -> name to alter(name, bytes) }))
    }

    @Test fun `native backup preserves only referenced personal sources and exact replay metadata`() {
        val root = File(temp, "kits")
        val original = makeKit(root, "Voice", personal = true)
        // An old unreferenced take is private library material, not part of this pad's closure.
        File(root, "Voice/${ConfluencePersonalFiles.DIRECTORY}/unreferenced/secret.txt").apply { parentFile.mkdirs(); writeText("not referenced") }
        val backup = KitBackup.backup(root, File(temp, "backup.zip"))
        ZipFile(backup.file).use { zip ->
            assertEquals(setOf("Voice.xpn", KitBackupNative.entryName("Voice.xpn")), zip.entries().toList().map { it.name }.toSet())
        }
        val restored = KitBackup.restore(backup.file, File(temp, "fresh")).kits.single()
        assertEquals(original.pads.single().source, restored.kit.pads.single().source)
        assertEquals(null, restored.kit.pads.single().recipe, "a native source closure must not revive unclosed treatment wrappers")
        for (name in listOf("voice.json", "raw.wav", "source.wav")) {
            val relative = "${ConfluencePersonalFiles.DIRECTORY}/$id/$name"
            assertContentEquals(File(root, "Voice/$relative").readBytes(), File(restored.directory, relative).readBytes(), name)
        }
        assertFalse(File(restored.directory, "${ConfluencePersonalFiles.DIRECTORY}/unreferenced").exists())
        assertEquals(restored.kit, KitStore.load(restored.directory))
        ConfluencePersonalFiles.files(restored.kit, restored.directory)
        // Ordinary rendered exports still carry no raw personal audio or native source pack.
        val xpn = File(temp, "ordinary.xpn")
        XpnPackager.write(original, File(root, "Voice"), xpn, Exporters.defaultMeta(original))
        ZipFile(xpn).use { zip -> assertTrue(zip.entries().toList().none { it.name.contains(".confluence-personal") || it.name.startsWith(KitBackupNative.PREFIX) }) }
    }

    @Test fun `single velocity layer keeps baseline imported shape and unrelated pads stay render only`() {
        val root = File(temp, "mixed")
        val original = makeKit(root, "Mixed", personal = true, singleLayer = true)
        val other = original.pads.single().copy(slot = 2, sampleFile = "A02_Loop_02.wav", source = mapOf("engine" to "BOARD"),
            velocityLayers = emptyList())
        File(root, "Mixed/A01_Loop_01.wav").copyTo(File(root, "Mixed/A02_Loop_02.wav"))
        KitStore.save(original.copy(pads = original.pads + other), File(root, "Mixed"))
        val result = KitBackup.restore(KitBackup.backup(root, File(temp, "mixed.zip")).file, File(temp, "mixed-out")).kits.single()
        assertTrue(result.kit.pad(1)!!.velocityLayers.isEmpty(), "a sole XPN velocity zone still imports as an ordinary pad")
        assertEquals(null, result.kit.pad(1)!!.recipe)
        assertEquals(null, result.kit.pad(2)!!.recipe, "the personal closure cannot revive a BOARD wrapper without its source assets")
        assertEquals(other.source, result.kit.pad(2)!!.source)
    }

    @Test fun `malformed personal manifest or changed binding skips that kit without losing healthy kits`() {
        val root = File(temp, "broken")
        makeKit(root, "Healthy")
        makeKit(root, "MissingFields", personal = true)
        File(root, "MissingFields/${ConfluencePersonalFiles.DIRECTORY}/$id/voice.json").writeText("{\"format\":\"snipsnap-confluence-personal-voice\"}")
        val changed = makeKit(root, "StaleBinding", personal = true)
        KitStore.save(changed.copy(pads = changed.pads.map { it.copy(source = it.source + ("personalSourceSha256" to "0".repeat(64))) }), File(root, "StaleBinding"))
        val spoofed = makeKit(root, "SpoofedPcm", personal = true)
        val manifest = File(root, "SpoofedPcm/${ConfluencePersonalFiles.DIRECTORY}/$id/voice.json")
        val metadata = Json.parse(manifest.readText()).obj().toMutableMap()
        metadata["source"] = JsonValue.Obj(metadata.getValue("source").obj() + ("pcmHash" to JsonValue.Str("0".repeat(64))))
        metadata["region"] = JsonValue.Obj(mapOf("assetHash" to JsonValue.Str("0".repeat(64))))
        manifest.writeText(Json.write(JsonValue.Obj(metadata)) + "\n")
        KitStore.save(spoofed.copy(pads = spoofed.pads.map { it.copy(source = it.source + mapOf(
            "personalSourceSha256" to "0".repeat(64), "sourceHashes" to "0".repeat(64),
            "personalVoiceManifestSha256" to hash(manifest.readBytes()),
        )) }), File(root, "SpoofedPcm"))
        val result = KitBackup.backup(root, File(temp, "broken.zip"))
        assertEquals(listOf("Healthy"), result.packed)
        assertTrue(result.skipped.getValue("MissingFields").contains("manifest"), result.skipped.toString())
        assertTrue(result.skipped.getValue("StaleBinding").contains("source binding differs"), result.skipped.toString())
        assertTrue(result.skipped.getValue("SpoofedPcm").contains("canonical PCM identity differs"), result.skipped.toString())
    }

    @Test fun `tampered source bytes fail before overwrite and healthy siblings still restore`() {
        val root = File(temp, "tamper")
        makeKit(root, "Voice", personal = true); makeKit(root, "Healthy")
        val backup = KitBackup.backup(root, File(temp, "tamper.zip"))
        val altered = alterBackup(backup.file) { name, bytes ->
            if (!name.startsWith(KitBackupNative.PREFIX)) bytes else zipBytes(entries(bytes).map { (path, body) ->
                path to if (path.endsWith("source.wav")) body.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() } else body
            })
        }
        val dest = File(temp, "tamper-out")
        makeKit(dest, "Voice")
        val before = File(dest, "Voice/kit.json").readBytes()
        val result = KitBackup.restore(altered, dest, overwrite = true)
        assertEquals(listOf("Healthy"), result.kits.map { it.kit.name })
        assertTrue(result.skipped.getValue("Voice.xpn").contains("checksum"), result.skipped.toString())
        assertContentEquals(before, File(dest, "Voice/kit.json").readBytes(), "a refused source pack must not replace an existing kit")
        assertFalse(File(dest, "Voice/${ConfluencePersonalFiles.DIRECTORY}").exists())
    }

    @Test fun `unsafe unlisted source path and cross XPN binding refuse without partial kit`() {
        val root = File(temp, "paths")
        makeKit(root, "Voice", personal = true)
        val backup = KitBackup.backup(root, File(temp, "paths.zip"))
        val traversal = alterBackup(backup.file) { name, bytes ->
            if (name.startsWith(KitBackupNative.PREFIX)) zipBytes(entries(bytes) + ("../escape.wav" to byteArrayOf(1))) else bytes
        }
        val failure = assertFailsWith<IllegalArgumentException> { KitBackup.restore(traversal, File(temp, "paths-out")) }
        assertTrue(failure.message!!.contains("unsafe native backup path"), failure.message)
        assertFalse(File(temp, "paths-out/Voice").exists())
        assertFalse(File(temp, "escape.wav").exists())
        val stale = alterBackup(backup.file) { name, bytes -> if (name == "Voice.xpn") bytes + byteArrayOf(1) else bytes }
        val mismatch = assertFailsWith<IllegalArgumentException> { KitBackup.restore(stale, File(temp, "stale-out")) }
        assertTrue(mismatch.message!!.contains("does not match its rendered XPN"), mismatch.message)
        assertFalse(File(temp, "stale-out/Voice").exists())
    }

    @Test fun `source extraction spends the same restore budget as rendered samples and archive bytes`() {
        val root = File(temp, "budget")
        makeKit(root, "Voice", personal = true)
        val backup = KitBackup.backup(root, File(temp, "budget.zip"))
        val archiveBytes = ZipFile(backup.file).use { zip -> zip.entries().toList().sumOf { it.size } }
        val renderedBytes = File(root, "Voice/A01_Loop_01.wav").length()
        val budget = XpnImporter.WriteBudget(archiveBytes + renderedBytes + 20_000)
        val error = assertFailsWith<LimitedRead.TooLargeException> { KitBackup.restore(backup.file, File(temp, "budget-out"), budget = budget) }
        assertTrue(error.message!!.contains("shared backup write budget"), error.message)
        assertEquals(0L, budget.remaining, "failed source writes cannot replenish the shared budget")
        assertFalse(File(temp, "budget-out/Voice").exists())
        assertEquals(1, KitBackup.restore(backup.file, File(temp, "roomy-out"), budget = XpnImporter.WriteBudget(5L * 1024 * 1024)).kits.size)
    }

    @Test fun `removing a personal source archive refuses that kit instead of claiming an editable restore`() {
        val root = File(temp, "missing-pack")
        makeKit(root, "Voice", personal = true); makeKit(root, "Healthy")
        val backup = KitBackup.backup(root, File(temp, "missing-pack.zip"))
        val stripped = File(temp, "stripped.zip").apply {
            writeBytes(zipBytes(entries(backup.file.readBytes()).filterNot { it.first.startsWith(KitBackupNative.PREFIX) }))
        }
        val dest = File(temp, "missing-pack-out")
        val restored = KitBackup.restore(stripped, dest)
        assertEquals(listOf("Healthy"), restored.kits.map { it.kit.name })
        assertTrue(restored.skipped.getValue("Voice.xpn").contains("missing its native source archive"), restored.skipped.toString())
        assertFalse(File(dest, "Voice").exists())
    }

    @Test fun `a symlinked raw source is refused rather than copied out of the kit`() {
        val root = File(temp, "links")
        makeKit(root, "Voice", personal = true); makeKit(root, "Healthy")
        val raw = File(root, "Voice/${ConfluencePersonalFiles.DIRECTORY}/$id/raw.wav")
        val outside = File(temp, "outside.wav").also { raw.copyTo(it) }
        assertTrue(raw.delete())
        Files.createSymbolicLink(raw.toPath(), outside.toPath())
        val result = KitBackup.backup(root, File(temp, "links.zip"))
        assertEquals(listOf("Healthy"), result.packed)
        assertTrue(result.skipped.getValue("Voice").contains("symbolic link"), result.skipped.toString())
    }

    @Test fun `a merged kit owns both personal source closures after its donors are deleted`() {
        val root = File(temp, "merge-donors")
        val a = makeKit(root, "Alpha", personal = true)
        val secondId = "abcdef1234567890abcdef1234567890"
        val b = makeKit(root, "Beta", personal = true, voiceId = secondId)
        val dest = File(temp, "merged-shelf/Combined")
        val expected = listOf(id to File(root, "Alpha"), secondId to File(root, "Beta")).flatMap { (voiceId, dir) ->
            listOf("voice.json", "raw.wav", "source.wav").map { name ->
                val relative = "${ConfluencePersonalFiles.DIRECTORY}/$voiceId/$name"
                relative to File(dir, relative).readBytes()
            }
        }
        val merged = KitMerge.merge(File(root, "Alpha"), File(root, "Beta"), dest)
        assertEquals(a.pad(1)!!.source + ("mergedFrom" to "Alpha"), merged.pad(1)!!.source)
        assertEquals(b.pad(1)!!.source + ("mergedFrom" to "Beta"), merged.pad(17)!!.source)
        assertTrue(root.deleteRecursively())
        for ((path, bytes) in expected) assertContentEquals(bytes, File(dest, path).readBytes(), path)
        ConfluencePersonalFiles.requireImportable(merged, dest)
        val restored = KitBackup.restore(KitBackup.backup(dest.parentFile, File(temp, "merged-backup.zip")).file, File(temp, "merged-restored")).kits.single()
        assertEquals(merged.pad(1)!!.source, restored.kit.pad(1)!!.source)
        assertEquals(merged.pad(17)!!.source, restored.kit.pad(17)!!.source)
        ConfluencePersonalFiles.requireImportable(restored.kit, restored.directory)
    }

    @Test fun `merge refuses missing personal dependencies before creating its destination`() {
        val root = File(temp, "merge-missing")
        makeKit(root, "Alpha", personal = true); makeKit(root, "Beta")
        File(root, "Alpha/${ConfluencePersonalFiles.DIRECTORY}/$id/raw.wav").delete()
        val dest = File(temp, "merge-missing-out")
        val error = assertFailsWith<IllegalArgumentException> { KitMerge.merge(File(root, "Alpha"), File(root, "Beta"), dest) }
        assertTrue(error.message!!.contains("missing or oversized personal voice raw.wav"), error.message)
        assertFalse(dest.exists(), "metadata must not travel without its actual source closure")
    }

    @Test fun `merge refuses a same ID with conflicting immutable manifests before writes`() {
        val root = File(temp, "merge-conflict")
        makeKit(root, "Alpha", personal = true)
        val b = makeKit(root, "Beta", personal = true)
        val manifest = File(root, "Beta/${ConfluencePersonalFiles.DIRECTORY}/$id/voice.json")
        val changed = Json.parse(manifest.readText()).obj() + ("name" to JsonValue.Str("Different take"))
        manifest.writeText(Json.write(JsonValue.Obj(changed)) + "\n")
        KitStore.save(b.copy(pads = b.pads.map { it.copy(source = it.source + ("personalVoiceManifestSha256" to hash(manifest.readBytes()))) }), File(root, "Beta"))
        val dest = File(temp, "merge-conflict-out")
        val error = assertFailsWith<IllegalArgumentException> { KitMerge.merge(File(root, "Alpha"), File(root, "Beta"), dest) }
        assertTrue(error.message!!.contains("conflicting immutable source files"), error.message)
        assertFalse(dest.exists())
    }
}
