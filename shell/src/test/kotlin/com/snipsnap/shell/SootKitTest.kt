package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.SootKits
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate for SOOT, in software: the kit's grids through saving,
 * reloading, the app's own pad resolver ([PadHit.resolve]) and the MPC 3 exporter, read back from the
 * files each step wrote. GATECRASH's proof, on SOOT's pads.
 */
class SootKitTest {
    /** A hit, the roll and the four bars: one of each kind of pad, with the whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> = SootKits.build().let { listOf(it[1], it[SootKits.HITS], it[SootKits.HITS + 1]) }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("soot-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Soot Probe", pads, dir, tempoBpm = SootKits.BPM.toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("soot-play").toFile()
        try {
            KitAssembler.assembleArranged("Soot Probe", probePads(), dir)
            for (pad in KitStore.load(dir).pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) = assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(3, zones.size)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(SootKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(SootKits.TAKES, starts.take(SootKits.TAKES).toSet().size, "${pad.displayName} zone $z")
                    assertEquals(starts.take(SootKits.TAKES), starts.drop(SootKits.TAKES), "${pad.displayName} zone $z repeat")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "${pad.displayName}: soft and hard never share a slice")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the kit is on the shelf once at its tempo`() {
        val starter = assertNotNull(StarterKits.byId("soot"))
        assertEquals(SootKits.BPM.toFloat(), starter.tempoBpm)
        assertFalse(starter.seeded)
        assertEquals(1, StarterShelf.GROUPS.count { "soot" in it.ids })
        assertTrue(starter.displayName == "SOOT")
    }
}
