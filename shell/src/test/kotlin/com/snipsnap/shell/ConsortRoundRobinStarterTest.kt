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
import com.snipsnap.synth.ConsortRoundRobinKits
import com.snipsnap.synth.Restrike
import com.snipsnap.synth.SynthKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * CONSORT TAKES, the opt-in round robin for the CONSORT starter: the grid through the shelf, saving,
 * reloading, preflight, the app's pad resolver and both MPC exports, read back from the files each step
 * wrote. Each touch (soft, default, hard breath) deals two takes (engine takes 0 and 2). MPC 3 carries
 * both; MPC 2 keeps each touch's first take (the recorded format limit). ROBIN re-strikes CONSORT pads.
 */
class ConsortRoundRobinStarterTest {
    private val id = ConsortRoundRobinKits.ID
    private val lsb = 3f / 8388608

    @Test fun `CONSORT TAKES sits right after CONSORT among the winds and is appended after it`() {
        val starter = assertNotNull(StarterKits.byId(id))
        assertEquals("consort-round-robin", id)
        assertEquals("CONSORT TAKES", starter.displayName)
        assertTrue(starter.displayName.length <= UserPresets.MAX_NAME, "${starter.displayName} fits a name")
        assertFalse(starter.seeded)
        assertEquals(KeySpec.parse("Cminpent"), starter.key)
        val consort = assertNotNull(StarterKits.byId(StarterKits.CONSORT_ID))
        assertEquals("CONSORT", consort.displayName)
        assertFalse(consort.seeded)
        val group = StarterShelf.GROUPS.single { id in it.ids }
        assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == id } })
        assertEquals(group.ids.indexOf(StarterKits.CONSORT_ID) + 1, group.ids.indexOf(id))
        assertTrue("embouchure" in group.ids && "bellows" in group.ids, "CONSORT sits with the winds")
        val ids = StarterKits.ALL.map { it.id }
        assertEquals(StarterKits.CONSORT_ID, ids[ids.indexOf(id) - 1], "the stored starter order only grows at the end")
        assertEquals(com.snipsnap.synth.AccordRoundRobinKits.ID, ids[ids.indexOf(StarterKits.CONSORT_ID) - 1])
    }

    @Test fun `ROBIN re-strikes a CONSORT pad with the engine's own takes, breath scaled by the hit`() {
        val patch = SynthKits.consortPatches()[8]
        assertTrue(Restrike.canRestrike(patch))
        val first = assertNotNull(Restrike.take(patch, 1f, seed = 5, take = 0)).samples
        assertContentEquals(patch.render().samples, first, "take 0 at full hit is the saved pad")
        val second = assertNotNull(Restrike.take(patch, 1f, seed = 5, take = 1)).samples
        assertContentEquals(patch.copy(take = 1).render().samples, second)
        assertFalse(first.contentEquals(second), "a re-strike is another way of playing")
        val soft = assertNotNull(Restrike.take(patch, .5f, seed = 5, take = 0)).samples
        assertContentEquals(patch.copy(velocity = patch.velocity * .5f).render().samples, soft, "a softer hit is less breath")
    }

    @Test fun `every take survives saving reloading the resolver and both MPC exports as rendered`() {
        val root = createTempDirectory("consort-rr").toFile()
        try {
            val pads = ConsortRoundRobinKits.build()
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(id)).render("Consort Takes", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved, "the saved kit must retain every source field")
            assertEquals(KeySpec.parse("Cminpent"), saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            val patches = ConsortRoundRobinKits.patches()
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })
            // Every take of every touch on disk, dealt by the resolver in order, cycling.
            TriadKitProof.verify(saved, dir, pads)

            val kept = SynthKits.consort()
            for ((i, pad) in saved.pads.withIndex()) {
                val patch = patches[i]
                assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite takes")
                assertNull(pad.recipe)
                assertEquals(kept[i]!!.drumClass, pad.drumClass)
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(3, zones.size)
                assertEquals(listOf(2, 2, 2), zones.map { it.cycle })
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(1, wav.channels)
                assertEquals(44_100, wav.sampleRate)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val hits = List(8) { assertNotNull(PadHit.resolve(pad, velocity, it) { wav.frameCount.toLong() }) }
                    val starts = hits.map { it.startFrame }
                    assertEquals((0 until 2).map { pad.chain!!.boundaries[zone.baseSlice + it] }, starts.take(2), "${pad.displayName} zone $z: takes 1-2")
                    assertEquals(List(4) { starts.take(2) }.flatten(), starts, "${pad.displayName} zone $z: the cycle repeats")
                    assertTrue(hits.all { it.pitchRatio == 1.0 && it.loopStartFrame == -1L })
                    // Each take on disk is the engine's render of that touch and take, to PCM24.
                    for (k in 0 until 2) {
                        val take = ConsortRoundRobinKits.render(patch, ConsortRoundRobinKits.TOUCHES[z], ConsortRoundRobinKits.TAKES[k]).samples
                        val at = pad.chain!!.boundaries[zone.baseSlice + k].toInt()
                        assertTrue(take.indices.all { abs(take[it] - wav.samples[at + it]) <= lsb }, "${pad.displayName} touch ${z + 1} take ${k + 1} on disk")
                    }
                }
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
