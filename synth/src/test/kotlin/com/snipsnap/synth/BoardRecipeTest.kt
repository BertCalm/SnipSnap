package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoardRecipeTest {
    @Test
    fun `version one stores only board identity and amount with stable roundtrips`() {
        for (amount in listOf(0f, .35f, .7f, 1f)) {
            val recipe = BoardRecipe(amount = amount)
            val json = recipe.toJsonValue()
            assertEquals(setOf("board", "type", "amount"), json.entries.keys)
            assertEquals(JsonValue.Num(1.0), json.entries["board"])
            assertEquals(JsonValue.Str("wide"), json.entries["type"])
            assertEquals(recipe, BoardRecipe.fromJsonValue(json))
            assertEquals(recipe, BoardRecipe.fromJsonText(recipe.toJsonText()))
            assertEquals(recipe.toJsonText(), BoardRecipe.fromJsonText(recipe.toJsonText()).toJsonText())
        }
        assertTrue(BoardRecipe(amount = 0f).isBypass)
        assertFalse(BoardRecipe(amount = .35f).isBypass)
        assertEquals(BoardType.WIDE, BoardRecipe.fromJsonText("""{"amount":0.7,"type":"wide","board":1}""").type)
    }

    @Test
    fun `rush stores its own identity in version one with stable JSON roundtrips`() {
        for (amount in listOf(0f, .35f, .7f, 1f)) {
            val recipe = BoardRecipe(BoardType.RUSH, amount)
            val json = recipe.toJsonValue()
            assertEquals(setOf("board", "type", "amount"), json.entries.keys)
            assertEquals(JsonValue.Num(1.0), json.entries["board"])
            assertEquals(JsonValue.Str("rush"), json.entries["type"])
            assertEquals(JsonValue.Num(amount.toDouble()), json.entries["amount"])
            assertEquals(recipe, BoardRecipe.fromJsonValue(json))
            val restored = BoardRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored)
            assertEquals(recipe.toJsonText(), restored.toJsonText())
        }
        assertEquals(BoardRecipe(BoardType.RUSH, .7f), BoardRecipe.fromJsonText(
            """{"amount":0.7,"type":"rush","board":1}""",
        ))
    }

    @Test
    fun `rush processing dispatches to its renderer and zero is a hard bypass`() {
        val source = Snip(FloatArray(2048) { index ->
            when (index % 4) { 0 -> .6f; 1 -> -.2f; 2 -> .1f; else -> 0f }
        }, 1, 44_100)
        val original = source.samples.copyOf()
        for (amount in listOf(.35f, .7f, 1f)) {
            val expected = Rush.process(source, amount)
            val actual = BoardRecipe(BoardType.RUSH, amount).process(source)
            assertEquals(expected.channels, actual.channels)
            assertEquals(expected.sampleRate, actual.sampleRate)
            assertContentEquals(expected.samples, actual.samples)
        }
        assertContentEquals(original, source.samples)
        val bypass = BoardRecipe(BoardType.RUSH, 0f)
        assertTrue(bypass.isBypass)
        assertFalse(BoardRecipe(BoardType.RUSH, .35f).isBypass)
        assertSame(source, bypass.process(source))
        val signedZero = Snip(floatArrayOf(-0.0f, .3f, -.7f, 0f), 2, 44_100)
        assertSame(signedZero, bypass.process(signedZero))
        assertEquals(Int.MIN_VALUE, signedZero.samples[0].toRawBits())
    }

    @Test
    fun `legacy wide recipes keep their identity and defaults after adding rush`() {
        val legacy = BoardRecipe.fromJsonText("""{"board":1,"type":"wide","amount":0.5}""")
        assertEquals(BoardRecipe(BoardType.WIDE, .5f), legacy)
        assertEquals(BoardType.WIDE, BoardRecipe().type)
        assertEquals(JsonValue.Str("wide"), legacy.toJsonValue().entries["type"])
        assertEquals(legacy, BoardRecipe.fromJsonText(legacy.toJsonText()))
    }

    private fun changed(field: String, value: JsonValue): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(BoardRecipe(amount = .7f).toJsonValue().entries).also { it[field] = value },
    )

    @Test
    fun `foreign versions unknown boards malformed types and out of range saved amounts fail explicitly`() {
        for ((field, value) in listOf(
            "board" to JsonValue.Num(0.0), "board" to JsonValue.Num(2.0), "board" to JsonValue.Num(1.5),
            "board" to JsonValue.Str("1"), "board" to JsonValue.Null,
            "type" to JsonValue.Str("other"), "type" to JsonValue.Str("WIDE"),
            "type" to JsonValue.Str("RUSH"), "type" to JsonValue.Bool(true),
            "amount" to JsonValue.Num(-.001), "amount" to JsonValue.Num(1.0000000001),
            "amount" to JsonValue.Num(Double.NaN), "amount" to JsonValue.Num(Double.POSITIVE_INFINITY),
            "amount" to JsonValue.Str("0.7"), "amount" to JsonValue.Null,
            "extra" to JsonValue.Num(0.0),
        )) assertFailsWith<JsonException>("accepted $field=$value") {
            BoardRecipe.fromJsonValue(changed(field, value))
        }
        assertFailsWith<JsonException> { BoardRecipe.fromJsonValue(JsonValue.Arr(emptyList())) }
    }

    @Test
    fun `missing fields and duplicate keys cannot silently choose a different sound`() {
        for (field in listOf("board", "type", "amount")) {
            val missing = LinkedHashMap(BoardRecipe().toJsonValue().entries).also { it.remove(field) }
            assertFailsWith<JsonException> { BoardRecipe.fromJsonValue(JsonValue.Obj(missing)) }
        }
        assertFailsWith<JsonException> {
            BoardRecipe.fromJsonText("""{"board":1,"type":"wide","amount":0.35,"amount":1}""")
        }
    }
}
