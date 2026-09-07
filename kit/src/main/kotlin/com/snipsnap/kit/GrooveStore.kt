package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File

/**
 * The kit's grooves, persisted — `groove.json` beside `kit.json`, closing
 * the leak where a captured rhythm existed only at export time. Chop
 * writes it, importers refill it from the clips they find, and every
 * native export embeds it without being asked.
 *
 * Holds a *list* of clips because the native container does (commercial
 * kits ship four: Chorus / Verse / Intro / Bridge — corpus-verified);
 * pattern variations land in the same file.
 *
 * This store is a clip shelf, not the GROOVE screen's program cache: the
 * screen's A–D are pure functions of the captured clip, recomputed live and
 * never written here (design/HANDOFF.md). What lands in `groove.json` is
 * whatever CLI verbs (Chop/Import/Learn/Euclid/Feel) deliberately choose to
 * persist as artifacts, plus PROG E via [GrooveEdit] — both are correct,
 * independently, at once.
 */
object GrooveStore {

    const val FILE_NAME = "groove.json"
    const val VERSION = 1

    fun save(kitDir: File, clips: List<Mpc3Clip>): File {
        require(clips.isNotEmpty()) { "no clips - use delete() to clear the grooves" }
        kitDir.mkdirs()
        val file = File(kitDir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(clips)) + "\n")
        return file
    }

    /** The kit's grooves, oldest promise first; empty when none saved. */
    fun load(kitDir: File): List<Mpc3Clip> {
        val file = File(kitDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    fun delete(kitDir: File): Boolean = File(kitDir, FILE_NAME).delete()

    private fun toJson(clips: List<Mpc3Clip>): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "clips" to JsonValue.Arr(clips.map { clipToJson(it) }),
        ),
    )

    private fun fromJson(root: JsonValue): List<Mpc3Clip> {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("groove.json has no version")
        if (version != VERSION) {
            throw JsonException("groove.json version $version is not supported (this build reads $VERSION)")
        }
        return obj["clips"]?.arr().orEmpty().map { clipFromJson(it) }
    }

    /** The clip codec, shared with every other sidecar that persists one. */
    internal fun clipToJson(clip: Mpc3Clip): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(clip.name),
            "bars" to JsonValue.Num(clip.bars.toDouble()),
            "notes" to JsonValue.Arr(
                clip.notes.map { n ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "note" to JsonValue.Num(n.note.toDouble()),
                            "timePulses" to JsonValue.Num(n.timePulses.toDouble()),
                            "velocity" to JsonValue.Num(n.velocity.toDouble()),
                            "lengthPulses" to JsonValue.Num(n.lengthPulses.toDouble()),
                        ),
                    )
                },
            ),
        ),
    )

    /**
     * Typed accessors throughout: a clip written by another tool, or torn
     * mid-write, refuses as a [JsonException] in words - never a cast that
     * fails or a null that is not there (the hardening contract, BBB4).
     */
    internal fun clipFromJson(clipJson: JsonValue): Mpc3Clip {
        val c = clipJson.obj()
        return Mpc3Clip(
            name = c["name"]?.str() ?: throw JsonException("clip has no name"),
            bars = c["bars"]?.int() ?: throw JsonException("clip has no bars"),
            notes = c["notes"]?.arr().orEmpty().map { noteJson ->
                val n = noteJson.obj()
                Mpc3Note(
                    note = n["note"]?.int() ?: throw JsonException("note has no note"),
                    timePulses = n["timePulses"]?.num()?.toLong() ?: throw JsonException("note has no timePulses"),
                    velocity = n["velocity"]?.num()?.toFloat() ?: throw JsonException("note has no velocity"),
                    lengthPulses = n["lengthPulses"]?.num()?.toLong() ?: throw JsonException("note has no lengthPulses"),
                )
            },
        )
    }
}
