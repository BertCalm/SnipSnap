package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** Saved finite strike with explicit render model; missing model replays R1. HOLD is reserved; there is no implicit loop. */
data class TempestPatch(
    override val name: String,
    val voice: TempestVoice,
    override val macros: Map<String, Float>,
    val model: Int = Tempest.defaultModelFor(voice),
) : Patch {
    init {
        require(Tempest.supports(voice, model)) { "unsupported TEMPEST $voice model $model" }
        Patches.validateMacros(this, Tempest.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tempest.render(voice, macros, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also { it["model"] = JsonValue.Num(model.toDouble()) })

    companion object {
        const val ENGINE = "TEMPEST"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TempestVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TempestPatch(name, voice, macros, value.obj()["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1)
            }

        fun fromJsonText(text: String): TempestPatch = fromJsonValue(Json.parse(text)) as TempestPatch
    }
}
