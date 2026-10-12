package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.json.Json
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyConfig
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.AlloyVoice
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlloyIntegrationTest {
    @Test
    fun `saved Alloy edits and factory promotion retain the generated config root and energy`() {
        val dir = createTempDirectory("alloy-presets").toFile()
        try {
            val base = Alloy.configFor(AlloyVoice.KEY)
            val source = AlloyPatch("MY ALLOY", AlloyVoice.KEY, mapOf("FUSE" to .7f), midi = 36, velocity = .3f,
                config = base.copy(fuseRadians = 1.25f, held = base.held.copy(roughness = .8f)))
            UserPresets.save(dir, source, 1L)
            val saved = UserPresets.read(dir).single()
            assertEquals(source, saved.patch)
            val line = UserPresets.rosterLine(source)
            assertTrue(".copy(midi = 36, velocity = 0.3f, model = 1, config = AlloyConfig.fromJsonValue(" in line, line)
            assertTrue('\n' !in line && '\r' !in line, "a pasteable Kotlin roster line must escape JSON's formatting")
            val escaped = line.substringAfter("com.snipsnap.json.Json.parse(").substringBeforeLast(")))").trim()
            val json = Json.parse(escaped).str()
            assertEquals(source.config, AlloyConfig.fromJsonValue(Json.parse(json)), "pasteable promotion drops custom synthesis data")
            assertEquals("AlloyPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Alloy starter recipes replay bit exactly through the editing door`() {
        val dir = createTempDirectory("alloy-starter").toFile()
        try {
            val kitDir = File(dir, "kit")
            val starter = StarterKits.byId("alloy")!!
            starter.render("Alloy Starter", kitDir)
            val model = KitBuilderModel.open(kitDir)
            assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
            assertEquals(16, model.kit.pads.size)
            val patches = model.kit.pads.mapIndexed { i, pad ->
                val recipe = Breed.recipeOf(pad.recipe)!!
                val patch = assertIs<AlloyPatch>(recipe.patch)
                assertNull(recipe.fx)
                assertEquals(if (i >= 14) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
                patch
            }
            assertEquals(AlloyVoice.entries.toSet(), patches.map { it.voice }.toSet())
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
            val source = model.kit.pads.first()
            val original = File(kitDir, source.sampleFile).readBytes()
            val replayed = RecipeReplay.apply(model, model.kit.pads.last().slot, source.recipe!!, "Alloy Replay")
            assertEquals(source.recipe, replayed.pad.recipe)
            assertTrue(original.contentEquals(File(kitDir, replayed.pad.sampleFile).readBytes()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
