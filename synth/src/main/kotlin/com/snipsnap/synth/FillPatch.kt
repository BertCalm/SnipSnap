package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved FILL. The schedule has no randomness, so saving and reopening a partial macro map regenerates
 * the same fill. Velocity arrives through [Velocity] and scales the whole run: SKIN's drums get softer and
 * darker together, and the crescendo keeps its shape.
 */
data class FillPatch(
    override val name: String,
    val voice: FillVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Fill.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Fill.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "FILL"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> FillVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FillPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): FillPatch = fromJsonValue(Json.parse(text)) as FillPatch
    }
}
