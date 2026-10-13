package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrphicaPatchTest {
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
