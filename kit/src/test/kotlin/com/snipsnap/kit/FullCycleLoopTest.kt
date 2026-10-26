package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullCycleLoopTest {
    @Test
    fun `saved gated cycles retain full-range loops and sustain in both MPC formats`() {
        val root = createTempDirectory("full-cycle-loop").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val samples = FloatArray(4410) { i -> (.2 * kotlin.math.sin(2 * Math.PI * i / 100)).toFloat() }
            WavWriter.write(File(source, "cycle.wav"), Snip(samples, 1, 44100))
            val kit = Kit("Cycle test", listOf(
                KitPad(1, "cycle.wav", drumClass = DrumClass.LOOP, oneShot = false,
                    loop = true, attack = 0f, decay = 1f),
            ))
            KitStore.save(kit, source)
            assertEquals(kit, KitStore.load(source))
            val native = Mpc3Exporter.exportTrack(kit, source, File(root, "native"), mpc2Twin = true)
            val instrument = Mpc3Project.read(native.program).drumPrograms().single()
                .getValue("instruments").arr().first().obj()
            assertEquals(2, instrument.getValue("triggerMode").int())
            val layers = instrument.getValue("layersv").arr().map { it.obj() }
            for (layer in layers) {
                val slice = layer.getValue("sliceInfo").obj()
                val filled = layer.getValue("sampleFile").str().isNotEmpty()
                assertEquals(if (filled) 1 else 0, slice.getValue("LoopMode").int())
                if (filled) {
                    assertEquals(0, slice.getValue("LoopStart").int())
                    assertEquals(samples.size, slice.getValue("End").int())
                }
            }
            val envelope = instrument.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            assertEquals(1.0, envelope.getValue("Sustain").obj().getValue("value0").num())
            assertEquals(0.0, envelope.getValue("Release").obj().getValue("value0").num())
            assertFalse(envelope.getValue("OneShot").obj().getValue("value0").bool())

            val xpm = File(native.samples.first().parentFile, "Cycle test.xpm")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xpm)
            val xmlInstrument = document.getElementsByTagName("Instrument").item(0) as Element
            fun text(element: Element, tag: String) = element.getElementsByTagName(tag).item(0).textContent
            assertEquals("False", text(xmlInstrument, "OneShot"))
            assertEquals("False", text(xmlInstrument, "VolumeADEnvelope"))
            assertEquals(1f, text(xmlInstrument, "VolumeSustain").toFloat())
            assertEquals(0f, text(xmlInstrument, "VolumeRelease").toFloat())
            val xmlLayers = xmlInstrument.getElementsByTagName("Layer")
            for (index in 0 until xmlLayers.length) {
                val layer = xmlLayers.item(index) as Element
                val filled = text(layer, "SampleName").isNotEmpty()
                assertEquals(if (filled) "1" else "0", text(layer, "SliceLoop"))
                if (filled) assertEquals(samples.size, text(layer, "SliceEnd").toInt())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `ordinary pads keep their original JSON and full-cycle playback is opt-in`() {
        val plain = KitPad(1, "note.wav")
        assertFalse(KitStore.padToJson(plain).entries.containsKey("loop"))
        assertFalse(KitStore.padFromJson(KitStore.padToJson(plain)).loop)
        assertTrue(KitStore.padFromJson(KitStore.padToJson(plain.copy(loop = true))).loop)
    }
}
