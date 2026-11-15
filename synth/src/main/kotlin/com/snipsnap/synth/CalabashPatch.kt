package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved CALABASH pad: the voice, its macros, how hard it was struck and which of four takes it is, so a kit's
 * velocity zones and round-robin takes replay byte for byte from their recipe. The model version travels with
 * it so a later re-voicing cannot silently change a saved pad (the CLUTTER and SCUFF shape).
 */
data class CalabashPatch(
    override val name: String,
    val voice: CalabashVoice,
    override val macros: Map<String, Float>,
    val velocity: Float = 1f,
    val take: Int = 0,
    val model: Int = Calabash.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Calabash.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "CALABASH velocity out of 0..1: $velocity" }
        require(take in 0..Calabash.MAX_TAKE) { "CALABASH take out of 0..${Calabash.MAX_TAKE}: $take" }
        require(model == Calabash.MODEL_VERSION) { "CALABASH model $model is not the one this build renders (${Calabash.MODEL_VERSION})" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Calabash.render(voice, macros, velocity, take)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["take"] = JsonValue.Num(take.toDouble())
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "CALABASH"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val take = obj["take"]?.takeUnless { it is JsonValue.Null }?.int() ?: 0
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Calabash.MODEL_VERSION
            return Patches.decode(value, ENGINE, { n -> CalabashVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                CalabashPatch(name, voice, macros, velocity, take, model)
            }
        }

        fun fromJsonText(text: String): CalabashPatch = fromJsonValue(Json.parse(text)) as CalabashPatch
    }
}
