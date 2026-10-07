package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved COROLLA sound. TUNE stores the note and the deterministic render derives its seed
 * from that note and voice. Event velocity belongs to [Corolla.render], not the saved recipe.
 */
data class CorollaPatch(
    override val name: String,
    val voice: CorollaVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Corolla.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Corolla.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "COROLLA"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> CorollaVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                CorollaPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): CorollaPatch = fromJsonValue(Json.parse(text)) as CorollaPatch
    }
}
