package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved BACKWASH sound: voice + macro settings + a hand-written name.
 *
 * Serialized as JSON (shape shared by every engine via [Patches]) so a synth
 * pad can keep its recipe next to its rendered WAV (editable forever) and so
 * patches can be shared as files. Embedding into `kit.json` happens through
 * [PadRecipe]; this is the per-engine format.
 */
data class BackwashPatch(
    override val name: String,
    val voice: BackwashVoice,
    override val macros: Map<String, Float>,
    val model: Int = Backwash.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Backwash.macrosFor(voice))
        require(model != 3 || voice.legacy) { "model 3 has no ${voice.name} voice" }
        require(model in 3..Backwash.MODEL_VERSION) { "unsupported backwash model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)
    fun render(velocity: Float) = when (model) {
        3 -> BackwashV3.render(voice, macros, velocity)
        4 -> Backwash.render(voice, macros, velocity)
        else -> error("unsupported backwash model $model")
    }
    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "BACKWASH"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> BackwashVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                BackwashPatch(name, voice, macros, value.obj()["model"]?.int() ?: 3)
            }

        fun fromJsonText(text: String): BackwashPatch = fromJsonValue(Json.parse(text)) as BackwashPatch
    }
}
