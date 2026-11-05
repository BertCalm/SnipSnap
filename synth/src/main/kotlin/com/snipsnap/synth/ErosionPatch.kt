package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Native one-shot recipe. Model and playing velocity survive JSON and edits. */
data class ErosionPatch(
    override val name: String,
    val voice: ErosionVoice,
    override val macros: Map<String, Float> = Erosion.defaults(voice),
    val velocity: Float = 1f,
    val model: Int = Erosion.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Erosion.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "EROSION velocity must be finite in 0..1" }
        require(model == Erosion.MODEL_VERSION) { "unsupported erosion model $model" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Erosion.render(voice, macros, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["velocity"] = JsonValue.Num(velocity.toDouble())
        it["model"] = JsonValue.Num(model.toDouble())
    })
    companion object {
        const val ENGINE = "EROSION"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: throw JsonException("EROSION patch has no model")
            val velocity = obj["velocity"]?.num()?.toFloat() ?: throw JsonException("EROSION patch has no velocity")
            if (model != Erosion.MODEL_VERSION) throw JsonException("unsupported erosion model $model")
            if (!velocity.isFinite() || velocity !in 0f..1f) throw JsonException("EROSION velocity outside 0..1")
            return Patches.decode(value, ENGINE, { n -> ErosionVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ErosionPatch(name, voice, macros, velocity, model)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as ErosionPatch
    }
}
