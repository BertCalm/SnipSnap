package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.w3c.dom.Element

class OverlapKitTest {
    @Test
    fun `native MPC twins retain approved recipes and every frame including finite held tails`() {
        val root = createTempDirectory("overlap-kit").toFile()
        try {
            val archive = OverlapKitGenerator.generate(root)
            val destination = File(root, OverlapKitGenerator.KIT_NAME)
            val source = File(destination, "Source Kit")
            val kit = KitStore.load(source)
            assertEquals(16, kit.pads.size)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertFalse(Preflight.check(kit, source).blocked())
            val bank = OverlapMusicalBank.all()
            val lowNames = listOf("LOW THREAD", "LOW PIN", "LOW CUP", "LOW SEED")
            val data = File(destination, "${kit.name}_[TrackData]")
            val project = Mpc3Project.read(File(destination, "${kit.name}.xtd"))
            assertTrue(project.isTrack)
            assertEquals(listOf(kit.name), project.trackNames)
            val instruments = (project.drumPrograms().single().getValue("instruments") as JsonValue.Arr).items
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(File(data, "${kit.name}.xpm"))
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val patches = kit.pads.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertNull(recipe.fx, "pad ${pad.slot} gained an effect chain")
                val patch = assertIs<OverlapPatch>(recipe.patch)
                val expected = if (index < 12) bank[index].patch else {
                    val base = bank[index - 12].patch
                    base.copy(name = lowNames[index - 12], midi = base.midi - 12)
                }
                assertEquals(expected.toJsonValue(), patch.toJsonValue(), "pad ${pad.slot} changed the saved musical recipe")
                assertEquals(2, patch.model)
                assertEquals(6, patch.macros.size)
                assertEquals(patch.name, pad.displayName)
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(.707946f, pad.level)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                val replay = recipe.render()
                assertEquals(1, replay.channels)
                assertEquals(WavWriter.MPC_SAMPLE_RATE, replay.sampleRate)
                assertTrue(replay.samples.all { it.isFinite() })
                assertTrue(replay.samples.last() == 0f, "pad ${pad.slot} lost its native end fade")
                val original = File(source, pad.sampleFile)
                val exported = File(data, pad.sampleFile)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay) }.toByteArray()
                assertContentEquals(encoded, original.readBytes(), "source WAV ${pad.slot} differs from the dry native render")
                assertContentEquals(encoded, exported.readBytes(), "export WAV ${pad.slot} changed gain or tail")
                val info = WavInfo.read(exported)
                assertEquals(24, info.bitsPerSample)
                assertEquals(replay.frameCount.toLong(), info.frameCount)
                if (index in 4..11) assertTrue(info.frameCount > 7L * WavWriter.MPC_SAMPLE_RATE,
                    "finite texture/breath ${pad.slot} was shortened")

                val instrument = instruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int(), "MPC 3 must play pad ${pad.slot} once")
                val layer = (instrument.getValue("layersv") as JsonValue.Arr).items.first().obj()
                assertEquals(pad.sampleFile, layer.getValue("sampleFile").str())
                assertEquals(JsonValue.Bool(false), layer.getValue("loop"))
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(replay.frameCount.toDouble(), slice.getValue("End").num())
                assertEquals(0, slice.getValue("LoopMode").int())
                val xmlInstrument = xmlInstruments.item(index) as Element
                fun xmlText(tag: String) = xmlInstrument.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", xmlText("OneShot"))
                assertEquals(pad.sampleStem, xmlText("SampleName"))
                assertEquals(replay.frameCount.toString(), xmlText("SliceEnd"))
                assertEquals("0", xmlText("SliceLoop"))
                patch
            }
            assertEquals(listOf(48, 48, 36, 36), patches.takeLast(4).map { it.midi })
            val manifest = Json.parse(File(destination, "kit-manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals(16, (manifest.getValue("pads") as JsonValue.Arr).items.size)
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.all { it.startsWith("${kit.name}/") && ".." !in it.split('/') })
                assertEquals(32, names.count { it.endsWith(".wav") })
                assertTrue("${kit.name}/Source Kit/kit.json" in names)
                assertTrue("${kit.name}/${kit.name}.xtd" in names)
                assertTrue("${kit.name}/${kit.name}_[TrackData]/${kit.name}.xpm" in names)
                kit.pads.forEach { pad ->
                    val entry = zip.getEntry("${kit.name}/Source Kit/${pad.sampleFile}")
                    assertContentEquals(File(source, pad.sampleFile).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
                }
            }
            assertFalse(File(root, ".overlap-work").exists(), "staging must be cleaned after export")
        } finally {
            root.deleteRecursively()
        }
    }
}
