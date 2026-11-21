package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.KerfKits
import com.snipsnap.synth.KerfKits.Genre
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * KERF's four genre kits through the owner's velocity and round-robin gate, in software: a hit, the roll and the riser
 * of each kit through saving, reloading, [PadHit.resolve] and the MPC 3 exporter (`:shell:generateKerfKits` runs all
 * sixteen pads of every kit).
 */
class KerfKitTest {
    @Test fun `each kit's grids survive saving reloading playback and MPC 3 export`() {
        for (g in Genre.entries) {
            val dir = Files.createTempDirectory("kerf-${g.name.lowercase()}").toFile()
            try {
                // a snare, the roll and the riser: KerfKits.build(g, roles) levels each pad by its own grid, so these
                // are the kit's own pads (`:shell:generateKerfKits` proves every pad of every kit the same way)
                // The roll and riser are one code path for every kit; they are proved here on one kit per bank (the
                // pool, round 5's, round 6's new loops and its percussion kit), every kit's fourth pad on all.
                val sample = setOf(Genre.DUST, Genre.RUSH, Genre.JUNGLE, Genre.CLANG)
                val roles = if (g in sample) listOf(3, KerfKits.HITS, KerfKits.HITS + 1) else listOf(3)
                val pads = KerfKits.build(g, roles)
                // one gain per pad: its loudest cell sits at its level, so nothing a pad deals clips
                for ((role, pad) in roles.zip(pads)) {
                    val loudest = pad.velocityTakes.flatten().maxOf { s -> s.samples.maxOf { kotlin.math.abs(it) } }
                    val want = com.snipsnap.synth.Kerf.PEAK * Math.pow(10.0, KerfKits.levelDb(g, role) / 20.0).toFloat()
                    assertEquals(want, loudest, 1e-5f, "${g.title} pad ${role + 1} level")
                }
                val kit = KitAssembler.assembleArranged("Kerf ${g.title}", pads, dir, tempoBpm = g.bpm.toFloat())
                val saved = KitStore.load(dir)
                assertEquals(kit, saved, g.title)
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
                        val starts = List(KerfKits.TAKES * 2) { start(velocity, it) }
                        assertEquals(KerfKits.TAKES, starts.take(KerfKits.TAKES).toSet().size, "${g.title} ${pad.displayName} zone $z")
                        assertEquals(starts.take(KerfKits.TAKES), starts.drop(KerfKits.TAKES), "${g.title} ${pad.displayName} zone $z repeat")
                    }
                    assertFalse(start(.1f, 0) == start(1f, 0), "${pad.displayName}: soft and hard never share a slice")
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `the four kits are on the shelf once each at their own tempos`() {
        for (g in Genre.entries) {
            val starter = assertNotNull(StarterKits.byId(g.id), g.id)
            assertEquals(g.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { g.id in it.ids }, g.id)
            assertEquals("KERF ${g.title.uppercase()}", starter.displayName)
        }
    }
}
