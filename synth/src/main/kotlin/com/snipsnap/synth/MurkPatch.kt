package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved MURK sound. TUNE carries the note, and the engine derives its structural and behavioral
 * seeds from the settled recipe, so saving and reopening a partial macro map regenerates the same
 * sample. Event velocity is supplied through [Velocity], as for the other struck melodic engines.
 */
data class MurkPatch(
    override val name: String,
    val voice: MurkVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Murk.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Murk.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "MURK"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> MurkVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                MurkPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): MurkPatch = fromJsonValue(Json.parse(text)) as MurkPatch
    }
}
