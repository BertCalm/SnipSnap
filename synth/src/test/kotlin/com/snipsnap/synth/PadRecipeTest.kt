package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadRecipeTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("padrecipe").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun onePatchPerEngine(): List<Patch> = listOf(
        ThumpPatch("Kick Test", ThumpVoice.KICK, mapOf("TUNE" to 0.3f)),
        SkinPatch("Room Kick Test", SkinVoice.KICK, mapOf("TUNE" to 0.3f)),
        TinesPatch("Bell Test", TinesVoice.BELL, mapOf("RATIO" to 0.9f)),
        PluckPatch("Nylon Test", PluckVoice.NYLON, mapOf("DAMP" to 0.2f)),
        TonewheelPatch("Stab Test", TonewheelVoice.STAB, mapOf("BAR8" to 1f)),
        VelvetPatch("Acid Test", VelvetVoice.SQUELCH, mapOf("SQUEEZE" to 0.9f)),
        SnapPatch("Photo Test", SnapVoice.ORBIT, mapOf("GRIT" to 0.4f), IntArray(Snap.TABLE_SIZE) { (it * 255) / (Snap.TABLE_SIZE - 1) }),
        GlintPatch("Glass Test", GlintVoice.BOTTLE, mapOf("PEAK" to 0.7f)),
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
            "tide" to SynthKits.tide(),
        )) {
            kit.forEachIndexed { i, pad ->
                if (pad == null) return@forEachIndexed
                assertTrue(pad.recipe != null, "$name pad ${i + 1} has no recipe")
                // Every stored recipe must parse through the typed layer.
                PadRecipe.fromJsonValue(pad.recipe!!)
            }
        }
    }

    @Test
    fun `a treatment name survives a round trip`() {
        // "crushed" is one of Shuffle.TREATMENTS's five bank-B characters.
        // TAPE/CRUSH/DIRT are a separate vocabulary - pad-sheet segments
        // over Eras - and Treatments.chain only knows the bank-B names.
        val r = PadRecipe(fx = Treatments.chain("crushed"), treatment = "crushed")
        val back = PadRecipe.fromJsonText(r.toJsonText())
        assertEquals("crushed", back.treatment)
        assertEquals(r.fx, back.fx)
    }

    @Test
    fun `a recipe with no treatment tag still parses`() {
        // "2" here is the live VERSION, not a stand-in for an old file - see
        // "a v1 recipe is rejected, not migrated" below for that case. This
        // is testing that an *optional* field being absent is not an error.
        val untagged = """{"recipe":2,"fx":{"fx":1,"reverse":true}}"""
        assertNull(PadRecipe.fromJsonText(untagged).treatment, "an untagged recipe is not an error")
    }

    @Test
    fun `every remixed bank-B pad names its treatment`() {
        val bankA = Shuffle.kit(seed = 7).take(16)
        val both = Shuffle.withRemixBank(bankA, seed = 7)
        val bankB = both.drop(16).filterNotNull()
        assertEquals(bankA.filterNotNull().size, bankB.size, "every assigned pad gets a twin")
        for (pad in bankB) {
            val recipe = PadRecipe.fromJsonValue(pad.recipe!!)
            assertEquals(
                true,
                recipe.treatment in Shuffle.TREATMENTS.map { it.first },
                "a twin with no treatment tag can't be labelled: ${recipe.treatment}",
            )
        }
    }

    @Test
    fun `the treatment amount round-trips with its name`() {
        val r = PadRecipe(fx = Treatments.chain("crushed", 0.35f), treatment = "crushed", amount = 0.35f)
        val back = PadRecipe.fromJsonText(r.toJsonText())
        assertEquals("crushed", back.treatment)
        assertEquals(0.35f, back.amount)
    }

    @Test
    fun `a recipe with no amount still parses`() {
        val untagged = """{"recipe":2,"fx":{"fx":1,"reverse":true}}"""
        assertNull(PadRecipe.fromJsonText(untagged).amount, "an amount-less recipe is not an error")
    }

    @Test
    fun `a v1 recipe is rejected, not migrated`() {
        // U3+U5+U6 (docs/SYNTH_UPGRADE.md): Punch, Dsp.Env, filter
        // saturation and 4x oversampling all change what render() produces
        // for an identical patch, so a v1 recipe's stored audio can no
        // longer be trusted to match a fresh render of itself. The doc's
        // own recommendation is a hard break, not a silent reinterpretation
        // - this is that break, proven.
        val v1 = """{"recipe":1,"fx":{"fx":1,"reverse":true}}"""
        val thrown = assertFailsWith<JsonException> { PadRecipe.fromJsonText(v1) }
        assertTrue(
            "unsupported recipe version 1" in (thrown.message ?: ""),
            "should name the actual guard that fired, not just any rejection: ${thrown.message}",
        )
    }

    @Test
    fun `alias defaults to true for VELVET CHIP and anything feeding CRUNCH, false otherwise`() {
        // docs/SYNTH_UPGRADE.md's U6 alias-flag section: grit stays
        // available where it is the point.
        val chip = PadRecipe(patch = VelvetPatch("Chip", VelvetVoice.CHIP, emptyMap()))
        assertTrue(chip.alias, "CHIP should default to aliased")

        val crunched = PadRecipe(
            patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()),
            fx = FxChain(crunch = mapOf("BITS" to 0.5f)),
        )
        assertTrue(crunched.alias, "a chain feeding CRUNCH should default to aliased")

        val clean = PadRecipe(patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()))
        assertTrue(!clean.alias, "a plain patch with no crunch should default to clean")

        val otherVoiceCrunchFree = PadRecipe(patch = VelvetPatch("Bass", VelvetVoice.BASS, emptyMap()))
        assertTrue(!otherVoiceCrunchFree.alias, "a non-CHIP VELVET voice should default to clean")
    }

    @Test
    fun `alias can be set explicitly against its computed default`() {
        val forcedClean = PadRecipe(patch = VelvetPatch("Chip", VelvetVoice.CHIP, emptyMap()), alias = false)
        assertTrue(!forcedClean.alias, "an explicit alias must override the CHIP default")

        val forcedAliased = PadRecipe(patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()), alias = true)
        assertTrue(forcedAliased.alias, "an explicit alias must override the clean default")
    }

    @Test
    fun `fromJsonValue infers alias when the key is absent from an otherwise-valid v2 recipe`() {
        // toJsonValue always writes "alias" explicitly, so the round-trip
        // tests above never touch the obj["alias"]?.bool() ?: impliesAlias(..)
        // fallback in fromJsonValue - strip the key back out to exercise it
        // directly, the same way a hand-built or older-tooling v2 document
        // that simply omitted the field would arrive.
        val chip = PadRecipe(patch = VelvetPatch("Chip", VelvetVoice.CHIP, emptyMap()))
        val stripped = JsonValue.Obj(chip.toJsonValue().entries - "alias")
        assertTrue(
            PadRecipe.fromJsonValue(stripped).alias,
            "an omitted alias key on a CHIP recipe should still infer true",
        )

        val crunched = PadRecipe(
            patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()),
            fx = FxChain(crunch = mapOf("BITS" to 0.5f)),
        )
        val strippedCrunched = JsonValue.Obj(crunched.toJsonValue().entries - "alias")
        assertTrue(
            PadRecipe.fromJsonValue(strippedCrunched).alias,
            "an omitted alias key on a CRUNCH-feeding recipe should still infer true",
        )
    }

    @Test
    fun `alias round-trips through JSON`() {
        for (alias in listOf(true, false)) {
            val r = PadRecipe(patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()), alias = alias)
            assertEquals(alias, PadRecipe.fromJsonText(r.toJsonText()).alias)
        }
    }

    @Test
    fun `an explicit alias overriding its computed default survives the JSON round trip`() {
        // The test above only overrides a plain patch's false default *up*
        // to true - it never proves the opposite direction (a true default
        // pinned *down*) survives toJsonValue/fromJsonValue, which a bug
        // that silently re-derived alias from patch/fx on write or read,
        // instead of respecting the stored/serialized value, would still
        // pass without.
        val forcedClean = PadRecipe(patch = VelvetPatch("Chip", VelvetVoice.CHIP, emptyMap()), alias = false)
        assertEquals(false, PadRecipe.fromJsonText(forcedClean.toJsonText()).alias, "CHIP forced clean should stay clean")

        val forcedAliased = PadRecipe(patch = ThumpPatch("Kick", ThumpVoice.KICK, emptyMap()), alias = true)
        assertEquals(true, PadRecipe.fromJsonText(forcedAliased.toJsonText()).alias, "a plain patch forced aliased should stay aliased")
    }

    @Test
    fun `Treatments-apply records both the name and the amount it used`() {
        val snip = Thump.render(ThumpVoice.SNARE)
        val treated = Treatments.apply("crushed", snip, 0.4f)
        val recipe = PadRecipe.fromJsonValue(treated.recipe)
        assertEquals("crushed", recipe.treatment)
        assertEquals(0.4f, recipe.amount)
    }
}
