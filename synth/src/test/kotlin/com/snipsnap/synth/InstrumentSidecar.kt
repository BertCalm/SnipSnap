package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.KeygroupProgram
import java.io.File

/**
 * The instrument suite's regeneration sidecar — `instruments.json`,
 * mirroring the promise `kit.json` makes for kits: an instrument folder
 * carries enough to rebuild itself.
 *
 * The renders are deterministic (engines at exact MIDI pitch, seeded
 * everything), so the sidecar doesn't need to freeze audio — it records
 * **which engine, which zones, which layers, which loop points**, both as
 * provenance and as the spec a rebuilder (or the app's regenerate button)
 * follows. Static engine facts come from the recipe table here; per-zone
 * facts come from the rendered programs, so the two can never disagree
 * with what actually shipped.
 */
object InstrumentSidecar {

    const val FILE_NAME = "instruments.json"
    const val VERSION = 1

    /** Engine identity + how each zone's velocity layers were rendered. */
    private data class Recipe(val engine: String, val layerNotes: List<String>)

    private val RECIPES = mapOf(
        "SnipSnap EP" to Recipe(
            "tines-ep",
            listOf("soft = true render at bright 0.35 (vel 1-63)", "main = bright 0.8 (vel 64-127)"),
        ),
        "SnipSnap Organ" to Recipe(
            "tonewheel-soul-held",
            listOf("single layer; sustain loop cut at a whole number of periods of the 16' sub"),
        ),
        "SnipSnap Harp" to Recipe(
            "pluck-harp",
            listOf("soft = main softened 0.6 (vel 1-63)", "main (vel 64-127)"),
        ),
        "SnipSnap Music Box" to Recipe(
            "tines-chime-twins",
            listOf("single layer - one dynamic, wistful"),
        ),
    )

    fun describe(programs: List<KeygroupProgram>): JsonValue.Obj {
        val instruments = programs.map { program ->
            val recipe = requireNotNull(RECIPES[program.name]) {
                "no recipe on record for '${program.name}' - add it before shipping the sidecar"
            }
            JsonValue.Obj(
                linkedMapOf(
                    "name" to JsonValue.Str(program.name),
                    "engine" to JsonValue.Str(recipe.engine),
                    "layers" to JsonValue.Arr(recipe.layerNotes.map { JsonValue.Str(it) }),
                    "volumeRelease" to JsonValue.Num(program.volumeRelease.toDouble()),
                    "zones" to JsonValue.Arr(
                        program.keygroups.map { kg ->
                            JsonValue.Obj(
                                linkedMapOf(
                                    "rootMidi" to JsonValue.Num(kg.rootNote.toDouble()),
                                    "note" to JsonValue.Str(Scales.nameOf(kg.rootNote)),
                                    "lowNote" to JsonValue.Num(kg.lowNote.toDouble()),
                                    "highNote" to JsonValue.Num(kg.highNote.toDouble()),
                                    "samples" to JsonValue.Arr(
                                        kg.layers.map { layer ->
                                            JsonValue.Obj(
                                                linkedMapOf(
                                                    "sample" to JsonValue.Str(layer.sampleName),
                                                    "frames" to JsonValue.Num(layer.frameCount.toDouble()),
                                                    "velStart" to JsonValue.Num(layer.velStart.toDouble()),
                                                    "velEnd" to JsonValue.Num(layer.velEnd.toDouble()),
                                                    "loopStartFrame" to JsonValue.Num(layer.loopStartFrame.toDouble()),
                                                ),
                                            )
                                        },
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        return JsonValue.Obj(
            linkedMapOf(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "regenerate" to JsonValue.Str("./gradlew :synth:generateInstrumentSuite - renders are deterministic"),
                "instruments" to JsonValue.Arr(instruments),
            ),
        )
    }

    fun write(dir: File, programs: List<KeygroupProgram>): File {
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        file.writeText(Json.write(describe(programs)) + "\n")
        return file
    }
}
