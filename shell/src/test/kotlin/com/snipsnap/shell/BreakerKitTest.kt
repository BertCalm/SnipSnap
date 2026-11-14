package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.Breaker
import com.snipsnap.synth.BreakerKits
import com.snipsnap.synth.BreakerVoice
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate for BREAKER's three kits, in software: each starter's sixteen
 * pads, each a velocity grid dealt by the shared robin from the pad's own recipe, followed through saving and
 * reloading, regenerating from the saved recipes, the app's pad resolver and preview mixer, and the MPC 3
 * exporter, read back from the files each step wrote. And the drum-shelf gate: the genre kits are authored
 * kits, not BREAKER at other control settings.
 */
class BreakerKitTest {
    private companion object {
        fun render(kit: BreakerKits.Kit, dir: File): Kit = assertNotNull(StarterKits.byId(kit.id)).render(BreakerKitGenerator.nameOf(kit), dir)

        /** One render per kit, shared by the tests that only read it: dealing a kit takes about fifteen seconds. */
        val shared: Map<BreakerKits.Kit, File> by lazy {
            BreakerKits.Kit.entries.associateWith { kit ->
                Files.createTempDirectory("breaker-kit-${kit.id}").toFile().also { dir ->
                    Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
                    render(kit, dir)
                }
            }
        }
    }

    private val scratch: File = Files.createTempDirectory("breaker-kit-out").toFile()

    @AfterTest fun clean() { scratch.deleteRecursively() }

    @Test
    fun `sixteen pads as laid out, each a velocity grid of re-struck takes from its own recipe`() {
        for ((which, dir) in shared) {
            val kit = KitStore.load(dir)
            val patches = BreakerKits.patches(which)
            assertEquals((1..16).toList(), kit.pads.map { it.slot }, "$which")
            assertEquals(patches.map { it.name }, kit.pads.map { it.displayName }, "$which")
            for ((pad, patch) in kit.pads.zip(patches)) {
                val chain = assertNotNull(pad.chain, "$which ${pad.displayName}")
                val zones = assertNotNull(chain.zones, "$which ${pad.displayName}")
                val (zoneCount, takes) = BreakerKits.gridFor(pad.slot, which)
                // Every synth drum gets the full grid; the recorded tambourine gets its own two layers of two takes.
                assertEquals(if (patch.voice == BreakerVoice.TAMBOURINE) 2 to 2 else 3 to 3, zoneCount to takes)
                assertEquals(zoneCount, zones.size)
                assertEquals(List(zoneCount) { takes }, zones.map { it.cycle })
                assertEquals(zoneCount * takes, chain.boundaries.size)
                // The pad keeps its sound's recipe, so it stays an editable BREAKER pad, on its kit's model:
                // BREAKER as the owner accepted it (5), the genre kits with model 6's beater.
                assertEquals(patch, Breed.recipeOf(pad)?.patch, "$which ${pad.displayName}")
                assertEquals(if (which == BreakerKits.Kit.BREAKER) 5 else 6, patch.model, "$which ${pad.displayName}")
                assertEquals(Breaker.drumClassFor(patch.voice), pad.drumClass)
                assertTrue(pad.oneShot)
            }
            assertEquals(BreakerKits.levels(which), kit.pads.map { it.level })
            // The open hats choke the closed one, and nothing else is choked, the cymbals included.
            val hats = patches.withIndex().filter { it.value.voice == BreakerVoice.HAT_CLOSED || it.value.voice == BreakerVoice.HAT_OPEN }.map { it.index + 1 }
            assertEquals(hats, kit.pads.filter { it.muteGroup == 1 }.map { it.slot }, "$which")
            assertTrue(kit.pads.filter { it.slot !in hats }.all { it.muteGroup == 0 }, "$which")
        }
    }

    @Test
    fun `the genre kits are authored kits, not BREAKER at other settings`() {
        // The owner's drum-shelf gate: distinct roles, tuning and damping, not different effect strengths on
        // the same pads. Measured on the recipes the kits save, pad by pad against BREAKER's.
        fun layout(kit: BreakerKits.Kit) = BreakerKits.patches(kit)
        val breaker = layout(BreakerKits.Kit.BREAKER)
        for (genre in listOf(BreakerKits.Kit.DUB_TANK, BreakerKits.Kit.TRIP_HOP)) {
            val pads = layout(genre)
            fun tune(i: Int, of: List<com.snipsnap.synth.BreakerPatch>) = of[i].macros["TUNE"] ?: Breaker.defaults(of[i].voice).getValue("TUNE")
            fun damp(i: Int, of: List<com.snipsnap.synth.BreakerPatch>) = of[i].macros["DAMP"] ?: .5f
            val roles = pads.indices.count { pads[it].voice != breaker[it].voice }
            val retuned = pads.indices.count { pads[it].voice == breaker[it].voice && abs(tune(it, pads) - tune(it, breaker)) > .05f }
            val damped = pads.count { abs((it.macros["DAMP"] ?: .5f) - .5f) > .05f }
            // A pad that holds the same drum at the same tuning and damping differs from BREAKER's only in its rig.
            val rigOnly = pads.indices.count { pads[it].voice == breaker[it].voice && abs(tune(it, pads) - tune(it, breaker)) <= .05f &&
                abs(damp(it, pads) - damp(it, breaker)) <= .05f }
            println("BREAKER $genre: $roles pads hold another drum than BREAKER's, $retuned more retuned, $damped damped or opened, $rigOnly differ in their rig only")
            assertTrue(roles >= 5, "$genre lays out its own roles ($roles pads)")
            assertTrue(retuned >= 4, "$genre has its own tuning ($retuned pads)")
            assertTrue(damped >= 6, "$genre has its own damping ($damped pads)")
            assertTrue(rigOnly <= 4, "$genre is not BREAKER with other effect settings ($rigOnly pads differ in the rig alone)")
        }
        // The roles the owner approved, where they sit.
        val dub = layout(BreakerKits.Kit.DUB_TANK)
        assertEquals(BreakerVoice.STICK, dub[1].voice, "Dub Tank's backbeat is the rimshot on A02")
        assertEquals(BreakerVoice.BUMP, dub[14].voice, "Dub Tank's A15 is Bump")
        assertEquals(1f, dub[4].macros["SLAP"], "the thrown snare's echo is the 375 ms one")
        val trip = layout(BreakerKits.Kit.TRIP_HOP)
        assertEquals(listOf(BreakerVoice.SNARE, BreakerVoice.KICK), listOf(trip[4].voice, trip[5].voice), "Trip hop's ghost snare and second kick")
        assertTrue(trip[4].macros.getValue("DAMP") > .8f, "the ghost snare is damped short")
        assertTrue(trip[0].macros.getValue("TUNE") < .3f && trip[1].macros.getValue("TUNE") < .3f, "the heavy kick and fat snare sit a semitone down")
        // Tempos: dub at 74, the break at 92.
        assertEquals(74f, BreakerKits.Kit.DUB_TANK.bpm)
        assertEquals(92f, BreakerKits.Kit.TRIP_HOP.bpm)
    }

    @Test
    fun `every take is another strike, and every softer zone a different strike, not the hard one turned down`() {
        for ((which, dir) in shared) {
            val grids = BreakerKitProof.measure(KitStore.load(dir), dir)
            println("BREAKER $which grid:\n" + grids.joinToString("\n") {
                "${it.name}: ${it.zones}x${it.takes}, closest takes ${"%.1f".format(it.takesApartDb)} dB apart, " +
                    "least-moved soft zone ${"%.1f".format(it.leastZoneMoveDb)} dB"
            })
            // What a copy measures, against what a re-strike does: the robin's dealing of one strike (a shave off
            // its front and a level nudge) correlates at 1 within rounding, and a zone that was the hard take
            // turned down moves the spectrum's shape by 0 (docs/BREAKER.md, "The kit").
            for (g in grids) {
                assertTrue(g.closestTakes < BreakerKitProof.COPY_LIKENESS, "$which ${g.name}: two takes are copies of one strike ($g)")
                assertTrue(g.leastZoneMoveDb > BreakerKitProof.LEVEL_ONLY_DB, "$which ${g.name}: a softer zone is the hard one turned down ($g)")
            }
        }
    }

    @Test
    fun `every kit survives saving and reloading, and BREAKER regenerates from its saved recipes byte for byte`() {
        for ((which, dir) in shared) {
            val saved = KitStore.load(dir)
            assertEquals(BreakerKits.patches(which), saved.pads.map { Breed.recipeOf(it)?.patch }, "$which: the recipes reload")
        }
        val dir = shared.getValue(BreakerKits.Kit.BREAKER)
        val saved = KitStore.load(dir)
        val again = File(scratch, "again")
        // Rendered into a fresh folder, saved, and loaded back: the same kit, every field.
        assertEquals(render(BreakerKits.Kit.BREAKER, again), KitStore.load(again))
        val regenerated = KitStore.load(again)
        assertEquals(saved.pads.map { it.chain }, regenerated.pads.map { it.chain })
        assertEquals(saved.pads.map { it.recipe }, regenerated.pads.map { it.recipe })
        for ((a, b) in saved.pads.zip(regenerated.pads)) {
            assertTrue(File(dir, a.sampleFile).readBytes().contentEquals(File(again, b.sampleFile).readBytes()), "${a.displayName}: the grid regenerates")
        }
    }

    @Test
    fun `playback picks the zone by velocity, deals the takes in order and repeats them`() {
        for ((which, dir) in shared) {
            val saved = KitStore.load(dir)
            for (pad in saved.pads) {
                val frames = WavReader.read(File(dir, pad.sampleFile)).frameCount.toLong()
                fun start(velocity: Float, hit: Int) = assertNotNull(PadHit.resolve(pad, velocity, hit) { frames }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                val anchors = zones.map { zone ->
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(zone.cycle * 2) { start(velocity, it) }
                    assertEquals(zone.cycle, starts.take(zone.cycle).toSet().size, "$which ${pad.displayName}: a fresh take per hit")
                    assertEquals(starts.take(zone.cycle), starts.drop(zone.cycle), "$which ${pad.displayName}: the cycle repeats")
                    assertEquals(starts.take(zone.cycle).sorted(), starts.take(zone.cycle), "$which ${pad.displayName}: in order")
                    starts.first()
                }
                assertEquals(zones.size, anchors.toSet().size, "$which ${pad.displayName}: each zone is its own slices")
            }
            // The preview mixer the app plays grooves through cycles the takes too: eight hits on A02 differ
            // from the same eight with every zone pinned to its first take.
            val two = Mpc3Clip("A02", 2, (0..7).map { Mpc3Note(Mpc3Note.noteFor(2), it * 480L, .9f) })
            val cycling = KitPreview.render(saved, dir, two, which.bpm)
            val pinned = saved.copy(pads = saved.pads.map { p -> p.copy(chain = p.chain?.let { c -> c.copy(zones = c.zones?.map { it.copy(cycle = 1) }) }) })
            assertFalse(cycling.samples.contentEquals(KitPreview.render(pinned, dir, two, which.bpm).samples), "$which")
        }
    }

    @Test
    fun `the MPC 3 export carries every zone and take, and its samples are the saved ones`() {
        for ((which, dir) in shared) {
            val saved = KitStore.load(dir)
            val export = Mpc3Exporter.exportTrack(saved, dir, File(scratch, "MPC3-${which.id}"), overwrite = true)
            // The exported track's velocity ranges, base slices, cycle lengths and slice windows.
            TriadKitProof.verifyTrack(export.program, saved)
            val data = export.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)).blocked(), "$which")
            for (pad in saved.pads) {
                val mine = WavReader.read(File(dir, pad.sampleFile))
                val theirs = WavReader.read(File(data, pad.sampleFile))
                assertEquals(mine.frameCount, theirs.frameCount, "$which ${pad.displayName}")
                assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= 3f / 8388608 }, "$which ${pad.displayName}: exported PCM")
            }
        }
    }

    @Test
    fun `each kit is on the shelf once, at its tempo`() {
        for (kit in BreakerKits.Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm, starter.tempoBpm)
            assertEquals(kit.title, starter.displayName)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
        }
    }
}
