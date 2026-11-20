package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc2Limits
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordKits
import com.snipsnap.synth.AccordRoundRobinKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * ACCORD TAKES, the opt-in round robin for the kept ACCORD starter, whose passage-offset takes the
 * owner kept by ear in accord-rr-1 (19 Nov 2026): the grid through the shelf, saving, reloading,
 * preflight, the app's pad resolver and both MPC exports, read back from the files each step wrote.
 * Each touch deals two takes (engine takes 0 and 2; four made the kit about 245 MB and ran the
 * insert-card test out of memory). MPC 3 carries both; MPC 2 keeps each touch's first take (the
 * recorded format limit).
 */
class AccordRoundRobinStarterTest {
    private val id = AccordRoundRobinKits.ID
    private val lsb = 3f / 8388608

    @Test fun `ACCORD TAKES sits right after ACCORD on the shelf and is appended after ACCORD VOICES`() {
        val starter = assertNotNull(StarterKits.byId(id))
        assertEquals("accord-round-robin", id)
        assertEquals("ACCORD TAKES", starter.displayName)
        assertTrue(starter.displayName.length <= UserPresets.MAX_NAME, "${starter.displayName} fits a name")
        assertFalse(starter.seeded)
        assertEquals(KeySpec.parse("Cminpent"), starter.key)
        val group = StarterShelf.GROUPS.single { id in it.ids }
        assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == id } })
        assertEquals(group.ids.indexOf("accord") + 1, group.ids.indexOf(id))
        assertEquals(group.ids.indexOf(id) + 1, group.ids.indexOf(StarterKits.ACCORD_VOICES_ID))
        // Stored starter order only grows at the end: ACCORD TAKES was appended after ACCORD VOICES.
        // Later starters are appended after it, so pin its place rather than demand it stays last.
        val ids = StarterKits.ALL.map { it.id }
        assertEquals(StarterKits.ACCORD_VOICES_ID, ids[ids.indexOf(id) - 1], "the stored starter order only grows at the end")
        // The kept ACCORD starter stays where it was.
        assertEquals(ids.indexOf("obbligato") + 1, ids.indexOf("accord"))
    }

    @Test fun `every take survives saving reloading the resolver and both MPC exports as rendered`() {
        val root = createTempDirectory("accord-rr").toFile()
        try {
            val pads = AccordRoundRobinKits.build()
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(id)).render("Accord Takes", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved, "the saved kit must retain every source field")
            assertEquals(KeySpec.parse("Cminpent"), saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            val patches = AccordKits.patches()
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })
            // Every take of every touch on disk, dealt by the resolver in order, cycling.
            TriadKitProof.verify(saved, dir, pads)

            val kept = AccordKits.arranged()
            for ((i, pad) in saved.pads.withIndex()) {
                val patch = patches[i]
                assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite takes")
                assertNull(pad.recipe)
                assertEquals(kept[i].drumClass, pad.drumClass)
                assertEquals(kept[i].muteGroup ?: com.snipsnap.audio.AutoPlace.muteGroupFor(pad.drumClass), pad.muteGroup)
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(listOf(0 to 41, 42 to 84, 85 to 127), zones.map { it.velStart to it.velEnd })
                assertEquals(listOf(2, 2, 2), zones.map { it.cycle })
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(1, wav.channels)
                assertEquals(44_100, wav.sampleRate)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val hits = List(8) { assertNotNull(PadHit.resolve(pad, velocity, it) { wav.frameCount.toLong() }) }
                    val starts = hits.map { it.startFrame }
                    // Takes 1, 2, then 1 again: the zone's two slices in chain order.
                    assertEquals((0 until 2).map { pad.chain!!.boundaries[zone.baseSlice + it] }, starts.take(2), "${pad.displayName} zone $z: takes 1-2")
                    assertEquals(List(4) { starts.take(2) }.flatten(), starts, "${pad.displayName} zone $z: the cycle repeats")
                    assertTrue(hits.all { it.pitchRatio == 1.0 && it.loopStartFrame == -1L })
                }
                // The kept touch's first hit plays the kept ACCORD pad, the engine's render, to PCM24.
                val z = AccordRoundRobinKits.TOUCHES.indexOf(patch.velocity)
                val zone = zones[z]
                val first = assertNotNull(PadHit.resolve(pad, (zone.velStart + zone.velEnd) / 2f / 127f, 0) { wav.frameCount.toLong() })
                val render = Accord.render(patch).samples
                assertEquals(render.size.toLong(), first.endFrameExclusive - first.startFrame)
                assertTrue(render.indices.all { abs(render[it] - wav.samples[first.startFrame.toInt() + it]) <= lsb }, "${pad.displayName}: kept pad on disk")
            }

            // MPC 3: every take goes out unchanged, each touch a layer dealing its two slices.
            val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("mpc3"))
            TriadKitProof.verifyTrack(track.program, saved)
            val data = track.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, track.program, data, track.findings)).blocked())
            for (pad in saved.pads) {
                val mine = WavReader.read(dir.resolve(pad.sampleFile))
                val theirs = WavReader.read(File(data, pad.sampleFile))
                assertEquals(mine.frameCount, theirs.frameCount, pad.displayName)
                assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= lsb }, "${pad.displayName}: exported PCM")
            }

            // MPC 2 cannot both switch by velocity and deal takes: the export screen says so before writing,
            // and each touch's layer windows its first take.
            assertEquals(saved.pads.map { it.slot }, Mpc2Limits.gridPads(saved).map { it.slot })
            assertNotNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, saved))
            val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("mpc2"))
            val findings = ExportReadBack.verify(saved, folder)
            assertFalse(findings.blocked(), findings.joinToString { it.message })
            val out = root.resolve("mpc2")
            val xpm = out.walk().first { it.name.endsWith(".xpm") }.readText()
            val instruments = xpm.split("<Instrument number=").drop(1)
            for ((i, pad) in saved.pads.withIndex()) {
                val block = instruments.first { it.startsWith("\"${pad.slot - 1}\"") }
                val exported = WavReader.read(out.walk().first { it.name == pad.sampleFile })
                val chain = pad.chain!!
                for ((z, zone) in chain.zones!!.withIndex()) {
                    val layer = block.substringAfter("<Layer number=\"${z + 1}\">").substringBefore("</Layer>")
                    val start = chain.boundaries[zone.baseSlice]
                    val end = chain.boundaries[zone.baseSlice + 1]
                    assertTrue("<SliceStart>$start</SliceStart>" in layer && "<SliceEnd>$end</SliceEnd>" in layer,
                        "${pad.displayName} touch ${z + 1}: MPC 2 plays its first take")
                    val take = pads[i].velocityTakes[z][0].samples
                    assertEquals(take.size.toLong(), end - start)
                    assertTrue(take.indices.all { abs(take[it] - exported.samples[start.toInt() + it]) <= lsb }, "${pad.displayName} touch ${z + 1}: MPC 2 PCM")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
