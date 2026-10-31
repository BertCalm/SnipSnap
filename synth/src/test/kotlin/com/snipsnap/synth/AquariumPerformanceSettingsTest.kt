package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AquariumPerformanceSettingsTest {
    private fun edited(vararg values: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(AquariumPerformanceSettings().toJsonValue().entries + values)

    @Test fun `live settings preserve resolved controls unsigned seeds and explicit policies`() {
        for (seed in listOf(0L, 0x8000_0000L, AquariumRecipe.MAX_SEED)) {
            val recipe = AquariumPerformanceSettings(
                AquariumRecipe(AquariumControls.preset("full").settings.parameters, seed),
                enabled = true, outputGain = .125f,
            )
            val text = recipe.toJsonText()
            val reopened = AquariumPerformanceSettings.fromJsonText(text)
            assertEquals(recipe, reopened)
            assertEquals(text, reopened.toJsonText())
            assertEquals(52, reopened.settings.parameters.size)
            assertEquals(seed, reopened.settings.seed)
            assertEquals(listOf("aquariumPerformance", "settings", "enabled", "outputGain", "transitionPolicy", "outputPolicy"), recipe.toJsonValue().entries.keys.toList())
            assertEquals("fade-wet-v1", recipe.toJsonValue().entries.getValue("transitionPolicy").str())
            assertEquals("linked-sample-peak-v1", recipe.toJsonValue().entries.getValue("outputPolicy").str())
            assertEquals(recipe.settings, recipe.copy(enabled = false, outputGain = 0f).settings)
        }
        val defaults = AquariumPerformanceSettings()
        assertFalse(defaults.enabled)
        assertTrue(defaults.settings.isBypass)
        assertEquals(.5f, defaults.outputGain)
    }

    @Test fun `live settings reject unknown versions policies missing fields and offline context`() {
        val fields = AquariumPerformanceSettings().toJsonValue().entries
        for (name in fields.keys) assertFailsWith<JsonException>("missing $name") {
            AquariumPerformanceSettings.fromJsonValue(JsonValue.Obj(fields - name))
        }
        for ((name, value) in listOf(
            "aquariumPerformance" to JsonValue.Num(2.0),
            "aquariumPerformance" to JsonValue.Num(1.5),
            "transitionPolicy" to JsonValue.Str("instant"),
            "outputPolicy" to JsonValue.Str("normalize"),
            "enabled" to JsonValue.Num(1.0),
            "settings" to JsonValue.Null,
            "bpm" to JsonValue.Num(120.0),
            "tailSeconds" to JsonValue.Num(2.0),
            "peakCeiling" to JsonValue.Num(.5),
        )) assertFailsWith<JsonException>(name) { AquariumPerformanceSettings.fromJsonValue(edited(name to value)) }
    }

    @Test fun `gain domain permits intentional mute and rejects nonfinite overflow and nonzero underflow`() {
        for (gain in listOf(0f, Float.MIN_VALUE, .5f, 1f)) {
            val value = AquariumPerformanceSettings(outputGain = gain)
            assertEquals(value, AquariumPerformanceSettings.fromJsonText(value.toJsonText()))
        }
        for (gain in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.001f, 1.001f)) {
            val error = assertFailsWith<IllegalArgumentException> { AquariumPerformanceSettings(outputGain = gain) }
            assertTrue(error.message!!.startsWith("Aquarium performance gain must be finite and in 0..1:"))
        }
        for (gain in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -Double.MIN_VALUE, Math.nextUp(1.0))) {
            val error = assertFailsWith<JsonException> {
                AquariumPerformanceSettings.fromJsonValue(edited("outputGain" to JsonValue.Num(gain)))
            }
            assertEquals("Aquarium performance gain must be finite and in 0..1", error.message)
        }
        val underflow = assertFailsWith<JsonException> {
            AquariumPerformanceSettings.fromJsonValue(edited("outputGain" to JsonValue.Num(1e-100)))
        }
        assertEquals("Aquarium performance gain is too small for native Float precision", underflow.message)
    }
}
