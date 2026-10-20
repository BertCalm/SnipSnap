package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphicaPatchTest {
    @Test
    fun `old config JSON stays unchanged and captured sustain is explicitly versioned`() {
        val legacy = OrphicaPatch("Legacy", OrphicaConfig())
        val fields = legacy.toJsonValue().entries.getValue("config").obj()
        assertFalse("hold" in fields)
        assertFalse("sustainModel" in fields)
        assertEquals(legacy, OrphicaPatch.fromJsonText(legacy.toJsonText()))
        assertEquals(0f, OrphicaPatch.fromJsonText(legacy.toJsonText()).config.hold)
        assertEquals(1, OrphicaPatch.fromJsonText(legacy.toJsonText()).config.sustainModel)

        for (patch in listOf(legacy.copy(config = legacy.config.copy(hold = .8f)),
            legacy.copy(macros = mapOf("HOLD" to 1f)))) {
            val heldFields = patch.toJsonValue().entries.getValue("config").obj()
            assertTrue("hold" in heldFields && "sustainModel" in heldFields)
            assertEquals(1, heldFields.getValue("sustainModel").int())
            assertEquals(patch, OrphicaPatch.fromJsonText(patch.toJsonText()))
            assertContentEquals(patch.render().samples, OrphicaPatch.fromJsonText(patch.toJsonText()).render().samples)
            val changed = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { root ->
                root["config"] = JsonValue.Obj(LinkedHashMap(heldFields).also { it["sustainModel"] = JsonValue.Num(2.0) })
            })
            assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed) }
        }
    }

    @Test
    fun `panel macros preserve saved physical config with neutral tune`() {
        val config = OrphicaConfig(pluck = .13f, fracture = .81f, grainSizeMs = 93f, damping = .62f, hold = .7f)
        val macros = Orphica.macrosFrom(config)
        assertEquals(0f, Orphica.defaults().getValue("HOLD"))
        assertEquals(.5f, macros.getValue("TUNE"))
        assertEquals(config, Orphica.configWithMacros(config, macros))
        assertFailsWith<IllegalArgumentException> { config.copy(hold = 1.1f) }
        assertFailsWith<IllegalArgumentException> { config.copy(sustainModel = 2) }
    }

    @Test
    fun `complete physical config and all long seed bits round trip with exact audio`() {
        for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 9007199254740993L)) {
            val config = OrphicaConfig(material = OrphicaMaterial.CRYSTAL, mode = OrphicaMode.REVERSE,
                midi = 67, velocity = .63f, pluck = .47f, fracture = .83f, grainSizeMs = 87.5f,
                grainRateHz = 47.25f, scatter = .81f, damping = .37f, seconds = .4f,
                seed = seed, captureSeconds = .2f)
            val patch = OrphicaPatch("Complete note", config, mapOf("TUNE" to .5f, "FRACTURE" to .71f))
            val restored = OrphicaPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertEquals(patch.toJsonText(), restored.toJsonText())
            assertEquals(JsonValue.Str(seed.toString()), restored.toJsonValue().entries.getValue("config").obj()["seed"])
            assertContentEquals(patch.render().samples, restored.render().samples)
        }
    }

    @Test
    fun `omitted macros retain physical settings and tune offsets preserve saved MIDI`() {
        val config = OrphicaConfig(midi = 63, pluck = .17f, fracture = .83f, grainSizeMs = 117f, damping = .72f, seconds = .4f)
        val patch = OrphicaPatch("No macro overrides", config)
        assertEquals(config, Orphica.configWithMacros(config, emptyMap()))
        assertContentEquals(Orphica.render(config).samples, patch.render().samples)
        val up = patch.withMacros(mapOf("TUNE" to 1f))
        assertEquals(config, up.config)
        assertEquals(75, Orphica.configWithMacros(up.config, up.macros).midi)
        val down = patch.withMacros(mapOf("TUNE" to 0f))
        assertEquals(51, Orphica.configWithMacros(down.config, down.macros).midi)
        assertEquals(config, Orphica.configWithMacros(config, mapOf("TUNE" to .5f)))
        assertEquals(Orphica.MIDI_MIN, Orphica.configWithMacros(config.copy(midi = Orphica.MIDI_MIN), mapOf("TUNE" to 0f)).midi)
    }

    @Test
    fun `unsupported models missing physical fields and material disagreement are refused`() {
        val patch = OrphicaPatch("Saved")
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[key] = value },
        )
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed("voice", JsonValue.Str("STEEL"))) }
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it.remove("config") },
        )) }
        val config = LinkedHashMap(patch.toJsonValue().entries.getValue("config").obj())
        config["seed"] = JsonValue.Str("9223372036854775808")
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed("config", JsonValue.Obj(config))) }
        config["seed"] = JsonValue.Num(1.0)
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed("config", JsonValue.Obj(config))) }
        config.remove("grainRateHz")
        assertFailsWith<JsonException> { OrphicaPatch.fromJsonValue(changed("config", JsonValue.Obj(config))) }
    }

    @Test
    fun `invalid macros nonfinite physical settings and unsafe sizes fail at construction`() {
        assertFailsWith<IllegalArgumentException> { OrphicaPatch(" ") }
        assertFailsWith<IllegalArgumentException> { OrphicaPatch("Bad", macros = mapOf("SHIMMER" to .4f)) }
        assertFailsWith<IllegalArgumentException> { OrphicaPatch("Bad", macros = mapOf("FRACTURE" to Float.NaN)) }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { OrphicaConfig(fracture = value) }
            assertFailsWith<IllegalArgumentException> { OrphicaConfig(velocity = value) }
            assertFailsWith<IllegalArgumentException> { OrphicaConfig(damping = value) }
        }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(midi = 109) }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(grainSizeMs = 0f) }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(grainRateHz = 1000f) }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(seconds = 100f) }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(captureSeconds = 0f) }
        assertFailsWith<IllegalArgumentException> { OrphicaConfig(seconds = .2f, captureSeconds = .2f) }
    }
}
