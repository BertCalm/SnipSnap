package com.snipsnap.shell

import com.snipsnap.synth.BackroomSnare
import com.snipsnap.synth.Skin
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.SkinPatch
import java.util.Random
import com.snipsnap.synth.SkinVoice
import java.nio.file.Files
import kotlin.test.*

class BackroomPresetsTest {
    @Test fun `Backroom shelf restore and promotion retain the model controls escaped name and audio`() {
        val root = Files.createTempDirectory("backroom-presets").toFile()
        try {
            val source = SkinPatch("HIT \"\$X\\Y\"", SkinVoice.SNARE,
                BackroomSnare.defaults() + mapOf("BODY" to .8f, "WIRE" to .25f, "BRIGHTNESS" to .7f), model = 2)
            UserPresets.save(root, source, 1L)
            assertEquals(source, UserPresets.read(root).single().patch)
            val binned = assertNotNull(UserPresets.forget(root, source.engine, source.voiceName, source.name, 2L))
            val restored = assertNotNull(UserPresets.unforget(root, binned)).patch
            assertEquals(source, restored)
            val expression = KotlinLiteral.parse(UserPresets.rosterLine(restored).removeSuffix(",")) as KotlinLiteral.Call
            assertEquals("fromJsonText", expression.name)
            assertEquals("SkinPatch", (expression.receiver as KotlinLiteral.Call).name)
            val promoted = SkinPatch.fromJsonText((expression.positional.single() as KotlinLiteral.Str).value)
            assertEquals(source, promoted)
            assertContentEquals(source.render().samples, promoted.render().samples)
        } finally { root.deleteRecursively() }
    }

    @Test fun `crossing original and Backroom snares keeps the first parent model and its playable controls`() {
        val original = SkinPatch("Original", SkinVoice.SNARE, Skin.defaults(SkinVoice.SNARE))
        val backroom = SkinPatch("Backroom", SkinVoice.SNARE, BackroomSnare.defaults(), model = 2)
        for ((a, b) in listOf(original to backroom, backroom to original)) {
            for (seed in 0L..7L) {
                val recipe = Breed.cross(PadRecipe(a), PadRecipe(b), Random(seed))
                val child = assertIs<SkinPatch>(recipe.patch)
                assertEquals(a, child)
                assertEquals(a.model == 2, recipe.alias)
                assertContentEquals(a.render().samples, recipe.render().samples)
            }
        }
        val dark = backroom.copy(macros = BackroomSnare.defaults() + ("BRIGHTNESS" to 0f))
        val open = backroom.copy(macros = BackroomSnare.defaults() + ("BRIGHTNESS" to 1f))
        val crossedValues = (0L..31L).map { seed ->
            val child = assertIs<SkinPatch>(Breed.cross(PadRecipe(dark), PadRecipe(open), Random(seed)).patch)
            assertEquals(2, child.model)
            child.macros.getValue("BRIGHTNESS")
        }.toSet()
        assertEquals(setOf(0f, .5f, 1f), crossedValues)
    }

    @Test fun `original SKIN promotion keeps its original factory helper`() {
        assertEquals("p(SkinVoice.SNARE, \"Old Snare\", \"SNAP\" to 0.7f),",
            UserPresets.rosterLine(SkinPatch("Old Snare", SkinVoice.SNARE, mapOf("SNAP" to .7f))))
    }
}
