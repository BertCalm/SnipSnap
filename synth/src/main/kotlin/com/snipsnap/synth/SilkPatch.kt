package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved SILK sound - same contract as [ThumpPatch], different engine tag. */
data class SilkPatch(
    override val name: String,
    val voice: SilkVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Silk.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Silk.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SILK"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> SilkVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                SilkPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): SilkPatch = fromJsonValue(Json.parse(text)) as SilkPatch
    }
}
