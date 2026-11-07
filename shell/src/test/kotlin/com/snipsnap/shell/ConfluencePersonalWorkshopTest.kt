package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.ConfluenceVoiceBank
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfluencePersonalWorkshopTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE

    private fun take(): Snip = Snip(FloatArray(rate * 2) { frame ->
        val phase = 2 * PI * 130.81278265 * frame / rate
        (.28 * sin(phase) + .09 * sin(3 * phase) + .03 * sin(7 * phase)).toFloat()
    }, 1, rate)

    private fun prepared(): ConfluencePersonalWorkshop.Prepared =
        ConfluencePersonalWorkshop.prepare(take(), rate / 5, rate * 7 / 5)

    private fun wav(snip: Snip): ByteArray =
        ByteArrayOutputStream().also { WavWriter.write(it, snip) }.toByteArray()

    private fun withRoot(block: (File) -> Unit) {
        val root = createTempDirectory("personal-workshop-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun files(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    @Test
    fun `nonzero trim keeps exact previews through saving reopening and pad assignment`() = withRoot { root ->
        val raw = take()
        val rawSamples = raw.samples.copyOf()
        val start = rate / 5
        val end = rate * 7 / 5
        val draft = ConfluencePersonalWorkshop.prepare(raw, start, end)
        val expectedOriginal = rawSamples.copyOfRange(start, end)
        assertContentEquals(expectedOriginal, draft.original.samples)
        raw.samples.fill(0f)
        draft.original.samples.fill(0f)
        assertContentEquals(expectedOriginal, draft.original.samples)
        val previews = ConfluencePersonalWorkshop.Mode.entries.associateWith { mode ->
            wav(ConfluencePersonalWorkshop.preview(draft, mode))
        }
        val library = File(root, "library")
        val voice = ConfluencePersonalWorkshop.saveVoice(library, draft, "My held vowel", ConfluencePersonalLibrary.Origin.IMPORT)
        assertEquals(voice.id, ConfluencePersonalWorkshop.listVoices(library).single().id)
        assertEquals(ConfluencePersonalLibrary.Origin.IMPORT, voice.origin)
        voice.rawSnip.samples.fill(0f)
        assertContentEquals(rawSamples, ConfluencePersonalWorkshop.loadVoice(library, voice.id).rawSnip.samples)
        val reopened = ConfluencePersonalWorkshop.openVoice(library, voice.id)
        assertEquals(start, reopened.startFrame)
        assertEquals(end, reopened.endFrame)
        assertEquals(draft.assessment.referenceMidi, reopened.assessment.referenceMidi)
        assertEquals(draft.assessment.sourceHash, reopened.assessment.sourceHash)
        for ((mode, expected) in previews) {
            assertContentEquals(expected, wav(ConfluencePersonalWorkshop.preview(reopened, mode)), "$mode must survive a fresh disk load")
        }

        val model = KitBuilderModel.create("Personal Voice", File(root, "kit"))
        val original = model.assign(1, Snip(FloatArray(512) { .1f }, 1, rate), DrumClass.PERC)
        model.save()
        val originalBytes = File(model.kitDir, original.sampleFile).readBytes()
        val rendered = ConfluencePersonalWorkshop.preparePad(voice, ConfluencePersonalWorkshop.Mode.SOLO)
        rendered.snip.samples.fill(0f)
        val pad = ConfluencePersonalWorkshop.makePad(model, voice, rendered, 2)
        model.save()
        assertEquals(original, KitStore.load(model.kitDir).pad(1))
        assertContentEquals(originalBytes, File(model.kitDir, original.sampleFile).readBytes())
        assertContentEquals(previews.getValue(ConfluencePersonalWorkshop.Mode.SOLO), File(model.kitDir, pad.sampleFile).readBytes())
        assertEquals(ConfluencePersonalWorkshop.previewLevel, pad.level)
        assertTrue(pad.oneShot)
        assertNull(pad.recipe)
        assertEquals(voice.id, ConfluencePersonalWorkshop.openPad(model.kitDir, pad).id)
    }

    @Test
    fun `wrong voice occupied target and changed source cannot commit a stale preview`() = withRoot { root ->
        val library = File(root, "library")
        val draft = prepared()
        val a = ConfluencePersonalWorkshop.saveVoice(library, draft, "Voice A")
        val b = ConfluencePersonalWorkshop.saveVoice(library, draft, "Voice B")
        val preview = ConfluencePersonalWorkshop.preparePad(a)
        val model = KitBuilderModel.create("Destination", File(root, "kit"))
        model.assign(1, Snip(FloatArray(512) { .1f }, 1, rate), DrumClass.PERC)
        model.save()
        val before = files(model.kitDir)
        val wrongVoice = assertFailsWith<IllegalArgumentException> { ConfluencePersonalWorkshop.makePad(model, b, preview, 2) }
        assertTrue(wrongVoice.message.orEmpty().contains("Preview this personal voice"))
        val occupied = assertFailsWith<IllegalArgumentException> { ConfluencePersonalWorkshop.makePad(model, a, preview, 1) }
        assertTrue(occupied.message.orEmpty().contains("occupied"))
        assertEquals(before, files(model.kitDir))
        a.sourceFile.writeBytes(a.sourceFile.readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() })
        val changed = assertFailsWith<IllegalArgumentException> { ConfluencePersonalWorkshop.makePad(model, a, preview, 2) }
        assertTrue(changed.message.orEmpty().contains("changed"), changed.message)
        assertNull(model.pad(2))
        assertEquals(before, files(model.kitDir), "failed save must not publish a closure, audio file or metadata")
    }

    @Test
    fun `backup restores editable personal sources after the donor library and kit are gone`() = withRoot { root ->
        val library = File(root, "library")
        val voice = ConfluencePersonalWorkshop.saveVoice(library, prepared(), "Portable vowel")
        val kits = File(root, "kits")
        val model = KitBuilderModel.create("Portable Voice", File(kits, "voice"))
        for ((index, mode) in listOf(ConfluencePersonalWorkshop.Mode.ORIGINAL, ConfluencePersonalWorkshop.Mode.ENSEMBLE).withIndex()) {
            ConfluencePersonalWorkshop.makePad(model, voice, ConfluencePersonalWorkshop.preparePad(voice, mode), index + 1)
        }
        model.save()
        val pads = KitStore.load(model.kitDir).pads
        val expected = pads.associate { it.slot to File(model.kitDir, it.sampleFile).readBytes() }
        val closure = files(File(model.kitDir, ConfluencePersonalLibrary.KIT_DIR))
        val backup = File(root, "backup.zip")
        val packed = KitBackup.backup(kits, backup)
        assertEquals(listOf("Portable Voice"), packed.packed)
        assertTrue(packed.skipped.isEmpty(), packed.skipped.toString())
        assertTrue(library.deleteRecursively())
        assertTrue(kits.deleteRecursively())
        val restored = KitBackup.restore(backup, File(root, "restored"))
        assertTrue(restored.skipped.isEmpty(), restored.skipped.toString())
        val directory = restored.kits.single().directory
        assertEquals(closure, files(File(directory, ConfluencePersonalLibrary.KIT_DIR)))
        val loaded = KitStore.load(directory)
        for (before in pads) {
            val after = loaded.pad(before.slot)!!
            assertEquals(before.source, after.source)
            assertNull(after.recipe)
            assertContentEquals(expected.getValue(before.slot), File(directory, after.sampleFile).readBytes())
            val source = ConfluencePersonalWorkshop.openPad(directory, after)
            val rerendered = ConfluencePersonalWorkshop.preparePad(source, ConfluencePersonalWorkshop.savedMode(after))
            assertContentEquals(expected.getValue(before.slot), wav(rerendered.snip), "restored source must reproduce pad ${before.slot}")
        }
        val fresh = KitBuilderModel.open(directory)
        val source = ConfluencePersonalWorkshop.openPad(directory, loaded.pad(2)!!)
        val newPad = ConfluencePersonalWorkshop.makePad(fresh, source, ConfluencePersonalWorkshop.preparePad(source), 3)
        fresh.save()
        assertContentEquals(expected.getValue(2), File(directory, newPad.sampleFile).readBytes())
    }

    @Test
    fun `missing personal closure and mismatched bindings refuse without factory fallback or audio loss`() = withRoot { root ->
        val factoryBefore = ConfluenceVoiceBank.bundled().manifestText
        val voice = ConfluencePersonalWorkshop.saveVoice(File(root, "library"), prepared(), "Private vowel")
        val model = KitBuilderModel.create("Private Voice", File(root, "kit"))
        val pad = ConfluencePersonalWorkshop.makePad(model, voice, ConfluencePersonalWorkshop.preparePad(voice), 1)
        model.save()
        val audio = File(model.kitDir, pad.sampleFile).readBytes()
        assertFalse(ConfluenceWorkshop.isGathering(pad))
        val wrongBinding = pad.copy(source = pad.source + ("personalSourceSha256" to "0".repeat(64)))
        val mismatch = assertFailsWith<IllegalArgumentException> { ConfluencePersonalWorkshop.openPad(model.kitDir, wrongBinding) }
        assertTrue(mismatch.message.orEmpty().contains("does not match"))
        val closure = File(model.kitDir, ConfluencePersonalLibrary.KIT_DIR)
        assertTrue(closure.deleteRecursively())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalWorkshop.openPad(model.kitDir, pad) }
        assertContentEquals(audio, File(model.kitDir, pad.sampleFile).readBytes())
        assertEquals(factoryBefore, ConfluenceVoiceBank.bundled().manifestText)
        assertEquals(pad, KitStore.load(model.kitDir).pad(1))
    }
}
