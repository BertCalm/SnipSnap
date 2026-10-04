package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved BALLAST sound: voice (how the structure is set up) + macro settings + a hand-written name. Same shape as
 * every other engine's patch - see [Patches]. There is no `seed` in it: the oscillators' phases, the strings' tiny
 * detunes and the tiles' resting positions are seeded from the voice and the note by `Dsp.seedFor`, so a recipe
 * regenerates bit for bit.
 */
data class BallastPatch(
    override val name: String,
    val voice: BallastVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Ballast.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Ballast.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "BALLAST"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> BallastVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                BallastPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): BallastPatch = fromJsonValue(Json.parse(text)) as BallastPatch
    }
}
