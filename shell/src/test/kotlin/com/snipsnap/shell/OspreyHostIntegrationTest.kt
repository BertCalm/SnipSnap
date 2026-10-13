package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.OspreyVoice
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OspreyHostIntegrationTest {
    @Test
    fun `starter pads retain dry finite sea recipes and replay the auditioned bytes`() {
        val directory = createTempDirectory("osprey-starter").toFile()
        try {
            val kit = StarterKits.byId("osprey")!!.render("Osprey proof", directory)
            assertFalse(Preflight.check(kit, directory).blocked())
            val model = KitBuilderModel.open(directory)
            assertEquals(16, model.kit.pads.size)
            assertTrue(model.kit.pads.all { it.drumClass == DrumClass.TONAL && it.oneShot })
            for (pad in model.kit.pads) {
                val recipe = Breed.recipeOf(pad.recipe)!!
                val patch = assertIs<OspreyPatch>(recipe.patch)
                assertNull(recipe.fx)
                assertEquals(60, patch.midi)
                assertEquals(731, patch.seed)
                assertEquals(8f, patch.durationSeconds)
                assertEquals(0f, patch.macros.getValue("SPACE"))
            }
            val source = model.kit.pads.first()
            val target = model.kit.pads.last()
            val original = File(directory, source.sampleFile).readBytes()
            val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
            assertEquals(source.recipe, replayed.pad.recipe)
            assertContentEquals(original, File(directory, replayed.pad.sampleFile).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `shelf and factory promotion retain the variation duration and render model`() {
        val directory = createTempDirectory("osprey-presets").toFile()
        try {
            val patch = OspreyPatch("MY SHORE", OspreyVoice.SWELL, linkedMapOf("SHORE" to .4f, "SEA" to .8f),
                midi = 57, velocity = .6f, seed = -39, durationSeconds = 5.5f)
            UserPresets.save(directory, patch, 713L)
            val saved = UserPresets.read(directory).single()
            assertEquals(patch, saved.patch)
            assertEquals(713L, saved.savedAt)
            assertEquals("p(OspreyVoice.SWELL, \"MY SHORE\", \"SHORE\" to 0.4f, \"SEA\" to 0.8f)" +
                ".copy(midi = 57, velocity = 0.6f, seed = -39, durationSeconds = 5.5f, modelVersion = 1),",
                UserPresets.rosterLine(saved.patch))
        } finally {
            directory.deleteRecursively()
        }
    }
}
