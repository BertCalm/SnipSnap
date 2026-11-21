package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved KERF sound: voice + macro settings + a hand-written name, and the engine [model] it was made with, so a
 * later round can keep old saves sounding as they did (SOOT's shape). The fitted numbers stay in the bank
 * ([KerfBank]); the patch names a hit by its HIT macro (the spec's decision A).
 */
data class KerfPatch(
    override val name: String,
    val voice: KerfVoice,
    override val macros: Map<String, Float>,
    val model: Int = Kerf.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Kerf.macrosFor(voice, model))
        require(model in 1..Kerf.MODEL_VERSION) { "unsupported kerf model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] cuts the hit again with another knife and other noise; take 0 is the reference hit. */
    fun render(velocity: Float, take: Int = 0) = Kerf.render(voice, macros, velocity, take, model)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "KERF"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> KerfVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                // a save without a model is from before model 2 existed, so it is model 1
                KerfPatch(name, voice, macros, value.obj()["model"]?.int() ?: 1)
            }

        fun fromJsonText(text: String): KerfPatch = fromJsonValue(Json.parse(text)) as KerfPatch
    }
}
