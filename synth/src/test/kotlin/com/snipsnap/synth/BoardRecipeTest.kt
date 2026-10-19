package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun changed(field: String, value: JsonValue): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(BoardRecipe(amount = .7f).toJsonValue().entries).also { it[field] = value },
    )

    @Test
    fun `foreign versions unknown boards malformed types and out of range saved amounts fail explicitly`() {
        for ((field, value) in listOf(
            "board" to JsonValue.Num(0.0), "board" to JsonValue.Num(2.0), "board" to JsonValue.Num(1.5),
            "board" to JsonValue.Str("1"), "board" to JsonValue.Null,
            "type" to JsonValue.Str("other"), "type" to JsonValue.Str("WIDE"), "type" to JsonValue.Bool(true),
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
