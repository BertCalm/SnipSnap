package com.snipsnap.mpc3

import com.snipsnap.json.JsonValue
import java.io.File

/**
 * Light, tolerant accessors over an MPC 3 project payload.
 *
 * Field paths come from the community `.xpj` knowledge base and have NOT been
 * verified against our own hardware yet, so everything here is nullable and
 * nothing throws on absence: this class exists to *dissect* real files the
 * moment one lands, not to promise a schema. The raw [JsonValue] tree is
 * always reachable for anything these accessors don't cover.
 */
class Mpc3Project(val container: AcvsContainer) {

    companion object {
        /** Object type line 3 carries for a project, per the knowledge base. */
        const val PROJECT_OBJECT_TYPE = "SerialisableProjectData"

        fun read(file: File): Mpc3Project = Mpc3Project(Acvs.read(file))

        fun read(bytes: ByteArray): Mpc3Project = Mpc3Project(Acvs.read(bytes))
    }

    val firmware: String get() = container.header.firmware

    val isProject: Boolean get() = container.header.objectType == PROJECT_OBJECT_TYPE

    /** The `data` object all project content lives under; null when absent. */
    val data: Map<String, JsonValue>? =
        ((container.payload as? JsonValue.Obj)?.entries?.get("data") as? JsonValue.Obj)?.entries

    /** `data.version` — the schema version (28 in the analysed corpus). */
    val schemaVersion: Int? = (data?.get("version") as? JsonValue.Num)?.value?.toInt()

    val tracks: List<Map<String, JsonValue>> =
        (data?.get("tracks") as? JsonValue.Arr)?.items
            ?.mapNotNull { (it as? JsonValue.Obj)?.entries }
            ?: emptyList()

    val trackNames: List<String> =
        tracks.mapNotNull { (it["name"] as? JsonValue.Str)?.value }

    /**
     * The `program.drum` objects of drum tracks — where pads, layers and the
     * pad note map live (docs/MPC3_FORMAT.md has the schema).
     */
    fun drumPrograms(): List<Map<String, JsonValue>> =
        tracks.mapNotNull { track ->
            val program = (track["program"] as? JsonValue.Obj)?.entries ?: return@mapNotNull null
            (program["drum"] as? JsonValue.Obj)?.entries
        }

    /**
     * One-screen summary for "send me the file and I'll tell you what it is".
     */
    fun describe(): String = buildString {
        append("ACVS ").append(container.header.objectType)
        append(" · firmware ").append(firmware)
        append(" · ").append(container.header.platform)
        schemaVersion?.let { append(" · schema v").append(it) }
        if (tracks.isNotEmpty()) {
            append(" · ").append(tracks.size).append(" tracks")
            append(" (").append(drumPrograms().size).append(" drum)")
        }
    }
}
