package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AquariumRenderRecipeTest {
    private fun edited(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(AquariumRenderRecipe().toJsonValue().entries + fields)

    @Test
    fun `saved defaults explicitly pin finite fresh fixed gain render policy`() {
        val recipe = AquariumRenderRecipe()
        val saved = recipe.toJsonValue().entries
        assertEquals(listOf("aquariumRender", "settings", "clock", "loopPolicy", "peakPolicy", "bpm", "tailSeconds", "fadeSeconds", "outputGain", "peakCeiling"), saved.keys.toList())
        assertEquals(1, saved.getValue("aquariumRender").int())
        assertEquals(AquariumRecipe().toJsonValue(), saved.getValue("settings"))
        assertEquals("fresh", saved.getValue("clock").str())
        assertEquals("finite", saved.getValue("loopPolicy").str())
        assertEquals("reject", saved.getValue("peakPolicy").str())
        assertEquals(0.0, saved.getValue("bpm").num())
        assertEquals(2.0, saved.getValue("tailSeconds").num())
        assertEquals(0.005, saved.getValue("fadeSeconds").num())
        assertEquals(0.5, saved.getValue("outputGain").num())
        assertEquals(0.99f.toDouble(), saved.getValue("peakCeiling").num())
        assertTrue(recipe.isBypass)
        assertEquals(recipe, AquariumRenderRecipe.fromJsonText(recipe.toJsonText()))
    }

    @Test
    fun `complete settings unsigned seeds tempo and context survive byte stable serialization`() {
        for (seed in listOf(0L, 0x8000_0000L, AquariumRecipe.MAX_SEED)) {
            val settings = AquariumRecipe(mapOf("aqua_drift_mix" to 0.25f, "aqua_tide_syncEnabled" to 1f, "aqua_tide_syncDiv" to 4f), seed)
            val recipe = AquariumRenderRecipe(settings, bpm = 113.123456789, tailSeconds = 1.23456789, fadeSeconds = 0.009876543, outputGain = 0.375f, peakCeiling = 0.875f)
            assertFalse(recipe.isBypass)
            val json = recipe.toJsonText()
            val decoded = AquariumRenderRecipe.fromJsonText(json)
            assertEquals(recipe, decoded)
            assertEquals(settings, decoded.settings)
            assertEquals(seed, decoded.settings.seed)
            assertEquals(recipe.hashCode(), decoded.hashCode())
            assertEquals(json, decoded.toJsonText())
            val reversed = JsonValue.Obj(recipe.toJsonValue().entries.toList().reversed().toMap())
            assertEquals(json, AquariumRenderRecipe.fromJsonValue(reversed).toJsonText())
        }
    }

    @Test
    fun `frame counts use source rate nearest frame ties upward and bounded integer sizes`() {
        val defaults = AquariumRenderRecipe()
        for ((rate, tail, fade) in listOf(Triple(8_000, 16_000, 40), Triple(44_100, 88_200, 221), Triple(48_000, 96_000, 240), Triple(192_000, 384_000, 960))) {
            assertEquals(tail, defaults.tailFrames(rate))
            assertEquals(fade, defaults.fadeFrames(rate))
        }
        val half = 0.5 / 8_000
        assertEquals(1, AquariumRenderRecipe(tailSeconds = half, fadeSeconds = half).tailFrames(8_000))
        assertEquals(1, AquariumRenderRecipe(tailSeconds = half, fadeSeconds = half).fadeFrames(8_000))
        assertEquals(0, AquariumRenderRecipe(tailSeconds = half * 0.999).tailFrames(8_000))
        assertEquals(5_760_000, AquariumRenderRecipe(tailSeconds = 30.0).tailFrames(192_000))
        assertEquals(19_200, AquariumRenderRecipe(fadeSeconds = 0.1).fadeFrames(192_000))
        assertEquals(0, AquariumRenderRecipe(tailSeconds = 0.0, fadeSeconds = 0.0).tailFrames(8_000))
        for (rate in listOf(Int.MIN_VALUE, 0, 7_999, 192_001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { defaults.tailFrames(rate) }
            assertFailsWith<IllegalArgumentException> { defaults.fadeFrames(rate) }
        }
    }

    @Test
    fun `policies version unknown and missing fields refuse without applying defaults`() {
        for ((field, bad) in listOf(
            "clock" to JsonValue.Str("continuous"), "loopPolicy" to JsonValue.Str("sustain"),
            "peakPolicy" to JsonValue.Str("normalize"), "aquariumRender" to JsonValue.Num(2.0),
            "aquariumRender" to JsonValue.Num(1.5), "placement" to JsonValue.Str("pad"),
            "settings" to JsonValue.Null, "bpm" to JsonValue.Str("120"),
        )) assertFailsWith<JsonException>(field) { AquariumRenderRecipe.fromJsonValue(edited(field to bad)) }
        for (field in AquariumRenderRecipe().toJsonValue().entries.keys) {
            assertFailsWith<JsonException>("missing $field") {
                AquariumRenderRecipe.fromJsonValue(JsonValue.Obj(AquariumRenderRecipe().toJsonValue().entries - field))
            }
        }
        val badSettings = JsonValue.Obj(AquariumRecipe().toJsonValue().entries + ("model" to JsonValue.Num(2.0)))
        assertFailsWith<JsonException> { AquariumRenderRecipe.fromJsonValue(edited("settings" to badSettings)) }
    }

    @Test
    fun `JSON numeric ranges reject nonfinite and precise overflow before Float narrowing`() {
        val bounds = mapOf("bpm" to 1000.0, "tailSeconds" to 30.0, "fadeSeconds" to 0.1, "outputGain" to 1.0, "peakCeiling" to 1.0)
        for ((field, maximum) in bounds) {
            for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -Double.MIN_VALUE, Math.nextUp(maximum))) {
                assertFailsWith<JsonException>("$field: $bad") {
                    AquariumRenderRecipe.fromJsonValue(edited(field to JsonValue.Num(bad)))
                }
            }
            val decoded = AquariumRenderRecipe.fromJsonValue(edited(field to JsonValue.Num(maximum)))
            assertEquals(decoded.toJsonText(), AquariumRenderRecipe.fromJsonText(decoded.toJsonText()).toJsonText())
        }
        for (field in listOf("outputGain", "peakCeiling")) {
            for (bad in listOf(0.0, -0.0, Double.MIN_VALUE)) {
                assertFailsWith<JsonException>("$field: $bad") {
                    AquariumRenderRecipe.fromJsonValue(edited(field to JsonValue.Num(bad)))
                }
            }
            val smallest = AquariumRenderRecipe.fromJsonValue(edited(field to JsonValue.Num(Float.MIN_VALUE.toDouble())))
            assertEquals(smallest, AquariumRenderRecipe.fromJsonText(smallest.toJsonText()))
        }
    }

    @Test
    fun `constructors reject invalid render settings and canonicalize zero`() {
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.0001)) {
            assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(bpm = bad) }
            assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(tailSeconds = bad) }
            assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(fadeSeconds = bad) }
        }
        assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(bpm = Math.nextUp(1000.0)) }
        assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(tailSeconds = Math.nextUp(30.0)) }
        assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(fadeSeconds = Math.nextUp(0.1)) }
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0f, -0f, Math.nextUp(1f))) {
            assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(outputGain = bad) }
            assertFailsWith<IllegalArgumentException> { AquariumRenderRecipe(peakCeiling = bad) }
        }
        val positive = AquariumRenderRecipe(bpm = 0.0, tailSeconds = 0.0, fadeSeconds = 0.0)
        val negative = AquariumRenderRecipe(bpm = -0.0, tailSeconds = -0.0, fadeSeconds = -0.0)
        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertEquals(positive.toJsonText(), negative.toJsonText())
    }

}
