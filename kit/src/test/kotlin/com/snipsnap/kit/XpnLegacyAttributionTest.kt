package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.LimitedRead
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XpnLegacyAttributionTest {
    private val temp = Files.createTempDirectory("xpn-legacy-attribution-").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(name: String, id: String): Pair<Kit, File> {
        val directory = File(temp, "input-$id").apply { mkdirs() }
        val voiceFile = "${id}_Voice.wav"
        val ordinaryFile = "${id}_Ordinary.wav"
        for ((file, hz) in listOf(voiceFile to 220, ordinaryFile to 310)) {
            val snip = Snip(FloatArray(4_410) { i -> (.25 * sin(2 * PI * hz * i / WavWriter.MPC_SAMPLE_RATE)).toFloat() },
                1, WavWriter.MPC_SAMPLE_RATE)
            WavWriter.write(File(directory, file), snip)
        }
        val source = linkedMapOf(
            "attribution" to "$id recorded voice — original performers credited here.",
            "license" to "CC-BY-4.0",
            "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
            "sourceUrl" to "https://example.com/recordings/$id",
            "modifications" to "Selected complete phrase; constant gain.\nA second retained notice line.",
        )
        val kit = Kit(name, listOf(
            KitPad(1, voiceFile, "RECORDED VOICE", DrumClass.LOOP, source = source),
            KitPad(17, ordinaryFile, "ORDINARY PAD", DrumClass.SNARE),
        ))
        KitStore.save(kit, directory)
        return kit to directory
    }

    private fun packed(kit: Kit, directory: File, id: String): File =
        XpnPackager.write(kit, directory, File(temp, "$id.xpn"), Exporters.defaultMeta(kit))

    private fun entries(file: File): LinkedHashMap<String, ByteArray> = ZipFile(file).use { zip ->
        linkedMapOf<String, ByteArray>().also { result ->
            for (entry in zip.entries().asSequence().filter { !it.isDirectory }) {
                result[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    }

    /** Remove only the new source sidecars, preserving the exact older native layout and notice. */
    private fun legacyEntries(file: File): LinkedHashMap<String, ByteArray> = entries(file).also { result ->
        result.keys.removeAll { it.endsWith(".snipsnap-source.json") }
    }

    private fun archive(id: String, entries: Map<String, ByteArray>): File = File(temp, "$id.xpn").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `legacy root notice survives keeping only an ordinary pad and exporting again including sanitized kit names`() {
        for ((index, name) in listOf("Legacy voice", "A__B").withIndex()) {
            val (kit, directory) = buildKit(name, "root$index")
            val notice = assertNotNull(SourceAttribution.render(kit))
            val oldEntries = legacyEntries(packed(kit, directory, "root-current-$index"))
            assertTrue("Programs/$name.xpm" in oldEntries)
            assertContentEquals(notice.toByteArray(Charsets.UTF_8), oldEntries.getValue(SourceAttribution.fileName(kit)))
            if (name == "A__B") assertEquals("A_B-source-attribution.txt", SourceAttribution.fileName(kit))

            val oldArchive = archive("root-old-$index", oldEntries)
            val imported = XpnImporter.import(oldArchive, File(temp, "root-import-$index"))
            for (pad in imported.kit.pads) {
                assertEquals(mapOf("importedFrom" to oldArchive.name, "attribution" to notice), pad.source)
                assertContentEquals(File(directory, pad.sampleFile).readBytes(), File(imported.directory, pad.sampleFile).readBytes())
            }
            assertEquals(imported.kit, KitStore.load(imported.directory), "coarse legacy attribution is persisted in kit.json")

            // The old notice has no per-pad bindings. An unrelated retained pad must still carry it.
            val retained = imported.kit.copy(pads = listOf(assertNotNull(imported.kit.pad(17))))
            assertEquals(DrumClass.UNKNOWN, retained.pads.single().drumClass)
            val reexport = packed(retained, imported.directory, "root-retained-$index")
            val exportedNotice = entries(reexport).getValue(SourceAttribution.fileName(retained)).toString(Charsets.UTF_8)
            assertTrue(exportedNotice.contains(notice), "the full original notice survives retaining a single ordinary pad")
            val reimported = XpnImporter.import(reexport, File(temp, "root-reimport-$index"))
            assertEquals(listOf(17), reimported.kit.pads.map { it.slot })
            assertEquals(notice, reimported.kit.pads.single().source["attribution"])
        }
    }

    @Test
    fun `older pack notices beside each programs samples retain their own credits`() {
        val inputs = listOf(buildKit("First legacy voice", "first"), buildKit("Second legacy voice", "second"))
        val pack = PackBuilder.build(inputs.map { it.second }, File(temp, "old-pack"),
            ExpansionMeta(title = "Legacy pack", identifier = "app.snipsnap.legacypack", description = "Two older credited programs"),
            withPreviews = false, asXpn = true)
        val oldEntries = legacyEntries(assertNotNull(pack.xpn))
        for ((kit, _) in inputs) {
            assertTrue("Samples/${kit.name}/${SourceAttribution.fileName(kit)}" in oldEntries)
            assertFalse(SourceAttribution.fileName(kit) in oldEntries)
        }
        val imported = XpnImporter.importAll(archive("old-multiprogram-pack", oldEntries), File(temp, "old-pack-import"))
        assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
        assertEquals(inputs.map { it.first.name }.toSet(), imported.kits.map { it.kit.name }.toSet())
        for ((original, directory) in inputs) {
            val result = imported.kits.single { it.kit.name == original.name }
            val notice = assertNotNull(SourceAttribution.render(original))
            for (pad in result.kit.pads) {
                assertEquals(notice, pad.source["attribution"])
                assertContentEquals(File(directory, pad.sampleFile).readBytes(), File(result.directory, pad.sampleFile).readBytes())
            }
            val output = packed(result.kit, result.directory, "reexport-${original.name}")
            assertTrue(entries(output).getValue(SourceAttribution.fileName(result.kit)).toString(Charsets.UTF_8).contains(notice))
        }
    }

    @Test
    fun `foreign root and nested programs ignore native looking notices and extract only their samples`() {
        val (kit, directory) = buildKit("Foreign voice", "foreign")
        val native = legacyEntries(packed(kit, directory, "foreign-native"))
        for ((index, programPath) in listOf("${kit.name}.xpm", "Vendor/Programs/${kit.name}.xpm").withIndex()) {
            val foreign = LinkedHashMap(native)
            val program = assertNotNull(foreign.remove("Programs/${kit.name}.xpm"))
            foreign[programPath] = program
            foreign["unrelated-source-attribution.txt"] = "SOURCE ATTRIBUTION\n${kit.name}\n\nUnrelated credits".toByteArray()
            foreign["../do-not-extract.txt"] = "never extract me".toByteArray()
            val input = archive("foreign-layout-$index", foreign)
            val result = XpnImporter.import(input, File(temp, "foreign-import-$index"))
            for (pad in result.kit.pads) assertEquals(mapOf("importedFrom" to input.name), pad.source)
            assertEquals(kit.pads.map { it.sampleFile }.toSet() + KitStore.FILE_NAME, result.directory.list()!!.toSet())
            assertFalse(File(temp, "do-not-extract.txt").exists())
            assertFalse(File(result.directory.parentFile, "do-not-extract.txt").exists())
        }
    }

    @Test
    fun `unrecognized text at the native notice filename remains ordinary foreign content`() {
        val (kit, directory) = buildKit("Native filename", "unrecognized")
        val oldEntries = legacyEntries(packed(kit, directory, "unrecognized-current"))
        oldEntries[SourceAttribution.fileName(kit)] = "Vendor credits without the SnipSnap header".toByteArray()
        val input = archive("unrecognized-old", oldEntries)
        val imported = XpnImporter.import(input, File(temp, "unrecognized-import"))
        for (pad in imported.kit.pads) assertEquals(mapOf("importedFrom" to input.name), pad.source)
        assertFalse(File(imported.directory, SourceAttribution.fileName(kit)).exists())
    }

    @Test
    fun `shared budget includes the legacy attribution retained separately on every pad before staging`() {
        val (kit, directory) = buildKit("Repeated notice", "repeated")
        val oldEntries = legacyEntries(packed(kit, directory, "repeated-current"))
        val sampleBytes = kit.pads.sumOf { File(directory, it.sampleFile).length() }
        val noticePath = SourceAttribution.fileName(kit)
        // Make one notice larger than all audio, so the repeated attribution alone exceeds the
        // tight budget before sample staging even though one plaintext notice plus all WAVs fits.
        val notice = oldEntries.getValue(noticePath) + "Additional original source detail: ".toByteArray() +
            ByteArray((sampleBytes + 1_024).toInt()) { 'a'.code.toByte() }
        oldEntries[noticePath] = notice
        val input = archive("repeated-old", oldEntries)
        val tight = XpnImporter.WriteBudget(sampleBytes + notice.size)
        val refused = File(temp, "repeated-refused")
        assertFailsWith<LimitedRead.TooLargeException> { XpnImporter.import(input, refused, budget = tight) }
        assertFalse(refused.exists(), "repeated metadata must be refused before any kit is staged")
        assertTrue(tight.max - tight.remaining >= notice.size, "a failed attempt must still debit the recognized notice it read")

        val roomy = XpnImporter.WriteBudget(sampleBytes + notice.size.toLong() * (kit.pads.size + 2) + 65_536)
        val imported = XpnImporter.import(input, File(temp, "repeated-import"), budget = roomy)
        for (pad in imported.kit.pads) {
            assertEquals(notice.toString(Charsets.UTF_8), pad.source["attribution"])
            assertContentEquals(File(directory, pad.sampleFile).readBytes(), File(imported.directory, pad.sampleFile).readBytes())
        }
        assertTrue(roomy.max - roomy.remaining >= sampleBytes + notice.size.toLong() * kit.pads.size,
            "the budget accounts for every retained attribution as well as the audio")
    }

    @Test
    fun `unrecognized notice text and foreign program layouts do not consume the exact sample budget`() {
        val (kit, directory) = buildKit("Foreign budget", "foreign-budget")
        val original = legacyEntries(packed(kit, directory, "foreign-budget-current"))
        val sampleBytes = kit.pads.sumOf { File(directory, it.sampleFile).length() }
        val unknownText = LinkedHashMap(original).apply {
            this[SourceAttribution.fileName(kit)] = "Vendor notice without a recognized native header".toByteArray() +
                ByteArray((sampleBytes * 2).toInt()) { 'b'.code.toByte() }
        }
        val foreignLayout = LinkedHashMap(original).apply {
            this["${kit.name}.xpm"] = assertNotNull(remove("Programs/${kit.name}.xpm"))
        }
        for ((index, contents) in listOf(unknownText, foreignLayout).withIndex()) {
            val input = archive("foreign-exact-budget-$index", contents)
            val budget = XpnImporter.WriteBudget(sampleBytes)
            val imported = XpnImporter.import(input, File(temp, "foreign-exact-import-$index"), budget = budget)
            assertEquals(0L, budget.remaining, "only the exact WAV bytes spend this budget")
            for (pad in imported.kit.pads) {
                assertEquals(mapOf("importedFrom" to input.name), pad.source)
                assertContentEquals(File(directory, pad.sampleFile).readBytes(), File(imported.directory, pad.sampleFile).readBytes())
            }
        }
    }

    @Test
    fun `identical root and sample notices agree while conflicting native notices refuse before landing`() {
        val (kit, directory) = buildKit("Conflicting voice", "conflict")
        val oldEntries = legacyEntries(packed(kit, directory, "conflict-current"))
        val rootNotice = oldEntries.getValue(SourceAttribution.fileName(kit))
        val nestedPath = "Samples/${kit.name}/${SourceAttribution.fileName(kit)}"
        oldEntries[nestedPath] = rootNotice
        val agreeing = XpnImporter.import(archive("agreeing-old", oldEntries), File(temp, "agreeing-import"))
        assertEquals(rootNotice.toString(Charsets.UTF_8), agreeing.kit.pads.first().source["attribution"])

        oldEntries[nestedPath] = rootNotice + "A conflicting additional notice line.\n".toByteArray()
        val destination = File(temp, "conflict-import")
        val budget = XpnImporter.WriteBudget(1024L * 1024)
        val failure = assertFailsWith<IllegalArgumentException> {
            XpnImporter.import(archive("conflicting-old", oldEntries), destination, budget = budget)
        }
        assertTrue(failure.message.orEmpty().contains("conflicting source attribution"))
        assertTrue(budget.remaining < budget.max, "a rejected conflicting native notice still spends its shared read budget")
        assertFalse(File(destination, kit.name).exists(), "conflicting attribution must not land a partially imported kit")
    }

    @Test
    fun `two native programs sharing a sanitized root notice name refuse ambiguous legacy attribution`() {
        val (kit, directory) = buildKit("A__B", "ambiguous")
        val oldEntries = legacyEntries(packed(kit, directory, "ambiguous-current"))
        val program = oldEntries.getValue("Programs/A__B.xpm").toString(Charsets.UTF_8)
        oldEntries["Programs/A_B.xpm"] = program.replace("<ProgramName>A__B</ProgramName>", "<ProgramName>A_B</ProgramName>").toByteArray(Charsets.UTF_8)
        assertTrue(oldEntries.getValue("Programs/A_B.xpm").toString(Charsets.UTF_8).contains("<ProgramName>A_B</ProgramName>"))
        val destination = File(temp, "ambiguous-import")
        val result = XpnImporter.importAll(archive("ambiguous-old", oldEntries), destination)
        assertTrue(result.kits.isEmpty())
        assertEquals(setOf("Programs/A__B.xpm", "Programs/A_B.xpm"), result.skipped.map { it.first }.toSet())
        assertTrue(result.skipped.all { it.second.contains("ambiguous source attribution") }, result.skipped.toString())
        assertFalse(destination.exists(), "ambiguous notices are rejected before any kit is staged")
    }
}
