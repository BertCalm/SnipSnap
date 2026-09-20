package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/**
 * BENCH NOTES (`docs/WORKSHOP.md`, WS4): what the ear noticed, written
 * down where it was noticed.
 *
 * `docs/BENCH.md` is the list of everything only a phone in a hand can
 * answer, and its answers were written on a laptop from memory, hours
 * later. The phone knows the context a note is about — which screen,
 * which kit, which pad, when — so with the WORKSHOP open the title bar
 * grows a NOTE chip on every screen: a line of text, the context stamped
 * on it, appended to `Bench/notes.jsonl` beside the kits. SEND TO BENCH
 * carries the file and renders every note in its manifest as a `→` line,
 * ready to paste under the BENCH.md row it answers ([render]).
 *
 * A note is one line: whitespace typed into it, newlines included,
 * collapses to single spaces ([oneLine]), because the `→` line it becomes
 * at the desk is one line too. Never audio, and never a log the machine
 * wrote — these are the tester's own words, and the only thing in the
 * bench zip a person typed.
 */
object BenchNotes {

    /** The folder beside the kits — a folder with no `kit.json`, so the shelf never lists it. */
    const val DIR = "Bench"

    const val FILE_NAME = "notes.jsonl"

    /** Between the parts of a context: `PLAY · Break Kit · A03`. */
    const val SEPARATOR = " · "

    data class Note(
        /** When it was taken — the same `yyyy-MM-dd HHmm` stamp the hand-outs carry in their names, made by the caller. */
        val at: String,
        /** The screen it was taken on, as the menu row names it: PLAY, CHOP, SETUP. */
        val screen: String,
        /** The open kit, or null with none open. */
        val kit: String?,
        /** The pad whose sheet was open, `A03`, or null. */
        val pad: String?,
        /** What was heard, as one line. */
        val text: String,
    ) {
        init {
            require(text.isNotBlank()) { "a note says something" }
        }

        /** Where it was taken, the way the dialog and the manifest say it: [context] of its own parts. */
        val context: String get() = context(screen, kit, pad)
    }

    /**
     * When and where a note is taken, read from the app the moment the
     * NOTE chip is tapped and held until KEEP: the tap is the moment the
     * note is about, not the end of the typing, and reading the four
     * parts once means the context line the dialog shows and the line
     * KEEP writes can never disagree.
     */
    data class Stamp(val at: String, val screen: String, val kit: String?, val pad: String?) {
        /** What the dialog shows before a word is typed: [context] of these parts. */
        val context: String get() = context(screen, kit, pad)

        /** The note KEEP writes: this stamp and the words. */
        fun note(text: String): Note = Note(at, screen, kit, pad, text)
    }

    /** Where the notes live under [shelfRoot]. */
    fun file(shelfRoot: File): File = File(File(shelfRoot, DIR), FILE_NAME)

    /** `PLAY · Break Kit · A03` — the parts that are known, in that order; the dialog shows it before a word is typed. */
    fun context(screen: String, kit: String?, pad: String?): String = listOfNotNull(screen, kit, pad).joinToString(SEPARATOR)

    /** A note as typed, as one line: trimmed, every run of whitespace (a newline included) one space. */
    fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** One line per note; append-friendly, the same shape as [TeachLog.toJsonl]. */
    fun toJsonl(notes: List<Note>): String =
        notes.joinToString("") { Json.write(toJson(it)).replace("\n", "").replace("    ", "") + "\n" }

    fun fromJsonl(text: String): List<Note> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            // A process killed mid-append leaves a torn last line, and one
            // bad line must not lose the rest — [TeachLog.fromJsonl]'s rule.
            try {
                fromJson(Json.parse(line))
            } catch (e: Exception) {
                null
            }
        }.toList()

    fun append(file: File, notes: List<Note>) {
        if (notes.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(toJsonl(notes))
    }

    fun read(file: File): List<Note> =
        if (file.isFile) fromJsonl(file.readText()) else emptyList()

    /** The paste-ready line for `docs/BENCH.md`: `→ 2026-09-19 2107 · PLAY · Break Kit · A03: what was heard`. */
    fun render(note: Note): String = "→ ${note.at}$SEPARATOR${note.context}: ${note.text}"

    /** Every note as a [render] line, in the order they were taken. */
    fun renderAll(notes: List<Note>): String = notes.joinToString("") { render(it) + "\n" }

    private fun toJson(n: Note): JsonValue {
        val entries = linkedMapOf<String, JsonValue>(
            "at" to JsonValue.Str(n.at),
            "screen" to JsonValue.Str(n.screen),
        )
        n.kit?.let { entries["kit"] = JsonValue.Str(it) }
        n.pad?.let { entries["pad"] = JsonValue.Str(it) }
        entries["text"] = JsonValue.Str(n.text)
        return JsonValue.Obj(entries)
    }

    private fun fromJson(v: JsonValue): Note {
        val o = (v as JsonValue.Obj).entries
        fun str(k: String) = (o[k] as JsonValue.Str).value
        fun strOrNull(k: String) = (o[k] as? JsonValue.Str)?.value
        return Note(at = str("at"), screen = str("screen"), kit = strOrNull("kit"), pad = strOrNull("pad"), text = str("text"))
    }
}
