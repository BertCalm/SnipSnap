package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * `.pocket` files (HH4) — a [GrooveFeel.Template] as a tradeable
 * artifact: sixteen timing offsets, sixteen accents, a per-lane push/drag
 * layer, a name. The feel a drummer leaves in a groove, small enough to
 * hand around like a preset and honest about coverage — a position the
 * donor never played is `null` in the file exactly as it is in the
 * template, and a lane the donor never leaned is simply absent from the
 * lane layer.
 *
 * Same store rules as every sidecar: unknown fields ignored on load,
 * unknown versions refused outright — except that v1, which predates the
 * lane layer, still reads: [VERSION] moved to 2 the day the lane layer
 * was added, but no `.pocket` written by v1 has a reason to become
 * unreadable, so both versions stay in [READABLE] and a v1 file just
 * comes back with an empty lane layer.
 */
object PocketStore {

    /** Bumped to 2 when [GrooveFeel.Template] gained its lane layer. v1 files still read — see [read]. */
    const val VERSION = 2

    /** Versions this build can read. v1 predates the lane layer and reads with it empty. */
    private val READABLE = setOf(1, 2)

    const val EXTENSION = "pocket"

    data class Pocket(val name: String, val template: GrooveFeel.Template) {
        init {
            require(name.isNotBlank()) { "a pocket has a name" }
        }
    }

    fun save(pocket: Pocket, file: File): File {
        val json = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(pocket.name),
                "offsets" to JsonValue.Arr(
                    pocket.template.offsets.map { o ->
                        o?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
                    },
                ),
                "accents" to JsonValue.Arr(
                    pocket.template.accents.map { a ->
                        a?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
                    },
                ),
                "laneOffsets" to JsonValue.Obj(
                    LinkedHashMap<String, JsonValue>().apply {
                        pocket.template.laneOffsets.forEach { (lane, pulses) -> put(lane.name, JsonValue.Num(pulses.toDouble())) }
                    },
                ),
                "laneAccents" to JsonValue.Obj(
                    LinkedHashMap<String, JsonValue>().apply {
                        pocket.template.laneAccents.forEach { (lane, scale) -> put(lane.name, JsonValue.Num(scale.toDouble())) }
                    },
                ),
            ),
        )
        file.parentFile?.mkdirs()
        AtomicFile.writeText(file, Json.write(json) + "\n")
        return file
    }

    @Throws(IOException::class)
    fun read(file: File): Pocket {
        if (!file.isFile) throw IOException("no such pocket: $file")
        val obj = Json.parse(file.readText(Charsets.UTF_8)).obj()
        val version = obj["version"]?.int() ?: throw JsonException("pocket has no version")
        if (version !in READABLE) {
            throw JsonException("pocket version $version is not supported (this build reads ${READABLE.sorted()})")
        }
        val name = obj["name"]?.str() ?: throw JsonException("pocket has no name")
        fun arr(key: String): List<JsonValue> =
            (obj[key] as? JsonValue.Arr)?.items ?: throw JsonException("pocket has no $key")

        // A lane name this build does not know is an unknown field, not a bad
        // file — ignored, per this store's own rule. v1 files have neither
        // object at all, which reads as "no lane layer", exactly right.
        fun laneLongs(key: String): Map<GrooveEdit.Lane, Long> =
            (obj[key] as? JsonValue.Obj)?.entries.orEmpty().mapNotNull { (k, v) ->
                val lane = GrooveEdit.Lane.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                val num = (v as? JsonValue.Num)?.value ?: throw JsonException("$key carries numbers")
                lane to num.toLong()
            }.toMap()

        fun laneFloats(key: String): Map<GrooveEdit.Lane, Float> =
            (obj[key] as? JsonValue.Obj)?.entries.orEmpty().mapNotNull { (k, v) ->
                val lane = GrooveEdit.Lane.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                val num = (v as? JsonValue.Num)?.value ?: throw JsonException("$key carries numbers")
                lane to num.toFloat()
            }.toMap()

        return Pocket(
            name = name,
            template = GrooveFeel.Template(
                offsets = arr("offsets").map { v ->
                    if (v is JsonValue.Null) null else (v as? JsonValue.Num)?.value?.toLong()
                        ?: throw JsonException("offsets carry numbers or nulls")
                },
                accents = arr("accents").map { v ->
                    if (v is JsonValue.Null) null else (v as? JsonValue.Num)?.value?.toFloat()
                        ?: throw JsonException("accents carry numbers or nulls")
                },
                laneOffsets = laneLongs("laneOffsets"),
                laneAccents = laneFloats("laneAccents"),
            ),
        )
    }
}
