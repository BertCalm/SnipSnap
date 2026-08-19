package com.snipsnap.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonTest {

    private fun roundTrip(v: JsonValue): JsonValue = Json.parse(Json.write(v))

    @Test
    fun `round-trips a nested document`() {
        val doc = JsonValue.Obj(
            linkedMapOf(
                "name" to JsonValue.Str("Kit \"01\" — naïve\n"),
                "version" to JsonValue.Num(1.0),
                "level" to JsonValue.Num(0.707946),
                "on" to JsonValue.Bool(true),
                "off" to JsonValue.Bool(false),
                "nothing" to JsonValue.Null,
                "items" to JsonValue.Arr(
                    listOf(JsonValue.Num(-3.0), JsonValue.Str("a\\b"), JsonValue.Obj(emptyMap())),
                ),
            ),
        )
        assertEquals(doc, roundTrip(doc))
    }

    @Test
    fun `parses escapes and unicode`() {
        val v = Json.parse(""""a\n\t\"\\\u0041\u00e9"""")
        assertEquals("a\n\t\"\\Aé", v.str())
    }

    @Test
    fun `parses numbers in all shapes`() {
        assertEquals(0.0, Json.parse("0").num())
        assertEquals(-12.0, Json.parse("-12").num())
        assertEquals(3.5, Json.parse("3.5").num())
        assertEquals(1200.0, Json.parse("1.2e3").num())
        assertEquals(42, Json.parse("42").int())
    }

    @Test
    fun `int refuses a fraction`() {
        assertFailsWith<JsonException> { Json.parse("1.5").int() }
    }

    @Test
    fun `rejects malformed input`() {
        for (bad in listOf(
            "", "{", "[1,", "{\"a\":}", "\"unterminated", "{\"a\":1,}", "tru",
            "1 2", "{\"a\":1}extra", "\"bad\\q\"", "\"\\u00g1\"", "01x", "- 1", "{a:1}",
        )) {
            assertFailsWith<JsonException>("should reject: $bad") { Json.parse(bad) }
        }
    }

    @Test
    fun `rejects duplicate keys`() {
        assertFailsWith<JsonException> { Json.parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun `rejects raw control characters in strings`() {
        assertFailsWith<JsonException> { Json.parse("\"a\u0001b\"") }
    }

    @Test
    fun `rejects absurd nesting`() {
        val deep = "[".repeat(60) + "]".repeat(60)
        assertFailsWith<JsonException> { Json.parse(deep) }
    }

    @Test
    fun `writer escapes control characters`() {
        val out = Json.write(JsonValue.Str("a\u0007b"))
        assertTrue("\\u0007" in out, out)
    }

    @Test
    fun `whole numbers render without a trailing point`() {
        assertEquals("42", Json.write(JsonValue.Num(42.0)))
        assertTrue("." in Json.write(JsonValue.Num(0.5)))
    }

    @Test
    fun `preserves key order`() {
        val parsed = Json.parse("""{"z":1,"a":2,"m":3}""").obj()
        assertEquals(listOf("z", "a", "m"), parsed.keys.toList())
    }
}
