package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.XpnImporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChamberKitTest {
    @Test
    fun `starter bank preserves six factory rooms and renders its scale at native notes`() {
        val bank = ChamberKits.entries()
        assertEquals(16, bank.size)
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), bank.take(8).map { it.patch.midi })
        val anchor = ChamberPresets.all().first { it.name == "Four Answers" }
        bank.take(8).forEach { entry ->
            assertEquals(anchor, entry.patch.copy(name = anchor.name, midi = anchor.midi))
            assertEquals(anchor.name, entry.preset)
        }
        assertEquals(ChamberPresets.all(), bank.subList(8, 14).map { it.patch })
        assertEquals(anchor.copy(name = "Uniform Room", macros = anchor.macros + ("DIVIDE" to 0f)), bank[14].patch)
        val aquarium = ChamberPresets.all().first { it.name == "Aquarium Ceiling" }
        assertEquals(aquarium.copy(name = "Low Canopy", macros = aquarium.macros + ("PLACEMENT" to .15f)), bank[15].patch)
        assertEquals(16, bank.map { it.patch.name }.distinct().size)
        for (entry in bank) {
            assertEquals(3, entry.patch.model)
            assertEquals(.75f, entry.patch.velocity)
            assertEquals(0L, entry.patch.seed)
            assertEquals(0f, entry.patch.macros.getValue("HOLD"))
            assertEquals(DrumClass.TONAL, Chamber.drumClassFor(entry.patch.voice, entry.patch.macros))
            assertEquals(entry.patch, PadRecipe.fromJsonText(PadRecipe(entry.patch).toJsonText()).patch)
        }
    }

    @Test
    fun `native MPC twins and downloadable archive preserve every finite room recipe and audio frame`() {
        val root = createTempDirectory("chamber-kit").toFile()
        try {
            val destination = File(root, ChamberKitGenerator.KIT_NAME)
            File(destination, "recipes/obsolete.json").also { it.parentFile.mkdirs() }.writeText("stale")
            File(destination, "unrelated.wav").writeText("stale")
            val archive = ChamberKitGenerator.generate(root)
            val data = File(destination, "${ChamberKitGenerator.KIT_NAME}_[TrackData]")
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
            val nativeNoteMap = project.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
            val xmlFactory = DocumentBuilderFactory.newInstance()
            xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = xmlFactory.newDocumentBuilder().parse(File(data, "${kit.name}.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val xmlNoteMap = xml.getElementsByTagName("PadNote")
            val entries = ChamberKits.entries()
            val patches = kit.pads.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<ChamberPatch>(recipe.patch)
                assertEquals(entries[index].patch, patch, "${pad.slot}: saved room performance changed")
                assertEquals(patch.name, pad.displayName)
                assertEquals(entries[index].preset, pad.source["preset"])
                assertEquals(entries[index].role, pad.source["role"])
                assertEquals(patch.midi.toString(), pad.source["pitchMidi"])
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(pad.chain)
                assertNull(recipe.fx)
                assertNull(recipe.treatment)
                assertFalse(recipe.alias)

                val replay = recipe.render()
                assertEquals(1, replay.channels)
                assertEquals(44_100, replay.sampleRate)
                assertTrue(replay.samples.all { it.isFinite() })
                assertTrue(replay.peak() in .000001f..1f)
                assertTrue(replay.samples.last() == 0f, "${pad.slot}: finite ending lost its fade")
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay) }.toByteArray()
                val sample = File(data, pad.sampleFile)
                assertContentEquals(encoded, sample.readBytes(), "${pad.slot}: native pitch, gain or full tail changed")
                assertEquals(sha256(sample), pad.source["rawSha256"], "${pad.slot}: exported recipe fingerprint differs")
                val info = WavInfo.read(sample)
                assertEquals(24, info.bitsPerSample)
                assertEquals(replay.frameCount.toLong(), info.frameCount)
                val savedRecipe = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.json")
                assertEquals(recipe, PadRecipe.fromJsonText(savedRecipe.readText()))

                // ReadBack does not compare playback, slice bounds or both trigger maps.
                val instrument = nativeInstruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int(), "${pad.slot}: MPC 3 changed one-shot playback")
                val layer = (instrument.getValue("layersv") as JsonValue.Arr).items.first().obj()
                assertEquals(pad.sampleFile, layer.getValue("sampleFile").str())
                assertEquals(JsonValue.Bool(false), layer.getValue("loop"))
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(0, slice.getValue("LoopMode").int())
                assertEquals(replay.frameCount.toDouble(), slice.getValue("End").num())
                assertEquals(Mpc3Note.noteFor(pad.slot), nativeNoteMap.getValue("value$index").int())
                val xmlInstrument = xmlInstruments.item(index) as Element
                fun text(tag: String) = xmlInstrument.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", text("OneShot"), "${pad.slot}: MPC 2 changed one-shot playback")
                assertEquals(pad.sampleStem, text("SampleName"))
                assertEquals("0", text("SliceLoop"))
                assertEquals(replay.frameCount.toString(), text("SliceEnd"))
                val xmlPad = xmlNoteMap.item(index) as Element
                assertEquals(PadNoteMap.noteForPad(pad.slot).toString(), xmlPad.getElementsByTagName("Note").item(0).textContent)
                patch
            }
            assertTrue(patches.containsAll(ChamberPresets.all()))
            val manifest = Json.parse(File(destination, "manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals(3, manifest.getValue("model").int())
            assertEquals("PadRecipe.render", manifest.getValue("renderSource").str())
            val rows = (manifest.getValue("pads") as JsonValue.Arr).items
            assertEquals(16, rows.size)
            for ((row, patch) in rows.zip(patches)) {
                assertEquals(patch.seed.toString(), row.obj().getValue("seed").str())
                assertEquals(patch.configuration.toJsonValue(), row.obj().getValue("configuration"))
                assertEquals(PadRecipe(patch).toJsonValue(), row.obj().getValue("savedRecipe"))
            }
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.all { it.startsWith("${kit.name}/") && ".." !in it.split('/') })
                assertEquals(16, names.count { it.endsWith(".wav") }, "both MPC generations must share one WAV set")
                assertEquals(16, names.count { "/recipes/" in it && it.endsWith(".json") })
                assertTrue("${kit.name}/${kit.name}.xtd" in names)
                assertTrue("${kit.name}/${data.name}/${kit.name}.xpm" in names)
                assertTrue("${kit.name}/${data.name}/kit.json" in names)
                assertFalse(names.any { "obsolete" in it || "unrelated" in it || it.endsWith(".zip") })
                for (pad in kit.pads) {
                    val entry = zip.getEntry("${kit.name}/${data.name}/${pad.sampleFile}")
                    assertContentEquals(File(data, pad.sampleFile).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
                }
            }
            assertEquals(sha256(archive), File(root, "${ChamberKitGenerator.ARCHIVE_NAME}.sha256").readText().substringBefore(' '))

            val imported = XpnImporter.importAll(archive, File(root, "imported"))
            assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
            val landed = imported.kits.single()
            val landedKit = KitStore.load(landed.directory)
            assertEquals(kit.key, landedKit.key)
            for (original in kit.pads) {
                val pad = requireNotNull(landedKit.pad(original.slot))
                assertEquals(original.recipe, pad.recipe, "${pad.slot}: imported room recipe changed")
                assertEquals(original.source["rawSha256"], pad.source["rawSha256"])
                assertEquals(sha256(File(landed.directory, pad.sampleFile)), pad.source["rawSha256"])
                assertContentEquals(File(data, original.sampleFile).readBytes(), File(landed.directory, pad.sampleFile).readBytes())
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
