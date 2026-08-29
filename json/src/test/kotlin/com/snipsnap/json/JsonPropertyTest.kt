package com.snipsnap.json

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The JSON parser underlies every `kit.json`, `groove.json` and ACVS
 * payload — the most load-bearing parser in the codebase. This pins it two
 * ways: the round-trip property (anything we write, we read back identical)
 * over random trees, and a mutation fuzz asserting every malformed input
 * ends in a `JsonException`, never an `AIOOBE`, NPE, or `StackOverflow`.
 */
class JsonPropertyTest {

    /** A random JSON value, depth-bounded. Numbers are kept "nice" so the
     *  test isn't about float formatting — that has its own cases below. */
    private fun randomValue(rnd: Random, depth: Int): JsonValue = when {
        depth <= 0 -> leaf(rnd)
        else -> when (rnd.nextInt(6)) {
            0 -> JsonValue.Obj(
                (0 until rnd.nextInt(5)).associate { randomString(rnd) to randomValue(rnd, depth - 1) },
            )
            1 -> JsonValue.Arr((0 until rnd.nextInt(5)).map { randomValue(rnd, depth - 1) })
            else -> leaf(rnd)
        }
    }

    private fun leaf(rnd: Random): JsonValue = when (rnd.nextInt(5)) {
        0 -> JsonValue.Str(randomString(rnd))
        1 -> JsonValue.Num(rnd.nextInt(-1_000_000, 1_000_000).toDouble())
        2 -> JsonValue.Bool(rnd.nextBoolean())
        3 -> JsonValue.Null
        else -> JsonValue.Num(rnd.nextInt(-1000, 1000) + 0.5) // a clean half
    }

    private fun randomString(rnd: Random): String = buildString {
        repeat(rnd.nextInt(6)) {
            // Include the characters that exercise escaping: quotes,
            // backslashes, control chars, and a non-ASCII code point.
            append("ab\"\\\n\tü\u0001x"[rnd.nextInt(9)])
        }
    }

    @Test
    fun `anything written parses back identical`() {
        repeat(2_000) { seed ->
            val rnd = Random(seed)
            val value = randomValue(rnd, depth = 5)
            val text = Json.write(value)
            val back = Json.parse(text)
            assertEquals(value, back, "seed $seed did not round-trip: $text")
        }
    }

    @Test
    fun `number, unicode and escape edge cases parse to the right values`() {
        assertEquals(1e10, (Json.parse("1e10") as JsonValue.Num).value)
        assertEquals(1.5e-3, (Json.parse("1.5E-3") as JsonValue.Num).value)
        assertEquals(-0.0, (Json.parse("-0.0") as JsonValue.Num).value)
        assertEquals(123456789.0, (Json.parse("123456789") as JsonValue.Num).value)
        // \u escape and a surrogate pair (an emoji) survive intact.
        assertEquals("A", (Json.parse("\"\\u0041\"") as JsonValue.Str).value)
        assertEquals("😀", (Json.parse("\"\\uD83D\\uDE00\"") as JsonValue.Str).value)
        // Duplicate keys are refused outright - stricter than the common
        // "last value wins", and the safer choice: an ambiguous object is a
        // bad object.
        assertFailsWith<JsonException> { Json.parse("""{"k":"1","k":"2"}""") }
        // A raw control char in a string is refused, per spec.
        assertFailsWith<JsonException> { Json.parse("\"a\u0001b\"") }
    }

    @Test
    fun `deep nesting is refused, not a stack overflow`() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertFailsWith<JsonException> { Json.parse(deep) }
    }

    @Test
    fun `mutating valid JSON always ends typed-or-valid, never a crash`() {
        val seeds = listOf(
            """{"a":1,"b":[true,null,"x\u00fc"],"c":{"d":-2.5e3}}""",
            """[1,2,3,{"k":"v"},false]""",
            """"just a string with \" and \\ "}""",
            "12345.678e-2",
        )
        for ((si, valid) in seeds.withIndex()) {
            val rnd = Random(si)
            repeat(3_000) { i ->
                val mutant = mutate(valid, rnd)
                try {
                    Json.parse(mutant)
                } catch (e: JsonException) {
                    // the one allowed outcome for bad input
                } catch (t: Throwable) {
                    fail("seed $si round $i: untyped ${t::class.simpleName} on ${mutant.take(40)}")
                }
            }
        }
    }

    private fun mutate(valid: String, rnd: Random): String {
        val chars = valid.toCharArray()
        when (rnd.nextInt(4)) {
            0 -> if (chars.isNotEmpty()) chars[rnd.nextInt(chars.size)] = rnd.nextInt(32, 127).toChar()
            1 -> return valid.take(if (valid.isEmpty()) 0 else rnd.nextInt(valid.length))
            2 -> if (chars.isNotEmpty()) chars[rnd.nextInt(chars.size)] = "\\\"{}[]".random(rnd)
            3 -> return valid + "\\\"{}[],:".random(rnd)
        }
        return String(chars)
    }
}
