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
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.TerraGenreRoundRobinKits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * ACCORD VOICES, the opt-in starter for the four voices kept in accord-voices-1: REED, COURSE,
 * ESCAPEMENT and BELL at C2, C3, C4 and C5, the X1 renders (AccordVoicesPinsTest pins them). The
 * kit goes through the shelf, saving, reloading, the app's pad resolver, recipe replay and both
 * MPC exports, read back from the files each step wrote. It has no velocity zones or takes, so both
 * MPC generations carry every pad whole.
 */
class AccordVoicesStarterTest {
    private val id = StarterKits.ACCORD_VOICES_ID

    @Test fun `ACCORD VOICES sits right after ACCORD on the shelf and is appended to the stored order`() {
        val starter = assertNotNull(StarterKits.byId(id))
        assertEquals("accord-voices", id)
        assertEquals("ACCORD VOICES", starter.displayName)
        assertTrue(starter.displayName.length <= UserPresets.MAX_NAME, "${starter.displayName} fits a name")
        assertFalse(starter.seeded)
        assertEquals(KeySpec.parse("Cminpent"), starter.key)
        val group = StarterShelf.GROUPS.single { id in it.ids }
        assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == id } })
        assertEquals(group.ids.indexOf("accord") + 1, group.ids.indexOf(id))
        // Stored starter order only grows at the end: ACCORD VOICES was appended after PUNJAB TAKES.
        // Later starters are appended after it, so pin its place rather than demand it stays last.
        val ids = StarterKits.ALL.map { it.id }
        assertEquals(TerraGenreRoundRobinKits.Kit.PUNJAB.id, ids[ids.indexOf(id) - 1], "the stored starter order only grows at the end")
        // The kept ACCORD starter stays where it was and plays only its three voices.
        assertEquals(ids.indexOf("obbligato") + 1, ids.indexOf("accord"))
        assertTrue(AccordKits.patches().none { it.voice in AccordKits.NEW_VOICES })
    }

    @Test fun `every pad survives saving reloading playback replay and both MPC exports as rendered`() {
        val root = createTempDirectory("accord-voices-starter").toFile()
        try {
            val dir = root.resolve("source")
            val kit = assertNotNull(StarterKits.byId(id)).render("Accord Voices Check", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved, "the saved kit must retain every recipe and source field")
            assertEquals(KeySpec.parse("Cminpent"), saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            val patches = AccordKits.voicesPatches()
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })

            for ((pad, expected) in saved.pads.zip(patches)) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(expected, assertIs<AccordPatch>(recipe.patch))
                assertNull(recipe.fx, "${pad.displayName}: starter pads land dry")
                assertEquals(Accord.drumClassFor(expected), pad.drumClass)
                assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite render")
                assertFalse(pad.loop)
                assertNull(pad.chain)
                assertTrue(pad.velocityLayers.isEmpty())
                assertEquals("FINITE_ONE_SHOT", pad.source["playback"])
                assertEquals(expected.midi.toString(), pad.source["rootMidi"])
                assertEquals(expected.velocity.toString(), pad.source["velocity"])

                // The WAV on disk is the engine's render, the one pinned and heard, to PCM24.
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(1, wav.channels)
                assertEquals(44_100, wav.sampleRate)
                val render = expected.render().samples
                assertEquals(render.size, wav.frameCount, pad.displayName)
                assertTrue(render.indices.all { abs(render[it] - wav.samples[it]) <= 3f / 8388608 }, "${pad.displayName} on disk")

                // The pad resolver plays the whole file once, at every MIDI velocity, unpitched.
                val frames = wav.frameCount.toLong()
                for (midiVelocity in listOf(1, 41, 64, 96, 127)) {
                    val hit = assertNotNull(PadHit.resolve(pad, midiVelocity / 127f, midiVelocity) { frames })
                    assertEquals(pad.sampleFile, hit.sampleFile)
                    assertEquals(0L, hit.startFrame)
                    assertEquals(frames, hit.endFrameExclusive)
                    assertEquals(1.0, hit.pitchRatio)
                    assertEquals(-1L, hit.loopStartFrame)
                }
            }

            // The first and last pads regenerate their saved WAVs exactly from the recipe alone.
            val model = KitBuilderModel.open(dir)
            for (pad in listOf(saved.pads.first(), saved.pads.last())) {
                val original = dir.resolve(pad.sampleFile).readBytes()
                val replay = RecipeReplay.apply(model, pad.slot, assertNotNull(pad.recipe), pad.displayName)
                assertContentEquals(original, dir.resolve(replay.pad.sampleFile).readBytes(), pad.displayName)
            }

            // MPC 3: every pad's PCM goes out unchanged and the track reads back.
            val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("mpc3"))
            val data = track.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, track.program, data, track.findings)).blocked())
            assertEquals(16, track.samples.size)
            for (pad in saved.pads) {
                val mine = WavReader.read(dir.resolve(pad.sampleFile))
                val theirs = WavReader.read(File(data, pad.sampleFile))
                assertEquals(mine.frameCount, theirs.frameCount, pad.displayName)
                assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= 3f / 8388608 }, "${pad.displayName}: exported PCM")
            }

            // MPC 2: no zones or takes, so nothing to note before writing, and the program folder reads back.
            assertNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, saved))
            val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("mpc2"))
            val findings = ExportReadBack.verify(saved, folder)
            assertFalse(findings.blocked(), findings.joinToString { it.message })
        } finally {
            root.deleteRecursively()
        }
    }
}
