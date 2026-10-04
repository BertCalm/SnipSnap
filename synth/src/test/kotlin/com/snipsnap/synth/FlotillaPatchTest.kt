package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JSON contract for a FLOTILLA patch: the note and the model ride beside the macros. */
class FlotillaPatchTest {

    private val patch = FlotillaPatch(
        "Wake",
        FlotillaVoice.RIPPLE,
        Flotilla.defaults(FlotillaVoice.RIPPLE),
        midi = 72,
        velocity = 0.4f,
    )

    @Test
    fun `a patch round-trips with its note, velocity and model`() {
        val text = patch.toJsonText()
        val back = FlotillaPatch.fromJsonText(text)
        assertEquals(patch, back)
        assertEquals(patch, Patches.fromJsonText(text))
        assertEquals(text, back.toJsonText())
        val keys = patch.toJsonValue().entries.keys
        assertTrue("model" in keys && "midi" in keys && "velocity" in keys)
        assertEquals(1.0, (patch.toJsonValue().entries.getValue("model") as JsonValue.Num).value)
    }

    @Test
    fun `missing midi, velocity and model read as the defaults, and an unknown field is ignored`() {
        val bare = """{"engine":"FLOTILLA","version":1,"name":"Wake","voice":"RIPPLE","macros":{"PULSE":0.2},"extra":"kept out","midi":null,"velocity":null,"model":null}"""
        val back = FlotillaPatch.fromJsonText(bare)
        assertEquals(Flotilla.DEFAULT_MIDI, back.midi)
        assertEquals(1f, back.velocity)
        assertEquals(Flotilla.MODEL_VERSION, back.model)
        assertEquals(0.2f, back.macros.getValue("PULSE"))
        assertEquals(back, Patches.fromJsonText(bare))
    }

    @Test
    fun `a missing macro is allowed and filled from the voice at render`() {
        val partial = FlotillaPatch("Wake", FlotillaVoice.KNOCK, mapOf("PULSE" to 0.9f))
        val settled = Flotilla.settledMacros(partial.voice, partial.macros)
        assertEquals(0.9f, settled.getValue("PULSE"))
        assertEquals(Flotilla.defaults(FlotillaVoice.KNOCK).getValue("SKIN"), settled.getValue("SKIN"))
    }

    @Test
    fun `a bad model, version, voice, note, velocity, macro or name is refused`() {
        val base = LinkedHashMap(patch.toJsonValue().entries)
        fun edited(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue {
            val obj = LinkedHashMap(base)
            edit(obj)
            return JsonValue.Obj(obj)
        }
        val model = assertFailsWith<JsonException> { FlotillaPatch.fromJsonValue(edited { it["model"] = JsonValue.Num(2.0) }) }
        assertTrue("unsupported flotilla model" in (model.message ?: ""))
        val version = assertFailsWith<JsonException> { FlotillaPatch.fromJsonValue(edited { it["version"] = JsonValue.Num(2.0) }) }
        assertTrue("unsupported patch version 2" in (version.message ?: ""))
        assertFailsWith<JsonException> { FlotillaPatch.fromJsonValue(edited { it["voice"] = JsonValue.Str("KICK") }) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, Flotilla.defaults(FlotillaVoice.RIPPLE), midi = 35) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, Flotilla.defaults(FlotillaVoice.RIPPLE), midi = 85) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, Flotilla.defaults(FlotillaVoice.RIPPLE), velocity = 1.1f) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, Flotilla.defaults(FlotillaVoice.RIPPLE), velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch(" ", FlotillaVoice.RIPPLE, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, mapOf("SPARKLE" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, mapOf("PULSE" to 1.2f)) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, mapOf("PULSE" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { FlotillaPatch("Wake", FlotillaVoice.RIPPLE, emptyMap(), model = 0) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(Json.parse("""{"engine":"FLOTILLA","version":9,"name":"x","voice":"RIPPLE","macros":{}}""")) }
    }
}
