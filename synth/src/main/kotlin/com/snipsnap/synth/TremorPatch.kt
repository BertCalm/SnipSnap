package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved TREMOR sound. The note is TUNE, the same way every other pitched engine stores pitch:
 * there is no second channel for it. Seeds come from the voice and the note (`Dsp.seedFor` inside
 * [Tremor.play]), so a recipe regenerates bit for bit. Velocity is not stored; [Tremor.render]
 * takes it as a number, and [Velocity] passes it through. New sounds use the current model;
 * recipes saved before model selection existed retain the original renderer.
 */
data class TremorPatch(
    override val name: String,
    val voice: TremorVoice,
    override val macros: Map<String, Float>,
    val model: Int = Tremor.MODEL,
) : Patch {
    init {
        Patches.validateMacros(this, Tremor.macrosFor(voice))
        require(model in Tremor.LEGACY_MODEL..Tremor.MODEL) { "unsupported tremor model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tremor.render(voice, macros, model = model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            put("model", JsonValue.Num(model.toDouble()))
        },
    )

    companion object {
        const val ENGINE = "TREMOR"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val model = value.obj()["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: Tremor.LEGACY_MODEL
            if (model !in Tremor.LEGACY_MODEL..Tremor.MODEL) throw JsonException("unsupported tremor model $model")
            return Patches.decode(value, ENGINE, { n -> TremorVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TremorPatch(name, voice, macros, model)
            }
        }

        fun fromJsonText(text: String): TremorPatch = fromJsonValue(Json.parse(text)) as TremorPatch
    }
}
