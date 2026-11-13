package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved SCUFF pad: voice, macros, how hard it was hit and which take it is, so a kit's velocity zones and
 * round-robin takes replay byte for byte from their recipe. The model version travels with it so a later
 * re-voicing cannot silently change a saved pad.
 */
data class ScuffPatch(
    override val name: String,
    val voice: ScuffVoice,
    override val macros: Map<String, Float>,
    val velocity: Float = 1f,
    val take: Int = 0,
    val model: Int = Scuff.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Scuff.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "SCUFF velocity out of 0..1: $velocity" }
        require(take in 0..Scuff.MAX_TAKE) { "SCUFF take out of 0..${Scuff.MAX_TAKE}: $take" }
        require(model == Scuff.MODEL_VERSION) { "SCUFF model $model is not the one this build renders (${Scuff.MODEL_VERSION})" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Scuff.render(voice, macros, velocity, take)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["take"] = JsonValue.Num(take.toDouble())
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "SCUFF"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val take = obj["take"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Scuff.MODEL_VERSION
            return Patches.decode(value, ENGINE, { n -> ScuffVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ScuffPatch(name, voice, macros, velocity, take, model)
            }
        }

        fun fromJsonText(text: String): ScuffPatch = fromJsonValue(Json.parse(text)) as ScuffPatch
    }
}
