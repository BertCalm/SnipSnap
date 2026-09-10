package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * READ BACK on every MPC format the wizard writes: a kit whose pads carry
 * every field the verifier compares — level, pan, both tunes, a mute
 * group, a shape, and a two-zone velocity layer — must read back with no
 * FAIL through the real writers and the real reader. Then the two
 * negatives: a file mangled after the write is a FAIL in words naming the
 * field, and a file that won't parse at all is a FAIL, never a throw.
 */
class ExportReadBackTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("readback").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun tone(hz: Double, frames: Int = 3_000): Snip =
        Snip(FloatArray(frames) { i -> (sin(2 * PI * hz * i / 44_100) * 0.5).toFloat() }, 1, 44_100)

    private fun kit(dir: File): Kit {
        dir.mkdirs()
        WavWriter.write(File(dir, "Kick.wav"), tone(60.0))
        WavWriter.write(File(dir, "Snare Soft.wav"), tone(200.0))
        WavWriter.write(File(dir, "Snare Hard.wav"), tone(220.0))
        WavWriter.write(File(dir, "Hat.wav"), tone(4000.0, frames = 1_500))
        val kit = Kit(
            "ReadBackKit",
            listOf(
                KitPad(
                    slot = 1, sampleFile = "Kick.wav", drumClass = DrumClass.KICK,
                    level = 0.8f, pan = 0.3f, tuneCoarse = 3, tuneFine = -12, muteGroup = 0,
                    attack = 0.1f, decay = 0.4f, cutoff = 0.7f, resonance = 0.2f,
                ),
                KitPad(
                    slot = 2, sampleFile = "Snare Hard.wav", drumClass = DrumClass.SNARE,
                    velocityLayers = listOf(KitLayer("Snare Soft.wav", 1, 63), KitLayer("Snare Hard.wav", 64, 127)),
                ),
                KitPad(slot = 5, sampleFile = "Hat.wav", drumClass = DrumClass.HAT_CLOSED, muteGroup = 1),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    private val mpcFormats = listOf(
        ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION, ExportFormat.XPN,
        ExportFormat.MPC3_TRACK, ExportFormat.MPC3_PROJECT,
    )

    @Test
    fun `every MPC format reads back clean through the real writers and the real reader`() {
        val dir = File(temp, "src")
        val kit = kit(dir)
        for (format in mpcFormats) {
            val outcome = Exporters.export(format, kit, dir, File(temp, "card-${format.id}"), overwrite = true)
            val findings = ExportReadBack.verify(kit, outcome)
            val fails = findings.filter { it.severity == Severity.FAIL }
            assertTrue(fails.isEmpty(), "$format: " + fails.joinToString(" | ") { it.message })
            // One OK row per pad, plus the header line naming the file.
            assertEquals(kit.pads.size + 1, findings.count { it.severity == Severity.OK }, "$format: OK rows")
            assertTrue(findings.any { it.severity == Severity.SKIP && "not checked" in it.message }, "$format: says what it didn't check")
        }
    }

    @Test
    fun `a format X-Ray can't read is one SKIP row, not a verdict`() {
        val dir = File(temp, "midi-src")
        val kit = kit(dir)
        val outcome = Exporters.export(ExportFormat.MIDI, kit, dir, File(temp, "card-midi"), overwrite = true)
        val findings = ExportReadBack.verify(kit, outcome)
        assertEquals(1, findings.size)
        assertEquals(Severity.SKIP, findings.single().severity)
    }

    @Test
    fun `a program mangled after the write is a FAIL that names the field and both values`() {
        val dir = File(temp, "mangle-src")
        val kit = kit(dir)
        val outcome = Exporters.export(ExportFormat.PROGRAM_FOLDER, kit, dir, File(temp, "card-mangle"), overwrite = true)
        val xpm = outcome.primary
        val text = xpm.readText()
        // Pad A01's coarse tune is the only 3 in the file - the program-level
        // TuneCoarse is a literal 0 - so this edit lands on exactly one pad.
        assertTrue("<TuneCoarse>3</TuneCoarse>" in text)
        xpm.writeText(text.replace("<TuneCoarse>3</TuneCoarse>", "<TuneCoarse>4</TuneCoarse>"))

        val findings = ExportReadBack.verify(kit, outcome)
        val fail = findings.single { it.severity == Severity.FAIL }
        assertEquals(1, fail.slot)
        assertTrue("tune 4 (asked 3)" in fail.message, fail.message)
        // The other pads still read back - one bad field never poisons the rest.
        assertEquals(kit.pads.size - 1 + 1, findings.count { it.severity == Severity.OK })
    }

    @Test
    fun `a file that won't parse is a FAIL in words, never a throw`() {
        val dir = File(temp, "garbage-src")
        val kit = kit(dir)
        val outcome = Exporters.export(ExportFormat.MPC3_TRACK, kit, dir, File(temp, "card-garbage"), overwrite = true)
        outcome.primary.writeText("this is not a track")

        val findings = ExportReadBack.verify(kit, outcome)
        assertTrue(findings.any { it.severity == Severity.FAIL }, findings.joinToString { it.message })
        assertFalse(findings.any { it.severity == Severity.OK }, "nothing reads back from garbage")
    }

    @Test
    fun `SKIP never blocks`() {
        assertFalse(listOf(Finding(Severity.SKIP, "not checked")).blocked())
    }
}
