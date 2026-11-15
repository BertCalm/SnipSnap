package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.CalabashKits
import com.snipsnap.synth.CalabashKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for CALABASH: a grid through saving, reloading, the app's
 * own pad resolver, and the MPC 3 exporter, read back from the files each step wrote.
 */
class CalabashKitTest {
    private companion object {
        /**
         * A kick, a hat, a hand drum and a roll (the sabar roll, the longest grid): the kinds of pad the kits hold,
         * each with its whole 3 x 4 grid, built once.
         */
        val probes: List<ArrangedPad> by lazy {
            listOf(Kit.AFROBEATS to 0, Kit.AFROBEAT to 3, Kit.AFROCUBAN to 4, Kit.MBALAX to 15).map { (k, role) -> CalabashKits.pad(k, role) }
        }
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("calabash-grid").toFile()
        try {
            val kit = KitAssembler.assembleArranged("Calabash Probe", probes, dir, tempoBpm = Kit.MBALAX.bpm.toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, probes)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("calabash-play").toFile()
        try {
            KitAssembler.assembleArranged("Calabash Probe", probes, dir)
            for (pad in KitStore.load(dir).pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(CalabashKits.velocities.size, zones.size)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(CalabashKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(CalabashKits.TAKES, starts.take(CalabashKits.TAKES).toSet().size, "${pad.displayName} zone $z")
                    assertEquals(starts.take(CalabashKits.TAKES), starts.drop(CalabashKits.TAKES), "${pad.displayName} zone $z repeat")
                    assertEquals(starts.take(CalabashKits.TAKES).sorted(), starts.take(CalabashKits.TAKES), "${pad.displayName} zone $z order")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), pad.displayName)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the four kits are on the shelf once each at their authored tempo`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("CALABASH "))
        }
    }
}
