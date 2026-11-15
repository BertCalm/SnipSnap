package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.EastAfricaKits
import com.snipsnap.audio.WavReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.math.abs
import kotlin.math.log10

class EastAfricaRoundTripTest {
    @Test fun `every genre keeps velocity and cycling through shelf save SFZ export import and playback`() {
        val root = createTempDirectory("east-africa-grid-roundtrip").toFile()
        try {
            for (genre in EastAfricaKits.Genre.entries) {
                val dir = root.resolve(genre.id)
                val starter = assertNotNull(StarterKits.byId(genre.id))
                assertEquals(genre.bpm.toFloat(), starter.tempoBpm)
                val expected = EastAfricaKits.build(genre)
                val kit = starter.render(genre.title, dir)
                TriadKitProof.verify(kit, dir, expected)
                assertEquals(kit, KitStore.load(dir))
                assertEquals(22, kit.pads.size)
                for((r,pad) in kit.pads.withIndex()) {
                    assertEquals(EastAfricaKits.level(genre,r),pad.level)
                    assertEquals(if(r in 5..6)1 else 0,pad.muteGroup)
                }
                // Reuse the serialized-grid check already used by Triad; common
                // ExportReadBack deliberately skips these chain fields.
                val mpc3 = Mpc3Exporter.exportTrack(kit, dir, root.resolve("mpc3/${genre.id}"))
                TriadKitProof.verifyTrack(mpc3.program, kit)
                TriadKitProof.verify(kit, java.io.File(mpc3.directory, com.snipsnap.mpc3.Mpc3TrackWriter.trackDataDirName(kit.name)), expected)
                val mpc2 = KitExporter.exportProgramFolder(kit, dir, root.resolve("mpc2/${genre.id}"))
                for (export in listOf(mpc3, mpc2)) {
                    assertEquals(22, export.samples.size)
                    for (pad in kit.pads) {
                        val delivered = export.samples.single { it.name == pad.sampleFile }
                        assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), delivered.readBytes())
                    }
                }
                // The separate-file cycling exporter is for unzoned robin pads.
                // This bank must retain its three velocity zones, not flatten them.
                assertTrue(kit.pads.none { it.isRobinChain })
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
                val sfz = SfzWriter.write(kit, dir, root.resolve("export"))
                val imported = SfzImporter.import(sfz, root.resolve("import"))
                assertTrue(imported.skipped.isEmpty())
                assertEquals(22, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    val back = imported.kit.pads[i]
                    assertEquals(pad.chain, back.chain)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertTrue(dir.resolve(pad.sampleFile).readBytes().contentEquals(imported.directory.resolve(back.sampleFile).readBytes()))
                    for (v in 1..127) for (hitIndex in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v / 127f, hitIndex) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v / 127f, hitIndex) { wav.frameCount.toLong() })
                        val zone = assertNotNull(pad.chain?.zoneFor(v))
                        val window = requireNotNull(pad.chain).window(zone.baseSlice + hitIndex % EastAfricaKits.TAKES, wav.frameCount.toLong())
                        assertEquals(window.first, before.startFrame)
                        assertEquals(window.first, after.startFrame)
                        assertEquals(window.last + 1, after.endFrameExclusive)
                        // SFZ writes volume to three decimal dB places, so its round trip
                        // is bounded by half a 0.001 dB step, not bit-identical linear gain.
                        assertTrue(abs(20*log10(after.gainLeft.toDouble()/before.gainLeft))<.00051)
                        assertTrue(abs(20*log10(after.gainRight.toDouble()/before.gainRight))<.00051)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
                assertEquals(1, StarterShelf.GROUPS.count { genre.id in it.ids })
            }
        } finally { root.deleteRecursively() }
    }
}
