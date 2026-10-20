package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChainConfig
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainShape
import com.snipsnap.synth.ScannedChainVoice
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScannedChainRecallTest {
    private fun patch() = ScannedChainPatch("MY SCAN", ScannedChainVoice.BLOOM,
        macros = mapOf("MOTION" to .6f, "DAMP" to .25f, "BEND" to .5f, "BLOOM" to .7f),
        shape = ScannedChainShape.drawn(FloatArray(128) { i ->
            (.2 * sin(2 * PI * i / 128) + .08 * sin(6 * PI * i / 128 + .4)).toFloat()
        }, label = "Saved line"),
        config = ScannedChainConfig.forVoice(ScannedChainVoice.BLOOM).copy(restFraction = .13f,
            seconds = .4f, attackSeconds = .01f, releaseSeconds = .08f, seed = 911),
        midi = 67, velocity = .55f)

    @Test
    fun `a saved preset and its promoted JSON constructor retain all scanned state`() {
        val root = kotlin.io.path.createTempDirectory("scanned-chain-presets").toFile()
        try {
            val original = patch()
            val saved = UserPresets.save(root, original, nowMillis = 1_700_000_000_000L)
            val loaded = UserPresets.read(root).single()
            assertEquals(saved, loaded)
            val recalled = assertIs<ScannedChainPatch>(loaded.patch)
            assertEquals(original, recalled)
            assertContentEquals(original.render().samples, recalled.render().samples)
            assertEquals("ScannedChainPresets.kt", UserPresets.rosterFile("SCANNED_CHAIN"))
            assertEquals("ScannedChainVoice", UserPresets.voiceEnum("scanned_chain"))

            val line = UserPresets.rosterLine(recalled)
            val prefix = "ScannedChainPatch.fromJsonText(\""
            assertTrue(line.startsWith(prefix) && line.endsWith("\"),"))
            val literal = line.removePrefix(prefix).removeSuffix("\"),")
            // This fixture uses the shared JSON/Kotlin string-escape subset.
            // Decode the generated literal rather than looking for field names.
            val embeddedJson = Json.parse("\"$literal\"").str()
            val promoted = ScannedChainPatch.fromJsonText(embeddedJson)
            assertEquals(original, promoted, "promotion retains 128 coordinates, configuration, note, energy and model")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a landed pad recalls and replays its complete scanned recipe to identical WAV bytes`() {
        val root = kotlin.io.path.createTempDirectory("scanned-chain-pad-replay").toFile()
        try {
            val original = patch()
            val recipe = PadRecipe(original)
            val kitDir = File(root, "kit")
            KitBuilderModel.fromChop("Scanned Pad", listOf(ArrangedPad(
                recipe.render(), DrumClass.TONAL, recipe.toJsonValue(), displayName = original.name,
            )), kitDir)
            val model = KitBuilderModel.open(kitDir)
            val landed = assertNotNull(model.pad(1))
            assertEquals(DrumClass.TONAL, landed.drumClass)
            assertTrue(landed.oneShot)
            val landedRecipe = assertNotNull(landed.recipe)
            val recalled = assertIs<ScannedChainPatch>(PadRecipe.fromJsonValue(landedRecipe).patch)
            assertEquals(original, recalled)
            assertContentEquals(original.shape.points, recalled.shape.points)
            assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(landedRecipe))

            model.assign(2, DrumSynth.snare(), DrumClass.SNARE)
            val result = RecipeReplay.apply(model, 2, landedRecipe, "SNARE 02")
            assertEquals(landedRecipe, result.pad.recipe)
            val sourceBytes = File(kitDir, landed.sampleFile).readBytes()
            assertContentEquals(sourceBytes, File(kitDir, result.pad.sampleFile).readBytes(),
                "DO IT AGAIN uses the authored shape and physical settings")
            model.save(accrueWear = false)
            val reopened = KitBuilderModel.open(kitDir)
            val reopenedRecipe = PadRecipe.fromJsonValue(assertNotNull(reopened.pad(2)?.recipe))
            assertEquals(original, reopenedRecipe.patch)
        } finally {
            root.deleteRecursively()
        }
    }
}
