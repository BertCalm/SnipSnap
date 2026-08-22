package com.snipsnap.mpc3

import com.snipsnap.json.JsonValue
import java.io.File

/**
 * Light, tolerant accessors over an MPC 3 payload — a project (`.xpj`) or a
 * standalone track (`.xtd` / `.xty`).
 *
 * Everything is nullable and nothing throws on absence: the raw [JsonValue]
 * tree is always reachable for anything these accessors don't cover.
 *
 * The field paths were originally taken from a community `.xpj` write-up and
 * are now checked against `reference/golden/` — 59 real projects and 13 real
 * track files. Several of that write-up's claims were wrong and are corrected
 * in `docs/MPC3_FORMAT.md`; the ones this class depends on survived.
 */
class Mpc3Project(val container: AcvsContainer) {

    companion object {
        /** Header line 3 for a project — one or more tracks under `data.tracks`. */
        const val PROJECT_OBJECT_TYPE = "SerialisableProjectData"

        /** Header line 3 for a standalone program: `.xtd` (drum), `.xty` (instrument). */
        const val TRACK_OBJECT_TYPE = "SerialisableTrackData"

        fun read(file: File): Mpc3Project = Mpc3Project(Acvs.read(file))

        fun read(bytes: ByteArray): Mpc3Project = Mpc3Project(Acvs.read(bytes))
    }

    val firmware: String get() = container.header.firmware

    val isProject: Boolean get() = container.header.objectType == PROJECT_OBJECT_TYPE

    val isTrack: Boolean get() = container.header.objectType == TRACK_OBJECT_TYPE

    /** The `data` object all content lives under; null when absent. */
    val data: Map<String, JsonValue>? =
        ((container.payload as? JsonValue.Obj)?.entries?.get("data") as? JsonValue.Obj)?.entries

    /**
     * `data.version` — 5 on every harvested file, project and track alike.
     *
     * Do not gate on it, or on any other version integer: one Classic Drum
     * Machines pack ships `program.version` 4 beside 2, and `drumVersion`
     * present beside absent. Akai's own content fails a strict schema check.
     */
    val schemaVersion: Int? = (data?.get("version") as? JsonValue.Num)?.value?.toInt()

    /**
     * The track objects, whichever container this is.
     *
     * A project nests them under `data.tracks`. A standalone `.xtd`/`.xty` has
     * no `tracks` key at all — `data` *is* the track, carrying the same name,
     * colour, `samples` pool, clips and `program` that a project's array
     * element does. So a track file reads as a one-track list, which is what
     * it structurally is: one element of `tracks[]` hoisted to the top.
     *
     * Detected by shape rather than by header line 3, so a file with an
     * unexpected object type still reads if it looks like a track.
     */
    val tracks: List<Map<String, JsonValue>> =
        (data?.get("tracks") as? JsonValue.Arr)?.items
            ?.mapNotNull { (it as? JsonValue.Obj)?.entries }
            ?: data?.takeIf { it["program"] is JsonValue.Obj }?.let { listOf(it) }
            ?: emptyList()

    val trackNames: List<String> =
        tracks.mapNotNull { (it["name"] as? JsonValue.Str)?.value }

    /** Every track's `program` object, drum and instrument alike. */
    fun programs(): List<Map<String, JsonValue>> =
        tracks.mapNotNull { (it["program"] as? JsonValue.Obj)?.entries }

    /**
     * The `program.drum` objects — where instrument slots, layers and
     * `padGroup` live (docs/MPC3_FORMAT.md has the schema).
     *
     * Present on **both** program types, and not only on drum kits: a keygroup
     * program keeps its zones in `program.drum.instruments` too, with
     * `program.keygroup` holding only global synth state. Filter on
     * `program.type` — `0` drum, `1` keygroup — rather than on this being
     * present.
     *
     * Note `padNoteMap` is a sibling of `drum` on `program`, not inside it.
     */
    fun drumPrograms(): List<Map<String, JsonValue>> =
        programs().mapNotNull { (it["drum"] as? JsonValue.Obj)?.entries }

    /**
     * One-screen summary for "send me the file and I'll tell you what it is".
     */
    fun describe(): String = buildString {
        append("ACVS ").append(container.header.objectType)
        append(" · firmware ").append(firmware)
        append(" · ").append(container.header.platform)
        schemaVersion?.let { append(" · schema v").append(it) }
        if (tracks.isNotEmpty()) {
            append(" · ").append(tracks.size)
            append(if (tracks.size == 1) " track" else " tracks")
            trackNames.firstOrNull()?.takeIf { tracks.size == 1 }?.let { append(" \"").append(it).append('"') }
            programs().mapNotNull { (it["type"] as? JsonValue.Num)?.value?.toInt() }
                .takeIf { it.isNotEmpty() }
                ?.let { types ->
                    append(" (").append(types.count { it == 0 }).append(" drum")
                    types.count { it == 1 }.takeIf { it > 0 }?.let { append(", ").append(it).append(" keygroup") }
                    append(')')
                }
        }
    }
}
