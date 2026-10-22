package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SynthWorkTest {
    private fun recipe() = PadRecipe(
        OnsetPatch("KEPT KICK", OnsetVoice.KICK,
            Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .25f), modelVersion = 2),
        FxChain(reverse = true), treatment = "KEPT RACK", amount = .42f, alias = true,
    )

    private fun wav(recipe: PadRecipe): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, recipe.render())
    }.toByteArray()

    @Test
    fun `complete edited recipe and relative update target survive a bench state round trip`() {
        val shelf = kotlin.io.path.createTempDirectory("synth-work").toFile()
        try {
            val kit = File(shelf, "KEPT KIT").apply { mkdirs() }
            val other = File(shelf, "OTHER KIT").apply { mkdirs() }
            val settings = mutableMapOf("engine" to "ONSET", "macro.ONSET.KICK.BLEND" to ".25")
            val source = assertNotNull(SynthWork.source(shelf, kit, 17))
            val original = recipe()
            SynthWork.replace(settings, listOf(SynthWork.Working(original, source)))
            val loaded = SynthWork.read(settings.toMap()).single()
            val patch = assertNotNull(loaded.recipe.patch)
            val changed = loaded.recipe.copy(patch = Patches.edited(patch, patch.name,
                patch.macros + ("BLEND" to .85f) + ("DECAY" to .6f)))
            SynthWork.replace(settings, listOf(loaded.copy(recipe = changed)))

            // The navigation bag holds only strings; no remembered object is
            // available to the next bench composition.
            val restored = SynthWork.read(settings.toMap().toMutableMap()).single()
            assertEquals(changed, restored.recipe)
            assertEquals(2, (restored.recipe.patch as OnsetPatch).modelVersion)
            assertEquals(.85f, restored.recipe.patch!!.macros.getValue("BLEND"))
            assertContentEquals(wav(changed), wav(restored.recipe))
            assertTrue(restored.source!!.matches(shelf, kit))
            assertFalse(restored.source!!.matches(shelf, other), "a changed open kit cannot inherit UPDATE")
            assertFalse(settings.values.any { shelf.absolutePath in it }, "private absolute paths stay out of the bag")
            assertEquals(".25", settings["macro.ONSET.KICK.BLEND"], "work does not rewrite unrelated bag fields")

            SynthWork.replace(settings, emptyList())
            assertTrue(SynthWork.read(settings).isEmpty(), "loading a new preset clears the reopened recipe")
            assertEquals("ONSET", settings["engine"])
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `corrupt unknown and mismatched work is ignored without changing navigation state`() {
        val valid = mutableMapOf<String, String>()
        SynthWork.replace(valid, listOf(SynthWork.Working(recipe(), SynthWork.Source("KEPT KIT", 17))))
        val (key, json) = valid.entries.single()
        val cases = listOf(
            key to "{broken",
            key to json.replace("ONSET", "FUTURE"),
            "workingRecipe.ONSET.SNARE" to json,
            key to json.replace("KEPT KIT", "/private/other"),
        )
        for ((badKey, value) in cases) {
            val settings = mutableMapOf("engine" to "SKIN", badKey to value)
            val before = settings.toMap()
            assertTrue(SynthWork.read(settings).isEmpty())
            assertEquals(before, settings, "reading a stale bag must not mutate it")
        }
    }
}
