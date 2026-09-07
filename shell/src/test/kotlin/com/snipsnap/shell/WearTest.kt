package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("wear").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun wornModel(name: String, plays: Int): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.enableWear()
        m.recordPlays(plays)
        m.save(accrueWear = false)
        return m
    }

    @Test
    fun `plays and saves accrue mileage - managing the ledger does not`() {
        val m = KitBuilderModel.create("Accrual", File(temp, "Accrual"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        assertEquals(null, m.kit.wear, "wear is opt-in")
        m.recordPlays(5)
        assertEquals(null, m.kit.wear, "plays before opting in age nothing")

        m.enableWear()
        m.save(accrueWear = false)
        assertEquals(0.0, m.kit.wear!!.mileage, "switching the deck on is not a mile")

        m.recordPlays(10)
        assertEquals(10.0, m.kit.wear!!.mileage)
        m.save() // a dirty save spins the tape once
        assertEquals(11.0, m.kit.wear!!.mileage)
        m.save() // a clean save has nothing to persist - no mile
        assertEquals(11.0, m.kit.wear!!.mileage)

        m.disableWear()
        m.recordPlays(100)
        assertEquals(11.0, m.kit.wear!!.mileage, "a disabled deck accrues nothing")
        m.enableWear()
        assertEquals(11.0, m.kit.wear!!.mileage, "the tape remembered while the deck was off")

        // Reopen from disk: the ledger round-trips through kit.json.
        m.save(accrueWear = false)
        assertEquals(11.0, KitBuilderModel.open(m.kitDir).kit.wear!!.mileage)
    }

    @Test
    fun `wear ages the renders, wiping the ledger is a new tape`() {
        val m = wornModel("LivingTape", plays = 800)
        val pristineBytes = m.kit.pads.associate { it.sampleFile to File(m.kitDir, it.sampleFile).readBytes() }

        val pristine = KitPreview.render(m.kit.copy(wear = null), m.kitDir)
        val worn = Wear.render(m.kit, KitPreview.render(m.kit, m.kitDir))
        assertTrue(Wear.isAudible(m.kit), "800 plays are audible")
        assertTrue(!worn.samples.contentEquals(pristine.samples), "worn preview differs from pristine")
        for ((f, bytes) in pristineBytes) {
            assertTrue(File(m.kitDir, f).readBytes().contentEquals(bytes), "$f untouched on disk")
        }

        m.resetWear()
        m.save(accrueWear = false)
        assertEquals(null, Wear.earnedW(m.kit), "zero mileage renders pristine")
        val newTape = Wear.render(m.kit, KitPreview.render(m.kit, m.kitDir))
        assertTrue(newTape.samples.contentEquals(pristine.samples), "a wiped ledger IS the new-tape sound")
    }

    @Test
    fun `a worn kit stages clean and passes preflight plus every export format`() {
        val m = wornModel("WornExport", plays = 1200)
        val stage = Wear.stageWorn(m.kit, m.kitDir, File(temp, "worn-stage"))

        // The staged twin is a real kit folder: worn WAVs, everything else carried.
        assertTrue(File(stage, "kit.json").isFile, "the sidecar rides along")
        for (pad in m.kit.pads) {
            val worn = File(stage, pad.sampleFile)
            assertTrue(worn.isFile, "${pad.sampleFile} staged")
            assertTrue(
                !worn.readBytes().contentEquals(File(m.kitDir, pad.sampleFile).readBytes()),
                "${pad.sampleFile} staged worn, not copied",
            )
        }

        // The CC6 promise holds for the worn twin: preflight green means
        // every format exports.
        val findings = Preflight.check(m.kit, stage)
        assertTrue(findings.none { it.severity == Severity.FAIL }, "worn kit passes preflight: $findings")
        for (format in ExportFormat.entries) {
            val dest = File(temp, "worn-card-${format.id}")
            val outcome = Exporters.export(format, m.kit, stage, dest, overwrite = true)
            assertTrue(outcome.primary.exists(), "${format.id} exported from the worn twin")
        }
    }
}
