package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved ARCO sound: voice (the string and the box it is bowed into) + macro
 * settings + a hand-written name. Same shape as every other engine's patch - see
 * [Patches]. There is no `seed` anywhere in it, and no noise either: a bow needs
 * none (the spike's 1 percent of noise on the bow velocity changed nothing), so a
 * recipe regenerates bit for bit from the macros alone.
 */
data class ArcoPatch(
    override val name: String,
    val voice: ArcoVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Arco.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Arco.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "ARCO"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> ArcoVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                ArcoPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): ArcoPatch = fromJsonValue(Json.parse(text)) as ArcoPatch
    }
}
