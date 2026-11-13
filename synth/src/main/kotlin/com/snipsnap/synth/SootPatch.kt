package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved SOOT sound: voice + macro settings + a hand-written name, and the renderer [model] it was
 * made with, so a later round can keep old saves sounding as they did (BREAKER's shape).
 */
data class SootPatch(
    override val name: String,
    val voice: SootVoice,
    override val macros: Map<String, Float>,
    val model: Int = Soot.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Soot.macrosFor(voice, model))
        require(model in 1..Soot.MODEL_VERSION) { "unsupported soot model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] re-strikes the drum; take 0 is the reference hit. */
    fun render(velocity: Float, take: Int = 0) = Soot.render(voice, macros, velocity, take, model)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SOOT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SootVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SootPatch(name, voice, macros, value.obj()["model"]?.int() ?: Soot.MODEL_VERSION)
            }

        fun fromJsonText(text: String): SootPatch = fromJsonValue(Json.parse(text)) as SootPatch
    }
}
