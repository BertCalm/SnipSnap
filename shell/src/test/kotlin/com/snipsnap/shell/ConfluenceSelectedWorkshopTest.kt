package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ConfluenceBankFiles
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.SourceAttribution
import com.snipsnap.synth.ConfluenceRecipe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfluenceSelectedWorkshopTest {
    private fun withRoot(test: (File) -> Unit) {
        val root = createTempDirectory("confluence-selected-workshop-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun wav(snip: Snip) = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()

    @Test
    fun `source is the approved PCM and all processed modes explicitly retain unconfirmed native register`() {
        val bank = ConfluenceSelectedWorkshop.bank
        val source = ConfluenceSelectedWorkshop.prepare(ConfluenceSelectedWorkshop.Settings("kulning-03", ConfluenceSelectedWorkshop.Mode.SOURCE))
        assertContentEquals(bank.rawSource("kulning-03").samples, source.snip.samples)
        assertEquals(bank.voice("kulning-03").rawRegion.assetHash, source.source["sourceHashes"])
        assertTrue(source.source.getValue("modifications").contains("No gain, fades"))
        for (mode in listOf(ConfluenceSelectedWorkshop.Mode.SOLO, ConfluenceSelectedWorkshop.Mode.PAIR, ConfluenceSelectedWorkshop.Mode.GROUP)) {
            val prepared = ConfluenceSelectedWorkshop.prepare(ConfluenceSelectedWorkshop.Settings("kulning-03", mode, .7f, .21f))
            val recipe = ConfluenceRecipe.fromJsonText(prepared.source.getValue("bankRecipe"))
            assertNull(recipe.midi)
            assertNull(recipe.regions.single().referenceMidi)
            assertEquals(0f, recipe.macros["REGISTER"])
            assertEquals(.7f * .15f, recipe.macros["DIVERGENCE"])
            assertEquals(30 * 44_100, prepared.snip.frameCount)
            assertTrue(prepared.source.getValue("modifications").contains("varispeed"))
        }
    }

    @Test
    fun `make saves the retained preview with no automatic tuning and refuses occupied slots`() = withRoot { root ->
        val model = KitBuilderModel.create("Voice Passages", File(root, "kit"))
        model.setKey(KeySpec.parse("Cminpent"))
        val settings = ConfluenceSelectedWorkshop.Settings("yaman-04", ConfluenceSelectedWorkshop.Mode.GROUP, .4f, .2f)
        val prepared = ConfluenceSelectedWorkshop.prepare(settings)
        val expected = wav(prepared.snip)
        prepared.snip.samples.fill(0f)
        val pad = ConfluenceSelectedWorkshop.makePad(model, prepared, 1)
        model.save()
        assertContentEquals(expected, File(model.kitDir, pad.sampleFile).readBytes())
        assertEquals(DrumClass.LOOP, pad.drumClass)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        assertTrue(pad.oneShot)
        assertEquals(prepared.previewLevel, pad.level)
        assertEquals(settings, ConfluenceSelectedWorkshop.openPad(model.kitDir, pad))
        assertTrue(SourceAttribution.render(model.kit)!!.contains("Kaustuv Kanti Ganguli"))
        assertFailsWith<IllegalArgumentException> { ConfluenceSelectedWorkshop.makePad(model, prepared, 1) }
        assertContentEquals(expected, File(model.kitDir, pad.sampleFile).readBytes())
        val changed = pad.copy(source = pad.source + ("sourceHashes" to "0".repeat(64)))
        assertFailsWith<IllegalArgumentException> { ConfluenceSelectedWorkshop.openPad(model.kitDir, changed) }
    }

    @Test
    fun `native backup restores the actual bank and missing bank never resolves against bundled voices`() = withRoot { root ->
        val kits = File(root, "kits")
        val model = KitBuilderModel.create("Passages", File(kits, "Passages"))
        val settings = ConfluenceSelectedWorkshop.Settings("khoomei-02", ConfluenceSelectedWorkshop.Mode.PAIR)
        val preview = ConfluenceSelectedWorkshop.prepare(settings)
        val pad = ConfluenceSelectedWorkshop.makePad(model, preview, 1)
        model.save()
        val archive = File(root, "backup.zip")
        val backup = KitBackup.backup(kits, archive)
        assertTrue(backup.skipped.isEmpty(), backup.skipped.toString())
        assertEquals(listOf("Passages"), backup.packed)
        val bank = ConfluenceBankFiles.bankDirectory(model.kitDir, ConfluenceSelectedWorkshop.bank.manifestSha256)
        assertTrue(bank.deleteRecursively())
        assertFailsWith<IllegalArgumentException> { ConfluenceSelectedWorkshop.openPad(model.kitDir, pad) }
        assertTrue(kits.deleteRecursively())
        val restored = KitBackup.restore(archive, File(root, "restored"))
        assertTrue(restored.skipped.isEmpty(), restored.skipped.toString())
        val directory = restored.kits.single().directory
        assertEquals(settings, ConfluenceSelectedWorkshop.openPad(directory, KitStore.load(directory).pads.single()))
        val restoredBank = ConfluenceBankFiles.bankDirectory(directory, ConfluenceSelectedWorkshop.bank.manifestSha256)
        assertContentEquals(ConfluenceSelectedWorkshop.bank.manifestText.toByteArray(), File(restoredBank, "bank.json").readBytes())
    }

    @Test
    fun `breed carries the exact cultural bank before the parent disappears`() = withRoot { root ->
        val mother = KitBuilderModel.create("Mother", File(root, "mother"))
        val settings = ConfluenceSelectedWorkshop.Settings("yaman-01", ConfluenceSelectedWorkshop.Mode.REFERENCE)
        val source = ConfluenceSelectedWorkshop.makePad(mother, ConfluenceSelectedWorkshop.prepare(settings), 1)
        mother.save()
        val father = KitBuilderModel.create("Father", File(root, "father"))
        father.assign(1, Snip(FloatArray(512) { .1f }, 1, 44_100))
        father.save()
        val directory = File(root, "child")
        val report = Breed.breed(mother.kitDir, father.kitDir, directory)
        assertEquals(listOf(1), report.kept)
        assertTrue(mother.kitDir.deleteRecursively())
        assertEquals(settings, ConfluenceSelectedWorkshop.openPad(directory, report.kit.pads.single()))
        assertEquals(source.source["bankManifestSha256"], report.kit.pads.single().source["bankManifestSha256"])
        assertNotNull(SourceAttribution.render(report.kit))
        assertFalse(ConfluenceSelectedWorkshop.isEditable(report.kit.pads.single().copy(source = emptyMap())))
    }
}
