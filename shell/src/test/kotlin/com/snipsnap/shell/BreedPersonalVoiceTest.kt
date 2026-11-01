package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitBackup
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreedPersonalVoiceTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE

    private fun withRoot(test: (File) -> Unit) {
        val root = createTempDirectory("breed-personal-voice-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun personalKit(root: File): KitBuilderModel {
        val take = Snip(FloatArray(rate * 2) { frame ->
            val phase = 2 * PI * 130.81278265 * frame / rate
            (.28 * sin(phase) + .09 * sin(3 * phase) + .03 * sin(7 * phase)).toFloat()
        }, 1, rate)
        val prepared = ConfluencePersonalWorkshop.prepare(take, rate / 5, rate * 7 / 5)
        val voice = ConfluencePersonalWorkshop.saveVoice(File(root, "library"), prepared, "My vowel")
        val model = KitBuilderModel.create("Mother", File(root, "mother"))
        val preview = ConfluencePersonalWorkshop.preparePad(voice, ConfluencePersonalWorkshop.Mode.ORIGINAL)
        ConfluencePersonalWorkshop.makePad(model, voice, preview, 1)
        ConfluencePersonalWorkshop.makePad(model, voice, preview, 2)
        model.save()
        return model
    }

    private fun partner(root: File, fx: Boolean = false): KitBuilderModel {
        val model = KitBuilderModel.create("Father", File(root, "father"))
        model.assign(1, Snip(FloatArray(rate) { .1f }, 1, rate), DrumClass.PERC)
        if (fx) model.update(1) { it.copy(recipe = PadRecipe(fx = FxChain(eq = mapOf("BASS" to .51f))).toJsonValue()) }
        model.save()
        return model
    }

    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    @Test
    fun `kept personal pads carry one exact editable closure and remain eligible for native backup`() = withRoot { root ->
        val mother = personalKit(root)
        val father = partner(root)
        val expectedClosure = hashes(File(mother.kitDir, ConfluencePersonalLibrary.KIT_DIR))
        val childRoot = File(root, "children")
        val childDir = File(childRoot, "Child")
        val report = Breed.breed(mother.kitDir, father.kitDir, childDir)
        assertEquals(listOf(1, 2), report.kept)
        assertEquals(expectedClosure, hashes(File(childDir, ConfluencePersonalLibrary.KIT_DIR)))
        assertEquals(3, expectedClosure.size, "two pads using one voice copy its immutable trio only once")
        assertTrue(File(root, "library").deleteRecursively())
        assertTrue(mother.kitDir.deleteRecursively())
        for (pad in report.kit.pads) {
            val voice = ConfluencePersonalWorkshop.openPad(childDir, pad)
            assertEquals(pad.source["personalVoiceId"], voice.id)
            assertEquals("Mother x Father", pad.source["bredFrom"])
        }
        val archive = File(root, "backup.zip")
        val backup = KitBackup.backup(childRoot, archive)
        assertEquals(listOf("Child"), backup.packed)
        assertTrue(backup.skipped.isEmpty(), backup.skipped.toString())
        assertTrue(childRoot.deleteRecursively())
        val restored = KitBackup.restore(archive, File(root, "restored"))
        assertTrue(restored.skipped.isEmpty(), restored.skipped.toString())
        val restoredDir = restored.kits.single().directory
        assertEquals(expectedClosure, hashes(File(restoredDir, ConfluencePersonalLibrary.KIT_DIR)))
        KitStore.load(restoredDir).pads.forEach { ConfluencePersonalWorkshop.openPad(restoredDir, it) }
    }

    @Test
    fun `personal A source survives an ordinary B effects cross without rebinding to B`() = withRoot { root ->
        val mother = personalKit(root)
        val father = partner(root, fx = true)
        val expectedClosure = hashes(File(mother.kitDir, ConfluencePersonalLibrary.KIT_DIR))
        val childDir = File(root, "Child")
        val report = Breed.breed(mother.kitDir, father.kitDir, childDir, seed = 0)
        assertTrue(1 in report.crossed, "a personal capture retains ordinary rack-cross behavior")
        val child = report.kit.pad(1)!!
        assertTrue(Breed.recipeOf(child)?.fx?.eq != null, "the chosen seed retains B's EQ section")
        assertEquals(mother.pad(1)!!.source["personalVoiceId"], child.source["personalVoiceId"])
        assertEquals(expectedClosure, hashes(File(childDir, ConfluencePersonalLibrary.KIT_DIR)))
        assertTrue(File(root, "library").deleteRecursively())
        assertTrue(mother.kitDir.deleteRecursively())
        assertEquals(child.source["personalVoiceId"], ConfluencePersonalWorkshop.openPad(childDir, child).id)
    }

    @Test
    fun `missing personal dependency refuses Breed before a visible child is created`() = withRoot { root ->
        val mother = personalKit(root)
        val father = partner(root, fx = true)
        val id = mother.pad(1)!!.source.getValue("personalVoiceId")
        assertTrue(File(mother.kitDir, "${ConfluencePersonalLibrary.KIT_DIR}/$id/source.wav").delete())
        val child = File(root, "Child")
        assertFailsWith<IllegalArgumentException> { Breed.breed(mother.kitDir, father.kitDir, child) }
        assertFalse(child.exists(), "source admission precedes child folder and kit metadata creation")
    }
}
