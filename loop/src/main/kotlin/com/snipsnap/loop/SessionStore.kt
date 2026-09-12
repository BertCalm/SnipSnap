package com.snipsnap.loop

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File
import java.io.IOException

/**
 * A session on disk: a folder of audio plus a JSON sidecar.
 *
 * The same shape KitStore uses for kit.json, for the same reason — every
 * sample reference is a bare filename resolved against this folder, so the
 * folder can be copied, shared or backed up as one thing and still play.
 */
object SessionStore {

    const val FILE_NAME = "loop.json"
    const val VERSION = 1

    /**
     * Write-then-rename through [AtomicFile], never in place.
     *
     * The same reason `kit.json` goes through it: this file *is* the session,
     * and an in-place `writeText` that is interrupted — disk full, the OS
     * killing the app mid-save — truncates a session that was valid a moment
     * ago. What the app does next makes that worse rather than better: a
     * sidecar that will not parse stops the next send rather than overwriting
     * it, which is right, but it means a torn write is a grid nobody can
     * repair from inside the app. A reader now sees either the whole old
     * session or the whole new one.
     */
    fun save(session: Session, dir: File): File {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(session)) + "\n")
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): Session {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    private fun num(v: Number) = JsonValue.Num(v.toDouble())

    private fun toJson(session: Session): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "version" to num(VERSION),
            "bpm" to num(session.bpm),
            "barsPerInterval" to num(session.barsPerInterval),
            "sampleRate" to num(session.sampleRate),
            "tracks" to JsonValue.Arr(session.tracks.map { trackJson(it) }),
        ),
    )

    private fun trackJson(track: Track): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(track.name),
            "engaged" to JsonValue.Bool(track.engaged),
            "level" to num(track.level),
            "pan" to num(track.pan),
            "chain" to JsonValue.Arr(track.chain.map { blockJson(it) }),
        ),
    )

    private fun blockJson(block: Block): JsonValue = when (block) {
        is LoopBlock -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("loop"),
                "sampleFile" to JsonValue.Str(block.sampleFile),
            ),
        )
        is SilenceBlock -> JsonValue.Obj(linkedMapOf("type" to JsonValue.Str("silence")))
        is PatternBlock -> JsonValue.Obj(
            linkedMapOf(
                "type" to JsonValue.Str("pattern"),
                "kit" to JsonValue.Str(block.kit),
                "steps" to JsonValue.Arr(
                    block.steps.map {
                        JsonValue.Obj(
                            linkedMapOf(
                                "step" to num(it.step),
                                "slot" to num(it.slot),
                                "velocity" to num(it.velocity),
                                "microOffset" to num(it.microOffset),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun fromJson(root: JsonValue): Session {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw IllegalStateException("$FILE_NAME has no version")
        check(version == VERSION) { "$FILE_NAME is version $version, this build reads $VERSION" }

        return Session(
            tracks = obj["tracks"]?.arr().orEmpty().map { trackFrom(it) },
            bpm = (obj["bpm"]?.num() ?: 90.0).toFloat(),
            barsPerInterval = obj["barsPerInterval"]?.int() ?: 4,
            sampleRate = obj["sampleRate"]?.int() ?: 48_000,
        )
    }

    private fun trackFrom(value: JsonValue): Track {
        val t = value.obj()
        return Track(
            name = t["name"]?.str() ?: "",
            chain = t["chain"]?.arr().orEmpty().map { blockFrom(it) },
            engaged = t["engaged"]?.bool() ?: true,
            level = (t["level"]?.num() ?: 1.0).toFloat(),
            pan = (t["pan"]?.num() ?: 0.0).toFloat(),
        )
    }

    private fun blockFrom(value: JsonValue): Block {
        val b = value.obj()
        return when (val type = b["type"]?.str()) {
            "silence" -> SilenceBlock
            "loop" -> LoopBlock(b["sampleFile"]?.str() ?: throw IllegalStateException("loop block has no sampleFile"))
            "pattern" -> PatternBlock(
                kit = b["kit"]?.str() ?: throw IllegalStateException("pattern block has no kit"),
                steps = b["steps"]?.arr().orEmpty().map { stepFrom(it) },
            )
            else -> throw IllegalStateException("unknown block type '$type'")
        }
    }

    private fun stepFrom(value: JsonValue): Step {
        val s = value.obj()
        return Step(
            step = s["step"]?.int() ?: 0,
            slot = s["slot"]?.int() ?: 1,
            velocity = (s["velocity"]?.num() ?: 1.0).toFloat(),
            microOffset = s["microOffset"]?.int() ?: 0,
        )
    }
}
