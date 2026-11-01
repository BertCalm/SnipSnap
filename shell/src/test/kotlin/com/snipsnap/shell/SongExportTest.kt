package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongExportTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("songexport").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        val s16 = Mpc3Clip.PULSES_PER_16TH
        GrooveStore.save(
            m.kitDir,
            listOf(Mpc3Clip("Groove", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 4 * s16, 0.85f), Mpc3Note(36, 8 * s16, 0.9f), Mpc3Note(37, 12 * s16, 0.9f)))),
        )
        return m
    }

    private fun sequenceNames(xpj: File): List<String> {
        val project = Mpc3Project.read(xpj)
        assertTrue(project.isProject, "the reader takes it as a project")
        return (project.data!!["sequences"] as JsonValue.Arr).items.map { seq ->
            val value = (seq as JsonValue.Obj).entries["value"] as JsonValue.Obj
            (value.entries["name"] as JsonValue.Str).value
        }
    }

    @Test
    fun `the song lands as numbered sequences, and no WAV unless asked`() {
        val m = model("Export")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val dest = File(temp, "card")
        val written = SongExport.write(m.kit, m.kitDir, plan, dest)

        assertEquals(File(dest, "Export Song.xpj"), written.project)
        assertEquals(plan.sections.mapIndexed { i, s -> "%02d %s".format(java.util.Locale.ROOT, i + 1, s.name) }, sequenceNames(written.project))
        assertNull(written.mixdown, "no mixdown unless asked")
        assertFalse(File(dest, "Export Song.wav").exists())
    }

    @Test
    fun `the mixdown is the Arranger's mix, written beside the project`() {
        val m = model("Mixed")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val written = SongExport.write(m.kit, m.kitDir, plan, File(temp, "card"), mixdown = true)

        val mixdown = written.mixdown!!
        assertEquals(File(temp, "card/Mixed Song.wav"), mixdown.file)
        assertEquals(Arranger.mixdown(m.kit, m.kitDir, plan).snip.frameCount, mixdown.mix.snip.frameCount)
        assertEquals(mixdown.mix.snip.frameCount, WavReader.read(mixdown.file).frameCount, "the WAV holds the whole mix")
    }

    @Test
    fun `an existing WAV refuses before anything is written`() {
        // The CLI used to write the project first and only then find the WAV,
        // so a refusal left half an export on the card.
        val m = model("Half")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val dest = File(temp, "card").apply { mkdirs() }
        val wav = File(dest, "Half Song.wav").apply { writeText("someone else's") }

        val refused = assertFailsWith<DestinationExists> { SongExport.write(m.kit, m.kitDir, plan, dest, mixdown = true) }
        assertEquals(wav, refused.path)
        assertFalse(File(dest, "Half Song.xpj").exists(), "no project written")
        assertEquals("someone else's", wav.readText(), "the WAV untouched")

        // Without a mixdown asked for, the WAV is not this export's business.
        SongExport.write(m.kit, m.kitDir, plan, dest)
        assertTrue(File(dest, "Half Song.xpj").isFile)
    }

    @Test
    fun `an existing project refuses by the file on disk, and overwrite replaces it`() {
        val m = model("Again")
        val plan = Arranger.arrange(m.kit, m.kitDir, seed = 0)
        val dest = File(temp, "card")
        val first = SongExport.write(m.kit, m.kitDir, plan, dest)
        val bytes = first.project.readBytes()

        assertEquals(first.project, assertFailsWith<DestinationExists> { SongExport.write(m.kit, m.kitDir, plan, dest) }.path)
        // Only the data folder left behind: the refusal names the folder, the thing actually there.
        first.project.delete()
        val dataDir = File(dest, "Again Song_[ProjectData]")
        assertEquals(dataDir, assertFailsWith<DestinationExists> { SongExport.write(m.kit, m.kitDir, plan, dest) }.path)

        val again = SongExport.write(m.kit, m.kitDir, plan, dest, overwrite = true)
        assertTrue(bytes.contentEquals(again.project.readBytes()), "same plan, same project bytes")
    }
}
