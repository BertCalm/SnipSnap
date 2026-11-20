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
import com.snipsnap.synth.TempestGenreRoundRobinKits
import com.snipsnap.synth.TerraGenreKits
import com.snipsnap.synth.TerraGenreRoundRobinKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * The velocity and round-robin gate, in software, for SAHEL TAKES, MAQAM TAKES and PUNJAB TAKES, as
 * TerraRoundRobinStarterTest runs it for the classic kit: each grid through the shelf, saving,
 * reloading, the app's pad resolver, the groove preview and both MPC exports, read back from the files
 * each step wrote. MPC 2 keeps each zone's first take (the recorded format limit); MPC 3 carries every take.
 */
class TerraGenreRoundRobinStarterTest {
    private val kits = TerraGenreRoundRobinKits.Kit.entries

    @Test fun `each TAKES kit is on the shelf once, right after its kept genre kit, and appended to the stored order`() {
        val ids = StarterKits.ALL.map { it.id }
        val before = listOf(TempestGenreRoundRobinKits.Kit.STEP.id) + kits.map { it.id }
        for ((n, kit) in kits.withIndex()) {
            val starter = assertNotNull(StarterKits.byId(kit.id))
            assertEquals(kit.displayName, starter.displayName)
            assertEquals("${kit.source.displayName.removePrefix("TERRA ")} TAKES", starter.displayName)
            assertTrue(starter.displayName.length <= UserPresets.MAX_NAME, "${starter.displayName} fits a name")
            assertFalse(starter.seeded)
            assertNull(starter.key, "a percussion kit claims no musical key")
            val group = StarterShelf.GROUPS.single { kit.id in it.ids }
            assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == kit.id } })
            assertEquals(group.ids.indexOf(kit.source.id) + 1, group.ids.indexOf(kit.id))
            // Stored starter order only grows at the end: the three were appended after STEP TAKES, in kit order.
            assertEquals(before[n], ids[ids.indexOf(kit.id) - 1], "the stored starter order only grows at the end")
            assertNotNull(StarterKits.byId(kit.source.id))
        }
    }

    @Test fun `every take survives saving reloading playback preview and both MPC exports`() {
        val root = createTempDirectory("terra-genre-rr").toFile()
        try {
            for (k in kits) {
                val pads = TerraGenreRoundRobinKits.build(k)
                val dir = root.resolve(k.id)
                val kit = assertNotNull(StarterKits.byId(k.id)).render(k.displayName, dir)
                val saved = KitStore.load(dir)
                assertEquals(kit, saved)
                assertNull(saved.key)
                assertFalse(Preflight.check(saved, dir).blocked())
                assertEquals((1..16).toList(), saved.pads.map { it.slot })
                assertEquals(TerraGenreKits.patches(k.source).map { it.name }, saved.pads.map { it.displayName })
                TriadKitProof.verify(saved, dir, pads)

                // Each zone keeps the kept kit's three touch windows' order and deals its four takes in turn.
                val accepted = TerraGenreKits.build(k.source).map { it!! }
                for ((i, pad) in saved.pads.withIndex()) {
                    assertEquals(0, pad.muteGroup, "separate hand-drum hits may overlap")
                    assertTrue(pad.oneShot)
                    assertEquals(accepted[i].drumClass, pad.drumClass)
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
                        // The first hit in every zone plays the kept kit's zone sample.
                        val reference = (accepted[i].softVariants + accepted[i].snip)[z].samples
                        val first = hits.first()
                        assertEquals(reference.size.toLong(), first.endFrameExclusive - first.startFrame)
                        assertTrue(reference.indices.all { abs(reference[it] - wav.samples[first.startFrame.toInt() + it]) <= 3f / 8388608 },
                            "${pad.displayName} zone $z take 0 on disk")
                    }
                }

                // The groove preview the app plays cycles the takes too.
                val groove = Mpc3Clip("A01", 2, (0..7).map { Mpc3Note(Mpc3Note.noteFor(5), it * 480L, .9f) })
                val pinned = saved.copy(pads = saved.pads.map { p -> p.copy(chain = p.chain?.let { c -> c.copy(zones = c.zones?.map { it.copy(cycle = 1) }) }) })
                assertFalse(KitPreview.render(saved, dir, groove, 96f).samples.contentEquals(KitPreview.render(pinned, dir, groove, 96f).samples))

                val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("${k.id}-mpc3"))
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
                val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("${k.id}-mpc2"))
                assertFalse(ExportReadBack.verify(saved, folder).blocked())
                root.resolve(k.id).deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
