package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A saved six-cymbal stack; event velocity is supplied separately through [Velocity]. */
data class NimbusPatch(
    override val name: String,
    val voice: NimbusVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Nimbus.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Nimbus.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "NIMBUS"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> NimbusVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                NimbusPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): NimbusPatch = fromJsonValue(Json.parse(text)) as NimbusPatch
    }
}
