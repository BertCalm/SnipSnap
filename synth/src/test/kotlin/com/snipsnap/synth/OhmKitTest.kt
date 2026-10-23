package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.XpnImporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
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

class OhmKitTest {
    @Test
    fun `the sixteen native notes preserve factory recipes complete audio and one-shot playback in both MPC formats`() {
        val root = createTempDirectory("ohm-kit").toFile()
        try {
            // A regeneration must not silently package leftovers from a previous export.
            val destination = File(root, OhmKitGenerator.KIT_NAME)
            File(destination, "recipes/obsolete.json").also { it.parentFile.mkdirs() }.writeText("stale")
            File(destination, "unrelated.wav").writeText("stale")
            val archive = OhmKitGenerator.generate(root)
            val data = File(destination, "${OhmKitGenerator.KIT_NAME}_[TrackData]")
            val kit = KitStore.load(data)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(KeySpec.parse("Cminpent"), kit.key)
            assertFalse(Preflight.check(kit, data).blocked())
            assertFalse(File(destination, "recipes/obsolete.json").exists())
            assertFalse(File(destination, "unrelated.wav").exists())
            val project = Mpc3Project.read(File(destination, "${kit.name}.xtd"))
            assertTrue(project.isTrack)
            assertEquals(listOf(kit.name), project.trackNames)
            val nativeInstruments = (project.drumPrograms().single().getValue("instruments") as JsonValue.Arr).items
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(File(data, "${kit.name}.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val names = List(8) { "Solo" } + listOf("Argument", "Communion", "Quiet Company", "Gather",
                "Argument", "Communion", "Quiet Company", "Gather")
            val midis = listOf(48, 51, 53, 55, 58, 60, 63, 65, 60, 60, 60, 60, 48, 48, 48, 72)
            val presets = OhmPresets.all().associateBy { it.name }
            val patches = kit.pads.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<OhmPatch>(recipe.patch)
                val original = presets.getValue(names[index])
                val displayName = "${original.name} ${Scales.nameOf(midis[index])}"
                val expected = if (midis[index] == original.midi) original
                    else original.copy(name = displayName, midi = midis[index])
                assertEquals(expected.toJsonValue(), patch.toJsonValue(), "${pad.slot}: accepted performance changed")
                assertEquals(displayName, pad.displayName)
                assertEquals(original.name, pad.source["preset"])
                assertEquals(2, patch.model)
                assertEquals(.8f, patch.velocity)
                assertEquals(731, patch.seed)
                assertEquals(original.gesture, patch.gesture)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(pad.chain)
                assertNull(recipe.fx)
                assertFalse(recipe.alias)

                val replay = recipe.render()
                assertEquals(1, replay.channels)
                assertEquals(44_100, replay.sampleRate)
                assertTrue(replay.samples.all { it.isFinite() })
                assertTrue(replay.samples.last() == 0f)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay) }.toByteArray()
                val sample = File(data, pad.sampleFile)
                assertContentEquals(encoded, sample.readBytes(), "${pad.slot}: native sample gain, pitch or full tail changed")
                assertEquals(sha256(sample), pad.source["rawSha256"], "${pad.slot}: editable export fingerprint differs")
                val info = WavInfo.read(sample)
                assertEquals(24, info.bitsPerSample)
                assertEquals(replay.frameCount.toLong(), info.frameCount)
                val savedRecipe = File(destination, "recipes/A%02d.json".format(java.util.Locale.ROOT, pad.slot))
                assertEquals(recipe, PadRecipe.fromJsonText(savedRecipe.readText()))

                // Read-back intentionally omits these fields; test the actual exported programs.
                val instrument = nativeInstruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int(), "${pad.slot}: MPC 3 changed one-shot playback")
                val layer = (instrument.getValue("layersv") as JsonValue.Arr).items.first().obj()
                assertEquals(pad.sampleFile, layer.getValue("sampleFile").str())
                assertEquals(JsonValue.Bool(false), layer.getValue("loop"))
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(0, slice.getValue("LoopMode").int())
                assertEquals(replay.frameCount.toDouble(), slice.getValue("End").num())
                val xmlInstrument = xmlInstruments.item(index) as Element
                fun text(tag: String) = xmlInstrument.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", text("OneShot"), "${pad.slot}: MPC 2 changed one-shot playback")
                assertEquals(pad.sampleStem, text("SampleName"))
                assertEquals("0", text("SliceLoop"))
                assertEquals(replay.frameCount.toString(), text("SliceEnd"))
                patch
            }
            // All five exact native factory patches are present, not merely re-authored approximations.
            assertTrue(patches.containsAll(OhmPresets.all()))
            assertEquals(16, kit.pads.map { it.displayName }.distinct().size)
            val manifest = Json.parse(File(destination, "manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals("PadRecipe.render", manifest.getValue("renderSource").str())
            assertEquals(16, (manifest.getValue("pads") as JsonValue.Arr).items.size)
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(entries.all { it.startsWith("${kit.name}/") && ".." !in it.split('/') })
                assertEquals(16, entries.count { it.endsWith(".wav") }, "both MPC generations must share the WAV set")
                assertEquals(16, entries.count { "/recipes/" in it && it.endsWith(".json") })
                assertTrue("${kit.name}/${kit.name}.xtd" in entries)
                assertTrue("${kit.name}/${data.name}/${kit.name}.xpm" in entries)
                assertTrue("${kit.name}/${data.name}/kit.json" in entries)
                assertFalse(entries.any { "obsolete" in it || "unrelated" in it || it.endsWith(".zip") })
                for (pad in kit.pads) {
                    val entry = zip.getEntry("${kit.name}/${data.name}/${pad.sampleFile}")
                    assertContentEquals(File(data, pad.sampleFile).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
                }
            }
            assertEquals(sha256(archive), File(root, "${OhmKitGenerator.ARCHIVE_NAME}.sha256").readText().substringBefore(' '))

            // ShelfImport delegates combined XTD/XPM ZIPs to this exact importer.
            // Retain edit history only when it still describes the shipped audio.
            val imported = XpnImporter.importAll(archive, File(root, "imported"))
            assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
            val landed = imported.kits.single()
            val landedKit = KitStore.load(landed.directory)
            assertEquals(kit.key, landedKit.key)
            for (original in kit.pads) {
                val pad = requireNotNull(landedKit.pad(original.slot))
                assertEquals(original.recipe, pad.recipe, "${pad.slot}: downloaded kit lost its editable performance")
                assertEquals(original.source["rawSha256"], pad.source["rawSha256"])
                assertEquals(pad.source["rawSha256"], sha256(File(landed.directory, pad.sampleFile)))
                assertContentEquals(File(data, original.sampleFile).readBytes(), File(landed.directory, pad.sampleFile).readBytes())
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
            }

            val alteredArchive = File(root, "altered.zip")
            val changedSample = "${kit.name}/${data.name}/${kit.pads.first().sampleFile}"
            ZipFile(archive).use { source ->
                ZipOutputStream(alteredArchive.outputStream()).use { output ->
                    for (entry in source.entries().asSequence()) {
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == changedSample) {
                            val bytes = source.getInputStream(entry).use { it.readBytes() }
                            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                            output.write(bytes)
                        } else {
                            source.getInputStream(entry).use { it.copyTo(output) }
                        }
                        output.closeEntry()
                    }
                }
            }
            val refusedDir = File(root, "refused")
            val refused = XpnImporter.importAll(alteredArchive, refusedDir)
            assertTrue(refused.kits.isEmpty(), "modified WAV must not receive stale editable history")
            assertEquals(1, refused.skipped.size)
            assertTrue("sample checksum disagrees at pad 1" in refused.skipped.single().second)
            assertTrue(KitStore.list(refusedDir).isEmpty(), "a refused import must not leave a partial kit")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
