package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.KerfTonalKits
import com.snipsnap.synth.KerfTonalKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * KERF's pitched-pad kits through the owner's velocity and round-robin gate, in software: the lowest and highest pad
 * of each kit through saving, reloading, [PadHit.resolve] and the MPC 3 exporter (`:shell:generateKerfKits` runs every
 * pad).
 */
class KerfTonalKitTest {
    @Test fun `each kit's grids survive saving reloading playback and MPC 3 export`() {
        for (k in Kit.entries) {
            val dir = Files.createTempDirectory("kerf-${k.name.lowercase()}").toFile()
            try {
                val pads = KerfTonalKits.build(k, listOf(0, KerfTonalKits.PADS - 1))
                val kit = KitAssembler.assembleArranged("Kerf ${k.title}", pads, dir, tempoBpm = KerfTonalKits.bpm(k).toFloat())
                val saved = KitStore.load(dir)
                assertEquals(kit, saved, k.title)
                TriadKitProof.verify(saved, dir, pads)
                val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
                TriadKitProof.verifyTrack(exported.program, saved)
                for (pad in saved.pads) {
                    val wav = WavReader.read(File(dir, pad.sampleFile))
                    fun start(velocity: Float, hit: Int) = assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                    val zones = assertNotNull(pad.chain?.zones)
                    assertEquals(3, zones.size)
                    for ((z, zone) in zones.withIndex()) {
                        val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                        val starts = List(KerfTonalKits.TAKES * 2) { start(velocity, it) }
                        assertEquals(KerfTonalKits.TAKES, starts.take(KerfTonalKits.TAKES).toSet().size, "${k.title} ${pad.displayName} zone $z")
                        assertEquals(starts.take(KerfTonalKits.TAKES), starts.drop(KerfTonalKits.TAKES), "${k.title} ${pad.displayName} zone $z repeat")
                    }
                    assertFalse(start(.1f, 0) == start(1f, 0), "${pad.displayName}: soft and hard never share a slice")
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `the three kits are on the shelf once each at their own tempos`() {
        for (k in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(k.id), k.id)
            assertEquals(KerfTonalKits.bpm(k).toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { k.id in it.ids }, k.id)
            assertEquals("KERF ${k.title.uppercase()}", starter.displayName)
        }
    }
}
