package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ApsisPatchTest {
    private val patch = ApsisPatch("Authored passage", ApsisVoice.PASSAGE,
        Apsis.defaults(ApsisVoice.PASSAGE), midi = 57, velocity = .4f,
        config = ApsisConfig(seedContext = Int.MIN_VALUE))

    @Test
    fun `the shared patch and pad recipe doors retain exact model seed pitch velocity and macros`() {
        val text = patch.toJsonText()
        assertEquals(patch, ApsisPatch.fromJsonText(text))
        assertEquals(patch, Patches.fromJsonText(text))
        assertEquals(text, Patches.fromJsonText(text).toJsonText())
        val recipe = PadRecipe(patch)
        assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
        assertEquals(setOf("STRETCH", "DISTANCE", "BODIES", "ALIGN", "COUPLE", "HOLD"), patch.macros.keys)
        assertEquals(Int.MIN_VALUE, ApsisPatch.fromJsonText(text).config.seedContext)
    }

    @Test
    fun `new configurations select model three while both earlier models still replay`() {
        assertEquals(3, ApsisConfig().modelVersion)
        assertEquals(3, patch.config.modelVersion)
        for (model in listOf(1, 2, 3)) {
            val source = patch.copy(config = ApsisConfig(modelVersion = model, seedContext = Int.MIN_VALUE))
            assertEquals(source, Patches.fromJsonText(source.toJsonText()))
        }
    }

    @Test
    fun `macro and name edits retain the body configuration and played note`() {
        val edited = Patches.edited(patch, "My passage", mapOf("STRETCH" to .8f)) as ApsisPatch
        assertEquals(patch.copy(name = "My passage", macros = mapOf("STRETCH" to .8f)), edited)
        assertEquals(edited, ApsisPatch.fromJsonText(edited.toJsonText()))
        assertContentEquals(
            Apsis.render(edited.voice, edited.macros, edited.midi, edited.velocity, edited.config).samples,
            PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).render().samples,
        )
    }

    @Test
    fun `missing optional saved fields retain the first model and performance defaults`() {
        for (optional in listOf("", """, "midi":null, "velocity":null, "config":null""",
            """, "config":{}""", """, "config":{"modelVersion":null,"seedContext":null}""")) {
            val decoded = ApsisPatch.fromJsonText("""{"engine":"APSIS","version":1,"name":"Passage","voice":"PASSAGE","macros":{"ALIGN":0.2},"future":true$optional}""")
            assertEquals(Apsis.DEFAULT_MIDI, decoded.midi)
            assertEquals(1f, decoded.velocity)
            assertEquals(ApsisConfig(modelVersion = 1), decoded.config)
            assertEquals(mapOf("ALIGN" to .2f), decoded.macros)
        }
    }

    @Test
    fun `a first audition recipe retains its renderer through editing and native velocity`() {
        val legacy = ApsisPatch.fromJsonText("""{"engine":"APSIS","version":1,"name":"Old passage","voice":"PASSAGE","midi":57,"velocity":0.4,"macros":{"STRETCH":0.65,"DISTANCE":0.4,"BODIES":0.4,"ALIGN":0.55,"COUPLE":0.45,"HOLD":0},"config":{"modelVersion":1,"seedContext":-314}}""")
        val edited = Patches.edited(legacy, "Old edited", legacy.macros + ("COUPLE" to .8f)) as ApsisPatch
        assertEquals(ApsisConfig(modelVersion = 1, seedContext = -314), edited.config)
        assertEquals(57, edited.midi)
        assertEquals(.4f, edited.velocity)
        assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        val expected = Apsis.render(edited.voice, edited.macros, edited.midi, .25f, edited.config)
        assertContentEquals(expected.samples, Velocity.atVelocity(edited, .25f).samples)
    }

    @Test
    fun `invalid recipes are refused before rendering`() {
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) })
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("engine", JsonValue.Str("GYRE"))) }
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("voice", JsonValue.Str("COMET"))) }
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("config", JsonValue.Arr(emptyList()))) }
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("config", JsonValue.Obj(mapOf("modelVersion" to JsonValue.Num(99.0))))) }
        assertFailsWith<JsonException> { ApsisPatch.fromJsonValue(changed("config", JsonValue.Obj(mapOf("seedContext" to JsonValue.Num(.5))))) }
        for (midi in listOf(35, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = velocity) }
        for (value in listOf(-.1f, 1.1f, Float.NaN))
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("COUPLE" to value)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TUNE" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
    }

    @Test
    fun `production velocity rendering preserves orbital configuration`() {
        val native = patch.copy(velocity = .25f).render()
        assertContentEquals(native.samples, Velocity.atVelocity(patch, .25f).samples)
        assertFalse(native.samples.contentEquals(patch.render().samples))
    }

    @Test
    fun `the six first listen recipes are unique complete and regenerable`() {
        val presets = ApsisPresets.forVoice(ApsisVoice.PASSAGE)
        assertEquals(6, presets.size)
        assertEquals(6, presets.map { it.name }.toSet().size)
        assertEquals(presets, Presets.forVoice("APSIS", "PASSAGE"))
        for (preset in presets) {
            assertEquals(patch.macros.keys, preset.macros.keys)
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
            assertEquals(3, preset.config.modelVersion)
            assertEquals(Apsis.DEFAULT_MIDI, preset.midi)
            assertEquals(1f, preset.velocity)
        }
        assertEquals(1, presets.count { Apsis.isLoop(it.macros.getValue("HOLD")) })
        assertEquals(presets[3].macros - "ALIGN", presets[4].macros - "ALIGN",
            "gathered and staggered recipes isolate ALIGN")
        assertEquals(1f, presets[3].macros.getValue("ALIGN"))
        assertEquals(0f, presets[4].macros.getValue("ALIGN"))
    }
}
