package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved TESSERA object. TUNE stores its note; material, frame and chamber state are rebuilt
 * from the deterministic recipe on each render. Event velocity scales hammer energy through
 * [Velocity], leaving the saved HAMMER contact character intact.
 */
data class TesseraPatch(
    override val name: String,
    val voice: TesseraVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Tessera.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Tessera.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "TESSERA"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TesseraVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TesseraPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): TesseraPatch = fromJsonValue(Json.parse(text)) as TesseraPatch
    }
}
