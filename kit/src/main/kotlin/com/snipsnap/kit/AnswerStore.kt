package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File

/**
 * The kit's answer, persisted — `answer.json` beside `kit.json`, holding
 * the generated bassline clip and pointing at its rendered bass note (a
 * plain WAV in the kit folder). The keygroup program itself is *not*
 * stored: it rebuilds deterministically from the WAV via `OneNote`, the
 * same way any pitched note becomes an instrument.
 */
object AnswerStore {

    const val FILE_NAME = "answer.json"
    const val VERSION = 1

    /** One sideman the band brought: its track name, note file, and line. */
    data class BandMember(
        val name: String,
        val sampleFile: String,
        val clip: Mpc3Clip,
    ) {
        init {
            require(name.isNotBlank()) { "band member name must not be blank" }
            require('/' !in sampleFile && '\\' !in sampleFile) {
                "band sample must be a bare filename inside the kit folder: $sampleFile"
            }
        }
    }

    data class StoredAnswer(
        /** The reroll seed that produced this answer. */
        val seed: Int,
        /** Instrument/track name, MPC-safe (e.g. "Chamber Answer"). */
        val name: String,
        /** The rendered bass note's file, bare name inside the kit folder. */
        val sampleFile: String,
        /** The bassline. */
        val clip: Mpc3Clip,
        /** The sidemen, when the answer grew into a band. */
        val band: List<BandMember> = emptyList(),
    ) {
        init {
            require(name.isNotBlank()) { "answer name must not be blank" }
            require('/' !in sampleFile && '\\' !in sampleFile) {
                "answer sample must be a bare filename inside the kit folder: $sampleFile"
            }
        }
    }

    fun save(kitDir: File, answer: StoredAnswer): File {
        kitDir.mkdirs()
        val file = File(kitDir, FILE_NAME)
        val root = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "seed" to JsonValue.Num(answer.seed.toDouble()),
            "name" to JsonValue.Str(answer.name),
            "sample" to JsonValue.Str(answer.sampleFile),
            "clip" to GrooveStore.clipToJson(answer.clip),
        )
        if (answer.band.isNotEmpty()) {
            root["band"] = JsonValue.Arr(
                answer.band.map { m ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "name" to JsonValue.Str(m.name),
                            "sample" to JsonValue.Str(m.sampleFile),
                            "clip" to GrooveStore.clipToJson(m.clip),
                        ),
                    )
                },
            )
        }
        AtomicFile.writeText(file, Json.write(JsonValue.Obj(root)) + "\n")
        return file
    }

    /** The kit's answer, or null when it has none. */
    fun load(kitDir: File): StoredAnswer? {
        val file = File(kitDir, FILE_NAME)
        if (!file.isFile) return null
        val obj = (Json.parse(file.readText(Charsets.UTF_8)) as JsonValue.Obj).entries
        val version = (obj["version"] as? JsonValue.Num)?.value?.toInt()
        require(version == VERSION) {
            "answer.json version $version is not supported (this build reads $VERSION)"
        }
        return StoredAnswer(
            seed = (obj["seed"] as JsonValue.Num).value.toInt(),
            name = (obj["name"] as JsonValue.Str).value,
            sampleFile = (obj["sample"] as JsonValue.Str).value,
            clip = GrooveStore.clipFromJson(obj["clip"] ?: throw IllegalArgumentException("answer.json has no clip")),
            band = (obj["band"] as? JsonValue.Arr)?.items.orEmpty().map { memberJson ->
                val m = (memberJson as JsonValue.Obj).entries
                BandMember(
                    name = (m["name"] as JsonValue.Str).value,
                    sampleFile = (m["sample"] as JsonValue.Str).value,
                    clip = GrooveStore.clipFromJson(m["clip"] ?: throw IllegalArgumentException("band member has no clip")),
                )
            },
        )
    }

    fun delete(kitDir: File): Boolean = File(kitDir, FILE_NAME).delete()
}
