package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.RiddimKits
import com.snipsnap.synth.RiddimKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for RIDDIM: each of the three kits, whole, through saving,
 * reloading, the app's own pad resolver and the MPC 3 exporter, read back from the files each step wrote. Every pad
 * is checked, the RIDDIM voices and the CALABASH percussion on the same grid alike.
 */
class RiddimKitTest {
    private companion object {
        val kits: Map<Kit, List<ArrangedPad>> by lazy { Kit.entries.associateWith { RiddimKits.build(it) } }
    }

    @Test fun `every kit survives saving reloading playback and MPC 3 export`() {
        for ((k, pads) in kits) {
            val dir = Files.createTempDirectory("riddim-grid").toFile()
            try {
                val kit = KitAssembler.assembleArranged("Riddim ${k.title}", pads, dir, tempoBpm = k.bpm.toFloat())
                val saved = KitStore.load(dir)
                assertEquals(kit, saved, k.name)
                TriadKitProof.verify(saved, dir, pads)
                val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
                TriadKitProof.verifyTrack(exported.program, saved)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats, on every pad`() {
        for ((k, pads) in kits) {
            val dir = Files.createTempDirectory("riddim-play").toFile()
            try {
                KitAssembler.assembleArranged("Riddim ${k.title}", pads, dir)
                for (pad in KitStore.load(dir).pads) {
                    val wav = WavReader.read(File(dir, pad.sampleFile))
                    fun start(velocity: Float, hit: Int) =
                        assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                    val zones = assertNotNull(pad.chain?.zones)
                    assertEquals(RiddimKits.velocities.size, zones.size)
                    for ((z, zone) in zones.withIndex()) {
                        val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                        val starts = List(RiddimKits.TAKES * 2) { start(velocity, it) }
                        val what = "${k.name} ${pad.displayName} zone $z"
                        assertEquals(RiddimKits.TAKES, starts.take(RiddimKits.TAKES).toSet().size, what)
                        assertEquals(starts.take(RiddimKits.TAKES), starts.drop(RiddimKits.TAKES), "$what repeat")
                        assertEquals(starts.take(RiddimKits.TAKES).sorted(), starts.take(RiddimKits.TAKES), "$what order")
                    }
                    assertFalse(start(.1f, 0) == start(1f, 0), "${k.name} ${pad.displayName}")
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `the three kits are on the shelf once each at their authored tempo`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("RIDDIM "))
        }
    }
}
