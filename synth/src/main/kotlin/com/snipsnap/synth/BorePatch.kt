package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved BORE sound: voice (the valve and the bore it blows) + macro settings
 * + a hand-written name. Same shape as every other engine's patch - see
 * [Patches]. There is no `seed` anywhere in it: the turbulence is seeded from
 * the voice and the note by `Dsp.seedFor`, so a recipe regenerates bit for bit.
 */
data class BorePatch(
    override val name: String,
    val voice: BoreVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Bore.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Bore.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "BORE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> BoreVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                BorePatch(name, voice, macros)
            }

        fun fromJsonText(text: String): BorePatch = fromJsonValue(Json.parse(text)) as BorePatch
    }
}
