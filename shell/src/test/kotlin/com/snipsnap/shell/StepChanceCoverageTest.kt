package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import com.snipsnap.kit.BeatTape
import com.snipsnap.kit.ChanceExport
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.MidiGroove
import com.snipsnap.kit.SessionMixdown
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.DrumProgram
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The claim of BEAT step 4: a step set to 0% sounds through NO path. A path that
 * missed the roll would play every hit and say nothing, so every route from a
 * clip to sound or to notes is listed here with a one-hit clip at chance 0, and
 * the same clip at 100 as the control that proves the probe can hear.
 */
class StepChanceCoverageTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("chance-coverage").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val m = KitBuilderModel.create("Chancy", File(temp, "Chancy")).also {
        it.assign(1, DrumSynth.kick(), DrumClass.KICK)
        it.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        it.save()
    }

    private fun clipAt(chance: Int, name: String = "Kit A") =
        Mpc3Clip(name, 1, listOf(Mpc3Note(36, 0, 0.9f, chance = chance)))

    private fun silent(s: Snip) = s.samples.all { it == 0f }

    private fun plan(clip: Mpc3Clip) = Arranger.Arrangement(
        "Chancy Song", 0,
        listOf(Arranger.Section("theme", clip, 2, "test"), Arranger.Section("outro", clip, 1, "test")),
    )

    /** Every path that turns a clip into audio, as a name and a render of [clip]. */
    private fun audioPaths(clip: Mpc3Clip): Map<String, () -> Snip> = mapOf(
        "KitPreview.renderLoop" to { KitPreview.renderLoop(m.kit, m.kitDir, clip, passes = 3, tempoBpm = 120f) },
        "KitPreview.render" to { KitPreview.render(m.kit, m.kitDir, clip = clip, tempoBpm = 120f) },
        "SessionMixdown.render" to { SessionMixdown.render(listOf(m.kitDir), 120f, clipFor = { _, _ -> clip }) },
        "Arranger.mixdown" to { Arranger.mixdown(m.kit, m.kitDir, plan(clip)).snip },
        "BeatTape.render" to { GrooveStore.save(m.kitDir, listOf(clip)); BeatTape.render(listOf(m.kitDir), 2).audio },
    )

    @Test
    fun `a step at 0 percent is silent through every audio path, and the same clip at 100 is not`() {
        for ((name, render) in audioPaths(clipAt(0))) {
            assertTrue(silent(render()), "$name played a step set to 0%")
        }
        for ((name, render) in audioPaths(clipAt(100))) {
            assertFalse(silent(render()), "$name is deaf: the control clip made no sound")
        }
    }

    @Test
    fun `a step at 0 percent leaves no note in tiled, the bed or the MIDI file`() {
        val dead = clipAt(0)
        assertTrue(Arranger.tiled(dead, 1).notes.isEmpty(), "tiled x1")
        assertTrue(Arranger.tiled(dead, 3).notes.isEmpty(), "tiled x3")
        assertTrue(Arranger.bedNotes(dead, dead.lengthPulses * 2).isEmpty(), "bedNotes")
        val live = clipAt(100)
        assertEquals(3, Arranger.tiled(live, 3).notes.size)
        assertEquals(
            MidiGroove.write(Mpc3Clip("Kit A", 1, emptyList()), 120f).toList(),
            MidiGroove.write(dead, 120f).toList(),
            "MidiGroove.write: a step at 0% is the file of an empty clip",
        )
        assertEquals(1, MidiGroove.read(MidiGroove.write(live, 120f), "x").clip.notes.size)
    }

    @Test
    fun `the live scheduler hands over no note of a step at 0 percent, this pass or the next`() {
        val dead = clipAt(0)
        val total = 16f
        var handed = 0
        BeatSchedule.forEachNoteDue(dead, -1.0, 0.5, total, 0L) { _, _ -> handed++ }
        BeatSchedule.forEachNoteDue(dead, 10.0, 17.0, total, 0L) { _, _ -> handed++ } // the wrap: the note found in the next pass
        assertEquals(0, handed)
        BeatSchedule.forEachNoteDue(clipAt(100), -1.0, 0.5, total, 0L) { _, _ -> handed++ }
        BeatSchedule.forEachNoteDue(clipAt(100), 10.0, 17.0, total, 0L) { _, _ -> handed++ }
        assertEquals(2, handed, "the control clip is handed over, here and in the next pass")
    }

    @Test
    fun `the live scheduler rolls each lap on its own and the next-pass note on lap + 1`() {
        val c = Mpc3Clip("Kit A", 1, listOf(Mpc3Note(36, 0, 0.9f, chance = 50)))
        val soundedOn = (0L until 200L).filter { lap ->
            var n = 0
            BeatSchedule.forEachNoteDue(c, -1.0, 0.5, 16f, lap) { _, _ -> n++ }
            n == 1
        }
        assertTrue(soundedOn.size in 40..160 && soundedOn.size < 200, "laps that sound: ${soundedOn.size}")
        for (lap in 0L until 50L) {
            var ahead = 0
            BeatSchedule.forEachNoteDue(c, 10.0, 17.0, 16f, lap) { _, _ -> ahead++ }
            var then = 0
            BeatSchedule.forEachNoteDue(c, -1.0, 0.5, 16f, lap + 1) { _, _ -> then++ }
            assertEquals(then, ahead, "lap $lap: a note handed over early is the one its own lap would")
        }
    }

    private fun payload(clip: Mpc3Clip) =
        Mpc3TrackWriter().payloadText(DrumProgram("Chancy", emptyList()), clips = listOf(clip))

    @Test
    fun `an MPC track writes the chance as probability under WRITE and bakes it away under BAKE`() {
        val dead = clipAt(0)
        val written = payload(ChanceExport.forMpc(dead, ChanceExport.Mode.WRITE))
        assertTrue(Regex("\"probability\"\\s*:\\s*0\\b").containsMatchIn(written), "WRITE keeps the hit with probability 0")
        val baked = payload(ChanceExport.forMpc(dead, ChanceExport.Mode.BAKE))
        assertFalse(baked.contains("\"probability\""), "BAKE leaves the silent hit out of the file")
        val plain = payload(ChanceExport.forMpc(clipAt(100), ChanceExport.Mode.BAKE))
        assertTrue(Regex("\"probability\"\\s*:\\s*100\\b").containsMatchIn(plain), "a plain hit writes 100")
    }

    @Test
    fun `the song project keeps every hit with its chance under WRITE, and rolls per repeat under BAKE`() {
        val half = Mpc3Clip("Kit A", 1, listOf(Mpc3Note(36, 0, 0.9f, chance = 50)))
        val p = Arranger.Arrangement("Chancy Song", 0, listOf(Arranger.Section("theme", half, 8, "test")))

        val written = SongExport.sectionClips(p, ChanceExport.Mode.WRITE).single()
        assertEquals(8, written.notes.size, "WRITE is not pre-resolved: all eight repeats are in the file")
        assertEquals(setOf(50), written.notes.map { it.chance }.toSet())
        assertEquals("01 theme", written.name)

        val baked = SongExport.sectionClips(p, ChanceExport.Mode.BAKE).single()
        assertTrue(baked.notes.size < 8, "BAKE rolled each repeat on its own: ${baked.notes.size} of 8 survive")
        assertEquals(setOf(100), baked.notes.map { it.chance }.toSet())
        // The same laps the WAV mixdown rolls (keyed on the source's name, not the renamed section).
        assertEquals(Arranger.tiled(half, 8).notes.map { it.timePulses }, baked.notes.map { it.timePulses })

        val dead = Arranger.Arrangement("Chancy Song", 0, listOf(Arranger.Section("theme", clipAt(0), 2, "test")))
        assertTrue(SongExport.sectionClips(dead, ChanceExport.Mode.BAKE).single().notes.isEmpty())
        assertEquals(2, SongExport.sectionClips(dead, ChanceExport.Mode.WRITE).single().notes.size)
    }

    @Test
    fun `the default is WRITE`() {
        assertEquals(ChanceExport.Mode.WRITE, ChanceExport.MODE)
    }
}
