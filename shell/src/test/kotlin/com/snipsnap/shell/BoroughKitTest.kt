package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc2Limits
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import com.snipsnap.kit.SfzImporter
import com.snipsnap.kit.SfzWriter
import com.snipsnap.synth.BoroughKits
import com.snipsnap.synth.BoroughKits.Kit
import com.snipsnap.synth.BoroughKits.Stack
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for BOROUGH: a kit from the shelf (STOOP DUST with its
 * default stack) through saving, reloading, the app's pad resolver at all 127 velocities, SFZ out and back, the MPC 3
 * exporter (its zones, slices and cycles read back) and MPC 2 (each zone's first take: the owner's format limit of
 * 2026-11-14); and a kit with a stack it does not default to, built by [BoroughKits.build] as the stack picker will,
 * through the same save and MPC 3 track. `:shell:generateBoroughKits` does every kit with its default stack.
 */
class BoroughKitTest {
    @Test fun `a shelf kit keeps velocity and takes through save SFZ MPC 3 MPC 2 and playback`() {
        val root = createTempDirectory("borough-roundtrip").toFile()
        try {
            val k = Kit.STOOP
            val dir = root.resolve(k.id)
            val starter = assertNotNull(StarterKits.byId(k.id))
            val kit = starter.render(k.title, dir)
            assertEquals(kit, KitStore.load(dir))
            assertEquals(16, kit.pads.size)
            assertFalse(Preflight.check(kit, dir).blocked(), k.id)
            // The saved PCM is the engine's grid, take for take, and the hats still choke as one.
            val rendered = BoroughKits.build(k)
            TriadKitProof.verify(kit, dir, rendered)
            assertEquals(setOf(3, 4, 8), kit.pads.filter { it.muteGroup == BoroughKits.HAT_GROUP }.map { it.slot }.toSet())
            assertEquals(listOf("Tamb Shake", "Tamb Hit", "Thud", "Claps"), kit.pads.drop(12).map { it.displayName })

            // MPC 3: every zone's slices and cycle, read back from the written track; samples byte for byte.
            val mpc3 = Mpc3Exporter.exportTrack(kit, dir, root.resolve("mpc3"))
            TriadKitProof.verifyTrack(mpc3.program, kit)
            assertTrue(ExportReadBack.verifyProgram(kit, mpc3.program, ExportFormat.MPC3_TRACK).none { it.severity == Severity.FAIL })
            // MPC 2: the velocity layers survive, each its zone's first take; the export screen says so (Mpc2Limits).
            val mpc2 = KitExporter.exportProgramFolder(kit, dir, root.resolve("mpc2"))
            for (export in listOf(mpc3, mpc2)) for (pad in kit.pads) {
                assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), export.samples.single { it.name == pad.sampleFile }.readBytes())
            }
            assertEquals(16, Mpc2Limits.gridPads(kit).size)
            assertNotNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, kit))
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mpc2.program)
            val instruments = xml.getElementsByTagName("Instrument")
            for (pad in kit.pads) {
                val instrument = (0 until instruments.length).map { instruments.item(it) as Element }.single { it.getAttribute("number").toInt() == pad.slot - 1 }
                fun Element.value(tag: String) = getElementsByTagName(tag).item(0).textContent
                val nodes = instrument.getElementsByTagName("Layer")
                val layers = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.value("SampleName").isNotEmpty() }
                val chain = assertNotNull(pad.chain)
                assertEquals(3, layers.size, "slot ${pad.slot}")
                for ((layer, zone) in layers.zip(assertNotNull(chain.zones))) {
                    assertEquals(zone.velStart, layer.value("VelStart").toInt())
                    assertEquals(zone.velEnd, layer.value("VelEnd").toInt())
                    assertEquals(chain.boundaries[zone.baseSlice], layer.value("SliceStart").toLong())
                    assertEquals(chain.boundaries[zone.baseSlice + 1], layer.value("SliceEnd").toLong())
                }
            }

            // SFZ out and back, then the pad resolver at every velocity: the same zone, the same take dealt in order.
            val sfz = SfzWriter.write(kit, dir, root.resolve("sfz"))
            val imported = SfzImporter.import(sfz, root.resolve("import"))
            assertTrue(imported.skipped.isEmpty())
            assertEquals(16, imported.kit.pads.size)
            for ((i, pad) in kit.pads.withIndex()) {
                assertNull(pad.recipe)
                assertEquals("BOROUGH", pad.source["engine"])
                val back = imported.kit.pads[i]
                val chain = assertNotNull(pad.chain)
                assertEquals(3, chain.zones!!.size)
                assertTrue(chain.zones!!.all { it.cycle == BoroughKits.TAKES })
                assertEquals(chain, back.chain)
                assertEquals(pad.muteGroup, back.muteGroup)
                assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), imported.directory.resolve(back.sampleFile).readBytes())
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                for (v in 1..127) for (hit in 0..8) {
                    val before = assertNotNull(PadHit.resolve(pad, v / 127f, hit) { wav.frameCount.toLong() })
                    val after = assertNotNull(PadHit.resolve(back, v / 127f, hit) { wav.frameCount.toLong() })
                    val zone = assertNotNull(chain.zoneFor(v))
                    val expectedZone = if (v < 42) 0 else if (v < 85) 1 else 2
                    assertEquals(expectedZone * BoroughKits.TAKES, zone.baseSlice, "slot ${pad.slot} velocity $v")
                    val w = chain.window(zone.baseSlice + hit % BoroughKits.TAKES, wav.frameCount.toLong())
                    assertEquals(w.first, before.startFrame); assertEquals(w.last + 1, before.endFrameExclusive)
                    assertEquals(w.first, after.startFrame); assertEquals(w.last + 1, after.endFrameExclusive)
                    assertEquals(before.gainLeft, after.gainLeft, abs(before.gainLeft) * 0.000059f + 1e-7f)
                    assertEquals(before.pitchRatio, after.pitchRatio)
                }
                // successive playback windows deal the three takes in order, then again; soft and hard differ
                val starts = List(BoroughKits.TAKES * 2) { PadHit.resolve(pad, 1f, it) { wav.frameCount.toLong() }!!.startFrame }
                assertEquals(BoroughKits.TAKES, starts.take(BoroughKits.TAKES).toSet().size)
                assertEquals(starts.take(BoroughKits.TAKES), starts.drop(BoroughKits.TAKES))
                assertEquals(starts.take(BoroughKits.TAKES).sorted(), starts.take(BoroughKits.TAKES))
                assertFalse(PadHit.resolve(pad, .1f, 0) { wav.frameCount.toLong() }!!.startFrame == starts[0])
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `a kit with another stack survives saving and MPC 3 export`() {
        val root = createTempDirectory("borough-stack").toFile()
        try {
            val pads = BoroughKits.build(Kit.WAX, Stack.TOPS)
            val kit = KitAssembler.assembleArranged("Borough Wax Tops", pads, root, tempoBpm = Kit.WAX.bpm.toFloat())
            val saved = KitStore.load(root)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, root, pads)
            assertEquals(listOf("Clap", "Ride", "Ride Bell", "Sticks"), saved.pads.drop(12).map { it.displayName })
            assertEquals(listOf("TOPS"), saved.pads.drop(12).map { it.source["stack"] }.distinct())
            TriadKitProof.verifyTrack(Mpc3Exporter.exportTrack(saved, root, root.resolve("export")).program, saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun `the six kits are on the shelf once each at their tempo`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertEquals(kit.title, starter.displayName)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("BOROUGH "))
        }
    }
}
