package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.SourceAttribution
import com.snipsnap.synth.ConfluenceAssemblyRecipe
import com.snipsnap.synth.ConfluenceRole
import com.snipsnap.synth.ConfluenceVoiceBank
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfluenceWorkshopTest {
    private fun withKit(test: (File, ConfluenceVoiceBank) -> Unit) {
        val root = createTempDirectory("confluence-workshop-").toFile()
        try {
            val bank = ConfluenceVoiceBank.bundled()
            val directory = File(root, "kit")
            KitAssembler.assembleArranged("Gathering Workshop", listOf(bank.arrangedPad(bank.preset(bank.mainPresetId).recipe)), directory)
            bank.writePortableBank(File(directory, StarterKits.CONFLUENCE_BANK_DIR))
            test(directory, bank)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()

    @Test
    fun `variation saves exactly its preview and controls without changing the original and reopens after moving`() = withKit { dir, bank ->
        val before = KitStore.load(dir)
        val original = before.pad(1)!!
        val originalBytes = File(dir, original.sampleFile).readBytes()
        val editor = ConfluenceWorkshop.open(dir, original)
        val controls = editor.initialControls.copy(foundationVoiceId = "round-oo", space = .71f,
            divergence = .42f, answerGapSeconds = .95f, foundationGain = .81f, airyGain = .51f, answerGain = .63f)
        val prepared = editor.prepare(controls)
        val preview = wav(prepared.arrangedPad.snip)
        // A player owns its returned buffers, not the render retained for MAKE VARIATION.
        prepared.arrangedPad.snip.samples.fill(0f)
        assertContentEquals(preview, wav(prepared.arrangedPad.snip))
        val fresh = KitBuilderModel.open(dir)
        assertEquals(2, ConfluenceWorkshop.firstEmptySlot(fresh))
        val added = editor.makeVariation(fresh, prepared, 2)
        fresh.save()
        val reopened = KitStore.load(dir)
        assertEquals(original, reopened.pad(1))
        assertContentEquals(originalBytes, File(dir, original.sampleFile).readBytes())
        assertContentEquals(preview, File(dir, added.sampleFile).readBytes())
        assertEquals(prepared.previewLevel, added.level)
        assertTrue(added.oneShot)
        assertEquals(null, added.recipe)
        assertTrue(ConfluenceWorkshop.isGathering(added))
        assertEquals(controls, ConfluenceWorkshop.open(dir, reopened.pad(2)!!).initialControls)
        val recipe = ConfluenceAssemblyRecipe.fromJsonText(added.source.getValue("assemblyRecipe"))
        assertEquals(bank.voice("round-oo").region, recipe.roles.single { it.role == ConfluenceRole.FOUNDATION }.region)
        assertTrue(added.source.getValue("attribution").contains("Wilkins"))
        assertTrue(added.source.getValue("licenseUrl").contains("creativecommons.org/licenses/by/4.0"))

        val moved = File(dir.parentFile, "moved")
        assertTrue(dir.copyRecursively(moved))
        val relocated = ConfluenceWorkshop.open(moved, KitStore.load(moved).pad(2)!!)
        assertContentEquals(preview, wav(relocated.prepare(relocated.initialControls).arrangedPad.snip))
    }

    @Test
    fun `occupied destinations and unrelated previews refuse without replacing either pad`() = withKit { dir, _ ->
        val model = KitBuilderModel.open(dir)
        val source = model.pad(1)!!
        val editor = ConfluenceWorkshop.open(dir, source)
        val prepared = editor.prepare(editor.initialControls)
        val taken = model.assign(2, Snip(FloatArray(512) { .1f }, 1, WavWriter.MPC_SAMPLE_RATE), DrumClass.LOOP)
        model.save()
        val bytes = File(dir, taken.sampleFile).readBytes()
        assertFailsWith<IllegalArgumentException> { editor.makeVariation(model, prepared, 2) }
        assertContentEquals(bytes, File(dir, taken.sampleFile).readBytes())
        assertEquals(source, model.pad(1))
        val another = ConfluenceWorkshop.open(dir, source)
        assertFailsWith<IllegalArgumentException> { another.makeVariation(model, prepared, 3) }
        assertEquals(null, model.pad(3))
        // Independent mixer edits stay on the source, and do not pretend to be gathering controls.
        model.update(1) { it.copy(level = .25f, cutoff = .4f, displayName = "TREATED ORIGINAL") }
        val made = editor.makeVariation(model, prepared, 3)
        assertEquals(.25f, model.pad(1)!!.level)
        assertEquals(.4f, model.pad(1)!!.cutoff)
        assertEquals(prepared.previewLevel, made.level)
        assertEquals(null, made.cutoff)
    }

    @Test
    fun `changed original audio or source recipe refuses a stale render`() = withKit { dir, _ ->
        val model = KitBuilderModel.open(dir)
        val source = model.pad(1)!!
        val editor = ConfluenceWorkshop.open(dir, source)
        val prepared = editor.prepare(editor.initialControls)
        val audio = File(dir, source.sampleFile)
        val originalBytes = audio.readBytes()
        audio.writeBytes(originalBytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertFailsWith<IllegalArgumentException> { editor.makeVariation(model, prepared, 2) }
        assertEquals(null, model.pad(2))
        audio.writeBytes(originalBytes)
        model.update(1) { it.copy(source = it.source + ("assemblyRecipe" to "changed")) }
        assertFailsWith<IllegalArgumentException> { editor.makeVariation(model, prepared, 2) }
        assertEquals(null, model.pad(2))
    }

    @Test
    fun `exact bundled fallback restores a missing bank and mismatched or corrupt banks refuse`() = withKit { dir, _ ->
        val source = KitStore.load(dir).pad(1)!!
        val local = File(dir, StarterKits.CONFLUENCE_BANK_DIR)
        assertTrue(local.deleteRecursively())
        val editor = ConfluenceWorkshop.open(dir, source)
        val prepared = editor.prepare(editor.initialControls)
        val model = KitBuilderModel.open(dir)
        editor.makeVariation(model, prepared, 2)
        model.save()
        assertNotNull(ConfluenceVoiceBank.fromDirectory(local))
        assertFailsWith<IllegalArgumentException> {
            ConfluenceWorkshop.open(dir, source.copy(source = source.source + ("bankVersion" to "999")))
        }
        assertFalse(ConfluenceWorkshop.isGathering(source.copy(source = source.source + ("roles" to "FOUNDATION,AIRY"))))
        assertFailsWith<IllegalArgumentException> {
            ConfluenceWorkshop.open(dir, source.copy(source = source.source + ("assemblyRecipe" to "{}")))
        }
        val voice = File(local, "voices/low-ah.wav")
        voice.writeBytes(voice.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertFailsWith<IllegalArgumentException> { ConfluenceWorkshop.open(dir, source) }
    }

    @Test
    fun `a restored backup retains real gathering controls and credits and can make another variation`() = withKit { dir, _ ->
        val original = KitStore.load(dir).pad(1)!!
        val backup = File(dir.parentFile, "backup.zip")
        KitBackup.backup(dir.parentFile, backup)
        val restoredRoot = File(dir.parentFile, "restored")
        val restored = KitBackup.restore(backup, restoredRoot)
        assertTrue(restored.skipped.isEmpty(), restored.skipped.toString())
        assertEquals(1, restored.kits.size)
        val restoredDir = restored.kits.single().directory
        val source = KitStore.load(restoredDir).pad(1)!!
        assertEquals(original.source, source.source)
        assertFalse(File(restoredDir, StarterKits.CONFLUENCE_BANK_DIR).exists())
        val editor = ConfluenceWorkshop.open(restoredDir, source)
        val prepared = editor.prepare(editor.initialControls.copy(space = .7f))
        val fresh = KitBuilderModel.open(restoredDir)
        editor.makeVariation(fresh, prepared, 2)
        fresh.save()
        assertTrue(File(restoredDir, "${StarterKits.CONFLUENCE_BANK_DIR}/bank.json").isFile)
        val notice = SourceAttribution.render(KitStore.load(restoredDir))!!
        for (text in listOf("Wilkins", "Seetharaman", "Wahl", "Pardo", "creativecommons.org/licenses/by/4.0", "1193957")) {
            assertTrue(notice.contains(text), "restored and edited kits retain $text")
        }
    }
}
