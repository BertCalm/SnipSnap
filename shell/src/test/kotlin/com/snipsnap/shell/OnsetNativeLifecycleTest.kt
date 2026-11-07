package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetPresets
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phone-side recipe work and version history, before a rendered export leaves the shelf. */
class OnsetNativeLifecycleTest {
    private fun wav(recipe: PadRecipe): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, recipe.render())
    }.toByteArray()

    @Test
    fun `native pad work returns after navigation and a version archived before UPDATE restores exact audio`() {
        val root = kotlin.io.path.createTempDirectory("onset-native-lifecycle").toFile()
        try {
            val shelf = File(root, "shelf")
            val otherKit = File(shelf, "OTHER KIT").apply { mkdirs() }
            val voices = listOf(OnsetVoice.TOM, OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL)
            for (voice in voices) {
                val patch = OnsetPresets.extensionStudy().first { it.voice == voice }.copy(name = "KEPT NATIVE")
                val original = PadRecipe(patch, FxChain(reverse = true),
                    treatment = "KEPT RACK", amount = .37f, alias = true, terminalFadeMs = 8f)
                val originalWav = wav(original)
                val dir = File(shelf, voice.name)
                val model = KitBuilderModel.create(voice.name, dir)
                val drumClass = if (voice == OnsetVoice.TOM) DrumClass.TOM else DrumClass.PERC
                val assigned = model.assign(17, original.render(), drumClass, "Kept ${voice.name}")
                model.update(17) { it.copy(recipe = original.toJsonValue(), level = .63f, pan = .32f,
                    tuneCoarse = -2, tuneFine = 7, muteGroup = 3, attack = .12f, humanize = .21f,
                    source = mapOf("engine" to "ONSET", "study" to "009", "rawSha256" to sha(originalWav))) }
                model.save()
                assertContentEquals(originalWav, File(dir, assigned.sampleFile).readBytes())

                val reopened = KitBuilderModel.open(dir)
                val before = assertNotNull(reopened.pad(17))
                val loaded = assertNotNull(Breed.recipeOf(before))
                assertEquals(original, loaded)
                // Choosing the groove tempo is a real metadata-only save. Its
                // outgoing version is archived while this original WAV is
                // still live, before UPDATE changes the sample's bytes.
                reopened.setTempo(104f)
                reopened.save()
                val beforeUpdateTake = reopened.takes().last()
                val navigation = Navigation()
                navigation.go(Place.MAKE)
                navigation.open(Bench.SYNTH)
                val settings = navigation.make.settings(Bench.SYNTH)
                settings["engine"] = "ONSET"
                settings["voice"] = voice.name
                settings["preset.ONSET.${voice.name}"] = patch.name
                val source = assertNotNull(SynthWork.source(shelf, dir, 17))
                SynthDrafts.write(settings, listOf(patch))
                SynthWork.replace(settings, listOf(SynthWork.Working(loaded, source)))

                // A slider edit clears the highlight but retains both the
                // instrument snapshot and the reopened pad's processing chain.
                val changed = assertIs<OnsetPatch>(Patches.edited(patch, patch.name,
                    patch.macros + ("BLEND" to .37f) + ("DECAY" to .81f)))
                val edited = loaded.copy(patch = changed)
                settings.remove("preset.ONSET.${voice.name}")
                for ((name, value) in changed.macros) settings["macro.ONSET.${voice.name}.$name"] = value.toString()
                SynthDrafts.write(settings, listOf(changed))
                SynthWork.replace(settings, listOf(SynthWork.Working(edited, source)))
                navigation.go(Place.PROJECT)
                navigation.go(Place.MAKE)
                navigation.open(Bench.SYNTH)
                val returning = navigation.make.settings(Bench.SYNTH).toMap()
                val draft = assertIs<OnsetPatch>(SynthDrafts.read(returning).single())
                val work = SynthWork.read(returning).single()
                assertEquals(changed, draft)
                assertEquals(4, draft.modelVersion)
                assertEquals(edited, work.recipe, "navigation retains FX, treatment, amount, alias and delivery fade")
                assertEquals(17, work.source!!.slot)
                assertTrue(work.source!!.matches(shelf, dir))
                assertFalse(work.source!!.matches(shelf, otherKit), "UPDATE cannot follow a different open kit")
                assertFalse(returning.values.any { shelf.absolutePath in it })

                val plan = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(work.recipe.toJsonValue()))
                assertEquals(edited, plan.recipe)
                RecipeReplay.apply(reopened, 17, work.recipe.toJsonValue(), before.displayName)
                assertEquals(before.copy(recipe = edited.toJsonValue(), source = before.source - "rawSha256"),
                    reopened.pad(17), "UPDATE retains playback controls and provenance, dropping only the stale WAV hash")
                reopened.save()
                val updated = KitBuilderModel.open(dir)
                val updatedPad = assertNotNull(updated.pad(17))
                assertEquals(edited, Breed.recipeOf(updatedPad))
                val editedWav = wav(edited)
                assertContentEquals(editedWav, File(dir, updatedPad.sampleFile).readBytes())
                assertFalse(originalWav.contentEquals(editedWav), "the edited sound must differ for rollback to prove audio fidelity")

                updated.restoreTake(beforeUpdateTake)
                updated.save()
                val restoredKit = KitStore.load(dir)
                assertEquals(null, restoredKit.tempoBpm, "the saved version also restores the earlier kit metadata")
                val restoredPad = assertNotNull(restoredKit.pad(17))
                assertEquals(before, restoredPad)
                assertEquals(original, Breed.recipeOf(restoredPad))
                assertContentEquals(originalWav, File(dir, restoredPad.sampleFile).readBytes(),
                    "version rollback restores the native sound's audio as well as its saved controls")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
