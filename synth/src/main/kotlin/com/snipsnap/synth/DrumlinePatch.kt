package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved DRUMLINE sound: voice + macro settings + a hand-written name, and the renderer [model] it was
 * made with, so a later round can keep old saves sounding as they did (BREAKER's shape).
 */
data class DrumlinePatch(
    override val name: String,
    val voice: DrumlineVoice,
    override val macros: Map<String, Float>,
    val model: Int = Drumline.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Drumline.macrosFor(voice))
        require(model == Drumline.MODEL_VERSION) { "unsupported drumline model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] re-strikes the drum; take 0 is the reference hit. */
    fun render(velocity: Float, take: Int = 0) = Drumline.render(voice, macros, velocity, take, model)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "DRUMLINE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> DrumlineVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                DrumlinePatch(name, voice, macros, value.obj()["model"]?.int() ?: Drumline.MODEL_VERSION)
            }

        fun fromJsonText(text: String): DrumlinePatch = fromJsonValue(Json.parse(text)) as DrumlinePatch
    }
}
