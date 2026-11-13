package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.MirrorballKits
import com.snipsnap.synth.MirrorballKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software: a MIRRORBALL grid through saving, reloading,
 * the app's own pad resolver, and the MPC 3 exporter, read back from the files each step wrote.
 */
class MirrorballKitTest {
    /** An echo pad, a phrase and a kick: one of each kind of pad the kits hold, with the whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> {
        fun pad(kit: Kit, role: Int, cls: DrumClass): ArrangedPad {
            val grid = MirrorballKits.velocities.map { v -> List(MirrorballKits.TAKES) { t -> MirrorballKits.render(kit, role, v, t) } }
            return ArrangedPad(grid.last().first(), cls, velocityTakes = grid, source = mapOf("engine" to "MIRRORBALL", "role" to role.toString()))
        }
        // An echoing hand drum, a phrase, and a kick with no echo.
        return listOf(pad(Kit.LOFT, 6, DrumClass.PERC), pad(Kit.PHILLY, 14, DrumClass.LOOP), pad(Kit.MUNICH, 0, DrumClass.KICK))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("mirrorball-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Mirrorball Probe", pads, dir, tempoBpm = 122f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            // Saved PCM against the renders, through PadHit.resolve, two full cycles of every zone.
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            // The exported track's own velocity ranges, base slices, cycle lengths and frame windows.
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("mirrorball-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Mirrorball Probe", pads, dir)
            val pad = KitStore.load(dir).pads.first()
            val wav = WavReader.read(File(dir, pad.sampleFile))
            fun start(velocity: Float, hit: Int) =
                assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
            val zones = assertNotNull(pad.chain?.zones)
            for ((z, zone) in zones.withIndex()) {
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                val starts = List(MirrorballKits.TAKES * 2) { start(velocity, it) }
                // Four different slices in order, then the same four again.
                assertEquals(MirrorballKits.TAKES, starts.take(MirrorballKits.TAKES).toSet().size, "zone $z")
                assertEquals(starts.take(MirrorballKits.TAKES), starts.drop(MirrorballKits.TAKES), "zone $z repeat")
                assertEquals(starts.take(MirrorballKits.TAKES).sorted(), starts.take(MirrorballKits.TAKES), "zone $z order")
            }
            // A soft touch and a hard one never land on the same slice.
            assertFalse(start(.1f, 0) == start(1f, 0))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the three kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("MIRRORBALL "))
        }
    }
}
