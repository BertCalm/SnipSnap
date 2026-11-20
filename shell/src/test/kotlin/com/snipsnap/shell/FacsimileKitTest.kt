package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.FacsimileKits
import com.snipsnap.synth.FacsimileKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for FACSIMILE: a grid through saving, reloading, the app's
 * own pad resolver and the MPC 3 exporter, read back from the files each step wrote.
 */
class FacsimileKitTest {
    /** A single drum, a layered stack, a ghost pad, a hat (choke group) and a groove, each with its whole 3 x 4 grid. */
    private val probes = listOf(Kit.KNOCK to 0, Kit.KNOCK to 5, Kit.KNOCK to 2, Kit.SHUFFLE to 6, Kit.KNOCK to 14)

    private fun probePads(): List<ArrangedPad> = probes.map { (kit, role) ->
        val grid = FacsimileKits.velocities.map { v -> List(FacsimileKits.TAKES) { t -> FacsimileKits.render(kit, role, v, t) } }
        ArrangedPad(grid.last().first(), FacsimileKits.classOf(kit, role), velocityTakes = grid,
            muteGroup = FacsimileKits.muteGroup(kit, role), source = mapOf("engine" to "FACSIMILE", "role" to role.toString()))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("facsimile-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Facsimile Probe", pads, dir, tempoBpm = 113f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("facsimile-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Facsimile Probe", pads, dir)
            val saved = KitStore.load(dir)
            for (pad in saved.pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(FacsimileKits.velocities.size, zones.map { it.velStart }.toSet().size, "pad ${pad.slot} zones")
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(FacsimileKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(FacsimileKits.TAKES, starts.take(FacsimileKits.TAKES).toSet().size, "pad ${pad.slot} zone $z")
                    assertEquals(starts.take(FacsimileKits.TAKES), starts.drop(FacsimileKits.TAKES), "pad ${pad.slot} zone $z repeat")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "pad ${pad.slot}: soft and hard play the same slice")
            }
            // the hat's choke group and the groove's survive the save
            assertEquals(FacsimileKits.HAT_GROUP, saved.pads[3].muteGroup)
            assertEquals(FacsimileKits.PHRASE_GROUP, saved.pads[4].muteGroup)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the four kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("FACSIMILE "))
            assertTrue((0 until FacsimileKits.PADS).any { FacsimileKits.classOf(kit, it) == DrumClass.LOOP })
        }
    }
}
