package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved TREMOR sound. The note is TUNE, the same way every other pitched engine stores pitch:
 * there is no second channel for it. Seeds come from the voice and the note (`Dsp.seedFor` inside
 * [Tremor.play]), so a recipe regenerates bit for bit. Velocity is not stored; [Tremor.render]
 * takes it as a number, and [Velocity] passes it through.
 */
data class TremorPatch(
    override val name: String,
    val voice: TremorVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Tremor.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tremor.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "TREMOR"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TremorVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TremorPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): TremorPatch = fromJsonValue(Json.parse(text)) as TremorPatch
    }
}
