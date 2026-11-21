package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.synth.TerraArticulation
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraStickKits
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.*

/**
 * TERRA's Japanese kits ([TerraStickKits]) keep every touch and every take from the shelf to the hardware formats:
 * the starter renders the kit, the saved PCM is each zone sample, the kit reloads equal, MPC 3 carries each zone's
 * slices and cycle, MPC 2 carries each zone (its first take: the owner's 2026-11-14 format limit for a pad with both
 * velocity layers and round robin), SFZ round-trips the chain, and playback picks the right zone at all 127
 * velocities and deals the four takes in turn over nine hits. The engine acceptance gates' velocity/round-robin
 * evidence for these kits (docs/TERRA_STICKS.md).
 */
class TerraStickKitsRoundTripTest {
    @Test fun `every kit keeps its touches and takes through shelf, save, MPC 3, MPC 2, SFZ and playback`() {
        val root = createTempDirectory("terra-stick-kits").toFile()
        try {
            for (k in TerraStickKits.Kit.entries) {
                val dir = root.resolve(k.id)
                val starter = assertNotNull(StarterKits.byId(k.id))
                assertEquals(k.bpm.toFloat(), starter.tempoBpm)
                assertEquals(1, StarterShelf.GROUPS.count { k.id in it.ids }, "${k.id} is shelved once")
                val expected = TerraStickKits.build(k)
                val kit = starter.render(k.displayName, dir)
                // the saved PCM is each zone sample, dealt by PadHit, and each pad keeps its recipe in its source
                TriadKitProof.verify(kit, dir, expected)
                assertEquals(kit, KitStore.load(dir))
                assertEquals(16, kit.pads.size)
                assertTrue(kit.pads.none { it.isRobinChain })
                for ((i, pad) in kit.pads.withIndex()) {
                    val chain = assertNotNull(pad.chain, pad.displayName)
                    assertEquals(3, chain.zones!!.size, pad.displayName)
                    assertTrue(chain.zones!!.all { it.cycle == TerraStickKits.TAKES }, pad.displayName)
                    val recipe = TerraPatch.fromJsonText(pad.source.getValue("recipe"))
                    assertEquals(TerraStickKits.patches(k)[i], recipe, "${pad.displayName} reloads its recipe")
                    assertEquals(recipe.articulation.name, pad.source.getValue("articulation"))
                }
                assertTrue(kit.pads.any { it.source["articulation"] == TerraArticulation.ROLL.name }, "${k.id} has a roll")
                assertTrue(kit.pads.any { it.source["articulation"] == TerraArticulation.RISER.name }, "${k.id} has a riser")

                val mpc3 = Mpc3Exporter.exportTrack(kit, dir, root.resolve("mpc3/${k.id}"))
                TriadKitProof.verifyTrack(mpc3.program, kit)
                TriadKitProof.verify(kit, java.io.File(mpc3.directory, com.snipsnap.mpc3.Mpc3TrackWriter.trackDataDirName(kit.name)), expected)
                val mpc2 = KitExporter.exportProgramFolder(kit, dir, root.resolve("mpc2/${k.id}"))
                for (export in listOf(mpc3, mpc2)) {
                    assertEquals(16, export.samples.size)
                    for (pad in kit.pads) assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), export.samples.single { it.name == pad.sampleFile }.readBytes())
                }
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mpc2.program)
                val instruments = xml.getElementsByTagName("Instrument")
                for (pad in kit.pads) {
                    val instrument = (0 until instruments.length).map { instruments.item(it) as Element }.single { it.getAttribute("number").toInt() == pad.slot - 1 }
                    fun Element.value(tag: String) = getElementsByTagName(tag).item(0).textContent
                    val nodes = instrument.getElementsByTagName("Layer")
                    val layers = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.value("SampleName").isNotEmpty() }
                    val chain = requireNotNull(pad.chain)
                    assertEquals(3, layers.size, pad.displayName)
                    for ((layer, zone) in layers.zip(requireNotNull(chain.zones))) {
                        assertEquals(zone.velStart, layer.value("VelStart").toInt())
                        assertEquals(zone.velEnd, layer.value("VelEnd").toInt())
                        assertEquals(chain.boundaries[zone.baseSlice], layer.value("SliceStart").toLong())
                        assertEquals(chain.boundaries[zone.baseSlice + 1], layer.value("SliceEnd").toLong())
                    }
                }

                val sfz = SfzWriter.write(kit, dir, root.resolve("sfz/${k.id}"))
                val imported = SfzImporter.import(sfz, root.resolve("import/${k.id}"))
                assertTrue(imported.skipped.isEmpty())
                assertEquals(16, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    val back = imported.kit.pads[i]
                    assertEquals(pad.chain, back.chain)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), imported.directory.resolve(back.sampleFile).readBytes())
                    val chain = requireNotNull(pad.chain)
                    for (v in 1..127) for (hit in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v / 127f, hit) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v / 127f, hit) { wav.frameCount.toLong() })
                        val zone = assertNotNull(chain.zoneFor(v))
                        val window = chain.window(zone.baseSlice + hit % TerraStickKits.TAKES, wav.frameCount.toLong())
                        assertEquals(window.first, before.startFrame)
                        assertEquals(window.first, after.startFrame)
                        assertEquals(window.last + 1, after.endFrameExclusive)
                        // SFZ writes volume to three decimal dB places
                        assertTrue(abs(20 * log10(after.gainLeft.toDouble() / before.gainLeft)) < .00051)
                        assertTrue(abs(20 * log10(after.gainRight.toDouble() / before.gainRight)) < .00051)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
