package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Importer
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

class NacreVelocityPackTest {
    @Test
    fun `both programs deliver all forty eight replayable layers and preserve the bank through import and backup`() {
        val root = createTempDirectory("nacre-velocity-pack").toFile()
        try {
            val destination = File(root, NacreVelocityKitGenerator.KIT_NAME)
            File(destination, "recipes/obsolete.json").also { it.parentFile.mkdirs() }.writeText("stale")
            val archive = NacreVelocityKitGenerator.generate(root)
            assertFalse(File(destination, "recipes/obsolete.json").exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".nacre-velocity-kit-") })
            val data = File(destination, "SnipSnap Nacre Velocity Kit_[TrackData]")
            val kit = KitStore.load(data)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(KeySpec.parse("Cminpent"), kit.key)
            val xtd = File(destination, "SnipSnap Nacre Velocity Kit.xtd")
            val native = Mpc3Project.read(xtd)
            assertTrue(native.isTrack)
            val nativeInstruments = native.drumPrograms().single().getValue("instruments").arr()
            val nativeMap = native.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
            val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val xml = factory.newDocumentBuilder().parse(File(data, "SnipSnap Nacre Velocity Kit.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val xmlNotes = xml.getElementsByTagName("PadNote")
            val mpc2Triggers = listOf(37, 36, 42, 82, 40, 38, 46, 44, 48, 47, 45, 43, 49, 55, 51, 53)
            val midis = listOf(48, 51, 53, 55, 58, 60, 63, 65, 48, 51, 55, 60, 48, 51, 55, 60)
            val presetNames = List(8) { "Pearl Close" } + List(4) { "Coral Reply" } + List(4) { "Kelp Passage" }
            val starts = NacrePresets.all().associateBy { it.name }
            val manifest = Json.parse(File(destination, "manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals(48, manifest.getValue("sampleCount").int())
            val records = manifest.getValue("pads").arr()
            assertEquals(16, records.size)
            for ((index, pad) in kit.pads.withIndex()) {
                val hard = assertIs<NacrePatch>(PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch)
                assertEquals(starts.getValue(presetNames[index]).copy(name = pad.displayName, midi = midis[index], velocity = 1f), hard)
                assertEquals(3, hard.model)
                assertEquals(7L, hard.seed)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertTrue(pad.chain == null && pad.tuneCoarse == 0 && pad.tuneFine == 0)
                assertEquals(.707946f, pad.level)
                assertEquals(listOf(1, 42, 85), pad.velocityLayers.map { it.velStart })
                assertEquals(listOf(41, 84, 127), pad.velocityLayers.map { it.velEnd })
                val bundle = NacreVelocityRecipe.fromJsonText(pad.source.getValue("nacreVelocityLayers"))
                assertEquals(hard, bundle.hardPatch)
                val record = records[index].obj()
                assertEquals(midis[index], record.getValue("pitchMidi").int())
                assertEquals("7", record.getValue("seed").str())
                assertEquals(bundle, NacreVelocityRecipe.fromJsonValue(record.getValue("bundle")))
                assertEquals(bundle, NacreVelocityRecipe.fromJsonText(File(destination, record.getValue("bundleFile").str()).readText()))
                val layerRecords = record.getValue("layers").arr()
                assertEquals(3, layerRecords.size)
                val hashes = Json.parse(pad.source.getValue("rawVelocitySha256")).obj()
                assertEquals(pad.velocityLayers.map { it.sampleFile }.toSet(), hashes.keys)
                assertEquals(sha256(File(data, pad.sampleFile)), pad.source.getValue("rawSha256"))
                for ((zone, mapping) in pad.velocityLayers.withIndex()) {
                    val source = bundle.layers[zone]
                    val r = layerRecords[zone].obj()
                    val recipe = PadRecipe.fromJsonValue(r.getValue("savedRecipe"))
                    assertEquals(source.recipe, recipe)
                    assertNull(recipe.fx)
                    assertNull(recipe.board)
                    assertNull(recipe.pedals)
                    assertNull(recipe.terminalFadeMs)
                    assertEquals(listOf(.30f, .65f, 1f)[zone], (recipe.patch as NacrePatch).velocity)
                    assertEquals(source.samplingGain.toDouble(), r.getValue("samplingGain").num())
                    assertEquals(source.recipe, PadRecipe.fromJsonText(File(destination, r.getValue("sourceRecipe").str()).readText()))
                    val wrapper = Json.parse(File(destination, r.getValue("recipe").str()).readText()).obj()
                    assertEquals("snipsnap-nacre-velocity-layer", wrapper.getValue("format").str())
                    assertEquals(recipe, PadRecipe.fromJsonValue(wrapper.getValue("recipe")))
                    assertEquals(source.samplingGain.toDouble(), wrapper.getValue("samplingGain").num())
                    val nativeSource = recipe.render()
                    val expected = nativeSource.copy(samples = FloatArray(nativeSource.samples.size) { nativeSource.samples[it] * source.samplingGain })
                    val sample = File(data, mapping.sampleFile)
                    assertContentEquals(wavBytes(expected), sample.readBytes(), "${pad.slot}/$zone: exported source/gain replay differs")
                    if (zone == 2) {
                        assertEquals(1f, source.samplingGain)
                        assertContentEquals(wavBytes(hard.render()), sample.readBytes(), "preferred hard strike must remain the native source")
                    }
                    val info = WavInfo.read(sample)
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(1, info.channels)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(264_600L, info.frameCount)
                    assertEquals(sha256(sample), hashes.getValue(mapping.sampleFile).str())
                    assertEquals(sha256(sample), r.getValue("sampleSha256").str())
                }
                val instrument = nativeInstruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int())
                assertEquals(1.0, instrument.getValue("synthSection").obj().getValue("velocitySensitivity").num())
                assertEquals(0, instrument.getValue("whichMuteGroup").int())
                assertFalse(instrument.getValue("monophonic").bool())
                assertEquals(3, instrument.getValue("polyphony").int())
                assertTrue(instrument.getValue("muteTargets").obj().values.all { it.int() == 0 })
                val nLayers = instrument.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleFile").str().isNotBlank() }
                assertEquals(listOf(85, 42, 1), nLayers.map { it.getValue("velocityStart").int() })
                val xmlPad = xmlInstruments.item(index) as Element
                fun text(tag: String) = xmlPad.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", text("OneShot"))
                assertEquals(1f, text("VelocitySensitivity").toFloat())
                assertEquals("True", text("Mono"))
                assertEquals("1", text("Polyphony"))
                assertEquals("0", text("MuteGroup"))
                assertEquals("0", text("TuneCoarse"))
                assertEquals("0", text("TuneFine"))
                val rawXmlLayers = xmlPad.getElementsByTagName("Layer")
                val xLayers = (0 until rawXmlLayers.length).map { rawXmlLayers.item(it) as Element }
                    .filter { it.getElementsByTagName("SampleName").item(0).textContent.isNotBlank() }
                assertEquals(3, xLayers.size)
                for ((zone, mapping) in pad.velocityLayers.withIndex()) {
                    val n = nLayers.single { it.getValue("velocityStart").int() == mapping.velStart }
                    assertEquals(mapping.sampleFile, n.getValue("sampleFile").str())
                    assertEquals(mapping.velEnd, n.getValue("velocityEnd").int())
                    assertFalse(n.getValue("loop").bool())
                    assertEquals(1.0, n.getValue("volume").obj().getValue("gainCoefficient").num())
                    val slice = n.getValue("sliceInfo").obj()
                    assertEquals(0, slice.getValue("Start").int())
                    assertEquals(264_600, slice.getValue("End").int())
                    assertEquals(0, slice.getValue("LoopMode").int())
                    val x = xLayers[zone]
                    fun value(tag: String) = x.getElementsByTagName(tag).item(0).textContent
                    assertEquals(mapping.sampleStem, value("SampleName"))
                    assertEquals(mapping.velStart.toString(), value("VelStart"))
                    assertEquals(mapping.velEnd.toString(), value("VelEnd"))
                    assertEquals("264600", value("SliceEnd"))
                    assertEquals("0", value("SliceLoop"))
                    assertEquals(1f, value("Volume").toFloat())
                }
                // Resolve every velocity from the actual program fields, not the kit table.
                for (velocity in 0..127) {
                    val expectedZone = when (velocity) { 0 -> null; in 1..41 -> 0; in 42..84 -> 1; else -> 2 }
                    val selectedN = nLayers.filter { velocity in it.getValue("velocityStart").int()..it.getValue("velocityEnd").int() }
                    val selectedX = xLayers.filter { velocity in it.getElementsByTagName("VelStart").item(0).textContent.toInt()..it.getElementsByTagName("VelEnd").item(0).textContent.toInt() }
                    assertEquals(if (expectedZone == null) 0 else 1, selectedN.size)
                    assertEquals(if (expectedZone == null) 0 else 1, selectedX.size)
                    if (expectedZone != null) {
                        assertEquals(pad.velocityLayers[expectedZone].sampleFile, selectedN.single().getValue("sampleFile").str())
                        assertEquals(pad.velocityLayers[expectedZone].sampleStem, selectedX.single().getElementsByTagName("SampleName").item(0).textContent)
                    }
                }
                assertEquals(36 + index, nativeMap.getValue("value$index").int())
                val xmlNote = xmlNotes.item(index) as Element
                assertEquals(mpc2Triggers[index].toString(), xmlNote.getElementsByTagName("Note").item(0).textContent)
            }
            val html = File(destination, "index.html").readText()
            assertEquals(16, Regex("<audio ").findAll(html).count())
            assertTrue("MPC listen pending" in html)
            assertTrue("does not emulate hardware gain" in html)
            assertTrue("../SnipSnap-Nacre-Velocity-Kit.zip" in html)
            for (line in File(destination, "SHA256SUMS.txt").readLines()) {
                assertEquals(line.substringBefore("  "), sha256(File(destination, line.substringAfter("  "))))
            }
            val sources = manifest.getValue("source").obj()
            assertTrue(sources.getValue("revision").str().matches(Regex("[a-f0-9]{40}")))
            for (raw in sources.getValue("files").arr()) {
                val source = raw.obj()
                assertEquals(source.getValue("sha256").str(), sha256(File(destination, source.getValue("path").str())))
            }
            val packaged = destination.walkTopDown().filter { it.isFile }.associateBy { "${destination.name}/${it.relativeTo(destination).invariantSeparatorsPath}" }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(packaged.keys, entries.map { it.name }.toSet())
                assertEquals(48, entries.count { it.name.endsWith(".wav") })
                assertFalse(entries.any { ".." in it.name.split('/') || it.name.endsWith(".zip") })
                for (entry in entries) assertContentEquals(packaged.getValue(entry.name).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
            }
            assertEquals(sha256(archive), File(root, "${NacreVelocityKitGenerator.ARCHIVE_NAME}.sha256").readText().substringBefore(' '))
            val bytes = archive.readBytes()
            assertContentEquals(bytes, NacreVelocityKitGenerator.generate(root).readBytes())

            val xmlImport = XpnImporter.importAll(archive, File(root, "xml-import"))
            assertTrue(xmlImport.skipped.isEmpty(), xmlImport.skipped.toString())
            val xmlLanded = xmlImport.kits.single()
            assertImported(kit, data, KitStore.load(xmlLanded.directory), xmlLanded.directory)
            val nativeImport = Mpc3Importer.import(xtd, File(root, "native-import"))
            assertImported(kit, data, nativeImport.kit, nativeImport.directory)
            val backup = File(root, "backup.zip")
            val saved = KitBackup.backup(File(root, "xml-import"), backup)
            assertTrue(saved.skipped.isEmpty(), saved.skipped.toString())
            val restored = KitBackup.restore(backup, File(root, "backup-import"))
            assertTrue(restored.skipped.isEmpty(), restored.skipped.toString())
            val restoredKit = restored.kits.single()
            // XPN backup retains the complete inline bank and all sample bytes. The
            // generic XPN format does not rebuild ordinary recipe/key/listening labels.
            val backedUp = KitStore.load(restoredKit.directory)
            for (original in kit.pads) {
                val pad = requireNotNull(backedUp.pad(original.slot))
                assertEquals(original.source.getValue("nacreVelocityLayers"), pad.source.getValue("nacreVelocityLayers"))
                assertEquals(original.source.getValue("rawVelocitySha256"), pad.source.getValue("rawVelocitySha256"))
                assertEquals(original.velocityLayers.map { Triple(it.sampleFile, it.velStart, it.velEnd) },
                    pad.velocityLayers.map { Triple(it.sampleFile, it.velStart, it.velEnd) })
                val bank = NacreVelocityRecipe.fromJsonText(pad.source.getValue("nacreVelocityLayers"))
                assertEquals(3, bank.layers.size)
                for (layer in original.velocityLayers) assertContentEquals(File(data, layer.sampleFile).readBytes(), File(restoredKit.directory, layer.sampleFile).readBytes())
            }

            // Both soft and middle audio must be fingerprint-bound, including valid ZIP CRCs.
            for (zone in 0..1) {
                val altered = File(root, "changed-zone-$zone.zip")
                val changedPath = "${destination.name}/${data.name}/${kit.pads.first().velocityLayers[zone].sampleFile}"
                ZipFile(archive).use { input -> ZipOutputStream(altered.outputStream()).use { output ->
                    for (entry in input.entries().asSequence()) {
                        output.putNextEntry(ZipEntry(entry.name))
                        val content = input.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == changedPath) content[content.lastIndex] = (content.last().toInt() xor 1).toByte()
                        output.write(content); output.closeEntry()
                    }
                } }
                val refusedRoot = File(root, "refused-$zone")
                val refused = XpnImporter.importAll(altered, refusedRoot)
                assertTrue(refused.kits.isEmpty(), "altered zone $zone must not receive unchanged edit history")
                assertEquals(1, refused.skipped.size)
                assertTrue("checksum" in refused.skipped.single().second.lowercase(), refused.skipped.toString())
                assertTrue(KitStore.list(refusedRoot).isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    private fun assertImported(expected: Kit, source: File, actual: Kit, directory: File) {
        assertEquals(expected.key, actual.key)
        for (original in expected.pads) {
            val pad = requireNotNull(actual.pad(original.slot))
            assertEquals(original.recipe, pad.recipe)
            assertEquals(original.velocityLayers, pad.velocityLayers)
            assertEquals(original.source.getValue("nacreVelocityLayers"), pad.source.getValue("nacreVelocityLayers"))
            assertEquals(original.source.getValue("rawVelocitySha256"), pad.source.getValue("rawVelocitySha256"))
            assertTrue(pad.oneShot)
            assertEquals(0, pad.muteGroup)
            val bundle = NacreVelocityRecipe.fromJsonText(pad.source.getValue("nacreVelocityLayers"))
            assertEquals(3, bundle.layers.size)
            for (layer in original.velocityLayers) assertContentEquals(File(source, layer.sampleFile).readBytes(), File(directory, layer.sampleFile).readBytes())
        }
    }

    private fun wavBytes(snip: Snip) = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
