package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The accepted opening crosses the same stored-pad and working-recipe doors as SYNTH. */
class PrismOpeningWorkflowTest {
    @Test
    fun `land reopen edit navigate and replay retain each accepted spectral opening`() {
        val shelf = createTempDirectory("prism-opening-work").toFile()
        try {
            for ((index, source) in PrismOpeningPresets.all().withIndex()) {
                val kitDir = File(shelf, "Opening ${index + 1}")
                val model = KitBuilderModel.create("Opening ${index + 1}", kitDir)
                val original = PadRecipe(source, treatment = "KEPT OPENING", amount = .4f, alias = false)
                val cls = Prism.drumClassFor(source.voice, source.macros)
                model.assign(1, original.render(), cls, source.name)
                model.update(1) { it.copy(recipe = original.toJsonValue()) }
                model.save(accrueWear = false)

                val reopened = KitBuilderModel.open(kitDir)
                val pad = assertNotNull(reopened.pad(1))
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val loaded = assertIs<PrismPatch>(recipe.patch)
                assertEquals(original, recipe, "${source.name}: the stored pad must retain the full recipe")
                assertEquals(source.motion, loaded.motion)
                assertTrue(pad.oneShot, "accepted motion is delivered as a finite pad")

                // A fresh composition has only these serialized strings, not the prior patch object.
                val settings = mutableMapOf("engine" to "PRISM", "voice" to source.voiceName)
                val updateTarget = assertNotNull(SynthWork.source(shelf, kitDir, 1))
                SynthWork.replace(settings, listOf(SynthWork.Working(recipe, updateTarget)))
                val work = SynthWork.read(settings.toMap()).single()
                val workingPatch = assertIs<PrismPatch>(work.recipe.patch)
                val editedMacros = workingPatch.macros + mapOf("TUNE" to .25f, "BODY" to .67f)
                val edited = assertIs<PrismPatch>(Patches.edited(workingPatch, "Edited ${index + 1}", editedMacros))
                val changed = work.recipe.copy(patch = edited)
                assertEquals(source.motion, edited.motion, "pitch and timbre edits keep authored A, B and the travel law")
                assertEquals(source.template, edited.template)
                assertEquals(source.velocity, edited.velocity)
                assertEquals(source.model, edited.model)
                assertEquals(Prism.rootMidi(source.voice) + 6, Prism.midiFor(edited.voice, edited.macros.getValue("TUNE")))

                SynthWork.replace(settings, listOf(work.copy(recipe = changed)))
                SynthDrafts.write(settings, listOf(edited))
                val returning = SynthWork.read(settings.toMap().toMutableMap()).single()
                val draft = assertIs<PrismPatch>(SynthDrafts.read(settings.toMap()).single())
                assertEquals(edited, draft, "a draft with no preset highlight retains structural motion")
                assertEquals(changed, returning.recipe, "reopening work preserves treatment metadata as well as the source")
                assertTrue(assertNotNull(returning.source).matches(shelf, kitDir))
                assertTrue(settings.values.none { shelf.absolutePath in it }, "the working recipe keeps a shelf-relative target")

                val patchBeforeVelocity = draft.toJsonText()
                val soft = Velocity.atVelocity(draft, .31f)
                assertContentEquals(edited.copy(velocity = .31f).render().samples, soft.samples,
                    "${source.name}: the shared velocity path keeps the entire opening")
                assertEquals(patchBeforeVelocity, draft.toJsonText(), "velocity rendering never edits saved motion or note energy")

                val expectedWav = wav(changed)
                val plan = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(returning.recipe.toJsonValue()))
                assertEquals(changed, plan.recipe)
                RecipeReplay.apply(reopened, 1, returning.recipe.toJsonValue(), source.name)
                reopened.save(accrueWear = false)
                val finalPad = assertNotNull(KitBuilderModel.open(kitDir).pad(1))
                val finalRecipe = PadRecipe.fromJsonValue(assertNotNull(finalPad.recipe))
                assertEquals(changed, finalRecipe)
                assertContentEquals(expectedWav, File(kitDir, finalPad.sampleFile).readBytes(),
                    "${source.name}: the landed WAV must be the complete edited recipe's own render")
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    private fun wav(recipe: PadRecipe): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, recipe.render())
    }.toByteArray()
}
