package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.MpcFormat
import com.snipsnap.mpc3.MpcFormats
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportWizardTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("wizard").toFile()

    private fun makeKit(name: String): Pair<Kit, File> {
        val dir = File(temp, name)
        val m = KitBuilderModel.create(name, dir)
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        return m.kit to dir
    }

    @Test
    fun `the stage machine - ready, writing labels, complete, eject`() {
        val (kit, dir) = makeKit("Stages")
        val w = ExportWizardModel(kit, dir)

        assertEquals(ExportWizardModel.Stage.READY, w.stage)
        assertEquals("WRITE KIT", w.writeLabel)
        assertEquals("READY TO DUB", w.dubLabel)
        assertEquals(4, w.fileCount) // 3 WAVs + the program
        assertTrue("4 FILES QUEUED" in w.dubFilesLine(0))
        assertFalse(w.blocked)

        val result = w.write(File(temp, "card-stages"))
        assertTrue(result is ExportWizardModel.WriteResult.Done)
        assertEquals(ExportWizardModel.Stage.COMPLETE, w.stage)
        assertEquals("EJECT CARD ✓", w.writeLabel)
        assertEquals("DUB COMPLETE", w.dubLabel)
        assertTrue("ALL 4 FILES ON TAPE" in w.dubFilesLine(4))

        assertFailsWith<IllegalStateException> { w.write(File(temp, "again")) }
        w.eject()
        assertEquals(ExportWizardModel.Stage.READY, w.stage)
        assertFailsWith<IllegalStateException> { w.eject() }
    }

    @Test
    fun `the format cycler walks all five and each writes for real`() {
        val (kit, dir) = makeKit("Cycler")
        val w = ExportWizardModel(kit, dir)
        assertEquals(ExportFormat.PROGRAM_FOLDER, w.format)

        val seen = mutableListOf<ExportFormat>()
        repeat(ExportFormat.entries.size) {
            seen += w.format
            val card = File(temp, "card-cycler")
            val r = w.write(card, overwrite = true)
            assertTrue(r is ExportWizardModel.WriteResult.Done, "${w.format} failed")
            assertTrue(r.outcome.primary.exists(), "${w.format}: ${r.outcome.primary} missing")
            w.eject()
            w.cycleFormat()
        }
        assertEquals(ExportFormat.entries.toList(), seen)
        assertEquals(ExportFormat.PROGRAM_FOLDER, w.format, "cycler wraps")

        // Spot-check the two native artifacts by magic bytes.
        val card = File(temp, "card-cycler")
        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(File(card, "Cycler.xtd")))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(File(card, "Cycler/Cycler.xpm")))
        assertTrue(File(card, "Cycler.xpj").isFile)
        assertTrue(File(card, "Expansions/Cycler/Expansion.xml").isFile)
        assertTrue(File(card, "Cycler.xpn").isFile)
    }

    @Test
    fun `preflight FAILs block the write and nothing lands`() {
        val (kit, dir) = makeKit("Broken")
        // Break the folder after the model loaded: a missing sample is a FAIL.
        File(dir, kit.pads.first().sampleFile).delete()

        val w = ExportWizardModel(kit, dir)
        w.runPreflight()
        assertTrue(w.blocked)

        val card = File(temp, "card-broken")
        val result = w.write(card)
        assertTrue(result is ExportWizardModel.WriteResult.Blocked)
        assertTrue(result.findings.any { it.severity == com.snipsnap.kit.Severity.FAIL })
        assertEquals(ExportWizardModel.Stage.READY, w.stage, "a blocked write never claims progress")
        assertFalse(File(card, kit.name).exists())
    }

    @Test
    fun `cycling is locked while writing is not READY`() {
        val (kit, dir) = makeKit("Locked")
        val w = ExportWizardModel(kit, dir)
        w.write(File(temp, "card-locked"))
        val before = w.format
        w.cycleFormat()
        assertEquals(before, w.format, "COMPLETE stage must not cycle")
    }

    @Test
    fun `layered pads count every zone in the dub`() {
        val dir = File(temp, "Layered")
        KitBuilderModel.create("Layered", dir).apply {
            assign(1, DrumSynth.kick(), DrumClass.KICK)
            save()
        }
        // Hand-build a layered pad on top: main + one soft zone.
        val base = KitStore.load(dir)
        com.snipsnap.audio.WavWriter.write(File(dir, "soft.wav"), DrumSynth.snare())
        com.snipsnap.audio.WavWriter.write(File(dir, "hard.wav"), DrumSynth.snare(seed = 2))
        val layered = base.copy(
            pads = base.pads + KitPad(
                slot = 2,
                sampleFile = "hard.wav",
                velocityLayers = listOf(
                    com.snipsnap.kit.KitLayer("soft.wav", 1, 63),
                    com.snipsnap.kit.KitLayer("hard.wav", 64, 127),
                ),
            ),
        )
        KitStore.save(layered, dir)
        val w = ExportWizardModel(layered, dir)
        assertEquals(4, w.fileCount) // kick + two zones + program
    }
}
