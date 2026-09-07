package com.snipsnap.json

/**
 * A deliberately tiny JSON reader/writer for SnipSnap's file formats — the
 * kit.json sidecar and the JSON payload inside MPC 3 ACVS containers.
 *
 * Hand-rolled rather than a dependency so every module in this repo stays
 * consumable by the Android app with nothing else on the classpath. Strict
 * about everything: duplicate keys, trailing garbage, raw control characters
 * and absurd nesting are errors, not shrugs.
 */
sealed class JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    fun obj(): Map<String, JsonValue> = (this as? Obj)?.entries ?: bad("object")
    fun arr(): List<JsonValue> = (this as? Arr)?.items ?: bad("array")
    fun str(): String = (this as? Str)?.value ?: bad("string")
    fun num(): Double = (this as? Num)?.value ?: bad("number")
    fun int(): Int = num().let { d ->
        val i = d.toInt()
        if (i.toDouble() != d) throw JsonException("expected an integer, got $d")
        i
    }
    /** A whole number inside Long's range: fractions, infinities and anything past 2^63 refuse rather than truncate or clamp. */
    fun long(): Long = num().let { d ->
        if (d.isNaN() || d != Math.floor(d) || d < Long.MIN_VALUE.toDouble() || d >= Long.MAX_VALUE.toDouble()) {
            throw JsonException("expected a whole number in range, got $d")
        }
        d.toLong()
    }
    fun bool(): Boolean = (this as? Bool)?.value ?: bad("boolean")

    private fun bad(wanted: String): Nothing =
        throw JsonException("expected $wanted, got ${this::class.simpleName}")
}

class JsonException(message: String) : RuntimeException(message)

object Json {

    // MPC 3 projects nest ~10 levels deep; 64 leaves headroom without
    // letting a malicious file recurse to a stack overflow.
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (!p.eof) p.fail("trailing content")
        return v
    }

    fun write(value: JsonValue): String = StringBuilder().also { render(value, it, 0) }.toString()

    private fun render(v: JsonValue, sb: StringBuilder, indent: Int) {
        when (v) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is JsonValue.Num -> {
                val d = v.value
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is JsonValue.Str -> renderString(v.value, sb)
            is JsonValue.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.items.forEachIndexed { i, item ->
                    pad(sb, indent + 1); render(item, sb, indent + 1)
                    if (i != v.items.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent); sb.append(']')
            }
            is JsonValue.Obj -> {
                if (v.entries.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val keys = v.entries.entries.toList()
                keys.forEachIndexed { i, (k, item) ->
                    pad(sb, indent + 1); renderString(k, sb); sb.append(": ")
                    render(item, sb, indent + 1)
                    if (i != keys.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent); sb.append('}')
            }
        }
    }

    private fun pad(sb: StringBuilder, indent: Int) = repeat(indent) { sb.append("  ") }

    private fun renderString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var i = 0
        val eof get() = i >= s.length

        fun fail(msg: String): Nothing = throw JsonException("$msg at offset $i")

        fun ws() {
            while (!eof && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            ws()
            if (eof) fail("unexpected end of input")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun obj(depth: Int): JsonValue {
            expect('{')
            val out = LinkedHashMap<String, JsonValue>()
            ws()
            if (!eof && s[i] == '}') { i++; return JsonValue.Obj(out) }
            while (true) {
                ws()
                val key = string()
                if (out.containsKey(key)) fail("duplicate key \"$key\"")
                ws(); expect(':')
                out[key] = value(depth + 1)
                ws()
                if (eof) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonValue.Obj(out) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonValue {
            expect('[')
            val out = ArrayList<JsonValue>()
            ws()
            if (!eof && s[i] == ']') { i++; return JsonValue.Arr(out) }
            while (true) {
                out.add(value(depth + 1))
                ws()
                if (eof) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonValue.Arr(out) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (eof) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape \\u$hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    c.code < 0x20 -> fail("raw control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): JsonValue {
            val start = i
            if (!eof && s[i] == '-') i++
            if (eof || s[i] !in '0'..'9') fail("expected a value")
            while (!eof && s[i] in '0'..'9') i++
            if (!eof && s[i] == '.') {
                i++
                if (eof || s[i] !in '0'..'9') fail("bad number")
                while (!eof && s[i] in '0'..'9') i++
            }
            if (!eof && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!eof && (s[i] == '+' || s[i] == '-')) i++
                if (eof || s[i] !in '0'..'9') fail("bad exponent")
                while (!eof && s[i] in '0'..'9') i++
            }
            return JsonValue.Num(s.substring(start, i).toDouble())
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            if (eof || s[i] != c) fail("expected '$c'")
            i++
        }
    }
}
