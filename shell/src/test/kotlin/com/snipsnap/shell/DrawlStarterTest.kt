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
import com.snipsnap.synth.DrawlKits
import com.snipsnap.synth.DrawlPatch
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * DRAWL's starters on the shelf (round 3, 21 Nov 2026). DRAWL: sixteen finite pads that save, reload,
 * regenerate from their recipes and export to MPC 3 and MPC 2. DRAWL TAKES: the same pads with soft,
 * medium and hard picking and four takes each, through saving, reloading, preflight, the app's pad
 * resolver and both MPC exports, read back from the files each step wrote. MPC 3 carries every take;
 * MPC 2 keeps each touch's first take (the recorded format limit, owner's decision of 2026-11-14).
 */
class DrawlStarterTest {
    private val lsb = 3f / 8388608
    private val key = KeySpec.parse(DrawlKits.KEY)

    @Test fun `DRAWL and DRAWL TAKES sit together among the plucks and were appended to the stored order`() {
        val starter = assertNotNull(StarterKits.byId(DrawlKits.ID))
        val takes = assertNotNull(StarterKits.byId(DrawlKits.TAKES_ID))
        assertEquals("drawl" to "DRAWL", starter.id to starter.displayName)
        assertEquals("drawl-takes" to "DRAWL TAKES", takes.id to takes.displayName)
        for (s in listOf(starter, takes)) {
            assertTrue(s.displayName.length <= UserPresets.MAX_NAME, "${s.displayName} fits a name")
            assertFalse(s.seeded)
            assertEquals(key, s.key)
        }
        val group = StarterShelf.GROUPS.single { DrawlKits.ID in it.ids }
        assertEquals(Copy.STARTER_HEADING_PLUCKS, group.heading)
        assertEquals(2, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == DrawlKits.ID || it == DrawlKits.TAKES_ID } }, "each on the shelf once")
        assertEquals(group.ids.indexOf(DrawlKits.ID) + 1, group.ids.indexOf(DrawlKits.TAKES_ID))
        // Stored starter order only grows at the end: both were appended after ACCORD TAKES.
        val ids = StarterKits.ALL.map { it.id }
        assertEquals(com.snipsnap.synth.AccordRoundRobinKits.ID, ids[ids.indexOf(DrawlKits.ID) - 1])
        assertEquals(ids.indexOf(DrawlKits.ID) + 1, ids.indexOf(DrawlKits.TAKES_ID))
    }

    @Test fun `DRAWL saves, reloads, regenerates every pad from its recipe and exports to both MPCs`() {
        val root = createTempDirectory("drawl").toFile()
        try {
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(DrawlKits.ID)).render("Drawl", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved, "the saved kit must retain every source field")
            assertEquals(key, saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            val patches = DrawlKits.patches()
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })
            for ((i, pad) in saved.pads.withIndex()) {
                val recipe = assertNotNull(Breed.recipeOf(pad.recipe), "${pad.displayName}: no recipe")
                val patch = assertIs<DrawlPatch>(recipe.patch)
                assertEquals(patches[i], patch, "${pad.displayName}: the saved recipe")
                assertNull(recipe.fx, "${pad.displayName}: lands dry")
                assertTrue(pad.oneShot)
                assertNull(pad.chain, "${pad.displayName}: one take")
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(44_100, wav.sampleRate)
                val render = patch.render().samples
                assertEquals(render.size, wav.frameCount, pad.displayName)
                assertTrue(render.indices.all { abs(render[it] - wav.samples[it]) <= lsb }, "${pad.displayName}: regenerates its WAV")
                val hit = assertNotNull(PadHit.resolve(pad, .8f, 0) { wav.frameCount.toLong() })
                assertEquals(0L to wav.frameCount.toLong(), hit.startFrame to hit.endFrameExclusive, "${pad.displayName}: plays the whole note")
                assertTrue(hit.pitchRatio == 1.0 && hit.loopStartFrame == -1L, "${pad.displayName}: finite, never looped")
            }
            val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("mpc3"))
            val data = track.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, track.program, data, track.findings)).blocked())
            assertNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, saved), "no velocity-by-take pad: MPC 2 has nothing to warn about")
            val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("mpc2"))
            val findings = ExportReadBack.verify(saved, folder)
            assertFalse(findings.blocked(), findings.joinToString { it.message })
            for (pad in saved.pads) {
                val mine = WavReader.read(dir.resolve(pad.sampleFile))
                for (out in listOf(data, root.resolve("mpc2"))) {
                    val theirs = WavReader.read(out.walk().first { it.name == pad.sampleFile })
                    assertEquals(mine.frameCount, theirs.frameCount, pad.displayName)
                    assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= lsb }, "${pad.displayName}: exported PCM")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `every DRAWL TAKES take survives saving reloading the resolver and both MPC exports as rendered`() {
        val root = createTempDirectory("drawl-takes").toFile()
        try {
            val pads = DrawlKits.takesArranged()
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(DrawlKits.TAKES_ID)).render("Drawl Takes", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved, "the saved kit must retain every source field")
            assertEquals(key, saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            val patches = DrawlKits.patches()
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })
            // Every take of every touch on disk, dealt by the resolver in order, cycling.
            TriadKitProof.verify(saved, dir, pads)

            for ((i, pad) in saved.pads.withIndex()) {
                val patch = patches[i]
                assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite takes")
                assertNull(pad.recipe)
                assertEquals(patch, com.snipsnap.synth.Patches.fromJsonText(pad.source.getValue("recipe")), "${pad.displayName}: the recipe survives")
                assertEquals(DrawlKits.VERSION.toString(), pad.source["roundRobinVersion"])
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(listOf(0 to 41, 42 to 84, 85 to 127), zones.map { it.velStart to it.velEnd })
                assertEquals(listOf(4, 4, 4), zones.map { it.cycle })
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(1, wav.channels)
                assertEquals(44_100, wav.sampleRate)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val hits = List(8) { assertNotNull(PadHit.resolve(pad, velocity, it) { wav.frameCount.toLong() }) }
                    val starts = hits.map { it.startFrame }
                    assertEquals((0 until 4).map { pad.chain!!.boundaries[zone.baseSlice + it] }, starts.take(4), "${pad.displayName} zone $z: takes 1-4")
                    assertEquals(starts.take(4) + starts.take(4), starts, "${pad.displayName} zone $z: the cycle repeats")
                    assertTrue(hits.all { it.pitchRatio == 1.0 && it.loopStartFrame == -1L })
                }
                // A medium hit's first take plays the DRAWL pad, the engine's render, to PCM24.
                val first = assertNotNull(PadHit.resolve(pad, (zones[1].velStart + zones[1].velEnd) / 2f / 127f, 0) { wav.frameCount.toLong() })
                val render = patch.render().samples
                assertEquals(render.size.toLong(), first.endFrameExclusive - first.startFrame)
                assertTrue(render.indices.all { abs(render[it] - wav.samples[first.startFrame.toInt() + it]) <= lsb }, "${pad.displayName}: DRAWL pad on disk")
            }

            // MPC 3: every take goes out unchanged, each touch a layer dealing its four slices.
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
