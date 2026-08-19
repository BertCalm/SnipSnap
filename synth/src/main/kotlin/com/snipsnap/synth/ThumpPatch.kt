package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved THUMP sound: voice + macro settings + a hand-written name.
 *
 * Serialized as JSON so a synth pad can keep its recipe next to its rendered
 * WAV (editable forever) and so patches can be shared as files. Embedding
 * into `kit.json` happens at the app layer; this is the format.
 */
data class ThumpPatch(
    val name: String,
    val voice: ThumpVoice,
    val macros: Map<String, Float>,
) {
    init {
        require(name.isNotBlank()) { "patch name must not be blank" }
        val known = Thump.macrosFor(voice).map { it.name }.toSet()
        for ((k, v) in macros) {
            require(k in known) { "unknown macro $k for $voice (knows $known)" }
            require(v in 0f..1f) { "macro $k out of 0..1: $v" }
        }
    }

    fun render() = Thump.render(voice, macros)

    fun toJsonText(): String = Json.write(
        JsonValue.Obj(
            linkedMapOf(
                "engine" to JsonValue.Str(ENGINE),
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(name),
                "voice" to JsonValue.Str(voice.name),
                "macros" to JsonValue.Obj(
                    macros.entries.associateTo(LinkedHashMap()) { (k, v) ->
                        k to JsonValue.Num(v.toDouble())
                    },
                ),
            ),
        ),
    )

    companion object {
        const val ENGINE = "THUMP"
        const val VERSION = 1

        fun fromJsonText(text: String): ThumpPatch {
            val obj = Json.parse(text).obj()
            val engine = obj["engine"]?.str() ?: throw JsonException("patch has no engine")
            if (engine != ENGINE) throw JsonException("not a $ENGINE patch: $engine")
            val version = obj["version"]?.int() ?: throw JsonException("patch has no version")
            if (version != VERSION) throw JsonException("unsupported patch version $version")
            val voiceName = obj["voice"]?.str() ?: throw JsonException("patch has no voice")
            val voice = ThumpVoice.entries.firstOrNull { it.name == voiceName }
                ?: throw JsonException("unknown voice $voiceName")
            val macros = (obj["macros"] as? JsonValue.Obj)?.entries
                ?.mapValues { (_, v) -> v.num().toFloat() }
                ?: emptyMap()
            return ThumpPatch(
                name = obj["name"]?.str() ?: throw JsonException("patch has no name"),
                voice = voice,
                macros = macros,
            )
        }
    }
}
