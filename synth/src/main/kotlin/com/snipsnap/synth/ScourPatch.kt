package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved SCOUR sound. TUNE carries the note and every draw is seeded from the voice, so saving and
 * reopening a partial macro map regenerates the same sample. Velocity arrives through [Velocity] as
 * shot speed, as for the other struck melodic engines.
 */
data class ScourPatch(
    override val name: String,
    val voice: ScourVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Scour.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Scour.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "SCOUR"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ScourVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ScourPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): ScourPatch = fromJsonValue(Json.parse(text)) as ScourPatch
    }
}
