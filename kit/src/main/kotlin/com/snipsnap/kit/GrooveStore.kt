package com.snipsnap.kit

import com.snipsnap.json.Json
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
        val obj = (root as JsonValue.Obj).entries
        val version = (obj["version"] as? JsonValue.Num)?.value?.toInt()
        require(version == VERSION) { "groove.json version $version is not supported (this build reads $VERSION)" }
        return ((obj["clips"] as? JsonValue.Arr)?.items.orEmpty()).map { clipFromJson(it) }
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

    internal fun clipFromJson(clipJson: JsonValue): Mpc3Clip {
        val c = (clipJson as JsonValue.Obj).entries
        return Mpc3Clip(
            name = (c["name"] as JsonValue.Str).value,
            bars = (c["bars"] as JsonValue.Num).value.toInt(),
            notes = ((c["notes"] as? JsonValue.Arr)?.items.orEmpty()).map { noteJson ->
                val n = (noteJson as JsonValue.Obj).entries
                Mpc3Note(
                    note = (n["note"] as JsonValue.Num).value.toInt(),
                    timePulses = (n["timePulses"] as JsonValue.Num).value.toLong(),
                    velocity = (n["velocity"] as JsonValue.Num).value.toFloat(),
                    lengthPulses = (n["lengthPulses"] as JsonValue.Num).value.toLong(),
                )
            },
        )
    }
}
