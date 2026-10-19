package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.LimitedRead
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XpnSourceMetadataTest {
    private val temp = Files.createTempDirectory("xpn-source-").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun source(author: String) = linkedMapOf(
        "attribution" to "$author — original wordless recording\nOriginal member: /voices/$author.wav",
        "license" to "CC-BY-4.0",
        "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
        "sourceUrl" to "https://example.test/$author",
        "modifications" to "Chronological crop, constant gain and finite ensemble rendering.",
        "assemblyRecipe" to "{\"roles\":[\"FOUNDATION\",\"AIRY\",\"ANSWER\"],\"seed\":17}",
    )

    private fun kit(name: String, seed: Int, credited: Boolean = true): Pair<Kit, File> {
        val dir = File(temp, "input-$name").apply { mkdirs() }
        for ((index, file) in listOf("A02_Soft.wav", "A02_Loud.wav", "B02_Voice.wav").withIndex()) {
            WavWriter.write(File(dir, file), Snip(FloatArray(441) { i ->
                (0.2 * kotlin.math.sin(i / (7.0 + seed + index))).toFloat()
            }, 1, WavWriter.MPC_SAMPLE_RATE))
        }
        val kit = Kit(name, listOf(
            KitPad(2, "A02_Loud.wav", "LAYERED VOICE", source = if (credited) source("Singer $seed") else emptyMap(),
                velocityLayers = listOf(KitLayer("A02_Soft.wav", 0, 63), KitLayer("A02_Loud.wav", 64, 127))),
            KitPad(18, "B02_Voice.wav", "ORDINARY SOURCE", source = mapOf("app" to "Recorder", "title" to "room $seed\nTake two", "at" to "00:04")),
        ))
        KitStore.save(kit, dir)
        return kit to dir
    }

    private fun pack(kit: Kit, dir: File, name: String = kit.name): File =
        XpnPackager.write(kit, dir, File(temp, "$name.xpn"), Exporters.defaultMeta(kit))

    private fun entries(file: File): LinkedHashMap<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().toList().filter { !it.isDirectory }.associateTo(linkedMapOf()) { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun archive(name: String, contents: Map<String, ByteArray>): File = File(temp, "$name.xpn").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, bytes) in contents) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun changedMetadata(file: File, name: String, change: (Map<String, JsonValue>) -> Map<String, JsonValue>): File {
        val contents = entries(file)
        val path = contents.keys.single { it.endsWith(".snipsnap-source.json") }
        contents[path] = Json.write(JsonValue.Obj(change(Json.parse(contents.getValue(path).toString(Charsets.UTF_8)).obj())))
            .toByteArray(Charsets.UTF_8)
        return archive(name, contents)
    }

    private fun changedFirstPad(root: Map<String, JsonValue>, change: (Map<String, JsonValue>) -> Map<String, JsonValue>): Map<String, JsonValue> {
        val pads = root.getValue("pads").arr()
        return root + ("pads" to JsonValue.Arr(listOf(JsonValue.Obj(change(pads.first().obj()))) + pads.drop(1)))
    }

    @Test
    fun `source maps survive native sharing save and repeat export with layered sparse slots`() {
        val (original, dir) = kit("Wordless sharing", 1)
        val imported = XpnImporter.import(pack(original, dir), File(temp, "received"))
        assertEquals(original.pads.associate { it.slot to it.source }, imported.kit.pads.associate { it.slot to it.source })
        assertEquals(original.pads.map { it.velocityLayers }, imported.kit.pads.map { it.velocityLayers })
        assertEquals(imported.kit, KitStore.load(imported.directory))
        val reshared = pack(imported.kit, imported.directory, "shared-again")
        val second = XpnImporter.import(reshared, File(temp, "received-again"))
        assertEquals(original.pads.associate { it.slot to it.source }, second.kit.pads.associate { it.slot to it.source })
        for (pad in original.pads) {
            assertContentEquals(File(dir, pad.sampleFile).readBytes(), File(second.directory, pad.sampleFile).readBytes())
        }
        val retained = second.kit.copy(pads = listOf(second.kit.pad(2)!!))
        val folder = KitExporter.exportProgramFolder(retained, second.directory, File(temp, "retained"))
        val notice = File(folder.directory, SourceAttribution.fileName(retained)).readText()
        for (value in original.pad(2)!!.source.values) assertTrue(value in notice, "lost source field $value")
    }

    @Test
    fun `backup restore and rebackup retain recording credits and ordinary source fields`() {
        val (original, input) = kit("Backup wordless", 2)
        val shelf = File(temp, "shelf").apply { mkdirs() }
        input.copyRecursively(File(shelf, original.name))
        val backup = KitBackup.backup(shelf, File(temp, "backup.zip"))
        val restoredRoot = File(temp, "restored")
        val restored = KitBackup.restore(backup.file, restoredRoot).kits.single()
        assertEquals(original.pads.associate { it.slot to it.source }, restored.kit.pads.associate { it.slot to it.source })
        val secondBackup = KitBackup.backup(restoredRoot, File(temp, "second-backup.zip"))
        val twice = KitBackup.restore(secondBackup.file, File(temp, "twice")).kits.single()
        assertEquals(restored.kit, twice.kit)
        val output = KitExporter.exportProgramFolder(twice.kit, twice.directory, File(temp, "backup-reexport"))
        assertTrue(File(output.directory, SourceAttribution.fileName(twice.kit)).readText().contains(source("Singer 2").getValue("attribution")))
    }

    @Test
    fun `an oversized source notice is named without costing healthy kits in the backup`() {
        val healthy = kit("Healthy source", 20)
        val oversized = kit("Oversized source", 21)
        val bad = oversized.first.copy(pads = oversized.first.pads.map { it.copy(source = mapOf("attribution" to "a".repeat(1_048_577))) })
        KitStore.save(bad, oversized.second)
        val shelf = File(temp, "bounded-shelf").apply { mkdirs() }
        for ((original, input) in listOf(healthy, oversized)) input.copyRecursively(File(shelf, original.name))
        val result = KitBackup.backup(shelf, File(temp, "bounded-backup.zip"))
        assertEquals(listOf(healthy.first.name), result.packed)
        assertEquals(setOf(bad.name), result.skipped.keys)
        assertTrue(result.skipped.getValue(bad.name).contains("source metadata"))
        val restored = KitBackup.restore(result.file, File(temp, "bounded-restored")).kits.single()
        assertEquals(healthy.first.pads.associate { it.slot to it.source }, restored.kit.pads.associate { it.slot to it.source })
    }

    @Test
    fun `native multi program packs resolve identical stems to each programs own audio and sources`() {
        val first = kit("First wordless", 3)
        val second = kit("Second wordless", 19)
        val result = PackBuilder.build(listOf(first.second, second.second), File(temp, "pack"),
            ExpansionMeta(title = "Wordless pack", identifier = "app.snipsnap.wordless.test"), withPreviews = false, asXpn = true)
        val imported = XpnImporter.importAll(result.xpn!!, File(temp, "pack-import"))
        assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
        assertEquals(2, imported.kits.size)
        for ((expected, input) in listOf(first, second)) {
            val actual = imported.kits.single { it.kit.name == expected.name }
            assertEquals(expected.pads.associate { it.slot to it.source }, actual.kit.pads.associate { it.slot to it.source })
            for (file in listOf("A02_Soft.wav", "A02_Loud.wav", "B02_Voice.wav")) {
                assertContentEquals(File(input, file).readBytes(), File(actual.directory, file).readBytes(), "${expected.name}/$file")
            }
        }
    }

    @Test
    fun `highly compressed source maps share the archive write budget across programs`() {
        val inputs = (1..3).map { index ->
            val (original, directory) = kit("Budget source $index", index + 30)
            val large = original.copy(pads = original.pads.map { pad ->
                if (pad.slot == 2) pad.copy(source = pad.source + ("modifications" to "source preparation ".repeat(4_096))) else pad
            })
            KitStore.save(large, directory)
            large to directory
        }
        val pack = PackBuilder.build(inputs.map { it.second }, File(temp, "source-budget-pack"),
            ExpansionMeta(title = "Source budget pack", identifier = "app.snipsnap.sourcebudget"), withPreviews = false, asXpn = true)
        val contents = entries(pack.xpn!!)
        val metadataBytes = inputs.map { (original, _) -> contents.getValue("Programs/${original.name}.xpm.snipsnap-source.json").size.toLong() }
        val oneKitSamples = inputs.first().second.listFiles()!!.filter { it.extension == "wav" }.sumOf { it.length() }
        val tight = XpnImporter.WriteBudget(metadataBytes.first() + oneKitSamples + metadataBytes[1] / 2)
        val destination = File(temp, "source-budget-tight")
        val refused = XpnImporter.importAll(pack.xpn!!, destination, budget = tight)
        assertEquals(listOf(inputs.first().first.name), refused.kits.map { it.kit.name })
        assertEquals(2, refused.skipped.size)
        assertTrue(refused.skipped.all { it.second.contains("source metadata") }, refused.skipped.toString())
        assertEquals(0L, tight.remaining, "the refused partial source read must remain spent for later programs")
        assertEquals(listOf(inputs.first().first.name), destination.listFiles()!!.map { it.name })

        val exact = XpnImporter.WriteBudget(metadataBytes.sum() + oneKitSamples * inputs.size)
        val complete = XpnImporter.importAll(pack.xpn!!, File(temp, "source-budget-exact"), budget = exact)
        assertTrue(complete.skipped.isEmpty(), complete.skipped.toString())
        assertEquals(3, complete.kits.size)
        assertEquals(0L, exact.remaining, "native source and WAV bytes must both debit the shared budget")
    }

    @Test
    fun `a malformed source attempt spends its read before another program is considered`() {
        val first = kit("A malformed source", 41)
        val second = kit("B valid source", 42)
        val pack = PackBuilder.build(listOf(first.second, second.second), File(temp, "malformed-budget-pack"),
            ExpansionMeta(title = "Malformed source budget", identifier = "app.snipsnap.malformedsourcebudget"), withPreviews = false, asXpn = true)
        val contents = entries(pack.xpn!!)
        val malformed = ("{ invalid source " + "a".repeat(32_768)).toByteArray()
        contents["Programs/${first.first.name}.xpm.snipsnap-source.json"] = malformed
        val secondBytes = contents.getValue("Programs/${second.first.name}.xpm.snipsnap-source.json").size
        val budget = XpnImporter.WriteBudget(malformed.size.toLong() + secondBytes / 2)
        val destination = File(temp, "malformed-budget-import")
        val refused = XpnImporter.importAll(archive("malformed-budget", contents), destination, budget = budget)
        assertTrue(refused.kits.isEmpty(), "a malformed source must not return budget for the next program")
        assertEquals(2, refused.skipped.size)
        assertEquals(0L, budget.remaining)
        assertFalse(destination.exists(), "source refusals must happen before sample staging")

        val oversized = entries(pack.xpn!!)
        oversized["Programs/${first.first.name}.xpm.snipsnap-source.json"] = ByteArray((XpnSourceMetadata.MAX_BYTES + 1).toInt()) { ' '.code.toByte() }
        val capBudget = XpnImporter.WriteBudget(XpnSourceMetadata.MAX_BYTES + 100)
        assertFailsWith<LimitedRead.TooLargeException> {
            XpnImporter.import(archive("source-entry-cap", oversized), File(temp, "source-entry-cap-import"), budget = capBudget)
        }
        assertTrue(capBudget.remaining <= 100, "a per-entry-cap refusal must retain the partial source-read debit")
    }

    @Test
    fun `stale program slot layer and file bindings refuse rather than relabel another sound`() {
        val (original, dir) = kit("Bindings", 4)
        val packed = pack(original, dir)
        val variants = linkedMapOf<String, (Map<String, JsonValue>) -> Map<String, JsonValue>>(
            "program-path" to { root -> root + ("programEntry" to JsonValue.Str("Programs/Other.xpm")) },
            "program-hash" to { root -> root + ("programSha256" to JsonValue.Str("0".repeat(64))) },
            "slot" to { root -> changedFirstPad(root) { it + ("slot" to JsonValue.Num(1.0)) } },
            "layers" to { root -> changedFirstPad(root) { it + ("samples" to JsonValue.Arr(it.getValue("samples").arr().reversed())) } },
            "traversal" to { root -> changedFirstPad(root) { pad ->
                val samples = pad.getValue("samples").arr().toMutableList()
                samples[0] = JsonValue.Obj(samples[0].obj() + ("entry" to JsonValue.Str("../../A02_Soft.wav")))
                pad + ("samples" to JsonValue.Arr(samples))
            } },
            "other-program" to { root -> changedFirstPad(root) { pad ->
                val samples = pad.getValue("samples").arr().toMutableList()
                samples[0] = JsonValue.Obj(samples[0].obj() + ("entry" to JsonValue.Str("Samples/Other/A02_Soft.wav")))
                pad + ("samples" to JsonValue.Arr(samples))
            } },
            "sample-hash" to { root -> changedFirstPad(root) { pad ->
                val samples = pad.getValue("samples").arr().toMutableList()
                samples[0] = JsonValue.Obj(samples[0].obj() + ("sha256" to JsonValue.Str("0".repeat(64))))
                pad + ("samples" to JsonValue.Arr(samples))
            } },
        )
        for ((name, change) in variants) {
            val output = File(temp, "refused-$name")
            assertFailsWith<IllegalArgumentException>(name) { XpnImporter.import(changedMetadata(packed, name, change), output) }
            assertTrue(output.listFiles().orEmpty().isEmpty(), "$name left an incorrectly credited kit or partial files")
        }
        val alteredXml = entries(packed).apply {
            val path = "Programs/${original.name}.xpm"
            this[path] = getValue(path).toString(Charsets.UTF_8).replace("<OneShot>True</OneShot>", "<OneShot>False</OneShot>").toByteArray()
            // Whitespace alone is sufficient to invalidate the exact original XML binding.
            this[path] = getValue(path) + byteArrayOf(10)
        }
        assertFailsWith<IllegalArgumentException> { XpnImporter.import(archive("edited-program", alteredXml), File(temp, "edited-import")) }
    }

    @Test
    fun `malformed unsupported duplicate and oversized metadata are bounded refusals`() {
        val (original, dir) = kit("Bounded", 5)
        val packed = pack(original, dir)
        val contents = entries(packed)
        val path = contents.keys.single { it.endsWith(".snipsnap-source.json") }
        val badDocuments = listOf(
            "{".toByteArray(),
            ByteArray((XpnSourceMetadata.MAX_BYTES + 1).toInt()) { ' '.code.toByte() },
        )
        for ((index, bytes) in badDocuments.withIndex()) {
            val target = File(temp, "bad-document-$index")
            assertFailsWith<IllegalArgumentException> {
                XpnImporter.import(archive("bad-document-$index", contents + (path to bytes)), target)
            }
            assertTrue(target.listFiles().orEmpty().isEmpty())
        }
        val unsupported = changedMetadata(packed, "unsupported") { it + ("version" to JsonValue.Num(2.0)) }
        assertFailsWith<IllegalArgumentException> { XpnImporter.import(unsupported, File(temp, "unsupported")) }
        val duplicateSlot = changedMetadata(packed, "duplicate-slot") { root ->
            val pads = root.getValue("pads").arr()
            root + ("pads" to JsonValue.Arr(pads + pads.first()))
        }
        assertFailsWith<IllegalArgumentException> { XpnImporter.import(duplicateSlot, File(temp, "duplicate-slot")) }
        val tooMuch = original.copy(pads = original.pads.map { it.copy(source = mapOf("attribution" to "a".repeat(1_048_577))) })
        val refusedExport = File(temp, "too-large.xpn")
        assertFailsWith<IllegalArgumentException> { XpnPackager.write(tooMuch, dir, refusedExport, Exporters.defaultMeta(tooMuch)) }
        assertFalse(refusedExport.exists())
    }

    @Test
    fun `only bound samples land and missing native assets never fall back to another programs file`() {
        val (original, dir) = kit("Missing native", 6)
        val contents = entries(pack(original, dir))
        val removed = contents.remove("Samples/${original.name}/A02_Soft.wav")!!
        contents["Samples/Other/A02_Soft.wav"] = removed
        contents["../../do-not-extract.txt"] = "not a sample".toByteArray()
        val output = File(temp, "missing-native-import")
        assertFailsWith<IllegalArgumentException> { XpnImporter.import(archive("missing-native", contents), output) }
        assertTrue(output.listFiles().orEmpty().isEmpty())
        assertFalse(File(temp, "do-not-extract.txt").exists())

        val plain = original.copy(pads = original.pads.map { it.copy(source = emptyMap()) })
        val noMetadata = pack(plain, dir, "plain-native")
        ZipFile(noMetadata).use { zip -> assertNull(zip.entries().toList().find { it.name.endsWith(".snipsnap-source.json") }) }
        val imported = XpnImporter.import(noMetadata, File(temp, "plain-native-import"))
        assertTrue(imported.kit.pads.all { it.source == mapOf("importedFrom" to noMetadata.name) })
    }
}
