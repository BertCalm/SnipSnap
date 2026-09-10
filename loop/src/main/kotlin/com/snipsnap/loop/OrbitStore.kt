package com.snipsnap.loop

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * A set of rings on disk: `orbits.json`, a sidecar in the folder whose kit
 * and snips it names — the shape `loop.json` and `groove.json` already use,
 * for the same reason: every reference is a bare filename resolved against
 * that folder, so the folder still plays after a copy or a share.
 */
object OrbitStore {

    const val FILE_NAME = "orbits.json"

    /**
     * 3: a ring has a `span` (FREE, HALF, ONE, TWO, FOUR) where 2 had a
     * boolean `lockToBar` and 1 had a `mode`. Every older file still loads:
     * `lockToBar: true` and `SAME_LAP` both become `ONE`, and a version 1
     * ring's voice is the pads its hits already named.
     */
    const val VERSION = 3
    private const val FIRST_VERSION = 1

    fun save(set: OrbitSet, dir: File): File {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        file.writeText(Json.write(toJson(set)) + "\n", Charsets.UTF_8)
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): OrbitSet {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    fun exists(dir: File): Boolean = File(dir, FILE_NAME).isFile

    private fun num(v: Number) = JsonValue.Num(v.toDouble())

    private fun toJson(set: OrbitSet): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to num(VERSION),
            "bpm" to num(set.bpm),
            "lapSteps" to num(set.lapSteps),
            "sampleRate" to num(set.sampleRate),
            "orbits" to JsonValue.Arr(set.orbits.map { orbitJson(it) }),
        ),
    )

    private fun orbitJson(orbit: Orbit): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(orbit.name),
            "steps" to num(orbit.steps),
            "span" to JsonValue.Str(orbit.span.name),
            "voice" to JsonValue.Arr(orbit.pads.map { num(it) }),
            "engaged" to JsonValue.Bool(orbit.engaged),
            "level" to num(orbit.level),
            "pan" to num(orbit.pan),
            "content" to contentJson(orbit.content),
        ),
    )

    private fun contentJson(content: OrbitContent): JsonValue = when (content) {
        is SnipOrbit -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("snip"),
                "sampleFile" to JsonValue.Str(content.sampleFile),
            ),
        )
        is PatternOrbit -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("pattern"),
                "kit" to JsonValue.Str(content.kit),
                "hits" to JsonValue.Arr(
                    content.hits.map {
                        JsonValue.Obj(
                            linkedMapOf(
                                "step" to num(it.step),
                                "slot" to num(it.slot),
                                "velocity" to num(it.velocity),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun fromJson(root: JsonValue): OrbitSet {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw IllegalStateException("$FILE_NAME has no version")
        check(version in FIRST_VERSION..VERSION) { "$FILE_NAME is version $version, this build reads $FIRST_VERSION..$VERSION" }
        return OrbitSet(
            orbits = obj["orbits"]?.arr().orEmpty().map { orbitFrom(it) },
            bpm = (obj["bpm"]?.num() ?: 90.0).toFloat(),
            sampleRate = obj["sampleRate"]?.int() ?: 48_000,
            lapSteps = obj["lapSteps"]?.int() ?: OrbitSet.DEFAULT_LAP_STEPS,
        )
    }

    private fun orbitFrom(value: JsonValue): Orbit {
        val o = value.obj()
        // Version 3 writes a span name. Version 2 wrote lockToBar; version 1
        // a mode name. Both older forms only ever meant "one bar" or "free".
        val span = when {
            o["span"] != null -> OrbitSpan.fromName(o["span"]?.str())
            o["lockToBar"] != null -> if (o["lockToBar"]?.bool() == true) OrbitSpan.ONE else OrbitSpan.FREE
            else -> when (val mode = o["mode"]?.str()) {
                null, "SAME_SPEED" -> OrbitSpan.FREE
                "SAME_LAP" -> OrbitSpan.ONE
                else -> throw IllegalStateException("unknown orbit mode '$mode'")
            }
        }
        val content = contentFrom(o["content"] ?: throw IllegalStateException("orbit has no content"))
        // A snip ring has no voice; a version 1 pattern ring's voice is derived from its hits.
        val voice = if (content is SnipOrbit) emptyList() else o["voice"]?.arr().orEmpty().map { it.int() }
        return Orbit(
            name = o["name"]?.str() ?: "",
            steps = o["steps"]?.int() ?: OrbitSet.DEFAULT_LAP_STEPS,
            content = content,
            span = span,
            voice = voice,
            engaged = o["engaged"]?.bool() ?: true,
            level = (o["level"]?.num() ?: 1.0).toFloat(),
            pan = (o["pan"]?.num() ?: 0.0).toFloat(),
        )
    }

    private fun contentFrom(value: JsonValue): OrbitContent {
        val c = value.obj()
        return when (val type = c["type"]?.str()) {
            "snip" -> SnipOrbit(c["sampleFile"]?.str() ?: throw IllegalStateException("snip orbit has no sampleFile"))
            "pattern" -> PatternOrbit(
                kit = c["kit"]?.str() ?: throw IllegalStateException("pattern orbit has no kit"),
                hits = c["hits"]?.arr().orEmpty().map { hitFrom(it) },
            )
            else -> throw IllegalStateException("unknown orbit content type '$type'")
        }
    }

    private fun hitFrom(value: JsonValue): OrbitHit {
        val h = value.obj()
        return OrbitHit(
            step = h["step"]?.int() ?: 0,
            slot = h["slot"]?.int() ?: 1,
            velocity = (h["velocity"]?.num() ?: 1.0).toFloat(),
        )
    }
}
