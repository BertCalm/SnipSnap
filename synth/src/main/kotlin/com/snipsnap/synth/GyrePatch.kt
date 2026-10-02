package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved GYRE sound: voice + macro settings + a hand-written name, the same shape as every other
 * engine's patch ([Patches]). No seed: the plucks are seeded from the voice, the note and the
 * string by `Dsp.seedFor`, so a recipe regenerates bit for bit. A recipe without TOUCH (every
 * round-one recipe) is a pure pluck, and stays one when R2 adds TOUCH.
 */
data class GyrePatch(
    override val name: String,
    val voice: GyreVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Gyre.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Gyre.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "GYRE"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> GyreVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                GyrePatch(name, voice, macros)
            }

        fun fromJsonText(text: String): GyrePatch = fromJsonValue(Json.parse(text)) as GyrePatch
    }
}
