package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.StaggerKits
import com.snipsnap.synth.StaggerKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for STAGGER: a grid through saving, reloading, the app's
 * own pad resolver and the MPC 3 exporter, read back from the files each step wrote, in [LooseCrateKitTest]'s shape.
 */
class StaggerKitTest {
    /** A muffled kick, a foley pad, a hat, a glass pad and the riser: one of each kind of pad, each with its whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> {
        fun pad(kit: Kit, role: Int): ArrangedPad {
            val grid = StaggerKits.velocities.map { v -> List(StaggerKits.TAKES) { t -> StaggerKits.render(kit, role, v, t) } }
            return ArrangedPad(grid.last().first(), StaggerKits.classOf(kit, role), velocityTakes = grid,
                muteGroup = StaggerKits.muteGroup(kit, role), source = mapOf("engine" to "STAGGER", "role" to role.toString()))
        }
        return listOf(pad(Kit.CARDBOARD, 0), pad(Kit.CARDBOARD, 10), pad(Kit.STUMBLE, 5), pad(Kit.MATINEE, 8), pad(Kit.MATINEE, StaggerKits.ROLL))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("stagger-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Stagger Probe", pads, dir, tempoBpm = 89f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("stagger-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Stagger Probe", pads, dir)
            val saved = KitStore.load(dir)
            for (pad in saved.pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(StaggerKits.velocities.size, zones.map { it.velStart }.toSet().size, "pad ${pad.slot} zones")
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(StaggerKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(StaggerKits.TAKES, starts.take(StaggerKits.TAKES).toSet().size, "pad ${pad.slot} zone $z")
                    assertEquals(starts.take(StaggerKits.TAKES), starts.drop(StaggerKits.TAKES), "pad ${pad.slot} zone $z repeat")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "pad ${pad.slot}: soft and hard play the same slice")
            }
            // The open box's choke group survives the save: hats cut hats on the sampler.
            assertEquals(StaggerKits.HAT_GROUP, saved.pads[2].muteGroup)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the three kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("STAGGER "))
            assertEquals(DrumClass.LOOP, StaggerKits.classOf(kit, StaggerKits.GROOVE))
        }
    }
}
