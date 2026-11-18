package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.SunroomKits
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.*

class SunroomRoundTripTest {
    @Test fun `all Sunroom choices retain every strike through save SFZ import and playback`() {
        val root = createTempDirectory("sunroom").toFile()
        try {
            for (t in SunroomKits.Choice.entries) {
                val dir = root.resolve(t.id)
                val rendered = SunroomKits.build(t)
                val starter = assertNotNull(StarterKits.byId(t.id))
                assertFalse(starter.seeded)
                assertEquals(t.tempoBpm,starter.tempoBpm)
                assertEquals(1,StarterShelf.GROUPS.count { t.id in it.ids })
                val kit = starter.render(t.title,dir)
                assertEquals(kit, KitStore.load(dir))
                assertFalse(Preflight.check(kit, dir).blocked(), t.id)
                val sfz = SfzWriter.write(kit, dir, root.resolve("export-${t.id}"))
                val imported = SfzImporter.import(sfz, root.resolve("import-${t.id}"))
                val exported = Mpc3Exporter.exportTrack(kit,dir,root.resolve("mpc-${t.id}"),mpc2Twin=true)
                TriadKitProof.verifyTrack(exported.program,kit)
                TriadKitProof.verify(kit, dir, rendered)
                TriadKitProof.verify(kit, exported.samples.first().parentFile, rendered)
                val mpc2 = KitExporter.exportProgramFolder(kit, dir, root.resolve("mpc2-${t.id}"))
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mpc2.program)
                val instruments = xml.getElementsByTagName("Instrument")
                for (pad in kit.pads) {
                    val instrument = (0 until instruments.length).map { instruments.item(it) as Element }
                        .single { it.getAttribute("number").toInt() == pad.slot - 1 }
                    fun Element.value(tag: String) = getElementsByTagName(tag).item(0).textContent
                    assertEquals("1", instrument.value("ZonePlay"))
                    val nodes = instrument.getElementsByTagName("Layer")
                    val layers = (0 until nodes.length).map { nodes.item(it) as Element }
                        .filter { it.value("SampleName").isNotEmpty() }
                    val chain = requireNotNull(pad.chain)
                    assertEquals(3, layers.size)
                    for ((layer, zone) in layers.zip(requireNotNull(chain.zones))) {
                        assertEquals(zone.velStart, layer.value("VelStart").toInt())
                        assertEquals(zone.velEnd, layer.value("VelEnd").toInt())
                        assertEquals(chain.boundaries[zone.baseSlice], layer.value("SliceStart").toLong())
                        assertEquals(chain.boundaries[zone.baseSlice + 1], layer.value("SliceEnd").toLong())
                    }
                }

                assertTrue(ExportReadBack.verifyProgram(kit,exported.program,ExportFormat.MPC3_TRACK).none { it.severity == Severity.FAIL })
                for (pad in kit.pads) assertContentEquals(dir.resolve(pad.sampleFile).readBytes(),exported.samples.first().parentFile.resolve(pad.sampleFile).readBytes())
                assertTrue(imported.skipped.isEmpty())
                assertEquals(16, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    assertEquals(SunroomKits.VERSION.toString(), pad.source["bankVersion"])
                    assertTrue(pad.oneShot)
                    val back = imported.kit.pads[i]
                    assertNull(pad.recipe, "Frozen audition assets must not claim an editable synth recipe")
                    assertEquals("sunroom", pad.source["factory"])
                    assertEquals("frozen synthesis and reference-derived resynthesis", pad.source["sourceKind"])
                    assertEquals(t.bank, pad.source["kit"])
                    val chain = assertNotNull(pad.chain)
                    assertEquals(3, chain.zones!!.size)
                    assertTrue(chain.zones!!.all { it.cycle == 3 })
                    assertEquals(chain, back.chain)
                    assertEquals(pad.muteGroup, back.muteGroup)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), imported.directory.resolve(back.sampleFile).readBytes())
                    for ((n, take) in rendered[i].velocityTakes.flatten().withIndex()) {
                        val w = chain.window(n, wav.frameCount.toLong())
                        assertEquals(take.frameCount.toLong(), w.last + 1 - w.first)
                        assertTrue(take.samples.indices.all { abs(take.samples[it] - wav.samples[w.first.toInt()+it]) <= 2e-7f })
                    }
                    for (v in 1..127) for (hitIndex in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val zone = assertNotNull(chain.zoneFor(v))
                        val expectedZone = if (v < 42) 0 else if (v < 85) 1 else 2
                        assertEquals(expectedZone * 3, zone.baseSlice)
                        assertEquals(3, zone.cycle)
                        val w = chain.window(zone.baseSlice + hitIndex%3, wav.frameCount.toLong())
                        assertEquals(w.first, after.startFrame)
                        assertEquals(w.last+1, after.endFrameExclusive)
                        // SFZ writes volume to 0.001 dB: half a step is < 0.000058 relative gain.
                        assertEquals(before.gainLeft, after.gainLeft, abs(before.gainLeft) * 0.000059f + 1e-7f)
                        assertEquals(before.gainRight, after.gainRight, abs(before.gainRight) * 0.000059f + 1e-7f)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
