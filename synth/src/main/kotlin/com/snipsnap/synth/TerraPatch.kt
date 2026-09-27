package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved TERRA sound: voice (topology) + macro settings + a hand-written
 * name. Same shape as every other engine's patch - see [Patches].
 */
data class TerraPatch(
    override val name: String,
    val voice: TerraVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Terra.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Terra.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "TERRA"

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> TerraVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TerraPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): TerraPatch = fromJsonValue(Json.parse(text)) as TerraPatch
    }
}
