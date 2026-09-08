package com.snipsnap.shell

import java.io.File

/**
 * Getting a finished export onto the card.
 *
 * The export drivers all write `java.io.File` trees, and they are the
 * byte-stable, corpus-guarded part of this project — the last thing that
 * should be rewritten to satisfy a storage API. A card picked through
 * the system's folder picker is not a `File` at all: it is a tree the
 * app reaches only through the platform's document API.
 *
 * So the write stays exactly as it is, into the app's own space, and the
 * finished tree is copied across afterwards. That costs a second pass
 * over the bytes and the room to hold them twice, which is the honest
 * price of leaving the writers alone. What it buys is that the card gets
 * the same bytes the tests and the golden fixtures cover, rather than
 * bytes from a second implementation nobody checks.
 *
 * This is the part of that copy which is a decision rather than an API
 * call: what to create, in what order, and under what name. The calling
 * side does the platform work and is not testable off a device; this is,
 * and holds the ordering rule that makes the rest possible.
 */
object CardCopy {

    /**
     * One thing to create on the card. [segments] is the path from the
     * copy's root, so `["Kit", "Samples", "kick.wav"]` — the last element
     * is the entry's own name, and everything before it is a folder that
     * [plan] has already listed.
     */
    data class Entry(
        val segments: List<String>,
        val isDirectory: Boolean,
        val source: File,
    ) {
        init {
            require(segments.isNotEmpty()) { "an entry needs a name" }
        }

        /** The entry's own name on the card. */
        val name: String get() = segments.last()

        /** The folders above it, outermost first — empty at the copy's root. */
        val parents: List<String> get() = segments.dropLast(1)
    }

    /**
     * [items] and everything under them, **parents before their
     * contents**. That order is the whole point: a document API creates a
     * child inside a folder it is handed, so a folder that has not been
     * created yet has no handle to pass, and a copy walking the tree in
     * any other order would have to remember or re-find them.
     * Breadth-first, so every folder in an entry's [Entry.parents]
     * appears earlier in the list than the entry itself.
     *
     * It takes the things to copy and not a folder to copy *out of*,
     * because an export's own output is what belongs on the card — an
     * `ExportOutcome`'s `primary` and its `companion`. The folder those
     * were written into is the app's whole exports directory, and for a
     * format that nests its own name inside it, handing that folder over
     * would put every export ever made onto the card alongside this one.
     *
     * Each item lands at the copy's root under its own name; a missing
     * one is skipped rather than refused, since a driver that produced no
     * companion simply has none to copy.
     */
    fun plan(items: List<File>): List<Entry> {
        val out = ArrayList<Entry>()
        val queue = ArrayDeque<Pair<File, List<String>>>()
        for (item in items) {
            if (!item.exists()) continue
            val segments = listOf(item.name)
            out += Entry(segments, item.isDirectory, item)
            if (item.isDirectory) queue += item to segments
        }
        while (queue.isNotEmpty()) {
            val (dir, prefix) = queue.removeFirst()
            // Sorted so a copy is the same twice running - a directory
            // listing is not ordered, and an export that lands in a
            // different order each time is one nobody can diff.
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                val segments = prefix + child.name
                out += Entry(segments, child.isDirectory, child)
                if (child.isDirectory) queue += child to segments
            }
        }
        return out
    }

    /** Total bytes the plan will move — what a progress readout counts against. */
    fun byteCount(entries: List<Entry>): Long =
        entries.filter { !it.isDirectory }.sumOf { it.source.length() }

    /**
     * The MIME type to create a document with. The platform wants one,
     * and gets it wrong from the extension often enough to matter: an
     * `.xpm` guessed as text arrives on the card with `.txt` stuck on the
     * end, which the MPC will not read. Anything unrecognised is binary
     * rather than a guess, since a wrong type renames the file and a
     * generic one does not.
     */
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "mid", "midi" -> "audio/midi"
        "png" -> "image/png"
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
