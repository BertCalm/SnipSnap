package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPresets
import com.snipsnap.synth.Patches
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
                assertEquals(Osprey.MODEL_VERSION, patch.modelVersion)
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
            for (version in 1..Osprey.MODEL_VERSION) {
                val destination = File(directory, "model-$version")
                val patch = OspreyPatch("MY SHORE", OspreyVoice.SWELL, linkedMapOf("SHORE" to .4f, "SEA" to .8f),
                    midi = 57, velocity = .6f, seed = -39, durationSeconds = .75f, modelVersion = version)
                UserPresets.save(destination, patch, 713L)
                val saved = UserPresets.read(destination).single()
                assertEquals(patch, saved.patch)
                assertEquals(713L, saved.savedAt)
                assertEquals("p(OspreyVoice.SWELL, \"MY SHORE\", \"SHORE\" to 0.4f, \"SEA\" to 0.8f)" +
                    ".copy(midi = 57, velocity = 0.6f, seed = -39, durationSeconds = 0.75f, modelVersion = $version),",
                    UserPresets.rosterLine(saved.patch))
                assertContentEquals(Osprey.render(patch.voice, patch.macros, patch.midi, patch.velocity,
                    patch.seed, patch.durationSeconds, modelVersion = version).samples, saved.patch.render().samples,
                    "the shelf must retain its selected renderer")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `partial legacy factory promotion resolves missing controls in the saved model`() {
        val source = OspreyPatch("OLD SEA", OspreyVoice.SWELL, linkedMapOf("SHORE" to .4f),
            midi = 57, velocity = .6f, seed = -39, durationSeconds = .75f, modelVersion = 1)
        // Exercise the actual private factory helper that the exported Kotlin line calls.
        val helper = OspreyPresets.javaClass.getDeclaredMethod("p", OspreyVoice::class.java,
            String::class.java, emptyArray<Pair<String, Float>>().javaClass)
        helper.isAccessible = true
        val base = helper.invoke(OspreyPresets, source.voice, source.name,
            source.macros.toList().toTypedArray()) as OspreyPatch
        val promoted = base.copy(midi = source.midi, velocity = source.velocity, seed = source.seed,
            durationSeconds = source.durationSeconds, modelVersion = source.modelVersion)
        assertEquals(source, promoted, "promotion must retain absent legacy controls")
        assertEquals(source, Patches.fromJsonText(promoted.toJsonText()))
        assertContentEquals(source.render().samples, promoted.render().samples)
        val legacyDefaults = Osprey.defaults(source.voice, source.modelVersion) + source.macros
        assertContentEquals(source.render().samples, source.copy(macros = legacyDefaults).render().samples,
            "loading a partial legacy recipe with its own defaults must keep its audio")
        assertFalse(source.render().samples.contentEquals(
            source.copy(macros = Osprey.defaults(source.voice, 2) + source.macros).render().samples),
            "model two defaults must not be substituted into a legacy recipe")
        assertTrue("modelVersion = 1" in UserPresets.rosterLine(promoted))
    }
}
