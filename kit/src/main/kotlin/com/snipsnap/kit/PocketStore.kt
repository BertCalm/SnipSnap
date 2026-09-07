package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * `.pocket` files (HH4) — a [GrooveFeel.Template] as a tradeable
 * artifact: sixteen timing offsets, sixteen accents, a name. The feel a
 * drummer leaves in a groove, small enough to hand around like a preset
 * and honest about coverage — a position the donor never played is
 * `null` in the file exactly as it is in the template.
 *
 * Same store rules as every sidecar: unknown fields ignored on load,
 * unknown versions refused outright.
 */
object PocketStore {

    const val VERSION = 1
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
        if (version != VERSION) {
            throw JsonException("pocket version $version is not supported (this build reads $VERSION)")
        }
        val name = obj["name"]?.str() ?: throw JsonException("pocket has no name")
        fun arr(key: String): List<JsonValue> =
            (obj[key] as? JsonValue.Arr)?.items ?: throw JsonException("pocket has no $key")
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
            ),
        )
    }
}
