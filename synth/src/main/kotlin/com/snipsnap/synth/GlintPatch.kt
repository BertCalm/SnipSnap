package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved GLINT sound — same contract as [TinesPatch], different engine tag. */
data class GlintPatch(
    override val name: String,
    val voice: GlintVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Glint.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Glint.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "GLINT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> GlintVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                GlintPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
