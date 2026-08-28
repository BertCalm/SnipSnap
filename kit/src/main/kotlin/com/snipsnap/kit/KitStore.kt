package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
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
        AtomicFile.writeText(file, Json.write(toJson(kit)) + "\n")
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): Kit {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return read(file)
    }

    /** Read any kit-json file — `kit.json` itself, or an archived take. */
    @Throws(IOException::class)
    fun read(file: File): Kit {
        if (!file.isFile) throw IOException("no such file: $file")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    /** Subdirectories of [root] that are kits, sorted by name. */
    fun list(root: File): List<File> =
        root.listFiles { f: File -> f.isDirectory && File(f, FILE_NAME).isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun toJson(kit: Kit): JsonValue = JsonValue.Obj(
        linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "name" to JsonValue.Str(kit.name),
        ).also { root ->
            kit.key?.let {
                root["key"] = JsonValue.Obj(
                    linkedMapOf(
                        "root" to JsonValue.Num(it.rootSemitone.toDouble()),
                        "scale" to JsonValue.Str(it.scale.name),
                    ),
                )
            }
            kit.tempoBpm?.let { root["tempoBpm"] = JsonValue.Num(it.toDouble()) }
            kit.wear?.let {
                // The ledger only - w is derived from it, never stored.
                root["wear"] = JsonValue.Obj(
                    linkedMapOf(
                        "mileage" to JsonValue.Num(it.mileage),
                        "enabled" to JsonValue.Bool(it.enabled),
                        "k" to JsonValue.Num(it.k),
                    ),
                )
            }
            root["pads"] = JsonValue.Arr(
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
                    // The shape rides only when set - null means "the
                    // format's default", and defaults are never written down.
                    p.attack?.let { entries["attack"] = JsonValue.Num(it.toDouble()) }
                    p.decay?.let { entries["decay"] = JsonValue.Num(it.toDouble()) }
                    p.cutoff?.let { entries["cutoff"] = JsonValue.Num(it.toDouble()) }
                    p.resonance?.let { entries["resonance"] = JsonValue.Num(it.toDouble()) }
                    p.humanize?.let { entries["humanize"] = JsonValue.Num(it.toDouble()) }
                    p.chain?.let { c ->
                        val chain = linkedMapOf<String, JsonValue>(
                            "cycle" to JsonValue.Num(c.cycle.toDouble()),
                            "boundaries" to JsonValue.Arr(c.boundaries.map { JsonValue.Num(it.toDouble()) }),
                        )
                        c.zones?.let { zs ->
                            chain["zones"] = JsonValue.Arr(
                                zs.map { z ->
                                    JsonValue.Obj(
                                        linkedMapOf(
                                            "velStart" to JsonValue.Num(z.velStart.toDouble()),
                                            "velEnd" to JsonValue.Num(z.velEnd.toDouble()),
                                            "baseSlice" to JsonValue.Num(z.baseSlice.toDouble()),
                                            "cycle" to JsonValue.Num(z.cycle.toDouble()),
                                        ),
                                    )
                                },
                            )
                        }
                        entries["chain"] = JsonValue.Obj(chain)
                    }
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
            )
        },
    )

    private fun fromJson(root: JsonValue): Kit {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("kit.json has no version")
        if (version != VERSION) {
            throw JsonException("kit.json version $version is not supported (this build reads $VERSION)")
        }
        val name = obj["name"]?.str() ?: throw JsonException("kit.json has no name")
        val key = (obj["key"] as? JsonValue.Obj)?.let { k ->
            KeySpec(
                rootSemitone = k.entries["root"]?.int() ?: throw JsonException("key has no root"),
                scale = k.entries["scale"]?.str()?.let { s ->
                    Scale.entries.firstOrNull { it.name == s }
                        ?: throw JsonException("unknown scale '$s' in kit.json key")
                } ?: throw JsonException("key has no scale"),
            )
        }
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
                attack = p["attack"]?.num()?.toFloat(),
                decay = p["decay"]?.num()?.toFloat(),
                cutoff = p["cutoff"]?.num()?.toFloat(),
                resonance = p["resonance"]?.num()?.toFloat(),
                humanize = p["humanize"]?.num()?.toFloat(),
                chain = (p["chain"] as? JsonValue.Obj)?.let { c ->
                    ChainInfo(
                        boundaries = ((c.entries["boundaries"] as? JsonValue.Arr)?.items.orEmpty())
                            .map { (it as JsonValue.Num).value.toLong() },
                        cycle = c.entries["cycle"]?.int() ?: 2,
                        zones = (c.entries["zones"] as? JsonValue.Arr)?.items?.map { zoneJson ->
                            val z = zoneJson.obj()
                            ChainZone(
                                velStart = z["velStart"]?.int() ?: throw JsonException("zone has no velStart"),
                                velEnd = z["velEnd"]?.int() ?: throw JsonException("zone has no velEnd"),
                                baseSlice = z["baseSlice"]?.int() ?: throw JsonException("zone has no baseSlice"),
                                cycle = z["cycle"]?.int() ?: throw JsonException("zone has no cycle"),
                            )
                        },
                    )
                },
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
        val wear = (obj["wear"] as? JsonValue.Obj)?.let { w ->
            WearLedger(
                mileage = w.entries["mileage"]?.num() ?: 0.0,
                enabled = w.entries["enabled"]?.bool() ?: true,
                k = w.entries["k"]?.num() ?: WearLedger.DEFAULT_K,
            )
        }
        return Kit(name, pads, key, tempoBpm = obj["tempoBpm"]?.num()?.toFloat(), wear = wear)
    }
}
