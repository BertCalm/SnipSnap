package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved Samba candidate sound: voice + macro settings + a hand-written name, and the renderer [model] it was
 * made with, so a later round can keep old saves sounding as they did (BREAKER's shape).
 */
data class SambaPatch(
    override val name: String,
    val voice: ParadeSamba.Voice,
    override val macros: Map<String, Float>,
    val model: Int = ParadeSamba.MODEL,
) : Patch {
    init {
        Patches.validateMacros(this, SambaPatch.macrosFor())
        require(model == ParadeSamba.MODEL) { "unsupported samba model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)

    /** [take] re-strikes the drum; take 0 is the reference hit. */
    fun render(velocity: Float, take: Int = 0) = ParadeSamba.render(voice, macros, velocity, take)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        return JsonValue.Obj(obj)
    }

    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SAMBA"
        const val VERSION = Patches.VERSION
        fun macrosFor() = ParadeSamba.defaults().map { (k,v) -> MacroSpec(k,v) }

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ParadeSamba.Voice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SambaPatch(name, voice, macros, value.obj()["model"]?.int() ?: 1)
            }

        fun fromJsonText(text: String): SambaPatch = fromJsonValue(Json.parse(text)) as SambaPatch
    }
}
