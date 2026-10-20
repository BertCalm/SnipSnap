package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The editable pad crosses real kit storage and the existing replay writer. */
class OnsetRecipeReplayTest {
    @Test
    fun `reopen edit and replay keep every voice renderer and complete processing recipe`() {
        val root = kotlin.io.path.createTempDirectory("onset-replay").toFile()
        try {
            val cases = OnsetVoice.entries.map { it to Onset.MODEL_VERSION } +
                listOf(OnsetVoice.KICK to 1, OnsetVoice.KICK to 2, OnsetVoice.HAT_CLOSED to 2)
            for ((voice, modelVersion) in cases) {
                val dir = File(root, "${voice.name}-$modelVersion")
                val model = KitBuilderModel.create("${voice.name}-$modelVersion", dir)
                val patch = OnsetPatch("MY ${voice.name}", voice,
                    Onset.defaults(voice) + ("BLEND" to .75f), modelVersion)
                val recipe = PadRecipe(patch, FxChain(reverse = true), treatment = "reversed", amount = .4f, alias = true)
                model.assign(1, recipe.render(), DrumClass.PERC, patch.name)
                model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
                model.assign(2, DrumSynth.snare(), DrumClass.SNARE, "Destination")
                model.save()

                val reopened = KitBuilderModel.open(dir)
                val loaded = assertNotNull(Breed.recipeOf(assertNotNull(reopened.pad(1))))
                assertEquals(recipe, loaded, "kit storage retains the complete $voice recipe")
                val loadedPatch = assertIs<OnsetPatch>(loaded.patch)
                val changed = Patches.edited(loadedPatch, loadedPatch.name,
                    loadedPatch.macros + ("DECAY" to .65f)) as OnsetPatch
                assertEquals(modelVersion, changed.modelVersion)
                val edited = loaded.copy(patch = changed)
                val plan = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(edited.toJsonValue()))
                assertEquals(edited, plan.recipe)
                RecipeReplay.apply(reopened, 2, edited.toJsonValue(), "Destination")
                reopened.save()

                val finalKit = KitBuilderModel.open(dir)
                val pad = assertNotNull(finalKit.pad(2))
                val finalRecipe = assertNotNull(Breed.recipeOf(pad))
                assertEquals(edited, finalRecipe, "replay retains FX, treatment, amount and alias")
                val rendered = finalRecipe.render()
                val stored = WavReader.read(File(dir, pad.sampleFile))
                assertEquals(rendered.samples.size, stored.samples.size)
                val worst = rendered.samples.indices.maxOf { abs(rendered.samples[it] - stored.samples[it]) }
                assertTrue(worst < 1e-5f, "$voice model $modelVersion recipe no longer regenerates its WAV: $worst")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
