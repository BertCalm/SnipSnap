package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportFormatsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("exportformats").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the XPJ line says what actually rides along`() {
        assertEquals("MPC SESSION (.XPJ) — KITS + GROOVES", ExportFormat.MPC3_PROJECT.cyclerLabel)
        assertEquals("xpj", ExportFormat.MPC3_PROJECT.id, "the CLI word does not move")
    }

    @Test
    fun `every format's id is unique and lowercase`() {
        val ids = ExportFormat.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "two formats share an id")
        assertEquals(ids.map { it.lowercase() }, ids, "ids are the CLI's words, lowercase")
    }

    @Test
    fun `exactly PROGRAM_FOLDER and EXPANSION self-nest`() {
        val expected = setOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION)
        assertEquals(expected, ExportFormat.entries.filter { it.selfNesting }.toSet())
    }

    private fun clip(name: String) = Mpc3Clip(name, 1, listOf(Mpc3Note(36, 0, 1f, 240)))

    private fun buildKit(dir: File, name: String): Kit {
        dir.mkdirs()
        WavWriter.write(
            File(dir, "A01_Kick_01.wav"),
            Cleanup.process(Snip(FloatArray(4410) { i -> (0.5 * Math.sin(i / 12.0)).toFloat() }, 1, 44_100)),
        )
        val kit = Kit(name, listOf(KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK)))
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `H2 - PROG E survives the xtd four-clip cap alongside the base`() {
        val dir = File(temp, "capped")
        val kit = buildKit(dir, "Capped Kit")

        val base = clip("Capped Kit")
        val e = clip(GrooveEdit.progEName(base.name))
        // base + 4 derived variants + E = 6 stored clips, well past the
        // container's 4-slot cap — E rode in last via GrooveEdit.save(),
        // so a plain .take(4) would have silently dropped it.
        val variants = listOf(clip("Variant 1"), clip("Variant 2"), clip("Variant 3"), clip("Variant 4"))
        GrooveStore.save(dir, listOf(base) + variants + e)

        val result = Exporters.export(ExportFormat.MPC3_TRACK, kit, dir, File(temp, "out"))
        val track = Mpc3Project.read(result.primary).tracks.first()
        val clipNames = ((track["sharedClipMap"] as com.snipsnap.json.JsonValue.Arr).items)
            .map { entry ->
                val value = (entry as com.snipsnap.json.JsonValue.Obj).entries["value"] as com.snipsnap.json.JsonValue.Obj
                (value.entries["name"] as com.snipsnap.json.JsonValue.Str).value
            }

        assertEquals(4, clipNames.size, "the container's own cap still holds")
        assertTrue(base.name in clipNames, "the captured base must survive the cap")
        assertTrue(e.name in clipNames, "PROG E must survive the cap, displacing a derived variant instead")
    }
}
