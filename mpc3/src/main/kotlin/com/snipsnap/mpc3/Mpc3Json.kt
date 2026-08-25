package com.snipsnap.mpc3

/**
 * The tiny JSON node type the MPC 3 writers build with — kept separate from
 * the shared `:json` module because the MPC fingerprint style needs explicit
 * int/double control (`"pitch": 0.0` must stay a float; the shared writer
 * collapses whole doubles to integers). Rendered 4-space pretty, the style
 * real payloads use.
 */
internal sealed interface J {
    class O(val e: List<Pair<String, J>>) : J
    class A(val items: List<J>) : J
    class S(val v: String) : J
    class I(val v: Long) : J
    class D(val v: Double) : J
    class B(val v: Boolean) : J
}

internal fun obj(vararg pairs: Pair<String, J>): J = J.O(pairs.toList())
internal fun s(v: String): J = J.S(v)
internal fun i(v: Long): J = J.I(v)
internal fun i(v: Int): J = J.I(v.toLong())
internal fun d(v: Double): J = J.D(v)
internal fun b(v: Boolean): J = J.B(v)
internal fun v0(v: J): J = J.O(listOf("value0" to v))

internal fun render(root: J): String = StringBuilder(8 * 1024 * 1024).also { renderNode(root, it, 0) }.toString()

/** Render starting at [indent] pad levels — for splicing into a skeleton. */
internal fun renderAt(root: J, indent: Int): String =
    StringBuilder(1024 * 1024).also { renderNode(root, it, indent) }.toString()

private fun renderNode(v: J, sb: StringBuilder, indent: Int) {
    when (v) {
        is J.S -> renderString(v.v, sb)
        is J.I -> sb.append(v.v)
        is J.B -> sb.append(if (v.v) "true" else "false")
        is J.D -> sb.append(v.v)   // Double.toString keeps the decimal point: 1.0, 0.375, 0.7079460024833679
        is J.A -> {
            if (v.items.isEmpty()) { sb.append("[]"); return }
            sb.append("[\n")
            v.items.forEachIndexed { n, item ->
                pad(sb, indent + 1); renderNode(item, sb, indent + 1)
                if (n != v.items.size - 1) sb.append(',')
                sb.append('\n')
            }
            pad(sb, indent); sb.append(']')
        }
        is J.O -> {
            if (v.e.isEmpty()) { sb.append("{}"); return }
            sb.append("{\n")
            v.e.forEachIndexed { n, (k, item) ->
                pad(sb, indent + 1); renderString(k, sb); sb.append(": ")
                renderNode(item, sb, indent + 1)
                if (n != v.e.size - 1) sb.append(',')
                sb.append('\n')
            }
            pad(sb, indent); sb.append('}')
        }
    }
}

private fun pad(sb: StringBuilder, indent: Int) = repeat(indent) { sb.append("    ") }

private fun renderString(str: String, sb: StringBuilder) {
    sb.append('"')
    for (c in str) {
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
