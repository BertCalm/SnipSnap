package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved AEROSTAT sound. One voice, both tube banks, the six musical macros
 * plus TUNE (the host's requested note). Noise and the vessel's starting
 * state come from the voice and the note, so a recipe regenerates bit for bit.
 * A phrase that carries pressure between strikes is [Aerostat.phrase], not this.
 */
data class AerostatPatch(
    override val name: String,
    val voice: AerostatVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Aerostat.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Aerostat.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "AEROSTAT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> AerostatVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                AerostatPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): AerostatPatch = fromJsonValue(Json.parse(text)) as AerostatPatch
    }
}
