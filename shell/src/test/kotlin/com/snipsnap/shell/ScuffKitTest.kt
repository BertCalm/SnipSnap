package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.ScuffKits
import com.snipsnap.synth.ScuffKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for SCUFF: a grid through saving, reloading, the
 * app's own pad resolver, and the MPC 3 exporter, read back from the files each step wrote.
 */
class ScuffKitTest {
    /** A kick, a hat and a bus phrase: the kinds of pad the kit holds, with the whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> {
        fun pad(role: Int, cls: DrumClass): ArrangedPad {
            val grid = ScuffKits.velocities.map { v -> List(ScuffKits.TAKES) { t -> ScuffKits.render(Kit.DETROIT, role, v, t) } }
            return ArrangedPad(grid.last().first(), cls, velocityTakes = grid, source = mapOf("engine" to "SCUFF", "role" to role.toString()))
        }
        return listOf(pad(0, DrumClass.KICK), pad(2, DrumClass.HAT_CLOSED), pad(14, DrumClass.LOOP))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("scuff-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Scuff Probe", pads, dir, tempoBpm = Kit.DETROIT.bpm.toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("scuff-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Scuff Probe", pads, dir)
            val pad = KitStore.load(dir).pads.first()
            val wav = WavReader.read(File(dir, pad.sampleFile))
            fun start(velocity: Float, hit: Int) =
                assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
            val zones = assertNotNull(pad.chain?.zones)
            for ((z, zone) in zones.withIndex()) {
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                val starts = List(ScuffKits.TAKES * 2) { start(velocity, it) }
                assertEquals(ScuffKits.TAKES, starts.take(ScuffKits.TAKES).toSet().size, "zone $z")
                assertEquals(starts.take(ScuffKits.TAKES), starts.drop(ScuffKits.TAKES), "zone $z repeat")
                assertEquals(starts.take(ScuffKits.TAKES).sorted(), starts.take(ScuffKits.TAKES), "zone $z order")
            }
            assertFalse(start(.1f, 0) == start(1f, 0))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the kit is on the shelf once at its authored tempo`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("SCUFF "))
        }
    }
}
