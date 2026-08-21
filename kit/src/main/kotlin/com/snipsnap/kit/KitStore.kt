package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * Reads and writes the `kit.json` sidecar that makes a folder a kit.
 *
 * Forward compatibility: unknown fields are ignored on load (and therefore
 * dropped on the next save); an unknown *version* is refused outright, since
 * silently misreading someone's kit is worse than asking them to update.
 */
object KitStore {

    const val FILE_NAME = "kit.json"
    const val VERSION = 1

    fun save(kit: Kit, dir: File): File {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        file.writeText(Json.write(toJson(kit)) + "\n", Charsets.UTF_8)
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): Kit {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    /** Subdirectories of [root] that are kits, sorted by name. */
    fun list(root: File): List<File> =
        root.listFiles { f: File -> f.isDirectory && File(f, FILE_NAME).isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun toJson(kit: Kit): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "name" to JsonValue.Str(kit.name),
            "pads" to JsonValue.Arr(
                kit.pads.sortedBy { it.slot }.map { p ->
                    val entries = linkedMapOf<String, JsonValue>(
                        "slot" to JsonValue.Num(p.slot.toDouble()),
                        "sample" to JsonValue.Str(p.sampleFile),
                        "name" to JsonValue.Str(p.displayName),
                        "class" to JsonValue.Str(p.drumClass.name),
                        "level" to JsonValue.Num(p.level.toDouble()),
                        "pan" to JsonValue.Num(p.pan.toDouble()),
                        "tuneCoarse" to JsonValue.Num(p.tuneCoarse.toDouble()),
                        "tuneFine" to JsonValue.Num(p.tuneFine.toDouble()),
                        "muteGroup" to JsonValue.Num(p.muteGroup.toDouble()),
                        "oneShot" to JsonValue.Bool(p.oneShot),
                    )
                    p.colorHex?.let { entries["colorHex"] = JsonValue.Str(it) }
                    if (p.source.isNotEmpty()) {
                        entries["source"] = JsonValue.Obj(
                            p.source.entries.associateTo(LinkedHashMap()) { (k, v) ->
                                k to JsonValue.Str(v)
                            },
                        )
                    }
                    p.recipe?.let { entries["recipe"] = it }
                    if (p.velocityLayers.isNotEmpty()) {
                        entries["layers"] = JsonValue.Arr(
                            p.velocityLayers.map { l ->
                                JsonValue.Obj(
                                    linkedMapOf(
                                        "sample" to JsonValue.Str(l.sampleFile),
                                        "velStart" to JsonValue.Num(l.velStart.toDouble()),
                                        "velEnd" to JsonValue.Num(l.velEnd.toDouble()),
                                    ),
                                )
                            },
                        )
                    }
                    JsonValue.Obj(entries)
                },
            ),
        ),
    )

    private fun fromJson(root: JsonValue): Kit {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("kit.json has no version")
        if (version != VERSION) {
            throw JsonException("kit.json version $version is not supported (this build reads $VERSION)")
        }
        val name = obj["name"]?.str() ?: throw JsonException("kit.json has no name")
        val pads = obj["pads"]?.arr().orEmpty().map { padJson ->
            val p = padJson.obj()
            KitPad(
                slot = p["slot"]?.int() ?: throw JsonException("pad has no slot"),
                sampleFile = p["sample"]?.str() ?: throw JsonException("pad has no sample"),
                displayName = p["name"]?.str()
                    ?: (p["sample"]!!.str().substringBeforeLast('.')),
                drumClass = p["class"]?.str()?.let { cls ->
                    DrumClass.entries.firstOrNull { it.name == cls } ?: DrumClass.UNKNOWN
                } ?: DrumClass.UNKNOWN,
                colorHex = (p["colorHex"] as? JsonValue.Str)?.value,
                level = p["level"]?.num()?.toFloat() ?: 0.707946f,
                pan = p["pan"]?.num()?.toFloat() ?: 0.5f,
                tuneCoarse = p["tuneCoarse"]?.int() ?: 0,
                tuneFine = p["tuneFine"]?.int() ?: 0,
                muteGroup = p["muteGroup"]?.int() ?: 0,
                oneShot = p["oneShot"]?.bool() ?: true,
                source = (p["source"] as? JsonValue.Obj)?.entries
                    ?.mapValues { (_, v) -> v.str() }
                    ?: emptyMap(),
                // Verbatim, no interpretation: a recipe written by a newer
                // build (or another engine) must survive a load-save cycle
                // here untouched.
                recipe = p["recipe"] as? JsonValue.Obj,
                velocityLayers = (p["layers"] as? JsonValue.Arr)?.items.orEmpty().map { layerJson ->
                    val l = layerJson.obj()
                    KitLayer(
                        sampleFile = l["sample"]?.str() ?: throw JsonException("layer has no sample"),
                        velStart = l["velStart"]?.int() ?: throw JsonException("layer has no velStart"),
                        velEnd = l["velEnd"]?.int() ?: throw JsonException("layer has no velEnd"),
                    )
                },
            )
        }
        return Kit(name, pads)
    }
}
