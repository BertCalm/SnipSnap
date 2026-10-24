package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The release evidence must describe the same selected recipe that makes its audio. */
class AlloyAuditionGeneratorTest {
    @Test
    fun `every release case resolves the selected voice before rendering or export`() {
        val cases = AlloyAuditionGenerator.cases(full = false)
        assertEquals(310, cases.size)
        assertEquals(310, cases.map { it.id }.distinct().size)
        assertEquals(36, cases.count { it.section == "PRESETS" })
        val shortCorner = cases.single { it.id == "pluck_all_low_c2" }
        assertEquals("EXTREMES", shortCorner.section)
        assertEquals(36, shortCorner.baseMidi)
        assertTrue(shortCorner.raw)
        for (macro in listOf("MATERIAL", "FUSE", "EDGE", "BODY", "LENGTH", "HOLD")) {
            assertEquals(0f, shortCorner.macros.getValue(macro))
        }
        assertEquals(mapOf("BASS" to "original", "KEY" to "revised", "PLUCK" to "revised",
            "BRASS" to "revised", "STRING" to "revised", "SOFT" to "original"),
            AlloyAuditionGenerator.selectedVoices())
        for (c in cases) {
            val record = AlloyAuditionGenerator.recipeRecord(c)
            assertEquals(Alloy.defaultConfigFor(c.voice), c.recipe.config, c.id)
            assertEquals(c.baseMidi, c.recipe.midi, c.id)
            assertEquals(c.midi, Alloy.midiFor(c.voice, c.recipe.macros.getValue("TUNE"), c.recipe.midi), c.id)
            assertEquals(c.macros, c.recipe.macros, c.id)
            assertEquals(c.velocity, c.recipe.velocity, c.id)
            assertEquals(c.recipe, AlloyPatch.fromJsonValue(record.getValue("patch") as JsonValue), c.id)
            assertEquals(c.recipe.config,
                AlloyConfig.fromJsonValue(Json.parse(record.getValue("config") as String)), c.id)
        }
    }

    @Test
    fun `an explicit custom patch configuration wins without changing the case note or velocity`() {
        val original = AlloyAuditionGenerator.cases(full = false)
            .first { it.voice == AlloyVoice.KEY && it.section == "VOICES" }
        val custom = AlloyPatch("Saved original key", AlloyVoice.KEY, Alloy.defaults(AlloyVoice.KEY),
            midi = 55, velocity = .4f,
            config = Alloy.configFor(AlloyVoice.KEY).copy(fuseRadians = 1.1f))
        val c = original.copy(patch = custom)
        assertEquals(custom.config, c.recipe.config)
        assertEquals(original.baseMidi, c.recipe.midi)
        assertEquals(original.velocity, c.recipe.velocity)
        val record = AlloyAuditionGenerator.recipeRecord(c)
        assertEquals(c.recipe, AlloyPatch.fromJsonValue(record.getValue("patch") as JsonValue))
        assertTrue(c.recipe.config != Alloy.defaultConfigFor(AlloyVoice.KEY))
    }

    @Test
    fun `diagnostic ablation recipes carry their flag and do not advertise a plain replay patch`() {
        val cases = AlloyAuditionGenerator.cases(full = false)
        val ablated = cases.filter { it.ablation }
        assertTrue(ablated.isNotEmpty())
        for (c in ablated) {
            val record = AlloyAuditionGenerator.recipeRecord(c)
            assertEquals(false, record["attackModulation"], c.id)
            assertEquals("recipes/${c.id}.json", record["renderRecipeFile"], c.id)
            assertNull(record["patchFile"], c.id)
            val enabled = cases.single { it.pair == c.pair && !it.ablation }
            assertEquals(enabled.recipe.copy(name = c.recipe.name), c.recipe, c.id)
        }
        for (c in cases.filterNot { it.ablation }) {
            val record = AlloyAuditionGenerator.recipeRecord(c)
            assertEquals(true, record["attackModulation"], c.id)
            assertEquals("clip-patches/${c.id}.json", record["patchFile"], c.id)
        }
    }

    @Test
    fun `high tune recipes retain their valid base note rather than serializing the effective note`() {
        val upper = AlloyAuditionGenerator.cases(full = false).filter { it.id.endsWith("_upper_pitch") }
        assertEquals(6, upper.size)
        for (c in upper) {
            val record = AlloyAuditionGenerator.recipeRecord(c)
            assertEquals(96, c.recipe.midi)
            assertEquals(108, c.midi)
            assertEquals(96, record["baseMidi"])
            assertEquals(108, record["effectiveMidi"])
            assertEquals(c.recipe, AlloyPatch.fromJsonText(c.recipe.toJsonText()))
        }
    }
}
