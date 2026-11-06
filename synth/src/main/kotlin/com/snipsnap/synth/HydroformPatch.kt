package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A replayable HYDROFORM voice, continuous macros and source touch. */
data class HydroformPatch(override val name: String, val voice: HydroformVoice,
                          override val macros: Map<String, Float>, val velocity: Float = 1f) : Patch {
    init {
        Patches.validateMacros(this, Hydroform.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f)
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Hydroform.render(voice, macros, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["velocity"] = JsonValue.Num(velocity.toDouble())
        it["model"] = JsonValue.Num(Hydroform.MODEL_VERSION.toDouble())
    })
    companion object {
        const val ENGINE = "HYDROFORM"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: throw JsonException("HYDROFORM has no model")
            if (model != Hydroform.MODEL_VERSION) throw JsonException("unsupported HYDROFORM model $model")
            val v = obj["velocity"]?.num() ?: throw JsonException("HYDROFORM has no velocity")
            if (!v.isFinite() || v !in 0.0..1.0) throw JsonException("HYDROFORM velocity outside 0..1")
            return Patches.decode(value, ENGINE, { n -> HydroformVoice.entries.firstOrNull { it.name == n } }) { n, voice, macros ->
                HydroformPatch(n, voice, macros, v.toFloat())
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as HydroformPatch
    }
}
