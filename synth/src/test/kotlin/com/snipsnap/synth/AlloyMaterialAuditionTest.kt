package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals

class AlloyMaterialAuditionTest {
    @Test
    fun `audition case retains mapping and declares both anchor and resolved body`() {
        for (patch in AlloyMaterialPresets.all()) {
            val controls = patch.macros + ("MATERIAL" to .25f)
            val case = AlloyAuditionGenerator.Case(
                section = "PRESETS", group = "BODY", id = patch.voice.name,
                title = patch.name, detail = "Approved body mapping",
                voice = patch.voice, macros = controls, midi = 48, patch = patch,
            )
            assertEquals(patch.materialResponse, case.recipe.materialResponse)
            assertEquals(patch.config, case.recipe.config)
            assertEquals(patch.copy(macros = controls).effectiveConfig(), case.recipe.effectiveConfig())
            val record = AlloyAuditionGenerator.recipeRecord(case)
            assertEquals(case.recipe, AlloyPatch.fromJsonValue(record.getValue("patch") as JsonValue))
            assertEquals(case.recipe.config, AlloyConfig.fromJsonValue(Json.parse(record.getValue("config") as String)))
            assertEquals(case.recipe.effectiveConfig(), AlloyConfig.fromJsonValue(Json.parse(record.getValue("effectiveConfig") as String)))
        }
    }
}
