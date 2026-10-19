package com.snipsnap.shell

/**
 * A reader for the small piece of Kotlin a promoted roster line is written in:
 * calls with positional or named arguments (`p(...)`, `.copy(...)`, `listOf(...)`,
 * `MurmurConfig(...)`), `"KEY" to 0.5f` pairs, qualified enum entries, string
 * literals and number literals.
 *
 * It keeps what the Kotlin compiler would see, so a test can hold a line to the
 * rules its paste has to pass: a `$` left unescaped in a string is a template, not
 * a character, and is refused; a number keeps its literal type (`1` is an Int,
 * `1.0` and `1.0E-4` are Doubles, `1.0f` is a Float) because a Float handed to a
 * Double parameter does not compile.
 */
internal object KotlinLiteral {

    sealed interface Node
    data class Str(val value: String) : Node
    data class Num(val text: String, val type: Type) : Node {
        enum class Type { INT, DOUBLE, FLOAT }
    }
    /** `Owner.ENTRY`, e.g. an enum constant. */
    data class Ref(val owner: String, val name: String) : Node
    data class Pair(val first: Node, val second: Node) : Node
    /** `name(args)`, or `receiver.name(args)` for a method call. */
    data class Call(val receiver: Node?, val name: String, val positional: List<Node>, val named: LinkedHashMap<String, Node>) : Node

    fun parse(text: String): Node {
        val reader = Reader(text)
        val node = reader.expression()
        reader.skipSpace()
        require(reader.atEnd()) { "unread text after the expression at ${reader.pos}: '${text.substring(reader.pos)}'" }
        return node
    }

    private class Reader(val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length
        fun skipSpace() { while (!atEnd() && text[pos].isWhitespace()) pos++ }
        fun peek(): Char? = text.getOrNull(pos)
        fun expect(c: Char) {
            skipSpace()
            require(peek() == c) { "expected '$c' at $pos, found '${text.substring(pos).take(20)}'" }
            pos++
        }

        fun expression(): Node {
            var node = postfix()
            skipSpace()
            if (text.startsWith("to ", pos)) {
                pos += 3
                node = Pair(node, postfix())
            }
            return node
        }

        private fun postfix(): Node {
            var node = primary()
            while (true) {
                skipSpace()
                if (peek() != '.' || text.getOrNull(pos + 1)?.isLetter() != true) return node
                pos++
                val name = identifier()
                node = if (peek() == '(') call(node, name) else Ref(owner(node), name)
            }
        }

        private fun owner(node: Node): String = when (node) {
            is Ref -> "${node.owner}.${node.name}"
            is Call -> if (node.receiver == null && node.positional.isEmpty() && node.named.isEmpty()) node.name else error("not a qualifier: $node")
            else -> error("not a qualifier: $node")
        }

        private fun primary(): Node {
            skipSpace()
            val c = peek() ?: error("expression expected at end of text")
            return when {
                c == '"' -> string()
                c == '-' || c.isDigit() -> number()
                c.isLetter() -> {
                    val name = identifier()
                    if (peek() == '(') call(null, name) else Call(null, name, emptyList(), LinkedHashMap())
                }
                else -> error("unexpected '$c' at $pos")
            }
        }

        private fun identifier(): String {
            val start = pos
            while (!atEnd() && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            require(pos > start) { "identifier expected at $start" }
            return text.substring(start, pos)
        }

        private fun call(receiver: Node?, name: String): Call {
            expect('(')
            val positional = mutableListOf<Node>()
            val named = LinkedHashMap<String, Node>()
            skipSpace()
            if (peek() == ')') { pos++; return Call(receiver, name, positional, named) }
            while (true) {
                skipSpace()
                val label = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*").matchAt(text, pos)
                if (label != null) {
                    pos += label.value.length
                    val key = label.groupValues[1]
                    require(key !in named) { "argument $key given twice" }
                    named[key] = expression()
                } else {
                    require(named.isEmpty()) { "positional argument after a named one at $pos" }
                    positional += expression()
                }
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ')' -> { pos++; return Call(receiver, name, positional, named) }
                    else -> error("expected ',' or ')' at $pos, found '${text.substring(pos).take(20)}'")
                }
            }
        }

        private fun string(): Str {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek() ?: error("unterminated string")
                pos++
                when (c) {
                    '"' -> return Str(sb.toString())
                    '$' -> error("an unescaped '$' at ${pos - 1} is a Kotlin string template, not a character")
                    '\\' -> {
                        val e = peek() ?: error("dangling escape")
                        pos++
                        sb.append(
                            when (e) {
                                '"' -> '"'; '\\' -> '\\'; '$' -> '$'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                else -> error("'\\$e' is not a Kotlin escape")
                            },
                        )
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Num {
            val m = Regex("-?[0-9][0-9_]*(\\.[0-9]+)?([eE][-+]?[0-9]+)?([fFL])?").matchAt(text, pos)
                ?: error("number expected at $pos")
            pos += m.value.length
            val suffix = m.groupValues[3]
            val type = when {
                suffix == "f" || suffix == "F" -> Num.Type.FLOAT
                suffix == "L" -> error("Long literals are not read here: ${m.value}")
                m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty() -> Num.Type.DOUBLE
                else -> Num.Type.INT
            }
            return Num(m.value, type)
        }
    }
}
