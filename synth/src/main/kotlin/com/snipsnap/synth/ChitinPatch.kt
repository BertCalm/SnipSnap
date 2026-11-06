package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** Saved finite-note voice and macros; no seamless sustain in model 1. */
data class ChitinPatch(
    override val name: String,
    val voice: ChitinVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Chitin.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Chitin.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "CHITIN"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ChitinVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ChitinPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): ChitinPatch = fromJsonValue(Json.parse(text)) as ChitinPatch
    }
}
