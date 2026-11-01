package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OtisPrototypeRecipeTest {
    private val request = OtisPrototypeRequest(
        voice = OtisPrototypeVoice.ORGAN,
        notes = listOf(OtisPrototypeNote(57, .0, .12, .36f), OtisPrototypeNote(64, .09, .10, .91f)),
        seed = -3187,
        organ = OtisOrganSettings(
            drawbars = listOf(.2f, .4f, .8f, .1f, .3f, .5f, .7f, .9f, .6f),
            click = .29f, percussionLevel = .33f, percussionHarmonic = OtisOrganPercussion.SECOND,
            percussionDecaySeconds = .21, leakage = .41f, drive = .24f,
            attackSeconds = .008, releaseSeconds = .08, cabinetHz = 6700.0,
            rotary = true, rotaryStart = OtisOrganRotary.BRAKE, rotaryTarget = OtisOrganRotary.SLOW,
            rotaryChangeSeconds = .07, rotaryDepth = .43f,
        ),
        air = OtisAirSettings(.21f, .32f, .43f, .54f, .65f, .76f, .87f, .19f),
    )

    @Test
    fun `complete request roundtrips and reproduces every family bit for bit`() {
        for (model in 1..OtisPrototype.MODEL_VERSION) for (voice in OtisPrototypeVoice.entries) {
            val original = request.copy(voice = voice, model = model)
            val text = OtisPrototypeRecipe.toJsonText(original)
            val restored = OtisPrototypeRecipe.fromJsonText(text)
            assertEquals(original, restored, "$voice model $model settings changed")
            assertEquals(text, OtisPrototypeRecipe.toJsonText(restored))
            assertContentEquals(original.render().samples, restored.render().samples, "$voice model $model audio changed")
        }
    }

    private fun root(field: String, value: JsonValue?): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(OtisPrototypeRecipe.toJsonValue(request).entries).also { if (value == null) it.remove(field) else it[field] = value },
    )
    private fun nested(parent: String, field: String, value: JsonValue?): JsonValue.Obj {
        val fields = OtisPrototypeRecipe.toJsonValue(request).entries.getValue(parent).obj().toMutableMap()
        if (value == null) fields.remove(field) else fields[field] = value
        return root(parent, JsonValue.Obj(fields))
    }
    private fun note(field: String, value: JsonValue): JsonValue.Obj {
        val notes = OtisPrototypeRecipe.toJsonValue(request).entries.getValue("notes").arr().toMutableList()
        notes[0] = JsonValue.Obj(notes[0].obj() + (field to value))
        return root("notes", JsonValue.Arr(notes))
    }

    @Test
    fun `unknown and missing fields are refused at every object boundary`() {
        val malformed = listOf(
            root("future", JsonValue.Bool(true)) to "unknown recipe fields: future",
            root("seed", null) to "missing recipe fields: seed",
            nested("organ", "future", JsonValue.Num(1.0)) to "unknown organ fields: future",
            nested("organ", "drawbars", null) to "missing organ fields: drawbars",
            nested("air", "future", JsonValue.Num(1.0)) to "unknown air fields: future",
            nested("air", "breath", null) to "missing air fields: breath",
            note("future", JsonValue.Num(1.0)) to "unknown notes[0] fields: future",
        )
        for ((value, message) in malformed) {
            val failure = assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonValue(value) }
            assertTrue(failure.message.orEmpty().contains(message), failure.message)
        }
    }

    @Test
    fun `schema model enums and primitive types are strict`() {
        for ((value, message) in listOf(
            root("format", JsonValue.Str("STOOP")) to "not an Otis prototype recipe",
            root("model", JsonValue.Num(7.0)) to "unsupported Otis prototype model 7",
            root("model", JsonValue.Num(0.0)) to "unsupported Otis prototype model 0",
            root("voice", JsonValue.Str("TONEWHEEL")) to "unknown voice TONEWHEEL",
            nested("organ", "percussionHarmonic", JsonValue.Str("FOURTH")) to "unknown percussionHarmonic FOURTH",
            nested("organ", "rotaryTarget", JsonValue.Str("MEDIUM")) to "unknown rotaryTarget MEDIUM",
        )) {
            assertEquals(message, assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonValue(value) }.message)
        }
        for (value in listOf(
            root("notes", JsonValue.Obj(emptyMap())), root("organ", JsonValue.Null), root("air", JsonValue.Bool(false)),
            root("model", JsonValue.Str("1")), root("model", JsonValue.Num(1.5)), root("seed", JsonValue.Num(.5)),
            root("voice", JsonValue.Num(0.0)), nested("organ", "rotary", JsonValue.Num(1.0)),
            nested("organ", "drawbars", JsonValue.Str("888000000")), nested("air", "pressure", JsonValue.Str("soft")),
            note("midi", JsonValue.Num(60.5)), note("velocity", JsonValue.Bool(true)),
        )) assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonValue(value) }
        val text = OtisPrototypeRecipe.toJsonText(request)
        val duplicated = text.replace("\"model\": ${request.model}", "\"model\": ${request.model}, \"model\": ${request.model}")
        assertTrue(assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonText(duplicated) }.message.orEmpty().contains("duplicate key"))
    }

    @Test
    fun `decoded requests obey constructor bounds and reject nonfinite values`() {
        for ((value, field) in listOf(
            note("midi", JsonValue.Num(35.0)) to "MIDI",
            note("startSeconds", JsonValue.Num(-.01)) to "start",
            note("gateSeconds", JsonValue.Num(1000.0)) to "gate",
            nested("organ", "releaseSeconds", JsonValue.Num(1000.0)) to "releaseSeconds",
            nested("organ", "attackSeconds", JsonValue.Num(-.01)) to "attackSeconds",
            nested("organ", "cabinetHz", JsonValue.Num(100_000.0)) to "cabinetHz",
            nested("organ", "drawbars", JsonValue.Arr(List(8) { JsonValue.Num(.5) })) to "drawbars",
            root("notes", JsonValue.Arr(emptyList())) to "notes",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { OtisPrototypeRecipe.fromJsonValue(value) }
            assertTrue(failure.message.orEmpty().contains(field, ignoreCase = true), failure.message)
        }
        // Check the original JSON number before Float rounding can hide a small range violation.
        for ((value, field) in listOf(
            note("velocity", JsonValue.Num(1.00000001)) to "velocity",
            nested("air", "pressure", JsonValue.Num(-.1)) to "pressure",
            nested("air", "breath", JsonValue.Num(Double.NaN)) to "breath",
            nested("organ", "click", JsonValue.Num(Double.POSITIVE_INFINITY)) to "click",
            nested("organ", "drawbars", JsonValue.Arr(List(9) { JsonValue.Num(1.00000001) })) to "drawbars",
            nested("organ", "rotaryChangeSeconds", JsonValue.Num(Double.NEGATIVE_INFINITY)) to "rotaryChangeSeconds",
        )) {
            val failure = assertFailsWith<JsonException> { OtisPrototypeRecipe.fromJsonValue(value) }
            assertTrue(failure.message.orEmpty().contains(field), failure.message)
        }
    }
}
