package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitStore
import java.io.File
import java.io.IOException

/**
 * The label (HH5) — run your own imprint. A crate root with a
 * `label.json` is a label: every kit under it gets a catalog number
 * (`DF-001`, `DF-002`, …) assigned once and **never moved** — new kits
 * take the next numbers in name order, kits that leave keep their
 * entry the way a real catalog keeps deleted releases. `catalog.txt`
 * beside it is the human-readable ledger, and the J-card spine and
 * liner notes wear the number when a kit lives under a labeled root.
 */
object Label {

    const val FILE_NAME = "label.json"
    const val CATALOG_NAME = "catalog.txt"
    const val VERSION = 1

    data class Info(
        val name: String,
        val prefix: String,
        /** Kit name → catalog number. Entries only grow; numbers never move. */
        val catalog: Map<String, Int>,
    ) {
        init {
            require(name.isNotBlank()) { "a label has a name" }
            require(Regex("^[A-Z0-9]{2,5}$").matches(prefix)) { "prefix is 2..5 A-Z/0-9, got '$prefix'" }
        }

        fun numberFor(kitName: String): String? = catalog[kitName]?.let { "%s-%03d".format(java.util.Locale.ROOT, prefix, it) }
    }

    /** Found a label at [root]? Null when the root isn't one. */
    fun load(root: File): Info? {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return null
        val obj = Json.parse(file.readText(Charsets.UTF_8)).obj()
        val version = obj["version"]?.int() ?: throw JsonException("label.json has no version")
        if (version != VERSION) {
            throw JsonException("label.json version $version is not supported (this build reads $VERSION)")
        }
        return Info(
            name = obj["name"]?.str() ?: throw JsonException("label.json has no name"),
            prefix = obj["prefix"]?.str() ?: throw JsonException("label.json has no prefix"),
            catalog = (obj["catalog"] as? JsonValue.Obj)?.entries
                ?.mapValues { (_, v) -> v.int() ?: throw JsonException("catalog numbers are integers") }
                ?: emptyMap(),
        )
    }

    /** Start an imprint at [root]. Refuses a root that already is one. */
    fun init(root: File, name: String, prefix: String? = null): Info {
        require(root.isDirectory) { "no such crate root: $root" }
        if (File(root, FILE_NAME).isFile) {
            throw IOException("already a label: ${File(root, FILE_NAME)} - the imprint exists, just run label again")
        }
        val info = Info(name, prefix?.uppercase() ?: derivePrefix(name), emptyMap())
        save(root, info)
        return assign(root)
    }

    /**
     * The stable-numbering pass: every kit under [root] that has no
     * catalog number yet gets the next one, in name order; existing
     * numbers are untouched. Rewrites `label.json` and `catalog.txt`.
     */
    fun assign(root: File): Info {
        val info = load(root) ?: throw IOException("not a label (no $FILE_NAME): $root")
        val present = KitStore.list(root).mapNotNull { dir ->
            try {
                KitStore.load(dir).name
            } catch (e: Exception) {
                null
            }
        }
        val catalog = info.catalog.toMutableMap()
        var next = (catalog.values.maxOrNull() ?: 0) + 1
        for (kitName in present.distinct().sortedBy { it.lowercase() }) {
            if (kitName !in catalog) catalog[kitName] = next++
        }
        val updated = info.copy(catalog = catalog)
        save(root, updated)
        AtomicFile.writeText(File(root, CATALOG_NAME), catalogText(updated, present.toSet()))
        return updated
    }

    /**
     * The kit's catalog number, when its parent folder is a labeled
     * root — how the J-card and liner notes find theirs. Never assigns;
     * an unlabeled crate (or a kit not yet cataloged) is just null.
     */
    fun forKit(kitDir: File): String? {
        val root = kitDir.absoluteFile.parentFile ?: return null
        val info = try {
            load(root)
        } catch (e: Exception) {
            return null
        } ?: return null
        val kitName = try {
            KitStore.load(kitDir).name
        } catch (e: Exception) {
            return null
        }
        return info.numberFor(kitName)
    }

    /** Initials of the name's words, or its first letters — DF for Dusty Fingers. */
    fun derivePrefix(name: String): String {
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        val initials = words.mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .joinToString("")
        return when {
            initials.length >= 2 -> initials.take(5)
            words.isNotEmpty() && words[0].length >= 2 -> words[0].uppercase().take(3)
            else -> "SNS"
        }
    }

    private fun save(root: File, info: Info) {
        val json = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(info.name),
                "prefix" to JsonValue.Str(info.prefix),
                "catalog" to JsonValue.Obj(
                    info.catalog.entries.sortedBy { it.value }
                        .associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) },
                ),
            ),
        )
        AtomicFile.writeText(File(root, FILE_NAME), Json.write(json) + "\n")
    }

    /** The ledger, numbers ascending; a kit that left the crate says so. */
    private fun catalogText(info: Info, present: Set<String>): String = buildString {
        appendLine(info.name.uppercase())
        appendLine("=".repeat(maxOf(info.name.length, 8)))
        for ((kitName, n) in info.catalog.entries.sortedBy { it.value }) {
            appendLine(
                "%s-%03d  %s%s".format(java.util.Locale.ROOT, info.prefix, n, kitName, if (kitName in present) "" else "  (gone)"),
            )
        }
    }
}
