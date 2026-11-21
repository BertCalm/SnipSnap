package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.SigilKits
import com.snipsnap.synth.SigilKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for SIGIL: a grid through saving, reloading, the app's own pad
 * resolver and the MPC 3 exporter, read back from the files each step wrote. `:shell:generateSigilKits` runs the same
 * proof on all eight kits whole.
 */
class SigilKitTest {
    /** A kick, a hat (choke group), a tuned log, a roll, a groove and a drumless kit's pad, each with its whole 3 x 4 grid. */
    private val probes = listOf(Kit.IRON to 0, Kit.NEON to 4, Kit.ASH to 6, Kit.KILN to SigilKits.ROLL,
        Kit.NEON to SigilKits.GROOVE, Kit.KILN_DRUMLESS to 11)

    private fun probePads(): List<ArrangedPad> = probes.map { (kit, role) ->
        val grid = SigilKits.velocities.map { v -> List(SigilKits.TAKES) { t -> SigilKits.render(kit, role, v, t) } }
        ArrangedPad(grid.last().first(), SigilKits.classOf(kit, role), velocityTakes = grid,
            muteGroup = SigilKits.muteGroup(kit, role), source = mapOf("engine" to "SIGIL", "role" to role.toString()))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("sigil-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Sigil Probe", pads, dir, tempoBpm = 90f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("sigil-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Sigil Probe", pads, dir)
            val saved = KitStore.load(dir)
            for (pad in saved.pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(SigilKits.velocities.size, zones.map { it.velStart }.toSet().size, "pad ${pad.slot} zones")
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(SigilKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(SigilKits.TAKES, starts.take(SigilKits.TAKES).toSet().size, "pad ${pad.slot} zone $z")
                    assertEquals(starts.take(SigilKits.TAKES), starts.drop(SigilKits.TAKES), "pad ${pad.slot} zone $z repeat")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "pad ${pad.slot}: soft and hard play the same slice")
            }
            // the hat's choke group and the phrases' survive the save
            assertEquals(SigilKits.HAT_GROUP, saved.pads[1].muteGroup)
            assertEquals(SigilKits.PHRASE_GROUP, saved.pads[3].muteGroup)
            assertEquals(SigilKits.PHRASE_GROUP, saved.pads[4].muteGroup)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the eight kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertEquals(kit.title, starter.displayName)
            assertEquals(DrumClass.LOOP, SigilKits.classOf(kit, SigilKits.GROOVE))
        }
        assertTrue(Kit.entries.count { it.drumless } == 4)
    }
}
