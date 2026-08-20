package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PadRecipeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("padrecipe").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun onePatchPerEngine(): List<Patch> = listOf(
        ThumpPatch("Kick Test", ThumpVoice.KICK, mapOf("TUNE" to 0.3f)),
        TinesPatch("Bell Test", TinesVoice.BELL, mapOf("RATIO" to 0.9f)),
        PluckPatch("Nylon Test", PluckVoice.NYLON, mapOf("DAMP" to 0.2f)),
        TonewheelPatch("Stab Test", TonewheelVoice.STAB, mapOf("BAR8" to 1f)),
        VelvetPatch("Acid Test", VelvetVoice.SQUELCH, mapOf("SQUEEZE" to 0.9f)),
    )

    @Test
    fun `every engine's patch dispatches through the same door`() {
        for (patch in onePatchPerEngine()) {
            val back = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, back, "${patch.engine} did not round-trip")
            assertTrue(
                back.render().samples.contentEquals(patch.render().samples),
                "${patch.engine}: same JSON must mean same sound",
            )
        }
        assertFailsWith<JsonException> {
            Patches.fromJsonText("""{"engine":"THEREMIN","version":1,"name":"?","voice":"AIR","macros":{}}""")
        }
    }

    @Test
    fun `a synth recipe regenerates its audio through the chain`() {
        val recipe = PadRecipe(
            patch = VelvetPatch("Chip Test", VelvetVoice.CHIP, emptyMap()),
            fx = FxChain(crunch = mapOf("BITS" to 0.8f), spring = mapOf("MIX" to 0.4f)),
        )
        val back = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, back)
        assertTrue(back.render().samples.contentEquals(recipe.render().samples))
    }

    @Test
    fun `a capture recipe is fx-only and re-treats what it is given`() {
        val captured = Thump.render(ThumpVoice.SNARE) // stands in for a real capture
        val recipe = PadRecipe(fx = FxChain(echo = mapOf("MIX" to 0.5f)))
        val back = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(null, back.patch)
        assertTrue(back.process(captured).samples.contentEquals(recipe.fx!!.process(captured).samples))
        // And rendering without a patch is a clear error, not silence.
        assertFailsWith<IllegalArgumentException> { back.render() }
    }

    @Test
    fun `an empty recipe is refused and foreign JSON is refused`() {
        assertFailsWith<IllegalArgumentException> { PadRecipe() }
        assertFailsWith<JsonException> {
            PadRecipe.fromJsonText(ThumpPatch("K", ThumpVoice.KICK, emptyMap()).toJsonText())
        }
    }

    @Test
    fun `the whole point - a kit on disk regenerates itself from its sidecar`() {
        // Assemble the chip kit (patches + converter chain per pad), then
        // forget everything except the folder: load kit.json, read each
        // pad's recipe back through the typed layer, render, and get the
        // exact samples that went into the WAVs. Editable forever, proven.
        val dir = File(temp, "chip")
        val arranged = SynthKits.chip()
        KitAssembler.assembleArranged("Chip", arranged, dir)

        val loaded = KitStore.load(dir)
        assertEquals(16, loaded.pads.size)
        for (pad in loaded.pads) {
            val recipeJson = pad.recipe
            assertTrue(recipeJson != null, "pad ${pad.slot} lost its recipe")
            val recipe = PadRecipe.fromJsonValue(recipeJson)
            val regenerated: Snip = recipe.render()
            val original = arranged[pad.slot - 1]!!.snip
            assertTrue(
                regenerated.samples.contentEquals(original.samples),
                "pad ${pad.slot} did not regenerate bit-for-bit",
            )
        }
    }

    @Test
    fun `factory kits all carry recipes on every pad`() {
        for ((name, kit) in listOf(
            "classic" to ThumpKits.classic(),
            "melodic" to SynthKits.melodic(),
            "chip" to SynthKits.chip(),
        )) {
            kit.forEachIndexed { i, pad ->
                if (pad == null) return@forEachIndexed
                assertTrue(pad.recipe != null, "$name pad ${i + 1} has no recipe")
                // Every stored recipe must parse through the typed layer.
                PadRecipe.fromJsonValue(pad.recipe!!)
            }
        }
    }
}
