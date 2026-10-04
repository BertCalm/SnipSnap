package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved MERCURY sound: voice (the object's family) + macro settings + a hand-written
 * name. Same shape as every other engine's patch - see [Patches]. There is no `seed`
 * in it: the strike's burst and the water's start are seeded from the voice and the
 * note by `Dsp.seedFor`, so a recipe regenerates bit for bit.
 */
data class MercuryPatch(
    override val name: String,
    val voice: MercuryVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Mercury.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Mercury.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "MERCURY"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> MercuryVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                MercuryPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): MercuryPatch = fromJsonValue(Json.parse(text)) as MercuryPatch
    }
}
