package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File

/**
 * `surface.json` beside `kit.json`: what the SURFACE plays from this kit
 * and the four corner states its MORPH pad blends between. Small, typed,
 * and under the same rules as every sidecar - unknown fields ignored,
 * unknown versions refused, a torn file a [JsonException] in words.
 *
 * The corners are the same four macros the engine runs (pitch, cutoff,
 * resonance, drive, each 0..1), so a corner *is* a position on the pad:
 * [Corner.from] turns the current reading in any mode into one, which is
 * how SET A..D captures the sound under the finger.
 */
object SurfaceStore {

    const val FILE_NAME = "surface.json"
    const val VERSION = 1

    data class Corner(val pitch: Float, val cutoff: Float, val resonance: Float, val drive: Float) {
        init {
            for ((name, v) in listOf("pitch" to pitch, "cutoff" to cutoff, "resonance" to resonance, "drive" to drive)) {
                require(v.isFinite() && v in 0f..1f) { "$name is 0..1, got $v" }
            }
        }

        companion object {
            /** The engine's own defaults: A clean, B dark, C low and thick, D hot. */
            val CLEAN = Corner(0.5f, 1.0f, 0.0f, 0.0f)
            val DARK = Corner(0.5f, 0.25f, 0.3f, 0.1f)
            val LOW = Corner(0.25f, 0.6f, 0.5f, 0.4f)
            val HOT = Corner(0.75f, 0.85f, 0.2f, 0.9f)
            val DEFAULTS: List<Corner> = listOf(CLEAN, DARK, LOW, HOT)

            /**
             * The macro state under the finger, by the same map the engine
             * applies: XY is pitch across, cutoff up, half the roll as
             * resonance; XYZ adds the pinch as drive and the whole roll as
             * resonance; MORPH is the weighted blend of [corners].
             */
            fun from(mode: TouchSurface.Mode, reading: TouchSurface.Reading, tilt: Float, corners: List<Corner>): Corner {
                val t = tilt.coerceIn(0f, 1f)
                return when (mode) {
                    TouchSurface.Mode.XY -> Corner(reading.x, reading.y, t * 0.5f, 0f)
                    TouchSurface.Mode.XYZ -> Corner(reading.x, reading.y, t, reading.z)
                    TouchSurface.Mode.MORPH -> {
                        require(corners.size == 4) { "a morph blends four corners, got ${corners.size}" }
                        val w = listOf(reading.a, reading.b, reading.c, reading.d)
                        fun blend(pick: (Corner) -> Float) =
                            corners.indices.sumOf { (w[it] * pick(corners[it])).toDouble() }.toFloat().coerceIn(0f, 1f)
                        Corner(blend { it.pitch }, blend { it.cutoff }, blend { it.resonance }, blend { it.drive })
                    }
                }
            }
        }
    }

    data class Settings(
        /** The pad the surface plays, by slot; null = the kit's lowest. */
        val padSlot: Int?,
        val corners: List<Corner> = Corner.DEFAULTS,
    ) {
        init {
            require(corners.size == 4) { "four corners, got ${corners.size}" }
            padSlot?.let { require(it in 1..128) { "slot out of range: $it" } }
        }

        companion object {
            val DEFAULT = Settings(padSlot = null)
        }
    }

    fun save(kitDir: File, settings: Settings): File {
        kitDir.mkdirs()
        val file = File(kitDir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(settings)) + "\n")
        return file
    }

    /** The kit's surface settings; the defaults when it has none. */
    fun load(kitDir: File): Settings {
        val file = File(kitDir, FILE_NAME)
        if (!file.isFile) return Settings.DEFAULT
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    private fun toJson(s: Settings): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "pad" to (s.padSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "corners" to JsonValue.Arr(
                s.corners.map { c ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "pitch" to JsonValue.Num(c.pitch.toDouble()),
                            "cutoff" to JsonValue.Num(c.cutoff.toDouble()),
                            "resonance" to JsonValue.Num(c.resonance.toDouble()),
                            "drive" to JsonValue.Num(c.drive.toDouble()),
                        ),
                    )
                },
            ),
        ),
    )

    private fun fromJson(root: JsonValue): Settings {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("surface.json has no version")
        if (version != VERSION) {
            throw JsonException("surface.json version $version is not supported (this build reads $VERSION)")
        }
        val pad = obj["pad"]?.takeUnless { it is JsonValue.Null }?.int()
        val corners = obj["corners"]?.arr()?.map { c ->
            val o = c.obj()
            fun macro(name: String) = o[name]?.num()?.toFloat() ?: throw JsonException("corner has no $name")
            Corner(macro("pitch"), macro("cutoff"), macro("resonance"), macro("drive"))
        } ?: Corner.DEFAULTS
        if (corners.size != 4) throw JsonException("surface.json has ${corners.size} corners, not 4")
        return Settings(pad, corners)
    }
}
