package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved MAGNET sound - same contract as the other engines' patches, different engine tag. */
data class MagnetPatch(
    override val name: String,
    val voice: MagnetVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Magnet.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Magnet.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "MAGNET"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> MagnetVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                MagnetPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): MagnetPatch = fromJsonValue(Json.parse(text)) as MagnetPatch
    }
}
