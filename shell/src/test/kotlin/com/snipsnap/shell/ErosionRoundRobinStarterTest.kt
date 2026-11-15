package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc2Limits
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.ErosionBank
import com.snipsnap.synth.ErosionKits
import com.snipsnap.synth.ErosionRoundRobinKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * The owner's velocity and round-robin gate, in software, for the opt-in EROSION round robin: the
 * grid through the shelf, saving, reloading, the app's pad resolver, the groove preview and both
 * MPC exports, read back from the files each step wrote. MPC 2 keeps each zone's first take (the
 * recorded format limit); MPC 3 carries every take.
 */
class ErosionRoundRobinStarterTest {
    @Test fun `the round robin is on the shelf once, beside the accepted starter, which is unchanged`() {
        val starter = assertNotNull(StarterKits.byId(ErosionRoundRobinKits.ID))
        assertEquals("EROSION ROUND ROBIN", starter.displayName)
        assertFalse(starter.seeded)
        assertNull(starter.key, "relative TUNE must not invent a musical root")
        val group = StarterShelf.GROUPS.single { ErosionRoundRobinKits.ID in it.ids }
        assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == ErosionRoundRobinKits.ID } })
        assertEquals(group.ids.indexOf("erosion") + 1, group.ids.indexOf(ErosionRoundRobinKits.ID))
        // Stored starter order only grows at the end: EROSION ROUND ROBIN was appended after TERRA's genre kits.
        // Later starters are appended after it, so pin its place rather than demand it stays last.
        val ids = StarterKits.ALL.map { it.id }
        assertEquals("terra-punjab", ids[ids.indexOf(ErosionRoundRobinKits.ID) - 1], "the stored starter order only grows at the end")
    }

    @Test fun `every take survives saving reloading playback preview and both MPC exports`() {
        val root = createTempDirectory("erosion-rr").toFile()
        try {
            val pads = ErosionRoundRobinKits.build()
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(ErosionRoundRobinKits.ID)).render("Erosion Round Robin", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..8).toList(), saved.pads.map { it.slot })
            assertEquals(ErosionBank.entries().map { it.name }, saved.pads.map { it.displayName })
            TriadKitProof.verify(saved, dir, pads)

            // Each zone keeps the accepted touch windows' order and deals its four takes in turn.
            val accepted = ErosionKits.contrast()
            for ((i, pad) in saved.pads.withIndex()) {
                assertEquals(0, pad.muteGroup, "separate impacts may overlap")
                assertTrue(pad.oneShot)
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(listOf(0 to 41, 42 to 84, 85 to 127), zones.map { it.velStart to it.velEnd })
                assertEquals(listOf(4, 4, 4), zones.map { it.cycle })
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val hits = List(8) { assertNotNull(PadHit.resolve(pad, velocity, it) { wav.frameCount.toLong() }) }
                    val starts = hits.map { it.startFrame }
                    assertEquals(4, starts.take(4).toSet().size, "${pad.displayName} zone $z: a fresh take per hit")
                    assertEquals(starts.take(4), starts.drop(4), "${pad.displayName} zone $z: the cycle repeats")
                    // The first hit in every zone plays the accepted zone sample.
                    val reference = (accepted[i].softVariants + accepted[i].snip)[z].samples
                    val first = hits.first()
                    assertEquals(reference.size.toLong(), first.endFrameExclusive - first.startFrame)
                    assertTrue(reference.indices.all { abs(reference[it] - wav.samples[first.startFrame.toInt() + it]) <= 3f / 8388608 },
                        "${pad.displayName} zone $z take 0 on disk")
                }
            }

            // The groove preview the app plays cycles the takes too.
            val groove = Mpc3Clip("A01", 2, (0..7).map { Mpc3Note(Mpc3Note.noteFor(1), it * 480L, .9f) })
            val pinned = saved.copy(pads = saved.pads.map { p -> p.copy(chain = p.chain?.let { c -> c.copy(zones = c.zones?.map { it.copy(cycle = 1) }) }) })
            assertFalse(KitPreview.render(saved, dir, groove, 96f).samples.contentEquals(KitPreview.render(pinned, dir, groove, 96f).samples))

            val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("mpc3"))
            TriadKitProof.verifyTrack(track.program, saved)
            val data = track.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, track.program, data, track.findings)).blocked())
            for (pad in saved.pads) {
                val mine = WavReader.read(dir.resolve(pad.sampleFile))
                val theirs = WavReader.read(File(data, pad.sampleFile))
                assertEquals(mine.frameCount, theirs.frameCount, "${pad.displayName}")
                assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= 3f / 8388608 }, "${pad.displayName}: exported PCM")
            }

            // MPC 2 cannot both switch by velocity and deal takes; the export screen says so before writing.
            assertEquals(saved.pads.map { it.slot }, Mpc2Limits.gridPads(saved).map { it.slot })
            assertNotNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, saved))
            val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("mpc2"))
            assertFalse(ExportReadBack.verify(saved, folder).blocked())
        } finally {
            root.deleteRecursively()
        }
    }
}
