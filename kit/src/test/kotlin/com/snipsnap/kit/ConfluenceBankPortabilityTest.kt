package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
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

class ConfluenceBankPortabilityTest {
    private val temp = Files.createTempDirectory("selected-bank-portability").toFile()
    private val rate = WavWriter.MPC_SAMPLE_RATE
    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    companion object {
        private val pack = listOf(File("../synth/src/main/resources/confluence-selected-bank"), File("synth/src/main/resources/confluence-selected-bank"))
            .first { File(it, "bank.json").isFile }
        private val manifestBytes by lazy { File(pack, "bank.json").readBytes() }
        private val manifest by lazy { Json.parse(manifestBytes.toString(Charsets.UTF_8)).obj() }
        private val fingerprint by lazy { sha(manifestBytes) }
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private val payloads by lazy { (listOf("bank.json") + manifest.getValue("assets").arr().map { it.obj().getValue("path").str() })
            .associateWith { File(pack, it).readBytes() } }
    }

    private fun kit(root: File, name: String = "Passages", selected: Boolean = true): Kit {
        val dir = File(root, name).apply { mkdirs() }
        WavWriter.write(File(dir, "A01_Loop_01.wav"), Snip(FloatArray(rate / 10) { (kotlin.math.sin(it * .04) * .2).toFloat() }, 1, rate))
        val source = if (selected) {
            for ((path, bytes) in payloads) {
                val file = ConfluenceBankFiles.child(dir, "${ConfluenceBankFiles.DIRECTORY}/$fingerprint/$path")
                file.parentFile.mkdirs(); file.writeBytes(bytes)
            }
            val voice = manifest.getValue("voices").arr().first().obj()
            mapOf("engine" to ConfluenceBankFiles.ENGINE, "bankId" to manifest.getValue("id").str(),
                "bankVersion" to manifest.getValue("version").int().toString(), "bankManifestSha256" to fingerprint,
                "usedVoiceIds" to voice.getValue("id").str(), "sourceHashes" to voice.getValue("canonicalPcmSha256").str(),
                // The kit transport keeps this recipe opaque; synth/shell tests validate its DSP schema.
                "bankRecipe" to "{\"transportFixture\":true}", "bankSettings" to "{\"performers\":1}",
                "attribution" to payloads.getValue("SOURCE-ATTRIBUTION.txt").toString(Charsets.UTF_8),
                "license" to "CC-BY-4.0; CC0-1.0", "sourceUrl" to "https://freesound.org/people/sankalp/sounds/193920/",
                "modifications" to "Exact selected canonical source pack; finite rendered fixture.")
        } else emptyMap()
        return Kit(name, listOf(KitPad(1, "A01_Loop_01.wav", drumClass = DrumClass.LOOP, source = source)))
            .also { KitStore.save(it, dir) }
    }

    private fun addPersonal(kit: Kit, dir: File): Kit {
        val id = "1234567890abcdef1234567890abcdef"
        val voiceDir = File(dir, "${ConfluencePersonalFiles.DIRECTORY}/$id").apply { mkdirs() }
        val snip = Snip(FloatArray(rate / 2) { (kotlin.math.sin(it * .03) * .25).toFloat() }, 1, rate)
        val raw = File(voiceDir, "raw.wav")
        raw.writeBytes(ByteBuffer.allocate(44 + snip.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(3); putShort(1); putInt(rate); putInt(rate * 4); putShort(4); putShort(32)
            put("data".toByteArray()); putInt(snip.samples.size * 4); snip.samples.forEach { putFloat(it) }
        }.array())
        val source = File(voiceDir, "source.wav")
        WavWriter.write(source, snip)
        val hash = ConfluencePersonalFiles.canonicalHash(source)
        fun n(value: Number) = JsonValue.Num(value.toDouble())
        fun info(file: File, bits: Int, encoding: String) = JsonValue.Obj(linkedMapOf(
            "path" to JsonValue.Str(file.name), "fileSha256" to JsonValue.Str(sha(file.readBytes())),
            "bytes" to n(file.length()), "frames" to n(snip.frameCount), "sampleRate" to n(rate), "channels" to n(1),
            "bitDepth" to n(bits), "encoding" to JsonValue.Str(encoding)))
        val metadata = File(voiceDir, "voice.json")
        metadata.writeText(Json.write(JsonValue.Obj(mapOf(
            "format" to JsonValue.Str("snipsnap-confluence-personal-voice"), "schema" to n(1), "id" to JsonValue.Str(id),
            "raw" to info(raw, 32, "IEEE_FLOAT_32"),
            "source" to JsonValue.Obj(info(source, 24, "PCM_24").entries + ("pcmHash" to JsonValue.Str(hash))),
            "region" to JsonValue.Obj(mapOf("assetHash" to JsonValue.Str(hash))),
        ))) + "\n")
        val personal = kit.pads.single().copy(slot = 2, sampleFile = "A02_Voice_02.wav", source = mapOf(
            "engine" to "CONFLUENCE_PERSONAL", "personalVoiceId" to id,
            "personalVoiceManifestSha256" to sha(metadata.readBytes()), "personalSourceSha256" to hash,
            "personalSourceFileSha256" to sha(source.readBytes()), "sourceHashes" to hash, "personalRecipe" to "{}"))
        File(dir, kit.pads.single().sampleFile).copyTo(File(dir, personal.sampleFile))
        return kit.copy(pads = kit.pads + personal).also { KitStore.save(it, dir) }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { archive -> for ((path, bytes) in entries) {
            archive.putNextEntry(ZipEntry(path)); archive.write(bytes); archive.closeEntry()
        } }
    }.toByteArray()
    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(bytes.inputStream()).use { archive -> while (true) {
            val entry = archive.nextEntry ?: break
            add(entry.name to archive.readBytes())
        } }
    }
    private fun altered(backup: File, edit: (String, ByteArray) -> ByteArray): File = File(temp, "altered-${System.nanoTime()}.zip").apply {
        writeBytes(zip(entries(backup.readBytes()).map { (name, bytes) -> name to edit(name, bytes) }))
    }

    @Test fun `native backup carries exact packaged bank fingerprint assets and credits after donor deletion`() {
        val root = File(temp, "shelf")
        val original = kit(root)
        val backup = KitBackup.backup(root, File(temp, "native.zip"))
        assertTrue(root.deleteRecursively())
        val restored = KitBackup.restore(backup.file, File(temp, "restored")).kits.single()
        assertEquals(original.pads.single().source, restored.kit.pads.single().source)
        for ((path, bytes) in payloads) assertContentEquals(bytes, File(ConfluenceBankFiles.bankDirectory(restored.directory, fingerprint), path).readBytes(), path)
        ConfluenceBankFiles.requireImportable(restored.kit, restored.directory)
        assertTrue(SourceAttribution.render(restored.kit)!!.contains("Kaustuv"))
        assertTrue(SourceAttribution.render(restored.kit)!!.contains("CC0"))
        assertEquals(restored.kit, KitStore.load(restored.directory))
    }

    @Test fun `ordinary XPN is rendered only while native backup composes personal and bank source closures`() {
        val root = File(temp, "combined")
        val original = addPersonal(kit(root), File(root, "Passages"))
        val xpn = File(temp, "ordinary.xpn")
        XpnPackager.write(original, File(root, "Passages"), xpn, Exporters.defaultMeta(original))
        val rendered = XpnImporter.import(xpn, File(temp, "ordinary"))
        assertEquals(ConfluenceBankFiles.RENDERED_ENGINE, rendered.kit.pad(1)!!.source["engine"])
        for (key in listOf("bankManifestSha256", "bankRecipe", "bankSettings", "sourceHashes", "usedVoiceIds")) assertFalse(key in rendered.kit.pad(1)!!.source, key)
        assertEquals(original.pad(1)!!.source["attribution"], rendered.kit.pad(1)!!.source["attribution"])
        assertEquals("CONFLUENCE_PERSONAL_RENDERED", rendered.kit.pad(2)!!.source["engine"])
        assertFalse(File(rendered.directory, ConfluenceBankFiles.DIRECTORY).exists())
        assertTrue(KitBackup.backup(rendered.directory.parentFile, File(temp, "rendered-backup.zip")).skipped.isEmpty())
        val backup = KitBackup.backup(root, File(temp, "combined.zip"))
        ZipFile(backup.file).use { archive -> assertEquals(setOf("Passages.xpn", KitBackupNative.entryName("Passages.xpn"), KitBackupBanks.entryName("Passages.xpn")), archive.entries().toList().map { it.name }.toSet()) }
        assertTrue(root.deleteRecursively())
        val restored = KitBackup.restore(backup.file, File(temp, "combined-out")).kits.single()
        assertEquals(original.pads.map { it.source }, restored.kit.pads.map { it.source })
        ConfluenceBankFiles.requireImportable(restored.kit, restored.directory)
        assertTrue(File(restored.directory, ConfluencePersonalFiles.DIRECTORY).isDirectory)
    }

    @Test fun `tampered bank payload cannot overwrite an existing kit and healthy siblings still restore`() {
        val root = File(temp, "tamper")
        kit(root); kit(root, "Healthy", selected = false)
        val backup = KitBackup.backup(root, File(temp, "tamper.zip"))
        val bad = altered(backup.file) { name, bytes -> if (!name.startsWith(KitBackupBanks.PREFIX)) bytes else
            zip(entries(bytes).map { (path, body) -> path to if (path.endsWith("yaman-01.wav")) body.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() } else body }) }
        val dest = File(temp, "tamper-out")
        kit(dest, selected = false)
        val before = File(dest, "Passages/kit.json").readBytes()
        val result = KitBackup.restore(bad, dest, overwrite = true)
        assertEquals(listOf("Healthy"), result.kits.map { it.kit.name })
        assertTrue(result.skipped.getValue("Passages.xpn").contains("checksum"), result.skipped.toString())
        assertContentEquals(before, File(dest, "Passages/kit.json").readBytes())
        assertFalse(File(dest, "Passages/${ConfluenceBankFiles.DIRECTORY}").exists())
    }

    @Test fun `missing native bank sidecar and a wrong manifest fingerprint refuse without factory fallback`() {
        val root = File(temp, "missing")
        val original = kit(root); kit(root, "Healthy", selected = false)
        val backup = KitBackup.backup(root, File(temp, "missing.zip"))
        val stripped = File(temp, "stripped.zip").apply { writeBytes(zip(entries(backup.file.readBytes()).filterNot { it.first.startsWith(KitBackupBanks.PREFIX) })) }
        val result = KitBackup.restore(stripped, File(temp, "missing-out"))
        assertEquals(listOf("Healthy"), result.kits.map { it.kit.name })
        assertTrue(result.skipped.getValue("Passages.xpn").contains("missing its native source archive"), result.skipped.toString())
        val wrong = original.copy(pads = original.pads.map { it.copy(source = it.source + ("bankManifestSha256" to "0".repeat(64))) })
        KitStore.save(wrong, File(root, "Passages"))
        val refused = KitBackup.backup(root, File(temp, "wrong-bank.zip"))
        assertEquals(listOf("Healthy"), refused.packed)
        assertTrue(refused.skipped.getValue("Passages").contains("manifest"), refused.skipped.toString())
    }

    @Test fun `unsafe sidecar paths and changed XPN bytes fail before publication`() {
        val root = File(temp, "paths")
        kit(root)
        val backup = KitBackup.backup(root, File(temp, "paths.zip"))
        val traversal = altered(backup.file) { name, bytes -> if (name.startsWith(KitBackupBanks.PREFIX)) zip(entries(bytes) + ("../escape.wav" to byteArrayOf(1))) else bytes }
        val error = assertFailsWith<IllegalArgumentException> { KitBackup.restore(traversal, File(temp, "paths-out")) }
        assertTrue(error.message!!.contains("unsafe selected-bank backup path"), error.message)
        assertFalse(File(temp, "paths-out/Passages").exists())
        val changed = altered(backup.file) { name, bytes -> if (name == "Passages.xpn") bytes + byteArrayOf(1) else bytes }
        assertFailsWith<IllegalArgumentException> { KitBackup.restore(changed, File(temp, "changed-out")) }
            .also { assertTrue(it.message!!.contains("differs from its rendered XPN"), it.message) }
        assertFalse(File(temp, "changed-out/Passages").exists())
    }

    @Test fun `bank source extraction shares the archive and sample write budget`() {
        val root = File(temp, "budget")
        kit(root)
        val backup = KitBackup.backup(root, File(temp, "budget.zip"))
        val outerBytes = ZipFile(backup.file).use { archive -> archive.entries().toList().sumOf { it.size } }
        val budget = XpnImporter.WriteBudget(outerBytes + File(root, "Passages/A01_Loop_01.wav").length() + 50_000)
        val error = assertFailsWith<LimitedRead.TooLargeException> { KitBackup.restore(backup.file, File(temp, "budget-out"), budget = budget) }
        assertTrue(error.message!!.contains("shared backup write budget"), error.message)
        assertEquals(0L, budget.remaining)
        assertFalse(File(temp, "budget-out/Passages").exists())
    }

    @Test fun `merge copies a referenced bank once and missing or symlinked dependencies refuse before writes`() {
        val donors = File(temp, "donors")
        val a = kit(donors, "Alpha"); val b = kit(donors, "Beta")
        val mergedDir = File(temp, "merged")
        val merged = KitMerge.merge(File(donors, "Alpha"), File(donors, "Beta"), mergedDir)
        assertEquals(a.pad(1)!!.source + ("mergedFrom" to "Alpha"), merged.pad(1)!!.source)
        assertEquals(b.pad(1)!!.source + ("mergedFrom" to "Beta"), merged.pad(17)!!.source)
        assertTrue(donors.deleteRecursively())
        ConfluenceBankFiles.requireImportable(merged, mergedDir)
        assertEquals(1, File(mergedDir, ConfluenceBankFiles.DIRECTORY).listFiles()!!.size)
        val fresh = File(temp, "bad-donors")
        kit(fresh, "Alpha"); kit(fresh, "Beta", selected = false)
        val source = File(ConfluenceBankFiles.bankDirectory(File(fresh, "Alpha"), fingerprint), "voices/yaman-01.wav")
        val outside = File(temp, "outside.wav").also { source.copyTo(it) }
        assertTrue(source.delete())
        val missingDest = File(temp, "missing-merge")
        assertFailsWith<IllegalArgumentException> { KitMerge.merge(File(fresh, "Alpha"), File(fresh, "Beta"), missingDest) }
        assertFalse(missingDest.exists())
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        val linkDest = File(temp, "linked-merge")
        assertFailsWith<IllegalArgumentException> { KitMerge.merge(File(fresh, "Alpha"), File(fresh, "Beta"), linkDest) }
            .also { assertTrue(it.message!!.contains("symbolic link"), it.message) }
        assertFalse(linkDest.exists())
    }
}
