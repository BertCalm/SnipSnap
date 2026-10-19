package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAttributionTest {
    private val temp = Files.createTempDirectory("source-attribution-").toFile()
    private val notice = linkedMapOf(
        "origin" to "confluence-wordless-bank",
        "bankId" to "wordless-starter-v1",
        "attribution" to "VocalSet: A Singing Voice Dataset — Julia Wilkins, Prem Seetharaman, Alison Wahl, Bryan Pardo (2018).",
        "license" to "CC-BY-4.0",
        "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
        "sourceUrl" to "https://doi.org/10.5281/zenodo.1193957",
        "modifications" to "Cropped, constant gain, ensemble scheduling and optional room rendering.",
        "assemblyRecipe" to "{\"engine\":\"CONFLUENCE_ASSEMBLY_PROTOTYPE\",\"seed\":17}",
    )

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kit(name: String, suffix: String = "A", source: Map<String, String> = notice): Pair<Kit, File> {
        val dir = File(temp, "input-$suffix").apply { mkdirs() }
        val file = "A01_Voice_$suffix.wav"
        val audio = Snip(FloatArray(8_820) { i -> (.2 * sin(2 * PI * 220 * i / WavWriter.MPC_SAMPLE_RATE)).toFloat() },
            1, WavWriter.MPC_SAMPLE_RATE)
        WavWriter.write(File(dir, file), audio)
        return Kit(name, listOf(KitPad(1, file, "LOW AND AIR", DrumClass.LOOP, source = source))) to dir
    }

    @Test
    fun `a retained single licensed pad carries its complete notice through every sample export`() {
        val (original, dir) = kit("One retained voice")
        KitStore.save(original, dir)
        val loaded = KitStore.load(dir)
        assertEquals(notice, loaded.pads.single().source)
        val expected = requireNotNull(SourceAttribution.render(loaded))
        for (value in notice.values) assertTrue(expected.contains(value), "the notice lost $value")

        for (format in ExportFormat.entries.filter { it != ExportFormat.MIDI }) {
            val output = File(temp, "export-${format.id}")
            val result = Exporters.export(format, loaded, dir, output)
            val actual = if (format == ExportFormat.XPN) {
                ZipFile(result.primary).use { zip ->
                    val entry = requireNotNull(zip.getEntry(SourceAttribution.fileName(loaded)))
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } else {
                val files = output.walkTopDown().filter { it.isFile && it.name == SourceAttribution.fileName(loaded) }.toList()
                assertEquals(1, files.size, "$format must retain the single pad's notice alongside its samples")
                files.single().readText()
            }
            assertEquals(expected, actual, "$format changed or omitted the source notice")
            assertTrue(ExportReadBack.verify(loaded, result).none { it.severity == Severity.FAIL },
                "$format's notice must not make its program unreadable")
        }
    }

    @Test
    fun `shared project samples keep each kit notice and clear only its own stale notice`() {
        val (first, firstDir) = kit("First voice", "A")
        val (second, secondDir) = kit("Second voice", "B", notice + ("attribution" to "Second recorded source, credited separately."))
        val shared = File(temp, "project-samples")
        Mpc3Exporter.stageTrack(first, firstDir, shared)
        Mpc3Exporter.stageTrack(second, secondDir, shared)
        val firstNotice = File(shared, SourceAttribution.fileName(first))
        val secondNotice = File(shared, SourceAttribution.fileName(second))
        assertEquals(SourceAttribution.render(first), firstNotice.readText())
        val secondText = secondNotice.readText()
        assertEquals(SourceAttribution.render(second), secondText)

        val uncredited = first.copy(pads = first.pads.map { it.copy(source = emptyMap()) })
        Mpc3Exporter.stageTrack(uncredited, firstDir, shared)
        assertFalse(firstNotice.exists(), "an overwritten kit must not retain a source it no longer credits")
        assertEquals(secondText, secondNotice.readText(), "another track's notice must remain intact")
    }

    @Test
    fun `notice and credited archive bytes are stable across source map and pad ordering`() {
        val (base, dir) = kit("Stable credits")
        File(dir, "A02_Voice_A.wav").writeBytes(File(dir, base.pads.single().sampleFile).readBytes())
        val first = base.copy(pads = base.pads + base.pads.single().copy(slot = 2, sampleFile = "A02_Voice_A.wav", displayName = "SECOND VOICE"))
        val reversed = first.copy(pads = first.pads.reversed().map { it.copy(source = it.source.entries.reversed().associate { entry -> entry.toPair() }) })
        assertEquals(SourceAttribution.render(first), SourceAttribution.render(reversed))
        val a = XpnPackager.write(first, dir, File(temp, "first.xpn"), Exporters.defaultMeta(first))
        val b = XpnPackager.write(reversed, dir, File(temp, "second.xpn"), Exporters.defaultMeta(reversed))
        assertContentEquals(a.readBytes(), b.readBytes())
    }

    @Test
    fun `uncredited kits add no notice and sample result lists remain audio only`() {
        val (plain, dir) = kit("Plain kit", source = emptyMap())
        assertNull(SourceAttribution.render(plain))
        val folder = KitExporter.exportProgramFolder(plain, dir, File(temp, "plain-folder"))
        assertEquals(1, folder.samples.size)
        assertTrue(folder.samples.all { it.extension == "wav" })
        assertFalse(File(folder.directory, SourceAttribution.fileName(plain)).exists())
        val xpn = XpnPackager.write(plain, dir, File(temp, "plain.xpn"), Exporters.defaultMeta(plain))
        ZipFile(xpn).use { assertNull(it.getEntry(SourceAttribution.fileName(plain))) }

        val credited = plain.copy(pads = plain.pads.map { it.copy(source = notice) })
        val withNotice = KitExporter.exportProgramFolder(credited, dir, File(temp, "credited-folder"))
        assertEquals(1, withNotice.samples.size, "the audio-only result must not count the text notice as a sample")
        assertTrue(File(withNotice.directory, SourceAttribution.fileName(credited)).isFile)
    }
}
