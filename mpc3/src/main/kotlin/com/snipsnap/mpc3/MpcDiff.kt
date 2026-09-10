package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.max
import org.xml.sax.InputSource

/**
 * Structured diff of two MPC files, either generation — the corpus guard
 * that keeps the writers honest, promoted from test machinery to a bench
 * tool. This is the instrument for "why won't this file load": what the
 * firmware's own save carries that ours doesn't, and where the values part
 * ways.
 *
 * Both sides load by content, never extension: gzip magic is the MPC 3
 * ACVS container (its JSON payload is the tree), an XML declaration is the
 * MPC 2 family (elements become the tree — attributes as `@name`, repeated
 * tags as arrays), and bare JSON (a `kit.json`, a `groove.json`) parses
 * as-is.
 *
 * Structure is compared **schema-aware**, the way the corpus guards do:
 * array indices collapse to `[*]` and pad-table `valueN` keys to `value*`,
 * so "this kit has 4 samples and that one 40" is not four hundred missing
 * paths. Values are compared at concrete paths, sentinel-tolerant: two
 * INT64_MAX-ish numbers are the same sentinel whatever precision they kept
 * (see [Acvs.INT64_MAX_APPROX]), and floats match to a relative 1e-6 —
 * a real delta is a different value, not a different rendering.
 */
object MpcDiff {

    /** One concrete path where both files have a value and they disagree. */
    data class ValueDiff(val path: String, val a: String, val b: String)

    data class Result(
        /** Normalised key paths present in A and absent from B. */
        val onlyInA: Set<String>,
        /** Normalised key paths present in B and absent from A. */
        val onlyInB: Set<String>,
        val valueDiffs: List<ValueDiff>,
    ) {
        val identical: Boolean
            get() = onlyInA.isEmpty() && onlyInB.isEmpty() && valueDiffs.isEmpty()
    }

    fun diff(a: File, b: File): Result = diff(load(a), load(b))

    fun diff(a: JsonValue, b: JsonValue): Result {
        val pathsA = mutableSetOf<String>().also { collect(a, "", it) }
        val pathsB = mutableSetOf<String>().also { collect(b, "", it) }
        val diffs = mutableListOf<ValueDiff>()
        walk(a, b, "", pathsA, pathsB, diffs)
        return Result(pathsA - pathsB, pathsB - pathsA, diffs)
    }

    /** The comparable tree inside any MPC file (or bare JSON). */
    fun load(file: File): JsonValue {
        if (!file.isFile) throw IllegalArgumentException("no such file: $file")
        val bytes = file.readBytes()
        return when (MpcFormats.detect(bytes)) {
            MpcFormat.MPC3_ACVS -> Acvs.read(bytes).payload
                ?: throw AcvsException("$file: ACVS payload isn't json")
            MpcFormat.MPC2_XML -> xmlTree(bytes.toString(Charsets.UTF_8))
            MpcFormat.UNKNOWN -> {
                val text = bytes.toString(Charsets.UTF_8)
                val head = text.trimStart().firstOrNull()
                if (head == '{' || head == '[') {
                    Json.parse(text)
                } else {
                    throw IllegalArgumentException(
                        "$file is not an MPC 3 container, MPC 2 XML, or JSON",
                    )
                }
            }
        }
    }

    /** A human label for what [load] saw in the file. */
    fun formatLabel(file: File): String = when (MpcFormats.detect(file)) {
        MpcFormat.MPC3_ACVS -> "MPC 3 (ACVS ${Acvs.read(file).header.objectType})"
        MpcFormat.MPC2_XML -> "MPC 2 (XML)"
        MpcFormat.UNKNOWN -> "JSON"
    }

    // ---- structure ---------------------------------------------------------

    private val VALUE_N = Regex("value\\d+")

    private fun norm(key: String): String = if (VALUE_N.matches(key)) "value*" else key

    private fun collect(v: JsonValue, prefix: String, out: MutableSet<String>) {
        when (v) {
            is JsonValue.Obj -> v.entries.forEach { (k, child) ->
                val p = "$prefix.${norm(k)}"
                out.add(p)
                collect(child, p, out)
            }
            is JsonValue.Arr -> v.items.forEach { collect(it, "$prefix[*]", out) }
            else -> {}
        }
    }

    // ---- values ------------------------------------------------------------

    private fun walk(
        a: JsonValue,
        b: JsonValue,
        path: String,
        normA: Set<String>,
        normB: Set<String>,
        out: MutableList<ValueDiff>,
    ) {
        when {
            a is JsonValue.Obj && b is JsonValue.Obj -> {
                for (k in a.entries.keys + b.entries.keys) {
                    val av = a.entries[k]
                    val bv = b.entries[k]
                    val child = "$path.$k"
                    when {
                        av != null && bv != null -> walk(av, bv, child, normA, normB, out)
                        // A concrete key missing on one side only counts as a
                        // value delta when the *normalised* path exists in
                        // both — otherwise the structural sets already say it.
                        av != null -> if (normPath(child) in normB) {
                            out += ValueDiff(child, render(av), "(absent)")
                        }
                        bv != null -> if (normPath(child) in normA) {
                            out += ValueDiff(child, "(absent)", render(bv))
                        }
                    }
                }
            }
            a is JsonValue.Arr && b is JsonValue.Arr -> {
                if (a.items.size != b.items.size) {
                    out += ValueDiff("$path[]", "${a.items.size} items", "${b.items.size} items")
                }
                for (i in 0 until minOf(a.items.size, b.items.size)) {
                    walk(a.items[i], b.items[i], "$path[$i]", normA, normB, out)
                }
            }
            else -> if (!scalarEqual(a, b)) out += ValueDiff(path, render(a), render(b))
        }
    }

    private fun normPath(concrete: String): String =
        concrete.split('.').joinToString(".") { seg ->
            val bracket = seg.indexOf('[')
            if (bracket < 0) {
                norm(seg)
            } else {
                norm(seg.take(bracket)) + seg.drop(bracket).replace(Regex("\\[\\d+]"), "[*]")
            }
        }

    private fun scalarEqual(a: JsonValue, b: JsonValue): Boolean = when {
        a is JsonValue.Num && b is JsonValue.Num -> {
            val x = a.value
            val y = b.value
            // Both INT64_MAX-ish: the same "forever" sentinel, whatever
            // precision survived the trip through a double.
            (x >= SENTINEL_FLOOR && y >= SENTINEL_FLOOR) ||
                abs(x - y) <= 1e-6 * max(1.0, max(abs(x), abs(y)))
        }
        else -> a == b
    }

    private const val SENTINEL_FLOOR = 9.0e18

    private fun render(v: JsonValue): String = when (v) {
        is JsonValue.Str -> {
            val s = v.value
            if (s.length <= 60) "\"$s\"" else "\"${s.take(57)}...\" (${s.length} chars)"
        }
        is JsonValue.Num -> {
            val d = v.value
            if (d == Math.rint(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        is JsonValue.Bool -> v.value.toString()
        JsonValue.Null -> "null"
        is JsonValue.Obj -> "(object)"
        is JsonValue.Arr -> "(array)"
    }

    // ---- MPC 2 XML as a tree -----------------------------------------------

    private fun xmlTree(text: String): JsonValue {
        // The files are machine-written; a doctype would be an attack.
        val doc = SafeXml.newFactory().newDocumentBuilder().parse(InputSource(StringReader(text)))
        val root = doc.documentElement
        return JsonValue.Obj(mapOf(root.tagName to element(root)))
    }

    private fun element(e: org.w3c.dom.Element): JsonValue {
        val entries = linkedMapOf<String, JsonValue>()
        for (i in 0 until e.attributes.length) {
            val attr = e.attributes.item(i)
            entries["@${attr.nodeName}"] = JsonValue.Str(attr.nodeValue)
        }
        val children = (0 until e.childNodes.length)
            .mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }
        if (children.isEmpty()) {
            val body = e.textContent.trim()
            if (entries.isEmpty()) return JsonValue.Str(body)
            if (body.isNotEmpty()) entries["#text"] = JsonValue.Str(body)
            return JsonValue.Obj(entries)
        }
        for ((tag, same) in children.groupBy { it.tagName }) {
            entries[tag] = if (same.size == 1) {
                element(same[0])
            } else {
                JsonValue.Arr(same.map { element(it) })
            }
        }
        return JsonValue.Obj(entries)
    }
}
