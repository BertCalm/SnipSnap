package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.KindredKits
import com.snipsnap.synth.KindredKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for KINDRED: a grid through saving, reloading, the app's
 * own pad resolver, and the MPC 3 exporter, read back from the files each step wrote.
 */
class KindredKitTest {
    /** A kick, a hat, a clap (three strikes per hit), the phrase and the riser, with the whole 3 x 4 grid. */
    private fun probePads(kit: Kit): List<ArrangedPad> {
        fun pad(role: Int): ArrangedPad {
            val grid = KindredKits.velocities.map { v -> List(KindredKits.TAKES) { t -> KindredKits.render(kit, role, v, t) } }
            return ArrangedPad(grid.last().first(), KindredKits.drumClass(kit, role), velocityTakes = grid,
                source = mapOf("engine" to "KINDRED", "role" to role.toString()))
        }
        return listOf(pad(0), pad(2), pad(1), pad(KindredKits.HITS), pad(KindredKits.HITS + 1))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("kindred-grid").toFile()
        try {
            val pads = probePads(Kit.TECHNO)
            val kit = KitAssembler.assembleArranged("Kindred Probe", pads, dir, tempoBpm = Kit.TECHNO.bpm.toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("kindred-play").toFile()
        try {
            KitAssembler.assembleArranged("Kindred Probe", probePads(Kit.INDUSTRIAL), dir)
            for (pad in KitStore.load(dir).pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(KindredKits.velocities.size, zones.size)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(KindredKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(KindredKits.TAKES, starts.take(KindredKits.TAKES).toSet().size, "slot ${pad.slot} zone $z")
                    assertEquals(starts.take(KindredKits.TAKES), starts.drop(KindredKits.TAKES), "slot ${pad.slot} zone $z repeat")
                    assertEquals(starts.take(KindredKits.TAKES).sorted(), starts.take(KindredKits.TAKES), "slot ${pad.slot} zone $z order")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "slot ${pad.slot}: soft and hard hits play the same slice")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `every kit is on the shelf once at its authored tempo`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("KINDRED "))
        }
        assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), listOf(KindredKits.drumClass(Kit.TECHNO, 14), KindredKits.drumClass(Kit.TECHNO, 15)))
    }
}
