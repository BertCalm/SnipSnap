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
 * The owner's velocity and round-robin gate for the BREAKER kit, in software: the starter's sixteen pads, each
 * a velocity grid dealt by the shared robin from the pad's own recipe, followed through saving and reloading,
 * regenerating from the saved recipes, the app's pad resolver and preview mixer, and the MPC 3 exporter, read
 * back from the files each step wrote.
 */
class BreakerKitTest {
    private companion object {
        fun render(dir: File): Kit = assertNotNull(StarterKits.byId(BreakerKits.ID)).render("Breaker", dir)

        /** One render shared by the tests that only read it: dealing the kit takes about fifteen seconds. */
        val shared: File by lazy {
            Files.createTempDirectory("breaker-kit").toFile().also { dir ->
                Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
                render(dir)
            }
        }
    }

    private val kitDir: File get() = shared
    private val scratch: File = Files.createTempDirectory("breaker-kit-out").toFile()

    @AfterTest fun clean() { scratch.deleteRecursively() }

    @Test
    fun `sixteen pads as agreed, each a velocity grid of re-struck takes from its own recipe`() {
        val kit = KitStore.load(kitDir)
        val patches = BreakerKits.patches()
        assertEquals((1..16).toList(), kit.pads.map { it.slot })
        assertEquals(patches.map { it.name }, kit.pads.map { it.displayName })
        for ((pad, patch) in kit.pads.zip(patches)) {
            val chain = assertNotNull(pad.chain, pad.displayName)
            val zones = assertNotNull(chain.zones, pad.displayName)
            val (zoneCount, takes) = BreakerKits.gridFor(pad.slot)
            // Every synth drum gets the full grid; the recorded tambourine gets its own two layers of two takes.
            assertEquals(if (patch.voice == BreakerVoice.TAMBOURINE) 2 to 2 else 3 to 3, zoneCount to takes)
            assertEquals(zoneCount, zones.size)
            assertEquals(List(zoneCount) { takes }, zones.map { it.cycle })
            assertEquals(zoneCount * takes, chain.boundaries.size)
            // The pad keeps its sound's recipe, so it stays an editable BREAKER pad.
            assertEquals(patch, Breed.recipeOf(pad)?.patch, pad.displayName)
            assertEquals(Breaker.drumClassFor(patch.voice), pad.drumClass)
            assertTrue(pad.oneShot)
        }
        assertEquals(BreakerKits.levels(), kit.pads.map { it.level })
        // The open hat chokes the closed one, and nothing else is choked, the cymbals included.
        assertEquals(listOf(3, 4), kit.pads.filter { it.muteGroup == 1 }.map { it.slot })
        assertTrue(kit.pads.filter { it.slot !in 3..4 }.all { it.muteGroup == 0 })
    }

    @Test
    fun `every take is another strike, and every softer zone a different strike, not the hard one turned down`() {
        val grids = BreakerKitProof.measure(KitStore.load(kitDir), kitDir)
        println("BREAKER kit grid:\n" + grids.joinToString("\n") {
            "${it.name}: ${it.zones}x${it.takes}, closest takes ${"%.1f".format(it.takesApartDb)} dB apart, " +
                "least-moved soft zone ${"%.1f".format(it.leastZoneMoveDb)} dB"
        })
        // What a copy measures, against what a re-strike does: the robin's dealing of one strike (a shave off
        // its front and a level nudge) correlates at 1 within rounding, and a zone that was the hard take
        // turned down moves the spectrum's shape by 0. The weakest pads are the crushed kicks, whose takes
        // sit 34 dB apart and whose soft zones move about 1 dB (docs/BREAKER.md, "The kit").
        for (g in grids) {
            assertTrue(g.closestTakes < BreakerKitProof.COPY_LIKENESS, "${g.name}: two takes are copies of one strike ($g)")
            assertTrue(g.leastZoneMoveDb > BreakerKitProof.LEVEL_ONLY_DB, "${g.name}: a softer zone is the hard one turned down ($g)")
        }
    }

    @Test
    fun `the grid survives saving and reloading, and regenerates from the saved recipes byte for byte`() {
        val saved = KitStore.load(kitDir)
        val again = File(scratch, "again")
        // Rendered into a fresh folder, saved, and loaded back: the same kit, every field.
        assertEquals(render(again), KitStore.load(again))
        val regenerated = KitStore.load(again)
        assertEquals(saved.pads.map { it.chain }, regenerated.pads.map { it.chain })
        assertEquals(saved.pads.map { it.recipe }, regenerated.pads.map { it.recipe })
        for ((a, b) in saved.pads.zip(regenerated.pads)) {
            assertTrue(File(kitDir, a.sampleFile).readBytes().contentEquals(File(again, b.sampleFile).readBytes()), "${a.displayName}: the grid regenerates")
        }
    }

    @Test
    fun `playback picks the zone by velocity, deals the takes in order and repeats them`() {
        val saved = KitStore.load(kitDir)
        for (pad in saved.pads) {
            val frames = WavReader.read(File(kitDir, pad.sampleFile)).frameCount.toLong()
            fun start(velocity: Float, hit: Int) = assertNotNull(PadHit.resolve(pad, velocity, hit) { frames }).startFrame
            val zones = assertNotNull(pad.chain?.zones)
            val anchors = zones.map { zone ->
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                val starts = List(zone.cycle * 2) { start(velocity, it) }
                assertEquals(zone.cycle, starts.take(zone.cycle).toSet().size, "${pad.displayName}: a fresh take per hit")
                assertEquals(starts.take(zone.cycle), starts.drop(zone.cycle), "${pad.displayName}: the cycle repeats")
                assertEquals(starts.take(zone.cycle).sorted(), starts.take(zone.cycle), "${pad.displayName}: in order")
                starts.first()
            }
            assertEquals(zones.size, anchors.toSet().size, "${pad.displayName}: each zone is its own slices")
        }
        // The preview mixer the app plays grooves through cycles the takes too: eight hits on the snare
        // differ from the same eight with every zone pinned to its first take.
        val bap = Mpc3Clip("Bap", 2, (0..7).map { Mpc3Note(Mpc3Note.noteFor(2), it * 480L, .9f) })
        val cycling = KitPreview.render(saved, kitDir, bap, BreakerKits.BPM)
        val pinned = saved.copy(pads = saved.pads.map { p -> p.copy(chain = p.chain?.let { c -> c.copy(zones = c.zones?.map { it.copy(cycle = 1) }) }) })
        assertFalse(cycling.samples.contentEquals(KitPreview.render(pinned, kitDir, bap, BreakerKits.BPM).samples))
    }

    @Test
    fun `the MPC 3 export carries every zone and take, and its samples are the saved ones`() {
        val saved = KitStore.load(kitDir)
        val export = Mpc3Exporter.exportTrack(saved, kitDir, File(scratch, "MPC3"), overwrite = true)
        // The exported track's velocity ranges, base slices, cycle lengths and slice windows.
        TriadKitProof.verifyTrack(export.program, saved)
        val data = export.samples.first().parentFile
        assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)).blocked())
        for (pad in saved.pads) {
            val mine = WavReader.read(File(kitDir, pad.sampleFile))
            val theirs = WavReader.read(File(data, pad.sampleFile))
            assertEquals(mine.frameCount, theirs.frameCount, pad.displayName)
            assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= 3f / 8388608 }, "${pad.displayName}: exported PCM")
        }
    }

    @Test
    fun `on the shelf once, at the tempo it was heard at`() {
        val starter = assertNotNull(StarterKits.byId(BreakerKits.ID))
        assertEquals(BreakerKits.BPM, starter.tempoBpm)
        assertFalse(starter.seeded)
        assertEquals(1, StarterShelf.GROUPS.count { BreakerKits.ID in it.ids })
    }
}
