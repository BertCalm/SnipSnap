package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.SideroomKits
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.test.*

class SideroomDeliveryTest {
    @Test fun `all cycling companions preserve every take PCM order mute group and both format descriptors`() {
        val root=Files.createTempDirectory("sideroom-delivery-test").toFile()
        try {
            for(g in SideroomKits.Kit.entries) {
                val pads=SideroomKits.build(g)
                val exports=SideroomDelivery.exportCycles(g,File(root,g.slug),pads=pads)
                for((z,result) in exports.withIndex()) {
                    val payload=requireNotNull(Acvs.read(result.program).payload) as JsonValue.Obj
                    val data=payload.obj("data").obj("program").obj("drum")
                    val instruments=(data.entries.getValue("instruments") as JsonValue.Arr).items
                    val name="Sideroom ${g.title} ${SideroomDelivery.touches[z]} Cycle"
                    val folder=File(result.directory,Mpc3TrackWriter.trackDataDirName(name))
                    val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder,"$name.xpm"))
                    val xmlInstruments=doc.getElementsByTagName("Instrument")
                    assertEquals(30,result.samples.size)
                    for(r in pads.indices) {
                        val inst=instruments[r] as JsonValue.Obj
                        assertEquals(0,inst.num("zonePlayTime"))
                        assertEquals(pads[r].muteGroup ?: 0,inst.num("whichMuteGroup"))
                        val layers=(inst.entries.getValue("layersv") as JsonValue.Arr).items.map { it as JsonValue.Obj }.filter { it.str("sampleName").isNotEmpty() }
                        assertEquals(3,layers.size)
                        val xi=xmlInstruments.item(r) as Element
                        assertEquals("0",xi.text("ZonePlay"))
                        val xl=xi.getElementsByTagName("Layer")
                        for(t in 0..2) {
                            val layer=layers[t];val x=xl.item(t) as Element
                            assertEquals(0,layer.num("velocityStart"));assertEquals(127,layer.num("velocityEnd"))
                            assertEquals(0,layer.num("sliceIncrement"));assertEquals(1,layer.num("sliceCycleLength"))
                            assertEquals(layer.str("sampleName"),x.text("SampleName"))
                            assertEquals("0",x.text("VelStart"));assertEquals("127",x.text("VelEnd"))
                            val wav=WavReader.read(File(folder,layer.str("sampleFile")))
                            val expected=pads[r].velocityTakes[z][t]
                            assertEquals(expected.frameCount,wav.frameCount)
                            assertEquals(0,layer.obj("sliceInfo").num("Start"))
                            assertEquals(wav.frameCount,layer.obj("sliceInfo").num("End"))
                            assertEquals(wav.frameCount.toString(),x.text("SliceEnd"))
                            assertTrue(expected.samples.indices.all { abs(expected.samples[it]-wav.samples[it])<=3f/8388608 },"${g.slug}/$z/$r/$t PCM")
                        }
                        // Two full descriptor-driven cycles use the three complete WAVs in authored order.
                        val played=(0..5).map { layers[it%3].str("sampleName") }
                        assertEquals(played.take(3),played.drop(3));assertEquals(3,played.toSet().size)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
    private fun JsonValue.Obj.obj(k:String)=entries.getValue(k) as JsonValue.Obj
    private fun JsonValue.Obj.num(k:String)=(entries.getValue(k) as JsonValue.Num).value.toInt()
    private fun JsonValue.Obj.str(k:String)=(entries.getValue(k) as JsonValue.Str).value
    private fun Element.text(k:String)=getElementsByTagName(k).item(0).textContent
}
