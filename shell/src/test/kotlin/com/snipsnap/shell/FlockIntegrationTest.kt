package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlockIntegrationTest {
    @Test
    fun `factory starter keeps sixteen dry finite performances and replays every voice exactly`() {
        val directory = createTempDirectory("flock-starter").toFile()
        try {
            val starter = requireNotNull(StarterKits.byId("flock"))
            assertEquals("FLOCK", starter.displayName)
            assertFalse(starter.seeded, "saved performances make a fixed starter")
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("Flock proof", directory)
            assertFalse(Preflight.check(kit, directory).blocked())
            val model = KitBuilderModel.open(directory)
            assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
            assertEquals((1..16).toList(), model.kit.pads.map { it.slot })
            val patches = model.kit.pads.map { pad ->
                val recipe = requireNotNull(Breed.recipeOf(pad.recipe))
                val patch = assertIs<FlockPatch>(recipe.patch)
                assertNull(recipe.fx, "the flock's movement is dry")
                assertEquals(DrumClass.TONAL, pad.drumClass, "long finite gestures remain pitched sounds")
                assertTrue(pad.oneShot, "HOLD extends a finite performance")
                assertEquals(0, pad.muteGroup, "independent flocks do not choke one another")
                assertEquals(patch.name, pad.displayName)
                assertEquals(Flock.MODEL_VERSION, patch.model)
                assertEquals(1f, patch.velocity)
                assertEquals(patch, Patches.fromJsonValue(patch.toJsonValue()))
                patch
            }
            val gathering = FlockPresets.forVoice(FlockVoice.GATHER).first()
            assertEquals(listOf(60, 63, 65, 67, 70, 72, 75, 77), patches.take(8).map { it.midi })
            assertTrue(patches.take(8).all {
                it.voice == FlockVoice.GATHER && it.seed == gathering.seed && it.macros == gathering.macros
            }, "the scale changes pitch while retaining the same initial performance")
            assertEquals(FlockPresets.all().drop(1), patches.drop(8), "the remaining factory recipes retain their order and complete state")
            assertEquals(FlockVoice.entries.toSet(), patches.map { it.voice }.toSet())

            val target = model.kit.pads.last()
            for (voice in FlockVoice.entries) {
                val index = patches.indexOfFirst { it.voice == voice }
                val source = model.kit.pads[index]
                val original = File(directory, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, requireNotNull(source.recipe), target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "$voice keeps note, energy, model and seed")
                assertContentEquals(original, File(directory, replayed.pad.sampleFile).readBytes(), "$voice regenerates its original WAV")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `factory names are protected and saved edits retain exact seeds and either render model`() {
        val directory = createTempDirectory("flock-presets").toFile()
        try {
            for (voice in FlockVoice.entries) {
                val presets = FlockPresets.forVoice(voice)
                assertEquals(presets, Presets.forVoice(FlockPatch.ENGINE, voice.name))
                for (patch in presets) {
                    assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
                    assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
                }
            }
            val reserved = "CLOSE FEATHERS"
            assertEquals(UserPresets.Check.Factory(reserved), UserPresets.check(
                "  close feathers  ", FlockPatch.ENGINE, FlockVoice.GATHER.name, emptyList()))
            for ((model, seed) in listOf(1 to Long.MIN_VALUE, 2 to Long.MAX_VALUE)) {
                val source = FlockPatch("Saved flock", FlockVoice.RIBBON,
                    Flock.defaults(FlockVoice.RIBBON) + mapOf("FLOW" to .2f, "HOLD" to 0f),
                    midi = 57, velocity = .37f, seed = seed, model = model)
                val macros = source.macros + ("EDGE" to .71f)
                val edited = assertIs<FlockPatch>(Patches.edited(source, "MY FLOCK", macros))
                assertEquals(source.copy(name = "MY FLOCK", macros = macros), edited)
                val recipe = PadRecipe(edited)
                val restoredRecipe = PadRecipe.fromJsonText(recipe.toJsonText())
                assertEquals(recipe, restoredRecipe)
                assertEquals(recipe, Breed.recipeOf(recipe.toJsonValue()))
                val shelf = File(directory, "model-$model")
                UserPresets.save(shelf, edited, nowMillis = 713L)
                val saved = UserPresets.read(shelf).single()
                assertEquals(713L, saved.savedAt)
                assertEquals(edited, saved.patch)
                assertContentEquals(edited.render().samples, restoredRecipe.render().samples)
                val seedLiteral = if (seed == Long.MIN_VALUE) "Long.MIN_VALUE" else "${seed}L"
                val line = UserPresets.rosterLine(saved.patch)
                assertTrue(line.startsWith("p(FlockVoice.RIBBON, \"MY FLOCK\", $seedLiteral, "))
                assertTrue(".copy(midi = 57, velocity = 0.37f, model = $model)" in line, line)
                assertEquals("FlockPresets.kt", UserPresets.rosterFile(edited.engine))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
