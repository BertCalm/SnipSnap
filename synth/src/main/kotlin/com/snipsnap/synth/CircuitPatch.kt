package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved CIRCUIT ensemble: deterministic voice and normalized macro settings. */
data class CircuitPatch(
    override val name: String,
    val voice: CircuitVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Circuit.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Circuit.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "CIRCUIT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> CircuitVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                CircuitPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): CircuitPatch = fromJsonValue(Json.parse(text)) as CircuitPatch
    }
}
