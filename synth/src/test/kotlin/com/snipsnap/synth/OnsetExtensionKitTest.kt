package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OnsetExtensionKitTest {
    private val temp = kotlin.io.path.createTempDirectory("onset-extension-kit").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `acceptance bank stays sixteen model3 patches while native extension is separate`() {
        val accepted = OnsetKits.patches()
        assertEquals(16, accepted.size)
        assertTrue(accepted.all { it.modelVersion == 3 })
        assertEquals(listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN),
            accepted.take(4).map { it.voice })
        assertEquals(listOf(0f, .25f, .5f, 1f), accepted.chunked(4).map { it.first().macros.getValue("BLEND") })
        val extension = OnsetKits.extensionStudy()
        assertEquals(7, extension.size)
        extension.forEach { assertEquals(4, assertIs<OnsetPatch>(PadRecipe.fromJsonValue(it.recipe!!).patch).modelVersion) }
    }

    @Test
    fun `generated native download imports both formats with seven exact recipes and full unity windows`() {
        val archive = OnsetExtensionKitGenerator.write(temp)
        val root = File(temp, OnsetExtensionKitGenerator.KIT_NAME)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(root.name))
        val kit = KitStore.load(data)
        val xtd = File(root, "${root.name}.xtd")
        val xpm = File(data, "${root.name}.xpm")
        assertEquals(7, kit.pads.size)
        val settings = Json.parse(File(root, "settings.json").readText()).obj()
        assertEquals(4, settings.getValue("modelVersion").int())
        assertEquals(7, settings.getValue("pads").arr().size)
        assertTrue(settings.getValue("sourceIdentity").obj().isNotEmpty())

        val track = Mpc3Project.read(xtd)
        assertEquals(1.0, track.programs().single().getValue("mixable").obj().getValue("volume").num())
        val instruments = track.drumPrograms().single().getValue("instruments").arr().map { it.obj() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xpm)
        val xmlInstruments = document.getElementsByTagName("Instrument")
        assertEquals(7, xmlInstruments.length)
        for (pad in kit.pads) {
            val frames = WavReader.read(File(data, pad.sampleFile)).frameCount.toLong()
            val native = instruments[pad.slot - 1]
            val slice = native.getValue("layersv").arr().first().obj().getValue("sliceInfo").obj()
            assertEquals(0L, slice.getValue("Start").long())
            assertEquals(frames, slice.getValue("End").long())
            assertEquals(1.0, native.getValue("mixable").obj().getValue("volume").num())
            val xml = xmlInstruments.item(pad.slot - 1) as Element
            fun value(tag: String) = xml.getElementsByTagName(tag).item(0).textContent
            assertEquals(frames, value("SliceEnd").toLong())
            assertEquals(0.0, value("VolumeDecay").toDouble())
            assertEquals(1.0, value("Volume").toDouble())
        }
        ZipFile(archive).use { zip ->
            assertTrue(zip.entries().asSequence().any { it.name.endsWith("/kit.json") })
            assertEquals(7, zip.entries().asSequence().count { it.name.endsWith(".wav") })
        }
        val native = Mpc3Importer.import(xtd, File(temp, "xtd-import"))
        val compatibility = XpnImporter.import(archive, File(temp, "xpm-import"))
        for ((imported, directory) in listOf(native.kit to native.directory, compatibility.kit to compatibility.directory)) {
            for (pad in imported.pads) {
                val original = assertNotNull(kit.pad(pad.slot))
                assertEquals(original.recipe, pad.recipe)
                assertEquals(4, assertIs<OnsetPatch>(PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch).modelVersion)
                assertContentEquals(File(data, original.sampleFile).readBytes(), File(directory, pad.sampleFile).readBytes())
            }
        }
    }
}
