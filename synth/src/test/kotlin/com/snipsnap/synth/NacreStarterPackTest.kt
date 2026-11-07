package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.XpnImporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NacreStarterPackTest {
    @Test
    fun `the downloadable pack preserves native performances full endings and editable history in both MPC formats`() {
        val root = createTempDirectory("nacre-starter-pack").toFile()
        try {
            val destination = File(root, NacreKitGenerator.KIT_NAME)
            File(destination, "recipes/obsolete.json").also { it.parentFile.mkdirs() }.writeText("stale")
            val archive = NacreKitGenerator.generate(root)
            assertFalse(File(destination, "recipes/obsolete.json").exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".nacre-kit-") })
            val data = File(destination, "SnipSnap Nacre Kit_[TrackData]")
            val kit = KitStore.load(data)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(KeySpec.parse("Cminpent"), kit.key)
            val native = Mpc3Project.read(File(destination, "SnipSnap Nacre Kit.xtd"))
            assertTrue(native.isTrack)
            assertEquals(listOf("SnipSnap Nacre Kit"), native.trackNames)
            val nativeInstruments = native.drumPrograms().single().getValue("instruments").arr()
            val nativeMap = native.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
            val xmlFactory = DocumentBuilderFactory.newInstance()
            xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = xmlFactory.newDocumentBuilder().parse(File(data, "SnipSnap Nacre Kit.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val xmlNotes = xml.getElementsByTagName("PadNote")
            // MPC 2's drum mapping is deliberately not the native track's chromatic map.
            val mpc2Triggers = listOf(37, 36, 42, 82, 40, 38, 46, 44, 48, 47, 45, 43, 49, 55, 51, 53)
            val names = List(8) { "Pearl Close" } + listOf("Coral Reply", "Kelp Passage", "Pearl Close", "Pearl Close",
                "Coral Reply", "Coral Reply", "Kelp Passage", "Kelp Passage")
            val midis = listOf(48, 51, 53, 55, 58, 60, 63, 65) + List(8) { 48 }
            val velocities = List(10) { .65f } + listOf(.30f, 1f, .30f, 1f, .30f, 1f)
            val starts = NacrePresets.all().associateBy { it.name }
            val manifest = Json.parse(File(destination, "manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals("PadRecipe.render", manifest.getValue("renderSource").str())
            val records = manifest.getValue("pads").arr()
            assertEquals(16, records.size)
            for ((index, pad) in kit.pads.withIndex()) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<NacrePatch>(recipe.patch)
                val start = starts.getValue(names[index])
                assertEquals(start.copy(name = pad.displayName, midi = midis[index], velocity = velocities[index]), patch)
                assertEquals(3, patch.model)
                assertEquals(7L, patch.seed)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertEquals(.707946f, pad.level)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(pad.chain)
                assertNull(recipe.fx)
                assertNull(recipe.board)
                assertNull(recipe.pedals)
                assertNull(recipe.terminalFadeMs)
                val reference = start.copy(midi = midis[index], velocity = velocities[index]).render()
                val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, reference) }.toByteArray()
                val sample = File(data, pad.sampleFile)
                assertContentEquals(expectedWav, sample.readBytes(), "${pad.slot}: samples differ from the native preset performance")
                val info = WavInfo.read(sample)
                assertEquals(44_100, info.sampleRate)
                assertEquals(1, info.channels)
                assertEquals(24, info.bitsPerSample)
                assertEquals(264_600L, info.frameCount)
                assertEquals(sha256(sample), pad.source.getValue("rawSha256"))
                val record = records[index].obj()
                assertEquals(midis[index], record.getValue("pitchMidi").int())
                assertEquals("7", record.getValue("seed").str())
                assertEquals(velocities[index].toDouble(), record.getValue("velocity").num())
                assertEquals(recipe, PadRecipe.fromJsonValue(record.getValue("savedRecipe")))
                assertEquals(recipe, PadRecipe.fromJsonText(File(destination, record.getValue("recipe").str()).readText()))
                assertEquals(sha256(sample), record.getValue("sampleSha256").str())
                assertEquals(.707946f.toDouble(), record.getValue("padMixerLevel").num())
                assertEquals(1, record.getValue("exportGain").int())

                // ReadBack skips one-shot and slice settings, so examine both files directly.
                val instrument = nativeInstruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int())
                assertEquals(0, instrument.getValue("whichMuteGroup").int())
                assertTrue(instrument.getValue("muteTargets").obj().values.all { it.int() == 0 })
                assertFalse(instrument.getValue("monophonic").bool())
                assertEquals(3, instrument.getValue("polyphony").int())
                val layer = instrument.getValue("layersv").arr().first().obj()
                assertEquals(pad.sampleFile, layer.getValue("sampleFile").str())
                assertFalse(layer.getValue("loop").bool())
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(0, slice.getValue("Start").int())
                assertEquals(264_600, slice.getValue("End").int())
                assertEquals(0, slice.getValue("LoopMode").int())
                assertEquals(36 + index, nativeMap.getValue("value$index").int())
                val xmlPad = xmlInstruments.item(index) as Element
                fun text(tag: String) = xmlPad.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", text("OneShot"))
                assertEquals("True", text("Mono"))
                assertEquals("1", text("Polyphony"))
                assertEquals("0", text("MuteGroup"))
                assertEquals("0", text("SliceLoop"))
                assertEquals("264600", text("SliceEnd"))
                assertEquals(pad.sampleStem, text("SampleName"))
                assertEquals("0", text("TuneCoarse"))
                assertEquals("0", text("TuneFine"))
                assertTrue((1..4).all { text("MuteTarget$it") == "0" })
                val xmlNote = xmlNotes.item(index) as Element
                assertEquals(mpc2Triggers[index].toString(), xmlNote.getElementsByTagName("Note").item(0).textContent)
                val triggers = record.getValue("triggerMidi").obj()
                assertEquals(mpc2Triggers[index], triggers.getValue("mpc2").int())
                assertEquals(36 + index, triggers.getValue("mpc3").int())
            }
            val html = File(destination, "index.html").readText()
            assertEquals(16, Regex("<audio ").findAll(html).count())
            assertTrue("MPC trial pending" in html)
            assertTrue("../SnipSnap-Nacre-Kit.zip" in html)
            assertTrue("MPC 2 repeats on one pad may restart" in html)
            for (line in File(destination, "SHA256SUMS.txt").readLines()) {
                val expected = line.substringBefore("  ")
                val path = line.substringAfter("  ")
                assertEquals(expected, sha256(File(destination, path)), "$path: package checksum differs")
            }
            val source = manifest.getValue("source").obj()
            assertTrue(source.getValue("revision").str().matches(Regex("[a-f0-9]{40}")))
            for (entry in source.getValue("files").arr()) {
                val file = entry.obj()
                assertEquals(file.getValue("sha256").str(), sha256(File(destination, file.getValue("path").str())))
            }
            val files = destination.walkTopDown().filter { it.isFile }.associateBy {
                "SnipSnap Nacre Kit/${it.relativeTo(destination).invariantSeparatorsPath}"
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(files.keys, entries.map { it.name }.toSet())
                assertEquals(16, entries.count { it.name.endsWith(".wav") })
                assertFalse(entries.any { it.name.endsWith(".zip") || "obsolete" in it.name || ".." in it.name.split('/') })
                for (entry in entries) assertContentEquals(files.getValue(entry.name).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
            }
            assertEquals(sha256(archive), File(root, "SnipSnap-Nacre-Kit.zip.sha256").readText().substringBefore(' '))
            val originalArchive = archive.readBytes()
            // Same source and same performances must rebuild the complete download identically.
            assertContentEquals(originalArchive, NacreKitGenerator.generate(root).readBytes())

            val imported = XpnImporter.importAll(archive, File(root, "imported"))
            assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
            val landed = imported.kits.single()
            val landedKit = KitStore.load(landed.directory)
            assertEquals(kit.key, landedKit.key)
            for (original in kit.pads) {
                val pad = requireNotNull(landedKit.pad(original.slot))
                assertEquals(original.recipe, pad.recipe, "${pad.slot}: imported audio lost its editable history")
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertEquals(original.source["rawSha256"], pad.source["rawSha256"])
                assertContentEquals(File(data, original.sampleFile).readBytes(), File(landed.directory, pad.sampleFile).readBytes())
            }
            val altered = File(root, "changed-sample.zip")
            val changedPath = "SnipSnap Nacre Kit/${data.name}/${kit.pads.first().sampleFile}"
            ZipFile(archive).use { input ->
                ZipOutputStream(altered.outputStream()).use { output ->
                    for (entry in input.entries().asSequence()) {
                        output.putNextEntry(ZipEntry(entry.name))
                        val bytes = input.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == changedPath) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
            val refusedDir = File(root, "refused")
            val refused = XpnImporter.importAll(altered, refusedDir)
            assertTrue(refused.kits.isEmpty(), "changed audio must not receive the old recipe")
            assertEquals(1, refused.skipped.size)
            assertTrue("sample checksum disagrees at pad 1" in refused.skipped.single().second)
            assertTrue(KitStore.list(refusedDir).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
